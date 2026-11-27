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
import dev.glassworks.engine.vitro.VitroFailures
import dev.glassworks.engine.vitro.cards.LiveCameraFeed
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
import dev.glassworks.engine.vitro.cards.MoreInfoRoute
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
import dev.glassworks.engine.vitro.cards.cssText
import dev.glassworks.engine.vitro.cards.lineBox
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HistoryPoint
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.FrontendLocale
import dev.glassworks.engine.vitro.data.HaLocale
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.EntityFilterConfig
import dev.glassworks.engine.vitro.model.HaCardSize
import dev.glassworks.engine.vitro.fonts.WebFonts
import dev.glassworks.engine.vitro.clarity.VitroClarity
import dev.glassworks.engine.vitro.compat.CompatChecker
import dev.glassworks.engine.vitro.compat.BubbleFallback
import dev.glassworks.engine.vitro.compat.IslandBudget
import dev.glassworks.engine.vitro.compat.IslandCardSizes
import dev.glassworks.engine.vitro.compat.IslandFrontend
import dev.glassworks.engine.vitro.compat.IslandScripts
import dev.glassworks.engine.vitro.compat.WebFallback
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.CardTier
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.model.LovelaceResourceList
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
    private class TabEntry(val viewIndex: Int, val label: TextView, val icon: MdiIconView?, val indicator: View, val tab: View)

    private val tabs = ArrayList<TabEntry>()
    private var moreInfo: MoreInfoSheet? = null

    /** The entity the open More Info sheet shows (to open it again in a new language). */
    private var moreInfoEntityId: String? = null
    private val viewCache = LinkedHashMap<Int, View>()

    /** Cached views that hold a dead island ([islandPagesGone]): built again, not shown from the cache, next time. */
    private val staleViews = HashSet<Int>()

    /** For each cached view, the scripts its islands could load when it was built ([IslandScripts]). */
    private val builtScripts = HashMap<Int, IslandScripts>()

    /** Views the user came from, newest last; [goBack] pops it. Capped at [MAX_BACK_STACK]. */
    private val viewBackStack = ArrayDeque<Int>()

    /**
     * HA's `notification-manager`: the toasts HA's frontend shows over the page (an update that failed after its dialog
     * closed, a tap with nothing to do, the connection lost), over the view and any sheet.
     */
    private val toasts = HaToastHost(context) { ThemeResolver.resolve(themeCatalog, darkMode, userThemeName) }

    /** HA's `disconnect-toast-mixin`: the connection-lost toast waits a second, so a quick reconnect shows nothing. */
    private val connectionLostToast = Runnable {
        toasts.show(HaToast(connectionLostMessage ?: HaText.ui("ui.notification_toast.connection_lost"), durationMs = -1L, id = CONNECTION_LOST_TOAST))
    }
    private var connectionLostMessage: String? = null

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
            val firstAnswer = !resourcesKnown
            resourcesKnown = true
            val held = resourceHold
            resourceHold = null
            if (held != null) mainHandler.removeCallbacks(held.timeout)
            // A list equals another with the same resources; whether Bubble's modules apply changes the routes too.
            if (field == v && (field as? LovelaceResourceList)?.bubbleModules == (v as? LovelaceResourceList)?.bubbleModules) {
                // The first answer is what a held first build waited for, even when it's the empty list it assumed.
                if (firstAnswer && held != null && dashboard === held.config) renderView(held.index, forceRebuild = false, pushHistory = false)
                return
            }
            field = v
            // The islands load the resources into their page: new resources need new pages, pooled ones too.
            dropBuiltViews(keepIslands = false)
            closePooledIslands()
            // The view the page is on: the one a first build held for this answer, or the one that went to HA's
            // frontend (never the index before it, which the page never showed: tablet Vitro run 3 built it behind HA).
            val index = held?.takeIf { dashboard === it.config }?.index ?: webViewIndex ?: currentViewIndex
            dashboard?.let { renderView(index, forceRebuild = true, pushHistory = held == null) }
        }

    /**
     * Whether Home Assistant has answered for this server's Lovelace resources ([resourcesList] set since
     * [resetForServer]). Until then nothing that routes on them is decided: which cards go to an island, and whether a
     * view goes to HA's frontend ([holdFirstBuildForResourcesMs]).
     */
    @Volatile var resourcesKnown: Boolean = false
        private set

    /**
     * How long a first build waits for the resources' answer ([resourcesKnown]; 0: it doesn't). The engine sets it.
     * They're asked before the dashboard's config, but a dashboard that loads Bubble Card hears them only after the
     * modules check ([dev.glassworks.engine.vitro.data.BubbleModules]: a command, a subscription and a file), and the
     * config lands first. Without the wait, a view of Bubble cards that only an island draws went to HA's frontend
     * ("no Lovelace resource loads Bubble Card's script") and came back once they landed (tablet Vitro run 3). Past
     * the wait it builds with what it has, and the answer rebuilds it.
     */
    var holdFirstBuildForResourcesMs: Long = 0

    /** A first build waiting for the resources ([holdFirstBuildForResourcesMs]): its view, its config, its timer. */
    private class ResourceHold(val index: Int, val config: DashboardConfig, val timeout: Runnable)

    private var resourceHold: ResourceHold? = null

    /** The view the page last handed to HA's frontend ([onWebView]), until a view is built again. */
    private var webViewIndex: Int? = null

    /**
     * True when view [index]'s first build waits for the resources' answer: none yet for this server, and the view
     * isn't built. Past [holdFirstBuildForResourcesMs] it builds without them.
     */
    private fun holdForResources(index: Int, cfg: DashboardConfig): Boolean {
        if (resourcesKnown || holdFirstBuildForResourcesMs <= 0 || viewCache[index] != null) return false
        resourceHold?.let { h ->
            if (h.index == index && h.config === cfg) return true
            mainHandler.removeCallbacks(h.timeout)
        }
        lateinit var hold: ResourceHold
        hold = ResourceHold(index, cfg, Runnable {
            if (resourceHold !== hold) return@Runnable
            resourceHold = null
            // No answer in time (a Home Assistant that never sends one): built as if it had none, from now on.
            resourcesKnown = true
            Log.w(TAG, "No Lovelace resources after $holdFirstBuildForResourcesMs ms: building without them")
            if (dashboard === cfg) renderView(index, forceRebuild = false, pushHistory = false)
        })
        resourceHold = hold
        mainHandler.postDelayed(hold.timeout, holdFirstBuildForResourcesMs)
        return true
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

    /**
     * A card of a masonry view still to be added ([SlicedBuild]): [make] builds it; [place] puts it in its column in
     * place of the empty slot that held its place there, taken from the build's [CardStage] ([staged]) or straight.
     * A card is never out of its view's tree: built, it waits in the stage, inside the view, so whatever it opens (an
     * island's page, its subscriptions, a camera) goes with the view when the view goes.
     */
    private class CardStep(val card: CardConfig, val make: () -> View, val place: (card: View, staged: Boolean) -> Unit)

    /**
     * The cards below a masonry view's first screen, in config order, collected while [buildViewTree] runs:
     * [renderView] hands them to a [SlicedBuild]. Null outside a build.
     */
    private var pendingSteps: MutableList<CardStep>? = null

    /** Where the masonry view [buildViewTree] builds keeps its later cards until they're placed ([CardStage]). */
    private var pendingStage: CardStage? = null

    /**
     * A masonry view on screen whose cards below its first screen are still being added. Home Assistant's masonry
     * view (`hui-masonry-view.ts` `_createColumns`) puts its columns up first and adds every card in 16 ms slices with
     * a frame between, which on a panel draws the first screen over many frames and stretches the whole build.
     * Vitro builds the first screen in one pass instead ([buildMasonryLayout]: the cards whose column, decided from
     * the config, puts them within it by HA's card sizes), so the first frame shows it whole. Once that screen has
     * been drawn twice (it settles first: [postSlice]), it makes the rest in chunks of at most [REST_SLICE_BUDGET_MS],
     * one a frame, each after its frame has been drawn; a card that takes long to make starts a chunk ([isHeavyCard]).
     * A chunk's
     * cards wait in the view's [CardStage], laid out by nothing, and all of them go to their columns in one pass when
     * the last chunk is made ([placeStaged]): one layout of the dashboard for the whole rest, not one per chunk (on the
     * S1, a frame after each chunk laid the dashboard out again, and doubled the time to a quiet main thread). Once the
     * view is scrolled, what's made so far is placed at once and each chunk places its own. The finished view is the
     * one a build in one go makes, as every card's column is decided before any is built ([HaMasonry.place]). What waits for the whole view (the islands no card took
     * back, the readiness and metrics, the rebuild for guessed states) waits for its last chunk ([finishBuild]).
     */
    private inner class SlicedBuild(
        val index: Int,
        val viewCfg: ViewConfig,
        val startedAt: Long,
        steps: List<CardStep>,
        /** The view's card layers ([cacheCardAsLayer]): a card a later chunk adds counts against the same budget. */
        val layers: CardLayerBudget,
        /** Where the chunks' cards wait to be placed. */
        val stage: CardStage?,
    ) {
        val steps = ArrayDeque(steps)

        /** The cards made and waiting in [stage], in config order. */
        val staged = ArrayList<Pair<CardStep, View>>()

        /** The view was scrolled: what's made goes to its column at once. */
        var placeAtOnce = false
        var slices = 0
        var longestMs = 0L
        val run = Runnable { runSlice(this) }
        private var scheduled: (() -> Unit)? = null

        /** The next chunk; the first waits for the first screen to be drawn twice ([postSlice]). */
        fun schedule() {
            scheduled = postSlice(run, slices <= 1)
        }

        fun unschedule() {
            scheduled?.invoke()
            scheduled = null
        }
    }

    /**
     * Runs [task] once the next frame has been drawn, or, [afterFrames], once the first screen has been drawn and drawn
     * again (the first chunk: what the first screen does on its next pass, a graph sized now, an image, comes before
     * any card below it), and returns what cancels it. One chunk a frame: a frame callback runs before its frame's
     * layout and draw, and the ordinary message it sends ([chunkMessage]) runs after them, so every chunk leaves the
     * next frame its turn (an asynchronous message would pass the frame's barrier and starve it: on the S1, only 39 of
     * 106 chunks back to back had a frame between them). A chunk's frame is cheap: its cards wait in the stage, laid
     * out by nothing ([CardStage]). Tests give their own.
     */
    internal var postSlice: (Runnable, Boolean) -> (() -> Unit) = { task, afterFrames ->
        fun send() = mainHandler.sendMessage(chunkMessage(task))
        val choreographer = android.view.Choreographer.getInstance()
        val second = android.view.Choreographer.FrameCallback { send() }
        val first = android.view.Choreographer.FrameCallback { choreographer.postFrameCallback(second) }
        choreographer.postFrameCallback(if (afterFrames) first else second)
        val cancel = {
            choreographer.removeFrameCallback(first)
            choreographer.removeFrameCallback(second)
            mainHandler.removeCallbacks(task)
        }
        cancel
    }

    /** The message a chunk runs in: an ordinary one, which a pending frame's barrier holds back ([postSlice]). */
    internal fun chunkMessage(task: Runnable): android.os.Message = android.os.Message.obtain(mainHandler, task)

    /** The view build still adding cards ([SlicedBuild]), or null. */
    private var building: SlicedBuild? = null

    /** How many chunks this view has run ([runSlice]). Tests. */
    internal var chunksRun = 0
        private set

    /**
     * The clock a chunk's budget is read on ([SLICE_BUDGET_MS], [REST_SLICE_BUDGET_MS]). Tests set one that moves: Robolectric's stands still
     * while the main thread works, so every view builds there in one slice, as before.
     */
    internal var sliceClock: () -> Long = { SystemClock.uptimeMillis() }

    /** The view on screen is still adding its cards ([SlicedBuild]). Tests. */
    internal val buildInProgress: Boolean get() = building != null

    /**
     * Stops the build in progress: no card is made or added after this. [dropPartial]: its half-built view goes too,
     * its islands closed (another view is shown, so none is handed over); else the caller drops or rebuilds it.
     */
    private fun cancelBuild(dropPartial: Boolean) {
        val b = building ?: return
        building = null
        b.unschedule()
        b.steps.clear()
        // The staged cards are in the view's tree (its stage): they go with it.
        b.staged.clear()
        (viewCache[b.index] as? ScrollView)?.setOnScrollChangeListener(null)
        if (dropPartial) dropView(b.index, keepIslands = false)
    }

    /**
     * One chunk of [b]: cards made in config order until [budgetMs] has passed since [start] (at least one), off the
     * tree, then attached together, so the next frame lays out only them. The last chunk finishes the view. The first
     * chunk runs in the build's own pass ([renderView]), with HA's [SLICE_BUDGET_MS] counted from the build's start:
     * a first screen that took longer than that is drawn before any card below it is made.
     */
    private fun runSlice(b: SlicedBuild, start: Long = sliceClock(), budgetMs: Long = REST_SLICE_BUDGET_MS) {
        if (building !== b) return
        chunksRun++
        val began = sliceClock()
        b.slices++
        val made = ArrayList<Pair<CardStep, View>>()
        buildingIndex = b.index
        buildingLayers = b.layers
        android.os.Trace.beginSection("Vitro chunk")
        try {
            // A card whose build ends this one (a re-entrant render or drop) leaves the rest unmade.
            while (building === b && b.steps.isNotEmpty()) {
                if ((made.isNotEmpty() || start != began) && sliceClock() - start > budgetMs) break
                // A card that takes long to make starts a chunk of its own budget: after other cards, it'd run it over.
                if (made.isNotEmpty() && isHeavyCard(b.steps.first().card)) break
                val step = b.steps.removeFirst()
                made += step to step.make()
            }
            android.os.Trace.beginSection("Vitro chunk: ${made.size} cards")
            android.os.Trace.endSection()
        } finally {
            android.os.Trace.endSection()
            buildingIndex = -1
            buildingLayers = null
        }
        if (building !== b) return
        val stage = b.stage
        if (stage == null || b.placeAtOnce) {
            for ((step, card) in made) step.place(card, false)
        } else {
            // Into the stage: in the view's tree, attached, but GONE, so nothing lays them out yet.
            for ((step, card) in made) stage.addView(card)
            b.staged += made
        }
        if (b.steps.isEmpty()) placeStaged(b)
        val added = made.map { it.second }
        b.longestMs = maxOf(b.longestMs, sliceClock() - began)
        // Islands of the cards just added run only while their view is on screen, as [updateIslandsShown] sets them.
        val root = viewCache[b.index]
        if (root != null) {
            val shown = dashboardShown && activityShown && b.index == currentViewIndex && root.visibility == VISIBLE
            for (card in added) islandsIn(card).forEach { it.setShown(shown && visibleWithin(it as View, root)) }
        }
        if (b.steps.isEmpty()) {
            building = null
            b.stage?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
            (viewCache[b.index] as? ScrollView)?.setOnScrollChangeListener(null)
            finishBuild(b.index, b.viewCfg, b.startedAt, cached = false, slices = b.slices, longestSliceMs = b.longestMs)
        } else {
            b.schedule()
        }
    }

    /**
     * A card that takes long to make, so a chunk starts with it ([runSlice]): a graph, a map, a logbook, a calendar or a
     * forecast, an entities card of many rows (a 20-row one took 54-91 ms on the S1), or a stack or grid holding one.
     */
    private fun isHeavyCard(card: CardConfig): Boolean = when {
        card.type in HEAVY_CARD_TYPES -> true
        card.type == "entities" -> (card.raw.optJSONArray("entities")?.length() ?: 0) >= HEAVY_ENTITY_ROWS
        else -> card.childCards.any(::isHeavyCard)
    }

    /** Where the shortest column's placed cards end, in [scroll]'s content (what a scroll down reaches first). */
    private fun placedBottom(scroll: ScrollView, b: SlicedBuild): Int {
        val row = b.stage?.parent as? android.view.ViewGroup ?: return 0
        val r = Rect()
        var bottom = Int.MAX_VALUE
        for (i in 0 until row.childCount) {
            val column = row.getChildAt(i) as? CardColumn ?: continue
            r.set(0, 0, column.width, column.height)
            scroll.offsetDescendantRectToMyCoords(column, r)
            bottom = minOf(bottom, r.bottom)
        }
        return if (bottom == Int.MAX_VALUE) 0 else bottom
    }

    /**
     * The cards [b] has made and staged go to their columns, in config order, in one pass: they stay attached as they
     * move ([CardStage.take], [CardColumn.place]), and the dashboard is laid out once for all of them.
     */
    private fun placeStaged(b: SlicedBuild) {
        val stage = b.stage ?: return
        if (b.staged.isEmpty()) return
        android.os.Trace.beginSection("Vitro place: ${b.staged.size} cards")
        try {
            for ((step, card) in b.staged) {
                stage.take(card)
                step.place(card, true)
            }
            b.staged.clear()
            (stage.parent as? View)?.requestLayout()
        } finally {
            android.os.Trace.endSection()
        }
        // Their islands were paused in the stage: they run as their view does.
        updateIslandsShown()
    }

    /** The card layers of the view being built ([cacheCardAsLayer]); null outside a build. */
    private var buildingLayers: CardLayerBudget? = null
    var onViewSelected: ((ViewConfig) -> Unit)? = null

    /**
     * Island cards' page losses ([islandPagesGone]), by card (its config's text): when each happened, the last
     * [MAX_ISLAND_LOSSES] kept. Main thread.
     */
    private val islandLosses = HashMap<String, ArrayDeque<Long>>()

    /**
     * Island cards given up for (their config's text) and why: each page died [MAX_ISLAND_LOSSES] times within
     * [ISLAND_LOSS_WINDOW_MS] (a card that crashes its content process, or a view whose islands the system keeps
     * killing). Their views go to HA's frontend, one page for all of it, until another Home Assistant
     * ([resetForServer]). A card edited since is another card, and gets its own chances.
     */
    private val gaveUpIslands = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The views a given-up island card was in ([viewKey]) and why: they go to HA's frontend whatever the card's config
     * held when it ran (an auto-entities child carries the entity it matched, which the view's own config doesn't).
     */
    private val gaveUpViews = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** A view of this dashboard, as [gaveUpViews] keys it: its place and its config. */
    private fun viewKey(view: ViewConfig): String = "${view.index}:${view.raw}"

    /**
     * Shows a view Vitro doesn't draw ([fallbackReason]: a strategy, a built-in card or row it has no renderer for, a
     * custom card an island found drawing with HA's own elements) in Home Assistant's own frontend, with the reason.
     * Such a view is never built: no empty page and no island for a card the island can't draw. Null builds every view
     * as it is (a view with no page).
     */
    var onWebView: ((ViewConfig, String) -> Unit)? = null

    /**
     * Why [view] goes to HA's frontend now: its own reason ([ViewConfig.webFallbackReason]), a card in it that an
     * island found drawing with Home Assistant's own elements this session ([IslandFrontend]), a row auto-entities
     * matches now that Vitro doesn't draw natively ([WebFallback.matchedRowsReason]), an `iframe` card of a page on
     * this Home Assistant at its full address ([haOrigin]), or more island pages than this device runs in one view
     * ([islandBudget]). Null: Vitro draws it.
     */
    fun fallbackReason(view: ViewConfig): String? =
        view.webFallbackReason ?: (if (gaveUpViews.isEmpty()) null else gaveUpViews[viewKey(view)])
            ?: (if (IslandFrontend.isEmpty && gaveUpIslands.isEmpty()) null else WebFallback.viewReason(view) { learnedReason(it, resourcesList) })
            ?: WebFallback.matchedRowsReason(view, IslandScripts.of(resourcesList), store.all().values, store::get)
            ?: WebFallback.haIframeReason(view, haOrigin())
            ?: scriptVerdict(view).let { v -> IslandBudget.reason(v.islands, islandBudget) ?: v.bubble }

    /** What [view]'s config says with the dashboard's island scripts: its island pages, and its Bubble reason. */
    private class ScriptVerdict(val scripts: IslandScripts, val islands: Int, val bubble: String?)

    /**
     * [ScriptVerdict]s by view config (its identity; a new dashboard's configs are new objects, and [setDashboard] lets
     * the old ones go). Both answers follow from the config and the scripts alone, so a view's are worked out once per
     * script list, not on every open ([fallbackReason] runs on each). Main thread.
     */
    private val scriptVerdicts = java.util.IdentityHashMap<ViewConfig, ScriptVerdict>()

    private fun scriptVerdict(view: ViewConfig): ScriptVerdict {
        val scripts = IslandScripts.of(resourcesList)
        scriptVerdicts[view]?.let { if (it.scripts == scripts) return it }
        return ScriptVerdict(scripts, IslandBudget.islandCount(view, scripts), BubbleFallback.reason(view, scripts))
            .also { scriptVerdicts[view] = it }
    }

    /**
     * The origin of the Home Assistant this dashboard is from, or null while none: an `iframe` card of a page on it goes
     * to HA's frontend ([WebFallback.haIframeReason]). The engine sets it.
     */
    var haOrigin: () -> String? = { null }

    /**
     * The most island pages one view opens on this device ([IslandBudget]): a view with more goes to HA's frontend.
     * Tests set it.
     */
    internal var islandBudget: Int = IslandBudget.forDevice(context)

    /**
     * What this session learned about [card] with [resources]: its island page kept dying ([islandPagesGone]), or,
     * for a card that goes to an island, it needs HA's frontend ([IslandFrontend]).
     */
    private fun learnedReason(card: CardConfig, resources: List<LovelaceResource>): String? {
        if (gaveUpIslands.isNotEmpty()) gaveUpIslands[card.raw.toString()]?.let { return it }
        if (CardRouter.tier(card, IslandScripts.of(resources)) != CardTier.WEB_ISLAND) return null
        return IslandFrontend.reason(resources, card.type)
    }

    /**
     * An island found its [card] drawing with elements of HA's frontend it lacks ([tags]), in the custom cards [owners]
     * (the island's own card, or one inside it). Remembered for the session ([IslandFrontend]), so every view with such
     * a card goes to HA's frontend from now on, without drawing it again: the view on screen now, through [onWebView]
     * as any view Vitro doesn't draw, and another when it's opened. Their built trees go, islands and all: they never
     * show again. Without a page ([onWebView] null) it's only remembered, and the view stays as it is. [resources]: the
     * scripts the island was built with (a report that lands after the dashboard changed is about those).
     */
    fun cardNeedsFrontend(card: CardConfig, tags: List<String>, owners: List<String>, resources: List<LovelaceResource> = resourcesList) {
        for (type in owners.ifEmpty { listOf(card.type) }) {
            if (IslandFrontend.record(resources, type, tags)) {
                Log.i(TAG, "Island ${card.type}: $type draws with ${tags.joinToString()}, which only HA's frontend has")
            }
        }
        // An inner card that doesn't go to an island where it stands alone (a native type) would blame no card: the
        // island's own card, then, so its view still leaves.
        if (WebFallback.cardReason(card) { learnedReason(it, resources) } == null) IslandFrontend.record(resources, card.type, tags)
        leaveForFrontend()
    }

    /**
     * An island's [card] threw on something its page doesn't have and drew nothing ([reason] says what): remembered for
     * the session as [cardNeedsFrontend] does, so every view with such a card goes to HA's frontend from now on.
     */
    fun cardFailedInIsland(card: CardConfig, reason: String, resources: List<LovelaceResource> = resourcesList) {
        if (IslandFrontend.recordReason(resources, card.type, reason)) Log.w(TAG, "Island ${card.type}: handed to HA's frontend: $reason")
        leaveForFrontend()
    }

    /**
     * A native auto-entities [card] matched a row Vitro doesn't draw natively ([reason]): the views whose matches now
     * need HA's frontend ([WebFallback.matchedRowsReason]) leave, the one on screen included. Posted, since the card
     * reports from its own build. Nothing is remembered: the view is decided again from the states each time it's
     * shown, so one whose matches are all native again comes back to Vitro.
     */
    fun matchedNeedFrontend(card: CardConfig, reason: String) {
        Log.i(TAG, "${card.type}: $reason")
        mainHandler.post { leaveForFrontend() }
    }

    /**
     * An island's [card] answered `getCardSize()` with [size] ([IslandCardSizes]). A masonry view places its cards by
     * those sizes, as HA's does: the view on screen is laid out again (its islands kept) when the answer moves a card
     * to another column or changes how many columns stay, and other built masonry views with the card are when next
     * shown. A view that places its cards the same stays as it is.
     */
    fun islandCardSize(card: CardConfig, size: Double, generation: Int = serverGeneration) {
        // A size an island of another Home Assistant reported, queued before [resetForServer]: not this one's card.
        if (generation != serverGeneration) return
        val cfg = dashboard
        val width = (resources.displayMetrics.widthPixels / dp).toInt()
        fun holds(v: ViewConfig) = v.cards.any { root -> holdsCard(root, card.raw.toString()) }
        val masonry = cfg?.views?.withIndex()?.filter { (i, v) -> viewCache[i] != null && isMasonry(v) && holds(v) }.orEmpty()
        val before = masonry.associate { (i, v) -> i to HaMasonry.place(v.cards, width, ::stateForBuild).let { (p, n) -> p.toList() to n } }
        if (!IslandCardSizes.record(card.raw, size)) return
        for ((i, v) in masonry) {
            val after = HaMasonry.place(v.cards, width, ::stateForBuild).let { (p, n) -> p.toList() to n }
            if (after == before[i]) continue
            Log.i(TAG, "View '${v.path ?: i}': ${card.type} weighs $size (its getCardSize): its masonry columns are placed again")
            if (i == currentViewIndex && webViewIndex == null) renderView(i, forceRebuild = true, pushHistory = false) else staleViews += i
        }
    }

    /**
     * Which Home Assistant the islands' reports are about: [resetForServer] moves it on, and a report made under an
     * older one ([islandCardSize]) is dropped (Sourcery on #952). The engine reads it as it builds each card's context.
     */
    @Volatile var serverGeneration: Int = 0
        private set

    /** A view [buildViewTree] lays out as masonry (its last case): not a panel, sections, sidebar or layout-card view. */
    private fun isMasonry(v: ViewConfig): Boolean =
        !(v.type == "panel" && v.cards.isNotEmpty()) && !(v.type == "sections" && v.sections.isNotEmpty()) &&
            v.type != "sidebar" && v.layoutCardType == null

    /** True when [card], or a card inside it, has the config [raw]. */
    private fun holdsCard(card: CardConfig, raw: String): Boolean =
        card.raw.toString() == raw || CardRouter.producedCards(card, perEntity = false).any { holdsCard(it.value, raw) }

    /**
     * The views that go to HA's frontend now ([fallbackReason]) leave: built ones go, islands and all (they never show
     * again), and the view on screen goes through [onWebView]. Without a page ([onWebView] null) nothing changes.
     */
    private fun leaveForFrontend() {
        val cfg = dashboard ?: return
        if (onWebView == null) return
        for (index in viewCache.keys.toList()) {
            val view = cfg.views.getOrNull(index) ?: continue
            if (fallbackReason(view) != null) dropView(index, keepIslands = false)
        }
        cfg.views.getOrNull(currentViewIndex)?.let { view ->
            if (fallbackReason(view) != null) renderView(currentViewIndex, forceRebuild = false, pushHistory = false)
        }
    }

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
        addView(toasts, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /**
     * HA's toasts sit above its dialogs: a layer added over the view after the toasts (a More Info sheet opened while
     * one shows) goes under them, so a toast on screen stays in sight until it closes.
     */
    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        if (child !== toasts && toasts.parent === this && indexOfChild(toasts) != childCount - 1) toasts.bringToFront()
    }

    /**
     * The connection to HA dropped ([message]: HA's "Connection lost. Reconnecting…") or came back (null): HA's toast
     * for it, shown a second after the drop until the connection is back, as `disconnect-toast-mixin` does.
     */
    fun setConnectionBanner(message: String?) {
        removeCallbacks(connectionLostToast)
        if (message.isNullOrBlank()) {
            connectionLostMessage = null
            toasts.show(HaToast("", durationMs = 0L, id = CONNECTION_LOST_TOAST))
            return
        }
        connectionLostMessage = message
        postDelayed(connectionLostToast, CONNECTION_LOST_DELAY_MS)
    }

    /** Shows [toast] as HA's frontend shows a toast (`showToast`), over the view and any sheet. Main thread. */
    fun showToast(toast: HaToast) = toasts.show(toast)

    /** The toasts showing (tests). */
    internal val toastMessages: List<String> get() = toasts.messages

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
        scriptVerdicts.clear()
        closeMoreInfo()
        dashboard = null
        viewBackStack.clear()
        // Another Home Assistant: no island of this one's carries over, nor what its islands' losses decided.
        dropBuiltViews(keepIslands = false)
        closePooledIslands()
        islandLosses.clear()
        gaveUpIslands.clear()
        gaveUpViews.clear()
        serverGeneration++
        IslandCardSizes.clear()
        resourceHold?.let { mainHandler.removeCallbacks(it.timeout) }
        resourceHold = null
        webViewIndex = null
        resourcesList = emptyList()
        // Another server's resources are its own answer's: none is known until it comes.
        resourcesKnown = false
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

    /** The language, formats and time zone the built views were drawn with. */
    private var drawnLocale: Triple<FrontendLocale, String, Long>? = null

    /** How many times [setLocale] built the dashboard again (tests: a reconnect with nothing changed builds nothing). */
    internal var localeRebuilds = 0
        private set

    /**
     * The user's language or formats changed ([HaLocale]): every text, number, date and time was written with the
     * old ones, so the view is built again, as a theme change does. Each island's page loads again too: its `hass.locale`
     * and `<html lang>` are written into the page when it loads, and its bridge carries no locale, so a kept page would
     * stay in the old language. A language change is rare; a reconnect never gets here, as strings installed again
     * unchanged keep [HaText.version] as it was ([HaFrontend]).
     */
    fun setLocale(locale: FrontendLocale, timeZone: String, strings: Long) {
        val next = Triple(locale, timeZone, strings)
        if (next == drawnLocale) return
        drawnLocale = next
        if (dashboard == null) return
        localeRebuilds++
        // An open More Info sheet built its words once: it opens again in the new language.
        val reopen = moreInfoEntityId?.takeIf { moreInfo != null }
        // HA's page re-renders in place: the view stays where it was scrolled.
        val scrolledTo = currentScroll()?.scrollY ?: 0
        closeMoreInfo()
        dropBuiltViews(keepIslands = false)
        dashboard?.let { renderView(currentViewIndex, forceRebuild = true) }
        if (scrolledTo > 0) currentScroll()?.let { scrollOnLayout(it, scrolledTo) }
        reopen?.let { openMoreInfo(it) }
    }

    /**
     * Scrolls [scroll] to [y] as it's laid out: before that it has no content to scroll, and an island reports its
     * height later, so each layout scrolls on (clamped to the content there is) until [y] is reached. It stops then,
     * when anything else moved the scroll since (the user), or after [RESTORE_SCROLL_MS].
     */
    private fun scrollOnLayout(scroll: ScrollView, y: Int) {
        val listener = object : OnLayoutChangeListener {
            var placed = -1

            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (placed >= 0 && v.scrollY != placed) {
                    v.removeOnLayoutChangeListener(this)
                    return
                }
                v.scrollTo(0, y)
                placed = v.scrollY
                if (placed >= y) v.removeOnLayoutChangeListener(this)
            }
        }
        scroll.addOnLayoutChangeListener(listener)
        scroll.postDelayed({ scroll.removeOnLayoutChangeListener(listener) }, RESTORE_SCROLL_MS)
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
        moreInfoRequest++
        drawnLocale = Triple(HaLocale.current, HaLocale.timeZone().id, HaText.version)
        holding?.cancel?.invoke()
        holding = null
        waited.clear()
        stateRebuilds.values.forEach { it() }
        stateRebuilds.clear()
        scriptVerdicts.clear()
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

    /**
     * The connection to Home Assistant is up again: an open More Info sheet renews what it streams, and live cameras
     * (the cards' and the sheet's) try again what failed while it was down.
     */
    fun connectionRestored() {
        moreInfo?.takeIf { !it.isDismissed }?.reconnected()
        LiveCameraFeed.connectionRestored(store)
    }

    /**
     * Counts More Info requests: a script's fields lookup ([openMoreInfo]) acts only if no newer request came, nor a
     * close or a new dashboard, meanwhile.
     */
    private var moreInfoRequest = 0

    fun closeMoreInfo(): Boolean {
        moreInfoRequest++
        val sheet = moreInfo ?: return false
        moreInfo = null
        sheet.dismiss()
        return true
    }

    /** The open More Info sheet, if any (the parity harness compares its surface, #885). */
    internal val openSheet: MoreInfoSheet? get() = moreInfo

    fun openMoreInfo(entityId: String) = openMoreInfo(entityId, null)

    /**
     * Opens More Info for an entity in Home Assistant's own frontend, over the view on screen (its URL's
     * `more-info-entity-id`), with the reason Vitro's sheet can't show it as HA does ([MoreInfoRoute]), or a script
     * with fields, whose form only HA's dialog has. Its last argument is the dialog's view (`more-info-view`:
     * `history` for an Activity entry on the History tab), or null for its default. Null (a view with no page): Vitro's
     * sheet, for every entity, whose Run then never starts such a script, and whose Activity entries open nothing.
     */
    var onMoreInfoInFrontend: ((ViewConfig, String, String, String?) -> Unit)? = null

    /**
     * HA's own More Info dialog for [entityId], on its view [moreInfoView] (null: its default), in HA's frontend over the
     * view on screen ([onMoreInfoInFrontend]), for what only it has ([reason]: a logbook card entry's detail). Closes
     * an open sheet first. False when there's no page for it (no [onMoreInfoInFrontend], no view): nothing happens.
     */
    internal fun moreInfoInFrontend(entityId: String, moreInfoView: String?, reason: String): Boolean {
        val inFrontend = onMoreInfoInFrontend ?: return false
        val shown = dashboard?.views?.getOrNull(currentViewIndex) ?: return false
        closeMoreInfo()
        inFrontend(shown, entityId, reason, moreInfoView)
        return true
    }

    /** [trace]: what Clarity's harness follows of the sheet ([MoreInfoSheet.Trace]). */
    internal fun openMoreInfo(entityId: String, trace: MoreInfoSheet.Trace?) {
        val inFrontend = onMoreInfoInFrontend
        val view = dashboard?.views?.getOrNull(currentViewIndex)
        // Clarity times the sheet itself; everyone else gets HA's dialog where the sheet would draw a different one.
        if (inFrontend != null && trace == null && view != null) {
            MoreInfoRoute.handOff(entityId, store[entityId])?.let { reason ->
                closeMoreInfo()
                inFrontend(view, entityId, "More Info: $reason", null)
                return
            }
        }
        // A script with fields (its `get_services` entry): its form is in HA's dialog alone (#926's Run finds the same).
        if (inFrontend != null && entityId.substringBefore('.') == "script") {
            val ctx = contextFactory?.invoke(ThemeResolver.resolve(themeCatalog, darkMode, userThemeName)) ?: return
            val request = ++moreInfoRequest
            dev.glassworks.engine.vitro.cards.EntityRowControls.scriptHasFields(ctx, entityId) { fields ->
                // Another entity's More Info, a close or a new dashboard since: this answer is stale.
                if (request != moreInfoRequest) return@scriptHasFields
                val shown = dashboard?.views?.getOrNull(currentViewIndex)
                if (fields == true && shown != null) {
                    closeMoreInfo()
                    inFrontend(shown, entityId, "More Info of a script with fields", null)
                } else {
                    showMoreInfo(entityId, trace)
                }
            }
            return
        }
        showMoreInfo(entityId, trace)
    }

    private fun showMoreInfo(entityId: String, trace: MoreInfoSheet.Trace?) {
        closeMoreInfo()
        moreInfoEntityId = entityId
        // HA's dialogs live outside the view: a view's `theme` never reaches them, the user's theme does (#885).
        val theme = ThemeResolver.resolve(themeCatalog, darkMode, userThemeName)
        // Its fonts are the user theme's too, as a view's cards get their own theme's.
        val cardCtx = contextFactory?.invoke(theme)?.withFontFamily(VitroFonts.familyFor(theme)) ?: return
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
        // What only HA's dialog has (an Activity entry's detail): its dialog, on the same view, over this one.
        if (onMoreInfoInFrontend != null) sheet.inFrontend = { id, moreInfoView, reason ->
            val shown = dashboard?.views?.getOrNull(currentViewIndex)
            val inFrontend = onMoreInfoInFrontend
            if (shown != null && inFrontend != null && moreInfo === sheet) {
                closeMoreInfo()
                inFrontend(shown, id, reason, moreInfoView.takeIf { it != "info" })
            }
        }
        // Keys stay in the sheet, as in HA's modal dialog: nothing behind it takes focus while it's open.
        blockDashboardFocus(true)
        sheet.show(entityId)
        moreInfo = sheet
        addView(sheet, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun resolveViewIndex(config: DashboardConfig, path: String?): Int = config.viewIndex(path, currentUserId())

    /**
     * The signed-in Home Assistant user's id (`auth/current_user`), or null while it isn't known. A view's `visible`
     * reads it: which tabs show ([applyTabVisibility]) and which view a dashboard opens on ([resolveViewIndex]).
     */
    var currentUserId: () -> String? = { null }

    /** The signed-in user changed (it arrived, or another one signed in): the tabs follow their views' `visible`. */
    fun userChanged() = applyTabVisibility(currentViewIndex)

    /**
     * HA's `hui-root` tab rules, outside edit mode: a view hidden from the signed-in user ([ViewConfig.tabHiddenFor]:
     * `visible: false`, or a `visible` list of other users) has no tab, unless it's the view on screen (reached by its
     * path). Subviews have none at all ([rebuildTabs]).
     */
    private fun applyTabVisibility(selected: Int) {
        val views = dashboard?.views ?: return
        val userId = currentUserId()
        for (t in tabs) {
            val hidden = t.viewIndex != selected && views.getOrNull(t.viewIndex)?.tabHiddenFor(userId) == true
            t.tab.visibility = if (hidden) GONE else VISIBLE
        }
    }

    /**
     * HA's toolbar behind the view tabs (`hui-root`'s `.header`, frontend 20261028.5): `--app-header-background-color`,
     * with no fallback of its own. HA's theme sets it to `--sidebar-background-color`, which it sets to
     * `--card-background-color`: the theme variable, never the card-only `--ha-card-background` or a card-mod style, so
     * a theme with transparent cards keeps its toolbar (Greptile on #952). Past those, HA's own card background.
     */
    internal fun headerBackground(theme: dev.glassworks.engine.vitro.theme.ResolvedTheme): Int =
        listOf("app-header-background-color", "sidebar-background-color", "card-background-color")
            .firstNotNullOfOrNull { ThemeResolver.parseColor(theme.vars[it]) }
            ?: if (theme.dark) 0xFF1C1C1C.toInt() else 0xFFFFFFFF.toInt()

    private fun rebuildTabs(config: DashboardConfig) {
        tabBar.removeAllViews()
        tabs.clear()
        val theme = ThemeResolver.resolve(themeCatalog, darkMode)
        tabScroll.setBackgroundColor(headerBackground(theme))
        // Like HA, subviews have no tab: they are reached by navigation and come back with their back arrow. A view
        // hidden from this user gets one, shown only while it's on screen ([applyTabVisibility]).
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
            // HA's hui-root: an icon alone for a view with one, its title too only with `show_icon_and_title`.
            val content = v.tab { HaText.ui("ui.panel.lovelace.views.unnamed_view") }
            tab.contentDescription = content.label
            val icon = content.icon?.let { name ->
                MdiIconView(context).apply { setIcon(name, theme.secondaryTextColor) }.also {
                    val gap = if (content.text != null) px(6f) else 0
                    row.addView(it, LinearLayout.LayoutParams(px(18f), px(18f)).apply { marginEnd = gap })
                }
            }
            val label = TextView(context).apply {
                text = content.text
                visibility = if (content.text == null) GONE else VISIBLE
                textSize = 13f
                typeface = VitroFonts.typeface(context, Inter.MEDIUM, 13f)
                includeFontPadding = false
            }
            row.addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            tab.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            val indicator = View(context).apply { visibility = INVISIBLE }
            tab.addView(indicator, LinearLayout.LayoutParams(MATCH_PARENT, (2.5f * dp).toInt().coerceAtLeast(1)))
            tabBar.addView(tab, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            tabs += TabEntry(idx, label, icon, indicator, tab)
        }
        applyTabVisibility(currentViewIndex)
        updateTabVisibility()
    }

    private fun updateTabVisibility() {
        val count = dashboard?.views?.count { !it.subview } ?: 0
        tabScroll.visibility = if (!hideHeader && count > 1) VISIBLE else GONE
    }

    private fun highlightTab(selected: Int, theme: ResolvedTheme) {
        // The strip itself follows the theme too (a light/dark switch, a cached view's own theme).
        tabScroll.setBackgroundColor(headerBackground(theme))
        applyTabVisibility(selected)
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
     * How long a view's first build waits for its first states (0: it doesn't). The engine sets it: the dashboard's
     * entities are subscribed once per connection, the view on screen's first, so this waits only for the first view
     * after a connection (later views find their states in the store), and its states usually land within a round
     * trip. Past the wait it builds
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

    /**
     * True when the view on screen has been built: not waiting for its first states, nor still adding its cards
     * ([SlicedBuild]). The page counts it ready then.
     */
    val currentViewBuilt: Boolean get() = resourceHold == null && viewCache[currentViewIndex] != null && building?.index != currentViewIndex

    /**
     * True when the view on screen has a tree on screen, its cards all added or still being added ([SlicedBuild]):
     * Clarity's harness counts it past HA's launch screen, as HA's masonry view is once its columns are there.
     */
    internal val currentViewOnScreen: Boolean get() = resourceHold == null && viewCache[currentViewIndex] != null

    /**
     * The page is gone: a view waiting for its first states isn't built later (a late build would make cards, and
     * islands, nothing shows), and no guessed view is built again.
     */
    fun cancelPendingBuilds() {
        cancelBuild(dropPartial = false)
        holding?.cancel?.invoke()
        holding = null
        resourceHold?.let { mainHandler.removeCallbacks(it.timeout) }
        resourceHold = null
        stateRebuilds.values.forEach { it() }
        stateRebuilds.clear()
    }

    /** Views of this dashboard that have waited once: past the wait (states or not), they build. */
    private val waited = HashSet<Int>()

    /**
     * True when the first build of view [index] waits: one of its entities isn't known yet (no state, and HA hasn't
     * said it doesn't exist). Views share entities (a dashboard's header cards), so one known entity says little: a
     * view built with most of its states missing renders, and lays out, every card again as they land. It's built
     * once every entity is known (HA's first event for the subscription that asked for them), or after
     * [holdFirstBuildForStatesMs].
     */
    private fun holdForStates(index: Int, viewCfg: ViewConfig): Boolean {
        if (holdFirstBuildForStatesMs <= 0 || index in waited) return false
        holding?.let { if (it.index == index) return true }
        holding?.cancel?.invoke()
        holding = null
        val refs = CardRouter.referencedEntities(viewCfg)
        // A view that picks its entities at run time (auto-entities) names none of them: it waits for HA's answer for
        // every entity, as HA's frontend draws a view once hass has every state. Built before, it matches nothing.
        val all = CardRouter.followsAllEntities(viewCfg)
        fun known() = refs.all { store[it] != null || store.isMissing(it) } && (!all || store.answeredAll)
        if ((refs.isEmpty() && !all) || known()) return false
        val config = dashboard
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
        // One check queued at a time: a snapshot tells hundreds of entities at once, on the socket's thread.
        val checkQueued = java.util.concurrent.atomic.AtomicBoolean(false)
        val check = Runnable {
            checkQueued.set(false)
            if (known()) release()
        }
        fun queueCheck() {
            if (checkQueued.compareAndSet(false, true)) mainHandler.post(check)
        }
        // Each unknown entity's listener hears its state land, and HA's answer that it doesn't exist (markSnapshot).
        val unsubs = refs.filter { store[it] == null }.map { id -> store.subscribe(id) { queueCheck() } } +
            listOfNotNull(if (all) store.subscribeSnapshot { queueCheck() } else null)
        mine = Wait(index) {
            unsubs.forEach { it() }
            mainHandler.removeCallbacks(timeout)
            mainHandler.removeCallbacks(check)
        }
        holding = mine
        mainHandler.postDelayed(timeout, holdFirstBuildForStatesMs)
        // States that landed between the check above and the listeners (the store is written on another thread).
        queueCheck()
        return true
    }

    /** Per view index: the listener waiting for the states its build guessed at. */
    private val stateRebuilds = HashMap<Int, () -> Unit>()

    /**
     * HA re-renders when hass updates; a Mushroom card's class and grid rows follow its entity's features, which a
     * view's first build can come before (its states hadn't landed, or the wait ran out). When any of [ids] lands, the
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
        // A view still adding its cards stops unless it's the one shown again as it is: no card is added to a view
        // that's left. Another view's half-built tree goes; a rebuild of the same view takes its islands back below.
        building?.let { b ->
            if (b.index != index || forceRebuild || index in staleViews) cancelBuild(dropPartial = b.index != index)
        }
        // Whether the view goes to HA's frontend, and which of its cards go to islands, is decided once the resources
        // are known: the decision made then is the one that stands. The connecting screen stays meanwhile.
        if (holdForResources(index, cfg)) {
            // The view the page waits on is the current one: a rebuild meanwhile (the user's light or dark choice, a
            // theme, the header) renders it, not the index before, which would take the wait over (Greptile on #952).
            // Whether it goes to HA's frontend is still decided once the resources are known.
            currentViewIndex = index
            for (i in 0 until contentHost.childCount) contentHost.getChildAt(i).visibility = GONE
            for (i in 0 until overlayLayer.childCount) overlayLayer.getChildAt(i).visibility = GONE
            updateIslandsShown()
            updateCardsHidden()
            return
        }
        // Before anything is selected or built: the page leaves for HA's frontend, and this view stays as it was. No
        // build follows, so no island a rebuild gave up waits for one.
        onWebView?.let { show ->
            fallbackReason(viewCfg)?.let { reason ->
                cancelBuild(dropPartial = true)
                closePooledIslands()
                webViewIndex = index
                show(viewCfg, reason)
                return
            }
        }
        webViewIndex = null
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

        val cached = if (!forceRebuild && index !in staleViews) viewCache[index] else null
        // A first build waits for the view's states, as HA's frontend renders a view's cards once hass has them.
        // Only a first build waits: a stale view (a dead island, [islandPagesGone]) still has its tree, and builds now.
        if (cached == null && viewCache[index] == null && holdForStates(index, viewCfg)) {
            // The previous view's cards don't stay on screen (and tappable) under this view's tab while it waits.
            for (i in 0 until contentHost.childCount) contentHost.getChildAt(i).visibility = GONE
            for (i in 0 until overlayLayer.childCount) overlayLayer.getChildAt(i).visibility = GONE
            updateIslandsShown()
            updateCardsHidden()
            return
        }
        var steps: List<CardStep> = emptyList()
        var stepLayers: CardLayerBudget? = null
        var stepStage: CardStage? = null
        val clockAtStart = sliceClock()
        val viewRoot = if (cached != null) {
            cached
        } else {
            // The view's body font rides on the cards' context, not only on VitroFonts.family: a card that builds a
            // child later (auto-entities' refresh) while another view is shown still gets this view's font.
            val cardCtx = contextFactory?.invoke(theme)?.withFontFamily(VitroFonts.familyFor(theme)) ?: return closePooledIslands()
            cardCtx.viewType = viewCfg.type
            // The fonts the dashboard's stylesheets declare: read only once a card draws a family Vitro doesn't have.
            WebFonts.configure(cardCtx.haBase, cardCtx.resources, context.cacheDir)
            fontsFor = cardCtx.haBase to cardCtx.resources
            val pinned = ArrayList<Pair<View, FrameLayout.LayoutParams>>()
            pendingPinned = pinned
            val deferred = ArrayList<CardStep>()
            pendingSteps = deferred
            pendingStage = null
            guessedStates.clear()
            // The view's old tree, if it has one, goes first: its islands wait for this build to take them back.
            dropView(index, keepIslands = true)
            staleViews -= index
            buildingIndex = index
            val layers = CardLayerBudget(cardLayerBudgetBytes())
            buildingLayers = layers
            clarity?.buildStarted(index)
            android.os.Trace.beginSection("Vitro first pass")
            val built = try {
                buildViewTree(viewCfg, cardCtx)
            } finally {
                android.os.Trace.endSection()
                pendingPinned = null
                pendingSteps = null
                buildingIndex = -1
                buildingLayers = null
            }
            layers.roots += built
            steps = deferred
            stepLayers = layers
            stepStage = pendingStage
            pendingStage = null
            viewCache[index] = built
            builtScripts[index] = IslandScripts.of(cardCtx.resources)
            contentHost.addView(built, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            if (pinned.isNotEmpty()) {
                val frame = FrameLayout(context).apply {
                    clipChildren = false
                    clipToPadding = false
                }
                pinned.forEach { (v, lp) -> frame.addView(v, lp) }
                layers.roots += frame
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
        // Shown again while it's still adding its cards: its build goes on, and finishes the view ([finishBuild]).
        if (cached != null && building?.index == index) {
            updateIslandsShown()
            updateCardsHidden()
            return
        }
        if (steps.isEmpty()) {
            finishBuild(index, viewCfg, t0, cached = cached != null)
            return
        }
        // The first screen is on screen; the cards below it follow ([SlicedBuild]), while 16 ms of the build are left
        // now, the rest once that screen is drawn.
        updateIslandsShown()
        updateCardsHidden()
        val b = SlicedBuild(index, viewCfg, t0, steps, stepLayers ?: CardLayerBudget(cardLayerBudgetBytes()), stepStage)
        building = b
        // Scrolled down to where a column's placed cards end, while the rest is being made: what's made so far goes to
        // its place now, and so does each chunk's. A small scroll (a card taking focus) changes nothing.
        (viewRoot as? ScrollView)?.setOnScrollChangeListener { v, _, y, _, oldY ->
            if (y > oldY && building === b && !b.placeAtOnce && y + v.height >= placedBottom(v as ScrollView, b)) {
                b.placeAtOnce = true
                placeStaged(b)
            }
        }
        runSlice(b, start = clockAtStart, budgetMs = SLICE_BUDGET_MS)
    }

    /**
     * The view [index] is built (or shown from the cache, [cached]): the islands no card took back close, only its
     * islands run, and its metrics are reported, which is when the page counts it ready. A first build also listens
     * for the states it guessed at ([rebuildWhenStatesLand]). [slices]: how many slices a masonry build took
     * ([SlicedBuild]), and the longest of them.
     */
    private fun finishBuild(index: Int, viewCfg: ViewConfig, t0: Long, cached: Boolean, slices: Int = 1, longestSliceMs: Long = -1) {
        if (!cached) rebuildWhenStatesLand(index, guessedStates.toSet())
        // Islands the build didn't take back (their card is gone or changed) close; only this view's islands run.
        closePooledIslands()
        updateIslandsShown()
        updateCardsHidden()

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
            cacheHit = cached,
            islands = islands.map { "${it.path}: ${it.verdict.reasons.joinToString("; ")}" },
        )
        lastMetrics = metrics
        val sliced = if (slices > 1) ", slices=$slices, longest slice=$longestSliceMs ms" else ""
        Log.i(
            TAG,
            "View '${metrics.viewTitle}' (${metrics.viewType}) built in ${metrics.buildTimeMs} ms " +
                "(cache=${metrics.cacheHit}, core=${metrics.nativeCoreCards}, hacs=${metrics.nativeHacsCards}, " +
                "islands=${metrics.webIslandCards}, entities=${metrics.subscribedEntities}$sliced)",
        )
        // The checker's answer for this view, once per build: why each island falls back.
        if (!cached) metrics.islands.forEach { Log.i(TAG, "Island $it") }
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

    /** Whether [v] and every view above it up to [root] are VISIBLE. */
    private fun visibleWithin(v: View, root: View): Boolean {
        var cur: View? = v
        while (cur != null && cur !== root) {
            if (cur.visibility != VISIBLE) return false
            cur = cur.parent as? View
        }
        return true
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
        // A view still adding its cards adds none once it's dropped.
        if (building?.index == index) cancelBuild(dropPartial = false)
        staleViews -= index
        viewCache.remove(index)?.let { retireIslands(it, keepIslands); contentHost.removeView(it) }
        overlayFrames.remove(index)?.let { retireIslands(it, keepIslands); overlayLayer.removeView(it) }
        builtScripts.remove(index)
        clarity?.keepCards(viewCache.keys)
    }

    /** Drops every built view and the pinned cards. */
    private fun dropBuiltViews(keepIslands: Boolean) {
        cancelBuild(dropPartial = false)
        staleViews.clear()
        viewCache.values.forEach { retireIslands(it, keepIslands) }
        overlayFrames.values.forEach { retireIslands(it, keepIslands) }
        viewCache.clear()
        clarity?.keepCards(viewCache.keys)
        builtScripts.clear()
        contentHost.removeAllViews()
        clearOverlay()
    }

    /** The island an earlier build of [card] gave up, for its new build to take, or null. */
    private fun adoptIsland(key: String): WebIslandCardView.Island? {
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
            // A card its visibility conditions hide (GONE inside the view) pauses too.
            islandsIn(root).forEach { it.setShown(shown && visibleWithin(it as View, root)) }
        }
        for ((index, frame) in overlayFrames) {
            val shown = onScreen && index == currentViewIndex && frame.visibility == VISIBLE
            islandsIn(frame).forEach { it.setShown(shown) }
        }
    }

    /** Each cached tree's hidden state as its cards last heard it ([updateCardsHidden]); a new tree's are shown. */
    private val cardsHidden = java.util.WeakHashMap<View, Boolean>()

    /**
     * The cards of a cached view that isn't shown stop rendering live changes, as HA renders only the view on screen,
     * and catch up as it shows again, before it draws ([CardSurface.setViewHidden]). Its pinned cards follow it.
     * Only a tree whose state changed is walked.
     */
    private fun updateCardsHidden() {
        fun follow(root: View) {
            val hidden = root.visibility != VISIBLE
            if ((cardsHidden[root] ?: false) == hidden) return
            cardsHidden[root] = hidden
            forEachCard(root) { it.setViewHidden(hidden) }
        }
        viewCache.values.forEach(::follow)
        overlayFrames.values.forEach(::follow)
    }

    /**
     * The live cameras of a card just built ([card], with its visibility conditions) start now, while the rest of the
     * view builds, if the connection knows their capabilities ([PictureCardView.startEarly]). Only those the build
     * shows, while the dashboard is on screen: a hidden card opens no stream, as in HA.
     */
    private fun startCamerasEarly(card: View) {
        if (!dashboardShown || !activityShown || card.visibility != VISIBLE) return
        forEachCard(card) { c -> if (c is PictureCardView && visibleWithin(c, card)) c.startEarly() }
    }

    private fun forEachCard(v: View, action: (CardSurface) -> Unit) {
        if (v is CardSurface) action(v)
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) forEachCard(v.getChildAt(i), action)
    }

    /**
     * The content of island [pages] went away (a crashed or killed content process): they draw nothing again. A dead
     * page waiting in the pool closes, so no build takes it. A card that holds one closes it, and its view is built
     * again with its other islands handed over alive: the view on screen now, a hidden view when it's shown.
     */
    fun islandPagesGone(pages: Set<Page>) {
        if (pages.isEmpty()) return
        // Each card whose page died counts a loss: one that keeps dying is given up for, and goes to HA's frontend.
        var gaveUp = false
        val now = SystemClock.elapsedRealtime()
        // Each dead card once, with the views it was in.
        val lost = LinkedHashMap<String, Pair<CardConfig, MutableSet<Int>>>()
        for ((index, root) in viewCache) {
            val dead = islandsIn(root) + (overlayFrames[index]?.let { islandsIn(it) } ?: emptyList())
            for (card in dead.filter { it.holds(pages) }.map { it.islandConfig }) {
                lost.getOrPut(card.raw.toString()) { card to HashSet() }.second += index
            }
        }
        for ((card, views) in lost.values) {
            val reason = countIslandLoss(card, now) ?: continue
            gaveUp = true
            for (index in views) dashboard?.views?.getOrNull(index)?.let { gaveUpViews[viewKey(it)] = reason }
        }
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
        // A card given up for sends its views to HA's frontend, the one on screen included: nothing reopens for it.
        if (gaveUp && onWebView != null) {
            val current = dashboard?.views?.getOrNull(currentViewIndex)
            val currentLeaves = current != null && fallbackReason(current) != null
            leaveForFrontend()
            hit.retainAll(viewCache.keys)
            if (currentLeaves) hit -= currentViewIndex
        }
        for (index in hit) {
            if (index == currentViewIndex) renderView(index, forceRebuild = true, pushHistory = false)
            // A hidden view keeps its tree, live islands and all, and is built again when it's shown: they're handed
            // over then, and only the dead card opens a new page.
            else staleViews += index
        }
    }

    /** Counts a loss of [card]'s page at [now]; the reason when that gives the card up ([gaveUpIslands]), else null. */
    private fun countIslandLoss(card: CardConfig, now: Long): String? {
        val key = card.raw.toString()
        // Given up already, yet built again (its view's key changed): its view goes now.
        gaveUpIslands[key]?.let { return it }
        val times = islandLosses.getOrPut(key) { ArrayDeque() }
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > ISLAND_LOSS_WINDOW_MS) times.removeFirst()
        while (times.size > MAX_ISLAND_LOSSES) times.removeFirst()
        if (times.size < MAX_ISLAND_LOSSES) return null
        islandLosses.remove(key)
        val reason = "a `${card.type}` card whose island page stopped $MAX_ISLAND_LOSSES times in a row"
        gaveUpIslands[key] = reason
        Log.w(TAG, "Island ${card.type}: its page stopped $MAX_ISLAND_LOSSES times within ${ISLAND_LOSS_WINDOW_MS / 60_000} min; handing it to HA's frontend")
        return reason
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
        sink += view to pinnedLayoutParams(mod, fillsHeight = card.type in HA_CARD_FULL_HEIGHT)
        return true
    }

    /**
     * Overlay [FrameLayout.LayoutParams] for a pinned card: gravity from its offsets/transform, margins in dp. Its
     * height is the card-mod `height`, else, for `position: fixed` on a card whose own `ha-card` is `height: 100%`
     * ([fillsHeight]), the viewport's: the viewport is a fixed box's containing block, so HA draws such a card as tall
     * as the screen (a tile pinned with `bottom: 24px` runs from 24 px above the top to 24 px above the bottom, its
     * content centered). Otherwise the card's own height.
     */
    internal fun pinnedLayoutParams(mod: CardModStyle, fillsHeight: Boolean = false): FrameLayout.LayoutParams {
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
        val h = length(mod.fixedHeight, viewportH)?.takeIf { it > 0 }
            ?: if (fillsHeight && mod.fixedToViewport && mod.fixedHeight?.trim()?.lowercase() != "auto") viewportH else WRAP_CONTENT
        return FrameLayout.LayoutParams(w, h, horizontal or vertical).apply {
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
            layoutCard != null -> LayoutCardView(ctx, layoutViewCard(view), { child ->
                // A pinned card (card-mod `position: fixed`) goes to the overlay. Its host stays in the layout as an
                // empty slot, as in HA: a grid cell, or a place (and its card size) in the column placement.
                // A hidden card's slot goes with it.
                if (pinIfFixed(child, ctx)) {
                    wrapVisibility(child, LayoutCardView.PinnedSlot(context), ctx)
                } else {
                    wrapVisibility(child, buildCard(child, ctx, false), ctx).also { v ->
                        // Clarity sees each card by its index in the view's config (a layout-break builds nothing).
                        clarity?.cardBuilt(buildingIndex, view.cards.indexOfFirst { it === child }, child, v)
                        startCamerasEarly(v)
                    }
                }
            }, widthDp)
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
            // hui-entity-badge draws its state with state-display: a timestamp relative ("In 3 hours").
            if (state != null) ctx.showTimestampOr(stateView, s, state, anyDomain = true) else stateView.text = name.orEmpty()
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
        // A card that throws past its own guards (CardSurface's, or the ContainedCard round every other card) still
        // becomes HA's error card in its slot: the grid is the last line.
        val slotCards = java.util.IdentityHashMap<View, CardConfig>()
        grid.onChildFailed = { child ->
            val card = slotCards.remove(child)
            val i = grid.indexOfChild(child)
            if (card != null && i >= 0) {
                val lp = child.layoutParams
                grid.removeViewAt(i)
                // Its visibility conditions still hold: a hidden card stays hidden as the error card.
                grid.addView(wrapVisibility(card, errorCard(card, ctx), ctx), i, lp)
            }
        }
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
            val built = buildCard(card, ctx, fillViewport = false, layout = "grid")
            // A card that failed to build takes hui-error-card's size (its grid_options still apply), as in HA.
            val size = HaGridSize.of(if (built is ErrorCardView) card.copy(type = ErrorCardView.TYPE) else card, stateForBuild(card))
            val cardView = wrapVisibility(card, built, ctx)
            slotCards[cardView] = card
            cacheCardAsLayer(cardView, card, ctx)
            startCamerasEarly(cardView)
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
        val (placement, count) = HaMasonry.place(placed.map { it.value }, widthDp, ::stateForBuild)
        val row = MasonryColumnsLayout(context, px(if (widthDp < 600) 600f else 500f)).apply { applyViewPadding(this, ctx) }
        val columns = (0 until count).map { cardColumn(ctx) }
        columns.forEach { row.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT)) }
        val viewIndex = buildingIndex
        fun make(configIndex: Int, card: CardConfig): View {
            val v = wrapVisibility(card, buildCard(card, ctx, fillViewport = false), ctx)
            clarity?.cardBuilt(viewIndex, configIndex, card, v)
            cacheCardAsLayer(v, card, ctx)
            startCamerasEarly(v)
            return v
        }
        val deferred = pendingSteps
        // The first screen: each card whose column, as HA's card sizes fill it (50 px a unit, and the card's margins),
        // puts its top within it. Those are made and attached now, so the view's first frame shows that screen whole;
        // the rest follow in chunks ([SlicedBuild]), or here too, outside a view build. Every column gets its first
        // card now, so none changes width as the rest arrive. A card left for later holds its place in its column with
        // an empty slot (GONE: no room, no margin), so a card built before the ones above it still lands in order; made,
        // it waits in the view's stage ([CardStage]) until the cards go to their slots together ([placeStaged]).
        // A card hidden now ([shownNow]: a conditional whose conditions don't hold, a card's `visibility`) still has
        // its column, as in HA, but takes no room on the screen: it doesn't count toward it. One hidden now that's
        // within the first screen is built with it (shown again, it's in place at once); one below it is built in its
        // turn, and shows from then on if its conditions hold by then.
        val margin = cardMargin(ctx)
        val viewportDp = (if (height > 0) height else resources.displayMetrics.heightPixels) / dp
        val firstScreenDp = viewportDp * FIRST_SCREEN_SHARE
        val tops = DoubleArray(count) { 4.0 }
        var stage: CardStage? = null
        placed.forEachIndexed { k, (configIndex, card) ->
            val column = columns[placement[k]]
            val top = tops[placement[k]]
            if (shownNow(card, ctx)) tops[placement[k]] += HaCardSize.of(card, ::stateForBuild) * 50.0 + margin[0] + margin[2]
            // A live camera below the first screen is made and attached now all the same, in its place: its stream
            // starts with the view, as in a build in one go.
            if (deferred == null || top < firstScreenDp || playsLiveVideo(card)) {
                column.addView(make(configIndex, card), cardMarginParams(ctx, widthDp))
            } else {
                val slot = View(context).apply { visibility = GONE }
                column.addView(slot, cardMarginParams(ctx, widthDp))
                if (stage == null) stage = CardStage(context).also { row.addView(it, LinearLayout.LayoutParams(0, 0)); pendingStage = it }
                deferred += CardStep(card, { make(configIndex, card) }) { v, staged ->
                    val at = column.indexOfChild(slot)
                    column.removeViewAt(at)
                    if (staged) column.place(v, at, cardMarginParams(ctx, widthDp)) else column.addView(v, at, cardMarginParams(ctx, widthDp))
                }
            }
        }
        return row
    }

    /**
     * HA's sidebar view ([SidebarColumnsLayout]): from 760 px up, the cards with `view_layout.position: sidebar` in the
     * side column and the rest in the main one; narrower, all of them in one column in config order. Margins as in
     * the masonry view.
     */
    private fun buildSidebarLayout(cards: List<CardConfig>, ctx: VitroCardContext, widthDp: Int): View {
        val main = cardColumn(ctx)
        val split = widthDp >= 760
        val side = if (split) cardColumn(ctx) else main
        for ((cardIndex, card) in cards.withIndex()) {
            if (pinIfFixed(card, ctx)) continue
            val inSidebar = card.raw.optJSONObject("view_layout")?.optString("position") == "sidebar"
            val column = if (inSidebar) side else main
            val v = wrapVisibility(card, buildCard(card, ctx, fillViewport = false), ctx)
            clarity?.cardBuilt(buildingIndex, cardIndex, card, v)
            cacheCardAsLayer(v, card, ctx)
            startCamerasEarly(v)
            column.addView(v, cardMarginParams(ctx, widthDp))
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

    /** The view's `--masonry-view-card-margin` (4px 4px 8px), top, right, bottom, left in dp. */
    private fun cardMargin(ctx: VitroCardContext): FloatArray =
        MushroomTitleCardView.paddingDp(ctx.theme.vars["masonry-view-card-margin"]) ?: floatArrayOf(4f, 4f, 8f, 4f)

    /**
     * A masonry or sidebar column ([CardColumn]): the first card it shows has the top margin, the ones after it the
     * collapsed gap (the larger of the bottom and top margins).
     */
    private fun cardColumn(ctx: VitroCardContext): CardColumn {
        val m = cardMargin(ctx)
        return CardColumn(context, px(m[0]), px(maxOf(0f, m[0] - m[2])))
    }

    /** `margin: var(--masonry-view-card-margin, 4px 4px 8px)`, the side margins dropped at 500 px and narrower. */
    private fun cardMarginParams(ctx: VitroCardContext, widthDp: Int): LinearLayout.LayoutParams {
        val m = cardMargin(ctx)
        val narrow = widthDp <= 500
        return LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(if (narrow) 0 else px(m[3]), px(m[0]), if (narrow) 0 else px(m[1]), px(m[2]))
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
     * bounds would clip, nor for one that plays a live camera ([playsLiveVideo]): each video frame would draw its whole
     * layer again. Sections, masonry and sidebar views alike.
     *
     * Never for a card that holds an island ([IslandCard]: a custom card's page, an iframe) either: the island is a
     * browser's SurfaceView, whose picture shows through the hole its window leaves for it, and a layer draws that
     * hole as its own pixels. The island then stays blank while its page draws and sizes the card. The 160 px an island
     * holds before its page reports a size is already big on the S1, so every masonry island was blank there.
     *
     * And only while the view's layers fit its budget ([CardLayerBudget], [cardLayerBudgetBytes]), first laid out first:
     * the layers stay in HWUI's GPU cache, and once they fill it, Skia purges the masks and atlases every card draws
     * with and makes them again each frame. On the S1, heavy's masonry columns made 39 entities cards big (36 MB of
     * layers): the path-mask cache went and issuing a scrolling frame's draw commands took 18 ms instead of 6.7.
     */
    private fun cacheCardAsLayer(cardView: View, card: CardConfig, ctx: VitroCardContext) {
        if (!cardLayerWanted(ctx.theme.cardElevationDp, cardView.elevation) || playsLiveVideo(card)) return
        val budget = buildingLayers ?: CardLayerBudget(cardLayerBudgetBytes())
        fun want(v: View, w: Int, h: Int): Int = when {
            islandsIn(v).isNotEmpty() -> LAYER_TYPE_NONE
            (cardLayers == CardLayers.ALL || isBigCard(w, h, width, height)) && budget.claim(v, w, h) -> LAYER_TYPE_HARDWARE
            else -> LAYER_TYPE_NONE
        }.also { if (it == LAYER_TYPE_NONE) budget.release(v) }
        if (cardLayers == CardLayers.ALL) cardView.setLayerType(want(cardView, 0, 0), null)
        // Again when the card's size changes: a big card by its new size, and an island a card made later (an
        // auto-entities child, a conditional's) takes the card off its layer. A card whose size can't change (a fixed
        // grid_options.rows) never hears of that island here: the island lifts the layer itself ([liftLayersAbove]).
        cardView.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if (r - l == or - ol && b - t == ob - ot) return@addOnLayoutChangeListener
            val want = want(v, r - l, b - t)
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

    /**
     * The bytes a view's card layers may take: [BIG_LAYER_BUDGET_BYTES] on a 2 GB panel ([CardLayers.BIG]), and half of
     * HWUI's GPU cache with [CardLayers.ALL], so Skia keeps room for what every card draws with.
     */
    private fun cardLayerBudgetBytes(): Long = when (cardLayers) {
        CardLayers.BIG -> BIG_LAYER_BUDGET_BYTES
        CardLayers.ALL -> resources.displayMetrics.let { it.widthPixels.toLong() * it.heightPixels } * HWUI_CACHE_BYTES_PER_PIXEL / 2
    }

    /**
     * One built view's card layers and the bytes they take ([layerBytes]), against [limit] ([cacheCardAsLayer]). A card claims its
     * layer's bytes when it's laid out at a size that wants one, and gets it only if every layer of the view still fits.
     * A card that leaves the view ([roots]: its tree and its pinned cards) or loses its layer ([liftLayersAbove]) gives
     * its share back. Main thread.
     */
    internal class CardLayerBudget(val limit: Long) {
        private val held = LinkedHashMap<View, Long>()

        /** The view's tree and its pinned cards' frame, once built: a card under neither has left the view. */
        val roots = ArrayList<View>(2)

        /** The bytes the view's layers take now. */
        val used: Long get() = held.values.sum()

        /** [card] wants a layer of [w] x [h] px ([layerBytes]): true when it fits beside the others (its own earlier share replaced). */
        fun claim(card: View, w: Int, h: Int): Boolean {
            held.remove(card)
            held.keys.retainAll { it.layerType == View.LAYER_TYPE_HARDWARE && (roots.isEmpty() || inView(it)) }
            val bytes = layerBytes(w, h)
            if (used + bytes > limit) return false
            held[card] = bytes
            return true
        }

        /** [card] draws without a layer: its share is free. */
        fun release(card: View) {
            held.remove(card)
        }

        private fun inView(v: View): Boolean = generateSequence(v) { it.parent as? View }.any { p -> roots.any { it === p } }
    }

    /** [layout] is HA's `layout` for this card ([VitroCardContext.cardLayout]): `"grid"` straight in a section. */
    private fun buildCard(card: CardConfig, parentCtx: VitroCardContext, fillViewport: Boolean, layout: String? = null): View {
        val themedCtx = if (card.theme != null && card.theme != parentCtx.theme.name) {
            val overrideTheme = ThemeResolver.resolve(themeCatalog, darkMode, card.theme)
            contextFactory?.invoke(overrideTheme)?.let { themed ->
                // The card's theme sets the body font for the text inside it only if it sets one; a theme of colors
                // only keeps the font in force outside the card.
                themed.withFontFamily(VitroFonts.declaredFamily(overrideTheme) ?: VitroFonts.familyOf(parentCtx.context))
                    .also { it.viewType = parentCtx.viewType }
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
        } catch (e: Exception) {
            // One card that can't be built is HA's error card in its slot: the rest of the view builds as before.
            VitroFailures.report("build", card.type, e)
            return errorCard(card, parentCtx)
        } finally {
            inheritedCardVars = outerVars
            ctx.cardVars = outerCtxVars
            ctx.cardLayout = outerLayout
        }
    }

    /**
     * HA's stack host (`hui-stack-card`, the vertical and horizontal stacks' and the grid's): its `h1.card-header`
     * title when the config has one, over its `#root` (`flex: 1`, so a stretched stack's cards take the rest of its
     * height). [pad] applies the card's card-mod padding to the outermost view.
     */
    private fun stackHost(card: CardConfig, ctx: VitroCardContext, pad: (View) -> Unit, root: View): View {
        val title = card.title?.takeIf { it.isNotEmpty() } ?: return root.also(pad)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            pad(this)
            addView(stackTitle(ctx, title), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
    }

    /**
     * The stacks' `h1.card-header`: `--ha-card-header-font-size` (24 px) in the normal weight, `line-height:
     * var(--ha-line-height-condensed)` (1.2), `letter-spacing: -0.012em`, `padding: 24px 16px 16px`, in
     * `--ha-card-header-color` (the primary text color), aligned by `--ha-stack-title-text-align` (start). It wraps.
     */
    private fun stackTitle(ctx: VitroCardContext, title: String): TextView {
        val vars = ctx.theme.vars + inheritedCardVars
        fun v(name: String) = vars[name]?.let { ThemeResolver.resolveValue(it, vars, 0).trim() }?.takeIf { it.isNotEmpty() }
        val size = v("ha-card-header-font-size")?.let { ThemeResolver.parseDimensionDp(it, 24f) } ?: 24f
        val color = v("ha-card-header-color")?.let(ThemeResolver::parseColor) ?: ctx.theme.primaryTextColor
        return cssText(ctx, size, color).apply {
            text = title
            letterSpacing = -0.012f
            textAlignment = when (v("ha-stack-title-text-align")?.lowercase()) {
                "center" -> View.TEXT_ALIGNMENT_CENTER
                "end" -> View.TEXT_ALIGNMENT_VIEW_END
                "right" -> if (layoutDirection == LAYOUT_DIRECTION_RTL) View.TEXT_ALIGNMENT_VIEW_START else View.TEXT_ALIGNMENT_VIEW_END
                "left" -> if (layoutDirection == LAYOUT_DIRECTION_RTL) View.TEXT_ALIGNMENT_VIEW_END else View.TEXT_ALIGNMENT_VIEW_START
                else -> View.TEXT_ALIGNMENT_VIEW_START
            }
            lineBox(ctx, this, size, 1.2f)
            setPadding(ctx.px(16f), ctx.px(24f), ctx.px(16f), ctx.px(16f))
        }
    }

    /**
     * CSS `gap` between a stack's shown cards: LinearLayout's middle dividers skip GONE children, so a hidden card
     * leaves no gap behind, as a `[hidden]` flex item leaves none in HA.
     */
    private fun LinearLayout.gapBetweenShown(gap: Int, vertical: Boolean) {
        if (gap <= 0) return
        dividerDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            if (vertical) setSize(0, gap) else setSize(gap, 0)
        }
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }

    /** HA's error card for [card], or an empty view if even that can't be made (a theme it can't read). */
    private fun errorCard(card: CardConfig, ctx: VitroCardContext): View =
        try {
            ErrorCardView(ctx, card)
        } catch (e: Exception) {
            VitroFailures.report("build", ErrorCardView.TYPE, e)
            View(context)
        }

    /** Tests: a card of their own for a config (one that throws), before Vitro's; null for Vitro's. */
    internal var testCards: ((CardConfig, VitroCardContext) -> View?)? = null

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
        fun applyStackPadding(v: View) {
            stackMod.paddingInsets?.let { p ->
                v.setPadding(ctx.px(p.left), ctx.px(p.top), ctx.px(p.right), ctx.px(p.bottom))
            }
        }
        // Anything outside Vitro's supported set goes to an island on purpose (CardCompat), decided once per card.
        val built: View = testCards?.invoke(card, ctx) ?: if (card.inIsland(IslandScripts.of(ctx.resources))) {
            WebIslandCardView(ctx, card, fillViewport = fillViewport, adopted = adoptIsland(WebIslandCardView.keyOf(card, fillViewport, ctx.viewType)))
        } else when (card.type) {
            // Layout containers
            "vertical-stack" -> stackHost(card, ctx, ::applyStackPadding, LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gapBetweenShown(verticalGap, vertical = true)
                card.childCards.forEach { child ->
                    addView(wrapVisibility(child, buildCard(child, ctx, false), ctx), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }
            })
            "horizontal-stack" -> stackHost(card, ctx, ::applyStackPadding, LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gapBetweenShown(horizontalGap, vertical = false)
                val childH = if (card.childCards.size >= 2) MATCH_PARENT else WRAP_CONTENT
                card.childCards.forEach { child ->
                    addView(wrapVisibility(child, buildCard(child, ctx, false), ctx), LinearLayout.LayoutParams(0, childH, 1f))
                }
            })
            // HA's `square` is on unless the config turns it off (`square !== false`).
            "grid" -> stackHost(card, ctx, ::applyStackPadding, GridCardLayout(context, card.gridColumns(), gridGap, square = card.raw.opt("square") != false).apply {
                card.childCards.forEach { child ->
                    addView(wrapVisibility(child, buildCard(child, ctx, false), ctx), android.view.ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }
            })
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
            "date" -> SmallCardConfigs.dateError(card)?.let { HaErrorCardView(ctx, it) } ?: DateCardView(ctx, card)
            "plant-status" -> if (card.entity?.substringBefore('.') == "plant") PlantStatusCardView(ctx, card)
                else HaErrorCardView(ctx, "Specify an entity from within the plant domain")
            "toggle-group" -> if ((card.raw.optJSONArray("entities")?.length() ?: 0) > 0) ToggleGroupCardView(ctx, card)
                else HaErrorCardView(ctx, "Entities are required")
            "shortcut" -> ShortcutCardView(ctx, card)
            "alert" -> SmallCardConfigs.alertError(card)?.let { HaErrorCardView(ctx, it) } ?: AlertCardView(ctx, card)
            "iframe" -> SmallCardConfigs.iframeError(card)?.let { HaErrorCardView(ctx, it) }
                ?: IframeCardView(ctx, card, fillViewport, adopted = adoptIsland(IframeCardView.keyFor(ctx, card, fillViewport)))
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
            else -> WebIslandCardView(ctx, card, fillViewport = fillViewport, adopted = adoptIsland(WebIslandCardView.keyOf(card, fillViewport, ctx.viewType)))
        }
        (built as? CardSurface)?.let { surface ->
            // A conditional's child keeps its own type.
            if (surface.cardType == null) surface.cardType = card.type
            // Its first render threw (and was contained): HA's error card, sized as one, rather than the half-made card.
            if (surface.failure != null && surface !is ErrorCardView) return errorCard(card, ctx)
        }
        // An island that loads card-mod styles its card itself: styling the native frame too would apply it twice
        // (a 0.5 opacity would show as 0.25). Only for the island drawing this card: a `conditional` returns its
        // child's island, which never receives the conditional's own card_mod.
        val islandStyles = built is WebIslandCardView && built.config === card && IslandScripts.of(ctx.resources).cardMod
        if (!islandStyles) (built as? CardSurface)?.applyCardMod(card)
        // swipe-card has no card of its own (no background to restyle): its `:host` opacity still applies to the pager.
        // Its guard ([contain]) is what a visibility change fades, so the pager keeps this opacity throughout.
        if (built is SwipeCardView) stackMod.opacity?.let { built.alpha = it.coerceIn(0f, 1f) }
        val contained = contain(card, built, ctx)
        // `margin` on the card insets it inside its slot, as an outer margin does in the browser.
        // Not around an island that applies card-mod: its `margin` is drawn there, and twice would double the inset.
        val margin = stackMod.marginInsets?.takeUnless { islandStyles } ?: return contained
        return FrameLayout(context).apply {
            setPadding(px(margin.left), px(margin.top), px(margin.right), px(margin.bottom))
            addView(contained, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    /**
     * [built] inside a [ContainedCard] when it's a card without [CardSurface]'s own guards (a heading, Mushroom's chips
     * and title, a stack or another container): a throw in its measure, layout, draw or touch shows HA's error card in
     * its slot, wherever the card sits. A [CardSurface] guards itself; a conditional's card was contained when it was
     * built; a plain [View] (an empty slot) runs no card code.
     */
    private fun contain(card: CardConfig, built: View, ctx: VitroCardContext): View = when {
        built is CardSurface || built is ContainedCard || card.type == "conditional" || built.javaClass == View::class.java -> built
        else -> ContainedCard(ctx, card.type, built) { errorCard(card, ctx) }
    }

    /**
     * Whether [card] shows now: the conditions [wrapVisibility] gives its view (its `visibility`, a conditional's
     * `conditions`) hold for the store's states, the time, the screen's width and the signed-in user, as its view
     * decides when it's built.
     */
    private fun shownNow(card: CardConfig, ctx: VitroCardContext): Boolean {
        val conds = card.visibility + VisibilityCondition.parseList(card.raw.optJSONArray("conditions"))
        if (conds.isEmpty()) return true
        val widthDp = (resources.displayMetrics.widthPixels / dp).toInt()
        val userId = ctx.haUser().optString("id", "").ifEmpty { null }
        val nowMs = ctx.now()
        return conds.all { VisibilityCondition.evaluateAt(it, nowMs, store::get, widthDp, userId) }
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
                val fadeMs = transitionMs ?: DEFAULT_FADE_MS
                crossfade(view, visible, fadeMs)
                // An island inside pauses once the card is hidden (the fade's end), and runs again when it's shown.
                if (islandsIn(view).isNotEmpty()) {
                    if (visible) updateIslandsShown() else view.postDelayed({ updateIslandsShown() }, fadeMs + 16)
                }
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

    /**
     * A shown card's opacity: its `card_mod` `opacity` when it sets one, else fully opaque. A [ContainedCard] rests
     * opaque: the card inside it (a swipe-card's pager) keeps its own opacity.
     */
    private fun restingAlpha(view: View): Float = (view as? CardSurface)?.activeCardMod?.opacity?.coerceIn(0f, 1f) ?: 1f

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
        /**
         * Takes every hardware layer off the views between [island] and the VitroView holding it ([cacheCardAsLayer]):
         * an island attached into a card that already has a layer (an auto-entities or conditional child made later,
         * in a card whose size doesn't change) would otherwise stay blank under it. Main thread; nothing outside a
         * VitroView (a More Info sheet's island) is touched.
         */
        internal fun liftLayersAbove(island: View) {
            val above = ArrayList<View>()
            var p = island.parent
            while (p is View && p !is VitroView) {
                above += p
                p = p.parent
            }
            if (p !is VitroView) return
            for (v in above) if (v.layerType == View.LAYER_TYPE_HARDWARE) v.setLayerType(View.LAYER_TYPE_NONE, null)
        }

        /** HA's `CONNECTION_LOST_TOAST_ID`, and how long a drop lasts before its toast shows. */
        private const val CONNECTION_LOST_TOAST = "connection-lost"
        private const val CONNECTION_LOST_DELAY_MS = 1_000L

        /** How long a rebuilt view keeps scrolling back to where it was while its islands report their heights. */
        private const val RESTORE_SCROLL_MS = 5_000L

        /**
         * How long a masonry build goes on past its first screen before that screen is drawn ([SlicedBuild]): HA's
         * `_createColumns` waits for the next render once more than 16 ms went by since its slice began
         * (`hui-masonry-view.ts`).
         */
        internal const val SLICE_BUDGET_MS = 16L

        /**
         * The longest chunk of cards below a masonry view's first screen ([SlicedBuild]): with the frame that lays its
         * cards out, under the 50 ms a task may wait before the main thread counts as busy (RAIL's input budget, which
         * Clarity's main-thread probe uses too). One card that takes longer is still made whole.
         */
        internal const val REST_SLICE_BUDGET_MS = 24L

        /**
         * How far down a masonry view its first pass builds ([buildMasonryLayout]), as a share of the viewport's height:
         * HA's card sizes only estimate a card's height, so a little more than the screen, not to leave its foot empty.
         */
        internal const val FIRST_SCREEN_SHARE = 1.25

        /** Cards that take long to make ([isHeavyCard]): each starts a chunk of its own. */
        private val HEAVY_CARD_TYPES = setOf("history-graph", "statistics-graph", "map", "logbook", "calendar", "weather-forecast")

        /** An entities card with this many rows or more takes long to make ([isHeavyCard]). */
        private const val HEAVY_ENTITY_ROWS = 10

        /** Whether a card is drawn through a hardware layer: never when it casts a shadow, which the layer's bounds would clip. */
        internal fun cardLayerWanted(themeElevationDp: Float, viewElevation: Float): Boolean =
            themeElevationDp == 0f && viewElevation == 0f

        /** [card] or a card inside it (a stack, a grid) plays a camera live (`camera_view: live`). */
        internal fun playsLiveVideo(card: CardConfig): Boolean =
            card.raw.optString("camera_view") == "live" || card.childCards.any(::playsLiveVideo)

        /** The share of the dashboard's area from which a card gets its own layer. */
        internal const val BIG_CARD_SHARE = 0.07f

        internal fun isBigCard(w: Int, h: Int, rootW: Int, rootH: Int): Boolean =
            rootW > 0 && rootH > 0 && w.toLong() * h >= BIG_CARD_SHARE * rootW.toLong() * rootH

        /**
         * A card layer's GPU memory: RGBA 8888 at the size Skia allocates it, each side rounded up as its approximate fit
         * does (`GrResourceProvider::MakeApprox`). A 348 x 314 entities card takes 512 x 512, 1 MB: measured on Android 10,
         * 16 layers held 16.3 MB.
         */
        internal fun layerBytes(w: Int, h: Int): Long = approxSide(w).toLong() * approxSide(h) * 4

        /** Skia's approximate fit: at least 16, the next power of two up to 1024, then the next half step of one. */
        internal fun approxSide(px: Int): Int {
            val v = maxOf(16, px)
            if (v and (v - 1) == 0) return v
            val ceil = Integer.highestOneBit(v) shl 1
            if (v <= 1024) return ceil
            val mid = (ceil shr 1) + (ceil shr 2)
            return if (v <= mid) mid else ceil
        }

        /**
         * What a view's card layers may take on a 2 GB panel ([CardLayers.BIG]): the S1 may give layers about 8 MB
         * (816b3b4de measured every card's layer at 17 MB of GPU memory there, too much).
         */
        internal const val BIG_LAYER_BUDGET_BYTES = 8L * 1024 * 1024

        /**
         * HWUI's GPU cache per pixel of the display (Android 10's `SURFACE_SIZE_MULTIPLIER`, 12 x 4 bytes; later versions
         * allow more): about 50 MB at 1440 x 720, shared by every layer, mask, glyph atlas and image the app draws.
         */
        internal const val HWUI_CACHE_BYTES_PER_PIXEL = 48L

        private const val TAG = "Glassworks.Vitro"
        private const val PERIODIC_CHECK_MS = 60_000L
        private const val DEFAULT_FADE_MS = 300L

        /** A badge's text: `name · state` when the name shows, else the formatted state (`21.5 °C`, `Closed`). */
        fun badgeText(badge: CardConfig, s: EntityState?): String {
            val raw = badge.raw
            val name = badge.name(s) ?: s?.displayName ?: badge.entity.orEmpty()
            val showName = raw.optBoolean("show_name", badge.name(s) != null)
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
            val showName = raw.optBoolean("show_name", badge.name(s) != null)
            val showState = raw.optBoolean("show_state", true)
            val name = if (showName) badge.name(s) ?: s?.displayName ?: badge.entity.orEmpty() else null
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

        /** How many times an island card's page may die within [ISLAND_LOSS_WINDOW_MS] before it is given up for. */
        internal const val MAX_ISLAND_LOSSES = 2
        internal const val ISLAND_LOSS_WINDOW_MS = 5 * 60_000L
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
