package sh.edm.s1kiosk.settings

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.brand.Tokens
import dev.glassworks.manage.ManagePages
import dev.glassworks.manage.OverviewAction
import dev.glassworks.manage.PanelActions
import dev.glassworks.manage.PanelStatus
import dev.glassworks.manage.Tile
import dev.glassworks.manage.panesSummary
import dev.glassworks.pane.PaneState
import dev.glassworks.pane.chipWord
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Inter
import sh.edm.s1kiosk.ui.StackView
import sh.edm.s1kiosk.ui.Wrap

/**
 * The settings menu's first page, the Manage page's Overview on the panel: what it runs and since when,
 * with Reload dashboard, Restart app and Check for updates; Home Assistant, Wi-Fi, the engine and memory
 * as tiles ([columns] to a row); then every pane with its state, each opening its page ([onPane]). All
 * from the Manage page's own [PanelStatus] and [PanelActions]. Main thread.
 */
class OverviewPage(context: Context, private val columns: Int) : LinearLayout(context) {

    var onPane: ((String) -> Unit)? = null

    private val g = Glass(context)
    private var actions: PanelActions? = null
    /** What the page was drawn from: drawn again when it changes. */
    private var drawn: List<Any?>? = null
    /** What the last action said: started, or why not. */
    private var answer: Pair<String, Boolean>? = null
    private var last: PanelStatus? = null

    init { orientation = VERTICAL }

    fun bind(s: PanelStatus, a: PanelActions) {
        actions = a
        last = s
        val shape = listOf(s.version, ManagePages.duration(s.uptimeMs), s.tiles, s.panes.map { listOf(it.id, it.title, it.state) }, answer)
        if (shape == drawn) return
        drawn = shape
        removeAllViews()
        addView(hero(s, a), LayoutParams(-1, -2))
        addView(tiles(s.tiles), LayoutParams(-1, -2).apply { topMargin = g.px(GAP) })
        if (s.panes.isNotEmpty()) addView(panes(s), LayoutParams(-1, -2).apply { topMargin = g.px(GAP) })
    }

    private fun card() = LinearLayout(context).apply {
        orientation = VERTICAL
        background = g.surface(Tokens.R_TILE.toFloat())
        val p = g.px(CARD_PAD)
        setPadding(p, p, p, p)
    }

