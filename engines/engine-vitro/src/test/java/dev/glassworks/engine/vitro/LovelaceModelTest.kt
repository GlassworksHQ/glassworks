/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.cards.AlarmPanelCardView
import dev.glassworks.engine.vitro.cards.AreaCardView
import dev.glassworks.engine.vitro.cards.EntityCardView
import dev.glassworks.engine.vitro.cards.GaugeCardView
import dev.glassworks.engine.vitro.cards.StatisticsGraphCardView
import dev.glassworks.engine.vitro.cards.TodoItem
import dev.glassworks.engine.vitro.cards.TodoListCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.CardTier
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.VisibilityCondition
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Lovelace parser, the tier router and the visibility conditions. Pure JVM. */
class LovelaceModelTest {

    private fun card(json: String): CardConfig = LovelaceParser.parseCard(JSONObject(json))

    private fun view(json: String) = LovelaceParser.parseView(0, JSONObject(json))

    private fun states(vararg pairs: Pair<String, String>): (String) -> EntityState? {
        val map = pairs.associate { (id, s) -> id to EntityState(id, s, emptyMap()) }
        return { id -> map[id] }
    }

    // ---- Views ----

    @Test
    fun sectionsViewKeepsGridOptionsColumnSpanAndVisibility() {
        val dash = LovelaceParser.parseDashboard(
            JSONObject(
                """{
                  "title": "Test Home",
                  "views": [{
                    "type": "sections", "title": "Living", "path": "living", "icon": "mdi:sofa",
                    "theme": "glass_night", "background": "#101820", "max_columns": 3,
                    "sections": [
                      {"type": "grid", "title": "Lights", "column_span": 2,
                       "visibility": [{"condition": "state", "entity": "input_boolean.guest_mode", "state": "on"}],
                       "cards": [
                         {"type": "tile", "entity": "light.lounge_lamp", "grid_options": {"columns": 6, "rows": 2}},
                         {"type": "tile", "entity": "light.lounge_strip", "grid_options": {"columns": 12}}
                       ]},
                      {"type": "grid", "column_span": 9, "cards": []}
                    ]
                  }]
                }""",
            ),
        )
        assertEquals("Test Home", dash.title)
        assertEquals(1, dash.views.size)
        val v = dash.views[0]
        assertEquals("sections", v.type)
        assertEquals("Living", v.title)
        assertEquals("living", v.path)
        assertEquals("mdi:sofa", v.icon)
        assertEquals("glass_night", v.theme)
        assertEquals("#101820", v.background)
        assertEquals(3, v.maxColumns)
        assertEquals(2, v.sections.size)

        val lights = v.sections[0]
        assertEquals("Lights", lights.title)
        assertEquals(2, lights.columnSpan)
        assertEquals(listOf(VisibilityCondition.State("input_boolean.guest_mode", "on", null)), lights.visibility)
        assertEquals(2, lights.cards.size)
        assertEquals(6, lights.cards[0].gridOptionsColumns())
        assertEquals(12, lights.cards[1].gridOptionsColumns())

        // column_span is clamped to 1..4.
        assertEquals(4, v.sections[1].columnSpan)
        assertNull(v.sections[1].title)
    }

    @Test
    fun masonryViewIsTheDefaultAndGetsATitleFromItsPosition() {
        val dash = LovelaceParser.parseDashboard(
            JSONObject(
                """{"views": [
                  {"title": "Home", "cards": [{"type": "entities", "title": "Sensors", "entities": ["sensor.kitchen_temp"]}]},
                  {"cards": [{"type": "tile", "entity": "light.lounge_lamp"}]}
                ]}""",
            ),
        )
        assertNull(dash.title)
        assertEquals(listOf("masonry", "masonry"), dash.views.map { it.type })
        assertEquals("Home", dash.views[0].title)
        assertEquals("View 2", dash.views[1].title)
        assertEquals(1, dash.views[0].cards.size)
        assertEquals(listOf(0, 1), dash.views.map { it.index })
        assertEquals(4, dash.views[0].maxColumns) // default
        assertTrue(dash.views[0].sections.isEmpty())
    }

    @Test
    fun viewTypeIsInferredFromPanelFlagOrSections() {
        assertEquals("panel", view("""{"panel": true, "cards": [{"type": "custom:acme-wall-card"}]}""").type)
        assertEquals("sections", view("""{"sections": [{"type": "grid", "cards": []}]}""").type)
        assertEquals("masonry", view("""{"cards": []}""").type)
        assertEquals("sidebar", view("""{"type": "sidebar", "cards": []}""").type) // explicit type wins
        assertEquals(6, view("""{"max_columns": 99}""").maxColumns) // clamped
        assertEquals(1, view("""{"max_columns": 0}""").maxColumns)
    }

