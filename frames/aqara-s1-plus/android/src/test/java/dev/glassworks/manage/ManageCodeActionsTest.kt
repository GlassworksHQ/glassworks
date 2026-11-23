/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Manage page's actions that need someone at the panel (#33): setup over the network and the factory reset run
 * only with the code the panel shows then, and the reset also only after its question, as on the panel.
 */
class ManageCodeActionsTest {
    private val runs = ArrayList<String>()
    private val allow = SettingsItem.Action("Allow setup over the network", { "" }, "Allow", needsCode = true) { d -> runs += "allow"; d(null) }
    private val reset = SettingsItem.Action("Reset the panel", { "" }, "Reset",
        confirm = SettingsItem.Confirm("Reset the panel?", "This clears every setting. It keeps Wi-Fi.", "Reset the panel"), needsCode = true) { d -> runs += "reset"; d(null) }
    private val plain = SettingsItem.Action("Check the radio", { "" }, "Check") { d -> runs += "plain"; d(null) }
    private val section = SettingsSection(ManagePages.SECURITY, "Security", listOf(allow, reset, plain))

    private val f = ManageFixture().also { f -> f.statusFn = { f.status.copy(sections = f.status.sections + section) } }
    private val b = f.Browser().also { it.signInWithHa() }
    private val csrf = b.csrf()

    private fun key(item: SettingsItem) = SettingsChange.changeable(section).first { it.second === item }.first
    private fun form(item: SettingsItem, extra: Map<String, String> = emptyMap()) =
        mapOf("csrf" to csrf, "section" to section.id, "key" to key(item), "value" to SettingsItem.Action.RUN) + extra
    private fun wrong(code: String) = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    @Test fun anActionThatNeedsTheCodeRunsOnlyWithTheCodeThePanelShows() {
        val asked = b.post("/settings", form(allow))
        assertEquals(200, asked.status)
        assertTrue("the code dialog", asked.text().contains("Enter the code shown on the panel"))
        assertTrue("it says what for", asked.text().contains("Allow setup over the network requires someone at the panel."))
        val shown = f.shown.last()!!
        assertEquals("the panel names the action", "Allow setup over the network", shown.label)
        assertEquals("nothing ran yet", emptyList<String>(), runs)

        val answer = asked.answer()!!
        val bad = b.post("/settings", form(allow, mapOf("answer" to answer, "code" to wrong(shown.code))))
        assertEquals(401, bad.status)
        assertTrue(bad.text().contains("That's not the code. 4 tries left."))
        assertEquals(emptyList<String>(), runs)

        val ok = b.post("/settings", form(allow, mapOf("answer" to bad.answer()!!, "code" to shown.code)))
        assertEquals(303, ok.status)
        assertEquals("/security", ok.headers["Location"])
        assertEquals(listOf("allow"), runs)
        assertNull("the panel hides the code once used", f.shown.last())
    }

    @Test fun theRightCodeWithoutTheDialogsAnswerRunsNothing() {
        b.post("/settings", form(allow))
        val code = f.shown.last()!!.code
        assertEquals("no answer: the dialog again", 200, b.post("/settings", form(allow, mapOf("code" to code))).status)
        assertEquals("a made-up answer: the dialog again", 200, b.post("/settings", form(allow, mapOf("answer" to "forged", "code" to code))).status)
        assertEquals(emptyList<String>(), runs)
    }

    @Test fun aCodeForAnotherActionOrForSigningInDoesntRunThisOne() {
        val forAllow = b.post("/settings", form(allow)).answer()!!
        f.now += 31_000
        b.post("/settings", form(reset, mapOf("answer" to b.post("/settings", form(reset)).answer()!!)))
        val resetCode = f.shown.last()!!.code
        val refused = b.post("/settings", form(allow, mapOf("answer" to forAllow, "code" to resetCode)))
        assertEquals("the reset's code isn't Allow's", 401, refused.status)

        f.now += 31_000
        f.Browser(remote = "192.0.2.78").post("/manage/code", mapOf("next" to "/"))
        val signInCode = f.shown.last()!!.code
        assertEquals("a sign-in code isn't Allow's", 401, b.post("/settings", form(allow, mapOf("answer" to refused.answer()!!, "code" to signInCode))).status)
        assertEquals(emptyList<String>(), runs)
    }

    @Test fun theResetAsksItsQuestionFirstThenTheCode() {
        val question = b.post("/settings", form(reset))
        assertEquals(200, question.status)
        assertTrue("the question on the Security page", question.text().contains("Reset the panel?") && question.text().contains("It keeps Wi-Fi."))
        assertTrue("over the signed-in browsers", question.text().contains("Signed-in browsers"))
        assertTrue("no code yet", f.shown.isEmpty())

        val asked = b.post("/settings", form(reset, mapOf("answer" to question.answer()!!)))
        assertTrue("then the code", asked.text().contains("Enter the code shown on the panel"))
        assertTrue("its button is the reset's, in rose", asked.text().contains("""class="pill danger" type="submit">Reset the panel<"""))
        assertEquals(emptyList<String>(), runs)

        assertEquals(303, b.post("/settings", form(reset, mapOf("answer" to asked.answer()!!, "code" to f.shown.last()!!.code))).status)
        assertEquals(listOf("reset"), runs)
    }

    @Test fun whenThePanelCantShowACodeNothingRuns() {
        f.code.canDisplay = { false }
        val r = b.post("/settings", form(allow))
        assertEquals(503, r.status)
        assertTrue(r.text().contains("The panel can't show a code right now. Allow setup over the network needs it."))
        assertEquals(emptyList<String>(), runs)
        assertTrue(f.events.any { it.contains("refused, the panel can't show a code") })
    }

    @Test fun anActionWithoutTheFlagRunsAsBefore() {
        assertEquals(303, b.post("/settings", form(plain)).status)
        assertEquals(listOf("plain"), runs)
        assertTrue("no code", f.shown.isEmpty())
    }

    @Test fun theSecurityPageDrawsTheSectionsRowsAfterTheBrowsers() {
        val page = b.get("/security").text()
        val browsers = page.indexOf("Signed-in browsers")
        assertTrue(browsers > 0)
        for (t in listOf("Allow setup over the network", "Reset the panel")) assertTrue(t, page.indexOf(t) > browsers)
        val links = { p: String -> Regex("""href="/security"""").findAll(p).count() }
        val without = f.statusFn
        f.statusFn = null
        assertEquals("the nav lists Security as before, not again under Panel", links(b.get("/security").text()), links(page))
        f.statusFn = without
    }

    @Test fun signOutAllEndsEveryBrowser() {
        val other = f.Browser(remote = "192.0.2.78").also { it.signInWithHa() }
        assertEquals(2, f.sessions.list(f.haBase).size)
        assertTrue(f.server.signOutAll())
        assertTrue(f.sessions.list(f.haBase).isEmpty())
        for (x in listOf(b, other)) assertFalse("signed out", x.get("/security").text().contains("Signed-in browsers"))
    }
}
