/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.app.Application
import android.content.Context
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.ManagePages
import dev.glassworks.manage.paneRow
import dev.glassworks.manage.text
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The camera's use on the Manage page (2026-11-18, the Mirror): the panel's two cards, on every frame, for
 * either sign-in, and logged. "Needs Presence + camera." links to them. Before, the page said
 * "On the panel", which a frame without touch can't offer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CameraManageTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val file get() = app.getSharedPreferences("camera-manage-test", Context.MODE_PRIVATE)

    @After fun tearDown() { file.edit().clear().commit() }

    /** The app's definition, with its cards (never drawn here) on a frame with or without [touch]. */
    private fun manage(touch: Boolean): Pair<CameraDefinition, ManageFixture> {
        val def = CameraDefinition(file, granted = { true }, pickers = { error("the panel's view isn't drawn here") }, touch = { touch })
        val f = ManageFixture()
        f.statusFn = { def.settings().single().let { sec -> f.status.copy(panes = listOf(paneRow(CameraPane.ID, sec)), sections = listOf(sec)) } }
        return def to f
    }

    private fun admin(f: ManageFixture) = f.Browser().also { it.signInWithHa() }

    private fun withCode(f: ManageFixture) = f.Browser().also { b ->
        b.get("/")
        b.post("/manage/code", mapOf("next" to "/"))
        b.post("/manage/code", mapOf("next" to "/", "code" to f.shown.last()!!.code))
    }

    private fun pick(f: ManageFixture, b: ManageFixture.Browser, use: CameraUse) =
        b.post("/settings", mapOf("csrf" to b.csrf(), "section" to CameraPane.ID, "key" to CameraDefinition.USE_TITLE, "value" to use.name))

    @Test fun bothCardsOnEveryFrameNotOnThePanel() {
        for (touch in listOf(false, true)) {
            val page = admin(manage(touch).second).get("/camera").text()
            assertFalse("touch=$touch: no read-only row", page.contains(ManagePages.ON_THE_PANEL) || page.contains("Shown on the panel's own settings"))
            val cards = page.substringAfter("<form class=\"ucs\" id=\"i-camera-use\"").substringBefore("</form>")
            assertTrue("touch=$touch: the cards, Presence only lit", cards.contains(
                "value=\"PRESENCE\" aria-pressed=\"true\"><b>Presence only</b><span>${CameraPrefs.USE_PRESENCE_LINE}</span>"))
            assertTrue(cards.contains("value=\"PRESENCE_AND_SNAPSHOTS\" aria-pressed=\"false\"><b>Presence + camera</b>"))
            assertFalse("nothing dimmed", cards.contains("disabled"))
        }
    }

    @Test fun anAdministratorPicksPresenceAndCameraAndTheLogSaysSo() {
        val (def, f) = manage(touch = false)
        val b = admin(f)
        assertEquals("/camera", pick(f, b, CameraUse.PRESENCE_AND_SNAPSHOTS).headers["Location"])
        assertEquals(CameraUse.PRESENCE_AND_SNAPSHOTS, def.prefs.use)
        assertTrue(f.events.toString(), f.events.contains("Alex set Camera › Camera use to Presence + camera"))
        assertTrue("lit now", b.get("/camera").text().contains("value=\"PRESENCE_AND_SNAPSHOTS\" aria-pressed=\"true\""))
    }

    @Test fun thePanelsCodeChangesItTooAsOnThePanel() {
        val (def, f) = manage(touch = false)
        val b = withCode(f)
        assertFalse("not dimmed", b.get("/camera").text().substringAfter("<form class=\"ucs\"").substringBefore("</form>").contains("disabled"))
        assertEquals("/camera", pick(f, b, CameraUse.PRESENCE_AND_SNAPSHOTS).headers["Location"])
        assertEquals(CameraUse.PRESENCE_AND_SNAPSHOTS, def.prefs.use)
        assertTrue(f.events.toString(), f.events.contains("Code sign-in set Camera › Camera use to Presence + camera"))
    }

    @Test fun underPrivacyTheUseIsRefusedAsThePanelsCardsAreDimmed() {
        val (def, f) = manage(touch = true)
        def.prefs.privacy = true
        val r = pick(f, admin(f), CameraUse.PRESENCE_AND_SNAPSHOTS)
        assertEquals(422, r.status)
        assertTrue(r.text().contains(CameraPage.PRIVACY_ON))
        assertEquals(CameraUse.PRESENCE, def.prefs.use)
    }

    @Test fun needsPresenceAndCameraLinksToTheCardsAndSoDoesTheRefusal() {
        val (_, f) = manage(touch = false)
        val b = admin(f)
        val link = "<a class=\"see\" href=\"#i-camera-use\">${CameraPage.NEEDS_CAMERA}</a>"
        assertTrue("the stream's line", b.get("/camera").text().contains(link))
        val r = b.post("/settings", mapOf("csrf" to b.csrf(), "section" to CameraPane.ID, "key" to "ONVIF stream/Stream over ONVIF and RTSP", "value" to "on"))
        assertEquals(422, r.status)
        assertTrue("the red line after the refused switch", r.text().contains("<p class=\"err\" role=\"alert\">$link</p>"))
    }

    @Test fun theRefusalLinksToWhatRefusedItNotWhatThePagePostedAgainst() {
        val (def, f) = manage(touch = false)
        val b = admin(f)
        val csrf = b.csrf()
        val build = f.statusFn!!
        var flip = true
        // Privacy turns on between the page the POST reads and the save: the save refuses for privacy.
        f.statusFn = { build().also { if (flip) { flip = false; def.prefs.privacy = true } } }
        val r = b.post("/settings", mapOf("csrf" to csrf, "section" to CameraPane.ID, "key" to "ONVIF stream/Stream over ONVIF and RTSP", "value" to "on"))
        assertEquals(422, r.status)
        assertTrue(r.text(), r.text().contains("<p class=\"err\" role=\"alert\"><a class=\"see\" href=\"#i-privacy-camera-privacy\">${CameraPage.PRIVACY_ON}</a></p>"))
    }
}
