/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.secrets

import android.util.Log
import dev.glassworks.platform.KeystoreKey
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the panel's secrets (tokens, keys, passwords) with one AES-256-GCM key, so a settings file
 * read off the panel without the key holds no secret. [key] gives the key, or null when it can't be
 * reached now: [keystore] is the Android Keystore's, a test passes a software key.
 *
 * A sealed value is text: [MARK], then base64 of the 12-byte IV and the ciphertext with its tag. The
 * scope (the file and key it is stored under) is authenticated with it, so a sealed value copied to
 * another key doesn't open. Never logs a value.
 */
class SecretStore(private val key: () -> SecretKey?) {

    /** What [open] found. */
    sealed interface Opened {
        class Value(val text: String) : Opened

        /** Sealed with a key this panel no longer has, or changed since: it never opens again. */
        data object Lost : Opened

        /** The key can't be reached now (the key store isn't there or failed): it may open later. */
        data object Unavailable : Opened
    }

    /** [text] sealed for [scope], or null when the key can't be reached now. */
    fun seal(text: String, scope: String): String? {
        val k = key() ?: return null
        return try {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.ENCRYPT_MODE, k)
            c.updateAAD(scope.toByteArray(Charsets.UTF_8))
            val out = c.doFinal(text.toByteArray(Charsets.UTF_8))
            MARK + Base64.getEncoder().encodeToString(c.iv + out)
        } catch (e: Exception) {
            null
        }
    }

    fun open(sealed: String, scope: String): Opened {
        val bytes = runCatching { Base64.getDecoder().decode(sealed.removePrefix(MARK)) }.getOrNull()
        if (!isSealed(sealed) || bytes == null || bytes.size < IV_BYTES + TAG_BITS / 8) return Opened.Lost
        val k = key() ?: return Opened.Unavailable
        return try {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            c.updateAAD(scope.toByteArray(Charsets.UTF_8))
            Opened.Value(String(c.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8))
        } catch (e: AEADBadTagException) {
            Opened.Lost
        } catch (e: InvalidKeyException) {
            // KeyPermanentlyInvalidatedException is one: the key is there but can never be used again.
            Opened.Lost
        } catch (e: GeneralSecurityException) {
            Opened.Unavailable
        } catch (e: RuntimeException) {
            Opened.Unavailable // ProviderException: the key store failed this time
        }
    }

    companion object {
        const val MARK = "gw-secret-v1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** The Keystore key every secret on this panel is sealed with. Panels keep it: never rename it. */
        const val KEY_ALIAS = "glassworks_secrets"
        const val TAG = "S1Kiosk.Secrets"

        fun isSealed(stored: String): Boolean = stored.startsWith(MARK)

        /** The store for this app, with its key in the Android Keystore. */
        fun keystore(): SecretStore = SecretStore(KeystoreKey(KEY_ALIAS) { Log.w(TAG, it) })
    }
}
