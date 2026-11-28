/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.BoringLayout
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristic
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import dev.glassworks.engine.vitro.ui.IconHost
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.platform.Platform
import dev.glassworks.ui.Inter
import kotlin.math.ceil
import kotlin.math.max

/**
 * A plain entities-card row, `hui-generic-entity-row` with a toggle or its state at the end, as one view: it draws the
 * state badge's icon, the name and the state itself, and holds a real child only for the toggle ([switch]), which takes
 * its own taps and TalkBack focus. It's what the row of four views (a LinearLayout around an icon, two TextViews and the
 * toggle or the state) drew, pixel for pixel, made with one view instead of four: a TextView is the costliest view to
 * make, and Clarity's heavy view had 253 rows of them (`HeavyViewMeasure`). Rows with a control, cover buttons or a
 * secondary line keep their views ([EntitiesCardView]).
 *
 * It lays out as that LinearLayout did (`CENTER_VERTICAL`, 16 px side padding, at least 40 px tall): the 24 px icon
 * 8 px in with 24 px after it, the name taking what's left (`weight 1`) with 8 px after it, then the end as wide as it
 * wants, up to the row. Its texts are laid out as the TextViews laid theirs (one line, the name ellipsized at its end,
 * [RowText]). A name squeezed to nothing still shows its first letters in the 8 px after it, cut, as Chrome does
 * (`EntityRowLayout`). A new state that keeps the row's height lays out and draws this row alone, never the card.
 *
 * TalkBack reads the row as its name, and the state after it ([contentDescription]); the toggle is read on its own.
 */
