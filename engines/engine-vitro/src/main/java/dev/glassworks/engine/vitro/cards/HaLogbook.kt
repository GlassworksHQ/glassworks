/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HaText
import org.json.JSONArray
import org.json.JSONObject

/**
 * What HA's `ha-logbook` computes for a row, from `src/data/logbook.ts` and `src/panels/logbook/logbook-entry-model.ts`
 * (frontend 20261028.5): which entities have an Activity section, how the stream's messages merge, a row's value, its
 * cause and its node's color. The views are [MoreInfoLogbookView]'s.
 */
internal object HaLogbook {
    /** `CONTINUOUS_DOMAINS`: no logbook for them. */
    private val CONTINUOUS_DOMAINS = setOf("counter", "proximity")

    /** `DOMAINS_MORE_INFO_NO_HISTORY`. */
    private val NO_HISTORY_DOMAINS = setOf("camera", "configurator")

    /** `DOMAINS_WITH_MORE_INFO`: a dialog of their own, whose Activity section is on its History view, not its default. */
    private val DOMAINS_WITH_MORE_INFO = setOf(
        "alarm_control_panel", "automation", "calendar", "camera", "climate", "configurator", "conversation", "counter",
        "cover", "date", "datetime", "fan", "group", "humidifier", "image", "input_boolean", "input_datetime",
        "lawn_mower", "light", "lock", "media_player", "person", "remote", "script", "scene", "siren", "sun", "switch",
        "time", "timer", "update", "vacuum", "valve", "water_heater", "weather",
    )

    /**
     * The `more-info-view` of [entityId]'s dialog that lists its Activity section: `history` for a domain with a dialog
     * of its own (`DOMAINS_WITH_MORE_INFO`), else null, the default view (`ha-more-info-info`).
     */
    fun activityView(entityId: String): String? =
        if (entityId.substringBefore('.') in DOMAINS_WITH_MORE_INFO) "history" else null

    /**
     * A tap on a logbook card's entry ([entityId], the entry's entity). HA opens its logbook detail dialog
     * (`ha-logbook-renderer`'s `showLogbookDetailDialog`: the entry, its cause, the run's trace), which only HA's
     * frontend has and no URL opens. Its More Info dialog for the entity does open from one, on the view whose Activity
     * section lists the same entry, the detail one tap away: Vitro hands the tap off there, as the sheet's Activity rows
     * do. An entity whose dialog has no Activity section ([shows]: a numeric sensor, a camera), or a tap without HA's
     * frontend ([VitroCardContext.moreInfoInFrontend] false), opens Vitro's More Info instead; an entry without an
     * entity (an event) opens nothing.
     */
    fun openEntryDetail(ctx: dev.glassworks.engine.vitro.ui.VitroCardContext, entityId: String?) {
        val id = entityId ?: return
        if (shows(id, ctx.store[id], ctx.haConfig()) && ctx.moreInfoInFrontend(id, activityView(id), "a logbook entry's detail")) return
        ctx.openMoreInfo(id)
    }

    /** `TRIGGER_DOMAINS`. */
    private val TRIGGER_DOMAINS = setOf("automation", "script")

    /** `SENSOR_NUMERIC_DEVICE_CLASSES` (`src/data/sensor_entity_constants.ts`). */
    private val SENSOR_NUMERIC_DEVICE_CLASSES = setOf(
        "absolute_humidity", "apparent_power", "aqi", "area", "atmospheric_pressure", "battery",
        "blood_glucose_concentration", "carbon_dioxide", "carbon_monoxide", "conductivity", "current", "data_rate",
        "data_size", "distance", "duration", "energy", "energy_distance", "energy_storage", "frequency", "gas", "humidity",
        "illuminance", "irradiance", "moisture", "monetary", "nitrogen_dioxide", "nitrogen_monoxide", "nitrous_oxide",
        "ozone", "ph", "pm1", "pm10", "pm25", "pm4", "power", "power_factor", "precipitation", "precipitation_intensity",
        "pressure", "radon", "reactive_energy", "reactive_power", "signal_strength", "sound_pressure", "speed",
        "sulphur_dioxide", "temperature", "temperature_delta", "volatile_organic_compounds",
        "volatile_organic_compounds_parts", "voltage", "volume", "volume_flow_rate", "volume_storage", "water", "weight",
        "wind_direction", "wind_speed",
    )

