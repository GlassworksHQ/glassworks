/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.cards.ButtonCardCss
import dev.glassworks.engine.vitro.cards.ButtonCardJs
import dev.glassworks.engine.vitro.cards.ButtonCardSheet
import dev.glassworks.engine.vitro.cards.ButtonCardStates
import dev.glassworks.engine.vitro.cards.ButtonCardTemplates
import dev.glassworks.engine.vitro.cards.CssGridSpec
import dev.glassworks.engine.vitro.compat.CardRoute
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.LovelaceParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * custom:button-card's config without a view: dashboard templates, `[[[ ]]]` JavaScript, `state:` matching with
 * JavaScript's comparisons, its grid stylesheet, and which configs Vitro draws natively. Pure JVM.
 */
class ButtonCardModelTest {
    private fun card(json: String) = LovelaceParser.parseCard(JSONObject(json))
    private fun state(s: String, attrs: Map<String, Any?> = emptyMap()) = EntityState("sensor.x", s, attrs)

    @Test
    fun templatesMergeLikeButtonCard() {
        val dashboard = JSONObject("""{
            "button_card_templates": {
              "base": {"color_type": "card", "styles": {"card": [{"height": "80px"}]}, "state": [{"id": "on", "value": "on", "color": "red"}]},
              "big": {"template": "base", "size": "60%", "styles": {"card": [{"padding": "4px"}]}, "extra_styles": "a { }"}
            },
            "views": [{"cards": [
              {"type": "custom:button-card", "template": "big", "entity": "light.a", "state": [{"id": "on", "icon": "mdi:sun"}, {"value": "off", "color": "blue"}], "extra_styles": "b { }"},
              {"type": "vertical-stack", "cards": [{"type": "custom:button-card", "template": ["base", "missing"]}]}
            ]}]
        }""")
        val parsed = LovelaceParser.parseDashboard(dashboard)
        val c = parsed.views[0].cards[0].raw
        assertFalse(c.has("template"))
        assertEquals("card", c.getString("color_type"))
        assertEquals("60%", c.getString("size"))
        // Lists concatenate, in template order.
        assertEquals("""[{"height":"80px"},{"padding":"4px"}]""", c.getJSONObject("styles").getJSONArray("card").toString())
        // `state` entries with the same id merge; the others follow.
        val states = c.getJSONArray("state")
        assertEquals(2, states.length())
        assertEquals("red", states.getJSONObject(0).getString("color"))
        assertEquals("mdi:sun", states.getJSONObject(0).getString("icon"))
        assertEquals("blue", states.getJSONObject(1).getString("color"))
        // Each level's extra_styles, in merge order, as one sheet.
        assertEquals("a { }\nb { }", c.getString("extra_styles"))
        // A missing template leaves the config as it was: the island shows button-card's own error.
        val missing = parsed.views[0].cards[0 + 1].childCards[0]
        assertTrue(missing.raw.has("template"))
        assertEquals(CardRoute.ISLAND, missing.compat.route)
        assertTrue(missing.compat.reasons.single(), "button_card_templates" in missing.compat.reasons.single())
        // The reason names the option, never the dashboard's template name.
        assertFalse(missing.compat.reasons.single(), "missing" in missing.compat.reasons.single())
        // The dashboard object itself is left alone.
        assertTrue(dashboard.getJSONArray("views").getJSONObject(0).getJSONArray("cards").getJSONObject(0).has("template"))
    }

    @Test
    fun mergeDeepFlattensOneListLevelAsConcatDoes() {
        val out = ButtonCardTemplates.mergeDeep(JSONObject("""{"a": [1], "o": {"x": 1}}"""), JSONObject("""{"a": [[2, 3], 4], "o": {"y": 2}}"""))
        assertEquals("[1,2,3,4]", out.getJSONArray("a").toString())
        assertEquals(1, out.getJSONObject("o").getInt("x"))
        assertEquals(2, out.getJSONObject("o").getInt("y"))
    }

    @Test
    fun javascriptTemplatesAndTheirEscapes() {
        assertTrue(ButtonCardJs.isJs("[[[ return entity.state ]]]"))
        assertTrue(ButtonCardJs.isJs("  [[[\n  return 1;\n]]]  "))
        assertFalse(ButtonCardJs.isJs("[[[[ not js ]]]]"))
        assertFalse(ButtonCardJs.isJs("[[ two ]]"))
        assertEquals("[[[x]]]", ButtonCardJs.literal("[[[[x]]]]"))
        assertEquals("[[[[x]]]", ButtonCardJs.literal("[[[[x]]]"))
        val paths = ButtonCardJs.paths(JSONObject("""{"name": "[[[ return 1 ]]]", "styles": {"card": [{"color": "[[[ return 'red' ]]]"}]}, "variables": {"v": "[[[ return 2 ]]]"}}"""))
        assertEquals(listOf("name", "styles.card[0].color"), paths)
    }

