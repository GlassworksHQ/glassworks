package dev.glassworks.s1

import dev.glassworks.KioskPrefs
import dev.glassworks.engine.gecko.GeckoEngine
import dev.glassworks.engine.vitro.VitroEngine
import dev.glassworks.engine.vitro.camera.CameraStreamFactories
import dev.glassworks.engine.vitro.camera.CameraStreamKind
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The engine the app runs on is picked once, when its runtime is made: with Vitro off (the default) it is GeckoView
 * alone, as before Vitro existed, and no [VitroEngine] is made. Settings → Labs, or `--ez vitro true`, takes effect at
 * the next start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = KioskApp::class)
class EngineChoiceTest {
    private val app get() = RuntimeEnvironment.getApplication() as KioskApp

    @Test fun vitroOffRunsOnGeckoAlone() {
        assertFalse("off by default", KioskPrefs(app).vitroEnabled)
        val engine = app.glassworks.engine
        assertTrue("GeckoView, not wrapped: $engine", engine is GeckoEngine)
        assertFalse(engine is VitroEngine)
        assertNull("no WebRTC player without Vitro", CameraStreamFactories.provider(CameraStreamKind.WEB_RTC))
    }

    /** A start with Vitro on installs the crash sentinel as the default handler: the next test gets the old one back. */
    private val handler = Thread.getDefaultUncaughtExceptionHandler()

    @After fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(handler)
        CameraStreamFactories.unregister(CameraStreamKind.WEB_RTC)
    }

    @Test fun afterTheSentinelTurnedVitroOffTheNextStartIsOnGecko() {
        // VitroSentinel: the app crashed twice in a row just after starting with Vitro on.
        KioskPrefs(app).vitroEnabled = true
        KioskPrefs(app).turnVitroOffAfterCrashes("ClassCastException in WeatherCardView.showMetric", 0L)
        assertTrue("the way back: the browser engine", app.glassworks.engine is GeckoEngine)
        assertSame("no sentinel without Vitro", handler, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test fun vitroOnAtStartWrapsGecko() {
        KioskPrefs(app).vitroEnabled = true
        val engine = app.glassworks.engine
        assertTrue("Vitro: $engine", engine is VitroEngine)
        assertTrue("with GeckoView for the pages it can't draw", (engine as VitroEngine).fallback is GeckoEngine)
        assertFalse("the crash sentinel watches the process", handler === Thread.getDefaultUncaughtExceptionHandler())
        assertNotNull("its cameras play over WebRTC (engine-vitro-webrtc)", CameraStreamFactories.provider(CameraStreamKind.WEB_RTC))
    }

    @Test fun turningItOnLaterWaitsForARestart() {
        val engine = app.glassworks.engine
        KioskPrefs(app).vitroEnabled = true
        assertSame("the running process keeps its engine", engine, app.glassworks.engine)
        assertTrue(engine is GeckoEngine)
    }

    @Test fun theRuntimeTestsMakeIsGeckoToo() {
        assertTrue(KioskApp.engine(app.build) is GeckoEngine)
        assertTrue(KioskApp.engine(app.build, vitro = true) is VitroEngine)
    }
}
