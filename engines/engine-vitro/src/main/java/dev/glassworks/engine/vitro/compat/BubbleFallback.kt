/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.CardTier
import dev.glassworks.engine.vitro.model.ViewConfig

/**
 * A view whose Bubble card Vitro can't draw as Bubble does goes to Home Assistant's frontend when no island can draw it
 * either. Such a card (sub-buttons, a slider, a pop-up, styles: anything `native-options.json` sends to the island)
 * normally goes to the island, which runs Bubble's own script. Without that script among the dashboard's Lovelace
 * resources (Bubble loaded through `frontend: extra_module_url`, say), [CardVerdict.inIsland] would leave it to
 * [dev.glassworks.engine.vitro.cards.BubbleCardView], which draws only the plain configs and would drop the rest:
 * a sub-button's switch, a slider. So does any Bubble card while Bubble Card modules restyle them. HA's frontend draws
 * the card as HA does, with every control.
 */
object BubbleFallback {
    private const val BUBBLE = "custom:bubble-card"

    /** Why [view] goes to HA's frontend with [scripts], or null when every Bubble card in it is drawn as Bubble does. */
    fun reason(view: ViewConfig, scripts: IslandScripts): String? =
        CardRouter.viewRoots(view).firstNotNullOfOrNull { find(it.card, scripts) }

    private fun find(card: CardConfig, scripts: IslandScripts): String? = when {
        // An island draws it and everything inside it.
        CardRouter.tier(card, scripts) == CardTier.WEB_ISLAND -> null
        card.type == BUBBLE && card.compat.route == CardRoute.ISLAND ->
            "a Bubble card Vitro doesn't draw as Bubble does (${card.compat.reasons.firstOrNull() ?: "its options"}), " +
                "and no Lovelace resource loads Bubble Card's script for an island"
        // Bubble's modules restyle every Bubble card, and only Bubble's own script applies them.
        card.type == BUBBLE && scripts.bubbleModules ->
            "Bubble Card modules are set up in Home Assistant, and no Lovelace resource loads Bubble Card's script for an island"
        else -> CardRouter.producedCards(card, perEntity = false).firstNotNullOfOrNull { find(it.value, scripts) }
    }
}
