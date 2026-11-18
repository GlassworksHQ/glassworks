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
 * #466 panel run (2026-11-18): "I'm already authenticated at the panel level." A Manage sign-in with Home Assistant
 * keeps the administrator's HA sign-in for that session, so All panels needs no second one. Sign-out, the session's
 * end and a new HA address end it in HA; a code sign-in has none; no token is ever logged.
 */
class ManageKeptSignInTest {
    private val f = ManageFixture()

    private fun signedIn() = f.Browser().also { it.signInWithHa() }
    private fun sessionId() = f.sessions.list(f.haBase).last().id
    private fun access(b: ManageFixture.Browser, csrf: String? = b.csrf(), origin: String = "http://${b.host}") =
        b.post(ManageServer.HA_ACCESS, csrf?.let { mapOf("csrf" to it) } ?: emptyMap(), mapOf("origin" to origin))

    @Test fun anAdministratorsSignInIsKeptAndAllPanelsGetsAFreshAccessToken() {
        val b = signedIn()
        assertTrue(f.revoked.isEmpty())
        assertTrue(f.server.keepsHaSignIn(sessionId()))
        assertTrue(b.get(ManagePages.PANELS).text().contains("data-ha-signin=\"1\""))
        val r = access(b)
        assertEquals(200, r.status)
        val j = JSONObject(r.text())
        assertEquals("ACCESS-RENEWED", j.getString("access_token"))
        assertEquals(1800, j.getInt("expires_in"))
        assertEquals("no-store", r.headers["Cache-Control"])
        assertEquals(listOf("REFRESH"), f.refreshed)
    }

    @Test fun onlyThisPanelsOwnSignedInPageGetsIt() {
        val b = signedIn()
        assertEquals("without the form token", 403, access(b, csrf = null).status)
        assertEquals("from another site", 403, access(b, origin = "http://evil.example").status)
        assertEquals("signed out", 303, f.Browser().post(ManageServer.HA_ACCESS, emptyMap()).status)
        assertTrue(f.refreshed.isEmpty())
    }

    @Test fun aCodeSignInKeepsNothingSoAllPanelsAsksForContinue() {
        val b = f.Browser()
        b.cookies[ManageServer.SESSION] = f.sessions.create(null, SignedInWith.PANEL_CODE, "Safari on Mac", false, f.haBase).first
        assertTrue(b.get(ManagePages.PANELS).text().contains("data-ha-signin=\"\""))
        assertEquals(404, access(b).status)
    }

    @Test fun signingOutEndsItInHomeAssistant() {
        val b = signedIn()
        val id = sessionId()
        b.post("/manage/signout", mapOf("csrf" to b.csrf()))
        assertEquals(listOf("REFRESH"), f.revoked)
        assertFalse(f.server.keepsHaSignIn(id))
    }

    @Test fun signingTheBrowserOutFromSecurityEndsItToo() {
        val b = signedIn()
        val id = sessionId()
        val csrf = b.csrf()
        val other = signedIn()
        other.post("/security/signout", mapOf("csrf" to other.csrf(), "id" to id))
        assertEquals(listOf("REFRESH"), f.revoked)
        assertEquals("that browser is signed out: no token for it", 303, access(b, csrf = csrf).status)
    }

    @Test fun allPanelsSignOutEndsItAndLeavesTheManageSession() {
        val b = signedIn()
        assertEquals(200, b.post(ManageServer.HA_FORGET, mapOf("csrf" to b.csrf())).status)
        assertEquals(listOf("REFRESH"), f.revoked)
        assertEquals(200, b.get("/").status)
        assertEquals(404, access(b).status)
    }

    @Test fun aRevokeHomeAssistantDidntTakeIsReportedAndTriedAgain() {
        val b = signedIn()
        val csrf = b.csrf()
        f.revokeWorks = false
        val r = b.post(ManageServer.HA_FORGET, mapOf("csrf" to csrf))
        assertEquals(502, r.status)
        assertTrue(r.text().contains("didn't confirm"))
        assertEquals("no longer usable", 404, access(b, csrf = csrf).status)
        f.revokeWorks = true
        assertEquals("Try again ends it", 200, b.post(ManageServer.HA_FORGET, mapOf("csrf" to csrf)).status)
        assertEquals(listOf("REFRESH", "REFRESH", "REFRESH"), f.revoked)
        f.revoked.clear()
        f.server.sweep()
        assertTrue("nothing left to end", f.revoked.isEmpty())
    }

    @Test fun theSweepTriesAgainAnEndHomeAssistantDidntTake() {
        val b = signedIn()
        f.revokeWorks = false
        b.post("/manage/signout", mapOf("csrf" to b.csrf()))
        assertEquals(2, f.revoked.size)
        f.revokeWorks = true
        f.server.sweep()
        assertEquals(3, f.revoked.size)
        f.server.sweep()
        assertEquals("done once taken", 3, f.revoked.size)
    }

    @Test fun aNewHomeAssistantAddressEndsIt() {
        val b = signedIn()
        val id = sessionId()
        f.haBase = "http://192.0.2.11:8123"
        f.server.sweep()
        assertEquals(listOf("REFRESH"), f.revoked)
        assertFalse(f.server.keepsHaSignIn(id))
    }

    @Test fun aSignInHomeAssistantNoLongerTakesIsDroppedButAnOutageIsNot() {
        val b = signedIn()
        f.refreshAnswer = HaGateway.Tokens.Failed("unreachable", unreachable = true)
        assertEquals(503, access(b).status)
        assertTrue(f.server.keepsHaSignIn(sessionId()))
        f.refreshAnswer = HaGateway.Tokens.Failed("server_error")
        assertEquals("a 5xx from HA or a proxy is an outage too", 503, access(b).status)
        assertTrue(f.server.keepsHaSignIn(sessionId()))
        f.refreshAnswer = HaGateway.Tokens.Failed("invalid_grant", refused = true)
        assertEquals(404, access(b).status)
        assertFalse(f.server.keepsHaSignIn(sessionId()))
    }

    @Test fun aSessionThatRunsOutHasItsSignInEndedByTheSweepWithNoRequest() {
        f.Browser().signInWithHa(remember = false)
        val id = sessionId()
        f.now += 13L * 3600 * 1000 // past a session that isn't remembered (12 h)
        assertTrue("nothing ends it before the sweep", f.revoked.isEmpty())
        f.server.sweep()
        assertEquals(listOf("REFRESH"), f.revoked)
        assertFalse(f.server.keepsHaSignIn(id))
    }

    @Test fun noTokenInTheLogTheDiagnosticsOrAWarning() {
        val b = signedIn()
        access(b)
        b.post(ManageServer.HA_FORGET, mapOf("csrf" to b.csrf()))
        for (e in f.events) f.log.append(LogEntry(f.now, LogEntry.Level.INFO, "Manage", e))
        val diagnostics = signedIn().get("/log/diagnostics.txt").text()
        for (secret in listOf("REFRESH", "ACCESS-RENEWED", "ACCESS")) {
            assertTrue(secret, f.events.none { secret in it })
            assertFalse(secret, secret in diagnostics)
            assertTrue(secret, f.warnings.none { secret in it })
        }
    }
}
