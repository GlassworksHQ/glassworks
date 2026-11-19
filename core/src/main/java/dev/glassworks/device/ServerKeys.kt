/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.device

import dev.glassworks.esphome.ApiServer
import dev.glassworks.esphome.NoiseKeys
import dev.glassworks.esphome.ReadableNoiseKeyStore

/**
 * The API server's [NoiseKeys], kept in step with the stored key whichever comes first: the server's start, or a key
 * stored from outside the API (the installer's hand-over, an adb extra, the settings). A key stored before the start
 * is read at the start, not when the device was made; one stored after reaches the running server.
 *
 * The S1 run of 2026-11-19 (#608) needed both: the switch handed the key over 144 ms before the server started, with
 * keys read earlier, so the server came up with no key, and the settings listener, registered after the start, never
 * heard of the key. PanelDevice calls it on esphome-main.
 */
class ServerKeys(private val store: ReadableNoiseKeyStore) {
    /** The keys the server uses, or will use. */
    @Volatile var keys: NoiseKeys = NoiseKeys(store)
        private set

    /** The keys for a server starting now: read from the store now. */
    fun forStart(): NoiseKeys = NoiseKeys(store).also { keys = it }

    /**
     * The stored key may have changed: [stored] is the key in use by the settings, or null for none. When it differs
     * from the keys' latest, [server], if one runs, gets it (its sessions are dropped, as ESPHome does on a new key).
     * True when the server got a new key. With no server, the next [forStart] reads the store anyway.
     */
    fun follow(stored: ByteArray?, server: ApiServer?): Boolean {
        val latest = keys.latest
        val same = if (stored == null || latest == null) stored == null && latest == null else stored.contentEquals(latest)
        if (same || server == null) return false
        server.replaceKey(stored)
        return true
    }
}
