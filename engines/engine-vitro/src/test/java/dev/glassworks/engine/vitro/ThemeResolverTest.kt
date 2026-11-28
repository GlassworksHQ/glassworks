/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.theme.CardModStyle
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import dev.glassworks.engine.vitro.theme.CssBackground
import dev.glassworks.engine.vitro.theme.ThemeBackground
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** Theme catalog parsing, `var()` resolution, CSS color/background parsing and card-mod extraction. Pure JVM. */
class ThemeResolverTest {

    private fun argb(v: Long): Int = v.toInt()

    private fun catalog(json: String): ThemeCatalog = ThemeCatalog.parse(JSONObject(json))

    private fun resolveWith(vars: String, dark: Boolean = true, themeName: String? = "t", viewBackground: String? = null): ResolvedTheme =
        ThemeResolver.resolve(catalog("""{"themes": {"t": $vars}}"""), dark, themeName, viewBackground)

    private val twoThemes = """{
      "default_theme": "paper",
      "default_dark_theme": "midnight_glass",
      "themes": {
        "midnight_glass": {
          "primary-color": "#ff5722",
          "primary-background-color": "#001122",
          "ha-card-border-radius": "20px",
          "ha-card-border-width": "0px",
          "modes": {"dark": {"primary-text-color": "#ffffff"}, "light": {"primary-text-color": "#000000"}}
        },
        "paper": {
          "primary-color": "rgb(10, 20, 30)",
          "opacity-num": 0.5,
          "nullish": null,
          "modes": {"light": {"card-background-color": "#f0f0f0"}}
        },
        "weird": "not an object"
      }
    }"""

    // ---- The catalog ----

    @Test
    fun catalogParseHandlesMissingPieces() {
        assertEquals(ThemeCatalog(), ThemeCatalog.parse(null))
        assertEquals(ThemeCatalog("default", null, emptyMap()), ThemeCatalog.parse(JSONObject("{}")))
        val onlyNames = ThemeCatalog.parse(JSONObject("""{"default_theme": "", "default_dark_theme": "night"}"""))
        assertEquals("default", onlyNames.defaultTheme) // an empty name falls back
        assertEquals("night", onlyNames.defaultDarkTheme)
        assertTrue(onlyNames.themes.isEmpty())
    }

    @Test
    fun catalogParseSplitsModesFromTheBaseAndKeepsOnlyObjectThemes() {
        val c = catalog(twoThemes)
        assertEquals("paper", c.defaultTheme)
        assertEquals("midnight_glass", c.defaultDarkTheme)
        assertEquals(setOf("midnight_glass", "paper"), c.themes.keys) // "weird" is not a theme

        val glass = c.themes.getValue("midnight_glass")
        assertEquals("#ff5722", glass.base["primary-color"])
        assertFalse("modes is not a variable", "modes" in glass.base)
        assertEquals("#ffffff", glass.dark["primary-text-color"])
        assertEquals("#000000", glass.light["primary-text-color"])

        val paper = c.themes.getValue("paper")
        assertEquals("0.5", paper.base["opacity-num"]) // numbers become their text
        assertFalse("null values are dropped", "nullish" in paper.base)
        assertTrue(paper.dark.isEmpty())
        assertEquals("#f0f0f0", paper.light["card-background-color"])
    }

    // ---- Defaults and modes ----

    @Test
    fun darkDefaultsMatchHomeAssistantsDarkTheme() {
        val t = ThemeResolver.resolve(ThemeCatalog(), dark = true)
        assertEquals("default", t.name)
        assertTrue(t.dark)
        assertEquals(argb(0xFF009AC7), t.primaryColor)
        assertEquals(argb(0xFFFF9800), t.accentColor)
        assertEquals(argb(0xFF111111), t.backgroundColor)
        assertEquals(argb(0xFF282828), t.secondaryBackgroundColor)  // HA's darkColorStyles, not neutral-10
        assertEquals(argb(0xFF1C1C1C), t.cardBackgroundColor) // ha-card-background -> var(--card-background-color)
        assertEquals(argb(0xFFE1E1E1), t.primaryTextColor)
        assertEquals(argb(0xFF9B9B9B), t.secondaryTextColor)
        assertEquals(argb(0xFF6F6F6F), t.disabledTextColor)
        assertEquals(argb(0x1FE1E1E1), t.dividerColor) // rgba(225, 225, 225, 0.12)
        assertEquals(argb(0xFF2C2C2C), t.cardBorderColor) // HA 2026 ha-card-border-color
        assertEquals(argb(0xFF44739E), t.stateIconColor)
        assertEquals(argb(0xFF9E9E9E), t.stateInactiveColor)
        assertEquals(1f, t.cardBorderWidthDp, 0.001f)
        assertEquals(12f, t.cardBorderRadiusDp, 0.001f)
        assertEquals(ThemeBackground.Solid(argb(0xFF111111)), t.background)
        assertEquals(CardModStyle(), t.cardMod)
    }

    @Test
    fun lightDefaultsMatchHomeAssistantsLightTheme() {
        val t = ThemeResolver.resolve(ThemeCatalog(), dark = false)
        assertFalse(t.dark)
        assertEquals(argb(0xFFFAFAFA), t.backgroundColor)
        assertEquals(argb(0xFFE5E5E5), t.secondaryBackgroundColor)
        assertEquals(argb(0xFFFFFFFF), t.cardBackgroundColor)
        assertEquals(argb(0xFF212121), t.primaryTextColor)
        assertEquals(argb(0xFF727272), t.secondaryTextColor)
        assertEquals(argb(0xFFBDBDBD), t.disabledTextColor)
        assertEquals(argb(0x1F000000), t.dividerColor)
        assertEquals(argb(0xFFE0E0E0), t.cardBorderColor) // HA 2026 ha-card-border-color
        assertEquals(ThemeBackground.Solid(argb(0xFFFAFAFA)), t.background)
    }

    @Test
    fun darkModePicksTheDefaultDarkThemeAndItsDarkMode() {
        val t = ThemeResolver.resolve(catalog(twoThemes), dark = true)
        assertEquals("midnight_glass", t.name)
        assertEquals(argb(0xFFFF5722), t.primaryColor)
        assertEquals(argb(0xFF001122), t.backgroundColor)
        assertEquals(argb(0xFFFFFFFF), t.primaryTextColor) // modes.dark
        assertEquals(20f, t.cardBorderRadiusDp, 0.001f)
        assertEquals(0f, t.cardBorderWidthDp, 0.001f)
        assertEquals("#ff5722", t.vars["primary-color"])
    }

    @Test
    fun lightModeUsesTheDefaultThemeAndItsLightMode() {
        val t = ThemeResolver.resolve(catalog(twoThemes), dark = false)
        assertEquals("paper", t.name)
        assertEquals(argb(0xFF0A141E), t.primaryColor) // rgb(10, 20, 30)
        assertEquals(argb(0xFFF0F0F0), t.cardBackgroundColor) // modes.light, through ha-card-background
        assertEquals(argb(0xFF212121), t.primaryTextColor) // not overridden, keeps the light default
        assertEquals("0.5", t.vars["opacity-num"])
    }

    @Test
    fun overrideThemePicksThatThemeInEitherMode() {
        val light = ThemeResolver.resolve(catalog(twoThemes), dark = false, overrideTheme = "midnight_glass")
        assertEquals("midnight_glass", light.name)
        assertEquals(argb(0xFF000000), light.primaryTextColor) // modes.light
        assertEquals(argb(0xFF001122), light.backgroundColor)

        val dark = ThemeResolver.resolve(catalog(twoThemes), dark = true, overrideTheme = "paper")
        assertEquals("paper", dark.name)
        assertEquals(argb(0xFF0A141E), dark.primaryColor)
        assertEquals(argb(0xFF1C1C1C), dark.cardBackgroundColor) // paper has no dark mode: dark default stays
    }

