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
 */
class LanSetup(
    /** The setup channel's loop over a connection ([sh.edm.s1kiosk.setup.SetupChannel.serve]); `mayChange` gates every op but `state`. */
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
) {
    private val random = SecureRandom()
    private val sessions = Sessions()

    /**
     * WebSockets at once, one of each kind: each holds one of the web server's few workers. An unpaired one can
     * read the state only, so it never keeps a paired browser out (CodeRabbit on #376).
     */
    private val paired = AtomicInteger()
    private val unpaired = AtomicInteger()

    /** The answer for a request under `/setup/`, or null for one that isn't this class's (the Manage page takes it). */
    fun handle(req: HttpRequest): HttpResponse? {
        if (req.path != PAIR && req.path != DONE && req.path != CHANNEL) return null
        if (!ManageServer.hostAllowed(req.headers["host"], ownHosts())) return HttpResponse.text(400, "Open this page with the panel's address.")
        return when (req.path) {
            CHANNEL -> channel(req)
            PAIR -> when (req.method) {
                "GET" -> json(200, JSONObject().put("ok", true).put("paired", sessions.valid(req.cookies[COOKIE], touch = false)).put("open", pairingOpen()))
                "POST" -> if (ManageServer.sameOrigin(req.headers)) pair(req) else notOwnPage()
                else -> HttpResponse.text(405, "Not allowed.")
            }
            else -> when {
                req.method != "POST" -> HttpResponse.text(405, "Not allowed.")
                !ManageServer.sameOrigin(req.headers) -> notOwnPage()
                else -> {
                    if (sessions.end(req.cookies[COOKIE])) event("Setup over the network finished")
                    json(200, JSONObject().put("ok", true), cookie(""))
                }
            }
        }
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
                json(200, JSONObject().put("ok", true).put("paired", true), cookie(sessions.create()))
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
        val token = req.cookies[COOKIE]
        val isPaired = sessions.valid(token, touch = false)
        val slot = if (isPaired) paired else unpaired
        if (slot.get() >= 1) return HttpResponse.text(503, "Another browser is setting this panel up. Try again in a moment.")
        return HttpResponse.webSocket(key, if (isPaired) idleMs.toInt() else unpairedIdleMs) { ws ->
            if (slot.incrementAndGet() > 1) {
                slot.decrementAndGet()
                ws.close(1013)
                return@webSocket
            }
            try {
                serve(ws.input, ws.output) { sessions.valid(token, touch = true) }
            } catch (_: IOException) {
                // The browser went away mid-write.
            } finally {
                slot.decrementAndGet()
                ws.close()
            }
        }
    }

    /** The paired browsers. Only the SHA-256 of each cookie is kept; nothing is written to disk. Thread-safe. */
    private inner class Sessions {
        private inner class Paired(val createdMs: Long, var usedMs: Long)

        private val byKey = LinkedHashMap<String, Paired>()

        @Synchronized
        fun create(): String {
            val t = token(32)
            val now = monoMs()
            byKey[SessionStore.hash(t)] = Paired(now, now)
            while (byKey.size > maxSessions) byKey.remove(byKey.entries.minByOrNull { it.value.usedMs }!!.key)
            return t
        }

        /** A paired browser's cookie, good now. [touch] counts it as used (an op that changes something). */
        @Synchronized
        fun valid(token: String?, touch: Boolean): Boolean {
            if (token.isNullOrEmpty() || token.length > 100) return false
            val k = SessionStore.hash(token)
            val p = byKey[k] ?: return false
            val now = monoMs()
            if (now - p.usedMs >= idleMs || now - p.createdMs >= maxMs) { byKey.remove(k); return false }
            if (touch) p.usedMs = now
            return true
        }

        @Synchronized
        fun end(token: String?): Boolean = !token.isNullOrEmpty() && byKey.remove(SessionStore.hash(token)) != null
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
        const val PAIR = "/setup/pair"
        const val DONE = "/setup/done"
        const val CHANNEL = "/setup/channel"
        const val COOKIE = "gw_setup"
        const val PURPOSE = "setup"
        const val LABEL = "Set up this panel from a computer"

        /** How long a re-arm opens pairing again (the Home Assistant button, the USB op). */
        const val ARM_MS = 30 * 60_000L

        const val PAIRING_OFF = "Home Assistant has added this panel, so pairing over the network is off. Turn it on again with " +
            "the panel's “Allow setup over the network” button in Home Assistant, or over USB with the installer."
        private const val NO_CODE = "That code has run out. Show a new one."
    }
}