    /** `NUMERICAL_DOMAINS` (`src/data/history.ts`). */
    private val NUMERICAL_DOMAINS = setOf("counter", "input_number", "number")

    /**
     * `STATE_ACTION_MESSAGES`: the word a timestamp-state domain's row shows instead of its timestamp (a button
     * "pressed"), under `ui.components.logbook.messages`.
     */
    private val STATE_ACTION_MESSAGES = mapOf(
        "button" to "ui.components.logbook.messages.pressed",
        "input_button" to "ui.components.logbook.messages.pressed",
        "scene" to "ui.components.logbook.messages.activated",
        "tag" to "ui.components.logbook.messages.scanned",
        "image" to "ui.components.logbook.messages.updated",
        "notify" to "ui.components.logbook.messages.sent",
        "wake_word" to "ui.components.logbook.messages.detected",
        "stt" to "ui.components.logbook.messages.transcribed",
        "tts" to "ui.components.logbook.messages.spoke",
        "conversation" to "ui.components.logbook.messages.responded",
        "ai_task" to "ui.components.logbook.messages.ran",
        "infrared" to "ui.components.logbook.messages.command_sent",
        "radio_frequency" to "ui.components.logbook.messages.command_sent",
    )

    /** HA's trigger-type labels (`ui.components.logbook.trigger_type.*`), by the platform a source parses to. */
    private val TRIGGER_TYPE_LABELS = mapOf(
        "state" to "ui.components.logbook.trigger_type.state",
        "numeric_state" to "ui.components.logbook.trigger_type.numeric_state",
        "event" to "ui.components.logbook.trigger_type.event",
        "time" to "ui.components.logbook.trigger_type.time",
        "time_pattern" to "ui.components.logbook.trigger_type.time_pattern",
    )

    /** `RESTORABLE_BUTTON_DOMAINS`: a restored button's timestamp isn't a press. */
    private val RESTORABLE_BUTTON_DOMAINS = setOf("button", "input_button")

    /** `DOMAINS_WITH_DYNAMIC_PICTURE`: their picture shows now, never in the log. */
    private val DYNAMIC_PICTURE_DOMAINS = setOf("camera", "image", "media_player")

    /** The fixed system users core names (`SYSTEM_USER_ICONS`). */
    private val SYSTEM_USER_ICONS = mapOf("Home Assistant Cloud" to "mdi:cloud", "Home Assistant Cast" to "mdi:cast")

    /** `triggerPhrases`, in HA's order ("time pattern" before "time"), with what they parse to. */
    private val TRIGGER_PHRASES = listOf(
        "numeric state of" to "numeric_state", "state of" to "state", "event" to "event", "time pattern" to "time_pattern",
        "time" to "time", "Home Assistant stopping" to "homeassistant", "Home Assistant starting" to "homeassistant",
    )

