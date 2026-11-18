package dev.glassworks.device

import android.app.Application
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.input.InputManager
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.frame.Frame
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.frame.Input
import dev.glassworks.platform.AppBuild
import dev.glassworks.screen.AmbientLight
import dev.glassworks.screen.ProximityPresence
import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.shadows.ShadowSensor
import dev.glassworks.s1.KioskApp
import dev.glassworks.s1.PanePrefs
import dev.glassworks.s1.base.BaseBackend
import dev.glassworks.s1.frame.AqaraS1Plus

/**
 * #476: Home Assistant gets the screen entities a device can back, from the same facts as its Screen settings
 * and the Manage page ([dev.glassworks.screen.ScreenHardware.of]). Presence range needs a sensor with a range
 * (the S1's radar); Auto brightness, Illuminance and Max auto brightness need a light sensor; Presence needs the
 * frame's own sensor. With only a camera, presence is the camera pane's `camera_presence`, listed on or off.
 *
 * Each case builds the real list through [PanelDevice.initEntities], as [PanelEntitiesPinTest] does. The
 * whole list of each frame is pinned in `frame-entities.golden.txt` (object ids, in the order Home Assistant gets
 * them); regenerate it with
 * `./gradlew :kiosk:testDebugUnitTest --tests '*FrameEntitiesTest*' -Ps1RegenGolden=true`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FrameEntitiesTest {
    private val app = RuntimeEnvironment.getApplication()

    /** The core's screen entities and the camera's presence, in the order the S1 lists them. */
    private val screenIds = listOf(
        "screen", "auto_brightness", "presence", "illuminance", "screen_mode", "idle_timeout", "max_brightness",
        "presence_range", "camera_presence",
    )

    private class Case(val name: String, val frame: () -> Frame, val screen: List<String>, val setup: () -> Unit = {})

    private val cases = listOf(
        Case("aqara-s1-plus", ::AqaraS1Plus, screenIds - "camera_presence"),
        Case("generic, no sensors, no camera", { generic() }, listOf("screen", "screen_mode", "idle_timeout")),
        Case(
            "generic, light sensor", { generic() },
            listOf("screen", "auto_brightness", "illuminance", "screen_mode", "idle_timeout", "max_brightness"),
        ) { sensor(Sensor.TYPE_LIGHT) },
        Case(
            "generic, proximity and light sensors", { generic() },
            listOf("screen", "auto_brightness", "presence", "illuminance", "screen_mode", "idle_timeout", "max_brightness"),
        ) { sensor(Sensor.TYPE_PROXIMITY); sensor(Sensor.TYPE_LIGHT) },
        Case(
            "generic, a stub proximity sensor (range 0)", { generic() },
            listOf("screen", "screen_mode", "idle_timeout"),
        ) { sensor(Sensor.TYPE_PROXIMITY, maxRange = 0f) },
        Case(
            "google-pixel-tablet: a light sensor and a camera", { generic() },
            listOf("screen", "auto_brightness", "illuminance", "screen_mode", "idle_timeout", "max_brightness", "camera_presence"),
        ) { sensor(Sensor.TYPE_LIGHT); camera() },
        Case(
            "lululemon-mirror: a camera, no sensors, no touch", { generic(dev.glassworks.s1.frame.TestFrames.MIRROR_PROPS) },
            listOf("screen", "screen_mode", "idle_timeout", "camera_presence"),
        ) { camera(); keysOnly() },
    )

    @Test fun eachFrameListsTheScreenEntitiesItsHardwareBacks() {
        for (c in cases) assertEquals(c.name, c.screen, entityIds(c).filter { it in screenIds })
    }

    /** #139: the Frame update entity only on a frame with releases of its own. */
    @Test fun onlyTheS1AndTheMirrorHaveAFrameUpdate() {
        for (c in cases) assertEquals(c.name, c.name.startsWith("aqara-s1-plus") || c.name.startsWith("lululemon-mirror"), "frame_update" in entityIds(c))
    }

    @Test fun theCameraPaneOffKeepsCameraPresence() {
        val tablet = cases.single { it.name.startsWith("google-pixel-tablet") }
        val on = entityIds(tablet)
        PanePrefs(app).paneFile("camera").edit().putBoolean("camera_enabled", false).commit()
        assertEquals(on, entityIds(tablet))
    }

    /** Every frame's whole list, in order, so a review sees each entity a frame gains, loses or moves. */
    @Test fun everyFrameMatchesGolden() {
        val actual = cases.joinToString("") { c -> "## ${c.name}\n" + entityIds(c).joinToString("") { "$it\n" } + "\n" }
        if (System.getProperty("s1.regenGolden") == "true") {
            RepoFiles.inModule("src/test/resources/$GOLDEN").writeText(actual)
            return
        }
        val golden = javaClass.getResourceAsStream("/$GOLDEN")?.bufferedReader()?.readText()
        assertNotNull("$GOLDEN missing; regenerate it (see the class KDoc)", golden)
        assertEquals(golden, actual)
    }

    /** A fresh device: Robolectric resets the shadows only between tests. */
    private fun reset() {
        shadowOf(app.getSystemService(SensorManager::class.java)).apply {
            getSensorList(Sensor.TYPE_ALL).forEach { removeSensor(it) }
        }
        val input = app.getSystemService(InputManager::class.java)
        input.inputDeviceIds.forEach { shadowOf(input).removeInputDevice(it) }
        for (f in listOf(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE)) {
            shadowOf(app.packageManager).setSystemFeature(f, true)
        }
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, false)
        PanePrefs(app).wallBase.backend = BaseBackend.LOCALSERVICE.pref
    }

    private fun entityIds(c: Case): List<String> {
        reset()
        c.setup()
        val frame = c.frame()
        if (c.name.startsWith("lululemon-mirror")) assertEquals(Input.NONE, frame.input)
        val runtime = KioskApp.runtime(frame, build = AppBuild("1.0.0", 10000, debug = false))
        val device = PanelDevice(app, runtime, runtime::devicePanes)
        try {
            device.initEntities(
                DeviceIdentity(
                    name = "test-000001", friendlyName = "Test", macAddress = "02:00:00:00:00:01",
                    esphomeVersion = PanelDevice.ESPHOME_VERSION, compilationTime = "test", manufacturer = "Test",
                    model = "Test", voiceFeatures = PanelDevice.VOICE_FEATURES,
                ),
            )
            return device.entityList().map { it.objectId }
        } finally {
            device.stop()
        }
    }

    /** The generic app's frame: Android's standard proximity and light sensors, when the device lists them. */
    private fun generic(props: Map<String, String> = emptyMap()) =
        GenericAndroid(app, presence = ProximityPresence("test"), light = AmbientLight("test"), prop = { props[it].orEmpty() })

    private fun sensor(type: Int, maxRange: Float = 5f) {
        val s = ShadowSensor.newInstance(type)
        shadowOf(s).setMaximumRange(maxRange)
        shadowOf(app.getSystemService(SensorManager::class.java)).addSensor(s)
    }

    private fun camera() = shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)

    /** The Mirror's buttons: input devices, none a touchscreen. */
    private fun keysOnly() =
        shadowOf(app.getSystemService(InputManager::class.java)).addInputDevice(ShadowInputDevice.makeInputDeviceNamed("gpio-keys"))

    private companion object {
        const val GOLDEN = "frame-entities.golden.txt"
    }
}
