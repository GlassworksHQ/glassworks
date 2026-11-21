package dev.glassworks.timers

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.glassworks.KioskPrefs
import dev.glassworks.TimerBoard
import dev.glassworks.TimerBoard.Entry
import dev.glassworks.TimerOverlay
import dev.glassworks.ui.Mockup
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.io.File
import java.time.Duration

/**
 * #691: the timers in the overlays' theme, rendered from the real views (TimerOverlay, RingView, TimerDoneView) at
 * the S1's 1440x720 and the Mirror's 1080x1920, light and dark. Illustrative timers only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*TimerThemeMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimerThemeMockups {

    private val dir = System.getProperty("s1.mockupDir")

    @Before fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        android.animation.ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 0f)
        Mockup.interFromSources()
    }

    @After fun tearDown() {
        TimerBoard.reset()
        ShadowChoreographer.setPaused(false)
    }

    private fun idle(ms: Long = 800) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun bakeWait() = repeat(20) { Thread.sleep(150); idle(16) }

    private fun layout(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun save(name: String, v: View, w: Int, h: Int) {
        val scratch = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        repeat(3) { layout(v, w, h); v.draw(Canvas(scratch)); idle(300) }
        layout(v, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        File(File(dir!!).apply { mkdirs() }, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The timers over a stand-in dashboard, in [theme] (read when the overlay is attached, as in the app). */
    private fun scene(theme: String, w: Int, h: Int, dark: Boolean, touch: Boolean = true): Pair<FrameLayout, TimerOverlay> {
        KioskPrefs(RuntimeEnvironment.getApplication()).overlayTheme = theme
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        a.getSharedPreferences("timers", Context.MODE_PRIVATE).edit().clear().commit()
        val root = FrameLayout(a)
        root.addView(Dashboard(a, dark), FrameLayout.LayoutParams(-1, -1))
        val timers = TimerOverlay(a).apply { this.touch = { touch } }
        root.addView(timers, timers.layoutParams())
        a.setContentView(root, ViewGroup.LayoutParams(-1, -1))
        layout(root, w, h)
        idle()
        return root to timers
    }

    /** The board: three or more timers in one card. [touch] false is the Mirror (no "Hide"). */
    private fun boards(tag: String, w: Int, h: Int, touch: Boolean) {
        for ((theme, dark) in listOf("glassworks/dark" to true, "dusk/light" to false, "aurora/dark" to true, "dune/light" to false)) {
            val (root, timers) = scene(theme, w, h, dark, touch)
            TimerBoard.update(listOf(
                Entry("p", "Pasta", 600, 491, true), Entry("r", "Rice", 1200, 892, true),
                Entry("o", "Oven", 2700, 1470, false), Entry("e", "Eggs", 420, 7, true),
            ))
            timers.refresh(); idle(400)
            save("board-$tag-${theme.replace('/', '-')}", root, w, h)
            TimerBoard.reset()
        }
        val (root, timers) = scene("glassworks/dark", w, h, true, touch)
        TimerBoard.update((1..7).map { Entry("t$it", listOf("Pasta", "Rice", "Oven", "Eggs", "Tea", "Bread", "Laundry")[it - 1], 300 * it, 280 * it, true) })
        timers.refresh(); idle(400)
        save("board-$tag-seven", root, w, h)
        TimerBoard.reset()
    }

    /** The rings and the done screen. [touch] false is the Mirror. */
    private fun shots(tag: String, w: Int, h: Int, touch: Boolean) {
        for ((theme, dark) in listOf("glassworks/dark" to true, "dusk/light" to false, "aurora/dark" to true, "dune/light" to false)) {
            val name = theme.replace('/', '-')
            var (root, timers) = scene(theme, w, h, dark, touch)
            // Two timers stay rings (three make the board): running, and the last 10 seconds.
            TimerBoard.update(listOf(Entry("p", "Pasta", 600, 492, true), Entry("e", "Eggs", 420, 7, true)))
            timers.refresh(); idle(400)
            save("timers-$tag-$name-dock", root, w, h)
            // Done: Eggs finished, Pasta still running.
            val s = scene(theme, w, h, dark, touch); root = s.first; timers = s.second
            TimerBoard.update(listOf(Entry("p", "Pasta", 600, 491, true), Entry("e", "Eggs", 420, 0, true)))
            TimerBoard.finished("Eggs")
            TimerBoard.update(listOf(Entry("p", "Pasta", 600, 491, true)))
            timers.refresh(); bakeWait(); idle(1200)
            save("timers-$tag-$name-done", root, w, h)
            TimerBoard.dismissFinished(); TimerBoard.reset()
        }
    }

    @Test fun s1() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", dir != null)
        shots("s1", 1440, 720, touch = true)
        boards("s1", 1440, 720, touch = true)
    }

    /** The done screen on the Mirror (portrait, no touch) with long names: the texts fit, and the hint says how it ends. */
    @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    @Test fun mirrorDoneLongNames() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", dir != null)
        for ((theme, dark) in listOf("glassworks/dark" to true, "dusk/light" to false)) {
            val (root, timers) = scene(theme, 1080, 1920, dark, touch = false)
            TimerBoard.update(listOf(
                Entry("l", "Laundry in the basement washer", 2700, 0, true), Entry("p", "Pasta for the whole family", 600, 491, true),
                Entry("r", "Rice in the big pot", 1200, 890, true), Entry("t", "Tea", 300, 200, true),
            ))
            TimerBoard.finished("Laundry in the basement washer")
            TimerBoard.update(listOf(Entry("p", "Pasta for the whole family", 600, 491, true), Entry("r", "Rice in the big pot", 1200, 890, true), Entry("t", "Tea", 300, 200, true)))
            timers.refresh(); bakeWait(); idle(1200)
            save("done-mirror-long-${theme.replace('/', '-')}", root, 1080, 1920)
            TimerBoard.dismissFinished(); TimerBoard.reset()
        }
    }

    @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    @Test fun mirror() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", dir != null)
        shots("mirror", 1080, 1920, touch = false)
        boards("mirror", 1080, 1920, touch = false)
    }

    /** A stand-in dashboard in HA's dark or light look: cards and a few tiles, so the timers' glass shows. */
    private class Dashboard(context: Context, private val dark: Boolean) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            c.drawColor(if (dark) Color.rgb(16, 18, 22) else Color.rgb(238, 240, 244))
            val card = if (dark) Color.rgb(27, 29, 34) else Color.WHITE
            val cols = 3; val gap = 12f
            val cw = (width / d - gap * (cols + 1)) / cols
            for (i in 0 until 9) {
                val x = gap + (i % cols) * (cw + gap); val y = gap + (i / cols) * 150f
                p.color = if (i == 4) (if (dark) Color.rgb(74, 61, 26) else Color.rgb(254, 243, 199)) else card
                c.drawRoundRect(x * d, y * d, (x + cw) * d, (y + 138f) * d, 14 * d, 14 * d, p)
                p.color = if (dark) Color.rgb(200, 204, 210) else Color.rgb(60, 64, 72); p.textSize = 14 * d
                c.drawText("Card ${i + 1}", (x + 16) * d, (y + 30) * d, p)
            }
        }
    }
}
