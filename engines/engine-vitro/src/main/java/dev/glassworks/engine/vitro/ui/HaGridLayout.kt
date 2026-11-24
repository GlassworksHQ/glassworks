/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import dev.glassworks.engine.vitro.model.CardConfig
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A CSS grid with `grid-auto-flow: row` as Home Assistant's sections view and grid sections use it: [columns]
 * equal columns with [colGap] / [rowGap] between them, each child spanning [LayoutParams.span] columns (it wraps
 * to the next row when it doesn't fit), rows as tall as their tallest child. With [stretch] the children of a row
 * grow to the row's height (a grid section's cards); without it they keep their own (the sections themselves).
 * GONE children take no space, so hidden cards and sections leave no gap. [maxContentWidth] caps the content box,
 * which is then centered, like the view's `max-width` with `margin: 0 auto`.
 */
class HaGridLayout(
    context: Context,
    private val columns: Int,
    private val colGap: Int,
    private val rowGap: Int,
    private val stretch: Boolean,
) : ViewGroup(context) {
    var maxContentWidth: Int = Int.MAX_VALUE

    // CSS `overflow: visible`: a card's content may paint past its cell (the clock's digits do), so cells don't clip.
    init { clipChildren = false; clipToPadding = false }

    /** [span] columns; [fixedHeight] px for a card with a fixed number of grid rows, else its own height. */
    class LayoutParams(val span: Int, val fixedHeight: Int? = null) : ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

    private var rowOf = IntArray(0)
    private var colOf = IntArray(0)
    private var rowTop = IntArray(0)
    private var rowHeight = IntArray(0)
    private var contentLeft = 0
    private var colWidth = 0f

    private fun span(child: View): Int = ((child.layoutParams as? LayoutParams)?.span ?: columns).coerceIn(1, columns)

    private fun cellWidth(col: Int, span: Int): Int {
        val left = (col * (colWidth + colGap)).roundToInt()
        val right = ((col + span) * (colWidth + colGap) - colGap).roundToInt()
        return (right - left).coerceAtLeast(0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val avail = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val content = min(avail, maxContentWidth)
        contentLeft = paddingLeft + (avail - content) / 2
        colWidth = ((content - (columns - 1) * colGap).coerceAtLeast(0)).toFloat() / columns
        val n = childCount
        rowOf = IntArray(n) { -1 }
        colOf = IntArray(n)
        val heights = ArrayList<Int>()
        var row = -1
        var col = columns
        for (i in 0 until n) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val s = span(child)
            if (col + s > columns) { row++; col = 0; heights += 0 }
            rowOf[i] = row
            colOf[i] = col
            col += s
            val lp = child.layoutParams as? LayoutParams
            val hs = lp?.fixedHeight?.let { MeasureSpec.makeMeasureSpec(it, MeasureSpec.EXACTLY) }
                ?: MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            child.measure(MeasureSpec.makeMeasureSpec(cellWidth(colOf[i], s), MeasureSpec.EXACTLY), hs)
            heights[row] = max(heights[row], child.measuredHeight)
        }
        rowHeight = heights.toIntArray()
        if (stretch) {
            for (i in 0 until n) {
                val child = getChildAt(i)
                if (rowOf[i] < 0 || (child.layoutParams as? LayoutParams)?.fixedHeight != null) continue
                if (child.measuredHeight < rowHeight[rowOf[i]]) {
                    child.measure(
                        MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(rowHeight[rowOf[i]], MeasureSpec.EXACTLY),
                    )
                }
            }
        }
        rowTop = IntArray(rowHeight.size)
        var y = paddingTop
        for (r in rowHeight.indices) {
            rowTop[r] = y
            y += rowHeight[r] + if (r < rowHeight.lastIndex) rowGap else 0
        }
        setMeasuredDimension(width, y + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (i >= rowOf.size || rowOf[i] < 0 || child.visibility == GONE) continue
            val x = contentLeft + (colOf[i] * (colWidth + colGap)).roundToInt()
            val y = rowTop[rowOf[i]]
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LayoutParams(columns)
    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = LayoutParams(columns)
}

/**
 * The size of a card in a grid section, as HA computes it (`hui-card.getGridOptions()`): the card's own
 * `grid_options` (or the old `layout_options`) over the defaults its type reports. [columns] is in 12ths of a
 * section column, null for `"full"`; [rows] is a fixed number of 56 px rows, or null for `auto`.
 */
data class HaGridSize(val columns: Int?, val rows: Int?) {
    companion object {
        fun of(card: CardConfig): HaGridSize {
            val raw = card.raw
            val default = defaults(card)
            var cols: Any? = default.columns ?: "full"
            var rows: Any? = default.rows ?: "auto"
            var minCols: Int? = null
            var maxCols: Int? = null
            var minRows: Int? = null
            var maxRows: Int? = null
            val g = raw.optJSONObject("grid_options")
            val legacy = raw.optJSONObject("layout_options")
            if (g != null) {
                if (g.has("columns")) cols = g.opt("columns")
                if (g.has("rows")) rows = g.opt("rows")
                minCols = g.optInt("min_columns", 0).takeIf { it > 0 }
                maxCols = g.optInt("max_columns", 0).takeIf { it > 0 }
                minRows = g.optInt("min_rows", 0).takeIf { it > 0 }
                maxRows = g.optInt("max_rows", 0).takeIf { it > 0 }
            } else if (legacy != null) {
                if (legacy.has("grid_columns")) cols = legacy.opt("grid_columns").let { (it as? Number)?.toInt()?.times(3) ?: it }
                if (legacy.has("grid_rows")) rows = legacy.opt("grid_rows")
            }
            val c = when (cols) {
                is Number -> cols.toInt().let { v -> v.coerceAtLeast(minCols ?: 1).let { maxCols?.let { m -> minOf(it, m) } ?: it } }.coerceIn(1, 48)
                else -> null
            }
            val r = when (rows) {
                is Number -> rows.toInt().let { v -> v.coerceAtLeast(minRows ?: 1).let { maxRows?.let { m -> minOf(it, m) } ?: it } }
                else -> null
            }
            return HaGridSize(c, r?.takeIf { it > 0 })
        }

        /** What each card type reports from `getGridOptions()` (HA 2026.11, Mushroom 5). */
        fun defaults(card: CardConfig): HaGridSize {
            val raw = card.raw
            return when (card.type) {
                "clock" -> {
                    val size = raw.optString("clock_size", "small")
                    if (raw.optString("clock_style") == "analog") {
                        HaGridSize(6, when (size) { "medium" -> 3; "large" -> 4; else -> 2 })
                    } else {
                        HaGridSize(6, when (size) { "medium", "large" -> 2; else -> 1 })
                    }
                }
                "tile" -> {
                    val features = raw.optJSONArray("features")?.length() ?: 0
                    val vertical = raw.optBoolean("vertical", false)
                    // Inline features sit beside the info, so they add no rows (HA shows only the first one).
                    val inline = features > 0 && !vertical && raw.optString("features_position") == "inline"
                    HaGridSize(if (inline) 12 else 6, 1 + (if (inline) 0 else features) + if (vertical) 1 else 0)
                }
                // hui-entity-card's (and so hui-sensor-card's) getGridOptions: 6 columns, 2 rows.
                "entity", "sensor" -> HaGridSize(6, 2)
                "light", "area" -> HaGridSize(6, null)
                // hui-button-card's getGridOptions: 6 columns, 2 rows.
                "button" -> HaGridSize(6, 2)
                // Measured in HA: the picture cards span the section's full width at their image's height.
                "picture", "picture-entity", "picture-glance" -> HaGridSize(12, null)
                "heading" -> HaGridSize(null, null)
                // Measured in HA: with show_forecast: false the weather card is 2 rows (120 px), its conditions centered.
                "weather-forecast" -> HaGridSize(12, if (raw.optBoolean("show_forecast", true)) null else 2)
                "custom:mushroom-template-card" -> {
                    val hasContent = listOf("icon", "picture", "primary", "secondary").any { raw.optString(it).isNotBlank() }
                    val vertical = raw.optBoolean("vertical", false) || raw.optString("layout") == "vertical"
                    if (raw.has("multiline_secondary") && raw.optBoolean("multiline_secondary")) HaGridSize(6, null)
                    else HaGridSize(6, ((if (hasContent) 1 else 0) + if (vertical) 1 else 0).takeIf { it > 0 })
                }
                "custom:mushroom-entity-card", "custom:mushroom-light-card", "custom:mushroom-fan-card",
                "custom:mushroom-cover-card", "custom:mushroom-person-card", "custom:mushroom-lock-card",
                "custom:mushroom-media-player-card", "custom:mushroom-climate-card", "custom:mushroom-vacuum-card",
                "custom:mushroom-humidifier-card", "custom:mushroom-number-card", "custom:mushroom-select-card",
                "custom:mushroom-alarm-control-panel-card" -> when (raw.optString("layout")) {
                    "vertical" -> HaGridSize(6, 2)
                    "horizontal" -> HaGridSize(12, 1)
                    else -> HaGridSize(6, 1)
                }
                else -> HaGridSize(12, null)
            }
        }
    }
}
