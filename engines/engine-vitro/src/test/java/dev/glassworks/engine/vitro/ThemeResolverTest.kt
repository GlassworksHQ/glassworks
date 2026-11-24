/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.theme.CardModStyle
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import dev.glassworks.engine.vitro.theme.ThemeBackground
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals(argb(0xFF03A9F4), t.primaryColor)
        assertEquals(argb(0xFFFF9800), t.accentColor)
        assertEquals(argb(0xFF111111), t.backgroundColor)
        assertEquals(argb(0xFF202020), t.secondaryBackgroundColor)
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
        assertEquals(argb(0xFF03A9F4), t.primaryColor)
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
        assertEquals(argb(0x3303A9F4), t.cardBackgroundColor)
        assertEquals(argb(0xFF112233), t.primaryColor)
        assertEquals("#112233", t.vars["brand"])
        assertEquals("rgba(3, 169, 244, 0.2)", t.vars["card-background-color"])
    }

    @Test(timeout = 5_000)
    fun resolveSurvivesCyclesInATheme() {
        val t = resolveWith("""{"primary-color": "var(--accent-color)", "accent-color": "var(--primary-color)"}""")
        // Unresolvable, so the color falls back to HA's default blue and orange.
        assertEquals(argb(0xFF03A9F4), t.primaryColor)
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
    fun cssRootBlockExportsVariablesAndSkipsCardMod() {
        val t = resolveWith(
            """{"primary-color": "#ff5722", "card-mod-card": "ha-card { opacity: 0.9; }", "card-mod-card-yaml": "opacity: 0.9", "multi": "a;b\nc"}""",
        )
        val css = t.toCssRootBlock()
        assertTrue(css.startsWith(":root {\n"))
        assertTrue(css.endsWith("}\n"))
        assertTrue(css.contains("  --primary-color: #ff5722;\n"))
        assertTrue(css.contains("  --primary-background-color: #111111;\n")) // a default is exported too
        assertTrue(css.contains("  --rgb-primary-color: 3, 169, 244;\n"))
        assertTrue("; and newlines in values cannot break out of the declaration", css.contains("  --multi: ab c;\n"))
        assertFalse(css.contains("card-mod"))
    }

    @Test
    fun hexFormatsAnAndroidColorForTheOverlayTheme() {
        val t = ThemeResolver.resolve(ThemeCatalog())
        assertEquals("#03a9f4", t.hex(t.primaryColor))
        assertEquals("#ffffff", t.hex(argb(0x80FFFFFF))) // alpha dropped
        assertEquals("#000000", t.hex(0))
    }

    @Test
    fun activeColorFollowsTheDomainKeyThenAHomeAssistantDefault() {
        val t = resolveWith("""{"state-light-active-color": "#ff00ff", "state-lock-active-color": "#00ffff"}""")
        assertEquals(argb(0xFFFF00FF), t.activeColor("light"))
        assertEquals(argb(0xFF00FFFF), t.activeColor("lock")) // a domain the table does not list
        assertEquals(argb(0xFFFF8100), t.activeColor("climate")) // heat color
        assertEquals(argb(0xFFFFC107), t.activeColor("switch"))
        assertEquals(argb(0xFFFFC107), t.activeColor("input_boolean"))
        assertEquals(argb(0xFFFFC107), t.activeColor("binary_sensor"))
        assertEquals(argb(0xFF009688), t.activeColor("fan"))
        assertEquals(argb(0xFF9C27B0), t.activeColor("cover"))
        assertEquals(argb(0xFF4CAF50), t.activeColor("person"))
        assertEquals(argb(0xFF4CAF50), t.activeColor("device_tracker"))
        assertEquals(argb(0xFF2196F3), t.activeColor("sensor")) // HA blue
        assertEquals(t.primaryColor, t.activeColor("weather")) // no rule: the primary color
    }
}
