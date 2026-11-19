/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-19 panel run: "one sign-in, wherever they start, covers every panel's Manage page and every panel's All
 * panels". A handoff's session gets this panel's own long-lived HA token, so All panels there needs no second login;
 * a tab's own sign-in (Continue) is kept by its panel for the session. Each panel still asks its own HA who the user is.
 */
class ManageOneSignInEverywhereTest {
    private val f = ManageFixture()
    private val handed = "panel-a-access-token-0123456789abcdef"

    private fun handoff() = f.Browser(fetchMetadata = false).also { b ->
        assertEquals(200, b.post(ManageServer.HANDOFF, mapOf("token" to handed, "next" to "/"), mapOf("origin" to "http://192.0.2.41:8099")).status)
    }
    private fun sessionId() = f.sessions.list(f.haBase).last().id
    private fun access(b: ManageFixture.Browser, csrf: String = b.csrf()) =
        b.post(ManageServer.HA_ACCESS, mapOf("csrf" to csrf), mapOf("origin" to "http://${b.host}"))
    private fun keep(b: ManageFixture.Browser, refresh: String = "TAB-REFRESH", csrf: String? = b.csrf(), origin: String = "http://${b.host}") =
        b.post(ManageServer.HA_KEEP, (csrf?.let { mapOf("csrf" to it) } ?: emptyMap()) + mapOf("refresh" to refresh), mapOf("origin" to origin))

    @Test fun aHandoffGivesThisPanelsAllPanelsItsOwnSignIn() {
        val b = handoff()
        assertEquals("made with the handed token, for a day", 1, f.madeLongLived.size)
        val (with, name, days) = f.madeLongLived.single()
        assertEquals(handed, with)
        assertEquals(1, days)
        assertTrue("named after this panel and the browser: $name", name.startsWith("Glassworks Manage on ${b.host} ("))
        assertTrue(f.server.keepsHaSignIn(sessionId()))
        assertTrue("All panels here needs no second login", b.get(ManagePages.PANELS).text().contains("data-ha-signin=\"1\""))
        val r = access(b)
        assertEquals(200, r.status)
        assertEquals("LONG-LIVED", JSONObject(r.text()).getString("access_token"))
        assertTrue("used as it is: no renewal at HA", f.refreshed.isEmpty())
    }

    @Test fun signingOutDeletesItInHomeAssistant() {
        val b = handoff()
        b.post("/manage/signout", mapOf("csrf" to b.csrf()))
        assertEquals(listOf("LONG-LIVED"), f.endedLongLived)
        assertTrue("not a refresh token: nothing to revoke there", f.revoked.isEmpty())
    }

    @Test fun allPanelsSignOutDeletesItTooAndTheSessionStays() {
        val b = handoff()
        assertEquals(200, b.post(ManageServer.HA_FORGET, mapOf("csrf" to b.csrf())).status)
        assertEquals(listOf("LONG-LIVED"), f.endedLongLived)
        assertEquals(404, access(b).status)
        assertEquals(200, b.get("/").status)
    }

    @Test fun itsUsedOnlyUntilALittleBeforeHaLetsItRunOut() {
        val b = handoff()
        val csrf = b.csrf()
        f.monoMs += ManageServer.LONG_LIVED_USE_MS
        assertEquals(404, access(b, csrf).status)
        assertEquals("ended in HA, not left to run out", listOf("LONG-LIVED"), f.endedLongLived)
    }

    @Test fun itNeverGoesToAnOldAddress() {
        val b = handoff()
        f.haBase = "http://192.0.2.11:8123"
        f.server.sweep()
        assertTrue(f.endedLongLived.isEmpty())
        assertTrue(f.events.any { it.contains("address changed") })
        assertFalse(b.get("/").text().contains("LONG-LIVED"))
    }

    @Test fun whenHaMakesNoneAllPanelsAsksForContinueAsBefore() {
        f.longLivedAnswer = null
        val b = handoff()
        assertFalse(f.server.keepsHaSignIn(sessionId()))
        assertTrue(b.get(ManagePages.PANELS).text().contains("data-ha-signin=\"\""))
    }

