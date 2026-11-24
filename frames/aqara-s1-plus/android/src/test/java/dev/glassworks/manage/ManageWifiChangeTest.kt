/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.net.WifiChange
import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.settings.CoreSettings
import dev.glassworks.settings.WifiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Changing the panel's Wi-Fi from the Manage page (#660): Change shows the panel's code and the sheet; Join starts the
 * change only with that code, never with a made-up or stale answer; the password goes to the join alone, never back
 * into a page or the Log; and the page then says the panel is switching. Illustrative network names only.
 */
class ManageWifiChangeTest {
    private val home = WifiChange.Network("Brightside Home", 3)

    private class Radio(var on: WifiChange.Network?) : WifiChange.Radio {
        var block: WifiChange.Block? = null
        val joins = ArrayList<Pair<String, String>>()
        override fun block() = block
        override fun current() = on
        override fun isSaved(name: String) = false
        override fun nearby() = listOf("Garden Shed", "Attic")
        override fun join(name: String, password: String, done: (WifiChange.Joined) -> Unit) { joins += name to password }
        override fun cancel() {}
        override fun goBack(to: WifiChange.Network, leave: String, forget: Boolean, done: (Boolean) -> Unit) { on = to; done(true) }
        override fun reaches(done: (Boolean) -> Unit) = done(true)
        override fun armHotspot() {}
    }

    private val radio = Radio(home)
    private val timers = ArrayList<Pair<Long, () -> Unit>>()

    /** The start delay's timer: the change's own deadline stays pending. */
    private fun startDelay() = timers.filter { it.first < 60_000 }.forEach { timers.remove(it); it.second() }
    private val change = WifiChange(radio, object : WifiChange.Store {
        override fun load(): WifiChange.Pending? = null
        override fun save(p: WifiChange.Pending?) = true
    }, clock = { 0L }, schedule = { ms, run -> val t = ms to run; timers += t; { timers.remove(t) } })

    private fun section() = SettingsSection(CoreSettings.NETWORK, CoreSettings.NETWORK_TITLE, listOfNotNull(WifiSettings.item(change) { true }))

    private val f = ManageFixture().also { f -> f.statusFn = { f.status.copy(sections = f.status.sections + section()) } }
    private val b = f.Browser().also { it.signInWithHa() }
    private val csrf = b.csrf()

    private fun form(extra: Map<String, String> = emptyMap()) = mapOf("csrf" to csrf, "section" to CoreSettings.NETWORK, "key" to WifiSettings.TITLE) + extra
    private fun wrong(code: String) = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    /** Change on the row: the sheet over the page, with the panel showing a code for it. */
    private fun opened() = b.post("/settings", form())

