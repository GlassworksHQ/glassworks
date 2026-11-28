/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.model

import dev.glassworks.engine.vitro.compat.CardCompat
import dev.glassworks.engine.vitro.compat.CardRoute
import dev.glassworks.engine.vitro.compat.CardVerdict
import dev.glassworks.engine.vitro.compat.IslandScripts
import dev.glassworks.engine.vitro.compat.NativeOptions
import dev.glassworks.engine.vitro.compat.VitroSupport
import dev.glassworks.engine.vitro.compat.WebFallback
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaEntityName
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

/**
 * The dashboard's Lovelace resources as Home Assistant's connection lists them, with what it learned about that Home
 * Assistant alongside: [bubbleModules] is true when Bubble Card modules may restyle every Bubble card there
 * ([dev.glassworks.engine.vitro.data.BubbleModules]), so Vitro can't draw one as Bubble does. A plain list (tests, the
 * offline checker) knows of no modules.
 */
class LovelaceResourceList(
    private val items: List<LovelaceResource>,
    val bubbleModules: Boolean,
) : List<LovelaceResource> by items {
    // A List like any other: equal to a list of the same resources, and hashed as one. Whoever needs [bubbleModules]
    // compares it on its own (VitroView.resourcesList).
    override fun equals(other: Any?): Boolean = other is List<*> && items == other
    override fun hashCode(): Int = items.hashCode()
    override fun toString(): String = "$items (bubbleModules=$bubbleModules)"
}