    @Test
    fun blankAndBackendSelectedOverridesAreIgnored() {
        for (o in listOf("", "   ", "Backend-selected")) {
            assertEquals("override '$o'", "midnight_glass", ThemeResolver.resolve(catalog(twoThemes), dark = true, overrideTheme = o).name)
        }
        assertEquals("paper", ThemeResolver.resolve(catalog(twoThemes), dark = false, overrideTheme = "Backend-selected").name)
    }

    @Test
    fun unknownThemeNameKeepsTheModeDefaults() {
        val t = ThemeResolver.resolve(catalog(twoThemes), dark = true, overrideTheme = "does_not_exist")
        assertEquals("does_not_exist", t.name)
        assertEquals(argb(0xFF009AC7), t.primaryColor)
        assertEquals(argb(0xFF111111), t.backgroundColor)
    }

    // ---- var() chains ----

    @Test
    fun varChainsResolveThroughFallbacks() {
        val map = mapOf("a" to "var(--b)", "b" to "var(--c, red)", "c" to "#00ff00")
        assertEquals("#00ff00", ThemeResolver.resolveValue("var(--a)", map, 0))
        assertEquals("1px solid #00ff00", ThemeResolver.resolveValue("1px solid var(--c)", map, 0))
        assertEquals("#123456", ThemeResolver.resolveValue("var(--missing, #123456)", map, 0))
        assertEquals("", ThemeResolver.resolveValue("var(--missing)", map, 0))
        assertEquals("plain", ThemeResolver.resolveValue("  plain ", map, 0))
        assertEquals("#00ff00 #00ff00", ThemeResolver.resolveValue("var(--c) var(--a)", map, 0))
    }

    @Test
    fun rgbTriplesFeedRgbaFunctions() {
        val map = mapOf("rgb-primary-color" to "3, 169, 244")
        val css = ThemeResolver.resolveValue("rgba(var(--rgb-primary-color), 0.2)", map, 0)
        assertEquals("rgba(3, 169, 244, 0.2)", css)
        assertEquals(argb(0x3303A9F4), ThemeResolver.parseColor(css))
    }

    @Test(timeout = 5_000)
    fun varCyclesStopInsteadOfLooping() {
        val cycle = mapOf("a" to "var(--b)", "b" to "var(--a)")
        assertTrue("var(" in ThemeResolver.resolveValue("var(--a)", cycle, 0))
        assertTrue("var(" in ThemeResolver.resolveValue("var(--a)", mapOf("a" to "var(--a)"), 0))
    }

    @Test
    fun varResolutionIsCappedAtEightHops() {
        val shortChain = (0 until 4).associate { "v$it" to "var(--v${it + 1})" } + ("v4" to "#abcdef")
        assertEquals("#abcdef", ThemeResolver.resolveValue("var(--v0)", shortChain, 0))
        val longChain = (0 until 20).associate { "v$it" to "var(--v${it + 1})" } + ("v20" to "#abcdef")
        assertTrue("var(" in ThemeResolver.resolveValue("var(--v0)", longChain, 0))
    }

    @Test
    fun resolveSubstitutesVarsAcrossTheWholeTheme() {
        val t = resolveWith(
            """{"card-background-color": "rgba(var(--rgb-primary-color), 0.2)", "primary-color": "var(--brand, #00ff00)", "brand": "var(--brand-base)", "brand-base": "#112233"}""",
        )
        assertEquals(argb(0x33009AC7), t.cardBackgroundColor)
        assertEquals(argb(0xFF112233), t.primaryColor)
        assertEquals("#112233", t.vars["brand"])
        assertEquals("rgba(0, 154, 199, 0.2)", t.vars["card-background-color"])
    }

    @Test(timeout = 5_000)
    fun resolveSurvivesCyclesInATheme() {
        val t = resolveWith("""{"primary-color": "var(--accent-color)", "accent-color": "var(--primary-color)"}""")
        // Unresolvable, so the color falls back to HA 2026's default blue (#009ac7) and orange.
        assertEquals(argb(0xFF009AC7), t.primaryColor)
        assertEquals(argb(0xFFFF9800), t.accentColor)
    }

    // ---- Colors ----

    @Test
    fun parseColorReadsHexForms() {
        assertEquals(argb(0xFFFFFFFF), ThemeResolver.parseColor("#fff"))
        assertEquals(argb(0xFFFF0000), ThemeResolver.parseColor("#f00"))
        assertEquals(argb(0xFF00FF88), ThemeResolver.parseColor("#0f8"))
        assertEquals(argb(0xFFFF8800), ThemeResolver.parseColor("#ff8800"))
        assertEquals(argb(0xFFFF8800), ThemeResolver.parseColor("#FF8800"))
        assertEquals(argb(0xFFFF8800), ThemeResolver.parseColor("  #ff8800  "))
        // CSS #rrggbbaa becomes Android 0xaarrggbb.
        assertEquals(argb(0x80FF8800), ThemeResolver.parseColor("#ff880080"))
        assertEquals(argb(0x44112233), ThemeResolver.parseColor("#11223344"))
        assertEquals(argb(0x88FF8800), ThemeResolver.parseColor("#f808")) // #rgba
    }

    @Test
    fun parseColorReadsRgbAndRgba() {
        assertEquals(argb(0xFFFF8000), ThemeResolver.parseColor("rgb(255, 128, 0)"))
        assertEquals(argb(0x80000000), ThemeResolver.parseColor("rgba(0, 0, 0, 0.5)"))
        assertEquals(argb(0x80FF0000), ThemeResolver.parseColor("rgba(255,0,0,50%)"))
        assertEquals(argb(0x80FF0000), ThemeResolver.parseColor("RGBA(255, 0, 0, .5)"))
        assertEquals(argb(0xFFFF0000), ThemeResolver.parseColor("rgb(300, 0, 0)")) // clamped
        assertEquals(argb(0x00FF0000), ThemeResolver.parseColor("rgba(255, 0, 0, 0)"))
        assertEquals(argb(0xFFFF0000), ThemeResolver.parseColor("rgba(255, 0, 0, 7)")) // alpha clamped
    }

    @Test
    fun parseColorReadsHslAndHsla() {
        assertEquals(argb(0xFFFF0000), ThemeResolver.parseColor("hsl(0, 100%, 50%)"))
        assertEquals(argb(0xFF00FF00), ThemeResolver.parseColor("hsl(120, 100%, 50%)"))
        assertEquals(argb(0xFF0000FF), ThemeResolver.parseColor("hsl(240, 100%, 50%)"))
        assertEquals(argb(0x80FFFFFF), ThemeResolver.parseColor("hsla(0, 0%, 100%, 0.5)"))
        assertEquals(argb(0xFF336699), ThemeResolver.parseColor("hsl(210deg, 50%, 40%)"))
        assertEquals(argb(0xFFFF0000), ThemeResolver.parseColor("hsl(360, 100%, 50%)")) // hue wraps
    }

    @Test
    fun parseColorReadsNamedColors() {
        assertEquals(argb(0xFFF44336), ThemeResolver.parseColor("red"))
        assertEquals(argb(0xFFFFFFFF), ThemeResolver.parseColor("WHITE"))
        assertEquals(argb(0xFF000000), ThemeResolver.parseColor("black"))
        assertEquals(0, ThemeResolver.parseColor("transparent"))
        assertEquals(ThemeResolver.parseColor("gray"), ThemeResolver.parseColor("grey"))
        assertEquals(argb(0xFF2196F3), ThemeResolver.parseColor(" Blue "))
    }

