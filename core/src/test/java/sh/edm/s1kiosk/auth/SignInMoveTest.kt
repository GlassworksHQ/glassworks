package sh.edm.s1kiosk.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.SignInNotStored

/**
 * #467: DHCP moves Home Assistant, and discovery finds it at a new address. The sign-in follows only from https to
 * https where the same Home Assistant (its mDNS uuid) answers, and no token goes to an address before that is
 * confirmed. On http it never follows: the uuid is public, so anything on the LAN can claim it. RFC 5737 hosts.
 */
class SignInMoveTest {
    private val old = "https://192.0.2.20:8123"
    private val new = "https://192.0.2.21:8123"
    private val oldHttp = "http://192.0.2.20:8123"
    private val newHttp = "http://192.0.2.21:8123"
    private val ours = "0123456789abcdef0123456789abcdef"
    private val other = "fedcba9876543210fedcba9876543210"
    private val token = "made-up-refresh-token"

    private fun auth(base: String = old, uuid: String? = ours) = StoredAuth(base, "http://192.0.2.10:8099/", token, uuid)

    @Test fun theSameHomeAssistantAtANewAddressMovesTheSignIn() {
        assertEquals(SignInMove.Move(new), SignInMove.decide(auth(), "$new/", HaSeen(new, ours)))
    }

    @Test fun anotherOrUnknownHomeAssistantDoesNot() {
        assertEquals(SignInMove.Unconfirmed, SignInMove.decide(auth(), new, HaSeen(new, other)))
        assertEquals(SignInMove.Unconfirmed, SignInMove.decide(auth(), new, null))
        // The uuid was seen at another address than the dashboard's.
        assertEquals(SignInMove.Unconfirmed, SignInMove.decide(auth(), new, HaSeen("http://192.0.2.22:8123", ours)))
        // Never from https to http, whatever the uuid says.
        assertEquals(SignInMove.Unconfirmed, SignInMove.decide(auth(), newHttp, HaSeen(newHttp, ours)))
        // Another Home Assistant took the sign-in's own address.
        assertEquals(SignInMove.Unconfirmed, SignInMove.decide(auth(), old, HaSeen(old, other)))
    }

    @Test fun onHttpTheSameUuidNeverMovesTheSignIn() {
        // A device on the LAN can advertise Home Assistant's uuid with its own address: only a certificate proves the host.
        assertEquals(SignInMove.Unconfirmed, SignInMove.decide(auth(oldHttp), newHttp, HaSeen(newHttp, ours)))
        assertEquals(SignInMove.Unconfirmed, SignInMove.decide(auth(oldHttp), new, HaSeen(new, ours)))
        // Learning stores the uuid and sends nothing, so it stays on http.
        assertEquals(SignInMove.Learn(ours), SignInMove.decide(auth(oldHttp, uuid = null), oldHttp, HaSeen(oldHttp, ours)))
    }

    @Test fun atItsOwnAddressTheSignInStays() {
        assertEquals(SignInMove.Stay, SignInMove.decide(auth(), old, HaSeen(old, ours)))
        assertEquals(SignInMove.Stay, SignInMove.decide(auth(), old, null))
        assertEquals(SignInMove.Stay, SignInMove.decide(auth(), null, HaSeen(new, ours)))
    }

    @Test fun aSignInFromBeforeLearnsTheUuidOnlyAtItsOwnAddress() {
        assertEquals(SignInMove.Learn(ours), SignInMove.decide(auth(uuid = null), old, HaSeen(old, ours)))
        assertEquals(SignInMove.Stay, SignInMove.decide(auth(uuid = null), old, HaSeen(new, ours)))
        assertEquals(SignInMove.Unconfirmed, SignInMove.decide(auth(uuid = null), new, HaSeen(new, ours)))
    }

