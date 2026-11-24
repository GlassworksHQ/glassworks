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
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** HA 2026's gauge: its colors and levels (`hui-gauge-card`, `ha-gauge`), the value text and the name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GaugeCardTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()

    @After
    fun forget() = HaDisplay.reset()

    private fun card(config: String, state: String = "74", attrs: Map<String, Any?> = mapOf("unit_of_measurement" to "%")): GaugeCardView {
        store.putLocal(EntityState("sensor.ink", state, mapOf("friendly_name" to "Ink") + attrs))
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        )
        return GaugeCardView(ctx, LovelaceParser.parseCard(JSONObject(config)))
    }

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun value(c: View) = all(c).single { it !is ViewGroup && it !is TextView && it.contentDescription != null }.contentDescription.toString()
    private fun name(c: View) = all(c).filterIsInstance<TextView>().single().text.toString()

    @Test
    fun theArcTakesTheSeverityColorElseInfo() {
        val c = card("""{"type": "gauge", "entity": "sensor.ink", "severity": {"green": 50, "yellow": 20, "red": 10}}""")
        assertEquals(GaugeCardView.SEVERITY_GREEN, c.severityOf(74.0))
        assertEquals(GaugeCardView.SEVERITY_YELLOW, c.severityOf(20.0))
        // Below every threshold: HA's normal, --info-color.
        assertEquals(GaugeCardView.INFO, c.severityOf(5.0))
        assertEquals(GaugeCardView.INFO, card("""{"type": "gauge", "entity": "sensor.ink"}""").severityOf(74.0))
        // New-format segments.
        val seg = card("""{"type": "gauge", "entity": "sensor.ink", "segments": [{"from": 0, "color": "#ff0000"}, {"from": 60, "color": "#00ff00"}]}""")
        assertEquals(0xFF00FF00.toInt(), seg.severityOf(74.0))
        assertEquals(0xFFFF0000.toInt(), seg.severityOf(10.0))
    }

    @Test
    fun needleLevelsStartAtMinInInfo() {
        // Thresholds sorted; the first doesn't start at min, so ha-gauge puts an info level before it.
        val c = card("""{"type": "gauge", "entity": "sensor.ink", "needle": true, "severity": {"green": 50, "yellow": 20, "red": 10}}""")
        assertEquals(listOf(0.0 to GaugeCardView.INFO, 10.0 to GaugeCardView.SEVERITY_RED, 20.0 to GaugeCardView.SEVERITY_YELLOW,
            50.0 to GaugeCardView.SEVERITY_GREEN), c.levels().map { it.level to it.color })
        // Starting at min: no extra level.
        val red0 = card("""{"type": "gauge", "entity": "sensor.ink", "needle": true, "severity": {"green": 50, "yellow": 20, "red": 0}}""")
        assertEquals(listOf(0.0, 20.0, 50.0), red0.levels().map { it.level })
    }

    @Test
    fun aSegmentLabelReplacesTheValue() {
        val c = card("""{"type": "gauge", "entity": "sensor.ink", "needle": true,
            "segments": [{"from": 0, "color": "red", "label": "Low"}, {"from": 60, "color": "green", "label": "Full"}]}""")
        assertEquals("Full", value(c))
        assertEquals("74%", value(card("""{"type": "gauge", "entity": "sensor.ink", "needle": true}""")))
    }

    @Test
    fun theValueTextAndTheName() {
        assertEquals("74%", value(card("""{"type": "gauge", "entity": "sensor.ink"}""")))
        // A custom unit: the number, then the unit (no space before %).
        assertEquals("74 ml", value(card("""{"type": "gauge", "entity": "sensor.ink", "unit": "ml"}""")))
        assertEquals("Ink", name(card("""{"type": "gauge", "entity": "sensor.ink"}""")))
        assertEquals("Black", name(card("""{"type": "gauge", "entity": "sensor.ink", "name": "Black"}""")))
        // HA 2026's default name: the device's, then the entity's own.
        HaDisplay.applyDevices(JSONArray("""[{"id": "d1", "name": "Printer"}]"""))
        HaDisplay.applyRegistry(JSONObject().put("entities", JSONArray("""[{"ei": "sensor.ink", "di": "d1", "en": "Black ink"}]""")))
        assertEquals("Printer Black ink", name(card("""{"type": "gauge", "entity": "sensor.ink"}""")))
    }
}
