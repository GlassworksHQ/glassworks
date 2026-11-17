/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import android.Manifest
import dev.glassworks.platform.HotspotFailure
import dev.glassworks.platform.HotspotNetwork
import dev.glassworks.setup.HotspotStep.Action
import dev.glassworks.setup.HotspotStep.Card
import dev.glassworks.setup.HotspotStep.Tone
import dev.glassworks.setup.SetupHotspotPolicy.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Wi-Fi step shows for each state of the setup hotspot (#197). Made-up network values only. */
class HotspotStepTest {
    private val net = HotspotNetwork("Example-Setup", "abcdefghjkmn")
    private val ready = HotspotPermission.Step.Ready
    private val url = "http://192.0.2.1:8099/setup"
    private fun off(stopped: Stop? = null, error: HotspotFailure? = null) = SetupHotspot.View.Off(stopped, error)

    @Test
    fun `on - the join QR code, the setup address, the key, the minutes left and Close it`() {
        val c = HotspotStep.card(SetupHotspot.View.On(net, 14 * 60_000L + 1, asked = false), ready, url)
        assertEquals(net.qrText(), c.qr)
        assertEquals("Join the panel's Wi-Fi.", c.title)
        val body = c.body!!
        assertTrue(body, "\n$url\n" in body && "abcdefghjkmn" in body && "“Example-Setup”" in body)
        assertEquals("Wi-Fi · closes within 15 minutes", c.label)
        assertEquals(Action.STOP, c.action)
    }

    @Test
    fun `on, before the panel has its address - the text says it shows here`() {
        val c = HotspotStep.card(SetupHotspot.View.On(net, 60_000L, asked = true), ready, null)
        assertTrue(c.body!!, "the address that shows here" in c.body!!)
        assertEquals("Wi-Fi · closes within 1 minute", c.label)
    }

    @Test
    fun `starting - its own card at once, with the spinner and Close it, before the QR code`() {
        val c = HotspotStep.card(SetupHotspot.View.Starting, ready, null)
        assertEquals("Starting the setup Wi-Fi…", c.title)
        assertTrue("the spinner's line", c.waiting != null)
        assertNull("no QR code yet", c.qr)
        assertNull(c.status)
        assertEquals(Action.STOP, c.action)
    }

    @Test
    fun `on, once a phone reached the panel over it - the setup page's QR code, its address written out`() {
        val c = HotspotStep.card(SetupHotspot.View.On(net, 14 * 60_000L, asked = true, phone = true), ready, url)
        assertEquals(url, c.qr)
        assertEquals("Open the setup page.", c.title)
        assertTrue(c.body!!, "\n$url\n" in c.body!!)
        assertEquals("Wi-Fi · closes within 14 minutes", c.label)
        assertEquals(Action.STOP, c.action)
    }

    @Test
    fun `a phone on it before the panel has its address - still the join QR code, not an empty one`() {
        val c = HotspotStep.card(SetupHotspot.View.On(net, 60_000L, asked = true, phone = true), ready, null)
        assertEquals(net.qrText(), c.qr)
    }

    @Test
    fun `a start the person asked for that failed - the reason in the same card, with Try again`() {
        val c = HotspotStep.card(off(Stop.FAILED, HotspotFailure.TETHERING_ON), ready, null, asked = true)
        assertEquals(Card(title = "The setup Wi-Fi didn't start.", status = HotspotFailure.TETHERING_ON.text, tone = Tone.ROSE, action = Action.RETRY), c)
        val wifiOff = HotspotStep.card(off(error = HotspotFailure.WIFI_OFF), ready, null, asked = true)
        assertEquals("The setup Wi-Fi didn't start.", wifiOff.title)
        assertEquals(Action.WIFI_SETTINGS, wifiOff.action)
        val noReason = HotspotStep.card(off(Stop.FAILED, HotspotFailure.FAILED), ready, null, asked = true)
        assertEquals("the title says it already", "Android didn't say why.", noReason.status)
    }

    @Test
    fun `asked, Android's permission still comes first`() {
        val why = HotspotPermission.Step.Explain(Manifest.permission.NEARBY_WIFI_DEVICES, "the reason")
        assertEquals(Card(status = "the reason", tone = Tone.AMBER, action = Action.ALLOW),
            HotspotStep.card(off(error = HotspotFailure.NO_PERMISSION), why, null, asked = true))
    }

    @Test
    fun `off - the offer to open it`() {
        val c = HotspotStep.card(off(), ready, null)
        assertEquals(Action.OPEN, c.action)
        assertNull(c.qr)
        assertNull(c.status)
    }

    @Test
    fun `the reason for Android's permission comes before its dialog, then where to allow it`() {
        val why = HotspotPermission.Step.Explain(Manifest.permission.NEARBY_WIFI_DEVICES, "the reason")
        assertEquals(Card(status = "the reason", tone = Tone.AMBER, action = Action.ALLOW), HotspotStep.card(off(error = HotspotFailure.NO_PERMISSION), why, null))
        val blocked = HotspotPermission.Step.Blocked(Manifest.permission.NEARBY_WIFI_DEVICES, "allow it in settings")
        assertEquals(Card(status = "allow it in settings", tone = Tone.ROSE), HotspotStep.card(off(), blocked, null))
    }

    @Test
    fun `a failure says why, with Try again`() {
        val c = HotspotStep.card(off(Stop.FAILED, HotspotFailure.TETHERING_ON), ready, null)
        assertEquals(Card(status = HotspotFailure.TETHERING_ON.text, tone = Tone.ROSE, action = Action.RETRY), c)
    }

    @Test
    fun `after 15 minutes it says the panel tries your Wi-Fi again`() {
        val c = HotspotStep.card(off(Stop.TIMED_OUT), ready, null)
        assertTrue(c.status!!, "15 minutes" in c.status!!)
        assertEquals(Action.OPEN, c.action)
    }

    @Test
    fun `the radio off and locked - why, and Turn on Wi-Fi`() {
        assertEquals(Card(status = HotspotFailure.WIFI_OFF.text, tone = Tone.ROSE, action = Action.WIFI_SETTINGS),
            HotspotStep.card(off(error = HotspotFailure.WIFI_OFF), ready, null))
    }

    @Test
    fun `Android closed it - a line, and the offer again`() {
        val c = HotspotStep.card(off(Stop.ANDROID), ready, null)
        assertTrue(c.status!!, "Android closed" in c.status!!)
        assertEquals(Action.OPEN, c.action)
    }

    @Test
    fun `minutes round up and never say 0`() {
        assertEquals("1 minute", HotspotStep.minutes(0))
        assertEquals("1 minute", HotspotStep.minutes(60_000))
        assertEquals("2 minutes", HotspotStep.minutes(60_001))
        assertEquals("15 minutes", HotspotStep.minutes(15 * 60_000L))
    }
}
