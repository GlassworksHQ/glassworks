package dev.glassworks.s1.frame

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.text
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.platform.AppBuild
import dev.glassworks.testing.RepoFiles
import dev.glassworks.web.PageShell.esc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import dev.glassworks.s1.KioskApp
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.HttpPoster
import dev.glassworks.auth.SignInServer
import dev.glassworks.s1.base.WallBasePrefs
import dev.glassworks.s1.manage.S1Manage
import dev.glassworks.radio.SysProps
import dev.glassworks.settings.S1Settings

/**
 * The frame's Device page (maintainer, 2026-11-15): exactly these rows on each frame, under Panel after
 * Screen, the same on the Manage page; Block Aqara cloud keeps its prefs key and Home Assistant entity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DevicePageTest {
    private val app = RuntimeEnvironment.getApplication()

    @Before fun aPixelTablet() {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
        app.applicationInfo.targetSdkVersion = 35 // the app's; Robolectric's bare Application says 23
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false) // Robolectric keeps it from the last test
    }

    private fun titles(s: SettingsSection) = s.items.map { i ->
        when (i) {
            is SettingsItem.Switch -> i.title
            is SettingsItem.Info -> i.title()
            is SettingsItem.Link -> i.title
            is SettingsItem.Choice -> i.title
            else -> i::class.simpleName
        }
    }

    private val props = object : SysProps {
        val values = mutableMapOf("persist.s1kiosk.radio_mode" to "thread")
        override fun get(name: String) = values[name].orEmpty()
        override fun set(name: String, value: String) { values[name] = value }
    }

    private fun s1(): SettingsSection = S1Device(AqaraCloud(run = { "" }), props, reader = { it.run() }).section(app) { null }

    /** Installs [pkg] for the package manager, turned off when [off]. */
    private fun install(pkg: String, off: Boolean) {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ApplicationInfo().apply { packageName = pkg } })
        if (off) app.packageManager.setApplicationEnabledSetting(pkg, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
    }

    @Test fun theS1sRows() {
        install("com.lumi.ota", off = true)
        install("com.aqara.inhome.launcher", off = false)
        val s = s1()
        assertEquals("device", s.id)
        assertEquals("S1 Plus", s.title)
        assertEquals(listOf("Block Aqara cloud", "Aqara cloud daemon", "Aqara apps", "Radio mode", "Silent updates", "Frame loader", "Update channel"), titles(s))
        assertEquals("Turned off: Firmware updater (1 of 2)", s.items.filterIsInstance<SettingsItem.Info>().single { it.title() == "Aqara apps" }.detail())
        val radio = s.items.filterIsInstance<SettingsItem.Link>().single()
        assertEquals("opens the Radio pane", "radio", radio.section)
        assertEquals("Thread border router", radio.detail)
        assertEquals("init said nothing", "Unknown", s.items.filterIsInstance<SettingsItem.Info>().single { it.title() == "Aqara cloud daemon" }.detail())
    }

    @Test fun getpropRunsOffTheCallingThreadAtMostEveryFiveSeconds() {
        // #303 verifier: the page is built on the main thread every couple of seconds; getprop forks a process.
        val queued = ArrayList<Runnable>()
        var now = 0L
        var reads = 0
        val counting = object : SysProps {
            override fun get(name: String): String { reads++; return props.get(name) }
            override fun set(name: String, value: String) {}
        }
        val device = S1Device(AqaraCloud(run = { "" }), counting, reader = { queued += it }, clock = { now })
        fun radio() = device.section(app) { null }.items.filterIsInstance<SettingsItem.Link>().single().detail
        assertEquals("not read yet", "Unknown", radio())
        assertEquals("nothing ran on the caller's thread", 0, reads)
        queued.toList().forEach { it.run() }; queued.clear()
        assertEquals("Thread border router", radio())
        now += S1Device.STALE_MS
        radio()
        assertEquals("within five seconds: no new read", 0, queued.size)
        now += 1
        radio()
        assertEquals("then one", 1, queued.size)
    }

    @Test fun aqaraAppsTheAppCantSeeAreLeftOut() {
        assertEquals(listOf("Block Aqara cloud", "Aqara cloud daemon", "Radio mode", "Silent updates", "Frame loader", "Update channel"), titles(s1()))
    }

    @Test fun blockAqaraCloudIsTheWallBasesSettingUnderItsOwnKey() {
        val sw = s1().items.filterIsInstance<SettingsItem.Switch>().single()
        val file = app.getSharedPreferences("kiosk", Context.MODE_PRIVATE)
        var answer: String? = "not yet"
        sw.save(false) { answer = it }
        assertEquals(null, answer)
        assertFalse(file.getBoolean(WallBasePrefs.KEY_BLOCK_AQARA_CLOUD, true))
        assertEquals("block_aqara_cloud", WallBasePrefs.KEY_BLOCK_AQARA_CLOUD)
        assertFalse(dev.glassworks.s1.PanePrefs(app).wallBase.blockAqaraCloud)
        sw.save(true) {}
        assertTrue(sw.value())
    }

    @Test fun theWallBasePageLeavesTheCloudToTheDevicePage() {
        assertEquals(setOf("block_aqara_cloud", "aqara_cloud"), dev.glassworks.s1.base.WallBaseDefinition.CLOUD_ENTITIES)
    }

    private fun silent(s: SettingsSection) = s.items.filterIsInstance<SettingsItem.Info>().single { it.title() == "Silent updates" }

    @Test fun theS1sSilentUpdatesFollowTheBootImagesRootService() {
        val row = silent(s1())
        assertEquals("No: this boot image has no root update service. To change it: update the boot image with Kiln", row.detail())
        assertTrue("amber: Kiln can fix it", row.fixable())
        props.values["sys.s1kiosk.update.version"] = "1"
        val withService = S1Device(AqaraCloud(run = { "" }), props, reader = { it.run() }).section(app) { null }
        assertEquals("Yes: the boot image's root update service installs them", silent(withService).detail())
        assertFalse(silent(withService).fixable())
    }

    @Test fun aRootServicePropertyThatIsntAVersionMeansNoService() {
        for (v in listOf("", "0", "-1", "yes", "1.0", " v2 ")) {
            props.values["sys.s1kiosk.update.version"] = v
            val row = silent(S1Device(AqaraCloud(run = { "" }), props, reader = { it.run() }).section(app) { null })
            assertTrue("'$v': ${row.detail()}", row.detail().startsWith("No: this boot image has no root update service"))
        }
        props.values["sys.s1kiosk.update.version"] = " 3\n"
        val row = silent(S1Device(AqaraCloud(run = { "" }), props, reader = { it.run() }).section(app) { null })
        assertTrue("getprop's own whitespace is fine", row.detail().startsWith("Yes: "))
    }

    @Test fun theS1sSilentUpdatesSayCheckingUntilGetpropLands() {
        val queued = ArrayList<Runnable>()
        val device = S1Device(AqaraCloud(run = { "" }), props, reader = { queued += it })
        assertEquals("Checking", silent(device.section(app) { null }).detail())
        queued.forEach { it.run() }
        assertTrue(silent(device.section(app) { null }).detail().startsWith("No: "))
    }

    @Test fun genericAndroidsRows() {
        val s = GenericAndroid(app).deviceSection(app)
        assertEquals("Pixel Tablet", s.title)
        assertEquals(listOf("Silent updates", "Update channel", "Home screen", "Turn the screen off", "Device owner", "Model", "Android"), titles(s))
        val info = s.items.filterIsInstance<SettingsItem.Info>().associate { it.title() to it.detail() }
        assertEquals("Google Pixel Tablet", info["Model"])
        assertEquals("No", info["Device owner"])
        // setup's This device step (#403): neither is set on a fresh install, and each says where to set it
        assertEquals(GenericAndroid.HOME_NO, info["Home screen"])
        assertEquals(GenericAndroid.SCREEN_OFF_NO, info["Turn the screen off"])
        assertEquals(
            "Robolectric's SDK 34, Install unknown apps not allowed",
            "No: Android needs “Install unknown apps” allowed for the app. To change it: allow it in Android's settings, under Apps", info["Silent updates"],
        )
        assertTrue(silent(s).fixable())
    }

    @Test fun genericAndroidWithInstallUnknownAppsUpdatesItself() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val row = silent(GenericAndroid(app).deviceSection(app))
        assertEquals("Yes: Android 12 or newer lets the app update itself", row.detail())
        assertFalse(row.fixable())
    }

    @Test
    @Config(sdk = [29])
    fun aDeviceOwnerUpdatesWithoutATapOnAndroid10() {
        // CodeRabbit on #303: a device owner installs silently on any Android the app runs on.
        shadowOf(app.getSystemService(android.app.admin.DevicePolicyManager::class.java)).setDeviceOwner(android.content.ComponentName(app.packageName, "x.Admin"))
        val info = GenericAndroid(app).deviceSection(app).items.filterIsInstance<SettingsItem.Info>().associate { it.title() to it.detail() }
        assertEquals("Yes: the app is the device owner", info["Silent updates"])
        assertTrue(info["Device owner"]!!.startsWith("Yes"))
    }

    @Test
    @Config(sdk = [29])
    fun withoutDeviceOwnerAndroid10AsksForATapAndSaysHowToChangeIt() {
        val row = silent(GenericAndroid(app).deviceSection(app))
        assertEquals("No: Android 10 asks for a tap on each update. To change it: make the app device owner, on a reset device with no accounts", row.detail())
        assertTrue(row.fixable())
    }

    private fun frameVersion(s: SettingsSection) = s.items.filterIsInstance<SettingsItem.Info>().single { it.title() == "Frame version" }

    /** #139: an S1 without the loader in its boot image has no stamp, so it shows the version before the first. */
    @Test fun theS1sFrameVersionComesLast() {
        val s = S1Device(AqaraCloud(run = { "" }), props, reader = { it.run() }).section(app, AqaraS1Plus().frameRelease) { null }
        assertEquals(listOf("Block Aqara cloud", "Aqara cloud daemon", "Radio mode", "Silent updates", "Frame loader", "Update channel", "Frame version"), titles(s))
        assertEquals("Before 1.0.0", frameVersion(s).detail())
        assertFalse("no release checked: nothing to do", frameVersion(s).fixable())
    }

    @Test fun theMirrorsFrameVersionSitsBeforeAndroid() {
        val s = TestFrames.mirror(app).deviceSection(app)!!
        assertEquals(listOf("Silent updates", "Update channel", "Home screen", "Turn the screen off", "Device owner", "Model", "Frame version", "Android"), titles(s))
        assertEquals("Before 1.0.0 (Glass Panes image 2)", frameVersion(s).detail())
    }

    @Test fun theListIsDebloatShs() {
        val sh = RepoFiles.file("frames/aqara-s1-plus/tools/debloat/debloat.sh").readText()
        val safe = Regex("""(?m)^\s+(com\.[a-z0-9_.]+)\s+#""").findAll(sh).map { it.groupValues[1] }.toList()
        val launcher = Regex("""(?m)^LAUNCHER=(\S+)""").find(sh)!!.groupValues[1]
        assertEquals(safe + launcher, S1Device.AQARA_APPS.keys.toList())
    }

    private val tokens = HaTokenClient(object : HttpPoster {
        override fun postForm(url: String, body: String) = 0 to ""
        override fun get(url: String, maxBytes: Int) = 0 to ""
    })

    @Test fun bothSurfacesShowTheSamePageUnderPanelAfterScreen() {
        for (frame in listOf(AqaraS1Plus(), GenericAndroid(app), TestFrames.mirror(app))) {
            val runtime = KioskApp.runtime(frame, build = AppBuild("1.0.0", 10000, debug = false))
            val prefs = KioskPrefs(app)
            val panel = S1Settings.model(runtime, prefs, runtime.paneDefinitions(app), deviceSettings = false, fullScreen = {}, context = app)
            val ids = panel.sections.map { it.id }
            // Screen, then Appearance (#691), then the frame's own page.
            assertEquals("after Screen and Appearance", ids.indexOf("screen") + 2, ids.indexOf("device"))
            assertEquals("appearance", ids[ids.indexOf("screen") + 1])
            val manage = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs)
            val f = ManageFixture().apply { statusFn = { manage.status() } }
            val b = f.Browser().also { it.signInWithHa() }
            val device = manage.status().sections.single { it.id == "device" }
            assertEquals(titles(panel.sections.single { it.id == "device" }), titles(device))
            val nav = b.get("/").text().substringAfter("<h3>Panel</h3>").substringBefore("<h3>")
            assertTrue(nav, nav.contains("href=\"/device\"><span class=\"nt\">${esc(device.title)}</span>"))
            val page = b.get("/device").text()
            for (t in titles(device)) assertTrue("$t on the Manage page", page.contains(esc(t.toString())))
            assertEquals("#139: a Frame version row only where the frame has releases", frame.frameRelease != null, "Frame version" in titles(device))
        }
    }
}
