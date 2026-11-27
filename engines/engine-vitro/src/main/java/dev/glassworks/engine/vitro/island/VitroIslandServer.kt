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
 * - `GET /__vitro/strings/<hash>.json` serves HA's strings for the islands' `hass.localize` ([IslandStrings]).
 * - `GET /__vitro/ws/<id>` upgrades to a loopback WebSocket bridging `hass.states` (Proxy-filtered),
 *   `hass.callWS`, `hass.callService`, `hass-more-info`, and auto-resize straight to Kotlin.
 * - Every other path the islands load (`/local/...`, `/hacsfiles/...`, `/api/...`, the dashboard's own Lovelace
 *   resources) is strictly reverse-proxied to the panel's single configured Home Assistant ([session]) with the
 *   panel's Bearer token injected.
 *
 * Only islands use it. Home Assistant's own pages (sign-in, settings, history) load from Home Assistant itself in the
 * fallback engine, the legacy one too (its lockdown allows Home Assistant's origin and this server's): there the
 * page's WebSocket and its external auth are Home Assistant's own, and nothing here stands in for them.
 *
 * Loopback is open to every app on the device, so the proxy (which spends the panel's token) answers only a client
 * that holds this server's capability: a random path segment, new for every server ([capPath]). Island pages, their
 * bridges and what the page itself loads live under it (`/<cap>/__vitro/island/<id>`, `/<cap>/local/card.js`). What a
 * card builds itself from an absolute path (an entity picture's `/api/…`, an `import('/hacsfiles/…')`) arrives without
 * it, but with the page's address as its `Referer`, which holds it: that request is let through, a `GET` by a redirect
 * into [capPath], so what it loads in turn (a module's imports, a stylesheet's fonts) has the capability in its address
 * too. Every answer says `Referrer-Policy: same-origin`, so the browser never names an island's address to another
 * origin, and another app on another loopback port never learns the capability.
 *
 * Not a cookie (#799): cookies aren't kept apart by port (RFC 6265), so the browser sent one set on 127.0.0.1 to every
 * other server there, where another app could take it and replay it here.
 *
 * In depth: every request must name this server as its `Host` (a page whose own name resolves to 127.0.0.1, DNS
 * rebinding, is refused); the proxy forwards only the paths the islands use, never a `.`/`..` segment or an escaped
 * slash; slot ids are unguessable too. Refusals are logged without queries, tokens or the capability.
 *
 * The origin stays the same across app starts ([portMemory]): what a card keeps in `localStorage` or IndexedDB is per
 * origin, so a new port on every start would hand every card empty storage (#881). The server takes the port it had
 * last time, and only when another app holds it a new one, which it remembers from then on.
 *
 * A bridge stays open for as long as its island does (#882): the server pings it every [keepAliveMs], and the browser's
 * pong (answered by its network code, without waking the page) is the read that keeps it under [bridgeIdleMs]. Only a
 * page that stopped answering at all is dropped.
 */
class VitroIslandServer(
    /**
     * The Home Assistant origin and the token for it, read together once per proxied request: a page that moves to
     * another origin mid-request can't pair the old origin with the new token.
     */
    private val session: () -> Pair<String?, String?>,
    private val log: (String) -> Unit = {},
    /**
     * The URLs of the dashboard's Lovelace resources (`/local/card.js`, `/my_integration/card.js`): integrations serve
     * their cards at paths of their own, so the proxy also forwards what is under each one's folder ([resourcePaths]).
     */
    private val resources: () -> Collection<String> = { emptyList() },
    /**
     * Where the port is kept between app starts, so the islands' origin, and the storage a card keeps there, stays the
     * same. Null (tests) takes a new ephemeral port on every start.
     */
    private val portMemory: PortMemory? = null,
    /** A bridge whose page sends nothing for this long, not even a pong, is dropped (a page that went away). */
    private val bridgeIdleMs: Int = BRIDGE_IDLE_MS,
    /** How often a bridge is pinged: well inside [bridgeIdleMs], so a quiet page's pongs keep its bridge open. */
    private val keepAliveMs: Long = KEEPALIVE_MS,
    /** A monotonic clock in ms, for the pings. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /**
     * Asks for the session again after Home Assistant refused its token (a 401): a forced refresh
     * (`VitroEngine.renewIslandSession`), whose result is given to `done` on any thread. Null (tests) never renews.
     */
    private val renewSession: ((done: (Pair<String?, String?>) -> Unit) -> Unit)? = null,
    /** How long a proxied request waits for [renewSession] before it answers with the 401 it got. */
    private val renewTimeoutMs: Long = RENEW_TIMEOUT_MS,
) {
    constructor(haBase: () -> String?, accessToken: () -> String?, log: (String) -> Unit = {}) :
        this({ haBase() to accessToken() }, log)

    /** The island server's port between app starts ([portMemory]). */
    interface PortMemory {
        /** The port the server had last time, or 0 when none is known. */
        fun load(): Int

        fun save(port: Int)
    }

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

    /**
     * The proxy's capability, 128 random bits new for every server, as the first segment of a path: `/<32 hex digits>`.
     * Only what is under it (or a same-origin request whose `Referer` is) spends the panel's token or opens a bridge.
     * The island page builds its own addresses with it; nothing else of the app's needs it.
     */
    val capPath: String = "/" + hex(ByteArray(CAPABILITY_BYTES).also(random::nextBytes))

    /**
     * Memory every proxied response being read shares, in KiB: the server answers with whole bodies, and sixteen
     * workers each holding [MAX_PROXY_BODY] would be hundreds of MiB on a 2 GB panel.
     */
    private val proxyBudget = java.util.concurrent.Semaphore(PROXY_BUDGET_KIB, true)

    /** Guards a slot's removal against a socket of that slot registering at the same time. */
    private val slotLock = Any()
    private var server: HttpServer? = null

    val port: Int get() = server?.localPort ?: -1
    val origin: String? get() = port.takeIf { it > 0 }?.let { "http://127.0.0.1:$it" }

    /**
     * Starts the server once and gives its origin. With a [portMemory], on the port it had last time; when another app
     * holds that one, on a new one (the islands' storage starts over there), which is remembered from then on.
     */
    @Synchronized
    fun ensureStarted(): String {
        origin?.let { return it }
        val memory = portMemory
        val remembered = memory?.let { m -> runCatching { m.load() }.getOrNull() }?.takeIf { it in VALID_PORTS }
        val candidates = if (memory == null) listOf(0) else buildList {
            remembered?.let(::add)
            repeat(NEW_PORT_TRIES) { add(NEW_PORTS.first + random.nextInt(NEW_PORTS.last - NEW_PORTS.first + 1)) }
            add(0)
        }
        var failure: IOException? = null
        for (candidate in candidates) {
            val s = newServer(candidate)
            try {
                s.start()
            } catch (e: IOException) {
                failure = e
                s.stop()
                continue
            }
            server = s
            if (memory != null && s.localPort != remembered) {
                if (remembered != null) log("port $remembered is taken: the islands move to ${s.localPort}, where their storage starts over")
                runCatching { memory.save(s.localPort) }
            }
            return "http://127.0.0.1:${s.localPort}"
        }
        throw failure ?: IOException("no port to listen on")
    }

    private fun newServer(port: Int): HttpServer =
        HttpServer(
            port = port,
            // Every answer: never this server's addresses (they hold the capability) in a request to another origin.
            handler = { req -> handle(req).let { if (it.upgrade != null) it else it.with(REFERRER_POLICY) } },
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
        return id to "$base$capPath/__vitro/island/$id"
    }

    /** True when [url] is an island page of this server (under its [capPath]), never another of its paths. */
    fun isIslandPage(url: String): Boolean = origin?.let { url.startsWith("$it$capPath/__vitro/island/") } == true

    /**
     * [url] on this server as the path it stands for on Home Assistant, the capability taken off (a link a card's page
     * followed to `/local/help.html`): it goes to Home Assistant, which must never see the capability. Null for a URL
     * on any other origin.
     */
    fun haPath(url: String): String? {
        val o = origin ?: return null
        if (!url.startsWith("$o/")) return null
        return withoutCap(url.removePrefix(o))
    }

    /** [path] without this server's [capPath] in front. */
    fun withoutCap(path: String): String = when {
        path.startsWith("$capPath/") -> path.removePrefix(capPath)
        path == capPath -> "/"
        else -> path
    }

    /**
     * Hands slot [id] to [handler]: a rebuilt card that kept the island's page (a theme change, the same config). Its
     * open bridges close, so the page connects again, to [handler], and gets its snapshot. False when the slot is gone.
     */
    fun replaceSlot(id: String, handler: SlotHandler): Boolean {
        val sockets = synchronized(slotLock) {
            if (slots[id] == null) return false
            slots[id] = handler
            activeSockets.remove(id)
        }
        sockets?.forEach { runCatching { it.abort() } }
        return true
    }

    fun unregisterSlot(id: String) {
        val sockets = synchronized(slotLock) {
            slots.remove(id)
            activeSockets.remove(id)
        }
        sockets?.forEach { runCatching { it.abort() } }
    }

    private fun handle(req: HttpRequest): HttpResponse {
        // Inside the capability: the path after it is what the request is for.
        val inside = req.path.startsWith("$capPath/")
        val path = if (inside) req.path.removePrefix(capPath) else req.path
        // DNS rebinding: a page on another name that resolves to 127.0.0.1 sends that name as its Host.
        if (req.headers["host"]?.trim()?.lowercase() != "127.0.0.1:$port") {
            log("refused: Host isn't this server (${req.method} ${firstSegment(path)})")
            return HttpResponse.text(421, "Misdirected request")
        }
        if (path.startsWith("/__vitro/island/")) {
            // Only at its address under the capability: that address is what the page's own requests name as their
            // Referer, and what its relative ones resolve under.
            if (!inside) return refused(req, path)
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
        if (path.startsWith(IslandStrings.PREFIX)) {
            // HA's strings for the islands' `hass.localize` (nothing secret: HA serves them to anyone). The address
            // names their content, so the browser keeps them.
            // A set it no longer has is a 404: the page's localize then misses (""), never reads another language's.
            val hash = path.removePrefix(IslandStrings.PREFIX).removeSuffix(".json")
            val strings = IslandStrings.byHash(hash) ?: return HttpResponse.text(404, "Not found")
            return HttpResponse(
                200,
                mapOf("Content-Type" to "application/json; charset=utf-8", "Cache-Control" to "public, max-age=31536000, immutable"),
                strings.body,
            )
        }
        if (path.startsWith("/__vitro/ws/")) {
            // The island page opens its bridge under the capability; nothing else may (an Origin is anyone's to write).
            if (!inside) return refused(req, path)
            val id = path.removePrefix("/__vitro/ws/")
            val slot = slots[id] ?: return HttpResponse.text(404, "Unknown island slot")
            val wsKey = req.headers["sec-websocket-key"] ?: return HttpResponse.text(400, "Missing Sec-WebSocket-Key")
            return HttpResponse.webSocket(wsKey, idleMs = bridgeIdleMs) { ws ->
                runSlotSocket(id, slot, ws)
            }
        }
        if (!inside) {
            // Another app on the device must not borrow the panel's Home Assistant session.
            if (!referredFromInside(req)) return refused(req, path)
            // A card's own absolute path (`/api/…`, `/hacsfiles/…`) from an island page: a GET goes into the capability
            // first, so the browser holds what it loads (a module, a stylesheet) at an address with the capability,
            // and what that loads in turn names it as its Referer. Other methods carry no such address.
            if ((req.method == "GET" || req.method == "HEAD") && forwards(path)) {
                return HttpResponse(307, mapOf("Location" to capPath + req.target, "Cache-Control" to "no-store"), ByteArray(0))
            }
        }
        return proxyToHa(req, path, if (inside) req.target.removePrefix(capPath) else req.target)
    }

    /** A request without the capability: refused as the proxy refuses one, logged without its query or the rest of its path. */
    private fun refused(req: HttpRequest, path: String): HttpResponse {
        log("refused: no capability (${req.method} ${firstSegment(path)})")
        return HttpResponse.text(403, "Not allowed")
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
        // The writer pings too, on a clock of its own: a page that only listens (a dashboard card mostly receives
        // patches) still sends its pongs, so its bridge outlives the read deadline (#882).
        val keepAlive = KeepAlive(keepAliveMs, clock)
        val writer = Thread({
            try {
                while (true) {
                    val bytes = outbox.poll(keepAlive.waitMs(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (bytes != null) {
                        ws.output.write(bytes)
                        ws.output.flush()
                    }
                    if (keepAlive.due()) {
                        ws.ping()
                        keepAlive.pinged()
                    }
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
     * True when [req] comes from a page under the capability: its `Referer` is an address of this very origin inside
     * [capPath]. The browser writes it, and with this server's `Referrer-Policy: same-origin` only for requests to this
     * origin, so no other app learns it; an app that writes one itself must hold the capability already.
     */
    private fun referredFromInside(req: HttpRequest): Boolean {
        val referer = req.headers["referer"]?.trim() ?: return false
        val inside = "http://127.0.0.1:$port$capPath/"
        if (referer.length < inside.length) return false
        return java.security.MessageDigest.isEqual(referer.substring(0, inside.length).toByteArray(), inside.toByteArray())
    }

    /** True when the proxy forwards [path], one of its own prefixes or under a Lovelace resource's folder. */
    private fun forwards(path: String): Boolean =
        proxied(path) || underResource(path, runCatching { session().first }.getOrNull())

    /**
     * Strictly proxies HA static assets and API paths (`/local/...`, `/hacsfiles/...`, `/api/...`) to the [session]'s origin:
     * `GET`, `POST`, `PUT`, `PATCH` and `DELETE` (what `hass.callApi` / `hass.fetchWithAuth` send), with the
     * panel's Bearer token injected. A `204 No Content` comes back with an empty body. [path] and [target] (the path
     * with the query) are the request's without the capability; the caller has checked that it holds it.
     */
    private fun proxyToHa(req: HttpRequest, path: String, target: String): HttpResponse {
        // Only what the islands load: anything else isn't forwarded with the panel's token.
        val (origin, firstToken) = session()
        var token = firstToken
        if (!proxied(path) && !underResource(path, origin)) {
            log("refused: not a proxied path (${req.method} ${firstSegment(path)})")
            return HttpResponse.text(404, "Not found")
        }
        val base = origin?.trimEnd('/') ?: return HttpResponse.text(503, "Home Assistant not configured")
        if (!path.startsWith("/")) return HttpResponse.text(400, "Invalid path")
        var targetUrl = base + target
        val held = IntArray(1)
        // Set once a response carries the permits: the server releases them after writing it (its onDone).
        var handedOff = false
        // Retried once, with a renewed token, after Home Assistant refused this one.
        var renewed = false
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
                    // A token that expired under the island: the same request once more with a renewed one, if it is
                    // for the same Home Assistant. Its answer, whatever it is, goes to the page.
                    if (code == 401 && !renewed && !token.isNullOrEmpty()) {
                        renewed = true
                        val fresh = renewedSession(token)
                        if (fresh != null && sameBase(fresh.first, base) && !fresh.second.isNullOrEmpty() && fresh.second != token) {
                            log("retrying a request Home Assistant refused, with a renewed token (${firstSegment(path)})")
                            token = fresh.second
                            targetUrl = base + target
                            hops = 0
                            continue
                        }
                    }
                    val location = conn.getHeaderField("Location")
                    if (code in REDIRECTS && location != null && (req.method == "GET" || req.method == "HEAD")) {
                        val next = sameOriginRedirect(base, targetUrl, location)
                            ?: return@runCatching HttpResponse.text(502, "Proxy error: redirect away from Home Assistant")
                        // Each hop meets the same path rule as a direct request.
                        val nextPath = URL(next).path.ifEmpty { "/" }
                        if (!proxied(nextPath) && !underResource(nextPath, origin)) {
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
                    headers["Content-Type"] = conn.contentType ?: guessMime(path)
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

    /**
     * The session to retry with after Home Assistant refused [refused]: the current one when another request renewed
     * it already, else what [renewSession] gives within [renewTimeoutMs]. Null when nothing renews it.
     */
    private fun renewedSession(refused: String?): Pair<String?, String?>? {
        session().takeIf { it.second != null && it.second != refused }?.let { return it }
        val renew = renewSession ?: return null
        val latch = java.util.concurrent.CountDownLatch(1)
        val got = java.util.concurrent.atomic.AtomicReference<Pair<String?, String?>?>(null)
        runCatching { renew { s -> got.set(s); latch.countDown() } }.onFailure { return null }
        return if (latch.await(renewTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) got.get() else null
    }

    /** Whether [origin] is the Home Assistant [base] a request went to. */
    private fun sameBase(origin: String?, base: String): Boolean = origin?.trimEnd('/') == base

    /** True when [path] is one of the dashboard's Lovelace resources or under its folder ([resourcePaths]). */
    private fun underResource(path: String, haOrigin: String?): Boolean {
        if (!safePath(path)) return false
        val urls = runCatching { resources() }.getOrNull() ?: return false
        return resourcePaths(urls, haOrigin).any { if (it.endsWith("/")) path.startsWith(it) else path == it }
    }

    /**
     * When a bridge is pinged: every [intervalMs] by [clock], however much else goes out (only a read, the pong, keeps
     * the bridge under its deadline, and what the server sends isn't one).
     */
    internal class KeepAlive(private val intervalMs: Long, private val clock: () -> Long) {
        private var next = clock() + intervalMs

        /** How long the writer may wait for a message before the next ping is due. */
        fun waitMs(): Long = (next - clock()).coerceAtLeast(0)

        fun due(): Boolean = clock() >= next

        fun pinged() {
            next = clock() + intervalMs
        }
    }

    companion object {
        /** A bridge's read deadline: a page that sends nothing for this long, not even a pong, has gone. */
        internal const val BRIDGE_IDLE_MS = 120_000

        /** How often a bridge is pinged: four pings fit in [BRIDGE_IDLE_MS]. */
        internal const val KEEPALIVE_MS = 30_000L

        /** How long a refused request waits for a renewed token: well inside the request's own read timeout. */
        internal const val RENEW_TIMEOUT_MS = 10_000L

        /** Ports a remembered one may be. */
        private val VALID_PORTS = 1024..65535

        /**
         * Where a new port is picked: below Android's ephemeral range (32768–60999), so an outgoing connection never
         * holds it when the app starts again.
         */
        internal val NEW_PORTS = 20000..29999

        /** New ports tried before an ephemeral one. */
        private const val NEW_PORT_TRIES = 8

        /**
         * Path prefixes the proxy forwards, what the islands use: HA's API (`callApi`, entity pictures, camera
         * proxies), the card resources (`/local`, `/hacsfiles`), HA's frontend bundles and static files (a card that
         * imports HA's own pieces) and media (`/media/local/…`, signed). Not `/auth/` nor HA's pages: those load from
         * Home Assistant itself.
         */
        private val PROXIED_PREFIXES = listOf(
            "/api/", "/local/", "/hacsfiles/", "/static/", "/frontend_latest/", "/frontend_es5/", "/media/",
        )

        /** True when the proxy forwards [path] whatever the dashboard's resources are. */
        internal fun proxied(path: String): Boolean = safePath(path) && PROXIED_PREFIXES.any { path.startsWith(it) }

        /**
         * A raw path that stays where it says: no `.` or `..` segment (escaped or not) and no backslash or escaped
         * slash, which Home Assistant could resolve to a path outside the prefix it was let through for.
         */
        internal fun safePath(path: String): Boolean {
            if (!path.startsWith("/") || '\\' in path) return false
            val lower = path.lowercase()
            if ("%2f" in lower || "%5c" in lower) return false
            return path.split('/').none { seg -> seg.replace("%2e", ".", ignoreCase = true).let { it == "." || it == ".." } }
        }

        /** Paths whose answer is the same for everyone (HA serves them without a token): their caching is kept. */
        private val PUBLIC_PREFIXES = listOf("/static/", "/frontend_latest/", "/frontend_es5/", "/hacsfiles/", "/local/")

        internal fun publicAsset(path: String): Boolean = PUBLIC_PREFIXES.any { path.startsWith(it) }

        /** First path segments a resource never opens: HA's sign-in and this server's own routes. */
        private val NEVER_RESOURCE = setOf("", "auth", "__vitro")

        /**
         * What the proxy forwards for the Lovelace resources [urls] on [haOrigin]: a resource at `/<folder>/…` opens
         * `/<folder>/` (the card's chunks and assets sit beside it, and an integration serves its card at a folder of
         * its own); one at the root, `/<file>`, that path alone. Only same-origin resources count (a path, or an
         * absolute URL on [haOrigin]), never one in `/auth/`, and never a path that leaves its folder.
         */
        internal fun resourcePaths(urls: Collection<String>, haOrigin: String?): Set<String> {
            val out = LinkedHashSet<String>()
            val home = haOrigin?.let(::originKey)
            for (raw in urls) {
                val u = raw.trim()
                val path = when {
                    u.startsWith("//") -> continue
                    u.startsWith("/") -> u
                    // An absolute URL on Home Assistant's origin, its default port written or not.
                    home != null -> runCatching { URL(u) }.getOrNull()?.takeIf { originKey(it) == home }
                        ?.let { it.path.ifEmpty { "/" } } ?: continue
                    else -> continue
                }.substringBefore('#').substringBefore('?')
                if (!safePath(path)) continue
                val rest = path.removePrefix("/")
                val first = rest.substringBefore('/')
                if (first.lowercase() in NEVER_RESOURCE) continue
                out += if ('/' in rest) "/$first/" else path
            }
            return out
        }

        /** [url]'s scheme, host and effective port (`https://ha.example` and `https://ha.example:443` are one), or null. */
        private fun originKey(url: String): String? = runCatching { originKey(URL(url)) }.getOrNull()

        private fun originKey(url: URL): String? {
            val scheme = url.protocol?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
            val host = url.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return "$scheme://$host:${if (url.port < 0) url.defaultPort else url.port}"
        }

        /**
         * The first path segment, for a refusal's log line: never the rest (ids) or the query, and never what looks like
         * a capability (another server's, or a guess at this one's).
         */
        private fun firstSegment(path: String): String {
            val first = path.removePrefix("/").substringBefore('/').take(40)
            val shown = if (CAPABILITY_LIKE.matches(first)) "…" else first
            return "/" + shown + if (path.removePrefix("/").contains('/')) "/…" else ""
        }

        /** The capability's random bytes: 128 bits, as 32 hex digits in [capPath]. */
        private const val CAPABILITY_BYTES = 16

        private val CAPABILITY_LIKE = Regex("[0-9a-fA-F]{${CAPABILITY_BYTES * 2}}")

        /**
         * On every answer: a request to another origin (another app's port on 127.0.0.1, a CDN, Home Assistant itself)
         * never names an island's address, which holds the capability. `same-origin`, not `no-referrer`: the page's own
         * requests name it, which is what lets a card's absolute paths through ([referredFromInside]).
         */
        private val REFERRER_POLICY = mapOf("Referrer-Policy" to "same-origin")

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
            /**
             * The body's declared length (`Content-Length`), or -1. Within [max] and with no [budget], the body is read
             * into one array of that size: no buffer grown and copied as it fills, and no copy at the end (a picture of
             * a few hundred KiB made several large-object arrays of garbage each time). A body that ends sooner is
             * trimmed to what came; one that goes on is read on as with no length, under [max].
             */
            expected: Long = -1L,
        ): ByteArray {
            if (budget == null && expected in 1..max.toLong()) {
                val exact = ByteArray(expected.toInt())
                var got = 0
                while (got < exact.size) {
                    val n = input.read(exact, got, exact.size - got)
                    if (n < 0) return exact.copyOf(got)
                    got += n
                }
                val next = input.read()
                if (next < 0) return exact
                if (exact.size + 1 > max) throw IOException("response over ${max shr 20} MiB")
                // Longer than it said: the rest as for a body of unknown length, after what came.
                val rest = ByteArrayOutputStream(exact.size * 2)
                rest.write(exact)
                rest.write(next)
                return readOn(input, max, rest)
            }
            return readOn(input, max, ByteArrayOutputStream(), budget, held, waitMs)
        }

        private fun readOn(
            input: InputStream,
            max: Int,
            out: ByteArrayOutputStream,
            budget: java.util.concurrent.Semaphore? = null,
            held: IntArray? = null,
            waitMs: Long = PROXY_BUDGET_WAIT_MS,
        ): ByteArray {
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
