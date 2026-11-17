package sh.edm.s1kiosk.voice

import android.app.Application
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.esphome.WakeWordInfo
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PaneState
import dev.glassworks.pane.SettingsItem
import dev.glassworks.platform.AppBuild
import dev.glassworks.voice.ModelFailure
import dev.glassworks.voice.VoiceAssistantController
import dev.glassworks.voice.VoiceInput
import dev.glassworks.voice.VoiceReadiness
import dev.glassworks.voice.WakeWords
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.PanePrefs
import sh.edm.s1kiosk.esphome.UrlPlayer

/** #458: when voice can't get ready, the Assist page, the Manage page and Home Assistant say why. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AssistReadinessTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = PanePrefs(app).voice
    private val input = FakeInput()
    private var published = 0
    private var now = 1_000_000L
    private lateinit var pane: AssistPane

    @Before fun setUp() {
        prefs.wakeWord = "okay_nabu"
        prefs.micMuted = false
        pane = AssistPane(
            ctx = PaneContext(
                app, PanePrefs(app)::paneFile,
                DeviceIdentity(
                    name = "test", friendlyName = "Test", macAddress = "02:00:00:00:00:01", esphomeVersion = "x",
                    compilationTime = "x", manufacturer = "x", model = "x", voiceFeatures = 0,
                ),
                publishStates = { published++ }, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
                build = AppBuild("1.0.0", 10000, debug = false),
            ),
            player = object : UrlPlayer {
                override fun play(urls: List<String>, onDone: (Boolean) -> Unit) {}
                override fun stop() {}
            },
            speaker = object : SpeakerMedia {
                override val playing = false
                override val announcing = false
                override fun pause() {}
                override fun resume() {}
            },
            resolveUrl = { it }, wakeChime = {}, onVoiceEvent = {},
            createInput = { _, _, _ -> input },
        ).also { it.clock = { now } }
    }

    @After fun tearDown() = pane.stop()

    private fun entity() = pane.entities().single { it.objectId == "voice_status" }.shownState()
    private fun section() = AssistDefinition(PanePrefs(app).paneFile("assist")).settings(pane).single()
    private fun status() = section().items.filterIsInstance<SettingsItem.Status>().single()
    private fun tryAgain() = section().items.filterIsInstance<SettingsItem.Group>().flatMap { it.items }
        .filterIsInstance<SettingsItem.Action>().singleOrNull()

    private fun failWith(r: VoiceReadiness) {
        input.readiness = r
        input.onReadiness!!(r)
    }

    @Test
    fun homeAssistantReadsReadyOnceTheWakeWordListens() {
        pane.start()
        assertEquals("Getting ready", entity())
        assertEquals("Getting “Okay Nabu” ready", status().text())
        failWith(VoiceReadiness.Ready)
        assertEquals("Ready", entity())
        assertEquals("Listening for “Okay Nabu”", status().text())
        assertTrue(status().ok())
        assertFalse(status().problem())
        assertNull("nothing to try again", tryAgain())
    }

    @Test
    fun theMirrorsCertificateFailureSaysWhyWhatToDoAndWhen() {
        pane.start()
        failWith(VoiceReadiness.ModelFailed(ModelFailure.Certificate, retryAtMs = now + 4 * 60_000))
        assertEquals("Download failed", entity())
        assertEquals(1, published)
        assertEquals(
            "The wake word model couldn't be downloaded: the panel couldn't verify GitHub's certificate. Check the panel's date and time. " +
                "The wake word won't work until the panel has it. Trying again in 4 min.",
            status().text(),
        )
        assertTrue(status().problem())
        assertFalse(status().ok())
        val chip = section().status()!!
        assertEquals(PaneState.Kind.PROBLEM, chip.kind)
        assertEquals("Download failed · Assist", chip.detail)
        val rows = section().items.filterIsInstance<SettingsItem.States>().single().rows()
        assertEquals("Download failed", rows.single { it.name == "Voice status" }.value)
    }

    @Test
    fun tryAgainAsksTheEngineNow() {
        pane.start()
        failWith(VoiceReadiness.ModelFailed(ModelFailure.Offline, retryAtMs = now + 30_000))
        val row = tryAgain()!!
        assertEquals("Download failed. Trying again in 30 s.", row.detail())
        var answer: String? = "not called"
        row.run { answer = it }
        assertNull(answer)
        assertEquals(1, input.retries)
    }

    @Test
    fun eachFailureHasItsOwnStateAndLine() {
        pane.start()
        val cases = listOf(
            VoiceReadiness.ModelFailed(ModelFailure.Offline, now) to ("Download failed" to "couldn't reach GitHub"),
            VoiceReadiness.ModelFailed(ModelFailure.SecureConnection, now) to ("Download failed" to "secure connection to GitHub failed"),
            VoiceReadiness.ModelFailed(ModelFailure.Http(404), now) to ("Download failed" to "GitHub answered HTTP 404"),
            VoiceReadiness.ModelFailed(ModelFailure.CheckFailed, now) to ("Model check failed" to "failed its check"),
            VoiceReadiness.ModelFailed(ModelFailure.Storage, now) to ("Model not saved" to "Free some storage"),
            VoiceReadiness.ModelFailed(ModelFailure.Missing, now) to ("Model missing" to "Pick another wake word"),
            VoiceReadiness.EngineFailed(now) to ("Engine failed" to "engine didn't start"),
            VoiceReadiness.MicrophoneNotAllowed to ("Microphone not allowed" to "Allow Microphone for this app"),
            VoiceReadiness.MicrophoneUnavailable to ("Microphone unavailable" to "its reads fail"),
        )
        for ((r, want) in cases) {
            failWith(r)
            assertEquals("$r", want.first, entity())
            assertTrue("$r: ${status().text()}", status().text().contains(want.second))
            assertTrue("$r", status().problem())
            assertEquals("$r", VoiceStatus.retries(r), tryAgain() != null)
        }
    }

    @Test
    fun theRetryTimeCountsDown() {
        assertEquals("Trying again in 10 min.", VoiceStatus.retry(600_000, 0))
        assertEquals("Trying again in 2 min.", VoiceStatus.retry(61_000, 0))
        assertEquals("Trying again in 45 s.", VoiceStatus.retry(44_500, 0))
        assertEquals("Trying again now.", VoiceStatus.retry(0, 5_000))
    }

    @Test
    fun voiceOffHasNoState() {
        assertNull(entity())
        pane.start()
        failWith(VoiceReadiness.MicrophoneUnavailable)
        pane.stop()
        assertNull("Home Assistant shows it unknown while voice is off", entity())
    }

    @Test
    fun aMutedPanelSaysMutedWhileTheModelLoads() {
        prefs.micMuted = true
        pane.start()
        assertEquals("Microphone muted", status().text())
    }

    private class FakeInput : VoiceInput {
        override var controller: VoiceAssistantController? = null
        override var threshold = 0.5f
        override var onError: ((String) -> Unit)? = null
        override var readiness: VoiceReadiness = VoiceReadiness.Starting
        override var onReadiness: ((VoiceReadiness) -> Unit)? = null
        var retries = 0
        override fun retryNow() { retries++ }
        override fun start(muted: Boolean) {}
        override fun stop() {}
        override fun setMuted(m: Boolean) {}
        override fun setGain(g: Float) {}
        override fun available() = WakeWords.MICRO.map { WakeWordInfo(it.id, it.phrase) }
        override fun activeIds() = emptyList<String>()
        override fun setActive(ids: List<String>) {}
    }
}
