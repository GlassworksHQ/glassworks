package sh.edm.s1kiosk

import android.content.pm.ActivityInfo
import android.view.Surface
import dev.glassworks.testing.RepoFiles
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
import org.robolectric.shadows.ShadowDisplay
import sh.edm.s1kiosk.gecko.GeckoPinHarness
import sh.edm.s1kiosk.gecko.GeckoShadows

/**
 * #198: MainActivity takes its orientation from the frame when it starts, so the lululemon Mirror's portrait glass
 * gets a portrait app. The S1 stays exactly as it was: landscape either way up, as its manifest asks.
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

    /** Robolectric's screen is a phone's (portrait at rotation 0): a tablet stands landscape at rotation 0. */
    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun aLandscapeTabletIsLandscape() {
        shadowOf(ShadowDisplay.getDefaultDisplay()).setRotation(Surface.ROTATION_0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, requested())
    }

    /** A screen that stands portrait at rotation 0, as the Mirror's does with `ro.sf.hwrotation=270`. */
    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun aPortraitScreenGetsAPortraitApp() = assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, requested())
}
