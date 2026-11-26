/* SPDX-License-Identifier: MIT
 *
 * Ported from button-card: custom-cards/button-card, tag v7.0.1. Copyright (c) 2018, Alexandre Garcia.
 * The licence text is in THIRD-PARTY-NOTICES.md and LICENSES/MIT.txt.
 * Changes: rewritten in Kotlin for Vitro's native views; its `styles:` lists read as lit's styleMap would, plus what Vitro draws. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.compat.VitroSupport
import dev.glassworks.engine.vitro.theme.ThemeResolver
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The CSS in button-card's `styles:` lists, read the way the browser reads lit's `styleMap` on each element: each
 * entry is one declaration, a value the browser rejects (a stray `;`, unbalanced brackets) is dropped, ` !important`
 * is kept apart, and a number becomes its text. The checker ([dev.glassworks.engine.vitro.compat.CardCompat]) and the
 * native card read styles through here, so they agree on what's drawn.
 */
object ButtonCardCss {
    /** One declaration: its property (as written; CSS custom properties keep their case) and its value. */
    data class Decl(val name: String, val value: String, val important: Boolean)

    /**
     * A style part's list (`styles.card: [{color: red}, …]`) as declarations, later entries winning, or null when
     * it isn't a list (button-card would throw spreading it, and HA would show an error).
     */
    fun declarations(list: Any?): List<Decl>? {
        if (list == null || list == JSONObject.NULL) return emptyList()
        if (list !is JSONArray) return null
        val out = LinkedHashMap<String, Decl>()
        for (i in 0 until list.length()) {
            val entry = list.opt(i) as? JSONObject ?: continue
            for (k in entry.keys()) {
                val raw = entry.opt(k)
                // `Object.assign` keeps a null; lit's styleMap then removes the property.
                if (raw == null || raw == JSONObject.NULL) {
                    out.remove(k)
                    continue
                }
                val text = ButtonCardJs.value(if (raw is String) raw else jsString(raw)) as String
                out.remove(k)
                out[k] = toDecl(k, text) ?: continue
            }
        }
        return out.values.toList()
    }

    /**
     * A part's declarations for one render: the config's, then the matched `state:` entry's over them
     * (`_buildStyleGeneric`). [field] picks one `custom_fields` list.
     */
    fun part(config: JSONObject, state: JSONObject?, part: String, field: String? = null): Map<String, Decl> {
        fun of(o: JSONObject?): List<Decl> {
            val styles = o?.optJSONObject("styles") ?: return emptyList()
            val list = if (field != null) styles.optJSONObject("custom_fields")?.opt(field) else styles.opt(part)
            return declarations(list).orEmpty()
        }
        val out = LinkedHashMap<String, Decl>()
        // A later declaration moves to the end, so the map keeps the order the browser applies them in.
        for (d in of(config)) { out.remove(d.name); out[d.name] = d }
        for (d in of(state)) { out.remove(d.name); out[d.name] = d }
        return out
    }

    /** A declaration the browser takes, or null for one it drops (lit's styleMap then sets nothing). */
    private fun toDecl(name: String, rawValue: String): Decl? {
        // Comments first, quote-aware: a quoted `/*` is text, and a comment after `!important` doesn't hide it.
        var v = ThemeResolver.stripCssComments(rawValue).trim()
        var important = false
        if (v.endsWith("!important")) {
            important = true
            v = v.removeSuffix("!important").trim()
        }
        if (v.isEmpty() || !balanced(v)) return null
        return Decl(name.trim(), v, important)
    }

