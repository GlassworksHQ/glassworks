package dev.glassworks.auth

import dev.glassworks.SignInNotStored
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.glassworks.manage.ManagePage
import dev.glassworks.KioskPrefs
import dev.glassworks.manage.orWithout
import dev.glassworks.web.HttpResponse
import dev.glassworks.web.HttpServer
import dev.glassworks.glassworks
import dev.glassworks.frame.nodeName
import dev.glassworks.setup.SetupChannelServer
import dev.glassworks.manage.PanelHosts
import dev.glassworks.setup.LanSetup
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The panel's Home Assistant credentials: the refresh token from the QR sign-in (stored in the
 * private `auth` prefs, never logged) and a cached access token. Process-wide; the sign-in server
 * is started by KioskService, the GeckoView bridge in MainActivity asks for access tokens.
 */
object HaAuth {
    /** Result for the frontend's `externalAuthSetToken(success, data)` callback. */
    sealed class Access {
        data class Ok(val accessToken: String, val expiresIn: Long) : Access() {
            override fun toString() = "Ok(expiresIn=$expiresIn)"
        }
        data class Failed(val reason: String, val signedOut: Boolean) : Access()
    }

    /** The Manage log reads it as "Setup" (S1Manage.logSource). */
    private const val LAN_TAG = "Glassworks.SetupChannel"

