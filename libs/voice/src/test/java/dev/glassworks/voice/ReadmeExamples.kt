package dev.glassworks.voice

import android.app.Application
import android.content.Context
import dev.glassworks.esphome.ApiServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The README's examples, compiled. Keep in sync with README.md. */
internal object ReadmeExamples {
    fun assistant(context: Context, server: ApiServer, player: UrlPlayer, muted: Boolean): Pair<VoiceEngine, VoiceAssistantController> {
        val engine = VoiceEngine(context, VoiceConfig(), WakeWords.DEFAULT, onActiveWordChanged = { id -> /* store it, pass it next time */ })
        val controller = VoiceAssistantController(
            send = { type, payload -> server.sendVoice(type, payload) },
            player = player, // plays the response and announcements: your media player
            resolveUrl = { url -> if (url.startsWith("/")) "http://homeassistant.local:8123$url" else url }, // HA may send paths
            wakeWords = engine, // Home Assistant's wake word select picks from these
            emit = { event -> println(event) }, // WakeDetected, Listening, Thinking, Speaking, Idle, ...
        )
        engine.controller = controller
        server.voice = controller // Home Assistant runs Assist through the ESPHome API
        engine.start(muted = muted /* README: false */)
        // and about once a second: controller.tick()
        return engine to controller
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReadmeExamplesTest {
    @Test fun theAssistantStartsAndStops() {
        val server = ApiServer(0, { error("not connected in this test") }, emptyList(), psk = null)
        val player = object : UrlPlayer {
            override fun play(urls: List<String>, onDone: (Boolean) -> Unit) = onDone(true)
            override fun stop() {}
        }
        val (engine, controller) = ReadmeExamples.assistant(RuntimeEnvironment.getApplication(), server, player, muted = true)
        assertTrue(server.voice === controller)
        assertEquals(listOf("okay_nabu", "hey_jarvis", "alexa", "hey_mycroft"), engine.available().map { it.id })
        assertEquals(VoiceAssistantController.State.IDLE, controller.currentState)
        controller.close()
        engine.stop()
    }
}
