/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.AlarmModesSelect
import dev.glassworks.engine.vitro.cards.EnterCodeView
import dev.glassworks.engine.vitro.cards.HaCodeDialog
import dev.glassworks.engine.vitro.cards.KeypadGrid
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * HA's code dialog (`dialog-enter-code`, frontend 20261028.5) drawn natively ([HaCodeDialog]), and the alarm's More
 * Info that opens it (`more-info-alarm_control_panel`): the keypad for a number code, the field and footer for a text
 * one, HA's grid order, TalkBack names, and the modes select that asks for a code before it arms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp")
class HaCodeDialogTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()
    private val calls = ArrayList<Triple<String, String, JSONObject?>>()
    private val ws = ArrayList<JSONObject>()
    private var wsAnswer: (JSONObject) -> Any? = { null }

    @Before fun forget() {
        HaCodeDialog.lastShown = null
        HaCodeDialog.lastView = null
    }

    private fun ctx() = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN },
        callService = { d, s, data, _ -> calls += Triple(d, s, data) },
        callWs = { msg, done -> ws += msg; done(true, wsAnswer(msg), null) },
        navigate = {}, openMoreInfo = {},
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun shown(v: View, root: View): Boolean {
        var c: View? = v
        while (c != null && c !== root) {
            if (c.visibility != View.VISIBLE) return false
            c = c.parent as? View
        }
        return true
    }
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { shown(it, v) && it.text.isNotEmpty() }.map { it.text.toString() }

    private fun ask(format: String?, title: String = "Disarm"): Pair<MutableList<String>, IntArray> {
        val codes = ArrayList<String>()
        val cancels = IntArray(1)
        HaCodeDialog.ask(ctx(), format, title, title, onCancel = { cancels[0]++ }) { codes += it }
        idle()
        return codes to cancels
    }

    @Test fun aNumberCodeGetsHasKeypadInItsGridOrder() {
        val (codes, cancels) = ask("number")
        val view = HaCodeDialog.lastView!!
        assertTrue(HaCodeDialog.lastShown!!.isShowing)
        assertTrue(view.numeric)
        // The title, the field's label, the digits: no footer buttons for a number code.
        val shownTexts = texts(view)
        assertTrue(shownTexts.toString(), "Disarm" in shownTexts && "Code" in shownTexts)
        assertNull(view.cancelButton)
        // 1-9, then Clear, 0, Submit (HA's CSS grid pins Clear to row 4), three to a row.
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "clear", "0", "submit"), view.keys.keys.toList())
        assertEquals(3, view.columns)
        val k = view.keys
        val dp = activity.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((960 * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((480 * dp).toInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertEquals("56 px keys", 56f, k.getValue("5").width / dp, 0.5f)
        assertEquals("24 px apart", 80f, (k.getValue("2").left - k.getValue("1").left) / dp, 0.5f)
        assertEquals("Clear starts the last row", k.getValue("1").left, k.getValue("clear").left)
        assertEquals(k.getValue("clear").top, k.getValue("submit").top)
        // TalkBack names every key: the digit, Clear, and Submit by the action.
        assertEquals("7", k.getValue("7").contentDescription)
        assertEquals("Clear", k.getValue("clear").contentDescription)
        assertEquals("Disarm", k.getValue("submit").contentDescription)
        assertEquals("Close", view.closeButton.contentDescription)
        // A wall panel's window (480 px tall) is HA's bottom sheet, and the field isn't focused (no keyboard).
        assertFalse(view.wide)
        assertFalse(view.autofocus)
        assertFalse(view.codeInput.isFocused)

        // Clear is disabled until something is typed; the keys append, Clear empties.
        assertFalse(k.getValue("clear").isEnabled)
        k.getValue("1").performClick(); k.getValue("9").performClick()
        assertEquals("19", view.code)
        assertTrue(k.getValue("clear").isEnabled)
        k.getValue("clear").performClick()
        assertEquals("", view.code)
        assertFalse(k.getValue("clear").isEnabled)
        "2468".forEach { k.getValue(it.toString()).performClick() }
        assertEquals("masked", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, view.codeInput.inputType)
        k.getValue("submit").performClick()
        idle()
        assertEquals(listOf("2468"), codes)
        assertEquals(0, cancels[0])
        assertFalse(HaCodeDialog.lastShown!!.isShowing)
    }

    @Test fun theShowToggleUnmasksANumberCode() {
        ask("number")
        val view = HaCodeDialog.lastView!!
        val toggle = all(view).single { it.contentDescription == "Show password" }
        toggle.performClick()
        assertEquals(InputType.TYPE_CLASS_NUMBER, view.codeInput.inputType)
        assertEquals("Hide password", toggle.contentDescription)
    }

    @Test fun closingItCancelsOnceAndSendsNothing() {
        val (codes, cancels) = ask("number")
        val view = HaCodeDialog.lastView!!
        view.keys.getValue("4").performClick()
        view.closeButton.performClick()
        idle()
        assertEquals(1, cancels[0])
        assertTrue(codes.isEmpty())
        // The scrim too, in another dialog.
        val (codes2, cancels2) = ask("number")
        HaCodeDialog.lastView!!.performClick()
        idle()
        assertEquals(1, cancels2[0])
        assertTrue(codes2.isEmpty())
    }

    @Test fun aTextCodeGetsTheFieldAndHasFooter() {
        val (codes, cancels) = ask("text", title = "Unlock")
        val view = HaCodeDialog.lastView!!
        assertFalse(view.numeric)
        assertTrue("no keypad", view.keys.isEmpty())
        assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, view.codeInput.inputType)
        assertEquals("Cancel", view.cancelButton!!.contentDescription)
        assertEquals("Unlock", view.submitButton!!.contentDescription)
        view.codeInput.setText("open sesame")
        view.submitButton!!.performClick()
        idle()
        assertEquals(listOf("open sesame"), codes)
        assertEquals(0, cancels[0])
        val (_, cancels2) = ask("text")
        HaCodeDialog.lastView!!.cancelButton!!.performClick()
        idle()
        assertEquals(1, cancels2[0])
    }

    @Test fun aShortWindowLaysTheKeypadOutInSixColumns() {
        // `@media (max-height: 450px)`: Clear ends the first row.
        assertEquals(listOf("1", "2", "3", "4", "5", "clear", "6", "7", "8", "9", "0", "submit"), KeypadGrid.order(6))
    }

    @Config(qualifiers = "w1280dp-h800dp")
    @Test fun aLargeWindowGetsTheCentredDialogWithTheFieldFocused() {
        ask("number")
        val view = HaCodeDialog.lastView!!
        assertTrue(view.wide)
        assertTrue(view.autofocus)
    }

    // ---- The alarm's More Info ----

    private fun alarm(state: String, vararg attrs: Pair<String, Any?>) =
        store.putLocal(EntityState("alarm_control_panel.house", state, mapOf("supported_features" to 3, *attrs)))

    private fun sheet(): MoreInfoSheet {
        val sheet = MoreInfoSheet(ctx()) {}
        activity.setContentView(sheet)
        sheet.show("alarm_control_panel.house")
        idle()
        return sheet
    }

    @Test fun moreInfoOffersTheModesAndAsksForTheCodeBeforeArming() {
        alarm("disarmed", "code_format" to "number", "code_arm_required" to true)
        val s = sheet()
        val select = all(s).filterIsInstance<AlarmModesSelect>().single()
        assertEquals(listOf("armed_home", "armed_away", "disarmed"), select.options.keys.toList())
        assertEquals(listOf("Home", "Away", "Disarmed"), select.options.values.map { it.contentDescription.toString() })
        assertTrue("no keypad in the sheet itself", all(s).none { it is EnterCodeView })

        select.options.getValue("armed_away").performClick()
        idle()
        assertEquals("config/entity_registry/get", ws.single().getString("type"))
        assertTrue("nothing sent before the code", calls.isEmpty())
        val dialog = HaCodeDialog.lastView!!
        assertTrue(dialog.numeric)
        assertTrue("titled Arm", "Arm" in texts(dialog))
        "1234".forEach { dialog.keys.getValue(it.toString()).performClick() }
        dialog.keys.getValue("submit").performClick()
        idle()
        val (domain, service, data) = calls.single()
        assertEquals("alarm_control_panel" to "alarm_arm_away", domain to service)
        assertEquals("1234", data!!.getString("code"))
    }

    @Test fun cancellingTheCodeTakesTheSelectBack() {
        alarm("disarmed", "code_format" to "number", "code_arm_required" to true)
        val s = sheet()
        val select = all(s).filterIsInstance<AlarmModesSelect>().single()
        select.options.getValue("armed_home").performClick()
        idle()
        HaCodeDialog.lastView!!.closeButton.performClick()
        idle()
        assertTrue(calls.isEmpty())
        // The radio that's checked is Disarmed again (the selected option has a background).
        assertNotNull(select.options.getValue("disarmed").background)
        assertNull(select.options.getValue("armed_home").background)
    }

    @Test fun armingWithoutCodeArmRequiredOrWithADefaultCodeSkipsTheDialog() {
        alarm("disarmed", "code_format" to "number")
        val s = sheet()
        all(s).filterIsInstance<AlarmModesSelect>().single().options.getValue("armed_home").performClick()
        idle()
        assertNull(HaCodeDialog.lastShown)
        assertEquals("alarm_arm_home", calls.single().second)
        assertFalse(calls.single().third!!.has("code"))
        // Disarming needs the code format alone, and a default code in the registry skips it.
        alarm("armed_home", "code_format" to "number"); idle()
        wsAnswer = { JSONObject().put("options", JSONObject().put("alarm_control_panel", JSONObject().put("default_code", "0000"))) }
        all(s).filterIsInstance<AlarmModesSelect>().single().options.getValue("disarmed").performClick()
        idle()
        assertNull(HaCodeDialog.lastShown)
        assertEquals("alarm_disarm", calls.last().second)
    }

    @Test fun aTriggeredAlarmShowsItsStatusAndDisarm() {
        alarm("triggered", "code_format" to "text")
        val s = sheet()
        assertEquals(View.GONE, all(s).filterIsInstance<AlarmModesSelect>().single().visibility)
        val disarm = all(s).single { it.contentDescription == null && it is TextView && it.text == "Disarm" && shown(it, s) }
        disarm.performClick()
        idle()
        // A text code: HA's field and footer, submitted with Disarm.
        val dialog = HaCodeDialog.lastView!!
        assertFalse(dialog.numeric)
        dialog.codeInput.setText("abc")
        dialog.submitButton!!.performClick()
        idle()
        assertEquals("alarm_disarm", calls.single().second)
        assertEquals("abc", calls.single().third!!.getString("code"))
    }
}
