package dev.glassworks.camera

import android.app.Application
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import dev.glassworks.platform.PlatformApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.glassworks.s1.KioskApp
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.glassworks

/** The camera's privacy dot (#117; maintainer, 2026-11-14): only below Android 12, which has its own. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CameraDotTest {
    private fun dot(api: Int) = CameraDot(RuntimeEnvironment.getApplication(), PlatformApi(api))

    @Test fun belowAndroid12ItShowsWhileImagesLeave() {
        for (api in listOf(29, 30)) {
            val d = dot(api)
            assertEquals("API $api: hidden at first", View.GONE, d.visibility)
            d.show(true)
            assertEquals("API $api: shown while leaving", View.VISIBLE, d.visibility)
            d.show(false)
            assertEquals("API $api: gone after", View.GONE, d.visibility)
        }
    }

    @Test fun fromAndroid12AndroidsOwnIndicatorSaysIt() {
        for (api in listOf(31, 33, 34)) {
            val d = dot(api)
            d.show(true)
            assertEquals("API $api: never ours", View.GONE, d.visibility)
        }
    }

    @Test fun itTakesNoTouches() {
        val d = dot(29).apply { show(true) }
        assertFalse(d.isClickable)
        val now = SystemClock.uptimeMillis()
        assertFalse("a touch goes to what is under it", d.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1f, 1f, 0)))
    }
}

/** The dot in the real MainActivity: it follows the runtime's "images leaving" (#117). */
private const val DASH = "http://192.0.2.10:8123/lovelace/0"

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [30],
    application = KioskApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class CameraDotOnAndroid11Test {
    @Test fun theDotFollowsImagesLeavingThePanel() = GeckoPinHarness(prefs = { it.urlSetting = DASH }).use { h ->
        val dot = h.rootChildren().filterIsInstance<CameraDot>().single()
        assertEquals("hidden at start", View.GONE, dot.visibility)
        val runtime = h.activity.glassworks
        runtime.cameraLeaving = true
        runtime.onCameraLeaving!!.invoke()
        assertEquals(View.VISIBLE, dot.visibility)
        runtime.cameraLeaving = false
        runtime.onCameraLeaving!!.invoke()
        assertEquals(View.GONE, dot.visibility)
    }
}

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
class CameraDotOnAndroid14Test {
    @Test fun androidsOwnIndicatorReplacesTheDot() = GeckoPinHarness(prefs = { it.urlSetting = DASH }).use { h ->
        val dot = h.rootChildren().filterIsInstance<CameraDot>().single()
        val runtime = h.activity.glassworks
        runtime.cameraLeaving = true
        runtime.onCameraLeaving!!.invoke()
        assertEquals("Android 12+ shows its own", View.GONE, dot.visibility)
    }
}
