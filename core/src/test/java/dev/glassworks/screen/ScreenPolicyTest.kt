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
import java.io.File
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
        override var idleOffSec = 120
        override val wakeOnApproach = true
        override val presenceRangeM = 0f
        override val minBrightness = 0.1f
        override var maxBrightness = 1f
    }

    /** Counts starts and stops, and lets a test send a reading while it is started. */
    private class FakeSource : PresenceSource, LightSource {
        var starts = 0
        var stops = 0
        var presence: ((Boolean, Float) -> Unit)? = null
        override fun start(context: Context, onReading: (near: Boolean, raw: Float) -> Unit) { starts++; presence = onReading }
        var lux: ((Float) -> Unit)? = null
        override fun start(context: Context, onLux: (Float) -> Unit) { starts++; lux = onLux }
        override fun available(context: Context) = true
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

    private val holdFile get() = File(context.noBackupFilesDir, "test_hold")

    /** The app's process dies (a force-stop): nothing of it runs on, and its files stay. */
    private fun kill(screen: ScreenPolicy) {
        val saved = holdFile.takeIf { it.exists() }?.readText()
        screen.stop()
        saved?.let(holdFile::writeText)
    }

    private fun testHoldLog() = ShadowLog.getLogsForTag("Test.Screen").map { it.msg }.filter { it.startsWith("test hold") }

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

    @Test fun theLimitCapsBrightnessUnlessHeld() {
        assertEquals(0.2f, ScreenPolicy.brightnessCap(held = false, max = 0.2f))
        assertEquals(1f, ScreenPolicy.brightnessCap(held = true, max = 0.2f))
        assertEquals(1f, ScreenPolicy.brightnessCap(held = false, max = 1f))
    }

    /** A high or critical notification (#118): full brightness past a night limit Home Assistant set, at once, and back after release. */
    @Test fun aHoldLiftsTheBrightnessLimitAtOnce() {
        val light = FakeSource()
        val settings = Settings().apply { maxBrightness = 0.2f }
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

    /**
     * #449: a frame with no presence sensor through setup. Past a 20 s idle timeout, under the brightness limit,
     * with no wake; the countdown starts when it ends.
     */
    @Test fun stayOnPausesTheIdleTimeoutAndChangesNothingElse() {
        val light = FakeSource()
        val settings = Settings().apply { idleOffSec = 20; maxBrightness = 0.2f }
        val screen = policy(light = light, settings = settings)
        screen.start(context)
        light.lux!!(1000f)
        advance(10)
        val keepOn = mutableListOf<Boolean>()
        screen.onKeepScreenOn = { keepOn += it }
        keepOn.clear()

        screen.stayOn("not ready", true)
        screen.stayOn("not ready", true)
        advance(600_000)
        assertEquals("on far past the 20 s idle timeout", emptyList<Boolean>(), keepOn.filter { !it })
        assertEquals("the brightness limit stays", 0.2f, screen.brightness, 0.001f)
        light.lux!!(2000f) // a brighter room while it stays on: still under the limit
        advance(10)
        assertEquals("the brightness limit stays in a bright room", 0.2f, screen.brightness, 0.001f)
        assertFalse("not a hold", screen.held)
        assertEquals("no wake", null, ShadowPowerManager.getLatestWakeLock())

        screen.stayOn("not ready", false)
        advance(19_000)
        assertEquals(emptyList<Boolean>(), keepOn.filter { !it })
        advance(2_000)
        assertEquals("the idle timeout counts from the end", listOf(false), keepOn.filter { !it })
        screen.stop()
    }

    @Test fun stayOnLeavesAScreenThatIsOffAlone() {
        val screen = policy()
        screen.start(context)
        screen.sleep("test")
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        advance(10)
        ShadowPowerManager.reset()
        screen.stayOn("not ready", true)
        advance(10_000)
        assertFalse(screen.screenOn)
        assertEquals("nothing turned it on", null, ShadowPowerManager.getLatestWakeLock())
        screen.stop()
    }

    /** Clarity's test hold (#240): the screen on past a 20 s night timeout, at the night's brightness limit. */
    @Test fun aTestHoldKeepsTheScreenOnAndLeavesTheBrightnessLimitAlone() {
        ShadowLog.clear()
        val light = FakeSource()
        val settings = Settings().apply { idleOffSec = 20; maxBrightness = 0.2f }
        val screen = policy(light = light, settings = settings)
        screen.start(context)
        light.lux!!(1000f)
        advance(10)
        val keepOn = mutableListOf<Boolean>()
        screen.onKeepScreenOn = { keepOn += it }
        keepOn.clear()

        screen.testHold(context, 600)
        assertEquals("the night cap stays", 0.2f, screen.brightness, 0.001f)
        light.lux!!(1000f)
        advance(599_000)
        assertEquals("still the night cap", 0.2f, screen.brightness, 0.001f)
        assertEquals("on far past the 20 s night timeout", emptyList<Boolean>(), keepOn.filter { !it })
        assertTrue(screen.testHeld)
        assertFalse("not a hold that lifts night dimming", screen.held)

        advance(2_000) // the next tick
        assertFalse("expired by itself", screen.testHeld)
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
        screen.testHold(context, 600)
        screen.hold("notification")
        screen.testHold(context, 0)
        assertFalse(screen.testHeld)
        assertTrue("the notification still holds it", screen.held)
        screen.release("notification")
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
        screen.testHold(context, 30)
        advance(20_000)
        screen.testHold(context, 30)
        advance(20_000)
        assertTrue("held 40 s after the first, 20 s after the second", screen.testHeld)
        advance(10_000)
        assertFalse(screen.testHeld)
        assertEquals(
            listOf("test hold on for 30 s", "test hold renewed for 30 s", "test hold off (expired)"),
            ShadowLog.getLogsForTag("Test.Screen").map { it.msg }.filter { it.startsWith("test hold") },
        )
        screen.stop()
    }

    @Test fun aTestHoldLastsAnHourAtMost() {
        val screen = policy()
        screen.start(context)
        screen.testHold(context, 86_400)
        advance(3_599_000)
        assertTrue(screen.testHeld)
        advance(1_000)
        assertFalse(screen.testHeld)
        screen.stop()
    }

    /** stop() then start() (the activity recreated) doesn't leave the panel held. */
    @Test fun stopEndsATestHold() {
        val screen = policy()
        screen.start(context)
        screen.testHold(context, 600)
        screen.stop()
        assertFalse(screen.testHeld)
        assertFalse("nothing left for the next start", holdFile.exists())
        screen.start(context)
        val keepOn = mutableListOf<Boolean>()
        screen.onKeepScreenOn = { keepOn += it }
        keepOn.clear()
        advance(121_000)
        assertEquals("the 120 s idle timeout runs again", listOf(false), keepOn.filter { !it })
        screen.stop()
    }

    /** The kiosk's intent extra (MainActivity.applyIntent): `--ez test_hold true --ei test_hold_s 90`. */
    @Test fun theIntentExtraHoldsAndIsUsedOnce() {
        ShadowLog.clear()
        val screen = policy()
        screen.start(context)
        val on = Intent().putExtra("test_hold", true).putExtra("test_hold_s", 90)
        screen.applyTestHoldExtra(context, on)
        assertTrue(screen.testHeld)
        assertFalse("removed, so a recreated activity doesn't apply it again", on.hasExtra("test_hold"))
        screen.applyTestHoldExtra(context, Intent().putExtra("test_hold", true))
        screen.applyTestHoldExtra(context, Intent())
        screen.applyTestHoldExtra(context, Intent().putExtra("test_hold", false))
        assertFalse(screen.testHeld)
        assertEquals(
            listOf("test hold on for 90 s", "test hold renewed for 1800 s", "test hold off (ended)"),
            ShadowLog.getLogsForTag("Test.Screen").map { it.msg }.filter { it.startsWith("test hold") },
        )
        screen.stop()
    }

    /** A cold launch: MainActivity applies the intent before it starts the policy (CodeRabbit on #332). */
    @Test fun aTestHoldFromBeforeStartWakesTheScreenAtStart() {
        ShadowLog.clear()
        val screen = policy()
        screen.applyTestHoldExtra(context, Intent().putExtra("test_hold", true))
        screen.start(context)
        assertTrue(ShadowLog.getLogsForTag("Test.Screen").any { it.msg == "wake (test hold)" })
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        screen.stop()
    }

    /**
     * The end is on elapsedRealtime and the tick checks it before anything else, so it also ends while
     * the screen is off (CodeRabbit on #332: a Handler delay counts uptime, which deep sleep stops).
     */
    @Test fun aTestHoldEndsWhileTheScreenIsOff() {
        val screen = policy()
        screen.start(context)
        screen.testHold(context, 60)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        advance(10)
        assertFalse(screen.screenOn)
        advance(61_000)
        assertFalse(screen.testHeld)
        screen.stop()
    }

    /**
     * The S1's night run (2026-11-16): each graded restart force-stops the app, and the new process's
     * 20 s night timeout turned the screen off before the runner sent the hold again. The hold now
     * outlives the restart, and still expires on time.
     */
    @Test fun aTestHoldOutlivesARestartOfTheApp() {
        val settings = Settings().apply { idleOffSec = 20 }
        val before = policy(settings = settings)
        before.start(context)
        before.testHold(context, 600)
        advance(100_000)
        kill(before)
        ShadowLog.clear()

        val after = policy(settings = settings)
        after.start(context)
        val keepOn = mutableListOf<Boolean>()
        after.onKeepScreenOn = { keepOn += it }
        keepOn.clear()
        assertTrue(after.testHeld)
        assertEquals(listOf("test hold resumed for 500 s"), testHoldLog())
        assertTrue("it wakes the screen", ShadowLog.getLogsForTag("Test.Screen").any { it.msg == "wake (test hold)" })
        advance(60_000)
        assertEquals("on past the 20 s night timeout in the new process", emptyList<Boolean>(), keepOn.filter { !it })

        advance(440_000)
        assertFalse("expired at the first hold's end", after.testHeld)
        assertFalse(holdFile.exists())
        advance(21_000)
        assertEquals("the night timeout is back", listOf(false), keepOn.filter { !it })
        after.stop()
    }

    @Test fun falseEndsAHoldThatOutlivedARestart() {
        val before = policy()
        before.start(context)
        before.testHold(context, 600)
        kill(before)
        val after = policy()
        after.start(context)
        assertTrue(after.testHeld)
        after.applyTestHoldExtra(context, Intent().putExtra("test_hold", false))
        assertFalse(after.testHeld)
        assertFalse(holdFile.exists())
        kill(after)
        val next = policy()
        next.start(context)
        assertFalse(next.testHeld)
        next.stop()
    }

    /**
     * A hold saved in another boot, one further ahead than the hour a hold may last (a reboot reset
     * elapsedRealtime, on an Android without a boot count), or a file that doesn't parse, is dropped.
     */
    @Test fun aHoldFromAnotherBootIsDropped() {
        AndroidSettings.Global.putInt(context.contentResolver, "boot_count", 7)
        val now = android.os.SystemClock.elapsedRealtime()
        for (saved in listOf("${now + 600_000} 6", "${now + 3_700_000} 7", "garbage")) {
            holdFile.writeText(saved)
            val screen = policy()
            screen.start(context)
            assertFalse(saved, screen.testHeld)
            assertFalse(saved, holdFile.exists())
            screen.stop()
        }
    }
}
