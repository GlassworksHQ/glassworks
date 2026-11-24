/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.auth.HaSocket
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDashboardClient
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        override fun onConnected(haVersion: String) { connected += haVersion }
        override fun onAuthRequired() { authRequired.incrementAndGet() }
        override fun onDashboards(dashboards: List<DashboardSummary>) { this.dashboards += dashboards }
        override fun onResources(resources: List<LovelaceResource>) { this.resources += resources }
        override fun onThemes(catalog: ThemeCatalog) { themes += catalog }
        override fun onDashboardConfig(urlPath: String?, config: DashboardConfig) { configs += urlPath to config }
        override fun onDisconnected(reason: String) { disconnected += reason }
        override fun onUserTheme(themeName: String?, dark: Boolean?) { userThemes += themeName to dark }
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
    ): HaDashboardClient {
        val c = HaDashboardClient(
            haBase = haBase,
            tokenProvider = tokens,
            store = store,
            connect = connect,
            listener = listener,
        )
        clients += c
        return c
    }

    /** A started client with the whole startup sequence done (the first entity subscription is on the wire). */
    private fun connected(dashboardPath: String? = null, connect: (String) -> HaSocket = server::connect): HaDashboardClient {
        val c = newClient(connect = connect)
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
        val sent = server.received.toList().drop(1) // after `auth`
        assertEquals(
            listOf(
                "get_config", "frontend/get_themes", "subscribe_events", "lovelace/dashboards/list",
                "lovelace/resources", "lovelace/config", "subscribe_events", "subscribe_entities",
            ),
            sent.take(8).map { it.getString("type") },
        )
        assertEquals((1..8).toList(), sent.take(8).map { it.getInt("id") })
        assertEquals(
            listOf("themes_updated", "lovelace_updated"),
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
        awaitCondition("currentUser") { c.currentUser.optString("id") == "u1" }
        assertEquals("Wall Panel", c.currentUser.getString("name"))
        assertFalse(c.currentUser.getBoolean("is_admin"))
        assertEquals(JSONObject().toString(), newClient().currentUser.toString()) // empty until it arrives
        assertEquals(1, server.commands("auth/current_user").size)
    }

    @Test
    fun selectedThemeAsAStringReachesTheListener() {
        connected(connect = userDataConnect(JSONObject(), JSONObject("""{"selectedTheme": "glass_dusk"}""")))
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
        val sub = server.commands("frontend/subscribe_user_data").single()
        assertEquals("core", sub.getString("key"))
        server.pushEvent(sub.getInt("id"), JSONObject("""{"value": {"selectedTheme": {"theme": "night", "dark": false}}}"""))
        awaitCondition("change") { listener.userThemes.size == 2 }
        assertEquals("night" to false, listener.userThemes[1])
        server.pushEvent(sub.getInt("id"), JSONObject("""{"value": {}}"""))
        awaitCondition("cleared") { listener.userThemes.size == 3 }
        assertEquals(null to null, listener.userThemes[2])
    }

    @Test
    fun theUserRequestsComeAfterTheFirstEightStartupRequests() {
        connected(connect = userDataConnect(JSONObject(), null))
        awaitCondition("user data asked") { server.commands("frontend/subscribe_user_data").isNotEmpty() }
        val types = server.received.toList().drop(1).map { it.getString("type") }
        assertEquals(
            listOf("auth/current_user", "frontend/get_user_data", "frontend/subscribe_user_data"),
            types.drop(8).take(3),
        )
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
    fun compressedDiffEventsUpdateTheStoreAndWakeItsSubscribers() {
        connected()
        awaitCondition("entities") { store.size() == 4 }
        val woken = AtomicInteger(0)
        store.subscribe("light.lounge_lamp") { woken.incrementAndGet() }
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
        assertTrue(server.commands("config/area_registry/list").single().getInt("id") > 0)
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

        val msg = server.commands("history/stream").single()
        assertEquals(listOf("sensor.kitchen_temp", "sensor.other"), msg.getJSONArray("entity_ids").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertTrue(msg.getBoolean("minimal_response"))
        assertFalse(msg.getBoolean("significant_changes_only"))
        val start = Instant.parse(msg.getString("start_time"))
        val expected = Instant.now().minusSeconds(6 * 3600L)
        assertTrue("start_time is 6 hours back: $start vs $expected", kotlin.math.abs(start.epochSecond - expected.epochSecond) < 120)

        // One-shot: the client unsubscribes after the first series.
        val subId = msg.getInt("id")
        awaitCondition("history unsubscribe") { server.commands("unsubscribe_events").any { it.getInt("subscription") == subId } }
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
    fun historyUnsubscribesEvenWhenTheServerAnswersAtOnce() {
        // The event arrives as the request leaves the wire, before the caller can have recorded the subscription id.
        server.history = JSONObject("""{"states": {"sensor.kitchen_temp": [{"s": "20.5", "lu": 1700000000.0}]}}""")
        val c = connected(connect = { url ->
            SpySocket(server.connect(url), after = { msg ->
                if (msg.optString("type") == "history/stream") server.pushEvent(msg.getInt("id"), server.history)
            })
        })
        val got = LinkedBlockingQueue<Map<String, List<HistoryPoint>>>()
        c.fetchHistory(listOf("sensor.kitchen_temp")) { got.put(it) }
        assertEquals(listOf(HistoryPoint(1700000000.0, 20.5)), got.poll(10, TimeUnit.SECONDS)!!.getValue("sensor.kitchen_temp"))
        val subId = server.commands("history/stream").single().getInt("id")
        awaitCondition("history unsubscribe") { server.commands("unsubscribe_events").any { it.getInt("subscription") == subId } }
        Thread.sleep(150) // the server's own delayed copy of the series must not call back again
        assertNull(got.poll())
    }

    @Test
    fun socketWritesLeaveTheCallingThreadAndKeepTheirOrder() {
        // On Android the caller is the main thread, where a socket write throws NetworkOnMainThreadException.
        val writers = CopyOnWriteArrayList<Pair<String, String>>() // message type to writing thread
        val c = connected(connect = { url ->
            SpySocket(server.connect(url), before = { msg -> writers += msg.optString("type") to Thread.currentThread().name })
        })
        awaitCondition("startup writes") { writers.size >= 12 } // auth + 8 startup requests + 3 user-data requests
        val caller = Thread.currentThread()

        c.callService("light", "turn_on")
        c.callService("light", "turn_off")
        c.fetchHistory(listOf("sensor.kitchen_temp"), 1) {}
        c.callService("light", "toggle")
        awaitCondition("queued writes") { server.commands("history/stream").isNotEmpty() && server.commands("call_service").size == 3 }

        val sent = writers.drop(1) // everything after `auth`
        assertTrue("every post-auth write is on the writer thread: $sent", sent.all { it.second == "vitro-ha-send" })
        assertNotEquals("vitro-ha-send", caller.name)
        assertEquals("auth", writers[0].first)
        assertNotEquals("vitro-ha-send", writers[0].second) // the handshake itself is inline on the reader

        // FIFO: ids go out in the order the calls were made, across the reader's and the caller's requests.
        val ids = server.received.drop(1).map { it.getInt("id") }
        assertEquals(ids.sorted(), ids)
        // Display data (registry, icons, translations) can interleave: it isn't what this test orders.
        val display = setOf("config/entity_registry/list_for_display", "config/device_registry/list", "frontend/get_icons", "frontend/get_translations")
        val after = server.received.drop(1).filter { it.getInt("id") > 11 && it.getString("type") !in display }.map {
            if (it.getString("type") == "call_service") it.getString("service") else it.getString("type")
        }
        assertEquals(listOf("turn_on", "turn_off", "history/stream", "toggle"), after.take(4))
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
        c.fetchHistory(emptyList()) { throw AssertionError("no callback expected") }
        assertTrue(server.commands("history/stream").isEmpty())
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
        assertEquals(listOf(false, true, false), tokenCalls.toList())
        assertTrue("the rejected socket was closed", server.sockets[0].closed)
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
}
