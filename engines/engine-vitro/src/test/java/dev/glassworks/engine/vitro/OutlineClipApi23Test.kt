/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The outline clip on Android 6, the legacy build's floor (the Mirror): reading a radius back needs Android 7
 * (`Outline.getRadius`, behind `Platform.hasGradientDrawableGetters`), so below it nothing calls it and every rounded
 * card keeps its clip, as it did before. ([OutlineClipSdkTest] covers Android 8 and 10, where the clip is decided.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class OutlineClipApi23Test {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()

    private fun ctx() = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> },
        navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
    )

    private fun show(card: CardSurface): CardSurface {
        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(card, FrameLayout.LayoutParams(476, ViewGroup.LayoutParams.WRAP_CONTENT))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        card.draw(Canvas(Bitmap.createBitmap(card.width.coerceAtLeast(1), card.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        return card
    }

    @Test
    fun everyRoundedCardDrawsAndKeepsItsClip() {
        store.putLocal(EntityState("light.kitchen", "on", mapOf("friendly_name" to "Kitchen", "brightness" to 180), 0.0, 0.0))
        store.putLocal(EntityState("switch.fan", "off", mapOf("friendly_name" to "Fan"), 0.0, 0.0))
        val inside = object : CardSurface(ctx()) {}
        inside.addView(View(activity), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40).apply { setMargins(16, 16, 16, 16) })
        val cards = listOf(
            show(inside),
            show(TileCardView(ctx(), LovelaceParser.parseCard(JSONObject("""{"type": "tile", "entity": "light.kitchen"}""")))),
            show(EntitiesCardView(ctx(), LovelaceParser.parseCard(JSONObject("""{"type": "entities", "entities": ["light.kitchen", "switch.fan"]}""")))),
        )
        for (card in cards) {
            assertNull("${card.javaClass.simpleName} drew without failing", card.failure)
            assertEquals("background shape", OutlineClip.needsClip(card))
            assertTrue("${card.javaClass.simpleName} keeps its clip", card.clipToOutline)
        }
    }
}