internal class EntityRowView(
    private val ctx: VitroCardContext,
    paint: TextPaint,
    /** The toggle at the row's end, or null for a row that shows its state. */
    val switch: HaSwitchView?,
) : ViewGroup(ctx.context), IconHost {
    private val name = RowText(paint, Layout.Alignment.ALIGN_NORMAL, ellipsize = true)
    private val state: RowText? = if (switch == null) RowText(paint, Layout.Alignment.ALIGN_OPPOSITE, ellipsize = false) else null

    private val padStart = ctx.px(16f)
    private val padEnd = ctx.px(16f)
    private val minRowHeight = ctx.px(40f)
    private val iconSize = ctx.px(24f)
    private val iconMarginStart = ctx.px(8f)
    private val iconMarginEnd = ctx.px(24f)
    private val nameMarginEnd = ctx.px(8f)

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var iconName = "home"
    private var iconPath: Path? = null
    private var iconTint = 0

    // Where each part sits, in this view's coordinates: set by [place].
    private var iconLeft = 0
    private var iconTop = 0
    private var nameLeft = 0
    private var nameWidth = 0
    private var nameTop = 0
    private var endLeft = 0
    private var endWidth = 0
    private var endTop = 0
    private var nameHeight = 0
    private var endHeight = 0

    /** The name had no room: drawn whole (no ellipsis) across the 8 px after it, clipped. */
    private var squeezed = false

    /** The width [place] last laid the row out for, or -1. */
    private var placedFor = -1

    /**
     * The tallest a text may be: the height the row was measured within, when its parent bounds it (a TextView sized
     * to its text is never taller than what it's given), else no limit.
     */
    private var textCap = Int.MAX_VALUE

    init {
        setWillNotDraw(false)
        // Real padding, as the LinearLayout had: its children (the toggle) are clipped to it, and so is the text.
        setPaddingRelative(padStart, 0, padEnd, 0)
        if (switch != null) addView(switch)
    }

    /** The name as shown. */
    val nameText: CharSequence get() = name.text

    /** The state as shown, or null for a row with a toggle. */
    val stateText: CharSequence? get() = state?.text

    /** The icon's name as last set (`mdi:` prefix and all), and its color. */
    val icon: String get() = iconName
    val iconColor: Int get() = iconTint

    /** Whether the name was squeezed out by the end (tests). */
    internal val nameSqueezed: Boolean get() = squeezed

    /** A text the row draws: its words, its layout, and where the layout sits in the row, px (tests, the parity harness). */
    internal class DrawnText(val text: CharSequence, val layout: Layout, val left: Int, val top: Int, val paint: TextPaint)

    /** The name and the state as drawn, once laid out. */
    internal fun drawnTexts(): List<DrawnText> {
        if (placedFor != width) place(width)
        return listOfNotNull(
            name.laidOut()?.let { DrawnText(name.text, it, nameLeft, nameTop, it.paint) },
            state?.laidOut()?.let { DrawnText(state.text, it, endLeft, endTop, it.paint) },
        )
    }

    fun setIcon(name: String, color: Int) {
        val mayBeStandIn = MdiIcons.isLoading
        val path = MdiIcons.pathFor(name)
        if (mayBeStandIn) MdiIcons.redrawWhenReady(this)
        if (path === iconPath && name == iconName && color == iconTint) return
        iconName = name
        iconPath = path
        iconTint = color
        invalidate()
    }

    override fun reloadIcon() {
        iconPath = MdiIcons.pathFor(iconName)
        invalidate()
    }

    /** How many times a new name or state was set (tests: a TextView's setText, counted). */
    internal var textSets = 0
        private set

    /** Sets the name; returns whether it changed (the row then needs [commit]). */
    fun setName(text: CharSequence): Boolean = name.set(text).also { if (it) textSets++ }

    /** Sets the state; returns whether it changed (the row then needs [commit]). */
    fun setState(text: CharSequence): Boolean = (state?.set(text) ?: false).also { if (it) textSets++ }

    /**
     * After a change of text: lays the row out again, on its own when it keeps its height (a new state is a new width
     * for the name and the state, never for the row), else asks its parent.
     */
    fun commit() {
        val labels = describe()
        if (!TextUtils.equals(contentDescription, labels)) contentDescription = labels
        if (!isLaidOut || isLayoutRequested || width == 0) {
            requestLayout()
            invalidate()
            return
        }
        if (rowHeight() != height) {
            requestLayout()
        } else {
            placedFor = -1
            place(width)
        }
        invalidate()
    }

    /** What TalkBack reads: the name, then the state for a row that shows one. */
    private fun describe(): CharSequence {
        val st = state?.text
        return if (st.isNullOrEmpty()) name.text else "${name.text}, $st"
    }

    private val rtl: Boolean get() = layoutDirection == LAYOUT_DIRECTION_RTL

    private fun textDir(): TextDirectionHeuristic =
        if (rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR

    /** The end's height: the toggle's, or the state's line. */
    private fun endHeight(): Int = switch?.measuredHeight ?: state!!.height(textDir())

    /**
     * The row's height, as LinearLayout found it: the tallest child, or for the texts (aligned by their baselines) the
     * deepest ascent and descent together, and at least 40 px.
     */
    private fun rowHeight(): Int {
        val dir = textDir()
        var ascent = name.baseline(dir)
        var descent = name.height(dir) - ascent
        var tallest = max(iconSize, name.height(dir))
        if (state != null) {
            ascent = max(ascent, state.baseline(dir))
            descent = max(descent, state.height(dir) - state.baseline(dir))
        }
        tallest = max(tallest, endHeight())
        return max(minRowHeight, max(tallest, ascent + descent))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        textCap = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)
        switch?.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        placedFor = -1
        setMeasuredDimension(w, resolveSize(rowHeight(), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        placedFor = -1
        place(r - l)
    }

    /**
     * LinearLayout's horizontal pass for these children: the end measured first, as wide as it wants up to the row
     * inside its padding, then the name given what's left (never less than nothing), placed from the start edge in
     * order (icon, name, end), or from the left in reverse for a right-to-left row. Each child centered vertically.
     */
    private fun place(w: Int) {
        if (placedFor == w) return
        placedFor = w
        val dir = textDir()
        val content = (w - padStart - padEnd).coerceAtLeast(0)
        endWidth = switch?.measuredWidth ?: minOf(state!!.desiredWidth(dir), content)
        val used = padStart + padEnd + iconMarginStart + iconSize + iconMarginEnd + nameMarginEnd + endWidth
        nameWidth = (w - used).coerceAtLeast(0)
        squeezed = nameWidth == 0 && name.text.isNotEmpty()
        // A squeezed name is laid out on one unbounded line (no ellipsis), as `setHorizontallyScrolling` lays it out.
        name.layOut(if (squeezed) VERY_WIDE else nameWidth, dir, ellipsize = !squeezed)
        state?.layOut(endWidth, dir, ellipsize = false)
        val h = height.takeIf { it > 0 } ?: measuredHeight
        iconTop = (h - iconSize) / 2
        nameHeight = minOf(name.height(dir), textCap)
        nameTop = (h - nameHeight) / 2
        endHeight = switch?.measuredHeight ?: minOf(endHeight(), textCap)
        endTop = (h - endHeight) / 2
        if (!rtl) {
            iconLeft = paddingLeft + iconMarginStart
            nameLeft = iconLeft + iconSize + iconMarginEnd
            endLeft = nameLeft + nameWidth + nameMarginEnd
        } else {
            // In reverse from the left: the end, the name (its end margin on its left), then the icon.
            endLeft = paddingLeft
            nameLeft = endLeft + endWidth + nameMarginEnd
            iconLeft = nameLeft + nameWidth + iconMarginEnd
        }
        switch?.layout(endLeft, endTop, endLeft + endWidth, endTop + switch.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        if (placedFor != width) place(width)
        // Inside the padding, as the LinearLayout clipped its children (clipToPadding).
        canvas.save()
        canvas.clipRect(paddingLeft, 0, width - paddingRight, height)
        drawIcon(canvas)
        // A squeezed name keeps its 8 px: the TextView was laid out that wide, from where the name starts.
        val nameShown = if (squeezed) nameMarginEnd else nameWidth
        name.draw(canvas, nameLeft, nameTop, nameShown, nameHeight)
        state?.draw(canvas, endLeft, endTop, endWidth, endHeight)
        canvas.restore()
    }

    /** As [dev.glassworks.engine.vitro.ui.MdiIconView] draws its glyph in its 24 px box. */
    private fun drawIcon(canvas: Canvas) {
        val src = iconPath ?: MdiIcons.pathFor(iconName).also { iconPath = it }
        val side = iconSize.coerceAtLeast(1).toFloat()
        iconPaint.color = iconTint
        canvas.save()
        canvas.translate(iconLeft.toFloat(), iconTop.toFloat())
        canvas.drawPath(MdiIcons.placed(src, side / 24f, 0f, 0f), iconPaint)
        canvas.restore()
    }

    /**
     * One line of text, laid out as a TextView with `maxLines = 1` lays it out (`TextView.makeSingleLayout`): a
     * [BoringLayout] for text that fits and needs no bidi, else a [StaticLayout] of one line, ellipsized at its end
     * when [ellipsize] (the name). Its line spacing takes fallback fonts' heights into account where a TextView's does
     * (Android 9 and up, and from Android 13 for the boring layout too).
     */
    private class RowText(private val paint: TextPaint, private val alignment: Layout.Alignment, private val ellipsize: Boolean) {
        var text: CharSequence = ""
            private set
        private var desired = -1
        private var layout: Layout? = null
        private var laidWidth = -1
        private var laidEllipsized = false
        private var laidDir: TextDirectionHeuristic? = null

        /**
         * The text's one-line metrics when it's "boring" (no bidi; `BoringLayout.isBoring`), for the direction
         * [boringDir]: its width, height and baseline without a layout, as a TextView reads them.
         */
        private var boring: BoringLayout.Metrics? = null
        private var boringDir: TextDirectionHeuristic? = null

        /** A layout of the whole line, for its height and baseline, when the text isn't boring. */
        private var probe: Layout? = null
        private var probeDir: TextDirectionHeuristic? = null

        fun set(t: CharSequence): Boolean {
            if (TextUtils.equals(text, t)) return false
            text = t
            desired = -1
            layout = null
            boringDir = null
            probe = null
            return true
        }

        /** [text]'s boring metrics for [dir], or null when a TextView would lay it out with a StaticLayout. */
        private fun boring(dir: TextDirectionHeuristic): BoringLayout.Metrics? {
            if (boringDir === dir) return boring
            boring = when {
                // Android 13's TextView asks with its direction and fallback fonts' line heights.
                Platform.hasBoringFallbackLineSpacing -> BoringLayout.isBoring(text, paint, dir, true, null)
                // Before it, the hidden form TextView uses refuses text its direction makes right-to-left first.
                dir.isRtl(text, 0, text.length) -> null
                else -> BoringLayout.isBoring(text, paint)
            }
            boringDir = dir
            desired = -1
            return boring
        }

        /** The width the text wants, rounded up, as a TextView sized to its text takes. */
        fun desiredWidth(dir: TextDirectionHeuristic): Int {
            if (desired < 0) desired = boring(dir)?.width ?: ceil(Layout.getDesiredWidth(text, paint).toDouble()).toInt()
            return desired
        }

        private fun probe(dir: TextDirectionHeuristic): Layout {
            probe?.takeIf { probeDir === dir }?.let { return it }
            return make(desiredWidth(dir).coerceAtLeast(1), dir, false).also { probe = it; probeDir = dir }
        }

        /** The line's height: its layout's, once laid out, else its boring metrics', else a layout of the whole line. */
        fun height(dir: TextDirectionHeuristic): Int {
            layout?.takeIf { laidDir === dir }?.let { return it.getLineTop(1) }
            boring(dir)?.let { return it.descent - it.ascent }
            return probe(dir).getLineTop(1)
        }

        fun baseline(dir: TextDirectionHeuristic): Int {
            layout?.takeIf { laidDir === dir }?.let { return it.getLineBaseline(0) }
            boring(dir)?.let { return -it.ascent }
            return probe(dir).getLineBaseline(0)
        }

        fun laidOut(): Layout? = layout

        fun layOut(width: Int, dir: TextDirectionHeuristic, ellipsize: Boolean) {
            val e = ellipsize && this.ellipsize
            if (layout != null && laidWidth == width && laidEllipsized == e && laidDir === dir) return
            layout = make(width, dir, e)
            laidWidth = width
            laidEllipsized = e
            laidDir = dir
        }

        private fun make(width: Int, dir: TextDirectionHeuristic, ellipsize: Boolean): Layout {
            val w = width.coerceAtLeast(0)
            boring(dir)?.let { m ->
                if (m.width <= w) {
                    return if (Platform.hasBoringFallbackLineSpacing) {
                        BoringLayout.make(text, paint, w, alignment, m, false, null, 0, true)
                    } else {
                        BoringLayout.make(text, paint, w, alignment, 1f, 0f, m, false)
                    }
                }
            }
            val b = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                .setAlignment(alignment)
                .setTextDirection(dir)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            // Ellipsized to one line; else as many as it takes, the first shown (TextView's layout without ellipsis).
            if (ellipsize) b.setEllipsize(TextUtils.TruncateAt.END).setEllipsizedWidth(w).setMaxLines(1)
            if (Platform.hasFallbackLineSpacing) b.setUseLineSpacingFromFallbacks(true)
            return b.build()
        }

        /** Draws the line at ([left], [top]), clipped to [width] x [height], as a TextView clips to itself. */
        fun draw(canvas: Canvas, left: Int, top: Int, width: Int, height: Int) {
            val l = layout ?: return
            canvas.save()
            canvas.clipRect(left, top, left + width, top + height)
            canvas.translate(left.toFloat(), top.toFloat())
            l.draw(canvas)
            canvas.restore()
        }
    }

    companion object {
        /**
         * Whether plain rows are drawn as one view. Tests turn it off to draw the row of views it replaced, which it
         * must match pixel for pixel (`EntityRowViewTest`); the rows with a control or a secondary line always are.
         */
        internal var enabled = true

        /** `TextView.VERY_WIDE`: the width a horizontally scrolling TextView lays its line out at. */
        private const val VERY_WIDE = 1024 * 1024

        /**
         * The paint a row's name and state are drawn with: 14 px of the theme's primary text color in its regular
         * weight, sized and set up as a TextView sets up its own. One for every row of a card.
         */
        fun paint(ctx: VitroCardContext): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            val dm = ctx.context.resources.displayMetrics
            density = dm.density
            val sp = ctx.scaledSp(14f)
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
            typeface = VitroFonts.typeface(ctx.context, Inter.REGULAR, sp)
            color = ctx.theme.primaryTextColor
        }
    }
}