    @Test
    fun panelViewHoldsOneFullScreenCustomCard() {
        val v = view(
            """{"type": "panel", "title": "Wall", "path": "wall", "cards": [
                 {"type": "custom:acme-wall-card", "rooms": [{"name": "Lounge", "lights": ["light.lounge_lamp"]}]}
               ]}""",
        )
        assertEquals("panel", v.type)
        assertEquals(1, v.cards.size)
        assertEquals("custom:acme-wall-card", v.cards[0].type)
        assertEquals(CardTier.WEB_ISLAND, CardRouter.tier(v.cards[0]))
        assertTrue(v.sections.isEmpty())
    }

    @Test
    fun dashboardWithoutViewsParsesToEmpty() {
        val dash = LovelaceParser.parseDashboard(JSONObject("""{"title": "Empty"}"""))
        assertEquals("Empty", dash.title)
        assertTrue(dash.views.isEmpty())
    }

    @Test
    fun nonObjectViewsAndCardsAreSkipped() {
        val dash = LovelaceParser.parseDashboard(JSONObject("""{"views": ["junk", {"cards": [42, {"type": "tile"}]}]}"""))
        // The junk entry is skipped but the index of the next view stays its position in the array.
        assertEquals(1, dash.views.size)
        assertEquals(1, dash.views[0].index)
        assertEquals(1, dash.views[0].cards.size)
    }

    // ---- Cards ----

    @Test
    fun cardWithoutATypeIsAnEntitiesCard() {
        assertEquals("entities", card("""{"entities": ["sensor.kitchen_temp"]}""").type)
        assertEquals("entities", card("""{"type": ""}""").type)
    }

    @Test
    fun stackGridAndConditionalCardsParseTheirChildren() {
        val stack = card(
            """{"type": "vertical-stack", "cards": [{"type": "tile", "entity": "light.a"}, {"type": "horizontal-stack", "cards": [{"type": "tile", "entity": "light.b"}]}]}""",
        )
        assertEquals(2, stack.childCards.size)
        assertEquals(1, stack.childCards[1].childCards.size)

        val cond = card("""{"type": "conditional", "conditions": [], "card": {"type": "tile", "entity": "light.c"}}""")
        assertEquals(listOf("tile"), cond.childCards.map { it.type })

        val grid = card("""{"type": "grid", "columns": 9, "cards": [{"type": "tile"}]}""")
        assertEquals(6, grid.gridColumns()) // clamped
        assertEquals(3, card("""{"type": "grid"}""").gridColumns()) // default
        assertEquals(1, card("""{"type": "grid", "columns": 0}""").gridColumns())

        // A card without children keeps none, even if it carries a `cards` key it does not own.
        assertTrue(card("""{"type": "tile", "cards": [{"type": "tile"}]}""").childCards.isEmpty())
    }

    @Test
    fun cardAccessorsReadTheCommonKeys() {
        val c = card("""{"type": "tile", "entity": "light.lounge_lamp", "name": "Lamp", "title": "T", "icon": "mdi:lamp", "theme": "glass_night"}""")
        assertEquals("light.lounge_lamp", c.entity)
        assertEquals("Lamp", c.name)
        assertEquals("T", c.title)
        assertEquals("mdi:lamp", c.icon)
        assertEquals("glass_night", c.theme)
        val bare = card("""{"type": "tile"}""")
        assertNull(bare.entity)
        assertNull(bare.name)
        assertNull(bare.theme)
    }

    @Test
    fun entitiesRowsAcceptStringsAndObjects() {
        val rows = card(
            """{"type": "entities", "entities": [
                 "sensor.kitchen_temp",
                 "   ",
                 {"entity": "light.lounge_lamp", "name": "Lamp", "icon": "mdi:lamp", "secondary_info": "last-changed", "tap_action": {"action": "toggle"}},
                 {"name": "no entity, skipped"},
                 7
               ]}""",
        ).entities()
        assertEquals(listOf("sensor.kitchen_temp", "light.lounge_lamp"), rows.map { it.entity })
        assertNull(rows[0].name)
        assertEquals("Lamp", rows[1].name)
        assertEquals("mdi:lamp", rows[1].icon)
        assertEquals("last-changed", rows[1].secondaryInfo)
        assertEquals("toggle", rows[1].tapAction?.action)
        assertTrue(card("""{"type": "tile"}""").entities().isEmpty())
    }

    @Test
    fun actionConfigParsesBothServiceSpellings() {
        assertEquals("more-info", ActionConfig.parse(null).action)
        assertEquals("toggle", ActionConfig.parse(null, "toggle").action)
        assertEquals("more-info", ActionConfig.parse(JSONObject("""{"action": ""}""")).action)

        val performAction = ActionConfig.parse(
            JSONObject("""{"action": "perform-action", "perform_action": "light.turn_on", "data": {"brightness": 200}, "target": {"entity_id": "light.lounge_lamp"}}"""),
        )
        assertEquals("light.turn_on", performAction.service)
        assertEquals(200, performAction.serviceData?.getInt("brightness"))
        assertEquals("light.lounge_lamp", performAction.target?.getString("entity_id"))

        val legacy = ActionConfig.parse(JSONObject("""{"action": "call-service", "service": "switch.toggle", "service_data": {"entity_id": "switch.fan"}}"""))
        assertEquals("switch.toggle", legacy.service)
        assertEquals("switch.fan", legacy.serviceData?.getString("entity_id"))

        val nav = ActionConfig.parse(JSONObject("""{"action": "navigate", "navigation_path": "/lovelace/living"}"""))
        assertEquals("/lovelace/living", nav.navigationPath)
        assertEquals("/lovelace/living", ActionConfig.parse(JSONObject("""{"action": "url", "url_path": "/lovelace/living"}""")).urlPath)
    }

