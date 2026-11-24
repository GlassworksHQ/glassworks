/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import dev.glassworks.engine.vitro.cards.GaugeCardView
import dev.glassworks.engine.vitro.cards.HumidityDialView
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

    private fun context(store: EntityStore = EntityStore()) = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
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
        val dial = HumidityDialView(context())
        dial.bind(30.0, 80.0, current = 41.0, target = 50.0, on = true, status = "Humidifying")
        assertEquals("Humidifying, 50%, Currently 41%", dial.contentDescription.toString())
        dial.nudge(1)
        assertEquals("Humidifying, 51%, Currently 41%", dial.contentDescription.toString())
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
}
