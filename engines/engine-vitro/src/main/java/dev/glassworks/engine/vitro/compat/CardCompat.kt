/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import dev.glassworks.engine.vitro.cards.CssSelector
import dev.glassworks.engine.vitro.cards.MdNode
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.theme.ThemeResolver
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Where a card renders. */
enum class CardRoute {
    /** A native Vitro renderer. */
    NATIVE,

    /** A web island, so the card looks the way Home Assistant draws it. */
    ISLAND,
}

/**
 * Vitro's decision for one card: its [route], every reason it falls back (empty when native), and notes about
 * known gaps that don't force a fallback. The card's own config decides it, not its children: a stack's children
 * get their own verdicts.
 */
data class CardVerdict(
    val type: String,
    val route: CardRoute,
    val reasons: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    val isNative: Boolean get() = route == CardRoute.NATIVE
}

/**
 * Checks one card against [VitroSupport]. Pure and deterministic: it reads the card's config once (at parse time,
 * cached on [CardConfig.compat]) and never per frame. Templated parts (`{{ }}`, `{% %}`) are checked by shape: a
 * templated value can't be checked, but the property and the selector around it can.
 */
object CardCompat {
    fun check(card: CardConfig): CardVerdict {
        val reasons = LinkedHashSet<String>()
        val notes = LinkedHashSet<String>()
        val type = card.type
        when {
            type in VitroSupport.CORE_CARD_TYPES || type in VitroSupport.HACS_CARD_TYPES -> Unit
            type.startsWith("custom:") -> reasons += "`$type` is a custom card with no native renderer"
            else -> reasons += "`$type` isn't a card type Vitro draws"
        }
        if (reasons.isEmpty()) {
            checkOptions(card, reasons, notes)
            checkCardMod(card, reasons, notes)
            if (type == "markdown") checkMarkdownContent(card.raw.optString("content", ""), reasons)
        }
        return CardVerdict(
            type = type,
            route = if (reasons.isEmpty()) CardRoute.NATIVE else CardRoute.ISLAND,
            reasons = reasons.toList(),
            notes = notes.toList(),
        )
    }

    // -------------------------------------------------------------------------------------------------------------
    // Options
    // -------------------------------------------------------------------------------------------------------------

