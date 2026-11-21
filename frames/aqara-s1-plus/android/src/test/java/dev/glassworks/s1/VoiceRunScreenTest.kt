package dev.glassworks.s1

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.glassworks.KioskPrefs
import dev.glassworks.OverlayHost
import dev.glassworks.PaneOverlay
import dev.glassworks.glassworksRuntime
import dev.glassworks.voice.VoiceEvent
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowLog

/**
 * A voice run keeps the screen on past the idle timeout, in both Assist looks, from the wake word or push-to-talk,
 * through a follow-up question; the timeout counts again from the end of the run. Driven through the S1 app's own
 * voice path ([S1Panes.onVoiceEvent]) and its own overlays ([S1Panes.overlays]), on the app's screen policy. No
 * MainActivity: its setup tick makes minutes of test time slow, and the overlays are what shows a run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = KioskApp::class, qualifiers = "w960dp-h480dp-land-hdpi")
class VoiceRunScreenTest {
    private val app = RuntimeEnvironment.getApplication()
    private val runtime get() = app.glassworksRuntime
    private var overlays: List<PaneOverlay> = emptyList()

    @Before fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @After fun tearDown() {
        overlays.forEach { it.destroy() }
        runtime.screen.stop()
        ShadowChoreographer.setPaused(false)
    }

    /**
     * [ms] of test time, a second at a time as the screen policy ticks: one long idleFor moves the clock to its end
     * before it runs what was due, so a look that closes 1 s after the run would seem to close at the end of the jump.
     */
    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, 1_000L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
    }

    /** The S1 app's overlays in an activity, and its screen policy started with [look] and the overlay [overlay]. */
    private fun panel(look: String, overlay: Boolean = true): S1Panes {
        KioskPrefs(app).apply {
            screenMode = "presence"
            idleOffSec = IDLE_S
            assistLook = look
        }
        PanePrefs(app).voice.overlay = overlay
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val main = Handler(Looper.getMainLooper())
        val parts = runtime.app as S1Panes
        overlays = parts.overlays(runtime, object : OverlayHost {
            override val activity = activity
            override fun openInPage(url: String?) {}
            override fun hideMenu() {}
            override fun post(block: () -> Unit) { main.post(block) }
        })
        val root = FrameLayout(activity)
        overlays.forEach { root.addView(it.view, it.layoutParams) }
        activity.setContentView(root, ViewGroup.LayoutParams(-1, -1))
        runtime.screen.start(app)
        ShadowLog.clear()
        advance(50)
        return parts
    }

    /** The idle sleeps the screen policy logged so far. */
    private fun idleSleeps() = ShadowLog.getLogsForTag("Glassworks.Screen").count { it.msg.startsWith("sleep (idle ${IDLE_S}s)") }

    /**
     * Runs [events] (each followed by twice the idle timeout with no other voice event), then Idle, and checks the
     * screen stayed on through all of it and turns off one full idle timeout after the run is over.
     */
    private fun S1Panes.runHoldsTheScreen(vararg events: VoiceEvent) {
        assertEquals("on before the run", 0, idleSleeps())
        for (e in events) {
            onVoiceEvent(runtime, e)
            advance(2 * IDLE_S * 1000L)
            assertEquals("still on after $e and twice the idle timeout", 0, idleSleeps())
        }
        onVoiceEvent(runtime, VoiceEvent.Idle)
        advance(IDLE_S * 1000L - 1_000)
        assertEquals("on for a whole idle timeout after the run", 0, idleSleeps())
        advance(LOOK_GONE_MS + 2_000) // the look's closing moment and exit, then the rest of the timeout
        assertEquals("then off", 1, idleSleeps())
    }

    @Test fun aWakeWordRunWithTheEdgeGlowHoldsTheScreen() =
        panel(KioskPrefs.ASSIST_GLOW).runHoldsTheScreen(VoiceEvent.WakeDetected, VoiceEvent.Listening, VoiceEvent.Thinking, VoiceEvent.Speaking)

    @Test fun aWakeWordRunWithTheOrbHoldsTheScreen() =
        panel(KioskPrefs.ASSIST_ORB).runHoldsTheScreen(VoiceEvent.WakeDetected, VoiceEvent.Listening, VoiceEvent.Thinking, VoiceEvent.Speaking)

    /** Push-to-talk starts at listening; the answer asks back, and the follow-up listens with no Idle in between. */
    @Test fun aPushToTalkRunWithAFollowUpQuestionHoldsTheScreen() = panel(KioskPrefs.ASSIST_GLOW).runHoldsTheScreen(
        VoiceEvent.Listening, VoiceEvent.Thinking, VoiceEvent.Speaking,
        VoiceEvent.Listening, VoiceEvent.Thinking, VoiceEvent.Speaking,
    )

    /** With the voice overlay off nothing shows the run, and it still holds the screen. */
    @Test fun aRunHoldsTheScreenWithTheOverlayOff() =
        panel(KioskPrefs.ASSIST_ORB, overlay = false).runHoldsTheScreen(VoiceEvent.WakeDetected, VoiceEvent.Listening, VoiceEvent.Thinking, VoiceEvent.Speaking)

    private companion object {
        /** The shortest idle timeout the panel's settings offer. */
        const val IDLE_S = 30
        /** Longer than either look's closing hold and exit (an error's 2.8 s is the longest). */
        const val LOOK_GONE_MS = 4_000L
    }
}
