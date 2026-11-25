#!/usr/bin/env python3
"""Audit a Home Assistant Lovelace dashboard (YAML or JSON) for Vitro-native compatibility.

Exits 0 if the entire dashboard renders 100% natively in Vitro (zero Tier 3 GeckoView triggers),
or exits 1 if any card would boot GeckoView.

Routes cards the way the engine does (compat/CardCompat.kt): a custom card with something Vitro can't draw goes
to an island; a built-in card stays native and gets a WARN line instead, because the island can't draw built-in
cards. Its card_mod check is coarser than the engine's: the site's checker (glassworks.dev/blueprints#vitro-check)
and the panel's own report read every property.
"""
import argparse
import json
import re
import sys
from pathlib import Path

NATIVE_CORE_TYPES = {
    "tile",
    "entities",
    "glance",
    "button",
    "heading",
    "clock",
    "sensor",
    "history-graph",
    "gauge",
    "thermostat",
    "weather-forecast",
    "markdown",
    "picture",
    "picture-entity",
    "picture-glance",
    "media-control",
    "grid",
    "horizontal-stack",
    "vertical-stack",
    "conditional",
    "entity-filter",
    "entity",
    "light",
    "alarm-panel",
    "todo-list",
    "statistics-graph",
    "area",
    "picture-elements",
    "humidifier",
    "calendar",
    "logbook",
}

NATIVE_HACS_TYPES = {
    "custom:mushroom-entity-card",
    "custom:mushroom-light-card",
    "custom:mushroom-title-card",
    "custom:mushroom-chips-card",
    "custom:mushroom-template-card",
    "custom:mushroom-climate-card",
    "custom:mushroom-fan-card",
    "custom:mushroom-cover-card",
    "custom:mushroom-person-card",
    "custom:bubble-card",
    "custom:mini-graph-card",
    "custom:clock-weather-card",
    "custom:modern-circular-gauge",
    "custom:bar-card",
    "custom:mushroom-media-player-card",
    "custom:mushroom-lock-card",
    "custom:mushroom-alarm-control-panel-card",
    "custom:mushroom-vacuum-card",
    "custom:mushroom-humidifier-card",
    "custom:mushroom-number-card",
    "custom:mushroom-select-card",
    "custom:mini-media-player",
    "custom:auto-entities",
    "custom:stack-in-card",
    "custom:vertical-stack-in-card",
    "custom:layout-card",
}

SUPPORTED_TILE_FEATURES = {
    "light-brightness",
    "light-color-temp",
    "fan-speed",
    "cover-open-close",
    "cover-position",
    "climate-hvac-modes",
    "target-temperature",
    "select-options",
    "media-player-volume-slider",
    "toggle",
    "media-player-playback",
    "light-color-favorites",
    "numeric-input",
    "vacuum-commands",
}

# Cards drawn natively only in some configurations: card type -> (option, its default, the native values). Any
# other value is a web card when its script is installed, as the engine routes it. Same as configDependentTypes in
# site/public/vitro-compat.json.
CONFIG_DEPENDENT_TYPES = {
    "custom:bubble-card": ("card_type", "button", {"button", "separator"}),
    "custom:layout-card": ("layout_type", "masonry-layout", {"masonry-layout", "horizontal-layout", "vertical-layout", "grid-layout"}),
}

# `entities` rows with a `type:` Vitro draws; a row without one is a plain entity row.
ENTITIES_ROW_TYPES = {"divider", "section"}

# Options of an `entities` card Vitro doesn't draw.
ENTITIES_UNSUPPORTED_OPTIONS = {"header", "footer"}

# `picture-elements` element types Vitro draws.
PICTURE_ELEMENT_TYPES = {"state-icon", "state-label", "state-badge", "icon", "service-button"}

# Mushroom chip types Vitro draws; `conditional` wraps one of the others.
CHIP_TYPES = {"entity", "template", "weather", "light", "action", "menu", "back", "spacer", "conditional"}

# Tags HA's Markdown sanitizer keeps that Vitro doesn't draw.
UNDRAWN_MARKDOWN_TAGS = {
    "audio",
    "video",
    "source",
    "picture",
    "details",
    "summary",
    "input",
    "area",
    "ha-alert",
    "ha-qr-code",
    "ha-svg-icon",
}

CONTAINER_TYPES = {
    "grid",
    "horizontal-stack",
    "vertical-stack",
    "conditional",
    "custom:stack-in-card",
    "custom:vertical-stack-in-card",
    "custom:layout-card",
}


