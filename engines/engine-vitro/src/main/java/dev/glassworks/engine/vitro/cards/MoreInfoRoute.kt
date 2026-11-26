/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.data.EntityState
import org.json.JSONArray

/**
 * Which More Info dialogs Vitro's sheet ([MoreInfoSheet]) draws, and which go to Home Assistant's frontend instead.
 *
 * HA's dialog (frontend 20261028.5, `src/dialogs/more-info/`) is the spec. A domain with a dialog of its own
 * (`DOMAINS_WITH_MORE_INFO`) or a control in its state row (`input_text`, `text`) that the sheet doesn't draw, or a
 * feature of one it does draw that the sheet leaves out, opens HA's own dialog: never a generic sheet with the controls
 * missing. The rest are HA's default dialog (the state row, the history, the Activity section), which the sheet draws:
 * a number's and an input_number's state row is its slider or box (`state-card-number`); a select's its menu, a
 * button's its Press, an alert's its toggle and an event's its last event ([MoreInfoStateCard]).
 */
internal object MoreInfoRoute {
    /**
     * Why [s]'s dialog goes to HA's frontend, or null when the sheet draws it as HA does. [entityId] names it when
     * there's no state yet (the sheet then waits for one, as HA's dialog does).
     */
    fun handOff(entityId: String, s: EntityState?): String? {
        val domain = entityId.substringBefore('.')
        if (s?.attributes?.containsKey("custom_ui_more_info") == true) return "a custom more-info element (custom_ui_more_info)"
        // more-info-content lists a group entity's members under its control (`attributes.entity_id`).
        if (domain in NATIVE_CONTROLS && memberCount(s) > 0) return "a group's member list in $domain's dialog"
        return when (domain) {
            // more-info-siren: the tones, volume and duration open a view of their own ("More controls").
            "siren" -> if (s.supports(SIREN_TONES or SIREN_VOLUME or SIREN_DURATION)) "siren's advanced controls" else null
            in NATIVE_CONTROLS -> null
            "vacuum" -> vacuumGap(s)
            "update" -> updateGap(entityId, s)
            in HA_DIALOGS, in HA_ROW_CONTROLS -> "$domain's dialog"
            else -> null
        }
    }

    /**
     * The domains whose dialog the sheet draws itself: a control view of its own, or (select, input_select, button,
     * input_button) HA's default view with the domain's state row.
     */
    private val NATIVE_CONTROLS = setOf(
        "light", "switch", "input_boolean", "siren", "fan", "camera", "climate", "cover",
        "select", "input_select", "lock", "media_player", "alarm_control_panel", "button", "input_button", "script",
        "scene",
    )

    /** HA's `DOMAINS_WITH_MORE_INFO` the sheet has no view for (vacuum and update are decided by their features). */
    private val HA_DIALOGS = setOf(
        // more-info-automation (last triggered, Run actions) and more-info-humidifier (the target humidity dial).
        "automation", "humidifier",
        "calendar", "configurator", "conversation", "counter", "date", "datetime", "group", "image", "input_datetime",
        "lawn_mower", "person", "remote", "sun", "time", "timer", "valve", "water_heater", "weather",
    )

    /** `DOMAINS_HIDE_DEFAULT_MORE_INFO` whose state row is a control the sheet doesn't draw. */
    private val HA_ROW_CONTROLS = setOf("input_text", "text")

    /** HA's `SirenEntityFeature` bits behind its advanced controls. */
    private const val SIREN_TONES = 4
    private const val SIREN_VOLUME = 8
    private const val SIREN_DURATION = 16

    private fun memberCount(s: EntityState?): Int = when (val v = s?.attributes?.get("entity_id")) {
        is List<*> -> v.size
        is JSONArray -> v.length()
        else -> 0
    }

    /** `more-info-vacuum`: everything but cleaning by area (`CLEAN_AREA`), a view of its own. */
    private fun vacuumGap(s: EntityState?): String? =
        if (VacuumFeatures.has(s, VacuumFeatures.CLEAN_AREA)) "vacuum's clean-by-area view" else null

    /**
     * `more-info-update`: everything but the backup switch (`BACKUP`, which reads the Supervisor's and the backup
     * integration's settings), what only the Supervisor's updates have (a backup running holds them back, their
     * failures link to its logs: `platform: hassio`), and Skip on an update that updates itself, which asks first.
     */
    private fun updateGap(entityId: String, s: EntityState?): String? = when {
        UpdateFeatures.has(s, UpdateFeatures.BACKUP) -> "update's backup option"
        dev.glassworks.engine.vitro.data.HaDisplay.registry[entityId]?.platform == "hassio" -> "a Supervisor update"
        s?.attributes?.get("auto_update") == true -> "an update that installs itself (Skip asks first)"
        else -> null
    }
}

/** HA's `supportsFeature`: [bit] is set in the entity's `supported_features`. */
internal fun EntityState?.supports(bit: Int): Boolean =
    ((this?.attributes?.get("supported_features") as? Number)?.toInt() ?: 0) and bit != 0

/** HA's `VacuumEntityFeature` (`src/data/vacuum.ts`). */
internal object VacuumFeatures {
    const val PAUSE = 4
    const val STOP = 8
    const val RETURN_HOME = 16
    const val FAN_SPEED = 32
    const val STATUS = 128
    const val LOCATE = 512
    const val CLEAN_SPOT = 1024
    const val STATE = 4096
    const val START = 8192
    const val CLEAN_AREA = 16384

    fun has(s: EntityState?, bit: Int): Boolean = s.supports(bit)
}

/** HA's `UpdateEntityFeature` (`src/data/update.ts`). */
internal object UpdateFeatures {
    const val INSTALL = 1
    const val SPECIFIC_VERSION = 2
    const val PROGRESS = 4
    const val BACKUP = 8
    const val RELEASE_NOTES = 16

    fun has(s: EntityState?, bit: Int): Boolean = s.supports(bit)
}
