/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * An AES-256-GCM key in the Android Keystore under [alias], made the first time it is asked for. The
 * key never leaves the key store (no app can export it) and needs no user sign-in, so the panel can
 * use it at boot with nobody at the screen. API 23 and later have all of it; the app's minimum is 26.
 *
 * Null when the key store can't be reached (a vendor bug, a test without one); [warn] hears that
 * once, without any value. A key that went missing is made again, so what was sealed with the old
 * one no longer opens ([dev.glassworks.secrets.SecretStore.Opened.Lost]).
 */
class KeystoreKey(private val alias: String, private val warn: (String) -> Unit) : () -> SecretKey? {
    @Volatile private var key: SecretKey? = null
    private val warned = AtomicBoolean()

    @Synchronized override fun invoke(): SecretKey? {
        key?.let { return it }
        return try {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            val k = store.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
            k.also { key = it }
        } catch (e: Exception) {
            if (warned.compareAndSet(false, true)) warn("The key store isn't available (${e.javaClass.simpleName}); secrets stay as they are")
            null
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
