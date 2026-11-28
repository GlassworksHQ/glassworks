/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.HaLocale
import dev.glassworks.engine.vitro.data.HaText
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * HA's `stateActive` (frontend 20261028.5, `common/entity/state_active.ts`): whether an entity's state counts as on
 * for its domain, which a tile's color and its features follow.
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

/**
 * HA's `<state-display>` (frontend 20261028.5, `state-display/state-display.ts`) as a tile draws it: the `state_content`
 * parts (or the domain's default ones), each formatted as HA formats it, joined by " · ". [text] is what it shows at
 * [nowMs]; [tickMs] is how soon it changes on its own (a relative time, a running timer), or null.
 */
internal object HaStateDisplay {
    const val SEPARATOR = " · "

    /** HA's `DEFAULT_STATE_CONTENT_DOMAINS`. */
    private val DEFAULT_CONTENT = mapOf(
        "climate" to listOf("state", "current_temperature"),
        "cover" to listOf("state", "current_position"),
        "fan" to listOf("percentage"),
        "humidifier" to listOf("state", "current_humidity"),
        "light" to listOf("brightness"),
        "timer" to listOf("remaining_time"),
        "update" to listOf("install_status"),
        "valve" to listOf("state", "current_position"),
    )

    /** HA's `HIDDEN_ZERO_ATTRIBUTES_DOMAINS`: attributes left out while they're 0. */
    private val HIDDEN_ZERO = mapOf(
        "valve" to setOf("current_position"), "cover" to setOf("current_position"),
        "fan" to setOf("percentage"), "light" to setOf("brightness"),
    )

    private val TIMESTAMP_STATE_PROPS = setOf("last_updated", "last_changed")
    private val TIMESTAMP_CONTENTS = TIMESTAMP_STATE_PROPS + "last_triggered"
    private val TIMESTAMP_DOMAIN_CONTENTS = mapOf(
        "calendar" to setOf("start_time", "end_time"),
        "input_datetime" to setOf("timestamp"),
        "sun" to setOf("next_dawn", "next_dusk", "next_midnight", "next_noon", "next_rising", "next_setting"),
    )

    /** HA's `SENSOR_TIMESTAMP_DEVICE_CLASSES`. */
    private val SENSOR_TIMESTAMP_DEVICE_CLASSES = setOf("timestamp", "uptime")

    class Display(val text: String, val tickMs: Long?)

    /**
     * The contents a `state_content` config value names, as HA normalizes them (`last-changed` is `last_changed`),
     * or null when it names none (the domain's default then).
     */
    fun contentOf(raw: Any?): List<String>? = when (raw) {
        null, JSONObject.NULL -> null
        is JSONArray -> (0 until raw.length()).mapNotNull { raw.opt(it)?.takeUnless { v -> v == JSONObject.NULL }?.toString() }.map(::normalize)
        else -> listOf(normalize(raw.toString()))
    }

    private fun normalize(s: String) = when (s) {
        "last-updated" -> "last_updated"
        "last-changed" -> "last_changed"
        else -> s
    }

    /**
     * What `<state-display .content=[content]>` shows for [s] at [nowMs]; with [dashUnavailable] (a heading badge's
     * `dash-unavailable`) an unavailable or unknown state is "—", and [name] is what the `name` content shows.
     */
    fun of(s: EntityState, content: List<String>?, nowMs: Long, config: JSONObject, dashUnavailable: Boolean = false, name: String? = null): Display {
        val contents = content ?: DEFAULT_CONTENT[s.domain] ?: listOf("state")
        var tick: Long? = null
        fun ticks(ms: Long) { tick = tick?.let { minOf(it, ms) } ?: ms }
        val values = contents.mapNotNull { c ->
            when {
                c == "state" && dashUnavailable && (s.state == "unavailable" || s.state == "unknown") -> "—"
                c == "name" && !name.isNullOrEmpty() -> name
                else -> part(s, c, nowMs, config, ::ticks)
            }?.takeIf { v -> v.isNotEmpty() }
        }
        if (values.isEmpty()) return Display(formatEntityState(s), null)
        return Display(values.joinToString(SEPARATOR), tick)
    }

    /** HA's `formatEntityState`: the translated state, numbers in the user's format with their unit. */
    fun formatEntityState(s: EntityState): String = s.plainState()

