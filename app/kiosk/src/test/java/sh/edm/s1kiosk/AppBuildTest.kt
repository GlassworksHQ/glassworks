package sh.edm.s1kiosk

import android.app.Application
import android.content.Context
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.gecko.GeckoEngine
import dev.glassworks.manage.ManageHost
import dev.glassworks.platform.AppBuild
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.esphome.TextSensorEntity
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.HttpPoster
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.esphome.ManualExecutor
import sh.edm.s1kiosk.esphome.PanelDevice
import sh.edm.s1kiosk.esphome.RecordingMdns
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.improv.ImprovIdentity
import sh.edm.s1kiosk.manage.S1Manage
import sh.edm.s1kiosk.sendspin.SendspinPane
import sh.edm.s1kiosk.update.UpdateManager
import sh.edm.s1kiosk.voice.VoicePrefs
import java.net.HttpURLConnection
import java.net.InetAddress

/**
 * The APK reads its BuildConfig once ([KioskApp.build]), and every part that reports or compares the
 * app's version gets that build from the runtime or its PaneContext (#21, S-version). The runtime here
 * carries a build no BuildConfig has, so each value below can only have come from what was handed in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppBuildTest {
    private val app = RuntimeEnvironment.getApplication()
    private val build = AppBuild("9.8.7", 90807, "f00ba47")
    private val runtime = GlassworksRuntime(AqaraS1Plus(), build)
    private val stops = ArrayList<() -> Unit>()

    @After fun tearDown() = stops.forEach { runCatching { it() } }

    @Test fun theApkHandsInItsBuildConfig() {
        val kiosk = KioskApp()
        assertEquals(AppBuild(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.TEST_BUILD, BuildConfig.DEBUG), kiosk.build)
        assertSame(kiosk.build, kiosk.glassworks.build)
    }

    @Test fun remoteDebuggingDefaultsToTheGivenBuildType() {
        assertEquals(true, engineSettings(GlassworksRuntime(AqaraS1Plus(), build.copy(debug = true))).remoteDebugging)
        assertEquals(false, engineSettings(GlassworksRuntime(AqaraS1Plus(), build.copy(debug = false))).remoteDebugging)
    }

    @Test fun updatesCompareAndAskWithTheGivenVersion() {
        val updates = UpdateManager(app, runtime) {}.also { stops += it::stop }
        assertEquals("9.8.7", updates.model.currentVersion)
        val open = UpdateManager::class.java.getDeclaredMethod("open", String::class.java).apply { isAccessible = true }
        val c = open.invoke(updates, "https://192.0.2.10/") as HttpURLConnection
        assertEquals("s1-kiosk/9.8.7 (+https://github.com/GlassworksHQ/glassworks)", c.getRequestProperty("User-Agent"))
    }

    @Test fun improvSaysTheGivenVersion() {
        assertEquals(listOf("S1 Kiosk", "9.8.7"), ImprovIdentity.ble(runtime.frame, build).take(2))
        assertEquals(listOf("S1 Kiosk", "9.8.7"), ImprovIdentity.serial(runtime.frame, build).take(2))
    }

    @Test fun homeAssistantAndMusicAssistantGetTheGivenVersion() {
        val prefs = KioskPrefs(app)
        prefs.paneFile("assist").edit().putBoolean(VoicePrefs.KEY_ENABLED, false).commit()
        prefs.wallBase.backend = BaseBackend.LOCALSERVICE.pref
        val exec = ManualExecutor()
        val mdns = RecordingMdns()
        val device = PanelDevice(app, runtime, onVoiceEvent = {}, onApiHello = { _, _ -> }, exec = exec, mdns = mdns, apiPort = 0,
            apiBindAddress = InetAddress.getLoopbackAddress()).also { stops += it::stop }
        device.start()
        exec.runDue()

        assertEquals("S1 Kiosk 9.8.7", mdns.identities.single().compilationTime)
        assertEquals("9.8.7", text(device.entityList().single { it.objectId == "app_version" } as TextSensorEntity))
        val sendspin = device.paneList().filterIsInstance<SendspinPane>().single()
        assertEquals("S1 Kiosk 9.8.7", sendspin.player.hello().softwareVersion)
    }

    @Test fun theManagePageShowsTheGivenBuild() {
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        val manage = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, KioskPrefs(app))
        assertEquals("S1 Kiosk 9.8.7 · test build f00ba47", manage.status().about.toMap()["Glassworks Panel"])
        val host = S1Manage::class.java.getDeclaredField("host").apply { isAccessible = true }.get(manage) as ManageHost
        assertEquals("Version: S1 Kiosk 9.8.7 · test build f00ba47 (90807)", host.diagnostics().lineSequence().first())
    }

    /** What the runtime's engine starts Gecko with. */
    @Suppress("UNCHECKED_CAST")
    private fun engineSettings(r: GlassworksRuntime): EngineSettings =
        (GeckoEngine::class.java.getDeclaredField("settings").apply { isAccessible = true }.get(r.engine) as (Context) -> EngineSettings)(app)

    /** The state a text sensor sends Home Assistant. */
    @Suppress("UNCHECKED_CAST")
    private fun text(e: TextSensorEntity): String? =
        (TextSensorEntity::class.java.getDeclaredField("get").apply { isAccessible = true }.get(e) as () -> String?)()
}
