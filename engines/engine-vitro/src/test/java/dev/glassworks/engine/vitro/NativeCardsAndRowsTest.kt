/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.AlertCardView
import dev.glassworks.engine.vitro.cards.ButtonCardView
import dev.glassworks.engine.vitro.cards.DateCardView
import dev.glassworks.engine.vitro.cards.EntitiesCardView
import dev.glassworks.engine.vitro.cards.HaErrorCardView
import dev.glassworks.engine.vitro.cards.IframeCardView
import dev.glassworks.engine.vitro.cards.PlantStatusCardView
import dev.glassworks.engine.vitro.cards.ShoppingListCardView
import dev.glassworks.engine.vitro.cards.ShortcutCardView
import dev.glassworks.engine.vitro.cards.StatisticCardView
import dev.glassworks.engine.vitro.cards.ToggleGroupCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.model.HaCardSize
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.HaGridSize
import dev.glassworks.engine.vitro.ui.HaMasonry
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.SidebarColumnsLayout
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import dev.glassworks.engine.vitro.cards.LayoutCardView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Date
import java.util.TimeZone

/**
 * The built-in cards and `entities` rows Vitro draws natively since they stopped sending their view to HA's frontend
 * (#861): HA 2026.11's aliases, its small cards, the iframe island, the special rows, and HA's view layouts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeCardsAndRowsTest {
    /** The picture loads a test starts are all back before the next test: one in flight lands in its looper (D28). */
    private val pendingImagesBefore = dev.glassworks.engine.vitro.ui.VitroCardContext.pendingImageLoads.get()

    @After fun imageLoadsSettle() = awaitImageLoads(pendingImagesBefore)

    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()
    private val calls = ArrayList<Triple<String, String, JSONObject?>>()
    private val navigations = ArrayList<String>()
    private val moreInfo = ArrayList<String>()
    private val ws = ArrayList<JSONObject>()
    private var wsAnswer: (JSONObject) -> Triple<Boolean, Any?, JSONObject?> = { Triple(true, null, null) }
    private var user = JSONObject().put("id", "u1").put("is_admin", true)

    @After fun forget() = HaDisplay.reset()

    private fun ctx() = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN },
        callService = { d, s, data, _ -> calls += Triple(d, s, data) },
        callWs = { msg, done -> ws += msg; val (ok, r, e) = wsAnswer(msg); done(ok, r, e) },
        navigate = { navigations += it }, openMoreInfo = { moreInfo += it },
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        haUser = { user },
        now = { NOW },
        pageUrl = { pageUrl },
    )

    private var pageUrl: String? = null

    private fun card(json: String) = LovelaceParser.parseCard(JSONObject(json))
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun View.isShownIn(root: View): Boolean {
        var v: View? = this
        while (v != null) {
            if (v.visibility != View.VISIBLE) return false
            if (v === root) return true
            v = v.parent as? View
        }
        return true
    }
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.isShownIn(v) && it.text.isNotEmpty() }.map { it.text.toString() }
    private fun icons(v: View) = all(v).filterIsInstance<MdiIconView>().filter { it.isShownIn(v) }.map { it.icon }
    private fun put(id: String, state: String, vararg attrs: Pair<String, Any?>) =
        store.putLocal(EntityState(id, state, mapOf(*attrs), lastChanged = (NOW - 5 * 60_000) / 1000.0))
    private fun layout(v: View, wDp: Int, hDp: Int = 0) {
        val dp = activity.resources.displayMetrics.density
        v.measure(View.MeasureSpec.makeMeasureSpec((wDp * dp).toInt(), View.MeasureSpec.EXACTLY),
            if (hDp == 0) View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED) else View.MeasureSpec.makeMeasureSpec((hDp * dp).toInt(), View.MeasureSpec.EXACTLY))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    // ---- entities rows ----

    private fun entities(vararg rows: String) = EntitiesCardView(ctx(), card("""{"type": "entities", "entities": [${rows.joinToString(",")}]}"""))

    @Test fun aButtonRowRunsItsActionFromItsButtonOnly() {
        put("light.desk", "off", "friendly_name" to "Desk")
        val c = entities("""{"type": "button", "entity": "light.desk"}""", """{"type": "button", "name": "Bed time", "action_name": "Go", "icon": "mdi:bed", "tap_action": {"action": "navigate", "navigation_path": "/lovelace/night"}}""")
        assertEquals(listOf("Desk", "Run", "Bed time", "Go"), texts(c))
        // The light toggles (HA's toggle domain default); the row itself doesn't act.
        all(c).filterIsInstance<TextView>().first { it.text == "Run" }.performClick()
        idle()
        assertEquals(listOf("light" to "toggle"), calls.map { it.first to it.second })
        all(c).filterIsInstance<TextView>().first { it.text == "Go" }.performClick()
        assertEquals(listOf("/lovelace/night"), navigations)
        assertTrue(icons(c).contains("mdi:bed"))
    }

    @Test fun aScriptToggleStartsItWhenOffAndStopsItWhileRunning() {
        // HA's toggleEntity: turn_on unless the state is off ("closed", "locked", "off"), so a running script stops.
        put("script.wake_up", "off", "friendly_name" to "Wake up")
        val c = ButtonCardView(ctx(), card("""{"type": "button", "entity": "script.wake_up", "tap_action": {"action": "toggle"}}"""))
        c.performClick()
        idle()
        put("script.wake_up", "on", "friendly_name" to "Wake up")
        c.performClick()
        idle()
        assertEquals(listOf("script" to "turn_on", "script" to "turn_off"), calls.map { it.first to it.second })
    }

    @Test fun aCallServiceRowIsAButtonRowThatPerformsItsAction() {
        val c = entities("""{"type": "call-service", "name": "Ring", "action": "script.ring", "data": {"times": 2}}""", """{"type": "perform-action", "name": "Chime", "service": "script.chime"}""")
        assertEquals(listOf("Ring", "Run", "Chime", "Run"), texts(c))
        all(c).filterIsInstance<TextView>().filter { it.text == "Run" }.forEach { it.performClick() }
        assertEquals(listOf("script" to "ring", "script" to "chime"), calls.map { it.first to it.second })
        assertEquals(2, calls[0].third!!.optInt("times"))
    }

    @Test fun aTextRowShowsItsNameAndTextAndAWeblinkNavigates() {
        val c = entities(
            """{"type": "text", "name": "Wi-Fi", "text": "Guest", "icon": "mdi:wifi"}""",
            """{"type": "weblink", "url": "/config/integrations"}""",
            """{"type": "weblink", "name": "Docs", "url": "https://www.home-assistant.io/"}""",
            // HA refuses a script URL: the row isn't built.
            """{"type": "weblink", "url": "javascript:alert(1)"}""",
        )
        assertEquals(listOf("Wi-Fi", "Guest", "/config/integrations", "Docs"), texts(c))
        assertEquals(listOf("mdi:wifi", "mdi:link", "mdi:link"), icons(c))
        val docs = all(c).filterIsInstance<TextView>().first { it.text == "Docs" }
        assertTrue("a link's text is underlined", docs.paintFlags and Paint.UNDERLINE_TEXT_FLAG != 0)
        (docs.parent as View).performClick()
        assertEquals(listOf("https://www.home-assistant.io/"), navigations)
    }

    // Native graphics: the line breaks need real text widths.
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) fun aButtonCardsLongNameWrapsAndTheCardGrowsWithIt() {
        // hui-button-card's name is a plain span: a long one wraps ("Kitchen Island / Pendant"), never "…", and the card
        // (no fixed height in masonry or a stack) is one 19.2 px line taller for each line it adds.
        put("light.short", "on", "friendly_name" to "Hall")
        put("light.long", "on", "friendly_name" to "Kitchen Island Pendant")
        val short = ButtonCardView(ctx(), card("""{"type": "button", "entity": "light.short"}"""))
        val long = ButtonCardView(ctx(), card("""{"type": "button", "entity": "light.long"}"""))
        layout(short, 110)
        layout(long, 110)
        val name = all(long).filterIsInstance<TextView>().first { it.text.toString() == "Kitchen Island Pendant" }
        assertTrue("the name wraps: ${name.lineCount} line(s)", name.lineCount >= 2)
        assertNull("no ellipsis", name.ellipsize)
        assertEquals("the whole name shows", 0, name.layout.getEllipsisCount(name.lineCount - 1))
        val dp = activity.resources.displayMetrics.density
        val extra = (long.measuredHeight - short.measuredHeight) / dp
        assertEquals("one 19.2 px line per extra line", 19.2f * (name.lineCount - 1), extra, 1f)
    }

    @Test fun aButtonsRowHasOneChipPerEntity() {
        put("light.a", "on", "friendly_name" to "Lamp A")
        put("switch.b", "off", "friendly_name" to "Fan B")
        val c = entities("""{"type": "buttons", "entities": ["light.a", {"entity": "switch.b", "name": "Fan", "show_name": true}, {"entity": "light.a", "show_icon": false, "name": "Only name"}]}""")
        // A chip without `name` and `show_name` shows its icon alone.
        assertEquals(listOf("Fan", "Only name"), texts(c))
        assertEquals(2, icons(c).size)
        val chips = all(c).filter { it.isClickable && it !is EntitiesCardView }
        chips.first().performClick()
        assertEquals(listOf("light" to "toggle"), calls.map { it.first to it.second })
    }

    @Test fun aConditionalRowShowsWhileItsConditionsHoldAndLeavesNoGap() {
        put("light.a", "off", "friendly_name" to "Lamp")
        put("sensor.t", "21", "friendly_name" to "Temp", "unit_of_measurement" to "°C")
        val c = entities(
            """{"type": "conditional", "conditions": [{"condition": "state", "entity": "light.a", "state": "on"}], "row": {"entity": "sensor.t"}}""",
            """{"type": "text", "name": "Note", "text": "Hi"}""",
        )
        layout(c, 400)
        assertEquals(listOf("Note", "Hi"), texts(c))
        val note = all(c).filterIsInstance<TextView>().first { it.text == "Note" }.parent as View
        assertEquals("the first shown row has no gap above it", 0, (note.layoutParams as ViewGroup.MarginLayoutParams).topMargin)
        put("light.a", "on", "friendly_name" to "Lamp")
        idle()
        assertTrue(texts(c).containsAll(listOf("Temp", "Note")))
        assertEquals(Math.round(8 * activity.resources.displayMetrics.density).toInt(), (note.layoutParams as ViewGroup.MarginLayoutParams).topMargin)
    }

    // ---- aliases ----

    @Test fun entityButtonIsTheButtonCardAndSectionIsHasErrorCard() {
        val vv = VitroView(activity, store, Color.BLACK)
        assertEquals(HaCardSize.of(card("""{"type": "button", "entity": "light.a"}""")), HaCardSize.of(card("""{"type": "entity-button", "entity": "light.a"}""")), 0.0)
        assertEquals(HaGridSize(6, 2), HaGridSize.of(card("""{"type": "entity-button", "entity": "light.a"}""")))
        assertEquals("Configuration error", dev.glassworks.engine.vitro.cards.SmallCardConfigs.SECTION_CARD_ERROR)
        val err = HaErrorCardView(ctx(), dev.glassworks.engine.vitro.cards.SmallCardConfigs.SECTION_CARD_ERROR)
        assertEquals(listOf("Configuration error"), texts(err))
        assertEquals(listOf("mdi:alert-circle-outline"), icons(err))
        user = JSONObject().put("id", "u2").put("is_admin", false)
        assertTrue("other users see the icon alone", texts(HaErrorCardView(ctx(), "x")).isEmpty())
        assertNotNull(vv)
    }

    @Test fun aShoppingListCardShowsTheShoppingListOrTheFirstListByName() {
        put("todo.zeta", "2", "friendly_name" to "Zeta")
        put("todo.alpha", "1", "friendly_name" to "Alpha")
        assertEquals("todo.alpha", ShoppingListCardView.pick(store.all().values))
        HaDisplay.applyRegistry(JSONObject().put("entities", JSONArray()
            .put(JSONObject().put("ei", "todo.zeta").put("pl", "shopping_list"))
            .put(JSONObject().put("ei", "todo.alpha").put("pl", "local_todo"))))
        assertEquals("the Shopping list integration's list among several", "todo.zeta", ShoppingListCardView.pick(store.all().values))
        val v = ShoppingListCardView(ctx(), card("""{"type": "shopping-list"}"""))
        assertEquals(1, v.childCount)
    }

    // ---- small cards ----

    @Test fun aStatisticCardAsksTheRecorderForItsPeriodAndShowsTheValue() {
        put("sensor.energy", "10", "friendly_name" to "Energy", "unit_of_measurement" to "kWh", "state_class" to "total_increasing")
        wsAnswer = { m ->
            if (m.optString("type") == "recorder/statistic_during_period") Triple(true, JSONObject().put("change", 12.5), null)
            else Triple(true, JSONArray(), null)
        }
        val v = StatisticCardView(ctx(), card("""{"type": "statistic", "entity": "sensor.energy", "stat_type": "change", "period": {"calendar": {"period": "month"}}}"""))
        idle()
        val call = ws.first { it.optString("type") == "recorder/statistic_during_period" }
        assertEquals("sensor.energy", call.optString("statistic_id"))
        assertEquals("month", call.optJSONObject("calendar")!!.optString("period"))
        assertEquals(listOf("Energy", "12.5", "kWh"), texts(v))
        v.performClick()
        assertEquals(listOf("sensor.energy"), moreInfo)
        // No value for the period: `?`.
        wsAnswer = { m -> if (m.optString("type") == "recorder/statistic_during_period") Triple(true, JSONObject().put("change", JSONObject.NULL), null) else Triple(true, JSONArray(), null) }
        val empty = StatisticCardView(ctx(), card("""{"type": "statistic", "entity": "sensor.energy", "stat_type": "change", "period": {"rolling_window": {"duration": {"days": 1}}}}"""))
        idle()
        assertTrue(texts(empty).contains("?"))
        assertNotNull(ws.last { it.optString("type") == "recorder/statistic_during_period" }.optJSONObject("rolling_window"))
        // An external statistic opens nothing.
        assertFalse(StatisticCardView(ctx(), card("""{"type": "statistic", "entity": "sensor:energy", "stat_type": "max", "period": {}}""")).isClickable)
        assertEquals(HaGridSize(6, 2), HaGridSize.of(card("""{"type": "statistic", "entity": "sensor.energy"}""")))
    }

    @Test fun aDateCardWritesTodayInTheServersZone() {
        // The expected text is English: pin the locale, whatever the host's is.
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        try { dateCardText() } finally { java.util.Locale.setDefault(saved) }
    }

    private fun dateCardText() {
        val v = DateCardView(ctx(), card("""{"type": "date", "time_zone": "America/Chicago", "title": "Today"}"""))
        assertEquals(TimeZone.getTimeZone("America/Chicago"), v.zone())
        // 2026-11-12 10:08 in Chicago, HA's `weekday: long, day: numeric, month: long`.
        assertEquals("Thursday, November 12", v.textAt(Date(NOW)))
        assertTrue(texts(v).containsAll(listOf("Today", "Thursday, November 12")))
        val parts = DateCardView(ctx(), card("""{"type": "date", "time_zone": "America/Chicago", "date_format": ["day-2-digit", "separator-slash", "month-2-digit", "separator-new-line", "year-numeric"]}"""))
        assertEquals("12/11\n2026", parts.textAt(Date(NOW)))
        assertEquals(HaGridSize(6, 2), HaGridSize.of(card("""{"type": "date", "date_size": "large"}""")))
        assertEquals(1.0, HaCardSize.of(card("""{"type": "date"}""")), 0.0)
    }

    @Test fun aPlantStatusCardShowsItsReadingsAndTheProblemsInRed() {
        put("sensor.fern_moisture", "41", "friendly_name" to "Moisture", "unit_of_measurement" to "%")
        put("plant.fern", "problem", "friendly_name" to "Fern", "moisture" to 41, "temperature" to 19.5, "battery" to 80,
            "problem" to "temperature low", "unit_of_measurement_dict" to mapOf("moisture" to "%", "temperature" to "°C", "battery" to "%"),
            "sensors" to mapOf("moisture" to "sensor.fern_moisture"))
        val v = PlantStatusCardView(ctx(), card("""{"type": "plant-status", "entity": "plant.fern"}"""))
        assertEquals(listOf("Fern", "41", "%", "19.5", "°C", "80", "%"), texts(v))
        assertEquals(listOf("mdi:water-percent", "mdi:thermometer", "mdi:battery-80"), icons(v))
        val temp = all(v).filterIsInstance<TextView>().first { it.text == "19.5" }
        assertEquals(0xFFDB4437.toInt(), temp.currentTextColor)
        // A reading opens its sensor.
        (all(v).filterIsInstance<TextView>().first { it.text == "41" }.parent as View).performClick()
        assertEquals(listOf("sensor.fern_moisture"), moreInfo)
    }

    @Test fun aToggleGroupTurnsItsEntitiesOnOrOffTogether() {
        put("light.a", "off"); put("light.b", "off")
        val v = ToggleGroupCardView(ctx(), card("""{"type": "toggle-group", "title": "Lights", "entities": ["light.a", "light.b"]}"""))
        assertEquals(listOf("Lights", "All off"), texts(v))
        all(v).first { it.contentDescription == "Turn Lights on" }.performClick()
        assertEquals(Triple("light", "turn_on", "[\"light.a\",\"light.b\"]"), calls.single().let { Triple(it.first, it.second, it.third!!.optJSONArray("entity_id").toString()) })
        put("light.a", "on")
        idle()
        assertEquals(listOf("Lights", "1 of 2 on"), texts(v))
        assertNotNull(all(v).firstOrNull { it.contentDescription == "Turn Lights off" })
        assertEquals("press", ToggleGroupCardView.toggleAction("button", false))
        assertEquals("close_cover", ToggleGroupCardView.toggleAction("cover", false))
    }

    @Test fun aShortcutShowsItsLabelOrItsActionsDefault() {
        HaDisplay.applyAreas(JSONArray().put(JSONObject().put("area_id", "kitchen").put("name", "Kitchen").put("icon", "mdi:stove")))
        val nav = ShortcutCardView(ctx(), card("""{"type": "shortcut", "description": "Lights and blinds", "tap_action": {"action": "navigate", "navigation_path": "/config/areas/area/kitchen"}}"""))
        assertEquals(listOf("Kitchen", "Lights and blinds"), texts(nav))
        assertEquals(listOf("mdi:stove"), icons(nav))
        nav.performClick()
        assertEquals(listOf("/config/areas/area/kitchen"), navigations)
        val plain = ShortcutCardView(ctx(), card("""{"type": "shortcut", "label": "Nothing"}"""))
        assertFalse("no action: not a button", plain.isClickable)
        assertEquals(listOf("mdi:link"), icons(plain))
        assertEquals(listOf("Assist"), texts(ShortcutCardView(ctx(), card("""{"type": "shortcut", "tap_action": {"action": "assist"}}"""))))
    }

    @Test fun anAlertCardWashesTheCardWhileItsEntityAlerts() {
        HaDisplay.applyRegistry(JSONObject().put("entities", JSONArray().put(JSONObject().put("ei", "binary_sensor.door").put("ai", "hall"))))
        HaDisplay.applyAreas(JSONArray().put(JSONObject().put("area_id", "hall").put("name", "Hall")))
        put("binary_sensor.door", "off", "friendly_name" to "Door", "device_class" to "door")
        val v = AlertCardView(ctx(), card("""{"type": "alert", "entity": "binary_sensor.door"}"""))
        assertEquals("Door", texts(v).first())
        assertTrue(texts(v)[1], texts(v)[1].endsWith(" · 5 min. ago · Hall"))
        put("binary_sensor.door", "on", "friendly_name" to "Door", "device_class" to "door")
        idle()
        assertTrue(AlertCardView.isAlertActive(store["binary_sensor.door"]!!))
        assertEquals("now", AlertCardView.shortRelative(NOW, NOW))
        assertEquals("2 hr. ago", AlertCardView.shortRelative(NOW - 2 * 3_600_000, NOW))
        assertEquals("yesterday", AlertCardView.shortRelative(NOW - 26 * 3_600_000, NOW))
        v.performClick()
        assertEquals(listOf("binary_sensor.door"), moreInfo)
    }

    // ---- iframe ----

    @Test fun anIframePageHoldsOnlyTheFrameWithHasSandboxAndAllow() {
        val v = IframeCardView(ctx(), card("""{"type": "iframe", "url": "/local/plan.html", "aspect_ratio": "16:9", "allow": "fullscreen; camera"}"""))
        assertEquals("${FakeHaServer.BASE}/local/plan.html", v.url)
        assertEquals(9f / 16f, v.ratio!!, 0.0001f)
        assertTrue(v.html, v.html.contains("""<iframe src="${FakeHaServer.BASE}/local/plan.html" sandbox=" ${IframeCardView.SANDBOX}" allow="fullscreen; camera">"""))
        val open = IframeCardView(ctx(), card("""{"type": "iframe", "url": "https://example.com/", "disable_sandbox": true, "allow_open_top_navigation": true}"""))
        assertFalse(open.html.contains("sandbox="))
        assertTrue(IframeCardView.sandboxOf(JSONObject().put("allow_open_top_navigation", true))!!.startsWith("allow-top-navigation-by-user-activation "))
        assertEquals(0.5f, IframeCardView.aspect(null)!!, 0f)
        assertEquals(0.75f, IframeCardView.aspect("75%")!!, 0f)
        assertEquals(HaGridSize(12, 4), HaGridSize.of(card("""{"type": "iframe", "url": "x"}""")))
        assertEquals(4.0, HaCardSize.of(card("""{"type": "iframe", "url": "x", "aspect_ratio": "75%"}""")), 0.0)
    }

    // ---- view layouts ----

    @Test fun masonryColumnsAndPlacementFollowHomeAssistant() {
        assertEquals(1, HaMasonry.columns(299))
        assertEquals(1, HaMasonry.columns(599))
        assertEquals(2, HaMasonry.columns(720))
        assertEquals(3, HaMasonry.columns(960))
        assertEquals(4, HaMasonry.columns(1280))
        // Five 1-unit tiles fill the first column up to 5; the next goes to the smallest (the second).
        val tiles = List(6) { card("""{"type": "tile", "entity": "light.t$it"}""") }
        val (cols, n) = HaMasonry.place(tiles, 960)
        assertEquals(listOf(0, 0, 0, 0, 0, 1), cols.toList())
        assertEquals("the third column is left empty and dropped", 2, n)
        // A tall card first: the next ones go to the columns still under 5.
        val mixed = listOf(card("""{"type": "thermostat", "entity": "climate.a"}"""), card("""{"type": "gauge", "entity": "sensor.a"}"""), card("""{"type": "tile", "entity": "light.a"}"""), card("""{"type": "tile", "entity": "light.b"}"""))
        assertEquals(listOf(0, 1, 1, 2), HaMasonry.place(mixed, 960).first.toList())
        // Never more columns than cards; `max_columns` is the sections view's, not masonry's.
        assertEquals(1, HaMasonry.place(listOf(tiles[0]), 1280).second)
        val view = LovelaceParser.parseView(0, JSONObject("""{"max_columns": 1, "cards": []}"""))
        assertEquals("masonry", view.type)
    }

    @Test fun sidebarWidthsAreCssFlexbox() {
        val dp = 1
        // Content wider than the room: the sidebar keeps 380, the main column takes the rest.
        assertEquals(572 to 380, SidebarColumnsLayout.widths(952, 2000, 2000, 1620 * dp, 380 * dp))
        // Room to spare: shared 2 : 1, the sidebar capped at 380 and the main column taking what's over.
        assertEquals(572 to 380, SidebarColumnsLayout.widths(952, 300, 300, 1620, 380))
        assertEquals(601 to 351, SidebarColumnsLayout.widths(952, 300, 200, 1620, 380))
    }

    @Test @Config(qualifiers = "w960dp-h480dp")
    fun aSidebarViewPutsItsSidebarCardsBesideTheMainOnes() {
        val vv = VitroView(activity, store, Color.BLACK)
        vv.contextFactory = { theme -> ctx().let { VitroCardContext(context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(), accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} }) } }
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject("""{"views": [{"type": "sidebar", "cards": [
            {"type": "markdown", "content": "The main column's first card, with a line far wider than either column"},
            {"type": "markdown", "content": "The sidebar's card, with a line far wider than its 380 px", "view_layout": {"position": "sidebar"}},
            {"type": "markdown", "content": "Main two"}]}]}""")), null)
        activity.setContentView(vv)
        idle()
        val layout = all(vv).filterIsInstance<SidebarColumnsLayout>().single()
        val (main, side) = (layout.getChildAt(0) as ViewGroup) to (layout.getChildAt(1) as ViewGroup)
        assertEquals(2, main.childCount)
        assertEquals(1, side.childCount)
        val dp = activity.resources.displayMetrics.density
        // Side by side in 960 less the 8 px margins, the sidebar at most 380 px and the main column the wider one.
        assertTrue(side.width / dp <= 380.5f)
        assertTrue(main.width > side.width)
        assertTrue((main.width + side.width) / dp <= 952.5f)
        assertEquals(main.right, side.left)
    }

    @Test @Config(qualifiers = "w960dp-h480dp")
    fun aLayoutCardViewIsLaidOutByLayoutCard() {
        val vv = VitroView(activity, store, Color.BLACK)
        vv.contextFactory = { theme -> VitroCardContext(context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(), accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} }) }
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject("""{"views": [{"type": "custom:grid-layout",
            "layout": {"grid-template-columns": "1fr 1fr", "grid-gap": "8px"},
            "badges": [{"entity": "light.a"}],
            "cards": [{"type": "tile", "entity": "light.a"}, {"type": "tile", "entity": "light.b"}, {"type": "tile", "entity": "light.c", "view_layout": {"grid-column": "span 2"}}]}]}""")), null)
        activity.setContentView(vv)
        idle()
        val grid = all(vv).filterIsInstance<LayoutCardView>().single()
        assertEquals("two rows: two cards, then one spanning both columns", 2, grid.tracks.size)
        assertEquals(listOf(2, 1), grid.tracks.map { r -> (0 until r.childCount).count { r.getChildAt(it) !is android.widget.Space } })
        assertTrue("layout-card views draw no badges: the layout is the scroller's only content", grid.parent is android.widget.ScrollView)
    }

    // ---- CodeRabbit's findings on #876 ----

    @Test fun anIframeRatioHaCantUseLeavesTheFrameWithNoHeightOfItsOwn() {
        for (bad in listOf("NaN", "Infinity", "0:9", "-1", "x")) {
            val r = IframeCardView.aspect(bad)
            assertTrue(bad, r == null || (r.isFinite() && r == 0f))
        }
        val v = IframeCardView(ctx(), card("""{"type": "iframe", "url": "/x", "aspect_ratio": "NaN"}"""))
        layout(v, 400) // measuring doesn't throw
    }

    @Test fun anIframeLinkResolvesAgainstTheDashboardsUrl() {
        pageUrl = "${FakeHaServer.BASE}/wall-test/living"
        assertEquals("${FakeHaServer.BASE}/wall-test/camera.html", IframeCardView(ctx(), card("""{"type": "iframe", "url": "camera.html"}""")).url)
        assertEquals("${FakeHaServer.BASE}/local/cam.html", IframeCardView(ctx(), card("""{"type": "iframe", "url": "/local/cam.html"}""")).url)
        assertEquals("https://example.com/", IframeCardView(ctx(), card("""{"type": "iframe", "url": "https://example.com/"}""")).url)
    }

    @Test fun aShoppingListWaitsForTheRegistryBeforeShowingAnEditableList() {
        put("todo.zeta", "2", "friendly_name" to "Zeta")
        put("todo.alpha", "1", "friendly_name" to "Alpha")
        val v = ShoppingListCardView(ctx(), card("""{"type": "shopping-list"}"""))
        // Before the registry the pick could still move: no editable list yet, so nothing is added to a provisional one.
        assertNull(v.shown)
        assertEquals(0, v.childCount)
        HaDisplay.applyRegistry(JSONObject().put("entities", JSONArray()
            .put(JSONObject().put("ei", "todo.zeta").put("pl", "shopping_list"))
            .put(JSONObject().put("ei", "todo.alpha").put("pl", "local_todo"))))
        store.renotifyAll()
        idle()
        assertEquals("the Shopping list integration's list", "todo.zeta", v.shown)
        val built = all(v).filterIsInstance<dev.glassworks.engine.vitro.cards.TodoListCardView>().single()
        // Settled: a later change doesn't build it again.
        put("todo.alpha", "3", "friendly_name" to "Alpha")
        idle()
        assertTrue(built === all(v).filterIsInstance<dev.glassworks.engine.vitro.cards.TodoListCardView>().single())
        // An explicit entity is editable at once.
        HaDisplay.reset()
        assertEquals("todo.alpha", ShoppingListCardView(ctx(), card("""{"type": "shopping-list", "entity": "todo.alpha"}""")).shown)
    }

    // The picture is on a refused address, so no image load outlives the test (the parity harness waits for none).
    @Test fun aPlantWhosePictureCardIsRebuiltDoesntCrash() {
        put("plant.fern", "ok", "friendly_name" to "Fern", "moisture" to 40, "problem" to "none", "entity_picture" to "http://127.0.0.1:9/fern.png")
        val v = PlantStatusCardView(ctx(), card("""{"type": "plant-status", "entity": "plant.fern"}"""))
        put("plant.fern", "problem", "friendly_name" to "Fern", "moisture" to 10, "problem" to "moisture low", "entity_picture" to "http://127.0.0.1:9/fern.png")
        idle()
        assertTrue(texts(v).contains("10"))
    }

    @Test fun anInvertedLayoutCardWidthPairStillLaysOut() {
        val cfg = card("""{"type": "custom:layout-card", "layout_type": "custom:vertical-layout", "layout": {"width": 400, "max_width": 350},
            "cards": [{"type": "markdown", "content": "a"}]}""")
        val v = LayoutCardView(ctx(), cfg, { c -> dev.glassworks.engine.vitro.cards.MarkdownCardView(ctx(), c) }, 960)
        layout(v, 960)
    }

    @Test fun aMasonryColumnWithNoShownCardTakesNoRoom() {
        val row = dev.glassworks.engine.vitro.ui.MasonryColumnsLayout(activity, 10_000)
        val cols = List(2) { android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL } }
        cols.forEach { row.addView(it, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        val a = View(activity).also { cols[0].addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10)) }
        View(activity).also { cols[1].addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10)) }
        layout(row, 800)
        assertEquals(cols[0].width, cols[1].width)
        a.visibility = View.GONE
        layout(row, 800)
        assertEquals(0, cols[0].width)
        assertEquals(Math.round(800 * activity.resources.displayMetrics.density), cols[1].width)
    }

    @Test fun aMasonryColumnNeverStretchesACardToTheRoomLeft() {
        // The view's ScrollView fills the screen, so a short view hands the row an exact height. HA's column never
        // limits a card's height: a card whose background matches its parent (media control's) stays its own height
        // instead of taking the room left, and the card under it shows right below it.
        val row = dev.glassworks.engine.vitro.ui.MasonryColumnsLayout(activity, 10_000)
        val col = dev.glassworks.engine.vitro.ui.CardColumn(activity, 0, 0)
        row.addView(col, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT))
        val card = android.widget.FrameLayout(activity).apply {
            addView(View(activity), android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(View(activity), android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100))
        }
        col.addView(card, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val after = View(activity).also { col.addView(it, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50)) }
        row.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
        row.layout(0, 0, 800, 2000)
        assertEquals(100, card.height)
        assertEquals(100, after.top)
        assertEquals(150, col.height)
    }

    @Test fun theHeaderSwitchFollowsAButtonRowsLight() {
        put("light.a", "on", "friendly_name" to "A")
        put("light.b", "on", "friendly_name" to "B")
        val c = EntitiesCardView(ctx(), card("""{"type": "entities", "title": "T", "entities": [{"type": "button", "entity": "light.a"}, {"type": "button", "entity": "light.b"}]}"""))
        val sw = all(c).filterIsInstance<dev.glassworks.engine.vitro.cards.HaSwitchView>().single()
        assertTrue("lights on: the header switch is on", sw.isOn)
    }

    @Test fun aConditionalRowCanHoldASectionHeading() {
        put("light.a", "on")
        val c = entities("""{"type": "conditional", "conditions": [{"condition": "state", "entity": "light.a", "state": "on"}], "row": {"type": "section", "label": "Lights"}}""")
        assertEquals(listOf("Lights"), texts(c))
    }

    private companion object {
        /** 2026-11-12 10:08 in America/Chicago (16:08 UTC). */
        const val NOW = 1_794_499_680_000L
    }
}
