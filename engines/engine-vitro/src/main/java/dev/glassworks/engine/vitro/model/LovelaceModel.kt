/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.model

import dev.glassworks.engine.vitro.data.EntityState
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** Rendering tier assigned by [CardRouter] for each Lovelace card. */
enum class CardTier {
    /** Built-in Home Assistant card rendered natively in Kotlin/Canvas (zero JS). */
    NATIVE_CORE,
    /** Popular HACS custom card rendered natively via a built-in fast-path adapter. */
    NATIVE_HACS,
    /** Arbitrary unrecognized custom card routed to a stripped Micro-Web Island. */
    WEB_ISLAND,
}

data class DashboardSummary(
    val urlPath: String,
    val title: String,
    val icon: String? = null,
    val requireAdmin: Boolean = false,
)

data class LovelaceResource(
    val url: String,
    val type: String, // "module", "js", "css"
)

data class DashboardConfig(
    val title: String?,
    val views: List<ViewConfig>,
)

data class ViewConfig(
    val index: Int,
    val title: String,
    val path: String?,
    val icon: String?,
    val type: String, // "masonry", "sections", "panel", "sidebar"
    val theme: String?,
    val background: String?,
    val maxColumns: Int,
    val sections: List<SectionConfig>,
    val cards: List<CardConfig>,
    val badges: List<CardConfig>,
    val subview: Boolean = false,
    val backPath: String? = null,
)

data class SectionConfig(
    val type: String, // "grid"
    val title: String?,
    val columnSpan: Int,
    val cards: List<CardConfig>,
    val visibility: List<VisibilityCondition>,
)

data class ActionConfig(
    val action: String, // "toggle", "more-info", "navigate", "call-service", "perform-action", "url", "none"
    val navigationPath: String? = null,
    val service: String? = null,
    val serviceData: JSONObject? = null,
    val target: JSONObject? = null,
    val urlPath: String? = null,
    /** Text of HA's `confirmation:` prompt (`true` gives "Are you sure?"), or null when the action runs straight away. */
    val confirmation: String? = null,
) {
    companion object {
        private const val DEFAULT_CONFIRMATION = "Are you sure?"

        /** Reads HA's `confirmation`: `true`, `false`, or `{ text: "..." }`. */
        fun parseConfirmation(raw: JSONObject): String? {
            val c = raw.opt("confirmation")
            return when {
                c is Boolean -> if (c) DEFAULT_CONFIRMATION else null
                c is JSONObject -> c.optString("text", "").ifEmpty { DEFAULT_CONFIRMATION }
                c is String && c.lowercase() == "true" -> DEFAULT_CONFIRMATION
                c is String && c.isNotEmpty() && c.lowercase() != "false" -> c
                else -> null
            }
        }

        fun parse(raw: JSONObject?, defaultAction: String = "more-info"): ActionConfig {
            if (raw == null) return ActionConfig(defaultAction)
            val action = raw.optString("action", defaultAction).ifEmpty { defaultAction }
            val service = raw.optString("perform_action", "").ifEmpty { raw.optString("service", "") }.ifEmpty { null }
            val data = raw.optJSONObject("data") ?: raw.optJSONObject("service_data")
            return ActionConfig(
                action = action,
                navigationPath = raw.optString("navigation_path", "").ifEmpty { null },
                service = service,
                serviceData = data,
                target = raw.optJSONObject("target"),
                urlPath = raw.optString("url_path", "").ifEmpty { null },
                confirmation = parseConfirmation(raw),
            )
        }
    }
}

data class EntityRowConfig(
    val entity: String,
    val name: String? = null,
    val icon: String? = null,
    val secondaryInfo: String? = null,
    val tapAction: ActionConfig? = null,
    val holdAction: ActionConfig? = null,
    /** `entity` for an entity row; `divider` and `section` for HA's special rows, which carry no entity. */
    val rowType: String = "entity",
) {
    val isEntity: Boolean get() = rowType == "entity"
}

