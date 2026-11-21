package dev.glassworks.timers

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import dev.glassworks.TimerBoard
import dev.glassworks.ui.Mockup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * The timer done screen on a portrait screen and one without touch (#691; the Mirror is both): its texts fit the
 * screen with the voice overlay's gutter, and where nothing can be tapped it says how it ends instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TimerDoneLayoutTest {

    // Paused: its frame loop runs only as the test idles past each frame (unpaused, it would never let go).
    @Before fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @After fun tearDown() {
        TimerBoard.reset()
        ShadowChoreographer.setPaused(false)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))

    /** The done screen at [w] by [h] px, a timer with a long name just finished. */
    private fun done(w: Int, h: Int, touch: Boolean = true): TimerDoneView {
        val root = Mockup.show(FrameLayout(Mockup.activity()))
        val v = TimerDoneView(root.context).apply { this.touch = touch }
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        TimerBoard.update(listOf(
            TimerBoard.Entry("l", "Laundry in the basement washer", 2700, 0, true),
            TimerBoard.Entry("p", "Pasta for the whole family", 600, 491, true),
            TimerBoard.Entry("r", "Rice in the big pot", 1200, 890, true),
            TimerBoard.Entry("t", "Tea", 300, 200, true),
        ))
        TimerBoard.finished("Laundry in the basement washer")
        TimerBoard.update(TimerBoard.timers.filter { it.id != "l" }.map { TimerBoard.Entry(it.id, it.name, it.totalSeconds, it.pausedLeft, it.active) })
        v.refresh()
        idle()
        Mockup.layout(root, w, h)
        idle()
        Mockup.layout(root, w, h)
        return v
    }

    private fun field(v: TimerDoneView, name: String): View =
        TimerDoneView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(v) as View

    private fun screenX(v: View): IntArray = IntArray(2).also { v.getLocationInWindow(it) }

    @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    @Test fun onAPortraitScreenTheTextsFitWithTheVoiceOverlaysGutter() {
        val v = done(1080, 1920)
        val dp = v.resources.displayMetrics.density
        val gutter = (TimerDoneView.GUTTER_DP * dp).toInt()
        for (name in listOf("label", "main", "sub", "restRow", "column")) {
            val t = field(v, name)
            val left = screenX(t)[0]
            assertTrue("$name starts inside the gutter: $left", left >= gutter - 1)
            assertTrue("$name ends inside the gutter: ${left + t.width}", left + t.width <= 1080 - gutter + 1)
        }
        // The long name is set to the column: every line of it fits there.
        val main = field(v, "main") as TextView
        val text = main.layout!!
        for (i in 0 until text.lineCount) assertTrue("line $i: ${text.getLineWidth(i)} in ${main.width}", text.getLineWidth(i) <= main.width + 1)
    }

    @Config(qualifiers = "w960dp-h480dp-land-hdpi")
    @Test fun onTheS1TheColumnStays820Dp() {
        val v = done(1440, 720)
        val dp = v.resources.displayMetrics.density
        assertEquals((TimerDoneView.COLUMN_DP * dp).toInt(), field(v, "column").width)
    }

    @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    @Test fun withoutTouchItSaysItClosesByItselfAndTakesNoTaps() {
        val v = done(1080, 1920, touch = false)
        val hint = field(v, "hint") as TextView
        assertEquals(TimerDoneView.SELF_HINT, hint.text.toString())
        assertFalse(v.isClickable)
        // And it does close by itself, a minute after the last timer finished.
        assertTrue(TimerBoard.expire(TimerBoard.done.last().at + TimerBoard.DONE_HOLD_MS))
        v.refresh()
        assertFalse(v.showing)
    }

    @Config(qualifiers = "w960dp-h480dp-land-hdpi")
    @Test fun withTouchItSaysTapAnywhereToDismiss() {
        val v = done(1440, 720)
        assertEquals(TimerDoneView.TAP_HINT, (field(v, "hint") as TextView).text.toString())
        assertTrue(v.isClickable)
    }
}
