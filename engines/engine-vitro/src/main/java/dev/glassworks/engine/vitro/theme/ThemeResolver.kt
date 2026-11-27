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
    /**
     * `background:` / `background-image:` holding any gradient, every layer and stop position as CSS paints it. They
     * lie on the card's fill: [backgroundColor], or the theme's card color when the card sets none.
     */
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
    /**
     * `font-family:` on the card, a CSS list with its `var()`s resolved, or null. Every text inside the card inherits
     * it, as in the browser; `inherit` and the other keywords leave the theme's.
     */
    val fontFamily: String? = null,
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
    /** `height:` on a pinned card, raw CSS (`120px`, `50%`), or null for the card's own. */
    val fixedHeight: String? = null,
    /**
     * `position: fixed` itself (not `absolute`): the viewport is the containing block, so a card whose own `ha-card`
     * is `height: 100%` is as tall as the screen.
     */
    val fixedToViewport: Boolean = false,
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
            // This block goes inside an island's <style>: no angle brackets, so a theme value can't close it.
            val safeKey = k.filter { it != '<' && it != '>' && it != '\n' && it != ';' && it != '{' && it != '}' }
            val safeVal = v.replace("\n", " ").replace(";", "").filter { it != '<' && it != '>' }
            append("  --").append(safeKey).append(": ").append(safeVal).append(";\n")
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
    private val CUSTOM_PROPERTY_NAME = Regex("""--[\w-]+""")
    /** `font-family` values that keep the inherited list. */
    private val FONT_KEYWORDS = setOf("inherit", "unset", "revert", "revert-layer")

    /**
     * What `font-family: initial` resets to: the browser's own default font, a serif in Chrome and Firefox alike,
     * not the font the card would inherit.
     */
    const val INITIAL_FONT_FAMILY = "serif"
    private val LETTER_SPACING = Regex("""(-?[\d.]+)\s*(em|px|rem)?""", RegexOption.IGNORE_CASE)
    private val TRANSLATE = Regex("""translate\(([^,)]+)(?:,([^)]+))?\)""")
    private val STOP_PERCENT = Regex("""\s+[\d.]+%$""")
    private val STOP_POSITION = Regex("""\s+[\d.]+(?:%|px)$""")
    // One pattern per property name; the names are the literals below, so these stay small.
    private val themeVarProps = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private fun themeVarProp(name: String): Regex = themeVarProps.getOrPut(name) {
        Regex("""(?:^|[;\n{])\s*$name\s*:\s*([^;!\n}]+)""", RegexOption.IGNORE_CASE)
    }

    private val JINJA_PATTERN = Regex("""\{%.*?%\}|\{\{.*?\}\}|\{#.*?#\}""", RegexOption.DOT_MATCHES_ALL)

    /**
     * [css] with each comment replaced by a space, as the browser reads it: a slash-star inside a quoted string (a
     * `content:` value, an attribute selector's value) doesn't open one, so it can't hide the rules after it. A string ends
     * at its closing quote or at a newline, a backslash and the character after it are copied as they are, and a
     * comment that is never closed runs to the end. The site's checker (`stripCssComments` in
     * `site/src/lib/vitroCompat.ts`) and the audit script (`strip_css_comments`) do the same.
     */
    fun stripCssComments(css: String): String {
        if (!css.contains("/*")) return css
        val out = StringBuilder(css.length)
        var quote = NO_QUOTE
        var i = 0
        while (i < css.length) {
            val c = css[i]
            when {
                c == '\\' && i + 1 < css.length -> {
                    out.append(c).append(css[i + 1])
                    i += 2
                    continue
                }
                quote != NO_QUOTE -> if (c == quote || c == '\n') quote = NO_QUOTE
                c == '"' || c == '\'' -> quote = c
                c == '/' && css.startsWith("/*", i) -> {
                    val end = css.indexOf("*/", i + 2)
                    out.append(' ')
                    i = if (end < 0) css.length else end + 2
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private const val NO_QUOTE = '\u0000'

    /**
     * Which characters of [css] are quoted text: a string, its quotes included, or a backslash escape and the
     * character after it. A brace, `;` or `,` there isn't structure (`--label: "{ ; }"`), so the rule readers
     * ([cssRulesImportant], [hostDeclarations]) skip it. A string ends as in [stripCssComments].
     */
    private fun quotedChars(css: String): BooleanArray {
        val quoted = BooleanArray(css.length)
        var quote = NO_QUOTE
        var i = 0
        while (i < css.length) {
            val c = css[i]
            when {
                c == '\\' && i + 1 < css.length -> {
                    quoted[i] = true
                    quoted[i + 1] = true
                    i += 2
                    continue
                }
                quote != NO_QUOTE -> {
                    quoted[i] = true
                    if (c == quote || c == '\n') quote = NO_QUOTE
                }
                c == '"' || c == '\'' -> {
                    quoted[i] = true
                    quote = c
                }
            }
            i++
        }
        return quoted
    }

    /** A rule body split into declarations at each `;` outside quoted text and parentheses (`url(data:…;base64,…)`). */
    private fun splitDeclarations(body: String): List<String> {
        val quoted = quotedChars(body)
        val out = ArrayList<String>()
        var parens = 0
        var start = 0
        for (i in body.indices) {
            if (quoted[i]) continue
            when (body[i]) {
                '(' -> parens++
                ')' -> if (parens > 0) parens--
                ';' -> if (parens == 0) {
                    out += body.substring(start, i)
                    start = i + 1
                }
            }
        }
        out += body.substring(start)
        return out
    }
    private val TIME_PATTERN = Regex("""([\d.]+)(ms|s)""")
    private val DIM_PX = Regex("""([\d.]+)\s*(px|rem|em)?""", RegexOption.IGNORE_CASE)
    private val SHADOW_COLOR = Regex("""(?:rgba?|hsla?)\([^)]*\)""", RegexOption.IGNORE_CASE)
    private val SHADOW_LENGTH = Regex("""(?<![\w#.])(-?[\d.]+)(?:px)?(?=\s|$)""", RegexOption.IGNORE_CASE)

    // HA 2026's `--primary-color` is `--ha-color-primary-40` (#009ac7) in both modes, not the old #03a9f4.
    private val DARK_DEFAULTS = mapOf(
        "primary-color" to "#009ac7",
        "accent-color" to "#ff9800",
        "primary-background-color" to "#111111",
        // HA's dark theme (frontend 20261028.5, `darkColorStyles`): #282828, not the neutral-10 #202020. Bubble Card's
        // buttons draw on it (tablet Vitro run 3: #202020 here, #282828 in HA).
        "secondary-background-color" to "#282828",
        "card-background-color" to "#1c1c1c",
        "ha-card-background" to "var(--card-background-color)",
        "primary-text-color" to "#e1e1e1",
        "secondary-text-color" to "#9b9b9b",
        "disabled-text-color" to "#6f6f6f",
        "divider-color" to "rgba(225, 225, 225, 0.12)",
        "ha-card-border-color" to "#2c2c2c",
        "ha-card-border-width" to "1px",
        "ha-card-border-radius" to "12px",
        "rgb-primary-color" to "0, 154, 199",
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
        "primary-color" to "#009ac7",
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
        "rgb-primary-color" to "0, 154, 199",
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

    /** The theme [resolve] draws [overrideTheme] with: that one, or else the catalog's default for the mode. */
    fun pickedName(catalog: ThemeCatalog, dark: Boolean, overrideTheme: String?): String =
        overrideTheme?.takeIf { it.isNotBlank() && it != "Backend-selected" }
            ?: (if (dark) catalog.defaultDarkTheme else null)
            ?: catalog.defaultTheme

    fun resolve(
        catalog: ThemeCatalog,
        dark: Boolean = true,
        overrideTheme: String? = null,
        viewBackground: String? = null,
    ): ResolvedTheme {
        val raw = LinkedHashMap<String, String>(if (dark) DARK_DEFAULTS else LIGHT_DEFAULTS)
        val pickedName = pickedName(catalog, dark, overrideTheme)

        catalog.themes[pickedName]?.let { def ->
            raw.putAll(def.base)
            raw.putAll(if (dark) def.dark else def.light)
        }

        val themeVarNames = LinkedHashSet<String>()
        catalog.themes[pickedName]?.let { def ->
            themeVarNames += def.base.keys
            themeVarNames += if (dark) def.dark.keys else def.light.keys
        }

        // Names in a reference cycle are invalid, as in CSS, and so is a name whose value needs an invalid or missing
        // one with no fallback (`b: var(--a)` with `a` in a cycle): left out of the scope, so a `var()` of one takes
        // its fallback (as the card parser does). They're left out of the result entirely, not exported empty:
        // `--b: ;` would be a valid empty value, and an island's `var(--b, fallback)` would never take its fallback.
        val invalid = invalidNames(raw)
        val scope: Map<String, String> = if (invalid.isEmpty()) raw else raw - invalid
        val resolved = LinkedHashMap<String, String>(raw.size)
        for (k in raw.keys) {
            if (k !in invalid) resolved[k] = resolveValue(raw[k].orEmpty(), scope, 0)
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
            primaryColor = parseColor(resolved["primary-color"]) ?: 0xFF009AC7.toInt(),
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
        // Bounded like the browser's: substitution that grows a value by more than the limit in one pass
        // (`--a: var(--a) var(--a)` doubles every pass) makes it invalid, an empty value, instead of filling the heap
        // on the main thread. The growth is what's capped, not the length: a long card-mod stylesheet stays whole.
        val replaced = substituteVars(input, MAX_VAR_EXPANSION) { key, fallback, _ -> map[key] ?: fallback } ?: return ""
        return if (replaced == input) replaced.trim() else resolveValue(replaced, map, depth + 1)
    }

    /**
     * The names of [raw] that are invalid at computed-value time: those in a cycle, and (to a fixed point) those whose
     * value uses a missing or invalid name through a `var()` with no fallback (a fallback that does the same counts).
     */
    internal fun invalidNames(raw: Map<String, String>, outside: Set<String> = emptySet()): Set<String> {
        val invalid = HashSet(cyclicNames(raw))
        while (true) {
            // [outside]: names defined around [raw] (the theme's, for a card's own properties), valid as they are.
            val scope = (raw.keys - invalid).let { if (outside.isEmpty()) it else it + (outside - raw.keys) }
            // A whole stylesheet (`card-mod-card`) isn't one value: a missing reference in one of its declarations
            // empties that reference (resolveValue) and leaves the other declarations as they are.
            val more = raw.filter { (k, v) -> k !in invalid && !k.startsWith("card-mod-") && needsMissing(v, scope) }.keys
            if (more.isEmpty()) return invalid
            invalid += more
        }
    }

    /** True when [value] substitutes a name outside [scope] through a `var()` (or fallback chain) with no fallback. */
    private fun needsMissing(value: String, scope: Set<String>, depth: Int = 0): Boolean {
        if ("var(" !in value || depth >= 8) return false
        var missing = false
        substituteVars(value, Int.MAX_VALUE) { key, fallback, hasFallback ->
            if (key !in scope && (!hasFallback || needsMissing(fallback, scope, depth + 1))) missing = true
            ""
        }
        return missing
    }

    /** How much a value may grow in one substitution pass. */
    private const val MAX_VAR_EXPANSION = 16 * 1024

    private val VAR_NAME = Regex("""var\(\s*--([\w-]+)""")

    /**
     * The names in [raw] (name -> value, no `--`) that take part in a reference cycle (`--a: var(--a)`,
     * `--a: var(--b); --b: var(--a)`). As in CSS they are invalid at computed-value time: a `var()` of one
     * takes its fallback, as for a name that isn't there.
     */
    internal fun cyclicNames(raw: Map<String, String>): Set<String> {
        val refs = raw.mapValues { (_, v) -> VAR_NAME.findAll(v).map { it.groupValues[1] }.filter { it in raw }.toSet() }
        val cyclic = HashSet<String>()
        for (start in raw.keys) {
            // Does a path from start's references lead back to start?
            val seen = HashSet<String>()
            val stack = ArrayDeque(refs[start].orEmpty())
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                if (n == start) { cyclic += start; break }
                if (seen.add(n)) stack.addAll(refs[n].orEmpty())
            }
        }
        return cyclic
    }

    /**
     * Replaces each outermost `var(--name, fallback)` in [s] with [value] of its name and fallback. The whole
     * expression is found by balanced parentheses, so a nested fallback (`var(--a, var(--b))`) isn't cut short.
     * Null as soon as the value has grown by more than [maxGrowth] chars: checked at each append, so the limit
     * bounds the memory used on the way, not only the result.
     */
    private fun substituteVars(s: String, maxGrowth: Int, value: (key: String, fallback: String, hasFallback: Boolean) -> String): String? {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val at = s.indexOf("var(", i)
            if (at < 0) {
                out.append(s, i, s.length)
                break
            }
            out.append(s, i, at)
            var j = at + 4
            var depth = 1
            while (j < s.length && depth > 0) {
                when (s[j]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                j++
            }
            if (depth != 0) {
                out.append(s, at, s.length) // unbalanced: left as it is
                break
            }
            val inner = s.substring(at + 4, j - 1)
            var comma = -1
            var d = 0
            for (k in inner.indices) {
                when (inner[k]) {
                    '(' -> d++
                    ')' -> d--
                    ',' -> if (d == 0) { comma = k; break }
                }
            }
            val name = (if (comma < 0) inner else inner.substring(0, comma)).trim()
            if (!name.startsWith("--")) {
                out.append(s, at, j) // not a custom property reference: left as it is
                i = j
                continue
            }
            val v = value(name.removePrefix("--"), if (comma < 0) "" else inner.substring(comma + 1).trim(), comma >= 0)
            // The output so far is bounded by the whole input plus the budget at every append (so a later part that
            // shrinks it can still count), and the finished value by its net growth.
            if (out.length.toLong() + v.length > s.length.toLong() + maxGrowth) return null
            out.append(v)
            i = j
        }
        if (out.length.toLong() - s.length > maxGrowth) return null
        return out.toString()
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
        val text = raw.orEmpty()
        val m = DIM_PX.find(text) ?: return defaultDp
        // A negative length or a percentage isn't a positive dp: leave it unapplied rather than guess.
        if (m.range.first > 0 && text[m.range.first - 1] == '-') return defaultDp
        if (m.range.last + 1 < text.length && text[m.range.last + 1] == '%') return defaultDp
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
        // One linear layer: the parsed one, with its angle (`.25turn`, `to top right`) and stops (`10px`), which the
        // plain pattern below can't read.
        (css?.layers?.singleOrNull() as? CssBackground.Linear)?.let { l ->
            // A corner keyword's angle depends on the box: painted from the layer itself.
            if (l.corner != null) return ThemeBackground.Layers(css, fallbackColor)
            return ThemeBackground.LinearGradient(l.angleDeg, l.stops.map { it.color }.toIntArray(), l.stops)
        }
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
        // The border shorthand and each longhand compete per component as in the card's own style: `!important`
        // first, then the later declaration (`border-width: 8px; border: 0px solid` draws none).
        fun winner(vararg names: String): Pair<String, String>? {
            var best: Triple<String, String, Pair<Boolean, Int>>? = null
            for (name in names) {
                if (name !in lowerCss) continue
                for (m in themeDecl(name).findAll(css)) {
                    // An invalid declaration is dropped before the cascade (`border: nonsense` hides no width).
                    if (!borderDeclValid(name, m.groupValues[1].replace(IMPORTANT, "").trim())) continue
                    val key = IMPORTANT.containsMatchIn(m.groupValues[1]) to m.range.first
                    val b = best
                    if (b == null || key.first && !b.third.first || key.first == b.third.first && key.second > b.third.second) {
                        best = Triple(name, m.groupValues[1], key)
                    }
                }
            }
            val (name, raw) = best ?: return null
            return raw.replace(IMPORTANT, "").trim().takeIf { it.isNotEmpty() }?.let { name to it }
        }
        fun noBorder(v: String) = v.equals("none", ignoreCase = true) || v == "0"
        val widthWin = winner("border-width", "border")
        val bw: Float? = when {
            widthWin == null -> null
            widthWin.first == "border-width" -> parseDimensionDp(widthWin.second, -1f).takeIf { it >= 0f }
            noBorder(widthWin.second) -> 0f
            else -> shorthandWidthDp(widthWin.second)
        }
        val colorWin = winner("border-color", "border")
        val bc: Int? = when {
            colorWin == null -> null
            colorWin.first == "border-color" -> parseColor(colorWin.second)
            noBorder(colorWin.second) -> null
            else -> colorWin.second.split(WHITESPACE).lastOrNull()?.let(::parseColor)
        }

        return CardModStyle(bg, radius, bw, bc, opacity, blur, complex)
    }

    /**
     * False for a border declaration CSS would drop as invalid before the cascade. Other properties, and values with
     * `var()` (whose validity is known only after substitution), pass. A name Vitro doesn't know as a color
     * (`rebeccapurple`) passes beside a width or a style, so a valid border isn't lost to the short color table, but
     * alone it's no border at all.
     */
    private fun borderDeclValid(name: String, value: String): Boolean {
        if (value.isEmpty()) return false
        val lower = value.lowercase(Locale.ROOT)
        if ("var(" in lower || lower in CSS_WIDE_KEYWORDS) return true
        fun width(t: String) = t in BORDER_WIDTH_KEYWORDS || BORDER_LENGTH.matches(t) || t.startsWith("calc(")
        fun color(t: String) = parseColor(t) != null || t == "currentcolor" || t == "transparent" ||
            COLOR_FUNCTION.containsMatchIn(t)
        val tokens = splitTopLevelSpaces(lower)
        return when (name) {
            "border-width" -> tokens.size in 1..4 && tokens.all(::width)
            "border-color" -> tokens.size in 1..4 && tokens.all { color(it) || IDENT.matches(it) }
            "border" -> {
                if (tokens.size > 3) return false
                val widths = tokens.count(::width)
                val styles = tokens.count { it in BORDER_STYLES }
                val colors = tokens.count(::color)
                val unknown = tokens.size - widths - styles - colors
                widths <= 1 && styles <= 1 && colors + unknown <= 1 &&
                    (unknown == 0 || widths + styles > 0 && tokens.none { !width(it) && it !in BORDER_STYLES && !color(it) && !IDENT.matches(it) })
            }
            else -> true
        }
    }

    private val CSS_WIDE_KEYWORDS = setOf("inherit", "initial", "unset", "revert", "revert-layer")
    private val BORDER_WIDTH_KEYWORDS = setOf("thin", "medium", "thick")
    private val BORDER_STYLES = setOf("none", "hidden", "dotted", "dashed", "solid", "double", "groove", "ridge", "inset", "outset")
    /** The width a `border` shorthand gives: its length token, where a zero may drop its unit (`0.0 solid`). */
    private fun shorthandWidthDp(value: String): Float? {
        val token = splitTopLevelSpaces(value.lowercase(Locale.ROOT)).firstOrNull { BORDER_LENGTH.matches(it) }
        if (token != null && UNITLESS_ZERO.matches(token)) return 0f
        return PX_NUMBER.find(token ?: value)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private val UNITLESS_ZERO = Regex("""0*\.?0+""")

    /** A length: a zero may drop its unit (`0`, `0.0`, `.0`), any other needs one. */
    private val BORDER_LENGTH = Regex("""0*\.?0+|\d*\.?\d+(?:px|r?em|pt|pc|cm|mm|in|q|vh|vw|vmin|vmax|ch|ex)""")
    private val COLOR_FUNCTION = Regex("""^(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch|color|color-mix)\(""")
    private val IDENT = Regex("""-?[a-z_][a-z0-9_-]*""")

    /** One declaration of [name] in a theme's card-mod CSS, its value with any `!important` kept. */
    private fun themeDecl(name: String): Regex = themeDecls.getOrPut(name) {
        Regex("""(?:^|[;\n{])\s*$name\s*:\s*([^;\n}]+)""", RegexOption.IGNORE_CASE)
    }

    private val themeDecls = java.util.concurrent.ConcurrentHashMap<String, Regex>()

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
        val cleaned = JINJA_PATTERN.replace(stripCssComments(css), " ")
        if (cleaned.isBlank()) return CardModStyle()
        val (decls, otherRules) = hostDeclarations(cleaned)
        // The card's declarations in source order, split as the browser splits them ([splitDeclarations]): a `;` in a
        // string (`content: "a; opacity: 0"`) doesn't start another one. The value keeps a trailing `!important`,
        // which wins below as it does in the browser. A property's name is ASCII case-insensitive; a custom one's isn't.
        val declList = splitDeclarations(decls).mapNotNull { d ->
            val colon = d.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            val name = d.substring(0, colon).trim()
            (if (name.startsWith("--")) name else name.lowercase(Locale.ROOT)) to d.substring(colon + 1)
        }
        // Custom properties cascade like any other: per name, the last `!important` declaration wins, else the last.
        val rawCustom = declList.filter { (name, _) -> CUSTOM_PROPERTY_NAME.matches(name) }
            .groupBy({ it.first.removePrefix("--") }, { it.second })
            .mapValues { (_, values) ->
                val winner = values.lastOrNull { IMPORTANT.containsMatchIn(it) } ?: values.last()
                winner.replace(IMPORTANT, "").trim()
            }
        // The card's own custom properties come before the theme's, as they would on its ha-card, and they may refer
        // to each other (`--inset: var(--spacing)`): resolved against that whole scope. Names in a cycle, or that need
        // a name defined nowhere (`--fill: var(--missing)`), are invalid: left out of the scope (the theme's value of
        // that name included, as it's shadowed), so a `var()` of theirs takes its fallback.
        val cyclic = if (rawCustom.isEmpty()) emptySet() else invalidNames(rawCustom, vars.keys)
        // (Copies of the theme's map only when needed: `- cyclic` with nothing cyclic would copy it again.)
        val rawScope = if (rawCustom.isEmpty()) vars else (vars + rawCustom).let { if (cyclic.isEmpty()) it else it - cyclic }
        val custom = rawCustom.mapValues { resolveValue(it.value, rawScope, 0) }
        val scope = if (custom.isEmpty()) vars else (vars + custom).let { if (cyclic.isEmpty()) it else it - cyclic }
        // As in the browser: the last `!important` declaration wins, else the last one. card-mod keeps the flag.
        fun prop(name: String): String? {
            val values = declList.filter { it.first == name }.map { it.second }
            val winner = values.lastOrNull { IMPORTANT.containsMatchIn(it) } ?: values.lastOrNull()
            return winner?.replace(IMPORTANT, "")?.trim()?.takeIf { it.isNotEmpty() }?.let { resolveValue(it, scope, 0) }
        }

        /**
         * The cascade's winner among [names] (a shorthand and its longhand): an `!important` declaration beats a
         * normal one, then the later one wins. The winning name and its resolved value.
         */
        fun winner(vararg names: String): Pair<String, String>? {
            var best: Triple<String, String, Pair<Boolean, Int>>? = null
            for ((index, decl) in declList.withIndex()) {
                val (name, value) = decl
                if (name !in names) continue
                // An invalid declaration is dropped before the cascade (`border: nonsense` hides no width).
                if (!borderDeclValid(name, value.replace(IMPORTANT, "").trim())) continue
                val key = IMPORTANT.containsMatchIn(value) to index
                val b = best
                if (b == null || key.first && !b.third.first || key.first == b.third.first && key.second > b.third.second) {
                    best = Triple(name, value, key)
                }
            }
            val (name, raw) = best ?: return null
            return raw.replace(IMPORTANT, "").trim().takeIf { it.isNotEmpty() }?.let { name to resolveValue(it, scope, 0) }
        }

        // `background` resets the image and the color; `background-image` and `background-color` set one each. Which
        // one paints is the cascade's call between the shorthand and that longhand.
        val image = winner("background-image", "background")
        val gradientText = image?.second?.takeIf { "gradient(" in it.lowercase(Locale.ROOT) }
        val gradient = gradientText?.takeIf { "linear-gradient" in it.lowercase(Locale.ROOT) }
            ?.let { parseBackground(it, 0) as? ThemeBackground.LinearGradient }
        val layers = gradientText?.let(CssBackground::parse)
        // `background: none` (the dashboard's usual "bare card") is a transparent fill.
        fun fill(v: String): Int? = if (v.trim().equals("none", ignoreCase = true)) 0 else parseColor(v)
        val color = winner("background-color", "background")
        val bg = when {
            color == null -> null
            color.first == "background-color" -> fill(color.second)
            // A shorthand with a gradient: its own color, else transparent (the shorthand resets it).
            "gradient" in color.second.lowercase(Locale.ROOT) -> CssBackground.parse(color.second)?.color ?: 0
            else -> fill(color.second)
        }
        val radius = prop("border-radius")?.let { parseDimensionDp(it, -1f) }?.takeIf { it >= 0f }
        val opacity = prop("opacity")?.let { o ->
            if (o.endsWith("%")) o.dropLast(1).toFloatOrNull()?.div(100f) else o.toFloatOrNull()
        }?.coerceIn(0f, 1f)
        val blur = prop("backdrop-filter")?.let { bf ->
            BLUR.find(bf)?.groupValues?.get(1)?.toFloatOrNull()
        }
        // The `border` shorthand and each longhand compete in the cascade (`!important`, then the later one), per
        // component: `border-width: 8px; border: 0px solid` draws no border, `border: 0px; border-width: 8px` does.
        fun noBorder(v: String) = v.equals("none", ignoreCase = true) || v == "0"
        val widthWin = winner("border-width", "border")
        val bw: Float? = when {
            widthWin == null -> null
            widthWin.first == "border-width" -> parseDimensionDp(widthWin.second, -1f).takeIf { it >= 0f }
            noBorder(widthWin.second) -> 0f
            else -> shorthandWidthDp(widthWin.second)
        }
        val colorWin = winner("border-color", "border")
        val bc: Int? = when {
            colorWin == null -> null
            colorWin.first == "border-color" -> parseColor(colorWin.second)
            noBorder(colorWin.second) -> null
            else -> splitTopLevelSpaces(colorWin.second).lastOrNull()?.let(::parseColor)
        }

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
        val fontFamily = prop("font-family")?.replace(Regex("\\s+"), " ")?.trim()
            ?.let { if (it.equals("initial", ignoreCase = true)) INITIAL_FONT_FAMILY else it }
            ?.takeUnless { it.lowercase(Locale.ROOT) in FONT_KEYWORDS }
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
            fontFamily = fontFamily,
            letterSpacingEm = letterSpacing,
            transitionOpacityMs = transitionMs,
            positionFixed = position == "fixed" || position == "absolute",
            // `auto` (and `unset`, `initial`) is no inset: the overlay must not anchor the card to that edge.
            fixedTop = prop("top")?.let(::inset),
            fixedRight = prop("right")?.let(::inset),
            fixedBottom = prop("bottom")?.let(::inset),
            fixedLeft = prop("left")?.let(::inset),
            fixedWidth = prop("width")?.let(::inset),
            // An explicit `height: auto` is kept: the card's content height, unlike no height (a 100% card's viewport).
            // `initial` and `unset` are `auto` too: height isn't inherited, so both reset it to its initial value.
            fixedHeight = prop("height")?.let { if (it.trim().lowercase(Locale.ROOT) in NO_INSET) "auto" else it },
            fixedToViewport = position == "fixed",
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
        val cleaned = JINJA_PATTERN.replace(stripCssComments(css), " ")
        val out = ArrayList<Pair<String, List<Triple<String, String, Boolean>>>>()
        var depth = 0
        var segStart = 0
        var bodyStart = 0
        var selector = ""
        val quoted = quotedChars(cleaned)
        for (i in cleaned.indices) {
            if (quoted[i]) continue
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
                        val decls = splitDeclarations(cleaned.substring(bodyStart, i)).mapNotNull { d ->
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
        val quoted = quotedChars(css)
        for (i in css.indices) {
            if (quoted[i]) continue
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
            // Positions name the sides, so one value Vitro can't take (a percentage, a negative length) rejects the
            // whole shorthand instead of shifting the others; `auto` keeps its place as 0.
            val vals = splitTopLevelSpaces(raw).map { tok ->
                if (tok.trim().equals("auto", ignoreCase = true)) 0f
                else parseDimensionDp(tok, -1f).takeIf { v -> v >= 0f } ?: return@let null
            }
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
