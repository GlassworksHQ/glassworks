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
    fun textThatOnlyLooksLikeARuleIsNot() {
        val css = """
            .x::before { content: "@font-face { font-family: Fake; src: url(fake.ttf) }"; }
            .y::after { content: '@import url(evil.css);'; }
            /* @font-face { font-family: Commented; src: url(c.ttf) } */
            @font-face { font-family: "Pilot\" Sans"; src: url("/local/format(woff2)/brand.ttf"); }
            @font-face { font-family: Hex\26 Co; src: url(h.ttf) format("truetype"); }
        """
        val parsed = CssFontFaces.parse(css, sheet)
        assertTrue(parsed.imports.isEmpty())
        assertEquals(listOf("pilot\" sans", "hex&co"), parsed.faces.map { it.family })
        // "format(" inside the URL's path is no hint: the TTF stays drawable.
        val pilot = parsed.faces[0]
        assertEquals(null, pilot.sources.single().format)
        assertEquals("http://homeassistant.local:8123/local/format(woff2)/brand.ttf", pilot.source?.url)
        assertEquals("truetype", parsed.faces[1].sources.single().format)
        // The usage list decodes the same way, so both spellings name one font.
        assertEquals(listOf("Pilot\" Sans", "serif"), CssFontFaces.families("\"Pilot\\\" Sans\", serif"))
    }

    @Test
    fun localNamesEscapedCommasAndGenericKeywords() {
        // A local() name holding "url(" isn't a source; the url() after it is.
        val css = """@font-face { font-family: L; src: local("My url(x) Font"), url(l.ttf); }"""
        assertEquals(listOf("http://homeassistant.local:8123/local/fonts/l.ttf"), CssFontFaces.parse(css, sheet).faces.single().supportedUrls)
        // An escaped comma is part of the name.
        assertEquals(listOf("Foo, Bar", "serif"), CssFontFaces.families("Foo\\, Bar, serif"))
        // A bare generic keyword is generic; the same word quoted is a family name.
        assertEquals(
            listOf(CssFontFaces.Family("serif", true), CssFontFaces.Family("serif", false), CssFontFaces.Family("Roboto", false)),
            CssFontFaces.familyList("serif, \"serif\", Roboto"),
        )
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
        // Two rules for 700 (an imported sheet and the one importing it): the later wins, for 700 and for 600 alike.
        val bold2 = FontFace("f", 700, 700, false, listOf(FontSource("https://x/700-later.ttf", null)))
        assertEquals(bold2, WebFonts.match(listOf(regular, bold, bold2), 700, false))
        assertEquals(bold2, WebFonts.match(listOf(regular, bold, bold2), 600, false))
        // Of two faces split by unicode-range, the Latin one.
        val greek = FontFace("f", 400, 400, false, listOf(FontSource("https://x/greek.ttf", null)), listOf(0x370..0x3FF))
        val latin = FontFace("f", 400, 400, false, listOf(FontSource("https://x/latin.ttf", null)), listOf(0x0..0xFF))
        assertEquals(latin, WebFonts.match(listOf(greek, latin), 400, false))
        // Not just the later rule: Latin wins when it comes first too.
        assertEquals(latin, WebFonts.match(listOf(latin, greek), 400, false))
    }

    @Test
    fun importsAfterARuleAreIgnoredAndUrlEscapesDecoded() {
        val css = "@import url(\"first.css\");\n.tile { color: red }\n@import url(\"late.css\");\n" +
            "@font-face { font-family: Brand   Sans; src: url(\"my\\20 font.ttf\") format(\"truetype\"), url(a%20b.ttf); }"
        val parsed = CssFontFaces.parse(css, sheet)
        assertEquals(listOf("http://homeassistant.local:8123/local/fonts/first.css"), parsed.imports)
        val face = parsed.faces.single()
        assertEquals(listOf("http://homeassistant.local:8123/local/fonts/my%20font.ttf", "http://homeassistant.local:8123/local/fonts/a%20b.ttf"), face.supportedUrls)
        // Unquoted identifiers join with one space, as the usage side reads them.
        assertEquals("brand sans", face.family)
        assertEquals(listOf("Brand Sans", "serif"), CssFontFaces.families("Brand   Sans, serif"))
        assertEquals(listOf("Brand   Sans"), CssFontFaces.families("\"Brand   Sans\""))
        // A hex escape's terminating space isn't a separator: `Brand\26  Sans` is "Brand& Sans".
        assertEquals(listOf("Brand& Sans"), CssFontFaces.families("Brand\\26  Sans"))
        assertEquals(listOf("Brand& Sans"), CssFontFaces.families("Brand\\26     Sans"))
    }

    @Test
    fun withoutVariationsTheNearestStaticWeight() {
        val variable = FontFace("f", 100, 900, false, listOf(FontSource("https://x/vf.ttf", null)))
        val regular = FontFace("f", 400, 400, false, listOf(FontSource("https://x/400.ttf", null)))
        val bold = FontFace("f", 700, 700, false, listOf(FontSource("https://x/700.ttf", null)))
        val all = listOf(variable, regular, bold)
        assertEquals(variable, WebFonts.match(WebFonts.candidates(all, canVary = true), 600, false))
        // Below Android 8: 600 is the static 700, not the variable font's default instance.
        assertEquals(bold, WebFonts.match(WebFonts.candidates(all, canVary = false), 600, false))
        // A family that's only variable keeps it.
        assertEquals(listOf(variable), WebFonts.candidates(listOf(variable), canVary = false))
    }
}
