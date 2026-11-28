/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import dev.glassworks.engine.vitro.cards.CssSelector
import dev.glassworks.engine.vitro.cards.MdNode
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
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
    /**
     * The types of the cards inside it, nested ones and the ones auto-entities builds included. The island draws them
     * too, so their scripts count ([inIsland]).
     */
    val innerTypes: Set<String> = emptySet(),
) {
    /**
     * A Bubble card Vitro draws natively, which only matches Bubble where no Bubble Card module restyles it: Bubble
     * applies its `default` module and every global one to each card, from Home Assistant ([IslandScripts.bubbleModules]).
     */
    private val bubbleModulesMatter: Boolean get() = route == CardRoute.NATIVE && type == BUBBLE

    /** Native, but Bubble's modules may restyle it and the island can draw it: the island draws it ([inIsland]). */
    private fun toBubbleIsland(scripts: IslandScripts): Boolean =
        bubbleModulesMatter && scripts.bubbleModules && scripts.provides(type)

    /** Cards Vitro draws natively (this one or one inside it) whose script no Lovelace resource provides. */
    private fun missingScripts(scripts: IslandScripts): List<String> =
        (listOf(type) + innerTypes).filter { it in VitroSupport.HACS_CARD_TYPES && !scripts.provides(it) }.distinct()

    val isNative: Boolean get() = route == CardRoute.NATIVE

    /**
     * Whether the card is built in an island, which only helps when the island can draw it: it runs the card's own
     * script, which it loads from the dashboard's Lovelace resources ([scripts]). A card Vitro also draws natively
     * goes there only when a resource provides its script, and, for a card_mod-only fallback, card-mod too.
     * Otherwise it stays native. A custom card with no native renderer goes there regardless: it's its only chance.
     */
    fun inIsland(scripts: IslandScripts = IslandScripts.ANY): Boolean = toBubbleIsland(scripts) || route == CardRoute.ISLAND &&
        (type !in VitroSupport.HACS_CARD_TYPES || (missingScripts(scripts).isEmpty() && (!cardModOnly || scripts.cardMod)))

    /**
     * This verdict as the view builds it with [scripts]: native, with the reasons as notes, when [inIsland] says the
     * card stays; for a card with no native renderer, a note when no registered script seems to define it.
     */
    fun effective(scripts: IslandScripts = IslandScripts.ANY): CardVerdict = when {
        toBubbleIsland(scripts) -> copy(route = CardRoute.ISLAND, reasons = listOf(BUBBLE_MODULES_REASON))
        route != CardRoute.ISLAND -> this
        inIsland(scripts) ->
            if (type !in VitroSupport.HACS_CARD_TYPES && type.startsWith("custom:") && !scripts.mentions(type)) {
                copy(notes = notes + "no Lovelace resource seems to provide `$type`, so the island may not draw it")
            } else {
                this
            }
        else -> {
            val missing = missingScripts(scripts)
            val why = if (missing.isNotEmpty()) {
                "the island has no script for " + missing.joinToString(", ") { "`$it`" } + " (no Lovelace resource provides it)"
            } else {
                "the island can't load card-mod (it isn't a Lovelace resource)"
            }
            copy(route = CardRoute.NATIVE, reasons = emptyList(), notes = notes + reasons.map { "would fall back for $it, but $why" })
        }
    }
}

private const val BUBBLE = "custom:bubble-card"

