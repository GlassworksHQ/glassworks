/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.model

import dev.glassworks.engine.vitro.data.EntityState
import org.json.JSONArray
import org.json.JSONObject

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
)

sealed class VisibilityCondition {
    data class State(val entity: String, val state: String?, val stateNot: String?) : VisibilityCondition()
    data class NumericState(val entity: String, val above: Double?, val below: Double?) : VisibilityCondition()
    data class Screen(val mediaQuery: String) : VisibilityCondition()
    data class And(val conditions: List<VisibilityCondition>) : VisibilityCondition()
    data class Or(val conditions: List<VisibilityCondition>) : VisibilityCondition()
    data class Not(val conditions: List<VisibilityCondition>) : VisibilityCondition()

    fun evaluate(lookup: (String) -> EntityState?, widthDp: Int = 1024): Boolean = when (this) {
        is State -> {
            val cur = lookup(entity)?.state
            when {
                state != null -> cur == state
                stateNot != null -> cur != stateNot
                else -> true
            }
        }
        is NumericState -> {
            val v = lookup(entity)?.state?.toDoubleOrNull() ?: return false
            (above == null || v > above) && (below == null || v < below)
        }
        is Screen -> evaluateMediaQuery(mediaQuery, widthDp)
        is And -> conditions.all { it.evaluate(lookup, widthDp) }
        is Or -> conditions.any { it.evaluate(lookup, widthDp) }
        is Not -> conditions.none { it.evaluate(lookup, widthDp) }
    }

    fun referencedEntities(out: MutableSet<String>) {
        when (this) {
            is State -> out += entity
            is NumericState -> out += entity
            is Screen -> Unit
            is And -> conditions.forEach { it.referencedEntities(out) }
            is Or -> conditions.forEach { it.referencedEntities(out) }
            is Not -> conditions.forEach { it.referencedEntities(out) }
        }
    }

    companion object {
        private val MIN_W = Regex("""min-width\s*:\s*(\d+)px""")
        private val MAX_W = Regex("""max-width\s*:\s*(\d+)px""")

        private fun evaluateMediaQuery(q: String, widthDp: Int): Boolean {
            val min = MIN_W.find(q)?.groupValues?.get(1)?.toIntOrNull()
            val max = MAX_W.find(q)?.groupValues?.get(1)?.toIntOrNull()
            return (min == null || widthDp >= min) && (max == null || widthDp <= max)
        }

        fun parseList(arr: JSONArray?): List<VisibilityCondition> {
            if (arr == null) return emptyList()
            val out = ArrayList<VisibilityCondition>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { parse(it) }?.let { out += it }
            }
            return out
        }

        fun parse(o: JSONObject): VisibilityCondition? {
            return when (o.optString("condition", "state")) {
                "state" -> {
                    val entity = o.optString("entity", "").ifEmpty { return null }
                    val st = if (o.has("state") && !o.isNull("state")) o.optString("state") else null
                    val not = if (o.has("state_not") && !o.isNull("state_not")) o.optString("state_not") else null
                    State(entity, st, not)
                }
                "numeric_state" -> {
                    val entity = o.optString("entity", "").ifEmpty { return null }
                    val above = if (o.has("above") && !o.isNull("above")) o.optDouble("above") else null
                    val below = if (o.has("below") && !o.isNull("below")) o.optDouble("below") else null
                    NumericState(entity, above?.takeIf { !it.isNaN() }, below?.takeIf { !it.isNaN() })
                }
                "screen" -> Screen(o.optString("media_query", ""))
                "and" -> And(parseList(o.optJSONArray("conditions")))
                "or" -> Or(parseList(o.optJSONArray("conditions")))
                "not" -> Not(parseList(o.optJSONArray("conditions")))
                else -> null
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
    )

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
            if (c.childCards.isNotEmpty() && c.type in setOf("grid", "horizontal-stack", "vertical-stack", "conditional")) {
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

    private val ENTITY_ID_PATTERN = Regex("""^[a-z][a-z0-9_]*\.[a-z0-9_]+$""")

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
            c.entities().forEach { out += it.entity }
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
            "grid", "horizontal-stack", "vertical-stack" -> parseCards(raw.optJSONArray("cards"))
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
