package dev.glassworks.setup

import dev.glassworks.improv.ImprovState
import dev.glassworks.platform.HotspotFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import dev.glassworks.setup.WifiWays.Plan
import dev.glassworks.setup.WifiWays.Way.HOTSPOT
import dev.glassworks.setup.WifiWays.Way.IMPROV
import dev.glassworks.setup.WifiWays.Way.MANUAL

/** The Wi-Fi step leads with the best way the panel can do now, and offers the rest in order (#468). */
class WifiWaysTest {
    private val ready = HotspotPermission.Step.Ready
    private val offer = HotspotStep.card(SetupHotspot.View.Off(null, null), ready, null)
    private val wifiOff = HotspotStep.card(SetupHotspot.View.Off(null, HotspotFailure.WIFI_OFF), ready, null)

    private fun improv(running: Boolean = true, state: ImprovState? = ImprovState.AUTHORIZATION_REQUIRED, startError: String? = null) =
        WifiStep.improv(ImprovFacts(running = running, advertising = running, state = if (running) state else null, startError = startError))

    @Test
    fun `Bluetooth setup running leads, the hotspot, then typing it, follow`() {
        assertEquals(Plan(IMPROV, listOf(HOTSPOT, MANUAL)), WifiWays.plan(improv(), offer))
    }

    @Test
    fun `it keeps the lead in every running state, waiting, allowed, joining, failed`() {
        for (s in ImprovState.entries) assertEquals(s.name, IMPROV, WifiWays.plan(improv(state = s), offer).lead)
    }

    @Test
    fun `Bluetooth that couldn't start never leads, but stays a way to try again`() {
        assertEquals(Plan(HOTSPOT, listOf(IMPROV, MANUAL)), WifiWays.plan(improv(running = false, startError = "Bluetooth is off and could not be turned on"), offer))
    }

    @Test
    fun `Bluetooth switched off (Not now, or its window ended), the hotspot leads, and Use Bluetooth stays one tap away`() {
        assertEquals(Plan(HOTSPOT, listOf(IMPROV, MANUAL)), WifiWays.plan(improv(running = false), offer))
    }

    @Test
    fun `Bluetooth switched off and no hotspot to open, one tap on Use Bluetooth still beats typing it`() {
        assertEquals(Plan(IMPROV, listOf(MANUAL)), WifiWays.plan(improv(running = false), wifiOff))
        assertEquals(Plan(IMPROV, listOf(MANUAL)), WifiWays.plan(improv(running = false), null))
    }

    @Test
    fun `the hotspot counts while one tap opens it, Android's permission included`() {
        val explain = HotspotStep.card(SetupHotspot.View.Off(null, HotspotFailure.NO_PERMISSION),
            HotspotPermission.Step.Explain("android.permission.NEARBY_WIFI_DEVICES", "why"), null)
        assertEquals(HOTSPOT, WifiWays.plan(improv(running = false, startError = "off"), explain).lead)
        assertEquals("Allow keeps the hotspot's place: Bluetooth still leads", Plan(IMPROV, listOf(HOTSPOT, MANUAL)), WifiWays.plan(improv(), explain))
        val retry = HotspotStep.card(SetupHotspot.View.Off(null, HotspotFailure.TETHERING_ON), ready, null)
        assertEquals(HOTSPOT, WifiWays.plan(improv(running = false, startError = "off"), retry).lead)
    }

    @Test
    fun `on a first visit Android's permission shows before anyone picks the hotspot, and Bluetooth setup still leads`() {
        // Android 13+: never asked, so the card's button is Allow (HotspotPermission.step), with no choice made yet.
        val firstVisit = HotspotStep.card(SetupHotspot.View.Off(null, null),
            HotspotPermission.Step.Explain("android.permission.NEARBY_WIFI_DEVICES", "why"), null)
        assertEquals(HotspotStep.Action.ALLOW, firstVisit.action)
        assertEquals(Plan(IMPROV, listOf(HOTSPOT, MANUAL)), WifiWays.plan(improv(), firstVisit))
        assertEquals("Bluetooth switched off, the hotspot leads with its Allow", Plan(HOTSPOT, listOf(IMPROV, MANUAL)), WifiWays.plan(improv(running = false), firstVisit))
    }

    @Test
    fun `neither Bluetooth nor the hotspot, typing it on the panel leads, with Bluetooth's retry behind it`() {
        val noBt = improv(running = false, startError = "no Bluetooth adapter")
        assertEquals(Plan(MANUAL, listOf(IMPROV)), WifiWays.plan(noBt, wifiOff))
        val refused = HotspotStep.card(SetupHotspot.View.Off(null, HotspotFailure.NO_PERMISSION),
            HotspotPermission.Step.Blocked("android.permission.NEARBY_WIFI_DEVICES", "settings"), null)
        assertEquals(Plan(MANUAL, listOf(IMPROV)), WifiWays.plan(noBt, refused))
        assertEquals("an app without the hotspot", Plan(MANUAL, listOf(IMPROV)), WifiWays.plan(noBt, null))
    }

    @Test
    fun `with Wi-Fi off the hotspot can't open, so it isn't offered while Bluetooth leads`() {
        assertEquals(Plan(IMPROV, listOf(MANUAL)), WifiWays.plan(improv(), wifiOff))
    }
}
