/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks

import android.content.Context
import dev.glassworks.auth.StoredAuth
import dev.glassworks.esphome.StoredKey
import dev.glassworks.secrets.SecretPrefs
import dev.glassworks.secrets.SecretStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #584: a signing-key switch with root copies the app's settings across an uninstall, which deletes its Keystore key.
 * The installer hands the API key and the sign-in back, and they go into the copied settings as they were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KioskPrefsCarryOverTest {
    private val app = RuntimeEnvironment.getApplication()
    /** Made-up values: never a real key, token or address in a test. */
    private val key = ByteArray(32) { (it + 7).toByte() }
    private val token = "made-up-refresh-token"
    private val signIn = StoredAuth("http://192.0.2.10:8123", "http://192.0.2.20:8124/", token)

    private val oldKey: SecretKey = aesKey()
    /** The new install's Keystore: no key until the first secret is sealed (KeystoreKey.orNew). */
    private var newKey: SecretKey? = null
    private val oldInstall = SecretStore { oldKey }
    private val newInstall = SecretStore({ newKey ?: aesKey().also { newKey = it } }, { newKey })

    @Before fun clean() {
        for (f in listOf("kiosk", "auth")) app.getSharedPreferences(f, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun theSwitchedAppGetsItsKeyAndSignInBack() {
        // the old build, signed in and keyed: its settings are what the switch copies
        KioskPrefs(app, oldInstall).apply {
            auth = signIn
            assertTrue(noiseKeyStore.save(key))
        }
        val p = KioskPrefs(app, newInstall)
        assertEquals("the copied key is stored but can't be read: the API server stays locked", StoredKey.Unreadable, p.noiseKeyStore.read())
        assertNull("the panel shows the sign-in QR code meanwhile", p.auth)

        assertEquals(SecretPrefs.CarryOver.STORED, p.carryOverApiKey(key))
        assertEquals(SecretPrefs.CarryOver.STORED, p.carryOverRefreshToken(token))

        val after = KioskPrefs(app, newInstall)
        assertArrayEquals(key, (after.noiseKeyStore.read() as StoredKey.Present).key)
        assertEquals(signIn, after.auth)
        assertTrue(after.apiEncryption)
    }

    @Test fun theSignInGoesOnlyIntoTheCopiedOne() {
        // a fresh app: no Home Assistant address or client id, so a token alone signs in to nothing
        assertNull(KioskPrefs(app, newInstall).carryOverRefreshToken(token))
        assertNull(KioskPrefs(app, newInstall).auth)
    }

    @Test fun aReflashFillsAnEmptyStoreAsBefore() {
        // #574: the fresh app after a reflash, encryption off and no key
        val p = KioskPrefs(app, newInstall)
        assertEquals(SecretPrefs.CarryOver.STORED, p.carryOverApiKey(key))
        assertArrayEquals(key, (p.noiseKeyStore.read() as StoredKey.Present).key)
        assertEquals("handed over again", SecretPrefs.CarryOver.SAME, p.carryOverApiKey(key))
    }

    @Test fun aKeyInUseStays() {
        val p = KioskPrefs(app, oldInstall)
        assertTrue(p.noiseKeyStore.save(key))
        assertEquals(SecretPrefs.CarryOver.DIFFERENT, p.carryOverApiKey(ByteArray(32) { 9 }))
        assertArrayEquals(key, (p.noiseKeyStore.read() as StoredKey.Present).key)
    }

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
