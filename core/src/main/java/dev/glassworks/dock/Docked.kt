/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.dock

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dev.glassworks.frame.Dock
import dev.glassworks.platform.Platform
import dev.glassworks.platform.PlatformApi

/** Whether the device sits on a dock, and whether Home Assistant gets the *Docked* sensor (docs/esphome.md#docked). */
object Docked {
    /**
     * On a dock when the battery charges from one ([plugged], the battery broadcast's `EXTRA_PLUGGED`, where
     * [PlatformApi.hasDockPowerSource]), or when the last dock broadcast's `EXTRA_DOCK_STATE` ([dockState]) is any
     * dock. A Pixel Tablet on its dock says `BATTERY_PLUGGED_DOCK` (read on one, 2026-11-17). Null [dockState]: no
     * dock broadcast yet.
     */
    @SuppressLint("InlinedApi") // a constant, read only where the platform has it
    fun of(platform: PlatformApi, plugged: Int, dockState: Int?): Boolean =
        (platform.hasDockPowerSource && plugged == BatteryManager.BATTERY_PLUGGED_DOCK) ||
            (dockState != null && dockState != Intent.EXTRA_DOCK_STATE_UNDOCKED)

    /** Home Assistant gets the sensor on a frame that docks, or that might and has been [seen] on a dock. */
    fun hasEntity(dock: Dock, seen: Boolean): Boolean = dock == Dock.YES || (dock == Dock.IF_SEEN && seen)
}

/**
 * Follows [Docked.of] from the two sticky broadcasts, `ACTION_BATTERY_CHANGED` and `ACTION_DOCK_EVENT`, while
 * started. Android delivers both to a registered receiver only while the app runs: nothing polls or wakes the
 * device. [onChange] runs on the main thread, after [start] and on each change.
 */
class DockWatcher(
    private val context: Context,
    private val platform: PlatformApi = Platform,
    private val onChange: (Boolean) -> Unit,
) {
    private var plugged = 0
    private var dockState: Int? = null
    /** What [onChange] last heard; null before [start]. */
    private var reported: Boolean? = null
    /** On a dock; null until [start] has read the sticky broadcasts, so nobody reads a guess. */
    @Volatile var docked: Boolean? = null
        private set
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) { read(intent); update() }
    }

    /** Main thread. Reads the sticky values now, so [docked] is right at once. */
    fun start() {
        if (started) return
        started = true
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { read(it) }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_DOCK_EVENT))?.let { read(it) }
        update()
    }

    /** Main thread. */
    fun stop() {
        if (!started) return
        started = false
        reported = null
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun read(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BATTERY_CHANGED -> plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            Intent.ACTION_DOCK_EVENT -> dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_UNDOCKED)
        }
    }

    /** Tells [onChange] when the answer differs from what it last heard (always the first time). */
    private fun update() {
        val now = Docked.of(platform, plugged, dockState)
        docked = now
        if (now == reported) return
        reported = now
        onChange(now)
    }
}
