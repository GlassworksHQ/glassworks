package dev.glassworks.s1

import android.content.Intent
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaAuth
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Clarity's test dashboard on the S1 app (`--ez test_hold true --es test_dashboard <loopback URL>`): the reference
 * dashboard shows for the graded part, and the panel's saved dashboard and its sign-in stay as they were. Before, the
 * runner set the saved dashboard, and since #496 a moved Home Assistant address asks to sign in again. RFC 5737 addresses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = KioskApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class TestDashboardTest {
    @After fun noHoldLeft() {
        File(RuntimeEnvironment.getApplication().noBackupFilesDir, "test_hold").delete()
    }

    private fun GeckoPinHarness.loads() = events.filter { ".load " in it }.map { it.substringAfter(".load ") }

    @Test fun theS1ShowsTheTestsDashboardAndKeepsItsOwnAndItsSignIn() = GeckoPinHarness(prefs = { it.urlSetting = DASHBOARD }).use { h ->
        h.signIn(HA)
        h.newIntent(Intent().putExtra("reload", true))
        val before = h.loads().size
        h.newIntent(Intent().putExtra("test_hold", true).putExtra("test_hold_s", 1800).putExtra("test_dashboard", FAKE))
        assertEquals(listOf(FAKE), h.loads().drop(before))
        assertEquals(DASHBOARD, KioskPrefs(h.app).urlSetting)
        assertTrue(HaAuth.signedIn)
        assertFalse(HaAuth.movedAway)

        val after = h.loads().size
        h.newIntent(Intent().putExtra("test_dashboard", ""))
        val back = h.loads().drop(after)
        assertEquals(back.toString(), 1, back.size)
        assertTrue("its own dashboard, signed in: $back", back[0].startsWith(DASHBOARD) && "external_auth=1" in back[0])
        assertTrue(HaAuth.signedIn)
        assertFalse(HaAuth.movedAway)
    }

    /**
     * Clarity reads which engine draws the page from the kiosk's answer to the hold (perfrun.py ENGINE_RE), and the
     * Vitro setting it puts back after a Vitro run. The hold takes its extra off the intent, so the answer is decided
     * before that (CodeRabbit on #835).
     */
    @Test fun theHoldsAnswerSaysTheEngineAndTheVitroSetting() = GeckoPinHarness(prefs = { it.urlSetting = DASHBOARD }).use { h ->
        org.robolectric.shadows.ShadowLog.clear()
        h.newIntent(Intent().putExtra("test_hold", true).putExtra("test_hold_s", 60))
        val said = org.robolectric.shadows.ShadowLog.getLogs().map { it.msg }.filter { it.startsWith("test hold engine: ") }
        assertEquals(said.toString(), 1, said.size)
        assertTrue(said[0], Regex("^test hold engine: .+; vitro setting off$").matches(said[0]))
        h.newIntent(Intent().putExtra("test_hold", false))
    }

    private companion object {
        const val HA = "http://192.0.2.10:8123"
        const val DASHBOARD = "http://192.0.2.10:8123/lovelace/0"
        const val FAKE = "http://127.0.0.1:8089/clarity-ref/start"
    }
}
