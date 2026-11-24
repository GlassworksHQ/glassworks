/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.cards.CssSelector
import dev.glassworks.engine.vitro.compat.CardRoute
import dev.glassworks.engine.vitro.compat.CardVerdict
import dev.glassworks.engine.vitro.compat.CompatChecker
import dev.glassworks.engine.vitro.compat.VitroSupport
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.CardTier
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeResolver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The deliberate fallback (#795): what renders native, what goes to an island, and why. The "native" fixtures are
 * the idioms of the Mirror, tablet and S1 dashboards (copied without names, places or entity ids that identify
 * anyone), which must stay fully native. Pure JVM.
 */
class CompatCheckerTest {

    private fun card(json: String) = LovelaceParser.parseCard(JSONObject(json))

    private fun verdict(json: String): CardVerdict = card(json).compat

    private fun assertNative(json: String): CardVerdict {
        val v = verdict(json)
        assertEquals("expected native, got ${v.reasons}", CardRoute.NATIVE, v.route)
        return v
    }

    /**
     * Something outside the supported set. A custom card (Mushroom, Bubble, …) goes to an island, which runs its own
     * code. A built-in card Vitro draws stays native, because the island can't draw built-in cards yet, and the
     * checker notes what it would have fallen back for.
     */
    private fun assertIsland(json: String, reasonPart: String): CardVerdict {
        val v = verdict(json)
        if (v.type in VitroSupport.CORE_CARD_TYPES) {
            assertEquals("a built-in card stays native", CardRoute.NATIVE, v.route)
            assertTrue("no note mentions '$reasonPart': ${v.notes}", v.notes.any { reasonPart in it && "built-in" in it })
        } else {
            assertEquals("expected an island", CardRoute.ISLAND, v.route)
            assertTrue("no reason mentions '$reasonPart': ${v.reasons}", v.reasons.any { reasonPart in it })
        }
        return v
    }

    private fun style(css: String) = JSONObject.quote(css)

    // ---- Real dashboard idioms: must stay native ----

    @Test
    fun mirrorTextOnlyMarkdownWithTypographySheetIsNative() {
        val v = assertNative(
            """{"type": "markdown", "text_only": true,
                "content": "{% set h = now().hour %}{{ 'Good morning' if h < 12 else 'Good evening' }}",
                "card_mod": {"style": {
                  ".": ${style("ha-card { background: none; border: none; box-shadow: none; transition: opacity 0.8s; }")},
                  "ha-markdown$": ${style("ha-markdown-element { font-size: 16px; font-weight: 600; letter-spacing: 0.14em; text-transform: uppercase; opacity: 0.65; }")}
                }}}""",
        )
        assertTrue(v.notes.isEmpty())
    }

    @Test
    fun mirrorClockWithDigitalSheetIsNative() {
        assertNative(
            """{"type": "clock", "clock_size": "large", "time_format": "12", "no_background": true,
                "card_mod": {"style": {
                  ".": ${style("ha-card { --ha-clock-card-digital-font-size-large: 136px; --ha-font-weight-medium: 300; transition: opacity 0.8s; } ha-card .time-wrapper.size-large { align-items: flex-start; padding: 0 4px; }")},
                  "hui-clock-card-digital$": ${style("div.clock-container { justify-content: flex-start; } div.time-parts { line-height: 1; } div.time-parts.size-large .time-part.am-pm { font-size: 40px; font-weight: 400; align-self: end; }")}
                }}}""",
        )
    }

    @Test
    fun mirrorGridHostSheetIsNative() {
        // `ha-card { }` in a stack matches nothing in HA (a stack has no ha-card), so it's not a reason either.
        assertNative(
            """{"type": "grid", "columns": 4, "square": false, "cards": [{"type": "markdown", "content": "x"}],
                "card_mod": {"style": ${style(":host { margin: 3px 36px 0; --grid-card-gap: 11.6px; transition: opacity 0.8s; } ha-card { background: none; border: none; box-shadow: none; }")}}}""",
        )
    }

    @Test
    fun pinnedMarkdownCardIsNative() {
        assertNative(
            """{"type": "markdown", "content": "**21°**",
                "card_mod": {"style": {
                  ".": ${style("ha-card { position: fixed; top: 50%; right: 20px; transform: translateY(-50%); width: 108px; background: rgba(255,255,255,0.05); border-radius: 21.8px; border-width: 1.3px; padding: 15.4px 6.4px; --card-text-align: center; } ha-card ha-markdown { padding: 0 } :host ha-card { height: auto }")},
                  "ha-markdown$": ${style("ha-markdown-element p { margin: 0; line-height: 1.2 }")}
                }}}""",
        )
    }

    @Test
    fun markdownTableSheetsWithStructuralSelectorsAreNative() {
        val v = assertNative(
            """{"type": "markdown",
                "content": "<table role=\"presentation\"><tr><td width=\"40%\"></td><td width=\"0%\"></td></tr></table>\n##### <ha-icon icon=\"mdi:weather-night\"></ha-icon> Sleep\n###### a long line",
                "card_mod": {"style": {
                  ".": ${style("ha-card { --markdown-table-border-width: 0; --markdown-table-padding-block: 0; --markdown-table-padding-inline: 0; --markdown-image-border-radius: 12px; background: none; border: none; box-shadow: none; } ha-card ha-markdown { padding: 0 }")},
                  "ha-markdown$": ${style("""
                      ha-markdown-element { --mdc-icon-size: 18px; color: #fff }
                      ha-markdown-element table { width: 100%; border-spacing: 0; table-layout: fixed; overflow: hidden; border-radius: 99px }
                      ha-markdown-element table:nth-of-type(2n+3) td[width="0%"] { display: none }
                      ha-markdown-element table:nth-of-type(2) td:nth-child(1) { background: #a78bfa }
                      ha-markdown-element td:first-child { width: 46px; opacity: 0.55 }
                      ha-markdown-element td:nth-child(2) ha-icon { vertical-align: middle }
                      ha-markdown-element h5 ha-icon { position: relative; top: -2px; vertical-align: middle }
                      ha-markdown-element h6 { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin: 0 }
                      ha-markdown-element img { display: block; width: 100%; height: 160.5px }
                      ha-markdown-element hr { margin: 8px 0 }
                  """.trimIndent())}
                }}}""",
        )
        // Rendered natively, with the not-yet-exact parts on record.
        assertTrue(v.notes.any { "text-overflow" in it })
        assertTrue(v.notes.any { "white-space: nowrap" in it })
        assertTrue(v.notes.any { "position: relative" in it })
    }

    @Test
    fun mushroomTemplateWithGradientAndTemplatedSheetIsNative() {
        assertNative(
            """{"type": "custom:mushroom-template-card", "entity": "light.desk", "primary": "Desk",
                "card_mod": {"style": ${style("{% if is_state(entity, 'on') %}ha-card { background: linear-gradient(150deg, rgba(251,191,36,0.22), rgba(251,191,36,0.05)); border: 1px solid rgba(251,191,36,0.3); }{% else %}ha-card { background: rgba(255,255,255,0.045); border: 1px solid rgba(255,255,255,0.07); }{% endif %} ha-card { border-radius: 24px; padding: 6px 4px; box-shadow: none; }")}}}""",
        )
    }

    @Test
    fun stackInCardFillingItsCellIsNative() {
        assertNative(
            """{"type": "custom:stack-in-card", "mode": "vertical", "keep": {"background": true},
                "cards": [{"type": "custom:mushroom-template-card", "primary": "a"}],
                "card_mod": {"style": ${style("ha-card { height: 100%; box-sizing: border-box; background: none; border: none; padding: 16px 20px; --vertical-stack-card-gap: 8px; transition: opacity 0.8s; }")}}}""",
        )
    }

    @Test
    fun chipsWithVariablesAndConditionalChipsAreNativeWithAKnownGap() {
        val v = assertNative(
            """{"type": "custom:mushroom-chips-card", "alignment": "end",
                "chips": [
                  {"type": "template", "icon": "mdi:thermometer", "content": "{{ states('sensor.t') }}"},
                  {"type": "conditional", "conditions": [{"condition": "state", "entity": "binary_sensor.d", "state": "on"}],
                   "chip": {"type": "template", "icon": "mdi:door-open", "content": "Door"}}
                ],
                "card_mod": {"style": ${style("ha-card { --chip-background: rgba(255,255,255,0.08); --chip-border-radius: 99px; --chip-height: 32px; --primary-text-color: #fb7185; padding: 6px 0 0 8px; background: none; border: none; box-shadow: none; }")}}}""",
        )
        assertTrue(v.notes.any { "#792" in it })
    }

    @Test
    fun tileWithFeaturesInlineIsNativeWithAKnownGap() {
        val v = assertNative(
            """{"type": "tile", "entity": "light.desk", "features_position": "inline",
                "features": [{"type": "light-brightness"}]}""",
        )
        assertTrue(v.notes.any { "features_position" in it })
        assertNative("""{"type": "tile", "entity": "fan.a", "features": [{"type": "fan-speed"}, {"type": "toggle"}]}""")
        assertNative("""{"type": "tile", "entity": "input_select.a", "features": [{"type": "select-options"}]}""")
    }

    @Test
    fun whatHaDropsIsNotAReason() {
        // Declarations outside a rule and `!important` are dropped by HA too: dropping them is parity.
        val v = assertNative(
            """{"type": "tile", "entity": "light.a", "card_mod": {"style": ${style("background: red; ha-card { background: none !important; border-radius: 12px !important }")}}}""",
        )
        assertTrue(v.notes.isEmpty())
    }

    @Test
    fun benignPropertiesAndInteractionStatesAreNotReasons() {
        val v = assertNative(
            """{"type": "tile", "entity": "light.a", "card_mod": {"style": ${style(
                "ha-card { cursor: pointer; transition: all 0.3s ease; -webkit-tap-highlight-color: transparent; user-select: none; will-change: opacity; outline: none; animation: none } " +
                    "ha-card:hover { background: rgba(255,255,255,0.1) } ha-card:active { opacity: 0.8 } ha-card::-webkit-scrollbar { display: none }",
            )}}}""",
        )
        assertTrue(v.notes.isEmpty())
    }

    @Test
    fun backdropBlurIsAKnownGapNotAnIsland() {
        // An island can't blur native views behind it either, so falling back wouldn't help.
        val v = assertNative("""{"type": "tile", "entity": "light.a", "card_mod": {"style": ${style("ha-card { backdrop-filter: blur(12px); }")}}}""")
        assertTrue(v.notes.any { "backdrop-filter" in it })
    }

    // ---- Outside the set: must fall back, with the reason ----

    @Test
    fun unknownCardsFallBack() {
        assertIsland("""{"type": "custom:acme-floorplan-card"}""", "custom card with no native renderer")
        assertIsland("""{"type": "map"}""", "isn't a card type Vitro draws")
    }

    @Test
    fun unmodeledShadowPathsFallBack() {
        assertIsland(
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": {"mushroom-shape-icon$": ${style(".shape { --icon-size: 64px }")}}}}""",
            "card_mod path `mushroom-shape-icon$`",
        )
        assertIsland(
            """{"type": "markdown", "content": "x", "card_mod": {"style": {"ha-markdown$": {"ha-markdown-element$": "p { color: red }"}}}}""",
            "nests further",
        )
        assertIsland(
            """{"type": "tile", "entity": "light.a", "card_mod": {"style": {"ha-tile-icon$": ${style(".container { border-radius: 4px }")}}}}""",
            "card_mod path `ha-tile-icon$`",
        )
    }

    @Test
    fun selectorsIntoUnmodeledDomFallBack() {
        assertIsland(
            """{"type": "tile", "entity": "light.a", "card_mod": {"style": ${style("ha-card .primary { font-size: 20px }")}}}""",
            "reaches inside the card",
        )
        assertIsland(
            """{"type": "markdown", "title": "Hi", "content": "x", "card_mod": {"style": ${style("ha-card .card-header { color: red }")}}}""",
            "matches nothing Vitro draws",
        )
        assertIsland(
            """{"type": "markdown", "content": "x", "card_mod": {"style": {"ha-markdown$": ${style("h1::after { content: '!' }")}}}}""",
            "pseudo-element",
        )
        assertIsland(
            """{"type": "markdown", "content": "x", "card_mod": {"style": {"ha-markdown$": ${style("p:has(img) { margin: 0 }")}}}}""",
            "`:has`",
        )
        assertIsland(
            """{"type": "vertical-stack", "cards": [], "card_mod": {"style": ${style("hui-tile-card { margin: 4px }")}}}""",
            "reaches inside the stack",
        )
    }

    @Test
    fun cssVitroCannotDrawFallsBack() {
        fun tileWith(css: String) = """{"type": "tile", "entity": "light.a", "card_mod": {"style": ${style(css)}}}"""
        assertIsland(tileWith("ha-card { background: url('/local/wood.jpg') center / cover }"), "background images")
        assertIsland(tileWith("ha-card { background: radial-gradient(circle, #fff, #000) }"), "only linear gradients")
        assertIsland(tileWith("ha-card { transform: scale(0.9) }"), "transform")
        assertIsland(tileWith("ha-card { animation: pulse 2s infinite } @keyframes pulse { 50% { opacity: 0.5 } }"), "animation")
        assertIsland(tileWith("@media (max-width: 600px) { ha-card { padding: 0 } }"), "`@media`")
        assertIsland(tileWith("ha-card { filter: grayscale(1) }"), "filter")
        assertIsland(tileWith("ha-card { border: 2px dashed #fff }"), "only solid borders")
        assertIsland(tileWith("ha-card { height: 120px }"), "height")
        assertIsland(tileWith("ha-card { width: 50% }"), "only applies to pinned")
        assertIsland(tileWith("ha-card { box-shadow: inset 0 0 4px #000 }"), "inset")
        assertIsland(tileWith("ha-card { color: red }"), "`color: red` isn't supported here")
        // The same `color` on a Markdown card flows into its text through the cascade.
        assertNative("""{"type": "markdown", "content": "x", "card_mod": {"style": ${style("ha-card { color: red; font-size: 18px }")}}}""")
        assertIsland(
            """{"type": "markdown", "content": "x", "card_mod": {"style": {"ha-markdown$": ${style("p { font-family: 'Comic Sans MS' }")}}}}""",
            "font Vitro doesn't bundle",
        )
        assertIsland(
            """{"type": "markdown", "content": "x", "card_mod": {"style": {"ha-markdown$": ${style("div { display: grid }")}}}}""",
            "isn't laid out natively",
        )
    }

    @Test
    fun cardOptionsVitroCannotDrawFallBack() {
        assertIsland("""{"type": "tile", "entity": "climate.a", "features": [{"type": "climate-preset-modes"}]}""", "tile feature `climate-preset-modes`")
        assertIsland("""{"type": "entities", "entities": [{"type": "custom:slider-entity-row", "entity": "light.a"}]}""", "custom:slider-entity-row")
        assertIsland("""{"type": "entities", "entities": [{"type": "buttons", "entities": ["light.a"]}]}""", "row type `buttons`")
        assertIsland("""{"type": "entities", "entities": ["light.a"], "footer": {"type": "graph", "entity": "sensor.a"}}""", "`footer:`")
        assertIsland("""{"type": "picture-elements", "image": "/local/p.png", "elements": [{"type": "conditional", "conditions": [], "elements": []}]}""", "element `conditional`")
        assertIsland("""{"type": "custom:mushroom-chips-card", "chips": [{"type": "alarm-control-panel", "entity": "alarm_control_panel.a"}]}""", "chip `alarm-control-panel`")
        assertIsland("""{"type": "custom:bubble-card", "card_type": "pop-up", "hash": "#k"}""", "card_type: pop-up")
        assertIsland("""{"type": "custom:layout-card", "layout_type": "custom:foo-layout", "cards": []}""", "layout_type: foo-layout")
        assertIsland("""{"type": "markdown", "content": "<ha-alert alert-type=\"warning\">Door open</ha-alert>"}""", "`<ha-alert>`")
        assertIsland("""{"type": "markdown", "content": "<details><summary>More</summary>x</details>"}""", "`<details>`")
        // Tags HA's sanitizer escapes show as text there too, so they don't count.
        assertNative("""{"type": "markdown", "content": "<script>x</script> <iframe src=\"x\"></iframe>"}""")
    }

    // ---- Routing ----

    @Test
    fun theVerdictIsDecidedOncePerCard() {
        val c = card("""{"type": "tile", "entity": "light.a"}""")
        assertSame(c.compat, c.compat)
    }

    @Test
    fun aNativeTypeOutsideTheSetIsTierThree() {
        val c = card("""{"type": "custom:mushroom-template-card", "card_mod": {"style": {"mushroom-shape-icon$": ".shape { color: red }"}}}""")
        assertEquals(CardTier.WEB_ISLAND, CardRouter.tier(c))
        assertEquals(CardTier.NATIVE_HACS, CardRouter.tier(card("""{"type": "custom:mushroom-template-card"}""")))
        // A built-in card stays native: the island has no HA frontend to draw it.
        val tile = card("""{"type": "tile", "entity": "light.a", "card_mod": {"style": {"ha-tile-icon$": ".x { color: red }"}}}""")
        assertEquals(CardTier.NATIVE_CORE, CardRouter.tier(tile))
        assertTrue(tile.compat.notes.toString(), tile.compat.notes.any { "built-in" in it })
    }

    @Test
    fun aStackThatFallsBackCountsAsOneIsland() {
        val view = LovelaceParser.parseView(
            0,
            JSONObject(
                """{"cards": [
                  {"type": "vertical-stack", "cards": [{"type": "tile", "entity": "light.a"}, {"type": "custom:acme-card"}]},
                  {"type": "custom:stack-in-card", "card_mod": {"style": "@media (min-width: 1px) { :host { margin: 0 } }"},
                   "cards": [{"type": "custom:mushroom-entity-card", "entity": "light.b"}, {"type": "custom:mushroom-entity-card", "entity": "light.c"}]}
                ]}""",
            ),
        )
        val tiers = CardRouter.tierCounts(view)
        assertEquals(1, tiers[CardTier.NATIVE_CORE])
        assertEquals(2, tiers[CardTier.WEB_ISLAND])
        val report = CompatChecker.check(view)
        // The first stack and its two cards, then the second stack alone: the island draws what's inside it.
        assertEquals(4, report.entries.size)
        assertEquals(listOf("View 1 › card 1 (vertical-stack) › card 2 (custom:acme-card)", "View 1 › card 2 (custom:stack-in-card)"), report.islands.map { it.path })
        val text = report.format()
        assertTrue(text, text.startsWith("4 cards: 2 native, 2 island"))
        assertTrue(text, "ISLAND View 1 › card 2 (custom:stack-in-card)\n  - card_mod `@media` rules aren't evaluated natively" in text)
    }

    @Test
    fun aCustomContainerWithBuiltInCardsInsideStaysNative() {
        // In the island, stack-in-card would draw its tiles as summaries: further from HA than Vitro's native stack.
        val media = "@media (min-width: 1px) { :host { margin: 0 } }"
        val v = verdict(
            """{"type": "custom:stack-in-card", "card_mod": {"style": ${style(media)}},
                "cards": [{"type": "custom:mushroom-entity-card", "entity": "light.a"}, {"type": "tile", "entity": "light.b"}]}""",
        )
        assertEquals(CardRoute.NATIVE, v.route)
        assertTrue(v.notes.toString(), v.notes.any { "`@media`" in it && "built-in cards inside it" in it })
        // A built-in card deeper down counts too.
        assertEquals(
            CardRoute.NATIVE,
            verdict(
                """{"type": "custom:layout-card", "layout_type": "custom:foo-layout",
                    "cards": [{"type": "custom:vertical-stack-in-card", "cards": [{"type": "entities", "entities": ["light.a"]}]}]}""",
            ).route,
        )
        // Only custom cards inside: the island runs all of their code, so the container goes there.
        assertIsland(
            """{"type": "custom:layout-card", "layout_type": "custom:foo-layout",
                "cards": [{"type": "custom:mushroom-entity-card", "entity": "light.a"}, {"type": "custom:acme-card"}]}""",
            "layout_type: foo-layout",
        )
    }

    @Test
    fun aSelectorThatGoesOnFromTheCardReachesInside() {
        assertTrue(ThemeResolver.isHostSelector("ha-card.active"))
        assertTrue(ThemeResolver.isHostSelector("ha-card[data-state=\"on off\"]"))
        assertFalse(ThemeResolver.isHostSelector("ha-card.active .primary"))
        assertFalse(ThemeResolver.isHostSelector("ha-card.active>div"))
        assertFalse(ThemeResolver.isHostSelector("ha-card#x + ha-card"))
        assertIsland(
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card.big .primary { font-size: 20px }")}}}""",
            "reaches inside the card",
        )
    }

    @Test
    fun anInteractionStateInsideNotMatchesAtRest() {
        // `:not(:hover)` is true at rest, so its rule applies; a plain `:hover` rule never does on a touch panel.
        assertIsland(
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card:not(:hover) .shape { opacity: 0.5 }")}}}""",
            "reaches inside the card",
        )
        assertNative("""{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card:hover .shape { opacity: 0.5 }")}}}""")
    }

    @Test
    fun aCardPinnedInOneRuleIsPinnedInTheOthers() {
        // Vitro merges the card's own rules, so `position: fixed` in one pins the width and transform in another.
        assertNative(
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style(
                "ha-card { position: fixed; top: 50%; left: 50% } :host { width: 300px; transform: translate(-50%, -50%) }",
            )}}}""",
        )
        assertNative(
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style(
                "ha-card { position: fixed; top: 50%; left: 50%; transform: translateX(-50%) translateY( -50% ) }",
            )}}}""",
        )
        // The last position wins: a later `position: static` unpins it.
        assertIsland(
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style(
                "ha-card { position: fixed; width: 300px } :host { position: static }",
            )}}}""",
            "only applies to pinned",
        )
        assertIsland(
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style(
                "ha-card { position: fixed; transform: translate(-50%, -50%) scale(0.9) }",
            )}}}""",
            "only centering a pinned card",
        )
    }

    @Test
    fun layeredBackgroundsFallBackEvenAsGradients() {
        fun mushroomWith(css: String) = """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style(css)}}}"""
        assertIsland(
            mushroomWith("ha-card { background: linear-gradient(90deg, rgba(0,0,0,0.2), transparent), linear-gradient(#123, #456) }"),
            "layered backgrounds",
        )
        // Commas inside one gradient are its stops.
        assertNative(mushroomWith("ha-card { background: linear-gradient(90deg, rgba(0,0,0,0.2) 0%, #456 100%) }"))
    }

    @Test
    fun theCheckerSeesOnlyTheCardsTheViewBuilds() {
        // A sections view builds its sections and ignores `cards:`; a panel view builds its first card.
        val sections = LovelaceParser.parseView(
            0,
            JSONObject(
                """{"type": "sections", "sections": [{"cards": [{"type": "tile", "entity": "light.a"}]}],
                    "cards": [{"type": "custom:acme-card"}]}""",
            ),
        )
        assertEquals(listOf("View 1 › section 1 › card 1 (tile)"), CompatChecker.check(sections).entries.map { it.path })
        assertEquals(0, CardRouter.tierCounts(sections)[CardTier.WEB_ISLAND])
        val panel = LovelaceParser.parseView(
            0,
            JSONObject("""{"type": "panel", "cards": [{"type": "tile", "entity": "light.a"}, {"type": "custom:acme-card"}]}"""),
        )
        assertEquals(listOf("View 1 › card 1 (tile)"), CompatChecker.check(panel).entries.map { it.path })
        assertEquals(0, CardRouter.tierCounts(panel)[CardTier.WEB_ISLAND])
    }

    @Test
    fun aFullyNativeDashboardSaysSo() {
        val dash = LovelaceParser.parseDashboard(
            JSONObject("""{"views": [{"path": "home", "sections": [{"cards": [{"type": "tile", "entity": "light.a"}, {"type": "markdown", "content": "hi"}]}]}]}"""),
        )
        val report = CompatChecker.check(dash)
        assertTrue(report.islands.isEmpty())
        assertEquals("2 cards: 2 native, 0 island (fully native)", report.format())
    }

    // ---- The set agrees with the engine ----

    @Test
    fun supportedSetMatchesTheRouterAndTheCascade() {
        assertEquals(VitroSupport.CORE_CARD_TYPES, CardRouter.NATIVE_CORE_TYPES)
        assertEquals(VitroSupport.HACS_CARD_TYPES, CardRouter.NATIVE_HACS_TYPES)
        for (p in VitroSupport.PSEUDO_CLASSES) {
            val sel = when (p) {
                "not", "is", "where" -> "p:$p(h1)"
                "nth-child", "nth-last-child", "nth-of-type", "nth-last-of-type" -> "p:$p(2n+1)"
                else -> "p:$p"
            }
            assertNotNull("the cascade can't parse $sel", CssSelector.parse(sel))
        }
        assertTrue(VitroSupport.BENIGN_PROPERTIES.keys.none { it in VitroSupport.CASCADE_PROPERTIES })
        assertFalse(VitroSupport.UNDRAWN_MARKDOWN_TAGS.keys.any { it !in dev.glassworks.engine.vitro.cards.MdHtml.WHITELIST })
    }
}

