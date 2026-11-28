/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine

/**
 * How an engine that draws the dashboard itself (Vitro) draws it now ([Engine.drawing]): the view on screen, and each
 * view of that dashboard. Settings → Labs shows it, on the panel and on the Manage page. A snapshot: the engine makes
 * a new one when the view on screen changes or is built again, and it never changes after that.
 */
class Drawing(
    /** The view on screen, or null before the engine has one up. */
    val shown: ViewDrawing?,
    views: () -> List<ViewDrawing> = { emptyList() },
) {
    /**
     * Each view of the dashboard on screen, in its order, [shown] among them; empty when the page isn't a dashboard the
     * engine draws. Worked out the first time it's read (on any thread), then kept with the snapshot.
     */
    val views: List<ViewDrawing> by lazy { runCatching { views() }.getOrDefault(emptyList()) }
}

/** One view: whether the engine draws it, and which of its cards run in browser views of their own (Vitro's islands). */
data class ViewDrawing(
    /** The view's title, or its path when it has none. */
    val name: String,
    /**
     * Why the engine leaves this page to the app's browser, which shows it as Home Assistant's frontend draws it, as the
     * part of a sentence after "Vitro doesn't draw" ("a `map` card"); null while the engine draws it.
     */
    val frontend: String? = null,
    /** The cards that run in browser views of their own, each with where it sits and why. Empty when [frontend] is set. */
    val islands: List<CardInBrowser> = emptyList(),
)

/** A card in a browser view of its own: where it sits (`home › section 2 › card 3 (custom:mushroom-media-player-card)`), and why. */
data class CardInBrowser(val where: String, val why: String)
