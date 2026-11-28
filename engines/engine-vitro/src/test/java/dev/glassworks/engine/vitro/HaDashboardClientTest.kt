/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.auth.HaSocket
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HistoryPoint
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.DashboardSummary
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [HaDashboardClient] against [FakeHaServer], an in-memory Home Assistant WebSocket API, over the whole protocol:
 * auth, config, themes, dashboards, resources, Lovelace config, view-scoped entity subscriptions, services,
 * generic commands, history and templates. The client runs its real reader thread; tests wait on the wire.
 */
class HaDashboardClientTest {

    private class RecordingListener : HaDashboardClient.Listener {
        val connected = CopyOnWriteArrayList<String>()
        val authRequired = AtomicInteger(0)
        val dashboards = CopyOnWriteArrayList<List<DashboardSummary>>()
        val resources = CopyOnWriteArrayList<List<LovelaceResource>>()
        val themes = CopyOnWriteArrayList<ThemeCatalog>()
        val configs = CopyOnWriteArrayList<Pair<String?, DashboardConfig>>()
        val disconnected = CopyOnWriteArrayList<String>()
        val userThemes = CopyOnWriteArrayList<Pair<String?, Boolean?>>()
        val failedConfigs = CopyOnWriteArrayList<Pair<String?, String>>()
        val users = CopyOnWriteArrayList<JSONObject>()
        val refused = CopyOnWriteArrayList<String?>()

        override fun onConnected(haVersion: String) { connected += haVersion }
        override fun onAuthRequired() { authRequired.incrementAndGet() }
        override fun onDashboards(dashboards: List<DashboardSummary>) { this.dashboards += dashboards }
        override fun onResources(resources: List<LovelaceResource>) { this.resources += resources }
        override fun onThemes(catalog: ThemeCatalog) { themes += catalog }
        override fun onDashboardConfig(urlPath: String?, config: DashboardConfig) { configs += urlPath to config }
        override fun onDashboardConfigFailed(urlPath: String?, code: String) { failedConfigs += urlPath to code }
        override fun onDisconnected(reason: String) { disconnected += reason }
        override fun onUserTheme(themeName: String?, dark: Boolean?) { userThemes += themeName to dark }
        val localeChanges = AtomicInteger(0)
        override fun onLocaleChanged() { localeChanges.incrementAndGet() }
        override fun onCurrentUser(user: JSONObject) { users += user }
        override fun onDashboardRefused(urlPath: String?) { refused += urlPath }
    }

