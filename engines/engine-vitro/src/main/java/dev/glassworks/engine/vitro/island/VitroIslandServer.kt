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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loopback-only HTTP + WebSocket micro-host server for Tier 3 Custom Card Islands.
 *
 * Keeps Tier 3 100% GeckoView-consistent (zero `android.webkit.WebView`) across Android 6, 10,
 * and 15 while bypassing Home Assistant's 10+ MB frontend SPA:
 * - `GET /__vitro/island/<id>` serves the ~5 KB Micro-Host HTML (theme CSS vars + custom card JS).
 * - `GET /__vitro/ws/<id>` upgrades to a loopback WebSocket bridging `hass.states` (Proxy-filtered),
 *   `hass.callWS`, `hass.callService`, `hass-more-info`, and auto-resize straight to Kotlin.
 * - Every other path (`/local/...`, `/hacsfiles/...`, `/api/...`) is strictly reverse-proxied to the
 *   panel's single configured [haBase] with the panel's Bearer token injected.
 */
class VitroIslandServer(
    private val haBase: () -> String?,
    private val accessToken: () -> String?,
    private val log: (String) -> Unit = {},
) {
    interface SlotHandler {
        val html: String
        fun onConnected(send: (JSONObject) -> Unit)
        fun onMessage(msg: JSONObject, send: (JSONObject) -> Unit)
        fun onDisconnected()
    }

    private val slots = ConcurrentHashMap<String, SlotHandler>()
    private val activeSockets = ConcurrentHashMap<String, CopyOnWriteArraySet<WebSocketStreams>>()
    private val nextSlotId = AtomicInteger(1)
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
            workers = 8,
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
        val id = "s${nextSlotId.getAndIncrement()}"
        slots[id] = handler
        return id to "$base/__vitro/island/$id"
    }

    fun unregisterSlot(id: String) {
        slots.remove(id)
        activeSockets.remove(id)?.forEach { runCatching { it.abort() } }
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
        val set = activeSockets.computeIfAbsent(id) { CopyOnWriteArraySet() }
        set += ws
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
            val reader = BufferedReader(InputStreamReader(ws.input, Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
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

    /** Strictly proxies HA static assets and API paths (`/local/...`, `/hacsfiles/...`, `/api/...`) to [haBase]. */
    private fun proxyToHa(req: HttpRequest): HttpResponse {
        val base = haBase()?.trimEnd('/') ?: return HttpResponse.text(503, "Home Assistant not configured")
        if (!req.path.startsWith("/")) return HttpResponse.text(400, "Invalid path")
        val targetUrl = base + req.target
        return runCatching {
            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = req.method
                connectTimeout = 8_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                accessToken()?.takeIf { it.isNotEmpty() }?.let { setRequestProperty("Authorization", "Bearer $it") }
                req.headers["content-type"]?.let { setRequestProperty("Content-Type", it) }
                req.headers["accept"]?.let { setRequestProperty("Accept", it) }
                if (req.body.isNotEmpty() && req.method in setOf("POST", "PUT", "PATCH")) {
                    doOutput = true
                    outputStream.use { it.write(req.body) }
                }
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                val contentType = conn.contentType ?: guessMime(req.path)
                val headers = LinkedHashMap<String, String>()
                headers["Content-Type"] = contentType
                headers["Cache-Control"] = conn.getHeaderField("Cache-Control") ?: "no-cache"
                HttpResponse(code, headers, bytes)
            } finally {
                conn.disconnect()
            }
        }.getOrElse { e ->
            HttpResponse.text(502, "Proxy error: ${e.message}")
        }
    }

    companion object {
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
