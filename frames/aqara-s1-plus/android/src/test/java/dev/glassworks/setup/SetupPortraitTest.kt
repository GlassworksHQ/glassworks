package dev.glassworks.setup

import dev.glassworks.s1.frame.TestFrames
import android.app.Application
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.glassworks.ui.Mockup

/**
 * The setup screen on a screen that stands portrait and takes no tap: the lululemon Mirror's 1080×1920 (#412). The
 * card stacks into one column, so the QR code shows whole and the title wraps between words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w540dp-h960dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement, so the title wraps as on a device
class SetupPortraitTest {
    @Before fun interFromSources() = Mockup.interFromSources()

    private val ip = "192.0.2.10"
    private val online = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi")
    private val offline = WifiSetupStatus.Snapshot(WifiSetupStatus.State.NOT_CONNECTED)

    private fun screen(step: String, wifi: WifiSetupStatus.Snapshot = online, panelIp: String? = ip): SetupOverlay {
        val a = Mockup.activity()
        val s = SetupOverlay(a, TestFrames.mirror(a))
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(SetupOverlay.Model(step = step, done = emptySet(), wifi = wifi, haUrl = "http://homeassistant.local:8123",
            haSource = "mdns", panelIp = panelIp, signedIn = false))
        Mockup.idle()
        Mockup.layout(s, 1080, 1920)
        return s
    }

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    /** The QR code's tile: the shown image with a bitmap, and the rounded white tile around it. */
    private fun qrTile(s: View): View =
        views(s).filterIsInstance<ImageView>().single { it.isShown && it.drawable is BitmapDrawable }.parent as View

    /** True when [v] lies inside each of its parents up to [root], so nothing clips it. */
    private fun whole(v: View, root: View): Boolean {
        var child = v
        while (child !== root) {
            val parent = child.parent as ViewGroup
            if (child.left < 0 || child.top < 0 || child.right > parent.width || child.bottom > parent.height) return false
            child = parent
        }
        return true
    }

    @Test fun theQrCodeShowsWholeAndFullSizeOnEveryStepThatHasOne() {
        for (step in listOf(SetupFlow.WIFI, SetupFlow.HA, SetupFlow.SIGNIN, SetupFlow.PANES)) {
            val s = screen(step, wifi = if (step == SetupFlow.WIFI) offline else online)
            val tile = qrTile(s)
            assertTrue("$step: the QR code is clipped", whole(tile, s))
            assertEquals("$step: the QR code keeps its size", Math.round(226 * s.resources.displayMetrics.density), tile.width)
            assertEquals(tile.width, tile.height)
        }
    }

    @Test fun theTitleWrapsBetweenWords() {
        val s = screen(SetupFlow.WIFI, wifi = offline, panelIp = null)
        val title = Mockup.label(s, "Waiting for Wi-Fi.")
        val text = title.text.toString()
        val layout = title.layout
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertTrue("broke inside a word: ${text.substring(0, end)}|${text.substring(end)}", text[end - 1] == ' ')
        }
        assertTrue("never ellipsized", (0 until layout.lineCount).none { layout.getEllipsisCount(it) > 0 })
    }

    @Test fun theTextColumnHasTheCardsWidth() {
        val s = screen(SetupFlow.HA)
        val body = views(s).filterIsInstance<TextView>().first { it.isShown && it.text.contains("http://$ip:8099/setup") }
        assertTrue("body ${body.width}px wide", body.width > 1080 / 2)
    }

    /** A tablet that turns: the same screen restacks on its next layout, both ways. */
    @Test fun aLiveRotationRestacksTheCard() {
        val s = screen(SetupFlow.HA)
        val body = views(s).filterIsInstance<TextView>().first { it.isShown && it.text.contains("http://$ip:8099/setup") }
        fun at(v: View) = IntArray(2).also(v::getLocationInWindow)
        fun qrUnderText() = at(qrTile(s))[1] >= at(body)[1] + body.height
        fun qrBesideText() = at(qrTile(s))[0] >= at(body)[0] + body.width
        assertTrue("portrait: the QR code under the text", qrUnderText())
        Mockup.layout(s, 1920, 1080)
        assertTrue("landscape: the QR code beside the text", qrBesideText())
        assertTrue("landscape: the QR code is clipped", whole(qrTile(s), s))
        Mockup.layout(s, 1080, 1920)
        assertTrue("portrait again: the QR code under the text", qrUnderText())
        assertTrue("portrait again: the QR code is clipped", whole(qrTile(s), s))
    }
}
