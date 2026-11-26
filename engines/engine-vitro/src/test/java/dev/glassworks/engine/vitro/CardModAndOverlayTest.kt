/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeBackground
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Card-level `card_mod` (static and templated), the pinned overlay layer and visibility fades. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CardModAndOverlayTest {
    /** The picture loads a test starts are all back before the next test: one in flight lands in its looper (D28). */
    private val pendingImagesBefore = dev.glassworks.engine.vitro.ui.VitroCardContext.pendingImageLoads.get()

    @After fun imageLoadsSettle() = awaitImageLoads(pendingImagesBefore)

    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private class Templates {
        val live = LinkedHashMap<String, (String) -> Unit>()
        fun push(match: String, value: String) {
            val key = live.keys.firstOrNull { match in it } ?: throw AssertionError("no template containing '$match' in ${live.keys}")
            live.getValue(key)(value)
            ShadowLooper.idleMainLooper()
        }
    }

    private fun context(store: EntityStore, templates: Templates = Templates()) =
        VitroCardContext(
            context = activity,
            haBase = FakeHaServer.BASE,
            store = store,
            theme = ThemeResolver.resolve(ThemeCatalog(), true),
            resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN },
            callService = { _, _, _, _ -> },
            navigate = {},
            openMoreInfo = {},
            fetchHistory = { _, _, _ -> },
            subscribeTemplate = { tpl, cb ->
                templates.live[tpl] = cb
                ({ templates.live.remove(tpl) })
            },
        )

    private fun card(json: String) = JSONObject(json).let { CardConfig(it.getString("type"), it) }

    private fun ViewGroup.all(): List<View> {
        val out = ArrayList<View>()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            out += c
            if (c is ViewGroup) out += c.all()
        }
        return out
    }

    // ---- ThemeResolver.parseCardMod ----

    @Test
    fun parsesGradientBorderAndTypography() {
        val mod = ThemeResolver.parseCardMod(
            """ha-card {
                 background: linear-gradient(135deg, #1e3a5f, rgba(0, 0, 0, 0.4));
                 border-color: #ffb300;
                 border-width: 2px;
                 border-radius: 28px;
                 padding: 0;
                 font-size: 164px;
                 font-weight: light;
                 letter-spacing: 0.14em;
                 transition: opacity 0.8s ease-in-out;
               }""",
        )
        val gradient = mod.backgroundGradient
        assertNotNull(gradient)
        gradient!!
        assertEquals(135f, gradient.angleDeg)
        assertArrayEquals(intArrayOf(0xFF1E3A5F.toInt(), 0x66000000), gradient.colors)
        assertEquals("the shorthand resets the color: transparent under the gradient", 0, mod.backgroundColor)
        assertEquals(0xFFFFB300.toInt(), mod.borderColor)
        assertEquals(2f, mod.borderWidthDp)
        assertEquals(28f, mod.borderRadiusDp)
        assertEquals(0f, mod.paddingDp)
        assertEquals(dev.glassworks.engine.vitro.theme.EdgeInsetsDp(0f, 0f, 0f, 0f), mod.paddingInsets)
        assertEquals(164f, mod.fontSizeSp)
        assertEquals(300, mod.fontWeight)
        assertEquals(0.14f, mod.letterSpacingEm!!, 0.0001f)
        assertEquals(800L, mod.transitionOpacityMs)
        assertFalse(mod.positionFixed)
        assertFalse(mod.hasComplexSelectors)
    }

    @Test
    fun parsesMultiValuePaddingAndGapAndViewPadding() {
        val two = ThemeResolver.parseCardMod("ha-card { padding: 16px 10px; gap: 14px; }")
        assertEquals(dev.glassworks.engine.vitro.theme.EdgeInsetsDp(10f, 16f, 10f, 16f), two.paddingInsets)
        assertEquals(16f, two.paddingDp)
        assertEquals(14f, two.gapDp)

        val four = ThemeResolver.parseCardMod("ha-card { padding: 4px 8px 12px 16px; padding-left: 20px; }")
        assertEquals(dev.glassworks.engine.vitro.theme.EdgeInsetsDp(20f, 4f, 8f, 12f), four.paddingInsets)

        val theme = ThemeResolver.resolve(
            ThemeCatalog(
                defaultTheme = "mirror",
                themes = mapOf("mirror" to ThemeCatalog.ThemeDefinition(base = mapOf("masonry-view-padding" to "28px", "masonry-view-card-margin" to "14px"))),
            ),
        )
        assertEquals(28f, theme.viewPaddingDp)
        assertEquals(14f, theme.cardGapDp)
    }

    @Test
    fun parsesFixedPositionAndCentering() {
        val mod = ThemeResolver.parseCardMod(
            "ha-card { position: fixed; right: 24px; top: 50%; transform: translateY(-50%); width: 132px; }",
        )
        assertTrue(mod.positionFixed)
        assertEquals("24px", mod.fixedRight)
        assertEquals("50%", mod.fixedTop)
        assertEquals("132px", mod.fixedWidth)
        assertNull(mod.fixedLeft)
        assertTrue(mod.fixedCenterY)
        assertFalse(mod.fixedCenterX)

        val both = ThemeResolver.parseCardMod("ha-card { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%) }")
        assertTrue(both.positionFixed)
        assertTrue(both.fixedCenterX)
        assertTrue(both.fixedCenterY)
    }

    @Test
    fun readsStyleFromStringOrSelectorMapAndResolvesThemeVars() {
        val vars = mapOf("accent-color" to "#ff9800")
        val asString = JSONObject("""{"style": "ha-card { border-color: var(--accent-color); font-weight: 600 }"}""")
        val asMap = JSONObject("""{"style": {".": "ha-card { border-color: var(--accent-color) }", "ha-tile-icon$": "x"}}""")
        assertEquals(0xFFFF9800.toInt(), ThemeResolver.parseCardMod(asString, vars).borderColor)
        assertEquals(600, ThemeResolver.parseCardMod(asString, vars).fontWeight)
        assertEquals(0xFFFF9800.toInt(), ThemeResolver.parseCardMod(asMap, vars).borderColor)
        assertNull(ThemeResolver.cardModStyleString(JSONObject("""{"style": {"ha-tile-icon$": "x"}}""")))

        // Other selectors don't leak into the card's own declarations; `to right` maps to 90 degrees.
        val mixed = ThemeResolver.parseCardMod(
            "ha-card { background: linear-gradient(to right, #000000, #ffffff) } .name { font-size: 40px }",
        )
        assertEquals(90f, mixed.backgroundGradient?.angleDeg)
        assertNull(mixed.fontSizeSp)
        assertTrue(mixed.hasComplexSelectors)
        assertEquals(400L, ThemeResolver.parseCardMod("ha-card { transition: all 400ms linear }").transitionOpacityMs)
        assertNull(ThemeResolver.parseCardMod("ha-card { transition: transform 1s }").transitionOpacityMs)
        // A declaration outside any rule is invalid CSS: card-mod (and so Vitro) ignores it.
        assertNull(ThemeResolver.parseCardMod("background: #112233; font-size: 20px").backgroundColor)
        assertNull(ThemeResolver.parseCardMod("background: #112233; font-size: 20px").fontSizeSp)
        // Card-scoped custom properties, margins and `background: none`.
        val vars2 = ThemeResolver.parseCardMod("ha-card { --card-text-align: right; margin: 4px 8px; background: none }")
        assertEquals("right", vars2.customProperties["card-text-align"])
        assertEquals(8f, vars2.marginInsets?.left)
        assertEquals(4f, vars2.marginInsets?.top)
        assertEquals(0, vars2.backgroundColor)
    }

    // ---- CardSurface.applyCardMod ----

    @Test
    fun aRestyleWithoutBoxShadowBringsTheThemeShadowBack() {
        val store = EntityStore()
        store.putLocal(EntityState("light.lounge", "off", mapOf("friendly_name" to "Lounge")))
        val ctx = context(store)
        val tile = TileCardView(ctx, card("""{"type": "tile", "entity": "light.lounge"}"""))
        val baseline = ctx.theme.cardElevationDp * ctx.dp
        // A templated style re-renders: first with a shadow, then without one.
        tile.applyStyleCss("ha-card { box-shadow: 0 2px 6px #000 }")
        assertEquals(5f * ctx.dp, tile.elevation, 0.001f)
        tile.applyStyleCss("ha-card { padding: 4px }")
        assertEquals(baseline, tile.elevation, 0.001f)
        tile.applyStyleCss("ha-card { box-shadow: none }")
        assertEquals(0f, tile.elevation, 0.001f)
        // A card that shares its parent's surface keeps no shadow of its own.
        tile.applyStyleCss("ha-card { box-shadow: 0 2px 6px #000 }")
        tile.makeSeamless(keepBackground = true)
        tile.applyStyleCss("ha-card { padding: 4px }")
        assertEquals(0f, tile.elevation, 0.001f)
        // Even an explicit shadow: the card shares its parent's surface, background kept or not.
        tile.applyStyleCss("ha-card { box-shadow: 0 2px 6px #000 }")
        assertEquals(0f, tile.elevation, 0.001f)
    }

    @Test
    fun staticCardModRestylesTheSurface() {
        val store = EntityStore()
        store.putLocal(EntityState("light.lounge", "off", mapOf("friendly_name" to "Lounge")))
        val ctx = context(store)
        val config = card(
            """{"type": "tile", "entity": "light.lounge", "card_mod": {"style":
               "ha-card { background: linear-gradient(90deg, #102030, #405060); border: 3px solid #ff0000; border-radius: 40px; opacity: 0.5 }"}}""",
        )
        val tile = TileCardView(ctx, config)
        tile.applyCardMod(config)
        // The gradient paints like CSS (CssBackgroundDrawable) under a rounded, bordered layer.
        val layers = tile.background as android.graphics.drawable.LayerDrawable
        assertTrue(layers.getDrawable(0) is dev.glassworks.engine.vitro.ui.CssBackgroundDrawable)
        val border = layers.getDrawable(1) as GradientDrawable
        assertEquals(40f * ctx.dp, border.cornerRadius)
        val gradient = tile.activeCardMod.backgroundLayers!!.layers.single() as dev.glassworks.engine.vitro.theme.CssBackground.Linear
        assertEquals(90f, gradient.angleDeg)
        assertEquals(listOf(0xFF102030.toInt(), 0xFF405060.toInt()), gradient.stops.map { it.color })
        assertEquals(0.5f, tile.alpha)
        assertEquals(0xFFFF0000.toInt(), tile.activeCardMod.borderColor)
        assertEquals(3f, tile.activeCardMod.borderWidthDp)
        assertTrue(tile.clipToOutline)

        // Transparent borderless cards drop the rounded outline so corner clipping never chops text.
        val bareCfg = card(
            """{"type": "markdown", "content": "Monday", "card_mod": {"style": "ha-card { background: transparent; border: none; padding: 0; }"}}""",
        )
        val bare = dev.glassworks.engine.vitro.cards.MarkdownCardView(ctx, bareCfg)
        bare.applyCardMod(bareCfg)
        assertNull(bare.background)
        assertFalse(bare.clipToOutline)
    }

    @Test
    fun templatedCardModFollowsTheRenderedTemplate() {
        val store = EntityStore()
        store.putLocal(EntityState("light.lounge", "off", mapOf("friendly_name" to "Lounge")))
        val templates = Templates()
        val config = card(
            """{"type": "tile", "entity": "light.lounge", "card_mod": {"style":
               "ha-card { background: {{ '#ffb300' if is_state(entity, 'on') else '#202020' }}; }"}}""",
        )
        val tile = TileCardView(context(store, templates), config)
        tile.applyCardMod(config)

        val tpl = templates.live.keys.single { "is_state(entity" in it }
        assertTrue("entity is set Mushroom-style", tpl.startsWith("{% set entity = 'light.lounge' %}"))

        templates.push("is_state(entity", "ha-card { background: #202020; }")
        assertEquals(0xFF202020.toInt(), (tile.background as GradientDrawable).color?.defaultColor)

        // The room lights up: HA re-renders and the tile turns amber.
        templates.push("is_state(entity", "ha-card { background: #ffb300; }")
        assertEquals(0xFFFFB300.toInt(), (tile.background as GradientDrawable).color?.defaultColor)
        assertEquals(0xFFFFB300.toInt(), tile.activeCardMod.backgroundColor)
    }

    @Test
    fun orientationSnapsToNearestEighth() {
        assertEquals(GradientDrawable.Orientation.BOTTOM_TOP, CardSurface.orientationFor(0f))
        assertEquals(GradientDrawable.Orientation.TL_BR, CardSurface.orientationFor(140f))
        assertEquals(GradientDrawable.Orientation.TOP_BOTTOM, CardSurface.orientationFor(180f))
        assertEquals(GradientDrawable.Orientation.BR_TL, CardSurface.orientationFor(-45f))
    }

    // ---- VitroView: pinned overlay and visibility ----

    @Test
    fun anIslandThatLoadsCardModGetsTheOpacityOnce() {
        fun islandAlpha(resources: List<dev.glassworks.engine.vitro.model.LovelaceResource>): Float {
            val vv = vitroView(
                EntityStore(),
                """{"views": [{"cards": [{"type": "custom:acme-card", "card_mod": {"style": "ha-card { opacity: 0.5 }"}}]}]}""",
                resources,
            )
            fun find(v: View): dev.glassworks.engine.vitro.cards.WebIslandCardView? = when (v) {
                is dev.glassworks.engine.vitro.cards.WebIslandCardView -> v
                is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
                else -> null
            }
            return assertNotNull(find(vv)).let { find(vv)!!.alpha }
        }
        // card-mod in the island applies the 0.5 there, so the native frame stays at 1 (not 0.25 overall).
        assertEquals(1f, islandAlpha(listOf(dev.glassworks.engine.vitro.model.LovelaceResource("/hacsfiles/lovelace-card-mod/card-mod.js", "module"))), 0.001f)
        // Without card-mod in the island, the native frame is the only layer that applies it.
        assertEquals(0.5f, islandAlpha(emptyList()), 0.001f)
    }

    @Test
    fun aConditionalAroundAnIslandKeepsItsOwnCardMod() {
        // The island receives only the child's config, so the conditional's own opacity is drawn natively.
        val vv = vitroView(
            EntityStore(),
            """{"views": [{"cards": [{"type": "conditional", "conditions": [], "card_mod": {"style": "ha-card { opacity: 0.5 }"},
                "card": {"type": "custom:acme-card"}}]}]}""",
            listOf(dev.glassworks.engine.vitro.model.LovelaceResource("/hacsfiles/lovelace-card-mod/card-mod.js", "module")),
        )
        fun find(v: View): dev.glassworks.engine.vitro.cards.WebIslandCardView? = when (v) {
            is dev.glassworks.engine.vitro.cards.WebIslandCardView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        val island = find(vv)
        assertNotNull(island)
        assertEquals(0.5f, island!!.alpha, 0.001f)
    }

    @Test
    fun anIslandThatLoadsCardModGetsTheMarginOnce() {
        fun islandParent(resources: List<dev.glassworks.engine.vitro.model.LovelaceResource>): ViewGroup? {
            val vv = vitroView(
                EntityStore(),
                """{"views": [{"cards": [{"type": "custom:acme-card", "card_mod": {"style": "ha-card { margin: 12px }"}}]}]}""",
                resources,
            )
            fun find(v: View): dev.glassworks.engine.vitro.cards.WebIslandCardView? = when (v) {
                is dev.glassworks.engine.vitro.cards.WebIslandCardView -> v
                is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
                else -> null
            }
            val island = find(vv)
            assertNotNull(island)
            return island!!.parent as? ViewGroup
        }
        val dp = activity.resources.displayMetrics.density
        // card-mod in the island draws the margin there, so the native frame adds no padding around it.
        val styled = islandParent(listOf(dev.glassworks.engine.vitro.model.LovelaceResource("/hacsfiles/lovelace-card-mod/card-mod.js", "module")))
        assertEquals(0, styled?.paddingLeft ?: 0)
        // Without card-mod there, the native frame is the only layer that insets it.
        assertEquals((12 * dp).toInt(), islandParent(emptyList())?.paddingLeft)
    }

    @Test
    fun resourcesArrivingLaterRebuildTheViews() {
        // Built before the resources arrive, a card-mod-only fallback stays native; once card-mod arrives, the view
        // is rebuilt and the card goes to its island.
        val vv = vitroView(
            EntityStore(),
            """{"views": [{"cards": [{"type": "custom:mushroom-template-card", "card_mod": {"style": {"mushroom-shape-icon$": ".shape { color: red }"}}}]}]}""",
        )
        assertEquals(0, vv.lastMetrics.webIslandCards)
        val cardMod = listOf(
            dev.glassworks.engine.vitro.model.LovelaceResource("/hacsfiles/lovelace-mushroom/mushroom.js?hacstag=1", "module"),
            dev.glassworks.engine.vitro.model.LovelaceResource("/hacsfiles/lovelace-card-mod/card-mod.js?hacstag=1", "module"),
        )
        vv.resourcesList = cardMod
        assertEquals(1, vv.lastMetrics.webIslandCards)
        assertFalse(vv.lastMetrics.cacheHit)
        assertEquals(1, vv.lastMetrics.islands.size)
        // The same list again changes nothing.
        val metrics = vv.lastMetrics
        vv.resourcesList = cardMod.toList()
        assertTrue(metrics === vv.lastMetrics)
    }

    @Test
    fun aNewServerDropsTheOldDashboard() {
        // The new server's resources must not rebuild the old server's dashboard before its own arrives.
        val store = EntityStore()
        store.putLocal(EntityState("light.a", "on", mapOf("friendly_name" to "A")))
        val vv = vitroView(store, """{"views": [{"cards": [{"type": "tile", "entity": "light.a"}]}]}""")
        assertNotNull(vv.dashboard)
        vv.openMoreInfo("light.a")
        // The sheet is really open, so the check after the reset proves the reset removed it.
        fun sheets(v: android.view.View): Int = (if (v is dev.glassworks.engine.vitro.cards.MoreInfoSheet) 1 else 0) +
            ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).sumOf { sheets(g.getChildAt(it)) } } ?: 0)
        assertEquals(1, sheets(vv))
        vv.resetForServer()
        assertEquals(0, sheets(vv))
        assertNull(vv.dashboard)
        // Nothing of the old server stays on screen: no More-Info sheet either.
        assertFalse(vv.closeMoreInfo())
        val metrics = vv.lastMetrics
        vv.resourcesList = listOf(dev.glassworks.engine.vitro.model.LovelaceResource("/local/other.js", "module"))
        assertTrue("nothing rebuilt", metrics === vv.lastMetrics)
    }

    @Test
    fun autoIsNoInsetAndLeftWinsOverRight() {
        val vv = VitroView(activity, EntityStore(), 0xFF000000.toInt())
        val dp = activity.resources.displayMetrics.density
        // `right: auto` doesn't anchor the card to the right edge.
        val auto = vv.pinnedLayoutParams(ThemeResolver.parseCardMod("ha-card { position: fixed; left: 24px; right: auto; top: auto; bottom: 0; width: 120px }"))
        assertEquals(Gravity.START or Gravity.BOTTOM, auto.gravity)
        assertEquals((24 * dp).toInt(), auto.marginStart)
        // Both insets with a width: `left` wins, as in CSS.
        val both = vv.pinnedLayoutParams(ThemeResolver.parseCardMod("ha-card { position: fixed; left: 24px; right: 24px; top: 0; width: 120px }"))
        assertEquals(Gravity.START or Gravity.TOP, both.gravity)
    }

    @Test
    fun aFixedCardWhoseHaCardIsFullHeightIsAsTallAsTheViewport() {
        // HA's tile (like most of its cards) is `ha-card { height: 100% }`. With `position: fixed` the viewport is its
        // containing block, so a tile pinned with `bottom: 24px` is as tall as the screen, its top 24 px above it.
        val vv = VitroView(activity, EntityStore(), 0xFF000000.toInt())
        val dp = activity.resources.displayMetrics.density
        val screenH = activity.resources.displayMetrics.heightPixels
        val css = "ha-card { position: fixed; right: 24px; bottom: 24px; width: 160px; }"
        assertTrue("tile" in dev.glassworks.engine.vitro.ui.HA_CARD_FULL_HEIGHT)
        val tile = vv.pinnedLayoutParams(ThemeResolver.parseCardMod(css), fillsHeight = true)
        assertEquals(screenH, tile.height)
        assertEquals(Gravity.END or Gravity.BOTTOM, tile.gravity)
        assertEquals((24 * dp).toInt(), tile.bottomMargin)
        assertEquals((160 * dp).toInt(), tile.width)
        // A card whose own height isn't 100% (a custom card's is unknown) keeps its own.
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, vv.pinnedLayoutParams(ThemeResolver.parseCardMod(css)).height)
        // `position: absolute` isn't placed against the viewport.
        val absolute = ThemeResolver.parseCardMod("ha-card { position: absolute; right: 24px; bottom: 24px; }")
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, vv.pinnedLayoutParams(absolute, fillsHeight = true).height)
        // The card-mod height wins over the card's own 100%.
        val sized = ThemeResolver.parseCardMod("ha-card { position: fixed; right: 24px; bottom: 24px; height: 120px; }")
        assertEquals((120 * dp).toInt(), vv.pinnedLayoutParams(sized, fillsHeight = true).height)
        val half = ThemeResolver.parseCardMod("ha-card { position: fixed; top: 0; height: 50%; }")
        assertEquals(screenH / 2, vv.pinnedLayoutParams(half, fillsHeight = true).height)
        // An explicit `height: auto` is the content's height, not the 100% card's viewport.
        val auto = ThemeResolver.parseCardMod("ha-card { position: fixed; top: 0; height: auto; }")
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, vv.pinnedLayoutParams(auto, fillsHeight = true).height)
        // `initial` and `unset` reset height to `auto` too (it isn't inherited).
        for (reset in listOf("initial", "unset", "UNSET")) {
            val r = ThemeResolver.parseCardMod("ha-card { position: fixed; top: 0; height: $reset; }")
            assertEquals(reset, ViewGroup.LayoutParams.WRAP_CONTENT, vv.pinnedLayoutParams(r, fillsHeight = true).height)
        }
        // `entity-button` is the button card's other name: the same 100%.
        assertTrue("button" in dev.glassworks.engine.vitro.ui.HA_CARD_FULL_HEIGHT && "entity-button" in dev.glassworks.engine.vitro.ui.HA_CARD_FULL_HEIGHT)
    }

    @Test
    fun theCenteringIdiomIsPlacedAtTheViewportCenter() {
        // `left: 50%; top: 50%; transform: translate(-50%, -50%)` centers the card in HA, and the overlay centers it too.
        val vv = VitroView(activity, EntityStore(), 0xFF000000.toInt())
        val both = vv.pinnedLayoutParams(ThemeResolver.parseCardMod("ha-card { position: fixed; left: 50%; top: 50%; transform: translate(-50%, -50%) }"))
        assertEquals(Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL, both.gravity)
        assertEquals(0, both.topMargin)
        assertEquals(0, both.marginStart)
        // One axis: `top: 50%` with translateY(-50%) centers vertically; the other axis keeps its offset.
        val dp = activity.resources.displayMetrics.density
        val side = vv.pinnedLayoutParams(ThemeResolver.parseCardMod("ha-card { position: fixed; right: 24px; top: 50%; transform: translateY(-50%) }"))
        assertEquals(Gravity.END or Gravity.CENTER_VERTICAL, side.gravity)
        assertEquals((24 * dp).toInt(), side.marginEnd)
        // The overlay reads every translate in the list, as the checker does.
        val list = vv.pinnedLayoutParams(ThemeResolver.parseCardMod("ha-card { position: fixed; left: 0; top: 50%; transform: translate(0, 0) translate(0, -50%) }"))
        assertEquals(Gravity.START or Gravity.CENTER_VERTICAL, list.gravity)
    }

    private fun vitroView(
        store: EntityStore,
        dashboard: String,
        resources: List<dev.glassworks.engine.vitro.model.LovelaceResource> = emptyList(),
        holdMs: Long = 0,
    ): VitroView {
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        vv.holdFirstBuildForStatesMs = holdMs
        vv.resourcesList = resources
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity,
                haBase = FakeHaServer.BASE,
                store = store,
                theme = theme,
                resources = vv.resourcesList,
                accessToken = { FakeHaServer.TOKEN },
                callService = { _, _, _, _ -> },
                navigate = {},
                openMoreInfo = {},
                fetchHistory = { _, _, _ -> },
                subscribeTemplate = { _, _ -> {} },
            )
        }
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject(dashboard)), null)
        return vv
    }

    @Test
    fun pictureBackdropIsItsOwnCachedLayer() {
        // A gradient backdrop is drawn by its own view on a hardware layer (rasterized once, composited per frame),
        // in place of the root's color; a solid one stays the root's color.
        val gradient = vitroView(EntityStore(), """{"views": [{"title": "Home", "type": "sections",
            "background": "radial-gradient(circle at 20% 10%, #123456, transparent 40%), #000000",
            "sections": [{"type": "grid", "cards": []}]}]}""")
        val backdrop = gradient.getChildAt(0)
        assertTrue(backdrop.background is dev.glassworks.engine.vitro.ui.CssBackgroundDrawable)
        assertEquals(View.LAYER_TYPE_HARDWARE, backdrop.layerType)
        assertEquals(null, gradient.background)
        val solid = vitroView(EntityStore(), """{"views": [{"title": "Home", "type": "sections",
            "background": "#102030", "sections": [{"type": "grid", "cards": []}]}]}""")
        assertEquals(null, solid.getChildAt(0).background)
        assertTrue(solid.background is android.graphics.drawable.ColorDrawable)
    }

    @Test
    fun cardsAreCachedLayersUnlessTheyCastShadows() {
        // A shadow (the theme's card elevation, or the card's own) would be clipped to the layer's bounds.
        assertTrue(VitroView.cardLayerWanted(0f, 0f))
        assertFalse(VitroView.cardLayerWanted(2f, 0f))
        assertFalse(VitroView.cardLayerWanted(0f, 3f))
        // Only cards covering at least 7% of the dashboard.
        assertTrue(VitroView.isBigCard(440, 372, 1440, 720))
        assertFalse(VitroView.isBigCard(214, 84, 1440, 720))
        assertFalse(VitroView.isBigCard(440, 372, 0, 0))

        val store = EntityStore()
        store.putLocal(EntityState("light.lounge", "on", mapOf("friendly_name" to "Lounge")))
        val dash = """{"views": [{"title": "Home", "type": "sections", "theme": "t", "sections": [{"type": "grid", "cards": [
            {"type": "tile", "entity": "light.lounge"},
            {"type": "markdown", "content": "# Big\n\nA\n\nB\n\nC\n\nD\n\nE\n\nF\n\nG"}]}]}]}"""
        fun layers(theme: String, mode: VitroView.CardLayers = VitroView.CardLayers.BIG): Map<String, Int> {
            val vv = vitroView(store, """{"views": []}""")
            vv.cardLayers = mode
            vv.setThemes(ThemeCatalog.parse(JSONObject("""{"themes": {"t": $theme}}""")))
            vv.setDashboard(LovelaceParser.parseDashboard(JSONObject(dash)), null)
            vv.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            vv.layout(0, 0, 1280, 800)
            fun inGrid(card: View) = generateSequence(card) { it.parent as? View }
                .first { it.parent is dev.glassworks.engine.vitro.ui.HaGridLayout }.layerType
            return mapOf(
                "tile" to inGrid(vv.all().filterIsInstance<TileCardView>().single()),
                "markdown" to inGrid(vv.all().filterIsInstance<dev.glassworks.engine.vitro.cards.MarkdownCardView>().single()),
            )
        }
        assertEquals(mapOf("tile" to View.LAYER_TYPE_NONE, "markdown" to View.LAYER_TYPE_HARDWARE), layers("{}"))
        assertEquals(mapOf("tile" to View.LAYER_TYPE_NONE, "markdown" to View.LAYER_TYPE_NONE),
            layers("""{"ha-card-box-shadow": "0 2px 8px rgba(0,0,0,0.3)"}"""))

        // With RAM to spare every card gets a layer, the tile too; a shadow still keeps them all off it.
        val all = VitroView.CardLayers.ALL
        assertEquals(mapOf("tile" to View.LAYER_TYPE_HARDWARE, "markdown" to View.LAYER_TYPE_HARDWARE), layers("{}", all))
        assertEquals(mapOf("tile" to View.LAYER_TYPE_NONE, "markdown" to View.LAYER_TYPE_NONE),
            layers("""{"ha-card-box-shadow": "0 2px 8px rgba(0,0,0,0.3)"}""", all))
    }

    @Test
    fun everyCardGetsALayerFromTwoGibibytesOfRam() {
        // MemTotal as the devices read it: the Pixel Tablet's 8 GB and a 3 GB device get every card on a layer; the
        // S1's 2 GB and the Mirror's 1 GB keep the big-card rule.
        assertEquals(VitroView.CardLayers.ALL, VitroView.CardLayers.forTotalMem(7_802_585_088L))
        assertEquals(VitroView.CardLayers.ALL, VitroView.CardLayers.forTotalMem(2_800_000_000L))
        assertEquals(VitroView.CardLayers.ALL, VitroView.CardLayers.forTotalMem(2_147_483_648L))
        assertEquals(VitroView.CardLayers.BIG, VitroView.CardLayers.forTotalMem(2_071_752_704L))
        assertEquals(VitroView.CardLayers.BIG, VitroView.CardLayers.forTotalMem(975_106_048L))
        assertEquals(VitroView.CardLayers.BIG, VitroView.CardLayers.forTotalMem(0L)) // unknown: the cautious rule
    }

    @Test
    fun fixedCardLandsInTheOverlayLayer() {
        val store = EntityStore()
        store.putLocal(EntityState("light.lounge", "on", mapOf("friendly_name" to "Lounge")))
        store.putLocal(EntityState("binary_sensor.presence", "off", emptyMap()))
        val vv = vitroView(
            store,
            """{"views": [{"title": "Home", "type": "sections", "sections": [{"type": "grid", "cards": [
                 {"type": "tile", "entity": "light.lounge", "name": "Scrolling"},
                 {"type": "tile", "entity": "light.lounge", "name": "Pinned",
                  "visibility": [{"condition": "state", "entity": "binary_sensor.presence", "state": "on"}],
                  "card_mod": {"style": "ha-card { position: fixed; right: 24px; top: 50%; transform: translateY(-50%); width: 132px; transition: opacity 0.8s }"}}
               ]}]}]}""",
        )
        val pinned = vv.overlayLayer.all().filterIsInstance<TileCardView>()
        assertEquals(1, pinned.size)
        val lp = pinned.single().layoutParams as FrameLayout.LayoutParams
        assertEquals(Gravity.END or Gravity.CENTER_VERTICAL, lp.gravity)
        val density = activity.resources.displayMetrics.density
        assertEquals((24f * density).toInt(), lp.marginEnd)
        assertEquals((132f * density).toInt(), lp.width)
        assertEquals(0, lp.topMargin)

        // The scrolling layout keeps only the normal card.
        val scrolling = vv.all().filterIsInstance<TileCardView>().filter { it !in pinned }
        assertEquals(1, scrolling.size)
        assertFalse(scrolling.single().parentsInclude(vv.overlayLayer))

        // Its visibility still binds; detached, it flips at once.
        assertEquals(View.GONE, pinned.single().visibility)
        store.putLocal(EntityState("binary_sensor.presence", "on", emptyMap()))
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, pinned.single().visibility)
        assertEquals(1f, pinned.single().alpha)
    }

    @Test
    fun visibilityBeforeAttachTogglesSynchronously() {
        val store = EntityStore()
        store.putLocal(EntityState("input_boolean.guest", "off", emptyMap()))
        store.putLocal(EntityState("light.hall", "on", mapOf("friendly_name" to "Hall")))
        val vv = vitroView(
            store,
            """{"views": [{"title": "Home", "cards": [
                 {"type": "tile", "entity": "light.hall",
                  "visibility": [{"condition": "state", "entity": "input_boolean.guest", "state": "on"}]}
               ]}]}""",
        )
        assertEquals(0, vv.overlayLayer.all().size)
        val tile = vv.all().filterIsInstance<TileCardView>().single()
        assertFalse(tile.isAttachedToWindow)
        assertEquals(View.GONE, tile.visibility)
        assertEquals(0f, tile.alpha)

        store.putLocal(EntityState("input_boolean.guest", "on", emptyMap()))
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, tile.visibility)
        assertEquals(1f, tile.alpha)

        store.putLocal(EntityState("input_boolean.guest", "off", emptyMap()))
        ShadowLooper.idleMainLooper()
        assertEquals(View.GONE, tile.visibility)
    }

    @Test
    fun parsedFixedCardKeepsGradientBackgroundType() {
        // A pinned capsule's gradient comes from parseBackground, so it's a real LinearGradient.
        val mod = ThemeResolver.parseCardMod("ha-card { background-image: linear-gradient(180deg, #000000 0%, #ffffff 100%) }")
        assertTrue(mod.backgroundGradient is ThemeBackground.LinearGradient)
        assertEquals(180f, mod.backgroundGradient?.angleDeg)
    }

    private fun View.parentsInclude(target: View): Boolean {
        var p = parent
        while (p != null) {
            if (p === target) return true
            p = p.parent
        }
        return false
    }

    @Test
    fun aTemplateThatDropsItsPaddingAndSurfaceRulesGivesTheCardItsOwnBack() {
        val surface = object : dev.glassworks.engine.vitro.ui.CardSurface(context(EntityStore())) {}
        surface.setPadding(10, 10, 10, 10)
        val before = listOf(surface.paddingLeft, surface.paddingTop, surface.paddingRight, surface.paddingBottom)
        // Padding and a borderless, transparent surface, then the template drops both rules: several times over.
        repeat(3) {
            surface.applyStyleCss("ha-card { padding: 30px; background: transparent; border: none; }")
            // The override takes effect (no border left to add) before the template drops it.
            val px30 = (30 * activity.resources.displayMetrics.density).toInt()
            assertEquals(listOf(px30, px30, px30, px30), listOf(surface.paddingLeft, surface.paddingTop, surface.paddingRight, surface.paddingBottom))
            surface.applyStyleCss("")
        }
        // Back exactly where it started: no border inset added twice, and the theme border's inset restored.
        assertEquals(before, listOf(surface.paddingLeft, surface.paddingTop, surface.paddingRight, surface.paddingBottom))
    }

    /** A tile-like surface: content from the outer edge, and its drawn border readable. */
    private class OuterEdgeSurface(ctx: dev.glassworks.engine.vitro.ui.VitroCardContext) : dev.glassworks.engine.vitro.ui.CardSurface(ctx) {
        override val contentInsideBorder: Boolean get() = false
        val drawn: Int get() = drawnBorderPx
    }

    @Test
    fun aTemplateThatDropsItsBorderRuleGivesAnOuterEdgeCardItsOwnBorderBack() {
        val surface = OuterEdgeSurface(context(EntityStore()))
        val before = surface.drawn
        assertTrue("the theme draws a border", before > 0)
        surface.applyStyleCss("ha-card { border-color: #ff0000; }")
        surface.applyStyleCss("")
        assertEquals(before, surface.drawn)
    }

    /** A card that renders an entity and a template, as cards do in their setup. */
    private class Following(ctx: dev.glassworks.engine.vitro.ui.VitroCardContext) : dev.glassworks.engine.vitro.ui.CardSurface(ctx) {
        val states = ArrayList<String?>()
        val rendered = ArrayList<String>()
        init {
            observe("light.lounge_lamp") { states += it?.state }
            renewing { ctx.subscribeTemplate("{{ states('light.lounge_lamp') }}") { r -> ctx.main.post { rendered += r } } }
        }
    }

    @Test
    fun aCachedCardThatComesBackFollowsItsEntityAndTemplatesAgain() {
        val store = EntityStore().apply { putLocal(dev.glassworks.engine.vitro.data.EntityState("light.lounge_lamp", "on", emptyMap())) }
        val templates = Templates()
        val card = Following(context(store, templates))
        val root = android.widget.FrameLayout(activity)
        activity.setContentView(root)
        root.addView(card)
        ShadowLooper.idleMainLooper()
        assertEquals(listOf<String?>("on"), card.states)

        // Off screen (a cached view left the window): nothing reaches it, and its template is closed.
        root.removeView(card)
        store.putLocal(dev.glassworks.engine.vitro.data.EntityState("light.lounge_lamp", "off", emptyMap()))
        ShadowLooper.idleMainLooper()
        assertEquals(listOf<String?>("on"), card.states)
        assertTrue(templates.live.isEmpty())

        // Back: it catches up at once, and later changes and template renders reach it again.
        root.addView(card)
        ShadowLooper.idleMainLooper()
        assertEquals("off", card.states.last())
        store.putLocal(dev.glassworks.engine.vitro.data.EntityState("light.lounge_lamp", "on", emptyMap()))
        ShadowLooper.idleMainLooper()
        assertEquals("on", card.states.last())
        templates.push("lounge_lamp", "on")
        assertEquals(listOf("on"), card.rendered)
    }

    @Test
    fun aTemplatePictureThatChangesDropsTheOldPictureUntilTheNewOneLoads() {
        val templates = Templates()
        val tile = dev.glassworks.engine.vitro.cards.TileCardView(context(EntityStore(), templates), card(
            """{"type": "custom:mushroom-template-card", "icon": "mdi:account", "picture": "{{ states('sensor.who') }}"}""",
        ))
        val picture = tile.all().filterIsInstance<android.widget.ImageView>().first()
        templates.push("sensor.who", "/local/a.png")
        // Picture A on screen (its load stands in here), then the template moves to B, whose load fails.
        picture.setImageDrawable(android.graphics.drawable.ColorDrawable(0xFFFF0000.toInt()))
        templates.push("sensor.who", "/local/b.png")
        assertNull(picture.drawable)
        assertEquals(View.GONE, picture.visibility)
        assertTrue(tile.all().any { it is dev.glassworks.engine.vitro.ui.MdiIconView && it.visibility == View.VISIBLE })
    }

    @Test
    fun aMushroomCardBuiltBeforeItsStateIsBuiltAgainWhenItLands() {
        // The view opens before its entities' states arrive: the build guesses (controls assumed). When the states
        // land, the view is built again from them, as HA re-renders on a hass update.
        val store = EntityStore()
        // The engine's wait for the first states is off here: the build guesses at once (as after the wait runs out).
        val vv = vitroView(store, MUSHROOM_LIGHTS)
        activity.setContentView(vv)
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        fun mushrooms() = all(vv).count { it is dev.glassworks.engine.vitro.cards.MushroomLightCardView && it.isShown }
        assertEquals("guessed without states", 2, mushrooms())
        store.putLocal(dev.glassworks.engine.vitro.data.EntityState("light.switch_only", "on", mapOf("supported_color_modes" to listOf("onoff"))))
        store.putLocal(dev.glassworks.engine.vitro.data.EntityState("light.dimmer", "on", mapOf("supported_color_modes" to listOf("brightness"), "brightness" to 128)))
        ShadowLooper.idleMainLooper()
        // On/off only: the tile (one row). The dimmer keeps Mushroom's controls row (two rows).
        assertEquals(1, mushrooms())
        val dimmer = all(vv).single { it is dev.glassworks.engine.vitro.cards.MushroomLightCardView && it.isShown }
        val lp = (generateSequence(dimmer) { it.parent as? View }.first { it.layoutParams is dev.glassworks.engine.vitro.ui.HaGridLayout.LayoutParams }.layoutParams
            as dev.glassworks.engine.vitro.ui.HaGridLayout.LayoutParams)
        val oneRow = lp.fixedHeight!!
        assertTrue("two rows: $oneRow", oneRow > (100 * activity.resources.displayMetrics.density).toInt())
    }

    @Test
    fun aViewWaitsForItsFirstStatesAndIsBuiltOnce() {
        // With the engine's wait: nothing is built until the view's states land, then once, from them.
        val store = EntityStore()
        val builds = ArrayList<Boolean>()
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        vv.holdFirstBuildForStatesMs = 1_000
        vv.onMetrics = { m -> builds += m.cacheHit }
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
                accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            )
        }
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject(MUSHROOM_LIGHTS)), null)
        activity.setContentView(vv)
        ShadowLooper.idleMainLooper()
        assertTrue("nothing built before the states", builds.isEmpty())
        store.putLocal(dev.glassworks.engine.vitro.data.EntityState("light.switch_only", "on", mapOf("supported_color_modes" to listOf("onoff"))))
        store.putLocal(dev.glassworks.engine.vitro.data.EntityState("light.dimmer", "on", mapOf("supported_color_modes" to listOf("brightness"))))
        ShadowLooper.idleMainLooper()
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("built once, not built again", listOf(false), builds)
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        assertEquals(1, all(vv).count { it is dev.glassworks.engine.vitro.cards.MushroomLightCardView && it.isShown })
    }

    @Test
    fun aViewWhoseStatesDontComeIsBuiltAfterTheWait() {
        val store = EntityStore()
        val vv = vitroView(store, MUSHROOM_LIGHTS, holdMs = 1_000)
        activity.setContentView(vv)
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        ShadowLooper.idleMainLooper()
        assertEquals(0, all(vv).count { it is dev.glassworks.engine.vitro.cards.MushroomLightCardView })
        ShadowLooper.idleMainLooper(1_100, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("built after the wait, guessing", 2, all(vv).count { it is dev.glassworks.engine.vitro.cards.MushroomLightCardView && it.isShown })
        // The states land late: the guessed cards are built again from them.
        store.putLocal(dev.glassworks.engine.vitro.data.EntityState("light.switch_only", "on", mapOf("supported_color_modes" to listOf("onoff"))))
        ShadowLooper.idleMainLooper()
        assertEquals(1, all(vv).count { it is dev.glassworks.engine.vitro.cards.MushroomLightCardView && it.isShown })
    }

    @Test
    fun aLateStateOfAnOlderWaitDoesntEndANewerOne() {
        val store = EntityStore()
        val vv = vitroView(store, MUSHROOM_LIGHTS, holdMs = 1_000)
        activity.setContentView(vv)
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        ShadowLooper.idleMainLooper()
        // A state of the first dashboard lands: its wait's end is queued, not run yet.
        store.putLocal(dev.glassworks.engine.vitro.data.EntityState("light.switch_only", "on", mapOf("supported_color_modes" to listOf("onoff"))))
        // Another dashboard comes first, and its view (the same index) waits in turn.
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject(OTHER_LIGHT)), null)
        ShadowLooper.idleMainLooper()
        assertEquals("the old wait's end didn't build the new view", 0, all(vv).count { it is dev.glassworks.engine.vitro.cards.MushroomLightCardView })
        // The new view's own state still ends its wait.
        store.putLocal(dev.glassworks.engine.vitro.data.EntityState("light.other", "on", mapOf("supported_color_modes" to listOf("brightness"))))
        ShadowLooper.idleMainLooper()
        assertEquals(1, all(vv).count { it is dev.glassworks.engine.vitro.cards.MushroomLightCardView && it.isShown })
    }

    @Test
    fun theViewShownBeforeIsHiddenWhileTheNextOneWaits() {
        val store = EntityStore()
        store.putLocal(dev.glassworks.engine.vitro.data.EntityState("light.switch_only", "on", mapOf("supported_color_modes" to listOf("onoff"))))
        store.putLocal(dev.glassworks.engine.vitro.data.EntityState("light.dimmer", "on", mapOf("supported_color_modes" to listOf("brightness"))))
        val vv = vitroView(store, TWO_VIEWS, holdMs = 1_000)
        activity.setContentView(vv)
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        fun shown() = all(vv).filterIsInstance<dev.glassworks.engine.vitro.cards.MushroomLightCardView>().filter { it.isShown }
        ShadowLooper.idleMainLooper()
        // (The switch-only light is built as another card: one light card with brightness.)
        assertEquals("the first view has its states: built at once", 1, shown().size)
        val first = shown().single()
        // The second view's entity has no state yet: it waits, and the first view's cards aren't left tappable under it.
        vv.navigateToView("other")
        ShadowLooper.idleMainLooper()
        assertEquals(0, shown().size)
        store.putLocal(dev.glassworks.engine.vitro.data.EntityState("light.other", "on", mapOf("supported_color_modes" to listOf("brightness"))))
        ShadowLooper.idleMainLooper()
        assertTrue("the second view's card", shown().single() !== first)
        // Back to the first view: cached, shown at once.
        vv.navigateToView("home")
        ShadowLooper.idleMainLooper()
        assertTrue("the first view's own card", shown().single() === first)
    }

    private companion object {
        const val MUSHROOM_LIGHTS = """{"views": [{"title": "Home", "type": "sections", "sections": [{"type": "grid", "cards": [
            {"type": "custom:mushroom-light-card", "entity": "light.switch_only", "show_brightness_control": true},
            {"type": "custom:mushroom-light-card", "entity": "light.dimmer", "show_brightness_control": true}]}]}]}"""
        const val OTHER_LIGHT = """{"views": [{"title": "Other", "type": "sections", "sections": [{"type": "grid", "cards": [
            {"type": "custom:mushroom-light-card", "entity": "light.other", "show_brightness_control": true}]}]}]}"""
        const val TWO_VIEWS = """{"views": [
            {"title": "Home", "path": "home", "type": "sections", "sections": [{"type": "grid", "cards": [
                {"type": "custom:mushroom-light-card", "entity": "light.switch_only", "show_brightness_control": true},
                {"type": "custom:mushroom-light-card", "entity": "light.dimmer", "show_brightness_control": true}]}]},
            {"title": "Other", "path": "other", "type": "sections", "sections": [{"type": "grid", "cards": [
                {"type": "custom:mushroom-light-card", "entity": "light.other", "show_brightness_control": true}]}]}]}"""
    }
}
