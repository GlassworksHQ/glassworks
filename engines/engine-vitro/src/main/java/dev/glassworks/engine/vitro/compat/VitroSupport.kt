/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

/**
 * The set Vitro draws natively, in one place. A custom card with anything outside it goes to a web island on
 * purpose ([CardCompat]), so it looks the way Home Assistant draws it and only that card pays for a web engine.
 *
 * Which options and values each card draws exactly as HA does is declared in `native-options.json` and checked by
 * [NativeOptions] (the strict compat rule): a card with anything else falls back. Built-in cards are the exception to
 * the island: it runs a custom card's own JavaScript but has no HA frontend, so it would draw a built-in card (a `tile`,
 * say) as a plain summary. A built-in card, or a custom container Vitro draws with a built-in card inside it, sends its
 * view to HA's frontend for such an option instead ([WebFallback]). Its card_mod is different: a built-in card stays
 * native even when its card_mod is outside this set, and [CardCompat.check] records what it would have fallen back for
 * as a note.
 *
 * Three lists decide a card_mod sheet's route:
 * - **Supported**: drawn natively and faithfully.
 * - **Benign** ([BENIGN_PROPERTIES], [BENIGN_PSEUDO_CLASSES]): unsupported, but it doesn't change how the card looks
 *   on a touch panel, so it never forces an island. Each entry says why.
 * - **Known gaps** ([KNOWN_GAP_PROPERTIES], [CARD_MOD_NOT_APPLIED]): in scope for native parity and drawn natively
 *   today, but not exact yet. They never force an island either (an island wouldn't do better, or the parity work
 *   is open); the checker reports them as notes so they stay visible.
 *
 * What HA itself drops (declarations outside a rule, tags outside its sanitizer) is dropped by Vitro too: that *is*
 * parity, so it never counts against a card.
 *
 * `!important` is kept by card-mod and wins in HA. On the card itself Vitro honors it among the card's own card_mod
 * declarations. In Markdown and clock sheets Vitro strips the flag and keeps the value at normal precedence, so where
 * an `!important` card-mod rule beats HA's own styles in HA, HA's rule can win in Vitro. The checker notes it.
 *
 * When a renderer learns something new, add it here or to `native-options.json`, and the README's "What Vitro draws
 * natively" table with it.
 */
object VitroSupport {
    // ---------------------------------------------------------------------------------------------------------
    // Card types
    // ---------------------------------------------------------------------------------------------------------

    /** Built-in Home Assistant cards with a native renderer (Tier 1). */
    val CORE_CARD_TYPES: Set<String> = setOf(
        "tile", "entities", "glance", "button", "heading", "clock", "sensor", "history-graph", "gauge", "thermostat",
        "weather-forecast", "markdown", "picture", "picture-entity", "picture-glance", "media-control", "grid",
        "horizontal-stack", "vertical-stack", "conditional", "entity-filter", "entity", "light", "alarm-panel",
        "todo-list", "statistics-graph", "area", "picture-elements", "humidifier", "calendar", "logbook",
        // HA's legacy name for `button` (`hui-entity-button-card` extends `hui-button-card`, entity required).
        "entity-button",
        // HA's other legacy names: `shopping-list` is the to-do list card on the shopping list, and `section` (an
        // always-loaded type with no element) HA's error card.
        "shopping-list", "section",
        "statistic", "date", "plant-status", "toggle-group", "shortcut", "alert",
        // Its page in a browser island of its own, the way HA's iframe shows it.
        "iframe",
    )

