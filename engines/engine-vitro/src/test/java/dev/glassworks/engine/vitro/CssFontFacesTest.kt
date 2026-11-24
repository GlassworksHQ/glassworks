/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.fonts.CssFontFaces
import dev.glassworks.engine.vitro.fonts.FontFace
import dev.glassworks.engine.vitro.fonts.FontSource
import dev.glassworks.engine.vitro.fonts.WebFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `@font-face` and `@import` read from a `type: css` resource the way the browser reads its `<link>` sheet. */
class CssFontFacesTest {
    private val sheet = "http://homeassistant.local:8123/local/fonts/site.css"

    @Test
    fun readsFamilyWeightStyleAndSourcesResolvedAgainstTheSheet() {
        val css = """
            /* a comment with @font-face { font-family: Nope; src: url(x.ttf) } in it */
            @import url("https://fonts.googleapis.com/css2?family=Lora:wght@400..700&display=swap");
            @import 'more.css';
            @font-face {
              font-family: "My Font";
              src: local("My Font"), url(my-font.woff2) format("woff2"), url('../files/my font.woff') format('woff');
              font-weight: bold;
              font-style: italic;
              font-display: swap;
            }
            @media (min-width: 0) { @font-face { font-family: Other; src: url(/local/o.ttf); font-weight: 100 900 } }
            .card { font-family: "My Font" }
        """
        val parsed = CssFontFaces.parse(css, sheet)
        assertEquals(
            listOf(
                "https://fonts.googleapis.com/css2?family=Lora:wght@400..700&display=swap",
                "http://homeassistant.local:8123/local/fonts/more.css",
            ),
            parsed.imports,
        )
        assertEquals(2, parsed.faces.size)
        val my = parsed.faces[0]
        assertEquals("my font", my.family)
        assertEquals(700 to 700, my.weightMin to my.weightMax)
        assertTrue(my.italic)
        // local() is skipped (no font is "installed" on a panel), the WOFF2 is listed but not drawable.
        assertEquals(
            listOf(
                FontSource("http://homeassistant.local:8123/local/fonts/my-font.woff2", "woff2"),
                FontSource("http://homeassistant.local:8123/local/files/my%20font.woff", "woff"),
            ),
            my.sources,
        )
        assertEquals("http://homeassistant.local:8123/local/files/my%20font.woff", my.source?.url)
        val other = parsed.faces[1]
        assertEquals("other", other.family)
        assertTrue(other.variable)
        assertEquals(100 to 900, other.weightMin to other.weightMax)
        assertFalse(other.italic)
        assertEquals("http://homeassistant.local:8123/local/o.ttf", other.source?.url)
    }

    @Test
    fun aDataUriKeepsItsSemicolonsAndCommas() {
        val css = "@font-face { font-family: Inline; src: url(data:font/woff;base64,d09GRg==) format('woff'); }"
        val face = CssFontFaces.parse(css, sheet).faces.single()
        assertEquals("data:font/woff;base64,d09GRg==", face.source?.url)
    }

    @Test
    fun onlyTrueTypeOpenTypeAndWoffAreDrawable() {
        assertTrue(FontSource("https://x/a.ttf", null).supported)
        assertTrue(FontSource("https://x/a", "truetype").supported)
        assertTrue(FontSource("https://x/a", "truetype-variations").supported)
        assertTrue(FontSource("https://x/a.woff?v=2", null).supported)
        assertTrue(FontSource("https://x/a.otf", "opentype").supported)
        assertFalse(FontSource("https://x/a.woff2", null).supported)
        assertFalse(FontSource("https://x/a", "woff2").supported)
        assertFalse(FontSource("https://x/a", "woff2-variations").supported)
        assertFalse(FontSource("https://x/a", "embedded-opentype").supported)
        // A face with only WOFF2 can't be drawn: the next family of the list is.
        val face = CssFontFaces.parse("@font-face { font-family: W; src: url(w.woff2) format('woff2') }", sheet).faces.single()
        assertNull(face.source)
    }

    @Test
    fun aFaceWithoutAFamilyOrSourceIsDropped() {
        val css = "@font-face { src: url(a.ttf) } @font-face { font-family: A } @font-face { font-family: B; src: local(B) }"
        assertTrue(CssFontFaces.parse(css, sheet).faces.isEmpty())
        // Only http(s) and data URLs are fetched.
        assertTrue(CssFontFaces.parse("@font-face { font-family: F; src: url(file:///etc/passwd) }", sheet).faces.isEmpty())
    }

    @Test
    fun weightsFollowCss() {
        assertEquals(400 to 400, CssFontFaces.parseWeightRange(null))
        assertEquals(400 to 400, CssFontFaces.parseWeightRange("normal"))
        assertEquals(700 to 700, CssFontFaces.parseWeightRange("bold"))
        assertEquals(300 to 300, CssFontFaces.parseWeightRange("300"))
        assertEquals(100 to 900, CssFontFaces.parseWeightRange("900 100"))
    }

    @Test
    fun theFamiliesOfAList() {
        assertEquals(listOf("Google Sans", "Product Sans", "Inter", "sans-serif"), CssFontFaces.families("\"Google Sans\", 'Product Sans', Inter, sans-serif"))
    }

    @Test
    fun theBrowsersFaceForAWeightAndStyle() {
        fun face(w: Int, italic: Boolean = false, max: Int = w) = FontFace("f", w, max, italic, listOf(FontSource("https://x/$w$italic.ttf", null)))
        val light = face(300)
        val regular = face(400)
        val medium = face(500)
        val bold = face(700)
        val italic = face(400, italic = true)
        val all = listOf(light, regular, medium, bold, italic)
        assertEquals(regular, WebFonts.match(all, 400, false))
        assertEquals(bold, WebFonts.match(all, 700, false))
        // 600 is missing: heavier first above 500.
        assertEquals(bold, WebFonts.match(all, 600, false))
        // 450 is missing: heavier up to 500 first.
        assertEquals(medium, WebFonts.match(all, 450, false))
        // 350: lighter first below 400.
        assertEquals(light, WebFonts.match(all, 350, false))
        assertEquals(italic, WebFonts.match(all, 400, true))
        // Bold italic with one italic face: the italic face, emboldened at draw time.
        assertEquals(italic, WebFonts.match(all, 700, true))
        // Italic asked of a family with none: a normal face, slanted at draw time.
        assertEquals(bold, WebFonts.match(listOf(light, regular, bold), 700, true))
        // Only a 700 face: 400 falls back to it.
        assertEquals(bold, WebFonts.match(listOf(bold), 400, false))
        // A variable face holds every weight in its range.
        val variable = face(100, max = 900)
        assertEquals(variable, WebFonts.match(listOf(variable), 650, false))
        // Of two faces split by unicode-range, the Latin one.
        val greek = FontFace("f", 400, 400, false, listOf(FontSource("https://x/greek.ttf", null)), listOf(0x370..0x3FF))
        val latin = FontFace("f", 400, 400, false, listOf(FontSource("https://x/latin.ttf", null)), listOf(0x0..0xFF))
        assertEquals(latin, WebFonts.match(listOf(greek, latin), 400, false))
    }
}
