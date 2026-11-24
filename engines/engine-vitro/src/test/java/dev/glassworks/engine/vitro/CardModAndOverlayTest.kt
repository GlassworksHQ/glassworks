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
        assertNull(mod.backgroundColor)
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

    private fun vitroView(store: EntityStore, dashboard: String): VitroView {
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity,
                haBase = FakeHaServer.BASE,
                store = store,
                theme = theme,
                resources = emptyList(),
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
}
