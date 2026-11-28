/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.HaGridSize
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The arms of `hui-tile-card` (frontend 20261028.5) that the parity harness can't see because they act rather than
 * draw: its action defaults, the icon's label, the pulse and the timer's blink, and its grid options. The table they
 * come from is engines/engine-vitro/docs/source-audit/tile.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TileSourcePortTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()
    private val calls = mutableListOf<String>()
    private val moreInfo = mutableListOf<String>()

    private fun ctx() = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN },
        callService = { domain, service, data, _ -> calls += "$domain.$service ${data?.optString("entity_id")}" },
        navigate = {}, openMoreInfo = { moreInfo += it },
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
    )

    private fun card(json: String) = LovelaceParser.parseCard(JSONObject(json))

    private fun put(id: String, state: String, attrs: Map<String, Any?> = emptyMap()) =
        store.putLocal(EntityState(id, state, mapOf("friendly_name" to id.substringAfter('.').replace('_', ' ')) + attrs))

    private fun all(v: View): List<View> =
        listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    /** The tile's icon box: the clickable parent of its glyph. */
    private fun icon(tile: View): View = all(tile).filterIsInstance<MdiIconView>().first().parent as View

    @Test
    fun aLongPressIsATapWithoutAHoldAction() {
        put("light.porch", "off")
        // getTileActionDefaults: no hold action, so the long press is the tap, as HA's action handler takes it.
        val tile = TileCardView(ctx(), card("""{"type": "tile", "entity": "light.porch", "tap_action": {"action": "toggle"}}"""))
        assertFalse(tile.isLongClickable)
        assertTrue(tile.isClickable)
        // The icon's own long press is its tap too (icon_hold_action is none).
        assertFalse(icon(tile).isLongClickable)
        // A configured hold action still runs.
        val held = TileCardView(ctx(), card("""{"type": "tile", "entity": "light.porch", "hold_action": {"action": "more-info"}}"""))
        assertTrue(held.isLongClickable)
        held.performLongClick()
        assertEquals(listOf("light.porch"), moreInfo)
    }

    @Test
    fun aTileWithNoActionIsNotAButton() {
        put("sensor.rain", "4")
        // hasTileCardAction false: ha-tile-container gets no button role and its ripple is off.
        val tile = TileCardView(ctx(), card("""{"type": "tile", "entity": "sensor.rain", "tap_action": {"action": "none"}}"""))
        assertFalse(tile.isClickable)
        assertTrue(TileCardView(ctx(), card("""{"type": "tile", "entity": "sensor.rain"}""")).isClickable)
    }

    @Test
    fun theIconIsNamedForWhatItsToggleDoes() {
        put("light.porch", "off")
        put("button.hallway_chime", "2026-11-28T09:00:00+00:00")
        put("scene.movie_night", "2026-11-28T09:00:00+00:00")
        fun label(id: String) = icon(TileCardView(ctx(), card("""{"type": "tile", "entity": "$id", "name": "X"}"""))).contentDescription.toString()
        assertEquals("Toggle X", label("light.porch"))
        assertEquals("Press X", label("button.hallway_chime"))
        assertEquals("Activate X", label("scene.movie_night"))
    }

    @Test
    fun alarmsAndJammedLocksPulseOtherStatesDont() {
        fun pulsing(id: String, state: String): Boolean {
            put(id, state)
            return TileCardView(ctx(), card("""{"type": "tile", "entity": "$id"}""")).isPulsing
        }
        for (s in listOf("pending", "arming", "triggered")) assertTrue(s, pulsing("alarm_control_panel.house", s))
        assertFalse(pulsing("alarm_control_panel.house", "armed_away"))
        assertTrue(pulsing("lock.front_door", "jammed"))
        assertFalse(pulsing("lock.front_door", "unlocked"))
    }

    @Test
    fun timerJustFinishedIsHasRule() {
        fun s(state: String, transition: String? = null) =
            EntityState("timer.tea", state, if (transition == null) emptyMap() else mapOf("last_transition" to transition))
        assertTrue(TileCardView.timerJustFinished(s("active"), s("idle", "finished")))
        assertTrue(TileCardView.timerJustFinished(s("paused"), s("idle", "finished")))
        // Cancelled, already idle, or no state before: no blink.
        assertFalse(TileCardView.timerJustFinished(s("active"), s("idle", "cancelled")))
        assertFalse(TileCardView.timerJustFinished(s("idle"), s("idle", "finished")))
        assertFalse(TileCardView.timerJustFinished(null, s("idle", "finished")))
    }

    @Test
    fun gridOptionsAreClampedToTheTilesMinimums() {
        // getGridOptions: 6 columns minimum (3 vertical, 12 inline) and min_rows = rows.
        assertEquals(HaGridSize(6, 2), HaGridSize.of(card("""{"type": "tile", "entity": "light.porch",
            "features": [{"type": "light-brightness"}], "grid_options": {"columns": 3, "rows": 1}}""")))
        assertEquals(HaGridSize(3, 2), HaGridSize.of(card("""{"type": "tile", "entity": "light.porch", "vertical": true,
            "grid_options": {"columns": 3}}""")))
        assertEquals(HaGridSize(12, 2), HaGridSize.of(card("""{"type": "tile", "entity": "light.porch",
            "features_position": "inline", "features": [{"type": "toggle"}, {"type": "light-brightness"}],
            "grid_options": {"columns": 6}}""")))
        // computeCardFeatureRows: inline, the features after the first fill two columns.
        assertEquals(false to 2, HaGridSize.tileFeatureLayout(JSONObject("""{"features": [{}, {}]}""")))
        assertEquals(true to 1, HaGridSize.tileFeatureLayout(JSONObject("""{"features_position": "inline", "features": [{}, {}, {}]}""")))
        assertEquals(true to 2, HaGridSize.tileFeatureLayout(JSONObject("""{"features_position": "inline", "features": [{}, {}, {}, {}]}""")))
        assertEquals(false to 2, HaGridSize.tileFeatureLayout(JSONObject("""{"vertical": true, "features_position": "inline", "features": [{}, {}]}""")))
    }
}
