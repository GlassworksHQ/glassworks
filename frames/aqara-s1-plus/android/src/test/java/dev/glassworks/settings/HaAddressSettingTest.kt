package dev.glassworks.settings

import android.app.Application
import dev.glassworks.KioskPrefs
import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Dashboard → Home Assistant address setting normalizes bare hosts/IPs to `http://<host>:8123`,
 * preserves explicit schemes/ports, maps blank or `auto` to [KioskPrefs.AUTO], and rejects invalid
 * values with an inline error instead of saving them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HaAddressSettingTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs get() = KioskPrefs(app)

    private fun addressItem(): SettingsItem.Text {
        val core = CoreSettings(
            prefs = prefs,
            version = "1.0.0",
            status = { "" },
            deviceSettings = null,
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {},
            hardware = MenuFixture.S1_HARDWARE,
        )
        return core.sections().getValue(SettingsPlace.DASHBOARD).items
            .filterIsInstance<SettingsItem.Text>()
            .single { it.title == "Home Assistant" }
    }

    private fun save(item: SettingsItem.Text, input: String): String? {
        var result: String? = "unset"
        SettingsChange.change(item, input) { result = it }
        return result
    }

    @Test
    fun normalizesBareHostsAndPreservesExplicitSchemesAndPorts() {
        val item = addressItem()

        assertNull(save(item, "192.0.2.10"))
        assertEquals("http://192.0.2.10:8123", prefs.urlSetting)

        assertNull(save(item, "192.0.2.10:80"))
        assertEquals("http://192.0.2.10:80", prefs.urlSetting)

        assertNull(save(item, "homeassistant.local/lovelace/0"))
        assertEquals("http://homeassistant.local:8123/lovelace/0", prefs.urlSetting)

        assertNull(save(item, "[fd00::5]"))
        assertEquals("http://[fd00::5]:8123", prefs.urlSetting)

        assertNull(save(item, "https://ha.example.com"))
        assertEquals("https://ha.example.com", prefs.urlSetting)

        assertNull(save(item, "http://ha.example.com"))
        assertEquals("http://ha.example.com", prefs.urlSetting)
    }

    @Test
    fun blankAndAutoSaveAutoAndDisplayFoundAutomatically() {
        val item = addressItem()
        prefs.setHaUrl("http://192.0.2.10:8123", "mdns")

        assertNull(save(item, "192.0.2.20"))
        assertEquals("http://192.0.2.20:8123", prefs.urlSetting)

        assertNull(save(item, ""))
        assertEquals(KioskPrefs.AUTO, prefs.urlSetting)
        assertEquals("", item.value())
        assertEquals("192.0.2.10 · found automatically", SettingsChange.shown(item))

        assertNull(save(item, "192.0.2.20"))
        assertNull(save(item, "AUTO"))
        assertEquals(KioskPrefs.AUTO, prefs.urlSetting)

        assertTrue(item.hint.contains("homeassistant.local:8123"))
    }

    @Test
    fun invalidAddressReturnsInlineErrorWithoutOverwritingSavedValue() {
        val item = addressItem()
        prefs.urlSetting = "http://192.0.2.10:8123"

        for (bad in listOf("not a url", "ftp://192.0.2.10", "192.0.2.10:99999", "javascript:alert(1)", "http://")) {
            val err = save(item, bad)
            assertEquals("expected error for <$bad>", CoreSettings.HA_ADDRESS_ERROR, err)
            assertEquals("saved value untouched after <$bad>", "http://192.0.2.10:8123", prefs.urlSetting)
        }
    }
}