/** Why a Bubble card Vitro could draw goes to the island on a Home Assistant with Bubble Card modules. */
const val BUBBLE_MODULES_REASON =
    "Bubble Card modules are set up in Home Assistant, and Bubble applies its default and global ones to every Bubble card"

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
        }
        // The island runs a custom card's own JavaScript, so it draws that card as HA does. It has no HA frontend for
        // built-in cards, so a built-in card there would be a plain summary: further from HA than Vitro's native
        // card. Until the island can run HA's card code, a built-in card Vitro draws stays native for its card_mod and
        // the checker says what it would have fallen back for. So does a custom container Vitro draws (stack-in-card,
        // layout-card) with a built-in card anywhere inside it: in the island, that child would be a summary too.
        // Their options and content are different: what they don't draw as HA does sends their view to HA's frontend
        // ([WebFallback]), which is why [checkOptions] leaves those out here.
        if (reasons.isNotEmpty()) {
            val why = when {
                type in VitroSupport.CORE_CARD_TYPES -> "the island can't draw built-in cards yet"
                type in VitroSupport.HACS_CARD_TYPES && needsHaFrontend(card) ->
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
            innerTypes = innerTypes(card),
        )
    }

    /**
     * True when [check] can only route [card] native, whatever else its config holds: a built-in card, or a custom card
     * Vitro draws that needs HA's frontend for a built-in card inside it (the island would draw those as summaries).
     * [check] keeps such a card native and turns any reasons into notes, so routing ([CardConfig.inIsland]) can skip
     * the full check for it: on a slow panel that check is most of what a view's card_mod costs to build.
     */
    fun staysNative(card: CardConfig): Boolean =
        card.type in VitroSupport.CORE_CARD_TYPES || (card.type in VitroSupport.HACS_CARD_TYPES && needsHaFrontend(card))

    /** The types of every card [card] produces, at any depth ([CardRouter.producedCards]). */
    private fun innerTypes(card: CardConfig): Set<String> {
        val out = LinkedHashSet<String>()
        fun walk(c: CardConfig) {
            CardRouter.producedCards(c).forEach { (_, child) -> out += child.type; walk(child) }
        }
        walk(card)
        return out
    }

    /**
     * True when [card] is a built-in card or produces one at any depth ([CardRouter.producedCards]): the island can
     * only draw those as summaries. `stack-in-card` always does: version 0.2.0 wraps its cards in a built-in
     * `vertical-stack` or `horizontal-stack`.
     */
    private fun needsHaFrontend(card: CardConfig): Boolean =
        !card.type.startsWith("custom:") || card.type == "custom:stack-in-card" ||
            CardRouter.producedCards(card).any { needsHaFrontend(it.value) }

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
        // The strict rule ([NativeOptions]): an option or value the native card doesn't draw as HA does. A card the
        // island can't draw (a built-in card, or a custom one holding built-in cards) goes to HA's frontend for it
        // instead ([WebFallback]), so its reasons aren't the island's.
        if (!card.nativeOnly) reasons += card.unsupportedOptions
        when (card.type) {
            // Native, but its page is a web page: it takes a browser session, as HA's iframe does.
            "iframe" -> notes += CompatChecker.IFRAME_ISLAND
            "custom:button-card" -> ButtonCardCompat.check(raw, reasons, notes)
            "custom:swipe-card" -> {
                val params = raw.optJSONObject("parameters") ?: JSONObject()
                for (k in params.keys()) {
                    if (k !in VitroSupport.SWIPE_CARD_PARAMETERS) reasons += "swipe-card has a Swiper parameter Vitro doesn't run natively" + (if (k in KNOWN_SWIPER_NAMES) " (`$k`)" else "")
                }
                params.opt("pagination")?.let { p ->
                    val type = (p as? JSONObject)?.optString("type", "bullets")?.ifEmpty { "bullets" } ?: "bullets"
                    if (type !in setOf("bullets", "fraction")) reasons += "swipe-card `pagination.type` isn't one Vitro draws natively"
                    if ((p as? JSONObject)?.optBoolean("dynamicBullets") == true) reasons += "swipe-card `dynamicBullets` isn't drawn natively"
                }
                params.opt("slidesPerView")?.let { v ->
                    if (v != "auto" && (v as? Number)?.toDouble()?.takeIf { it > 0 } == null) reasons += "swipe-card `slidesPerView` isn't a value Vitro draws natively"
                }
                if (raw.has("card_width")) reasons += "swipe-card `card_width` isn't drawn natively"
            }
            "custom:weather-card" -> {
                // The forecast comes from the entity's `forecast` attribute, which HA no longer sets: Vitro draws what HA draws.
                if (raw.optString("entity").isEmpty()) reasons += "weather-card needs an `entity` (HA shows an error)"
            }
        }
    }

    /** Swiper's own parameter names, which a reason may name; any other key is the dashboard's text and isn't shown. */
    private val KNOWN_SWIPER_NAMES = setOf(
        "effect", "navigation", "scrollbar", "direction", "freeMode", "autoHeight", "watchOverflow", "slidesPerGroup",
        "zoom", "parallax", "thumbs", "virtual", "breakpoints", "cssMode", "grid", "mousewheel", "keyboard",
    )

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
                        reasons += "card_mod path `${redactAttributeValues(k)}` nests further into the card's shadow DOM, which Vitro doesn't model"
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
                reasons += "card_mod path `${redactAttributeValues(key)}` styles the card's inner DOM, which Vitro doesn't model"
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
    private val AT_RULE = Regex("""@([a-zA-Z-]+)""")

    /** At-rules a card sheet may hold without changing the look on its own. */
    private val HARMLESS_AT_RULES = setOf("keyframes", "-webkit-keyframes", "charset")

    /**
     * [checkSheetUncached], once per (card type, key, text, sheet): every card is checked when a view builds, and
     * dashboards often repeat a card_mod sheet across cards and across views. The check
     * only adds strings to its three sets, so the adds are recorded in order and replayed into the caller's sets,
     * which leaves them exactly as a fresh check would (the same strings, in the same order, the same set when two are
     * one). At most [SHEET_CACHE] results are kept.
     */
    private fun checkSheet(
        type: String,
        key: String,
        rawCss: String,
        sheet: Sheet,
        reasons: MutableSet<String>,
        propertyReasons: MutableSet<String>,
        notes: MutableSet<String>,
    ) {
        val cacheKey = listOf(type, key, rawCss, sheet)
        val adds = synchronized(sheetCache) { sheetCache[cacheKey] } ?: run {
            val log = ArrayList<Pair<Int, String>>()
            checkSheetUncached(type, key, rawCss, sheet, AddLog(0, log), AddLog(1, log), AddLog(2, log))
            synchronized(sheetCache) { sheetCache[cacheKey] = log }
            log
        }
        for ((target, value) in adds) {
            when (target) {
                0 -> reasons += value
                1 -> propertyReasons += value
                else -> notes += value
            }
        }
    }

    /** A set that only records what is added to it, tagged with which of the check's sets it stands for. */
    private class AddLog(private val tag: Int, private val log: MutableList<Pair<Int, String>>) : java.util.AbstractSet<String>() {
        override fun add(element: String): Boolean {
            log += tag to element
            return true
        }
        override val size: Int get() = throw UnsupportedOperationException("the check only adds")
        override fun iterator(): MutableIterator<String> = throw UnsupportedOperationException("the check only adds")
    }

    private const val SHEET_CACHE = 256
    private val sheetCache = object : LinkedHashMap<List<Any>, List<Pair<Int, String>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any>, List<Pair<Int, String>>>?) = size > SHEET_CACHE
    }

    private fun checkSheetUncached(
        type: String,
        key: String,
        rawCss: String,
        sheet: Sheet,
        reasons: MutableSet<String>,
        propertyReasons: MutableSet<String>,
        notes: MutableSet<String>,
    ) {
        // Template blocks go; expressions become a placeholder so the shape around them still parses.
        val css = JINJA_EXPR.replace(JINJA_BLOCK.replace(ThemeResolver.stripCssComments(rawCss), " "), TPL)
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
        val hostPlacement = (listOf("position", "width", "transform") + OFFSETS).mapNotNull { name ->
            val all = hostDecls.filter { it.first == name }
            (all.lastOrNull { it.third } ?: all.lastOrNull())?.let { name to it.second.trim().lowercase(Locale.ROOT) }
        }.toMap()
        // A pinned card's geometry as a whole: only the forms the overlay places exactly ([pinnedGeometryProblem]).
        val surfaceSheet = sheet == Sheet.CARD || sheet == Sheet.MARKDOWN_CARD || sheet == Sheet.CLOCK_CARD
        if (surfaceSheet && hostPlacement["position"].let { it == "fixed" || it == "absolute" }) {
            pinnedGeometryProblem(hostPlacement)?.let { propertyReasons += "card_mod `$key` pinned card: $it" }
        }
        for ((_, important, verdicts) in rules) {
            val decls = important.map { it.first to it.second }
            // Card-mod keeps `!important`; the cascade inside Markdown and the clock doesn't honor it yet.
            if (sheet != Sheet.CARD && sheet != Sheet.STACK && important.any { it.third } && verdicts.any { it is Sel.Honored }) {
                notes += gap("!important", "card_mod `$key` `!important`")
            }
            // Attribute values left out of what is reported (and logged): one can hold a signed URL or a name.
            verdicts.filterIsInstance<Sel.Unsupported>().forEach { reasons += "card_mod `${redactAttributeValues(key)}` selector `${redactAttributeValues(it.selector)}`: ${it.why}" }
            verdicts.filterIsInstance<Sel.Unchecked>().forEach { notes += gap("templated selector", "card_mod `${redactAttributeValues(key)}` `${redactAttributeValues(it.selector)}`") }
            verdicts.filterIsInstance<Sel.Noted>().forEach { notes += gap(it.gap, "card_mod `${redactAttributeValues(key)}` `${redactAttributeValues(it.selector)}`") }
            val honored = verdicts.filterIsInstance<Sel.Honored>()
            if (honored.isEmpty()) continue
            val host = honored.any { it.host }
            checkDeclarations(type, key, decls, sheet, host, if (host) hostPlacement else emptyMap(), propertyReasons, notes)
        }
    }

    /**
     * The names of at-rules at the top level of [css] (`@media`, `@keyframes`, ...). An `@` counts only where a rule
     * can start (the start of the sheet, or after a `}` or `;`, whitespace aside) and outside quoted strings and
     * parentheses, so `url(/local/a@home.png)` or `[data-label="@media"]` isn't one. The site's checker
     * (`topLevelAtRules` in `site/src/lib/vitroCompat.ts`) and the audit script (`top_level_at_rules`) scan the same way.
     */
    private fun topLevelAtRules(css: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var parens = 0
        var quote = '\u0000'
        var ruleStart = true
        var i = 0
        while (i < css.length) {
            val c = css[i]
            when {
                c == '\\' -> i++
                quote != '\u0000' -> if (c == quote || c == '\n') quote = '\u0000'
                c == '"' || c == '\'' -> quote = c
                c == '(' -> parens++
                c == ')' -> if (parens > 0) parens--
                c == '{' -> depth++
                c == '}' -> if (depth > 0) depth--
                c == '@' -> if (depth == 0 && parens == 0 && ruleStart) {
                    AT_RULE.matchAt(css, i)?.let { out += it.groupValues[1].lowercase(Locale.ROOT) }
                }
            }
            if (!c.isWhitespace()) ruleStart = quote == '\u0000' && (c == '{' || c == '}' || c == ';')
            i++
        }
        return out
    }

    private sealed class Sel {
        data class Honored(val host: Boolean) : Sel()
        object Benign : Sel()
        data class Unchecked(val selector: String) : Sel()

        /** A rule that applies at rest but that Vitro doesn't apply: a known gap ([gap]), not a fallback. */
        data class Noted(val selector: String, val gap: String) : Sel()
        data class Unsupported(val selector: String, val why: String) : Sel()
    }

    private val PSEUDO = Regex("""(::?)([a-zA-Z-]+)""")
    private val LEGACY_PSEUDO_ELEMENTS = setOf("before", "after", "first-line", "first-letter")

    private fun classifySelector(sel: String, sheet: Sheet): Sel {
        if (TPL in sel) return Sel.Unchecked(sel.replace(TPL, "{{ … }}"))
        val norm = sel.lowercase(Locale.ROOT).replace(SELECTOR_SPACES, " ")
        if (sheet == Sheet.STACK) {
            // A stack has no ha-card of its own: `ha-card …` in its sheet matches nothing in HA either.
            if (norm == ":host") return Sel.Honored(host = true)
            if (norm.startsWith("ha-card") || norm.startsWith(":host ha-card") || norm.startsWith(":host > ha-card")) return Sel.Benign
        } else if (ThemeResolver.isHostSelector(norm)) {
            return Sel.Honored(host = true)
        }
        var benign = false
        var restLink = false
        val stripped = stripAttributeValues(norm)
        val groups = functionalGroups(stripped)
        val interactionMemo = HashMap<String, Boolean>()
        for (m in PSEUDO.findAll(stripped)) {
            val name = m.groupValues[2]
            val element = m.groupValues[1] == "::" || name in LEGACY_PSEUDO_ELEMENTS
            when {
                element && name in VitroSupport.BENIGN_PSEUDO_ELEMENTS -> benign = true
                element -> return Sel.Unsupported(sel, "pseudo-element `::$name` isn't drawn natively")
                // An unvisited link matches with no interaction at all: the rule applies at rest.
                name == "link" -> restLink = true
                // An interaction state makes the rule inactive at rest only when it rules out every way to match:
                // `:not(:hover)` matches at rest, and so does `:is(:hover, ha-card)` through its other alternative.
                name in VitroSupport.BENIGN_PSEUDO_CLASSES -> if (rulesOutRest(stripped, m.range.first, groups, interactionMemo)) benign = true
                name in VitroSupport.PSEUDO_CLASSES -> Unit
                name == "host" -> return Sel.Unsupported(sel, "`:host` with a selector isn't modeled")
                else -> return Sel.Unsupported(sel, "`:$name` isn't evaluated natively")
            }
        }
        if (benign) return Sel.Benign
        if (restLink && sheet != Sheet.CARD && sheet != Sheet.STACK) return Sel.Noted(sel, ":link")
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

    private fun stripAttributeValues(sel: String): String = sel.replace(ATTRIBUTE_VALUE, "[]")

    /** [sel] with each attribute selector's value replaced by `…`, its name and operator kept (`[src^=…]`). */
    internal fun redactAttributeValues(sel: String): String = sel.replace(ATTRIBUTE_VALUE) { m ->
        val inner = m.value.substring(1, m.value.length - 1)
        val op = ATTRIBUTE_OPERATOR.find(inner) ?: return@replace m.value
        "[" + inner.substring(0, op.range.last + 1) + "…]"
    }

    private val ATTRIBUTE_OPERATOR = Regex("""[~|^$*]?=""")

    // Compiled once: every card's selectors pass through here when a view builds.
    private val SELECTOR_SPACES = Regex("""\s+""")
    // Quote- and escape-aware: a `]` inside a quoted value (`[src^="/x?a=]&token=…"]`), an escaped quote inside one
    // (`"/x?q=\"]&token=…"`) or an escaped `]` doesn't end the attribute selector.
    // An escape takes any next character, a line break included (`.` wouldn't).
    private val ATTRIBUTE_VALUE = Regex("""\[(?:"(?:\\[\s\S]|[^"\\])*"|'(?:\\[\s\S]|[^'\\])*'|\\[\s\S]|[^\]"'\\])*\]""")

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
    private fun rulesOutRest(sel: String, at: Int, groups: List<Pair<String, IntRange>>, memo: HashMap<String, Boolean>): Boolean {
        for ((name, range) in groups) {
            if (at !in range) continue
            if (name == "not" || name == "has") return false
            val alternatives = CssSelector.splitTopLevel(sel.substring(range.first + 1, range.last), ',')
            // Each alternative must *require* an interaction state: `:not(:hover)` contains one and matches at rest.
            if (alternatives.any { alt -> !requiresInteraction(alt.trim(), memo) }) return false
        }
        return true
    }

    /** True when [sel] can only match in an interaction state: one of its states rules out the card at rest. */
    /**
     * Each distinct alternative is decided once per selector ([memo]): nested `:is()` groups share their inner
     * alternatives, and deciding them again for every enclosing group grows exponentially with the nesting.
     */
    private fun requiresInteraction(sel: String, memo: HashMap<String, Boolean>): Boolean = memo[sel] ?: run {
        val groups = functionalGroups(sel)
        PSEUDO.findAll(sel).any { m ->
            m.groupValues[1] == ":" && m.groupValues[2] in VitroSupport.BENIGN_PSEUDO_CLASSES && rulesOutRest(sel, m.range.first, groups, memo)
        }.also { memo[sel] = it }
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
    /** What places a pinned card: settled across the card's own rules ([hostPlacement]). */
    private val PLACEMENT: Set<String> by lazy { setOf("position", "width", "transform") + OFFSETS }

    private val PINNED_WIDTH = Regex("""^-?[\d.]+(px|dp|%|r?em)?$""")
    private val TRANSLATE = Regex("""^(translate[xy]?)\((.*)\)$""")
    private val CENTER_OR_ZERO = setOf("-50%", "0", "0px", "0%")
    private val BORDER_STYLE_WORDS = setOf("dashed", "dotted", "double", "groove", "ridge", "inset", "outset")
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
            // How the declaration reads in a reason or note: built only when one is written.
            val shown = { "`$name: ${loggable(rawValue)}`" }
            val value = rawValue.trim().lowercase(Locale.ROOT)
            // On the card itself only the placement that wins the cascade counts (`width: calc(…)` then
            // `width: 300px !important` is placed at 300px); a winning calc() is still checked below.
            if (host && name in PLACEMENT && hostPlacement[name] != null && value != hostPlacement[name]) continue
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
                    name in VitroSupport.KNOWN_GAP_PROPERTIES -> notes += gap(name, shown())
                    // Offsets without a position do nothing in the browser either.
                    name in OFFSETS && (position == null || position == "static") -> Unit
                    else -> reasons += "card_mod `$key` ${shown()} isn't supported here"
                }
                continue
            }
            if (!checkable) continue
            val problem = if (asCard) checkCardValue(name, value, pinned, position, shown, notes)
            else if (asCascade) checkCascadeValue(sheet, name, value, position, shown, notes)
            else null
            problem?.let { reasons += "card_mod `$key` ${shown()} $it" }
        }
    }

    /** Why a card-surface value can't be drawn, or null when it can. */
    private fun checkCardValue(
        name: String,
        value: String,
        pinned: Boolean,
        position: String?,
        shown: () -> String,
        notes: MutableSet<String>,
    ): String? = when (name) {
        "background", "background-image" -> backgroundProblem(value, allowGradient = true)
        "border" -> if (SPACE.split(value).any { it in BORDER_STYLE_WORDS }) "isn't drawn natively (only solid borders)" else null
        "position" -> if (value in setOf("fixed", "absolute", "static", "relative", "initial", "unset", "inherit")) null else "isn't drawn natively"
        "top", "right", "bottom", "left" -> when {
            // The overlay reads a pinned offset as px, dp, rem/em or %; `calc()` would put the card at the edge.
            pinned -> if (value in UNSET || PINNED_WIDTH.matches(value)) null else "isn't drawn natively (a pinned offset is px, rem or %)"
            else -> {
                if (position != null && position != "static") notes += gap("position: relative", shown())
                null
            }
        }
        "width" -> when {
            value in setOf("auto", "100%", "initial", "unset") -> null
            // The overlay reads a pinned width as px, dp, rem/em or %; anything else (`calc()`) would wrap the content.
            pinned -> when {
                !PINNED_WIDTH.matches(value) -> "isn't drawn natively (a pinned width is px, rem or %)"
                // The overlay sizes a width of zero or less to the card's content, not to nothing.
                (value.trimEnd { it.isLetter() || it == '%' }.toDoubleOrNull() ?: 1.0) <= 0.0 -> "isn't drawn natively (a pinned width of zero)"
                else -> null
            }
            else -> "only applies to pinned (`position: fixed`) cards"
        }
        "height" -> if (value in setOf("auto", "100%", "initial", "unset", "inherit", "fit-content")) null else "isn't drawn natively (only `auto` and `100%`)"
        "transform" -> when {
            value == "none" -> null
            pinned && centeredAxes(value) != null -> null
            else -> "isn't drawn natively (only centering a pinned card at `left: 50%` / `top: 50%`)"
        }
        "box-shadow" -> when {
            value == "none" -> null
            "inset" in value -> "isn't drawn natively (inset shadow)"
            else -> {
                if (GLOW.containsMatchIn(value)) notes += gap("box-shadow: glow", shown())
                null
            }
        }
        else -> null
    }

    /**
     * Which axes a pinned card's transform centers (`x` to `y`), or null for a transform the overlay can't apply:
     * every function is a translate whose arguments are `-50%` or `0`, with at most one `-50%` shift per axis
     * (`translate(-50%, 12px)` would drop the 12px, `translateX(-50%) translateX(-50%)` is a whole width).
     */
    private fun centeredAxes(value: String): Pair<Boolean, Boolean>? {
        if (value == "none") return false to false
        var x = 0
        var y = 0
        for (fn in CssSelector.splitTopLevel(value, ' ').filter { it.isNotBlank() }) {
            val m = TRANSLATE.matchEntire(fn) ?: return null
            val args = CssSelector.splitTopLevel(m.groupValues[2], ',').map { it.trim() }
            val maxArgs = if (m.groupValues[1] == "translate") 2 else 1
            if (args.size !in 1..maxArgs || args.any { it !in CENTER_OR_ZERO }) return null
            when (m.groupValues[1]) {
                "translate" -> { if (args[0] == "-50%") x++; if (args.getOrNull(1) == "-50%") y++ }
                "translatex" -> if (args[0] == "-50%") x++
                "translatey" -> if (args[0] == "-50%") y++
            }
        }
        return if (x > 1 || y > 1) null else (x == 1) to (y == 1)
    }

    /**
     * Why the overlay can't place a pinned card the way HA does, or null when it can. The overlay draws exactly:
     * at most one horizontal inset (two only with a `width`, where `left` wins as in CSS) and one vertical inset,
     * and the centering idiom, `left: 50%` / `top: 50%` with a `-50%` translate on that axis and no opposite inset.
     * `auto`, `unset` and `initial` count as no inset. Each value's form is checked on its own declaration.
     */
    private fun pinnedGeometryProblem(placement: Map<String, String>): String? {
        fun inset(name: String) = placement[name]?.takeUnless { it in UNSET }
        val left = inset("left")
        val right = inset("right")
        val top = inset("top")
        val bottom = inset("bottom")
        val width = placement["width"]?.takeUnless { it in UNSET }
        val (cx, cy) = placement["transform"]?.let(::centeredAxes) ?: (false to false)
        return when {
            cx && (left != "50%" || right != null) || cy && (top != "50%" || bottom != null) ->
                "isn't drawn natively (only centering a pinned card at `left: 50%` / `top: 50%`)"
            left != null && right != null && width == null ->
                "`left` and `right` without a `width` stretch it between them, which the overlay doesn't draw"
            top != null && bottom != null -> "`top` and `bottom` stretch it between them, which the overlay doesn't draw"
            else -> null
        }
    }

    private val UNSET = setOf("auto", "unset", "initial")

    /** Why a cascade value can't be drawn, or null when it can. */
    private fun checkCascadeValue(sheet: Sheet, name: String, value: String, position: String?, shown: () -> String, notes: MutableSet<String>): String? = when (name) {
        "display" -> if (sheet == Sheet.CLOCK_CARD || sheet == Sheet.CLOCK_DIGITAL || value in VitroSupport.MARKDOWN_DISPLAY) null
        else "isn't laid out natively"
        "background", "background-color" -> backgroundProblem(value, allowGradient = false)
        "white-space" -> {
            if (value == "nowrap") notes += gap("white-space: nowrap", shown())
            null
        }
        "position" -> when (value) {
            "static", "initial", "unset" -> null
            "relative" -> {
                notes += gap("position: relative", shown())
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
        "url(" in value || "image-set(" in value -> "isn't drawn natively (background images)"
        "radial-gradient" in value || "conic-gradient" in value || "repeating-" in value -> "isn't drawn natively (only linear gradients)"
        !allowGradient && "gradient" in value -> "isn't drawn natively (no gradients inside Markdown)"
        CssSelector.splitTopLevel(value, ',').size > 1 -> "isn't drawn natively (layered backgrounds)"
        else -> null
    }

    // -------------------------------------------------------------------------------------------------------------
    // Markdown content
    // -------------------------------------------------------------------------------------------------------------

    private val OPEN_TAG = Regex("""<([a-zA-Z][a-zA-Z0-9-]*)""")

    /**
     * Why a Markdown card's [content] isn't drawn as HA draws it: the tags HA's sanitizer keeps that Vitro doesn't
     * draw. The card is built in, so these send its view to HA's frontend ([WebFallback]).
     */
    internal fun markdownReasons(content: String): List<String> {
        val reasons = LinkedHashSet<String>()
        for (m in OPEN_TAG.findAll(content)) {
            val tag = m.groupValues[1].lowercase(Locale.ROOT)
            VitroSupport.UNDRAWN_MARKDOWN_TAGS[tag]?.let { reasons += "Markdown `<$tag>` ($it) isn't drawn natively" }
        }
        return reasons.toList()
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A known-gap note: what it's about, then why it stays native. */
    private fun gap(key: String, subject: String): String = "$subject: known gap, " + (VitroSupport.KNOWN_GAP_PROPERTIES[key] ?: key)

    private fun objects(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}
