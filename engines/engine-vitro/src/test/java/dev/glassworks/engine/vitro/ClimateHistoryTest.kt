/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Application
import dev.glassworks.engine.vitro.cards.HaHistory
import dev.glassworks.engine.vitro.cards.HaHistoryLines
import dev.glassworks.engine.vitro.cards.HistorySeries
import dev.glassworks.engine.vitro.cards.HistoryState
import dev.glassworks.engine.vitro.cards.LineEntity
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Climate, humidifier and water-heater entities in `history-graph`, as HA's frontend draws them from their attributes
 * (`computeHistory` and `generateStateHistoryChartLineData`, frontend 20261028.4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClimateHistoryTest {
    private val theme = ThemeResolver.resolve(ThemeCatalog(), true)

    private fun st(s: String, lu: Double, a: String) = HistoryState(s, lu, JSONObject(a))

    private fun entity(id: String, states: List<HistoryState>, current: EntityState? = null): LineEntity {
        val store = mapOf(id to (current ?: EntityState(id, states.last().s, emptyMap())))
        val result = HaHistory.compute({ store[it] }, mapOf(id to states), listOf(id), "°C")
        assertEquals("°C".takeIf { !id.startsWith("humidifier.") } ?: "%", result.line.single().unit)
        return result.line.single().data.single()
    }

    private fun series(e: LineEntity, end: Double = 10_000_000.0) =
        HaHistoryLines.series(e, end, end, "AC", null, true, theme, intArrayOf(0), null)

    /** The series' values at its points, NaN as null. */
    private fun values(s: HistorySeries) = s.ys.map { if (it.isNaN()) null else it }

    @Test
    fun aClimateKeepsEveryRecordedTemperatureWhileItsModeStaysTheSame() {
        // The mode never changes: only the attributes do. HA keeps a state unless it repeats the two before it,
        // attributes included (equalState), so each temperature stays; a state-only comparison kept just three.
        val states = listOf(17.5, 18.0, 18.5, 18.0, 17.5).mapIndexed { i, t ->
            st("off", 1000.0 + i, """{"current_temperature": $t, "temperature": null, "hvac_action": "off"}""")
        }
        val e = entity("climate.ac", states)
        assertEquals(5, e.states.size)
        assertEquals(listOf(17.5, 18.0, 18.5, 18.0, 17.5), values(series(e)[0]).take(5))
        // A true repeat (same state and attributes three times) still goes.
        val repeated = List(3) { st("off", 1000.0 + it, """{"current_temperature": 18}""") }
        assertEquals(2, entity("climate.ac", repeated).states.size)
    }

    @Test
    fun aClimateUsesLastUpdatedAndKeepsOnlyTheLineAttributes() {
        val e = entity("climate.ac", listOf(HistoryState("heat", 1000.0, JSONObject("""{"current_temperature": 18, "fan_mode": "auto", "friendly_name": "AC"}""")).also { it.lc = 500.0 }))
        assertEquals(1_000_000.0, e.states[0].lastChanged, 0.0)
        assertEquals(setOf("current_temperature"), e.states[0].attributes!!.keys().asSequence().toSet())
    }

    @Test
    fun aClimateDrawsItsCurrentTemperatureAFillWhileItHeatsAndItsTarget() {
        val e = entity("climate.ac", listOf(
            st("heat", 1000.0, """{"current_temperature": 18, "temperature": 21, "hvac_action": "idle"}"""),
            st("heat", 2000.0, """{"current_temperature": 19, "temperature": 21, "hvac_action": "heating"}"""),
            st("heat", 3000.0, """{"current_temperature": 20.5, "temperature": 21, "hvac_action": "idle"}"""),
        ))
        val s = series(e)
        assertEquals(listOf("climate.ac-current_temperature", "climate.ac-heating", "climate.ac-target_temperature"), s.map { it.id })
        assertEquals(listOf("AC current temperature", "AC heating", "AC target temperature"), s.map { it.name })
        assertEquals(listOf(false, true, false), s.map { it.fill })
        // The fill is the theme's heat color; the lines take graph colors in order (the fill takes none).
        assertEquals(0xFFFF6F22.toInt(), s[1].color)
        assertEquals(dev.glassworks.engine.vitro.cards.HaGraphColors.byIndex(theme, 1), s[2].color)
        // The heating fill: null, then the temperature while heating, ended where heating stops, then held to the end.
        assertEquals(listOf(1_000_000.0, 2_000_000.0, 3_000_000.0, 3_000_000.0, 10_000_000.0), s[1].xs)
        assertEquals(listOf(null, 19.0, 19.0, null, null), values(s[1]))
        assertEquals(listOf(18.0, 19.0, 20.5, 20.5), values(s[0]))
        assertEquals(listOf(21.0, 21.0, 21.0, 21.0), values(s[2]))
    }

    @Test
    fun withoutAnHvacActionTheModeFillsAndARangeDrawsBothEnds() {
        val e = entity("climate.ac", listOf(
            st("cool", 1000.0, """{"current_temperature": 24, "target_temp_low": 20, "target_temp_high": 25}"""),
            st("heat_cool", 2000.0, """{"current_temperature": 23, "target_temp_low": 20, "target_temp_high": 25}"""),
        ))
        val s = series(e)
        assertEquals(listOf("current_temperature", "cooling", "target_temperature_mode", "target_temperature_mode_low"), s.map { it.id.substringAfter('-') })
        assertEquals(listOf("AC target temperature high", "AC target temperature low"), s.drop(2).map { it.name })
        assertEquals(0xFF2196F3.toInt(), s[1].color)
        assertEquals(listOf(24.0, 24.0, null, null), values(s[1]))
        assertEquals(listOf(25.0, 25.0, 25.0), values(s[2]))
        assertEquals(listOf(20.0, 20.0, 20.0), values(s[3]))
    }

    @Test
    fun aWaterHeaterFillsByItsState() {
        val e = entity("water_heater.tank", listOf(
            st("heat", 1000.0, """{"current_temperature": 50, "temperature": 55}"""),
            st("off", 2000.0, """{"current_temperature": 52, "temperature": 55}"""),
        ))
        val s = series(e)
        assertEquals(listOf("current_temperature", "heating", "target_temperature"), s.map { it.id.substringAfter('-') })
        assertEquals(listOf(50.0, 50.0, null, null), values(s[1]))
    }

    @Test
    fun aHumidifierDrawsItsTargetItsCurrentHumidityAndAFillWhileItHumidifies() {
        val e = entity("humidifier.room", listOf(
            st("on", 1000.0, """{"humidity": 45, "current_humidity": 38, "action": "humidifying"}"""),
            st("on", 2000.0, """{"humidity": 45, "current_humidity": 44, "action": "idle"}"""),
        ))
        val s = series(e)
        assertEquals(listOf("target_humidity", "current_humidity", "humidifying"), s.map { it.id.substringAfter('-') })
        assertEquals(listOf(false, false, true), s.map { it.fill })
        assertEquals(0xFF2196F3.toInt(), s[2].color)
        assertEquals(listOf(38.0, 38.0, null, null), values(s[2]))
    }

    @Test
    fun aHumidifierWithoutAnActionFillsUpToItsTargetWhileOn() {
        val e = entity("humidifier.room", listOf(
            st("on", 1000.0, """{"humidity": 45}"""),
            st("off", 2000.0, """{"humidity": 40}"""),
        ))
        val s = series(e)
        assertEquals(listOf("target_humidity", "on"), s.map { it.id.substringAfter('-') })
        assertEquals("AC on", s[1].name)
        // "On" takes the next graph color, as HA's addDataSet does without one.
        assertEquals(dev.glassworks.engine.vitro.cards.HaGraphColors.byIndex(theme, 1), s[1].color)
        assertEquals(listOf(45.0, 45.0, null, null), values(s[1]))
    }

    @Test
    fun theLegendShowsTheTemperatureEachSeriesDraws() {
        val ac = EntityState("climate.ac", "off", mapOf("current_temperature" to 18.25, "temperature" to null, "target_temp_low" to 20))
        assertEquals("18.25 °C", HaHistoryLines.legendValue("climate.ac-current_temperature", ac, "°C"))
        assertEquals("Unknown", HaHistoryLines.legendValue("climate.ac-target_temperature", ac, "°C"))
        assertEquals("20 °C", HaHistoryLines.legendValue("climate.ac-target_temperature_mode_low", ac, "°C"))
        assertEquals("Unknown", HaHistoryLines.legendValue("climate.ac-target_temperature_mode", ac, "°C"))
        val hum = EntityState("humidifier.room", "on", emptyMap())
        assertTrue(HaHistoryLines.legendValue("humidifier.room-target_humidity", hum, "°C")!!.isNotEmpty())
    }

    @Test
    fun aClimateWithoutRecordedHistoryDrawsItsCurrentAttributes() {
        // limitedHistoryFromStateObj: no history, so the current state stands in, attributes included.
        val ac = EntityState("climate.ac", "heat", mapOf("current_temperature" to 18, "temperature" to 22, "target_temp_high" to null, "target_temp_low" to null, "hvac_action" to "heating"))
        val result = HaHistory.compute({ if (it == ac.entityId) ac else null }, emptyMap(), listOf("climate.ac"), "°C")
        val s = series(result.line.single().data.single())
        assertEquals(listOf("current_temperature", "heating", "target_temperature"), s.map { it.id.substringAfter('-') })
        assertEquals(18.0, s[0].ys.first(), 0.0)
        assertEquals(18.0, s[1].ys.first(), 0.0)
        assertEquals(22.0, s[2].ys.first(), 0.0)
    }
}
