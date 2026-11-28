package dev.glassworks.s1

import android.app.Application
import android.content.Context
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.gecko.GeckoEngine
import dev.glassworks.pane.SettingsItem
import dev.glassworks.platform.AppBuild
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.glassworks.esphome.TextSensorEntity
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
import dev.glassworks.settings.S1Settings
import dev.glassworks.update.UpdateManager
import dev.glassworks.assist.VoicePrefs
import java.net.HttpURLConnection
import java.net.InetAddress
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.glassworks

/**
 * The APK reads its BuildConfig once ([KioskApp.build]), and every part that reports or compares the
 * app's version gets that build from the runtime or its PaneContext (#21, S-version). The runtime here
 * carries a build no BuildConfig has, so each value below can only have come from what was handed in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppBuildTest {
    private val app = RuntimeEnvironment.getApplication()
    private val build = AppBuild("9.8.7", 90807, debug = false, testBuild = "f00ba47")
    private val runtime = KioskApp.runtime(AqaraS1Plus(), build)
    private val stops = ArrayList<() -> Unit>()

    @After fun tearDown() = stops.forEach { runCatching { it() } }

    @Test fun theApkHandsInItsBuildConfig() {
        val kiosk = attachedKioskApp()
        assertEquals(AppBuild(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.DEBUG, BuildConfig.TEST_BUILD,
            notificationIcon = R.drawable.ic_notification), kiosk.build)
        assertSame(kiosk.build, kiosk.glassworks.build)
    }

    @Test fun remoteDebuggingDefaultsToTheGivenBuildType() {
        assertEquals(true, engineSettings(KioskApp.runtime(AqaraS1Plus(), build.copy(debug = true))).remoteDebugging)
        assertEquals(false, engineSettings(KioskApp.runtime(AqaraS1Plus(), build.copy(debug = false))).remoteDebugging)
    }

    @Test fun updatesCompareAndAskWithTheGivenVersion() {
        val updates = UpdateManager(app, runtime) {}.also { stops += it::stop }
        assertEquals("9.8.7", updates.model.currentVersion)
        val open = UpdateManager::class.java.getDeclaredMethod("open", String::class.java).apply { isAccessible = true }
        val c = open.invoke(updates, "https://192.0.2.10/") as HttpURLConnection
        assertEquals("glassworks-aqara-s1-plus/9.8.7 (+https://github.com/GlassworksHQ/glassworks)", c.getRequestProperty("User-Agent"))
    }

    @Test fun improvSaysTheGivenVersion() {
        assertEquals(listOf("Glassworks", "9.8.7"), ImprovIdentity.ble(runtime.frame, build).take(2))
        assertEquals(listOf("Glassworks", "9.8.7"), ImprovIdentity.serial(runtime.frame, build).take(2))
    }

    @Test fun homeAssistantAndMusicAssistantGetTheGivenVersion() {
        val panePrefs = PanePrefs(app)
        panePrefs.paneFile("assist").edit().putBoolean(VoicePrefs.KEY_ENABLED, false).commit()
        panePrefs.wallBase.backend = BaseBackend.LOCALSERVICE.pref
        val exec = ManualExecutor()
        val mdns = RecordingMdns()
        val device = PanelDevice(app, runtime, runtime::devicePanes, onApiHello = { _, _ -> }, exec = exec, mdns = mdns, apiPort = 0,
            apiBindAddress = InetAddress.getLoopbackAddress()).also { stops += it::stop }
        device.start()
        exec.runDue()

        assertEquals("Glassworks 9.8.7", mdns.identities.single().compilationTime)
        assertEquals("9.8.7", text(device.entityList().single { it.objectId == "app_version" } as TextSensorEntity))
        val sendspin = device.paneList().filterIsInstance<SendspinPane>().single()
        assertEquals("Glassworks 9.8.7", sendspin.player.identity.softwareVersion)
    }

    @Test fun theManagePageShowsTheGivenBuild() {
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        val manage = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, KioskPrefs(app))
        val status = manage.status()
        assertEquals("9.8.7 · test build f00ba47", status.version)
        val about = status.sections.single { it.id == "about" }.items.filterIsInstance<SettingsItem.Info>().first()
        assertEquals("Glassworks 9.8.7 · test build f00ba47", about.title())
        assertEquals("Version: Glassworks 9.8.7 · test build f00ba47 (90807)", manage.diagnostics().lineSequence().first())
    }

    @Test fun theSettingsMenuShowsTheGivenBuild() {
        val model = S1Settings.model(runtime, KioskPrefs(app), emptyList(), deviceSettings = false, fullScreen = {}, context = app)
        assertEquals("9.8.7 · test build f00ba47", model.version)
        val about = model.sections.single { it.id == "about" }.items.filterIsInstance<SettingsItem.Info>().first()
        assertEquals("Glassworks 9.8.7 · test build f00ba47", about.title())
    }

    /**
     * #23: an app on the core announces itself by its own name, which only [AppBuild.product] carries. The S1
     * app hands in no name and keeps the default, "Glassworks" (the tests above), so this one uses a name no app has.
     */
    @Test fun anotherAppAnnouncesItsOwnName() {
        val other = build.copy(product = "Glassworks Tablet")
        val r = KioskApp.runtime(AqaraS1Plus(), other)
        assertEquals(listOf("Glassworks Tablet", "9.8.7"), ImprovIdentity.ble(r.frame, other).take(2))
        assertEquals(listOf("Glassworks Tablet", "9.8.7"), ImprovIdentity.serial(r.frame, other).take(2))
        assertEquals("Glassworks Tablet", UpdateManager(app, r) {}.also { stops += it::stop }.model.title)
        val about = S1Settings.model(r, KioskPrefs(app), emptyList(), deviceSettings = false, fullScreen = {}, context = app)
            .sections.single { it.id == "about" }.items.filterIsInstance<SettingsItem.Info>().first()
        assertEquals("Glassworks Tablet 9.8.7 · test build f00ba47", about.title())

        PanePrefs(app).paneFile("assist").edit().putBoolean(VoicePrefs.KEY_ENABLED, false).commit()
        PanePrefs(app).wallBase.backend = BaseBackend.LOCALSERVICE.pref
        val exec = ManualExecutor()
        val mdns = RecordingMdns()
        val device = PanelDevice(app, r, r::devicePanes, onApiHello = { _, _ -> }, exec = exec, mdns = mdns, apiPort = 0,
            apiBindAddress = InetAddress.getLoopbackAddress()).also { stops += it::stop }
        device.start()
        exec.runDue()
        assertEquals("Glassworks Tablet 9.8.7", mdns.identities.single().compilationTime)
        assertEquals("Panel Hub S1 Plus (Glassworks Tablet)", mdns.identities.single().model)
        assertEquals("Glassworks Tablet 9.8.7", device.paneList().filterIsInstance<SendspinPane>().single().player.identity.softwareVersion)
    }

    /** What the runtime's engine starts Gecko with. */
    @Suppress("UNCHECKED_CAST")
    private fun engineSettings(r: GlassworksRuntime): EngineSettings {
        val gecko = (r.engine as? dev.glassworks.engine.vitro.VitroEngine)?.fallback ?: r.engine
        return (GeckoEngine::class.java.getDeclaredField("settings").apply { isAccessible = true }.get(gecko) as (Context) -> EngineSettings)(app)
    }

    /** The state a text sensor sends Home Assistant. */
    @Suppress("UNCHECKED_CAST")
    private fun text(e: TextSensorEntity): String? =
        (TextSensorEntity::class.java.getDeclaredField("get").apply { isAccessible = true }.get(e) as () -> String?)()
}
