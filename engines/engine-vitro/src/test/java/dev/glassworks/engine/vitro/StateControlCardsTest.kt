/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.BrightnessDialView
import dev.glassworks.engine.vitro.cards.GaugeCardView
import dev.glassworks.engine.vitro.cards.ThermostatCardView
import dev.glassworks.engine.vitro.cards.ThermostatDialView
import dev.glassworks.engine.vitro.cards.LightCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The state rules and service calls of HA's state-control cards (light, thermostat, humidifier, gauge), as HA's
 * frontend 20261028.5 has them (`hui-*-card`, `ha-state-control-*`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StateControlCardsTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private data class Call(val service: String, val data: JSONObject?)

    private val calls = ArrayList<Call>()
    private val moreInfo = ArrayList<String>()
    private val theme by lazy { ThemeResolver.resolve(ThemeCatalog(), true) }

    private fun context(store: EntityStore) = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { d, sv, data, _ -> calls += Call("$d.$sv", data) }, navigate = {},
        openMoreInfo = { moreInfo += it }, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        haUser = { JSONObject().put("is_admin", true) },
    )

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    /** The texts shown: each one and every view above it up to [root] visible. */
    private fun visibleTexts(root: View) = all(root).filterIsInstance<TextView>().filter { tv ->
        tv.text.isNotEmpty() && generateSequence<View>(tv) { if (it === root) null else it.parent as? View }.all { it.visibility == View.VISIBLE }
    }.map { it.text.toString() }

    private fun light(state: String, attrs: Map<String, Any?>, card: String = """{"type": "light", "entity": "light.desk"}"""): Pair<LightCardView, BrightnessDialView> {
        val store = EntityStore()
        store.putLocal(EntityState("light.desk", state, attrs + ("friendly_name" to "Desk")))
        val view = LightCardView(context(store), LovelaceParser.parseCard(JSONObject(card)))
        return view to all(view).filterIsInstance<BrightnessDialView>().single()
    }

    private fun BrightnessDialView.color(): Int? = BrightnessDialView::class.java.getDeclaredField("tint").apply { isAccessible = true }.get(this) as Int?

    private val dimmable = mapOf("supported_color_modes" to listOf("color_temp", "hs"))

    @Test
    fun theLightSliderSendsBrightnessPctAndNeverTurnsTheLightOff() {
        val (_, dial) = light("on", dimmable + ("brightness" to 128))
        dial.onBrightness!!.invoke(65)
        // HA's _setBrightness: light.turn_on with brightness_pct, the slider's own 1–100 value.
        assertEquals("light.turn_on", calls.last().service)
        assertEquals(65, calls.last().data!!.getInt("brightness_pct"))
        assertFalse(calls.last().data!!.has("brightness"))
        // The slider's min is 1: its start dims the light to 1 %, it doesn't turn it off.
        dial.onBrightness!!.invoke(0)
        assertEquals("light.turn_on", calls.last().service)
        assertEquals(1, calls.last().data!!.getInt("brightness_pct"))
    }

    @Test
    fun theLightButtonRunsTheTapActionAndTheNameNone() {
        // Default: tap toggles.
        val (card, dial) = light("on", dimmable + ("brightness" to 128))
        dial.performClick()
        assertEquals("light.toggle", calls.single().service)
        // HA's #info has no action: tapping the name does nothing.
        all(card).filterIsInstance<TextView>().filter { it.text == "Desk" }.forEach { assertFalse(it.isClickable); assertFalse((it.parent as View).isClickable) }
        // A configured tap_action replaces the toggle.
        calls.clear()
        val (_, dial2) = light("on", dimmable, """{"type": "light", "entity": "light.desk", "tap_action": {"action": "more-info"}}""")
        dial2.performClick()
        assertTrue(calls.isEmpty())
        assertEquals(listOf("light.desk"), moreInfo)
    }

    @Test
    fun theLightButtonTakesTheLightsColorDimmedByItsBrightness() {
        // rgb_color, through stateColorBrightness: (204 + 245) / 5 = 89.8 %.
        val (_, dial) = light("on", dimmable + mapOf("brightness" to 204, "rgb_color" to listOf(255, 129, 40)))
        assertEquals(android.graphics.Color.rgb(229, 116, 36), dial.color())
        // No rgb_color: the light's active color, dimmed the same way.
        val (_, plain) = light("on", mapOf("supported_color_modes" to listOf("brightness"), "brightness" to 255))
        assertEquals(theme.activeColor("light"), plain.color())
        // Off: the state icon color, whatever the attributes say.
        val (_, off) = light("off", dimmable + ("rgb_color" to listOf(255, 0, 0)))
        assertEquals(theme.stateIconColor, off.color())
    }

    @Test
    fun noPercentUnderTheDialButTheStateWhileUnavailable() {
        val (on, _) = light("on", dimmable + ("brightness" to 166))
        assertEquals(listOf("Desk"), visibleTexts(on))
        // Unavailable: the state above the name, a disabled button in HA's disabled color, no slider to drag.
        val (card, dial) = light("unavailable", mapOf("supported_color_modes" to listOf("brightness")))
        assertEquals(listOf("Unavailable", "Desk"), visibleTexts(card))
        assertFalse(dial.isEnabled)
        assertEquals(LightCardView.disabledButtonColor(theme), dial.color())
        dial.performClick()
        assertTrue(calls.isEmpty())
        // Unknown: the state too, but the light button still works.
        val (unknown, unknownDial) = light("unknown", mapOf("supported_color_modes" to listOf("brightness")))
        assertEquals(listOf("Unknown", "Desk"), visibleTexts(unknown))
        unknownDial.performClick()
        assertEquals("light.toggle", calls.single().service)
    }

    @Test
    fun aLightWithoutBrightnessModesHasNoSlider() {
        // HA's lightSupportsBrightness: no supported_color_modes, or only onoff, hides the slider.
        assertFalse(LightCardView.lightSupportsBrightness(EntityState("light.a", "on", emptyMap())))
        assertFalse(LightCardView.lightSupportsBrightness(EntityState("light.a", "on", mapOf("supported_color_modes" to listOf("onoff")))))
        assertTrue(LightCardView.lightSupportsBrightness(EntityState("light.a", "on", mapOf("supported_color_modes" to listOf("white")))))
        assertNull(LightCardView.rgbColor(EntityState("light.a", "on", mapOf("rgb_color" to listOf(1, 2)))))
    }

    private fun gauge(state: String, attrs: Map<String, Any?> = emptyMap(), card: String = """{"type": "gauge", "entity": "sensor.ink"}"""): GaugeCardView {
        val store = EntityStore()
        if (state != "-") store.putLocal(EntityState("sensor.ink", state, attrs))
        return GaugeCardView(context(store), LovelaceParser.parseCard(JSONObject(card)))
    }

    @Test
    fun theGaugeShowsHasWarningWhereItsValueIsntANumber() {
        // HA's isNaN(value): unknown, a word, a missing attribute; unavailable and a missing entity have their own.
        assertEquals(listOf("Entity is non-numeric: sensor.ink"), visibleTexts(gauge("unknown")))
        assertEquals(listOf("Entity is non-numeric: sensor.ink"), visibleTexts(gauge("low")))
        assertEquals(listOf("Entity is currently unavailable: sensor.ink"), visibleTexts(gauge("unavailable")))
        assertEquals(listOf("Entity not found"), visibleTexts(gauge("-")))
        assertEquals(listOf("Attribute level is non-numeric"),
            visibleTexts(gauge("on", card = """{"type": "gauge", "entity": "sensor.ink", "attribute": "level"}""")))
        // The warning takes no action.
        val warned = gauge("unknown")
        assertFalse(warned.isClickable)
        // A number draws the gauge, and the name under it; the card acts again.
        val ok = gauge("42", mapOf("unit_of_measurement" to "%", "friendly_name" to "Ink"))
        assertEquals(listOf("Ink"), visibleTexts(ok))
        assertTrue(ok.isClickable)
        // JavaScript's Number(): an attribute that is a number, or a numeric string, is a value.
        assertEquals(listOf("Ink"), visibleTexts(gauge("on", mapOf("level" to 3, "friendly_name" to "Ink"),
            """{"type": "gauge", "entity": "sensor.ink", "attribute": "level"}""")))
    }

    private fun thermostat(id: String, state: String, attrs: Map<String, Any?>, width: Int = 500): ThermostatCardView {
        val store = EntityStore()
        store.putLocal(EntityState(id, state, attrs))
        val card = ThermostatCardView(context(store), LovelaceParser.parseCard(JSONObject("""{"type": "thermostat", "entity": "$id"}""")))
        val px = (width * activity.resources.displayMetrics.density).toInt()
        card.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        card.layout(0, 0, px, card.measuredHeight)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        return card
    }

    private fun steppersShown(card: View) =
        all(card).filter { it.contentDescription?.toString()?.endsWith("temperature") == true && it !is ThermostatDialView }
            .count { v -> generateSequence(v) { if (it === card) null else it.parent as? View }.all { it.visibility == View.VISIBLE } }

    private val heat = mapOf(
        "hvac_modes" to listOf("off", "heat"), "min_temp" to 7, "max_temp" to 30, "target_temp_step" to 0.5,
        "current_temperature" to 19.5, "temperature" to 21, "hvac_action" to "heating", "supported_features" to 385,
    )

    @Test
    fun theThermostatsButtonsShowAtHasLargeSizeWithATarget() {
        // HA's 262 px dial is its large size: − and + in its opening.
        assertEquals(2, steppersShown(thermostat("climate.hall", "heat", heat)))
        // Below 250 px (.container.md) HA hides them.
        assertEquals(0, steppersShown(thermostat("climate.hall", "heat", heat, width = 250)))
        // Unavailable: no target, no buttons; the dial says the state.
        val gone = thermostat("climate.hall", "unavailable", emptyMap())
        assertEquals(0, steppersShown(gone))
        assertEquals("Unavailable", all(gone).filterIsInstance<ThermostatDialView>().single().contentDescription.toString())
    }

    @Test
    fun aWaterHeatersDialSaysTargetAndCurrently() {
        val card = thermostat("water_heater.boiler", "eco", mapOf(
            "min_temp" to 43.3, "max_temp" to 60, "current_temperature" to 48, "temperature" to 50, "supported_features" to 15,
        ))
        val dial = all(card).filterIsInstance<ThermostatDialView>().single()
        assertEquals("Target, 50.0 °C, Currently 48 °C", dial.contentDescription.toString())
        // The − and + set the tank through water_heater.set_temperature.
        all(card).single { it.contentDescription == "Increase temperature" }.performClick()
        assertEquals("water_heater.set_temperature", calls.single().service)
        assertEquals(50.5, calls.single().data!!.getDouble("temperature"), 0.0)
    }

    private val humidifierOn = mapOf(
        "min_humidity" to 30, "max_humidity" to 80, "available_modes" to listOf("normal", "eco"), "current_humidity" to 41,
        "humidity" to 45, "mode" to "normal", "action" to "humidifying", "device_class" to "humidifier", "supported_features" to 1,
        "friendly_name" to "Nursery Humidifier",
    )

    private fun humidifierCard(state: String, attrs: Map<String, Any?>): View {
        val store = EntityStore()
        store.putLocal(EntityState("humidifier.nursery", state, attrs))
        val card = dev.glassworks.engine.vitro.cards.HumidifierCardView(context(store),
            LovelaceParser.parseCard(JSONObject("""{"type": "humidifier", "entity": "humidifier.nursery"}""")))
        val px = (500 * activity.resources.displayMetrics.density).toInt()
        card.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        card.layout(0, 0, px, card.measuredHeight)
        return card
    }

    private fun shown(card: View, v: View) = generateSequence(v) { if (it === card) null else it.parent as? View }.all { it.visibility == View.VISIBLE }

    @Test
    fun theHumidifierCardIsHasHumidityControlWithoutABar() {
        val card = humidifierCard("on", humidifierOn)
        val dial = all(card).filterIsInstance<ThermostatDialView>().single()
        // The action, the target in whole percents, the current humidity: no "Currently".
        assertEquals("Humidifying, 45%, 41%", dial.contentDescription.toString())
        // No power button or mode picker: HA draws a bar only for features, which fall back to HA's frontend.
        assertTrue(all(card).none { it.contentDescription?.toString() == "Power" || (it as? TextView)?.text?.toString() == "Normal" })
        // The + adds one step and sends humidifier.set_humidity with a whole number.
        all(card).single { it.contentDescription == "Raise target humidity" }.performClick()
        assertEquals("humidifier.set_humidity", calls.single().service)
        assertEquals(46, calls.single().data!!.get("humidity"))
        // Unavailable: no target, no buttons, the state as the label.
        val gone = humidifierCard("unavailable", emptyMap())
        assertEquals(0, all(gone).count { it.contentDescription?.toString()?.endsWith("humidity") == true && shown(gone, it) })
        assertEquals("Unavailable", all(gone).filterIsInstance<ThermostatDialView>().single().contentDescription.toString())
    }

    @Test
    fun theThermostatSizesItsDialAsHaDoes() {
        // getGridOptions: 12 columns by 5 rows (a sections grid fixes its height), two thirds of a row per feature.
        val grid = dev.glassworks.engine.vitro.ui.HaGridSize.of(LovelaceParser.parseCard(JSONObject("""{"type": "thermostat", "entity": "climate.hall"}""")))
        assertEquals(12, grid.columns)
        assertEquals(5, grid.rows)
        assertEquals(6, dev.glassworks.engine.vitro.ui.HaGridSize.of(LovelaceParser.parseCard(JSONObject(
            """{"type": "humidifier", "entity": "humidifier.h", "features": [{"type": "humidifier-toggle"}]}"""))).rows)
        // No fixed height (a masonry column): the title, then a square container around the control, as wide as the
        // card up to the control's 336 px.
        val dp = activity.resources.displayMetrics.density
        for ((width, container) in listOf(310 to 310 - 2, 400 to 336)) {
            val card = thermostat("climate.hall", "heat", heat, width = width)
            val dial = all(card).filterIsInstance<ThermostatDialView>().single()
            val inner = card.measuredHeight - card.paddingTop - card.paddingBottom
            assertEquals("width $width", 48f + container, inner / dp, 1.5f)
            assertEquals("width $width", container - 16f, dial.height / dp, 1.5f)
        }
    }
}
