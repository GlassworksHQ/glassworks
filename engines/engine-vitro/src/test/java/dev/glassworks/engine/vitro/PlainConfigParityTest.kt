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
import dev.glassworks.engine.vitro.cards.EntitiesCardView
import dev.glassworks.engine.vitro.cards.EntityRowView
import dev.glassworks.engine.vitro.cards.GlanceCardView
import dev.glassworks.engine.vitro.cards.HaCodeDialog
import dev.glassworks.engine.vitro.cards.HaSwitchView
import dev.glassworks.engine.vitro.cards.MushroomAlarmControlPanelCardView
import dev.glassworks.engine.vitro.cards.MushroomCoverCardView
import dev.glassworks.engine.vitro.cards.MushroomFanCardView
import dev.glassworks.engine.vitro.cards.MushroomLightCardView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.compat.NativeOptions
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HaLocale
import dev.glassworks.engine.vitro.model.EntityFilterConfig
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.HaGridSize
import dev.glassworks.engine.vitro.ui.TimestampText
import dev.glassworks.engine.vitro.ui.showTimestampOr
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Plain configs, made only of options Vitro declares (`native-options.json`), that drew differently from HA
 * (frontend 20261028.5) or Mushroom 5.3.0: the entities card's and entity filter's header toggle, Mushroom's state
 * lines and alarm buttons, timestamp states and string badges.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlainConfigParityTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()
    private val calls = ArrayList<Triple<String, String, JSONObject?>>()
    private val ws = ArrayList<JSONObject>()
    private var wsAnswer: (JSONObject) -> Any? = { null }
    private var now = NOW
    private val zone = HaLocale.deviceTimeZone

    @Before fun chicago() {
        HaLocale.deviceTimeZone = { TimeZone.getTimeZone("America/Chicago") }
    }

    @After fun forget() {
        HaLocale.deviceTimeZone = zone
        HaDisplay.reset()
    }

    private fun ctx() = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN },
        callService = { d, s, data, _ -> calls += Triple(d, s, data) },
        callWs = { msg, done -> ws += msg; done(true, wsAnswer(msg), null) },
        navigate = {}, openMoreInfo = {},
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        now = { now },
    )

    private fun card(json: String) = LovelaceParser.parseCard(JSONObject(json))
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun View.shownIn(root: View): Boolean {
        var v: View? = this
        while (v != null) {
            if (v.visibility != View.VISIBLE) return false
            if (v === root) return true
            v = v.parent as? View
        }
        return true
    }
    private fun texts(v: View) = all(v).filter { it.shownIn(v) }.flatMap(::drawnWords).filter { it.isNotEmpty() }
    private fun put(id: String, state: String, vararg attrs: Pair<String, Any?>) =
        store.putLocal(EntityState(id, state, mapOf(*attrs), lastChanged = (NOW - 5 * 60_000) / 1000.0))
    private fun headerSwitch(v: View): HaSwitchView? = all(v).filterIsInstance<HaSwitchView>().firstOrNull { it.contentDescription == "Toggle entities" }
    private fun described(v: View, d: String) = all(v).firstOrNull { it.contentDescription?.toString() == d && it.shownIn(v) }

    // ---- 1. The entities card's header toggle ----

    @Test fun theHeaderToggleNeedsTwoRowsOfAToggleDomain() {
        put("light.a", "on"); put("switch.b", "off"); put("siren.c", "off"); put("sensor.d", "3")
        fun shown(rows: String, extra: String = "") =
            headerSwitch(EntitiesCardView(ctx(), card("""{"type": "entities", "title": "T"$extra, "entities": [$rows]}""")))?.isShownIn() == true
        // HA's `_showHeaderToggle`: a title, no `show_header_toggle`, and two rows whose domain is in DOMAINS_TOGGLE.
        assertFalse("one light alone", shown(""""light.a", "sensor.d""""))
        assertTrue("a light and a switch", shown(""""light.a", "switch.b""""))
        assertFalse("a siren isn't a toggle domain of HA's header toggle", shown(""""light.a", "siren.c""""))
        assertTrue("the same light twice counts twice, as HA counts the rows", shown(""""light.a", "light.a""""))
        assertFalse("show_header_toggle: false", shown(""""light.a", "switch.b"""", """, "show_header_toggle": false"""))
        assertNull("no title, no header", headerSwitch(EntitiesCardView(ctx(), card("""{"type": "entities", "entities": ["light.a", "switch.b"]}"""))))
    }

    private fun HaSwitchView.isShownIn(): Boolean {
        var v: View? = this
        while (v != null) { if (v.visibility != View.VISIBLE) return false; v = v.parent as? View }
        return true
    }

    @Test fun theHeaderToggleSwitchesOnlyItsToggleDomainEntitiesThatExist() {
        put("light.a", "off"); put("switch.b", "off"); put("siren.c", "off")
        val c = EntitiesCardView(ctx(), card("""{"type": "entities", "title": "T", "entities": ["light.a", "switch.b", "siren.c", "light.gone"]}"""))
        val sw = headerSwitch(c)!!
        assertTrue(sw.isShownIn())
        assertFalse(sw.isOn)
        assertTrue(sw.performClick())
        val (domain, service, data) = calls.single()
        assertEquals("homeassistant" to "turn_on", domain to service)
        assertEquals("""["light.a","switch.b"]""", data!!.getJSONArray("entity_id").toString())
    }

    // ---- 2. entity-filter's header toggle counts only the rows that pass ----

    @Test fun anEntityFiltersHeaderToggleCountsOnlyTheRowsThatPass() {
        put("light.a", "on"); put("light.b", "off"); put("light.c", "off")
        val outer = card("""{"type": "entity-filter", "entities": ["light.a", "light.b", "light.c"], "state_filter": ["on"], "card": {"title": "Lights on"}}""")
        val c = EntitiesCardView(ctx(), EntityFilterConfig.innerCard(outer))
        idle()
        val sw = headerSwitch(c)!!
        assertFalse("one light passes the filter: HA's inner card has one row, so no toggle", sw.isShownIn())
        put("light.b", "on"); idle()
        assertTrue("two lights pass", sw.isShownIn())
        assertTrue(sw.performClick())
        assertEquals("""["light.a","light.b"]""", calls.single().third!!.getJSONArray("entity_id").toString())
        put("light.a", "off"); idle()
        assertFalse(sw.isShownIn())
    }

    // ---- 3. Mushroom's state lines ----

    @Test fun mushroomCardsWithoutControlsWriteMushroomsStateLine() {
        put("cover.blind", "open", "current_position" to 50)
        put("cover.garage", "closing", "current_position" to 30)
        put("cover.shed", "closed", "current_position" to 0)
        put("light.lamp", "on", "brightness" to 166)
        put("fan.fan", "on", "percentage" to 33.4)
        fun line(json: String): List<String> = texts(TileCardView(ctx(), card(json)).also { idle() })
        assertTrue(line("""{"type": "custom:mushroom-cover-card", "entity": "cover.blind"}""").toString(),
            "Open ⸱ 50%" in line("""{"type": "custom:mushroom-cover-card", "entity": "cover.blind"}"""))
        assertTrue("Closing ⸱ 30%" in line("""{"type": "custom:mushroom-cover-card", "entity": "cover.garage"}"""))
        assertTrue("a 0 position isn't shown", "Closed" in line("""{"type": "custom:mushroom-cover-card", "entity": "cover.shed"}"""))
        assertTrue("65%" in line("""{"type": "custom:mushroom-light-card", "entity": "light.lamp"}"""))
        assertTrue("33%" in line("""{"type": "custom:mushroom-fan-card", "entity": "fan.fan"}"""))
        // Mushroom's entity card writes formatEntityState: no tile extras.
        assertTrue("On" in line("""{"type": "custom:mushroom-entity-card", "entity": "light.lamp"}"""))
        assertTrue("Open" in line("""{"type": "custom:mushroom-entity-card", "entity": "cover.blind"}"""))
    }

    @Test fun mushroomCardsWithControlsWriteTheSameLine() {
        put("cover.shed", "closed", "current_position" to 0)
        put("cover.garage", "closing", "current_position" to 30, "supported_features" to 15)
        val shed = MushroomCoverCardView(ctx(), card("""{"type": "custom:mushroom-cover-card", "entity": "cover.shed", "show_buttons_control": true}"""))
        idle()
        assertTrue(texts(shed).toString(), "Closed" in texts(shed))
        val garage = MushroomCoverCardView(ctx(), card("""{"type": "custom:mushroom-cover-card", "entity": "cover.garage", "show_position_control": true}"""))
        idle()
        assertTrue(texts(garage).toString(), "Closing ⸱ 30%" in texts(garage))
        put("light.dim", "on", "brightness" to 255, "supported_color_modes" to listOf("brightness"))
        val light = MushroomLightCardView(ctx(), card("""{"type": "custom:mushroom-light-card", "entity": "light.dim", "show_brightness_control": true}"""))
        idle()
        assertTrue("100%" in texts(light))
        put("fan.f", "on", "percentage" to 20, "supported_features" to 1)
        val fan = MushroomFanCardView(ctx(), card("""{"type": "custom:mushroom-fan-card", "entity": "fan.f", "show_percentage_control": true}"""))
        idle()
        assertTrue("20%" in texts(fan))
    }

    @Test fun cardsThatWriteFormatEntityStateLeaveOutTheTilesExtras() {
        // hui-picture-entity-card, hui-entity-card and Mushroom's entity chip write HA's formatEntityState: a lit
        // light is "On" there (only the tile, badges and Mushroom's light card show its brightness).
        put("light.lamp", "on", "brightness" to 166, "friendly_name" to "Lamp")
        val picture = dev.glassworks.engine.vitro.cards.PictureCardView(ctx(), card("""{"type": "picture-entity", "entity": "light.lamp", "image": "/local/x.png"}"""))
        idle()
        assertTrue("picture-entity " + texts(picture), "On" in texts(picture) && "65%" !in texts(picture))
        val entity = dev.glassworks.engine.vitro.cards.EntityCardView(ctx(), card("""{"type": "entity", "entity": "light.lamp"}"""))
        idle()
        assertTrue("entity " + texts(entity), "On" in texts(entity))
        val chips = dev.glassworks.engine.vitro.cards.MushroomChipsCardView(ctx(), card("""{"type": "custom:mushroom-chips-card", "chips": [{"type": "entity", "entity": "light.lamp"}]}"""))
        idle()
        assertTrue("chip " + texts(chips), "On" in texts(chips))
    }

    @Test fun anEntityCardTallerThanItsContentSpreadsItAsHaCardsSpaceBetween() {
        // hui-entity-card's ha-card: height 100%, a column with justify-content: space-between over the header, .info
        // and .footer. In a 2-row section slot (120 px) the value line sits half the spare height lower than right under
        // the header (#928: it was ~12 px high); in a masonry column, at its own height, nothing moves.
        put("sensor.ink", "74", "unit_of_measurement" to "%", "friendly_name" to "Ink")
        val entity = dev.glassworks.engine.vitro.cards.EntityCardView(ctx(), card("""{"type": "entity", "entity": "sensor.ink"}"""))
        idle()
        val dp = activity.resources.displayMetrics.density
        val w = View.MeasureSpec.makeMeasureSpec((246 * dp).roundToInt(), View.MeasureSpec.EXACTLY)
        val value = all(entity).filterIsInstance<TextView>().single { it.text.toString() == "74" }
        fun topIn(v: View): Int { var t = 0; var c: View = v; while (c !== entity) { t += c.top; c = c.parent as View }; return t }
        entity.measure(w, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val natural = entity.measuredHeight
        entity.layout(0, 0, entity.measuredWidth, natural)
        val atNatural = topIn(value)
        // A slot 40 px taller than the content (the corpus's 120 px slot is the parity harness's; this sandbox's fonts
        // differ in height).
        val tall = natural + (40 * dp).roundToInt()
        entity.measure(w, View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY))
        entity.layout(0, 0, entity.measuredWidth, tall)
        assertEquals((tall - natural) / 2f, (topIn(value) - atNatural).toFloat(), 1.5f)
    }

    @Test fun stateDisplayAddsAnAttributeThatIsSetAndNotZeroInAnyState() {
        // HA's state-display (the tile, view badges): `cover: [state, current_position]`, `fan: percentage`,
        // `light: brightness`, each attribute left out when it is null or 0.
        assertEquals("Closing · 30%", EntityState("cover.g", "closing", mapOf("current_position" to 30)).formattedState())
        assertEquals("Closed", EntityState("cover.g", "closed", mapOf("current_position" to 0)).formattedState())
        assertEquals("Off", EntityState("fan.f", "off", mapOf("percentage" to 0)).formattedState())
        assertEquals("On", EntityState("fan.f", "on", mapOf("percentage" to null)).formattedState())
        assertEquals("Off", EntityState("light.l", "off", mapOf("brightness" to null)).formattedState())
    }

    @Test fun aHeadingEntityDrawsADashWhenUnavailable() {
        // hui-heading-entity: state-display with dash-unavailable.
        store.putLocal(EntityState("sensor.door", "unavailable", emptyMap()))
        val c = dev.glassworks.engine.vitro.cards.HeadingCardView(ctx(), card("""{"type": "heading", "heading": "Hall", "badges": ["sensor.door"]}"""))
        idle()
        assertTrue(texts(c).toString(), "—" in texts(c))
    }

    @Test fun mushroomCardsOnTheTilePathTakeMushroomsIconColors() {
        // Mushroom 5.3.0's renderIcon (HA's references: mushroom-cover-closing, mushroom-entity-timestamp).
        val theme = ThemeResolver.resolve(ThemeCatalog(), true)
        fun color(type: String, s: EntityState, raw: String = "{}") =
            dev.glassworks.engine.vitro.cards.mushroomIconColor(type, JSONObject(raw), s, theme)
        val blue = 0xFF2196F3.toInt()
        assertNull("a closing cover is grey", color("custom:mushroom-cover-card", EntityState("cover.b", "closing", emptyMap())))
        assertEquals(blue, color("custom:mushroom-cover-card", EntityState("cover.b", "opening", emptyMap())))
        assertEquals("the entity card: blue for any active entity", blue, color("custom:mushroom-entity-card", EntityState("sensor.t", "21", emptyMap())))
        assertEquals(blue, color("custom:mushroom-entity-card", EntityState("light.l", "on", emptyMap())))
        assertNull(color("custom:mushroom-entity-card", EntityState("light.l", "off", emptyMap())))
        assertEquals(0xFFFF9800.toInt(), color("custom:mushroom-light-card", EntityState("light.l", "on", mapOf("rgb_color" to listOf(255, 0, 0)))))
        assertEquals(0xFFFF0000.toInt(), color("custom:mushroom-light-card", EntityState("light.l", "on", mapOf("rgb_color" to listOf(255, 0, 0))), """{"use_light_color": true}"""))
        assertEquals(0xFF4CAF50.toInt(), color("custom:mushroom-fan-card", EntityState("fan.f", "on", emptyMap())))
    }

    @Test fun mushroomEntityChipIconsTakeTheChipsTextColor() {
        // Mushroom's entity chip colors its icon only with its own icon_color (HA's reference: mushroom-chips-person).
        put("person.alex", "home"); put("light.lamp", "on", "rgb_color" to listOf(255, 160, 0))
        val chips = dev.glassworks.engine.vitro.cards.MushroomChipsCardView(ctx(), card("""{"type": "custom:mushroom-chips-card", "chips": [{"type": "entity", "entity": "person.alex"}, {"type": "entity", "entity": "light.lamp"}]}"""))
        idle()
        val theme = ThemeResolver.resolve(ThemeCatalog(), true)
        val tints = all(chips).filterIsInstance<dev.glassworks.engine.vitro.ui.MdiIconView>().map { it.tintColor }
        assertEquals(listOf(theme.primaryTextColor, theme.primaryTextColor), tints)
    }

    @Test fun aHorizontalMushroomSliderFillsTheHalfBesideTheInfo() {
        // mushroom-slider's flex: 1 (HA's reference: mushroom-light-horizontal), not a 36 px square.
        put("light.lamp", "on", "brightness" to 166, "supported_color_modes" to listOf("brightness"))
        val c = MushroomLightCardView(ctx(), card("""{"type": "custom:mushroom-light-card", "entity": "light.lamp", "layout": "horizontal", "show_brightness_control": true}"""))
        idle()
        val dp = activity.resources.displayMetrics.density
        c.measure(View.MeasureSpec.makeMeasureSpec((480 * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        c.layout(0, 0, c.measuredWidth, c.measuredHeight)
        val slider = all(c).filterIsInstance<dev.glassworks.engine.vitro.cards.HaHorizontalSliderView>().single()
        assertTrue("slider ${slider.width / dp} dp wide", slider.width / dp > 150)
    }

    // ---- 4. Mushroom's alarm card ----

    @Test fun mushroomsAlarmCardHasNoButtonsWithoutStates() {
        put("alarm_control_panel.house", "armed_away", "code_format" to "number")
        val raw = """{"type": "custom:mushroom-alarm-control-panel-card", "entity": "alarm_control_panel.house"}"""
        val c = MushroomAlarmControlPanelCardView(ctx(), card(raw))
        idle()
        assertNull("no Disarm without states", described(c, "Disarm"))
        assertNull(described(c, "Arm home"))
        assertFalse("its grid row has no controls", HaGridSize.mushroomHasControls("custom:mushroom-alarm-control-panel-card", JSONObject(raw)))
        put("alarm_control_panel.house", "disarmed", "code_format" to "number"); idle()
        assertNull("nor arm buttons", described(c, "Arm home"))
    }

    @Test fun anUnavailableAlarmKeepsItsButtonDisabled() {
        put("alarm_control_panel.house", "unavailable")
        val c = MushroomAlarmControlPanelCardView(ctx(), card("""{"type": "custom:mushroom-alarm-control-panel-card", "entity": "alarm_control_panel.house", "states": ["armed_home"]}"""))
        idle()
        val disarm = described(c, "Disarm")
        assertNotNull("Mushroom shows the Disarm button, disabled", disarm)
        assertFalse(disarm!!.isEnabled)
    }

    @Test fun mushroomsAlarmCardAsksForTheCodeWhenTheAlarmNeedsOne() {
        put("alarm_control_panel.house", "disarmed", "code_format" to "number", "code_arm_required" to true)
        val c = MushroomAlarmControlPanelCardView(ctx(), card("""{"type": "custom:mushroom-alarm-control-panel-card", "entity": "alarm_control_panel.house", "states": ["armed_home", "armed_away"]}"""))
        idle()
        HaCodeDialog.lastShown = null
        assertTrue(described(c, "Arm away")!!.performClick())
        idle()
        assertEquals("config/entity_registry/get", ws.single().getString("type"))
        assertTrue("no call before the code", calls.isEmpty())
        assertNotNull("the code dialog", HaCodeDialog.lastShown)
        val dialog = HaCodeDialog.lastView!!
        // HA's dialog-enter-code for a number code: the keypad, titled and submitted with "Arm".
        assertTrue(dialog.numeric)
        assertTrue("Arm" in texts(dialog))
        assertEquals("Arm", dialog.keys.getValue("submit").contentDescription)
        "1234".forEach { dialog.keys.getValue(it.toString()).performClick() }
        assertEquals("1234", dialog.code)
        dialog.keys.getValue("submit").performClick()
        idle()
        val (domain, service, data) = calls.single()
        assertEquals("alarm_control_panel" to "alarm_arm_away", domain to service)
        assertEquals("1234", data!!.getString("code"))
        assertFalse("the dialog closed", HaCodeDialog.lastShown!!.isShowing)
    }

    @Test fun aDefaultCodeOrNoCodeRequiredSkipsTheDialog() {
        // Arming without code_arm_required needs no code, even with a code format.
        put("alarm_control_panel.house", "disarmed", "code_format" to "number", "code_arm_required" to false)
        val c = MushroomAlarmControlPanelCardView(ctx(), card("""{"type": "custom:mushroom-alarm-control-panel-card", "entity": "alarm_control_panel.house", "states": ["armed_home"]}"""))
        idle()
        HaCodeDialog.lastShown = null
        described(c, "Arm home")!!.performClick(); idle()
        assertTrue(ws.isEmpty())
        assertEquals("alarm_arm_home", calls.single().second)
        assertFalse(calls.single().third!!.has("code"))
        // Disarming with a code format, and a default code in the registry: no dialog, no code.
        put("alarm_control_panel.house", "armed_home", "code_format" to "number"); idle()
        wsAnswer = { JSONObject().put("options", JSONObject().put("alarm_control_panel", JSONObject().put("default_code", "0000"))) }
        described(c, "Disarm")!!.performClick(); idle()
        assertNull(HaCodeDialog.lastShown)
        assertEquals("alarm_disarm", calls.last().second)
        assertFalse(calls.last().third!!.has("code"))
    }

    // ---- 5. Timestamp states (B6) ----

    private fun iso(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'", java.util.Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    @Test fun timestampStatesAreFormattedAsHaFormatsThem() {
        val inThree = NOW + 3 * 3_600_000L
        val sunrise = EntityState("sensor.next_sunrise", iso(inThree), mapOf("device_class" to "timestamp"))
        // computeStateDisplay: formatDateTime in the user's zone.
        assertEquals("November 12, 2026 at 1:08\u202FPM", sunrise.formattedState())
        assertEquals("November 12, 2026 at 1:08\u202FPM", sunrise.plainState())
        val scene = EntityState("scene.movie", iso(NOW - 2 * 60_000L), emptyMap())
        assertEquals("November 12, 2026 at 10:06\u202FAM", scene.formattedState())
        // hui-timestamp-display: relative, capitalized; an uptime as a total.
        assertEquals("In 3 hours", sunrise.timestampDisplay(NOW))
        assertEquals("2 minutes ago", scene.timestampDisplay(NOW))
        val boot = EntityState("sensor.uptime", iso(NOW - 3 * 86_400_000L), mapOf("device_class" to "uptime"))
        assertEquals("3 days", boot.timestampDisplay(NOW))
        // Unknown and unavailable aren't timestamps; text that isn't a date stays as it is.
        assertNull(EntityState("button.restart", "unknown", emptyMap()).timestampDisplay(NOW))
        assertEquals("Unknown", EntityState("button.restart", "unknown", emptyMap()).formattedState())
        assertEquals("soon", EntityState("sensor.x", "soon", mapOf("device_class" to "timestamp")).formattedState())
        // state-display takes a timestamp device_class on any domain; the rows and glance only on a sensor.
        val odd = EntityState("input_text.when", iso(inThree), mapOf("device_class" to "timestamp"))
        assertNull(odd.timestampDisplay(NOW))
        assertEquals("In 3 hours", odd.timestampDisplay(NOW, anyDomain = true))
        // The date domains: a local date, time or both.
        assertEquals("November 12, 2026", EntityState("input_datetime.day", "2026-11-12", emptyMap()).formattedState())
        assertEquals("November 12, 2026 at 7:30\u202FAM", EntityState("input_datetime.alarm", "2026-11-12 07:30:00", emptyMap()).formattedState())
    }

    @Test fun rowsGlanceAndTilesDrawATimestampRelativeAndKeepItCurrent() {
        put("sensor.next_alarm", iso(NOW + 90_000L), "device_class" to "timestamp", "friendly_name" to "Next alarm")
        val row = EntitiesCardView(ctx(), card("""{"type": "entities", "entities": ["sensor.next_alarm"]}"""))
        idle()
        assertTrue("row " + texts(row), "In 2 minutes" in texts(row))
        val glance = GlanceCardView(ctx(), card("""{"type": "glance", "entities": ["sensor.next_alarm"]}"""))
        idle()
        assertTrue("glance " + texts(glance), "In 2 minutes" in texts(glance))
        val tile = TileCardView(ctx(), card("""{"type": "tile", "entity": "sensor.next_alarm"}"""))
        idle()
        assertTrue("tile " + texts(tile), "In 2 minutes" in texts(tile))
        // Mushroom's `state` info: ha-relative-time for a timestamp sensor (HA's reference: mushroom-entity-timestamp).
        val mushroom = TileCardView(ctx(), card("""{"type": "custom:mushroom-entity-card", "entity": "sensor.next_alarm"}"""))
        idle()
        assertTrue("mushroom " + texts(mushroom), "In 2 minutes" in texts(mushroom))
        // An uptime isn't one of Mushroom's: it writes formatEntityState, the date and time.
        put("sensor.up", iso(NOW - 3 * 86_400_000L), "device_class" to "uptime")
        val up = TileCardView(ctx(), card("""{"type": "custom:mushroom-entity-card", "entity": "sensor.up"}"""))
        idle()
        assertTrue("uptime " + texts(up), "November 9, 2026 at 10:08\u202FAM" in texts(up))

        // On screen, the relative time moves on each second, as hui-timestamp-display redraws it.
        val parent = android.widget.FrameLayout(activity)
        activity.setContentView(parent)
        parent.addView(row)
        val value = all(row).filterIsInstance<EntityRowView>().first { it.stateText.toString() == "In 2 minutes" }
        assertTrue(TimestampText.isTicking(value))
        now = NOW + 60_000L
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_100))
        assertEquals("In 30 seconds", value.stateText.toString())
    }

    @Test fun aDiscardedTimestampTextIsntKeptByItsTicker() {
        put("sensor.next_alarm", iso(NOW + 90_000L), "device_class" to "timestamp")
        val c = ctx()
        fun discarded(): java.lang.ref.WeakReference<TextView> {
            val text = TextView(activity)
            c.showTimestampOr(text, store["sensor.next_alarm"], "…")
            assertTrue(TimestampText.isTicking(text))
            return java.lang.ref.WeakReference(text)
        }
        val ref = discarded()
        repeat(20) { if (ref.get() != null) { System.gc(); Thread.sleep(20) } }
        assertNull("a text no one holds is collected, ticker and all", ref.get())
    }

    // ---- 6. String badges ----

    @Test fun aStringBadgeShowsItsNameAsHaDoes() {
        val view = JSONObject("""{"badges": ["sensor.outdoor"], "cards": []}""")
        assertTrue(NativeOptions.viewReasons(view).toString(), NativeOptions.viewReasons(view).isEmpty())
        val dash = LovelaceParser.parseDashboard(JSONObject().put("views", org.json.JSONArray().put(view)))
        val badge = dash.views.single().badges.single()
        assertEquals(true, badge.raw.optBoolean("show_name"))
        val temp = EntityState("sensor.outdoor", "7.4", mapOf("unit_of_measurement" to "°C", "friendly_name" to "Outdoor"))
        assertEquals("Outdoor" to "7.4 °C", VitroView.badgeLines(badge, temp))
    }

    private companion object {
        /** 2026-11-12 10:08 in America/Chicago (16:08 UTC). */
        const val NOW = 1_794_499_680_000L
    }
}
