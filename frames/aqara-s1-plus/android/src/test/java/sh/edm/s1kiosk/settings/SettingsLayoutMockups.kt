package sh.edm.s1kiosk.settings

import android.app.Application
import android.content.pm.PackageManager
import dev.glassworks.frame.Frame
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.Tile
import dev.glassworks.manage.paneRow
import dev.glassworks.pane.Pane
import dev.glassworks.platform.AppBuild
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.radio.FixedReadout
import sh.edm.s1kiosk.radio.radioDefinition
import sh.edm.s1kiosk.ui.Mockup

/**
 * The settings menu's layout (approved 2026-11-15) on both frames at their own size: the S1 at 1440x720
 * (960x480 dp) and a made-up Pixel Tablet at 1280x800 (1280x800 dp, mdpi so the shot is 1:1). The
 * Overview, the core's pages, a pane's page and the Off fold. Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*SettingsLayoutMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsLayoutMockups {
    private val S1DeviceApps = sh.edm.s1kiosk.frame.S1Device.AQARA_APPS.keys
    private val app = RuntimeEnvironment.getApplication()
    private val panes = PaneFixtures(app)

    @Before fun setUp() {
        Mockup.interFromSources()
        KioskPrefs(app).apply { urlSetting = "http://homeassistant.local:8123/lovelace/panel"; zoom = 1.25f; presenceRangeM = 1.8f; nightWindow = "22:00-07:00" }
    }

    @After fun shutdown() = panes.shutdown()

    /** The radio's mode as the root helper has it, for the S1's Device page. */
    private val props = object : sh.edm.s1kiosk.radio.SysProps {
        override fun get(name: String) = if (name == "persist.s1kiosk.radio_mode") "thread_router" else ""
        override fun set(name: String, value: String) {}
    }

    /** The menu on [frame] with [running] panes, [off] turned off, on its Overview. */
    private fun menu(frame: Frame, frameName: String, running: List<Pane>, off: Set<String>, memory: String): KioskSettingsOverlay {
        val radio = radioDefinition(app, FixedReadout(null, running = true))
        val defs = GlassworksRuntime(frame, build = AppBuild("1.0.0", 10000, debug = false)).paneDefinitions(app).map { if (it.id == "radio" && frame is AqaraS1Plus) radio else it }
        defs.forEach { d -> d.switch?.on = d.id !in off }
        val byId = running.associateBy { it.id }
        fun model() = MenuFixture.model(app, radio, status = "screen on · presence near · voice=idle", panes = defs, running = { byId[it] }).let { m ->
            // The frame's Device page, after Screen, as S1Settings puts it.
            val device = if (frame is AqaraS1Plus) sh.edm.s1kiosk.frame.S1Device(sh.edm.s1kiosk.frame.AqaraCloud(run = { "stopped" }), props).section(app) { byId[it] }
                else frame.deviceSection(app) { byId[it] }
            val at = m.sections.indexOfFirst { it.id == "screen" } + 1
            m.copy(frame = frameName, sections = m.sections.toMutableList().apply { if (device != null) add(at, device) })
        }
        val f = ManageFixture()
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        o.reload = ::model
        o.overview = KioskSettingsOverlay.Overview({
            val m = model()
            f.status.copy(
                frame = frameName, version = "0.4.3",
                tiles = f.status.tiles.map { if (it.label == "Memory") Tile("Memory", memory, "Free 0.9 GB", null) else it },
                panes = m.sections.filter { it.id in m.panes }.map { paneRow(it.id, it) }, sections = m.sections,
            )
        }, f.actions)
        o.show(model())
        Mockup.idle()
        return o
    }

    private fun shots(prefix: String, w: Int, h: Int, open: () -> KioskSettingsOverlay) {
        fun shot(name: String, id: String?, then: (KioskSettingsOverlay) -> Unit = {}) {
            val o = open()
            id?.let { o.previewSection(it) }
            then(o)
            Mockup.idle()
            Mockup.save("$prefix-$name", o, w, h)
        }
        shot("01-overview", null)
        shot("02-dashboard", "dashboard")
        shot("03-screen", "screen")
        shot("04-voice", "assist")
        shot("05-off-pane", "diagnostics")
        shot("06-about", "about")
        shot("07-edit-a-value", "dashboard") { it.previewEdit("Home Assistant") }
        shot("08-device", "device")
    }

    @Test
    @Config(qualifiers = "w960dp-h480dp-land-hdpi")
    fun s1() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        // Aqara's apps as debloat.sh leaves them: all but the launcher turned off.
        for (pkg in S1DeviceApps) {
            shadowOf(app.packageManager).installPackage(android.content.pm.PackageInfo().apply { packageName = pkg; applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg } })
            if (pkg != "com.aqara.inhome.launcher") app.packageManager.setApplicationEnabledSetting(pkg, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
        }
        shots("layout-s1", Mockup.W, Mockup.H) {
            menu(AqaraS1Plus(), "S1 Plus", listOf(panes.voice(), panes.music(), panes.radio(), panes.wallBase(), panes.timeZone()), setOf("diagnostics"), "1.1 of 2 GB")
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tablet() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
        for (f in listOf(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_CAMERA_ANY)) {
            shadowOf(app.packageManager).setSystemFeature(f, true)
        }
        shots("layout-tablet", 1280, 800) {
            menu(GenericAndroid(app), "Pixel Tablet", listOf(panes.voice(), panes.music(), panes.timeZone()), setOf("diagnostics"), "2.4 of 8 GB")
        }
    }
}
