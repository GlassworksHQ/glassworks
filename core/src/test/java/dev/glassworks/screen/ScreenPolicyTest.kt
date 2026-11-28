/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.screen

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.provider.Settings as AndroidSettings
import dev.glassworks.frame.SleepMode
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
        override val screenMode: String = "presence",
        override val wakeOnApproach: Boolean = true,
    ) : ScreenSettings {
        /** The saved settings the policy wrote. */
        val writes = mutableListOf<String>()
        override var autoBrightness = autoBrightness
            set(v) { writes += "autoBrightness"; field = v }
        override var manualBrightness = manualBrightness
            set(v) { writes += "manualBrightness"; field = v }
        override var idleOffSec = 120
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
        sleepMode: SleepMode = SleepMode.LOCK,
    ) = ScreenPolicy(
        presence, light, admin = Application::class.java, settings = { settings },
        logTag = "Test.Screen", wakeLockTag = "test:wake", sleepMode = sleepMode,
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

    /**
     * Someone walks up to a screen that is off, with [settings]. Returns whether it woke, and checks that presence is
     * reported either way: `near`, and a change for the listeners that send the `presence` binary_sensor.
     */
    private fun approachToAScreenThatIsOff(settings: Settings): Boolean {
        ShadowLog.clear()
        val presence = FakeSource()
        val screen = policy(presence = presence, settings = settings)
        screen.start(context)
        screen.sleep("home assistant")
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        advance(10)
        assertFalse(screen.screenOn)
        var changes = 0
        screen.addListener { changes++ }

        presence.presence!!(true, 1f)

        assertTrue("presence is near", screen.near)
        assertEquals("presence is reported", 1, changes)
        val woke = ShadowLog.getLogsForTag("Test.Screen").any { it.msg == "wake (approach)" }
        screen.stop()
        return woke
    }

    /** #601: the Mirror's camera turned on a screen Home Assistant had turned off, in "always". */
    @Test fun alwaysDoesNotWakeOnApproachAndStillReportsPresence() {
        assertFalse(approachToAScreenThatIsOff(Settings(screenMode = "always")))
    }

    @Test fun presenceWakesOnApproach() {
        assertTrue(approachToAScreenThatIsOff(Settings(screenMode = "presence")))
    }

    @Test fun wakeOnApproachOffDoesNotWakeInEitherMode() {
        assertFalse(approachToAScreenThatIsOff(Settings(screenMode = "presence", wakeOnApproach = false)))
        assertFalse(approachToAScreenThatIsOff(Settings(screenMode = "always", wakeOnApproach = false)))
    }

    /** The settings' wake switch reads the same condition the policy wakes on. */
    @Test fun approachWakesOnlyInPresenceWithWakeOnApproach() {
        assertTrue(Settings(screenMode = "presence", wakeOnApproach = true).approachWakes)
        assertFalse(Settings(screenMode = "presence", wakeOnApproach = false).approachWakes)
        assertFalse(Settings(screenMode = "always", wakeOnApproach = true).approachWakes)
        assertFalse(Settings(screenMode = "always", wakeOnApproach = false).approachWakes)
    }

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

    /**
     * A long Assist answer: the run (and its look on screen) keeps the screen on far past a 30 s idle timeout, and the
     * countdown starts again when the last of them ends. Either one alone holds it.
     */
    @Test fun aVoiceRunKeepsTheScreenOnUntilItEndsThenTheIdleTimeoutRunsFromThere() {
        val settings = Settings().apply { idleOffSec = 30 }
        val screen = policy(settings = settings)
        screen.start(context)
        val keepOn = mutableListOf<Boolean>()
        screen.onKeepScreenOn = { keepOn += it }
        keepOn.clear()

        screen.wake("voice")
        screen.voiceRunning(true)
        screen.voiceShowing(true)
        advance(90_000) // thinking, then a long answer, with no voice event in between
        assertEquals("on through the run", emptyList<Boolean>(), keepOn.filter { !it })
        screen.voiceRunning(false) // the answer ended
        advance(2_000) // the look's closing moment
        assertEquals("on while the look still shows the run", emptyList<Boolean>(), keepOn.filter { !it })
        screen.voiceShowing(false)
        advance(29_000)
        assertEquals("a full timeout after the run", emptyList<Boolean>(), keepOn.filter { !it })
        advance(2_000)
        assertEquals("then off", listOf(false), keepOn.filter { !it })
        screen.stop()
    }

    /** A run that never says it ended can't keep the screen on for good: the idle timeout comes back after a while. */
    @Test fun aVoiceRunThatGoesSilentLetsTheIdleTimeoutBackAfterItsLimit() {
        val settings = Settings().apply { idleOffSec = 30 }
        val screen = policy(settings = settings)
        screen.start(context)
        val keepOn = mutableListOf<Boolean>()
        screen.onKeepScreenOn = { keepOn += it }
        keepOn.clear()

        screen.voiceRunning(true)
        screen.voiceShowing(true)
        advance(ScreenPolicy.VOICE_MAX_MS - 60_000)
        screen.voiceRunning(true) // a voice event renews it
        advance(ScreenPolicy.VOICE_MAX_MS - 1_000)
        assertEquals("on while the run still speaks up", emptyList<Boolean>(), keepOn.filter { !it })
        advance(33_000)
        assertEquals("off a timeout after the limit", listOf(false), keepOn.filter { !it })
        screen.stop()
    }

    /** What was heard and the answer's text renew a run in progress, and hold nothing on their own. */
    @Test fun aVoiceRunsTextsRenewItAndStartNothing() {
        val settings = Settings().apply { idleOffSec = 30 }
        val screen = policy(settings = settings)
        screen.start(context)
        val keepOn = mutableListOf<Boolean>()
        screen.onKeepScreenOn = { keepOn += it }
        keepOn.clear()

        screen.voiceRenew() // no run: nothing to renew
        advance(31_000)
        assertEquals("a text outside a run holds nothing", listOf(false), keepOn.filter { !it })

        screen.wake("voice")
        keepOn.clear()
        screen.voiceRunning(true)
        advance(ScreenPolicy.VOICE_MAX_MS - 60_000)
        screen.voiceRenew() // the answer's text
        advance(ScreenPolicy.VOICE_MAX_MS - 1_000)
        assertEquals("on: the text renewed the run", emptyList<Boolean>(), keepOn.filter { !it })
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

    private fun dashboardLog() = ShadowLog.getLogsForTag("Test.Screen").map { it.msg }.filter { it.startsWith("test dashboard") }

    /** Clarity's own dashboard rides on the test hold: only a loopback address, and only while a hold is on. */
    @Test fun aTestDashboardNeedsAHoldAndAnAddressOnThisDevice() {
        ShadowLog.clear()
        val screen = policy()
        screen.start(context)
        val changes = mutableListOf<String?>()
        screen.onTestDashboard = { changes += it }
        screen.setTestDashboard(context, "http://127.0.0.1:8089/clarity-ref/start")
        assertEquals("no hold", null, screen.testDashboard)
        screen.applyTestHoldExtra(context, Intent().putExtra("test_hold", true).putExtra("test_dashboard", "http://192.0.2.10:8123/lovelace/0"))
        assertEquals("not on this device", null, screen.testDashboard)
        val both = Intent().putExtra("test_hold", true).putExtra("test_dashboard", "http://127.0.0.1:8089/clarity-ref/start")
        screen.applyTestHoldExtra(context, both)
        assertEquals("http://127.0.0.1:8089/clarity-ref/start", screen.testDashboard)
        assertFalse("removed once applied", both.hasExtra("test_dashboard"))
        screen.applyTestHoldExtra(context, Intent().putExtra("test_dashboard", "http://localhost:8089/x"))
        screen.applyTestHoldExtra(context, Intent().putExtra("test_dashboard", "http://localhost:8089/x"))
        screen.applyTestHoldExtra(context, Intent().putExtra("test_dashboard", ""))
        screen.applyTestHoldExtra(context, Intent().putExtra("test_dashboard", ""))
        assertEquals(null, screen.testDashboard)
        assertEquals(listOf("http://127.0.0.1:8089/clarity-ref/start", "http://localhost:8089/x", null), changes)
        assertEquals(
            listOf(
                "test dashboard refused: no test hold", "test dashboard refused: only http on 127.0.0.1, localhost or a local network address",
                "test dashboard on: http://127.0.0.1:8089", "test dashboard on: http://localhost:8089",
                "test dashboard on: http://localhost:8089 (already)", "test dashboard off (cleared)",
            ),
            dashboardLog(),
        )
        assertTrue("the hold goes on", screen.testHeld)
        screen.stop()
    }

    /** Each graded restart force-stops the app: the new process comes back to the test's dashboard, until the hold ends. */
    @Test fun aTestDashboardOutlivesARestartAndEndsWithTheHold() {
        val before = policy()
        before.start(context)
        before.testHold(context, 600)
        before.setTestDashboard(context, "http://127.0.0.1:8089/clarity-ref/start")
        before.testHold(context, 900)
        assertEquals("a renewed hold keeps it", "http://127.0.0.1:8089/clarity-ref/start", TestHold.dashboard(context))
        kill(before)
        ShadowLog.clear()

        val after = policy()
        after.start(context)
        val changes = mutableListOf<String?>()
        after.onTestDashboard = { changes += it }
        assertEquals("http://127.0.0.1:8089/clarity-ref/start", after.testDashboard)
        assertEquals(listOf("test dashboard resumed: http://127.0.0.1:8089"), dashboardLog())
        after.applyTestHoldExtra(context, Intent().putExtra("test_hold", false))
        assertEquals(null, after.testDashboard)
        assertEquals("back to the saved dashboard", listOf<String?>(null), changes)
        assertEquals(null, TestHold.dashboard(context))
        after.stop()
    }

    /**
     * The emulator run of 2026-11-19: the runner's clear reached a kiosk that had just been force-stopped, so the intent
     * launched it, and it applied the extra before [ScreenPolicy.start] read the hold's file: nothing to clear, and the
     * dashboard came back at start.
     */
    @Test fun aColdLaunchThatEndsTheTestDashboardEndsIt() {
        val before = policy()
        before.start(context)
        before.testHold(context, 600)
        before.setTestDashboard(context, "http://127.0.0.1:8089/clarity-ref/start")
        kill(before)
        ShadowLog.clear()

        val after = policy()
        after.applyTestHoldExtra(context, Intent().putExtra("test_dashboard", ""))
        after.start(context)
        assertEquals(null, after.testDashboard)
        assertTrue("the hold goes on", after.testHeld)
        assertEquals(listOf("test dashboard resumed: http://127.0.0.1:8089", "test dashboard off (cleared)"), dashboardLog())
        assertEquals(null, TestHold.dashboard(context))
        after.stop()
    }

    /** A runner that died leaves nothing behind: the dashboard ends when the hold expires. */
    @Test fun aTestDashboardEndsWhenTheHoldExpires() {
        val screen = policy()
        screen.start(context)
        screen.testHold(context, 60)
        screen.setTestDashboard(context, "http://127.0.0.1:8089/clarity-ref/start")
        val changes = mutableListOf<String?>()
        screen.onTestDashboard = { changes += it }
        advance(61_000)
        assertFalse(screen.testHeld)
        assertEquals(null, screen.testDashboard)
        assertEquals(listOf<String?>(null), changes)
        assertFalse(holdFile.exists())
        screen.stop()
    }

    /** Clarity's fake over the LAN (`run.sh --ha-transport lan`, the Mirror's Android 6): taken with a hold, as on loopback. */
    @Test fun aLocalNetworkTestDashboardIsTakenWithAHold() {
        ShadowLog.clear()
        val screen = policy()
        screen.start(context)
        screen.setTestDashboard(context, "http://10.0.0.41:8089/clarity-ref/start")
        assertEquals("no hold", null, screen.testDashboard)
        screen.testHold(context, 60)
        screen.setTestDashboard(context, "http://10.0.0.41:8089/clarity-ref/start")
        assertEquals("http://10.0.0.41:8089/clarity-ref/start", screen.testDashboard)
        assertEquals("test dashboard on: http://10.0.0.41:8089", dashboardLog().last())
        screen.stop()
    }

    @Test fun onlyLoopbackOrLocalNetworkHttpIsATestDashboard() {
        // Loopback (adb reverse), or a local network address (Clarity's --ha-transport lan, where adb reverse fails).
        for (ok in listOf("http://127.0.0.1:8089/clarity-ref/start", "http://localhost:8089/", "https://127.0.0.1:8443/x", "http://[::1]:8089/",
                          "http://10.0.0.41:8089/clarity-ref/start", "http://[fd00::41]:8089/", "http://169.254.0.41:8089/",
                          "http://[::ffff:10.0.0.41]:8089/", "http://10.0.0.0:8089/", "http://172.16.0.1:8089/")) {
            assertTrue(ok, TestHoldFile.isTestDashboard(ok))
        }
        // 192.0.2.0/24 and 203.0.113.0/24 are documentation ranges, not private: like any public address, refused. So is
        // any name, which would be resolved. A leading zero is octal to Gecko (010.0.0.1 loads 8.0.0.1, 0172.16.0.1
        // 122.16.0.1) though Java reads it as decimal, so it is refused; so are "any", IPv4-compatible and mapped public.
        for (no in listOf("http://192.0.2.10:8123/", "http://203.0.113.5:8089/", "http://[2001:db8::1]:8089/",
                          "http://010.0.0.1:8089/", "http://0172.16.0.1:8089/", "http://10.0.0.01:8089/", "http://0.0.0.0:8089/",
                          "http://[::]:8089/", "http://[::ffff:8.8.8.8]:8089/", "http://[::10.0.0.1]:8089/",
                          "http://[::ffff:010.0.0.1]:8089/", "http://167772161:8089/", "http://0x0a.0.0.1:8089/", "http://10.1:8089/",
                          "http://10.0.0.256:8089/",
                          "http://homeassistant.local:8123", "http://10.0.0.41.example/", "file:///sdcard/x.html", "http://127.0.0.1.example/",
                          "ftp://10.0.0.41/", "javascript:alert(1)", "", "http://127.0.0.1:8089/a b")) {
            assertFalse(no, TestHoldFile.isTestDashboard(no))
        }
    }

    // ------------------------------------------------------------ the Log page's line (#675)

    private val s1 = ScreenHardware(ScreenHardware.Presence.Sensor(ranged = true), light = true, touch = true)

    /** The Mirror: presence from the camera pane, no light sensor. */
    private val mirror = ScreenHardware(ScreenHardware.Presence.Pane("camera", "Camera", on = true), light = false, touch = false)

    @Test fun theLogLineSaysWhatTheFrameHasInWords() {
        assertEquals("Someone near: yes · Room light: 120 lux · Brightness: 40 %", s1.status(near = true, lux = 120.4f, brightness = 0.4f))
        assertEquals("Someone near: no · Room light: no reading yet · Brightness: set by Android", s1.status(false, Float.NaN, -1f))
    }

    @Test fun theLogLineLeavesOutWhatTheFrameLacks() {
        assertEquals("Someone near: no · Brightness: set by Android", mirror.status(near = false, lux = Float.NaN, brightness = -1f))
        val cameraOff = mirror.copy(presence = ScreenHardware.Presence.Pane("camera", "Camera", on = false))
        assertEquals("Brightness: 75 %", cameraOff.status(near = false, lux = Float.NaN, brightness = 0.75f))
        assertEquals("Brightness: 75 %", mirror.copy(presence = ScreenHardware.Presence.None).status(true, 3f, 0.75f))
    }

    @Test fun theRawReadingsGoToLogcatOnly() {
        ShadowLog.clear()
        val sensor = FakeSource()
        val screen = policy(presence = sensor)
        screen.start(context)
        sensor.presence!!(false, 7.716E-4f)
        val line = screen.status(mirror)
        assertEquals("Someone near: no · Brightness: set by Android", line)
        val raw = ShadowLog.getLogsForTag("Test.Screen").map { it.msg }.single { it.startsWith("status: ") }
        assertTrue(raw, "raw 7.716E-4" in raw && "lux=NaN" in raw && "admin=" in raw)
        screen.stop()
    }

    // ------------------------------------------------------------ backlight-only sleep (the lululemon Mirror's display)

    /** What a [SleepMode.BACKLIGHT_OFF] policy told the activity, in order. */
    private class Hooks(screen: ScreenPolicy) {
        val keepOn = mutableListOf<Boolean>()
        val brightness = mutableListOf<Float>()
        val blackout = mutableListOf<Boolean>()
        var changes = 0
        init {
            screen.onKeepScreenOn = { keepOn += it }
            screen.onBrightness = { brightness += it }
            screen.onBlackout = { blackout += it }
            screen.addListener { changes++ }
            keepOn.clear(); brightness.clear(); blackout.clear()
        }
    }

    /** An active device admin, so a [SleepMode.LOCK] policy would lockNow(). */
    private fun activeAdmin() {
        shadowOf(context.getSystemService(DevicePolicyManager::class.java)).setActiveAdmin(ComponentName(context, Application::class.java))
    }

    /** Robolectric's lockNow() locks the keyguard; nothing else here does. */
    private fun lockedNow() = context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

    @Test fun backlightOffIdleSleepNeverLocksAndKeepsTheDisplayOn() {
        activeAdmin()
        val settings = Settings(autoBrightness = false, manualBrightness = 0.6f).apply { idleOffSec = 30 }
        val screen = policy(settings = settings, sleepMode = SleepMode.BACKLIGHT_OFF)
        screen.start(context)
        val h = Hooks(screen)

        advance(31_000)

        assertFalse("no lockNow", lockedNow())
        assertEquals("keep-screen-on never let go", emptyList<Boolean>(), h.keepOn.filter { !it })
        assertEquals("the backlight goes to 0", listOf(ScreenPolicy.BLACKOUT_BRIGHTNESS), h.brightness)
        assertEquals("black over the page", listOf(true), h.blackout)
        assertTrue(screen.blackout)
        assertFalse("off to Home Assistant", screen.screenOn)
        assertTrue("the Screen entity heard of it", h.changes > 0)
        assertEquals("the policy's own brightness is kept for the wake", 0.6f, screen.brightness, 0.001f)
        advance(600_000)
        assertEquals("one sleep, not one a tick", listOf(true), h.blackout)
        screen.stop()
    }

    @Test fun backlightOffHomeAssistantSleepAndWakeKeepTheScreenEntityInStep() {
        activeAdmin()
        val screen = policy(settings = Settings(autoBrightness = false, manualBrightness = 0.6f), sleepMode = SleepMode.BACKLIGHT_OFF)
        screen.start(context)
        val h = Hooks(screen)

        screen.sleep("home assistant")
        assertFalse(lockedNow())
        assertFalse(screen.screenOn)
        assertEquals(listOf(true), h.blackout)
        assertEquals(listOf(0f), h.brightness)

        val before = h.changes
        screen.wake("home assistant")
        assertTrue(screen.screenOn)
        assertFalse(screen.blackout)
        assertEquals(listOf(true, false), h.blackout)
        assertEquals("the saved brightness comes back", listOf(0f, 0.6f), h.brightness)
        assertTrue("the Screen entity heard of the wake", h.changes > before)
        assertEquals(emptyList<Boolean>(), h.keepOn.filter { !it })
        screen.stop()
    }

    /**
     * A blackout renews the wake lock a wake took while Android is interactive, and stops once it isn't: the power key
     * may have just turned the display off, and the lock's ACQUIRE_CAUSES_WAKEUP would turn it back on.
     */
    @Test fun backlightOffRenewsTheWakeLockOnlyWhileInteractive() {
        val screen = policy(settings = Settings(), sleepMode = SleepMode.BACKLIGHT_OFF)
        screen.start(context)
        screen.wake("test")
        val lock = ShadowPowerManager.getLatestWakeLock()
        screen.sleep("home assistant")
        assertTrue(screen.blackout)
        assertTrue("the wake's lock is kept through the blackout", lock.isHeld)

        val held = shadowOf(lock).timesHeld
        advance(ScreenPolicy.AWAKE_REFRESH_MS + 2_000)
        assertTrue("renewed while interactive", shadowOf(lock).timesHeld > held)

        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        val off = shadowOf(lock).timesHeld
        advance(ScreenPolicy.AWAKE_REFRESH_MS + 2_000)
        assertEquals("not renewed once Android isn't interactive", off, shadowOf(lock).timesHeld)
        screen.stop()
    }

    @Test fun backlightOffWakeRestoresAutoBrightnessAtTheRoomsLevelAndRestartsTheIdleTimeout() {
        val light = FakeSource()
        val settings = Settings().apply { idleOffSec = 30 }
        val screen = policy(light = light, settings = settings, sleepMode = SleepMode.BACKLIGHT_OFF)
        screen.start(context)
        light.lux!!(1000f)
        val h = Hooks(screen)

        screen.sleep("home assistant")
        light.lux!!(0f) // the room went dark while the screen slept
        assertEquals("still black: a reading doesn't light it", listOf(0f, 0f), h.brightness)
        screen.wake("touch")
        assertEquals("straight to the dark room's level", settings.minBrightness, h.brightness.last(), 0.001f)

        advance(29_000)
        assertEquals("the countdown started again at the wake", listOf(true, false), h.blackout)
        advance(2_000)
        assertEquals(listOf(true, false, true), h.blackout)
        screen.stop()
    }

    @Test fun backlightOffAVoiceRunStillDelaysTheIdleSleep() {
        val screen = policy(settings = Settings().apply { idleOffSec = 30 }, sleepMode = SleepMode.BACKLIGHT_OFF)
        screen.start(context)
        val h = Hooks(screen)

        screen.wake("voice")
        screen.voiceRunning(true)
        advance(90_000)
        assertEquals("on through the run", emptyList<Boolean>(), h.blackout)
        screen.voiceRunning(false)
        advance(29_000)
        assertEquals(emptyList<Boolean>(), h.blackout)
        advance(2_000)
        assertEquals("then dark", listOf(true), h.blackout)
        screen.stop()
    }

    @Test fun backlightOffAHoldWakesTheScreenAndKeepsIt() {
        val screen = policy(settings = Settings().apply { idleOffSec = 30 }, sleepMode = SleepMode.BACKLIGHT_OFF)
        screen.start(context)
        val h = Hooks(screen)
        screen.sleep("home assistant")

        screen.hold("notification")
        assertTrue(screen.screenOn)
        assertEquals(listOf(true, false), h.blackout)
        advance(120_000)
        assertEquals("held past the timeout", listOf(true, false), h.blackout)
        screen.release("notification")
        advance(31_000)
        assertEquals(listOf(true, false, true), h.blackout)
        screen.stop()
    }

    @Test fun backlightOffApproachWakesFromTheBlackout() {
        val presence = FakeSource()
        val screen = policy(presence = presence, sleepMode = SleepMode.BACKLIGHT_OFF)
        screen.start(context)
        val h = Hooks(screen)
        screen.sleep("home assistant")

        presence.presence!!(true, 1f)

        assertTrue(screen.screenOn)
        assertEquals(listOf(true, false), h.blackout)
        screen.stop()
    }

    /** Android's display never went off, so its screen-on broadcast can't end the blackout behind the policy's back. */
    @Test fun backlightOffAScreenOnBroadcastLeavesTheBlackout() {
        val screen = policy(sleepMode = SleepMode.BACKLIGHT_OFF)
        screen.start(context)
        val h = Hooks(screen)
        screen.sleep("home assistant")

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        advance(10)

        assertFalse(screen.screenOn)
        assertTrue(screen.blackout)
        assertEquals(listOf(true), h.blackout)
        screen.stop()
    }

    /** If Android genuinely went SCREEN_OFF (e.g. physical power key) during blackout, a subsequent SCREEN_ON wakes cleanly. */
    @Test fun backlightOffScreenOffThenScreenOnExitsBlackout() {
        val screen = policy(sleepMode = SleepMode.BACKLIGHT_OFF)
        screen.start(context)
        val h = Hooks(screen)
        screen.sleep("home assistant")

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        advance(10)

        assertTrue(screen.screenOn)
        assertFalse(screen.blackout)
        assertEquals(listOf(true, false), h.blackout)
        screen.stop()
    }

    /** An activity made during a blackout (a restart) draws it at once. */
    @Test fun backlightOffAHookSetDuringABlackoutGetsIt() {
        val screen = policy(sleepMode = SleepMode.BACKLIGHT_OFF)
        screen.start(context)
        screen.sleep("home assistant")
        val blackout = mutableListOf<Boolean>()
        val brightness = mutableListOf<Float>()
        screen.onBlackout = { blackout += it }
        screen.onBrightness = { brightness += it }
        assertEquals(listOf(true), blackout)
        assertEquals(listOf(0f), brightness)
        screen.stop()
    }

    // ------------------------------------------------------------ visible floor (the Mirror: brightness 0 is the backlight off)

    private val systemBrightnessUri = AndroidSettings.System.getUriFor(AndroidSettings.System.SCREEN_BRIGHTNESS)

    /** Android's brightness setting (0..255), and the change its slider sends. */
    private fun systemBrightness(v: Int) {
        AndroidSettings.System.putInt(context.contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS, v)
        context.contentResolver.notifyChange(systemBrightnessUri, null)
        advance(10)
    }

    @Test fun awakeBrightnessKeepsABacklightOffFrameVisible() {
        val floor = ScreenPolicy.VISIBLE_FLOOR
        assertEquals("a manual value below the floor", floor, ScreenPolicy.awakeBrightness(0.01f, null, floor), 0f)
        assertEquals("auto brightness at 0", floor, ScreenPolicy.awakeBrightness(0f, 0.8f, floor), 0f)
        assertEquals("a value above it is kept", 0.6f, ScreenPolicy.awakeBrightness(0.6f, 0f, floor), 0f)
        assertEquals("the system value at 0", floor, ScreenPolicy.awakeBrightness(-1f, 0f, floor), 0f)
        assertEquals("a visible system value stays the system's", -1f, ScreenPolicy.awakeBrightness(-1f, 200 / 255f, floor), 0f)
        assertEquals("an unreadable system value stays the system's", -1f, ScreenPolicy.awakeBrightness(-1f, null, floor), 0f)
        assertEquals("no floor elsewhere", 0f, ScreenPolicy.awakeBrightness(0f, 0f, null), 0f)
        assertEquals("no floor elsewhere", -1f, ScreenPolicy.awakeBrightness(-1f, 0f, null), 0f)
    }

    /** The Mirror came up awake and dark: following a system brightness of 0, which is its backlight off. */
    @Test fun backlightOffFollowingASystemBrightnessOfZeroStaysVisibleAndWakesVisible() {
        AndroidSettings.System.putInt(context.contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS, 0)
        val screen = policy(settings = Settings(autoBrightness = false, manualBrightness = 0f), sleepMode = SleepMode.BACKLIGHT_OFF)
        screen.start(context)
        val brightness = mutableListOf<Float>()
        screen.onBrightness = { brightness += it }
        assertEquals("awake at the floor, not at 0", listOf(ScreenPolicy.VISIBLE_FLOOR), brightness)
        assertEquals("still following the system value", -1f, screen.brightness, 0f)

        screen.sleep("home assistant")
        screen.wake("home assistant")
        assertEquals("the blackout is 0, the wake visible", listOf(ScreenPolicy.VISIBLE_FLOOR, 0f, ScreenPolicy.VISIBLE_FLOOR), brightness)
        screen.stop()
    }

    /** onBrightness attached before start(): the first value came without the system's, so start sends the floor. */
    @Test fun backlightOffAHookAttachedBeforeStartGetsTheFloorAtStart() {
        AndroidSettings.System.putInt(context.contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS, 0)
        val screen = policy(settings = Settings(autoBrightness = false, manualBrightness = 0f), sleepMode = SleepMode.BACKLIGHT_OFF)
        val brightness = mutableListOf<Float>()
        screen.onBrightness = { brightness += it }
        screen.start(context)
        assertEquals("the floor once the system value is known", ScreenPolicy.VISIBLE_FLOOR, brightness.last(), 0f)
        screen.stop()
    }

    @Test fun backlightOffTheSystemValueIsFollowedAgainOnceItIsVisible() {
        AndroidSettings.System.putInt(context.contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS, 200)
        val screen = policy(settings = Settings(autoBrightness = false, manualBrightness = -1f), sleepMode = SleepMode.BACKLIGHT_OFF)
        screen.start(context)
        val brightness = mutableListOf<Float>()
        screen.onBrightness = { brightness += it }
        assertEquals("a visible system value: no override", listOf(-1f), brightness)

        systemBrightness(0)
        assertEquals("slid to 0: the floor", listOf(-1f, ScreenPolicy.VISIBLE_FLOOR), brightness)
        systemBrightness(0)
        assertEquals("said once", listOf(-1f, ScreenPolicy.VISIBLE_FLOOR), brightness)
        systemBrightness(120)
        assertEquals("back to the system's own", listOf(-1f, ScreenPolicy.VISIBLE_FLOOR, -1f), brightness)

        screen.sleep("home assistant")
        systemBrightness(0)
        assertEquals("a blackout stays at 0", listOf(-1f, ScreenPolicy.VISIBLE_FLOOR, -1f, 0f), brightness)
        screen.stop()
    }

    @Test fun backlightOffAManualValueBelowTheFloorIsLifted() {
        val screen = policy(settings = Settings(autoBrightness = false, manualBrightness = 0.6f), sleepMode = SleepMode.BACKLIGHT_OFF)
        screen.start(context)
        val brightness = mutableListOf<Float>()
        screen.onBrightness = { brightness += it }
        screen.setManualBrightness(0.01f)
        assertEquals(listOf(0.6f, ScreenPolicy.VISIBLE_FLOOR), brightness)
        screen.stop()
    }

    /** Every other frame keeps its system value and manual values as they were. */
    @Test fun lockFramesHaveNoFloor() {
        AndroidSettings.System.putInt(context.contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS, 0)
        val screen = policy(settings = Settings(autoBrightness = false, manualBrightness = -1f))
        screen.start(context)
        val brightness = mutableListOf<Float>()
        screen.onBrightness = { brightness += it }
        screen.setManualBrightness(0.01f)
        assertEquals(listOf(-1f, 0.01f), brightness)
        screen.stop()
    }

    /** The default: every frame but the Mirror turns Android's display off, as before. */
    @Test fun lockSleepStillLocksAndNeverBlacksOut() {
        activeAdmin()
        val screen = policy(settings = Settings().apply { idleOffSec = 30 })
        screen.start(context)
        val h = Hooks(screen)

        advance(31_000)

        assertTrue("lockNow", lockedNow())
        assertEquals(emptyList<Boolean>(), h.blackout)
        assertFalse(screen.blackout)
        assertFalse("no brightness 0", 0f in h.brightness)
        screen.stop()
    }
}
