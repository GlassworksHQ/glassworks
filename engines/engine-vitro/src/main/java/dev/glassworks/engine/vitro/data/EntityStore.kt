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
        // HA's computeStateDisplay: "Unavailable" and "Unknown" are the frontend's own words.
        if (isUnavailable) return HaText.ui(if (state == "unknown") "state.default.unknown" else "state.default.unavailable")
        val u = unit
        // Numeric states (a unit, a state class, or a number domain) go through formatNumber with the registry's
        // display precision, in the user's number format; the unit follows with HA's blankBeforeUnit.
        val numeric = u != null || attributes["state_class"] != null || domain in NUMBER_DOMAINS
        if (numeric && state.isNotBlank() && HaFormat.jsNumber(state)?.isNaN() == false) {
            val n = HaDisplay.formatState(state, HaDisplay.displayPrecision(entityId), (attributes["step"] as? Number)?.toDouble())
            return if (u == null) n else "$n${HaFormat.blankBeforeUnit(u)}$u"
        }
        if (u != null) return "$state${HaFormat.blankBeforeUnit(u)}$u"
        dateTimeState()?.let { return it }
        if (domain != "light" && domain != "cover" && domain != "climate" && domain != "fan") HaDisplay.stateLabel(this)?.let { return it }
        // Options and text are the user's own strings: HA shows them as they are, never capitalized.
        if (domain in VERBATIM_DOMAINS) return state
        return when (domain) {
            // HA's state-display (the tile, badges): a light's brightness, a fan's speed, a cover's position, each
            // only when the attribute is set and not 0, else the state.
            "light" -> {
                val pct = brightnessPct?.takeIf { (attributes["brightness"] as? Number)?.toDouble() != 0.0 }
                if (pct != null) percent(pct) else HaDisplay.stateLabel(this) ?: state.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
            "fan" -> (attributes["percentage"] as? Number)?.toInt()?.takeIf { it != 0 }?.let { percent(it) }
                ?: HaDisplay.stateLabel(this) ?: state.replaceFirstChar { it.titlecase(Locale.ROOT) }
            "binary_sensor" -> HaDisplay.stateLabel(this) ?: when (deviceClass) {
                "door", "window", "garage_door", "opening" -> if (state == "on") "Open" else "Closed"
                "motion", "occupancy", "presence" -> if (state == "on") "Detected" else "Clear"
                "lock" -> if (state == "on") "Unlocked" else "Locked"
                "moisture" -> if (state == "on") "Wet" else "Dry"
                "smoke", "gas", "problem" -> if (state == "on") "Detected" else "Clear"
                else -> if (state == "on") "On" else "Off"
            }
            "cover" -> {
                val pos = (attributes["current_position"] as? Number)?.toInt()
                val label = HaDisplay.stateLabel(this) ?: state.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
                if (pos != null && pos != 0) "$label · ${percent(pos)}" else label
            }
            "climate" -> {
                val cur = attributes["current_temperature"]
                val target = attributes["temperature"]
                val action = attributes["hvac_action"] as? String
                buildString {
                    append(action?.let { HaDisplay.attributeLabel(this@EntityState, "hvac_action", it) }
                        ?: (if (action == null) HaDisplay.stateLabel(this@EntityState) else null)
                        ?: (action ?: state).replaceFirstChar { it.titlecase(Locale.ROOT) })
                    (target ?: cur)?.let { append(" · ").append(HaFormat.formatNumber(it)).append("°") }
                }
            }
            else -> state.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }

    /**
     * HA's `computeStateDisplay` for a date or a time (frontend 20261028.5): a `date`, `input_datetime` or `time`
     * state in the panel's own zone, and a timestamp state ([isTimestampState]) with `formatDateTime`. Text that
     * isn't a date stays as it is, as HA's `try` around the formatter leaves it. Null for any other state.
     */
    private fun dateTimeState(): String? {
        if (domain in DATE_DOMAINS) {
            val local = HaLocale.current.copy(timeZone = "local")
            val parts = state.split(' ')
            return when {
                parts.size == 2 -> jsDateMs("${parts[0]}T${parts[1]}")?.let { HaFormat.formatDateTime(it, local) }
                parts.size == 1 && '-' in state -> jsDateMs("${state}T00:00")?.let { HaFormat.formatDate(it, local) }
                parts.size == 1 && ':' in state -> {
                    // `new Date(<today in UTC>T<time>)`: the time on today's date, read in the panel's zone.
                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
                    jsDateMs("${today}T$state")?.let { HaFormat.formatTime(it, local) }
                }
                else -> null
            } ?: state
        }
        if (!isTimestampState) return null
        return jsDateMs(state)?.let { HaFormat.formatDateTime(it) } ?: state
    }

    /**
     * Whether HA formats this state as a point in time (`computeStateDisplay`): the domains whose state is the time of
     * their last event (a button's press, a scene's activation, an event's fire, …) and a `sensor` whose
     * `device_class` is `timestamp` or `uptime`.
     */
    val isTimestampState: Boolean
        get() = domain in TIMESTAMP_DOMAINS || (domain == "sensor" && deviceClass in TIMESTAMP_CLASSES)

    /**
     * The state as `hui-timestamp-display` draws it where HA's cards use one (`state-display`, the sensor row, the
     * glance card), or null when they'd show [formattedState] instead: unavailable, unknown, or not a timestamp.
     * [format] is the display's: `relative` ("In 3 hours", "5 minutes ago"), `total` (an uptime's "3 days"),
     * `datetime`, `date` or `time`; null picks HA's default, `total` for an uptime and `relative` otherwise.
     * [anyDomain] is `state-display`'s test, which reads the `device_class` of any entity, not only a sensor's.
     */
    fun timestampDisplay(nowMs: Long, format: String? = null, anyDomain: Boolean = false): String? {
        if (isUnavailable) return null
        val timestamp = isTimestampState || (anyDomain && deviceClass in TIMESTAMP_CLASSES)
        if (!timestamp) return null
        val ts = jsDateMs(state) ?: return HaText.ui("ui.panel.lovelace.components.timestamp-display.invalid").ifEmpty { "Invalid timestamp" }
        val text = when (format ?: if (deviceClass == "uptime") "total" else "relative") {
            "relative" -> HaFormat.relativeTime(ts, nowMs)
            "total" -> HaFormat.relativeTime(nowMs, ts, includeTense = false)
            "datetime" -> HaFormat.formatDateTime(ts)
            "date" -> HaFormat.formatDate(ts)
            "time" -> HaFormat.formatTime(ts)
            else -> return HaText.ui("ui.panel.lovelace.components.timestamp-display.invalid_format").ifEmpty { "Invalid display format" }
        }
        // `capitalize` applies to the relative and total forms only (the formatters' own text is left alone).
        return if (format == null || format == "relative" || format == "total") HaFormat.capitalize(text) else text
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
     * How long ago [lastChanged] was, as `<ha-relative-time capitalize>` writes it in the user's language ("Now", "5
     * minutes ago", "Yesterday"), or null when it is unknown (<= 0). [nowEpochSec] is a parameter so tests (and a
     * ticking card) control the clock.
     */
    fun relativeLastChanged(nowEpochSec: Double = System.currentTimeMillis() / 1000.0): String? {
        if (lastChanged <= 0.0) return null
        return HaFormat.capitalize(HaFormat.relativeTime((lastChanged * 1000).toLong(), (nowEpochSec * 1000).toLong()))
    }

    /** A percentage as HA's tile writes it: the number in the user's format and the language's space before "%". */
    private fun percent(n: Int): String = "${HaFormat.formatNumber(n)}${HaFormat.blankBeforePercent(HaLocale.current)}%"

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

/** HA's TIMESTAMP_STATE_DOMAINS (frontend 20261028.5): domains whose state is the time of their last event. */
private val TIMESTAMP_DOMAINS = setOf(
    "ai_task", "button", "conversation", "event", "image", "infrared", "input_button", "notify", "radio_frequency",
    "scene", "stt", "tag", "tts", "wake_word", "datetime",
)

/** HA's sensor device classes drawn as a point in time (`SENSOR_DEVICE_CLASS_TIMESTAMP` and `uptime`). */
private val TIMESTAMP_CLASSES = setOf("timestamp", "uptime")

/** Domains whose state is a local date, time or both (`computeStateDisplay`'s first date branch). */
private val DATE_DOMAINS = setOf("date", "input_datetime", "time")

private val JS_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?\s*(Z|[+-]\d{2}(?::?\d{2})?)?)?$""")

/**
 * What JavaScript's `new Date(text)` makes of an ISO date, in epoch ms: a date and time with an offset at that
 * instant, one without an offset in the panel's zone, and a date alone at UTC midnight. Null where JavaScript's would
 * be an Invalid Date (HA's formatters then throw and show the text as it is).
 */
internal fun jsDateMs(text: String): Long? {
    val m = JS_DATE.matchEntire(text.trim()) ?: return null
    val g = m.groupValues
    val year = g[1].toInt(); val month = g[2].toInt(); val day = g[3].toInt()
    val hasTime = g[4].isNotEmpty()
    val hour = if (hasTime) g[4].toInt() else 0
    val minute = if (hasTime) g[5].toInt() else 0
    val second = g[6].toIntOrNull() ?: 0
    val millis = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.substring(0, 3)?.toInt() ?: 0
    if (month !in 1..12 || day !in 1..31 || hour > 24 || minute > 59 || second > 59) return null
    val zone = g[8]
    val tz = when {
        !hasTime || zone == "Z" -> java.util.TimeZone.getTimeZone("UTC")
        zone.isEmpty() -> HaLocale.deviceTimeZone()
        else -> {
            val digits = zone.substring(1).replace(":", "")
            val minutes = digits.substring(0, 2).toInt() * 60 + (if (digits.length >= 4) digits.substring(2, 4).toInt() else 0)
            java.util.SimpleTimeZone((if (zone[0] == '-') -minutes else minutes) * 60_000, "")
        }
    }
    val cal = java.util.Calendar.getInstance(tz).apply {
        isLenient = false
        clear()
        set(year, month - 1, day, hour, minute, second)
        set(java.util.Calendar.MILLISECOND, millis)
    }
    return runCatching { cal.timeInMillis }.getOrNull()
}
private val TILE_EXTRA_DOMAINS = setOf("light", "cover", "fan", "climate")
private val VERBATIM_DOMAINS = setOf("select", "input_select", "text", "input_text")

class EntityStore {
    private val states = ConcurrentHashMap<String, EntityState>()
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(EntityState?) -> Unit>>()
    private val anyListeners = CopyOnWriteArrayList<(Set<String>) -> Unit>()

    /** Told after every [markSnapshot] that lands: HA has answered for more entities ([isAnswered]). */
    private val snapshotListeners = CopyOnWriteArrayList<() -> Unit>()

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

    /** How many listeners follow [entityId] now ([subscribe]). Tests. */
    internal fun listenerCount(entityId: String): Int = listeners[entityId]?.size ?: 0

    /** Subscribes to changes for one [entityId]. Returns an unsubscribe handle. */
    fun subscribe(entityId: String, listener: (EntityState?) -> Unit): () -> Unit {
        // getOrPut on a ConcurrentMap is get + putIfAbsent: computeIfAbsent needs API 24, and this module is minSdk 23.
        val list = listeners.getOrPut(entityId) { CopyOnWriteArrayList() }
        list += listener
        return { list -= listener }
    }

    /**
     * The entity ids the current entity subscriptions asked for (empty means every entity), once their first events
     * have arrived; null before the first. An id it covers that has no state then doesn't exist in HA.
     */
    private val snapshot = AtomicReference(Snapshot(null, null))

    /** The subscription expected to answer next ([owner], any token) and, once it has, its [filter]. */
    private class Snapshot(val owner: Any?, val filter: Set<String>?)

    /** True when HA's answer has covered [entityId]: it has a state, or HA said it has no such entity ([isMissing]). */
    fun isAnswered(entityId: String): Boolean = states[entityId] != null || isMissing(entityId)

    /** True once the current subscription's answer has landed, for some entities or every one. */
    val anyAnswered: Boolean get() = snapshot.get().filter != null

    /** True once the current subscription's answer covers every entity (it followed them all, an empty filter). */
    val answeredAll: Boolean get() = snapshot.get().filter?.isEmpty() == true

    /** Calls [listener] after each part of HA's answer lands ([markSnapshot]); returns its unsubscribe. */
    fun subscribeSnapshot(listener: () -> Unit): () -> Unit {
        snapshotListeners += listener
        return { snapshotListeners -= listener }
    }

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
     *
     * One owner may answer in several parts (the dashboard's entities in more than one `subscribe_entities`: the view
     * on screen first, the rest after it, and ids a config change adds): what HA has answered for is their union.
     */
    fun markSnapshot(filter: Set<String>, owner: Any? = null): Boolean {
        var current: Snapshot
        do {
            current = snapshot.get()
            if (current.owner !== owner) return false
            val known = current.filter
            val answered = when {
                known == null -> filter
                // Empty is every entity: nothing widens it, and it covers any part.
                known.isEmpty() || filter.isEmpty() -> emptySet()
                else -> known + filter
            }
        } while (!snapshot.compareAndSet(current, Snapshot(owner, answered)))
        for ((id, list) in listeners) {
            if (states[id] == null && (filter.isEmpty() || id in filter)) list.forEach { notify(it, null) }
        }
        for (fn in snapshotListeners) {
            try { fn() } catch (e: Exception) { /* one listener's throw doesn't stop the others */ }
        }
        return true
    }

    /** Calls every listener again with its entity's current state (HA's display data arrived: re-render). */
    fun renotifyAll() {
        // Listeners of [DISPLAY_KEY] (no entity has that id) hear only this: a card that reads HA's registries alone.
        for ((id, list) in listeners) {
            val st = states[id]
            list.forEach { notify(it, st) }
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
                // A snapshot (every resubscribe starts with one) repeats entities that haven't changed: only a
                // different one counts, so the cards already showing it don't render again. The new instance is kept
                // either way: it is what HA reported, so an optimistic write it matches is confirmed, not rolled back.
                val prev = states.put(id, entity)
                if (prev != entity) changed += id
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

    /**
     * Calls one listener: one that throws (a card's) doesn't stop the others, or the socket reader that dispatches.
     * Every way the store tells its listeners goes through here ([dispatch], [markSnapshot], [renotifyAll]).
     */
    private fun notify(fn: (EntityState?) -> Unit, value: EntityState?) {
        try {
            fn(value)
        } catch (e: Exception) {
            VitroFailures.report("update", null, e)
        }
    }

    private fun dispatch(changed: Set<String>) {
        updatesReceived.addAndGet(changed.size.toLong())
        for (id in changed) {
            val subs = listeners[id] ?: continue
            val value = states[id]
            for (fn in subs) {
                notificationsDispatched.incrementAndGet()
                notify(fn, value)
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
