/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #466: another panel's page reads and changes this panel's settings with a Home Assistant token, which this
 * panel checks with its own Home Assistant. Administrators only; no cookie counts; the token is never logged.
 */
class ManageApiTest {
    private var idle = 120
    private var hide = true
    private var radioOn = true
    private var role = "zigbee"
    private var cameraSet = true
    private var haUrl = "http://homeassistant.local:8123/lovelace/0"
    private var dashboard = "Default"
    private val f = ManageFixture()

    /** Not JWT-shaped, so the Log's redaction wouldn't catch it: only never writing it keeps it out. */
    private val token = "panel-a-page-token-0123456789abcdef"

    init {
        val screen = SettingsSection("screen", "Screen", listOf(
            SettingsItem.Switch("Full screen", "Hide Home Assistant's header and sidebar", { hide }) { v, d -> hide = v; d(null) },
            SettingsItem.Duration("Turn off when idle", { idle }, listOf(30, 60, 120, 0), { "After $it s" }) { v, d -> idle = v; d(null) },
            SettingsItem.Action("Allow setup over the network", { "" }, "Allow") { d -> d(null) },
        ))
        val radio = SettingsSection("radio", "Radio", listOf(
            SettingsItem.Choice("Role", { role }, { listOf("zigbee" to "Zigbee coordinator", "thread" to "Thread border router") },
                confirm = { if (it == role) null else SettingsItem.Confirm("Change the radio's role?", "Zigbee devices stop.", "Change") }) { v, d -> role = v; d(null) },
        ), master = SettingsItem.Switch("Zigbee / Thread radio", "", { radioOn },
            confirm = { on -> if (on) null else SettingsItem.Confirm("Turn the radio off?", "Zigbee devices stop.", "Turn off") }) { v, d -> radioOn = v; d(null) })
        val camera = SettingsSection("camera", "Camera", listOf(
            SettingsItem.SignIn("Stream sign-in", { cameraSet }, { "viewer" }) { _, d -> d(null) },
        ))
        val dashboard = SettingsSection("dashboard", "Home Assistant & dashboard", listOf(
            SettingsItem.Text("Home Assistant", "Found automatically", SettingsItem.TextKind.ADDRESS, { haUrl }, perPanel = true) { v, d -> haUrl = v; d(null) },
            SettingsItem.Choice("Dashboard", { this.dashboard }, { listOf("Default" to "Default", "Tablet" to "Tablet") }, perPanel = true) { v, d -> this.dashboard = v; d(null) },
        ))
        f.statusFn = { f.status.copy(sections = listOf(screen, radio, camera, dashboard)) }
    }

    /** A page on another panel: its Origin, no cookie, the token as a bearer. */
    private fun page(t: String? = token) = f.Browser(remote = "192.0.2.77").also { it.cookies.clear() }.let { b ->
        object {
            val headers = mapOf("origin" to "http://192.0.2.41:8099") + (t?.let { mapOf("authorization" to "Bearer $it") } ?: emptyMap())
            fun get() = b.get(ManageApi.SETTINGS, headers)
            fun post(vararg form: Pair<String, String>) = b.post(ManageApi.SETTINGS, form.toMap(), headers)
        }
    }

    private fun json(r: dev.glassworks.web.HttpResponse) = JSONObject(r.text())

    @Test fun readsTheSettingsForAnAdministratorsToken() {
        val r = page().get()
        assertEquals(200, r.status)
        assertEquals("*", r.headers["Access-Control-Allow-Origin"])
        val j = json(r)
        assertEquals("0.9.2", j.getString("version"))
        val ids = (0 until j.getJSONArray("sections").length()).map { j.getJSONArray("sections").getJSONObject(it).getString("id") }
        assertEquals(listOf("screen", "radio", "camera", "dashboard"), ids)
        val screen = j.getJSONArray("sections").getJSONObject(0).getJSONArray("items")
        assertEquals("actions aren't values: only the switch and the duration", 2, screen.length())
        val idleItem = screen.getJSONObject(1)
        assertEquals("duration", idleItem.getString("kind"))
        assertEquals("120", idleItem.getString("value"))
        assertEquals("[30,60,120,0]", idleItem.getJSONArray("choices").toString())
        val radio = j.getJSONArray("sections").getJSONObject(1).getJSONArray("items")
        assertEquals("the pane's on/off comes first, as its key says", "on", radio.getJSONObject(0).getString("key"))
        assertEquals("Thread border router", radio.getJSONObject(1).getJSONArray("options").getJSONArray(1).getString(1))
    }

