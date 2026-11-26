/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import dev.glassworks.engine.vitro.cards.AutoEntitiesFilter
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.EntityFilterConfig
import dev.glassworks.engine.vitro.model.NATIVE_VIEW_TYPES
import dev.glassworks.engine.vitro.model.ViewConfig
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Vitro hands to Home Assistant's own frontend (the fallback page) instead of drawing it, so a panel never shows
 * an empty page, an empty card, a missing row or a card drawn differently from HA's.
 *
 * - **Strategies.** A dashboard, view or section saved as a `strategy:` holds no cards: HA's frontend generates them
 *   in JavaScript (its built-in strategies, or a custom one from a Lovelace resource), and no WebSocket command returns
 *   what it generates. Vitro can't know the cards, so the whole dashboard (or the view) goes to HA's page.
 * - **View layouts Vitro doesn't draw**: a view `type` other than HA's masonry, panel, sidebar and sections and
 *   layout-card's four ([NATIVE_VIEW_TYPES]). Vitro never guesses another layout as masonry.
 * - **Built-in cards Vitro doesn't draw** (`map`, the energy cards, ...). A web island runs a custom card's
 *   own script but has no HA frontend, so a built-in card there is an empty element that still costs a browser
 *   session. A view with one goes to HA's page, which draws every card in it. So does a `shortcut` card whose
 *   default label or icon is a panel's, a view's or an action's name, which only HA's frontend knows.
 * - **The strict compat rule** ([NativeOptions]): a built-in card Vitro draws claims its config only when it draws
 *   every option and value in it as HA does (an `entities` row of a domain whose control Vitro doesn't draw, a tile
 *   feature, a double-tap action, ...). So does a custom card that holds built-in cards ([CardCompat.staysNative]),
 *   which the island can't draw, and the view's own config: its header, footer and sidebar, its sections and badges.
 *   Anything else sends the view to HA's page. A custom card the island can draw goes there instead ([CardCompat]).
 *
 * A custom card's island runs its own script, so it draws as in HA, with two exceptions that come here: a custom card
 * that wraps a built-in card Vitro doesn't draw (the reason walks every card a card builds, [CardRouter.producedCards]),
 * and one whose island found it drawing with elements of HA's own frontend that an island page doesn't have
 * ([IslandFrontend], the `learned` reasons of [viewReason]). No card is named here: the island finds them itself.
 *
 * - **auto-entities' matches** ([matchedReason], [matchedRowsReason]): auto-entities picks its rows (or cards) at run
 *   time, so its config can't say whether Vitro draws every one. Vitro draws it natively, and when what it matched
 *   includes a row the strict rule doesn't claim (a row of a domain whose control Vitro doesn't draw, say), the view
 *   goes to HA's page, as it would with that row in its config. It's checked again whenever the matches change.
 */
object WebFallback {
    /** Why the whole [dashboard] goes to HA's page, or null when Vitro draws it. */
    fun dashboardReason(dashboard: DashboardConfig): String? =
        if (dashboard.strategy) "a strategy dashboard (HA's frontend generates its views)" else null

    /**
     * Why [view] goes to HA's page, or null when Vitro draws it. [learned]: what this session found about a card
     * ([IslandFrontend]): a reason for a card an island found drawing with HA's own elements, or null.
     */
    fun viewReason(view: ViewConfig, learned: ((CardConfig) -> String?)? = null): String? {
        if (view.strategy) return "a strategy view (HA's frontend generates its cards)"
        if (view.type !in NATIVE_VIEW_TYPES) return "a `${view.type}` view (a layout Vitro doesn't draw)"
        if (view.sections.any { it.strategy }) return "a strategy section (HA's frontend generates its cards)"
        NativeOptions.viewReasons(view.raw).firstOrNull()?.let { return it }
        // Only the cards the view builds: a panel view's first card, a sections view's section cards.
        for (root in CardRouter.viewRoots(view)) cardReason(root.card, learned)?.let { return it }
        return null
    }

    /**
     * Why [card], or a card it builds, needs HA's frontend, or null when Vitro or an island draws all of it.
     * [learned]: as for [viewReason].
     */
    fun cardReason(card: CardConfig, learned: ((CardConfig) -> String?)? = null): String? = cardReason(card, learned, true)

