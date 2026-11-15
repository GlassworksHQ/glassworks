/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.testing

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreException
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A software stand-in for Android's `AndroidKeyStore` provider, which Robolectric doesn't have, so the
 * app's real Keystore code (KeystoreKey, SecretStore.keystore()) runs in a JVM test. Keys are plain AES
 * keys in memory. [down] makes the key store fail to load (an outage); [unrecoverable] makes reading a
 * key fail as Android does, with the key store's error code in the cause ([Error]): 17
 * (KEY_PERMANENTLY_INVALIDATED) is gone for good, 4 (SYSTEM_ERROR) passes. [install] before a test, [uninstall] after it.
 */
object FakeAndroidKeyStore {
    val keys = ConcurrentHashMap<String, SecretKey>()
    @Volatile var down = false
    @Volatile var unrecoverable: Int? = null

    /** Stands in for android.security.KeyStoreException, which the app reads by its getErrorCode(). */
    class Error(private val code: Int) : Exception("key store error $code") { fun getErrorCode(): Int = code }

    private val provider = object : Provider("AndroidKeyStore", 1.0, "test stand-in for Android's key store") {
        init {
            put("KeyStore.AndroidKeyStore", Store::class.java.name)
            put("KeyGenerator.AES", Generator::class.java.name)
        }
    }

    fun install() {
        uninstall()
        Security.addProvider(provider)
    }

    fun uninstall() {
        Security.removeProvider(provider.name)
        keys.clear(); down = false; unrecoverable = null
    }

    class Store : KeyStoreSpi() {
        override fun engineLoad(stream: InputStream?, password: CharArray?) { if (down) throw java.io.IOException("key store down") }
        override fun engineGetKey(alias: String, password: CharArray?): Key? {
            if (down) throw KeyStoreException("key store down")
            val code = unrecoverable
            if (code != null && keys.containsKey(alias)) throw UnrecoverableKeyException("Failed to obtain information about key").apply { initCause(Error(code)) }
            return keys[alias]
        }
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineContainsAlias(alias: String) = keys.containsKey(alias)
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineSize() = keys.size
        override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = null
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) { keys[alias] = key as SecretKey }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = throw UnsupportedOperationException()
        override fun engineStore(stream: OutputStream?, password: CharArray?) = throw UnsupportedOperationException()
    }

    /** Takes Android's KeyGenParameterSpec (read by name, so this file needs no Android classes) and keeps the key under its alias. */
    class Generator : KeyGeneratorSpi() {
        private var alias = ""
        private var bits = 256
        override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
            alias = params.javaClass.getMethod("getKeystoreAlias").invoke(params) as String
            bits = (params.javaClass.getMethod("getKeySize").invoke(params) as Int).takeIf { it > 0 } ?: 256
        }
        override fun engineInit(random: SecureRandom?) = throw UnsupportedOperationException("needs a KeyGenParameterSpec")
        override fun engineInit(keysize: Int, random: SecureRandom?) = throw UnsupportedOperationException("needs a KeyGenParameterSpec")
        override fun engineGenerateKey(): SecretKey {
            if (down) throw java.security.ProviderException("key store down")
            return SecretKeySpec(ByteArray(bits / 8).also { SecureRandom().nextBytes(it) }, "AES").also { keys[alias] = it }
        }
    }
}