    @Test
    fun stringBadgesBecomeEntityCards() {
        val v = view("""{"badges": ["sensor.kitchen_temp", {"type": "entity", "entity": "light.lounge_lamp"}, 3], "cards": []}""")
        assertEquals(2, v.badges.size)
        assertEquals("sensor.kitchen_temp", v.badges[0].entity)
        assertEquals("entity", v.badges[0].type)
        assertEquals("light.lounge_lamp", v.badges[1].entity)
    }

    // ---- Tiers ----

    @Test
    fun tierOneIsTheBuiltInHomeAssistantCards() {
        for (t in listOf("tile", "entities", "sensor", "clock", "thermostat", "glance", "gauge", "markdown", "history-graph")) {
            assertEquals("$t is Tier 1", CardTier.NATIVE_CORE, CardRouter.tier(card("""{"type": "$t"}""")))
        }
    }

    @Test
    fun tierTwoIsThePopularCustomCardsWithNativeFastPaths() {
        for (t in listOf("custom:mushroom-entity-card", "custom:bubble-card", "custom:mini-graph-card", "custom:clock-weather-card")) {
            assertEquals("$t is Tier 2", CardTier.NATIVE_HACS, CardRouter.tier(card("""{"type": "$t"}""")))
        }
    }

    @Test
    fun tierThreeIsAnyOtherCardAndGoesToAWebIsland() {
        assertEquals(CardTier.WEB_ISLAND, CardRouter.tier(card("""{"type": "custom:acme-floorplan-card"}""")))
        assertEquals(CardTier.WEB_ISLAND, CardRouter.tier(card("""{"type": "custom:mushroom-unknown-card"}""")))
        assertEquals(CardTier.WEB_ISLAND, CardRouter.tier(card("""{"type": "some-core-card-we-lack"}""")))
    }

    @Test
    fun everyRoutedTypeIsInExactlyOneTier() {
        assertTrue(CardRouter.NATIVE_CORE_TYPES.intersect(CardRouter.NATIVE_HACS_TYPES).isEmpty())
        assertTrue(CardRouter.NATIVE_HACS_TYPES.all { it.startsWith("custom:") })
        assertTrue(CardRouter.NATIVE_CORE_TYPES.none { it.startsWith("custom:") })
    }

    @Test
    fun tierCountsFlattenLayoutContainers() {
        val v = view(
            """{"type": "sections", "sections": [
                 {"type": "grid", "cards": [
                   {"type": "vertical-stack", "cards": [
                     {"type": "tile", "entity": "light.a"},
                     {"type": "custom:acme-floorplan-card"}
                   ]},
                   {"type": "custom:mini-graph-card", "entities": ["sensor.kitchen_temp"]}
                 ]}
               ],
               "cards": [{"type": "entities", "entities": ["sensor.kitchen_temp"]}]}""",
        )
        val counts = CardRouter.tierCounts(v)
        assertEquals(2, counts[CardTier.NATIVE_CORE]) // tile + entities; the stack itself does not count
        assertEquals(1, counts[CardTier.NATIVE_HACS])
        assertEquals(1, counts[CardTier.WEB_ISLAND])
    }

    // ---- Referenced entities ----

    @Test
    fun referencedEntitiesCoverEveryCardKindOfAView() {
        val v = view(
            """{"type": "sections",
               "badges": ["sensor.badge_temp"],
               "sections": [
                 {"type": "grid",
                  "visibility": [{"condition": "numeric_state", "entity": "sensor.section_gate", "above": 1}],
                  "cards": [
                    {"type": "tile", "entity": "light.lounge_lamp"},
                    {"type": "entities", "entities": ["sensor.kitchen_temp", {"entity": "switch.fan"}]},
                    {"type": "conditional",
                     "conditions": [{"condition": "state", "entity": "binary_sensor.front_door", "state": "on"}],
                     "card": {"type": "tile", "entity": "light.hall"}},
                    {"type": "picture", "camera_image": "camera.porch"},
                    {"type": "tile", "entity": "light.with_visibility",
                     "visibility": [{"condition": "or", "conditions": [{"condition": "state", "entity": "person.alex", "state": "home"}]}]}
                  ]}
               ],
               "cards": [{"type": "tile", "entity": "light.masonry_card"}]}""",
        )
        assertEquals(
            setOf(
                "sensor.badge_temp", "sensor.section_gate", "light.lounge_lamp", "sensor.kitchen_temp", "switch.fan",
                "binary_sensor.front_door", "light.hall", "camera.porch", "light.with_visibility", "person.alex",
                "light.masonry_card",
            ),
            CardRouter.referencedEntities(v),
        )
    }

