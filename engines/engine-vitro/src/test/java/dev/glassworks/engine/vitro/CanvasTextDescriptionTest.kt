/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import dev.glassworks.engine.vitro.cards.GaugeCardView
import dev.glassworks.engine.vitro.cards.LightCardView
import dev.glassworks.engine.vitro.cards.ThermostatDialView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Text Vitro draws on a canvas is its view's content description: what HA's page has as text, for TalkBack too. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CanvasTextDescriptionTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private val calls = ArrayList<String>()

    private fun context(store: EntityStore = EntityStore()) = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { d, sv, _, _ -> calls += "$d.$sv" }, navigate = {}, openMoreInfo = {},
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
    )

    @Test
    fun theThermostatDialDescribesWhatItDraws() {
        val dial = ThermostatDialView(context(), "°C")
        // Off, no target: the state big and the current temperature (HA's "Off" and "17.5 °C").
        dial.bind(16.0, 31.0, 0.5, current = 17.5, target = null, mode = "off", action = "off", stateLabel = "Off")
        assertEquals("Off, 17.5 °C", dial.contentDescription.toString())
        // Heating to 21.5: the action, the target, the current temperature.
        dial.bind(16.0, 31.0, 0.5, current = 19.0, target = 21.5, mode = "heat", action = "heating")
        assertEquals("Heating, 21.5 °C, 19.0 °C", dial.contentDescription.toString())
        // A step up changes it with the target.
        dial.nudge(1)
        assertEquals("Heating, 22.0 °C, 19.0 °C", dial.contentDescription.toString())
    }

    @Test
    fun theHumidityDialDescribesWhatItDraws() {
        // HA's humidity control: the action, the target in whole percents, the current humidity.
        val dial = ThermostatDialView(context(), "%").apply { humidifier = true }
        dial.bind(30.0, 80.0, 1.0, current = 41.0, target = 50.0, mode = "on", action = "humidifying", stateLabel = "On")
        assertEquals("Humidifying, 50%, 41%", dial.contentDescription.toString())
        dial.nudge(1)
        assertEquals("Humidifying, 51%, 41%", dial.contentDescription.toString())
        // Off: HA's label is the state while there's a target.
        dial.bind(30.0, 80.0, 1.0, current = 52.0, target = 45.0, mode = "off", action = "off", stateLabel = "Off")
        assertEquals("Off, 45%, 52%", dial.contentDescription.toString())
        // A 45.5 % target reads as HA's whole percent; a current 0 % is a reading too.
        dial.bind(30.0, 80.0, 0.5, current = 0.0, target = 45.5, mode = "on", action = "humidifying", stateLabel = "On")
        assertEquals("Humidifying, 46%, 0%", dial.contentDescription.toString())
    }

    @Test
    fun theGaugeDescribesItsValue() {
        val store = EntityStore()
        store.putLocal(EntityState("sensor.load", "42", mapOf("unit_of_measurement" to "%", "friendly_name" to "Load")))
        val card = GaugeCardView(context(store), LovelaceParser.parseCard(JSONObject("""{"type": "gauge", "entity": "sensor.load"}""")))
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        val described = all(card).filter { it !is ViewGroup && !it.contentDescription.isNullOrBlank() }.map { it.contentDescription.toString() }
        assertEquals(1, described.size)
        assertEquals(true, described.single().startsWith("42"))
    }

    @Test
    fun theLightCardsDialDescribesTheBrightness() {
        val store = EntityStore()
        store.putLocal(EntityState("light.desk", "on", mapOf("brightness" to 128, "supported_color_modes" to listOf("brightness"), "friendly_name" to "Desk")))
        val card = LightCardView(context(store), LovelaceParser.parseCard(JSONObject("""{"type": "light", "entity": "light.desk"}""")))
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        val described = { all(card).filter { it !is ViewGroup && !it.contentDescription.isNullOrBlank() }.map { it.contentDescription.toString() } }
        assertEquals(true, "Brightness 50%" in described())
        // HA's brightness line is hidden (opacity 0) but while a drag shows it: no "50%" under the dial.
        assertEquals(false, all(card).filterIsInstance<android.widget.TextView>().any { it.text.toString().contains("%") && it.visibility == View.VISIBLE })
        store.putLocal(EntityState("light.desk", "off", mapOf("supported_color_modes" to listOf("brightness"), "friendly_name" to "Desk")))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(true, "Off" in described())
        // HA's light card spans the section.
        assertEquals(12, dev.glassworks.engine.vitro.ui.HaGridSize.of(LovelaceParser.parseCard(JSONObject("""{"type": "light", "entity": "light.desk"}"""))).columns)
    }

    @Test
    fun theLightDialTogglesFromAClickToo() {
        // TalkBack and a keyboard click the dial: it toggles the light, as a tap on its icon does.
        val store = EntityStore()
        store.putLocal(EntityState("light.desk", "on", mapOf("brightness" to 128, "supported_color_modes" to listOf("brightness"))))
        val card = LightCardView(context(store), LovelaceParser.parseCard(JSONObject("""{"type": "light", "entity": "light.desk"}""")))
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        val dial = all(card).single { it.contentDescription?.toString() == "Brightness 50%" }
        assertEquals(true, dial.isClickable)
        dial.performClick()
        assertEquals(1, calls.size)
        assertEquals(true, calls.single().startsWith("light."))
    }

    @Test
    fun anUnavailableAlarmShowsDisarmAsHaDoes() {
        // HA's alarm panel: the arm buttons only while disarmed; Disarm in every other state, unavailable too.
        val store = EntityStore()
        store.putLocal(EntityState("alarm_control_panel.home", "unavailable", mapOf("supported_features" to 63)))
        val card = dev.glassworks.engine.vitro.cards.AlarmPanelCardView(
            context(store), LovelaceParser.parseCard(JSONObject("""{"type": "alarm-panel", "entity": "alarm_control_panel.home"}""")),
        )
        activity.setContentView(card)
        val row = card.javaClass.getDeclaredField("actionsRow").apply { isAccessible = true }.get(card) as ViewGroup
        val labels = (0 until row.childCount).map { (row.getChildAt(it) as android.widget.TextView).text.toString() }
        assertEquals(1, labels.size)
        assertEquals(false, row.getChildAt(0).isEnabled)
    }

    @Test
    fun theDefaultArmButtonsAreTheOnesTheAlarmSupports() {
        fun buttons(features: Int, states: String = ""): Int {
            val store = EntityStore()
            store.putLocal(EntityState("alarm_control_panel.home", "disarmed", mapOf("supported_features" to features)))
            val card = dev.glassworks.engine.vitro.cards.AlarmPanelCardView(
                context(store), LovelaceParser.parseCard(JSONObject("""{"type": "alarm-panel", "entity": "alarm_control_panel.home"$states}""")),
            )
            activity.setContentView(card)
            return (card.javaClass.getDeclaredField("actionsRow").apply { isAccessible = true }.get(card) as ViewGroup).childCount
        }
        // arm_away (2) and arm_night (4): of HA's defaults (home, away), only away.
        assertEquals(1, buttons(6))
        assertEquals(2, buttons(63))
        // States the card lists are shown as written.
        assertEquals(2, buttons(6, ""","states": ["arm_home", "arm_night"]"""))
    }

    @Test
    fun anUnsubmittedAlarmCodeIsForgottenWhenTheCardIsHidden() {
        val store = EntityStore()
        store.putLocal(EntityState("alarm_control_panel.home", "disarmed", mapOf("code_format" to "number", "supported_features" to 3)))
        val card = dev.glassworks.engine.vitro.cards.AlarmPanelCardView(
            context(store), LovelaceParser.parseCard(JSONObject("""{"type": "alarm-panel", "entity": "alarm_control_panel.home"}""")),
        )
        val holder = android.widget.FrameLayout(activity).apply { addView(card) }
        activity.setContentView(holder)
        val onKey = card.javaClass.getDeclaredMethod("onKey", String::class.java).apply { isAccessible = true }
        val code = card.javaClass.getDeclaredField("code").apply { isAccessible = true }.get(card) as StringBuilder
        onKey.invoke(card, "1"); onKey.invoke(card, "2")
        assertEquals("12", code.toString())
        // A cached view hidden behind another hides the card through its ancestor.
        holder.visibility = View.GONE
        assertEquals("", code.toString())
        holder.visibility = View.VISIBLE
        onKey.invoke(card, "3")
        (holder.parent as ViewGroup).removeView(holder)
        assertEquals("", code.toString())
    }

    @Test
    fun aCancelledDialDragRestoresTheDescribedTarget() {
        val dial = ThermostatDialView(context(), "°C")
        dial.bind(16.0, 31.0, 0.5, current = 19.0, target = 21.5, mode = "heat", action = "heating")
        // Mid-drag at 24 (the preview), then the gesture is cancelled: nothing is sent, and TalkBack hears 21.5 again.
        dial.javaClass.getDeclaredField("dragging").apply { isAccessible = true }.setBoolean(dial, true)
        dial.javaClass.getDeclaredMethod("setTarget", java.lang.Double::class.java).apply { isAccessible = true }.invoke(dial, 24.0)
        dial.javaClass.getDeclaredMethod("describe").apply { isAccessible = true }.invoke(dial)
        assertEquals("Heating, 24.0 °C, 19.0 °C", dial.contentDescription.toString())
        dial.onTouchEvent(android.view.MotionEvent.obtain(0L, 0L, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0))
        assertEquals("Heating, 21.5 °C, 19.0 °C", dial.contentDescription.toString())
    }

    @Test
    fun canvasSlidersAreSeekBarsForTalkBackAndKeyboards() {
        val committed = ArrayList<Float>()
        val slider = dev.glassworks.engine.vitro.cards.HaHorizontalSliderView(context(), 0xFFFFAA00.toInt()) { committed += it }
        slider.setValue(0.5f, true)
        val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
        slider.onInitializeAccessibilityNodeInfo(info)
        assertEquals(android.widget.SeekBar::class.java.name, info.className)
        assertEquals(50f, info.rangeInfo.current, 0.01f)
        assertEquals(true, slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(0.6f, committed.last(), 0.001f)
        slider.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
        // A key edit is sent when its key comes up, as a drag is when it's let go.
        assertEquals(1, committed.size)
        slider.onKeyUp(android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(0.59f, committed.last(), 0.001f)

        val kelvins = ArrayList<Int>()
        val bar = dev.glassworks.engine.vitro.cards.HaColorTempBarView(context()) { kelvins += it }
        bar.bind(2000, 6500, 4250, on = true)
        val barInfo = android.view.accessibility.AccessibilityNodeInfo.obtain()
        bar.onInitializeAccessibilityNodeInfo(barInfo)
        assertEquals(4250f, barInfo.rangeInfo.current, 1f)
        bar.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)
        assertEquals(3800, kelvins.last())
    }

    @Test
    fun theMoreInfoTogglePillIsACheckableSwitch() {
        val toggled = ArrayList<Boolean>()
        val pill = dev.glassworks.engine.vitro.cards.VerticalTogglePillView(activity, 0xFFFFAA00.toInt(), true).apply { onToggle = { toggled += it } }
        pill.setOn(false)
        assertEquals(true, pill.isClickable)
        pill.performClick()
        assertEquals(listOf(true), toggled)
        val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
        pill.onInitializeAccessibilityNodeInfo(info)
        assertEquals(true, info.isCheckable)
        assertEquals(true, info.isChecked)
    }
}