    /**
     * [cardReason]; [options]: false for a config Vitro made itself (entity-filter's and auto-entities' inner card),
     * whose keys aren't all the user's.
     */
    private fun cardReason(card: CardConfig, learned: ((CardConfig) -> String?)?, options: Boolean): String? {
        val type = card.type
        if (!type.startsWith("custom:") && type !in VitroSupport.CORE_CARD_TYPES) return "the built-in `$type` card"
        learned?.invoke(card)?.let { return it }
        if (type == "shortcut") shortcutReason(card)?.let { return it }
        // An iframe of HA's own pages (a relative or root-relative `url`) needs HA's origin: HA sends
        // `X-Frame-Options: SAMEORIGIN`, so a frame in a loopback island page is refused. HA's frontend draws it.
        if (type == "iframe" && sameOriginIframe(card.raw.optString("url", ""))) return HA_IFRAME
        // The island can't draw this card (it's built in, or holds built-in cards): what its native card doesn't draw
        // as HA does goes to HA's frontend.
        if (options && card.nativeOnly) card.unsupportedOptions.firstOrNull()?.let { return it }
        if (type == "markdown") CardCompat.markdownReasons(card.raw.optString("content", "")).firstOrNull()?.let { return it }
        // auto-entities' inner card holds what it matched in its `card_param` field, which Vitro wrote: the user's own
        // options are the rest of it, and the explicit `entities:` entries, which it always lists there (the ones a
        // filter adds depend on live states). Under `card_param: cards` those entries are cards, checked on their own.
        val inner = if (type == "custom:auto-entities") CardRouter.builtChildren(card).size else -1
        for (child in CardRouter.producedCards(card)) {
            if (child.index == inner) {
                val written = card.raw.optString("card_param", "entities").ifEmpty { "entities" }
                val own = JSONObject(child.value.raw.toString()).apply {
                    remove(written)
                    if (written != "cards") card.raw.optJSONArray("entities")?.let { put(written, JSONArray(it.toString())) }
                }
                if (child.value.nativeOnly) NativeOptions.cardReasons(child.value.type, own).firstOrNull()?.let { return it }
                cardReason(child.value, learned, false)?.let { return it }
            } else {
                cardReason(child.value, learned, true)?.let { return it }
            }
        }
        // HA builds the passing entities into the inner card; its options were checked with entity-filter's.
        if (type == "entity-filter") cardReason(EntityFilterConfig.innerCard(card), learned, false)?.let { return it }
        return null
    }

    /**
     * What the checkers say about a native `custom:auto-entities` card: they can't know which rows it matches, so the
     * view is native only if those are ([matchedRowsReason]). The site checker and the audit script say the same.
     */
    const val MATCHED_ROWS_NOTE =
        "auto-entities picks its rows at run time: the view is native if the rows it matches are, and a row Vitro " +
            "doesn't draw natively opens the view in Home Assistant's frontend"

    /**
     * Why [inner], the card auto-entities built from what it matched ([AutoEntitiesFilter.innerCard]), needs HA's
     * frontend, or null when Vitro (or an island) draws all of it. The whole card is the user's now, the rows Vitro
     * wrote in its `card_param` field included, so every option of it is checked, the matches' own `options` too.
     */
    fun matchedReason(inner: CardConfig): String? =
        cardReason(inner, null, true)?.let { "a row `custom:auto-entities` matched: $it" }

    /**
     * [matchedReason] for every `custom:auto-entities` card Vitro draws natively in [view], over [states] now (an
     * island runs the real card, which draws its own matches), nested ones (one used as another's `card:`) included. Null when [view] has none, or each one's matches are
     * drawn natively. Read whenever the view is shown, so a view whose matches changed is decided again.
     */
    fun matchedRowsReason(
        view: ViewConfig,
        scripts: IslandScripts,
        states: Collection<EntityState>,
        lookup: (String) -> EntityState?,
    ): String? {
        if (!view.hasAutoEntities) return null
        fun walk(card: CardConfig): String? {
            if (card.inIsland(scripts)) return null
            if (card.type == AUTO_ENTITIES) {
                val inner = AutoEntitiesFilter.innerCard(card.raw, AutoEntitiesFilter.evaluate(card.raw, states, lookup))
                matchedReason(inner)?.let { return it }
                // The inner card can be (or hold) another auto-entities, which picks its own rows at run time from
                // what this one wrote into it and its own filter: its matches are checked too, however deep.
                return walk(inner)
            }
            for (child in CardRouter.producedCards(card, perEntity = false)) walk(child.value)?.let { return it }
            return null
        }
        for (root in CardRouter.viewRoots(view)) walk(root.card)?.let { return it }
        return null
    }

    internal const val AUTO_ENTITIES = "custom:auto-entities"


    private const val HA_IFRAME = "an `iframe` card of a Home Assistant page"

    /**
     * Why [view] goes to HA's page for an `iframe` card (in it, or in a card it builds) whose absolute `url` is on Home
     * Assistant's own origin [haBase]: HA's `X-Frame-Options: SAMEORIGIN` refuses it in a loopback island page, as it
     * does a relative one ([cardReason]). Null when there is none, or [haBase] isn't known. Apart from [viewReason]:
     * that one is the view's own, kept with its config, and it can't know the server.
     */
    fun haIframeReason(view: ViewConfig, haBase: String?): String? {
        if (haBase.isNullOrEmpty()) return null
        fun holds(card: CardConfig): Boolean =
            (card.type == "iframe" && sameOriginIframe(card.raw.optString("url", ""), haBase)) ||
                CardRouter.producedCards(card).any { holds(it.value) } ||
                (card.type == "entity-filter" && holds(EntityFilterConfig.innerCard(card)))
        return if (CardRouter.viewRoots(view).any { holds(it.card) }) HA_IFRAME else null
    }

