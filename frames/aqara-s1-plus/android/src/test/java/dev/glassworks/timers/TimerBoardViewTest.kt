package dev.glassworks.timers

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.glassworks.TimerBoard
import dev.glassworks.TimerBoard.Entry
import dev.glassworks.TimerOverlay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** #691: with three or more timers the group is one board that lists them; fewer stay rings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class TimerBoardViewTest {

    @After fun tearDown() = TimerBoard.reset()

    private fun idle(ms: Long = 600) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun overlay(touch: Boolean = true): TimerOverlay {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        a.getSharedPreferences("timers", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val root = FrameLayout(a)
        val t = TimerOverlay(a).apply { this.touch = { touch } }
        root.addView(t, t.layoutParams())
        a.setContentView(root, ViewGroup.LayoutParams(-1, -1))
        layout(root)
        idle()
        return t
    }

    private fun layout(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1440, 720)
    }

    private fun <T : View> find(v: View, type: Class<T>): List<T> {
        val out = ArrayList<T>()
        fun walk(x: View) { if (type.isInstance(x)) out += type.cast(x)!!; if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(v)
        return out
    }

    private fun board(t: TimerOverlay) = find(t, BoardView::class.java).single()
    private fun shownRings(t: TimerOverlay) = find(t, RingView::class.java).filter { it.visibility == View.VISIBLE }

    private fun timers(n: Int) = (1..n).map { Entry("t$it", "Timer $it", 600 * it, 500 * it, true) }

    @Test fun twoTimersAreRingsThreeAreABoardAndBack() {
        val t = overlay()
        TimerBoard.update(timers(2)); t.refresh(); layout(t.rootView); idle()
        assertEquals(View.GONE, board(t).visibility)
        assertEquals(2, shownRings(t).size)

        TimerBoard.update(timers(3)); t.refresh(); layout(t.rootView); idle()
        assertEquals(View.VISIBLE, board(t).visibility)
        assertEquals(0, shownRings(t).size)
        assertEquals(3, board(t).timers.size)
        val dp = t.resources.displayMetrics.density
        assertEquals((BoardView.heightDp(3) * dp).toInt(), board(t).height)

        TimerBoard.update(timers(2)); t.refresh(); layout(t.rootView); idle()
        assertEquals(View.GONE, board(t).visibility)
        assertEquals(2, shownRings(t).size)
    }

    @Test fun manyTimersListFiveAndSayHowManyMore() {
        assertEquals(5, BoardView.rowsFor(7))
        assertTrue(BoardView.heightDp(7) > BoardView.heightDp(5))
        assertEquals(BoardView.heightDp(5) + BoardView.MORE_DP, BoardView.heightDp(7))
        assertEquals("+2 more", TimerTexts.boardMore(7, 5))
        assertEquals("", TimerTexts.boardMore(4, 4))
    }

    @Test fun hideOnTheBoardTucksTheTimersIntoTheEdge() {
        val t = overlay()
        TimerBoard.update(timers(4)); t.refresh(); layout(t.rootView); idle()
        val b = board(t)
        b.draw(Canvas(Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)))
        assertFalse("a touch screen offers Hide", b.hideBox.isEmpty)
        val dock = b.parent as View
        val x = b.left + b.hideBox.centerX(); val y = b.top + b.hideBox.centerY()
        val now = SystemClock.uptimeMillis()
        dock.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        dock.dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0))
        idle()
        assertEquals("hidden: the half orb at the edge shows", View.VISIBLE, t.getChildAt(3).visibility)
    }

    @Test fun withoutTouchTheBoardOffersNoHide() {
        val t = overlay(touch = false)
        TimerBoard.update(timers(3)); t.refresh(); layout(t.rootView); idle()
        val b = board(t)
        b.draw(Canvas(Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)))
        assertTrue(b.hideBox.isEmpty)
    }

    @Test fun theBoardsWords() {
        assertEquals("10 min", TimerTexts.short(600))
        assertEquals("1 h 30 min", TimerTexts.short(5400))
        assertEquals("45 s", TimerTexts.short(45))
        assertEquals("2 min 30 s", TimerTexts.short(150))
        assertEquals("", TimerTexts.short(0))
        assertEquals("Timers · 4", TimerTexts.boardTitle(4))
        val now = 1_000_000L
        val running = TimerBoard.Shown("p", "Pasta", now + 491_000, active = true, pausedLeft = 491, totalSeconds = 600)
        val paused = TimerBoard.Shown("o", "Oven", 0, active = false, pausedLeft = 1470, totalSeconds = 2700)
        assertEquals("10 min", TimerTexts.boardLine(running, now))
        assertEquals("8:11", TimerTexts.boardTime(running, now))
        assertEquals("45 min · 24:30 left", TimerTexts.boardLine(paused, now))
        assertEquals("Paused", TimerTexts.boardTime(paused, now))
        val unknown = paused.copy(totalSeconds = 0)
        assertEquals("24:30 left", TimerTexts.boardLine(unknown, now))
    }
}
