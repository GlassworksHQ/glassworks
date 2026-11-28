/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.HaCardSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * HA's masonry view (`hui-masonry-view.ts`, frontend 20261028.8), the parts that decide where each card goes:
 * - **Columns:** one per `(min-width: 300px)`, `600`, `900` and `1200` query the screen matches, at least one (the
 *   kiosk has no docked sidebar to take one away), and never more than there are cards. `max_columns` is a sections
 *   view option: the masonry view doesn't read it.
 * - **Placement:** card by card, into the first column whose `getCardSize` total is under 5, else the smallest one
 *   ([HaCardSize]). Columns left empty are dropped, so a few small cards share one column.
 */
object HaMasonry {
    /** The column count for a view [widthDp] wide. */
    fun columns(widthDp: Int): Int = max(1, listOf(300, 600, 900, 1200).count { widthDp >= it })

    /** Each card's column (an index into the columns that keep a card), and how many columns that is. */
    fun place(cards: List<CardConfig>, widthDp: Int, state: (CardConfig) -> EntityState? = { null }): Pair<IntArray, Int> {
        val n = min(columns(widthDp), cards.size)
        if (n == 0) return IntArray(0) to 0
        val sizes = DoubleArray(n)
        val raw = IntArray(cards.size)
        cards.forEachIndexed { i, c ->
            var pick = 0
            for (k in 0 until n) {
                if (sizes[k] < 5) { pick = k; break }
                if (sizes[k] < sizes[pick]) pick = k
            }
            sizes[pick] += HaCardSize.of(c, state)
            raw[i] = pick
        }
        // Empty columns go; the rest keep their order.
        val used = raw.distinct().sorted()
        val remap = used.withIndex().associate { (newIndex, old) -> old to newIndex }
        return IntArray(cards.size) { remap.getValue(raw[it]) } to used.size
    }
}

/**
 * HA's cards whose own `ha-card` style is `height: 100%` (frontend 20261028.5: each card module's styles, the tile's in
 * `tile-card-style.ts`; the sensor card extends the entity card's). Area, heading and the sankey cards aren't; a custom
 * card's is its own and unknown, so it keeps its height. A card pinned with card-mod's `position: fixed` resolves that
 * 100% against the viewport ([VitroView.pinnedLayoutParams]).
 */
internal val HA_CARD_FULL_HEIGHT = setOf(
    "alarm-panel", "button", "entity-button", "calendar", "clock", "entities", "entity", "gauge", "glance", "history-graph",
    "humidifier", "iframe", "light", "logbook", "map", "markdown", "media-control", "picture", "picture-elements",
    "picture-entity", "picture-glance", "plant-status", "sensor", "statistic", "statistics-graph", "thermostat",
    "tile", "todo-list", "weather-forecast",
)

/**
 * The masonry view's `#columns`: a row of `flex: 1 0 0` columns, each at most [maxColumnPx] wide (500 px, 600 under a
 * 600 px screen), centered.
 */
internal class MasonryColumnsLayout(context: Context, private val maxColumnPx: Int) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && childCount > 0) {
            // `.column:not(:has(> *:not([hidden]))) { flex-grow: 0 }`: a column whose cards are all hidden takes no
            // room, and the others share it; a card shown again (a visibility change lays this out again) brings its
            // column back.
            fun hasShown(c: View) = c is ViewGroup && (0 until c.childCount).any { c.getChildAt(it).visibility != GONE }
            val shown = (0 until childCount).count { hasShown(getChildAt(it)) }
            val inner = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val w = if (shown == 0) 0 else min(maxColumnPx, inner / shown).coerceAtLeast(0)
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val lp = c.layoutParams as LayoutParams
                val want = if (hasShown(c)) w else 0
                if (lp.width != want || lp.weight != 0f) { lp.width = want; lp.weight = 0f }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/**
 * HA's sidebar view (`hui-sidebar-view.ts`): from 760 px up, `#main` (`flex-grow: 2`, at most 1620 px) and `#sidebar`
 * (`flex-grow: 1; flex-shrink: 0`, at most 380 px) side by side, centered; each starts at its content's width (CSS's
 * `flex-basis: auto`, the widest a card's content asks for), and the free room is shared 2 : 1, or taken from the
 * main column when there's too little. Narrower, one column with every card in config order.
 */
internal class SidebarColumnsLayout(context: Context, private val main: View, private val sidebar: View) : ViewGroup(context) {
    init {
        addView(main)
        addView(sidebar)
    }

