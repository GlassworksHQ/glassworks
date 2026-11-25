/* SPDX-License-Identifier: MIT
 *
 * Ported from button-card: custom-cards/button-card, tag v7.0.1. Copyright (c) 2018, Alexandre Garcia.
 * The licence text is in THIRD-PARTY-NOTICES.md and LICENSES/MIT.txt.
 * Changes: rewritten in Kotlin for Vitro's native views; its stylesheet as cascade rules and a small CSS grid layout. */

package dev.glassworks.engine.vitro.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * button-card's stylesheet for its grid (src/styles.ts, v7.0.1): each `layout` and its `no-icon`, `no-name`,
 * `no-state` and `no-label` variants, as rules with their CSS specificity, resolved the way the browser cascades them.
 */
internal object ButtonCardSheet {
    data class Resolved(
        val areas: List<List<String>>,
        val columns: List<CssGridSpec.Track>,
        val rows: List<CssGridSpec.Track>,
        /** Per element (`img-cell`, `name`, `state`, `label`): its declarations from the sheet. */
        val items: Map<String, Map<String, String>>,
    )

    /** One rule: the container classes it needs (and the ones it needs absent), what it styles, and its specificity. */
    private class Rule(
        val classes: Set<String>,
        val not: Set<String> = emptySet(),
        val target: String = "container",
        val ids: Int = 1,
        val decls: Map<String, String>,
    ) {
        val specificity: Int get() = ids * 100 + classes.size + not.size
    }

    private fun c(vararg classes: String, not: Set<String> = emptySet(), decls: Map<String, String>) = Rule(classes.toSet(), not, decls = decls)
    private fun item(target: String, vararg classes: String, not: Set<String> = emptySet(), decls: Map<String, String>) =
        Rule(classes.toSet(), not, target, ids = 2, decls = decls)

    private fun grid(areas: String, cols: String, rows: String) = mapOf("areas" to areas, "cols" to cols, "rows" to rows)
    private fun align(a: String) = mapOf("align-self" to a)

