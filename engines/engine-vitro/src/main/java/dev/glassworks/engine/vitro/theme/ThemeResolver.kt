/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.theme

import dev.glassworks.engine.vitro.cards.CssSelector
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Background description parsed from HA's `lovelace-background` / `primary-background-color`. */
sealed class ThemeBackground {
    data class Solid(val color: Int) : ThemeBackground()
    /** CSS `linear-gradient(...)`: the angle plus every parsed color stop, in order. */
    data class LinearGradient(val angleDeg: Float, val colors: IntArray, val stops: List<CssBackground.Stop>? = null) : ThemeBackground()

    /** CSS `radial-gradient(...)`: every parsed color stop, center to edge. Shape and position keywords are ignored. */
    data class RadialGradient(val colors: IntArray) : ThemeBackground()
    data class ImageUrl(val url: String, val fallbackColor: Int) : ThemeBackground()

    /** Several gradient layers, or a radial one with a size or position: painted as the browser stacks them. */
    data class Layers(val css: CssBackground, val fallbackColor: Int) : ThemeBackground()
}

/** CSS box insets (in dp), matching 1/2/3/4-value shorthand and per-side properties. */
data class EdgeInsetsDp(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Parsed container-level `card-mod` styles that Vitro can apply natively without a browser. */
data class CardModStyle(
    val backgroundColor: Int? = null,
    val borderRadiusDp: Float? = null,
    val borderWidthDp: Float? = null,
    val borderColor: Int? = null,
    val opacity: Float? = null,
    val blurRadiusDp: Float? = null,
    /** True when the theme contains deep Shadow-DOM / keyframe selectors Vitro cannot mirror natively. */
    val hasComplexSelectors: Boolean = false,
    /** `background:` / `background-image:` holding a `linear-gradient(...)`. */
    val backgroundGradient: ThemeBackground.LinearGradient? = null,
    /** `background:` / `background-image:` holding any gradient, every layer and stop position as CSS paints it. */
    val backgroundLayers: CssBackground? = null,
    /** `padding:` top value in dp (kept for callers checking uniform / zero padding); full sides live in [paddingInsets]. */
    val paddingDp: Float? = null,
    /** Full CSS `padding` / `padding-{top,right,bottom,left}` box in dp. */
    val paddingInsets: EdgeInsetsDp? = null,
    /** CSS `gap` / `grid-gap` / `row-gap` in dp (for stack/grid containers). */
    val gapDp: Float? = null,
    /** `font-size:` in sp (`px`/`sp` as-is, `rem`/`em` x16). Cards read it from [CardSurface.activeCardMod]. */
    val fontSizeSp: Float? = null,
    /** `font-weight:` as a CSS weight (100..900); keywords map to 300/400/500/600/700. */
    val fontWeight: Int? = null,
    /** `letter-spacing:` in em (`2px` is divided by the font size, 16 when none is set). */
    val letterSpacingEm: Float? = null,
    /** Duration of a `transition:` on `opacity` (or `all`), in ms, clamped to 0..3000. */
    val transitionOpacityMs: Long? = null,
    /** `position: fixed` / `absolute` on the card itself: Vitro pins it to the viewport overlay layer. */
    val positionFixed: Boolean = false,
    /** Raw CSS offsets for a pinned card (`24px`, `50%`, ...). */
    val fixedTop: String? = null,
    val fixedRight: String? = null,
    val fixedBottom: String? = null,
    val fixedLeft: String? = null,
    val fixedWidth: String? = null,
    /** `transform: translateY(-50%)` / `translate(x, -50%)`: center vertically. */
    val fixedCenterY: Boolean = false,
    /** `transform: translateX(-50%)` / `translate(-50%, y)`: center horizontally. */
    val fixedCenterX: Boolean = false,
    /** `color:` (the text color, not `background-color`). */
    val textColor: Int? = null,
    /** `text-align:` lowercased (`left`, `start`, `center`, `right`, `end`), or null. */
    val textAlign: String? = null,
    /**
     * CSS custom properties declared on the card (`ha-card { --card-text-align: right; }`), names without `--`,
     * values with `var()` resolved. In the browser they inherit into every shadow root of the card, so a card
     * reads them the way HA's own CSS does (`--card-text-align`, `--markdown-image-border-radius`,
     * `--ha-clock-card-digital-font-size-large`, `--vertical-stack-card-gap`, ...), falling back to the theme.
     */
    val customProperties: Map<String, String> = emptyMap(),
    /** CSS `margin` on the card (an inset inside its grid cell or stack slot), in dp. */
    val marginInsets: EdgeInsetsDp? = null,
    /** `box-shadow` on the card as a native elevation in dp (`none` is 0), or null to keep the theme's. */
    val elevationDp: Float? = null,
)

/** HA's base theme (`color.globals`): the domains whose `--state-<domain>-active-color` it defines itself. */
private val HA_BASE_ACTIVE: Map<String, Int> = mapOf(
    "light" to 0xFFFFC107, "switch" to 0xFFFFC107, "input_boolean" to 0xFFFFC107, "binary_sensor" to 0xFFFFC107,
    "fan" to 0xFF00BCD4, "cover" to 0xFF926BC7, "media_player" to 0xFF03A9F4, "vacuum" to 0xFF009688,
    "valve" to 0xFF2196F3, "siren" to 0xFFF44336, "update" to 0xFFFF9800, "lawn_mower" to 0xFF009688,
    "climate" to 0xFFFF6F22,
).mapValues { it.value.toInt() }

/** Resolved, native-ready Home Assistant theme for one view or card. */
data class ResolvedTheme(
    val name: String,
    val dark: Boolean,
    val vars: Map<String, String>,
    val primaryColor: Int,
    val accentColor: Int,
    val backgroundColor: Int,
    val secondaryBackgroundColor: Int,
    val cardBackgroundColor: Int,
    val primaryTextColor: Int,
    val secondaryTextColor: Int,
    val disabledTextColor: Int,
    val dividerColor: Int,
    val cardBorderColor: Int,
    val cardBorderWidthDp: Float,
    val cardBorderRadiusDp: Float,
    val background: ThemeBackground,
    val cardMod: CardModStyle = CardModStyle(),
    /** HA's `--state-icon-color`: the steel-blue of icons on plain `sensor` tiles and inactive rows. */
    val stateIconColor: Int = 0xFF44739E.toInt(),
    /** HA's `--state-inactive-color`: the grey of an inactive toggleable tile. */
    val stateInactiveColor: Int = 0xFF9E9E9E.toInt(),
    /** Native elevation derived from HA's `--ha-card-box-shadow` (0 when the theme has none or says `none`). */
    val cardElevationDp: Float = 0f,
    /** Gap between cards: `--masonry-view-card-margin` or `--ha-view-sections-column-gap`, clamped to 4..24 dp. */
    val cardGapDp: Float = 8f,
    /** Outer padding of a view: `--masonry-view-padding` / `--vitro-view-padding`, else [cardGapDp] (masonry and panel views; sections views use HA's `--ha-view-sections-*` variables). */
    val viewPaddingDp: Float = cardGapDp,
    /**
     * Variable names the theme itself set (as opposed to HA defaults Vitro fills in). Null treats every entry
     * of [vars] as set by the theme. It lets `--state-active-color` beat the built-in per-domain defaults.
     */
    val themeVarNames: Set<String>? = null,
    /**
     * Text size multiplier: `--vitro-font-scale`, else HA's `--ha-font-size-m` over its 14 px default,
     * clamped to 0.7..2.2. Cards apply it through [dev.glassworks.engine.vitro.ui.VitroCardContext.scaledSp].
     */
    val fontScale: Float = 1f,
) {
    private fun themeVar(key: String): String? =
        vars[key]?.takeIf { themeVarNames == null || key in themeVarNames }

    /**
     * Domain active color following HA's `stateColorCss`: `--state-<domain>-active-color` (or `-on-color`) from the
     * theme, then HA's base-theme color for the domains it defines (lights and switches amber, media players light
     * blue, ...), which a theme-wide `--state-active-color` can't reach, then `--state-active-color`, then defaults.
     */
    fun activeColor(domain: String): Int {
        val d = domain.lowercase(Locale.ROOT)
        ThemeResolver.parseColor(themeVar("state-$d-active-color"))?.let { return it }
        ThemeResolver.parseColor(themeVar("state-$d-on-color"))?.let { return it }
        HA_BASE_ACTIVE[d]?.let { base ->
            ThemeResolver.parseColor(vars["state-$d-active-color"])?.let { return it }
            // A climate's active color is its heat color, which a theme may set on its own.
            if (d == "climate") ThemeResolver.parseColor(vars["state-climate-heat-color"])?.let { return it }
            return base
        }
        if (d != "sensor" && d != "person" && d != "device_tracker") {
            ThemeResolver.parseColor(themeVar("state-active-color"))?.let { return it }
        }
        ThemeResolver.parseColor(vars["state-$d-active-color"])?.let { return it }
        return when (domain) {
            "light" -> ThemeResolver.parseColor(vars["state-light-active-color"]) ?: 0xFFFFC107.toInt()
            "switch", "input_boolean" -> ThemeResolver.parseColor(vars["state-switch-active-color"]) ?: 0xFFFFC107.toInt()
            "climate" -> ThemeResolver.parseColor(vars["state-climate-heat-color"]) ?: 0xFFFF6F22.toInt()
            "binary_sensor" -> ThemeResolver.parseColor(vars["state-binary_sensor-active-color"]) ?: 0xFFFFC107.toInt()
            "sensor" -> ThemeResolver.parseColor(vars["state-sensor-active-color"]) ?: 0xFF2196F3.toInt()
            "fan" -> ThemeResolver.parseColor(vars["state-fan-active-color"]) ?: 0xFF00BCD4.toInt()
            "cover" -> ThemeResolver.parseColor(vars["state-cover-active-color"]) ?: 0xFF926BC7.toInt()
            "person", "device_tracker" -> ThemeResolver.parseColor(vars["state-person-home-color"]) ?: 0xFF4CAF50.toInt()
            else -> primaryColor
        }
    }

    /** Export resolved CSS custom properties for Tier 3 Micro-Web Islands so web cards match native cards. */
    fun toCssRootBlock(): String = buildString {
        append(":root {\n")
        for ((k, v) in vars) {
            if (k.startsWith("card-mod-")) continue
            val safeVal = v.replace("\n", " ").replace(";", "")
            append("  --").append(k).append(": ").append(safeVal).append(";\n")
        }
        append("}\n")
    }

    /** `#rrggbb` hex string for Glassworks overlay theme sync (`haTheme`). */
    fun hex(color: Int): String = String.format(Locale.ROOT, "#%06x", color and 0xFFFFFF)
}

/** Collection of themes returned by Home Assistant's `frontend/get_themes`. */
data class ThemeCatalog(
    val defaultTheme: String = "default",
    val defaultDarkTheme: String? = null,
    val themes: Map<String, ThemeDefinition> = emptyMap(),
) {
    data class ThemeDefinition(
        val base: Map<String, String>,
        val light: Map<String, String> = emptyMap(),
        val dark: Map<String, String> = emptyMap(),
    )

    companion object {
        fun parse(raw: JSONObject?): ThemeCatalog {
            if (raw == null) return ThemeCatalog()
            val defTheme = raw.optString("default_theme", "default").ifEmpty { "default" }
            val defDark = raw.optString("default_dark_theme", "").ifEmpty { null }
            val themesObj = raw.optJSONObject("themes") ?: return ThemeCatalog(defTheme, defDark)
            val map = LinkedHashMap<String, ThemeDefinition>()
            val names = themesObj.keys()
            while (names.hasNext()) {
                val name = names.next()
                val body = themesObj.optJSONObject(name) ?: continue
                val base = LinkedHashMap<String, String>()
                var light = emptyMap<String, String>()
                var dark = emptyMap<String, String>()
                val keys = body.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "modes") {
                        val modes = body.optJSONObject("modes")
                        light = readStringMap(modes?.optJSONObject("light"))
                        dark = readStringMap(modes?.optJSONObject("dark"))
                    } else {
                        val v = body.opt(k)
                        if (v != null && v != JSONObject.NULL) base[k] = v.toString()
                    }
                }
                map[name] = ThemeDefinition(base, light, dark)
            }
            return ThemeCatalog(defTheme, defDark, map)
        }

        private fun readStringMap(obj: JSONObject?): Map<String, String> {
            if (obj == null) return emptyMap()
            val out = LinkedHashMap<String, String>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.opt(k)
                if (v != null && v != JSONObject.NULL) out[k] = v.toString()
            }
            return out
        }
    }
}

