/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.HaButton
import dev.glassworks.engine.vitro.cards.MarkedLite
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.cards.MoreInfoUpdateView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** More Info of an update: Skip, Clear skipped and Update call HA's services; the notes come once; a failure shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MoreInfoUpdateTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val services = ArrayList<Pair<String, JSONObject?>>()
    private val ws = ArrayList<Pair<JSONObject, (Boolean, Any?, JSONObject?) -> Unit>>()
    private val store = EntityStore()

    private fun sheet(state: String, features: Int, vararg attrs: Pair<String, Any?>): MoreInfoSheet {
        store.putLocal(EntityState(ID, state, mapOf(
            "supported_features" to features, "friendly_name" to "Firmware", "title" to "Hallway Display",
            "installed_version" to "1.0", "latest_version" to "1.1", "in_progress" to false,
        ) + attrs))
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { d, s, data, _ -> services += "$d.$s" to data },
            navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            callWs = { msg, done -> ws += msg to done },
        )
        return MoreInfoSheet(ctx) {}.also { it.show(ID) }
    }

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun MoreInfoSheet.button(label: String) = all(this).filterIsInstance<HaButton>().single { it.contentDescription == label && it.visibility == View.VISIBLE }
    /** Visible up to the sheet (off a window, `isShown` is always false). */
    private fun View.visible(): Boolean {
        var v: View? = this
        while (v != null) {
            if (v.visibility != View.VISIBLE) return false
            v = v.parent as? View
        }
        return true
    }
    private fun MoreInfoSheet.shown(text: String) = all(this).filterIsInstance<TextView>().any { it.text.toString() == text && it.visible() }
    private fun idle() = ShadowLooper.idleMainLooper()

    @Test
    fun skipAndUpdateCallTheirServicesWithTheVersionWhenItInstallsOne() {
        val s = sheet("on", INSTALL or SPECIFIC_VERSION)
        s.button("Skip").performClick()
        assertEquals("update.skip", services.single().first)
        s.button("Update").performClick()
        val call = ws.single { it.first.optString("type") == "call_service" }.first
        assertEquals("install", call.getString("service"))
        assertEquals(ID, call.getJSONObject("service_data").getString("entity_id"))
        assertEquals("1.1", call.getJSONObject("service_data").getString("version"))
        assertFalse("no backup option here", call.getJSONObject("service_data").has("backup"))
    }

    @Test
    fun aSkippedVersionOffersClearSkippedAndStillInstalls() {
        val s = sheet("off", INSTALL, "skipped_version" to "1.1")
        assertTrue("the row shows the skipped version", s.shown("1.1"))
        s.button("Clear skipped").performClick()
        assertEquals("update.clear_skipped", services.single().first)
        assertTrue(s.button("Update").isEnabled)
    }

    @Test
    fun upToDateNothingToSkipOrInstall() {
        val s = sheet("off", INSTALL)
        assertFalse(s.button("Skip").isEnabled)
        assertFalse(s.button("Update").isEnabled)
    }

    @Test
    fun installingShowsItsProgressAndHoldsTheButtons() {
        val s = sheet("on", INSTALL or PROGRESS, "in_progress" to true, "update_percentage" to 40)
        assertTrue(s.shown("Installing (40%)"))
        assertFalse(s.button("Skip").isEnabled)
        assertFalse("loading", s.button("Update").isEnabled)
    }

    @Test
    fun aFailedInstallSaysWhy() {
        val s = sheet("on", INSTALL)
        s.button("Update").performClick()
        ws.single { it.first.optString("type") == "call_service" }.second(false, null, JSONObject().put("code", "home_assistant_error").put("message", "Device offline"))
        idle()
        assertTrue(all(s).filterIsInstance<TextView>().any { it.visible() && "Device offline" in it.text })
    }

    @Test
    fun theReleaseNotesAreAskedForOnceAndDrawn() {
        val s = sheet("on", INSTALL or RELEASE_NOTES)
        val asked = ws.filter { it.first.optString("type") == "update/release_notes" }
        assertEquals(1, asked.size)
        assertEquals(ID, asked.single().first.getString("entity_id"))
        asked.single().second(true, "Faster start\nand less flicker.", null)
        idle()
        // HA's ha-markdown here has no `breaks`: one paragraph, one line.
        assertTrue(all(s).filterIsInstance<TextView>().any { it.visible() && it.text.toString().startsWith("Faster start and less flicker.") })
        store.putLocal(store[ID]!!.copy(lastChanged = 5.0))
        idle()
        assertEquals("not asked again on a state change", 1, ws.count { it.first.optString("type") == "update/release_notes" })
    }

    @Test
    fun theStateTextIsHasComputeUpdateStateDisplay() {
        fun st(state: String, vararg a: Pair<String, Any?>) = EntityState(ID, state, mapOf<String, Any?>("latest_version" to "1.1") + a)
        assertEquals("Installing (40%)", MoreInfoUpdateView.updateStateText(st("on", "in_progress" to true, "supported_features" to PROGRESS, "update_percentage" to 40)))
        assertEquals("Installing", MoreInfoUpdateView.updateStateText(st("on", "in_progress" to true)))
        assertEquals("1.1", MoreInfoUpdateView.updateStateText(st("off", "skipped_version" to "1.1")))
        assertEquals(null, MoreInfoUpdateView.releaseUrl(st("on", "release_url" to "javascript:alert(1)")))
        assertEquals("https://example.com/r", MoreInfoUpdateView.releaseUrl(st("on", "release_url" to "https://example.com/r")))
    }

    @Test
    fun markdownWithoutBreaksJoinsLinesAndKeepsHardBreaks() {
        assertEquals("<p>one\ntwo</p>\n", MarkedLite.toHtml("one\ntwo", breaks = false))
        assertEquals("<p>one<br>two</p>\n", MarkedLite.toHtml("one  \ntwo", breaks = false))
        assertEquals("<p>one<br>two</p>\n", MarkedLite.toHtml("one\ntwo"))
    }

    private companion object {
        const val ID = "update.hallway_display_firmware"
        const val INSTALL = 1
        const val SPECIFIC_VERSION = 2
        const val PROGRESS = 4
        const val RELEASE_NOTES = 16
    }
}
