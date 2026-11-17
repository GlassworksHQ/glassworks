package sh.edm.s1kiosk.settings

import android.app.Application
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskPrefs

/**
 * "Allow setup over the network" in the panel's own settings (#34): the third way to open pairing again once
 * Home Assistant has the panel, beside the HA button and Kiln over USB. The Manage page draws the same item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SetupOverNetworkSettingTest {
    private val prefs = KioskPrefs(RuntimeEnvironment.getApplication())
    private var ip: String? = "192.0.2.10"

    private fun item(): SettingsItem.Action {
        val core = CoreSettings(
            prefs, "1.0.0", status = { "" }, deviceSettings = null,
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {},
            panelIp = { ip }, hardware = MenuFixture.S1_HARDWARE,
        )
        return core.sections().getValue(SettingsPlace.DASHBOARD).items.filterIsInstance<SettingsItem.Action>()
            .single { it.title == CoreSettings.SETUP_OVER_NETWORK }
    }

    @Test fun beforeHomeAssistantHasThePanelItSaysWhereToOpenThePage() {
        prefs.haSeen = false
        val d = item().detail()
        assertTrue(d, d.contains("http://192.0.2.10:8099/setup"))
        assertTrue(d, d.contains("until Home Assistant adds the panel"))
    }

    @Test fun afterwardsAllowOpensPairingForThirtyMinutes() {
        prefs.haSeen = true
        assertFalse("pairing is off once HA has the panel", prefs.lanPairingOpen)
        val action = item()
        assertEquals("Lets a computer on this network set the panel up, for 30 minutes.", action.detail())
        var answer: String? = "not called"
        action.run { answer = it }
        assertEquals(null, answer)
        assertTrue("Allow opened it", prefs.lanPairingOpen)
        val until = prefs.lanSetupUntil - System.currentTimeMillis()
        assertTrue("about 30 minutes: $until", until in 29 * 60_000L..30 * 60_000L)
        val d = item().detail()
        assertTrue(d, d.startsWith("On until ") && d.contains("http://192.0.2.10:8099/setup"))
    }

    @Test fun withoutWifiItLeavesTheAddressOut() {
        prefs.haSeen = false
        ip = null
        val d = item().detail()
        assertFalse(d, d.contains("http://"))
        assertTrue(d, d.contains("the panel's setup page"))
    }

    /** The window ends: pairing closes again (the deadline, not only "was it ever allowed"). */
    @Test fun pairingClosesWhenTheThirtyMinutesEnd() {
        prefs.haSeen = true
        prefs.armLanSetup()
        assertTrue(prefs.lanPairingOpen)
        val sp = sh.edm.s1kiosk.SecretFiles.open(RuntimeEnvironment.getApplication(), "kiosk", dev.glassworks.secrets.SecretStore.keystore())
        sp.edit().putLong("lan_setup_until", System.currentTimeMillis() - 1).commit()
        assertFalse("the 30 minutes are over", prefs.lanPairingOpen)
        sp.edit().putLong("lan_setup_until", System.currentTimeMillis() + 60_000).commit()
        assertTrue("and a new window opens it", prefs.lanPairingOpen)
    }

    /** The Manage page draws the same row: Allow there needs a signed-in Manage session, and then opens pairing. */
    @Test fun theManagePageAllowsItOnlyForASignedInBrowser() {
        prefs.haSeen = true
        val section = CoreSettings(
            prefs, "1.0.0", status = { "" }, deviceSettings = null,
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {}, panelIp = { ip }, hardware = MenuFixture.S1_HARDWARE,
        ).sections().getValue(SettingsPlace.DASHBOARD)
        val key = dev.glassworks.pane.SettingsChange.changeable(section).first { (_, i) -> i is SettingsItem.Action && i.title == CoreSettings.SETUP_OVER_NETWORK }.first
        val f = dev.glassworks.manage.ManageFixture()
        f.statusFn = { f.status.copy(sections = listOf(section)) }
        val form = mapOf("section" to section.id, "key" to key, "value" to SettingsItem.Action.RUN)

        val stranger = f.Browser()
        val refused = stranger.post("/settings", form + ("csrf" to "x"))
        assertTrue("not signed in: back to sign-in (${refused.status})", refused.status == 303 && refused.headers["Location"] == "/")
        assertFalse("nothing opened", prefs.lanPairingOpen)

        val b = f.Browser().also { it.signInWithHa() }
        assertEquals(303, b.post("/settings", form + ("csrf" to b.csrf())).status)
        assertTrue("signed in, Allow opened pairing", prefs.lanPairingOpen)
    }
}
