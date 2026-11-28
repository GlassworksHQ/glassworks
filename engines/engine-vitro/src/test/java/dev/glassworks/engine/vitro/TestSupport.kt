/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import dev.glassworks.auth.HaSocket
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.PageHost
import dev.glassworks.engine.PagePort
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Polls [cond] until it holds, running [tick] before each check (Robolectric tests pass a looper idle there).
 * Fails after [timeoutMs]. Background threads (the HA socket reader) are why this polls instead of joining.
 */
fun awaitCondition(
    message: String,
    timeoutMs: Long = 10_000,
    tick: () -> Unit = {},
    cond: () -> Boolean,
) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (true) {
        tick()
        if (cond()) return
        if (System.nanoTime() > deadline) throw AssertionError("Timed out after $timeoutMs ms waiting for: $message")
        Thread.sleep(10)
    }
}

/**
 * One hardware frame of [v]'s window, as Android's renderer draws it (Robolectric's native graphics record real display
 * lists): pending messages run first (unless not [idle]: a frame before a posted swap runs), then [beforeFrame], then the root records again only if it was invalidated
 * itself. Otherwise each parent rebuilds just the display lists of the views that changed below it, straight from its
 * own list, without running its draw or `drawChild`. A throw comes out as it would on a panel.
 */
fun hardwareFrame(v: View, idle: Boolean = true, beforeFrame: () -> Unit = {}) {
    if (idle) org.robolectric.shadows.ShadowLooper.idleMainLooper()
    beforeFrame()
    val root = v.rootView
    val recreate = View::class.java.getDeclaredField("mRecreateDisplayList").apply { isAccessible = true }
    recreate.setBoolean(root, root.isInvalidated())
    privateFlags.setInt(root, privateFlags.getInt(root) and pflagInvalidated.inv())
    try {
        View::class.java.getDeclaredMethod("updateDisplayListIfDirty").apply { isAccessible = true }.invoke(root)
    } catch (e: java.lang.reflect.InvocationTargetException) {
        throw e.cause ?: e
    } finally {
        recreate.setBoolean(root, false)
    }
}

// Lazily: the file's other helpers run in plain JVM tests too, where View is a stub without these fields.
private val privateFlags by lazy { View::class.java.getDeclaredField("mPrivateFlags").apply { isAccessible = true } }
private val pflagInvalidated by lazy { View::class.java.getDeclaredField("PFLAG_INVALIDATED").apply { isAccessible = true }.getInt(null) }

/** The view asked to record its display list again ([View.invalidate]) and hasn't yet. */
fun View.isInvalidated(): Boolean = privateFlags.getInt(this) and pflagInvalidated != 0

/**
 * An in-memory Home Assistant WebSocket API, speaking just enough of the real protocol for [HaDashboardClient]:
 * `auth_required` -> `auth` -> `auth_ok` / `auth_invalid`, then request/`result` pairs and `event` pushes.
 *
 * Every message the client sends lands in [received] in order, so tests assert on the wire. [connect] has the
 * `(String) -> HaSocket` shape both [HaDashboardClient] and `VitroEngine` take. Addresses are RFC 5737.
 */
/**
 * Serves no HA frontend files (HaFrontend) for each test, and puts back the fetch that was there after it. HA's
 * frontend files are fetched over HTTP, not through [FakeHaServer]'s socket: without this a test's clients would go to
 * the network for them (192.0.2.10 only times out, on the one thread every frontend read shares). A test that serves
 * them sets [dev.glassworks.engine.vitro.data.HaFrontend.fetch] itself, after this has run.
 */
class NoHaFrontendFetch : org.junit.rules.ExternalResource() {
    private var saved: ((String) -> ByteArray?)? = null

    override fun before() {
        saved = dev.glassworks.engine.vitro.data.HaFrontend.fetch
        dev.glassworks.engine.vitro.data.HaFrontend.fetch = { null }
    }

    override fun after() {
        saved?.let { dev.glassworks.engine.vitro.data.HaFrontend.fetch = it }
    }
}

