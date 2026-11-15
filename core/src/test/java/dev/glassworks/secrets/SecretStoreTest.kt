/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.secrets

import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretStoreTest {
    private val key = aesKey()
    private val store = SecretStore { key }

    @Test fun aSealedSecretOpensToTheSameText() {
        val sealed = store.seal("made-up-refresh-token", "auth/refresh_token")!!
        assertTrue(SecretStore.isSealed(sealed))
        assertFalse("no plaintext in what is stored", sealed.contains("made-up-refresh-token"))
        assertEquals("made-up-refresh-token", (store.open(sealed, "auth/refresh_token") as SecretStore.Opened.Value).text)
        assertNotEquals("a fresh IV each time", sealed, store.seal("made-up-refresh-token", "auth/refresh_token"))
        assertEquals("", (store.open(store.seal("", "x/y")!!, "x/y") as SecretStore.Opened.Value).text)
    }

    @Test fun aChangedCiphertextNeverOpens() {
        val sealed = store.seal("made-up-camera-pass", "kiosk/camera_stream_password")!!
        val bytes = Base64.getDecoder().decode(sealed.removePrefix(SecretStore.MARK))
        for (i in bytes.indices) {
            val bad = bytes.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            val text = SecretStore.MARK + Base64.getEncoder().encodeToString(bad)
            assertEquals("byte $i", SecretStore.Opened.Lost, store.open(text, "kiosk/camera_stream_password"))
        }
        assertEquals("cut short", SecretStore.Opened.Lost, store.open(sealed.dropLast(8), "kiosk/camera_stream_password"))
        assertEquals("not base64", SecretStore.Opened.Lost, store.open(SecretStore.MARK + "%%%", "kiosk/camera_stream_password"))
    }

    @Test fun onlyTheMarkFollowedByAnIvAndATagCountsAsSealed() {
        assertTrue(SecretStore.isSealed(store.seal("", "x/y")!!))
        assertFalse("a password that starts like one", SecretStore.isSealed(SecretStore.MARK + "hunter2"))
        assertFalse("too short for an IV and a tag", SecretStore.isSealed(SecretStore.MARK + Base64.getEncoder().encodeToString(ByteArray(27))))
        assertTrue(SecretStore.isSealed(SecretStore.MARK + Base64.getEncoder().encodeToString(ByteArray(28))))
        assertFalse(SecretStore.isSealed("made-up-plaintext"))
    }

    @Test fun aSecretOpensOnlyUnderTheKeyItWasStoredUnder() {
        val sealed = store.seal("made-up-key", "kiosk/api_key")!!
        assertEquals(SecretStore.Opened.Lost, store.open(sealed, "auth/refresh_token"))
    }

    @Test fun aSecretSealedWithAKeyThatIsGoneIsLost() {
        val sealed = store.seal("made-up-key", "kiosk/api_key")!!
        val remade = SecretStore { aesKey() }
        assertEquals(SecretStore.Opened.Lost, remade.open(sealed, "kiosk/api_key"))
    }

    @Test fun withoutAKeyStoreNothingSealsAndNothingIsLost() {
        val sealed = store.seal("made-up-key", "kiosk/api_key")!!
        val none = SecretStore { null }
        assertNull(none.seal("made-up-key", "kiosk/api_key"))
        assertEquals("it may open later", SecretStore.Opened.Unavailable, none.open(sealed, "kiosk/api_key"))
    }

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
