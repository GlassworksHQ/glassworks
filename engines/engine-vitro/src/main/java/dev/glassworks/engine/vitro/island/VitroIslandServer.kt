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
 *
 * Loopback is open to every app on the device, so the proxy (which spends the panel's token) answers only a client
 * that holds this server's capability: a random cookie the island page sets, and the entry route sets for the
 * legacy engine's pages in exchange for a one-time code ([entryUrl]). Other apps reach neither (slot ids are
 * unguessable too). In depth: every request must name this server as its `Host` (a page whose own name resolves to
 * 127.0.0.1, DNS rebinding, is refused); the proxy forwards only the paths HA's page and the islands use; a bridge
 * needs the cookie or this server's own `Origin`. Refusals are logged without queries or tokens.
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

        /** The socket [onConnected] handed [send] for has closed. */
        fun onDisconnected(send: (JSONObject) -> Unit)
    }

    private val slots = ConcurrentHashMap<String, SlotHandler>()
    private val activeSockets = ConcurrentHashMap<String, CopyOnWriteArraySet<WebSocketStreams>>()
    private val random = SecureRandom()

    /** The proxy's capability, random per server: the [CAPABILITY_COOKIE] a client must send to use the panel's token. */
    private val capability: String = hex(ByteArray(32).also(random::nextBytes))

    private val capabilityCookie: Map<String, String>
        get() = mapOf("Set-Cookie" to "$CAPABILITY_COOKIE=$capability; Path=/; HttpOnly; SameSite=Strict")

    /** One-time entry codes ([entryUrl]): when each stops working ([System.nanoTime]) and the path it opens. */
    private val entryCodes = ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * Memory every proxied response being read shares, in KiB: the server answers with whole bodies, and sixteen
     * workers each holding [MAX_PROXY_BODY] would be hundreds of MiB on a 2 GB panel.
     */
    private val proxyBudget = java.util.concurrent.Semaphore(PROXY_BUDGET_KIB, true)

    /** How long an entry code works: the fallback loads it at once. A test shortens it. */
    internal var entryCodeTtlNs: Long = 30_000_000_000L

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
            // HTML, icons, assets and API calls. Bridge sockets run on threads of their own (below), so however many
            // islands are open, these workers stay free for requests.
            workers = 16,
            queued = 64,
            // Open bridges at once (one per island on screen, a replacement may overlap its old socket); past it a
            // bridge is refused with 503 and the island retries.
            upgradeThreads = MAX_BRIDGES,
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
        val id = "s" + hex(ByteArray(16).also(random::nextBytes))
        slots[id] = handler
        return id to "$base/__vitro/island/$id"
    }

    /**
     * The URL a browser loads to open [pathAndQuery] (a path on Home Assistant, `/config`, `/auth/authorize?…`)
     * through the proxy: it sets the capability cookie and redirects there. Never log it; it carries the capability.
     */
    fun entryUrl(pathAndQuery: String): String {
        val to = entryPath(pathAndQuery)
        // A code good once and for [entryCodeTtlNs], not the capability: the URL left in the browser's history (or
        // anywhere it gets copied) is worthless after it has been used.
        val now = System.nanoTime()
        entryCodes.entries.removeAll { now - it.value.first > 0 }
        val code = hex(ByteArray(16).also(random::nextBytes))
        // The code opens this path and no other: the `to` in the URL is only there to read.
        entryCodes[code] = (now + entryCodeTtlNs) to to
        return ensureStarted() + ENTRY + "?c=" + code + "&to=" + java.net.URLEncoder.encode(to, "UTF-8")
    }

    /** Spends entry code [code]: the path it was issued for when it was unused and still in time, else null. */
    private fun spendEntryCode(code: String): String? {
        if (code.isEmpty()) return null
        val (until, to) = entryCodes.remove(code) ?: return null
        return to.takeIf { System.nanoTime() - until <= 0 }
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
        // DNS rebinding: a page on another name that resolves to 127.0.0.1 sends that name as its Host.
        if (req.headers["host"]?.trim()?.lowercase() != "127.0.0.1:$port") {
            log("refused: Host isn't this server (${req.method} ${firstSegment(path)})")
            return HttpResponse.text(421, "Misdirected request")
        }
        if (path.startsWith("/__vitro/island/")) {
            val id = path.removePrefix("/__vitro/island/")
            val slot = slots[id] ?: return HttpResponse.text(404, "Unknown island slot")
            // The page's own requests (card scripts, `hass.callApi`) go through the proxy with this cookie.
            return HttpResponse.html(200, slot.html, capabilityCookie)
        }
        if (path == ENTRY) {
            // The path the code was issued for ([entryPath] made it safe for a header), never the request's `to`.
            val to = spendEntryCode(req.query["c"].orEmpty()) ?: run {
                log("refused: an unknown, used or expired entry code")
                return HttpResponse.text(403, "Not allowed")
            }
            return HttpResponse(302, mapOf("Location" to to, "Cache-Control" to "no-store") + capabilityCookie, ByteArray(0))
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
            // The island page itself: it sends the cookie (same site) and this server's Origin.
            if (!authorized(req) && req.headers["origin"]?.trim()?.lowercase() != "http://127.0.0.1:$port") {
                log("refused: a bridge without the capability or this server's Origin")
                return HttpResponse.text(403, "Not allowed")
            }
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
        // Sending only queues: the card's store observer calls this on HA's event thread, under the store's write
        // lock, and a socket write has no deadline. One writer thread per bridge drains the queue. A browser that
        // stops reading fills it; then the bridge is dropped (the island reconnects and gets a fresh snapshot) rather
        // than queueing without bound or blocking Home Assistant's events.
        val outbox = java.util.concurrent.ArrayBlockingQueue<ByteArray>(MAX_QUEUED_MESSAGES)
        val send: (JSONObject) -> Unit = { obj ->
            if (!outbox.offer((obj.toString() + "\n").toByteArray(Charsets.UTF_8))) {
                log("island bridge not reading: dropped")
                runCatching { ws.abort() }
            }
        }
        val writer = Thread({
            try {
                while (true) {
                    val bytes = outbox.take()
                    ws.output.write(bytes)
                    ws.output.flush()
                }
            } catch (_: InterruptedException) {
            } catch (_: Exception) {
                runCatching { ws.abort() } // a failed write ends the bridge; the reader below sees it close
            }
        }, "vitro-island-write").apply { isDaemon = true; start() }
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
            writer.interrupt()
            set -= ws
            slot.onDisconnected(send)
        }
    }

    /**
     * Strictly proxies HA static assets and API paths (`/local/...`, `/hacsfiles/...`, `/api/...`) to the [session]'s origin:
     * `GET`, `POST`, `PUT`, `PATCH` and `DELETE` (what `hass.callApi` / `hass.fetchWithAuth` send), with the
     * panel's Bearer token injected. A `204 No Content` comes back with an empty body.
     */
    /** True when [req] carries this server's capability cookie. */
    private fun authorized(req: HttpRequest): Boolean {
        val cookies = req.headers["cookie"] ?: return false
        return cookies.split(';').any { c ->
            val kv = c.trim()
            kv.startsWith("$CAPABILITY_COOKIE=") &&
                java.security.MessageDigest.isEqual(kv.substringAfter('=').toByteArray(), capability.toByteArray())
        }
    }

    private fun proxyToHa(req: HttpRequest): HttpResponse {
        // Another app on the device must not borrow the panel's Home Assistant session.
        if (!authorized(req)) {
            log("refused: no capability (${req.method} ${firstSegment(req.path)})")
            return HttpResponse.text(403, "Not allowed")
        }
        // Only what HA's page and the islands load: anything else isn't forwarded with the panel's token.
        if (!proxied(req.path)) {
            log("refused: not a proxied path (${req.method} ${firstSegment(req.path)})")
            return HttpResponse.text(404, "Not found")
        }
        val (origin, token) = session()
        val base = origin?.trimEnd('/') ?: return HttpResponse.text(503, "Home Assistant not configured")
        if (!req.path.startsWith("/")) return HttpResponse.text(400, "Invalid path")
        var targetUrl = base + req.target
        val held = IntArray(1)
        // Set once a response carries the permits: the server releases them after writing it (its onDone).
        var handedOff = false
        return try { runCatching {
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
                        // Each hop meets the same path rule as a direct request.
                        val nextPath = URL(next).path.ifEmpty { "/" }
                        if (!proxied(nextPath)) {
                            log("refused: a redirect to a path that isn't proxied (${firstSegment(nextPath)})")
                            return@runCatching HttpResponse.text(502, "Proxy error: redirect to a path that isn't proxied")
                        }
                        if (++hops > MAX_REDIRECTS) return@runCatching HttpResponse.text(502, "Proxy error: too many redirects")
                        targetUrl = next
                        continue
                    }
                    val headers = LinkedHashMap<String, String>()
                    // An answer made with the panel's token isn't kept by the browser: after a sign-in change it would
                    // be served from the cache at the same loopback URL. Only HA's public files keep their caching.
                    headers["Cache-Control"] =
                        // The path the answer came from (after any redirect): a public file redirecting to a token-bearing
                        // one is token-bearing.
                        if (publicAsset(URL(targetUrl).path)) conn.getHeaderField("Cache-Control") ?: "no-cache" else "no-store"
                    // `hass.callApi('DELETE', …)` and friends: 204/205 carry no body, and asking for one can throw.
                    if (code == 204 || code == 205) return@runCatching HttpResponse(code, headers, ByteArray(0))
                    val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                    val bytes = stream?.use { readCapped(it, MAX_PROXY_BODY, proxyBudget, held) }
                        ?: ByteArray(0)
                    headers["Content-Type"] = conn.contentType ?: guessMime(req.path)
                    // The body stays in memory until it's written: its share of the budget goes with it, released by
                    // the server once the write ends (or fails), not when this returns.
                    val permits = held[0]
                    handedOff = true
                    return@runCatching HttpResponse(code, headers, bytes, onDone = { if (permits > 0) proxyBudget.release(permits) })
                } finally {
                    conn.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            HttpResponse.text(502, "Proxy error")
        }.getOrElse { e ->
            HttpResponse.text(502, "Proxy error: ${e.message}")
        } } finally {
            if (!handedOff && held[0] > 0) proxyBudget.release(held[0])
        }
    }

    companion object {
        /**
         * Path prefixes the proxy forwards: HA's API and auth (the legacy engine's sign-in, `callApi`), the card
         * resources (`/local`, `/hacsfiles`), HA's frontend bundles (modern and legacy builds), static files and media
         * (`/media/local/…`, signed).
         */
        private val PROXIED_PREFIXES = listOf(
            "/api/", "/auth/", "/local/", "/hacsfiles/", "/static/", "/frontend_latest/", "/frontend_es5/", "/media/",
        )

        /** Single files HA's page asks for at its root (manifest, service workers, the onboarding page). */
        private val PROXIED_FILES = setOf(
            "/manifest.json", "/sw-modern.js", "/sw-legacy.js", "/service_worker.js", "/robots.txt", "/onboarding.html",
        )

        /**
         * HA's own pages (its panels: the legacy engine shows them for what Vitro doesn't draw natively). A dashboard
         * with its own path always has a `-` in it (HA requires one), so a first segment with one is a page too.
         */
        private val HA_PANELS = setOf(
            "config", "history", "logbook", "map", "energy", "developer-tools", "profile", "media-browser", "todo",
            "calendar", "hassio", "lovelace", "home", "light", "climate", "security", "areas", "notifications", "tag",
            "hacs",
        )

        /** Paths whose answer is the same for everyone (HA serves them without a token): their caching is kept. */
        private val PUBLIC_PREFIXES = listOf("/static/", "/frontend_latest/", "/frontend_es5/", "/hacsfiles/", "/local/")

        internal fun publicAsset(path: String): Boolean = PUBLIC_PREFIXES.any { path.startsWith(it) }

        /**
         * [pathAndQuery] as a path on this origin, safe in a `Location` header: a leading `/` (never `//` or `/\`,
         * which would leave the origin), and control characters percent-encoded.
         */
        internal fun entryPath(pathAndQuery: String): String {
            var to = if (pathAndQuery.startsWith("/")) pathAndQuery else "/$pathAndQuery"
            while (to.startsWith("//") || to.startsWith("/\\")) to = "/" + to.drop(2)
            return buildString {
                for (c in to) if (c.code < 0x20 || c.code == 0x7f) append("%%%02X".format(c.code)) else append(c)
            }
        }

        /** True when the proxy forwards [path]; the root (`/`) is HA's default dashboard page. */
        internal fun proxied(path: String): Boolean {
            if (path == "/" || path in PROXIED_FILES) return true
            if (PROXIED_PREFIXES.any { path.startsWith(it) }) return true
            val first = path.removePrefix("/").substringBefore('/')
            return first in HA_PANELS || ('-' in first && first.none { it == '.' })
        }

        /** The first path segment, for a refusal's log line: never the rest (ids) or the query. */
        private fun firstSegment(path: String): String =
            "/" + path.removePrefix("/").substringBefore('/').take(40) + if (path.removePrefix("/").contains('/')) "/…" else ""

        /** The cookie that carries the proxy's capability. */
        internal const val CAPABILITY_COOKIE = "vitro_cap"

        /** The route that sets the capability cookie and redirects into the proxy ([entryUrl]). */
        private const val ENTRY = "/__vitro/enter"

        private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        /** Island bridges open at once, each on its own thread. */
        internal const val MAX_BRIDGES = 48

        /** Messages queued for one island before it counts as not reading and its bridge is dropped. */
        internal const val MAX_QUEUED_MESSAGES = 512

        /** The longest bridge message (one JSON line) an island may send. */
        private const val MAX_BRIDGE_MESSAGE = 1 shl 20

        /** The largest proxied response held in memory (the server answers with whole bodies only). */
        internal const val MAX_PROXY_BODY = 16 shl 20

        /** What all proxied responses being read at once may hold, in KiB: two of the largest. */
        internal const val PROXY_BUDGET_KIB = 32 * 1024

        /** How long a read waits for [PROXY_BUDGET_KIB] room before the request fails as busy. */
        private const val PROXY_BUDGET_WAIT_MS = 5_000L

        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /** [location] resolved against [current], or null when it leaves [base]'s scheme, host and port. */
        internal fun sameOriginRedirect(base: String, current: String, location: String): String? = runCatching {
            val next = URL(URL(current), location)
            val home = URL(base)
            val samePort = (if (next.port < 0) next.defaultPort else next.port) == (if (home.port < 0) home.defaultPort else home.port)
            if (next.protocol.equals(home.protocol, true) && next.host.equals(home.host, true) && samePort) next.toString() else null
        }.getOrNull()

        /**
         * All of [input], or an [IOException] once it passes [max] bytes (a stream that never ends frees the worker).
         * With a [budget] (permits are KiB), each 16 KiB read takes its share first and adds it to [held], which the
         * caller releases; a read that finds no room within [waitMs] fails, so concurrent bodies share one bound.
         */
        internal fun readCapped(
            input: InputStream,
            max: Int,
            budget: java.util.concurrent.Semaphore? = null,
            held: IntArray? = null,
            waitMs: Long = PROXY_BUDGET_WAIT_MS,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            // KiB taken from the budget so far: what the bytes buffered need, however the reads come in.
            var charged = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) return out.toByteArray()
                if (out.size() + n > max) throw IOException("response over ${max shr 20} MiB")
                if (budget != null) {
                    val need = ((out.size().toLong() + n + 1023) / 1024).toInt() - charged
                    if (need > 0) {
                        if (!budget.tryAcquire(need, waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) throw IOException("proxy busy")
                        charged += need
                        held?.let { it[0] += need }
                    }
                }
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