    private fun hero(s: PanelStatus, a: PanelActions) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = g.surface(Tokens.R_TILE.toFloat())
        val p = g.px(CARD_PAD)
        setPadding(g.px(CARD_PAD + 6f), p, p, p)
        addView(StackView(context), LayoutParams(g.px(64f), g.px(60f)).apply { marginEnd = g.px(26f) })
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(g.dot(Glass.GREEN), LayoutParams(g.px(10f), g.px(10f)).apply { marginEnd = g.px(12f) })
                addView(g.heading(26f).apply { text = "Running" })
            })
            addView(g.text(15f, Glass.MU).apply { text = "Glassworks ${s.version} · up ${ManagePages.duration(s.uptimeMs)}" },
                LayoutParams(-1, -2).apply { topMargin = g.px(6f) })
            addView(Wrap(context, g.px(10f), g.px(10f)).apply {
                OverviewAction.entries.forEach { act ->
                    addView(g.ghost(act.label).apply {
                        textSize = 15f
                        setPadding(g.px(18f), g.px(11f), g.px(18f), g.px(11f))
                        setOnClickListener { run(act) }
                    })
                }
            }, LayoutParams(-1, -2).apply { topMargin = g.px(14f) })
            answer?.let { (words, ok) ->
                addView(g.text(14.5f, if (ok) Glass.MU else Glass.ROSE).apply { text = words; setLineSpacing(0f, 1.2f) },
                    LayoutParams(-1, -2).apply { topMargin = g.px(10f) })
            }
        }, LayoutParams(0, -2, 1f))
    }

    /** Runs [act] and says what came of it, in the Manage page's words. */
    private fun run(act: OverviewAction) {
        val a = actions ?: return
        val why = runCatching { act.run(a) }.getOrElse { it.message ?: "That didn't work." }
        answer = if (why == null) act.started to true else why to false
        last?.let { bind(it, a) }
    }

    /** The menu opened again: what an action said last time is over. */
    fun reset() { answer = null }

    private fun tiles(list: List<Tile>) = LinearLayout(context).apply {
        orientation = VERTICAL
        list.chunked(columns).forEachIndexed { r, row ->
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                repeat(columns) { c ->
                    val v = if (c < row.size) tile(row[c]) else View(context)
                    addView(v, LayoutParams(0, -1, 1f).apply { if (c > 0) marginStart = g.px(GAP) })
                }
            }, LayoutParams(-1, -2).apply { if (r > 0) topMargin = g.px(GAP) })
        }
    }

    private fun tile(t: Tile) = LinearLayout(context).apply {
        orientation = VERTICAL
        background = g.surface(Tokens.R_TILE.toFloat())
        val p = g.px(CARD_PAD)
        setPadding(p, p, p, p)
        addView(g.text(13.5f, Glass.MU).apply { text = t.label; setSingleLine() })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            t.tone?.let { addView(g.dot(toneColor(it)), LayoutParams(g.px(8f), g.px(8f)).apply { marginEnd = g.px(9f) }) }
            addView(g.text(18f, Glass.TX).apply { text = t.value; setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
        }, LayoutParams(-1, -2).apply { topMargin = g.px(6f) })
        if (t.detail.isNotEmpty()) addView(g.text(14f, Glass.MU).apply { text = t.detail; setSingleLine(); ellipsize = TextUtils.TruncateAt.END },
            LayoutParams(-1, -2).apply { topMargin = g.px(5f) })
        contentDescription = "${t.label}: ${t.value}"
    }

    private fun panes(s: PanelStatus) = card().apply {
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(g.text(16.5f, Glass.TX, Inter.MEDIUM).apply { text = "Panes" }, LayoutParams(0, -2, 1f))
            addView(g.text(14f, Glass.MU).apply { text = panesSummary(s.panes) })
        }, LayoutParams(-1, -2).apply { bottomMargin = g.px(6f) })
        s.panes.forEach { p ->
            addView(View(context).apply { setBackgroundColor(Glass.BD) }, LayoutParams(-1, g.px(1f).coerceAtLeast(1)))
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = g.px(ROW_MIN)
                setPadding(0, g.px(8f), 0, g.px(8f))
                val off = p.state.kind == PaneState.Kind.OFF
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(g.text(16.5f, if (off) Glass.MU else Glass.TX).apply { text = p.title; setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
                    if (p.state.detail.isNotEmpty()) addView(g.text(14f, Glass.MU).apply { text = p.state.detail; setSingleLine(); ellipsize = TextUtils.TruncateAt.END },
                        LayoutParams(-1, -2).apply { topMargin = g.px(4f) })
                }, LayoutParams(0, -2, 1f).apply { marginEnd = g.px(14f) })
                p.state.chipWord?.let { addView(g.statusChip(it, SettingsSidebar.dotColor(p.state))) }
                addView(g.text(24f, Glass.FA, Inter.LIGHT).apply { text = "›" }, LayoutParams(-2, -2).apply { marginStart = g.px(14f) })
                contentDescription = p.title
                val id = p.sectionId
                if (id != null) { isClickable = true; setOnClickListener { onPane?.invoke(id) } }
            }, LayoutParams(-1, -2))
        }
    }

    private fun toneColor(t: Tile.Tone) = when (t) {
        Tile.Tone.OK -> Glass.GREEN
        Tile.Tone.WORKING -> Glass.AMBER
        Tile.Tone.PROBLEM -> Glass.ROSE
        Tile.Tone.OFF -> Glass.FA
    }

    companion object {
        /** A card's padding, the same on every side, and the gap between cards, in dp. */
        const val CARD_PAD = 16f
        const val GAP = 12f
        /** A row's least height, in dp: a finger's width on the S1. */
        const val ROW_MIN = 52f
    }
}
