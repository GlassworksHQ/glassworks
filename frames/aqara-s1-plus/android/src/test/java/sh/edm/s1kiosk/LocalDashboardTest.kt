package sh.edm.s1kiosk

import android.content.Intent
import dev.glassworks.platform.PlatformApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.dashboard.Dashboard
import sh.edm.s1kiosk.dashboard.DashboardOptions
import sh.edm.s1kiosk.discovery.HaDiscovery
import sh.edm.s1kiosk.gecko.GeckoPinHarness
import sh.edm.s1kiosk.gecko.GeckoShadows

/**
 * #449: a dashboard set as `http://homeassistant.local:8123` on an Android that can't resolve `.local` (the S1's
 * Android 10, the Mirror's 6) loads through the address discovery found, and the setting stays as entered. The real
 * MainActivity on GeckoShadows: Robolectric's API 29 stands for every level below 31. RFC 5737 addresses.
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
class LocalDashboardTest {
    private val setting = "http://homeassistant.local:8123/lovelace/0"
    private val found = "http://192.0.2.10:8123"

    private fun harness(setting: String = this.setting, at: String? = found) = GeckoPinHarness(prefs = {
        it.urlSetting = setting
        if (at != null) it.setHaUrl(at, HaDiscovery.SOURCE_MDNS)
    })

    private fun GeckoPinHarness.loads() = events.filter { it.startsWith("s1.load ") }

    @Test @Config(sdk = [29])
    fun `below Android 12 the dashboard loads through the address found`() = harness().use { h ->
        assertEquals(listOf("s1.load http://192.0.2.10:8123/lovelace/0"), h.loads())
        assertEquals("the setting stays as entered", setting, KioskPrefs(h.app).urlSetting)
        assertTrue(h.logs("S1Kiosk").toString(), "Dashboard through http://192.0.2.10:8123/lovelace/0: Android can't resolve homeassistant.local before 12" in h.logs("S1Kiosk"))
    }

    @Test fun `from Android 12 it loads as set`() = harness().use { h ->
        assertEquals(listOf("s1.load $setting"), h.loads())
    }

    @Test @Config(sdk = [29])
    fun `a new address from discovery reaches the dashboard, a restart for the engine's hosts`() = harness().use { h ->
        KioskPrefs(h.app).setHaUrl("http://192.0.2.11:8123", HaDiscovery.SOURCE_MDNS)
        h.newIntent(Intent().putExtra("ha_discovered", "http://192.0.2.11:8123"))
        assertTrue(h.logs("S1Kiosk").toString(), h.logs("S1Kiosk").any { it.startsWith("restart deferred") })
    }

    @Test @Config(sdk = [29])
    fun `before discovery has an address it loads as set, then the first address restarts onto it`() = harness(at = null).use { h ->
        assertEquals(listOf("s1.load $setting"), h.loads())
        assertTrue(h.logs("S1Kiosk").toString(), h.logs("S1Kiosk").any { it.endsWith("Home Assistant's address isn't known yet") })
        KioskPrefs(h.app).setHaUrl(found, HaDiscovery.SOURCE_MDNS)
        h.newIntent(Intent().putExtra("ha_discovered", found))
        assertTrue(h.logs("S1Kiosk").toString(), h.logs("S1Kiosk").any { it.startsWith("restart deferred") })
    }

    @Test @Config(sdk = [29])
    fun `https keeps the name its certificate is for, and the log says so`() = harness("https://homeassistant.local:8123/").use { h ->
        assertEquals(listOf("s1.load https://homeassistant.local:8123/"), h.loads())
        assertTrue(h.logs("S1Kiosk").toString(), h.logs("S1Kiosk").any { it.endsWith("https, and its certificate names homeassistant.local") })
    }

    @Test fun `the engine's hosts, Home Assistant's base and the dashboard select follow the platform`() {
        val app = RuntimeEnvironment.getApplication()
        KioskPrefs(app).apply { urlSetting = setting; setHaUrl(found, HaDiscovery.SOURCE_MDNS) }
        val old = KioskPrefs(app, platform = PlatformApi(23))
        assertEquals(listOf("192.0.2.10"), old.secureHosts())
        assertEquals(found, old.haBase)
        assertTrue(old.loadsFoundAddress)
        // The select compares with the setting and writes it: it keeps the name, so a choice doesn't save the address.
        val list = listOf(Dashboard("tablet", "Tablet"))
        assertEquals("http://homeassistant.local:8123", old.settingBase)
        assertEquals("http://homeassistant.local:8123/tablet", DashboardOptions.urlFor("Tablet", old.urlSetting, old.settingBase, old.haUrl, list))

        val new = KioskPrefs(app, platform = PlatformApi(31))
        assertEquals(listOf("homeassistant.local"), new.secureHosts())
        assertEquals("http://homeassistant.local:8123", new.haBase)
        assertFalse(new.loadsFoundAddress)
    }
}
