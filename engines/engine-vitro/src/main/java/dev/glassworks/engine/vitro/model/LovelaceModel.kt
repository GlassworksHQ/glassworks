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
    data class NumericState(val entity: String, val above: Double?, val below: Double?) : VisibilityCondition()
    data class Screen(val mediaQuery: String) : VisibilityCondition()
    data class And(val conditions: List<VisibilityCondition>) : VisibilityCondition()
    data class Or(val conditions: List<VisibilityCondition>) : VisibilityCondition()
    data class Not(val conditions: List<VisibilityCondition>) : VisibilityCondition()
    /** HA's `user` condition: visible only to the listed user ids. */
    data class User(val users: List<String>) : VisibilityCondition()
    /**
     * HA's `time` condition. [after] and [before] are minutes from local midnight (0..1439); a window with
     * `after > before` runs overnight. [weekdays] holds [Calendar] day constants (null means every day).
     */
    data class Time(val after: Int?, val before: Int?, val weekdays: Set<Int>?) : VisibilityCondition()
    /**
     * A condition Vitro can't evaluate (`sun`, `template`, a malformed `state`, …). It always hides the card:
     * a hidden camera or alarm card must never appear because a condition was not understood.
     */
    data class Unknown(val condition: String) : VisibilityCondition()

    fun evaluate(
        lookup: (String) -> EntityState?,
        widthDp: Int = 1024,
        userId: String? = null,
        nowMinutes: Int = currentLocalMinutes(),
        weekday: Int = currentLocalWeekday(),
    ): Boolean = when (this) {
        is State -> {
            // Like HA's frontend, a missing entity reads as "unavailable": `state_not: off` still shows it.
            val cur = lookup(entity)?.state ?: UNAVAILABLE
            (states == null || cur in states) && (statesNot == null || cur !in statesNot)
        }
        is NumericState -> {
            val v = lookup(entity)?.state?.toDoubleOrNull() ?: return false
            (above == null || v > above) && (below == null || v < below)
        }
        is Screen -> evaluateMediaQuery(mediaQuery, widthDp)
        is And -> conditions.all { it.evaluate(lookup, widthDp, userId, nowMinutes, weekday) }
        is Or -> conditions.any { it.evaluate(lookup, widthDp, userId, nowMinutes, weekday) }
        is Not -> conditions.none { it.evaluate(lookup, widthDp, userId, nowMinutes, weekday) }
        is User -> userId != null && userId in users
        is Time -> evaluateTime(nowMinutes, weekday)
        is Unknown -> false
    }

    private fun Time.evaluateTime(nowMinutes: Int, weekday: Int): Boolean {
        if (weekdays != null && weekday !in weekdays) return false
        return when {
            after != null && before != null ->
                if (after <= before) nowMinutes in after until before else nowMinutes >= after || nowMinutes < before
            after != null -> nowMinutes >= after
            before != null -> nowMinutes < before
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
            is NumericState -> out += entity
            is Screen, is User, is Time, is Unknown -> Unit
            is And -> conditions.forEach { it.referencedEntities(out) }
            is Or -> conditions.forEach { it.referencedEntities(out) }
            is Not -> conditions.forEach { it.referencedEntities(out) }
        }
    }

    companion object {
        private const val UNAVAILABLE = "unavailable"
        private val MIN_W = Regex("""min-width\s*:\s*(\d+)px""")
        private val MAX_W = Regex("""max-width\s*:\s*(\d+)px""")
        private val HHMM = Regex("""^\s*(\d{1,2}):(\d{2})(?::(\d{2}))?\s*$""")
        private val WEEKDAYS = mapOf(
            "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY,
            "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY,
        )

        fun currentLocalMinutes(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

        fun currentLocalWeekday(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

        private fun evaluateMediaQuery(q: String, widthDp: Int): Boolean {
            val min = MIN_W.find(q)?.groupValues?.get(1)?.toIntOrNull()
            val max = MAX_W.find(q)?.groupValues?.get(1)?.toIntOrNull()
            return (min == null || widthDp >= min) && (max == null || widthDp <= max)
        }

        /** `HH:MM` or `HH:MM:SS` as minutes from midnight, or null when it isn't a valid time of day. */
        fun parseTimeOfDay(raw: String?): Int? {
            val m = HHMM.find(raw ?: return null) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h !in 0..23 || min !in 0..59) return null
            return h * 60 + min
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
                    val entity = o.optString("entity", "").ifEmpty { return Unknown(condName) }
                    val st = parseStringList(o, "state")
                    val not = parseStringList(o, "state_not")
                    if (st == null && not == null) return Unknown(condName)
                    State(entity, st, not)
                }
                "numeric_state" -> {
                    val entity = o.optString("entity", "").ifEmpty { return Unknown(condName) }
                    val above = if (o.has("above") && !o.isNull("above")) o.optDouble("above") else null
                    val below = if (o.has("below") && !o.isNull("below")) o.optDouble("below") else null
                    NumericState(entity, above?.takeIf { !it.isNaN() }, below?.takeIf { !it.isNaN() })
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

    /**
     * Whether Vitro draws this card natively or sends it to a web island, and why ([CardCompat]). Decided once,
     * the first time it's asked after the dashboard is parsed, and never per frame.
     */
    val compat: CardVerdict by lazy { CardCompat.check(this) }

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
        card.compat.inIsland(scripts) -> CardTier.WEB_ISLAND
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
            if (c.childCards.isNotEmpty() && c.type in CONTAINER_TYPES && !c.compat.inIsland(scripts)) {
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
        fun walk(c: CardConfig): Boolean = c.type in FOLLOWS_ALL_TYPES || c.childCards.any(::walk) ||
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
