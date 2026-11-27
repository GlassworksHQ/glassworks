/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.net.wifi.WifiManager

/**
 * Switches Wi-Fi back on after Android failed to load its driver, where this app may switch it on.
 *
 * Why: on the lululemon Mirror's stock Android 6, Android starts the Wi-Fi driver before the vendor scripts have
 * started the Wi-Fi chip's firmware. Once the apps are optimized the boot is fast enough to lose that race every
 * time: the driver doesn't load, Wi-Fi goes to the vendor's failed state ([VENDOR_STATE_FAILED]; AOSP's
 * [WifiManager.WIFI_STATE_UNKNOWN] elsewhere), and Android then switches Wi-Fi off for good (`Settings.Global.WIFI_ON`
 * 0, kept across reboots). Switching it on a few seconds later works.
 * The stock apps the in-place install hides used to do that; now the app does
 * (`frames/lululemon-mirror/research/stock-inplace-parity.md`).
 *
 * It reacts only to that failure ([isDriverFailure]), never to a plain switch-off, so a Wi-Fi someone switched off on
 * purpose stays off. A recovery tries again with backoff ([DELAYS_MS], about two and a half minutes in all), and ends
 * when Wi-Fi is on, when the tries run out, or at [close]. Main thread, through [schedule].
 */
class WifiDriverRecovery(
    /** This app may switch Wi-Fi on ([WifiRadio.mayEnable]). Asked at each try. */
    private val mayEnable: () -> Boolean,
    /** Wi-Fi's state now ([WifiManager.getWifiState]). */
    private val state: () -> Int,
    /** `WifiManager.setWifiEnabled(true)`: true when Android took the request. */
    private val enable: () -> Boolean,
    /** Runs the block after the delay, on the main thread; the returned function cancels it. */
    private val schedule: (Long, () -> Unit) -> () -> Unit,
    private val log: (String) -> Unit,
    /** The time since boot ([android.os.SystemClock.elapsedRealtime]). */
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    companion object {
        /**
         * Before each try, from the failure. The first waits for Android's own switch-off that follows the failure:
         * a switch-on before it changes nothing (the switch still reads on), and the switch-off would undo it.
         */
        val DELAYS_MS = longArrayOf(5_000, 10_000, 15_000, 30_000, 45_000, 45_000)

        /**
         * After a recovery used up its tries, a failure this soon after its last switch-on is that switch-on's own
         * answer, not a new failure: it doesn't start another recovery. One later on does.
         */
        const val OWN_ANSWER_MS = 60_000L

        /**
         * The Mirror's stock Android 6 says a failed driver load as Wi-Fi state 5, which AOSP doesn't have: its
         * WifiService logs "Wi-Fi state is failed", broadcasts `wifi_state=5, previous_wifi_state=1`, and switches Wi-Fi
         * off without another broadcast, so 5 stays the sticky state (verified on the Mirror, 2026-11-25:
         * `dumpsys activity broadcasts`). AOSP says the same failure as [WifiManager.WIFI_STATE_UNKNOWN].
         */
        const val VENDOR_STATE_FAILED = 5

        /** The state says the driver didn't load: AOSP's unknown, or the vendor's failed ([VENDOR_STATE_FAILED]). */
        private fun failed(state: Int): Boolean = state == WifiManager.WIFI_STATE_UNKNOWN || state == VENDOR_STATE_FAILED

        /**
         * The driver didn't load ([failed]), or the switch-off Android makes right after it (a disable whose previous
         * state was a failure). Also what the sticky broadcast says to an app that started after.
         */
        fun isDriverFailure(state: Int, previous: Int): Boolean =
            failed(state) || (state == WifiManager.WIFI_STATE_DISABLED && failed(previous))

        /**
         * A switch-off from on or coming on (a person, or another app), not the driver failing: a failed load goes
         * through a failed state first ([isDriverFailure]).
         */
        fun isSwitchOff(state: Int, previous: Int): Boolean =
            (state == WifiManager.WIFI_STATE_DISABLING || state == WifiManager.WIFI_STATE_DISABLED) &&
                (previous == WifiManager.WIFI_STATE_ENABLED || previous == WifiManager.WIFI_STATE_ENABLING)

        /** Wi-Fi is on or coming on: nothing to do. */
        fun isOnOrComing(state: Int): Boolean =
            state == WifiManager.WIFI_STATE_ENABLED || state == WifiManager.WIFI_STATE_ENABLING
    }

    /** The next try's cancel, while a recovery runs. */
    private var pending: (() -> Unit)? = null
    private var attempt = 0
    /** When the last switch-on was asked; -1 before any. */
    private var askedAt = -1L
    /** The last recovery used up its tries: its own final answer mustn't start another. */
    private var exhausted = false

    /** A recovery is running. */
    val running: Boolean get() = pending != null

    /** A Wi-Fi state change ([WifiManager.WIFI_STATE_CHANGED_ACTION]'s state and previous state). */
    fun onState(state: Int, previous: Int) {
        when {
            isDriverFailure(state, previous) -> start()
            state == WifiManager.WIFI_STATE_ENABLED && running ->
                finish(if (attempt == 0) "Wi-Fi came on by itself" else "Wi-Fi is on again after $attempt ${if (attempt == 1) "try" else "tries"}")
            // someone switched Wi-Fi off while it was on or coming on: that's on purpose, so the recovery stops
            running && isSwitchOff(state, previous) -> finish("Wi-Fi was switched off: not switching it back on")
        }
    }

    private fun start() {
        if (running) return
        if (exhausted && askedAt >= 0 && now() - askedAt < OWN_ANSWER_MS) return
        exhausted = false
        if (!mayEnable()) {
            log("Wi-Fi driver failed to load; this app can't switch Wi-Fi on, so it stays off")
            return
        }
        log("Wi-Fi driver failed to load: switching Wi-Fi back on in ${DELAYS_MS[0] / 1000} s")
        attempt = 0
        next()
    }

    private fun next() {
        if (attempt >= DELAYS_MS.size) {
            exhausted = true
            finish("Wi-Fi didn't come back after $attempt tries: giving up until a failure that isn't an answer to these")
            return
        }
        pending = schedule(DELAYS_MS[attempt]) { tryOnce() }
    }

    private fun tryOnce() {
        pending = null
        attempt++
        val now = state()
        if (now == WifiManager.WIFI_STATE_ENABLED) {
            finish("Wi-Fi is on again")
            return
        }
        if (!mayEnable()) {
            finish("this app can no longer switch Wi-Fi on: stopping")
            return
        }
        if (now == WifiManager.WIFI_STATE_ENABLING) {
            log("Wi-Fi is coming on (try $attempt): waiting")
        } else {
            askedAt = now()
            val ok = runCatching(enable).getOrDefault(false)
            log("switching Wi-Fi on (try $attempt of ${DELAYS_MS.size}): ${if (ok) "asked" else "refused"}")
        }
        next()
    }

    private fun finish(why: String) {
        pending?.invoke()
        pending = null
        log(why)
    }

    /** Stops a recovery that runs. */
    fun close() {
        pending?.invoke()
        pending = null
    }
}