    /**
     * `FALLBACK_DOMAIN_ICONS` (`src/data/icons.ts`): what `ha-domain-icon` draws for a domain without icons of its own
     * before it would fall back to the brand's image.
     */
    private val FALLBACK_DOMAIN_ICONS = mapOf(
        "ai_task" to "mdi:star-four-points", "air_quality" to "mdi:air-filter", "alert" to "mdi:alert",
        "automation" to "mdi:robot", "battery" to "mdi:battery", "calendar" to "mdi:calendar", "climate" to "mdi:thermostat",
        "configurator" to "mdi:cog", "conversation" to "mdi:forum-outline", "counter" to "mdi:counter",
        "date" to "mdi:calendar", "datetime" to "mdi:calendar-clock", "demo" to "mdi:home-assistant",
        "device_tracker" to "mdi:account", "door" to "mdi:door-open", "garage_door" to "mdi:garage-open", "gate" to "mdi:gate",
        "google_assistant" to "mdi:google-assistant", "group" to "mdi:google-circles-communities",
        "homeassistant" to "mdi:home-assistant", "homekit" to "mdi:home-automation", "humidity" to "mdi:water-percent",
        "illuminance" to "mdi:brightness-6", "image_processing" to "mdi:image-filter-frames", "image" to "mdi:image",
        "infrared" to "mdi:led-on", "input_boolean" to "mdi:toggle-switch", "input_button" to "mdi:button-pointer",
        "input_datetime" to "mdi:calendar-clock", "input_number" to "mdi:ray-vertex", "input_select" to "mdi:format-list-bulleted",
        "input_text" to "mdi:form-textbox", "lawn_mower" to "mdi:robot-mower", "light" to "mdi:lightbulb",
        "moisture" to "mdi:water", "motion" to "mdi:motion-sensor", "notify" to "mdi:comment-alert", "number" to "mdi:ray-vertex",
        "occupancy" to "mdi:home-account", "persistent_notification" to "mdi:bell", "person" to "mdi:account",
        "plant" to "mdi:flower", "power" to "mdi:flash", "proximity" to "mdi:apple-safari", "radio_frequency" to "mdi:radio-tower",
        "remote" to "mdi:remote", "scene" to "mdi:palette", "schedule" to "mdi:calendar-clock", "script" to "mdi:script-text",
        "select" to "mdi:format-list-bulleted", "sensor" to "mdi:eye", "simple_alarm" to "mdi:bell", "siren" to "mdi:bullhorn",
        "stt" to "mdi:microphone-message", "sun" to "mdi:white-balance-sunny", "temperature" to "mdi:thermometer",
        "text" to "mdi:form-textbox", "time" to "mdi:clock", "timer" to "mdi:timer-outline", "template" to "mdi:code-braces",
        "todo" to "mdi:clipboard-list", "tts" to "mdi:speaker-message", "vacuum" to "mdi:robot-vacuum",
        "wake_word" to "mdi:chat-sleep", "weather" to "mdi:weather-partly-cloudy", "window" to "mdi:window-closed",
        "zone" to "mdi:map-marker-radius",
    )

    /** `isComponentLoaded(hass.config, "logbook")`. A config that doesn't list components (a test's) counts as one with it. */
    fun loaded(haConfig: JSONObject): Boolean {
        val components = haConfig.optJSONArray("components") ?: return true
        return (0 until components.length()).any { components.optString(it) == "logbook" }
    }

    /** `isNumericEntity(domain, stateObj, undefined)`: a numeric domain, a unit or state class, a numeric device class. */
    fun isNumeric(s: EntityState): Boolean =
        s.domain in NUMERICAL_DOMAINS || s.attributes.containsKey("unit_of_measurement") || s.attributes.containsKey("state_class") ||
            (s.domain == "sensor" && s.deviceClass in SENSOR_NUMERIC_DEVICE_CLASSES)

    /**
     * `computeShowLogBookComponent`: HA runs its logbook, the entity has a state, and it isn't a continuous one (a
     * counter, a proximity, a numeric sensor) or a camera.
     */
    fun shows(entityId: String, s: EntityState?, haConfig: JSONObject): Boolean {
        if (!loaded(haConfig) || s == null) return false
        val domain = entityId.substringBefore('.')
        if (domain in CONTINUOUS_DOMAINS || domain in NO_HISTORY_DOMAINS) return false
        return !(domain == "sensor" && isNumeric(s))
    }

    /**
     * `_processStreamMessage`: [incoming] (as the stream sent it, oldest first) put newest first, merged with
     * [current] (newest first; null before the first message) as HA merges them: an empty message changes nothing,
     * entries older than [purgeBeforeSec] go, and new ones go above, below or among the rest by time.
     */
    fun merge(current: List<LogbookEntry>?, incoming: List<LogbookEntry>, purgeBeforeSec: Double?): List<LogbookEntry> {
        val fresh = incoming.reversed()
        if (current.isNullOrEmpty()) return fresh
        if (fresh.isEmpty()) return current
        val kept = if (purgeBeforeSec != null) current.filter { it.whenSec > purgeBeforeSec } else current
        return when {
            kept.isEmpty() -> fresh
            fresh.last().whenSec > kept.first().whenSec -> fresh + kept
            kept.last().whenSec > fresh.first().whenSec -> kept + fresh
            // JavaScript's sort is stable, as Kotlin's is.
            else -> (kept + fresh).sortedByDescending { it.whenSec }
        }
    }

