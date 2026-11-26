/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.cards.AlarmPanelCardView
import dev.glassworks.engine.vitro.cards.AreaCardView
import dev.glassworks.engine.vitro.cards.CalendarCardView
import dev.glassworks.engine.vitro.cards.EntityCardView
import dev.glassworks.engine.vitro.cards.GaugeCardView
import dev.glassworks.engine.vitro.cards.LogbookCardView
import dev.glassworks.engine.vitro.cards.PictureElementsCardView
import dev.glassworks.engine.vitro.cards.StatisticsGraphCardView
import dev.glassworks.engine.vitro.cards.TodoItem
import dev.glassworks.engine.vitro.cards.TodoListCardView
import dev.glassworks.engine.vitro.cards.WebIslandCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.AreaCardConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.EntityFilterConfig
import dev.glassworks.engine.vitro.model.CardTier
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.VisibilityCondition
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.HaGridSize
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

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
        assertEquals("Lamp", c.name(null))
        assertEquals("T", c.title)
        assertEquals("mdi:lamp", c.icon)
        assertEquals("glass_night", c.theme)
        val bare = card("""{"type": "tile"}""")
        assertNull(bare.entity)
        assertNull(bare.name(null))
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
        for (t in listOf("custom:mushroom-entity-card", "custom:mushroom-template-card", "custom:mushroom-chips-card", "custom:swipe-card")) {
            assertEquals("$t is Tier 2", CardTier.NATIVE_HACS, CardRouter.tier(card("""{"type": "$t"}""")))
        }
        // A fast path that doesn't match its card (the strict compat rule) goes to the island when a Lovelace resource
        // provides its script, and is drawn natively only when none does.
        for (c in listOf("""{"type": "custom:bubble-card", "card_type": "pop-up"}""", """{"type": "custom:mini-graph-card"}""", """{"type": "custom:clock-weather-card"}""")) {
            assertEquals("$c with its script", CardTier.WEB_ISLAND, CardRouter.tier(card(c)))
            assertEquals("$c without one", CardTier.NATIVE_HACS, CardRouter.tier(card(c), dev.glassworks.engine.vitro.compat.IslandScripts.of(emptyList())))
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
                   {"type": "custom:mushroom-entity-card", "entity": "sensor.kitchen_temp"}
                 ]}
               ],
               "cards": [{"type": "entities", "entities": ["sensor.kitchen_temp"]}]}""",
        )
        val counts = CardRouter.tierCounts(v)
        // The tile; the stack itself does not count, and a sections view doesn't build its top-level `cards:`.
        assertEquals(1, counts[CardTier.NATIVE_CORE])
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
    fun moreInfoTargetsOfActionsAreReferenced() {
        // A tap that opens another entity's More Info (HA's handleAction: the action's `entity`): the sheet reads that
        // entity from the store, so the subscription covers it. On the card and on its rows; none means the card's own.
        val v = view(
            """{"cards": [
                 {"type": "tile", "entity": "light.desk", "tap_action": {"action": "more-info", "entity": "light.hall"},
                  "hold_action": {"action": "more-info"}},
                 {"type": "entities", "entities": [{"entity": "switch.kettle", "double_tap_action": {"action": "more-info", "entity": "sensor.kettle_power"}}]}
               ]}""",
        )
        assertEquals(setOf("light.desk", "light.hall", "switch.kettle", "sensor.kettle_power"), CardRouter.referencedEntities(v))
    }

    @Test
    fun actionTargetsAreTrimmedAndFoundInNestedRows() {
        // ActionConfig.parse trims the action's `entity`, so the subscription does too; a conditional row's `row` and a
        // buttons row's buttons carry their own actions.
        val v = view(
            """{"cards": [
                 {"type": "tile", "entity": "light.desk", "tap_action": {"action": "more-info", "entity": " light.hall "}},
                 {"type": "entities", "entities": [
                   {"type": "conditional", "conditions": [{"entity": "sun.sun", "state": "below_horizon"}],
                    "row": {"entity": "switch.porch", "hold_action": {"action": "more-info", "entity": "sensor.porch_power"}}},
                   {"type": "buttons", "entities": [{"entity": "scene.movie", "tap_action": {"action": "more-info", "entity": "media_player.tv"}}]}
                 ]}
               ]}""",
        )
        val refs = CardRouter.referencedEntities(v)
        assertTrue(refs.toString(), "light.hall" in refs)
        assertFalse(refs.toString(), " light.hall " in refs)
        assertTrue(refs.toString(), "sensor.porch_power" in refs)
        assertTrue(refs.toString(), "media_player.tv" in refs)
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
        assertTrue(VisibilityCondition.State("light.lounge_lamp").evaluate(states()))
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
    fun aTimeConditionReadsTheInstantItIsGiven() {
        // A replay's captured 08:30 decides, not the wall clock (the card context's `now`).
        val c = VisibilityCondition.Time(after = 8 * 3600, before = 9 * 3600, weekdays = null)
        val at = { h: Int, m: Int -> java.util.Calendar.getInstance().apply { set(2026, 0, 14, h, m, 0) }.timeInMillis }
        assertTrue(VisibilityCondition.evaluateAt(c, at(8, 30), states(), 1024, null))
        assertFalse(VisibilityCondition.evaluateAt(c, at(10, 0), states(), 1024, null))
    }

    @Test
    fun numericStateCoercesAsHasNumberDoes() {
        // HA's `Number(value)`: true is 1, an empty string and null are 0; a missing attribute isn't a number.
        val fan = EntityState("fan.den", "on", mapOf("oscillating" to true, "preset" to "", "speed" to null))
        val lookup = { id: String -> if (id == "fan.den") fan else null }
        fun above0(attr: String) = VisibilityCondition.NumericState("fan.den", above = 0.0, below = null, attribute = attr).evaluate(lookup)
        fun belowHalf(attr: String) = VisibilityCondition.NumericState("fan.den", above = null, below = 0.5, attribute = attr).evaluate(lookup)
        assertTrue(above0("oscillating"))
        assertTrue(belowHalf("preset"))
        assertTrue(belowHalf("speed"))
        assertFalse(belowHalf("missing"))
        assertEquals(16.0, VisibilityCondition.jsToNumber("0x10")!!, 0.0)
        assertNull(VisibilityCondition.jsToNumber("1f"))
        assertNull(VisibilityCondition.jsToNumber("NaN"))
        assertEquals(1500.0, VisibilityCondition.jsToNumber(" 1.5e3 ")!!, 0.0)
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

        assertTrue(VisibilityCondition.Screen("screen and (max-width: 767.5px)").evaluate(states(), widthDp = 600))
        // HA's `media_query ? matchMedia(…).matches : false`: an empty query hides.
        assertFalse(VisibilityCondition.Screen("").evaluate(states(), widthDp = 320))
        // A query Vitro can't evaluate whole (other features, a list) hides the card rather than ignoring the rest.
        assertFalse(VisibilityCondition.Screen("(prefers-color-scheme: dark)").evaluate(states(), widthDp = 320))
        assertFalse(VisibilityCondition.Screen("(min-width: 100px) and (orientation: portrait)").evaluate(states(), widthDp = 1024))
        assertFalse(VisibilityCondition.Screen("(max-width: 100px), (min-width: 200px)").evaluate(states(), widthDp = 1024))
        // ...also under `not`: not knowing is never permission to show.
        assertFalse(VisibilityCondition.Not(listOf(VisibilityCondition.Screen("(orientation: portrait)"))).evaluate(states(), widthDp = 1024))
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

        // HA negates the conjunction: `not [a, b]` shows the card unless both hold.
        val not = VisibilityCondition.Not(listOf(lampOn, doorOpen))
        assertTrue(not.evaluate(neither))
        assertTrue(not.evaluate(lampOnly))
        assertFalse(not.evaluate(both))

        // Nesting.
        val nested = VisibilityCondition.And(listOf(VisibilityCondition.Or(listOf(lampOn, doorOpen)), VisibilityCondition.Not(listOf(VisibilityCondition.Screen("(max-width: 500px)")))))
        assertTrue(nested.evaluate(lampOnly, widthDp = 1024))
        assertFalse(nested.evaluate(lampOnly, widthDp = 400))

        // Empty groups: And is vacuously true, Or false, Not the negation of an empty And.
        assertTrue(VisibilityCondition.And(emptyList()).evaluate(neither))
        assertFalse(VisibilityCondition.Or(emptyList()).evaluate(neither))
        assertFalse(VisibilityCondition.Not(emptyList()).evaluate(neither))
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
                // An entity id bound reads that entity's state when the condition is evaluated.
                VisibilityCondition.NumericState("sensor.kitchen_temp", null, null, aboveEntity = "sensor.not_a_number"),
                VisibilityCondition.Screen("(min-width: 768px)"),
                VisibilityCondition.And(listOf(VisibilityCondition.State("light.a", "on", null))),
                VisibilityCondition.Or(emptyList()),
                VisibilityCondition.Not(listOf(VisibilityCondition.Screen("(max-width: 400px)"))),
                VisibilityCondition.User(listOf("abc")),
                VisibilityCondition.Unknown("state"), // no entity: kept, and it hides the card
                VisibilityCondition.Unknown("junk"), // not an object
            ),
            parsed,
        )
        assertTrue(VisibilityCondition.parseList(null).isEmpty())
        assertEquals(VisibilityCondition.Unknown("user"), VisibilityCondition.parse(JSONObject("""{"condition": "user"}""")))
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
            // A dot in a view's path isn't a file.
            "http://192.0.2.10:8123/wall-test/v1.2",
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
            // A page some other server hands out, such as Clarity's SurfaceFlinger check: the fallback draws it.
            "http://127.0.0.1:8090/clarity/page/sfcheck.html",
            "http://192.0.2.10:8123/wall-test/plan.HTM",
            "http://192.0.2.10:8123/wall-test/report.json",
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
    fun entityFilterDrawsItsInnerCardWithTheFiltersEntities() {
        val outer = card("""{"type": "entity-filter", "entities": ["light.a", "light.b"], "state_filter": ["off"],
            "card": {"title": "Lights off", "show_header_toggle": false}}""")
        val inner = EntityFilterConfig.innerCard(outer)
        assertEquals("entities", inner.type)
        assertEquals("Lights off", inner.title)
        assertEquals(listOf("light.a", "light.b"), inner.entities().map { it.entity })
        assertEquals("off", inner.raw.getJSONArray("state_filter").getString(0))
        assertFalse(inner.raw.optBoolean("show_header_toggle", true))
        // With no inner card: a plain entities card.
        assertEquals("entities", EntityFilterConfig.innerCard(card("""{"type": "entity-filter", "entities": ["light.a"]}""")).type)
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
        // `states: []` is kept: HA shows no arm buttons then.
        assertEquals(emptyList<String>(), AlarmPanelCardView.readStates(JSONArray("[]")))
        assertEquals("alarm_arm_away", AlarmPanelCardView.serviceFor("arm_away"))
        assertEquals("alarm_disarm", AlarmPanelCardView.serviceFor("disarm"))
        assertEquals("Armed home", AlarmPanelCardView.stateLabel("armed_home"))
        assertEquals(0xFFF44336.toInt(), AlarmPanelCardView.stateColor("triggered"))
        // HA: armed states are green, disarmed falls back to --state-inactive-color.
        assertEquals(0xFF4CAF50.toInt(), AlarmPanelCardView.stateColor("armed_night"))
        assertEquals(0xFF9E9E9E.toInt(), AlarmPanelCardView.stateColor("disarmed"))
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
    fun statisticsGraphReadsIdsAndTypes() {
        val c = card("""{"type": "statistics-graph", "entities": ["sensor.a", {"entity": "sensor.b"}]}""")
        assertEquals(listOf("sensor.a", "sensor.b"), StatisticsGraphCardView.statisticIds(c))
        assertEquals(listOf("sensor.c"), StatisticsGraphCardView.statisticIds(card("""{"type": "statistics-graph", "entity": "sensor.c"}""")))
        // HA's default asks for every type; the chart draws the ones the statistic has.
        assertEquals(listOf("change", "state", "sum", "min", "max", "mean"), StatisticsGraphCardView.readStatTypes(null))
        assertEquals(listOf("max", "min"), StatisticsGraphCardView.readStatTypes(JSONArray("""["max", "bogus", "min"]""")))
        assertEquals(listOf("mean"), StatisticsGraphCardView.readStatTypes("mean"))
        assertEquals(listOf("min", "change", "mean", "max"), StatisticsGraphCardView.sortBandTypes(listOf("change", "min", "max", "mean")))
    }

    @Test
    fun areaCardNamesDisplayTypesAndSensorFormat() {
        assertEquals("Living room", AreaCardView.areaName(card("""{"type": "area", "area": "living_room"}""")))
        assertEquals("Den", AreaCardView.areaName(card("""{"type": "area", "area": "x", "name": "Den"}""")))
        // HA's setConfig: display_type, else camera for the old show_camera, else picture.
        assertEquals("picture", AreaCardConfig.displayType(JSONObject("""{"area": "x"}""")))
        assertEquals("camera", AreaCardConfig.displayType(JSONObject("""{"area": "x", "show_camera": true}""")))
        assertEquals("compact", AreaCardConfig.displayType(JSONObject("""{"area": "x", "display_type": "compact", "show_camera": true}""")))
        // Only sensors, alerts or a camera need the area's entities (and so every entity of the view).
        assertFalse(AreaCardConfig.readsAreaEntities(JSONObject("""{"area": "x"}""")))
        assertTrue(AreaCardConfig.readsAreaEntities(JSONObject("""{"area": "x", "sensor_classes": ["temperature"]}""")))
        assertTrue(AreaCardConfig.readsAreaEntities(JSONObject("""{"area": "x", "alert_classes": ["moisture"]}""")))
        assertFalse(CardRouter.followsAllEntities(view("""{"cards": [{"type": "area", "area": "x"}]}""")))
        assertTrue(CardRouter.followsAllEntities(view("""{"cards": [{"type": "area", "area": "x", "show_camera": true}]}""")))
        // formatNumberWithUnit (en, one decimal at most): no space before % or °.
        assertEquals("21.5 °C", AreaCardView.formatWithUnit(21.46, "°C"))
        assertEquals("0.3 kWh", AreaCardView.formatWithUnit(0.25, "kWh"))
        assertEquals("48%", AreaCardView.formatWithUnit(48.0, "%"))
        assertEquals("1,250 W", AreaCardView.formatWithUnit(1250.0, "W"))
        assertEquals(20.5, AreaCardView.median(listOf(22.0, 19.0, 21.0, 20.0)), 0.0)
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
        // Icon only: neither line.
        assertEquals(
            null to null,
            VitroView.badgeLines(card("""{"type": "entity", "entity": "sensor.outdoor", "show_icon": true, "show_name": false, "show_state": false}"""), temp),
        )
    }

    @Test
    fun headingBadgesAreSubscribedAndCardsTakeHaGridSizes() {
        val v = view(
            """{"type": "sections", "sections": [{"cards": [
                {"type": "heading", "heading": "Studio", "badges": [{"type": "entity", "entity": "sensor.studio_power"}, "light.desk"]},
                {"type": "clock"}
            ]}]}""",
        )
        val refs = CardRouter.referencedEntities(v)
        assertTrue("sensor.studio_power" in refs)
        assertTrue("light.desk" in refs)
        // HA's own grid sizes: a clock is 6 of 12 columns (2 rows when large), a tile 6 x 1, markdown full width.
        assertEquals(HaGridSize(6, 1), HaGridSize.of(card("""{"type": "clock"}""")))
        assertEquals(HaGridSize(6, 2), HaGridSize.of(card("""{"type": "clock", "clock_size": "large"}""")))
        assertEquals(HaGridSize(12, 2), HaGridSize.of(card("""{"type": "clock", "clock_size": "large", "grid_options": {"columns": 12}}""")))
        assertEquals(HaGridSize(6, 1), HaGridSize.of(card("""{"type": "tile", "entity": "light.a"}""")))
        assertEquals(HaGridSize(12, null), HaGridSize.of(card("""{"type": "markdown", "content": "x"}""")))
        assertEquals(HaGridSize(null, null), HaGridSize.of(card("""{"type": "markdown", "grid_options": {"columns": "full"}}""")))
        assertEquals(HaGridSize(9, 3), HaGridSize.of(card("""{"type": "markdown", "layout_options": {"grid_columns": 3, "grid_rows": 3}}""")))
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

    // ---- v1.0: picture-elements, humidifier, calendar, logbook, hass.user ----

    @Test
    fun finalCoreCardsRouteNative() {
        for (t in listOf("picture-elements", "humidifier", "calendar", "logbook")) {
            assertTrue("$t is native core", t in CardRouter.NATIVE_CORE_TYPES)
            assertEquals(t, CardTier.NATIVE_CORE, CardRouter.tier(card("""{"type": "$t"}""")))
        }
    }

    @Test
    fun referencedEntitiesScanPictureElements() {
        val v = view(
            """{"cards": [{
              "type": "picture-elements", "image": "/local/floorplan.png", "camera_image": "camera.porch",
              "elements": [
                {"type": "state-icon", "entity": "light.kitchen", "style": {"left": "35%", "top": "60%"}},
                {"type": "state-label", "entity": "sensor.kitchen_t", "prefix": "T ", "style": {"left": "40%", "top": "20%"}},
                {"type": "service-button", "title": "Party", "service": "scene.turn_on", "style": {"left": "50%", "top": "90%"}},
                {"type": "conditional",
                 "conditions": [{"entity": "input_boolean.guest", "state": "on"}],
                 "elements": [{"type": "state-badge", "entity": "binary_sensor.door"}]}
              ]
            }]}""",
        )
        assertEquals(
            setOf("light.kitchen", "sensor.kitchen_t", "input_boolean.guest", "binary_sensor.door", "camera.porch"),
            CardRouter.referencedEntities(v),
        )
    }

    @Test
    fun pictureElementsParsePercentagesAspectRatioAndLabels() {
        assertEquals(0.35f, PictureElementsCardView.parsePercent("35%"), 1e-6f)
        assertEquals(0.125f, PictureElementsCardView.parsePercent(" 12.5 % "), 1e-6f)
        assertEquals(0f, PictureElementsCardView.parsePercent("0%"), 1e-6f)
        for (bad in listOf(null, "", "35", "120px", "abc%", "150%", "-5%")) {
            assertEquals("$bad", 0.5f, PictureElementsCardView.parsePercent(bad), 1e-6f)
        }
        assertEquals(9f / 16f, PictureElementsCardView.parseAspectRatio("16:9")!!, 1e-6f)
        assertEquals(0.75f, PictureElementsCardView.parseAspectRatio("4x3")!!, 1e-6f)
        assertEquals(0.56f, PictureElementsCardView.parseAspectRatio("56%")!!, 1e-6f)
        assertEquals(0.5f, PictureElementsCardView.parseAspectRatio("2")!!, 1e-6f)
        assertNull(PictureElementsCardView.parseAspectRatio(""))
        assertNull(PictureElementsCardView.parseAspectRatio("wide"))

        val els = PictureElementsCardView.parseElements(
            JSONArray("""[{"type": "state-label", "entity": "sensor.t", "prefix": "~", "suffix": "!",
                           "style": {"left": "35%", "top": "60%"}}, {"type": "icon", "icon": "mdi:home"}, 7]"""),
        )
        assertEquals(2, els.size)
        assertEquals(0.35f, els[0].xFrac, 1e-6f)
        assertEquals(0.6f, els[0].yFrac, 1e-6f)
        assertEquals(0.5f, els[1].xFrac, 1e-6f)
        assertEquals("~…!", PictureElementsCardView.labelText(els[0], null))
        assertEquals("~21 °C!", PictureElementsCardView.labelText(els[0], EntityState("sensor.t", "21", mapOf("unit_of_measurement" to "°C"))))
    }

    @Test
    fun calendarReadsIdsEventsAndFormatsRows() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(
            listOf("calendar.home", "calendar.work"),
            CalendarCardView.calendarIds(card("""{"type": "calendar", "entities": ["calendar.home", {"entity": "calendar.work"}, "sensor.x"]}""")),
        )
        assertEquals(listOf("calendar.solo"), CalendarCardView.calendarIds(card("""{"type": "calendar", "entity": "calendar.solo"}""")))

        val events = CalendarCardView.parseEvents(
            JSONArray(
                """[
                  {"summary": "Standup", "start": {"dateTime": "2026-11-24T10:00:00+00:00"}, "end": {"dateTime": "2026-11-24T11:00:00+00:00"}},
                  {"summary": "Holiday", "start": {"date": "2026-11-23"}, "end": {"date": "2026-11-24"}},
                  {"start": "2026-11-25T08:30:00Z", "end": "2026-11-25T09:00:00Z"},
                  {"summary": "Broken", "start": {}}
                ]""",
            ),
            "calendar.home",
            utc,
        )
        assertEquals(listOf("Standup", "Holiday", "Untitled"), events.map { it.summary })
        assertTrue(events[1].allDay && !events[0].allDay)
        assertTrue(CalendarCardView.parseEvents(null, "calendar.home").isEmpty())

        // The ranges HA asks for: the month's 6 weeks from the Sunday before the 1st; the list's 7 days.
        val la = TimeZone.getTimeZone("America/Los_Angeles")
        val now = 1_795_543_680_000L // 2026-11-24 10:08 in Los Angeles
        val (ms, me) = CalendarCardView.range(CalendarCardView.MONTH, now, la)
        assertEquals("2026-11-01T07:00:00.000Z", LogbookCardView.isoMillis(ms))
        assertEquals("2026-12-13T08:00:00.000Z", LogbookCardView.isoMillis(me))
        val (ls, le) = CalendarCardView.range(CalendarCardView.LIST, now, la)
        assertEquals("2026-11-24T08:00:00.000Z", LogbookCardView.isoMillis(ls))
        assertEquals("2026-12-01T08:00:00.000Z", LogbookCardView.isoMillis(le))
        assertEquals("November 2026", CalendarCardView.title(CalendarCardView.MONTH, now, la))
        assertEquals("Nov 24 – 30, 2026", CalendarCardView.title(CalendarCardView.LIST, now, la))
        assertEquals("Nov 25 – Dec 1, 2026", CalendarCardView.title(CalendarCardView.LIST, now + 86_400_000L, la))
        assertEquals("Dec 29, 2026 – Jan 4, 2027", CalendarCardView.title(CalendarCardView.LIST, now + 35 * 86_400_000L, la))
        assertEquals("November 24, 2026", CalendarCardView.title(CalendarCardView.DAY, now, la))
    }

    @Test
    fun logbookEntriesNewestFirstWithTheirCauseAndHasHeadersAndTimes() {
        val rows = LogbookCardView.parseEntries(
            JSONArray(
                """[
                  {"when": 1000.5, "entity_id": "light.desk", "state": "on", "context_domain": "script", "context_event_type": "call_service"},
                  {"when": "1970-01-01T00:33:20+00:00", "entity_id": "light.desk", "state": "off", "name": "Desk"},
                  {"when": 1500, "entity_id": "automation.wake", "message": "triggered by time", "domain": "automation"},
                  {"when": 1600, "message": "no entity"}
                ]""",
            ),
        )
        assertEquals(listOf(2000.0, 1600.0, 1500.0, 1000.5), rows.map { it.whenSec })
        assertEquals("Desk", rows[0].name)
        assertNull(rows[1].entityId)
        assertEquals("triggered by time", rows[2].message)
        assertEquals("script", rows[3].contextDomain)
        assertEquals("call_service", rows[3].contextEventType)
        // HA's stream request: from hours_to_show ago to a year ahead, times as toISOString writes them.
        val req = LogbookCardView.streamRequest(listOf("light.desk"), 24, 86_400_000L * 2)
        assertEquals("1970-01-02T00:00:00.000Z", req.getString("start_time"))
        assertEquals("1971-01-03T00:00:00.000Z", req.getString("end_time"))
        // formatTimeWithSeconds and the day headers, in English.
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val now = 1_795_543_680_000L // 2026-11-24 14:08 UTC
        assertEquals("Today · November 24, 2026", LogbookCardView.dateHeader(now - 3_600_000L, now, utc))
        assertEquals("Yesterday · November 23, 2026", LogbookCardView.dateHeader(now - 86_400_000L, now, utc))
        assertEquals("November 22, 2026", LogbookCardView.dateHeader(now - 2 * 86_400_000L, now, utc))
    }

    @Test
    fun islandUserIsTheSignedInUserOrThePanel() {
        // No user yet: the island keeps its anonymous `{ id: 'vitro', name: 'Panel' }` default.
        assertNull(WebIslandCardView.islandUser(JSONObject()))

        val real = WebIslandCardView.islandUser(
            JSONObject("""{"id": "abc123", "name": "Alex", "is_admin": true, "is_owner": false, "credentials": [{"auth_provider_type": "homeassistant"}]}"""),
        )!!
        assertEquals("abc123", real.getString("id"))
        assertEquals("Alex", real.getString("name"))
        assertTrue(real.getBoolean("is_admin"))
        assertFalse(real.has("credentials"))
    }

    // ---- Visibility: fail closed, lists, user, time ----

    @Test
    fun unknownConditionsHideTheCard() {
        val anyState = states("sun.sun" to "above_horizon", "light.a" to "on")
        for (json in listOf(
            """{"condition": "sun", "after": "sunset"}""",
            """{"condition": "template", "value_template": "{{ true }}"}""",
            """{"condition": "made_up_by_a_hacs_card"}""",
            """{"condition": "state", "state": "on"}""", // malformed: no entity
            """{"condition": "state", "entity": "light.a"}""", // malformed: nothing to compare
            """{"condition": "numeric_state", "above": 3}""", // malformed: no entity
            """{"condition": "time", "after": "25:99"}""", // malformed time
            """{"condition": "time", "weekdays": ["mon", "funday"]}""", // malformed weekday
        )) {
            val c = VisibilityCondition.parse(JSONObject(json))
            assertTrue("$json should be Unknown, was $c", c is VisibilityCondition.Unknown)
            assertFalse("$json must fail closed", c.evaluate(anyState, userId = "abc", nowSeconds = 36_000, weekday = java.util.Calendar.MONDAY))
        }

        val lampOn = VisibilityCondition.State("light.a", "on", null)
        val sun = VisibilityCondition.Unknown("sun")
        assertFalse(VisibilityCondition.And(listOf(lampOn, sun)).evaluate(anyState))
        // Inside `or` it can't make a card visible on its own, but a known branch that holds still shows it.
        assertFalse(VisibilityCondition.Or(listOf(sun, VisibilityCondition.Unknown("template"))).evaluate(anyState))
        assertTrue(VisibilityCondition.Or(listOf(sun, lampOn)).evaluate(anyState))

        // A card config with one unknown condition among known ones is hidden.
        val camera = card(
            """{"type": "picture-entity", "entity": "camera.porch",
                "visibility": [{"condition": "state", "entity": "light.a", "state": "on"}, {"condition": "sun", "before": "sunrise"}]}""",
        )
        assertEquals(2, camera.visibility.size)
        assertFalse(camera.visibility.all { it.evaluate(anyState) })
        val parsedOr = VisibilityCondition.parse(JSONObject("""{"condition": "or", "conditions": [{"condition": "sun"}, {"condition": "template"}]}"""))
        assertFalse(parsedOr.evaluate(anyState))
        // Negating a condition Vitro can't evaluate doesn't show the card either.
        assertFalse(VisibilityCondition.Not(listOf(sun)).evaluate(anyState))
        assertFalse(VisibilityCondition.Not(listOf(lampOn, sun)).evaluate(anyState))
        assertTrue(VisibilityCondition.Not(listOf(VisibilityCondition.State("light.a", "off", null), sun)).evaluate(anyState))
    }

    @Test
    fun conditionsReadHaCoreEntityIdAndEntityBounds() {
        val byEntityId = VisibilityCondition.parse(JSONObject("""{"condition": "state", "entity_id": "light.desk", "state": "on"}"""))
        assertEquals(VisibilityCondition.State("light.desk", "on", null), byEntityId)
        assertTrue(byEntityId.evaluate(states("light.desk" to "on")))

        val bounded = VisibilityCondition.parse(
            JSONObject("""{"condition": "numeric_state", "entity_id": "sensor.room", "above": "input_number.minimum", "below": "30"}"""),
        )
        assertEquals(VisibilityCondition.NumericState("sensor.room", null, 30.0, aboveEntity = "input_number.minimum"), bounded)
        assertFalse(bounded.evaluate(states("sensor.room" to "19", "input_number.minimum" to "21")))
        assertTrue(bounded.evaluate(states("sensor.room" to "22", "input_number.minimum" to "21")))
        // A bound entity with no number is ignored, as HA does with NaN.
        assertTrue(bounded.evaluate(states("sensor.room" to "19", "input_number.minimum" to "unavailable")))
        val watched = LinkedHashSet<String>()
        bounded.referencedEntities(watched)
        assertEquals(setOf("sensor.room", "input_number.minimum"), watched)
    }

    @Test
    fun theNextTimeBoundIsJustAfterIt() {
        val c = VisibilityCondition.parse(JSONObject("""{"condition": "time", "after": "12:00:01", "before": "12:00:05"}"""))
        val noon = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 12); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(1_250L, VisibilityCondition.msUntilNextTimeBound(listOf(c), noon))
        assertEquals(2_250L, VisibilityCondition.msUntilNextTimeBound(listOf(c), noon + 3_000))
        assertEquals(null, VisibilityCondition.msUntilNextTimeBound(listOf(VisibilityCondition.User(listOf("u")))))
    }

    @Test
    fun timeConditionsKeepSeconds() {
        val c = VisibilityCondition.parse(JSONObject("""{"condition": "time", "after": "08:00:30"}"""))
        assertFalse(c.evaluate(states(), nowSeconds = 8 * 3_600 + 10))
        assertTrue(c.evaluate(states(), nowSeconds = 8 * 3_600 + 30))
        assertTrue(VisibilityCondition.parse(JSONObject("""{"condition": "time", "after": "08:00:60"}""")) is VisibilityCondition.Unknown)
    }

    @Test
    fun stateAndStateNotTakeAValueOrAList() {
        val parsed = VisibilityCondition.parseList(
            JSONArray(
                """[
                  {"condition": "state", "entity": "media_player.lounge", "state": ["playing", "paused"]},
                  {"condition": "state", "entity": "media_player.lounge", "state_not": ["off", "unavailable"]},
                  {"condition": "state", "entity": "media_player.lounge", "state": "playing"},
                  {"condition": "state", "entity": "media_player.lounge", "state_not": "off"}
                ]""",
            ),
        )
        val (anyOf, noneOf, one, notOne) = parsed
        assertEquals(VisibilityCondition.State("media_player.lounge", states = listOf("playing", "paused")), anyOf)
        assertEquals(VisibilityCondition.State("media_player.lounge", statesNot = listOf("off", "unavailable")), noneOf)
        assertEquals(VisibilityCondition.State("media_player.lounge", "playing", null), one)
        assertEquals(VisibilityCondition.State("media_player.lounge", null, "off"), notOne)

        fun at(s: String) = states("media_player.lounge" to s)
        assertTrue(anyOf.evaluate(at("playing")))
        assertTrue(anyOf.evaluate(at("paused")))
        assertFalse(anyOf.evaluate(at("idle")))
        assertFalse(anyOf.evaluate(states()))

        assertTrue(noneOf.evaluate(at("playing")))
        assertFalse(noneOf.evaluate(at("off")))
        assertFalse(noneOf.evaluate(at("unavailable")))
        assertTrue(noneOf.evaluate(states())) // missing reads as "unknown" (as in HA), which isn't excluded

        assertTrue(one.evaluate(at("playing")))
        assertFalse(one.evaluate(at("paused")))
        assertTrue(notOne.evaluate(at("idle")))
        assertFalse(notOne.evaluate(at("off")))

        val unavailable = VisibilityCondition.parse(JSONObject("""{"entity": "sensor.gone", "state": "unavailable"}"""))
        assertFalse(unavailable.evaluate(states())) // a deleted entity is "unknown", not "unavailable"
        assertTrue(unavailable.evaluate(states("sensor.gone" to "unavailable")))
        val unknown = VisibilityCondition.parse(JSONObject("""{"entity": "sensor.gone", "state": "unknown"}"""))
        assertTrue(unknown.evaluate(states()))
    }

    @Test
    fun conditionsReadTheConfiguredAttribute() {
        val lamp = EntityState("light.desk", "on", mapOf("brightness" to 150, "effect" to "rainbow"))
        val look: (String) -> EntityState? = { if (it == "light.desk") lamp else null }
        val bright = VisibilityCondition.parse(JSONObject("""{"condition": "numeric_state", "entity": "light.desk", "attribute": "brightness", "above": 100}"""))
        assertTrue(bright.evaluate(look))
        val dim = VisibilityCondition.parse(JSONObject("""{"condition": "numeric_state", "entity": "light.desk", "attribute": "brightness", "below": 100}"""))
        assertFalse(dim.evaluate(look))
        val effect = VisibilityCondition.parse(JSONObject("""{"condition": "state", "entity": "light.desk", "attribute": "effect", "state": "rainbow"}"""))
        assertTrue(effect.evaluate(look))
        val other = VisibilityCondition.parse(JSONObject("""{"condition": "state", "entity": "light.desk", "attribute": "effect", "state": "on"}"""))
        assertFalse(other.evaluate(look)) // the state is "on", the attribute isn't
        val number = VisibilityCondition.parse(JSONObject("""{"condition": "state", "entity": "light.desk", "attribute": "brightness", "state": "150"}"""))
        assertTrue(number.evaluate(look)) // compared as JavaScript's String(150)
        val missing = VisibilityCondition.parse(JSONObject("""{"condition": "state", "entity": "light.desk", "attribute": "nope", "state": "unknown"}"""))
        assertTrue(missing.evaluate(look))
        // A list compares as JavaScript's String() writes it: "off,heat".
        val climate = EntityState("climate.den", "heat", mapOf("hvac_modes" to listOf("off", "heat", null)))
        val modes = VisibilityCondition.parse(JSONObject("""{"condition": "state", "entity": "climate.den", "attribute": "hvac_modes", "state": "off,heat,"}"""))
        assertTrue(modes.evaluate({ if (it == "climate.den") climate else null }))
    }

    @Test
    fun numbersCompareAsJavaScriptWritesThem() {
        for ((v, js) in listOf(150.0 to "150", 0.0001 to "0.0001", 0.000001 to "0.000001", 1e-7 to "1e-7", 1.5 to "1.5",
            -2.25 to "-2.25", 1e21 to "1e+21", 1e20 to "100000000000000000000", 123456.789 to "123456.789", 1.5e-10 to "1.5e-10")) {
            assertEquals("$v", js, VisibilityCondition.jsNumber(v))
        }
        val sensor = EntityState("sensor.dose", "on", mapOf("level" to 0.0001))
        val c = VisibilityCondition.parse(JSONObject("""{"condition": "state", "entity": "sensor.dose", "attribute": "level", "state": "0.0001"}"""))
        assertTrue(c.evaluate({ if (it == "sensor.dose") sensor else null }))
    }

    @Test
    fun stateOperandsThatNameAnEntityMatchItsState() {
        val c = VisibilityCondition.parse(JSONObject("""{"condition": "state", "entity": "sensor.current", "state": "input_select.expected"}"""))
        assertTrue(c.evaluate(states("sensor.current" to "eco", "input_select.expected" to "eco")))
        assertFalse(c.evaluate(states("sensor.current" to "eco", "input_select.expected" to "comfort")))
        val not = VisibilityCondition.parse(JSONObject("""{"condition": "state", "entity": "sensor.current", "state_not": ["off", "input_select.expected"]}"""))
        assertFalse(not.evaluate(states("sensor.current" to "eco", "input_select.expected" to "eco")))
        assertTrue(not.evaluate(states("sensor.current" to "eco", "input_select.expected" to "away")))
        // A literal still matches as itself.
        assertTrue(c.evaluate(states("sensor.current" to "input_select.expected")))
        val refs = LinkedHashSet<String>().also { not.referencedEntities(it) }
        assertEquals(setOf("sensor.current", "input_select.expected"), refs)
    }

    @Test
    fun aGroupWithOneConditionObjectEvaluatesIt() {
        val one = VisibilityCondition.parse(JSONObject("""{"condition": "and", "conditions": {"entity": "light.a", "state": "on"}}"""))
        assertEquals(VisibilityCondition.And(listOf(VisibilityCondition.State("light.a", "on", null))), one)
        assertFalse(one.evaluate(states("light.a" to "off")))
        assertTrue(one.evaluate(states("light.a" to "on")))
        val unreadable = VisibilityCondition.parse(JSONObject("""{"condition": "or", "conditions": "light.a"}"""))
        assertFalse(unreadable.evaluate(states("light.a" to "on")))
        assertEquals(VisibilityCondition.And(emptyList()), VisibilityCondition.parse(JSONObject("""{"condition": "and", "conditions": []}""")))
    }

    @Test
    fun userConditionMatchesTheSignedInUser() {
        val c = VisibilityCondition.parse(JSONObject("""{"condition": "user", "users": ["u-alex", "u-sam"]}"""))
        assertEquals(VisibilityCondition.User(listOf("u-alex", "u-sam")), c)
        assertTrue(c.evaluate(states(), userId = "u-alex"))
        assertTrue(c.evaluate(states(), userId = "u-sam"))
        assertFalse(c.evaluate(states(), userId = "u-guest"))
        assertFalse(c.evaluate(states(), userId = null)) // user not known yet: hidden
        assertFalse(c.evaluate(states())) // older callers pass no user
        assertTrue(c.dependsOnUser())
        assertTrue(VisibilityCondition.Not(listOf(c)).dependsOnUser())
        assertFalse(VisibilityCondition.Screen("").dependsOnUser())
        assertTrue(VisibilityCondition.parse(JSONObject("""{"condition": "user", "users": []}""")) is VisibilityCondition.Unknown)
    }

    @Test
    fun timeConditionHandlesWindowsOvernightAndWeekdays() {
        val mon = java.util.Calendar.MONDAY
        val sat = java.util.Calendar.SATURDAY
        fun t(h: Int, m: Int = 0, sec: Int = 0) = h * 3_600 + m * 60 + sec

        val day = VisibilityCondition.parse(JSONObject("""{"condition": "time", "after": "08:00", "before": "17:30:00"}"""))
        assertEquals(VisibilityCondition.Time(t(8), t(17, 30), null), day)
        assertFalse(day.evaluate(states(), nowSeconds = t(7, 59), weekday = mon))
        assertTrue(day.evaluate(states(), nowSeconds = t(8), weekday = mon))
        assertTrue(day.evaluate(states(), nowSeconds = t(17, 29), weekday = mon))
        assertFalse(day.evaluate(states(), nowSeconds = t(17, 30), weekday = mon))

        val night = VisibilityCondition.parse(JSONObject("""{"condition": "time", "after": "22:00", "before": "06:00"}"""))
        assertTrue(night.evaluate(states(), nowSeconds = t(23), weekday = mon))
        assertTrue(night.evaluate(states(), nowSeconds = t(0), weekday = mon))
        assertTrue(night.evaluate(states(), nowSeconds = t(5, 59), weekday = mon))
        assertFalse(night.evaluate(states(), nowSeconds = t(6), weekday = mon))
        assertFalse(night.evaluate(states(), nowSeconds = t(12), weekday = mon))

        val afterOnly = VisibilityCondition.Time(t(18), null, null)
        assertTrue(afterOnly.evaluate(states(), nowSeconds = t(18), weekday = mon))
        assertFalse(afterOnly.evaluate(states(), nowSeconds = t(17, 59), weekday = mon))
        val beforeOnly = VisibilityCondition.Time(null, t(9), null)
        assertTrue(beforeOnly.evaluate(states(), nowSeconds = t(8, 59), weekday = mon))
        assertFalse(beforeOnly.evaluate(states(), nowSeconds = t(9), weekday = mon))

        val weekend = VisibilityCondition.parse(JSONObject("""{"condition": "time", "weekdays": ["sat", "sun"]}"""))
        assertEquals(VisibilityCondition.Time(null, null, setOf(sat, java.util.Calendar.SUNDAY)), weekend)
        assertTrue(weekend.evaluate(states(), nowSeconds = t(12), weekday = sat))
        assertFalse(weekend.evaluate(states(), nowSeconds = t(12), weekday = mon))

        val weekdayMornings = VisibilityCondition.parse(
            JSONObject("""{"condition": "time", "after": "06:30", "before": "09:00", "weekdays": ["mon", "tue", "wed", "thu", "fri"]}"""),
        )
        assertTrue(weekdayMornings.evaluate(states(), nowSeconds = t(7), weekday = mon))
        assertFalse(weekdayMornings.evaluate(states(), nowSeconds = t(7), weekday = sat))
        assertFalse(weekdayMornings.evaluate(states(), nowSeconds = t(10), weekday = mon))

        assertTrue(day.dependsOnTime())
        assertTrue(VisibilityCondition.And(listOf(VisibilityCondition.Or(listOf(night)))).dependsOnTime())
        assertFalse(VisibilityCondition.State("light.a", "on", null).dependsOnTime())
        assertEquals(t(7, 5), VisibilityCondition.parseTimeOfDay("7:05"))
        assertNull(VisibilityCondition.parseTimeOfDay("24:00"))
        assertNull(VisibilityCondition.parseTimeOfDay("noon"))
    }

    // ---- Theme font scale ----

    @Test
    fun themeFontScaleComesFromVitroScaleOrHaFontSize() {
        fun scale(vars: String): Float =
            ThemeResolver.resolve(ThemeCatalog.parse(JSONObject("""{"themes": {"t": $vars}}""")), true, "t").fontScale

        assertEquals(1f, scale("""{}"""), 0.001f)
        assertEquals(1.25f, scale("""{"vitro-font-scale": "1.25"}"""), 0.001f)
        assertEquals(2.2f, scale("""{"vitro-font-scale": "5"}"""), 0.001f)
        assertEquals(0.7f, scale("""{"vitro-font-scale": "0.2"}"""), 0.001f)
        assertEquals(1.5f, scale("""{"ha-font-size-m": "21px"}"""), 0.001f)
        assertEquals(1f, scale("""{"ha-font-size-m": "0.875rem"}"""), 0.001f)
        assertEquals(2.2f, scale("""{"ha-font-size-m": "64px"}"""), 0.001f)
        assertEquals(0.7f, scale("""{"ha-font-size-m": "4px"}"""), 0.001f)
        // The Vitro multiplier wins over HA's size; garbage falls through to HA's size, then to 1.
        assertEquals(1.1f, scale("""{"vitro-font-scale": "1.1", "ha-font-size-m": "28px"}"""), 0.001f)
        assertEquals(2f, scale("""{"vitro-font-scale": "big", "ha-font-size-m": "28px"}"""), 0.001f)
        assertEquals(1f, scale("""{"ha-font-size-m": "large"}"""), 0.001f)
        // var() chains resolve first.
        assertEquals(1.5f, scale("""{"my-size": "21px", "ha-font-size-m": "var(--my-size)"}"""), 0.001f)
    }

    // ---- Icons ----

    private class BoundsSink : MdiIcons.PathSink {
        var commands = 0
        var moves = 0
        var minC = Float.MAX_VALUE
        var maxC = -Float.MAX_VALUE
        private fun see(vararg v: Float) {
            for (x in v) {
                assertTrue("non-finite coordinate", x.isFinite())
                minC = minOf(minC, x); maxC = maxOf(maxC, x)
            }
            commands++
        }
        override fun moveTo(x: Float, y: Float) { moves++; see(x, y) }
        override fun lineTo(x: Float, y: Float) = see(x, y)
        override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = see(x1, y1, x, y)
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = see(x1, y1, x2, y2, x, y)
        override fun close() { commands++ }
    }

    @Test
    fun everyBuiltInIconParsesInsideTheGrid() {
        val names = MdiIcons.names()
        assertTrue("expected the extended set, got ${names.size}", names.size >= 200)
        for (name in names) {
            val sink = BoundsSink()
            MdiIcons.parseSvgPathInto(MdiIcons.pathDataFor(name), sink)
            assertTrue("$name drew nothing", sink.moves > 0 && sink.commands > 2)
            assertTrue("$name leaves the 24x24 grid: ${sink.minC}..${sink.maxC}", sink.minC >= -1.5f && sink.maxC <= 25.5f)
        }
    }

    @Test
    fun iconLookupHasRegisterAndCommonDashboardNames() {
        for (n in listOf(
            "information-outline", "information", "alert-circle-outline", "help-circle", "eye-off", "bell-ring",
            "timer-outline", "air-humidifier", "battery-50", "bluetooth", "solar-power", "lightning-bolt", "leaf",
            "power-plug", "television", "washing-machine", "garage-open", "shield-check", "car-electric",
            "chevron-left", "dots-horizontal", "cog-outline", "sync", "map-marker", "star-outline", "account-group",
            "theme-light-dark", "chart-line", "pencil", "trash-can", "fullscreen-exit", "video-off", "router-wireless",
            "paw", "pool", "fireplace", "broom",
        )) {
            assertTrue("missing $n", MdiIcons.has(n))
        }
        assertTrue(MdiIcons.has("mdi:information-outline"))
        assertTrue(MdiIcons.has("hass:INFORMATION-OUTLINE"))
        assertFalse(MdiIcons.has("mdi:definitely-not-an-icon"))
        assertEquals(MdiIcons.pathDataFor("home"), MdiIcons.pathDataFor("mdi:definitely-not-an-icon"))

        val tri = "M12 2L22 20H2Z"
        MdiIcons.register("mdi:vitro-test-triangle", tri)
        assertTrue(MdiIcons.has("vitro-test-triangle"))
        assertEquals(tri, MdiIcons.pathDataFor("mdi:vitro-test-triangle"))
        assertTrue("vitro-test-triangle" in MdiIcons.names())
    }

    @Test
    fun pathParserHandlesArcsSmoothCurvesAndPackedFlags() {
        // A full circle as two arcs (the way MDI writes circles) becomes cubics that stay on the circle.
        val circle = BoundsSink()
        MdiIcons.parseSvgPathInto("M12,2A10,10 0 0,1 22,12A10,10 0 0,1 12,22A10,10 0 0,1 2,12A10,10 0 0,1 12,2Z", circle)
        assertEquals(2f, circle.minC, 0.05f)
        assertEquals(22f, circle.maxC, 0.05f)

        // Packed flags ("0 01 1") and relative arcs parse to the same end point as the spaced form.
        val ends = ArrayList<Pair<Float, Float>>()
        val endSink = object : MdiIcons.PathSink {
            override fun moveTo(x: Float, y: Float) { ends += x to y }
            override fun lineTo(x: Float, y: Float) { ends += x to y }
            override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) { ends += x to y }
            override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) { ends += x to y }
            override fun close() {}
        }
        MdiIcons.parseSvgPathInto("M2 12a10 10 0 0110 10", endSink)
        assertEquals(12f, ends.last().first, 0.01f)
        assertEquals(22f, ends.last().second, 0.01f)

        // An exponent stays in its number (`1e1` is 10); a minus after a number still starts the next one.
        ends.clear()
        MdiIcons.parseSvgPathInto("M0 0L1e1 4", endSink)
        assertEquals(10f, ends.last().first, 0.001f)
        assertEquals(4f, ends.last().second, 0.001f)
        ends.clear()
        MdiIcons.parseSvgPathInto("M0 0L10-4l2.5E-1 1", endSink)
        assertEquals(10.25f, ends.last().first, 0.001f)
        assertEquals(-3f, ends.last().second, 0.001f)

        // An incomplete trailing group draws nothing more (no zero filled in for the missing y).
        ends.clear()
        MdiIcons.parseSvgPathInto("M0 0 L10 10 L20", endSink)
        assertEquals(listOf(0f to 0f, 10f to 10f), ends.toList())
        assertEquals("mdi:alert-circle-outline", MdiIcons.weatherIcon("exceptional"))

        // S and T reflect the previous control point; lower-case forms are relative.
        val smooth = BoundsSink()
        MdiIcons.parseSvgPathInto("M2 12C2 6 8 2 12 2S22 6 22 12s-4 10-10 10Q6 22 4 18T2 12Z", smooth)
        assertTrue(smooth.commands >= 6)
        assertTrue(smooth.minC >= 1.5f && smooth.maxC <= 22.5f)
    }
}
