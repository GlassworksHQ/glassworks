/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

/**
 * The set Vitro draws natively, in one place. A custom card with anything outside it goes to a web island on
 * purpose ([CardCompat]), so it looks the way Home Assistant draws it and only that card pays for a web engine.
 *
 * Built-in cards are the exception: the island runs a custom card's own JavaScript but has no HA frontend, so it
 * would draw a built-in card (a `tile`, say) as a plain summary. A built-in card Vitro draws stays native even when
 * its config is outside this set (an unsupported option, card_mod path or selector), and so does a custom container
 * Vitro draws with a built-in card inside it. [CardCompat.check] records what it would have fallen back for as a
 * note instead of routing the card to the island.
 *
 * Three lists decide a card's route:
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
 * When a renderer learns something new, add it here, and the README's "What Vitro draws natively" table with it.
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
        "custom:vertical-stack-in-card", "custom:layout-card",
    )

    /** HA's stacks: no `ha-card` of their own, so card-mod's `ha-card { }` in them matches nothing in HA either. */
    val STACK_TYPES: Set<String> = setOf("vertical-stack", "horizontal-stack", "grid", "conditional")

    // ---------------------------------------------------------------------------------------------------------
    // Options per card type that change what is drawn
    // ---------------------------------------------------------------------------------------------------------

    /** Tile card `features[].type` values Vitro draws. */
    val TILE_FEATURES: Set<String> = setOf(
        "light-brightness", "select-options", "cover-open-close", "climate-hvac-modes", "target-temperature",
        "fan-speed", "cover-position", "light-color-temp", "media-player-volume-slider", "toggle",
        "media-player-playback", "light-color-favorites", "numeric-input", "vacuum-commands",
    )

    /** `entities` rows with a `type:`. A row without one is a plain entity row, which Vitro draws. */
    val ENTITIES_ROW_TYPES: Set<String> = setOf("divider", "section")

    /** `picture-elements` element types Vitro draws (`conditional` and `image` elements are not). */
    val PICTURE_ELEMENT_TYPES: Set<String> = setOf("state-icon", "state-label", "state-badge", "icon", "service-button")

    /** `custom:mushroom-chips-card` chip types Vitro draws; `conditional` wraps one of the others. */
    val CHIP_TYPES: Set<String> = setOf("entity", "template", "weather", "light", "action", "menu", "back", "spacer", "conditional")

    /** `custom:bubble-card` `card_type` values Vitro draws (the default is `button`). */
    val BUBBLE_CARD_TYPES: Set<String> = setOf("button", "separator")

    /** `custom:layout-card` `layout_type` values (with or without `custom:`). */
    val LAYOUT_CARD_TYPES: Set<String> = setOf("masonry-layout", "horizontal-layout", "vertical-layout", "grid-layout")

    /** Card options Vitro doesn't draw, by card type: having one sends the card to an island. */
    val UNSUPPORTED_OPTIONS: Map<String, Map<String, String>> = mapOf(
        "entities" to mapOf(
            "header" to "an entities header (picture or buttons)",
            "footer" to "an entities footer (buttons, graph or picture)",
        ),
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
        "link" to "an unvisited link, which Markdown styles anyway",
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
        "tile features_position: inline" to "Vitro puts the features under the tile; #792",
        "card_mod.class" to "Vitro ignores card_mod classes; they only matter with a card-mod theme",
        "card variable" to "Vitro doesn't read this custom property on this card; HA's card may",
        "templated selector" to "a selector built by a template isn't checked",
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
