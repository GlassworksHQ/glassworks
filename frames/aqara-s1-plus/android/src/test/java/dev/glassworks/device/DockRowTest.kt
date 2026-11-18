package dev.glassworks.device

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.HttpPoster
import dev.glassworks.auth.SignInServer
import dev.glassworks.dock.DockRow
import dev.glassworks.frame.Frame
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.text
import dev.glassworks.pane.SettingsItem
import dev.glassworks.platform.AppBuild
import dev.glassworks.settings.S1Settings
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
import sh.edm.s1kiosk.KioskApp
import sh.edm.s1kiosk.PanePrefs
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.manage.S1Manage

/**
 * The Device page's dock row (maintainer, 2026-11-18): *Docked · On its dock* or *Not docked*, live, on the panel's
 * settings and on the Manage page alike, and only where Home Assistant has the Docked sensor. Built through the real
 * settings model and Manage page, with a running device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DockRowTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = KioskPrefs(app)
    private val devices = ArrayList<PanelDevice>()
    private lateinit var exec: ManualExecutor

    @Before fun aPixelTablet() {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
        ShadowBuild.setHardware("tangorpro")
        PanePrefs(app).wallBase.backend = BaseBackend.LOCALSERVICE.pref
        prefs.deviceMac = "02:00:00:A1:B2:C3"
        battery(BatteryManager.BATTERY_PLUGGED_DOCK)
    }

    @After fun tearDown() = devices.forEach { runCatching { it.stop() } }

    @Suppress("DEPRECATION")
    private fun battery(plugged: Int) {
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, plugged))
        shadowOf(Looper.getMainLooper()).idle()
        if (::exec.isInitialized) exec.runDue()
    }

    /** The runtime with its device running, as KioskService leaves it. */
    private fun running(frame: Frame = GenericAndroid(app)): GlassworksRuntime {
        val runtime = KioskApp.runtime(frame, build = AppBuild("1.0.0", 10000, debug = false))
        exec = ManualExecutor()
        val device = PanelDevice(app, runtime, runtime::devicePanes, onApiHello = { _, _ -> }, exec = exec, mdns = RecordingMdns(), apiPort = 0,
            apiBindAddress = InetAddress.getLoopbackAddress())
        devices += device
        device.start()
        exec.runDue()
        shadowOf(Looper.getMainLooper()).idle()
        exec.runDue()
        runtime.device = device
        return runtime
    }

    private fun devicePage(runtime: GlassworksRuntime) =
        S1Settings.model(runtime, prefs, runtime.paneDefinitions(app), deviceSettings = false, fullScreen = {}, context = app)
            .sections.single { it.id == GenericAndroid.DEVICE }

    private fun dockRow(runtime: GlassworksRuntime): SettingsItem.Info? =
        devicePage(runtime).items.filterIsInstance<SettingsItem.Info>().singleOrNull { DockRow.shownIn(listOf(it)) }

    private fun manage(runtime: GlassworksRuntime): ManageFixture.Browser {
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        val m = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs)
        return ManageFixture().apply { statusFn = { m.status() } }.Browser().also { it.signInWithHa() }
    }

    @Test fun thePanelsDevicePageShowsTheDockAndFollowsIt() {
        val runtime = running()
        val page = devicePage(runtime)
        val row = page.items.last() as SettingsItem.Info
        assertTrue("the last row, after the hardware", DockRow.shownIn(listOf(row)))
        assertTrue(page.items.any { it is SettingsItem.Info && it.title() == GenericAndroid.MODEL_TITLE })
        assertEquals(DockRow.DOCKED to DockRow.ON_ITS_DOCK, row.title() to row.detail())
        battery(0)
        assertEquals("the same row, read again while the page is open", DockRow.NOT_DOCKED to "", row.title() to row.detail())
        battery(BatteryManager.BATTERY_PLUGGED_DOCK)
        assertEquals(DockRow.DOCKED, row.title())
    }

    @Test fun theManagePageShowsTheSameAndReloadsItself() {
        val runtime = running()
        val browser = manage(runtime)
        val docked = browser.get("/device").text()
        assertTrue(docked, docked.contains("<b>Docked</b>") && docked.contains("On its dock"))
        assertTrue("it has no scripts, so it reloads itself", docked.contains("""<meta http-equiv="refresh" content="3;url=/device">"""))
        battery(0)
        val lifted = browser.get("/device").text()
        assertTrue(lifted, lifted.contains("<b>Not docked</b>") && !lifted.contains("On its dock"))
        assertFalse("no other page reloads", browser.get("/screen").text().contains("http-equiv=\"refresh\""))
    }

    /** WCAG 2.2.1: the person can stop the reload (a reload restarts a screen reader), and start it again. */
    @Test fun theReloadCanBePausedAndResumed() {
        val browser = manage(running())
        val live = browser.get("/device").text()
        assertTrue(live, live.contains("""<a href="/device?live=off">Pause</a>"""))
        val paused = browser.get("/device?live=off").text()
        assertFalse("paused: no reload", paused.contains("http-equiv=\"refresh\""))
        assertTrue("the row is still there", paused.contains("<b>Docked</b>"))
        assertTrue(paused, paused.contains("""<a href="/device">Resume</a>"""))
        assertFalse("no pause line where nothing reloads", browser.get("/screen").text().contains("?live=off"))
    }

    @Test fun anOrdinaryTabletAndTheS1HaveNoRow() {
        ShadowBuild.setModel("Lenovo TB-X606F")
        battery(0)
        val tablet = running()
        assertNull(dockRow(tablet))
        assertFalse(manage(tablet).get("/device").text().contains("http-equiv=\"refresh\""))
        devices.forEach { it.stop() }

        ShadowBuild.setHardware(AqaraS1Plus.HARDWARE)
        battery(BatteryManager.BATTERY_PLUGGED_DOCK)
        val s1 = running(AqaraS1Plus())
        assertNull(s1.docked())
        assertTrue(S1Settings.model(s1, prefs, s1.paneDefinitions(app), deviceSettings = false, fullScreen = {}, context = app)
            .sections.none { s -> DockRow.shownIn(s.items) })
    }

    @Test fun noRowWhileTheDeviceIsntRunning() {
        val runtime = KioskApp.runtime(GenericAndroid(app), build = AppBuild("1.0.0", 10000, debug = false))
        assertNull(runtime.docked())
        assertNull(dockRow(runtime))
    }
}
