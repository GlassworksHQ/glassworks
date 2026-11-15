package sh.edm.s1kiosk.update

import android.Manifest
import android.app.Application
import android.content.pm.PackageInstaller.SessionParams
import android.os.Parcel
import dev.glassworks.platform.PlatformApi
import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The PackageInstaller session for an update (#277): on Android 12+ it asks for no user action, and
 * below it is byte for byte the session the app always used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateSessionTest {
    private val pkg = "sh.edm.s1kiosk"

    private fun bytes(p: SessionParams): ByteArray {
        val parcel = Parcel.obtain()
        try {
            p.writeToParcel(parcel, 0)
            return parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    /** `SessionParams.requireUserAction` is hidden: what PackageInstallerSession reads. */
    private fun requireUserAction(p: SessionParams): Int =
        SessionParams::class.java.getField("requireUserAction").getInt(p)

    /** The session as it was before #277. */
    private fun before(size: Long) = SessionParams(SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(pkg)
        setSize(size)
    }

    @Test
    fun `below Android 12 the session is unchanged`() {
        for (api in listOf(26, 29, 30)) {
            val p = UpdateManager.sessionParams(pkg, 12_345, PlatformApi(api))
            assertArrayEquals("API $api", bytes(before(12_345)), bytes(p))
            assertEquals(SessionParams.USER_ACTION_UNSPECIFIED, requireUserAction(p))
        }
    }

    @Test
    fun `from Android 12 the session asks for no user action`() {
        for (api in listOf(31, 34, 35, 37)) {
            val p = UpdateManager.sessionParams(pkg, 12_345, PlatformApi(api))
            assertEquals("API $api", SessionParams.USER_ACTION_NOT_REQUIRED, requireUserAction(p))
            val same = before(12_345).apply { setRequireUserAction(SessionParams.USER_ACTION_NOT_REQUIRED) }
            assertArrayEquals("API $api: nothing else changes", bytes(same), bytes(p))
        }
    }

    @Test
    fun `the app declares the permissions Android checks`() {
        val manifest = File(RepoFiles.s1App, "src/main/AndroidManifest.xml").readText()
        for (p in listOf(Manifest.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION, Manifest.permission.REQUEST_INSTALL_PACKAGES)) {
            assertTrue(p, "<uses-permission android:name=\"$p\" />" in manifest)
        }
    }
}
