package sh.edm.s1kiosk.settings

import sh.edm.s1kiosk.frame.TestFrames
import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import dev.glassworks.frame.Frame
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.manage.ManageFixture
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsItem
import dev.glassworks.platform.AppBuild
import dev.glassworks.screen.AmbientLight
import dev.glassworks.screen.ProximityPresence
import dev.glassworks.screen.ScreenHardware
import dev.glassworks.screen.ScreenHardware.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import sh.edm.s1kiosk.KioskApp
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.HttpPoster
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.manage.S1Manage

/**
 * The Screen page shows only what the frame can back (#471), on the panel's settings and on the Manage page,
 * which read one model ([S1Settings.model]) and one answer to "what does this device have" ([ScreenHardware.of]).
 * The frames: the S1, a generic tablet with and without a proximity sensor, a light sensor or a camera, and
 * the lululemon Mirror (no touch).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScreenByFrameTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = KioskPrefs(app)

    private fun sensor(type: Int, name: String, maxRange: Float = 5f) {
        val s = ShadowSensor.newInstance(type)
        shadowOf(s).setName(name)
        shadowOf(s).setMaximumRange(maxRange)
        shadowOf(app.getSystemService(SensorManager::class.java)).addSensor(s)
    }

    /** The frame's settings app opens, as on a real device, so the Manage page shows its link. */
    private fun settingsApp(frame: Frame) {
        val info = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "com.example.settings"; name = "Settings" } }
        frame.systemSettingsIntents().forEach { shadowOf(app.packageManager).addResolveInfoForIntent(it, info) }
    }

    private fun camera() = shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)

    private fun generic() = GenericAndroid(app, presence = ProximityPresence("test"), light = AmbientLight("test"))

    private fun definitions(frame: Frame): List<PaneDefinition> =
        KioskApp.runtime(frame, build = AppBuild("1.0.0", 10000, debug = false)).paneDefinitions(app)

    private fun hardware(frame: Frame) = ScreenHardware.of(frame, definitions(frame), app)

    /** The Screen page's rows on the panel, and the same page's HTML on the Manage page. */
    private fun screen(frame: Frame): Pair<List<SettingsItem>, String> {
        settingsApp(frame)
        val runtime = KioskApp.runtime(frame, build = AppBuild("1.0.0", 10000, debug = false))
        val model = S1Settings.model(runtime, prefs, runtime.paneDefinitions(app), deviceSettings = true, fullScreen = {}, context = app)
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        val m = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs)
        val browser = ManageFixture().apply { statusFn = { m.status() } }.Browser().also { it.signInWithHa() }
        return model.sections.single { it.id == "screen" }.items to String(browser.get("/screen").body)
    }

    private fun List<SettingsItem>.titles() = map(SettingsChange::titleOf)
    private fun List<SettingsItem>.wake() = filterIsInstance<SettingsItem.Switch>().single { it.title == CoreSettings.WAKE_TITLE }

    @Test fun theS1HasItsSensorsAndTouch() {
        // No VI5300 listed, as while the sensor HAL starts late after boot or a restart: the frame declares its radar.
        assertEquals(ScreenHardware(Presence.Sensor(ranged = true), light = true, touch = true), hardware(AqaraS1Plus()))
        val (rows, html) = screen(AqaraS1Plus())
        assertEquals(
            listOf(CoreSettings.WAKE_TITLE, "Presence range", "Turn off when idle", "Brightness", AqaraS1Plus.SETTINGS_TITLE),
            rows.titles(),
        )
        assertEquals("Uses the presence sensor", rows.wake().detail)
        for (s in listOf("Uses the presence sensor", "The sensor's own setting", "Follows the room's light", AqaraS1Plus.SETTINGS_TITLE)) assertTrue(s, s in html)
    }

    @Test fun aTabletWithNothingShowsNoPresenceLightOrRange() {
        val (rows, html) = screen(generic())
        assertEquals(ScreenHardware(Presence.None, light = false, touch = true), hardware(generic()))
        assertEquals(listOf("Turn off when idle", SettingsMenu.ANDROID_TITLE), rows.titles())
        for (s in listOf("someone's near", "Presence range", "presence sensor", "Brightness", "room's light")) assertFalse(s, s in html)
    }

    @Test fun aProximitySensorWakesButHasNoRange() {
        sensor(Sensor.TYPE_PROXIMITY, "Proximity")
        sensor(Sensor.TYPE_LIGHT, "Ambient Light")
        assertEquals(ScreenHardware(Presence.Sensor(ranged = false), light = true, touch = true), hardware(generic()))
        val (rows, html) = screen(generic())
        assertEquals(listOf(CoreSettings.WAKE_TITLE, "Turn off when idle", "Brightness", SettingsMenu.ANDROID_TITLE), rows.titles())
        assertEquals("Uses the proximity sensor", rows.wake().detail)
        assertTrue("Uses the proximity sensor" in html)
        assertFalse("Presence range" in html)
    }

    @Test fun aStubProximitySensorIsNoPresence() {
        // Listed with a range of 0 it can never read near: no wake row it can't back, and the camera can stand in.
        sensor(Sensor.TYPE_PROXIMITY, "Proximity", maxRange = 0f)
        assertEquals(Presence.None, hardware(generic()).presence)
        camera()
        assertEquals(Presence.Pane("camera", "Camera", on = false), hardware(generic()).presence)
    }

    @Test fun aCameraWithItsPaneOffSaysWhatTurnsPresenceOn() {
        camera()
        sensor(Sensor.TYPE_LIGHT, "Ambient Light") // the Pixel Tablet: a light sensor and a camera, no proximity sensor
        assertEquals(ScreenHardware(Presence.Pane("camera", "Camera", on = false), light = true, touch = true), hardware(generic()))
        val (rows, html) = screen(generic())
        val link = rows.first() as SettingsItem.Link
        assertEquals(CoreSettings.WAKE_TITLE, link.title)
        assertEquals("Turn on the Camera pane to use it", link.detail)
        assertEquals("opens the camera's page", "camera", link.section)
        assertTrue("on the Manage page, a link to it", """href="/camera"""" in html && "Turn on the Camera pane to use it" in html)
        assertFalse("Presence range" in html)
    }

    @Test fun aCameraWithItsPaneOnWakesTheScreen() {
        camera()
        definitions(generic()).single { it.id == "camera" }.switch!!.on = true
        assertEquals(Presence.Pane("camera", "Camera", on = true), hardware(generic()).presence)
        val (rows, html) = screen(generic())
        assertEquals("Uses the Camera pane", rows.wake().detail)
        assertTrue("Uses the Camera pane" in html)
        assertFalse(rows.any { it is SettingsItem.Slider })
    }

    @Test fun theMirrorHasNoLinkToAndroidsSettings() {
        camera()
        assertTrue("a tablet has it, on both", SettingsMenu.ANDROID_TITLE in screen(generic()).second)
        val mirror = TestFrames.mirror(app, ProximityPresence("test"), AmbientLight("test"))
        assertFalse(hardware(mirror).touch)
        val (rows, html) = screen(mirror)
        assertFalse(rows.titles().toString(), SettingsMenu.ANDROID_TITLE in rows.titles())
        assertFalse(SettingsMenu.ANDROID_TITLE in html)
        assertEquals("presence from its camera, as on a tablet", "camera", (rows.first() as SettingsItem.Link).section)
    }

    @Test fun withoutPresenceTurnOffWhenIdleIsTheScreensOnlyOnOff() {
        // Home Assistant's Screen mode "always" keeps the screen on: the row says Never, and a choice puts the mode back.
        prefs.screenMode = "always"; prefs.idleOffSec = 300
        val idle = screen(generic()).first.filterIsInstance<SettingsItem.Duration>().first { it.title == "Turn off when idle" }
        assertEquals("Never", idle.shown(idle.value()))
        idle.save(0) {}
        assertEquals("Never leaves it always on, also at night (CodeRabbit on #475)", "always", prefs.screenMode)
        idle.save(120) {}
        assertEquals("presence", prefs.screenMode)
        assertEquals(120, prefs.idleOffSec)
        assertEquals("After 2 minutes", idle.shown(idle.value()))
    }

    @Test fun withPresenceTurnOffWhenIdleLeavesTheWakeSwitchAlone() {
        sensor(Sensor.TYPE_PROXIMITY, "vi5300 Proximity Sensor")
        prefs.screenMode = "always"; prefs.idleOffSec = 300
        val idle = screen(AqaraS1Plus()).first.filterIsInstance<SettingsItem.Duration>().first { it.title == "Turn off when idle" }
        assertEquals("After 5 minutes", idle.shown(idle.value()))
        idle.save(120) {}
        assertEquals("always", prefs.screenMode)
    }
}
