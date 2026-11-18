package dev.glassworks.settings

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
import sh.edm.s1kiosk.KioskApp
import dev.glassworks.KioskPrefs
import sh.edm.s1kiosk.frame.AqaraS1Plus
import dev.glassworks.radio.FixedReadout
import dev.glassworks.radio.radioDefinition
import dev.glassworks.ui.Mockup

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
        KioskPrefs(app).apply { urlSetting = "http://homeassistant.local:8123/lovelace/panel"; zoom = 1.25f; presenceRangeM = 1.8f }
    }

    @After fun shutdown() = panes.shutdown()

    /** The radio's mode as the root helper has it, for the S1's Device page. */
    private val props = object : dev.glassworks.radio.SysProps {
        override fun get(name: String) = when (name) {
            "persist.s1kiosk.radio_mode" -> "thread_router"
            "sys.s1kiosk.update.version" -> "1"
            else -> ""
        }
        override fun set(name: String, value: String) {}
    }

    /** The menu on [frame] with [running] panes, [off] turned off, on its Overview. */
    private fun menu(frame: Frame, frameName: String, running: List<Pane>, off: Set<String>, memory: String): KioskSettingsOverlay {
        val radio = radioDefinition(app, FixedReadout(null, running = true))
        val defs = KioskApp.runtime(frame, build = AppBuild("1.0.0", 10000, debug = false)).paneDefinitions(app).map { if (it.id == "radio" && frame is AqaraS1Plus) radio else it }
        defs.forEach { d -> d.switch?.on = d.id !in off }
        val byId = running.associateBy { it.id }
        fun model() = MenuFixture.model(app, radio, status = "screen on · presence near · voice=idle", panes = defs, running = { byId[it] }).let { m ->
            // The frame's Device page, after Screen, as S1Settings puts it.
            val device = if (frame is AqaraS1Plus) sh.edm.s1kiosk.frame.S1Device(sh.edm.s1kiosk.frame.AqaraCloud(run = { "stopped" }), props, reader = { it.run() }).section(app, frame.frameRelease) { byId[it] }
                else frame.deviceSection(app) { byId[it] }
            device?.let { lastDevice = it; lastFrame = frameName }
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

    private var lastDevice: dev.glassworks.pane.SettingsSection? = null
    private var lastFrame = ""

    /** The same Device page on the Manage page (#269), as HTML to render beside the panel's. */
    private fun manageDevice(name: String) {
        val device = lastDevice ?: return
        val f = ManageFixture()
        f.statusFn = { f.status.copy(frame = lastFrame, sections = listOf(device)) }
        val b = f.Browser().also { it.signInWithHa() }
        java.io.File(Mockup.dir!!, "$name-manage.html").writeBytes(b.get("/device").body)
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
        manageDevice("$prefix-08-device")
        // #139: the Frame version row, last on a frame with releases of its own.
        if (lastDevice?.items?.any { it is dev.glassworks.pane.SettingsItem.Info && it.title() == "Frame version" } == true) {
            shot("08b-device-frame-version", "device") { Mockup.idle(); it.previewScrollTo(Mockup.label(it, "Frame version"), alignBottom = true) }
        }
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
        aPixelTablet()
        shots("layout-tablet", 1280, 800) {
            menu(GenericAndroid(app), "Pixel Tablet", listOf(panes.voice(), panes.music(), panes.timeZone()), setOf("diagnostics"), "2.4 of 8 GB")
        }
    }

    /** Its Device page below Android 12: updates install without a tap only as device owner or as a system app (#198). */
    @Test
    @Config(sdk = [30], qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tabletOnAndroid11() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        aPixelTablet()
        val o = menu(GenericAndroid(app), "Pixel Tablet", listOf(panes.voice()), setOf("diagnostics"), "2.4 of 8 GB")
        o.previewSection("device")
        Mockup.idle()
        Mockup.save("layout-tablet-android11-08-device", o, 1280, 800)
        manageDevice("layout-tablet-android11-08-device")
    }

    /** The lululemon Mirror's Device page, portrait: its frame version from before Kiln's stamp (#139). */
    @Test
    @Config(qualifiers = "w480dp-h960dp-port-hdpi")
    fun mirror() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        app.applicationInfo.targetSdkVersion = 35
        val o = menu(sh.edm.s1kiosk.frame.TestFrames.mirror(app), "lululemon Studio Mirror", listOf(panes.voice()), setOf("diagnostics"), "1.1 of 2 GB")
        o.previewSection("device")
        Mockup.idle()
        Mockup.save("layout-mirror-08-device", o, Mockup.H, Mockup.W)
        manageDevice("layout-mirror-08-device")
    }

    private fun aPixelTablet() {
        app.applicationInfo.targetSdkVersion = 35 // the app's; Robolectric's bare Application says 23
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
        for (f in listOf(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_CAMERA_ANY)) {
            shadowOf(app.packageManager).setSystemFeature(f, true)
        }
    }
}
