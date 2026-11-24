/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.net

import dev.glassworks.net.WifiChange.Joined
import dev.glassworks.net.WifiChange.Last
import dev.glassworks.net.WifiChange.Network
import dev.glassworks.net.WifiChange.Refusal
import dev.glassworks.net.WifiChange.State
import dev.glassworks.net.WifiChange.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Changing Wi-Fi after setup (#660): a change stays only once the panel is on the new network with Home Assistant in
 * reach within 2 minutes; every other ending is back on the old network, which stays saved; a restart part way finishes
 * the change the same way. Illustrative network names only.
 */
class WifiChangeTest {
    private val home = Network("Brightside Home", 3)
    private var now = 0L
    private val timers = ArrayList<Pair<Long, () -> Unit>>()
    private val logs = ArrayList<String>()

    private inner class FakeRadio : WifiChange.Radio {
        var block: WifiChange.Block? = null
        var on: Network? = home
        val saved = hashSetOf(home.name)
        var savedReadable = true
        var joinDone: ((Joined) -> Unit)? = null
        val joins = ArrayList<String>()
        val passwords = ArrayList<String>()
        var cancels = 0
        var reach = false
        var reachAsks = 0
        val backs = ArrayList<Triple<Network, String, Boolean>>()
        var backOk = true
        var hotspotArms = 0

        override fun block() = block
        override fun current() = on
        override fun isSaved(name: String) = if (savedReadable) name in saved else null
        override fun join(name: String, password: String, done: (Joined) -> Unit) { joins += name; passwords += password; joinDone = done }
        override fun cancel() { cancels++ }
        override fun goBack(to: Network, leave: String, forget: Boolean, done: (Boolean) -> Unit) {
            backs += Triple(to, leave, forget)
            if (backOk) on = to
            done(backOk)
        }
        override fun reaches(done: (Boolean) -> Unit) { reachAsks++; done(reach) }
        override fun armHotspot() { hotspotArms++ }
    }

    private inner class FakeStore : WifiChange.Store {
        var saved: WifiChange.Pending? = null
        var writable = true
        override fun load() = saved
        override fun save(p: WifiChange.Pending?): Boolean { if (!writable) return false; saved = p; return true }
    }

    private val radio = FakeRadio()
    private val store = FakeStore()
    private fun change() = WifiChange(radio, store, clock = { now }, schedule = { ms, run ->
        val t = (now + ms) to run
        timers += t
        ({ timers.remove(t) })
    }, log = { logs += it })
    private val c = change()

    /** Runs the timers due by [ms] from now, in order. */
    private fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = timers.filter { it.first <= end }.minByOrNull { it.first } ?: break
            timers.remove(next)
            now = next.first
            next.second()
        }
        now = end
    }

    private fun startAndJoin(name: String = "Garden Shed", password: String = "correct horse"): Refusal? {
        val r = c.start(name, password)
        advance(2_000)
        return r
    }

    @Test fun aChangeIsAcceptedSavedThenJoinsAfterTheAnswerGetsOut() {
        assertNull(c.start("Garden Shed", "correct horse"))
        assertEquals(State.Joining("Garden Shed", home, untilMs = 120_000), c.state)
        assertEquals("saved before leaving, never the password", WifiChange.Pending("Garden Shed", home, forget = true), store.saved)
        assertTrue("not yet: the page's answer goes out first", radio.joins.isEmpty())
        advance(2_000)
        assertEquals(listOf("Garden Shed"), radio.joins)
        assertEquals(listOf("correct horse"), radio.passwords)
        assertEquals("the setup hotspot is armed as for any new details", 1, radio.hotspotArms)
    }

    @Test fun joinedWithHomeAssistantInReachStays() {
        startAndJoin()
        radio.on = Network("Garden Shed", 7)
        radio.reach = true
        radio.joinDone!!(Joined.Ok)
        assertEquals(State.Idle(Last.Joined("Garden Shed")), c.state)
        assertNull("nothing left to finish after a restart", store.saved)
        assertTrue("no way back taken", radio.backs.isEmpty())
        advance(10 * 60_000)
        assertEquals("the deadline is gone", State.Idle(Last.Joined("Garden Shed")), c.state)
    }

    @Test fun aFailedJoinIsBackOnTheOldNetworkAndSaysWhy() {
        startAndJoin()
        radio.joinDone!!(Joined.Failed(Why.PASSWORD))
        assertEquals(listOf(Triple(home, "Garden Shed", true)), radio.backs)
        assertEquals(State.Idle(Last.Back("Garden Shed", home.name, Why.PASSWORD)), c.state)
        assertNull(store.saved)
    }

    @Test fun aNetworkSavedBeforeStaysSavedOnTheWayBack() {
        radio.saved += "Garden Shed"
        startAndJoin()
        radio.joinDone!!(Joined.Failed(Why.NO_CONNECTION))
        assertFalse("not forgotten: it was the person's before", radio.backs.single().third)
    }

    @Test fun homeAssistantOutOfReachGoesBackAtTheDeadline() {
        startAndJoin()
        radio.on = Network("Guest Wi-Fi", 9)
        radio.reach = false
        radio.joinDone!!(Joined.Ok)
        assertTrue((c.state as State.Joining).joined)
        advance(60_000)
        assertTrue("asked again every few seconds", radio.reachAsks > 5)
        assertTrue("still trying at 1 minute", c.state is State.Joining)
        advance(60_000)
        assertEquals(State.Idle(Last.Back("Garden Shed", home.name, Why.NO_HOME_ASSISTANT)), c.state)
        assertEquals(0, radio.cancels)
    }

    @Test fun homeAssistantAnsweringLateStillCounts() {
        startAndJoin()
        radio.on = Network("Garden Shed", 7)
        radio.joinDone!!(Joined.Ok)
        advance(30_000)
        radio.reach = true
        advance(5_000)
        assertEquals(State.Idle(Last.Joined("Garden Shed")), c.state)
    }

    @Test fun anAnswerWhileNotOnTheNewNetworkDoesntKeepTheChange() {
        startAndJoin()
        radio.reach = true // Home Assistant answers, but over the old network the panel fell back to
        radio.joinDone!!(Joined.Ok)
        assertTrue("not kept", c.state is State.Joining)
        advance(120_000)
        assertEquals(State.Idle(Last.Back("Garden Shed", home.name, Why.NO_HOME_ASSISTANT)), c.state)
    }

    @Test fun savedNetworksThatCantBeReadRefuseRatherThanRiskForgettingOne() {
        radio.savedReadable = false
        assertEquals(Refusal.SavedUnknown, c.start("Garden Shed", "x"))
        assertNull(store.saved)
        assertTrue(radio.joins.isEmpty())
    }

    @Test fun closingStopsEveryTimerAndAnswerButKeepsTheChangeForTheNextStart() {
        startAndJoin()
        c.close()
        radio.joinDone!!(Joined.Failed(Why.PASSWORD))
        advance(10 * 60_000)
        assertTrue("no way back taken by an ended service", radio.backs.isEmpty())
        assertEquals(WifiChange.Pending("Garden Shed", home, forget = true), store.saved)
        assertTrue(timers.isEmpty())
    }

    @Test fun aJoinThatNeverAnswersTimesOutCancelsAndGoesBack() {
        startAndJoin()
        advance(118_000)
        assertEquals(State.Idle(Last.Back("Garden Shed", home.name, Why.TIMED_OUT)), c.state)
        assertEquals(1, radio.cancels)
        radio.joinDone!!(Joined.Failed(Why.TIMED_OUT)) // the cancelled join answers late
        assertEquals("a late answer changes nothing", 1, radio.backs.size)
    }

    @Test fun anOldNetworkThatDoesntComeBackArmsTheHotspotAndSaysSo() {
        startAndJoin()
        radio.backOk = false
        radio.joinDone!!(Joined.Failed(Why.NO_CONNECTION))
        assertEquals(State.Idle(Last.Stuck("Garden Shed", home.name, Why.NO_CONNECTION)), c.state)
        assertEquals("armed at the start and again now", 2, radio.hotspotArms)
    }

    @Test fun otherWifiDetailsSupersedeWithoutGoingBack() {
        startAndJoin()
        radio.joinDone!!(Joined.Superseded)
        assertEquals(State.Idle(null), c.state)
        assertTrue("Kiln's or Bluetooth's join isn't undone", radio.backs.isEmpty())
        assertNull(store.saved)
    }

    @Test fun refusalsChangeNothing() {
        radio.block = WifiChange.Block.SUGGESTIONS_ONLY
        assertEquals(Refusal.Blocked(WifiChange.Block.SUGGESTIONS_ONLY), c.start("Garden Shed", "x"))
        radio.block = null
        assertEquals(Refusal.Same, c.start(home.name, "x"))
        radio.on = null
        assertEquals("no network to come back to", Refusal.NotOnWifi, c.start("Garden Shed", "x"))
        radio.on = home
        store.writable = false
        assertEquals("without the saved change a restart would lose the way back", Refusal.NotSaved, c.start("Garden Shed", "x"))
        store.writable = true
        assertEquals(State.Idle(null), c.state)
        assertTrue(radio.joins.isEmpty() && timers.isEmpty() && store.saved == null)

        assertNull(c.start("Garden Shed", "x"))
        assertEquals("one change at a time", Refusal.Busy, c.start("Attic", "y"))
    }

    @Test fun aRestartOnTheNewNetworkWithHomeAssistantStays() {
        store.saved = WifiChange.Pending("Garden Shed", home, forget = true)
        radio.on = Network("Garden Shed", 7)
        radio.reach = true
        c.resume()
        assertEquals(State.Idle(Last.Joined("Garden Shed")), c.state)
        assertNull(store.saved)
    }

    @Test fun aRestartBackOnTheOldNetworkForgetsWhatTheChangeSaved() {
        store.saved = WifiChange.Pending("Garden Shed", home, forget = true)
        c.resume()
        assertEquals(listOf(Triple(home, "Garden Shed", true)), radio.backs)
        assertEquals(State.Idle(Last.Back("Garden Shed", home.name, Why.INTERRUPTED)), c.state)
    }

    @Test fun aRestartOfflineWaitsAMinuteThenGoesBack() {
        store.saved = WifiChange.Pending("Garden Shed", home, forget = false)
        radio.on = null
        c.resume()
        advance(55_000)
        assertTrue(c.state is State.Joining)
        advance(5_000)
        assertEquals(State.Idle(Last.Back("Garden Shed", home.name, Why.TIMED_OUT)), c.state)
        assertEquals(listOf(Triple(home, "Garden Shed", false)), radio.backs)
    }

    @Test fun nothingSavedNothingToResume() {
        c.resume()
        assertEquals(State.Idle(null), c.state)
        assertTrue(timers.isEmpty())
    }

    @Test fun noLogLineHasANetworkNameOrThePassword() {
        startAndJoin(password = "correct horse")
        radio.joinDone!!(Joined.Failed(Why.PASSWORD))
        startAndJoin(name = "Attic", password = "battery staple")
        radio.joinDone!!(Joined.Ok)
        advance(120_000)
        assertTrue(logs.isNotEmpty())
        for (l in logs) for (secret in listOf("Brightside", "Garden Shed", "Attic", "correct horse", "battery staple")) {
            assertFalse("$secret in: $l", l.contains(secret))
        }
        assertFalse("the saved change never prints a name", WifiChange.Pending("Garden Shed", home, true).toString().contains("Garden"))
    }
}
