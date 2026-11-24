/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.theme

import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Background description parsed from HA's `lovelace-background` / `primary-background-color`. */
sealed class ThemeBackground {
    data class Solid(val color: Int) : ThemeBackground()
    data class LinearGradient(val angleDeg: Float, val colors: IntArray) : ThemeBackground()
    data class ImageUrl(val url: String, val fallbackColor: Int) : ThemeBackground()
}

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
)

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
) {
    /** Domain active color matching HA's `--state-<domain>-active-color` convention. */
    fun activeColor(domain: String): Int {
        val domainKey = "state-${domain.lowercase(Locale.ROOT)}-active-color"
        ThemeResolver.parseColor(vars[domainKey])?.let { return it }
        return when (domain) {
            "light" -> ThemeResolver.parseColor(vars["state-light-active-color"]) ?: 0xFFFFC107.toInt()
            "switch", "input_boolean" -> ThemeResolver.parseColor(vars["state-switch-active-color"]) ?: 0xFFFFC107.toInt()
            "climate" -> ThemeResolver.parseColor(vars["state-climate-heat-color"]) ?: 0xFFFF8100.toInt()
            "binary_sensor" -> ThemeResolver.parseColor(vars["state-binary_sensor-active-color"]) ?: 0xFFFFC107.toInt()
            "sensor" -> ThemeResolver.parseColor(vars["state-sensor-active-color"]) ?: 0xFF2196F3.toInt()
            "fan" -> ThemeResolver.parseColor(vars["state-fan-active-color"]) ?: 0xFF009688.toInt()
            "cover" -> ThemeResolver.parseColor(vars["state-cover-active-color"]) ?: 0xFF9C27B0.toInt()
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
    private val DIM_PX = Regex("""([\d.]+)\s*(px|rem|em)?""", RegexOption.IGNORE_CASE)

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
        "ha-card-border-color" to "rgba(225, 225, 225, 0.12)",
        "ha-card-border-width" to "1px",
        "ha-card-border-radius" to "12px",
        "rgb-primary-color" to "3, 169, 244",
        "rgb-accent-color" to "255, 152, 0",
        "rgb-primary-text-color" to "225, 225, 225",
        "rgb-card-background-color" to "28, 28, 28",
        "state-light-active-color" to "#ffc107",
        "state-switch-active-color" to "#ffc107",
        "state-climate-heat-color" to "#ff8100",
        "state-climate-cool-color" to "#2b9af9",
        "state-binary_sensor-active-color" to "#ffc107",
        "state-sensor-active-color" to "#2196f3",
        "state-fan-active-color" to "#009688",
        "state-cover-active-color" to "#9c27b0",
        "state-person-home-color" to "#4caf50",
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
        "ha-card-border-color" to "rgba(0, 0, 0, 0.12)",
        "ha-card-border-width" to "1px",
        "ha-card-border-radius" to "12px",
        "rgb-primary-color" to "3, 169, 244",
        "rgb-accent-color" to "255, 152, 0",
        "rgb-primary-text-color" to "33, 33, 33",
        "rgb-card-background-color" to "255, 255, 255",
        "state-light-active-color" to "#ffc107",
        "state-switch-active-color" to "#ffc107",
        "state-climate-heat-color" to "#ff8100",
        "state-climate-cool-color" to "#2b9af9",
        "state-binary_sensor-active-color" to "#ffc107",
        "state-sensor-active-color" to "#2196f3",
        "state-fan-active-color" to "#009688",
        "state-cover-active-color" to "#9c27b0",
        "state-person-home-color" to "#4caf50",
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
            ?: if (dark) 0x1FE1E1E1 else 0x1F000000

        val bgSpec = viewBackground?.takeIf { it.isNotBlank() } ?: resolved["lovelace-background"]
        val background = parseBackground(bgSpec, bg)

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
        )
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
        LINEAR_GRADIENT_PATTERN.find(spec)?.let { m ->
            val angle = m.groupValues[1].toFloatOrNull() ?: 180f
            val stops = splitTopLevelCommas(m.groupValues[2]).mapNotNull { stop ->
                val cleaned = stop.trim().replace(Regex("""\s+[\d.]+%$"""), "")
                parseColor(cleaned)
            }
            if (stops.size >= 2) return ThemeBackground.LinearGradient(angle, stops.toIntArray())
        }
        parseColor(spec)?.let { return ThemeBackground.Solid(it) }
        return ThemeBackground.Solid(fallbackColor)
    }

    /** Extracts safe container CSS properties from `card-mod-card` / `card-mod-card-yaml` and flags complex rules. */
    fun parseCardMod(vars: Map<String, String>): CardModStyle {
        val css = vars["card-mod-card"].orEmpty() + "\n" + vars["card-mod-card-yaml"].orEmpty()
        if (css.isBlank()) return CardModStyle()
        val complex = "$" in css || "::part" in css || "@keyframes" in css || "{{" in css || "ha-state-icon" in css
        fun prop(name: String): String? =
            Regex("""(?:^|[;\n{])\s*$name\s*:\s*([^;!\n}]+)""", RegexOption.IGNORE_CASE).find(css)?.groupValues?.get(1)?.trim()

        val bg = prop("background-color")?.let(::parseColor) ?: prop("background")?.let(::parseColor)
        val radius = prop("border-radius")?.let { parseDimensionDp(it, -1f) }?.takeIf { it >= 0f }
        val opacity = prop("opacity")?.toFloatOrNull()?.coerceIn(0f, 1f)
        val blur = prop("backdrop-filter")?.let { bf ->
            Regex("""blur\(\s*([\d.]+)px\s*\)""", RegexOption.IGNORE_CASE).find(bf)?.groupValues?.get(1)?.toFloatOrNull()
        }
        var bw: Float? = null
        var bc: Int? = null
        prop("border")?.let { border ->
            Regex("""([\d.]+)px""").find(border)?.groupValues?.get(1)?.toFloatOrNull()?.let { bw = it }
            border.split(Regex("""\s+""")).lastOrNull()?.let(::parseColor)?.let { bc = it }
        }
        prop("border-width")?.let { parseDimensionDp(it, -1f) }?.takeIf { it >= 0f }?.let { bw = it }
        prop("border-color")?.let(::parseColor)?.let { bc = it }

        return CardModStyle(bg, radius, bw, bc, opacity, blur, complex)
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
        "purple" to 0xFF9C27B0.toInt(),
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