def load_dashboard(path: str):
    raw = sys.stdin.read() if path == "-" else Path(path).read_text(encoding="utf-8")
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        try:
            import yaml
        except ImportError:
            sys.stderr.write("error: input is not JSON and PyYAML is not installed (pip install pyyaml)\n")
            sys.exit(2)
        data = yaml.safe_load(raw)
    if isinstance(data, dict) and "data" in data and isinstance(data["data"], dict) and "config" in data["data"]:
        data = data["data"]["config"]
    if isinstance(data, dict) and "config" in data and isinstance(data["config"], dict) and "views" in data["config"]:
        data = data["config"]
    return data


def supported_style_key(card_type: str, key: str) -> bool:
    """Selector-map keys Vitro reads per card type (VitroSupport.cardModSheets): "." always, the roots on their cards."""
    if key == ".":
        return True
    if key == "ha-markdown$":
        return card_type == "markdown"
    if key == "hui-clock-card-digital$":
        return card_type == "clock"
    return False


# Legacy single-colon pseudo-elements (ha-card:before): generated content, not drawn natively either.
LEGACY_PSEUDO = re.compile(r":(before|after|first-line|first-letter)\b", re.I)


# A selector for the card itself (ha-card, :host, :host > ha-card), compounded with classes, ids, attributes
# (ha-card.compact) and interaction states at most. A structural pseudo-class (:nth-child(1)) isn't applied natively.
OWN_CARD_SELECTOR = re.compile(
    r"^(?::host(?:\s*>?\s*ha-card)?|ha-card)"
    r"(?:\.[\w-]+|#[\w-]+|\[[^\]]*\]|:(?:hover|active|focus|focus-visible|focus-within)(?![\w-]))*$",
    re.I,
)
# An at-rule the renderer doesn't evaluate (@media, @supports): only keyframes and @charset are harmless.
UNSUPPORTED_AT_RULE = re.compile(r"@(?!(?:-webkit-)?keyframes(?![\w-])|charset(?![\w-]))[a-z-]+", re.I)
# Jinja is masked before reading selectors, as the renderer replaces it first.
JINJA = re.compile(r"\{\{[\s\S]*?\}\}|\{%[\s\S]*?%\}|\{#[\s\S]*?#\}")


# Quoted strings are emptied before looking for at-rules: an @ in one ([data-label="@media"]) isn't one.
QUOTED = re.compile(r"\"(?:[^\"\\\n]|\\.)*\"|'(?:[^'\\\n]|\\.)*'")


def mask_css(css: str) -> str:
    """The CSS without comments and Jinja: what the renderer reads rules from."""
    return JINJA.sub(" ", re.sub(r"/\*[\s\S]*?\*/", " ", css))


def reaches_inside(card_type: str, css: str) -> bool:
    """A rule in the card's own sheet for something inside it (ha-card .card-header): not applied natively."""
    if card_type in ("markdown", "clock"):
        return False
    text = mask_css(css)
    for m in re.finditer(r"([^{}]+)\{", text):
        for sel in m.group(1).split(","):
            t = sel.strip()
            if t and not t.startswith("@") and not OWN_CARD_SELECTOR.match(t):
                return True
    return False


def card_mod_reason(card: dict, card_type: str):
    """Why the card's card_mod isn't drawn natively (a coarse check), or None."""
    cm = card.get("card_mod")
    if not isinstance(cm, dict):
        return None
    style = cm.get("style")
    def complex_css(css) -> bool:
        return (not isinstance(css, str) or "$:" in css or "@keyframes" in css or "::" in css
                or LEGACY_PSEUDO.search(css) is not None or UNSUPPORTED_AT_RULE.search(QUOTED.sub('""', mask_css(css))) is not None)
    def own(css) -> bool:
        return complex_css(css) or (isinstance(css, str) and reaches_inside(card_type, css))
    if isinstance(style, dict):
        unsupported = any(not supported_style_key(card_type, k) or (own(v) if k == "." else complex_css(v))
                          for k, v in style.items())
    else:
        unsupported = isinstance(style, str) and own(style)
    if unsupported:
        return "complex Shadow-DOM / pseudo / keyframe card_mod (only ha-card container styles mirror natively)"
    return None


def card_type_of(card: dict) -> str:
    """The card's type as the engine parses it: `entities` when it has none."""
    t = card.get("type")
    return "entities" if t is None or t == "" else str(t)


