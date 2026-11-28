/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.util.LruCache
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import dev.glassworks.auth.OAuth
import dev.glassworks.engine.Page
import dev.glassworks.engine.vitro.GuardedHandler
import dev.glassworks.engine.vitro.VitroFailures
import dev.glassworks.engine.vitro.camera.CameraPlayers
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.ForecastDay
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.data.HistoryPoint
import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.theme.CardModStyle
import dev.glassworks.engine.vitro.theme.CssBackground
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.platform.Platform
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max

/** Shared context passed to every Vitro card renderer on the active view. */
class VitroCardContext(
    val context: Context,
    val haBase: String,
    val store: EntityStore,
    val theme: ResolvedTheme,
    val resources: List<LovelaceResource>,
    val accessToken: () -> String?,
    val haConfig: () -> JSONObject = { JSONObject() },
    val callService: (domain: String, service: String, data: JSONObject?, target: JSONObject?) -> Unit,
    /**
     * [callService] that reports whether Home Assistant accepted the call (false too when it never reached HA).
     * Without one (tests) it calls [callService] and reports nothing.
     */
    val callServiceThen: (domain: String, service: String, data: JSONObject?, target: JSONObject?, done: (Boolean) -> Unit) -> Unit =
        { domain, service, data, target, _ -> callService(domain, service, data, target) },
    val callWs: (msg: JSONObject, done: (success: Boolean, result: Any?, error: JSONObject?) -> Unit) -> Unit = { _, done -> done(false, null, null) },
    val navigate: (path: String) -> Unit,
    val openMoreInfo: (entityId: String) -> Unit,
    val fetchHistory: (entityIds: List<String>, hours: Int, done: (Map<String, List<HistoryPoint>>) -> Unit) -> Unit,
    val subscribeTemplate: (template: String, onRendered: (String) -> Unit) -> () -> Unit,
    /** Loopback micro-host server + GeckoView [Page] factory for Tier 3 Custom Card Islands (zero WebView). */
    val islandServer: VitroIslandServer? = null,
    val openIslandPage: ((url: String) -> Page?)? = null,
    /**
     * `weather/subscribe_forecast` (`daily`, `hourly` or `twice_daily`), held open as HA's weather card holds it: every
     * list the integration pushes reaches `onForecast` (any thread), across reconnects, until the returned handle is
     * called. The default (tests, no engine) never delivers.
     */
    val subscribeForecast: (entityId: String, forecastType: String, onForecast: (List<ForecastDay>) -> Unit) -> () -> Unit = { _, _, _ -> {} },
    /** HA's `auth/current_user` result (`id`, `name`, `is_admin`, `is_owner`), empty until it arrives. */
    val haUser: () -> JSONObject = { JSONObject() },
    /**
     * The players a live camera can use ([CameraPlayers]: HLS and MJPEG, and WebRTC once its player registers).
     * Null (tests, no engine): no player, so live cameras poll snapshots.
     */
    val cameraPlayers: CameraPlayers? = null,
    /**
     * A WebSocket subscription (`todo/item/subscribe`, say): every event of [msg]'s subscription reaches `onEvent`, on
     * any thread, until the returned handle is called. It is held across connections, as HA's frontend holds its own:
     * asked for while the connection is down it goes out once it is back, and a new connection sends it again.
     * `onError` runs once, on any thread, if Home Assistant rejects it, or with null when there is no connection to
     * Home Assistant at all. A delivery already under way when the handle is called may still finish, so a consumer
     * checks on its own thread that the subscription is still its current one before using an event. The default
     * (tests, no engine) never delivers anything.
     */
    val subscribeWs: (msg: JSONObject, onEvent: (JSONObject) -> Unit, onError: (JSONObject?) -> Unit) -> () -> Unit = { _, _, _ -> {} },
    /**
     * [subscribeWs] whose message is built again by `message` for every connection (a `history/stream` window that
     * follows the clock): `onEvent` hears with `first` the first event of each, after which a stream starts over. The
     * default hands the message built now to [subscribeWs].
     */
    val subscribeWsFresh: (
        message: () -> JSONObject,
        onEvent: (event: JSONObject, first: Boolean) -> Unit,
        onError: (JSONObject?) -> Unit,
    ) -> () -> Unit = { message, onEvent, onError -> subscribeWs(message(), { onEvent(it, false) }, onError) },
    /**
     * A subscription on the connection of now only, which also says when Home Assistant accepted it (`onStarted`, on
     * any thread): what a web island's `subscribeMessage` resolves on. The island starts it again itself after a
     * reconnect. `onError` also runs (its `error`) if the connection drops before HA answered. The default (tests, no
     * engine) is [subscribeWs], and says it started as soon as it's asked.
     */
    val subscribeWsStarted: (
        msg: JSONObject,
        onStarted: () -> Unit,
        onEvent: (JSONObject) -> Unit,
        onError: (JSONObject?) -> Unit,
    ) -> () -> Unit = { msg, onStarted, onEvent, onError ->
        var failed = false
        subscribeWs(msg, onEvent) { e -> failed = true; onError(e) }.also { if (!failed) onStarted() }
    },
    /** The wall clock, ms since the epoch: what HA's frontend reads as `new Date()` for its time windows. */
    val now: () -> Long = System::currentTimeMillis,
    /** The dashboard's own URL (HA's page `location`), which relative links resolve against; null when unknown. */
    val pageUrl: () -> String? = { null },
    /**
     * A web island looked up [entityId] at runtime: the entity subscription takes it in, so its changes reach the
     * island. Any thread. The default (tests, no engine) does nothing.
     */
    val watchEntity: (entityId: String) -> Unit = {},
    /**
     * A web island walked `hass.states` (`Object.values`, `for…in`): HA's frontend hands a card every state, so the
     * view it sits on follows every entity from now on. Any thread. The default (tests, no engine) does nothing.
     */
    val watchAllEntities: () -> Unit = {},
    /**
     * HA's registries for a web island's `hass` ([dev.glassworks.engine.vitro.data.HassRegistries]), shared by every
     * island: `onChange` gets the whole set (now when one is known, again on each change) on any thread until the
     * returned handle is called. Any thread. The default (tests, no engine) never delivers.
     */
    val watchRegistries: (onChange: (JSONObject) -> Unit) -> () -> Unit = { { } },
    /**
     * [onChange] runs (on any thread) each time the page gets a new Home Assistant token or learns the signed-in user
     * ([haUser]), until the returned handle is called: a web island whose page stays sends it the new sign-in. The
     * default (tests, no engine) never calls it.
     */
    val watchToken: (onChange: () -> Unit) -> () -> Unit = { { } },
    /**
     * Whether the screen takes a tap (the frame's `input`), which is what HA's page reads as `(pointer: coarse)` in
     * the browser on that device. False on a frame without touch (the Mirror): HA there styles for a fine pointer.
     */
    val touch: Boolean = true,
    /**
     * A web island found its card drawing with elements of Home Assistant's frontend that an island page doesn't have
     * ([tags], e.g. `ha-control-circular-slider`), in the custom cards [owners] (`custom:` types; the island's own
     * card, or one inside it): the card's view goes to HA's frontend
     * ([dev.glassworks.engine.vitro.compat.IslandFrontend]). Main thread. The default (tests, no engine) does nothing.
     */
    val needsFrontend: (card: CardConfig, tags: List<String>, owners: List<String>) -> Unit = { _, _, _ -> },
    /** An island's card answered `getCardSize()` with [size] (main thread): HA's masonry view weighs it by that. */
    val islandCardSize: (card: CardConfig, size: Double) -> Unit = { card, size ->
        dev.glassworks.engine.vitro.compat.IslandCardSizes.record(card.raw, size)
    },
    /**
     * A web island's [card] threw on something an island page doesn't have and drew nothing ([reason] names it): its
     * view goes to HA's frontend, as for [needsFrontend]. Main thread. The default (tests, no engine) does nothing.
     */
    val islandFailed: (card: CardConfig, reason: String) -> Unit = { _, _ -> },
    /**
     * A native `custom:auto-entities` [card] matched a row (or card) Vitro doesn't draw natively ([reason] names it,
     * [dev.glassworks.engine.vitro.compat.WebFallback.matchedReason]): its view goes to HA's frontend, as for
     * [needsFrontend]. Main thread. The default (tests, no engine) does nothing.
     */
    val matchedNeedFrontend: (card: CardConfig, reason: String) -> Unit = { _, _ -> },
    /**
     * Whether the page's connection to Home Assistant is up now: what a web island's `hass.connected` says. The
     * default (tests, no engine) is up.
     */
    val haConnected: () -> Boolean = { true },
    /**
     * Counts the connections to Home Assistant that came up: a web island page whose bridge was down learns from it
     * whether HA's connection changed meanwhile (its `resubscribe: false` subscriptions then ended). The default
     * (tests, no engine) never changes.
     */
    val haConnectionEpoch: () -> Long = { 0L },
    /**
     * [onChange] runs (on any thread) each time the connection to Home Assistant goes down (false) or is up again
     * (true), until the returned handle is called: a web island's `hass.connection` fires `disconnected` and `ready`,
     * and its subscriptions start again. HA drops a connection's subscriptions with it. The default (tests, no engine)
     * never calls it.
     */
    val watchConnection: (onChange: (connected: Boolean) -> Unit) -> () -> Unit = { { } },
    /**
     * [onChange] gets Home Assistant's config ([haConfig], `get_config`) each time a connection reads it, on any
     * thread, until the returned handle is called: a web island's `hass.config` follows it (a page made before it
     * arrived has none, and HA's cards read its `version`). The default (tests, no engine) never calls it.
     */
    val watchConfig: (onChange: (JSONObject) -> Unit) -> () -> Unit = { { } },
    /**
     * What Home Assistant's web UI keeps in `localStorage` on this panel ([dev.glassworks.web.HaPageStorage]), or null
     * when the panel has no copy for this Home Assistant: a web island starts its card's storage from it (#881).
     */
    val pageStorage: () -> JSONObject? = { null },
    /**
     * HA's `showToast`: a toast over the page ([HaToast]), which outlives the card or sheet that showed it (an update
     * that failed after its dialog closed). Main thread. The default (tests, no engine) shows nothing.
     */
    val showToast: (HaToast) -> Unit = {},
    /**
     * [navigate] for a `navigate` action with `navigation_replace: true` (HA's `navigate(path, {replace: true})`): the
     * page it leaves is no step in the history. The default (tests, no engine) is [navigate].
     */
    val navigateReplace: (path: String) -> Unit = navigate,
    /**
     * Opens HA's own More Info dialog for an entity in HA's frontend, over the view on screen, on its `more-info-view`
     * ([view], null for its default), for what only HA's frontend has ([reason]): a logbook entry's detail dialog.
     * False when there's no page to open it in (tests, no engine): the caller does what it would without HA's frontend.
     */
    val moreInfoInFrontend: (entityId: String, view: String?, reason: String) -> Boolean = { _, _, _ -> false },
) {
    val dp: Float = context.resources.displayMetrics.density
    /** The main thread, for the card's own updates: one that throws is logged and dropped ([GuardedHandler]). */
    val main: Handler = GuardedHandler(Looper.getMainLooper())

    /**
     * CSS custom properties in force for the card being built: the ones its enclosing cards and its own `card_mod`
     * set on `ha-card` (names without `--`). The view sets them around each card's construction, so a card reads
     * them in its constructor, the way a browser inherits them through the card's shadow roots.
     */
    var cardVars: Map<String, String> = emptyMap()

    /**
     * HA's `layout` property on the card being built (hui-card's): `"grid"` for a card placed straight in a sections
     * view's grid section, null anywhere else (inside a stack, a grid card, masonry). Set around each card's
     * construction like [cardVars]; Mushroom 5 sizes its vertical info differently in a grid.
     */
    var cardLayout: String? = null

    /**
     * The type of the view being built (`masonry`, `sections`, `panel`, `sidebar`, a layout-card view's `custom:` type),
     * null outside a view (More Info, tests). Set once per view build; Bubble Card picks its layout from it.
     */
    var viewType: String? = null

    fun px(dpVal: Float): Int = (dpVal * dp).toInt()

    /**
     * This context for a card that sets its own `font-family` (card-mod): the same in every way, but its views are
     * built on a [VitroFontContext], so every text the card draws (and the cards inside it) uses [fontFamily].
     */
    fun withFontFamily(fontFamily: String): VitroCardContext = VitroCardContext(
        context = VitroFontContext(context, fontFamily),
        haBase = haBase,
        store = store,
        theme = theme,
        resources = resources,
        accessToken = accessToken,
        haConfig = haConfig,
        callService = callService,
        callServiceThen = callServiceThen,
        callWs = callWs,
        navigate = navigate,
        openMoreInfo = openMoreInfo,
        fetchHistory = fetchHistory,
        subscribeTemplate = subscribeTemplate,
        islandServer = islandServer,
        openIslandPage = openIslandPage,
        subscribeForecast = subscribeForecast,
        haUser = haUser,
        cameraPlayers = cameraPlayers,
        subscribeWs = subscribeWs,
        subscribeWsFresh = subscribeWsFresh,
        subscribeWsStarted = subscribeWsStarted,
        now = now,
        pageUrl = pageUrl,
        watchEntity = watchEntity,
        watchAllEntities = watchAllEntities,
        watchRegistries = watchRegistries,
        watchToken = watchToken,
        touch = touch,
        needsFrontend = needsFrontend,
        islandFailed = islandFailed,
        islandCardSize = islandCardSize,
        matchedNeedFrontend = matchedNeedFrontend,
        haConnected = haConnected,
        haConnectionEpoch = haConnectionEpoch,
        watchConnection = watchConnection,
        watchConfig = watchConfig,
        pageStorage = pageStorage,
        showToast = showToast,
        navigateReplace = navigateReplace,
        moreInfoInFrontend = moreInfoInFrontend,
    ).also {
        it.cardVars = cardVars
        it.cardLayout = cardLayout
        it.viewType = viewType
    }

    /** [sp] scaled by the theme's text size ([ResolvedTheme.fontScale]); pass the result to `setTextSize(SP, …)`. */
    fun scaledSp(sp: Float): Float = sp * theme.fontScale

    /**
     * Dispatches a standard HA Lovelace `tap_action` / `hold_action` / `double_tap_action`. When the action carries
     * a `confirmation`, a native dialog asks first and the action only runs on Confirm.
     */
    fun performAction(action: ActionConfig, defaultEntityId: String?) {
        // A tap runs in its own message, outside the card's guards: a throw here is logged, the tap does nothing.
        try {
            performActionNow(action, defaultEntityId)
        } catch (e: Exception) {
            VitroFailures.report("action", null, e)
        }
    }

    private fun performActionNow(action: ActionConfig, defaultEntityId: String?) {
        val prompt = action.confirmation
        if (prompt != null && action.action != "none") {
            // The dialog's button runs on its own message, outside [performAction]'s guard.
            confirmThen(prompt) {
                try {
                    runAction(action, defaultEntityId)
                } catch (e: Exception) {
                    VitroFailures.report("action", null, e)
                }
            }
            return
        }
        runAction(action, defaultEntityId)
    }

    private fun confirmThen(message: String, onConfirm: () -> Unit) {
        runCatching {
            AlertDialog.Builder(context)
                .setMessage(message)
                .setNegativeButton(HaText.ui("ui.common.cancel"), null)
                .setPositiveButton("Confirm") { _, _ -> onConfirm() }
                .show()
        }
    }

    private fun runAction(action: ActionConfig, defaultEntityId: String?) {
        // HA's handleAction tells in a toast when an action has nothing to act on.
        fun missing(key: String) = showToast(HaToast(HaText.ui(key)))
        when (action.action) {
            "none" -> Unit
            "toggle" -> defaultEntityId?.let(::toggleEntity) ?: missing("ui.panel.lovelace.cards.actions.no_entity_toggle")
            // HA's handleAction: the action's own `entity`, else the card's.
            "more-info" -> (action.entity ?: defaultEntityId)?.let(openMoreInfo) ?: missing("ui.panel.lovelace.cards.actions.no_entity_more_info")
            "navigate" -> action.navigationPath?.takeIf { it.isNotEmpty() }?.let(if (action.replace) navigateReplace else navigate)
                ?: missing("ui.panel.lovelace.cards.actions.no_navigation_path")
            "call-service", "perform-action" -> {
                val svc = action.service?.takeIf { it.isNotEmpty() } ?: return missing("ui.panel.lovelace.cards.actions.no_action")
                val domain = svc.substringBefore('.', "")
                val name = svc.substringAfter('.', "")
                if (domain.isNotEmpty() && name.isNotEmpty()) {
                    callService(domain, name, action.serviceData, action.target)
                }
            }
            "url" -> action.urlPath?.takeIf { it.isNotEmpty() }?.let(navigate) ?: missing("ui.panel.lovelace.cards.actions.no_url")
        }
    }

    /**
     * An optimistic write: shows [next] at once and calls the service. If HA turns the call down, the entity goes back
     * to its last confirmed state (what HA reported, or what it accepted since), unless a newer state replaced the
     * optimistic one meanwhile (that one is the truth): an entity HA didn't change sends no event that would correct
     * it. Overlapping writes to one entity share that confirmed state, so a refused write never restores another
     * write's refused value. With no [next] (nothing to show) it only calls. Main thread only.
     */
    fun callOptimistic(next: EntityState?, domain: String, service: String, data: JSONObject?, target: JSONObject? = null) {
        if (next == null) {
            callServiceThen(domain, service, data, target) {}
            return
        }
        val id = next.entityId
        val writes = optimisticWrites.getOrPut(store) { HashMap() }
        val current = store[id]
        val pending = writes[id] ?: current?.let { PendingWrites(it).also { p -> writes[id] = p } }
        val seq = pending?.let { p ->
            // The store holds something other than our last proposal: HA reported it meanwhile, so it is the
            // confirmed state now, newer than every write issued so far.
            if (current != null && p.latest != null && current !== p.latest) {
                p.confirmed = current
                p.confirmedSeq = p.issued
            }
            p.latest = next
            p.outstanding++
            ++p.issued
        } ?: 0
        store.putLocal(next)
        callServiceThen(domain, service, data, target) { ok ->
            main.post {
                val p = pending ?: return@post
                if (ok) {
                    // HA accepted it: this is what the entity becomes, the state a later refusal goes back to (unless
                    // HA has already reported something newer than this write).
                    if (seq > p.confirmedSeq) {
                        p.confirmed = next
                        p.confirmedSeq = seq
                    }
                } else if (p.latest === next && store[id] === next) {
                    // The newest write was refused and nothing newer replaced it: back to the confirmed state.
                    store.putLocal(p.confirmed)
                }
                if (--p.outstanding == 0) writes.remove(id)
            }
        }
    }

    /** HA's `toggle` for one entity: the domain's own service, with an optimistic flip for plain on/off states. */
    fun toggleEntity(entityId: String) {
        val domain = entityId.substringBefore('.')
        val cur = store[entityId]
        val (targetDomain, service) = when (domain) {
            "lock" -> "lock" to if (cur?.state == "locked") "unlock" else "lock"
            // HA's toggleEntity: a script that is off starts; one that's running (or in any other state) stops.
            "script" -> "script" to if (cur == null || cur.state == "off") "turn_on" else "turn_off"
            // HA's toggle on things that can only fire: press the button, activate the scene.
            "button", "input_button" -> domain to "press"
            "scene" -> "scene" to "turn_on"
            in TOGGLE_DOMAINS -> domain to "toggle"
            else -> "homeassistant" to "toggle"
        }
        // Optimistic local state flip for instant native responsiveness (undone if HA turns the call down).
        val flipped = if (cur != null && domain != "script" && cur.state in setOf("on", "off")) {
            cur.copy(state = if (cur.state == "on") "off" else "on")
        } else {
            null
        }
        callOptimistic(flipped, targetDomain, service, JSONObject().put("entity_id", entityId))
    }

    /**
     * Loads an HA-hosted image asynchronously on a shared daemon pool and delivers on the main thread.
     * Images are downsampled to at most [MAX_IMAGE_DIM] px on the long edge and decoded as RGB_565, and kept
     * in a shared 12 MB LRU cache. URLs carrying a `t=` query parameter (live camera polls) are never cached.
     * A [public] image (one of HA's static assets) goes without the panel's token.
     */
    fun loadImage(rawUrl: String, owner: View? = null, public: Boolean = false, done: (Bitmap?) -> Unit) {
        if (owner == null) fetchImage(rawUrl, public, done) else whenShown(owner) { fetchImage(rawUrl, public, done) }
    }

    /**
     * Runs [action] once [view] is on screen, as HA's page never loads a hidden card's images: now when it's shown,
     * at the first frame that shows it when it's attached but hidden (its visibility conditions, a cached view that
     * isn't the current one). A view not attached yet decides on the next turn of the main loop (the build attaches
     * a card in the turn that creates it); one never attached (built on its own) runs it then.
     */
    private fun whenShown(view: View, action: () -> Unit) {
        fun decide() = if (view.isAttachedToWindow && !view.isShown) awaitShown(view, action) else action()
        if (view.isAttachedToWindow) decide() else main.post(::decide)
    }

    private fun awaitShown(view: View, action: () -> Unit) {
        val observer = view.viewTreeObserver
        lateinit var detach: View.OnAttachStateChangeListener
        val preDraw = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (view.isShown) {
                    stop()
                    action()
                }
                return true
            }

            fun stop() {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                view.removeOnAttachStateChangeListener(detach)
            }
        }
        // A card taken off screen (its view rebuilt) never shows: its wait ends with it.
        detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = preDraw.stop()
        }
        observer.addOnPreDrawListener(preDraw)
        view.addOnAttachStateChangeListener(detach)
    }

    private fun fetchImage(rawUrl: String, public: Boolean, done: (Bitmap?) -> Unit) {
        val fullUrl = absoluteUrl(rawUrl)
        val cacheable = !LIVE_PARAM.containsMatchIn(fullUrl)
        pendingImageLoads.incrementAndGet()
        // The cache generation this load started under: a clear while it was in flight (another sign-in) keeps its
        // image out of the cache and off the card.
        val generation = cacheGeneration
        val deliver = { bmp: Bitmap? ->
            main.post {
                try {
                    done(bmp?.takeIf { generation == cacheGeneration })
                } finally {
                    pendingImageLoads.decrementAndGet()
                }
            }
        }
        if (cacheable) {
            IMAGE_CACHE.get(fullUrl)?.takeIf { !it.isRecycled }?.let { hit ->
                deliver(hit)
                return
            }
        }
        IMAGE_POOL.execute {
            val bmp = runCatching {
                val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 8_000
                    if (!public) haToken(fullUrl)?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
                try {
                    // Bounded before decoding: a huge asset (with or without a Content-Length) fails, not the heap.
                    if (conn.responseCode in 200..299) decodeDownsampled(conn.inputStream.use { VitroIslandServer.readCapped(it, MAX_IMAGE_BYTES, expected = conn.contentLength.toLong()) }) else null
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (bmp != null && cacheable) {
                synchronized(IMAGE_CACHE) { if (generation == cacheGeneration) IMAGE_CACHE.put(fullUrl, bmp) }
            }
            deliver(bmp)
        }
    }

    /**
     * Loads an SVG image (weather-card's icons, from its `icons:` folder or its CDN, as the browser would) and parses it
     * with [dev.glassworks.engine.vitro.cards.MdSvg]'s subset, delivering on the main thread: null when it can't be
     * fetched, is over 512 KB or isn't an SVG Vitro reads. Parsed images are kept, so a dashboard reads each once.
     * Only Home Assistant's own origin gets the panel's token.
     */
    fun loadSvg(rawUrl: String, done: (dev.glassworks.engine.vitro.cards.MdSvg?) -> Unit) {
        val fullUrl = absoluteUrl(rawUrl)
        synchronized(SVG_CACHE) { SVG_CACHE[fullUrl] }?.let { hit -> done(hit); return }
        pendingImageLoads.incrementAndGet()
        // A clear while it's in flight (another sign-in) keeps its image out of the cache and off the card.
        val generation = cacheGeneration
        IMAGE_POOL.execute {
            val svg = runCatching {
                val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 8_000
                    haToken(fullUrl)?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
                try {
                    if (conn.responseCode in 200..299) {
                        dev.glassworks.engine.vitro.cards.MdSvg.parse(String(conn.inputStream.use { VitroIslandServer.readCapped(it, MAX_SVG_BYTES) }, Charsets.UTF_8))
                    } else {
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (svg != null) synchronized(SVG_CACHE) { if (generation == cacheGeneration) SVG_CACHE[fullUrl] = svg }
            main.post { try { done(svg?.takeIf { generation == cacheGeneration }) } finally { pendingImageLoads.decrementAndGet() } }
        }
    }

    /** An embedded picture: the decoded [bitmap] (maybe sampled down) and the image's own [width] and [height]. */
    class Embedded(val bitmap: Bitmap, val width: Int, val height: Int)

    /**
     * Decodes an embedded raster (`data:image/…;base64,…`) off the main thread and delivers it there, or null when it
     * isn't one, is too big to read ([MAX_EMBEDDED_CHARS]) or doesn't decode. It is sampled down to at most
     * [MAX_EMBEDDED_PIXELS], alpha kept (a small icon may carry a large source).
     */
    fun decodeEmbedded(dataUri: String, done: (Embedded?) -> Unit) {
        pendingImageLoads.incrementAndGet()
        IMAGE_POOL.execute {
            val out = runCatching { decodeEmbeddedRaster(dataUri) }.getOrNull()
            main.post { try { done(out) } finally { pendingImageLoads.decrementAndGet() } }
        }
    }

    /**
     * `GET`s an HA REST path (e.g. `/api/calendars/calendar.home?start=…&end=…`) with the panel's bearer token on
     * the shared daemon pool and delivers the parsed [JSONObject] / [JSONArray] on the main thread, or null on any
     * failure (network, non-2xx, bad JSON).
     */
    fun fetchJson(path: String, done: (Any?) -> Unit) {
        val fullUrl = absoluteUrl(path)
        IMAGE_POOL.execute {
            val parsed = runCatching {
                val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 8_000
                    setRequestProperty("Accept", "application/json")
                    haToken(fullUrl)?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.use { VitroIslandServer.readCapped(it, MAX_JSON_BYTES) }
                        JSONTokener(String(body, Charsets.UTF_8)).nextValue()
                    } else {
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            main.post { done(parsed) }
        }
    }

    /** The panel's token for [url] on Home Assistant's own origin; an image on any other host gets no credentials. */
    private fun haToken(url: String): String? {
        if (!sameOrigin(url, haBase)) return null
        return accessToken()?.takeIf { it.isNotEmpty() }
    }

    private fun absoluteUrl(raw: String): String = when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("/") -> haBase.trimEnd('/') + raw
        else -> haBase.trimEnd('/') + "/" + raw
    }

    companion object {
        /**
         * [loadImage] calls whose bitmap hasn't reached its card yet. A testability hook: the parity harness waits
         * for zero before it draws (#793), so a late image can't leave a card half drawn.
         */
        internal val pendingImageLoads = java.util.concurrent.atomic.AtomicInteger()

        /**
         * The domains [toggleEntity] switches with their own `toggle` service once a `toggle` action runs. Only which
         * service a toggle calls: whether a tap toggles by default is HA's [ActionConfig.DOMAINS_TOGGLE].
         */
        val TOGGLE_DOMAINS = setOf(
            "light", "switch", "fan", "input_boolean", "cover", "lock",
            "automation", "script", "humidifier", "siren", "valve",
        )

        /** SVGs [loadSvg] parsed, by address (a few weather icons). */
        private val SVG_CACHE = object : LinkedHashMap<String, dev.glassworks.engine.vitro.cards.MdSvg>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, dev.glassworks.engine.vitro.cards.MdSvg>?) = size > 32
        }

        /** The largest SVG [loadSvg] reads. */
        private const val MAX_SVG_BYTES = 512 shl 10

        /** The largest encoded image [loadImage] reads; past it the load fails (null). */
        private const val MAX_IMAGE_BYTES = 12 shl 20

        /** The largest REST answer [fetchJson] reads (a calendar's events, statistics); past it, null. */
        private const val MAX_JSON_BYTES = 4 shl 20

        private val IMAGE_POOL = Executors.newFixedThreadPool(3) { r -> Thread(r, "vitro-img").apply { isDaemon = true } }

        /** Long-edge cap in px: plenty for a 1440x720 panel, 4x-8x less memory than full-size ARGB_8888. */
        private const val MAX_IMAGE_DIM = 720
        private const val IMAGE_CACHE_KB = 12 * 1024
        private val LIVE_PARAM = Regex("[?&]t=")

        private val IMAGE_CACHE = object : LruCache<String, Bitmap>(IMAGE_CACHE_KB) {
            override fun sizeOf(key: String, value: Bitmap): Int = max(1, value.allocationByteCount / 1024)
        }

        /** Drops every cached image: they were fetched with a sign-in (or a Home Assistant) that no longer holds. */
        fun clearImageCache() = synchronized(IMAGE_CACHE) {
            // A load already on its way under the old sign-in doesn't put its image back afterwards.
            cacheGeneration++
            IMAGE_CACHE.evictAll()
            synchronized(SVG_CACHE) { SVG_CACHE.clear() }
        }

        @Volatile private var cacheGeneration = 0

        /** The optimistic writes still waiting for HA, per store and entity ([callOptimistic]); main thread only. */
        private class PendingWrites(var confirmed: EntityState) {
            var latest: EntityState? = null
            var outstanding = 0

            /** Writes issued so far (each gets the next number), and the newest one [confirmed] reflects. */
            var issued = 0
            var confirmedSeq = 0
        }

        private val optimisticWrites = java.util.WeakHashMap<EntityStore, HashMap<String, PendingWrites>>()

        /** Who the cached images were fetched as: the sign-in ([tokenSignIn]) of the last token used. */
        private var imageSignIn: String? = null

        /**
         * The panel is about to use [token]. When it belongs to another sign-in than the last one (another account on
         * the same Home Assistant, or the same one signed in again), images fetched with the old one go. A routine
         * refresh of the same sign-in keeps them.
         */
        fun noteToken(token: String) = synchronized(IMAGE_CACHE) {
            val who = tokenSignIn(token) ?: return@synchronized
            if (imageSignIn != null && imageSignIn != who) clearImageCache()
            imageSignIn = who
        }

        /**
         * The sign-in a Home Assistant access token belongs to: its JWT `iss`, the refresh token it was issued from
         * (the same across refreshes, new on every sign-in). Null when it isn't a JWT HA issued.
         */
        internal fun tokenSignIn(token: String): String? = runCatching {
            val parts = token.split('.')
            if (parts.size != 3) return null
            val payload = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            JSONObject(String(payload, Charsets.UTF_8)).optString("iss").ifEmpty { null }
        }.getOrNull()

        /**
         * True when [a] and [b] are the same origin: scheme, host and effective port (RFC 6454), so
         * `https://homeassistant.local` and `https://homeassistant.local:443` match and other ports don't.
         */
        fun sameOrigin(a: String?, b: String?): Boolean {
            val x = canonicalOrigin(a) ?: return false
            return x == canonicalOrigin(b)
        }

        private fun canonicalOrigin(url: String?): String? {
            val origin = url?.let(OAuth::origin) ?: return null
            return when {
                origin.startsWith("https://") && origin.endsWith(":443") -> origin.removeSuffix(":443")
                origin.startsWith("http://") && origin.endsWith(":80") -> origin.removeSuffix(":80")
                else -> origin
            }
        }

        /** The longest embedded image text read (about 6 MiB of image). */
        private const val MAX_EMBEDDED_CHARS = 8 shl 20

        /** Decoded pixels an embedded image may take (4 MiB as ARGB). */
        private const val MAX_EMBEDDED_PIXELS = 1L shl 20

        internal fun decodeEmbeddedRaster(dataUri: String): Embedded? {
            val payload = dataUri.substringAfter(',', "")
            if (payload.isEmpty() || payload.length > MAX_EMBEDDED_CHARS) return null
            val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while ((w / sample).toLong() * (h / sample) > MAX_EMBEDDED_PIXELS) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            return Embedded(bmp, w, h)
        }

        private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = max(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            var sample = 1
            while (longest / sample > MAX_IMAGE_DIM) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }
}

/**
 * Base rounded surface for every native Lovelace card, styled directly from [ResolvedTheme]
 * (including `ha-card-background`, border radius/width/color, and container `card-mod` properties).
 */
open class CardSurface(protected val ctx: VitroCardContext) : FrameLayout(ctx.context) {
    /**
     * A store or template subscription: [subscribe] makes it again, [catchUp] redraws from what changed meanwhile.
     * One made before the card is attached (its setup) is [renewable]: a cached view that leaves the window and comes
     * back takes it up again. One made while attached (in `onAttachedToWindow`) is the card's own to make again.
     */
    private class Subscription(val subscribe: () -> (() -> Unit), val renewable: Boolean, val catchUp: (() -> Unit)?) {
        var handle: (() -> Unit)? = null

        /** A render is queued for it ([requestRender]). Guarded by [renderLock]. */
        var queued = false

        /** It changed while its view was hidden ([setViewHidden]): it renders when the view shows. Guarded by [renderLock]. */
        var stale = false
    }

    private val subscriptions = ArrayList<Subscription>()

    /**
     * The store's changes waiting for one render pass ([renderQueued]): a snapshot or a burst of changes renders each
     * changed [observe] once, in one main-thread task per card rather than one per change. Guarded by [renderLock],
     * since the store tells its listeners on the socket's thread.
     */
    private val renderLock = Any()
    private val renderQueue = ArrayList<Subscription>()
    private var renderPosted = false
    private val renderQueued = Runnable { renderQueued() }

    /**
     * True while the card sits in a cached view that isn't shown ([setViewHidden]): its [observe] renders wait, and the
     * view shows the current state again as it's shown. Written on the main thread, read on the socket's.
     */
    @Volatile private var viewHidden = false

    /** Renders run so far (tests): one per changed [observe] and pass, and one per catch-up. */
    internal var rendersRun = 0
        private set

    /** Detached since its subscriptions were last made: the next attach makes the renewable ones again. */
    private var subscriptionsDropped = false

    /** The shadow the card has without a card_mod `box-shadow`: the theme's, or none once it shares a surface. */
    private var baselineElevationDp = 0f

    /** True once the card shares a parent's surface (stack-in-card): no shadow of its own, even from card_mod. */
    private var shadowless = false

    // The padding cards and card_mod set (CSS padding), and the border drawn around it: like `ha-card`'s
    // `border: 1px solid`, the content sits inside the border, so a card's height is its content plus both borders.
    // Read before the constructor finishes (View may set padding), hence nullable.
    private var cssPadding: IntArray? = null
    private var borderInsetPx = 0

    /** The border's exact width in device px (1.5 for a 1 px border at density 1.5), which [setPadding] splits. */
    private var borderInsetF = 0f

    /** The drawn border's exact width in device px, as [drawnBorderPx] is the drawn one. */
    private var drawnBorderF = 0f

    /** The border the card drew before card_mod first restyled the surface ([setBorderInset] derives the inset). */
    private var baseBorderWidth = 0
    private var baseBorderWidthF = 0f

    /**
     * Whether the card's surface asks to clip its content to its rounded outline (a fill or a border). The view clips
     * only while some of its content reaches the corners: [updateOutlineClip] decides as the card records
     * ([ScrollPath.contentClip]).
     */
    // Declared before init, which sets it (applyTheme): an initializer after it would reset the field.
    private var outlineClip: Boolean = false
        set(v) {
            field = v
            // Decided now ([OutlineClip.needsClip]: a surface painted past its rounded fill, a card_mod gradient's, always
            // clips) and again as the card records, when its content is laid out. Only a real change is passed on:
            // setClipToOutline damages the parent every time.
            val clip = v && (!ScrollPath.contentClip || OutlineClip.needsClip(this) != null)
            if (clipToOutline != clip) clipToOutline = clip
        }

    init {
        applyTheme(ctx.theme)
        // Every card's draw goes through [draw], where a throw is caught: a ViewGroup that draws nothing of its own
        // would have Android call [dispatchDraw] alone.
        setWillNotDraw(false)
    }

    // ---- containment: a card that throws shows HA's error card in its place and takes nothing else down

    /** The card's type (`tile`, `custom:bubble-card`), for the log when it fails. The view sets it once it's built. */
    var cardType: String? = null

    /**
     * What the card threw, once it has: from then on it is HA's error card ([showErrorTile]), and its own measure,
     * layout, draw and updates no longer run. Null while it works.
     */
    var failure: Throwable? = null
        private set

    /** The error card is in place of the card's own views (the swap waits for the next message after a [fail]). */
    private var errorTileShown = false

    /**
     * The card threw at [where]: it's logged once ([VitroFailures]) and the card becomes HA's error card. The swap
     * waits for the next main-thread message, outside the measure, layout or draw it failed in; until then the card
     * draws nothing. Only the first failure counts.
     */
    internal fun fail(where: String, error: Throwable) {
        if (failure != null) return
        failure = error
        VitroFailures.report(where, cardType, error)
        ctx.main.post { if (!errorTileShown) runCatching { showErrorTile() } }
    }

    /**
     * Runs [block], the card's own code outside its build (a state update, a template's answer): a throw turns the
     * card into HA's error card instead of reaching the main looper. Nothing runs once the card has failed.
     */
    protected fun guarded(where: String, block: () -> Unit) {
        if (failure != null) return
        try {
            block()
        } catch (e: Exception) {
            fail(where, e)
        }
    }

    /**
     * Replaces the card's views with HA's error card (hui-error-card): its subscriptions end and its listeners go, so
     * nothing of the card's own runs again. The card keeps its slot, its surface and its radius.
     */
    internal fun showErrorTile() {
        errorTileShown = true
        for (s in subscriptions) s.handle?.let { runCatching { it() } }
        subscriptions.clear()
        // What the card runs on its own (a WebSocket stream, a refresh timer) ends too.
        runCatching { onCardFailed() }
        setOnClickListener(null)
        setOnLongClickListener(null)
        setOnTouchListener(null)
        isClickable = false
        isLongClickable = false
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        removeAllViews()
        // hui-error-card's ha-card: `border-width: 0`, the tint filling it edge to edge.
        (background as? GradientDrawable)?.let { (it.mutate() as GradientDrawable).setStroke(0, Color.TRANSPARENT) }
        setBorderInset(0)
        setPadding(0, 0, 0, 0)
        addView(errorCardContent(ctx), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        requestLayout()
        invalidate()
    }

    /** How many times it was measured and laid out (tests: a card placed later lays none of the others out again). */
    internal var measures = 0
        private set
    internal var layouts = 0
        private set

    final override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measures++
        if (failure == null) {
            try {
                measureCard(widthMeasureSpec, heightMeasureSpec)
                return
            } catch (e: Exception) {
                fail("measure", e)
            }
        }
        if (errorTileShown) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        } else {
            setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(ctx.px(ERROR_CARD_MIN_DP), heightMeasureSpec))
        }
    }

    /**
     * The card failed and is HA's error card now: end what it runs outside [observe], [renewing] and [track] (its own
     * WebSocket subscriptions, its timers). Its attach and visibility hooks check [failure] so they don't start them
     * again.
     */
    protected open fun onCardFailed() {}

    /** The card's own measure ([onMeasure] runs it, and shows HA's error card if it throws). */
    protected open fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    final override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        layouts++
        if (failure == null) {
            try {
                layoutCard(changed, left, top, right, bottom)
                return
            } catch (e: Exception) {
                fail("layout", e)
            }
        }
        if (errorTileShown) super.onLayout(changed, left, top, right, bottom)
    }

    /** The card's own layout ([onLayout] runs it, and shows HA's error card if it throws). */
    protected open fun layoutCard(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
    }

    /**
     * Keeps clipToOutline only while the content reaches the rounded corners ([OutlineClip]): most cards keep theirs
     * inside the padding, and a card that clips has every draw inside it clipped to an anti-aliased rounded rect, on
     * every frame it's drawn without a layer. Run as the card records, which every change inside it (a state, a layout,
     * a background) leads to, so the test sees what draws.
     */
    private fun updateOutlineClip() {
        val clip = OutlineClip.needsClip(this) != null
        if (clipToOutline != clip) clipToOutline = clip
    }

    /** The card's whole draw, its children's included ([setWillNotDraw] false sends every draw here). */
    override fun draw(canvas: Canvas) {
        if (failure != null) {
            // The card's own onDraw may be what threw: once it's the error card, only its surface and the error
            // card's views draw, never the card's own code again.
            if (errorTileShown) drawErrorCard(canvas)
            return
        }
        val saved = canvas.saveCount
        try {
            if (outlineClip && ScrollPath.contentClip) updateOutlineClip()
            super.draw(canvas)
        } catch (e: Exception) {
            canvas.restoreToCount(saved)
            fail("draw", e)
        }
    }

    /**
     * A view inside the card changed (Android 8 and later, hardware drawing): the card records itself again when it's
     * the nearest card guard to the change ([isNearestGuard]), so the changed view's draw runs inside [draw]'s catch.
     * Without it Android rebuilds only that view's display list, straight from the card's, and a throw there would
     * reach the app. The card's other views keep their lists: it costs the card's own small list. A failed card
     * records itself again too, whether or not its error card is in yet: its [draw] then runs no view of the card's
     * own, so nothing of it is drawn outside a catch, and the guards above can keep leaving it to this one. A camera's
     * video frame is left alone ([redrawsWithoutCardCode]): it redraws only its TextureView, which runs no card code.
     */
    @TargetApi(Build.VERSION_CODES.O)
    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        if (!redrawsWithoutCardCode(target) && isNearestGuard(this, target)) invalidate()
    }

    /**
     * As [onDescendantInvalidated], on Android 6 and 7, where hardware drawing finds the changed view this way. It
     * doesn't say which view changed, so every card guard above it records itself again, but for a camera's video
     * frame, which its [dev.glassworks.engine.vitro.camera.CameraFitFrame] marks ([VideoRedraw]).
     */
    @Deprecated("ViewGroup's, deprecated in API 26, where onDescendantInvalidated takes over")
    override fun invalidateChildInParent(location: IntArray, dirty: Rect): ViewParent? {
        if (!Platform.hasDescendantInvalidated && isHardwareAccelerated && !VideoRedraw.isVideoFrame(this)) invalidate()
        @Suppress("DEPRECATION")
        return super.invalidateChildInParent(location, dirty)
    }

    /** The error card's draw: the card's surface (its background), then its views, without the card's [onDraw]. */
    private fun drawErrorCard(canvas: Canvas) {
        val saved = canvas.saveCount
        try {
            background?.let { bg ->
                bg.setBounds(0, 0, width, height)
                bg.draw(canvas)
            }
            dispatchDraw(canvas)
        } catch (e: Exception) {
            canvas.restoreToCount(saved)
            VitroFailures.report("draw", ErrorCardView.TYPE, e)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (failure != null && !errorTileShown) return true
        return try {
            super.dispatchTouchEvent(ev)
        } catch (e: Exception) {
            fail("touch", e)
            true
        }
    }

    /**
     * Whether the content sits inside the border, as in most HA cards. The tile card and Mushroom's cards lay their
     * content out from the card's outer edge (their containers fill the whole card, border included), so they say
     * false. Read from the constructor: override it with a constant getter.
     */
    protected open val contentInsideBorder: Boolean get() = true

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        cssPadding = intArrayOf(left, top, right, bottom)
        // A fractional border puts the content at a fractional offset, whose edges Chrome snaps to device pixels:
        // the leading ones round out (1.5 -> 2), the trailing ones in (1), together both borders' width.
        val lead = Math.round(borderInsetF)
        val trail = Math.round(2 * borderInsetF) - lead
        super.setPadding(left + lead, top + lead, right + trail, bottom + trail)
    }

    /**
     * Where the browser's content box starts against this card's, device px: a 1 px border at density 1.5 puts it
     * 1.5 px in, where [setPadding] starts it at 2, so -0.5. Chrome snaps boxes and text there, but composites a
     * canvas (a chart's) where it falls, half a pixel off the grid.
     */
    val contentSubpixel: Float get() = borderInsetF - Math.round(borderInsetF)

    /** Fills the card with [color] from a cascade that knows its content, keeping its radius and border. */
    protected fun fillFromCascade(color: Int) {
        (background as? GradientDrawable)?.let { (it.mutate() as GradientDrawable).setColor(color) }
    }

    /** True once the card's own style dropped its surface ([dropSurface]): a stack keeps it borderless too. */
    private var surfaceDropped = false

    /** No fill and no border (`background: none; border: none`): the content takes the border's place too. */
    protected fun dropSurface() {
        surfaceDropped = true
        background = null
        outlineClip = false
        setBorderInset(0)
    }

    /** The border the card draws now, px (0 without one), whether or not its content sits inside it. */
    protected var drawnBorderPx = 0
        private set

    /** The border width the content sits inside (0 when the card draws no border): [width] drawn, [exact] unrounded. */
    private fun setBorderInset(width: Int, exact: Float = width.toFloat()) {
        drawnBorderPx = width
        drawnBorderF = exact
        val px = if (contentInsideBorder) width else 0
        val f = if (contentInsideBorder) exact else 0f
        if (px == borderInsetPx && f == borderInsetF) return
        borderInsetPx = px
        borderInsetF = f
        val p = cssPadding ?: intArrayOf(0, 0, 0, 0)
        setPadding(p[0], p[1], p[2], p[3])
    }

    fun applyTheme(theme: ResolvedTheme) {
        val strokePx = (theme.cardBorderWidthDp * ctx.dp).toInt()
        val strokeF = theme.cardBorderWidthDp * ctx.dp
        val hasSurface = Color.alpha(theme.cardBackgroundColor) > 0 || strokePx > 0
        if (hasSurface) {
            background = GradientDrawable().apply {
                cornerRadius = theme.cardBorderRadiusDp * ctx.dp
                setColor(theme.cardBackgroundColor)
                if (strokePx > 0) setStroke(strokePx, theme.cardBorderColor)
            }
            outlineClip = true
            setBorderInset(strokePx, strokeF)
        } else {
            background = null
            outlineClip = false
            setBorderInset(0)
        }
        theme.cardMod.opacity?.let { alpha = it }
        baselineElevationDp = theme.cardElevationDp
        elevation = baselineElevationDp * ctx.dp
    }

    /**
     * True once a seamless container (`stack-in-card`) has taken this card's surface off: later `card_mod`
     * renders still set padding and opacity but no longer bring the background or border back.
     */
    var seamless: Boolean = false
        private set

    /**
     * Shares a parent card's surface (stack-in-card): no shadow, and unless [keepBackground], no fill and no rounding,
     * the card keeping only its border ([applySeamlessSurface]).
     */
    fun makeSeamless(keepBackground: Boolean = false, keepRadius: Boolean = false) {
        seamless = !keepBackground
        shadowless = true
        baselineElevationDp = 0f
        elevation = 0f
        if (!keepBackground) {
            applySeamlessSurface(activeCardMod)
        } else if (!keepRadius) {
            (background as? GradientDrawable)?.mutate()?.let { (it as GradientDrawable).cornerRadius = 0f }
        }
        onMadeSeamless(keepBackground, keepRadius)
    }

    /**
     * A seamless container took this card's surface off ([makeSeamless]). A card that draws its own `ha-card` (an
     * island's page) styles it as the container does.
     */
    protected open fun onMadeSeamless(keepBackground: Boolean, keepRadius: Boolean) {}

    /**
     * stack-in-card's style on a card it holds is inline (`box-shadow: none; background: transparent;
     * border-radius: 0px`), so card_mod can't bring those back, but the card keeps its border: the theme's, or the one
     * its card_mod sets (`border: none` takes it off). Square, unfilled, so the stack's cards read as rows of one card.
     * A card whose own style has no border (a text-only Markdown card's `.text-only`, which outranks card_mod's
     * `ha-card`) stays without one.
     */
    private fun applySeamlessSurface(mod: CardModStyle) {
        val strokePx = if (surfaceDropped) 0 else ((mod.borderWidthDp ?: ctx.theme.cardBorderWidthDp) * ctx.dp).toInt()
        val strokeF = if (surfaceDropped) 0f else (mod.borderWidthDp ?: ctx.theme.cardBorderWidthDp) * ctx.dp
        val strokeColor = mod.borderColor ?: ctx.theme.cardBorderColor
        outlineClip = false
        background = if (strokePx > 0 && Color.alpha(strokeColor) > 0) {
            GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(strokePx, strokeColor)
            }
        } else {
            null
        }
        setBorderInset(strokePx, strokeF)
    }

    /** The card's own `card_mod` as last applied (re-rendered live for templated styles). Empty when none. */
    var activeCardMod: CardModStyle = CardModStyle()
        private set

    /**
     * Applies the card's `card_mod.style` (a string, or the `"."` entry of a selector map) natively: gradient or solid
     * fill, border, radius, padding and opacity. A Jinja style (`{{ }}` / `{% %}`) is rendered by HA and re-applied
     * every time it changes; like Mushroom, `entity` is set to the card's entity first when the template uses it.
     */
    fun applyCardMod(config: CardConfig) {
        val rawStyle = ThemeResolver.cardModStyleString(config.raw.optJSONObject("card_mod")) ?: return
        if ("{{" in rawStyle || "{%" in rawStyle) {
            val entity = config.entity
            val tpl = if (entity != null && ENTITY_WORD.containsMatchIn(rawStyle)) {
                "{% set entity = '$entity' %}$rawStyle"
            } else {
                rawStyle
            }
            renewing { ctx.subscribeTemplate(tpl) { rendered -> ctx.main.post { applyStyleCss(rendered) } } }
        } else {
            applyStyleCss(rawStyle)
        }
    }

    /** The card's own `card_mod` CSS as last applied (templates rendered), for cards that read more of it. */
    var activeCardCss: String? = null
        private set

    /** The surface as it was before `card_mod` first restyled it, to go back to when a template drops the rule. */
    private var baseBackground: android.graphics.drawable.Drawable? = null
    private var baseClip = false
    private var basePadding: IntArray? = null
    private var baseChildPadding: IntArray? = null
    private var restyled = false

    /** Parses one rendered `card_mod` CSS string and restyles the surface from it. */
    fun applyStyleCss(css: String) {
        val theme = ctx.theme
        val mod = ThemeResolver.parseCardMod(css, theme.vars)
        val previous = activeCardMod
        activeCardMod = mod
        activeCardCss = css
        val radiusPx = (mod.borderRadiusDp ?: theme.cardBorderRadiusDp) * ctx.dp
        val strokePx = ((mod.borderWidthDp ?: theme.cardBorderWidthDp) * ctx.dp).toInt()
        val strokeF = (mod.borderWidthDp ?: theme.cardBorderWidthDp) * ctx.dp
        val strokeColor = mod.borderColor ?: theme.cardBorderColor
        val gradient = mod.backgroundGradient
        val layers = mod.backgroundLayers
        val restyle = gradient != null || layers != null || mod.backgroundColor != null || mod.borderColor != null ||
            mod.borderWidthDp != null || mod.borderRadiusDp != null
        if (restyle && !seamless && !restyled) {
            baseBackground = background
            baseClip = outlineClip
            // The drawn width, not the inset: a card laid out from its outer edge has a border but no inset.
            baseBorderWidth = drawnBorderPx
            baseBorderWidthF = drawnBorderF
            restyled = true
        } else if (!restyle && restyled) {
            // A template that no longer styles the surface: back to the card's own, border inset included (the
            // content sits inside the theme border again).
            background = baseBackground
            outlineClip = baseClip
            setBorderInset(baseBorderWidth, baseBorderWidthF)
            restyled = false
        }
        if (restyle && !seamless) {
            val fillColor = mod.backgroundColor ?: theme.cardBackgroundColor
            val invisibleSurface = gradient == null && layers == null && Color.alpha(fillColor) == 0 && strokePx == 0
            if (invisibleSurface) {
                // Transparent, borderless card (`background: transparent; border: none;`): drop the rounded
                // outline so Android's clipToOutline never clips text at the card's corners.
                background = null
                outlineClip = false
                setBorderInset(0)
            } else if (layers != null) {
                // Gradients paint like the browser (angle, stop positions, layers), under the card's border.
                background = android.graphics.drawable.LayerDrawable(arrayOf(
                    // The layers lie on the card's resolved fill (a `background-color`, the shorthand's own color, or the
                    // theme's card color under a lone `background-image`).
                    CssBackgroundDrawable(CssBackground(layers.layers, fillColor), ctx.dp, radiusPx),
                    GradientDrawable().apply {
                        cornerRadius = radiusPx
                        setColor(Color.TRANSPARENT)
                        if (strokePx > 0) setStroke(strokePx, strokeColor)
                    },
                ))
                outlineClip = true
            } else {
                background = (if (gradient != null) GradientDrawable(orientationFor(gradient.angleDeg), gradient.colors) else GradientDrawable()).apply {
                    if (gradient == null) setColor(fillColor)
                    cornerRadius = radiusPx
                    if (strokePx > 0) setStroke(strokePx, strokeColor)
                }
                outlineClip = true
            }
            if (!invisibleSurface) setBorderInset(strokePx, strokeF)
        }
        val pad = mod.paddingInsets ?: mod.paddingDp?.let { dev.glassworks.engine.vitro.theme.EdgeInsetsDp(it, it, it, it) }
        if (pad != null) {
            if (basePadding == null) {
                // The CSS padding, not the view's (which adds the border inset; setPadding would add it again).
                basePadding = cssPadding?.copyOf() ?: intArrayOf(0, 0, 0, 0)
                // The padding clearDefaultInnerPadding takes from a sole child, to give back with the outer one.
                baseChildPadding = (getChildAt(0) as? android.view.ViewGroup)?.takeIf { childCount == 1 }
                    ?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
            }
            setPadding(ctx.px(pad.left), ctx.px(pad.top), ctx.px(pad.right), ctx.px(pad.bottom))
            clearDefaultInnerPadding()
        } else {
            basePadding?.let { setPadding(it[0], it[1], it[2], it[3]) }
            baseChildPadding?.let { p ->
                (getChildAt(0) as? android.view.ViewGroup)?.takeIf { childCount == 1 }?.setPadding(p[0], p[1], p[2], p[3])
            }
            basePadding = null
            baseChildPadding = null
        }
        when {
            mod.opacity != null -> alpha = mod.opacity.coerceIn(0f, 1f)
            // The template dropped its `opacity`: back to the theme's card opacity, or opaque.
            previous.opacity != null -> alpha = ctx.theme.cardMod.opacity?.coerceIn(0f, 1f) ?: 1f
        }
        if (seamless) applySeamlessSurface(mod)
        // A templated style re-renders here: when it stops setting `box-shadow`, the theme's shadow comes back.
        if (!shadowless) elevation = (mod.elevationDp ?: baselineElevationDp) * ctx.dp
        onCardModApplied(mod)
    }

    /** Hook for cards that inherit typography or layout cues (such as `font-size`) from `card_mod`. */
    protected open fun onCardModApplied(mod: CardModStyle) {}

    /**
     * Clears the card's default internal padding when `card_mod` supplies an explicit `padding:` so `padding: 0`
     * truly removes padding and `padding: Npx` replaces rather than doubles it. Subclasses with deeper padded
     * containers (such as [dev.glassworks.engine.vitro.cards.StackInCardView]) override this.
     */
    protected open fun clearDefaultInnerPadding() {
        if (childCount == 1) (getChildAt(0) as? android.view.ViewGroup)?.setPadding(0, 0, 0, 0)
    }

    /**
     * Subtle tactile press feedback: scales to 0.975 over 70 ms on touch-down and springs back to 1.0
     * over 120 ms on release/cancel. The touch listener returns false, so clicks still fire normally.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun enablePressFeedback() {
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).setDuration(70L)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120L)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                }
            }
            false
        }
    }

    /** Wires HA's `hold_action` on this card: a long press with haptic feedback. */
    fun bindHoldAction(action: ActionConfig, entityId: String?) {
        bindHoldAction(ctx, action, entityId)
    }

    protected fun observe(entityId: String, onState: (EntityState?) -> Unit) {
        // Subscribed before the first read, so a change between the two isn't missed; each render reads the store
        // then, not the value a dispatch carried, so dispatches posted out of order never leave an older one on screen.
        // A change queues the card's render pass ([requestRender]): every change before it runs renders once.
        lateinit var sub: Subscription
        sub = Subscription(
            subscribe = { ctx.store.subscribe(entityId) { _ -> requestRender(sub) } },
            renewable = !isAttachedToWindow,
            catchUp = { rendersRun++; guarded("update") { onState(ctx.store[entityId]) } },
        )
        add(sub)
        guarded("update") { onState(ctx.store[entityId]) }
    }

    /**
     * [s]'s entity changed (any thread): its render is queued, and the card posts one pass for everything queued
     * before it runs. Hidden in a cached view, nothing is posted: the render waits for the view to show.
     */
    private fun requestRender(s: Subscription) {
        synchronized(renderLock) {
            if (viewHidden) {
                s.stale = true
                return
            }
            if (!s.queued) {
                s.queued = true
                renderQueue += s
            }
            if (renderPosted) return
            renderPosted = true
        }
        ctx.main.post(renderQueued)
    }

    private fun renderQueued() {
        val batch = synchronized(renderLock) {
            renderPosted = false
            val out = renderQueue.toList()
            renderQueue.clear()
            out.forEach { it.queued = false }
            // Hidden since they were queued: they render when the view shows.
            if (viewHidden) out.forEach { it.stale = true }
            if (viewHidden) emptyList() else out
        }
        // One that ended since (the card left the window) catches up when it comes back, not now.
        for (s in batch) if (s.handle != null) s.catchUp?.invoke()
    }

    /**
     * The cached view this card sits in was hidden (true) or shown again (false), by [VitroView]: HA renders only the
     * view on screen, so a hidden view's cards stop rendering live changes, and catch up with the current state as the
     * view shows, before it draws. The subscriptions stay, so nothing is asked of HA again. Main thread.
     */
    internal fun setViewHidden(hidden: Boolean) {
        hiddenByView = hidden
        applyHidden()
    }

    /** [setViewHidden]'s own state, apart from [culled]. Main thread. */
    private var hiddenByView = false

    /**
     * True while the card is outside the band of the view its column draws ([ViewportCull]):
     * attached and shown as far as Android knows, but not drawn. Its live changes wait, as a hidden view's do, and
     * catch up before it's drawn again. Main thread.
     */
    internal var culled = false
        private set

    internal fun setCulled(culled: Boolean) {
        if (this.culled == culled) return
        this.culled = culled
        applyHidden()
        onCulledChanged(culled)
    }

    /** The card stopped being drawn ([culled]) or is drawn again: a card with a timer of its own pauses it. */
    protected open fun onCulledChanged(culled: Boolean) {}

    private fun applyHidden() {
        val hidden = hiddenByView || culled
        if (viewHidden == hidden) return
        val due = synchronized(renderLock) {
            viewHidden = hidden
            if (hidden) return
            subscriptions.filter { it.stale }.onEach { it.stale = false }
        }
        for (s in due) if (s.handle != null) s.catchUp?.invoke()
    }

    /**
     * A subscription [subscribe] makes (a template, a store-wide listener) and whose handle ends it. Made now, ended
     * on detach, and made again when the card comes back (set up before it was attached, like [observe]).
     */
    protected fun renewing(subscribe: () -> (() -> Unit)) {
        add(Subscription(subscribe, renewable = !isAttachedToWindow, catchUp = null))
    }

    /** A handle ended on detach and never made again (the card renews it itself, if at all). */
    protected fun track(unsub: () -> Unit) {
        subscriptions += Subscription({ unsub }, renewable = false, catchUp = null).also { it.handle = unsub }
    }

    private fun add(s: Subscription) {
        s.handle = s.subscribe()
        subscriptions += s
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!subscriptionsDropped) return
        subscriptionsDropped = false
        // A cached card back on screen: what it subscribed to in its setup follows again, from the current state.
        for (s in subscriptions) {
            s.handle = s.subscribe()
            s.catchUp?.invoke()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        subscriptions.forEach { s -> s.handle?.let { runCatching { it() } }; s.handle = null }
        subscriptions.removeAll { !it.renewable }
        subscriptionsDropped = true
        // The next attach catches every one of them up.
        synchronized(renderLock) { subscriptions.forEach { it.stale = false } }
    }

    companion object {
        private const val PRESS_SCALE = 0.975f

        /** hui-error-card's height: 16 px padding above and below a 24 px icon. */
        const val ERROR_CARD_MIN_DP = 56f
        private val ENTITY_WORD = Regex("""\bentity\b""")

        /** CSS gradient angle (0 = to top, clockwise) → the nearest of [GradientDrawable]'s eight orientations. */
        fun orientationFor(angleDeg: Float): GradientDrawable.Orientation {
            val norm = ((angleDeg % 360f) + 360f) % 360f
            return when (Math.round(norm / 45f) % 8) {
                0 -> GradientDrawable.Orientation.BOTTOM_TOP
                1 -> GradientDrawable.Orientation.BL_TR
                2 -> GradientDrawable.Orientation.LEFT_RIGHT
                3 -> GradientDrawable.Orientation.TL_BR
                4 -> GradientDrawable.Orientation.TOP_BOTTOM
                5 -> GradientDrawable.Orientation.TR_BL
                6 -> GradientDrawable.Orientation.RIGHT_LEFT
                else -> GradientDrawable.Orientation.BR_TL
            }
        }

        /** Computes a soft tinted pill/circle color (e.g. 20% alpha of active domain color). */
        fun withAlpha(color: Int, alphaFraction: Float): Int {
            val a = (alphaFraction.coerceIn(0f, 1f) * 255f).toInt()
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }
    }
}

/** Wires HA's `hold_action` on any view: a long press with haptic feedback runs [action]. `none` leaves it off. */
fun View.bindHoldAction(ctx: VitroCardContext, action: ActionConfig, entityId: String?) {
    if (action.action == "none") {
        setOnLongClickListener(null)
        isLongClickable = false
        return
    }
    setOnLongClickListener { v ->
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        ctx.performAction(action, entityId)
        true
    }
}
