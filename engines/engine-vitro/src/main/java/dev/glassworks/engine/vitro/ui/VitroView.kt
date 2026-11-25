/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.AlarmPanelCardView
import dev.glassworks.engine.vitro.cards.CustomButtonCardView
import dev.glassworks.engine.vitro.cards.HaErrorCardView
import dev.glassworks.engine.vitro.cards.LegacyWeatherCardView
import dev.glassworks.engine.vitro.cards.SwipeCardView
import dev.glassworks.engine.vitro.cards.AreaCardView
import dev.glassworks.engine.vitro.cards.AutoEntitiesCardView
import dev.glassworks.engine.vitro.cards.CardBuilder
import dev.glassworks.engine.vitro.cards.LayoutCardView
import dev.glassworks.engine.vitro.cards.AlertCardView
import dev.glassworks.engine.vitro.cards.DateCardView
import dev.glassworks.engine.vitro.cards.IframeCardView
import dev.glassworks.engine.vitro.cards.PlantStatusCardView
import dev.glassworks.engine.vitro.cards.ShoppingListCardView
import dev.glassworks.engine.vitro.cards.ShortcutCardView
import dev.glassworks.engine.vitro.cards.SmallCardConfigs
import dev.glassworks.engine.vitro.cards.StatisticCardView
import dev.glassworks.engine.vitro.cards.ToggleGroupCardView
import dev.glassworks.engine.vitro.cards.MiniMediaPlayerCardView
import dev.glassworks.engine.vitro.cards.MushroomAlarmControlPanelCardView
import dev.glassworks.engine.vitro.cards.MushroomHumidifierCardView
import dev.glassworks.engine.vitro.cards.MushroomLockCardView
import dev.glassworks.engine.vitro.cards.MushroomMediaPlayerCardView
import dev.glassworks.engine.vitro.cards.MushroomNumberCardView
import dev.glassworks.engine.vitro.cards.MushroomSelectCardView
import dev.glassworks.engine.vitro.cards.MushroomVacuumCardView
import dev.glassworks.engine.vitro.cards.StackInCardView
import dev.glassworks.engine.vitro.cards.VerticalStackInCardView
import dev.glassworks.engine.vitro.cards.BarCardView
import dev.glassworks.engine.vitro.cards.BubbleCardView
import dev.glassworks.engine.vitro.cards.ButtonCardView
import dev.glassworks.engine.vitro.cards.CalendarCardView
import dev.glassworks.engine.vitro.cards.ClockCardView
import dev.glassworks.engine.vitro.cards.EntitiesCardView
import dev.glassworks.engine.vitro.cards.EntityCardView
import dev.glassworks.engine.vitro.cards.GaugeCardView
import dev.glassworks.engine.vitro.cards.GlanceCardView
import dev.glassworks.engine.vitro.cards.HeadingCardView
import dev.glassworks.engine.vitro.cards.HumidifierCardView
import dev.glassworks.engine.vitro.cards.LightCardView
import dev.glassworks.engine.vitro.cards.LogbookCardView
import dev.glassworks.engine.vitro.cards.MarkdownCardView
import dev.glassworks.engine.vitro.cards.MediaControlCardView
import dev.glassworks.engine.vitro.cards.MushroomTitleCardView
import dev.glassworks.engine.vitro.cards.ModernCircularGaugeCardView
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.cards.MushroomChipsCardView
import dev.glassworks.engine.vitro.cards.MushroomClimateCardView
import dev.glassworks.engine.vitro.cards.MushroomCoverCardView
import dev.glassworks.engine.vitro.cards.MushroomFanCardView
import dev.glassworks.engine.vitro.cards.MushroomLightCardView
import dev.glassworks.engine.vitro.cards.PictureCardView
import dev.glassworks.engine.vitro.cards.PictureElementsCardView
import dev.glassworks.engine.vitro.cards.HistoryGraphCardView
import dev.glassworks.engine.vitro.cards.SensorGraphCardView
import dev.glassworks.engine.vitro.cards.StatisticsGraphCardView
import dev.glassworks.engine.vitro.cards.ThermostatCardView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.cards.TodoListCardView
import dev.glassworks.engine.vitro.cards.WeatherCardView
import dev.glassworks.engine.Page
import dev.glassworks.engine.vitro.cards.WebIslandCardView
import dev.glassworks.engine.vitro.cards.IslandCard
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HistoryPoint
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.EntityFilterConfig
import dev.glassworks.engine.vitro.fonts.WebFonts
import dev.glassworks.engine.vitro.clarity.VitroClarity
import dev.glassworks.engine.vitro.compat.CompatChecker
import dev.glassworks.engine.vitro.compat.IslandScripts
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.CardTier
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.model.SectionConfig
import dev.glassworks.engine.vitro.model.ViewConfig
import dev.glassworks.engine.vitro.model.VisibilityCondition
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import dev.glassworks.engine.vitro.theme.CardModStyle
import dev.glassworks.engine.vitro.theme.CssBackground
import dev.glassworks.engine.vitro.theme.ThemeBackground
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.ui.Inter
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/** Snapshot of Vitro's rendering telemetry for the currently active view. */
data class VitroMetrics(
    val viewTitle: String = "",
    val viewType: String = "",
    val buildTimeMs: Long = 0L,
    val nativeCoreCards: Int = 0,
    val nativeHacsCards: Int = 0,
    val webIslandCards: Int = 0,
    val subscribedEntities: Int = 0,
    val cacheHit: Boolean = false,
    /** Every card in the view that falls back to an island, with why (`CompatChecker`). */
    val islands: List<String> = emptyList(),
)

/**
 * Root native Android dashboard view for Vitro.
 *
 * Maintains a keep-alive cache of built views so view switches happen in ~16 ms instead of
 * tearing down and recreating cards, renders HA Sections / Masonry / Panel layouts directly
 * using native Views & Canvas, and only allocates [WebIslandCardView] when a view actually
 * contains an unrecognized `custom:*` card.
 */
