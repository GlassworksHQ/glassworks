package dev.glassworks.assist

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.glassworks.EdgeGlowOverlay
import dev.glassworks.theme.Themes
import dev.glassworks.ui.Mockup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/** #691: Spark's edge glow as the Assist look, through a voice run: it comes, follows the run, and goes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class EdgeGlowOverlayTest {
    private val showing = ArrayList<Boolean>()
    private var cancelled = 0

    @Before fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @After fun tearDown() = ShadowChoreographer.setPaused(false)

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun glow(pick: Themes.Pick = Themes.DEFAULT): EdgeGlowOverlay {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(a)
        val g = EdgeGlowOverlay(a).apply {
            theme = { pick }
            onShowingChanged = { showing += it }
            onCancel = { cancelled++ }
        }
        root.addView(g, FrameLayout.LayoutParams(-1, -1))
        a.setContentView(root, ViewGroup.LayoutParams(-1, -1))
        Mockup.layout(root)
        idle(50)
        return g
    }

    @Test fun aRunComesFollowsAndGoes() {
        val g = glow()
        assertEquals(View.GONE, g.visibility)
        g.update(VoiceUi("listening", seq = 1), enabled = true)
        idle(700)
        assertEquals(View.VISIBLE, g.visibility)
        assertEquals(listOf(true), showing)
        g.update(VoiceUi("thinking", heard = "Turn off the kitchen lights", seq = 2), true)
        g.update(VoiceUi("speaking", heard = "Turn off the kitchen lights", reply = "Turned off 3 lights.", seq = 3), true)
        idle(300)
        assertTrue(Mockup.label(g, "Turned off 3 lights.").visibility == View.VISIBLE)
        g.update(VoiceUi("idle", heard = "Turn off the kitchen lights", reply = "Turned off 3 lights.", seq = 4), true)
        assertEquals("a timer set by voice flies out as it goes", listOf(true, false), showing)
        idle(600)
        assertEquals(View.GONE, g.visibility)
    }

    @Test fun anErrorHoldsInRoseThenGoes() {
        val g = glow()
        g.update(VoiceUi("listening", seq = 1), true)
        idle(700)
        g.update(VoiceUi("error: Home Assistant is not connected", seq = 2), true)
        idle(300)
        assertTrue(Mockup.label(g, "Can't reach Home Assistant").visibility == View.VISIBLE)
        assertEquals(View.VISIBLE, g.visibility)
        idle(2_200)
        assertEquals(View.GONE, g.visibility)
    }

    @Test fun aPanelSideErrorOutsideARunDoesntBringItUp() {
        val g = glow()
        g.update(VoiceUi("error: no microphone", errorLocal = true, seq = 1), true)
        idle(300)
        assertEquals(View.GONE, g.visibility)
    }

    @Test fun aTapCancelsTheRunAndTouchesThenGoToTheDashboard() {
        val g = glow()
        g.update(VoiceUi("listening", seq = 1), true)
        idle(700)
        assertTrue(g.isClickable)
        g.performClick()
        assertEquals(1, cancelled)
        assertTrue(!g.isClickable)
        idle(600)
        assertEquals(View.GONE, g.visibility)
    }

    @Test fun turnedOffItHides() {
        val g = glow()
        g.update(VoiceUi("listening", seq = 1), true)
        idle(700)
        g.update(VoiceUi("listening", seq = 1), enabled = false)
        idle(600)
        assertEquals(View.GONE, g.visibility)
    }

    @Test fun aLightThemeGivesDarkText() {
        val g = glow(Themes.pick("dusk/light"))
        g.update(VoiceUi("listening", seq = 1), true)
        idle(700)
        val prompt = Mockup.label(g, VoiceTexts.PROMPT)
        assertTrue(Themes.contrast(prompt.currentTextColor, Themes.DUSK.light.ground) >= 3.0)
    }

    @Test fun anErrorDuringTheEntranceStillShowsWhenIdleFollows() {
        val g = glow()
        g.update(VoiceUi("listening", seq = 1), true)
        idle(100)
        g.update(VoiceUi("error: Home Assistant is not connected", seq = 2), true)
        idle(100)
        g.update(VoiceUi("idle", seq = 3), true) // all inside the 650 ms entrance
        idle(700)
        assertEquals(View.VISIBLE, g.visibility)
        assertTrue(Mockup.label(g, "Can't reach Home Assistant").visibility == View.VISIBLE)
        idle(2_200)
        assertEquals(View.GONE, g.visibility)
    }

    @Test fun aNewRunDuringTheExitBringsItBack() {
        val g = glow()
        g.update(VoiceUi("listening", seq = 1), true)
        idle(700)
        g.update(VoiceUi("idle", seq = 2), true)
        idle(100) // going: inside the 420 ms exit
        g.update(VoiceUi("wake", seq = 3), true)
        idle(700)
        assertEquals(View.VISIBLE, g.visibility)
        assertTrue("it can be tapped to cancel again", g.isClickable)
        assertEquals(listOf(true, false, true), showing)
        g.update(VoiceUi("thinking", heard = "What time is it", seq = 4), true)
        idle(300)
        assertTrue(Mockup.label(g, VoiceTexts.quote("What time is it")).visibility == View.VISIBLE)
    }

    @Test fun whatsLeftOfACancelledRunDoesntBringItBack() {
        val g = glow()
        g.update(VoiceUi("listening", seq = 1), true)
        idle(700)
        g.performClick()
        idle(100)
        g.update(VoiceUi("thinking", heard = "Turn off the lights", seq = 2), true)
        g.update(VoiceUi("speaking", reply = "Done.", seq = 3), true)
        idle(700)
        assertEquals(View.GONE, g.visibility)
        assertEquals(listOf(true, false), showing)
    }

    private fun glowOf(g: EdgeGlowOverlay): EdgeGlow =
        (0 until g.childCount).map { g.getChildAt(it) }.filterIsInstance<EdgeGlow>().single()

    /** How thick each strip is now (1 = its own size), and how bright the border is. */
    private fun border(e: EdgeGlow): Pair<List<Float>, Float> =
        e.edges.mapIndexed { i, s -> if (i % 3 == 1) s.scaleX else s.scaleY } to (e.edges[0].parent as View).alpha

    @Test fun theWholeBorderAnswersTheVoiceFromTheRealMicLevel() {
        val g = glow()
        VoiceLevels.mic = 0f
        g.update(VoiceUi("listening", seq = 1), true)
        idle(900)
        idle(400)
        val (quietThick, quietBright) = border(glowOf(g))
        // Speech as a panel's mic hears it (VoiceEngine's level: -55 to -15 dBFS as 0 to 1), through the same
        // VoiceLevels.mic the orb reads.
        VoiceLevels.mic = .45f
        idle(400)
        val (loudThick, loudBright) = border(glowOf(g))
        for ((i, k) in loudThick.withIndex()) assertTrue("strip $i swells: $k", k >= 1.3f && k > quietThick[i] + .25f)
        assertTrue("the border brightens: $quietBright -> $loudBright", loudBright > quietBright + .25f)
        // Quiet again: it settles back (the smoother's release).
        VoiceLevels.mic = 0f
        repeat(10) { idle(300) }
        val (afterThick, _) = border(glowOf(g))
        for (k in afterThick) assertTrue("back to its own size: $k", k < 1.05f)
        VoiceLevels.mic = 0f
    }

    @Test fun theVoiceCurveOpensUpTheRangeRealSpeechUses() {
        assertEquals(0f, EdgeGlow.voice(AudioLevel.LISTENING_FLOOR), 1e-6f)
        assertEquals(0f, EdgeGlow.voice(0f), 1e-6f)
        assertTrue("normal speech is well up the scale", EdgeGlow.voice(.3f) > .5f)
        assertEquals("loud speech is the top", 1f, EdgeGlow.voice(.55f), 1e-6f)
        var last = -1f
        for (i in 0..100) { val v = EdgeGlow.voice(i / 100f); assertTrue(v >= last); last = v }
    }
}