    private val ABSOLUTE = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://""")

    /**
     * An iframe `url` HA resolves on its own origin: a relative or root-relative one, and, with [haBase] (HA's origin),
     * an absolute one on that origin. A scheme-relative one (`//host/...`) takes HA's scheme; without [haBase] it
     * counts as HA's, and an absolute one never does.
     */
    internal fun sameOriginIframe(url: String, haBase: String? = null): Boolean {
        val u = url.trim()
        if (u.startsWith("//")) return haBase == null || VitroCardContext.sameOrigin(haBase.substringBefore("//") + u, haBase)
        if (!ABSOLUTE.containsMatchIn(u)) return true
        return haBase != null && VitroCardContext.sameOrigin(u, haBase)
    }

    /**
     * Why a `shortcut` card needs HA's frontend: with no `label` (or no `icon`), HA shows its action's own one, which
     * for a dashboard, view or panel path is that page's title and icon, and for an action the service's translated
     * name and icon. Vitro draws the defaults it knows the same way: none, `url`, `assist`, and area, device and
     * settings paths.
     */
    private fun shortcutReason(card: CardConfig): String? {
        val raw = card.raw
        val tap = raw.optJSONObject("tap_action")
        val action = tap?.optString("action", "none") ?: "none"
        // Vitro's actions don't open Assist: HA's page does.
        if (listOf("tap_action", "hold_action").any { raw.optJSONObject(it)?.optString("action") == "assist" }) {
            return "a `shortcut` card that opens Assist"
        }
        val needsLabel = raw.optString("label", "").isEmpty()
        val needsIcon = raw.optString("icon", "").isEmpty()
        if (!needsLabel && !needsIcon) return null
        return when (action) {
            "perform-action", "call-service" -> "a `shortcut` card that shows its action's name or icon"
            "navigate" -> if (ShortcutDefaults.navigation(tap?.optString("navigation_path", "").orEmpty()) == null) {
                "a `shortcut` card that shows the page it opens by its title or icon"
            } else {
                null
            }
            else -> null
        }
    }
}

/**
 * The label and icon HA's `shortcut` card shows when its config has none (`getShortcutCardDefaults`), for the
 * actions whose defaults Vitro knows without HA's frontend. A navigation path to a dashboard, a view or a panel is
 * named by that page, which [navigation] doesn't know (null): such a card goes to HA's frontend ([WebFallback]).
 */
object ShortcutDefaults {
    /** A default: [label] is literal, or the id of an area or device (a [kind] of `area` or `device`) to look up. */
    data class Default(val label: String, val icon: String, val kind: String = "text")

    /** `none` and any action without a default of its own: no label, the link icon. */
    val NONE = Default("", "mdi:link")

    private val CONFIG_ROUTES = mapOf(
        "automation" to ("Automations" to "mdi:robot"),
        "scene" to ("Scenes" to "mdi:palette"),
        "script" to ("Scripts" to "mdi:script-text"),
        "tools" to ("Tools" to "mdi:hammer"),
        "integrations" to ("Integrations" to "mdi:puzzle"),
        "devices" to ("Devices" to "mdi:devices"),
        "entities" to ("Entities" to "mdi:shape"),
    )

    /** The default for [tap] (the card's `tap_action`), or null when only HA's frontend knows it. */
    fun of(tap: org.json.JSONObject?): Default? = when (tap?.optString("action", "none") ?: "none") {
        "navigate" -> navigation(tap?.optString("navigation_path", "").orEmpty())
        "assist" -> Default("Assist", "mdi:microphone")
        "url" -> Default(tap?.optString("url_path", "").orEmpty(), "mdi:open-in-new")
        "perform-action", "call-service" -> null
        else -> NONE
    }

    /**
     * `computeNavigationPathInfo` for the paths it resolves without panels or a dashboard's config: an area
     * (`/config/areas/area/<id>`, `/<dashboard>/areas-<id>`), a device (`/config/devices/device/<id>`) and the
     * settings pages it names. An empty path is HA's default (no label, the link icon).
     */
    fun navigation(path: String): Default? {
        if (path.isEmpty()) return NONE
        val segments = path.removePrefix("/").split('/', '?')
        val panel = segments.getOrNull(0).orEmpty()
        val sub = segments.getOrNull(1)
        if (panel == "config" && sub == "areas" && segments.getOrNull(2) == "area" && !segments.getOrNull(3).isNullOrEmpty()) {
            return Default(segments[3], "mdi:texture-box", kind = "area")
        }
        if (panel == "config" && sub == "devices" && segments.getOrNull(2) == "device" && !segments.getOrNull(3).isNullOrEmpty()) {
            return Default(segments[3], "mdi:devices", kind = "device")
        }
        if (panel == "config" && sub != null) CONFIG_ROUTES[sub]?.let { (label, icon) -> return Default(label, icon) }
        if (sub != null && sub.startsWith("areas-")) return Default(sub.removePrefix("areas-"), "mdi:texture-box", kind = "area")
        return null
    }
}
