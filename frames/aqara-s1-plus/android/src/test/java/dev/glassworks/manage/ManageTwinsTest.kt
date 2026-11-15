/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #269: a native view's typed twin on the Manage page. Its rows draw where "On the panel" was, a long choice
 * is one grouped list, and an action is a button that asks first when the panel does, all through the
 * same POST, CSRF token and item saves as the rest of the page.
 */
class ManageTwinsTest {
    private var zone = "Europe/Madrid"
    private var used = 0
    private var resets = 0
    private var failing = false
    private val f = ManageFixture()

    private val zones = listOf("Europe/Madrid" to "Madrid", "Europe/London" to "London", "America/New_York" to "New York", "UTC" to "UTC")

    init {
        val twin = {
            listOf(
                SettingsItem.Choice("Time zone", { zone }, { zones }, group = { it.substringBefore('/', "UTC") }, line = { "$it · now" }) { v, d -> zone = v; d(null) },
                SettingsItem.Info({ "Kept by the panel" }, { "The panel puts it back." }),
                SettingsItem.Action("Home Assistant's time zone", { "UTC+00:00 · like Europe/London" }, "Use it") { d ->
                    if (failing) d("The root helper didn't answer.") else { used++; zone = "Europe/London"; d(null) }
                },
                SettingsItem.Action("Thread network", { "Joined" }, "Reset",
                    confirm = SettingsItem.Confirm("Reset the Thread network?", "Thread devices drop off.", "Reset")) { d -> resets++; d(null) },
            )
        }
        val tz = SettingsSection("time-zone", "Time zone", listOf(
            SettingsItem.Native("tz", "The zone, from a list", twin = twin) { error("the panel's view isn't drawn here") },
        ))
        val radio = SettingsSection("radio", "Radio", listOf(SettingsItem.Native("radio", "The roles") { error("not here") }))
        f.statusFn = { f.status.copy(panes = listOf(paneRow("time-zone", tz), paneRow("radio", radio)), sections = listOf(tz, radio)) }
    }

    private fun signedIn() = f.Browser().also { it.signInWithHa() }

    private fun form(b: ManageFixture.Browser, key: String, vararg more: Pair<String, String>) =
        mapOf("csrf" to b.csrf(), "section" to "time-zone", "key" to key) + more

    @Test fun aTwinDrawsItsRowsWhereOnThePanelWas() {
        val page = signedIn().get("/time-zone").text()
        assertFalse(page.contains(ManagePages.ON_THE_PANEL))
        assertTrue("the choice's line, as an edit link", page.contains("Europe/Madrid · now") && page.contains("/time-zone?edit=Time+zone\""))
        assertTrue(page.contains("Kept by the panel") && page.contains("The panel puts it back."))
        assertTrue("the action is a form with its button", page.contains("name=\"key\" value=\"Home Assistant's time zone\"") &&
            page.contains("name=\"value\" value=\"run\">Use it</button>"))
        assertTrue("a native view with no twin still says so", signedIn().get("/radio").text().contains(ManagePages.ON_THE_PANEL))
    }

    @Test fun aLongChoiceIsOneGroupedListWithTheZoneSelected() {
        val dlg = signedIn().get("/time-zone?edit=Time+zone").text().substringAfter("class=\"dlg\"")
        assertTrue(dlg.contains("<select class=\"fld\" name=\"value\""))
        assertTrue(dlg.contains("<optgroup label=\"Europe\"><option value=\"Europe/Madrid\" selected>Madrid</option><option value=\"Europe/London\">London</option></optgroup>"))
        assertTrue(dlg.contains("<optgroup label=\"America\">") && dlg.contains("<optgroup label=\"UTC\">"))
        assertFalse("a zone is picked: no Pick one", dlg.contains(ManagePages.PICK_ONE))
        assertTrue("Save, not chips", dlg.contains(">Save</button>") && !dlg.contains("class=\"pc"))
        zone = "Asia/Nowhere"
        assertTrue("none picked: the first line says to pick, so Save can't send Africa by accident",
            signedIn().get("/time-zone?edit=Time+zone").text().contains("<option value=\"\" disabled selected>${ManagePages.PICK_ONE}</option>"))
    }

    @Test fun aPickedZoneSavesAndOneNotInTheListIsRefused() {
        val b = signedIn()
        assertEquals(303, b.post("/settings", form(b, "Time zone", "value" to "America/New_York")).status)
        assertEquals("America/New_York", zone)
        val r = b.post("/settings", form(b, "Time zone", "value" to "Mars/Olympus"))
        assertEquals(422, r.status)
        assertTrue("the list stays open with why", r.text().contains("class=\"dlg\"") && r.text().contains(SettingsChange.PICK))
        assertEquals("America/New_York", zone)
        assertEquals(403, b.post("/settings", mapOf("section" to "time-zone", "key" to "Time zone", "value" to "UTC")).status)
        assertEquals("America/New_York", zone)
    }

    @Test fun anActionRunsOnTheMainThreadAndOnlyWithRun() {
        val b = signedIn()
        assertEquals(403, b.post("/settings", mapOf("section" to "time-zone", "key" to "Home Assistant's time zone", "value" to "run")).status)
        assertEquals(0, used)
        val r = b.post("/settings", form(b, "Home Assistant's time zone", "value" to "run"))
        assertEquals(303, r.status)
        assertEquals("/time-zone", r.headers["Location"])
        assertEquals(1, used)
        assertEquals("Europe/London", zone)
        assertEquals(1, f.onMainCalls)
        assertTrue(f.events.toString(), f.events.any { it == "Alex ran Time zone › Home Assistant's time zone" })
        assertEquals(422, b.post("/settings", form(b, "Home Assistant's time zone", "value" to "on")).status)
        assertEquals(1, used)
    }

    @Test fun aFailedActionSaysWhyOnThePage() {
        failing = true
        val b = signedIn()
        val r = b.post("/settings", form(b, "Home Assistant's time zone", "value" to "run"))
        assertEquals(422, r.status)
        assertTrue(r.text().contains("class=\"err\" role=\"alert\">The root helper didn't answer.</p>"))
        assertFalse("no dialog for an action", r.text().contains("class=\"dlg\""))
        assertTrue(f.events.any { it.startsWith("Alex couldn't run Time zone › Home Assistant's time zone") })
    }

    /** Either sign-in (#288); one that costs something asks first, as on the panel. */
    @Test fun anActionThatAsksOnThePanelAsksHereFirstForACodeSignInToo() {
        val b = f.Browser()
        b.post("/manage/code", mapOf("next" to "/"))
        b.post("/manage/code", mapOf("next" to "/", "code" to f.shown.last()!!.code))
        val ask = b.post("/settings", form(b, "Thread network", "value" to "run"))
        assertEquals(200, ask.status)
        val dlg = ask.text().substringAfter("class=\"dlg\"")
        assertTrue(dlg.contains("Reset the Thread network?") && dlg.contains("Thread devices drop off.") && dlg.contains(">Reset</button>"))
        assertTrue(dlg.contains("name=\"value\" value=\"run\"") && dlg.contains("name=\"confirmed\" value=\"1\""))
        assertEquals("asked, not done", 0, resets)
        assertEquals(303, b.post("/settings", form(b, "Thread network", "value" to "run", "confirmed" to "1")).status)
        assertEquals(1, resets)
    }
}
