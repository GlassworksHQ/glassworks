/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.data.EntityState

/**
 * HA's `stateActive` (frontend 20261028.5, `common/entity/state_active.ts`): whether an entity's state counts as on
 * for its domain, which a tile's color and its features follow.
 *
 * The same object, word for word, as #927's (in its `HaStateDisplay.kt`): whichever of the two merges second drops its
 * copy.
 */
internal object HaStateActive {
    /** HA's `TIMESTAMP_STATE_DOMAINS` (`common/const.ts`): domains whose state is the time of their last event. */
    val TIMESTAMP_STATE_DOMAINS = setOf(
        "ai_task", "button", "conversation", "event", "image", "infrared", "input_button", "notify", "radio_frequency",
        "scene", "stt", "tag", "tts", "wake_word", "datetime",
    )

    fun of(s: EntityState, state: String = s.state): Boolean {
        val domain = s.domain
        if (domain in TIMESTAMP_STATE_DOMAINS) return state != "unavailable"
        if (state == "unavailable" || state == "unknown") return false
        if (state == "off" && domain != "alert") return false
        return when (domain) {
            "alarm_control_panel" -> state != "disarmed"
            "alert" -> state != "idle"
            "cover", "valve" -> state != "closed"
            "device_tracker", "person" -> state != "not_home"
            "lawn_mower" -> state !in setOf("docked", "paused", "idle")
            "lock" -> state != "locked"
            "media_player" -> state != "standby"
            "vacuum" -> state !in setOf("idle", "docked", "paused")
            "plant" -> state == "problem"
            "group" -> state in setOf("on", "home", "open", "locked", "problem")
            "timer" -> state == "active"
            "camera" -> state in setOf("streaming", "recording")
            else -> true
        }
    }
}
