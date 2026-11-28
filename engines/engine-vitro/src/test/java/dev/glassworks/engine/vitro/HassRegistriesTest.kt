/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.data.HassRegistries
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** [HassRegistries]: HA's registries in the shape its frontend gives cards (`hass.entities`, `devices`, `areas`, `floors`). */
class HassRegistriesTest {

    @Test
    fun theEntityDisplayListExpandsAsHasFrontendDoes() {
        val entities = HassRegistries.entities(JSONObject(
            """{"entity_categories": {"0": "config", "1": "diagnostic"}, "entities": [
                 {"ei": "light.lounge_lamp", "di": "dev-1", "ai": "lounge", "lb": ["evening"], "tk": "lamp", "pl": "hue",
                  "hn": true, "en": "Lamp", "ic": "mdi:lamp", "dp": 1},
                 {"ei": "sensor.lamp_signal", "di": "dev-1", "ec": 1, "hb": true},
                 {"di": "no-id"}]}""",
        ))
        assertEquals(setOf("light.lounge_lamp", "sensor.lamp_signal"), entities.keys().asSequence().toSet())
        val lamp = entities.getJSONObject("light.lounge_lamp")
        assertEquals("light.lounge_lamp", lamp.getString("entity_id"))
        assertEquals("dev-1", lamp.getString("device_id"))
        assertEquals("lounge", lamp.getString("area_id"))
        assertEquals("evening", lamp.getJSONArray("labels").getString(0))
        assertEquals("lamp", lamp.getString("translation_key"))
        assertEquals("hue", lamp.getString("platform"))
        assertEquals(true, lamp.getBoolean("has_entity_name"))
        assertEquals("Lamp", lamp.getString("name"))
        assertEquals("mdi:lamp", lamp.getString("icon"))
        assertEquals(1, lamp.getInt("display_precision"))
        assertFalse("no category: left out, undefined to a card", lamp.has("entity_category"))

        val signal = entities.getJSONObject("sensor.lamp_signal")
        assertEquals("diagnostic", signal.getString("entity_category"))
        assertEquals(true, signal.getBoolean("hidden"))
        assertEquals("labels are always a list", 0, signal.getJSONArray("labels").length())
        assertFalse(signal.has("name"))
        assertFalse(signal.has("area_id"))
    }

    @Test
    fun devicesAreasAndFloorsAreKeyedByTheirIds() {
        val set = HassRegistries.build(mapOf(
            HassRegistries.Kind.DEVICES to JSONArray("""[{"id": "dev-1", "name": "Ball Lamp", "area_id": "lounge"}, {"name": "no id"}]"""),
            HassRegistries.Kind.AREAS to JSONArray("""[{"area_id": "lounge", "name": "Lounge", "floor_id": "ground"}]"""),
            HassRegistries.Kind.FLOORS to null, // refused: an older Home Assistant
        ))
        assertEquals(setOf("entities", "devices", "areas", "floors"), set.keys().asSequence().toSet())
        assertEquals(0, set.getJSONObject("entities").length())
        assertEquals("Ball Lamp", set.getJSONObject("devices").getJSONObject("dev-1").getString("name"))
        assertEquals(1, set.getJSONObject("devices").length())
        assertEquals("ground", set.getJSONObject("areas").getJSONObject("lounge").getString("floor_id"))
        assertEquals(0, set.getJSONObject("floors").length())
    }
}
