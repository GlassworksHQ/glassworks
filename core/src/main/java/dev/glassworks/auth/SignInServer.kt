package dev.glassworks.auth

import dev.glassworks.SignInNotStored
import dev.glassworks.web.LocalName
import dev.glassworks.web.LocalRoute
import dev.glassworks.web.PageShell
import dev.glassworks.web.PageShell.Look
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Log facade: android.util.Log on the device, stdout in JVM tests. Never pass secrets. */
object AuthLog {
    private const val TAG = "Glassworks.Auth"
    private val onAndroid = System.getProperty("java.vendor")?.contains("Android") == true
    fun i(msg: String) = if (onAndroid) { android.util.Log.i(TAG, msg); Unit } else println("I/$TAG: $msg")
    fun w(msg: String, t: Throwable? = null) =
        if (onAndroid) { android.util.Log.w(TAG, msg, t); Unit } else println("W/$TAG: $msg ${t ?: ""}")
}

/**
 * The panel's sign-in web server (plain HTTP on the LAN, default port 8099). The phone scans the QR
 * code for `http://<panel-ip>:8099/signin` and:
 *
 * 1. `GET /signin` → `302` to `<HA>/auth/authorize?response_type=code&client_id=http://<host>/&
 *    redirect_uri=http://<host>/callback&state=<random>&code_challenge=<S256>&
 *    code_challenge_method=S256`, `<host>` being the `Host` header the phone
 *    used (so client_id and redirect_uri always share scheme + netloc, which is what HA's IndieAuth
 *    `verify_redirect_uri` accepts without fetching anything).
 * 2. The user signs in to HA on the phone; HA's frontend sends the phone to
 *    `/callback?code=…&state=…`.
 * 3. `GET /callback` → the state is checked (single use, 10 min), the code is exchanged at
 *    `<HA>/auth/token` with the same client_id and the PKCE `code_verifier`, and [onSignedIn] gets
 *    the refresh token. The phone sees "Panel signed in ✓".
 *
 * The setup page (`/setup`, #453) starts the same flow in its own tab with `[CONTINUE]?then=setup`. That
 * sign-in is bound to the browser with a cookie holding its state ([HANDOFF] and a slot), and its callback sends
 * the browser back to the setup page ([SETUP_RETURN]) instead of showing the done page. A callback for it
 * without that cookie is refused before the code is used. The QR code's own sign-in is not bound, as before.
 *
 * Once the panel is signed in ([signedIn]), `/signin`, [CONTINUE] and `/callback` no longer replace that sign-in for
 * anyone on the network: only a browser signed in to the Manage page ([Request.manage]) may. (The panel's screen shows
 * its sign-in QR code only while it isn't signed in.) Otherwise a phone on the LAN, or a page that sends a browser to
 * `/signin/continue` (login CSRF), could swap the panel's Home Assistant account for its own. A sign-in started before
 * the panel signed in another way doesn't replace it either. After a sign-out, a revoked token or a move of Home
 * Assistant ([HaAuth.movedAway]) the panel isn't signed in, and these pages work for anyone again.
 *
 * `/thread` and `/thread/callback` are the same flow for [ThreadJoin]: an administrator signs in once
 * so the panel can read HA's Thread dataset; the tokens are revoked right after. `/thread` only works
 * while the panel shows its QR code ([ThreadJoin.armed]).
 *
 * Pure JVM (tested in SignInServerTest against a fake HA).
 */