    @Test
    fun referencedEntitiesDeepScanCustomCardsButIgnoreUrlsIconsAndTypes() {
        val v = view(
            """{"type": "panel", "cards": [{
                 "type": "custom:acme-floorplan-card",
                 "title": "Floor plan",
                 "image": "floorplan.png",
                 "icon": "light.not_an_entity_icon",
                 "navigation_path": "lovelace.settings",
                 "url_path": "wall.test",
                 "asset": "island.js",
                 "photo": "cover.jpg",
                 "logo": "mark.svg",
                 "sprite": "frames.png",
                 "link": "http://192.0.2.10:8123/local/page.html",
                 "script": "/local/acme/card.js",
                 "rooms": [
                   {"name": "Lounge", "type": "sensor.type_key_is_skipped", "lights": ["light.lounge_lamp", "light.lounge_strip"], "temp": "sensor.lounge_temp"},
                   {"name": "Kitchen", "nested": {"deeper": {"fan": "fan.kitchen_extractor"}}}
                 ],
                 "sleep": {"score": "sensor.sleep_score", "bed": "binary_sensor.bed_occupied"},
                 "camera": {"entity": "camera.porch"},
                 "not_entities": ["Lounge", "on", "mdi:lamp", "12.5", "Light.Upper", "light.", ".lamp"]
               }]}""",
        )
        assertEquals(
            setOf(
                "light.lounge_lamp", "light.lounge_strip", "sensor.lounge_temp", "fan.kitchen_extractor",
                "sensor.sleep_score", "binary_sensor.bed_occupied", "camera.porch",
            ),
            CardRouter.referencedEntities(v),
        )
    }

    @Test
    fun builtInCardsAreNotDeepScanned() {
        val v = view("""{"cards": [{"type": "tile", "entity": "light.a", "something_else": "light.hidden"}]}""")
        assertEquals(setOf("light.a"), CardRouter.referencedEntities(v))
    }

    @Test
    fun referencedEntitiesReachCustomCardsInsideStacks() {
        val v = view(
            """{"cards": [{"type": "horizontal-stack", "cards": [
                 {"type": "custom:acme-floorplan-card", "rooms": [{"lights": ["light.deep_in_stack"]}]}
               ]}]}""",
        )
        assertEquals(setOf("light.deep_in_stack"), CardRouter.referencedEntities(v))
    }

    // ---- Visibility ----

    @Test
    fun stateConditionMatchesOrExcludesAState() {
        val on = VisibilityCondition.State("light.lounge_lamp", "on", null)
        assertTrue(on.evaluate(states("light.lounge_lamp" to "on")))
        assertFalse(on.evaluate(states("light.lounge_lamp" to "off")))
        assertFalse(on.evaluate(states())) // an unknown entity is not "on"

        val notOff = VisibilityCondition.State("light.lounge_lamp", null, "off")
        assertTrue(notOff.evaluate(states("light.lounge_lamp" to "on")))
        assertFalse(notOff.evaluate(states("light.lounge_lamp" to "off")))
        assertTrue(notOff.evaluate(states())) // missing is "not off"

        // Neither `state` nor `state_not`: nothing to compare, so it shows.
        assertTrue(VisibilityCondition.State("light.lounge_lamp", null, null).evaluate(states()))
    }

    @Test
    fun numericStateConditionIsStrictAndNeedsANumber() {
        val c = VisibilityCondition.NumericState("sensor.kitchen_temp", above = 20.0, below = 30.0)
        assertTrue(c.evaluate(states("sensor.kitchen_temp" to "25")))
        assertTrue(c.evaluate(states("sensor.kitchen_temp" to "20.5")))
        assertFalse(c.evaluate(states("sensor.kitchen_temp" to "20")))
        assertFalse(c.evaluate(states("sensor.kitchen_temp" to "30")))
        assertFalse(c.evaluate(states("sensor.kitchen_temp" to "unavailable")))
        assertFalse(c.evaluate(states()))

        assertTrue(VisibilityCondition.NumericState("sensor.kitchen_temp", above = 20.0, below = null).evaluate(states("sensor.kitchen_temp" to "99")))
        assertTrue(VisibilityCondition.NumericState("sensor.kitchen_temp", above = null, below = 5.0).evaluate(states("sensor.kitchen_temp" to "-3")))
    }