class VitroView(
    context: Context,
    private val store: EntityStore,
    /** The page's own color: what shows before (and between) Home Assistants' themes. */
    private val neutralBackground: Int,
) : FrameLayout(context) {
    private val dp = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()


    private val shell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /**
     * The theme's backdrop when it is a picture (gradients, layers, an image): its own view under everything, on a
     * hardware layer, so the GPU rasterizes it once per size or theme and every later frame (a live value changing)
     * only composites it. Drawn on the root, it was shaded again on every frame. A solid color stays the root's.
     */
    private val backdrop = View(context).apply { setLayerType(LAYER_TYPE_HARDWARE, null) }
    private val tabScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        visibility = GONE
    }
    private val tabBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val contentHost = FrameLayout(context)

    /**
     * Viewport-pinned cards (`card_mod` `position: fixed`), above the scrolling content and below the More-Info sheet
     * and the connection banner. Holds one frame per built view, shown and hidden with it like [contentHost].
     */
    val overlayLayer: FrameLayout = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
    }
    private val overlayFrames = HashMap<Int, FrameLayout>()

    /** Pinned cards collected while [buildViewTree] runs, with their layout params in the overlay. */
    private var pendingPinned: MutableList<Pair<View, FrameLayout.LayoutParams>>? = null
    private val statusLabel = TextView(context).apply {
        textSize = 14f
        setTextColor(0xFFA1A1AA.toInt())
        gravity = Gravity.CENTER
        typeface = VitroFonts.typeface(context, Inter.REGULAR, 14f)
    }

    /** One top-bar tab and the dashboard view it opens. */
    private class TabEntry(val viewIndex: Int, val label: TextView, val icon: MdiIconView?, val indicator: View)

    private val tabs = ArrayList<TabEntry>()
    private var moreInfo: MoreInfoSheet? = null
    private val viewCache = LinkedHashMap<Int, View>()

    /** For each cached view, the scripts its islands could load when it was built ([IslandScripts]). */
    private val builtScripts = HashMap<Int, IslandScripts>()

    /** Views the user came from, newest last; [goBack] pops it. Capped at [MAX_BACK_STACK]. */
    private val viewBackStack = ArrayDeque<Int>()

    private val bannerText = TextView(context).apply {
        text = "Connection lost. Reconnecting…"
        textSize = 13f
        setTextColor(0xFFFFFFFF.toInt())
        typeface = VitroFonts.typeface(context, Inter.MEDIUM, 13f)
        includeFontPadding = false
    }
    private val connectionBanner = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(16f), px(10f), px(18f), px(10f))
        background = GradientDrawable().apply {
            cornerRadius = 999f * dp
            setColor(0xEB2B2B2B.toInt())
        }
        elevation = 6f * dp
        visibility = GONE
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFB300.toInt())
            }
        }
        addView(dot, LinearLayout.LayoutParams(px(8f), px(8f)).apply { marginEnd = px(10f) })
        addView(bannerText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    var dashboard: DashboardConfig? = null
        private set
    var currentViewIndex: Int = 0
        private set
    var themeCatalog: ThemeCatalog = ThemeCatalog()
        private set
    var userThemeName: String? = null
        private set
    /** Follows Android's night mode, like Home Assistant's own auto light/dark; callers can still override it. */
    var darkMode: Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    var hideHeader: Boolean = true
        set(v) {
            if (field == v) return
            field = v
            updateTabVisibility()
            // Subviews draw their back row only while the header shows: drop the cached ones, rebuild the current.
            val views = dashboard?.views ?: return
            viewCache.keys.filter { views.getOrNull(it)?.subview == true && it != currentViewIndex }.forEach { idx ->
                dropView(idx, keepIslands = false)
            }
            if (views.getOrNull(currentViewIndex)?.subview == true) renderView(currentViewIndex, forceRebuild = true)
        }
    /**
     * The dashboard's Lovelace resources. Islands load them into their page, and a card-mod-only fallback routes on
     * whether card-mod is among them, so a change rebuilds the cached views and the current one. The island server
     * reads it on its own threads (the folders its proxy forwards).
     */
    @Volatile var resourcesList: List<LovelaceResource> = emptyList()
        set(v) {
            if (field == v) return
            field = v
            // The islands load the resources into their page: new resources need new pages, pooled ones too.
            dropBuiltViews(keepIslands = false)
            closePooledIslands()
            dashboard?.let { renderView(currentViewIndex, forceRebuild = true) }
        }
    var lastMetrics: VitroMetrics = VitroMetrics()
        private set

    var onMetrics: ((VitroMetrics) -> Unit)? = null

    /**
     * Clarity's native harness while a Clarity run shows its reference dashboard ([VitroClarity], started by the
     * engine); null otherwise, and then every hook below is one null check.
     */
    internal var clarity: VitroClarity? = null
        private set

    /**
     * Gives the view Clarity's harness ([VitroClarity]), or takes it away (null). A harness learns each view's cards as
     * the view is built, so the views built before it (cached) are dropped: the load that attaches it renders its view
     * next (a tab-like switch or a new dashboard), built with the harness there, and none is measured without its cards.
     * Only when a harness attaches; tab switches within its run keep the cache.
     */
    internal fun attachClarity(c: VitroClarity?) {
        clarity = c
        if (c == null || viewCache.isEmpty()) return
        // The render that follows takes the islands back (the same cards), or closes them.
        dropBuiltViews(keepIslands = true)
    }

    /** The view whose cards [buildViewTree] is building (Clarity's card registry). */
    private var buildingIndex = -1
    var onViewSelected: ((ViewConfig) -> Unit)? = null

    /**
     * Shows a view Vitro doesn't draw ([ViewConfig.webFallbackReason]: a strategy, a built-in card or row it has no
     * renderer for) in Home Assistant's own frontend, with the reason. Such a view is never built: no empty page and
     * no island for a card the island can't draw. Null builds every view as it is (a view with no page).
     */
    var onWebView: ((ViewConfig, String) -> Unit)? = null

    /** A tab tap is about to switch views: the page records where it was, in the same history as its loads. */
    var onTabNavigation: (() -> Unit)? = null

    /**
     * The owning page's back (its one history of loads and tabs): true when it went back. A subview's back arrow
     * uses it, so the arrow and the host's back key walk the same history. Null on a view with no page.
     */
    var pageBack: (() -> Boolean)? = null
    var onThemeResolved: ((ResolvedTheme) -> Unit)? = null
    var contextFactory: ((ResolvedTheme) -> VitroCardContext)? = null

    /** Visibility re-checks for `time` and `user` conditions, run just after each minute boundary. */
    private val periodicChecks = LinkedHashSet<() -> Unit>()
    private val periodicTick = object : Runnable {
        override fun run() {
            periodicChecks.toList().forEach { it() }
            scheduleTick()
        }
    }

    /**
     * A web font arrived (or the stylesheets changed what a drawn family resolves to): text drawn in the fallback
     * meanwhile draws again, as the browser swaps a `font-display: swap` font in. Weakly held: the font loader lives
     * as long as the process.
     */
    private val fontListener: () -> Unit = run {
        val self = java.lang.ref.WeakReference(this)
        lateinit var listener: () -> Unit
        listener = {
            val view = self.get()
            if (view == null) WebFonts.removeListener(listener) else view.redrawForFonts()
        }
        listener
    }

    /** A font arrived while this view wasn't on screen: it draws again when it's attached. */
    private var fontsStale = false

    /** The server and resources this view last set the font loader up for. */
    private var fontsFor: Pair<String, List<LovelaceResource>>? = null

    private fun redrawForFonts() {
        // Only the view on screen redraws (and so asks for fonts), and only for its own dashboard's fonts: a redraw
        // sets the loader up for this view, which would take it away from the view that asked. Another view catches
        // up when it's attached again.
        val mine = fontsFor ?: return
        if (!isAttachedToWindow || !WebFonts.isConfiguredFor(mine.first, mine.second)) {
            fontsStale = true
            return
        }
        rebuildForFonts()
    }

    /** Builds the current view again, which also sets the font loader up for this view's dashboard. */
    private fun rebuildForFonts() {
        fontsStale = false
        val cfg = dashboard ?: return
        if (cfg.views.getOrNull(currentViewIndex) == null) return
        dropBuiltViews(keepIslands = true)
        renderView(currentViewIndex, forceRebuild = true)
    }

    init {
        WebFonts.addListener(fontListener)
        MdiIcons.init(context)
        // Keyboard focus and HA's roles for every control, while TalkBack or a keyboard is there to use them.
        VitroAccess.install(this)
        setBackgroundColor(neutralBackground)
        addView(backdrop, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        tabScroll.addView(tabBar, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        shell.addView(tabScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        shell.addView(contentHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        addView(shell, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(overlayLayer, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(statusLabel, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(
            connectionBanner,
            LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(16f) },
        )
    }

    /** Shows a small floating pill (e.g. "Connection lost. Reconnecting…") while [message] is non-blank; hides it otherwise. */
    fun setConnectionBanner(message: String?) {
        if (message.isNullOrBlank()) {
            connectionBanner.visibility = GONE
            return
        }
        bannerText.text = message
        connectionBanner.visibility = VISIBLE
        connectionBanner.bringToFront()
    }

    /**
     * Back navigation for the host's back key: closes the More-Info sheet first, then returns to the view the user
     * came from. False when there is nothing to go back to.
     */
    fun goBack(): Boolean {
        if (closeMoreInfo()) return true
        if (viewBackStack.isEmpty()) return false
        val prev = viewBackStack.removeLast()
        renderView(prev, forceRebuild = false, pushHistory = false)
        return true
    }

    fun showStatus(message: String) {
        statusLabel.text = message
        statusLabel.visibility = VISIBLE
    }

    fun hideStatus() {
        statusLabel.visibility = GONE
    }

    /**
     * A different Home Assistant server: forget the previous one's dashboard, views and resources, so none of it is
     * rebuilt with the new server's resources before the new dashboard arrives.
     */
    fun resetForServer() {
        holding?.cancel?.invoke()
        holding = null
        waited.clear()
        stateRebuilds.values.forEach { it() }
        stateRebuilds.clear()
        closeMoreInfo()
        dashboard = null
        viewBackStack.clear()
        // Another Home Assistant: no island of this one's carries over.
        dropBuiltViews(keepIslands = false)
        closePooledIslands()
        resourcesList = emptyList()
        // Nothing of the old server stays on screen while the new one connects: not its tabs, not its backdrop, and
        // a backdrop image still loading from it doesn't land afterwards.
        tabBar.removeAllViews()
        tabs.clear()
        tabScroll.visibility = GONE
        backdropGeneration++
        backdrop.background = null
        setBackgroundColor(neutralBackground)
    }

    fun setThemes(catalog: ThemeCatalog) {
        if (catalog == themeCatalog) return
        val before = themeInputs(themeCatalog, darkMode, userThemeName)
        themeCatalog = catalog
        // A theme the dashboard doesn't use changed (or was added): nothing on it looks different.
        if (before != null && before == themeInputs(catalog, darkMode, userThemeName)) return
        rebuildForTheme()
    }

    /** Applies the user's selected Home Assistant theme (`frontend/get_user_data` `core.selectedTheme`). */
    fun setUserTheme(themeName: String?, dark: Boolean?) {
        val nextTheme = themeName?.takeIf { it.isNotBlank() && it != "Backend-selected" }
        val nextDark = dark ?: darkMode
        if (nextTheme == userThemeName && nextDark == darkMode) return
        val before = themeInputs(themeCatalog, darkMode, userThemeName)
        userThemeName = nextTheme
        darkMode = nextDark
        // Another name for the theme already in force (a theme Home Assistant doesn't have, and the default): no change.
        if (before != null && before == themeInputs(themeCatalog, darkMode, userThemeName)) return
        rebuildForTheme()
    }

    /**
     * The theme changed: the native cards are built again in it, and each island keeps its page, restyled to the
     * new theme over its bridge ([WebIslandCardView.handOff]).
     */
    private fun rebuildForTheme() {
        dropBuiltViews(keepIslands = true)
        dashboard?.let { renderView(currentViewIndex, forceRebuild = true) }
    }

    /**
     * What the dashboard's look is made of in [catalog]: for each theme its views and cards ask for, the theme Home
     * Assistant picks for it in [dark] mode and that theme's definition. Equal inputs draw the same dashboard. Null
     * without a dashboard.
     */
    private fun themeInputs(catalog: ThemeCatalog, dark: Boolean, userTheme: String?): List<Any?>? {
        val cfg = dashboard ?: return null
        val asked = LinkedHashSet<String?>()
        fun cardThemes(json: Any?) {
            when (json) {
                is org.json.JSONObject -> json.keys().forEach { k ->
                    val v = json.opt(k)
                    if (k == "theme" && v is String) asked += v else cardThemes(v)
                }
                is org.json.JSONArray -> for (i in 0 until json.length()) cardThemes(json.opt(i))
            }
        }
        for (v in cfg.views) {
            asked += v.theme ?: userTheme
            v.cards.forEach { cardThemes(it.raw) }
            v.badges.forEach { cardThemes(it.raw) }
            v.sections.forEach { section -> section.cards.forEach { cardThemes(it.raw) } }
        }
        return listOf<Any?>(dark) + asked.map { name ->
            val picked = ThemeResolver.pickedName(catalog, dark, name)
            picked to catalog.themes[picked]
        }
    }

    fun setDashboard(config: DashboardConfig, initialViewPath: String?) {
        holding?.cancel?.invoke()
        holding = null
        waited.clear()
        stateRebuilds.values.forEach { it() }
        stateRebuilds.clear()
        dashboard = config
        viewBackStack.clear()
        // An island whose card is in the new config the same as before keeps its page.
        dropBuiltViews(keepIslands = true)
        val idx = resolveViewIndex(config, initialViewPath)
        rebuildTabs(config)
        renderView(idx, forceRebuild = true)
    }

    fun navigateToView(viewPath: String?): Boolean {
        val cfg = dashboard ?: return false
        val idx = resolveViewIndex(cfg, viewPath)
        renderView(idx, forceRebuild = false)
        return true
    }

    /** A more-info sheet is open, or still closing. */
    internal val moreInfoOpen: Boolean
        get() = moreInfo != null || (0 until childCount).any { getChildAt(it) is MoreInfoSheet }

    /** The loading or connecting status covers the view. */
    internal val statusShowing: Boolean get() = statusLabel.visibility == VISIBLE

    /** The view on screen's scrolling container, if it scrolls (a panel view doesn't). */
    internal fun currentScroll(): ScrollView? = viewCache[currentViewIndex] as? ScrollView

    /** Opens the view at [path] as its tab does (the page's history and location follow). False with no dashboard. */
    internal fun openViewAsTab(path: String): Boolean {
        val cfg = dashboard ?: return false
        val idx = resolveViewIndex(cfg, path)
        if (idx != currentViewIndex) onTabNavigation?.invoke()
        renderView(idx, forceRebuild = false)
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val c = clarity ?: return super.dispatchTouchEvent(ev)
        val handled = super.dispatchTouchEvent(ev)
        c.touched(ev)
        return handled
    }

    /**
     * Makes More Info modal, as HA's dialog is: while it's open, the dashboard (tabs, cards, pinned cards) takes no
     * keyboard focus and is hidden from TalkBack, and both come back when it closes.
     */
    private fun blockDashboardFocus(block: Boolean) {
        val mode = if (block) FOCUS_BLOCK_DESCENDANTS else FOCUS_AFTER_DESCENDANTS
        val a11y = if (block) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        for (v in listOf(shell, overlayLayer)) {
            v.descendantFocusability = mode
            v.importantForAccessibility = a11y
        }
    }

    fun closeMoreInfo(): Boolean {
        val sheet = moreInfo ?: return false
        moreInfo = null
        sheet.dismiss()
        return true
    }

    fun openMoreInfo(entityId: String) = openMoreInfo(entityId, null)

    /** [trace]: what Clarity's harness follows of the sheet ([MoreInfoSheet.Trace]). */
    internal fun openMoreInfo(entityId: String, trace: MoreInfoSheet.Trace?) {
        closeMoreInfo()
        val cfg = dashboard?.views?.getOrNull(currentViewIndex)
        val theme = ThemeResolver.resolve(themeCatalog, darkMode, cfg?.theme ?: userThemeName, cfg?.background)
        val cardCtx = contextFactory?.invoke(theme) ?: return
        var sheetRef: MoreInfoSheet? = null
        val sheet = MoreInfoSheet(cardCtx) {
            sheetRef?.let {
                removeView(it)
                if (moreInfo === it) moreInfo = null
            }
            // The dashboard takes keyboard focus again once no sheet is over it.
            if (moreInfo == null) blockDashboardFocus(false)
        }
        sheetRef = sheet
        sheet.trace = trace
        // Keys stay in the sheet, as in HA's modal dialog: nothing behind it takes focus while it's open.
        blockDashboardFocus(true)
        sheet.show(entityId)
        moreInfo = sheet
        addView(sheet, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun resolveViewIndex(config: DashboardConfig, path: String?): Int = config.viewIndex(path)

    private fun rebuildTabs(config: DashboardConfig) {
        tabBar.removeAllViews()
        tabs.clear()
        val theme = ThemeResolver.resolve(themeCatalog, darkMode)
        tabScroll.setBackgroundColor(theme.secondaryBackgroundColor)
        // Like HA, subviews have no tab: they are reached by navigation and come back with their back arrow.
        config.views.forEachIndexed { idx, v ->
            if (v.subview) return@forEachIndexed
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                tag = idx
                setOnClickListener {
                    if (idx != currentViewIndex) onTabNavigation?.invoke()
                    renderView(idx, forceRebuild = false)
                }
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(16f), px(12f), px(16f), px(9.5f))
            }
            val icon = v.icon?.let { name ->
                MdiIconView(context).apply { setIcon(name, theme.secondaryTextColor) }.also {
                    row.addView(it, LinearLayout.LayoutParams(px(18f), px(18f)).apply { marginEnd = px(6f) })
                }
            }
            val label = TextView(context).apply {
                text = v.title
                textSize = 13f
                typeface = VitroFonts.typeface(context, Inter.MEDIUM, 13f)
                includeFontPadding = false
            }
            row.addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            tab.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            val indicator = View(context).apply { visibility = INVISIBLE }
            tab.addView(indicator, LinearLayout.LayoutParams(MATCH_PARENT, (2.5f * dp).toInt().coerceAtLeast(1)))
            tabBar.addView(tab, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            tabs += TabEntry(idx, label, icon, indicator)
        }
        updateTabVisibility()
    }

    private fun updateTabVisibility() {
        val count = dashboard?.views?.count { !it.subview } ?: 0
        tabScroll.visibility = if (!hideHeader && count > 1) VISIBLE else GONE
    }

    private fun highlightTab(selected: Int, theme: ResolvedTheme) {
        // The strip itself follows the theme too (a light/dark switch, a cached view's own theme).
        tabScroll.setBackgroundColor(theme.secondaryBackgroundColor)
        for (t in tabs) {
            val active = t.viewIndex == selected
            val color = if (active) theme.primaryColor else theme.secondaryTextColor
            t.label.setTextColor(color)
            t.icon?.tintColor = color
            t.indicator.setBackgroundColor(theme.primaryColor)
            t.indicator.visibility = if (active) VISIBLE else INVISIBLE
        }
    }

    /** Entities a build chose a card's class or size for without their state (it hadn't arrived yet). */
    private val guessedStates = HashSet<String>()

    /** The build's look at [card]'s state; a missing one is noted, so the view is built again when it lands. */
    private fun stateForBuild(card: CardConfig): EntityState? {
        val id = card.entity ?: return null
        return store[id] ?: run { guessedStates += id; null }
    }

    /**
     * How long a view's first build waits for its first states (0: it doesn't). The engine sets it: the view's
     * entities are subscribed as it opens, and their states usually land within a round trip. Past the wait it builds
     * anyway, and a card that guessed without state is built again when it lands ([rebuildWhenStatesLand]).
     */
    var holdFirstBuildForStatesMs: Long = 0

    /** A view's first build waiting for its states, and what ends the wait (its listener and timer). */
    private class Wait(val index: Int, val cancel: () -> Unit)

    /** The wait in progress. Each wait is its own object: a late callback of an older wait never ends a newer one. */
    private var holding: Wait? = null

    /**
     * The main thread's handler: a view's own post() waits until it's attached to a window, which a page built
     * before it's shown (or in a test) may not be yet.
     */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** True when the view on screen has been built (not waiting for its first states). */
    val currentViewBuilt: Boolean get() = viewCache[currentViewIndex] != null

    /**
     * The page is gone: a view waiting for its first states isn't built later (a late build would make cards, and
     * islands, nothing shows), and no guessed view is built again.
     */
    fun cancelPendingBuilds() {
        holding?.cancel?.invoke()
        holding = null
        stateRebuilds.values.forEach { it() }
        stateRebuilds.clear()
    }

    /** Views of this dashboard that have waited once: past the wait (states or not), they build. */
    private val waited = HashSet<Int>()

    /**
     * True when the first build of view [index] waits: none of its entities has a state yet. It's built when the
     * first of them lands, or after [holdFirstBuildForStatesMs].
     */
    private fun holdForStates(index: Int, viewCfg: ViewConfig): Boolean {
        if (holdFirstBuildForStatesMs <= 0 || index in waited) return false
        holding?.let { if (it.index == index) return true }
        holding?.cancel?.invoke()
        holding = null
        val refs = CardRouter.referencedEntities(viewCfg)
        if (refs.isEmpty() || refs.any { store[it] != null }) return false
        val config = dashboard
        var unsub: (() -> Unit)? = null
        lateinit var timeout: Runnable
        lateinit var mine: Wait
        fun release() {
            if (holding !== mine) return
            holding = null
            mine.cancel()
            waited += index
            if (dashboard === config && currentViewIndex == index && viewCache[index] == null) {
                renderView(index, forceRebuild = false, pushHistory = false)
            }
        }
        timeout = Runnable { release() }
        unsub = store.subscribeAny { changed -> if (changed.any { it in refs }) mainHandler.post { release() } }
        mine = Wait(index) { unsub?.invoke(); mainHandler.removeCallbacks(timeout) }
        holding = mine
        mainHandler.postDelayed(timeout, holdFirstBuildForStatesMs)
        // States that landed between the check above and the listener (the store is written on another thread).
        if (refs.any { store[it] != null }) mainHandler.post { release() }
        return true
    }

    /** Per view index: the listener waiting for the states its build guessed at. */
    private val stateRebuilds = HashMap<Int, () -> Unit>()

    /**
     * HA re-renders when hass updates; a Mushroom card's class and grid rows follow its entity's features, which a
     * view's first build can come before (its entities are subscribed as it opens). When any of [ids] lands, the
     * view [index] is built again, once: now if it's on screen, else its cached tree is dropped for the next visit.
     */
    private fun rebuildWhenStatesLand(index: Int, ids: Set<String>) {
        stateRebuilds.remove(index)?.invoke()
        if (ids.isEmpty()) return
        val config = dashboard
        var unsub: (() -> Unit)? = null
        fun rebuild() {
            val mine = unsub ?: return
            if (stateRebuilds[index] !== mine) return
            stateRebuilds.remove(index)
            mine()
            if (dashboard !== config) return
            if (currentViewIndex == index) {
                renderView(index, forceRebuild = true, pushHistory = false)
            } else {
                dropView(index, keepIslands = false)
            }
        }
        unsub = store.subscribeAny { changed ->
            if (changed.none { it in ids }) return@subscribeAny
            mainHandler.post { rebuild() }
        }
        stateRebuilds[index] = unsub
        // A state that landed while the view was being built (the store is written on the reader thread) reached
        // no listener: it counts as landing now.
        if (ids.any { store[it] != null }) mainHandler.post { rebuild() }
    }

    private fun renderView(index: Int, forceRebuild: Boolean, pushHistory: Boolean = true) {
        // Nothing to build: no island a rebuild gave up waits for it.
        val cfg = dashboard ?: return closePooledIslands()
        val viewCfg = cfg.views.getOrNull(index) ?: return closePooledIslands()
        // Before anything is selected or built: the page leaves for HA's frontend, and this view stays as it was. No
        // build follows, so no island a rebuild gave up waits for one.
        onWebView?.let { show ->
            viewCfg.webFallbackReason?.let { reason ->
                closePooledIslands()
                show(viewCfg, reason)
                return
            }
        }
        val t0 = SystemClock.elapsedRealtime()
        val prev = currentViewIndex
        if (pushHistory && !forceRebuild && prev != index && cfg.views.getOrNull(prev) != null) {
            viewBackStack.addLast(prev)
            while (viewBackStack.size > MAX_BACK_STACK) viewBackStack.removeFirst()
        }
        currentViewIndex = index
        hideStatus()

        val theme = ThemeResolver.resolve(themeCatalog, darkMode, viewCfg.theme ?: userThemeName, viewCfg.background)
        VitroFonts.family = VitroFonts.familyFor(theme)
        applyBackdrop(theme)
        highlightTab(index, theme)
        onThemeResolved?.invoke(theme)
        onViewSelected?.invoke(viewCfg)

        val cached = if (!forceRebuild) viewCache[index] else null
        // A first build waits for the view's states, as HA's frontend renders a view's cards once hass has them.
        if (cached == null && holdForStates(index, viewCfg)) {
            // The previous view's cards don't stay on screen (and tappable) under this view's tab while it waits.
            for (i in 0 until contentHost.childCount) contentHost.getChildAt(i).visibility = GONE
            for (i in 0 until overlayLayer.childCount) overlayLayer.getChildAt(i).visibility = GONE
            updateIslandsShown()
            return
        }
        val viewRoot = if (cached != null) {
            cached
        } else {
            // The view's body font rides on the cards' context, not only on VitroFonts.family: a card that builds a
            // child later (auto-entities' refresh) while another view is shown still gets this view's font.
            val cardCtx = contextFactory?.invoke(theme)?.withFontFamily(VitroFonts.familyFor(theme)) ?: return closePooledIslands()
            // The fonts the dashboard's stylesheets declare: read only once a card draws a family Vitro doesn't have.
            WebFonts.configure(cardCtx.haBase, cardCtx.resources, context.cacheDir)
            fontsFor = cardCtx.haBase to cardCtx.resources
            val pinned = ArrayList<Pair<View, FrameLayout.LayoutParams>>()
            pendingPinned = pinned
            guessedStates.clear()
            // The view's old tree, if it has one, goes first: its islands wait for this build to take them back.
            dropView(index, keepIslands = true)
            buildingIndex = index
            clarity?.buildStarted(index)
            val built = try {
                buildViewTree(viewCfg, cardCtx)
            } finally {
                pendingPinned = null
                buildingIndex = -1
            }
            rebuildWhenStatesLand(index, guessedStates.toSet())
            viewCache[index] = built
            builtScripts[index] = IslandScripts.of(cardCtx.resources)
            contentHost.addView(built, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            if (pinned.isNotEmpty()) {
                val frame = FrameLayout(context).apply {
                    clipChildren = false
                    clipToPadding = false
                }
                pinned.forEach { (v, lp) -> frame.addView(v, lp) }
                overlayFrames[index] = frame
                overlayLayer.addView(frame, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            trimViewCache(keep = index)
            built
        }

        // Keep-alive cache: toggle visibility instead of destroying built native views. Pinned cards follow their view.
        for (i in 0 until contentHost.childCount) {
            val child = contentHost.getChildAt(i)
            child.visibility = if (child === viewRoot) VISIBLE else GONE
        }
        val activeOverlay = overlayFrames[index]
        for (i in 0 until overlayLayer.childCount) {
            val child = overlayLayer.getChildAt(i)
            child.visibility = if (child === activeOverlay) VISIBLE else GONE
        }
        // Islands the build didn't take back (their card is gone or changed) close; only this view's islands run.
        closePooledIslands()
        updateIslandsShown()

        val elapsed = SystemClock.elapsedRealtime() - t0
        // The report follows the tree on screen: a cached tree keeps the card-mod state it was built with.
        val scripts = builtScripts[index] ?: IslandScripts.of(resourcesList)
        val islands = CompatChecker.islands(viewCfg, scripts)
        val tiers = CardRouter.tierCounts(viewCfg, scripts)
        val entities = CardRouter.referencedEntities(viewCfg)
        val metrics = VitroMetrics(
            viewTitle = viewCfg.title,
            viewType = viewCfg.type,
            buildTimeMs = elapsed,
            nativeCoreCards = tiers[CardTier.NATIVE_CORE] ?: 0,
            nativeHacsCards = tiers[CardTier.NATIVE_HACS] ?: 0,
            webIslandCards = tiers[CardTier.WEB_ISLAND] ?: 0,
            subscribedEntities = entities.size,
            cacheHit = cached != null,
            islands = islands.map { "${it.path}: ${it.verdict.reasons.joinToString("; ")}" },
        )
        lastMetrics = metrics
        Log.i(
            TAG,
            "View '${metrics.viewTitle}' (${metrics.viewType}) built in ${metrics.buildTimeMs} ms " +
                "(cache=${metrics.cacheHit}, core=${metrics.nativeCoreCards}, hacs=${metrics.nativeHacsCards}, " +
                "islands=${metrics.webIslandCards}, entities=${metrics.subscribedEntities})",
        )
        // The checker's answer for this view, once per build: why each island falls back.
        if (cached == null) metrics.islands.forEach { Log.i(TAG, "Island $it") }
        onMetrics?.invoke(metrics)
    }

    /** Keeps at most [MAX_CACHED_VIEWS] built views alive, evicting the oldest one that is not [keep]. */
    private fun trimViewCache(keep: Int) {
        while (viewCache.size > MAX_CACHED_VIEWS) {
            val oldest = viewCache.keys.firstOrNull { it != keep } ?: return
            dropView(oldest, keepIslands = false)
        }
    }

    private fun clearOverlay() {
        overlayFrames.clear()
        overlayLayer.removeAllViews()
    }

    // ---- Islands: one browser page per island card, open only while the card is built

    /** Islands a rebuild gave up ([WebIslandCardView.handOff]), by card key, until the build takes them back. */
    private val islandPool = HashMap<String, ArrayDeque<WebIslandCardView.Island>>()

    /**
     * Whether the page shows the dashboard, not the fallback's page in its place: islands run only while it does.
     * The engine's page sets it.
     */
    var dashboardShown: Boolean = true
        set(v) {
            if (field == v) return
            field = v
            updateIslandsShown()
        }

    /**
     * Whether the activity is in front (started): behind another app no island runs, and none is made active there.
     * The engine's page sets it from the activity's lifecycle.
     */
    var activityShown: Boolean = true
        set(v) {
            if (field == v) return
            field = v
            updateIslandsShown()
        }

    /** The island cards in [root]'s tree: web islands and iframe cards. */
    private fun islandsIn(root: View, into: MutableList<IslandCard> = ArrayList()): MutableList<IslandCard> {
        if (root is IslandCard) {
            into += root
        } else if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) islandsIn(root.getChildAt(i), into)
        }
        return into
    }

    /** The islands of [root], a tree leaving the view: kept for the rebuild under way ([keepIslands]), or closed. */
    private fun retireIslands(root: View, keepIslands: Boolean) {
        for (island in islandsIn(root)) {
            if (!keepIslands) {
                island.release()
                continue
            }
            island.handOff()?.let { islandPool.getOrPut(it.key) { ArrayDeque() }.addLast(it) }
        }
    }

    /** Drops built view [index] and its pinned cards. */
    private fun dropView(index: Int, keepIslands: Boolean) {
        viewCache.remove(index)?.let { retireIslands(it, keepIslands); contentHost.removeView(it) }
        overlayFrames.remove(index)?.let { retireIslands(it, keepIslands); overlayLayer.removeView(it) }
        builtScripts.remove(index)
        clarity?.keepCards(viewCache.keys)
    }

    /** Drops every built view and the pinned cards. */
    private fun dropBuiltViews(keepIslands: Boolean) {
        viewCache.values.forEach { retireIslands(it, keepIslands) }
        overlayFrames.values.forEach { retireIslands(it, keepIslands) }
        viewCache.clear()
        clarity?.keepCards(viewCache.keys)
        builtScripts.clear()
        contentHost.removeAllViews()
        clearOverlay()
    }

    /** The island an earlier build of [card] gave up, for its new build to take, or null. */
    private fun adoptIsland(card: CardConfig, fillViewport: Boolean): WebIslandCardView.Island? {
        val key = WebIslandCardView.keyOf(card, fillViewport)
        val queue = islandPool[key] ?: return null
        val island = queue.removeFirstOrNull()
        if (queue.isEmpty()) islandPool.remove(key)
        return island
    }

    private fun closePooledIslands() {
        if (islandPool.isEmpty()) return
        val left = islandPool.values.flatten()
        islandPool.clear()
        left.forEach { it.close() }
    }

    /**
     * Each island runs only while its view is on screen ([WebIslandCardView.setShown]): the current view's, while the
     * dashboard shows and the activity is in front. A hidden view's islands keep their page but stop painting.
     */
    private fun updateIslandsShown() {
        val onScreen = dashboardShown && activityShown
        for ((index, root) in viewCache) {
            val shown = onScreen && index == currentViewIndex && root.visibility == VISIBLE
            islandsIn(root).forEach { it.setShown(shown) }
        }
        for ((index, frame) in overlayFrames) {
            val shown = onScreen && index == currentViewIndex && frame.visibility == VISIBLE
            islandsIn(frame).forEach { it.setShown(shown) }
        }
    }

    /**
     * The content of island [pages] went away (a crashed or killed content process): they draw nothing again. A dead
     * page waiting in the pool closes, so no build takes it. A card that holds one closes it, and its view is built
     * again: the view on screen now, with its other islands handed over alive; a hidden view when it's shown.
     */
    fun islandPagesGone(pages: Set<Page>) {
        if (pages.isEmpty()) return
        val pooled = islandPool.values.flatten().filter { it.page in pages }
        for (island in pooled) {
            islandPool[island.key]?.remove(island)
            if (islandPool[island.key]?.isEmpty() == true) islandPool.remove(island.key)
            island.close()
        }
        val hit = HashSet<Int>()
        for ((index, root) in viewCache) {
            val dead = islandsIn(root).filter { it.holds(pages) }
            val pinned = overlayFrames[index]?.let { islandsIn(it).filter { card -> card.holds(pages) } }.orEmpty()
            (dead + pinned).forEach { it.release() }
            if (dead.isNotEmpty() || pinned.isNotEmpty()) hit += index
        }
        for (index in hit) {
            if (index == currentViewIndex) renderView(index, forceRebuild = true, pushHistory = false)
            else dropView(index, keepIslands = false)
        }
    }

    /** The page is gone: every island closes, and nothing is built again. */
    fun closeIslands() {
        cancelPendingBuilds()
        dropBuiltViews(keepIslands = false)
        closePooledIslands()
    }

    /**
     * Sends a `position: fixed` card to the current view's overlay instead of the scrolling layout. False (and
     * nothing happens) for every other card, or outside a view build.
     */
    private fun pinIfFixed(
        card: CardConfig,
        ctx: VitroCardContext,
        sectionVisibility: List<VisibilityCondition> = emptyList(),
        layout: String? = null,
    ): Boolean {
        val sink = pendingPinned ?: return false
        val mod = ThemeResolver.parseCardMod(card.raw.optJSONObject("card_mod"), ctx.theme.vars)
        if (!mod.positionFixed) return false
        // A fixed card still lives in its section: when the section hides, so does the card.
        val view = wrapVisibility(card, buildCard(card, ctx, fillViewport = false, layout = layout), ctx, sectionVisibility)
        sink += view to pinnedLayoutParams(mod)
        return true
    }

    /** Overlay [FrameLayout.LayoutParams] for a pinned card: gravity from its offsets/transform, margins in dp. */
    internal fun pinnedLayoutParams(mod: CardModStyle): FrameLayout.LayoutParams {
        val viewportW = if (width > 0) width else resources.displayMetrics.widthPixels
        val viewportH = if (height > 0) height else resources.displayMetrics.heightPixels
        fun length(raw: String?, extent: Int): Int? {
            val v = raw?.trim()?.lowercase() ?: return null
            Regex("""^(-?[\d.]+)%$""").matchEntire(v)?.let { m ->
                return m.groupValues[1].toFloatOrNull()?.let { (it / 100f * extent).toInt() }
            }
            Regex("""^(-?[\d.]+)(px|dp)?$""").matchEntire(v)?.let { m ->
                return m.groupValues[1].toFloatOrNull()?.let { px(it) }
            }
            Regex("""^(-?[\d.]+)r?em$""").matchEntire(v)?.let { m ->
                return m.groupValues[1].toFloatOrNull()?.let { px(it * 16f) }
            }
            return null
        }
        val horizontal = when {
            mod.fixedCenterX -> Gravity.CENTER_HORIZONTAL
            // Both insets with a width: `left` wins, as CSS resolves it in a left-to-right page.
            mod.fixedLeft == null && mod.fixedRight != null -> Gravity.END
            else -> Gravity.START
        }
        val vertical = when {
            mod.fixedCenterY -> Gravity.CENTER_VERTICAL
            mod.fixedTop == null && mod.fixedBottom != null -> Gravity.BOTTOM
            else -> Gravity.TOP
        }
        val w = length(mod.fixedWidth, viewportW)?.takeIf { it > 0 } ?: WRAP_CONTENT
        return FrameLayout.LayoutParams(w, WRAP_CONTENT, horizontal or vertical).apply {
            if (!mod.fixedCenterY) {
                topMargin = length(mod.fixedTop, viewportH) ?: 0
                bottomMargin = length(mod.fixedBottom, viewportH) ?: 0
            }
            if (!mod.fixedCenterX) {
                marginStart = length(mod.fixedLeft, viewportW) ?: 0
                marginEnd = length(mod.fixedRight, viewportW) ?: 0
            }
        }
    }

    /** Bumped by every backdrop change, so an image that loads after a newer backdrop doesn't replace it. */
    private var backdropGeneration = 0

    private fun applyBackdrop(theme: ResolvedTheme) {
        val generation = ++backdropGeneration
        // A picture replaces the root's color, as it did when it was the root's own background.
        fun picture(d: android.graphics.drawable.Drawable) {
            background = null
            backdrop.background = d
        }
        fun color(c: Int) {
            backdrop.background = null
            setBackgroundColor(c)
        }
        when (val bg = theme.background) {
            is ThemeBackground.Solid -> color(bg.color)
            is ThemeBackground.LinearGradient -> picture(CssBackgroundDrawable(
                CssBackground(listOf(CssBackground.Linear(bg.angleDeg, bg.stops ?: bg.colors.map { CssBackground.Stop(it, null, null) })), null),
                dp,
            ))
            is ThemeBackground.RadialGradient -> picture(RadialBackdrop(bg.colors))
            is ThemeBackground.Layers -> picture(CssBackgroundDrawable(bg.css, dp))
            is ThemeBackground.ImageUrl -> {
                color(bg.fallbackColor)
                contextFactory?.invoke(theme)?.loadImage(bg.url) { bmp ->
                    if (bmp != null && generation == backdropGeneration) picture(BitmapDrawable(resources, bmp))
                }
            }
        }
    }

    /** A subview's back arrow and title: back to `back_path`, else to the view it was opened from. */
    private fun subviewBackRow(view: ViewConfig, ctx: VitroCardContext, pad: Int): View {
        val backRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, px(4f))
            isClickable = true
            setOnClickListener {
                val pageBack = pageBack
                when {
                    view.backPath != null -> ctx.navigate(view.backPath)
                    // A page's own history is the one back walks: this view's stack mirrors it, and replaying that
                    // after the page's is exhausted would revisit views already gone back through.
                    pageBack != null -> if (!pageBack()) ctx.navigate("/")
                    viewBackStack.isNotEmpty() -> goBack()
                    else -> ctx.navigate("/")
                }
            }
        }
        backRow.addView(TextView(context).apply {
            text = "←  ${view.title}"
            textSize = 18f
            setTextColor(ctx.theme.primaryTextColor)
            typeface = VitroFonts.typeface(context, Inter.MEDIUM, 18f)
        })
        return backRow
    }

    private fun buildViewTree(view: ViewConfig, ctx: VitroCardContext): View {
        // Panel view: single full-viewport card (e.g. custom:mirror-home-card, custom:tablet-home-card).
        if (view.type == "panel" && view.cards.isNotEmpty()) {
            val card = view.cards.first()
            // In a full-viewport frame: the cached-view switch shows and hides the frame, the card's conditions the
            // card inside it, so returning to the view can't undo a hidden card.
            val frame = FrameLayout(context).apply {
                addView(wrapVisibility(card, buildCard(card, ctx, fillViewport = true), ctx), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            // A panel subview keeps its back arrow while the header shows; the card fills the rest.
            if (!view.subview || hideHeader) return frame
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(subviewBackRow(view, ctx, px(ctx.theme.viewPaddingDp)), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val widthDp = (resources.displayMetrics.widthPixels / dp).toInt()
        val outerPad = px(ctx.theme.viewPaddingDp)

        val layoutCard = view.layoutCardType
        val container: View = when {
            view.type == "sections" && view.sections.isNotEmpty() -> buildSectionsLayout(view, ctx, widthDp)
            view.type == "sidebar" -> buildSidebarLayout(view.cards, ctx, widthDp)
            // layout-card as the view's own layout: the same layouts as the card, with the view's `layout:`.
            layoutCard != null -> LayoutCardView(ctx, layoutViewCard(view), { child -> wrapVisibility(child, buildCard(child, ctx, false), ctx) }, widthDp)
            else -> buildMasonryLayout(view.cards, ctx, widthDp)
        }
        // layout-card's views draw no badges.
        val withBadges = if (view.badges.isNotEmpty() && layoutCard == null) {
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(buildBadgeBar(view.badges, ctx), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = px(8f)
                    bottomMargin = px(8f)
                })
                addView(container, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        } else {
            container
        }
        // HA's subview back arrow is part of its toolbar, so a hidden header (the kiosk's) hides it too.
        val body = if (view.subview && !hideHeader) {
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(subviewBackRow(view, ctx, outerPad), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(withBadges, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        } else {
            withBadges
        }
        scroll.addView(body, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return scroll
    }

    /** HA's view `badges`: a centered, scrollable row of live entity pills above the cards. */
    private fun buildBadgeBar(badges: List<CardConfig>, ctx: VitroCardContext): View {
        // isFillViewport makes the row at least as wide as the screen, so CENTER gravity centers the pills
        // while they fit; once they don't, the row grows and scrolls.
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(px(8f), 0, px(8f), 0)
        }
        badges.forEachIndexed { idx, badge ->
            val pill = buildBadge(badge, ctx) ?: return@forEachIndexed
            row.addView(wrapVisibility(badge, pill, ctx), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                if (idx > 0) marginStart = px(8f)
            })
        }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
        }
        scroll.addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return scroll
    }

    private fun buildBadge(badge: CardConfig, ctx: VitroCardContext): View? {
        val eid = badge.entity ?: return null
        val theme = ctx.theme
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(38f)
            setPadding(px(12f), px(6f), px(12f), px(6f))
            background = GradientDrawable().apply {
                cornerRadius = 999f * dp
                setColor(theme.cardBackgroundColor)
                setStroke(px(1f).coerceAtLeast(1), theme.cardBorderColor)
            }
            isClickable = true
        }
        val showIcon = badge.raw.optBoolean("show_icon", true)
        val icon = MdiIconView(context)
        if (showIcon) pill.addView(icon, LinearLayout.LayoutParams(px(18f), px(18f)).apply { marginEnd = px(8f) })
        fun line(sp: Float, weight: Int, color: Int) = TextView(context).apply {
            textSize = sp
            typeface = VitroFonts.typeface(context, weight, sp)
            setTextColor(color)
            includeFontPadding = false
            maxLines = 1
        }
        // HA 2026 stacks the name above the state when the name shows; otherwise the state stands alone.
        val nameView = line(10f, Inter.REGULAR, theme.secondaryTextColor)
        val stateView = line(12f, Inter.MEDIUM, theme.primaryTextColor)
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(nameView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(stateView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = px(1f) })
        }
        pill.addView(texts, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val render = { s: EntityState? ->
            val (name, state) = badgeLines(badge, s)
            nameView.text = name.orEmpty()
            nameView.visibility = if (name != null && state != null) VISIBLE else GONE
            stateView.text = state ?: name.orEmpty()
            // Neither line asked for: no text slot at all.
            texts.visibility = if (name == null && state == null) GONE else VISIBLE
            // An icon-only badge is still a button: named after its entity for TalkBack.
            pill.contentDescription = if (name == null && state == null) s?.displayName ?: eid else null
            if (showIcon) icon.setIcon(MdiIcons.resolveIconName(badge.icon, s), badgeIconColor(theme, s))
        }
        render(store[eid])
        val tap = ActionConfig.parse(badge.raw.optJSONObject("tap_action"), "more-info")
        pill.setOnClickListener { ctx.performAction(tap, eid) }
        pill.bindHoldAction(ctx, ActionConfig.parse(badge.raw.optJSONObject("hold_action"), "none"), eid)
        fun subscribe() = store.subscribe(eid) { _ -> ctx.main.post { render(store[eid]) } }
        var unsub: (() -> Unit)? = subscribe()
        pill.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // Back after a detach: listen again, and show what changed meanwhile.
                if (unsub == null) {
                    unsub = subscribe()
                    render(store[eid])
                }
            }
            override fun onViewDetachedFromWindow(v: View) {
                unsub?.invoke()
                unsub = null
            }
        })
        return pill
    }

    /**
     * HA's sections view (`hui-sections-view.ts`): horizontal padding of `--ha-view-sections-column-gap` (the
     * narrow gap at 600 px and below), as many columns of at least `--ha-view-sections-column-min-width` as fit
     * (at most `max_columns`, and no more than the sections need), each at most
     * `--ha-view-sections-column-max-width` wide and centered, `--ha-view-sections-row-gap` between sections
     * and above and below them. Hidden sections take no room.
     */
    private fun buildSectionsLayout(view: ViewConfig, ctx: VitroCardContext, widthDp: Int): View {
        val vars = ctx.theme.vars
        fun dim(name: String, def: Float) = ThemeResolver.parseDimensionDp(vars[name], def)
        val narrow = widthDp <= 600
        val colGap = if (narrow) dim("ha-view-sections-narrow-column-gap", 8f) else dim("ha-view-sections-column-gap", 32f)
        val rowGap = dim("ha-view-sections-row-gap", 24f)
        val maxW = dim("ha-view-sections-column-max-width", 500f)
        val minW = dim("ha-view-sections-column-min-width", 320f)
        val fit = kotlin.math.floor((widthDp - 2 * colGap + colGap) / (minW + colGap)).toInt().coerceAtLeast(1)
        val totalSpan = view.sections.sumOf { it.columnSpan }.coerceAtLeast(1)
        val cols = minOf(fit, view.maxColumns, totalSpan).coerceAtLeast(1)
        val grid = HaGridLayout(context, cols, px(colGap), px(rowGap), stretch = false).apply {
            setPadding(px(colGap), px(rowGap), px(colGap), px(rowGap))
            maxContentWidth = px(cols * maxW + (cols - 1) * colGap)
        }
        for (sec in view.sections) {
            val span = sec.columnSpan.coerceIn(1, cols)
            val built = wrapConditions(sec.visibility, buildSection(sec, ctx, span), ctx)
            grid.addView(built, HaGridLayout.LayoutParams(span))
        }
        return grid
    }

    /**
     * HA's grid section (`hui-grid-section.ts`): a 12-column grid per section column with
     * `--ha-section-grid-row-gap` / `--ha-section-grid-column-gap` between cards. A card spans its
     * [HaGridSize] columns, and a card with a fixed number of rows is that many `--ha-section-grid-row-height`
     * rows tall; cards in a row stretch to the row's height.
     */
    private fun buildSection(section: SectionConfig, ctx: VitroCardContext, span: Int): View {
        val vars = ctx.theme.vars
        fun dim(name: String, def: Float) = ThemeResolver.parseDimensionDp(vars[name], def)
        val rowGap = dim("ha-section-grid-row-gap", 8f)
        val colGap = dim("ha-section-grid-column-gap", 8f)
        val rowHeight = dim("ha-section-grid-row-height", 56f)
        val columns = 12 * span
        val grid = HaGridLayout(context, columns, px(colGap), px(rowGap), stretch = true)
        section.title?.let { title ->
            grid.addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(ctx.theme.primaryTextColor)
                typeface = VitroFonts.typeface(context, Inter.MEDIUM, 15f)
                setPadding(px(4f), 0, px(4f), 0)
            }, HaGridLayout.LayoutParams(columns))
        }
        for (card in section.cards) {
            if (pinIfFixed(card, ctx, section.visibility, layout = "grid")) {
                // HA keeps the fixed card's grid slot (empty, but it still takes a row gap) while it's shown.
                grid.addView(wrapVisibility(card, View(context), ctx), HaGridLayout.LayoutParams(columns))
                continue
            }
            val size = HaGridSize.of(card, stateForBuild(card))
            val cardView = wrapVisibility(card, buildCard(card, ctx, fillViewport = false, layout = "grid"), ctx)
            cacheCardAsLayer(cardView, ctx)
            val fixed = size.rows?.let { r -> px(r * (rowHeight + rowGap) - rowGap) }
            grid.addView(cardView, HaGridLayout.LayoutParams(size.columns?.coerceAtMost(columns) ?: columns, fixed))
        }
        return grid
    }

    /**
     * HA's masonry view: the cards dealt into columns by [HaMasonry], the columns at most 500 px wide and centered
     * ([MasonryColumnsLayout]), `#columns { margin: 0 4px }` under the view's 4 px top padding, and each card in
     * `--masonry-view-card-margin` (4px 4px 8px; no side margins at 500 px and narrower). A theme's
     * `--masonry-view-padding` (Vitro's own) replaces the view's padding.
     */
    private fun buildMasonryLayout(cards: List<CardConfig>, ctx: VitroCardContext, widthDp: Int): View {
        // A pinned card (card-mod `position: fixed`) goes to the overlay first: it takes no column and no weight.
        val placed = cards.withIndex().filter { (_, card) -> !pinIfFixed(card, ctx) }
        val (placement, count) = HaMasonry.place(placed.map { it.value }, widthDp)
        val row = MasonryColumnsLayout(context, px(if (widthDp < 600) 600f else 500f)).apply { applyViewPadding(this, ctx) }
        val columns = (0 until count).map { LinearLayout(context).apply { orientation = LinearLayout.VERTICAL } }
        columns.forEach { row.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT)) }
        placed.forEachIndexed { k, (configIndex, card) ->
            val column = columns[placement[k]]
            val v = wrapVisibility(card, buildCard(card, ctx, fillViewport = false), ctx)
            clarity?.cardBuilt(buildingIndex, configIndex, card, v)
            column.addView(v, cardMarginParams(ctx, widthDp, first = column.childCount == 0))
        }
        return row
    }

    /**
     * HA's sidebar view ([SidebarColumnsLayout]): from 760 px up, the cards with `view_layout.position: sidebar` in the
     * side column and the rest in the main one; narrower, all of them in one column in config order. Margins as in
     * the masonry view.
     */
    private fun buildSidebarLayout(cards: List<CardConfig>, ctx: VitroCardContext, widthDp: Int): View {
        val main = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val split = widthDp >= 760
        val side = if (split) LinearLayout(context).apply { orientation = LinearLayout.VERTICAL } else main
        for ((cardIndex, card) in cards.withIndex()) {
            if (pinIfFixed(card, ctx)) continue
            val inSidebar = card.raw.optJSONObject("view_layout")?.optString("position") == "sidebar"
            val column = if (inSidebar) side else main
            val v = wrapVisibility(card, buildCard(card, ctx, fillViewport = false), ctx)
            clarity?.cardBuilt(buildingIndex, cardIndex, card, v)
            column.addView(v, cardMarginParams(ctx, widthDp, first = column.childCount == 0))
        }
        if (!split) {
            return SidebarStackLayout(context).apply {
                applyViewPadding(this, ctx)
                addView(main, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        }
        return SidebarColumnsLayout(context, main, side).apply { applyViewPadding(this, ctx) }
    }

    /** `:host { padding-top: 4px }` and `margin: 0 4px` round the columns, or the theme's `--masonry-view-padding`. */
    private fun applyViewPadding(v: View, ctx: VitroCardContext) {
        val own = (ctx.theme.vars["masonry-view-padding"] ?: ctx.theme.vars["vitro-view-padding"])?.let { px(ctx.theme.viewPaddingDp) }
        if (own != null) v.setPadding(own, own, own, own) else v.setPadding(px(4f), px(4f), px(4f), 0)
    }

    /**
     * `margin: var(--masonry-view-card-margin, 4px 4px 8px)`, the side margins dropped at 500 px and narrower. Block
     * margins collapse: after the column's [first] card, the larger of the bottom and top margins is the gap.
     */
    private fun cardMarginParams(ctx: VitroCardContext, widthDp: Int, first: Boolean): LinearLayout.LayoutParams {
        val m = MushroomTitleCardView.paddingDp(ctx.theme.vars["masonry-view-card-margin"]) ?: floatArrayOf(4f, 4f, 8f, 4f)
        val narrow = widthDp <= 500
        val top = if (first) m[0] else maxOf(0f, m[0] - m[2])
        return LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(if (narrow) 0 else px(m[3]), px(top), if (narrow) 0 else px(m[1]), px(m[2]))
        }
    }

    /** A layout-card view as the card [LayoutCardView] draws: its `type` as `layout_type`, its `layout` and cards. */
    private fun layoutViewCard(view: ViewConfig): CardConfig {
        val raw = JSONObject().put("type", "custom:layout-card").put("layout_type", view.type)
        view.raw.optJSONObject("layout")?.let { raw.put("layout", it) }
        raw.put("cards", view.raw.optJSONArray("cards") ?: JSONArray())
        return CardConfig(type = "custom:layout-card", raw = raw, childCards = view.cards)
    }

    /**
     * Which cards get their own hardware layer, read once per process from the device's RAM ([CardLayers.forDevice]).
     * Tests set it.
     */
    internal var cardLayers: CardLayers = CardLayers.forDevice(context)

    /**
     * A card on its own hardware layer: a live value redraws only the card it changed into its layer, and every frame
     * composites the layers instead of replaying every card's text and shapes. With [CardLayers.ALL] every card gets
     * one (about 11 MB more PSS than no layers on a 2 GB panel, the S1); with [CardLayers.BIG] only a big card (at least
     * [BIG_CARD_SHARE] of the dashboard's area), which holds most of what a frame draws, decided once the card has a
     * size and again when it changes. Never for a card that casts a shadow ([cardLayerWanted]), which the layer's
     * bounds would clip.
     */
    private fun cacheCardAsLayer(cardView: View, ctx: VitroCardContext) {
        if (!cardLayerWanted(ctx.theme.cardElevationDp, cardView.elevation)) return
        if (cardLayers == CardLayers.ALL) {
            cardView.setLayerType(LAYER_TYPE_HARDWARE, null)
            return
        }
        cardView.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if (r - l == or - ol && b - t == ob - ot) return@addOnLayoutChangeListener
            val big = isBigCard(r - l, b - t, width, height)
            val want = if (big) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE
            if (v.layerType != want) v.setLayerType(want, null)
        }
    }

    /** Which cards a view draws through their own hardware layer ([cacheCardAsLayer]). */
    internal enum class CardLayers {
        /** Every card (that casts no shadow): devices with RAM to spare, 2 GiB and up. */
        ALL,

        /** Only big cards: 2 GB and less, such as the S1 and the Mirror. */
        BIG;

        companion object {
            /**
             * Total RAM from which every card gets a layer: 2 GiB. `MemTotal` reads below a device's nominal size, so
             * a 2 GB panel stays on the big-card rule: on the S1 (2.07 GB) every card's layer cost about 11 MB more
             * PSS than no layers, 17 MB of it GPU memory (2026-11-24), over the 8 MB it may take. Tablets (the Pixel
             * Tablet reads 7.8 GB) layer every card.
             */
            const val ALL_MIN_TOTAL_MEM = 2_147_483_648L

            fun forTotalMem(bytes: Long): CardLayers = if (bytes >= ALL_MIN_TOTAL_MEM) ALL else BIG

            @Volatile private var deviceMode: CardLayers? = null

            /** [forTotalMem] for this device, read once per process (`ActivityManager.MemoryInfo.totalMem`). */
            fun forDevice(context: Context): CardLayers = deviceMode ?: run {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                val total = am?.let { m -> android.app.ActivityManager.MemoryInfo().also { m.getMemoryInfo(it) }.totalMem } ?: 0L
                forTotalMem(total).also { deviceMode = it }
            }
        }
    }

    /** [layout] is HA's `layout` for this card ([VitroCardContext.cardLayout]): `"grid"` straight in a section. */
    private fun buildCard(card: CardConfig, parentCtx: VitroCardContext, fillViewport: Boolean, layout: String? = null): View {
        val themedCtx = if (card.theme != null && card.theme != parentCtx.theme.name) {
            val overrideTheme = ThemeResolver.resolve(themeCatalog, darkMode, card.theme)
            contextFactory?.invoke(overrideTheme)?.let { themed ->
                // The card's theme sets the body font for the text inside it only if it sets one; a theme of colors
                // only keeps the font in force outside the card.
                themed.withFontFamily(VitroFonts.declaredFamily(overrideTheme) ?: VitroFonts.familyOf(parentCtx.context))
            } ?: parentCtx
        } else {
            parentCtx
        }
        // Custom properties set on a card (`ha-card { --vertical-stack-card-gap: 8px }`) inherit into the cards
        // inside it, as they do through the browser's shadow roots; `var()` in them sees the inherited ones.
        val outerVars = inheritedCardVars
        // With nothing inherited, the theme's own map: the same scope, and the parse is shared with the card's surface.
        val stackMod = ThemeResolver.parseCardMod(card.raw.optJSONObject("card_mod"), if (outerVars.isEmpty()) themedCtx.theme.vars else themedCtx.theme.vars + outerVars)
        // `font-family` on the card: every text inside it, the cards it stacks included, inherits it (as from ha-card).
        val ctx = stackMod.fontFamily?.let(themedCtx::withFontFamily) ?: themedCtx
        val outerCtxVars = ctx.cardVars
        val scope = if (stackMod.customProperties.isEmpty()) outerVars else outerVars + stackMod.customProperties
        // Children built later (a refresh, a filter that changes) re-enter this card's scope.
        val childBuilder: CardBuilder = { child ->
            val savedVars = inheritedCardVars
            val savedCtxVars = ctx.cardVars
            inheritedCardVars = scope
            ctx.cardVars = scope
            try {
                wrapVisibility(child, buildCard(child, ctx, false), ctx)
            } finally {
                inheritedCardVars = savedVars
                ctx.cardVars = savedCtxVars
            }
        }
        val outerLayout = ctx.cardLayout
        inheritedCardVars = scope
        ctx.cardVars = scope
        ctx.cardLayout = layout
        try {
            return buildCardWithVars(card, ctx, fillViewport, childBuilder, stackMod)
        } finally {
            inheritedCardVars = outerVars
            ctx.cardVars = outerCtxVars
            ctx.cardLayout = outerLayout
        }
    }

    /** Card-scoped custom properties in force while building the current card's children. */
    private var inheritedCardVars: Map<String, String> = emptyMap()

    /** A gap from HA's stack and grid variables (`--grid-card-gap`, `--vertical-stack-card-gap`, ...), 8 px by default. */
    private fun cardGap(ctx: VitroCardContext, vararg names: String): Int {
        for (n in names) {
            val v = inheritedCardVars[n] ?: ctx.theme.vars[n] ?: continue
            return px(ThemeResolver.parseDimensionDp(ThemeResolver.resolveValue(v, ctx.theme.vars, 0), 8f))
        }
        return px(8f)
    }

    private fun buildCardWithVars(
        card: CardConfig,
        ctx: VitroCardContext,
        fillViewport: Boolean,
        childBuilder: CardBuilder,
        stackMod: CardModStyle,
    ): View {
        val verticalGap = cardGap(ctx, "vertical-stack-card-gap", "stack-card-gap")
        val horizontalGap = cardGap(ctx, "horizontal-stack-card-gap", "stack-card-gap")
        val gridGap = cardGap(ctx, "grid-card-gap")
        fun LinearLayout.applyStackPadding() {
            stackMod.paddingInsets?.let { p ->
                setPadding(ctx.px(p.left), ctx.px(p.top), ctx.px(p.right), ctx.px(p.bottom))
            }
        }
        // Anything outside Vitro's supported set goes to an island on purpose (CardCompat), decided once per card.
        val built: View = if (card.inIsland(IslandScripts.of(ctx.resources))) {
            WebIslandCardView(ctx, card, fillViewport = fillViewport, adopted = adoptIsland(card, fillViewport))
        } else when (card.type) {
            // Layout containers
            "vertical-stack" -> LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                applyStackPadding()
                card.childCards.forEachIndexed { idx, child ->
                    addView(wrapVisibility(child, buildCard(child, ctx, false), ctx), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        if (idx > 0) topMargin = verticalGap
                    })
                }
            }
            "horizontal-stack" -> LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                applyStackPadding()
                val childH = if (card.childCards.size >= 2) MATCH_PARENT else WRAP_CONTENT
                card.childCards.forEachIndexed { idx, child ->
                    addView(wrapVisibility(child, buildCard(child, ctx, false), ctx), LinearLayout.LayoutParams(0, childH, 1f).apply {
                        if (idx > 0) marginStart = horizontalGap
                    })
                }
            }
            "grid" -> LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                applyStackPadding()
                val cols = card.gridColumns()
                card.childCards.chunked(cols).forEachIndexed { rIdx, chunk ->
                    val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    val childH = if (chunk.size >= 2) MATCH_PARENT else WRAP_CONTENT
                    chunk.forEachIndexed { cIdx, child ->
                        r.addView(wrapVisibility(child, buildCard(child, ctx, false), ctx), LinearLayout.LayoutParams(0, childH, 1f).apply {
                            if (cIdx > 0) marginStart = gridGap
                        })
                    }
                    addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        if (rIdx > 0) topMargin = gridGap
                    })
                }
            }
            "conditional" -> {
                val child = card.childCards.firstOrNull()
                // The conditional card stands in for its card, in the grid as anywhere else.
                if (child == null) View(context) else buildCard(child, ctx, fillViewport, layout = ctx.cardLayout)
            }
            // Tier 1: Built-in HA cards
            "tile" -> TileCardView(ctx, card)
            "entities" -> EntitiesCardView(ctx, card)
            // HA wraps the passing entities in its inner card (an entities card by default): its title and toggle.
            "entity-filter" -> EntitiesCardView(ctx, EntityFilterConfig.innerCard(card))
            "glance" -> GlanceCardView(ctx, card)
            "button" -> ButtonCardView(ctx, card)
            // HA's legacy alias: `hui-entity-button-card` is the button card, and without an entity HA shows its error.
            "entity-button" -> if (card.entity != null) ButtonCardView(ctx, card) else HaErrorCardView(ctx, "Entity must be specified")
            "heading" -> HeadingCardView(ctx, card)
            "clock" -> ClockCardView(ctx, card)
            "sensor" -> EntityCardView(ctx, card)
            "history-graph" -> HistoryGraphCardView(ctx, card)
            "gauge" -> GaugeCardView(ctx, card)
            "thermostat" -> ThermostatCardView(ctx, card)
            "weather-forecast" -> WeatherCardView(ctx, card)
            "markdown" -> MarkdownCardView(ctx, card)
            "picture", "picture-entity", "picture-glance" -> PictureCardView(ctx, card)
            "media-control" -> MediaControlCardView(ctx, card)
            "entity" -> EntityCardView(ctx, card)
            "light" -> LightCardView(ctx, card)
            "alarm-panel" -> AlarmPanelCardView(ctx, card)
            "todo-list" -> TodoListCardView(ctx, card)
            "statistics-graph" -> StatisticsGraphCardView(ctx, card)
            "area" -> AreaCardView(ctx, card)
            "picture-elements" -> PictureElementsCardView(ctx, card)
            "humidifier" -> HumidifierCardView(ctx, card)
            "calendar" -> CalendarCardView(ctx, card)
            "logbook" -> LogbookCardView(ctx, card)
            "shopping-list" -> ShoppingListCardView(ctx, card)
            "section" -> HaErrorCardView(ctx, SmallCardConfigs.SECTION_CARD_ERROR)
            "statistic" -> SmallCardConfigs.statisticError(card)?.let { HaErrorCardView(ctx, it) } ?: StatisticCardView(ctx, card)
            "date" -> DateCardView(ctx, card)
            "plant-status" -> if (card.entity?.substringBefore('.') == "plant") PlantStatusCardView(ctx, card)
                else HaErrorCardView(ctx, "Specify an entity from within the plant domain")
            "toggle-group" -> if ((card.raw.optJSONArray("entities")?.length() ?: 0) > 0) ToggleGroupCardView(ctx, card)
                else HaErrorCardView(ctx, "Entities are required")
            "shortcut" -> ShortcutCardView(ctx, card)
            "alert" -> SmallCardConfigs.alertError(card)?.let { HaErrorCardView(ctx, it) } ?: AlertCardView(ctx, card)
            "iframe" -> SmallCardConfigs.iframeError(card)?.let { HaErrorCardView(ctx, it) }
                ?: IframeCardView(ctx, card, fillViewport, adopted = adoptIsland(card, fillViewport))
            // Tier 2: Popular HACS Fast-Paths
            // Mushroom's light, fan and cover cards with their controls row; without one, the tile draws them.
            "custom:mushroom-light-card" -> if (HaGridSize.mushroomHasControls(card.type, card.raw, stateForBuild(card))) MushroomLightCardView(ctx, card) else TileCardView(ctx, card)
            "custom:mushroom-fan-card" -> if (HaGridSize.mushroomHasControls(card.type, card.raw, stateForBuild(card))) MushroomFanCardView(ctx, card) else TileCardView(ctx, card)
            "custom:mushroom-cover-card" -> if (HaGridSize.mushroomHasControls(card.type, card.raw, stateForBuild(card))) MushroomCoverCardView(ctx, card) else TileCardView(ctx, card)
            "custom:mushroom-entity-card",
            "custom:mushroom-person-card",
            "custom:mushroom-template-card" -> TileCardView(ctx, card)
            "custom:mushroom-title-card" -> MushroomTitleCardView(ctx, card)
            "custom:mushroom-chips-card" -> MushroomChipsCardView(ctx, card)
            "custom:mushroom-climate-card" -> MushroomClimateCardView(ctx, card)
            "custom:bubble-card" -> BubbleCardView(ctx, card)
            "custom:mini-graph-card" -> SensorGraphCardView(ctx, card)
            "custom:clock-weather-card" -> WeatherCardView(ctx, card)
            "custom:modern-circular-gauge" -> ModernCircularGaugeCardView(ctx, card)
            "custom:bar-card" -> BarCardView(ctx, card)
            "custom:mushroom-media-player-card" -> MushroomMediaPlayerCardView(ctx, card)
            "custom:mushroom-lock-card" -> MushroomLockCardView(ctx, card)
            "custom:mushroom-alarm-control-panel-card" -> MushroomAlarmControlPanelCardView(ctx, card)
            "custom:mushroom-vacuum-card" -> MushroomVacuumCardView(ctx, card)
            "custom:mushroom-humidifier-card" -> MushroomHumidifierCardView(ctx, card)
            "custom:mushroom-number-card" -> MushroomNumberCardView(ctx, card)
            "custom:mushroom-select-card" -> MushroomSelectCardView(ctx, card)
            "custom:mini-media-player" -> MiniMediaPlayerCardView(ctx, card)
            "custom:auto-entities" -> AutoEntitiesCardView(ctx, card, childBuilder)
            "custom:stack-in-card" -> StackInCardView(ctx, card, childBuilder)
            "custom:vertical-stack-in-card" -> VerticalStackInCardView(ctx, card, childBuilder)
            "custom:layout-card" -> LayoutCardView(ctx, card, childBuilder)
            "custom:button-card" -> CustomButtonCardView(ctx, card)
            // Mushroom's empty card draws nothing outside the editor; it only holds its grid slot.
            "custom:mushroom-empty-card" -> View(context)
            "custom:swipe-card" -> SwipeCardView(ctx, card, childBuilder)
            "custom:weather-card" -> LegacyWeatherCardView(ctx, card)
            // Tier 3: Arbitrary unrecognized custom cards -> stripped Micro-Web Island
            else -> WebIslandCardView(ctx, card, fillViewport = fillViewport, adopted = adoptIsland(card, fillViewport))
        }
        // An island that loads card-mod styles its card itself: styling the native frame too would apply it twice
        // (a 0.5 opacity would show as 0.25). Only for the island drawing this card: a `conditional` returns its
        // child's island, which never receives the conditional's own card_mod.
        val islandStyles = built is WebIslandCardView && built.config === card && IslandScripts.of(ctx.resources).cardMod
        if (!islandStyles) (built as? CardSurface)?.applyCardMod(card)
        // swipe-card has no card of its own (no background to restyle): its `:host` opacity still applies to the pager.
        if (built is SwipeCardView) stackMod.opacity?.let { built.restingAlpha = it.coerceIn(0f, 1f); built.alpha = built.restingAlpha }
        // `margin` on the card insets it inside its slot, as an outer margin does in the browser.
        // Not around an island that applies card-mod: its `margin` is drawn there, and twice would double the inset.
        val margin = stackMod.marginInsets?.takeUnless { islandStyles } ?: return built
        return FrameLayout(context).apply {
            setPadding(px(margin.left), px(margin.top), px(margin.right), px(margin.bottom))
            addView(built, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    private fun wrapVisibility(
        card: CardConfig,
        view: View,
        ctx: VitroCardContext,
        extra: List<VisibilityCondition> = emptyList(),
    ): View {
        val conds = extra + card.visibility + VisibilityCondition.parseList(card.raw.optJSONArray("conditions"))
        if (conds.isEmpty()) return view
        val transitionMs = ThemeResolver.parseCardMod(card.raw.optJSONObject("card_mod"), ctx.theme.vars).transitionOpacityMs
        return wrapConditions(conds, view, ctx, transitionMs)
    }

    /**
     * Shows [view] only while every condition holds, re-checking live as the referenced entities change, once a
     * minute for `time` conditions, and until the signed-in user is known for `user` conditions.
     */
    private fun wrapConditions(
        conds: List<VisibilityCondition>,
        view: View,
        ctx: VitroCardContext,
        transitionMs: Long? = null,
    ): View {
        if (conds.isEmpty()) return view
        val widthDp = (resources.displayMetrics.widthPixels / dp).toInt()
        var shown: Boolean? = null
        val update = {
            val userId = ctx.haUser().optString("id", "").ifEmpty { null }
            val nowMs = ctx.now()
            val visible = conds.all { VisibilityCondition.evaluateAt(it, nowMs, store::get, widthDp, userId) }
            if (visible != shown) {
                shown = visible
                crossfade(view, visible, transitionMs ?: DEFAULT_FADE_MS)
            }
        }
        update()
        val refs = LinkedHashSet<String>()
        conds.forEach { it.referencedEntities(refs) }
        fun subscribe() = refs.map { eid -> store.subscribe(eid) { ctx.main.post(update) } }
        var unsubs = subscribe()
        // HA's user arrives after the first render, so user-gated cards re-check on the same tick as time-gated ones.
        val ticks = conds.any { it.dependsOnTime() || it.dependsOnUser() }
        view.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // Back after a detach: listen again, and catch up with what changed meanwhile.
                if (unsubs.isEmpty() && refs.isNotEmpty()) {
                    unsubs = subscribe()
                    if (!ticks) update()
                }
                if (ticks) {
                    update() // The clock may have moved on while detached.
                    addPeriodicCheck(update, secondBoundaries(conds))
                }
            }
            override fun onViewDetachedFromWindow(v: View) {
                unsubs.forEach { it() }
                unsubs = emptyList()
                if (ticks) removePeriodicCheck(update)
                // A cancelled fade never runs its end action: snap to the state it was heading for.
                v.animate().cancel()
                shown?.let { snapVisibility(v, it) }
            }
        })
        return view
    }

    /**
     * Shows or hides a conditional card. Detached (first bind, tests) it flips at once; on screen it fades its
     * opacity over [durationMs] (`card_mod` `transition: opacity …`, else 300 ms) and only goes GONE at the end.
     */
    private fun crossfade(view: View, show: Boolean, durationMs: Long) {
        view.animate().cancel()
        if (!view.isAttachedToWindow || durationMs <= 0L) {
            snapVisibility(view, show)
            return
        }
        val target = restingAlpha(view)
        if (show) {
            if (view.visibility != VISIBLE) {
                view.alpha = 0f
                view.visibility = VISIBLE
            }
            view.animate().alpha(target).setDuration(durationMs).start()
        } else {
            if (view.visibility != VISIBLE) {
                snapVisibility(view, false)
                return
            }
            view.animate().alpha(0f).setDuration(durationMs).withEndAction {
                if (view.alpha == 0f) view.visibility = GONE
            }.start()
        }
    }

    private fun snapVisibility(view: View, show: Boolean) {
        view.visibility = if (show) VISIBLE else GONE
        view.alpha = if (show) restingAlpha(view) else 0f
    }

    /** A shown card's opacity: its `card_mod` `opacity` when it sets one, else fully opaque. */
    private fun restingAlpha(view: View): Float = when (view) {
        is SwipeCardView -> view.restingAlpha
        else -> (view as? CardSurface)?.activeCardMod?.opacity?.coerceIn(0f, 1f) ?: 1f
    }

    // ---- Periodic visibility re-checks (`time` and `user` conditions) ----

    /** Seconds of the day that `time` conditions start or end at off the minute (`08:00:30`), per check. */
    private val checkBoundaries = HashMap<() -> Unit, List<Int>>()

    private fun addPeriodicCheck(check: () -> Unit, boundaries: List<Int> = emptyList()) {
        periodicChecks += check
        if (boundaries.isNotEmpty()) checkBoundaries[check] = boundaries
        if (isAttachedToWindow) scheduleTick()
    }

    private fun removePeriodicCheck(check: () -> Unit) {
        periodicChecks -= check
        checkBoundaries -= check
        if (periodicChecks.isEmpty()) removeCallbacks(periodicTick)
    }

    /** The `time` bounds in [conds] (nested ones too) that don't fall on a minute: the minute tick would miss them. */
    private fun secondBoundaries(conds: List<VisibilityCondition>): List<Int> {
        val out = ArrayList<Int>()
        fun walk(c: VisibilityCondition) {
            when (c) {
                is VisibilityCondition.Time -> listOfNotNull(c.after, c.before).filterTo(out) { it % 60 != 0 }
                is VisibilityCondition.And -> c.conditions.forEach(::walk)
                is VisibilityCondition.Or -> c.conditions.forEach(::walk)
                is VisibilityCondition.Not -> c.conditions.forEach(::walk)
                else -> Unit
            }
        }
        conds.forEach(::walk)
        return out
    }

    /**
     * Next tick just after the coming minute boundary, so `after: "07:00"` flips at 07:00, not up to 60 s late; or
     * sooner, just after a bound with seconds (`08:00:30`) when one comes first.
     */
    private fun scheduleTick() {
        removeCallbacks(periodicTick)
        if (periodicChecks.isEmpty()) return
        val now = System.currentTimeMillis()
        var delay = PERIODIC_CHECK_MS - now % PERIODIC_CHECK_MS + 250L
        if (checkBoundaries.isNotEmpty()) {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
            // All from one clock sample: a second read could cross the boundary and push it to tomorrow.
            val nowSec = cal.get(java.util.Calendar.HOUR_OF_DAY) * 3_600 + cal.get(java.util.Calendar.MINUTE) * 60 +
                cal.get(java.util.Calendar.SECOND)
            val msInSecond = cal.get(java.util.Calendar.MILLISECOND)
            for (b in checkBoundaries.values.flatten()) {
                val ahead = ((b - nowSec) % 86_400 + 86_400) % 86_400
                val ms = (if (ahead == 0) 86_400 else ahead) * 1_000L - msInSecond + 250L
                if (ms in 1 until delay) delay = ms
            }
        }
        postDelayed(periodicTick, delay)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        tidyMoreInfo()
        scheduleTick()
        // Back on screen after fonts changed elsewhere, or after another dashboard took the font loader (which drops
        // the loads this view was waiting for, with no notice): build again, taking the loader back.
        val retired = fontsFor?.let { !WebFonts.isConfiguredFor(it.first, it.second) } == true
        if (fontsStale || retired) rebuildForFonts()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(periodicTick)
        super.onDetachedFromWindow()
        // A detach can cut a sheet's exit short (its animation is cancelled, so it never calls back): finish it here.
        tidyMoreInfo()
    }

    /**
     * Removes every More Info sheet that's closed or closing and sets the dashboard's focus and TalkBack state from
     * whether a live sheet is still open. Safe to run any number of times.
     */
    private fun tidyMoreInfo() {
        if (moreInfo?.isDismissed == true) moreInfo = null
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child is MoreInfoSheet && child !== moreInfo && child.isDismissed) removeViewAt(i)
        }
        blockDashboardFocus(moreInfo != null)
    }

    companion object {
        /** Whether a card is drawn through a hardware layer: never when it casts a shadow, which the layer's bounds would clip. */
        internal fun cardLayerWanted(themeElevationDp: Float, viewElevation: Float): Boolean =
            themeElevationDp == 0f && viewElevation == 0f

        /** The share of the dashboard's area from which a card gets its own layer. */
        internal const val BIG_CARD_SHARE = 0.07f

        internal fun isBigCard(w: Int, h: Int, rootW: Int, rootH: Int): Boolean =
            rootW > 0 && rootH > 0 && w.toLong() * h >= BIG_CARD_SHARE * rootW.toLong() * rootH

        private const val TAG = "Glassworks.Vitro"
        private const val PERIODIC_CHECK_MS = 60_000L
        private const val DEFAULT_FADE_MS = 300L

        /** A badge's text: `name · state` when the name shows, else the formatted state (`21.5 °C`, `Closed`). */
        fun badgeText(badge: CardConfig, s: EntityState?): String {
            val raw = badge.raw
            val name = badge.name ?: s?.friendlyName ?: badge.entity.orEmpty()
            val showName = raw.optBoolean("show_name", badge.name != null)
            val showState = raw.optBoolean("show_state", true)
            val state = s?.formattedState() ?: "…"
            return when {
                showName && showState -> "$name · $state"
                showName -> name
                else -> state
            }
        }

        /**
         * A badge's two lines as HA 2026 draws them: `(name, state)`. The name is null unless `show_name` is on
         * (or a `name` is set); the state is null when `show_state: false`.
         */
        fun badgeLines(badge: CardConfig, s: EntityState?): Pair<String?, String?> {
            val raw = badge.raw
            val showName = raw.optBoolean("show_name", badge.name != null)
            val showState = raw.optBoolean("show_state", true)
            val name = if (showName) badge.name ?: s?.friendlyName ?: badge.entity.orEmpty() else null
            // `show_state: false` hides the state even when no name shows (an icon-only badge).
            val state = if (showState) s?.formattedState() ?: "…" else null
            return name to state
        }

        /** HA's badge icon colors: green for home and locked, red for unlocked, sensor blue, grey when inactive. */
        fun badgeIconColor(theme: ResolvedTheme, s: EntityState?): Int {
            if (s == null) return theme.stateIconColor
            return when (s.domain) {
                "person", "device_tracker" -> if (s.state == "home") HOME_GREEN else theme.stateInactiveColor
                "lock" -> when (s.state) {
                    "locked" -> HOME_GREEN
                    "unlocked", "open" -> UNLOCKED_RED
                    else -> theme.stateIconColor
                }
                "binary_sensor" -> if (s.state == "on") theme.activeColor("binary_sensor") else theme.stateInactiveColor
                "sensor" -> SENSOR_BLUE
                else -> if (s.isActive) theme.activeColor(s.domain) else theme.stateIconColor
            }
        }

        const val HOME_GREEN = 0xFF4CAF50.toInt()
        const val UNLOCKED_RED = 0xFFF44336.toInt()
        const val SENSOR_BLUE = 0xFF03A9F4.toInt()
        private const val MAX_CACHED_VIEWS = 3
        private const val MAX_BACK_STACK = 16
    }
}

/** Radial backdrop whose radius follows its bounds, reaching the farthest corner like CSS `radial-gradient`. */
private class RadialBackdrop(colors: IntArray) : GradientDrawable(Orientation.TOP_BOTTOM, colors) {
    init {
        gradientType = RADIAL_GRADIENT
        setGradientCenter(0.5f, 0.5f)
    }

    override fun onBoundsChange(r: Rect) {
        gradientRadius = (hypot(r.width().toFloat(), r.height().toFloat()) / 2f).coerceAtLeast(1f)
        super.onBoundsChange(r)
    }
}
