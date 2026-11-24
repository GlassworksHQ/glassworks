/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.engine.vitro.ui.VitroFonts.Face
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The face text draws in: the theme's font list read like a browser does, first family Vitro has wins. */
class VitroFontsTest {
    private fun theme(vars: String) =
        ThemeResolver.resolve(ThemeCatalog.parse(JSONObject("""{"themes": {"t": $vars}}""")), true, "t", null)

    @Test
    fun haDefaultIsRoboto() {
        // HA's default theme: Roboto, which is Android's sans-serif, not the bundled Inter.
        assertEquals(Face.SYSTEM, VitroFonts.faceFor(VitroFonts.HA_DEFAULT))
        assertEquals(Face.SYSTEM, VitroFonts.faceFor(null as String?))
        assertEquals(Face.SYSTEM, VitroFonts.faceFor(theme("{}")))
    }

    @Test
    fun firstFamilyVitroHasWins() {
        // Google Sans and Product Sans aren't there to load, so the browser falls to Inter, and so does Vitro.
        assertEquals(Face.INTER, VitroFonts.faceFor("\"Google Sans\", \"Product Sans\", Inter, system-ui, sans-serif"))
        // A system family ahead of Inter wins, as it would in the browser.
        assertEquals(Face.SYSTEM, VitroFonts.faceFor("Roboto, Inter, sans-serif"))
        assertEquals(Face.INTER, VitroFonts.faceFor("'Inter var', sans-serif"))
        // Nothing Vitro has: the generic fallback, the system's sans-serif.
        assertEquals(Face.SYSTEM, VitroFonts.faceFor("\"Comic Neue\""))
    }

    @Test
    fun themeBodyFontThenPrimaryFont() {
        assertEquals(Face.INTER, VitroFonts.faceFor(theme("""{"ha-font-family-body": "Inter, sans-serif"}""")))
        assertEquals(Face.INTER, VitroFonts.faceFor(theme("""{"primary-font-family": "Inter"}""")))
        assertEquals(Face.SYSTEM, VitroFonts.faceFor(theme("""{"ha-font-family-body": "Roboto", "primary-font-family": "Inter"}""")))
    }

    @Test
    fun aThemeOfColorsOnlyDeclaresNoFont() {
        // A card's color-only theme keeps the font in force outside the card (VitroView).
        assertEquals(null, VitroFonts.declaredFamily(theme("""{"primary-color": "#ff0000"}""")))
        assertEquals("Lora, serif", VitroFonts.declaredFamily(theme("""{"ha-font-family-body": "Lora, serif"}""")))
    }
}