sealed class VisibilityCondition {
    /**
     * HA's `state` condition. [states] and [statesNot] take one value or a list (`state: [playing, paused]`).
     * The single-value constructor keeps older call sites working.
     */
    data class State(val entity: String, val states: List<String>? = null, val statesNot: List<String>? = null) : VisibilityCondition() {
        constructor(entity: String, state: String?, stateNot: String?) :
            this(entity, state?.let { listOf(it) }, stateNot?.let { listOf(it) })
    }
    /**
     * HA's `numeric_state` condition. A bound may name an entity instead of a number ([aboveEntity], [belowEntity]):
     * its state is the bound then, and like HA a bound that isn't a number is ignored.
     */
    data class NumericState(
        val entity: String,
        val above: Double?,
        val below: Double?,
        val aboveEntity: String? = null,
        val belowEntity: String? = null,
    ) : VisibilityCondition()
    data class Screen(val mediaQuery: String) : VisibilityCondition()
    data class And(val conditions: List<VisibilityCondition>) : VisibilityCondition()
    data class Or(val conditions: List<VisibilityCondition>) : VisibilityCondition()
    data class Not(val conditions: List<VisibilityCondition>) : VisibilityCondition()
    /** HA's `user` condition: visible only to the listed user ids. */
    data class User(val users: List<String>) : VisibilityCondition()
    /**
     * HA's `time` condition. [after] and [before] are seconds from local midnight (0..86399); a window with
     * `after > before` runs overnight. [weekdays] holds [Calendar] day constants (null means every day).
     */
    data class Time(val after: Int?, val before: Int?, val weekdays: Set<Int>?) : VisibilityCondition()
    /**
     * A condition Vitro can't evaluate (`sun`, `template`, a malformed `state`, …). It always hides the card:
     * a hidden camera or alarm card must never appear because a condition was not understood.
     */
    data class Unknown(val condition: String) : VisibilityCondition()

    /** True only when the condition definitely holds: one Vitro can't evaluate never shows a card. */
    fun evaluate(
        lookup: (String) -> EntityState?,
        widthDp: Int = 1024,
        userId: String? = null,
        nowSeconds: Int = currentLocalSeconds(),
        weekday: Int = currentLocalWeekday(),
    ): Boolean = evaluate3(lookup, widthDp, userId, nowSeconds, weekday) == true

    /**
     * Three-valued: null when Vitro can't tell ([Unknown], a media query it can't read). Null stays null through `not`,
     * so negating a condition Vitro doesn't understand never turns it into permission to show a card; `and` with a
     * false branch is false, `or` with a true branch is true.
     */
    private fun evaluate3(lookup: (String) -> EntityState?, widthDp: Int, userId: String?, nowSeconds: Int, weekday: Int): Boolean? =
        when (this) {
            is State -> {
                // Like HA's frontend, a missing entity reads as "unavailable": `state_not: off` still shows it.
                val cur = lookup(entity)?.state ?: UNAVAILABLE
                (states == null || cur in states) && (statesNot == null || cur !in statesNot)
            }
            is NumericState -> {
                val v = lookup(entity)?.state?.toDoubleOrNull()
                if (v == null) {
                    false
                } else {
                    // An entity bound reads that entity's state; a bound that isn't a number (yet) is ignored, as in HA.
                    val lo = above ?: aboveEntity?.let { lookup(it)?.state?.toDoubleOrNull() }
                    val hi = below ?: belowEntity?.let { lookup(it)?.state?.toDoubleOrNull() }
                    (lo == null || v > lo) && (hi == null || v < hi)
                }
            }
            is Screen -> evaluateMediaQuery(mediaQuery, widthDp)
            is And -> and3(conditions.map { it.evaluate3(lookup, widthDp, userId, nowSeconds, weekday) })
            is Or -> {
                val r = conditions.map { it.evaluate3(lookup, widthDp, userId, nowSeconds, weekday) }
                if (r.any { it == true }) true else if (r.any { it == null }) null else false
            }
            // HA negates the conjunction of the children (`!checkConditionsMet(conditions)`).
            is Not -> and3(conditions.map { it.evaluate3(lookup, widthDp, userId, nowSeconds, weekday) })?.not()
            is User -> userId != null && userId in users
            is Time -> evaluateTime(nowSeconds, weekday)
            is Unknown -> null
        }

