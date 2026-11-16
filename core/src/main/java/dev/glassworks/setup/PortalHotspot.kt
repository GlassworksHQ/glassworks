/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import dev.glassworks.platform.Hotspot

/**
 * A [Hotspot] with its captive portal (#197): [portal] runs exactly while [hotspot] is up, on the hotspot's addresses.
 * It starts when Android says the hotspot is up and stops with it, however it ends: [stop], Android ending it, or a
 * failed start. Android 10 gives the hotspot its IPv6 address a moment after its IPv4 one, so the addresses are read
 * again [recheckMs] after the start, and the portal moves to them if they changed.
 *
 * One thread, the one the hotspot's events come on. [schedule] runs a callback after a delay and returns its cancel.
 */
class PortalHotspot(
    private val hotspot: Hotspot,
    private val portal: Portal,
    private val schedule: (delayMs: Long, run: () -> Unit) -> () -> Unit,
    private val addresses: () -> PortalAddresses? = PortalAddresses::now,
    private val log: (String) -> Unit = {},
    private val recheckMs: Long = 3_000L,
) : Hotspot {
    private var at: PortalAddresses? = null
    private var cancelRecheck: (() -> Unit)? = null

    override fun start(onEvent: (Hotspot.Event) -> Unit) {
        off()
        hotspot.start { e ->
            if (e is Hotspot.Event.Started) on() else off()
            onEvent(e)
        }
    }

    override fun stop() {
        off()
        hotspot.stop()
    }

    private fun on() {
        move()
        cancelRecheck = schedule(recheckMs) { cancelRecheck = null; move() }
    }

    /** Runs the portal on the addresses the hotspot has now. */
    private fun move() {
        val now = addresses()
        if (now == null) { log("portal: the hotspot has no address yet"); return }
        if (now == at) return
        at = now
        portal.start(now)
    }

    private fun off() {
        cancelRecheck?.invoke()
        cancelRecheck = null
        at = null
        portal.stop()
    }
}
