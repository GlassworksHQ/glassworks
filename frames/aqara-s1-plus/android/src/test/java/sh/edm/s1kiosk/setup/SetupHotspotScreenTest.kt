package sh.edm.s1kiosk.setup

import android.app.Application
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import dev.glassworks.frame.Input
import dev.glassworks.platform.HotspotNetwork
import dev.glassworks.setup.HotspotPermission
import dev.glassworks.setup.HotspotStep
import dev.glassworks.setup.SetupHotspot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.ui.Mockup

/**
 * The setup hotspot on the setup screen's Wi-Fi step (#197): while it is on, the card shows its join QR code and
 * Close it; while it is off, one button offers it. A screen without touch shows the QR code and takes no tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class SetupHotspotScreenTest {
    private val offline = WifiSetupStatus.Snapshot(WifiSetupStatus.State.NOT_CONNECTED)
    private val net = HotspotNetwork("Example-Setup", "abcdefghjkmn")
    private val on = HotspotStep.card(SetupHotspot.View.On(net, 14 * 60_000L, asked = true), HotspotPermission.Step.Ready, "http://192.0.2.1:8099/setup")
    private val offer = HotspotStep.card(SetupHotspot.View.Off(null, null), HotspotPermission.Step.Ready, null)

    private fun screen(card: HotspotStep.Card?, input: Input = Input.TOUCH, installer: InstallerWifi? = null): SetupOverlay {
        val a = Mockup.activity()
        val s = s1SetupOverlay(a).also { it.input = input }
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(SetupOverlay.Model(step = SetupFlow.WIFI, done = emptySet(), wifi = offline, haUrl = null, haSource = "",
            panelIp = null, signedIn = false, hotspot = card, installer = installer))
        Mockup.idle()
        Mockup.layout(s)
        return s
    }

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    private fun qrShown(s: View) = views(s).any { it is ImageView && it.isShown && it.drawable is BitmapDrawable }
    private fun texts(s: View) = views(s).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()
    private fun button(s: View, label: String) = views(s).filterIsInstance<TextView>().single { it.isShown && it.text.toString() == label }

    @Test fun whileOnTheCardShowsTheJoinQrCodeAndCloseIt() {
        val s = screen(on)
        val asked = mutableListOf<HotspotStep.Action>()
        s.onHotspotAction = { asked += it }
        assertTrue(qrShown(s))
        assertTrue(texts(s).toString(), "Join the panel's Wi-Fi." in texts(s))
        button(s, "Close it").performClick()
        assertEquals(listOf(HotspotStep.Action.STOP), asked)
    }

    @Test fun opening_thenOnLeavesNoOpeningLineUnderTheCard() {
        val s = screen(HotspotStep.card(SetupHotspot.View.Starting, HotspotPermission.Step.Ready, null))
        assertTrue("Looking for Wi-Fi networks, then opening the panel's own Wi-Fi…" in texts(s))
        s.render(s.model!!.copy(hotspot = on))
        Mockup.idle()
        Mockup.layout(s)
        assertTrue(texts(s).toString(), "Looking for Wi-Fi networks, then opening the panel's own Wi-Fi…" !in texts(s))
    }

    private val wifiOff = HotspotStep.card(SetupHotspot.View.Off(null, dev.glassworks.platform.HotspotFailure.WIFI_OFF), HotspotPermission.Step.Ready, null)

    @Test fun wifiOffSaysWhyAndTurnOnWifiOpensTheSettings() {
        val s = screen(wifiOff)
        val asked = mutableListOf<HotspotStep.Action>()
        s.onHotspotAction = { asked += it }
        assertTrue(texts(s).toString(), dev.glassworks.platform.HotspotFailure.WIFI_OFF.text in texts(s))
        assertTrue("no QR code", !qrShown(s))
        button(s, "Turn on Wi-Fi").performClick()
        assertEquals(listOf(HotspotStep.Action.WIFI_SETTINGS), asked)
    }

    @Test fun wifiOffWithoutTouchSaysWhyAndTakesNoTap() {
        val s = screen(wifiOff, Input.NONE)
        assertTrue(texts(s).toString(), dev.glassworks.platform.HotspotFailure.WIFI_OFF.text in texts(s))
        assertTrue(views(s).none { it.isShown && it is TextView && it.text.toString() == "Turn on Wi-Fi" })
    }

    @Test fun theHotspotsReasonGoesWithItsButtonOverAnOldInstallerFailure() {
        val explain = HotspotStep.card(SetupHotspot.View.Off(null, dev.glassworks.platform.HotspotFailure.NO_PERMISSION),
            HotspotPermission.Step.Explain(android.Manifest.permission.NEARBY_WIFI_DEVICES, "the reason for Nearby devices"), null)
        val s = screen(explain, installer = InstallerWifi("Example-WiFi", InstallerWifi.Phase.FAILED, "wrong password"))
        assertTrue(texts(s).toString(), "the reason for Nearby devices" in texts(s))
        assertTrue(texts(s).toString(), texts(s).none { it.startsWith("Couldn't join") })
        button(s, "Allow")
    }

    @Test fun whileOffOneButtonOffersIt() {
        val s = screen(offer)
        val asked = mutableListOf<HotspotStep.Action>()
        s.onHotspotAction = { asked += it }
        assertTrue("no QR code until it is on", !qrShown(s))
        button(s, "Set up from a phone").performClick()
        assertEquals(listOf(HotspotStep.Action.OPEN), asked)
    }

    @Test fun withoutTouchItShowsTheQrCodeAndTakesNoTap() {
        val s = screen(on, Input.NONE)
        assertTrue(qrShown(s))
        assertTrue("Join the panel's Wi-Fi." in texts(s))
        assertTrue("nothing takes a tap", views(s).none { it.isShown && it.hasOnClickListeners() && it is TextView && it.text.toString() == "Close it" })
    }

    @Test fun withoutAHotspotTheWifiStepIsAsBefore() {
        val s = screen(null)
        assertTrue(texts(s).none { it == "Set up from a phone" || it == "Join the panel's Wi-Fi." })
    }
}
