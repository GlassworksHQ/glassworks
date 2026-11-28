/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.settings

import android.app.Application
import dev.glassworks.KioskPrefs
import dev.glassworks.SecretFiles
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsPlace
import dev.glassworks.secrets.SecretStore
import dev.glassworks.setup.SetupPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Factory reset (#33, decided 2026-11-17): the app's settings and its Home Assistant sign-in go, the panel's identity in
 * Home Assistant and the radio stay, and the app restarts into setup. The same row on the panel and on the Manage page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FactoryResetTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = KioskPrefs(app)
    private val kiosk = SecretFiles.open(app, "kiosk", SecretStore.keystore())

    @Test fun theSettingsGoAndTheIdentityTheRadioAndTheOnceChecksStay() {
        prefs.urlSetting = "http://192.0.2.10:8123/lovelace/0"
        prefs.hideHeader = false
        prefs.screenMode = "presence"
        prefs.haSeen = true
        prefs.wifiSeen = true
        prefs.haSetupSkipped = true
        prefs.silentUpdatesAnswered = true
        prefs.updateChannel = dev.glassworks.update.UpdateChannel.BETA
        prefs.armLanSetup()
        prefs.deviceMac = "02:00:00:00:00:01"
        prefs.deviceStepsMigrated = true
        assertTrue(kiosk.edit()
            .putBoolean("api_encryption", true)
            // Panes' settings live in the same file (PaneFiles): the voice pane's goes, the radio's stays.
            .putString("assist_wake_word", "okay_nabu")
            .putString("radio_mode", "zigbee").putString("radio_allow", "192.0.2.10").putBoolean("setup_radio_done", true).putBoolean("setup_radio_migrated", true)
            .commit())
        val setup = SetupPrefs(app)
        setup.thisDeviceAnswered = true
        setup.thisDeviceMigrated = true

        assertTrue(prefs.resetSettings() && setup.reset())

        assertTrue("the dashboard address is automatic again", prefs.isAutoUrl)
        assertTrue("full screen is back to its default", prefs.hideHeader)
        assertEquals("always", prefs.screenMode)
        assertFalse("Home Assistant adds the panel again", prefs.haSeen)
        assertFalse(prefs.wifiSeen)
        assertFalse(prefs.haSetupSkipped)
        assertFalse("setup asks Silent updates again", prefs.silentUpdatesAnswered)
        assertEquals(dev.glassworks.update.UpdateChannel.of(null), prefs.updateChannel)
        assertEquals(0L, prefs.lanSetupUntil)
        assertFalse(kiosk.contains("assist_wake_word"))
        assertFalse("setup asks This device again", setup.thisDeviceAnswered)

        assertTrue("the panel keeps its ESPHome identity", prefs.apiEncryption)
        assertEquals("02:00:00:00:00:01", prefs.deviceMac)
        assertEquals("the radio keeps its network up", "zigbee", kiosk.getString("radio_mode", null))
        assertEquals("192.0.2.10", kiosk.getString("radio_allow", null))
        assertTrue(kiosk.getBoolean("setup_radio_done", false))
        assertTrue("the once-per-install checks stay done, so no step is skipped", prefs.deviceStepsMigrated && setup.thisDeviceMigrated)
        assertTrue(kiosk.getBoolean("setup_radio_migrated", false))
    }

    /** What Vitro kept from Home Assistant's pages goes too, also when Vitro is off by now. */
    @Test fun theResetClearsWhatVitroKeptFromHomeAssistantsPages() {
        val ha = "http://192.0.2.10:8123"
        val storage = dev.glassworks.web.HaPageStorage.of(app)
        assertTrue(storage.put("$ha/lovelace/0", """{"thc_snooze":"{\"h_1\":99}"}"""))
        val island = app.getSharedPreferences("vitro_island", android.content.Context.MODE_PRIVATE)
        assertTrue(island.edit().putInt("port", 40123).commit())
        val frontend = java.io.File(app.cacheDir, "vitro-frontend/2026.11.0").apply { mkdirs() }
        java.io.File(frontend, "en.json").writeText("{}")
        val fonts = java.io.File(app.cacheDir, "vitro-fonts").apply { mkdirs() }
        java.io.File(fonts, "0123abcd.font").writeBytes(byteArrayOf(0, 1, 0, 0))

        assertTrue(S1Settings.clearSettings(prefs, app))

        assertNull("the copy of HA's localStorage goes", storage.items(ha))
        assertTrue(app.getSharedPreferences("ha_page_storage", android.content.Context.MODE_PRIVATE).all.isEmpty())
        assertTrue("the islands get a new origin, with empty storage", island.all.isEmpty())
        assertFalse("HA's cached frontend strings go", java.io.File(app.cacheDir, "vitro-frontend").exists())
        assertFalse("the fonts fetched from that Home Assistant go", fonts.exists())
        assertTrue("the rest of the settings step still ran", prefs.isAutoUrl)
        dev.glassworks.web.HaPageStorage.closed = false // closed until the process ends: the next test starts open
    }

    @Test fun theSealedSecretsTheResetKeepsAreTheIdentityOnly() {
        for (k in SecretFiles.SECRETS.getValue("kiosk")) assertEquals(k, k == "api_key", KioskPrefs.keptByReset(k))
    }

    private class Steps(
        var signedOut: Boolean = true,
        var cleared: Boolean = true,
        var browsers: Boolean? = true,
        val inBackground: (() -> Unit) -> Unit = { it() },
    ) {
        val did = ArrayList<String>()
        var answer: String? = "not answered"
        val reset = FactoryReset(
            signOut = { done -> did += "sign out"; done(signedOut) },
            clearSettings = { did += "clear"; cleared },
            signOutBrowsers = { did += "browsers"; browsers },
            restart = { did += "restart" },
            onMain = { it() },
            inBackground = inBackground,
        )
        fun run() = reset.run { answer = it; did += "answered" }
    }

    @Test fun itSignsOutThenSignsBrowsersOutThenClearsThenRestarts() {
        val s = Steps().apply { run() }
        assertEquals(listOf("sign out", "browsers", "clear", "answered", "restart"), s.did)
        assertNull(s.answer)
    }

    /** The settings step writes files and waits for queued page-storage writes: off the main thread, and the answer waits. */
    @Test fun theSettingsAreClearedOffTheMainThreadAndTheAnswerWaitsForThem() {
        val queued = ArrayList<() -> Unit>()
        val s = Steps(inBackground = { queued += it }).apply { run() }
        assertEquals("nothing cleared, answered or restarted on the main thread", listOf("sign out", "browsers"), s.did)
        assertEquals("not answered", s.answer)
        queued.single()()
        assertEquals(listOf("sign out", "browsers", "clear", "answered", "restart"), s.did)
        assertNull(s.answer)
    }

    @Test fun aSettingsStepThatThrowsIsASettingsStepThatFailed() {
        val did = ArrayList<String>()
        var answer: String? = "not answered"
        FactoryReset(
            signOut = { done -> done(true) }, clearSettings = { error("disk") }, signOutBrowsers = { true },
            restart = { did += "restart" }, onMain = { it() },
        ).run { answer = it }
        assertEquals(FactoryReset.NOT_CLEARED, answer)
        assertTrue("no restart", did.isEmpty())
    }

    @Test fun aSignInThatStaysStopsTheResetBeforeAnySettingGoes() {
        val s = Steps(signedOut = false).apply { run() }
        assertEquals(listOf("sign out", "answered"), s.did)
        assertEquals(FactoryReset.SIGN_IN_STAYED, s.answer)
    }

    @Test fun settingsThatCantBeClearedMeanNoRestart() {
        val s = Steps(cleared = false).apply { run() }
        assertEquals(listOf("sign out", "browsers", "clear", "answered"), s.did)
        assertEquals(FactoryReset.NOT_CLEARED, s.answer)
    }

    /** A sign-out that couldn't be saved would bring the browsers back after the restart (CodeRabbit on #682). */
    @Test fun browsersThatCantBeSignedOutForGoodStopTheResetBeforeTheSettings() {
        val s = Steps(browsers = false).apply { run() }
        assertEquals(listOf("sign out", "browsers", "answered"), s.did)
        assertEquals(FactoryReset.BROWSERS_STAYED, s.answer)
    }

    @Test fun withoutAManagePageTheResetGoesOn() {
        val s = Steps(browsers = null).apply { run() }
        assertEquals(listOf("sign out", "browsers", "clear", "answered", "restart"), s.did)
        assertNull(s.answer)
    }

    private fun security(reset: ((done: (String?) -> Unit) -> Unit)? = null, closed: () -> Unit = {}) = CoreSettings(
        prefs, "1.0.0", status = { "" }, deviceSettings = null,
        licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {},
        live = CoreSettings.Live(closeSetup = closed, factoryReset = reset), hardware = MenuFixture.S1_HARDWARE,
    ).sections().getValue(SettingsPlace.SECURITY)

    private fun actions(s: dev.glassworks.pane.SettingsSection) = s.items.filterIsInstance<SettingsItem.Action>().associateBy { it.title }

    @Test fun theResetRowAsksWhatGoesAndWhatStaysAndNeedsTheCode() {
        var ran = 0
        val row = actions(security(reset = { d -> ran++; d(null) })).getValue(FactoryReset.TITLE)
        assertTrue(row.needsCode)
        val ask = row.confirm!!
        assertEquals("Reset the panel?", ask.title)
        for (gone in listOf("every setting of the app and its panes", "setup's answers", "Home Assistant sign-in", "every browser signed in to the Manage page"))
            assertTrue(gone, ask.detail.contains(gone))
        for (kept in listOf("Wi-Fi", "the installed app", "the Log", "the radio's settings", "the panel in Home Assistant"))
            assertTrue(kept, ask.detail.contains(kept))
        row.run {}
        assertEquals(1, ran)
        assertFalse("no reset given, no row", actions(security()).containsKey(FactoryReset.TITLE))
    }

    @Test fun closeShowsOnlyWhileReArmedAndClosesPairingAndTheBrowsers() {
        prefs.haSeen = true
        var closed = 0
        assertFalse("nothing to close", actions(security { closed++ }).containsKey(CoreSettings.CLOSE_SETUP))
        val allow = actions(security { closed++ }).getValue(CoreSettings.SETUP_OVER_NETWORK)
        assertTrue(allow.needsCode)
        allow.run {}
        assertTrue(prefs.lanPairingOpen)
        val close = actions(security { closed++ }).getValue(CoreSettings.CLOSE_SETUP)
        assertTrue(close.needsCode)
        assertTrue(close.detail(), close.detail().startsWith("Open until "))
        var answer: String? = "not answered"
        close.run { answer = it }
        assertNull(answer)
        assertFalse("pairing is closed", prefs.lanPairingOpen)
        assertEquals("the paired browsers and the hotspot end", 1, closed)
        assertFalse(actions(security { closed++ }).containsKey(CoreSettings.CLOSE_SETUP))
    }
}
