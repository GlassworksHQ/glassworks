package dev.glassworks.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.glassworks.pane.BLUEPRINTS
import dev.glassworks.pane.BlueprintAction
import dev.glassworks.pane.BlueprintSurface
import dev.glassworks.manage.PanelActions
import dev.glassworks.manage.PanelStatus
import dev.glassworks.manage.SettingsNav
import dev.glassworks.manage.paneRow
import dev.glassworks.manage.settingsNav
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsChange.couldNot
import dev.glassworks.pane.SettingsChange.hhmm
import dev.glassworks.pane.SettingsChange.parseRange
import dev.glassworks.pane.shown
import dev.glassworks.setup.Backdrop
import dev.glassworks.ui.Glass
import dev.glassworks.ui.Glass.Companion.AMBER
import dev.glassworks.ui.Glass.Companion.BD
import dev.glassworks.ui.Glass.Companion.FA
import dev.glassworks.ui.Glass.Companion.MU
import dev.glassworks.ui.Glass.Companion.ROSE
import dev.glassworks.ui.Glass.Companion.TX
import dev.glassworks.ui.GlassSlider
import dev.glassworks.ui.Inter
import dev.glassworks.ui.QrCode
import dev.glassworks.ui.StackView
import dev.glassworks.ui.TileView
import dev.glassworks.theme.Themes
import dev.glassworks.ui.Wrap

/**
 * The kiosk settings menu, in the Manage page's layout (approved 2026-11-15). Left: the sidebar
 * ([SettingsSidebar], from the same [settingsNav] as the Manage page's): Overview, Panel, Panes and System,
 * and Done at the foot. Right: the page picked. The Overview ([OverviewPage]) is the Manage page's, from its
 * [PanelStatus] and [PanelActions]; it is where the menu opens. A section's page has its title with the
 * pane's on/off at top right ([SettingsSection.master]), then its rows. A switch applies when tapped, a
 * slider when let go, a duration or a time window when picked, a typed value on Save. A change that only
 * takes effect at start shows #33's banner with Restart now.
 *
 * A full-screen overlay in MainActivity's window, above the setup screen. Main thread only.
 */
class KioskSettingsOverlay(context: Context) : FrameLayout(context) {

    data class Model(
        val sections: List<SettingsSection>,
        val version: String,
        /** The ids of the panes' sections: the sidebar's Panes. */
        val panes: Set<String> = emptySet(),
        /** The frame's name in words ("S1 Plus"), under "Settings". */
        val frame: String = "",
    )

    /** What the Overview shows and does: the Manage page's own ([dev.glassworks.manage.ManageHost]). */
    class Overview(val status: () -> PanelStatus, val actions: PanelActions)

    /** The Overview's source; without one the menu has no Overview and opens on its first section. */
    var overview: Overview? = null

    /** "Restart now": a change waits for the app to start again. */
    var onRestart: (() -> Unit)? = null

    /**
     * The model as it is now. After a pane is turned on or off its page changes (#212: an off pane's page
     * says what that means), so the menu asks again.
     */
    var reload: (() -> Model)? = null

    var isShowing = false
        private set

    private val g = Glass(context)
    private fun px(v: Float) = g.px(v)

    private var model: Model? = null
    private var current: String? = null
    /** A change that needs a restart was made since the app started. */
    private var restartPending = false

    private val sidebar = SettingsSidebar(context).apply {
        onTap = { id -> select(id) }
        foot.addView(g.primary("Done").apply { setPadding(0, px(12f), 0, px(12f)); setOnClickListener { hide() } }, FrameLayout.LayoutParams(-1, -2))
    }

    /** Wide screens get a wider sidebar: about a fifth of the width. */
    private val sideWidth = (resources.configuration.screenWidthDp * 0.22f).coerceIn(200f, 300f)
    /** A tall screen (a tablet) puts the Overview's tiles two to a row; the S1's 480 dp put them in one. */
    private val overviewPage = OverviewPage(context, if (resources.configuration.screenHeightDp >= 600) 2 else 4).apply { onPane = { id -> select(id) } }

