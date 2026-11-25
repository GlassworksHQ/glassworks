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
 * An in-memory Home Assistant WebSocket API, speaking just enough of the real protocol for [HaDashboardClient]:
 * `auth_required` -> `auth` -> `auth_ok` / `auth_invalid`, then request/`result` pairs and `event` pushes.
 *
 * Every message the client sends lands in [received] in order, so tests assert on the wire. [connect] has the
 * `(String) -> HaSocket` shape both [HaDashboardClient] and `VitroEngine` take. Addresses are RFC 5737.
 */
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
              {"type": "grid", "title": "Lights", "cards": [
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
    @Volatile var initialEntities: JSONObject = JSONObject(ENTITIES_JSON)
    @Volatile var history: JSONObject = JSONObject("""{"states": {}}""")
    /** `history/history_during_period` answers with an error. */
    @Volatile var failHistory: Boolean = false
    @Volatile var templateResult: Any = "21.5 °C"

    /** `config/entity_registry/list_for_display`'s answer; null refuses it (`unknown_command`). */
    @Volatile var entityRegistryDisplay: JSONObject? = null

    /** `config/device_registry/list`'s answer; null refuses it. */
    @Volatile var deviceRegistry: org.json.JSONArray? = null

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
            "lovelace/dashboards/list" -> ok(sock, id, dashboards)
            "lovelace/resources" -> ok(sock, id, resources)
            "lovelace/config" -> {
                if (m.optString("url_path") == "missing") fail(sock, id, "config_not_found") else ok(sock, id, dashboardConfig)
            }
            "subscribe_entities" -> {
                ok(sock, id, JSONObject.NULL)
                val wanted = m.optJSONArray("entity_ids")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
                val initial = JSONObject()
                val keys = initialEntities.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (wanted == null || k in wanted) initial.put(k, initialEntities.get(k))
                }
                event(sock, id, JSONObject().put("a", initial))
            }
            "call_service" -> ok(sock, id, JSONObject().put("context", JSONObject().put("id", "ctx-1")))
            "ping" -> sock.push(JSONObject().put("id", id).put("type", "pong"))
            "history/history_during_period" ->
                if (failHistory) fail(sock, id, "unknown_error") else ok(sock, id, history.optJSONObject("states") ?: JSONObject())
            "render_template" -> {
                ok(sock, id, JSONObject.NULL)
                event(sock, id, JSONObject().put("result", templateResult))
            }
            "config/area_registry/list" ->
                ok(sock, id, org.json.JSONArray("""[{"area_id": "lounge", "name": "Lounge"}]"""))
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
    override fun onContentGone(killed: Boolean) {}
    override fun onAndroidPermissions(permissions: List<String>, answer: (Boolean) -> Unit) = answer(true)
    override fun onConnect(port: PagePort) { ports += port }
    override fun onPortMessage(port: PagePort, message: JSONObject) { portMessages += message }

    fun messages(type: String): List<JSONObject> = portMessages.filter { it.optString("type") == type }
}

/** A stand-in for the browser engine (GeckoView) that Vitro wraps. Records how it is used. */
class RecordingEngine(
    override val name: String = "FakeGecko 1.0",
    override val lockedDown: Boolean = false,
) : Engine {
    class Opened(val activity: Activity, val host: PageHost, val background: Int)

    @Volatile override var started: EngineSettings? = null
    val opens = CopyOnWriteArrayList<Opened>()
    val pages = CopyOnWriteArrayList<RecordingPage>()

    override fun open(activity: Activity, host: PageHost, background: Int): Page {
        started = EngineSettings(secureHosts = listOf("192.0.2.10"), zoom = 1f, remoteDebugging = false)
        opens += Opened(activity, host, background)
        return RecordingPage(activity).also { pages += it }
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

    override fun load(url: String) { loads += url }
    override fun reload() { reloads++ }
    override fun goBack() { backs++ }
    override fun restart() { restarts++ }
    override fun post(message: JSONObject) { posted += message }
}
