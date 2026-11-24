package dev.glassworks.s1

import dev.glassworks.s1.frame.TestFrames
import android.app.Application
import android.content.pm.PackageManager
import dev.glassworks.frame.Capability
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.frame.NoAgent
import dev.glassworks.pane.CantRun
import dev.glassworks.platform.AppBuild
import dev.glassworks.testing.RepoFiles
import java.io.File
import java.net.InetAddress
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowLog
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.HttpPoster
import dev.glassworks.auth.SignInServer
import dev.glassworks.s1.base.BaseBackend
import dev.glassworks.device.ManualExecutor
import dev.glassworks.device.PanelDevice
import dev.glassworks.device.RecordingMdns
import dev.glassworks.s1.frame.AqaraS1Plus
import dev.glassworks.provisioning.ImprovIdentity
import dev.glassworks.s1.manage.S1Manage
import dev.glassworks.speaker.SendspinPane
import dev.glassworks.speaker.SendspinPrefs
import dev.glassworks.settings.SettingsMenu
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.glassworks
import dev.glassworks.KioskService

/**
 * #23, minimal part: the app runs the S1's frame only on the S1's hardware. Any other device runs the
 * generic Android frame: it goes by its own maker and model everywhere it names itself, and runs the
 * panes its features allow. The S1 stays as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GenericFrameTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = KioskPrefs(app)
    private val panePrefs = PanePrefs(app)
    private val devices = ArrayList<PanelDevice>()
    private val repo = RepoFiles.root

    private fun features(vararg on: String) {
        val pm = shadowOf(app.packageManager)
        for (f in listOf(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_CAMERA_ANY)) {
            pm.setSystemFeature(f, f in on)
        }
    }

    @Before fun aPixelTablet() {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
        ShadowBuild.setHardware("tangorpro")
        features(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_CAMERA_ANY)
        panePrefs.paneFile("sendspin").edit().putBoolean(SendspinPrefs.KEY_ENABLED, false).commit()
        panePrefs.wallBase.backend = BaseBackend.LOCALSERVICE.pref
        prefs.deviceMac = "02:00:00:A1:B2:C3"
    }

    @After fun tearDown() = devices.forEach { runCatching { it.stop() } }

    private fun generic() = KioskApp.runtime(GenericAndroid(app), build = AppBuild("1.0.0", 10000, debug = false))

    /** Starts the device the way KioskService does, and returns what it announced and the panes it built. */
    private fun run(runtime: GlassworksRuntime): Pair<DeviceIdentity, PanelDevice> {
        val exec = ManualExecutor()
        val mdns = RecordingMdns()
        val device = PanelDevice(app, runtime, runtime::devicePanes, onApiHello = { _, _ -> }, exec = exec, mdns = mdns, apiPort = 0,
            apiBindAddress = InetAddress.getLoopbackAddress())
        devices += device
        device.start()
        exec.runDue()
        return mdns.identities.single() to device
    }

    @Test fun theS1sHardwarePicksTheS1sFrameAndAnythingElseTheGenericOne() {
        val generic = GenericAndroid(app)
        assertTrue(AqaraS1Plus.orElse("sun50iw10p1") { generic } is AqaraS1Plus)
        for (hw in listOf("tangorpro", "qcom", "robolectric", "")) assertEquals(hw, generic, AqaraS1Plus.orElse(hw) { generic })
    }

    @Test fun aTabletHasNoAgentSoItInstallsUpdatesWithPackageInstaller() {
        assertSame(NoAgent, GenericAndroid(app).agent)
        assertNull(GenericAndroid(app).agent.appUpdates)
    }

    @Test fun aTabletUsesItsOwnNameAndAndroidsSettingsAndRunsNoServiceWork() {
        val generic = GenericAndroid(app)
        assertNull(generic.shortName)
        assertNull(generic.systemSettingsTitle)
        assertNull(generic.serviceWork(app) {})
    }

    @Test fun aTabletAnnouncesItselfAsWhatItIs() {
        val (id, _) = run(generic())
        assertEquals("pixel-tablet-a1b2c3", id.name)
        assertEquals("Pixel Tablet", id.friendlyName)
        assertEquals("Google", id.manufacturer)
        assertEquals("Pixel Tablet (Glassworks)", id.model)
        assertEquals("it has a microphone, so it offers Assist", PanelDevice.VOICE_FEATURES, id.voiceFeatures)
    }

    /** #454: its ROM says "MSM8916 for arm64"; lululemon's boot image says what it is. */
    @Test fun aMirrorAnnouncesItselfAsTheMirror() {
        val (id, _) = run(KioskApp.runtime(TestFrames.mirror(app), build = AppBuild("1.0.0", 10000, debug = false)))
        assertEquals("lululemon-mirror-a1b2c3", id.name)
        assertEquals("lululemon Studio Mirror", id.friendlyName)
        assertEquals("lululemon", id.manufacturer)
        assertEquals("Studio Mirror (Glassworks)", id.model)
    }

    @Test fun theS1StillAnnouncesItselfAsAnS1() {
        val (id, _) = run(KioskApp.runtime(AqaraS1Plus(), build = AppBuild("1.0.0", 10000, debug = false)))
        assertEquals("aqara-s1-plus-a1b2c3", id.name)
        assertEquals("Aqara S1 Plus", id.friendlyName)
        assertEquals("Aqara", id.manufacturer)
        assertEquals("Panel Hub S1 Plus (Glassworks)", id.model)
    }

    @Test fun aTabletRunsThePanesItsHardwareAllows() {
        val runtime = generic()
        val definitions = runtime.paneDefinitions(app)
        assertEquals("the S1 frame's own panes aren't there", listOf("assist", "sendspin", "notifications", "radio", "bluetooth-proxy", "camera", "diagnostics"), definitions.map { it.id })
        assertEquals(listOf("radio"), definitions.filterIsInstance<CantRun>().map { it.id })
        assertFalse("setup asks nothing about a radio", runtime.setupSteps(app).any { it.id == "radio" })
        assertFalse(runtime.setupPanes(app).any { it.choice.title == "Radio" })

        val (_, device) = run(runtime)
        val built = device.paneList().map { it.id }
        assertEquals(listOf("sendspin", "assist", "timers", "notifications", "bluetooth-proxy", "camera", "diagnostics"), built)
        assertFalse("no radio entities in Home Assistant", device.entityList().any { it.objectId.startsWith("radio_") })
        assertTrue("a tablet with a camera has the camera pane's on/off (the S1 doesn't)", device.entityList().any { it.objectId == "camera_pane" })
    }

    @Test fun theManagePageLeavesOutTheRadio() {
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        val status = S1Manage(app, generic(), SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs).status()
        assertTrue("a pane the tablet can't run isn't shown (#23)", status.panes.none { it.id == "radio" } && status.sections.none { it.id == "radio" })
        assertEquals("Pixel Tablet", status.frame)
        assertEquals("Google Pixel Tablet", status.about.toMap()["Frame"])
    }

    @Test fun aDeviceWithoutAMicrophoneHasNoAssist() {
        features(PackageManager.FEATURE_AUDIO_OUTPUT)
        val runtime = generic()
        assertEquals(listOf("assist", "radio", "bluetooth-proxy", "camera"), runtime.paneDefinitions(app).filterIsInstance<CantRun>().map { it.id })
        val (id, device) = run(runtime)
        assertEquals("no voice features although Assist's switch is on", 0, id.voiceFeatures)
        assertFalse(device.paneList().any { it.id == "assist" })
        assertFalse("timers ride with Assist", device.paneList().any { it.id == "timers" })
        assertFalse(device.entityList().any { it.objectId == "mute_microphone" })
    }

    @Test fun musicAssistantAndImprovUseTheDevicesOwnName() {
        panePrefs.paneFile("sendspin").edit().putBoolean(SendspinPrefs.KEY_ENABLED, true).commit()
        for ((runtime, name, product, maker) in listOf(
            Quad(generic(), "Pixel Tablet", "Pixel Tablet (Glassworks)", "Google"),
            Quad(KioskApp.runtime(AqaraS1Plus(), build = AppBuild("1.0.0", 10000, debug = false)), "Aqara S1 Plus", "Panel Hub S1 Plus (Glassworks)", "Aqara"),
        )) {
            val (_, device) = run(runtime)
            val identity = (device.paneList().single { it.id == "sendspin" } as SendspinPane).player.identity
            assertEquals(name, identity.name)
            assertEquals(product, identity.productName)
            assertEquals(maker, identity.manufacturer)
            assertEquals(name, ImprovIdentity.ble(runtime.frame, runtime.build)[3])
            assertEquals(name, ImprovIdentity.serial(runtime.frame, runtime.build)[3])
            device.stop()
        }
        assertEquals("Aqara Panel Hub S1 Plus/tangorpro", ImprovIdentity.ble(AqaraS1Plus(), AppBuild("1.0.0", 10000, debug = false))[2])
        assertEquals("Allwinner A133", ImprovIdentity.serial(AqaraS1Plus(), AppBuild("1.0.0", 10000, debug = false))[2])
        assertEquals("Google Pixel Tablet/tangorpro", ImprovIdentity.ble(GenericAndroid(app), AppBuild("1.0.0", 10000, debug = false))[2])
        assertEquals("tangorpro", ImprovIdentity.serial(GenericAndroid(app), AppBuild("1.0.0", 10000, debug = false))[2])
    }

    @Test fun theAppLogsTheFrameItPicked() {
        ShadowLog.clear()
        attachedKioskApp().glassworks
        assertTrue(ShadowLog.getLogsForTag("Glassworks").any { it.msg == "Frame: generic-android (tangorpro)" })
        ShadowBuild.setHardware(AqaraS1Plus.HARDWARE)
        attachedKioskApp().glassworks
        assertTrue(ShadowLog.getLogsForTag("Glassworks").any { it.msg == "Frame: aqara-s1-plus (sun50iw10p1)" })
    }

    @Test fun theDeviceSettingsLinkNamesTheDevicesOwnApp() {
        assertEquals("Aqara settings", SettingsMenu.deviceSettingsTitle(AqaraS1Plus()))
        assertEquals("Android settings", SettingsMenu.deviceSettingsTitle(GenericAndroid(app)))
    }

    /** The data agrees with the code: the S1's `provides`, and each pane's `requires`, as in their JSON files. */
    @Test fun theFramesAndPanesMatchTheirJson() {
        fun words(a: org.json.JSONArray) = (0 until a.length()).map { a.getString(it) }.toSet()
        val s1 = JSONObject(File(repo, "frames/aqara-s1-plus/design/frame.json").readText())
        assertEquals(words(s1.getJSONArray("provides")), AqaraS1Plus().provides.map { it.id }.toSet())
        assertFalse("generic has no fixed list: it depends on the device", JSONObject(File(repo, "frames/generic-android/design/frame.json").readText()).has("provides"))

        val paneJson = RepoFiles.sources(atLeast = 8) { it.name == "pane.json" }
            .map { JSONObject(it.readText()) }.associate { it.getString("id") to words(it.getJSONArray("requires")) }
        val definitions = KioskApp.runtime(AqaraS1Plus(), build = AppBuild("1.0.0", 10000, debug = false)).paneDefinitions(app)
        for (d in definitions) assertEquals(d.id, paneJson.getValue(d.id), d.requires.map { it.id }.toSet())
        // The other way: a pane.json that requires something has a definition that gates it, or rides with one.
        val ridesWith = mapOf("timers" to "assist")
        val byId = definitions.associateBy { it.id }
        for ((id, req) in paneJson.filterValues { it.isNotEmpty() }) {
            val gate = byId[ridesWith[id] ?: id]
            assertTrue("$id requires $req but no definition gates it", gate != null)
            assertEquals(id, req, gate!!.requires.map { it.id }.toSet())
        }
        val known = Capability.entries.map { it.id }.toSet()
        for ((id, req) in paneJson) assertTrue("$id requires a word the app doesn't know: $req", known.containsAll(req))
    }

    private data class Quad(val runtime: GlassworksRuntime, val name: String, val product: String, val maker: String)
}