    /** HACS cards with a native fast path (Tier 2). */
    val HACS_CARD_TYPES: Set<String> = setOf(
        "custom:mushroom-entity-card", "custom:mushroom-light-card", "custom:mushroom-title-card",
        "custom:mushroom-chips-card", "custom:mushroom-template-card", "custom:mushroom-climate-card",
        "custom:mushroom-fan-card", "custom:mushroom-cover-card", "custom:mushroom-person-card", "custom:bubble-card",
        "custom:mini-graph-card", "custom:clock-weather-card", "custom:modern-circular-gauge", "custom:bar-card",
        "custom:mushroom-media-player-card", "custom:mushroom-lock-card", "custom:mushroom-alarm-control-panel-card",
        "custom:mushroom-vacuum-card", "custom:mushroom-humidifier-card", "custom:mushroom-number-card",
        "custom:mushroom-select-card", "custom:mini-media-player", "custom:auto-entities", "custom:stack-in-card",
        "custom:vertical-stack-in-card", "custom:layout-card", "custom:button-card", "custom:mushroom-empty-card",
        "custom:swipe-card", "custom:weather-card",
    )

    /**
     * The script files (Lovelace resource file names) that define each card in [HACS_CARD_TYPES]. An island can draw
     * one of these cards only when a resource with one of its files is registered ([IslandScripts.provides]).
     */
    val CARD_SCRIPTS: Map<String, Set<String>> = HACS_CARD_TYPES.associateWith { type ->
        when {
            type.startsWith("custom:mushroom-") -> setOf("mushroom.js")
            type == "custom:mini-graph-card" -> setOf("mini-graph-card-bundle.js", "mini-graph-card.js")
            type == "custom:mini-media-player" -> setOf("mini-media-player-bundle.js", "mini-media-player.js")
            type == "custom:weather-card" -> setOf("weather-card.js")
            else -> setOf(type.removePrefix("custom:") + ".js")
        }
    }

    /** HA's stacks: no `ha-card` of their own, so card-mod's `ha-card { }` in them matches nothing in HA either. */
    val STACK_TYPES: Set<String> = setOf("vertical-stack", "horizontal-stack", "grid", "conditional")

    // ---------------------------------------------------------------------------------------------------------
    // Options per card type that change what is drawn
    // ---------------------------------------------------------------------------------------------------------

    /**
     * `entities` rows with a `type:` Vitro parses as HA's special rows (`EntityRowConfig.parse`). Which of them, and
     * which plain entity rows, Vitro draws exactly as HA does is `native-options.json`'s `entityRow` ([NativeOptions]).
     */
    val ENTITIES_ROW_TYPES: Set<String> = setOf(
        "divider", "section", "button", "call-service", "perform-action", "text", "weblink", "buttons", "conditional",
    )

    // ---------------------------------------------------------------------------------------------------------
    // custom:button-card (v7.0.1). Its JavaScript (`[[[ ]]]`) always goes to an island; this is the rest.
    // ---------------------------------------------------------------------------------------------------------

    /** button-card `layout` values: each is one of its CSS grids (`#container.<layout>`). */
    val BUTTON_CARD_LAYOUTS: Set<String> = setOf(
        "vertical", "icon_name_state", "name_state", "icon_name", "icon_state", "icon_name_state2nd",
        "icon_state_name2nd", "icon_label",
    )

    /** Actions a native button-card runs (`tap_action`, `hold_action`, `double_tap_action`). */
    val BUTTON_CARD_ACTIONS: Set<String> = setOf(
        "none", "toggle", "more-info", "navigate", "url", "call-service", "perform-action",
    )

    /** button-card options Vitro doesn't draw: setting one sends the card to an island. */
    val BUTTON_CARD_UNSUPPORTED_OPTIONS: Map<String, String> = mapOf(
        "show_live_stream" to "a camera's live stream",
        "spinner" to "the spinner overlay",
        "protect" to "PIN- or password-protected actions",
        "extra_styles" to "its own stylesheet (`extra_styles`)",
        "press_action" to "momentary press actions",
        "release_action" to "momentary release actions",
        "icon_tap_action" to "actions on the icon",
        "icon_hold_action" to "actions on the icon",
        "icon_double_tap_action" to "actions on the icon",
        "icon_press_action" to "actions on the icon",
        "icon_release_action" to "actions on the icon",
    )

