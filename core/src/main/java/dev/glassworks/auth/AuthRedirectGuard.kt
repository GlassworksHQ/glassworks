package dev.glassworks.auth

/**
 * Breaks `/auth/authorize` → `external_auth=1` redirect loops while the panel holds a stored sign-in
 * (`HaAuth.signedIn == true`) but Home Assistant rejects auth without revoking the refresh token
 * (for example an HTTP 403 IP ban, a transient 5xx, or a WebSocket `auth_invalid`).
 *
 * Without this guard, `MainActivity.redirect()` bounces every `/auth/authorize` navigation back to
 * the dashboard URL with `?external_auth=1`, which restarts the page, cancels the loading overlay's
 * fallback timer, and traps the panel on "Connecting to Home Assistant" while hammering HA.
 */
class AuthRedirectGuard(
    private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var redirects = 0
    private var windowStart = 0L
    private var externalAuthFailed = false

    /** True once a redirect back to `external_auth=1` was refused to break a loop. */
    @Volatile var loopBroken: Boolean = false
        private set

    /**
     * Records a failed `getExternalAuth` on the current load: any `/auth/authorize` navigation
     * should be allowed through right away instead of bouncing back into external auth.
     */
    @Synchronized
    fun onExternalAuthFailed() {
        val now = clock()
        if (windowStart == 0L) windowStart = now
        externalAuthFailed = true
    }

    /**
     * Called when `redirect()` sees `/auth/authorize` while `HaAuth.signedIn` is true.
     * Returns `true` to intercept and redirect to the dashboard with `external_auth=1`,
     * or `false` to break the loop and let `/auth/authorize` load.
     */
    @Synchronized
    fun onAuthorizeRedirect(): Boolean {
        val now = clock()
        if (externalAuthFailed) {
            loopBroken = true
            return false
        }
        if (redirects > 0 && now - windowStart >= windowMs) {
            redirects = 0
            windowStart = 0L
            loopBroken = false
        }
        if (redirects >= maxRedirects) {
            loopBroken = true
            return false
        }
        if (redirects == 0) windowStart = now
        redirects++
        loopBroken = false
        return true
    }

    /** Clears all state after a dashboard page reports `ready` or a fresh sign-in/sign-out fires. */
    @Synchronized
    fun reset() {
        redirects = 0
        windowStart = 0L
        externalAuthFailed = false
        loopBroken = false
    }

    /**
     * Called when a new dashboard load starts (for example `loadDashboard()` or a manual reload).
     * Clears a previous failure or loop once [windowMs] has elapsed.
     */
    @Synchronized
    fun onDashboardLoad() {
        val now = clock()
        if (windowStart == 0L || now - windowStart >= windowMs) {
            redirects = 0
            windowStart = 0L
            externalAuthFailed = false
            loopBroken = false
        }
    }

    companion object {
        const val DEFAULT_MAX_REDIRECTS = 2
        const val DEFAULT_WINDOW_MS = 30_000L
    }
}