    @Test
    fun screenConditionComparesTheWidthToTheMediaQuery() {
        val tablet = VisibilityCondition.Screen("(min-width: 768px)")
        assertTrue(tablet.evaluate(states(), widthDp = 1024))
        assertTrue(tablet.evaluate(states(), widthDp = 768))
        assertFalse(tablet.evaluate(states(), widthDp = 600))

        val band = VisibilityCondition.Screen("(min-width: 600px) and (max-width: 900px)")
        assertTrue(band.evaluate(states(), widthDp = 700))
        assertFalse(band.evaluate(states(), widthDp = 1000))
        assertFalse(band.evaluate(states(), widthDp = 500))

        assertTrue(VisibilityCondition.Screen("").evaluate(states(), widthDp = 320)) // nothing to restrict
        assertTrue(VisibilityCondition.Screen("(prefers-color-scheme: dark)").evaluate(states(), widthDp = 320)) // unknown queries do not hide
    }

    @Test
    fun andOrNotCombineConditions() {
        val lampOn = VisibilityCondition.State("light.lounge_lamp", "on", null)
        val doorOpen = VisibilityCondition.State("binary_sensor.front_door", "on", null)
        val both = states("light.lounge_lamp" to "on", "binary_sensor.front_door" to "on")
        val lampOnly = states("light.lounge_lamp" to "on", "binary_sensor.front_door" to "off")
        val neither = states("light.lounge_lamp" to "off", "binary_sensor.front_door" to "off")

        val and = VisibilityCondition.And(listOf(lampOn, doorOpen))
        assertTrue(and.evaluate(both))
        assertFalse(and.evaluate(lampOnly))

        val or = VisibilityCondition.Or(listOf(lampOn, doorOpen))
        assertTrue(or.evaluate(lampOnly))
        assertFalse(or.evaluate(neither))

        val not = VisibilityCondition.Not(listOf(lampOn, doorOpen))
        assertTrue(not.evaluate(neither))
        assertFalse(not.evaluate(lampOnly))

        // Nesting.
        val nested = VisibilityCondition.And(listOf(VisibilityCondition.Or(listOf(lampOn, doorOpen)), VisibilityCondition.Not(listOf(VisibilityCondition.Screen("(max-width: 500px)")))))
        assertTrue(nested.evaluate(lampOnly, widthDp = 1024))
        assertFalse(nested.evaluate(lampOnly, widthDp = 400))

        // Empty groups: And is vacuously true, Or false, Not true.
        assertTrue(VisibilityCondition.And(emptyList()).evaluate(neither))
        assertFalse(VisibilityCondition.Or(emptyList()).evaluate(neither))
        assertTrue(VisibilityCondition.Not(emptyList()).evaluate(neither))
    }

    @Test
    fun parseReadsEveryConditionKind() {
        val parsed = VisibilityCondition.parseList(
            JSONArray(
                """[
                  {"condition": "state", "entity": "light.lounge_lamp", "state": "on"},
                  {"condition": "state", "entity": "light.lounge_lamp", "state_not": "off"},
                  {"entity": "light.no_condition_key", "state": "on"},
                  {"condition": "numeric_state", "entity": "sensor.kitchen_temp", "above": 18.5, "below": 26},
                  {"condition": "numeric_state", "entity": "sensor.kitchen_temp", "above": "sensor.not_a_number"},
                  {"condition": "screen", "media_query": "(min-width: 768px)"},
                  {"condition": "and", "conditions": [{"condition": "state", "entity": "light.a", "state": "on"}]},
                  {"condition": "or", "conditions": []},
                  {"condition": "not", "conditions": [{"condition": "screen", "media_query": "(max-width: 400px)"}]},
                  {"condition": "user", "users": ["abc"]},
                  {"condition": "state", "state": "on"},
                  "junk"
                ]""",
            ),
        )
        assertEquals(
            listOf(
                VisibilityCondition.State("light.lounge_lamp", "on", null),
                VisibilityCondition.State("light.lounge_lamp", null, "off"),
                VisibilityCondition.State("light.no_condition_key", "on", null), // `state` is the default condition
                VisibilityCondition.NumericState("sensor.kitchen_temp", 18.5, 26.0),
                VisibilityCondition.NumericState("sensor.kitchen_temp", null, null), // a non-number bound is dropped
                VisibilityCondition.Screen("(min-width: 768px)"),
                VisibilityCondition.And(listOf(VisibilityCondition.State("light.a", "on", null))),
                VisibilityCondition.Or(emptyList()),
                VisibilityCondition.Not(listOf(VisibilityCondition.Screen("(max-width: 400px)"))),
            ),
            parsed,
        )
        // Unsupported conditions (user), an entity-less state and non-objects are skipped, not fatal.
        assertTrue(VisibilityCondition.parseList(null).isEmpty())
        assertNull(VisibilityCondition.parse(JSONObject("""{"condition": "user"}""")))
        assertNotNull(VisibilityCondition.parse(JSONObject("""{"condition": "screen"}""")))
    }

