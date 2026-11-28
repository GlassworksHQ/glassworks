/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.cards.MoreInfoRoute
import dev.glassworks.engine.vitro.data.EntityState
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Which More Info dialogs the sheet draws, and which go to HA's frontend because the sheet can't draw them as HA does. */
class MoreInfoRouteTest {
    private fun s(id: String, vararg attrs: Pair<String, Any?>) = EntityState(id, "on", attrs.toMap())

    private fun native(id: String, vararg attrs: Pair<String, Any?>) = assertNull(id, MoreInfoRoute.handOff(id, s(id, *attrs)))
    private fun web(id: String, vararg attrs: Pair<String, Any?>) = assertNotNull(id, MoreInfoRoute.handOff(id, s(id, *attrs)))

    @Test
    fun theDefaultDialogAndTheControlsTheSheetHasStayNative() {
        listOf("sensor.temp", "binary_sensor.door", "device_tracker.phone", "zone.home", "number.volume", "input_number.target",
            "light.desk", "cover.blind",
            "climate.hall", "lock.door", "select.mode", "input_select.mode", "scene.movie", "script.bed", "button.ring",
            "switch.plug", "fan.ceiling", "media_player.tv", "alarm_control_panel.home", "camera.door", "siren.hall",
        ).forEach { native(it) }
        // No state yet: decided by the domain.
        assertNull(MoreInfoRoute.handOff("sensor.temp", null))
    }

    @Test
    fun domainsWithADialogTheSheetDoesntDrawGoToHomeAssistant() {
        listOf("weather.home", "timer.tea", "person.alex", "water_heater.tank", "valve.main", "lawn_mower.lawn",
            "counter.visits", "input_datetime.alarm", "input_text.note", "text.label", "remote.tv", "sun.sun",
            "calendar.home", "group.all", "automation.night", "humidifier.bedroom", "date.day", "time.at",
            "datetime.when", "image.map", "conversation.ha",
        ).forEach { web(it) }
        assertNotNull(MoreInfoRoute.handOff("timer.tea", null))
    }

    @Test
    fun featuresTheSheetLeavesOutGoToHomeAssistant() {
        // Cleaning by area is a view of its own; the rest of the vacuum's dialog is the sheet's.
        native("vacuum.robot", "supported_features" to (4 or 8 or 16 or 8192))
        web("vacuum.robot", "supported_features" to (8192 or 16384))
        // The backup switch reads the Supervisor's settings.
        native("update.fw", "supported_features" to (1 or 4 or 16))
        web("update.fw", "supported_features" to (1 or 8))
        // A siren's tones, volume or duration ("More controls").
        web("siren.hall", "supported_features" to (3 or 4))
        // A group's members, listed under its control.
        web("light.kitchen", "entity_id" to listOf("light.a", "light.b"))
        // A custom more-info element.
        web("sensor.temp", "custom_ui_more_info" to "my-more-info")
    }
}