def option_reasons(card: dict, ctype: str) -> list[str]:
    """Options Vitro doesn't draw (CardCompat.checkOptions)."""
    out = []
    objs = lambda v: [x for x in v if isinstance(x, dict)] if isinstance(v, list) else []
    rule = CONFIG_DEPENDENT_TYPES.get(ctype)
    if rule:
        option, default, native = rule
        value = str(card.get(option) or default)
        if option == "layout_type":
            value = value.removeprefix("custom:") or default
        if value not in native:
            out.append(f"{option}: {value} isn't drawn natively")
    if ctype == "tile":
        for feat in objs(card.get("features")):
            ftype = str(feat.get("type") or "")
            if ftype and ftype not in SUPPORTED_TILE_FEATURES:
                out.append(f"tile feature '{ftype}' isn't drawn natively")
    if ctype == "entities":
        out += [f"'{k}:' isn't drawn natively" for k in sorted(ENTITIES_UNSUPPORTED_OPTIONS) if card.get(k) is not None]
    if ctype in ("entities", "entity-filter"):
        for row in objs(card.get("entities")):
            rtype = str(row.get("type") or "")
            if rtype and rtype not in ENTITIES_ROW_TYPES:
                out.append(f"entities row type '{rtype}' isn't drawn natively")
    if ctype == "picture-elements":
        for el in objs(card.get("elements")):
            etype = str(el.get("type") or "")
            if etype not in PICTURE_ELEMENT_TYPES:
                out.append(f"picture-elements element '{etype or '(no type)'}' isn't drawn natively")
    if ctype == "custom:mushroom-chips-card":
        for chip in objs(card.get("chips")):
            ctype_ = str(chip.get("type", "entity"))
            if ctype_ not in CHIP_TYPES:
                out.append(f"mushroom chip '{ctype_}' isn't drawn natively")
            inner = chip.get("chip")
            if ctype_ == "conditional" and isinstance(inner, dict):
                itype = str(inner.get("type", "entity"))
                if itype not in CHIP_TYPES or itype == "conditional":
                    out.append(f"mushroom chip '{itype}' (in a conditional chip) isn't drawn natively")
    if ctype == "markdown":
        for tag in re.findall(r"<([a-zA-Z][a-zA-Z0-9-]*)", str(card.get("content") or "")):
            if tag.lower() in UNDRAWN_MARKDOWN_TAGS:
                out.append(f"Markdown <{tag.lower()}> isn't drawn natively")
    return out


def produced_cards(card: dict, ctype: str, per_entity: bool) -> list[tuple[str, dict]]:
    """The cards a card builds one level down (CardRouter.producedCards), with a label for the report."""
    out = []
    if ctype in CONTAINER_TYPES and ctype != "conditional":
        kids = [c for c in card.get("cards") or [] if isinstance(c, dict)]
        if ctype == "custom:layout-card" and str(card.get("layout_type") or "").removeprefix("custom:") in ("vertical-layout", "vertical"):
            kids = [c for c in kids if card_type_of(c) != "custom:layout-break"]
        out = [(f"{ctype}[{i}]", c) for i, c in enumerate(kids)]
    elif ctype == "conditional" and isinstance(card.get("card"), dict):
        out = [("conditional[0]", card["card"])]
    if ctype != "custom:auto-entities":
        return out
    # As the runtime builds it: a copy, `type: entities` when it names none, without the field `card_param` fills.
    inner = json.loads(json.dumps(card["card"])) if isinstance(card.get("card"), dict) else {}
    inner.setdefault("type", "entities")
    param = card.get("card_param") or "entities"
    inner.pop(param, None)
    generated = []
    if param == "cards":
        for entry in card.get("entities") or []:
            if isinstance(entry, str):
                generated.append({"type": "tile"})
            elif isinstance(entry, dict):
                built = dict({"type": "tile"}, **entry)
                built.pop("entity", None)
                generated.append(built)
        include = (card.get("filter") or {}).get("include") if isinstance(card.get("filter"), dict) else None
        for rule in include if isinstance(include, list) else []:
            opts = rule.get("options") if isinstance(rule, dict) else None
            generated.append(dict({"type": "tile"}, **opts) if isinstance(opts, dict) else {"type": "tile"})
        inner["cards"] = generated
    out.append(("card", inner))
    if per_entity:
        out += [(f"entities[{i}]", g) for i, g in enumerate(generated)]
    return out


def needs_ha_frontend(card: dict) -> bool:
    """A built-in card, or a card that builds one at any depth: the island would draw those as plain summaries."""
    ctype = card_type_of(card)
    return (not ctype.startswith("custom:") or ctype == "custom:stack-in-card"
            or any(needs_ha_frontend(c) for _, c in produced_cards(card, ctype, True)))