    private fun part(s: EntityState, content: String, nowMs: Long, config: JSONObject, ticks: (Long) -> Unit): String? {
        val domain = s.domain
        if (content == "state") {
            val noValue = s.state == "unavailable" || s.state == "unknown"
            if ((s.deviceClass in SENSOR_TIMESTAMP_DEVICE_CLASSES || domain in HaStateActive.TIMESTAMP_STATE_DOMAINS) && !noValue) {
                val total = s.deviceClass == "uptime"
                return timestamp(jsDate(s.state), if (total) "total" else "relative", nowMs, ticks)
            }
            return formatEntityState(s)
        }
        if (content == "entity-id") return s.entityId

        var ts: Long? = null
        var hasTs = false
        when {
            content in TIMESTAMP_STATE_PROPS -> {
                val sec = if (content == "last_changed") s.lastChanged else s.lastUpdated
                if (sec > 0.0) { ts = (sec * 1000).roundToLong(); hasTs = true }
            }
            domain == "input_datetime" && content == "timestamp" -> {
                val v = s.attributes["timestamp"]
                // `timestamp * 1000`: NaN (no timestamp) isn't a time, 0 is.
                if (v is Number) { ts = (v.toDouble() * 1000).roundToLong(); hasTs = true }
            }
            content in TIMESTAMP_CONTENTS || TIMESTAMP_DOMAIN_CONTENTS[domain]?.contains(content) == true -> {
                when (val v = s.attributes[content]) {
                    null -> Unit
                    is Number -> { ts = v.toLong(); hasTs = true }
                    is String -> if (v.isNotEmpty()) { ts = jsDate(v); hasTs = true }
                    else -> { ts = null; hasTs = true }
                }
            }
        }
        if (hasTs) return timestamp(ts, "relative", nowMs, ticks)

        if (content == "remaining_time" && domain == "timer") return timerDisplay(s, nowMs, ticks)
        if (content == "install_status" && domain == "update") return updateDisplay(s)

        val attribute = s.attributes[content] ?: return null
        if (HIDDEN_ZERO[domain]?.contains(content) == true && isFalsy(attribute)) return null
        return attributeValue(s, content, attribute, config)
    }

    /** JavaScript truthiness for an attribute value: 0, "" and false hide a zero-hidden attribute. */
    private fun isFalsy(v: Any?): Boolean = when (v) {
        null -> true
        is Number -> v.toDouble() == 0.0 || v.toDouble().isNaN()
        is String -> v.isEmpty()
        is Boolean -> !v
        else -> false
    }

    /**
     * `<hui-timestamp-display capitalize>` with [format] `relative` (or `total`, an uptime): "5 minutes ago", counting on
     * every second; HA's "Invalid date" for a time it can't read.
     */
    private fun timestamp(ms: Long?, format: String, nowMs: Long, ticks: (Long) -> Unit): String {
        if (ms == null) return HaText.ui("ui.panel.lovelace.components.timestamp-display.invalid")
        ticks(1_000L)
        val text = if (format == "relative") HaFormat.relativeTime(ms, nowMs)
        else HaFormat.relativeTime(nowMs, ms, includeTense = false)
        return HaFormat.capitalize(text)
    }

    /** `new Date(text)` for the ISO forms HA sends, in milliseconds; null when it isn't one. */
    private fun jsDate(text: String): Long? {
        EntityStore.parseIsoEpochSeconds(text)?.let { return (it * 1000).roundToLong() }
        // A date alone is midnight UTC, as JavaScript reads it.
        if (DATE_ONLY.matches(text)) EntityStore.parseIsoEpochSeconds("${text}T00:00:00Z")?.let { return (it * 1000).roundToLong() }
        return null
    }

    /** HA's `timerTimeRemaining`: `remaining` while idle or paused, the time to `finishes_at` while active. */
    fun timerRemaining(s: EntityState, nowMs: Long): Double? {
        val remaining = s.attributes["remaining"] as? String
        if (remaining.isNullOrEmpty()) return null
        var left = durationToSeconds(remaining)
        if (s.state == "active") {
            val finishes = (s.attributes["finishes_at"] as? String)?.let { EntityStore.parseIsoEpochSeconds(it) }
            left = if (finishes == null) Double.NaN else max((finishes * 1000 - nowMs) / 1000.0, 0.0)
        }
        return left
    }

    /**
     * HA's `computeDisplayTimer` on the live remaining time: "4:32" (h:mm:ss, m:ss, or seconds alone), the state's own
     * word while idle or at 0, "(Paused)" after a paused timer's time. An active timer counts down every second.
     */
    fun timerDisplay(s: EntityState, nowMs: Long, ticks: (Long) -> Unit): String {
        val left = timerRemaining(s, nowMs)
        if (s.state == "active") ticks(1_000L)
        if (s.state == "idle" || left == 0.0) return formatEntityState(s)
        // `secondsToDuration(timeRemaining || 0) || "0"`: NaN and undefined count as 0.
        val d = if (left == null || left.isNaN()) 0.0 else left
        var display = secondsToDuration(d) ?: "0"
        if (s.state == "paused") display = "$display (${formatEntityState(s)})"
        return display
    }

