/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.screen

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.glassworks.frame.SleepMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.log10

/**
 * When the screen is on and how bright it is, from the frame's [presence] and [light] sources.
 *
 *  - Approaching the panel wakes the screen ([ScreenSettings.approachWakes]), and it stays on while someone is near.
 *  - After `idleOffSec` with nobody near and no touch, the screen turns off.
 *  - Brightness follows ambient light (log curve), up to `maxBrightness`.
 *  - Nothing here knows the time of day (#473): Home Assistant changes the idle timeout and the
 *    brightness limit at night, with the Panel night mode blueprint.
 *  - [hold] keeps the screen on at full brightness, past the brightness limit and the idle timeout,
 *    while something urgent is on it (a high or critical notification), until [release].
 *  - [stayOn] pauses the idle timeout and changes nothing else: no wake, no brightness. A frame with no
 *    presence sensor keeps the screen on with it until setup is done and the dashboard is up (#449).
 *  - [testHold] keeps the screen on past the idle timeout for a test run, and changes nothing else:
 *    brightness stays as it is. It ends by itself.
 *  - A voice run pauses the idle timeout while it runs ([voiceRunning]) and while it shows ([voiceShowing]), so the
 *    screen doesn't go dark in the middle of a long answer; the countdown starts again when the run ends.
 *
 * Turning the screen off uses DevicePolicyManager.lockNow() when [admin] is an active device admin. On a frame whose
 * [sleepMode] is [SleepMode.BACKLIGHT_OFF] it never does: Android's display stays on, and sleep is a [blackout]
 * (brightness 0 and a black screen over the page) that any [wake] ends.
 *
 * A wake that comes without a touch ([wake]: Home Assistant, a timer, voice, Improv, a notification)
 * holds a screen wake lock until the kiosk's own idle timeout puts the screen to sleep (#257). After a
 * lockNow() the window's keep-screen-on flag doesn't keep the screen on, so without the lock Android's
 * own `screen_off_timeout` turned it off again about 20 s after the wake.
 * Otherwise it releases KEEP_SCREEN_ON and lets the system screen_off_timeout expire.
 *
 * One per process (on the app's runtime). Everything runs on the main thread unless a member says otherwise.
 */
