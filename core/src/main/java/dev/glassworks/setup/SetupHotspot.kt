/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import dev.glassworks.platform.Hotspot
import dev.glassworks.platform.HotspotFailure
import dev.glassworks.platform.HotspotNetwork
import dev.glassworks.setup.SetupHotspotPolicy.Stop

/**
 * The panel's web server's check for a phone on the setup hotspot (#460). Android 10 gives an app no list of a
 * hotspot's clients, so a request from the hotspot's own network ([isClient]) is the proof that a phone joined: then
 * [SetupHotspot.phoneJoined] runs through [post] (the main thread). Checked until the first one in each run. Any thread.
 */
class PhoneJoin(
    private val hotspot: () -> SetupHotspot?,
    private val isClient: (remote: String?) -> Boolean,
    private val post: (() -> Unit) -> Unit,
) {
    /** The web server got a request from [remote]. */
    fun request(remote: String?) {
        val hs = hotspot() ?: return
        // The run is read before the check: the main thread may stop it and start another before the post runs
        // (CodeRabbit on #465), and a request seen in one run says nothing about the next.
        val run = hs.runId
        if (hs.isOn && !hs.phoneSeen && isClient(remote)) post { hs.phoneJoined(run) }
    }
}

/**
 * The setup hotspot on the panel (#197): runs [SetupHotspotPolicy] and keeps [hotspot] on exactly while it
 * says so. The setup screen draws [view], and its Stop calls [cancel]. Wi-Fi details from any channel call
 * [configured] before the panel joins: the hotspot is off by the time it returns, so the radio is free.
 *
 * Every way out stops the hotspot: connected, configured, 15 minutes, Stop, setup done ([cancel]), Android
 * ending it, and [close] when the service ends. [Hotspot.stop] puts back what the start changed.
 *
 * One thread (the main one on the panel). [schedule] runs a callback after a delay and returns its cancel.
 */
