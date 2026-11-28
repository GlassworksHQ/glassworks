/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.ViewConfig

/** One card as Vitro builds it, where it sits in the dashboard, and its verdict. */
data class CompatEntry(val path: String, val verdict: CardVerdict)

/**
 * The compatibility checker's answer for a dashboard or a view: every card Vitro builds, whether it renders native
 * or falls back to an island, and the exact reasons. The cards inside a stack are listed on their own; the cards
 * inside a stack that falls back are not, because the island draws them.
 */
data class CompatReport(
    val entries: List<CompatEntry>,
    /** The dashboard or views Home Assistant's frontend shows instead ([WebFallback]), each with why. */
    val webPages: List<Pair<String, String>> = emptyList(),
) {
    val islands: List<CompatEntry> get() = entries.filter { !it.verdict.isNative }
    val natives: List<CompatEntry> get() = entries.filter { it.verdict.isNative }
    val notes: List<Pair<String, String>> get() = entries.flatMap { e -> e.verdict.notes.map { e.path to it } }

    /** A plain-text report: a summary line, then every fallback with its reasons, then the notes (gaps and warnings). */
    fun format(includeNotes: Boolean = true): String = buildString {
        append("${entries.size} cards: ${natives.size} native, ${islands.size} island")
        if (islands.isEmpty() && webPages.isEmpty()) append(" (fully native)")
        appendLine()
        for ((where, why) in webPages) appendLine("HA FRONTEND $where: $why")
        for (e in islands) {
            appendLine("ISLAND ${e.path}")
            e.verdict.reasons.forEach { appendLine("  - $it") }
        }
        if (includeNotes) {
            val distinct = notes.groupBy({ it.second }, { it.first })
            // Notes come from native cards (known gaps) and islands (a missing script) alike.
            if (distinct.isNotEmpty()) appendLine("Compatibility notes:")
            for ((note, paths) in distinct) {
                appendLine("  - $note" + if (paths.size > 1) " (${paths.size} cards)" else "")
            }
        }
    }.trimEnd()
}

/** Builds [CompatReport]s by walking a dashboard the way `VitroView` builds it. */
object CompatChecker {
    fun check(dashboard: DashboardConfig, scripts: IslandScripts = IslandScripts.ANY): CompatReport {
        WebFallback.dashboardReason(dashboard)?.let { return CompatReport(emptyList(), listOf("the dashboard" to it)) }
        val views = dashboard.views.map { check(it, scripts) }
        return CompatReport(views.flatMap { it.entries }, views.flatMap { it.webPages })
    }

    /**
     * [scripts]: the dashboard's Lovelace resources as an island loads them ([CardVerdict.inIsland]). Without them
     * ([IslandScripts.ANY]) the report assumes every card's script and card-mod are there.
     */
    fun check(view: ViewConfig, scripts: IslandScripts = IslandScripts.ANY): CompatReport {
        val out = ArrayList<CompatEntry>()
        val viewName = view.path ?: view.title
        // Vitro builds none of such a view: HA's frontend draws it.
        view.webFallbackReason?.let { return CompatReport(emptyList(), listOf("view $viewName" to it)) }
        for (root in CardRouter.viewRoots(view)) {
            val where = root.section?.let { "$viewName › section ${it + 1} › card ${root.index + 1}" } ?: "$viewName › card ${root.index + 1}"
            walk(root.card, where, scripts, out)
        }
        return CompatReport(out)
    }

    /**
     * The [CompatReport.islands] of [check] for [view], without checking the cards that can only stay native
     * ([CardCompat.staysNative]): what a view logs after it builds, at the cost of routing it.
     */
    fun islands(view: ViewConfig, scripts: IslandScripts = IslandScripts.ANY, iframes: Boolean = false): List<CompatEntry> {
        val out = ArrayList<CompatEntry>()
        val viewName = view.path ?: view.title
        for (root in CardRouter.viewRoots(view)) {
            val where = root.section?.let { "$viewName › section ${it + 1} › card ${root.index + 1}" } ?: "$viewName › card ${root.index + 1}"
            walk(root.card, where, scripts, out, islandsOnly = true, iframes = iframes)
        }
        return out
    }

    /**
     * Why an `iframe` card takes an island page though Vitro draws it ([CardCompat]'s note): [islands] lists it with
     * `iframes`, as a page it runs ([IslandBudget.islandCount] counts it too).
     */
    const val IFRAME_ISLAND = "the `iframe` card shows its page in a browser island of its own"

    /**
     * [holdsGenerated]: [card] is auto-entities' inner card under `card_param: cards`, whose `cards:` it generates.
     * [islandsOnly]: list only the cards that fall back, skipping the check of those that can only stay native.
     */
    private fun walk(
        card: CardConfig,
        path: String,
        scripts: IslandScripts,
        out: MutableList<CompatEntry>,
        holdsGenerated: Int = -1,
        islandsOnly: Boolean = false,
        iframes: Boolean = false,
    ) {
        val here = "$path (${card.type})"
        // Native, but its page runs in an island page of its own: listed as one with [iframes]. One with an option Vitro
        // doesn't draw is an island anyway, listed below with its reasons.
        if (iframes && card.type == "iframe" && card.compat.effective(scripts).isNative) {
            out += CompatEntry(here, card.compat.copy(reasons = listOf(IFRAME_ISLAND)))
            return
        }
        if (!(islandsOnly && card.nativeOnly)) {
            // A native auto-entities card is native only if the rows it matches at run time are: the report says so.
            val verdict = card.compat.effective(scripts).let { v ->
                if (v.isNative && card.type == WebFallback.AUTO_ENTITIES) v.copy(notes = v.notes + WebFallback.MATCHED_ROWS_NOTE) else v
            }
            if (!islandsOnly || !verdict.isNative) out += CompatEntry(here, verdict)
            if (!verdict.isNative) return
        }
        // auto-entities' inner card is checked with its generated cards. The ones a filter matches aren't listed: they
        // depend on live states. The explicit `entities:` entries come first and are built whatever the states, so
        // each of those is listed (an island among them included). They only fill its `cards:`, so its other children
        // (a conditional's `card:`) are listed as usual.
        if (holdsGenerated >= 0 && card.type in LovelaceParser.CARDS_FIELD_TYPES) {
            // By the child's index in the config, not its place among the built ones: a layout-break among the
            // explicit entries isn't built, and the first generated card mustn't take its place.
            CardRouter.producedCards(card, perEntity = false).filter { (i, _) -> i < holdsGenerated }.forEach { (i, child) ->
                walk(child, "$here › entity ${i + 1}", scripts, out, islandsOnly = islandsOnly, iframes = iframes)
            }
            return
        }
        val explicit = if (CardRouter.generatesCards(card)) card.raw.optJSONArray("entities")?.length() ?: 0 else -1
        CardRouter.producedCards(card, perEntity = false).forEach { (i, child) ->
            walk(child, "$here › card ${i + 1}", scripts, out, holdsGenerated = explicit, islandsOnly = islandsOnly, iframes = iframes)
        }
    }
}
