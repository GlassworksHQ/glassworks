/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.bluetoothproxy

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * The Bluetooth adapter's MAC that Home Assistant keys the proxy's scanner on (`bluetooth_mac_address`,
 * #246), or null for the fallback: no field, and HA keys the scanner on the device's ESPHome MAC, as
 * before. Android 10 hides the adapter's MAC from apps, so the frame says where to read it ([read]; on
 * the S1, the root helper's file). A frame without a way gives none, and always gets the fallback.
 *
 * Settled the first time the proxy runs in a process ([value] is lazy) and then fixed, so HA never
 * sees the address change from one connection to the next. The first valid MAC is [kept], and wins
 * every later time, so a helper that fails or answers late later on can't flip HA back to the
 * fallback. So HA moves a panel's scanner to the adapter's address at most once (in place, the same
 * entry: the S1 run on #399, 2026-11-16): the first time the proxy runs after the helper has published.
 *
 * Every failure is the fallback: no file, an error, no answer within [timeoutMs], or a value that
 * isn't a real address (empty, malformed, all zero, broadcast, Android's `02:00:00:00:00:00`).
 */
class AdapterMac(
    private val read: () -> String?,
    private val kept: BluetoothProxyPrefs,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    /** The MAC (`AA:BB:CC:DD:EE:FF`), or null for the fallback. Reads [read] at most once. */
    val value: String? by lazy { settle() }

    private fun settle(): String? {
        normalized(kept.adapterMac)?.let { return it }
        val fresh = normalized(readWithin()) ?: return null
        kept.adapterMac = fresh
        return fresh
    }

    private fun readWithin(): String? {
        val task = FutureTask(read)
        Thread(task, "bt-adapter-mac").apply { isDaemon = true; start() }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            task.cancel(true)
            null
        }
    }

    companion object {
        /** Reading a small file: anything slower is something wrong. */
        const val TIMEOUT_MS = 2_000L
        private val FORMAT = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
        private val PLACEHOLDERS = setOf("00:00:00:00:00:00", "02:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")

        /** [s] as `AA:BB:CC:DD:EE:FF` if it is a real adapter address, else null. Whitespace around it is dropped. */
        fun normalized(s: String?): String? {
            val m = s?.trim()?.uppercase() ?: return null
            return m.takeIf { FORMAT.matches(it) && it !in PLACEHOLDERS }
        }
    }
}
