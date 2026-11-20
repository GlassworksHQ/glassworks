/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import dev.glassworks.manage.ManageServer
import dev.glassworks.manage.PanelCode
import dev.glassworks.manage.SessionStore
import dev.glassworks.web.HttpRequest
import dev.glassworks.web.HttpResponse
import dev.glassworks.web.WebSocketStreams
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The setup channel over the LAN (#34), on the panel's web port next to the Manage page. The WebSocket at
 * [CHANNEL] carries the same line-delimited JSON as the USB socket: [serve] is the setup channel's own
 * loop, so both transports run one dispatcher. Each WebSocket text frame holds whole lines.
 *
 * Pairing: `POST` [PAIR] shows a code on the panel ([PanelCode], with its limits on guesses and on new
 * codes), and `POST` [PAIR] with `code=` checks it. A right code gives the browser a setup session: a
 * cookie for [PAIR]'s path, good until [DONE], [idleMs] without a change, or [maxMs] after pairing.
 * Without a session the channel answers `state` only and refuses every op that changes something.
 *
 * Pairing is open until Home Assistant adopts the panel; after that only while re-armed ([pairingOpen]).
 * A session already made keeps working when pairing closes, so setup can finish.
 *
 * Like the Manage page, it answers only to the panel's own names ([ownHosts]: DNS rebinding), takes POSTs
 * only from its own pages, and opens the WebSocket only for a page on the panel's own origin: a browser
 * always sends `Origin` with a WebSocket, so another site is refused. Pure JVM; tests drive [handle].
 *
 * The page itself ([PAGE]) is a build of Kiln's setup steps (kiln/apps/kiln/lan), shipped in the APK's
 * assets and read through [files]. Its CSP lets it load only its own files and open only this WebSocket.
 *
 * What is left: an upgraded connection has no write timeout. A browser that stops reading while the panel
 * writes to it holds its worker until the OS gives up on the TCP send (minutes). A newer paired channel and
 * [DONE] end such a channel at once ([WebSocketStreams.abort]); an unpaired one has its own single slot.
 */
class LanSetup(
    /** The setup channel's loop over a connection ([dev.glassworks.setup.SetupChannel.serve]); `mayChange` gates every op but `state`. */
    private val serve: (input: InputStream, output: OutputStream, mayChange: () -> Boolean) -> Unit,
    private val code: PanelCode,
    private val ownHosts: () -> Set<String>,
    /** Pairing is open: Home Assistant hasn't adopted the panel, or someone re-armed it lately. */
    private val pairingOpen: () -> Boolean,
    private val event: (String) -> Unit = {},
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val idleMs: Long = 30 * 60_000L,
    private val maxMs: Long = 2 * 3600_000L,
    private val maxSessions: Int = 4,
    /** How long an unpaired WebSocket may wait for the browser; a paired one waits [idleMs]. */
    private val unpairedIdleMs: Int = 60_000,
    /** The page's files by name (`index.html`, `setup.js`, `setup.css`, `inter.ttf`), or null when this build has none. */
    private val files: (String) -> ByteArray? = { null },
    /** A browser ended its setup ([DONE]): setup over the network is finished, and the setup hotspot can close (#197). */
    private val onDone: () -> Unit = {},
    /** Sees every request's address: a phone on the setup hotspot shows itself this way (#460). Null where there is none. */
    private val phoneJoin: PhoneJoin?,
) {
    private val random = SecureRandom()
    private val sessions = Sessions()

    /**
     * WebSockets at once, one of each kind: each holds one of the web server's few workers. An unpaired one can
     * read the state only, so it never keeps a paired browser out. The paired one is the newest: a browser that
     * pairs again, or another one, takes it over, so a channel whose session ended can't hold it (CodeRabbit on #376).
     */
    private val unpaired = AtomicInteger()
    private val held = AtomicReference<Held?>()

    private class Held(val token: String, val ws: WebSocketStreams)

    /** The answer for a request under `/setup/`, or null for one that isn't this class's (the Manage page takes it). */
    fun handle(req: HttpRequest): HttpResponse? {
        phoneJoin?.request(req.remote)
        val file = pageFile(req.path)
        if (file == null && req.path != PAIR && req.path != DONE && req.path != CHANNEL) return null
        if (!ManageServer.hostAllowed(req.headers["host"], ownHosts())) return HttpResponse.text(400, "Open this page with the panel's address.")
        if (file != null) return page(req, file)
        return when (req.path) {
            CHANNEL -> channel(req)
            PAIR -> when (req.method) {
                "GET" -> json(200, JSONObject().put("ok", true).put("paired", sessions.valid(req.cookies[COOKIE], req.remote, touch = false)).put("open", pairingOpen()))
                "POST" -> if (ManageServer.sameOrigin(req.headers)) pair(req) else notOwnPage()
                else -> HttpResponse.text(405, "Not allowed.")
            }
            else -> when {
                req.method != "POST" -> HttpResponse.text(405, "Not allowed.")
                !ManageServer.sameOrigin(req.headers) -> notOwnPage()
                else -> {
                    // Only from the address that paired, as every other use of the session: a cookie copied off the
                    // LAN can't end someone else's setup or drop their channel (#386 verifier).
                    val token = req.cookies[COOKIE]
                    if (sessions.end(token, req.remote)) {
                        event("Setup over the network finished")
                        onDone()
                        held.get()?.takeIf { it.token == token }?.ws?.abort()
                    }
                    json(200, JSONObject().put("ok", true), cookie(""))
                }
            }
        }
    }

    /**
     * Ends setup over the network now ("Close setup over the network", behind the panel's code): every paired
     * browser's session and its channel, then [onDone], as a browser's own [DONE] does. Pairing stays as
     * [pairingOpen] says; the caller closes that first (KioskPrefs.closeLanSetup).
     */
    fun close() {
        val ended = sessions.endAll()
        held.getAndSet(null)?.ws?.abort()
        event("Setup over the network closed${if (ended > 0) ": $ended paired ${if (ended == 1) "browser" else "browsers"} ended" else ""}")
        onDone()
    }

    /** `POST` [PAIR]: show a code on the panel, or check the one typed. */
    private fun pair(req: HttpRequest): HttpResponse {
        if (!pairingOpen()) return problem(403, PAIRING_OFF)
        val typed = req.form["code"]
        val id = req.cookies[COOKIE]?.takeIf { it.length in 20..64 }
        if (typed == null) {
            val requester = id ?: token(24)
            val newId = if (id == null) cookie(requester) else emptyMap()
            return when (val shown = code.show(PURPOSE, LABEL, requester)) {
                is PanelCode.Show.Ok -> json(200, JSONObject().put("ok", true).put("shown", true), newId)
                is PanelCode.Show.Locked -> problem(429, locked(shown.untilMs))
                is PanelCode.Show.Busy -> problem(429, when (shown.why) {
                    PanelCode.Why.SHOWING -> "The panel is showing another code. Try again in ${seconds(shown.untilMs)} seconds."
                    PanelCode.Why.TOO_MANY -> "The panel has shown too many codes lately. Try again in ${minutes(shown.untilMs)} minutes."
                })
                PanelCode.Show.Unavailable -> problem(503, "The panel can't show a code right now.")
            }
        }
        if (id == null) return problem(409, NO_CODE)
        return when (val c = code.check(PURPOSE, id, typed)) {
            PanelCode.Check.Ok -> {
                event("A browser paired with the panel to set it up over the network (${req.remote ?: "unknown address"})")
                json(200, JSONObject().put("ok", true).put("paired", true), cookie(sessions.create(req.remote)))
            }
            is PanelCode.Check.Wrong -> {
                event("Wrong code typed to set the panel up over the network")
                problem(401, if (c.triesLeft > 0) "That's not the code. ${c.triesLeft} ${if (c.triesLeft == 1) "try" else "tries"} left."
                    else "That's not the code, and that was the last try. Show a new one.")
            }
            PanelCode.Check.NoCode -> problem(409, NO_CODE)
            is PanelCode.Check.Locked -> problem(429, locked(c.untilMs))
        }
    }

    /** [CHANNEL]: the WebSocket, for a page on the panel's own origin only. */
    private fun channel(req: HttpRequest): HttpResponse {
        val key = WebSocketStreams.key(req) ?: return HttpResponse.text(400, "This address takes a WebSocket only.")
        val origin = req.headers["origin"]
        if (origin == null || !ManageServer.sameOrigin(req.headers)) return notOwnPage()
        // The session this channel runs under, when the browser has paired; an unpaired channel has none.
        val remote = req.remote
        val token = req.cookies[COOKIE]?.takeIf { sessions.valid(it, remote, touch = false) }
        if (token == null && unpaired.get() >= 1) return HttpResponse.text(503, "Another browser is reading this panel's setup. Try again in a moment.")
        return HttpResponse.webSocket(key, if (token != null) idleMs.toInt() else unpairedIdleMs) { ws ->
            val mine = token?.let { Held(it, ws) }
            if (mine != null) {
                held.getAndSet(mine)?.ws?.abort()
            } else if (unpaired.incrementAndGet() > 1) {
                unpaired.decrementAndGet()
                ws.close(1013)
                return@webSocket
            }
            try {
                serve(ws.input, ws.output) { token != null && sessions.valid(token, remote, touch = true) }
            } catch (_: IOException) {
                // The browser went away mid-write, or a newer paired channel took over.
            } finally {
                if (mine != null) held.compareAndSet(mine, null) else unpaired.decrementAndGet()
                ws.close()
            }
        }
    }

    /** The page's file name for [path], or null when [path] isn't the page. */
    private fun pageFile(path: String): String? = when (path) {
        PAGE, "$PAGE/" -> "index.html"
        else -> path.removePrefix("$PAGE/").takeIf { path.startsWith("$PAGE/") && it in PAGE_TYPES }
    }

    /** [PAGE] and its files: GET only, revalidated on every load, the page under a CSP that names this panel's WebSocket. */
    private fun page(req: HttpRequest, name: String): HttpResponse {
        if (req.method != "GET" && req.method != "HEAD") return HttpResponse.text(405, "Not allowed.")
        val bytes = files(name) ?: return HttpResponse.text(404, "This build of the app has no setup page.")
        val headers = mapOf("Content-Type" to PAGE_TYPES.getValue(name), "Cache-Control" to "no-cache", "X-Content-Type-Options" to "nosniff") +
            if (name != "index.html") emptyMap() else mapOf(
                // The Host header passed hostAllowed: one of the panel's own names, with a port of digits.
                "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data:; " +
                    "connect-src 'self' ws://${req.headers["host"]}; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
                "X-Frame-Options" to "DENY",
                "Referrer-Policy" to "same-origin",
            )
        return HttpResponse(200, headers, bytes)
    }

    /**
     * The paired browsers. Only the SHA-256 of each cookie is kept; nothing is written to disk. Thread-safe.
     *
     * A session counts only from the address that paired ([Paired.remote]). The page is plain HTTP on the LAN, as
     * the Manage page is (no certificate a browser trusts for a LAN address), so another device that copies the
     * cookie off the network can't use it from its own address (CodeRabbit on PR 386).
     */
    private inner class Sessions {
        private inner class Paired(val createdMs: Long, var usedMs: Long, val remote: String?)

        private val byKey = LinkedHashMap<String, Paired>()

        @Synchronized
        fun create(remote: String?): String {
            val t = token(32)
            val now = monoMs()
            byKey[SessionStore.hash(t)] = Paired(now, now, remote)
            while (byKey.size > maxSessions) byKey.remove(byKey.entries.minByOrNull { it.value.usedMs }!!.key)
            return t
        }

        /** A paired browser's cookie, good now and from [remote]. [touch] counts it as used (an op that changes something). */
        @Synchronized
        fun valid(token: String?, remote: String?, touch: Boolean): Boolean {
            if (token.isNullOrEmpty() || token.length > 100) return false
            val k = SessionStore.hash(token)
            val p = byKey[k] ?: return false
            if (p.remote != remote) return false
            val now = monoMs()
            if (now - p.usedMs >= idleMs || now - p.createdMs >= maxMs) { byKey.remove(k); return false }
            if (touch) p.usedMs = now
            return true
        }

        /** Ends every session; how many there were. */
        @Synchronized
        fun endAll(): Int = byKey.size.also { byKey.clear() }

        @Synchronized
        /** Ends [token]'s session when [remote] is the address that paired it. */
        fun end(token: String?, remote: String?): Boolean {
            if (token.isNullOrEmpty() || token.length > 100) return false
            val k = SessionStore.hash(token)
            if (byKey[k]?.remote != remote) return false
            return byKey.remove(k) != null
        }
    }

    private fun token(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    /** The setup cookie: the browser's id before pairing, its session after. Empty clears it. */
    private fun cookie(value: String) = mapOf("Set-Cookie" to "$COOKIE=$value; Path=/setup; HttpOnly; SameSite=Strict" + if (value.isEmpty()) "; Max-Age=0" else "")

    private fun seconds(untilMs: Long) = ((untilMs - wallMs() + 999) / 1000).coerceAtLeast(1)

    private fun minutes(untilMs: Long) = ((untilMs - wallMs() + 59_999) / 60_000).coerceAtLeast(1)

    private fun locked(untilMs: Long) = "Too many wrong codes. Try again in ${minutes(untilMs)} minutes."

    private fun notOwnPage() = HttpResponse.text(403, "This request didn't come from the panel's own page.")

    private fun problem(status: Int, text: String) = json(status, JSONObject().put("ok", false).put("error", text))

    private fun json(status: Int, o: JSONObject, extra: Map<String, String> = emptyMap()) = HttpResponse(
        status, mapOf("Content-Type" to "application/json; charset=utf-8", "Cache-Control" to "no-store") + extra,
        o.toString().toByteArray(Charsets.UTF_8),
    )

    companion object {
        const val PAGE = "/setup"
        const val PAIR = "/setup/pair"
        const val DONE = "/setup/done"
        const val CHANNEL = "/setup/channel"
        const val COOKIE = "gw_setup"
        const val PURPOSE = "setup"
        const val LABEL = "Set up this panel from a computer"

        /** How long a re-arm opens pairing again (the Home Assistant button, the USB op). */
        const val ARM_MS = 30 * 60_000L

        /** The page's files ([files]), by name, with their types. */
        val PAGE_TYPES = mapOf(
            "index.html" to "text/html; charset=utf-8",
            "setup.js" to "text/javascript; charset=utf-8",
            "setup.css" to "text/css; charset=utf-8",
            "inter.ttf" to "font/ttf",
        )

        const val PAIRING_OFF = "Pairing is off: Home Assistant has added this panel. Turn it on with Allow setup over the network: " +
            "in Home Assistant, in the panel's settings, or over USB with Kiln."
        private const val NO_CODE = "That code has run out. Show a new one."
    }
}