    @Test fun thePanelsOwnHomeAssistantAddressIsNeverCopiedNorChangedFromAnotherPanel() {
        val items = json(page().get()).getJSONArray("sections").getJSONObject(3).getJSONArray("items")
        val row = items.getJSONObject(0)
        assertEquals("Home Assistant", row.getString("key"))
        assertFalse("marked as not copyable", row.getBoolean("copy"))
        val r = page().post("section" to "dashboard", "key" to "Home Assistant", "value" to "http://192.0.2.10:8123")
        assertEquals(422, r.status)
        assertEquals(ManageApi.PER_PANEL, json(r).getString("problem"))
        assertEquals("http://homeassistant.local:8123/lovelace/0", haUrl)

        val dashRow = items.getJSONObject(1)
        assertEquals("Dashboard", dashRow.getString("key"))
        assertFalse("dashboard choice is marked as not copyable", dashRow.getBoolean("copy"))
        val dashRes = page().post("section" to "dashboard", "key" to "Dashboard", "value" to "Tablet")
        assertEquals(422, dashRes.status)
        assertEquals(ManageApi.PER_PANEL, json(dashRes).getString("problem"))
        assertEquals("Default", dashboard)
        assertEquals(0, f.onMainCalls)
    }

    @Test fun aSignInSaysOnlyWhetherItIsSet() {
        val camera = json(page().get()).getJSONArray("sections").getJSONObject(2).getJSONArray("items").getJSONObject(0)
        assertEquals("sign-in", camera.getString("kind"))
        assertTrue(camera.getBoolean("set"))
        assertFalse(camera.has("value"))
        assertFalse("not even the user name", camera.toString().contains("viewer"))
        assertEquals(422, page().post("section" to "camera", "key" to "Stream sign-in", "value" to "x").status)
    }

    @Test fun changesASettingThroughItsOwnSaveAndLogsWhoAndFromWhere() {
        val r = page().post("section" to "screen", "key" to "Turn off when idle", "value" to "60")
        assertEquals(200, r.status)
        assertTrue(json(r).getBoolean("ok"))
        assertEquals(60, idle)
        assertEquals(1, f.onMainCalls)
        assertTrue(f.events.toString(), f.events.contains("Alex (from 192.0.2.41:8099) changed Screen › Turn off when idle"))
    }

    @Test fun thePanelsChecksStillApply() {
        val r = page().post("section" to "screen", "key" to "Turn off when idle", "value" to "45")
        assertEquals(422, r.status)
        assertEquals(dev.glassworks.pane.SettingsChange.PICK, json(r).getString("problem"))
        assertEquals(120, idle)
    }

    @Test fun aChangeThatAsksFirstOnThePanelRunsOnlyWithItsOneShotAnswer() {
        val asked = page().post("section" to "radio", "key" to "on", "value" to "off")
        assertEquals(409, asked.status)
        val ask = json(asked).getJSONObject("ask")
        assertEquals("Turn the radio off?", ask.getString("title"))
        val id = ask.getString("id")
        assertTrue(radioOn)
        assertEquals("the caller's word alone is no answer", 409, page().post("section" to "radio", "key" to "on", "value" to "off", "confirmed" to "1").status)
        assertEquals("another change can't use it", 409, page().post("section" to "radio", "key" to "Role", "value" to "thread", "answer" to id).status)
        assertEquals("zigbee", role)
        assertTrue(radioOn)
        val id2 = json(page().post("section" to "radio", "key" to "on", "value" to "off")).getJSONObject("ask").getString("id")
        assertEquals("another token can't use it", 409, page("other-admin-token-0123456789").post("section" to "radio", "key" to "on", "value" to "off", "answer" to id2).status)
        val id3 = json(page().post("section" to "radio", "key" to "on", "value" to "off")).getJSONObject("ask").getString("id")
        assertEquals(200, page().post("section" to "radio", "key" to "on", "value" to "off", "answer" to id3).status)
        assertFalse(radioOn)
        radioOn = true
        assertEquals("used up", 409, page().post("section" to "radio", "key" to "on", "value" to "off", "answer" to id3).status)
        assertTrue(radioOn)
    }