    private fun and3(results: List<Boolean?>): Boolean? =
        if (results.any { it == false }) false else if (results.any { it == null }) null else true

    private fun Time.evaluateTime(nowSeconds: Int, weekday: Int): Boolean {
        if (weekdays != null && weekday !in weekdays) return false
        return when {
            after != null && before != null ->
                if (after <= before) nowSeconds in after until before else nowSeconds >= after || nowSeconds < before
            after != null -> nowSeconds >= after
            before != null -> nowSeconds < before
            else -> true
        }
    }

    /** True when this condition (or one nested in it) depends on the clock, so the view must re-check it. */
    fun dependsOnTime(): Boolean = when (this) {
        is Time -> true
        is And -> conditions.any { it.dependsOnTime() }
        is Or -> conditions.any { it.dependsOnTime() }
        is Not -> conditions.any { it.dependsOnTime() }
        else -> false
    }

    /** True when this condition (or one nested in it) depends on who is signed in. */
    fun dependsOnUser(): Boolean = when (this) {
        is User -> true
        is And -> conditions.any { it.dependsOnUser() }
        is Or -> conditions.any { it.dependsOnUser() }
        is Not -> conditions.any { it.dependsOnUser() }
        else -> false
    }

    fun referencedEntities(out: MutableSet<String>) {
        when (this) {
            is State -> out += entity
            is NumericState -> {
                out += entity
                aboveEntity?.let { out += it }
                belowEntity?.let { out += it }
            }
            is Screen, is User, is Time, is Unknown -> Unit
            is And -> conditions.forEach { it.referencedEntities(out) }
            is Or -> conditions.forEach { it.referencedEntities(out) }
            is Not -> conditions.forEach { it.referencedEntities(out) }
        }
    }

    companion object {
        private const val UNAVAILABLE = "unavailable"
        /** One `(min-width: Npx)` / `(max-width: Npx)` feature: the only media features Vitro evaluates. */
        private val WIDTH_FEATURE = Regex("""^\(\s*(min|max)-width\s*:\s*(\d+(?:\.\d+)?)px\s*\)$""")
        private val MEDIA_AND = Regex("""\s+and\s+""")
        private val ENTITY_ID = Regex("""^\w+\.\w+$""")
        private val HHMM = Regex("""^\s*(\d{1,2}):(\d{2})(?::(\d{2}))?\s*$""")
        private val WEEKDAYS = mapOf(
            "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY,
            "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY,
        )

        fun currentLocalSeconds(): Int = Calendar.getInstance().let {
            it.get(Calendar.HOUR_OF_DAY) * 3_600 + it.get(Calendar.MINUTE) * 60 + it.get(Calendar.SECOND)
        }

        /**
         * Milliseconds from [nowMillis] (local time) until just after the next `after`/`before` bound of a `time`
         * condition in [conds] (nested ones too), or null when they have none: a check then sees the change on time.
         */
        fun msUntilNextTimeBound(conds: List<VisibilityCondition>, nowMillis: Long = System.currentTimeMillis()): Long? {
            val bounds = ArrayList<Int>()
            fun walk(c: VisibilityCondition) {
                when (c) {
                    is Time -> listOfNotNull(c.after, c.before).forEach { bounds += it }
                    is And -> c.conditions.forEach(::walk)
                    is Or -> c.conditions.forEach(::walk)
                    is Not -> c.conditions.forEach(::walk)
                    else -> Unit
                }
            }
            conds.forEach(::walk)
            if (bounds.isEmpty()) return null
            val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
            val nowSec = cal.get(Calendar.HOUR_OF_DAY) * 3_600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)
            val ms = cal.get(Calendar.MILLISECOND)
            return bounds.minOf { b ->
                val ahead = ((b - nowSec) % 86_400 + 86_400) % 86_400
                (if (ahead == 0) 86_400 else ahead) * 1_000L - ms + 250L
            }
        }

