package dev.glassworks.device

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.BatteryManager
import android.os.Looper
import dev.glassworks.esphome.ApiEntity
import dev.glassworks.esphome.BinarySensorEntity
import dev.glassworks.frame.Dock
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.platform.AppBuild
import java.net.InetAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import sh.edm.s1kiosk.KioskApp
import dev.glassworks.KioskPrefs
import sh.edm.s1kiosk.PanePrefs
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.frame.AqaraS1Plus

/**
 * The *Docked* sensor is only where it means something (docs/esphome.md#docked): a frame that docks or a device with
 * Google's dock manager from the start, any other generic device once it has docked (kept across restarts), the S1
 * never. On it, it follows the device on and off its dock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DockedEntityTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = KioskPrefs(app)
    private val devices = ArrayList<PanelDevice>()
    private lateinit var exec: ManualExecutor

    @Before fun setUp() {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
        ShadowBuild.setHardware("tangorpro")
        PanePrefs(app).wallBase.backend = BaseBackend.LOCALSERVICE.pref
        prefs.deviceMac = "02:00:00:A1:B2:C3"
        battery(0)
    }

    @After fun tearDown() = devices.forEach { runCatching { it.stop() } }

    @Suppress("DEPRECATION")
    private fun send(intent: Intent) {
        app.sendStickyBroadcast(intent)
        idle()
    }

    private fun battery(plugged: Int) = send(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, plugged))

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        if (::exec.isInitialized) exec.runDue()
    }

    private fun runtime(frame: dev.glassworks.frame.Frame = GenericAndroid(app)) =
        KioskApp.runtime(frame, build = AppBuild("1.0.0", 10000, debug = false))

    /** Starts the device the way KioskService does, with its dock watcher. */
    private fun run(runtime: GlassworksRuntime = runtime()): PanelDevice {
        exec = ManualExecutor()
        val device = PanelDevice(app, runtime, runtime::devicePanes, onApiHello = { _, _ -> }, exec = exec, mdns = RecordingMdns(), apiPort = 0,
            apiBindAddress = InetAddress.getLoopbackAddress())
        devices += device
        device.start()
        exec.runDue()
        idle()
        return device
    }

    private fun PanelDevice.dockedEntity(): ApiEntity? = entityList().firstOrNull { it.objectId == "docked" }

    private fun PanelDevice.dockedState(): Boolean {
        val e = dockedEntity() as BinarySensorEntity
        val get = BinarySensorEntity::class.java.getDeclaredField("get").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (get.get(e) as () -> Boolean?)() == true
    }

    @Test fun aPixelTabletHasItFromTheStartAndItFollowsTheDock() {
        assertEquals(Dock.YES, GenericAndroid(app).dock)
        val device = run()
        val e = device.dockedEntity() as BinarySensorEntity
        assertEquals("Docked", e.name)
        assertEquals("on by default: people automate on it", false, e.disabledByDefault)
        assertEquals(dev.glassworks.esphome.EntityCategory.NONE, e.category)
        assertFalse(device.dockedState())
        battery(BatteryManager.BATTERY_PLUGGED_DOCK)
        assertTrue("on its dock", device.dockedState())
        battery(0)
        assertFalse("lifted off", device.dockedState())
        battery(BatteryManager.BATTERY_PLUGGED_DOCK)
        assertTrue("back on", device.dockedState())
    }

    @Test fun anOrdinaryTabletHasNone() {
        ShadowBuild.setModel("Lenovo TB-X606F")
        assertEquals(Dock.IF_SEEN, GenericAndroid(app).dock)
        assertNull(run().dockedEntity())
    }

    @Test fun aTabletWithGooglesDockManagerHasIt() {
        ShadowBuild.setModel("Pixel Tablet 2")
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = GenericAndroid.DOCK_MANAGER })
        assertEquals(Dock.YES, GenericAndroid(app).dock)
        assertTrue(run().dockedEntity() != null)
    }

    @Test fun anyOtherTabletGetsItOnceItDocksAndKeepsIt() {
        ShadowBuild.setModel("Lenovo TB-X606F")
        val device = run()
        assertNull(device.dockedEntity())
        send(Intent(Intent.ACTION_DOCK_EVENT).putExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_DESK))
        assertTrue("the first dock adds it", device.dockedEntity() != null)
        assertTrue(device.dockedState())
        assertTrue("kept", prefs.dockSeen)
        device.stop()

        send(Intent(Intent.ACTION_DOCK_EVENT).putExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_UNDOCKED))
        val again = run()
        assertTrue("after a restart, off its dock, it's still there", again.dockedEntity() != null)
        assertFalse(again.dockedState())
    }

    @Test fun theS1NeverHasItAndListensForNoDock() {
        ShadowBuild.setHardware(AqaraS1Plus.HARDWARE)
        battery(BatteryManager.BATTERY_PLUGGED_DOCK)
        val device = run(runtime(AqaraS1Plus()))
        assertNull(device.dockedEntity())
        assertFalse("nothing learnt", prefs.dockSeen)
        prefs.dockSeen = true
        assertNull("not even after a dock was seen", run(runtime(AqaraS1Plus())).dockedEntity())
    }
}
