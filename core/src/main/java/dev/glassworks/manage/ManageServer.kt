/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsItem
import dev.glassworks.web.HttpRequest
import dev.glassworks.web.HttpResponse
import dev.glassworks.web.PageShell
import java.net.URI
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The panel's Manage page at `http://<panel>:8099/` (#33). It shares the port with the QR-code pages:
 * [qrPaths] and, until the panel is set up ([setUp]), `/` go to [qr] untouched and need no session.
 * Everything else needs a signed-in browser.
 *
 * Signing in: with Home Assistant ([HaSignIn]: local HA only, admins only, HA's token revoked at once), or
 * with the code on the panel's screen ([PanelCode]). Either gives the browser this page's own session
 * ([SessionStore]): an HttpOnly, SameSite=Strict cookie, for 30 days when remembered.
 *
 * Every POST must come from this page: its `Origin` is the panel's own, and a signed-in one carries the
 * session's CSRF token. A dangerous action (reboot) also needs the code the panel shows at that moment.
 * Pure JVM; tests drive [handle].
 */
class ManageServer(
    private val host: ManageHost,
    /** The HA address the panel uses, or null. Sessions are tied to it. */
    private val haBase: () -> String?,
    /** Setup is done: `/` is the Manage page, not the QR sign-in. */
    private val setUp: () -> Boolean,
    private val sessions: SessionStore,
    private val code: PanelCode,
    private val signIn: HaSignIn,
    private val log: ManageLog,
    private val qrPaths: Set<String>,
    private val qr: (HttpRequest) -> HttpResponse,
    /**
     * The panel's own addresses and names, lowercase, without a port or brackets: its IP addresses, its
     * mDNS name. The page answers only to these (DNS rebinding, including a spoofed `.local` answer).
     */
    private val ownHosts: () -> Set<String>,
    private val pages: ManagePages = ManagePages(),
    private val event: (String) -> Unit = {},
    /** How long a settings change may take before the page answers "still applying" (the radio's off waits on the radio). */
    private val saveWaitMs: Long = SAVE_WAIT_MS,
    /** A monotonic clock in ms for the questions' expiry: a wall clock that jumps (NTP, a time zone) mustn't stretch or cut it. */
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** All panels' scripts by file name ([ManagePages.PANELS_SCRIPT]), or null when this build has none (#466). */
    private val scripts: (String) -> ByteArray? = { null },
    /** How often kept Home Assistant sign-ins of ended sessions are revoked, off the request threads; 0 for never (tests call [sweep]). */
    sweepMs: Long = 60_000,
    /** Runs [stop]'s last revokes off the caller's thread (it may be the main thread); tests run them at once. */
    private val background: (() -> Unit) -> Unit = { work -> Thread(work, "gw-manage-stop").apply { isDaemon = true }.start() },
    /** "Allow a new computer" on the Security page, where the app can answer adb's question (#435); null elsewhere. */
    private val usb: ManageUsbDebugging? = null,
) {
    private val random = SecureRandom()

    /** A Home Assistant token another panel's page brings, checked with this panel's own Home Assistant (#466). */
    /**
     * Home Assistant calls on the web server's threads (All panels' renewals and sign-outs, other panels' token checks)
     * share these few slots, fewer than its four workers: Home Assistant slow to answer leaves the rest of the Manage page
     * a worker. One that finds them full answers "busy, try again" (CodeRabbit on #534).
     */
    private val haCalls = java.util.concurrent.Semaphore(MAX_HA_CALLS)

    private val tokens = HaTokenCheck(signIn.gateway, haBase, signIn::isLocal, monoMs, asking = haCalls)

    private val handoffs = PerAddress(HANDOFFS_PER_WINDOW, HANDOFF_WINDOW_MS)

    private val api = ManageApi(host, tokens, haBase, ::apply, PENDING, event, ::ask) { id, owner, section, key, value -> answered(id, owner, section, key, value) }

    /** A question's answer (#269 verifier): the change it asked about, for one session, until it runs out. */
    private data class Asked(val session: String, val section: String, val key: String, val value: String, val untilMs: Long)

    /** The questions the page has shown, by their one-shot token. */
    private val asked = java.util.concurrent.ConcurrentHashMap<String, Asked>()

    /** A token for the question about [value] at [key]: the dialog's form sends it back with the answer. */
    private fun ask(session: String, section: String, key: String, value: String): String {
        val now = monoMs()
        asked.entries.removeIf { it.value.untilMs < now }
        // A browser can't pile questions up: the oldest go first.
        while (asked.size >= MAX_ASKED) asked.entries.minByOrNull { it.value.untilMs }?.let { asked.remove(it.key) } ?: break
        val b = ByteArray(18).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        asked[token] = Asked(session, section, key, value, now + ASK_MS)
        return token
    }

    /**
     * Each Home Assistant sign-in kept for All panels (#466), by the Manage session it came with. In memory only: an
     * app restart forgets them (they stay under the person's profile in HA until they expire or are removed there).
     */
    private val kept = java.util.concurrent.ConcurrentHashMap<String, HaSignIn.Kept>()

    /**
     * A session that ended (signed out, run out): its questions go with it, and its kept sign-in is ended in HA. Off the
     * request's thread unless [now]: a sign-out doesn't wait for Home Assistant's answer. The sign-in is no longer
     * usable at once, and one HA doesn't take is tried again by the sweep (CodeRabbit on #534).
     */
    private fun endSession(session: String, now: Boolean = false) {
        asked.entries.removeIf { it.value.session == session }
        if (now) {
            val k = kept[session] ?: unrevoked[session]
            val ended = endKept(session)
            leftFor.remove(session)
            k?.let { said(it, ended) }
            return
        }
        // One step under the lock: a forget that comes in between finds it in one map or the other, never in neither.
        val k = synchronized(grants) { kept.remove(session)?.also { queue(session, it) } } ?: return
        background { said(k, endKept(session)); leftFor.remove(session) }
    }

    /**
     * One line in the Log for how a session's kept sign-in ended in Home Assistant, as it ends with the session (sign-out,
     * Security, running out): a sign-out that ends it says so, and one HA didn't confirm says that (2026-11-19 panel run:
     * a sign-out's delete left no trace either way). No token in it. A sign-in left at an old address has its own line.
     */
    private fun said(k: HaSignIn.Kept, ended: Ended) {
        val what = if (k.longLived) "the long-lived Home Assistant token this panel made" else "the Home Assistant sign-in this panel kept"
        when (ended) {
            Ended.DONE -> event("Home Assistant ended $what for a Manage session that ended")
            Ended.NOT_CONFIRMED -> event("Home Assistant didn't confirm it ended $what for a Manage session that ended; the panel tries again" +
                if (k.longLived) ", and HA ends it within a day anyway" else "")
            Ended.LEFT -> {}
        }
    }

    /**
     * Ends the Home Assistant sign-in kept under [id] (a session, or a grant no session took), in HA. The Manage session
     * and its questions stay (CodeRabbit on #534). False when HA didn't confirm it, also while another call (the sweep, a
     * second Try again) is still ending it: that one may fail yet, so this one can't say it's done.
     */
    private fun endKept(id: String, order: Long? = null): Ended {
        if (!ending.add(id)) return Ended.NOT_CONFIRMED
        try {
            val k = synchronized(grants) {
                // The sweep's pick: only if it's still the entry it picked, not one queued again since (CodeRabbit on #534).
                if (order != null && retryOrder[id] != order) return Ended.DONE
                kept.remove(id) ?: unrevoked.remove(id)?.also { retryOrder.remove(id) }
            } ?: return if (id in leftFor) Ended.LEFT else Ended.DONE
            if (moved(k)) { leftAtOldAddress(); leftFor += id; return Ended.LEFT }
            // The address is checked again before HA's second try too: the token never follows it to an old one.
            if (signIn.end(k) { !moved(k) }) return Ended.DONE
            if (moved(k)) { leftAtOldAddress(); leftFor += id; return Ended.LEFT }
            // HA didn't take the revoke: no longer usable, but kept aside to try again (Try again, the sweep).
            retryLater(id, k)
            return Ended.NOT_CONFIRMED
        } finally {
            ending.remove(id)
        }
    }

    /**
     * Sessions whose kept sign-in was left at an old address: a later Try again (the first answer lost) still says so,
     * not "done", until the session ends (CodeRabbit on #534).
     */
    private val leftFor: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** How [endKept] went: revoked in HA, left at an old HA address unrevoked, or not confirmed (tried again later). */
    private enum class Ended { DONE, LEFT, NOT_CONFIRMED }

    /** Sessions whose kept sign-in a call to [forget] is ending in Home Assistant right now. */
    private val ending: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Kept sign-ins whose revoke Home Assistant didn't take: not usable, tried again by [sweep] and by Try again. */
    private val unrevoked = java.util.concurrent.ConcurrentHashMap<String, HaSignIn.Kept>()

    /** Set by [stop]: a sign-in kept or left unrevoked after it goes to the last cleanup, not to a sweep that won't come. */
    @Volatile private var stopping = false

    /** Guards moving a grant between [kept] and [unrevoked]. */
    private val grants = Any()

    /** When each [unrevoked] entry was queued: the sweep tries the longest-waiting first, a few per pass. */
    private val retryOrder = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val queued = java.util.concurrent.atomic.AtomicLong()

    private fun queue(id: String, k: HaSignIn.Kept) {
        // Never over another one still waiting: that one stays tracked under a key of its own, and the sweep retries both.
        val order = queued.incrementAndGet()
        val key = if (unrevoked[id].let { it != null && it !== k }) "$id~$order" else id
        unrevoked[key] = k
        retryOrder[key] = order
    }

    private fun retryLater(id: String, k: HaSignIn.Kept) {
        synchronized(grants) { queue(id, k) }
        if (stopping) drain()
    }

    /** Ends, once more and off this thread, every sign-in still kept or unrevoked. */
    private fun drain() {
        val left = ArrayList<HaSignIn.Kept>()
        for (id in kept.keys.toCollection(ArrayList())) kept.remove(id)?.let(left::add)
        for (id in unrevoked.keys.toCollection(ArrayList())) unrevoked.remove(id)?.let(left::add)
        retryOrder.clear()
        if (left.isEmpty()) return
        background {
            for (k in left) {
                if (moved(k)) { leftAtOldAddress(); continue }
                // The last try: one HA doesn't take stays in HA, and the Log says where to remove it (CodeRabbit on #534).
                if (runCatching { signIn.end(k) { !moved(k) } }.getOrDefault(false) != true) {
                    if (moved(k)) { leftAtOldAddress(); continue }
                    event("Home Assistant didn't confirm it ended a Manage sign-in as the page stopped. It stays under that person's profile in Home Assistant (Security, Refresh tokens) until removed there.")
                }
            }
        }
    }

    /**
     * Whether [k] came from another Home Assistant address than the panel's now. Its refresh token never goes to the old
     * address: whatever answers there now may not be that Home Assistant. The panel's own sign-in does the same
     * (`HaAuth.signOut`: "Home Assistant moved, so no revoke sent"), CodeRabbit on #534.
     */
    private fun moved(k: HaSignIn.Kept) = k.haBase != haBase()?.trim()?.trimEnd('/')

    private fun leftAtOldAddress() = event("Home Assistant's address changed, so a Manage sign-in wasn't revoked at the old one. It stays under that person's profile in Home Assistant (Security, Refresh tokens) until removed there.")

    private val sweeper = if (sweepMs > 0) java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gw-manage-sweep").apply { isDaemon = true } }
        .also { it.scheduleWithFixedDelay({ runCatching { sweep() } }, sweepMs, sweepMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    else null

    /**
     * The Manage page stops (the kiosk service stops its web server): the sweep stops with it, and every Home Assistant
     * sign-in it still keeps, usable or waiting for a retry, is ended in HA once more, off this thread. One HA doesn't
     * take then stays under the person's profile, as after an app restart (CodeRabbit on #534). A revoke still under way,
     * or a sign-in that finishes after, goes to the same last cleanup when it comes back.
     */
    fun stop() {
        stopping = true
        sweeper?.shutdownNow()
        drain()
    }

    /**
     * Ends the kept sign-ins of sessions that have ended (run out, signed out elsewhere, HA's address changed). Runs
     * every [sweepMs] on its own thread, so no request waits on Home Assistant's revoke (CodeRabbit on #534).
     */
    fun sweep() = dropEnded()

    private fun dropEnded() {
        // Copies made by iterating: toList()'s one-item shortcut can call next() on a key another thread just took.
        // The retries are picked before the ends below: one that fails there is queued again for a later pass, not
        // tried twice more at once (CodeRabbit on #534).
        val due = unrevoked.keys.toCollection(ArrayList()).map { it to (retryOrder[it] ?: 0L) }.sortedBy { it.second }.take(RETRIES_PER_SWEEP)
        // The candidates first, then the live sessions: one signed in meanwhile isn't a candidate (CodeRabbit on #534).
        // Read without pruning: list() would end the sessions of another address, perhaps one just made for a new one.
        val candidates = kept.keys.toCollection(ArrayList())
        val marked = leftFor.toCollection(ArrayList())
        if (candidates.isNotEmpty() || marked.isNotEmpty()) {
            val live = sessions.liveIds()
            // A "left at the old address" mark lasts as long as its session (CodeRabbit on #534).
            for (id in marked) if (id !in live) leftFor.remove(id)
            val ha = haBase()?.trim()?.trimEnd('/')
            for (id in candidates) {
                if (id !in live) endSession(id, now = true)
                else if (kept[id]?.let { it.haBase != ha } == true) endKept(id)
            }
        }
        // Then the revokes HA didn't take, the longest-waiting first and a few per pass: an HA that doesn't answer can't
        // hold back the newly ended ones above for long. The rest wait for the next pass (CodeRabbit on #534).
        for ((id, order) in due) endKept(id, order)
    }



    /**
     * A factory reset (#33): signs every browser out, the one that asked for it too, and ends in Home Assistant the
     * sign-ins All panels kept for them. False when the sign-outs couldn't be saved: they hold until the app restarts.
     */
    fun signOutAll(): Boolean {
        val (ids, saved) = sessions.revokeAll()
        ids.forEach { endSession(it) }
        event("Every browser was signed out (factory reset)")
        return saved != SessionStore.Revoked.NOT_SAVED
    }

    /** How many sessions are marked as having their sign-in left at an old address (tests). */
    fun leftMarks(): Int = leftFor.size

    /** Whether [session] has a Home Assistant sign-in kept for All panels (tests). */
    fun keepsHaSignIn(session: String): Boolean = kept.containsKey(session)

    /** How many questions wait for [session]'s answer (tests). */
    fun questionsWaiting(session: String): Int = asked.values.count { it.session == session }

    /**
     * Whether [token] answers the question about exactly this change for this session. Used up either way: a
     * replayed or a stale answer asks again. A form with `confirmed=1` and no token is no answer.
     */
    private fun answered(token: String?, session: String, section: String, key: String, value: String): Boolean {
        if (token.isNullOrEmpty()) return false
        val a = asked.remove(token) ?: return false
        return a.untilMs >= monoMs() && a == a.copy(session = session, section = section, key = key, value = value)
    }

    fun handle(req: HttpRequest): HttpResponse {
        // First, for the QR pages too: a page on another site whose name resolves here (DNS rebinding) mustn't start or
        // finish the panel's sign-in either. Every QR code names the panel by one of its own addresses.
        if (!hostAllowed(req.headers["host"], ownHosts())) return HttpResponse.text(400, "Open this page with the panel's address.")
        if (req.path in qrPaths) return qrPage(req)
        // Before setup, / is the QR sign-in, except for a browser already signed in to Manage (with the code).
        if (req.path == "/" && !setUp() && sessions.lookup(req.cookies[SESSION], haBase()) == null) return qrPage(req)
        // Called from a page on another panel, with a Home Assistant token rather than this page's cookie (#466).
        if (req.path.startsWith(ManageApi.PREFIX)) return api.handle(req)
        if (req.path == HANDOFF) return if (req.method == "POST") handoff(req) else HttpResponse.seeOther("/")
        if (req.path.startsWith(SCRIPTS)) return script(req.path.removePrefix(SCRIPTS))
        if (req.method != "GET" && req.method != "HEAD" && req.method != "POST") return HttpResponse.text(405, "Not allowed.")
        if (req.method == "POST" && !sameOrigin(req)) return HttpResponse.text(403, "This form didn't come from the panel's own page.")
        val session = sessions.lookup(req.cookies[SESSION], haBase())
        // Back from the reload in signedOut() with the cookie: the same page, without the mark.
        // safeNext: a path on this panel only (`//host/` would leave it).
        if (session != null && req.method == "GET" && req.query[RELOADED] != null) return HttpResponse.seeOther(safeNext(withoutMark(req)))
        return when (req.path) {
            "/favicon.ico" -> HttpResponse.text(404, "")
            "/manage/ha" -> post(req) { startHa(req) }
            HaSignIn.CALLBACK -> haCallback(req)
            "/manage/code" -> if (req.method == "POST") codeSignIn(req) else codeDialog(req, null)
            "/manage/signout" -> post(req) { signOut(req, session) }
            else -> if (session == null) signedOut(req) else signedIn(req, session)
        }
    }

    // ------------------------------------------------------------------ the QR pages

    /** [req] carries a live session for this page (its cookie, for the Home Assistant the panel uses now). */
    fun signedIn(req: HttpRequest): Boolean = sessions.lookup(req.cookies[SESSION], haBase()) != null

    /** The QR pages as they are, with the way back to Manage in their header for a signed-in browser. */
    private fun qrPage(req: HttpRequest): HttpResponse {
        val r = qr(req)
        val signed = sessions.lookup(req.cookies[SESSION], haBase()) != null
        val right = if (signed) """<a class="to" href="/">Manage this panel <span aria-hidden="true">›</span></a>""" else ""
        return HttpResponse(r.status, r.headers, PageShell.fillHeaderSlot(r.body, right))
    }

    // ------------------------------------------------------------------ signing in

    private fun signedOut(req: HttpRequest): HttpResponse {
        // All panels' calls get an answer the page can read: a redirect is an error to it, which it would take for an outage.
        if (req.method == "POST" && (req.path == HA_ACCESS || req.path == HA_FORGET || req.path == HA_KEEP)) return json(401, JSONObject().put("problem", MANAGE_ENDED))
        if (req.method == "POST") return HttpResponse.seeOther("/")
        // Arrived from another site (HA's "Visit"): the SameSite=Strict cookie stayed behind. Load the page again
        // from here, which is same-site, so the cookie comes. Browsers send Sec-Fetch-Site only to https and
        // loopback, never to the panel's http:// address on the LAN, so no header counts too, when no cookie came
        // at all. The mark keeps it to one reload: back without a session, it's the sign-in page.
        val site = req.headers["sec-fetch-site"]
        if (req.query[RELOADED] == null && req.cookies[SESSION] == null && (site == "cross-site" || site == null)) {
            val to = safeNext(withoutMark(req))
            return html(200, pages.bounce(name(), to + (if ('?' in to) "&" else "?") + "$RELOADED=1"))
        }
        return signInPage(req, safeNext(withoutMark(req)), null)
    }

    private fun signInPage(req: HttpRequest, next: String, problem: String?, status: Int = 200): HttpResponse {
        val ha = haBase()
        val haOk = ha != null && signIn.isLocal(ha)
        val extra = preCookie(req)
        return html(status, pages.signIn(name(), next, haOk, problem), formTargets = listOfNotNull(ha?.let(::origin)), extra = extra)
    }

    private fun startHa(req: HttpRequest): HttpResponse {
        val next = safeNext(req.form["next"])
        val ha = haBase() ?: return signInPage(req, next, "The panel doesn't know Home Assistant's address yet. Use the code on the panel.", 409)
        val hostHeader = req.headers["host"]!!
        val (pre, cookie) = pre(req)
        val (url, why) = signIn.start(ha, hostHeader.lowercase(), pre, req.form["remember"] == "1", next)
        if (url == null) return signInPage(req, next, why, 409)
        return HttpResponse.seeOther(url, cookie)
    }

    private fun haCallback(req: HttpRequest): HttpResponse {
        val q = req.query
        return when (val r = signIn.finish(q["state"], q["code"], q["error"], req.cookies[PRE])) {
            is HaSignIn.Result.Failed -> {
                event("Sign-in through Home Assistant refused: ${r.title}")
                html(403, PageShell.qrPage(name(), r.title, PageShell.esc(r.text), PageShell.Look.PROBLEM,
                    button = """<a class="btn" href="/">Back to sign in</a>"""))
            }
            is HaSignIn.Result.Ok -> {
                // Authenticated against the address the sign-in started with: if the panel's has changed since, start
                // again. Read once, so the session is bound to the address that was checked.
                val ha = haBase()
                if (r.haBase != ha?.trim()?.trimEnd('/')) {
                    // No session to keep it for: end it in HA. One HA didn't take waits for the sweep under its own id.
                    r.kept?.let { k -> if (!signIn.end(k)) retryLater("grant-" + ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }, k) }
                    return html(409, PageShell.qrPage(name(), "Home Assistant's address changed", "Sign in again.", PageShell.Look.PROBLEM,
                        button = """<a class="btn" href="/">Back to sign in</a>"""))
                }
                val (token, s) = sessions.create(r.user.name, SignedInWith.HOME_ASSISTANT, SessionStore.browserName(req.headers["user-agent"]), r.remember, ha)
                r.kept?.let { kept[s.id] = it }
                // The page stopped while HA answered: no sweep will end this one.
                if (stopping) drain()
                event("${r.user.name} signed in with Home Assistant (${s.browser})")
                // HA sent the browser here from another site: a redirect would still be cross-site, and the
                // new Strict cookie wouldn't go with it. A page that reloads to the target is same-site.
                html(200, PageShell.document(name(), "", """<meta http-equiv="refresh" content="0;url=${PageShell.esc(r.next)}">"""),
                    extra = mapOf("Set-Cookie" to sessionCookie(token, r.remember) + "\n" + clearCookie(PRE)))
            }
        }
    }

    /**
     * [HANDOFF], POST from a page on another panel (#466): `token` is the Home Assistant access token that page
     * holds. This panel asks its own Home Assistant who it belongs to ([HaTokenCheck]) and, for an administrator,
     * signs this browser in as a Home Assistant sign-in would, until the browser closes. The token is neither kept
     * nor revoked: it is the other page's. It comes from another site, so no Origin check; the token is the proof.
     */
    private fun handoff(req: HttpRequest): HttpResponse {
        fun refused(title: String, text: String, status: Int = 403, log: Boolean = true, extra: Map<String, String> = emptyMap()): HttpResponse {
            if (log) event("A sign-in from another panel's page was refused: $title")
            return html(status, PageShell.qrPage(name(), title, PageShell.esc(text), PageShell.Look.PROBLEM,
                button = """<a class="btn" href="/">Sign in here</a>"""), extra = extra)
        }
        // A fresh check asks Home Assistant every time: a page posting made-up tokens over and over (a cross-site form
        // can) would keep its few questions busy. So a few sign-ins per address at a time, then a pause (verifier on #487).
        if (!handoffs.allow(req.remote ?: "?", monoMs())) return refused("Too many sign-ins", HANDOFF_PAUSE, 429, log = false,
            extra = mapOf("Retry-After" to (HANDOFF_WINDOW_MS / 1000).toString()))
        // Asked fresh: an answer kept from before must not start a 12-hour sign-in for a token revoked since.
        val ok = when (val r = tokens.check(req.form["token"], fresh = true)) {
            is HaTokenCheck.Result.Ok -> r
            is HaTokenCheck.Result.NotAdmin -> return refused("Administrators only", "${r.name} isn't a Home Assistant administrator. Sign in with an administrator's account.")
            HaTokenCheck.Result.NoHa -> return refused("Can't check who you are", ManageApi.NO_HA)
            HaTokenCheck.Result.Refused -> return refused("Can't sign in", ManageApi.REFUSED)
            HaTokenCheck.Result.Busy -> return refused("Try again in a moment", ManageApi.BUSY)
        }
        // Bound to the address that confirmed the user, as haCallback binds its own: if it changed since, start again.
        val ha = haBase()
        if (ok.haBase != ha?.trim()?.trimEnd('/')) return refused("Home Assistant's address changed", "Sign in again.")
        val (token, s) = sessions.create(ok.user.name, SignedInWith.HOME_ASSISTANT, SessionStore.browserName(req.headers["user-agent"]), false, ha)
        event("${ok.user.name} signed in with Home Assistant from the Manage page on ${ManageApi.from(req, req.form["from"])} (${s.browser})")
        keepLongLived(req, s, ok.haBase, req.form["token"].orEmpty())
        // A page from another site posted here: a redirect would still be cross-site for the Strict cookie (see haCallback).
        val next = safeNext(req.form["next"])
        return html(200, PageShell.document(name(), "", """<meta http-equiv="refresh" content="0;url=${PageShell.esc(next)}">"""),
            extra = mapOf("Set-Cookie" to sessionCookie(token, false)))
    }

    /**
     * A handoff's session gets this panel's own Home Assistant sign-in, so All panels here needs no second login (2026-11-19
     * panel run): a long-lived token made with the handed access token, for a day, named after this panel and the browser
     * so it reads plainly under the person's profile. Kept in memory with the session and deleted in HA when it ends, like
     * any kept sign-in. The sender's own sign-in is never handed over; panels still don't trust each other. When HA
     * doesn't make one (or its calls are all busy), All panels here asks for Continue, as before.
     */
    private fun keepLongLived(req: HttpRequest, s: Session, ha: String, handed: String) {
        if (handed.isEmpty() || !haCalls.tryAcquire()) return
        val name = "Glassworks Manage on ${req.headers["host"] ?: "this panel"} (${s.browser}) " +
            ByteArray(2).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val made = try { signIn.gateway.createLongLived(ha, handed, name, LONG_LIVED_DAYS) } finally { haCalls.release() }
        if (made == null) { event("Home Assistant didn't make a sign-in for All panels here; it asks for one there"); return }
        kept[s.id] = HaSignIn.Kept(ha, "", made, longLived = true, untilMs = monoMs() + LONG_LIVED_USE_MS)
        // The page stopped meanwhile: no sweep will end this one.
        if (stopping) drain()
    }

    /**
     * [HA_KEEP], POST from All panels on this panel (same origin, CSRF): the tab signed in to Home Assistant on its own
     * (Continue), and hands that sign-in's refresh token to this panel, which keeps it for the session as a Manage
     * sign-in with HA would be kept. The tab then drops its copy: one sign-in, used by every tab and every handoff of this
     * browser's session (2026-11-19 panel run). HA must renew it and name an administrator first.
     */
    private fun haKeep(req: HttpRequest, s: Session): HttpResponse {
        val refresh = req.form["refresh"].orEmpty()
        if (refresh.isEmpty() || refresh.length > 4096) return json(400, JSONObject().put("problem", "No sign-in to keep."))
        val ha = haBase()?.trim()?.trimEnd('/')?.takeIf { signIn.isLocal(it) } ?: return json(404, JSONObject().put("problem", ManageApi.NO_HA))
        // The same sign-in kept already (a second request with it) is plain success; another one means "revoke yours".
        val same = { k: HaSignIn.Kept? -> k != null && !k.longLived && k.refreshToken == refresh }
        kept[s.id]?.let { return json(200, JSONObject().put("ok", true).also { o -> if (!same(it)) o.put("already", true) }) }
        // An earlier sign-in of this session is still waiting for HA to confirm its revoke: one at a time, so that one
        // stays tracked until HA confirms it (CodeRabbit on #534). The tab keeps its own meanwhile.
        if (unrevoked.containsKey(s.id)) return json(409, JSONObject().put("problem", "The panel is still ending this browser's earlier sign-in. Try again in a moment."))
        val host = req.headers["host"] ?: return json(400, JSONObject().put("problem", "No sign-in to keep."))
        val clientId = "http://$host/"
        if (!haCalls.tryAcquire()) return json(503, JSONObject().put("problem", BUSY))
        try {
            val t = signIn.gateway.refresh(ha, clientId, refresh)
            if (t !is HaGateway.Tokens.Ok) return json(if ((t as HaGateway.Tokens.Failed).refused) 404 else 503,
                JSONObject().put("problem", if (t.refused) "Home Assistant no longer takes this sign-in." else "Can't reach Home Assistant. Try again in a moment."))
            val user = signIn.gateway.currentUser(ha, t.accessToken)
            if (user?.isAdmin != true) return json(403, JSONObject().put("problem", "Only Home Assistant administrators manage panels."))
            if (s.id !in sessions.liveIds()) return json(401, JSONObject().put("problem", MANAGE_ENDED))
            // Another tab of this session kept its own first: this one isn't kept, so that tab revokes it (CodeRabbit on #534).
            kept.putIfAbsent(s.id, HaSignIn.Kept(ha, clientId, refresh))?.let { other ->
                return json(200, JSONObject().put("ok", true).also { o -> if (!same(other)) o.put("already", true) })
            }
            if (stopping) drain()
            event("${user.name}'s Home Assistant sign-in from All panels is kept for this browser (${s.browser})")
            return json(200, JSONObject().put("ok", true))
        } finally {
            haCalls.release()
        }
    }

    /**
     * All panels (#466). Its script talks to this panel's Home Assistant and to the other panels' Manage
     * pages (any host, on [PORT]), and posts the handoff to them: the CSP allows those and nothing else.
     */
    private fun panels(status: PanelStatus, viewer: ManagePages.Viewer, session: String): HttpResponse {
        val local = haBase()?.trim()?.trimEnd('/')?.takeIf { signIn.isLocal(it) }
        // A CSP host-source can't be an IPv6 literal (CSP's host grammar has none), so the page couldn't reach it: say so
        // instead of offering a page that can't work (CodeRabbit on #488).
        val v6 = local != null && (runCatching { URI(local).host }.getOrNull()?.startsWith("[") == true)
        val ha = local?.takeIf { !v6 }
        // From the same read as the address the page gets: a kept sign-in counts only if it's for that address (CodeRabbit on #534).
        val haSignIn = ha != null && kept[session]?.haBase == ha
        val haOrigin = ha?.let(::origin)?.let { o -> " $o " + o.replaceFirst("http", "ws") }.orEmpty()
        val csp = "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; frame-ancestors 'none'; " +
            "connect-src 'self' http://*:$PORT$haOrigin; form-action 'self' http://*:$PORT"
        return HttpResponse.html(200, pages.panels(status, viewer, ha, session, if (v6) IPV6_HA else null, haSignIn), PageShell.securityHeaders() + mapOf("Content-Security-Policy" to csp))
    }

    /** [ManagePages.PANELS_SCRIPT] and what it imports: GET, no session (they hold nothing but code). */
    private fun script(name: String): HttpResponse {
        val bytes = name.takeIf { it in SCRIPT_FILES }?.let(scripts) ?: return HttpResponse.text(404, "Not found.")
        return HttpResponse(200, mapOf("Content-Type" to "text/javascript; charset=utf-8", "Cache-Control" to "no-cache", "X-Content-Type-Options" to "nosniff"), bytes)
    }

    /**
     * [HA_ACCESS], POST from All panels on this panel (same origin, CSRF): a fresh access token from the Home Assistant
     * sign-in kept with this session, so All panels needs no second sign-in (#466 panel run). A session signed in with
     * the panel's code has none: All panels asks for Continue with Home Assistant then. Never logged.
     */
    private fun haAccess(s: Session): HttpResponse {
        val k = kept[s.id] ?: return json(404, JSONObject().put("problem", "No Home Assistant sign-in is kept for this browser."))
        val movedNow = { moved(k) }
        // The page has the old address: it reloads to get the new one, rather than signing in at the old (CodeRabbit on #534).
        val addressChanged = { json(404, JSONObject().put("problem", ADDRESS_RELOAD).put("reload", true)) }
        if (movedNow()) { endKept(s.id); return addressChanged() }
        // A long-lived token from a handoff is used as it is, until a little before HA lets it run out.
        if (k.longLived) {
            val leftMs = k.untilMs - monoMs()
            if (leftMs <= 60_000) { endKept(s.id); return json(404, JSONObject().put("problem", "This sign-in has ended.")) }
            return json(200, JSONObject().put("access_token", k.refreshToken).put("expires_in", minOf(leftMs / 1000, 1800L)))
        }
        // Home Assistant slow to answer mustn't take every worker: the Manage page's other requests still get one.
        if (!haCalls.tryAcquire()) return json(503, JSONObject().put("problem", BUSY))
        val t = try { signIn.gateway.refresh(k.haBase, k.clientId, k.refreshToken) } finally { haCalls.release() }
        // The Manage session ran out or was signed out while HA answered, whatever HA said: Sign in again, as for any
        // ended one; the sweep ends what it kept (CodeRabbit on #534).
        if (s.id !in sessions.liveIds()) return json(401, JSONObject().put("problem", MANAGE_ENDED))
        // The address changed while HA answered, whatever it said: reload for the new one (CodeRabbit on #534).
        if (movedNow()) { endKept(s.id); return addressChanged() }
        // All panels' Sign out, or HA's refusal, came meanwhile: no Try again for a sign-in that's over (CodeRabbit on #534).
        if (kept[s.id] !== k) return json(404, JSONObject().put("problem", "This sign-in has ended."))
        return when (t) {
            is HaGateway.Tokens.Ok -> if (kept[s.id] !== k) {
                // All panels' Sign out, or HA's refusal, came meanwhile: no token for a sign-in that's over.
                json(404, JSONObject().put("problem", "This sign-in has ended."))
            } else json(200, JSONObject().put("access_token", t.accessToken).put("expires_in", t.expiresIn))
            // HA refused it (revoked there, or run out): drop it, and All panels asks for a sign-in. Anything else (no
            // answer, a 5xx from HA or a proxy) is an outage: keep it, and All panels says so (CodeRabbit on #534).
            is HaGateway.Tokens.Failed -> if (t.refused) {
                kept.remove(s.id)
                json(404, JSONObject().put("problem", "Home Assistant no longer takes this sign-in."))
            } else json(503, JSONObject().put("problem",
                if (t.unreachable) "Can't reach Home Assistant. Try again in a moment." else "Home Assistant didn't renew the sign-in. Try again in a moment."))
        }
    }

    private fun json(status: Int, body: JSONObject) = HttpResponse(status,
        mapOf("Content-Type" to "application/json; charset=utf-8", "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
        body.toString().toByteArray(Charsets.UTF_8))

    /** `/manage/code`, POST: show a code on the panel, or check the one typed. */
    private fun codeSignIn(req: HttpRequest): HttpResponse {
        val next = safeNext(req.form["next"])
        val remember = req.form["remember"] == "1"
        val (pre, cookie) = pre(req)
        val typed = req.form["code"]
        if (typed == null) {
            return when (val shown = code.show(PURPOSE_SIGN_IN, "Sign in to manage this panel", pre)) {
                is PanelCode.Show.Ok -> HttpResponse.seeOther("/manage/code?next=" + enc(next) + if (remember) "&remember=1" else "", cookie)
                is PanelCode.Show.Locked -> codeLocked(req, next, remember)
                is PanelCode.Show.Busy -> { capped(req, shown); codeDialog(req, busyText(shown), next, remember, 429) }
                PanelCode.Show.Unavailable -> {
                    event("The panel can't show a code (it may not draw over other apps)")
                    signInPage(req, next, NO_CODE + if (haBase() != null) " Sign in with Home Assistant instead." else "", 503)
                }
            }
        }
        return when (val c = code.check(PURPOSE_SIGN_IN, pre, typed)) {
            PanelCode.Check.Ok -> {
                val (token, s) = sessions.create(null, SignedInWith.PANEL_CODE, SessionStore.browserName(req.headers["user-agent"]), remember, haBase())
                event("A browser signed in with the panel's code (${s.browser})")
                HttpResponse.seeOther(next, mapOf("Set-Cookie" to sessionCookie(token, remember) + "\n" + clearCookie(PRE)))
            }
            is PanelCode.Check.Wrong -> {
                event("Wrong code typed to sign in")
                codeDialog(req, if (c.triesLeft > 0) "That's not the code. ${c.triesLeft} ${if (c.triesLeft == 1) "try" else "tries"} left."
                    else "That's not the code, and that was the last try. Show a new one.", next, remember, 401)
            }
            PanelCode.Check.NoCode -> codeDialog(req, "That code has run out. Go back and show a new one.", next, remember, 401)
            is PanelCode.Check.Locked -> codeLocked(req, next, remember)
        }
    }

    private fun codeLocked(req: HttpRequest, next: String, remember: Boolean) =
        codeDialog(req, "Too many wrong codes. Try again after ${hhmm(code.lockedUntil() ?: 0)}.", next, remember, 429)

    private fun codeDialog(req: HttpRequest, problem: String?, next: String = safeNext(req.query["next"]),
                           remember: Boolean = req.query["remember"] == "1", status: Int = 200): HttpResponse {
        val html = pages.codeDialog(
            name(), "Enter the code shown on the panel", "The panel is showing a 6-digit code right now.", "/manage/code", "Sign in",
            "/", mapOf("next" to next) + if (remember) mapOf("remember" to "1") else emptyMap(), problem, danger = false,
        )
        return html(status, html, extra = preCookie(req))
    }

    private fun signOut(req: HttpRequest, session: Session?): HttpResponse {
        if (session == null) return HttpResponse.seeOther("/")
        if (!csrfOk(req, session)) return HttpResponse.text(403, "This form has expired. Go back and try again.")
        if (sessions.revoke(session.id) == SessionStore.Revoked.NOT_SAVED) return notSaved()
        endSession(session.id)
        event("${who(session)} signed out (${session.browser})")
        return HttpResponse.seeOther("/", mapOf("Set-Cookie" to clearCookie(SESSION)))
    }

    // ------------------------------------------------------------------ signed in

    private fun signedIn(req: HttpRequest, s: Session): HttpResponse {
        val viewer = ManagePages.Viewer(who(s), s.csrf, homeAssistant = s.with == SignedInWith.HOME_ASSISTANT)
        if (req.method == "POST") {
            if (!csrfOk(req, s)) return HttpResponse.text(403, "This form has expired. Go back and try again.")
            return posted(req, s, viewer)
        }
        val status = host.status()
        val path = req.path
        return when {
            path == "/" -> html(200, pages.overview(status, viewer, host.actions, notice(req.query["done"])))
            path == "/log" -> {
                val level = when (req.query["level"]) { "warning" -> LogEntry.Level.WARNING; "error" -> LogEntry.Level.ERROR; else -> LogEntry.Level.INFO }
                val q = req.query["q"].orEmpty().take(100)
                html(200, pages.log(status, viewer, log.read(level, q), level, q))
            }
            path == "/log/diagnostics.txt" -> diagnostics()
            path == "/security" -> html(200, securityPage(status, viewer, s.id, notice(req.query["done"]), req.query["usb"]))
            path == "/about" -> html(200, pages.about(status, viewer))
            path == ManagePages.PANELS -> panels(status, viewer, s.id)
            path == "/confirm/reboot" -> html(200, rebootDialog(status, viewer, null))
            else -> {
                val edit = req.query["edit"]?.let { ManagePages.Dialog.Edit(it) }
                settingsPage(status, viewer, path.removePrefix("/"), dialog = edit, done = req.query["done"],
                    paused = req.query[ManagePages.LIVE_PARAM] == "off", mine = s.id)
                    ?: html(404, pages.notFound(status, viewer))
            }
        }
    }

    /** The Security page: the signed-in browsers ([mine] is this one's), the core's Security section, and this frame's cards. */
    private fun securityPage(status: PanelStatus, viewer: ManagePages.Viewer, mine: String, notice: String?, usbQuery: String? = null,
                             dialog: ManagePages.Dialog? = null, problem: String? = null): String =
        pages.security(status, viewer, sessions.list(haBase()), mine, notice, more = usb?.cards(viewer, usbQuery).orEmpty(), dialog = dialog, problem = problem)

    /**
     * A pane's or a core section's page by [id], or null when the panel has no such page. The core's Security section
     * draws on the Security page, with the signed-in browsers; [mine] is the session that asks.
     */
    private fun settingsPage(status: PanelStatus, viewer: ManagePages.Viewer, id: String, dialog: ManagePages.Dialog? = null,
                             problem: String? = null, done: String? = null, code: Int = 200, problemSee: String? = null, paused: Boolean = false,
                             mine: String = ""): HttpResponse? {
        val pane = status.panes.firstOrNull { it.id == id }
        val section = status.sections.firstOrNull { it.id == (pane?.sectionId ?: id) }
        if (pane == null && section == null) return null
        // An edit link for an item the page doesn't have now, or one changed in its row (a switch, a slider), is the page itself.
        val shown = dialog?.takeIf { d ->
            val item = section?.let { SettingsChange.find(it, d.key) }
            d is ManagePages.Dialog.Confirm || (item != null && item !is SettingsItem.Switch && item !is SettingsItem.Slider && item !is SettingsItem.Action)
        }
        if (pane == null && id == ManagePages.SECURITY) return html(code, securityPage(status, viewer, mine, sectionNotice(done), dialog = shown, problem = problem))
        return html(code, pages.section(status, viewer, "/$id", pane?.title ?: section!!.title, pane?.state, section, pane?.links.orEmpty(),
            dialog = shown, problem = problem, restart = done == "restart", notice = sectionNotice(done), problemSee = problemSee, paused = paused))
    }

    private fun sectionNotice(done: String?): String? = when (done) {
        "gone" -> "That setting isn't on this page now. Here is the page as it is."
        "pending" -> "The panel is still applying that. Reload in a moment to see it."
        else -> null
    }

    /**
     * `/settings`, POST: change one item, as the panel's menu does (#242). The form names the section, the
     * item's key ([SettingsChange.changeable]) and the value; [SettingsChange.change] checks it and calls the
     * item's own save, on the panel's main thread ([ManageHost.onMain]). A switch or an action that asks first
     * on the panel asks here too, for either sign-in, and runs only when the dialog's one-shot `answer` comes
     * back for the same session and change.
     */
    private fun changeSetting(req: HttpRequest, s: Session, viewer: ManagePages.Viewer): HttpResponse {
        val status = host.status()
        val id = req.form["section"].orEmpty()
        val section = status.sections.firstOrNull { it.id == id } ?: return HttpResponse.seeOther("/")
        val page = "/" + (status.panes.firstOrNull { it.sectionId == id }?.id ?: id)
        val key = req.form["key"].orEmpty()
        val item = SettingsChange.find(section, key) ?: return HttpResponse.seeOther("$page?done=gone")
        val what = ManageApi.what(section, key)
        if (item is SettingsItem.SignIn) return changeSignIn(req, s, viewer, page, key, what, item)
        // The time window's dialog sends On, From and To; the rest send their value as text. Trimmed here, as
        // SettingsChange.change trims it, so a padded value (" run ") can't skip the question (CodeRabbit on #331).
        val raw = (if (item is SettingsItem.TimeRange) (if (req.form["on"] == "1") "${req.form["from"].orEmpty()}-${req.form["to"].orEmpty()}" else "")
            else req.form["value"].orEmpty()).trim()
        // A change that asks first on the panel asks here too, and runs only with the answer to that question:
        // a one-shot token the dialog carries, for this session and this exact change (#269 verifier).
        val question = ManageApi.question(item, raw)
        val atPanel = item is SettingsItem.Action && item.needsCode && raw == SettingsItem.Action.RUN
        val confirmed = (question != null || atPanel) && answered(req.form["answer"], s.id, id, key, raw)
        if (question != null && !confirmed) {
            return settingsPage(status, viewer, page.removePrefix("/"), ManagePages.Dialog.Confirm(key, raw, question, ask(s.id, id, key, raw)), mine = s.id)!!
        }
        // Then, for an action that needs someone at the panel, the code it shows: the same one-shot answer binds the code to this change.
        if (atPanel) codeGate(req, s, viewer, id, key, page, item as SettingsItem.Action, confirmed)?.let { return it }
        val problem = apply(item, raw)
        val did = if (item is SettingsItem.Action) "ran" else "changed"
        if (problem == PENDING) {
            event("${who(s)} $did $what (still applying)")
            return HttpResponse.seeOther("$page?done=pending")
        }
        event(when {
            problem != null -> "${who(s)} couldn't ${if (item is SettingsItem.Action) "run" else "change"} $what: $problem"
            // A choice says what it is now: "Alex set Camera › Camera use to Presence + camera".
            item is SettingsItem.Choice -> "${who(s)} set $what to ${runCatching { item.options() }.getOrNull()?.firstOrNull { it.first == raw }?.second ?: raw}"
            else -> "${who(s)} $did $what"
        })
        if (problem == null) return HttpResponse.seeOther(if (SettingsChange.restarts(item)) "$page?done=restart" else page)
        // As the panel: a typed value, a duration, a choice or a window keeps its sheet open with why; the rest say it on the page.
        val dialog = if (item is SettingsItem.Switch || item is SettingsItem.Slider || item is SettingsItem.Action || (item is SettingsItem.Choice && (item.cards != null || item.swatch != null))) null
            else ManagePages.Dialog.Edit(key, problem, typed = raw.takeIf { item is SettingsItem.Text })
        // The link follows the page as it is now: the refusal came from the state the save saw, not the one posted against.
        val now = host.status()
        val see = (now.sections.firstOrNull { it.id == id }?.let { SettingsChange.find(it, key) } as? SettingsItem.Switch)?.see
        return settingsPage(now, viewer, page.removePrefix("/"), dialog, problem = if (dialog == null) problem else null, code = 422, problemSee = see, mine = s.id)!!
    }

    /**
     * The panel's code before an action that needs someone at the panel ([SettingsItem.Action.needsCode]): null once the
     * code typed is right, else the page to answer with. Without a typed code (or with a stale answer) the panel shows a
     * code for this browser and this action, and the dialog asks for it, as Reboot does.
     */
    private fun codeGate(req: HttpRequest, s: Session, viewer: ManagePages.Viewer, section: String, key: String, page: String,
                         item: SettingsItem.Action, confirmed: Boolean): HttpResponse? {
        val purpose = "$PURPOSE_ACTION:$section/$key"
        val what = ManageApi.what(host.status().sections.first { it.id == section }, key)
        fun dialog(problem: String?, status: Int) = html(status, actionCodeDialog(viewer, s, section, key, page, item, problem))
        val typed = req.form["code"]
        if (typed == null || !confirmed) return when (val shown = code.show(purpose, item.title, s.id)) {
            is PanelCode.Show.Ok -> dialog(null, 200)
            is PanelCode.Show.Locked -> dialog(lockedText(), 429)
            is PanelCode.Show.Busy -> { capped(req, shown); dialog(busyText(shown), 429) }
            PanelCode.Show.Unavailable -> {
                event("${who(s)}: $what refused, the panel can't show a code")
                settingsPage(host.status(), viewer, page.removePrefix("/"), problem = "$NO_CODE ${item.title} needs it.", code = 503, mine = s.id)!!
            }
        }
        return when (val c = code.check(purpose, s.id, typed)) {
            PanelCode.Check.Ok -> null
            is PanelCode.Check.Wrong -> {
                event("Wrong code typed for $what")
                dialog(if (c.triesLeft > 0) "That's not the code. ${c.triesLeft} ${if (c.triesLeft == 1) "try" else "tries"} left." else "That was the last try. Cancel and start again.", 401)
            }
            PanelCode.Check.NoCode -> dialog("That code has run out. Cancel and start again.", 401)
            is PanelCode.Check.Locked -> dialog(lockedText(), 429)
        }
    }

    /** The code dialog over [page] for [item]: its answer is a fresh one-shot token for this session and this action. */
    private fun actionCodeDialog(viewer: ManagePages.Viewer, s: Session, section: String, key: String, page: String,
                                 item: SettingsItem.Action, problem: String?): String {
        val run = SettingsItem.Action.RUN
        val behind = settingsPage(host.status(), viewer, page.removePrefix("/"), mine = s.id)?.body?.toString(Charsets.UTF_8).orEmpty()
        return pages.codeDialog(name(), "Enter the code shown on the panel", "${item.title} requires someone at the panel. Enter the 6-digit code shown on its screen.",
            "/settings", item.confirm?.action ?: item.label, page,
            mapOf("csrf" to viewer.csrf, "section" to section, "key" to key, "value" to run, "answer" to ask(s.id, section, key, run)), problem,
            // An action that asks first costs something (the reset): its button is rose, as Reboot's.
            danger = item.confirm != null,
            behindHtml = behind.substringAfter("<body>").substringBeforeLast("</body>"))
    }

    /**
     * A sign-in's dialog: a user name and a new password, or Clear. The password is passed on to the item's
     * save and nowhere else: not to the Log, not back into the page when it didn't save. No route reads a
     * stored password back (maintainer, 2026-11-15).
     */
    private fun changeSignIn(req: HttpRequest, s: Session, viewer: ManagePages.Viewer, page: String,
                             key: String, what: String, item: SettingsItem.SignIn): HttpResponse {
        val clear = req.form["clear"] == "1"
        val problem = onMain { done ->
            if (clear) SettingsChange.clear(item, done) else SettingsChange.signIn(item, req.form["user"].orEmpty(), req.form["password"].orEmpty(), done)
        }
        val did = if (clear) "cleared $what" else "set $what"
        if (problem == PENDING) { event("${who(s)} $did (still applying)"); return HttpResponse.seeOther("$page?done=pending") }
        event(if (problem == null) "${who(s)} $did" else "${who(s)} couldn't change $what: $problem")
        if (problem == null) return HttpResponse.seeOther(page)
        return settingsPage(host.status(), viewer, page.removePrefix("/"), ManagePages.Dialog.Edit(key, problem), code = 422)!!
    }

    /** [SettingsChange.change] on the panel's main thread; its answer, or [PENDING] when it takes longer than a page should wait. */
    private fun apply(item: SettingsItem, raw: String): String? = onMain { done -> SettingsChange.change(item, raw, done) }

    /** Runs [f] on the panel's main thread and waits for its answer, or [PENDING] after [saveWaitMs]. */
    private fun onMain(f: (done: (String?) -> Unit) -> Unit): String? {
        val answered = CountDownLatch(1)
        val answer = AtomicReference<String?>()
        host.onMain { f { why -> if (answered.count > 0) { answer.set(why); answered.countDown() } } }
        return if (answered.await(saveWaitMs, TimeUnit.MILLISECONDS)) answer.get() else PENDING
    }

    private fun posted(req: HttpRequest, s: Session, viewer: ManagePages.Viewer): HttpResponse {
        val a = host.actions
        fun run(name: String, what: String, f: () -> String?): HttpResponse {
            val problem = f()
            event(if (problem == null) "${who(s)}: $what" else "${who(s)}: $what failed: $problem")
            return HttpResponse.seeOther(if (problem == null) "/?done=$name" else "/?done=failed")
        }
        return when (req.path) {
            "/action/reload" -> run("reload", "reload the dashboard", a::reloadDashboard)
            "/action/restart" -> run("restart", "restart the app", a::restartApp)
            "/action/update" -> run("update", "check for updates", a::checkForUpdates)
            "/action/reboot" -> {
                if (!a.canReboot) return HttpResponse.seeOther("/")
                // Rebooting takes the radio's network down for everyone: someone must be at the panel.
                when (val shown = code.show(PURPOSE_REBOOT, "Reboot the panel", s.id)) {
                    is PanelCode.Show.Ok -> HttpResponse.seeOther("/confirm/reboot")
                    is PanelCode.Show.Locked -> html(429, rebootDialog(host.status(), viewer, lockedText()))
                    is PanelCode.Show.Busy -> { capped(req, shown); html(429, rebootDialog(host.status(), viewer, busyText(shown))) }
                    PanelCode.Show.Unavailable -> {
                        event("${who(s)}: reboot refused, the panel can't show a code")
                        HttpResponse.seeOther("/?done=nocode")
                    }
                }
            }
            "/confirm/reboot" -> when (val c = code.check(PURPOSE_REBOOT, s.id, req.form["code"].orEmpty())) {
                PanelCode.Check.Ok -> run("reboot", "reboot the panel", a::reboot)
                is PanelCode.Check.Wrong -> html(401, rebootDialog(host.status(), viewer,
                    if (c.triesLeft > 0) "That's not the code. ${c.triesLeft} ${if (c.triesLeft == 1) "try" else "tries"} left." else "That was the last try. Cancel and start again."))
                PanelCode.Check.NoCode -> html(401, rebootDialog(host.status(), viewer, "That code has run out. Cancel and start again."))
                is PanelCode.Check.Locked -> html(429, rebootDialog(host.status(), viewer, lockedText()))
            }
            "/settings" -> changeSetting(req, s, viewer)
            HA_ACCESS -> haAccess(s)
            HA_KEEP -> haKeep(req, s)
            HA_FORGET -> if (!haCalls.tryAcquire()) json(503, JSONObject().put("problem", BUSY))
            else when (try { endKept(s.id) } finally { haCalls.release() }) {
                Ended.DONE -> {
                    event("${who(s)} ended the Home Assistant sign-in All panels used (${s.browser})")
                    json(200, JSONObject().put("ok", true))
                }
                // Not revoked: the address moved, so the token didn't go to the old one. The page says how to remove it.
                Ended.LEFT -> json(200, JSONObject().put("ok", true).put("left", true))
                Ended.NOT_CONFIRMED -> json(502, JSONObject().put("problem", "Home Assistant didn't confirm it ended the sign-in. Try again."))
            }
            ManageUsbDebugging.ALLOW, ManageUsbDebugging.REMOVE ->
                usb?.post(req.path, req.form, who(s), homeAssistant = s.with == SignedInWith.HOME_ASSISTANT) ?: HttpResponse.text(404, "Not found.")
            "/security/signout" -> {
                val id = req.form["id"].orEmpty()
                val target = sessions.list(haBase()).firstOrNull { it.id == id }
                if (sessions.revoke(id) == SessionStore.Revoked.NOT_SAVED) return notSaved()
                endSession(id)
                target?.let { event("${who(s)} signed out ${it.browser}") }
                if (id == s.id) HttpResponse.seeOther("/", mapOf("Set-Cookie" to clearCookie(SESSION)))
                else HttpResponse.seeOther("/security?done=signedout")
            }
            else -> HttpResponse.text(404, "Not found.")
        }
    }

    /** The panel couldn't store a sign-out: it holds until the app restarts, and the person should know. */
    private fun notSaved(): HttpResponse {
        event("A sign-out couldn't be saved; it holds until the app restarts")
        return HttpResponse.text(500, "Signed out until the app restarts: the panel couldn't save it. Try again.")
    }

    private fun rebootDialog(status: PanelStatus, viewer: ManagePages.Viewer, problem: String?): String =
        pages.codeDialog(name(), "Enter the code shown on the panel", "Restarting requires someone at the panel. Enter the 6-digit code shown on its screen.",
            "/confirm/reboot", "Reboot the panel", "/", mapOf("csrf" to viewer.csrf), problem, danger = true,
            // The overview the reboot was asked from, dimmed behind the dialog.
            behindHtml = pages.overview(status, viewer, host.actions, null).substringAfter("<body>").substringBeforeLast("</body>"))

    private fun busyText(b: PanelCode.Show.Busy): String = when (b.why) {
        PanelCode.Why.SHOWING -> {
            val s = ((b.untilMs - System.currentTimeMillis() + 999) / 1000).coerceIn(1, 30)
            "The panel is showing another code. Try again in $s ${if (s == 1L) "second" else "seconds"}."
        }
        PanelCode.Why.TOO_MANY -> "The panel has shown too many codes in the last ${code.showWindowMs / 60_000} minutes. Try again after ${hhmm(b.untilMs)}."
    }

    /** The cap on new codes was hit: worth a line in the Log, with who asked. */
    private fun capped(req: HttpRequest, b: PanelCode.Show.Busy) {
        if (b.why == PanelCode.Why.TOO_MANY) event("Too many code requests: refused one from ${req.remote ?: "an unknown address"}")
    }

    private fun lockedText() = "Too many wrong codes. Try again after ${hhmm(code.lockedUntil() ?: 0)}."

    private fun diagnostics(): HttpResponse {
        val now = System.currentTimeMillis()
        val text = buildString {
            append("Glassworks Panel diagnostics, ").append(Date(now)).append("\n\n")
            append(host.diagnostics()).append("\n\nLog, the last 7 days, newest first:\n")
            for (e in log.read(limit = 5000)) append(pages.time(e.timeMs)).append(' ').append(e.level.name[0]).append(' ').append(e.source).append(": ").append(e.text).append('\n')
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ENGLISH).format(Date(now))
        return HttpResponse(200, mapOf(
            "Content-Type" to "text/plain; charset=utf-8", "Cache-Control" to "no-store",
            "Content-Disposition" to "attachment; filename=\"glassworks-diagnostics-$stamp.txt\"",
        ) + PageShell.securityHeaders(), Redact.text(text).toByteArray(Charsets.UTF_8))
    }

    private fun notice(done: String?): String? = OverviewAction.entries.firstOrNull { it.key == done }?.started ?: when (done) {
        "reboot" -> "The panel is restarting. This page will come back in about a minute."
        "signedout" -> "That browser is signed out."
        "failed" -> "That didn't work. The Log says why."
        "nocode" -> "$NO_CODE Rebooting needs it."
        else -> null
    }

    // ------------------------------------------------------------------ checks and cookies

    /** POSTs only: the form came from this page (its Origin is the panel's own address). */
    private fun sameOrigin(req: HttpRequest): Boolean = sameOrigin(req.headers)

    private fun csrfOk(req: HttpRequest, s: Session): Boolean {
        val sent = req.form["csrf"] ?: return false
        return MessageDigest.isEqual(sent.toByteArray(), s.csrf.toByteArray())
    }

    private inline fun post(req: HttpRequest, f: () -> HttpResponse): HttpResponse =
        if (req.method == "POST") f() else HttpResponse.seeOther("/")

    /** The browser's pre-sign-in id (binds an HA sign-in or a code to it), and the cookie to set if it's new. */
    private fun pre(req: HttpRequest): Pair<String, Map<String, String>> {
        req.cookies[PRE]?.takeIf { it.length in 20..64 }?.let { return it to emptyMap() }
        val b = ByteArray(24).also(random::nextBytes)
        val v = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        return v to mapOf("Set-Cookie" to preCookieValue(v))
    }

    private fun preCookie(req: HttpRequest): Map<String, String> = pre(req).second

    // Lax: HA's redirect back is a top-level GET from another site, and it must carry this cookie.
    private fun preCookieValue(v: String) = "$PRE=$v; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800"

    private fun sessionCookie(token: String, remember: Boolean) =
        "$SESSION=$token; Path=/; HttpOnly; SameSite=Strict" + if (remember) "; Max-Age=${30L * 24 * 3600}" else ""

    private fun clearCookie(name: String) = "$name=; Path=/; HttpOnly; SameSite=${if (name == SESSION) "Strict" else "Lax"}; Max-Age=0"

    private fun html(status: Int, html: String, formTargets: List<String> = emptyList(), extra: Map<String, String> = emptyMap()) =
        HttpResponse.html(status, html, PageShell.securityHeaders("'self'" + formTargets.joinToString("") { " $it" }) + extra)

    private fun who(s: Session) = s.user ?: "Code sign-in"

    private fun name() = host.name

    private fun hhmm(ms: Long) = SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date(ms))

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        const val SESSION = "gw_session"

        /** The query mark of the one reload a signed-out page from another site gets ([signedOut]). */
        const val RELOADED = "gw_reload"

        /** [req]'s path and query without the [RELOADED] mark. */
        fun withoutMark(req: HttpRequest): String {
            val q = req.query.filterKeys { it != RELOADED }
            return if (q.isEmpty()) req.path else req.path + "?" + q.entries.joinToString("&") { (k, v) -> dev.glassworks.web.HttpServer.enc(k) + "=" + dev.glassworks.web.HttpServer.enc(v) }
        }
        const val PRE = "gw_pre"

        /** How many sign-ins from another panel's page one address may start in [HANDOFF_WINDOW_MS]. */
        const val HANDOFFS_PER_WINDOW = 5
        const val HANDOFF_WINDOW_MS = 10_000L
        const val HANDOFF_PAUSE = "This address started several sign-ins in a short time. Try again in a few seconds."

        /** How long a settings change may take before the answer is "still applying"; All panels' writes wait longer (#466). */
        const val SAVE_WAIT_MS = 20_000L

        /** All panels' line when the panel's Home Assistant address is an IPv6 literal, which its CSP can't name. */
        const val IPV6_HA = "All panels can't use a Home Assistant address written as an IPv6 address: the page's security policy can't name one. " +
            "Use Home Assistant's name or IPv4 address in this panel's settings (Home Assistant & dashboard)."

        /** Every panel's Manage page is on this port; All panels finds the others by it (#466). */
        const val PORT = 8099

        /** All panels' scripts, served from the panel's assets (`manage/`). */
        const val SCRIPTS = "/manage/js/"
        val SCRIPT_FILES = setOf("panels.js", "panels-core.js", "panels-boot.js")

        /** All panels' fresh access token from this session's kept Home Assistant sign-in, and its end (#466). */
        const val HA_ACCESS = "/manage/ha/access"
        const val HA_FORGET = "/manage/ha/forget"
        const val HA_KEEP = "/manage/ha/keep"
        /** A handoff's own long-lived token: HA's lifespan in days, and how long this panel uses it (an hour less). */
        const val LONG_LIVED_DAYS = 1
        const val LONG_LIVED_USE_MS = 23L * 3600 * 1000
        const val MANAGE_ENDED = "Your sign-in to this Manage page has ended. Sign in again."
        const val ADDRESS_RELOAD = "This panel's Home Assistant address changed. Reload the page to go on."
        /** Revokes HA didn't take that one sweep tries again; the rest wait for the next. */
        const val RETRIES_PER_SWEEP = 4
        /** All panels' renewals at once; the web server has four workers. */
        const val MAX_HA_CALLS = 2
        const val BUSY = "The panel is busy. Try again in a moment."

        /** Where a page on another panel signs this browser in with its Home Assistant token (#466). */
        const val HANDOFF = "/manage/ha/token"

        /** [apply]'s answer while a change is still running. */
        private const val PENDING = "\u0000pending"
        const val PURPOSE_SIGN_IN = "signin"
        const val PURPOSE_REBOOT = "reboot"
        /** A settings action that needs someone at the panel; the code's purpose names its section and key. */
        const val PURPOSE_ACTION = "action"

        /** How long a question's answer stays good, and how many may wait at once. */
        private const val ASK_MS = 5 * 60_000L
        private const val MAX_ASKED = 64
        private const val NO_CODE = "The panel can't show a code right now."

        /**
         * True when the `Host` header names the panel itself: one of [own], with or without a port. Any other
         * name is refused, even one that resolves here (DNS rebinding) or a `.local` name someone else answers.
         */
        fun hostAllowed(host: String?, own: Set<String>): Boolean {
            val h = host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 255 } ?: return false
            val name = when {
                // `[address]` or `[address]:port`, nothing else after the bracket: the whole header reaches pages
                // (LanSetup's Content-Security-Policy names it), so `[fd00::40]evil` or `[fd00::40];x` is refused.
                h.startsWith("[") -> {
                    val close = h.indexOf(']')
                    if (close <= 1 || !portOrNothing(h.substring(close + 1))) return false
                    h.substring(1, close)
                }
                h.count { it == ':' } == 1 -> h.substringBefore(':').takeIf { portOrNothing(h.substring(h.indexOf(':'))) && it.isNotEmpty() } ?: return false
                h.contains(':') -> return false
                else -> h
            }
            return name in own
        }

        /** Empty, or `:` and a port of 1 to 5 ASCII digits in 1..65535. */
        private fun portOrNothing(rest: String): Boolean {
            if (rest.isEmpty()) return true
            if (rest[0] != ':') return false
            val digits = rest.substring(1)
            if (digits.isEmpty() || digits.length > 5 || digits.any { it !in '0'..'9' }) return false
            return digits.toInt() in 1..65535
        }

        /** The request came from a page on this panel: its `Origin` is the panel's own address (header names lowercase). */
        fun sameOrigin(headers: Map<String, String>): Boolean {
            val origin = headers["origin"]
            val hostHeader = headers["host"] ?: return false
            if (origin == null) return headers["sec-fetch-site"] == "same-origin"
            return origin.equals("http://$hostHeader", ignoreCase = true)
        }

        /** Where to go after signing in: a path on this panel, never another site. */
        fun safeNext(next: String?): String {
            val n = next?.trim().orEmpty()
            if (!n.startsWith("/") || n.startsWith("//") || n.startsWith("/\\") || n.startsWith("/manage/") || n.length > 300) return "/"
            if (n.any { it.isWhitespace() || it.code < 0x20 }) return "/"
            return n
        }

        fun origin(url: String): String? = try {
            val u = URI(url.trim())
            if (u.scheme == null || u.rawAuthority == null) null else u.scheme.lowercase() + "://" + u.rawAuthority.lowercase()
        } catch (_: Exception) { null }
    }
}
