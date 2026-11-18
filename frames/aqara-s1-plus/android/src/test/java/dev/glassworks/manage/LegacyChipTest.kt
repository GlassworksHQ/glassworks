/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.manage.AppManage

/**
 * #29: on the legacy build, the Overview's Legacy chip says what its frozen engine can't do. Its words are a
 * tooltip the button names (aria-describedby), so a screen reader reads them on focus. Elsewhere there is no chip.
 */
class LegacyChipTest {
    private fun overview(legacy: Boolean): String {
        val f = ManageFixture()
        f.status = f.status.copy(legacy = legacy)
        val b = f.Browser()
        b.signInWithHa()
        return b.get("/").text()
    }

    @Test fun theLegacyBuildsOverviewHasTheChipAndItsLimits() {
        val html = overview(legacy = true)
        assertTrue(html, html.contains("""<button class="chip" type="button" aria-describedby="lgtip">Legacy</button>"""))
        val tip = html.substringAfter("""<span class="tip" id="lgtip" role="tooltip">""").substringBefore("</span>")
        assertTrue(tip, tip.contains("loads only your Home Assistant"))
        assertTrue(tip, tip.contains("remote debugging is off"))
        assertTrue("next to the version", html.contains("""<p class="meta lgm">Glassworks """))
    }

    @Test fun otherBuildsHaveNoChip() {
        val html = overview(legacy = false)
        assertFalse(html.contains("lgtip"))
        assertFalse(html.contains(">Legacy<"))
        assertTrue(html.contains("""<p class="meta">Glassworks """))
    }

    /** The legacy engine's name ends in its label; the tile still reads product, major and version (it read "Version only)"). */
    @Test fun theEngineTileReadsTheVersionOnBothEngines() {
        assertEquals(Tile("Engine", "GeckoView 159", "Version 159.0", Tile.Tone.OK), AppManage.engineTile("GeckoView 159.0"))
        assertEquals(Tile("Engine", "GeckoView 144", "Version 144.0", Tile.Tone.OK), AppManage.engineTile("GeckoView 144.0 (legacy, Home Assistant only)"))
        assertEquals(Tile("Engine", "Unknown", "", Tile.Tone.OK), AppManage.engineTile(""))
    }
}