        fun currentLocalWeekday(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

        /**
         * A media query of `(min-width: Npx)` / `(max-width: Npx)` features joined with `and` (optionally after
         * `screen and` / `all and`), evaluated at [widthDp]. Anything else (orientation, lists, `not`, other features)
         * is null: Vitro can't tell, and the card stays hidden. An empty query is false, as in HA.
         */
        private fun evaluateMediaQuery(q: String, widthDp: Int): Boolean? {
            val query = q.trim().lowercase(Locale.ROOT)
            if (query.isEmpty()) return false
            val parts = query.split(MEDIA_AND).toMutableList()
            if (parts.first() == "screen" || parts.first() == "all") parts.removeAt(0)
            if (parts.isEmpty()) return null
            var ok = true
            for (part in parts) {
                val m = WIDTH_FEATURE.matchEntire(part.trim()) ?: return null
                val px = m.groupValues[2].toDouble()
                ok = ok && if (m.groupValues[1] == "min") widthDp >= px else widthDp <= px
            }
            return ok
        }

        /** `HH:MM` or `HH:MM:SS` as seconds from midnight, or null when it isn't a valid time of day. */
        fun parseTimeOfDay(raw: String?): Int? {
            val m = HHMM.find(raw ?: return null) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val sec = m.groupValues[3].ifEmpty { "0" }.toInt()
            if (h !in 0..23 || min !in 0..59 || sec !in 0..59) return null
            return h * 3_600 + min * 60 + sec
        }

        /** The condition's target: core's `entity_id` before Lovelace's `entity`, as HA's frontend reads it. */
        private fun conditionEntity(o: JSONObject): String =
            o.optString("entity_id", "").trim().ifEmpty { o.optString("entity", "").trim() }

        /** A numeric bound: a number (or numeric string), or else the entity id whose state is the bound. */
        private fun bound(o: JSONObject, key: String): Pair<Double?, String?> {
            if (!o.has(key) || o.isNull(key)) return null to null
            val v = o.opt(key)
            if (v is Number) return v.toDouble().takeIf { !it.isNaN() } to null
            val text = v.toString().trim()
            text.toDoubleOrNull()?.let { return it to null }
            return null to text.takeIf { ENTITY_ID.matches(it) }
        }

        /** Reads [key] as either one string or a JSON array of strings; null when absent or empty. */
        fun parseStringList(o: JSONObject, key: String): List<String>? {
            if (!o.has(key) || o.isNull(key)) return null
            val out = when (val v = o.opt(key)) {
                null -> emptyList()
                is JSONArray -> (0 until v.length()).mapNotNull { i -> v.opt(i)?.takeIf { it != JSONObject.NULL }?.toString() }
                else -> listOf(v.toString())
            }
            return out.ifEmpty { null }
        }

        fun parseList(arr: JSONArray?): List<VisibilityCondition> {
            if (arr == null) return emptyList()
            val out = ArrayList<VisibilityCondition>(arr.length())
            for (i in 0 until arr.length()) {
                // A non-object entry is a condition we can't read: fail closed rather than drop it.
                out += arr.optJSONObject(i)?.let { parse(it) } ?: Unknown(arr.opt(i)?.toString().orEmpty())
            }
            return out
        }

        /** Parses one condition. Anything Vitro can't evaluate becomes [Unknown], which hides the card. */
        fun parse(o: JSONObject): VisibilityCondition {
            val condName = o.optString("condition", "state")
            return when (condName) {
                "state" -> {
                    val entity = conditionEntity(o).ifEmpty { return Unknown(condName) }
                    val st = parseStringList(o, "state")
                    val not = parseStringList(o, "state_not")
                    if (st == null && not == null) return Unknown(condName)
                    State(entity, st, not)
                }
                "numeric_state" -> {
                    val entity = conditionEntity(o).ifEmpty { return Unknown(condName) }
                    val (above, aboveEntity) = bound(o, "above")
                    val (below, belowEntity) = bound(o, "below")
                    NumericState(entity, above, below, aboveEntity, belowEntity)
                }
                "screen" -> Screen(o.optString("media_query", ""))
                "user" -> {
                    val users = parseStringList(o, "users") ?: return Unknown(condName)
                    User(users)
                }
                "time" -> {
                    // `after`/`before` may also name an input_datetime; Vitro can't read those yet, so they fail closed.
                    val after = o.optString("after", "").ifBlank { null }?.let { parseTimeOfDay(it) ?: return Unknown(condName) }
                    val before = o.optString("before", "").ifBlank { null }?.let { parseTimeOfDay(it) ?: return Unknown(condName) }
                    val weekdays = parseStringList(o, "weekdays")?.let { days ->
                        days.map { WEEKDAYS[it.trim().lowercase(Locale.ROOT)] ?: return Unknown(condName) }.toSet()
                    }
                    Time(after, before, weekdays)
                }
                "and" -> And(parseList(o.optJSONArray("conditions")))
                "or" -> Or(parseList(o.optJSONArray("conditions")))
                "not" -> Not(parseList(o.optJSONArray("conditions")))
                else -> Unknown(condName)
            }
        }
    }
}