    private val RULES: List<Rule> = listOf(
        Rule(emptySet(), target = "img-cell", decls = mapOf("area" to "i", "width" to "100%", "height" to "100%",
            "align-self" to "center", "justify-self" to "center")),
        Rule(emptySet(), target = "name", decls = mapOf("area" to "n", "max-width" to "100%", "align-self" to "center", "justify-self" to "center")),
        Rule(emptySet(), target = "state", decls = mapOf("area" to "s", "max-width" to "100%", "align-self" to "center", "justify-self" to "center")),
        Rule(emptySet(), target = "label", decls = mapOf("area" to "l", "max-width" to "100%", "align-self" to "center", "justify-self" to "center")),
        c("vertical", decls = grid("'i' 'n' 's' 'l'", "1fr", "1fr min-content min-content min-content")),
        c("vertical", "section", decls = mapOf("rows" to "auto min-content min-content min-content")),
        item("name", "vertical", "section", not = setOf("no-icon"), decls = mapOf("margin-top" to "8px")),
        c("vertical", "no-icon", decls = grid("'n' 's' 'l'", "1fr", "1fr min-content 1fr")),
        item("state", "vertical", "no-icon", decls = align("center")),
        item("name", "vertical", "no-icon", decls = align("end")),
        item("label", "vertical", "no-icon", decls = align("start")),
        c("vertical", "no-icon", "no-name", decls = grid("'s' 'l'", "1fr", "1fr 1fr")),
        item("state", "vertical", "no-icon", "no-name", decls = align("end")),
        item("label", "vertical", "no-icon", "no-name", decls = align("start")),
        c("vertical", "no-icon", "no-state", decls = grid("'n' 'l'", "1fr", "1fr 1fr")),
        item("name", "vertical", "no-icon", "no-state", decls = align("end")),
        item("label", "vertical", "no-icon", "no-state", decls = align("start")),
        c("vertical", "no-icon", "no-label", decls = grid("'n' 's'", "1fr", "1fr 1fr")),
        item("name", "vertical", "no-icon", "no-label", decls = align("end")),
        item("state", "vertical", "no-icon", "no-label", decls = align("start")),
        c("vertical", "no-icon", "no-label", "no-name", decls = grid("'s'", "1fr", "1fr")),
        item("state", "vertical", "no-icon", "no-label", "no-name", decls = align("center")),
        c("vertical", "no-icon", "no-label", "no-state", decls = grid("'n'", "1fr", "1fr")),
        item("name", "vertical", "no-icon", "no-label", "no-state", decls = align("center")),
        c("vertical", "no-icon", "no-name", "no-state", decls = grid("'l'", "1fr", "1fr")),
        item("label", "vertical", "no-icon", "no-name", "no-state", decls = align("center")),
        c("icon_name_state", decls = grid("'i n' 'l l'", "40% 1fr", "1fr min-content")),
        c("icon_name", decls = grid("'i n' 's s' 'l l'", "40% 1fr", "1fr min-content min-content")),
        c("icon_name", "section", decls = mapOf("rows" to "auto min-content min-content")),
        c("icon_state", decls = grid("'i s' 'n n' 'l l'", "40% 1fr", "1fr min-content min-content")),
        c("icon_state", "section", decls = mapOf("rows" to "auto min-content min-content")),
        c("name_state", decls = grid("'i' 'n' 'l'", "1fr", "1fr min-content min-content")),
        c("name_state", "section", decls = mapOf("rows" to "auto min-content min-content")),
        c("name_state", "no-icon", decls = grid("'n' 'l'", "1fr", "1fr 1fr")),
        item("name", "name_state", "no-icon", decls = align("end")),
        item("name", "name_state", "section", not = setOf("no-icon"), decls = mapOf("margin-top" to "8px")),
        item("label", "name_state", "no-icon", decls = align("start")),
        c("name_state", "no-icon", "no-label", decls = grid("'n'", "1fr", "1fr")),
        item("name", "name_state", "no-icon", "no-label", decls = align("center")),
        c("icon_name_state2nd", decls = grid("'i n' 'i s' 'i l'", "40% 1fr", "1fr min-content 1fr")),
        item("name", "icon_name_state2nd", decls = align("end")),
        item("state", "icon_name_state2nd", decls = align("center")),
        item("label", "icon_name_state2nd", decls = align("start")),
        c("icon_name_state2nd", "no-label", decls = grid("'i n' 'i s'", "40% 1fr", "1fr 1fr")),
        item("name", "icon_name_state2nd", decls = align("end")),
        item("state", "icon_name_state2nd", decls = align("start")),
        c("icon_state_name2nd", decls = grid("'i s' 'i n' 'i l'", "40% 1fr", "1fr min-content 1fr")),
        item("state", "icon_state_name2nd", decls = align("end")),
        item("name", "icon_state_name2nd", decls = align("center")),
        item("label", "icon_state_name2nd", decls = align("start")),
        c("icon_state_name2nd", "no-label", decls = grid("'i s' 'i n'", "40% 1fr", "1fr 1fr")),
        item("state", "icon_state_name2nd", decls = align("end")),
        item("name", "icon_state_name2nd", decls = align("start")),
        c("icon_label", decls = grid("'i l' 'n n' 's s'", "40% 1fr", "1fr min-content min-content")),
    )

    private val cache = HashMap<Set<String>, Resolved>()

    /** The sheet for a container with [classes] (its layout, `no-*` and `section`). */
    fun resolve(classes: Set<String>): Resolved = synchronized(cache) {
        cache.getOrPut(classes) {
            val matching = RULES.withIndex()
                .filter { (_, r) -> classes.containsAll(r.classes) && r.not.none { it in classes } }
                .sortedWith(compareBy({ it.value.specificity }, { it.index }))
            val container = HashMap<String, String>()
            val items = HashMap<String, HashMap<String, String>>()
            for ((_, r) in matching) {
                if (r.target == "container") container.putAll(r.decls) else items.getOrPut(r.target) { HashMap() }.putAll(r.decls)
            }
            // A layout button-card doesn't know gets no template: one auto-placed column (CardCompat sends it to an island).
            Resolved(
                areas = container["areas"]?.let { CssGridSpec.areas(it) } ?: listOf(listOf("i"), listOf("n"), listOf("s"), listOf("l")),
                columns = CssGridSpec.tracks(container["cols"] ?: "1fr")!!,
                rows = CssGridSpec.tracks(container["rows"] ?: "auto auto auto auto")!!,
                items = items,
            )
        }
    }
}

/**
 * A CSS grid with named areas, as button-card's `#container` lays out its parts: tracks in px or % of the container,
 * `fr` and content-sized (`auto`, `min-content`), the browser's sizing of flexible rows when the height is open
 * (every `fr` row grows to the largest share its items need, spanning items included), and each item's
 * `align-self` and `justify-self` in its area.
 */