/**
 * Resolves Home Assistant YAML themes (including `modes`, `var(--...)` chains, `rgb-*` triples,
 * CSS gradients, background URLs, and basic `card-mod-card` container rules) into [ResolvedTheme].
 */
object ThemeResolver {
    private val VAR_PATTERN = Regex("""var\(\s*--([a-zA-Z0-9_-]+)\s*(?:,\s*([^)]+))?\)""")
    private val RGBA_PATTERN = Regex("""rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)(?:\s*[,/]\s*([\d.]+%?))?\s*\)""", RegexOption.IGNORE_CASE)
    private val HSLA_PATTERN = Regex("""hsla?\(\s*([\d.]+)(?:deg)?\s*,\s*([\d.]+)%\s*,\s*([\d.]+)%(?:\s*[,/]\s*([\d.]+%?))?\s*\)""", RegexOption.IGNORE_CASE)
    private val URL_PATTERN = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
    private val LINEAR_GRADIENT_PATTERN = Regex("""linear-gradient\(\s*(?:([\d.]+)deg\s*,)?\s*(.+)\)""", RegexOption.IGNORE_CASE)
    private val RADIAL_GRADIENT_PATTERN = Regex("""radial-gradient\(\s*(.+)\)""", RegexOption.IGNORE_CASE)
    private const val HA_DEFAULT_FONT_SIZE_PX = 14f
    private const val MIN_FONT_SCALE = 0.7f
    private const val MAX_FONT_SCALE = 2.2f
    // Compiled once: card_mod is parsed for every card a view builds, and compiling a pattern per property lookup
    // cost a slow panel more than a second per view.
    private val BLUR = Regex("""blur\(\s*([\d.]+)px\s*\)""", RegexOption.IGNORE_CASE)
    private val PX_NUMBER = Regex("""([\d.]+)px""")
    private val WHITESPACE = Regex("""\s+""")
    private val CUSTOM_PROPERTY = Regex("""(?:^|[;\n{])\s*--([\w-]+)\s*:\s*([^;\n]+)""")
    private val LETTER_SPACING = Regex("""(-?[\d.]+)\s*(em|px|rem)?""", RegexOption.IGNORE_CASE)
    private val TRANSLATE = Regex("""translate\(([^,)]+)(?:,([^)]+))?\)""")
    private val STOP_PERCENT = Regex("""\s+[\d.]+%$""")
    private val STOP_POSITION = Regex("""\s+[\d.]+(?:%|px)$""")
    // One pattern per property name; the names are the literals below, so these stay small.
    private val declProps = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private val themeVarProps = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private fun declProp(name: String): Regex = declProps.getOrPut(name) {
        // The value keeps a trailing `!important`: [parseCardMod] lets it win, as the browser does.
        Regex("""(?:^|[;\n])\s*$name\s*:\s*([^;\n]+)""", RegexOption.IGNORE_CASE)
    }
    private fun themeVarProp(name: String): Regex = themeVarProps.getOrPut(name) {
        Regex("""(?:^|[;\n{])\s*$name\s*:\s*([^;!\n}]+)""", RegexOption.IGNORE_CASE)
    }