    /** HA's `secondsToDuration`. */
    fun secondsToDuration(d: Double): String? {
        val h = floor(d / 3600).toLong()
        val m = floor((d % 3600) / 60).toLong()
        val s = floor((d % 3600) % 60).toLong()
        fun pad(n: Long) = if (n < 10) "0$n" else "$n"
        return when {
            h > 0 -> "$h:${pad(m)}:${pad(s)}"
            m > 0 -> "$m:${pad(s)}"
            s > 0 -> "$s"
            else -> null
        }
    }

    /** HA's `durationToSeconds` ("0:04:30" -> 270): hours, minutes and seconds, a fraction kept. */
    private fun durationToSeconds(duration: String): Double {
        val parts = duration.split(":").map { it.trim().toDoubleOrNull() ?: Double.NaN }
        if (parts.size < 3) return Double.NaN
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    }

    /**
     * HA's `computeUpdateStateDisplay`: "Installing (40%)" or "Installing" while an install runs, the skipped version
     * when the latest one was skipped, else the state.
     */
    private fun updateDisplay(s: EntityState): String {
        val a = s.attributes
        val inProgress = a["in_progress"].let { it != null && !isFalsy(it) }
        val supportsProgress = ((a["supported_features"] as? Number)?.toLong() ?: 0L) and UPDATE_PROGRESS != 0L
        if (inProgress) {
            val pct = a["update_percentage"]
            if (supportsProgress && pct == null) return HaText.ui("ui.card.update.preparing")
            if (supportsProgress) {
                val digits = (a["display_precision"] as? Number)?.toInt()
                return HaText.ui("ui.card.update.installing_with_progress", "progress" to
                    HaFormat.formatNumber(pct, HaLocale.current, HaFormat.NumberOptions(digits, digits)))
            }
            return HaText.ui("ui.card.update.installing")
        }
        if (s.state == "off") {
            val latest = a["latest_version"]
            if (latest != null && !isFalsy(latest) && a["skipped_version"] == latest) return latest.toString()
        }
        return formatEntityState(s)
    }

    /** HA's `computeAttributeValueDisplay` for a value that isn't null. */
    fun attributeValue(s: EntityState, attribute: String, value: Any, config: JSONObject): String {
        val domain = s.domain
        if (value is Number) {
            val v = value.toDouble()
            val formatted = when {
                domain == "light" && attribute == "brightness" -> jsRound(v / 255 * 100).toString()
                domain == "media_player" && attribute == "volume_level" -> jsRound(v * 100).toString()
                domain == "media_player" && attribute == "media_duration" -> durationDigital(v)
                domain == "input_datetime" && attribute == "year" -> jsString(v)
                else -> HaFormat.formatNumber(value, HaLocale.current)
            }
            val unitSystem = config.optJSONObject("unit_system")
            val unit = when {
                domain == "weather" -> weatherUnit(s, attribute, unitSystem)
                attribute in TEMPERATURE_ATTRIBUTES -> unitSystem?.optString("temperature", "")
                else -> DOMAIN_ATTRIBUTES_UNITS[domain]?.get(attribute)
            }?.takeIf { it.isNotEmpty() }
            return if (unit == null) formatted else formatted + HaFormat.blankBeforeUnit(unit, HaLocale.current) + unit
        }
        if (value is String && DATE_START.containsMatchIn(value)) {
            if (TIMESTAMP.matches(value)) {
                jsDate(value)?.let { return HaFormat.formatDateTimeWithSeconds(it) }
            }
            if (DATE_ONLY.matches(value)) {
                // A date alone is the same calendar day anywhere: midnight on the panel's clock, shown there.
                val parts = value.split('-').map { it.toInt() }
                val cal = Calendar.getInstance(HaLocale.deviceTimeZone()).apply {
                    clear(); set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
                }
                return HaFormat.formatDate(cal.timeInMillis, HaLocale.current.copy(timeZone = "local"))
            }
            jsDate(value)?.let { return HaFormat.formatDate(it) }
        }
        if (value is Map<*, *> || (value is List<*> && value.any { it is Map<*, *> || it is List<*> })) {
            return jsonStringify(value)
        }
        if (value is List<*>) {
            return value.joinToString(", ") { item ->
                if (item == null) HaText.ui("state.default.unknown") else attributeValue(s, attribute, item, config)
            }
        }
        val text = jsString(value)
        return HaDisplay.attributeLabel(s, attribute, text) ?: text
    }

