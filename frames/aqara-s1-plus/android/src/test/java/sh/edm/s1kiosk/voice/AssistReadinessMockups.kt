package sh.edm.s1kiosk.voice

import android.app.Application
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.esphome.WakeWordInfo
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.paneRow
import dev.glassworks.pane.PaneContext
import dev.glassworks.platform.AppBuild
import dev.glassworks.voice.ModelFailure
import dev.glassworks.voice.VoiceAssistantController
import dev.glassworks.voice.VoiceInput
import dev.glassworks.voice.VoiceReadiness
import dev.glassworks.voice.WakeWords
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.PanePrefs
import sh.edm.s1kiosk.esphome.UrlPlayer
import sh.edm.s1kiosk.settings.KioskSettingsOverlay
import sh.edm.s1kiosk.settings.PaneFixtures
import sh.edm.s1kiosk.ui.Mockup
import java.io.File

/**
 * #458: the Assist page when voice can't get ready, on the panel (PNG, 1440×720) and on the Manage page
 * (HTML), one pair per state. Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*AssistReadinessMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistReadinessMockups {
    private val app = RuntimeEnvironment.getApplication()
    private val panes = PaneFixtures(app)
    private val now = 1_000_000L

    @Before fun interFromSources() = Mockup.interFromSources()
    @After fun shutdown() = panes.shutdown()

    private class Fixed(override val readiness: VoiceReadiness) : VoiceInput {
        override var controller: VoiceAssistantController? = null
        override var threshold = 0.97f
        override var onError: ((String) -> Unit)? = null
        override fun start(muted: Boolean) {}
        override fun stop() {}
        override fun setMuted(m: Boolean) {}
        override fun setGain(g: Float) {}
        override fun available() = WakeWords.MICRO.map { WakeWordInfo(it.id, it.phrase) }
        override fun activeIds() = listOf("okay_nabu")
        override fun setActive(ids: List<String>) {}
    }

    /** Voice on, listening for Okay Nabu, with its engine in [r]. */
    private fun voice(r: VoiceReadiness): AssistPane {
        PanePrefs(app).voice.apply { wakeWord = "okay_nabu"; micMuted = false; micGain = 1.5f; wakeSound = true; overlay = true }
        return AssistPane(
            PaneContext(
                app, PanePrefs(app)::paneFile,
                DeviceIdentity("aqara-s1-plus-000001", "Aqara S1 Plus", "02:00:00:00:00:01", "2026.10.2", "x", "Aqara", "Panel Hub S1 Plus", 0),
                publishStates = {}, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
                build = AppBuild("1.0.0", 10000, debug = false),
            ),
            player = object : UrlPlayer { override fun play(urls: List<String>, onDone: (Boolean) -> Unit) {}; override fun stop() {} },
            speaker = object : SpeakerMedia { override val playing = false; override val announcing = false; override fun pause() {}; override fun resume() {} },
            resolveUrl = { it }, wakeChime = {}, onVoiceEvent = {}, createInput = { _, _, _ -> Fixed(r) },
        ).also { it.clock = { now }; it.start() }
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        val out = File(Mockup.dir!!, "458").apply { mkdirs() }
        val states = listOf(
            "download-failed" to VoiceReadiness.ModelFailed(ModelFailure.Certificate, now + 4 * 60_000),
            "check-failed" to VoiceReadiness.ModelFailed(ModelFailure.CheckFailed, now + 30_000),
            "no-microphone" to VoiceReadiness.MicrophoneUnavailable,
            "ready" to VoiceReadiness.Ready,
        )
        for ((name, r) in states) {
            val pane = voice(r)
            val model = panes.model(listOf(pane))
            val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
            o.reload = model
            o.show(model())
            o.previewSection("assist")
            Mockup.idle()
            Mockup.layout(o)
            Mockup.save("458/$name-panel", o)
            val f = ManageFixture()
            f.statusFn = { model().let { m -> f.status.copy(panes = m.sections.filter { it.id in m.panes }.map { paneRow(it.id, it) }, sections = m.sections) } }
            val b = f.Browser().also { it.signInWithHa() }
            File(out, "$name-manage.html").writeBytes(b.get("/assist").body)
            pane.stop()
        }
    }
}