    @Test
    fun conditionsReportTheEntitiesTheyWatch() {
        val c = VisibilityCondition.And(
            listOf(
                VisibilityCondition.State("light.a", "on", null),
                VisibilityCondition.Or(listOf(VisibilityCondition.NumericState("sensor.b", 1.0, null), VisibilityCondition.Screen("(min-width: 1px)"))),
                VisibilityCondition.Not(listOf(VisibilityCondition.State("switch.c", null, "off"))),
            ),
        )
        val out = LinkedHashSet<String>()
        c.referencedEntities(out)
        assertEquals(setOf("light.a", "sensor.b", "switch.c"), out)
    }

    @Test
    fun cardVisibilityComesFromItsConfig() {
        val c = card("""{"type": "tile", "entity": "light.a", "visibility": [{"condition": "state", "entity": "input_boolean.guest_mode", "state": "on"}]}""")
        assertEquals(1, c.visibility.size)
        assertTrue(c.visibility.all { it.evaluate(states("input_boolean.guest_mode" to "on")) })
        assertFalse(c.visibility.all { it.evaluate(states("input_boolean.guest_mode" to "off")) })
        assertTrue(card("""{"type": "tile"}""").visibility.isEmpty())
    }

    // ---- Routes ----

    @Test
    fun parseRouteSplitsDashboardAndView() {
        assertEquals("clarity-ref" to "typical-nocam", LovelaceParser.parseRoute("http://192.0.2.10:8123/clarity-ref/typical-nocam"))
        assertEquals("wall-test" to null, LovelaceParser.parseRoute("http://192.0.2.10:8123/wall-test"))
        assertEquals("wall-test" to null, LovelaceParser.parseRoute("http://192.0.2.10:8123/wall-test/"))
        assertEquals("wall-test" to "living", LovelaceParser.parseRoute("http://192.0.2.10:8123/wall-test/living?kiosk=1#top"))
        assertEquals("wall-test" to "0", LovelaceParser.parseRoute("https://homeassistant.local:8123/wall-test/0"))
    }

    @Test
    fun parseRouteTreatsLovelaceAndHomeAsTheDefaultDashboard() {
        assertEquals(null to "0", LovelaceParser.parseRoute("http://192.0.2.10:8123/lovelace/0"))
        assertEquals(null to "kitchen", LovelaceParser.parseRoute("http://192.0.2.10:8123/home/kitchen"))
        assertEquals(null to null, LovelaceParser.parseRoute("http://192.0.2.10:8123/lovelace"))
    }

    @Test
    fun parseRouteWithNoPathHasNeitherDashboardNorView() {
        assertEquals(null to null, LovelaceParser.parseRoute("http://192.0.2.10:8123"))
        assertEquals(null to null, LovelaceParser.parseRoute("http://192.0.2.10:8123/"))
        assertEquals(null to null, LovelaceParser.parseRoute("not a url"))
        assertEquals(null to null, LovelaceParser.parseRoute(""))
    }

    @Test
    fun dashboardsAreDashboardUrlsButSignInAndSettingsAreNot() {
        for (u in listOf(
            "http://192.0.2.10:8123",
            "http://192.0.2.10:8123/",
            "http://192.0.2.10:8123/lovelace/0",
            "http://192.0.2.10:8123/home/living",
            "http://192.0.2.10:8123/wall-test/living",
            "http://192.0.2.10:8123/clarity-ref",
            "https://homeassistant.local:8123/dashboard-wall/home?kiosk=1",
        )) {
            assertTrue("$u is a dashboard", LovelaceParser.isDashboardUrl(u))
        }
        for (u in listOf(
            "http://192.0.2.10:8123/auth/authorize?client_id=abc&redirect_uri=def",
            "http://192.0.2.10:8123/config/blueprint/import",
            "http://192.0.2.10:8123/config/dashboard",
            "http://192.0.2.10:8123/developer-tools/state",
            "http://192.0.2.10:8123/profile/general",
            "http://192.0.2.10:8123/hassio/dashboard",
            "http://192.0.2.10:8123/hacs/entry",
            "http://192.0.2.10:8123/logbook",
            "http://192.0.2.10:8123/history",
            "http://192.0.2.10:8123/energy",
            "http://192.0.2.10:8123/map",
            "http://192.0.2.10:8123/media-browser/browser",
            "http://192.0.2.10:8123/calendar",
            "http://192.0.2.10:8123/todo",
            "http://192.0.2.10:8123/api/camera_proxy/camera.porch",
            "http://192.0.2.10:8123/local/page.html",
            "http://192.0.2.10:8123/hacsfiles/acme/acme-card.js",
        )) {
            assertFalse("$u is not a dashboard", LovelaceParser.isDashboardUrl(u))
        }
    }
    // ---- v0.6: extended core cards, badges, gauge severity ----

    @Test
    fun extendedCoreCardsRouteNative() {
        for (t in listOf("entity", "light", "alarm-panel", "todo-list", "statistics-graph", "area")) {
            assertTrue("$t is native core", t in CardRouter.NATIVE_CORE_TYPES)
            assertEquals(t, CardTier.NATIVE_CORE, CardRouter.tier(card("""{"type": "$t"}""")))
        }
    }

