/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.frame.GenericAndroid
import dev.glassworks.pane.SettingsSection
import dev.glassworks.update.UpdateChannel
import dev.glassworks.update.UpdateChannelRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #605: the Manage page changes the update channel as the panel does, asking first before Beta. */
class UpdateChannelManageTest {
    private var channel = UpdateChannel.STABLE
    private var changed = 0
    private val f = ManageFixture()

    init {
        val device = SettingsSection(GenericAndroid.DEVICE, "Device", listOf(UpdateChannelRow.item({ channel }, { channel = it }) { changed++ }))
        f.statusFn = { f.status.copy(sections = listOf(device)) }
    }

    private fun form(b: ManageFixture.Browser, value: String, vararg more: Pair<String, String>) =
        mapOf("csrf" to b.csrf(), "section" to GenericAndroid.DEVICE, "key" to UpdateChannelRow.TITLE, "value" to value) + more

    @Test fun theDevicePageShowsTheRow() {
        val b = f.Browser().also { it.signInWithHa() }
        val page = b.get("/${GenericAndroid.DEVICE}").text()
        assertTrue(page.contains(UpdateChannelRow.TITLE))
        assertTrue(page.contains(">Stable<"))
    }

    @Test fun betaIsAskedFirstThenSavedAndTheCheckPicksAgain() {
        val b = f.Browser().also { it.signInWithHa() }
        val ask = b.post("/settings", form(b, "beta"))
        assertEquals(200, ask.status)
        assertTrue(ask.text().contains("Get beta versions?") && ask.text().contains(">Use Beta</button>"))
        assertEquals("asked, not done", UpdateChannel.STABLE, channel)
        assertEquals(303, b.post("/settings", form(b, "beta", "answer" to ask.answer()!!)).status)
        assertEquals(UpdateChannel.BETA, channel)
        assertEquals(1, changed)
        assertTrue(f.events.any { it.contains("Update channel") })
        assertTrue("the page shows Beta", b.get("/${GenericAndroid.DEVICE}").text().contains(UpdateChannelRow.BETA_LINE))

        val back = b.post("/settings", form(b, "stable"))
        assertEquals("back to Stable asks too", 200, back.status)
        assertTrue(back.text().contains("Back to Stable?") && back.text().contains("stable version newer than it"))
        assertEquals(UpdateChannel.BETA, channel)
        assertEquals(303, b.post("/settings", form(b, "stable", "answer" to back.answer()!!)).status)
        assertEquals(UpdateChannel.STABLE, channel)
        assertEquals(2, changed)
    }

    @Test fun anythingElseIsRefused() {
        val b = f.Browser().also { it.signInWithHa() }
        assertEquals(422, b.post("/settings", form(b, "nightly")).status)
        assertEquals(UpdateChannel.STABLE, channel)
        assertEquals(0, changed)
    }
}