    @Test
    fun stateEntriesMatchWithJavascriptComparisons() {
        val states = JSONArray("""[
            {"value": 5, "operator": "<", "name": "cold"},
            {"value": "15", "operator": "<", "name": "text"},
            {"value": "^1", "operator": "regex", "name": "re"},
            {"operator": "default", "name": "def"}
        ]""")
        assertEquals("cold", ButtonCardStates.match(states, state("4.5"))?.getString("name"))
        // "12" < "15" as text (both strings), after `5 < 12` failed as numbers.
        assertEquals("text", ButtonCardStates.match(states, state("12"))?.getString("name"))
        // "20" < "15" is false as text: JavaScript compares two strings by character.
        assertEquals("def", ButtonCardStates.match(states, state("20"))?.getString("name"))
        assertEquals("text", ButtonCardStates.match(states, state("100"))?.getString("name"))
        assertEquals("re", ButtonCardStates.match(states, state("18"))?.getString("name"))
        assertEquals("cold", ButtonCardStates.match(JSONArray("""[{"value": 7, "name": "cold"}]"""), state("7.0"))?.getString("name"))
        // `value: true` never equals "on" (Number("on") is NaN), as in the browser.
        assertNull(ButtonCardStates.match(JSONArray("""[{"value": true}]"""), state("on")))
        assertNull(ButtonCardStates.match(states, null))
    }

    @Test
    fun stylesDropWhatTheBrowserDrops() {
        val decls = ButtonCardCss.declarations(JSONArray("""[{"padding": "0;"}, {"border": "1px solid red; /*x*/"}, {"color": "red !important"}, {"font-size": 14}, {"color": null}]"""))!!
        // `padding: 0;` and the border with a `;` are invalid values; the null removes the color; 14 is "14" (invalid
        // for font-size, but a declaration all the same).
        assertEquals(listOf("font-size"), decls.map { it.name })
        assertNull(ButtonCardCss.declarations(JSONObject("""{"color": "red"}""")))
        // A comment goes, but a quoted `/*` is text, not a comment (the font names stay whole); a comment after
        // `!important` doesn't hide it.
        val quoted = ButtonCardCss.declarations(JSONArray("""[{"font-family": "'a/*b', 'c*/d'"}, {"color": "red /* note */"}, {"width": "10px !important /* x */"}]"""))!!
        assertEquals(listOf("'a/*b', 'c*/d'", "red", "10px"), quoted.map { it.value })
        assertEquals(listOf(false, false, true), quoted.map { it.important })
        assertEquals(0xFFFF0000.toInt(), ButtonCardCss.color("red"))
        assertEquals(0xFF808080.toInt(), ButtonCardCss.color("grey"))
    }

    @Test
    fun theGridSheetCascadesByCssSpecificity() {
        val vertical = ButtonCardSheet.resolve(setOf("vertical", "no-state", "no-label"))
        assertEquals(listOf(listOf("i"), listOf("n"), listOf("s"), listOf("l")), vertical.areas)
        val noIcon = ButtonCardSheet.resolve(setOf("vertical", "no-icon", "no-label"))
        assertEquals(listOf(listOf("n"), listOf("s")), noIcon.areas)
        assertEquals("end", noIcon.items["name"]?.get("align-self"))
        assertEquals("start", noIcon.items["state"]?.get("align-self"))
        // The later, equally specific rule wins: icon_name_state2nd's state is `start`, not `center`.
        assertEquals("start", ButtonCardSheet.resolve(setOf("icon_name_state2nd")).items["state"]?.get("align-self"))
        assertEquals(listOf(40f, 0f), ButtonCardSheet.resolve(setOf("icon_label")).columns.map { it.fixed?.value ?: 0f })
        assertEquals("8px", ButtonCardSheet.resolve(setOf("vertical", "section", "no-state", "no-label")).items["name"]?.get("margin-top"))
        assertNull(CssGridSpec.areas("\"a b\" \"c\""))
        assertNull(CssGridSpec.areas("\"a b a\""))
        assertEquals(3, CssGridSpec.tracks("repeat(2, 1fr) 40px")?.size)
        // More tracks than the native grid takes, or a count past Int, is a grid Vitro doesn't draw.
        assertNull(CssGridSpec.tracks("repeat(25, 1fr)"))
        assertNull(CssGridSpec.tracks("repeat(99999999999, 1fr)"))
    }

