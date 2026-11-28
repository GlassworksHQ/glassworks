/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.HaCodeDialog
import dev.glassworks.engine.vitro.cards.EntitiesCardView
import dev.glassworks.engine.vitro.cards.EntityRowControls
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPopupMenu

/**
 * The entities rows HA draws with a control (W5): the number slider and box, the dropdowns, and the Run, Cancel,
 * Activate, Press, Lock and Unlock buttons, each sending the service call HA's `hui-*-entity-row` sends. Their look is
 * the parity cases' (`entities-controls`, `entities-number`, `entities-select`, `entities-buttons`, `entities-lock`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EntityRowControlsTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()
    private val calls = ArrayList<Triple<String, String, JSONObject?>>()
    private val moreInfo = ArrayList<String>()
    private val ws = ArrayList<JSONObject>()
    private var services = JSONObject()
    private var wsAnswers = true
    private var now = NOW

    @After fun forget() = HaDisplay.reset()

    private fun ctx() = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN },
        callService = { d, s, data, _ -> calls += Triple(d, s, data) },
        callWs = { msg, done -> ws += msg; if (wsAnswers) done(true, services, null) else done(false, null, JSONObject().put("code", "disconnected")) },
        navigate = {}, openMoreInfo = { moreInfo += it },
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        now = { now },
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun shown(v: View, root: View): Boolean {
        var x: View? = v
        while (x != null && x !== root) {
            if (x.visibility != View.VISIBLE) return false
            x = x.parent as? View
        }
        return true
    }
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it !is EditText && shown(it, v) && it.text.isNotEmpty() }.map { it.text.toString() }
    private fun button(v: View, label: String) = all(v).filterIsInstance<TextView>().first { it.text == label && shown(it, v) }
    private fun put(id: String, state: String, vararg attrs: Pair<String, Any?>) =
        store.putLocal(EntityState(id, state, mapOf(*attrs), lastChanged = (NOW - 5 * 60_000) / 1000.0))
    private fun entities(vararg rows: String) =
        EntitiesCardView(ctx(), LovelaceParser.parseCard(JSONObject("""{"type": "entities", "entities": [${rows.joinToString(",")}]}""")))
    private fun layout(v: View, wDp: Int) {
        val dp = activity.resources.displayMetrics.density
        v.measure(View.MeasureSpec.makeMeasureSpec((wDp * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }
    private fun touch(v: View, action: Int, x: Float) {
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, action, x, v.height / 2f, 0)
        v.dispatchTouchEvent(e)
        e.recycle()
    }
    private fun calls() = calls.map { "${it.first}.${it.second}" }

    // ---- numbers ----

    @Test fun aSliderRowSendsTheSteppedValueWhenLetGo() {
        put("input_number.volume", "35.0", "min" to 0, "max" to 100, "step" to 5, "mode" to "slider", "friendly_name" to "Volume")
        val c = entities("\"input_number.volume\"")
        layout(c, 500)
        assertEquals(listOf("Volume", "35"), texts(c))
        val slider = all(c).filterIsInstance<EntityRowControls.HaRowSlider>().single()
        val inset = 8 * activity.resources.displayMetrics.density
        // 62 % of the track: 60 in steps of 5.
        val x = inset + (slider.width - 2 * inset) * 0.62f
        touch(slider, MotionEvent.ACTION_DOWN, x)
        touch(slider, MotionEvent.ACTION_MOVE, x)
        assertTrue("nothing is sent mid-drag", calls.isEmpty())
        touch(slider, MotionEvent.ACTION_UP, x)
        idle()
        assertEquals(listOf("input_number.set_value"), calls())
        assertEquals("input_number.volume", calls[0].third!!.getString("entity_id"))
        assertEquals(60L, calls[0].third!!.get("value"))
        assertTrue("the row didn't open more-info", moreInfo.isEmpty())
    }

    @Test fun aNumberSliderKeepsDecimalStepsAndANumberWithoutAValueShowsADash() {
        put("number.offset", "0.5", "min" to -2, "max" to 2, "step" to 0.5, "mode" to "auto", "friendly_name" to "Offset")
        put("number.gone", "unknown", "min" to 0, "max" to 10, "step" to 1, "mode" to "slider", "friendly_name" to "Gone")
        val c = entities("\"number.offset\"", "\"number.gone\"")
        layout(c, 500)
        assertEquals(listOf("Offset", "0.5", "Gone", "—"), texts(c))
        val slider = all(c).filterIsInstance<EntityRowControls.HaRowSlider>().first()
        val inset = 8 * activity.resources.displayMetrics.density
        touch(slider, MotionEvent.ACTION_DOWN, inset)
        touch(slider, MotionEvent.ACTION_UP, inset + (slider.width - 2 * inset) * 0.13f)
        idle()
        assertEquals(listOf("number.set_value"), calls())
        assertEquals(-1.5, calls[0].third!!.getDouble("value"), 0.0)
    }

    @Test fun aSliderKeepsTheMinimumsDecimals() {
        put("input_number.half", "1.5", "min" to 0.5, "max" to 3.5, "step" to 1, "mode" to "slider", "friendly_name" to "Half")
        val c = entities("\"input_number.half\"")
        layout(c, 500)
        val slider = all(c).filterIsInstance<EntityRowControls.HaRowSlider>().single()
        val inset = 8 * activity.resources.displayMetrics.density
        touch(slider, MotionEvent.ACTION_DOWN, inset)
        touch(slider, MotionEvent.ACTION_UP, inset)
        idle()
        assertEquals(0.5, calls.single().third!!.getDouble("value"), 0.0)
        touch(slider, MotionEvent.ACTION_DOWN, inset + (slider.width - 2 * inset) * 0.68f)
        touch(slider, MotionEvent.ACTION_UP, inset + (slider.width - 2 * inset) * 0.68f)
        idle()
        assertEquals(2.5, calls.last().third!!.getDouble("value"), 0.0)
    }

    @Test fun aSliderRowHidesItsStateOnARowOf300PxOrLess() {
        put("input_number.volume", "35", "min" to 0, "max" to 100, "step" to 1, "mode" to "slider", "friendly_name" to "Volume")
        val c = entities("\"input_number.volume\"")
        // 300 px of row content: the card's 2 × 16 px padding and its border around it.
        layout(c, 334)
        assertEquals(listOf("Volume"), texts(c))
        layout(c, 360)
        assertEquals(listOf("Volume", "35"), texts(c))
    }

    @Test fun aBoxSendsTheTypedValueOnDone() {
        put("input_number.away", "16.5", "min" to 10, "max" to 25, "step" to 0.5, "mode" to "box", "unit_of_measurement" to "°C", "friendly_name" to "Away")
        val c = entities("\"input_number.away\"")
        layout(c, 500)
        val input = all(c).filterIsInstance<EditText>().single()
        assertEquals("16.5", input.text.toString())
        assertTrue(texts(c).contains("°C"))
        input.requestFocus()
        input.setText("18")
        input.onEditorAction(EditorInfo.IME_ACTION_DONE)
        idle()
        assertEquals(listOf("input_number.set_value"), calls())
        assertEquals(18L, calls[0].third!!.get("value"))
        // The value it shows again sends nothing.
        calls.clear()
        input.requestFocus()
        input.setText("18")
        input.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertTrue(calls.isEmpty())
    }

    @Test fun anUntouchedBoxSendsNothingWhenLeftAfterHaChangedTheValue() {
        put("input_number.away", "20", "min" to 10, "max" to 25, "step" to 1, "mode" to "box", "friendly_name" to "Away")
        val c = entities("\"input_number.away\"")
        layout(c, 500)
        val input = all(c).filterIsInstance<EditText>().single()
        input.requestFocus()
        put("input_number.away", "25", "min" to 10, "max" to 25, "step" to 1, "mode" to "box", "friendly_name" to "Away")
        idle()
        assertEquals("the focused field keeps what it showed", "20", input.text.toString())
        input.clearFocus()
        idle()
        assertTrue("an untouched field writes nothing back", calls.isEmpty())
        assertEquals("25", input.text.toString())
        // A real edit still sends once.
        input.requestFocus()
        input.setText("12")
        input.clearFocus()
        idle()
        assertEquals(listOf("input_number.set_value"), calls())
        assertEquals(12L, calls[0].third!!.get("value"))
    }

    @Test fun numberModesAndBoxWidthsFollowHa() {
        fun n(domain: String, vararg a: Pair<String, Any?>) = EntityState("$domain.x", "1", mapOf(*a))
        assertTrue(EntityRowControls.showsSlider(n("input_number", "mode" to "slider")))
        assertFalse(EntityRowControls.showsSlider(n("input_number", "mode" to "box")))
        assertTrue(EntityRowControls.showsSlider(n("number", "mode" to "auto", "min" to 1, "max" to 5, "step" to 1)))
        assertFalse("more than 256 steps", EntityRowControls.showsSlider(n("number", "mode" to "auto", "min" to 0, "max" to 1000, "step" to 1)))
        assertFalse(EntityRowControls.showsSlider(n("number", "mode" to "box")))
        // Chrome's number input: digits of min/max (a sign counts), a point and the decimals.
        assertEquals(4, EntityRowControls.inputChars(n("number", "min" to 10, "max" to 25, "step" to 0.5)))
        assertEquals(3, EntityRowControls.inputChars(n("number", "min" to 0, "max" to 120, "step" to 5)))
        assertEquals(4, EntityRowControls.inputChars(n("number", "min" to 0, "max" to 1000, "step" to 1)))
        assertEquals(6, EntityRowControls.inputChars(n("number", "min" to -10, "max" to 10, "step" to 0.25)))
        assertEquals(20, EntityRowControls.inputChars(n("number", "max" to 10)))
        assertEquals("16.5", EntityRowControls.jsNumberText(16.5))
        assertEquals("15", EntityRowControls.jsNumberText(15.0))
        assertEquals("-1.25", EntityRowControls.jsNumberText(-1.25))
        assertEquals("NaN", EntityRowControls.jsNumberText(Double.NaN))
    }

    // ---- dropdowns ----

    @Test fun aDropdownSelectsAnotherOptionOnTheEntitysDomain() {
        put("input_select.mode", "Evening", "options" to listOf("Day", "Evening", "Night"), "friendly_name" to "House mode")
        put("select.dryer", "Cotton", "options" to listOf("Cotton", "Wool"), "friendly_name" to "Dryer")
        val c = entities("\"input_select.mode\"", "{\"entity\": \"select.dryer\", \"name\": \"Program\"}")
        layout(c, 500)
        // The name is the field's label, over the value: HA's `hide-name`.
        assertEquals(listOf("House mode", "Evening", "Program", "Cotton"), texts(c))
        // The row's name moved into the field: TalkBack still reads the row (which opens More Info) by it.
        assertEquals("House mode", (button(c, "House mode").parent.parent as View).contentDescription)
        (button(c, "House mode").parent as View).performClick()
        val menu = ShadowPopupMenu.getLatestPopupMenu()
        assertEquals(listOf("Day", "Evening", "Night"), (0 until menu.menu.size()).map { menu.menu.getItem(it).title.toString() })
        shadowOf(menu).onMenuItemClickListener.onMenuItemClick(menu.menu.getItem(2))
        assertEquals(listOf("input_select.select_option"), calls())
        assertEquals("Night", calls[0].third!!.getString("option"))
        // The current option sends nothing.
        shadowOf(menu).onMenuItemClickListener.onMenuItemClick(menu.menu.getItem(1))
        assertEquals(1, calls.size)
        (button(c, "Program").parent as View).performClick()
        val second = ShadowPopupMenu.getLatestPopupMenu()
        shadowOf(second).onMenuItemClickListener.onMenuItemClick(second.menu.getItem(1))
        assertEquals("select.select_option", calls().last())
        assertEquals("Wool", calls.last().third!!.getString("option"))
        assertTrue(moreInfo.isEmpty())
    }

    @Test fun anUnavailableDropdownShowsTheRawStateAndDoesntOpen() {
        put("input_select.guest", "unavailable", "friendly_name" to "Guest scene")
        val c = entities("\"input_select.guest\"")
        layout(c, 500)
        assertEquals(listOf("Guest scene", "unavailable"), texts(c))
        val field = button(c, "Guest scene").parent as View
        assertFalse(field.isEnabled)
        assertEquals(0.5f, field.alpha, 0f)
    }

    // ---- buttons ----

    @Test fun aScriptRunsOrOpensMoreInfoWhenItHasFields() {
        put("script.goodnight", "off", "mode" to "single", "current" to 0, "friendly_name" to "Goodnight")
        put("script.greet", "off", "mode" to "single", "current" to 0, "friendly_name" to "Greet")
        services = JSONObject("""{"script": {"goodnight": {"fields": {}}, "greet": {"fields": {"who": {"required": true}}}}}""")
        val c = entities("\"script.goodnight\"", "{\"entity\": \"script.greet\", \"action_name\": \"Say hi\"}")
        layout(c, 500)
        assertEquals(listOf("Goodnight", "Run", "Greet", "Say hi"), texts(c))
        button(c, "Run").performClick()
        idle()
        assertEquals("get_services", ws.single().getString("type"))
        assertEquals(listOf("script.turn_on"), calls())
        assertEquals("script.goodnight", calls[0].third!!.getString("entity_id"))
        button(c, "Say hi").performClick()
        idle()
        assertEquals("a script with fields opens More Info, as HA does", listOf("script.greet"), moreInfo)
        assertEquals(1, calls.size)
    }

    @Test fun aScriptReloadedWithFieldsIsNeverRunOnAnOlderNoFieldsAnswer() {
        put("script.greet", "off", "mode" to "single", "current" to 0, "friendly_name" to "Greet")
        services = JSONObject("""{"script": {"greet": {"fields": {}}}}""")
        val c = entities("\"script.greet\"")
        layout(c, 500)
        button(c, "Run").performClick()
        idle()
        assertEquals(listOf("script.turn_on"), calls())
        // HA reloads the script with a field, within the cache's minute: "no fields" is asked again, never reused.
        services = JSONObject("""{"script": {"greet": {"fields": {"who": {"required": true}}}}}""")
        button(c, "Run").performClick()
        idle()
        assertEquals(2, ws.count { it.getString("type") == "get_services" })
        assertEquals("no second turn_on: the form opens", 1, calls.size)
        assertEquals(listOf("script.greet"), moreInfo)
    }

    @Test fun aScriptWhoseFieldsAreUnknownNeverRunsFromItsRow() {
        put("script.greet", "off", "mode" to "single", "current" to 0, "friendly_name" to "Greet")
        wsAnswers = false
        val c = entities("\"script.greet\"")
        layout(c, 500)
        button(c, "Run").performClick()
        idle()
        assertTrue("no turn_on without knowing its fields", calls.isEmpty())
        assertEquals(listOf("script.greet"), moreInfo)
    }

    /** A dashboard on a VitroView with this test's context, and HA's More Info dialog handed off to [inFrontend]. */
    private fun dashboardView(inFrontend: ((String) -> Unit)?): VitroView {
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        vv.contextFactory = { ctx() }
        if (inFrontend != null) vv.onMoreInfoInFrontend = { _, id, _, _ -> inFrontend(id) }
        val card = JSONObject("""{"type": "entities", "entities": ["script.greet", "script.goodnight"]}""")
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject().put("views", org.json.JSONArray().put(JSONObject().put("title", "Home").put("cards", org.json.JSONArray().put(card))))), null)
        activity.setContentView(vv)
        idle()
        return vv
    }

    @Test fun aScriptLookupAnsweredAfterANewerMoreInfoDoesNothing() {
        put("script.greet", "off", "mode" to "single", "current" to 0, "friendly_name" to "Greet")
        put("script.goodnight", "off", "mode" to "single", "current" to 0, "friendly_name" to "Goodnight")
        services = JSONObject("""{"script": {"goodnight": {}, "greet": {"fields": {"who": {"required": true}}}}}""")
        val handedOff = ArrayList<String>()
        val vv = dashboardView { handedOff += it }
        // The greet lookup is still on its way when the user opens goodnight's More Info instead.
        vv.openMoreInfo("script.greet")
        vv.openMoreInfo("script.goodnight")
        idle()
        assertTrue("greet's late answer doesn't replace the newer choice", handedOff.isEmpty())
        assertNotNull("goodnight's sheet", vv.openSheet)
    }

    @Test fun aScriptLookupAnsweredAfterTheSheetClosedOpensNothing() {
        // VitroEngine's dispose() closes More Info: a lookup still out then answers into nothing, so no sheet is
        // built on a disposed page.
        put("script.goodnight", "off", "mode" to "single", "current" to 0, "friendly_name" to "Goodnight")
        services = JSONObject("""{"script": {"goodnight": {}}}""")
        val handedOff = ArrayList<String>()
        val vv = dashboardView { handedOff += it }
        vv.openMoreInfo("script.goodnight")
        vv.closeMoreInfo()
        idle()
        assertNull("no sheet after the close", vv.openSheet)
        assertTrue(handedOff.isEmpty())
    }

    @Test fun moreInfoForAScriptWithFieldsIsHasOwnDialog() {
        put("script.greet", "off", "mode" to "single", "current" to 0, "friendly_name" to "Greet")
        put("script.goodnight", "off", "mode" to "single", "current" to 0, "friendly_name" to "Goodnight")
        services = JSONObject("""{"script": {"goodnight": {}, "greet": {"fields": {"who": {"required": true}}}}}""")
        val handedOff = ArrayList<String>()
        val vv = dashboardView { handedOff += it }
        vv.openMoreInfo("script.greet")
        idle()
        assertEquals("HA's dialog has the fields' form", listOf("script.greet"), handedOff)
        assertNull("Vitro's sheet doesn't open", vv.openSheet)
        vv.openMoreInfo("script.goodnight")
        idle()
        assertEquals(1, handedOff.size)
        assertTrue("a script without fields gets Vitro's sheet", vv.openSheet != null)
    }

    @Test fun theSheetsRunNeverStartsAScriptWithFields() {
        put("script.greet", "off", "mode" to "single", "current" to 0, "friendly_name" to "Greet")
        put("script.goodnight", "off", "mode" to "single", "current" to 0, "friendly_name" to "Goodnight")
        services = JSONObject("""{"script": {"goodnight": {}, "greet": {"fields": {"who": {"required": true}}}}}""")
        // No page to hand HA's dialog to: Vitro's sheet opens, and its Run must not start the script without its fields.
        val vv = dashboardView(inFrontend = null)
        vv.openMoreInfo("script.greet")
        idle()
        all(vv.openSheet!!).filterIsInstance<TextView>().first { it.text == "Run" }.performClick()
        idle()
        assertTrue("script.turn_on without the fields: ${calls()}", calls.isEmpty())
        vv.openMoreInfo("script.goodnight")
        idle()
        all(vv.openSheet!!).filterIsInstance<TextView>().first { it.text == "Run" }.performClick()
        idle()
        assertEquals(listOf("script.turn_on"), calls())
    }

    @Test fun aRunningScriptCancelsAndAQueuedOneCanRunAgain() {
        put("script.water", "on", "mode" to "single", "current" to 1, "friendly_name" to "Water")
        put("script.chime", "on", "mode" to "queued", "current" to 2, "max" to 10, "friendly_name" to "Chime")
        val c = entities("\"script.water\"", "\"script.chime\"")
        layout(c, 500)
        assertEquals(listOf("Water", "Cancel", "Chime", "Cancel 2", "Run"), texts(c))
        button(c, "Cancel").performClick()
        button(c, "Cancel 2").performClick()
        button(c, "Run").performClick()
        idle()
        assertEquals(listOf("script.turn_off", "script.turn_off", "script.turn_on"), calls())
        assertEquals(listOf("script.water", "script.chime", "script.chime"), calls.map { it.third!!.getString("entity_id") })
    }

    @Test fun sceneButtonAndInputButtonRowsPressTheirService() {
        put("scene.movie", "unknown", "friendly_name" to "Movie")
        put("button.restart", "unknown", "friendly_name" to "Restart")
        put("input_button.bell", "unknown", "friendly_name" to "Bell")
        put("button.gone", "unavailable", "friendly_name" to "Gone")
        val c = entities("{\"entity\": \"scene.movie\", \"action_name\": \"Start\"}", "\"button.restart\"", "\"input_button.bell\"", "\"button.gone\"")
        layout(c, 500)
        assertEquals(listOf("Movie", "Start", "Restart", "Press", "Bell", "Press", "Gone", "Press"), texts(c))
        button(c, "Start").performClick()
        val presses = all(c).filterIsInstance<TextView>().filter { it.text == "Press" }
        presses.forEach { it.performClick() }
        assertEquals(listOf("scene.turn_on", "button.press", "input_button.press"), calls())
        assertFalse("an unavailable entity's button is disabled", presses.last().isEnabled)
        assertTrue("a button never opens the row's more-info", moreInfo.isEmpty())
    }

    @Test fun aLockRowUnlocksWhileLockedAndAsksForACode() {
        put("lock.front", "locked", "friendly_name" to "Front")
        put("lock.shed", "jammed", "friendly_name" to "Shed")
        put("lock.safe", "locked", "code_format" to "^\\d{4}$", "friendly_name" to "Safe")
        val c = entities("\"lock.front\"", "\"lock.shed\"", "\"lock.safe\"")
        layout(c, 500)
        assertEquals(listOf("Front", "Unlock", "Shed", "Lock", "Safe", "Unlock"), texts(c))
        all(c).filterIsInstance<TextView>().filter { it.text == "Unlock" }.first().performClick()
        button(c, "Lock").performClick()
        assertEquals(listOf("lock.unlock", "lock.lock"), calls())
        all(c).filterIsInstance<TextView>().filter { it.text == "Unlock" }.last().performClick()
        idle()
        // HA's code dialog for a lock: its text version (a lock's code_format is a pattern), titled and confirmed "Unlock".
        val dialog = HaCodeDialog.lastView!!
        assertFalse(dialog.numeric)
        assertTrue("the prompt reads HA's code label", "Code" in all(dialog).filterIsInstance<TextView>().map { it.text.toString() })
        assertEquals("Unlock", dialog.submitButton!!.contentDescription)
        dialog.codeInput.setText("1234")
        dialog.submitButton!!.performClick()
        idle()
        assertEquals("lock.unlock", calls().last())
        assertEquals("1234", calls.last().third!!.getString("code"))
        assertEquals("lock.safe", calls.last().third!!.getString("entity_id"))
        assertEquals("the registry entry was read first", "config/entity_registry/get", ws.last().getString("type"))
        // A default code in the lock's registry options: no prompt, and no code sent (HA's lock uses its own).
        services = JSONObject("""{"options": {"lock": {"default_code": "9999"}}}""")
        HaCodeDialog.lastView = null
        all(c).filterIsInstance<TextView>().filter { it.text == "Unlock" }.last().performClick()
        idle()
        assertNull("no prompt", HaCodeDialog.lastView)
        assertEquals("lock.unlock", calls().last())
        assertFalse(calls.last().third!!.has("code"))
    }

    @Test fun aRowWithAControlStillOpensMoreInfoFromItsName() {
        put("script.goodnight", "off", "mode" to "single", "friendly_name" to "Goodnight")
        val c = entities("\"script.goodnight\"")
        layout(c, 500)
        (button(c, "Goodnight").parent as View).performClick()
        assertEquals(listOf("script.goodnight"), moreInfo)
        assertTrue(calls.isEmpty())
    }

    // ---- secondary_info ----

    @Test fun secondaryInfoFollowsHasStateDisplay() {
        val now = NOW
        val auto = EntityState("automation.porch", "on", mapOf("last_triggered" to "2026-11-12T13:03:00+00:00"))
        assertEquals("3 hours ago", EntityRowControls.secondaryText(auto, "last-triggered", now))
        val never = EntityState("script.goodnight", "off", mapOf("last_triggered" to null))
        assertEquals("never triggered: the state", "Off", EntityRowControls.secondaryText(never, "last-triggered", now))
        assertEquals("switch.kettle", EntityRowControls.secondaryText(EntityState("switch.kettle", "off", emptyMap()), "entity-id", now))
        assertEquals("65%", EntityRowControls.secondaryText(EntityState("light.desk", "on", mapOf("brightness" to 166)), "brightness", now))
        assertEquals("Off", EntityRowControls.secondaryText(EntityState("light.desk", "off", mapOf("brightness" to null)), "brightness", now))
        assertEquals("40%", EntityRowControls.secondaryText(EntityState("cover.blind", "open", mapOf("current_position" to 40)), "position", now))
        assertEquals("10%", EntityRowControls.secondaryText(EntityState("cover.blind", "open", mapOf("current_tilt_position" to 10)), "tilt-position", now))
        assertEquals("no position: the state", "21 °C",
            EntityRowControls.secondaryText(EntityState("sensor.t", "21", mapOf("unit_of_measurement" to "°C")), "position", now))
        assertNull(EntityRowControls.secondaryText(auto, "none", now))
        assertNull(EntityRowControls.secondaryText(auto, null, now))
    }

    @Test fun secondaryInfoAreaIsTheAreasName() {
        // HA's migrateSecondaryInfoConfig: `area` is state-display's `area_name`, formatEntityName's area part (the
        // entity's own area, else its device's); with none, the state.
        HaDisplay.applyAreas(org.json.JSONArray("""[{"area_id": "porch", "name": "Porch"}]"""))
        HaDisplay.applyDevices(org.json.JSONArray("""[{"id": "d-lamp", "name": "Lantern", "area_id": "porch"}]"""))
        HaDisplay.applyRegistry(JSONObject().put("entities", org.json.JSONArray("""[{"ei": "light.porch", "di": "d-lamp"}, {"ei": "switch.kettle"}]""")))
        assertEquals("Porch", EntityRowControls.secondaryText(EntityState("light.porch", "on", emptyMap()), "area", NOW))
        assertEquals("no area: the state", "Off", EntityRowControls.secondaryText(EntityState("switch.kettle", "off", emptyMap()), "area", NOW))
        // An attribute called `area` isn't what it reads.
        assertEquals("Off", EntityRowControls.secondaryText(EntityState("switch.kettle", "off", mapOf("area" to "Attic")), "area", NOW))
        assertTrue("area" in EntityRowControls.SECONDARY_INFO)
    }

    @Test fun aRowShowsItsSecondaryLine() {
        store.putLocal(EntityState("switch.kettle", "off", mapOf("friendly_name" to "Kettle"), lastChanged = (NOW - 300_000) / 1000.0))
        val c = entities("{\"entity\": \"switch.kettle\", \"secondary_info\": \"entity-id\"}")
        layout(c, 500)
        assertEquals(listOf("Kettle", "switch.kettle"), texts(c))
    }

    @Test fun aRelativeSecondaryLineKeepsCountingWhileShown() {
        store.putLocal(EntityState("automation.porch", "on", mapOf("friendly_name" to "Porch", "last_triggered" to "2026-11-12T16:07:00+00:00")))
        val c = entities("{\"entity\": \"automation.porch\", \"secondary_info\": \"last-triggered\"}")
        activity.setContentView(c)
        layout(c, 500)
        assertTrue(texts(c).toString(), texts(c).contains("1 minute ago"))
        // No state change: only the clock moves, as on an idle automation.
        now = NOW + 2 * 3_600_000L
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertTrue(texts(c).toString(), texts(c).contains("2 hours ago"))
    }

    private companion object {
        /** 2026-11-12 16:08 UTC, the parity harness's instant. */
        const val NOW = 1_794_499_680_000L
    }
}
