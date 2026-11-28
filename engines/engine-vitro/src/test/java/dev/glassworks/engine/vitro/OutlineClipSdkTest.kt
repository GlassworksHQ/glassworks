/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.glassworks.engine.ScrollTuning
import dev.glassworks.engine.vitro.cards.EntitiesCardView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.OutlineClip
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * [ScrollTuning.Knob.CLIP] on the Android versions the panels run (the S1's Android 10, Android 8), not only the
 * newest: up to Android 10 `GradientDrawable.getCornerRadii()` clones its array without a null check, so it throws
 * for a drawable rounded with `setCornerRadius()`, which is every card's (an S1 run, 2026-11-28: every card showed
 * its error card). The outline clip reads no radius back from that getter.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [26, 29], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class OutlineClipSdkTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()

    @After fun reset() = ScrollTuning.resetForTest()

    private fun ctx() = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> },
        navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    /** [card] on screen at a column's width, drawn once (where its clip is decided and its draw guarded). */
    private fun show(card: CardSurface): CardSurface {
        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(card, FrameLayout.LayoutParams(476, ViewGroup.LayoutParams.WRAP_CONTENT))
        idle()
        val bmp = Bitmap.createBitmap(card.width.coerceAtLeast(1), card.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        card.draw(Canvas(bmp))
        idle()
        return card
    }

    @Test
    fun aCardRoundedWithSetCornerRadiusDrawsAndDecidesItsClip() {
        ScrollTuning.setForTest(ScrollTuning.Knob.CLIP, true)
        // The theme's card: a GradientDrawable rounded with setCornerRadius (CardSurface.applyTheme).
        val inside = object : CardSurface(ctx()) {}
        assertTrue((inside.background as GradientDrawable).let { it.cornerRadius > 0f })
        inside.addView(View(activity).apply { setBackgroundColor(0xFF00FF00.toInt()) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40).apply { setMargins(16, 16, 16, 16) })
        show(inside)
        assertNull("drew without failing", inside.failure)
        assertNull(OutlineClip.needsClip(inside))
        assertFalse(inside.clipToOutline)

        val corner = object : CardSurface(ctx()) {}
        corner.addView(View(activity).apply { setBackgroundColor(0xFF00FF00.toInt()) }, FrameLayout.LayoutParams(476, 40))
        show(corner)
        assertNull(corner.failure)
        assertNotNull(OutlineClip.needsClip(corner))
        assertTrue(corner.clipToOutline)
    }

    @Test
    fun heavysCardsDrawWithTheKnobOn() {
        ScrollTuning.setForTest(ScrollTuning.Knob.CLIP, true)
        store.putLocal(EntityState("light.kitchen", "on", mapOf("friendly_name" to "Kitchen", "brightness" to 180), 0.0, 0.0))
        store.putLocal(EntityState("switch.fan", "off", mapOf("friendly_name" to "Fan"), 0.0, 0.0))
        val tile = show(TileCardView(ctx(), LovelaceParser.parseCard(JSONObject("""{"type": "tile", "entity": "light.kitchen"}"""))))
        val entities = show(EntitiesCardView(ctx(), LovelaceParser.parseCard(JSONObject("""{"type": "entities", "entities": ["light.kitchen", "switch.fan"]}"""))))
        for (card in listOf(tile, entities)) {
            assertNull("${card.javaClass.simpleName} drew without failing", card.failure)
            assertFalse(card.clipToOutline)
        }
    }

    @Test
    fun aRippleRoundedLikeTheCardKeepsNoClipAndAGradientWithFourRadiiKeepsIt() {
        ScrollTuning.setForTest(ScrollTuning.Knob.CLIP, true)
        val card = object : CardSurface(ctx()) {}
        // A child filling the card, its background rounded per corner (setCornerRadii): unknown radius, clip kept.
        card.addView(View(activity).apply {
            background = GradientDrawable().apply { cornerRadii = FloatArray(8) { 4f }; setColor(0xFF223344.toInt()) }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60))
        show(card)
        assertNull(card.failure)
        assertTrue(card.clipToOutline)
        assertEquals(true, OutlineClip.needsClip(card)?.isNotEmpty())
    }
}
