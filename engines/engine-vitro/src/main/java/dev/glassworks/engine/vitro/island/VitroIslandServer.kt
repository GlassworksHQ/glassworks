/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.island

import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.web.HttpRequest
import dev.glassworks.web.HttpResponse
import dev.glassworks.web.HttpServer
import dev.glassworks.web.WebSocketStreams
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Loopback-only HTTP + WebSocket micro-host server for Tier 3 Custom Card Islands.
 *
 * Keeps Tier 3 100% GeckoView-consistent (zero `android.webkit.WebView`) across Android 6, 10,
 * and 15 while bypassing Home Assistant's 10+ MB frontend SPA:
 * - `GET /__vitro/island/<id>` serves the ~5 KB Micro-Host HTML (theme CSS vars + custom card JS).
 * - `GET /__vitro/ws/<id>` upgrades to a loopback WebSocket bridging `hass.states` (Proxy-filtered),
 *   `hass.callWS`, `hass.callService`, `hass-more-info`, and auto-resize straight to Kotlin.
 * - Every other path (`/local/...`, `/hacsfiles/...`, `/api/...`) is strictly reverse-proxied to the
 *   panel's single configured Home Assistant ([session]) with the panel's Bearer token injected.
 */
class VitroIslandServer(
    /**
     * The Home Assistant origin and the token for it, read together once per proxied request: a page that moves to
     * another origin mid-request can't pair the old origin with the new token.
     */
    private val session: () -> Pair<String?, String?>,
    private val log: (String) -> Unit = {},
) {
    constructor(haBase: () -> String?, accessToken: () -> String?, log: (String) -> Unit = {}) :
        this({ haBase() to accessToken() }, log)

    interface SlotHandler {
        val html: String
        fun onConnected(send: (JSONObject) -> Unit)
        fun onMessage(msg: JSONObject, send: (JSONObject) -> Unit)
        fun onDisconnected()
    }

    private val slots = ConcurrentHashMap<String, SlotHandler>()
    private val activeSockets = ConcurrentHashMap<String, CopyOnWriteArraySet<WebSocketStreams>>()
    private val random = SecureRandom()

    /** Guards a slot's removal against a socket of that slot registering at the same time. */
    private val slotLock = Any()
    private var server: HttpServer? = null

    val port: Int get() = server?.localPort ?: -1
    val origin: String? get() = port.takeIf { it > 0 }?.let { "http://127.0.0.1:$it" }

    @Synchronized
    fun ensureStarted(): String {
        origin?.let { return it }
        val s = HttpServer(
            port = 0,
            handler = ::handle,
            // Explicit IPv4 127.0.0.1: on Android, InetAddress.getLoopbackAddress() returns IPv6 ::1,
            // which refuses IPv4 http://127.0.0.1:<port> connections from GeckoView.
            bindAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
            log = log,
            maxBody = 256 * 1024,
            // An island's bridge socket holds a worker for as long as it is open, so leave room for the HTML,
            // assets and API calls next to them.
            workers = 16,
            queued = 64,
        )
        s.start()
        server = s
        return "http://127.0.0.1:${s.localPort}"
    }

    @Synchronized
    fun stop() {
        for (set in activeSockets.values) set.forEach { runCatching { it.abort() } }
        activeSockets.clear()
        slots.clear()
        server?.stop()
        server = null
    }

    fun registerSlot(handler: SlotHandler): Pair<String, String> {
        val base = ensureStarted()
        // Unguessable, so another app on the device can't open a live card's bridge by counting.
        val id = "s" + ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        slots[id] = handler
        return id to "$base/__vitro/island/$id"
    }

    fun unregisterSlot(id: String) {
        val sockets = synchronized(slotLock) {
            slots.remove(id)
            activeSockets.remove(id)
        }
        sockets?.forEach { runCatching { it.abort() } }
    }

    private fun handle(req: HttpRequest): HttpResponse {
        val path = req.path
        if (path.startsWith("/__vitro/island/")) {
            val id = path.removePrefix("/__vitro/island/")
            val slot = slots[id] ?: return HttpResponse.text(404, "Unknown island slot")
            return HttpResponse.html(200, slot.html)
        }
        if (path.startsWith("/__vitro/icon/")) {
            val iconName = path.removePrefix("/__vitro/icon/")
            // A worker thread may wait for the catalog: a stand-in glyph here would be cached by the page for a day.
            MdiIcons.catalogSize()
            val d = MdiIcons.pathDataFor(iconName)
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor"><path d="$d"/></svg>"""
            return HttpResponse(200, mapOf("Content-Type" to "image/svg+xml; charset=utf-8", "Cache-Control" to "max-age=86400"), svg.toByteArray(Charsets.UTF_8))
        }
        if (path.startsWith("/__vitro/ws/")) {
            val id = path.removePrefix("/__vitro/ws/")
            val slot = slots[id] ?: return HttpResponse.text(404, "Unknown island slot")
            val wsKey = req.headers["sec-websocket-key"] ?: return HttpResponse.text(400, "Missing Sec-WebSocket-Key")
            return HttpResponse.webSocket(wsKey, idleMs = 120_000) { ws ->
                runSlotSocket(id, slot, ws)
            }
        }
        return proxyToHa(req)
    }

    private fun runSlotSocket(id: String, slot: SlotHandler, ws: WebSocketStreams) {
        // The slot may have gone between the lookup and the upgrade: then this socket closes before the card hears of it.
        val set = synchronized(slotLock) {
            if (slots[id] !== slot) null
            else activeSockets.getOrPut(id) { CopyOnWriteArraySet() }.also { it += ws }
        }
        if (set == null) {
            runCatching { ws.abort() }
            return
        }
        val sendLock = Any()
        val send: (JSONObject) -> Unit = { obj ->
            synchronized(sendLock) {
                runCatching {
                    val bytes = (obj.toString() + "\n").toByteArray(Charsets.UTF_8)
                    ws.output.write(bytes)
                    ws.output.flush()
                }
            }
        }
        try {
            slot.onConnected(send)
            val input = BufferedInputStream(ws.input)
            while (true) {
                // A message over the limit ends the connection instead of growing without bound.
                val line = readLine(input, MAX_BRIDGE_MESSAGE) ?: break
                if (line.isBlank()) continue
                runCatching { JSONObject(line) }.getOrNull()?.let { msg ->
                    slot.onMessage(msg, send)
                }
            }
        } catch (_: Exception) {
            // Connection closed by island or server stop.
        } finally {
            set -= ws
            slot.onDisconnected()
        }
    }

    /**
     * Strictly proxies HA static assets and API paths (`/local/...`, `/hacsfiles/...`, `/api/...`) to the [session]'s origin:
     * `GET`, `POST`, `PUT`, `PATCH` and `DELETE` (what `hass.callApi` / `hass.fetchWithAuth` send), with the
     * panel's Bearer token injected. A `204 No Content` comes back with an empty body.
     */
    private fun proxyToHa(req: HttpRequest): HttpResponse {
        val (origin, token) = session()
        val base = origin?.trimEnd('/') ?: return HttpResponse.text(503, "Home Assistant not configured")
        if (!req.path.startsWith("/")) return HttpResponse.text(400, "Invalid path")
        var targetUrl = base + req.target
        return runCatching {
            var hops = 0
            while (true) {
                val conn = URL(targetUrl).openConnection() as HttpURLConnection
                try {
                    conn.apply {
                        requestMethod = req.method
                        connectTimeout = 8_000
                        readTimeout = 15_000
                        // Redirects are followed below, and only within Home Assistant's own origin.
                        instanceFollowRedirects = false
                        // The token read with [base], on every hop: never one read later for another origin.
                        token?.takeIf { it.isNotEmpty() }?.let { setRequestProperty("Authorization", "Bearer $it") }
                        req.headers["content-type"]?.let { setRequestProperty("Content-Type", it) }
                        req.headers["accept"]?.let { setRequestProperty("Accept", it) }
                        req.headers["x-requested-with"]?.let { setRequestProperty("X-Requested-With", it) }
                        if (req.body.isNotEmpty() && req.method in BODY_METHODS) {
                            doOutput = true
                            outputStream.use { it.write(req.body) }
                        }
                    }
                    val code = conn.responseCode
                    val location = conn.getHeaderField("Location")
                    if (code in REDIRECTS && location != null && (req.method == "GET" || req.method == "HEAD")) {
                        val next = sameOriginRedirect(base, targetUrl, location)
                            ?: return@runCatching HttpResponse.text(502, "Proxy error: redirect away from Home Assistant")
                        if (++hops > MAX_REDIRECTS) return@runCatching HttpResponse.text(502, "Proxy error: too many redirects")
                        targetUrl = next
                        continue
                    }
                    val headers = LinkedHashMap<String, String>()
                    headers["Cache-Control"] = conn.getHeaderField("Cache-Control") ?: "no-cache"
                    // `hass.callApi('DELETE', …)` and friends: 204/205 carry no body, and asking for one can throw.
                    if (code == 204 || code == 205) return@runCatching HttpResponse(code, headers, ByteArray(0))
                    val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                    val bytes = stream?.use { readCapped(it, MAX_PROXY_BODY) }
                        ?: ByteArray(0)
                    headers["Content-Type"] = conn.contentType ?: guessMime(req.path)
                    return@runCatching HttpResponse(code, headers, bytes)
                } finally {
                    conn.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            HttpResponse.text(502, "Proxy error")
        }.getOrElse { e ->
            HttpResponse.text(502, "Proxy error: ${e.message}")
        }
    }

    companion object {
        /** The longest bridge message (one JSON line) an island may send. */
        private const val MAX_BRIDGE_MESSAGE = 1 shl 20

        /** The largest proxied response held in memory (the server answers with whole bodies only). */
        internal const val MAX_PROXY_BODY = 16 shl 20

        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /** [location] resolved against [current], or null when it leaves [base]'s scheme, host and port. */
        internal fun sameOriginRedirect(base: String, current: String, location: String): String? = runCatching {
            val next = URL(URL(current), location)
            val home = URL(base)
            val samePort = (if (next.port < 0) next.defaultPort else next.port) == (if (home.port < 0) home.defaultPort else home.port)
            if (next.protocol.equals(home.protocol, true) && next.host.equals(home.host, true) && samePort) next.toString() else null
        }.getOrNull()

        /** All of [input], or an [IOException] once it passes [max] bytes (a stream that never ends frees the worker). */
        internal fun readCapped(input: InputStream, max: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) return out.toByteArray()
                if (out.size() + n > max) throw IOException("response over ${max shr 20} MiB")
                out.write(buf, 0, n)
            }
        }

        /** One `\n`-terminated UTF-8 line without the terminator, null at the end, an [IOException] past [max] bytes. */
        internal fun readLine(input: InputStream, max: Int): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
                if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
                if (out.size() >= max) throw IOException("bridge message over $max bytes")
                out.write(b)
            }
        }

        /** Methods whose request body is forwarded (`hass.callApi('DELETE', …)` may carry one too). */
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

        private fun guessMime(path: String): String = when {
            path.endsWith(".js", ignoreCase = true) || path.endsWith(".mjs", ignoreCase = true) -> "application/javascript; charset=utf-8"
            path.endsWith(".css", ignoreCase = true) -> "text/css; charset=utf-8"
            path.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
            path.endsWith(".png", ignoreCase = true) -> "image/png"
            path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            path.endsWith(".webp", ignoreCase = true) -> "image/webp"
            path.endsWith(".json", ignoreCase = true) -> "application/json; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