    private fun checkOptions(card: CardConfig, reasons: MutableSet<String>, notes: MutableSet<String>) {
        val raw = card.raw
        VitroSupport.UNSUPPORTED_OPTIONS[card.type]?.forEach { (key, what) ->
            if (raw.has(key) && !raw.isNull(key)) reasons += "`$key:` ($what) isn't drawn natively"
        }
        when (card.type) {
            "tile" -> {
                objects(raw.optJSONArray("features")).forEach { f ->
                    val t = f.optString("type", "")
                    if (t.isNotEmpty() && t !in VitroSupport.TILE_FEATURES) reasons += "tile feature `$t` isn't drawn natively"
                }
                if (raw.optString("features_position") == "inline" && raw.optJSONArray("features")?.length() ?: 0 > 0) {
                    notes += gap("tile features_position: inline", "`features_position: inline`")
                }
            }
            "entities", "entity-filter" -> {
                val rows = raw.optJSONArray("entities") ?: JSONArray()
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val t = row.optString("type", "")
                    if (t.isNotEmpty() && t !in VitroSupport.ENTITIES_ROW_TYPES) reasons += "entities row type `$t` isn't drawn natively"
                }
            }
            "picture-elements" -> objects(raw.optJSONArray("elements")).forEach { el ->
                val t = el.optString("type", "")
                if (t !in VitroSupport.PICTURE_ELEMENT_TYPES) reasons += "picture-elements element `${t.ifEmpty { "(no type)" }}` isn't drawn natively"
            }
            "custom:mushroom-chips-card" -> objects(raw.optJSONArray("chips")).forEach { chip ->
                val t = chip.optString("type", "entity")
                if (t !in VitroSupport.CHIP_TYPES) reasons += "mushroom chip `$t` isn't drawn natively"
                if (t == "conditional") {
                    val inner = chip.optJSONObject("chip")?.optString("type", "entity")
                    if (inner != null && (inner !in VitroSupport.CHIP_TYPES || inner == "conditional")) {
                        reasons += "mushroom chip `$inner` (in a conditional chip) isn't drawn natively"
                    }
                }
            }
            "custom:bubble-card" -> {
                val t = raw.optString("card_type", "button").ifEmpty { "button" }
                if (t !in VitroSupport.BUBBLE_CARD_TYPES) reasons += "Bubble Card `card_type: $t` isn't drawn natively"
            }
            "custom:layout-card" -> {
                val t = raw.optString("layout_type", "masonry-layout").removePrefix("custom:").ifEmpty { "masonry-layout" }
                if (t !in VitroSupport.LAYOUT_CARD_TYPES) reasons += "layout-card `layout_type: $t` isn't drawn natively"
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // card_mod
    // -------------------------------------------------------------------------------------------------------------

    /** Which CSS a sheet holds, so selectors and properties are checked against what Vitro draws there. */
    private enum class Sheet { CARD, STACK, MARKDOWN_CARD, MARKDOWN_CONTENT, CLOCK_CARD, CLOCK_DIGITAL }

    private fun checkCardMod(card: CardConfig, reasons: MutableSet<String>, notes: MutableSet<String>) {
        val cardMod = card.raw.optJSONObject("card_mod") ?: return
        for (key in cardMod.keys()) {
            when (key) {
                "style" -> Unit
                "class" -> notes += gap("card_mod.class", "`card_mod.class`")
                else -> if (key !in VitroSupport.CARD_MOD_OTHER_KEYS) notes += "card_mod `$key:` is ignored"
            }
        }
        val sheets: List<Pair<String, String>> = when (val style = cardMod.opt("style")) {
            is String -> listOf("." to style)
            is JSONObject -> style.keys().asSequence().map { k ->
                when (val v = style.opt(k)) {
                    is String -> k to v
                    else -> {
                        // A nested selector map walks further into shadow roots than Vitro models.
                        reasons += "card_mod path `$k` nests further into the card's shadow DOM, which Vitro doesn't model"
                        k to ""
                    }
                }
            }.toList()
            else -> emptyList()
        }
        val allowed = VitroSupport.cardModSheets(card.type)
        val notApplied = VitroSupport.CARD_MOD_NOT_APPLIED[card.type]
        for ((key, css) in sheets) {
            if (key !in allowed) {
                reasons += "card_mod path `$key` styles the card's inner DOM, which Vitro doesn't model"
                continue
            }
            if (css.isBlank()) continue
            val sheet = when {
                key == "ha-markdown$" -> Sheet.MARKDOWN_CONTENT
                key == "hui-clock-card-digital$" -> Sheet.CLOCK_DIGITAL
                card.type == "markdown" -> Sheet.MARKDOWN_CARD
                card.type == "clock" -> Sheet.CLOCK_CARD
                card.type in VitroSupport.STACK_TYPES -> Sheet.STACK
                else -> Sheet.CARD
            }
            if (notApplied != null && key == ".") {
                // Vitro doesn't apply this sheet yet: its properties are one known gap. A selector reaching into the
                // card's DOM still needs the island, as it would on any card.
                checkSheet(card.type, key, css, sheet, reasons, LinkedHashSet(), LinkedHashSet())
                notes += "card_mod on `${card.type}`: known gap, $notApplied"
            } else {
                checkSheet(card.type, key, css, sheet, reasons, reasons, notes)
            }
        }
    }

    private const val TPL = "__vitro_tpl__"
    private val JINJA_BLOCK = Regex("""\{%.*?%\}|\{#.*?#\}""", RegexOption.DOT_MATCHES_ALL)
    private val JINJA_EXPR = Regex("""\{\{.*?\}\}""", RegexOption.DOT_MATCHES_ALL)
    private val COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val AT_RULE = Regex("""@([a-zA-Z-]+)""")

    /** At-rules a card sheet may hold without changing the look on its own. */
    private val HARMLESS_AT_RULES = setOf("keyframes", "-webkit-keyframes", "charset")

    private fun checkSheet(
        type: String,
        key: String,
        rawCss: String,
        sheet: Sheet,
        reasons: MutableSet<String>,
        propertyReasons: MutableSet<String>,
        notes: MutableSet<String>,
    ) {
        // Template blocks go; expressions become a placeholder so the shape around them still parses.
        val css = JINJA_EXPR.replace(JINJA_BLOCK.replace(COMMENT.replace(rawCss, " "), " "), TPL)
        topLevelAtRules(css).forEach { at ->
            if (at !in HARMLESS_AT_RULES) reasons += "card_mod `@$at` rules aren't evaluated natively"
        }
        for ((selector, decls) in ThemeResolver.cssRules(css)) {
            val parts = CssSelector.splitTopLevel(selector, ',').map { it.trim() }.filter { it.isNotEmpty() }
            val verdicts = parts.map { classifySelector(it, sheet) }
            verdicts.filterIsInstance<Sel.Unsupported>().forEach { reasons += "card_mod `$key` selector `${it.selector}`: ${it.why}" }
            verdicts.filterIsInstance<Sel.Unchecked>().forEach { notes += gap("templated selector", "card_mod `$key` `${it.selector}`") }
            val honored = verdicts.filterIsInstance<Sel.Honored>()
            if (honored.isEmpty()) continue
            checkDeclarations(type, key, decls, sheet, honored.any { it.host }, propertyReasons, notes)
        }
    }

    /** The names of at-rules at the top level of [css] (`@media`, `@keyframes`, ...). */
    private fun topLevelAtRules(css: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var i = 0
        while (i < css.length) {
            when (css[i]) {
                '{' -> depth++
                '}' -> if (depth > 0) depth--
                '@' -> if (depth == 0) AT_RULE.matchAt(css, i)?.let { out += it.groupValues[1].lowercase(Locale.ROOT) }
            }
            i++
        }
        return out
    }

    private sealed class Sel {
        data class Honored(val host: Boolean) : Sel()
        object Benign : Sel()
        data class Unchecked(val selector: String) : Sel()
        data class Unsupported(val selector: String, val why: String) : Sel()
    }

    private val PSEUDO = Regex("""(::?)([a-zA-Z-]+)""")
    private val LEGACY_PSEUDO_ELEMENTS = setOf("before", "after", "first-line", "first-letter")

    private fun classifySelector(sel: String, sheet: Sheet): Sel {
        if (TPL in sel) return Sel.Unchecked(sel.replace(TPL, "{{ … }}"))
        val norm = sel.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")
        if (sheet == Sheet.STACK) {
            // A stack has no ha-card of its own: `ha-card …` in its sheet matches nothing in HA either.
            if (norm == ":host") return Sel.Honored(host = true)
            if (norm.startsWith("ha-card") || norm.startsWith(":host ha-card") || norm.startsWith(":host > ha-card")) return Sel.Benign
        } else if (ThemeResolver.isHostSelector(norm)) {
            return Sel.Honored(host = true)
        }
        var benign = false
        for (m in PSEUDO.findAll(stripAttributeValues(norm))) {
            val name = m.groupValues[2]
            val element = m.groupValues[1] == "::" || name in LEGACY_PSEUDO_ELEMENTS
            when {
                element && name in VitroSupport.BENIGN_PSEUDO_ELEMENTS -> benign = true
                element -> return Sel.Unsupported(sel, "pseudo-element `::$name` isn't drawn natively")
                name in VitroSupport.BENIGN_PSEUDO_CLASSES -> benign = true
                name in VitroSupport.PSEUDO_CLASSES -> Unit
                name == "host" -> return Sel.Unsupported(sel, "`:host` with a selector isn't modeled")
                else -> return Sel.Unsupported(sel, "`:$name` isn't evaluated natively")
            }
        }
        if (benign) return Sel.Benign
        return when (sheet) {
            Sheet.CARD -> Sel.Unsupported(sel, "it reaches inside the card, whose DOM Vitro doesn't model")
            Sheet.STACK -> Sel.Unsupported(sel, "it reaches inside the stack, whose DOM Vitro doesn't model")
            else -> {
                val parsed = CssSelector.parse(sel.trim()) ?: return Sel.Unsupported(sel, "Vitro can't parse it")
                val skeleton = skeleton(sheet)
                if (skeleton != null && skeleton.none { parsed.matches(it) }) {
                    Sel.Unsupported(sel, "it matches nothing Vitro draws, so it targets HA internals Vitro doesn't model")
                } else {
                    Sel.Honored(host = false)
                }
            }
        }
    }

    private fun stripAttributeValues(sel: String): String = sel.replace(Regex("""\[[^\]]*\]"""), "[]")

    /**
     * The DOM Vitro rebuilds for a sheet, with every variant's classes at once (a `.size-large` rule is modeled even
     * on a medium clock: it matches nothing in HA either). Null for Markdown content, which depends on the content.
     */
    private fun skeleton(sheet: Sheet): List<MdNode>? = when (sheet) {
        Sheet.MARKDOWN_CARD -> {
            val card = MdNode("ha-card", mapOf("class" to "text-only with-header"))
            listOf(card, card.add(MdNode("ha-markdown")))
        }
        Sheet.CLOCK_CARD -> {
            val card = MdNode("ha-card", mapOf("class" to "no-background"))
            val wrapper = card.add(MdNode("div", mapOf("class" to "time-wrapper size-medium size-large")))
            listOf(card, wrapper, wrapper.add(MdNode("div", mapOf("class" to "time-title"))), wrapper.add(MdNode("hui-clock-card-digital")))
        }
        Sheet.CLOCK_DIGITAL -> {
            val root = MdNode("shadow-root")
            val container = root.add(MdNode("div", mapOf("class" to "clock-container")))
            val parts = container.add(MdNode("div", mapOf("class" to "time-parts size-medium size-large")))
            listOf(container, parts) + listOf("hour", "minute", "second", "am-pm").map {
                parts.add(MdNode("div", mapOf("class" to "time-part $it")))
            }
        }
        else -> null
    }

    // -------------------------------------------------------------------------------------------------------------
    // Declarations
    // -------------------------------------------------------------------------------------------------------------

    private val SPACE = Regex("""\s+""")
    private val TRANSLATE_CENTER = Regex("""^translate[xy]?\([^)]*-50%[^)]*\)$""")
    private val BORDER_STYLE_WORDS = setOf("dashed", "dotted", "double", "groove", "ridge", "inset", "outset")
    private val FONT_FAMILY_OK = Regex("""^(inherit|initial|unset|revert|monospace|sans-serif|system-ui|inter|roboto)$""")
    private val GLOW = Regex("""^0(px)?\s+0(px)?\s""")
    private val OFFSETS = setOf("top", "right", "bottom", "left")

    /**
     * Checks one rule's declarations. On a Markdown or clock card, the card's own rule (`ha-card { }`) is drawn
     * twice over: the surface from the card properties, and the content inherits its typography through the cascade,
     * so both sets apply there.
     */
    private fun checkDeclarations(
        type: String,
        key: String,
        decls: List<Pair<String, String>>,
        sheet: Sheet,
        host: Boolean,
        reasons: MutableSet<String>,
        notes: MutableSet<String>,
    ) {
        val cascadeSheet = sheet != Sheet.CARD && sheet != Sheet.STACK
        val surface = sheet == Sheet.CARD || (host && (sheet == Sheet.MARKDOWN_CARD || sheet == Sheet.CLOCK_CARD))
        val cascade = cascadeSheet
        val byName = decls.associate { it.first to it.second.trim().lowercase(Locale.ROOT) }
        val position = byName["position"]
        val pinned = position == "fixed" || position == "absolute"
        for ((name, rawValue) in decls) {
            val shown = "`$name: ${rawValue.trim()}`"
            val value = rawValue.trim().lowercase(Locale.ROOT)
            // A templated or var() value can't be checked here: the property alone decides.
            val checkable = TPL !in value && "var(" !in value
            if (name.startsWith("--")) {
                if (!cascade && name.removePrefix("--") !in VitroSupport.CARD_VARIABLES) notes += gap("card variable", "`$name` on `$type`")
                continue
            }
            val asCard = surface && (name in VitroSupport.CARD_PROPERTIES ||
                (name in VitroSupport.CARD_FONT_PROPERTIES && type in VitroSupport.FONT_SIZE_TYPES))
            val asStack = sheet == Sheet.STACK && name in VitroSupport.STACK_PROPERTIES
            val asCascade = cascade && (name in VitroSupport.CASCADE_PROPERTIES || name == "position" || name in OFFSETS)
            if (!asCard && !asStack && !asCascade) {
                val benignValues = VitroSupport.BENIGN_VALUES[name]
                when {
                    name in VitroSupport.BENIGN_PROPERTIES -> Unit
                    benignValues != null && value in benignValues -> Unit
                    name in VitroSupport.KNOWN_GAP_PROPERTIES -> notes += gap(name, shown)
                    // Offsets without a position do nothing in the browser either.
                    name in OFFSETS && (position == null || position == "static") -> Unit
                    else -> reasons += "card_mod `$key` $shown isn't supported here"
                }
                continue
            }
            if (!checkable) continue
            val problem = if (asCard) checkCardValue(name, value, pinned, position, shown, notes)
            else if (asCascade) checkCascadeValue(sheet, name, value, position, shown, notes)
            else null
            problem?.let { reasons += "card_mod `$key` $shown $it" }
        }
    }

    /** Why a card-surface value can't be drawn, or null when it can. */
    private fun checkCardValue(name: String, value: String, pinned: Boolean, position: String?, shown: String, notes: MutableSet<String>): String? = when (name) {
        "background", "background-image" -> backgroundProblem(value, allowGradient = true)
        "border" -> if (SPACE.split(value).any { it in BORDER_STYLE_WORDS }) "isn't drawn natively (only solid borders)" else null
        "position" -> if (value in setOf("fixed", "absolute", "static", "relative", "initial", "unset", "inherit")) null else "isn't drawn natively"
        "top", "right", "bottom", "left" -> {
            if (!pinned && position != null && position != "static") notes += gap("position: relative", shown)
            null
        }
        "width" -> if (pinned || value in setOf("auto", "100%", "initial", "unset")) null else "only applies to pinned (`position: fixed`) cards"
        "height" -> if (value in setOf("auto", "100%", "initial", "unset", "inherit", "fit-content")) null else "isn't drawn natively (only `auto` and `100%`)"
        "transform" -> when {
            value == "none" -> null
            pinned && SPACE.split(value).all { TRANSLATE_CENTER.matches(it) } -> null
            else -> "isn't drawn natively (only centering a pinned card)"
        }
        "box-shadow" -> when {
            value == "none" -> null
            "inset" in value -> "isn't drawn natively (inset shadow)"
            else -> {
                if (GLOW.containsMatchIn(value)) notes += gap("box-shadow: glow", shown)
                null
            }
        }
        else -> null
    }

    /** Why a cascade value can't be drawn, or null when it can. */
    private fun checkCascadeValue(sheet: Sheet, name: String, value: String, position: String?, shown: String, notes: MutableSet<String>): String? = when (name) {
        "display" -> if (sheet == Sheet.CLOCK_CARD || sheet == Sheet.CLOCK_DIGITAL || value in VitroSupport.MARKDOWN_DISPLAY) null
        else "isn't laid out natively"
        "background", "background-color" -> backgroundProblem(value, allowGradient = false)
        "font-family" -> {
            val families = CssSelector.splitTopLevel(value, ',').map { it.trim().trim('"', '\'') }
            if (families.any { FONT_FAMILY_OK.matches(it) || "mono" in it }) null else "uses a font Vitro doesn't bundle"
        }
        "white-space" -> {
            if (value == "nowrap") notes += gap("white-space: nowrap", shown)
            null
        }
        "position" -> when (value) {
            "static", "initial", "unset" -> null
            "relative" -> {
                notes += gap("position: relative", shown)
                null
            }
            else -> "isn't laid out natively"
        }
        "top", "right", "bottom", "left" -> if (position == null || position == "static" || position == "relative") null else "isn't laid out natively"
        "overflow", "overflow-x", "overflow-y" ->
            if (value in setOf("hidden", "clip", "visible", "initial", "unset")) null else "isn't drawn natively (no scrolling inside a card)"
        else -> null
    }

    private fun backgroundProblem(value: String, allowGradient: Boolean): String? = when {
        "url(" in value -> "isn't drawn natively (background images)"
        "radial-gradient" in value || "conic-gradient" in value || "repeating-" in value -> "isn't drawn natively (only linear gradients)"
        !allowGradient && "gradient" in value -> "isn't drawn natively (no gradients inside Markdown)"
        CssSelector.splitTopLevel(value, ',').size > 1 && "gradient" !in value -> "isn't drawn natively (layered backgrounds)"
        else -> null
    }

    // -------------------------------------------------------------------------------------------------------------
    // Markdown content
    // -------------------------------------------------------------------------------------------------------------

    private val OPEN_TAG = Regex("""<([a-zA-Z][a-zA-Z0-9-]*)""")

    private fun checkMarkdownContent(content: String, reasons: MutableSet<String>) {
        for (m in OPEN_TAG.findAll(content)) {
            val tag = m.groupValues[1].lowercase(Locale.ROOT)
            VitroSupport.UNDRAWN_MARKDOWN_TAGS[tag]?.let { reasons += "Markdown `<$tag>` ($it) isn't drawn natively" }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A known-gap note: what it's about, then why it stays native. */
    private fun gap(key: String, subject: String): String = "$subject: known gap, " + (VitroSupport.KNOWN_GAP_PROPERTIES[key] ?: key)

    private fun objects(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}
