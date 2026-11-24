/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.MushroomChipsCardView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Markdown, Mushroom template / chips and the newer tile features, rendered natively. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CoreParityTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private class Call(val domain: String, val service: String, val data: JSONObject?) {
        override fun toString() = "$domain.$service ${data?.optString("entity_id")}"
    }

    /** Every `render_template` subscription a card made, by template, with a way to push a rendered value. */
    private class Templates {
        val live = LinkedHashMap<String, (String) -> Unit>()
        var unsubscribed = 0
        fun push(match: String, value: String) {
            val key = live.keys.firstOrNull { match in it } ?: throw AssertionError("no template containing '$match' in ${live.keys}")
            live.getValue(key)(value)
            ShadowLooper.idleMainLooper()
        }
    }

    private fun context(store: EntityStore, calls: MutableList<Call> = ArrayList(), templates: Templates = Templates()) =
        VitroCardContext(
            context = activity,
            haBase = FakeHaServer.BASE,
            store = store,
            theme = ThemeResolver.resolve(ThemeCatalog(), true),
            resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN },
            callService = { domain, service, data, _ -> calls += Call(domain, service, data) },
            navigate = {},
            openMoreInfo = {},
            fetchHistory = { _, _, _ -> },
            subscribeTemplate = { tpl, cb ->
                templates.live[tpl] = cb
                ({ templates.unsubscribed++ })
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

    private fun View.byDescription(text: String): View {
        val out = ArrayList<View>()
        findViewsWithText(out, text, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        return out.firstOrNull { it.contentDescription?.toString() == text }
            ?: throw AssertionError("no view described '$text'")
    }

    // ---- Markdown blocks ----

    // ---- Markdown inline ----

    private inline fun <reified T : Any> Spanned.spansOf(): List<T> =
        getSpans(0, length, T::class.java).sortedBy { getSpanStart(it) }

    private fun Spanned.covered(span: Any): String = subSequence(getSpanStart(span), getSpanEnd(span)).toString()

    // ---- Markdown card ----

    // ---- Mushroom template card ----

    @Test
    fun mushroomTemplateRendersTemplatedColorBadgeAndLayout() {
        val store = EntityStore()
        store.putLocal(EntityState("light.desk", "on", mapOf("friendly_name" to "Desk")))
        val templates = Templates()
        val tile = TileCardView(
            context(store, templates = templates),
            card(
                """{"type": "custom:mushroom-template-card", "entity": "light.desk",
                   "primary": "Desk", "secondary": "{{ states(entity) }} for a while, and a long tail of words",
                   "icon": "mdi:lightbulb", "icon_color": "{{ 'amber' if is_state('light.desk', 'on') else 'disabled' }}",
                   "badge_icon": "mdi:lock", "badge_color": "red",
                   "layout": "vertical", "multiline_secondary": true}""",
            ),
        )
        assertNotNull(tile.mushroom)
        val m = tile.mushroom!!
        assertEquals(LinearLayout.VERTICAL, m.row.orientation)
        assertEquals("Desk", m.primary.text.toString())
        assertEquals(Int.MAX_VALUE, m.secondary.maxLines)
        assertEquals(View.VISIBLE, m.badge.visibility)
        assertEquals(0xFFF44336.toInt(), m.badgeColor)

        // `entity` is handed to HA's render_template as a variable.
        assertTrue(templates.live.keys.any { it.startsWith("{% set entity = 'light.desk' %}") && "states(entity)" in it })
        templates.push("is_state", "amber")
        assertEquals(0xFFFFC107.toInt(), m.iconColor)
        assertEquals(0xFFFFC107.toInt(), m.icon.tintColor)
        templates.push("states(entity)", "on for a while")
        assertEquals("on for a while", m.secondary.text.toString())
        templates.push("is_state", "disabled")
        assertEquals(0xFFBDBDBD.toInt(), m.iconColor)
    }

    @Test
    fun mushroomTemplateDefaultsToAHorizontalSingleLineWithoutBadge() {
        val tile = TileCardView(
            context(EntityStore()),
            card("""{"type": "custom:mushroom-template-card", "primary": "Hello", "secondary": "World", "icon_color": "#123456"}"""),
        )
        val m = tile.mushroom!!
        assertEquals(LinearLayout.HORIZONTAL, m.row.orientation)
        assertEquals(1, m.secondary.maxLines)
        assertEquals(View.GONE, m.badge.visibility)
        assertEquals(0xFF123456.toInt(), m.iconColor)
    }

    // ---- Mushroom chips ----

    @Test
    fun templateChipsRenderLiveAndNeverShowRawJinja() {
        val store = EntityStore()
        val templates = Templates()
        val chips = MushroomChipsCardView(
            context(store, templates = templates),
            card(
                """{"type": "custom:mushroom-chips-card", "chips": [
                     {"type": "template", "content": "{{ states('sensor.outside') }} °C", "icon": "{{ 'mdi:fire' }}",
                      "icon_color": "{{ 'red' }}"}
                   ]}""",
            ),
        )
        assertFalse(chips.all().filterIsInstance<TextView>().any { "{{" in it.text || "{%" in it.text })
        templates.push("sensor.outside", "12 °C")
        assertEquals(listOf("12 °C"), chips.all().filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.map { it.text.toString() })
        templates.push("'red'", "red")
        val icon = chips.all().filterIsInstance<dev.glassworks.engine.vitro.ui.MdiIconView>().single()
        assertEquals(0xFFF44336.toInt(), icon.tintColor)
    }

    @Test
    fun conditionalChipsFollowStateAndChipVisibilityIsIgnored() {
        val store = EntityStore()
        store.putLocal(EntityState("input_boolean.guest", "off", emptyMap()))
        store.putLocal(EntityState("binary_sensor.door", "off", emptyMap()))
        store.putLocal(EntityState("light.hall", "on", mapOf("friendly_name" to "Hall")))
        val chips = MushroomChipsCardView(
            context(store),
            card(
                """{"type": "custom:mushroom-chips-card", "chips": [
                     {"type": "conditional", "conditions": [{"entity": "input_boolean.guest", "state": "on"}],
                      "chip": {"type": "action", "icon": "mdi:account"}},
                     {"type": "entity", "entity": "light.hall",
                      "visibility": [{"condition": "state", "entity": "binary_sensor.door", "state_not": "off"}]}
                   ]}""",
            ),
        )
        val (guestChip, hallChip) = (0 until chips.childCount).map { chips.getChildAt(it) }
        assertEquals(View.GONE, guestChip.visibility)
        // Mushroom ignores a chip's own `visibility`: only `conditional` chips come and go.
        assertEquals(View.VISIBLE, hallChip.visibility)

        store.putLocal(EntityState("input_boolean.guest", "on", emptyMap()))
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, guestChip.visibility)

        store.putLocal(EntityState("binary_sensor.door", "on", emptyMap()))
        store.putLocal(EntityState("input_boolean.guest", "off", emptyMap()))
        ShadowLooper.idleMainLooper()
        assertEquals(View.GONE, guestChip.visibility)
        assertEquals(View.VISIBLE, hallChip.visibility)
    }

    @Test
    fun chipVariablesFromCardModSizeColorAndJustifyTheChips() {
        val chips = MushroomChipsCardView(
            context(EntityStore()),
            card(
                """{"type": "custom:mushroom-chips-card", "alignment": "justify", "chips": [
                     {"type": "template", "icon": "mdi:home", "content": "Good"},
                     {"type": "template", "icon": "mdi:home", "content": "Chat"}],
                   "card_mod": {"style": "ha-card { padding: 4px 0 0 8px; --chip-height: 32px; --chip-border-radius: 99px; --chip-background: rgba(52,211,153,0.16); --primary-text-color: #34d399; }"}}""",
            ),
        )
        val dp = activity.resources.displayMetrics.density
        chips.measure(View.MeasureSpec.makeMeasureSpec((400 * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        chips.layout(0, 0, chips.measuredWidth, chips.measuredHeight)
        assertEquals((8 * dp).toInt(), chips.paddingLeft)
        assertEquals((4 * dp).toInt(), chips.paddingTop)
        val first = chips.getChildAt(0) as LinearLayout
        val border = (1 * dp).toInt().coerceAtLeast(1)
        // content-box: the 1 px border sits outside --chip-height.
        assertEquals((32 * dp).toInt() + 2 * border, first.minimumHeight)
        assertEquals(0x2934D399, (first.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
        val label = first.all().filterIsInstance<TextView>().single()
        assertEquals(9.6f * dp, label.textSize, 0.5f)
        assertEquals(0xFF34D399.toInt(), label.currentTextColor)
        // justify-content: space-between puts the last chip against the end.
        val second = chips.getChildAt(1)
        assertEquals(chips.width - chips.paddingRight, second.right)
        assertEquals(chips.paddingLeft, first.left)
    }

    @Test
    fun chipVariablesResolveThroughTheCardAndItsParents() {
        val ctx = context(EntityStore())
        ctx.cardVars = mapOf("pill" to "#ff0000")
        val chips = MushroomChipsCardView(
            ctx,
            card(
                """{"type": "custom:mushroom-chips-card", "chips": [{"type": "template", "content": "A"}],
                   "card_mod": {"style": "ha-card { --chip-background: var(--pill); --tint: #00ff00; --primary-text-color: var(--tint); }"}}""",
            ),
        )
        val chip = chips.getChildAt(0) as LinearLayout
        assertEquals(0xFFFF0000.toInt(), (chip.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
        assertEquals(0xFF00FF00.toInt(), chip.all().filterIsInstance<TextView>().single().currentTextColor)
    }

    @Test
    fun inlineFeaturesShowTheFirstConfiguredOne() {
        val store = EntityStore()
        store.putLocal(EntityState("light.desk", "on", mapOf("brightness" to 128)))
        val t = TileCardView(context(store), card(
            """{"type": "tile", "entity": "light.desk", "features_position": "inline",
               "features": [{"type": "toggle"}, {"type": "light-brightness"}]}""",
        ))
        // The toggle comes first in the config, so it's the one beside the info (HA draws no others inline).
        assertNotNull(t.all().firstOrNull { it.contentDescription == "On" })
        assertTrue(t.all().none { it is dev.glassworks.engine.vitro.cards.HaHorizontalSliderView })
    }

    // ---- Tile features ----

    private fun tile(store: EntityStore, calls: MutableList<Call>, entity: String, feature: String) =
        TileCardView(context(store, calls), card("""{"type": "tile", "entity": "$entity", "features": [$feature]}"""))

    @Test
    fun toggleFeatureTurnsOnAndOffAndLocks() {
        val store = EntityStore()
        store.putLocal(EntityState("light.desk", "off", emptyMap()))
        store.putLocal(EntityState("lock.front", "locked", emptyMap()))
        val calls = ArrayList<Call>()
        val light = tile(store, calls, "light.desk", """{"type": "toggle"}""")
        assertTrue(light.byDescription("Off").isSelected)
        light.byDescription("On").performClick()
        assertEquals("light.turn_on light.desk", calls.last().toString())
        assertTrue(light.byDescription("On").isSelected)
        light.byDescription("Off").performClick()
        assertEquals("light.turn_off light.desk", calls.last().toString())

        val lock = tile(store, calls, "lock.front", """{"type": "toggle"}""")
        lock.byDescription("Unlock").performClick()
        assertEquals("lock.unlock lock.front", calls.last().toString())
        lock.byDescription("Lock").performClick()
        assertEquals("lock.lock lock.front", calls.last().toString())
    }

    @Test
    fun mediaPlaybackFeatureDrivesThePlayer() {
        val store = EntityStore()
        store.putLocal(EntityState("media_player.den", "playing", emptyMap()))
        val calls = ArrayList<Call>()
        val t = tile(store, calls, "media_player.den", """{"type": "media-player-playback"}""")
        t.byDescription("Previous").performClick()
        t.byDescription("Pause").performClick() // playing shows pause
        t.byDescription("Next").performClick()
        t.byDescription("Stop").performClick()
        assertEquals(
            listOf("media_previous_track", "media_play_pause", "media_next_track", "media_stop"),
            calls.map { it.service },
        )
        assertTrue(calls.all { it.domain == "media_player" && it.data?.optString("entity_id") == "media_player.den" })
    }

    @Test
    fun colorFavoritesSendTheirColors() {
        val store = EntityStore()
        store.putLocal(EntityState("light.desk", "on", emptyMap()))
        val calls = ArrayList<Call>()
        val t = tile(
            store, calls, "light.desk",
            """{"type": "light-color-favorites", "favorites": [{"rgb_color": [255, 0, 0]}, {"color_temp_kelvin": 3000}]}""",
        )
        t.byDescription("Favorite color 1").performClick()
        assertEquals("light.turn_on light.desk", calls.last().toString())
        assertEquals("[255,0,0]", calls.last().data!!.getJSONArray("rgb_color").toString())
        t.byDescription("Favorite color 2").performClick()
        assertEquals(3000, calls.last().data!!.getInt("color_temp_kelvin"))

        // No favorites configured: HA's six defaults.
        val defaults = tile(store, calls, "light.desk", """{"type": "light-color-favorites"}""")
        defaults.byDescription("Favorite color 6").performClick()
        assertTrue(calls.last().data!!.has("rgb_color"))
    }

    @Test
    fun numericInputStepsWithinLimits() {
        val store = EntityStore()
        store.putLocal(EntityState("input_number.volume", "9", mapOf("min" to 0, "max" to 10, "step" to 1, "unit_of_measurement" to "dB")))
        store.putLocal(EntityState("counter.cups", "3", mapOf("step" to 1)))
        val calls = ArrayList<Call>()
        val t = tile(store, calls, "input_number.volume", """{"type": "numeric-input"}""")
        assertTrue(t.all().filterIsInstance<TextView>().any { it.text.toString() == "9 dB" })
        t.byDescription("Increase").performClick()
        assertEquals("input_number.set_value input_number.volume", calls.last().toString())
        assertEquals(10L, calls.last().data!!.getLong("value"))
        t.byDescription("Increase").performClick() // already at max
        assertEquals(10L, calls.last().data!!.getLong("value"))
        t.byDescription("Decrease").performClick()
        assertEquals(9L, calls.last().data!!.getLong("value"))

        val counter = tile(store, calls, "counter.cups", """{"type": "numeric-input"}""")
        counter.byDescription("Increase").performClick()
        assertEquals("counter.increment counter.cups", calls.last().toString())
        counter.byDescription("Decrease").performClick()
        assertEquals("counter.decrement counter.cups", calls.last().toString())
    }

    @Test
    fun vacuumCommandsCallTheirServices() {
        val store = EntityStore()
        store.putLocal(EntityState("vacuum.robo", "docked", emptyMap()))
        val calls = ArrayList<Call>()
        val t = tile(store, calls, "vacuum.robo", """{"type": "vacuum-commands"}""")
        for (label in listOf("Start", "Stop", "Clean spot", "Locate", "Return home")) t.byDescription(label).performClick()
        assertEquals(listOf("start", "stop", "clean_spot", "locate", "return_to_base"), calls.map { it.service })
        assertTrue(calls.all { it.domain == "vacuum" })

        store.putLocal(EntityState("vacuum.robo", "cleaning", emptyMap()))
        ShadowLooper.idleMainLooper()
        t.byDescription("Pause").performClick()
        assertEquals("vacuum.pause vacuum.robo", calls.last().toString())

        val limited = tile(store, calls, "vacuum.robo", """{"type": "vacuum-commands", "commands": ["return_home"]}""")
        val described = ArrayList<View>()
        limited.findViewsWithText(described, "Stop", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        assertTrue(described.isEmpty())
        limited.byDescription("Return home").performClick()
        assertEquals("vacuum.return_to_base vacuum.robo", calls.last().toString())
    }
}
