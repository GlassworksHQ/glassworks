/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.data.HaLocale
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import dev.glassworks.engine.Page
import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.ViewConfig
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs

/**
 * A card drawn in a browser page of its own (a web island, or the iframe card's frame): what VitroView does with its
 * page as views are built, shown and dropped ([WebIslandCardView] has the details).
 */
interface IslandCard {
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
    /** The senders of the bridge sockets open for this island (a replacement can connect before the old one closes). */
    private val senders = CopyOnWriteArraySet<(JSONObject) -> Unit>()

    /**
     * Orders a connection's snapshot before any patch for it. Declared before `init`: the bridge may connect while
     * the constructor still runs (init registers the slot and loads the page).
     */
    private val seedLock = Any()

    /**
     * Whether the card picks its entities at runtime (`auto-entities` filters, `button-card` JS templates, a card
     * that names none up front): such a card follows every entity in the store, not only the ones it read.
     */
    internal val followsAllEntities: Boolean by lazy {
        config.raw.has("filter") || referencedEntities().isEmpty()
    }

    /** The card walked `hass.states` (`watchAll`): it follows every entity from then on, as [followsAllEntities]. */
    @Volatile private var walksAllStates = false

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
                    get() = buildIslandHtml(ctx, config, fillViewport, slotId = idRef)

                override fun onConnected(send: (JSONObject) -> Unit) {
                    // The snapshot goes first: no patch for this socket can be queued before it (the observer
                    // below takes the same lock). This card's theme before it: a page an earlier build of the card
                    // handed over was drawn in that build's.
                    documentRan = true
                    synchronized(seedLock) {
                        send(themeMessage())
                        send(authMessage(currentToken(), currentUser()))
                        seedInitialStates(send)
                        registries?.let { send(registriesMessage(it)) }
                        senders += send
                    }
                }

                override fun onMessage(msg: JSONObject, send: (JSONObject) -> Unit) {
                    handleIslandMessage(msg, send)
                }

