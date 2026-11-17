/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTPS on Androids whose CA store lacks a root the app's hosts chain to (#456, docs/research/android-ca-roots.md).
 * Android 6 and 7.0 lack ISRG Root X1 (Let's Encrypt), and GitHub serves raw.githubusercontent.com and its release
 * assets on it: the wake word models and update APKs. There, [install] makes every HttpsURLConnection in the process
 * trust the system store plus [ROOTS]. Certificate checks and the hostname check stay as Android does them. From
 * Android 7.1 the store has the root, and nothing changes.
 */
object HttpsTrust {
    /**
     * The roots the app adds, each a CA's public certificate, unmodified. Only roots a host the app fetches from
     * needs (tools/legacy/ca-roots.sh shows which). Root certificates carry no licence terms; every OS and browser
     * store ships them (THIRD-PARTY-NOTICES.md).
     *
     * **They expire.** [HttpsTrustTest] fails a year before one does. To refresh or add one: rerun
     * tools/legacy/ca-roots.sh, take the root from its CA's site (and compare it to the copy in AOSP's
     * system/ca-certificates), paste it here, and update its line in the note and the fingerprint in the test.
     *
     * - **ISRG Root X1**: https://letsencrypt.org/certs/isrgrootx1.pem. Expires 2035-06-04. SHA-256
     *   96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6. In AOSP from Android 7.1 (6187b673.0).
     */
    val ROOTS: List<String> = listOf(
        """
            -----BEGIN CERTIFICATE-----
            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
            -----END CERTIFICATE-----
        """.trimIndent(),
    )

    /** [ROOTS], parsed. */
    val roots: List<X509Certificate> by lazy {
        val cf = CertificateFactory.getInstance("X.509")
        ROOTS.map { cf.generateCertificate(it.byteInputStream()) as X509Certificate }
    }

    /**
     * Below Android 7.1, makes [roots] trusted next to the system's for every HttpsURLConnection the process opens
     * from now on: the model download, the update check and download, notification pictures and the HA sign-in.
     * Call it once, before the first connection. Gives whether it did.
     *
     * Never throws: it runs as the app starts, so a device whose KeyStore or TrustManagerFactory refuses the store,
     * or a class it lacks, gives a failure here and leaves HTTPS as the system has it. [set] and [factory] are for
     * tests.
     */
    fun install(
        platform: PlatformApi = Platform,
        set: (SSLSocketFactory) -> Unit = HttpsURLConnection::setDefaultSSLSocketFactory,
        factory: () -> SSLSocketFactory = { socketFactory(systemTrustManager()) },
    ): Result<Boolean> {
        if (platform.hasIsrgRootX1) return Result.success(false)
        return try {
            set(factory())
            Result.success(true)
        } catch (t: Throwable) { // not just Exception: a NoClassDefFoundError on Android 6 is an Error
            Result.failure(t)
        }
    }

    /** TLS with [system] and [extra] as its trust: what [install] gives HttpsURLConnection. */
    fun socketFactory(system: X509TrustManager, extra: List<X509Certificate> = roots): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(SystemPlusRoots(system, trustManager(extra))), null) }.socketFactory

    /** The platform's trust manager: its CA store, and on Android the user's CAs and Network Security Config. */
    fun systemTrustManager(): X509TrustManager = trustManager(null)

    /** A trust manager over exactly [anchors], or the system's when null. */
    fun trustManager(anchors: List<X509Certificate>?): X509TrustManager {
        val store = anchors?.let { certs ->
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certs.forEachIndexed { i, c -> setCertificateEntry("root$i", c) }
            }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}

/**
 * Trusts a server chain [system] trusts, else one [extra] trusts; refuses it with [system]'s error otherwise.
 *
 * A plain [X509TrustManager]: `X509ExtendedTrustManager` exists only from Android 7.0, and a class that extends it
 * doesn't load on Android 6 (the API 23 emulator threw NoClassDefFoundError, 2026-11-17). So the hostname-aware
 * overloads go unused, and the hostname check stays where it is: HttpsURLConnection's HostnameVerifier on Android,
 * the endpoint check on the JVM. Android 7.0's Network Security Config takes the plain overload too, as long as the
 * app sets no per-domain config (neither app does).
 */
internal class SystemPlusRoots(private val system: X509TrustManager, private val extra: X509TrustManager) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            try { extra.checkServerTrusted(chain, authType) } catch (x: CertificateException) { e.addSuppressed(x); throw e }
        }
    }

    // The app is never a TLS server that asks for client certificates: the system decides, as before.
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + extra.acceptedIssuers
}
