/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.CircleCropDrawable
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Mushroom 5's template card: its picture is cropped round, its 56 px container moved (not shrunk) by card-mod padding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MushroomPictureTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    @Test
    fun theIconTogglesByDefaultForATogglingDomainAsInMushroom() {
        // Mushroom 5's template card: icon_tap_action defaults to toggle for these domains, so the icon is interactive
        // and draws its tinted shape (the air purifier in a stack-in-card); a climate or a sensor gets none.
        for (e in listOf("fan.air_purifier", "light.lamp", "switch.x", "input_boolean.x", "scene.x", "valve.x")) {
            assertEquals(e, "toggle", TileCardView.mushroomIconDefault(e))
        }
        for (e in listOf("climate.ac", "sensor.co2", "media_player.tv", null)) {
            assertEquals("$e", "none", TileCardView.mushroomIconDefault(e))
        }
    }

    @Test
    fun aPictureIsCroppedToACircleWhateverDrawsIt() {
        val src = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val out = Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888)
        CircleCropDrawable(src).apply { setBounds(0, 0, 36, 36) }.draw(Canvas(out))
        assertEquals("the corner is outside the circle", 0, Color.alpha(out.getPixel(1, 1)))
        assertEquals("the middle is the picture", Color.RED, out.getPixel(18, 18))
        assertEquals("a wide picture still fills the circle's height", Color.RED, out.getPixel(18, 2))
    }

    private fun context() = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = EntityStore(),
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
    )

    @Test
    fun cardModPaddingMovesTheContainerWithoutShrinkingIt() {
        val ctx = context()
        val card = TileCardView(ctx, LovelaceParser.parseCard(JSONObject("""{"type": "custom:mushroom-template-card",
            "primary": "A", "secondary": "B", "card_mod": {"style": "ha-card { padding: 8px 14px; }"}}""")))
        card.applyCardMod(LovelaceParser.parseCard(JSONObject("""{"type": "x", "card_mod": {"style": "ha-card { padding: 8px 14px; }"}}""")))
        val dp = activity.resources.displayMetrics.density
        val h = (56 * dp).toInt()
        card.measure(View.MeasureSpec.makeMeasureSpec((246 * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, card.measuredWidth, h)
        val row = (card as ViewGroup).getChildAt(0)
        assertEquals("starts under the top padding", card.paddingTop, row.top)
        assertEquals("keeps its 56 px", (56 * dp).toInt(), row.height)
    }

    /** A vertical card's text lines' tops in the card, in CSS px, and the card's height (the Mirror's lights stack). */
    private fun verticalGeometry(layout: String?): Triple<Float, Float, Float> {
        val ctx = context()
        ctx.cardLayout = layout
        val style = "ha-card { border: 1px solid rgba(255,255,255,0.07); border-radius: 24px; padding: 12px 10px; }"
        val json = """{"type": "custom:mushroom-template-card", "layout": "vertical", "icon": "mdi:sofa",
            "primary": "Living", "secondary": "Lights off", "card_mod": {"style": "$style"}}"""
        val card = TileCardView(ctx, LovelaceParser.parseCard(JSONObject(json)))
        card.applyCardMod(LovelaceParser.parseCard(JSONObject("""{"type": "x", "card_mod": {"style": "$style"}}""")))
        val dp = activity.resources.displayMetrics.density
        card.measure(
            View.MeasureSpec.makeMeasureSpec((152 * dp).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        fun top(v: View): Float {
            var y = 0f
            var cur: View = v
            while (cur !== card) { y += cur.top + cur.translationY; cur = cur.parent as View }
            return y / dp
        }
        fun find(text: String): TextView {
            fun walk(v: View): TextView? = when {
                v is TextView && v.text.toString() == text -> v
                v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { walk(v.getChildAt(it)) }
                else -> null
            }
            return walk(card)!!
        }
        return Triple(top(find("Living")), top(find("Lights off")), card.measuredHeight / dp)
    }

    @Test
    @Config(qualifiers = "w720dp-h1280dp-port-hdpi") // the Mirror's
    fun aVerticalCardOutsideASectionGridStacksTheInfoUnderTheIconAsMushroomDoes() {
        // Mushroom 5, no fixed-info-height: border 1 + padding 12, less .container's -1 px margin, + .content padding 10
        // + the 36 px shape (ha-tile-icon's 6 px padding taken back by its -6 px margin) + the 10 px gap, then
        // ha-tile-info's 22.4 px primary line and 14.4 px secondary line, then 10 - 1 + 12 + 1. HA's render of the
        // Mirror's lights stack: card edges ~81.0 / ~206.7 CSS px, primary text 70.6 under the top (line 67.4).
        val (primary, secondary, height) = verticalGeometry(layout = null)
        assertEquals(68f, primary, 1f)
        assertEquals(90.4f, secondary, 1f)
        assertEquals(126.8f, height, 1f)
    }

    @Test
    @Config(qualifiers = "w720dp-h1280dp-port-hdpi") // the Mirror's
    fun aVerticalCardStraightInASectionGridKeepsTheFixedInfoHeight() {
        // `fixed-info-height`: the primary is centered in a 32 px box, so the secondary sits lower than outside a grid.
        val (_, secondary, _) = verticalGeometry(layout = "grid")
        assertNotEquals(90.4f, secondary, 3f)
    }
}