def walk_card(card: dict, loc: str, counts: dict[str, int], triggers: list[str], warnings: list[str], explicit: int = -1):
    if not isinstance(card, dict):
        return
    ctype = card_type_of(card)
    if card.get("type") in (None, ""):
        warnings.append(f"  WARN  {loc}: no type, so Vitro draws it as an entities card")

    if ctype in NATIVE_CORE_TYPES or ctype in NATIVE_HACS_TYPES:
        reasons = option_reasons(card, ctype)
        cm = card_mod_reason(card, ctype)
        if cm:
            reasons.append(cm)
    elif ctype.startswith("custom:"):
        reasons = ["a custom card with no native renderer"]
    else:
        reasons = ["not a card type Vitro draws"]

    if reasons and (ctype in NATIVE_CORE_TYPES or (ctype in NATIVE_HACS_TYPES and needs_ha_frontend(card))):
        # A built-in card stays native (the island can't draw built-in cards), and so does a custom card Vitro
        # draws with a built-in card inside it.
        for r in reasons:
            warnings.append(f"  WARN  {loc}: {r} (stays native: the island can't draw built-in cards)")
    elif reasons:
        counts["tier3"] += 1
        triggers.append(f"  TIER 3 [GECKOVIEW]  {loc}: type='{ctype}': {'; '.join(reasons)}")
        return

    children = produced_cards(card, ctype, False)
    if explicit >= 0 and ctype in CONTAINER_TYPES and ctype != "conditional":
        # auto-entities' inner card: only its explicit `entities:` entries are known before it runs.
        for i, (_, child) in enumerate(children[:explicit]):
            walk_card(child, f"{loc}.entities[{i}]", counts, triggers, warnings)
        return
    if not (ctype in CONTAINER_TYPES and children):
        counts["tier1" if ctype in NATIVE_CORE_TYPES else "tier2"] += 1
    holds = len(card.get("entities") or []) if ctype == "custom:auto-entities" and card.get("card_param") == "cards" else -1
    for label, child in children:
        walk_card(child, f"{loc}.{label}", counts, triggers, warnings, holds)


def view_type(view: dict) -> str:
    """The view's type as the engine infers it."""
    sections = [s for s in view.get("sections") or [] if isinstance(s, dict)]
    return view.get("type") or ("panel" if view.get("panel") is True else "sections" if sections else "masonry")


def view_roots(view: dict, vname: str) -> list[tuple[str, dict]]:
    """The cards the engine builds for a view: a panel view's first card, a sections view's sections, or its cards."""
    sections = [s for s in view.get("sections") or [] if isinstance(s, dict)]
    vtype = view_type(view)
    cards = view.get("cards") or []
    if vtype == "panel" and cards:
        return [(f"{vname}/card[0]", cards[0])]
    if vtype == "sections" and sections:
        return [(f"{vname}/section[{si}]/card[{ci}]", c) for si, s in enumerate(sections) for ci, c in enumerate(s.get("cards") or [])]
    return [(f"{vname}/card[{ci}]", c) for ci, c in enumerate(cards)]


def audit(data: dict) -> int:
    views = data.get("views") if isinstance(data, dict) else None
    if not isinstance(views, list):
        sys.stderr.write("error: expected a Lovelace dashboard with a top-level 'views' list\n")
        return 2

    total = {"tier1": 0, "tier2": 0, "tier3": 0}

    for vi, view in enumerate(views):
        if not isinstance(view, dict):
            continue
        vname = view.get("path") or view.get("title") or f"view_{vi}"
        vcounts = {"tier1": 0, "tier2": 0, "tier3": 0}
        vtriggers: list[str] = []
        vwarnings: list[str] = []

        for loc, card in view_roots(view, vname):
            walk_card(card, loc, vcounts, vtriggers, vwarnings)

        for k in total:
            total[k] += vcounts[k]

        status = "NATIVE" if vcounts["tier3"] == 0 else "BOOTS GECKOVIEW"
        print(
            f"View '{vname}' ({view_type(view)}): "
            f"Tier1={vcounts['tier1']} Tier2={vcounts['tier2']} Tier3={vcounts['tier3']} -> {status}"
        )
        for line in vtriggers:
            print(line)
        for line in vwarnings:
            print(line)

    print("---")
    print(f"Total: Tier 1 (Core)={total['tier1']}, Tier 2 (Native HACS)={total['tier2']}, Tier 3 (GeckoView)={total['tier3']}")
    if total["tier3"] == 0:
        print("PASS: 100% Vitro-native (zero GeckoView triggers).")
        return 0
    print(f"FAIL: {total['tier3']} Tier 3 card(s) will boot GeckoView.")
    return 1


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("dashboard", help="Path to Lovelace YAML or JSON file, or '-' for stdin")
    args = ap.parse_args()
    data = load_dashboard(args.dashboard)
    sys.exit(audit(data))


if __name__ == "__main__":
    main()
