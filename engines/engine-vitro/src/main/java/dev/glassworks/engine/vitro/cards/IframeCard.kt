/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.Page
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.island.VitroIslandServer
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.EntityRowConfig
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.ui.Inter
import org.json.JSONObject
import java.net.URI
import kotlin.math.roundToInt

/** The config checks of HA's small cards' `setConfig`: the error HA's card shows instead, or null when it builds. */
object SmallCardConfigs {
    /**
     * What HA 2026.11 shows for a `section` card: the type is always loaded, but no `hui-section-card` element
     * exists, so the card fails to build and HA's error card shows its generic title (captured against the pinned
     * frontend: the `section-card` parity case).
     */
    const val SECTION_CARD_ERROR = "Configuration error"

    private val VALID_ENTITY_ID = Regex("""^\w+\.\w+$""")

    /** `hui-date-card`: a `time_zone` must be a zone Intl knows (an IANA name); none is the user's or HA's. */
    fun dateError(card: CardConfig): String? {
        val tz = card.raw.optString("time_zone", "")
        if (tz.isEmpty()) return null
        return if (tz in java.util.TimeZone.getAvailableIDs()) null else "Invalid time zone: $tz"
    }

    /** `hui-statistic-card`: an entity (or external statistic), a `stat_type` and a `period`. */
    fun statisticError(card: CardConfig): String? {
        val raw = card.raw
        val entity = card.entity ?: return "Entity must be specified"
        if (raw.optString("stat_type", "").isEmpty()) return "Statistic type must be specified"
        if (!raw.has("period") || raw.isNull("period") || raw.opt("period") == "") return "Period must be specified"
        if (':' !in entity && !VALID_ENTITY_ID.matches(entity)) return "Invalid entity"
        return null
    }

    /** `hui-alert-card`: an entity of [AlertCardView.ALERT_DOMAINS], string colors and a boolean `pulse`. */
    fun alertError(card: CardConfig): String? {
        val raw = card.raw
        val entity = card.entity.orEmpty()
        val badType = { key: String -> raw.has(key) && raw.opt(key) !is String }
        if (!VALID_ENTITY_ID.matches(entity) || badType("alert_color") || badType("clear_color") ||
            (raw.has("pulse") && raw.opt("pulse") !is Boolean)
        ) {
            return "Invalid configuration"
        }
        if (entity.substringBefore('.') !in AlertCardView.ALERT_DOMAINS) {
            return "Specify an entity from within the ${AlertCardView.ALERT_DOMAINS.joinToString(", ")} domains"
        }
        return null
    }

    /** `hui-iframe-card`: a `url`, and none that would run script (`javascript:` and the like). */
    fun iframeError(card: CardConfig): String? {
        val url = card.raw.optString("url", "")
        if (url.isEmpty()) return "URL required"
        if (EntityRowConfig.unsafeUrl(url)) return "Invalid URL"
        return null
    }
}

/**
 * HA's `iframe` card (`hui-iframe-card`): its `url` in a frame, in a browser island of its own (a page that holds
 * nothing but that frame), so only the page the card shows runs web code. As in HA:
 * - The frame is `aspect_ratio` of the card's width tall (`50%` by default; `16:9`, `16x9` and `2` work too), except
 *   in a panel view or a sections grid, where it fills the card.
 * - `sandbox` is HA's (`allow-forms allow-popups allow-pointer-lock allow-same-origin allow-scripts allow-modals
 *   allow-downloads`, plus `allow-top-navigation-by-user-activation` with `allow_open_top_navigation`), or none with
 *   `disable_sandbox`; `allow` is the card's, `fullscreen` by default.
 * - `title` is the card's header, `hide_background` drops the card's surface.
 * - When Home Assistant is served over https, a page that isn't shows HA's error instead.
 *
 * Its page lives as a web island's does ([IslandCard]): paused while its view isn't on screen, handed to the next build
 * of the same card (a reconnect, a theme change) instead of loading again, and closed ([Page.close]) when the card goes
 * for good or its content process dies.
 */