                override fun onDisconnected(send: (JSONObject) -> Unit) {
                    // This socket's sender only: the others open for the slot keep getting patches.
                    synchronized(seedLock) { senders -= send }
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
        send(JSONObject().put("type", "snapshot").put("states", seed))
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
                // Read at runtime, beyond what the config names: the view's subscription takes it in.
                if (added && !followsAllEntities) ctx.watchEntity(id)
            }
            "watchAll" -> {
                // Every change reaches the card now, and the view's subscription takes in every entity, so the
                // states it walks are all of HA's, as in HA's frontend.
                walksAllStates = true
                ctx.watchAllEntities()
            }
            "registries" -> askRegistries()
            "resize" -> {
                if (fillViewport) return
                val cssH = msg.optInt("height", 160).coerceIn(48, 1200)
                val pxH = ctx.px(cssH.toFloat())
                ctx.main.post {
                    val v = islandPage?.view ?: return@post
                    if (v.layoutParams?.height != pxH) {
                        v.layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, pxH)
                    }
                }
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
                // the path is a dashboard path (`/lovelace/1`), handed to Vitro's own navigation.
                val path = msg.optString("path", "").trim()
                if (path.isNotEmpty() && !path.startsWith("/__vitro/")) ctx.main.post { ctx.navigate(path) }
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
        val page = islandPage ?: return null
        val id = slotId ?: return null
        val server = ctx.islandServer ?: return null
        islandGone = true
        detached = true
        releaseRegistries()
        server.replaceSlot(id, PARKED)
        val height = page.view.layoutParams?.height ?: ctx.px(160f)
        removeView(page.view)
        senders.clear()
        islandPage = null
        slotId = null
        // What the page asked for once, and won't ask again: the next card keeps it up.
        return Island(
            keyOf(config, fillViewport), page, id, height, server, walksAllStates, registriesAsked.get(), pageActive,
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
        if (islandGone) return
        islandGone = true
        detached = true
        releaseRegistries()
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

    override fun onAttachedToWindow() {
        val wasDetached = attachedOnce
        attachedOnce = true
        super.onAttachedToWindow()
        ctx.main.removeCallbacks(releaseIfDetached)
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

    companion object {
        /**
         * What makes two builds of a card the same island: its whole config and whether it fills the view. A
         * rebuild that only changed the theme, or other cards, hands the island over ([handOff]) instead of opening
         * a new page.
         */
        fun keyOf(config: CardConfig, fillViewport: Boolean): String = (if (fillViewport) "fill:" else "card:") + config.raw

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
            return if (fillViewport) vars + "html:root { background-color: ${ctx.theme.hex(ctx.theme.backgroundColor)}; }\n" else vars
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
         * [STORAGE_BASE_KEY] with HA's origin: for another Home Assistant, HA's copy wins every key it has.
         */
        internal const val ISLAND_STORAGE_JS = """
function vitroSeedStorage(store, origin, seed) {
  if (!seed || typeof seed !== 'object') return 0;
  const BASE = '__vitro_ha_storage';
  let base = {};
  try {
    const b = JSON.parse(store.getItem(BASE) || 'null');
    if (b && b.origin === origin && b.items && typeof b.items === 'object') base = b.items;
  } catch (e) {}
  let changed = 0;
  // A key the island couldn't store or remove keeps what it took before (or stays out of it), so the next start tries
  // it again and still knows the value the island holds came from HA.
  const failed = new Set();
  const set = (k, v) => { try { store.setItem(k, v); changed++; } catch (e) { failed.add(k); } };
  for (const k of Object.keys(seed)) {
    const v = seed[k];
    if (k === BASE || typeof v !== 'string') continue;
    if (base[k] !== v && store.getItem(k) !== v) set(k, v);
  }
  for (const k of Object.keys(base)) {
    if (k === BASE || Object.prototype.hasOwnProperty.call(seed, k)) continue;
    if (store.getItem(k) === base[k]) { try { store.removeItem(k); changed++; } catch (e) { failed.add(k); } }
  }
  const took = {};
  const had = (k) => Object.prototype.hasOwnProperty.call(base, k);
  for (const k of Object.keys(seed)) {
    if (!failed.has(k)) took[k] = seed[k];
    else if (had(k)) took[k] = base[k];
  }
  for (const k of failed) if (!Object.prototype.hasOwnProperty.call(seed, k) && had(k)) took[k] = base[k];
  try { store.setItem(BASE, JSON.stringify({ origin: origin, items: took })); } catch (e) {}
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

        fun buildIslandHtml(
            ctx: VitroCardContext,
            config: CardConfig,
            fillViewport: Boolean = false,
            slotId: String = "s0",
        ): String {
            val tag = config.type.removePrefix("custom:")
            val themeCss = themeCss(ctx, fillViewport)
            val resourceScripts = ctx.resources.joinToString("\n") { r ->
                val inlineJs = decodeInlineModule(r.url)
                when {
                    inlineJs != null -> "<script type=\"module\">\n${inlineJs.replace(SCRIPT_END, "<\\\\/$1")}\n</script>"
                    r.type == "css" -> """<link rel="stylesheet" href=${htmlAttr(r.url)}>"""
                    r.type == "module" -> """<script type="module" src=${htmlAttr(r.url)}></script>"""
                    else -> """<script src=${htmlAttr(r.url)}></script>"""
                }
            }
            val cfgJson = config.raw.toString()
            val haCfgJson = ctx.haConfig().toString()
            val haUserJson = islandUser(ctx.haUser())?.toString() ?: "null"
            // The user's language and formats, as HA's page holds them in `hass.locale` (HaLocale).
            val haLocaleJson = HaLocale.current.toJson().toString()
            val tokenJson = JSONObject.quote(ctx.accessToken().orEmpty())
            val heightRule = if (fillViewport) "height: 100vh; width: 100vw;" else ""
            return """
<!DOCTYPE html>
<html lang=${htmlAttr(HaLocale.current.language)}>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
${storageSeedScript(ctx)}
<style id="vitro-theme">
$themeCss</style>
<style>
/* The island is the card's whole page, with no HA header above it: a card that takes var(--header-height) off the
   viewport's height fills it, as in HA's kiosk view (the header hidden, its height 0). */
:root { --header-height: 0px; }
html, body { margin: 0; padding: 0; background: transparent; color: var(--primary-text-color); font-family: Inter, system-ui, sans-serif; overflow: hidden; $heightRule }
#mount { width: 100%; ${if (fillViewport) "height: 100vh;" else ""} }
ha-card { display: block; background: var(--ha-card-background, var(--card-background-color)); border-radius: var(--ha-card-border-radius, 12px); color: var(--primary-text-color); }
ha-card.vitro-fallback { padding: 12px 16px; }
.vitro-title { font-size: 1.15em; font-weight: 500; padding-bottom: 8px; }
.vitro-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 6px 0; }
.vitro-state { color: var(--secondary-text-color); }
</style>
<script>
// Minimal HA custom-element shims for GeckoView Micro-Islands (zero HA SPA).
if (!customElements.get('ha-card')) {
  customElements.define('ha-card', class extends HTMLElement {});
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
  _resolve() {
    if (this.icon) return this.icon;
    const st = this._stateObj;
    if (st && st.attributes && st.attributes.icon) return st.attributes.icon;
    const domain = st && st.entity_id ? String(st.entity_id).split('.')[0] : '';
    return VITRO_DOMAIN_ICONS[domain] || 'mdi:home';
  }
  _render() {
    if (!this.isConnected) return;
    this.style.display = 'inline-flex';
    let inner = this.firstElementChild;
    if (!inner || inner.localName !== 'ha-icon') {
      this.textContent = '';
      inner = document.createElement('ha-icon');
      this.appendChild(inner);
    }
    const icon = this._resolve();
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
$resourceScripts
</head>
<body>
<div id="mount"></div>
<script type="module">
const rawStates = {};
const haConfig = $haCfgJson;
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
const statesProxy = new Proxy(rawStates, {
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
});

// hass.entities, devices, areas and floors, as HA's frontend has them: asked for (once) when a card first reads one.
const registries = { entities: {}, devices: {}, areas: {}, floors: {} };
let registriesAsked = false;

function vitroTitle(s) {
  const t = String(s == null ? '' : s).replace(/_/g, ' ');
  return t ? t.charAt(0).toUpperCase() + t.slice(1) : t;
}

function vitroLocalize(key, ...args) {
  if (!key) return '';
  const k = String(key);
  const m = k.match(/^component\.[^.]+\.entity_component\._\.state\.(.+)$/);
  let out = vitroTitle(m ? m[1] : k.split('.').pop());
  // HA passes placeholders as ('name', value, …) pairs or one { name: value } object; {0} is positional.
  const values = {};
  if (args.length === 1 && args[0] && typeof args[0] === 'object') Object.assign(values, args[0]);
  else for (let i = 0; i + 1 < args.length; i += 2) values[args[i]] = args[i + 1];
  args.forEach((a, i) => { if (typeof a !== 'object') values[String(i)] = a; });
  return out.replace(/\{([^}]+)\}/g, (all, name) => (name in values ? String(values[name]) : all));
}

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

function vitroFormatState(stateObj, state) {
  const s = state !== undefined && state !== null ? state : (stateObj ? stateObj.state : '');
  const str = String(s == null ? '' : s);
  const unit = stateObj && stateObj.attributes ? stateObj.attributes.unit_of_measurement : undefined;
  if (unit && str !== 'unavailable' && str !== 'unknown') return vitroFormatNumber(str) + vitroBlankBeforeUnit(unit) + unit;
  return vitroTitle(str);
}

function vitroFormatAttribute(stateObj, attr, value) {
  const v = value !== undefined ? value : (stateObj && stateObj.attributes ? stateObj.attributes[attr] : undefined);
  if (v === undefined || v === null) return '';
  if (Array.isArray(v)) return v.map((x) => (typeof x === 'string' ? vitroTitle(x) : String(x))).join(', ');
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  if (typeof v === 'object') return JSON.stringify(v);
  return vitroTitle(v);
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
  return fetch('/api/' + String(path || '').replace(/^\/+/, ''), init);
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

// hass.user until Home Assistant says who signed in, and when it doesn't know.
const ANON_USER = { id: 'vitro', name: 'Panel', is_admin: false, is_owner: false };

const hassBase = {
  config: haConfig,
  language: haLocale.language,
  selectedLanguage: haLocale.language,
  translationMetadata: { fragments: [], translations: { [haLocale.language]: { nativeName: haLocale.language, isRTL: false } } },
  locale: haLocale,
  themes: { darkMode: ${ctx.theme.dark}, default_theme: ${JSONObject.quote(ctx.theme.name)}, themes: {} },
  selectedTheme: null,
  user: ANON_USER,
  services: {},
  panels: {},
  resources: {},
  auth: { data: { access_token: accessToken, hassUrl: location.origin }, accessToken },
  connected: true,
  hassUrl: (path) => path ? new URL(path, location.origin).href : location.origin,
  // HA's own call_service over the bridge: the promise settles with Home Assistant's answer.
  callService(domain, service, data, target, notifyOnError, returnResponse) {
    const msg = { type: 'call_service', domain, service, service_data: data || {} };
    if (target) msg.target = target;
    if (returnResponse) msg.return_response = true;
    return vitroCallWS(msg);
  },
  callWS(msg) { return vitroCallWS(msg); },
  callApi: vitroCallApi,
  callApiRaw: vitroCallApiRaw,
  fetchWithAuth(path, init) { return fetch(path, init || {}); },
  localize: vitroLocalize,
  formatEntityState: vitroFormatState,
  formatEntityAttributeValue: vitroFormatAttribute,
  formatEntityAttributeName(stateObj, attr) { return vitroTitle(attr); },
  connection: { sendMessagePromise: (msg) => vitroCallWS(msg) },
};
if (haUser) hassBase.user = haUser;

// A new object on every patch: Lit cards compare hass by reference to decide whether to re-render. Each hass keeps
// the registries it was made with, as HA's does: a card comparing the old one's with the new one's sees the change.
function makeHass() {
  const h = Object.assign({}, hassBase, { states: statesProxy });
  const snapshot = Object.assign({}, registries);
  for (const key of Object.keys(snapshot)) {
    Object.defineProperty(h, key, {
      enumerable: true,
      configurable: true,
      get() {
        if (!registriesAsked) {
          registriesAsked = true;
          sendBridge({ type: 'registries' });
        }
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
  sock = new WebSocket(proto + '//' + location.host + '/__vitro/ws/' + ${JSONObject.quote(slotId)});
  sock.onopen = () => {
    bridgeOpened = true;
    while (outQueue.length) sock.send(outQueue.shift().line);
    for (const entry of wsPending.values()) entry.sent = true;
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
          hassBase.auth = { data: { access_token: accessToken, hassUrl: location.origin }, accessToken };
          changed = true;
        }
        // A user not known (yet) for this sign-in: the anonymous panel, never the previous account.
        const user = (m.user && typeof m.user === 'object') ? m.user : ANON_USER;
        if (JSON.stringify(user) !== JSON.stringify(hassBase.user)) {
          hassBase.user = user;
          changed = true;
        }
        if (changed && cardEl) cardEl.hass = makeHass();
      } else if (m.type === 'theme') {
        // The card's theme now (a theme change kept this page): its variables, and hass.themes for the card.
        const style = document.getElementById('vitro-theme');
        if (style && typeof m.css === 'string') style.textContent = m.css;
        hassBase.themes = Object.assign({}, hassBase.themes, { darkMode: !!m.dark, default_theme: String(m.name || '') });
        if (cardEl) cardEl.hass = makeHass();
      } else if (m.type === 'snapshot' && m.states) {
        // A (re)connect: the whole store, replacing what the island had.
        for (const id of Object.keys(rawStates)) delete rawStates[id];
        Object.assign(rawStates, m.states);
        if (cardEl) cardEl.hass = makeHass();
      } else if (m.type === 'patch' && m.states) {
        for (const id of Object.keys(m.states)) {
          if (m.states[id] === null) delete rawStates[id];
          else rawStates[id] = m.states[id];
        }
        if (cardEl) cardEl.hass = makeHass();
      } else if (m.type === 'registries' && m.registries) {
        // New objects every time: Lit cards compare them by reference.
        for (const key of Object.keys(registries)) registries[key] = m.registries[key] || {};
        if (cardEl) cardEl.hass = makeHass();
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
  fetch('/__vitro/ws/' + ${JSONObject.quote(slotId)}, { cache: 'no-store' }).then(
    (r) => { if (r.status !== 404) connectBridge(); },
    () => setTimeout(retryBridge, 1500),
  );
}

async function boot() {
  connectBridge();
  try { await Promise.race([customElements.whenDefined(tag), new Promise(r => setTimeout(r, 4000))]); } catch (e) {}
  cardEl = document.createElement(tag);
  if (typeof cardEl.setConfig === 'function') cardEl.setConfig(cfg);
  cardEl.hass = makeHass();
  document.getElementById('mount').appendChild(cardEl);
  wireEvents();
  wireResize();
}

function moreInfo(id) {
  if (id) sendBridge({ type: 'moreInfo', entity_id: id });
}

function navigateTo(path) {
  // Never the island's own address: only a dashboard path a card navigated to.
  if (path && typeof path === 'string' && !path.startsWith('/__vitro/')) sendBridge({ type: 'navigate', path });
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
    case 'navigate': navigateTo(action.navigation_path); break;
    case 'toggle': if (entity) hassBase.callService('homeassistant', 'toggle', { entity_id: entity }); break;
    case 'call-service':
    case 'perform-action': {
      const svc = String(action.perform_action || action.service || '');
      const dot = svc.indexOf('.');
      if (dot > 0) hassBase.callService(svc.slice(0, dot), svc.slice(dot + 1), action.data || action.service_data, action.target);
      break;
    }
    case 'url': if (action.url_path) window.open(action.url_path, '_blank'); break;
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
  // HA's navigate(): history.pushState, then `location-changed` on window.
  window.addEventListener('location-changed', (ev) => {
    const p = (ev && ev.detail && ev.detail.path) || (location.pathname + location.search);
    navigateTo(p);
  });
  window.addEventListener('hass-navigate', (ev) => {
    const p = ev && ev.detail && ev.detail.path;
    navigateTo(p);
  });
}

function wireResize() {
  const mount = document.getElementById('mount');
  let timer = null;
  let lastH = -1;
  const reportSize = () => {
    timer = null;
    const h = Math.ceil(mount.getBoundingClientRect().height || 0);
    // 0 px is a card that has not rendered yet: reporting it would collapse the island and flash.
    if (h <= 0 || h === lastH) return;
    lastH = h;
    sendBridge({ type: 'resize', height: h });
  };
  const scheduleResize = () => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(reportSize, 50);
  };
  if (window.ResizeObserver) new ResizeObserver(scheduleResize).observe(mount);
  scheduleResize();
}
boot();
</script>
</body>
</html>
""".trimIndent()
        }
    }
}
