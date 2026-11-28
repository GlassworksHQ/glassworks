/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.MarkdownCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** A Markdown card whose template changes only text updates its text views in place, drawing what a rebuild draws. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownInPlaceTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private val SHEET = "ha-markdown-element h6 { font-size: 12px; letter-spacing: 0.14em; text-transform: uppercase; margin: 0 } " +
        "ha-markdown-element h1 { font-size: 56px; font-weight: 300; line-height: 1; margin: 6px 0 4px }"

    /** A templated Markdown card and a way to push its next rendered content. */
    private fun card(): Pair<MarkdownCardView, (String) -> Unit> {
        var push: (String) -> Unit = {}
        val ctx = VitroCardContext(
            context = activity,
            haBase = FakeHaServer.BASE,
            store = EntityStore(),
            theme = ThemeResolver.resolve(ThemeCatalog(), true),
            resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN },
            callService = { _, _, _, _ -> },
            navigate = {},
            openMoreInfo = {},
            fetchHistory = { _, _, _ -> },
            subscribeTemplate = { _, cb -> push = cb; {} },
        )
        val cfg = JSONObject().put("type", "markdown").put("content", "{{ now() }}")
            .put("card_mod", JSONObject().put("style", JSONObject().put("ha-markdown$", SHEET)))
        val view = MarkdownCardView(ctx, dev.glassworks.engine.vitro.model.LovelaceParser.parseCard(cfg))
        return view to { s -> push(s); ShadowLooper.idleMainLooper() }
    }

    private fun ViewGroup.texts(): List<TextView> = (0 until childCount).flatMap { i ->
        when (val c = getChildAt(i)) {
            is TextView -> listOf(c)
            is ViewGroup -> c.texts()
            else -> emptyList()
        }
    }

    private fun draw(v: View): IntArray {
        v.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    }

    @Test
    fun aChangedMinuteUpdatesTextInPlaceAndDrawsLikeARebuild() {
        val (updated, pushA) = card()
        pushA("###### Good night, Edu\n# 4:57 AM\nTuesday, November 24")
        draw(updated) // laid out first, so the update below changes text views that already have a layout
        val before = updated.texts()
        pushA("###### Good night, Edu\n# 4:58 AM\nTuesday, November 24")
        val after = updated.texts()
        assertEquals(before.size, after.size)
        before.indices.forEach { assertSame(before[it], after[it]) }
        assertTrue(after.any { "4:58" in it.text.toString() })

        val (fresh, pushB) = card()
        pushB("###### Good night, Edu\n# 4:58 AM\nTuesday, November 24")
        assertTrue("the in-place card draws exactly what a fresh one does", draw(updated).contentEquals(draw(fresh)))
    }

    @Test
    fun aChangedStructureIsBuiltAgain() {
        val (view, push) = card()
        push("# 4:57 AM")
        val before = view.texts().single()
        push("# 4:58 AM\n\nnew paragraph")
        assertNotSame(before, view.texts().first())
    }
}
