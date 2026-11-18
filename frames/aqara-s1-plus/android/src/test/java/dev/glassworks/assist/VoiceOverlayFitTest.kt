package dev.glassworks.assist

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import dev.glassworks.VoiceOverlay
import dev.glassworks.ui.Mockup
import java.time.Duration

/**
 * The Assist overlay's reply stays on the screen on every frame: the S1 (960x480 dp), a Pixel Tablet (2560x1600 px
 * at xhdpi) and the lululemon Mirror standing portrait (1080x1920 px at xhdpi), where a fixed 820 dp column ran the
 * reply off both edges (2026-11-18). The mock-ups draw each frame with a short, the photographed and a long reply:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*VoiceOverlayFitTest*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceOverlayFitTest {

    @Before fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        Mockup.interFromSources()
    }

    @After fun tearDown() = ShadowChoreographer.setPaused(false)

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private class Stage(val root: FrameLayout, val voice: VoiceOverlay, val w: Int, val h: Int)

    private fun stage(): Stage {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val m = a.resources.displayMetrics
        val root = FrameLayout(a).apply { setBackgroundColor(Color.rgb(16, 18, 22)) }
        val voice = VoiceOverlay(a).apply { onCancel = {} }
        root.addView(voice, FrameLayout.LayoutParams(-1, -1))
        a.setContentView(root, ViewGroup.LayoutParams(-1, -1))
        return Stage(root, voice, m.widthPixels, m.heightPixels)
    }

    private fun Stage.reply(heard: String, reply: String) {
        voice.update(VoiceUi("speaking", heard = heard, reply = reply, seq = 1), enabled = true)
        repeat(3) { Mockup.layout(root, w, h); idle(400) }
    }

    /** The reply's lines, as drawn, lie inside the screen, below the transcript and above the cancel hint. */
    private fun Stage.assertFits(heard: String, reply: String) {
        reply(heard, reply)
        val main = Mockup.label(root, reply)
        val quote = Mockup.label(root, VoiceTexts.quote(heard))
        val hint = Mockup.label(root, "Tap anywhere to cancel")
        val layout = main.layout
        val shown = minOf(layout.lineCount, main.maxLines)
        val where = "${w}x$h px, \"$reply\""
        for (i in 0 until shown) {
            val left = main.left + layout.getLineLeft(i)
            val right = main.left + layout.getLineRight(i)
            assertTrue("$where: line $i spans $left..$right", left >= 0f && right <= w)
        }
        val textBottom = main.top + layout.getLineBottom(shown - 1)
        assertTrue("$where: the transcript ends at ${quote.bottom}, the reply starts at ${main.top}", quote.bottom <= main.top)
        assertTrue("$where: the reply ends at $textBottom, the hint starts at ${hint.top}", textBottom <= hint.top)
    }

    private fun assertAllFit() {
        val s = stage()
        for ((heard, reply) in REPLIES) s.assertFits(heard, reply)
    }

    @Test fun s1() = assertAllFit()

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun pixelTablet() = assertAllFit()

    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun mirror() = assertAllFit()

    private fun shoot(name: String) {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        for ((i, r) in REPLIES.withIndex()) {
            val s = stage()
            s.reply(r.first, r.second)
            Thread.sleep(2_000) // the orb's bake runs on a real thread
            idle(1_200)
            Mockup.save("assist-$name-${listOf("short", "photo", "long")[i]}", s.root, s.w, s.h)
        }
    }

    @Test fun s1Mockups() = shoot("s1")

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun pixelTabletMockups() = shoot("tablet")

    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun mirrorMockups() = shoot("mirror")

    private companion object {
        val REPLIES = listOf(
            "Is the front door locked?" to "Yes.",
            "How's it going?" to "I'm doing well, thanks. How can I help you today?",
            "What's going on at home?" to "The living room lights are off and the front door is locked. " +
                "The thermostat is set to 21 degrees, and the washing machine finishes in 25 minutes. " +
                "Tomorrow looks sunny, with a high of 18 degrees.",
        )
    }
}
