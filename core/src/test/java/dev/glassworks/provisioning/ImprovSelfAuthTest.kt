/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.provisioning

import dev.glassworks.frame.Input
import dev.glassworks.improv.ImprovError
import dev.glassworks.improv.ImprovSession
import dev.glassworks.improv.ImprovState
import dev.glassworks.improv.ScannedNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #611: on a screen without touch (the Mirror) Improv authorizes itself for 5 minutes, only while the panel has no
 * network, and an approach opens it again. A screen that takes a tap keeps Allow as it was.
 */
class ImprovSelfAuthTest {
    private object Quiet : ImprovSession.Effects {
        override fun publishState(state: ImprovState) {}
        override fun publishError(error: ImprovError) {}
        override fun sendResult(packet: ByteArray) {}
        override fun identify() {}
        override fun startWifi(ssid: String, password: String) {}
        override fun scanNetworks(): List<ScannedNetwork> = emptyList()
        override fun deviceInfo(): List<String> = emptyList()
    }

    /** As ImprovController starts a session at [now]. */
    private fun started(input: Input, online: Boolean, now: Long = 0L) = ImprovSelfAuth.session(Quiet, input).also {
        it.start(requireAuthorization = !ImprovSelfAuth.atStart(input, online), now = now)
    }

    @Test fun opensOnlyWithNoTouchAndNoNetwork() {
        assertEquals("the Mirror, offline", ImprovState.AUTHORIZED, started(Input.NONE, online = false).state)
        assertEquals("the Mirror, online", ImprovState.AUTHORIZATION_REQUIRED, started(Input.NONE, online = true).state)
        assertEquals("a touchscreen, offline", ImprovState.AUTHORIZATION_REQUIRED, started(Input.TOUCH, online = false).state)
        assertEquals("a touchscreen, online", ImprovState.AUTHORIZATION_REQUIRED, started(Input.TOUCH, online = true).state)
    }

    @Test fun endsAfterFiveMinutes() {
        val s = started(Input.NONE, online = false, now = 1_000L)
        assertEquals(1_000L + 5 * 60_000L, s.authorizedUntil)
        s.tick(1_000L + 5 * 60_000L - 1)
        assertEquals("open until the last moment", ImprovState.AUTHORIZED, s.state)
        s.tick(1_000L + 5 * 60_000L)
        assertEquals("then back to asking", ImprovState.AUTHORIZATION_REQUIRED, s.state)
    }

    @Test fun anApproachOpensItAgainForFiveMinutes() {
        val s = started(Input.NONE, online = false)
        s.tick(5 * 60_000L)
        assertEquals(ImprovState.AUTHORIZATION_REQUIRED, s.state)
        val auth = ImprovSelfAuth.proximityAuth(Input.NONE, chosen = null)
        assertTrue("on by default without touch", auth)
        assertTrue(ImprovSelfAuth.onApproach(Input.NONE, online = false, proximityAuth = auth))
        s.authorize(6 * 60_000L)
        assertEquals(ImprovState.AUTHORIZED, s.state)
        assertEquals(11 * 60_000L, s.authorizedUntil)
        assertFalse("turned off with --ez improv_proximity_auth false",
            ImprovSelfAuth.onApproach(Input.NONE, online = false, ImprovSelfAuth.proximityAuth(Input.NONE, chosen = false)))
    }

    @Test fun anOnlinePanelNeverAuthorizesByItself() {
        assertFalse("at the start", ImprovSelfAuth.atStart(Input.NONE, online = true))
        assertFalse("on an approach", ImprovSelfAuth.onApproach(Input.NONE, online = true, proximityAuth = true))
        assertFalse("on an approach, set on by hand",
            ImprovSelfAuth.onApproach(Input.NONE, online = true, ImprovSelfAuth.proximityAuth(Input.NONE, chosen = true)))
    }

    @Test fun touchFramesAreUnchanged() {
        assertFalse("approach authorization stays off by default", ImprovSelfAuth.proximityAuth(Input.TOUCH, chosen = null))
        assertFalse(ImprovSelfAuth.onApproach(Input.TOUCH, online = false, ImprovSelfAuth.proximityAuth(Input.TOUCH, chosen = null)))
        assertTrue("set on, online too, as before",
            ImprovSelfAuth.onApproach(Input.TOUCH, online = true, ImprovSelfAuth.proximityAuth(Input.TOUCH, chosen = true)))
        val s = started(Input.TOUCH, online = false)
        s.authorize(0L)
        assertEquals("Allow still lasts 2 minutes", 2 * 60_000L, s.authorizedUntil)
        assertEquals("the hotspot's 3 minutes", 3 * 60_000L, ImprovSelfAuth.hotspotGraceMs(Input.TOUCH, improvAuto = true, bootGraceMs = 30_000L))
    }

    @Test fun withoutTouchTheHotspotWaitsForTheWindow() {
        assertEquals(30_000L + 5 * 60_000L, ImprovSelfAuth.hotspotGraceMs(Input.NONE, improvAuto = true, bootGraceMs = 30_000L))
        assertEquals("Improv doesn't start by itself: nothing to wait for",
            3 * 60_000L, ImprovSelfAuth.hotspotGraceMs(Input.NONE, improvAuto = false, bootGraceMs = 30_000L))
    }

    /**
     * ImprovController and KioskService need Bluetooth and a service to run, so a JVM test can't start them. This pins
     * that they decide through these rules, so the tests above cover what the panel does.
     */
    @Test fun theControllerAndTheServiceUseTheseRules() {
        val code = dev.glassworks.testing.RepoFiles.code(atLeast = 200)
        val controller = code.single { it.name == "ImprovController.kt" }.readText()
        for (rule in listOf("ImprovSelfAuth.session(", "ImprovSelfAuth.atStart(", "ImprovSelfAuth.onApproach(", "ImprovSelfAuth.proximityAuth(")) {
            assertTrue("ImprovController calls $rule", rule in controller)
        }
        assertFalse("no session that skips them", "ImprovSession(effects" in controller)
        val service = code.single { it.name == "KioskService.kt" }.readText()
        assertTrue("KioskService gives the hotspot its wait", "ImprovSelfAuth.hotspotGraceMs(" in service)
    }
}
