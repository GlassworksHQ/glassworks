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
 * get their own verdicts. The one exception: a custom container with a built-in card inside stays native, because
 * the island would draw that child as a summary.
 */
data class CardVerdict(
    val type: String,
    val route: CardRoute,
    val reasons: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    /** True when every reason is the card's card_mod, which the island only applies when card-mod is a resource. */
    val cardModOnly: Boolean = false,
) {
    val isNative: Boolean get() = route == CardRoute.NATIVE

    /**
     * Whether the card is built in an island. With [cardModInIsland] false (card-mod isn't a Lovelace resource, so
     * the island can't load it), a fallback for card_mod alone would lose that style in the island too, so the card
     * stays native.
     */
    fun inIsland(cardModInIsland: Boolean = true): Boolean = route == CardRoute.ISLAND && (cardModInIsland || !cardModOnly)

    /** This verdict as the view builds it: native, with the reasons as notes, when [inIsland] says the card stays. */
    fun effective(cardModInIsland: Boolean = true): CardVerdict =
        if (route != CardRoute.ISLAND || inIsland(cardModInIsland)) this
        else copy(
            route = CardRoute.NATIVE,
            reasons = emptyList(),
            notes = notes + reasons.map { "would fall back for $it, but the island can't load card-mod (it isn't a Lovelace resource)" },
        )
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
        var cardModOnly = false
        if (reasons.isEmpty()) {
            checkOptions(card, reasons, notes)
            val beforeCardMod = reasons.size
            checkCardMod(card, reasons, notes)
            cardModOnly = beforeCardMod == 0 && reasons.isNotEmpty()
            if (type == "markdown") checkMarkdownContent(card.raw.optString("content", ""), reasons)
        }
        // The island runs a custom card's own JavaScript, so it draws that card as HA does. It has no HA frontend for
        // built-in cards, so a built-in card there would be a plain summary: further from HA than Vitro's native
        // card. Until the island can run HA's card code, a built-in card Vitro draws stays native and the checker
        // says what it would have fallen back for. So does a custom container Vitro draws (stack-in-card,
        // layout-card) with a built-in card anywhere inside it: in the island, that child would be a summary too.
        if (reasons.isNotEmpty()) {
            val why = when {
                type in VitroSupport.CORE_CARD_TYPES -> "the island can't draw built-in cards yet"
                type in VitroSupport.HACS_CARD_TYPES && (card.childCards.any(::needsHaFrontend) || generatesBuiltInCards(card)) ->
                    "the island can't draw the built-in cards inside it yet"
                else -> null
            }
            if (why != null) {
                reasons.forEach { notes += "would fall back for $it, but $why" }
                return CardVerdict(type = type, route = CardRoute.NATIVE, notes = notes.toList())
            }
        }
        return CardVerdict(
            type = type,
            route = if (reasons.isEmpty()) CardRoute.NATIVE else CardRoute.ISLAND,
            reasons = reasons.toList(),
            notes = notes.toList(),
            cardModOnly = cardModOnly,
        )
    }

    /** True when [card] or a card inside it is a built-in card, which the island can only draw as a summary. */
    private fun needsHaFrontend(card: CardConfig): Boolean =
        !card.type.startsWith("custom:") || card.childCards.any(::needsHaFrontend) || generatesBuiltInCards(card)

    /**
     * True when `auto-entities` builds built-in cards at runtime: its `card:` (an `entities` card by default), or with
     * `card_param: cards` one card per entity, from each top-level `entities:` entry and each rule's `options`
     * (a `tile` by default), the way [dev.glassworks.engine.vitro.cards.AutoEntitiesCardView] builds them.
     */
    private fun generatesBuiltInCards(card: CardConfig): Boolean {
        if (card.type != "custom:auto-entities") return false
        val inner = card.raw.optJSONObject("card")?.optString("type", "")?.ifEmpty { null } ?: "entities"
        if (!inner.startsWith("custom:")) return true
        if (card.raw.optString("card_param", "entities") != "cards") return false
        val listed = card.raw.optJSONArray("entities")
        val entryTypes = (0 until (listed?.length() ?: 0)).mapNotNull { i ->
            when (val e = listed?.opt(i)) {
                is String -> "tile"
                is JSONObject -> e.optString("type", "").ifEmpty { "tile" }
                else -> null
            }
        }
        val ruleTypes = objects(card.raw.optJSONObject("filter")?.optJSONArray("include")).map { r ->
            r.optJSONObject("options")?.optString("type", "")?.ifEmpty { null } ?: "tile"
        }
        val types = entryTypes + ruleTypes
        return types.isEmpty() || types.any { !it.startsWith("custom:") }
    }

    /**
     * A CSS value as the checker may log it. The reasons go to logcat and the HUD, so an address (which can carry a
     * signed or tokenised query) never shows: a `url(...)` becomes `url(…)`, and a quoted string that looks like an
     * address (`://`, or a leading `/`), in `image-set()` or any other function, becomes `"…"`. A long value is cut.
     */
    internal fun loggable(value: String): String {
        val unquoted = URL_FN.replace(value.trim()) { "url(…)" }
        // Every source inside `image-set()` is an address, relative ones included.
        val imageSets = IMAGE_SET.findAll(unquoted).map { m -> m.range.first..closingParen(unquoted, m.range.last) }.toList()
        val noUrls = QUOTED.replace(unquoted) { m ->
            val inner = m.value.substring(1, m.value.length - 1).trim()
            val address = imageSets.any { m.range.first in it } || "://" in inner || inner.startsWith("/") || '?' in inner || '=' in inner
            if (address) "\"…\"" else m.value
        }
        // A declaration split inside a quoted address (a `;` in it) leaves an unterminated `url(` or quote: cut there.
        val masked = QUOTED.replace(noUrls) { "x".repeat(it.value.length) }
        val open = UNTERMINATED.find(masked)?.range?.first
        val safe = if (open != null) noUrls.substring(0, open) + "…" else noUrls
        return if (safe.length <= MAX_LOGGED_VALUE) safe else safe.take(MAX_LOGGED_VALUE) + "…"
    }

    private val IMAGE_SET = Regex("""(?:-webkit-)?image-set\(""", RegexOption.IGNORE_CASE)

    /** The index of the `)` closing the `(` at [open], or the end of [s]. */
    private fun closingParen(s: String, open: Int): Int {
        var depth = 0
        for (k in open until s.length) {
            when (s[k]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return k
            }
        }
        return s.length - 1
    }

    private val UNTERMINATED = Regex("""url\((?!…\))|["']""", RegexOption.IGNORE_CASE)

    // CSS escapes (`\)`, `\"`) don't end a URL, quoted or not.
    private val QUOTED = Regex(""""(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'""")
    private val URL_FN = Regex("""url\(\s*(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|(?:\\.|[^)\\])*)\s*\)""", RegexOption.IGNORE_CASE)
    private const val MAX_LOGGED_VALUE = 80

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
        val rules = ThemeResolver.cssRulesImportant(css).map { (selector, decls) ->
            val parts = CssSelector.splitTopLevel(selector, ',').map { it.trim() }.filter { it.isNotEmpty() }
            Triple(selector, decls, parts.map { classifySelector(it, sheet) })
        }
        // Vitro merges every rule on the card itself into one style, so the `position` that wins there pins the card
        // for all of them (`ha-card { position: fixed }` then `:host { width: … }`). As in the browser, and in
        // [ThemeResolver.parseCardMod], the last `!important` one wins, else the last one.
        // The offsets settle the same way, so a centering transform is checked against the offset it pairs with.
        val hostDecls = rules.filter { (_, _, v) -> v.any { it is Sel.Honored && it.host } }.flatMap { (_, decls, _) -> decls }
        val hostPlacement = (listOf("position") + OFFSETS).mapNotNull { name ->
            val all = hostDecls.filter { it.first == name }
            (all.lastOrNull { it.third } ?: all.lastOrNull())?.let { name to it.second.trim().lowercase(Locale.ROOT) }
        }.toMap()
        for ((_, important, verdicts) in rules) {
            val decls = important.map { it.first to it.second }
            // Card-mod keeps `!important`; the cascade inside Markdown and the clock doesn't honor it yet.
            if (sheet != Sheet.CARD && sheet != Sheet.STACK && important.any { it.third } && verdicts.any { it is Sel.Honored }) {
                notes += gap("!important", "card_mod `$key` `!important`")
            }
            verdicts.filterIsInstance<Sel.Unsupported>().forEach { reasons += "card_mod `$key` selector `${it.selector}`: ${it.why}" }
            verdicts.filterIsInstance<Sel.Unchecked>().forEach { notes += gap("templated selector", "card_mod `$key` `${it.selector}`") }
            val honored = verdicts.filterIsInstance<Sel.Honored>()
            if (honored.isEmpty()) continue
            val host = honored.any { it.host }
            checkDeclarations(type, key, decls, sheet, host, if (host) hostPlacement else emptyMap(), propertyReasons, notes)
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
        val stripped = stripAttributeValues(norm)
        val groups = functionalGroups(stripped)
        for (m in PSEUDO.findAll(stripped)) {
            val name = m.groupValues[2]
            val element = m.groupValues[1] == "::" || name in LEGACY_PSEUDO_ELEMENTS
            when {
                element && name in VitroSupport.BENIGN_PSEUDO_ELEMENTS -> benign = true
                element -> return Sel.Unsupported(sel, "pseudo-element `::$name` isn't drawn natively")
                // An interaction state makes the rule inactive at rest only when it rules out every way to match:
                // `:not(:hover)` matches at rest, and so does `:is(:hover, ha-card)` through its other alternative.
                name in VitroSupport.BENIGN_PSEUDO_CLASSES -> if (rulesOutRest(stripped, m.range.first, groups)) benign = true
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

    private val FUNCTIONAL = Regex(""":(not|is|where|matches|-webkit-any|has)\(""")

    /** Each functional pseudo-class in [sel] (`:not(`, `:is(`, ...) with the range of its parentheses. */
    private fun functionalGroups(sel: String): List<Pair<String, IntRange>> = FUNCTIONAL.findAll(sel).map { m ->
        val open = m.range.last
        var depth = 0
        var end = sel.length - 1
        for (k in open until sel.length) {
            when (sel[k]) {
                '(' -> depth++
                ')' -> if (--depth == 0) { end = k; break }
            }
        }
        m.groupValues[1] to (open..end)
    }.toList()

    /**
     * True when the interaction state at [at] keeps the selector from matching at rest: it isn't inside a `:not()`
     * or `:has()`, and inside each enclosing `:is()` / `:where()` every alternative has an interaction state too.
     */
    private fun rulesOutRest(sel: String, at: Int, groups: List<Pair<String, IntRange>>): Boolean {
        for ((name, range) in groups) {
            if (at !in range) continue
            if (name == "not" || name == "has") return false
            val alternatives = CssSelector.splitTopLevel(sel.substring(range.first + 1, range.last), ',')
            if (alternatives.any { alt -> PSEUDO.findAll(alt).none { it.groupValues[2] in VitroSupport.BENIGN_PSEUDO_CLASSES } }) return false
        }
        return true
    }

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
    private val PINNED_WIDTH = Regex("""^-?[\d.]+(px|dp|%|r?em)?$""")
    private val TRANSLATE = Regex("""^(translate[xy]?)\((.*)\)$""")
    private val CENTER_OR_ZERO = setOf("-50%", "0", "0px", "0%")
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
        hostPlacement: Map<String, String>,
        reasons: MutableSet<String>,
        notes: MutableSet<String>,
    ) {
        val cascadeSheet = sheet != Sheet.CARD && sheet != Sheet.STACK
        val surface = sheet == Sheet.CARD || (host && (sheet == Sheet.MARKDOWN_CARD || sheet == Sheet.CLOCK_CARD))
        val cascade = cascadeSheet
        val byName = decls.associate { it.first to it.second.trim().lowercase(Locale.ROOT) }
        // On the card itself, the position and offsets all its rules settle on; inside it, this rule's own.
        val placement = if (host) hostPlacement else byName
        val position = placement["position"]
        val pinned = position == "fixed" || position == "absolute"
        for ((name, rawValue) in decls) {
            val shown = "`$name: ${loggable(rawValue)}`"
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
            val problem = if (asCard) checkCardValue(name, value, pinned, position, placement, shown, notes)
            else if (asCascade) checkCascadeValue(sheet, name, value, position, shown, notes)
            else null
            problem?.let { reasons += "card_mod `$key` $shown $it" }
        }
    }

    /** Why a card-surface value can't be drawn, or null when it can. */
    private fun checkCardValue(
        name: String,
        value: String,
        pinned: Boolean,
        position: String?,
        placement: Map<String, String>,
        shown: String,
        notes: MutableSet<String>,
    ): String? = when (name) {
        "background", "background-image" -> backgroundProblem(value, allowGradient = true)
        "border" -> if (SPACE.split(value).any { it in BORDER_STYLE_WORDS }) "isn't drawn natively (only solid borders)" else null
        "position" -> if (value in setOf("fixed", "absolute", "static", "relative", "initial", "unset", "inherit")) null else "isn't drawn natively"
        "top", "right", "bottom", "left" -> when {
            // The overlay reads a pinned offset as px, dp, rem/em or %; `calc()` would put the card at the edge.
            pinned -> if (value == "auto" || PINNED_WIDTH.matches(value)) null else "isn't drawn natively (a pinned offset is px, rem or %)"
            else -> {
                if (position != null && position != "static") notes += gap("position: relative", shown)
                null
            }
        }
        "width" -> when {
            value in setOf("auto", "100%", "initial", "unset") -> null
            // The overlay reads a pinned width as px, dp, rem/em or %; anything else (`calc()`) would wrap the content.
            pinned -> if (PINNED_WIDTH.matches(value)) null else "isn't drawn natively (a pinned width is px, rem or %)"
            else -> "only applies to pinned (`position: fixed`) cards"
        }
        "height" -> if (value in setOf("auto", "100%", "initial", "unset", "inherit", "fit-content")) null else "isn't drawn natively (only `auto` and `100%`)"
        "transform" -> when {
            value == "none" -> null
            pinned && centersAtHalf(value, placement) -> null
            else -> "isn't drawn natively (only centering a pinned card at `left: 50%` / `top: 50%`)"
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

    /**
     * True for a transform the pinned overlay places exactly. The overlay centers an axis in the viewport and ignores
     * that axis's offset, which is what HA does only for the centering idiom: `left: 50%` with a `-50%` X translate,
     * `top: 50%` with a `-50%` Y translate. Every other translate is `0`. So `translate(-50%, 12px)` (the 12px is
     * dropped) and `top: 0` with `translate(-50%, -50%)` (HA moves the card up by half its height) don't count.
     */
    private fun centersAtHalf(value: String, placement: Map<String, String>): Boolean {
        // The overlay centers each axis once: two `-50%` shifts on one axis (`translateX(-50%) translateX(-50%)`) don't fit.
        var x = 0
        var y = 0
        for (fn in CssSelector.splitTopLevel(value, ' ').filter { it.isNotBlank() }) {
            val m = TRANSLATE.matchEntire(fn) ?: return false
            val args = CssSelector.splitTopLevel(m.groupValues[2], ',').map { it.trim() }
            val maxArgs = if (m.groupValues[1] == "translate") 2 else 1
            if (args.size !in 1..maxArgs || args.any { it !in CENTER_OR_ZERO }) return false
            when (m.groupValues[1]) {
                "translate" -> { if (args[0] == "-50%") x++; if (args.getOrNull(1) == "-50%") y++ }
                "translatex" -> if (args[0] == "-50%") x++
                "translatey" -> if (args[0] == "-50%") y++
            }
        }
        return x <= 1 && y <= 1 && (x == 0 || placement["left"] == "50%") && (y == 0 || placement["top"] == "50%")
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
        CssSelector.splitTopLevel(value, ',').size > 1 -> "isn't drawn natively (layered backgrounds)"
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
