/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.data.HaLocale
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import dev.glassworks.engine.Page
import dev.glassworks.engine.vitro.island.IslandStrings
import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.ViewConfig
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.errorCardContent
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs

/**
 * A card drawn in a browser page of its own (a web island, or the iframe card's frame): what VitroView does with its
 * page as views are built, shown and dropped ([WebIslandCardView] has the details).
 */
interface IslandCard {
    /** The card whose page this is: a card whose page keeps dying is given up for by its config (VitroView). */
    val islandConfig: CardConfig

    /** Whether its page is one of [pages]. */
    fun holds(pages: Set<Page>): Boolean

    /** Shows or hides its page ([Page.setActive]). Main thread. */
    fun setShown(shown: Boolean)

    /** Gives its page and slot up for the next build of the same card, or null when it has none. */
    fun handOff(): WebIslandCardView.Island?

    /** Closes its page ([Page.close]) and slot for good. Main thread; once. */
    fun release()
}

/**
 * What an island page reports when it can't make its card ([WebIslandCardView]'s `cardError`): HA's own message, as
 * its error card holds it ("Custom element doesn't exist: acme-card.", or what the card's `setConfig` threw).
 */
class IslandCardError(message: String) : IllegalStateException(message)

/**
 * Tier 3 Custom Card Escape Hatch ("GeckoView Micro-Web Island").
 *
 * Uses zero `android.webkit.WebView`: every Tier 3 island runs through Glassworks' wrapped
 * `Engine` (GeckoView) pointed at Vitro's loopback [VitroIslandServer], keeping CSS/JS rendering
 * 100% consistent across Android 6 (Mirror), Android 10 (S1 Plus), and Android 15+ while skipping
 * Home Assistant's 10+ MB frontend SPA.
 *
 * Communicates over a loopback WebSocket (`/__vitro/ws/<slotId>`) with a dependency-tracking
 * `hass.states` Proxy, `hass.callWS`, `hass.callService`, `hass.config`, and theme `:root` vars.
 */
