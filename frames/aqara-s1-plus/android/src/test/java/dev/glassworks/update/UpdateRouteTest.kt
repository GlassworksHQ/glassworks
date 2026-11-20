package dev.glassworks.update

import android.app.Application
import android.content.ContextWrapper
import dev.glassworks.Glassworks
import dev.glassworks.GlassworksApp
import dev.glassworks.pane.SettingsItem
import dev.glassworks.frame.AgentCapability
import dev.glassworks.frame.Frame
import dev.glassworks.frame.FrameAgent
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.frame.RootUpdates
import dev.glassworks.platform.AppBuild
import dev.glassworks.update.SilentUpdates
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import dev.glassworks.s1.KioskApp

/**
 * Which way an update installs, from the frame agent's [RootUpdates] (FrameAgent.appUpdates): the S1's root update
 * service when the boot image has it (the agent offers `app-updates`), else Android's PackageInstaller.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateRouteTest {
    private val app = RuntimeEnvironment.getApplication()
    private val managers = ArrayList<UpdateManager>()

    @Before fun theAppsTarget() { app.applicationInfo.targetSdkVersion = 35 } // Robolectric's bare Application says 23

    @After fun tearDown() = managers.forEach { it.stop() }

    private class FakeRoot(val present: Boolean, private val failure: String? = null, private val last: String? = null) : RootUpdates {
        val installed = ArrayList<String>()
        override fun lastFailure() = last
        override fun install(version: String, installing: (String) -> Unit, over: () -> Boolean): String? {
            installed += version
            installing("Installing $version (root update service)")
            return failure
        }
    }

    /** The generic frame, with an agent whose app updates are [root], offered when its boot image has the service. */
    private fun frame(root: FakeRoot?): Frame = object : Frame by GenericAndroid(app) {
        override val agent = object : FrameAgent {
            override val declared = if (root == null) emptySet() else setOf(AgentCapability.APP_UPDATES)
            override fun version() = if (root == null) null else 1
            override fun offers(capability: AgentCapability) = capability == AgentCapability.APP_UPDATES && root?.present == true
            override val appUpdates: RootUpdates? = root
        }
    }

    /** A manager on [frame], started, once its first pass on the update thread is done. */
    private fun started(frame: Frame): UpdateManager {
        val ready = CountDownLatch(1)
        val m = UpdateManager(app, KioskApp.runtime(frame, AppBuild("1.0.0", 10000, debug = false))) { ready.countDown() }
        managers += m
        m.start()
        assertTrue("the update thread started", ready.await(5, TimeUnit.SECONDS))
        return m
    }

    /** Installs a verified APK the way the update thread does after the download, and returns the update card's status. */
    private fun installVerified(m: UpdateManager): String {
        val asset = ReleaseAsset("s1-kiosk-1.2.3.apk", "https://192.0.2.10/a.apk", 4)
        m.model.onChecked(UpdateOffer(AppVersion.parse("1.2.3")!!, "v1.2.3", "", "https://192.0.2.10/r", asset, asset))
        val a = m.model.beginInstall(0)!!
        val pending = File(app.filesDir, "pending.apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        UpdateManager::class.java.getDeclaredMethod("installVerified", UpdateModel.Attempt::class.java, File::class.java)
            .apply { isAccessible = true }.invoke(m, a, pending)
        return m.model.statusText()
    }

    private fun sessions() = app.packageManager.packageInstaller.allSessions.size

    @Test fun aBootImageWithTheRootServiceInstallsThroughIt() {
        val root = FakeRoot(present = true)
        val m = started(frame(root))
        assertEquals("no note: it installs on its own", "", m.model.installNote)
        assertEquals(SilentUpdates.RootService, m.silentUpdates)
        assertEquals("Installing 1.2.3 (root update service)", installVerified(m))
        assertEquals(listOf("1.2.3"), root.installed)
        assertEquals("no PackageInstaller session", 0, sessions())
    }

    @Test fun whatTheRootServiceRefusesFailsTheUpdate() {
        val m = started(frame(FakeRoot(present = true, failure = "root update service: pm refused")))
        val status = installVerified(m)
        assertTrue(status, status.contains("pm refused"))
    }

    @Test fun anOlderBootImageWithoutTheServiceFallsBackToPackageInstaller() {
        val root = FakeRoot(present = false)
        val m = started(frame(root))
        assertEquals(UpdateManager.NO_HELPER_NOTE, m.model.installNote)
        assertEquals(SilentUpdates.NoRootService, m.silentUpdates)
        installVerified(m)
        assertEquals("the service is never asked", emptyList<String>(), root.installed)
        assertEquals(1, sessions())
    }

    @Test fun aFrameWithoutARootServiceInstallsWithPackageInstaller() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val m = started(frame(null))
        assertEquals(SilentUpdates.SelfUpdate, m.silentUpdates) // Android 12+ (sdk 34)
        assertEquals(UpdateManager.NO_TAP_NOTE, m.model.installNote)
        assertEquals("Installing 1.2.3 without a tap", installVerified(m))
        assertEquals(1, sessions())
    }

    @Test fun withoutInstallUnknownAppsTheUpdateSaysItWaitsForConfirmation() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val m = started(frame(null))
        assertEquals(SilentUpdates.UnknownAppsOff, m.silentUpdates)
        assertEquals(UpdateManager.CONFIRM_NOTE, m.model.installNote)
        assertEquals("Installing 1.2.3: confirm on the screen", installVerified(m))
    }

    /** A started manager on the generic frame, and each time it tells Home Assistant its state may have changed. */
    private fun watched(runtime: Glassworks = KioskApp.runtime(frame(null), AppBuild("1.0.0", 10000, debug = false))): Pair<UpdateManager, LinkedBlockingQueue<Unit>> {
        val changes = LinkedBlockingQueue<Unit>()
        val m = UpdateManager(app, runtime) { changes.put(Unit) }
        managers += m
        m.start()
        assertTrue("the update thread started", changes.poll(5, TimeUnit.SECONDS) != null)
        return m to changes
    }

    @Test fun homeAssistantFollowsInstallUnknownAppsWhenTheAppComesBack() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val (m, changes) = watched()
        assertEquals(SilentUpdates.UnknownAppsOff, m.silentUpdates)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true) // allowed in Android's settings
        m.recheckSilent() // MainActivity.onResume
        assertTrue("Home Assistant is told", changes.poll(5, TimeUnit.SECONDS) != null)
        assertEquals(SilentUpdates.SelfUpdate, m.silentUpdates)
        assertEquals(UpdateManager.NO_TAP_NOTE, m.model.installNote)
    }

    @Test fun aFreshReadThatDiffersReachesHomeAssistant() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val (m, changes) = watched()
        m.noticeSilent(SilentUpdates.UnknownAppsOff) // the Device page agrees: nothing to tell
        m.recheckSilent() // and nothing changed since
        assertEquals("no push while nothing changed", null, changes.poll(500, TimeUnit.MILLISECONDS))
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        m.noticeSilent(SilentUpdates.SelfUpdate) // setup's step or Manage read the new answer
        assertTrue("Home Assistant is told", changes.poll(5, TimeUnit.SECONDS) != null)
        assertEquals(SilentUpdates.SelfUpdate, m.silentUpdates)
    }

    @Test fun theDevicePageRowAndManageTellHomeAssistant() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val runtime = KioskApp.runtime(frame(null), AppBuild("1.0.0", 10000, debug = false))
        val (m, changes) = watched(runtime)
        val context = object : ContextWrapper(app), GlassworksApp {
            override val glassworks = runtime
            override fun getApplicationContext() = this
        }
        val row = GenericAndroid(context).deviceSection(context)!!.items.filterIsInstance<SettingsItem.Info>().single { it.title() == "Silent updates" }
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        assertTrue(row.detail().startsWith("Yes: "))
        assertTrue("Home Assistant is told", changes.poll(5, TimeUnit.SECONDS) != null)
        assertEquals(SilentUpdates.SelfUpdate, m.silentUpdates)
    }

    @Test fun theLastFailedRootInstallIsShownAtStart() {
        val m = started(frame(FakeRoot(present = true, last = "pm: signatures differ")))
        assertEquals("Last update failed: pm: signatures differ", m.model.statusText())
    }
}