data class CardConfig(
    val type: String,
    val raw: JSONObject,
    val theme: String? = raw.optString("theme", "").ifEmpty { null },
    val visibility: List<VisibilityCondition> = VisibilityCondition.parseList(raw.optJSONArray("visibility")),
    val childCards: List<CardConfig> = emptyList(),
) {
    val entity: String? get() = raw.optString("entity", "").ifEmpty { null }
    val title: String? get() = raw.optString("title", "").ifEmpty { null }
    val name: String? get() = raw.optString("name", "").ifEmpty { null }
    val icon: String? get() = raw.optString("icon", "").ifEmpty { null }

    fun entities(): List<EntityRowConfig> {
        val arr = raw.optJSONArray("entities") ?: return emptyList()
        val out = ArrayList<EntityRowConfig>(arr.length())
        for (i in 0 until arr.length()) {
            when (val item = arr.opt(i)) {
                is String -> if (item.isNotBlank()) out += EntityRowConfig(item)
                is JSONObject -> {
                    when (item.optString("type", "")) {
                        "divider" -> { out += EntityRowConfig(entity = "", rowType = "divider"); continue }
                        "section" -> {
                            out += EntityRowConfig(entity = "", name = item.optString("label", "").ifEmpty { null }, rowType = "section")
                            continue
                        }
                    }
                    val id = item.optString("entity", "").ifEmpty { continue }
                    out += EntityRowConfig(
                        entity = id,
                        name = item.optString("name", "").ifEmpty { null },
                        icon = item.optString("icon", "").ifEmpty { null },
                        secondaryInfo = item.optString("secondary_info", "").ifEmpty { null },
                        tapAction = item.optJSONObject("tap_action")?.let { ActionConfig.parse(it) },
                        holdAction = item.optJSONObject("hold_action")?.let { ActionConfig.parse(it) },
                    )
                }
            }
        }
        return out
    }

    fun gridColumns(): Int = raw.optInt("columns", 3).coerceIn(1, 6)
    fun gridOptionsColumns(): Int? = raw.optJSONObject("grid_options")?.let { g ->
        if (g.has("columns") && !g.isNull("columns")) g.optInt("columns", 0).takeIf { it > 0 } else null
    }
}