    /** Fields of an action that a native button-card doesn't run: one of them sends the card to an island. */
    val BUTTON_CARD_UNSUPPORTED_ACTION_FIELDS: Map<String, String> = mapOf(
        "repeat" to "a hold that repeats", "repeat_limit" to "a hold that repeats", "sound" to "a sound",
        "protect" to "a PIN or password",
    )

    /** button-card options that don't change the look without JavaScript, and why Vitro ignores them. */
    val BUTTON_CARD_IGNORED_OPTIONS: Map<String, String> = mapOf(
        "tooltip" to "a hover tooltip; a touch panel has no pointer",
        "update_timer" to "re-renders on a timer, which only JavaScript templates need",
        "group_expand" to "follows a group's members, which only JavaScript templates read",
        "triggers_update" to "version 7 no longer reads it",
        "variables" to "only JavaScript templates read them",
        "disable_kbd" to "keyboard actions; a wall panel has no keyboard",
        "card_size" to "masonry's size estimate",
    )

    /** button-card `styles:` parts. `custom_fields` holds one list per field. */
    val BUTTON_CARD_STYLE_PARTS: Set<String> = setOf(
        "card", "icon", "name", "state", "label", "grid", "img_cell", "entity_picture", "lock", "custom_fields",
        "tooltip", "spinner",
    )

    /** Text styles every text part (name, state, label, a custom field) draws. */
    private val BUTTON_CARD_TEXT: Set<String> = setOf(
        "color", "opacity", "font-size", "font-weight", "font-family", "font-style", "text-transform",
        "letter-spacing", "line-height", "text-align", "white-space", "text-overflow", "overflow",
        "justify-self", "align-self", "display",
        "padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
        "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
        "background-color", "border-radius", "width", "max-width", "min-width",
    )

    /** The properties Vitro draws in each button-card style part (values are checked in `ButtonCardCompat`). */
    val BUTTON_CARD_STYLE_PROPERTIES: Map<String, Set<String>> = mapOf(
        "card" to setOf(
            "background", "background-color", "background-image", "border", "border-width", "border-style",
            "border-color", "border-radius", "box-shadow", "opacity", "color", "font-size", "font-weight",
            "font-family", "font-style", "text-transform", "letter-spacing", "line-height", "height", "min-height",
            "padding", "padding-top", "padding-right", "padding-bottom", "padding-left", "transform", "animation",
            "display", "overflow", "text-align", "box-sizing",
        ),
        "name" to BUTTON_CARD_TEXT,
        "state" to BUTTON_CARD_TEXT,
        "label" to BUTTON_CARD_TEXT,
        "custom_field" to BUTTON_CARD_TEXT + setOf("position", "top", "right", "bottom", "left", "grid-area"),
        "icon" to setOf("color", "width", "height", "opacity", "animation", "display", "max-width", "max-height"),
        "img_cell" to setOf(
            "width", "height", "justify-self", "align-self", "justify-content", "align-items", "background-color",
            "border-radius", "opacity", "overflow", "display", "max-width", "max-height", "color",
            "padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
            "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
            // The cell is a flex box: `text-align` doesn't move the icon in it.
            "text-align",
        ),
        "entity_picture" to setOf("width", "border-radius", "opacity", "color",
            "padding", "padding-top", "padding-right", "padding-bottom", "padding-left"),
        "grid" to setOf(
            "grid-template-areas", "grid-template-columns", "grid-template-rows", "gap", "grid-gap", "column-gap",
            "row-gap", "grid-column-gap", "grid-row-gap", "justify-items", "align-items", "display", "text-align",
        ),
        "lock" to setOf("color", "opacity", "justify-content", "align-items",
            "padding", "padding-top", "padding-right", "padding-bottom", "padding-left"),
    )

    /** button-card's own `@keyframes`: the animations Vitro runs on the icon and the card. */
    val BUTTON_CARD_ANIMATIONS: Set<String> = setOf("blink", "rotating")

    // ---------------------------------------------------------------------------------------------------------
    // custom:swipe-card (v5.0.0): Swiper's parameters.
    // ---------------------------------------------------------------------------------------------------------

