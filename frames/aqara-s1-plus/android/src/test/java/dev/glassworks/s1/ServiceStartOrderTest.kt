package dev.glassworks.s1

import android.os.Looper
import dev.glassworks.KioskService
import dev.glassworks.assist.VoicePrefs
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.glassworks
import dev.glassworks.glassworksRuntime
import dev.glassworks.s1.base.BaseBackend
import dev.glassworks.s1.frame.AqaraS1Plus
import dev.glassworks.speaker.SendspinPrefs
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.time.Duration

/**
 * KioskService's start-up work that the dashboard's first frame doesn't need (the ESPHome device with its panes, Home
 * Assistant's discovery) starts after that frame ([dev.glassworks.AfterFirstFrame]), or after the timeout without one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [29],
    application = KioskApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class ServiceStartOrderTest {
    private val app = RuntimeEnvironment.getApplication()
    private var service: ServiceController<KioskService>? = null

    @Before fun setUp() {
        ShadowBuild.setHardware(AqaraS1Plus.HARDWARE)
        // The panes that would load models or open ports here: off, as in the device tests.
        val panes = PanePrefs(app)
        panes.paneFile("assist").edit().putBoolean(VoicePrefs.KEY_ENABLED, false).commit()
        panes.paneFile("sendspin").edit().putBoolean(SendspinPrefs.KEY_ENABLED, false).commit()
        panes.wallBase.backend = BaseBackend.LOCALSERVICE.pref
    }

    @After fun tearDown() { service?.destroy() }

    /** The device's panes are built on esphome-main once it starts ([dev.glassworks.device.PanelDevice.initEntities]). */
    private fun deviceStarted() = app.glassworks.panes.isNotEmpty()

    private fun awaitDevice() {
        val until = System.currentTimeMillis() + 10_000
        while (!deviceStarted() && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    private fun idleMain(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun theDeviceStartsOnlyAfterTheFirstDashboardFrame() {
        service = Robolectric.buildService(KioskService::class.java).create()
        assertNotNull("the device is there at once (the Manage page, the settings)", app.glassworksRuntime.device)
        idleMain(5_000)
        Thread.sleep(300) // esphome-main would have built the panes by now, had it started
        assertFalse("not started before the frame", deviceStarted())

        app.glassworks.afterFirstFrame.reached("test: first paint")
        idleMain(100)
        Thread.sleep(300)
        assertFalse("not during the settle", deviceStarted())
        idleMain(200)
        awaitDevice()
        assertTrue("started after it", deviceStarted())
    }

    @Test fun withoutAFrameTheDeviceStartsAfterTheTimeout() {
        service = Robolectric.buildService(KioskService::class.java).create()
        idleMain(dev.glassworks.AfterFirstFrame.TIMEOUT_MS + dev.glassworks.AfterFirstFrame.SETTLE_MS)
        awaitDevice()
        assertTrue(deviceStarted())
    }

    @Test fun aServiceStoppedBeforeTheFrameStartsNothingAfterIt() {
        val s = Robolectric.buildService(KioskService::class.java).create()
        s.destroy()
        app.glassworks.afterFirstFrame.reached("test: first paint")
        idleMain(1_000)
        Thread.sleep(300)
        assertFalse(deviceStarted())
    }
}