    @Test fun aSettingThisPanelDoesntHaveIsMissingNotAnError() {
        val r = page().post("section" to "screen", "key" to "Night mode", "value" to "on")
        assertEquals(404, r.status)
        assertTrue(json(r).getBoolean("missing"))
        assertEquals(404, page().post("section" to "voice", "key" to "on", "value" to "on").status)
    }

    @Test fun aTokenHomeAssistantDoesntConfirmGetsNothing() {
        f.refusedTokens += token
        val r = page().get()
        assertEquals(401, r.status)
        assertEquals("Bearer", r.headers["WWW-Authenticate"])
        assertFalse(r.text().contains("Turn off when idle"))
        assertEquals(401, page().post("section" to "screen", "key" to "Turn off when idle", "value" to "60").status)
        assertEquals(120, idle)
        assertEquals(401, page(null).get().status)
    }

    @Test fun anotherUserIsRefused() {
        f.user = HaUser("Sam", isAdmin = false)
        val r = page().post("section" to "screen", "key" to "Turn off when idle", "value" to "60")
        assertEquals(403, r.status)
        assertEquals("Sam isn't a Home Assistant administrator.", json(r).getString("problem"))
        assertEquals(120, idle)
        assertEquals(0, f.onMainCalls)
    }

    @Test fun aSignedInBrowsersCookieDoesntCount() {
        val b = f.Browser().also { it.signInWithHa() }
        assertEquals(401, b.get(ManageApi.SETTINGS).status)
        assertEquals(401, b.post(ManageApi.SETTINGS, mapOf("csrf" to b.csrf(), "section" to "screen", "key" to "Turn off when idle", "value" to "60")).status)
        assertEquals(120, idle)
    }

    @Test fun onlyAHomeAssistantOnTheLocalNetworkChecks() {
        f.haBase = "https://example.ui.nabu.casa"
        assertEquals(409, page().get().status)
        f.haBase = null
        assertEquals(409, page().get().status)
        assertTrue("never asked a public Home Assistant", f.userChecks.isEmpty())
    }

    @Test fun anAnswerIsKeptForAMinuteAndForThatAddressOnly() {
        page().get(); page().get()
        assertEquals(1, f.userChecks.size)
        f.monoMs += 60_000
        page().get()
        assertEquals(2, f.userChecks.size)
        f.haBase = "http://192.0.2.11:8123"
        page().get()
        assertEquals(listOf("http://192.0.2.10:8123", "http://192.0.2.10:8123", "http://192.0.2.11:8123"), f.userChecks.toList())
    }

    @Test fun aRefusedTokenIsKeptAsRefusedForTenSeconds() {
        f.refusedTokens += token
        page().get(); page().get(); page().get()
        assertEquals("a made-up token asks Home Assistant once", 1, f.userChecks.size)
        f.refusedTokens.clear()
        assertEquals(401, page().get().status)
        f.monoMs += 10_000
        assertEquals(200, page().get().status)
        assertEquals(2, f.userChecks.size)
    }

