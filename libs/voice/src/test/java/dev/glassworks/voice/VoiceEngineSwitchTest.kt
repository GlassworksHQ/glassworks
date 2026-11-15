package dev.glassworks.voice

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.glassworks.esphome.Msg
import dev.glassworks.esphome.ProtoMessage
import dev.glassworks.esphome.WakeWordInfo
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Switching words: what Home Assistant hears while a new word loads, and when it can't. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceEngineSwitchTest {
    private class Always : WakeDetector {
        @Volatile var closed = false
        override fun process(frame: ShortArray) = 1f
        override fun close() { closed = true }
    }

    private val errors = CopyOnWriteArrayList<String>()
    private val jarvis = Always()
    private val nabu = Always()
    private val nabuGate = CountDownLatch(1)
    private val nabuLoading = CountDownLatch(1)
    private var nabuFails = false
    private lateinit var engine: VoiceEngine

    private fun engine(word: String = "hey_jarvis"): VoiceEngine {
        val dir = Files.createTempDirectory("models").toFile()
        return VoiceEngine(RuntimeEnvironment.getApplication(), VoiceConfig(modelsDir = dir, refractoryMs = 0), word, onActiveWordChanged = {}) { word ->
            when (word.id) {
                "hey_jarvis" -> jarvis
                "okay_nabu" -> {
                    nabuLoading.countDown()
                    // Like a real TFLite load, an interrupt (stop() -> shutdownNow) doesn't end it.
                    val until = System.currentTimeMillis() + 5_000
                    while (nabuGate.count > 0 && System.currentTimeMillis() < until) {
                        try { nabuGate.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                    }
                    if (nabuFails) throw ModelsMissingException("Downloading the wake word model okay_nabu.tflite failed: offline")
                    nabu
                }
                else -> error("unexpected ${word.id}")
            }
        }.also { e ->
            e.onError = { errors += it }
            engine = e
            e.start(muted = true)
        }
    }

    @After fun stop() = engine.stop()

    /** The wake word phrase HA would get for one frame, or null when nothing was sent. */
    private fun phraseHeard(): String? {
        val sent = ArrayList<Pair<Int, ProtoMessage>>()
        val c = VoiceAssistantController(
            send = { t, p -> sent += t to ProtoMessage(p.toByteArray()); true },
            player = object : UrlPlayer {
                override fun play(urls: List<String>, onDone: (Boolean) -> Unit) {}
                override fun stop() {}
            },
            wakeWords = object : WakeWordCatalog {
                override fun available() = emptyList<WakeWordInfo>()
                override fun activeIds() = emptyList<String>()
                override fun setActive(ids: List<String>) {}
            },
            emit = {},
        )
        c.onSubscribe(true)
        engine.controller = c
        engine.onMicFrame(ShortArray(1280))
        return sent.firstOrNull { it.first == Msg.VOICE_ASSISTANT_REQUEST }?.second?.string(5)
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            assertTrue(what, System.currentTimeMillis() < until)
            Thread.sleep(10)
        }
    }

    @Test
    fun whileTheNewWordLoadsTheOldOneStillAnswersAsItself() {
        val e = engine()
        eventually("Hey Jarvis loaded") { phraseHeard() == "Hey Jarvis" }
        e.setActive(listOf("okay_nabu"))
        assertEquals("Hey Jarvis", phraseHeard())
        nabuGate.countDown()
        eventually("Okay Nabu loaded") { phraseHeard() == "Okay Nabu" }
        eventually("the old model is closed") { jarvis.closed }
    }

    /** The overlay's orb reads [VoiceInput.onMicLevel]: each frame streamed to Home Assistant, then 0 when the run ends. */
    @Test
    fun eachStreamedFrameReportsItsLevelAndTheEndReportsZero() {
        val e = engine()
        eventually("Hey Jarvis loaded") { phraseHeard() == "Hey Jarvis" } // the run is starting: frames stream
        val levels = CopyOnWriteArrayList<Float>()
        e.onMicLevel = { levels += it }
        val frame = ShortArray(1280) { (3277 * kotlin.math.sin(2 * Math.PI * 440 * it / 16_000.0)).toInt().toShort() }
        e.onMicFrame(frame)
        assertEquals(listOf(MicLevel.fromRms(MicLevel.rms(frame))), levels.toList())
        assertTrue("about -23 dBFS reads about 0.8", levels.single() in 0.75f..0.85f)
        e.controller!!.cancel()
        e.onMicFrame(ShortArray(1280))
        assertEquals(0f, levels.last())
    }

    /** A level, and the thread that reported it. */
    private data class Level(val value: Float, val thread: String)

    /**
     * Muting or stopping closes the microphone, so no frame brings the level down: the engine reports 0
     * itself (CodeRabbit on #362). The thread tells that reset apart from a silent frame, which also reads 0.
     */
    @Test
    fun mutingOrStoppingTheMicrophoneReportsTheLevelDown() {
        val e = engine()
        eventually("Hey Jarvis loaded") { phraseHeard() == "Hey Jarvis" } // the run is starting: frames stream
        val levels = CopyOnWriteArrayList<Level>()
        e.onMicLevel = { levels += Level(it, Thread.currentThread().name) }
        val loud = ShortArray(1280) { (3277 * kotlin.math.sin(2 * Math.PI * 440 * it / 16_000.0)).toInt().toShort() }
        e.onMicFrame(loud)
        assertTrue("a level above 0", levels.single().value > 0.5f)
        e.setMuted(false)
        e.setMuted(true)
        eventually("muting reports 0 from the engine's thread") { levels.any { it == Level(0f, "voice-main") } }
        e.onMicFrame(loud)
        levels.clear()
        e.stop()
        assertEquals(listOf(Level(0f, Thread.currentThread().name)), levels.toList())
    }

    @Test
    fun aWordThatFailsToLoadStopsTheOldOneAndSaysWhy() {
        nabuFails = true
        val e = engine()
        eventually("Hey Jarvis loaded") { phraseHeard() == "Hey Jarvis" }
        e.setActive(listOf("okay_nabu"))
        nabuGate.countDown()
        eventually("the error is reported") { errors.isNotEmpty() }
        assertEquals(listOf("Downloading the wake word model okay_nabu.tflite failed: offline"), errors)
        assertEquals("no word answers for Okay Nabu", null, phraseHeard())
        assertTrue(jarvis.closed)
        assertEquals("HA's choice stays", listOf("okay_nabu"), e.activeIds())
    }

    @Test
    fun aModelThatFinishesLoadingAfterStopIsClosedNotInstalled() {
        val e = engine("okay_nabu")
        assertTrue("the load started", nabuLoading.await(5, TimeUnit.SECONDS))
        e.stop()
        nabuGate.countDown()
        eventually("the late model is closed") { nabu.closed }
        assertEquals("nothing answers after stop", null, phraseHeard())
    }
}