    @Test
    fun referencedEntitiesScanTheExtendedCoreCards() {
        val v = view(
            """{
              "badges": ["person.alex", {"type": "entity", "entity": "sensor.outside"}],
              "cards": [
                {"type": "entity", "entity": "sensor.power"},
                {"type": "light", "entity": "light.desk"},
                {"type": "alarm-panel", "entity": "alarm_control_panel.home"},
                {"type": "todo-list", "entity": "todo.shopping"},
                {"type": "statistics-graph", "entities": ["sensor.energy", {"entity": "sensor.gas"}]},
                {"type": "statistics-graph", "entity": "sensor.water"},
                {"type": "area", "area": "living_room", "temperature_entity": "sensor.living_t",
                 "humidity_entity": "sensor.living_h", "navigation_path": "/lovelace/living"}
              ]
            }""",
        )
        assertEquals(
            setOf(
                "sensor.power", "light.desk", "alarm_control_panel.home", "todo.shopping",
                "sensor.energy", "sensor.gas", "sensor.water", "sensor.living_t", "sensor.living_h",
                "person.alex", "sensor.outside",
            ),
            CardRouter.referencedEntities(v),
        )
        assertEquals(7, CardRouter.tierCounts(v)[CardTier.NATIVE_CORE])
    }

    @Test
    fun gaugeSeverityPicksTheHighestThresholdAtOrBelowTheValue() {
        val sev = GaugeCardView.parseSeverity(JSONObject("""{"green": 0, "yellow": 50, "red": 80}"""))
        assertEquals(GaugeCardView.SEVERITY_GREEN, GaugeCardView.severityColor(sev, 10.0))
        assertEquals(GaugeCardView.SEVERITY_YELLOW, GaugeCardView.severityColor(sev, 50.0))
        assertEquals(GaugeCardView.SEVERITY_RED, GaugeCardView.severityColor(sev, 95.0))
        assertNull(GaugeCardView.severityColor(sev, -5.0))
        // Reversed thresholds (low is bad) follow the same rule.
        val rev = GaugeCardView.parseSeverity(JSONObject("""{"red": 0, "yellow": 20, "green": 50}"""))
        assertEquals(GaugeCardView.SEVERITY_RED, GaugeCardView.severityColor(rev, 5.0))
        assertEquals(GaugeCardView.SEVERITY_GREEN, GaugeCardView.severityColor(rev, 70.0))
        assertTrue(GaugeCardView.parseSeverity(null).isEmpty())
    }

    @Test
    fun entityCardShowsAttributeOrStateWithUnit() {
        val s = EntityState("sensor.t", "21.5", mapOf("unit_of_measurement" to "°C", "friendly_name" to "Temp", "battery" to 80))
        assertEquals("21.5" to "°C", EntityCardView.valueAndUnit(JSONObject(), s))
        assertEquals("80" to "%", EntityCardView.valueAndUnit(JSONObject("""{"attribute": "battery", "unit": "%"}"""), s))
        assertEquals("–" to "", EntityCardView.valueAndUnit(JSONObject(), null))
    }

    @Test
    fun alarmPanelStatesAndServices() {
        assertEquals(listOf("arm_home", "arm_away"), AlarmPanelCardView.readStates(null))
        assertEquals(listOf("arm_night"), AlarmPanelCardView.readStates(JSONArray("""["arm_night"]""")))
        assertEquals("alarm_arm_away", AlarmPanelCardView.serviceFor("arm_away"))
        assertEquals("alarm_disarm", AlarmPanelCardView.serviceFor("disarm"))
        assertEquals("Armed home", AlarmPanelCardView.stateLabel("armed_home"))
        assertEquals(0xFFF44336.toInt(), AlarmPanelCardView.stateColor("triggered"))
        assertEquals(0xFF4CAF50.toInt(), AlarmPanelCardView.stateColor("disarmed"))
    }

    @Test
    fun todoItemsParseFromTheListResult() {
        val items = TodoListCardView.parseItems(
            JSONObject(
                """{"items": [
                  {"uid": "a1", "summary": "Milk", "status": "needs_action"},
                  {"uid": "b2", "summary": "Eggs", "status": "completed"},
                  {"summary": "No uid"}
                ]}""",
            ),
        )
        assertEquals(
            listOf(TodoItem("a1", "Milk", false), TodoItem("b2", "Eggs", true), TodoItem("No uid", "No uid", false)),
            items,
        )
        assertTrue(TodoListCardView.parseItems(null).isEmpty())
    }

