/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.model

import dev.glassworks.engine.vitro.compat.CardCompat
import dev.glassworks.engine.vitro.compat.CardRoute
import dev.glassworks.engine.vitro.compat.CardVerdict
import dev.glassworks.engine.vitro.compat.IslandScripts
import dev.glassworks.engine.vitro.compat.VitroSupport
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
) {
    /**
     * The view a path opens, as HA's router picks it: none (or blank) is the first view, then a view's own `path`,
     * then an index; anything else the first view. The renderer and the compat warm-up both read it.
     */
    fun viewIndex(path: String?): Int {
        if (views.isEmpty() || path.isNullOrBlank()) return 0
        views.indexOfFirst { it.path == path }.takeIf { it >= 0 }?.let { return it }
        path.toIntOrNull()?.takeIf { it in views.indices }?.let { return it }
        return 0
    }
}

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
     * The single-value constructor keeps older call sites working. With [attribute] the condition compares that
     * attribute instead of the state. A value that names an entity also matches that entity's state, as in HA.
     */
    data class State(
        val entity: String,
        val states: List<String>? = null,
        val statesNot: List<String>? = null,
        val attribute: String? = null,
    ) : VisibilityCondition() {
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
        /** Compare this attribute instead of the state. */
        val attribute: String? = null,
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

    /**
     * True only when the condition definitely holds: one Vitro can't evaluate never shows a card. [nowSeconds] and
     * [weekday] default to one sample of the clock, so a check across midnight never mixes two days.
     */
    fun evaluate(
        lookup: (String) -> EntityState?,
        widthDp: Int = 1024,
        userId: String? = null,
        nowSeconds: Int? = null,
        weekday: Int? = null,
    ): Boolean {
        val now = if (nowSeconds == null || weekday == null) Calendar.getInstance() else null
        return evaluate3(
            lookup, widthDp, userId,
            nowSeconds ?: secondsOf(now!!),
            weekday ?: now!!.get(Calendar.DAY_OF_WEEK),
        ) == true
    }

    /**
     * Three-valued: null when Vitro can't tell ([Unknown], a media query it can't read). Null stays null through `not`,
     * so negating a condition Vitro doesn't understand never turns it into permission to show a card; `and` with a
     * false branch is false, `or` with a true branch is true.
     */
    private fun evaluate3(lookup: (String) -> EntityState?, widthDp: Int, userId: String?, nowSeconds: Int, weekday: Int): Boolean? =
        when (this) {
            is State -> {
                // Like HA's frontend, a missing entity (or attribute) reads as "unknown": `state_not: off` still shows it.
                val obj = lookup(entity)
                val cur = when {
                    obj == null -> UNKNOWN
                    attribute != null -> obj.attributes[attribute]?.let(::jsString) ?: UNKNOWN
                    else -> obj.state
                }
                // A value naming an entity matches that entity's state too (`state: input_select.mode`).
                fun expand(values: List<String>) = values + values.mapNotNull { v ->
                    if (ENTITY_ID.matches(v)) lookup(v)?.state else null
                }
                (states == null || cur in expand(states)) && (statesNot == null || cur !in expand(statesNot))
            }
            is NumericState -> {
                val obj = lookup(entity)
                // HA's `Number(state)`: true is 1, null and "" are 0, a missing entity or attribute isn't a number.
                val v = when {
                    obj == null -> null
                    attribute != null -> if (obj.attributes.containsKey(attribute)) jsToNumber(obj.attributes[attribute]) else null
                    else -> jsToNumber(obj.state)
                }
                if (v == null) {
                    false
                } else {
                    // An entity bound reads that entity's state; a bound that isn't a number (yet) is ignored, as in HA.
                    val lo = above ?: aboveEntity?.let { lookup(it)?.state?.let(::jsToNumber) }
                    val hi = below ?: belowEntity?.let { lookup(it)?.state?.let(::jsToNumber) }
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
            is State -> {
                out += entity
                // Entity-valued operands: a change to the named entity changes the result.
                for (v in states.orEmpty() + statesNot.orEmpty()) if (ENTITY_ID.matches(v)) out += v
            }
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
        private const val UNKNOWN = "unknown"

        /**
         * An attribute value as JavaScript's `String()` writes it, as HA compares it: `150`, not `150.0`; a list as
         * its elements joined by commas (`off,heat`, a null element empty); an object as `[object Object]`.
         */
        private fun jsString(v: Any?): String = when (v) {
            null -> ""
            is Double -> jsNumber(v)
            is Float -> jsNumber(v.toDouble())
            is List<*> -> v.joinToString(",") { jsString(it) }
            is Map<*, *> -> "[object Object]"
            else -> v.toString()
        }

        /** JavaScript's `Number(v)` for a JSON value, or null for NaN: what HA's numeric_state condition compares. */
        internal fun jsToNumber(v: Any?): Double? = when (v) {
            null -> 0.0
            is Boolean -> if (v) 1.0 else 0.0
            is Number -> v.toDouble().takeIf { !it.isNaN() }
            is List<*> -> when (v.size) {
                0 -> 0.0
                1 -> jsToNumber(jsString(v[0]))
                else -> null
            }
            is Map<*, *> -> null
            else -> {
                val t = v.toString().trim()
                when {
                    t.isEmpty() -> 0.0
                    t == "Infinity" || t == "+Infinity" -> Double.POSITIVE_INFINITY
                    t == "-Infinity" -> Double.NEGATIVE_INFINITY
                    t.startsWith("0x", ignoreCase = true) -> t.drop(2).toLongOrNull(16)?.toDouble()
                    // Only JavaScript's decimal syntax: Kotlin also reads "NaN", "1f" and "1d".
                    JS_DECIMAL.matches(t) -> t.toDoubleOrNull()
                    else -> null
                }
            }
        }

        private val JS_DECIMAL = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

        /**
         * A number as JavaScript's `Number.prototype.toString` writes it: `150`, `0.0001`, `1e-7`, `1e+21` (Java writes
         * `150.0`, `1.0E-4`). The digits are Java's shortest round-trip ones; only the layout follows JavaScript.
         */
        internal fun jsNumber(d: Double): String {
            if (d.isNaN()) return "NaN"
            if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
            if (d == 0.0) return "0"
            val bd = java.math.BigDecimal(d.toString()).stripTrailingZeros()
            val digits = bd.unscaledValue().abs().toString()
            val k = digits.length
            val n = k - bd.scale() // value = 0.digits x 10^n
            val sign = if (d < 0) "-" else ""
            val body = when {
                n in k..21 -> digits + "0".repeat(n - k)
                n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
                n in -5..0 -> "0." + "0".repeat(-n) + digits
                else -> {
                    val e = n - 1
                    (if (k == 1) digits else digits[0] + "." + digits.substring(1)) + "e" + (if (e >= 0) "+" else "-") + kotlin.math.abs(e)
                }
            }
            return sign + body
        }

        /**
         * [evaluate] at the instant [nowMs] (a card context's clock): its time of day and weekday from one sample, so
         * a parity replay reads the captured instant and the live renderer the wall clock.
         */
        fun evaluateAt(
            condition: VisibilityCondition,
            nowMs: Long,
            lookup: (String) -> EntityState?,
            widthDp: Int,
            userId: String?,
        ): Boolean {
            val c = Calendar.getInstance().apply { timeInMillis = nowMs }
            return condition.evaluate(lookup, widthDp, userId, secondsOf(c), c.get(Calendar.DAY_OF_WEEK))
        }

        private fun secondsOf(c: Calendar): Int =
            c.get(Calendar.HOUR_OF_DAY) * 3_600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND)
        /** One `(min-width: Npx)` / `(max-width: Npx)` feature: the only media features Vitro evaluates. */
        private val WIDTH_FEATURE = Regex("""^\(\s*(min|max)-width\s*:\s*(\d+(?:\.\d+)?)px\s*\)$""")
        private val MEDIA_AND = Regex("""\s+and\s+""")
        private val ENTITY_ID = Regex("""^\w+\.\w+$""")
        private val HHMM = Regex("""^\s*(\d{1,2}):(\d{2})(?::(\d{2}))?\s*$""")
        private val WEEKDAYS = mapOf(
            "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY,
            "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY,
        )

        fun currentLocalSeconds(): Int = secondsOf(Calendar.getInstance())

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

        /**
         * A group's `conditions`: a list, or one condition object (HA takes either). Absent is an empty group; any
         * other value is unreadable (null), never an empty group that shows the card.
         */
        private fun parseGroup(o: JSONObject): List<VisibilityCondition>? = when (val c = o.opt("conditions")) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> parseList(c)
            is JSONObject -> listOf(parse(c))
            else -> null
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
                    State(entity, st, not, o.optString("attribute", "").trim().ifEmpty { null })
                }
                "numeric_state" -> {
                    val entity = conditionEntity(o).ifEmpty { return Unknown(condName) }
                    val (above, aboveEntity) = bound(o, "above")
                    val (below, belowEntity) = bound(o, "below")
                    NumericState(entity, above, below, aboveEntity, belowEntity, o.optString("attribute", "").trim().ifEmpty { null })
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
                "and" -> And(parseGroup(o) ?: return Unknown(condName))
                "or" -> Or(parseGroup(o) ?: return Unknown(condName))
                "not" -> Not(parseGroup(o) ?: return Unknown(condName))
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

    private val compatHolder = lazy { CardCompat.check(this) }

    /**
     * Whether Vitro draws this card natively or sends it to a web island, and why ([CardCompat]). Decided once,
     * the first time it's asked after the dashboard is parsed, and never per frame.
     */
    val compat: CardVerdict by compatHolder

    /** Whether [compat] has been computed, without computing it (the warm-up's test reads it). */
    internal val compatComputed: Boolean get() = compatHolder.isInitialized()

    /**
     * Whether this card goes to an island ([CardVerdict.inIsland]). A card [CardCompat.staysNative] says can only be
     * native is answered without the full check, which then runs only when a report asks for its notes.
     */
    fun inIsland(scripts: IslandScripts = IslandScripts.ANY): Boolean =
        !nativeOnly && compat.inIsland(scripts)

    /** [CardCompat.staysNative] for this card, decided once like [compat] (it walks the cards it produces). */
    internal val nativeOnly: Boolean by lazy { CardCompat.staysNative(this) }

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
    /** Built-in cards with a native renderer; defined in [VitroSupport.CORE_CARD_TYPES]. */
    val NATIVE_CORE_TYPES: Set<String> get() = VitroSupport.CORE_CARD_TYPES

    /** Keys besides `entity` / `entities` where core cards name an entity id. */
    private val EXTRA_ENTITY_KEYS = listOf("temperature_entity", "humidity_entity", "camera_image")

    /** HACS cards with a native fast path; defined in [VitroSupport.HACS_CARD_TYPES]. */
    val NATIVE_HACS_TYPES: Set<String> get() = VitroSupport.HACS_CARD_TYPES

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

    /**
     * The card's tier. A card of a native type still goes to an island when something in its config is outside
     * [VitroSupport] ([CardConfig.compat]).
     */
    fun tier(card: CardConfig, scripts: IslandScripts = IslandScripts.ANY): CardTier = when {
        card.inIsland(scripts) -> CardTier.WEB_ISLAND
        card.type in NATIVE_CORE_TYPES -> CardTier.NATIVE_CORE
        card.type in NATIVE_HACS_TYPES -> CardTier.NATIVE_HACS
        else -> CardTier.WEB_ISLAND
    }

    /** Counts how many cards in [view] fall into each [CardTier] (flattening layout containers). */
    fun tierCounts(view: ViewConfig, scripts: IslandScripts = IslandScripts.ANY): Map<CardTier, Int> {
        val counts = linkedMapOf(
            CardTier.NATIVE_CORE to 0,
            CardTier.NATIVE_HACS to 0,
            CardTier.WEB_ISLAND to 0,
        )
        fun walk(c: CardConfig) {
            if (c.childCards.isNotEmpty() && c.type in CONTAINER_TYPES && !c.inIsland(scripts)) {
                builtChildren(c).forEach { walk(it.value) }
            } else {
                val t = tier(c, scripts)
                counts[t] = (counts[t] ?: 0) + 1
            }
        }
        viewRoots(view).forEach { walk(it.card) }
        return counts
    }

    /**
     * The children a container builds, with their index in its `cards:`. A vertical layout-card consumes its
     * `custom:layout-break` markers instead of building them ([dev.glassworks.engine.vitro.cards.LayoutCardView]).
     */
    fun builtChildren(card: CardConfig): List<IndexedValue<CardConfig>> {
        val all = card.childCards.withIndex().toList()
        if (card.type != "custom:layout-card") return all
        val layout = card.raw.optString("layout_type", "masonry-layout").removePrefix("custom:")
        return if (layout == "vertical-layout" || layout == "vertical") all.filter { it.value.type != LAYOUT_BREAK } else all
    }

    const val LAYOUT_BREAK = "custom:layout-break"

    /**
     * Every card [card]'s config produces, one level down, each with an index for report paths: a container's or a
     * conditional's cards ([builtChildren]), and auto-entities' `card:` (an `entities` card by default) as
     * `AutoEntitiesCardView` builds it, with its `card_param` field (`entities` by default) replaced. Under
     * `card_param: cards`, that field holds one card per top-level `entities:` entry and include rule's `options` (a
     * `tile` by default), so the inner card's own verdict sees them. With [perEntity] those generated cards are listed
     * here too, for routing. The report leaves [perEntity] off and doesn't walk into the inner card's generated
     * cards ([generatesCards]), because which of them exist depends on live states.
     */
    fun producedCards(card: CardConfig, perEntity: Boolean = true): List<IndexedValue<CardConfig>> {
        val out = builtChildren(card).toMutableList()
        if (card.type != "custom:auto-entities") return out
        val inner = card.raw.optJSONObject("card")?.let { JSONObject(it.toString()) } ?: JSONObject()
        if (!inner.has("type")) inner.put("type", "entities")
        val param = card.raw.optString("card_param", "entities").ifEmpty { "entities" }
        // auto-entities replaces this field with what it matched, so what the config wrote there never shows.
        inner.remove(param)
        val generated = if (param == "cards") generatedCards(card) else emptyList()
        if (param == "cards") inner.put("cards", JSONArray().apply { generated.forEach { put(JSONObject(it.toString())) } })
        out += IndexedValue(out.size, LovelaceParser.parseCard(inner))
        if (perEntity) generated.forEach { out += IndexedValue(out.size, LovelaceParser.parseCard(it)) }
        return out
    }

    /** True when [card] is auto-entities building one card per entity: its inner card's cards are all generated. */
    fun generatesCards(card: CardConfig): Boolean =
        card.type == "custom:auto-entities" && card.raw.optString("card_param", "entities") == "cards"

    /** The cards auto-entities builds per entity, one per `entities:` entry and include rule (no entity filled in). */
    private fun generatedCards(card: CardConfig): List<JSONObject> {
        val templates = ArrayList<JSONObject>()
        card.raw.optJSONArray("entities")?.let { arr ->
            for (i in 0 until arr.length()) {
                when (val e = arr.opt(i)) {
                    is String -> templates += JSONObject()
                    is JSONObject -> templates += JSONObject(e.toString()).apply { remove("entity") }
                }
            }
        }
        card.raw.optJSONObject("filter")?.optJSONArray("include")?.let { rules ->
            for (i in 0 until rules.length()) templates += rules.optJSONObject(i)?.optJSONObject("options")?.let { JSONObject(it.toString()) } ?: JSONObject()
        }
        templates.forEach { if (!it.has("type")) it.put("type", "tile") }
        return templates
    }

    /** One top-level card `VitroView` builds for a view, with its section (null outside sections) and index. */
    data class ViewRoot(val section: Int?, val index: Int, val card: CardConfig)

    /**
     * The top-level cards `VitroView` builds for [view], the same choice its `buildViewTree` makes: a panel view's
     * first card, a sections view's section cards, or else the view's own cards (masonry).
     */
    fun viewRoots(view: ViewConfig): List<ViewRoot> = when {
        view.type == "panel" && view.cards.isNotEmpty() -> listOf(ViewRoot(null, 0, view.cards.first()))
        view.type == "sections" && view.sections.isNotEmpty() ->
            view.sections.flatMapIndexed { s, section -> section.cards.mapIndexed { i, c -> ViewRoot(s, i, c) } }
        else -> view.cards.mapIndexed { i, c -> ViewRoot(null, i, c) }
    }

    /**
     * True when a card in [view] chooses its entities at runtime (`custom:auto-entities`), so the view must follow
     * every entity rather than only the ones [referencedEntities] names.
     */
    fun followsAllEntities(view: ViewConfig): Boolean {
        // An area card's sensors, alerts and camera are its area's entities, known only from HA's registries.
        fun walk(c: CardConfig): Boolean = c.type in FOLLOWS_ALL_TYPES || c.childCards.any(::walk) ||
            (c.type == "area" && AreaCardConfig.readsAreaEntities(c.raw)) ||
            (c.type.startsWith("custom:") && c.raw.optJSONObject("card")?.optString("type") in FOLLOWS_ALL_TYPES)
        return view.sections.any { s -> s.cards.any(::walk) } || view.cards.any(::walk)
    }

    /**
     * `^[a-z][a-z0-9_]*\.[a-z0-9_]+$`, checked by hand: the deep scan below tests every string of a custom card's
     * config (long templates included), and a pattern match per string cost a slow panel about 0.1 s per view.
     */
    internal fun isEntityId(v: String): Boolean {
        if (v.isEmpty() || v[0] !in 'a'..'z') return false
        val dot = v.indexOf('.')
        if (dot < 1 || dot == v.length - 1) return false
        for (i in v.indices) {
            if (i == dot) continue
            val c = v[i]
            if (c !in 'a'..'z' && c !in '0'..'9' && c != '_') return false
        }
        return true
    }

    private fun scanElements(arr: JSONArray?, out: MutableSet<String>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val el = arr.optJSONObject(i) ?: continue
            el.optString("entity", "").takeIf { isEntityId(it) }?.let { out += it }
            VisibilityCondition.parseList(el.optJSONArray("conditions")).forEach { it.referencedEntities(out) }
            scanElements(el.optJSONArray("elements"), out)
        }
    }

    /** Every `entity_id` referenced anywhere in [view], for view-scoped `subscribe_entities`. */
    fun referencedEntities(view: ViewConfig): Set<String> {
        val out = LinkedHashSet<String>()
        fun scanJson(v: Any?) {
            when (v) {
                is String -> if (isEntityId(v) && !v.startsWith("http") && !v.endsWith(".js") && !v.endsWith(".png") && !v.endsWith(".jpg") && !v.endsWith(".svg")) {
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
                c.raw.optString(key, "").takeIf { isEntityId(it) }?.let { out += it }
            }
            // Heading cards carry entity `badges` (objects or bare ids) on their right side.
            c.raw.optJSONArray("badges")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val id = arr.optJSONObject(i)?.optString("entity", "") ?: arr.optString(i, "")
                    if (isEntityId(id)) out += id
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

    /** Card types whose children are their `cards:` list (a conditional's is its `card:`). */
    val CARDS_FIELD_TYPES: Set<String> = setOf(
        "grid", "horizontal-stack", "vertical-stack", "custom:stack-in-card", "custom:vertical-stack-in-card", "custom:layout-card",
    )

    fun parseCard(raw: JSONObject): CardConfig {
        val type = raw.optString("type", "entities").ifEmpty { "entities" }
        val children = when (type) {
            in CARDS_FIELD_TYPES -> parseCards(raw.optJSONArray("cards"))
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

/** What HA's area card config means, for the card and for the entity subscription. */
object AreaCardConfig {
    /** `display_type`, else `camera` for the old `show_camera`, else `picture`. */
    fun displayType(raw: JSONObject): String =
        raw.optString("display_type", "").ifEmpty { if (raw.optBoolean("show_camera", false)) "camera" else "picture" }

    /** True when the card reads its area's entities (sensors, alerts, its camera), not only HA's registries. */
    fun readsAreaEntities(raw: JSONObject): Boolean =
        (raw.optJSONArray("sensor_classes")?.length() ?: 0) > 0 || (raw.optJSONArray("alert_classes")?.length() ?: 0) > 0 ||
            displayType(raw) == "camera"
}

/** HA's `hui-entity-filter-card`: the card it builds around the entities that pass the filter. */
object EntityFilterConfig {
    /**
     * `{type: "entities", ...config.card, entities}`: the inner `card` (an entities card unless it says otherwise),
     * with the filter's entities, and its `state_filter` for Vitro's entities card to hide rows as states change.
     */
    fun innerCard(outer: CardConfig): CardConfig {
        val inner = outer.raw.optJSONObject("card")?.let { JSONObject(it.toString()) } ?: JSONObject()
        if (!inner.has("type")) inner.put("type", "entities")
        outer.raw.optJSONArray("entities")?.let { inner.put("entities", JSONArray(it.toString())) }
        outer.raw.optJSONArray("state_filter")?.let { inner.put("state_filter", JSONArray(it.toString())) }
        return LovelaceParser.parseCard(inner)
    }
}

/**
 * A card's size in HA's `getCardSize` units (about 50 px each), as layout-card's masonry columns weigh them: the
 * core cards' own rules, else 1 (layout-card's answer for a card that has none or doesn't answer in time).
 */
object HaCardSize {
    fun of(card: CardConfig): Int {
        val raw = card.raw
        return when (card.type) {
            "entities" -> (if (card.title != null) 2 else 0) + card.entities().size.coerceAtLeast(1)
            "tile" -> {
                val features = raw.optJSONArray("features")?.length() ?: 0
                val inline = features > 0 && raw.optString("features_position") == "inline"
                1 + (if (raw.optBoolean("vertical", false)) 1 else 0) + (if (inline) 0 else features)
            }
            "glance" -> {
                val lines = listOf("show_icon", "show_name", "show_state").count { raw.optBoolean(it, true) }
                val rows = Math.ceil(card.entities().size / raw.optInt("columns", 5).coerceAtLeast(1).toDouble()).toInt()
                (if (card.title != null) 2 else 0) + lines * rows
            }
            "button" -> (if (raw.optBoolean("show_icon", true)) 4 else 0) + (if (raw.optBoolean("show_name", true)) 1 else 0)
            "markdown" -> if (raw.has("card_size")) raw.optInt("card_size", 1)
                else Math.round(raw.optString("content", "").split("\n").size / 2.0).toInt() + (if (card.title != null) 1 else 0)
            else -> 1
        }
    }
}
