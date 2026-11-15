package sh.edm.s1kiosk.esphome

import android.app.Application
import dev.glassworks.pane.Pane
import dev.glassworks.platform.AppBuild
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.frame.AqaraS1Plus
import java.net.InetAddress

/** The device runs the panes the app builds from its [DeviceHost], and hands back what they left with it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceHostTest {
    private val app = RuntimeEnvironment.getApplication()
    private val runtime = GlassworksRuntime(AqaraS1Plus(), build = AppBuild("1.0.0", 10000, debug = false))
    private val exec = ManualExecutor()
    private val heard = ArrayList<String>()
    private var host: DeviceHost? = null

    /** A pane with only an id. Anonymous, so PaneCatalogTest's scan of the app's panes skips it. */
    private fun named(id: String): Pane = object : Pane { override val id = id }

    private val device = PanelDevice(
        app, runtime,
        buildPanes = { h ->
            host = h
            h.cancelVoice = { heard += "cancel" }
            h.onStop { heard += "stopped" }
            listOf(named("one"), named("camera"), named("two"))
        },
        onApiHello = { _, _ -> }, exec = exec, mdns = RecordingMdns(), apiPort = 0, apiBindAddress = InetAddress.getLoopbackAddress(),
    )

    @After fun tearDown() { runCatching { device.stop() } }

    @Test fun theDeviceRunsTheAppsPanesInOrderWithoutTheOnesTheFrameCantRun() {
        device.start()
        exec.runDue()
        assertTrue("the S1 has no camera", "camera" in host!!.cantRun)
        assertEquals(listOf("one", "two"), device.paneList().map { it.id })
        assertEquals(listOf("one", "two"), runtime.panes.map { it.id })
    }

    @Test fun cancellingVoiceRunsWhatThePanesLeftOnTheDevicesThread() {
        device.start()
        exec.runDue()
        device.cancelVoice()
        assertEquals("not before the device's thread runs it", emptyList<String>(), heard)
        exec.runDue()
        assertEquals(listOf("cancel"), heard)
    }

    @Test fun stoppingTheDeviceRunsWhatThePanesLeftForIt() {
        device.start()
        exec.runDue()
        device.stop()
        assertEquals(listOf("stopped"), heard)
        assertEquals(emptyList<Pane>(), runtime.panes)
    }
}
