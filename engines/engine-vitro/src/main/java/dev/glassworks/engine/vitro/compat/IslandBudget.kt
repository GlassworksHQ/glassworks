/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import android.content.Context
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.CardTier
import dev.glassworks.engine.vitro.model.ViewConfig

/**
 * How many island pages one view may open before Home Assistant's frontend draws it instead.
 *
 * Every island is a browser page of its own, loading every Lovelace resource of the dashboard: about 24 MiB of PSS for
 * a blank one on the tablet, more with what its scripts parse. A view with 20 custom cards would be 20 of those on a
 * 2 GB panel, and past a few, one session of HA's frontend that draws the whole view costs less. A view over the
 * budget goes there ([WebFallback] reasons, through [dev.glassworks.engine.vitro.ui.VitroView.fallbackReason]), as a
 * view with a card Vitro can't draw does. The count is [islandCount]: what the view's build opens, not what's on screen.
 */
object IslandBudget {
    /**
     * Total RAM from which a device runs [LARGE] islands in a view: 2 GiB, as for card layers. `MemTotal` reads below
     * a device's nominal size, so a 2 GB panel (the S1, the Mirror) stays on [MEDIUM].
     */
    const val LARGE_MIN_TOTAL_MEM = 2_147_483_648L

    /** Below this (about 1.5 GiB of RAM), a device runs [SMALL] islands in a view. */
    const val MEDIUM_MIN_TOTAL_MEM = 1_610_612_736L

    /** Islands per view on a device with less than [MEDIUM_MIN_TOTAL_MEM]. */
    const val SMALL = 4

    /** Islands per view on a 2 GB panel (the S1, the Mirror). To be measured on the S1 ([islandCount]). */
    const val MEDIUM = 6

    /** Islands per view on a device with [LARGE_MIN_TOTAL_MEM] and more (a tablet). */
    const val LARGE = 12

    fun forTotalMem(bytes: Long): Int = when {
        bytes >= LARGE_MIN_TOTAL_MEM -> LARGE
        bytes >= MEDIUM_MIN_TOTAL_MEM -> MEDIUM
        else -> SMALL
    }

    @Volatile private var device: Int? = null

    /** [forTotalMem] for this device, read once per process (`ActivityManager.MemoryInfo.totalMem`). */
    fun forDevice(context: Context): Int = device ?: run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val total = am?.let { m -> android.app.ActivityManager.MemoryInfo().also { m.getMemoryInfo(it) }.totalMem } ?: 0L
        forTotalMem(total).also { device = it }
    }

    /**
     * The island pages Vitro opens to build [view] with [scripts]: one per card that goes to an island (the cards inside
     * it draw in its page) and one per iframe card, through every container Vitro draws natively (stacks, grids,
     * conditionals, swipe and layout cards, auto-entities' cards: one per rule, the least it builds).
     */
    fun islandCount(view: ViewConfig, scripts: IslandScripts): Int =
        CardRouter.viewRoots(view).sumOf { islandsIn(it.card, scripts) }

    private fun islandsIn(card: CardConfig, scripts: IslandScripts): Int = when {
        CardRouter.tier(card, scripts) == CardTier.WEB_ISLAND -> 1
        card.type == "iframe" -> 1
        // Not per entity: auto-entities' generated cards are the inner card's children, counted through it once.
        else -> CardRouter.producedCards(card, perEntity = false).sumOf { islandsIn(it.value, scripts) }
    }

    /** Why [view] goes to HA's frontend for its islands, or null when it has [budget] of them or fewer. */
    fun reason(view: ViewConfig, scripts: IslandScripts, budget: Int): String? = reason(islandCount(view, scripts), budget)

    /** Why a view with [islands] island pages ([islandCount]) goes to HA's frontend, or null within [budget]. */
    fun reason(islands: Int, budget: Int): String? =
        if (islands > budget) "$islands cards in island pages, more than the $budget this device runs in one view" else null
}
