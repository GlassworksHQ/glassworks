/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.content.Context
import android.graphics.Color
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
import dev.glassworks.engine.vitro.cards.BubbleCardView
import dev.glassworks.engine.vitro.cards.ButtonCardView
import dev.glassworks.engine.vitro.cards.ClockCardView
import dev.glassworks.engine.vitro.cards.EntitiesCardView
import dev.glassworks.engine.vitro.cards.GaugeCardView
import dev.glassworks.engine.vitro.cards.GlanceCardView
import dev.glassworks.engine.vitro.cards.HeadingCardView
import dev.glassworks.engine.vitro.cards.MarkdownCardView
import dev.glassworks.engine.vitro.cards.MediaControlCardView
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.cards.MushroomChipsCardView
import dev.glassworks.engine.vitro.cards.PictureCardView
import dev.glassworks.engine.vitro.cards.SensorGraphCardView
import dev.glassworks.engine.vitro.cards.ThermostatCardView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.cards.WeatherCardView
import dev.glassworks.engine.vitro.cards.WebIslandCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HistoryPoint
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.CardTier
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.model.SectionConfig
import dev.glassworks.engine.vitro.model.ViewConfig
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import dev.glassworks.engine.vitro.theme.ThemeBackground
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.ui.Inter
import org.json.JSONObject

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

    private var moreInfo: MoreInfoSheet? = null
    private val viewCache = LinkedHashMap<Int, View>()

    var dashboard: DashboardConfig? = null
        private set
    var currentViewIndex: Int = 0
        private set
    var themeCatalog: ThemeCatalog = ThemeCatalog()
        private set
    var darkMode: Boolean = true
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

    init {
        setBackgroundColor(backgroundColor)
        tabScroll.addView(tabBar, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        shell.addView(tabScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        shell.addView(contentHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        addView(shell, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(statusLabel, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun showStatus(message: String) {
        statusLabel.text = message
        statusLabel.visibility = VISIBLE
    }

    fun hideStatus() {
        statusLabel.visibility = GONE
    }

    fun setThemes(catalog: ThemeCatalog) {
        themeCatalog = catalog
        viewCache.clear()
        dashboard?.let { renderView(currentViewIndex, forceRebuild = true) }
    }

    fun setDashboard(config: DashboardConfig, initialViewPath: String?) {
        dashboard = config
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
        sheet.dismiss()
        removeView(sheet)
        moreInfo = null
        return true
    }

    fun openMoreInfo(entityId: String) {
        closeMoreInfo()
        val cfg = dashboard?.views?.getOrNull(currentViewIndex)
        val theme = ThemeResolver.resolve(themeCatalog, darkMode, cfg?.theme, cfg?.background)
        val cardCtx = contextFactory?.invoke(theme) ?: return
        val sheet = MoreInfoSheet(cardCtx) {
            moreInfo?.let { removeView(it) }
            moreInfo = null
        }
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
        val theme = ThemeResolver.resolve(themeCatalog, darkMode)
        tabScroll.setBackgroundColor(theme.secondaryBackgroundColor)
        config.views.forEachIndexed { idx, v ->
            val tab = TextView(context).apply {
                text = v.title
                textSize = 13f
                typeface = Inter.typeface(context, Inter.MEDIUM, 13f)
                setPadding(px(16f), px(12f), px(16f), px(12f))
                isClickable = true
                setOnClickListener { renderView(idx, forceRebuild = false) }
            }
            tabBar.addView(tab, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        updateTabVisibility()
    }

    private fun updateTabVisibility() {
        val count = dashboard?.views?.size ?: 0
        tabScroll.visibility = if (!hideHeader && count > 1) VISIBLE else GONE
    }

    private fun highlightTab(selected: Int, theme: ResolvedTheme) {
        for (i in 0 until tabBar.childCount) {
            val tv = tabBar.getChildAt(i) as? TextView ?: continue
            tv.setTextColor(if (i == selected) theme.primaryColor else theme.secondaryTextColor)
        }
    }

    private fun renderView(index: Int, forceRebuild: Boolean) {
        val cfg = dashboard ?: return
        val viewCfg = cfg.views.getOrNull(index) ?: return
        val t0 = SystemClock.elapsedRealtime()
        currentViewIndex = index
        hideStatus()

        val theme = ThemeResolver.resolve(themeCatalog, darkMode, viewCfg.theme, viewCfg.background)
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
                viewCache[index] = built
                contentHost.addView(built, LayoutParams(MATCH_PARENT, MATCH_PARENT))
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
        val gap = px(10f)
        val outerPad = px(12f)

        val container: View = if (view.type == "sections" && view.sections.isNotEmpty()) {
            buildSectionsLayout(view, ctx, widthDp, outerPad, gap)
        } else {
            buildMasonryLayout(view.cards, ctx, widthDp, outerPad, gap)
        }
        val body = if (view.subview) {
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val backRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(outerPad, outerPad, outerPad, px(4f))
                    isClickable = true
                    setOnClickListener { ctx.navigate(view.backPath ?: "/") }
                }
                backRow.addView(TextView(context).apply {
                    text = "←  ${view.title}"
                    textSize = 18f
                    setTextColor(ctx.theme.primaryTextColor)
                    typeface = Inter.typeface(context, Inter.MEDIUM, 18f)
                })
                addView(backRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(container, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        } else {
            container
        }
        scroll.addView(body, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return scroll
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
            col.addView(buildSection(sec, ctx, gap), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
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
        for (card in section.cards) {
            val cols = card.gridOptionsColumns()
            val isHalf = cols != null && cols <= 6
            val cardView = wrapVisibility(card, buildCard(card, ctx, fillViewport = false), ctx)
            if (isHalf) {
                val row = pendingHalfRow
                if (row == null) {
                    val newRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    newRow.addView(cardView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    box.addView(newRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = gap })
                    pendingHalfRow = newRow
                } else {
                    row.addView(cardView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = gap })
                    pendingHalfRow = null
                }
            } else {
                pendingHalfRow = null
                box.addView(cardView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = gap })
            }
        }
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
        "clock", "sensor", "gauge", "weather-forecast" -> 4
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

    private fun wrapVisibility(card: CardConfig, view: View, ctx: VitroCardContext): View {
        val conds = card.visibility + dev.glassworks.engine.vitro.model.VisibilityCondition.parseList(card.raw.optJSONArray("conditions"))
        if (conds.isEmpty()) return view
        val widthDp = (resources.displayMetrics.widthPixels / dp).toInt()
        val update = {
            val visible = conds.all { it.evaluate(store::get, widthDp) }
            view.visibility = if (visible) VISIBLE else GONE
        }
        update()
        val refs = LinkedHashSet<String>()
        conds.forEach { it.referencedEntities(refs) }
        val unsubs = refs.map { eid -> store.subscribe(eid) { ctx.main.post(update) } }
        view.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { unsubs.forEach { it() } }
        })
        return view
    }

    companion object {
        private const val TAG = "Glassworks.Vitro"
    }
}