    /**
     * The two widths for [inner] px of room: CSS flexbox with these two items ([widths] is pure for tests). Each
     * column's content width is what it takes when it may take all of [inner] (CSS's max-content, capped at the room:
     * past it, the flexbox ends the same way).
     */
    private fun split(inner: Int): Pair<Int, Int> {
        val room = MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST)
        val anyH = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        main.measure(room, anyH)
        sidebar.measure(room, anyH)
        val maxMain = (1620 * resources.displayMetrics.density).roundToInt()
        val maxSide = (380 * resources.displayMetrics.density).roundToInt()
        return widths(inner, main.measuredWidth, sidebar.measuredWidth, maxMain, maxSide)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val total = MeasureSpec.getSize(widthMeasureSpec)
        val inner = (total - paddingLeft - paddingRight).coerceAtLeast(0)
        val (mw, sw) = split(inner)
        val anyH = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        main.measure(MeasureSpec.makeMeasureSpec(mw, MeasureSpec.EXACTLY), anyH)
        sidebar.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY), anyH)
        setMeasuredDimension(total, paddingTop + paddingBottom + max(main.measuredHeight, sidebar.measuredHeight))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inner = r - l - paddingLeft - paddingRight
        // `justify-content: center`.
        val x0 = paddingLeft + ((inner - main.measuredWidth - sidebar.measuredWidth) / 2).coerceAtLeast(0)
        main.layout(x0, paddingTop, x0 + main.measuredWidth, paddingTop + main.measuredHeight)
        val sx = x0 + main.measuredWidth
        sidebar.layout(sx, paddingTop, sx + sidebar.measuredWidth, paddingTop + sidebar.measuredHeight)
    }

    companion object {
        /**
         * Flexbox for `#main` (grow 2, shrink 1, `min-width: 0`) and `#sidebar` (grow 1, shrink 0) in [inner] px, from
         * their content widths and maximums: (main, sidebar).
         */
        fun widths(inner: Int, mainContent: Int, sideContent: Int, maxMain: Int, maxSide: Int): Pair<Int, Int> {
            // Hypothetical sizes: the bases clamped by max-width.
            var m = min(mainContent, maxMain).toDouble()
            var s = min(sideContent, maxSide).toDouble()
            val free = inner - m - s
            if (free >= 0) {
                // Grow 2 : 1, a frozen item's share going to the other.
                var mg = m + free * 2 / 3
                var sg = s + free / 3
                if (sg > maxSide) { mg += sg - maxSide; sg = maxSide.toDouble() }
                if (mg > maxMain) { sg = min(maxSide.toDouble(), sg + (mg - maxMain)); mg = maxMain.toDouble() }
                m = mg
                s = sg
            } else {
                // Only the main column shrinks (`flex-shrink: 0` on the sidebar), down to 0.
                m = max(0.0, m + free)
            }
            return m.roundToInt() to s.roundToInt()
        }
    }
}

/** HA's sidebar view under 760 px: one column, every card in config order (the parity harness finds it by class). */
internal class SidebarStackLayout(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
    }
}

/**
 * A masonry or sidebar column: the cards in order, their block margins collapsing as CSS collapses them. The first card
 * it shows gets [firstTopPx] above it, every later shown one [restTopPx] (the bottom margin above already spaces it);
 * a hidden card (GONE) takes no margin, so the card after it can be the first.
 *
 * A column never limits its cards' heights: in HA each card is a block as tall as its content, and the column as tall
 * as its cards. The view's scroller fills the screen (`fillViewport`), so a short view hands the row an exact height,
 * and a column measured within it would hand each card the room left (`AT_MOST`): a card whose background matches its
 * parent (media control's) would stretch to the bottom of the screen and push the cards under it out. The column
 * measures its cards unbounded instead.
 */
internal class CardColumn(context: Context, private val firstTopPx: Int, private val restTopPx: Int) : LinearLayout(context) {
    init {
        orientation = VERTICAL
    }

    /** The view's culling ([ViewportCull], ScrollTuning.Knob.CULL), once it found this column; null: every card drawn. */
    internal var cull: ViewportCull? = null

    override fun drawChild(canvas: android.graphics.Canvas, child: View, drawingTime: Long): Boolean {
        if (cull?.skips(child) == true) return false
        return super.drawChild(canvas, child, drawingTime)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var first = true
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val lp = c.layoutParams as? MarginLayoutParams ?: continue
            val want = if (first) firstTopPx else restTopPx
            if (lp.topMargin != want) lp.topMargin = want
            first = false
        }
        super.onMeasure(widthMeasureSpec, unbounded)
    }
}
