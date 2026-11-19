package dev.glassworks.settings

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Looper
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.HttpPoster
import dev.glassworks.auth.SignInServer
import dev.glassworks.device.ManualExecutor
import dev.glassworks.device.PanelDevice
import dev.glassworks.device.RecordingMdns
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.manage.ManageFixture
import dev.glassworks.platform.AppBuild
import dev.glassworks.ui.Mockup
import java.io.File
import java.net.InetAddress
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import dev.glassworks.s1.KioskApp
import dev.glassworks.s1.manage.S1Manage

/**
 * The Pixel Tablet's Device page with its dock row (maintainer, 2026-11-18), on the panel at the tablet's 1280x800
 * (mdpi, so the shot is 1:1) and as the Manage page's HTML: before (no row: the device isn't running, so the page is
 * main's), docked, and lifted. The real settings model and Manage page over a running device. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*DockRowMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DockRowMockups {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = KioskPrefs(app)
    private val devices = ArrayList<PanelDevice>()
    private lateinit var exec: ManualExecutor

    @After fun tearDown() = devices.forEach { runCatching { it.stop() } }

    @Suppress("DEPRECATION")
    private fun battery(plugged: Int) {
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, plugged))
        shadowOf(Looper.getMainLooper()).idle()
        if (::exec.isInitialized) exec.runDue()
    }

    private fun runtime(running: Boolean): GlassworksRuntime {
        val runtime = KioskApp.runtime(GenericAndroid(app), build = AppBuild("1.0.0", 10000, debug = false))
        if (!running) return runtime
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

    private fun shoot(name: String, runtime: GlassworksRuntime) {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        fun model() = S1Settings.model(runtime, prefs, runtime.paneDefinitions(app), deviceSettings = true, fullScreen = {}, version = "0.4.3", context = app)
        o.reload = ::model
        o.show(model())
        o.previewSection(GenericAndroid.DEVICE)
        Mockup.idle()
        Mockup.save(name, o, 1280, 800)
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        val m = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs, version = "0.4.3")
        val browser = ManageFixture().apply { statusFn = { m.status() } }.Browser().also { it.signInWithHa() }
        File(Mockup.dir!!, "manage").mkdirs()
        File(Mockup.dir!!, "manage/$name.html").writeBytes(browser.get("/device").body)
    }

    @Test fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        Mockup.interFromSources()
        app.applicationInfo.targetSdkVersion = 35
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
        ShadowBuild.setHardware("tangorpro")
        for (f in listOf(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_CAMERA_ANY)) {
            shadowOf(app.packageManager).setSystemFeature(f, true)
        }
        prefs.deviceMac = "02:00:00:A1:B2:C3"
        battery(BatteryManager.BATTERY_PLUGGED_DOCK)
        shoot("dock-01-before", runtime(running = false))
        val runtime = runtime(running = true)
        shoot("dock-02-docked", runtime)
        battery(0)
        shoot("dock-03-lifted", runtime)
    }
}
