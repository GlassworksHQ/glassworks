/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Snapshot of one Home Assistant entity. Immutable for lock-free UI reads. */
data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: Map<String, Any?>,
    val lastChanged: Double = 0.0,
    val lastUpdated: Double = 0.0,
) {
    val domain: String get() = entityId.substringBefore('.', "")

    val friendlyName: String
        get() = (attributes["friendly_name"] as? String)?.takeIf { it.isNotBlank() }
            ?: entityId.substringAfter('.', entityId).replace('_', ' ')
                .replaceFirstChar { it.titlecase(Locale.ROOT) }

    val unit: String? get() = (attributes["unit_of_measurement"] as? String)?.takeIf { it.isNotEmpty() }
    val deviceClass: String? get() = (attributes["device_class"] as? String)?.takeIf { it.isNotEmpty() }
    val icon: String? get() = (attributes["icon"] as? String)?.takeIf { it.isNotEmpty() }
    val entityPicture: String? get() = (attributes["entity_picture"] as? String)?.takeIf { it.isNotEmpty() }

    val isUnavailable: Boolean get() = state == "unavailable" || state == "unknown"

    val isActive: Boolean
        get() = when (domain) {
            "light", "switch", "fan", "input_boolean", "automation", "script" -> state == "on"
            "binary_sensor" -> state == "on"
            "lock" -> state == "unlocked"
            "cover" -> state == "open" || state == "opening"
            "climate" -> state != "off" && !isUnavailable
            "media_player" -> state == "playing" || state == "buffering"
            "person", "device_tracker" -> state == "home"
            "vacuum" -> state == "cleaning" || state == "returning"
            else -> state == "on" || state == "active" || state == "open"
        }

    /** Light brightness in 0..100, or null if unavailable. */
    val brightnessPct: Int?
        get() = (attributes["brightness"] as? Number)?.toDouble()?.let { kotlin.math.round((it / 255.0) * 100.0).toInt().coerceIn(0, 100) }

    fun formattedState(): String {
        if (isUnavailable) return state.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val u = unit
        if (u != null) return if (u == "%") "$state$u" else "$state $u"
        return when (domain) {
            "light" -> {
                val pct = brightnessPct
                if (state == "on" && pct != null) "$pct%" else state.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
            "binary_sensor" -> when (deviceClass) {
                "door", "window", "garage_door", "opening" -> if (state == "on") "Open" else "Closed"
                "motion", "occupancy", "presence" -> if (state == "on") "Detected" else "Clear"
                "lock" -> if (state == "on") "Unlocked" else "Locked"
                "moisture" -> if (state == "on") "Wet" else "Dry"
                "smoke", "gas", "problem" -> if (state == "on") "Detected" else "Clear"
                else -> if (state == "on") "On" else "Off"
            }
            "cover" -> {
                val pos = (attributes["current_position"] as? Number)?.toInt()
                if (state == "open" && pos != null) "Open · $pos%" else state.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
            "climate" -> {
                val cur = attributes["current_temperature"]
                val target = attributes["temperature"]
                val action = attributes["hvac_action"] as? String
                buildString {
                    append((action ?: state).replaceFirstChar { it.titlecase(Locale.ROOT) })
                    if (target != null) append(" · $target°")
                    else if (cur != null) append(" · $cur°")
                }
            }
            else -> state.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }

    /**
     * "Just now", "5 minutes ago", "2 days ago" … for how long ago [lastChanged] was, or null when it is unknown (<= 0).
     * [nowEpochSec] is a parameter so tests (and a ticking card) control the clock.
     */
    fun relativeLastChanged(nowEpochSec: Double = System.currentTimeMillis() / 1000.0): String? {
        if (lastChanged <= 0.0) return null
        val delta = (nowEpochSec - lastChanged).toLong().coerceAtLeast(0L)
        fun unit(n: Long, name: String) = if (n == 1L) "1 $name ago" else "$n ${name}s ago"
        return when {
            delta < 60L -> "Just now"
            delta < 3_600L -> unit(delta / 60L, "minute")
            delta < 86_400L -> unit(delta / 3_600L, "hour")
            delta < 7L * 86_400L -> unit(delta / 86_400L, "day")
            delta < 30L * 86_400L -> unit(delta / (7L * 86_400L), "week")
            else -> unit(delta / (30L * 86_400L), "month")
        }
    }

    /** JSON object matching HA's `hass.states[entity_id]` shape for Tier 3 Micro-Web Islands. */
    fun toHassJson(): JSONObject {
        val attrs = JSONObject()
        for ((k, v) in attributes) attrs.put(k, v ?: JSONObject.NULL)
        return JSONObject()
            .put("entity_id", entityId)
            .put("state", state)
            .put("attributes", attrs)
    }
}

/**
 * Reactive Home Assistant entity store.
 *
 * Unlike HA's browser frontend (which clones `hass.states` and cascades `set hass` across every card on
 * the page), [EntityStore] maintains per-entity subscriber lists so a sensor tick only wakes the exact
 * cards bound to that `entity_id`.
 */
class EntityStore {
    private val states = ConcurrentHashMap<String, EntityState>()
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(EntityState?) -> Unit>>()
    private val anyListeners = CopyOnWriteArrayList<(Set<String>) -> Unit>()

    val updatesReceived = AtomicLong(0)
    val notificationsDispatched = AtomicLong(0)

    operator fun get(entityId: String): EntityState? = states[entityId]
    fun size(): Int = states.size
    fun all(): Map<String, EntityState> = states

    /** Subscribes to changes for one [entityId]. Returns an unsubscribe handle. */
    fun subscribe(entityId: String, listener: (EntityState?) -> Unit): () -> Unit {
        val list = listeners.computeIfAbsent(entityId) { CopyOnWriteArrayList() }
        list += listener
        return { list -= listener }
    }

    /** Subscribes to any batch of changed entity IDs (used for visibility / islands). */
    fun subscribeAny(listener: (Set<String>) -> Unit): () -> Unit {
        anyListeners += listener
        return { anyListeners -= listener }
    }

    /** Applies HA's `subscribe_entities` event payload (`a` initial map, `c` compressed diffs, `r` removals). */
    fun applyCompressedEvent(ev: JSONObject): Set<String> {
        val changed = LinkedHashSet<String>()

        ev.optJSONObject("a")?.let { added ->
            val keys = added.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val obj = added.optJSONObject(id) ?: continue
                val entity = EntityState(
                    entityId = id,
                    state = obj.optString("s", ""),
                    attributes = toMap(obj.optJSONObject("a")),
                    lastChanged = obj.optDouble("lc", 0.0),
                    lastUpdated = obj.optDouble("lu", 0.0),
                )
                states[id] = entity
                changed += id
            }
        }

        ev.optJSONObject("c")?.let { diffs ->
            val keys = diffs.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val diff = diffs.optJSONObject(id) ?: continue
                val prev = states[id]
                val attrs = HashMap<String, Any?>(prev?.attributes.orEmpty())
                var state = prev?.state.orEmpty()
                var lc = prev?.lastChanged ?: 0.0
                var lu = prev?.lastUpdated ?: 0.0

                diff.optJSONObject("+")?.let { plus ->
                    if (plus.has("s")) state = plus.optString("s", state)
                    if (plus.has("lc")) lc = plus.optDouble("lc", lc)
                    if (plus.has("lu")) lu = plus.optDouble("lu", lu)
                    plus.optJSONObject("a")?.let { a ->
                        val ak = a.keys()
                        while (ak.hasNext()) {
                            val k = ak.next()
                            attrs[k] = unwrap(a.opt(k))
                        }
                    }
                }
                diff.optJSONObject("-")?.let { minus ->
                    minus.optJSONArray("a")?.let { removed ->
                        for (i in 0 until removed.length()) attrs.remove(removed.optString(i))
                    }
                }

                states[id] = EntityState(id, state, attrs, lc, lu)
                changed += id
            }
        }

        ev.optJSONArray("r")?.let { removed ->
            for (i in 0 until removed.length()) {
                val id = removed.optString(i)
                if (states.remove(id) != null) changed += id
            }
        }

        if (changed.isNotEmpty()) dispatch(changed)
        return changed
    }

    /** Applies a full `get_states` JSON array. */
    fun applyStatesArray(arr: JSONArray): Set<String> {
        val changed = LinkedHashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("entity_id", "").ifEmpty { continue }
            states[id] = EntityState(
                entityId = id,
                state = o.optString("state", ""),
                attributes = toMap(o.optJSONObject("attributes")),
                lastChanged = parseTimestamp(o.opt("last_changed")),
                lastUpdated = parseTimestamp(o.opt("last_updated")),
            )
            changed += id
        }
        if (changed.isNotEmpty()) dispatch(changed)
        return changed
    }

    /** Local optimistic update (e.g. instant toggle visual feedback before HA replies). */
    fun putLocal(entity: EntityState) {
        states[entity.entityId] = entity
        dispatch(setOf(entity.entityId))
    }

    private fun dispatch(changed: Set<String>) {
        updatesReceived.addAndGet(changed.size.toLong())
        for (id in changed) {
            val subs = listeners[id] ?: continue
            val value = states[id]
            for (fn in subs) {
                notificationsDispatched.incrementAndGet()
                fn(value)
            }
        }
        for (fn in anyListeners) fn(changed)
    }

    companion object {
        private val ISO = Regex("""^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(?:\.(\d+))?\s*(Z|[+-]\d{2}(?::?\d{2})?)?$""")
        private val isoFormat = object : ThreadLocal<java.text.SimpleDateFormat>() {
            override fun initialValue() = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
                isLenient = false
            }
        }

        /**
         * A HA timestamp as epoch seconds: a number is taken as is, an ISO-8601 string (`2026-11-23T17:00:00+00:00`,
         * `...Z`, with or without fractional seconds) is parsed, anything else is 0.0. Done by hand around a UTC
         * [java.text.SimpleDateFormat] because `java.time` needs API 26 and the `X` pattern API 24, and this module is minSdk 23.
         */
        fun parseTimestamp(v: Any?): Double = when (v) {
            is Number -> v.toDouble()
            is String -> parseIsoEpochSeconds(v) ?: 0.0
            else -> 0.0
        }

        fun parseIsoEpochSeconds(text: String): Double? {
            val m = ISO.matchEntire(text.trim()) ?: return null
            val (date, time, fraction, zone) = m.destructured
            val base = try {
                isoFormat.get()!!.parse("${date}T$time")?.time ?: return null
            } catch (_: java.text.ParseException) {
                return null
            }
            var seconds = base / 1000.0
            if (fraction.isNotEmpty()) seconds += ("0.$fraction").toDouble()
            if (zone.isNotEmpty() && zone != "Z") {
                val sign = if (zone[0] == '-') -1 else 1
                val digits = zone.substring(1).replace(":", "")
                val hours = digits.substring(0, 2).toInt()
                val minutes = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
                seconds -= sign * (hours * 3_600 + minutes * 60)
            }
            return seconds
        }

        private fun toMap(obj: JSONObject?): Map<String, Any?> {
            if (obj == null) return emptyMap()
            val out = LinkedHashMap<String, Any?>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = unwrap(obj.opt(k))
            }
            return out
        }

        private fun unwrap(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONArray -> (0 until v.length()).map { unwrap(v.opt(it)) }
            is JSONObject -> toMap(v)
            else -> v
        }
    }
}