class ScreenPolicy(
    private val presence: PresenceSource?,
    private val light: LightSource?,
    /** The app's DeviceAdminReceiver. */
    private val admin: Class<*>,
    private val settings: (Context) -> ScreenSettings,
    /** The logcat tag. The app picks it, so its existing log filters keep working. */
    private val logTag: String,
    /** The wake lock's tag, as `dumpsys power` shows it. */
    private val wakeLockTag: String,
    /** How the frame's screen sleeps ([dev.glassworks.frame.Frame.sleepMode]). */
    private val sleepMode: SleepMode = SleepMode.LOCK,
) {
    internal companion object {
        const val TICK_MS = 1000L
        /**
         * The wake lock's own timeout, a safety net if the tick stops: the tick takes it again every
         * [AWAKE_REFRESH_MS] while the screen should stay on.
         */
        const val AWAKE_MAX_MS = 10 * 60_000L
        const val AWAKE_REFRESH_MS = 60_000L
        const val TEST_HOLD = "test hold"
        /** The longest [testHold]: a runner renews it, so a lost runner can't hold the screen for long. */
        const val TEST_HOLD_MAX_S = 3600
        const val TEST_HOLD_EXTRA = "test_hold"
        const val TEST_HOLD_S_EXTRA = "test_hold_s"
        /** A loopback or local network dashboard for the test hold ([testDashboard]); "" ends it. */
        const val TEST_DASHBOARD_EXTRA = "test_dashboard"
        /** The [stayOn] reasons of a voice run: in progress ([voiceRunning]), on screen ([voiceShowing]). */
        const val VOICE_RUN = "voice run"
        const val VOICE_SHOWN = "voice on screen"
        /**
         * How long a voice run may pause the idle timeout with no word from it: a safety net, so a run that never
         * ends (a lost Idle) can't keep the screen on for good. Every voice event renews it.
         */
        const val VOICE_MAX_MS = 10 * 60_000L

        /**
         * The window brightness of a [blackout]: `WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF`. On a device whose
         * lowest brightness is 0 (the lululemon Mirror's) the backlight goes off; elsewhere it is the lowest it goes.
         */
        const val BLACKOUT_BRIGHTNESS = 0f

        /** The most automatic brightness may reach: [max], or full while something [held] the screen. */
        fun brightnessCap(held: Boolean, max: Float): Float = if (held) 1f else max

        /**
         * The lowest window brightness while awake on a frame whose lowest brightness is off ([SleepMode.BACKLIGHT_OFF]):
         * the default lowest automatic brightness, so a dark room looks the same as before.
         */
        const val VISIBLE_FLOOR = 0.1f

        /**
         * What the window gets while awake: the policy's [brightness] (0..1, or below 0 for the system value), lifted to
         * [floor] when there is one. The system value ([system], 0..1, null if unreadable) stays the system's own,
         * with no window override, unless it is below the floor. Without a floor, [brightness] as it is.
         *
         * Why: on the lululemon Mirror 0 is the backlight off, and the system value can be 0 (a slider moved all the
         * way down, or left there by something else). Followed as it was, the panel came up awake and dark, and a
         * wake from Home Assistant didn't light it.
         */
        fun awakeBrightness(brightness: Float, system: Float?, floor: Float?): Float = when {
            floor == null -> brightness
            brightness >= 0f -> maxOf(brightness, floor)
            system != null && system < floor -> floor
            else -> brightness
        }
    }

    private lateinit var ctx: Context
    private lateinit var prefs: ScreenSettings
    private lateinit var pm: PowerManager
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var brightnessObserver: ContentObserver? = null

    // --- live state, readable from any thread
    @Volatile var near = false; private set
    @Volatile var proximityRaw = Float.NaN; private set
    @Volatile var lux = Float.NaN; private set
    @Volatile var brightness = -1f; private set
    @Volatile var screenOn = true; private set

    /**
     * Asleep with the display still on ([SleepMode.BACKLIGHT_OFF]): the window at [BLACKOUT_BRIGHTNESS], and the
     * activity draws black over the page ([onBlackout]). [screenOn] is false meanwhile. Main thread.
     */
    var blackout = false; private set
    private var lastActivity = SystemClock.elapsedRealtime()
    /** Why the screen is held ([hold]). Main thread. */
    private val holds = HashSet<String>()

    /** Why the idle timeout is paused ([stayOn]). Main thread. */
    private val stays = HashSet<String>()

    /** Something holds the screen on ([hold]). */
    val held: Boolean get() = holds.isNotEmpty()

    /** A [testHold] is on. Main thread. */
    var testHeld = false; private set

    /**
     * The dashboard a test shows for its hold instead of the saved one: Clarity's fake Home Assistant, on a
     * loopback address. Clarity used to point the saved dashboard there, which moved the panel's Home Assistant
     * address, and since #496 a moved address asks to sign in again (the Pixel Tablet's run, 2026-11-18). Null
     * without one, and always after the hold ends. It changes no setting and no sign-in; it lives with the hold,
     * in memory and in [TestHoldFile]. Main thread.
     */
    var testDashboard: String? = null; private set

    /** Called with the new [testDashboard] (null: back to the saved dashboard) when it changes. Main thread. */
    var onTestDashboard: ((String?) -> Unit)? = null
    /** When it ends, on [SystemClock.elapsedRealtime], which counts deep sleep too. */
    private var testHoldUntil = 0L
    /** Keeps the hold through a restart of the app; set once a context is known. */
    private var testHoldFile: TestHoldFile? = null

    private fun testHoldFile(c: Context): TestHoldFile =
        testHoldFile ?: TestHold.file(c).also { testHoldFile = it }

    /** Activity hooks: keep-screen-on flag and window brightness (0..1, or -1 = system). */
    var onKeepScreenOn: ((Boolean) -> Unit)? = null
        set(v) { field = v; v?.invoke(keepOn) }
    var onBrightness: ((Float) -> Unit)? = null
        set(v) { field = v; if (v != null) sendBrightness() }

    /** Activity hook: draw black over everything, and wake on a tap, while true ([blackout]). */
    var onBlackout: ((Boolean) -> Unit)? = null
        set(v) { field = v; v?.invoke(blackout) }

    /**
     * What the window gets: [brightness], kept visible on a frame whose lowest brightness is off ([awakeBrightness]),
     * or [BLACKOUT_BRIGHTNESS] in a [blackout].
     */
    private val windowBrightness: Float get() =
        if (blackout) BLACKOUT_BRIGHTNESS else awakeBrightness(brightness, if (visibleFloor != null) systemBrightness() else null, visibleFloor)

    /** [VISIBLE_FLOOR] on a [SleepMode.BACKLIGHT_OFF] frame, where 0 is the backlight off; none elsewhere. */
    private val visibleFloor: Float? = if (sleepMode == SleepMode.BACKLIGHT_OFF) VISIBLE_FLOOR else null

    /** `Settings.System.SCREEN_BRIGHTNESS` as 0..1, or null before [start] or when it can't be read. */
    private fun systemBrightness(): Float? {
        if (!::ctx.isInitialized) return null
        return runCatching {
            android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrNull()
    }

    /** The last value [onBrightness] got. */
    private var sentBrightness = Float.NaN

    private fun sendBrightness() {
        val w = windowBrightness
        sentBrightness = w
        onBrightness?.invoke(w)
    }
    /** Fired on any state change (presence/lux/screen); used to push updates to the page. */
    var onStateChanged: (() -> Unit)? = null

    /** Extra state listeners (e.g. the ESPHome API), fired with [onStateChanged]; main thread. */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun notifyChanged() {
        onStateChanged?.invoke()
        listeners.forEach { it() }
    }

    private var keepOn = true
    /** A [sleep] without device admin is waiting for the system timeout: "always" mustn't take it back (#522). */
    private var sleepAsked = false
    /** Keeps the screen on after a [wake], until [sleep] (#257). Main thread. */
    private var awake: PowerManager.WakeLock? = null
    private var awakeSince = 0L

    fun start(context: Context) {
        if (started) return
        started = true
        ctx = context.applicationContext
        prefs = settings(ctx)
        pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(ctx, admin)
        lastActivity = SystemClock.elapsedRealtime()
        screenOn = pm.isInteractive && !blackout
        watchSystemBrightness()
        // The light sensor reports only on change, so auto brightness off can't wait for a reading.
        updateBrightness()
        // A hook attached before start got the window brightness without the system value (no context yet): on a
        // backlight-off frame following a system value of 0 that was the system's own, i.e. dark. Send it again now.
        if (windowBrightness != sentBrightness) sendBrightness()
        presence?.start(ctx, ::onPresence)
        light?.start(ctx, ::onLux)
        ctx.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        applyPresenceRange()
        handler.post(tick)
        // A test restarted the app inside its hold: the new process holds the screen from its start,
        // before the kiosk's idle timeout can turn it off.
        resumeTestHold(ctx)
        // A test hold from before start() (the intent that launched the activity) hasn't woken it yet.
        if (testHeld) wake(TEST_HOLD)
    }

    /** Undoes [start]: the sensors, the screen broadcasts, the brightness observer and the tick. [start] may run again. */
    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(tick)
        presence?.stop(ctx)
        light?.stop(ctx)
        // start() may have thrown before it registered the receiver.
        runCatching { ctx.unregisterReceiver(screenReceiver) }
        brightnessObserver?.let { ctx.contentResolver.unregisterContentObserver(it) }
        brightnessObserver = null
        if (testHeld) endTestHold("stopped")
        letGo()
    }

    /** Passes the saved presence range to the presence source: null, or why it didn't take. */
    fun applyPresenceRange(): String? = if (started) presence?.setRange(ctx, prefs.presenceRangeM) else null

    val isAdmin: Boolean get() = started && dpm.isAdminActive(adminComponent)

    fun removeAdmin() {
        if (isAdmin) dpm.removeActiveAdmin(adminComponent)
    }

    /** Touch / page interaction / voice activity. */
    fun userActivity() {
        lastActivity = SystemClock.elapsedRealtime()
        if (!keepOn) setKeepOn(true)
    }

    /**
     * Turns the screen on and keeps it on until the idle timeout counts down from now (#257), also after
     * a lockNow() sleep, when nothing else would keep it on.
     */
    fun wake(reason: String) {
        Log.i(logTag, "wake ($reason)")
        lastActivity = SystemClock.elapsedRealtime()
        setKeepOn(true)
        keepAwake()
        if (blackout) endBlackout()
    }

    /** Back from a [blackout]: the page shows again, at the brightness the policy says now. */
    private fun endBlackout() {
        blackout = false
        screenOn = true
        onBlackout?.invoke(false)
        // The light may have changed while it was dark: go straight to the new target.
        if (prefs.autoBrightness && !lux.isNaN()) updateBrightness(immediate = true)
        sendBrightness()
        notifyChanged()
    }

    /**
     * [SleepMode.BACKLIGHT_OFF]'s sleep: Android's display stays on (no lockNow, keep-screen-on stays, the wake lock
     * stays), and only the light goes. The tick keeps the wake lock while it lasts.
     */
    private fun startBlackout() {
        if (blackout) return
        blackout = true
        screenOn = false
        setKeepOn(true)
        sendBrightness()
        onBlackout?.invoke(true)
        notifyChanged()
    }

    private fun keepAwake() {
        @Suppress("DEPRECATION")
        val wl = awake ?: pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE, wakeLockTag
        ).apply { setReferenceCounted(false) }.also { awake = it }
        wl.acquire(AWAKE_MAX_MS)
        awakeSince = SystemClock.elapsedRealtime()
    }

    /** The screen may go off now: the idle timeout said so, or something else turned it off. */
    private fun letGo() {
        awake?.takeIf { it.isHeld }?.release()
    }

    /**
     * Wakes the screen and keeps it on, at full brightness, past the brightness limit and the idle
     * timeout, until [release] with the same [reason]. Several reasons can hold it at once.
     */
    fun hold(reason: String) {
        val first = holds.isEmpty()
        holds += reason
        if (started) wake(reason)
        if (first && started) updateBrightness(immediate = true)
    }

    /**
     * Keeps the screen on past the idle timeout for a test run, such as Clarity's (#240), for [seconds]
     * from now, or ends that at once when [seconds] is 0 or less. A new one while it is on moves the end.
     * Brightness stays as it is, so a test measures the panel as it would be. It
     * changes no setting: it lives in memory and in [TestHoldFile], which carries it through a restart
     * of the app (a test restarts it) and not past a reboot. It ends by itself, so a test that crashed
     * can't leave the screen on, and at [stop].
     */
    fun testHold(context: Context, seconds: Int) {
        val file = testHoldFile(context)
        if (seconds <= 0) {
            file.clear()
            if (testHeld) endTestHold("ended")
            return
        }
        val s = minOf(seconds, TEST_HOLD_MAX_S)
        Log.i(logTag, "test hold ${if (testHeld) "renewed" else "on"} for $s s")
        testHeld = true
        testHoldUntil = SystemClock.elapsedRealtime() + s * 1000L
        file.write(testHoldUntil, TestHold.boot(context), testDashboard)
        if (started) wake(TEST_HOLD)
    }

    /**
     * Shows [url] instead of the saved dashboard for the rest of the test hold ([testDashboard]), or ends that
     * when [url] is blank. Refused, with a log line, for an address off this device or without a hold.
     */
    fun setTestDashboard(context: Context, url: String) {
        val want = url.trim().ifEmpty { null }
        when {
            // Said again, so a runner that asks twice reads the same answer (perfrun.py testdash).
            want == testDashboard -> {
                if (want != null) Log.i(logTag, "test dashboard on: ${origin(want)} (already)")
                return
            }
            want != null && !TestHoldFile.isTestDashboard(want) -> {
                Log.w(logTag, "test dashboard refused: only http on 127.0.0.1, localhost or a local network address")
                return
            }
            want != null && !testHeld -> {
                Log.w(logTag, "test dashboard refused: no test hold")
                return
            }
        }
        testDashboard = want
        if (testHeld) testHoldFile(context).write(testHoldUntil, TestHold.boot(context), want)
        Log.i(logTag, if (want != null) "test dashboard on: ${origin(want)}" else "test dashboard off (cleared)")
        onTestDashboard?.invoke(want)
    }

    /**
     * The hold a test left in [TestHoldFile] when it restarted the app. Read at [start], and before an intent's extras
     * apply: the intent that launches the app comes before [start], and must see the hold it changes (a cold launch
     * with `test_dashboard ''` would otherwise clear nothing, and the hold's dashboard would come back at [start]).
     */
    private fun resumeTestHold(c: Context) {
        if (testHeld) return
        val saved = testHoldFile(c).read(SystemClock.elapsedRealtime(), TestHold.boot(c), TEST_HOLD_MAX_S * 1000L) ?: return
        testHeld = true
        testHoldUntil = saved.end
        testDashboard = saved.dashboard
        Log.i(logTag, "test hold resumed for ${(saved.end - SystemClock.elapsedRealtime() + 999) / 1000} s")
        saved.dashboard?.let { Log.i(logTag, "test dashboard resumed: ${origin(it)}") }
    }

    /** scheme://host:port, for the log. */
    private fun origin(url: String): String =
        runCatching { java.net.URI(url).let { "${it.scheme}://${it.authority}" } }.getOrDefault("?")

    /**
     * An intent's [TEST_HOLD_EXTRA]: `true` holds for [TEST_HOLD_S_EXTRA] seconds (1800 without it),
     * `false` ends it; then its [TEST_DASHBOARD_EXTRA] ([setTestDashboard]). Removed once applied, so a
     * recreated activity doesn't apply them again.
     */
    fun applyTestHoldExtra(context: Context, intent: Intent) {
        if (intent.hasExtra(TEST_HOLD_EXTRA) || intent.hasExtra(TEST_DASHBOARD_EXTRA)) resumeTestHold(context)
        if (intent.hasExtra(TEST_HOLD_EXTRA)) {
            testHold(context, if (intent.getBooleanExtra(TEST_HOLD_EXTRA, false)) intent.getIntExtra(TEST_HOLD_S_EXTRA, 1800) else 0)
            intent.removeExtra(TEST_HOLD_EXTRA)
        }
        // After the hold, so one intent can start both.
        intent.getStringExtra(TEST_DASHBOARD_EXTRA)?.let {
            intent.removeExtra(TEST_DASHBOARD_EXTRA)
            setTestDashboard(context, it)
        }
    }

    /** The idle timeout counts from now, so the screen doesn't go dark at once. */
    private fun endTestHold(why: String) {
        testHoldFile?.clear()
        testHeld = false
        Log.i(logTag, "test hold off ($why)")
        lastActivity = SystemClock.elapsedRealtime()
        if (testDashboard != null) {
            testDashboard = null
            Log.i(logTag, "test dashboard off ($why)")
            onTestDashboard?.invoke(null)
        }
    }

    /**
     * Pauses the idle timeout while [on], for [reason]. Several reasons can pause it at once, and the countdown
     * starts from the moment the last one ends. Unlike [hold] it neither wakes the screen nor lifts the brightness
     * limit: a screen that is off stays off, so a panel whose Home Assistant goes away at night stays dark.
     */
    fun stayOn(reason: String, on: Boolean) {
        if (on) {
            if (stays.add(reason)) Log.i(logTag, "stay on ($reason)")
        } else if (stays.remove(reason)) {
            Log.i(logTag, "stay on ended ($reason)")
            lastActivity = SystemClock.elapsedRealtime()
        }
    }

    /** When the voice run last said something ([voiceRunning], [voiceShowing]), on [SystemClock.elapsedRealtime]. */
    private var voiceAt = 0L

    /**
     * A voice run is in progress, from the wake word or push-to-talk through the answer and any follow-up question
     * ([on]), or over ([on] false: idle or an error). Called on every voice event. It pauses the idle timeout as
     * [stayOn] does, and the countdown starts again when the run ends. Waking the screen stays with the caller.
     */
    fun voiceRunning(on: Boolean) = voice(VOICE_RUN, on)

    /**
     * The voice run said something without changing its state (what was heard, the answer's text): renews a run in
     * progress against [VOICE_MAX_MS], and starts nothing outside one.
     */
    fun voiceRenew() {
        if (VOICE_RUN in stays || VOICE_SHOWN in stays) voiceAt = SystemClock.elapsedRealtime()
    }

    /** The Assist look (the orb or the edge glow) shows a voice run, its closing moment included: as [voiceRunning]. */
    fun voiceShowing(on: Boolean) = voice(VOICE_SHOWN, on)

    private fun voice(reason: String, on: Boolean) {
        if (on) voiceAt = SystemClock.elapsedRealtime()
        stayOn(reason, on)
    }

    /** Lets go of a [hold]. The idle timeout counts from now, so the screen doesn't go dark at once. */
    fun release(reason: String) {
        if (!holds.remove(reason)) return
        Log.i(logTag, "release ($reason)")
        lastActivity = SystemClock.elapsedRealtime()
        if (holds.isEmpty() && started) updateBrightness(immediate = true)
    }

    fun sleep(reason: String) = sleep(reason, asked = true)

    /**
     * [asked]: someone turned the screen off (Home Assistant), not the idle timeout. Without device admin, "always" then
     * waits for the system timeout; after an idle sleep it keeps the screen on again if the mode changed to "always".
     */
    private fun sleep(reason: String, asked: Boolean) {
        if (sleepMode == SleepMode.BACKLIGHT_OFF) {
            Log.i(logTag, "sleep ($reason) backlight off")
            startBlackout()
            return
        }
        Log.i(logTag, "sleep ($reason) admin=$isAdmin")
        letGo()
        if (isAdmin) {
            try {
                dpm.lockNow()
                return
            } catch (e: SecurityException) {
                Log.w(logTag, "lockNow failed", e)
            }
        }
        // Fallback: let the system timeout turn the screen off.
        setKeepOn(false)
        sleepAsked = asked
    }

    private fun setKeepOn(v: Boolean) {
        if (v) sleepAsked = false
        if (keepOn == v) return
        keepOn = v
        onKeepScreenOn?.invoke(v)
    }

    private val tick = object : Runnable {
        override fun run() {
            handler.postDelayed(this, TICK_MS)
            if (testHeld && SystemClock.elapsedRealtime() >= testHoldUntil) endTestHold("expired")
            if ((VOICE_RUN in stays || VOICE_SHOWN in stays) && SystemClock.elapsedRealtime() - voiceAt >= VOICE_MAX_MS) {
                Log.w(logTag, "voice run silent for ${VOICE_MAX_MS / 1000} s: idle timeout back on")
                stayOn(VOICE_RUN, false)
                stayOn(VOICE_SHOWN, false)
            }
            // A blackout keeps Android's display on: renew the wake lock a wake took, as while awake, and
            // not once the device isn't interactive (the same power-key race as below).
            if (blackout) {
                if (awake?.isHeld == true && pm.isInteractive && SystemClock.elapsedRealtime() - awakeSince >= AWAKE_REFRESH_MS) keepAwake()
                return
            }
            if (!screenOn) return
            // Still wanted on: take the wake lock again before its own timeout runs out. Not while the
            // device isn't interactive: the power key may have just turned it off, its broadcast not yet
            // here, and the lock's ACQUIRE_CAUSES_WAKEUP would turn the screen back on.
            if (awake?.isHeld == true && pm.isInteractive && SystemClock.elapsedRealtime() - awakeSince >= AWAKE_REFRESH_MS) keepAwake()
            if (prefs.screenMode == "always") {
                if (!sleepAsked) setKeepOn(true)
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (near || holds.isNotEmpty() || stays.isNotEmpty() || testHeld) lastActivity = now
            val timeoutSec = prefs.idleOffSec
            if (timeoutSec > 0 && keepOn && now - lastActivity >= timeoutSec * 1000L) {
                sleep("idle ${timeoutSec}s", asked = false)
            }
        }
    }

    private var systemScreenOff = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val on = i.action == Intent.ACTION_SCREEN_ON
            Log.i(logTag, "screen ${if (on) "ON" else "OFF"}")
            if (!on) {
                systemScreenOff = true
                screenOn = false
                // Turned off by the power key or anything else: don't hold it against that. Not when a wake
                // already turned it on again (a late broadcast from the sleep before it).
                if (!pm.isInteractive) letGo()
                notifyChanged()
                return
            }
            // A real system SCREEN_OFF followed by SCREEN_ON wakes even out of blackout; a stray
            // SCREEN_ON with no preceding SCREEN_OFF leaves an active blackout intact.
            val wasSystemOff = systemScreenOff
            systemScreenOff = false
            if (blackout && wasSystemOff) {
                wake("screen on")
                return
            }
            screenOn = !blackout
            lastActivity = SystemClock.elapsedRealtime()
            setKeepOn(true)
            notifyChanged()
        }
    }

    private fun onPresence(nowNear: Boolean, raw: Float) {
        proximityRaw = raw
        if (nowNear == near) return
        near = nowNear
        Log.i(logTag, "presence ${if (near) "NEAR" else "far"} (raw=$raw)")
        if (near) {
            lastActivity = SystemClock.elapsedRealtime()
            // Presence is still reported in "always" (the binary_sensor); only the wake waits for the mode (#601).
            if (!screenOn && prefs.approachWakes) wake("approach")
        }
        notifyChanged()
    }

    private fun onLux(value: Float) {
        val prev = lux
        lux = value
        updateBrightness()
        if (prev.isNaN() || kotlin.math.abs(prev - lux) > maxOf(5f, prev * 0.2f)) notifyChanged()
    }

    /**
     * Re-applies the brightness policy after a settings change (main thread). [immediate] jumps to the new target
     * instead of easing toward it: the light sensor reports only changes, so in a steady room no reading would
     * come to finish the easing (a new Max auto brightness from Home Assistant, #477).
     */
    fun refreshBrightness(immediate: Boolean = false) {
        if (started) updateBrightness(immediate)
    }

    /**
     * The system brightness slider (notification shade) only writes Settings.System.SCREEN_BRIGHTNESS;
     * our own brightness is a window override that beats it. This app never writes that setting, so a
     * change there is the user moving the slider: switch auto brightness off and follow the system
     * value. Turning auto brightness back on (Home Assistant or app settings) resumes it.
     */
    private fun watchSystemBrightness() {
        val startedAt = SystemClock.elapsedRealtime()
        val uri = android.provider.Settings.System.getUriFor(android.provider.Settings.System.SCREEN_BRIGHTNESS)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // Followed or not, a system value below the visible floor changes what the window gets.
                if (visibleFloor != null && windowBrightness != sentBrightness) sendBrightness()
                if (SystemClock.elapsedRealtime() - startedAt < 30_000) return // boot-time writes by the firmware
                if (!prefs.autoBrightness && prefs.manualBrightness <= 0f) return // already following the system
                Log.i(logTag, "system brightness slider moved: following it (auto brightness off)")
                prefs.autoBrightness = false
                prefs.manualBrightness = 0f // <= 0: no window override, i.e. the system value
                refreshBrightness()
                notifyChanged()
            }
        }
        ctx.contentResolver.registerContentObserver(uri, false, observer)
        brightnessObserver = observer
    }

    /** Fixed brightness from Home Assistant: turns auto brightness off (main thread). */
    fun setManualBrightness(b: Float) {
        prefs.manualBrightness = b.coerceIn(0.01f, 1f)
        prefs.autoBrightness = false
        refreshBrightness()
    }

    private fun updateBrightness(immediate: Boolean = false) {
        if (!prefs.autoBrightness || lux.isNaN()) {
            val manual = if (prefs.autoBrightness) -1f else prefs.manualBrightness.let { if (it > 0f) it else -1f }
            if (brightness != manual) {
                brightness = manual
                sendBrightness()
                notifyChanged()
            }
            return
        }
        val max = brightnessCap(held, prefs.maxBrightness)
        val min = minOf(prefs.minBrightness, max)
        // 0 lux -> min, ~1000 lux -> max, logarithmic in between.
        val t = (log10(lux.coerceAtLeast(0f) + 1f) / 3f).coerceIn(0f, 1f)
        val target = min + (max - min) * t
        // Smooth, and ignore tiny changes to avoid flicker.
        val next = if (brightness < 0f || immediate) target else brightness + (target - brightness) * 0.3f
        if (brightness < 0f || kotlin.math.abs(next - brightness) > 0.01f) {
            brightness = next
            sendBrightness()
        }
    }

    /** The Log page's line for [hardware] ([ScreenHardware.status]). The raw readings go to logcat only. */
    fun status(hardware: ScreenHardware): String {
        Log.i(logTag, "status: presence=${if (near) "near" else "far"} (raw $proximityRaw) lux=$lux brightness=$brightness admin=$isAdmin")
        return hardware.status(near, lux, brightness)
    }
}