/** Classifies cards into Native Core, Native HACS Fast-Paths, or Micro-Web Islands. */
object CardRouter {
    val NATIVE_CORE_TYPES = setOf(
        "tile",
        "entities",
        "glance",
        "button",
        "heading",
        "clock",
        "sensor",
        "history-graph",
        "gauge",
        "thermostat",
        "weather-forecast",
        "markdown",
        "picture",
        "picture-entity",
        "picture-glance",
        "media-control",
        "grid",
        "horizontal-stack",
        "vertical-stack",
        "conditional",
        "entity-filter",
        "entity",
        "light",
        "alarm-panel",
        "todo-list",
        "statistics-graph",
        "area",
        "picture-elements",
        "humidifier",
        "calendar",
        "logbook",
    )

    /** Keys besides `entity` / `entities` where core cards name an entity id. */
    private val EXTRA_ENTITY_KEYS = listOf("temperature_entity", "humidity_entity", "camera_image")

    val NATIVE_HACS_TYPES = setOf(
        "custom:mushroom-entity-card",
        "custom:mushroom-light-card",
        "custom:mushroom-title-card",
        "custom:mushroom-chips-card",
        "custom:mushroom-template-card",
        "custom:mushroom-climate-card",
        "custom:mushroom-fan-card",
        "custom:mushroom-cover-card",
        "custom:mushroom-person-card",
        "custom:bubble-card",
        "custom:mini-graph-card",
        "custom:clock-weather-card",
        "custom:modern-circular-gauge",
        "custom:bar-card",
        "custom:mushroom-media-player-card",
        "custom:mushroom-lock-card",
        "custom:mushroom-alarm-control-panel-card",
        "custom:mushroom-vacuum-card",
        "custom:mushroom-humidifier-card",
        "custom:mushroom-number-card",
        "custom:mushroom-select-card",
        "custom:mini-media-player",
        "custom:auto-entities",
        "custom:stack-in-card",
        "custom:vertical-stack-in-card",
        "custom:layout-card",
    )

    /**
     * Cards that only lay out the cards in their `cards:` list. [tierCounts] counts what is inside them, not the
     * container, so a Tier 3 card inside a `stack-in-card` still shows up as an island.
     */
    val CONTAINER_TYPES = setOf(
        "grid", "horizontal-stack", "vertical-stack", "conditional",
        "custom:stack-in-card", "custom:vertical-stack-in-card", "custom:layout-card",
    )

    /** Cards that pick their entities at runtime from the whole state machine (`auto-entities` filters). */
    private val FOLLOWS_ALL_TYPES = setOf("custom:auto-entities")

    fun tier(card: CardConfig): CardTier = when {
        card.type in NATIVE_CORE_TYPES -> CardTier.NATIVE_CORE
        card.type in NATIVE_HACS_TYPES -> CardTier.NATIVE_HACS
        else -> CardTier.WEB_ISLAND
    }

    /** Counts how many cards in [view] fall into each [CardTier] (flattening layout containers). */
    fun tierCounts(view: ViewConfig): Map<CardTier, Int> {
        val counts = linkedMapOf(
            CardTier.NATIVE_CORE to 0,
            CardTier.NATIVE_HACS to 0,
            CardTier.WEB_ISLAND to 0,
        )
        fun walk(c: CardConfig) {
            if (c.childCards.isNotEmpty() && c.type in CONTAINER_TYPES) {
                c.childCards.forEach(::walk)
            } else {
                val t = tier(c)
                counts[t] = (counts[t] ?: 0) + 1
            }
        }
        view.sections.forEach { s -> s.cards.forEach(::walk) }
        view.cards.forEach(::walk)
        return counts
    }

    /**
     * True when a card in [view] chooses its entities at runtime (`custom:auto-entities`), so the view must follow
     * every entity rather than only the ones [referencedEntities] names.
     */
    fun followsAllEntities(view: ViewConfig): Boolean {
        fun walk(c: CardConfig): Boolean = c.type in FOLLOWS_ALL_TYPES || c.childCards.any(::walk) ||
            (c.type.startsWith("custom:") && c.raw.optJSONObject("card")?.optString("type") in FOLLOWS_ALL_TYPES)
        return view.sections.any { s -> s.cards.any(::walk) } || view.cards.any(::walk)
    }

