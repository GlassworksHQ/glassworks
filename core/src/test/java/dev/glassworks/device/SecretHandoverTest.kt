/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.device

import dev.glassworks.esphome.StoredKey
import dev.glassworks.secrets.SecretPrefs
import dev.glassworks.secrets.SecretPrefs.CarryOver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * #574: a reflash carries the ESPHome API key over adb. #584: a signing-key switch carries it and the Home Assistant
 * sign-in, so the panel stays signed in and Home Assistant reconnects with no re-authentication.
 */
class SecretHandoverTest {
    /** Made-up values: never a real key or token in a test. */
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val other = ByteArray(32) { (100 - it).toByte() }
    private val b64 = enc(key)
    private val token = "made-up-refresh-token-0123456789abcdef"
    private val tokenB64 = enc(token.toByteArray())

    private companion object {
        fun enc(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    }

    /**
     * The app's two secrets. A held value that is lost reads as unreadable (sealed with a key this app no longer has,
     * as after a switch) and is replaced by a carry-over; [down] is a key store outage, where nothing is.
     */
    private class Store(
        var key: ByteArray? = null,
        var token: String? = null,
        lost: Boolean = false,
        var down: Boolean = false,
        var saves: Boolean = true,
        var signInHere: Boolean = true,
    ) : HandoverStore {
        var keyLost = lost
        var tokenLost = lost
        var written = 0
        override fun apiKey(): StoredKey = key?.let { if (keyLost || down) StoredKey.Unreadable else StoredKey.Present(it) } ?: StoredKey.Absent
        override fun signIn(): SecretPrefs.Secret = token?.let { if (tokenLost || down) SecretPrefs.Secret.Unavailable else SecretPrefs.Secret.Value(it) }
            ?: SecretPrefs.Secret.Absent
        private fun <T> carry(held: T?, lost: Boolean, value: T, same: (T, T) -> Boolean, put: (T) -> Unit): CarryOver = when {
            down -> CarryOver.UNAVAILABLE
            held != null && !lost && same(held, value) -> CarryOver.SAME
            held != null && !lost -> CarryOver.DIFFERENT
            !saves -> CarryOver.NOT_STORED
            else -> { put(value); written++; CarryOver.STORED }
        }
        override fun carryOverApiKey(key: ByteArray) =
            carry(this.key, keyLost, key, { a, b -> a.contentEquals(b) }) { this.key = it; keyLost = false }
        override fun carryOverSignIn(token: String): CarryOver? = if (!signInHere) null
            else carry(this.token, tokenLost, token, { a, b -> a == b }) { this.token = it; tokenLost = false }
    }

    private val logged = mutableListOf<String>()
    private var signIns = 0
    private fun handover(store: Store) = SecretHandover(store, ::enc, { runCatching { Base64.getDecoder().decode(it) }.getOrNull() },
        { logged += it }, { signIns++ })

    @Test fun saysWhatItCarriesWithoutASecret() {
        assertEquals("carries api_key signin", handover(Store(key, token)).handle("carries"))
    }

    @Test fun getGivesTheKeyHomeAssistantProvisioned() {
        assertEquals("key $b64", handover(Store(key)).handle("get"))
        assertEquals("none", handover(Store()).handle("get"))
        assertEquals("unreadable", handover(Store(key, down = true)).handle("get"))
    }

    @Test fun getSignInGivesTheRefreshToken() {
        assertEquals("signin $tokenB64", handover(Store(token = token)).handle("get-signin"))
        assertEquals("none", handover(Store()).handle("get-signin"))
        assertEquals("unreadable", handover(Store(token = token, down = true)).handle("get-signin"))
    }

    @Test fun setFillsTheFreshAppsEmptyStore() {
        val s = Store()
        assertEquals("ok", handover(s).handle("set $b64"))
        assertArrayEquals(key, s.key)
    }

    @Test fun aSwitchReplacesWhatTheDeletedKeySealed() {
        // the copied data: both secrets stored, sealed with the old install's key, which Android deleted
        val s = Store(other, "made-up-old-token", lost = true)
        val h = handover(s)
        assertEquals("ok", h.handle("set $b64"))
        assertEquals("ok", h.handle("set-signin $tokenB64"))
        assertArrayEquals(key, s.key)
        assertEquals(token, s.token)
        assertEquals("the screens hear about the sign-in once", 1, signIns)
        // a retried hand-over is fine, and writes nothing
        assertEquals("ok", h.handle("set $b64"))
        assertEquals("ok", h.handle("set-signin $tokenB64"))
        assertEquals(2, s.written)
        assertEquals("a retried sign-in tells the screens again, in case the first never did", 2, signIns)
    }

    @Test fun setNeverReplacesAValueInUse() {
        val s = Store(other, "made-up-other-token")
        val h = handover(s)
        assertTrue(h.handle("set $b64").startsWith("refused a different key"))
        assertTrue(h.handle("set-signin $tokenB64").startsWith("refused a different sign-in"))
        assertArrayEquals(other, s.key)
        assertEquals("made-up-other-token", s.token)
        assertEquals(0, s.written)
        assertEquals(0, signIns)
    }

    @Test fun anOutageChangesNothing() {
        val s = Store(other, "made-up-other-token", down = true)
        assertTrue(handover(s).handle("set $b64").startsWith("refused the key store"))
        assertTrue(handover(s).handle("set-signin $tokenB64").startsWith("refused the key store"))
        assertEquals(0, s.written)
    }

    @Test fun theSignInGoesOnlyIntoASignInTheDataHolds() {
        // no Home Assistant address or client id here: the data didn't come across, so the token signs in nowhere
        val s = Store(signInHere = false)
        assertEquals("refused no sign-in here to carry it into", handover(s).handle("set-signin $tokenB64"))
        assertNull(s.token)
        assertEquals(0, signIns)
    }

    @Test fun setRefusesWhatIsntAKeyOrAToken() {
        val s = Store()
        val h = handover(s)
        for (bad in listOf("set", "set not-base64!", "set ${enc(ByteArray(16) { 1 })}", "set ${enc(ByteArray(32))}",
            "set-signin", "set-signin not-base64!", "set-signin ${enc("two words".toByteArray())}",
            "set-signin ${enc("line\nbreak".toByteArray())}", "set-signin ${enc(ByteArray(SecretHandover.MAX_TOKEN_BYTES + 1) { 0x61 })}")) {
            assertTrue(bad, h.handle(bad).startsWith("refused "))
        }
        assertNull(s.key)
        assertNull(s.token)
        assertTrue(handover(Store(saves = false)).handle("set $b64").startsWith("refused the key can't be stored"))
        assertTrue(h.handle("remove").startsWith("refused "))
        assertEquals("an older client's word for it", "refused unknown request", h.handle("get-sendspin"))
    }

    @Test fun twoSetsAtOnceCantBothFillTheStore() {
        // the first check waits (up to 1 s) for the second set to arrive: without the lock both see an empty store
        val inside = java.util.concurrent.CountDownLatch(2)
        val s = object : HandoverStore {
            @Volatile var held: ByteArray? = null
            override fun apiKey(): StoredKey = StoredKey.Absent
            override fun carryOverApiKey(key: ByteArray): CarryOver {
                val seen = held
                inside.countDown(); inside.await(1, java.util.concurrent.TimeUnit.SECONDS)
                return when {
                    seen == null -> { held = key; CarryOver.STORED }
                    seen.contentEquals(key) -> CarryOver.SAME
                    else -> CarryOver.DIFFERENT
                }
            }
            override fun signIn(): SecretPrefs.Secret = SecretPrefs.Secret.Absent
            override fun carryOverSignIn(token: String): CarryOver? = null
        }
        val h = SecretHandover(s, ::enc, { runCatching { Base64.getDecoder().decode(it) }.getOrNull() })
        val replies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads = listOf(key, other).map { k -> Thread { replies += h.handle("set ${enc(k)}") } }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }
        assertEquals(1, replies.count { it == "ok" })
        assertEquals(1, replies.count { it.startsWith("refused ") })
    }

    @Test fun noSecretIsEverLogged() {
        val s = Store(key, token)
        handover(s).handle("get")
        handover(s).handle("get-signin")
        handover(Store(lost = true)).handle("set $b64")
        handover(Store(lost = true)).handle("set-signin $tokenB64")
        assertEquals(4, logged.size)
        assertFalse(logged.any { it.contains(b64) || it.contains(token) || it.contains(tokenB64) })
        assertTrue("a fingerprint instead", logged.all { Regex("fingerprint [0-9a-f]{4}").containsMatchIn(it) })
    }

    @Test fun servesOneReplyPerLine() {
        val out = ByteArrayOutputStream()
        handover(Store()).serve(ByteArrayInputStream("set $b64\n\nget\nset-signin $tokenB64\nget-signin\n".toByteArray()), out)
        assertEquals("ok\nkey $b64\nok\nsignin $tokenB64\n", out.toString("US-ASCII"))
    }
}