    private lateinit var prefs: KioskPrefs
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "ha-auth").apply { isDaemon = true } }
    private val http = UrlConnectionPoster()
    private val tokens = HaTokenClient(http)
    private val cache = AccessTokenCache()
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var server: HttpServer? = null
    /**
     * The sign-in server's pending sign-ins ([signInStates]), made once per process and kept across server restarts, so
     * a factory reset drops the ones the live server holds ([forgetPendingSignIns]).
     */
    @Volatile private var signInStates: StateStore? = null
    /** The Manage page while the server runs: the settings menu's Overview shows and does what its Overview does. */
    var manage: ManagePage? = null
        private set

    /**
     * "Join Home Assistant's Thread network" (ThreadJoin): the panel's own token first, else a one-off
     * administrator sign-in through the sign-in server's `/thread`. The service sets [ThreadJoin.deliver].
     */
    val threadJoin: ThreadJoin = ThreadJoin(
        client = HaThreadClient { url -> WsHaSocket.connect(url) },
        tokens = tokens,
        haBase = { if (::prefs.isInitialized) prefs.haBase else null },
        panelToken = { done ->
            if (!signedIn) done(null)
            else accessToken(force = false) { a -> done((a as? Access.Ok)?.accessToken) }
        },
        tokenBase = { signedInBase },
        exec = exec,
    )

    /** The panel's screen shows the sign-in QR code: a sign-in from it may replace the stored one ([SignInServer]). */
    val signInArm = SignInArm()

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = KioskPrefs(context.applicationContext)
    }

    /**
     * Signed in to the dashboard's Home Assistant: what every screen means by "signed in" (the setup screen, the
     * phone's setup page, Manage, the settings menu, HA's *Signed in* sensor). False after Home Assistant moved
     * ([movedAway]), although the old sign-in is still stored (#467).
     */
    val signedIn: Boolean get() = stored != null

    /** The HA base URL the stored token belongs to, if a sign-in is stored (also after a move). */
    val signedInBase: String? get() = if (::prefs.isInitialized) prefs.auth?.haBase else null

    /** The stored sign-in, if it is for the dashboard's Home Assistant (see [signedIn]); its token is redacted from toString. */
    val stored: StoredAuth? get() = if (::prefs.isInitialized) prefs.auth?.takeIf { it.isFor(prefs.haBase) } else null

    /**
     * Signed in, but the dashboard's Home Assistant is at another address than the sign-in's ([StoredAuth.isFor]):
     * no token goes anywhere, and the setup screen asks for the sign-in again (#467).
     */
    val movedAway: Boolean get() = ::prefs.isInitialized && prefs.auth?.isFor(prefs.haBase) == false

    /** Listener gets the new signed-in state on the main thread. */
    fun addListener(l: (Boolean) -> Unit) { listeners += l }
    fun removeListener(l: (Boolean) -> Unit) { listeners -= l }

    /**
     * Asks [base]'s Home Assistant who the panel's stored sign-in is (`auth/current_user`) and answers
     * with the name, or null, on a thread of its own: the page's token refreshes don't wait for it.
     */
    fun askAccount(base: String, done: (String?) -> Unit) {
        accessToken(force = false) { a ->
            val token = (a as? Access.Ok)?.accessToken ?: return@accessToken done(null)
            Thread({
                done(HaGatewayClient(tokens) { url -> WsHaSocket.connect(url) }.currentUser(base, token)?.name)
            }, "ha-account").apply { isDaemon = true }.start()
        }
    }

    private fun notifyChanged() {
        val v = signedIn
        main.post { listeners.forEach { runCatching { it(v) } } }
    }

    /**
     * Starts the panel's web server on [OAuth.SIGNIN_PORT] (retries if the port is busy): the QR-code
     * sign-in pages ([SignInServer]) and, behind them, the app's Manage page ([dev.glassworks.Glassworks.managePage], #33).
     */
    @Synchronized
    fun startServer(context: Context) {
        init(context)
        if (server != null) return
        // One store for the process: a bind retry keeps the Manage page, and the sign-in server inside it, built with it.
        val states = signInStates ?: signInStates(prefs).also { signInStates = it }
        // A sign-in started while a .local Home Assistant had no address yet trades its code through the one found since.
        // Once signed in, only a Manage session or the panel's own screen may replace the sign-in (signedIn, signInArm).
        val signIn = SignInServer(OAuth.SIGNIN_PORT, { prefs.haBase }, tokens, ::onSignedIn, states, thread = threadJoin,
            reach = prefs::route, signedIn = { signedIn }, armed = signInArm::armed)
        // A Manage page that can't load (a class Android 6 refuses, #435) leaves the QR pages up, and the app with them.
        val m = manage ?: orWithout("The Manage page", { AuthLog.w(it) }) {
            val page = context.glassworks.managePage(context, signIn, tokens, prefs)
            try {
                page.startLog()
            } catch (e: Throwable) {
                // What did start stops, and a later start builds the page again rather than reuse this one.
                runCatching { page.stopLog() }
                throw e
            }
            page
        }?.also { manage = it }
        val frame = context.glassworks.frame
        // The ESPHome name, from the MAC PanelDevice saved (a panel that never announced itself has none yet).
        val ownHosts = PanelHosts.cached { PanelHosts.now(prefs.deviceMac.takeIf { it.isNotEmpty() }?.let(frame::nodeName)) { msg, e -> AuthLog.w("$msg: $e") } }
        val s = m?.http(OAuth.SIGNIN_PORT) ?: qrOnly(signIn, ownHosts)
        // Setup over the LAN (#34) shares the port: the setup channel's own loop, gated by pairing with the panel's code.
        val lan = LanSetup(
            serve = SetupChannelServer.lan(context, context.glassworks),
            code = context.glassworks.panelCode,
            ownHosts = ownHosts,
            // The setup hotspot's key shows only on the panel's screen, so anyone on it can see the screen (#197).
            pairingOpen = { prefs.lanPairingOpen || context.glassworks.setupHotspot?.isOn == true },
            event = { msg -> android.util.Log.i(LAN_TAG, msg) },
            onDone = { main.post { context.glassworks.setupHotspot?.cancel(); context.glassworks.settleDeviceSteps(context) } },
            // A phone on the setup hotspot: the screen then shows the setup page's address (#460).
            phoneJoin = dev.glassworks.setup.PhoneJoin(
                hotspot = { context.glassworks.setupHotspot },
                isClient = { dev.glassworks.platform.HotspotAddress.isClient(context, it) },
                post = { main.post(it) },
            ),
            // The page (kiln/apps/kiln/lan) is in the assets; its font is the app's own Inter.
            files = { name ->
                runCatching {
                    if (name == "inter.ttf") context.resources.openRawResource(dev.glassworks.core.R.font.inter).use { it.readBytes() }
                    else context.assets.open("setup/$name").use { it.readBytes() }
                }.getOrNull()
            },
        )
        s.front = lan::handle
        context.glassworks.lanSetup = lan
        try {
            s.start()
            server = s
        } catch (e: IOException) {
            AuthLog.w("Cannot listen on ${OAuth.SIGNIN_PORT}: ${e.message}; retrying in 10 s")
            // Cancelled by stopServer: a stopped service mustn't get its web server back (CodeRabbit on #534).
            val retry = Runnable { bindRetry = null; startServer(context) }
            bindRetry = retry
            main.postDelayed(retry, 10_000)
        }
    }

    /**
     * The sign-in server's pending sign-ins, saved in [prefs]: the app restarts when Home Assistant's address first goes
     * in, often while a sign-in is open at Home Assistant (Kiln's Home Assistant step), and it must still be known when
     * Home Assistant sends the person back to the new process.
     */
    internal fun signInStates(prefs: KioskPrefs) = StateStore(saved = object : SavedSignIns {
        override fun read() = prefs.pendingSignIns
        override fun write(text: String?) { prefs.pendingSignIns = text }
    })

    /**
     * Forgets every sign-in in progress (a factory reset): the running server's first, so none finishes before the app
     * restarts and nothing it saves later brings them back, then the saved copy. False when that couldn't be removed.
     */
    @Synchronized // with startServer, which makes the store: a reset never runs between its read of the saved copy and this
    fun forgetPendingSignIns(context: Context): Boolean {
        init(context)
        signInStates?.clear()
        return try { prefs.pendingSignIns = null; true } catch (e: SignInNotStored) { AuthLog.w(e.message ?: "pending sign-ins not removed"); false }
    }

    /** The pending retry when the port was busy, until it runs or [stopServer] cancels it. */
    private var bindRetry: Runnable? = null

    /**
     * The web server with only the QR-code pages, for when the Manage page can't load. It answers only to the panel's own
     * names, as the Manage page does (DNS rebinding), and no browser holds a Manage session here.
     */
    internal fun qrOnly(signIn: SignInServer, ownHosts: () -> Set<String>): HttpServer = HttpServer(OAuth.SIGNIN_PORT, { req ->
        if (!dev.glassworks.manage.ManageServer.hostAllowed(req.headers["host"], ownHosts())) HttpResponse.text(400, "Open this page with the panel's address.")
        else {
            val r = signIn.handle(SignInServer.Request(req.method, req.path, req.query, req.headers))
            HttpResponse(r.status, r.headers, r.body)
        }
    })

    @Synchronized
    fun stopServer() {
        bindRetry?.let(main::removeCallbacks)
        bindRetry = null
        server?.stop()
        server = null
        manage?.stop()
        manage = null
    }

    private fun onSignedIn(auth: StoredAuth, first: TokenResult.Ok) {
        prefs.auth = auth
        signInArm.disarm() // one sign-in per code on the screen: a second phone that scanned it doesn't replace this one
        cache.put(first.accessToken, first.expiresIn)
        AuthLog.i("Refresh token stored for ${auth.haBase}")
        notifyChanged()
    }

    /**
     * A sign-in went into the stored settings by another way than the QR code's: an installer carried it over a
     * reinstall (SecretHandover, #584), or Home Assistant's login page on the panel (#633). Forgets any cached token
     * and tells the screens, which showed the sign-in QR code until now. [how] starts the log line.
     */
    fun signInStored(how: String) {
        cache.clear()
        signInArm.disarm() // signed in another way: the code that was on the screen no longer replaces it
        AuthLog.i("$how${signedInBase?.let { " for $it" } ?: ""}")
        notifyChanged()
    }

    /**
     * Gets an access token (cached until 60 s before `expires_in`, or refreshed with
     * `grant_type=refresh_token`); [force] skips the cache. [done] runs on the auth thread.
     * An `invalid_grant` / `access_denied` reply (token revoked in HA) deletes the refresh token.
     */
    fun accessToken(force: Boolean, done: (Access) -> Unit) {
        exec.execute {
            val auth = prefs.auth
            if (auth == null) { done(Access.Failed("not signed in", signedOut = true)); return@execute }
            // Before the cache: a cached token must not go out either.
            if (!auth.isFor(prefs.haBase)) { done(Access.Failed("Home Assistant moved", signedOut = false)); return@execute }
            if (!force) cache.get()?.let { done(Access.Ok(it, cache.secondsLeft())); return@execute }
            when (val r = tokens.refresh(auth)) {
                is TokenResult.Ok -> {
                    cache.put(r.accessToken, r.expiresIn)
                    done(Access.Ok(r.accessToken, r.expiresIn))
                }
                is TokenResult.Error -> {
                    AuthLog.w("token refresh failed: ${r.code} (${r.httpStatus})")
                    if (r.isRevoked || (r.code == "invalid_request" && r.httpStatus == 400)) {
                        // invalid_request = client_id mismatch; the token can't be used either way.
                        clearLocal()
                        done(Access.Failed(r.code, signedOut = true))
                    } else {
                        done(Access.Failed(r.code, signedOut = false))
                    }
                }
            }
        }
    }

    /**
     * Revokes the refresh token at HA (best effort) and deletes it. [done] on the auth thread, with false
     * when the token couldn't be deleted (the key store is down: it stays, and signs in again once it's back).
     */
    fun signOut(reason: String, done: (signedOut: Boolean) -> Unit = {}) {
        exec.execute {
            val auth = prefs.auth
            when {
                auth == null -> Unit
                // Revoking sends the token: never to an address that isn't the sign-in's own.
                !auth.isFor(prefs.haBase) -> AuthLog.i("Signed out ($reason); Home Assistant moved, so no revoke sent")
                else -> AuthLog.i("Signed out ($reason); revoke ${if (tokens.revoke(auth)) "confirmed" else "not confirmed"}")
            }
            done(clearLocal())
        }
    }

    /** Deletes the stored sign-in; false when it couldn't be (the key store is down, so the sealed token stays). */
    private fun clearLocal(): Boolean {
        val had = prefs.hasAuth
        val cleared = try { prefs.auth = null; true } catch (e: SignInNotStored) { AuthLog.w("${e.message}; it stays until the key store is back"); false }
        cache.clear()
        if (had) notifyChanged()
        return cleared
    }
}
