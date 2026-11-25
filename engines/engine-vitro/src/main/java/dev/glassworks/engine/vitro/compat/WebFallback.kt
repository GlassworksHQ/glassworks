/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.EntityFilterConfig
import dev.glassworks.engine.vitro.model.EntityRowConfig
import dev.glassworks.engine.vitro.model.NATIVE_VIEW_TYPES
import dev.glassworks.engine.vitro.model.ViewConfig

/**
 * What Vitro hands to Home Assistant's own frontend (the fallback page) instead of drawing it, so a panel never shows
 * an empty page, an empty card or a missing row where HA draws something.
 *
 * - **Strategies.** A dashboard, view or section saved as a `strategy:` holds no cards: HA's frontend generates them
 *   in JavaScript (its built-in strategies, or a custom one from a Lovelace resource), and no WebSocket command returns
 *   what it generates. Vitro can't know the cards, so the whole dashboard (or the view) goes to HA's page.
 * - **View layouts Vitro doesn't draw**: a view `type` other than HA's masonry, panel, sidebar and sections and
 *   layout-card's four ([NATIVE_VIEW_TYPES]). Vitro never guesses another layout as masonry.
 * - **Built-in cards Vitro doesn't draw** (`map`, the energy cards, ...). A web island runs a custom card's
 *   own script but has no HA frontend, so a built-in card there is an empty element that still costs a browser
 *   session. A view with one goes to HA's page, which draws every card in it. So does a `shortcut` card whose
 *   default label or icon is a panel's, a view's or an action's name, which only HA's frontend knows.
 * - **Special `entities` rows Vitro doesn't draw** ([UNDRAWN_ROW_TYPES]). Without this, an `attribute` row would
 *   show the entity's state instead of the attribute. A view with one goes to HA's page.
 *
 * A custom card never comes here: its island runs its own script, so it draws as in HA. A custom card that wraps a
 * built-in card Vitro doesn't draw does: the reason walks every card a card builds ([CardRouter.producedCards]).
 */
object WebFallback {
    /**
     * HA's special `entities` rows (`hui-*-row` in its frontend) Vitro doesn't draw yet: `attribute` (its value needs
     * HA's attribute formatting) and `cast`. Rows Vitro does draw: plain entity rows (also with an explicit `*-entity`
     * type), and [EntityRowConfig.SPECIAL_TYPES]. A `custom:` row with an `entity` is drawn as a plain entity row.
     */
    val UNDRAWN_ROW_TYPES: Set<String> = setOf("attribute", "cast")

    /** Why the whole [dashboard] goes to HA's page, or null when Vitro draws it. */
    fun dashboardReason(dashboard: DashboardConfig): String? =
        if (dashboard.strategy) "a strategy dashboard (HA's frontend generates its views)" else null

    /** Why [view] goes to HA's page, or null when Vitro draws it. */
    fun viewReason(view: ViewConfig): String? {
        if (view.strategy) return "a strategy view (HA's frontend generates its cards)"
        if (view.type !in NATIVE_VIEW_TYPES) return "a `${view.type}` view (a layout Vitro doesn't draw)"
        if (view.sections.any { it.strategy }) return "a strategy section (HA's frontend generates its cards)"
        for (card in view.cards) cardReason(card)?.let { return it }
        for (section in view.sections) for (card in section.cards) cardReason(card)?.let { return it }
        return null
    }

    /** Why [card], or a card it builds, needs HA's frontend, or null when Vitro or an island draws all of it. */
    fun cardReason(card: CardConfig): String? {
        val type = card.type
        if (!type.startsWith("custom:") && type !in VitroSupport.CORE_CARD_TYPES) return "the built-in `$type` card"
        if (type == "entities") undrawnRow(card)?.let { return it }
        if (type == "shortcut") shortcutReason(card)?.let { return it }
        // Vitro has no double tap yet: a card it newly draws whose double-tap action does something stays HA's.
        if ((type == "shortcut" || type == "alert") && hasDoubleTap(card.raw)) return "a `$type` card with a double-tap action"
        // Vitro's actions don't open Assist.
        if (type == "alert" && listOf("tap_action", "hold_action").any { card.raw.optJSONObject(it)?.optString("action") == "assist" }) {
            return "an `alert` card that opens Assist"
        }
        // A footer (a graph, buttons, a picture) under the statistic isn't drawn natively.
        if (type == "statistic" && card.raw.has("footer") && !card.raw.isNull("footer")) return "a `statistic` card with a footer"
        // The energy dashboard's date picker drives such a card: it lives in HA's frontend (an energy card).
        if (type == "statistic" && (card.raw.optBoolean("energy_date_selection", false) || card.raw.optString("period") == "energy_date_selection")) {
            return "a `statistic` card that follows the energy date picker"
        }
        val children = CardRouter.producedCards(card).map { it.value } +
            (if (type == "entity-filter") listOf(EntityFilterConfig.innerCard(card)) else emptyList())
        for (child in children) cardReason(child)?.let { return it }
        return null
    }

    /** A `double_tap_action` that does something (Vitro binds tap and hold, not double tap). */
    private fun hasDoubleTap(o: org.json.JSONObject): Boolean =
        o.optJSONObject("double_tap_action")?.optString("action", "none").let { it != null && it != "none" }

