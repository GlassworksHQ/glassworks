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
        val signedOut = f.Browser().post(ManageServer.HA_ACCESS, emptyMap())
        assertEquals("signed out: an answer All panels can read, not a redirect", 401, signedOut.status)
        assertTrue(signedOut.text().contains(ManageServer.MANAGE_ENDED))
        assertEquals(401, f.Browser().post(ManageServer.HA_FORGET, emptyMap()).status)
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
        assertEquals("that browser is signed out: no token for it", 401, access(b, csrf = csrf).status)
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

    @Test fun tryAgainWhileTheSweepIsStillEndingItIsntReportedDone() {
        val b = signedIn()
        val csrf = b.csrf()
        f.revokeWorks = false
        assertEquals(502, b.post(ManageServer.HA_FORGET, mapOf("csrf" to csrf)).status)
        // The sweep tries again, and Home Assistant is slow to answer (and won't take it this time either).
        val started = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        f.revokeStarted = started
        f.revokeGate = gate
        val sweeping = Thread { f.server.sweep() }.apply { start() }
        try {
            assertTrue("the sweep began its revoke", started.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("not done while the sweep may still fail", 502, b.post(ManageServer.HA_FORGET, mapOf("csrf" to csrf)).status)
            assertEquals("that Try again sent nothing of its own", 3, f.revoked.size)
        } finally {
            gate.countDown()
            sweeping.join(10_000)
        }
        assertFalse("the sweep finished", sweeping.isAlive)
        assertFalse(f.revokeGateTimedOut)
        f.revokeGate = null
        f.revokeStarted = null
        f.revokeWorks = true
        assertEquals("tried again once the sweep is done", 200, b.post(ManageServer.HA_FORGET, mapOf("csrf" to csrf)).status)
    }

    @Test fun anAddressThatChangesWhileHomeAssistantRenewsItGetsNoToken() {
        val b = signedIn()
        val id = sessionId()
        f.duringRefresh = { f.haBase = "http://192.0.2.11:8123" }
        val r = access(b)
        assertEquals(404, r.status)
        assertTrue("the page reloads for the new address, not a sign-in at the old", JSONObject(r.text()).optBoolean("reload"))
        assertFalse(r.text().contains("ACCESS-RENEWED"))
        assertTrue("never sent to the old address", f.revoked.isEmpty())
        assertTrue(f.events.any { it.contains("address changed") })
        assertFalse(f.server.keepsHaSignIn(id))
    }

    @Test fun anAddressThatChangesWhileARenewalFailsSaysReloadToo() {
        val b = signedIn()
        f.refreshAnswer = HaGateway.Tokens.Failed("invalid_grant", refused = true)
        f.duringRefresh = { f.haBase = "http://192.0.2.11:8123" }
        val r = access(b)
        assertEquals(404, r.status)
        assertTrue(JSONObject(r.text()).optBoolean("reload"))
    }

    @Test fun stoppingThePageEndsWhatItStillKeepsAndRetriesNothingAfter() {
        signedIn()
        val other = signedIn()
        f.revokeWorks = false
        assertEquals(502, other.post(ManageServer.HA_FORGET, mapOf("csrf" to other.csrf())).status)
        f.revoked.clear()
        f.revokeWorks = true
        f.server.stop()
        assertEquals("the kept one and the unconfirmed one, once each", listOf("REFRESH", "REFRESH"), f.revoked)
        f.revoked.clear()
        f.server.sweep()
        assertTrue("nothing left after the stop", f.revoked.isEmpty())
    }

    @Test fun aGrantNoSessionTookIsRevokedWhereItCameFromAndNotRetriedThereAfter() {
        val b = f.Browser()
        b.get("/")
        val start = b.post("/manage/ha", mapOf("next" to "/"))
        val state = dev.glassworks.web.HttpServer.parseQuery(start.headers["Location"]!!.substringAfter('?'))["state"]!!
        f.haBase = "http://192.0.2.11:8123"
        f.revokeWorks = false
        assertEquals("the address changed during the sign-in", 409,
            b.get("/manage/callback?code=CODE&state=$state", mapOf("sec-fetch-site" to "cross-site")).status)
        assertEquals(2, f.revoked.size)
        f.revokeWorks = true
        f.server.sweep()
        assertEquals("the address it came from is no longer the panel's: dropped, not sent there again", 2, f.revoked.size)
        assertTrue(f.events.any { it.contains("address changed") })
    }

    @Test fun signingOutDoesntWaitForHomeAssistant() {
        val b = signedIn()
        val id = sessionId()
        val later = ArrayList<() -> Unit>()
        f.deferred = later
        assertEquals(303, b.post("/manage/signout", mapOf("csrf" to b.csrf())).status)
        assertTrue("the browser is signed out before HA answers", f.revoked.isEmpty())
        assertFalse("and the sign-in is no longer usable", f.server.keepsHaSignIn(id))
        f.deferred = null
        later.forEach { it() }
        assertEquals(listOf("REFRESH"), f.revoked)
    }

    @Test fun aSessionThatEndsWhileHomeAssistantRenewsItGetsNoToken() {
        val b = signedIn()
        val id = sessionId()
        f.duringRefresh = { f.sessions.revoke(id) }
        val r = access(b)
        assertEquals("the Manage session ended: Sign in again", 401, r.status)
        assertTrue(r.text().contains(ManageServer.MANAGE_ENDED))
        assertFalse(r.text().contains("ACCESS-RENEWED"))
    }

    @Test fun allPanelsSignOutWhileHomeAssistantRenewsItGetsNoToken() {
        val b = signedIn()
        val csrf = b.csrf()
        f.duringRefresh = { f.duringRefresh = null; b.post(ManageServer.HA_FORGET, mapOf("csrf" to csrf)) }
        val r = access(b, csrf = csrf)
        assertEquals("the Manage session is fine, the HA sign-in is over", 404, r.status)
        assertFalse(r.text().contains("ACCESS-RENEWED"))
    }

    @Test fun renewalsLeaveWorkersForTheRestOfThePage() {
        val browsers = List(3) { signedIn() }
        val csrfs = browsers.map { it.csrf() }
        val held = java.util.concurrent.CountDownLatch(1)
        val inside = java.util.concurrent.CountDownLatch(ManageServer.MAX_HA_CALLS)
        val gateTimedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        f.duringRefresh = { inside.countDown(); if (!held.await(10, java.util.concurrent.TimeUnit.SECONDS)) gateTimedOut.set(true) }
        val answers = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        val slow = (0 until ManageServer.MAX_HA_CALLS).map { i -> Thread { answers[i] = access(browsers[i], csrf = csrfs[i]).status }.apply { start() } }
        try {
            assertTrue("two renewals wait on Home Assistant", inside.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val third = access(browsers[2], csrf = csrfs[2])
            assertEquals("busy, try again: not another worker stuck on HA", 503, third.status)
            assertEquals("All panels' Sign out shares the slots", 503, browsers[2].post(ManageServer.HA_FORGET, mapOf("csrf" to csrfs[2])).status)
            val check = f.Browser().get(ManageApi.SETTINGS, mapOf("authorization" to "Bearer OTHER-PANELS-TOKEN"))
            assertEquals("and so does another panel's token check", 503, check.status)
            assertTrue(f.server.keepsHaSignIn(f.sessions.list(f.haBase).first { it.csrf == csrfs[2] }.id))
        } finally {
            held.countDown()
            slow.forEach { it.join(10_000) }
        }
        assertTrue("both renewals finished", slow.none { it.isAlive })
        assertFalse("they were held until released", gateTimedOut.get())
        assertEquals("both admitted renewals got their token", mapOf(0 to 200, 1 to 200), answers.toMap())
        f.duringRefresh = null
        assertEquals(200, access(browsers[2], csrf = csrfs[2]).status)
    }

    @Test fun aRevokeThatFailsInASweepWaitsForTheNextOne() {
        f.Browser().signInWithHa(remember = false)
        f.now += 13L * 3600 * 1000
        f.revokeWorks = false
        f.server.sweep()
        assertEquals("two tries, then queued for a later pass, not two more at once", 2, f.revoked.size)
        f.server.sweep()
        assertEquals(4, f.revoked.size)
    }

    @Test fun aSessionThatEndsWhileARenewalFailsGetsSignInNotTryAgain() {
        val b = signedIn()
        val id = sessionId()
        f.refreshAnswer = HaGateway.Tokens.Failed("unreachable", unreachable = true)
        f.duringRefresh = { f.sessions.revoke(id) }
        val r = access(b)
        assertEquals(401, r.status)
        assertTrue(r.text().contains(ManageServer.MANAGE_ENDED))
    }

    @Test fun aSweepEndsNewlyEndedSessionsFirstAndRetriesAFewAtATime() {
        f.revokeWorks = false
        repeat(ManageServer.RETRIES_PER_SWEEP + 2) {
            val b = signedIn()
            assertEquals(502, b.post(ManageServer.HA_FORGET, mapOf("csrf" to b.csrf())).status)
        }
        f.Browser().signInWithHa(remember = false)
        val ended = sessionId()
        f.now += 13L * 3600 * 1000
        f.revoked.clear()
        f.server.sweep()
        assertFalse("the newly ended one was tried", f.server.keepsHaSignIn(ended))
        assertEquals("it, then a few queued ones, two tries each", 2 * (1 + ManageServer.RETRIES_PER_SWEEP), f.revoked.size)
    }

    @Test fun theSweepReadsTheSessionsWithoutEndingANewAddresssOnes() {
        signedIn()
        val (_, other) = f.sessions.create("Alex", SignedInWith.HOME_ASSISTANT, "Safari on Mac", true, "http://192.0.2.11:8123")
        // The sweep read the old address; a session for the new one was made meanwhile. The sweep mustn't end it.
        f.server.sweep()
        assertTrue(other.id in f.sessions.liveIds())
    }

    @Test fun aRevokeThatFailsAfterTheStopGoesToTheLastCleanup() {
        val b = signedIn()
        val csrf = b.csrf()
        f.revokeWorks = false
        assertEquals(502, b.post(ManageServer.HA_FORGET, mapOf("csrf" to csrf)).status)
        val started = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        f.revokeStarted = started
        f.revokeGate = gate
        val sweeping = Thread { f.server.sweep() }.apply { start() }
        try {
            assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
            f.server.stop()
            assertEquals("nothing left to drain yet: the sweep holds it", 3, f.revoked.size)
            f.revokeGate = null
        } finally {
            gate.countDown()
            sweeping.join(10_000)
        }
        assertFalse(sweeping.isAlive)
        // HA still doesn't take it: the sweep's two tries, then the last cleanup's two, rather than nothing after the stop.
        assertEquals(6, f.revoked.size)
    }

    @Test fun aRevokeHaDoesntTakeAsThePageStopsIsInTheLog() {
        signedIn()
        f.revokeWorks = false
        f.server.stop()
        assertEquals(2, f.revoked.size)
        assertTrue(f.events.any { it.contains("as the page stopped") })
    }

    @Test fun aSignInThatFinishesAfterTheStopIsEndedToo() {
        val b = f.Browser()
        b.get("/")
        val start = b.post("/manage/ha", mapOf("next" to "/"))
        val state = dev.glassworks.web.HttpServer.parseQuery(start.headers["Location"]!!.substringAfter('?'))["state"]!!
        f.server.stop()
        b.get("/manage/callback?code=CODE&state=$state", mapOf("sec-fetch-site" to "cross-site"))
        assertEquals(listOf("REFRESH"), f.revoked)
        assertFalse(f.server.keepsHaSignIn(sessionId()))
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

    @Test fun aNewHomeAssistantAddressDropsItWithoutSendingItToTheOldOne() {
        signedIn()
        val id = sessionId()
        f.haBase = "http://192.0.2.11:8123"
        f.server.sweep()
        assertTrue("the old address may be someone else now", f.revoked.isEmpty())
        assertTrue(f.events.any { it.contains("address changed") && !it.contains("REFRESH") })
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
        // A revoke HA doesn't take, so there is a warning to look at.
        f.revokeWorks = false
        b.post(ManageServer.HA_FORGET, mapOf("csrf" to b.csrf()))
        assertTrue("a warning was written", f.warnings.isNotEmpty())
        f.revokeWorks = true
        for (e in f.events) f.log.append(LogEntry(f.now, LogEntry.Level.INFO, "Manage", e))
        val download = signedIn().get("/log/diagnostics.txt")
        assertEquals(200, download.status)
        assertTrue(download.headers["Content-Disposition"].orEmpty().startsWith("attachment"))
        val diagnostics = download.text()
        for (secret in listOf("REFRESH", "ACCESS-RENEWED", "ACCESS")) {
            assertTrue(secret, f.events.none { secret in it })
            assertFalse(secret, secret in diagnostics)
            assertTrue(secret, f.warnings.none { secret in it })
        }
    }
}
