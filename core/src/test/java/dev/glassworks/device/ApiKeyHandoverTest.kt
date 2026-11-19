/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.device

import dev.glassworks.esphome.ReadableNoiseKeyStore
import dev.glassworks.esphome.StoredKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/** #574: a reflash carries the ESPHome API key over adb, so Home Assistant reconnects with no re-authentication. */
class ApiKeyHandoverTest {
    /** Made-up keys: never a real one in a test. */
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val other = ByteArray(32) { (100 - it).toByte() }
    private val b64 = Base64.getEncoder().encodeToString(key)

    private class Store(var held: StoredKey = StoredKey.Absent, val saves: Boolean = true) : ReadableNoiseKeyStore {
        var saved = 0
        override fun read(): StoredKey = held
        override fun load(): ByteArray? = (held as? StoredKey.Present)?.key
        override fun save(key: ByteArray?): Boolean {
            if (!saves) return false
            saved++
            held = key?.let { StoredKey.Present(it) } ?: StoredKey.Absent
            return true
        }
    }

    private val logged = mutableListOf<String>()
    private fun handover(store: Store) = ApiKeyHandover(store, { Base64.getEncoder().encodeToString(it) },
        { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }, { logged += it })

    @Test fun getGivesTheKeyHomeAssistantProvisioned() {
        assertEquals("key $b64", handover(Store(StoredKey.Present(key))).handle("get"))
        assertEquals("none", handover(Store()).handle("get"))
        assertEquals("unreadable", handover(Store(StoredKey.Unreadable)).handle("get"))
    }

    @Test fun setFillsTheFreshAppsEmptyStore() {
        val s = Store()
        assertEquals("ok", handover(s).handle("set $b64"))
        assertArrayEquals(key, (s.held as StoredKey.Present).key)
    }

    @Test fun setNeverReplacesAKeyInUse() {
        val s = Store(StoredKey.Present(other))
        assertTrue(handover(s).handle("set $b64").startsWith("refused "))
        assertArrayEquals(other, (s.held as StoredKey.Present).key)
        assertEquals(0, s.saved)
        // the same key again is fine (a retried hand-over), and writes nothing
        assertEquals("ok", handover(Store(StoredKey.Present(key))).handle("set $b64"))
        assertTrue(handover(Store(StoredKey.Unreadable)).handle("set $b64").startsWith("refused "))
    }

    @Test fun setRefusesWhatIsntAKey() {
        val s = Store()
        val h = handover(s)
        for (bad in listOf("set", "set not-base64!", "set ${Base64.getEncoder().encodeToString(ByteArray(16) { 1 })}",
            "set ${Base64.getEncoder().encodeToString(ByteArray(32))}")) {
            assertTrue(bad, h.handle(bad).startsWith("refused "))
        }
        assertEquals(StoredKey.Absent, s.held)
        assertTrue(handover(Store(saves = false)).handle("set $b64").startsWith("refused "))
        assertTrue(h.handle("remove").startsWith("refused "))
    }

    @Test fun twoSetsAtOnceCantBothFillTheStore() {
        // the first read waits (up to 1 s) for the second set to arrive: without the lock both see an empty store
        val inside = java.util.concurrent.CountDownLatch(2)
        val s = object : ReadableNoiseKeyStore {
            @Volatile var held: StoredKey = StoredKey.Absent
            override fun read(): StoredKey { inside.countDown(); inside.await(1, java.util.concurrent.TimeUnit.SECONDS); return held }
            override fun load(): ByteArray? = null
            override fun save(key: ByteArray?): Boolean { held = StoredKey.Present(key!!); return true }
        }
        val h = ApiKeyHandover(s, { Base64.getEncoder().encodeToString(it) }, { runCatching { Base64.getDecoder().decode(it) }.getOrNull() })
        val replies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads = listOf(key, other).map { k -> Thread { replies += h.handle("set ${Base64.getEncoder().encodeToString(k)}") } }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }
        assertEquals(1, replies.count { it == "ok" })
        assertEquals(1, replies.count { it.startsWith("refused ") })
    }

    @Test fun theKeyIsNeverLogged() {
        handover(Store(StoredKey.Present(key))).handle("get")
        handover(Store()).handle("set $b64")
        assertEquals(2, logged.size)
        assertFalse(logged.any { it.contains(b64) })
    }

    @Test fun servesOneReplyPerLine() {
        val out = ByteArrayOutputStream()
        handover(Store()).serve(ByteArrayInputStream("set $b64\n\nget\n".toByteArray()), out)
        assertEquals("ok\nkey $b64\n", out.toString("US-ASCII"))
    }
}
