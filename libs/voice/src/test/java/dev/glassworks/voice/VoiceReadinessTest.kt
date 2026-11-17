package dev.glassworks.voice

import android.Manifest
import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAudioRecord
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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
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
    fun aCorruptLegacyModelWhoseRepairCannotDownloadSaysWhy() {
        val dir = Files.createTempDirectory("models").toFile()
        listOf(TfliteWakeWord.MELSPEC_FILE, TfliteWakeWord.EMBEDDING_FILE, "hey_jarvis_v0.1.tflite").forEach { File(dir, it).writeBytes(ByteArray(64) { 7 }) }
        val e = VoiceEngine(app, VoiceConfig(modelsDir = dir), "hey_jarvis_v0.1", onActiveWordChanged = {}, retryDelay = { 3_600_000L },
            download = { throw UnknownHostException("github.com") }, loadDetector = null).also { engine = it; it.start(muted = true) }
        eventually("failed") { e.readiness != VoiceReadiness.Starting }
        assertEquals("the repair's download failed, not the engine", ModelFailure.Offline, (e.readiness as VoiceReadiness.ModelFailed).failure)
    }

    @Test
    fun tryAgainStartsTheBackoffOver() {
        val waits = CopyOnWriteArrayList<Int>()
        val dir = Files.createTempDirectory("models").toFile()
        val e = VoiceEngine(app, VoiceConfig(modelsDir = dir), "okay_nabu", onActiveWordChanged = {}, retryDelay = { waits += it; 3_600_000L }) {
            throw ModelsMissingException("offline", ModelFailure.Offline)
        }.also { engine = it; it.start(muted = true) }
        eventually("failed once") { waits.size == 1 }
        e.retryNow()
        eventually("failed after the tap") { waits.size == 2 }
        assertEquals("the wait after a tap is the first one again", listOf(1, 1), waits.toList())
    }

    @Test
    fun aMicrophoneWhoseReadsKeepFailingIsUnavailable() {
        val e = engine { Quiet }
        eventually("ready") { e.readiness == VoiceReadiness.Ready }
        e.micReadFailed(-3)
        e.micReadFailed(-3)
        assertEquals("two failed reads can pass", VoiceReadiness.Ready, e.readiness)
        e.micReadFailed(-3)
        assertEquals(VoiceReadiness.MicrophoneUnavailable, e.readiness)
        e.onMicFrame(ShortArray(1280))
        assertEquals(VoiceReadiness.Ready, e.readiness)
        e.micReadFailed(-3)
        e.micReadFailed(-3)
        assertEquals("a frame starts the count over", VoiceReadiness.Ready, e.readiness)
    }

    @Test
    fun aMutedMicrophoneForgetsItsFailedReads() {
        // A microphone that opens and waits for audio, so only the test reports failed reads.
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean): Int =
                try { Thread.sleep(60_000); 0 } catch (_: InterruptedException) { 0 }
        })
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val closed = Semaphore(0)
        val e = engine { Quiet }.also { it.onMicLevel = { level -> if (level == 0f) closed.release() } }
        eventually("ready") { e.readiness == VoiceReadiness.Ready }
        e.setMuted(false)
        e.micReadFailed(-3)
        e.micReadFailed(-3)
        e.setMuted(true)
        assertTrue("muted", closed.tryAcquire(5, TimeUnit.SECONDS))
        e.setMuted(false)
        e.micReadFailed(-3)
        assertEquals("the unmuted microphone starts its count over", VoiceReadiness.Ready, e.readiness)
    }

    @Test
    fun aLoadThatFindsEveryModelStillClearsWhatAKilledDownloadLeft() {
        val dir = Files.createTempDirectory("models").toFile()
        val model = File(dir, "w.tflite").apply { writeBytes(ByteArray(64) { 1 }) }
        val fetcher = ModelFetcher("https://example.invalid", mapOf("w.tflite" to ModelFetcher.sha256(model)), open = { error("nothing to download") })
        val killed = File(dir, "w.tflite.killed.part").apply { writeBytes(ByteArray(8)); setLastModified(System.currentTimeMillis() - 11 * 60_000) }
        val live = File(dir, "w.tflite.live.part").apply { writeBytes(ByteArray(8)) }
        assertEquals(emptyList<String>(), fetcher.missing(dir))
        assertEquals(emptyList<String>(), fetcher.ensure(dir))
        assertFalse("a killed download's part is gone", killed.exists())
        assertTrue("a live download's part stays", live.exists())
    }

    @Test
    fun stoppedItReadsStartingAgain() {
        val e = engine { Quiet }
        eventually("ready") { e.readiness == VoiceReadiness.Ready }
        e.stop()
        assertEquals(VoiceReadiness.Starting, e.readiness)
    }

    @Test
    fun twoDownloadsOfTheSameModelDoNotSpoilEachOther() {
        val model = ByteArray(200_000) { (it * 31).toByte() }
        val dir = Files.createTempDirectory("models").toFile()
        val tmp = File.createTempFile("model", "").apply { writeBytes(model) }
        val files = mapOf("w.tflite" to ModelFetcher.sha256(tmp))
        val halfway = java.util.concurrent.CountDownLatch(1)
        val goOn = java.util.concurrent.CountDownLatch(1)
        // The old engine's download: half the model, then it waits while the new engine's runs.
        val slow = ModelFetcher("https://example.invalid", files, open = {
            object : java.io.InputStream() {
                var at = 0
                override fun read(): Int = error("unused")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (at >= model.size) return -1
                    if (at >= model.size / 2 && halfway.count > 0) { halfway.countDown(); goOn.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                    val n = minOf(len, 4096, model.size - at)
                    System.arraycopy(model, at, b, off, n); at += n
                    return n
                }
            }
        })
        val old = Thread { runCatching { slow.ensure(dir) }.exceptionOrNull()?.let { failures += it } }.apply { start() }
        assertTrue(halfway.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val fresh = ModelFetcher("https://example.invalid", files, open = { ByteArrayInputStream(model) })
        assertEquals(listOf("w.tflite"), fresh.ensure(dir))
        goOn.countDown()
        old.join(5_000)
        assertEquals("the old download finished too", emptyList<Throwable>(), failures.toList())
        assertEquals(files.getValue("w.tflite"), ModelFetcher.sha256(File(dir, "w.tflite")))
        assertTrue("no part left", dir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    private val failures = CopyOnWriteArrayList<Throwable>()

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
