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
        assertFalse(p.edit().putString("token", "made-up-typed-meanwhile").commit())
        assertEquals("a sealed value is never replaced", sealed, stored("token"))
        assertFalse("fail closed: a secret never sealed isn't stored in the clear either",
            p.edit().putString("psk", "made-up-psk").putString("host", "192.0.2.10").commit())
        assertNull(stored("psk"))
        assertNull("the whole edit", stored("host"))
        assertEquals("one line per reason", 2, warnings.size)
        assertFalse(warnings.any { it.contains("made-up") })
        key = first
        assertEquals("made-up-token", prefs().getString("token", null))
    }

    /** No key store on the device at all (a JVM test; never Android 6+): nothing to wait for, so secrets are stored as before. */
    @Test fun aDeviceWithNoKeyStoreStoresSecretsAsBefore() {
        val none = SecretStore({ null }, { null }, present = { false })
        val p = SecretPrefs(raw, "secrets-test", secrets, none) { warnings += it }
        assertTrue(p.edit().putString("psk", "made-up-psk").commit())
        assertEquals("made-up-psk", stored("psk"))
        assertEquals("made-up-psk", p.getString("psk", null))
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

    @Test fun aSetUnderASecretKeyIsRefused() {
        val p = prefs()
        assertTrue("only strings are sealed", runCatching { p.edit().putStringSet("token", setOf("made-up-token")) }.isFailure)
        assertNull(raw.all["token"])
        assertTrue("other keys take sets", p.edit().putStringSet("hosts", setOf("192.0.2.10")).commit())
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


    /**
     * A signing-key switch (#584): the data is copied across an uninstall, which deleted the Keystore key, so the new
     * install has none (its Keystore makes one when a secret is first sealed, as KeystoreKey.orNew does).
     */
    private var keystore: SecretKey? = null
    private val fresh = SecretStore({ keystore ?: aesKey().also { keystore = it } }, { keystore })
    private fun switched() = SecretPrefs(raw, "secrets-test", secrets, fresh) { warnings += it }

    @Test fun aCarryOverReplacesWhatTheDeletedKeySealed() {
        prefs().edit().putString("token", "made-up-old").putString("psk", "made-up-psk").commit()
        val p = switched()
        assertEquals("no key yet: the copied value stays", SecretPrefs.Secret.Unavailable, p.secret("token"))
        assertTrue(raw.contains("token"))
        assertEquals(SecretPrefs.CarryOver.STORED, p.carryOver("token", "made-up-old") { it.putBoolean("signed_in", true) })
        assertEquals("made-up-old", p.getString("token", null))
        assertEquals("made-up-old", switched().getString("token", null))
        assertTrue("the extra settings in the same commit", raw.getBoolean("signed_in", false))
        assertTrue(SecretStore.isSealed(stored("token")!!))
        assertEquals("handed over again", SecretPrefs.CarryOver.SAME, p.carryOver("token", "made-up-old"))
        // what wasn't carried can never open now: removed when read, so the panel asks for it again
        assertNull(p.getString("psk", null))
        assertFalse(raw.contains("psk"))
        assertFalse("never the value", warnings.any { it.contains("made-up") })
    }

    @Test fun aCarryOverFillsAnEmptyValue() {
        assertEquals(SecretPrefs.CarryOver.STORED, switched().carryOver("token", "made-up-token"))
        assertEquals("made-up-token", switched().getString("token", null))
    }

    @Test fun aCarryOverNeverReplacesAValueInUse() {
        val p = prefs()
        p.edit().putString("token", "made-up-in-use").commit()
        val before = stored("token")
        assertEquals(SecretPrefs.CarryOver.DIFFERENT, p.carryOver("token", "made-up-other") { it.putBoolean("signed_in", true) })
        assertEquals(before, stored("token"))
        assertFalse("nothing else either", raw.contains("signed_in"))
        assertEquals(SecretPrefs.CarryOver.SAME, p.carryOver("token", "made-up-in-use"))
    }

    @Test fun aCarryOverDuringAnOutageChangesNothing() {
        prefs().edit().putString("token", "made-up-in-use").commit()
        val before = stored("token")
        key = null // the key store can't be reached: neither seal nor open
        assertEquals(SecretPrefs.CarryOver.UNAVAILABLE, prefs().carryOver("token", "made-up-other"))
        assertEquals(before, stored("token"))
        // it can seal again but not open yet: a value that may open later stays too
        val flaky = SecretStore({ first }, { null })
        assertEquals(SecretPrefs.CarryOver.UNAVAILABLE, SecretPrefs(raw, "secrets-test", secrets, flaky).carryOver("token", "made-up-other"))
        assertEquals(before, stored("token"))
    }

    @Test fun aReadThatMeetsACarryOverReadsTheCarriedValue() {
        // Another object over the same file (each KioskPrefs has its own) reads while the carry-over writes. Its open
        // fails with the new key, but the value was replaced before it could remove it: it reads the carried one.
        prefs().edit().putString("token", "made-up-old").commit()
        val writer = switched()
        var carried = false
        val readerStore = SecretStore({ keystore ?: aesKey().also { keystore = it } }, {
            if (!carried) { carried = true; assertEquals(SecretPrefs.CarryOver.STORED, writer.carryOver("token", "made-up-old")) }
            keystore
        })
        val reader = SecretPrefs(raw, "secrets-test", secrets, readerStore) { warnings += it }
        assertEquals("made-up-old", reader.getString("token", null))
        assertTrue(carried)
        assertTrue("not removed", raw.contains("token"))
        assertTrue("and not called lost", warnings.isEmpty())
    }

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
