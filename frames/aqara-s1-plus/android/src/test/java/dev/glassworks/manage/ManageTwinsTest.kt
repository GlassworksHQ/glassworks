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
    private var role = "thread_router"
    private var wipes = 0
    private var otherResets = 0
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
                SettingsItem.Choice("Role", { role }, { listOf("zigbee_tcp" to "Zigbee coordinator", "thread" to "Thread border router", "thread_router" to "Thread repeater") },
                    confirm = { v -> if (v == role) null else SettingsItem.Confirm("Switch to Zigbee coordinator?", "The panel stops repeating for your Thread network.", "Switch") }) { v, d -> role = v; d(null) },
                SettingsItem.Action("Thread network", { "Joined" }, "Reset",
                    confirm = SettingsItem.Confirm("Reset the Thread network?", "Thread devices drop off.", "Reset")) { d -> resets++; d(null) },
                SettingsItem.Action("Factory reset", { "" }, "Reset",
                    confirm = SettingsItem.Confirm("Reset the panel?", "Everything goes.", "Reset")) { d -> wipes++; d(null) },
            )
        }
        val tz = SettingsSection("time-zone", "Time zone", listOf(
            SettingsItem.Native("tz", "The zone, from a list", twin = twin) { error("the panel's view isn't drawn here") },
        ))
        val radio = SettingsSection("radio", "Radio", listOf(SettingsItem.Native("radio", "The roles") { error("not here") }))
        // Another page with an action of the same name, for an answer's section.
        val other = SettingsSection("other", "Other", listOf(SettingsItem.Native("other", "Other", twin = {
            listOf(SettingsItem.Action("Thread network", { "" }, "Reset", confirm = SettingsItem.Confirm("Reset it here?", "", "Reset")) { d -> otherResets++; d(null) })
        }) { error("not here") }))
        f.statusFn = { f.status.copy(panes = listOf(paneRow("time-zone", tz), paneRow("radio", radio), paneRow("other", other)), sections = listOf(tz, radio, other)) }
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
        val none = signedIn().get("/radio").text()
        assertFalse("#518: no generic row for a native view without a twin", none.contains(ManagePages.ON_THE_PANEL) || none.contains("own settings"))
    }

    /** #518: what a link opens on the panel (the notifications there), read-only under its title; another app says "On the panel". */
    @Test fun aLinksTwinDrawsUnderItsTitleAndAnotherAppIsOnThePanel() {
        val listed = SettingsSection("notes", "Notes", listOf(
            SettingsItem.Link("Notifications on the panel", "1 · open the list", twin = { listOf(SettingsItem.Info({ "Door" }, { "Someone is there · now" })) }) {},
            SettingsItem.Link("Licences", "") {},
        ))
        f.statusFn = { f.status.copy(panes = listOf(paneRow("notes", listed)), sections = listOf(listed)) }
        val page = signedIn().get("/notes").text()
        assertTrue(page.contains("""<div class="grp"><div class="gl">Notifications on the panel</div><div class="row static"><div><b>Door</b><span>Someone is there · now</span></div></div></div>"""))
        assertFalse("the panel's own words for the link stay there", page.contains("open the list"))
        assertTrue(page.contains("<b>Licences</b></div><em>${ManagePages.ON_THE_PANEL}</em>"))
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

    /** A choice that asks first on the panel (the radio's role) asks here too; the one it has asks nothing. */
    @Test fun aChoiceThatAsksOnThePanelAsksHereFirst() {
        val b = signedIn()
        val ask = b.post("/settings", form(b, "Role", "value" to "zigbee_tcp"))
        assertEquals(200, ask.status)
        val dlg = ask.text().substringAfter("class=\"dlg\"")
        assertTrue(dlg.contains("Switch to Zigbee coordinator?") && dlg.contains("name=\"value\" value=\"zigbee_tcp\"") && ask.answer() != null)
        assertEquals("asked, not done", "thread_router", role)
        assertEquals(0, f.onMainCalls)
        assertEquals("confirmed=1 alone is no answer", 200, b.post("/settings", form(b, "Role", "value" to "zigbee_tcp", "confirmed" to "1")).status)
        assertEquals("thread_router", role)
        assertEquals(303, b.post("/settings", form(b, "Role", "value" to "zigbee_tcp", "answer" to ask.answer()!!)).status)
        assertEquals("zigbee_tcp", role)
        assertEquals("the role it has: no question, saved at once", 303, b.post("/settings", form(b, "Role", "value" to "zigbee_tcp")).status)
    }

    private fun asked(b: ManageFixture.Browser, section: String, key: String, value: String) =
        b.post("/settings", mapOf("csrf" to b.csrf(), "section" to section, "key" to key, "value" to value)).answer()!!

    private fun answering(b: ManageFixture.Browser, section: String, key: String, value: String, answer: String) =
        b.post("/settings", mapOf("csrf" to b.csrf(), "section" to section, "key" to key, "value" to value, "answer" to answer)).status

    /** #269 delta verifier: an answer is for its own page, item and value, not just its session. */
    @Test fun anAnswerIsForItsOwnSectionKeyAndValue() {
        val b = signedIn()
        assertEquals("another page's item of the same name asks again", 200,
            answering(b, "other", "Thread network", "run", asked(b, "time-zone", "Thread network", "run")))
        assertEquals(0, otherResets)
        assertEquals("another item on the page asks again", 200,
            answering(b, "time-zone", "Factory reset", "run", asked(b, "time-zone", "Thread network", "run")))
        assertEquals(0, wipes)
        assertEquals("another value asks again", 200,
            answering(b, "time-zone", "Role", "thread", asked(b, "time-zone", "Role", "zigbee_tcp")))
        assertEquals("thread_router", role)
        assertEquals("its own change runs", 303, answering(b, "time-zone", "Role", "thread", asked(b, "time-zone", "Role", "thread")))
        assertEquals("thread", role)
        assertEquals(0, resets + wipes + otherResets)
    }

    /** #269 delta verifier: an answer is good for five minutes on the server's monotonic clock. */
    @Test fun anAnswerRunsOutAfterFiveMinutes() {
        val b = signedIn()
        val late = asked(b, "time-zone", "Thread network", "run")
        f.monoMs += 5 * 60_000 + 1
        assertEquals("ran out: asks again", 200, answering(b, "time-zone", "Thread network", "run", late))
        assertEquals(0, resets)
        val inTime = asked(b, "time-zone", "Thread network", "run")
        f.monoMs += 4 * 60_000
        assertEquals(303, answering(b, "time-zone", "Thread network", "run", inTime))
        assertEquals(1, resets)
    }

    /** #269 delta verifier: signing out drops the session's open questions. */
    @Test fun signingOutForgetsTheSessionsQuestions() {
        val b = signedIn()
        asked(b, "time-zone", "Thread network", "run")
        asked(b, "time-zone", "Role", "zigbee_tcp")
        val id = f.sessions.list(f.haBase).single().id
        assertEquals(2, f.server.questionsWaiting(id))
        assertEquals(303, b.post("/manage/signout", mapOf("csrf" to b.csrf())).status)
        assertEquals(0, f.server.questionsWaiting(id))
    }

    /** All panels' Sign out ends only the Home Assistant sign-in: the Manage session's questions stay (CodeRabbit on #534). */
    @Test fun endingAllPanelsSignInKeepsTheSessionsQuestions() {
        val b = signedIn()
        val waiting = asked(b, "time-zone", "Thread network", "run")
        val id = f.sessions.list(f.haBase).single().id
        f.revokeWorks = false
        assertEquals(502, b.post(ManageServer.HA_FORGET, mapOf("csrf" to b.csrf())).status)
        f.server.sweep()
        assertEquals("through a failed revoke and the sweep's retry", 1, f.server.questionsWaiting(id))
        assertEquals(303, answering(b, "time-zone", "Thread network", "run", waiting))
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
        assertTrue(dlg.contains("name=\"value\" value=\"run\"") && ask.answer() != null)
        assertEquals("asked, not done", 0, resets)
        assertEquals("a padded run asks too", 200, b.post("/settings", form(b, "Thread network", "value" to " run ")).status)
        assertEquals(0, resets)
        assertEquals("confirmed=1 alone is no answer", 200, b.post("/settings", form(b, "Thread network", "value" to "run", "confirmed" to "1")).status)
        assertEquals(0, resets)
        assertEquals(303, b.post("/settings", form(b, "Thread network", "value" to "run", "answer" to ask.answer()!!)).status)
        assertEquals(1, resets)
    }
}