class FakeHaServer {
    companion object {
        const val TOKEN = "test-token"
        const val BASE = "http://192.0.2.10:8123"
        private const val CLOSED = "\u0000closed\u0000"

        /** Home Assistant's `subscribe_entities` initial `a` payload for a made-up home. */
        const val ENTITIES_JSON = """{
          "light.lounge_lamp": {"s": "on", "a": {"friendly_name": "Lounge Lamp", "brightness": 128, "rgb_color": [255, 180, 60]}, "lc": 1700000000.5, "lu": 1700000001.0},
          "sensor.kitchen_temp": {"s": "21.5", "a": {"friendly_name": "Kitchen Temperature", "unit_of_measurement": "°C", "device_class": "temperature"}, "lc": 1700000000.0, "lu": 1700000002.0},
          "binary_sensor.front_door": {"s": "off", "a": {"friendly_name": "Front Door", "device_class": "door"}, "lc": 1700000000.0, "lu": 1700000000.0},
          "sensor.unreferenced_power": {"s": "340", "a": {"unit_of_measurement": "W"}, "lc": 1700000000.0, "lu": 1700000000.0}
        }"""

        /** A dashboard with a Sections view and a Panel view holding one unknown custom card (a Tier 3 island). */
        const val DASHBOARD_JSON = """{
          "title": "Test Home",
          "views": [
            {"title": "Living", "path": "living", "type": "sections", "sections": [
              {"type": "grid", "cards": [
                {"type": "tile", "entity": "light.lounge_lamp"},
                {"type": "entities", "title": "Sensors", "entities": ["sensor.kitchen_temp", "binary_sensor.front_door"]},
                {"type": "custom:mushroom-entity-card", "entity": "light.lounge_lamp"}
              ]}
            ]},
            {"title": "Wall", "path": "wall", "type": "panel", "cards": [
              {"type": "custom:acme-wall-card", "rooms": [{"name": "Lounge", "lights": ["light.lounge_lamp"]}]}
            ]}
          ]
        }"""
    }

    /** Tokens `auth` accepts. Empty means every token is `auth_invalid`. */
    @Volatile var acceptedTokens: Set<String> = setOf(TOKEN)
    @Volatile var haVersion: String = "2026.11.0"

    /** What a new connection hears first. Null sends nothing (the client waits for a greeting). */
    @Volatile var greeting: () -> JSONObject? = { JSONObject().put("type", "auth_required").put("ha_version", haVersion) }

    /** Makes [connect] throw, like an unreachable host. */
    @Volatile var refuseConnections: Boolean = false

    @Volatile var haConfig: JSONObject = JSONObject(
        """{"location_name": "Test Home", "time_zone": "UTC", "unit_system": {"temperature": "°C", "length": "km"}}""",
    )
    @Volatile var themes: JSONObject = JSONObject(
        """{"default_theme": "default", "default_dark_theme": "glass_night", "themes": {
             "glass_night": {"primary-color": "#ff5722", "primary-background-color": "#0a0a14"},
             "night": {"primary-color": "#123456", "modes": {"dark": {"primary-text-color": "#ffffff"}}}}}""",
    )
    @Volatile var dashboards: org.json.JSONArray = org.json.JSONArray(
        """[{"url_path": "wall-test", "title": "Wall Test", "icon": "mdi:tablet", "require_admin": false},
            {"url_path": "", "title": "No path, skipped"},
            {"url_path": "admin-only", "require_admin": true}]""",
    )
    @Volatile var resources: org.json.JSONArray = org.json.JSONArray(
        """[{"url": "/hacsfiles/acme/acme-card.js", "type": "module"},
            {"url": "/local/island.css", "type": "css"},
            {"url": "/local/legacy.js"},
            {"type": "module"}]""",
    )
    @Volatile var dashboardConfig: JSONObject = JSONObject(DASHBOARD_JSON)

    /** Dashboards HA has no saved config for: `lovelace/config` answers `config_not_found`. */
    @Volatile var missingDashboards: Set<String> = setOf("missing")