    @Test
    fun parseColorRejectsWhatItCannotRead() {
        for (bad in listOf(null, "", "   ", "notacolor", "#12", "#12345", "#gggggg", "#1234567", "rgb(a, b, c)", "var(--x)", "linear-gradient(red, blue)")) {
            assertNull("'$bad' is not a color", ThemeResolver.parseColor(bad))
        }
    }

    @Test
    fun parseDimensionConvertsRemAndEm() {
        assertEquals(12f, ThemeResolver.parseDimensionDp("12px", 0f), 0.001f)
        assertEquals(8f, ThemeResolver.parseDimensionDp("0.5rem", 0f), 0.001f)
        assertEquals(24f, ThemeResolver.parseDimensionDp("1.5em", 0f), 0.001f)
        assertEquals(3f, ThemeResolver.parseDimensionDp("3", 0f), 0.001f)
        assertEquals(7f, ThemeResolver.parseDimensionDp(null, 7f), 0.001f)
        assertEquals(7f, ThemeResolver.parseDimensionDp("", 7f), 0.001f)
        assertEquals(7f, ThemeResolver.parseDimensionDp("auto", 7f), 0.001f)
    }

    // ---- Backgrounds ----

    @Test
    fun backgroundWithNothingIsTheFallbackColor() {
        assertEquals(ThemeBackground.Solid(0x11), ThemeResolver.parseBackground(null, 0x11))
        assertEquals(ThemeBackground.Solid(0x11), ThemeResolver.parseBackground("  ", 0x11))
        assertEquals(ThemeBackground.Solid(0x11), ThemeResolver.parseBackground("not css", 0x11))
    }

    @Test
    fun backgroundColorIsSolid() {
        assertEquals(ThemeBackground.Solid(argb(0xFF112233)), ThemeResolver.parseBackground("#112233", 0))
        assertEquals(ThemeBackground.Solid(argb(0xFFF44336)), ThemeResolver.parseBackground("red", 0))
    }

    @Test
    fun backgroundUrlIsAnImageWithTheFallbackColor() {
        assertEquals(ThemeBackground.ImageUrl("/local/bg.jpg", 0x22), ThemeResolver.parseBackground("url('/local/bg.jpg') center / cover", 0x22))
        assertEquals(ThemeBackground.ImageUrl("/local/bg.png", 0x22), ThemeResolver.parseBackground("""url("/local/bg.png")""", 0x22))
        assertEquals(ThemeBackground.ImageUrl("/local/bg.webp", 0x22), ThemeResolver.parseBackground("url(/local/bg.webp)", 0x22))
        assertEquals(
            ThemeBackground.ImageUrl("http://192.0.2.10:8123/local/bg.jpg", 0x22),
            ThemeResolver.parseBackground("URL( 'http://192.0.2.10:8123/local/bg.jpg' )", 0x22),
        )
    }

    @Test
    fun backgroundLinearGradientKeepsAngleAndColorStops() {
        val g = ThemeResolver.parseBackground("linear-gradient(135deg, #ff0000 0%, rgba(0, 0, 255, 0.5) 100%)", 0) as ThemeBackground.LinearGradient
        assertEquals(135f, g.angleDeg, 0.001f)
        assertArrayEquals(intArrayOf(argb(0xFFFF0000), argb(0x800000FF)), g.colors)

        val noAngle = ThemeResolver.parseBackground("linear-gradient(#000000, #ffffff)", 0) as ThemeBackground.LinearGradient
        assertEquals(180f, noAngle.angleDeg, 0.001f) // CSS default: to bottom
        assertArrayEquals(intArrayOf(argb(0xFF000000), argb(0xFFFFFFFF)), noAngle.colors)

        val threeStops = ThemeResolver.parseBackground("linear-gradient(90deg, #111111, hsl(0, 100%, 50%), blue)", 0) as ThemeBackground.LinearGradient
        assertEquals(3, threeStops.colors.size)
        assertEquals(argb(0xFFFF0000), threeStops.colors[1])

        // A direction keyword is not a color stop and is skipped.
        val keyword = ThemeResolver.parseBackground("linear-gradient(to bottom, #000000, #ffffff)", 0) as ThemeBackground.LinearGradient
        assertEquals(2, keyword.colors.size)
    }

    @Test
    fun theLaterOfBackgroundAndBackgroundImageWins() {
        // A later shorthand resets the image (and sets the color)...
        val reset = ThemeResolver.parseCardMod("ha-card { background-image: linear-gradient(#000, #fff); background: #123456; }")
        assertNull(reset.backgroundLayers)
        assertEquals(argb(0xFF123456), reset.backgroundColor)
        // ...a later longhand keeps its gradient, on the shorthand's color.
        val keep = ThemeResolver.parseCardMod("ha-card { background: #123456; background-image: linear-gradient(#000, #fff); }")
        assertEquals(1, keep.backgroundLayers?.layers?.size)
        assertEquals(argb(0xFF123456), keep.backgroundColor)
        // `!important` beats order.
        val important = ThemeResolver.parseCardMod("ha-card { background-image: linear-gradient(#000, #fff) !important; background: #123456; }")
        assertEquals(1, important.backgroundLayers?.layers?.size)
        // A lone background-image lies on the card's own fill: no color of its own here (the theme's shows).
        val lone = ThemeResolver.parseCardMod("ha-card { background-color: #224466; background-image: linear-gradient(transparent, #fff); }")
        assertEquals(argb(0xFF224466), lone.backgroundColor)
    }

    @Test
    fun aGradientLayerKeepsTheColorAfterIt() {
        val bg = CssBackground.parse("linear-gradient(transparent, #fff) #123456")!!
        assertEquals(1, bg.layers.size)
        assertEquals(argb(0xFF123456), bg.color)
    }

    @Test
    fun theBorderShorthandAndItsLonghandsCompeteInCascadeOrder() {
        val later = ThemeResolver.parseCardMod("ha-card { border-width: 8px; border: 0px solid #123456; }")
        assertEquals(0f, later.borderWidthDp!!, 0f)
        val longhand = ThemeResolver.parseCardMod("ha-card { border: 0px solid #123456; border-width: 8px; }")
        assertEquals(8f, longhand.borderWidthDp!!, 0f)
        assertEquals(argb(0xFF123456), longhand.borderColor)
        val important = ThemeResolver.parseCardMod("ha-card { border-color: #ff0000 !important; border: 2px solid #00ff00; }")
        assertEquals(argb(0xFFFF0000), important.borderColor)
        assertEquals(2f, important.borderWidthDp!!, 0f)
    }

    @Test
    fun aThemesCardModBorderCascadesTheSameWay() {
        val later = ThemeResolver.parseCardMod(mapOf("card-mod-card" to "ha-card { border-width: 8px; border: 0px solid #123456; }"))
        assertEquals(0f, later.borderWidthDp!!, 0f)
        val longhand = ThemeResolver.parseCardMod(mapOf("card-mod-card" to "ha-card { border: 0px solid #123456; border-width: 8px; }"))
        assertEquals(8f, longhand.borderWidthDp!!, 0f)
        val important = ThemeResolver.parseCardMod(mapOf("card-mod-card" to "ha-card { border-width: 4px !important; border: 1px solid #00ff00; }"))
        assertEquals(4f, important.borderWidthDp!!, 0f)
    }

