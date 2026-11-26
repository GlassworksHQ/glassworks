/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.ui.MdiIcons
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** HA's display data: number formatting, icons and state labels, as HA's frontend computes them. */
class HaDisplayTest {
    @After
    fun reset() = HaDisplay.reset()

    @Test
    fun numbersFollowHaFormatNumber() {
        // The string's own decimals stay; a whole step and value drop them; the registry's precision wins.
        assertEquals("1.0", HaDisplay.formatState("1.0", null, 0.1))
        assertEquals("20", HaDisplay.formatState("20.0", null, 5.0))
        assertEquals("0", HaDisplay.formatState("0.0", 0, null))
        assertEquals("20.6", HaDisplay.formatState("20.55", 1, null))
        assertEquals("1,234.5", HaDisplay.formatState("1234.5", null, null))
        // ha-control-number-buttons formats the value: up to two decimals, trailing zeros dropped.
        assertEquals("1", HaDisplay.formatValue(1.0))
        assertEquals("0.9", HaDisplay.formatValue(0.9))
    }

    @Test
    fun formattedStateUsesTheRegistryAndTranslations() {
        HaDisplay.applyRegistry(JSONObject("""{"entities": [
            {"ei": "sensor.lux", "pl": "esphome", "dp": 0},
            {"ei": "select.pipeline", "pl": "esphome", "tk": "pipeline"}]}"""))
        HaDisplay.applyTranslations(JSONObject("""{"resources": {
            "component.esphome.entity.select.pipeline.state.preferred": "Preferred",
            "component.binary_sensor.entity_component.occupancy.state.on": "Detected"}}"""), true, listOf("select", "binary_sensor"))
        assertEquals("0 lx", EntityState("sensor.lux", "0.0", mapOf("unit_of_measurement" to "lx")).formattedState())
        assertEquals("Preferred", EntityState("select.pipeline", "preferred", emptyMap()).formattedState())
        // An option with no translation shows as HA shows it: as is.
        assertEquals("presence", EntityState("select.mode", "presence", emptyMap()).formattedState())
        assertEquals("Detected", EntityState("binary_sensor.p", "on", mapOf("device_class" to "occupancy")).formattedState())
    }

    @Test
    fun tileNamesJoinTheDeviceAndTheEntity() {
        HaDisplay.applyRegistry(JSONObject("""{"entities": [
            {"ei": "light.sunset", "di": "d1", "en": "Sunset"},
            {"ei": "light.ball", "di": "d2"},
            {"ei": "light.strip", "di": "d3", "en": "Accent"},
            {"ei": "switch.desk", "di": "d4", "en": "Desktop"},
            {"ei": "sensor.fridge", "di": "d5", "en": "Fridge temperature"},
            {"ei": "light.hall", "di": "d6", "en": "hall"},
            {"ei": "light.loose"}]}"""))
        HaDisplay.applyDevices(org.json.JSONArray("""[
            {"id": "d1", "name": "ZG-305Z", "name_by_user": "Sunset Lamp"},
            {"id": "d2", "name": "Ball Lamp", "name_by_user": null},
            {"id": "d3", "name": "LED Strip"},
            {"id": "d4", "name": "Desk"},
            {"id": "d5", "name": "Fridge"},
            {"id": "d6", "name": "Hall"}]"""))
        assertEquals("Sunset Lamp Sunset", HaDisplay.deviceEntityName("light.sunset"))
        assertEquals("Ball Lamp", HaDisplay.deviceEntityName("light.ball"))
        assertEquals("LED Strip Accent", HaDisplay.deviceEntityName("light.strip"))
        // The entity's own name stays whole: no device-name prefix is cut off it ("Desk" + "Desktop").
        assertEquals("Desk Desktop", HaDisplay.deviceEntityName("switch.desk"))
        // As hass.formatEntityName: "Fridge" + "Fridge temperature" stays "Fridge Fridge temperature".
        assertEquals("Fridge Fridge temperature", HaDisplay.deviceEntityName("sensor.fridge"))
        // An entity named exactly like its device shows the device's name once.
        assertEquals("Hall", HaDisplay.deviceEntityName("light.hall"))
        assertNull(HaDisplay.deviceEntityName("light.loose"))
    }

    @Test
    fun iconsComeFromTheIntegrationThenTheDomain() {
        assertNull(HaDisplay.icon(EntityState("binary_sensor.p", "on", mapOf("device_class" to "occupancy"))))
        HaDisplay.applyRegistry(JSONObject("""{"entities": [{"ei": "select.pipeline", "pl": "esphome", "tk": "pipeline"}]}"""))
        HaDisplay.applyIcons(JSONObject("""{"resources": {
            "binary_sensor": {"occupancy": {"default": "mdi:home-outline", "state": {"on": "mdi:home"}}},
            "sensor": {"battery": {"default": "mdi:battery", "range": {"0": "mdi:battery-outline", "50": "mdi:battery-50"}}},
            "select": {"_": {"default": "mdi:format-list-bulleted"}}}}"""), true, listOf("binary_sensor", "sensor", "select"))
        HaDisplay.applyIcons(JSONObject("""{"resources": {"esphome": {"select": {"pipeline": {"default": "mdi:filter-outline"}}}}}"""), false, listOf("esphome"))
        assertEquals("mdi:home", HaDisplay.icon(EntityState("binary_sensor.p", "on", mapOf("device_class" to "occupancy"))))
        assertEquals("mdi:home-outline", HaDisplay.icon(EntityState("binary_sensor.p", "off", mapOf("device_class" to "occupancy"))))
        assertEquals("mdi:battery-50", HaDisplay.icon(EntityState("sensor.b", "72", mapOf("device_class" to "battery"))))
        assertEquals("mdi:filter-outline", MdiIcons.resolveIconName(null, EntityState("select.pipeline", "preferred", emptyMap())))
        assertEquals("mdi:format-list-bulleted", MdiIcons.resolveIconName(null, EntityState("select.other", "a", emptyMap())))
        // The card's icon and the state's own icon still come first.
        assertEquals("mdi:star", MdiIcons.resolveIconName("mdi:star", EntityState("select.pipeline", "preferred", emptyMap())))
    }

    @Test
    fun aNewConnectionToTheSameHomeAssistantKeepsItsDataButAsksForTheWordsAgain() {
        // A8: names stay on screen across a reconnect; another Home Assistant's never carry over.
        HaDisplay.connectionTo("http://homeassistant.local:8123")
        HaDisplay.applyDevices(org.json.JSONArray("""[{"id": "d1", "name": "Ball Lamp"}]"""))
        HaDisplay.applyTranslations(JSONObject("""{"resources": {"component.light.entity_component._.state.on": "An"}}"""), true, listOf("light"))
        HaDisplay.applyIcons(JSONObject("{}"), true, listOf("light"))
        assertEquals(setOf<String>(), HaDisplay.missingFor(listOf("light.lamp")).first)

        HaDisplay.connectionTo("http://homeassistant.local:8123")
        assertEquals("Ball Lamp", HaDisplay.deviceName("d1"))
        assertEquals("asked for again", setOf("light"), HaDisplay.missingFor(listOf("light.lamp")).first)

        HaDisplay.connectionTo("http://192.0.2.20:8123")
        assertNull(HaDisplay.deviceName("d1"))
    }
}
