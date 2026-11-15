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
 * keys in memory. [down] is an outage as Android 10 shows one: lookups answer "no key" and making a key
 * fails. [unrecoverable] makes reading the key fail as Android 10 does: 17 (KEY_PERMANENTLY_INVALIDATED)
 * as a bare UnrecoverableKeyException, any other code with the key store's error ([Error]) as its cause. [install] before a test, [uninstall] after it.
 */
object FakeAndroidKeyStore {
    val keys = ConcurrentHashMap<String, SecretKey>()
    @Volatile var down = false
    @Volatile var unrecoverable: Int? = null

    /** The next [hidden] lookups answer "no key" although there is one, as Android 10 does when a check fails. */
    val hidden = java.util.concurrent.atomic.AtomicInteger()

    /** The next [bare] lookups throw a cause-less UnrecoverableKeyException, as Android 10 does for code 17, without the key being gone. */
    val bare = java.util.concurrent.atomic.AtomicInteger()

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
        keys.clear(); down = false; unrecoverable = null; hidden.set(0); bare.set(0)
    }

    class Store : KeyStoreSpi() {
        // As Android 10's AndroidKeyStoreSpi (AOSP android-10.0.0_r1, read 2026-11-15): load() only binds
        // to the key store service; getKey() asks contains() first, which is false when the service
        // fails, so an outage answers "no key"; then the key's characteristics, whose failure is an
        // UnrecoverableKeyException with the error as its cause, except 17, which comes back bare.
        override fun engineLoad(stream: InputStream?, password: CharArray?) {}
        override fun engineGetKey(alias: String, password: CharArray?): Key? {
            if (down || !keys.containsKey(alias)) return null
            if (hidden.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) return null
            if (bare.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) throw UnrecoverableKeyException("User changed or deleted their auth credentials")
            when (val code = unrecoverable) {
                null -> {}
                17 -> throw UnrecoverableKeyException("User changed or deleted their auth credentials")
                else -> throw UnrecoverableKeyException("Failed to obtain information about key").apply { initCause(Error(code)) }
            }
            return keys[alias]
        }
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineContainsAlias(alias: String) = !down && keys.containsKey(alias) && hidden.get() == 0
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
