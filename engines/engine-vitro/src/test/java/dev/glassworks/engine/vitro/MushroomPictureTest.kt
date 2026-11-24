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
import dev.glassworks.engine.vitro.cards.CircleCropDrawable
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
    fun aPictureIsCroppedToACircleWhateverDrawsIt() {
        val src = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val out = Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888)
        CircleCropDrawable(src).apply { setBounds(0, 0, 36, 36) }.draw(Canvas(out))
        assertEquals("the corner is outside the circle", 0, Color.alpha(out.getPixel(1, 1)))
        assertEquals("the middle is the picture", Color.RED, out.getPixel(18, 18))
        assertEquals("a wide picture still fills the circle's height", Color.RED, out.getPixel(18, 2))
    }

    @Test
    fun cardModPaddingMovesTheContainerWithoutShrinkingIt() {
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = EntityStore(),
            theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        )
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
}
