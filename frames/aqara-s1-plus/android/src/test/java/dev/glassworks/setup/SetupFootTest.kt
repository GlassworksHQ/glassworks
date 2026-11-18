package dev.glassworks.setup

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import dev.glassworks.pane.PaneChoice
import dev.glassworks.pane.SetupHost
import dev.glassworks.pane.SetupScreen
import dev.glassworks.pane.SetupStep
import dev.glassworks.update.SilentUpdates
import dev.glassworks.update.SilentUpdatesStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.frame.TestFrames
import dev.glassworks.ui.Mockup
import dev.glassworks.ui.StackView

/**
 * The card's buttons are its foot (#422): with a QR code beside the text, Continue sits in the card's corner under the
 * code, where every other step has it, not at the text's edge in the middle of the card. And a seventh rail row on the
 * S1 takes the stack art's room rather than pushing the art past the card's foot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupFootTest {
    @Before fun interFromSources() = Mockup.interFromSources()

    /** A pane's step that never finishes and draws only a title: the second sub-step a seventh rail row needs. */
    private class Plain(override val id: String) : SetupStep {
        override val title = "Another"
        override val reason = "test"
        override fun done() = false
        override fun screen(context: Context, host: SetupHost) = object : SetupScreen {
            override fun bind(card: dev.glassworks.pane.SetupCard, entered: Boolean) = card.title("Another step.")
        }
    }

    private lateinit var root: View

    /** On the S1, at [step], with Silent updates (Android 9: a QR code and Continue) and [Plain] under Panes. */
    private fun screen(step: String): SetupOverlay {
        val a = Mockup.activity()
        val steps = listOf(SilentUpdatesStep({ SilentUpdates.OldAndroid("9") }, { false }, {}), Plain("another"))
        val pane = SetupPane(PaneChoice("Updates", "Silent updates", on = { true }, apply = { null }), steps)
        val s = SetupOverlay(a, TestFrames.s1(), listOf(pane))
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        val done = SetupFlow.ids(true, steps.map { it.id }).takeWhile { it != step }.toSet()
        s.render(SetupOverlay.Model(step = step, done = done, wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"),
            haUrl = "http://homeassistant.local:8123", haSource = "mdns", panelIp = "192.0.2.10", signedIn = true))
        Mockup.idle()
        Mockup.layout(s)
        root = s
        return s
    }

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    /** [v]'s place on the setup screen, not in the window (the window adds its decor above it). */
    private fun box(v: View): Rect {
        val at = IntArray(2).also(v::getLocationInWindow)
        val r = IntArray(2).also(root::getLocationInWindow)
        return Rect(at[0] - r[0], at[1] - r[1], at[0] - r[0] + v.width, at[1] - r[1] + v.height)
    }

    @Test fun continueSitsInTheCardsCornerUnderTheQrCode() {
        val s = screen(SilentUpdatesStep.ID)
        val qr = box(views(s).filterIsInstance<ImageView>().single { it.isShown && it.drawable is BitmapDrawable }.parent as View)
        val go = box(views(s).filterIsInstance<TextView>().single { it.isShown && it.text == "Continue" })
        assertTrue("Continue $go under the QR code $qr", go.top >= qr.bottom)
        assertTrue("Continue $go at the QR code's right edge $qr", Math.abs(go.right - qr.right) <= 12 * s.resources.displayMetrics.density)
    }

    @Test fun aSeventhRailRowTakesTheStackArtsRoom() {
        val s = screen("another")
        val dp = s.resources.displayMetrics.density
        // The rail and the card end 40 dp above the screen's foot (the setup screen's padding).
        val bottom = s.height - (40 * dp).toInt()
        val rows = views(s).filterIsInstance<TextView>().filter { it.isShown && it.text in setOf("Another", "Done") }.toList()
        assertEquals(2, rows.size)
        for (r in rows) assertTrue("${r.text} is cut off", box(r).bottom <= bottom)
        // The brand's small mark stays; the big art at the rail's foot goes rather than overlap the rows or be cut off.
        val lowest = rows.maxOf { box(it).bottom }
        for (a in views(s).filterIsInstance<StackView>().filter { it.isShown && it.height > 40 * dp }) {
            assertTrue("stack art ${box(a)} over the rows", box(a).top >= lowest)
            assertTrue("stack art ${box(a)} cut off", box(a).bottom <= bottom)
        }
    }

    /** This device's rows at a large font (#524): they scroll above the card's foot, and both buttons stay on the card. */
    @Test fun thisDevicesRowsScrollAndItsButtonsStayAtALargeFont() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.6f)
        val a = Mockup.activity()
        val step = ThisDeviceStep({ ThisDeviceStep.Grants(home = false, admin = false) }, { false }, {}, { true }, { true })
        val s = SetupOverlay(a, TestFrames.tablet(a), emptyList(), listOf(step))
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(SetupOverlay.Model(step = ThisDeviceStep.ID, done = setOf(SetupFlow.WIFI, SetupFlow.HA, SetupFlow.SIGNIN),
            wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"),
            haUrl = "http://homeassistant.local:8123", haSource = "mdns", panelIp = "192.0.2.10", signedIn = true))
        Mockup.idle()
        Mockup.layout(s)
        root = s
        val dp = s.resources.displayMetrics.density
        val bottom = s.height - (40 * dp).toInt()
        for (label in listOf(ThisDeviceStep.MAKE_HOME, "Not now")) {
            val b = box(views(s).filterIsInstance<TextView>().single { it.isShown && it.text == label })
            assertTrue("$label $b on the card", b.top >= 0 && b.bottom <= bottom)
        }
        val rows = views(s).filterIsInstance<android.widget.ScrollView>().single { it.isShown && it.getChildAt(0).height > 0 && it.parent !== s }
        assertTrue("the rows have room to show: ${rows.height}px", rows.height > 40 * dp)
        assertTrue("the rows scroll: ${rows.getChildAt(0).height} in ${rows.height}", rows.getChildAt(0).height > rows.height)
        // Scrolled to the end, the screen row's last line (how to undo it) is in view.
        rows.scrollTo(0, rows.getChildAt(0).height)
        Mockup.layout(s)
        val undo = views(rows).filterIsInstance<TextView>().single { it.text == ThisDeviceStep.SCREEN_OFF }
        val inRows = box(undo).bottom - box(rows).top
        assertTrue("the undo line ends in view: $inRows of ${rows.height}", rows.scrollY > 0 && inRows <= rows.height + 1)
    }

    /** A redraw of the same step (every couple of seconds) keeps the step's own view attached, so a drag isn't cut (#524). */
    @Test fun aRedrawOfTheSameStepKeepsItsViewAttached() {
        val a = Mockup.activity()
        val step = ThisDeviceStep({ ThisDeviceStep.Grants(home = false, admin = false) }, { false }, {}, { true }, { true })
        val s = SetupOverlay(a, TestFrames.tablet(a), emptyList(), listOf(step))
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        val m = SetupOverlay.Model(step = ThisDeviceStep.ID, done = setOf(SetupFlow.WIFI, SetupFlow.HA, SetupFlow.SIGNIN),
            wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"),
            haUrl = "http://homeassistant.local:8123", haSource = "mdns", panelIp = "192.0.2.10", signedIn = true)
        s.render(m)
        Mockup.idle()
        val rows = views(s).filterIsInstance<android.widget.ScrollView>().single { it.isShown && it.parent !== s }
        var detached = 0
        rows.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { detached++ }
        })
        repeat(3) { s.render(m); Mockup.idle() }
        assertEquals("never detached by a redraw", 0, detached)
        assertTrue(rows.isShown)
    }
}
