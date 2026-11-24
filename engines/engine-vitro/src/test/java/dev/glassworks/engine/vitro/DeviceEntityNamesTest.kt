/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.GaugeCardView
import dev.glassworks.engine.vitro.cards.EntityCardView
import dev.glassworks.engine.vitro.cards.ThermostatCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** HA 2026's `formatEntityName` default on the gauge, the thermostat and the sensor card: "Device Entity". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceEntityNamesTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()

    @Before
    fun registries() {
        HaDisplay.applyDevices(JSONArray("""[{"id": "d1", "name": "Hallway"}]"""))
        HaDisplay.applyRegistry(JSONObject().put("entities", JSONArray("""[
            {"ei": "climate.hall", "di": "d1", "en": "Thermostat"},
            {"ei": "sensor.hall_t", "di": "d1", "en": "Temperature"}
        ]""")))
        store.putLocal(EntityState("climate.hall", "heat", mapOf("friendly_name" to "Hall thermostat", "temperature" to 20.0,
            "current_temperature" to 19.5, "hvac_modes" to listOf("off", "heat"), "min_temp" to 7, "max_temp" to 35)))
        store.putLocal(EntityState("sensor.hall_t", "19.5", mapOf("friendly_name" to "Hall temperature", "unit_of_measurement" to "°C")))
    }

    @After
    fun forget() = HaDisplay.reset()

    private fun ctx() = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
    )

    private fun texts(v: View): List<String> {
        fun all(x: View): List<View> = listOf(x) + ((x as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        return all(v).filterIsInstance<TextView>().map { it.text.toString() }
    }

    private fun card(json: String) = LovelaceParser.parseCard(JSONObject(json))

    @Test
    fun theCardsNameTheDeviceThenTheEntity() {
        val thermostat = texts(ThermostatCardView(ctx(), card("""{"type": "thermostat", "entity": "climate.hall"}""")))
        assertTrue(thermostat.toString(), "Hallway Thermostat" in thermostat)
        // The sensor card is hui-entity-card underneath (mini-graph-card, a HACS card, keeps the friendly name).
        val sensor = texts(EntityCardView(ctx(), card("""{"type": "sensor", "entity": "sensor.hall_t"}""")))
        assertTrue(sensor.toString(), "Hallway Temperature" in sensor)
        val gauge = texts(GaugeCardView(ctx(), card("""{"type": "gauge", "entity": "sensor.hall_t"}""")))
        assertTrue(gauge.toString(), "Hallway Temperature" in gauge)
        // A configured name still wins.
        assertTrue("Hall" in texts(ThermostatCardView(ctx(), card("""{"type": "thermostat", "entity": "climate.hall", "name": "Hall"}"""))))
    }
}
