/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.BrightnessDialView
import dev.glassworks.engine.vitro.cards.ButtonCardView
import dev.glassworks.engine.vitro.cards.EntitiesCardView
import dev.glassworks.engine.vitro.cards.GlanceBar
import dev.glassworks.engine.vitro.cards.GlanceCardView
import dev.glassworks.engine.vitro.cards.LightCardView
import dev.glassworks.engine.vitro.cards.PictureCardView
import dev.glassworks.engine.vitro.cards.PictureElementsCardView
import dev.glassworks.engine.vitro.cards.SensorGraphCardView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HaEntityName
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Default taps follow HA's DOMAINS_TOGGLE (a lock, a cover, a script or a siren opens More Info, never runs), and a
 * card's `name` in HA's name-picker form (`{type: device}`, `[{type: area}, {type: entity}]`) reads as HA joins it,
 * never as JSON text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ActionDefaultsAndNamesTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()
    private val calls = mutableListOf<String>()
    private val moreInfo = mutableListOf<String>()
    private val navigated = mutableListOf<String>()

    @Before
    fun setUp() {
        fun put(id: String, state: String, name: String) = store.putLocal(EntityState(id, state, mapOf("friendly_name" to name)))
        put("lock.front_door", "locked", "Front door lock")
        put("cover.garage", "closed", "Garage door")
        put("script.goodnight", "off", "Goodnight")
        put("siren.hall", "off", "Hall siren")
        put("group.downstairs", "on", "Downstairs")
        put("light.porch", "off", "Porch light")
        put("sensor.unregistered", "4", "Rain gauge")
        put("sensor.outdoor", "12", "Terrace temperature")
        HaDisplay.applyAreas(JSONArray("""[{"area_id": "porch", "name": "Porch", "floor_id": "ground"}]"""))
        HaDisplay.applyFloors(JSONArray("""[{"floor_id": "ground", "name": "Ground floor", "level": 0}]"""))
        HaDisplay.applyDevices(JSONArray("""[
            {"id": "d-hub", "name": "Porch hub", "area_id": "porch"},
            {"id": "d-lamp", "name": "Lantern", "name_by_user": "Porch lantern", "parent_device_id": "d-hub"},
            {"id": "d-lock", "name": "Front door", "area_id": "porch"}
        ]"""))
        HaDisplay.applyRegistry(JSONObject().put("entities", JSONArray("""[
            {"ei": "light.porch", "di": "d-lamp", "en": "Light"},
            {"ei": "lock.front_door", "di": "d-lock"}
        ]""")))
    }

    @After
    fun forget() = HaDisplay.reset()

    private fun ctx() = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN },
        callService = { domain, service, data, _ -> calls += "$domain.$service ${data?.optString("entity_id")}" },
        navigate = { navigated += it }, openMoreInfo = { moreInfo += it },
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
    )

    private fun card(json: String) = LovelaceParser.parseCard(JSONObject(json))

    private fun all(v: View): List<View> =
        listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    private fun texts(v: View): List<String> = all(v).flatMap(::drawnWords)

    private fun clear() { calls.clear(); moreInfo.clear(); navigated.clear() }

    // --- Default taps (0f) ---

    @Test
    fun aButtonCardOnALockCoverScriptOrSirenOpensMoreInfoAndNeverRuns() {
        for (id in listOf("lock.front_door", "cover.garage", "script.goodnight", "siren.hall")) {
            clear()
            ButtonCardView(ctx(), card("""{"type": "button", "entity": "$id"}""")).performClick()
            assertEquals(id, listOf(id), moreInfo)
            assertEquals(id, emptyList<String>(), calls)
        }
    }

    @Test
    fun aButtonCardOnAGroupOrALightToggles() {
        ButtonCardView(ctx(), card("""{"type": "button", "entity": "group.downstairs"}""")).performClick()
        ButtonCardView(ctx(), card("""{"type": "button", "entity": "light.porch"}""")).performClick()
        assertEquals(listOf("homeassistant.toggle group.downstairs", "light.toggle light.porch"), calls)
        assertTrue(moreInfo.isEmpty())
        // A configured tap still wins.
        clear()
        ButtonCardView(ctx(), card("""{"type": "button", "entity": "lock.front_door", "tap_action": {"action": "toggle"}}""")).performClick()
        assertEquals(listOf("lock.unlock lock.front_door"), calls)
    }

    @Test
    fun aStateIconOnAFloorPlanOpensMoreInfoEvenForALight() {
        val card = PictureElementsCardView(ctx(), card("""{"type": "picture-elements", "image": "/x.png", "elements": [
            {"type": "state-icon", "entity": "light.porch", "style": {"top": "30%", "left": "30%"}}]}"""))
        (all(card).filterIsInstance<MdiIconView>().single().parent as View).performClick()
        assertEquals(listOf("light.porch"), moreInfo)
        assertTrue(calls.toString(), calls.isEmpty())
    }

    private fun glanceRows(card: View): Pair<LinearLayout, LinearLayout> {
        val bar = all(card).filterIsInstance<GlanceBar>().single()
        return bar.getChildAt(1) as LinearLayout to bar.getChildAt(2) as LinearLayout
    }

    @Test
    fun pictureGlanceTogglesOnlyHaToggleDomainsAndHonorsForceDialog() {
        val glance = PictureCardView(ctx(), card("""{"type": "picture-glance", "title": "Porch", "image": "/x.png",
            "entities": ["lock.front_door", "cover.garage", "light.porch"]}"""))
        val (dialog, toggle) = glanceRows(glance)
        assertEquals(2, dialog.childCount)
        assertEquals(1, toggle.childCount)
        dialog.getChildAt(0).performClick()
        dialog.getChildAt(1).performClick()
        assertEquals(listOf("lock.front_door", "cover.garage"), moreInfo)
        assertTrue(calls.toString(), calls.isEmpty())
        toggle.getChildAt(0).performClick()
        assertEquals(listOf("light.toggle light.porch"), calls)

        clear()
        val forced = PictureCardView(ctx(), card("""{"type": "picture-glance", "image": "/x.png", "force_dialog": true,
            "entities": ["light.porch"]}"""))
        val (fDialog, fToggle) = glanceRows(forced)
        assertEquals(0, fToggle.childCount)
        fDialog.getChildAt(0).performClick()
        assertEquals(listOf("light.porch"), moreInfo)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun pictureGlanceSpreadsTitleDialogAndToggleRowsLikeSpaceBetween() {
        val glance = PictureCardView(ctx(), card("""{"type": "picture-glance", "title": "Porch", "image": "/x.png",
            "entities": ["lock.front_door", "light.porch"]}"""))
        val bar = all(glance).filterIsInstance<GlanceBar>().single()
        val width = 900
        bar.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        bar.layout(0, 0, width, bar.measuredHeight)
        val caption = bar.getChildAt(0)
        val (dialog, toggle) = glanceRows(glance)
        assertEquals(bar.paddingLeft, caption.left)
        assertEquals(width - bar.paddingRight, toggle.right)
        // The free space splits evenly: the dialog row sits between the title and the toggle row.
        val before = dialog.left - caption.right
        val after = toggle.left - dialog.right
        assertTrue("$before vs $after", kotlin.math.abs(before - after) <= 1)
    }

    @Test
    fun theLightCardsCenterButtonRunsItsTapAction() {
        LightCardView(ctx(), card("""{"type": "light", "entity": "light.porch"}""")).let { c ->
            all(c).filterIsInstance<BrightnessDialView>().single().performClick()
        }
        assertEquals(listOf("light.toggle light.porch"), calls)
        clear()
        LightCardView(ctx(), card("""{"type": "light", "entity": "light.porch",
            "tap_action": {"action": "navigate", "navigation_path": "/lovelace/porch"}}""")).let { c ->
            all(c).filterIsInstance<BrightnessDialView>().single().performClick()
        }
        assertEquals(listOf("/lovelace/porch"), navigated)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun theLightCardsCenterButtonHoldsToMoreInfoAndTheReleaseDoesntToggle() {
        val card = LightCardView(ctx(), card("""{"type": "light", "entity": "light.porch"}"""))
        // On screen, as on a panel: a detached view holds its delayed callbacks (the hold's timer) until it attaches.
        activity.setContentView(card)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val dial = all(card).filterIsInstance<BrightnessDialView>().single()
        assertTrue("the dial is laid out", dial.width > 0 && dial.height > 0)
        // A press on the light button, the middle of the dial (wherever the activity's layout put it).
        fun touch(action: Int) = android.view.MotionEvent.obtain(
            0L, android.os.SystemClock.uptimeMillis(), action, dial.width / 2f, dial.height / 2f, 0,
        ).also { dial.onTouchEvent(it); it.recycle() }
        touch(android.view.MotionEvent.ACTION_DOWN)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(
            android.view.ViewConfiguration.getLongPressTimeout().toLong() + 50, java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        touch(android.view.MotionEvent.ACTION_UP)
        assertEquals(listOf("light.porch"), moreInfo)
        assertTrue(calls.toString(), calls.isEmpty())
        // A short press is still a tap: it toggles.
        clear()
        touch(android.view.MotionEvent.ACTION_DOWN)
        touch(android.view.MotionEvent.ACTION_UP)
        assertEquals(listOf("light.toggle light.porch"), calls)
        assertTrue(moreInfo.isEmpty())
    }

    @Test
    fun aButtonRowOnALockOpensMoreInfo() {
        val entities = EntitiesCardView(ctx(), card("""{"type": "entities", "entities": [
            {"type": "button", "entity": "lock.front_door", "name": "Door"}]}"""))
        all(entities).filterIsInstance<TextView>().single { it.text.toString() == "Run" }.performClick()
        assertEquals(listOf("lock.front_door"), moreInfo)
        assertTrue(calls.isEmpty())
    }

    // --- Names (0h) ---

    @Test
    fun nameFormatsHaNamePickerPartsFromTheRegistries() {
        fun f(json: String, id: String = "light.porch", friendly: String? = "Porch light") =
            HaEntityName.format(JSONTokenerValue(json), id, friendly)
        assertEquals("Porch lantern", f("""{"type": "device"}"""))
        assertEquals("Porch Light", f("""[{"type": "area"}, {"type": "entity"}]"""))
        assertEquals("Ground floor · Porch hub Light", f("""[{"type": "floor"}, {"type": "text", "text": "·"}, {"type": "parent_device"}, {"type": "entity"}]"""))
        // Text alone needs no registry, and an entity on no registry entry is its friendly name.
        assertEquals("Hello there", f("""[{"type": "text", "text": "Hello"}, {"type": "text", "text": "there"}]""", id = "x.y", friendly = null))
        assertEquals("Rain gauge", f("""{"type": "entity"}""", id = "sensor.unregistered", friendly = "Rain gauge"))
        assertEquals("", f("""{"type": "device"}""", id = "sensor.unregistered", friendly = "Rain gauge"))
        // A device's main entity has no name of its own: its device names it, unless the name also asks for the device.
        assertEquals("Front door", f("""{"type": "entity"}""", id = "lock.front_door"))
        assertEquals("Front door", f("""[{"type": "device"}, {"type": "entity"}]""", id = "lock.front_door"))
        // The device's area (the entity's own is unset) and an unknown part, which HA drops.
        assertEquals("Porch", f("""[{"type": "area"}, {"type": "mystery"}]""", id = "lock.front_door"))
        // Plain values.
        assertEquals("Kitchen", HaEntityName.format("Kitchen", "light.porch", null))
        assertNull(HaEntityName.format(null, "light.porch", null))
        assertEquals("1984", HaEntityName.format(1984, "light.porch", null))
    }

    /** Parses [json] (an object or an array) the way org.json hands a config value to Vitro. */
    @Suppress("FunctionName")
    private fun JSONTokenerValue(json: String): Any = org.json.JSONTokener(json).nextValue()

    @Test
    fun theTileAndButtonCardsDrawANameObjectAsTextNeverJson() {
        val tile = TileCardView(ctx(), card("""{"type": "tile", "entity": "light.porch", "name": {"type": "device"}}"""))
        assertTrue(texts(tile).toString(), "Porch lantern" in texts(tile))
        val list = TileCardView(ctx(), card("""{"type": "tile", "entity": "light.porch", "name": [{"type": "area"}, {"type": "entity"}]}"""))
        assertTrue(texts(list).toString(), "Porch Light" in texts(list))
        val button = ButtonCardView(ctx(), card("""{"type": "button", "entity": "lock.front_door", "name": [{"type": "area"}, {"type": "text", "text": "lock"}]}"""))
        assertTrue(texts(button).toString(), "Porch lock" in texts(button))
        for (v in listOf(tile, list, button)) assertFalse(texts(v).toString(), texts(v).any { '{' in it || "\"type\"" in it })
    }

    @Test
    fun rowsBadgesAndGlanceCellsDrawANameObjectAsText() {
        val entities = EntitiesCardView(ctx(), card("""{"type": "entities", "entities": [
            {"entity": "light.porch", "name": {"type": "device"}},
            {"type": "button", "entity": "lock.front_door", "name": [{"type": "area"}, {"type": "entity"}]}]}"""))
        assertTrue(texts(entities).toString(), "Porch lantern" in texts(entities))
        assertTrue(texts(entities).toString(), "Porch Front door" in texts(entities))
        val glance = GlanceCardView(ctx(), card("""{"type": "glance", "entities": [{"entity": "light.porch", "name": [{"type": "area"}, {"type": "device"}]}]}"""))
        assertTrue(texts(glance).toString(), "Porch Porch lantern" in texts(glance))
        val badge = card("""{"type": "entity", "entity": "light.porch", "name": {"type": "entity"}, "show_state": false}""")
        assertEquals("Light", VitroView.badgeText(badge, store["light.porch"]))
        for (v in listOf(entities, glance)) assertFalse(texts(v).toString(), texts(v).any { "\"type\"" in it })
    }

    @Test
    fun callServiceAndPerformActionRowsKeepANameMadeOnlyOfEntityParts() {
        val entities = EntitiesCardView(ctx(), card("""{"type": "entities", "entities": [
            {"type": "call-service", "entity": "light.porch", "name": {"type": "entity"}, "action": "light.turn_on"},
            {"type": "perform-action", "entity": "lock.front_door", "name": {"type": "device"}, "action": "lock.lock"},
            {"type": "weblink", "url": "https://example.com/", "name": {"type": "entity"}}]}"""))
        assertTrue(texts(entities).toString(), "Light" in texts(entities))
        assertTrue(texts(entities).toString(), "Front door" in texts(entities))
        // A weblink resolves no entity, so its empty name still falls back to its URL.
        assertTrue(texts(entities).toString(), "https://example.com/" in texts(entities))
    }

    @Test
    fun miniGraphResolvesItsNameForItsEntityAndNamesLegendSeries() {
        // An entity on no registry entry: `{type: entity}` is its friendly name, not its object id or nothing.
        val graph = SensorGraphCardView(ctx(), card("""{"type": "custom:mini-graph-card", "entity": "sensor.outdoor",
            "name": {"type": "entity"}}"""))
        assertTrue(texts(graph).toString(), "Terrace temperature" in texts(graph))
        assertFalse(texts(graph).toString(), "outdoor" in texts(graph))
        // The primary entity taken from `entities`, and each series' own `name` in the legend.
        val series = SensorGraphCardView(ctx(), card("""{"type": "custom:mini-graph-card", "name": {"type": "entity"},
            "entities": [{"entity": "sensor.outdoor"}, {"entity": "sensor.unregistered", "name": "Rain"}]}"""))
        assertTrue(texts(series).toString(), "Terrace temperature" in texts(series))
        assertTrue(texts(series).toString(), "Rain" in texts(series))
        assertFalse(texts(series).toString(), "Rain gauge" in texts(series))
    }
}