    private val JINJA_PATTERN = Regex("""\{%.*?%\}|\{\{.*?\}\}|\{#.*?#\}""", RegexOption.DOT_MATCHES_ALL)
    private val CSS_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val TIME_PATTERN = Regex("""([\d.]+)(ms|s)""")
    private val DIM_PX = Regex("""([\d.]+)\s*(px|rem|em)?""", RegexOption.IGNORE_CASE)
    private val SHADOW_COLOR = Regex("""(?:rgba?|hsla?)\([^)]*\)""", RegexOption.IGNORE_CASE)
    private val SHADOW_LENGTH = Regex("""(?<![\w#.])(-?[\d.]+)(?:px)?(?=\s|$)""", RegexOption.IGNORE_CASE)

    private val DARK_DEFAULTS = mapOf(
        "primary-color" to "#03a9f4",
        "accent-color" to "#ff9800",
        "primary-background-color" to "#111111",
        "secondary-background-color" to "#202020",
        "card-background-color" to "#1c1c1c",
        "ha-card-background" to "var(--card-background-color)",
        "primary-text-color" to "#e1e1e1",
        "secondary-text-color" to "#9b9b9b",
        "disabled-text-color" to "#6f6f6f",
        "divider-color" to "rgba(225, 225, 225, 0.12)",
        "ha-card-border-color" to "#2c2c2c",
        "ha-card-border-width" to "1px",
        "ha-card-border-radius" to "12px",
        "rgb-primary-color" to "3, 169, 244",
        "rgb-accent-color" to "255, 152, 0",
        "rgb-primary-text-color" to "225, 225, 225",
        "rgb-card-background-color" to "28, 28, 28",
        "state-light-active-color" to "#ffc107",
        "state-switch-active-color" to "#ffc107",
        "state-climate-heat-color" to "#ff6f22",
        "state-climate-cool-color" to "#2196f3",
        "state-binary_sensor-active-color" to "#ffc107",
        "state-sensor-active-color" to "#2196f3",
        "state-fan-active-color" to "#00BCD4",
        "state-cover-active-color" to "#926bc7",
        "state-person-home-color" to "#4caf50",
        "state-icon-color" to "#44739e",
        "state-inactive-color" to "#9e9e9e",
    )

    private val LIGHT_DEFAULTS = mapOf(
        "primary-color" to "#03a9f4",
        "accent-color" to "#ff9800",
        "primary-background-color" to "#fafafa",
        "secondary-background-color" to "#e5e5e5",
        "card-background-color" to "#ffffff",
        "ha-card-background" to "var(--card-background-color)",
        "primary-text-color" to "#212121",
        "secondary-text-color" to "#727272",
        "disabled-text-color" to "#bdbdbd",
        "divider-color" to "rgba(0, 0, 0, 0.12)",
        "ha-card-border-color" to "#e0e0e0",
        "ha-card-border-width" to "1px",
        "ha-card-border-radius" to "12px",
        "rgb-primary-color" to "3, 169, 244",
        "rgb-accent-color" to "255, 152, 0",
        "rgb-primary-text-color" to "33, 33, 33",
        "rgb-card-background-color" to "255, 255, 255",
        "state-light-active-color" to "#ffc107",
        "state-switch-active-color" to "#ffc107",
        "state-climate-heat-color" to "#ff6f22",
        "state-climate-cool-color" to "#2196f3",
        "state-binary_sensor-active-color" to "#ffc107",
        "state-sensor-active-color" to "#2196f3",
        "state-fan-active-color" to "#00BCD4",
        "state-cover-active-color" to "#926bc7",
        "state-person-home-color" to "#4caf50",
        "state-icon-color" to "#44739e",
        "state-inactive-color" to "#9e9e9e",
    )

