package dev.glassworks.assist

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.glassworks.VoiceOverlay
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
 * #691: the voice orb in a theme, each state, at the S1's 1440x720 and the Mirror's 1080x1920, over a plain stand-in for
 * a dashboard (dark under a dark theme, light under a light one). Illustrative texts only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*VoiceThemeMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceThemeMockups {
    @Before fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        Mockup.interFromSources()
    }

    @After fun tearDown() { ShadowChoreographer.setPaused(false); VoiceLevels.mic = 0f; VoiceLevels.ttsPlaying = false }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun shoot(frame: String) {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        for (id in listOf("glassworks/dark", "dusk/light", "aurora/dark")) {
            val pick = Themes.pick(id)
            for (state in listOf("listening", "thinking", "speaking", "error", "done")) {
                val a = Robolectric.buildActivity(Activity::class.java).setup().get()
                val m = a.resources.displayMetrics
                val root = FrameLayout(a).apply { setBackgroundColor(if (pick.palette.darkGround) Color.rgb(16, 18, 22) else Color.rgb(242, 243, 245)) }
                val voice = VoiceOverlay(a).apply { onCancel = {}; theme = { pick } }
                root.addView(voice, FrameLayout.LayoutParams(-1, -1))
                a.setContentView(root, ViewGroup.LayoutParams(-1, -1))
                val heard = "Turn off the kitchen lights"
                when (state) {
                    "listening" -> { VoiceLevels.mic = .7f; voice.update(VoiceUi("listening", seq = 1), enabled = true) }
                    "thinking" -> { voice.update(VoiceUi("listening", seq = 1), true); voice.update(VoiceUi("thinking", heard = heard, seq = 2), true) }
                    "speaking" -> { VoiceLevels.ttsPlaying = true; voice.update(VoiceUi("speaking", heard = heard, reply = "Turned off 3 lights in the kitchen.", seq = 1), true) }
                    "error" -> voice.update(VoiceUi("error: Home Assistant is not connected", seq = 1), true)
                    "done" -> { voice.update(VoiceUi("speaking", heard = heard, reply = "Turned off 3 lights in the kitchen.", seq = 1), true); idle(600); voice.update(VoiceUi("idle", heard = heard, reply = "Turned off 3 lights in the kitchen.", seq = 2), true) }
                }
                repeat(3) { Mockup.layout(root, m.widthPixels, m.heightPixels); idle(300) }
                Thread.sleep(2_000) // the orb's bake runs on a real thread
                idle(if (state == "done") 500 else 900)
                Mockup.save("voice-$frame-${id.replace('/', '-')}-$state", root, m.widthPixels, m.heightPixels)
                VoiceLevels.mic = 0f; VoiceLevels.ttsPlaying = false
            }
        }
    }

    @Test fun s1() = shoot("s1")

    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun mirror() = shoot("mirror")
}
