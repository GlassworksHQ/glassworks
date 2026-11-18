package sh.edm.s1kiosk

import android.app.Application
import dev.glassworks.KioskPrefs
import dev.glassworks.platform.AppBuild
import dev.glassworks.setup.SetupPrefs
import dev.glassworks.setup.ThisDeviceStep
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
import sh.edm.s1kiosk.frame.TestFrames

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

    @Test fun thisDeviceComesFirstOnATabletAndNeverOnTheS1OrTheMirror() {
        assertTrue("the S1", runtime().deviceSetupSteps(app).none { it.id == ThisDeviceStep.ID })
        // The Mirror before the tablet: a test's input devices are one list, and the tablet adds a touchscreen (TestFrames).
        val mirror = KioskApp.runtime(TestFrames.mirror(app), build = AppBuild("1.0.0", 10000, debug = false))
        assertTrue("no touch: nobody could tap its buttons", mirror.deviceSetupSteps(app).none { it.id == ThisDeviceStep.ID })
        val tablet = KioskApp.runtime(TestFrames.tablet(app), build = AppBuild("1.0.0", 10000, debug = false))
        assertEquals(listOf(ThisDeviceStep.ID, SilentUpdatesStep.ID), tablet.deviceSetupSteps(app).map { it.id })
    }

    @Test fun aTabletSetUpBeforeNeverSeesThisDevice() {
        KioskPrefs(app).haSeen = true
        KioskApp.runtime(TestFrames.tablet(app), build = AppBuild("1.0.0", 10000, debug = false)).paneDefinitions(app)
        assertTrue(SetupPrefs(app).thisDeviceAnswered)
    }

    @Test fun aFreshTabletIsAskedDuringSetup() {
        KioskApp.runtime(TestFrames.tablet(app), build = AppBuild("1.0.0", 10000, debug = false)).paneDefinitions(app)
        assertFalse(SetupPrefs(app).thisDeviceAnswered)
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

    @Test fun theEndOfSetupAnswersThisDeviceToo() {
        val tablet = KioskApp.runtime(TestFrames.tablet(app), build = AppBuild("1.0.0", 10000, debug = false))
        tablet.paneDefinitions(app)
        assertFalse(SetupPrefs(app).thisDeviceAnswered)
        tablet.settleDeviceSteps(app)
        assertTrue(SetupPrefs(app).thisDeviceAnswered)
    }
}
