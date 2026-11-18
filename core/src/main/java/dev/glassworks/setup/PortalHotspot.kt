/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import dev.glassworks.platform.Hotspot

/**
 * A [Hotspot] with its captive portal (#197): [portal] runs exactly while [hotspot] is up, on the hotspot's addresses.
 * It starts when Android says the hotspot is up and stops with it, however it ends: [stop], Android ending it, or a
 * failed start. While the hotspot is up the addresses are read again every [recheckMs]: Android 10 gives the hotspot
 * its IPv6 address a moment after its IPv4 one, and the first read can come before either. The portal starts once
 * there is an address, moves when they change, and starts again when a listener didn't come up (a port taken).
 *
 * One thread, the one the hotspot's events come on. [schedule] runs a callback after a delay and returns its cancel.
 */
class PortalHotspot(
    private val hotspot: Hotspot,
    private val portal: Portal,
    private val schedule: (delayMs: Long, run: () -> Unit) -> () -> Unit,
    /** The hotspot's addresses now: `{ PortalAddresses.now(context) }` on a device. */
    private val addresses: () -> PortalAddresses?,
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

    /** Runs the portal on the addresses the hotspot has now, then again every [recheckMs] until [off]. */
    private fun on() {
        move()
        cancelRecheck = schedule(recheckMs) { cancelRecheck = null; on() }
    }

    private fun move() {
        val now = addresses()
        // No address (yet, or any more): nothing may keep listening, a partial start's listeners included.
        if (now == null) { at = null; portal.stop(); return }
        if (now == at) return
        // Remembered only once every listener is up, so a start that missed one is tried again on the next read.
        at = if (portal.start(now)) now else null
    }

    private fun off() {
        cancelRecheck?.invoke()
        cancelRecheck = null
        at = null
        portal.stop()
    }
}