    @Test fun whileHomeAssistantIsSlowAtMostTwoChecksWaitAndTheRestAreTurnedAwayAtOnce() {
        val gate = java.util.concurrent.CountDownLatch(1)
        f.userGate = gate
        val waiting = (1..2).map { n -> Thread { page("made-up-token-$n-0123456789").get() }.apply { start() } }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (f.userChecks.size < 2) {
            if (System.nanoTime() > deadline) {
                gate.countDown()
                throw AssertionError("two checks never reached Home Assistant")
            }
            Thread.sleep(5)
        }
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val third = runCatching { pool.submit<dev.glassworks.web.HttpResponse> { page("made-up-token-3-0123456789").get() }.get(3, java.util.concurrent.TimeUnit.SECONDS) }
                .getOrElse { throw AssertionError("a third check waited on Home Assistant instead of being turned away", it) }
            assertEquals(503, third.status)
            assertEquals("2", third.headers["Retry-After"])
            assertEquals("a page on another panel may read the wait", "Retry-After", third.headers["Access-Control-Expose-Headers"])
            assertEquals(2, f.userChecks.size)
        } finally {
            gate.countDown()
            pool.shutdownNow()
        }
        waiting.forEach { it.join(5_000) }
        f.userGate = null
        assertEquals(200, page().get().status)
    }

    @Test fun whetherHomeAssistantIsLocalIsDecidedOnceAMinuteNotOnEveryRequest() {
        f.resolved.clear()
        for (n in 1..4) page("token-$n-0123456789abcdef").get()
        assertEquals("one lookup of Home Assistant's address for four tokens", listOf("192.0.2.10"), f.resolved.toList())
        f.monoMs += 60_000
        page().get()
        assertEquals(2, f.resolved.size)
    }

    @Test fun aSlowLookupDoesntStretchAnAnswersMinute() {
        f.lookupMs = 5_000
        assertEquals(200, page().get().status)
        f.refusedTokens += token
        // The address was decided at 0 and the answer kept at 5 s; at 60 s the lookup runs again and takes 5 s.
        f.monoMs = 60_000
        assertEquals("the answer kept at 5 s is 60 s old once the lookup is done", 401, page().get().status)
        assertEquals(2, f.userChecks.size)
    }

    @Test fun thePreflightNeedsNoTokenAndAnswersCors() {
        val r = f.Browser().send("OPTIONS", ManageApi.SETTINGS, emptyMap(), mapOf("origin" to "http://192.0.2.41:8099",
            "access-control-request-method" to "POST", "access-control-request-headers" to "authorization", "access-control-request-private-network" to "true"))
        assertEquals(204, r.status)
        assertTrue(r.headers["Access-Control-Allow-Headers"]!!.contains("Authorization"))
        assertNull("192.0.2.0/24 isn't a private range: no private-network permission for it", r.headers["Access-Control-Allow-Private-Network"])
        assertTrue(f.userChecks.isEmpty())
        fun pna(origin: String) = f.Browser().send("OPTIONS", ManageApi.SETTINGS, emptyMap(), mapOf("origin" to origin,
            "access-control-request-method" to "GET", "access-control-request-private-network" to "true")).headers["Access-Control-Allow-Private-Network"]
        assertEquals("true", pna("http://glassworks-panel.local:8099"))
        assertNull(pna("https://evil.example"))
        assertNull(pna("http://203.0.113.5"))
    }

    @Test fun onlyAPageOnTheLocalNetworkCounts() {
        for (o in listOf("http://localhost:8099", "http://kitchen-panel.local:8099", "http://10.0.0.41:8099", "http://[fd00::41]:8099", "http://[fe80::1]:8099"))
            assertTrue(o, ManageApi.localOrigin(o))
        for (o in listOf(null, "", "null", "https://evil.example", "http://203.0.113.5", "http://[2001:db8::1]", "http://10.0.0.41.evil.example"))
            assertFalse(o.toString(), ManageApi.localOrigin(o))
    }

    @Test fun helloNeedsNoTokenAndSaysOnlyWhatHomeAssistantShowsAlready() {
        val r = f.Browser().get(ManageApi.HELLO, mapOf("origin" to "http://192.0.2.41:8099"))
        assertEquals(200, r.status)
        assertEquals("*", r.headers["Access-Control-Allow-Origin"])
        val j = json(r)
        assertTrue(j.getBoolean("glassworks"))
        assertEquals("Panel Hub S1 Plus (Glassworks)", j.getString("model"))
        assertEquals(setOf("api", "glassworks", "model"), j.keys().asSequence().toSet())
        assertTrue(f.userChecks.isEmpty())
    }

    @Test fun anotherHostNameGetsNothing() {
        val r = f.Browser(host = "evil.example").get(ManageApi.SETTINGS, mapOf("authorization" to "Bearer $token"))
        assertEquals(400, r.status)
        assertTrue(f.userChecks.isEmpty())
    }

    // ------------------------------------------------------------------ signing in from another panel's page

    private fun handoff(t: String, next: String = "/screen") = f.Browser(fetchMetadata = false).let { b ->
        b to b.post(ManageServer.HANDOFF, mapOf("token" to t, "next" to next), mapOf("origin" to "http://192.0.2.41:8099"))
    }

    @Test fun anAdministratorsTokenSignsThisBrowserInUntilItCloses() {
        val (b, r) = handoff(token)
        assertEquals(200, r.status)
        val cookie = r.headers["Set-Cookie"]!!
        assertTrue(cookie, cookie.startsWith("${ManageServer.SESSION}=") && cookie.contains("SameSite=Strict") && !cookie.contains("Max-Age"))
        assertTrue("reloads to the page asked for", r.text().contains("url=/screen"))
        assertTrue(b.get("/screen").text().contains("Turn off when idle"))
        assertTrue(f.events.toString(), f.events.any { it.startsWith("Alex signed in with Home Assistant from the Manage page on 192.0.2.41:8099") })
        assertTrue("the token is the other page's: kept and revoked by neither", f.revoked.isEmpty())
    }

    @Test fun aHandoffThatHomeAssistantDoesntConfirmSignsNothingIn() {
        f.user = HaUser("Sam", isAdmin = false)
        val (_, r) = handoff(token)
        assertEquals(403, r.status)
        assertTrue(r.text().contains("Administrators only"))
        assertNull(r.headers["Set-Cookie"])
        f.user = HaUser("Alex", isAdmin = true)
        f.refusedTokens += "unknown"
        assertNull(handoff("unknown").second.headers["Set-Cookie"])
        assertEquals("a link can't sign anyone in", 303, f.Browser().get(ManageServer.HANDOFF + "?token=$token").status)
    }

    @Test fun aHandoffAsksHomeAssistantFreshSoARevokedTokenCantSignIn() {
        assertEquals(200, page().get().status)
        f.refusedTokens += token
        assertEquals("the API still has the minute-old answer", 200, page().get().status)
        val (_, r) = handoff(token)
        assertEquals(403, r.status)
        assertNull(r.headers["Set-Cookie"])
        assertEquals(2, f.userChecks.size)
    }

    @Test fun madeUpHandoffsFromOneAddressArePausedSoTheyCantKeepHomeAssistantBusy() {
        fun post(t: String, remote: String = "192.0.2.77") =
            f.Browser(remote = remote, fetchMetadata = false).post(ManageServer.HANDOFF, mapOf("token" to t, "next" to "/"), mapOf("origin" to "http://evil.example"))
        for (n in 1..8) f.refusedTokens += "made-up-$n-0123456789"
        val answers = (1..8).map { n -> post("made-up-$n-0123456789").status }
        assertEquals(List(5) { 403 } + List(3) { 429 }, answers)
        val paused = post("made-up-1-0123456789")
        assertEquals("10", paused.headers["Retry-After"])
        assertEquals("the title and the line under it say different things", 1, Regex("Try again in a few seconds").findAll(paused.text()).count())
        assertEquals("five questions to Home Assistant, not eight", 5, f.userChecks.size)
        assertEquals("another computer isn't held up", 200, post(token, remote = "192.0.2.78").status)
        f.monoMs += ManageServer.HANDOFF_WINDOW_MS
        assertEquals(200, post(token).status)
        assertEquals(7, f.userChecks.size)
        assertEquals("the paused ones don't fill the Log", 5, f.events.count { it.startsWith("A sign-in from another panel's page was refused") })
    }

    @Test fun aHandoffIgnoresAKeptRefusalToo() {
        // Home Assistant refused the token a moment ago (it was restarting); now it confirms it.
        f.refusedTokens += token
        assertEquals(401, page().get().status)
        f.refusedTokens.clear()
        assertEquals("the API keeps the refusal for 10 s", 401, page().get().status)
        val (_, r) = handoff(token)
        assertEquals(200, r.status)
        assertTrue(r.headers["Set-Cookie"]!!.startsWith(ManageServer.SESSION + "="))
    }

    @Test fun aSettingsRequestIsBoundToTheAddressThatConfirmedTheUser() {
        var reads = 0
        f.haBaseFn = { if (reads++ == 0) "http://192.0.2.10:8123" else "http://192.0.2.11:8123" }
        val r = page().post("section" to "screen", "key" to "Turn off when idle", "value" to "60")
        assertEquals(401, r.status)
        assertEquals(ManageApi.ADDRESS_CHANGED, json(r).getString("problem"))
        assertEquals(120, idle)
        reads = 0
        assertEquals(401, page().get().status)
        f.haBaseFn = null
        assertEquals(200, page().get().status)
    }

    @Test fun aHandoffFromSafarisNewTabStillNamesThePageItCameFrom() {
        // Safari posts the handoff from a new tab with `Origin: null` (the 2026-11-18 panel run logged "on null").
        val b = f.Browser(fetchMetadata = false)
        assertEquals(200, b.post(ManageServer.HANDOFF, mapOf("token" to token, "next" to "/", "from" to "192.0.2.41:8099"), mapOf("origin" to "null")).status)
        assertTrue(f.events.toString(), f.events.any { it.startsWith("Alex signed in with Home Assistant from the Manage page on 192.0.2.41:8099 (") })
        assertTrue(f.events.none { "on null" in it })
        f.events.clear()
        assertEquals(200, f.Browser(fetchMetadata = false).post(ManageServer.HANDOFF, mapOf("token" to token, "next" to "/"), mapOf("origin" to "null")).status)
        assertTrue(f.events.toString(), f.events.any { it.startsWith("Alex signed in with Home Assistant from the Manage page on no page (") })
        f.events.clear()
        // A real Origin wins over what the page says about itself.
        f.Browser(fetchMetadata = false).post(ManageServer.HANDOFF, mapOf("token" to token, "next" to "/", "from" to "evil.example"), mapOf("origin" to "http://192.0.2.41:8099"))
        assertTrue(f.events.toString(), f.events.any { "on 192.0.2.41:8099 (" in it })
    }

    @Test fun aHandoffIsBoundToTheAddressThatConfirmedTheUser() {
        // The first read (the check) sees one address, every later one another: the panel's address changed mid-request.
        var reads = 0
        f.haBaseFn = { if (reads++ == 0) "http://192.0.2.10:8123" else "http://192.0.2.11:8123" }
        val (_, r) = handoff(token)
        assertEquals(403, r.status)
        assertTrue(r.text().contains("Home Assistant's address changed"))
        assertNull(r.headers["Set-Cookie"])
    }

    @Test fun aHandoffGoesOnlyToAPathOnThisPanel() {
        val (_, r) = handoff(token, next = "//evil.example/")
        assertTrue(r.text().contains("url=/\""))
    }

    @Test fun theTokenIsInNoLogNoDiagnosticsAndNoAnswer() {
        page().get()
        page().post("section" to "screen", "key" to "Turn off when idle", "value" to "60")
        page().post("section" to "radio", "key" to "on", "value" to "off")
        page().post("section" to "screen", "key" to "Turn off when idle", "value" to "45")
        handoff(token)
        f.user = HaUser("Sam", isAdmin = false)
        f.monoMs += 60_000
        page().get()
        handoff(token)
        for (e in f.events) f.log.append(LogEntry(f.now, LogEntry.Level.INFO, "Manage", e))
        assertFalse(f.events.isEmpty())
        assertTrue(f.events.toString(), f.events.none { token in it })
        f.user = HaUser("Alex", isAdmin = true)
        val b = f.Browser().also { it.signInWithHa() }
        val diagnostics = b.get("/log/diagnostics.txt").text()
        assertTrue(diagnostics.contains("Turn off when idle"))
        assertFalse(diagnostics.contains(token))
        assertFalse(b.get("/log").text().contains(token))
    }
}
