package sh.edm.s1kiosk

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import dev.glassworks.testing.RepoFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import sh.edm.s1kiosk.gecko.GeckoPinHarness
import sh.edm.s1kiosk.gecko.GeckoShadows

/**
 * MainActivity takes its orientation from the frame when it starts. The S1 stays exactly as it was: landscape either way
 * up, as its manifest asks. A device with a frame stands as its frame says. Any other turns as Android says, so a
 * tablet whose screen is portrait at rotation 0 isn't held portrait.
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
class OrientationTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun tearDown() = ShadowBuild.reset()

    private fun requested(): Int = GeckoPinHarness().use { it.activity.requestedOrientation }

    @Test @Config(qualifiers = "w960dp-h480dp-land-hdpi")
    fun theS1StaysLandscapeEitherWayUpAsItsManifestAsks() {
        ShadowBuild.setHardware("sun50iw10p1")
        val manifest = RepoFiles.file("frames/aqara-s1-plus/android/src/main/AndroidManifest.xml").readText()
        val main = manifest.substringAfter("android:name=\".MainActivity\"").substringBefore(">")
        assertTrue("the manifest, unchanged: $main", "android:screenOrientation=\"sensorLandscape\"" in main)
        assertEquals("the S1's frame", "aqara-s1-plus", app.glassworksRuntime.frame.board)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, requested())
    }

    /** Robolectric's screen is a phone's, portrait at rotation 0, as the Pixel Tablet's 1600x2560 is. On its dock it's landscape. */
    @Test @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun aPixelTabletIsLandscapeThoughItsScreenIsPortraitAtRotation0() {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, requested())
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun anUnknownTabletTurnsWithAutoRotateOrThePersonsChoice() {
        ShadowBuild.setManufacturer("Example")
        ShadowBuild.setModel("Example Tab 10")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, requested())
    }

    /**
     * The Mirror stands portrait because its Android does (`ro.sf.hwrotation=270`, auto-rotate off, #198): the app
     * turns with it and locks nothing. Its model isn't a frame row (lululemon-mirror/research has no reading of it).
     */
    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun theMirrorStandsAsItsAndroidDoes() {
        ShadowBuild.setModel("MSM8916 for arm64")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, requested())
    }

    /**
     * Both apps' manifests take a turn as a config change, so the activity and its page stay. Robolectric's package
     * manager reads no manifest attributes here: the test gives it the ones it checked.
     */
    @Test @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun aTabletTurnsWithoutLosingItsActivityOrItsPage() {
        val turn = mapOf(
            "orientation" to ActivityInfo.CONFIG_ORIENTATION, "screenSize" to ActivityInfo.CONFIG_SCREEN_SIZE,
            "screenLayout" to ActivityInfo.CONFIG_SCREEN_LAYOUT, "smallestScreenSize" to ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE,
        )
        for (path in listOf("frames/aqara-s1-plus/android/src/main/AndroidManifest.xml", "frames/generic-android/android/src/main/AndroidManifest.xml")) {
            val main = RepoFiles.file(path).readText().split("<activity").first { "MainActivity\"" in it.substringBefore(">") }.substringBefore(">")
            val handled = main.substringAfter("android:configChanges=\"").substringBefore("\"").split("|")
            assertTrue("$path: $handled", handled.containsAll(turn.keys))
        }
        GeckoPinHarness().use { h ->
            val info = app.packageManager.getActivityInfo(h.activity.componentName, 0)
            shadowOf(app.packageManager).addOrUpdateActivity(info.apply { configChanges = turn.values.reduce(Int::or) })
            val before = h.activity
            val sessions = GeckoShadows.sessions.toList()
            val events = h.eventsDuring {
                RuntimeEnvironment.setQualifiers("w1280dp-h800dp-land-xhdpi")
                h.controller.configurationChange(app.resources.configuration)
            }
            assertSame("not recreated", before, h.activity)
            assertFalse(h.activity.isFinishing)
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, h.activity.resources.configuration.orientation)
            assertEquals("the same page", sessions, GeckoShadows.sessions.toList())
            assertTrue("no page closed or reloaded: $events", events.none { it.endsWith(".close") || it.endsWith(".open") || ".load " in it || it.endsWith(".reload") })
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, h.activity.requestedOrientation)
        }
    }
}
