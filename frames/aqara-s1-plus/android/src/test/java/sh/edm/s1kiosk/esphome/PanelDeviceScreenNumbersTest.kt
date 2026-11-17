package sh.edm.s1kiosk.esphome

import android.app.Application
import android.os.Looper
import dev.glassworks.esphome.ApiEntity
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.esphome.ProtoMessage
import dev.glassworks.esphome.ProtoWriter
import dev.glassworks.platform.AppBuild
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskApp
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.frame.AqaraS1Plus

/**
 * The two numbers Home Assistant's night mode sets (#473), driven through their entities as HA does: Idle timeout
 * and Max auto brightness.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PanelDeviceScreenNumbersTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = KioskPrefs(app)

    private fun withDevice(block: (set: (String, Float) -> Unit, state: (String) -> Float) -> Unit) {
        val runtime = KioskApp.runtime(AqaraS1Plus(), build = AppBuild("1.0.0", 10000, debug = false))
        val device = PanelDevice(app, runtime, runtime::devicePanes)
        try {
            device.initEntities(
                DeviceIdentity(
                    name = "aqara-s1-plus-000001", friendlyName = "Aqara S1 Plus", macAddress = "02:00:00:00:00:01",
                    esphomeVersion = PanelDevice.ESPHOME_VERSION, compilationTime = "test", manufacturer = "Aqara",
                    model = "test", voiceFeatures = PanelDevice.VOICE_FEATURES,
                ),
            )
            fun entity(id: String): ApiEntity = device.entityList().single { it.objectId == id }
            block(
                { id, v -> entity(id).command(ProtoMessage(ProtoWriter().fixed32(1, entity(id).key, true).float(2, v).toByteArray())); shadowOf(Looper.getMainLooper()).idle() },
                { id -> ProtoMessage(entity(id).state()!!.toByteArray()).float(2) },
            )
        } finally {
            device.stop()
        }
    }

    /** A panel set to stay on ("always") turns off when idle again once HA sets a timeout, as the panel's own row does. */
    @Test fun anIdleTimeoutFromHaPutsAnAlwaysOnScreenBackToPresence() = withDevice { set, state ->
        prefs.screenMode = "always"
        set("idle_timeout", 20f)
        assertEquals(20, prefs.idleOffSec)
        assertEquals("presence", prefs.screenMode)
        assertEquals(20f, state("idle_timeout"), 0f)
    }

    /** 0 (never) changes the timeout only: an "always" screen stays on. */
    @Test fun zeroLeavesTheScreenModeAlone() = withDevice { set, _ ->
        prefs.screenMode = "always"
        set("idle_timeout", 0f)
        assertEquals(0, prefs.idleOffSec)
        assertEquals("always", prefs.screenMode)
    }

    @Test fun maxAutoBrightnessIsAPercentOfTheCurvesCeiling() = withDevice { set, state ->
        assertEquals("the panel's default", 100f, state("max_brightness"), 0f)
        set("max_brightness", 20f)
        assertEquals(0.2f, prefs.maxBrightness, 1e-6f)
        assertEquals(20f, state("max_brightness"), 0f)
        set("max_brightness", 1f) // below the entity's 5 %: HA can't send it, and the panel clamps it anyway
        assertEquals(0.05f, prefs.maxBrightness, 1e-6f)
    }
}
