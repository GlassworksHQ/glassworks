/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.cards.CssSelector
import dev.glassworks.engine.vitro.compat.CardCompat
import dev.glassworks.engine.vitro.compat.CardRoute
import dev.glassworks.engine.vitro.compat.CardVerdict
import dev.glassworks.engine.vitro.compat.CompatChecker
import dev.glassworks.engine.vitro.compat.IslandScripts
import dev.glassworks.engine.vitro.compat.VitroSupport
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.CardTier
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeResolver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /**
     * The strict compat rule ([dev.glassworks.engine.vitro.compat.NativeOptions]): a built-in card, or a custom one
     * holding built-in cards, with an option or value Vitro doesn't draw as HA does sends its view to HA's frontend.
     */
    private fun assertFrontend(json: String, reasonPart: String): String {
        val reason = dev.glassworks.engine.vitro.compat.WebFallback.cardReason(card(json))
        assertNotNull("expected HA's frontend for $json", reason)
        assertTrue("the reason doesn't mention '$reasonPart': $reason", reasonPart in reason!!)
        return reason
    }

    private fun style(css: String) = JSONObject.quote(css)

    @Test
    fun autoEntitiesFilterKeysVitroDoesNotEvaluateSendTheViewToHa() {
        // auto-entities builds an entities card, which the island can't draw: a rule Vitro doesn't evaluate would leave
        // its entities out, so HA's frontend draws the view.
        val all = card(
            """{"type": "custom:auto-entities", "filter": {"include": [{"area": "kitchen", "domain": "light"},
                {"or": [{"device": "Lamp"}, {"domain": "switch"}]}], "exclude": [{"integration": "hue"}]}}""",
        ).unsupportedOptions
        assertTrue(all.toString(), listOf("include[0].area", "include[1].or[0].device", "exclude[0].integration").all { k -> all.any { k in it } })
        assertFalse(all.toString(), all.any { ".domain" in it })
        assertFrontend("""{"type": "custom:auto-entities", "filter": {"include": [{"domain": "light"}], "template": "{{ [] }}"}}""", "`filter.template`")
        assertNative("""{"type": "custom:auto-entities", "filter": {"include": [{"domain": "light"}]}}""")
        assertNull(dev.glassworks.engine.vitro.compat.WebFallback.cardReason(card("""{"type": "custom:auto-entities", "filter": {"include": [{"domain": "light"}]}}""")))
    }

    @Test
    fun anInteractionStateInsideNotStillMatchesAtRest() {
        // `:not(:hover)` matches the card at rest: the rule isn't harmless, even if every alternative mentions :hover.
        val css = "ha-card:is(:hover, :not(:hover)) .x { opacity: .2 }"
        val v = verdict("""{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style(css)}}}""")
        assertTrue(v.reasons.toString(), v.reasons.isNotEmpty())
        // `:is(:hover, :focus)` still only matches while touched.
        assertNative("""{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card:is(:hover, :focus) .x { opacity: .2 }")}}}""")
    }

    @Test
    fun selectorDiagnosticsLeaveAttributeValuesOut() {
        // A selector-map key is reported redacted too (it goes to logcat with the island's reasons).
        val keyed = verdict("""{"type": "tile", "entity": "light.a", "card_mod": {"style": {"img[src=\"/api/image?token=key-secret\"]$": ".x { color: red }"}}}""")
        val keyedAll = (keyed.reasons + keyed.notes).joinToString()
        assertFalse(keyedAll, "key-secret" in keyedAll)
        assertTrue(keyedAll, "img[src=…]" in keyedAll)
        val css = "img[src^=\"https://cam.example/signed?token=secret-value\"] { opacity: 0 }"
        val v = verdict("""{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style(css)}}}""")
        val all = (v.reasons + v.notes).joinToString()
        assertFalse(all, "secret-value" in all)
        assertTrue(all, "img[src^=…]" in all)
        assertEquals("a[href=…] b[x]", CardCompat.redactAttributeValues("a[href=\"/x?y=1\"] b[x]"))
        // A `]` inside the quoted value doesn't end the selector early (the token after it stayed in the text).
        assertEquals("img[src^=…]", CardCompat.redactAttributeValues("img[src^=\"/image?part=]&token=demo-token\"]"))
        // An escaped quote inside the value doesn't end it either.
        assertEquals("img[src=…]", CardCompat.redactAttributeValues("img[src=\"/image?q=\\\"]&token=example\"]"))
        // An escaped line break inside the value doesn't stop the match either.
        assertEquals("img[src=…]", CardCompat.redactAttributeValues("img[src=\"/a\\\nb?token=example\"]"))
        val quoted = verdict("""{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("img[src^=\"/image?part=]&token=demo-token\"] { opacity: 0 }")}}}""")
        assertFalse((quoted.reasons + quoted.notes).joinToString(), "demo-token" in (quoted.reasons + quoted.notes).joinToString())
    }

    @Test
    fun deeplyNestedInteractionSelectorsAreDecidedQuickly() {
        // 25 nested :is() around :hover used to take millions of recursive checks.
        val nested = "ha-card" + ":is(".repeat(25) + ":hover" + ")".repeat(25) + " .x { opacity: .2 }"
        val started = System.nanoTime()
        assertNative("""{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style(nested)}}}""")
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 2_000_000_000L)
    }

    @Test
    fun aLayoutBreakAmongTheExplicitEntriesLeavesNoRoomForAGeneratedCard() {
        // The explicit entry is a layout-break (not built in a vertical layout); the filter's button-card isn't listed.
        val view = LovelaceParser.parseView(0, JSONObject("""{"cards": [{"type": "custom:auto-entities", "card_param": "cards",
            "card": {"type": "custom:layout-card", "layout_type": "custom:vertical-layout"},
            "entities": [{"type": "custom:layout-break"}],
            "filter": {"include": [{"domain": "light", "options": {"type": "custom:button-card"}}]}}]}"""))
        val report = CompatChecker.check(view)
        assertTrue(report.format(), report.entries.none { "custom:button-card" in it.path })
    }

    @Test
    fun theReportsNotesHeadingFitsIslandWarningsToo() {
        val view = LovelaceParser.parseView(0, JSONObject("""{"cards": [{"type": "tile", "entity": "light.a", "card_mod": {"style": "ha-card { backdrop-filter: blur(4px) }"}}]}"""))
        val text = CompatChecker.check(view).format()
        assertTrue(text, "Compatibility notes:" in text)
        assertFalse(text, "Known gaps (native" in text)
        // An island's warning (no resource provides its card) goes under the same heading.
        val island = LovelaceParser.parseView(0, JSONObject("""{"cards": [{"type": "custom:acme-card"}]}"""))
        val islandText = CompatChecker.check(island, IslandScripts.of(emptyList())).format()
        val heading = islandText.indexOf("Compatibility notes:")
        assertTrue(islandText, heading >= 0)
        assertTrue(islandText, islandText.indexOf("no Lovelace resource seems to provide `custom:acme-card`") > heading)
    }

    // ---- Real dashboard idioms: must stay native ----

    @Test
    fun routingSkipsTheCheckOnlyForCardsThatCanOnlyStayNative() {
        // A built-in card with card_mod Vitro can't apply, a stack-in-card holding one, a Mushroom card with an
        // unsupported option and a custom card: the shortcut must route each exactly as the full check does.
        val dashboard = LovelaceParser.parseDashboard(JSONObject("""{"views": [{"title": "Home", "type": "sections", "sections": [
            {"type": "grid", "cards": [
              {"type": "markdown", "content": "x", "card_mod": {"style": "ha-card::before { content: 'x' } ha-card { filter: blur(2px) }"}},
              {"type": "custom:stack-in-card", "cards": [{"type": "tile", "entity": "light.a", "card_mod": {"style": "ha-card:hover { color: red }"}}]},
              {"type": "custom:mushroom-template-card", "primary": "x", "card_mod": {"style": "ha-card::after { color: red }"}},
              {"type": "custom:my-own-card"},
              {"type": "vertical-stack", "cards": [{"type": "custom:mushroom-chips-card", "chips": [], "card_mod": {"style": "@media (max-width: 1px) { ha-card { color: red } }"}}]}
            ]}]}]}"""))
        assertRoutingShortcutHolds(dashboard)
        val islands = CompatChecker.islands(dashboard.views.single())
        assertTrue("the custom card still falls back", islands.any { "custom:my-own-card" in it.path })
    }

    @Test
    fun aRepeatedSheetGivesTheSameVerdictAsAFreshCheck() {
        // The sheet check is kept per text; a cached answer must be the fresh one, reasons and notes in order, and a
        // sheet that reports into the same set twice (reasons and property reasons) must too.
        fun card(css: String) = LovelaceParser.parseCard(JSONObject().put("type", "markdown").put("content", "x")
            .put("card_mod", JSONObject().put("style", JSONObject()
                .put(".", "ha-card { position: fixed; top: 10px; width: 50%; color: red !important } ha-card::before { color: red }")
                .put("ha-markdown$", css))))
        val css = "ha-markdown-element h1 { font-family: Comic Sans; color: red !important } ha-markdown-element div:hover { color: blue } @media (x) { a { b: c } }"
        val first = CardCompat.check(card(css))
        val again = CardCompat.check(card(css))
        assertEquals(first, again)
        assertEquals(first.reasons.toList(), again.reasons.toList())
        assertEquals(first.notes.toList(), again.notes.toList())
        assertTrue(first.reasons.isNotEmpty() || first.notes.isNotEmpty())
    }

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
    fun tileFeaturesAreNativeOnlyWhereVitroDrawsThemAsHaDoes() {
        assertNull(dev.glassworks.engine.vitro.compat.WebFallback.cardReason(card("""{"type": "tile", "entity": "light.desk", "features": [{"type": "light-brightness"}]}""")))
        assertNull(dev.glassworks.engine.vitro.compat.WebFallback.cardReason(card("""{"type": "tile", "entity": "input_select.a", "features": [{"type": "select-options"}]}""")))
        // fan-speed draws HA's slider or its speed buttons, as the fan's step decides (tile-fan, tile-fan-speeds).
        assertNull(dev.glassworks.engine.vitro.compat.WebFallback.cardReason(card("""{"type": "tile", "entity": "fan.a", "features": [{"type": "fan-speed"}, {"type": "toggle"}]}""")))
        // Inline features aren't compared with HA yet, and alarm-modes isn't drawn.
        assertFrontend("""{"type": "tile", "entity": "light.desk", "features_position": "inline", "features": [{"type": "light-brightness"}]}""", "`features_position: inline`")
        assertFrontend("""{"type": "tile", "entity": "alarm_control_panel.a", "features": [{"type": "alarm-modes"}, {"type": "toggle"}]}""", "`features[0].type: alarm-modes`")
    }

    @Test
    fun whatHaDropsIsNotAReason() {
        // Declarations outside a rule are dropped by HA too: dropping them is parity. `!important` on the card itself
        // keeps its value, and wins among the card's own declarations as it does in HA.
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
    fun aQuotedCommentOpenerDoesNotHideTheRulesAfterIt() {
        fun mushroom(css: String) = """{"type": "custom:mushroom-template-card", "primary": "x", "card_mod": {"style": ${style(css)}}}"""
        // The browser reads `"/*"` as a string, so the `@media` after it applies; read as a comment up to the real one,
        // it hid the at-rule and the card stayed native.
        assertIsland(mushroom("""ha-card { --label: "/*"; } @media (max-width: 1px) { ha-card { padding: 0 } } /* note */"""), "`@media`")
        assertIsland(mushroom("""ha-card { --label: '/*'; } ha-card .primary { font-size: 20px } /* note */"""), "reaches inside the card")
        // A real comment still goes, the at-rule in it included.
        assertNative(mushroom("/* @media (max-width: 1px) { } */ ha-card { opacity: 0.5 }"))
        // Nor does a brace or a `;` in a string open a block or end a selector.
        assertNative(mushroom("""ha-card { --label: "/* { */"; opacity: 0.5 }"""))
        assertNative(mushroom("""ha-card[data-label="a;b{"] { opacity: 0.5 }"""))
        assertIsland(mushroom("""ha-card { --label: "{"; } ha-card .primary { font-size: 20px }"""), "reaches inside the card")
    }

    @Test
    fun anAtSignCountsOnlyWhereARuleCanStart() {
        fun mushroom(css: String) = """{"type": "custom:mushroom-template-card", "primary": "x", "card_mod": {"style": ${style(css)}}}"""
        // In a quoted attribute value (#834's quirk, which the site's checker mirrored) or inside parentheses, an `@`
        // isn't an at-rule.
        assertNative(mushroom("""ha-card[data-label="@media"] { opacity: 0.5 }"""))
        assertNative(mushroom("""ha-card[data-label='@supports'] { opacity: 0.5 }"""))
        // A bare declaration at the top (card-mod's `<style>` drops it) with an address in `url()`.
        assertNative(mushroom("--art: url(/local/a@home.png); ha-card { opacity: 0.5 }"))
        // At the start of the sheet, or after a rule or a `;`, it is.
        assertIsland(mushroom("@media (max-width: 1px) { ha-card { padding: 0 } }"), "`@media`")
        assertIsland(mushroom("ha-card { opacity: 0.5 }\n  @supports (display: grid) { ha-card { padding: 0 } }"), "`@supports`")
        assertIsland(mushroom("@charset \"utf-8\"; @layer base { ha-card { padding: 0 } }"), "`@layer`")
        assertNative(mushroom("@charset \"utf-8\"; ha-card { opacity: 0.5 }"))
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
        // Any font family draws natively: a web font a `type: css` resource declares, else the next family, as in
        // the browser (VitroFonts).
        assertNative("""{"type": "markdown", "content": "x", "card_mod": {"style": {"ha-markdown$": ${style("p { font-family: 'Comic Sans MS' }")}}}}""")
        assertNative(tileWith("ha-card { font-family: Lobster, cursive }"))
        assertIsland(
            """{"type": "markdown", "content": "x", "card_mod": {"style": {"ha-markdown$": ${style("div { display: grid }")}}}}""",
            "isn't laid out natively",
        )
    }

    @Test
    fun cardOptionsVitroCannotDrawFallBack() {
        assertFrontend("""{"type": "tile", "entity": "light.a", "features": [{"type": "climate-preset-modes"}]}""", "`features[0].type: climate-preset-modes`")
        assertFrontend("""{"type": "entities", "entities": [{"type": "custom:slider-entity-row", "entity": "light.a"}]}""", "`entities[0].type: custom:slider-entity-row`")
        assertFrontend("""{"type": "entities", "entities": [{"type": "cast", "dashboard": "lovelace"}]}""", "`entities[0].type: cast`")
        assertFrontend("""{"type": "entities", "entities": ["light.a"], "footer": {"type": "graph", "entity": "sensor.a"}}""", "`footer`")
        assertFrontend("""{"type": "picture-elements", "image": "/local/p.png", "elements": [{"type": "conditional", "conditions": [], "elements": []}]}""", "conditional elements aren't drawn")
        assertIsland("""{"type": "custom:mushroom-chips-card", "chips": [{"type": "alarm-control-panel", "entity": "alarm_control_panel.a"}]}""", "`chips[0].type: alarm-control-panel`")
        assertIsland("""{"type": "custom:bubble-card", "card_type": "pop-up", "hash": "#k"}""", "`card_type: pop-up`")
        assertIsland("""{"type": "custom:layout-card", "layout_type": "custom:foo-layout", "cards": []}""", "`layout_type: custom:foo-layout`")
        assertFrontend("""{"type": "markdown", "content": "<ha-alert alert-type=\"warning\">Door open</ha-alert>"}""", "`<ha-alert>`")
        assertFrontend("""{"type": "markdown", "content": "<details><summary>More</summary>x</details>"}""", "`<details>`")
        // Tags HA's sanitizer escapes show as text there too, so they don't count.
        assertNull(dev.glassworks.engine.vitro.compat.WebFallback.cardReason(card("""{"type": "markdown", "content": "<script>x</script> <iframe src=\"x\"></iframe>"}""")))
    }

    // ---- Routing ----

    @Test
    fun loggedReasonsDropUrlsAndCapLongValues() {
        assertEquals("url(…) no-repeat", CardCompat.loggable("url('https://example.invalid/a.png?token=abc') no-repeat"))
        assertEquals("url(…), url(…)", CardCompat.loggable("url(\"x\"), url(y)"))
        // A quoted address in any function, such as image-set(), shows as "…"; other quoted strings stay.
        assertEquals(
            "image-set(\"…\" 1x), linear-gradient(red, blue)",
            CardCompat.loggable("image-set(\"https://example.invalid/a?token=abc\" 1x), linear-gradient(red, blue)"),
        )
        assertEquals("-webkit-image-set(\"…\" 1x)", CardCompat.loggable("-webkit-image-set('/api/image/a?token=abc' 1x)"))
        assertEquals("\"Roboto\", sans-serif", CardCompat.loggable("\"Roboto\", sans-serif"))
        // Every image-set() source is an address, relative ones included; so is any quoted string with a query.
        assertEquals(
            "image-set(\"…\" 1x), linear-gradient(red, blue)",
            CardCompat.loggable("image-set(\"a.png?sig=abc\" 1x), linear-gradient(red, blue)"),
        )
        assertEquals("image-set(\"…\" 1x, \"…\" 2x)", CardCompat.loggable("image-set(\"a.png\" 1x, 'b.png' 2x)"))
        assertEquals("\"…\"", CardCompat.loggable("\"img.png?token=abc\""))
        // A declaration cut inside a quoted address (a `;` in it) leaves the rest of the address out.
        assertEquals("…", CardCompat.loggable("url(\"https://h/a?sig=abc"))
        assertEquals("#fff image-set(…", CardCompat.loggable("#fff image-set('https://h/a?sig=abc"))
        // A CSS escape doesn't end the URL early.
        assertEquals("url(…)", CardCompat.loggable("url(https://h/a\\)?token=x)"))
        assertEquals("url(…) center", CardCompat.loggable("url(\"https://h/a\\\"?token=x\") center"))
        val long = CardCompat.loggable("a".repeat(200))
        assertEquals(81, long.length)
        assertTrue(long.endsWith("…"))
    }

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
                  {"type": "custom:vertical-stack-in-card", "card_mod": {"style": "@media (min-width: 1px) { :host { margin: 0 } }"},
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
        assertEquals(listOf("View 1 › card 1 (vertical-stack) › card 2 (custom:acme-card)", "View 1 › card 2 (custom:vertical-stack-in-card)"), report.islands.map { it.path })
        val text = report.format()
        assertTrue(text, text.startsWith("4 cards: 2 native, 2 island"))
        assertTrue(text, "ISLAND View 1 › card 2 (custom:vertical-stack-in-card)\n" in text && "  - card_mod `@media` rules aren't evaluated natively" in text)
        // stack-in-card builds a built-in stack around its cards, which the island would draw as a summary: native.
        val sic = verdict(
            """{"type": "custom:stack-in-card", "card_mod": {"style": "@media (min-width: 1px) { :host { margin: 0 } }"},
                "cards": [{"type": "custom:mushroom-entity-card", "entity": "light.b"}]}""",
        )
        assertEquals(CardRoute.NATIVE, sic.route)
        assertTrue(sic.notes.toString(), sic.notes.any { "`@media`" in it && "built-in cards inside it" in it })
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
            "`layout_type: custom:foo-layout`",
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
    fun aPinnedCardIsCheckedOnTheWidthThatWinsAndNotAtZero() {
        // The calc() loses to a later !important width: the card is placed at 300px.
        assertNative(
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style(
                "ha-card { position: fixed; left: 24px; top: 0; width: calc(100% - 10px); width: 300px !important }",
            )}}}""",
        )
        // A winning calc() still falls back.
        assertIsland(
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style(
                "ha-card { position: fixed; left: 24px; top: 0; width: 300px; width: calc(100% - 10px) }",
            )}}}""",
            "a pinned width is px",
        )
        // Zero wide: the overlay would size it to its content.
        assertIsland(
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card { position: fixed; left: 24px; top: 0; width: 0px }")}}}""",
            "a pinned width of zero",
        )
    }

    @Test
    fun anUnvisitedLinkRuleIsANoteNotHarmless() {
        // `a:link` matches at rest: the Markdown card stays native, with a note that the rule isn't applied.
        val v = assertNative("""{"type": "markdown", "content": "[x](/y)", "card_mod": {"style": {"ha-markdown$": "a:link { display: none }"}}}""")
        assertTrue(v.notes.toString(), v.notes.any { ":link" in it })
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
    fun importantWinsTheCardsPositionAsInHa() {
        // card-mod keeps `!important` (checked on card-mod 4.2.1): the fixed position beats the later static one.
        assertNative(
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style(
                "ha-card { position: fixed !important; top: 0 } :host { position: static; width: 300px }",
            )}}}""",
        )
        val mod = ThemeResolver.parseCardMod("ha-card { position: fixed !important; opacity: 0.5 !important } ha-card { position: static; opacity: 0.8 }")
        assertTrue(mod.positionFixed)
        assertEquals(0.5f, mod.opacity!!, 0.001f)
        // Inside Markdown, Vitro keeps the value but not the priority: a note.
        val v = assertNative(
            """{"type": "markdown", "content": "## Hi", "card_mod": {"style": {"ha-markdown$": ${style("h2 { font-size: 34px !important }")}}}}""",
        )
        assertTrue(v.notes.toString(), v.notes.any { "!important" in it })
    }

    @Test
    fun anInteractionStateIsBenignOnlyWhenEveryAlternativeHasOne() {
        assertIsland(
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card:is(:hover, ha-card) { filter: grayscale(1) }")}}}""",
            "reaches inside the card",
        )
        assertNative("""{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card:is(:hover, :focus) { filter: grayscale(1) }")}}}""")
        assertNative("""{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card:hover { filter: grayscale(1) }")}}}""")
    }

    @Test
    fun aPinnedTranslateMustBeOneTheOverlayApplies() {
        fun pinned(transform: String) =
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card { position: fixed; left: 50%; top: 0; transform: $transform }")}}}"""
        assertNative(pinned("translate(-50%, 0)"))
        assertNative(pinned("translate(-50%)"))
        assertIsland(pinned("translate(-50%, 12px)"), "only centering a pinned card")
        assertIsland(pinned("translateY(20px)"), "only centering a pinned card")
        // The overlay centers an axis and ignores its offset, which matches HA only at 50%: at `top: 0`,
        // `translate(-50%, -50%)` moves the card up by half its height in HA.
        assertIsland(pinned("translate(-50%, -50%)"), "only centering a pinned card")
        fun pinnedAt(offsets: String, transform: String) =
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card { position: fixed; $offsets; transform: $transform }")}}}"""
        assertNative(pinnedAt("left: 50%; top: 50%", "translate(-50%, -50%)"))
        assertNative(pinnedAt("right: 20px; top: 50%", "translateY(-50%)"))
        assertIsland(pinnedAt("left: 20px; top: 50%", "translate(-50%, -50%)"), "only centering a pinned card")
        // One centering shift per axis: two on X would move the card a whole width.
        assertIsland(pinnedAt("left: 50%; top: 0", "translateX(-50%) translateX(-50%)"), "only centering a pinned card")
        assertNative(pinnedAt("left: 50%; top: 50%", "translateX(-50%) translateY(-50%)"))
        // The offset that wins counts, `!important` included.
        assertNative(pinnedAt("left: 50%; top: 0 !important; top: 50% !important; top: 10px", "translateY(-50%)"))
    }

    @Test
    fun pointerEventsNoneChangesWhatATapDoes() {
        fun mushroomWith(css: String) = """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style(css)}}}"""
        assertIsland(mushroomWith("ha-card { pointer-events: none }"), "`pointer-events: none`")
        assertNative(mushroomWith("ha-card { pointer-events: auto }"))
        // A built-in card stays native and notes it.
        assertIsland("""{"type": "tile", "entity": "light.a", "card_mod": {"style": ${style("ha-card { pointer-events: none }")}}}""", "pointer-events")
    }

    @Test
    fun aFocusStateOfTheCardIsNotTheCardAtRest() {
        assertFalse(ThemeResolver.isHostSelector("ha-card.warning:focus-visible"))
        assertFalse(ThemeResolver.isHostSelector("ha-card.warning:focus"))
        // A comma inside a quoted attribute value doesn't split the selector list.
        assertFalse(ThemeResolver.isHostSelector("ha-card[data-state=\"on,off\"] .primary"))
        assertEquals(null, ThemeResolver.parseCardMod("ha-card[data-state=\"on,off\"] .primary { opacity: 0 }").opacity)
        assertTrue(ThemeResolver.isHostSelector("ha-card, :host"))
        assertEquals(null, ThemeResolver.parseCardMod("ha-card.warning:focus-visible { opacity: 0 }").opacity)
        assertNative("""{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card.warning:focus-visible { opacity: 0 }")}}}""")
    }

    @Test
    fun aPinnedWidthMustBeOneTheOverlayReads() {
        fun pinnedWidth(w: String) =
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card { position: fixed; top: 0; left: 0; width: $w }")}}}"""
        assertNative(pinnedWidth("300px"))
        assertNative(pinnedWidth("50%"))
        assertNative(pinnedWidth("20rem"))
        assertIsland(pinnedWidth("calc(100% - 24px)"), "a pinned width")
        // Offsets too: the overlay can't read `calc()`.
        assertIsland(
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card { position: fixed; left: 0; top: calc(50% - 24px) }")}}}""",
            "a pinned offset",
        )
        assertNative("""{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card { position: fixed; left: 24px; bottom: 5% }")}}}""")
    }

    @Test
    fun sizeContainmentIsNotAHint() {
        fun mushroomWith(css: String) = """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style(css)}}}"""
        assertIsland(mushroomWith("ha-card { contain: size }"), "`contain: size`")
        assertIsland(mushroomWith("ha-card { contain: strict }"), "`contain: strict`")
        assertNative(mushroomWith("ha-card { contain: content }"))
        assertNative(mushroomWith("ha-card { contain: layout paint }"))
    }

    @Test
    fun aPseudoElementOfTheCardIsNotTheCard() {
        assertFalse(ThemeResolver.isHostSelector("ha-card.warning::before"))
        assertFalse(ThemeResolver.isHostSelector("ha-card.warning:after"))
        assertTrue(ThemeResolver.isHostSelector("ha-card.warning"))
        assertEquals(null, ThemeResolver.parseCardMod("ha-card.warning::before { opacity: 0 }").opacity)
        assertIsland(
            """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card.warning::before { opacity: 0 }")}}}""",
            "pseudo-element `::before`",
        )
    }

    @Test
    fun autoEntitiesBuildingBuiltInCardsStaysNative() {
        val media = style("@media (min-width: 1px) { :host { margin: 0 } }")
        // Its default `entities` card, and with `card_param: cards` a tile per entity, would be summaries in the island.
        val v = verdict("""{"type": "custom:auto-entities", "filter": {"include": [{"domain": "light"}]}, "card_mod": {"style": $media}}""")
        assertEquals(CardRoute.NATIVE, v.route)
        assertTrue(v.notes.toString(), v.notes.any { "built-in cards inside it" in it })
        assertEquals(
            CardRoute.NATIVE,
            verdict(
                """{"type": "custom:auto-entities", "card": {"type": "custom:layout-card"}, "card_param": "cards",
                    "filter": {"include": [{"domain": "light"}]}, "card_mod": {"style": $media}}""",
            ).route,
        )
        // Top-level `entities:` entries become cards too: a string as a tile, an object as its own `type` (a tile by default).
        fun autoWith(entities: String) = verdict(
            """{"type": "custom:auto-entities", "card": {"type": "custom:layout-card"}, "card_param": "cards",
                "entities": $entities, "filter": {"include": []}, "card_mod": {"style": $media}}""",
        )
        assertEquals(CardRoute.NATIVE, autoWith("""[{"entity": "light.a", "type": "tile"}]""").route)
        assertEquals(CardRoute.NATIVE, autoWith("""["light.a"]""").route)
        assertEquals(CardRoute.ISLAND, autoWith("""[{"entity": "light.a", "type": "custom:mushroom-light-card"}]""").route)
        // A built-in card inside its custom `card:` counts too, with the default `card_param: entities`.
        val nested = verdict(
            """{"type": "custom:auto-entities", "card": {"type": "custom:layout-card", "cards": [{"type": "tile", "entity": "light.a"}]},
                "filter": {"include": [{"domain": "light"}]}, "card_mod": {"style": $media}}""",
        )
        assertEquals(CardRoute.NATIVE, nested.route)
        assertTrue(nested.notes.toString(), nested.notes.any { "built-in cards inside it" in it })
        // The report lists auto-entities' own card, not the per-entity ones (they depend on live states).
        val view = LovelaceParser.parseView(0, JSONObject("""{"cards": [{"type": "custom:auto-entities", "card": {"type": "custom:acme-card"}, "filter": {"include": [{"domain": "light"}]}}]}"""))
        assertEquals(
            listOf("View 1 › card 1 (custom:auto-entities)", "View 1 › card 1 (custom:auto-entities) › card 1 (custom:acme-card)"),
            CompatChecker.check(view).entries.map { it.path },
        )
        // `card_param: cards`: the inner card is checked with the cards auto-entities puts in it, and what the config
        // wrote in that field is replaced, so it doesn't count. The explicit `entities:` entries are listed (they are
        // built whatever the states); the cards a filter matches and the replaced ones aren't.
        val generated = LovelaceParser.parseView(
            0,
            JSONObject(
                """{"cards": [{"type": "custom:auto-entities", "card_param": "cards", "entities": [{"entity": "light.a", "type": "tile"}],
                    "card": {"type": "custom:layout-card", "layout_type": "custom:masonry-layout"}}]}""",
            ),
        )
        val genReport = CompatChecker.check(generated)
        assertTrue(genReport.format(), genReport.islands.isEmpty())
        assertEquals(
            listOf(
                "View 1 › card 1 (custom:auto-entities)",
                "View 1 › card 1 (custom:auto-entities) › card 1 (custom:layout-card)",
                "View 1 › card 1 (custom:auto-entities) › card 1 (custom:layout-card) › entity 1 (tile)",
            ),
            genReport.entries.map { it.path },
        )
        // An explicit custom card among them is an island in the report, not "fully native".
        val explicitIsland = LovelaceParser.parseView(
            0,
            JSONObject(
                """{"cards": [{"type": "custom:auto-entities", "card_param": "cards", "card": {"type": "grid"},
                    "entities": [{"entity": "light.a", "type": "custom:acme-card"}], "filter": {"include": [{"domain": "light"}]}}]}""",
            ),
        )
        val explicitReport = CompatChecker.check(explicitIsland)
        assertEquals(listOf("View 1 › card 1 (custom:auto-entities) › card 1 (grid) › entity 1 (custom:acme-card)"), explicitReport.islands.map { it.path })
        // Static children of the inner card are listed: a conditional's `card:` isn't generated.
        val conditional = LovelaceParser.parseView(
            0,
            JSONObject(
                """{"cards": [{"type": "custom:auto-entities", "card_param": "cards", "entities": ["light.a"],
                    "card": {"type": "conditional", "conditions": [], "card": {"type": "custom:acme-card"}}}]}""",
            ),
        )
        assertEquals(
            listOf(
                "View 1 › card 1 (custom:auto-entities)",
                "View 1 › card 1 (custom:auto-entities) › card 1 (conditional)",
                "View 1 › card 1 (custom:auto-entities) › card 1 (conditional) › card 1 (custom:acme-card)",
            ),
            CompatChecker.check(conditional).entries.map { it.path },
        )
        val replaced = card(
            """{"type": "custom:auto-entities", "card_param": "cards", "entities": [{"entity": "light.a", "type": "custom:mushroom-entity-card"}],
                "card": {"type": "custom:layout-card", "layout_type": "masonry", "cards": [{"type": "custom:acme-card"}]}}""",
        )
        val types = CardRouter.producedCards(replaced).flatMap { (_, c) -> listOf(c.type) + c.childCards.map { it.type } }
        assertFalse(types.toString(), "custom:acme-card" in types)
        assertTrue(types.toString(), "custom:mushroom-entity-card" in types)
        assertTrue(CompatChecker.check(LovelaceParser.parseView(0, JSONObject("""{"cards": [${replaced.raw}]}"""))).islands.isEmpty())
        // Custom cards all the way down: the island runs them all.
        assertIsland(
            """{"type": "custom:auto-entities", "card": {"type": "custom:layout-card"}, "card_param": "cards",
                "filter": {"include": [{"domain": "light", "options": {"type": "custom:mushroom-light-card"}}]}, "card_mod": {"style": $media}}""",
            "`@media`",
        )
    }

    private fun res(vararg urls: String) = IslandScripts.of(urls.map { u ->
        dev.glassworks.engine.vitro.model.LovelaceResource(u, if (u.endsWith(".css")) "css" else "module")
    })

    @Test
    fun aCardGoesToTheIslandOnlyWhenTheIslandCanDrawIt() {
        val mushroom = "/hacsfiles/lovelace-mushroom/mushroom.js?hacstag=1"
        val cardMod = "/hacsfiles/lovelace-card-mod/card-mod.js?hacstag=1"
        // A native Mushroom card with an unsupported selector: the island needs Mushroom's script.
        val styled = card("""{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card .shape { color: red }")}}}""")
        assertTrue("a card_mod selector is a card_mod reason", styled.compat.cardModOnly)
        assertTrue(styled.compat.inIsland(res(mushroom, cardMod)))
        assertFalse(styled.compat.inIsland(res(cardMod)))
        assertEquals(CardTier.NATIVE_HACS, CardRouter.tier(styled, res(cardMod)))
        val view = LovelaceParser.parseView(0, JSONObject("""{"cards": [${styled.raw}]}"""))
        val report = CompatChecker.check(view, res(cardMod))
        assertTrue(report.islands.isEmpty())
        assertTrue(report.notes.toString(), report.notes.any { "no script for `custom:mushroom-template-card`" in it.second })
        // Without the resource list (the offline check), every script is assumed there.
        assertEquals(1, CompatChecker.check(view).islands.size)

        // A card_mod-only fallback also needs card-mod.
        val pathOnly = card("""{"type": "custom:mushroom-template-card", "card_mod": {"style": {"mushroom-shape-icon$": ".shape { color: red }"}}}""")
        assertTrue(pathOnly.compat.cardModOnly)
        assertTrue(pathOnly.compat.inIsland(res(mushroom, cardMod)))
        assertFalse(pathOnly.compat.inIsland(res(mushroom)))
        assertTrue(CompatChecker.check(LovelaceParser.parseView(0, JSONObject("""{"cards": [${pathOnly.raw}]}""")), res(mushroom))
            .notes.any { "can't load card-mod" in it.second })

        // A custom card with no native renderer goes there regardless, with a note when nothing seems to provide it.
        val acme = card("""{"type": "custom:acme-card"}""")
        assertTrue(acme.compat.inIsland(res()))
        val acmeView = LovelaceParser.parseView(0, JSONObject("""{"cards": [{"type": "custom:acme-card"}]}"""))
        assertTrue(CompatChecker.check(acmeView, res()).notes.any { "may not draw it" in it.second })
        assertTrue(CompatChecker.check(acmeView, res("/local/acme-card.js")).notes.isEmpty())
    }

    @Test
    fun theIslandNeedsTheScriptsOfTheCardsInsideItToo() {
        val cardMod = "/hacsfiles/lovelace-card-mod/card-mod.js"
        val stack = "/hacsfiles/vertical-stack-in-card/vertical-stack-in-card.js"
        val mushroom = "/hacsfiles/lovelace-mushroom/mushroom.js"
        val c = card(
            """{"type": "custom:vertical-stack-in-card", "card_mod": {"style": ${style("@media (min-width: 1px) { :host { margin: 0 } }")}},
                "cards": [{"type": "custom:mushroom-entity-card", "entity": "light.a"}]}""",
        )
        assertTrue(c.compat.inIsland(res(cardMod, stack, mushroom)))
        // Without Mushroom's script, the island would lose the child's control: the stack stays native and says why.
        assertFalse(c.compat.inIsland(res(cardMod, stack)))
        val v = c.compat.effective(res(cardMod, stack))
        assertTrue(v.isNative)
        assertTrue(v.notes.toString(), v.notes.any { "no script for `custom:mushroom-entity-card`" in it })
        // A card auto-entities builds counts as well.
        val auto = card(
            """{"type": "custom:auto-entities", "card": {"type": "custom:layout-card"}, "card_param": "cards",
                "filter": {"include": [{"domain": "light", "options": {"type": "custom:mushroom-light-card"}}]},
                "card_mod": {"style": ${style("@media (min-width: 1px) { :host { margin: 0 } }")}}}""",
        )
        assertFalse(auto.compat.inIsland(res(cardMod, "/hacsfiles/auto-entities/auto-entities.js", "/hacsfiles/layout-card/layout-card.js")))
        assertTrue(auto.compat.inIsland(res(cardMod, "/hacsfiles/auto-entities/auto-entities.js", "/hacsfiles/layout-card/layout-card.js", mushroom)))
    }

    @Test
    fun islandScriptsMatchFileNames() {
        // Every card Vitro draws natively has a known script.
        assertEquals(VitroSupport.HACS_CARD_TYPES, VitroSupport.CARD_SCRIPTS.keys)
        assertTrue(res("/hacsfiles/Bubble-Card/bubble-card.js?hacstag=2").provides("custom:bubble-card"))
        assertTrue(res("/hacsfiles/mini-graph-card/mini-graph-card-bundle.js").provides("custom:mini-graph-card"))
        assertFalse(res("/local/other.js").provides("custom:bubble-card"))
        // card-mod by file name: not from a stylesheet or a query.
        assertTrue(res("/local/community/lovelace-card-mod/card-mod.js?v=4.2.1").cardMod)
        assertFalse(res().cardMod)
        assertFalse(res("/local/card-mod-preview.css").cardMod)
        assertFalse(res("/local/theme.js?variant=card-mod").cardMod)
        assertTrue(res("/local/acme.js").mentions("custom:acme-card"))
        assertFalse(res("/local/other.js").mentions("custom:acme-card"))
    }

    @Test
    fun pinnedGeometryIsAnAllowList() {
        // Only what the overlay places exactly stays native; everything else on a pinned card is a reason.
        val table = listOf(
            "top: 0; left: 0" to true,
            "bottom: 24px; right: 5%" to true,
            "top: 1.5rem; right: auto; left: 24px" to true,
            "left: 24px; right: 24px; width: 300px" to true,
            "left: 50%; top: 50%; transform: translate(-50%, -50%)" to true,
            "right: 20px; top: 50%; transform: translateY(-50%)" to true,
            "left: 50%; bottom: 0; transform: translateX(-50%)" to true,
            "top: unset; bottom: 0; left: initial; right: 0" to true,
            "left: 24px; right: 24px" to false,
            "left: 24px; right: 24px; width: auto" to false,
            "top: 0; bottom: 0" to false,
            "top: calc(50% - 24px); left: 0" to false,
            "left: 50%; right: 0; transform: translateX(-50%)" to false,
            "top: 50%; bottom: 10px; transform: translateY(-50%)" to false,
            "top: 0; left: 50%; transform: translate(-50%, -50%)" to false,
        )
        for ((geometry, native) in table) {
            val v = verdict("""{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style("ha-card { position: fixed; $geometry }")}}}""")
            assertEquals("`$geometry`: ${v.reasons}", native, v.isNative)
        }
        // A built-in card keeps its native renderer and notes it.
        assertIsland("""{"type": "markdown", "content": "x", "card_mod": {"style": ${style("ha-card { position: fixed; left: 24px; right: 24px }")}}}""", "pinned card")
    }

    @Test
    fun imageSetIsABackgroundImage() {
        fun mushroomWith(css: String) = """{"type": "custom:mushroom-template-card", "card_mod": {"style": ${style(css)}}}"""
        assertIsland(mushroomWith("ha-card { background-image: image-set(\"wood.png\" 1x) }"), "background images")
        assertIsland(mushroomWith("ha-card { background: -webkit-image-set('wood.png' 1x) }"), "background images")
        assertNative(mushroomWith("ha-card { background-image: none }"))
        assertNative(mushroomWith("ha-card { background-image: linear-gradient(#123, #456) }"))
    }

    @Test
    fun aVerticalLayoutBreakIsNoCard() {
        val view = LovelaceParser.parseView(
            0,
            JSONObject(
                """{"cards": [{"type": "custom:layout-card", "layout_type": "custom:vertical-layout",
                    "cards": [{"type": "tile", "entity": "light.a"}, {"type": "custom:layout-break"}, {"type": "tile", "entity": "light.b"}]}]}""",
            ),
        )
        val report = CompatChecker.check(view)
        assertTrue(report.format(), report.islands.isEmpty())
        // The original indices stay in the paths.
        assertEquals(
            listOf("View 1 › card 1 (custom:layout-card)", "View 1 › card 1 (custom:layout-card) › card 1 (tile)", "View 1 › card 1 (custom:layout-card) › card 3 (tile)"),
            report.entries.map { it.path },
        )
        assertEquals(0, CardRouter.tierCounts(view)[CardTier.WEB_ISLAND])
        // In other layouts the marker isn't consumed, so it stays a card.
        val masonry = LovelaceParser.parseView(0, JSONObject("""{"cards": [{"type": "custom:layout-card", "cards": [{"type": "custom:layout-break"}]}]}"""))
        assertEquals(1, CompatChecker.check(masonry).islands.size)
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
        assumeTrue("VITRO_PARITY_DIR not set", env != null)
        assertTrue("VITRO_PARITY_DIR is set but blank", env!!.isNotBlank())
        // Set means asked for: a wrong path or an empty folder fails instead of passing by skipping.
        val dir = File(env)
        assertTrue("VITRO_PARITY_DIR $dir isn't a readable folder", dir.isDirectory && dir.canRead())
        val files = dir.listFiles { f -> f.name.endsWith("-new.json") }.orEmpty().sortedBy { it.name }
        assertTrue("no *-new.json files in $dir", files.isNotEmpty())
        val withIslands = ArrayList<String>()
        for (f in files) {
            val dashboard = LovelaceParser.parseDashboard(JSONObject(f.readText()))
            val report = CompatChecker.check(dashboard)
            assertTrue("${f.name} has no cards", report.entries.isNotEmpty())
            assertRoutingShortcutHolds(dashboard)
            println("== ${f.name}\n${report.format()}\n")
            if (report.islands.isNotEmpty()) withIslands += f.name
        }
        assertTrue("islands in $withIslands", withIslands.isEmpty())
    }
}

/**
 * The routing shortcut ([CardCompat.staysNative], [CardConfig.inIsland], [CompatChecker.islands]) against the full
 * check, for every card [dashboard] builds: a card the shortcut keeps native is native in the full check, every card
 * routes the same, and each view lists the same islands.
 */
internal fun assertRoutingShortcutHolds(dashboard: dev.glassworks.engine.vitro.model.DashboardConfig) {
    for (view in dashboard.views) {
        val full = CompatChecker.check(view)
        assertEquals("islands of ${view.title}", full.islands, CompatChecker.islands(view))
        fun walk(c: dev.glassworks.engine.vitro.model.CardConfig) {
            val verdict = CardCompat.check(c)
            if (CardCompat.staysNative(c)) assertTrue("${c.type} kept native by the shortcut is native", verdict.isNative)
            assertEquals("${c.type} routes the same", verdict.inIsland(), c.inIsland())
            CardRouter.producedCards(c).forEach { (_, child) -> walk(child) }
        }
        CardRouter.viewRoots(view).forEach { walk(it.card) }
    }
}