    /**
     * Error codes the next `lovelace/config` requests answer with, one per request, before the config (HA still
     * starting answers `unknown_command`). Empty: every request is answered as usual.
     */
    val configFailures = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** `lovelace/config` answers every request with this error code; null answers as usual. */
    @Volatile var configError: String? = null
    @Volatile var initialEntities: JSONObject = JSONObject(ENTITIES_JSON)
    @Volatile var history: JSONObject = JSONObject("""{"states": {}}""")
    /** `history/history_during_period` answers with an error. */
    @Volatile var failHistory: Boolean = false
    @Volatile var templateResult: Any = "21.5 °C"

    /** `weather/subscribe_forecast`'s first event: the forecast list (HA's `forecast` array). */
    @Volatile var forecast: org.json.JSONArray = org.json.JSONArray(
        """[{"datetime": "2026-11-25T12:00:00+00:00", "condition": "sunny", "temperature": 14.0, "templow": 6.0}]""",
    )

    /**
     * False: a `ping` goes unanswered, as on a half-open connection (HA's host lost power, a router dropped it) where
     * nothing reaches the panel and nothing says so.
     */
    @Volatile var answerPings: Boolean = true

    /**
     * `auth/current_user`'s answer, the signed-in user: an administrator unless a test says otherwise; null refuses it
     * (`unknown_command`).
     */
    @Volatile var currentUser: JSONObject? = JSONObject("""{"id": "wall-user", "name": "Wall Panel", "is_owner": false, "is_admin": true}""")

    /** `lovelace/dashboards/list` answers with an error. */
    @Volatile var failDashboardList: Boolean = false

    /**
     * `frontend/update_panel`'s `require_admin` overrides, by panel: what `get_panels` applies over the dashboard's own
     * `require_admin` (HA core 2026.11's `websocket_get_panels`), and `lovelace/dashboards/list` doesn't show.
     */
    val panelRequireAdmin = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** `get_panels` answers with an error. */
    @Volatile var failPanels: Boolean = false

    /**
     * `get_panels` as HA core 2026.11 answers it for [currentUser]: the default dashboard (`lovelace`) and every listed
     * or config-less dashboard, each with its override over its own `require_admin`, an admin-only one only for an
     * administrator.
     */
    fun panels(): JSONObject {
        val admin = currentUser?.optBoolean("is_admin", false) == true
        val own = LinkedHashMap<String, Boolean>().apply { put("lovelace", false) }
        for (i in 0 until dashboards.length()) {
            val d = dashboards.optJSONObject(i) ?: continue
            val path = d.optString("url_path", "").ifEmpty { continue }
            own[path] = d.optBoolean("require_admin", false)
        }
        missingDashboards.forEach { own.putIfAbsent(it, false) }
        val out = JSONObject()
        for ((path, requireAdmin) in own) {
            val effective = panelRequireAdmin[path] ?: requireAdmin
            if (effective && !admin) continue
            out.put(path, JSONObject().put("component_name", "lovelace").put("url_path", path).put("require_admin", effective))
        }
        return out
    }

    /** HA changed its panels (`frontend/update_panel`, a dashboard added or removed): `panels_updated` on the newest connection. */
    fun pushPanelsUpdated() {
        pushEvent(eventSubscriptionId("panels_updated")!!, JSONObject().put("event_type", "panels_updated"))
    }

    /** `auth/current_user` goes unanswered, for a test to answer it later (on [sockets]). */
    @Volatile var holdCurrentUser: Boolean = false

    /**
     * `subscribe_entities` is acknowledged but its first event (the snapshot) never comes, as from a Home Assistant
     * still gathering it: a view's first build keeps waiting for its states. (An empty snapshot ends that wait at once:
     * every entity is then known not to exist.)
     */
    @Volatile var holdEntitySnapshot: Boolean = false

    /** `subscribe_entities` asking for any of these ids is refused (`home_assistant_error`), as by an HA in trouble. */
    @Volatile var refuseEntityIds: Set<String> = emptySet()

    /** `config/entity_registry/list_for_display`'s answer; null refuses it (`unknown_command`). */
    @Volatile var entityRegistryDisplay: JSONObject? = null