    /** `classifyLogbookEntry`. */
    fun category(e: LogbookEntry): String = when {
        e.entityId != null && e.state != null -> "entity"
        e.domain in TRIGGER_DOMAINS -> "automation"
        else -> "integration"
    }

    /**
     * `createHistoricState`: the entity as it was at [e], its static attributes copied from now (a dynamic picture
     * isn't, as it shows the present).
     */
    fun historicState(current: EntityState, state: String?): EntityState {
        val a = current.attributes
        val keep = HashMap<String, Any?>()
        for (k in listOf("device_class", "unit_of_measurement", "state_class", "options", "source_type", "has_date", "has_time")) {
            if (a.containsKey(k)) keep[k] = a[k]
        }
        if (current.domain !in DYNAMIC_PICTURE_DOMAINS) {
            for (k in listOf("entity_picture_local", "entity_picture")) if (a.containsKey(k)) keep[k] = a[k]
        }
        return EntityState(current.entityId, state ?: "", keep)
    }

    /** A row's value (`LogbookValue`): its text, and whether it's a state (else a message). */
    data class Value(val text: String, val isState: Boolean)

    /** `computeLogbookValue`, with `localizeStateMessage` for a state change. [store] is HA's states now. */
    fun value(e: LogbookEntry, store: (String) -> EntityState?): Value? {
        val eid = e.entityId
        val domain = eid?.substringBefore('.') ?: e.domain
        if (eid != null && !e.state.isNullOrEmpty()) {
            val current = store(eid)
            val text = if (current != null) localizeStateMessage(e.state, historicState(current, e.state), domain.orEmpty(), e) else e.state
            return Value(text, true)
        }
        // Run rows of an automation or a script carry core's English; HA uses its own label.
        if (eid != null && domain != null && domain in TRIGGER_DOMAINS && isRunMessage(domain, e.message)) {
            return Value(HaText.ui(if (domain == "script") "ui.components.logbook.script_ran" else "ui.components.logbook.automation_triggered"), true)
        }
        val msg = e.message ?: return null
        val text = if (hasContext(e) && e.contextEntityId != null) msg.replaceFirst(e.contextEntityId, " ") else msg
        return Value(text, false)
    }

    /** `localizeStateMessage`. */
    private fun localizeStateMessage(state: String, historic: EntityState, domain: String, e: LogbookEntry): String {
        if (state == "unknown" || state == "unavailable") return historic.formattedState()
        if (domain == "event") {
            val type = e.eventType
            if (type != null) return HaDisplay.attributeLabel(historic, "event_type", type) ?: type
            return HaText.ui("ui.components.logbook.messages.detected_event_no_type")
        }
        val action = STATE_ACTION_MESSAGES[domain]
        val stamp = dev.glassworks.engine.vitro.data.EntityStore.parseIsoEpochSeconds(state)
        val matchesEntry = stamp != null && kotlin.math.abs(stamp * 1000 - e.whenSec * 1000) < 1000
        if (action != null && (domain !in RESTORABLE_BUTTON_DOMAINS || matchesEntry)) {
            return HaText.ui(action)
        }
        return historic.formattedState()
    }

    private fun isRunMessage(domain: String, message: String?): Boolean =
        message == null || if (domain == "script") message == "started" else message == "triggered" || message.startsWith("triggered by ")

    private fun hasContext(e: LogbookEntry): Boolean = e.contextEventType != null || e.contextState != null || e.contextMessage != null