class SetupHotspot(
    private val hotspot: Hotspot,
    private val connected: () -> Boolean,
    private val clock: () -> Long,
    private val schedule: (delayMs: Long, run: () -> Unit) -> () -> Unit,
    private val onChange: () -> Unit = {},
    private val log: (String) -> Unit = {},
    graceMs: Long = 3 * 60_000L,
    maxOnMs: Long = 15 * 60_000L,
    /**
     * While on, [connected] is read again at least this often. The caller calls [refresh] on every network change;
     * this is rule 2's net if a change goes unheard (a callback Android didn't send).
     */
    private val checkMs: Long = 30_000L,
    /**
     * Why the hotspot can't do its job now, or null when it can. While set it doesn't start: on Android 10+ an app
     * can't turn a Wi-Fi radio on, so with Wi-Fi switched off the panel couldn't join what the phone sends (S1 panel
     * run on e7d2d49d). Read before a start only (never while ours is starting or on), and at least every [checkMs]
     * while set.
     */
    private val blocked: () -> HotspotFailure? = { null },
    /**
     * Runs before each start, then calls its argument to start. On most devices the radio can't scan while it is the
     * access point, so the panel scans here, while it still can, and the setup page lists those networks (S1 panel run
     * on e7d2d49d). A stop while it runs means no start.
     */
    private val beforeStart: (start: () -> Unit) -> Unit = { it() },
    /**
     * Before rule 1 starts the hotspot by itself: switches a Wi-Fi radio that is off on, where the app may, and says
     * true when it did. Then no saved network could have joined while the rule waited, so it waits again from now
     * ([SetupHotspotPolicy.radioSwitchedOn]) and the hotspot doesn't take the radio from the network as it joins (the
     * Mirror, 2026-11-25: Wi-Fi off after a failed driver load, the hotspot switched it on and started 4 s later, and
     * held the radio its 15 minutes). An asked start doesn't wait.
     */
    private val switchRadioOn: () -> Boolean = { false },
) {
    /** What the setup screen shows. */
    sealed interface View {
        /** Off. [stopped]: why it last went off. [error]: why the last start failed. */
        data class Off(val stopped: Stop?, val error: HotspotFailure?) : View

        data object Starting : View

        /**
         * On as [network], for [leftMs] more. [asked]: from the setup screen. [phone]: a phone reached the panel over
         * it ([phoneJoined]), so the screen can show the setup page's address instead of how to join.
         */
        data class On(val network: HotspotNetwork, val leftMs: Long, val asked: Boolean, val phone: Boolean = false) : View
    }

    private val policy = SetupHotspotPolicy(clock(), graceMs, maxOnMs)

    /** The hotspot as it is: null off, a start under way, or up as a network. */
    private var run: Run? = null

    private class Run(var network: HotspotNetwork? = null)

    private var error: HotspotFailure? = null

    /** The hotspot is up now. Any thread may read it (the setup page's pairing gate does); the rest is main-thread only. */
    @Volatile var isOn: Boolean = false
        private set

    /** A phone reached the panel over this run of the hotspot ([phoneJoined]). Any thread may read it. */
    @Volatile var phoneSeen: Boolean = false
        private set

    /** Which run of the hotspot this is: a new start changes it. Any thread may read it. */
    @Volatile var runId: Int = 0
        private set
    private var cancelWake: (() -> Unit)? = null
    private var closed = false

    /** The last refresh found it [blocked]: the rule waited, and starts over when the block lifts. */
    private var wasBlocked = false

    val view: View get() {
        val r = run
        val net = r?.network
        val on = policy.state as? SetupHotspotPolicy.State.On
        return when {
            r == null || on == null -> View.Off(policy.lastStop, error)
            net == null -> View.Starting
            else -> View.On(net, policy.leftMs(clock()) ?: 0, on.asked, phoneSeen)
        }
    }

    /** Starts the run: rule 1's clock began when this was made. Call [refresh] on each change in connectivity. */
    fun start() = refresh()

    /** Connectivity changed, or a wake is due. */
    fun refresh() {
        if (closed) return
        // Only before a start. Once ours is starting or on, nothing here stops it: on a device without STA+AP
        // concurrency Android parks the Wi-Fi client while our AP runs, which can read like Wi-Fi off (S1 retest on
        // b8f827fe: our own hotspot was stopped 25 s in).
        val block = if (run == null) blocked() else null
        if (block != null) {
            // The rule waits: nothing starts, and the screen says why. Read again soon.
            wasBlocked = true
            error = block
            cancelWake?.invoke()
            cancelWake = schedule(checkMs) { cancelWake = null; refresh() }
            onChange()
            return
        }
        if (wasBlocked) {
            // Back (Wi-Fi turned on): as after new Wi-Fi details, the saved network gets its minutes first. Only for a
            // rule that was counting or on: one Close it (or setup's end) left Idle stays closed (#418 verifier, A-N1).
            wasBlocked = false
            error = null
            if (policy.state != SetupHotspotPolicy.State.Idle) policy.configured(clock())
        }
        var d = policy.update(connected(), clock())
        val rule = policy.state
        if (d.on && run == null && rule is SetupHotspotPolicy.State.On && !rule.asked && switchRadioOn()) {
            log("setup hotspot: Wi-Fi was off, so it switched it on; the saved network gets its minutes first")
            policy.radioSwitchedOn(clock())
            d = policy.update(connected(), clock())
        }
        apply(d)
    }

    /** The setup screen asks for the hotspot. False while the panel is connected (rule 2). */
    fun ask(): Boolean {
        if (closed) return false
        error = null
        // As in refresh: while ours runs the parked client isn't Wi-Fi off, so don't read it then.
        if (run == null) blocked()?.let { error = it; onChange(); return false }
        if (!policy.ask(connected(), clock())) return false
        refresh()
        return true
    }

    /**
     * A device on the hotspot's network reached the panel (a request to its setup page, or a phone's captive check).
     * Android 10 gives an app no list of a hotspot's clients, so this is the proof that a phone joined. [run]: the
     * [runId] the device was seen in. Once per run; ignored while the hotspot isn't on, or once another run started.
     */
    fun phoneJoined(run: Int) {
        if (run != runId || !isOn || phoneSeen) return
        phoneSeen = true
        log("setup hotspot: a phone reached the panel over it")
        onChange()
    }

    /** Stop on the setup screen, or setup finished or cancelled. */
    fun cancel() {
        policy.cancel()
        refresh()
    }

    /** Wi-Fi details arrived: the hotspot is off when this returns, so the panel can join. */
    fun configured() {
        policy.configured(clock())
        refresh()
    }

    /** The service is ending: off for good. */
    fun close() {
        closed = true
        cancelWake?.invoke()
        cancelWake = null
        if (run != null) stopHotspot("the service ended")
    }

    private fun apply(d: SetupHotspotPolicy.Decision) {
        if (d.on && run == null) startHotspot()
        else if (!d.on && run != null) stopHotspot(policy.lastStop?.name?.lowercase() ?: "off")
        cancelWake?.invoke()
        val wake = if (d.on) minOf(d.wakeInMs ?: checkMs, checkMs) else d.wakeInMs
        cancelWake = wake?.let { schedule(it.coerceAtLeast(0)) { cancelWake = null; refresh() } }
        onChange()
    }

    private fun startHotspot() {
        val mine = Run()
        run = mine
        error = null
        phoneSeen = false
        runId++
        log("setup hotspot starting")
        beforeStart {
            // Stopped (or replaced) while the scan ran: nothing to start.
            if (run !== mine || closed) return@beforeStart
            hotspot.start { e ->
                // An event from a start that has been stopped since belongs to nobody.
                if (run !== mine) return@start
                when (e) {
                    is Hotspot.Event.Started -> {
                        mine.network = e.network
                        isOn = true
                        log("setup hotspot on")
                        onChange()
                    }
                    is Hotspot.Event.Failed -> {
                        log("setup hotspot failed: ${e.why}")
                        run = null
                        isOn = false
                        error = e.why
                        policy.failed()
                        refresh()
                    }
                    Hotspot.Event.Stopped -> {
                        log("setup hotspot stopped by Android")
                        run = null
                        isOn = false
                        policy.androidStopped(clock())
                        refresh()
                    }
                }
            }
        }
    }

    private fun stopHotspot(why: String) {
        run = null
        isOn = false
        hotspot.stop()
        log("setup hotspot off ($why)")
    }
}