    /** `config/device_registry/list`'s answer; null refuses it. */
    @Volatile var deviceRegistry: org.json.JSONArray? = null

    /** `config/area_registry/list`'s answer. */
    @Volatile var areaRegistry: org.json.JSONArray = org.json.JSONArray("""[{"area_id": "lounge", "name": "Lounge"}]""")

    /** `config/floor_registry/list`'s answer; null refuses it, as a Home Assistant without floors does. */
    @Volatile var floorRegistry: org.json.JSONArray? = null

    /** Everything the client sent, across every connection, in order. `auth` messages included. */
    val received = CopyOnWriteArrayList<JSONObject>()
    val sockets = CopyOnWriteArrayList<Socket>()
    val connectUrls = CopyOnWriteArrayList<String>()

    /** Orders a connection's registration against [shutdown]: shutdown can't miss a socket that is just opening. */
    private val socketsLock = Any()

    fun connect(url: String): HaSocket {
        val sock = synchronized(socketsLock) {
            if (refuseConnections) throw IOException("Connection refused")
            connectUrls += url
            Socket().also { sockets += it }
        }
        sock.greet() // outside the lock: a test's greeting may take its time
        return sock
    }

    fun commands(type: String): List<JSONObject> = received.filter { it.optString("type") == type }

    /** The id the client gave its `subscribe_events` for [eventType] on the newest connection ([pushEvent] uses it). */
    fun eventSubscriptionId(eventType: String): Int? =
        sockets.lastOrNull()?.received?.firstOrNull { it.optString("type") == "subscribe_events" && it.optString("event_type") == eventType }
            ?.optInt("id")

    /** The id of the newest connection's last command of [type] (a subscription's, for [pushEvent]). */
    fun lastId(type: String): Int? = sockets.lastOrNull()?.received?.lastOrNull { it.optString("type") == type }?.optInt("id")

    /** Pushes an `event` message for subscription [subscriptionId] over the newest connection. */
    fun pushEvent(subscriptionId: Int, event: JSONObject) {
        sockets.last().push(JSONObject().put("id", subscriptionId).put("type", "event").put("event", event))
    }

    /** The server hangs up on the newest connection. */
    fun dropConnection() {
        sockets.last().close()
    }

    /** Closes every connection and refuses new ones, so reader threads wind down after a test. */
    fun shutdown() {
        val open = synchronized(socketsLock) {
            refuseConnections = true
            sockets.toList()
        }
        open.forEach { it.close() }
    }

    inner class Socket : HaSocket {
        private val inbox = LinkedBlockingQueue<String>()
        @Volatile var closed = false
            private set

        /** What the client sent on this connection only ([FakeHaServer.received] has every connection's). */
        val received = CopyOnWriteArrayList<JSONObject>()

        internal fun greet() {
            greeting()?.let { inbox.offer(it.toString()) }
        }

        fun push(o: JSONObject) {
            inbox.offer(o.toString())
        }

        override fun send(text: String) {
            if (closed) throw IOException("Socket closed")
            handle(this, JSONObject(text))
        }

        override fun receive(): String? {
            val s = inbox.take()
            if (s == CLOSED) {
                inbox.offer(CLOSED) // every later receive() also sees the close
                return null
            }
            return s
        }

        override fun close() {
            closed = true
            inbox.offer(CLOSED)
        }
    }

