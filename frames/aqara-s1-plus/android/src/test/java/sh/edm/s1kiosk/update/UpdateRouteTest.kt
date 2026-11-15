package sh.edm.s1kiosk.update

import android.app.Application
import dev.glassworks.frame.Frame
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.frame.RootUpdates
import dev.glassworks.platform.AppBuild
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.GlassworksRuntime
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Which way an update installs, from the frame's [RootUpdates] (Frame.rootUpdates): the S1's root update
 * service when the boot image has it, else Android's PackageInstaller.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateRouteTest {
    private val app = RuntimeEnvironment.getApplication()
    private val managers = ArrayList<UpdateManager>()

    @After fun tearDown() = managers.forEach { it.stop() }

    private class FakeRoot(private val present: Boolean, private val failure: String? = null, private val last: String? = null) : RootUpdates {
        val installed = ArrayList<String>()
        override fun present() = present
        override fun lastFailure() = last
        override fun install(version: String, installing: (String) -> Unit, over: () -> Boolean): String? {
            installed += version
            installing("Installing $version (root update service)")
            return failure
        }
    }

    /** The generic frame, with [root] as its root update service. */
    private fun frame(root: RootUpdates?): Frame = object : Frame by GenericAndroid(app) {
        override val rootUpdates = root
    }

    /** A manager on [frame], started, once its first pass on the update thread is done. */
    private fun started(frame: Frame): UpdateManager {
        val ready = CountDownLatch(1)
        val m = UpdateManager(app, GlassworksRuntime(frame, AppBuild("1.0.0", 10000, debug = false))) { ready.countDown() }
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
        installVerified(m)
        assertEquals("the service is never asked", emptyList<String>(), root.installed)
        assertEquals(1, sessions())
    }

    @Test fun aFrameWithoutARootServiceInstallsWithPackageInstaller() {
        val m = started(frame(null))
        assertEquals(UpdateManager.NO_TAP_NOTE, m.model.installNote) // Android 12+ (sdk 34)
        installVerified(m)
        assertEquals(1, sessions())
    }

    @Test fun theLastFailedRootInstallIsShownAtStart() {
        val m = started(frame(FakeRoot(present = true, last = "pm: signatures differ")))
        assertEquals("Last update failed: pm: signatures differ", m.model.statusText())
    }
}
