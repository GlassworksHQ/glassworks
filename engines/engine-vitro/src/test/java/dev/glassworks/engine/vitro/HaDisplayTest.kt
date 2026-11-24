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
            {"ei": "light.strip", "di": "d3", "en": "LED Strip accent"},
            {"ei": "light.loose"}]}"""))
        HaDisplay.applyDevices(org.json.JSONArray("""[
            {"id": "d1", "name": "ZG-305Z", "name_by_user": "Sunset Lamp"},
            {"id": "d2", "name": "Ball Lamp", "name_by_user": null},
            {"id": "d3", "name": "LED Strip"}]"""))
        assertEquals("Sunset Lamp Sunset", HaDisplay.deviceEntityName("light.sunset"))
        assertEquals("Ball Lamp", HaDisplay.deviceEntityName("light.ball"))
        assertEquals("LED Strip accent", HaDisplay.deviceEntityName("light.strip"))
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
}
