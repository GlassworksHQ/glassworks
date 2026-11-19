/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.generic_android.BuildConfig
import dev.glassworks.generic_android.R
import dev.glassworks.pane.CantRun
import dev.glassworks.platform.AppBuild
import dev.glassworks.screen.AmbientLight
import dev.glassworks.screen.ProximityPresence
import dev.glassworks.testing.RepoFiles
import java.net.InetAddress
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.HttpPoster
import dev.glassworks.auth.SignInServer
import dev.glassworks.device.MdnsAdvertiser
import dev.glassworks.device.PanelDevice
import dev.glassworks.manage.AppManage
import dev.glassworks.settings.S1Settings
import dev.glassworks.update.ReleaseLine
import dev.glassworks.update.UpdateManager
import dev.glassworks.assist.AssistDefinition

/**
 * #23: Glassworks for generic Android is its own app on the core: its own id and name, Android's standard
 * sensors, and a fixed base set of panes (Assist with its timers, Sendspin, the camera, diagnostics). A pane the device has no hardware
 * for is hidden everywhere: the settings, the Manage page, setup and Home Assistant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GenericAppTest {
    private val app = RuntimeEnvironment.getApplication()
    private val build = AppBuild("1.0.0", 10000, debug = false, product = GenericApp.PRODUCT)
    private val devices = ArrayList<PanelDevice>()
    private val exec = Executors.newSingleThreadScheduledExecutor()

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
        KioskPrefs(app).deviceMac = "02:00:00:A1:B2:C3"
    }

    @After fun tearDown() {
        devices.forEach { runCatching { it.stop() } }
        exec.shutdownNow()
    }

    private fun runtime() = GenericApp.runtime(GenericApp.frame(app), build)

    /** Starts the device as KioskService does, on this thread, and returns what it announced. */
    private fun run(runtime: GlassworksRuntime): Pair<DeviceIdentity, PanelDevice> {
        val announced = ArrayList<DeviceIdentity>()
        val mdns = object : MdnsAdvertiser {
            override fun start(identity: DeviceIdentity, port: Int, encrypted: Boolean, provisionable: Boolean) { announced += identity }
            override fun stop() {}
        }
        val device = PanelDevice(app, runtime, runtime::devicePanes, onApiHello = { _, _ -> }, exec = exec, mdns = mdns, apiPort = 0,
            apiBindAddress = InetAddress.getLoopbackAddress())
        devices += device
        device.startInternal()
        return announced.single() to device
    }

    private fun manage(runtime: GlassworksRuntime): AppManage {
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        return AppManage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, KioskPrefs(app))
    }

    @Test fun itIsItsOwnAppNotTheS1s() {
        assertEquals("dev.glassworks.generic_android", BuildConfig.APPLICATION_ID)
        assertEquals("Glassworks", app.getString(R.string.app_name))
        assertEquals("Glassworks", GenericApp().build.product)
    }

    /** #23: the service's notification says the app's name, which the core once hard-coded as "S1 Kiosk". */
    @Test @Config(application = GenericApp::class)
    fun theServicesNotificationSaysTheAppsName() {
        val service = org.robolectric.Robolectric.buildService(dev.glassworks.KioskService::class.java).get()
        val build = dev.glassworks.KioskService::class.java.getDeclaredMethod("buildNotification").apply { isAccessible = true }
        val n = build.invoke(service) as android.app.Notification
        assertEquals("Glassworks", n.extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    @Test fun theManifestRunsTheCoresShellAndCanBeTheHomeApp() {
        val pm = app.packageManager
        for (category in listOf(Intent.CATEGORY_HOME, Intent.CATEGORY_LAUNCHER)) {
            val found = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(app.packageName), 0)
            assertEquals(category, listOf(GenericParts.LAUNCHER), found.map { it.activityInfo.name })
        }
        val info = pm.getPackageInfo(app.packageName, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_ACTIVITIES)
        assertTrue(info.services.orEmpty().any { it.name == "dev.glassworks.KioskService" })
        assertTrue(info.receivers.orEmpty().map { it.name }.containsAll(listOf("dev.glassworks.BootReceiver", "dev.glassworks.update.UpdateInstallReceiver")))
        val main = info.activities.orEmpty().single { it.name == "dev.glassworks.MainActivity" }
        assertEquals("other apps can't start it with settings in its extras", "android.permission.WRITE_SECURE_SETTINGS", main.permission)
    }

    /** CodeRabbit on #364: an app could have set the dashboard's address or the API key through MainActivity's extras. */
    @Test fun onlyAdbAndTheAppItselfChangeSettingsWithExtras() {
        val parts = GenericParts()
        fun start(cls: String) = Intent().setClassName(app.packageName, cls).putExtra("url", "http://192.0.2.66/").putExtra("api_encryption", false)
        val viaLauncher = start(GenericParts.LAUNCHER)
        assertTrue("dropped", dev.glassworks.MainActivity.screenExtras(viaLauncher, parts::trustsExtras))
        assertNull(viaLauncher.extras)
        val direct = start("dev.glassworks.MainActivity")
        assertFalse("kept", dev.glassworks.MainActivity.screenExtras(direct, parts::trustsExtras))
        assertEquals("http://192.0.2.66/", direct.getStringExtra("url"))
    }

    /** #23: it updates from its own releases (generic-android-v…), never the S1 app's, and says so to GitHub. */
    @Test fun itUpdatesFromItsOwnReleases() {
        val runtime = runtime()
        assertSame(ReleaseLine.GENERIC_ANDROID, runtime.releases)
        val updates = UpdateManager(app, runtime) {}
        try {
            assertEquals("Glassworks", updates.model.title)
            val open = UpdateManager::class.java.getDeclaredMethod("open", String::class.java).apply { isAccessible = true }
            val c = open.invoke(updates, "https://192.0.2.10/") as java.net.HttpURLConnection
            assertEquals("glassworks-generic-android/1.0.0 (+https://github.com/GlassworksHQ/glassworks)", c.getRequestProperty("User-Agent"))
        } finally {
            updates.stop()
        }
    }

    /** #198: the legacy build (its own id) updates from its own line, and this build never from that one. */
    @Test fun theLegacyBuildUpdatesFromItsOwnLine() {
        assertSame(ReleaseLine.GENERIC_ANDROID_LEGACY, GenericApp.releaseLine("dev.glassworks.generic_android.legacy"))
        assertSame(ReleaseLine.GENERIC_ANDROID, GenericApp.releaseLine(BuildConfig.APPLICATION_ID))
        assertSame("its debug build too", ReleaseLine.GENERIC_ANDROID_LEGACY, GenericApp.releaseLine("dev.glassworks.generic_android.legacy.debug"))
        assertSame(ReleaseLine.GENERIC_ANDROID, GenericApp.releaseLine("dev.glassworks.generic_android.debug"))
        assertSame("only that id", ReleaseLine.GENERIC_ANDROID, GenericApp.releaseLine("dev.glassworks.generic_android.legacy.other"))
    }

    /** #198: the legacy engine stays on the dashboard's origin, which it takes once, at the first open. */
    @Test fun theEngineStartsWithTheDashboard() {
        val prefs = KioskPrefs(app)
        prefs.url = "http://192.0.2.10:8123/lovelace/0"
        assertEquals("http://192.0.2.10:8123/lovelace/0", GenericApp.engineSettings(app, build).dashboard)
        prefs.url = ""
        assertNull("none set up yet", GenericApp.engineSettings(app, build).dashboard)
    }

    /**
     * #556: the /system copy asks to change networks it didn't save, so Improv can replace the one Kiln wrote into the
     * Mirror's image when its password is wrong. An update can never add the permission.
     */
    @Test fun everyBuildAsksToReplaceASavedNetwork() {
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertTrue("android.permission.OVERRIDE_WIFI_CONFIG" in requested)
    }

    /**
     * #198: on the Mirror, Kiln flashes the app into /system/priv-app, where Android 6 grants these. An update
     * gets only the privileged permissions its /system copy asked for, so every build asks for them. And Kiln
     * makes it the Mirror's home screen only with MAIN, HOME and DEFAULT in one intent filter (#385).
     */
    @Test fun everyBuildAsksForThePrivilegedPermissionsAndHasOneHomeFilter() {
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        // #435: MANAGE_USB and READ_LOGS let the Manage page allow a new computer over adb.
        for (p in listOf("android.permission.INSTALL_PACKAGES", "android.permission.REBOOT", "android.permission.WRITE_SECURE_SETTINGS",
                "android.permission.MANAGE_USB", "android.permission.READ_LOGS")) {
            assertTrue(p, p in requested)
        }
        val manifest = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(RepoFiles.file("frames/generic-android/android/src/main/AndroidManifest.xml"))
        val ns = "http://schemas.android.com/apk/res/android"
        fun names(filter: org.w3c.dom.Element, tag: String) = filter.getElementsByTagName(tag).let { l ->
            (0 until l.length).map { (l.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name").substringAfterLast('.') }
        }
        val filters = manifest.getElementsByTagName("intent-filter").let { l -> (0 until l.length).map { l.item(it) as org.w3c.dom.Element } }
        val home = filters.filter { "HOME" in names(it, "category") }
        assertEquals("one home filter", 1, home.size)
        assertEquals(listOf("MAIN"), names(home.single(), "action"))
        assertEquals(setOf("HOME", "DEFAULT"), names(home.single(), "category").toSet())
        assertEquals("no <data>: the HOME intent carries none", 0, home.single().getElementsByTagName("data").length)
        assertEquals(GenericParts.LAUNCHER, (home.single().parentNode as org.w3c.dom.Element).getAttributeNS(ns, "name"))
    }

    @Test fun theFrameReadsAndroidsStandardSensors() {
        val frame = GenericApp.frame(app)
        assertEquals("generic-android", frame.board)
        assertTrue(frame.presence is ProximityPresence)
        assertTrue(frame.light is AmbientLight)
        assertNotNull(runtime().screen)
    }

    @Test fun theScreenPageHasNothingOfTheS1sSensors() {
        // A tablet with no proximity or light sensor, as Robolectric's: #471, seen on the Pixel Tablet on 2026-11-17.
        val runtime = runtime()
        val items = S1Settings.model(runtime, KioskPrefs(app), runtime.paneDefinitions(app), deviceSettings = false, fullScreen = {}, context = app)
            .sections.single { it.id == "screen" }.items
        val screen = items.map(dev.glassworks.pane.SettingsChange::titleOf)
        for (row in listOf("Presence range", "Brightness")) assertFalse(screen.toString(), row in screen)
        assertTrue(screen.toString(), "Turn off when idle" in screen)
        // Its camera could tell the screen someone's near (#463): the row says so and opens the Camera page.
        val wake = items.first() as dev.glassworks.pane.SettingsItem.Link
        assertEquals("Turn on the Camera pane to use it", wake.detail)
        assertEquals("camera", wake.section)
    }

    @Test fun aTabletRunsTheBaseSetAndAnnouncesItselfAsGlassworks() {
        val runtime = runtime()
        val definitions = runtime.paneDefinitions(app)
        assertEquals(listOf("assist", "sendspin", "camera", "diagnostics"), definitions.map { it.id })
        assertTrue(definitions.none { it is CantRun })

        val (id, device) = run(runtime)
        assertEquals("Pixel Tablet (Glassworks)", id.model)
        assertEquals("Glassworks 1.0.0", id.compilationTime)
        assertEquals(listOf("sendspin", "assist", "timers", "camera", "diagnostics"), device.paneList().map { it.id })
        assertTrue("its camera's entities", device.entityList().map { it.objectId }.containsAll(listOf("camera", "camera_presence", "camera_privacy", "camera_pane")))
        assertFalse("nothing of the S1", device.entityList().any { it.objectId.startsWith("radio_") || it.objectId.startsWith("base_") })
    }

    /** The Pixel Tablet docks (its frame.json's `dock`): this app gives Home Assistant its Docked sensor; another tablet has none until it docks (DockedEntityTest). */
    @Test fun aPixelTabletHasTheDockedSensor() {
        val (_, device) = run(runtime())
        val docked = device.entityList().single { it.objectId == "docked" }
        assertEquals("Docked", docked.name)
        assertFalse("on by default", docked.disabledByDefault)
    }

    @Test fun anOrdinaryTabletHasNoDockedSensor() {
        ShadowBuild.setModel("Lenovo TB-X606F")
        val (_, device) = run(runtime())
        assertTrue(device.entityList().none { it.objectId == "docked" })
    }

    @Test fun aPaneTheDeviceCantRunIsHiddenEverywhere() {
        features(PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_CAMERA_ANY) // a speaker and a camera, no microphone
        val runtime = runtime()
        assertEquals(listOf("assist"), runtime.paneDefinitions(app).filterIsInstance<CantRun>().map { it.id })

        val menu = S1Settings.model(runtime, KioskPrefs(app), runtime.paneDefinitions(app), deviceSettings = false, fullScreen = {}, context = app)
        assertFalse("not in the settings", menu.sections.any { it.id == "assist" })
        assertTrue(menu.sections.any { it.id == "sendspin" })

        val status = manage(runtime).status()
        assertFalse("not on the Manage page", status.panes.any { it.id == "assist" } || status.sections.any { it.id == "assist" })
        assertTrue(status.panes.any { it.id == "sendspin" })

        val assistCard = AssistDefinition(app.getSharedPreferences("kiosk", 0)).setupChoice?.title
        assertFalse("no card in setup", runtime.setupPanes(app).any { it.choice.title == assistCard })
        assertFalse("no step in setup", runtime.setupSteps(app).any { it.id.startsWith("assist") })

        val (id, device) = run(runtime)
        assertEquals("no voice features in Home Assistant", 0, id.voiceFeatures)
        assertEquals("timers ride with Assist", listOf("sendspin", "camera", "diagnostics"), device.paneList().map { it.id })
        assertFalse(device.entityList().any { it.objectId == "mute_microphone" })
    }

    /** #463: a tablet without a camera has the camera pane as CantRun: no page, no setup card or step, no entity. */
    @Test fun aDeviceWithoutACameraHasNoCameraAnywhere() {
        features(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE)
        val runtime = runtime()
        assertEquals(listOf("camera"), runtime.paneDefinitions(app).filterIsInstance<CantRun>().map { it.id })
        val menu = S1Settings.model(runtime, KioskPrefs(app), runtime.paneDefinitions(app), deviceSettings = false, fullScreen = {}, context = app)
        assertFalse("not in the settings", menu.sections.any { it.id == "camera" })
        assertFalse("no step in setup", runtime.setupSteps(app).any { it.id == "camera" })

        val (_, device) = run(runtime)
        assertEquals(listOf("sendspin", "assist", "timers", "diagnostics"), device.paneList().map { it.id })
        val ids = device.entityList().map { it.objectId }
        assertEquals("no camera entity in Home Assistant", emptyList<String>(), ids.filter { it.startsWith("camera") || it == "onvif_stream" })
        assertTrue("the core's entities are there", "app_version" in ids)
    }

    @Test fun aDeviceWithNoneOfThatHardwareRunsOnlyDiagnosticsAndTheCore() {
        features()
        val runtime = runtime()
        assertEquals(listOf("assist", "sendspin", "camera"), runtime.paneDefinitions(app).filterIsInstance<CantRun>().map { it.id })
        val (_, device) = run(runtime)
        assertEquals(listOf("diagnostics"), device.paneList().map { it.id })
        assertTrue("the core's entities are there", device.entityList().any { it.objectId == "app_version" })
    }

    /**
     * Diagnostics needs no hardware, so every device runs it: its page on the panel's settings and on Manage (the same
     * section, as for every pane), and its sensors in Home Assistant. No frame here names a CPU thermal zone, so there
     * is no temperature entity and no temperature tile, rather than a 0.
     */
    @Test fun diagnosticsIsOnThePanelOnManageAndInHomeAssistant() {
        val runtime = runtime()
        val (_, device) = run(runtime)
        runtime.device = device
        val ids = device.entityList().map { it.objectId }
        for (id in listOf("diagnostics", "cpu_usage", "memory_available", "app_memory", "storage_free", "wifi_signal", "uptime", "app_uptime", "last_restart_reason")) {
            assertTrue(id, id in ids)
        }
        assertFalse("no thermal zone, no temperature", "cpu_temperature" in ids)

        val menu = S1Settings.model(runtime, KioskPrefs(app), runtime.paneDefinitions(app), deviceSettings = false, fullScreen = {}, context = app)
        val page = menu.sections.single { it.id == "diagnostics" }
        assertTrue("a pane in the rail", "diagnostics" in menu.panes)
        val tiles = page.items.filterIsInstance<dev.glassworks.pane.SettingsItem.Tiles>().single().tiles().map { it.name }
        assertEquals(listOf("CPU", "RAM", "Wi-Fi", "Storage", "Uptime"), tiles)

        val status = manage(runtime).status()
        assertTrue("on Manage's pane list", status.panes.any { it.id == "diagnostics" })
        assertEquals("the same page on Manage", tiles,
            status.sections.single { it.id == "diagnostics" }.items.filterIsInstance<dev.glassworks.pane.SettingsItem.Tiles>().single().tiles().map { it.name })
        runtime.device = null
    }

    /** The launcher icon and the splash draw the mark branding/boot/splash_mark.py writes for the S1 app: a copy, kept equal. */
    @Test fun theMarkIsTheBrandsAsTheS1AppsIs() {
        val own = java.io.File(RepoFiles.root, "frames/generic-android/android/src/main/res/drawable/glassworks_mark.xml")
        assertEquals(java.io.File(RepoFiles.s1App, "src/main/res/drawable/glassworks_mark.xml").readText(), own.readText())
    }

    @Test fun theManagePageNamesTheApp() {
        val diagnostics = manage(runtime()).diagnostics()
        assertTrue(diagnostics, diagnostics.lineSequence().first().startsWith("Version: Glassworks 1.0.0 "))
        assertFalse("no root side here", "Root side" in diagnostics)
        val status = manage(runtime()).status()
        assertEquals("MPL-2.0 and MIT (Open source licences has each)", status.about.toMap()["Licence"])
        assertTrue("no pane links of the S1's", status.panes.all { it.links.isEmpty() })
    }
}
