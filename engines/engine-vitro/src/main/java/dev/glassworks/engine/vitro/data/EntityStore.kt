/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data

import dev.glassworks.engine.vitro.VitroFailures
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * The name HA's cards show for this entity when their config sets none (2026's entity naming): its device and its
     * own name ("LED Strip" + "Window"), or [friendlyName] for an entity on no device. Filters and sorts that match on
     * `friendly_name` keep [friendlyName].
     */
    val displayName: String get() = HaDisplay.deviceEntityName(entityId) ?: friendlyName

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
            // HA's stateActive(): anything but closed / standby / idle-docked-paused, once it isn't off or unavailable.
            "cover" -> state != "closed" && !isUnavailable
            "climate" -> state != "off" && !isUnavailable
            "media_player" -> state != "standby" && state != "off" && !isUnavailable
            "person", "device_tracker" -> state == "home"
            "vacuum" -> state !in setOf("idle", "docked", "paused", "off") && !isUnavailable
            else -> state == "on" || state == "active" || state == "open"
        }

    /** Light brightness in 0..100, or null if unavailable. */
    val brightnessPct: Int?
        get() = (attributes["brightness"] as? Number)?.toDouble()?.let { kotlin.math.round((it / 255.0) * 100.0).toInt().coerceIn(0, 100) }

    /**
     * The color a lit light shines, as HA 2026 tints its icon and sliders: `rgb_color` when set, else its color
     * temperature (`color_temp_kelvin`, or `color_temp` in mireds) on a warm-amber → cool-white scale. Null for
     * anything that isn't an `on` light or a light that reports no color.
     */
    fun lightTintColor(): Int? {
        if (domain != "light" || !isActive) return null
        val rgb = attributes["rgb_color"] as? List<*>
        if (rgb != null && rgb.size >= 3) {
            val c = rgb.take(3).map { (it as? Number)?.toInt() }
            if (c.all { it != null }) return LightColor.pack(c[0]!!, c[1]!!, c[2]!!)
        }
        val kelvin = (attributes["color_temp_kelvin"] as? Number)?.toDouble()
            ?: (attributes["color_temp"] as? Number)?.toDouble()?.takeIf { it > 0 }?.let { 1_000_000.0 / it }
        return kelvin?.let { LightColor.kelvinToArgb(it.toInt()) }
    }

    fun formattedState(): String {
        if (isUnavailable) return state.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val u = unit
        // HA's computeStateDisplay: numeric states (a unit, a state class, or a number domain) go through
        // formatNumber with the registry's display precision; "%" takes no space.
        val numeric = u != null || attributes["state_class"] != null || domain in NUMBER_DOMAINS
        if (numeric && state.trim().toBigDecimalOrNull() != null) {
            val n = HaDisplay.formatState(state, HaDisplay.displayPrecision(entityId), (attributes["step"] as? Number)?.toDouble())
            return when (u) { null -> n; "%" -> "$n%"; else -> "$n $u" }
        }
        if (u != null) return if (u == "%") "$state$u" else "$state $u"
        if (domain != "light" && domain != "cover" && domain != "climate" && domain != "fan") HaDisplay.stateLabel(this)?.let { return it }
        // Options and text are the user's own strings: HA shows them as they are, never capitalized.
        if (domain in VERBATIM_DOMAINS) return state
        return when (domain) {
            "light" -> {
                val pct = brightnessPct
                if (state == "on" && pct != null) "$pct%" else state.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
            // HA's tile shows a running fan's speed instead of "On".
            "fan" -> (attributes["percentage"] as? Number)?.toInt()?.takeIf { state == "on" }?.let { "$it%" }
                ?: HaDisplay.stateLabel(this) ?: state.replaceFirstChar { it.titlecase(Locale.ROOT) }
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
     * The state as HA's `formatEntityState` writes it everywhere but a tile: translated, without the tile's extras
     * (a light's brightness, a cover's position, a fan's speed, a climate's temperatures).
     */
    fun plainState(): String {
        if (domain !in TILE_EXTRA_DOMAINS || isUnavailable) return formattedState()
        return HaDisplay.stateLabel(this) ?: state.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
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
        for ((k, v) in attributes) attrs.put(k, EntityStore.toJsonValue(v))
        val o = JSONObject()
            .put("entity_id", entityId)
            .put("state", state)
            .put("attributes", attrs)
        // Timestamps as HA writes them; one Vitro never received stays absent rather than becoming 1970.
        if (lastChanged > 0.0) o.put("last_changed", EntityStore.formatHaIso(lastChanged))
        if (lastUpdated > 0.0) o.put("last_updated", EntityStore.formatHaIso(lastUpdated))
        return o
    }
}

/**
 * Reactive Home Assistant entity store.
 *
 * Unlike HA's browser frontend (which clones `hass.states` and cascades `set hass` across every card on
 * the page), [EntityStore] maintains per-entity subscriber lists so a sensor tick only wakes the exact
 * cards bound to that `entity_id`.
 */
private val NUMBER_DOMAINS = setOf("number", "input_number", "counter")
private val TILE_EXTRA_DOMAINS = setOf("light", "cover", "fan", "climate")
private val VERBATIM_DOMAINS = setOf("select", "input_select", "text", "input_text")

class EntityStore {
    private val states = ConcurrentHashMap<String, EntityState>()
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(EntityState?) -> Unit>>()
    private val anyListeners = CopyOnWriteArrayList<(Set<String>) -> Unit>()

    /**
     * Clarity's harness (VitroClarity) while it measures: each changed entity of a `subscribe_entities` diff and the
     * change's context id, on the reader thread. Null otherwise.
     */
    interface ContextTap {
        /** The change was read, before the cards hear of it. */
        fun read(entityId: String, context: String?)

        /** The cards were told (each has queued its render), after [read]. */
        fun delivered(entityId: String, context: String?)
    }

    @Volatile var contextTap: ContextTap? = null

    val updatesReceived = AtomicLong(0)
    val notificationsDispatched = AtomicLong(0)

    operator fun get(entityId: String): EntityState? = states[entityId]
    fun size(): Int = states.size
    fun all(): Map<String, EntityState> = states

    /** Subscribes to changes for one [entityId]. Returns an unsubscribe handle. */
    fun subscribe(entityId: String, listener: (EntityState?) -> Unit): () -> Unit {
        // getOrPut on a ConcurrentMap is get + putIfAbsent: computeIfAbsent needs API 24, and this module is minSdk 23.
        val list = listeners.getOrPut(entityId) { CopyOnWriteArrayList() }
        list += listener
        return { list -= listener }
    }

    /**
     * The entity ids the current `subscribe_entities` asked for (empty means every entity), once its first event
     * has arrived; null before that. An id it covers that has no state then doesn't exist in HA.
     */
    private val snapshot = AtomicReference(Snapshot(null, null))

    /** The subscription expected to answer next ([owner], any token) and, once it has, its [filter]. */
    private class Snapshot(val owner: Any?, val filter: Set<String>?)

    /** True when HA has answered for [entityId] and has no such entity: HA's cards show "Entity not found". */
    fun isMissing(entityId: String): Boolean {
        val f = snapshot.get().filter ?: return false
        return (f.isEmpty() || entityId in f) && states[entityId] == null
    }

    /**
     * A new subscription, [owner], is on its way: nothing counts as missing until its first event. Lock-free, so the
     * UI thread switching views never waits for a snapshot being applied: that one's [markSnapshot] then no longer
     * lands.
     */
    fun resetSnapshot(owner: Any? = null) {
        snapshot.set(Snapshot(owner, null))
    }

    /**
     * The first event of [owner]'s subscription for [filter] has been applied: tells the missing ids' listeners.
     * False, and nothing changes, when another subscription has been asked for since ([resetSnapshot]).
     */
    fun markSnapshot(filter: Set<String>, owner: Any? = null): Boolean {
        val current = snapshot.get()
        if (current.owner !== owner || !snapshot.compareAndSet(current, Snapshot(owner, filter))) return false
        for ((id, list) in listeners) {
            if (states[id] == null && (filter.isEmpty() || id in filter)) list.forEach { it(null) }
        }
        return true
    }

    /** Calls every listener again with its entity's current state (HA's display data arrived: re-render). */
    fun renotifyAll() {
        // Listeners of [DISPLAY_KEY] (no entity has that id) hear only this: a card that reads HA's registries alone.
        for ((id, list) in listeners) {
            val st = states[id]
            list.forEach { it(st) }
        }
    }

    /** Subscribes to any batch of changed entity IDs (used for visibility / islands). */
    fun subscribeAny(listener: (Set<String>) -> Unit): () -> Unit {
        anyListeners += listener
        return { anyListeners -= listener }
    }

    /**
     * Applies HA's `subscribe_entities` event payload (`a` initial map, `c` compressed diffs, `r` removals).
     * [snapshotScope] non-null marks the subscription's first event, a snapshot of those entity ids (empty: every
     * entity): one of them missing from its `a` map no longer exists and is removed.
     */
    fun applyCompressedEvent(ev: JSONObject, snapshotScope: Set<String>? = null): Set<String> {
        val changed = LinkedHashSet<String>()

        ev.optJSONObject("a")?.let { added ->
            val keys = added.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val obj = added.optJSONObject(id) ?: continue
                val lc = obj.optDouble("lc", 0.0)
                val entity = EntityState(
                    entityId = id,
                    state = obj.optString("s", ""),
                    attributes = toMap(obj.optJSONObject("a")),
                    lastChanged = lc,
                    // HA leaves `lu` out when it equals `lc`.
                    lastUpdated = obj.optDouble("lu", lc),
                )
                states[id] = entity
                changed += id
            }
            if (snapshotScope != null) {
                val scope = if (snapshotScope.isEmpty()) states.keys.toList() else snapshotScope
                for (id in scope) {
                    if (!added.has(id) && states.remove(id) != null) changed += id
                }
            }
        }

        val tap = contextTap
        val tapped = if (tap != null) ArrayList<Pair<String, String?>>() else null
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
                    // A diff carries `lc` alone when the state changed (then last_updated is the same moment).
                    if (plus.has("lu")) lu = plus.optDouble("lu", lu) else if (plus.has("lc")) lu = lc
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
                if (tap != null && tapped != null) {
                    val ctx = diff.optJSONObject("+")?.opt("c") as? String
                    tap.read(id, ctx)
                    tapped += id to ctx
                }
            }
        }

        ev.optJSONArray("r")?.let { removed ->
            for (i in 0 until removed.length()) {
                val id = removed.optString(i)
                if (states.remove(id) != null) changed += id
            }
        }

        if (changed.isNotEmpty()) dispatch(changed)
        if (tap != null) tapped?.forEach { (id, ctx) -> tap.delivered(id, ctx) }
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

    /** Forgets every entity (the page moved to another Home Assistant) and tells their listeners they are gone. */
    fun clear() {
        // The old server's snapshot says nothing about which entities the next one lacks (the subscription on its
        // way, if any, still marks its own).
        while (true) {
            val current = snapshot.get()
            if (snapshot.compareAndSet(current, Snapshot(current.owner, null))) break
        }
        val gone = LinkedHashSet(states.keys)
        states.clear()
        if (gone.isNotEmpty()) dispatch(gone)
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
                // One listener that throws (a card's) doesn't stop the others, or the socket reader that dispatches.
                try {
                    fn(value)
                } catch (e: Exception) {
                    VitroFailures.report("update", null, e)
                }
            }
        }
        for (fn in anyListeners) {
            try {
                fn(changed)
            } catch (e: Exception) {
                VitroFailures.report("update", null, e)
            }
        }
    }

    companion object {
        /**
         * Not an entity id (no dot): [subscribe] to it to hear when HA's display data (registries, icons, labels)
         * lands, through [renotifyAll]. For cards that read the registries but follow no entity, like the area card.
         */
        const val DISPLAY_KEY = "vitro:display"

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

        /** Epoch seconds as HA's ISO form in UTC, with microseconds: `2026-11-24T10:00:00.123456+00:00`. */
        fun formatHaIso(epochSec: Double): String {
            val micros = Math.round(epochSec * 1_000_000.0)
            val ms = micros / 1_000L // positive: a timestamp Vitro got (floorDiv is API 24)
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val frac = micros % 1_000_000L
            return fmt.format(java.util.Date(ms)) + "." + frac.toString().padStart(6, '0') + "+00:00"
        }

        /** [v] from [unwrap] back as a JSON value: Android's `JSONObject.put` would write a List or Map as its toString(). */
        internal fun toJsonValue(v: Any?): Any = when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().also { o -> for ((k, x) in v) o.put(k.toString(), toJsonValue(x)) }
            is List<*> -> JSONArray().also { a -> for (x in v) a.put(toJsonValue(x)) }
            else -> v
        }

        private fun unwrap(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONArray -> (0 until v.length()).map { unwrap(v.opt(it)) }
            is JSONObject -> toMap(v)
            else -> v
        }
    }
}