    /**
     * `_formatMessageWithPossibleEntity`: a message naming an entity HA has a state for ("turned on by
     * light.desk") shows the entity's name in its place; the second mention of one already named empties the message.
     */
    fun messageText(message: String, store: (String) -> EntityState?): String {
        if ('.' !in message) return message
        val parts = message.split(" ")
        for (i in parts.indices) {
            val s = store(parts[i]) ?: continue
            val name = entityPrimary(parts[i], s)
            // lit's template puts the name between the two parts with whitespace, which renders as one space each.
            return listOf(parts.subList(0, i).joinToString(" "), name, parts.subList(i + 1, parts.size).joinToString(" "))
                .filter { it.isNotEmpty() }.joinToString(" ")
        }
        return message
    }

    /** `entityDisplay(...).primary`: the entity's own name, else its device's, else its id. */
    fun entityPrimary(entityId: String, s: EntityState): String {
        val parts = HaDisplay.nameParts(entityId, s.friendlyName)
        return parts.entity ?: parts.device ?: entityId
    }

    /** A row's cause (`LogbookCause`). */
    data class Cause(
        val type: String,
        val name: String,
        val userId: String? = null,
        val systemUser: Boolean = false,
        val entityId: String? = null,
        val brandDomain: String? = null,
    )

    /** HA's users as `ha-logbook` reads them (`_updateUsers`): names by id, the system users, the ones with a person. */
    class Users(val names: Map<String, String>, val system: Set<String>, val persons: Map<String, String>) {
        companion object {
            val NONE = Users(emptyMap(), emptySet(), emptyMap())

            /**
             * The persons' users from [states] (a person entity's `user_id`, named as the person), then, for an
             * administrator, `config/auth/list`'s [authList] for the rest and the system users.
             */
            fun of(states: Collection<EntityState>, authList: JSONArray?): Users {
                val names = LinkedHashMap<String, String>()
                val persons = LinkedHashMap<String, String>()
                for (s in states) {
                    if (s.domain != "person") continue
                    val uid = s.attributes["user_id"]?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
                    names[uid] = entityPrimary(s.entityId, s)
                    persons[uid] = s.entityId
                }
                val system = HashSet<String>()
                if (authList != null) {
                    for (i in 0 until authList.length()) {
                        val u = authList.optJSONObject(i) ?: continue
                        val id = u.optString("id").takeIf { it.isNotEmpty() } ?: continue
                        if (id !in names && !u.isNull("name")) names[id] = u.optString("name")
                        if (u.optBoolean("system_generated", false)) system += id
                    }
                }
                return Users(names, system, persons)
            }
        }
    }

    /** `computeUserCause`. */
    private fun userCause(e: LogbookEntry, users: Users): Cause? {
        val uid = e.contextUserId ?: return null
        val name = users.names[uid] ?: return null
        if (name.isEmpty()) return null
        return Cause("user", name, userId = uid, systemUser = uid in users.system)
    }

    /** `computeContextCause`. */
    private fun contextCause(e: LogbookEntry, store: (String) -> EntityState?): Cause? {
        fun primary(id: String): String? = store(id)?.let { entityPrimary(id, it) }
        if (e.contextEventType == "automation_triggered" || e.contextEventType == "script_started") {
            val name = e.contextEntityId?.let(::primary) ?: e.contextName
            if (!name.isNullOrEmpty()) {
                return Cause(if (e.contextEventType == "script_started") "script" else "automation", name, entityId = e.contextEntityId)
            }
        }
        if (e.contextEventType == "call_service" && e.contextDomain != null) {
            return Cause("integration", domainName(e.contextDomain), brandDomain = e.contextDomain)
        }
        if (e.contextState != null && e.contextEntityId != null) {
            val name = primary(e.contextEntityId) ?: e.contextEntityIdName
            if (!name.isNullOrEmpty()) return Cause("state", name, entityId = e.contextEntityId)
        }
        val source = e.source
        if (e.domain in TRIGGER_DOMAINS && !hasContext(e) && source != null) {
            val match = TRIGGER_PHRASES.firstOrNull { source.startsWith(it.first) }
            if (match != null) {
                val platform = match.second
                val rest = source.substring(match.first.length).trim()
                val entityId = rest.takeIf { Regex("^[a-z_]+\\.[a-z0-9_]+$").matches(it) }
                when (platform) {
                    "state", "numeric_state" -> {
                        val name = if (entityId != null) primary(entityId) ?: entityId
                        else TRIGGER_TYPE_LABELS[platform]?.let { HaText.ui(it) }.orEmpty().ifEmpty { platform }
                        return Cause("state", name, entityId = entityId)
                    }
                    "time", "time_pattern" -> return Cause("scheduled", "")
                    "homeassistant" -> {
                        val key = if (source.startsWith("Home Assistant starting")) "ui.components.logbook.homeassistant_starting"
                        else "ui.components.logbook.homeassistant_stopping"
                        return Cause("homeassistant", HaText.ui(key).ifEmpty { source })
                    }
                    else -> return Cause("integration", TRIGGER_TYPE_LABELS[platform]?.let { HaText.ui(it) }.orEmpty().ifEmpty { platform })
                }
            }
        }
        val contextName = e.contextName
        if (contextName != null) return Cause("integration", contextName, brandDomain = e.contextDomain)
        return null
    }

