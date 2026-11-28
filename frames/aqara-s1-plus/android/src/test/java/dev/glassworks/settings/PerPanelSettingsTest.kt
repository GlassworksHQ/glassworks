/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.settings

import android.app.Application
import dev.glassworks.KioskPrefs
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #466 panel run (2026-11-18): copying S1 → tablet carried the Home Assistant address across and signed the tablet's
 * dashboard out. The address (it is the dashboard's URL too) is the panel's own: All panels never copies it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PerPanelSettingsTest {
    @Test fun theHomeAssistantAddressIsThePanelsOwnAndNothingElseOnItsPageIs() {
        val prefs = KioskPrefs(RuntimeEnvironment.getApplication())
        val core = CoreSettings(
            prefs, "1.0.0", status = { "" }, deviceSettings = null,
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {}, hardware = MenuFixture.S1_HARDWARE,
        )
        val own = core.sections().values.flatMap { it.items }.filterIsInstance<SettingsItem.Text>().filter { it.perPanel }.map { it.title }
        assertEquals(listOf("Home Assistant"), own)
        val texts = core.sections().getValue(SettingsPlace.DASHBOARD).items.filterIsInstance<SettingsItem.Text>().map { it.title }
        assertTrue(texts.toString(), "Home Assistant" in texts)

        prefs.setHaUrl("http://192.0.2.10:8123", dev.glassworks.discovery.HaDiscovery.SOURCE_MDNS)
        val withDashes = CoreSettings(
            prefs, "1.0.0", status = { "" }, deviceSettings = null,
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {}, hardware = MenuFixture.S1_HARDWARE,
            dashboards = { listOf(dev.glassworks.dashboard.Dashboard("tablet", "Tablet")) },
        )
        val ownChoices = withDashes.sections().values.flatMap { it.items }.filterIsInstance<SettingsItem.Choice>().filter { it.perPanel }.map { it.title }
        assertEquals(listOf(CoreSettings.DASHBOARD_ROW_TITLE), ownChoices)
    }
}