/**
 * Local only: the checker on real dashboards. Set `VITRO_PARITY_DIR` to a folder of `*-new.json` Lovelace configs
 * (the Mirror, tablet and S1 dashboards) and each must render fully native. Skipped when it isn't set, so real
 * configs never need to be in the repo.
 */
class CompatCheckerLocalDashboardsTest {
    @Test
    fun realDashboardsRenderFullyNative() {
        val env = System.getenv("VITRO_PARITY_DIR")
        assumeTrue("VITRO_PARITY_DIR not set", !env.isNullOrBlank())
        // Set means asked for: a wrong path or an empty folder fails instead of passing by skipping.
        val dir = File(env!!)
        assertTrue("VITRO_PARITY_DIR $dir isn't a readable folder", dir.isDirectory && dir.canRead())
        val files = dir.listFiles { f -> f.name.endsWith("-new.json") }.orEmpty().sortedBy { it.name }
        assertTrue("no *-new.json files in $dir", files.isNotEmpty())
        val withIslands = ArrayList<String>()
        for (f in files) {
            val report = CompatChecker.check(LovelaceParser.parseDashboard(JSONObject(f.readText())))
            println("== ${f.name}\n${report.format()}\n")
            if (report.islands.isNotEmpty()) withIslands += f.name
        }
        assertTrue("islands in $withIslands", withIslands.isEmpty())
    }
}
