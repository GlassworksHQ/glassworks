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
data class CompatReport(val entries: List<CompatEntry>) {
    val islands: List<CompatEntry> get() = entries.filter { !it.verdict.isNative }
    val natives: List<CompatEntry> get() = entries.filter { it.verdict.isNative }
    val notes: List<Pair<String, String>> get() = entries.flatMap { e -> e.verdict.notes.map { e.path to it } }

    /** A plain-text report: a summary line, then every fallback with its reasons, then the known-gap notes. */
    fun format(includeNotes: Boolean = true): String = buildString {
        append("${entries.size} cards: ${natives.size} native, ${islands.size} island")
        if (islands.isEmpty()) append(" (fully native)")
        appendLine()
        for (e in islands) {
            appendLine("ISLAND ${e.path}")
            e.verdict.reasons.forEach { appendLine("  - $it") }
        }
        if (includeNotes) {
            val distinct = notes.groupBy({ it.second }, { it.first })
            if (distinct.isNotEmpty()) appendLine("Known gaps (native, not exact yet):")
            for ((note, paths) in distinct) {
                appendLine("  - $note" + if (paths.size > 1) " (${paths.size} cards)" else "")
            }
        }
    }.trimEnd()
}

/** Builds [CompatReport]s by walking a dashboard the way `VitroView` builds it. */
object CompatChecker {
    fun check(dashboard: DashboardConfig, scripts: IslandScripts = IslandScripts.ANY): CompatReport =
        CompatReport(dashboard.views.flatMap { check(it, scripts).entries })

    /**
     * [scripts]: the dashboard's Lovelace resources as an island loads them ([CardVerdict.inIsland]). Without them
     * ([IslandScripts.ANY]) the report assumes every card's script and card-mod are there.
     */
    fun check(view: ViewConfig, scripts: IslandScripts = IslandScripts.ANY): CompatReport {
        val out = ArrayList<CompatEntry>()
        val viewName = view.path ?: view.title
        for (root in CardRouter.viewRoots(view)) {
            val where = root.section?.let { "$viewName › section ${it + 1} › card ${root.index + 1}" } ?: "$viewName › card ${root.index + 1}"
            walk(root.card, where, scripts, out)
        }
        return CompatReport(out)
    }

    /** [holdsGenerated]: [card] is auto-entities' inner card under `card_param: cards`, whose `cards:` it generates. */
    private fun walk(card: CardConfig, path: String, scripts: IslandScripts, out: MutableList<CompatEntry>, holdsGenerated: Boolean = false) {
        val here = "$path (${card.type})"
        val verdict = card.compat.effective(scripts)
        out += CompatEntry(here, verdict)
        if (!verdict.isNative) return
        // auto-entities' inner card is checked with its generated cards, but those aren't listed: they depend on live
        // states. They only fill its `cards:`, so its other children (a conditional's `card:`) are listed as usual.
        if (holdsGenerated && card.type in LovelaceParser.CARDS_FIELD_TYPES) return
        val generates = CardRouter.generatesCards(card)
        CardRouter.producedCards(card, perEntity = false).forEach { (i, child) ->
            walk(child, "$here › card ${i + 1}", scripts, out, holdsGenerated = generates)
        }
    }
}