    private val server = FakeHaServer()
    private val store = EntityStore()
    private val listener = RecordingListener()
    private val clients = CopyOnWriteArrayList<HaDashboardClient>()
    private val tokenCalls = CopyOnWriteArrayList<Boolean>()

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        server.shutdown()
        HaDisplay.reset()
        VitroFailures.listener = null
    }

    /** Wraps a [FakeHaServer] socket to watch (and, with [after], answer or fail) each message the client writes. */
    private class SpySocket(
        private val inner: HaSocket,
        private val before: (JSONObject) -> Unit = {},
        private val after: (JSONObject) -> Unit = {},
    ) : HaSocket {
        override fun send(text: String) {
            val msg = JSONObject(text)
            before(msg)
            inner.send(text)
            after(msg)
        }

        override fun receive(): String? = inner.receive()
        override fun close() = inner.close()
    }

    private fun newClient(
        haBase: String = FakeHaServer.BASE,
        connect: (String) -> HaSocket = server::connect,
        tokens: (force: Boolean, done: (String?) -> Unit) -> Unit = { force, done ->
            tokenCalls += force
            done(FakeHaServer.TOKEN)
        },
        authTimeoutMs: Long = HaDashboardClient.AUTH_TIMEOUT_MS,
        keepAliveMs: Long = HaDashboardClient.KEEP_ALIVE_MS,
        pongTimeoutMs: Long = HaDashboardClient.PONG_TIMEOUT_MS,
        configRetryMs: LongArray = HaDashboardClient.CONFIG_RETRY_MS,
    ): HaDashboardClient {
        val c = HaDashboardClient(
            haBase = haBase,
            tokenProvider = tokens,
            store = store,
            connect = connect,
            listener = listener,
            authTimeoutMs = authTimeoutMs,
            keepAliveMs = keepAliveMs,
            pongTimeoutMs = pongTimeoutMs,
            configRetryMs = configRetryMs,
        )
        clients += c
        return c
    }

    /**
     * A started client with the whole startup sequence done (the first entity subscription is on the wire). Its view
     * follows every entity, picked before the start, as a reconnect finds it.
     */
    private fun connected(
        dashboardPath: String? = null,
        connect: (String) -> HaSocket = server::connect,
        configRetryMs: LongArray = HaDashboardClient.CONFIG_RETRY_MS,
    ): HaDashboardClient {
        val c = newClient(connect = connect, configRetryMs = configRetryMs)
        c.subscribeViewEntities(emptySet())
        c.start(dashboardPath)
        awaitCondition("startup sequence") {
            server.commands("subscribe_entities").isNotEmpty() && listener.configs.isNotEmpty() &&
                listener.resources.isNotEmpty() && listener.dashboards.isNotEmpty() && listener.themes.isNotEmpty()
        }
        return c
    }

    // ---- Handshake ----

    @Test
    fun authenticatesWithTheTokenAndReportsTheHaVersion() {
        newClient().start(null)
        awaitCondition("connected") { listener.connected.isNotEmpty() }
        assertEquals(listOf("2026.11.0"), listener.connected.toList())
        assertEquals(listOf("ws://192.0.2.10:8123/api/websocket"), server.connectUrls.toList())
        val auth = server.received.first()
        assertEquals("auth", auth.getString("type"))
        assertEquals(FakeHaServer.TOKEN, auth.getString("access_token"))
        assertEquals(listOf(false), tokenCalls.toList()) // no forced refresh when the token works
        assertEquals(0, listener.authRequired.get())
    }

    @Test
    fun httpsBecomesWssAndAPortAndPathAreDropped() {
        newClient(haBase = "https://homeassistant.local:8443/dashboard/ignored").start(null)
        awaitCondition("connect") { server.connectUrls.isNotEmpty() }
        assertEquals(listOf("wss://homeassistant.local:8443/api/websocket"), server.connectUrls.toList())
    }

    @Test
    fun startupSendsEachRequestInOrderWithIncreasingIds() {
        connected()
        // The last startup request: the panels' answer may release the rest before it's written.
        awaitCondition("panels_updated subscribed") { server.eventSubscriptionId("panels_updated") != null }
        val sent = server.received.toList().drop(1) // after `auth`
        // First the user's language and formats (HA's translations-mixin subscribes on connect): the translations
        // asked for later are in that language.
        assertEquals(
            listOf(
                "frontend/subscribe_user_data", "get_config", "frontend/get_themes", "subscribe_events", "lovelace/dashboards/list",
                "lovelace/resources", "lovelace/config", "subscribe_events", "subscribe_entities",
            ),
            sent.take(9).map { it.getString("type") },
        )
        assertEquals("language", sent[0].getString("key"))
        assertEquals((1..9).toList(), sent.take(9).map { it.getInt("id") })
        // After the user's: their panels (what HA's frontend opens for them), and HA's changes to them.
        assertEquals(listOf("get_panels", "subscribe_events"), sent.drop(12).take(2).map { it.getString("type") })
        assertEquals("panels_updated", sent[13].getString("event_type"))
        // Then the registries native cards draw with, followed on every connection.
        awaitCondition("registry events subscribed") { server.eventSubscriptionId("area_registry_updated") != null }
        assertEquals(
            listOf("themes_updated", "lovelace_updated", "panels_updated", "entity_registry_updated", "device_registry_updated", "area_registry_updated"),
            server.commands("subscribe_events").map { it.getString("event_type") },
        )
    }

    @Test
    fun getConfigIsKeptForCustomCards() {
        val c = connected()
        awaitCondition("haConfig") { c.haConfig.optString("location_name") == "Test Home" }
        assertEquals("UTC", c.haConfig.getString("time_zone"))
        assertEquals("°C", c.haConfig.getJSONObject("unit_system").getString("temperature"))
    }

    @Test
    fun themesAreParsedIntoACatalog() {
        connected()
        val catalog = listener.themes[0]
        assertEquals("default", catalog.defaultTheme)
        assertEquals("glass_night", catalog.defaultDarkTheme)
        assertEquals("#ff5722", catalog.themes.getValue("glass_night").base["primary-color"])
        assertEquals("#ffffff", catalog.themes.getValue("night").dark["primary-text-color"])
    }

    @Test
    fun dashboardListSkipsEntriesWithoutAPath() {
        connected()
        assertEquals(
            listOf(
                DashboardSummary("wall-test", "Wall Test", "mdi:tablet", requireAdmin = false),
                DashboardSummary("admin-only", "admin-only", null, requireAdmin = true), // the title falls back to the path
            ),
            listener.dashboards.single(),
        )
    }

    @Test
    fun resourcesKeepUrlAndTypeAndDefaultToModule() {
        connected()
        assertEquals(
            listOf(
                LovelaceResource("/hacsfiles/acme/acme-card.js", "module"),
                LovelaceResource("/local/island.css", "css"),
                LovelaceResource("/local/legacy.js", "module"),
            ),
            listener.resources.single(), // the entry without a url is skipped
        )
    }

    // ---- Signed-in user and their selected theme ----

    /**
     * A socket that answers the user-data commands [FakeHaServer] does not know: `auth/current_user`, then
     * `frontend/get_user_data` with [userData] (the `core` value, or null for none) and `frontend/subscribe_user_data`.
     */
    private fun userDataConnect(currentUser: JSONObject, userData: JSONObject?): (String) -> HaSocket = { url ->
        val inner = server.connect(url)
        val socket = server.sockets.last()
        fun reply(id: Int, result: Any) {
            socket.push(JSONObject().put("id", id).put("type", "result").put("success", true).put("result", result))
        }
        // The answer is queued before the fake server's own "unknown_command" failure, which then finds no waiter.
        SpySocket(inner, before = { msg ->
            val id = msg.optInt("id", -1)
            when (msg.optString("type")) {
                "auth/current_user" -> reply(id, currentUser)
                "frontend/get_user_data" -> reply(id, JSONObject().put("value", userData ?: JSONObject.NULL))
                "frontend/subscribe_user_data" -> reply(id, JSONObject.NULL)
            }
        })
    }

    @Test
    fun currentUserIsStoredFromAuthCurrentUser() {
        val user = JSONObject("""{"id": "u1", "name": "Wall Panel", "is_admin": false, "is_owner": false}""")
        val c = connected(connect = userDataConnect(user, null))
        // The last user request on the wire: its reply can't be read before the command is recorded.
        awaitCondition("user data subscribed") { server.commands("frontend/subscribe_user_data").isNotEmpty() }
        awaitCondition("currentUser") { c.currentUser.optString("id") == "u1" }
        assertEquals("Wall Panel", c.currentUser.getString("name"))
        assertFalse(c.currentUser.getBoolean("is_admin"))
        assertEquals(JSONObject().toString(), newClient().currentUser.toString()) // empty until it arrives
        assertEquals(1, server.commands("auth/current_user").size)
        // The page hears of it, so islands whose pages stay get the user as it arrives (just after it's stored).
        awaitCondition("the listener heard of the user") { listener.users.isNotEmpty() }
        assertEquals(listOf("u1"), listener.users.map { it.optString("id") })
    }

    @Test
    fun selectedThemeAsAStringReachesTheListener() {
        connected(connect = userDataConnect(JSONObject(), JSONObject("""{"selectedTheme": "glass_dusk"}""")))
        awaitCondition("user data subscribed") { server.commands("frontend/subscribe_user_data").isNotEmpty() }
        awaitCondition("user theme") { listener.userThemes.isNotEmpty() }
        assertEquals(listOf<Pair<String?, Boolean?>>("glass_dusk" to null), listener.userThemes.toList())
        assertEquals("core", server.commands("frontend/get_user_data").single().getString("key"))
    }

    @Test
    fun selectedThemeAsAnObjectCarriesTheDarkChoice() {
        connected(connect = userDataConnect(JSONObject(), JSONObject("""{"selectedTheme": {"theme": "glass_dusk", "dark": true, "primaryColor": "#ff0000"}}""")))
        awaitCondition("user theme") { listener.userThemes.isNotEmpty() }
        assertEquals(listOf<Pair<String?, Boolean?>>("glass_dusk" to true), listener.userThemes.toList())
    }

    @Test
    fun noSelectedThemeMeansFollowTheServerDefault() {
        connected(connect = userDataConnect(JSONObject(), null))
        awaitCondition("user theme") { listener.userThemes.isNotEmpty() }
        assertEquals(listOf<Pair<String?, Boolean?>>(null to null), listener.userThemes.toList())
    }

    @Test
    fun userDataSubscriptionReportsLaterThemeChanges() {
        connected(connect = userDataConnect(JSONObject(), null))
        awaitCondition("initial") { listener.userThemes.isNotEmpty() }
        awaitCondition("user data subscribed") { server.commands("frontend/subscribe_user_data").any { it.optString("key") == "core" } }
        val sub = server.commands("frontend/subscribe_user_data").single { it.optString("key") == "core" }
        server.pushEvent(sub.getInt("id"), JSONObject("""{"value": {"selectedTheme": {"theme": "night", "dark": false}}}"""))
        awaitCondition("change") { listener.userThemes.size == 2 }
        assertEquals("night" to false, listener.userThemes[1])
        server.pushEvent(sub.getInt("id"), JSONObject("""{"value": {}}"""))
        awaitCondition("cleared") { listener.userThemes.size == 3 }
        assertEquals(null to null, listener.userThemes[2])
    }

    @Test
    fun aProfileChangeRedrawsAndAsksForTheWordsInTheNewLanguage() {
        try {
            connected(connect = userDataConnect(JSONObject(), null))
            awaitCondition("language subscribed") { server.commands("frontend/subscribe_user_data").any { it.optString("key") == "language" } }
            val sub = server.commands("frontend/subscribe_user_data").first { it.optString("key") == "language" }
            // HA's frontend applies the profile live (translations-mixin's user-data subscription): so does Vitro.
            server.pushEvent(sub.getInt("id"), JSONObject("""{"value": {"language": "de", "number_format": "decimal_comma", "time_format": "24"}}"""))
            awaitCondition("locale changed") { listener.localeChanges.get() == 1 }
            assertEquals(dev.glassworks.engine.vitro.data.FrontendLocale(language = "de", numberFormat = "decimal_comma", timeFormat = "24"),
                dev.glassworks.engine.vitro.data.HaLocale.current)
            // The entities' words again, in German.
            awaitCondition("German translations") { server.commands("frontend/get_translations").any { it.optString("language") == "de" } }
            // A format alone redraws too.
            server.pushEvent(sub.getInt("id"), JSONObject("""{"value": {"language": "de", "number_format": "comma_decimal"}}"""))
            awaitCondition("format changed") { listener.localeChanges.get() == 2 }
            assertEquals("comma_decimal", dev.glassworks.engine.vitro.data.HaLocale.current.numberFormat)
        } finally {
            dev.glassworks.engine.vitro.data.HaLocale.reset()
        }
    }

    @Test
    fun theUserRequestsComeAfterTheFirstNineStartupRequests() {
        connected(connect = userDataConnect(JSONObject(), null))
        awaitCondition("user data asked") { server.commands("frontend/subscribe_user_data").any { it.optString("key") == "core" } }
        val types = server.received.toList().drop(1).map { it.getString("type") }
        assertEquals(
            listOf("auth/current_user", "frontend/get_user_data", "frontend/subscribe_user_data"),
            types.drop(9).take(3),
        )
    }

    // ---- Admin-only dashboards (#887) ----

    private val wallUser = JSONObject("""{"id": "wall-user", "name": "Wall Panel", "is_owner": false, "is_admin": false}""")

    private fun started(path: String?): HaDashboardClient = newClient().also {
        it.subscribeViewEntities(emptySet())
        it.start(path)
    }

    @Test
    fun anAdminOnlyDashboardIsRefusedForAUserWhoIsntAnAdministrator() {
        server.currentUser = wallUser
        val c = started("admin-only")
        awaitCondition("refused") { listener.refused.isNotEmpty() }
        // HA answered its config (it does for anyone): its frontend is what refuses it, and so does Vitro.
        assertEquals("admin-only", server.commands("lovelace/config").single().getString("url_path"))
        assertEquals(listOf<String?>("admin-only"), listener.refused.toList())
        assertTrue(listener.configs.isEmpty())
        assertTrue(listener.failedConfigs.isEmpty())
        assertFalse(c.mayOpen("admin-only"))
        assertTrue(c.mayOpen("wall-test"))
        assertTrue("the default dashboard is never admin only", c.mayOpen(null))
        // A path that is none of the user's panels: HA's frontend routes it to the user's default panel.
        assertFalse(c.mayOpen("not-listed"))
        // The user's list leaves the admin-only dashboard out, as HA's frontend does.
        awaitCondition("dashboards") { listener.dashboards.isNotEmpty() }
        assertEquals(listOf("wall-test"), listener.dashboards.single().map { it.urlPath })
        // Another dashboard on the same connection is drawn.
        c.fetchDashboard("wall-test")
        awaitCondition("wall-test") { listener.configs.isNotEmpty() }
        assertEquals("wall-test", listener.configs.single().first)
        assertEquals(1, listener.refused.size)
    }

    @Test
    fun anAdministratorOpensAnAdminOnlyDashboard() {
        val c = started("admin-only")
        awaitCondition("config") { listener.configs.isNotEmpty() }
        assertEquals("admin-only", listener.configs.single().first)
        assertTrue(listener.refused.isEmpty())
        assertTrue(c.mayOpen("admin-only"))
    }

    @Test
    fun aUserHomeAssistantWontNameIsNoAdministrator() {
        // auth/current_user refused: HA's frontend has no hass.user, so no administrator.
        server.currentUser = null
        val c = started("admin-only")
        awaitCondition("refused") { listener.refused.isNotEmpty() }
        assertTrue(listener.configs.isEmpty())
        assertFalse(c.mayOpen("admin-only"))
    }

    @Test
    fun noConfigOrDashboardListIsHandedOnBeforeTheUserIsKnown() {
        server.holdCurrentUser = true
        val c = started("wall-test")
        // The entity snapshot comes after the config's answer on the wire: by then the config has been read and held.
        awaitCondition("config answered, user not") {
            server.commands("auth/current_user").isNotEmpty() && store["sensor.kitchen_temp"] != null
        }
        assertTrue("no config before the user", listener.configs.isEmpty())
        assertTrue("no dashboard list before the user", listener.dashboards.isEmpty())
        val id = server.commands("auth/current_user").single().getInt("id")
        server.sockets.last().push(JSONObject().put("id", id).put("type", "result").put("success", true).put("result", wallUser))
        awaitCondition("config") { listener.configs.isNotEmpty() && listener.dashboards.isNotEmpty() }
        assertEquals("wall-test", listener.configs.single().first)
        assertEquals("wall-user", c.currentUser.getString("id"))
    }

    @Test
    fun aReconnectAsksAgainWhoTheUserIsBeforeHandingOnAConfig() {
        // An administrator first: the admin-only dashboard is drawn.
        started("admin-only")
        awaitCondition("config") { listener.configs.isNotEmpty() }
        // The user loses the role in HA; the connection drops. The new one's config may answer before the user does.
        server.currentUser = wallUser
        server.holdCurrentUser = true
        server.dropConnection()
        awaitCondition("config asked again, user not answered") {
            server.commands("auth/current_user").size == 2 && server.commands("subscribe_entities").size == 2
        }
        assertEquals("nothing handed on with the last connection's user", 1, listener.configs.size)
        val id = server.commands("auth/current_user").last().getInt("id")
        server.sockets.last().push(JSONObject().put("id", id).put("type", "result").put("success", true).put("result", wallUser))
        awaitCondition("refused") { listener.refused.isNotEmpty() }
        assertEquals(1, listener.configs.size)
    }

    @Test
    fun aRefusedUserLookupAfterAnAdministratorsIsNoAdministrator() {
        val c = started("admin-only")
        awaitCondition("config") { listener.configs.isNotEmpty() }
        assertTrue(c.currentUser.getBoolean("is_admin"))
        server.currentUser = null
        server.dropConnection()
        awaitCondition("refused") { listener.refused.isNotEmpty() }
        assertFalse("the last user isn't kept", c.currentUser.has("is_admin"))
        assertFalse(c.mayOpen("admin-only"))
    }

    @Test
    fun withNoPanelsOnlyTheDefaultDashboardIsDrawnForANonAdmin() {
        // HA refused get_panels: which panels the user has is unknown.
        server.failPanels = true
        server.currentUser = wallUser
        val c = started("wall-test")
        awaitCondition("refused") { listener.refused.isNotEmpty() }
        // HA's frontend decides any named dashboard for a user who isn't an administrator.
        assertTrue(listener.configs.isEmpty())
        assertFalse(c.mayOpen("wall-test"))
        assertTrue(c.mayOpen(null))
        c.fetchDashboard(null)
        awaitCondition("default dashboard") { listener.configs.isNotEmpty() }
    }

    @Test
    fun withNoPanelsAnAdministratorOpensAnyDashboard() {
        server.failPanels = true
        val c = started("admin-only")
        awaitCondition("config") { listener.configs.isNotEmpty() }
        assertTrue(listener.refused.isEmpty())
        assertTrue(c.mayOpen("admin-only"))
    }

    @Test
    fun aPanelOverrideMakesADashboardAdminOnlyThoughItsOwnConfigSaysNot() {
        // frontend/update_panel stored require_admin over wall-test, whose dashboard config says false: get_panels
        // leaves it out for a non-admin, and so does Vitro, its list included.
        server.panelRequireAdmin["wall-test"] = true
        server.currentUser = wallUser
        val c = started("wall-test")
        awaitCondition("refused") { listener.refused.isNotEmpty() }
        assertTrue(listener.configs.isEmpty())
        assertFalse(c.mayOpen("wall-test"))
        awaitCondition("dashboards") { listener.dashboards.isNotEmpty() }
        assertEquals(emptyList<String>(), listener.dashboards.single().map { it.urlPath })
        // The other way round: an admin-only dashboard opened to everyone.
        listener.refused.clear()
        server.panelRequireAdmin["admin-only"] = false
        awaitCondition("panels_updated subscribed") { server.eventSubscriptionId("panels_updated") != null }
        server.pushPanelsUpdated()
        c.fetchDashboard("admin-only")
        awaitCondition("admin-only, overridden open") { listener.configs.isNotEmpty() }
        assertEquals("admin-only", listener.configs.single().first)
    }

    @Test
    fun aDashboardMadeAdminOnlyWhileOnScreenIsRefusedWithoutAReconnect() {
        server.currentUser = wallUser
        val c = started("wall-test")
        awaitCondition("config") { listener.configs.isNotEmpty() }
        assertTrue(listener.refused.isEmpty())
        awaitCondition("panels_updated subscribed") { server.eventSubscriptionId("panels_updated") != null }
        // An administrator makes it admin only in HA: HA fires panels_updated, and the dashboard on screen goes.
        server.panelRequireAdmin["wall-test"] = true
        server.pushPanelsUpdated()
        awaitCondition("refused") { listener.refused.isNotEmpty() }
        assertEquals(listOf<String?>("wall-test"), listener.refused.toList())
        assertEquals("the same connection", 1, server.connectUrls.size)
        assertEquals(2, server.commands("get_panels").size)
        assertFalse(c.mayOpen("wall-test"))
        // The Dashboard select is told too.
        awaitCondition("dashboards again") { listener.dashboards.size == 2 }
        assertEquals(emptyList<String>(), listener.dashboards.last().map { it.urlPath })
    }

    @Test
    fun aDashboardRevokedWhileTheFallbackShowedIsRefusedOnItsCachedReturn() {
        server.currentUser = wallUser
        val c = started("wall-test")
        awaitCondition("config") { listener.configs.isNotEmpty() }
        awaitCondition("panels_updated subscribed") { server.eventSubscriptionId("panels_updated") != null }
        // The page leaves for HA's frontend; meanwhile wall-test becomes admin only.
        c.releaseDashboard()
        server.panelRequireAdmin["wall-test"] = true
        server.pushPanelsUpdated()
        awaitCondition("panels asked again") { server.commands("get_panels").size == 2 }
        awaitCondition("dashboards again") { listener.dashboards.size == 2 }
        assertTrue("nothing on screen to refuse yet", listener.refused.isEmpty())
        // Back to it from the page's cache, no fetch: still checked, and refused.
        c.claimDashboard("wall-test")
        awaitCondition("refused") { listener.refused.isNotEmpty() }
        assertEquals(listOf<String?>("wall-test"), listener.refused.toList())
        assertEquals("no new config asked", 1, server.commands("lovelace/config").size)
        // A dashboard the user may still open comes back as it was.
        listener.refused.clear()
        c.claimDashboard(null)
        Thread.sleep(200)
        assertTrue(listener.refused.isEmpty())
    }

    @Test
    fun aStaleAnswerLeavesTheNewerClaimToBeChecked() {
        // wall-test's config answers, but waits for the user; meanwhile the page claims the default dashboard back.
        server.currentUser = wallUser
        server.holdCurrentUser = true
        val c = started("wall-test")
        awaitCondition("config answered, user not") {
            server.commands("auth/current_user").isNotEmpty() && store["sensor.kitchen_temp"] != null
        }
        c.claimDashboard(null)
        val id = server.commands("auth/current_user").single().getInt("id")
        server.sockets.last().push(JSONObject().put("id", id).put("type", "result").put("success", true).put("result", wallUser))
        awaitCondition("panels_updated subscribed") { server.eventSubscriptionId("panels_updated") != null }
        awaitCondition("dashboards") { listener.dashboards.isNotEmpty() }
        // The stale wall-test answer is neither handed on nor taken as the claim.
        assertTrue(listener.configs.isEmpty())
        assertTrue(listener.refused.isEmpty())
        // The default dashboard is the claim: made admin only, it's refused.
        server.panelRequireAdmin["lovelace"] = true
        server.pushPanelsUpdated()
        awaitCondition("refused") { listener.refused.isNotEmpty() }
        assertEquals(listOf<String?>(null), listener.refused.toList())
    }

    @Test
    fun aConfigWaitsForThePanelsAHaChangeAsksForAgain() {
        server.currentUser = wallUser
        val c = started("wall-test")
        awaitCondition("config") { listener.configs.isNotEmpty() }
        awaitCondition("panels_updated subscribed") { server.eventSubscriptionId("panels_updated") != null }
        // HA's panels change; the panels and the config are asked again, and HA now refuses the user wall-test.
        server.panelRequireAdmin["wall-test"] = true
        server.pushPanelsUpdated()
        c.fetchDashboard("wall-test")
        awaitCondition("refused") { listener.refused.isNotEmpty() }
        assertEquals("the second config isn't handed on with the old panels", 1, listener.configs.size)
    }

    @Test
    fun aRefusedDashboardListLeavesAccessToThePanels() {
        // lovelace/dashboards/list only feeds the Dashboard select: its error changes nothing of who opens what.
        server.failDashboardList = true
        server.currentUser = wallUser
        val c = started("wall-test")
        awaitCondition("config") { listener.configs.isNotEmpty() }
        assertTrue(c.mayOpen("wall-test"))
        assertFalse(c.mayOpen("admin-only"))
        assertTrue("no list to report", listener.dashboards.isEmpty())
    }

    @Test
    fun theDefaultDashboardIsTheLovelacePanel() {
        // get_panels is the rule for the default dashboard too: an override can make it admin only.
        server.panelRequireAdmin["lovelace"] = true
        server.currentUser = wallUser
        val c = started(null)
        awaitCondition("refused") { listener.refused.isNotEmpty() }
        assertEquals(listOf<String?>(null), listener.refused.toList())
        assertFalse(c.mayOpen(null))
    }

    @Test
    fun aRefusedDashboardThatHaHasNoConfigForIsStillRefused() {
        // HA's frontend never asks for it: no panel. Its missing config doesn't make it HA's frontend's to auto-generate.
        server.currentUser = wallUser
        server.missingDashboards = setOf("admin-only")
        started("admin-only")
        awaitCondition("refused") { listener.refused.isNotEmpty() }
        assertTrue(listener.failedConfigs.isEmpty())
    }

    // ---- Lovelace config ----

    @Test
    fun theDefaultDashboardIsRequestedWithoutAUrlPath() {
        connected(dashboardPath = null)
        val msg = server.commands("lovelace/config").single()
        assertFalse(msg.has("url_path"))
        val (path, config) = listener.configs[0]
        assertNull(path)
        assertEquals("Test Home", config.title)
        assertEquals(listOf("Living", "Wall"), config.views.map { it.title })
        assertEquals(listOf("sections", "panel"), config.views.map { it.type })
    }

    @Test
    fun aNamedDashboardIsRequestedByUrlPath() {
        connected(dashboardPath = "wall-test")
        assertEquals("wall-test", server.commands("lovelace/config").single().getString("url_path"))
        assertEquals("wall-test", listener.configs[0].first)
    }

    @Test
    fun fetchDashboardSwitchesOverTheLiveSocketAndFailuresAreSilent() {
        val c = connected()
        c.fetchDashboard("missing") // HA answers config_not_found: no callback
        c.fetchDashboard("wall-test")
        awaitCondition("second config") { listener.configs.size >= 2 }
        assertEquals(listOf<String?>(null, "wall-test"), listener.configs.map { it.first })
        assertEquals(
            listOf<String?>(null, "missing", "wall-test"),
            server.commands("lovelace/config").map { if (it.has("url_path")) it.getString("url_path") else null },
        )
        assertEquals(1, server.connectUrls.size) // same connection
    }

    @Test
    fun lovelaceUpdatedEventRefetchesTheCurrentDashboard() {
        connected(dashboardPath = "wall-test")
        server.pushEvent(server.eventSubscriptionId("lovelace_updated")!!, JSONObject().put("event_type", "lovelace_updated"))
        awaitCondition("refetched config") { listener.configs.size == 2 }
        val msgs = server.commands("lovelace/config")
        assertEquals(2, msgs.size)
        assertEquals("wall-test", msgs[1].getString("url_path"))
    }

    @Test
    fun themesUpdatedEventRefetchesThemes() {
        connected()
        server.pushEvent(server.eventSubscriptionId("themes_updated")!!, JSONObject().put("event_type", "themes_updated"))
        awaitCondition("refetched themes") { listener.themes.size == 2 }
        assertEquals(2, server.commands("frontend/get_themes").size)
    }

    // ---- Entities ----

    @Test
    fun withoutAViewFilterTheClientSubscribesToEveryEntity() {
        connected()
        assertFalse(server.commands("subscribe_entities").single().has("entity_ids"))
        awaitCondition("entities") { store.size() == 4 }
        assertEquals("on", store["light.lounge_lamp"]!!.state)
        assertEquals("21.5 °C", store["sensor.kitchen_temp"]!!.formattedState())
        assertEquals("Closed", store["binary_sensor.front_door"]!!.formattedState())
    }

    @Test
    fun nothingIsSubscribedUntilAViewPicksItsEntities() {
        // Every entity at once can outgrow the socket's message limit on a large HA: the view's scope comes first.
        val c = newClient()
        c.start(null)
        awaitCondition("startup sequence") { listener.dashboards.isNotEmpty() && listener.themes.isNotEmpty() }
        readerBarrier(c)
        assertTrue(server.commands("subscribe_entities").isEmpty())
        c.subscribeViewEntities(setOf("light.lounge_lamp"))
        awaitCondition("scoped subscription") { server.commands("subscribe_entities").isNotEmpty() }
        assertEquals("light.lounge_lamp", server.commands("subscribe_entities").single().getJSONArray("entity_ids").getString(0))
    }

    @Test
    fun aViewFilterSetBeforeConnectingScopesTheFirstSubscription() {
        val c = newClient()
        c.subscribeViewEntities(setOf("light.lounge_lamp", "sensor.kitchen_temp"))
        c.start(null)
        awaitCondition("subscription") { server.commands("subscribe_entities").isNotEmpty() }
        val ids = server.commands("subscribe_entities").single().getJSONArray("entity_ids")
        assertEquals(setOf("light.lounge_lamp", "sensor.kitchen_temp"), (0 until ids.length()).map { ids.getString(it) }.toSet())
        awaitCondition("scoped entities") { store.size() == 2 }
        assertEquals(setOf("light.lounge_lamp", "sensor.kitchen_temp"), store.all().keys)
        assertNull(store["sensor.unreferenced_power"]) // never asked for, never sent
    }

    @Test
    fun changingTheViewResubscribesAndDropsTheOldSubscription() {
        val c = connected()
        awaitCondition("first entities") { store.size() == 4 }
        val first = server.commands("subscribe_entities").single().getInt("id")

        c.subscribeViewEntities(setOf("sensor.kitchen_temp"))
        awaitCondition("resubscribed") { server.commands("subscribe_entities").size == 2 && server.commands("unsubscribe_events").isNotEmpty() }
        val unsub = server.commands("unsubscribe_events").single()
        assertEquals(first, unsub.getInt("subscription"))
        val subs = server.commands("subscribe_entities")
        assertEquals(2, subs.size)
        assertEquals(listOf("sensor.kitchen_temp"), subs[1].getJSONArray("entity_ids").let { a -> (0 until a.length()).map { a.getString(it) } })

        // The same view again is a no-op on the wire.
        c.subscribeViewEntities(setOf("sensor.kitchen_temp"))
        Thread.sleep(150) // writes are queued: give a wrongly sent one time to land
        assertEquals(2, server.commands("subscribe_entities").size)
        assertEquals(1, server.commands("unsubscribe_events").size)

        // Events for the dropped subscription no longer reach the store; the new one's do.
        server.pushEvent(first, JSONObject("""{"c": {"light.lounge_lamp": {"+": {"s": "off"}}}}"""))
        server.pushEvent(subs[1].getInt("id"), JSONObject("""{"c": {"sensor.kitchen_temp": {"+": {"s": "22.0"}}}}"""))
        awaitCondition("new subscription's diff") { store["sensor.kitchen_temp"]!!.state == "22.0" }
        assertEquals("on", store["light.lounge_lamp"]!!.state)
    }

    @Test
    fun changingTheViewNeverWaitsForTheSnapshotBeingApplied() {
        // The reader applies the first snapshot under the store's write lock; here a store listener holds it there.
        val inSnapshot = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        store.subscribe("light.lounge_lamp") { s ->
            if (s != null && inSnapshot.count > 0) {
                inSnapshot.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val c = newClient()
        c.subscribeViewEntities(emptySet())
        c.start(null)
        assertTrue("snapshot reached the store", inSnapshot.await(10, TimeUnit.SECONDS))
        try {
            // The UI thread switching views meanwhile returns at once instead of waiting for the lock.
            val switched = CompletableFuture.runAsync { c.subscribeViewEntities(setOf("sensor.kitchen_temp")) }
            switched.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
        }
        // The retired snapshot's mark doesn't land: the new subscription's own does, once its first event arrives.
        awaitCondition("resubscribed") { server.commands("subscribe_entities").size == 2 }
        awaitCondition("new snapshot") { store["sensor.kitchen_temp"] != null && store.isMissing("sensor.not_in_ha").not() }
        c.subscribeViewEntities(setOf("sensor.kitchen_temp", "sensor.not_in_ha"))
        awaitCondition("missing entity reported by the third subscription") { store.isMissing("sensor.not_in_ha") }
    }

    // ---- The dashboard's entities, once per connection (G-performance L3, fix B) ----

    private fun ids(m: JSONObject): Set<String> = m.optJSONArray("entity_ids")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()

    @Test
    fun theViewsOwnEntitiesAreAskedForFirstAndEachEntityOnce() {
        val c = newClient()
        c.followEntities(setOf("light.lounge_lamp", "sensor.kitchen_temp", "binary_sensor.front_door"), first = setOf("sensor.kitchen_temp"), additive = true)
        c.start(null)
        awaitCondition("both parts") { server.commands("subscribe_entities").size == 2 }
        val subs = server.commands("subscribe_entities")
        assertEquals(setOf("sensor.kitchen_temp"), ids(subs[0]))
        assertEquals(setOf("light.lounge_lamp", "binary_sensor.front_door"), ids(subs[1]))
        awaitCondition("every state") { store.size() == 3 }
        // Diffs of either part reach the store.
        server.pushEvent(subs[0].getInt("id"), JSONObject("""{"c": {"sensor.kitchen_temp": {"+": {"s": "22.0"}}}}"""))
        server.pushEvent(subs[1].getInt("id"), JSONObject("""{"c": {"binary_sensor.front_door": {"+": {"s": "on"}}}}"""))
        awaitCondition("both diffs") { store["sensor.kitchen_temp"]!!.state == "22.0" && store["binary_sensor.front_door"]!!.state == "on" }
    }

    @Test
    fun aDashboardThatGrowsSubscribesOnlyTheNewIds() {
        val c = newClient()
        val first = setOf("light.lounge_lamp", "sensor.kitchen_temp", "sensor.gone_early")
        c.followEntities(first, additive = true)
        c.start(null)
        awaitCondition("first subscription") { server.commands("subscribe_entities").size == 1 && store.isMissing("sensor.gone_early") }
        // A view switch on the same dashboard, with another view's entities first: nothing on the wire.
        c.followEntities(first, first = setOf("sensor.kitchen_temp"), additive = true)
        // A config change that adds a card: only its entity, and one HA doesn't have.
        val grown = first + setOf("binary_sensor.front_door", "sensor.not_in_ha")
        c.followEntities(grown, additive = true)
        awaitCondition("the new ids") { server.commands("subscribe_entities").size == 2 }
        assertEquals(setOf("binary_sensor.front_door", "sensor.not_in_ha"), ids(server.commands("subscribe_entities")[1]))
        assertTrue("nothing dropped", server.commands("unsubscribe_events").isEmpty())
        awaitCondition("the new part's answer") { store["binary_sensor.front_door"] != null && store.isMissing("sensor.not_in_ha") }
        // The first part's answer still counts: what it said doesn't exist still doesn't.
        assertTrue(store.isMissing("sensor.gone_early"))
        // Never asked for: unknown, not missing.
        assertFalse(store.isMissing("sensor.unreferenced_power"))
        // A card removed: it stays subscribed, as long as most of what's followed is still wanted.
        c.followEntities(grown - "sensor.not_in_ha", additive = true)
        readerBarrier(c)
        assertEquals(2, server.commands("subscribe_entities").size)
        assertTrue(server.commands("unsubscribe_events").isEmpty())
    }

    @Test
    fun aReconnectSubscribesTheWholeSetOnce() {
        val c = newClient()
        c.followEntities(setOf("light.lounge_lamp", "sensor.kitchen_temp"), first = setOf("light.lounge_lamp"), additive = true)
        c.start(null)
        awaitCondition("first connection's parts") { server.commands("subscribe_entities").size == 2 }
        c.followEntities(setOf("light.lounge_lamp", "sensor.kitchen_temp", "binary_sensor.front_door"), first = setOf("sensor.kitchen_temp"), additive = true)
        awaitCondition("the added id") { server.commands("subscribe_entities").size == 3 }
        server.dropConnection()
        awaitCondition("reconnected", timeoutMs = 15_000) { server.sockets.size == 2 && server.sockets[1].received.count { it.optString("type") == "subscribe_entities" } == 2 }
        readerBarrier(c)
        val again = server.sockets[1].received.filter { it.optString("type") == "subscribe_entities" }
        assertEquals("the view on screen first", setOf("sensor.kitchen_temp"), ids(again[0]))
        assertEquals(setOf("light.lounge_lamp", "binary_sensor.front_door"), ids(again[1]))
        // A view switch on the new connection asks for nothing.
        c.followEntities(setOf("light.lounge_lamp", "sensor.kitchen_temp", "binary_sensor.front_door"), first = setOf("light.lounge_lamp"), additive = true)
        readerBarrier(c)
        assertEquals(2, server.sockets[1].received.count { it.optString("type") == "subscribe_entities" })
    }

    @Test
    fun aMalformedIdIsNeverSentAndIsMissingAtOnce() {
        // HA's cv.entity_ids refuses the whole subscribe_entities for one malformed id: it's left out, and its card
        // shows it as not found, as HA's frontend does.
        assertTrue(HaDashboardClient.isValidEntityId("sensor.office_2"))
        for (bad in listOf("not-an-id", "Light.Lounge", "sensor.", ".x", "_sensor.x", "sensor.x_", "sensor.a__b", "sensor.x.y")) {
            assertFalse(bad, HaDashboardClient.isValidEntityId(bad))
        }
        val c = newClient()
        c.followEntities(setOf("light.lounge_lamp", "not-an-id", "sensor.kitchen_temp"), first = setOf("light.lounge_lamp"), additive = true)
        c.start(null)
        awaitCondition("both valid states") { store["light.lounge_lamp"] != null && store["sensor.kitchen_temp"] != null }
        assertTrue(server.commands("subscribe_entities").none { "not-an-id" in ids(it) })
        assertTrue(store.isMissing("not-an-id"))
        // Nothing but malformed ids follows nothing: never every entity.
        c.followEntities(setOf("not-an-id"))
        readerBarrier(c)
        assertTrue("every entity never asked for", server.commands("subscribe_entities").all { it.has("entity_ids") })
        assertTrue(store.isMissing("not-an-id"))
    }

    @Test
    fun aRefusedPartIsAskedForAgainOnTheNextFollow() {
        val c = newClient()
        server.refuseEntityIds = setOf("binary_sensor.front_door")
        c.followEntities(setOf("light.lounge_lamp", "binary_sensor.front_door"), first = setOf("light.lounge_lamp"), additive = true)
        c.start(null)
        awaitCondition("the view's part answered") { server.commands("subscribe_entities").size == 2 && store["light.lounge_lamp"] != null }
        readerBarrier(c)
        // HA is fine again: the next follow (a view switch) asks for the refused id, and only it.
        server.refuseEntityIds = emptySet()
        c.followEntities(setOf("light.lounge_lamp", "binary_sensor.front_door"), first = setOf("binary_sensor.front_door"), additive = true)
        awaitCondition("asked again") { server.commands("subscribe_entities").size == 3 }
        assertEquals(setOf("binary_sensor.front_door"), ids(server.commands("subscribe_entities")[2]))
        awaitCondition("its state") { store["binary_sensor.front_door"] != null }
    }

    @Test
    fun anotherDashboardOrEveryEntityReplacesTheSubscription() {
        val c = newClient()
        c.followEntities(setOf("light.lounge_lamp", "sensor.kitchen_temp"), first = setOf("light.lounge_lamp"), additive = true)
        c.start(null)
        awaitCondition("two parts") { server.commands("subscribe_entities").size == 2 }
        val firstIds = server.commands("subscribe_entities").map { it.getInt("id") }
        // Another dashboard: most of what's followed isn't wanted any more, so both parts go.
        c.followEntities(setOf("binary_sensor.front_door"), additive = true)
        awaitCondition("replaced") { server.commands("subscribe_entities").size == 3 && server.commands("unsubscribe_events").size == 2 }
        assertEquals(firstIds.toSet(), server.commands("unsubscribe_events").map { it.getInt("subscription") }.toSet())
        assertEquals(setOf("binary_sensor.front_door"), ids(server.commands("subscribe_entities")[2]))
        // A view that follows every entity, and back: each a replacement.
        c.followEntities(emptySet())
        awaitCondition("every entity") { server.commands("subscribe_entities").size == 4 }
        assertFalse(server.commands("subscribe_entities")[3].has("entity_ids"))
        c.followEntities(setOf("binary_sensor.front_door"), additive = true)
        awaitCondition("scoped again") { server.commands("subscribe_entities").size == 5 }
        assertEquals(setOf("binary_sensor.front_door"), ids(server.commands("subscribe_entities")[4]))
    }

    @Test
    fun compressedDiffEventsUpdateTheStoreAndWakeItsSubscribers() {
        // The display data (the registries, sent after the entity subscription) re-renders every listener once it
        // settles (renotifyAll): the fake's area registry has data, so it does. Its answers can land after the diff
        // below, and the lamp's listener would hear "off" twice. Waited for, so the only "off" it hears is the diff's.
        val displaySettled = java.util.concurrent.atomic.AtomicBoolean(false)
        store.subscribe(EntityStore.DISPLAY_KEY) { displaySettled.set(true) }
        connected()
        awaitCondition("entities") { store.size() == 4 }
        awaitCondition("display data settled") { displaySettled.get() }
        // Counted: the notifications that carry the diff. The snapshot's own dispatch can still be running when
        // `size() == 4` holds (states go in before listeners hear), and it may reach this listener with "on".
        val woken = AtomicInteger(0)
        val sawRemovalFirst = java.util.concurrent.atomic.AtomicBoolean(true)
        store.subscribe("light.lounge_lamp") { s ->
            if (s?.state == "off") {
                // Recorded before the count: a reader that sees the count also sees this.
                if (store["sensor.unreferenced_power"] != null) sawRemovalFirst.set(false)
                woken.incrementAndGet()
            }
        }
        val sub = server.commands("subscribe_entities").single().getInt("id")

        server.pushEvent(
            sub,
            JSONObject(
                """{"c": {"light.lounge_lamp": {"+": {"s": "off", "a": {"brightness": 0}}, "-": {"a": ["rgb_color"]}}}, "r": ["sensor.unreferenced_power"]}""",
            ),
        )
        // The whole event is applied, then subscribers are told once.
        awaitCondition("diff applied") {
            store["light.lounge_lamp"]!!.state == "off" && store["sensor.unreferenced_power"] == null && woken.get() == 1
        }
        val lamp = store["light.lounge_lamp"]!!
        assertFalse("rgb_color" in lamp.attributes)
        assertEquals(0, (lamp.attributes["brightness"] as Number).toInt())
        assertEquals("Lounge Lamp", lamp.friendlyName)
        assertEquals(1, woken.get())
        assertTrue("the whole event was applied before the listener heard", sawRemovalFirst.get())
    }

    // ---- Commands ----

    @Test
    fun callServiceSendsDomainServiceDataAndTarget() {
        val c = connected()
        val done = CompletableFuture<Boolean>()
        c.callService(
            "light", "turn_on",
            JSONObject().put("brightness", 128),
            JSONObject().put("entity_id", "light.lounge_lamp"),
        ) { done.complete(it) }
        assertTrue(done.get(10, TimeUnit.SECONDS))
        val msg = server.commands("call_service").single()
        assertEquals("light", msg.getString("domain"))
        assertEquals("turn_on", msg.getString("service"))
        assertEquals(128, msg.getJSONObject("service_data").getInt("brightness"))
        assertEquals("light.lounge_lamp", msg.getJSONObject("target").getString("entity_id"))
    }

    @Test
    fun callServiceOmitsDataAndTargetWhenThereAreNone() {
        val c = connected()
        c.callService("homeassistant", "restart")
        awaitCondition("call_service") { server.commands("call_service").isNotEmpty() }
        val msg = server.commands("call_service").single()
        assertFalse(msg.has("service_data"))
        assertFalse(msg.has("target"))
    }

    @Test
    fun callWsRelaysAnyCommandAndReturnsTheResult() {
        val c = connected()
        val done = CompletableFuture<Triple<Boolean, Any?, JSONObject?>>()
        val request = JSONObject().put("type", "config/area_registry/list")
        c.callWs(request) { ok, result, err -> done.complete(Triple(ok, result, err)) }
        val (ok, result, err) = done.get(10, TimeUnit.SECONDS)
        assertTrue(ok)
        assertNull(err)
        assertEquals("Lounge", (result as JSONArray).getJSONObject(0).getString("name"))
        assertFalse("the caller's message is copied, not stamped with an id", request.has("id"))
        // The client asks for the areas itself too (the area card): every one carries an id.
        assertTrue(server.commands("config/area_registry/list").all { it.getInt("id") > 0 })
    }

    @Test
    fun callWsReportsAnErrorResult() {
        val c = connected()
        val done = CompletableFuture<Triple<Boolean, Any?, JSONObject?>>()
        c.callWs(JSONObject().put("type", "bogus/command")) { ok, result, err -> done.complete(Triple(ok, result, err)) }
        val (ok, result, err) = done.get(10, TimeUnit.SECONDS)
        assertFalse(ok)
        assertNull(result)
        assertEquals("unknown_command", err!!.getString("code"))
    }

    @Test
    fun callWsWithoutAConnectionReportsDisconnectedInsteadOfHanging() {
        // A card's hass.callWS() promise waits on this callback, so it must fire even when the socket is not up.
        val c = newClient() // never started: no socket
        val done = CompletableFuture<Triple<Boolean, Any?, JSONObject?>>()
        c.callWs(JSONObject().put("type", "config/area_registry/list")) { ok, result, err -> done.complete(Triple(ok, result, err)) }
        val (ok, result, err) = try {
            done.get(2, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw AssertionError(
                "callWs never called back on a client with no connection. HaDashboardClient.request() returns the " +
                    "new id even when send() failed, so callWs's `sent <= 0` check can never be true.",
            )
        }
        assertFalse(ok)
        assertNull(result)
        assertEquals("disconnected", err!!.getString("code"))
    }

    @Test
    fun historyReturnsNumericPointsAndThenUnsubscribes() {
        server.history = JSONObject(
            """{"states": {
                 "sensor.kitchen_temp": [{"s": "20.5", "lu": 1700000000.0}, {"s": "unavailable", "lu": 1700000050.0}, {"s": "21.0", "lc": 1700000100.0}],
                 "sensor.other": [{"s": "unknown"}],
                 "sensor.not_a_list": "junk"}}""",
        )
        val c = connected()
        val done = CompletableFuture<Map<String, List<HistoryPoint>>>()
        c.fetchHistory(listOf("sensor.kitchen_temp", "sensor.other"), hoursToShow = 6) { done.complete(it) }
        val points = done.get(10, TimeUnit.SECONDS)

        assertEquals(setOf("sensor.kitchen_temp"), points.keys) // a series with no numbers is left out
        assertEquals(listOf(HistoryPoint(1700000000.0, 20.5), HistoryPoint(1700000100.0, 21.0)), points.getValue("sensor.kitchen_temp"))

        // One-shot: the whole window in one answer, nothing subscribed (a stream's first event can be incomplete).
        val msg = server.commands("history/history_during_period").single()
        assertEquals(listOf("sensor.kitchen_temp", "sensor.other"), msg.getJSONArray("entity_ids").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertTrue(msg.getBoolean("minimal_response"))
        assertTrue(msg.getBoolean("no_attributes"))
        assertFalse(msg.getBoolean("significant_changes_only"))
        val start = Instant.parse(msg.getString("start_time"))
        val expected = Instant.now().minusSeconds(6 * 3600L)
        assertTrue("start_time is 6 hours back: $start vs $expected", kotlin.math.abs(start.epochSecond - expected.epochSecond) < 120)
        val end = Instant.parse(msg.getString("end_time"))
        assertTrue("end_time is now: $end", kotlin.math.abs(end.epochSecond - Instant.now().epochSecond) < 180)
        assertTrue(server.commands("history/stream").isEmpty())
        assertTrue(server.commands("unsubscribe_events").isEmpty())
    }

    @Test
    fun aFailedHistoryRequestCallsNothingBack() {
        server.failHistory = true
        val c = connected()
        val called = java.util.concurrent.atomic.AtomicBoolean(false)
        c.fetchHistory(listOf("sensor.kitchen_temp")) { called.set(true) }
        awaitCondition("history request") { server.commands("history/history_during_period").isNotEmpty() }
        val done = CompletableFuture<Boolean>()
        c.callWs(JSONObject().put("type", "config/area_registry/list")) { ok, _, _ -> done.complete(ok) }
        assertTrue(done.get(10, TimeUnit.SECONDS)) // the history answer came before this one
        assertFalse(called.get())
    }

    @Test
    fun subscribeWsDeliversEventsAndReportsARejectionOnce() {
        val c = connected()
        // Anything a callback wasn't expecting goes here, from the socket thread, and is asserted on this one.
        val unexpected = java.util.concurrent.ConcurrentLinkedQueue<String>()
        // Delivered: a subscription's events (the fake server streams render_template), until cancelled.
        val events = CompletableFuture<JSONObject>()
        val cancel = c.subscribeWs(
            JSONObject().put("type", "render_template").put("template", "{{ 1 }}"),
            { if (!events.complete(it)) unexpected += "a second event: $it" },
            { unexpected += "an error on a stream that delivered: $it" },
        )
        events.get(10, TimeUnit.SECONDS)
        cancel()
        // Rejected: the command's error reaches onError, once, and no event follows.
        val errors = java.util.concurrent.LinkedBlockingQueue<String>()
        c.subscribeWs(JSONObject().put("type", "bogus/stream"), { unexpected += "an event after a rejection: $it" }, { errors.put(it?.optString("code") ?: "null") })
        assertEquals("unknown_command", errors.poll(10, TimeUnit.SECONDS))
        assertNull(errors.poll(300, TimeUnit.MILLISECONDS))
        assertEquals(emptyList<String>(), unexpected.toList())
    }

    @Test
    fun historyAlsoReadsHomeAssistantsFullStateFormat() {
        server.history = JSONObject(
            """{"states": {"sensor.kitchen_temp": [
                 {"state": "20.5", "last_updated": "2026-11-23T17:00:00+00:00"},
                 {"state": "off", "last_updated": "2026-11-23T17:01:00+00:00"},
                 {"state": "21.0", "last_changed": "2026-11-23T17:02:00Z"},
                 {"state": "22.0", "last_updated": 1700000000.0}]}}""",
        )
        val c = connected()
        val done = CompletableFuture<Map<String, List<HistoryPoint>>>()
        c.fetchHistory(listOf("sensor.kitchen_temp")) { done.complete(it) }
        val pts = done.get(10, TimeUnit.SECONDS).getValue("sensor.kitchen_temp")
        assertEquals(listOf(1795453200.0, 1795453320.0, 1700000000.0), pts.map { it.epochSec })
        assertEquals(listOf(20.5, 21.0, 22.0), pts.map { it.value })
    }

    @Test
    fun socketWritesLeaveTheCallingThreadAndKeepTheirOrder() {
        // On Android the caller is the main thread, where a socket write throws NetworkOnMainThreadException.
        val writers = CopyOnWriteArrayList<Pair<String, String>>() // message type to writing thread
        val c = connected(connect = { url ->
            SpySocket(server.connect(url), before = { msg -> writers += msg.optString("type") to Thread.currentThread().name })
        })
        // auth + 9 startup requests + 3 user-data requests + the panels and their subscription
        awaitCondition("startup writes") { writers.size >= 15 }
        val caller = Thread.currentThread()

        c.callService("light", "turn_on")
        c.callService("light", "turn_off")
        c.fetchHistory(listOf("sensor.kitchen_temp"), 1) {}
        c.callService("light", "toggle")
        awaitCondition("queued writes") { server.commands("history/history_during_period").isNotEmpty() && server.commands("call_service").size == 3 }

        val sent = writers.drop(1) // everything after `auth`
        assertTrue("every post-auth write is on the writer thread: $sent", sent.all { it.second == "vitro-ha-send" })
        assertNotEquals("vitro-ha-send", caller.name)
        assertEquals("auth", writers[0].first)
        assertNotEquals("vitro-ha-send", writers[0].second) // the handshake itself is inline on the reader

        // FIFO: ids go out in the order the calls were made, across the reader's and the caller's requests.
        val ids = server.received.drop(1).map { it.getInt("id") }
        assertEquals(ids.sorted(), ids)
        // Display data (registry, icons, translations, the registries' update events) can interleave: it isn't what
        // this test orders.
        val display = setOf("config/entity_registry/list_for_display", "config/device_registry/list", "config/area_registry/list", "frontend/get_icons", "frontend/get_translations")
        val after = server.received.drop(1).filter {
            it.getInt("id") > 14 && it.getString("type") !in display && !it.optString("event_type").endsWith("_registry_updated")
        }.map {
            if (it.getString("type") == "call_service") it.getString("service") else it.getString("type")
        }
        assertEquals(listOf("turn_on", "turn_off", "history/history_during_period", "toggle"), after.take(4))
    }

    private fun failingCallServiceWrites(): HaDashboardClient = connected(connect = { url ->
        SpySocket(server.connect(url), before = { msg ->
            if (msg.optString("type") == "call_service") throw IOException("Broken pipe")
        })
    })

    @Test
    fun aServiceCallWhoseWriteFailsReportsFailure() {
        val c = failingCallServiceWrites()
        val done = CompletableFuture<Boolean>()
        c.callService("light", "turn_on") { done.complete(it) }
        assertFalse(done.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun aCallWsWhoseWriteFailsAnswersWithSendFailedInsteadOfHanging() {
        val c = failingCallServiceWrites()
        val done = CompletableFuture<Triple<Boolean, Any?, JSONObject?>>()
        c.callWs(JSONObject().put("type", "call_service").put("domain", "light").put("service", "x")) { ok, r, e -> done.complete(Triple(ok, r, e)) }
        val (ok, result, err) = done.get(10, TimeUnit.SECONDS)
        assertFalse(ok)
        assertNull(result)
        assertEquals("send_failed", err!!.getString("code"))
    }

    @Test
    fun historyWithNoEntitiesSendsNothing() {
        val c = connected()
        val called = java.util.concurrent.atomic.AtomicBoolean(false)
        c.fetchHistory(emptyList()) { called.set(true) }
        // A later command through the same writer: once its reply is back, anything queued before it went out.
        val done = CompletableFuture<Boolean>()
        c.callWs(JSONObject().put("type", "config/area_registry/list")) { ok, _, _ -> done.complete(ok) }
        assertTrue(done.get(10, TimeUnit.SECONDS))
        assertTrue(server.commands("history/history_during_period").isEmpty())
        assertFalse(called.get())
    }

    @Test
    fun templatesStreamRenderedResultsUntilUnsubscribed() {
        val c = connected()
        val rendered = LinkedBlockingQueue<String>()
        val template = "{{ states('sensor.kitchen_temp') }} °C"
        val stop = c.subscribeTemplate(template) { rendered.put(it) }

        assertEquals("21.5 °C", rendered.poll(10, TimeUnit.SECONDS))
        val msg = server.commands("render_template").single()
        assertEquals(template, msg.getString("template"))
        val sub = msg.getInt("id")

        server.pushEvent(sub, JSONObject().put("result", 22.5))
        assertEquals("22.5", rendered.poll(10, TimeUnit.SECONDS))
        server.pushEvent(sub, JSONObject().put("result", JSONObject.NULL))
        assertEquals("", rendered.poll(10, TimeUnit.SECONDS))

        stop()
        awaitCondition("template unsubscribe") { server.commands("unsubscribe_events").isNotEmpty() }
        val unsub = server.commands("unsubscribe_events").single()
        assertEquals(sub, unsub.getInt("subscription"))
    }

    // ---- Authentication problems ----

    @Test
    fun rejectedTokenAsksForARefreshOnceThenReportsAuthRequired() {
        server.acceptedTokens = emptySet()
        newClient().start(null)
        awaitCondition("auth required") { listener.authRequired.get() == 1 }
        assertEquals(listOf(false, true), tokenCalls.toList()) // the second call forces a refresh
        assertTrue(listener.connected.isEmpty())
        assertEquals(1, server.connectUrls.size) // the same token is not retried
        assertEquals(1, server.commands("auth").size)
    }

    @Test
    fun rejectedTokenWithAFreshOneReconnectsStraightAway() {
        server.acceptedTokens = setOf("new-token")
        var token = "old-token"
        val c = newClient(tokens = { force, done ->
            tokenCalls += force
            if (force) token = "new-token"
            done(token)
        })
        c.start(null)
        awaitCondition("connected with the fresh token") { listener.connected.isNotEmpty() }
        assertEquals(listOf("old-token", "new-token"), server.commands("auth").map { it.getString("access_token") })
        assertEquals(2, server.connectUrls.size)
        assertEquals(0, listener.authRequired.get())
        // The refreshed token goes straight into the next handshake: no third call to the provider.
        assertEquals(listOf(false, true), tokenCalls.toList())
        assertTrue("the rejected socket was closed", server.sockets[0].closed)
    }

    @Test
    fun theRefreshedTokenIsUsedEvenWhenThePlainProviderStillHasTheOldOne() {
        server.acceptedTokens = setOf("new-token")
        newClient(tokens = { force, done -> done(if (force) "new-token" else "old-token") }).start(null)
        awaitCondition("connected with the refreshed token") { listener.connected.isNotEmpty() }
        assertEquals(listOf("old-token", "new-token"), server.commands("auth").map { it.getString("access_token") })
        assertEquals(0, listener.authRequired.get())
    }

    @Test
    fun aRejectedRefreshedTokenReportsAuthRequiredOnce() {
        server.acceptedTokens = emptySet()
        newClient(tokens = { force, done -> done(if (force) "new-token" else "old-token") }).start(null)
        awaitCondition("auth required") { listener.authRequired.get() == 1 }
        assertEquals(listOf("old-token", "new-token"), server.commands("auth").map { it.getString("access_token") })
        assertEquals(2, server.connectUrls.size)
    }

    @Test
    fun aServerThatNeverGreetsIsDroppedAndRetried() {
        server.greeting = { null }
        newClient(authTimeoutMs = 100).start(null)
        awaitCondition("reconnect after the greeting timed out") { server.connectUrls.size >= 2 }
        assertTrue(server.sockets[0].closed)
        assertTrue(listener.disconnected.isNotEmpty())
    }

    @Test
    fun aRequestStillWaitingWhenTheConnectionDropsFailsOnce() {
        // `test/hang` reaches the wire but HA never answers it before the connection ends.
        val withheld = java.util.concurrent.CountDownLatch(1)
        val c = connected(connect = { url ->
            val inner = server.connect(url)
            object : HaSocket by inner {
                override fun send(text: String) {
                    if (JSONObject(text).optString("type") != "test/hang") inner.send(text) else withheld.countDown()
                }
            }
        })
        val results = CopyOnWriteArrayList<Pair<Boolean, JSONObject?>>()
        c.callWs(JSONObject().put("type", "test/hang")) { ok, _, err -> results += ok to err }
        assertTrue("the writer put it on the wire", withheld.await(10, TimeUnit.SECONDS))
        server.dropConnection()
        awaitCondition("the hanging request failed") { results.isNotEmpty() }
        Thread.sleep(100)
        assertEquals(1, results.size)
        assertFalse(results[0].first)
        assertEquals("disconnected", results[0].second?.optString("code"))
    }

    @Test
    fun aDashboardRequestCutOffByADroppedConnectionIsAskedAgainNotReportedMissing() {
        // The first connection never answers `lovelace/config` for "slow"; it then drops.
        val firstSocket = java.util.concurrent.atomic.AtomicBoolean(true)
        val withheld = java.util.concurrent.CountDownLatch(1)
        val c = connected(connect = { url ->
            val inner = server.connect(url)
            val swallow = firstSocket.getAndSet(false)
            object : HaSocket by inner {
                override fun send(text: String) {
                    val m = JSONObject(text)
                    if (swallow && m.optString("type") == "lovelace/config" && m.optString("url_path") == "slow") {
                        withheld.countDown()
                        return
                    }
                    inner.send(text)
                }
            }
        })
        c.fetchDashboard("slow")
        assertTrue("the request for \"slow\" was withheld", withheld.await(10, TimeUnit.SECONDS))
        server.dropConnection()
        // The reconnect asks for the same dashboard and gets it; nothing reports it as missing.
        awaitCondition("config after reconnect") { listener.configs.any { it.first == "slow" } }
        assertTrue(listener.failedConfigs.toString(), listener.failedConfigs.isEmpty())
    }

    @Test
    fun theEntityRegistryAloneSucceedingStillReRendersOnce() {
        // Subscribed before connecting: the snapshot wakes it once, the display re-render once more.
        val woken = AtomicInteger(0)
        store.subscribe("light.lounge_lamp") { woken.incrementAndGet() }
        // HA answers the entity registry; the device registry, icons and translations are refused (the fake's default).
        connected(connect = { url ->
            val inner = server.connect(url) as FakeHaServer.Socket
            object : HaSocket by inner {
                override fun send(text: String) {
                    val m = JSONObject(text)
                    if (m.optString("type") == "config/entity_registry/list_for_display") {
                        inner.push(JSONObject().put("id", m.getInt("id")).put("type", "result").put("success", true)
                            .put("result", JSONObject().put("entities", JSONArray())))
                    } else {
                        inner.send(text)
                    }
                }
            }
        })
        awaitCondition("snapshot and the one re-render") { woken.get() >= 2 }
        Thread.sleep(200)
        assertEquals(2, woken.get())
    }

    @Test
    fun aReleasedDashboardIsNotFetchedOnLovelaceUpdatedOrReconnect() {
        val c = connected()
        c.releaseDashboard()
        server.pushEvent(server.eventSubscriptionId("lovelace_updated")!!, JSONObject().put("event_type", "lovelace_updated"))
        // The reader has handled the update (and skipped it) before the connection goes.
        readerBarrier(c)
        assertEquals(1, server.commands("lovelace/config").size)
        server.dropConnection()
        awaitCondition("reconnected") { server.connectUrls.size == 2 && server.commands("subscribe_entities").size == 2 }
        // Only the first connection's startup request: nothing for the fallback page's "dashboard".
        assertEquals(1, server.commands("lovelace/config").size)
        // Reclaiming the dashboard shown before fetches it, since refetches were skipped meanwhile.
        c.claimDashboard(null)
        awaitCondition("refetched on claim") { server.commands("lovelace/config").size == 2 }
        // The page puts it on screen (VitroEngine does this in onDashboardConfig).
        awaitCondition("config delivered") { listener.configs.size == 2 }
        c.dashboardShown()
        // A claim with nothing skipped stays instant (no request).
        c.claimDashboard(null)
        readerBarrier(c)
        assertEquals(2, server.commands("lovelace/config").size)
    }

    /** Returns once the reader has handled everything HA sent before: a request's answer comes after all of it. */
    private fun readerBarrier(c: HaDashboardClient) {
        val done = CompletableFuture<Boolean>()
        c.callWs(JSONObject().put("type", "config/area_registry/list")) { ok, _, _ -> done.complete(ok) }
        assertTrue(done.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun aMissedRefetchSurvivesARequestWhoseAnswerIsDiscarded() {
        // While `swallow` is set, HA holds back its lovelace/config answer; the test delivers it late.
        val swallow = java.util.concurrent.atomic.AtomicBoolean(false)
        val swallowedId = java.util.concurrent.atomic.AtomicInteger(-1)
        var socket: FakeHaServer.Socket? = null
        val c = connected(connect = { url ->
            val inner = server.connect(url) as FakeHaServer.Socket
            socket = inner
            object : HaSocket by inner {
                override fun send(text: String) {
                    val m = JSONObject(text)
                    if (swallow.get() && m.optString("type") == "lovelace/config") {
                        // The id first: a test that sees the request also sees which id to answer.
                        swallowedId.set(m.getInt("id"))
                        server.received += m
                        return
                    }
                    inner.send(text)
                }
            }
        })
        c.releaseDashboard()
        server.pushEvent(server.eventSubscriptionId("lovelace_updated")!!, JSONObject().put("event_type", "lovelace_updated"))
        readerBarrier(c) // the update has been handled, while the dashboard is still released
        assertEquals(1, server.commands("lovelace/config").size) // skipped: the fallback shows
        swallow.set(true)
        c.claimDashboard(null) // asks, but the answer never comes
        awaitCondition("asked on claim") { server.commands("lovelace/config").size == 2 && swallowedId.get() >= 0 }
        c.releaseDashboard()
        swallow.set(false)
        // The answer arrives late, after the release: it is dropped, and it doesn't count as the refetch made up.
        val configsBefore = listener.configs.size
        socket!!.push(
            JSONObject().put("id", swallowedId.get()).put("type", "result").put("success", true)
                .put("result", JSONObject(FakeHaServer.DASHBOARD_JSON)),
        )
        readerBarrier(c)
        assertEquals(configsBefore, listener.configs.size)
        // The refetch was never made up: the next claim asks again and gets the dashboard.
        c.claimDashboard(null)
        awaitCondition("asked again") { server.commands("lovelace/config").size == 3 }
        awaitCondition("delivered on the new claim") { listener.configs.size == configsBefore + 1 }
    }

    @Test
    fun anIdleConnectionOutlivesTheUpgradeDeadline() {
        // The deadline bounds the upgrade only: no read timeout is left on the socket afterwards.
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val accepted = Thread {
                runCatching {
                    val s = server.accept()
                    val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    var key = ""
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = line.substringAfter(':').trim()
                    }
                    val accept = java.util.Base64.getEncoder().encodeToString(
                        java.security.MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                    )
                    s.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                    Thread.sleep(900) // idle for three upgrade deadlines
                    s.getOutputStream().write(byteArrayOf(0x81.toByte(), 2, 'h'.code.toByte(), 'i'.code.toByte()))
                    Thread.sleep(500)
                    s.close()
                }
            }.apply { isDaemon = true; start() }
            val ws = HaDashboardClient.connectLongLived("ws://127.0.0.1:${server.localPort}/api/websocket", upgradeTimeoutMs = 300)
            assertEquals("hi", ws.receive())
            ws.close()
            accepted.join(5_000)
        }
    }

    @Test
    fun aServiceCallThatCannotBeQueuedFailsAtOnce() {
        // No connection: the caller hears `false` now, so an optimistic control rolls back instead of waiting.
        val c = newClient()
        val result = CompletableFuture<Boolean>()
        c.callService("light", "toggle", JSONObject().put("entity_id", "light.lounge_lamp")) { result.complete(it) }
        assertFalse(result.get(1, TimeUnit.SECONDS))
    }

    @Test
    fun aCleanCloseByHomeAssistantIsReportedAsADisconnect() {
        connected()
        assertTrue(listener.disconnected.isEmpty())
        server.dropConnection() // EOF, no error
        awaitCondition("disconnect reported") { listener.disconnected.isNotEmpty() }
        assertEquals("Closed by Home Assistant", listener.disconnected[0])
        awaitCondition("reconnected") { server.connectUrls.size == 2 }
    }

    @Test
    fun aPingThroughCallWsSettlesOnPong() {
        val c = connected()
        val done = CompletableFuture<Boolean>()
        c.callWs(JSONObject().put("type", "ping")) { ok, _, _ -> done.complete(ok) }
        assertTrue(done.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun aStalledUpgradeFailsInsteadOfHanging() {
        // A proxy that accepts TCP and then never answers the HTTP upgrade.
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val held = CopyOnWriteArrayList<java.net.Socket>()
            val acceptor = Thread { runCatching { while (true) held += server.accept() } }.apply { isDaemon = true; start() }
            try {
                // Bounded by the test itself: a client that stops enforcing its deadline fails here, not hangs.
                val call = CompletableFuture.supplyAsync {
                    runCatching { HaDashboardClient.connectLongLived("ws://127.0.0.1:${server.localPort}/api/websocket", upgradeTimeoutMs = 300) }
                }
                val outcome = call.get(5, TimeUnit.SECONDS)
                val e = outcome.exceptionOrNull()
                assertTrue("the upgrade timed out: $e", e is java.net.SocketTimeoutException && e.message == "upgrade took too long")
            } finally {
                runCatching { server.close() }
                held.forEach { runCatching { it.close() } }
                acceptor.interrupt()
            }
        }
    }

    @Test
    fun aDashboardHaRejectsIsReported() {
        val c = connected()
        c.fetchDashboard("missing")
        awaitCondition("failure reported") { listener.failedConfigs.isNotEmpty() }
        assertEquals(listOf<Pair<String?, String>>("missing" to "config_not_found"), listener.failedConfigs.toList())
    }

    @Test
    fun aConfigErrorThatMayPassIsAskedAgainBeforeAnythingIsReported() {
        // A12: HA still starting answers `unknown_command` until Lovelace is loaded. Only `config_not_found` means HA
        // has no config; anything else is asked again, and the config that comes is drawn.
        server.configFailures += listOf("unknown_command", "home_assistant_error")
        connected(dashboardPath = "wall-test", configRetryMs = longArrayOf(50, 50, 50))
        assertEquals("wall-test", listener.configs.single().first)
        assertTrue(listener.failedConfigs.toString(), listener.failedConfigs.isEmpty())
        assertEquals(3, server.commands("lovelace/config").size)
    }

    @Test
    fun aConfigErrorThatLastsIsReportedOnceItsRetriesAreSpent() {
        server.configError = "error" // a YAML dashboard that doesn't load
        val c = newClient(configRetryMs = longArrayOf(50, 50))
        c.start("wall-test")
        awaitCondition("failure reported") { listener.failedConfigs.isNotEmpty() }
        Thread.sleep(300)
        assertEquals(listOf<Pair<String?, String>>("wall-test" to "error"), listener.failedConfigs.toList())
        assertEquals("the first ask and two retries", 3, server.commands("lovelace/config").size)
        assertTrue(listener.configs.isEmpty())
    }

    @Test
    fun aNewerFetchEndsTheRetriesOfAnOlderOne() {
        server.configError = "unknown_command"
        val c = newClient(configRetryMs = longArrayOf(300, 300, 300))
        c.start("wall-test")
        awaitCondition("the first answer") { server.commands("lovelace/config").isNotEmpty() }
        server.configError = null
        c.fetchDashboard("other")
        awaitCondition("the other config") { listener.configs.any { it.first == "other" } }
        Thread.sleep(1_200)
        assertEquals("wall-test is never asked again after the move", 1, server.commands("lovelace/config").count { it.optString("url_path") == "wall-test" })
        assertTrue(listener.failedConfigs.isEmpty())
    }

    @Test
    fun aCallbackThatThrowsLeavesTheConnectionUp() {
        // A13: a throw in a result or event callback is that callback's alone, not a lost connection.
        val contained = CopyOnWriteArrayList<String>()
        VitroFailures.listener = { where, _, _ -> contained += where }
        val c = connected()
        c.callWs(JSONObject().put("type", "config/area_registry/list")) { _, _, _ -> throw IllegalStateException("a card's callback") }
        val templates = AtomicInteger(0)
        c.subscribeTemplate("{{ 1 }}") { templates.incrementAndGet(); throw IllegalStateException("a card's template callback") }
        awaitCondition("both contained") { contained.size >= 2 }
        // The same connection still answers.
        val done = CompletableFuture<Boolean>()
        c.callWs(JSONObject().put("type", "config/area_registry/list")) { ok, _, _ -> done.complete(ok) }
        assertTrue(done.get(10, TimeUnit.SECONDS))
        assertTrue(listener.disconnected.toString(), listener.disconnected.isEmpty())
        assertEquals(1, listener.connected.size)
        assertEquals(1, server.connectUrls.size)
        assertTrue(contained.toString(), contained.containsAll(listOf("socket result", "socket event")))
    }

    @Test
    fun aNewEntitySubscriptionDropsEntitiesThatAreGone() {
        val c = connected()
        awaitCondition("snapshot applied") { store["binary_sensor.front_door"] != null }
        // The door sensor was deleted while the panel was away; the next subscription's snapshot lacks it.
        server.initialEntities = JSONObject(FakeHaServer.ENTITIES_JSON).apply { remove("binary_sensor.front_door") }
        c.subscribeViewEntities(setOf("light.lounge_lamp", "binary_sensor.front_door"))
        awaitCondition("door removed") { store["binary_sensor.front_door"] == null }
        assertTrue(store["light.lounge_lamp"] != null)
    }

    @Test
    fun anUnexpectedGreetingIsReportedAsADisconnect() {
        server.greeting = { JSONObject().put("type", "hello") }
        newClient().start(null)
        awaitCondition("disconnect") { listener.disconnected.isNotEmpty() }
        assertTrue(listener.disconnected[0], listener.disconnected[0].contains("Unexpected greeting: hello"))
        assertTrue(listener.connected.isEmpty())
        assertTrue("nothing was sent to a server that did not greet", server.received.isEmpty())
    }

    @Test
    fun aServerThatNeverGreetsAndHangsUpIsReportedAsADisconnect() {
        server.greeting = { null }
        newClient().start(null)
        awaitCondition("socket") { server.sockets.isNotEmpty() }
        server.sockets[0].close()
        awaitCondition("disconnect") { listener.disconnected.isNotEmpty() }
        assertTrue(listener.disconnected[0], listener.disconnected[0].contains("Closed before greeting"))
    }

    @Test
    fun anInvalidHaUrlIsReportedAndNeverConnects() {
        newClient(haBase = "not a url").start(null)
        awaitCondition("disconnect") { listener.disconnected.isNotEmpty() }
        assertEquals("Invalid Home Assistant URL: not a url", listener.disconnected[0])
        assertTrue(server.connectUrls.isEmpty())
    }

    @Test
    fun anUnreachableServerIsReportedAndRetried() {
        server.refuseConnections = true
        newClient().start(null)
        awaitCondition("disconnect") { listener.disconnected.isNotEmpty() }
        assertTrue(listener.disconnected[0], listener.disconnected[0].contains("Connection refused"))
        // After the 1 s backoff it connects once the server is back.
        server.refuseConnections = false
        awaitCondition("reconnect", timeoutMs = 15_000) { listener.connected.isNotEmpty() }
    }

    // ---- Registries for web islands ----

    private fun withRegistries() {
        server.entityRegistryDisplay = JSONObject(
            """{"entity_categories": {"0": "config", "1": "diagnostic"}, "entities": [
                 {"ei": "light.lounge_lamp", "di": "dev-1", "pl": "hue", "lb": ["evening"]},
                 {"ei": "sensor.lamp_signal", "di": "dev-1", "ec": 1, "hb": true}]}""",
        )
        server.deviceRegistry = JSONArray("""[{"id": "dev-1", "name": "Ball Lamp", "area_id": "lounge"}]""")
        server.floorRegistry = JSONArray("""[{"floor_id": "ground", "name": "Ground floor", "level": 0}]""")
    }

    @Test
    fun registriesAreReadOnlyOnceSomeoneWatchesThem() {
        withRegistries()
        val c = connected()
        Thread.sleep(200)
        // The startup reads no floors and doesn't follow them: only islands need those. The entity, device and area
        // registries are followed on every connection, for the native cards.
        assertTrue(server.commands("config/floor_registry/list").isEmpty())
        assertNull(server.eventSubscriptionId("floor_registry_updated"))
        assertNotNull(server.eventSubscriptionId("device_registry_updated"))

        val sets = CopyOnWriteArrayList<JSONObject>()
        val release = c.watchRegistries { sets += it }
        awaitCondition("the registries") { sets.isNotEmpty() }
        val set = sets.single()
        assertEquals(setOf("entities", "devices", "areas", "floors"), set.keys().asSequence().toSet())
        assertEquals("dev-1", set.getJSONObject("entities").getJSONObject("light.lounge_lamp").getString("device_id"))
        assertEquals("diagnostic", set.getJSONObject("entities").getJSONObject("sensor.lamp_signal").getString("entity_category"))
        assertEquals("Ball Lamp", set.getJSONObject("devices").getJSONObject("dev-1").getString("name"))
        assertEquals("Lounge", set.getJSONObject("areas").getJSONObject("lounge").getString("name"))
        assertEquals(0, set.getJSONObject("floors").getJSONObject("ground").getInt("level"))
        // The islands' own: the floors. The other three are the native cards' too, and stay.
        val registrySubs = listOf("floor_registry_updated")
            .map { event -> server.eventSubscriptionId(event).also { assertNotNull(event, it) }!! }
        fun cancelled() = server.commands("unsubscribe_events").map { it.getInt("subscription") }.filter { it in registrySubs }

        // A second watcher shares the feed: nothing more is asked of Home Assistant, and it gets the set at once.
        val floorsAsked = server.commands("config/floor_registry/list").size
        val second = CopyOnWriteArrayList<JSONObject>()
        val releaseSecond = c.watchRegistries { second += it }
        assertEquals(1, second.size)
        Thread.sleep(100)
        assertEquals(floorsAsked, server.commands("config/floor_registry/list").size)

        // The last one to go ends the feed: its event subscriptions are cancelled.
        release()
        Thread.sleep(100)
        assertTrue(cancelled().isEmpty())
        releaseSecond()
        awaitCondition("registry events unsubscribed") { cancelled().toSet() == registrySubs.toSet() }
    }

    @Test
    fun aRegistryUpdateRereadsThatRegistryOnceItsBurstIsOver() {
        withRegistries()
        val c = connected()
        val sets = CopyOnWriteArrayList<JSONObject>()
        c.watchRegistries { sets += it }
        awaitCondition("the registries") { sets.size == 1 }
        val entitiesAsked = server.commands("config/entity_registry/list_for_display").size
        val devicesAsked = server.commands("config/device_registry/list").size

        server.deviceRegistry = JSONArray("""[{"id": "dev-1", "name": "Ball Lamp", "area_id": "lounge"}, {"id": "dev-2", "name": "Fan"}]""")
        val sub = server.eventSubscriptionId("device_registry_updated")!!
        repeat(5) { server.pushEvent(sub, JSONObject().put("event_type", "device_registry_updated").put("data", JSONObject())) }
        awaitCondition("the new devices") { sets.size == 2 }
        assertEquals(setOf("dev-1", "dev-2"), sets[1].getJSONObject("devices").keys().asSequence().toSet())
        assertEquals("one read for the burst", devicesAsked + 1, server.commands("config/device_registry/list").size)
        assertEquals("the other registries aren't read again", entitiesAsked, server.commands("config/entity_registry/list_for_display").size)
    }

    @Test
    fun aRefetchStillWaitingWhenTheLastWatcherGoesIsNeverSent() {
        withRegistries()
        val c = connected()
        val sets = CopyOnWriteArrayList<JSONObject>()
        val release = c.watchRegistries { sets += it }
        awaitCondition("the registries") { sets.size == 1 }
        val floorsAsked = server.commands("config/floor_registry/list").size
        val sub = server.eventSubscriptionId("floor_registry_updated")!!
        server.pushEvent(sub, JSONObject().put("event_type", "floor_registry_updated").put("data", JSONObject()))
        awaitCondition("the event is in") { c.javaClass.getDeclaredField("registryDue").apply { isAccessible = true }.let { (it.get(c) as Set<*>).isNotEmpty() } }
        release()
        Thread.sleep(HaDashboardClient.REGISTRY_DEBOUNCE_MS + 300)
        assertEquals(floorsAsked, server.commands("config/floor_registry/list").size)
        assertEquals(1, sets.size)
    }

    @Test
    fun aDeviceRenamedInHomeAssistantRedrawsTheNativeCardsWithoutAReconnect() {
        // A6: HA's frontend follows the entity, device and area registries all the time; so do the native cards.
        withRegistries()
        server.entityRegistryDisplay = JSONObject(
            """{"entities": [{"ei": "light.lounge_lamp", "di": "dev-1", "en": "Light"}, {"ei": "sensor.kitchen_temp", "dp": 1}]}""",
        )
        val c = connected()
        awaitCondition("the display data") { HaDisplay.deviceName("dev-1") == "Ball Lamp" && HaDisplay.areaName("lounge") == "Lounge" }
        val woken = AtomicInteger(0)
        store.subscribe("light.lounge_lamp") { woken.incrementAndGet() }

        // The device is renamed: one read of the device list for the burst, and every card draws again.
        server.deviceRegistry = JSONArray("""[{"id": "dev-1", "name": "Ball Lamp", "name_by_user": "Desk Lamp", "area_id": "lounge"}]""")
        val devicesAsked = server.commands("config/device_registry/list").size
        val devices = server.eventSubscriptionId("device_registry_updated")
        assertNotNull("the device registry is followed without any island", devices)
        repeat(3) { server.pushEvent(devices!!, JSONObject().put("event_type", "device_registry_updated").put("data", JSONObject())) }
        awaitCondition("the new name") { HaDisplay.deviceEntityName("light.lounge_lamp") == "Desk Lamp Light" }
        awaitCondition("the cards draw again") { woken.get() >= 1 }
        assertEquals("one read for the burst", devicesAsked + 1, server.commands("config/device_registry/list").size)

        // The sensor's display precision changes, and an area is renamed.
        server.entityRegistryDisplay = JSONObject(
            """{"entities": [{"ei": "light.lounge_lamp", "di": "dev-1", "en": "Light"}, {"ei": "sensor.kitchen_temp", "dp": 2}]}""",
        )
        server.pushEvent(server.eventSubscriptionId("entity_registry_updated")!!, JSONObject().put("event_type", "entity_registry_updated").put("data", JSONObject()))
        awaitCondition("the new precision") { HaDisplay.displayPrecision("sensor.kitchen_temp") == 2 }
        server.areaRegistry = JSONArray("""[{"area_id": "lounge", "name": "Living room"}]""")
        server.pushEvent(server.eventSubscriptionId("area_registry_updated")!!, JSONObject().put("event_type", "area_registry_updated").put("data", JSONObject()))
        awaitCondition("the new area name") { HaDisplay.areaName("lounge") == "Living room" }
        // Nobody asked for floors: the native cards don't draw them.
        assertTrue(server.commands("config/floor_registry/list").isEmpty())
        assertTrue(listener.disconnected.isEmpty())
        c.close()
    }

    @Test
    fun anAreaPutOnAFloorLoadsItsNameAndFollowsTheFloors() {
        withRegistries()
        val c = connected()
        awaitCondition("the areas") { HaDisplay.areaName("lounge") == "Lounge" }
        assertTrue("no area on a floor: no floors", server.commands("config/floor_registry/list").isEmpty())
        // The area is put on a floor in HA: an area event, no floor event.
        server.areaRegistry = JSONArray("""[{"area_id": "lounge", "name": "Lounge", "floor_id": "ground"}]""")
        server.pushEvent(server.eventSubscriptionId("area_registry_updated")!!, JSONObject().put("event_type", "area_registry_updated").put("data", JSONObject()))
        awaitCondition("the floor's name") { HaDisplay.floors["ground"] == "Ground floor" }
        // The floor is renamed: followed natively, with no island watching.
        val floors = server.eventSubscriptionId("floor_registry_updated")
        assertNotNull("the floors are followed once a name needs them", floors)
        server.floorRegistry = JSONArray("""[{"floor_id": "ground", "name": "Downstairs", "level": 0}]""")
        server.pushEvent(floors!!, JSONObject().put("event_type", "floor_registry_updated").put("data", JSONObject()))
        awaitCondition("the new floor name") { HaDisplay.floors["ground"] == "Downstairs" }
        c.close()
    }

    @Test
    fun aReconnectKeepsTheDisplayDataUntilTheNewConnectionsAnswersReplaceIt() {
        // A8: the new connection's entity snapshot draws every card before its registry answers are in. Until they
        // are, the cards keep the device names they had, not the plain friendly names.
        withRegistries()
        val atSnapshot = CopyOnWriteArrayList<String>()
        connected(connect = { url ->
            SpySocket(server.connect(url), before = { m ->
                if (m.optString("type") == "subscribe_entities") atSnapshot += HaDisplay.deviceName("dev-1") ?: "(none)"
            })
        })
        awaitCondition("the display data") { HaDisplay.deviceName("dev-1") == "Ball Lamp" }
        server.deviceRegistry = JSONArray("""[{"id": "dev-1", "name": "Ceiling Lamp"}]""")
        server.dropConnection()
        awaitCondition("the second snapshot", timeoutMs = 15_000) { atSnapshot.size == 2 }
        assertEquals("the same Home Assistant's names stay while the snapshot draws", "Ball Lamp", atSnapshot[1])
        // Then the new connection's answer replaces them.
        awaitCondition("the new name") { HaDisplay.deviceName("dev-1") == "Ceiling Lamp" }
    }

    /**
     * A socket whose reader waits while [open] is false: answers pile up, to be read in one go. [atGate] says the reader
     * is waiting there; a close lets it through.
     */
    private class GatedSocket(private val inner: HaSocket) : HaSocket {
        @Volatile var open = true
        @Volatile var atGate = false
        @Volatile private var closed = false
        override fun send(text: String) = inner.send(text)
        override fun receive(): String? {
            while (!open && !closed) {
                atGate = true
                Thread.sleep(5)
            }
            atGate = false
            return inner.receive()
        }
        override fun close() {
            closed = true
            inner.close()
        }
    }

    @Test
    fun aFeedStartedAgainOnTheSameConnectionTakesNoReadOfTheOneBefore() {
        // D12: the islands' feed stops and starts again on one connection while the first feed's reads are on their way.
        // Their answers belong to the stopped feed: the new one's first set is its own reads', with today's devices.
        withRegistries()
        val sockets = CopyOnWriteArrayList<GatedSocket>()
        val c = connected(connect = { url -> GatedSocket(server.connect(url)).also { sockets += it } })
        awaitCondition("the startup's display data") { HaDisplay.deviceName("dev-1") == "Ball Lamp" }
        val gate = sockets.last()
        gate.open = false
        // The reader may be inside a read already: one answer more gets it out, and it then waits at the gate. (If it
        // is at the gate already, the answer waits there too, and is read with the rest.)
        c.callWs(JSONObject().put("type", "ping")) { _, _, _ -> }
        awaitCondition("the reader at the gate") { gate.atGate }
        val first = c.watchRegistries {}
        first() // stopped before any of its reads is answered
        server.deviceRegistry = JSONArray("""[{"id": "dev-1", "name": "Floor Lamp", "area_id": "lounge"}]""")
        val sets = CopyOnWriteArrayList<JSONObject>()
        c.watchRegistries { sets += it }
        gate.open = true
        awaitCondition("the new feed's set") { sets.isNotEmpty() }
        Thread.sleep(300)
        assertEquals("one set, from the new feed's reads", 1, sets.size)
        assertEquals("Floor Lamp", sets[0].getJSONObject("devices").getJSONObject("dev-1").getString("name"))
    }

    @Test
    fun theRegistryFeedStartsAgainOnANewConnection() {
        withRegistries()
        val c = connected()
        val sets = CopyOnWriteArrayList<JSONObject>()
        c.watchRegistries { sets += it }
        awaitCondition("the registries") { sets.size == 1 }
        server.floorRegistry = null // an older Home Assistant: no floors, the others still go out
        server.dropConnection()
        awaitCondition("the registries again", timeoutMs = 15_000) { sets.size == 2 }
        assertEquals(0, sets[1].getJSONObject("floors").length())
        assertEquals("Ball Lamp", sets[1].getJSONObject("devices").getJSONObject("dev-1").getString("name"))
        assertNotNull(server.eventSubscriptionId("entity_registry_updated"))
    }

    // ---- Connection life ----

    @Test
    fun aDroppedConnectionReconnectsAndStartsOverOnTheNewSocket() {
        connected(dashboardPath = "wall-test")
        awaitCondition("entities") { store.size() == 4 }
        server.dropConnection()
        awaitCondition("second connection", timeoutMs = 15_000) { listener.connected.size == 2 && server.commands("subscribe_entities").size == 2 }
        assertEquals(2, server.connectUrls.size)
        assertEquals(2, server.commands("auth").size)
        awaitCondition("second config") { listener.configs.size == 2 }
        assertEquals("wall-test", listener.configs[1].first)
        // Ids keep counting up across connections, so a late reply can never match the wrong request.
        val ids = server.received.filter { it.has("id") }.map { it.getInt("id") }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun closeShutsTheSocketAndStopsReconnecting() {
        val c = connected()
        c.close()
        awaitCondition("socket closed") { server.sockets[0].closed }
        Thread.sleep(1_500) // longer than the reconnect backoff
        assertEquals(1, server.connectUrls.size)
        c.start("wall-test") // a closed client does not start again
        Thread.sleep(200)
        assertEquals(1, server.connectUrls.size)
    }

    // ---- Cards' subscriptions across connections (A §2, §4) ----

    @Test
    fun theForecastStaysSubscribedAndEveryListTheIntegrationPushesArrives() {
        val c = connected()
        val lists = LinkedBlockingQueue<List<dev.glassworks.engine.vitro.data.ForecastDay>>()
        c.subscribeForecast("weather.home", "daily") { lists.put(it) }
        assertEquals(14.0, lists.poll(10, TimeUnit.SECONDS)!!.single().temperature)
        val sub = server.commands("weather/subscribe_forecast").single()
        assertEquals("weather.home", sub.getString("entity_id"))
        assertEquals("daily", sub.getString("forecast_type"))
        // The integration's next update (an hour later, a new day): the card hears it, the subscription stays.
        val next = JSONArray("""[{"datetime": "2026-11-26T12:00:00+00:00", "condition": "rainy", "temperature": 9.5}]""")
        server.pushEvent(sub.getInt("id"), JSONObject().put("type", "daily").put("forecast", next))
        val second = lists.poll(10, TimeUnit.SECONDS)!!.single()
        assertEquals("2026-11-26T12:00:00+00:00", second.datetime)
        assertEquals("rainy", second.condition)
        assertTrue("nothing unsubscribed it", server.commands("unsubscribe_events").none { it.optInt("subscription") == sub.getInt("id") })
    }

    @Test
    fun everyHeldSubscriptionIsSentAgainAfterAReconnectAndKeepsDelivering() {
        val c = connected()
        val rendered = LinkedBlockingQueue<String>()
        val forecasts = LinkedBlockingQueue<List<dev.glassworks.engine.vitro.data.ForecastDay>>()
        val todos = LinkedBlockingQueue<JSONObject>()
        val errors = CopyOnWriteArrayList<JSONObject?>()
        c.subscribeTemplate("{{ now() }}") { rendered.put(it) }
        c.subscribeForecast("weather.home", "hourly") { forecasts.put(it) }
        val stopTodo = c.subscribeWs(JSONObject().put("type", "todo/item/subscribe").put("entity_id", "todo.shopping"), { todos.put(it) }, { errors += it })
        assertNotNull(rendered.poll(10, TimeUnit.SECONDS))
        assertNotNull(forecasts.poll(10, TimeUnit.SECONDS))
        assertNotNull(todos.poll(10, TimeUnit.SECONDS))

        server.dropConnection()
        awaitCondition("reconnected", timeoutMs = 15_000) { listener.connected.size == 2 }
        // The new connection carries all three again, each under a new id, with the same request.
        awaitCondition("resubscribed") {
            server.sockets.last().received.map { it.optString("type") }
                .containsAll(listOf("render_template", "weather/subscribe_forecast", "todo/item/subscribe"))
        }
        assertEquals("{{ now() }}", server.sockets.last().received.single { it.optString("type") == "render_template" }.getString("template"))
        assertEquals("hourly", server.sockets.last().received.single { it.optString("type") == "weather/subscribe_forecast" }.getString("forecast_type"))
        assertNotNull("the template renders again", rendered.poll(10, TimeUnit.SECONDS))
        assertNotNull("the forecast comes again", forecasts.poll(10, TimeUnit.SECONDS))
        assertNotNull("the list comes again", todos.poll(10, TimeUnit.SECONDS))
        // And keeps following HA on the new connection.
        server.pushEvent(server.lastId("render_template")!!, JSONObject().put("result", "later"))
        assertEquals("later", rendered.poll(10, TimeUnit.SECONDS))
        assertTrue("a lost connection is no error", errors.isEmpty())

        // Its handle ends it on the connection it is on now.
        stopTodo()
        val todoId = server.lastId("todo/item/subscribe")!!
        awaitCondition("unsubscribed on the new connection") {
            server.sockets.last().received.any { it.optString("type") == "unsubscribe_events" && it.optInt("subscription") == todoId }
        }
        assertEquals(2, c.heldCount())
    }

    @Test
    fun aSubscriptionAskedForWhileTheConnectionIsDownGoesOutOnceItIsBack() {
        val c = connected()
        server.refuseConnections = true
        server.dropConnection()
        awaitCondition("disconnected") { listener.disconnected.isNotEmpty() }
        // A card built during "Reconnecting…" (a tab tap): its template waits, it isn't refused.
        val rendered = LinkedBlockingQueue<String>()
        val errors = CopyOnWriteArrayList<JSONObject?>()
        c.subscribeTemplate("{{ 1 + 1 }}") { rendered.put(it) }
        c.subscribeWs(JSONObject().put("type", "todo/item/subscribe").put("entity_id", "todo.shopping"), {}, { errors += it })
        Thread.sleep(200)
        assertTrue(errors.isEmpty())
        server.refuseConnections = false
        awaitCondition("reconnected", timeoutMs = 15_000) { listener.connected.size == 2 }
        assertEquals("21.5 °C", rendered.poll(10, TimeUnit.SECONDS))
        assertEquals(1, server.sockets.last().received.count { it.optString("type") == "render_template" })
        assertTrue(errors.isEmpty())
    }

    @Test
    fun aHeldSubscriptionCancelledWhileTheConnectionIsDownIsNeverSent() {
        val c = connected()
        server.refuseConnections = true
        server.dropConnection()
        awaitCondition("disconnected") { listener.disconnected.isNotEmpty() }
        val stop = c.subscribeTemplate("{{ 1 + 1 }}") {}
        stop()
        server.refuseConnections = false
        awaitCondition("reconnected", timeoutMs = 15_000) { listener.connected.size == 2 && server.commands("subscribe_entities").size == 2 }
        Thread.sleep(200)
        assertTrue(server.commands("render_template").isEmpty())
        assertEquals(0, c.heldCount())
    }

    @Test
    fun aHeldSubscriptionHomeAssistantRefusesIsReportedOnceAndNotSentAgain() {
        val c = connected()
        val errors = CopyOnWriteArrayList<JSONObject?>()
        c.subscribeWs(JSONObject().put("type", "acme/unknown_subscribe"), {}, { errors += it })
        awaitCondition("refused") { errors.size == 1 }
        assertEquals("unknown_command", errors.single()?.optString("code"))
        server.dropConnection()
        awaitCondition("reconnected", timeoutMs = 15_000) { listener.connected.size == 2 && server.commands("subscribe_entities").size == 2 }
        Thread.sleep(200)
        assertEquals(1, server.commands("acme/unknown_subscribe").size)
        assertEquals(1, errors.size)
    }

    @Test
    fun aFreshSubscriptionIsBuiltAgainForEachConnectionAndSaysWhenItStartsOver() {
        val c = connected()
        val built = AtomicInteger(0)
        val events = LinkedBlockingQueue<Pair<String, Boolean>>()
        c.subscribeWsFresh({
            // A history window that follows the clock: each connection asks for the one it has then.
            JSONObject().put("type", "render_template").put("template", "window ${built.incrementAndGet()}")
        }, { ev, first -> events.put(ev.optString("result") to first) }, {})
        assertEquals("21.5 °C" to true, events.poll(10, TimeUnit.SECONDS))
        server.pushEvent(server.lastId("render_template")!!, JSONObject().put("result", "more"))
        assertEquals("more" to false, events.poll(10, TimeUnit.SECONDS))
        server.dropConnection()
        awaitCondition("reconnected", timeoutMs = 15_000) { listener.connected.size == 2 }
        assertEquals("the new connection's first event starts the stream over", "21.5 °C" to true, events.poll(10, TimeUnit.SECONDS))
        assertEquals(2, built.get())
        assertEquals("window 2", server.sockets.last().received.single { it.optString("type") == "render_template" }.getString("template"))
    }

    @Test
    fun aOneOffSubscriptionIsNotSentAgainOnTheNextConnection() {
        // A camera's WebRTC offer belongs to its connection: it isn't replayed on the next one.
        val c = connected()
        c.subscribeWs(JSONObject().put("type", "todo/item/subscribe").put("entity_id", "todo.once"), {}, {}, resubscribe = false)
        awaitCondition("sent") { server.commands("todo/item/subscribe").size == 1 }
        server.dropConnection()
        awaitCondition("reconnected", timeoutMs = 15_000) { listener.connected.size == 2 && server.commands("subscribe_entities").size == 2 }
        Thread.sleep(200)
        assertEquals(1, server.commands("todo/item/subscribe").size)
    }

    // ---- Liveness and the token on a live connection (A §1, §7) ----

    @Test
    fun aHalfOpenConnectionIsNoticedAndReconnected() {
        // Nothing comes back, not even a close: HA's host lost power, or a router dropped the connection's state.
        val c = newClient(keepAliveMs = 150, pongTimeoutMs = 150)
        c.subscribeViewEntities(emptySet())
        c.start(null)
        awaitCondition("connected") { listener.connected.size == 1 && server.commands("subscribe_entities").isNotEmpty() }
        server.answerPings = false
        awaitCondition("dropped as silent", timeoutMs = 5_000) { listener.disconnected.isNotEmpty() }
        assertEquals(HaDashboardClient.NO_ANSWER, listener.disconnected.first())
        assertTrue(server.sockets[0].closed)
        server.answerPings = true
        awaitCondition("reconnected", timeoutMs = 15_000) { listener.connected.size == 2 }
    }

    @Test
    fun aConnectionThatAnswersItsPingsIsKept() {
        val c = newClient(keepAliveMs = 100, pongTimeoutMs = 300)
        c.start(null)
        awaitCondition("connected") { listener.connected.size == 1 }
        // Past several pong timeouts (the first lands with the fourth ping): each answered ping kept the connection.
        awaitCondition("several pings", timeoutMs = 5_000) { server.commands("ping").size >= 6 }
        assertTrue(listener.disconnected.isEmpty())
        assertEquals(1, server.connectUrls.size)
    }

    @Test
    fun aConnectionLivingOnOtherTrafficWithoutPongsLeavesNoPingBehind() {
        // HA streams events but never answers a ping: the connection lives, and no ping's callback piles up.
        val c = newClient(keepAliveMs = 50, pongTimeoutMs = 50)
        c.start(null)
        awaitCondition("connected") { listener.connected.size == 1 && server.commands("subscribe_events").isNotEmpty() }
        server.answerPings = false
        val traffic = Thread {
            try {
                while (true) {
                    // An event for no subscription: the reader hears it and drops it.
                    server.pushEvent(99_999, JSONObject().put("event_type", "noise"))
                    Thread.sleep(10)
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        try {
            awaitCondition("many unanswered pings", timeoutMs = 5_000) { server.commands("ping").size >= 10 }
            assertTrue("kept alive by the traffic", listener.disconnected.isEmpty())
            // At most the pings whose checks haven't run yet (a tick and the last check may meet), not one per ping.
            assertTrue("pending: ${c.pendingCount()}", c.pendingCount() <= 2)
        } finally {
            traffic.interrupt()
        }
    }

    @Test
    fun theTokenIsAskedForAgainWhileTheConnectionLasts() {
        // HA's access tokens last 30 minutes and an authenticated socket never asks again on its own: the client does,
        // so the provider (which caches until a minute before expiry) hands its consumers a fresh one in time.
        val given = CopyOnWriteArrayList<String?>()
        val c = newClient(keepAliveMs = 100, tokens = { force, done ->
            tokenCalls += force
            val t = if (tokenCalls.size > 2) "refreshed-token" else FakeHaServer.TOKEN
            given += t
            done(t)
        })
        c.start(null)
        awaitCondition("asked again on the live connection", timeoutMs = 5_000) { given.contains("refreshed-token") }
        assertTrue("never forced: the provider decides when to refresh", tokenCalls.none { it })
        assertEquals("the connection stays", 1, server.connectUrls.size)
    }
}
