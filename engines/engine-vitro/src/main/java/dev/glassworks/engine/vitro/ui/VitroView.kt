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
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.AlarmPanelCardView
import dev.glassworks.engine.vitro.cards.AreaCardView
import dev.glassworks.engine.vitro.cards.AutoEntitiesCardView
import dev.glassworks.engine.vitro.cards.CardBuilder
import dev.glassworks.engine.vitro.cards.LayoutCardView
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
import dev.glassworks.engine.vitro.cards.ModernCircularGaugeCardView
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.cards.MushroomChipsCardView
import dev.glassworks.engine.vitro.cards.PictureCardView
import dev.glassworks.engine.vitro.cards.PictureElementsCardView
import dev.glassworks.engine.vitro.cards.SensorGraphCardView
import dev.glassworks.engine.vitro.cards.StatisticsGraphCardView
import dev.glassworks.engine.vitro.cards.ThermostatCardView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.cards.TodoListCardView
import dev.glassworks.engine.vitro.cards.WeatherCardView
import dev.glassworks.engine.vitro.cards.WebIslandCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HistoryPoint
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
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
    backgroundColor: Int,
) : FrameLayout(context) {
    private val dp = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()

    private val shell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
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
                viewCache.remove(idx)?.let { contentHost.removeView(it) }
                overlayFrames.remove(idx)?.let { overlayLayer.removeView(it) }
            }
            if (views.getOrNull(currentViewIndex)?.subview == true) renderView(currentViewIndex, forceRebuild = true)
        }
    /**
     * The dashboard's Lovelace resources. Islands load them into their page, and a card-mod-only fallback routes on
     * whether card-mod is among them, so a change rebuilds the cached views and the current one.
     */
    var resourcesList: List<LovelaceResource> = emptyList()
        set(v) {
            if (field == v) return
            field = v
            viewCache.clear()
            builtScripts.clear()
            contentHost.removeAllViews()
            clearOverlay()
            dashboard?.let { renderView(currentViewIndex, forceRebuild = true) }
        }
    var lastMetrics: VitroMetrics = VitroMetrics()
        private set

    var onMetrics: ((VitroMetrics) -> Unit)? = null
    var onViewSelected: ((ViewConfig) -> Unit)? = null
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

    init {
        MdiIcons.init(context)
        setBackgroundColor(backgroundColor)
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
        dashboard = null
        viewBackStack.clear()
        viewCache.clear()
        builtScripts.clear()
        contentHost.removeAllViews()
        clearOverlay()
        resourcesList = emptyList()
    }

    fun setThemes(catalog: ThemeCatalog) {
        if (catalog == themeCatalog) return
        themeCatalog = catalog
        viewCache.clear()
        builtScripts.clear()
        contentHost.removeAllViews()
        clearOverlay()
        dashboard?.let { renderView(currentViewIndex, forceRebuild = true) }
    }

    /** Applies the user's selected Home Assistant theme (`frontend/get_user_data` `core.selectedTheme`). */
    fun setUserTheme(themeName: String?, dark: Boolean?) {
        val nextTheme = themeName?.takeIf { it.isNotBlank() && it != "Backend-selected" }
        val nextDark = dark ?: darkMode
        if (nextTheme == userThemeName && nextDark == darkMode) return
        userThemeName = nextTheme
        darkMode = nextDark
        viewCache.clear()
        builtScripts.clear()
        contentHost.removeAllViews()
        clearOverlay()
        dashboard?.let { renderView(currentViewIndex, forceRebuild = true) }
    }

    fun setDashboard(config: DashboardConfig, initialViewPath: String?) {
        dashboard = config
        viewBackStack.clear()
        viewCache.clear()
        builtScripts.clear()
        contentHost.removeAllViews()
        clearOverlay()
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

    fun closeMoreInfo(): Boolean {
        val sheet = moreInfo ?: return false
        moreInfo = null
        sheet.dismiss()
        return true
    }

    fun openMoreInfo(entityId: String) {
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
        }
        sheetRef = sheet
        sheet.show(entityId)
        moreInfo = sheet
        addView(sheet, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun resolveViewIndex(config: DashboardConfig, path: String?): Int {
        if (config.views.isEmpty()) return 0
        if (path.isNullOrBlank()) return 0
        config.views.indexOfFirst { it.path == path }.takeIf { it >= 0 }?.let { return it }
        path.toIntOrNull()?.takeIf { it in config.views.indices }?.let { return it }
        return 0
    }

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
                setOnClickListener { renderView(idx, forceRebuild = false) }
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
        for (t in tabs) {
            val active = t.viewIndex == selected
            val color = if (active) theme.primaryColor else theme.secondaryTextColor
            t.label.setTextColor(color)
            t.icon?.tintColor = color
            t.indicator.setBackgroundColor(theme.primaryColor)
            t.indicator.visibility = if (active) VISIBLE else INVISIBLE
        }
    }

    private fun renderView(index: Int, forceRebuild: Boolean, pushHistory: Boolean = true) {
        val cfg = dashboard ?: return
        val viewCfg = cfg.views.getOrNull(index) ?: return
        val t0 = SystemClock.elapsedRealtime()
        val prev = currentViewIndex
        if (pushHistory && !forceRebuild && prev != index && cfg.views.getOrNull(prev) != null) {
            viewBackStack.addLast(prev)
            while (viewBackStack.size > MAX_BACK_STACK) viewBackStack.removeFirst()
        }
        currentViewIndex = index
        hideStatus()

        val theme = ThemeResolver.resolve(themeCatalog, darkMode, viewCfg.theme ?: userThemeName, viewCfg.background)
        VitroFonts.face = VitroFonts.faceFor(theme)
        applyBackdrop(theme)
        highlightTab(index, theme)
        onThemeResolved?.invoke(theme)
        onViewSelected?.invoke(viewCfg)

        val cached = if (!forceRebuild) viewCache[index] else null
        val viewRoot = if (cached != null) {
            cached
        } else {
            val cardCtx = contextFactory?.invoke(theme) ?: return
            val pinned = ArrayList<Pair<View, FrameLayout.LayoutParams>>()
            pendingPinned = pinned
            val built = try {
                buildViewTree(viewCfg, cardCtx)
            } finally {
                pendingPinned = null
            }
            viewCache.remove(index)?.let { contentHost.removeView(it) }
            overlayFrames.remove(index)?.let { overlayLayer.removeView(it) }
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

        val elapsed = SystemClock.elapsedRealtime() - t0
        // The report follows the tree on screen: a cached tree keeps the card-mod state it was built with.
        val scripts = builtScripts[index] ?: IslandScripts.of(resourcesList)
        val compat = CompatChecker.check(viewCfg, scripts)
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
            islands = compat.islands.map { "${it.path}: ${it.verdict.reasons.joinToString("; ")}" },
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
            viewCache.remove(oldest)?.let { contentHost.removeView(it) }
            overlayFrames.remove(oldest)?.let { overlayLayer.removeView(it) }
        }
    }

    private fun clearOverlay() {
        overlayFrames.clear()
        overlayLayer.removeAllViews()
    }

    /**
     * Sends a `position: fixed` card to the current view's overlay instead of the scrolling layout. False (and
     * nothing happens) for every other card, or outside a view build.
     */
    private fun pinIfFixed(card: CardConfig, ctx: VitroCardContext, sectionVisibility: List<VisibilityCondition> = emptyList()): Boolean {
        val sink = pendingPinned ?: return false
        val mod = ThemeResolver.parseCardMod(card.raw.optJSONObject("card_mod"), ctx.theme.vars)
        if (!mod.positionFixed) return false
        // A fixed card still lives in its section: when the section hides, so does the card.
        val view = wrapVisibility(card, buildCard(card, ctx, fillViewport = false), ctx, sectionVisibility)
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

    private fun applyBackdrop(theme: ResolvedTheme) {
        when (val bg = theme.background) {
            is ThemeBackground.Solid -> setBackgroundColor(bg.color)
            is ThemeBackground.LinearGradient -> background = CssBackgroundDrawable(
                CssBackground(listOf(CssBackground.Linear(bg.angleDeg, bg.stops ?: bg.colors.map { CssBackground.Stop(it, null, null) })), null),
                dp,
            )
            is ThemeBackground.RadialGradient -> background = RadialBackdrop(bg.colors)
            is ThemeBackground.Layers -> background = CssBackgroundDrawable(bg.css, dp)
            is ThemeBackground.ImageUrl -> {
                setBackgroundColor(bg.fallbackColor)
                contextFactory?.invoke(theme)?.loadImage(bg.url) { bmp ->
                    if (bmp != null) background = BitmapDrawable(resources, bmp)
                }
            }
        }
    }

    private fun buildViewTree(view: ViewConfig, ctx: VitroCardContext): View {
        // Panel view: single full-viewport card (e.g. custom:mirror-home-card, custom:tablet-home-card).
        if (view.type == "panel" && view.cards.isNotEmpty()) {
            val card = view.cards.first()
            return buildCard(card, ctx, fillViewport = true)
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val widthDp = (resources.displayMetrics.widthPixels / dp).toInt()
        val gap = px(ctx.theme.cardGapDp)
        val outerPad = px(ctx.theme.viewPaddingDp)

        val container: View = if (view.type == "sections" && view.sections.isNotEmpty()) {
            buildSectionsLayout(view, ctx, widthDp)
        } else {
            buildMasonryLayout(view.cards, ctx, widthDp, outerPad, gap)
        }
        val withBadges = if (view.badges.isNotEmpty()) {
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
                val backRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(outerPad, outerPad, outerPad, px(4f))
                    isClickable = true
                    setOnClickListener {
                        if (view.backPath == null && viewBackStack.isNotEmpty()) goBack() else ctx.navigate(view.backPath ?: "/")
                    }
                }
                backRow.addView(TextView(context).apply {
                    text = "←  ${view.title}"
                    textSize = 18f
                    setTextColor(ctx.theme.primaryTextColor)
                    typeface = VitroFonts.typeface(context, Inter.MEDIUM, 18f)
                })
                addView(backRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
            if (showIcon) icon.setIcon(MdiIcons.resolveIconName(badge.icon, s), badgeIconColor(theme, s))
        }
        render(store[eid])
        val tap = ActionConfig.parse(badge.raw.optJSONObject("tap_action"), "more-info")
        pill.setOnClickListener { ctx.performAction(tap, eid) }
        pill.bindHoldAction(ctx, ActionConfig.parse(badge.raw.optJSONObject("hold_action"), "none"), eid)
        val unsub = store.subscribe(eid) { s -> ctx.main.post { render(s) } }
        pill.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { unsub() }
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
            if (pinIfFixed(card, ctx, section.visibility)) {
                // HA keeps the fixed card's grid slot (empty, but it still takes a row gap) while it's shown.
                grid.addView(wrapVisibility(card, View(context), ctx), HaGridLayout.LayoutParams(columns))
                continue
            }
            val size = HaGridSize.of(card)
            val cardView = wrapVisibility(card, buildCard(card, ctx, fillViewport = false), ctx)
            val fixed = size.rows?.let { r -> px(r * (rowHeight + rowGap) - rowGap) }
            grid.addView(cardView, HaGridLayout.LayoutParams(size.columns?.coerceAtMost(columns) ?: columns, fixed))
        }
        return grid
    }

    private fun buildMasonryLayout(
        cards: List<CardConfig>,
        ctx: VitroCardContext,
        widthDp: Int,
        outerPad: Int,
        gap: Int,
    ): View {
        val cols = when {
            widthDp < 540 -> 1
            widthDp < 880 -> 2
            widthDp < 1240 -> 3
            else -> 4
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(outerPad, outerPad, outerPad, outerPad)
        }
        val columns = (0 until cols).map { i ->
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                row.addView(this, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = gap
                })
            }
        }
        val weights = IntArray(cols)
        for (card in cards) {
            if (pinIfFixed(card, ctx)) continue
            // Place each card into the lightest column for a balanced masonry flow.
            var best = 0
            for (i in 1 until cols) if (weights[i] < weights[best]) best = i
            val v = wrapVisibility(card, buildCard(card, ctx, fillViewport = false), ctx)
            columns[best].addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = gap
            })
            weights[best] += estimateWeight(card)
        }
        return row
    }

    private fun estimateWeight(card: CardConfig): Int = when (card.type) {
        "entities" -> (card.entities().size * 2).coerceAtLeast(3)
        "history-graph", "thermostat", "picture", "picture-entity" -> 6
        "clock", "sensor", "gauge", "weather-forecast", "entity", "area" -> 4
        "light", "statistics-graph", "todo-list", "picture-elements", "calendar", "logbook" -> 6
        "humidifier" -> 7
        "alarm-panel" -> 8
        "grid", "custom:stack-in-card", "custom:vertical-stack-in-card", "custom:layout-card" -> card.childCards.size * 2
        "custom:mini-media-player", "custom:mushroom-media-player-card", "custom:auto-entities" -> 4
        else -> 2
    }

    private fun buildCard(card: CardConfig, parentCtx: VitroCardContext, fillViewport: Boolean): View {
        val ctx = if (card.theme != null && card.theme != parentCtx.theme.name) {
            val overrideTheme = ThemeResolver.resolve(themeCatalog, darkMode, card.theme)
            contextFactory?.invoke(overrideTheme) ?: parentCtx
        } else {
            parentCtx
        }
        // Custom properties set on a card (`ha-card { --vertical-stack-card-gap: 8px }`) inherit into the cards
        // inside it, as they do through the browser's shadow roots; `var()` in them sees the inherited ones.
        val outerVars = inheritedCardVars
        val outerCtxVars = ctx.cardVars
        val stackMod = ThemeResolver.parseCardMod(card.raw.optJSONObject("card_mod"), ctx.theme.vars + outerVars)
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
        inheritedCardVars = scope
        ctx.cardVars = scope
        try {
            return buildCardWithVars(card, ctx, fillViewport, childBuilder, stackMod)
        } finally {
            inheritedCardVars = outerVars
            ctx.cardVars = outerCtxVars
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
        val built: View = if (card.compat.inIsland(IslandScripts.of(ctx.resources))) {
            WebIslandCardView(ctx, card, fillViewport = fillViewport)
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
                if (child == null) View(context) else buildCard(child, ctx, fillViewport)
            }
            // Tier 1: Built-in HA cards
            "tile" -> TileCardView(ctx, card)
            "entities", "entity-filter" -> EntitiesCardView(ctx, card)
            "glance" -> GlanceCardView(ctx, card)
            "button" -> ButtonCardView(ctx, card)
            "heading" -> HeadingCardView(ctx, card)
            "clock" -> ClockCardView(ctx, card)
            "sensor", "history-graph" -> SensorGraphCardView(ctx, card)
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
            // Tier 2: Popular HACS Fast-Paths
            "custom:mushroom-entity-card",
            "custom:mushroom-light-card",
            "custom:mushroom-fan-card",
            "custom:mushroom-cover-card",
            "custom:mushroom-person-card",
            "custom:mushroom-template-card" -> TileCardView(ctx, card)
            "custom:mushroom-title-card" -> HeadingCardView(ctx, card)
            "custom:mushroom-chips-card" -> MushroomChipsCardView(ctx, card)
            "custom:mushroom-climate-card" -> ThermostatCardView(ctx, card)
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
            // Tier 3: Arbitrary unrecognized custom cards -> stripped Micro-Web Island
            else -> WebIslandCardView(ctx, card, fillViewport = fillViewport)
        }
        // An island that loads card-mod styles its card itself: styling the native frame too would apply it twice
        // (a 0.5 opacity would show as 0.25).
        val islandStyles = built is WebIslandCardView && IslandScripts.of(ctx.resources).cardMod
        if (!islandStyles) (built as? CardSurface)?.applyCardMod(card)
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
            val visible = conds.all { it.evaluate(store::get, widthDp, userId) }
            if (visible != shown) {
                shown = visible
                crossfade(view, visible, transitionMs ?: DEFAULT_FADE_MS)
            }
        }
        update()
        val refs = LinkedHashSet<String>()
        conds.forEach { it.referencedEntities(refs) }
        val unsubs = refs.map { eid -> store.subscribe(eid) { ctx.main.post(update) } }
        // HA's user arrives after the first render, so user-gated cards re-check on the same tick as time-gated ones.
        val ticks = conds.any { it.dependsOnTime() || it.dependsOnUser() }
        view.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (ticks) {
                    update() // The clock may have moved on while detached.
                    addPeriodicCheck(update)
                }
            }
            override fun onViewDetachedFromWindow(v: View) {
                unsubs.forEach { it() }
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
    private fun restingAlpha(view: View): Float = (view as? CardSurface)?.activeCardMod?.opacity?.coerceIn(0f, 1f) ?: 1f

    // ---- Periodic visibility re-checks (`time` and `user` conditions) ----

    private fun addPeriodicCheck(check: () -> Unit) {
        periodicChecks += check
        if (isAttachedToWindow) scheduleTick()
    }

    private fun removePeriodicCheck(check: () -> Unit) {
        periodicChecks -= check
        if (periodicChecks.isEmpty()) removeCallbacks(periodicTick)
    }

    /** Next tick just after the coming minute boundary, so `after: "07:00"` flips at 07:00, not up to 60 s late. */
    private fun scheduleTick() {
        removeCallbacks(periodicTick)
        if (periodicChecks.isEmpty()) return
        val now = System.currentTimeMillis()
        postDelayed(periodicTick, PERIODIC_CHECK_MS - now % PERIODIC_CHECK_MS + 250L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleTick()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(periodicTick)
        super.onDetachedFromWindow()
    }

    companion object {
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
            val state = if (showState || !showName) s?.formattedState() ?: "…" else null
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
