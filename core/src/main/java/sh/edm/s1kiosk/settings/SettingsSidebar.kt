package sh.edm.s1kiosk.settings

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.glassworks.brand.Tokens
import dev.glassworks.manage.SettingsNav
import dev.glassworks.pane.ChipTone
import dev.glassworks.pane.PaneState
import dev.glassworks.pane.chipTone
import dev.glassworks.pane.chipWord
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Inter
import sh.edm.s1kiosk.ui.StackView

/**
 * The settings menu's sidebar, drawn from [SettingsNav] as the Manage page draws its own: the mark with
 * "Settings" and the frame's name, then Overview, Panel, Panes and System. A pane has a dot in its
 * status colour at the row's end; one that can't run is greyed; the ones turned off fold under "Off · N".
 * The list scrolls when it doesn't fit; [foot] stays at the bottom (the panel's Done). Main thread.
 */
class SettingsSidebar(context: Context) : LinearLayout(context) {

    var onTap: ((String) -> Unit)? = null

    /** What sits under the list, always reachable: the panel puts Done here. */
    val foot = FrameLayout(context)

    private val g = Glass(context)
    private val frameName = g.text(13.5f, Glass.MU).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(g.px(24f))
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(list, LayoutParams(-1, -2))
    }

    init {
        orientation = VERTICAL
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(g.px(10f), 0, g.px(8f), 0)
            addView(StackView(context), LayoutParams(g.px(26f), g.px(25f)).apply { marginEnd = g.px(12f) })
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(g.text(18f, Glass.TX, Inter.MEDIUM).apply { text = "Settings" })
                addView(frameName, LayoutParams(-1, -2).apply { topMargin = g.px(2f) })
            }, LayoutParams(0, -2, 1f))
        }, LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, 0, 1f).apply { topMargin = g.px(10f) })
        addView(foot, LayoutParams(-1, -2).apply { topMargin = g.px(8f) })
    }

    /** The list fits without scrolling (tests). */
    val fits: Boolean get() = list.height <= scroll.height

    /** Draws [nav] with [selected] picked. [overview]: the menu has an Overview. [frame]: the frame's name, under "Settings". */
    fun bind(nav: SettingsNav, selected: String?, overview: Boolean, frame: String) {
        val moved = selected != shown
        shown = selected
        frameName.text = frame
        frameName.visibility = if (frame.isEmpty()) View.GONE else View.VISIBLE
        list.removeAllViews()
        if (overview) add(row(SettingsNav.OVERVIEW, "Overview", selected))
        group("Panel", nav.panel.map { row(it.id, it.title, selected) })
        val off = if (nav.off.isEmpty()) emptyList() else {
            val open = nav.off.any { it.id == selected }
            listOf(fold(nav.off.size, open) { onTap?.invoke(nav.off.first().id) }) + if (open) nav.off.map { row(it.id, it.title, selected, it.state) } else emptyList()
        }
        group("Panes", nav.panes.map { row(it.id, it.title, selected, it.state) } + off)
        group("System", nav.system.map { row(it.id, it.title, selected) })
        // A page picked from elsewhere (the Overview's list) brings its row into view; the refresh ticks don't move the list.
        if (moved) scroll.post {
            val row = (0 until list.childCount).map(list::getChildAt).firstOrNull { it.isSelected } ?: return@post
            if (row.top < scroll.scrollY || row.bottom > scroll.scrollY + scroll.height) scroll.scrollTo(0, row.top - (scroll.height - row.height) / 2)
        }
    }

    /** The page the list was last drawn for. */
    private var shown: String? = null

    private fun group(title: String, rows: List<View>) {
        if (rows.isEmpty()) return
        list.addView(g.label(title).apply { setPadding(g.px(12f), 0, 0, 0) },
            LayoutParams(-1, -2).apply { topMargin = g.px(14f); bottomMargin = g.px(4f) })
        rows.forEach(::add)
    }

    private fun add(v: View) {
        v.minimumHeight = g.px(ROW_DP)
        v.setPadding(v.paddingLeft, g.px(8f), v.paddingRight, g.px(8f))
        list.addView(v, LayoutParams(-1, -2).apply { topMargin = g.px(2f) })
    }

    private fun row(id: String, title: String, selected: String?, state: PaneState? = null) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(g.px(12f), 0, g.px(12f), 0)
        val on = id == selected
        val color = when {
            state?.kind == PaneState.Kind.CANT_RUN -> Glass.FA
            state?.kind == PaneState.Kind.OFF && !on -> Glass.MU
            else -> Glass.TX
        }
        // A long name ("Home Assistant & dashboard" in the S1's narrow sidebar) takes a second line.
        addView(g.text(16f, color).apply { text = title; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f) }, LayoutParams(0, -2, 1f))
        // The pane's state as a dot at the row's end, every name at the same x; its word is the Overview's chip.
        state?.chipWord?.let { word ->
            addView(g.dot(dotColor(state)).apply { contentDescription = "status $word" }, LayoutParams(g.px(8f), g.px(8f)).apply { marginStart = g.px(10f) })
        }
        background = if (on) g.surface(Tokens.R_PANE.toFloat()) else null
        isSelected = on
        contentDescription = title
        isClickable = true
        setOnClickListener { onTap?.invoke(id) }
    }

    /** "Off · N" and a chevron, quieter than a row: right while folded, down while open. */
    private fun fold(n: Int, open: Boolean, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(g.px(12f), 0, g.px(12f), 0)
        addView(g.text(13f, Glass.FA, Inter.MEDIUM).apply { text = "Off · $n"; isAllCaps = true; letterSpacing = 0.12f }, LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = "›"; textSize = 20f; setTextColor(Glass.FA); includeFontPadding = false
            rotation = if (open) 90f else 0f
        })
        contentDescription = "Off: $n panes"
        isClickable = true
        setOnClickListener { onClick() }
    }

    companion object {
        /** A sidebar row's height, in dp. */
        const val ROW_DP = 40f

        /** A pane's status colour: green while it runs, rose for a problem, faint otherwise (#243). */
        fun dotColor(state: PaneState): Int = when (state.chipTone) {
            ChipTone.OK -> Glass.GREEN
            ChipTone.PROBLEM -> Glass.ROSE
            ChipTone.QUIET -> Glass.FA
        }
    }
}