    private fun handle(sock: Socket, m: JSONObject) {
        received += m
        sock.received += m
        val id = m.optInt("id", -1)
        when (m.optString("type")) {
            "auth" ->
                if (m.optString("access_token") in acceptedTokens) {
                    sock.push(JSONObject().put("type", "auth_ok").put("ha_version", haVersion))
                } else {
                    sock.push(JSONObject().put("type", "auth_invalid").put("message", "Invalid access token or password"))
                }
            "get_config" -> ok(sock, id, haConfig)
            "frontend/get_themes" -> ok(sock, id, themes)
            "subscribe_events", "unsubscribe_events" -> ok(sock, id, JSONObject.NULL)
            "lovelace/dashboards/list" -> if (failDashboardList) fail(sock, id, "unknown_error") else ok(sock, id, dashboards)
            "get_panels" -> if (failPanels) fail(sock, id, "unknown_error") else ok(sock, id, panels())
            "auth/current_user" -> if (!holdCurrentUser) currentUser?.let { ok(sock, id, it) } ?: fail(sock, id, "unknown_command")
            "lovelace/resources" -> ok(sock, id, resources)
            "lovelace/config" -> {
                val error = configError ?: configFailures.poll()
                when {
                    m.optString("url_path") in missingDashboards -> fail(sock, id, "config_not_found")
                    error != null -> fail(sock, id, error)
                    else -> ok(sock, id, dashboardConfig)
                }
            }
            "subscribe_entities" -> {
                val wanted = m.optJSONArray("entity_ids")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
                // As HA's schema (`cv.entity_ids`): one malformed id fails the whole command.
                if (wanted?.any { !dev.glassworks.engine.vitro.data.HaDashboardClient.isValidEntityId(it) } == true) return fail(sock, id, "invalid_format")
                if (wanted?.any { it in refuseEntityIds } == true) return fail(sock, id, "home_assistant_error")
                ok(sock, id, JSONObject.NULL)
                if (holdEntitySnapshot) return
                val initial = JSONObject()
                val keys = initialEntities.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (wanted == null || k in wanted) initial.put(k, initialEntities.get(k))
                }
                event(sock, id, JSONObject().put("a", initial))
            }
            "call_service" -> ok(sock, id, JSONObject().put("context", JSONObject().put("id", "ctx-1")))
            "ping" -> if (answerPings) sock.push(JSONObject().put("id", id).put("type", "pong"))
            "weather/subscribe_forecast" -> {
                ok(sock, id, JSONObject.NULL)
                event(sock, id, JSONObject().put("type", m.optString("forecast_type")).put("forecast", forecast))
            }
            "todo/item/subscribe" -> {
                ok(sock, id, JSONObject.NULL)
                event(sock, id, JSONObject().put("items", org.json.JSONArray()))
            }
            "history/history_during_period" ->
                if (failHistory) fail(sock, id, "unknown_error") else ok(sock, id, history.optJSONObject("states") ?: JSONObject())
            "render_template" -> {
                ok(sock, id, JSONObject.NULL)
                event(sock, id, JSONObject().put("result", templateResult))
            }
            "config/area_registry/list" -> ok(sock, id, areaRegistry)
            "config/entity_registry/list_for_display" ->
                entityRegistryDisplay?.let { ok(sock, id, it) } ?: fail(sock, id, "unknown_command")
            "config/device_registry/list" -> deviceRegistry?.let { ok(sock, id, it) } ?: fail(sock, id, "unknown_command")
            "config/floor_registry/list" -> floorRegistry?.let { ok(sock, id, it) } ?: fail(sock, id, "unknown_command")
            else -> fail(sock, id, "unknown_command")
        }
    }

    private fun ok(sock: Socket, id: Int, result: Any) {
        sock.push(JSONObject().put("id", id).put("type", "result").put("success", true).put("result", result))
    }

    private fun fail(sock: Socket, id: Int, code: String) {
        sock.push(
            JSONObject().put("id", id).put("type", "result").put("success", false)
                .put("error", JSONObject().put("code", code).put("message", "Failed: $code")),
        )
    }

    private fun event(sock: Socket, id: Int, ev: JSONObject) {
        sock.push(JSONObject().put("id", id).put("type", "event").put("event", ev))
    }
}

/** A [PageHost] that records every call. Call it on the main thread, like the real host. */
class RecordingHost : PageHost {
    val pageStarts = CopyOnWriteArrayList<String>()
    val pageStops = CopyOnWriteArrayList<Boolean>()
    val firstPaints = AtomicInteger(0)
    val locations = CopyOnWriteArrayList<String?>()
    val ports = CopyOnWriteArrayList<PagePort>()
    val portMessages = CopyOnWriteArrayList<JSONObject>()
    @Volatile var redirectTo: (String) -> String? = { null }

