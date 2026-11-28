/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

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
 * As the S1 app's: the engine is picked once, when the runtime is made. With Vitro off (the default) it is GeckoView
 * alone and no [VitroEngine] is made; Settings → Labs, or `--ez vitro true`, takes effect at the next start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = GenericApp::class)
class EngineChoiceTest {
    private val app get() = RuntimeEnvironment.getApplication() as GenericApp

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
}
