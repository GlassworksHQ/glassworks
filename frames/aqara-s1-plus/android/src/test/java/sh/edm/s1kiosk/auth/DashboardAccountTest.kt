package sh.edm.s1kiosk.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The "Dashboard sign-in" row's words: asked once per sign-in, and never about an earlier one (#299 verifier). */
class DashboardAccountTest {
    private var stored: StoredAuth? = StoredAuth("http://192.0.2.10:8123", "http://192.0.2.40:8099/", "refresh-1")
    private var now = 0L
    private val asks = ArrayList<Pair<String, (String?) -> Unit>>()
    private val account = DashboardAccount({ stored }, { base, done -> asks += base to done }, clock = { now })

    @Test fun noStoredSignInLeavesTheRowOut() {
        stored = null
        assertNull(account.shown())
        assertEquals(0, asks.size)
    }

    @Test fun asksOnceThenNamesTheUser() {
        assertEquals("Signed in", account.shown())
        assertEquals("Signed in", account.shown())
        assertEquals("one ask", 1, asks.size)
        assertEquals("http://192.0.2.10:8123", asks.single().first)
        asks.single().second("Alex")
        assertEquals("Signed in as Alex", account.shown())
    }

    @Test fun asksAgainAMinuteAfterAnAskWithNoName() {
        account.shown()
        asks.single().second(null)
        now += DashboardAccount.RETRY_MS
        account.shown()
        assertEquals("not yet", 1, asks.size)
        now += 1
        account.shown()
        assertEquals(2, asks.size)
    }

    @Test fun aLateAnswerAboutAnEarlierSignInIsDropped() {
        account.shown()
        val late = asks.single().second
        stored = StoredAuth("http://192.0.2.10:8123", "http://192.0.2.40:8099/", "refresh-2") // signed out, signed in again
        assertEquals("Signed in", account.shown())
        late("Alex") // the first sign-in's answer arrives now
        assertEquals("the earlier sign-in's name isn't shown", "Signed in", account.shown())
        asks.last().second("Sam")
        assertEquals("Signed in as Sam", account.shown())
    }

    @Test fun aNewSignInForgetsTheName() {
        account.shown()
        asks.single().second("Alex")
        stored = stored!!.copy(refreshToken = "refresh-2")
        assertEquals("Signed in", account.shown())
        assertEquals("asked about the new sign-in", 2, asks.size)
    }
}