/** Light color math shared by cards and More-Info. Pure integer ARGB, so it runs in plain JVM tests. */
object LightColor {
    /** Kelvin stops on HA's scale: warm amber, soft warm, crisp white, cool white. */
    private val STOPS = listOf(
        2000 to 0xFF9E44,
        3000 to 0xFFD5A1,
        4500 to 0xFFF4E4,
        6500 to 0xCFE2FF,
    )

    /** Opaque ARGB from 0..255 channels (clamped). */
    fun pack(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun red(c: Int): Int = (c shr 16) and 0xFF
    fun green(c: Int): Int = (c shr 8) and 0xFF
    fun blue(c: Int): Int = c and 0xFF

    /** A color temperature as the opaque ARGB a light shows, interpolated between [STOPS] and clamped to 2000–6500 K. */
    fun kelvinToArgb(kelvin: Int): Int {
        val k = kelvin.coerceIn(STOPS.first().first, STOPS.last().first)
        for (i in 0 until STOPS.size - 1) {
            val (k0, c0) = STOPS[i]
            val (k1, c1) = STOPS[i + 1]
            if (k <= k1) {
                val t = (k - k0).toFloat() / (k1 - k0)
                fun mix(a: Int, b: Int) = Math.round(a + (b - a) * t)
                return pack(mix(red(c0), red(c1)), mix(green(c0), green(c1)), mix(blue(c0), blue(c1)))
            }
        }
        return 0xFF000000.toInt() or STOPS.last().second
    }

    /** Hue (0..360), saturation and value (0..1) to opaque ARGB. */
    fun hsvToArgb(hue: Float, sat: Float, value: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val s = sat.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - kotlin.math.abs(h % 2f - 1f))
        val m = v - c
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return pack(Math.round((r + m) * 255f), Math.round((g + m) * 255f), Math.round((b + m) * 255f))
    }

    /** Opaque ARGB to hue (0..360) and saturation (0..1); value is ignored, as HA's wheel shows full value. */
    fun argbToHueSat(color: Int): Pair<Float, Float> {
        val r = red(color) / 255f
        val g = green(color) / 255f
        val b = blue(color) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val hue = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val sat = if (max == 0f) 0f else d / max
        return hue to sat
    }
}
