package sh.edm.s1kiosk.manage

import android.app.Application
import dev.glassworks.platform.AppBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskApp
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.HttpPoster
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.auth.ThreadJoin
import sh.edm.s1kiosk.frame.AqaraS1Plus

/**
 * #23: the Manage page is the core's AppManage, which every app serves. What only the S1 has stays the S1's
 * (S1Manage.S1): the radio's Thread share link, the Thread join's QR pages, the root side in the diagnostics,
 * and its licence line. The generic app's page has none of them (GenericAppTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class S1ManageOwnTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun manage(): S1Manage {
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        return S1Manage(app, KioskApp.runtime(AqaraS1Plus(), AppBuild("1.0.0", 10000, debug = false)), SignInServer(0, { null }, tokens, { _, _ -> }), tokens, KioskPrefs(app))
    }

    @Test fun theRadiosRowLinksTheThreadShare() {
        assertEquals(listOf("Share Home Assistant's Thread network (start it on the panel first)" to ThreadJoin.PATH), S1Manage.S1.paneLinks("radio"))
        assertTrue(S1Manage.S1.paneLinks("assist").isEmpty())
    }

    @Test fun theQrPagesServeTheThreadJoin() {
        assertEquals(setOf(ThreadJoin.PATH, SignInServer.THREAD_CONTINUE, ThreadJoin.CALLBACK), S1Manage.S1.qrPaths)
        val server = manage().server
        @Suppress("UNCHECKED_CAST")
        val served = dev.glassworks.manage.ManageServer::class.java.getDeclaredField("qrPaths").apply { isAccessible = true }.get(server) as Set<String>
        assertEquals(setOf("/signin", SignInServer.CONTINUE, "/callback", ThreadJoin.PATH, SignInServer.THREAD_CONTINUE, ThreadJoin.CALLBACK), served)
    }

    @Test fun theDiagnosticsEndWithTheRootSide() {
        val text = manage().diagnostics()
        assertTrue(text, text.contains("\nRoot side:\n"))
        for (k in listOf("sys.s1kiosk.radio.status", "persist.s1kiosk.radio_mode", "persist.s1kiosk.radio_zigbee")) assertTrue(k, text.contains("  $k = "))
    }

    @Test fun aboutKeepsTheS1sLicenceLine() {
        assertEquals("MIT, with the Glassworks core under MPL-2.0", manage().status().about.toMap()["Licence"])
    }
}
