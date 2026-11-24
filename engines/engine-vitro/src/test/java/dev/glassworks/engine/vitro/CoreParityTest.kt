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
import dev.glassworks.engine.vitro.cards.MarkdownCardView
import dev.glassworks.engine.vitro.cards.MarkdownInline
import dev.glassworks.engine.vitro.cards.MarkdownRenderer
import dev.glassworks.engine.vitro.cards.MdBlock
import dev.glassworks.engine.vitro.cards.MdiIconSpan
import dev.glassworks.engine.vitro.cards.MushroomChipsCardView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.cards.parseMarkdownBlocks
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

    @Test
    fun markdownSplitsIntoHeadingsRulesTablesListsQuotesAndCenter() {
        val blocks = parseMarkdownBlocks(
            """
            # Home
            ## Lights
            #### Small
            Plain **text**
            second line
            ---
            <hr/>
            | Room | Temp |
            |:-----|-----:|
            | Lounge | 21 °C |
            | Kitchen | 19 °C |

            - one
            * two
              - nested
            1. first
            2. second
            > quoted *words*
            > still quoted
            <center>Centered **bold**</center>
            """.trimIndent(),
        )
        assertEquals(MdBlock.Heading(1, "Home"), blocks[0])
        assertEquals(MdBlock.Heading(2, "Lights"), blocks[1])
        assertEquals(MdBlock.Heading(4, "Small"), blocks[2])
        assertEquals(MdBlock.Paragraph("Plain **text**\nsecond line"), blocks[3])
        assertEquals(MdBlock.Rule, blocks[4])
        assertEquals(MdBlock.Rule, blocks[5])
        val table = blocks[6] as MdBlock.Table
        assertEquals(listOf("Room", "Temp"), table.header)
        assertEquals(listOf(listOf("Lounge", "21 °C"), listOf("Kitchen", "19 °C")), table.rows)
        assertEquals(listOf("start", "end"), table.aligns)
        assertEquals(MdBlock.ListItem(false, "•", "one", 0), blocks[7])
        assertEquals(MdBlock.ListItem(false, "•", "two", 0), blocks[8])
        assertEquals(MdBlock.ListItem(false, "•", "nested", 1), blocks[9])
        assertEquals(MdBlock.ListItem(true, "1.", "first", 0), blocks[10])
        assertEquals(MdBlock.ListItem(true, "2.", "second", 0), blocks[11])
        assertEquals(MdBlock.Quote("quoted *words*\nstill quoted"), blocks[12])
        assertEquals(MdBlock.Center(listOf(MdBlock.Paragraph("Centered **bold**"))), blocks[13])
        assertEquals(14, blocks.size)
    }

    // ---- Markdown inline ----

    private val inline = MarkdownInline(linkColor = 0xFF03A9F4.toInt(), codeBackground = 0x14FFFFFF, iconSizePx = 16)

    private inline fun <reified T : Any> Spanned.spansOf(): List<T> =
        getSpans(0, length, T::class.java).sortedBy { getSpanStart(it) }

    private fun Spanned.covered(span: Any): String = subSequence(getSpanStart(span), getSpanEnd(span)).toString()

    @Test
    fun inlineMarkdownBecomesSpans() {
        val text = inline.render(
            "**Bold** and *it* and __b2__ and _i2_ ~~gone~~ `code` [HA](https://example.org) <b>hb</b> <em>he</em> <s>hs</s> <code>hc</code>",
            0xFFFFFFFF.toInt(),
        )
        assertEquals("Bold and it and b2 and i2 gone code HA hb he hs hc", text.toString())
        val bold = text.spansOf<StyleSpan>().filter { it.style == Typeface.BOLD }.map { text.covered(it) }
        val italic = text.spansOf<StyleSpan>().filter { it.style == Typeface.ITALIC }.map { text.covered(it) }
        assertEquals(listOf("Bold", "b2", "hb"), bold)
        assertEquals(listOf("it", "i2", "he"), italic)
        assertEquals(listOf("gone", "hs"), text.spansOf<StrikethroughSpan>().map { text.covered(it) })
        assertEquals(listOf("code", "hc"), text.spansOf<TypefaceSpan>().filter { it.family == "monospace" }.map { text.covered(it) })
        val link = text.spansOf<ForegroundColorSpan>().single { it.foregroundColor == 0xFF03A9F4.toInt() }
        assertEquals("HA", text.covered(link))
        assertEquals(listOf("HA"), text.spansOf<UnderlineSpan>().map { text.covered(it) })
    }

    @Test
    fun snakeCaseAndArithmeticStayLiteral() {
        assertEquals("light_kitchen_main is 2 * 3 * 4", inline.render("light_kitchen_main is 2 * 3 * 4", 0).toString())
        assertTrue(inline.render("light_kitchen_main", 0).spansOf<StyleSpan>().isEmpty())
    }

    @Test
    fun fontAndSpanColorsBreaksAndEntities() {
        val text = inline.render(
            """<font color="red">Alarm</font><br>On <span style="color: #00ff00">green</span><br/>5&deg;C &amp; up""",
            0xFFFFFFFF.toInt(),
        )
        assertEquals("Alarm\nOn green\n5°C & up", text.toString())
        val colors = text.spansOf<ForegroundColorSpan>().associate { text.covered(it) to it.foregroundColor }
        assertEquals(0xFFF44336.toInt(), colors["Alarm"]) // HA's red
        assertEquals(0xFF00FF00.toInt(), colors["green"])
    }

    @Test
    fun haIconBecomesATintedImageSpan() {
        val text = inline.render(
            """<ha-icon icon="mdi:lightbulb"></ha-icon> Lamp <font color="#ff9800"><ha-icon icon="mdi:fire"/></font>""",
            0xFFEEEEEE.toInt(),
        )
        val icons = text.spansOf<MdiIconSpan>().sortedBy { text.getSpanStart(it) }
        assertEquals(listOf("mdi:lightbulb", "mdi:fire"), icons.map { it.iconName })
        assertEquals(0xFFEEEEEE.toInt(), icons[0].color) // the text color
        assertEquals(0xFFFF9800.toInt(), icons[1].color) // the enclosing font color
        assertEquals("￼ Lamp ￼", text.toString())
    }

    // ---- Markdown card ----

    @Test
    fun markdownCardRendersNativeViewsAndRebuildsOnEveryTemplateUpdate() {
        val templates = Templates()
        val view = MarkdownCardView(
            context(EntityStore(), templates = templates),
            card("""{"type": "markdown", "content": "# {{ states('sensor.t') }}\n| A | B |\n|---|---|\n| 1 | 2 |"}"""),
        )
        templates.push("sensor.t", "# Climate\nIt is **warm**\n\n| Room | Temp |\n|---|---|\n| Lounge | 21 |\n\n---\n- fan on")
        val body = view.body
        val heading = body.getChildAt(0) as TextView
        val para = body.getChildAt(1) as TextView
        assertEquals("Climate", heading.text.toString())
        assertTrue(heading.textSize > para.textSize)
        assertEquals("It is warm", para.text.toString())
        assertTrue((para.text as Spanned).spansOf<StyleSpan>().any { it.style == Typeface.BOLD })
        val table = body.getChildAt(2) as TableLayout
        val cells = table.all().filterIsInstance<TextView>().map { it.text.toString() }
        assertEquals(listOf("Room", "Temp", "Lounge", "21"), cells)
        assertEquals(MarkdownRenderer.RULE_TAG, body.getChildAt(3).tag)
        val item = body.getChildAt(4) as LinearLayout
        assertEquals(listOf("•", "fan on"), item.all().filterIsInstance<TextView>().map { it.text.toString() })

        // A later render replaces everything; nothing from the first one is left behind.
        templates.push("sensor.t", "Just text")
        assertEquals(1, body.childCount)
        assertEquals("Just text", (body.getChildAt(0) as TextView).text.toString())
        assertFalse(body.all().filterIsInstance<TextView>().any { "{{" in it.text })
    }

    @Test
    fun staticMarkdownQuotesAndCenterRender() {
        val view = MarkdownCardView(
            context(EntityStore()),
            card("""{"type": "markdown", "content": "> careful\n\n<center>**Hi**</center>"}"""),
        )
        val quote = view.body.getChildAt(0) as LinearLayout
        assertEquals(MarkdownRenderer.QUOTE_BAR_TAG, quote.getChildAt(0).tag)
        val center = view.body.getChildAt(1) as LinearLayout
        val hi = center.getChildAt(0) as TextView
        assertEquals("Hi", hi.text.toString())
        assertEquals(android.view.Gravity.CENTER_HORIZONTAL, hi.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
    }

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
    fun conditionalAndVisibilityChipsFollowState() {
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
        assertEquals(View.GONE, hallChip.visibility)

        store.putLocal(EntityState("input_boolean.guest", "on", emptyMap()))
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, guestChip.visibility)
        assertEquals(View.GONE, hallChip.visibility)

        store.putLocal(EntityState("binary_sensor.door", "on", emptyMap()))
        store.putLocal(EntityState("input_boolean.guest", "off", emptyMap()))
        ShadowLooper.idleMainLooper()
        assertEquals(View.GONE, guestChip.visibility)
        assertEquals(View.VISIBLE, hallChip.visibility)
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