    @Test
    fun buttonCardsWithoutJavascriptAreNative() {
        val native = card("""{"type": "custom:button-card", "entity": "light.a", "show_state": true, "layout": "icon_name",
            "color_type": "card", "size": "30px", "aspect_ratio": "1/1",
            "styles": {"card": [{"height": "80px"}, {"border-radius": "10px"}, {"border": "solid 1px rgba(57,128,228,0.4)"}, {"cursor": "pointer"}],
                       "name": [{"font-size": "14px"}, {"justify-self": "start"}, {"padding-left": "10px"}],
                       "icon": [{"color": "var(--accent-color)"}], "img_cell": [{"border-radius": "50%"}, {"width": "42px"}],
                       "custom_fields": {"t": [{"font-size": "12px"}]}},
            "custom_fields": {"t": "22°"},
            "state": [{"value": "on", "color": "rgb(255, 0, 0)", "styles": {"name": [{"color": "white"}]}}],
            "tap_action": {"action": "call-service", "service": "light.toggle", "data": {"entity_id": "entity"}},
            "hold_action": {"action": "more-info"}, "lock": {"enabled": true, "unlock": "hold"}, "tooltip": "hi"}""")
        assertEquals(card("{}").type, "entities")
        val v = native.compat
        assertEquals(v.reasons.toString(), CardRoute.NATIVE, v.route)
        assertTrue(v.notes.any { "tooltip" in it })
    }

    @Test
    fun javascriptCardsHtmlAndUndrawnStylesGoToAnIsland() {
        fun reasons(json: String) = card(json).compat.also { assertEquals(CardRoute.ISLAND, it.route) }.reasons.joinToString()
        assertTrue("JavaScript" in reasons("""{"type": "custom:button-card", "name": "[[[ return 'x' ]]]"}"""))
        assertTrue("holds a card" in reasons("""{"type": "custom:button-card", "custom_fields": {"g": {"card": {"type": "tile"}}}}"""))
        assertTrue("HTML" in reasons("""{"type": "custom:button-card", "name": "Line<br>two"}"""))
        assertTrue("filter" in reasons("""{"type": "custom:button-card", "styles": {"card": [{"filter": "blur(2px)"}]}}"""))
        assertTrue("`border` to a value" in reasons("""{"type": "custom:button-card", "styles": {"card": [{"border": "1px dashed red"}]}}"""))
        assertTrue("spinner" in reasons("""{"type": "custom:button-card", "spinner": true}"""))
        assertTrue("layout" in reasons("""{"type": "custom:button-card", "layout": "sideways"}"""))
        // The option, not its value: the action type is the dashboard's text.
        val action = reasons("""{"type": "custom:button-card", "tap_action": {"action": "fire-dom-event"}}""")
        assertTrue(action, "`tap_action` has an action type" in action && "fire-dom-event" !in action)
        assertTrue("force_eval" in reasons("""{"type": "custom:button-card", "variables": {"v": {"value": 1, "force_eval": true}}}"""))
        // A value the browser rejects is dropped natively too, and `variables` without JavaScript reading them is ignored.
        assertEquals(CardRoute.NATIVE, card("""{"type": "custom:button-card", "variables": {"v": "[[[ return 1 ]]]"}, "styles": {"card": [{"padding": "0;"}]}}""").compat.route)
    }

    @Test
    fun aTimerCountdownGoesToAnIsland() {
        // button-card counts a timer down every second (its time remaining, "(Paused)" while paused), which the native
        // card doesn't draw: with `show_state` on a timer the island draws it.
        val timer = card("""{"type": "custom:button-card", "entity": "timer.laundry", "show_state": true}""").compat
        assertEquals(CardRoute.ISLAND, timer.route)
        assertTrue(timer.reasons.single(), "timer" in timer.reasons.single())
        // `show_state` as button-card reads it (JavaScript truthiness): the string "0" is on, as the site checker has it.
        assertEquals(CardRoute.ISLAND, card("""{"type": "custom:button-card", "entity": "timer.laundry", "show_state": "0"}""").compat.route)
        assertEquals(CardRoute.NATIVE, card("""{"type": "custom:button-card", "entity": "timer.laundry", "show_state": 0}""").compat.route)
        // No state shown, a state_display of its own, or another domain: native as before.
        for (json in listOf(
            """{"type": "custom:button-card", "entity": "timer.laundry"}""",
            """{"type": "custom:button-card", "entity": "timer.laundry", "show_state": true, "state_display": "Running"}""",
            """{"type": "custom:button-card", "entity": "light.timer", "show_state": true}""",
        )) assertEquals(json, CardRoute.NATIVE, card(json).compat.route)
    }

