/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import dev.glassworks.platform.Hotspot
import dev.glassworks.platform.HotspotFailure
import dev.glassworks.platform.HotspotNetwork
import dev.glassworks.setup.SetupHotspotPolicy.Stop

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
) {
    /** What the setup screen shows. */
    sealed interface View {
        /** Off. [stopped]: why it last went off. [error]: why the last start failed. */
        data class Off(val stopped: Stop?, val error: HotspotFailure?) : View

        data object Starting : View

        /** On as [network], for [leftMs] more. [asked]: from the setup screen. */
        data class On(val network: HotspotNetwork, val leftMs: Long, val asked: Boolean) : View
    }

    private val policy = SetupHotspotPolicy(clock(), graceMs, maxOnMs)

    /** The hotspot as it is: null off, a start under way, or up as a network. */
    private var run: Run? = null

    private class Run(var network: HotspotNetwork? = null)

    private var error: HotspotFailure? = null

    /** The hotspot is up now. Any thread may read it (the setup page's pairing gate does); the rest is main-thread only. */
    @Volatile var isOn: Boolean = false
        private set
    private var cancelWake: (() -> Unit)? = null
    private var closed = false

    val view: View get() {
        val r = run
        val net = r?.network
        val on = policy.state as? SetupHotspotPolicy.State.On
        return when {
            r == null || on == null -> View.Off(policy.lastStop, error)
            net == null -> View.Starting
            else -> View.On(net, policy.leftMs(clock()) ?: 0, on.asked)
        }
    }

    /** Starts the run: rule 1's clock began when this was made. Call [refresh] on each change in connectivity. */
    fun start() = refresh()

    /** Connectivity changed, or a wake is due. */
    fun refresh() {
        if (closed) return
        val d = policy.update(connected(), clock())
        apply(d)
    }

    /** The setup screen asks for the hotspot. False while the panel is connected (rule 2). */
    fun ask(): Boolean {
        if (closed) return false
        error = null
        if (!policy.ask(connected(), clock())) return false
        refresh()
        return true
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
        cancelWake = d.wakeInMs?.let { schedule(it.coerceAtLeast(0)) { cancelWake = null; refresh() } }
        onChange()
    }

    private fun startHotspot() {
        val mine = Run()
        run = mine
        error = null
        log("setup hotspot starting")
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
                    policy.failed()
                    refresh()
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
