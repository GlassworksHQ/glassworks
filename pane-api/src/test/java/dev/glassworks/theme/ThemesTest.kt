/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #691's themes: five named ones, each light and dark, Glassworks dark by default. */
class ThemesTest {
    @Test fun fiveThemesEachLightAndDarkGlassworksFirst() {
        assertEquals(listOf("glassworks", "aurora", "dusk", "dune", "graphite"), Themes.ALL.map { it.id })
        assertEquals(10, Themes.options().size)
        assertEquals("glassworks/light" to "Glassworks, light", Themes.options().first())
        assertEquals("graphite/dark" to "Graphite, dark", Themes.options().last())
    }

    @Test fun theDefaultIsTodaysLook() {
        assertEquals("glassworks/dark", Themes.DEFAULT.id)
        assertTrue(Themes.DEFAULT.dark)
        assertEquals(0xff38bdf8.toInt(), Themes.DEFAULT.palette.a)
        assertEquals(0xffa78bfa.toInt(), Themes.DEFAULT.palette.b)
    }

    @Test fun anIdReadsBackAndAnUnknownOneIsTheDefault() {
        val p = Themes.pick("dusk/light")
        assertEquals(Themes.DUSK, p.theme)
        assertFalse(p.dark)
        assertEquals(Themes.DUSK.light, p.palette)
        for ((id, _) in Themes.options()) assertEquals(id, Themes.pick(id).id)
        for (bad in listOf(null, "", "ember/dark", "dusk", "dusk/dim", "dusk/dark/extra")) assertEquals(bad.toString(), Themes.DEFAULT.id, Themes.pick(bad).id)
    }

    @Test fun noThemeIsEmberAndEveryColourIsOpaque() {
        assertFalse(Themes.ALL.any { it.id.contains("ember") || it.name.contains("Ember") })
        for (t in Themes.ALL) for (p in listOf(t.dark, t.light)) for (c in listOf(p.a, p.b, p.ground)) assertEquals(t.id, 0xff, c ushr 24)
    }
}

/** #691's custom themes: any palette works like a named one, and text on it stays readable. */
class CustomThemeTest {
    private val pale = Themes.Palette(0xfffff7e0.toInt(), 0xffffeecc.toInt(), 0xfffffdf5.toInt())
    private val night = Themes.Palette(0xff101010.toInt(), 0xff202020.toInt(), 0xff050505.toInt())

    @Test fun aCustomThemeIsStoredAsTextAndReadBack() {
        val t = Themes.custom(night, pale)
        val text = Themes.encode(t)
        assertEquals("#101010,#202020,#050505;#fff7e0,#ffeecc,#fffdf5", text)
        val back = Themes.decode(text)!!
        assertEquals(night, back.dark)
        assertEquals(pale, back.light)
        for (bad in listOf(null, "", "#101010", "#101010,#202020,#050505", "#1010,#202020,#050505;#fff7e0,#ffeecc,#fffdf5", "red,green,blue;a,b,c"))
            assertEquals(bad.toString(), null, Themes.decode(bad))
    }

    @Test fun customPicksNeedTheCustomPalettes() {
        val t = Themes.custom(night, pale)
        assertEquals("custom/light", Themes.pick("custom/light", t).id)
        assertEquals(pale, Themes.pick("custom/light", t).palette)
        assertEquals("without saved colours, custom reads as the default", Themes.DEFAULT.id, Themes.pick("custom/light").id)
    }

    @Test fun inkIsReadableOnAnyGround() {
        for (g in listOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xff808080.toInt(), 0xff757575.toInt(), 0xff6b6b6b.toInt(), 0xffffeb3b.toInt(), 0xff1e3a8a.toInt(), 0xffef4444.toInt())) {
            val p = Themes.Palette(g, g, g)
            assertTrue(Themes.hex(g), Themes.contrast(p.ink, g) >= 4.5)
        }
        // A mid grey where neither of Glassworks' inks reaches 4.5:1 (CodeRabbit on #702): pure black or white does.
        val mid = Themes.Palette(0xff757575.toInt(), 0xff757575.toInt(), 0xff757575.toInt())
        assertTrue(Themes.contrast(mid.ink, mid.ground) >= 4.5)
        assertEquals(Themes.INK_LIGHT, night.ink)
        assertEquals(Themes.INK_DARK, pale.ink)
        assertTrue(night.darkGround)
        assertFalse(pale.darkGround)
    }

    @Test fun aLightTooCloseToItsGroundIsMovedUntilItReads() {
        val r = pale.readable(pale.a)
        assertTrue(Themes.contrast(r, pale.ground) >= 3.0)
        // one that already reads stays as it is
        assertEquals(Themes.GLASSWORKS.dark.a, Themes.GLASSWORKS.dark.readable(Themes.GLASSWORKS.dark.a))
    }

    @Test fun everyNamedThemesLightsReadOnTheirGroundOnceMadeReadable() {
        for (t in Themes.ALL) for (p in listOf(t.dark, t.light)) for (c in listOf(p.a, p.b))
            assertTrue("${t.id} ${Themes.hex(c)}", Themes.contrast(p.readable(c), p.ground) >= 3.0)
    }

    @Test fun homeAssistantsThemeIsOnePickInTheVariantItsGroundIs() {
        val dark = Themes.ha(Themes.Palette(0xff03a9f4.toInt(), 0xffff9800.toInt(), 0xff111111.toInt()))
        val light = Themes.ha(Themes.Palette(0xff03a9f4.toInt(), 0xffff9800.toInt(), 0xfffafafa.toInt()))
        assertEquals(Themes.DEFAULT.id, Themes.pick(Themes.HA_PICK).id) // the dashboard hasn't shown its colors
        assertEquals(Themes.HA_PICK, Themes.pick(Themes.HA_PICK, ha = dark).id)
        assertTrue(Themes.pick(Themes.HA_PICK, ha = dark).dark)
        assertFalse(Themes.pick(Themes.HA_PICK, ha = light).dark)
        assertEquals(Themes.DEFAULT.id, Themes.pick("ha/dark", ha = dark).id) // only ha/auto names it
        assertEquals("Home Assistant", Themes.pick(Themes.HA_PICK, ha = dark).name)
        val opts = Themes.options(Themes.CUSTOM_START, dark).map { it.first }
        assertEquals(1, opts.count { it.startsWith("ha/") })
        assertTrue("after the five, before Custom", opts.indexOf(Themes.HA_PICK) == 10 && opts.last().startsWith("custom/"))
        assertEquals(dark.dark, Themes.decodePalette(Themes.encode(dark.dark)))
        assertEquals(null, Themes.decodePalette("#123456,#abcdef"))
    }
}