    @Test fun theRowShowsTheNetworkAndChange() {
        val page = b.get("/network").text()
        assertTrue(page.contains("Brightside Home"))
        assertTrue("the row's button", page.contains(""">Change</button>"""))
        assertTrue("on the panel's nav", page.contains("""href="/network""""))
    }

    @Test fun changeShowsTheCodeAndTheSheetWithNearbyNetworks() {
        val r = opened()
        assertEquals(200, r.status)
        val t = r.text()
        assertTrue("the sheet", t.contains(WifiSettings.HINT.replace("'", "&#39;")) || t.contains("keeps its current network saved"))
        assertTrue("networks nearby", t.contains("""<option value="Garden Shed">Garden Shed</option>"""))
        assertTrue("the code boxes", t.contains("""name="code""""))
        assertEquals("the panel names what the code is for", ManageServer.WIFI_CODE_LABEL, f.shown.last()!!.label)
        assertTrue("nothing joined yet", radio.joins.isEmpty())
    }

    @Test fun theRightCodeStartsTheChangeAndThePageSaysThePanelIsSwitching() {
        val asked = opened()
        val code = f.shown.last()!!.code
        val r = b.post("/settings", form(mapOf("answer" to asked.answer()!!, "pick" to "Garden Shed", "password" to "correct horse", "code" to code)))
        assertEquals(303, r.status)
        assertEquals("/network?done=wifi", r.headers["Location"])
        assertTrue("under way", change.state is WifiChange.State.Joining)
        startDelay()
        assertEquals(listOf("Garden Shed" to "correct horse"), radio.joins)
        val page = b.get("/network?done=wifi").text()
        assertTrue("the notice", page.contains("this page may stop responding"))
        assertTrue("the row says where it goes and the way back", page.contains("Joining Garden Shed. If it doesn&#39;t work, the panel goes back to Brightside Home") ||
            page.contains("Joining Garden Shed. If it doesn't work, the panel goes back to Brightside Home"))
        assertFalse("no Change while it runs", page.contains(""">Change</button>"""))
        assertTrue(f.events.any { it.contains("started a Wi-Fi change") })
        assertTrue("no name or password in the Log", f.events.none { "Garden" in it || "correct horse" in it || "Brightside" in it })
        assertNull("the code is used up", f.shown.last())
    }

    @Test fun aTypedNameWinsOverThePick() {
        val asked = opened()
        b.post("/settings", form(mapOf("answer" to asked.answer()!!, "pick" to "Garden Shed", "network" to "Hidden Lab", "password" to "", "code" to f.shown.last()!!.code)))
        startDelay()
        assertEquals(listOf("Hidden Lab" to ""), radio.joins)
    }

    @Test fun aWrongCodeStartsNothingAndNeverSendsThePasswordBack() {
        val asked = opened()
        val code = f.shown.last()!!.code
        val r = b.post("/settings", form(mapOf("answer" to asked.answer()!!, "network" to "Garden Shed", "password" to "correct horse", "code" to wrong(code))))
        assertEquals(401, r.status)
        assertTrue(r.text().contains("That's not the code. 4 tries left.") || r.text().contains("That&#39;s not the code. 4 tries left."))
        assertTrue("the name stays", r.text().contains("""value="Garden Shed""""))
        assertFalse("the password never comes back", r.text().contains("correct horse"))
        assertTrue(change.state is WifiChange.State.Idle)
        assertTrue(radio.joins.isEmpty())
    }

    @Test fun theCodeWithoutTheSheetsAnswerStartsNothing() {
        opened()
        val code = f.shown.last()!!.code
        for (answer in listOf(null, "forged")) {
            val r = b.post("/settings", form(mapOfNotNull("answer" to answer, "network" to "Garden Shed", "password" to "correct horse", "code" to code)))
            assertEquals("the sheet again", 200, r.status)
        }
        assertTrue(change.state is WifiChange.State.Idle)
        assertTrue(radio.joins.isEmpty())
    }

    @Test fun aBadNameIsRefusedBeforeTheCodeIsUsed() {
        val asked = opened()
        val code = f.shown.last()!!.code
        val r = b.post("/settings", form(mapOf("answer" to asked.answer()!!, "network" to "x".repeat(33), "password" to "", "code" to code)))
        assertEquals(422, r.status)
        assertTrue(r.text().contains("at most 32 characters"))
        assertEquals("the code is still up", code, f.shown.last()!!.code)
    }

    @Test fun whenThePanelCantShowACodeNothingStarts() {
        f.code.canDisplay = { false }
        val r = opened()
        assertEquals(503, r.status)
        assertTrue(r.text().contains("Changing Wi-Fi needs it."))
        assertTrue(radio.joins.isEmpty())
    }

    @Test fun aRefusalAfterTheCodeSaysWhyAndShowsANewCode() {
        radio.on = null
        val asked = opened()
        val r = b.post("/settings", form(mapOf("answer" to asked.answer()!!, "network" to "Garden Shed", "password" to "x", "code" to f.shown.last()!!.code)))
        assertEquals(422, r.status)
        assertTrue(r.text().contains("isn&#39;t on Wi-Fi now") || r.text().contains("isn't on Wi-Fi now"))
        assertTrue("a new code for the next try", f.shown.last() != null)
    }

    @Test fun aDeviceThatCantSwitchSaysWhyAndOffersNothing() {
        radio.block = WifiChange.Block.SUGGESTIONS_ONLY
        val page = b.get("/network").text()
        assertTrue(page.contains("Change it in Android"))
        assertFalse(page.contains(""">Change</button>"""))
        assertTrue("nothing to change", SettingsChange.changeable(section()).isEmpty())
    }

    @Test fun anotherPanelsPageCantChangeIt() {
        assertTrue("not in the settings API's list", ManageApi.item(WifiSettings.TITLE, section().items.single()) == null)
    }

    private fun mapOfNotNull(vararg p: Pair<String, String?>): Map<String, String> = p.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
}