class WebIslandCardView(
    ctx: VitroCardContext,
    /** The card the island draws: its config, card_mod included, is what the island page receives. */
    internal val config: CardConfig,
    private val fillViewport: Boolean = false,
    /**
     * The island of an earlier build of this very card ([keyOf] equal), which [handOff] gave up: this card takes its
     * page and slot instead of opening a new page, and restyles it to its own theme. Null opens a new page.
     */
    adopted: Island? = null,
) : CardSurface(ctx), IslandCard {

    override val islandConfig: CardConfig get() = config

    /**
     * An island's page and its slot on the island server, between a card that let it go ([handOff]) and the next
     * build of that card, which takes it. [close] ends it when no build does.
     */
    class Island internal constructor(
        val key: String,
        internal val page: Page,
        internal val slotId: String,
        /** The island's height when it was handed off (it reports a new one only when its content resizes). */
        internal val height: Int,
        private val server: VitroIslandServer,
        /** The page walked `hass.states` and asked for the registries: it asks once, so the next card keeps them up. */
        internal val walksAllStates: Boolean = false,
        internal val registriesAsked: Boolean = false,
        /** Whether the page was active when handed off: a paused one (a hidden view's) is shown again when needed. */
        internal val active: Boolean = true,
        /**
         * The island's address, and whether its document ever ran (its bridge connected). One that hadn't may have got
         * the parked slot's empty page meanwhile: the card that takes it loads [url] again.
         */
        internal val url: String = "",
        internal val loaded: Boolean = true,
    ) {
        fun close() {
            server.unregisterSlot(slotId)
            page.close()
        }
    }

    private val watchedEntities = CopyOnWriteArraySet<String>()
    private var slotId: String? = null
    private var islandPage: Page? = null

    /** The island's address on the island server, and whether its document ever ran there (a bridge connected). */
    private var islandUrl = ""
    @Volatile private var documentRan = false

    /** True once the island left this card for good ([release]) or for its next build ([handOff]). */
    private var islandGone = false

    /** Whether the island's page is active ([setShown]); a new page starts active. */
    private var pageActive = true

    /** A release queued by a detach, which an attach before it runs takes back (a layout that moves its cards). */
    private val releaseIfDetached = Runnable { if (!isAttachedToWindow) release() }

    /**
     * HA's error card, shown over the page while the card's script is late ([showWaitingError]); null otherwise. Main
     * thread.
     */
    private var waitingError: android.view.View? = null
    /** What the page said is missing while it waits, for the error it becomes if the script never comes. */
    private var waitingMessage = ""
    /** The wait for a late script ends: the card is HA's error card for good and its page closes. */
    private val giveUpWaiting = Runnable {
        if (waitingError == null || islandGone) return@Runnable
        waitingError = null
        fail("island", IslandCardError(waitingMessage))
    }
    /** The senders of the bridge sockets open for this island (a replacement can connect before the old one closes). */
    private val senders = CopyOnWriteArraySet<(JSONObject) -> Unit>()

    /**
     * Orders a connection's snapshot before any patch for it. Declared before `init`: the bridge may connect while
     * the constructor still runs (init registers the slot and loads the page).
     */
    private val seedLock = Any()

    /**
     * Each open bridge's subscriptions, by the page's id for them, and what each bridge was told of the connection
     * last (`conn`). Under [seedLock]. Declared before `init` too: a page handed over runs already, and its bridge can
     * speak as soon as the slot answers to this card.
     */
    private val pageSubs = HashMap<(JSONObject) -> Unit, HashMap<Int, PageSub>>()
    private val sentConnected = HashMap<(JSONObject) -> Unit, Boolean>()

    /**
     * Sockets seeded before HA had answered for this card's entities: they're seeded again once it has. Under
     * [seedLock], and declared before `init` for the same reason as [pageSubs].
     */
    private val incompleteSeeds = LinkedHashSet<(JSONObject) -> Unit>()

    /** The connection watcher's handle: on for as long as the island is this card's, a move in the layout included. */
    @Volatile private var connectionWatch: (() -> Unit)? = null

    /** The config watcher's handle ([VitroCardContext.watchConfig]), with the same lifetime. */
    @Volatile private var configWatch: (() -> Unit)? = null

    /** Under [seedLock]: sockets seeded with no config before [configWatch] was on, and whether it has sent one since. */
    private val seededWithoutConfig = HashSet<(JSONObject) -> Unit>()
    private var configSent = false

    /**
     * Whether the card picks its entities at runtime (`auto-entities` filters, `button-card` JS templates, a card
     * that names none up front): such a card follows every entity in the store, not only the ones it read.
     */
    internal val followsAllEntities: Boolean by lazy {
        config.raw.has("filter") || referencedEntities().isEmpty()
    }

    /** The card walked `hass.states` (`watchAll`): it follows every entity from then on, as [followsAllEntities]. */
    @Volatile private var walksAllStates = false

    /** The page said once that the card needs HA's frontend (`needsFrontend`): later reports change nothing. */
    @Volatile private var needsFrontendSent = false

    /** The card read `hass.entities`, `devices`, `areas` or `floors`: the shared registry feed, until detached. */
    private val registriesAsked = java.util.concurrent.atomic.AtomicBoolean(false)
    private val registriesRelease = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
    @Volatile private var detached = false

    /** The last registries the feed gave (under [seedLock]): a socket that connects later gets them after its snapshot. */
    private var registries: JSONObject? = null

    // An inline island sits in Vitro's vertical scroller: a vertical drag belongs to the scroller, while taps and
    // horizontal drags (sliders, carousels, charts) stay inside the island.
    private val touchSlop = ViewConfiguration.get(ctx.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var gestureAxis = AXIS_NONE

    init {
        if (fillViewport) dropSurface()

        val server = ctx.islandServer
        val opener = ctx.openIslandPage
        if (server != null && opener != null) {
            lateinit var idRef: String
            val handler = object : VitroIslandServer.SlotHandler {
                override val html: String
                    get() = buildIslandHtml(ctx, config, fillViewport, slotId = idRef, base = server.capPath)

                override fun onConnected(send: (JSONObject) -> Unit) {
                    // The snapshot goes first: no patch for this socket can be queued before it (the observer
                    // below takes the same lock). This card's theme before it: a page an earlier build of the card
                    // handed over was drawn in that build's.
                    documentRan = true
                    synchronized(seedLock) {
                        send(themeMessage())
                        send(authMessage(currentToken(), currentUser()))
                        // Where the connection is now: a page kept across a hand-over may still think it's down.
                        val up = ctx.haConnected()
                        sentConnected[send] = up
                        send(connMessage(up, ctx.haConnectionEpoch()))
                        // HA's config as the panel has it now: a page made before it arrived has none.
                        val config = ctx.haConfig().takeIf { it.length() > 0 }
                        if (config != null) send(configMessage(config)) else if (configWatch == null) seededWithoutConfig += send
                        seedInitialStates(send)
                        registries?.let { send(registriesMessage(it)) }
                        senders += send
                    }
                }

                override fun onMessage(msg: JSONObject, send: (JSONObject) -> Unit) {
                    handleIslandMessage(msg, send)
                }

                override fun onDisconnected(send: (JSONObject) -> Unit) {
                    // This socket's sender only: the others open for the slot keep getting patches. Its page's
                    // subscriptions end on HA's side too (the page asks for them again on its next socket).
                    val ended = synchronized(seedLock) {
                        senders -= send
                        sentConnected -= send
                        pageSubs.remove(send)?.values?.toList().orEmpty()
                    }
                    ended.forEach { it.cancel() }
                    synchronized(seedLock) { incompleteSeeds -= send }
                }
            }
            // The earlier build's page, if its slot is still there: the slot now answers to this card.
            val kept = adopted?.takeIf { server.replaceSlot(it.slotId, handler) }
            if (adopted != null && kept == null) adopted.close()
            val (id, url) = if (kept != null) kept.slotId to kept.url else server.registerSlot(handler)
            idRef = id
            slotId = id
            islandUrl = url
            // A page whose document ran keeps it; one handed over before that loads the island now (it may hold the
            // parked slot's empty page).
            if (kept != null) documentRan = kept.loaded

            val page = kept?.page ?: opener(url)
            if (page != null) {
                islandPage = page
                val h = if (fillViewport) LayoutParams.MATCH_PARENT else kept?.height ?: ctx.px(160f)
                addView(page.view, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, h))
                if (kept == null || (!kept.loaded && url.isNotEmpty())) page.load(url)
                if (kept != null) {
                    // Paused under the card before this one: setShown(true) shows it again.
                    pageActive = kept.active
                    // The page asked for these once, from the card before this one.
                    if (kept.walksAllStates) {
                        walksAllStates = true
                        ctx.watchAllEntities()
                    }
                    if (kept.registriesAsked) askRegistries()
                }

                // `send` only queues (the island server writes on the socket's own thread): this runs on HA's event
                // thread under the store's write lock, where a write to a browser that stopped reading would block.
                // Renewed on attach: a layout that moves the card detaches and attaches it, and the island stays.
                renewing {
                    ctx.store.subscribeAny { changed ->
                        synchronized(seedLock) {
                            if (senders.isEmpty()) return@subscribeAny
                            // A token that changed and wasn't sent yet goes before the patch (belt and braces: the
                            // token watcher below sends it as it arrives).
                            sendTokenIfChanged()
                            patchFor(changed)?.let { patch -> senders.forEach { it(patch) } }
                        }
                    }
                }
                // A new token (a reconnect refreshed it) goes to the page as it arrives, with no state change needed: a
                // reconnect that changed no state sends no patch.
                renewing { ctx.watchToken { synchronized(seedLock) { sendTokenIfChanged() } } }
                // HA's answer for more entities landed: a page seeded before it gets the whole store, complete.
                renewing {
                    val off = ctx.store.subscribeSnapshot { synchronized(seedLock) { completeSeeds() } }
                    // A snapshot listener hears only later answers: one that landed before it (a bridge seeded while
                    // the card was still being built, or while it was detached) is checked for here.
                    synchronized(seedLock) { completeSeeds() }
                    off
                }
                // The connection to HA went down or is back: the page's `hass.connection` hears of it, and its
                // subscriptions start again on the new connection (HA dropped them with the old one). On while the
                // card is detached too (a move in the layout): a drop then is never missed, and live ones stay live.
                connectionWatch = ctx.watchConnection { up -> synchronized(seedLock) { connectionChanged(up) } }
                // A change between the bridge's seed and the watcher above went unheard: where it is now. Idempotent:
                // live subscriptions stay, waiting ones start, and a bridge told this already hears nothing.
                synchronized(seedLock) { connectionChanged(ctx.haConnected()) }
                configWatch = ctx.watchConfig { config ->
                    synchronized(seedLock) {
                        val msg = configMessage(config)
                        senders.forEach { it(msg) }
                        configSent = true
                    }
                }
                // HA's config that arrived between a bridge's seed (which had none) and the watcher above went unheard:
                // it goes now. A page told it already gets nothing more.
                synchronized(seedLock) {
                    if (!configSent) ctx.haConfig().takeIf { it.length() > 0 }?.let { config ->
                        val msg = configMessage(config)
                        senders.filter { it in seededWithoutConfig }.forEach { it(msg) }
                    }
                    seededWithoutConfig.clear()
                }
            } else {
                server.unregisterSlot(id)
                slotId = null
                showPlaceholder()
            }
        } else {
            showPlaceholder()
        }
    }

    private fun showPlaceholder() {
        val note = TextView(ctx.context).apply {
            text = "Custom card island: ${config.type}"
            setTextColor(ctx.theme.secondaryTextColor)
            setPadding(ctx.px(14f), ctx.px(14f), ctx.px(14f), ctx.px(14f))
        }
        addView(note, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }


    private fun referencedEntities(): Set<String> {
        val syntheticView = ViewConfig(0, "", null, null, "panel", null, null, 1, emptyList(), listOf(config), emptyList())
        return CardRouter.referencedEntities(syntheticView)
    }

    /**
     * The `snapshot` an island gets when its socket connects, empty too: every entity in the store, as HA's own
     * frontend hands every card the whole `hass.states`, so filter and template cards find what they look for. It
     * replaces the island's states, so an entity removed while the bridge was down doesn't linger.
     */
    internal fun seedInitialStates(send: (JSONObject) -> Unit) {
        watchedEntities.addAll(referencedEntities())
        val seed = JSONObject()
        for ((id, state) in ctx.store.all()) seed.put(id, state.toHassJson())
        val complete = statesComplete()
        // Not HA's answer yet (the store fills as HA's snapshot lands): the page waits to make its card, and this
        // socket gets the whole store again once it is ([completeSeeds]).
        if (!complete) incompleteSeeds += send
        send(JSONObject().put("type", "snapshot").put("states", seed).put("complete", complete))
    }

    /**
     * True when HA has answered for every entity the card names (a state, or HA's word that it has none); for a card
     * that names none (a filter, a clock), once HA's answer is in at all. The snapshot the card may be made with, as HA's
     * frontend makes cards once `hass.states` is in.
     */
    internal fun statesComplete(): Boolean {
        val refs = referencedEntities()
        return if (refs.isEmpty()) ctx.store.anyAnswered else refs.all(ctx.store::isAnswered)
    }

    /** Under [seedLock]: the sockets still waiting for HA's answer get it once it's in. */
    private fun completeSeeds() {
        if (incompleteSeeds.isEmpty() || !statesComplete()) return
        val waiting = incompleteSeeds.filter { it in senders }
        incompleteSeeds.clear()
        waiting.forEach { seedInitialStates(it) }
    }

    /**
     * The `patch` for a batch of [changed] entities, or null when none concern this island. A card that follows
     * every entity gets them all (a removed one as `null`); any other card only the ones it watches.
     */
    internal fun patchFor(changed: Set<String>): JSONObject? {
        val patch = JSONObject()
        for (eid in changed) {
            if (!followsAllEntities && !walksAllStates && eid !in watchedEntities) continue
            val state = ctx.store[eid]
            // A removed entity goes as null, for a watched one too, so the card doesn't keep a stale state.
            patch.put(eid, state?.toHassJson() ?: JSONObject.NULL)
        }
        if (patch.length() == 0) return null
        return JSONObject().put("type", "patch").put("states", patch)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!fillViewport) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    gestureAxis = AXIS_NONE
                }
                MotionEvent.ACTION_MOVE -> if (gestureAxis == AXIS_NONE) {
                    val dx = abs(ev.x - downX)
                    val dy = abs(ev.y - downY)
                    if (dx > touchSlop || dy > touchSlop) {
                        gestureAxis = if (dx > dy) AXIS_HORIZONTAL else AXIS_VERTICAL
                        // Horizontal: keep the scroller out. Vertical: let it take the gesture over.
                        super.requestDisallowInterceptTouchEvent(gestureAxis == AXIS_HORIZONTAL)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gestureAxis = AXIS_NONE
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** The island's engine view may ask to keep every gesture; a vertical drag of an inline island is not its to keep. */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (!fillViewport && disallowIntercept && gestureAxis == AXIS_VERTICAL) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    internal fun handleIslandMessage(msg: JSONObject, send: (JSONObject) -> Unit) {
        when (msg.optString("type")) {
            "watch" -> {
                val id = msg.optString("entity_id", "")
                if (id.isBlank()) return
                // Under the observer's lock: the value read here goes out before any newer patch for it, never after.
                val added = synchronized(seedLock) {
                    if (!watchedEntities.add(id)) return
                    ctx.store[id]?.let { state ->
                        send(JSONObject().put("type", "patch").put("states", JSONObject().put(id, state.toHassJson())))
                    }
                    true
                }
                // Read at runtime, beyond what the config names: the entity subscription takes it in.
                if (added && !followsAllEntities) ctx.watchEntity(id)
            }
            "watchAll" -> {
                // Every change reaches the card now, and its view follows every entity while it shows, so the
                // states it walks are all of HA's, as in HA's frontend.
                walksAllStates = true
                ctx.watchAllEntities()
            }
            "registries" -> askRegistries()
            // The page couldn't make the card (HA's createCardElement would show its error card): this card is HA's
            // error card from now on, natively.
            "cardError" -> {
                val message = msg.optString("message").take(MAX_ERROR_CHARS).ifBlank { "Configuration error" }
                // A script that hasn't defined the card yet may still: HA's error card shows meanwhile, and the page
                // stays for it. Anything else (a setConfig that threw) is that error card for good.
                if (msg.optBoolean("waiting", false)) ctx.main.post { showWaitingError(message) }
                else ctx.main.post { fail("island", IslandCardError(message)) }
            }
            // The late script arrived: the page makes the card, which takes the error card's place.
            "cardDefined" -> ctx.main.post { scriptArrived() }
            // The card draws with elements of HA's frontend this page doesn't have (frontend-watch.js): its view
            // goes to HA's frontend, as a view with a built-in card Vitro doesn't draw does ([VitroCardContext.needsFrontend]).
            "needsFrontend" -> {
                val tags = frontendTags(msg.optJSONArray("tags"))
                if (tags.isEmpty() || needsFrontendSent) return
                needsFrontendSent = true
                // The custom cards that draw them (a stack's inner card, not the stack), as `custom:` types: only
                // ones this island's config holds, so a page can't name a card it doesn't draw.
                val mine = customTypes(config.raw)
                val owners = frontendTags(msg.optJSONArray("cards")).map { "custom:$it" }.filter { it in mine }
                ctx.main.post { ctx.needsFrontend(config, tags, owners) }
            }
            "resize" -> {
                if (fillViewport) return
                val pxH = islandHeightPx(msg.optInt("height", 160))
                ctx.main.post {
                    val v = islandPage?.view ?: return@post
                    if (v.layoutParams?.height != pxH) {
                        v.layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH)
                    }
                }
            }
            "cardSize" -> {
                // The card's getCardSize, which HA's masonry view weighs it by ([IslandCardSizes]).
                val size = msg.optDouble("size", 1.0).takeIf { it.isFinite() && it >= 0 } ?: 1.0
                ctx.main.post { ctx.islandCardSize(config, size) }
            }
            "callService" -> {
                val domain = msg.optString("domain", "")
                val service = msg.optString("service", "")
                if (domain.isNotEmpty() && service.isNotEmpty()) {
                    ctx.callService(domain, service, msg.optJSONObject("data"), null)
                }
            }
            "callWs" -> {
                val callId = msg.optInt("id", -1)
                val inner = msg.optJSONObject("msg") ?: return
                ctx.callWs(inner) { ok, result, error ->
                    val reply = JSONObject().put("type", "wsReply").put("id", callId).put("ok", ok)
                    if (ok) reply.put("result", result ?: JSONObject.NULL)
                    else reply.put("error", error ?: JSONObject().put("message", "callWS failed"))
                    send(reply)
                }
            }
            "subscribe" -> {
                val sid = msg.optInt("sid", -1)
                val inner = msg.optJSONObject("msg") ?: return
                if (sid <= 0 || inner.optString("type").isEmpty()) return
                synchronized(seedLock) {
                    val mine = pageSubs.getOrPut(send) { HashMap() }
                    if (sid in mine) return
                    // One budget for the island, whatever number of bridges its page opens.
                    if (pageSubs.values.sumOf { it.size } >= MAX_PAGE_SUBSCRIPTIONS) {
                        send(subError(sid, JSONObject().put("code", "too_many_subscriptions").put("message", "Too many subscriptions")))
                        return
                    }
                    val sub = PageSub(send, sid, inner, msg.optBoolean("resubscribe", true))
                    mine[sid] = sub
                    startSub(sub)
                }
            }
            "unsubscribe" -> {
                val sid = msg.optInt("sid", -1)
                val sub = synchronized(seedLock) { pageSubs[send]?.remove(sid) } ?: return
                sub.cancel()
            }
            // The card threw on something this page lacks (a `hass` member, an HA element's method) and drew
            // nothing: its view goes to HA's frontend, as for `needsFrontend`, instead of an empty card.
            "cardBroken" -> {
                if (needsFrontendSent) return
                needsFrontendSent = true
                val member = msg.optString("member", "").takeIf { MEMBER_NAME.matches(it) }
                val reason = if (member != null) {
                    "a `${config.type}` card that stopped on `$member`, which an island page doesn't have"
                } else {
                    "a `${config.type}` card that failed in an island page and drew nothing"
                }
                ctx.main.post { ctx.islandFailed(config, reason) }
            }
            // `hass.auth.refreshAccessToken()`: the page gets the sign-in the panel holds now. The panel's own client
            // renews the token (a reconnect, [VitroCardContext.watchToken]); a card can't make it renew one sooner.
            "refreshToken" -> synchronized(seedLock) {
                sendTokenIfChanged()
                // The reply names the request: an `auth` the page gets otherwise (a seed, a new token) settles nothing.
                send(
                    JSONObject().put("type", "auth").put("token", currentToken()).put("user", currentUser() ?: JSONObject.NULL)
                        .put("rid", msg.optInt("rid", 0)),
                )
            }
            "moreInfo" -> {
                val id = msg.optString("entity_id", "")
                if (id.isNotBlank()) ctx.main.post { ctx.openMoreInfo(id) }
            }
            "action" -> {
                // An action with a `confirmation`: the native dialog asks first, as it does for native cards.
                val action = msg.optJSONObject("action") ?: return
                val entity = msg.optString("entity", "").ifEmpty { null }
                ctx.main.post { ctx.performAction(ActionConfig.parse(action), entity) }
            }
            "navigate" -> {
                // A card's `navigate` action / `history.pushState` + `location-changed` inside the island:
                // the path is a dashboard path (`/lovelace/1`), handed to Vitro's own navigation. `replace`: HA's
                // `navigate(path, {replace: true})` (`navigation_replace`), no step in the history.
                // Never with the island server's capability: Home Assistant (and its page's address) never see it.
                val raw = msg.optString("path", "").trim()
                val path = ctx.islandServer?.withoutCap(raw) ?: raw
                val go = if (msg.optBoolean("replace", false)) ctx.navigateReplace else ctx.navigate
                if (path.isNotEmpty() && !path.startsWith("/__vitro/")) ctx.main.post { go(path) }
            }
        }
    }

    /** Whether the island's page is one of [pages]. */
    override fun holds(pages: Set<Page>): Boolean = islandPage?.let { it in pages } == true

    /**
     * Shows or hides the island's page ([Page.setActive]): a page on a view that isn't on screen stops painting.
     * Main thread.
     */
    override fun setShown(shown: Boolean) {
        if (shown == pageActive) return
        pageActive = shown
        islandPage?.setActive(shown)
    }

    /**
     * Gives the island up for the next build of this card (the view is being built again), or null when it has none.
     * The page leaves this card; its slot stays until that build takes it ([Island]) or [Island.close] ends it. Until
     * then the slot answers to no card: what the page sends meanwhile reaches nothing.
     */
    override fun handOff(): Island? {
        if (islandGone) return null
        // A page still waiting for its card's script already told this card so, and won't tell the next build: it
        // closes, and the next build's page waits on its own (its own error card, its own time limit).
        if (waitingError != null) {
            release()
            return null
        }
        val page = islandPage ?: return null
        val id = slotId ?: return null
        val server = ctx.islandServer ?: return null
        islandGone = true
        detached = true
        releaseRegistries()
        endPageSubs()
        server.replaceSlot(id, PARKED)
        val height = page.view.layoutParams?.height ?: ctx.px(160f)
        removeView(page.view)
        senders.clear()
        islandPage = null
        slotId = null
        // What the page asked for once, and won't ask again: the next card keeps it up.
        return Island(
            keyOf(config, fillViewport, ctx.viewType), page, id, height, server, walksAllStates, registriesAsked.get(), pageActive,
            islandUrl, documentRan,
        )
    }

    /** Holds the shared registry feed for the page, once, until the island leaves this card. */
    private fun askRegistries() {
        if (!registriesAsked.compareAndSet(false, true)) return
        val release = ctx.watchRegistries { set ->
            synchronized(seedLock) {
                registries = set
                val msg = registriesMessage(set)
                senders.forEach { it(msg) }
            }
        }
        registriesRelease.set(release)
        // Gone while the feed was being asked for: give it back at once.
        if (detached) releaseRegistries()
    }

    private fun releaseRegistries() {
        registriesRelease.getAndSet(null)?.invoke()
    }

    /** Closes the island for good: its slot goes and its page ([Page.close]) stops. Main thread; once. */
    override fun release() {
        ctx.main.removeCallbacks(releaseIfDetached)
        ctx.main.removeCallbacks(giveUpWaiting)
        if (islandGone) return
        islandGone = true
        detached = true
        releaseRegistries()
        endPageSubs()
        senders.clear()
        slotId?.let { ctx.islandServer?.unregisterSlot(it) }
        slotId = null
        islandPage?.close()
        islandPage = null
    }

    /**
     * The card failed and is HA's error card now: its page closes at once, with its slot and bridges, rather than
     * when the card leaves the view.
     */
    override fun onCardFailed() = release()

    /**
     * The card's script didn't define it in time ([message]: HA's "Custom element doesn't exist"): HA's error card in
     * its place, as HA's createCardElement shows one, while the page keeps waiting. A script that comes within
     * [SCRIPT_WAIT_MS] gives the card back ([scriptArrived]); after that the card is that error card for good and its
     * page closes, so a card whose script never comes (a typo, a resource that failed to load) holds no browser page.
     * Main thread.
     */
    internal fun showWaitingError(message: String) {
        if (failure != null || islandGone || waitingError != null) return
        android.util.Log.i(TAG, "Island ${config.type}: its script hasn't defined it yet; waiting up to ${SCRIPT_WAIT_MS / 1000} s")
        waitingMessage = message
        val error = errorCardContent(ctx)
        waitingError = error
        // The page draws nothing yet: the card's height is the error card's.
        if (!fillViewport) islandPage?.view?.let { it.layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1) }
        addView(error, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        ctx.main.postDelayed(giveUpWaiting, SCRIPT_WAIT_MS)
    }

    /** The late script defined the card: the error card goes, and the page's card takes its place. Main thread. */
    internal fun scriptArrived() {
        val error = waitingError ?: return
        ctx.main.removeCallbacks(giveUpWaiting)
        waitingError = null
        // Until the card reports its size, the page holds the error card's: no jump in the column.
        val h = error.height.takeIf { it > 0 }
        removeView(error)
        if (!fillViewport && h != null) islandPage?.view?.let { it.layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, h) }
    }

    /** Whether HA's error card stands in for the card while its script is late ([showWaitingError]). */
    internal val waitingForScript: Boolean get() = waitingError != null

    override fun onAttachedToWindow() {
        val wasDetached = attachedOnce
        attachedOnce = true
        super.onAttachedToWindow()
        ctx.main.removeCallbacks(releaseIfDetached)
        // Into a card that already has a layer (a child made later in a card of fixed size): off it, or it's blank.
        dev.glassworks.engine.vitro.ui.VitroView.liftLayersAbove(this)
        // Back from a move (the observer was off while detached): the page catches up on what changed meanwhile.
        // The token watcher was off too: a token that arrived meanwhile goes first, then the states.
        if (wasDetached && !islandGone) synchronized(seedLock) {
            sendTokenIfChanged()
            senders.forEach { seedInitialStates(it) }
        }
    }

    /** True once the card was first attached: a later attach is a return. */
    private var attachedOnce = false

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // A card that left the tree is gone, unless it comes back before the next turn of the main loop (a layout
        // that moves its cards between columns detaches and attaches them at once): then it keeps its island.
        if (!islandGone) ctx.main.post(releaseIfDetached)
    }

    /**
     * One `hass.connection.subscribeMessage` of a page ([sid], its own id): the command, whether it starts again after
     * a reconnect (`resubscribe`, true unless the card said otherwise), and its subscription on HA's connection.
     */
    private inner class PageSub(
        val send: (JSONObject) -> Unit,
        val sid: Int,
        val msg: JSONObject,
        val resubscribe: Boolean,
    ) {
        /** The subscription on HA's connection now; null while it waits for one (no connection yet, or it dropped). */
        var handle: (() -> Unit)? = null
        /** Bumped by every start, so an event or error of an earlier one is dropped. Under [seedLock]. */
        var generation = 0
        /** True once HA accepted it and the page was told (`subAck`), which happens once. */
        var acked = false

        /** Whether [generation] is this subscription's latest start and the page still has it. Under [seedLock]. */
        fun current(gen: Int) = gen == generation && pageSubs[send]?.get(sid) === this

        /** Ends its subscription on HA's connection, if it has one: its events and errors are dropped from now on. */
        fun cancel() {
            val h = synchronized(seedLock) { generation++; handle.also { handle = null } }
            h?.let { runCatching { it() } }
        }
    }


    /**
     * Starts [sub] on HA's connection. Its events go to its page as `subEvent`; the page hears `subAck` once HA
     * accepted it, or `subError` when HA refused it. One that can't start now (no connection, a failed write, or a
     * connection that dropped before HA answered) waits for the connection to come back ([connectionChanged]). Under
     * [seedLock].
     */
    private fun startSub(sub: PageSub) {
        val gen = ++sub.generation
        var waits = false
        var refused = false
        val handle = ctx.subscribeWsStarted(
            sub.msg,
            {
                synchronized(seedLock) {
                    if (sub.current(gen) && !sub.acked) {
                        sub.acked = true
                        sub.send(JSONObject().put("type", "subAck").put("sid", sub.sid))
                    }
                }
            },
            { ev ->
                synchronized(seedLock) {
                    if (sub.current(gen)) sub.send(JSONObject().put("type", "subEvent").put("sid", sub.sid).put("event", ev))
                }
            },
            { err ->
                synchronized(seedLock) {
                    if (!sub.current(gen)) return@synchronized
                    val code = err?.optString("code")
                    if (err == null || code == HaDashboardClient.DISCONNECTED || code == HaDashboardClient.SEND_FAILED) {
                        // HA never had it, or lost it with the connection: it starts when the connection is back.
                        sub.handle = null
                        waits = true
                    } else {
                        pageSubs[sub.send]?.remove(sub.sid)
                        sub.handle = null
                        refused = true
                        sub.send(subError(sub.sid, err))
                    }
                }
            },
        )
        if (refused || !sub.current(gen)) return
        if (waits) return
        // Kept while HA hasn't answered too: the page can still end it.
        sub.handle = handle
    }

    /**
     * The connection to HA is [up] or down: the pages hear of it (`conn`). Back up, the subscriptions HA dropped with
     * the old connection start on the new one: every one that waits, and those that resubscribe; the others end, as
     * in HA's own client. Each bridge that wasn't told this yet hears it. Under [seedLock].
     */
    private fun connectionChanged(up: Boolean) {
        val ended = ArrayList<PageSub>()
        for (subs in pageSubs.values) {
            for (sub in subs.values.toList()) {
                if (!up) {
                    // HA drops a connection's subscriptions with it: nothing to cancel on the next one.
                    sub.generation++
                    sub.handle = null
                    continue
                }
                if (sub.handle != null) continue
                if (!sub.acked || sub.resubscribe) {
                    sub.handle?.let { old -> sub.handle = null; runCatching { old() } }
                    startSub(sub)
                } else {
                    subs.remove(sub.sid)
                    ended += sub
                    sub.send(JSONObject().put("type", "subEnd").put("sid", sub.sid))
                }
            }
        }
        ended.forEach { s -> s.handle?.let { runCatching { it() } }; s.handle = null }
        val msg = connMessage(up, ctx.haConnectionEpoch())
        for (send in senders) {
            if (sentConnected[send] == up) continue
            sentConnected[send] = up
            send(msg)
        }
    }


    /** Ends every page subscription of this island (it leaves its card): HA keeps none for a page nobody serves. */
    private fun endPageSubs() {
        connectionWatch?.invoke()
        connectionWatch = null
        configWatch?.invoke()
        configWatch = null
        val all = synchronized(seedLock) {
            sentConnected.clear()
            pageSubs.values.flatMap { it.values }.also { pageSubs.clear() }
        }
        all.forEach { it.cancel() }
    }

    /** The bridge message that restyles the island to this card's theme. */
    private fun themeMessage(): JSONObject = JSONObject()
        .put("type", "theme")
        .put("css", themeCss(ctx, fillViewport))
        .put("dark", ctx.theme.dark)
        .put("name", ctx.theme.name)

    /** The token and the user (`hass.user`, as JSON text) sent to the page last (under [seedLock]). */
    private var sentToken: String? = null
    private var sentUser: String? = null

    private fun currentToken(): String = ctx.accessToken().orEmpty()

    /** The signed-in user as the island's `hass.user` has it, or null while Home Assistant hasn't said. */
    private fun currentUser(): JSONObject? = islandUser(ctx.haUser())

    /**
     * Sends the current sign-in to the open bridges if its token or its user isn't the one sent last: a page kept
     * across a reconnect, or a sign-in to another account, follows it. Under [seedLock].
     */
    private fun sendTokenIfChanged() {
        if (senders.isEmpty()) return
        val token = currentToken()
        val user = currentUser()
        if (token != sentToken || user?.toString() != sentUser) {
            val msg = authMessage(token, user)
            senders.forEach { it(msg) }
        }
    }

    /** The bridge message with the sign-in the page's own requests use, and its user (null: not known yet). */
    private fun authMessage(token: String, user: JSONObject?): JSONObject {
        sentToken = token
        sentUser = user?.toString()
        return JSONObject().put("type", "auth").put("token", token).put("user", user ?: JSONObject.NULL)
    }

    /**
     * The island's height in pixels for a card [cssHeight] CSS px tall. A card that draws nothing (0) collapses to
     * one pixel, where HA gives it no height: its page stays on screen, so it reports again when it draws (a page
     * with no surface might not). A long card (a calendar, a table) grows to [MAX_SCREENS] screens, at least
     * [MIN_CAP_CSS] CSS px: the island is one browser surface, and its memory grows with its height.
     */
    internal fun islandHeightPx(cssHeight: Int): Int {
        if (cssHeight <= 0) return 1
        val screenCss = context.resources.displayMetrics.heightPixels / ctx.dp
        val cap = maxOf(MIN_CAP_CSS.toFloat(), screenCss * MAX_SCREENS).toInt()
        return maxOf(1, ctx.px(cssHeight.coerceAtMost(cap).toFloat()))
    }

    companion object {
        /** The most `hass.connection` subscriptions one page holds on HA's connection at once. */
        const val MAX_PAGE_SUBSCRIPTIONS = 64

        /**
         * How long an island page waits for a card's late script once HA's error card shows ([showWaitingError]): a
         * cold cache on a slow panel takes seconds, not minutes.
         */
        internal const val SCRIPT_WAIT_MS = 120_000L

        private const val TAG = "VitroIsland"

        /** An island grows to this many screens at most ([islandHeightPx]). */
        internal const val MAX_SCREENS = 3

        /** The least an island may grow to, in CSS px, on a short screen ([islandHeightPx]). */
        internal const val MIN_CAP_CSS = 1200

        /** A member name a `cardBroken` report may name in the log: a JavaScript identifier, nothing more. */
        private val MEMBER_NAME = Regex("""^[A-Za-z_$][A-Za-z0-9_$]{0,63}$""")

        /** HA's right-to-left languages (its translationMetadata's `isRTL`). */
        private val RTL_LANGUAGES = setOf("ar", "he", "fa", "ur", "ckb", "yi", "dv", "ps")

        /**
         * `translationMetadata.translations[language]` for an island: the language's own name for itself and whether
         * it reads right to left, as HA's frontend has them.
         */
        internal fun languageMeta(language: String): JSONObject {
            val base = language.substringBefore('-').lowercase(java.util.Locale.ROOT)
            val locale = java.util.Locale.forLanguageTag(language)
            val own = runCatching { locale.getDisplayLanguage(locale) }.getOrNull()
                ?.takeIf { it.isNotBlank() && !it.equals(base, ignoreCase = true) }
                ?.let { name -> name.substring(0, 1).uppercase(locale) + name.substring(1) }
            return JSONObject().put("nativeName", own ?: language).put("isRTL", base in RTL_LANGUAGES)
        }

        /** HA's `hass.panelUrl` for a dashboard at [pageUrl]: its first path segment (`lovelace`, `dashboard-wall`). */
        internal fun panelUrlOf(pageUrl: String?): String =
            pageUrl?.let { runCatching { java.net.URI(it).path }.getOrNull() }
                ?.trim('/')?.substringBefore('/')?.takeIf { it.isNotEmpty() } ?: "lovelace"

        /** The bridge message with Home Assistant's config (`hass.config`). */
        internal fun configMessage(config: JSONObject): JSONObject = JSONObject().put("type", "config").put("config", config)

        /** The bridge message that says whether the connection to Home Assistant is up (`hass.connected`). */
        internal fun connMessage(up: Boolean, epoch: Long = 0L): JSONObject =
            JSONObject().put("type", "conn").put("connected", up).put("epoch", epoch)

        /** The bridge message that tells the page HA refused subscription [sid] (with HA's [error]). */
        internal fun subError(sid: Int, error: JSONObject): JSONObject =
            JSONObject().put("type", "subError").put("sid", sid).put("error", error)

        /** The most of an island's error message kept (it's logged, never shown). */
        const val MAX_ERROR_CHARS = 300

        /** The most tags a `needsFrontend` report keeps: they only name the reason in the log. */
        const val MAX_FRONTEND_TAGS = 12

        /** A custom element's name as the island reports it: lower case, with a hyphen, nothing a log can't hold. */
        private val ELEMENT_TAG = Regex("""^[a-z][a-z0-9]*(?:-[a-z0-9]+)+$""")

        /** The tags of a `needsFrontend` report that are element names, sorted and capped ([MAX_FRONTEND_TAGS]). */
        internal fun frontendTags(raw: org.json.JSONArray?): List<String> {
            if (raw == null) return emptyList()
            return (0 until raw.length()).mapNotNull { raw.opt(it) as? String }
                .filter { it.length <= 64 && ELEMENT_TAG.matches(it) }
                .distinct().sorted().take(MAX_FRONTEND_TAGS)
        }

        /** Every `custom:` card type in [raw], its nested cards' included (a stack's `cards`, a wrapper's `card`). */
        internal fun customTypes(raw: Any?): Set<String> {
            val out = HashSet<String>()
            fun walk(v: Any?) {
                when (v) {
                    is JSONObject -> {
                        v.optString("type").takeIf { it.startsWith("custom:") }?.let { out += it }
                        v.keys().forEach { walk(v.opt(it)) }
                    }
                    is org.json.JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                }
            }
            walk(raw)
            return out
        }

        /**
         * The island page's watch for HA frontend elements a card draws with but the page lacks (`frontend-watch.js`,
         * a resource next to this class's package), read once.
         */
        internal val FRONTEND_WATCH_JS: String by lazy {
            WebIslandCardView::class.java.getResourceAsStream("/dev/glassworks/engine/vitro/island/frontend-watch.js")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("frontend-watch.js is missing from the engine's resources")
        }

        /** HA's formatDuration for the island page's state text (`format-duration.js`, next to `frontend-watch.js`), read once. */
        internal val FORMAT_DURATION_JS: String by lazy {
            WebIslandCardView::class.java.getResourceAsStream("/dev/glassworks/engine/vitro/island/format-duration.js")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("format-duration.js is missing from the engine's resources")
        }

        /**
         * What makes two builds of a card the same island: the config its page was built with ([islandConfig]: the card's
         * own, plus what it reads from HA's page in a view of [viewType]) and whether it fills the view. A rebuild that
         * only changed the theme, or other cards, hands the island over ([handOff]) instead of opening a new page. A
         * Bubble card's page built for a sections view is never handed to a masonry one (Greptile on #799).
         */
        fun keyOf(config: CardConfig, fillViewport: Boolean, viewType: String?): String =
            (if (fillViewport) "fill:" else "card:") + islandConfig(config.raw, viewType)

        /** A handed-off island's slot until the next build takes it: it serves nothing and drops what the page sends. */
        internal val PARKED = object : VitroIslandServer.SlotHandler {
            override val html: String get() = "<!DOCTYPE html>"
            override fun onConnected(send: (JSONObject) -> Unit) {}
            override fun onMessage(msg: JSONObject, send: (JSONObject) -> Unit) {}
            override fun onDisconnected(send: (JSONObject) -> Unit) {}
        }

        /**
         * The island's theme: its variables, and for a full-viewport island the page's background. A full-viewport
         * island is the view: under a card shorter than the screen the theme's background shows, as in HA's panel
         * view, not the white GeckoView draws behind a transparent page. A theme change sends it again. `html:root`
         * outranks the page's own `html, body { background: transparent }`, which comes after this block.
         */
        internal fun themeCss(ctx: VitroCardContext, fillViewport: Boolean): String {
            val vars = ctx.theme.toCssRootBlock()
            if (fillViewport) return vars + "html:root { background-color: ${ctx.theme.hex(ctx.theme.backgroundColor)}; }\n"
            // An inline island on a view of one opaque color paints that color itself: what it shows at its edges
            // (a card's rounded corners and ends) is then drawn by the page, never left half transparent for the
            // panel to blend. Translucent edges came out light on the panel (Bubble Card's slider ends). On a picture
            // or a gradient it stays transparent, so the view's background shows through.
            val solid = (ctx.theme.background as? dev.glassworks.engine.vitro.theme.ThemeBackground.Solid)?.color
            return if (solid != null && (solid ushr 24) == 0xFF) vars + "html:root { background-color: ${ctx.theme.hex(solid)}; }\n" else vars
        }

        /** The bridge message that replaces an island's `hass.entities`, `devices`, `areas` and `floors`. */
        internal fun registriesMessage(set: JSONObject): JSONObject =
            JSONObject().put("type", "registries").put("registries", set)

        private const val AXIS_NONE = 0
        private const val AXIS_HORIZONTAL = 1
        private const val AXIS_VERTICAL = 2
        private val VALID_BASE64 = Regex("""^[A-Za-z0-9+/=\s]+$""")

        /** Decodes a `data:` URI inline Lovelace JS resource into raw module source, or null for normal URLs. */
        /** `</script` in any case ends an HTML script element: an inline module escapes it as `<\/script`. */
        private val SCRIPT_END = Regex("</(script)", RegexOption.IGNORE_CASE)

        fun decodeInlineModule(url: String): String? {
            if (!url.startsWith("data:", ignoreCase = true)) return null
            val comma = url.indexOf(',')
            if (comma < 0) return null
            val meta = url.substring(5, comma).lowercase()
            val payload = url.substring(comma + 1)
            return runCatching {
                if (";base64" in meta) {
                    if (payload.isBlank() || !VALID_BASE64.matches(payload)) return null
                    String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
                } else {
                    // Percent-decoding only: a literal `+` in a data: URI is a plus, not a space.
                    java.net.URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8")
                }
            }.getOrNull()
        }

        /** A double-quoted HTML attribute value. `JSONObject.quote` would escape `/` as `\/` and break `/local/…` URLs. */
        internal fun htmlAttr(s: String): String =
            "\"" + s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;") + "\""

        /**
         * Builds the ~5 KB self-contained Lovelace card Micro-Host HTML for [config].
         * Runs inside GeckoView over [VitroIslandServer] with zero HA SPA overhead.
         */
        /**
         * `hass.user` for an island: the signed-in HA user's `id`, `name`, `is_admin` and `is_owner` (nothing else,
         * so no credentials reach card code), or null while `auth/current_user` hasn't answered, which leaves the
         * island on its anonymous `{ id: 'vitro', name: 'Panel' }` user.
         */
        fun islandUser(user: JSONObject): JSONObject? {
            val id = user.optString("id", "")
            if (id.isEmpty()) return null
            return JSONObject()
                .put("id", id)
                .put("name", user.optString("name", "").ifEmpty { "Panel" })
                .put("is_admin", user.optBoolean("is_admin", false))
                .put("is_owner", user.optBoolean("is_owner", false))
        }

        /**
         * Starts an island's `localStorage` from Home Assistant's (#881). Islands share one loopback origin, which
         * keeps its storage across app starts; [seed] is what HA's web UI holds on this panel ([VitroCardContext.pageStorage]).
         * A key HA's copy changed since the island last took it is taken again, and one HA dropped is dropped unless the
         * island changed it since; whatever else the island holds stays. What the island took last is kept under
         * [STORAGE_BASE_KEY] with HA's origin: for another Home Assistant, its copy wins every key it has, and what the
         * earlier one put in and the island never changed goes.
         *
         * The record is written before any value, so a value HA's copy put in is never there without it: when the
         * record doesn't fit, the island stays as it was and the next start tries again. A value that doesn't fit keeps
         * what the record said before, so it is tried again too; if that record can't be written, the record is kept
         * without the keys that failed (they are taken again next time), and only if even that fails does it go.
         */
        internal const val ISLAND_STORAGE_JS = """
function vitroSeedStorage(store, origin, seed) {
  if (!seed || typeof seed !== 'object') return 0;
  const BASE = '__vitro_ha_storage';
  const own = (o, k) => Object.prototype.hasOwnProperty.call(o, k);
  // What the island took last, from whichever HA (owned), and from this one only (base).
  let owned = {};
  let sameOrigin = false;
  try {
    const b = JSON.parse(store.getItem(BASE) || 'null');
    if (b && b.items && typeof b.items === 'object') { owned = b.items; sameOrigin = b.origin === origin; }
  } catch (e) {}
  const base = sameOrigin ? owned : {};
  const planned = {};
  const writes = [];
  for (const k of Object.keys(seed)) {
    const v = seed[k];
    if (k === BASE || typeof v !== 'string') continue;
    planned[k] = v;
    if (base[k] !== v && store.getItem(k) !== v) writes.push(k);
  }
  // Dropped by HA (or put in by another HA) and unchanged by the island since: it goes.
  const removals = Object.keys(owned).filter((k) => k !== BASE && !own(planned, k) && store.getItem(k) === owned[k]);
  const record = (items) => JSON.stringify({ origin: origin, items: items });
  try { store.setItem(BASE, record(planned)); } catch (e) { return 0; }
  let changed = 0;
  const failed = [];
  for (const k of writes) { try { store.setItem(k, planned[k]); changed++; } catch (e) { failed.push(k); } }
  for (const k of removals) { try { store.removeItem(k); changed++; } catch (e) { failed.push(k); } }
  if (failed.length) {
    // The value the island still holds for a key it couldn't change is the one it took before (or none).
    const took = Object.assign({}, planned);
    for (const k of failed) { if (own(owned, k)) took[k] = owned[k]; else delete took[k]; }
    try { store.setItem(BASE, record(took)); } catch (e) {
      // No room for that one: the record written above, less the keys that failed (shorter, so it fits). A failed
      // key is then taken from HA's copy again at the next start, and what the island changed elsewhere stays its own.
      const kept = Object.assign({}, planned);
      for (const k of failed) delete kept[k];
      try { store.setItem(BASE, record(kept)); } catch (e2) { try { store.removeItem(BASE); } catch (e3) {} }
    }
  }
  return changed;
}
"""

        /** The `localStorage` key under which an island keeps what it last took from HA's copy ([ISLAND_STORAGE_JS]). */
        internal const val STORAGE_BASE_KEY = "__vitro_ha_storage"

        /**
         * The island's first script: [ISLAND_STORAGE_JS] with HA's copy, before any card script runs. Nothing when the
         * panel has no copy for this Home Assistant (the island's own storage stays as it is).
         */
        internal fun storageSeedScript(ctx: VitroCardContext): String {
            val seed = runCatching { ctx.pageStorage() }.getOrNull() ?: return ""
            // `<` escaped: a value can't end the script element or open an HTML comment in it.
            val seedJson = seed.toString().replace("<", "\\u003c")
            val originJson = JSONObject.quote(ctx.haBase).replace("<", "\\u003c")
            return "<script>\n$ISLAND_STORAGE_JS\ntry { vitroSeedStorage(window.localStorage, $originJson, $seedJson); } catch (e) {}\n</script>"
        }

        /**
         * The config an island hands its card: [raw], except for what the card reads from HA's page that an island
         * has no part of. Bubble Card 3.4.3 (`setLayout`) takes `card_layout`, else `large` when HA's page shows a view
         * other than masonry (it looks for `hui-sections-view` and the other views under `hui-root`), else `normal`; and
         * in such a view it drops a `normal` set on a card with bottom sub-buttons. An island has no `hui-root`, so each
         * Bubble card in it, however deep, gets the layout Bubble picks in [viewType] written in (null: outside a view,
         * as Bubble outside HA's page). Tablet Vitro run 3: a slider in a sections view was 52 px in its island, 56 in HA.
         */
        internal fun islandConfig(raw: JSONObject, viewType: String?): JSONObject {
            if (viewType == null || viewType == "masonry" || !raw.toString().contains(BUBBLE_TYPE)) return raw
            fun bottomSubButtons(card: JSONObject): Boolean {
                val sub = card.optJSONObject("sub_button") ?: return false
                val bottom = sub.optJSONArray("bottom") ?: return false
                return (0 until bottom.length()).any { bottom.opt(it).let { b -> b != null && b != JSONObject.NULL && b != false && b != "" && b != 0 } }
            }
            fun walk(v: Any?): Any? = when (v) {
                is JSONObject -> JSONObject().also { out ->
                    for (k in v.keys()) out.put(k, walk(v.get(k)))
                    if (out.optString("type") == BUBBLE_TYPE) {
                        val layout = out.opt("card_layout")
                        if (layout == null || layout == JSONObject.NULL || (layout == "normal" && bottomSubButtons(out))) out.put("card_layout", "large")
                    }
                }
                is org.json.JSONArray -> org.json.JSONArray().also { out -> for (i in 0 until v.length()) out.put(walk(v.get(i))) }
                else -> v
            }
            return walk(raw) as JSONObject
        }

        private const val BUBBLE_TYPE = "custom:bubble-card"

        /**
         * The address the island page loads a Lovelace resource from: one on Home Assistant (a path, `/local/card.js`)
         * under the island server's capability [base], so the browser holds the card at an address that has it and the
         * card's own imports carry it too. A resource on another site, or HA's own full URL, is as written.
         */
        internal fun resourceUrl(url: String, base: String): String =
            if (url.startsWith("/") && !url.startsWith("//")) base + url else url

        /**
         * The island page for [config]. [base]: the island server's capability (`VitroIslandServer.capPath`), which
         * every address the page builds for the server's proxy and bridge starts with ("" in tests without a server).
         */
        fun buildIslandHtml(
            ctx: VitroCardContext,
            config: CardConfig,
            fillViewport: Boolean = false,
            slotId: String = "s0",
            base: String = "",
        ): String {
            val tag = config.type.removePrefix("custom:")
            val themeCss = themeCss(ctx, fillViewport)
            val resourceScripts = ctx.resources.joinToString("\n") { r ->
                val inlineJs = decodeInlineModule(r.url)
                val src = resourceUrl(r.url, base)
                when {
                    inlineJs != null -> "<script type=\"module\">\n${inlineJs.replace(SCRIPT_END, "<\\\\/$1")}\n</script>"
                    r.type == "css" -> """<link rel="stylesheet" href=${htmlAttr(src)}>"""
                    r.type == "module" -> """<script type="module" src=${htmlAttr(src)}></script>"""
                    else -> """<script src=${htmlAttr(src)}></script>"""
                }
            }
            // `/` as it is (JSONObject.quote writes `\/`): the page's own addresses read plainly.
            val baseJson = JSONObject.quote(base).replace("\\/", "/").replace("<", "\\u003c")
            val cfgJson = islandConfig(config.raw, ctx.viewType).toString()
            val haCfgJson = ctx.haConfig().toString()
            val haUserJson = islandUser(ctx.haUser())?.toString() ?: "null"
            // The user's language and formats, as HA's page holds them in `hass.locale` (HaLocale).
            // One read of the locale: its formats and its language (lang, dir) agree.
            val locale = HaLocale.current
            val haLocaleJson = locale.toJson().toString()
            val tokenJson = JSONObject.quote(ctx.accessToken().orEmpty())
            val language = locale.language
            // HA's translationMetadata for the user's language: a right-to-left one lays the page out that way too.
            val langMeta = languageMeta(language)
            val stringsJson = JSONObject.quote(IslandStrings.path())
            val panelJson = JSONObject.quote(panelUrlOf(ctx.pageUrl()))
            val heightRule = if (fillViewport) "height: 100vh; width: 100vw;" else ""
            return """
<!DOCTYPE html>
<html lang=${htmlAttr(language)} dir=${htmlAttr(if (langMeta.optBoolean("isRTL")) "rtl" else "ltr")}>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="same-origin">
${storageSeedScript(ctx)}
<style id="vitro-theme">
$themeCss</style>
<style>
/* The island is the card's whole page, with no HA header above it: a card that takes var(--header-height) off the
   viewport's height fills it, as in HA's kiosk view (the header hidden, its height 0). */
:root { --header-height: 0px; }
html, body { margin: 0; padding: 0; background: transparent; color: var(--primary-text-color); font-family: Inter, system-ui, sans-serif; overflow: hidden; $heightRule }
#mount { width: 100%; ${if (fillViewport) "height: 100vh;" else ""} }
ha-card.vitro-fallback { padding: 12px 16px; }
.vitro-title { font-size: 1.15em; font-weight: 500; padding-bottom: 8px; }
.vitro-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 6px 0; }
.vitro-state { color: var(--secondary-text-color); }
</style>
<script>
// Minimal HA custom-element shims for GeckoView Micro-Islands (zero HA SPA).
// ha-card as HA's is (frontend 20261028.5, ha-card.ts): its look in its own shadow root (:host), so it holds wherever
// the card puts it, in the card's shadow root too, where a rule in this page never reaches (it used to have no
// background, corners or border there). HA's core tokens aren't in this page: their values are the fallbacks.
if (!customElements.get('ha-card')) {
  const HA_CARD_CSS = ':host{background:var(--ha-card-background,var(--card-background-color,white));' +
    'backdrop-filter:var(--ha-card-backdrop-filter,none);box-shadow:var(--ha-card-box-shadow,none);box-sizing:border-box;' +
    'border-radius:var(--ha-card-border-radius,var(--ha-border-radius-lg,12px));border-width:var(--ha-card-border-width,1px);' +
    'border-style:solid;border-color:var(--ha-card-border-color,var(--divider-color,#e0e0e0));color:var(--primary-text-color);' +
    'transition:all .3s ease-out;display:block;position:relative}' +
    ':host([raised]){box-shadow:var(--ha-card-box-shadow,0px 2px 1px -1px #0003, 0px 1px 1px 0px #00000024, 0px 1px 3px 0px #0000001f);border:none}' +
    '.card-header,:host ::slotted(.card-header){color:var(--ha-card-header-color,var(--primary-text-color));' +
    'font-family:var(--ha-card-header-font-family,inherit);font-size:var(--ha-card-header-font-size,var(--ha-font-size-2xl,24px));' +
    'letter-spacing:-.012em;line-height:var(--ha-line-height-condensed,1.2);' +
    'padding:var(--ha-space-5,20px) var(--ha-space-4,16px) var(--ha-space-6,24px);font-weight:var(--ha-font-weight-normal,400);' +
    'margin-block:0;display:block}' +
    ':host ::slotted(.card-content:not(:nth-child(1 of .card-content,.card-header))),slot:not(:first-child)::slotted(.card-content)' +
    '{margin-top:calc(var(--ha-space-2,8px) * -1);padding-top:0}' +
    ':host ::slotted(.card-content){padding:var(--ha-space-4,16px)}' +
    ':host ::slotted(.card-actions){border-top:1px solid var(--divider-color,#e8e8e8);padding:var(--ha-space-2,8px)}';
  customElements.define('ha-card', class extends HTMLElement {
    static get observedAttributes() { return ['header', 'raised']; }
    constructor() {
      super();
      // As HA's render: the h1 only with a header, then the slot. The style goes last: HA's rules read whether the
      // slot is the first child (`slot:not(:first-child)`), and Lit's styles are no child at all.
      const root = this.attachShadow({ mode: 'open' });
      root.innerHTML = '<slot></slot><style>' + HA_CARD_CSS + '</style>';
      this._h = null;
    }
    connectedCallback() { vitroUpgradeCard(this); }
    attributeChangedCallback(name, _old, v) { if (name === 'header') this.header = v; }
    // HA: the header, when set, as an h1 above the card's content; none without one.
    set header(v) {
      this._header = v || '';
      if (!this._header) { if (this._h) { this._h.remove(); this._h = null; } return; }
      if (!this._h) {
        this._h = document.createElement('h1');
        this._h.className = 'card-header';
        this.shadowRoot.insertBefore(this._h, this.shadowRoot.firstChild);
      }
      this._h.textContent = this._header;
    }
    get header() { return this._header || ''; }
    set raised(v) { if (v) this.setAttribute('raised', ''); else this.removeAttribute('raised'); }
    get raised() { return this.hasAttribute('raised'); }
  });
  // A property set before the element upgraded sits on the instance and hides the class accessor: move it over.
  function vitroUpgradeCard(el) {
    for (const p of ['header', 'raised']) {
      if (Object.prototype.hasOwnProperty.call(el, p)) { const v = el[p]; delete el[p]; el[p] = v; }
    }
  }
}
if (!customElements.get('ha-icon')) {
  const iconCache = new Map();
  customElements.define('ha-icon', class extends HTMLElement {
    static get observedAttributes() { return ['icon']; }
    connectedCallback() { this._render(); }
    attributeChangedCallback() { this._render(); }
    set icon(v) { this.setAttribute('icon', v || ''); }
    get icon() { return this.getAttribute('icon') || ''; }
    _render() {
      const sz = getComputedStyle(this).getPropertyValue('--mdc-icon-size') || '24px';
      this.style.display = 'inline-flex';
      this.style.alignItems = 'center';
      this.style.justifyContent = 'center';
      this.style.width = sz;
      this.style.height = sz;
      const name = (this.getAttribute('icon') || 'mdi:home').replace(/^mdi:/, '');
      const cached = iconCache.get(name);
      if (cached) { this.innerHTML = cached; return; }
      fetch('/__vitro/icon/' + encodeURIComponent(name))
        .then((r) => r.ok ? r.text() : '')
        .then((svg) => {
          if (svg) {
            const styled = svg.replace('<svg ', '<svg style="width:100%;height:100%" ');
            iconCache.set(name, styled);
            // Only while this element still asks for that icon: a later one may already be drawn.
            if ((this.getAttribute('icon') || 'mdi:home').replace(/^mdi:/, '') === name) this.innerHTML = styled;
          }
        })
        .catch(() => {});
    }
  });
}
// More HA elements upstream HACS cards render or extend (button-card, mini-media-player, …). Each is defined only
// when nothing else did, so a card bundle that ships its own wins.
const vitroDefine = (name, cls) => { if (!customElements.get(name)) customElements.define(name, cls); };
function vitroReflect(cls, props) {
  // A property per attribute, both ways, as Lit and Polymer elements behave.
  for (const p of props) {
    Object.defineProperty(cls.prototype, p, {
      configurable: true,
      get() { return this.getAttribute(p); },
      set(v) { if (v === null || v === undefined || v === false) this.removeAttribute(p); else this.setAttribute(p, v === true ? '' : String(v)); },
    });
  }
}
// A property set before the element upgraded sits on the instance and hides the class accessor: move it over.
function vitroUpgrade(el, props) {
  for (const p of props) {
    if (Object.prototype.hasOwnProperty.call(el, p)) { const v = el[p]; delete el[p]; el[p] = v; }
  }
}
const VITRO_DOMAIN_ICONS = {
  light: 'mdi:lightbulb', switch: 'mdi:flash', input_boolean: 'mdi:flash', fan: 'mdi:fan', cover: 'mdi:blinds',
  climate: 'mdi:thermostat', humidifier: 'mdi:air-humidifier', media_player: 'mdi:cast', lock: 'mdi:lock',
  person: 'mdi:account', device_tracker: 'mdi:account', sensor: 'mdi:eye', binary_sensor: 'mdi:checkbox-blank-circle',
  camera: 'mdi:video', weather: 'mdi:weather-partly-cloudy',
};
vitroDefine('iron-icon', class extends (customElements.get('ha-icon')) {});
vitroDefine('ha-state-icon', class extends HTMLElement {
  static get observedAttributes() { return ['icon']; }
  connectedCallback() { vitroUpgrade(this, ['icon', 'stateObj', 'hass']); this._render(); }
  attributeChangedCallback() { this._render(); }
  set icon(v) { this._icon = v || ''; this._render(); }
  get icon() { return this._icon || this.getAttribute('icon') || ''; }
  set stateObj(v) { this._stateObj = v; this._render(); }
  get stateObj() { return this._stateObj; }
  set hass(v) { this._hass = v; }
  get hass() { return this._hass; }
  // HA's ha-state-icon: the icon set on it, else the entity's own, else its domain's; with neither an icon nor a state
  // object, nothing (it used to draw a house). A domain HA has no icon for: DEFAULT_DOMAIN_ICON, mdi:bookmark.
  _resolve() {
    if (this.icon) return this.icon;
    const st = this._stateObj;
    if (!st) return '';
    if (st.attributes && st.attributes.icon) return st.attributes.icon;
    const domain = st.entity_id ? String(st.entity_id).split('.')[0] : '';
    return VITRO_DOMAIN_ICONS[domain] || 'mdi:bookmark';
  }
  _render() {
    if (!this.isConnected) return;
    this.style.display = 'inline-flex';
    const icon = this._resolve();
    if (!icon) { this.textContent = ''; return; }
    let inner = this.firstElementChild;
    if (!inner || inner.localName !== 'ha-icon') {
      this.textContent = '';
      inner = document.createElement('ha-icon');
      this.appendChild(inner);
    }
    if (inner.getAttribute('icon') !== icon) inner.setAttribute('icon', icon);
  }
});
class VitroIconButton extends HTMLElement {
  static get observedAttributes() { return ['icon', 'path', 'disabled', 'label']; }
  connectedCallback() { vitroUpgrade(this, ['icon', 'path', 'label', 'disabled']); this._render(); }
  attributeChangedCallback() { this._render(); }
  set path(v) { this._path = v || ''; this._render(); }
  get path() { return this._path || this.getAttribute('path') || ''; }
  _render() {
    if (!this.isConnected) return;
    this.style.display = 'inline-flex';
    if (!this._button) {
      const kept = Array.from(this.childNodes);
      const b = document.createElement('button');
      b.type = 'button';
      b.style.cssText = 'border:0;background:transparent;color:inherit;cursor:pointer;padding:6px;display:inline-flex;align-items:center;justify-content:center;border-radius:50%;font:inherit';
      kept.forEach((n) => b.appendChild(n));
      this._slotted = kept;
      this._button = b;
      this.appendChild(b);
    }
    const b = this._button;
    b.disabled = this.hasAttribute('disabled');
    const label = this.getAttribute('label');
    if (label) b.setAttribute('aria-label', label); else b.removeAttribute('aria-label');
    const icon = this.icon;
    const path = this.path;
    if (icon) {
      b.textContent = '';
      const i = document.createElement('ha-icon');
      i.setAttribute('icon', icon);
      b.appendChild(i);
    } else if (path) {
      b.textContent = '';
      const ns = 'http://www.w3.org/2000/svg';
      const svg = document.createElementNS(ns, 'svg');
      svg.setAttribute('viewBox', '0 0 24 24');
      svg.style.cssText = 'width:var(--mdc-icon-size,24px);height:var(--mdc-icon-size,24px);fill:currentColor';
      const p = document.createElementNS(ns, 'path');
      p.setAttribute('d', path);
      svg.appendChild(p);
      b.appendChild(svg);
    } else if (this._slotted && !b.childNodes.length) {
      this._slotted.forEach((n) => b.appendChild(n));
    }
  }
}
vitroReflect(VitroIconButton, ['icon', 'label']);
Object.defineProperty(VitroIconButton.prototype, 'disabled', {
  configurable: true,
  get() { return this.hasAttribute('disabled'); },
  set(v) { if (v) this.setAttribute('disabled', ''); else this.removeAttribute('disabled'); },
});
vitroDefine('ha-icon-button', VitroIconButton);
vitroDefine('paper-icon-button', class extends VitroIconButton {});
class VitroSlider extends HTMLElement {
  static get observedAttributes() { return ['min', 'max', 'step', 'value', 'disabled']; }
  connectedCallback() { vitroUpgrade(this, ['min', 'max', 'step', 'value', 'disabled']); this._ensure(); this._sync(); }
  attributeChangedCallback() { this._sync(); }
  _ensure() {
    if (this._input) return this._input;
    this.style.display = 'block';
    const i = document.createElement('input');
    i.type = 'range';
    i.style.cssText = 'width:100%;margin:0;accent-color:var(--slider-color, var(--primary-color))';
    const relay = (type) => (ev) => {
      ev.stopPropagation();
      this._value = Number(i.value);
      this.dispatchEvent(new Event(type, { bubbles: true, composed: true }));
    };
    i.addEventListener('input', relay('input'));
    i.addEventListener('change', relay('change'));
    this._input = i;
    this.appendChild(i);
    return i;
  }
  _sync() {
    const i = this._ensure();
    i.min = String(this.min);
    i.max = String(this.max);
    i.step = String(this.step);
    i.value = String(this.value);
    i.disabled = this.disabled;
  }
  _num(name, fallback) { const a = this.getAttribute(name); return a === null || a === '' || isNaN(Number(a)) ? fallback : Number(a); }
  get min() { return this._min !== undefined ? this._min : this._num('min', 0); }
  set min(v) { this._min = Number(v); this._sync(); }
  get max() { return this._max !== undefined ? this._max : this._num('max', 100); }
  set max(v) { this._max = Number(v); this._sync(); }
  get step() { return this._step !== undefined ? this._step : this._num('step', 1); }
  set step(v) { this._step = Number(v); this._sync(); }
  get value() { return this._value !== undefined ? this._value : this._num('value', 0); }
  set value(v) { this._value = Number(v); this._sync(); }
  get disabled() { return this._disabled !== undefined ? this._disabled : this.hasAttribute('disabled'); }
  set disabled(v) { this._disabled = !!v; this._sync(); }
}
vitroDefine('paper-slider', VitroSlider);
vitroDefine('ha-slider', class extends VitroSlider {});
vitroDefine('ha-switch', class extends HTMLElement {
  static get observedAttributes() { return ['checked', 'disabled']; }
  connectedCallback() { vitroUpgrade(this, ['checked', 'disabled']); this._ensure(); this._sync(); }
  attributeChangedCallback() { this._sync(); }
  _ensure() {
    if (this._input) return this._input;
    this.style.display = 'inline-flex';
    const i = document.createElement('input');
    i.type = 'checkbox';
    i.style.cssText = 'margin:0;accent-color:var(--switch-checked-color, var(--primary-color))';
    i.addEventListener('change', (ev) => {
      ev.stopPropagation();
      this._checked = i.checked;
      this.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
    });
    this._input = i;
    this.appendChild(i);
    return i;
  }
  _sync() { const i = this._ensure(); i.checked = this.checked; i.disabled = this.disabled; }
  get checked() { return this._checked !== undefined ? this._checked : this.hasAttribute('checked'); }
  set checked(v) { this._checked = !!v; this._sync(); }
  get disabled() { return this._disabled !== undefined ? this._disabled : this.hasAttribute('disabled'); }
  set disabled(v) { this._disabled = !!v; this._sync(); }
});
function vitroRelativeTime(when, now) {
  const t = when instanceof Date ? when.getTime() : (typeof when === 'number' ? when : Date.parse(String(when)));
  if (!isFinite(t)) return '';
  const delta = Math.round(((now === undefined ? Date.now() : now) - t) / 1000);
  const ahead = delta < 0;
  const abs = Math.abs(delta);
  if (abs < 45) return 'just now';
  const steps = [[60, 'second'], [3600, 'minute'], [86400, 'hour'], [604800, 'day'], [2592000, 'week'], [31536000, 'month']];
  let n = abs;
  let unit = 'year';
  let div = 31536000;
  for (let k = 1; k < steps.length; k++) {
    if (abs < steps[k][0]) { unit = steps[k][1]; div = steps[k - 1][0]; break; }
  }
  if (unit === 'year' && abs < 31536000) { unit = 'month'; div = 2592000; }
  n = Math.max(1, Math.round(abs / div));
  const label = n + ' ' + unit + (n === 1 ? '' : 's');
  return ahead ? 'in ' + label : label + ' ago';
}
window.vitroRelativeTime = vitroRelativeTime;
vitroDefine('ha-relative-time', class extends HTMLElement {
  static get observedAttributes() { return ['datetime']; }
  connectedCallback() { vitroUpgrade(this, ['datetime', 'hass']); this._render(); this._timer = setInterval(() => this._render(), 60000); }
  disconnectedCallback() { clearInterval(this._timer); }
  attributeChangedCallback() { this._render(); }
  set datetime(v) { this._datetime = v; this._render(); }
  get datetime() { return this._datetime !== undefined ? this._datetime : this.getAttribute('datetime'); }
  set hass(v) { this._hass = v; }
  get hass() { return this._hass; }
  _render() {
    const v = this.datetime;
    this.textContent = v === null || v === undefined || v === '' ? '' : vitroRelativeTime(v);
  }
});
// HA's ha-svg-icon: an MDI `path` (a property or attribute) in a 24 x 24 viewBox, `--mdc-icon-size` square.
vitroDefine('ha-svg-icon', class extends HTMLElement {
  static get observedAttributes() { return ['path']; }
  connectedCallback() { vitroUpgrade(this, ['path']); this._render(); }
  attributeChangedCallback() { this._render(); }
  set path(v) { this._path = v || ''; this._render(); }
  get path() { return this._path || this.getAttribute('path') || ''; }
  _render() {
    if (!this.isConnected) return;
    this.style.display = 'inline-flex';
    this.style.width = 'var(--mdc-icon-size, 24px)';
    this.style.height = 'var(--mdc-icon-size, 24px)';
    const ns = 'http://www.w3.org/2000/svg';
    let svg = this.firstElementChild;
    if (!svg || svg.localName !== 'svg') {
      this.textContent = '';
      svg = document.createElementNS(ns, 'svg');
      svg.setAttribute('viewBox', '0 0 24 24');
      svg.style.cssText = 'width:100%;height:100%;fill:currentColor';
      svg.appendChild(document.createElementNS(ns, 'path'));
      this.appendChild(svg);
    }
    svg.firstElementChild.setAttribute('d', this.path);
  }
});
// HA's action-handler (action-handler-directive.ts), which cards bind their gestures to (Mushroom-style cards look it up
// in document.body and call bind(element, options)): a tap, a hold (500 ms) or a double tap (250 ms) fires `action`
// on the element, and a move, scroll or wheel cancels a press, as in HA. Without it such a card threw on render.
vitroDefine('action-handler', class extends HTMLElement {
  constructor() { super(); this.holdTime = 500; this.cancelled = false; this.scrolled = false; }
  connectedCallback() {
    this.style.display = 'none';
    if (this._wired) return;
    this._wired = true;
    ['touchcancel', 'mouseout', 'mouseup', 'touchmove', 'mousewheel', 'wheel', 'scroll'].forEach((type) => {
      document.addEventListener(type, () => {
        this.cancelled = true;
        // A scroll or a move, unlike the mouseup every click has: the press was a scroll, never a tap or a hold.
        if (type !== 'mouseup' && type !== 'mouseout' && type !== 'touchcancel') this.scrolled = true;
        if (this.timer) { clearTimeout(this.timer); this.timer = undefined; }
      }, { passive: true });
    });
  }
  bind(element, options) {
    const opts = options || {};
    const fire = (target, action) => target.dispatchEvent(new CustomEvent('action', { detail: { action }, bubbles: true, composed: true }));
    const prev = element.actionHandler;
    if (prev && JSON.stringify(prev.options) === JSON.stringify(opts)) return;
    // The taps the old binding still holds back for a double tap (one per click, on any of its descendants) never fire
    // under the new one (disabled, say). Only this element's: another element's pending taps stay.
    if (prev && prev.pendingTaps) {
      for (const id of prev.pendingTaps) {
        clearTimeout(id);
        if (this.dblClickTimeout === id) { this.dblClickTimeout = undefined; this.dblClickTarget = undefined; }
      }
      prev.pendingTaps.clear();
    }
    if (prev && prev.start) {
      element.removeEventListener('touchstart', prev.start);
      element.removeEventListener('touchend', prev.end);
      element.removeEventListener('touchcancel', prev.end);
      element.removeEventListener('mousedown', prev.start);
      element.removeEventListener('click', prev.end);
      element.removeEventListener('keydown', prev.key);
    }
    const handler = { options: opts, pendingTaps: new Set() };
    element.actionHandler = handler;
    if (opts.disabled) return;
    handler.start = () => {
      this.cancelled = false;
      this.scrolled = false;
      if (opts.hasHold) {
        this.held = false;
        this.timer = setTimeout(() => { this.held = true; }, this.holdTime);
      }
    };
    handler.end = (ev) => {
      if (ev.type === 'touchcancel' || (ev.type === 'touchend' && this.cancelled)) return;
      // A mouse press a wheel or scroll cancelled: its click is no hold (or tap) either.
      if (ev.type === 'click' && this.scrolled) { this.scrolled = false; return; }
      const target = ev.target;
      if (ev.cancelable) ev.preventDefault();
      if (opts.hasHold) { clearTimeout(this.timer); this.timer = undefined; }
      if (opts.hasHold && this.held) {
        fire(target, 'hold');
      } else if (opts.hasDoubleClick) {
        if ((ev.type === 'click' && ev.detail < 2) || !this.dblClickTimeout || this.dblClickTarget !== target) {
          const id = setTimeout(() => {
            handler.pendingTaps.delete(id);
            if (this.dblClickTimeout === id) { this.dblClickTimeout = undefined; this.dblClickTarget = undefined; }
            if (opts.hasTap !== false) fire(target, 'tap');
          }, 250);
          handler.pendingTaps.add(id);
          this.dblClickTimeout = id;
          this.dblClickTarget = target;
        } else {
          clearTimeout(this.dblClickTimeout);
          handler.pendingTaps.delete(this.dblClickTimeout);
          this.dblClickTimeout = undefined;
          this.dblClickTarget = undefined;
          fire(target, 'double_tap');
        }
      } else if (opts.hasTap !== false) {
        fire(target, 'tap');
      }
    };
    // Enter or Space is a tap: never the hold an earlier press left behind, and never one a pointer is timing now.
    handler.key = (ev) => {
      if (ev.key !== 'Enter' && ev.key !== ' ') return;
      clearTimeout(this.timer);
      this.timer = undefined;
      this.held = false;
      handler.end(ev);
    };
    if (!opts.keyboardOnly) {
      element.addEventListener('touchstart', handler.start, { passive: true });
      element.addEventListener('touchend', handler.end);
      element.addEventListener('touchcancel', handler.end);
      element.addEventListener('mousedown', handler.start, { passive: true });
      element.addEventListener('click', handler.end);
    }
    element.addEventListener('keydown', handler.key);
  }
});
vitroDefine('ha-ripple', class extends HTMLElement {});
vitroDefine('ha-spinner', class extends HTMLElement {});
vitroDefine('ha-formfield', class extends HTMLElement {});
vitroDefine('mwc-menu', class extends HTMLElement {});
vitroDefine('mwc-list-item', class extends HTMLElement {});
vitroDefine('ha-input', class extends HTMLElement {
  static get observedAttributes() { return ['value', 'placeholder', 'type', 'disabled']; }
  connectedCallback() { vitroUpgrade(this, ['value']); this._ensure(); this._sync(); }
  attributeChangedCallback() { this._sync(); }
  _ensure() {
    if (this._input) return this._input;
    this.style.display = 'inline-block';
    const i = document.createElement('input');
    i.style.cssText = 'width:100%;box-sizing:border-box;font:inherit;color:inherit';
    i.addEventListener('input', () => { this._value = i.value; });
    i.addEventListener('change', () => { this._value = i.value; });
    this._input = i;
    this.appendChild(i);
    return i;
  }
  _sync() {
    const i = this._ensure();
    i.type = this.getAttribute('type') || 'text';
    i.placeholder = this.getAttribute('placeholder') || '';
    i.disabled = this.hasAttribute('disabled');
    i.value = this.value;
  }
  get value() { return this._value !== undefined ? this._value : (this.getAttribute('value') || ''); }
  set value(v) { this._value = v === null || v === undefined ? '' : String(v); this._sync(); }
});
// The latest hass object, for elements cards create through loadCardHelpers().
window.__vitroHass = null;
function vitroFallbackCard(c) {
  // A built-in card nested in a custom one (stack-in-card, auto-entities, mod-card): a calm summary, not HA's frontend.
  const card = document.createElement('ha-card');
  card.className = 'vitro-fallback';
  // Inline styles: inside another card's shadow root the page's own rules don't reach this card.
  card.style.cssText = 'padding:12px 16px;display:block';
  let cfg = c || {};
  const ids = () => {
    const list = [];
    if (cfg.entity) list.push(cfg.entity);
    for (const e of (cfg.entities || [])) {
      const id = typeof e === 'string' ? e : (e && e.entity);
      if (id) list.push(id);
    }
    return list;
  };
  const render = () => {
    const hass = card._hass;
    const list = ids();
    // Text goes in through textContent only: names and states never become markup.
    card.innerHTML = (cfg.title ? '<div class="vitro-title" style="font-size:1.15em;font-weight:500;padding-bottom:8px"></div>' : '') +
      list.map(() => '<div class="vitro-row" style="display:flex;justify-content:space-between;align-items:center;gap:12px;padding:6px 0"><span></span><span class="vitro-state" style="color:var(--secondary-text-color)"></span></div>').join('');
    if (cfg.title) card.querySelector('.vitro-title').textContent = cfg.title;
    const rowEls = card.querySelectorAll('.vitro-row');
    list.forEach((id, i) => {
      const st = hass && hass.states ? hass.states[id] : undefined;
      rowEls[i].children[0].textContent = (st && st.attributes && st.attributes.friendly_name) || id;
      rowEls[i].children[1].textContent = st && hass ? hass.formatEntityState(st) : '';
    });
    if (!cfg.title && !rowEls.length) card.textContent = cfg.type || 'card';
  };
  card.setConfig = (next) => { cfg = next || {}; render(); };
  Object.defineProperty(card, 'hass', { get() { return card._hass; }, set(h) { card._hass = h; render(); } });
  card.getCardSize = () => 1 + ids().length;
  render();
  return card;
}
function vitroCreateCardElement(c) {
  const cfg = c || {};
  const type = String(cfg.type || '');
  if (!type.startsWith('custom:')) {
    const el = vitroFallbackCard(cfg);
    if (window.__vitroHass) el.hass = window.__vitroHass;
    return el;
  }
  const t = type.slice(7);
  const el = document.createElement(t);
  const configure = () => {
    try { if (typeof el.setConfig === 'function') el.setConfig(cfg); } catch (e) { console.warn('setConfig', t, e); }
    if (window.__vitroHass && !el.hass) el.hass = window.__vitroHass;
  };
  if (customElements.get(t)) configure();
  else customElements.whenDefined(t).then(() => { if (customElements.upgrade) customElements.upgrade(el); configure(); });
  return el;
}
window.loadCardHelpers = () => Promise.resolve({
  createCardElement: vitroCreateCardElement,
  createHuiElement: (c) => vitroCreateCardElement(c),
  importMoreInfoControl: () => Promise.resolve(),
});
</script>
<script>
${FRONTEND_WATCH_JS.replace(SCRIPT_END, "<\\\\/$1")}
</script>
<script>
${FORMAT_DURATION_JS.replace(SCRIPT_END, "<\\\\/$1")}
</script>
$resourceScripts
</head>
<body>
<div id="mount"></div>
<script type="module">
// The island server's capability: the proxy and the bridge answer only under it (or a request this page's address is
// the Referer of). The page's address, kept: a card's history.pushState must not take it away (see wireEvents).
const VITRO_BASE = $baseJson;
const VITRO_PAGE = location.pathname + location.search;
// A path on Home Assistant, as a card means it: this page's own addresses without the capability.
function vitroHaPath(p) {
  return VITRO_BASE && typeof p === 'string' && (p === VITRO_BASE || p.startsWith(VITRO_BASE + '/')) ? (p.slice(VITRO_BASE.length) || '/') : p;
}
const rawStates = {};
let haConfig = $haCfgJson;
const haUser = $haUserJson;
const haLocale = $haLocaleJson;
let accessToken = $tokenJson;
const wsPending = new Map();
// Lines waiting for the bridge: { line, id } (id: the call's, when it is one).
const outQueue = [];
// A call that can't go out within this long is rejected and leaves the queue: never sent much later, when the
// user may no longer expect it.
const QUEUED_CALL_MS = 5000;
let wsId = 1;
let sock = null;
let bridgeOpened = false;

function sendBridge(obj, id) {
  const line = JSON.stringify(obj) + '\n';
  if (sock && sock.readyState === WebSocket.OPEN) { sock.send(line); return true; }
  outQueue.push({ line, id });
  return false;
}

// True once the card walked hass.states: the island follows every entity, so no single lookup needs a watch.
let walksAll = false;
const statesHandler = {
  get(target, prop) {
    if (!walksAll && typeof prop === 'string' && prop.includes('.')) {
      sendBridge({ type: 'watch', entity_id: prop });
    }
    return target[prop];
  },
  // Object.keys/values/entries, for…in, spreads: HA's frontend hands a card every state, so a card that walks them
  // asks (once) for all of them. Cards that only look entities up by id never pay for that.
  ownKeys(target) {
    if (!walksAll) {
      walksAll = true;
      sendBridge({ type: 'watchAll' });
    }
    return Reflect.ownKeys(target);
  },
};
// HA's frontend hands a card a new `states` object whenever a state changes, an unchanged entity keeping its own
// object: cards compare oldHass.states[id] with hass.states[id] to skip renders (HA's hasConfigOrEntityChanged).
// One proxy over the live map made every old hass read the new states, so such a card never saw a change, and one
// that first rendered before the bridge's snapshot stayed blank (#885). Each change gets a copy of its own.
let statesView = null;
function currentStates() {
  if (!statesView) statesView = new Proxy(Object.assign({}, rawStates), statesHandler);
  return statesView;
}

// hass.entities, devices, areas and floors, as HA's frontend has them: asked for (once) when a card first reads one.
const registries = { entities: {}, devices: {}, areas: {}, floors: {} };
let registriesAsked = false;

function askRegistries() {
  if (registriesAsked) return;
  registriesAsked = true;
  sendBridge({ type: 'registries' });
}
// True once the registries came: before that, names are HA's own "without context" ones (the friendly name).
let registriesIn = false;

// HA's formatEntityName (common/entity/compute_entity_name_display.ts, frontend 20261028.5): an entity's name made of
// its own name, its device's, its parent device's, its area's and its floor's, from the registries, as a card's `name`
// config asks (a string, one item or a list of { type } / { type: 'text', text } items). Mushroom 5.3 names every
// entity with it on HA 2026.4 and later.
const VITRO_DEFAULT_NAME = ['parent_device', 'device', 'entity'];
function vitroStateName(s) {
  const a = (s && s.attributes) || {};
  const id = String((s && s.entity_id) || '');
  return a.friendly_name === undefined ? id.slice(id.indexOf('.') + 1).replace(/_/g, ' ') : String(a.friendly_name == null ? '' : a.friendly_name);
}
function vitroTrimmed(v) { return v == null ? undefined : String(v).trim(); }
function vitroNameParts(stateObj, followContext) {
  const entry = registries.entities[stateObj.entity_id];
  if (!entry) return { entity: vitroStateName(stateObj) };
  const devices = registries.devices;
  const device = entry.device_id ? devices[entry.device_id] : undefined;
  const parent = device && device.parent_device_id ? devices[device.parent_device_id] : undefined;
  // A child device without an area of its own is in its parent's.
  const deviceArea = device ? (device.area_id || (parent ? parent.area_id : undefined)) : undefined;
  const area = (entry.area_id || deviceArea) ? registries.areas[entry.area_id || deviceArea] : undefined;
  const floor = area && area.floor_id ? registries.floors[area.floor_id] : undefined;
  const entityName = (entry.name != null ? entry.name : (entry.original_name != null ? String(entry.original_name) : undefined)) || undefined;
  const deviceName = (d) => vitroTrimmed(d.name_by_user || d.name);
  // As HA's backend: an owner adds its name only while the next_name_part links reach it; an entity with no name of
  // its own is named after its device.
  const nameDevice = !followContext || entry.next_name_part === 'device' || !entityName ? device : null;
  const nameParent = !followContext || (entry.next_name_part === 'device' && device && device.next_name_part === 'parent_device') ? parent : null;
  return {
    entity: entityName,
    device: nameDevice ? deviceName(nameDevice) : undefined,
    parent_device: nameParent ? deviceName(nameParent) : undefined,
    area: area ? vitroTrimmed(area.name) : undefined,
    floor: floor ? vitroTrimmed(floor.name) : undefined,
  };
}
function vitroFormatEntityName(stateObj, name, options) {
  if (typeof name === 'string') return name;
  const separator = options && options.separator != null ? options.separator : ' ';
  const items = name == null ? null : (Array.isArray(name) ? name : [name]);
  if (items && items.every((n) => n && n.type === 'text')) return items.map((n) => n.text).join(separator);
  // HA's formatter before its registry-aware one is in (computeEntityNameDisplayWithoutContext): the friendly name.
  askRegistries();
  if (!registriesIn || !stateObj) return stateObj ? vitroStateName(stateObj) : '';
  if (!items) {
    const parts = vitroNameParts(stateObj, true);
    return VITRO_DEFAULT_NAME.map((t) => parts[t]).filter((n) => n).join(separator) || vitroStateName(stateObj);
  }
  const parts = vitroNameParts(stateObj, false);
  const useDevice = !parts.entity && !items.some((i) => i && i.type === 'device');
  return items.map((i) => (i && i.type === 'text' ? i.text : parts[i && i.type === 'entity' && useDevice ? 'device' : (i && i.type)]))
    .filter((n) => n).join(separator);
}

function vitroTitle(s) {
  const t = String(s == null ? '' : s).replace(/_/g, ' ');
  return t ? t.charAt(0).toUpperCase() + t.slice(1) : t;
}

// HA's strings: its UI strings in the user's language and the backend's the panel has read (IslandStrings), fetched
// before the card is made. loadBackendTranslation adds to them. A key that isn't there is a miss.
let haStrings = {};
const stringsReady = fetch($stringsJson).then((r) => (r.ok ? r.json() : {})).then((got) => {
  if (!got || typeof got !== 'object') return;
  // What loadBackendTranslation brought meanwhile stays.
  haStrings = Object.assign({}, got, haStrings);
  if (cardEl) stringsChanged();
}).catch(() => {});

function vitroNumber(n) {
  try { return new Intl.NumberFormat(haLocale.language).format(n); } catch (e) { return String(n); }
}
const vitroPluralRules = {};
function vitroPluralCategory(n, ordinal) {
  const key = haLocale.language + (ordinal ? ':o' : '');
  try {
    const rules = vitroPluralRules[key] || (vitroPluralRules[key] = new Intl.PluralRules(haLocale.language, { type: ordinal ? 'ordinal' : 'cardinal' }));
    return rules.select(n);
  } catch (e) {
    return n === 1 ? 'one' : 'other';
  }
}
// The end of the {…} block that opens at [start], or -1.
// ICU's quoting: an apostrophe before { } # or | starts literal text up to the next lone apostrophe ('' inside is
// one apostrophe). The index of that closing apostrophe, or -1 when [i] starts no quote.
function vitroQuoteEnd(p, i) {
  if (p[i] !== "'" || !'{}#|'.includes(p[i + 1] || '')) return -1;
  for (let j = i + 1; j < p.length; j++) {
    if (p[j] !== "'") continue;
    if (p[j + 1] === "'") { j++; continue; }
    return j;
  }
  return p.length;
}
function vitroBlockEnd(p, start) {
  let depth = 0;
  for (let j = start; j < p.length; j++) {
    // '' is one apostrophe, never the start of a quote.
    if (p[j] === "'" && p[j + 1] === "'") { j++; continue; }
    const q = vitroQuoteEnd(p, j);
    if (q >= 0) { j = q; continue; }
    if (p[j] === '{') depth++;
    else if (p[j] === '}' && --depth === 0) return j;
  }
  return -1;
}
// A plural branch's own # (the number): not a quoted one, and not one inside a nested argument (a nested plural
// puts its own count there).
function vitroPound(branch, n) {
  let out = '';
  let depth = 0;
  for (let j = 0; j < branch.length; j++) {
    if (branch[j] === "'" && branch[j + 1] === "'") { out += "''"; j++; continue; }
    const q = vitroQuoteEnd(branch, j);
    if (q >= 0) { out += branch.slice(j, q + 1); j = q; continue; }
    const c = branch[j];
    if (c === '{') depth++;
    else if (c === '}') depth--;
    out += c === '#' && depth === 0 ? n : c;
  }
  return out;
}
// A plural or select block's options ("offset:1 =0 {…} one {…} other {…}").
function vitroMessageOptions(s) {
  const map = {};
  let offset = 0;
  let i = 0;
  while (i < s.length) {
    while (i < s.length && /\s/.test(s[i])) i++;
    if (i >= s.length) break;
    let j = i;
    while (j < s.length && !/[\s{]/.test(s[j])) j++;
    const key = s.slice(i, j);
    if (key.startsWith('offset:')) { offset = Number(key.slice(7)) || 0; i = j; continue; }
    while (j < s.length && /\s/.test(s[j])) j++;
    if (s[j] !== '{') break;
    const end = vitroBlockEnd(s, j);
    if (end < 0) break;
    map[key] = s.slice(j + 1, end);
    i = end + 1;
  }
  return { offset, map };
}
function vitroMessageArg(body, values) {
  const comma = body.indexOf(',');
  const name = (comma < 0 ? body : body.slice(0, comma)).trim();
  const has = Object.prototype.hasOwnProperty.call(values, name);
  const v = values[name];
  if (comma < 0) return has ? (typeof v === 'number' ? vitroNumber(v) : String(v)) : '{' + body + '}';
  const rest = body.slice(comma + 1);
  const comma2 = rest.indexOf(',');
  const kind = (comma2 < 0 ? rest : rest.slice(0, comma2)).trim();
  const opts = comma2 < 0 ? '' : rest.slice(comma2 + 1);
  if (kind === 'plural' || kind === 'selectordinal') {
    const n = Number(v);
    const { offset, map } = vitroMessageOptions(opts);
    let branch = map['=' + n];
    if (branch === undefined) branch = map[vitroPluralCategory(n - offset, kind === 'selectordinal')];
    if (branch === undefined) branch = map.other;
    return branch === undefined ? '' : vitroFormatMessage(vitroPound(branch, vitroNumber(n - offset)), values);
  }
  if (kind === 'select') {
    const { map } = vitroMessageOptions(opts);
    const branch = map[String(v)] !== undefined ? map[String(v)] : map.other;
    return branch === undefined ? '' : vitroFormatMessage(branch, values);
  }
  if (kind === 'number') return has ? vitroNumber(Number(v)) : '';
  return has ? String(v) : '';
}
// HA's message format (ICU, as its intl-messageformat reads it): {name}, {n, plural, =0 {…} one {…} other {…}} with
// #, {x, select, a {…} other {…}}, and '' for a quote. A value the call doesn't give stays as {name}.
function vitroFormatMessage(pattern, values) {
  let out = '';
  let i = 0;
  while (i < pattern.length) {
    const c = pattern[i];
    if (c === "'" && pattern[i + 1] === "'") { out += "'"; i += 2; continue; }
    const q = vitroQuoteEnd(pattern, i);
    if (q >= 0) { out += pattern.slice(i + 1, q).replace(/''/g, "'"); i = q + 1; continue; }
    if (c !== '{') { out += c; i++; continue; }
    const end = vitroBlockEnd(pattern, i);
    if (end < 0) { out += pattern.slice(i); break; }
    out += vitroMessageArg(pattern.slice(i + 1, end), values);
    i = end + 1;
  }
  return out;
}

// HA's localize: the string for [key] with its placeholders filled, "" for a key it doesn't have (so a card's
// `localize(key) || fallback` reaches its fallback). Placeholders come as one { name: value } object or as
// ('name', value, …) pairs.
function vitroLocalize(key, ...args) {
  if (!key) return '';
  const raw = haStrings[String(key)];
  if (typeof raw !== 'string') return '';
  const values = {};
  if (args.length === 1 && args[0] && typeof args[0] === 'object') Object.assign(values, args[0]);
  else for (let i = 0; i + 1 < args.length; i += 2) values[args[i]] = args[i + 1];
  if (raw.indexOf('{') < 0 && raw.indexOf("'") < 0) return raw;
  try { return vitroFormatMessage(raw, values); } catch (e) { return raw; }
}
// A new function whenever the strings change, as HA's is: a card that compares localize sees the change.
function makeLocalize() { return (key, ...args) => vitroLocalize(key, ...args); }

// HA's numberFormatToLocale, formatNumber and blankBeforeUnit (common/number/format_number.ts), on this page's Intl.
function vitroNumberLocale(l) {
  switch (l.number_format) {
    case 'comma_decimal': return ['en-US', 'en'];
    case 'decimal_comma': return ['de', 'es', 'it'];
    case 'space_comma': return ['fr', 'sv', 'cs'];
    case 'quote_decimal': return ['de-CH'];
    case 'system': return undefined;
    default: return l.language;
  }
}
function vitroFormatNumber(num, l, options) {
  l = l || haLocale;
  const o = Object.assign({ maximumFractionDigits: 2 }, options || {});
  if (typeof num === 'string' && (!options || (options.minimumFractionDigits === undefined && options.maximumFractionDigits === undefined))) {
    const digits = num.indexOf('.') > -1 ? num.split('.')[1].length : 0;
    o.minimumFractionDigits = digits;
    o.maximumFractionDigits = digits;
  }
  if (l.number_format !== 'none' && !Number.isNaN(Number(num))) return new Intl.NumberFormat(vitroNumberLocale(l), o).format(Number(num));
  if (!Number.isNaN(Number(num)) && num !== '' && l.number_format === 'none') {
    return new Intl.NumberFormat('en-US', Object.assign(o, { useGrouping: false })).format(Number(num));
  }
  return typeof num === 'string' ? num : String(num);
}
function vitroBlankBeforeUnit(unit, l) {
  if (unit === '°') return '';
  if (unit === '%') return ['cs', 'de', 'fi', 'fr', 'sk', 'sv'].includes((l || haLocale).language) ? ' ' : '';
  return ' ';
}

// HA's translated label for [state] of [stateObj] (the integration's, the device class's, the domain's), or "".
function vitroStateLabel(stateObj, state) {
  if (state === 'unavailable' || state === 'unknown') return vitroLocalize('state.default.' + state);
  const id = stateObj && stateObj.entity_id ? String(stateObj.entity_id) : '';
  const domain = id.split('.')[0];
  if (!domain) return '';
  // The integration's label needs the entity registry: asked for once (HA's frontend always has it), the domain's
  // label serving until it's here.
  askRegistries();
  const entry = registries.entities[id];
  const dc = stateObj.attributes ? stateObj.attributes.device_class : undefined;
  return (entry && entry.platform && entry.translation_key
      ? vitroLocalize('component.' + entry.platform + '.entity.' + domain + '.' + entry.translation_key + '.state.' + state) : '') ||
    (dc ? vitroLocalize('component.' + domain + '.entity_component.' + dc + '.state.' + state) : '') ||
    vitroLocalize('component.' + domain + '.entity_component._.state.' + state);
}

// HA's computeStateDisplay and computeStateToParts (common/entity/compute_state_display.ts, frontend 20261028.5):
// numbers in the user's format with the registry's display precision and the translated unit, money in its
// currency, dates, times and timestamps in the user's language and time zone, then HA's translated state, then the
// state as it is. A duration in minutes, hours or days reads as one ("1h 30m"), through format-duration.js.
const VITRO_NUMBER_DOMAINS = ['counter', 'input_number', 'number'];
const VITRO_DATE_TIME_DOMAINS = new Set(['date', 'input_datetime', 'time']);
const VITRO_TIMESTAMP_DOMAINS = new Set(['ai_task', 'button', 'conversation', 'event', 'image', 'infrared', 'input_button',
  'notify', 'radio_frequency', 'scene', 'stt', 'tag', 'tts', 'wake_word', 'datetime']);
const VITRO_TIMESTAMP_CLASSES = ['timestamp', 'uptime'];
// data/sensor_entity_constants.ts: SensorDeviceClass less its non-numeric classes.
const VITRO_NUMERIC_SENSOR_CLASSES = new Set(['absolute_humidity', 'apparent_power', 'aqi', 'area', 'atmospheric_pressure',
  'battery', 'blood_glucose_concentration', 'carbon_dioxide', 'carbon_monoxide', 'conductivity', 'current', 'data_rate',
  'data_size', 'distance', 'duration', 'energy', 'energy_distance', 'energy_storage', 'frequency', 'gas', 'humidity',
  'illuminance', 'irradiance', 'moisture', 'monetary', 'nitrogen_dioxide', 'nitrogen_monoxide', 'nitrous_oxide', 'ozone',
  'ph', 'pm1', 'pm10', 'pm25', 'pm4', 'power', 'power_factor', 'precipitation', 'precipitation_intensity', 'pressure',
  'radon', 'reactive_energy', 'reactive_power', 'signal_strength', 'sound_pressure', 'speed', 'sulphur_dioxide',
  'temperature', 'temperature_delta', 'volatile_organic_compounds', 'volatile_organic_compounds_parts', 'voltage',
  'volume', 'volume_flow_rate', 'volume_storage', 'water', 'weight', 'wind_direction', 'wind_speed']);
const VITRO_MONETARY_PARTS = { integer: 'value', group: 'value', decimal: 'value', fraction: 'value', minusSign: 'value',
  plusSign: 'value', literal: 'literal', currency: 'unit' };

// HA's getNumberFormatOptions: the registry's display precision, or no decimals for a whole state with a whole step.
function vitroNumberOptions(state, attributes, entry) {
  const precision = entry ? entry.display_precision : undefined;
  if (precision !== undefined && precision !== null) return { maximumFractionDigits: precision, minimumFractionDigits: precision };
  if (Number.isInteger(Number(attributes && attributes.step)) && Number.isInteger(Number(state))) return { maximumFractionDigits: 0 };
  return undefined;
}
// HA's formatNumberToParts (common/number/format_number.ts).
function vitroNumberParts(num, options) {
  const l = haLocale;
  const o = Object.assign({ maximumFractionDigits: 2 }, options || {});
  if (typeof num === 'string' && (!options || (options.minimumFractionDigits === undefined && options.maximumFractionDigits === undefined))) {
    const digits = num.indexOf('.') > -1 ? num.split('.')[1].length : 0;
    o.minimumFractionDigits = digits;
    o.maximumFractionDigits = digits;
  }
  if (l.number_format !== 'none' && !Number.isNaN(Number(num))) return new Intl.NumberFormat(vitroNumberLocale(l), o).formatToParts(Number(num));
  if (!Number.isNaN(Number(num)) && num !== '' && l.number_format === 'none') {
    return new Intl.NumberFormat('en-US', Object.assign(o, { useGrouping: false })).formatToParts(Number(num));
  }
  return [{ type: 'literal', value: num }];
}
// HA's useAmPm and resolveTimeZone (common/datetime), and its formatDateTime, formatDate and formatTime.
function vitroAmPm(l) {
  if (l.time_format === 'language' || l.time_format === 'system') {
    const test = new Date('January 1, 2023 22:00:00').toLocaleString(l.time_format === 'language' ? l.language : undefined);
    return test.includes('10');
  }
  return l.time_format === '12';
}
function vitroTimeZone(l) {
  let local;
  try { local = Intl.DateTimeFormat().resolvedOptions().timeZone; } catch (e) {}
  return l.time_zone === 'local' && local ? local : ((haConfig && haConfig.time_zone) || local || 'UTC');
}
function vitroDateFormat(l, kind) {
  const amPm = vitroAmPm(l);
  const date = { year: 'numeric', month: 'long', day: 'numeric' };
  const time = { hour: kind === 'time' ? 'numeric' : (amPm ? 'numeric' : '2-digit'), minute: '2-digit', hourCycle: amPm ? 'h12' : 'h23' };
  const o = kind === 'date' ? date : kind === 'time' ? time : Object.assign({}, date, time);
  return new Intl.DateTimeFormat(l.language, Object.assign(o, { timeZone: vitroTimeZone(l) }));
}

function vitroStateParts(stateObj, state) {
  const s = state !== undefined && state !== null ? state : (stateObj ? stateObj.state : '');
  const str = String(s == null ? '' : s);
  // HA's words for it, or the state in words when the page has no strings for it (never an empty state).
  if (str === 'unknown' || str === 'unavailable') return [{ type: 'value', value: vitroLocalize('state.default.' + str) || vitroTitle(str) }];
  const id = String((stateObj && stateObj.entity_id) || '');
  const domain = id.split('.')[0];
  const attributes = (stateObj && stateObj.attributes) || {};
  // The registry's display precision and translated unit: asked for once, as for the state's label.
  askRegistries();
  const entry = registries.entities[id];
  if (attributes.unit_of_measurement || attributes.state_class || VITRO_NUMBER_DOMAINS.includes(domain) ||
      (domain === 'sensor' && VITRO_NUMERIC_SENSOR_CLASSES.has(attributes.device_class))) {
    // A duration in minutes, hours or days at the entity's display precision; one DurationFormat refuses falls
    // through to the number with its unit (format-duration.js).
    if (attributes.device_class === 'duration' && attributes.unit_of_measurement && VITRO_DURATION_UNITS.includes(attributes.unit_of_measurement)) {
      try {
        return [{ type: 'value', value: vitroFormatDuration(haLocale, str, attributes.unit_of_measurement, entry ? entry.display_precision : undefined) }];
      } catch (e) {}
    }
    const options = vitroNumberOptions(str, attributes, entry);
    if (attributes.device_class === 'monetary') {
      let parts = [];
      try {
        parts = vitroNumberParts(str, Object.assign({ style: 'currency', currency: attributes.unit_of_measurement, minimumFractionDigits: 2 }, options || {}));
      } catch (e) {}
      if (parts.length) {
        const out = [];
        for (const part of parts) {
          const type = VITRO_MONETARY_PARTS[part.type];
          if (!type) continue;
          const last = out[out.length - 1];
          if (type === 'value' && last && last.type === 'value') last.value += part.value;
          else out.push({ type, value: part.value });
        }
        return out;
      }
    }
    const value = vitroNumberParts(str, options).map((p) => p.value).join('');
    const unit = (entry && entry.translation_key &&
      vitroLocalize('component.' + entry.platform + '.entity.' + domain + '.' + entry.translation_key + '.unit_of_measurement')) ||
      attributes.unit_of_measurement;
    if (unit) return [{ type: 'value', value }, { type: 'literal', value: vitroBlankBeforeUnit(unit) }, { type: 'unit', value: unit }];
    return [{ type: 'value', value }];
  }
  if (VITRO_DATE_TIME_DOMAINS.has(domain)) {
    // Time-zone agnostic: in the browser's own zone, as HA has them.
    const l = Object.assign({}, haLocale, { time_zone: 'local' });
    try {
      const c = str.split(' ');
      if (c.length === 2) return [{ type: 'value', value: vitroDateFormat(l, 'dateTime').format(new Date(c.join('T'))) }];
      if (c.length === 1 && str.includes('-')) return [{ type: 'value', value: vitroDateFormat(l, 'date').format(new Date(str + 'T00:00')) }];
      if (c.length === 1 && str.includes(':')) {
        const today = new Date().toISOString().split('T')[0];
        return [{ type: 'value', value: vitroDateFormat(l, 'time').format(new Date(today + 'T' + str)) }];
      }
      return [{ type: 'value', value: str }];
    } catch (e) {
      return [{ type: 'value', value: str }];
    }
  }
  if (VITRO_TIMESTAMP_DOMAINS.has(domain) || (domain === 'sensor' && VITRO_TIMESTAMP_CLASSES.includes(attributes.device_class))) {
    try {
      return [{ type: 'value', value: vitroDateFormat(haLocale, 'dateTime').format(new Date(str)) }];
    } catch (e) {
      return [{ type: 'value', value: str }];
    }
  }
  // HA's last resort is the state as it is: for a domain whose labels HA's frontend has and this one has too (a state
  // they don't name). The page has only the domains the panel read; for another, HA would have its label, which is
  // most often the state in words ("Not home" for not_home).
  return [{ type: 'value', value: vitroStateLabel(stateObj, str) || (vitroHasDomainLabels(domain) ? str : vitroTitle(str)) }];
}

// The domains whose state labels (component.<domain>.entity_component.…) the page's strings hold, made again when they
// change.
let vitroLabelDomains = null;
let vitroLabelDomainsOf = null;
function vitroHasDomainLabels(domain) {
  if (vitroLabelDomainsOf !== haStrings) {
    vitroLabelDomainsOf = haStrings;
    vitroLabelDomains = new Set();
    for (const k of Object.keys(haStrings)) {
      const m = /^component\.([a-z0-9_]+)\.entity_component\./.exec(k);
      if (m) vitroLabelDomains.add(m[1]);
    }
  }
  return vitroLabelDomains.has(domain);
}

function vitroFormatState(stateObj, state) {
  return vitroStateParts(stateObj, state).map((p) => p.value).join('');
}

// HA's attribute formatters (common/entity/compute_attribute_display.ts and data/entity/entity_attributes.ts,
// frontend 20261028.5): numbers with HA's per-domain units and formatters, temperatures in the config's unit, and
// values and names in HA's translations. Dates are left as HA's translations or the value (a later port).
const VITRO_ATTRIBUTE_UNITS = {
  climate: { humidity: '%', current_humidity: '%', target_humidity_low: '%', target_humidity_high: '%', target_humidity_step: '%', min_humidity: '%', max_humidity: '%' },
  cover: { current_position: '%', current_tilt_position: '%' },
  fan: { percentage: '%' },
  humidifier: { humidity: '%', current_humidity: '%', min_humidity: '%', max_humidity: '%', target_humidity_step: '%' },
  light: { color_temp: 'mired', max_mireds: 'mired', min_mireds: 'mired', color_temp_kelvin: 'K', min_color_temp_kelvin: 'K', max_color_temp_kelvin: 'K', brightness: '%' },
  sun: { azimuth: '°', elevation: '°' },
  valve: { current_position: '%' },
  sensor: { battery_level: '%' },
  media_player: { volume_level: '%' },
};
const VITRO_ATTRIBUTE_FORMATTERS = {
  light: { brightness: (v) => Math.round((v / 255) * 100).toString() },
  media_player: { volume_level: (v) => Math.round(v * 100).toString() },
  input_datetime: { year: (v) => v.toString() },
};
const VITRO_TEMPERATURE_ATTRIBUTES = new Set(['temperature', 'current_temperature', 'target_temperature', 'target_temp_temp',
  'target_temp_high', 'target_temp_low', 'target_temp_step', 'min_temp', 'max_temp']);
function vitroWeatherUnit(stateObj, attr) {
  const a = stateObj.attributes || {};
  const units = (haConfig && haConfig.unit_system) || {};
  const length = units.length || '';
  switch (attr) {
    case 'visibility': return a.visibility_unit || length;
    case 'precipitation': return a.precipitation_unit || (length === 'km' ? 'mm' : 'in');
    case 'pressure': return a.pressure_unit || (length === 'km' ? 'hPa' : 'inHg');
    case 'apparent_temperature': case 'dew_point': case 'temperature': case 'templow': return a.temperature_unit || units.temperature;
    case 'wind_gust_speed': case 'wind_speed': return a.wind_speed_unit || length + '/h';
    case 'cloud_coverage': case 'humidity': case 'precipitation_probability': return '%';
    default: return a[attr + '_unit'] || '';
  }
}
function vitroAttributeParts(stateObj, attr, value) {
  const v = value !== undefined ? value : (stateObj && stateObj.attributes ? stateObj.attributes[attr] : undefined);
  if (v === null || v === undefined) return [{ type: 'value', value: vitroLocalize('state.default.unknown') || 'Unknown' }];
  const id = String((stateObj && stateObj.entity_id) || '');
  const domain = id.split('.')[0];
  if (attr === 'device_class' && typeof v === 'string') {
    const name = vitroLocalize('component.' + domain + '.entity_component.' + v + '.name');
    if (name) return [{ type: 'value', value: name }];
  }
  if (typeof v === 'number') {
    const formatter = VITRO_ATTRIBUTE_FORMATTERS[domain] && VITRO_ATTRIBUTE_FORMATTERS[domain][attr];
    const shown = formatter ? formatter(v) : vitroFormatNumber(v);
    let unit = VITRO_ATTRIBUTE_UNITS[domain] && VITRO_ATTRIBUTE_UNITS[domain][attr];
    if (domain === 'weather') unit = vitroWeatherUnit(stateObj, attr);
    else if (VITRO_TEMPERATURE_ATTRIBUTES.has(attr)) unit = haConfig && haConfig.unit_system ? haConfig.unit_system.temperature : undefined;
    const parts = [{ type: 'value', value: shown }];
    if (unit) {
      const blank = vitroBlankBeforeUnit(unit);
      if (blank) parts.push({ type: 'literal', value: blank });
      parts.push({ type: 'unit', value: unit });
    }
    return parts;
  }
  if ((Array.isArray(v) && v.some((x) => x instanceof Object)) || (!Array.isArray(v) && v instanceof Object)) {
    return [{ type: 'value', value: JSON.stringify(v) }];
  }
  if (Array.isArray(v)) return [{ type: 'value', value: v.map((x) => vitroFormatAttribute(stateObj, attr, x)).join(', ') }];
  const entry = registries.entities[id];
  const dc = stateObj && stateObj.attributes ? stateObj.attributes.device_class : undefined;
  const shown = (entry && entry.translation_key && vitroLocalize('component.' + entry.platform + '.entity.' + domain + '.' + entry.translation_key + '.state_attributes.' + attr + '.state.' + v)) ||
    (dc && vitroLocalize('component.' + domain + '.entity_component.' + dc + '.state_attributes.' + attr + '.state.' + v)) ||
    vitroLocalize('component.' + domain + '.entity_component._.state_attributes.' + attr + '.state.' + v) ||
    v;
  return [{ type: 'value', value: String(shown) }];
}
function vitroFormatAttribute(stateObj, attr, value) {
  return vitroAttributeParts(stateObj, attr, value).map((p) => p.value).join('');
}
function vitroAttributeName(stateObj, attr) {
  const id = String((stateObj && stateObj.entity_id) || '');
  const domain = id.split('.')[0];
  const entry = registries.entities[id];
  const dc = stateObj && stateObj.attributes ? stateObj.attributes.device_class : undefined;
  return (entry && entry.translation_key && vitroLocalize('component.' + entry.platform + '.entity.' + domain + '.' + entry.translation_key + '.state_attributes.' + attr + '.name')) ||
    (dc && vitroLocalize('component.' + domain + '.entity_component.' + dc + '.state_attributes.' + attr + '.name')) ||
    vitroLocalize('component.' + domain + '.entity_component._.state_attributes.' + attr + '.name') ||
    vitroLocalize('ui.components.attributes.names.' + attr) ||
    vitroTitle(String(attr).replace(/_/g, ' ').replace(/\bid\b/g, 'ID').replace(/\bip\b/g, 'IP').replace(/\bmac\b/g, 'MAC').replace(/\bgps\b/g, 'GPS'));
}

function vitroCallWS(msg) {
  return new Promise((resolve, reject) => {
    const id = wsId++;
    const entry = { resolve, reject, sent: false };
    wsPending.set(id, entry);
    entry.sent = sendBridge({ type: 'callWs', id, msg: msg || {} }, id);
    if (!entry.sent) {
      setTimeout(() => {
        if (entry.sent || wsPending.get(id) !== entry) return;
        wsPending.delete(id);
        const at = outQueue.findIndex((q) => q.id === id);
        if (at >= 0) outQueue.splice(at, 1);
        reject(new Error('Not connected'));
      }, QUEUED_CALL_MS);
    }
  });
}

// Same-origin: the island server proxies /api/... to Home Assistant with the panel's Bearer token.
function vitroCallApiRaw(method, path, parameters, headers) {
  const init = { method: String(method || 'GET').toUpperCase(), headers: Object.assign({}, headers || {}) };
  if (parameters !== undefined && parameters !== null && init.method !== 'GET') {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(parameters);
  }
  return fetch(VITRO_BASE + '/api/' + String(path || '').replace(/^\/+/, ''), init);
}

async function vitroCallApi(method, path, parameters, headers) {
  const r = await vitroCallApiRaw(method, path, parameters, headers);
  let body = null;
  if (r.status !== 204 && r.status !== 205) {
    const ct = r.headers.get('content-type') || '';
    const text = await r.text();
    body = text;
    if (ct.includes('application/json') && text) { try { body = JSON.parse(text); } catch (e) {} }
  }
  if (!r.ok) throw { error: (body && body.message) || ('Request failed: ' + r.status), status_code: r.status, body };
  return body;
}

// hass.connection, as HA's own client (home-assistant-js-websocket's Connection) gives it to cards: commands and
// subscriptions go over the bridge to the panel's one connection. A subscription the panel starts on HA's connection
// is followed by its id (sid); the bridge sends its events, and the page asks for each again on a new bridge.
let haConnected = true;
// The panel's HA connection the page last heard of (`conn`'s epoch), and whether a new bridge still has to ask for the
// page's subscriptions (it does at its first `conn`).
let connEpoch = null;
let replayOnConn = false;
// Counts the connection's changes: a reconnect's preCheck answers for that reconnect only.
let connGeneration = 0;
const connListeners = new Map();
const pageSubs = new Map();
let nextSid = 1;
function sendIfOpen(obj) {
  if (!sock || sock.readyState !== WebSocket.OPEN) return false;
  sock.send(JSON.stringify(obj) + '\n');
  return true;
}
function subscribeLine(sid, entry) { return { type: 'subscribe', sid, msg: entry.msg, resubscribe: entry.resubscribe }; }
async function vitroSubscribeMessage(callback, message, options) {
  if (options && typeof options.preCheck === 'function' && !(await options.preCheck())) throw new Error('Pre-check failed');
  const sid = nextSid++;
  const entry = {
    callback, msg: Object.assign({}, message || {}), resubscribe: !options || options.resubscribe !== false, acked: false,
    preCheck: options && typeof options.preCheck === 'function' ? options.preCheck : null,
  };
  await new Promise((resolve, reject) => {
    entry.resolve = resolve;
    entry.reject = reject;
    pageSubs.set(sid, entry);
    // Sent now, or when the bridge opens (it sends every subscription the page holds).
    sendIfOpen(subscribeLine(sid, entry));
  });
  return async () => {
    if (pageSubs.get(sid) !== entry) return;
    pageSubs.delete(sid);
    sendIfOpen({ type: 'unsubscribe', sid });
  };
}
function fireConn(type, data) {
  const list = connListeners.get(type);
  if (!list) return;
  for (const cb of Array.from(list)) {
    // A listener that throws is the card's error, as in HA's client: the others still run.
    try { cb(vitroConnection, data); } catch (e) { setTimeout(() => { throw e; }, 0); }
  }
}
const vitroConnection = {
  get haVersion() { return String((haConfig && haConfig.version) || ''); },
  get connected() { return haConnected; },
  subscribeMessage: vitroSubscribeMessage,
  subscribeEvents(callback, eventType) {
    const msg = { type: 'subscribe_events' };
    if (eventType) msg.event_type = eventType;
    return vitroSubscribeMessage(callback, msg);
  },
  sendMessage(message) { vitroCallWS(message).catch(() => {}); },
  sendMessagePromise(message) { return vitroCallWS(message); },
  ping() { return vitroCallWS({ type: 'ping' }); },
  addEventListener(type, cb) {
    if (typeof cb !== 'function') return;
    if (!connListeners.has(type)) connListeners.set(type, new Set());
    connListeners.get(type).add(cb);
  },
  removeEventListener(type, cb) {
    const list = connListeners.get(type);
    if (list) list.delete(cb);
  },
  fireEvent(type, data) { fireConn(type, data); },
  // The panel's own connection: a card can't close, suspend or reconnect it.
  close() {},
  reconnect() {},
  suspend() {},
  suspendReconnectUntil() {},
};

// HA's loadBackendTranslation: frontend/get_translations for a category (and integrations), merged into the strings.
// Each request goes once; it resolves with localize, which changes when new strings came (the card gets a new hass).
const backendAsked = new Map();
function vitroLoadBackendTranslation(category, integrations, configFlow) {
  const list = integrations === undefined || integrations === null ? null
    : (Array.isArray(integrations) ? integrations : [integrations]).map(String).sort();
  const flow = configFlow === undefined ? '' : String(!!configFlow);
  const key = category + '|' + (list ? list.join(',') : '*') + '|' + flow;
  const all = backendAsked.get(category + '|*|' + flow);
  let p = backendAsked.get(key) || (list ? all : undefined);
  if (!p) {
    const msg = { type: 'frontend/get_translations', language: haLocale.language, category: String(category) };
    if (list) msg.integration = list;
    if (configFlow !== undefined) msg.config_flow = !!configFlow;
    p = vitroCallWS(msg).then((r) => {
      const res = r && r.resources;
      if (!res || typeof res !== 'object') return;
      // A new map when anything changed: a hass handed out earlier keeps the strings it had (hass.resources).
      const changes = Object.keys(res).filter((k) => typeof res[k] === 'string' && haStrings[k] !== res[k]);
      if (!changes.length) return;
      const next = Object.assign({}, haStrings);
      for (const k of changes) next[k] = res[k];
      haStrings = next;
      stringsChanged();
    }, () => { backendAsked.delete(key); });
    backendAsked.set(key, p);
  }
  // HA's never rejects for a card: a failed request leaves the strings as they were, and is asked again next time.
  return p.then(() => hassBase.localize, () => hassBase.localize);
}
function stringsChanged() {
  hassBase.localize = makeLocalize();
  if (cardEl) feedCard();
}

// hass.services, as HA's frontend has them: read (once) when a card first reads them, then again when an integration
// registers or removes one, and after a reconnect.
let haServices = {};
let servicesAsked = false;
let servicesTimer = null;
function loadServices() {
  vitroCallWS({ type: 'get_services' }).then((r) => {
    if (!r || typeof r !== 'object') return;
    haServices = r;
    if (cardEl) feedCard();
  }, () => {});
}
function askServices() {
  if (servicesAsked) return;
  servicesAsked = true;
  // The listeners first, then the list: a service registered in between is in one or the other. A listener HA
  // refuses still leaves the list read once.
  const later = () => { clearTimeout(servicesTimer); servicesTimer = setTimeout(loadServices, 500); };
  const listen = (type) => vitroConnection.subscribeEvents(later, type).catch(() => {});
  Promise.all([listen('service_registered'), listen('service_removed')]).then(loadServices, loadServices);
}

// hass.auth's refreshAccessToken: the page asks for the token the panel holds now (the panel renews its own).
// Each request has its id, and only the reply that names it settles it: an `auth` the page gets for another reason (a
// seed queued before the request, a new token) doesn't.
const tokenWaiters = new Map();
let refreshIds = 0;
function vitroRefreshToken() {
  return new Promise((resolve) => {
    const rid = ++refreshIds;
    tokenWaiters.set(rid, resolve);
    sendBridge({ type: 'refreshToken', rid });
    setTimeout(() => {
      if (tokenWaiters.delete(rid)) resolve();
    }, QUEUED_CALL_MS);
  });
}
// One Auth for the page, as HA's client keeps one: a card that holds on to it reads the token the panel holds now.
const vitroAuth = {
  data: { get access_token() { return accessToken; }, hassUrl: location.origin },
  get accessToken() { return accessToken; },
  get expired() { return false; },
  refreshAccessToken: vitroRefreshToken,
};
function authObject() { return vitroAuth; }

// hass.user until Home Assistant says who signed in, and when it doesn't know.
const ANON_USER = { id: 'vitro', name: 'Panel', is_admin: false, is_owner: false };

const hassBase = {
  config: haConfig,
  language: haLocale.language,
  selectedLanguage: haLocale.language,
  translationMetadata: { fragments: [], translations: { [haLocale.language]: $langMeta } },
  locale: haLocale,
  loadBackendTranslation: vitroLoadBackendTranslation,
  // The panel has HA's Lovelace strings with its own (IslandStrings): every fragment is loaded.
  loadFragmentTranslation() { return Promise.resolve(hassBase.localize); },
  themes: { darkMode: ${ctx.theme.dark}, default_theme: ${JSONObject.quote(ctx.theme.name)}, default_dark_theme: null, theme: ${JSONObject.quote(ctx.theme.name)}, themes: {} },
  selectedTheme: null,
  user: ANON_USER,
  userData: {},
  systemData: {},
  panels: {},
  // HA's UI on the panel: the dashboard alone, no sidebar and no shortcuts.
  panelUrl: $panelJson,
  dockedSidebar: 'always_hidden',
  kioskMode: false,
  enableShortcuts: false,
  vibrate: true,
  suspendWhenHidden: true,
  debugConnection: false,
  auth: authObject(),
  hassUrl: (path) => path ? new URL(path, location.origin).href : location.origin,
  // HA's own call_service over the bridge: the promise settles with Home Assistant's answer.
  callService(domain, service, data, target, notifyOnError, returnResponse) {
    const msg = { type: 'call_service', domain, service, service_data: data || {} };
    if (target) msg.target = target;
    if (returnResponse) msg.return_response = true;
    return vitroCallWS(msg);
  },
  callWS(msg) { return vitroCallWS(msg); },
  sendWS(msg) { vitroCallWS(msg).catch(() => {}); },
  callApi: vitroCallApi,
  callApiRaw: vitroCallApiRaw,
  // A path on Home Assistant goes through the proxy under the capability; a full URL as it is.
  fetchWithAuth(path, init) {
    const p = typeof path === 'string' && path.startsWith('/') && !path.startsWith('//') ? VITRO_BASE + path : path;
    return fetch(p, init || {});
  },
  localize: makeLocalize(),
  formatEntityState: vitroFormatState,
  formatEntityStateToParts: vitroStateParts,
  formatEntityAttributeValue: vitroFormatAttribute,
  formatEntityAttributeValueToParts: vitroAttributeParts,
  formatEntityAttributeName: vitroAttributeName,
  formatEntityName: vitroFormatEntityName,
  connection: vitroConnection,
};
if (haUser) hassBase.user = haUser;

// A new object on every patch: Lit cards compare hass by reference to decide whether to re-render. Each hass keeps
// the registries it was made with, as HA's does: a card comparing the old one's with the new one's sees the change.
function makeHass() {
  const h = Object.assign({}, hassBase, { states: currentStates(), connected: haConnected });
  // Services and HA's strings by language (older cards read `resources`), as this hass was made.
  const services = haServices;
  Object.defineProperty(h, 'services', { enumerable: true, configurable: true, get() { askServices(); return services; } });
  const strings = haStrings;
  Object.defineProperty(h, 'resources', { enumerable: false, configurable: true, get() { return { [haLocale.language]: strings }; } });
  const snapshot = Object.assign({}, registries);
  for (const key of Object.keys(snapshot)) {
    Object.defineProperty(h, key, {
      enumerable: true,
      configurable: true,
      get() {
        askRegistries();
        return snapshot[key];
      },
    });
  }
  window.__vitroHass = h;
  return h;
}

const tag = ${JSONObject.quote(tag)};
const cfg = $cfgJson;
let cardEl = null;

function connectBridge() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  sock = new WebSocket(proto + '//' + location.host + VITRO_BASE + '/__vitro/ws/' + ${JSONObject.quote(slotId)});
  sock.onopen = () => {
    bridgeOpened = true;
    while (outQueue.length) sock.send(outQueue.shift().line);
    for (const entry of wsPending.values()) entry.sent = true;
    // Every subscription the page holds, on this bridge (a new one, or the one after a hand-over), once the bridge
    // says where HA's connection is (its first `conn`).
    replayOnConn = true;
  };
  sock.onmessage = (ev) => {
    const lines = String(ev.data || '').split('\n').filter(Boolean);
    for (const line of lines) {
      let m;
      try { m = JSON.parse(line); } catch (e) { continue; }
      if (m.type === 'auth' && typeof m.token === 'string') {
        // The sign-in now: a reconnect may have refreshed it since the page loaded, or another account signed in.
        let changed = false;
        if (m.token !== accessToken) {
          accessToken = m.token;
          hassBase.auth = authObject();
          changed = true;
        }
        // A user not known (yet) for this sign-in: the anonymous panel, never the previous account.
        const user = (m.user && typeof m.user === 'object') ? m.user : ANON_USER;
        if (JSON.stringify(user) !== JSON.stringify(hassBase.user)) {
          hassBase.user = user;
          changed = true;
        }
        if (changed && cardEl) feedCard();
        const waiter = typeof m.rid === 'number' ? tokenWaiters.get(m.rid) : undefined;
        if (waiter) { tokenWaiters.delete(m.rid); waiter(); }
      } else if (m.type === 'conn') {
        // The panel's connection to HA went down or is back (its subscriptions start again on the panel's side).
        const up = !!m.connected;
        const epoch = typeof m.epoch === 'number' ? m.epoch : connEpoch;
        if (replayOnConn) {
          replayOnConn = false;
          // HA's connection changed while this page had no bridge: as HA's client, a subscription that started and
          // doesn't resubscribe ended with it (the panel ended its side then). The others are asked for again.
          const changedMeanwhile = connEpoch !== null && epoch !== connEpoch;
          for (const [sid, entry] of Array.from(pageSubs)) {
            if ((changedMeanwhile || !up) && entry.acked && !entry.resubscribe) { pageSubs.delete(sid); continue; }
            sendIfOpen(subscribeLine(sid, entry));
          }
        }
        connEpoch = epoch;
        if (up !== haConnected) {
          haConnected = up;
          // HA's client asks a subscription's preCheck again before it resubscribes: one that says no now ends.
          connGeneration++;
          // The answer of this reconnect's check only: an earlier one that settles late changes nothing.
          if (up) for (const [sid, entry] of Array.from(pageSubs)) {
            if (!entry.preCheck) continue;
            const gen = connGeneration;
            Promise.resolve().then(() => entry.preCheck()).then((ok) => {
              if (ok || gen !== connGeneration || pageSubs.get(sid) !== entry) return;
              pageSubs.delete(sid);
              sendIfOpen({ type: 'unsubscribe', sid });
              // Never started: the card's subscribeMessage fails, as HA's does when its pre-check says no.
              if (!entry.acked) entry.reject(new Error('Pre-check failed'));
            }, () => {});
          }
          fireConn(up ? 'ready' : 'disconnected');
          if (up && servicesAsked) loadServices();
          if (cardEl) feedCard();
        }
      } else if (m.type === 'subAck') {
        const entry = pageSubs.get(m.sid);
        if (entry && !entry.acked) { entry.acked = true; entry.resolve(); }
      } else if (m.type === 'subEvent') {
        const entry = pageSubs.get(m.sid);
        // A callback that throws is the card's error (the page's error handler hears it); the next lines still run.
        if (entry) { try { entry.callback(m.event); } catch (e) { setTimeout(() => { throw e; }, 0); } }
      } else if (m.type === 'subError') {
        const entry = pageSubs.get(m.sid);
        if (entry) {
          pageSubs.delete(m.sid);
          if (!entry.acked) entry.reject(m.error || { code: 'unknown_error', message: 'Subscription failed' });
        }
      } else if (m.type === 'subEnd') {
        // Not resubscribed after a reconnect, as in HA's client: it just ends.
        pageSubs.delete(m.sid);
      } else if (m.type === 'theme') {
        // The card's theme now (a theme change kept this page): its variables, and hass.themes for the card.
        const style = document.getElementById('vitro-theme');
        if (style && typeof m.css === 'string') style.textContent = m.css;
        hassBase.themes = Object.assign({}, hassBase.themes, { darkMode: !!m.dark, default_theme: String(m.name || '') });
        if (cardEl) feedCard();
      } else if (m.type === 'config' && m.config && typeof m.config === 'object') {
        // HA's config as the panel has it now (a page made before it arrived had none): hass.config follows it.
        haConfig = m.config;
        hassBase.config = haConfig;
        if (Object.keys(haConfig).length) configArrived();
        if (cardEl) feedCard();
      } else if (m.type === 'snapshot' && m.states) {
        // A (re)connect: the whole store, replacing what the island had. Not `complete` while HA hasn't answered for
        // the card's entities yet: the card isn't made with states the store doesn't have yet, and the panel sends
        // the whole store again once HA's answer is in.
        for (const id of Object.keys(rawStates)) delete rawStates[id];
        Object.assign(rawStates, m.states);
        statesView = null;
        if (m.complete !== false) statesArrived();
        if (cardEl) feedCard();
      } else if (m.type === 'patch' && m.states) {
        for (const id of Object.keys(m.states)) {
          if (m.states[id] === null) delete rawStates[id];
          else rawStates[id] = m.states[id];
        }
        statesView = null;
        if (cardEl) feedCard();
      } else if (m.type === 'registries' && m.registries) {
        // New objects every time: Lit cards compare them by reference.
        for (const key of Object.keys(registries)) registries[key] = m.registries[key] || {};
        registriesIn = true;
        if (cardEl) feedCard();
      } else if (m.type === 'wsReply') {
        const entry = wsPending.get(m.id);
        if (!entry) continue;
        wsPending.delete(m.id);
        if (m.ok) entry.resolve(m.result);
        else entry.reject(m.error || new Error('callWS failed'));
      }
    }
  };
  sock.onclose = () => {
    // A call that went out on this socket gets no answer now: reject it (never resend, HA may have run it).
    for (const [id, entry] of wsPending) {
      if (entry.sent) { wsPending.delete(id); entry.reject(new Error('Connection lost')); }
    }
    // A bridge that was open closes when the card hands this page to its next build: connect again at once. One
    // that never opened waits.
    const quick = bridgeOpened;
    bridgeOpened = false;
    setTimeout(retryBridge, quick ? 50 : 1500);
  };
}

// Connects again unless the slot is gone: a closed island's page stops here instead of retrying for good.
function retryBridge() {
  fetch(VITRO_BASE + '/__vitro/ws/' + ${JSONObject.quote(slotId)}, { cache: 'no-store' }).then(
    (r) => { if (r.status !== 404) connectBridge(); },
    () => setTimeout(retryBridge, 1500),
  );
}

// The safety net for what an island page lacks that no list names: a card that throws a TypeError (what a missing
// `hass` member, or a missing method of an HA element, throws) and has drawn nothing after a grace goes to HA's
// frontend (cardBroken) instead of staying an empty card. The member it stopped on goes to the log.
const BROKEN_GRACE_MS = 1500;
let brokenSent = false;
let brokenTimer = null;
function vitroMissingMember(err) {
  if (!err || !(err instanceof TypeError || err.name === 'TypeError')) return null;
  const msg = String(err.message || '');
  const m = msg.match(/([\w$.]+) is not a function/) || msg.match(/([\w$.]+) is (?:undefined|null)\b/) ||
    msg.match(/reading '([^']+)'/) || msg.match(/([\w$.]+) is not iterable/);
  if (!m) return '';
  const parts = m[1].split('.');
  return parts[parts.length - 1];
}
// The card drew nothing: it isn't in the page, or it takes no room. The card, not #mount, which a full-viewport
// island holds at the screen's height even when it's empty.
// What the card drew: its own box, or its content's (a custom element is inline unless it says otherwise, and an
// inline host around a block card measures nothing).
// What the card drew, never its host's own box: a host can have a height of its own (`:host { height }`) before its
// content exists. Drawn means an element inside it takes room, or it shows text (the host then holds it).
function vitroCardBlank() {
  if (!cardEl || !cardEl.isConnected) return true;
  const content = (cardEl.shadowRoot ? Array.from(cardEl.shadowRoot.children || []) : []).concat(Array.from(cardEl.children || []));
  const room = (el) => el && el.localName !== 'style' && el.getBoundingClientRect && Math.ceil(el.getBoundingClientRect().height || 0) > 0;
  if (content.some(room)) return false;
  const text = ((cardEl.shadowRoot && cardEl.shadowRoot.textContent) || '') + (cardEl.textContent || '');
  return !(text.trim() && room(cardEl));
}
// Every update failing on the same missing member: the card shows what it drew before (HA's "Entity not found" from
// a first render, say), never what it should. Handed over too, drawn or not. Counted per update the page gives the
// card (feedCard): the same named member failing in BROKEN_REPEATS updates in a row. Other errors, or an update with
// none, start the count again; an unnamed TypeError only counts for the blank-card grace above.
const BROKEN_REPEATS = 3;
let cardUpdate = 0;
let streakMember = null;
let streakUpdates = new Set();
let threwThisUpdate = false;
function feedCard() {
  // The update before this one ran without failing: the card works, whatever it threw earlier.
  if (cardUpdate > 0 && !threwThisUpdate) { streakMember = null; streakUpdates = new Set(); }
  cardUpdate += 1;
  threwThisUpdate = false;
  cardEl.hass = makeHass();
}
function vitroOnCardThrow(err) {
  if (brokenSent || !cardEl) return;
  const member = vitroMissingMember(err);
  if (member === null) return;
  if (member) {
    threwThisUpdate = true;
    if (member !== streakMember) { streakMember = member; streakUpdates = new Set(); }
    streakUpdates.add(cardUpdate);
    if (streakUpdates.size >= BROKEN_REPEATS) {
      brokenSent = true;
      if (brokenTimer) { clearTimeout(brokenTimer); brokenTimer = null; }
      sendBridge({ type: 'cardBroken', member });
      return;
    }
  }
  if (brokenTimer) return;
  brokenTimer = setTimeout(() => {
    brokenTimer = null;
    if (brokenSent || !vitroCardBlank()) return;
    brokenSent = true;
    sendBridge({ type: 'cardBroken', member });
  }, BROKEN_GRACE_MS);
}
window.addEventListener('error', (ev) => vitroOnCardThrow(ev && ev.error));
window.addEventListener('unhandledrejection', (ev) => vitroOnCardThrow(ev && ev.reason));

// The first states and config from the panel: the card is made once they're here, as HA's frontend draws a card
// once hass has them. A card that drew first with no states would show HA's "Entity not found" for every entity.
let statesIn = false;
let statesWaiters = [];
function statesArrived() { statesIn = true; statesWaiters.splice(0).forEach((done) => done()); }
let configIn = Object.keys(haConfig || {}).length > 0;
let configWaiters = [];
function configArrived() { configIn = true; configWaiters.splice(0).forEach((done) => done()); }
const untilStates = () => (statesIn ? Promise.resolve() : new Promise((done) => statesWaiters.push(done)));
const untilConfig = () => (configIn ? Promise.resolve() : new Promise((done) => configWaiters.push(done)));
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));
// How long the page waits for its states before it says so (it keeps waiting), and for HA's config.
const STATES_NOTICE_MS = 8000;
const CONFIG_WAIT_MS = 3000;

// An honest notice in the card's place while the panel's states don't reach it: never a blank card, never a card
// drawn with no states. HA's own words for it when the page has them.
function showWaiting() {
  const note = document.createElement('ha-card');
  note.className = 'vitro-fallback';
  note.id = 'vitro-waiting';
  note.setAttribute('role', 'status');
  note.textContent = vitroLocalize('ui.notification_toast.connection_lost') || 'Connection lost. Reconnecting…';
  document.getElementById('mount').appendChild(note);
}

async function boot() {
  connectBridge();
  // The card's script, and HA's strings for its localize (a card reads them on its first render).
  try { await Promise.race([Promise.all([customElements.whenDefined(tag), stringsReady]), new Promise(r => setTimeout(r, 4000))]); } catch (e) {}
  // HA's createCardElement: a custom element that isn't defined in time, or a setConfig that throws, is HA's error
  // card ("Custom element doesn't exist: <tag>.", or what setConfig threw). The island says so, and its card becomes
  // that error card natively, instead of an empty page (#885: a card type its script no longer defines).
  // A script that is only slow (a cold cache, a big bundle) still defines it: as HA's createCardElement, the page
  // keeps waiting, and the card is made when it arrives (HA's ll-rebuild). The panel shows HA's error card meanwhile
  // (`waiting`) and decides how long it keeps the page for it.
  if (!customElements.get(tag)) {
    sendBridge({ type: 'cardError', message: "Custom element doesn't exist: " + tag + '.', waiting: true });
    await customElements.whenDefined(tag);
    sendBridge({ type: 'cardDefined' });
  }
  if (!statesIn) {
    const shown = await Promise.race([untilStates().then(() => true), sleep(STATES_NOTICE_MS).then(() => false)]);
    if (!shown) {
      showWaiting();
      wireResize();
      await untilStates();
      const note = document.getElementById('vitro-waiting');
      if (note) note.remove();
    }
  }
  await Promise.race([untilConfig(), sleep(CONFIG_WAIT_MS)]);
  const el = document.createElement(tag);
  try {
    if (typeof el.setConfig === 'function') el.setConfig(cfg);
  } catch (e) {
    sendBridge({ type: 'cardError', message: String((e && e.message) || e) });
    return;
  }
  cardEl = el;
  feedCard();
  document.getElementById('mount').appendChild(cardEl);
  wireEvents();
  if (!resizeWired) wireResize();
  settleCard(cardEl);
  // HA's own elements the card draws with that this page lacks (frontend-watch.js): its view goes to HA's frontend.
  vitroWatchFrontend(cardEl, { report: (tags, cards) => sendBridge({ type: 'needsFrontend', tags, cards }) });
}

function moreInfo(id) {
  if (id) sendBridge({ type: 'moreInfo', entity_id: id });
}

function navigateTo(path, replace) {
  // Never the island's own address: only a dashboard path a card navigated to, or an outside page's full URL, which
  // Vitro opens as a page (never in this island's own window). `replace`: HA's navigate(path, {replace: true}).
  if (path && typeof path === 'string' && !path.startsWith('/__vitro/')) sendBridge({ type: 'navigate', path, replace: replace === true });
}

// A link the card's user tapped: the island's window never leaves the card for it. One to another site goes to
// Vitro whole (it shows the page as it is); one on this page's origin is a path on Home Assistant, as on HA's page.
function linkTarget(ev) {
  const path = ev && typeof ev.composedPath === 'function' ? ev.composedPath() : [];
  for (const el of path) {
    if (!el || String(el.localName || '').toLowerCase() !== 'a' || !el.href) continue;
    let u;
    try { u = new URL(String(el.href), location.href); } catch (e) { return null; }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
    // A fragment of this very document (a card's own table of contents): the browser scrolls to it, as on HA's page.
    if (u.origin === location.origin && u.pathname === location.pathname && u.search === location.search && u.hash) return null;
    return u.origin === location.origin ? vitroHaPath(u.pathname) + u.search + u.hash : u.href;
  }
  return null;
}

// HA's handleAction for a `hass-action` event: the card says which gesture, its config says what it does.
function runAction(config, gesture) {
  if (!config) return;
  const action = config[(gesture || 'tap') + '_action'] || (gesture === 'tap' || !gesture ? { action: 'more-info' } : null);
  if (!action) return;
  const entity = action.entity || config.entity;
  // A confirmation is asked natively, as for native cards: Cancel sends nothing.
  if (action.confirmation && action.action !== 'none') {
    sendBridge({ type: 'action', action, entity: entity || '' });
    return;
  }
  switch (action.action) {
    case 'more-info': moreInfo(entity); break;
    case 'navigate': navigateTo(action.navigation_path, action.navigation_replace === true); break;
    case 'toggle': if (entity) hassBase.callService('homeassistant', 'toggle', { entity_id: entity }); break;
    case 'call-service':
    case 'perform-action': {
      const svc = String(action.perform_action || action.service || '');
      const dot = svc.indexOf('.');
      if (dot > 0) hassBase.callService(svc.slice(0, dot), svc.slice(dot + 1), action.data || action.service_data, action.target);
      break;
    }
    // Opened as a native card's `url` action opens it (the panel's page, with back), never in this card's own page.
    case 'url': if (action.url_path) sendBridge({ type: 'action', action, entity: entity || '' }); break;
    default: break;
  }
}

function wireEvents() {
  // HA's fireEvent bubbles and is composed, so window hears what any card or nested card sends.
  window.addEventListener('hass-more-info', (ev) => {
    const id = ev && ev.detail && ev.detail.entityId;
    if (id) sendBridge({ type: 'moreInfo', entity_id: id });
  });
  window.addEventListener('hass-action', (ev) => {
    const d = (ev && ev.detail) || {};
    runAction(d.config, d.action);
  });
  // HA's navigate(): history.pushState (replaceState with `replace`), then `location-changed` on window.
  window.addEventListener('location-changed', (ev) => {
    const p = (ev && ev.detail && ev.detail.path) || (vitroHaPath(location.pathname) + location.search);
    // The page keeps its own address: the one its requests name as their Referer, which holds the capability.
    if (location.pathname + location.search !== VITRO_PAGE) {
      try { history.replaceState(history.state, '', VITRO_PAGE); } catch (e) {}
    }
    navigateTo(vitroHaPath(p), Boolean(ev && ev.detail && ev.detail.replace));
  });
  window.addEventListener('hass-navigate', (ev) => {
    const p = ev && ev.detail && ev.detail.path;
    navigateTo(p);
  });
  // After the card's own handlers: a link the card handles itself (preventDefault) stays the card's.
  window.addEventListener('click', (ev) => {
    if (!ev || ev.defaultPrevented || ev.button > 0) return;
    const target = linkTarget(ev);
    if (!target) return;
    ev.preventDefault();
    navigateTo(target);
  });
}

let resizeWired = false;
function wireResize() {
  resizeWired = true;
  const mount = document.getElementById('mount');
  let timer = null;
  let lastH = -1;
  const reportSize = () => {
    timer = null;
    const h = Math.max(0, Math.ceil(mount.getBoundingClientRect().height || 0));
    // 0 px before the card's first render is a card that hasn't drawn yet: reporting it would collapse the island
    // and flash. After it, 0 px is a card that draws nothing (a wrapper with no match), which HA gives no height.
    if ((h === 0 && !cardSettled) || h === lastH) return;
    lastH = h;
    sendBridge({ type: 'resize', height: h });
  };
  const scheduleResize = () => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(reportSize, 50);
  };
  if (window.ResizeObserver) new ResizeObserver(scheduleResize).observe(mount);
  resizeNow = scheduleResize;
  scheduleResize();
}
// Set once the card had its first render (its updateComplete, or a second after it was made): a 0 px card from then
// on draws nothing, and its island collapses.
let cardSettled = false;
let resizeNow = null;
const CARD_SETTLE_MS = 1000;
function settleCard(el) {
  const first = el && el.updateComplete && typeof el.updateComplete.then === 'function' ? el.updateComplete : sleep(CARD_SETTLE_MS);
  Promise.race([Promise.resolve(first).catch(() => {}), sleep(CARD_SETTLE_MS)]).then(() => {
    cardSettled = true;
    if (resizeNow) resizeNow();
    vitroCardSize(el).then((size) => {
      const n = Number(size);
      sendBridge({ type: 'cardSize', size: Number.isFinite(n) && n >= 0 ? n : 1 });
    });
  });
}
// HA's computeCardSize, the weight its masonry view places a card by: the card's getCardSize(), 1 when it throws, has
// none, or takes more than 500 ms.
function vitroCardSize(el) {
  if (!el || typeof el.getCardSize !== 'function') return Promise.resolve(1);
  try {
    return Promise.race([Promise.resolve(el.getCardSize()), sleep(500).then(() => { throw new Error('timeout'); })]).catch(() => 1);
  } catch (e) {
    return Promise.resolve(1);
  }
}
boot();
</script>
</body>
</html>
""".trimIndent()
        }
    }
}