    @Test
    fun anInvalidBorderDeclarationIsDroppedBeforeTheCascade() {
        val css = "ha-card { border-width: 8px; border: nonsense; }"
        assertEquals(8f, ThemeResolver.parseCardMod(mapOf("card-mod-card" to css)).borderWidthDp!!, 0f)
        assertEquals(8f, ThemeResolver.parseCardMod(css).borderWidthDp!!, 0f)
        // A color name Vitro doesn't know, beside a width and a style, is still a valid shorthand: it wins.
        val unknownColor = "ha-card { border-width: 8px; border: 2px solid rebeccapurple; }"
        assertEquals(2f, ThemeResolver.parseCardMod(mapOf("card-mod-card" to unknownColor)).borderWidthDp!!, 0f)
        assertEquals(2f, ThemeResolver.parseCardMod(unknownColor).borderWidthDp!!, 0f)
        // An invalid width longhand doesn't beat the shorthand before it.
        assertEquals(3f, ThemeResolver.parseCardMod("ha-card { border: 3px solid #123456; border-width: wide; }").borderWidthDp!!, 0f)
        // A zero may drop its unit, decimals too; any other length may not.
        val zero = "ha-card { border-width: 0.0; }"
        assertEquals(0f, ThemeResolver.parseCardMod(mapOf("card-mod-card" to zero)).borderWidthDp!!, 0f)
        assertEquals(0f, ThemeResolver.parseCardMod(zero).borderWidthDp!!, 0f)
        assertEquals(3f, ThemeResolver.parseCardMod("ha-card { border: 3px solid #123456; border-width: 2; }").borderWidthDp!!, 0f)
        // A later shorthand with a unitless decimal zero beats an earlier width.
        val shorthandZero = "ha-card { border-width: 3px; border: 0.0 solid #123456; }"
        assertEquals(0f, ThemeResolver.parseCardMod(mapOf("card-mod-card" to shorthandZero)).borderWidthDp!!, 0f)
        assertEquals(0f, ThemeResolver.parseCardMod(shorthandZero).borderWidthDp!!, 0f)
    }

    @Test
    fun aCardVariableThatNeedsAMissingOneTakesTheFallback() {
        // `--fill` needs `--missing`, defined nowhere: it's invalid, so `var(--fill, …)` takes its fallback.
        val s = ThemeResolver.parseCardMod("ha-card { --fill: var(--missing); background-color: var(--fill, #123456); }")
        assertEquals(argb(0xFF123456), s.backgroundColor)
        // One that the theme defines is fine.
        val ok = ThemeResolver.parseCardMod("ha-card { --fill: var(--accent); background-color: var(--fill, #123456); }", mapOf("accent" to "#00ff00"))
        assertEquals(argb(0xFF00FF00), ok.backgroundColor)
    }

    @Test
    fun aGradientThatIsInvalidOrUnsupportedIsRejected() {
        // Invalid CSS, which the browser drops too: an invalid stop position, three positions.
        assertNull(CssBackground.parse("linear-gradient(#fff bogus, #000)"))
        assertNull(CssBackground.parse("linear-gradient(#fff 0% 10% 20%, #000)"))
        // Valid CSS that Vitro doesn't place yet (the four-value edge-offset position): rejected rather than drawn in
        // the wrong place, so an earlier valid background stays.
        assertNull(CssBackground.parse("radial-gradient(circle at right 20px bottom 30px, #fff, #000)"))
        // Their valid forms still parse: one or two positions, a one- or two-token place.
        assertNotNull(CssBackground.parse("linear-gradient(#fff 0% 10%, #000)"))
        assertNotNull(CssBackground.parse("radial-gradient(circle at right bottom, #fff, #000)"))
        assertNotNull(CssBackground.parse("radial-gradient(circle at 20px 30%, #fff, #000)"))
    }

    @Test
    fun aCornerDirectionDependsOnTheBox() {
        val bg = CssBackground.parse("linear-gradient(to top right, #000, #fff)")!!
        val g = bg.geometry(bg.layers[0], 200f, 100f, 1f)!!
        // The top-left and bottom-right corners sit at the midpoint: equal distance along the ray from its center.
        fun along(x: Float, y: Float) = (x - g.x0) * (g.x1 - g.x0) + (y - g.y0) * (g.y1 - g.y0)
        assertEquals(along(0f, 0f), along(200f, 100f), 0.5f)
        // An explicit angle stays fixed.
        val fixed = CssBackground.parse("linear-gradient(45deg, #000, #fff)")!!
        assertEquals(45f, (fixed.layers[0] as CssBackground.Linear).angleDeg)
        assertNull((fixed.layers[0] as CssBackground.Linear).corner)
    }

    @Test
    fun aTransitionHintMovesTheMidpoint() {
        val bg = CssBackground.parse("linear-gradient(#000000 0%, 20%, #ffffff 100%)")!!
        val stops = bg.layers[0].stops
        assertEquals(3, stops.size)
        assertEquals(0.2f, stops[1].pos!!, 0.0001f)
        assertEquals(argb(0xFF7F7F7F), stops[1].color)
        // A hint with no stop after it is invalid: no gradient.
        assertNull(CssBackground.parse("linear-gradient(#000, #fff, 20%)"))
    }

    @Test
    fun aSingleLinearBackdropKeepsItsParsedAngleAndPixelStops() {
        val g = ThemeResolver.parseBackground("linear-gradient(.25turn, #000 10px, #fff 90px)", 0) as ThemeBackground.LinearGradient
        assertEquals(90f, g.angleDeg, 0.001f)
        assertEquals(10f, g.stops!![0].px!!, 0.001f)
    }

    @Test
    fun aCyclicThemeVariableTakesItsFallback() {
        val t = resolveWith("""{"a": "var(--a)", "primary-color": "var(--a, #123456)"}""")
        assertEquals(argb(0xFF123456), t.primaryColor)
        // Left out of what islands get, not exported empty (`--a: ;` would be valid and hide the fallback).
        assertFalse(t.toCssRootBlock(), "--a:" in t.toCssRootBlock())
        // A name that needs the invalid one without a fallback is invalid too; one with a fallback stays.
        val chained = resolveWith("""{"a": "var(--a)", "b": "var(--a)", "c": "var(--a, #222222)", "primary-color": "var(--b, #123456)"}""")
        assertEquals(argb(0xFF123456), chained.primaryColor)
        assertFalse(chained.toCssRootBlock(), "--b:" in chained.toCssRootBlock())
        assertTrue(chained.toCssRootBlock(), "--c: #222222" in chained.toCssRootBlock())
        // A stylesheet with one missing reference keeps its other declarations.
        val sheet = resolveWith("""{"card-mod-card": "ha-card { color: var(--missing); background: #123456; border-radius: 24px; }"}""")
        assertEquals(argb(0xFF123456), sheet.cardBackgroundColor)
        assertEquals(24f, sheet.cardBorderRadiusDp, 0.01f)
    }

    @Test
    fun varExpansionIsBoundedWhileItGrows() {
        // 4 KiB referenced 4,096 times would build 16 MiB before the old check; now it stops at the budget.
        val big = "x".repeat(4096)
        val refs = (1..4096).joinToString(" ") { "var(--big)" }
        assertEquals("", ThemeResolver.resolveValue(refs, mapOf("big" to big), 0))
        // A long value that doesn't grow stays whole.
        val long = "a".repeat(100_000) + " var(--c)"
        assertEquals("a".repeat(100_000) + " red", ThemeResolver.resolveValue(long, mapOf("c" to "red"), 0))
        // A big substitution that a later, shrinking part of the same value pays back is fine.
        val shrinks = "var(--big) var(--drop, ${"y".repeat(30_000)})"
        assertEquals("x".repeat(20_000), ThemeResolver.resolveValue(shrinks, mapOf("big" to "x".repeat(20_000), "drop" to ""), 0))
    }