    /** Swiper `parameters` Vitro's pager reads. */
    val SWIPE_CARD_PARAMETERS: Set<String> = setOf(
        "pagination", "loop", "slidesPerView", "spaceBetween", "centeredSlides", "initialSlide", "autoplay",
        "speed", "grabCursor", "keyboard", "mousewheel", "simulateTouch", "observer", "observeParents", "roundLengths",
    )

    /** Swiper parameters that only matter with a pointer or are hints, never the look. */
    val SWIPE_CARD_BENIGN_PARAMETERS: Set<String> = setOf(
        "speed", "grabCursor", "keyboard", "mousewheel", "simulateTouch", "observer", "observeParents", "roundLengths",
    )

    // ---------------------------------------------------------------------------------------------------------
    // card_mod
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The `card_mod.style` sheets Vitro models, by card type. `"."` (or a plain string) is the card itself; the
     * `$` keys are the shadow roots whose DOM Vitro rebuilds. Any other key reaches into DOM Vitro doesn't have.
     */
    fun cardModSheets(type: String): Set<String> = when (type) {
        "markdown" -> setOf(".", "ha-markdown$")
        "clock" -> setOf(".", "hui-clock-card-digital$")
        else -> setOf(".")
    }

    /** `card_mod` keys other than `style`, and what Vitro does with them. None of them changes the look. */
    val CARD_MOD_OTHER_KEYS: Map<String, String> = mapOf(
        "debug" to "card-mod's console logging",
        "prepend" to "card-mod's load order",
    )

    /**
     * Card types that don't apply their card's `"."` sheet yet. Their card_mod is a known gap, reported as a note,
     * not a fallback, because the work to apply it natively is open.
     */
    val CARD_MOD_NOT_APPLIED: Map<String, String> = mapOf(
        "custom:mushroom-chips-card" to "the chips card doesn't apply its card_mod yet (padding, background, " +
            "`--chip-*` and color variables); #792",
    )

    /**
     * Card types whose card-level `font-size` Vitro reads. On any other card a `font-size` on `ha-card` would
     * reach text Vitro styles on its own, so it isn't honored there.
     */
    val FONT_SIZE_TYPES: Set<String> = setOf(
        "tile", "custom:mushroom-entity-card", "custom:mushroom-light-card", "custom:mushroom-fan-card",
        "custom:mushroom-cover-card", "custom:mushroom-person-card", "custom:mushroom-template-card",
        "custom:mushroom-media-player-card", "custom:mushroom-lock-card", "custom:mushroom-alarm-control-panel-card",
        "custom:mushroom-vacuum-card", "custom:mushroom-humidifier-card", "custom:mushroom-number-card",
        "custom:mushroom-select-card",
    )

    // ---------------------------------------------------------------------------------------------------------
    // CSS: the card itself (`ha-card { }`, `:host { }` in the "." sheet)
    // ---------------------------------------------------------------------------------------------------------

    /** Properties of the card's own rule that Vitro draws on the card surface. Values are checked in [CardCompat]. */
    val CARD_PROPERTIES: Set<String> = setOf(
        "background", "background-color", "background-image",
        "border", "border-width", "border-color", "border-radius",
        "box-shadow", "opacity",
        "padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
        "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
        "transition", "position", "top", "right", "bottom", "left", "width", "height", "transform",
        // Android views are always border-box, which is what this sets.
        "box-sizing",
        // Inherited by every text in the card, web fonts from `type: css` resources included (VitroFonts).
        "font-family",
    )

    /** Of [CARD_PROPERTIES]' typography cousins, the ones only [FONT_SIZE_TYPES] read. */
    val CARD_FONT_PROPERTIES: Set<String> = setOf("font-size")

    /** A stack's `:host { }`: what Vitro applies around its children. */
    val STACK_PROPERTIES: Set<String> = setOf(
        "padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
        "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
        "transition",
    )

