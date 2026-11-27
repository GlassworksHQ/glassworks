/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.AutoEntitiesCardView
import dev.glassworks.engine.vitro.cards.AutoEntitiesFilter
import dev.glassworks.engine.vitro.cards.LayoutCardView
import dev.glassworks.engine.vitro.cards.MiniMediaPlayerCardView
import dev.glassworks.engine.vitro.cards.MushroomAlarmControlPanelCardView
import dev.glassworks.engine.vitro.cards.MushroomHumidifierCardView
import dev.glassworks.engine.vitro.cards.MushroomLockCardView
import dev.glassworks.engine.vitro.cards.MushroomMediaPlayerCardView
import dev.glassworks.engine.vitro.cards.MushroomNumberCardView
import dev.glassworks.engine.vitro.cards.MushroomSelectCardView
import dev.glassworks.engine.vitro.cards.MushroomVacuumCardView
import dev.glassworks.engine.vitro.cards.StackInCardView
import dev.glassworks.engine.vitro.cards.VerticalStackInCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.CardTier
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** The 7,447-icon MDI catalog and the Tier 2 wave: Mushroom controls, mini-media-player, auto-entities, stacks, Bubble sub-buttons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HacsParityWaveTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    /** One HA service call a card made. */
    data class Call(val service: String, val data: JSONObject) {
        val entityId: String get() = data.optString("entity_id")
    }

    private val calls = ArrayList<Call>()
    private val actions = ArrayList<String>()

    private fun context(store: EntityStore, theme: dev.glassworks.engine.vitro.theme.ResolvedTheme = ThemeResolver.resolve(ThemeCatalog(), true)) = VitroCardContext(
        context = activity,
        haBase = FakeHaServer.BASE,
        store = store,
        theme = theme,
        resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN },
        callService = { domain, service, data, _ -> calls += Call("$domain.$service", data ?: JSONObject()) },
        navigate = { actions += "navigate:$it" },
        openMoreInfo = { actions += "more-info:$it" },
        fetchHistory = { _, _, _ -> },
        subscribeTemplate = { _, _ -> {} },
    )

    private fun card(json: String) = LovelaceParser.parseCard(JSONObject(json))

    private fun entity(id: String, state: String, vararg attrs: Pair<String, Any?>) =
        EntityState(id, state, mapOf("friendly_name" to id.substringAfter('.').replace('_', ' ')) + attrs)

    private fun store(vararg states: EntityState) = EntityStore().apply { states.forEach(::putLocal) }

    private fun idle() = ShadowLooper.idleMainLooper()

    private fun View.all(): List<View> {
        val out = ArrayList<View>()
        out += this
        if (this is ViewGroup) for (i in 0 until childCount) out += getChildAt(i).all()
        return out
    }

    private fun View.click(description: String) {
        val v = all().firstOrNull { it.contentDescription?.toString() == description && it.visibility == View.VISIBLE }
        assertNotNull("no visible '$description' in ${all().mapNotNull { it.contentDescription }}", v)
        assertTrue(v!!.performClick())
    }

    private fun View.texts(): List<String> = all().filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.map { it.text.toString() }

    // ---- MDI catalog ----

    @Test
    fun theBundledCatalogHoldsAllOfficialIcons() {
        MdiIcons.init(activity)
        assertEquals(7447, MdiIcons.catalogSize())
        for (n in listOf("robot-vacuum-variant", "doorbell-video", "ev-station", "circle", "flash-alert", "wrench", "information-outline")) {
            assertTrue("missing $n", MdiIcons.has(n))
            assertTrue("mdi:$n", MdiIcons.has("mdi:$n"))
        }
        // Official path data, as Home Assistant draws it (not the hand-drawn built-in `circle`).
        assertEquals("M12,2A10,10 0 0,0 2,12A10,10 0 0,0 12,22A10,10 0 0,0 22,12A10,10 0 0,0 12,2Z", MdiIcons.pathDataFor("mdi:circle"))
        assertTrue(MdiIcons.names().size >= 7447)
        assertFalse(MdiIcons.has("mdi:definitely-not-an-icon"))
        assertEquals(MdiIcons.pathDataFor("home"), MdiIcons.pathDataFor("mdi:definitely-not-an-icon"))
        // Registered glyphs still win over the catalog.
        MdiIcons.register("mdi:vitro-wave-test", "M0 0L24 24H0Z")
        assertEquals("M0 0L24 24H0Z", MdiIcons.pathDataFor("vitro-wave-test"))
        assertNotNull(MdiIcons.pathFor("mdi:ev-station"))
    }

    // ---- Mushroom ----

    @Test
    fun mushroomLockCallsLockAndUnlock() {
        val st = store(entity("lock.front_door", "locked"))
        val card = MushroomLockCardView(context(st), card("""{"type": "custom:mushroom-lock-card", "entity": "lock.front_door"}"""))
        idle()
        assertTrue("Locked" in card.texts())
        card.click("Unlock")
        assertEquals("lock.unlock", calls.last().service)
        assertEquals("lock.front_door", calls.last().entityId)

        st.putLocal(entity("lock.front_door", "unlocked"))
        idle()
        card.click("Lock")
        assertEquals("lock.lock", calls.last().service)
    }

    @Test
    fun mushroomAlarmArmsTheConfiguredStatesAndDisarms() {
        val st = store(entity("alarm_control_panel.home", "disarmed"))
        val card = MushroomAlarmControlPanelCardView(
            context(st),
            card("""{"type": "custom:mushroom-alarm-control-panel-card", "entity": "alarm_control_panel.home", "states": ["armed_home", "armed_away", "armed_night"]}"""),
        )
        idle()
        card.click("Arm night")
        assertEquals("alarm_control_panel.alarm_arm_night", calls.last().service)
        card.click("Arm away")
        assertEquals("alarm_control_panel.alarm_arm_away", calls.last().service)

        st.putLocal(entity("alarm_control_panel.home", "armed_away"))
        idle()
        assertTrue("Armed away" in card.texts())
        card.click("Disarm")
        assertEquals("alarm_control_panel.alarm_disarm", calls.last().service)
    }

    @Test
    fun mushroomVacuumRunsItsCommands() {
        val st = store(entity("vacuum.robo", "docked", "battery_level" to 85))
        val card = MushroomVacuumCardView(
            context(st),
            card("""{"type": "custom:mushroom-vacuum-card", "entity": "vacuum.robo", "commands": ["start_pause", "stop", "locate", "clean_spot", "return_home"]}"""),
        )
        idle()
        // Mushroom's vacuum card: HA's state label alone under the name (no battery, no `status` attribute).
        assertTrue(card.texts().toString(), card.texts().any { it == "Docked" })
        card.click("Start")
        assertEquals("vacuum.start", calls.last().service)
        st.putLocal(entity("vacuum.robo", "cleaning", "battery_level" to 80))
        idle()
        card.click("Pause")
        assertEquals("vacuum.pause", calls.last().service)
        card.click("Stop"); assertEquals("vacuum.stop", calls.last().service)
        card.click("Locate"); assertEquals("vacuum.locate", calls.last().service)
        card.click("Clean spot"); assertEquals("vacuum.clean_spot", calls.last().service)
        card.click("Return home"); assertEquals("vacuum.return_to_base", calls.last().service)
    }

    @Test
    fun mushroomCoverButtonsAndItsStateLine() {
        val st = store(entity("cover.blind", "open", "current_position" to 40, "supported_features" to 15))
        val card = dev.glassworks.engine.vitro.cards.MushroomCoverCardView(context(st),
            card("""{"type": "custom:mushroom-cover-card", "entity": "cover.blind", "show_buttons_control": true}"""))
        idle()
        assertTrue(card.texts().toString(), "Open \u2E31 40%" in card.texts())
        card.click("Open"); assertEquals("cover.open_cover", calls.last().service)
        card.click("Stop"); assertEquals("cover.stop_cover", calls.last().service)
        card.click("Close"); assertEquals("cover.close_cover", calls.last().service)

        // Both controls: the buttons first, and a button that switches to the position slider (and back).
        val both = dev.glassworks.engine.vitro.cards.MushroomCoverCardView(context(st),
            card("""{"type": "custom:mushroom-cover-card", "entity": "cover.blind", "show_buttons_control": true, "show_position_control": true}"""))
        idle()
        fun shown(d: String) = both.all().any { it.contentDescription?.toString() == d && it.visibility == View.VISIBLE }
        fun sliderShown() = both.all().any { it is dev.glassworks.engine.vitro.cards.HaHorizontalSliderView && it.visibility == View.VISIBLE }
        assertTrue(shown("Open")); assertFalse(sliderShown())
        both.click("position_control")
        assertFalse(shown("Open")); assertTrue(sliderShown())
        both.click("buttons_control")
        assertTrue(shown("Open")); assertFalse(sliderShown())
    }

    @Test
    fun mushroomCoverButtonsFollowMushroomsRules() {
        // mushroom-cover-buttons-control (Mushroom 5.3.0): a button per supported feature (OPEN 1, CLOSE 2, STOP 8),
        // open disabled while fully open or opening, close while fully closed or closing (unless assumed_state), stop
        // only while unavailable. Tablet Vitro run 3: a closed cover's close button stayed enabled.
        val st = store(entity("cover.blind", "closed", "current_position" to 0, "supported_features" to 15))
        val ctx = context(st)
        val card = dev.glassworks.engine.vitro.cards.MushroomCoverCardView(ctx,
            card("""{"type": "custom:mushroom-cover-card", "entity": "cover.blind", "show_buttons_control": true}"""))
        idle()
        fun button(d: String) = card.all().single { it.contentDescription?.toString() == d }
        fun enabled() = listOf("Open", "Stop", "Close").filter { button(it).visibility == View.VISIBLE }.associateWith { button(it).isEnabled }
        assertEquals(mapOf("Open" to true, "Stop" to true, "Close" to false), enabled())
        val before = calls.size
        button("Close").performClick()
        assertEquals("a disabled button sends nothing", before, calls.size)
        val icon = (button("Close") as android.view.ViewGroup).getChildAt(0) as MdiIconView
        assertEquals(dev.glassworks.engine.vitro.cards.mushroomDisabledColor(ctx), icon.tintColor)

        fun set(state: String, vararg attrs: Pair<String, Any>) {
            st.putLocal(entity("cover.blind", state, *attrs))
            idle()
        }
        set("open", "current_position" to 100, "supported_features" to 15)
        assertEquals(mapOf("Open" to false, "Stop" to true, "Close" to true), enabled())
        set("opening", "current_position" to 50, "supported_features" to 15)
        assertEquals(mapOf("Open" to false, "Stop" to true, "Close" to true), enabled())
        set("closing", "current_position" to 50, "supported_features" to 15)
        assertEquals(mapOf("Open" to true, "Stop" to true, "Close" to false), enabled())
        set("closed", "current_position" to 0, "supported_features" to 15, "assumed_state" to true)
        assertEquals(mapOf("Open" to true, "Stop" to true, "Close" to true), enabled())
        set("unavailable", "supported_features" to 15)
        assertEquals(mapOf("Open" to false, "Stop" to false, "Close" to false), enabled())
        // No position: the state alone. Open and close only (3): no stop button.
        set("open", "supported_features" to 3)
        assertEquals(mapOf("Open" to false, "Close" to true), enabled())
        set("closed", "supported_features" to 3)
        assertEquals(mapOf("Open" to true, "Close" to false), enabled())
        // A curtain's buttons are sideways.
        set("closed", "supported_features" to 3, "device_class" to "curtain")
        assertEquals("mdi:arrow-expand-horizontal", ((button("Open") as android.view.ViewGroup).getChildAt(0) as MdiIconView).icon)
        // No supported_features: Mushroom draws no button.
        set("open", "current_position" to 30)
        assertEquals(emptyMap<String, Boolean>(), enabled())

        // Mushroom's `_controls` follow the cover too: with both configured, a cover without SET_POSITION (3) has the
        // buttons only, and no button to switch to a slider it can't use (parity case m-mushroom-cover-cover-open-no-position).
        val both = dev.glassworks.engine.vitro.cards.MushroomCoverCardView(ctx,
            card("""{"type": "custom:mushroom-cover-card", "entity": "cover.blind", "show_buttons_control": true, "show_position_control": true}"""))
        set("open", "supported_features" to 3)
        fun shown(d: String) = both.all().any { it.contentDescription?.toString() == d && it.visibility == View.VISIBLE }
        fun sliderShown() = both.all().any { it is dev.glassworks.engine.vitro.cards.HaHorizontalSliderView && it.visibility == View.VISIBLE }
        assertTrue(shown("Open")); assertFalse(shown("position_control")); assertFalse(sliderShown())
        // With SET_POSITION (15): the switch to the slider is back.
        set("open", "current_position" to 100, "supported_features" to 15)
        assertTrue(shown("Open")); assertTrue(shown("position_control"))
        // Position only (4): the slider is the active control, and there's nothing to switch to.
        set("open", "current_position" to 100, "supported_features" to 4)
        assertFalse(shown("Open")); assertTrue(sliderShown()); assertFalse(shown("buttons_control"))
    }

    @Test
    fun aMushroomCoverBuiltOnAStoredStateKeepsItsControlsWhenSwitching() {
        // Greptile on #799: the state the first render (in init's start()) chose the controls for was reset by a property
        // initializer declared after init, so switching to the slider found no state and hid every control.
        val st = store(entity("cover.blind", "open", "current_position" to 40, "supported_features" to 15))
        val card = dev.glassworks.engine.vitro.cards.MushroomCoverCardView(context(st),
            card("""{"type": "custom:mushroom-cover-card", "entity": "cover.blind", "show_buttons_control": true, "show_position_control": true}"""))
        idle()
        fun shown(d: String) = card.all().any { it.contentDescription?.toString() == d && it.visibility == View.VISIBLE }
        fun sliderShown() = card.all().any { it is dev.glassworks.engine.vitro.cards.HaHorizontalSliderView && it.visibility == View.VISIBLE }
        // Before any update: the buttons, and the switch to the slider.
        assertTrue(shown("Open")); assertTrue(shown("position_control")); assertFalse(sliderShown())
        card.click("position_control")
        assertTrue("the slider, after switching with no update since", sliderShown())
        assertTrue(shown("buttons_control")); assertFalse(shown("Open"))
        card.click("buttons_control")
        assertTrue(shown("Open")); assertFalse(sliderShown())
    }

    @Test
    fun mushroomFanShowsItsPercentageAndOscillates() {
        val st = store(entity("fan.purifier", "on", "percentage" to 20, "oscillating" to false, "supported_features" to 3))
        val card = dev.glassworks.engine.vitro.cards.MushroomFanCardView(context(st),
            card("""{"type": "custom:mushroom-fan-card", "entity": "fan.purifier", "show_percentage_control": true, "show_oscillate_control": true}"""))
        idle()
        assertTrue(card.texts().toString(), "20%" in card.texts())
        card.click("Oscillate")
        assertEquals("fan.oscillate", calls.last().service)
        assertEquals(true, calls.last().data.getBoolean("oscillating"))
    }

    @Test
    fun mushroomClimateStepsTheTargetAndSwitchesToTheModes() {
        val st = store(entity("climate.ac", "heat", "temperature" to 22.0, "current_temperature" to 19.5, "target_temp_step" to 0.5,
            "temperature_unit" to "°C", "hvac_action" to "heating", "hvac_modes" to listOf("off", "heat", "cool", "auto")))
        val card = dev.glassworks.engine.vitro.cards.MushroomClimateCardView(context(st),
            card("""{"type": "custom:mushroom-climate-card", "entity": "climate.ac", "show_temperature_control": true, "hvac_modes": ["heat", "cool", "off"]}"""))
        idle()
        assertTrue(card.texts().toString(), "Heat \u2E31 19.5 °C" in card.texts() && "22.0" in card.texts())
        card.click("Increase")
        assertEquals("climate.set_temperature", calls.last().service)
        assertEquals(22.5, calls.last().data.getDouble("temperature"), 0.0)
        // The other control's button: the modes the entity has and the card lists, in HA's order (no auto).
        card.click("HVAC modes")
        card.click("cool")
        assertEquals("climate.set_hvac_mode", calls.last().service)
        assertEquals("cool", calls.last().data.getString("hvac_mode"))
        assertTrue(card.all().none { it.contentDescription == "auto" })
    }

    @Test
    fun aMushroomCardTakesARowForItsControlsOnlyWhenTheEntityHasThem() {
        val cfg = card("""{"type": "custom:mushroom-light-card", "entity": "light.x", "show_brightness_control": true}""")
        // An on/off light has no brightness to control: one row, as in Mushroom.
        assertEquals(1, dev.glassworks.engine.vitro.ui.HaGridSize.of(cfg, entity("light.x", "on", "supported_color_modes" to listOf("onoff"))).rows)
        assertEquals(2, dev.glassworks.engine.vitro.ui.HaGridSize.of(cfg, entity("light.x", "on", "supported_color_modes" to listOf("brightness"))).rows)
        assertEquals(1, dev.glassworks.engine.vitro.ui.HaGridSize.of(card("""{"type": "custom:mushroom-light-card", "entity": "light.x"}""")).rows)
    }

    @Test
    fun mushroomHumidifierTogglesAndShowsTheTarget() {
        val st = store(entity("humidifier.bedroom", "on", "humidity" to 45, "current_humidity" to 38, "min_humidity" to 30, "max_humidity" to 80))
        val card = MushroomHumidifierCardView(
            context(st),
            card("""{"type": "custom:mushroom-humidifier-card", "entity": "humidifier.bedroom", "show_target_humidity_control": true}"""),
        )
        idle()
        assertTrue(card.texts().any { "Target 45%" in it && "38%" in it })
        card.click("Toggle")
        assertEquals("humidifier.toggle", calls.last().service)
        assertEquals("humidifier.bedroom", calls.last().entityId)
    }

    @Test
    fun mushroomNumberStepsWithinTheEntityRange() {
        val st = store(entity("input_number.volume", "10", "min" to 0, "max" to 11, "step" to 1))
        val card = MushroomNumberCardView(
            context(st),
            card("""{"type": "custom:mushroom-number-card", "entity": "input_number.volume", "display_mode": "buttons"}"""),
        )
        idle()
        assertTrue("10" in card.texts())
        card.click("Increase")
        assertEquals("input_number.set_value", calls.last().service)
        assertEquals(11.0, calls.last().data.getDouble("value"), 0.0)
        st.putLocal(entity("input_number.volume", "11", "min" to 0, "max" to 11, "step" to 1))
        idle()
        card.click("Increase")
        assertEquals(11.0, calls.last().data.getDouble("value"), 0.0) // clamped at max
        card.click("Decrease")
        assertEquals(10.0, calls.last().data.getDouble("value"), 0.0)

        val num = store(entity("number.fan_speed", "2.5", "min" to 0, "max" to 5, "step" to 0.5))
        val n = MushroomNumberCardView(context(num), card("""{"type": "custom:mushroom-number-card", "entity": "number.fan_speed", "display_mode": "buttons"}"""))
        idle()
        n.click("Decrease")
        assertEquals("number.set_value", calls.last().service)
        assertEquals(2.0, calls.last().data.getDouble("value"), 1e-9)
    }

    @Test
    fun mushroomSelectPicksAnOption() {
        val st = store(entity("input_select.mode", "Home", "options" to listOf("Home", "Away", "Night")))
        val card = MushroomSelectCardView(context(st), card("""{"type": "custom:mushroom-select-card", "entity": "input_select.mode"}"""))
        idle()
        assertTrue("Home" in card.texts())
        assertEquals(listOf("Home", "Away", "Night"), card.options())
        card.choose("Night")
        assertEquals("input_select.select_option", calls.last().service)
        assertEquals("Night", calls.last().data.getString("option"))

        val sel = store(entity("select.preset", "eco", "options" to listOf("eco", "boost")))
        MushroomSelectCardView(context(sel), card("""{"type": "custom:mushroom-select-card", "entity": "select.preset"}""")).choose("boost")
        assertEquals("select.select_option", calls.last().service)
    }

    @Test
    fun mushroomMediaPlayerShowsMediaInfoAndControls() {
        val st = store(
            entity("media_player.kitchen", "playing", "media_title" to "So What", "media_artist" to "Miles Davis", "volume_level" to 0.42, "is_volume_muted" to false),
        )
        val card = MushroomMediaPlayerCardView(
            context(st),
            card(
                """{"type": "custom:mushroom-media-player-card", "entity": "media_player.kitchen", "use_media_info": true,
                   "show_volume_level": true, "media_controls": ["on_off", "previous", "play_pause_stop", "next"],
                   "volume_controls": ["volume_mute", "volume_set", "volume_buttons"]}""",
            ),
        )
        idle()
        assertTrue("So What" in card.texts())
        assertTrue(card.texts().any { it == "Miles Davis · 42%" })
        card.click("Pause"); assertEquals("media_player.media_play_pause", calls.last().service)
        card.click("Next track"); assertEquals("media_player.media_next_track", calls.last().service)
        card.click("Previous track"); assertEquals("media_player.media_previous_track", calls.last().service)
        card.click("Power"); assertEquals("media_player.turn_off", calls.last().service)
        card.click("Mute")
        assertEquals("media_player.volume_mute", calls.last().service)
        assertTrue(calls.last().data.getBoolean("is_volume_muted"))
        card.click("Volume up"); assertEquals("media_player.volume_up", calls.last().service)
    }

    // ---- mini-media-player ----

    @Test
    fun miniMediaPlayerShowsTheTrackAndControlsThePlayer() {
        val st = store(
            entity("media_player.lounge", "playing", "media_title" to "Blue in Green", "media_artist" to "Bill Evans",
                "volume_level" to 0.3, "media_duration" to 300, "media_position" to 150),
        )
        val card = MiniMediaPlayerCardView(
            context(st),
            card("""{"type": "custom:mini-media-player", "entity": "media_player.lounge", "artwork": "cover", "name": "Lounge", "hide": {"stop": false}}"""),
        )
        idle()
        assertTrue("Lounge" in card.texts())
        assertTrue("Blue in Green — Bill Evans" in card.texts())
        card.click("Pause"); assertEquals("media_player.media_play_pause", calls.last().service)
        card.click("Next track"); assertEquals("media_player.media_next_track", calls.last().service)
        card.click("Stop"); assertEquals("media_player.media_stop", calls.last().service)
        card.click("Mute")
        assertEquals("media_player.volume_mute", calls.last().service)
        card.click("Power")
        assertEquals("media_player.turn_off", calls.last().service)
        assertEquals("media_player.lounge", calls.last().entityId)

        st.putLocal(entity("media_player.lounge", "off"))
        idle()
        card.click("Power")
        assertEquals("media_player.turn_on", calls.last().service)

        val stateless = MiniMediaPlayerCardView(
            context(store(entity("media_player.den", "paused", "volume_level" to 0.5))),
            card("""{"type": "custom:mini-media-player", "entity": "media_player.den", "volume_stateless": true}"""),
        )
        idle()
        stateless.click("Volume up"); assertEquals("media_player.volume_up", calls.last().service)
        stateless.click("Play"); assertEquals("media_player.media_play_pause", calls.last().service)
    }

    // ---- auto-entities ----

    private fun builder(store: EntityStore): (CardConfig) -> View = { c ->
        // A stand-in for VitroView.buildCard: a surface listing the entities it was handed.
        object : CardSurface(context(store)) {}.apply {
            tag = c
            addView(TextView(context).apply { text = c.entities().joinToString(",") { it.entity } })
        }
    }

    @Test
    fun autoEntitiesFiltersSortsAndFollowsTheStore() {
        val st = store(
            entity("light.kitchen", "on", "friendly_name" to "Kitchen"),
            entity("light.bedroom", "off", "friendly_name" to "Bedroom"),
            entity("light.attic", "on", "friendly_name" to "Attic"),
            entity("light.hall_ignored", "on", "friendly_name" to "Hall"),
            entity("switch.fan", "on", "friendly_name" to "Fan"),
        )
        val view = AutoEntitiesCardView(
            context(st),
            card(
                """{"type": "custom:auto-entities", "card": {"type": "entities", "title": "Lights on"},
                   "filter": {"include": [{"domain": "light", "state": "on"}], "exclude": [{"entity_id": "*_ignored"}]},
                   "sort": {"method": "name"}, "show_empty": false}""",
            ),
            builder(st),
        )
        idle()
        assertEquals(listOf("light.attic", "light.kitchen"), view.matchedEntities)
        val inner = view.getChildAt(0).tag as CardConfig
        assertEquals("entities", inner.type)
        assertEquals("Lights on", inner.title)
        assertEquals(listOf("light.attic", "light.kitchen"), inner.entities().map { it.entity })

        st.putLocal(entity("light.bedroom", "on", "friendly_name" to "Bedroom"))
        idle()
        assertEquals(listOf("light.attic", "light.bedroom", "light.kitchen"), view.matchedEntities)

        for (id in listOf("light.attic", "light.bedroom", "light.kitchen")) st.putLocal(entity(id, "off"))
        idle()
        assertEquals(emptyList<String>(), view.matchedEntities)
        assertEquals(View.GONE, view.visibility)
    }

    @Test
    fun autoEntitiesHearsStatesThatLandWhileItsFirstBuildRuns() {
        // HA's snapshot is written on the socket's thread: it can land after the card read the store, while it builds
        // what it matched, and before it would listen. HA's auto-entities renders again whenever hass changes.
        val st = store()
        var landed = false
        val view = AutoEntitiesCardView(context(st), card("""{"type": "custom:auto-entities", "filter": {"include": [{"domain": "light"}]}}""")) { c ->
            if (!landed) {
                landed = true
                st.putLocal(entity("light.kitchen", "on", "friendly_name" to "Kitchen"))
            }
            builder(st)(c)
        }
        idle()
        assertEquals(listOf("light.kitchen"), view.matchedEntities)
    }

    @Test
    fun autoEntitiesValueRulesAndCardsParam() {
        val temps = listOf(
            entity("sensor.a_temp", "19.5", "device_class" to "temperature"),
            entity("sensor.b_temp", "24", "device_class" to "temperature"),
            entity("sensor.c_temp", "21", "device_class" to "temperature"),
            entity("sensor.power", "300", "device_class" to "power"),
        )
        val st = store(*temps.toTypedArray())
        val raw = JSONObject(
            """{"filter": {"include": [{"attributes": {"device_class": "temperature"}, "state": "> 20", "options": {"type": "tile"}}]},
                "sort": {"method": "state", "numeric": true, "reverse": true}, "card": {"type": "grid", "columns": 2}, "card_param": "cards"}""",
        )
        val matches = AutoEntitiesFilter.evaluate(raw, st.all().values, st::get)
        assertEquals(listOf("sensor.b_temp", "sensor.c_temp"), matches.map { it.entityId })
        assertTrue(AutoEntitiesFilter.matchValue("/^sensor\\.[ab]_/", "sensor.a_temp"))
        assertTrue(AutoEntitiesFilter.matchValue("!= off", "on"))
        assertTrue(AutoEntitiesFilter.matchValue(true, "on"))
        // An attribute holding JSON true reads "true": the same boolean pattern matches it.
        assertTrue(AutoEntitiesFilter.matchValue(true, "true"))
        assertTrue(AutoEntitiesFilter.matchValue(false, "false"))
        assertFalse(AutoEntitiesFilter.matchValue(true, "false"))
        assertFalse(AutoEntitiesFilter.matchValue("<= 5", "unavailable"))
        // A key Vitro can't evaluate (here `area`) never widens an include to everything.
        assertTrue(AutoEntitiesFilter.evaluate(JSONObject("""{"filter": {"include": [{"area": "kitchen"}]}}"""), st.all().values, st::get).isEmpty())
        // ...not even under `not`: "sensors not in the kitchen" can't be told apart, so it matches nothing.
        assertTrue(
            AutoEntitiesFilter.evaluate(
                JSONObject("""{"filter": {"include": [{"domain": "sensor", "not": {"area": "kitchen"}}]}}"""), st.all().values, st::get,
            ).isEmpty(),
        )
        // A malformed or empty rule under `not` matches nothing, rather than everything.
        for (bad in listOf(
            """{"domain": "sensor", "not": {"attributes": "invalid"}}""", """{"domain": "sensor", "not": {}}""",
            """{"domain": "sensor", "not": {"state": null}}""", """{"domain": "sensor", "not": {"state": {"x": 1}}}""",
        )) {
            assertTrue(bad, AutoEntitiesFilter.evaluate(JSONObject("""{"filter": {"include": [$bad]}}"""), st.all().values, st::get).isEmpty())
        }
        // A valid negated condition still works.
        assertTrue(AutoEntitiesFilter.evaluate(JSONObject("""{"filter": {"include": [{"domain": "sensor", "not": {"state": "nope"}}]}}"""), st.all().values, st::get).isNotEmpty())
        // A rule's own sort and count pick from its matches before the global list is built.
        val top = AutoEntitiesFilter.evaluate(
            JSONObject(
                """{"filter": {"include": [{"attributes": {"device_class": "temperature"}, "sort": {"method": "state", "numeric": true, "reverse": true, "count": 1}}]}}""",
            ),
            st.all().values, st::get,
        )
        assertEquals(1, top.size)

        var built: CardConfig? = null
        AutoEntitiesCardView(context(st), CardConfig("custom:auto-entities", raw)) { c -> built = c; View(activity) }
        val inner = checkNotNull(built)
        assertEquals("grid", inner.type)
        assertEquals(listOf("tile", "tile"), inner.childCards.map { it.type })
        assertEquals(listOf("sensor.b_temp", "sensor.c_temp"), inner.childCards.map { it.entity })
    }

    @Test
    fun mushroomsDisabledColorIsItsOwnNotHasInactiveColor() {
        // Mushroom 5.3: --rgb-disabled is var(--mush-rgb-disabled), else 111, 111, 111 dark and 189, 189, 189 light.
        val dark = context(store())
        assertEquals(android.graphics.Color.rgb(111, 111, 111), dev.glassworks.engine.vitro.cards.mushroomDisabledColor(dark))
        val light = context(store(), ThemeResolver.resolve(ThemeCatalog(), false))
        assertEquals(android.graphics.Color.rgb(189, 189, 189), dev.glassworks.engine.vitro.cards.mushroomDisabledColor(light))
        val themed = context(store(), ThemeResolver.resolve(ThemeCatalog(defaultTheme = "t",
            themes = mapOf("t" to ThemeCatalog.ThemeDefinition(base = mapOf("mush-rgb-disabled" to "10, 20, 30")))), true))
        assertEquals(android.graphics.Color.rgb(10, 20, 30), dev.glassworks.engine.vitro.cards.mushroomDisabledColor(themed))
        // A card's own card_mod (or an enclosing card's) outranks the theme while the card is built.
        themed.cardVars = mapOf("mush-rgb-disabled" to "40, 50, 60")
        assertEquals(android.graphics.Color.rgb(40, 50, 60), dev.glassworks.engine.vitro.cards.mushroomDisabledColor(themed))
        // A value with a bad channel is dropped whole (the theme's applies), not read from its other three numbers.
        themed.cardVars = mapOf("mush-rgb-disabled" to "10, invalid, 20, 30")
        assertEquals(android.graphics.Color.rgb(10, 20, 30), dev.glassworks.engine.vitro.cards.mushroomDisabledColor(themed))
        themed.cardVars = mapOf("mush-rgb-disabled" to "1, x, 2")
        assertEquals(android.graphics.Color.rgb(10, 20, 30), dev.glassworks.engine.vitro.cards.mushroomDisabledColor(themed))
    }

    // ---- stack-in-card / vertical-stack-in-card / layout-card ----

    @Test
    fun stackInCardDrawsItsChildrenOnOneSurface() {
        val st = store()
        val cfg = card(
            """{"type": "custom:stack-in-card", "title": "Living room", "mode": "horizontal",
               "cards": [{"type": "tile", "entity": "light.a"}, {"type": "tile", "entity": "light.b"}]}""",
        )
        assertEquals(2, cfg.childCards.size)
        val stack = StackInCardView(context(st), cfg, builder(st))
        assertTrue("Living room" in stack.texts())
        assertEquals(2, stack.children.size)
        // stack-in-card's inline style: no fill, no rounding, no shadow; the card keeps its (theme's) border.
        for (child in stack.children) {
            val surface = child.background as android.graphics.drawable.GradientDrawable
            assertEquals("no fill", android.graphics.Color.TRANSPARENT, surface.color!!.defaultColor)
            assertEquals("square", 0f, surface.cornerRadius, 0f)
            assertEquals(0f, child.elevation, 0f)
            assertTrue((child as CardSurface).seamless)
        }
        // As VitroView builds a child: its own card (a Markdown card for markdown), its card_mod applied, then the stack.
        val styled: (CardConfig) -> View = { c ->
            val v = if (c.type == "markdown") dev.glassworks.engine.vitro.cards.MarkdownCardView(context(st), c) else builder(st)(c)
            v.also { (it as CardSurface).applyCardMod(c) }
        }
        // A child's card_mod `border: none` takes the border off, and its background can't come back.
        val bare = StackInCardView(context(st), card("""{"type": "custom:stack-in-card", "cards": [{"type": "tile", "entity": "light.a",
            "card_mod": {"style": "ha-card { background: red; border: none; }"}}]}"""), styled)
        assertNull("no border, no fill", bare.children[0].background)
        // A text-only Markdown card has no border of its own (`.text-only`), in a stack too.
        val heading = StackInCardView(context(st), card("""{"type": "custom:stack-in-card", "cards": [
            {"type": "markdown", "content": "Climate", "text_only": true}, {"type": "tile", "entity": "light.a"}]}"""), styled)
        assertNull(heading.children[0].background)
        assertNotNull(heading.children[1].background)
        assertNotNull("the stack keeps its own surface", stack.background)
        val row = stack.children[0].parent as LinearLayout
        assertEquals(LinearLayout.HORIZONTAL, row.orientation)

        val kept = VerticalStackInCardView(
            context(st),
            card("""{"type": "custom:vertical-stack-in-card", "keep": {"background": true}, "cards": [{"type": "tile"}, {"type": "tile"}]}"""),
            builder(st),
        )
        assertNotNull(kept.children[0].background)
        assertEquals(LinearLayout.VERTICAL, (kept.children[0].parent as LinearLayout).orientation)
    }

    @Test
    fun aStackInCardKeepsAnAutoEntitiesReplacementSeamless() {
        val st = store(entity("light.a", "on"))
        lateinit var auto: AutoEntitiesCardView
        val inner = builder(st)
        val build: (CardConfig) -> View = { c ->
            if (c.type == "custom:auto-entities") AutoEntitiesCardView(context(st), c, inner).also { auto = it } else inner(c)
        }
        StackInCardView(context(st), card("""{"type": "custom:stack-in-card", "cards": [
            {"type": "custom:auto-entities", "card": {"type": "entities"}, "filter": {"include": [{"domain": "light"}]}}]}"""), build)
        val first = auto.getChildAt(0) as dev.glassworks.engine.vitro.ui.CardSurface
        assertTrue(first.seamless)
        // Another light matches: auto-entities builds a new inner card, which shares the stack's surface too.
        st.putLocal(entity("light.b", "on"))
        idle()
        val replacement = auto.getChildAt(0) as dev.glassworks.engine.vitro.ui.CardSurface
        assertTrue("rebuilt", replacement !== first)
        assertTrue(replacement.seamless)
    }

    @Test
    fun repeatedControlsAreBuiltOnce() {
        // A repeated entry would attach one icon view to two buttons (Android throws): each control appears once.
        val st = store(
            entity("media_player.den", "playing", "supported_features" to 0xFFFF),
            entity("vacuum.robo", "docked", "supported_features" to 0xFFFF),
        )
        MushroomMediaPlayerCardView(context(st), card("""{"type": "custom:mushroom-media-player-card", "entity": "media_player.den",
            "media_controls": ["play_pause_stop", "play_pause_stop", "next"], "volume_controls": ["volume_mute", "volume_mute"]}"""))
        MushroomVacuumCardView(context(st), card("""{"type": "custom:mushroom-vacuum-card", "entity": "vacuum.robo",
            "commands": ["start_pause", "start_pause", "return_home"]}"""))
    }

    @Test
    fun layoutCardGridUsesLayoutCardsMarginsAndNoGapByDefault() {
        // HA (layout-card's grid-layout) at 512 px: margin 0 4, padding 4 0, no gap, each card in 4 4 8 inside its
        // track, so two 1fr tiles are 244 px wide, 8 px apart, 8 px in from the top and the left.
        val st = store()
        val grid = LayoutCardView(context(st), card("""{"type": "custom:layout-card", "layout_type": "custom:grid-layout",
            "layout": {"grid-template-columns": "1fr 1fr"}, "cards": [{"type": "tile"}, {"type": "tile"}, {"type": "tile"}]}"""),
            builder(st), widthDp = 512)
        val dp = activity.resources.displayMetrics.density
        grid.measure(View.MeasureSpec.makeMeasureSpec((512 * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
        val a = grid.tracks[0].getChildAt(0)
        val b = grid.tracks[0].getChildAt(1)
        assertEquals(244f, a.width / dp, 1f)
        assertEquals(244f, b.width / dp, 1f)
        assertEquals(8f, (a.left + grid.tracks[0].left) / dp, 1f)
        assertEquals(8f, (b.left - a.right) / dp, 1f)
        assertEquals(8f, (a.top + grid.tracks[0].top) / dp, 1f)
        // The second row: the first's bottom margin (8) and the next card's top margin (4), no gap.
        assertEquals(12f, (grid.tracks[1].getChildAt(0).top + grid.tracks[1].top - (a.bottom + grid.tracks[0].top)) / dp, 1f)
    }

    /** The cards' names (their configs' `title`) column by column, after a measure at [widthDp]. */
    private fun columns(json: String, widthDp: Int): List<List<String?>> {
        val st = store()
        val v = LayoutCardView(context(st), card(json), builder(st), widthDp = widthDp)
        val dp = activity.resources.displayMetrics.density
        v.measure(View.MeasureSpec.makeMeasureSpec((widthDp * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v.tracks.map { col -> (0 until col.childCount).map { (col.getChildAt(it).tag as CardConfig).title } }
    }

    @Test
    fun layoutCardsColumnLayoutsPlaceCardsAsLayoutCardDoes() {
        fun e(title: String, n: Int) = """{"type": "entities", "title": "$title", "entities": [${List(n) { "\"light.l$it\"" }.joinToString()}]}"""
        val brk = """{"type": "custom:layout-break"}"""
        fun layout(type: String, vararg cards: String) =
            """{"type": "custom:layout-card", "layout_type": "custom:$type-layout", "layout": {"width": 200}, "cards": [${cards.joinToString()}]}"""
        // Masonry at 512: two columns. A (5 units) fills the first; B (3) goes to the shorter one, and C joins B because
        // that column is still under min_height (5).
        assertEquals(listOf(listOf("A"), listOf("B", "C")), columns(layout("masonry", e("A", 3), e("B", 1), e("C", 2)), 512))
        // Horizontal deals round robin; a break starts over at the first column.
        assertEquals(listOf(listOf("A", "C"), listOf("B")), columns(layout("horizontal", e("A", 1), e("B", 1), e("C", 1)), 512))
        assertEquals(listOf(listOf("A", "B"), listOf("C")), columns(layout("horizontal", e("A", 1), brk, e("B", 1), e("C", 1)), 512))
        // Vertical keeps one column until a break; the empty columns are dropped.
        assertEquals(listOf(listOf("A", "B"), listOf("C")), columns(layout("vertical", e("A", 1), e("B", 1), brk, e("C", 1)), 512))
        assertEquals(listOf(listOf("A", "B", "C")), columns(layout("vertical", e("A", 1), e("B", 1), e("C", 1)), 512))
        // Under one column width: one column.
        assertEquals(listOf(listOf("A", "B", "C")), columns(layout("masonry", e("A", 3), e("B", 1), e("C", 2)), 190))

        // HA at 512: margin 0 4 and padding 4 0 leave 504, two 252 px columns; in a column the cards' 4 4 8 margins
        // collapse to 8 between two cards.
        val st = store()
        val v = LayoutCardView(context(st), card(layout("masonry", e("A", 3), e("B", 1), e("C", 2))), builder(st), widthDp = 512)
        val dp = activity.resources.displayMetrics.density
        v.measure(View.MeasureSpec.makeMeasureSpec((512 * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        assertEquals(listOf(252f, 252f), v.tracks.map { it.width / dp })
        val (b, c) = v.tracks[1].getChildAt(0) to v.tracks[1].getChildAt(1)
        assertEquals(8f, (c.top - b.bottom) / dp, 0.5f)
        assertEquals(244f, b.width / dp, 0.5f)
    }

    @Test
    fun aSqueezedCoverRowKeepsTheNamesFirstLetterAndSpacedButtons() {
        // HA in a 244 px entities card: the name's box shrinks to its padding, the three buttons (a space apart) run
        // past the row, and Chrome still draws the first letter in the 8 px end padding.
        val st = store(entity("cover.kitchen_blind", "open", "friendly_name" to "Kitchen Blind"))
        val v = dev.glassworks.engine.vitro.cards.EntitiesCardView(context(st), card("""{"type": "entities", "entities": ["cover.kitchen_blind"]}"""))
        val dp = activity.resources.displayMetrics.density
        fun lay(w: Int) {
            v.measure(View.MeasureSpec.makeMeasureSpec((w * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            idle()
            v.measure(View.MeasureSpec.makeMeasureSpec((w * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        }
        val name = v.all().filterIsInstance<TextView>().single { it.text == "Kitchen Blind" }
        lay(208)
        assertEquals(8f, name.width / dp, 0.5f)
        assertNull("drawn whole, clipped", name.ellipsize)
        val buttons = v.all().filter { it.contentDescription in setOf("Open", "Stop", "Close") }
        assertEquals(3, buttons.size)
        assertEquals("a space apart", 48f + name.paint.measureText(" ") / dp, (buttons[1].left - buttons[0].left) / dp, 1f)
        // With room, the usual ellipsis.
        lay(400)
        assertTrue(name.width > 8 * dp)
        assertEquals(android.text.TextUtils.TruncateAt.END, name.ellipsize)
    }

    @Test
    fun cardSizesFollowHasGetCardSize() {
        fun size(json: String) = dev.glassworks.engine.vitro.model.HaCardSize.of(card(json)).toInt()
        assertEquals(5, size("""{"type": "entities", "title": "T", "entities": ["a.a", "a.b", "a.c"]}"""))
        assertEquals(1, size("""{"type": "entities", "entities": []}"""))
        assertEquals(3, size("""{"type": "tile", "entity": "a.a", "features": [{"type": "x"}, {"type": "y"}]}"""))
        assertEquals(2, size("""{"type": "tile", "entity": "a.a", "vertical": true, "features": [{"type": "x"}], "features_position": "inline"}"""))
        // 7 entities in 5 columns: 2 rows of 3 lines, and the title's 2.
        assertEquals(8, size("""{"type": "glance", "title": "G", "entities": ["a.a", "a.b", "a.c", "a.d", "a.e", "a.f", "a.g"]}"""))
        assertEquals(5, size("""{"type": "button", "entity": "a.a"}"""))
        assertEquals(2, size("""{"type": "markdown", "content": "1\n2\n3\n4"}"""))
        assertEquals(1, size("""{"type": "custom:anything"}"""))
    }

    @Test
    fun mushroomCardsWeighTheirLayoutAndControlsInMasonry() {
        // MushroomBaseCard.getCardSize (v5.3.0): 1, +1 vertical, +1 for a row of controls unless horizontal or collapsible.
        fun size(json: String, s: EntityState? = null) = dev.glassworks.engine.vitro.model.HaCardSize.of(card(json)) { s }.toInt()
        assertEquals(1, size("""{"type": "custom:mushroom-entity-card", "entity": "light.a"}"""))
        assertEquals(2, size("""{"type": "custom:mushroom-entity-card", "entity": "light.a", "vertical": true}"""))
        val dims = EntityState("light.a", "on", mapOf("supported_color_modes" to listOf("brightness")))
        val onOff = EntityState("light.a", "on", mapOf("supported_color_modes" to listOf("onoff")))
        val bright = """{"type": "custom:mushroom-light-card", "entity": "light.a", "show_brightness_control": true}"""
        assertEquals(2, size(bright, dims))
        // A light that can't dim draws no control, so it stays 1, as Mushroom's _controls reads the state.
        assertEquals(1, size(bright, onOff))
        assertEquals(3, size("""{"type": "custom:mushroom-light-card", "entity": "light.a", "show_brightness_control": true, "layout": "vertical"}""", dims))
        assertEquals(1, size("""{"type": "custom:mushroom-light-card", "entity": "light.a", "show_brightness_control": true, "layout": "horizontal"}""", dims))
        assertEquals(1, size("""{"type": "custom:mushroom-light-card", "entity": "light.a", "show_brightness_control": true, "collapsible_controls": true}""", dims))
        assertEquals(2, size("""{"type": "custom:mushroom-select-card", "entity": "select.a"}"""))
        // The cover card overrides getCardSize with 1.
        assertEquals(1, size("""{"type": "custom:mushroom-cover-card", "entity": "cover.a", "show_position_control": true}"""))
    }

    @Test
    fun layoutCardLaysOutAGridWithSpans() {
        val st = store()
        val cfg = card(
            """{"type": "custom:layout-card", "layout_type": "custom:grid-layout",
               "layout": {"grid-template-columns": "repeat(3, 1fr)", "grid-gap": "12px"},
               "cards": [{"type": "tile", "view_layout": {"grid-column": "span 2"}}, {"type": "tile"}, {"type": "tile"}, {"type": "tile", "view_layout": {"grid-column": "1 / -1"}}]}""",
        )
        val grid = LayoutCardView(context(st), cfg, builder(st), widthDp = 1280)
        assertEquals(3, grid.tracks.size) // [span 2, 1], [1 + filler], [full width]
        assertEquals(2, grid.tracks[0].childCount)
        assertEquals(2f, (grid.tracks[0].getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight, 0f)
        assertEquals(3f, (grid.tracks[2].getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight, 0f)
        assertEquals(
            listOf(LayoutCardView.Track(0f, 200f), LayoutCardView.Track(1f, 0f)),
            LayoutCardView.parseColumns("200px 1fr", 1280),
        )
        assertEquals(4, LayoutCardView.parseColumns("repeat(auto-fill, minmax(300px, 1fr))", 1280).size)
        // A percentage is of the grid's width, a fixed size; as a minmax maximum it still sizes the track.
        assertEquals(listOf(LayoutCardView.Track(0f, 200f), LayoutCardView.Track(1f, 0f)), LayoutCardView.parseColumns("25% 1fr", 800))
        assertEquals(LayoutCardView.Track(0f, 400f), LayoutCardView.parseColumns("minmax(0px, 50%) 1fr", 800)[0])
        assertEquals(LayoutCardView.Track(1f, 0f, 100f), LayoutCardView.parseColumns("minmax(100px, 1fr)", 800)[0])

        // In a half-width column, a percentage track is of the grid's measured width, not the display's.
        val half = LayoutCardView(
            context(st),
            card("""{"type": "custom:layout-card", "layout_type": "custom:grid-layout", "layout": {"grid-template-columns": "50% 1fr", "grid-gap": "0px"},
                "cards": [{"type": "tile"}, {"type": "tile"}]}"""),
            builder(st),
            widthDp = 1280,
        )
        val px = { dp: Int -> (dp * activity.resources.displayMetrics.density).toInt() }
        half.measure(View.MeasureSpec.makeMeasureSpec(px(640), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        half.layout(0, 0, half.measuredWidth, half.measuredHeight)
        val first = half.tracks[0].getChildAt(0)
        // grid-layout's 4 px side margins leave 632: the 50% track is 316, and the card's own 4 + 4 margin is in it.
        assertEquals(px(308).toFloat(), first.width.toFloat(), 2f)

        val horizontal = LayoutCardView(
            context(st),
            card("""{"type": "custom:layout-card", "layout_type": "custom:horizontal-layout", "layout": {"max_cols": 2}, "cards": [{"type": "tile"}, {"type": "tile"}, {"type": "tile"}]}"""),
            builder(st),
            widthDp = 1280,
        )
        // The columns are placed once the card knows its width.
        horizontal.measure(View.MeasureSpec.makeMeasureSpec(px(1280), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(listOf(2, 1), horizontal.tracks.map { it.childCount })
    }

    @Test
    fun containersCountTheirChildrenAndAutoEntitiesFollowsEveryEntity() {
        val view = LovelaceParser.parseView(
            0,
            JSONObject(
                """{"cards": [{"type": "custom:stack-in-card", "cards": [{"type": "tile"}, {"type": "custom:apexcharts-card"}]},
                   {"type": "custom:layout-card", "layout_type": "masonry", "cards": [{"type": "custom:mushroom-entity-card", "entity": "lock.a"}]},
                   {"type": "custom:auto-entities", "card": {"type": "entities"}, "filter": {"include": [{"domain": "light"}]}}]}""",
            ),
        )
        val tiers = CardRouter.tierCounts(view)
        assertEquals(1, tiers[CardTier.NATIVE_CORE])
        assertEquals(2, tiers[CardTier.NATIVE_HACS])
        assertEquals(1, tiers[CardTier.WEB_ISLAND])
        assertTrue(CardRouter.followsAllEntities(view))
        assertTrue("lock.a" in CardRouter.referencedEntities(view))
        assertEquals(30, CardRouter.NATIVE_HACS_TYPES.size)
    }
}