internal class ButtonGridLayout(context: Context) : ViewGroup(context) {
    class Params(var area: String, val isField: Boolean = false) : ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT) {
        var alignSelf: String? = null
        var justifySelf: String? = null
        var cssWidth: ButtonCardCss.Length? = null
        var cssHeight: ButtonCardCss.Length? = null
        var maxWidth: ButtonCardCss.Length? = null
        var minWidth: ButtonCardCss.Length? = null
        var marginTop: ButtonCardCss.Length? = null
        var margins: List<ButtonCardCss.Length> = emptyList()
        var fontPx: Float = 16.8f
        var positionAbsolute = false
        var insets: List<ButtonCardCss.Length?> = listOf(null, null, null, null)
        var isText = false

        // Results of the last measure.
        internal var r0 = 0; internal var r1 = 1; internal var c0 = 0; internal var c1 = 1
        internal var x = 0; internal var y = 0
    }

    private var areas: List<List<String>> = listOf(listOf("i"))
    private var cols: List<CssGridSpec.Track> = listOf(CssGridSpec.Track(fr = 1f))
    private var rows: List<CssGridSpec.Track> = listOf(CssGridSpec.Track(fr = 1f))
    private var rowGap: ButtonCardCss.Length? = null
    private var colGap: ButtonCardCss.Length? = null
    private var alignItems = "center"
    private var justifyItems: String? = null
    private var alignContentCenter = false
    private val dp = context.resources.displayMetrics.density

    /** Sizes of the last measure, px. */
    private var colSizes = IntArray(0)
    private var rowSizes = IntArray(0)
    private var contentTop = 0

    fun spec(
        areas: List<List<String>>,
        cols: List<CssGridSpec.Track>,
        rows: List<CssGridSpec.Track>,
        rowGap: ButtonCardCss.Length?,
        colGap: ButtonCardCss.Length?,
        alignItems: String,
        justifyItems: String?,
        alignContentCenter: Boolean,
    ) {
        this.areas = areas
        this.cols = cols
        this.rows = rows
        this.rowGap = rowGap
        this.colGap = colGap
        this.alignItems = alignItems
        this.justifyItems = justifyItems
        this.alignContentCenter = alignContentCenter
        requestLayout()
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is Params

    private fun px(cssPx: Float): Int = (cssPx * dp).roundToInt()

    /** Explicit tracks extended to the template's size; an area outside it goes into one auto row at the end. */
    private fun place(p: Params, nCols: Int, nRows: Int): Boolean {
        var r0 = Int.MAX_VALUE; var r1 = -1; var c0 = Int.MAX_VALUE; var c1 = -1
        areas.forEachIndexed { r, row -> row.forEachIndexed { c, n -> if (n == p.area) { r0 = min(r0, r); r1 = max(r1, r); c0 = min(c0, c); c1 = max(c1, c) } } }
        if (r1 < 0) {
            p.r0 = nRows; p.r1 = nRows + 1; p.c0 = 0; p.c1 = nCols
            return false
        }
        p.r0 = r0; p.r1 = r1 + 1; p.c0 = c0; p.c1 = c1 + 1
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val definiteH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else null
        val wCss = w / dp
        val nCols = max(cols.size, areas.firstOrNull()?.size ?: 1)
        val nExplicitRows = max(rows.size, areas.size)
        val items = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
        var implicitRow = false
        for (v in items) if (!place(v.layoutParams as Params, nCols, nExplicitRows) && !(v.layoutParams as Params).positionAbsolute) implicitRow = true
        val nRows = nExplicitRows + if (implicitRow) 1 else 0
        val cTracks = List(nCols) { cols.getOrElse(it) { CssGridSpec.Track(content = "auto") } }
        val rTracks = List(nRows) { rows.getOrElse(it) { CssGridSpec.Track(content = "auto") } }
        val cg = colGap?.let { px(it.px(wCss)) } ?: 0
        val rg = rowGap?.let { px(it.px(definiteH?.let { h -> h / dp } ?: 0f)) } ?: 0
        val flow = items.filter { !(it.layoutParams as Params).positionAbsolute }

        // Columns: fixed, then content, then the free space to fr tracks (or else to auto tracks).
        val cs = FloatArray(nCols)
        cTracks.forEachIndexed { i, t -> t.fixed?.let { cs[i] = px(it.px(wCss)).toFloat() } }
        for (v in flow) {
            val p = v.layoutParams as Params
            if (p.c1 - p.c0 != 1) continue
            val t = cTracks[p.c0]
            if (t.content != null) cs[p.c0] = max(cs[p.c0], maxContentWidth(v, p, wCss).toFloat())
        }
        distribute(cs, cTracks, w - cs.sum() - cg * (nCols - 1))
        colSizes = IntArray(nCols) { cs[it].roundToInt() }

        // Each item's width in its area, then its height there.
        val heights = HashMap<View, Int>()
        for (v in flow) {
            val p = v.layoutParams as Params
            val areaW = span(colSizes, p.c0, p.c1, cg)
            val iw = itemWidth(v, p, areaW, wCss)
            heights[v] = contentHeight(v, p, iw, areaW)
        }

        // Rows.
        val rs = FloatArray(nRows)
        val hCss = definiteH?.let { it / dp }
        rTracks.forEachIndexed { i, t ->
            val f = t.fixed ?: return@forEachIndexed
            if (f.unit == "%" && hCss == null) return@forEachIndexed
            rs[i] = px(f.px(hCss ?: 0f)).toFloat()
        }
        fun isContent(i: Int) = rTracks[i].content != null || (rTracks[i].fixed?.unit == "%" && hCss == null)
        for (v in flow) {
            val p = v.layoutParams as Params
            if (p.r1 - p.r0 == 1 && isContent(p.r0)) rs[p.r0] = max(rs[p.r0], heights[v]!!.toFloat())
        }
        // Spanning items over content rows only: the excess goes evenly to those rows.
        for (v in flow) {
            val p = v.layoutParams as Params
            if (p.r1 - p.r0 < 2 || (p.r0 until p.r1).any { rTracks[it].fr > 0f }) continue
            val have = (p.r0 until p.r1).sumOf { rs[it].toDouble() }.toFloat() + rg * (p.r1 - p.r0 - 1)
            val excess = heights[v]!! - have
            val content = (p.r0 until p.r1).filter { isContent(it) }
            if (excess > 0 && content.isNotEmpty()) content.forEach { rs[it] += excess / content.size }
        }
        val frSum = rTracks.sumOf { it.fr.toDouble() }.toFloat()
        if (definiteH != null) {
            distribute(rs, rTracks, definiteH - rs.sum() - rg * (nRows - 1))
        } else if (frSum > 0f) {
            // The browser's "find the size of an fr" with an open height: the largest share any item needs.
            var unit = 0f
            for (v in flow) {
                val p = v.layoutParams as Params
                val spanned = (p.r0 until p.r1)
                val flex = spanned.sumOf { rTracks[it].fr.toDouble() }.toFloat()
                if (flex <= 0f) continue
                val fixedPart = spanned.filter { rTracks[it].fr == 0f }.sumOf { rs[it].toDouble() }.toFloat() + rg * (spanned.count() - 1)
                unit = max(unit, (heights[v]!! - fixedPart) / max(flex, 1f))
            }
            rTracks.forEachIndexed { i, t -> if (t.fr > 0f) rs[i] = unit * t.fr }
        }
        rowSizes = IntArray(nRows) { rs[it].roundToInt() }
        val total = rowSizes.sum() + rg * (nRows - 1).coerceAtLeast(0)
        val h = definiteH ?: total
        contentTop = if (alignContentCenter && definiteH != null) ((definiteH - total) / 2).coerceAtLeast(0) else 0

        // Final sizes and positions.
        for (v in flow) {
            val p = v.layoutParams as Params
            val areaW = span(colSizes, p.c0, p.c1, cg)
            val areaH = span(rowSizes, p.r0, p.r1, rg)
            val m = marginsPx(p, wCss)
            val iw = itemWidth(v, p, areaW, wCss)
            val align = p.alignSelf?.takeIf { it != "auto" && it != "normal" } ?: alignItems
            val ih = when {
                p.cssHeight != null -> px(p.cssHeight!!.px(areaH / dp, p.fontPx))
                !p.isText && !p.isField && p.area == "i" -> areaH - m[0] - m[2]
                align == "stretch" || align == "normal" -> areaH - m[0] - m[2]
                else -> heights[v]!! - m[0] - m[2]
            }.coerceAtLeast(0)
            v.measure(MeasureSpec.makeMeasureSpec(iw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ih, MeasureSpec.EXACTLY))
            val ax = span(colSizes, 0, p.c0, cg) + if (p.c0 > 0) cg else 0
            val ay = contentTop + span(rowSizes, 0, p.r0, rg) + if (p.r0 > 0) rg else 0
            val justify = p.justifySelf?.takeIf { it != "auto" } ?: justifyItems ?: "stretch"
            p.x = ax + m[3] + when (justify) {
                "start", "left", "self-start", "flex-start" -> 0
                "end", "right", "self-end", "flex-end" -> areaW - m[1] - m[3] - iw
                "center" -> (areaW - m[1] - m[3] - iw) / 2
                else -> 0
            }
            p.y = ay + m[0] + when (align) {
                "start", "self-start", "flex-start", "baseline" -> 0
                "end", "self-end", "flex-end" -> areaH - m[0] - m[2] - ih
                "center" -> (areaH - m[0] - m[2] - ih) / 2
                else -> 0
            }
        }
        // Absolutely positioned custom fields sit in the card, not the grid: the card places them (relative to this box).
        for (v in items.filter { (it.layoutParams as Params).positionAbsolute }) {
            val p = v.layoutParams as Params
            v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val (t, r, b, l) = p.insets
            p.x = when {
                l != null -> px(l.px(wCss, p.fontPx))
                r != null -> w - px(r.px(wCss, p.fontPx)) - v.measuredWidth
                else -> 0
            }
            p.y = when {
                t != null -> px(t.px(h / dp, p.fontPx))
                b != null -> h - px(b.px(h / dp, p.fontPx)) - v.measuredHeight
                else -> 0
            }
        }
        setMeasuredDimension(w, h)
    }

    /** Free space to fr tracks (`1fr` each share, the sum taken as at least 1), else evenly to `auto` tracks. */
    private fun distribute(sizes: FloatArray, tracks: List<CssGridSpec.Track>, free: Float) {
        val frSum = tracks.sumOf { it.fr.toDouble() }.toFloat()
        if (frSum > 0f) {
            val unit = max(0f, free) / max(frSum, 1f)
            tracks.forEachIndexed { i, t -> if (t.fr > 0f) sizes[i] = unit * t.fr }
        } else if (free > 0f) {
            val auto = tracks.indices.filter { tracks[it].content == "auto" }
            auto.forEach { sizes[it] += free / auto.size }
        }
    }

    private fun span(sizes: IntArray, from: Int, to: Int, gap: Int): Int {
        if (to <= from) return 0
        var s = 0
        for (i in from until min(to, sizes.size)) s += sizes[i]
        return s + gap * (to - from - 1)
    }

    private fun marginsPx(p: Params, wCss: Float): IntArray {
        val m = p.margins.takeIf { it.size == 4 } ?: return IntArray(4)
        return IntArray(4) { i -> if (m[i].isAuto) 0 else px(m[i].px(wCss, p.fontPx)) }
    }

    private fun maxContentWidth(v: View, p: Params, wCss: Float): Int {
        val m = marginsPx(p, wCss)
        p.cssWidth?.takeIf { it.unit != "%" }?.let { return px(it.px(0f, p.fontPx)) + m[1] + m[3] }
        if (v is TextView) {
            v.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            return v.measuredWidth + m[1] + m[3]
        }
        return 0
    }

    /** An item's width in an area [areaW] wide: its own `width`, the area (stretch), or its content (shrink-to-fit). */
    private fun itemWidth(v: View, p: Params, areaW: Int, wCss: Float): Int {
        val m = marginsPx(p, wCss)
        val room = (areaW - m[1] - m[3]).coerceAtLeast(0)
        val maxW = p.maxWidth?.let { px(it.px(areaW / dp, p.fontPx)) } ?: Int.MAX_VALUE
        val minW = p.minWidth?.let { px(it.px(areaW / dp, p.fontPx)) } ?: 0
        val justify = p.justifySelf?.takeIf { it != "auto" } ?: justifyItems ?: "stretch"
        val w = when {
            p.cssWidth != null -> px(p.cssWidth!!.px(areaW / dp, p.fontPx))
            justify == "stretch" || justify == "normal" -> room
            v is TextView -> {
                v.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                min(v.measuredWidth, room)
            }
            else -> room
        }
        return max(minW, min(w, maxW))
    }

    /** An item's height as its row sees it (margins included): text at its width, the icon cell's content. */
    private fun contentHeight(v: View, p: Params, iw: Int, areaW: Int): Int {
        val m = marginsPx(p, 0f)
        p.cssHeight?.takeIf { it.unit != "%" }?.let { return px(it.px(0f, p.fontPx)) + m[0] + m[2] }
        val inner = when (v) {
            is TextView -> {
                v.measure(MeasureSpec.makeMeasureSpec(iw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                v.measuredHeight
            }
            else -> (v as? NaturalHeight)?.naturalHeight(iw) ?: 0
        }
        return inner + m[0] + m[2]
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == GONE) continue
            val p = v.layoutParams as Params
            v.layout(p.x, p.y, p.x + v.measuredWidth, p.y + v.measuredHeight)
        }
    }

    /** A grid item that knows its content height at a width (the icon cell). */
    interface NaturalHeight {
        fun naturalHeight(width: Int): Int
    }
}
