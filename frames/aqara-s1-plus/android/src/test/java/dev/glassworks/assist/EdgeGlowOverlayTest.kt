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
}
