/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #466: All panels, the one Manage page with a script, and what its CSP lets that script reach. */
class ManagePanelsPageTest {
    private val f = ManageFixture()

    @Test fun signedInItLoadsItsScriptWithThisPanelsHomeAssistant() {
        val b = f.Browser().also { it.signInWithHa() }
        val r = b.get(ManagePages.PANELS)
        assertEquals(200, r.status)
        val html = r.text()
        assertTrue(html.contains("data-ha=\"http://192.0.2.10:8123\""))
        // The tab keeps its Home Assistant sign-in for this Manage session only.
        val id = f.sessions.list(f.haBase).single().id
        assertTrue(html, html.contains("data-session=\"$id\""))
        assertTrue(html.contains("<script type=\"module\" src=\"/manage/js/panels.js\"></script>"))
        val csp = r.headers["Content-Security-Policy"]!!
        assertTrue(csp, csp.contains("script-src 'self';"))
        assertTrue(csp, csp.contains("connect-src 'self' http://*:8099 http://192.0.2.10:8123 ws://192.0.2.10:8123;"))
        assertTrue(csp, csp.contains("form-action 'self' http://*:8099"))
        assertFalse("no inline script", csp.contains("unsafe-inline';") && csp.contains("script-src 'unsafe"))
    }

    @Test fun whileItsScriptStartsItSaysItIsFindingPanelsAndTheJavaScriptLineIsOnlyForNoScripts() {
        val b = f.Browser().also { it.signInWithHa() }
        val html = b.get(ManagePages.PANELS).text()
        val list = html.substringAfter("id=\"gw-list\"").substringBefore("</section>")
        assertTrue(list, list.contains(ManagePages.FINDING))
        assertFalse(list, list.contains(ManagePages.NEEDS_SCRIPT))
        val noscript = html.substringAfter("<noscript>").substringBefore("</noscript>")
        assertTrue(noscript, noscript.contains(ManagePages.NEEDS_SCRIPT))
        assertEquals("the JavaScript line is nowhere else", 1, Regex(Regex.escape(ManagePages.NEEDS_SCRIPT)).findAll(html).count())
    }

    @Test fun everyOtherPageStaysWithoutScripts() {
        val b = f.Browser().also { it.signInWithHa() }
        for (p in listOf("/", "/log", "/security", "/about", "/screen")) {
            val r = b.get(p)
            assertFalse(p, r.text().contains("<script"))
            assertFalse(p, r.headers["Content-Security-Policy"]!!.contains("script-src"))
            assertTrue(p, r.text().contains("href=\"/panels\""))
        }
    }

    @Test fun withoutAHomeAssistantOnTheNetworkItSaysSoAndLoadsNothing() {
        f.haBase = "https://example.ui.nabu.casa"
        val b = f.Browser()
        b.cookies[ManageServer.SESSION] = f.sessions.create(null, SignedInWith.PANEL_CODE, "Safari on Mac", false, f.haBase).first
        val r = b.get(ManagePages.PANELS)
        assertTrue(r.text().contains("This panel has no Home Assistant on your network"))
        assertFalse(r.text().contains("<script"))
        assertFalse(r.headers["Content-Security-Policy"]!!.contains("nabu.casa"))
    }

    @Test fun anIpv6HomeAssistantAddressIsSaidPlainlyNotOfferedToACspThatCantNameIt() {
        f.haBase = "http://[fd00::10]:8123"
        val b = f.Browser()
        b.cookies[ManageServer.SESSION] = f.sessions.create(null, SignedInWith.PANEL_CODE, "Safari on Mac", false, f.haBase).first
        val r = b.get(ManagePages.PANELS)
        assertTrue(r.text(), r.text().contains("can't use a Home Assistant address written as an IPv6 address"))
        assertFalse(r.text().contains("<script"))
        assertFalse(r.headers["Content-Security-Policy"]!!, r.headers["Content-Security-Policy"]!!.contains("fd00"))
    }

    @Test fun signedOutItAsksForASignInFirst() {
        val r = f.Browser().get(ManagePages.PANELS)
        assertFalse(r.text().contains("<script"))
        assertTrue(r.text().contains("Manage this panel"))
    }

    /** HA sends the browser back to /panels?code=…&state=… from its own site: the Strict cookie comes after one reload. */
    @Test fun homeAssistantsRedirectBackKeepsTheCodeForTheScript() {
        val b = f.Browser().also { it.signInWithHa() }
        val back = b.get("/panels?code=C0DE&state=S7ATE", mapOf("sec-fetch-site" to "cross-site", "cookie" to ""))
        assertTrue(back.text(), back.text().contains("url=/panels?code=C0DE&amp;state=S7ATE&amp;gw_reload=1"))
        val reloaded = b.get("/panels?code=C0DE&state=S7ATE&gw_reload=1")
        assertEquals(303, reloaded.status)
        assertEquals("/panels?code=C0DE&state=S7ATE", reloaded.headers["Location"])
    }

    @Test fun theScriptsAreServedAsScriptsAndNothingElseIs() {
        val anon = f.Browser()
        for (name in ManageServer.SCRIPT_FILES) {
            val r = anon.get(ManageServer.SCRIPTS + name)
            assertEquals(name, 200, r.status)
            assertEquals("text/javascript; charset=utf-8", r.headers["Content-Type"])
            assertEquals("nosniff", r.headers["X-Content-Type-Options"])
        }
        assertEquals(404, anon.get(ManageServer.SCRIPTS + "../sessions.json").status)
        assertEquals(404, anon.get(ManageServer.SCRIPTS + "other.js").status)
    }

    @Test fun aCopyWaitsLongerThanThePanelWaitsForASave() {
        // Otherwise a save that ends between the two gives up in the browser while the panel answers "done".
        val core = f.Browser().get(ManageServer.SCRIPTS + "panels-core.js").text()
        val writeMs = Regex("export const WRITE_MS = ([0-9_]+);").find(core)!!.groupValues[1].replace("_", "").toLong()
        assertTrue("WRITE_MS $writeMs, the panel's save wait ${ManageServer.SAVE_WAIT_MS}", writeMs >= ManageServer.SAVE_WAIT_MS + 5_000)
        val settingsMs = Regex("export const SETTINGS_MS = ([0-9_]+);").find(core)!!.groupValues[1].replace("_", "").toLong()
        assertTrue("reads keep their own, shorter timeout", settingsMs < writeMs)
    }

    @Test fun thePagesScriptFindsPanelsOnThePortTheCspAllows() {
        val core = f.Browser().get(ManageServer.SCRIPTS + "panels-core.js").text()
        assertTrue(core.contains("export const PORT = '${ManageServer.PORT}';"))
    }
}