    private val title = g.heading(32f).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    /** The pane's on/off, at top right ([SettingsSection.master]). */
    private val masterSlot = FrameLayout(context)
    private val unavailable = g.text(15f, MU).apply { setLineSpacing(0f, 1.2f) }
    private val problem = g.text(15f, ROSE).apply { setLineSpacing(0f, 1.2f) }
    private val banner = Banner()
    /** The section's items: cards of rows ([card]), and what sits between them (a status line, tiles, a pane's own view). */
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** The widest a row's value gets on the right, so its name keeps room. */
    private val valueMax = px(resources.configuration.screenWidthDp * 0.3f)
    private val head = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        addView(masterSlot, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(16f) })
    }
    /** A section's page: its head, what stands in its way, then its rows. */
    private val sectionPage = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6f) })
        addView(unavailable, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
        addView(problem, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
        addView(banner.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        addView(rows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
    }
    private val page = FrameLayout(context).apply {
        val tall = resources.configuration.screenHeightDp >= 600
        setPadding(px(if (tall) 36f else 28f), px(if (tall) 28f else 20f), px(if (tall) 36f else 28f), px(20f))
    }
    /** A page taller than the card fades at the edge it continues past, and shows its scroll bar (#63 D5). */
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(px(36f))
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(page, LayoutParams(-1, -2))
    }
    private val sheet = Sheet()

    /** Native items' views ([SettingsItem.Native], e.g. the radio's): made once, kept across show()s. */
    private val natives = LinkedHashMap<String, SettingsItem.NativeView>()
    private val nativeHost = object : SettingsItem.NativeHost {
        override val context: Context get() = this@KioskSettingsOverlay.context
        override fun addLayer(layer: View) { addView(layer, LayoutParams(-1, -1)) }
        override fun reveal(field: View) = this@KioskSettingsOverlay.reveal(field)
        override fun hideKeyboard() = this@KioskSettingsOverlay.hideKeyboard()
    }

    init {
        background = Backdrop()
        isClickable = true // nothing reaches the page underneath
        isFocusable = true
        isFocusableInTouchMode = true // takes focus from the page, so its keyboard closes
        elevation = 24 * g.dp // above the setup screen (20 dp) and the voice overlay (16 dp)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(sidebar.apply { setPadding(px(12f), px(18f), px(12f), px(14f)) }, LinearLayout.LayoutParams(px(sideWidth), -1))
            addView(View(context).apply { setBackgroundColor(BD) }, LinearLayout.LayoutParams(px(1f).coerceAtLeast(1), -1))
            addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        }, LayoutParams(-1, -1))
        addView(sheet.view, LayoutParams(-1, -1))
        visibility = View.GONE
    }

    /**
     * Shows the menu for [m]. Opened, it starts on the Overview (the first section without one); shown
     * again while open, it stays on its page. Values are read again.
     */
    fun show(m: Model) {
        model = m
        if (!isShowing) { overviewPage.reset(); if (overview != null) current = SettingsNav.OVERVIEW }
        // The Manage page stopped since the last opening: no Overview to draw.
        if (current == SettingsNav.OVERVIEW && overview == null) current = firstPage(m)
        if (current != SettingsNav.OVERVIEW && m.sections.none { it.id == current }) current = firstPage(m)
        sheet.close()
        build()
        if (!isShowing) {
            isShowing = true
            animate().cancel()
            if (visibility != View.VISIBLE) alpha = 0f
            visibility = View.VISIBLE
            bringToFront()
            animate().alpha(1f).setDuration(220).start()
        }
        requestFocus()
    }

    /**
     * Back: closes the topmost layer only, an open sheet (a picker, an editor, a question), then a pane's
     * own dialog, then the menu (#63 round 2: Back on a picker closed the whole menu).
     */
    fun back() {
        when {
            sheet.isOpen -> sheet.close()
            natives.values.any { it.back() } -> {}
            else -> hide()
        }
    }

    /** The sidebar's list fits without scrolling (tests). */
    val railFits: Boolean get() = sidebar.fits

    private fun firstPage(m: Model): String? = if (overview != null) SettingsNav.OVERVIEW else m.sections.firstOrNull()?.id

    fun hide() {
        if (!isShowing) return
        isShowing = false
        natives.values.forEach { it.reset() }
        sheet.close()
        hideKeyboard()
        findFocus()?.clearFocus()
        animate().alpha(0f).setDuration(200).withEndAction { if (!isShowing) visibility = View.GONE }.start()
    }

    /** While the menu is open (MainActivity, every couple of seconds): native views, live rows and the rail read their state again. */
    fun refresh() {
        if (!isShowing) return
        if (!sheet.isOpen && !masterBusy) reload?.invoke()?.let { next ->
            model = next
            val s = next.sections.firstOrNull { it.id == current }
            // The page changed under the person (a base mounted, the pane turned off from Home Assistant,
            // the device started): draw it again from the new model. Otherwise only its values move.
            if (current != SettingsNav.OVERVIEW && (s == null || shape(s) != builtShape)) {
                if (s == null) current = firstPage(next)
                // The same page drawn again stays where the person had scrolled it.
                val y = if (s != null) scroll.scrollY else 0
                build()
                if (y > 0) scroll.post { scroll.scrollTo(0, y) }
                return
            }
        }
        // Only the page on screen: the others' views wait until they are shown again.
        natives.values.forEach { if (it.view.parent != null) it.refresh() }
        live.forEach { it() }
        bindRail()
        bindMaster()
        if (current == SettingsNav.OVERVIEW) bindOverview()
    }

    /** The shape the open page was drawn from ([shape]). */
    private var builtShape: List<Any?> = emptyList()

    /** What a page is made of: what it reads ([SettingsSection.source]), its master's value, whether it can run, and its items' kinds and names. */
    private fun shape(s: SettingsSection): List<Any?> {
        fun of(items: List<SettingsItem>): List<Any?> = items.flatMap { i ->
            when (i) {
                is SettingsItem.Group -> listOf("group ${i.title}") + of(i.items)
                is SettingsItem.Native -> listOf("native ${i.key}")
                // A switch's line can say why it is refused (the camera's stream): a new line draws the page again.
                is SettingsItem.Switch -> listOf("Switch ${i.title} ${i.detail}")
                else -> listOf("${i::class.simpleName} ${titleOf(i).orEmpty()}")
            }
        }
        return listOf(s.source, s.master?.value(), s.unavailable() != null) + of(s.items)
    }

    /** The page's read-only rows ([SettingsItem.States]) that read their values again on [refresh]. */
    private val live = ArrayList<() -> Unit>()

    /** Previews (the mock-ups): a section's page, an open editor or picker, a native view, the restart banner. */
    fun previewSection(id: String) { current = id; build() }
    fun previewEdit(title: String, typed: String? = null) {
        when (val i = model?.sections?.flatMap { flat(it.items) }?.firstOrNull { titleOf(it) == title } ?: return) {
            is SettingsItem.Text -> { sheet.text(i) {}; typed?.let { sheet.field.setText(it) } }
            is SettingsItem.Duration -> sheet.duration(i) {}
            is SettingsItem.TimeRange -> sheet.range(i) {}
            is SettingsItem.Choice -> sheet.choice(i) {}
            is SettingsItem.SignIn -> sheet.signIn(i) {}
            else -> {}
        }
    }
    fun previewConfirm(id: String) {
        val s = model?.sections?.firstOrNull { it.id == id } ?: return
        val m = s.master ?: return
        m.confirm(!m.value())?.let { sheet.confirm(it) {} }
    }
    fun previewRestart() { restartPending = true; banner.bind() }
    /** The QR sheet of the open page's first blueprint that leads to a phone. */
    fun previewBlueprintQr() {
        val s = model?.sections?.firstOrNull { it.id == current } ?: return
        val card = flat(s.items).filterIsInstance<SettingsItem.Blueprints>().firstOrNull()?.card(BlueprintSurface.PANEL) ?: return
        card.rows.firstNotNullOfOrNull { (bp, a) -> (a as? BlueprintAction.Phone)?.let { bp to it } }?.let { (bp, a) -> sheet.qr(bp.name, a.url) }
    }
    fun previewNative(key: String): SettingsItem.NativeView? = natives[key]

    /** The native views kept now, by key (tests). */
    val keptNatives: Set<String> get() = natives.keys.toSet()
    fun previewScrollTo(v: View, alignBottom: Boolean = false) {
        val r = Rect()
        v.getDrawingRect(r)
        scroll.offsetDescendantRectToMyCoords(v, r)
        val y = if (alignBottom) r.bottom + px(16f) - scroll.height else r.top - px(16f)
        scroll.scrollTo(0, y.coerceAtLeast(0))
    }

    // ------------------------------------------------------------------ building

    private fun select(id: String) {
        if (current == id) return
        sheet.close(); hideKeyboard(); current = id; build()
    }

    private fun build() {
        val m = model ?: return
        bindRail()
        if (current == SettingsNav.OVERVIEW) {
            showPage(overviewPage)
            builtShape = emptyList()
            masterSlot.removeAllViews(); masterToggle = null
            rows.removeAllViews(); live.clear()
            natives.values.forEach { n -> (n.view.parent as? ViewGroup)?.removeView(n.view); n.reset() }
            bindOverview()
            scroll.scrollTo(0, 0)
            return
        }
        val s = m.sections.firstOrNull { it.id == current } ?: return
        showPage(sectionPage)
        builtShape = shape(s)
        title.text = s.heading
        val why = s.unavailable()
        unavailable.text = why?.let { "This pane can't run on this panel: $it." }
        unavailable.visibility = if (why != null) View.VISIBLE else View.GONE
        problem.visibility = View.GONE
        bindMaster()
        banner.bind()
        rows.removeAllViews()
        live.clear()
        natives.values.forEach { n -> (n.view.parent as? android.view.ViewGroup)?.removeView(n.view); n.reset() }
        // A key no section has now (a pane from before a device restart, another base) is let go of.
        val keys = m.sections.flatMap { nativeKeys(it.items) }.toSet()
        natives.keys.retainAll(keys)
        layOut(s.items)
        scroll.scrollTo(0, 0)
    }

    private fun showPage(v: View) {
        if (page.getChildAt(0) === v) return
        page.removeAllViews()
        page.addView(v, LayoutParams(-1, -2))
    }

    /** The sidebar, from the same [settingsNav] as the Manage page's: each pane's state from its section ([paneRow]). */
    private fun bindRail() {
        val m = model ?: return
        val panes = m.sections.filter { it.id in m.panes }.map { paneRow(it.id, it) }
        sidebar.bind(settingsNav(m.sections, panes), current, overview != null, m.frame)
    }

    private fun bindOverview() {
        val o = overview ?: return
        runCatching { o.status() }.onSuccess { overviewPage.bind(it, o.actions) }
    }

    private var masterToggle: Glass.Toggle? = null
    private var masterBusy = false

    /** The pane's on/off at top right. Asks first when the pane says so; locked while it applies. */
    private fun bindMaster() {
        val s = model?.sections?.firstOrNull { it.id == current }
        val m = s?.master
        if (m == null || s.unavailable() != null) { masterSlot.removeAllViews(); masterToggle = null; return }
        val t = masterToggle?.takeIf { it.tag === m } ?: g.toggle(m.value()) { on -> masterChanged(m, on) }.also {
            it.tag = m
            it.contentDescription = "${s.title} on"
            masterSlot.removeAllViews()
            masterSlot.addView(it)
            masterToggle = it
        }
        if (!masterBusy) t.checked = m.value()
    }

    private fun masterChanged(m: SettingsItem.Switch, on: Boolean) {
        val t = masterToggle ?: return
        if (masterBusy) { t.checked = !on; return }
        val ask = m.confirm(on)
        if (ask == null) { applyMaster(m, on); return }
        t.checked = !on // not until it's answered
        sheet.confirm(ask) { t.checked = on; applyMaster(m, on) }
    }

    private fun applyMaster(m: SettingsItem.Switch, on: Boolean) {
        masterBusy = true
        save({ done -> m.save(on, done) }) { why ->
            masterBusy = false
            if (why != null) { masterToggle?.checked = !on; showProblem(why) }
            else {
                problem.visibility = View.GONE
                if (m.restartsApp) restartPending = true
                reload?.invoke()?.let { model = it }
                build()
            }
        }
    }

    private fun nativeKeys(items: List<SettingsItem>): List<String> = items.flatMap { i ->
        when (i) {
            is SettingsItem.Native -> listOf(i.key)
            is SettingsItem.Group -> nativeKeys(i.items)
            else -> emptyList()
        }
    }

    /** The open page's item called [title] in the model as it is now. */
    private fun now(key: String): SettingsItem? {
        fun find(items: List<SettingsItem>, prefix: String): SettingsItem? = items.firstNotNullOfOrNull { i ->
            if (i is SettingsItem.Group) find(i.items, prefix + i.title + "/") else i.takeIf { prefix + SettingsChange.titleOf(it) == key }
        }
        return model?.sections?.firstOrNull { it.id == current }?.let { find(it.items, "") }
    }

    private fun flat(items: List<SettingsItem>): List<SettingsItem> =
        items.flatMap { if (it is SettingsItem.Group) flat(it.items) else listOf(it) }

    private fun titleOf(i: SettingsItem): String? = when (i) {
        is SettingsItem.Text -> i.title
        is SettingsItem.Switch -> i.title
        is SettingsItem.Slider -> i.title
        is SettingsItem.Duration -> i.title
        is SettingsItem.TimeRange -> i.title
        is SettingsItem.Link -> i.title
        is SettingsItem.Choice -> i.title
        is SettingsItem.SignIn -> i.title
        // A changed action (Connect for Disconnect) draws the page again, with its own run (CodeRabbit on #331).
        is SettingsItem.Action -> i.title
        else -> null
    }

    /**
     * [items] as the approved layout draws them: the rows in cards, one per group with its name at the top,
     * and the rows before any group in a card of their own; "In Home Assistant" as its own card. A status
     * line, tiles and a pane's own view sit between the cards.
     */
    private fun layOut(items: List<SettingsItem>) {
        var open: LinearLayout? = null
        for (i in items) when (i) {
            is SettingsItem.Status, is SettingsItem.Tiles, is SettingsItem.Native -> { addBlock(between(i)); open = null }
            is SettingsItem.States -> { addBlock(states(i)); open = null }
            is SettingsItem.Blueprints -> { addBlock(blueprints(i)); open = null }
            is SettingsItem.Group -> { addBlock(card(i.title).also { c -> i.items.forEach { addRow(c, it, i.title + "/") } }); open = null }
            else -> addRow(open ?: card(null).also { addBlock(it); open = it }, i, "")
        }
    }

    private fun addBlock(v: View) = rows.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(OverviewPage.GAP) })

    private fun between(i: SettingsItem): View = when (i) {
        is SettingsItem.Status -> status(i)
        is SettingsItem.Tiles -> tiles(i)
        else -> item(i)
    }

    /**
     * A card: the same padding on every side, its [title] at the top with a hairline under it, or its first
     * row right at the top (the maintainer's fix to the mockup's spacing, 2026-11-15).
     */
    private fun card(title: String?, aside: String? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = g.surface(dev.glassworks.brand.Tokens.R_TILE.toFloat())
        val p = px(OverviewPage.CARD_PAD)
        setPadding(px(OverviewPage.CARD_PAD + 4f), p, px(OverviewPage.CARD_PAD + 4f), p)
        if (title != null) addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = HEADER
            setPadding(0, 0, 0, px(10f))
            addView(g.text(16.5f, TX, Inter.MEDIUM).apply { text = title }, LinearLayout.LayoutParams(0, -2, 1f))
            aside?.let { addView(g.text(14f, MU).apply { text = it }) }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /**
     * [i] at the end of [c], with a hairline above it unless it is the card's first row. [prefix]: the groups
     * it sits in, `/`-joined, so a row finds itself again by the pane API's key ([SettingsChange.changeable]).
     */
    private fun addRow(c: LinearLayout, i: SettingsItem, prefix: String) {
        if (i is SettingsItem.Group) {
            c.addView(g.label(i.title).apply { tag = LABEL }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18f); bottomMargin = px(4f) })
            i.items.forEach { addRow(c, it, prefix + i.title + "/") }
            return
        }
        val last = c.getChildAt(c.childCount - 1)
        if (last != null && last.tag != LABEL) c.addView(View(context).apply { setBackgroundColor(BD) }, LinearLayout.LayoutParams(-1, px(1f).coerceAtLeast(1)))
        c.addView(when (i) {
            // In a group as at the top: each to its own builder.
            is SettingsItem.States -> states(i)
            is SettingsItem.Blueprints -> blueprints(i)
            is SettingsItem.Status, is SettingsItem.Tiles -> between(i)
            else -> item(i, prefix)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun item(i: SettingsItem, prefix: String = ""): View = when (i) {
        is SettingsItem.Text -> follow(valueRow(i.title, shownOf(i), chevron = i.enabled(), dim = !i.enabled()) { if (i.enabled()) sheet.text(i) { build() } }) { shownOf(i) }
            .also { r -> if (i.kind == SettingsItem.TextKind.COLOR) colourDot(r) { shownOf(i) } }
        is SettingsItem.Switch -> toggleRow(i)
        is SettingsItem.Slider -> sliderRow(i)
        is SettingsItem.Duration -> follow(valueRow(i.title, i.shown(i.value()), chevron = true, dim = false) { sheet.duration(i) { build() } }) { i.shown(i.value()) }
        is SettingsItem.TimeRange -> follow(valueRow(i.title, i.shown(i.value()), chevron = true, dim = false) { sheet.range(i) { build() } }) { i.shown(i.value()) }
        // A link's line comes with the model, made again on each refresh: follow it (the Device page's radio mode).
        is SettingsItem.Link -> follow(row(i.title, i.detail, chevron = true, dim = i.detail.isEmpty() && i.section == null) { i.section?.let(::select) ?: i.open(context) }) {
            (now(prefix + i.title) as? SettingsItem.Link)?.detail ?: i.detail
        }
        is SettingsItem.Note -> g.text(14.5f, MU).apply { text = i.text(); setLineSpacing(0f, 1.25f); setPadding(0, px(14f), 0, px(14f)) }
        is SettingsItem.Info -> infoRow(i)
        is SettingsItem.Choice -> if (i.swatch != null) themes(i) else follow(valueRow(i.title, choiceName(i), chevron = true, dim = false) { sheet.choice(i) { build() } }) { choiceName(i) }
        is SettingsItem.SignIn -> follow(valueRow(i.title, i.shown(i.isSet()), chevron = true, dim = false) { sheet.signIn(i) { build() } }) { i.shown(i.isSet()) }
        is SettingsItem.Action -> actionRow(i, prefix)
        is SettingsItem.Group, is SettingsItem.States, is SettingsItem.Status, is SettingsItem.Tiles, is SettingsItem.Blueprints -> error("laid out by layOut")
        is SettingsItem.Native -> natives.getOrPut(i.key) { i.create(nativeHost) }.also { it.refresh() }.view
    }

    /** The row's value follows a change made elsewhere: Home Assistant (the wake word select) or the Manage page (#242). */
    /** A color row's value with the color itself before it, following it as [follow] follows the text. */
    private fun colourDot(r: LinearLayout, now: () -> String) {
        val value = r.findViewWithTag<TextView>(VALUE) ?: return
        var shown: Int? = null
        fun bind() {
            val c = Themes.colour(now())
            if (c == shown && value.compoundDrawablesRelative[0] != null) return
            shown = c
            val dot = c?.let { GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(it); setStroke(px(1f), BD); setBounds(0, 0, px(14f), px(14f)) } }
            value.setCompoundDrawablesRelative(dot, null, null, null)
            value.compoundDrawablePadding = px(8f)
        }
        bind()
        live += { bind() }
    }

    private fun follow(r: LinearLayout, now: () -> String): LinearLayout = r.also {
        (r.findViewWithTag<TextView>(VALUE))?.let { value ->
            live += { val n = now(); if (value.text != n) { value.text = n; value.visibility = if (n.isEmpty()) View.GONE else View.VISIBLE } }
        }
    }

    /** A name and a status line under it, read again on [refresh] (#212: what plays now). */
    private fun infoRow(i: SettingsItem.Info): View {
        val name = g.text(16.5f, TX)
        val value = g.text(15f, MU).apply { setLineSpacing(0f, 1.15f) }
        fun bind() {
            name.text = i.title()
            val v = i.detail()
            if (value.text != v) value.text = v
            value.setTextColor(if (i.fixable()) AMBER else MU)
            value.visibility = if (v.isEmpty()) View.GONE else View.VISIBLE
        }
        bind()
        live += ::bind
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(OverviewPage.ROW_MIN)
            setPadding(0, px(10f), 0, px(10f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(16f) })
        }
    }

    private fun choiceName(i: SettingsItem.Choice): String = SettingsChange.shown(i)

    /**
     * A choice with swatches as the theme picker (#691): a tap on an orb picks it at once. A pick made elsewhere (the
     * Manage page) while the page is open draws the page again.
     */
    private fun themes(i: SettingsItem.Choice): View {
        val shown = runCatching { i.value() }.getOrNull()
        live += { if (runCatching { i.value() }.getOrNull() != shown) build() }
        return ThemePicker(context, i) { id ->
            save({ done -> i.save(id, done) }) { why -> if (why != null) showProblem(why) else { problem.visibility = View.GONE; build() } }
        }.apply { setPadding(0, px(6f), 0, px(8f)) }
    }

    /**
     * "In Home Assistant" (#212): its label, then a name on the left and its value on the right, one line
     * each. Rows off by default in Home Assistant are dimmed, with a line that says why. Without Home Assistant,
     * a line says so instead (#261). Read again on [refresh].
     */
    private fun states(i: SettingsItem.States): View {
        val box = card(i.title)
        var drawn: List<Any?>? = null
        val values = ArrayList<TextView>()
        fun bind() {
            val now = i.shown()
            val list = now.rows
            val shape = listOf(now.note, now.dimmed) + list.map { it.name }
            if (shape == drawn) {
                list.forEachIndexed { n, r -> if (values[n].text != r.value) values[n].text = r.value }
                return
            }
            drawn = shape
            values.clear()
            while (box.childCount > 1) box.removeViewAt(1)
            now.note?.let { box.addView(g.text(14.5f, MU).apply { text = it; setLineSpacing(0f, 1.25f); setPadding(0, 0, 0, if (list.isEmpty()) 0 else px(10f)) }, LinearLayout.LayoutParams(-1, -2)) }
            list.forEach { r ->
                val dim = r.offByDefault || now.dimmed
                box.addView(View(context).apply { setBackgroundColor(BD) }, LinearLayout.LayoutParams(-1, px(1f).coerceAtLeast(1)))
                box.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = px(OverviewPage.ROW_MIN)
                    addView(g.text(16f, if (dim) MU else TX).apply { text = r.name; setSingleLine(); ellipsize = TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(16f) })
                    addView(g.text(15f, if (dim) FA else MU).apply { text = r.value; setSingleLine(); ellipsize = TextUtils.TruncateAt.END; maxWidth = valueMax }.also { values += it })
                }, LinearLayout.LayoutParams(-1, -2))
            }
            // Every row dimmed for the link: the off-by-default line would explain the wrong thing.
            if (!now.dimmed && list.any { it.offByDefault }) box.addView(g.text(13.5f, FA).apply { text = OFF_BY_DEFAULT; setLineSpacing(0f, 1.2f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        }
        bind()
        live += ::bind
        return box
    }

    /**
     * A pane's blueprints for Home Assistant, as a card of its own: its title, a line that says why the buttons are off or lead to
     * a phone, then each blueprint's name and summary with its button ([dev.glassworks.pane.blueprintCard]).
     * Add and Create automation open Home Assistant's own pages in the dashboard; Add from your phone shows
     * a QR code. Read again on [refresh]: the panel's sign-in check answers after the page opens.
     */
    private fun blueprints(i: SettingsItem.Blueprints): View {
        val box = card(BLUEPRINTS).apply { visibility = View.GONE }
        var drawn: List<Any?>? = null
        fun pill(label: String, on: Boolean, tap: () -> Unit) = g.ghost(label).apply {
            textSize = 15f
            setPadding(px(18f), px(10f), px(18f), px(10f))
            setSingleLine()
            isEnabled = on
            alpha = if (on) 1f else 0.4f
            if (on) setOnClickListener { tap() }
        }
        fun bind() {
            val card = runCatching { i.card(BlueprintSurface.PANEL) }.getOrNull() ?: return
            val now = listOf(card.note) + card.rows.map { it.second }
            if (now == drawn) return
            drawn = now
            box.visibility = View.VISIBLE
            while (box.childCount > 1) box.removeViewAt(1) // the card's title stays
            card.note?.let { note ->
                box.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, px(4f), 0, px(10f))
                    addView(g.dot(FA), LinearLayout.LayoutParams(px(8f), px(8f)).apply { marginEnd = px(10f) })
                    addView(g.text(14.5f, MU).apply { text = note; setLineSpacing(0f, 1.2f) }, LinearLayout.LayoutParams(0, -2, 1f))
                }, LinearLayout.LayoutParams(-1, -2))
            }
            for ((bp, action) in card.rows) {
                box.addView(View(context).apply { setBackgroundColor(BD) }, LinearLayout.LayoutParams(-1, px(1f).coerceAtLeast(1)))
                val right = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                when (action) {
                    is BlueprintAction.Add -> right.addView(pill("Add to Home Assistant", true) { hide(); i.open(action.url) })
                    is BlueprintAction.CreateAutomation -> {
                        right.addView(LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(g.dot(Glass.GREEN), LinearLayout.LayoutParams(px(8f), px(8f)).apply { marginEnd = px(8f) })
                            addView(g.text(14.5f, MU).apply { text = "In Home Assistant"; setSingleLine() })
                        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(14f) })
                        right.addView(pill("Create automation", true) { hide(); i.open(action.url) })
                    }
                    is BlueprintAction.Phone -> right.addView(pill("Add from your phone", true) { sheet.qr(bp.name, action.url) })
                    BlueprintAction.Off -> right.addView(pill("Add to Home Assistant", false) {})
                }
                box.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = px(64f)
                    setPadding(0, px(12f), 0, px(12f))
                    addView(texts(bp.name, bp.summary, TX), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(16f) })
                    addView(right)
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        bind()
        live += ::bind
        return box
    }

    /** The line under the heading: a green dot while the pane works, a grey one otherwise, and its words. */
    private fun status(i: SettingsItem.Status): View {
        val dot = View(context)
        val words = g.text(15f, MU)
        fun bind() {
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i.problem()) ROSE else if (i.ok()) Glass.GREEN else FA) }
            words.text = i.text()
        }
        bind()
        live += ::bind
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(2f), 0, px(6f))
            addView(dot, LinearLayout.LayoutParams(px(8f), px(8f)).apply { marginEnd = px(10f) })
            addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    /** Readings as tiles, [SettingsItem.Tiles.columns] to a row or as many as fit ([TileGrid]); values read again on [refresh]. */
    private fun tiles(i: SettingsItem.Tiles): View {
        val grid = TileGrid(context, i.columns, gap = px(if (i.columns >= 4) 10f else 12f), minWidth = px(TILE_MIN_DP))
        val views = ArrayList<TileView>()
        fun bind() {
            val list = i.tiles()
            if (list.size != views.size) {
                views.clear()
                grid.removeAllViews()
                val graph = list.any { it.history != null || it.meter != null }
                repeat(list.size) { grid.addView(TileView(context, if (i.columns >= 4) 22f else 27f, graph).also { views += it }) }
            }
            list.forEachIndexed { n, t -> views[n].bind(t) }
        }
        bind()
        live += ::bind
        return grid
    }

    /**
     * Tiles in rows of [asked], or of as many as fit the width it's given ([tileColumns]), each row as tall as its
     * tallest tile. Worked out at each measure, so a turn of the screen that keeps the activity re-flows them.
     */
    private class TileGrid(context: Context, private val asked: Int, private val gap: Int, private val minWidth: Int) : ViewGroup(context) {
        private var columns = 1
        private var tileWidth = 0

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = MeasureSpec.getSize(widthSpec)
            columns = tileColumns(asked, width, minWidth, gap)
            tileWidth = (width - gap * (columns - 1)) / columns
            var height = 0
            for (row in (0 until childCount).chunked(columns)) {
                val exact = MeasureSpec.makeMeasureSpec(tileWidth, MeasureSpec.EXACTLY)
                row.forEach { getChildAt(it).measure(exact, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)) }
                val rowHeight = row.maxOf { getChildAt(it).measuredHeight }
                row.forEach { getChildAt(it).measure(exact, MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)) }
                height += (if (height > 0) gap else 0) + rowHeight
            }
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var top = 0
            for (row in (0 until childCount).chunked(columns)) {
                row.forEachIndexed { c, n ->
                    val v = getChildAt(n)
                    val left = c * (tileWidth + gap)
                    v.layout(left, top, left + v.measuredWidth, top + v.measuredHeight)
                }
                top += getChildAt(row.first()).measuredHeight + gap
            }
        }
    }

    private fun shownOf(i: SettingsItem.Text): String = SettingsChange.shown(i)

    /** A name, a line under it and, when it opens something, a chevron. [dim]: a quieter row (Aqara's own settings). */
    private fun row(name: String, value: String, chevron: Boolean, dim: Boolean, onClick: (() -> Unit)?) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(OverviewPage.ROW_MIN)
        setPadding(0, px(10f), 0, px(10f))
        addView(texts(name, value, if (dim) MU else TX), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(16f) })
        if (chevron) addView(chevron())
        if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
    }

    /** A name on the left, its value on the right and, when it opens something, a chevron (the approved layout's rows). */
    private fun valueRow(name: String, value: String, chevron: Boolean, dim: Boolean, onClick: (() -> Unit)?) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(OverviewPage.ROW_MIN)
        setPadding(0, px(10f), 0, px(10f))
        addView(g.text(16.5f, if (dim) MU else TX).apply { text = name }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(16f) })
        // Always there, gone while empty: a row that follows a change ([follow]) can fill it later.
        addView(g.text(15f, MU).apply {
            text = value; tag = VALUE; gravity = Gravity.END; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; maxWidth = valueMax
            visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        })
        if (chevron) addView(chevron())
        if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
    }

    private fun chevron() = g.text(24f, FA, Inter.LIGHT).apply { text = "›"; setPadding(px(12f), 0, 0, 0) }

    private fun toggleRow(i: SettingsItem.Switch): View {
        lateinit var t: Glass.Toggle
        var saving = false
        fun apply(on: Boolean) {
            saving = true
            save({ done -> i.save(on, done) }) { why ->
                saving = false
                if (why != null) { t.checked = !on; showProblem(why) }
                else {
                    problem.visibility = View.GONE // what went wrong before is over
                    if (i.restartsApp) { restartPending = true; banner.bind() }
                }
            }
        }
        t = g.toggle(i.value()) { on ->
            val ask = i.confirm(on)
            if (ask == null) apply(on)
            else { t.checked = !on; sheet.confirm(ask) { t.checked = on; apply(on) } }
        }
        // Changed elsewhere while the page is open (Home Assistant, the Manage page, a card above it): follow, unless a change is on its way.
        live += { if (!saving && !sheet.isOpen) i.value().let { v -> if (t.checked != v) t.checked = v } }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(OverviewPage.ROW_MIN)
            setPadding(0, px(10f), 0, px(10f))
            addView(texts(i.title, i.detail, TX), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(16f) })
            addView(t)
            isClickable = true
            setOnClickListener { t.performClick() }
        }
    }

    /**
     * A name, its line, and a button at the end (#269); one that asks first asks in the sheet. Its line, its
     * button's word and its run are the model's action as it is now: a reload can bring the same title with another detail and
     * run (Home Assistant's zone changed; CodeRabbit on #331). Done, the page is drawn again from the model:
     * what the action changed shows at once.
     */
    private fun actionRow(i: SettingsItem.Action, prefix: String): View {
        val button = g.ghost(i.label).apply { setPadding(px(18f), px(8f), px(18f), px(8f)) }
        fun current() = now(prefix + i.title) as? SettingsItem.Action ?: i
        fun run(a: SettingsItem.Action) {
            button.isEnabled = false
            save({ done -> a.run(done) }) { why ->
                button.isEnabled = true
                if (why != null) showProblem(why)
                else {
                    problem.visibility = View.GONE
                    reload?.invoke()?.let { model = it }
                    build()
                }
            }
        }
        button.setOnClickListener { val a = current(); a.confirm?.let { sheet.confirm(it) { run(a) } } ?: run(a) }
        return follow(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(OverviewPage.ROW_MIN)
            setPadding(0, px(10f), 0, px(10f))
            addView(texts(i.title, i.detail(), TX), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(16f) })
            addView(button)
        }) { val a = current(); if (button.text != a.label) button.text = a.label; a.detail() }
    }

    /** The name with the value on its right, the slider under them; saved when let go. */
    private fun sliderRow(i: SettingsItem.Slider): View {
        val shown = g.text(15f, MU).apply { text = i.shown(i.value()) }
        val slider = GlassSlider(context, i.min, i.max, i.step).apply {
            value = i.value()
            contentDescription = i.title
            onMove = { v -> shown.text = i.shown(v) }
            onSet = { v ->
                if (v != i.value()) save({ done -> i.save(v, done) }) { why ->
                    if (why != null) { showProblem(why); value = i.value(); shown.text = i.shown(value) }
                    else problem.visibility = View.GONE
                }
            }
        }
        // A value changed elsewhere (Home Assistant set the volume) moves the slider too, unless it is held.
        live += {
            val v = i.value()
            if (!slider.dragging && v != slider.value) { slider.value = v; shown.text = i.shown(v) }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(14f), 0, px(8f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(g.text(16.5f, TX).apply { text = i.title }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(shown)
            }, LinearLayout.LayoutParams(-1, -2))
            addView(slider, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
        }
    }

    private fun texts(name: String, value: String, color: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(g.text(16.5f, color).apply { text = name })
        // Always there, gone while empty: a row that follows a change ([follow]) can fill it later.
        addView(g.text(15f, MU).apply { text = value; tag = VALUE; setLineSpacing(0f, 1.15f); visibility = if (value.isEmpty()) View.GONE else View.VISIBLE },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
    }

    /**
     * Runs a save and hands its answer to [after] on the main thread. A save that throws is an answer
     * too (#63 F8): the menu says what went wrong instead of closing the app.
     */
    private fun save(run: ((String?) -> Unit) -> Unit, after: (String?) -> Unit) {
        val answer: (String?) -> Unit = { why -> post { after(why) } }
        runCatching { run(answer) }.onFailure { answer(couldNot(it)) }
    }

    private fun showProblem(why: String) {
        problem.text = why
        problem.visibility = View.VISIBLE
    }

    /** Scrolls [v] (a native view's field) near the top of the page, above where the keyboard comes up. */
    internal fun reveal(v: View) {
        if (!isShowing) return
        val r = Rect()
        v.getDrawingRect(r)
        runCatching { scroll.offsetDescendantRectToMyCoords(v, r) }.onFailure { return }
        scroll.smoothScrollTo(0, (r.top - px(60f)).coerceAtLeast(0))
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(windowToken, 0)
    }

    // ------------------------------------------------------------------ pieces

    /** #33's "restart needed": an amber line with the one white button, while a change waits for it. */
    private inner class Banner {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 20 * g.dp
                setColor(Color.argb(20, 251, 191, 36))
                setStroke(px(1f).coerceAtLeast(1), Color.argb(90, 251, 191, 36))
            }
            setPadding(px(18f), px(10f), px(10f), px(10f))
            addView(g.dot(AMBER), LinearLayout.LayoutParams(px(9f), px(9f)).apply { marginEnd = px(13f) })
            addView(g.text(15.5f, TX).apply { text = RESTART_TEXT; setLineSpacing(0f, 1.2f) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(12f) })
            addView(g.primary("Restart now").apply { setPadding(px(22f), px(11f), px(22f), px(11f)); setOnClickListener { onRestart?.invoke() } })
        }

        fun bind() { view.visibility = if (restartPending) View.VISIBLE else View.GONE }
    }

    /**
     * The sheet a row opens (not in the design: the rows' chevrons open it): a typed value, a duration's
     * choices, a time window, or a question before a change. It sits near the top, so the keyboard
     * never covers it.
     */
    private inner class Sheet {
        private val name = g.heading(26f)
        private val hint = g.text(15f, MU).apply { setLineSpacing(0f, 1.2f) }
        private fun edit() = EditText(context).apply {
            textSize = 18f
            typeface = Inter.typeface(context, Inter.REGULAR, 18f)
            setTextColor(TX)
            setHintTextColor(FA)
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine()
            background = g.surface(16f, Glass.S2)
            setPadding(px(18f), px(14f), px(18f), px(14f))
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { onSave?.invoke(); true } else false }
        }
        val field = edit()
        /** A sign-in's new password ([signIn]): always empty when the sheet opens, emptied when it closes. */
        val password = edit()
        private fun clearPassword() {
            password.setText("")
            password.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            password.typeface = Inter.typeface(context, Inter.REGULAR, 18f) // the password type sets monospace
        }
        private val passwordRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        /** What the sheet shows under its name: the field, the choices or the window. */
        private val body = FrameLayout(context)
        private val why = g.text(15f, ROSE)
        private val cancel = g.plain("Cancel").apply { setOnClickListener { close() } }
        private val saveButton = g.primary("Save").apply { setOnClickListener { onSave?.invoke() } }
        private var onSave: (() -> Unit)? = null
        /** A sign-in's Clear, while one is set. */
        private val clearButton = g.plain("Clear").apply { setOnClickListener { onClear?.invoke() } }
        private var onClear: (() -> Unit)? = null
        private val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(cancel, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(14f) })
            addView(clearButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(14f) })
            addView(saveButton)
        }
        private val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = g.surface(28f, Color.parseColor("#17181e"))
            setPadding(px(30f), px(26f), px(30f), px(22f))
            isClickable = true
            addView(name)
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
            addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18f) })
            addView(why, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
            addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) })
        }
        val view = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(170, 10, 11, 15))
            isClickable = true
            setOnClickListener { close() }
            // 560 dp, or the screen less a margin on a narrow portrait screen.
            addView(card, LayoutParams(minOf(px(560f), context.resources.displayMetrics.widthPixels - px(32f)), -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = px(40f) })
            visibility = View.GONE
        }

        private fun open(title: String, hintText: String, content: View?, action: String?) {
            name.text = title
            hint.text = hintText
            hint.visibility = if (hintText.isEmpty()) View.GONE else View.VISIBLE
            (field.parent as? android.view.ViewGroup)?.removeView(field)
            passwordRow.removeAllViews()
            (passwordRow.parent as? android.view.ViewGroup)?.removeView(passwordRow)
            clearPassword()
            clearButton.visibility = View.GONE
            onClear = null
            body.removeAllViews()
            content?.let { body.addView(it, LayoutParams(-1, -2)) }
            body.visibility = if (content != null) View.VISIBLE else View.GONE
            why.visibility = View.GONE
            cancel.visibility = View.VISIBLE
            saveButton.visibility = if (action != null) View.VISIBLE else View.GONE
            saveButton.text = action.orEmpty()
            busy(false)
            view.visibility = View.VISIBLE
            view.bringToFront()
        }

        /** The color sheet's content while it is open ([text] for a [SettingsItem.TextKind.COLOR]). */
        private var colour: ColourField? = null

        private fun busy(on: Boolean) { saveButton.isEnabled = !on; saveButton.alpha = if (on) 0.5f else 1f }

        /** Runs [run]; closes the sheet and calls [after] once it applied, or says why not. */
        private fun commit(restarts: Boolean, after: () -> Unit, run: ((String?) -> Unit) -> Unit) {
            busy(true)
            val open = onSave
            save(run) { problem ->
                // A change that saved needs its restart, also when the sheet was closed meanwhile.
                if (problem == null && restarts) { restartPending = true; banner.bind() }
                if (onSave !== open || view.visibility != View.VISIBLE) return@save
                if (problem != null) { why.text = problem; why.visibility = View.VISIBLE; busy(false) }
                else {
                    this@KioskSettingsOverlay.problem.visibility = View.GONE
                    close()
                    after()
                }
            }
        }

        fun text(i: SettingsItem.Text, after: () -> Unit) {
            field.inputType = when (i.kind) {
                SettingsItem.TextKind.PLAIN -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                SettingsItem.TextKind.ADDRESS -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                SettingsItem.TextKind.NUMBER -> InputType.TYPE_CLASS_NUMBER
                SettingsItem.TextKind.DECIMAL -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                SettingsItem.TextKind.COLOR -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            // Checked as the Manage page checks it (#242): one path for a typed value.
            onSave = { commit(i.restartsApp, after) { done -> SettingsChange.change(i, field.text?.toString().orEmpty(), done) } }
            colour?.release(); colour = null
            field.setText(i.value())
            if (i.kind == SettingsItem.TextKind.COLOR) {
                // Swatches to tap, and the field for an exact color; no keyboard until the field is tapped.
                open(i.title, "", null, "Save")
                val c = ColourField(context, runCatching { i.suggestions() }.getOrDefault(emptyList()), field).also { colour = it }
                body.addView(c, LayoutParams(-1, -2)); body.visibility = View.VISIBLE
                field.hint = i.hint
                field.setSelection(field.text.length)
                return
            }
            open(i.title, i.hint, field, "Save")
            field.hint = i.hint
            field.setSelection(field.text.length)
            // The keyboard once the field has focus and is laid out: asked at once, it didn't come up on
            // the panel (#63 round 2).
            field.requestFocus()
            field.post {
                field.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        /**
         * A user name and a password, saved together after [SettingsChange.signIn]'s checks. The stored password
         * never comes back: while one is set, the field takes a new one, an empty field keeps it, and Clear removes it.
         */
        fun signIn(i: SettingsItem.SignIn, after: () -> Unit) {
            val fields = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val set = i.isSet()
            // open() takes the fields out of the last sheet: add them to this one after it.
            open(i.title, "", fields, "Save")
            fields.addView(field, LinearLayout.LayoutParams(-1, -2))
            passwordRow.addView(password, LinearLayout.LayoutParams(0, -2, 1f))
            password.hint = if (set) SettingsChange.NEW_PASSWORD else "Password"
            fields.addView(passwordRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            field.hint = "User name"
            field.setText(i.user().orEmpty())
            onSave = { commit(false, after) { done -> SettingsChange.signIn(i, field.text?.toString().orEmpty(), password.text?.toString().orEmpty(), done) } }
            if (set) {
                clearButton.visibility = View.VISIBLE
                onClear = { commit(false, after) { done -> SettingsChange.clear(i, done) } }
            }
            field.requestFocus()
        }

        /** The choices as chips; picking one applies it. */
        fun duration(i: SettingsItem.Duration, after: () -> Unit) {
            val now = i.value()
            val choices = (i.choices + now).distinct().sortedWith(compareBy<Int> { it == 0 }.thenBy { it })
            val chips = Wrap(context, px(10f), px(10f))
            onSave = null
            choices.forEach { sec ->
                chips.addView(g.chip(i.shown(sec).removePrefix("After ")).apply {
                    chosen = sec == now
                    setOnClickListener { onSave = {}; commit(false, after) { done -> i.save(sec, done) } }
                })
            }
            open(i.title, "", chips, null)
        }

        /** On or off, then From and To, each a step back or forward; Save applies both. */
        fun range(i: SettingsItem.TimeRange, after: () -> Unit) {
            val (from0, to0) = parseRange(i.value()) ?: (22 * 60 to 7 * 60)
            var from = from0; var to = to0
            var on = i.value().isNotBlank()
            val fromText = g.text(22f, TX).apply { gravity = Gravity.CENTER }
            val toText = g.text(22f, TX).apply { gravity = Gravity.CENTER }
            val times = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            fun bind() { fromText.text = hhmm(from); toText.text = hhmm(to); times.alpha = if (on) 1f else 0.4f }
            fun stepper(label: String, shown: TextView, change: (Int) -> Unit) = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(g.text(16.5f, MU).apply { text = label }, LinearLayout.LayoutParams(0, -2, 1f))
                fun step(glyph: String, desc: String, d: Int) = g.ghost(glyph).apply {
                    setPadding(0, 0, 0, 0); gravity = Gravity.CENTER; textSize = 22f
                    contentDescription = "$label $desc"
                    setOnClickListener { if (on) { change(d); bind() } }
                }
                addView(step("−", "earlier", -i.stepMin), LinearLayout.LayoutParams(px(56f), px(44f)))
                addView(shown, LinearLayout.LayoutParams(px(100f), -2))
                addView(step("+", "later", i.stepMin), LinearLayout.LayoutParams(px(56f), px(44f)))
            }
            times.addView(stepper("From", fromText) { d -> from = Math.floorMod(from + d, 24 * 60) }, LinearLayout.LayoutParams(-1, -2))
            times.addView(stepper("To", toText) { d -> to = Math.floorMod(to + d, 24 * 60) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(g.text(16.5f, TX).apply { text = "On" }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(g.toggle(on) { v -> on = v; bind() })
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(14f) })
                addView(times, LinearLayout.LayoutParams(-1, -2))
            }
            bind()
            onSave = { commit(false, after) { done -> i.save(if (on) "${hhmm(from)}-${hhmm(to)}" else "", done) } }
            open(i.title, "", content, "Save")
        }

        /** The options as chips; picking one applies it. */
        fun choice(i: SettingsItem.Choice, after: () -> Unit) {
            val now = i.value()
            val chips = Wrap(context, px(10f), px(10f))
            onSave = null
            i.options().forEach { (id, name) ->
                chips.addView(g.chip(name).apply {
                    chosen = id == now
                    setOnClickListener {
                        // One that asks first (the radio's role, #269): the question replaces the list, then it applies.
                        val ask = i.confirm(id)
                        if (ask == null) { onSave = {}; commit(false, after) { done -> i.save(id, done) } }
                        else confirm(ask) { save({ done -> i.save(id, done) }) { why -> after(); if (why != null) showProblem(why) } } // rebuild first: it hides the problem line
                    }
                })
            }
            open(i.title, "", chips, null)
        }

        /**
         * A blueprint's import as a QR code, for a phone signed in to Home Assistant as an administrator: the
         * panel's own sign-in isn't one. [url] is My Home Assistant's link, which the phone app opens signed in.
         */
        fun qr(blueprint: String, url: String) {
            val words = g.text(15f, MU).apply {
                text = "The panel's Home Assistant user isn't an administrator, so it can't add blueprints.\n\n" +
                    "Scan with a phone signed in to Home Assistant as an administrator. It opens Home Assistant's import dialog with $blueprint filled in."
                setLineSpacing(0f, 1.25f)
            }
            val code = FrameLayout(context).apply {
                background = g.surface(14f, Color.WHITE, Color.TRANSPARENT)
                setPadding(px(12f), px(12f), px(12f), px(12f))
                addView(ImageView(context).apply { setImageBitmap(QrCode.bitmap(url, px(176f))); contentDescription = "QR code: add $blueprint" }, LayoutParams(px(176f), px(176f)))
            }
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(24f) })
                addView(code, LinearLayout.LayoutParams(-2, -2))
            }
            onSave = { close() }
            open("Add it from your phone", "", content, "Done")
            cancel.visibility = View.GONE
        }

        /** A question before a change: its words, Cancel and the action. */
        fun confirm(c: SettingsItem.Confirm, yes: () -> Unit) {
            onSave = { close(); yes() }
            open(c.title, c.detail, null, c.action)
        }

        val isOpen: Boolean get() = view.visibility == View.VISIBLE

        fun close() {
            if (view.visibility != View.VISIBLE) return
            onSave = null
            clearPassword()
            colour?.release(); colour = null
            hideKeyboard()
            view.visibility = View.GONE
        }
    }

    companion object {
        const val RESTART_TEXT = SettingsChange.RESTART_TEXT
        const val OFF_BY_DEFAULT = SettingsChange.OFF_BY_DEFAULT
        /** Tags: a card's title row, a group's name inside a card, and a row's value (what [follow] updates). */
        private const val HEADER = "header"
        private const val LABEL = "label"
        private const val VALUE = "value"

        /** The narrowest a tile gets before its value and unit wrap and clip: "654 MiB free" needs about 110 dp inside it. */
        const val TILE_MIN_DP = 150f

        /**
         * The most tiles, up to [asked], that fit a row [width] wide at [minWidth] each with [gap] between them; one
         * at least. The Mirror's page in portrait, 268 dp: one.
         */
        fun tileColumns(asked: Int, width: Int, minWidth: Int, gap: Int): Int =
            (asked downTo 1).firstOrNull { n -> n * minWidth + (n - 1) * gap <= width } ?: 1
    }
}