    @Test
    fun statisticsGraphReadsIdsTypesAndPoints() {
        val c = card("""{"type": "statistics-graph", "entities": ["sensor.a", {"entity": "sensor.b"}]}""")
        assertEquals(listOf("sensor.a", "sensor.b"), StatisticsGraphCardView.statisticIds(c))
        assertEquals(listOf("sensor.c"), StatisticsGraphCardView.statisticIds(card("""{"type": "statistics-graph", "entity": "sensor.c"}""")))
        assertEquals(listOf("mean"), StatisticsGraphCardView.readStatTypes(null))
        assertEquals(listOf("max", "min"), StatisticsGraphCardView.readStatTypes(JSONArray("""["max", "bogus", "min"]""")))
        val rows = JSONArray(
            """[
              {"start": 1700000000000, "mean": 20.5, "max": 22},
              {"start": "2023-11-14T23:13:20+00:00", "mean": 21.0},
              {"start": 1700007200000, "mean": null}
            ]""",
        )
        val pts = StatisticsGraphCardView.parsePoints(rows, "mean")
        assertEquals(2, pts.size)
        assertEquals(1700000000000.0 to 20.5, pts[0])
        assertEquals(1700003600000.0, pts[1].first, 0.5)
        assertEquals(1, StatisticsGraphCardView.parsePoints(rows, "max").size)
    }

    @Test
    fun areaCardNamesAndSummaryEntities() {
        assertEquals("Living room", AreaCardView.areaName(card("""{"type": "area", "area": "living_room"}""")))
        assertEquals("Den", AreaCardView.areaName(card("""{"type": "area", "area": "x", "name": "Den"}""")))
        assertEquals(
            listOf("sensor.t", "sensor.h", "light.a"),
            AreaCardView.summaryEntities(JSONObject("""{"temperature_entity": "sensor.t", "humidity_entity": "sensor.h", "entities": ["light.a", "sensor.t"]}""")),
        )
    }

    @Test
    fun badgeTextShowsStateAndOptionallyTheName() {
        val home = EntityState("person.alex", "home", mapOf("friendly_name" to "Alex"))
        assertEquals("Home", VitroView.badgeText(card("""{"type": "entity", "entity": "person.alex"}"""), home))
        assertEquals("Alex · Home", VitroView.badgeText(card("""{"type": "entity", "entity": "person.alex", "show_name": true}"""), home))
        assertEquals("Al · Home", VitroView.badgeText(card("""{"type": "entity", "entity": "person.alex", "name": "Al"}"""), home))
        assertEquals("Alex", VitroView.badgeText(card("""{"type": "entity", "entity": "person.alex", "show_name": true, "show_state": false}"""), home))
    }

    @Test
    fun badgeLinesStackTheNameAboveTheStateOnlyWhenTheNameShows() {
        val temp = EntityState("sensor.outdoor", "7.4", mapOf("friendly_name" to "Outdoor temperature", "unit_of_measurement" to "°C"))
        assertEquals(null to "7.4 °C", VitroView.badgeLines(card("""{"type": "entity", "entity": "sensor.outdoor"}"""), temp))
        assertEquals(
            "Outdoor temperature" to "7.4 °C",
            VitroView.badgeLines(card("""{"type": "entity", "entity": "sensor.outdoor", "show_name": true}"""), temp),
        )
        assertEquals("Out" to "7.4 °C", VitroView.badgeLines(card("""{"type": "entity", "entity": "sensor.outdoor", "name": "Out"}"""), temp))
        assertEquals(
            "Outdoor temperature" to null,
            VitroView.badgeLines(card("""{"type": "entity", "entity": "sensor.outdoor", "show_name": true, "show_state": false}"""), temp),
        )
    }

    @Test
    fun headingBadgesAreSubscribedAndAClockDefaultsToHalfWidth() {
        val v = view(
            """{"type": "sections", "sections": [{"cards": [
                {"type": "heading", "heading": "Studio", "badges": [{"type": "entity", "entity": "sensor.studio_power"}, "light.desk"]},
                {"type": "clock"}
            ]}]}""",
        )
        val refs = CardRouter.referencedEntities(v)
        assertTrue("sensor.studio_power" in refs)
        assertTrue("light.desk" in refs)
        assertTrue(VitroView.isHalfWidth(card("""{"type": "clock"}""")))
        assertFalse(VitroView.isHalfWidth(card("""{"type": "clock", "grid_options": {"columns": 12}}""")))
        assertFalse(VitroView.isHalfWidth(card("""{"type": "tile", "entity": "light.a"}""")))
    }

    @Test
    fun entitiesRowsKeepDividersAndSections() {
        val c = card(
            """{"type": "entities", "entities": [
                 "light.desk",
                 {"type": "divider"},
                 {"type": "section", "label": "Blinds"},
                 {"type": "section"},
                 {"entity": "cover.office", "secondary_info": "last-changed"}
               ]}""",
        )
        val rows = c.entities()
        assertEquals(listOf("entity", "divider", "section", "section", "entity"), rows.map { it.rowType })
        assertEquals("", rows[1].entity)
        assertEquals("Blinds", rows[2].name)
        assertNull(rows[3].name)
        assertTrue(rows[0].isEntity && !rows[1].isEntity && !rows[2].isEntity)
        assertEquals("last-changed", rows[4].secondaryInfo)
        assertEquals(setOf("light.desk", "cover.office"), CardRouter.referencedEntities(view("""{"cards": [${c.raw}]}""")))
    }
}