    @Test fun aNonAdministratorsHandoffMakesNothing() {
        f.user = HaUser("Sam", isAdmin = false)
        assertEquals(403, f.Browser(fetchMetadata = false).post(ManageServer.HANDOFF, mapOf("token" to handed, "next" to "/"),
            mapOf("origin" to "http://192.0.2.41:8099")).status)
        assertTrue(f.madeLongLived.isEmpty())
    }

    @Test fun aTabsOwnSignInIsKeptByItsPanelForTheSession() {
        val b = f.Browser()
        b.cookies[ManageServer.SESSION] = f.sessions.create(null, SignedInWith.PANEL_CODE, "Safari on Mac", false, f.haBase).first
        val r = keep(b)
        assertEquals(200, r.status)
        assertEquals("HA renewed it first", listOf("TAB-REFRESH"), f.refreshed)
        assertTrue("and named an administrator", f.userChecks.isNotEmpty())
        assertTrue(f.server.keepsHaSignIn(sessionId()))
        assertTrue(b.get(ManagePages.PANELS).text().contains("data-ha-signin=\"1\""))
        assertEquals("another sign-in, once one is kept: revoke yours", true, JSONObject(keep(b, refresh = "ANOTHER").text()).optBoolean("already"))
        b.post("/manage/signout", mapOf("csrf" to b.csrf()))
        assertEquals("sign-out revokes it as any kept sign-in", listOf("TAB-REFRESH"), f.revoked)
    }

    @Test fun aSecondTabThatLosesTheRaceIsToldItsNotKept() {
        val b = f.Browser()
        b.cookies[ManageServer.SESSION] = f.sessions.create(null, SignedInWith.PANEL_CODE, "Safari on Mac", false, f.haBase).first
        val csrf = b.csrf()
        // The other tab's keep lands while this one waits on Home Assistant.
        f.duringRefresh = { f.duringRefresh = null; keep(b, refresh = "OTHER-TAB-REFRESH", csrf = csrf) }
        val r = keep(b, csrf = csrf)
        assertEquals(200, r.status)
        assertTrue("this tab's token isn't the kept one: it revokes its own", JSONObject(r.text()).optBoolean("already"))
    }

    @Test fun theSameSignInSentTwiceIsntRevoked() {
        val b = f.Browser()
        b.cookies[ManageServer.SESSION] = f.sessions.create(null, SignedInWith.PANEL_CODE, "Safari on Mac", false, f.haBase).first
        val csrf = b.csrf()
        f.duringRefresh = { f.duringRefresh = null; keep(b, csrf = csrf) }
        val r = keep(b, csrf = csrf)
        assertFalse("the kept one is this very sign-in: the tab must not revoke it", JSONObject(r.text()).optBoolean("already"))
        assertFalse(JSONObject(keep(b, csrf = csrf).text()).optBoolean("already"))
    }

    @Test fun onlyThisPanelsOwnSignedInPageCanHandItOver() {
        val b = f.Browser()
        b.cookies[ManageServer.SESSION] = f.sessions.create(null, SignedInWith.PANEL_CODE, "Safari on Mac", false, f.haBase).first
        assertEquals("without the form token", 403, keep(b, csrf = null).status)
        assertEquals("from another site", 403, keep(b, origin = "http://evil.example").status)
        assertEquals("signed out", 401, f.Browser().post(ManageServer.HA_KEEP, mapOf("refresh" to "TAB-REFRESH")).status)
        assertTrue(f.refreshed.isEmpty())
        f.user = HaUser("Sam", isAdmin = false)
        assertEquals("administrators only", 403, keep(b).status)
        assertFalse(f.server.keepsHaSignIn(sessionId()))
    }

    @Test fun noTokenInTheLog() {
        handoff()
        val b = f.Browser()
        b.cookies[ManageServer.SESSION] = f.sessions.create(null, SignedInWith.PANEL_CODE, "Safari on Mac", false, f.haBase).first
        keep(b)
        for (secret in listOf(handed, "LONG-LIVED", "TAB-REFRESH")) {
            assertTrue(secret, f.events.none { secret in it })
            assertTrue(secret, f.warnings.none { secret in it })
        }
    }
}