    /** No `;`, `{` or `}` outside quotes, and brackets that close: anything else is a value the browser rejects. */
    private fun balanced(v: String): Boolean {
        var depth = 0
        var quote = 0.toChar()
        for (c in v) {
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar()
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '(' -> depth++
                ')' -> if (--depth < 0) return false
                ';', '{', '}' -> return false
            }
        }
        return depth == 0 && quote == 0.toChar()
    }

    // ---------------------------------------------------------------------------------------------------------
    // Values
    // ---------------------------------------------------------------------------------------------------------

    /** A CSS length: [value] in [unit] (`px`, `%`, `em`, `rem`, `vw`, `vh`), or `auto`. */
    data class Length(val value: Float, val unit: String) {
        val isAuto: Boolean get() = unit == "auto"

        /**
         * In CSS px: % of [percentOf], em of [fontPx], rem of HA's root font (14 px: button-card's `1.2rem` is 16.8 px),
         * vw and vh of the viewport ([vwPx], [vhPx]).
         */
        fun px(percentOf: Float, fontPx: Float = 16f, vwPx: Float = viewportWidth, vhPx: Float = viewportHeight): Float = when (unit) {
            "%" -> value * percentOf / 100f
            "em" -> value * fontPx
            "rem" -> value * ROOT_FONT_PX
            "vw" -> value * vwPx / 100f
            "vh" -> value * vhPx / 100f
            "pt" -> value * 4f / 3f
            "auto" -> Float.NaN
            else -> value
        }
    }

    /** The viewport `vw` and `vh` are a hundredth of, in CSS px: the panel's screen ([viewport]). */
    @Volatile
    var viewportWidth = 0f
        private set

    @Volatile
    var viewportHeight = 0f
        private set

    /** Sets the viewport (the kiosk shows the dashboard full screen): the panel's screen in CSS px. */
    fun viewport(widthCss: Float, heightCss: Float) {
        viewportWidth = widthCss
        viewportHeight = heightCss
    }

    /** HA's root font size: `html { font-size }` in its frontend (`1.2rem` measures 16.8 px, 2026-11-24). */
    const val ROOT_FONT_PX = 14f

    private val LENGTH = Regex("""^([+-]?(?:\d+\.?\d*|\.\d+))(px|%|em|rem|vw|vh|pt)?$""", RegexOption.IGNORE_CASE)

    /** A single length (`12px`, `40%`, `1.2em`, `0`), `auto` when [auto], else null. A unitless number is only 0. */
    fun length(raw: String, auto: Boolean = false): Length? {
        val s = raw.trim().lowercase(Locale.ROOT)
        if (auto && s == "auto") return Length(0f, "auto")
        val m = LENGTH.matchEntire(s) ?: return null
        val n = m.groupValues[1].toFloatOrNull() ?: return null
        val unit = m.groupValues[2]
        if (unit.isEmpty() && n != 0f) return null
        return Length(n, unit.ifEmpty { "px" })
    }

    /** One to four lengths (`padding: 4px 8px`), as top, right, bottom, left. */
    fun box(raw: String, auto: Boolean = false): List<Length>? {
        val parts = raw.trim().split(SPACES).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 4) return null
        val l = parts.map { length(it, auto) ?: return null }
        return when (l.size) {
            1 -> listOf(l[0], l[0], l[0], l[0])
            2 -> listOf(l[0], l[1], l[0], l[1])
            3 -> listOf(l[0], l[1], l[2], l[1])
            else -> l
        }
    }

    private val SPACES = Regex("""\s+""")

    /**
     * A CSS color after `var()` substitution ([vars]): `#hex`, `rgb[a]()`, `hsl[a]()`, a CSS named color (CSS's
     * own values: `red` is #ff0000, `grey` #808080) or `transparent`. Null for anything else (`inherit` and
     * `currentcolor` are the caller's).
     */
    fun color(raw: String, vars: Map<String, String> = emptyMap()): Int? {
        val s = ThemeResolver.resolveValue(raw, vars, 0).trim().lowercase(Locale.ROOT)
        CSS_NAMED_COLORS[s]?.let { return it }
        if (s in HA_PALETTE_NAMES) return null
        return ThemeResolver.parseColor(s)
    }

    /** True when [raw] could be a color once its `var()`s are known: a color, `var(…)`, or a keyword that inherits. */
    fun colorLike(raw: String): Boolean {
        val s = raw.trim().lowercase(Locale.ROOT)
        return s.startsWith("var(") || s in COLOR_KEYWORDS || color(s) != null
    }

    private val COLOR_KEYWORDS = setOf("inherit", "currentcolor", "initial", "unset")

    /** Names [ThemeResolver.parseColor] reads as HA's palette but CSS doesn't know: not colors in a style. */
    private val HA_PALETTE_NAMES = setOf("amber")

    /** CSS Color Module 4's named colors. */
    val CSS_NAMED_COLORS: Map<String, Int> by lazy {
        val out = HashMap<String, Int>()
        for (pair in NAMED.trim().split(SPACES)) {
            val (name, hex) = pair.split('=')
            out[name] = (0xFF000000L or hex.toLong(16)).toInt()
        }
        out["transparent"] = 0
        out
    }

    private const val NAMED = """
        aliceblue=f0f8ff antiquewhite=faebd7 aqua=00ffff aquamarine=7fffd4 azure=f0ffff beige=f5f5dc bisque=ffe4c4
        black=000000 blanchedalmond=ffebcd blue=0000ff blueviolet=8a2be2 brown=a52a2a burlywood=deb887
        cadetblue=5f9ea0 chartreuse=7fff00 chocolate=d2691e coral=ff7f50 cornflowerblue=6495ed cornsilk=fff8dc
        crimson=dc143c cyan=00ffff darkblue=00008b darkcyan=008b8b darkgoldenrod=b8860b darkgray=a9a9a9
        darkgreen=006400 darkgrey=a9a9a9 darkkhaki=bdb76b darkmagenta=8b008b darkolivegreen=556b2f
        darkorange=ff8c00 darkorchid=9932cc darkred=8b0000 darksalmon=e9967a darkseagreen=8fbc8f
        darkslateblue=483d8b darkslategray=2f4f4f darkslategrey=2f4f4f darkturquoise=00ced1 darkviolet=9400d3
        deeppink=ff1493 deepskyblue=00bfff dimgray=696969 dimgrey=696969 dodgerblue=1e90ff firebrick=b22222
        floralwhite=fffaf0 forestgreen=228b22 fuchsia=ff00ff gainsboro=dcdcdc ghostwhite=f8f8ff gold=ffd700
        goldenrod=daa520 gray=808080 green=008000 greenyellow=adff2f grey=808080 honeydew=f0fff0 hotpink=ff69b4
        indianred=cd5c5c indigo=4b0082 ivory=fffff0 khaki=f0e68c lavender=e6e6fa lavenderblush=fff0f5
        lawngreen=7cfc00 lemonchiffon=fffacd lightblue=add8e6 lightcoral=f08080 lightcyan=e0ffff
        lightgoldenrodyellow=fafad2 lightgray=d3d3d3 lightgreen=90ee90 lightgrey=d3d3d3 lightpink=ffb6c1
        lightsalmon=ffa07a lightseagreen=20b2aa lightskyblue=87cefa lightslategray=778899 lightslategrey=778899
        lightsteelblue=b0c4de lightyellow=ffffe0 lime=00ff00 limegreen=32cd32 linen=faf0e6 magenta=ff00ff
        maroon=800000 mediumaquamarine=66cdaa mediumblue=0000cd mediumorchid=ba55d3 mediumpurple=9370db
        mediumseagreen=3cb371 mediumslateblue=7b68ee mediumspringgreen=00fa9a mediumturquoise=48d1cc
        mediumvioletred=c71585 midnightblue=191970 mintcream=f5fffa mistyrose=ffe4e1 moccasin=ffe4b5
        navajowhite=ffdead navy=000080 oldlace=fdf5e6 olive=808000 olivedrab=6b8e23 orange=ffa500
        orangered=ff4500 orchid=da70d6 palegoldenrod=eee8aa palegreen=98fb98 paleturquoise=afeeee
        palevioletred=db7093 papayawhip=ffefd5 peachpuff=ffdab9 peru=cd853f pink=ffc0cb plum=dda0dd
        powderblue=b0e0e6 purple=800080 rebeccapurple=663399 red=ff0000 rosybrown=bc8f8f royalblue=4169e1
        saddlebrown=8b4513 salmon=fa8072 sandybrown=f4a460 seagreen=2e8b57 seashell=fff5ee sienna=a0522d
        silver=c0c0c0 skyblue=87ceeb slateblue=6a5acd slategray=708090 slategrey=708090 snow=fffafa
        springgreen=00ff7f steelblue=4682b4 tan=d2b48c teal=008080 thistle=d8bfd8 tomato=ff6347
        turquoise=40e0d0 violet=ee82ee wheat=f5deb3 white=ffffff whitesmoke=f5f5f5 yellow=ffff00
        yellowgreen=9acd32
    """

    // ---------------------------------------------------------------------------------------------------------
    // What Vitro draws
    // ---------------------------------------------------------------------------------------------------------

    private val ALIGN = setOf("start", "end", "center", "stretch", "normal", "left", "right", "self-start", "self-end", "flex-start", "flex-end", "baseline", "auto")
    private val FLEX_ALIGN = setOf("center", "flex-start", "flex-end", "start", "end", "normal", "stretch", "left", "right")
    private val TRANSFORM_TRANSLATE = Regex("""^translate(x|y)?\(([^)]*)\)$""", RegexOption.IGNORE_CASE)

    /**
     * Why [d] in the style part [part] can't be drawn natively, or null when Vitro draws it (or it doesn't change the
     * look). [part] is a key of [VitroSupport.BUTTON_CARD_STYLE_PROPERTIES] (`custom_field` for any custom field).
     */
    fun problem(part: String, d: Decl): String? {
        val name = d.name.lowercase(Locale.ROOT)
        val v = d.value.trim()
        val lv = v.lowercase(Locale.ROOT)
        if (name.startsWith("--")) return null
        if (name in VitroSupport.BENIGN_PROPERTIES) return null
        VitroSupport.BENIGN_VALUES[name]?.let { ok -> if (lv in ok) return null }
        if (lv in GLOBAL_KEYWORDS && name !in INHERITING) return null
        val allowed = VitroSupport.BUTTON_CARD_STYLE_PROPERTIES[part] ?: return "a style part Vitro doesn't draw natively"
        if (name !in allowed) return "`${propertyName(name)}`, which Vitro doesn't draw natively"
        if ("var(" in lv) return null
        val ok = when (name) {
            "color", "background-color", "border-color" -> colorLike(v)
            "background" -> colorLike(v) || lv == "none" || backgroundOk(lv)
            "background-image" -> lv == "none" || backgroundOk(lv)
            "opacity" -> lv.removeSuffix("%").toFloatOrNull() != null
            "font-size" -> length(lv) != null || lv in FONT_SIZE_WORDS
            "font-weight" -> lv in FONT_WEIGHTS || lv.toIntOrNull()?.let { it in 1..1000 } == true
            "font-family" -> true
            "font-style" -> lv in setOf("normal", "italic", "oblique")
            "text-transform" -> lv in setOf("none", "uppercase", "lowercase", "capitalize")
            "letter-spacing" -> lv == "normal" || length(lv) != null
            "line-height" -> lv == "normal" || lv.toFloatOrNull() != null || length(lv) != null
            "text-align" -> lv in setOf("left", "right", "center", "start", "end", "justify")
            "white-space" -> lv in setOf("normal", "nowrap", "pre", "pre-wrap", "pre-line", "break-spaces")
            "text-overflow" -> lv in setOf("ellipsis", "clip")
            "overflow" -> lv in setOf("hidden", "visible", "clip", "auto")
            "justify-self", "align-self", "justify-items", "align-items" -> lv in ALIGN
            "justify-content" -> lv in FLEX_ALIGN
            "display" -> lv in setOf("none", "block", "flex", "inline-block", "inline-flex", "grid", "inline")
            "padding", "padding-top", "padding-right", "padding-bottom", "padding-left" -> box(lv) != null
            "margin", "margin-top", "margin-right", "margin-bottom", "margin-left" -> box(lv, auto = true) != null
            "width", "height", "min-height", "min-width", "max-width", "max-height", "top", "right", "bottom", "left" ->
                length(lv, auto = true) != null || (name.startsWith("max-") && lv == "none") || lv == "fit-content"
            "border-radius" -> box(lv) != null
            "border" -> lv == "none" || lv == "0" || borderOk(lv)
            "border-width" -> length(lv) != null
            "border-style" -> lv in setOf("solid", "none", "hidden")
            "box-shadow" -> lv == "none" || shadowOk(lv)
            "transform" -> lv == "none" || TRANSFORM_TRANSLATE.matchEntire(lv)?.groupValues?.get(2)?.split(',')?.all { length(it.trim()) != null } == true
            "animation" -> lv == "none" || animation(lv) != null
            "position" -> lv in setOf("absolute", "relative", "static")
            "box-sizing" -> true
            "grid-template-areas" -> CssGridSpec.areas(v) != null
            "grid-template-columns", "grid-template-rows" -> CssGridSpec.tracks(v) != null
            "gap", "grid-gap" -> box(lv)?.let { true } == true && lv.split(SPACES).size <= 2
            "column-gap", "row-gap", "grid-column-gap", "grid-row-gap" -> length(lv) != null || lv == "normal"
            "grid-area" -> CssGridSpec.IDENT.matches(lv)
            else -> true
        }
        // The property, never its value: a value is the dashboard's text (an address with a signature, a CSS escape that
        // hides one), and diagnostics go to logcat and the HUD.
        return if (ok) null else "`${propertyName(name)}` to a value Vitro doesn't draw natively"
    }

    private val PROPERTY_NAME = Regex("""^-?[a-z][a-z-]{0,39}$""")

    /** A CSS property name as a reason may show it: a plain one as it is, anything else as "a property". */
    private fun propertyName(name: String): String = if (PROPERTY_NAME.matches(name)) name else "a property"

    private val GLOBAL_KEYWORDS = setOf("initial", "unset", "inherit", "revert")
    /** Properties whose keyword values change what's drawn in a way Vitro doesn't follow. */
    private val INHERITING = setOf("color", "font-size", "font-weight", "font-family")
    private val FONT_SIZE_WORDS = setOf("small", "medium", "large", "x-small", "x-large", "xx-small", "xx-large", "smaller", "larger")
    private val FONT_WEIGHTS = setOf("normal", "bold", "bolder", "lighter")

    /** `background`: one color, or a linear gradient of colors (the card surface draws those). */
    private fun backgroundOk(lv: String): Boolean =
        lv.startsWith("linear-gradient(") && "url(" !in lv

    /** `border: 1px solid <color>` in any order, width and color optional. */
    private fun borderOk(lv: String): Boolean = borderParts(lv) != null

    /** A `border` shorthand as width (px), style and color text, or null when it isn't one Vitro draws. */
    fun borderParts(lv: String): Triple<Length?, String?, String?>? {
        var width: Length? = null
        var style: String? = null
        var color: String? = null
        for (t in splitTopLevel(lv)) {
            when {
                t in setOf("solid", "none", "hidden") -> style = t
                t in setOf("dashed", "dotted", "double", "groove", "ridge", "inset", "outset") -> return null
                t in setOf("thin", "medium", "thick") -> width = Length(mapOf("thin" to 1f, "medium" to 3f, "thick" to 5f)[t]!!, "px")
                length(t) != null -> width = length(t)
                colorLike(t) -> color = t
                else -> return null
            }
        }
        return Triple(width, style, color)
    }

    /** `box-shadow` layers Vitro draws as an elevation (outer, not inset). */
    private fun shadowOk(lv: String): Boolean = "inset" !in lv

    /** `animation: blink 2s ease infinite`: button-card's own keyframes, as the name and its duration in ms. */
    fun animation(lv: String): Pair<String, Long>? {
        var name: String? = null
        var ms = 0L
        for (t in lv.trim().split(SPACES)) {
            when {
                t in VitroSupport.BUTTON_CARD_ANIMATIONS -> name = t
                t.endsWith("ms") && t.removeSuffix("ms").toFloatOrNull() != null -> if (ms == 0L) ms = t.removeSuffix("ms").toFloat().toLong()
                t.endsWith("s") && t.removeSuffix("s").toFloatOrNull() != null -> if (ms == 0L) ms = (t.removeSuffix("s").toFloat() * 1000).toLong()
                t in setOf("infinite", "linear", "ease", "ease-in", "ease-out", "ease-in-out", "normal", "both", "forwards") -> Unit
                t.toFloatOrNull() != null -> Unit
                else -> return null
            }
        }
        return name?.let { it to ms }
    }

    /** Splits on spaces outside brackets (`1px solid rgba(0, 0, 0, 0.4)` is three tokens). */
    fun splitTopLevel(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in s.trim()) {
            when {
                c == '(' -> { depth++; cur.append(c) }
                c == ')' -> { depth--; cur.append(c) }
                c.isWhitespace() && depth == 0 -> if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}

/** The CSS grid of a button-card (`grid-template-areas`, `-columns` and `-rows`), parsed. */
object CssGridSpec {
    /** One track size: `px`/`%` ([fixed]), `fr` ([fr]), or sized to its content (`auto`, `min-content`, `max-content`). */
    data class Track(val fixed: ButtonCardCss.Length? = null, val fr: Float = 0f, val content: String? = null)

    val IDENT = Regex("""^[a-zA-Z_][\w-]*$""")
    private val QUOTED = Regex(""""([^"]*)"|'([^']*)'""")

    /**
     * `grid-template-areas: "i n" "i s"` as rows of cell names (`.` an empty cell), or null when it isn't valid
     * (rows of different lengths, an area that isn't a rectangle).
     */
    fun areas(raw: String): List<List<String>>? {
        val rows = QUOTED.findAll(raw).map { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim().split(Regex("""\s+""")).filter { it.isNotEmpty() } }.toList()
        if (rows.isEmpty() || rows.any { it.isEmpty() }) return null
        if (QUOTED.replace(raw, "").isNotBlank()) return null
        val cols = rows[0].size
        if (rows.any { it.size != cols }) return null
        if (rows.flatten().any { it != "." && !IDENT.matches(it) && !Regex("""^\.+$""").matches(it) }) return null
        // Every named area must be a rectangle.
        for (name in rows.flatten().toSet().filter { !it.startsWith(".") }) {
            val cells = rows.flatMapIndexed { r, row -> row.mapIndexedNotNull { c, n -> if (n == name) r to c else null } }
            val r0 = cells.minOf { it.first }; val r1 = cells.maxOf { it.first }
            val c0 = cells.minOf { it.second }; val c1 = cells.maxOf { it.second }
            if (cells.size != (r1 - r0 + 1) * (c1 - c0 + 1)) return null
        }
        return rows
    }

    /** Track sizes (`40% 1fr`, `1fr min-content min-content`, `repeat(2, 1fr)`), or null for one Vitro doesn't size. */
    fun tracks(raw: String): List<Track>? {
        val s = raw.trim().lowercase(Locale.ROOT)
        if (s == "none" || s.isEmpty()) return null
        val out = ArrayList<Track>()
        for (t in ButtonCardCss.splitTopLevel(expandRepeat(s) ?: return null)) {
            out += when {
                t.endsWith("fr") -> Track(fr = t.removeSuffix("fr").toFloatOrNull()?.takeIf { it >= 0f } ?: return null)
                t == "auto" || t == "min-content" || t == "max-content" || t == "fit-content" -> Track(content = t)
                else -> Track(fixed = ButtonCardCss.length(t)?.takeIf { it.value >= 0f } ?: return null)
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private val REPEAT = Regex("""repeat\(\s*(\d+)\s*,\s*([^()]+)\)""")

    /** The most tracks a `repeat()` may make natively: more is a grid Vitro doesn't draw (null). */
    private const val MAX_REPEAT = 24

    private fun expandRepeat(s: String): String? {
        var bad = false
        val out = REPEAT.replace(s) { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n == null || n > MAX_REPEAT) { bad = true; "" } else List(n) { m.groupValues[2].trim() }.joinToString(" ")
        }
        return if (bad || "(" in out) null else out
    }
}
