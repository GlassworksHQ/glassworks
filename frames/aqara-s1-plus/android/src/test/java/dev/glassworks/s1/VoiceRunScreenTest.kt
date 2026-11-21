package dev.glassworks.s1

import dev.glassworks.KioskPrefs
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.glassworksRuntime
import dev.glassworks.voice.VoiceEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A voice run keeps the screen on past the idle timeout, in both Assist looks, from the wake word or push-to-talk,
 * through a follow-up question; the timeout counts again from the end of the run. Driven through the S1 app's own
 * voice path ([S1Panes.onVoiceEvent]) and its overlays in the running activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = KioskApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class VoiceRunScreenTest {
    private val dash = "http://homeassistant.local:8123/lovelace/0"

    private fun harness(look: String, overlay: Boolean = true): GeckoPinHarness {
        PanePrefs(RuntimeEnvironment.getApplication()).voice.overlay = overlay
        return GeckoPinHarness(prefs = {
            it.urlSetting = dash
            it.screenMode = "presence"
            it.idleOffSec = IDLE_S
            it.assistLook = look
        })
    }

    /** The idle sleeps the screen policy logged so far. */
    private fun GeckoPinHarness.idleSleeps() = logs("Glassworks.Screen").count { it == "sleep (idle ${IDLE_S}s)" }

    /**
     * Runs [events] (each followed by [gapMs] with no other voice event), then Idle, and checks the screen stayed on
     * through all of it and turns off one full idle timeout after the run is over.
     */
    private fun GeckoPinHarness.runHoldsTheScreen(vararg events: VoiceEvent, gapMs: Long = 2 * IDLE_S * 1000L) {
        val runtime = app.glassworksRuntime
        val parts = runtime.app as S1Panes
        advance(1_000)
        assertEquals("on before the run", 0, idleSleeps())
        for (e in events) {
            parts.onVoiceEvent(runtime, e)
            advance(gapMs)
            assertEquals("still on after $e and twice the idle timeout", 0, idleSleeps())
        }
        parts.onVoiceEvent(runtime, VoiceEvent.Idle)
        advance(IDLE_S * 1000L - 1_000)
        assertEquals("on for a whole idle timeout after the run", 0, idleSleeps())
        advance(LOOK_GONE_MS + 2_000) // the look's closing moment and exit, then the rest of the timeout
        assertEquals("then off", 1, idleSleeps())
    }

    @Test fun aWakeWordRunWithTheEdgeGlowHoldsTheScreen() = harness(KioskPrefs.ASSIST_GLOW).use { h ->
        h.runHoldsTheScreen(VoiceEvent.WakeDetected, VoiceEvent.Listening, VoiceEvent.Thinking, VoiceEvent.Speaking)
    }

    @Test fun aWakeWordRunWithTheOrbHoldsTheScreen() = harness(KioskPrefs.ASSIST_ORB).use { h ->
        h.runHoldsTheScreen(VoiceEvent.WakeDetected, VoiceEvent.Listening, VoiceEvent.Thinking, VoiceEvent.Speaking)
    }

    /** Push-to-talk starts at listening; the answer asks back, and the follow-up listens with no Idle in between. */
    @Test fun aPushToTalkRunWithAFollowUpQuestionHoldsTheScreen() = harness(KioskPrefs.ASSIST_GLOW).use { h ->
        h.runHoldsTheScreen(
            VoiceEvent.Listening, VoiceEvent.Thinking, VoiceEvent.Speaking,
            VoiceEvent.Listening, VoiceEvent.Thinking, VoiceEvent.Speaking,
        )
    }

    /** With the voice overlay off nothing shows the run, and it still holds the screen. */
    @Test fun aRunHoldsTheScreenWithTheOverlayOff() = harness(KioskPrefs.ASSIST_ORB, overlay = false).use { h ->
        h.runHoldsTheScreen(VoiceEvent.WakeDetected, VoiceEvent.Listening, VoiceEvent.Thinking, VoiceEvent.Speaking)
    }

    private companion object {
        /**
         * Short, so the run stays cheap: the activity's setup tick does real work every 2 s of test time. Home
         * Assistant can set any timeout; the panel's own list starts at 30 s.
         */
        const val IDLE_S = 5
        /** Longer than either look's closing hold and exit (an error's 2.8 s is the longest). */
        const val LOOK_GONE_MS = 4_000L
    }
}