    /** HA's `formatDurationDigital` for whole hours, minutes and seconds: "1:02:03" or "2:03". */
    private fun durationDigital(v: Double): String {
        val h = floor(v / 3600).toLong()
        val m = floor((v % 3600) / 60).toLong()
        val s = v % 60
        val sec = HaFormat.formatNumber(s, HaLocale.current, HaFormat.NumberOptions(maximumFractionDigits = 0)).padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
    }

    /** HA's `getWeatherUnit`. */
    private fun weatherUnit(s: EntityState, measure: String, unitSystem: JSONObject?): String {
        val length = unitSystem?.optString("length", "").orEmpty()
        fun attr(k: String) = (s.attributes[k] as? String)?.takeIf { it.isNotEmpty() }
        return when (measure) {
            "visibility" -> attr("visibility_unit") ?: length
            "precipitation" -> attr("precipitation_unit") ?: if (length == "km") "mm" else "in"
            "pressure" -> attr("pressure_unit") ?: if (length == "km") "hPa" else "inHg"
            "apparent_temperature", "dew_point", "temperature", "templow" ->
                attr("temperature_unit") ?: unitSystem?.optString("temperature", "").orEmpty()
            "wind_gust_speed", "wind_speed" -> attr("wind_speed_unit") ?: "$length/h"
            "cloud_coverage", "humidity", "precipitation_probability" -> "%"
            else -> unitSystem?.optString(measure, "").orEmpty()
        }
    }

    /** JavaScript's `String(value)` for a scalar. */
    private fun jsString(v: Any): String = when (v) {
        is Double -> if (v == floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e21) v.toLong().toString() else v.toString()
        is Float -> jsString(v.toDouble())
        else -> v.toString()
    }

    /** `JSON.stringify` of an attribute's object or list, keys in their order. */
    private fun jsonStringify(v: Any?): String = when (v) {
        null -> "null"
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> JSONObject.quote(k.toString()) + ":" + jsonStringify(x) }
        is List<*> -> v.joinToString(",", "[", "]") { jsonStringify(it) }
        is String -> JSONObject.quote(v)
        is Number -> jsString(v)
        else -> v.toString()
    }

    /** JavaScript's `Math.round`: halves go up. */
    private fun jsRound(v: Double): Long = floor(v + 0.5).toLong()

    /** HA's `TEMPERATURE_ATTRIBUTES`. */
    private val TEMPERATURE_ATTRIBUTES = setOf(
        "temperature", "current_temperature", "target_temperature", "target_temp_temp", "target_temp_high",
        "target_temp_low", "target_temp_step", "min_temp", "max_temp",
    )

    /** HA's `DOMAIN_ATTRIBUTES_UNITS`. */
    private val DOMAIN_ATTRIBUTES_UNITS: Map<String, Map<String, String>> = mapOf(
        "climate" to listOf("humidity", "current_humidity", "target_humidity_low", "target_humidity_high", "target_humidity_step",
            "min_humidity", "max_humidity").associateWith { "%" },
        "cover" to mapOf("current_position" to "%", "current_tilt_position" to "%"),
        "fan" to mapOf("percentage" to "%"),
        "humidifier" to listOf("humidity", "current_humidity", "min_humidity", "max_humidity", "target_humidity_step").associateWith { "%" },
        "light" to mapOf("color_temp" to "mired", "max_mireds" to "mired", "min_mireds" to "mired", "color_temp_kelvin" to "K",
            "min_color_temp_kelvin" to "K", "max_color_temp_kelvin" to "K", "brightness" to "%"),
        "sun" to mapOf("azimuth" to "°", "elevation" to "°"),
        "valve" to mapOf("current_position" to "%"),
        "sensor" to mapOf("battery_level" to "%"),
        "media_player" to mapOf("volume_level" to "%"),
    )

    /** `UpdateEntityFeature.PROGRESS`. */
    private const val UPDATE_PROGRESS = 4L

    /** HA's `isDate(…, true)` and `isDate`. */
    private val DATE_START = Regex("""^\d{4}-(0[1-9]|1[0-2])-([12]\d|0[1-9]|3[01])""")
    private val DATE_ONLY = Regex("""^\d{4}-(0[1-9]|1[0-2])-([12]\d|0[1-9]|3[01])$""")

    /** HA's `isTimestamp`. */
    private val TIMESTAMP = Regex(
        """^\d{4}-(0[1-9]|1[0-2])-([12]\d|0[1-9]|3[01])[T| ](((([01]\d|2[0-3])((:?)[0-5]\d)?|24:?00)([.,]\d+(?!:))?)(\8[0-5]\d([.,]\d+)?)?([zZ]|([+-])([01]\d|2[0-3]):?([0-5]\d)?)?)$""",
    )
}
