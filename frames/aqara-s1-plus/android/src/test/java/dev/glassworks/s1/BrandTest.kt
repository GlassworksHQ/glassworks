package dev.glassworks.s1

import android.app.Notification
import dev.glassworks.testing.RepoFiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.glassworks.KioskService

/**
 * The S1 shows Glassworks (#527): its name and the brand's icon in the launcher and Settings → Apps, and the mark in
 * the service's notification. It was "S1 Kiosk" with a house glyph up to 0.4.3. The app id stays sh.edm.s1kiosk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = KioskApp::class)
class BrandTest {
    @Test fun theLauncherAndSettingsShowGlassworks() {
        assertEquals("sh.edm.s1kiosk", BuildConfig.APPLICATION_ID)
        // The sources: this module's tests run without the merged manifest and the app's resources.
        val main = File(RepoFiles.s1App, "src/main")
        val application = Regex("""<application\b[^>]*>""").find(File(main, "AndroidManifest.xml").readText())!!.value
        assertTrue(application, """android:label="@string/app_name"""" in application)
        assertTrue(application, """android:icon="@mipmap/ic_launcher"""" in application)
        assertTrue("""<string name="app_name">Glassworks</string>""" in File(main, "res/values/strings.xml").readText())
        assertTrue(File(main, "res/mipmap-anydpi-v26/ic_launcher.xml").isFile)
    }

    @Test fun theServicesNotificationSaysGlassworksWithTheMark() {
        val service = Robolectric.buildService(KioskService::class.java).get()
        val build = dev.glassworks.KioskService::class.java.getDeclaredMethod("buildNotification").apply { isAccessible = true }
        val n = build.invoke(service) as Notification
        assertEquals("Glassworks", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(R.drawable.ic_notification, n.smallIcon.resId)
    }
}