    private val ENTITY_ID_PATTERN = Regex("""^[a-z][a-z0-9_]*\.[a-z0-9_]+$""")

    private fun scanElements(arr: JSONArray?, out: MutableSet<String>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val el = arr.optJSONObject(i) ?: continue
            el.optString("entity", "").takeIf { ENTITY_ID_PATTERN.matches(it) }?.let { out += it }
            VisibilityCondition.parseList(el.optJSONArray("conditions")).forEach { it.referencedEntities(out) }
            scanElements(el.optJSONArray("elements"), out)
        }
    }

    /** Every `entity_id` referenced anywhere in [view], for view-scoped `subscribe_entities`. */
    fun referencedEntities(view: ViewConfig): Set<String> {
        val out = LinkedHashSet<String>()
        fun scanJson(v: Any?) {
            when (v) {
                is String -> if (ENTITY_ID_PATTERN.matches(v) && !v.startsWith("http") && !v.endsWith(".js") && !v.endsWith(".png") && !v.endsWith(".jpg") && !v.endsWith(".svg")) {
                    out += v
                }
                is JSONArray -> for (i in 0 until v.length()) scanJson(v.opt(i))
                is JSONObject -> {
                    val keys = v.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k == "type" || k == "icon" || k == "navigation_path" || k == "url_path" || k == "image") continue
                        scanJson(v.opt(k))
                    }
                }
            }
        }
        fun walkCard(c: CardConfig) {
            c.entity?.let { out += it }
            c.entities().forEach { if (it.entity.isNotEmpty()) out += it.entity }
            c.visibility.forEach { it.referencedEntities(out) }
            VisibilityCondition.parseList(c.raw.optJSONArray("conditions")).forEach { it.referencedEntities(out) }
            // Deep-scan custom cards (e.g. room arrays, sleep/outside/camera objects) so view-scoped
            // subscribe_entities automatically captures every entity without knowing each card's schema.
            if (c.type.startsWith("custom:")) scanJson(c.raw)
            for (key in EXTRA_ENTITY_KEYS) {
                c.raw.optString(key, "").takeIf { ENTITY_ID_PATTERN.matches(it) }?.let { out += it }
            }
            // Heading cards carry entity `badges` (objects or bare ids) on their right side.
            c.raw.optJSONArray("badges")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val id = arr.optJSONObject(i)?.optString("entity", "") ?: arr.optString(i, "")
                    if (ENTITY_ID_PATTERN.matches(id)) out += id
                }
            }
            // picture-elements: every overlay's entity, including those nested in `conditional` elements.
            scanElements(c.raw.optJSONArray("elements"), out)
            c.childCards.forEach(::walkCard)
        }
        view.sections.forEach { s ->
            s.visibility.forEach { it.referencedEntities(out) }
            s.cards.forEach(::walkCard)
        }
        view.cards.forEach(::walkCard)
        view.badges.forEach(::walkCard)
        return out
    }
}

/** Parses Home Assistant's `lovelace/config` JSON payload into [DashboardConfig]. */
object LovelaceParser {
    fun parseDashboard(raw: JSONObject): DashboardConfig {
        val title = raw.optString("title", "").ifEmpty { null }
        val viewsArr = raw.optJSONArray("views") ?: JSONArray()
        val views = ArrayList<ViewConfig>(viewsArr.length())
        for (i in 0 until viewsArr.length()) {
            val v = viewsArr.optJSONObject(i) ?: continue
            views += parseView(i, v)
        }
        return DashboardConfig(title, views)
    }

