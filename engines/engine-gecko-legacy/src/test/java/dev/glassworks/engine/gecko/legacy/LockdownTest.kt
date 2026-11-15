/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The legacy engine's guard (#198): its Gecko prefs, and what the page shows when the lockdown is slow to start. */
class LockdownTest {

    /**
     * Every pref the lockdown sets, pinned: dropping one (WebRTC, an early connection, Early Hints) is a hole that no
     * other test sees. A change here is a change to what a frozen engine may reach; update it on purpose.
     */
    @Test fun `the lockdown's prefs, all of them`() {
        assertEquals(
            mapOf(
                "dom.ipc.processCount" to 1,
                "dom.ipc.processPrelaunch.enabled" to false,
                "fission.autostart" to false,
                "media.peerconnection.enabled" to false,
                "media.peerconnection.ice.default_address_only" to true,
                "media.peerconnection.ice.no_host" to true,
                "network.dns.disablePrefetch" to true,
                "network.dns.disablePrefetchFromHTTPS" to true,
                "network.predictor.enabled" to false,
                "network.predictor.enable-prefetch" to false,
                "network.prefetch-next" to false,
                "network.http.speculative-parallel-limit" to 0,
                "network.early-hints.enabled" to false,
                "network.early-hints.preconnect.enabled" to false,
            ),
            Lockdown("test").prefs,
        )
    }

    /** The extension never connects: after [Lockdown.READY_TIMEOUT_MS] the page says so, and the load still waits. */
    @Test fun `a lockdown that doesn't start says so after the timeout, and nothing loads`() {
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val lockdown = Lockdown("test") { ms, task -> timers += ms to task }
        var loaded = 0
        val shown = mutableListOf<String>()

        lockdown.whenReady({ loaded++ }, { shown += it })
        lockdown.whenReady({ loaded++ }, { shown += it })
        assertEquals("one timer, however many loads wait", listOf(Lockdown.READY_TIMEOUT_MS), timers.map { it.first })
        assertTrue("nothing to say before the timeout", shown.isEmpty())

        timers.single().second()
        assertEquals(listOf(Lockdown.SLOW, Lockdown.SLOW), shown)
        assertEquals("the loads still wait for the lockdown", 0, loaded)

        var late = ""
        lockdown.whenReady({ loaded++ }, { late = it })
        assertEquals("a load after the timeout hears it at once", Lockdown.SLOW, late)
        assertEquals(0, loaded)
    }
}