    @Test
    fun backgroundGradientWithOneStopIsNotAGradient() {
        assertEquals(ThemeBackground.Solid(0x33), ThemeResolver.parseBackground("linear-gradient(90deg, #ff0000)", 0x33))
    }

    @Test
    fun viewBackgroundBeatsTheThemesLovelaceBackground() {
        val vars = """{"lovelace-background": "linear-gradient(180deg, #000000, #ffffff)", "primary-background-color": "#0a0a14"}"""
        val themed = resolveWith(vars)
        assertTrue(themed.background is ThemeBackground.LinearGradient)
        assertEquals(argb(0xFF0A0A14), themed.backgroundColor)

        val view = resolveWith(vars, viewBackground = "url(/local/x.png)")
        assertEquals(ThemeBackground.ImageUrl("/local/x.png", argb(0xFF0A0A14)), view.background)

        // A blank view background does not hide the theme's.
        assertTrue(resolveWith(vars, viewBackground = "  ").background is ThemeBackground.LinearGradient)
        // Neither: a solid in the theme's primary background.
        assertEquals(ThemeBackground.Solid(argb(0xFF0A0A14)), resolveWith("""{"primary-background-color": "#0a0a14"}""").background)
    }

    // ---- card-mod ----

    @Test
    fun cardModContainerPropertiesAreExtracted() {
        val css = "ha-card { background: rgba(10, 20, 30, 0.8); border-radius: 24px; opacity: 0.9; backdrop-filter: blur(12px); border: 2px solid #ff0000; }"
        val m = ThemeResolver.parseCardMod(mapOf("card-mod-card" to css))
        assertEquals(argb(0xCC0A141E), m.backgroundColor)
        assertEquals(24f, m.borderRadiusDp!!, 0.001f)
        assertEquals(0.9f, m.opacity!!, 0.0001f)
        assertEquals(12f, m.blurRadiusDp!!, 0.001f)
        assertEquals(2f, m.borderWidthDp!!, 0.001f)
        assertEquals(argb(0xFFFF0000), m.borderColor)
        assertFalse(m.hasComplexSelectors)
    }

    @Test
    fun cardModReadsPlainDeclarationsAndTheYamlVariable() {
        val m = ThemeResolver.parseCardMod(mapOf("card-mod-card-yaml" to "background-color: #123456\nborder-radius: 0.5rem\nopacity: 1.5\nborder-width: 4px\nborder-color: #00ff00"))
        assertEquals(argb(0xFF123456), m.backgroundColor)
        assertEquals(8f, m.borderRadiusDp!!, 0.001f)
        assertEquals(1f, m.opacity!!, 0.001f) // clamped
        assertEquals(4f, m.borderWidthDp!!, 0.001f)
        assertEquals(argb(0xFF00FF00), m.borderColor)
        assertNull(m.blurRadiusDp)

        val important = ThemeResolver.parseCardMod(mapOf("card-mod-card" to "ha-card { opacity: 0.5 !important; }"))
        assertEquals(0.5f, important.opacity!!, 0.001f)
    }

    @Test
    fun cardCustomPropertiesHonorImportant() {
        // Per name, the last `!important` declaration wins, else the last one, as in HA.
        val m = ThemeResolver.parseCardMod(
            "ha-card { --vertical-stack-card-gap: 12px !important; --vertical-stack-card-gap: 4px; --grid-card-gap: 2px; --grid-card-gap: 6px }",
        )
        assertEquals("12px", m.customProperties["vertical-stack-card-gap"])
        assertEquals("6px", m.customProperties["grid-card-gap"])
    }

    @Test
    fun cardModBackgroundColorBeatsBackgroundShorthand() {
        val m = ThemeResolver.parseCardMod(mapOf("card-mod-card" to "ha-card { background: #111111; background-color: #222222; }"))
        assertEquals(argb(0xFF222222), m.backgroundColor)
    }

    @Test
    fun cardModWithNothingUsableIsEmpty() {
        assertEquals(CardModStyle(), ThemeResolver.parseCardMod(emptyMap()))
        assertEquals(CardModStyle(), ThemeResolver.parseCardMod(mapOf("card-mod-card" to "   ")))
        val bgImage = ThemeResolver.parseCardMod(mapOf("card-mod-card" to "ha-card { background: url(/local/x.png); }"))
        assertNull(bgImage.backgroundColor) // only colors apply natively
    }

    @Test
    fun cardModFlagsShadowDomPartAndKeyframeRules() {
        val complex = listOf(
            "ha-card { background: #000000; } \$ .header { color: red; }",
            "ha-card::part(content) { color: red; }",
            "@keyframes pulse { from { opacity: 0 } to { opacity: 1 } } ha-card { animation: pulse 1s; }",
            "ha-card { --x: {{ states('sensor.kitchen_temp') }}; }",
            "ha-state-icon { color: red; }",
        )
        for (css in complex) {
            assertTrue("complex: $css", ThemeResolver.parseCardMod(mapOf("card-mod-card" to css)).hasComplexSelectors)
        }
        // The simple parts of a complex rule still apply.
        assertEquals(argb(0xFF000000), ThemeResolver.parseCardMod(mapOf("card-mod-card" to complex[0])).backgroundColor)
        // Complex rules in the -yaml variable count too.
        assertTrue(ThemeResolver.parseCardMod(mapOf("card-mod-card-yaml" to complex[1])).hasComplexSelectors)
        assertFalse(ThemeResolver.parseCardMod(mapOf("card-mod-card" to "ha-card { background: #000000; }")).hasComplexSelectors)
    }

    @Test
    fun cardModOverridesTheCardLookInAResolvedTheme() {
        val t = resolveWith(
            """{"card-mod-card": "ha-card { background: rgba(10, 20, 30, 0.8); border-radius: 24px; opacity: 0.9; backdrop-filter: blur(12px); border: 3px solid #00ff00; }"}""",
        )
        assertEquals(argb(0xCC0A141E), t.cardBackgroundColor)
        assertEquals(24f, t.cardBorderRadiusDp, 0.001f)
        assertEquals(3f, t.cardBorderWidthDp, 0.001f)
        assertEquals(argb(0xFF00FF00), t.cardBorderColor)
        assertEquals(0.9f, t.cardMod.opacity!!, 0.0001f)
        assertEquals(12f, t.cardMod.blurRadiusDp!!, 0.001f)
        assertFalse(t.cardMod.hasComplexSelectors)
    }

    @Test
    fun complexCardModIsReportedOnTheResolvedTheme() {
        val t = resolveWith("""{"card-mod-card": "ha-card::part(content) { color: red; } ha-card { border-radius: 8px; }"}""")
        assertTrue(t.cardMod.hasComplexSelectors)
        assertEquals(8f, t.cardBorderRadiusDp, 0.001f)
    }

    // ---- Exports ----

    @Test
    fun aThemeValueThatNamesOneOfHasOwnVariablesTakesHasValue() {
        // HA's page defines its tokens around the theme, so `var(--ha-color-…)` in a theme is valid there. Without them
        // the name was missing, the theme's card background invalid, and an island fell back to `white`.
        val ha = dev.glassworks.engine.vitro.theme.HaRootVars.of(true)
        val token = ha.getValue("ha-color-fill-disabled-normal-resting")
        val t = resolveWith("""{"card-background-color": "var(--ha-color-fill-disabled-normal-resting)", "ha-card-background": "var(--card-background-color)"}""")
        assertEquals(token, t.vars["card-background-color"])
        assertEquals(ThemeResolver.parseColor(token), t.cardBackgroundColor)
        // Light mode reads light mode's.
        val light = ThemeResolver.resolve(catalog("""{"themes": {"t": {"x": "var(--disabled-color)"}}}"""), false, "t")
        assertEquals("#bdbdbd", light.vars["x"])
        assertEquals("#464646", resolveWith("""{"x": "var(--disabled-color)"}""").vars["x"])
    }