    fun resolve(
        catalog: ThemeCatalog,
        dark: Boolean = true,
        overrideTheme: String? = null,
        viewBackground: String? = null,
    ): ResolvedTheme {
        val raw = LinkedHashMap<String, String>(if (dark) DARK_DEFAULTS else LIGHT_DEFAULTS)
        val pickedName = overrideTheme?.takeIf { it.isNotBlank() && it != "Backend-selected" }
            ?: (if (dark) catalog.defaultDarkTheme else null)
            ?: catalog.defaultTheme

        catalog.themes[pickedName]?.let { def ->
            raw.putAll(def.base)
            raw.putAll(if (dark) def.dark else def.light)
        }

        val themeVarNames = LinkedHashSet<String>()
        catalog.themes[pickedName]?.let { def ->
            themeVarNames += def.base.keys
            themeVarNames += if (dark) def.dark.keys else def.light.keys
        }

        val resolved = LinkedHashMap<String, String>(raw.size)
        for (k in raw.keys) {
            resolved[k] = resolveValue(raw[k].orEmpty(), raw, 0)
        }

        val bg = parseColor(resolved["primary-background-color"]) ?: if (dark) 0xFF111111.toInt() else 0xFFFAFAFA.toInt()
        val cardMod = parseCardMod(resolved)
        val cardBg = cardMod.backgroundColor
            ?: parseColor(resolved["ha-card-background"])
            ?: parseColor(resolved["card-background-color"])
            ?: if (dark) 0xFF1C1C1C.toInt() else 0xFFFFFFFF.toInt()
        val borderRadius = cardMod.borderRadiusDp ?: parseDimensionDp(resolved["ha-card-border-radius"], 12f)
        val borderWidth = cardMod.borderWidthDp ?: parseDimensionDp(resolved["ha-card-border-width"], 1f)
        val borderColor = cardMod.borderColor
            ?: parseColor(resolved["ha-card-border-color"])
            ?: parseColor(resolved["divider-color"])
            ?: if (dark) 0xFF2C2C2C.toInt() else 0xFFE0E0E0.toInt()

        val bgSpec = viewBackground?.takeIf { it.isNotBlank() } ?: resolved["lovelace-background"]
        val background = parseBackground(bgSpec, bg)

        val cardGap = (resolved["masonry-view-card-margin"] ?: resolved["ha-view-sections-column-gap"])
            ?.let { parseDimensionDp(it, 8f) }?.coerceIn(4f, 24f) ?: 8f
        val viewPad = (resolved["masonry-view-padding"] ?: resolved["vitro-view-padding"])
            ?.let { parseDimensionDp(it, -1f) }?.takeIf { it >= 0f }?.coerceIn(0f, 48f) ?: cardGap

        return ResolvedTheme(
            name = pickedName,
            dark = dark,
            vars = resolved,
            primaryColor = parseColor(resolved["primary-color"]) ?: 0xFF03A9F4.toInt(),
            accentColor = parseColor(resolved["accent-color"]) ?: 0xFFFF9800.toInt(),
            backgroundColor = bg,
            secondaryBackgroundColor = parseColor(resolved["secondary-background-color"]) ?: bg,
            cardBackgroundColor = cardBg,
            primaryTextColor = parseColor(resolved["primary-text-color"]) ?: if (dark) 0xFFE1E1E1.toInt() else 0xFF212121.toInt(),
            secondaryTextColor = parseColor(resolved["secondary-text-color"]) ?: if (dark) 0xFF9B9B9B.toInt() else 0xFF727272.toInt(),
            disabledTextColor = parseColor(resolved["disabled-text-color"]) ?: 0xFF6F6F6F.toInt(),
            dividerColor = parseColor(resolved["divider-color"]) ?: borderColor,
            cardBorderColor = borderColor,
            cardBorderWidthDp = borderWidth,
            cardBorderRadiusDp = borderRadius,
            background = background,
            cardMod = cardMod,
            stateIconColor = parseColor(resolved["state-icon-color"]) ?: 0xFF44739E.toInt(),
            stateInactiveColor = parseColor(resolved["state-inactive-color"]) ?: 0xFF9E9E9E.toInt(),
            cardElevationDp = parseBoxShadowElevationDp(resolved["ha-card-box-shadow"]),
            cardGapDp = cardGap,
            viewPaddingDp = viewPad,
            themeVarNames = themeVarNames,
            fontScale = parseFontScale(resolved),
        )
    }