class SignInServer(
    private val port: Int,
    /** HA base URL (`http://host:8123`), or null if not known yet. */
    private val haBase: () -> String?,
    private val tokens: HaTokenClient,
    /** Called with the new credentials (on the connection thread). */
    private val onSignedIn: (StoredAuth, TokenResult.Ok) -> Unit,
    private val states: StateStore = StateStore(),
    /** "Join Home Assistant's Thread network" (`/thread`), or null to not serve it. */
    private val thread: ThreadJoin? = null,
    /**
     * The address to listen on, or null for every address (the panel: phones reach it on the LAN).
     * Tests pass the loopback address they connect to. On macOS a wildcard listener can get a port
     * another process holds on 127.0.0.1, and that process then answers 127.0.0.1 (#99).
     */
    private val bindAddress: InetAddress? = null,
    /**
     * How the panel reaches a pending sign-in's Home Assistant when it trades the code ([exchangeBase]): as started,
     * or through the address discovery found in place of a `.local` name this Android can't resolve
     * ([LocalName.route], the dashboard's own routing). The panel's [haBase] is read at `/signin`, and the address may
     * come only after it: Kiln's sign-in opens right after Home Assistant's `.local` name is set, before discovery.
     */
    private val reach: (String) -> LocalRoute = { LocalRoute.AsSet(it) },
    /** How long a code exchange waits for discovery to find a `.local` Home Assistant's address ([LocalRoute.NoAddressYet]). */
    private val addressWaitMs: Long = 5_000,
    /** The panel is signed in to the dashboard's Home Assistant ([HaAuth.signedIn]): a new sign-in would replace it. */
    private val signedIn: () -> Boolean = { false },
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "signin-http").apply { isDaemon = true } }
    private val ids = AtomicInteger()
    /**
     * Saving a sign-in is one step with deciding it may be saved: [callback] checks before it trades the code and the
     * trade takes a while, so two callbacks can both pass while the panel isn't signed in (first setup, two phones).
     * [stores] counts the sign-ins saved here; a callback that sees it moved during its trade saved over nothing yet,
     * and doesn't.
     */
    private val saveLock = Any()
    private var stores = 0

    val localPort: Int get() = serverSocket?.localPort ?: -1

    @Throws(IOException::class)
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, port))
        serverSocket = ss
        running = true
        Thread({
            while (running) {
                val s = try { ss.accept() } catch (e: IOException) { if (running) AuthLog.w("accept failed", e); break }
                pool.execute { serve(s) }
            }
        }, "signin-accept").apply { isDaemon = true; start() }
        AuthLog.i("Sign-in server listening on ${ss.localPort}")
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        pool.shutdownNow()
    }

    class Request(
        val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>,
        /** The browser holds a Manage session (ManageServer's own cookie, checked there): it may replace the sign-in. */
        val manage: Boolean = false,
    )

    class Response(val status: Int, val reason: String, val headers: Map<String, String>, val body: ByteArray)

    private fun serve(s: Socket) {
        val id = ids.incrementAndGet()
        s.use { sock ->
            try {
                sock.soTimeout = 15_000
                val req = readRequest(BufferedInputStream(sock.getInputStream())) ?: return
                val res = handle(req)
                AuthLog.i("[$id] ${req.method} ${req.path} -> ${res.status}")
                write(sock.getOutputStream(), res)
            } catch (e: IOException) {
                AuthLog.w("[$id] ${e.message}")
            }
        }
    }

    /** Routes one request. Public for tests. */
    fun handle(req: Request): Response {
        if (req.method != "GET" && req.method != "HEAD") return page(405, "Method Not Allowed", "Not allowed", "Only GET is supported.")
        return when (req.path) {
            "/", "", "/signin" -> if (mayReplace(req)) landing() else alreadySignedIn()
            // Whether this sign-in may replace a stored one is decided here, once: a sign-in saved by another
            // connection between this check and the pending state doesn't lend it that right.
            CONTINUE -> if (mayReplace(req)) signin(req, replaces = req.manage) else alreadySignedIn()
            "/callback" -> callback(req)
            ThreadJoin.PATH -> threadLanding()
            THREAD_CONTINUE -> threadStart(req)
            ThreadJoin.CALLBACK -> threadCallback(req)
            "/favicon.ico" -> Response(404, "Not Found", mapOf("Content-Type" to "text/plain"), ByteArray(0))
            else -> page(404, "Not Found", "Not found", "This panel only serves the sign-in pages.")
        }
    }

    /** Not signed in, or someone allowed to replace the panel's sign-in: a browser signed in to Manage. */
    private fun mayReplace(req: Request): Boolean = !signedIn() || req.manage

    /** The panel isn't signed in, or this sign-in was started from Manage, which may replace it. */
    private fun mayStore(pending: PendingSignIn): Boolean = !signedIn() || pending.replaces

    private fun alreadySignedIn() = page(
        409, "Conflict", "Already signed in",
        "This panel is already signed in to Home Assistant. To use another account, sign the panel out first, then scan the code it shows.",
        footHtml = MANAGE_FOOT,
    )

    /** What the QR code opens (#63): what signing in is for, and the one way on. */
    private fun landing(): Response {
        val ha = haBase()?.let(OAuth::trimBase) ?: return notFoundHa()
        return page(
            200, "OK", "Sign in to this panel", "This lets the panel show your Home Assistant dashboard.", Look.START,
            tile = haTile(ha), button = button("Continue to Home Assistant", CONTINUE),
            foot = "You sign in directly on Home Assistant. The panel never sees your password.",
        )
    }

    private fun notFoundHa() = page(
        503, "Service Unavailable", "Home Assistant not found yet",
        "Enter its address on the panel, like <b>homeassistant.local</b>, then scan the code again.",
    )

    private fun signin(req: Request, replaces: Boolean): Response {
        val ha = haBase()?.let(OAuth::trimBase) ?: return notFoundHa()
        val host = OAuth.sanitizeHost(req.headers["host"])
            ?: return page(400, "Bad Request", "Can't sign in", "Open this page with the panel's address (scan the QR code).")
        val clientId = OAuth.clientId(host)
        val redirect = OAuth.redirectUri(host)
        val then = Then.of(req.query["then"])
        val sign = states.create(clientId, redirect, ha, then = then, replaces = replaces)
        val location = OAuth.authorizeUrl(ha, clientId, redirect, sign.state, sign.codeChallenge)
        // Lax: HA's redirect back is a top-level GET from another site, and it must carry this cookie. Its slot is
        // one no other pending sign-in holds, so starting another (or a cross-site GET starting one) never replaces
        // it, and there are at most a few. It outlives the state (10 minutes), so a sign-in that ran out still shows
        // the way back to setup.
        if (then == Then.SETUP) check(sign.slot >= 0) { "a setup sign-in without a slot can't be bound" }
        val bind = if (then == Then.SETUP) mapOf("Set-Cookie" to "$HANDOFF${sign.slot}=${sign.state}; Path=/callback; HttpOnly; SameSite=Lax; Max-Age=1800") else emptyMap()
        return Response(
            302, "Found",
            mapOf("Location" to location, "Cache-Control" to "no-store", "Content-Type" to "text/plain; charset=utf-8") + bind,
            "Redirecting to Home Assistant".toByteArray(),
        )
    }

    /**
     * A binding cookie is left to run out (30 minutes) rather than deleted: its slot is free again once the state is
     * consumed, so a newer sign-in in the same browser may already hold that name, and a delete would take its binding.
     * A leftover binds nothing: it counts only when its value is the callback's own state, which works once.
     */
    private fun callback(req: Request): Response {
        val state = req.query["state"]?.takeIf { STATE.matches(it) }
        val bound = state != null && bindings(req).any { (_, v) -> MessageDigest.isEqual(v.toByteArray(), state.toByteArray()) }
        val pending = states.consume(state, PendingSignIn.PURPOSE_SIGNIN)
        return callback(req.query, pending, bound)
    }

    private fun callback(q: Map<String, String>, pending: PendingSignIn?, bound: Boolean): Response {
        // Every way out of here says where to start again. A sign-in the panel still knows says where it started; one
        // it doesn't (run out) has only the setup page's cookie to say so, and otherwise the page names every start.
        val then: Then? = pending?.then ?: if (bound) Then.SETUP else null
        val back = if (then == Then.SETUP) BACK_TO_SETUP else ""
        val again = when (then) {
            Then.QR -> "Scan the code to try again."
            Then.SETUP -> "Go back to setup and sign in again."
            Then.KILN -> "Close this window and sign in again from Kiln."
            null -> "Start the sign-in again where you began it."
        }
        q["error"]?.let { err ->
            return page(400, "Bad Request", "Sign-in canceled", "Home Assistant answered: ${esc(err)}. $again", button = back)
        }
        pending ?: return expired(then)
        // Signed in since this sign-in started, or started by no one allowed to replace it: the code is not used.
        if (!mayStore(pending)) return alreadySignedIn()
        val seen = synchronized(saveLock) { stores }
        // Another browser finishing the setup page's sign-in (login CSRF): refused, the code is not used. Usually an
        // in-app browser that handed HA's login to the default one: the way back starts it again there.
        if (then == Then.SETUP && !bound) return page(403, "Forbidden", "Can't sign in",
            "This sign-in was started in another browser. Start it again from the setup page, in this browser.", button = back)
        val code = q["code"]?.takeIf { it.isNotEmpty() }
            ?: return page(400, "Bad Request", "Can't sign in", "Home Assistant didn't finish sending the sign-in response. $again", button = back)
        val base = exchangeBase(pending.haBase)
        return when (val r = tokens.exchangeCode(base.url, pending.clientId, code, pending.codeVerifier)) {
            is TokenResult.Ok -> {
                val rt = r.refreshToken
                    ?: return page(502, "Bad Gateway", "Can't sign in", "Home Assistant didn't finish creating the sign-in session.", button = back)
                val auth = StoredAuth(base.url, pending.clientId, rt)
                val late = synchronized(saveLock) {
                    // Another sign-in was saved while this one traded its code, or the panel was signed in another way
                    // (or the code on its screen went): this one doesn't replace it.
                    if (stores != seen || !mayStore(pending)) true else {
                        try {
                            onSignedIn(auth, r)
                        } catch (t: Throwable) {
                            AuthLog.w("storing the token failed", t)
                            val why = (t as? SignInNotStored)?.let { "${it.message}: its key store isn't available. Try again in a minute." }
                            return page(500, "Internal Server Error", "Can't sign in", why ?: "The panel couldn't store the sign-in.", button = back)
                        }
                        stores++
                        false
                    }
                }
                if (late) {
                    AuthLog.i("A sign-in finished after another one was saved: not stored")
                    tokens.revoke(auth) // best effort: the session it made in Home Assistant isn't used
                    return alreadySignedIn()
                }
                AuthLog.i("Signed in to ${base.url} (refresh token stored)")
                if (then == Then.SETUP) return backToSetup()
                if (then == Then.KILN) return kilnDone()
                page(200, "OK", "Panel signed in", "Your dashboard is loading now. You can close this page.", Look.DONE,
                    tile = haTile(pending.haBase, dot = true), footHtml = MANAGE_FOOT)
            }
            is TokenResult.Error -> {
                AuthLog.w("code exchange failed: ${r.code} (${r.httpStatus})")
                when {
                    r.isInvalidCode -> expired(then)
                    r.httpStatus <= 0 && base is LocalRoute.NoAddressYet -> page(502, "Bad Gateway", "Can't reach Home Assistant",
                        "The panel hasn't found ${esc(base.name)} on the network yet. Wait a moment, then ${retry(pending.then)}.", button = back)
                    r.httpStatus <= 0 -> page(502, "Bad Gateway", "Can't reach Home Assistant",
                        "The panel couldn't reach ${esc(pending.haBase)}. Check the network and ${retry(pending.then)}.", button = back)
                    else -> page(502, "Bad Gateway", "Can't sign in",
                        "Home Assistant refused the sign-in (${esc(r.code)}). $again", button = back)
                }
            }
        }
    }

    /** What the Thread QR code opens (#63): who must sign in, and why it's safe. */
    private fun threadLanding(): Response {
        val join = thread ?: return page(404, "Not Found", "Not found", "This panel only serves the sign-in pages.")
        if (!join.armed()) return notWaiting()
        haBase() ?: return notFoundHa()
        return page(
            200, "OK", "Share your Thread network", "This lets the panel join as a Thread router.", Look.START,
            tile = infoTile("Sign in as a Home Assistant <b>administrator</b>. The panel copies the network settings once, then signs right back out."),
            button = button("Continue to Home Assistant", THREAD_CONTINUE),
            foot = "Your network key is never shown or logged.",
        )
    }

    private fun notWaiting() = page(409, "Conflict", "Not waiting for a sign-in",
        "Start “Get it from Home Assistant” on the panel first, then scan the code it shows.")

    private fun retry(then: Then) = when (then) {
        Then.QR -> "scan the code again"
        Then.SETUP -> "sign in again from setup"
        Then.KILN -> "close this window and sign in again from Kiln"
    }

    /** [then] null: the panel no longer knows where this sign-in started, so the page names every start. */
    private fun expired(then: Then? = Then.QR) = page(400, "Bad Request", "This link has expired",
        when (then) {
            Then.QR -> "Scan the code on the panel again. It shows a new one."
            Then.SETUP -> "Go back to setup and sign in again."
            Then.KILN -> "Close this window and sign in again from Kiln."
            null -> "Start again where you began: scan the code on the panel, or sign in again from the setup page or Kiln."
        },
        button = if (then == Then.SETUP) BACK_TO_SETUP else "", foot = "Codes last 10 minutes and work once.")

    /**
     * Signed in from Kiln (`then=kiln`): Kiln opened this window and moves on by itself when the panel reports the
     * sign-in, so the window closes. A browser that won't let a script close it shows the one line, and nothing
     * about other tabs. The CSP allows that one script by its hash.
     */
    private fun kilnDone(): Response {
        val r = page(200, "OK", "Signed in", "Kiln has moved on.<script>$CLOSE</script>", Look.DONE)
        return Response(r.status, r.reason, r.headers + ("Content-Security-Policy" to "${r.headers["Content-Security-Policy"]}; script-src '$CLOSE_HASH'"), r.body)
    }

    /**
     * Signed in from the setup page: back to it, at the step after Home Assistant. HA sent the browser here from
     * another site, so a redirect would still be cross-site and the setup page's Strict cookie wouldn't go with
     * it. A page that reloads to the setup page is same-site.
     */
    private fun backToSetup() = Response(
        200, "OK",
        mapOf("Content-Type" to "text/html; charset=utf-8", "Cache-Control" to "no-store") + PageShell.securityHeaders(formAction = "'none'"),
        PageShell.document(PageShell.PRODUCT_NAME, "", """<meta http-equiv="refresh" content="0;url=$SETUP_RETURN">""").toByteArray(Charsets.UTF_8),
    )

    private fun threadStart(req: Request): Response {
        val join = thread ?: return page(404, "Not Found", "Not found", "This panel only serves the sign-in pages.")
        if (!join.armed()) return notWaiting()
        val ha = haBase()?.let(OAuth::trimBase) ?: return notFoundHa()
        val host = OAuth.sanitizeHost(req.headers["host"])
            ?: return page(400, "Bad Request", "Can't sign in", "Open this page with the panel's address (scan the QR code).")
        val clientId = OAuth.clientId(host)
        val redirect = "http://$host${ThreadJoin.CALLBACK}"
        val sign = states.create(clientId, redirect, ha, PendingSignIn.PURPOSE_THREAD)
        return Response(
            302, "Found",
            mapOf("Location" to OAuth.authorizeUrl(ha, clientId, redirect, sign.state, sign.codeChallenge), "Cache-Control" to "no-store",
                "Content-Type" to "text/plain; charset=utf-8"),
            "Redirecting to Home Assistant".toByteArray(),
        )
    }

    private fun threadCallback(req: Request): Response {
        val join = thread ?: return page(404, "Not Found", "Not found", "This panel only serves the sign-in pages.")
        val q = req.query
        q["error"]?.let { err ->
            return page(400, "Bad Request", "Sign-in canceled", "Home Assistant answered: ${esc(err)}. Scan the code to try again.")
        }
        val pending = states.consume(q["state"], PendingSignIn.PURPOSE_THREAD)
            ?: return expired()
        // Closed on the panel, or timed out, since this sign-in started: don't join after all.
        if (!join.armed()) return page(409, "Conflict", "Not waiting for a sign-in",
            "The panel stopped waiting. Start “Get it from Home Assistant” on the panel again, then scan the new code.")
        val code = q["code"]?.takeIf { it.isNotEmpty() }
            ?: return page(400, "Bad Request", "Can't sign in", "Home Assistant didn't finish sending the sign-in response. Scan the code to try again.")
        val base = exchangeBase(pending.haBase)
        return when (val r = tokens.exchangeCode(base.url, pending.clientId, code, pending.codeVerifier)) {
            is TokenResult.Ok -> {
                val rt = r.refreshToken
                    ?: return page(502, "Bad Gateway", "Can't sign in", "Home Assistant didn't finish creating the sign-in session.")
                val o = join.onGrant(StoredAuth(base.url, pending.clientId, rt), r)
                if (o.ok) page(200, "OK", o.title, esc(o.text), Look.DONE, tile = o.network?.let { networkTile(it, o.channel) }.orEmpty(),
                    footHtml = MANAGE_FOOT)
                else page(502, "Bad Gateway", o.title, esc(o.text))
            }
            is TokenResult.Error -> {
                AuthLog.w("thread: code exchange failed: ${r.code} (${r.httpStatus})")
                if (r.isInvalidCode) expired()
                else page(502, "Bad Gateway", "Can't sign in",
                    "Home Assistant refused the sign-in (${esc(r.code)}). Scan the code to try again.")
            }
        }
    }

    /**
     * Where a sign-in started at [started] trades its code, and the base its token is stored for: [started], or the
     * address discovery found for its `.local` name ([reach]). That is the base the dashboard loads from
     * (KioskPrefs.haBase), so the stored sign-in is for it ([StoredAuth.isFor]) and its refreshes go the same way.
     * client_id and redirect_uri stay the panel's own: Home Assistant checks them against the authorize request, not
     * against the address it is reached at.
     *
     * While discovery hasn't found the address yet, this waits for it up to [addressWaitMs]; then the base as started,
     * which works if Android resolves the name after all ([LocalRoute.NoAddressYet], for the failure page to name).
     */
    private fun exchangeBase(started: String): LocalRoute {
        val until = System.nanoTime() + addressWaitMs * 1_000_000
        while (true) {
            when (val r = reach(started)) {
                is LocalRoute.ByAddress -> {
                    AuthLog.i("Reaching ${r.name} at ${r.address} for the sign-in")
                    return LocalRoute.ByAddress(OAuth.trimBase(r.url), r.name, r.address)
                }
                is LocalRoute.NoAddressYet -> if (System.nanoTime() >= until) {
                    AuthLog.w("No address for ${r.name} yet: trading the sign-in at the name")
                    return LocalRoute.NoAddressYet(started, r.name)
                }
                else -> return LocalRoute.AsSet(started)
            }
            try { Thread.sleep(ADDRESS_POLL_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return LocalRoute.AsSet(started) }
        }
    }

    // ------------------------------------------------------------------ HTTP plumbing

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (head.size() > 16 * 1024) throw IOException("request head too large")
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val target = try { URI(parts[1]) } catch (_: Exception) { return Request(parts[0], "?", emptyMap(), headers) }
        return Request(parts[0], target.rawPath ?: "/", OAuth.parseQuery(target.rawQuery), headers)
    }

    private fun write(out: OutputStream, r: Response) {
        val sb = StringBuilder("HTTP/1.1 ${r.status} ${r.reason}\r\n")
        for ((k, v) in r.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.write(r.body)
        out.flush()
    }

    companion object {
        /** How often [exchangeBase] looks for a `.local` Home Assistant's address while it waits. */
        private const val ADDRESS_POLL_MS = 200L

        /** The sign-in and Thread pages' "Continue to Home Assistant": the redirect to HA's sign-in. */
        const val CONTINUE = "/signin/continue"
        const val THREAD_CONTINUE = "/thread/continue"

        /** `[CONTINUE]?then=setup`: the setup page's sign-in, which comes back to it (#453). */
        const val THEN_SETUP = "setup"

        /** `[CONTINUE]?then=kiln`: Kiln's sign-in, in a window Kiln opened, which closes when it is done. */
        const val THEN_KILN = "kiln"

        const val CLOSE = "window.close()"
        val CLOSE_HASH = "sha256-" + java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(CLOSE.toByteArray()))

        /** Where a sign-in from the setup page ends: the page, which carries on after Home Assistant. */
        const val SETUP_RETURN = "/setup#signed-in"

        /** The cookies that bind a setup page's sign-in to its browser: this and its slot, holding the sign-in's state. */
        const val HANDOFF = "gw_signin_"

        /** What [OAuth.randomState] makes. Anything else is no state, and never reaches the store or a header. */
        private val STATE = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val BINDING = Regex("^$HANDOFF[0-9]{1,2}$")

        /** The binding cookies the browser sent, by name. */
        private fun bindings(req: Request): List<Pair<String, String>> =
            req.headers["cookie"]?.split(';').orEmpty().map { it.trim().split('=', limit = 2) }
                .filter { it.size == 2 && BINDING.matches(it[0]) }.map { it[0] to it[1] }

        private const val BACK_TO_SETUP = """<a class="btn" href="/setup">Back to setup</a>"""

        fun esc(s: String) = PageShell.esc(s)

        private fun host(base: String) = base.substringAfter("://").substringBefore("/").substringBefore(":")

        fun haTile(base: String, dot: Boolean = false) = """<div class="tile"><span class="ic"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><path d="M4 11.5 12 4.5l8 7V20h-5.5v-5.5h-5V20H4z"/></svg></span><div><small>Home Assistant</small>${esc(host(base))}</div>${if (dot) "<i class=\"ok\"></i>" else ""}</div>"""

        fun infoTile(html: String) = """<div class="tile info"><span class="ii">i</span><div class="txt">$html</div></div>"""

        fun networkTile(name: String, channel: Int?) =
            """<div class="tile"><i class="ok lead"></i><div><small>Thread network</small>${esc(name)}${channel?.let { " · channel $it" } ?: ""}</div></div>"""

        fun button(label: String, href: String) = """<a class="btn" href="$href">${esc(label)} <span aria-hidden="true">↗</span></a>"""

        /**
         * One QR-code page (#63), drawn by the shell every page on the panel shares ([PageShell]). [text]
         * is HTML (escape what comes from outside). Done pages have no button; errors say the one fix on the
         * panel. [footHtml] is HTML under the button (a done page's way on to the Manage page).
         */
        fun page(
            status: Int, reason: String, title: String, text: String, look: Look = Look.PROBLEM,
            tile: String = "", button: String = "", foot: String = "", footHtml: String = "",
        ): Response {
            val html = PageShell.qrPage(PageShell.PRODUCT_NAME, title, text, look, tile, button, foot, footHtml,
                headerRight = PageShell.HEADER_SLOT)
            return Response(status, reason, mapOf("Content-Type" to "text/html; charset=utf-8", "Cache-Control" to "no-store") +
                PageShell.securityHeaders(formAction = "'none'"), html.toByteArray(Charsets.UTF_8))
        }

        /** Under a done page: the Manage page is the next place to go. */
        const val MANAGE_FOOT = """<a href="/">Manage this panel</a>"""
    }
}
