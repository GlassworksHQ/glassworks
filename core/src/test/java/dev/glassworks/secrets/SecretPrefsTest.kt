/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.secrets

import android.content.Context
import android.content.SharedPreferences
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretPrefsTest {
    private val raw: SharedPreferences = RuntimeEnvironment.getApplication().getSharedPreferences("secrets-test", Context.MODE_PRIVATE)
    private val first = aesKey()
    private var key: SecretKey? = first
    private val store = SecretStore { key }
    private val warnings = ArrayList<String>()
    private val secrets = setOf("token", "psk")

    private fun prefs() = SecretPrefs(raw, "secrets-test", secrets, store) { warnings += it }
    private fun stored(k: String) = raw.all[k] as String?

    @Test fun plaintextFromAnEarlierVersionIsSealedInPlaceAndReadsTheSame() {
        raw.edit().putString("token", "made-up-token").putString("psk", "made-up-psk").putString("host", "192.0.2.10").putInt("volume", 40).commit()
        val p = prefs()
        assertTrue(SecretStore.isSealed(stored("token")!!) && SecretStore.isSealed(stored("psk")!!))
        assertEquals("only the secrets", "192.0.2.10", stored("host"))
        assertEquals(40, raw.getInt("volume", 0))
        assertEquals("made-up-token", p.getString("token", null))
        assertEquals("made-up-psk", p.getString("psk", null))
        assertEquals("192.0.2.10", p.getString("host", null))
        assertTrue(warnings.isEmpty())
    }

    @Test fun sealingAgainChangesNothing() {
        raw.edit().putString("token", "made-up-token").commit()
        prefs()
        val once = stored("token")
        prefs(); prefs()
        assertEquals("nothing left to seal", once, stored("token"))
        assertEquals("made-up-token", prefs().getString("token", null))
    }

    @Test fun aFileLeftHalfSealedReadsBackWhole() {
        // What a key store that failed between two secrets leaves: one sealed, one still plaintext.
        raw.edit().putString("token", "made-up-token").commit()
        prefs()
        raw.edit().putString("psk", "made-up-psk").commit()
        val p = prefs()
        assertEquals("made-up-token", p.getString("token", null))
        assertEquals("made-up-psk", p.getString("psk", null))
        assertTrue(SecretStore.isSealed(stored("psk")!!))
    }

    @Test fun writesAreSealedAndAbsentStaysAbsent() {
        val p = prefs()
        assertNull(p.getString("token", null))
        assertEquals("the default when absent", "", p.getString("token", ""))
        assertFalse(p.contains("token"))
        p.edit().putString("token", "made-up-token").putString("host", "192.0.2.10").commit()
        assertTrue(SecretStore.isSealed(stored("token")!!))
        assertEquals("192.0.2.10", stored("host"))
        assertTrue(p.contains("token"))
        assertEquals("made-up-token", prefs().getString("token", null))
        p.edit().putString("token", null).commit()
        assertFalse(raw.contains("token"))
    }

    @Test fun aSecretWhoseKeyIsGoneReadsAsNotSetAndIsRemoved() {
        prefs().edit().putString("token", "made-up-token").commit()
        key = aesKey() // a restored backup, or a vendor bug: the old key is gone and a new one was made
        val p = prefs()
        assertNull(p.getString("token", null))
        assertFalse(p.contains("token"))
        assertFalse("removed, so the panel asks for it again", raw.contains("token"))
        assertNull(p.getString("token", null))
        assertEquals("logged once", 1, warnings.size)
        assertFalse("without the value", warnings.single().contains("made-up-token"))
        p.edit().putString("token", "made-up-new").commit()
        assertEquals("set again, it works", "made-up-new", prefs().getString("token", null))
    }

    @Test fun withoutAKeyStoreSecretsAreKeptForLater() {
        prefs().edit().putString("token", "made-up-token").commit()
        val sealed = stored("token")
        key = null
        val p = prefs()
        assertNull("reads as not set", p.getString("token", null))
        assertFalse(p.contains("token"))
        assertEquals("but kept", sealed, stored("token"))
        assertFalse("callers can tell it may come back", p.canRead("token"))
        assertTrue(p.canRead("psk"))
        p.edit().putString("token", "made-up-typed-meanwhile").commit()
        assertEquals("a sealed value is never replaced with plaintext", sealed, stored("token"))
        p.edit().putString("psk", "made-up-psk").commit()
        assertEquals("a new one is kept unsealed so the panel keeps working", "made-up-psk", stored("psk"))
        assertEquals("one line per reason", 3, warnings.size)
        assertFalse(warnings.any { it.contains("made-up") })
        key = first
        val back = prefs()
        assertEquals("made-up-token", back.getString("token", null))
        assertTrue("sealed once the key store is back", SecretStore.isSealed(stored("psk")!!))
        assertEquals("made-up-psk", back.getString("psk", null))
    }

    @Test fun anEditThatWouldDropAnUnreadableSecretWritesNothing() {
        prefs().edit().putString("token", "made-up-token").putString("host", "192.0.2.10").commit()
        val before = raw.all
        key = null
        val p = prefs()
        assertFalse("clear", p.edit().clear().commit())
        assertFalse("remove", p.edit().remove("token").putString("host", "192.0.2.99").commit())
        assertFalse("a null put is a remove", p.edit().putString("token", null).commit())
        assertFalse("replace", p.edit().putString("token", "made-up-new").putBoolean("flag", true).commit())
        p.edit().remove("token").putString("host", "192.0.2.99").apply()
        assertEquals("all or nothing: the other changes in those edits aren't written either", before, raw.all)
        assertTrue("an edit that leaves the secret alone is written", p.edit().putString("host", "192.0.2.99").commit())
        key = first
        assertEquals("made-up-token", prefs().getString("token", null))
    }

    @Test fun anEditIsCheckedAgainstWhatIsStoredWhenItIsWritten() {
        val p = prefs()
        val staged = p.edit().remove("token") // nothing stored yet: nothing to keep, so far
        p.edit().putString("token", "made-up-token").commit()
        val sealed = stored("token")
        key = null
        assertFalse("the token stored since can't be read now: kept", staged.commit())
        assertEquals(sealed, stored("token"))
    }

    @Test fun getAllHandsOutSecretsOpened() {
        raw.edit().putString("host", "192.0.2.10").commit()
        prefs().edit().putString("token", "made-up-token").commit()
        assertEquals(mapOf("host" to "192.0.2.10", "token" to "made-up-token"), prefs().all)
        key = null
        assertEquals("a secret that doesn't open now is left out", mapOf("host" to "192.0.2.10"), prefs().all)
    }

    @Test fun aListenerReadsTheOpenedValue() {
        val p = prefs()
        var heard: String? = null
        val l = SharedPreferences.OnSharedPreferenceChangeListener { sp, k -> if (k == "token") heard = sp.getString(k, null) }
        p.registerOnSharedPreferenceChangeListener(l)
        p.edit().putString("token", "made-up-token").commit()
        assertEquals("made-up-token", heard)
        p.unregisterOnSharedPreferenceChangeListener(l)
        heard = null
        p.edit().putString("token", "made-up-other").commit()
        assertNull("unregistered", heard)
    }


    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