    /**
     * Why a row in [card]'s `entities` needs HA's frontend, a conditional row's row included, or null: a row type
     * Vitro doesn't draw, or a button row (or a chip of a buttons row) with a double-tap action, which Vitro has no
     * gesture for yet.
     */
    private fun undrawnRow(card: CardConfig): String? {
        val rows = card.raw.optJSONArray("entities") ?: return null
        fun walk(row: Any?): String? {
            val o = row as? org.json.JSONObject ?: return null
            val t = o.optString("type", "")
            if (t in UNDRAWN_ROW_TYPES) return "an `$t` row in an entities card"
            if (t in setOf("button", "call-service", "perform-action") && hasDoubleTap(o)) return "a `$t` row with a double-tap action"
            if (t == "buttons") {
                val chips = o.optJSONArray("entities")
                if (chips != null && (0 until chips.length()).any { chips.optJSONObject(it)?.let(::hasDoubleTap) == true }) {
                    return "a `buttons` row with a double-tap action"
                }
            }
            return if (t == "conditional") walk(o.opt("row")) else null
        }
        for (i in 0 until rows.length()) walk(rows.opt(i))?.let { return it }
        return null
    }

    /**
     * Why a `shortcut` card needs HA's frontend: with no `label` (or no `icon`), HA shows its action's own one, which
     * for a dashboard, view or panel path is that page's title and icon, and for an action the service's translated
     * name and icon. Vitro draws the defaults it knows the same way: none, `url`, `assist`, and area, device and
     * settings paths.
     */
    private fun shortcutReason(card: CardConfig): String? {
        val raw = card.raw
        val tap = raw.optJSONObject("tap_action")
        val action = tap?.optString("action", "none") ?: "none"
        // Vitro's actions don't open Assist: HA's page does.
        if (listOf("tap_action", "hold_action").any { raw.optJSONObject(it)?.optString("action") == "assist" }) {
            return "a `shortcut` card that opens Assist"
        }
        val needsLabel = raw.optString("label", "").isEmpty()
        val needsIcon = raw.optString("icon", "").isEmpty()
        if (!needsLabel && !needsIcon) return null
        return when (action) {
            "perform-action", "call-service" -> "a `shortcut` card that shows its action's name or icon"
            "navigate" -> if (ShortcutDefaults.navigation(tap?.optString("navigation_path", "").orEmpty()) == null) {
                "a `shortcut` card that shows the page it opens by its title or icon"
            } else {
                null
            }
            else -> null
        }
    }
}

/**
 * The label and icon HA's `shortcut` card shows when its config has none (`getShortcutCardDefaults`), for the
 * actions whose defaults Vitro knows without HA's frontend. A navigation path to a dashboard, a view or a panel is
 * named by that page, which [navigation] doesn't know (null): such a card goes to HA's frontend ([WebFallback]).
 */
object ShortcutDefaults {
    /** A default: [label] is literal, or the id of an area or device (a [kind] of `area` or `device`) to look up. */
    data class Default(val label: String, val icon: String, val kind: String = "text")

    /** `none` and any action without a default of its own: no label, the link icon. */
    val NONE = Default("", "mdi:link")

    private val CONFIG_ROUTES = mapOf(
        "automation" to ("Automations" to "mdi:robot"),
        "scene" to ("Scenes" to "mdi:palette"),
        "script" to ("Scripts" to "mdi:script-text"),
        "tools" to ("Tools" to "mdi:hammer"),
        "integrations" to ("Integrations" to "mdi:puzzle"),
        "devices" to ("Devices" to "mdi:devices"),
        "entities" to ("Entities" to "mdi:shape"),
    )

    /** The default for [tap] (the card's `tap_action`), or null when only HA's frontend knows it. */
    fun of(tap: org.json.JSONObject?): Default? = when (tap?.optString("action", "none") ?: "none") {
        "navigate" -> navigation(tap?.optString("navigation_path", "").orEmpty())
        "assist" -> Default("Assist", "mdi:microphone")
        "url" -> Default(tap?.optString("url_path", "").orEmpty(), "mdi:open-in-new")
        "perform-action", "call-service" -> null
        else -> NONE
    }

    /**
     * `computeNavigationPathInfo` for the paths it resolves without panels or a dashboard's config: an area
     * (`/config/areas/area/<id>`, `/<dashboard>/areas-<id>`), a device (`/config/devices/device/<id>`) and the
     * settings pages it names. An empty path is HA's default (no label, the link icon).
     */
    fun navigation(path: String): Default? {
        if (path.isEmpty()) return NONE
        val segments = path.removePrefix("/").split('/', '?')
        val panel = segments.getOrNull(0).orEmpty()
        val sub = segments.getOrNull(1)
        if (panel == "config" && sub == "areas" && segments.getOrNull(2) == "area" && !segments.getOrNull(3).isNullOrEmpty()) {
            return Default(segments[3], "mdi:texture-box", kind = "area")
        }
        if (panel == "config" && sub == "devices" && segments.getOrNull(2) == "device" && !segments.getOrNull(3).isNullOrEmpty()) {
            return Default(segments[3], "mdi:devices", kind = "device")
        }
        if (panel == "config" && sub != null) CONFIG_ROUTES[sub]?.let { (label, icon) -> return Default(label, icon) }
        if (sub != null && sub.startsWith("areas-")) return Default(sub.removePrefix("areas-"), "mdi:texture-box", kind = "area")
        return null
    }
}
