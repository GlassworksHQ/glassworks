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
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.CardTier
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.model.SectionConfig
import dev.glassworks.engine.vitro.model.ViewConfig
import dev.glassworks.engine.vitro.model.VisibilityCondition
import dev.glassworks.engine.vitro.theme.ResolvedTheme
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
    private val statusLabel = TextView(context).apply {
        textSize = 14f
        setTextColor(0xFFA1A1AA.toInt())
        gravity = Gravity.CENTER
        typeface = Inter.typeface(context, Inter.REGULAR, 14f)
    }

    /** One top-bar tab and the dashboard view it opens. */
    private class TabEntry(val viewIndex: Int, val label: TextView, val icon: MdiIconView?, val indicator: View)

    private val tabs = ArrayList<TabEntry>()
    private var moreInfo: MoreInfoSheet? = null
    private val viewCache = LinkedHashMap<Int, View>()

    /** Views the user came from, newest last; [goBack] pops it. Capped at [MAX_BACK_STACK]. */
    private val viewBackStack = ArrayDeque<Int>()

    private val bannerText = TextView(context).apply {
        text = "Connection lost. Reconnecting…"
        textSize = 13f
        setTextColor(0xFFFFFFFF.toInt())
        typeface = Inter.typeface(context, Inter.MEDIUM, 13f)
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
            field = v
            updateTabVisibility()
        }
    var resourcesList: List<LovelaceResource> = emptyList()
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
        setBackgroundColor(backgroundColor)
        tabScroll.addView(tabBar, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        shell.addView(tabScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        shell.addView(contentHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        addView(shell, LayoutParams(MATCH_PARENT, MATCH_PARENT))
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

    fun setThemes(catalog: ThemeCatalog) {
        if (catalog == themeCatalog) return
        themeCatalog = catalog
        viewCache.clear()
        contentHost.removeAllViews()
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
        contentHost.removeAllViews()
        dashboard?.let { renderView(currentViewIndex, forceRebuild = true) }
    }

    fun setDashboard(config: DashboardConfig, initialViewPath: String?) {
        dashboard = config
        viewBackStack.clear()
        viewCache.clear()
        contentHost.removeAllViews()
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
                typeface = Inter.typeface(context, Inter.MEDIUM, 13f)
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
        applyBackdrop(theme)
        highlightTab(index, theme)
        onThemeResolved?.invoke(theme)
        onViewSelected?.invoke(viewCfg)

        val cached = if (!forceRebuild) viewCache[index] else null
        val viewRoot = if (cached != null) {
            cached
        } else {
            val cardCtx = contextFactory?.invoke(theme) ?: return
            buildViewTree(viewCfg, cardCtx).also { built ->
                viewCache.remove(index)?.let { contentHost.removeView(it) }
                viewCache[index] = built
                contentHost.addView(built, LayoutParams(MATCH_PARENT, MATCH_PARENT))
                trimViewCache(keep = index)
            }
        }

        // Keep-alive cache: toggle visibility instead of destroying built native views.
        for (i in 0 until contentHost.childCount) {
            val child = contentHost.getChildAt(i)
            child.visibility = if (child === viewRoot) VISIBLE else GONE
        }

        val elapsed = SystemClock.elapsedRealtime() - t0
        val tiers = CardRouter.tierCounts(viewCfg)
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
        )
        lastMetrics = metrics
        Log.i(
            TAG,
            "View '${metrics.viewTitle}' (${metrics.viewType}) built in ${metrics.buildTimeMs} ms " +
                "(cache=${metrics.cacheHit}, core=${metrics.nativeCoreCards}, hacs=${metrics.nativeHacsCards}, " +
                "islands=${metrics.webIslandCards}, entities=${metrics.subscribedEntities})",
        )
        onMetrics?.invoke(metrics)
    }

    /** Keeps at most [MAX_CACHED_VIEWS] built views alive, evicting the oldest one that is not [keep]. */
    private fun trimViewCache(keep: Int) {
        while (viewCache.size > MAX_CACHED_VIEWS) {
            val oldest = viewCache.keys.firstOrNull { it != keep } ?: return
            viewCache.remove(oldest)?.let { contentHost.removeView(it) }
        }
    }

    private fun applyBackdrop(theme: ResolvedTheme) {
        when (val bg = theme.background) {
            is ThemeBackground.Solid -> setBackgroundColor(bg.color)
            is ThemeBackground.LinearGradient -> {
                val orientation = when (((bg.angleDeg % 360f) + 360f) % 360f) {
                    in 45f..135f -> GradientDrawable.Orientation.LEFT_RIGHT
                    in 135f..225f -> GradientDrawable.Orientation.TOP_BOTTOM
                    in 225f..315f -> GradientDrawable.Orientation.RIGHT_LEFT
                    else -> GradientDrawable.Orientation.BOTTOM_TOP
                }
                background = GradientDrawable(orientation, bg.colors)
            }
            is ThemeBackground.RadialGradient -> background = RadialBackdrop(bg.colors)
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
        val outerPad = px(ctx.theme.cardGapDp)

        val container: View = if (view.type == "sections" && view.sections.isNotEmpty()) {
            buildSectionsLayout(view, ctx, widthDp, outerPad, gap)
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
        val body = if (view.subview) {
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
                    typeface = Inter.typeface(context, Inter.MEDIUM, 18f)
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
            typeface = Inter.typeface(context, weight, sp)
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

    private fun buildSectionsLayout(
        view: ViewConfig,
        ctx: VitroCardContext,
        widthDp: Int,
        outerPad: Int,
        gap: Int,
    ): View {
        val cols = when {
            widthDp < 560 -> 1
            widthDp < 920 -> minOf(2, view.maxColumns)
            widthDp < 1280 -> minOf(3, view.maxColumns)
            else -> minOf(4, view.maxColumns)
        }.coerceAtLeast(1)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(outerPad, outerPad, outerPad, outerPad)
        }
        val columns = (0 until cols).map { i ->
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                root.addView(this, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = gap
                })
            }
        }

        view.sections.forEachIndexed { idx, sec ->
            val col = columns[idx % cols]
            val built = wrapConditions(sec.visibility, buildSection(sec, ctx, gap), ctx)
            col.addView(built, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = px(14f)
            })
        }
        return root
    }

    private fun buildSection(section: SectionConfig, ctx: VitroCardContext, gap: Int): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        section.title?.let { title ->
            box.addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(ctx.theme.primaryTextColor)
                typeface = Inter.typeface(context, Inter.MEDIUM, 15f)
                setPadding(px(4f), 0, px(4f), px(8f))
            })
        }
        // Pack section cards in a 2-column sub-grid (matching HA's 12-column section units: <=6 = half, >6 = full).
        var pendingHalfRow: LinearLayout? = null
        // A half-width card left alone in its row keeps half the width: switch it to WRAP_CONTENT so the
        // 1px spacer on the right doesn't collapse a MATCH_PARENT row height to 1px.
        fun closeHalfRow() {
            val row = pendingHalfRow ?: return
            (row.getChildAt(0)?.layoutParams as? LinearLayout.LayoutParams)?.height = WRAP_CONTENT
            row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = gap })
            pendingHalfRow = null
        }
        for (card in section.cards) {
            val isHalf = isHalfWidth(card)
            val cardView = wrapVisibility(card, buildCard(card, ctx, fillViewport = false), ctx)
            if (isHalf) {
                val row = pendingHalfRow
                if (row == null) {
                    val newRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    newRow.addView(cardView, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                    box.addView(newRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = gap })
                    pendingHalfRow = newRow
                } else {
                    row.addView(cardView, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = gap })
                    pendingHalfRow = null
                }
            } else {
                closeHalfRow()
                box.addView(cardView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = gap })
            }
        }
        closeHalfRow()
        return box
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
        "grid" -> card.childCards.size * 2
        else -> 2
    }

    private fun buildCard(card: CardConfig, parentCtx: VitroCardContext, fillViewport: Boolean): View {
        val ctx = if (card.theme != null && card.theme != parentCtx.theme.name) {
            val overrideTheme = ThemeResolver.resolve(themeCatalog, darkMode, card.theme)
            contextFactory?.invoke(overrideTheme) ?: parentCtx
        } else {
            parentCtx
        }
        return when (card.type) {
            // Layout containers
            "vertical-stack" -> LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                card.childCards.forEachIndexed { idx, child ->
                    addView(wrapVisibility(child, buildCard(child, ctx, false), ctx), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        if (idx > 0) topMargin = px(8f)
                    })
                }
            }
            "horizontal-stack" -> LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                card.childCards.forEachIndexed { idx, child ->
                    addView(wrapVisibility(child, buildCard(child, ctx, false), ctx), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                        if (idx > 0) marginStart = px(8f)
                    })
                }
            }
            "grid" -> LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val cols = card.gridColumns()
                card.childCards.chunked(cols).forEachIndexed { rIdx, chunk ->
                    val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    chunk.forEachIndexed { cIdx, child ->
                        r.addView(wrapVisibility(child, buildCard(child, ctx, false), ctx), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                            if (cIdx > 0) marginStart = px(8f)
                        })
                    }
                    addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        if (rIdx > 0) topMargin = px(8f)
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
            // Tier 3: Arbitrary unrecognized custom cards -> stripped Micro-Web Island
            else -> WebIslandCardView(ctx, card, fillViewport = fillViewport)
        }
    }

    private fun wrapVisibility(card: CardConfig, view: View, ctx: VitroCardContext): View =
        wrapConditions(card.visibility + VisibilityCondition.parseList(card.raw.optJSONArray("conditions")), view, ctx)

    /**
     * Shows [view] only while every condition holds, re-checking live as the referenced entities change, once a
     * minute for `time` conditions, and until the signed-in user is known for `user` conditions.
     */
    private fun wrapConditions(conds: List<VisibilityCondition>, view: View, ctx: VitroCardContext): View {
        if (conds.isEmpty()) return view
        val widthDp = (resources.displayMetrics.widthPixels / dp).toInt()
        val update = {
            val userId = ctx.haUser().optString("id", "").ifEmpty { null }
            val visible = conds.all { it.evaluate(store::get, widthDp, userId) }
            view.visibility = if (visible) VISIBLE else GONE
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
            }
        })
        return view
    }

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

        /** HA's sections default width: a card is half-width at <= 6 of 12 columns; `clock` defaults to 6. */
        fun isHalfWidth(card: CardConfig): Boolean {
            val cols = card.gridOptionsColumns() ?: if (card.type == "clock") 6 else null
            return cols != null && cols <= 6
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
