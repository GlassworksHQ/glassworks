package dev.glassworks.diagnostics

import android.app.Application
import dev.glassworks.KioskPrefs
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.paneRow
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.radio.FixedReadout
import dev.glassworks.radio.radioDefinition
import dev.glassworks.settings.KioskSettingsOverlay
import dev.glassworks.settings.MenuFixture
import dev.glassworks.settings.PaneFixtures
import dev.glassworks.ui.Mockup
import java.io.File
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.glassworks.s1.PanePrefs

/**
 * The generic app's Diagnostics page in portrait at 1080x1920 (the lululemon Mirror's glass, a tablet stood up), on
 * the panel and on Manage. Its frames name no CPU thermal zone, so the page has no temperature tile. A real
 * [DiagnosticsPane] fed an hour of made-up samples. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*DiagnosticsMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w540dp-h960dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsMockups {
    private val app = RuntimeEnvironment.getApplication()
    private val panes = PaneFixtures(app)

    @Before fun setUp() {
        Mockup.interFromSources()
        KioskPrefs(app).apply { urlSetting = "http://homeassistant.local:8123/lovelace/panel"; zoom = 1.25f }
    }

    @After fun tearDown() = panes.shutdown()

    @Test fun portrait() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        val radio = radioDefinition(app, FixedReadout(null, running = true))
        val voice = MenuFixture.cardOnly("assist", "Voice", "Assist and timers")
        val music = MenuFixture.cardOnly("sendspin", "Music", "A Sendspin speaker")
        val camera = MenuFixture.cardOnly("camera", "Camera", "Presence and snapshots")
        val diagnostics = DiagnosticsDefinition(PanePrefs(app).paneFile("diagnostics")).also { it.switch.on = true }
        val pane = panes.diagnostics(hasTemperature = false)

        // Before: main's generic app, without the pane. After: with it, running.
        shot("diagnostics-generic-01-before-about", listOf(voice, music, camera), null, "about")
        shot("diagnostics-generic-02-after-about", listOf(voice, music, camera, diagnostics), pane, "about")
        shot("diagnostics-generic-03-after-page", listOf(voice, music, camera, diagnostics), pane, "diagnostics")

        // The same page on Manage (:8099), from the same model.
        val model = MenuFixture.model(app, radio, panes = listOf(voice, music, camera, diagnostics), running = { if (it == "diagnostics") pane else null })
        val f = ManageFixture()
        f.statusFn = { f.status.copy(frame = "lululemon Mirror", panes = model.sections.filter { it.id in model.panes }.map { paneRow(it.id, it) }, sections = model.sections) }
        val b = f.Browser().also { it.signInWithHa() }
        val out = File(Mockup.dir!!, "manage").apply { mkdirs() }
        File(out, "diagnostics-generic-04-manage-overview.html").writeBytes(b.get("/").body)
        File(out, "diagnostics-generic-05-manage-page.html").writeBytes(b.get("/diagnostics").body)
    }

    private fun shot(name: String, defs: List<PaneDefinition>, pane: DiagnosticsPane?, section: String) {
        val radio = radioDefinition(app, FixedReadout(null, running = true))
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        o.show(MenuFixture.model(app, radio, status = "screen on", panes = defs, running = { if (it == "diagnostics") pane else null }).copy(frame = "lululemon Mirror"))
        o.previewSection(section)
        Mockup.idle()
        Mockup.layout(o, 1080, 1920)
        Mockup.save(name, o, 1080, 1920)
    }
}
