/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.settings

import dev.glassworks.engine.CardInBrowser
import dev.glassworks.engine.Drawing
import dev.glassworks.engine.ViewDrawing
import dev.glassworks.manage.AppManage
import dev.glassworks.manage.Tile
import dev.glassworks.pane.SettingsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → Labs while Vitro draws (maintainer, 2026-11-28): the view on screen, its islands and why, and each view. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VitroDrawingRowsTest {
    private val media = CardInBrowser("home › section 2 › card 3 (custom:mushroom-media-player-card)", "`custom:mushroom-media-player-card` isn't drawn natively")
    private val clock = CardInBrowser("home › card 4 (custom:acme-clock-card)", "`custom:acme-clock-card` isn't a card Vitro draws")
    private val home = ViewDrawing("Home", islands = listOf(media, clock))
    private val energy = ViewDrawing("Energy", frontend = "the built-in `energy-date-selection` card")
    private val rooms = ViewDrawing("Rooms")

    private fun info(i: SettingsItem) = (i as SettingsItem.Info).let { it.title() to it.detail() }

    @Test fun nothingWhileVitroIsOff() {
        assertTrue(VitroDrawingRows.items { null }.isEmpty())
    }

    @Test fun beforeAViewIsUpItSaysSo() {
        val items = VitroDrawingRows.items { Drawing(null) }
        val group = items.single() as SettingsItem.Group
        assertEquals(VitroDrawingRows.SHOWN_TITLE, group.title)
        assertEquals(VitroDrawingRows.NOTHING_YET to VitroDrawingRows.NOTHING_YET_DETAIL, info(group.items.single()))
        assertTrue("the Manage page follows it", VitroDrawingRows.shownIn(items))
    }

    @Test fun theViewOnScreenListsEachIslandWithWhereAndWhy() {
        val items = VitroDrawingRows.items { Drawing(home) { listOf(home, energy, rooms) } }
        val group = items[0] as SettingsItem.Group
        assertEquals(
            listOf(
                "Home" to "Vitro draws it natively, with 2 cards in browser views of their own.",
                media.where to media.why,
                clock.where to clock.why,
            ),
            group.items.map(::info),
        )
        val each = items[1] as SettingsItem.States
        assertEquals(VitroDrawingRows.VIEWS_TITLE, each.title)
        assertEquals(
            listOf("Home" to "2 browser views", "Energy" to "Home Assistant's frontend", "Rooms" to "Native"),
            each.rows().map { it.name to it.value },
        )
    }

    @Test fun aViewHomeAssistantsFrontendDrawsSaysWhyAndCountsNoIslands() {
        val group = VitroDrawingRows.items { Drawing(energy) }.single() as SettingsItem.Group
        assertEquals(
            "Energy" to "Home Assistant's frontend shows it, because Vitro doesn't draw the built-in `energy-date-selection` card.",
            info(group.items.single()),
        )
    }

    @Test fun aNativeViewAndOneIsland() {
        assertEquals("Vitro draws every card natively.", VitroDrawingRows.detail(rooms))
        assertEquals("Vitro draws it natively, with 1 card in a browser view of its own.", VitroDrawingRows.detail(ViewDrawing("Home", islands = listOf(media))))
        assertEquals("1 browser view", VitroDrawingRows.summary(ViewDrawing("Home", islands = listOf(media))))
    }

    @Test fun aDashboardWithOneViewListsItOnce() {
        val items = VitroDrawingRows.items { Drawing(rooms) { listOf(rooms) } }
        assertEquals(1, items.size)
        assertFalse(items.any { it is SettingsItem.States })
    }

    @Test fun theEngineTileSaysHowTheViewOnScreenIsDrawnAndOpensLabs() {
        val name = "Vitro 0.0.1 (experimental)"
        assertEquals(Tile("Engine", "Vitro 0", "2 browser views", Tile.Tone.OK, href = "/${CoreSettings.LABS}"),
            AppManage.engineTile(name, drawing = Drawing(home)))
        assertEquals("HA's frontend", AppManage.engineTile(name, drawing = Drawing(energy)).detail)
        assertEquals("All native", AppManage.engineTile(name, drawing = Drawing(rooms)).detail)
        // Nothing on screen yet, or Vitro off: the tile as it was.
        assertEquals(Tile("Engine", "Vitro 0", "Version 0.0.1", Tile.Tone.OK), AppManage.engineTile(name, drawing = Drawing(null)))
        assertNull(AppManage.engineTile("GeckoView 159.0").href)
        // Turned off after crashes wins.
        assertEquals(AppManage.VITRO_OFF_TILE, AppManage.engineTile(name, vitroTurnedOff = true, drawing = Drawing(home)).detail)
    }

    /** The panel's menu keeps the rows while it's open and reads them again: they follow the engine, not the first read. */
    @Test fun theRowsReadTheDrawingEachTime() {
        var now: Drawing? = Drawing(home) { listOf(home, energy) }
        val items = VitroDrawingRows.items { now }
        val head = (items[0] as SettingsItem.Group).items[0]
        val each = items[1] as SettingsItem.States
        now = Drawing(ViewDrawing("Home", islands = listOf(clock))) { listOf(rooms, energy) }
        assertEquals("Home" to "Vitro draws it natively, with 1 card in a browser view of its own.", info(head))
        assertEquals(clock.where, info((items[0] as SettingsItem.Group).items[1]).first)
        assertEquals("the second island row is gone with its card", "", info((items[0] as SettingsItem.Group).items[2]).first)
        assertEquals(listOf("Rooms", "Energy"), each.rows().map { it.name })
        now = null
        assertEquals(VitroDrawingRows.NOTHING_YET, info(head).first)
    }

    @Test fun theViewsAreWorkedOutOnceAndAFailureListsNone() {
        var calls = 0
        val d = Drawing(rooms) { calls++; listOf(rooms, home) }
        d.views; d.views
        assertEquals(1, calls)
        assertTrue(Drawing(rooms) { error("no config") }.views.isEmpty())
    }
}
