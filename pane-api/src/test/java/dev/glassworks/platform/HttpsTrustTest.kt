/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory

/**
 * [HttpsTrust] (#456): real TLS handshakes against a local server, with made-up CAs standing in for the system store
 * and for the bundled roots. Then the bundled roots themselves: the ones GitHub's chain needs, and not near expiry.
 */
class HttpsTrustTest {
    private val servers = mutableListOf<HttpsServer>()

    @After fun stop() = servers.forEach { it.stop(0) }

    private class Ca(name: String) {
        val keys: KeyPair = ecKeys()
        val cert: X509Certificate = sign(X500Name("CN=$name"), keys, X500Name("CN=$name"), keys, ca = true, dns = null)

        /** A server certificate for [dns], signed by this CA. */
        fun leaf(dns: String): Pair<KeyPair, X509Certificate> {
            val k = ecKeys()
            return k to sign(X500Name("CN=$dns"), k, X500Name(cert.subjectX500Principal.name), keys, ca = false, dns = dns)
        }
    }

    /** A server on 127.0.0.1 with [leaf] (issued by [ca]) that answers 200; its https URL as `localhost`. */
    private fun serve(ca: Ca, leaf: Pair<KeyPair, X509Certificate>): URL {
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("leaf", leaf.first.private, PASS, arrayOf(leaf.second, ca.cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, PASS) }
        val tls = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.httpsConfigurator = HttpsConfigurator(tls)
        server.createContext("/") { ex -> ex.sendResponseHeaders(200, 2); ex.responseBody.use { it.write("ok".toByteArray()) } }
        server.start()
        servers += server
        return URL("https://localhost:${server.address.port}/")
    }

    private fun get(url: URL, factory: SSLSocketFactory): Int {
        val c = url.openConnection() as HttpsURLConnection // the default HostnameVerifier, as the app's clients
        c.sslSocketFactory = factory
        c.connectTimeout = 5_000
        c.readTimeout = 5_000
        return try { c.responseCode } finally { c.disconnect() }
    }

    private fun refused(url: URL, factory: SSLSocketFactory) {
        try {
            get(url, factory)
            fail("$url was trusted")
        } catch (_: SSLException) {
            // an SSLHandshakeException or SSLPeerUnverifiedException: refused by TLS, not a timeout or a refused connection
        }
    }

    private val system = Ca("Made-up System Root")
    private val bundled = Ca("Made-up Bundled Root")
    private val factory = HttpsTrust.socketFactory(HttpsTrust.trustManager(listOf(system.cert)), listOf(bundled.cert))

    @Test fun `a server on a system root is trusted`() {
        assertEquals(200, get(serve(system, system.leaf("localhost")), factory))
    }

    @Test fun `a server on a bundled root is trusted, and was refused without it`() {
        val url = serve(bundled, bundled.leaf("localhost"))
        assertEquals(200, get(url, factory))
        val systemOnly = SSLContext.getInstance("TLS").apply { init(null, arrayOf(HttpsTrust.trustManager(listOf(system.cert))), null) }
        refused(url, systemOnly.socketFactory)
    }

    @Test fun `a server on a root neither knows is refused`() {
        val stranger = Ca("Made-up Unknown Root")
        refused(serve(stranger, stranger.leaf("localhost")), factory)
    }

    @Test fun `a certificate for another host is refused, on either root`() {
        refused(serve(bundled, bundled.leaf("other.example")), factory)
        refused(serve(system, system.leaf("other.example")), factory)
    }

    @Test fun `the trust manager loads on Android 6 - no type newer than X509TrustManager`() {
        // X509ExtendedTrustManager is API 24: a class extending it threw NoClassDefFoundError on the API 23 emulator.
        assertEquals(Any::class.java, SystemPlusRoots::class.java.superclass)
        assertEquals(listOf(javax.net.ssl.X509TrustManager::class.java), SystemPlusRoots::class.java.interfaces.toList())
    }

    @Test fun `install adds the roots below Android 7_1 only`() {
        for (level in 21..40) {
            var set: SSLSocketFactory? = null
            val did = HttpsTrust.install(PlatformApi(level)) { set = it }
            assertEquals("installed at API $level", level < 25, did)
            assertEquals("factory set at API $level", level < 25, set != null)
        }
    }

    @Test fun `the chain GitHub serves for raw githubusercontent com validates on the bundled roots alone`() {
        val text = javaClass.getResource("githubusercontent-chain-2026-11-16.pem")!!.readText()
        val chain = CertificateFactory.getInstance("X.509")
            .generateCertificates(text.substring(text.indexOf("-----BEGIN")).byteInputStream()).map { it as X509Certificate }
        assertEquals(3, chain.size)
        fun validates(anchors: List<X509Certificate>): Boolean = runCatching {
            val params = PKIXParameters(anchors.map { TrustAnchor(it, null) }.toSet()).apply {
                isRevocationEnabled = false
                date = Date.from(Instant.parse("2026-11-16T12:00:00Z")) // the day it was served; the leaf expires 2026-12-29
            }
            CertPathValidator.getInstance("PKIX").validate(CertificateFactory.getInstance("X.509").generateCertPath(chain), params)
        }.isSuccess
        assertTrue("GitHub's chain doesn't reach a bundled root", validates(HttpsTrust.roots))
        assertFalse("the check accepts any anchor", validates(listOf(system.cert)))
    }

    @Test fun `the bundled roots are the ones the note names`() {
        val sha256 = HttpsTrust.roots.map { c -> MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) } }
        assertEquals(listOf(ISRG_ROOT_X1), sha256)
        HttpsTrust.roots.forEach { it.verify(it.publicKey) } // self-signed roots, not intermediates
    }

    @Test fun `no bundled root is within a year of expiry`() {
        val due = Date.from(Instant.now().plus(366, ChronoUnit.DAYS))
        val soon = HttpsTrust.roots.filter { it.notAfter.before(due) }
        assertTrue(
            "Bundled roots expire within a year; refresh them (HttpsTrust.ROOTS, docs/research/android-ca-roots.md): " +
                soon.joinToString { "${it.subjectX500Principal.name} on ${it.notAfter.toInstant()}" },
            soon.isEmpty(),
        )
    }

    private companion object {
        val PASS = "test".toCharArray()

        /** ISRG Root X1's SHA-256, as letsencrypt.org/certificates and AOSP's 6187b673.0 give it. */
        const val ISRG_ROOT_X1 = "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6"

        fun ecKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

        fun sign(subject: X500Name, keys: KeyPair, issuer: X500Name, issuerKeys: KeyPair, ca: Boolean, dns: String?): X509Certificate {
            val now = Instant.now()
            val b = JcaX509v3CertificateBuilder(
                issuer, BigInteger.valueOf(now.toEpochMilli()).add(BigInteger.valueOf(System.nanoTime())),
                Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(30, ChronoUnit.DAYS)), subject, keys.public,
            )
            b.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
            dns?.let { b.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, it))) }
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(issuerKeys.private)
            return JcaX509CertificateConverter().getCertificate(b.build(signer))
        }
    }
}
