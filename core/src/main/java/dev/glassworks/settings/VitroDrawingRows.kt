/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.settings

import dev.glassworks.engine.Drawing
import dev.glassworks.engine.ViewDrawing
import dev.glassworks.pane.SettingsItem

/**
 * Settings → Labs while Vitro draws the dashboard: how it draws the view on screen (natively, with cards in browser views
 * of their own, or left to Home Assistant's frontend), each of those cards with where it sits and why, and how it draws
 * each view of the dashboard. The panel's menu and the Manage page both show it, and both read it again while the page
 * is open, so it follows the view on screen. Nothing while Vitro is off ([Drawing] is null then).
 */
object VitroDrawingRows {
    const val SHOWN_TITLE = "On screen now"
    const val VIEWS_TITLE = "Each view"
    const val NOTHING_YET = "Nothing on screen yet"
    const val NOTHING_YET_DETAIL = "Once Vitro shows a view, this says how it draws it."
    const val NATIVE = "Native"
    const val FRONTEND = "Home Assistant's frontend"

    /**
     * The rows for what [drawing] says now, or none while Vitro is off. Which rows there are follows the drawing when they
     * are made; each row reads [drawing] again whenever a surface reads it (the panel's open menu every couple of
     * seconds), so a row never shows a view that's gone. A new count of rows makes the page draw itself again.
     */
    fun items(drawing: () -> Drawing?): List<SettingsItem> {
        fun now() = runCatching(drawing).getOrNull()
        val first = now() ?: return emptyList()
        fun shown() = now()?.shown
        val head = SettingsItem.Info(title = { shown()?.name ?: NOTHING_YET }, detail = { shown()?.let(::detail) ?: NOTHING_YET_DETAIL })
        val cards = first.shown?.islands.orEmpty().indices.map { i ->
            SettingsItem.Info(title = { shown()?.islands?.getOrNull(i)?.where.orEmpty() }, detail = { shown()?.islands?.getOrNull(i)?.why.orEmpty() })
        }
        return listOfNotNull(
            SettingsItem.Group(SHOWN_TITLE, listOf(head) + cards),
            // One view is the one on screen: listed once.
            if (first.views.size < 2) null
            else SettingsItem.States(VIEWS_TITLE) { now()?.views.orEmpty().map { SettingsItem.StateRow(it.name, summary(it)) } },
        )
    }

    /** A view in a few words: "Native", "2 browser views" or "Home Assistant's frontend". */
    fun summary(view: ViewDrawing): String = when {
        view.frontend != null -> FRONTEND
        view.islands.isEmpty() -> NATIVE
        else -> browserViews(view.islands.size)
    }

    /** The line under the view on screen: who draws it, and why when it isn't Vitro. */
    fun detail(view: ViewDrawing): String = when {
        view.frontend != null -> "$FRONTEND shows it, because Vitro doesn't draw ${view.frontend}."
        view.islands.isEmpty() -> "Vitro draws every card natively."
        view.islands.size == 1 -> "Vitro draws it natively, with 1 card in a browser view of its own."
        else -> "Vitro draws it natively, with ${view.islands.size} cards in browser views of their own."
    }

    /** The view on screen in the Manage page's Engine tile, short enough for its one line. */
    fun tile(view: ViewDrawing): String = when {
        view.frontend != null -> TILE_FRONTEND
        view.islands.isEmpty() -> TILE_NATIVE
        else -> browserViews(view.islands.size)
    }
    const val TILE_NATIVE = "All native"
    const val TILE_FRONTEND = "HA's frontend"

    fun browserViews(n: Int) = if (n == 1) "1 browser view" else "$n browser views"

    /** Whether [items] has the rows: a page that does changes by itself, so the Manage page reloads it. */
    fun shownIn(items: List<SettingsItem>): Boolean = items.any { it is SettingsItem.Group && it.title == SHOWN_TITLE }
}