    fun parseView(index: Int, raw: JSONObject): ViewConfig {
        val sectionsArr = raw.optJSONArray("sections")
        val sections = if (sectionsArr != null) {
            (0 until sectionsArr.length()).mapNotNull { sectionsArr.optJSONObject(it)?.let(::parseSection) }
        } else {
            emptyList()
        }
        val cards = parseCards(raw.optJSONArray("cards"))
        val badges = parseBadges(raw.optJSONArray("badges"))
        val explicitType = raw.optString("type", "").ifEmpty { null }
        val inferredType = when {
            explicitType != null -> explicitType
            raw.optBoolean("panel", false) -> "panel"
            sections.isNotEmpty() -> "sections"
            else -> "masonry"
        }
        return ViewConfig(
            index = index,
            title = raw.optString("title", "").ifEmpty { "View ${index + 1}" },
            path = raw.optString("path", "").ifEmpty { null },
            icon = raw.optString("icon", "").ifEmpty { null },
            type = inferredType,
            theme = raw.optString("theme", "").ifEmpty { null },
            background = raw.optString("background", "").ifEmpty { null },
            maxColumns = raw.optInt("max_columns", 4).coerceIn(1, 6),
            sections = sections,
            cards = cards,
            badges = badges,
            subview = raw.optBoolean("subview", false),
            backPath = raw.optString("back_path", "").ifEmpty { null },
        )
    }

    private fun parseSection(raw: JSONObject): SectionConfig = SectionConfig(
        type = raw.optString("type", "grid"),
        title = raw.optString("title", "").ifEmpty { null },
        columnSpan = raw.optInt("column_span", 1).coerceIn(1, 4),
        cards = parseCards(raw.optJSONArray("cards")),
        visibility = VisibilityCondition.parseList(raw.optJSONArray("visibility")),
    )

    fun parseCards(arr: JSONArray?): List<CardConfig> {
        if (arr == null) return emptyList()
        val out = ArrayList<CardConfig>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let(::parseCard)?.let { out += it }
        }
        return out
    }

    fun parseCard(raw: JSONObject): CardConfig {
        val type = raw.optString("type", "entities").ifEmpty { "entities" }
        val children = when (type) {
            "grid", "horizontal-stack", "vertical-stack",
            "custom:stack-in-card", "custom:vertical-stack-in-card", "custom:layout-card" -> parseCards(raw.optJSONArray("cards"))
            "conditional" -> listOfNotNull(raw.optJSONObject("card")?.let(::parseCard))
            else -> emptyList()
        }
        return CardConfig(type = type, raw = raw, childCards = children)
    }

    private fun parseBadges(arr: JSONArray?): List<CardConfig> {
        if (arr == null) return emptyList()
        val out = ArrayList<CardConfig>(arr.length())
        for (i in 0 until arr.length()) {
            when (val item = arr.opt(i)) {
                is String -> out += CardConfig("entity", JSONObject().put("type", "entity").put("entity", item))
                is JSONObject -> out += parseCard(item)
            }
        }
        return out
    }

    /** Extracts `(dashboardUrlPath, viewPath)` from a full HA URL like `http://ha:8123/clarity-ref/typical-nocam`. */
    fun parseRoute(url: String): Pair<String?, String?> {
        val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty().trim('/')
        if (path.isEmpty()) return null to null
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null to null
        val dash = when (parts[0]) {
            "lovelace", "home" -> null // default storage dashboard
            else -> parts[0]
        }
        val view = parts.getOrNull(1)
        return dash to view
    }

    /** True when [url] is a Home Assistant Lovelace dashboard path rather than login, settings, or blueprints. */
    fun isDashboardUrl(url: String): Boolean {
        val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty().trim('/')
        if (path.isEmpty()) return true
        val first = path.substringBefore('/')
        return first !in NON_DASHBOARD_PREFIXES
    }

    private val NON_DASHBOARD_PREFIXES = setOf(
        "auth", "config", "developer-tools", "profile", "hassio", "hacs",
        "logbook", "history", "energy", "map", "media-browser", "calendar",
        "todo", "api", "static", "frontend_latest", "local", "hacsfiles",
    )
}
