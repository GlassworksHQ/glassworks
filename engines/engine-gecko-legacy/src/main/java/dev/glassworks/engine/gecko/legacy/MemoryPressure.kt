/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.mozilla.gecko.GeckoAppShell

/**
 * Gecko's `memory-pressure` ("low-memory"), told again every [INTERVAL_MS] for as long as Android says memory is low
 * (#684). GeckoView 144's MemoryController tells Gecko only when `onTrimMemory` comes, and Android 6 calls that only
 * when the process's trim level rises. On the Mirror the level reaches RUNNING_CRITICAL soon after start and stays
 * there for hours, so Gecko hears it once, and the page's JS heap and the compositor's textures grow back until the
 * lowmemorykiller takes the page. Each notice runs a GC and a cycle collection in the page's process (the parent
 * forwards it there as FlushMemory) and clears WebRender's caches in the app's process, where the legacy engine
 * composites (Gecko 144's ContentParent, nsJSEnvironment, CompositorBridgeParent; read in
 * frames/lululemon-mirror/research/mirror-memory.md).
 *
 * [trimLevel] is Android's current level for this process; [notify] tells Gecko; [later] runs a task after a delay.
 */
internal class MemoryPressure(
    private val trimLevel: () -> Int,
    private val notify: () -> Unit,
    private val later: (Long, () -> Unit) -> Unit,
    private val log: (String) -> Unit,
) {
    fun start() = later(INTERVAL_MS, ::tick)

    private fun tick() {
        val level = trimLevel()
        if (isLow(level)) {
            notify()
            log("memory-pressure again: Android's trim level is $level")
        }
        start()
    }

    companion object {
        /** About 8 MB of the app's growth on the Mirror (80 MB in 20 minutes, #684) between two notices. */
        const val INTERVAL_MS = 2 * 60_000L

        /**
         * Android says memory is short: RUNNING_LOW or RUNNING_CRITICAL in front, or any level for a process in the
         * background list. RUNNING_MODERATE isn't short yet, and UI_HIDDEN only says the app's window went.
         */
        fun isLow(level: Int) =
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

        /** The app's process, on the main thread. */
        fun forThisProcess(logTag: String) = MemoryPressure(
            trimLevel = { ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).lastTrimLevel },
            notify = { GeckoAppShell.notifyObservers("memory-pressure", "low-memory") },
            later = { ms, task -> Handler(Looper.getMainLooper()).postDelayed(task, ms) },
            log = { Log.i(logTag, it) },
        )
    }
}
