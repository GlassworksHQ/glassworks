package dev.glassworks.voice

import android.Manifest
import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLHandshakeException

/** #458: why voice can't get ready, as the engine reports it, and when it tries again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceReadinessTest {
    private val app = RuntimeEnvironment.getApplication()
    private var engine: VoiceEngine? = null

    @After fun stop() { engine?.stop() }

    private fun failure(e: IOException): ModelFailure? {
        val f = ModelFetcher.micro { throw e }
        val dir = Files.createTempDirectory("models").toFile()
        return (runCatching { f.ensure(dir) }.exceptionOrNull() as? ModelDownloadException)?.failure
    }

    @Test
    fun aDownloadSaysWhyItFailed() {
        // What Android 6 threw on the Mirror on 2026-11-17: its CA store lacked the root.
        val oldCaStore = SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.")
            .apply { initCause(CertificateException(CertPathValidatorException("Trust anchor for certification path not found."))) }
        assertEquals(ModelFailure.Certificate, failure(oldCaStore))
        assertEquals(ModelFailure.SecureConnection, failure(SSLHandshakeException("handshake failed")))
        assertEquals(ModelFailure.Offline, failure(UnknownHostException("raw.githubusercontent.com")))
        assertEquals(ModelFailure.Offline, failure(SocketTimeoutException("timeout")))
        assertEquals(ModelFailure.Http(404), failure(HttpStatusException(404)))
    }

    @Test
    fun aDownloadThatIsNotTheModelFailsItsCheck() {
        val dir = Files.createTempDirectory("models").toFile()
        val e = runCatching { ModelFetcher.micro { ByteArrayInputStream("<html>not a model</html>".toByteArray()) }.ensure(dir) }.exceptionOrNull()
        assertEquals(ModelFailure.CheckFailed, (e as ModelDownloadException).failure)
        assertTrue("its log line keeps the hash's reason", e.message!!.contains("SHA-256"))
    }

    @Test
    fun aModelThatCannotBeSavedIsAStorageFailure() {
        val notADir = Files.createTempFile("models", "").toFile()
        val e = runCatching { ModelFetcher.micro { ByteArrayInputStream(ByteArray(1)) }.ensure(File(notADir, "micro")) }.exceptionOrNull()
        assertEquals(ModelFailure.Storage, (e as ModelDownloadException).failure)
    }

    @Test
    fun retriesBackOffToTenMinutes() {
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 600_000L, 600_000L), (1..7).map(::retryDelayMs))
    }

    private val states = CopyOnWriteArrayList<VoiceReadiness>()

    private fun engine(word: String = "okay_nabu", load: (WakeWord) -> WakeDetector): VoiceEngine {
        val dir = Files.createTempDirectory("models").toFile()
        return VoiceEngine(app, VoiceConfig(modelsDir = dir), word, onActiveWordChanged = {}, retryDelay = { 150L }) { load(it) }
            .also { e -> e.onReadiness = { states += it }; engine = e; e.start(muted = true) }
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            assertTrue(what, System.currentTimeMillis() < until)
            Thread.sleep(10)
        }
    }

    private object Quiet : WakeDetector {
        override fun process(frame: ShortArray) = 0f
        override fun close() {}
    }

    @Test
    fun aFailedDownloadSaysWhenItTriesAgainThenRecovers() {
        var tries = 0
        val e = engine {
            if (++tries < 3) throw ModelsMissingException("Downloading the wake word model okay_nabu.tflite failed: no trust anchor", ModelFailure.Certificate)
            Quiet
        }
        eventually("ready after the third try") { e.readiness == VoiceReadiness.Ready }
        assertEquals(3, tries)
        val failed = states.filterIsInstance<VoiceReadiness.ModelFailed>()
        assertTrue("it said why while it waited: $states", failed.isNotEmpty() && failed.all { it.failure == ModelFailure.Certificate })
        assertTrue("and when it tries again", failed.all { it.retryAtMs > 0 })
        assertEquals(VoiceReadiness.Starting, states.first())
        assertTrue("a retry keeps the failure on screen instead of flashing Starting", states.drop(1).dropLast(1).none { it == VoiceReadiness.Starting })
    }

    @Test
    fun anEngineThatWontStartIsNotAModelProblem() {
        val e = engine { throw UnsatisfiedLinkError("libtensorflowlite_jni.so not found") }
        eventually("engine failed") { e.readiness is VoiceReadiness.EngineFailed }
    }

    @Test
    fun noWakeWordIsReady() {
        val e = engine(word = "") { error("nothing to load") }
        eventually("ready") { e.readiness == VoiceReadiness.Ready }
    }

    @Test
    fun tryAgainLoadsNowInsteadOfAtTheNextRetry() {
        var tries = 0
        val dir = Files.createTempDirectory("models").toFile()
        val e = VoiceEngine(app, VoiceConfig(modelsDir = dir), "okay_nabu", onActiveWordChanged = {}, retryDelay = { 3_600_000L }) {
            if (++tries == 1) throw ModelsMissingException("offline", ModelFailure.Offline)
            Quiet
        }.also { engine = it; it.start(muted = true) }
        eventually("failed") { e.readiness is VoiceReadiness.ModelFailed }
        e.retryNow()
        eventually("ready long before the hour-long retry") { e.readiness == VoiceReadiness.Ready }
    }

    @Test
    fun backToTheWordThatStillListensIsReady() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val e = engine(word = "hey_jarvis") { word ->
            if (word.id == "okay_nabu") gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
            Quiet
        }
        eventually("Hey Jarvis ready") { e.readiness == VoiceReadiness.Ready }
        e.setActive(listOf("okay_nabu"))
        eventually("Okay Nabu loading") { e.readiness == VoiceReadiness.Starting }
        e.setActive(listOf("hey_jarvis"))
        gate.countDown()
        eventually("ready again, not stuck on Getting ready") { e.readiness == VoiceReadiness.Ready }
    }

    @Test
    fun aMicrophoneThatWontOpenSaysWhetherItIsAllowed() {
        val e = engine { Quiet }
        eventually("ready") { e.readiness == VoiceReadiness.Ready }
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        e.micOpenFailed()
        assertEquals(VoiceReadiness.MicrophoneNotAllowed, e.readiness)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        e.micOpenFailed()
        assertEquals(VoiceReadiness.MicrophoneUnavailable, e.readiness)
        e.onMicFrame(ShortArray(1280))
        assertEquals("a frame means it opened", VoiceReadiness.Ready, e.readiness)
        assertEquals(VoiceReadiness.Ready, states.last())
    }
}