    /**
     * Card-scoped custom properties Vitro reads on any card. Others reach Vitro only on Markdown and clock cards,
     * whose sheets run through the CSS cascade, so on other cards an unknown one is a note.
     */
    val CARD_VARIABLES: Set<String> = setOf(
        "vertical-stack-card-gap", "horizontal-stack-card-gap", "stack-card-gap", "grid-card-gap",
    )

    // ---------------------------------------------------------------------------------------------------------
    // CSS: the cascade (Markdown and clock sheets)
    // ---------------------------------------------------------------------------------------------------------

    /** Properties the Markdown and clock cascade computes (`MdCascade`), shorthands included. */
    val CASCADE_PROPERTIES: Set<String> = setOf(
        "font-size", "font-weight", "font-style", "font-family", "line-height", "letter-spacing", "text-transform",
        "text-align", "color", "opacity", "text-decoration", "text-decoration-line", "white-space", "visibility",
        "list-style", "list-style-type", "display", "width", "height", "max-width",
        "background", "background-color", "border-radius", "border-spacing", "border-collapse", "table-layout",
        "vertical-align", "overflow", "overflow-x", "overflow-y", "align-items", "align-self", "justify-content",
        "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
        "margin-block", "margin-inline", "margin-block-start", "margin-block-end", "margin-inline-start", "margin-inline-end",
        "padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
        "padding-block", "padding-inline", "padding-block-start", "padding-block-end", "padding-inline-start", "padding-inline-end",
        "border", "border-top", "border-right", "border-bottom", "border-left", "border-width", "border-style", "border-color",
        "border-top-width", "border-right-width", "border-bottom-width", "border-left-width",
        "border-top-style", "border-right-style", "border-bottom-style", "border-left-style",
        "border-top-color", "border-right-color", "border-bottom-color", "border-left-color",
    )

    /** `display` values the Markdown renderer lays out. */
    val MARKDOWN_DISPLAY: Set<String> = setOf(
        "none", "block", "inline", "inline-block", "list-item", "table", "table-row", "table-cell", "table-row-group",
        "table-header-group", "table-footer-group",
    )

    // ---------------------------------------------------------------------------------------------------------
    // CSS: selectors
    // ---------------------------------------------------------------------------------------------------------

    /** Pseudo-classes the cascade evaluates (`CssSelector`). */
    val PSEUDO_CLASSES: Set<String> = setOf(
        "first-child", "last-child", "only-child", "first-of-type", "last-of-type", "only-of-type",
        "nth-child", "nth-last-child", "nth-of-type", "nth-last-of-type", "empty", "root", "not", "is", "where",
    )

    /** Pseudo-classes that never match on a panel at rest, so a rule using them doesn't change the look. */
    val BENIGN_PSEUDO_CLASSES: Map<String, String> = mapOf(
        "hover" to "pointer hover; a touch panel has no pointer",
        "focus" to "keyboard focus; a wall panel has no keyboard",
        "focus-visible" to "keyboard focus; a wall panel has no keyboard",
        "focus-within" to "keyboard focus; a wall panel has no keyboard",
        "active" to "only while a finger is down",
        "visited" to "link history; browsers barely style it",
    )

    /** Pseudo-elements that don't draw content. */
    val BENIGN_PSEUDO_ELEMENTS: Map<String, String> = mapOf(
        "selection" to "selected text only",
        "-webkit-scrollbar" to "scrollbars; Vitro cards don't scroll",
        "-webkit-scrollbar-thumb" to "scrollbars; Vitro cards don't scroll",
        "-webkit-scrollbar-track" to "scrollbars; Vitro cards don't scroll",
    )

    // ---------------------------------------------------------------------------------------------------------
    // CSS: benign and known gaps
    // ---------------------------------------------------------------------------------------------------------

