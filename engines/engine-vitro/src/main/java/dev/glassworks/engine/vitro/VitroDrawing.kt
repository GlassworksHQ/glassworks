/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.CardInBrowser
import dev.glassworks.engine.Drawing
import dev.glassworks.engine.ViewDrawing
import dev.glassworks.engine.vitro.compat.BubbleFallback
import dev.glassworks.engine.vitro.compat.CompatChecker
import dev.glassworks.engine.vitro.compat.IslandBudget
import dev.glassworks.engine.vitro.compat.IslandScripts
import dev.glassworks.engine.vitro.compat.WebFallback
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.ViewConfig
import dev.glassworks.engine.vitro.ui.VitroMetrics

/**
 * What Vitro tells Settings → Labs about the page ([dev.glassworks.engine.Engine.drawing]): the view on screen as it
 * was built, with its islands, or why HA's frontend shows the page; and each view of the dashboard as its config says.
 */
internal object VitroDrawing {
    /** What a page that isn't a dashboard is called on the Labs page. */
    const val NOT_A_DASHBOARD = "Not a dashboard"
    /** Why: the part after "Vitro doesn't draw". */
    const val PAGES = "pages other than dashboards"
    /** What a dashboard Vitro leaves whole to HA's frontend is called there. */
    const val THIS_DASHBOARD = "This dashboard"

    /** A page that isn't one of the panel's Home Assistant dashboards (HA's settings, a card's link). */
    val NOT_A_DASHBOARD_PAGE = ViewDrawing(NOT_A_DASHBOARD, frontend = PAGES)

    /** Why a whole dashboard goes to HA's frontend, after "Vitro doesn't draw" (a strategy's has its own, [WebFallback.dashboardReason]). */
    const val ADMIN_ONLY = "a dashboard only administrators can open, so Home Assistant picks what this user sees"
    const val NO_CONFIG = "a dashboard Home Assistant has no saved config for"
    fun configFailed(code: String) = "a dashboard whose config Home Assistant couldn't read ($code)"

    /** [view]'s name as people know it: its title, else its path, else its place. */
    fun name(view: ViewConfig): String = view.title.ifBlank { view.path?.ifBlank { null } ?: "View ${view.index + 1}" }

    /** The view [metrics] reports as built: drawn by Vitro, with the cards that run in island pages. */
    fun built(view: ViewConfig?, metrics: VitroMetrics): ViewDrawing =
        ViewDrawing(view?.let(::name) ?: metrics.viewTitle.ifBlank { "View ${metrics.viewIndex + 1}" }, islands = metrics.islandCards)

    /**
     * [view] as its config says Vitro draws it, from the same reasons the view's build reads ([dev.glassworks.engine.vitro.ui.VitroView.fallbackReason]),
     * except what only the running dashboard knows: the rows auto-entities matches now, and a custom card an island
     * found drawing with HA's own elements. Those show once the view is open. Any thread: each answer is the config's.
     */
    fun planned(view: ViewConfig, scripts: IslandScripts, budget: Int, haOrigin: String?, gaveUp: String?): ViewDrawing {
        val frontend = view.webFallbackReason ?: gaveUp ?: WebFallback.haIframeReason(view, haOrigin)
            ?: IslandBudget.reason(view, scripts, budget) ?: BubbleFallback.reason(view, scripts)
        if (frontend != null) return ViewDrawing(name(view), frontend = frontend)
        return ViewDrawing(name(view), islands = CompatChecker.islands(view, scripts, iframes = true)
            .map { CardInBrowser(it.path, it.verdict.reasons.joinToString("; ")) })
    }

    /**
     * The snapshot: [shown] on screen, the view at [shownIndex] of [dashboard] (or -1), and every view of [dashboard]
     * worked out when the Labs page first reads them ([planned]), [shown] in its own place. No [dashboard]: no views.
     */
    fun of(
        shown: ViewDrawing?, shownIndex: Int, dashboard: DashboardConfig?,
        scripts: IslandScripts, budget: Int, haOrigin: String?, gaveUp: (ViewConfig) -> String?,
    ): Drawing = Drawing(shown) {
        dashboard?.views.orEmpty().map { v ->
            if (v.index == shownIndex && shown != null) shown else planned(v, scripts, budget, haOrigin, gaveUp(v))
        }
    }
}
