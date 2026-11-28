/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The `#root` of HA's grid card (`hui-grid-card`): `grid-template-columns: repeat([columns], minmax(0, 1fr))` with
 * `grid-gap: [gap]`, the cards placed in order and each stretched to its cell. A GONE card takes no cell, as a
 * `[hidden]` one doesn't in HA, so the cards after it move up.
 *
 * Rows are as tall as their tallest card. With [square] (HA's default, `square` unset or true) every row is the same
 * height (`grid-auto-rows: 1fr`), and at least as tall as a column is wide: HA's `#root::before` (`padding-bottom:
 * 100%`) sits in the first cell. So a square grid's rows are a column's width tall, or the tallest card's height when
 * a card needs more.
 *
 * With a definite height (a stretched card or a section slot), the free height is shared equally among the rows, as
 * `align-content: normal` stretches a grid's tracks.
 */
class GridCardLayout(
    context: Context,
    val columns: Int,
    val gap: Int,
    val square: Boolean,
) : ViewGroup(context) {
    // CSS `overflow: visible`, as in a grid section: a card's content may paint past its cell.
    init { clipChildren = false; clipToPadding = false }

    private var colLeft = IntArray(0)
    private var colRight = IntArray(0)
    private var rowHeight = IntArray(0)

    /** The rows' heights the last measure settled on (px), for tests. */
    internal val rowHeights: List<Int> get() = rowHeight.toList()

    private fun shown(): List<View> = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = columns.coerceAtLeast(1)
        val padX = paddingLeft + paddingRight
        val padY = paddingTop + paddingBottom
        val inner = (MeasureSpec.getSize(widthMeasureSpec) - padX).coerceAtLeast(0)
        // `minmax(0, 1fr)`: equal tracks of what the gaps leave, edges rounded so the tracks tile the width exactly.
        val track = max(0f, (inner - gap * (n - 1)).toFloat() / n)
        colLeft = IntArray(n) { (it * (track + gap)).roundToInt() }
        colRight = IntArray(n) { (it * (track + gap) + track).roundToInt() }

        val cards = shown()
        val rows = (cards.size + n - 1) / n
        val heights = IntArray(rows)
        val childHeightSpec = getChildMeasureSpec(heightMeasureSpec, padY, LayoutParams.WRAP_CONTENT)
        cards.forEachIndexed { i, v ->
            val c = i % n
            v.measure(MeasureSpec.makeMeasureSpec(colRight[c] - colLeft[c], MeasureSpec.EXACTLY), childHeightSpec)
            heights[i / n] = max(heights[i / n], v.measuredHeight)
        }
        if (square && rows > 0) {
            // `1fr` rows: all as tall as the tallest, and the `::before` makes that at least a column's width.
            val tallest = max(heights.max(), colRight[0] - colLeft[0])
            heights.fill(tallest)
        }
        var content = heights.sum() + gap * (rows - 1).coerceAtLeast(0)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val available = MeasureSpec.getSize(heightMeasureSpec) - padY
        if (heightMode == MeasureSpec.EXACTLY && rows > 0 && available > content) {
            // The free height in equal shares, the remainder to the first rows so the rows fill it exactly.
            val free = available - content
            for (r in 0 until rows) heights[r] += free / rows + (if (r < free % rows) 1 else 0)
            content = available
        }
        rowHeight = heights
        cards.forEachIndexed { i, v ->
            val c = i % n
            val w = colRight[c] - colLeft[c]
            val h = heights[i / n]
            if (v.measuredWidth != w || v.measuredHeight != h) {
                v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            }
        }
        val width = resolveSize(inner + padX, widthMeasureSpec)
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> min(content + padY, MeasureSpec.getSize(heightMeasureSpec))
            else -> content + padY
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val n = columns.coerceAtLeast(1)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val innerRight = (r - l) - paddingRight
        var top = paddingTop
        shown().forEachIndexed { i, v ->
            val row = i / n
            val c = i % n
            if (c == 0 && row > 0) top += rowHeight[row - 1] + gap
            val left = if (rtl) innerRight - colRight[c] else paddingLeft + colLeft[c]
            v.layout(left, top, left + v.measuredWidth, top + v.measuredHeight)
        }
    }
}