    /** Properties Vitro ignores because they don't change how a card looks on a touch panel, and why. */
    val BENIGN_PROPERTIES: Map<String, String> = mapOf(
        "cursor" to "the pointer's shape; panels are touch screens",
        "transition" to "how a change animates; Vitro draws the end state (and fades opacity on the card)",
        "transition-property" to "how a change animates; Vitro draws the end state",
        "transition-duration" to "how a change animates; Vitro draws the end state",
        "transition-timing-function" to "how a change animates; Vitro draws the end state",
        "transition-delay" to "how a change animates; Vitro draws the end state",
        "user-select" to "text selection only",
        "-webkit-user-select" to "text selection only",
        "-webkit-tap-highlight-color" to "the browser's tap flash; Vitro draws HA's ripple instead",
        "touch-action" to "which gestures the browser handles, not how it looks",
        "will-change" to "a browser performance hint",
        "-webkit-font-smoothing" to "a macOS font-smoothing hint",
        "-moz-osx-font-smoothing" to "a macOS font-smoothing hint",
        "text-rendering" to "a font-rendering hint",
        "z-index" to "stacking order; Vitro's cards never overlap except pinned ones, which sit on top anyway",
        "scroll-behavior" to "scrolling only",
        "overscroll-behavior" to "scrolling only",
        "scrollbar-width" to "scrollbars; Vitro cards don't scroll",
        "scrollbar-color" to "scrollbars; Vitro cards don't scroll",
        "-webkit-overflow-scrolling" to "scrolling only",
    )

    /** Properties that are benign only with these values (`outline: none`, `animation: none`). */
    val BENIGN_VALUES: Map<String, Set<String>> = mapOf(
        "outline" to setOf("none", "0", "0px"),
        "animation" to setOf("none"),
        "filter" to setOf("none"),
        "text-shadow" to setOf("none"),
        // `none` lets taps through the card in HA, which a native card doesn't do: only the default is harmless.
        "pointer-events" to setOf("auto"),
        // Layout, paint and style containment are performance hints; `size` (and `strict`) change how the card sizes.
        "contain" to setOf("none", "layout", "paint", "style", "content", "layout paint", "paint layout", "layout style", "paint style", "layout paint style"),
    )

    /**
     * Supported properties with values Vitro draws natively but not exactly yet. They're notes, never a fallback:
     * either the difference only shows in rare content, or an island couldn't do better.
     */
    val KNOWN_GAP_PROPERTIES: Map<String, String> = mapOf(
        "!important" to "Vitro keeps the value at normal precedence here, so HA's own rule can win where card-mod's wins in HA",
        "backdrop-filter" to "Vitro doesn't blur what's behind a card; an island can't blur native views either",
        "-webkit-backdrop-filter" to "Vitro doesn't blur what's behind a card; an island can't blur native views either",
        "text-overflow" to "no ellipsis yet; it only shows when the text doesn't fit",
        "white-space: nowrap" to "Vitro may wrap the line; it only shows when the text doesn't fit",
        "position: relative" to "Vitro ignores the small offset (`top`, `left`, ...)",
        "box-shadow: glow" to "drawn as an elevation shadow, not a colored glow",
        "card_mod.class" to "Vitro ignores card_mod classes; they only matter with a card-mod theme",
        "card variable" to "Vitro doesn't read this custom property on this card; HA's card may",
        "templated selector" to "a selector built by a template isn't checked",
        ":link" to "Vitro doesn't apply rules for unvisited links (`:link`); its links keep their own style",
    )

    // ---------------------------------------------------------------------------------------------------------
    // Markdown and HTML
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Tags HA's Markdown sanitizer keeps that Vitro doesn't draw (`MdHtml.WHITELIST` minus these is what Vitro
     * draws). Tags outside HA's whitelist show as text, as they do in HA, so they never count.
     */
    val UNDRAWN_MARKDOWN_TAGS: Map<String, String> = mapOf(
        "audio" to "audio players",
        "video" to "video players",
        "source" to "media sources",
        "picture" to "responsive pictures",
        "details" to "collapsible sections",
        "summary" to "collapsible sections",
        "input" to "checkboxes",
        "area" to "image maps",
        "ha-alert" to "HA's alert boxes",
        "ha-qr-code" to "QR codes",
        "ha-svg-icon" to "raw SVG icons",
    )
}