class IframeCardView(
    ctx: VitroCardContext,
    internal val config: CardConfig,
    private val fillViewport: Boolean = false,
    /** The page an earlier build of this very card gave up ([handOff]), taken instead of opening a new one. */
    private val adopted: WebIslandCardView.Island? = null,
) : CardSurface(ctx), IslandCard {
    override val islandConfig: CardConfig get() = config
    private val raw = config.raw
    /** The frame's address, resolved as HA's page resolves it: against the dashboard's own URL (else HA's origin). */
    internal val url: String = absolute(raw.optString("url", ""), ctx.pageUrl() ?: ctx.haBase)

    /** CSS `padding-top` of the frame as a share of its width, or null when it fills the card. */
    internal val ratio: Float? = if (fillViewport || ctx.cardLayout == "grid") null else aspect(raw.optString("aspect_ratio", "").ifEmpty { null })

    private var slotId: String? = null
    private var page: Page? = null
    private val frame = FrameLayout(ctx.context)

    /** True once the page left this card, for good ([release]) or for its next build ([handOff]). */
    private var pageGone = false

    /** Whether the page is active ([setShown]); a new page starts active. */
    private var pageActive = true

    /** A release queued by a detach, which an attach before it runs takes back (a layout that moves its cards). */
    private val releaseIfDetached = Runnable { if (!isAttachedToWindow) release() }

    /** The island page's HTML (tests read it). */
    internal val html: String = islandHtml(url, sandboxOf(raw), raw.optString("allow", "").ifEmpty { "fullscreen" })

    init {
        if (fillViewport || raw.optBoolean("hide_background", false)) {
            dropSurface()
            elevation = 0f
        }
        val col = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        raw.optString("title", "").ifEmpty { null }?.let { title ->
            col.addView(TextView(ctx.context).apply {
                val sp = ctx.scaledSp(24f)
                textSize = sp
                typeface = VitroFonts.typeface(ctx.context, Inter.REGULAR, sp)
                setTextColor(ctx.theme.primaryTextColor)
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                letterSpacing = -0.012f
                gravity = Gravity.CENTER_VERTICAL
                text = title
                setPadding(ctx.px(16f), ctx.px(20f), ctx.px(16f), ctx.px(24f))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (24f * 1.2f * ctx.dp).roundToInt() + ctx.px(44f)))
        }
        val fill = ratio == null
        col.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (fill) 0 else LinearLayout.LayoutParams.WRAP_CONTENT, if (fill) 1f else 0f))
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, if (fill) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT))
        val insecure = insecureReason()
        if (insecure != null) {
            frame.addView(TextView(ctx.context).apply {
                text = insecure
                setTextColor(ctx.theme.primaryTextColor)
                val p = ctx.px(16f)
                setPadding(p, p, p, p)
            })
        } else {
            open()
        }
        contentDescription = raw.optString("title", "").ifEmpty { null }
    }

    /** HA's `error_secure_context`, when HA is on https and the frame's page isn't. */
    private fun insecureReason(): String? {
        val context = runCatching { URI(ctx.haBase).scheme }.getOrNull()?.lowercase() ?: return null
        val target = runCatching { URI(url).scheme }.getOrNull()?.lowercase() ?: return null
        if (context != "https" || target == "https") return null
        return "Unable to load iframes pointing at websites using $target: if Home Assistant is served over $context:."
    }

    private fun open() {
        val server = ctx.islandServer
        val opener = ctx.openIslandPage
        if (server == null || opener == null) {
            frame.addView(TextView(ctx.context).apply {
                text = url
                setTextColor(ctx.theme.secondaryTextColor)
                val p = ctx.px(16f)
                setPadding(p, p, p, p)
            })
            return
        }
        val handler = object : VitroIslandServer.SlotHandler {
            override val html: String get() = this@IframeCardView.html
            override fun onConnected(send: (JSONObject) -> Unit) = Unit
            override fun onMessage(msg: JSONObject, send: (JSONObject) -> Unit) = Unit
            override fun onDisconnected(send: (JSONObject) -> Unit) = Unit
        }
        // The earlier build's page, if its slot is still there: it already shows this very frame.
        val kept = adopted?.takeIf { server.replaceSlot(it.slotId, handler) }
        if (adopted != null && kept == null) adopted.close()
        val (id, slotUrl) = if (kept != null) kept.slotId to "" else server.registerSlot(handler)
        slotId = id
        val p = kept?.page ?: opener(slotUrl)
        if (p == null) {
            server.unregisterSlot(id)
            slotId = null
            return
        }
        page = p
        // A page handed over paused stays paused until this card is shown ([setShown] compares with it).
        if (kept != null) pageActive = kept.active
        frame.addView(p.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (kept == null) p.load(slotUrl)
    }

    override fun holds(pages: Set<Page>): Boolean = page?.let { it in pages } == true

    override fun setShown(shown: Boolean) {
        if (shown == pageActive) return
        pageActive = shown
        page?.setActive(shown)
    }

    override fun handOff(): WebIslandCardView.Island? {
        if (pageGone) return null
        val p = page ?: return null
        val id = slotId ?: return null
        val server = ctx.islandServer ?: return null
        pageGone = true
        server.replaceSlot(id, WebIslandCardView.PARKED)
        frame.removeView(p.view)
        page = null
        slotId = null
        return WebIslandCardView.Island(keyFor(config, fillViewport, url), p, id, 0, server, active = pageActive)
    }

    override fun release() {
        ctx.main.removeCallbacks(releaseIfDetached)
        if (pageGone) return
        pageGone = true
        slotId?.let { ctx.islandServer?.unregisterSlot(it) }
        slotId = null
        page?.close()
        page = null
    }

    override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val r = ratio
        if (r != null && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            // `#root { padding-top: <ratio> }`: the frame's height from the card's content width.
            val inner = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val h = (inner * r).roundToInt()
            val lp = frame.layoutParams
            if (lp.height != h) lp.height = h
        }
        super.measureCard(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ctx.main.removeCallbacks(releaseIfDetached)
        dev.glassworks.engine.vitro.ui.VitroView.liftLayersAbove(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // A card that left the tree is gone, unless it comes back before the next turn of the main loop (a layout that
        // moves its cards between columns detaches and attaches them at once): then it keeps its page.
        if (!pageGone) ctx.main.post(releaseIfDetached)
    }

    companion object {
        /**
         * What makes two builds the same iframe island: the card's config and fill, and the address it resolved to (the
         * same relative `url` on another dashboard is another page). VitroView adopts by it.
         */
        fun keyFor(config: CardConfig, fillViewport: Boolean, url: String): String =
            WebIslandCardView.keyOf(config, fillViewport, viewType = null) + "\n" + url  // an iframe reads nothing of the view

        /** [keyFor] for a card about to be built in [ctx]. */
        fun keyFor(ctx: VitroCardContext, config: CardConfig, fillViewport: Boolean): String =
            keyFor(config, fillViewport, absolute(config.raw.optString("url", ""), ctx.pageUrl() ?: ctx.haBase))

        /** HA's `IFRAME_SANDBOX`. */
        const val SANDBOX = "allow-forms allow-popups allow-pointer-lock allow-same-origin allow-scripts allow-modals allow-downloads"

        /** The `sandbox` attribute HA sets, or null for none (`disable_sandbox`). */
        fun sandboxOf(raw: JSONObject): String? {
            if (raw.optBoolean("disable_sandbox", false)) return null
            val user = if (raw.optBoolean("allow_open_top_navigation", false)) "allow-top-navigation-by-user-activation" else ""
            return "$user $SANDBOX"
        }

        /** HA's `parseAspectRatio` as the card's `padding-top`: `h / w`, `0.5` for none, null when it can't be read. */
        fun aspect(value: String?): Float? {
            if (value == null) return 0.5f
            val v = value.trim()
            val (w, h) = if (v.endsWith("%")) {
                100.0 to (v.dropLast(1).toDoubleOrNull() ?: return null)
            } else {
                val parts = v.replaceFirst(":", "x").split("x")
                if (parts.size == 1) (parts[0].toDoubleOrNull() ?: return null) to 1.0
                else (parts[0].toDoubleOrNull() ?: return null) to (parts[1].toDoubleOrNull() ?: return null)
            }
            // A ratio HA can't use (`NaN`, `Infinity`, zero or less) leaves the padding off: no height of its own.
            if (!w.isFinite() || !h.isFinite() || w <= 0 || h <= 0) return 0f
            val r = (h / w).toFloat()
            return if (r.isFinite()) r else 0f
        }

        /** [url] resolved as the browser does against HA's page (`new URL(url, location)`). */
        fun absolute(url: String, base: String): String = runCatching {
            // An origin alone (`http://ha:8123`) is its root; a page's URL (`.../wall-test/living`) is resolved
            // against as the browser does (`new URL(url, location)`).
            val b = URI(base)
            val root = if (b.path.isNullOrEmpty()) URI(base.trimEnd('/') + "/") else b
            root.resolve(url.trim()).toString()
        }.getOrDefault(url)

        /** The island page: the frame, filling the page, and nothing else. */
        fun islandHtml(url: String, sandbox: String?, allow: String): String {
            fun attr(s: String) = WebIslandCardView.htmlAttr(s)
            val sandboxAttr = sandbox?.let { " sandbox=${attr(it)}" }.orEmpty()
            return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html, body { margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden; background: transparent; }
iframe { position: absolute; top: 0; left: 0; width: 100%; height: 100%; border: none; }
</style>
</head>
<body><iframe src=${attr(url)}$sandboxAttr allow=${attr(allow)}></iframe></body>
</html>
""".trimStart()
        }
    }
}

/**
 * HA's `shopping-list` card (`hui-shopping-list-card`): the to-do list card on its `entity`, else on the first to-do
 * list by name (the one the Shopping list integration provides, when there are several), picked once the first time
 * the card finds one, as HA picks it on its first update.
 */
class ShoppingListCardView(private val ctx: VitroCardContext, private val config: CardConfig) : FrameLayout(ctx.context) {
    private var unsubscribe: (() -> Unit)? = null

    /** The list the card shows, or null before it has one. Declared before `init`, which may set it. */
    internal var shown: String? = null
        private set

    /** True once the pick can't change: the config names the list, or the entity registry is in. */
    private var settled = false

    init {
        watch()
    }

    /**
     * Until the pick is settled: every state change looks again. A list taken before the entity registry says which
     * one is the Shopping list integration's is swapped for it once the registry knows ([settled]).
     */
    private fun watch() {
        if (unsubscribe != null) return
        if (tryBuild() && settled) return
        val look = { ctx.main.post { if (tryBuild() && settled) stopWatching() }; Unit }
        // States as they change, and HA's display data (the entity registry) when it lands.
        val any = ctx.store.subscribeAny { look() }
        val display = ctx.store.subscribe(EntityStore.DISPLAY_KEY) { look() }
        unsubscribe = { any(); display() }
        // A list that arrived between the first look and the listener reached no listener: look again.
        if (tryBuild() && settled) stopWatching()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        watch()
    }

    private fun stopWatching() {
        unsubscribe?.invoke()
        unsubscribe = null
    }

    /** Builds (or swaps) the inner card for the list HA would pick now; true once the card shows a list. */
    private fun tryBuild(): Boolean {
        if (settled) return true
        val explicit = config.entity
        val lists = ctx.store.all().values.filter { it.domain == "todo" && it.state != "unavailable" }
        val entity = explicit ?: pick(lists) ?: return false
        // Settled once the config names the list or the entity registry is in: a list it has no entry for gets none.
        settled = explicit != null || HaDisplay.registry.isNotEmpty()
        // Before that the pick may still move, and an item added to a provisional list would seem to vanish: the
        // editable card shows only once the pick is settled.
        if (!settled) return false
        if (entity == shown) return true
        shown = entity
        removeAllViews()
        val raw = JSONObject(config.raw.toString()).put("type", "todo-list").put("entity", entity)
        val inner = TodoListCardView(ctx, CardConfig(type = "todo-list", raw = raw))
        (inner as? CardSurface)?.applyCardMod(config)
        addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return true
    }

    override fun onDetachedFromWindow() {
        stopWatching()
        super.onDetachedFromWindow()
    }

    companion object {
        /** `getEntityId`: the available to-do lists sorted by name, the `shopping_list` one first among several. */
        fun pick(states: Collection<EntityState>): String? {
            val lists = states.filter { it.domain == "todo" && it.state != "unavailable" }
                .sortedWith(compareBy(java.text.Collator.getInstance()) { it: EntityState -> it.displayName })
            if (lists.size > 1) lists.firstOrNull { HaDisplay.registry[it.entityId]?.platform == "shopping_list" }?.let { return it.entityId }
            return lists.firstOrNull()?.entityId
        }
    }
}
