package dev.glassworks.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRedirectGuardTest {
    @Test
    fun breaksLoopAfterConsecutiveAuthorizeRedirects() {
        var now = 1_000L
        val guard = AuthRedirectGuard(maxRedirects = 2, windowMs = 30_000L, clock = { now })

        assertTrue(guard.onAuthorizeRedirect())
        assertFalse(guard.loopBroken)
        now += 1_000L
        assertTrue(guard.onAuthorizeRedirect())
        assertFalse(guard.loopBroken)
        now += 1_000L
        assertFalse("third bounce within window breaks the loop", guard.onAuthorizeRedirect())
        assertTrue(guard.loopBroken)
        assertFalse("stays broken while inside the window", guard.onAuthorizeRedirect())
    }

    @Test
    fun breaksImmediatelyAfterExternalAuthFailure() {
        var now = 1_000L
        val guard = AuthRedirectGuard(maxRedirects = 2, windowMs = 30_000L, clock = { now })

        guard.onExternalAuthFailed()
        assertFalse("first /auth/authorize after getExternalAuth failure does not bounce", guard.onAuthorizeRedirect())
        assertTrue(guard.loopBroken)
    }

    @Test
    fun resetClearsRedirectCountAndFailure() {
        var now = 1_000L
        val guard = AuthRedirectGuard(maxRedirects = 2, windowMs = 30_000L, clock = { now })

        guard.onExternalAuthFailed()
        assertFalse(guard.onAuthorizeRedirect())
        assertTrue(guard.loopBroken)

        guard.reset()
        assertFalse(guard.loopBroken)
        assertTrue(guard.onAuthorizeRedirect())
        assertTrue(guard.onAuthorizeRedirect())
        assertFalse(guard.onAuthorizeRedirect())
    }

    @Test
    fun windowExpiryAllowsFreshAttemptOnNewDashboardLoad() {
        var now = 1_000L
        val guard = AuthRedirectGuard(maxRedirects = 2, windowMs = 30_000L, clock = { now })

        assertTrue(guard.onAuthorizeRedirect())
        assertTrue(guard.onAuthorizeRedirect())
        assertFalse(guard.onAuthorizeRedirect())
        assertTrue(guard.loopBroken)

        // Still inside the window: a load does not clear the guard.
        now += 10_000L
        guard.onDashboardLoad()
        assertTrue(guard.loopBroken)
        assertFalse(guard.onAuthorizeRedirect())

        // After the window elapses: a new dashboard load gets a fresh window.
        now += 25_000L
        guard.onDashboardLoad()
        assertFalse(guard.loopBroken)
        assertTrue(guard.onAuthorizeRedirect())
    }

    @Test
    fun externalAuthFailureClearsAfterWindowElapsesOnNewDashboardLoad() {
        var now = 5_000L
        val guard = AuthRedirectGuard(maxRedirects = 2, windowMs = 30_000L, clock = { now })

        guard.onExternalAuthFailed()
        assertFalse(guard.onAuthorizeRedirect())

        now += 5_000L
        guard.onDashboardLoad()
        assertFalse("still blocked inside window", guard.onAuthorizeRedirect())

        now += 30_000L
        guard.onDashboardLoad()
        assertFalse(guard.loopBroken)
        assertTrue("allowed after window elapses", guard.onAuthorizeRedirect())
    }
}