    /** The panel's prefs, its HTTP and its log, faked. */
    private inner class Panel(var stored: StoredAuth?, var base: String?, var seen: HaSeen?) {
        val posts = mutableListOf<String>()
        val log = mutableListOf<String>()
        var storeWorks = true
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String): Pair<Int, String> {
                posts += url
                return 200 to """{"access_token":"made-up-access","expires_in":1800,"token_type":"Bearer"}"""
            }
            override fun get(url: String, maxBytes: Int) = throw AssertionError("no GET expected")
        })
        val home = FollowedSignIn(
            load = { stored },
            save = { if (storeWorks) stored = it else throw SignInNotStored("The panel can't store the sign-in right now") },
            base = { base }, seen = { seen }, log = { log += it },
        )

        /** What HaAuth.accessToken does: it refreshes only with a sign-in that stays where it is confirmed. */
        fun refresh(): TokenResult? = home.now()?.takeIf { it.second == SignInMove.Stay }?.let { tokens.refresh(it.first) }
    }

    @Test fun noTokenGoesToTheNewAddressBeforeItsUuidIsSeenThenTheRefreshGoesThere() {
        val p = Panel(auth(), base = new, seen = null)
        assertNull(p.refresh())
        assertEquals(old, p.stored?.haBase)
        p.seen = HaSeen(new, other)
        assertNull(p.refresh())
        assertEquals(emptyList<String>(), p.posts)

        p.seen = HaSeen(new, ours)
        assertTrue(p.refresh() is TokenResult.Ok)
        assertEquals(listOf("$new/auth/token"), p.posts)
        assertEquals(auth(base = new), p.stored)
    }

    @Test fun onHttpNoTokenGoesToAnAddressThatClaimsTheUuid() {
        val p = Panel(auth(oldHttp), base = newHttp, seen = HaSeen(newHttp, ours))
        assertNull(p.refresh())
        assertEquals(emptyList<String>(), p.posts)
        assertEquals(auth(oldHttp), p.stored)
    }

    @Test fun aSignInFromBeforeLearnsItsUuidThenFollows() {
        val p = Panel(auth(uuid = null), base = new, seen = HaSeen(new, ours))
        assertNull("not known as ours yet", p.refresh())
        assertEquals(emptyList<String>(), p.posts)

        p.base = old; p.seen = HaSeen(old, ours)
        assertTrue(p.refresh() is TokenResult.Ok)
        assertEquals(ours, p.stored?.haUuid)

        p.base = new; p.seen = HaSeen(new, ours)
        assertTrue(p.refresh() is TokenResult.Ok)
        assertEquals(listOf("$old/auth/token", "$new/auth/token"), p.posts)
        assertEquals(auth(base = new), p.stored)
    }

    @Test fun aMoveTheKeyStoreCantTakeStaysUnconfirmed() {
        val p = Panel(auth(), base = new, seen = HaSeen(new, ours)).apply { storeWorks = false }
        assertEquals(SignInMove.Unconfirmed, p.home.now()?.second)
        assertEquals(auth(), p.stored)
        assertEquals(emptyList<String>(), p.posts)
    }

    @Test fun eachChangeIsLoggedOnceWithoutAddressesOrTokens() {
        val p = Panel(auth(), base = new, seen = null)
        repeat(3) { p.home.now() }
        p.seen = HaSeen(new, ours)
        repeat(3) { p.home.now() }
        assertEquals(2, p.log.size)
        assertTrue(p.log.toString(), p.log.none { "192.0.2" in it || token in it || ours in it })
        assertTrue(auth().toString(), ours !in auth().toString() && token !in auth().toString())
    }

    @Test fun aNewSignInKeepsTheUuidSeenAtItsAddress() {
        val p = Panel(null, base = old, seen = HaSeen(old, ours))
        assertEquals(ours, p.home.withUuid(auth(uuid = null)).haUuid)
        p.seen = HaSeen(new, ours)
        assertNull(p.home.withUuid(auth(uuid = null)).haUuid)
    }
}