    @Test
    fun diagnosticsNameOptionsNeverValues() {
        // A signed address hidden by a CSS escape (`u\72l(` is `url(`) and a bare address in `layout` both go to an
        // island, and neither the address nor its signature reaches a reason (logcat, the HUD).
        val escaped = card("""{"type": "custom:button-card", "styles": {"card": [{"background-image": "u\\72l(/img?sig=SECRET1)"}]}}""").compat
        assertEquals(CardRoute.ISLAND, escaped.route)
        val bare = card("""{"type": "custom:button-card", "layout": "https://example.invalid/x?sig=SECRET2"}""").compat
        assertEquals(CardRoute.ISLAND, bare.route)
        val js = card("""{"type": "custom:button-card", "custom_fields": {"SECRET3": "[[[ return 'x' ]]]"}, "x_SECRET4": "[[[ return 1 ]]]"}""").compat
        val swipe = card("""{"type": "custom:swipe-card", "parameters": {"pagination": {"type": "SECRET5"}, "SECRET6": 1}, "cards": [{"type": "custom:button-card"}]}""").compat
        for (v in listOf(escaped, bare, js, swipe)) {
            assertTrue(v.reasons.isNotEmpty())
            val text = (v.reasons + v.notes).joinToString()
            assertFalse(text, Regex("SECRET|sig=|https?:|72l").containsMatchIn(text))
        }
        assertTrue(escaped.reasons.single(), "`background-image`" in escaped.reasons.single())
        assertTrue(bare.reasons.single(), "`layout:`" in bare.reasons.single())
    }

    @Test
    fun theOtherFastPathsRoute() {
        assertEquals(CardRoute.NATIVE, card("""{"type": "entity-button", "entity": "light.a"}""").compat.route)
        assertEquals(CardRoute.NATIVE, card("""{"type": "custom:mushroom-empty-card"}""").compat.route)
        assertEquals(CardRoute.NATIVE, card("""{"type": "custom:weather-card", "entity": "weather.home"}""").compat.route)
        assertEquals(CardRoute.ISLAND, card("""{"type": "custom:weather-card"}""").compat.route)
        val swipe = card("""{"type": "custom:swipe-card", "parameters": {"pagination": {"type": "bullets"}, "slidesPerView": 2, "loop": true},
            "cards": [{"type": "custom:button-card"}, {"type": "custom:button-card"}]}""")
        assertEquals(swipe.compat.reasons.toString(), CardRoute.NATIVE, swipe.compat.route)
        assertEquals(2, swipe.childCards.size)
        assertTrue("custom:swipe-card" in CardRouter.CONTAINER_TYPES)
        assertEquals(CardRoute.ISLAND, card("""{"type": "custom:swipe-card", "parameters": {"effect": "cube"}, "cards": [{"type": "custom:button-card"}]}""").compat.route)
        // A built-in card inside keeps it native: the island would draw that card as a summary.
        assertEquals(CardRoute.NATIVE, card("""{"type": "custom:swipe-card", "parameters": {"effect": "cube"}, "cards": [{"type": "tile"}]}""").compat.route)
    }
}

/**
 * Local only: the engine's verdict for each card `tools/vitro-census/census.py --dump-cards` wrote (the island census,
 * docs/research/vitro-island-census.md). Reads `VITRO_CENSUS_CARDS` (JSON lines: id, the dashboard's
 * `button_card_templates`, the card) and writes `VITRO_CENSUS_OUT` (id, type, route, reasons). Skipped when unset.
 */
class CensusLocalTest {
    @Test
    fun verdictsForTheCensusCards() {
        val input = System.getenv("VITRO_CENSUS_CARDS")
        org.junit.Assume.assumeTrue("VITRO_CENSUS_CARDS not set", !input.isNullOrBlank())
        val out = java.io.File(System.getenv("VITRO_CENSUS_OUT") ?: error("VITRO_CENSUS_OUT not set"))
        out.bufferedWriter().use { w ->
            java.io.File(input!!).forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val o = JSONObject(line)
                val dash = JSONObject().put("button_card_templates", o.optJSONObject("templates") ?: JSONObject())
                    .put("views", JSONArray().put(JSONObject().put("cards", JSONArray().put(o.getJSONObject("card")))))
                val card = LovelaceParser.parseDashboard(dash).views[0].cards[0]
                val v = card.compat
                w.write(JSONObject().put("id", o.getString("id")).put("type", card.type)
                    .put("route", if (card.inIsland()) "ISLAND" else "NATIVE").put("reasons", JSONArray(v.reasons)).toString())
                w.newLine()
            }
        }
    }
}
