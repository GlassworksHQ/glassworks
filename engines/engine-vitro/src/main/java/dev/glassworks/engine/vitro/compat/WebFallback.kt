/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.EntityFilterConfig
import dev.glassworks.engine.vitro.model.ViewConfig

/**
 * What Vitro hands to Home Assistant's own frontend (the fallback page) instead of drawing it, so a panel never shows
 * an empty page, an empty card or a missing row where HA draws something.
 *
 * - **Strategies.** A dashboard, view or section saved as a `strategy:` holds no cards: HA's frontend generates them
 *   in JavaScript (its built-in strategies, or a custom one from a Lovelace resource), and no WebSocket command returns
 *   what it generates. Vitro can't know the cards, so the whole dashboard (or the view) goes to HA's page.
 * - **Built-in cards Vitro doesn't draw** (`map`, `iframe`, the energy cards, ...). A web island runs a custom card's
 *   own script but has no HA frontend, so a built-in card there is an empty element that still costs a browser
 *   session. A view with one goes to HA's page, which draws every card in it.
 * - **Special `entities` rows Vitro doesn't draw** ([UNDRAWN_ROW_TYPES]). Without this, an `attribute` row would
 *   show the entity's state instead of the attribute, and `buttons`, `weblink` or `conditional` rows would be
 *   dropped. A view with one goes to HA's page.
 *
 * A custom card never comes here: its island runs its own script, so it draws as in HA. A custom card that wraps a
 * built-in card Vitro doesn't draw does: the reason walks every card a card builds ([CardRouter.producedCards]).
 */
object WebFallback {
    /**
     * HA's special `entities` rows (`hui-*-row` in its frontend) Vitro doesn't draw yet. Rows Vitro does draw: plain
     * entity rows (also with an explicit `*-entity` type), `divider` and `section`. A `custom:` row with an `entity`
     * is drawn as a plain entity row.
     */
    val UNDRAWN_ROW_TYPES: Set<String> = setOf("attribute", "button", "buttons", "call-service", "cast", "conditional", "text", "weblink")

    /** Why the whole [dashboard] goes to HA's page, or null when Vitro draws it. */
    fun dashboardReason(dashboard: DashboardConfig): String? =
        if (dashboard.strategy) "a strategy dashboard (HA's frontend generates its views)" else null

    /** Why [view] goes to HA's page, or null when Vitro draws it. */
    fun viewReason(view: ViewConfig): String? {
        if (view.strategy) return "a strategy view (HA's frontend generates its cards)"
        if (view.sections.any { it.strategy }) return "a strategy section (HA's frontend generates its cards)"
        for (card in view.cards) cardReason(card)?.let { return it }
        for (section in view.sections) for (card in section.cards) cardReason(card)?.let { return it }
        return null
    }

    /** Why [card], or a card it builds, needs HA's frontend, or null when Vitro or an island draws all of it. */
    fun cardReason(card: CardConfig): String? {
        val type = card.type
        if (!type.startsWith("custom:") && type !in VitroSupport.CORE_CARD_TYPES) return "the built-in `$type` card"
        if (type == "entities") undrawnRow(card)?.let { return "an `$it` row in an entities card" }
        val children = CardRouter.producedCards(card).map { it.value } +
            (if (type == "entity-filter") listOf(EntityFilterConfig.innerCard(card)) else emptyList())
        for (child in children) cardReason(child)?.let { return it }
        return null
    }

    /** The first row type in [card]'s `entities` Vitro doesn't draw, or null. */
    private fun undrawnRow(card: CardConfig): String? {
        val rows = card.raw.optJSONArray("entities") ?: return null
        for (i in 0 until rows.length()) {
            val t = rows.optJSONObject(i)?.optString("type", "") ?: continue
            if (t in UNDRAWN_ROW_TYPES) return t
        }
        return null
    }
}
