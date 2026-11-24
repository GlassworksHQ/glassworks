package dev.glassworks.s1

import dev.glassworks.KioskPrefs
import dev.glassworks.engine.gecko.GeckoEngine
import dev.glassworks.engine.vitro.VitroEngine
import org.junit.Assert.assertFalse
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
    }

    @Test fun vitroOnAtStartWrapsGecko() {
        KioskPrefs(app).vitroEnabled = true
        val engine = app.glassworks.engine
        assertTrue("Vitro: $engine", engine is VitroEngine)
        assertTrue("with GeckoView for the pages it can't draw", (engine as VitroEngine).fallback is GeckoEngine)
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