    /**
     * Reads the theme's text scale: `--vitro-font-scale` (a plain multiplier such as `1.25`), else HA's
     * `--ha-font-size-m` (px or rem) divided by HA's default of 14 px. Clamped to 0.7..2.2; 1 when neither is set.
     */
    fun parseFontScale(vars: Map<String, String>): Float {
        vars["vitro-font-scale"]?.trim()?.removeSuffix("x")?.toFloatOrNull()
            ?.takeIf { it.isFinite() && it > 0f }
            ?.let { return it.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE) }
        val px = parseDimensionDp(vars["ha-font-size-m"], -1f)
        if (!px.isFinite() || px <= 0f) return 1f
        return (px / HA_DEFAULT_FONT_SIZE_PX).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    }

    /**
     * Maps a CSS `box-shadow` (`0px 2px 6px rgba(0,0,0,0.2)`) to an Android elevation of 1..12 dp: half the blur
     * plus the vertical offset, from the first visible outer shadow. `none`, blank, unparsable values and shadows
     * that are all inset or fully transparent give 0.
     */
    fun parseBoxShadowElevationDp(raw: String?): Float {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.equals("none", ignoreCase = true)) return 0f
        val first = splitTopLevelCommas(value).map { it.trim() }.firstOrNull { it.isNotEmpty() && isVisibleOuterShadow(it) } ?: return 0f
        val lengths = SHADOW_LENGTH.findAll(SHADOW_COLOR.replace(first, " ").trim())
            .mapNotNull { it.groupValues[1].toFloatOrNull() }.toList()
        if (lengths.size < 2) return 0f
        val offsetY = lengths[1]
        val blur = lengths.getOrElse(2) { 0f }.coerceAtLeast(0f)
        return (blur / 2f + abs(offsetY)).coerceIn(1f, 12f)
    }

    /** False for an inset shadow and for a transparent one (`transparent`, `rgba(0,0,0,0)`), which draw no elevation. */
    private fun isVisibleOuterShadow(layer: String): Boolean {
        val tokens = splitTopLevelSpaces(layer)
        if (tokens.any { it.equals("inset", ignoreCase = true) }) return false
        val color = tokens.firstOrNull { !SHADOW_LENGTH.matches(it) } ?: return true
        return !color.equals("transparent", ignoreCase = true) && parseColor(color)?.let { it ushr 24 } != 0
    }

    /** Recursively substitutes `var(--name, fallback)` up to depth 8. */
    fun resolveValue(input: String, map: Map<String, String>, depth: Int): String {
        if (depth >= 8 || "var(" !in input) return input.trim()
        val replaced = VAR_PATTERN.replace(input) { m ->
            val key = m.groupValues[1]
            val fallback = m.groupValues.getOrNull(2)?.trim().orEmpty()
            map[key] ?: fallback
        }
        return if (replaced == input) replaced.trim() else resolveValue(replaced, map, depth + 1)
    }

    /** Parses CSS colors (`#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb()`, `rgba()`, `hsl()`, `hsla()`, named) to Android ARGB. */
    fun parseColor(raw: String?): Int? {
        val s = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        NAMED_COLORS[s]?.let { return it }
        if (s.startsWith("#")) {
            val hex = s.substring(1)
            return when (hex.length) {
                3 -> {
                    val r = hex[0].digitToIntOrNull(16) ?: return null
                    val g = hex[1].digitToIntOrNull(16) ?: return null
                    val b = hex[2].digitToIntOrNull(16) ?: return null
                    argb(255, r * 17, g * 17, b * 17)
                }
                4 -> {
                    val r = hex[0].digitToIntOrNull(16) ?: return null
                    val g = hex[1].digitToIntOrNull(16) ?: return null
                    val b = hex[2].digitToIntOrNull(16) ?: return null
                    val a = hex[3].digitToIntOrNull(16) ?: return null
                    argb(a * 17, r * 17, g * 17, b * 17)
                }
                6 -> hex.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
                8 -> {
                    // CSS #RRGGBBAA -> Android 0xAARRGGBB
                    val v = hex.toLongOrNull(16) ?: return null
                    val rrggbb = (v ushr 8) and 0xFFFFFFL
                    val aa = v and 0xFFL
                    ((aa shl 24) or rrggbb).toInt()
                }
                else -> null
            }
        }
        RGBA_PATTERN.matchEntire(s)?.let { m ->
            val r = m.groupValues[1].toDoubleOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
            val g = m.groupValues[2].toDoubleOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
            val b = m.groupValues[3].toDoubleOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
            val a = parseAlpha(m.groupValues.getOrNull(4))
            return argb(a, r, g, b)
        }
        HSLA_PATTERN.matchEntire(s)?.let { m ->
            val h = ((m.groupValues[1].toDoubleOrNull() ?: return null) % 360.0 + 360.0) % 360.0
            val sat = (m.groupValues[2].toDoubleOrNull() ?: return null) / 100.0
            val light = (m.groupValues[3].toDoubleOrNull() ?: return null) / 100.0
            val a = parseAlpha(m.groupValues.getOrNull(4))
            return hslToArgb(a, h, sat.coerceIn(0.0, 1.0), light.coerceIn(0.0, 1.0))
        }
        return null
    }

    fun parseDimensionDp(raw: String?, defaultDp: Float): Float {
        val m = DIM_PX.find(raw.orEmpty()) ?: return defaultDp
        val value = m.groupValues[1].toFloatOrNull() ?: return defaultDp
        return when (m.groupValues[2].lowercase(Locale.ROOT)) {
            "rem", "em" -> value * 16f
            else -> value
        }
    }

    fun parseBackground(spec: String?, fallbackColor: Int): ThemeBackground {
        if (spec.isNullOrBlank()) return ThemeBackground.Solid(fallbackColor)
        URL_PATTERN.find(spec)?.let { m ->
            return ThemeBackground.ImageUrl(m.groupValues[1].trim(), fallbackColor)
        }
        val css = CssBackground.parse(spec)
        css?.takeIf { it.layers.size > 1 || it.color != null || it.layers[0] is CssBackground.Radial }
            ?.let { return ThemeBackground.Layers(it, fallbackColor) }
        LINEAR_GRADIENT_PATTERN.find(spec)?.let { m ->
            val parts = splitTopLevelCommas(m.groupValues[2])
            // CSS side keywords (`to right`, `to bottom left`) become their angle; a plain list defaults to 180deg.
            val sideAngle = parts.firstOrNull()?.trim()?.lowercase(Locale.ROOT)
                ?.takeIf { it.startsWith("to ") }?.let(::sideToAngle)
            val angle = m.groupValues[1].toFloatOrNull() ?: sideAngle ?: 180f
            val stopParts = if (sideAngle != null) parts.drop(1) else parts
            val stops = stopParts.mapNotNull { stop ->
                val cleaned = stop.trim().replace(STOP_PERCENT, "")
                parseColor(cleaned)
            }
            // Keep the parsed stops (with their positions) for painting; `colors` stays the plain list.
            if (stops.size >= 2) return ThemeBackground.LinearGradient(angle, stops.toIntArray(), (css?.layers?.singleOrNull() as? CssBackground.Linear)?.stops)
        }
        RADIAL_GRADIENT_PATTERN.find(spec)?.let { m ->
            val stops = splitTopLevelCommas(m.groupValues[1]).mapNotNull { stop ->
                parseColor(stop.trim().replace(STOP_POSITION, ""))
            }
            if (stops.size >= 2) return ThemeBackground.RadialGradient(stops.toIntArray())
        }
        parseColor(spec)?.let { return ThemeBackground.Solid(it) }
        return ThemeBackground.Solid(fallbackColor)
    }

    /** Extracts safe container CSS properties from `card-mod-card` / `card-mod-card-yaml` and flags complex rules. */
    fun parseCardMod(vars: Map<String, String>): CardModStyle {
        val css = vars["card-mod-card"].orEmpty() + "\n" + vars["card-mod-card-yaml"].orEmpty()
        if (css.isBlank()) return CardModStyle()
        val complex = "$" in css || "::part" in css || "@keyframes" in css || "{{" in css || "ha-state-icon" in css
        val lowerCss = css.lowercase(Locale.ROOT)
        fun prop(name: String): String? = if (name !in lowerCss) null else
            themeVarProp(name).find(css)?.groupValues?.get(1)?.trim()

        val bg = prop("background-color")?.let(::parseColor) ?: prop("background")?.let(::parseColor)
        val radius = prop("border-radius")?.let { parseDimensionDp(it, -1f) }?.takeIf { it >= 0f }
        val opacity = prop("opacity")?.toFloatOrNull()?.coerceIn(0f, 1f)
        val blur = prop("backdrop-filter")?.let { bf ->
            BLUR.find(bf)?.groupValues?.get(1)?.toFloatOrNull()
        }
        var bw: Float? = null
        var bc: Int? = null
        prop("border")?.let { border ->
            PX_NUMBER.find(border)?.groupValues?.get(1)?.toFloatOrNull()?.let { bw = it }
            border.split(WHITESPACE).lastOrNull()?.let(::parseColor)?.let { bc = it }
        }
        prop("border-width")?.let { parseDimensionDp(it, -1f) }?.takeIf { it >= 0f }?.let { bw = it }
        prop("border-color")?.let(::parseColor)?.let { bc = it }

        return CardModStyle(bg, radius, bw, bc, opacity, blur, complex)
    }

    /**
     * Parses one card's `card_mod.style` CSS (already rendered if it was a template). Takes declarations inside
     * `ha-card { }` / `:host { }` blocks and bare top-level declarations; rules for other selectors only set
     * [CardModStyle.hasComplexSelectors]. `var(--x)` resolves against [vars] (theme variables, no `--` prefix).
     */
    fun parseCardMod(css: String, vars: Map<String, String> = emptyMap()): CardModStyle {
        val key = CardModKey(css, vars)
        synchronized(cardModCache) { cardModCache[key] }?.let { return it }
        val parsed = parseCardModUncached(css, vars)
        synchronized(cardModCache) { cardModCache[key] = parsed }
        return parsed
    }

    /**
     * A view parses the same `card_mod` text several times per card (layout, pinning, visibility, the surface, its
     * variables) and often across cards, against the same theme. [CardModStyle] is an immutable value, so the result is
     * kept per text and theme variable map (by identity: a theme resolves to one map), at most [CARD_MOD_CACHE] of them.
     */
    private class CardModKey(val css: String, val vars: Map<String, String>) {
        override fun equals(other: Any?) = other is CardModKey && other.vars === vars && other.css == css
        override fun hashCode() = css.hashCode() * 31 + System.identityHashCode(vars)
    }

    private const val CARD_MOD_CACHE = 256
    private val cardModCache = object : LinkedHashMap<CardModKey, CardModStyle>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CardModKey, CardModStyle>?) = size > CARD_MOD_CACHE
    }

    private fun parseCardModUncached(css: String, vars: Map<String, String>): CardModStyle {
        val cleaned = JINJA_PATTERN.replace(CSS_COMMENT.replace(css, " "), " ")
        if (cleaned.isBlank()) return CardModStyle()
        val (decls, otherRules) = hostDeclarations(cleaned)
        // Custom properties cascade like any other: per name, the last `!important` declaration wins, else the last.
        val custom = CUSTOM_PROPERTY.findAll(decls)
            .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
            .mapValues { (_, values) ->
                val winner = values.lastOrNull { IMPORTANT.containsMatchIn(it) } ?: values.last()
                resolveValue(winner.replace(IMPORTANT, "").trim(), vars, 0)
            }
        // Most of the properties asked for below aren't there: skip the pattern when the name isn't in the text at all
        // (the pattern matches ASCII case-insensitively, so a lowercase miss is always a pattern miss).
        val lowerDecls = decls.lowercase(Locale.ROOT)
        // As in the browser: the last `!important` declaration wins, else the last one. card-mod keeps the flag.
        fun prop(name: String): String? {
            if (name !in lowerDecls) return null
            val values = declProp(name).findAll(decls).map { it.groupValues[1] }.toList()
            val winner = values.lastOrNull { IMPORTANT.containsMatchIn(it) } ?: values.lastOrNull()
            return winner?.replace(IMPORTANT, "")?.trim()?.takeIf { it.isNotEmpty() }?.let { resolveValue(it, vars, 0) }
        }

        val bgImage = prop("background-image")
        val bgShort = prop("background")
        val gradient = listOfNotNull(bgImage, bgShort).firstOrNull { "linear-gradient" in it.lowercase(Locale.ROOT) }
            ?.let { parseBackground(it, 0) as? ThemeBackground.LinearGradient }
        val layers = listOfNotNull(bgImage, bgShort).firstOrNull { "gradient(" in it.lowercase(Locale.ROOT) }
            ?.let(CssBackground::parse)
        // `background: none` (the dashboard's usual "bare card") is a transparent fill.
        fun fill(v: String): Int? = if (v.trim().equals("none", ignoreCase = true)) 0 else parseColor(v)
        val bg = prop("background-color")?.let(::fill)
            ?: bgShort?.takeIf { (gradient == null && layers == null) || "gradient" !in it.lowercase(Locale.ROOT) }?.let(::fill)
        val radius = prop("border-radius")?.let { parseDimensionDp(it, -1f) }?.takeIf { it >= 0f }
        val opacity = prop("opacity")?.let { o ->
            if (o.endsWith("%")) o.dropLast(1).toFloatOrNull()?.div(100f) else o.toFloatOrNull()
        }?.coerceIn(0f, 1f)
        val blur = prop("backdrop-filter")?.let { bf ->
            BLUR.find(bf)?.groupValues?.get(1)?.toFloatOrNull()
        }
        var bw: Float? = null
        var bc: Int? = null
        val border = prop("border")
        if (border != null && (border.equals("none", ignoreCase = true) || border == "0")) {
            bw = 0f
        } else if (border != null) {
            PX_NUMBER.find(border)?.groupValues?.get(1)?.toFloatOrNull()?.let { bw = it }
            splitTopLevelSpaces(border).lastOrNull()?.let(::parseColor)?.let { bc = it }
        }
        prop("border-width")?.let { parseDimensionDp(it, -1f) }?.takeIf { it >= 0f }?.let { bw = it }
        prop("border-color")?.let(::parseColor)?.let { bc = it }

        val paddingInsets = parseEdgeInsets(
            shorthand = prop("padding"),
            top = prop("padding-top"),
            right = prop("padding-right"),
            bottom = prop("padding-bottom"),
            left = prop("padding-left"),
        )
        val padding = paddingInsets?.top
        val gap = (prop("gap") ?: prop("grid-gap") ?: prop("row-gap"))
            ?.let { splitTopLevelSpaces(it).firstOrNull() }
            ?.let { parseDimensionDp(it, -1f) }?.takeIf { it >= 0f }
        val fontSize = prop("font-size")?.let { parseDimensionDp(it, -1f) }?.takeIf { it > 0f }
        val fontWeight = prop("font-weight")?.let(::parseFontWeight)
        val letterSpacing = prop("letter-spacing")?.let { ls ->
            val m = LETTER_SPACING.find(ls) ?: return@let null
            val v = m.groupValues[1].toFloatOrNull() ?: return@let null
            when (m.groupValues[2].lowercase(Locale.ROOT)) {
                "em" -> v
                "rem" -> v * 16f / (fontSize ?: 16f)
                else -> v / (fontSize ?: 16f)
            }
        }
        val transitionMs = prop("transition")?.let(::parseOpacityTransitionMs)
        val position = prop("position")?.lowercase(Locale.ROOT)
        val transform = prop("transform")?.lowercase(Locale.ROOT).orEmpty().replace(" ", "")
        // Every translate in the list counts, as the checker reads it (`translate(0, 0) translate(0, -50%)` centers Y).
        val translates = TRANSLATE.findAll(transform).toList()
        val centerY = "translatey(-50%)" in transform || translates.any { it.groupValues[2] == "-50%" }
        val centerX = "translatex(-50%)" in transform || translates.any { it.groupValues[1] == "-50%" }

        return CardModStyle(
            backgroundColor = bg,
            borderRadiusDp = radius,
            borderWidthDp = bw,
            borderColor = bc,
            opacity = opacity,
            blurRadiusDp = blur,
            hasComplexSelectors = otherRules,
            backgroundGradient = gradient,
            backgroundLayers = layers,
            paddingDp = padding,
            paddingInsets = paddingInsets,
            gapDp = gap,
            fontSizeSp = fontSize,
            fontWeight = fontWeight,
            letterSpacingEm = letterSpacing,
            transitionOpacityMs = transitionMs,
            positionFixed = position == "fixed" || position == "absolute",
            // `auto` (and `unset`, `initial`) is no inset: the overlay must not anchor the card to that edge.
            fixedTop = prop("top")?.let(::inset),
            fixedRight = prop("right")?.let(::inset),
            fixedBottom = prop("bottom")?.let(::inset),
            fixedLeft = prop("left")?.let(::inset),
            fixedWidth = prop("width")?.let(::inset),
            fixedCenterY = centerY,
            fixedCenterX = centerX,
            textColor = prop("color")?.let(::parseColor),
            textAlign = prop("text-align")?.lowercase(Locale.ROOT),
            customProperties = custom,
            marginInsets = parseEdgeInsets(
                shorthand = prop("margin"),
                top = prop("margin-top"),
                right = prop("margin-right"),
                bottom = prop("margin-bottom"),
                left = prop("margin-left"),
            ),
            elevationDp = prop("box-shadow")?.let(::parseBoxShadowElevationDp),
        )
    }

    /**
     * One entry of a card's `card_mod.style` selector map, such as `"ha-markdown$"` or `"hui-clock-card-digital$"`
     * (the CSS card-mod injects into that element's shadow root). A plain-string style only has `"."`.
     */
    fun cardModSheet(cardMod: JSONObject?, key: String): String? {
        val style = cardMod?.opt("style") ?: return null
        val raw = when {
            style is String -> style.takeIf { key == "." }
            style is JSONObject -> style.opt(key) as? String
            else -> null
        }
        return raw?.takeIf { it.isNotBlank() }
    }

    /**
     * Splits a CSS sheet into `(selector, declarations)` rules the way the browser reads it: comments and Jinja
     * dropped, `!important` stripped from the value (see [cssRulesImportant] for the flag), bare top-level declarations dropped (they are not valid CSS, so card-mod's
     * `<style>` ignores them). A comma selector list stays one rule; declarations keep source order.
     */
    fun cssRules(css: String): List<Pair<String, List<Pair<String, String>>>> =
        cssRulesImportant(css).map { (sel, decls) -> sel to decls.map { (n, v, _) -> n to v } }

    /** [cssRules] with each declaration's `!important` flag kept, for code that resolves the cascade. */
    fun cssRulesImportant(css: String): List<Pair<String, List<Triple<String, String, Boolean>>>> {
        val cleaned = JINJA_PATTERN.replace(CSS_COMMENT.replace(css, " "), " ")
        val out = ArrayList<Pair<String, List<Triple<String, String, Boolean>>>>()
        var depth = 0
        var segStart = 0
        var bodyStart = 0
        var selector = ""
        for (i in cleaned.indices) {
            when (cleaned[i]) {
                '{' -> {
                    if (depth == 0) {
                        selector = cleaned.substring(segStart, i).trim()
                        bodyStart = i + 1
                    }
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0) {
                        val decls = cleaned.substring(bodyStart, i).split(';').mapNotNull { d ->
                            val c = d.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
                            val name = d.substring(0, c).trim().lowercase(Locale.ROOT)
                            val rawValue = d.substring(c + 1)
                            val value = rawValue.replace(IMPORTANT, "").trim()
                            if (name.isEmpty() || value.isEmpty()) null else Triple(name, value, IMPORTANT.containsMatchIn(rawValue))
                        }
                        if (selector.isNotEmpty() && !selector.startsWith("@")) out += selector to decls
                        segStart = i + 1
                    }
                }
                ';' -> if (depth == 0) segStart = i + 1
            }
        }
        return out
    }

    private val IMPORTANT = Regex("""\s*!\s*important\s*$""", RegexOption.IGNORE_CASE)

    /**
     * The raw `card_mod.style` of a card: a string, or the `"."` entry of a selector map (the card itself).
     * Null when the card has none.
     */
    fun cardModStyleString(cardMod: JSONObject?): String? {
        cardMod ?: return null
        val style = cardMod.opt("style")
        val raw = when (style) {
            is String -> style
            is JSONObject -> style.opt(".") as? String
            else -> null
        }
        return raw?.takeIf { it.isNotBlank() }
    }

    /** Static parse of a card's `card_mod` (templates stripped), for layout decisions such as pinning. */
    fun parseCardMod(cardMod: JSONObject?, vars: Map<String, String>): CardModStyle =
        cardModStyleString(cardMod)?.let { parseCardMod(it, vars) } ?: CardModStyle()

    /** Declarations of the card's own `ha-card` / `:host` rules, and whether any other rule exists. */
    private fun hostDeclarations(css: String): Pair<String, Boolean> {
        val host = StringBuilder()
        var other = false
        var depth = 0
        var segStart = 0
        var bodyStart = 0
        var selector = ""
        for (i in css.indices) {
            when (css[i]) {
                '{' -> {
                    if (depth == 0) {
                        selector = css.substring(segStart, i).trim()
                        bodyStart = i + 1
                    }
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0) {
                        if (isHostSelector(selector)) host.append(css, bodyStart, i).append(";\n") else other = true
                        segStart = i + 1
                    }
                }
                // A declaration outside any rule is not valid CSS: card-mod's <style> drops it, so Vitro does too.
                ';' -> if (depth == 0) segStart = i + 1
            }
        }
        // `!important` stays on each declaration: [parseCardMod] lets it win, as the browser does.
        return host.toString() to other
    }

    /**
     * True when [selector] (a comma list) styles the card itself: `ha-card`, `:host`, `:host ha-card`, `ha-card.x`.
     * A compound that goes on to a descendant or sibling (`ha-card.x .primary`) styles something inside the card,
     * not the card.
     */
    internal fun isHostSelector(selector: String): Boolean = CssSelector.splitTopLevel(selector, ',').any { raw ->
        val s = raw.trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ")
        s == "ha-card" || s == ":host" || s == "." || s == ":host ha-card" || s == ":host > ha-card" ||
            (s.startsWith("ha-card") && s.length > 7 && s[7] in ".[#" && !INTERACTION.containsMatchIn(s) &&
                !hasCombinator(s) && !PSEUDO_ELEMENT.containsMatchIn(s.replace(ATTRIBUTE, "[]")))
    }

    private val NO_INSET = setOf("auto", "unset", "initial")
    private val ATTRIBUTE = Regex("""\[[^\]]*\]""")

    private fun inset(value: String): String? = value.takeUnless { it.trim().lowercase(Locale.ROOT) in NO_INSET }

    /** States a card is in only while touched or focused: a rule for one isn't the card at rest. */
    private val INTERACTION = Regex(""":(hover|active|focus|focus-visible|focus-within)\b""")

    /** `::before`, `::part()` and the legacy one-colon forms: they style a box inside the card, not the card. */
    private val PSEUDO_ELEMENT = Regex("""::|:(before|after|first-line|first-letter)\b""")

    /** True when [s] has a combinator (whitespace, `>`, `+`, `~`) outside brackets, parentheses and quotes. */
    private fun hasCombinator(s: String): Boolean {
        var depth = 0
        var quote: Char? = null
        for (c in s) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> if (depth > 0) depth--
                depth == 0 && (c == ' ' || c == '>' || c == '+' || c == '~') -> return true
            }
        }
        return false
    }

    private fun parseEdgeInsets(
        shorthand: String?,
        top: String?,
        right: String?,
        bottom: String?,
        left: String?,
    ): EdgeInsetsDp? {
        val base = shorthand?.let { raw ->
            val vals = splitTopLevelSpaces(raw).mapNotNull { parseDimensionDp(it, -1f).takeIf { v -> v >= 0f } }
            when (vals.size) {
                0 -> null
                1 -> EdgeInsetsDp(vals[0], vals[0], vals[0], vals[0])
                2 -> EdgeInsetsDp(vals[1], vals[0], vals[1], vals[0])
                3 -> EdgeInsetsDp(vals[1], vals[0], vals[1], vals[2])
                else -> EdgeInsetsDp(vals[3], vals[0], vals[1], vals[2])
            }
        }
        val t = top?.let { parseDimensionDp(it, -1f) }?.takeIf { it >= 0f }
        val r = right?.let { parseDimensionDp(it, -1f) }?.takeIf { it >= 0f }
        val b = bottom?.let { parseDimensionDp(it, -1f) }?.takeIf { it >= 0f }
        val l = left?.let { parseDimensionDp(it, -1f) }?.takeIf { it >= 0f }
        if (base == null && t == null && r == null && b == null && l == null) return null
        return EdgeInsetsDp(
            left = l ?: base?.left ?: 0f,
            top = t ?: base?.top ?: 0f,
            right = r ?: base?.right ?: 0f,
            bottom = b ?: base?.bottom ?: 0f,
        )
    }

    private fun parseFontWeight(raw: String): Int? = when (raw.trim().lowercase(Locale.ROOT)) {
        "thin" -> 100
        "light", "lighter" -> 300
        "normal", "regular" -> 400
        "medium" -> 500
        "semibold", "semi-bold" -> 600
        "bold", "bolder" -> 700
        else -> raw.trim().toFloatOrNull()?.toInt()?.coerceIn(1, 1000)
    }

    /** `opacity 0.8s ease`, `all 400ms`, or a bare `0.3s` → ms; null when no item covers opacity. */
    private fun parseOpacityTransitionMs(raw: String): Long? {
        for (item in splitTopLevelCommas(raw)) {
            val tokens = splitTopLevelSpaces(item.trim().lowercase(Locale.ROOT))
            val first = tokens.firstOrNull() ?: continue
            val covers = first == "opacity" || first == "all" || TIME_PATTERN.matches(first)
            if (!covers) continue
            val time = tokens.firstNotNullOfOrNull { TIME_PATTERN.matchEntire(it) } ?: continue
            val v = time.groupValues[1].toDoubleOrNull() ?: continue
            val ms = if (time.groupValues[2] == "ms") v else v * 1000.0
            return ms.toLong().coerceIn(0L, 3000L)
        }
        return null
    }

    private fun sideToAngle(side: String): Float? {
        val words = side.removePrefix("to ").trim().split(WHITESPACE).toSet()
        val top = "top" in words
        val bottom = "bottom" in words
        val left = "left" in words
        val right = "right" in words
        return when {
            top && right -> 45f
            bottom && right -> 135f
            bottom && left -> 225f
            top && left -> 315f
            top -> 0f
            right -> 90f
            bottom -> 180f
            left -> 270f
            else -> null
        }
    }

    private fun splitTopLevelSpaces(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in s) {
            when {
                c == '(' -> { depth++; cur.append(c) }
                c == ')' -> { if (depth > 0) depth--; cur.append(c) }
                c.isWhitespace() && depth == 0 -> if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun parseAlpha(raw: String?): Int {
        if (raw.isNullOrBlank()) return 255
        val v = if (raw.endsWith("%")) (raw.dropLast(1).toDoubleOrNull() ?: 100.0) / 100.0 else raw.toDoubleOrNull() ?: 1.0
        return (v.coerceIn(0.0, 1.0) * 255.0).roundToInt()
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun hslToArgb(a: Int, h: Double, s: Double, l: Double): Int {
        val c = (1.0 - abs(2.0 * l - 1.0)) * s
        val x = c * (1.0 - abs((h / 60.0) % 2.0 - 1.0))
        val m = l - c / 2.0
        val (r1, g1, b1) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return argb(a, ((r1 + m) * 255).roundToInt(), ((g1 + m) * 255).roundToInt(), ((b1 + m) * 255).roundToInt())
    }

    private fun splitTopLevelCommas(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = 0
        for (i in s.indices) {
            when (s[i]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    out += s.substring(start, i)
                    start = i + 1
                }
            }
        }
        if (start < s.length) out += s.substring(start)
        return out
    }

    private val NAMED_COLORS = mapOf(
        "transparent" to 0x00000000,
        "black" to 0xFF000000.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "red" to 0xFFF44336.toInt(),
        "pink" to 0xFFE91E63.toInt(),
        "purple" to 0xFF926BC7.toInt(),
        "indigo" to 0xFF3F51B5.toInt(),
        "blue" to 0xFF2196F3.toInt(),
        "cyan" to 0xFF00BCD4.toInt(),
        "teal" to 0xFF009688.toInt(),
        "green" to 0xFF4CAF50.toInt(),
        "lime" to 0xFFCDDC39.toInt(),
        "yellow" to 0xFFFFEB3B.toInt(),
        "amber" to 0xFFFFC107.toInt(),
        "orange" to 0xFFFF9800.toInt(),
        "grey" to 0xFF9E9E9E.toInt(),
        "gray" to 0xFF9E9E9E.toInt(),
    )
}
