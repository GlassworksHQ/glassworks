/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.View
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.MushroomTitleCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mushroom 5's title card: its sizes and spacing, the theme's `--mush-title-*` overrides, alignment, the chevron. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MushroomTitleCardTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun card(config: String, themeVars: String = "{}"): MushroomTitleCardView {
        val theme = ThemeResolver.resolve(ThemeCatalog.parse(JSONObject("""{"themes": {"t": $themeVars}}""")), true, "t")
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = EntityStore(),
            theme = theme, resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        )
        return MushroomTitleCardView(ctx, LovelaceParser.parseCard(JSONObject(config)))
    }

    private fun lines(v: MushroomTitleCardView) = (0 until v.childCount).map { v.getChildAt(it) as TextView }

    private fun layout(v: View) {
        val w = (500 * activity.resources.displayMetrics.density).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    @Test
    fun aTitleAndSubtitleAreMushroomsDefault96Px() {
        val v = card("""{"type": "custom:mushroom-title-card", "title": "Living room", "subtitle": "Lights and blinds"}""")
        layout(v)
        val dp = activity.resources.displayMetrics.density
        // 24 padding + 32 title line + 8 spacing + 24 subtitle line + 8 padding.
        assertEquals(96f, v.measuredHeight / dp, 1f)
        val (title, subtitle) = lines(v)
        assertEquals(24f, title.textSize / dp, 0.5f)
        assertEquals(16f, subtitle.textSize / dp, 0.5f)
        assertEquals(32f, title.measuredHeight / dp, 1f)
        assertEquals(24f, subtitle.measuredHeight / dp, 1f)
    }

    @Test
    fun theThemesMushroomVariablesAndTheAlignmentApply() {
        val v = card("""{"type": "custom:mushroom-title-card", "title": "Hi", "alignment": "center"}""",
            """{"mush-title-font-size": "18px", "mush-title-padding": "4px 8px"}""")
        val dp = activity.resources.displayMetrics.density
        val title = lines(v).single()
        assertEquals(18f, title.textSize / dp, 0.5f)
        assertEquals(4f, v.paddingTop / dp, 0.5f)
        assertEquals(8f, v.paddingLeft / dp, 0.5f)
        assertTrue(title.gravity and android.view.Gravity.CENTER_HORIZONTAL == android.view.Gravity.CENTER_HORIZONTAL)
        assertArrayEquals(floatArrayOf(24f, 12f, 8f, 12f), MushroomTitleCardView.paddingDp("24px 12px 8px"), 0f)
        assertNull(MushroomTitleCardView.paddingDp("10% 2px"))
    }

    @Test
    fun aTapActionAddsTheChevron() {
        fun chevrons(v: MushroomTitleCardView) = lines(v).map { t -> (t.text as Spanned).getSpans(0, t.text.length, ImageSpan::class.java).size }
        assertEquals(listOf(1, 0), chevrons(card("""{"type": "custom:mushroom-title-card", "title": "A", "subtitle": "B",
            "title_tap_action": {"action": "navigate", "navigation_path": "/x"}}""")))
        assertEquals(listOf(0), chevrons(card("""{"type": "custom:mushroom-title-card", "title": "A", "title_tap_action": {"action": "none"}}""")))
    }
}
