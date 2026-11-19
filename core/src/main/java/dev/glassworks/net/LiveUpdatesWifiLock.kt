/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import dev.glassworks.platform.Platform
import dev.glassworks.platform.PlatformApi

/**
 * Keeps Wi-Fi out of power save while the dashboard is on the screen, so a change from Home Assistant shows when it
 * arrives, not at the next beacon (#644). In power save the radio sleeps between beacons, about 100 ms, and a packet
 * waits for the next one: the Pixel Tablet's live updates took 65 to 90 ms at p95 to reach the page.
 *
 * Held while the app is in front (its activity is started) and the screen is on, and released as soon as either
 * ends. Always on keeps the screen on, so it keeps the lock; a dimmed screen is still on. The lock is not
 * reference-counted: one release undoes any number of acquires. Android drops it when the process dies.
 * The mode is [PlatformApi.wifiLatencyLockMode]. Panels are plugged in, so the power it costs doesn't matter.
 *
 * Main thread.
 */
class LiveUpdatesWifiLock(context: Context, platform: PlatformApi = Platform) {
    private val lock: WifiManager.WifiLock? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(platform.wifiLatencyLockMode, TAG)
            ?.apply { setReferenceCounted(false) }
    }.onFailure { Log.w(LOG, "no Wi-Fi lock for live updates", it) }.getOrNull()

    val held: Boolean get() = lock?.isHeld == true

    /** Takes or drops the lock for the app's state now. Calling it again with the same state changes nothing. */
    fun update(inFront: Boolean, screenOn: Boolean) {
        val l = lock ?: return
        val want = inFront && screenOn
        if (want == l.isHeld) return
        try {
            if (want) l.acquire() else l.release()
        } catch (e: RuntimeException) {
            // A missing WAKE_LOCK permission, or Android's cap on Wi-Fi locks: the panel works as before, only slower.
            Log.w(LOG, "Wi-Fi lock for live updates: ${if (want) "acquire" else "release"} failed", e)
            return
        }
        Log.i(LOG, "Wi-Fi lock for live updates ${if (want) "held" else "released"}")
    }

    companion object {
        /** The lock's tag, as `dumpsys wifi` lists it. */
        const val TAG = "glassworks:live-updates"
        private const val LOG = "Glassworks.Wifi"
    }
}