    @Test
    fun anIslandsRootStartsFromHasVariablesThenTheThemeThenTheCardsAroundIt() {
        val ha = dev.glassworks.engine.vitro.theme.HaRootVars.of(true)
        assertTrue("HA's page has hundreds", ha.size > 400)
        val t = resolveWith("""{"primary-color": "#ff5722", "secondary-text-color": "var(--a)", "a": "var(--a)"}""")
        val css = t.toCssRootBlock(mapOf("primary-text-color" to "#abcdef"))
        fun count(name: String) = Regex("\\s--" + Regex.escape(name) + ":").findAll(css).count()
        assertTrue("one of HA's own", css.contains("  --rgb-disabled-color: ${ha.getValue("rgb-disabled-color")};\n"))
        assertTrue("the theme over HA's", css.contains("  --primary-color: #ff5722;\n"))
        assertEquals("each name once", 1, count("primary-color"))
        assertTrue("an enclosing card's variable over the theme", css.contains("  --primary-text-color: #abcdef;\n"))
        assertEquals(1, count("primary-text-color"))
        // An invalid theme name hides HA's default, as the theme's declaration does in HA's page.
        assertEquals(0, count("secondary-text-color"))
        // Window- and direction-bound names aren't in the table.
        assertFalse(css.contains("--safe-area-inset-top"))
        assertFalse(css.contains("--header-height"))
    }

    @Test
    fun hasVariablesComeFromThePinnedFrontend() {
        val pinned = Regex("^VERSION = \"([^\"]+)\"", RegexOption.MULTILINE)
            .find(java.io.File(dev.glassworks.testing.RepoFiles.root, "tools/perf/hafrontend.py").readText())!!.groupValues[1]
        assertEquals("run node tools/vitro-parity/ha-root-vars.mjs after a frontend update", pinned, dev.glassworks.engine.vitro.theme.HaRootVars.frontend)
    }

    @Test
    fun cssRootBlockExportsVariablesAndSkipsCardMod() {
        val t = resolveWith(
            """{"primary-color": "#ff5722", "card-mod-card": "ha-card { opacity: 0.9; }", "card-mod-card-yaml": "opacity: 0.9", "multi": "a;b\nc"}""",
        )
        val css = t.toCssRootBlock()
        assertTrue(css.startsWith(":root {\n"))
        assertTrue(css.endsWith("}\n"))
        assertTrue(css.contains("  --primary-color: #ff5722;\n"))
        assertTrue(css.contains("  --primary-background-color: #111111;\n")) // a default is exported too
        assertTrue(css.contains("  --rgb-primary-color: 0, 154, 199;\n"))
        assertTrue("; and newlines in values cannot break out of the declaration", css.contains("  --multi: ab c;\n"))
        assertFalse(css.contains("card-mod"))
    }

    @Test
    fun hexFormatsAnAndroidColorForTheOverlayTheme() {
        val t = ThemeResolver.resolve(ThemeCatalog())
        assertEquals("#009ac7", t.hex(t.primaryColor))
        assertEquals("#ffffff", t.hex(argb(0x80FFFFFF))) // alpha dropped
        assertEquals("#000000", t.hex(0))
    }

    @Test
    fun activeColorFollowsTheDomainKeyThenAHomeAssistantDefault() {
        val t = resolveWith("""{"state-light-active-color": "#ff00ff", "state-lock-active-color": "#00ffff"}""")
        assertEquals(argb(0xFFFF00FF), t.activeColor("light"))
        assertEquals(argb(0xFF00FFFF), t.activeColor("lock")) // a domain the table does not list
        assertEquals(argb(0xFFFF6F22), t.activeColor("climate")) // heat color
        assertEquals(argb(0xFFFFC107), t.activeColor("switch"))
        assertEquals(argb(0xFFFFC107), t.activeColor("input_boolean"))
        assertEquals(argb(0xFFFFC107), t.activeColor("binary_sensor"))
        assertEquals(argb(0xFF00BCD4), t.activeColor("fan")) // HA 2026 cyan
        assertEquals(argb(0xFF926BC7), t.activeColor("cover"))
        assertEquals(argb(0xFF4CAF50), t.activeColor("person"))
        assertEquals(argb(0xFF4CAF50), t.activeColor("device_tracker"))
        assertEquals(argb(0xFF2196F3), t.activeColor("sensor")) // HA blue
        assertEquals(t.primaryColor, t.activeColor("weather")) // no rule: the primary color
    }

    // ---- Deeper HA theme support ----

    @Test
    fun cardBoxShadowBecomesAnElevation() {
        assertEquals(0f, ThemeResolver.resolve(ThemeCatalog()).cardElevationDp, 0.001f) // no shadow in the defaults
        assertEquals(0f, resolveWith("""{"ha-card-box-shadow": "none"}""").cardElevationDp, 0.001f)
        assertEquals(0f, resolveWith("""{"ha-card-box-shadow": ""}""").cardElevationDp, 0.001f)
        // blur / 2 + y offset
        assertEquals(5f, resolveWith("""{"ha-card-box-shadow": "0px 2px 6px rgba(0,0,0,0.2)"}""").cardElevationDp, 0.001f)
        assertEquals(6f, resolveWith("""{"ha-card-box-shadow": "0 2px 8px rgba(0, 0, 0, 0.3)"}""").cardElevationDp, 0.001f)
        assertEquals(6f, resolveWith("""{"ha-card-box-shadow": "0 2px 8px #00000033"}""").cardElevationDp, 0.001f)
        // clamped to 1..12, and only the first of several shadows counts
        assertEquals(12f, resolveWith("""{"ha-card-box-shadow": "0 20px 60px rgba(0,0,0,0.5)"}""").cardElevationDp, 0.001f)
        assertEquals(1f, resolveWith("""{"ha-card-box-shadow": "0 0 0 1px rgba(0,0,0,0.1)"}""").cardElevationDp, 0.001f)
        assertEquals(5f, resolveWith("""{"ha-card-box-shadow": "0 2px 6px rgba(0,0,0,0.2), 0 20px 60px #000"}""").cardElevationDp, 0.001f)
        assertEquals(0f, resolveWith("""{"ha-card-box-shadow": "inset 0 2px 6px #000"}""").cardElevationDp, 0.001f)
        // A fully transparent shadow draws nothing.
        assertEquals(0f, resolveWith("""{"ha-card-box-shadow": "0 4px 8px transparent"}""").cardElevationDp, 0.001f)
        assertEquals(0f, resolveWith("""{"ha-card-box-shadow": "0 4px 8px rgba(0, 0, 0, 0)"}""").cardElevationDp, 0.001f)
        assertEquals(0f, ThemeResolver.parseBoxShadowElevationDp("0 4px 8px hsla(0, 0%, 0%, 0)"), 0.001f)
        assertEquals(8f, ThemeResolver.parseBoxShadowElevationDp("0 4px 8px rgba(0, 0, 0, 0.01)"), 0.001f)
        // The first visible outer layer counts: a transparent or inset one before it is skipped.
        assertEquals(8f, ThemeResolver.parseBoxShadowElevationDp("0 2px 6px transparent, 0 4px 8px #000"), 0.001f)
        assertEquals(5f, ThemeResolver.parseBoxShadowElevationDp("inset 0 0 4px #000, 0 2px 6px #000"), 0.001f)
        assertEquals(0f, ThemeResolver.parseBoxShadowElevationDp("0 2px 6px transparent, inset 0 4px 8px #000"), 0.001f)
    }

