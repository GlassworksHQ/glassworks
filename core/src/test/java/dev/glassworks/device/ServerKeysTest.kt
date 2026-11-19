/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.device

import dev.glassworks.esphome.ApiServer
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.esphome.NoiseKeys
import dev.glassworks.esphome.ReadableNoiseKeyStore
import dev.glassworks.esphome.StoredKey
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The S1 run of 2026-11-19 (#608, run B): a switch's hand-over must reach the API server whichever comes first, the
 * server's start or the key. Real [ApiServer] and [NoiseKeys] objects, never started (no socket).
 */
class ServerKeysTest {
    /** Made up: never a real key. */
    private val key = ByteArray(32) { (it + 3).toByte() }
    private val identity = DeviceIdentity("panel-test", "Panel test", "02:00:00:00:00:01", "2026.10.2", "", "Glassworks", "test", 0)

    /** The app's key store: the copied data's key while [held] is Unreadable, the handed-over key once it is Present. */
    private class Store(var held: StoredKey) : ReadableNoiseKeyStore {
        override fun read(): StoredKey = held
        override fun load(): ByteArray? = (held as? StoredKey.Present)?.key
        override fun save(key: ByteArray?): Boolean { held = key?.let { StoredKey.Present(it) } ?: StoredKey.Absent; return true }
    }

    private fun server(keys: NoiseKeys) = ApiServer(0, { identity }, emptyList(), keys, InetAddress.getLoopbackAddress())

    @Test fun aKeyHandedOverBeforeTheStartIsTheOneTheServerStartsWith() {
        val store = Store(StoredKey.Absent)
        val keys = ServerKeys(store) // made with the device, before the hand-over
        store.held = StoredKey.Present(key) // the hand-over, 144 ms before the start in run B
        val made = keys.keys
        assertNull("what run B's server started with: the keys read when the device was made", made.latest)
        val s = server(keys.forStart())
        assertTrue(s.keyed)
        assertArrayEquals(key, keys.keys.latest)
        assertFalse(keys.keys.provisionable)
    }

    @Test fun aKeyHandedOverAfterTheStartReachesTheLockedServer() {
        // the copied key can't be read yet: the server starts locked, refusing every client
        val store = Store(StoredKey.Unreadable)
        val keys = ServerKeys(store)
        val s = server(keys.forStart())
        assertTrue(keys.keys.locked)
        assertFalse("nothing stored that the settings can read: it stays locked", keys.follow(null, s))
        assertTrue(keys.keys.locked)
        store.held = StoredKey.Present(key)
        assertTrue(keys.follow(key, s))
        assertFalse(keys.keys.locked)
        assertTrue(keys.keys.hasKey && s.keyed)
        assertArrayEquals(key, keys.keys.latest)
        assertFalse("the same key again changes nothing", keys.follow(key, s))
    }

    @Test fun aKeyHandedOverAfterTheStartReachesAnUnprovisionedServer() {
        // an app that started with no key at all (no marker, or a key removed as lost): plaintext and the zero PSK
        val store = Store(StoredKey.Absent)
        val keys = ServerKeys(store)
        val s = server(keys.forStart())
        assertTrue(keys.keys.provisionable)
        store.held = StoredKey.Present(key)
        assertTrue(keys.follow(key, s))
        assertFalse(keys.keys.provisionable)
        assertTrue(s.keyed)
    }

    @Test fun withNoServerTheStartReadsIt() {
        val store = Store(StoredKey.Absent)
        val keys = ServerKeys(store)
        store.held = StoredKey.Present(key)
        assertFalse("no server yet: nothing to hand it to", keys.follow(key, null))
        assertArrayEquals(key, keys.forStart().latest)
    }
}
