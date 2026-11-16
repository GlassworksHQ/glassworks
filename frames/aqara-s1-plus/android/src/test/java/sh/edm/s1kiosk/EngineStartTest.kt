package sh.edm.s1kiosk

import android.app.Application
import dev.glassworks.engine.EngineSettings
import dev.glassworks.platform.AppBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A new dashboard address restarts the engine when the engine depends on it (#198 part 2). The legacy engine
 * holds pages to the dashboard's origin, which it takes once, from [EngineSettings.dashboard]; so the app hands
 * it the configured URL, and a change of origin (not just of host) restarts the process. The settings menu's
 * field restarts the app on any change (SettingsMenuTest.onlyWhatGeckoReadsAtStartAsksForARestart); the
 * installer's `ha.set` restarts when [KioskPrefs.engineStart] changes (SetupChannelServer.setHa). RFC 5737 hosts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EngineStartTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun restartsFor(from: String, to: String): Boolean {
        val prefs = KioskPrefs(app)
        prefs.urlSetting = from
        val before = prefs.engineStart()
        prefs.urlSetting = to
        return prefs.engineStart() != before
    }

    @Test fun `a new origin restarts the engine, a new path on the same origin doesn't`() {
        assertEquals(false, restartsFor("http://192.0.2.10:8123/lovelace/0", "http://192.0.2.10:8123/dashboard-wall/0"))
        assertEquals("a new host", true, restartsFor("http://192.0.2.10:8123/", "http://192.0.2.11:8123/"))
        assertEquals("the same host on another port: hosts alone missed it", true, restartsFor("http://192.0.2.10:8123/", "http://192.0.2.10:8124/"))
        assertEquals("the same host over https", true, restartsFor("http://192.0.2.10:8123/", "https://192.0.2.10:8123/"))
    }

    /** java.net.URI gives no host for a non-ASCII name; the legacy engine reads it, so a port change must restart too. */
    @Test fun `a Unicode dashboard host restarts on a port or scheme change too`() {
        assertEquals(true, restartsFor("http://ält.example.org:8123/", "http://ält.example.org:8124/"))
        assertEquals(true, restartsFor("http://ält.example.org:8123/", "https://ält.example.org:8123/"))
        assertEquals(false, restartsFor("http://ält.example.org:8123/a", "http://ält.example.org:8123/b"))
    }

    @Test fun `finding Home Assistant on auto restarts the engine`() {
        val prefs = KioskPrefs(app)
        prefs.urlSetting = KioskPrefs.AUTO
        val before = prefs.engineStart()
        prefs.setHaUrl("http://192.0.2.10:8123", "mdns")
        assertNotEquals(before, prefs.engineStart())
    }

    @Test fun `the engine starts with the configured dashboard, or none`() {
        val prefs = KioskPrefs(app)
        prefs.urlSetting = "http://192.0.2.10:8123/lovelace/0"
        assertEquals("http://192.0.2.10:8123/lovelace/0", started().dashboard)
        prefs.urlSetting = KioskPrefs.AUTO
        prefs.setHaUrl("", "")
        assertNull("auto, nothing found: no dashboard, and the legacy engine loads nothing", started().dashboard)
    }

    /** What KioskApp's engine reads at its first open. */
    private fun started(): EngineSettings = KioskApp.engineSettings(app, AppBuild("1.0.0", 10000, debug = false))
}
