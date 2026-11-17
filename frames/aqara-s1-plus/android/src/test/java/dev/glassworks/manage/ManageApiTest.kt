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
        f.statusFn = { f.status.copy(sections = listOf(screen, radio, camera)) }
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
        assertEquals(listOf("screen", "radio", "camera"), ids)
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

    @Test fun aChangeThatAsksFirstOnThePanelAsksHereUntilConfirmed() {
        val asked = page().post("section" to "radio", "key" to "on", "value" to "off")
        assertEquals(409, asked.status)
        assertEquals("Turn the radio off?", json(asked).getJSONObject("ask").getString("title"))
        assertTrue(radioOn)
        assertEquals(409, page().post("section" to "radio", "key" to "Role", "value" to "thread").status)
        assertEquals("zigbee", role)
        assertEquals(200, page().post("section" to "radio", "key" to "on", "value" to "off", "confirmed" to "1").status)
        assertFalse(radioOn)
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

    @Test fun aRefusedTokenIsAskedAgainNextTime() {
        f.refusedTokens += token
        page().get()
        f.refusedTokens.clear()
        assertEquals(200, page().get().status)
    }

    @Test fun thePreflightNeedsNoTokenAndAnswersCors() {
        val r = f.Browser().send("OPTIONS", ManageApi.SETTINGS, emptyMap(), mapOf("origin" to "http://192.0.2.41:8099",
            "access-control-request-method" to "POST", "access-control-request-headers" to "authorization", "access-control-request-private-network" to "true"))
        assertEquals(204, r.status)
        assertTrue(r.headers["Access-Control-Allow-Headers"]!!.contains("Authorization"))
        assertEquals("true", r.headers["Access-Control-Allow-Private-Network"])
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
