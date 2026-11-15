/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.screen

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowPowerManager
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScreenPolicyTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private class Settings(
        autoBrightness: Boolean = true,
        manualBrightness: Float = -1f,
    ) : ScreenSettings {
        /** The saved settings the policy wrote. */
        val writes = mutableListOf<String>()
        override var autoBrightness = autoBrightness
            set(v) { writes += "autoBrightness"; field = v }
        override var manualBrightness = manualBrightness
            set(v) { writes += "manualBrightness"; field = v }
        override val screenMode = "presence"
        override val idleOffSec = 120
        override var nightWindow = ""
        override val nightIdleOffSec = 20
        override val wakeOnApproach = true
        override val presenceRangeM = 0f
        override val minBrightness = 0.1f
        override val maxBrightness = 1f
        override val nightMaxBrightness = 0.2f
    }

    /** Counts starts and stops, and lets a test send a reading while it is started. */
    private class FakeSource : PresenceSource, LightSource {
        var starts = 0
        var stops = 0
        var presence: ((Boolean, Float) -> Unit)? = null
        override fun start(context: Context, onReading: (near: Boolean, raw: Float) -> Unit) { starts++; presence = onReading }
        var lux: ((Float) -> Unit)? = null
        override fun start(context: Context, onLux: (Float) -> Unit) { starts++; lux = onLux }
        override fun stop(context: Context) { stops++; presence = null }
    }

    private fun policy(
        presence: PresenceSource? = null,
        light: LightSource? = null,
        settings: Settings = Settings(),
    ) = ScreenPolicy(
        presence, light, admin = Application::class.java, settings = { settings },
        logTag = "Test.Screen", wakeLockTag = "test:wake",
    )

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** The runtime builds the policy long before the activity starts it; the idle countdown starts at start(). */
    @Test fun theIdleCountdownStartsAtStartNotWhenThePolicyWasBuilt() {
        val screen = policy()
        val keepOn = mutableListOf<Boolean>()
        advance(200_000)
        screen.start(context)
        screen.onKeepScreenOn = { keepOn += it }
        keepOn.clear()
        advance(119_000)
        assertEquals(emptyList<Boolean>(), keepOn)
        advance(1_000)
        assertEquals(listOf(false), keepOn)
    }

    @Test fun stopUndoesStartAndStartWorksAgain() {
        val presence = FakeSource()
        val light = FakeSource()
        val screen = policy(presence, light)
        val brightness = AndroidSettings.System.getUriFor(AndroidSettings.System.SCREEN_BRIGHTNESS)
        val keepOn = mutableListOf<Boolean>()
        var changes = 0
        screen.start(context)
        screen.onKeepScreenOn = { keepOn += it }
        screen.onStateChanged = { changes++ }
        keepOn.clear()
        assertEquals(1, shadowOf(context.contentResolver).getContentObservers(brightness).size)
        // A wake holds the screen until the idle timeout (#257); stop() lets go of it.
        screen.wake("test")
        val awake = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
        assertTrue("the wake holds the screen", awake.isHeld)

        screen.stop()
        screen.stop()

        assertFalse("stop() let go of the wake lock", awake.isHeld)

        assertEquals("each source stopped once", listOf(1, 1), listOf(presence.stops, light.stops))
        assertEquals("brightness observer gone", 0, shadowOf(context.contentResolver).getContentObservers(brightness).size)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        advance(600_000)
        assertEquals("no screen broadcast reached it", 0, changes)
        assertTrue("the screen still counts as on", screen.screenOn)
        assertEquals("no tick turned the screen off", emptyList<Boolean>(), keepOn)

        screen.start(context)
        assertEquals("sources started again", listOf(2, 2), listOf(presence.starts, light.starts))
        presence.presence!!(true, 1f)
        assertTrue(screen.near)
        advance(1_000)
        screen.stop()
    }

    @Test fun logsAndWakesUnderTheTagsItWasGiven() {
        ShadowLog.clear()
        val screen = policy()
        screen.start(context)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        screen.wake("test")
        assertEquals("test:wake", shadowOf(ShadowPowerManager.getLatestWakeLock()).tag)
        assertTrue(ShadowLog.getLogsForTag("Test.Screen").any { it.msg == "wake (test)" })
        screen.stop()
    }

    /** The light sensor reports only on change, so in a dark, still room no reading may come for a long time. */
    @Test fun startAppliesTheSavedManualBrightnessBeforeAnyLightReading() {
        val screen = policy(light = FakeSource(), settings = Settings(autoBrightness = false, manualBrightness = 0.4f))
        val hookBeforeStart = mutableListOf<Float>()
        screen.onBrightness = { hookBeforeStart += it }
        hookBeforeStart.clear()

        screen.start(context)
        val hookAfterStart = mutableListOf<Float>()
        screen.onBrightness = { hookAfterStart += it }

        assertEquals(0.4f, screen.brightness)
        assertEquals("a hook set before start", listOf(0.4f), hookBeforeStart)
        assertEquals("a hook set after start, as the activity does", listOf(0.4f), hookAfterStart)
        screen.stop()
    }

    @Test fun startWithAutoBrightnessKeepsTheSystemBrightnessUntilTheFirstLightReading() {
        val screen = policy(light = FakeSource(), settings = Settings(autoBrightness = true, manualBrightness = 0.4f))
        val calls = mutableListOf<Float>()
        screen.onBrightness = { calls += it }
        calls.clear()

        screen.start(context)

        assertEquals(-1f, screen.brightness)
        assertEquals(emptyList<Float>(), calls)
        screen.stop()
    }

    @Test fun nightCapsBrightnessUnlessHeld() {
        assertEquals(0.2f, ScreenPolicy.brightnessCap(night = true, held = false, max = 1f, nightMax = 0.2f))
        assertEquals(1f, ScreenPolicy.brightnessCap(night = true, held = true, max = 1f, nightMax = 0.2f))
        assertEquals(0.8f, ScreenPolicy.brightnessCap(night = false, held = false, max = 0.8f, nightMax = 0.2f))
    }

    /** A high or critical notification (#118): full brightness at night, at once, and back after release. */
    @Test fun aHoldLiftsNightDimmingAtOnce() {
        val light = FakeSource()
        val settings = Settings().apply { nightWindow = "00:00-23:59" }
        val screen = policy(light = light, settings = settings)
        screen.start(context)
        light.lux!!(1000f)
        advance(10)
        assertEquals(0.2f, screen.brightness, 0.001f)
        screen.hold("notification")
        assertEquals(1f, screen.brightness, 0.001f)
        assertTrue(screen.held)
        screen.release("notification")
        assertEquals(0.2f, screen.brightness, 0.001f)
        screen.stop()
    }

    /** Held, the idle timeout doesn't turn the screen off; after release it counts from the release. */
    @Test fun aHoldKeepsTheScreenOnPastTheIdleTimeout() {
        val screen = policy()
        screen.start(context)
        val keepOn = mutableListOf<Boolean>()
        screen.onKeepScreenOn = { keepOn += it }
        keepOn.clear()
        screen.hold("notification")
        advance(300_000)
        assertEquals(emptyList<Boolean>(), keepOn.filter { !it })
        screen.release("notification")
        advance(119_000)
        assertEquals(emptyList<Boolean>(), keepOn.filter { !it })
        advance(1_000)
        assertEquals(listOf(false), keepOn.filter { !it })
        screen.stop()
    }

    /** Clarity's test hold (#240): the screen on at day brightness past the night timeout, then night again. */
    @Test fun aTestHoldKeepsTheScreenOnAtDayBrightnessUntilItExpires() {
        ShadowLog.clear()
        val light = FakeSource()
        val settings = Settings().apply { nightWindow = "00:00-23:59" }
        val screen = policy(light = light, settings = settings)
        screen.start(context)
        light.lux!!(1000f)
        advance(10)
        val keepOn = mutableListOf<Boolean>()
        screen.onKeepScreenOn = { keepOn += it }
        keepOn.clear()

        screen.testHold(600)
        assertEquals("day brightness at once", 1f, screen.brightness, 0.001f)
        advance(599_000)
        assertEquals("on far past the 20 s night timeout", emptyList<Boolean>(), keepOn.filter { !it })
        assertTrue(screen.held)

        advance(1_000)
        assertFalse("expired by itself", screen.held)
        assertEquals("night dimming is back", 0.2f, screen.brightness, 0.001f)
        advance(19_000)
        assertEquals(emptyList<Boolean>(), keepOn.filter { !it })
        advance(2_000) // the next tick
        assertEquals("the night timeout counts from the end", listOf(false), keepOn.filter { !it })

        assertEquals("no saved setting written", emptyList<String>(), settings.writes)
        assertEquals(
            listOf("test hold on for 600 s", "test hold off (expired)"),
            ShadowLog.getLogsForTag("Test.Screen").map { it.msg }.filter { it.startsWith("test hold") },
        )
        screen.stop()
    }

    @Test fun zeroEndsATestHoldAtOnceAndLeavesOtherHolds() {
        ShadowLog.clear()
        val screen = policy()
        screen.start(context)
        screen.testHold(600)
        screen.hold("notification")
        screen.testHold(0)
        assertTrue("the notification still holds it", screen.held)
        screen.release("notification")
        assertFalse(screen.held)
        advance(600_000)
        assertEquals(
            listOf("test hold on for 600 s", "test hold off (ended)"),
            ShadowLog.getLogsForTag("Test.Screen").map { it.msg }.filter { it.startsWith("test hold") },
        )
        screen.stop()
    }

    @Test fun aNewTestHoldMovesTheEnd() {
        ShadowLog.clear()
        val screen = policy()
        screen.start(context)
        screen.testHold(30)
        advance(20_000)
        screen.testHold(30)
        advance(20_000)
        assertTrue("held 40 s after the first, 20 s after the second", screen.held)
        advance(10_000)
        assertFalse(screen.held)
        assertEquals(
            listOf("test hold on for 30 s", "test hold renewed for 30 s", "test hold off (expired)"),
            ShadowLog.getLogsForTag("Test.Screen").map { it.msg }.filter { it.startsWith("test hold") },
        )
        screen.stop()
    }

    @Test fun aTestHoldLastsAnHourAtMost() {
        val screen = policy()
        screen.start(context)
        screen.testHold(86_400)
        advance(3_599_000)
        assertTrue(screen.held)
        advance(1_000)
        assertFalse(screen.held)
        screen.stop()
    }
}