    @Test
    fun linearGradientKeepsEveryStop() {
        val g = ThemeResolver.parseBackground(
            "linear-gradient(45deg, #ff0000 0%, rgba(0, 255, 0, 0.5) 50%, #0000ff 100%)", 0,
        ) as ThemeBackground.LinearGradient
        assertEquals(45f, g.angleDeg, 0.001f)
        assertArrayEquals(intArrayOf(argb(0xFFFF0000), argb(0x8000FF00), argb(0xFF0000FF)), g.colors)
    }

    @Test
    fun radialGradientIsParsedWithItsStopsShapeAndPosition() {
        val bg = ThemeResolver.parseBackground("radial-gradient(circle at top left, #112233 0%, #445566 60%, rgba(0, 0, 0, 0.8) 100%)", 0)
            as ThemeBackground.Layers
        val g = bg.css.layers.single() as CssBackground.Radial
        assertTrue(g.circle)
        assertEquals(CssBackground.Length(0f, true), g.posX)
        assertEquals(CssBackground.Length(0f, true), g.posY)
        assertEquals(listOf(argb(0xFF112233), argb(0xFF445566), argb(0xCC000000)), g.stops.map { it.color })
        assertEquals(listOf(0f, 0.6f, 1f), g.stops.map { it.pos })

        val plain = ThemeResolver.parseBackground("radial-gradient(#000000, #ffffff)", 0) as ThemeBackground.Layers
        assertEquals(2, plain.css.layers.single().stops.size)

        assertEquals(ThemeBackground.Solid(0x44), ThemeResolver.parseBackground("radial-gradient(#ff0000)", 0x44))
        val themed = resolveWith("""{"lovelace-background": "radial-gradient(circle, #101020, #000000)"}""")
        assertTrue(themed.background is ThemeBackground.Layers)
    }

    @Test
    fun gradientStopsFollowCssOrderAcrossLinesAndFunctionalColors() {
        // An explicit position under an earlier one is raised first; the missing ones then spread between them.
        val bg = ThemeResolver.parseBackground("linear-gradient(red 90%, green, white 50%, black, blue 100%), #000000", 0)
            as ThemeBackground.Layers
        val layer = bg.css.layers.single()
        val geo = bg.css.geometry(layer, 100f, 100f, 1f)!!
        assertEquals(listOf(0.9f, 0.9f, 0.9f, 0.95f, 1f), geo.positions.toList().map { (it * 100).roundToInt() / 100f })
        // A theme can fold the value over lines.
        val folded = ThemeResolver.parseBackground("radial-gradient(\n  circle at top left,\n  #112233,\n  #445566\n)", 0)
        assertTrue(folded is ThemeBackground.Layers)
        // A first stop written as rgba() with spaces is a color stop, not a shape.
        val functional = CssBackground.parse("radial-gradient(rgba(0, 0, 0, 0.5) 10%, transparent)")!!.layers.single()
        assertEquals(2, functional.stops.size)
        assertEquals(0.1f, functional.stops[0].pos)
        // A single linear gradient keeps its stop positions for painting.
        val single = ThemeResolver.parseBackground("linear-gradient(90deg, red 20%, blue 80%)", 0) as ThemeBackground.LinearGradient
        assertEquals(listOf(0.2f, 0.8f), single.stops!!.map { it.pos })
    }

    @Test
    fun layeredBackgroundsKeepEveryLayerTheColorAndTheirGeometry() {
        val bg = ThemeResolver.parseBackground(
            "radial-gradient(1200px 700px at -10% -20%, rgba(56, 189, 248, 0.13), transparent 60%), " +
                "radial-gradient(900px 600px at 110% 120%, rgba(167, 139, 250, 0.14), transparent 60%), #0a0b0f",
            0,
        ) as ThemeBackground.Layers
        assertEquals(argb(0xFF0A0B0F), bg.css.color)
        val top = bg.css.layers[0] as CssBackground.Radial
        assertEquals(1200f, top.sizeX)
        assertEquals(700f, top.sizeY)
        assertEquals(CssBackground.Length(-10f, true), top.posX)
        // A 1280 x 800 box at 2x: the ellipse's center and radii, and `transparent` taking the color's RGB at alpha 0.
        val geo = bg.css.geometry(top, 2560f, 1600f, 2f)!!
        assertEquals(-256f, geo.x0, 0.01f)
        assertEquals(-320f, geo.y0, 0.01f)
        assertEquals(2400f, geo.rx, 0.01f)
        assertEquals(1400f, geo.ry, 0.01f)
        assertEquals(listOf(0f, 0.6f), geo.positions.toList())
        assertEquals(0x0038BDF8, geo.colors[1])

        val linear = CssBackground.parse("linear-gradient(to right, red 10px, blue)")!!.layers.single()
        val lg = CssBackground(listOf(linear), null).geometry(linear, 200f, 100f, 1f)!!
        assertEquals(0f, lg.x0, 0.01f)
        assertEquals(200f, lg.x1, 0.01f)
        assertEquals(0.05f, lg.positions[0], 0.001f)
    }

    @Test
    fun stateActiveColorReachesOnlyDomainsHaLeavesUncolored() {
        val t = resolveWith("""{"state-light-active-color": "#ff00ff", "state-active-color": "#00ff00"}""")
        assertEquals(argb(0xFFFF00FF), t.activeColor("light")) // the domain key wins
        // HA's base theme colors these domains itself, so the theme-wide key never reaches them (measured in HA).
        assertEquals(argb(0xFFFFC107), t.activeColor("switch"))
        assertEquals(argb(0xFF00BCD4), t.activeColor("fan"))
        assertEquals(argb(0xFF926BC7), t.activeColor("cover"))
        assertEquals(argb(0xFFFF6F22), t.activeColor("climate"))
        assertEquals(argb(0xFFFFC107), t.activeColor("binary_sensor"))
        assertEquals(argb(0xFF03A9F4), t.activeColor("media_player"))
        assertEquals(argb(0xFF00FF00), t.activeColor("automation")) // no base color: the theme-wide key applies
        assertEquals(argb(0xFF123456), resolveWith("""{"state-climate-heat-color": "#123456"}""").activeColor("climate"))
        assertEquals(argb(0xFF2196F3), t.activeColor("sensor")) // sensors and people keep their own colors
        assertEquals(argb(0xFF4CAF50), t.activeColor("person"))
    }

    @Test
    fun stateOnColorIsAnAlternativeToTheActiveColor() {
        val t = resolveWith("""{"state-switch-on-color": "#123456", "state-fan-active-color": "#abcdef", "state-fan-on-color": "#654321"}""")
        assertEquals(argb(0xFF123456), t.activeColor("switch"))
        assertEquals(argb(0xFFABCDEF), t.activeColor("fan")) // active-color is checked first
        assertEquals(argb(0xFFFFC107), t.activeColor("light")) // untouched domains keep HA's default
    }

