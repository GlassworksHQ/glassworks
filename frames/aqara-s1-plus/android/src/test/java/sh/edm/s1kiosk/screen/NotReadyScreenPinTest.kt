package sh.edm.s1kiosk.screen

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowSensor
import sh.edm.s1kiosk.KioskApp
import sh.edm.s1kiosk.glassworksRuntime
import sh.edm.s1kiosk.gecko.GeckoPinHarness
import sh.edm.s1kiosk.gecko.GeckoShadows
import sh.edm.s1kiosk.setup.SetupOverlay

/**
 * #449 keeps the setup screen on only where no presence sensor can wake it. The S1 has its VI5300: its setup screen
 * still turns off after a 20 s idle timeout (what HA's night mode sets), as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = KioskApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class NotReadyScreenPinTest {
    @After fun tearDown() = ShadowBuild.reset()

    /** The S1's frame, with its sensor. */
    private fun s1() {
        ShadowBuild.setHardware("sun50iw10p1")
        val sensor = ShadowSensor.newInstance(Sensor.TYPE_PROXIMITY)
        Sensor::class.java.getDeclaredField("mName").apply { isAccessible = true }.set(sensor, "vi5300 Proximity Sensor")
        val sm = RuntimeEnvironment.getApplication().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shadowOf(sm).addSensor(sensor)
    }

    @Test fun theS1sSetupScreenSleepsAsBefore() {
        s1()
        GeckoPinHarness(prefs = { it.idleOffSec = 20 }).use { h ->
            assertEquals("the S1's frame", "aqara-s1-plus", h.app.glassworksRuntime.frame.board)
            h.advance(5_000)
            assertTrue("a fresh panel shows setup", h.rootChildren().filterIsInstance<SetupOverlay>().single().isShowing)
            h.advance(30_000)
            assertEquals(listOf("sleep (idle 20s) admin=false"), h.logs("S1Kiosk.Screen").filter { it.startsWith("sleep (idle") })
            assertTrue(h.logs("S1Kiosk.Screen").none { it.startsWith("stay on") })
        }
    }
}
