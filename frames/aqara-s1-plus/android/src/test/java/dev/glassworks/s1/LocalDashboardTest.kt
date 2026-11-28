package dev.glassworks.s1

import android.content.Intent
import android.os.Looper
import dev.glassworks.esphome.ProtoMessage
import dev.glassworks.esphome.ProtoWriter
import dev.glassworks.esphome.SelectEntity
import dev.glassworks.platform.AppBuild
import dev.glassworks.platform.PlatformApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import dev.glassworks.s1.base.BaseBackend
import dev.glassworks.dashboard.Dashboard
import dev.glassworks.dashboard.DashboardOptions
import dev.glassworks.discovery.HaDiscovery
import dev.glassworks.device.ManualExecutor
import dev.glassworks.device.PanelDevice
import dev.glassworks.device.RecordingMdns
import dev.glassworks.s1.frame.AqaraS1Plus
import java.net.InetAddress
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.KioskPrefs

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

    private val stops = ArrayList<() -> Unit>()

    @After fun tearDown() = stops.forEach { runCatching { it() } }

    @Test @Config(sdk = [29])
    fun `below Android 12 the dashboard loads through the address found`() = harness().use { h ->
        assertEquals(listOf("s1.load http://192.0.2.10:8123/lovelace/0"), h.loads())
        assertEquals("the setting stays as entered", setting, KioskPrefs(h.app).urlSetting)
        assertTrue(h.logs("Glassworks").toString(), "Dashboard through 192.0.2.10:8123: Android can't resolve homeassistant.local before 12" in h.logs("Glassworks"))
    }

    @Test fun `from Android 12 it loads as set`() = harness().use { h ->
        assertEquals(listOf("s1.load $setting"), h.loads())
    }

    @Test @Config(sdk = [29])
    fun `a new address from discovery reaches the dashboard, a restart for the engine's hosts`() = harness().use { h ->
        KioskPrefs(h.app).setHaUrl("http://192.0.2.11:8123", HaDiscovery.SOURCE_MDNS)
        h.newIntent(Intent().putExtra("ha_discovered", "http://192.0.2.11:8123"))
        assertTrue(h.logs("Glassworks").toString(), h.logs("Glassworks").any { it.startsWith("restart deferred") })
    }

    @Test @Config(sdk = [29])
    fun `before discovery has an address it loads as set, then the first address restarts onto it`() = harness(at = null).use { h ->
        assertEquals(listOf("s1.load $setting"), h.loads())
        assertTrue(h.logs("Glassworks").toString(), h.logs("Glassworks").any { it.endsWith("and Home Assistant's address isn't known yet") })
        KioskPrefs(h.app).setHaUrl(found, HaDiscovery.SOURCE_MDNS)
        h.newIntent(Intent().putExtra("ha_discovered", found))
        assertTrue(h.logs("Glassworks").toString(), h.logs("Glassworks").any { it.startsWith("restart deferred") })
    }

    @Test @Config(sdk = [29])
    fun `a user and password in the setting never reach the log`() = harness("http://user:secret@homeassistant.local:8123/").use { h ->
        assertEquals(listOf("s1.load http://user:secret@192.0.2.10:8123/"), h.loads())
        h.newIntent(Intent().putExtra("ha_discovered", found))
        val logs = h.logs("Glassworks")
        assertTrue(logs.toString(), "Home Assistant at $found (dashboard setting: http://homeassistant.local:8123)" in logs)
        assertTrue(logs.filter { "secret" in it }.toString(), logs.none { "secret" in it })
    }

    @Test @Config(sdk = [29])
    fun `https keeps the name its certificate is for, and the log says so`() = harness("https://homeassistant.local:8123/").use { h ->
        assertEquals(listOf("s1.load https://homeassistant.local:8123/"), h.loads())
        assertTrue(h.logs("Glassworks").toString(), h.logs("Glassworks").any { it.endsWith("https, and its certificate is for the name") })
    }

    @Test @Config(sdk = [29])
    fun `the connecting screen names homeassistant_local, not the address it loads`() = harness().use { h ->
        h.progress.onPageStart(h.session, "http://192.0.2.10:8123/lovelace/0")
        assertTrue(h.loadingTexts().toString(), "Connecting to Home Assistant · homeassistant.local" in h.loadingTexts())
    }

    /** Through the real PanelDevice: Home Assistant picks a dashboard in the panel's Dashboard select. */
    @Test @Config(sdk = [29])
    fun `picking a dashboard in Home Assistant saves the local name, not the address`() {
        val app = RuntimeEnvironment.getApplication()
        KioskPrefs(app).apply {
            urlSetting = "http://homeassistant.local:8123/"
            setHaUrl(found, HaDiscovery.SOURCE_MDNS)
            dashboardsJson = DashboardOptions.toJson(listOf(Dashboard("tablet", "Tablet")))
        }
        PanePrefs(app).wallBase.backend = BaseBackend.LOCALSERVICE.pref
        val runtime = KioskApp.runtime(AqaraS1Plus(), AppBuild("1.0.0", 10000, debug = false))
        val sent = ArrayList<Intent>()
        runtime.commandSink = { sent += it }
        val exec = ManualExecutor()
        val device = PanelDevice(app, runtime, runtime::devicePanes, onApiHello = { _, _ -> }, exec = exec, mdns = RecordingMdns(),
            apiPort = 0, apiBindAddress = InetAddress.getLoopbackAddress()).also { stops += it::stop }
        device.start()
        exec.runDue()

        val select = device.entityList().filterIsInstance<SelectEntity>().single { it.objectId == "dashboard" }
        assertFalse("dashboard select is enabled by default in Home Assistant", select.disabledByDefault)
        assertEquals("the setting's dashboard, matched by name", "Default", select.shownState())
        select.command(ProtoMessage(ProtoWriter().fixed32(1, select.key, true).string(2, "Tablet").toByteArray()))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("http://homeassistant.local:8123/tablet", sent.single().getStringExtra("url"))
    }

    @Test fun `a user and password in the setting stay out of Home Assistant's base`() {
        val app = RuntimeEnvironment.getApplication()
        KioskPrefs(app).apply { urlSetting = "http://user:secret@homeassistant.local:8123/"; setHaUrl(found, HaDiscovery.SOURCE_MDNS) }
        val old = KioskPrefs(app, platform = PlatformApi(23))
        assertEquals("http://user:secret@192.0.2.10:8123/", old.url)
        assertEquals("the sign-in's base, as logged and stored", found, old.haBase)
        assertEquals("http://homeassistant.local:8123", old.settingBase)
    }

    @Test fun `the engine's hosts, Home Assistant's base and the dashboard select follow the platform`() {
        val app = RuntimeEnvironment.getApplication()
        KioskPrefs(app).apply { urlSetting = setting; setHaUrl(found, HaDiscovery.SOURCE_MDNS) }
        val old = KioskPrefs(app, platform = PlatformApi(23))
        assertEquals(listOf("192.0.2.10"), old.secureHosts())
        assertEquals(found, old.haBase)
        assertTrue(old.loadsFoundAddress)
        assertEquals("http://homeassistant.local:8123", old.settingBase)

        // No address yet: a failed load browses again, and the first address found is loaded.
        KioskPrefs(app).setHaUrl("", HaDiscovery.SOURCE_MDNS)
        assertTrue(KioskPrefs(app, platform = PlatformApi(23)).loadsFoundAddress)
        KioskPrefs(app).setHaUrl(found, HaDiscovery.SOURCE_MDNS)

        val new = KioskPrefs(app, platform = PlatformApi(31))
        assertEquals(listOf("homeassistant.local"), new.secureHosts())
        assertEquals("http://homeassistant.local:8123", new.haBase)
        assertFalse(new.loadsFoundAddress)
    }
}
