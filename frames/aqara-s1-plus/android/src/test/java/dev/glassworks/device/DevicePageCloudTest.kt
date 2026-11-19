package dev.glassworks.device

import android.app.Application
import dev.glassworks.pane.SettingsItem
import dev.glassworks.platform.AppBuild
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import dev.glassworks.s1.KioskApp
import dev.glassworks.s1.PanePrefs
import dev.glassworks.s1.base.BaseBackend
import dev.glassworks.s1.frame.AqaraCloud
import dev.glassworks.s1.frame.AqaraS1Plus
import java.net.InetAddress

/**
 * #303 verifier: Block Aqara cloud saved from the S1's Device page reaches Aqara's daemon, through the
 * device's pref listener and the wall base pane, as it did from the pane's Home Assistant switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DevicePageCloudTest {
    private val app = RuntimeEnvironment.getApplication()
    private val exec = ManualExecutor()

    /** init as the daemon's controls see it: getprop answers the state, setprop ctl.start/ctl.stop change it. */
    private var daemon = "running"
    private val controls = ArrayList<String>()
    private val cloud = AqaraCloud(run = { cmd ->
        when {
            cmd.first() == "getprop" -> daemon
            cmd == listOf("setprop", "ctl.start", "ha_master") -> { controls += "start"; daemon = "running"; "" }
            cmd == listOf("setprop", "ctl.stop", "ha_master") -> { controls += "stop"; daemon = "stopped"; "" }
            else -> ""
        }
    })
    private val runtime = KioskApp.runtime(AqaraS1Plus(cloud), build = AppBuild("1.0.0", 10000, debug = false))
    private lateinit var device: PanelDevice

    @Before fun setUp() {
        val prefs = PanePrefs(app)
        for (id in listOf("assist", "sendspin")) runtime.paneDefinitions(app).single { it.id == id }.switch!!.on = false
        prefs.wallBase.backend = BaseBackend.LOCALSERVICE.pref // no base port to open; no base attached
        prefs.wallBase.layout = ""
        prefs.wallBase.blockAqaraCloud = true
        device = PanelDevice(app, runtime, runtime::devicePanes, onApiHello = { _, _ -> }, exec = exec, mdns = RecordingMdns(),
            apiPort = 0, apiBindAddress = InetAddress.getLoopbackAddress())
        device.start()
        exec.runDue()
        daemon = "stopped"
        controls.clear()
    }

    @After fun tearDown() = device.stop()

    private fun saveFromTheDevicePage(on: Boolean) {
        val page = runtime.frame.deviceSection(app, runtime::runningPane)!!
        val sw = page.items.filterIsInstance<SettingsItem.Switch>().single { it.title == "Block Aqara cloud" }
        var answer: String? = "none yet"
        sw.save(on) { answer = it }
        assertEquals(null, answer)
        ShadowLooper.idleMainLooper()
        exec.runDue()
    }

    @Test fun blockingOffStartsTheDaemonAndOnStopsIt() {
        saveFromTheDevicePage(false)
        assertEquals("unblocked: ha_master may run (LocalService backend)", listOf("start"), controls)
        assertEquals("running", daemon)
        saveFromTheDevicePage(true)
        assertEquals("blocked with no base: ha_master stops", listOf("start", "stop"), controls)
        assertEquals("stopped", daemon)
    }
}