data class DashboardConfig(
    val title: String?,
    val views: List<ViewConfig>,
    /**
     * The config as Home Assistant sent it (its JSON text), or null when it wasn't parsed from one. Two configs with
     * the same source draw the same dashboard: a reconnect or `lovelace_updated` that brings it again rebuilds nothing.
     */
    val source: String? = null,
    /** Saved as a `strategy:`: HA's frontend generates the views, so the config has none ([WebFallback]). */
    val strategy: Boolean = false,
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

    /**
     * [viewIndex] for the signed-in user [userId] (null: not known): with no path HA's `hui-root` opens the first view
     * [ViewConfig.visibleTo] them (`views.findIndex(_isVisible)`), not the first one. A path opens its view, hidden or not.
     */
    fun viewIndex(path: String?, userId: String?): Int {
        if (views.isEmpty()) return 0
        if (path.isNullOrBlank()) return views.indexOfFirst { it.visibleTo(userId) }.coerceAtLeast(0)
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
    /** Saved as a `strategy:`: HA's frontend generates the cards, so the config has none ([WebFallback]). */
    val strategy: Boolean = false,
    /** The view's config as HA stores it: a layout-card view's `layout:` and the like. */
    val raw: JSONObject = JSONObject(),
) {
    /** A layout-card view (`type: custom:masonry-layout`, ...): the layout's name without `custom:`, else null. */
    val layoutCardType: String? get() = type.takeIf { it in LAYOUT_CARD_VIEW_TYPES }?.removePrefix("custom:")

    /** Why this view goes to HA's own frontend instead of being drawn ([WebFallback.viewReason]), or null. */
    val webFallbackReason: String? by lazy { WebFallback.viewReason(this) }

    /**
     * True when a card the view builds is (or holds) `custom:auto-entities`, whose matches are checked at run time
     * ([WebFallback.matchedRowsReason]).
     */
    internal val hasAutoEntities: Boolean by lazy {
        fun walk(c: CardConfig): Boolean =
            c.type == "custom:auto-entities" || CardRouter.producedCards(c, perEntity = false).any { walk(it.value) }
        CardRouter.viewRoots(this).any { walk(it.card) }
    }

    /**
     * What HA's `hui-root` puts in this view's tab (frontend 20261028.5): a view with an icon shows the icon alone, or
     * the icon and its title when the view sets `show_icon_and_title` and has a title; a view with no icon shows its
     * title, or [unnamed] (HA's "Unnamed view") when it has none.
     */
    fun tab(unnamed: () -> String): ViewTab {
        val title = raw.optString("title", "").ifEmpty { null }
        val both = icon != null && title != null && truthy(raw.opt("show_icon_and_title"))
        return when {
            both -> ViewTab(icon, title, title)
            icon != null -> ViewTab(icon, null, title)
            else -> ViewTab(null, title ?: unnamed(), title)
        }
    }

    /**
     * HA's `hui-root` `_isVisible` (outside edit mode): a view with no `visible`, `visible: true`, or a list of
     * `{user: id}` that holds [userId]. Which view a dashboard opens on reads it ([DashboardConfig.viewIndex]).
     */
    fun visibleTo(userId: String?): Boolean {
        if (!raw.has("visible")) return true
        return when (val v = raw.opt("visible")) {
            true -> true
            is JSONArray -> listedFor(v, userId)
            else -> false
        }
    }

    /**
     * HA's `hui-root` `_isTabHiddenForUser`: `visible: false`, or a list of `{user: id}` without [userId]. Such a
     * view, as a subview, has no tab unless it's the view on screen.
     */
    fun tabHiddenFor(userId: String?): Boolean {
        if (!raw.has("visible")) return false
        return when (val v = raw.opt("visible")) {
            false -> true
            is JSONArray -> !listedFor(v, userId)
            else -> false
        }
    }

    /**
     * `visible.some((e) => e.user === hass.user?.id)`, strictly: an entry with no `user` matches only when no user is
     * known (`undefined === undefined`), and a `user: null` never does.
     */
    private fun listedFor(list: JSONArray, userId: String?): Boolean = (0 until list.length()).any { i ->
        val entry = list.opt(i)
        val user: Any? = if (entry is JSONObject && entry.has("user")) entry.opt("user") else null
        if (userId == null) user == null else user == userId
    }
}

/**
 * A view's tab ([ViewConfig.tab]): its [icon], its [text] (null: the icon alone, HA's `icon-only` tab), and its
 * [label], HA's `aria-label` and the icon's tooltip (the view's own title, null when it has none).
 */
data class ViewTab(val icon: String?, val text: String?, val label: String?)

/** JavaScript's truthiness, for a config value HA tests with `&&` (`show_icon_and_title`). */
internal fun truthy(v: Any?): Boolean = when (v) {
    null, JSONObject.NULL, false -> false
    is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
    is String -> v.isNotEmpty()
    else -> true
}

/** The view types layout-card registers (`masonry-layout`, ...), as a view's `type`. */
val LAYOUT_CARD_VIEW_TYPES: Set<String> =
    setOf("custom:masonry-layout", "custom:horizontal-layout", "custom:vertical-layout", "custom:grid-layout")

/** The view types Vitro lays out natively: HA's own masonry, panel, sidebar and sections, and layout-card's. */
val NATIVE_VIEW_TYPES: Set<String> = setOf("masonry", "panel", "sidebar", "sections") + LAYOUT_CARD_VIEW_TYPES

data class SectionConfig(
    val type: String, // "grid"
    val title: String?,
    val columnSpan: Int,
    val cards: List<CardConfig>,
    val visibility: List<VisibilityCondition>,
    /** Saved as a `strategy:`: HA's frontend generates the cards ([WebFallback]). */
    val strategy: Boolean = false,
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
    /** A `more-info` action's own `entity`, which HA's `handleAction` opens before the card's. */
    val entity: String? = null,
    /** A `navigate` action's `navigation_replace`: HA's `navigate(path, {replace: true})`, no step in the history. */
    val replace: Boolean = false,
) {
    companion object {
        private const val DEFAULT_CONFIRMATION = "Are you sure?"

        /**
         * HA's `DOMAINS_TOGGLE` (`common/const.ts`): the domains a card's default tap toggles. Every other domain opens
         * More Info, a lock, a cover, a script or a siren included: HA never actuates them on a tap nobody configured.
         * (Which service a `toggle` calls is [dev.glassworks.engine.vitro.ui.CardSurface.toggleEntity]'s business.)
         */
        val DOMAINS_TOGGLE: Set<String> = setOf("fan", "input_boolean", "light", "switch", "group", "automation", "humidifier", "valve")

        /**
         * The default `tap_action` of HA's button card, button row and picture-glance toggle icons: `toggle` for an
         * entity in [DOMAINS_TOGGLE], `more-info` otherwise (and with no entity).
         */
        fun defaultTap(entityId: String?): String =
            if (entityId != null && entityId.substringBefore('.') in DOMAINS_TOGGLE) "toggle" else "more-info"

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
                entity = (raw.opt("entity") as? String)?.trim()?.ifEmpty { null },
                replace = raw.optBoolean("navigation_replace", false),
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
    /**
     * `entity` for an entity row; HA's special rows by their `type` otherwise: `divider`, `section`, `button` (also
     * `call-service` and `perform-action`, as HA builds them), `text`, `weblink`, `buttons` and `conditional`.
     */
    val rowType: String = "entity",
    /** A special row's own config, as HA's `hui-*-row` reads it (null for entity rows). */
    val raw: JSONObject? = null,
    /** A `conditional` row's `conditions`. */
    val conditions: List<VisibilityCondition> = emptyList(),
    /** A `conditional` row's `row`, or null when it has none HA can build. */
    val inner: EntityRowConfig? = null,
    /** A `buttons` row's entities, as `processConfigEntities` reads them. */
    val buttons: List<JSONObject> = emptyList(),
    /**
     * `name` in HA's name-picker form (a part or a list of parts), resolved when drawn ([nameFor]); [name] then holds
     * its text parts alone.
     */
    val nameSpec: Any? = null,
    /** An entity row's `action_name`: the label of a script's Run and a scene's Activate button. */
    val actionName: String? = null,
) {
    val isEntity: Boolean get() = rowType == "entity"

    /**
     * The row's name for its [entity], whose state is [s]: [name], or [nameSpec] as HA's `formatEntityName` joins it.
     * Null when the row sets none.
     */
    fun nameFor(s: EntityState?): String? =
        if (nameSpec != null) HaEntityName.format(nameSpec, entity.ifEmpty { null }, s?.friendlyName) else name

    /** Every entity this row reads: its own, a conditional's conditions and row, a buttons row's buttons. */
    fun referencedEntities(out: MutableSet<String>) {
        if (entity.isNotEmpty()) out += entity
        conditions.forEach { it.referencedEntities(out) }
        inner?.referencedEntities(out)
        buttons.forEach { b -> b.optString("entity", "").takeIf { it.isNotEmpty() }?.let { out += it } }
    }

    companion object {
        /** The special row types Vitro parses ([parse]); a row Vitro doesn't draw as HA does sends its view to HA's frontend ([WebFallback]). */
        val SPECIAL_TYPES: Set<String> get() = VitroSupport.ENTITIES_ROW_TYPES

        /**
         * One `entities:` entry as HA's `createRowElement` builds it, or null for one HA can't build (it shows an error
         * row there, Vitro leaves it out): a string is an entity row, an object with a special [SPECIAL_TYPES] `type`
         * that row, any other object with an `entity` an entity row.
         */
        fun parse(item: Any?): EntityRowConfig? = when (item) {
            is String -> item.takeIf { it.isNotBlank() }?.let { EntityRowConfig(it) }
            is JSONObject -> parseObject(item)
            else -> null
        }

        private val NAME_SPEC_ROWS = setOf("call-service", "perform-action", "button")

        private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key, "").ifEmpty { null }

        private fun parseObject(item: JSONObject): EntityRowConfig? {
            val spec = item.opt("name").takeIf(HaEntityName::isStructured) ?: return parseRow(item, item.str("name"))
            // HA's name-picker form: its text parts stand in for the name until the row draws it with its entity.
            // A row that resolves the name per entity (call-service, perform-action, button) keeps an empty
            // placeholder, so a name made only of entity parts doesn't drop it; text and weblink rows keep null.
            val text = HaEntityName.format(spec, null, null)?.ifEmpty { null }
                ?: "".takeIf { item.optString("type", "") in NAME_SPEC_ROWS }
            return parseRow(item, text)?.let { if (it.isEntity || it.rowType == "button") it.copy(nameSpec = spec) else it }
        }

        private fun parseRow(item: JSONObject, name: String?): EntityRowConfig? {
            val icon = item.str("icon")
            return when (val type = item.optString("type", "")) {
                "divider" -> EntityRowConfig(entity = "", rowType = "divider", raw = item)
                "section" -> EntityRowConfig(entity = "", name = item.str("label"), rowType = "section", raw = item)
                // hui-call-service-row: a button row whose tap runs its `action` (or `service`) with its data.
                "call-service", "perform-action" -> {
                    if (name == null) return null
                    val service = item.str("action") ?: item.str("service") ?: return null
                    val tap = JSONObject().put("action", "perform-action").put("perform_action", service)
                    (item.optJSONObject("data") ?: item.optJSONObject("service_data"))?.let { tap.put("data", it) }
                    val merged = JSONObject(item.toString()).apply {
                        if (!has("tap_action")) put("tap_action", tap)
                        put("type", "button")
                    }
                    parseRow(merged, name)
                }
                "button" -> {
                    val entity = item.str("entity")
                    if (name == null && entity == null) return null
                    EntityRowConfig(
                        entity = entity.orEmpty(), name = name, icon = icon, rowType = "button", raw = item,
                        tapAction = ActionConfig.parse(item.optJSONObject("tap_action"), ActionConfig.defaultTap(entity)),
                        holdAction = ActionConfig.parse(item.optJSONObject("hold_action"), "more-info"),
                    )
                }
                // hui-text-row: both are required.
                "text" -> if (name == null || item.str("text") == null) null
                    else EntityRowConfig(entity = "", name = name, icon = icon, rowType = "text", raw = item)
                "weblink" -> {
                    val url = item.str("url") ?: return null
                    // HA refuses a scheme that would run script (`javascript:`, `data:`, `vbscript:`).
                    if (unsafeUrl(url)) return null
                    EntityRowConfig(entity = "", name = name ?: url, icon = icon ?: "mdi:link", rowType = "weblink", raw = item)
                }
                "buttons" -> {
                    val arr = item.optJSONArray("entities") ?: return null
                    val list = ArrayList<JSONObject>(arr.length())
                    for (i in 0 until arr.length()) {
                        when (val e = arr.opt(i)) {
                            is String -> list += JSONObject().put("entity", e)
                            is JSONObject -> if (e.has("type") || e.has("entity")) list += e else return null
                            else -> return null
                        }
                    }
                    EntityRowConfig(entity = "", rowType = "buttons", raw = item, buttons = list)
                }
                "conditional" -> {
                    val conds = item.optJSONArray("conditions") ?: return null
                    val row = item.optJSONObject("row") ?: return null
                    // hui-conditional-row passes the card's `color` on to its row (applyDefaultColor).
                    val inner = parse(row) ?: return null
                    EntityRowConfig(
                        entity = "", rowType = "conditional", raw = item,
                        conditions = VisibilityCondition.parseList(conds), inner = inner,
                    )
                }
                else -> {
                    val id = item.str("entity") ?: return null
                    // Any other row with an entity is parsed as an entity row. One Vitro doesn't draw as HA does (an
                    // `attribute` row, a custom row, a domain whose control it lacks) sends its view to HA's frontend.
                    EntityRowConfig(
                        entity = id,
                        name = name,
                        icon = icon,
                        secondaryInfo = item.str("secondary_info"),
                        actionName = item.str("action_name"),
                        tapAction = item.optJSONObject("tap_action")?.let { ActionConfig.parse(it) },
                        holdAction = item.optJSONObject("hold_action")?.let { ActionConfig.parse(it) },
                        raw = if (type.isEmpty()) null else item,
                    )
                }
            }
        }

        /** `@braintree/sanitize-url` turning [url] into `about:blank`: a script-running scheme, as HA's weblink row and iframe card check. */
        fun unsafeUrl(url: String): Boolean {
            // sanitize-url strips control and whitespace characters and HTML entities before it reads the scheme.
            val cleaned = url.replace(Regex("""[\u0000-\u001F\u007F-\u009F\u2000-\u200D\uFEFF\s]"""), "")
                .replace(Regex("""&#?\w+;"""), "")
                .trim()
            val scheme = Regex("""^([^:/?#]+):""").find(cleaned)?.groupValues?.get(1)?.lowercase() ?: return false
            return scheme in setOf("javascript", "data", "vbscript")
        }
    }
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
    val icon: String? get() = raw.optString("icon", "").ifEmpty { null }

    /**
     * The card's `name` for its [entity], whose state is [s]: text as it is (an empty one counts as none), HA's
     * name-picker form (`{type: device}`, `[{type: area}, {type: entity}]`) as HA joins it ([HaEntityName]). Null when
     * the card sets no name, so the card draws its default. Read it when drawing: the registries it names may land
     * after the dashboard does.
     */
    fun name(s: EntityState?): String? = nameFor(entity, s)

    /** [name] for [entityId] (a card whose name isn't about its `entity`, or one that names another), state [s]. */
    fun nameFor(entityId: String?, s: EntityState?): String? = when (val v = raw.opt("name")) {
        is String -> v.ifEmpty { null }
        else -> HaEntityName.format(v, entityId, s?.friendlyName)
    }

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

    /**
     * The options and values in this card's config its native renderer doesn't draw as HA does ([NativeOptions]),
     * decided once: empty when it draws all of them.
     */
    val unsupportedOptions: List<String> by lazy { NativeOptions.cardReasons(type, raw) }

    fun entities(): List<EntityRowConfig> {
        val arr = raw.optJSONArray("entities") ?: return emptyList()
        val out = ArrayList<EntityRowConfig>(arr.length())
        for (i in 0 until arr.length()) EntityRowConfig.parse(arr.opt(i))?.let { out += it }
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
        "custom:stack-in-card", "custom:vertical-stack-in-card", "custom:layout-card", "custom:swipe-card",
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
        // A vertical layout-card view consumes its `custom:layout-break` markers, as the card does.
        view.layoutCardType == "vertical-layout" -> view.cards.withIndex().filter { it.value.type != LAYOUT_BREAK }.map { ViewRoot(null, it.index, it.value) }
        // Masonry, sidebar and the other layout-card views: the view's own cards.
        else -> view.cards.mapIndexed { i, c -> ViewRoot(null, i, c) }
    }

    /**
     * True when a card in [view] chooses its entities at runtime (`custom:auto-entities`), so the view must follow
     * every entity rather than only the ones [referencedEntities] names.
     */
    fun followsAllEntities(view: ViewConfig): Boolean {
        // An area card's sensors, alerts and camera are its area's entities, known only from HA's registries.
        // A shopping list card without an entity shows the first to-do list it finds.
        fun walk(c: CardConfig): Boolean = c.type in FOLLOWS_ALL_TYPES || c.childCards.any(::walk) ||
            (c.type == "shopping-list" && c.entity == null) ||
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

    /**
     * Every `entity_id` referenced anywhere in [view]: the view's own, which its first build waits for, and with every
     * other view's the dashboard's `subscribe_entities` (VitroEngine's dashboardEntities).
     */
    private val ACTION_KEYS = listOf("tap_action", "hold_action", "double_tap_action")

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
        // An action's own `entity` (HA's handleAction: a more-info or toggle of another entity than the card's): the
        // More Info sheet it opens reads that entity from the store.
        // Trimmed as ActionConfig.parse reads it, so " light.hall " subscribes the entity the tap opens.
        fun actionTargets(raw: JSONObject?) {
            if (raw == null) return
            for (key in ACTION_KEYS) {
                (raw.optJSONObject(key)?.opt("entity") as? String)?.trim()?.takeIf { isEntityId(it) }?.let { out += it }
            }
        }
        // A row's actions, and those of the rows inside it: a conditional row's `row`, a buttons row's `entities`.
        fun rowTargets(row: JSONObject?, depth: Int = 0) {
            if (row == null || depth > 8) return
            actionTargets(row)
            rowTargets(row.optJSONObject("row"), depth + 1)
            if (row.optString("type", "") == "buttons") {
                row.optJSONArray("entities")?.let { arr -> for (i in 0 until arr.length()) rowTargets(arr.optJSONObject(i), depth + 1) }
            }
        }
        fun walkCard(c: CardConfig) {
            c.entity?.let { out += it }
            c.entities().forEach { it.referencedEntities(out) }
            actionTargets(c.raw)
            c.raw.optJSONArray("entities")?.let { rows -> for (i in 0 until rows.length()) rowTargets(rows.optJSONObject(i)) }
            c.visibility.forEach { it.referencedEntities(out) }
            VisibilityCondition.parseList(c.raw.optJSONArray("conditions")).forEach { it.referencedEntities(out) }
            // Deep-scan custom cards (e.g. room arrays, sleep/outside/camera objects) so the entity
            // subscription captures every entity without knowing each card's schema.
            if (c.type.startsWith("custom:")) scanJson(c.raw)
            // weather-card reads `sun.sun` for its night icons and the sunrise and sunset, whatever its config says.
            if (c.type == "custom:weather-card") out += "sun.sun"
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
    fun parseDashboard(dashboard: JSONObject): DashboardConfig {
        // button-card's dashboard templates are plain merges: done once here, for the native card and the island alike.
        val raw = dev.glassworks.engine.vitro.cards.ButtonCardTemplates.expandDashboard(dashboard)
        val title = raw.optString("title", "").ifEmpty { null }
        val viewsArr = raw.optJSONArray("views") ?: JSONArray()
        val views = ArrayList<ViewConfig>(viewsArr.length())
        for (i in 0 until viewsArr.length()) {
            val v = viewsArr.optJSONObject(i) ?: continue
            views += parseView(i, v)
        }
        return DashboardConfig(title, views, source = raw.toString(), strategy = hasStrategy(raw))
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
        // HA's getViewType: `type`, else `panel: true`, else sections when the view has `sections`, else masonry when
        // it has `cards`, else (neither) an empty sections view.
        val inferredType = when {
            explicitType != null -> explicitType
            raw.optBoolean("panel", false) -> "panel"
            raw.has("sections") && !raw.isNull("sections") -> "sections"
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
            strategy = hasStrategy(raw),
            raw = raw,
        )
    }

    /** HA's own test: a config with a `strategy` is generated from it, whatever else it holds. */
    private fun hasStrategy(raw: JSONObject): Boolean = raw.has("strategy") && !raw.isNull("strategy")

    private fun parseSection(raw: JSONObject): SectionConfig = SectionConfig(
        type = raw.optString("type", "grid"),
        title = raw.optString("title", "").ifEmpty { null },
        columnSpan = raw.optInt("column_span", 1).coerceIn(1, 4),
        cards = parseCards(raw.optJSONArray("cards")),
        visibility = VisibilityCondition.parseList(raw.optJSONArray("visibility")),
        strategy = hasStrategy(raw),
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
        "custom:swipe-card",
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
                // HA's ensureBadgeConfig: a bare id is an entity badge that shows its name.
                is String -> out += CardConfig("entity", JSONObject().put("type", "entity").put("entity", item).put("show_name", true))
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

    /**
     * True when [url] is a Home Assistant Lovelace dashboard path rather than login, settings, blueprints or a file.
     * A path ending in a web file (`…/page.html`, `…/card.js`) is a file a server hands out, not a dashboard view,
     * wherever it is served from: Clarity's SurfaceFlinger check page (`/clarity/page/sfcheck.html`) opens in the
     * fallback, so the check reads the page's SurfaceView.
     */
    fun isDashboardUrl(url: String): Boolean {
        val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty().trim('/')
        if (path.isEmpty()) return true
        val first = path.substringBefore('/')
        if (first in NON_DASHBOARD_PREFIXES) return false
        return !WEB_FILE.containsMatchIn(path.substringAfterLast('/'))
    }

    /** A file name a web server hands out: a page, script, stylesheet, data or picture. */
    private val WEB_FILE = Regex("""\.(html?|m?js|json|css|xml|txt|png|jpe?g|gif|webp|svg|ico)$""", RegexOption.IGNORE_CASE)

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
 * A card's size in HA's `getCardSize` units (about 50 px each), as HA's masonry view and layout-card's masonry columns
 * weigh them: each built-in card's own `getCardSize` in frontend 20261028, else 1 (`computeCardSize`'s answer for a
 * card that has none or doesn't answer in time). Stacks add up (vertical) or take the largest (horizontal) of their
 * cards; a conditional card is its card's size, shown or not. [state] is a card's entity state where its size depends
 * on it (a Mushroom card's controls); null reads as Mushroom's "any option on".
 */
object HaCardSize {
    /** Mushroom's cards that extend `MushroomBaseCard` and keep its `getCardSize` (v5.3.0; the cover card says 1). */
    private val MUSHROOM_BASE_CARDS = setOf(
        "custom:mushroom-entity-card", "custom:mushroom-light-card", "custom:mushroom-fan-card",
        "custom:mushroom-person-card", "custom:mushroom-update-card", "custom:mushroom-lock-card",
        "custom:mushroom-media-player-card", "custom:mushroom-climate-card", "custom:mushroom-vacuum-card",
        "custom:mushroom-humidifier-card", "custom:mushroom-number-card", "custom:mushroom-select-card",
        "custom:mushroom-alarm-control-panel-card",
    )

    fun of(card: CardConfig, state: (CardConfig) -> EntityState? = { null }): Double {
        fun of(c: CardConfig): Double = of(c, state)
        val raw = card.raw
        fun has(key: String) = raw.has(key) && !raw.isNull(key)
        fun titled() = card.title != null
        fun headerFooter() = (if (has("header")) 3 else 0) + (if (has("footer")) 3 else 0)
        return when (card.type) {
            "vertical-stack" -> card.childCards.sumOf { of(it) }
            "horizontal-stack" -> card.childCards.maxOfOrNull { of(it) } ?: 0.0
            "conditional" -> card.childCards.firstOrNull()?.let { of(it) } ?: 1.0
            "grid" -> {
                val cols = card.gridColumns()
                val title = if (titled()) 1.0 else 0.0
                if (raw.optBoolean("square", true)) {
                    val rowHeight = when (cols) { 1 -> 5.0; 2 -> 3.0; 3 -> 2.0; else -> 1.0 }
                    val n = card.childCards.size
                    (if (n < cols) rowHeight else n.toDouble() / cols * rowHeight) + title
                } else {
                    title + card.childCards.chunked(cols).sumOf { row -> row.maxOf { of(it) } }
                }
            }
            "iframe" -> 1 + (raw.optString("aspect_ratio", "").replace("%", "").ifEmpty { "50" }.toDoubleOrNull() ?: 50.0) / 25
            "entity-filter" -> of(EntityFilterConfig.innerCard(card))
            in MUSHROOM_BASE_CARDS -> mushroomSize(card, state(card)).toDouble()
            // A custom card an island drew: its own getCardSize, as HA asks it ([IslandCardSizes]).
            else -> dev.glassworks.engine.vitro.compat.IslandCardSizes.size(raw) ?: intSize(card).toDouble()
        }
    }

    /**
     * `MushroomBaseCard.getCardSize`: 1, one more for the vertical layout, one more for a row of controls (not in the
     * horizontal layout, nor when they collapse).
     */
    private fun mushroomSize(card: CardConfig, s: EntityState?): Int {
        val raw = card.raw
        val layout = raw.optString("layout", "").ifEmpty { if (raw.optBoolean("vertical", false)) "vertical" else "default" }
        val controls = layout != "horizontal" && !raw.optBoolean("collapsible_controls", false) &&
            dev.glassworks.engine.vitro.ui.HaGridSize.mushroomHasControls(card.type, raw, s)
        return 1 + (if (layout == "vertical") 1 else 0) + (if (controls) 1 else 0)
    }

    private fun intSize(card: CardConfig): Int {
        val raw = card.raw
        fun has(key: String) = raw.has(key) && !raw.isNull(key)
        fun titled() = card.title != null
        fun headerFooter() = (if (has("header")) 3 else 0) + (if (has("footer")) 3 else 0)
        return when (card.type) {
            "entities" -> {
                val rows = raw.optJSONArray("entities")?.length() ?: 0
                (if (titled() || raw.optBoolean("show_header_toggle", false)) 2 else 0) + rows.coerceAtLeast(1) + headerFooter()
            }
            "tile" -> {
                val features = raw.optJSONArray("features")?.length() ?: 0
                val inline = features > 0 && raw.optString("features_position") == "inline"
                1 + (if (raw.optBoolean("vertical", false)) 1 else 0) + (if (inline) 0 else features)
            }
            "area" -> {
                val compact = raw.optString("display_type", "picture") == "compact"
                val features = raw.optJSONArray("features")?.length() ?: 0
                1 + (if (compact) (if (raw.optBoolean("vertical", false)) 1 else 0) else 2) + features
            }
            "glance" -> {
                val lines = listOf("show_icon", "show_name", "show_state").count { raw.optBoolean(it, true) }
                val rows = Math.ceil(card.entities().size / raw.optInt("columns", 5).coerceAtLeast(1).toDouble()).toInt()
                (if (titled()) 2 else 0) + lines * rows
            }
            "button", "entity-button" -> (if (raw.optBoolean("show_icon", true)) 4 else 0) + (if (raw.optBoolean("show_name", true)) 1 else 0)
            // button-card's getCardSize: its `card_size`, 3 by default.
            "custom:button-card" -> (raw.opt("card_size") as? Number)?.toInt()?.takeIf { it > 0 } ?: 3
            "markdown" -> if (raw.has("card_size")) raw.optInt("card_size", 1)
                else Math.round(raw.optString("content", "").split("\n").size / 2.0).toInt() + (if (titled()) 1 else 0)
            "entity", "statistic" -> 2 + (if (has("footer")) 3 else 0)
            "sensor" -> 2 + (if (raw.optString("graph") == "line") 3 else 0)
            "heading", "error", "section" -> 1
            // `title ? 2 : 0 + 2 * n`: HA's precedence, a titled history graph weighs 2 whatever it shows.
            "history-graph" -> if (titled()) 2 else 2 * (raw.optJSONArray("entities")?.length() ?: 0).coerceAtLeast(1)
            "statistics-graph" -> 5 + (if (titled()) 2 else 0) +
                (if (!raw.optBoolean("hide_legend", false)) raw.optJSONArray("entities")?.length() ?: 0 else 0)
            "gauge", "picture-elements" -> 4
            "thermostat", "humidifier" -> 7
            "light", "picture" -> 5
            "media-control", "picture-entity", "picture-glance", "plant-status" -> 3
            "calendar" -> 12
            "logbook" -> 9 + (if (titled()) 1 else 0)
            "todo-list", "shopping-list" -> (if (titled()) 2 else 0) + 3
            // Sized before its first update, when it can't know the code format yet.
            "alarm-panel" -> 10
            "clock" -> if (raw.optString("clock_size") == "small") 1 else 2
            "date" -> if (raw.optString("date_size") in setOf("medium", "large")) 2 else 1
            "alert", "shortcut", "toggle-group" -> if (raw.optBoolean("vertical", false)) 2 else 1
            "weather-forecast" -> 1 + (if (raw.opt("show_current") != false) 1 else 0) + (if (raw.opt("show_forecast") != false) 1 else 0) +
                (if (raw.optString("forecast_type") == "daily") 1 else 0)
            else -> 1
        }
    }
}
