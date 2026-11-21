package dev.glassworks.assist

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.glassworks.EdgeGlowOverlay
import dev.glassworks.theme.Themes
import dev.glassworks.ui.Mockup
import org.junit.After
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
import java.time.Duration

/**
 * #691: Spark's edge glow, each state, in a theme, at the S1's 1440x720 and the Mirror's 1080x1920, over a plain
 * stand-in for a dashboard. Robolectric runs the animations to their end, so these are the states as they settle, not
 * the motion (the motion was measured on the panels, #694). Illustrative texts only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*GlowThemeMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlowThemeMockups {
    @Before fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        Mockup.interFromSources()
    }

    @After fun tearDown() { ShadowChoreographer.setPaused(false); VoiceLevels.mic = 0f; VoiceLevels.ttsPlaying = false }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Cards of a dashboard, light or dark, so the glow is seen over something. */
    private class Dashboard(context: Context, private val dark: Boolean) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            c.drawColor(if (dark) Color.rgb(17, 19, 24) else Color.rgb(242, 243, 245))
            p.color = if (dark) Color.rgb(28, 30, 36) else Color.WHITE
            val cols = if (width > height) 3 else 2
            val gw = (width / d - 12f * (cols + 1)) / cols
            var y = 56f
            while (y < height / d - 40) {
                for (i in 0 until cols) { val x = 12f + i * (gw + 12f); c.drawRoundRect(x * d, y * d, (x + gw) * d, (y + 150f) * d, 14 * d, 14 * d, p) }
                y += 162f
            }
        }
    }

    private fun shoot(frame: String) {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        for (id in listOf("glassworks/dark", "dusk/light", "aurora/dark")) {
            val pick = Themes.pick(id)
            for (state in listOf("listening", "thinking", "speaking", "error")) {
                val a = Robolectric.buildActivity(Activity::class.java).setup().get()
                val m = a.resources.displayMetrics
                val root = FrameLayout(a)
                root.addView(Dashboard(a, pick.palette.darkGround), FrameLayout.LayoutParams(-1, -1))
                val glow = EdgeGlowOverlay(a).apply { theme = { pick } }
                root.addView(glow, FrameLayout.LayoutParams(-1, -1))
                a.setContentView(root, ViewGroup.LayoutParams(-1, -1))
                Mockup.layout(root, m.widthPixels, m.heightPixels); idle(50)
                val heard = "Turn off the kitchen lights"
                VoiceLevels.mic = .8f
                glow.update(VoiceUi("listening", seq = 1), enabled = true)
                repeat(4) { Mockup.layout(root, m.widthPixels, m.heightPixels); idle(250) }
                when (state) {
                    "thinking" -> glow.update(VoiceUi("thinking", heard = heard, seq = 2), true)
                    "speaking" -> { VoiceLevels.ttsPlaying = true; glow.update(VoiceUi("speaking", heard = heard, reply = "Turned off 3 lights in the kitchen.", seq = 2), true) }
                    "error" -> glow.update(VoiceUi("error: Home Assistant is not connected", seq = 2), true)
                }
                repeat(3) { Mockup.layout(root, m.widthPixels, m.heightPixels); idle(if (state == "thinking") 220 else 300) }
                Mockup.save("glow-$frame-${id.replace('/', '-')}-$state", root, m.widthPixels, m.heightPixels)
                VoiceLevels.mic = 0f; VoiceLevels.ttsPlaying = false
            }
        }
    }

    /**
     * Listening at a run of mic levels (silence, quiet, normal speech, loud and back), each as it settles: the frames of
     * a voice sequence, for a GIF of how the border answers the voice. The level is the engine's (VoiceLevels.mic).
     */
    private fun voice(frame: String) {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        val pick = Themes.pick("glassworks/dark")
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val m = a.resources.displayMetrics
        val root = FrameLayout(a)
        root.addView(Dashboard(a, true), FrameLayout.LayoutParams(-1, -1))
        val glow = EdgeGlowOverlay(a).apply { theme = { pick } }
        root.addView(glow, FrameLayout.LayoutParams(-1, -1))
        a.setContentView(root, ViewGroup.LayoutParams(-1, -1))
        Mockup.layout(root, m.widthPixels, m.heightPixels); idle(50)
        VoiceLevels.mic = 0f
        glow.update(VoiceUi("listening", seq = 1), enabled = true)
        repeat(4) { Mockup.layout(root, m.widthPixels, m.heightPixels); idle(250) }
        val levels = listOf(0f, .1f, .2f, .3f, .4f, .5f, .4f, .3f, .2f, .1f, 0f)
        for ((i, l) in levels.withIndex()) {
            VoiceLevels.mic = l
            repeat(3) { Mockup.layout(root, m.widthPixels, m.heightPixels); idle(200) }
            Mockup.save("voice-$frame-%02d-level-%02d".format(i, (l * 100).toInt()), root, m.widthPixels, m.heightPixels)
        }
        VoiceLevels.mic = 0f
    }

    @Test fun s1Voice() = voice("s1")

    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun mirrorVoice() = voice("mirror")

    @Test fun s1() = shoot("s1")

    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun mirror() = shoot("mirror")
}