    override fun onPageStart(url: String) { pageStarts += url }
    override fun onPageStop(success: Boolean) { pageStops += success }
    override fun onFirstPaint() { firstPaints.incrementAndGet() }
    override fun onLocationChange(url: String?) { locations += url }
    override fun redirect(url: String): String? = redirectTo(url)
    /** Every [onContentGone] the host was told of. */
    val contentGone = CopyOnWriteArrayList<Boolean>()
    override fun onContentGone(killed: Boolean) { contentGone += killed }
    /** Which pages may have the microphone and camera without a prompt (#847). None, unless a test says so. */
    @Volatile var trust: (String) -> Boolean = { false }
    override fun trusts(url: String): Boolean = trust(url)
    override fun onAndroidPermissions(permissions: List<String>, answer: (Boolean) -> Unit) = answer(true)
    override fun onConnect(port: PagePort) { ports += port }
    override fun onPortMessage(port: PagePort, message: JSONObject) { portMessages += message }

    fun messages(type: String): List<JSONObject> = portMessages.filter { it.optString("type") == type }
}

/** A stand-in for the browser engine (GeckoView) that Vitro wraps. Records how it is used. */
class RecordingEngine(
    override val name: String = "FakeGecko 1.0",
    override val lockedDown: Boolean = false,
    /** Each page's view holds a [android.view.SurfaceView], as a GeckoView does. */
    private val surfaces: Boolean = false,
) : Engine {
    class Opened(val activity: Activity, val host: PageHost, val background: Int)

    @Volatile override var started: EngineSettings? = null
    val opens = CopyOnWriteArrayList<Opened>()
    val pages = CopyOnWriteArrayList<RecordingPage>()

    override fun open(activity: Activity, host: PageHost, background: Int): Page {
        started = EngineSettings(secureHosts = listOf("192.0.2.10"), zoom = 1f, remoteDebugging = false)
        opens += Opened(activity, host, background)
        return RecordingPage(activity).also { page ->
            if (surfaces) (page.view as FrameLayout).addView(android.view.SurfaceView(activity))
            pages += page
        }
    }

    override fun isContentProcess(processName: String): Boolean = processName.endsWith(":tab")
}

class RecordingPage(activity: Activity) : Page {
    override val view: View = FrameLayout(activity)
    val loads = CopyOnWriteArrayList<String>()
    val posted = CopyOnWriteArrayList<JSONObject>()
    var reloads = 0
    var backs = 0
    var restarts = 0
    override var canGoBack = false

    /** [close] ran; nothing after it is recorded. */
    @Volatile var closed = false

    /** Every [setActive], in order. */
    val actives = CopyOnWriteArrayList<Boolean>()

    /** The last [setActive], or true (a new page is active). */
    val active: Boolean get() = actives.lastOrNull() ?: true

    override fun setActive(active: Boolean) { if (!closed) actives += active }
    override fun close() { closed = true }
    override fun load(url: String) { loads += url }
    override fun reload() { reloads++ }
    override fun goBack() { backs++ }
    override fun restart() { restarts++ }
    override fun post(message: JSONObject) { posted += message }
}

/**
 * Runs the main looper until the picture loads a test started are all back on it (the shared count is [before]
 * again), or [timeoutMs] pass, and fails if they aren't: a load still in flight when a test ends lands in a later
 * test's looper, and leaves the count the parity harness waits on off by one (D28). Robolectric shares that count
 * between the test classes of one sandbox, so a test passes the count it started with.
 */
fun awaitImageLoads(before: Int, timeoutMs: Long = 10_000) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (dev.glassworks.engine.vitro.ui.VitroCardContext.pendingImageLoads.get() != before && System.nanoTime() < deadline) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
        Thread.sleep(5)
    }
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    org.junit.Assert.assertEquals(
        "every picture load this test started has finished", before,
        dev.glassworks.engine.vitro.ui.VitroCardContext.pendingImageLoads.get(),
    )
}