    /** `computeRowCause`: an automation or script run over the user who started it, else the user, else the context. */
    fun rowCause(e: LogbookEntry, users: Users, store: (String) -> EntityState?): Cause? {
        val context = contextCause(e, store)
        if (context?.type == "automation" || context?.type == "script") return context
        return userCause(e, users) ?: context
    }

    /** `computeCausePhrase`: what the cause badge's tooltip says ("Triggered by …", "By Alex"). */
    fun causePhrase(c: Cause): String = when (c.type) {
        "user" -> HaText.ui("ui.components.logbook.cause.by", "name" to c.name)
        "automation" -> HaText.ui("ui.components.logbook.cause.by_automation", "name" to c.name)
        "script" -> HaText.ui("ui.components.logbook.cause.by_script", "name" to c.name)
        "state" -> HaText.ui("ui.components.logbook.cause.by_state_change", "name" to c.name)
        "scheduled" -> HaText.ui("ui.components.logbook.cause.scheduled")
        "integration" -> HaText.ui("ui.components.logbook.cause.via", "name" to c.name)
        else -> c.name
    }

    /**
     * The cause badge's icon (`renderLogbookCauseIcon`), or null for a user's avatar (initials or the person's
     * picture), which [MoreInfoLogbookView] draws. [UNSUPPORTED] for an integration whose domain has no icon here:
     * HA would draw the brand's image, which Vitro doesn't load.
     */
    fun causeIcon(c: Cause): String? = when (c.type) {
        "user" -> if (c.systemUser) SYSTEM_USER_ICONS[c.name] else null
        "automation" -> "mdi:robot"
        "script" -> "mdi:script-text"
        "state" -> "mdi:state-machine"
        "scheduled" -> "mdi:clock-outline"
        "homeassistant" -> "mdi:home-assistant"
        else -> c.brandDomain?.let { d -> domainIcon(d) ?: FALLBACK_DOMAIN_ICONS[d] ?: UNSUPPORTED } ?: "mdi:puzzle"
    }

    /** What [causeIcon] gives for a badge Vitro can't draw as HA does. */
    const val UNSUPPORTED = "unsupported"

    /** `domainIcon(…)` for an integration: its `entity_component` default, when HA has sent its icons. */
    private fun domainIcon(domain: String): String? = HaDisplay.domainIcon(EntityState("$domain.logbook_cause", "", emptyMap()))

    /** `domainToName`: the integration's title from HA's translations, else the domain. */
    private fun domainName(domain: String): String = HaText.ui("component.$domain.title").ifEmpty { domain }

    /** `computeUserInitials`: the first letter of each of the first three words, "?" without a name. */
    fun initials(name: String): String {
        if (name.isEmpty()) return "?"
        return name.trim().split(" ").take(3).joinToString("") { it.take(1) }
    }

    /** A person's picture for [userId] (`ha-user-badge`: the person whose `user_id` it is, with an `entity_picture`). */
    fun userPicture(userId: String, users: Users, store: (String) -> EntityState?): String? =
        users.persons[userId]?.let(store)?.entityPicture
}
