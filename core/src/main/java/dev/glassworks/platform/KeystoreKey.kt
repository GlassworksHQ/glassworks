/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * An AES-256-GCM key in the Android Keystore under [alias], made the first time it is asked for. The
 * key never leaves the key store (no app can export it) and needs no user authentication, so the app can
 * use it whenever it runs, with nobody at the screen (it starts once the device has unlocked after boot;
 * the S1 has no lock screen). API 23 and later have all of it; the app's minimum is 26.
 *
 * It reads the entry from the key store on every use and keeps no copy, so every object in the process
 * uses the key the store holds now: one made again by another object (below) is never met with an old
 * copy that would call a fresh secret lost.
 *
 * Null when the key store can't be reached (a vendor bug, a test without one); [warn] hears that
 * once, without any value. Only sealing makes a key ([orNew]); opening never does ([existing]), because
 * Android 10's lookup also answers "no key" when the key store fails for a moment, and making a key
 * under the alias deletes the one there. A key the key store itself says is gone for good
 * ([lostForGood]) is made again, so what was sealed with it no longer opens
 * ([dev.glassworks.secrets.SecretStore.Opened.Lost]): the panel asks for it again.
 */
class KeystoreKey(private val alias: String, private val warn: (String) -> Unit) {
    private val warned = AtomicBoolean()

    /** The key to open with, or null when there is none to use now. Never makes one. */
    fun existing(): SecretKey? = use { store -> lookup(store) }

    /**
     * The key to seal with, made if there is none. A lookup that finds nothing is asked once more on a
     * freshly loaded key store before a key is made, so a lookup that failed for a moment rarely costs
     * the key there.
     */
    fun orNew(): SecretKey? = use { store ->
        lookup(store) ?: run {
            val again = KeyStore.getInstance(PROVIDER).apply { load(null) }
            lookup(again) ?: if (again.containsAlias(alias)) null else make()
        }
    }

    private fun <T> use(f: (KeyStore) -> T?): T? = synchronized(KeystoreKey::class.java) {
        try {
            f(KeyStore.getInstance(PROVIDER).apply { load(null) })
        } catch (e: Exception) {
            if (warned.compareAndSet(false, true)) warn("The key store isn't available (${e.javaClass.simpleName}); secrets stay as they are")
            null
        }
    }

    /** The entry under [alias]; one the key store says is gone for good is replaced by a new key. */
    private fun lookup(store: KeyStore): SecretKey? = try {
        store.getKey(alias, null) as? SecretKey
    } catch (e: UnrecoverableKeyException) {
        // Android wraps every key store error in this one, a passing one too. Only an entry that is
        // gone for good is replaced (signed out, paired again); anything else is an outage.
        if (!lostForGood(e) && !(e.cause == null && causelessAgain())) throw e
        warn("The secrets key can't be read any more (${errorCode(e) ?: "invalidated"}); made a new one, so secrets need setting again")
        store.deleteEntry(alias)
        make()
    }

    /**
     * Android 10's getKey turns KEY_PERMANENTLY_INVALIDATED (17) into a bare UnrecoverableKeyException
     * with no cause (read 2026-11-15, AOSP android-10.0.0_r1: `AndroidKeyStoreSpi.engineGetKey` catches the
     * KeyPermanentlyInvalidatedException that `AndroidKeyStoreProvider.getKeyCharacteristics` throws for
     * 17 and rethrows only its message). Every other failure on that path keeps the key store's error as
     * the cause. So a cause-less one is taken as 17 only when a freshly loaded key store gives it again.
     */
    private fun causelessAgain(): Boolean = try {
        KeyStore.getInstance(PROVIDER).apply { load(null) }.getKey(alias, null)
        false
    } catch (e: UnrecoverableKeyException) {
        e.cause == null
    }

    private fun make(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"

        /** This device has the Android Keystore provider (every Android 6+ device; a plain JVM test has none). */
        fun present(): Boolean = java.security.Security.getProvider(PROVIDER) != null

        /**
         * The key store's error codes that mean a key is gone for good (read 2026-11-15 from AOSP
         * android-10.0.0_r1 `keystore/java/android/security/KeyStore.java`): KEY_NOT_FOUND 7,
         * VALUE_CORRUPTED 8 ("Key blob corrupted"), KEY_PERMANENTLY_INVALIDATED 17, and Keymaster's
         * KM_ERROR_INVALID_KEY_BLOB -33. Android 10 hands 17 to Cipher.init as a plain
         * InvalidKeyException, so the code decides, not the exception's type. Any other code (LOCKED 2,
         * SYSTEM_ERROR 4, …) may pass, so it is an outage.
         */
        private val GONE_FOR_GOOD = setOf(7, 8, 17, -33)

        /** The key store error code in [e]'s causes (android.security.KeyStoreException, read by name), or null. */
        fun errorCode(e: Throwable): Int? = generateSequence(e) { it.cause }.take(8).firstNotNullOfOrNull { t ->
            runCatching { t.javaClass.getMethod("getErrorCode").invoke(t) as? Int }.getOrNull()
        }

        /** [e] says the key can never be used again: a code in [GONE_FOR_GOOD], or KeyPermanentlyInvalidatedException. */
        fun lostForGood(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.take(8).any { it is android.security.keystore.KeyPermanentlyInvalidatedException } ||
                errorCode(e) in GONE_FOR_GOOD
    }
}
