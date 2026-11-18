package sh.edm.s1kiosk

import android.app.Application
import dev.glassworks.KioskPrefs
import dev.glassworks.platform.AppBuild
import dev.glassworks.update.SilentUpdatesStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.frame.AqaraS1Plus

/**
 * The device's own setup steps (#422's Silent updates): after the panes' steps, also on the setup channel, and never
 * shown on a panel that was set up before a build that has them (as the radio's step).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceSetupStepsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun runtime() = KioskApp.runtime(AqaraS1Plus(), build = AppBuild("1.0.0", 10000, debug = false))

    @Test fun silentUpdatesFollowsThePanesStepsAlsoOnTheSetupChannel() {
        val r = runtime()
        assertEquals(listOf(SilentUpdatesStep.ID), r.deviceSetupSteps(app).map { it.id })
        assertEquals(SilentUpdatesStep.ID, r.setupSteps(app).last().id)
        assertTrue("made once", r.deviceSetupSteps(app).single() === r.deviceSetupSteps(app).single())
    }

    @Test fun aFreshPanelIsAskedDuringSetup() {
        runtime().paneDefinitions(app)
        assertFalse(KioskPrefs(app).silentUpdatesAnswered)
    }

    @Test fun aPanelSetUpBeforeNeverSeesIt() {
        KioskPrefs(app).haSeen = true
        runtime().paneDefinitions(app)
        assertTrue(KioskPrefs(app).silentUpdatesAnswered)
    }

    @Test fun theCheckRunsOncePerInstall() {
        runtime().paneDefinitions(app) // fresh: not answered
        KioskPrefs(app).haSeen = true // then setup reaches Home Assistant
        runtime().paneDefinitions(app) // a restart mid-setup
        assertFalse("still asked during this first setup", KioskPrefs(app).silentUpdatesAnswered)
    }

    /** Setup's end answers the device's own steps, also one it skipped, so a lost grant never brings setup back. */
    @Test fun theEndOfSetupAnswersTheDevicesOwnSteps() {
        val r = runtime()
        r.paneDefinitions(app) // a fresh panel: not answered
        assertFalse(KioskPrefs(app).silentUpdatesAnswered)
        r.settleDeviceSteps(app)
        assertTrue(KioskPrefs(app).silentUpdatesAnswered)
        assertTrue("answered: it never asks again", r.deviceSetupSteps(app).single().done())
    }
}
