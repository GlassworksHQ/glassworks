/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.dock

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import dev.glassworks.frame.Dock
import dev.glassworks.platform.PlatformApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** [Docked.of] for every Android the app runs on, every power source and every dock state; and [Docked.hasEntity]. */
class DockedTest {
    private val sources = mapOf(
        "battery" to 0,
        "AC" to BatteryManager.BATTERY_PLUGGED_AC,
        "USB" to BatteryManager.BATTERY_PLUGGED_USB,
        "wireless" to BatteryManager.BATTERY_PLUGGED_WIRELESS,
        "dock" to 8, // BatteryManager.BATTERY_PLUGGED_DOCK (Android 13), written out so a changed constant shows here
    )
    private val dockStates = mapOf(
        "no dock broadcast" to null,
        "undocked" to Intent.EXTRA_DOCK_STATE_UNDOCKED,
        "desk" to Intent.EXTRA_DOCK_STATE_DESK,
        "car" to Intent.EXTRA_DOCK_STATE_CAR,
        "low-end desk" to Intent.EXTRA_DOCK_STATE_LE_DESK,
        "high-end desk" to Intent.EXTRA_DOCK_STATE_HE_DESK,
    )

    @Test fun theDecisionTable() {
        for (api in 23..36) for ((source, plugged) in sources) for ((state, dockState) in dockStates) {
            val expected = (source == "dock" && api >= 33) || (dockState != null && state != "undocked")
            assertEquals("API $api, $source, $state", expected, Docked.of(PlatformApi(api), plugged, dockState))
        }
    }

    /** The Pixel Tablet on its dock, as read on one (2026-11-17): Dock powered, and no dock broadcast assumed. */
    @Test fun aPixelTabletOnItsDock() {
        assertEquals(true, Docked.of(PlatformApi(34), 8, null))
        assertEquals("lifted off: on battery", false, Docked.of(PlatformApi(34), 0, null))
        assertEquals("lifted off onto a USB cable", false, Docked.of(PlatformApi(34), BatteryManager.BATTERY_PLUGGED_USB, null))
    }

    @Test fun whoGetsTheEntity() {
        for (seen in listOf(false, true)) {
            assertEquals("never on a frame that never docks (seen=$seen)", false, Docked.hasEntity(Dock.NO, seen))
            assertEquals("always on a frame that docks (seen=$seen)", true, Docked.hasEntity(Dock.YES, seen))
            assertEquals("on any other device once it has docked (seen=$seen)", seen, Docked.hasEntity(Dock.IF_SEEN, seen))
        }
    }
}

/** [DockWatcher] on Robolectric's broadcasts: the sticky values at start, then each change, and nothing after stop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DockWatcherTest {
    private val app = RuntimeEnvironment.getApplication()
    private val heard = ArrayList<Boolean>()

    private fun battery(plugged: Int) = Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
    private fun dockEvent(state: Int) = Intent(Intent.ACTION_DOCK_EVENT).putExtra(Intent.EXTRA_DOCK_STATE, state)

    @Suppress("DEPRECATION") // how Android's own services send these two
    private fun send(intent: Intent) {
        app.sendStickyBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun watcher() = DockWatcher(app, PlatformApi(34)) { heard += it }

    @Test fun startsFromTheStickyBatteryBroadcast() {
        send(battery(BatteryManager.BATTERY_PLUGGED_DOCK))
        val w = watcher().also { it.start() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, w.docked)
        assertEquals("one call at start; the same value again is no change", listOf(true), heard)
        w.stop()
    }

    @Test fun followsTheTabletOffItsDockAndBack() {
        send(battery(BatteryManager.BATTERY_PLUGGED_DOCK))
        val w = watcher().also { it.start() }
        send(battery(0))
        send(battery(0)) // a battery level change: no new value
        send(battery(BatteryManager.BATTERY_PLUGGED_DOCK))
        assertEquals(listOf(true, false, true), heard)
        w.stop()
        send(battery(0))
        assertEquals("nothing after stop", listOf(true, false, true), heard)
    }

    @Test fun followsTheDockBroadcast() {
        send(battery(BatteryManager.BATTERY_PLUGGED_AC))
        val w = watcher().also { it.start() }
        send(dockEvent(Intent.EXTRA_DOCK_STATE_DESK))
        send(dockEvent(Intent.EXTRA_DOCK_STATE_UNDOCKED))
        assertEquals(listOf(false, true, false), heard)
        w.stop()
    }

    /** Below Android 13 the battery's value 8 means nothing; only the dock broadcast counts. */
    @Test fun belowAndroid13OnlyTheDockBroadcastCounts() {
        send(battery(8))
        val w = DockWatcher(app, PlatformApi(32)) { heard += it }.also { it.start() }
        send(dockEvent(Intent.EXTRA_DOCK_STATE_DESK))
        assertEquals(listOf(false, true), heard)
        w.stop()
    }
}
