// The HTTPS trust check on a real Android (#456, MIT). Touches only the device it runs on and the internet: it
// downloads two wake word models and a release list from GitHub, and reaches untrusted-root.badssl.com. Writes
// nothing to the repo. Not part of the build: copy it into the core's instrumentation tests for one run, then
// delete it (it reaches the internet, so CI doesn't run it).
//
//   cp tools/legacy/https-trust-device-check.kt core/src/androidTest/java/dev/glassworks/platform/HttpsTrustDeviceCheck.kt
//   ANDROID_SERIAL=emulator-5554 ./gradlew --init-script tools/legacy/api23-emulator.gradle \
//     :glassworks-core:connectedDebugAndroidTest \
//     -Pandroid.testInstrumentationRunnerArguments.class=dev.glassworks.platform.HttpsTrustDeviceCheck
//   adb -s emulator-5554 logcat -d -s HttpsScratch:I
//   rm core/src/androidTest/java/dev/glassworks/platform/HttpsTrustDeviceCheck.kt
//
// On the API 23 emulator (system-images;android-23;default;arm64-v8a, AOSP's CA store), 2026-11-17: without the
// bundled root both model downloads fail with "Trust anchor for certification path not found"; with it both
// download and match their pinned SHA-256 (docs/research/android-ca-roots.md, step 4).
package dev.glassworks.platform

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// The wake word downloads on this device's own CA store, with and without HttpsTrust.
@RunWith(AndroidJUnit4::class)
class HttpsTrustDeviceCheck {
    private val urls = mapOf(
        "https://raw.githubusercontent.com/esphome/micro-wake-word-models/40ff33f57f8fc6ad71a75ef085abbf742495b225/models/v2/alexa.tflite"
            to "9011a8155b04de858c48038529235cbc0e42e9fca05a55bf588cb80a653a723b",
        "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/alexa_v0.1.tflite"
            to "7333a317a790070a7f3432b81d9439c779481cc4ebd67c73da7174ea3cf48397",
        "https://api.github.com/repos/GlassworksHQ/glassworks/releases?per_page=1" to null,
    )

    private fun fetch(url: String, factory: SSLSocketFactory?): String {
        val c = URL(url).openConnection() as HttpsURLConnection
        factory?.let { c.sslSocketFactory = it }
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "glassworks-scratch")
        return try {
            val code = c.responseCode
            val bytes = c.inputStream.use { it.readBytes() }
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            "HTTP $code from ${c.url.host}, ${bytes.size} bytes, sha256 $sha"
        } catch (e: Exception) {
            "${e.javaClass.name}: ${e.message}"
        } finally { c.disconnect() }
    }

    @Test fun downloads() {
        Log.i("HttpsScratch", "API ${Platform.apiLevel}, hasIsrgRootX1=${Platform.hasIsrgRootX1}")
        val systemOnly = SSLContext.getInstance("TLS").apply { init(null, arrayOf(HttpsTrust.systemTrustManager()), null) }.socketFactory
        val withRoots = HttpsTrust.socketFactory(HttpsTrust.systemTrustManager())
        val results = mutableListOf<String>()
        for ((url, sha) in urls) {
            val without = fetch(url, systemOnly)
            val with = fetch(url, withRoots)
            Log.i("HttpsScratch", "WITHOUT $url -> $without")
            Log.i("HttpsScratch", "WITH    $url -> $with")
            results += with
            if (sha != null) assertEquals(true, with.endsWith(sha))
        }
        val address = java.net.InetAddress.getAllByName("raw.githubusercontent.com").first()
        val host = if (address is java.net.Inet6Address) "[${address.hostAddress.substringBefore('%')}]" else address.hostAddress
        val byIp = fetch("https://$host/", withRoots)
        Log.i("HttpsScratch", "WITH    https://<raw.githubusercontent.com's IP>/ (chain to X1, name doesn't match) -> $byIp")
        assertEquals(true, byIp.contains("SSLPeerUnverifiedException") || byIp.contains("Hostname"))
        val untrusted = fetch("https://untrusted-root.badssl.com/", withRoots)
        Log.i("HttpsScratch", "WITH    https://untrusted-root.badssl.com/ -> $untrusted")
        assertEquals(true, untrusted.contains("SSLHandshakeException"))
        // The global path the app uses: install, then a plain connection.
        val installed = HttpsTrust.install()
        val plain = fetch(urls.keys.first(), null)
        Log.i("HttpsScratch", "install()=$installed, then default factory -> $plain")
        assertEquals(true, plain.endsWith(urls.values.first()!!))
    }
}