    @Test
    fun cardGapFollowsTheViewMarginVariablesAndIsClamped() {
        assertEquals(8f, ThemeResolver.resolve(ThemeCatalog()).cardGapDp, 0.001f)
        assertEquals(12f, resolveWith("""{"masonry-view-card-margin": "12px"}""").cardGapDp, 0.001f)
        assertEquals(16f, resolveWith("""{"ha-view-sections-column-gap": "1rem"}""").cardGapDp, 0.001f)
        assertEquals(12f, resolveWith("""{"masonry-view-card-margin": "12px", "ha-view-sections-column-gap": "20px"}""").cardGapDp, 0.001f)
        assertEquals(4f, resolveWith("""{"masonry-view-card-margin": "0px"}""").cardGapDp, 0.001f)
        assertEquals(24f, resolveWith("""{"masonry-view-card-margin": "60px"}""").cardGapDp, 0.001f)
    }

    @Test
    fun aShorthandWithAValueVitroCantTakeIsDroppedWholeNotShifted() {
        val all = ThemeResolver.parseCardMod("ha-card { padding: 4px 8px 12px 16px; }").paddingInsets!!
        assertEquals(listOf(16f, 4f, 8f, 12f), listOf(all.left, all.top, all.right, all.bottom))
        // `10%` can't become dp: the whole padding stays unapplied rather than read as top 4, right 12, …
        assertEquals(null, ThemeResolver.parseCardMod("ha-card { padding: 4px 10% 12px 16px; }").paddingInsets)
    }

    @Test
    fun cardCustomPropertiesReferToEachOther() {
        val mod = ThemeResolver.parseCardMod("ha-card { --spacing: 16px; --inset: var(--spacing, 4px); padding: var(--inset); }")
        assertEquals(16f, mod.paddingInsets!!.top, 0.001f)
        assertEquals("16px", mod.customProperties["inset"])
    }

    @Test
    fun cyclicCardPropertiesAreInvalidAndExpansionIsBounded() {
        // Doubles on every pass with no theme: invalid, not 134 million references on the main thread.
        val t0 = System.nanoTime()
        val doubling = ThemeResolver.parseCardMod("ha-card { --a: var(--a) var(--a); padding: var(--a); }")
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
        assertEquals(null, doubling.paddingInsets)
        // A mutual cycle falls back like a missing variable.
        val mutual = ThemeResolver.parseCardMod("ha-card { --a: var(--b); --b: var(--a); padding: var(--a, 12px); }")
        assertEquals(12f, mutual.paddingInsets!!.top, 0.001f)
        // A deep chain that isn't a cycle still resolves.
        val chain = (1..6).joinToString(" ") { "--v$it: var(--v${it + 1});" } + " --v7: 9px;"
        val deep = ThemeResolver.parseCardMod("ha-card { $chain padding: var(--v1); }")
        assertEquals(9f, deep.paddingInsets!!.top, 0.001f)
        // Growth past the cap in one pass is empty, as an invalid value.
        assertEquals("", ThemeResolver.resolveValue("var(--x)", mapOf("x" to "y".repeat(20_000)), 0))
    }

    @Test
    fun aLongThemeCardModStylesheetKeepsItsStyling() {
        // A theme's card-mod-card over 16 KiB (a long comment first) with one short variable: it must survive whole.
        val sheet = "/* " + "x".repeat(17_000) + " */ ha-card { background-color: var(--brand); }"
        val theme = resolveWith("""{"brand": "#ff0000", "card-mod-card": ${org.json.JSONObject.quote(sheet)}}""")
        assertEquals(0xFFFF0000.toInt(), theme.cardMod.backgroundColor)
    }

    @Test
    fun cardModFontFamily() {
        // A list that runs over a line break is read whole.
        assertEquals("Missing, Pacifico, serif", ThemeResolver.parseCardMod("ha-card { font-family: Missing,\n    Pacifico, serif; }").fontFamily)
        // inherit (and unset) keeps the font the card would get anyway; initial is the browser's default font.
        assertEquals(null, ThemeResolver.parseCardMod("ha-card { font-family: inherit; }").fontFamily)
        assertEquals(null, ThemeResolver.parseCardMod("ha-card { font-family: unset; }").fontFamily)
        assertEquals(ThemeResolver.INITIAL_FONT_FAMILY, ThemeResolver.parseCardMod("ha-card { font-family: initial; }").fontFamily)
        assertEquals("Pacifico, cursive", ThemeResolver.parseCardMod("ha-card { font-family: Pacifico, cursive; color: red }").fontFamily)
    }

    @Test
    fun aQuotedCommentOpenerIsTextNotAComment() {
        // As the browser reads it: the string holds a slash-star, and the rule after it still applies.
        assertEquals("""a { content: "/*" }   b { c: d }""", ThemeResolver.stripCssComments("""a { content: "/*" } /* x */ b { c: d }"""))
        assertEquals("a { content: '*/ /*' }  ", ThemeResolver.stripCssComments("a { content: '*/ /*' } /* never closed"))
        // An escaped quote doesn't end the string; a newline does.
        assertEquals("""a { content: "\" /*" }""", ThemeResolver.stripCssComments("""a { content: "\" /*" }"""))
        assertEquals("a { content: \"x\n   b { c: d }", ThemeResolver.stripCssComments("a { content: \"x\n /**/ b { c: d }"))
        val rules = ThemeResolver.cssRules("""ha-card { --label: "/*" } ha-card .x { color: red } /* note */""")
        assertEquals(listOf("ha-card", "ha-card .x"), rules.map { it.first })
        assertEquals(listOf("--label" to "\"/*\""), rules.first().second)
    }

    @Test
    fun quotedBracesAndSemicolonsAreNotStructure() {
        // The string keeps its `/* { */` (it's no comment), and its brace doesn't open a block: the rule still applies.
        val quotedBrace = """ha-card { --label: "/* { */"; opacity: .5 }"""
        assertEquals(0.5f, ThemeResolver.parseCardMod(quotedBrace).opacity)
        assertEquals(listOf("--label" to "\"/* { */\"", "opacity" to ".5"), ThemeResolver.cssRules(quotedBrace).single().second)
        // A `;` in a string, or inside parentheses, doesn't end the declaration.
        val rules = ThemeResolver.cssRules("""ha-card { content: 'a;b}'; background: url(data:image/png;base64,AA); opacity: .5 } b { c: d }""")
        assertEquals(listOf("ha-card", "b"), rules.map { it.first })
        assertEquals(listOf("content" to "'a;b}'", "background" to "url(data:image/png;base64,AA)", "opacity" to ".5"), rules.first().second)
        // An escaped quote doesn't end the string.
        assertEquals(listOf("ha-card"), ThemeResolver.cssRules("""ha-card { content: "\" }"; opacity: .5 }""").map { it.first })
    }

    @Test
    fun aQuotedDeclarationIsTextNotAnotherDeclaration() {
        // The real `opacity` comes first; the string holds a fake one, which the browser reads as text.
        assertEquals(0.5f, ThemeResolver.parseCardMod("""ha-card { opacity: .5; --label: "x; opacity: 0.1" }""").opacity)
        assertEquals(0.5f, ThemeResolver.parseCardMod("""ha-card { opacity: .5; content: 'a; opacity: 0.1; b' }""").opacity)
        // Nor does a quoted custom property define one.
        assertNull(ThemeResolver.parseCardMod("""ha-card { --label: "a; --fill: red"; background: var(--fill) }""").backgroundColor)
        // Duplicates keep the cascade: the last one wins, an `!important` one before it beats it.
        assertEquals(0.5f, ThemeResolver.parseCardMod("ha-card { opacity: .3; opacity: .5 }").opacity)
        assertEquals(0.2f, ThemeResolver.parseCardMod("ha-card { opacity: .2 !important; opacity: .5 }").opacity)
        assertEquals(0.4f, ThemeResolver.parseCardMod("ha-card { opacity: .2 } :host { opacity: .4 }").opacity)
    }
}
