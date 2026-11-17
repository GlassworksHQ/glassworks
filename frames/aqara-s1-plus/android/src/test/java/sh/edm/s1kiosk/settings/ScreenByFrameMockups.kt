package sh.edm.s1kiosk.settings

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import dev.glassworks.frame.Frame
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.frame.Input
import dev.glassworks.frame.Orientation
import dev.glassworks.manage.ManageFixture
import dev.glassworks.platform.AppBuild
import dev.glassworks.screen.AmbientLight
import dev.glassworks.screen.ProximityPresence
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowSensor
import sh.edm.s1kiosk.KioskApp
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.HttpPoster
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.manage.S1Manage
import sh.edm.s1kiosk.ui.Mockup
import java.io.File

/**
 * The Screen page on each frame (#471), on the panel and on the Manage page, from the real settings model:
 * the S1 (its VI5300 and light sensor), a Pixel Tablet (a light sensor and a camera, no proximity sensor),
 * a bare tablet (neither) and the lululemon Mirror (portrait, no touch; a camera, sensors unknown, drawn
 * without). Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*ScreenByFrameMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenByFrameMockups {
    private val app = RuntimeEnvironment.getApplication()

    @Before fun fonts() {
        Mockup.interFromSources()
        KioskPrefs(app).urlSetting = "http://homeassistant.local:8123/lovelace/panel"
    }

    private fun sensor(type: Int, name: String) {
        val s = ShadowSensor.newInstance(type)
        shadowOf(s).setName(name)
        shadowOf(app.getSystemService(SensorManager::class.java)).addSensor(s)
    }

    /** The frame's settings app opens, as on a real device, so the Manage page shows its link. */
    private fun settingsApp(frame: Frame) {
        val info = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "com.example.settings"; name = "Settings" } }
        frame.systemSettingsIntents().forEach { shadowOf(app.packageManager).addResolveInfoForIntent(it, info) }
    }

    private fun features(vararg f: String) = f.forEach { shadowOf(app.packageManager).setSystemFeature(it, true) }

    private fun tablet(model: String) {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel(model)
    }

    private fun generic(): GenericAndroid = GenericAndroid(app, presence = ProximityPresence("test"), light = AmbientLight("test"))

    private fun shoot(name: String, frame: Frame, w: Int, h: Int) {
        settingsApp(frame)
        val runtime = KioskApp.runtime(frame, build = AppBuild("1.0.0", 10000, debug = false))
        val prefs = KioskPrefs(app)
        val model = S1Settings.model(runtime, prefs, runtime.paneDefinitions(app), deviceSettings = true, fullScreen = {}, context = app)
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        o.show(model)
        o.previewSection("screen")
        Mockup.idle()
        Mockup.save("screen-$name", o, w, h)
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        val m = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs)
        val f = ManageFixture().apply { statusFn = { m.status() } }
        val browser = f.Browser().also { it.signInWithHa() }
        File(Mockup.dir!!, "manage").mkdirs()
        File(Mockup.dir!!, "manage/screen-$name.html").writeBytes(browser.get("/screen").body)
    }

    @Test fun s1() {
        assumeTrue(Mockup.dir != null)
        sensor(Sensor.TYPE_PROXIMITY, "vi5300 Proximity Sensor")
        sensor(Sensor.TYPE_LIGHT, "mk6212 Ambient Light Sensor")
        features(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE)
        shoot("s1", AqaraS1Plus(), Mockup.W, Mockup.H)
    }

    @Test fun pixelTablet() {
        assumeTrue(Mockup.dir != null)
        tablet("Pixel Tablet")
        sensor(Sensor.TYPE_LIGHT, "Ambient Light")
        features(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_CAMERA_ANY)
        shoot("generic", generic(), Mockup.W, Mockup.H)
    }

    @Test fun bareTablet() {
        assumeTrue(Mockup.dir != null)
        tablet("Tablet")
        features(PackageManager.FEATURE_AUDIO_OUTPUT)
        shoot("generic-bare", generic(), Mockup.W, Mockup.H)
    }

    @Test @Config(qualifiers = "w480dp-h960dp-port-hdpi")
    fun mirror() {
        assumeTrue(Mockup.dir != null)
        tablet("Mirror")
        features(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_CAMERA_ANY)
        val frame = object : Frame by generic() {
            override val input = Input.NONE
            override val orientation = Orientation.PORTRAIT
        }
        shoot("mirror", frame, Mockup.H, Mockup.W)
    }
}
