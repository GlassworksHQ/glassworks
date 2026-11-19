package dev.glassworks.manage

import android.app.Application
import dev.glassworks.platform.AppBuild
import dev.glassworks.web.HttpRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.glassworks.s1.KioskApp
import dev.glassworks.KioskPrefs
import dev.glassworks.s1.PanePrefs
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.HttpPoster
import dev.glassworks.auth.SignInServer
import dev.glassworks.s1.base.BaseBackend
import dev.glassworks.device.ManualExecutor
import dev.glassworks.device.PanelDevice
import dev.glassworks.device.RecordingMdns
import dev.glassworks.s1.frame.AqaraS1Plus
import dev.glassworks.speaker.SendspinPrefs
import dev.glassworks.assist.VoicePrefs
import java.net.InetAddress
import dev.glassworks.s1.manage.S1Manage

/** The Manage page answers to the panel's ESPHome name as `.local` (#260), and not to another panel's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ManageHostsTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = KioskPrefs(app)
    private val panePrefs = PanePrefs(app)
    private val tokens = HaTokenClient(object : HttpPoster {
        override fun postForm(url: String, body: String) = 0 to ""
        override fun get(url: String, maxBytes: Int) = 0 to ""
    })
    private val runtime = KioskApp.runtime(AqaraS1Plus(), build = AppBuild("1.0.0", 10000, debug = false))
    private val manage = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs)

    private fun status(host: String) = manage.server.handle(HttpRequest("GET", "/manage/code", emptyMap(), mapOf("host" to "$host:8099"))).status

    /** The device KioskService runs, started, with its engines off. */
    private fun startDevice(): PanelDevice {
        panePrefs.paneFile("assist").edit().putBoolean(VoicePrefs.KEY_ENABLED, false).commit()
        panePrefs.paneFile("sendspin").edit().putBoolean(SendspinPrefs.KEY_ENABLED, false).commit()
        panePrefs.wallBase.backend = BaseBackend.LOCALSERVICE.pref
        val exec = ManualExecutor()
        return PanelDevice(app, runtime, runtime::devicePanes, onApiHello = { _, _ -> }, exec = exec, mdns = RecordingMdns(), apiPort = 0,
            apiBindAddress = InetAddress.getLoopbackAddress()).also { it.start(); exec.runDue(); runtime.device = it }
    }

    @After fun tearDown() { runtime.device?.stop(); runtime.device = null }

    @Test fun itAnswersToTheNameItsDeviceAnnounced() {
        prefs.deviceMac = "02:00:00:A1:B2:C3"
        assertEquals("no device running: no name yet", 400, status("aqara-s1-plus-a1b2c3.local"))
        startDevice()
        assertNotEquals(400, status("aqara-s1-plus-a1b2c3.local"))
        assertEquals("another panel's name", 400, status("aqara-s1-plus-d4e5f6.local"))
        assertEquals("the old shared name", 400, status("s1-panel.local"))
    }
}
