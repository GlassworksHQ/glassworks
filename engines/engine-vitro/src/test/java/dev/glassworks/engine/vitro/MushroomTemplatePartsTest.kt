/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Mushroom 5.3.0's template card draws its icon only for an `icon` or a `picture`, and its text only for a `primary`
 * or a `secondary` (template-card.ts `render()`): no `mdi:home` stand-in pushing the text across a small tile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MushroomTemplatePartsTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    /** Template subscriptions by template text: a test renders one by calling its callback. */
    private val templates = LinkedHashMap<String, (String) -> Unit>()

    private fun context() = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = EntityStore(),
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
        fetchHistory = { _, _, _ -> },
        subscribeTemplate = { tpl, onRendered -> templates[tpl] = onRendered; {} },
    )

    private fun card(json: String): TileCardView {
        val card = TileCardView(context(), LovelaceParser.parseCard(JSONObject(json)))
        val dp = activity.resources.displayMetrics.density
        card.measure(View.MeasureSpec.makeMeasureSpec((180 * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        return card
    }

    private fun relayout(card: TileCardView) {
        shadowOf(Looper.getMainLooper()).idle()
        card.measure(View.MeasureSpec.makeMeasureSpec(card.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
    }

    /** [v]'s left edge in the card, in CSS px. */
    private fun leftIn(card: View, v: View): Float {
        var x = 0f
        var cur: View = v
        while (cur !== card) { x += cur.left + cur.translationX; cur = cur.parent as View }
        return x / activity.resources.displayMetrics.density
    }

    private fun shown(card: View, v: View): Boolean {
        var cur: View = v
        while (cur !== card) { if (cur.visibility != View.VISIBLE) return false; cur = cur.parent as View }
        return true
    }

    private fun textView(card: View, text: String): TextView {
        fun walk(v: View): TextView? = when {
            v is TextView && v.text.toString() == text -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { walk(v.getChildAt(it)) }
            else -> null
        }
        return walk(card)!!
    }

    @Test
    fun thePartsFollowMushroomsRender() {
        // JavaScript truthiness of each rendered value: an empty string (or a template not rendered yet) is nothing.
        val text = TileCardView.MushroomTemplateParts.of("", "", "Living Room", "Lights off")
        assertFalse("no icon and no picture: no ha-tile-icon", text.icon)
        assertTrue(text.info)
        assertTrue(text.content)
        assertTrue(TileCardView.MushroomTemplateParts.of("", "/local/p.png", "", "").icon)
        assertFalse("an icon alone has no ha-tile-info", TileCardView.MushroomTemplateParts.of("mdi:sofa", "", "", "").info)
        assertTrue(TileCardView.MushroomTemplateParts.of("", "", "", "Lights off").info)
        assertFalse("nothing at all: no content row", TileCardView.MushroomTemplateParts.of("", "", "", "").content)
    }

    @Test
    fun aCardWithTextAndNoIconDrawsNoIconAndStartsItsTextAtTheContentPadding() {
        val card = card("""{"type": "custom:mushroom-template-card", "primary": "Living Room", "secondary": "Lights off"}""")
        val views = card.mushroom!!
        assertFalse("no icon, no shape", shown(card, views.avatar))
        assertFalse(shown(card, views.icon))
        val primary = textView(card, "Living Room")
        assertTrue(shown(card, primary))
        // .content's `padding: 0 10px`: the info starts 10 px in (with the icon, 10 + 36 + 10 = 56).
        assertEquals(10f, leftIn(card, primary), 0.6f)
        // The card's own 56 px row stays: Mushroom's .content min-height.
        assertEquals(56f, card.measuredHeight / activity.resources.displayMetrics.density, 1f)
    }

    @Test
    fun aBadgeLivesInTheIconSoNoIconMeansNoBadge() {
        val card = card("""{"type": "custom:mushroom-template-card", "primary": "Hall", "badge_icon": "mdi:alert", "badge_color": "red"}""")
        assertFalse(shown(card, card.mushroom!!.badge))
        val withIcon = card("""{"type": "custom:mushroom-template-card", "primary": "Hall", "icon": "mdi:door", "badge_icon": "mdi:alert"}""")
        assertTrue(shown(withIcon, withIcon.mushroom!!.badge))
        assertEquals(56f, leftIn(withIcon, textView(withIcon, "Hall")), 0.6f)
    }

    @Test
    fun anIconAloneDrawsNoTextBlock() {
        val card = card("""{"type": "custom:mushroom-template-card", "icon": "mdi:sofa"}""")
        val views = card.mushroom!!
        assertTrue(shown(card, views.icon))
        assertFalse(shown(card, views.primary))
        assertFalse("ha-tile-info isn't rendered", shown(card, views.primary.parent as View))
    }

    @Test
    fun aCardWithNothingDrawsNoContent() {
        val card = card("""{"type": "custom:mushroom-template-card", "primary": "", "icon": ""}""")
        assertFalse(shown(card, card.mushroom!!.row))
    }

    @Test
    fun aTemplatedIconThatRendersEmptyDrawsNoIcon() {
        val card = card("""{"type": "custom:mushroom-template-card", "primary": "Desk",
            "icon": "{{ 'mdi:lamp' if is_state('light.desk', 'on') else '' }}"}""")
        val views = card.mushroom!!
        // Not rendered yet: Mushroom's getValue is undefined, so no icon (never a stand-in).
        assertFalse(shown(card, views.icon))
        val render = templates.entries.single { "mdi:lamp" in it.key }.value
        render("mdi:lamp")
        relayout(card)
        assertTrue(shown(card, views.icon))
        assertEquals(56f, leftIn(card, textView(card, "Desk")), 0.6f)
        render("")
        relayout(card)
        assertFalse(shown(card, views.avatar))
        assertEquals(10f, leftIn(card, textView(card, "Desk")), 0.6f)
    }

    @Test
    fun aVerticalCardWithNoIconCentersItsTextAlone() {
        val card = card("""{"type": "custom:mushroom-template-card", "vertical": true, "primary": "Living Room", "secondary": "Lights off"}""")
        assertFalse(shown(card, card.mushroom!!.avatar))
        assertTrue(shown(card, textView(card, "Living Room")))
    }

    @Test
    fun gridRowsFollowMushroomsGetGridOptions() {
        fun rows(json: String) = TileCardView.mushroomTemplateGridRows(JSONObject(json))
        assertEquals(1, rows("""{"primary": "A"}"""))
        assertEquals(1, rows("""{"icon": "mdi:sofa"}"""))
        assertNull("no content, no features: 0 rows", rows("""{"primary": ""}"""))
        // Vertical: one more row for a primary, or a secondary without an icon; an icon with a secondary alone, none.
        assertEquals(2, rows("""{"vertical": true, "icon": "mdi:sofa", "primary": "A"}"""))
        assertEquals(2, rows("""{"vertical": true, "secondary": "B"}"""))
        assertEquals(1, rows("""{"vertical": true, "icon": "mdi:sofa", "secondary": "B"}"""))
        assertEquals(1, rows("""{"vertical": true, "icon": "mdi:sofa"}"""))
        // The legacy layout counts only when `vertical` isn't set (migrateTemplateCardConfig).
        assertEquals(2, rows("""{"layout": "vertical", "primary": "A"}"""))
        assertEquals(1, rows("""{"layout": "vertical", "vertical": false, "primary": "A"}"""))
        assertNull(rows("""{"multiline_secondary": true, "primary": "A"}"""))
    }
}
