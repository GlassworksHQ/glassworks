#!/usr/bin/env python3
"""Audit a Home Assistant Lovelace dashboard (YAML or JSON) for Vitro-native compatibility.

Exits 0 if the entire dashboard renders 100% natively in Vitro (zero Tier 3 GeckoView triggers),
or exits 1 if any card would boot GeckoView.
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
    "entity-button",
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
    "custom:button-card",
    "custom:mushroom-empty-card",
    "custom:swipe-card",
    "custom:weather-card",
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

CONTAINER_TYPES = {
    "grid",
    "horizontal-stack",
    "vertical-stack",
    "conditional",
    "custom:stack-in-card",
    "custom:vertical-stack-in-card",
    "custom:layout-card",
    "custom:swipe-card",
}

# custom:button-card is native unless it runs JavaScript (`[[[ ]]]`) or holds cards or HTML. The engine and the site
# checker (site/src/lib/vitroButtonCard.ts) also check its styles; this script only reports those as a warning.
BUTTON_CARD_JS = re.compile(r"^\[\[\[(?!\[).*(?<!\])\]\]\]$", re.S)


def _merge(prev, obj):
    """button-card's mergeDeep: lists concatenate, maps merge, anything else replaces."""
    out = dict(prev)
    for k, o in obj.items():
        p = out.get(k)
        if isinstance(p, list) and isinstance(o, list):
            out[k] = p + [y for x in o for y in (x if isinstance(x, list) else [x])]
        elif isinstance(p, dict) and isinstance(o, dict):
            out[k] = _merge(p, o)
        else:
            out[k] = o
    return out


def _merge_states(into, frm):
    """button-card's mergeStatesById: entries with the same truthy id merge, the others follow."""
    out = []
    for s in into or []:
        local = s
        for f in frm or []:
            if isinstance(f, dict) and isinstance(s, dict) and f.get("id") and s.get("id") and f["id"] == s["id"] and isinstance(local, dict):
                local = _merge(local, f)
        out.append(local)
    for f in frm or []:
        fid = f.get("id") if isinstance(f, dict) else None
        if not into or not fid or not any(isinstance(s, dict) and s.get("id") and s["id"] == fid for s in into):
            out.append(f)
    return out


def expand_button_card(card: dict, templates: dict, depth: int = 0):
    """The card with its `template:` merged in from button_card_templates, or None when one is missing or they loop."""
    tpl = card.get("template")
    if not tpl:
        return card
    if depth > 32:
        return None
    result, states = {}, None
    for name in tpl if isinstance(tpl, list) else [tpl]:
        t = templates.get(name) if isinstance(name, str) else None
        res = expand_button_card(t, templates, depth + 1) if isinstance(t, dict) else None
        if res is None:
            return None
        result = _merge(result, res)
        if isinstance(res.get("state"), list):
            states = _merge_states(states, res["state"])
    result = _merge(result, {k: v for k, v in card.items() if k != "template"})
    # `state` entries merge by id, not by concatenation (non-list states are left for the caller to report).
    if states is not None or isinstance(card.get("state"), list):
        result["state"] = _merge_states(states, card.get("state") if isinstance(card.get("state"), list) else None)
    return result


def button_card_reason(card: dict, templates: dict) -> str | None:
    cfg = expand_button_card(card, templates)
    if cfg is None:
        return "a template that isn't in button_card_templates"

    def js(v, top=True):
        if isinstance(v, str):
            return bool(BUTTON_CARD_JS.match(v.strip()))
        if isinstance(v, dict):
            return any(js(x, False) for k, x in v.items() if not (top and k == "variables"))
        if isinstance(v, list):
            return any(js(x, False) for x in v)
        return False

    if js(cfg):
        return "JavaScript templates ([[[ ]]])"
    # Options and actions the engine's ButtonCardCompat doesn't run natively (VitroSupport's button-card lists).
    for key in BUTTON_CARD_UNSUPPORTED_OPTIONS:
        if key not in cfg:
            continue
        v = cfg[key]
        on = (isinstance(v, dict) and v.get("action") not in (None, "", "none")) or (isinstance(v, str) and v != "") \
            if key.endswith("_action") else bool(v)
        if on:
            return f"{key}:"
    for key in ("tap_action", "hold_action", "double_tap_action"):
        a = cfg.get(key)
        if not isinstance(a, dict) or a.get("action") in (None, "", "none"):
            continue
        if a.get("action") not in BUTTON_CARD_ACTIONS:
            return f"a {key} type it doesn't run natively"
        if any(a.get(f) for f in ("repeat", "repeat_limit", "sound", "protect")):
            return f"a {key} it doesn't run natively"
        if isinstance(a.get("confirmation"), dict) and "exemptions" in a["confirmation"]:
            return f"{key} confirmation exemptions"
    if isinstance(cfg.get("confirmation"), dict) and "exemptions" in cfg["confirmation"]:
        return "confirmation exemptions"
    if cfg.get("layout") is not None and cfg.get("layout") not in BUTTON_CARD_LAYOUTS:
        return "a layout it doesn't draw natively"
    # button-card evaluates a `force_eval` variable on every render, JavaScript or not (the engine sends it to an island).
    variables = cfg.get("variables") if isinstance(cfg.get("variables"), dict) else {}
    if any(isinstance(v, dict) and v.get("force_eval") is True for v in variables.values()):
        return "variables with force_eval"
    fields = cfg.get("custom_fields") if isinstance(cfg.get("custom_fields"), dict) else {}
    if any(isinstance(v, dict) and "card" in v for v in fields.values()):
        return "a card in custom_fields"
    if cfg.get("state") is not None and not isinstance(cfg.get("state"), list):
        return "a state: that isn't a list"
    texts = [cfg] + [st for st in (cfg.get("state") or []) if isinstance(st, dict)]
    if any(isinstance(t.get(k), str) and re.search(r"<[a-zA-Z/!]", t[k]) for t in texts for k in ("name", "label", "state_display")):
        return "HTML in its texts"
    return None


TEMPLATES: dict = {}

# VitroSupport.BUTTON_CARD_UNSUPPORTED_OPTIONS, BUTTON_CARD_ACTIONS and BUTTON_CARD_LAYOUTS (buttonCard in
# site/public/vitro-compat.json).
BUTTON_CARD_UNSUPPORTED_OPTIONS = {
    "show_live_stream", "spinner", "protect", "extra_styles", "press_action", "release_action", "icon_tap_action",
    "icon_hold_action", "icon_double_tap_action", "icon_press_action", "icon_release_action",
}
BUTTON_CARD_ACTIONS = {"none", "toggle", "more-info", "navigate", "url", "call-service", "perform-action"}
BUTTON_CARD_LAYOUTS = {
    "vertical", "icon_name_state", "name_state", "icon_name", "icon_state", "icon_name_state2nd",
    "icon_state_name2nd", "icon_label",
}

# Swiper parameters Vitro's native swipe-card reads (VitroSupport.SWIPE_CARD_PARAMETERS, swipeCardParameters in
# site/public/vitro-compat.json).
SWIPE_CARD_PARAMETERS = {
    "pagination", "loop", "slidesPerView", "spaceBetween", "centeredSlides", "initialSlide", "autoplay",
    "speed", "grabCursor", "keyboard", "mousewheel", "simulateTouch", "observer", "observeParents", "roundLengths",
}


def swipe_card_reason(card: dict) -> str | None:
    """What the engine's CardCompat doesn't run natively on a swipe-card: unknown parameters and the values it reads."""
    params = card.get("parameters") if isinstance(card.get("parameters"), dict) else {}
    for k in params:
        if k not in SWIPE_CARD_PARAMETERS:
            return f"parameter {k}"
    if "pagination" in params:
        p = params["pagination"]
        ptype = (p.get("type") or "bullets") if isinstance(p, dict) else "bullets"
        if ptype not in ("bullets", "fraction"):
            return f"pagination type {ptype}"
        if isinstance(p, dict) and p.get("dynamicBullets") is True:
            return "dynamicBullets"
    if "slidesPerView" in params:
        v = params["slidesPerView"]
        if v != "auto" and not (isinstance(v, (int, float)) and not isinstance(v, bool) and v > 0):
            return f"slidesPerView {v}"
    if "card_width" in card:
        return "card_width"
    return None


def _children(card: dict) -> list:
    out = [c for c in card.get("cards") or [] if isinstance(c, dict)] if isinstance(card.get("cards"), list) else []
    if isinstance(card.get("card"), dict):
        out.append(card["card"])
    return out


def needs_ha_frontend_inside(card: dict) -> bool:
    """The engine's needsHaFrontend: a built-in card (or stack-in-card) anywhere inside keeps a custom container native."""
    for c in _children(card):
        t = c.get("type") if isinstance(c.get("type"), str) else ""
        if not t.startswith("custom:") or t == "custom:stack-in-card" or needs_ha_frontend_inside(c):
            return True
    return False


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


def check_card_mod(card: dict, loc: str, warnings: list[str]):
    cm = card.get("card_mod")
    if not isinstance(cm, dict):
        return
    style = cm.get("style")
    card_type = str(card.get("type", "")).strip()
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
        warnings.append(f"  WARN  {loc}: complex Shadow-DOM / pseudo / keyframe card_mod (only ha-card container styles mirror natively)")


def walk_card(card: dict, loc: str, counts: dict[str, int], triggers: list[str], warnings: list[str]):
    if not isinstance(card, dict):
        return
    ctype = str(card.get("type") or "").strip()
    check_card_mod(card, loc, warnings)

    rule = CONFIG_DEPENDENT_TYPES.get(ctype)
    if rule:
        option, default, native = rule
        raw = card.get(option)
        value = raw.strip() if isinstance(raw, str) and raw.strip() else default
        # The engine reads layout-card's `layout_type` with or without `custom:`; Bubble Card's `card_type` as written.
        if option == "layout_type":
            value = value.removeprefix("custom:")
        if value not in native:
            counts["tier3"] += 1
            triggers.append(f"  TIER 3 [GECKOVIEW]  {loc}: type='{ctype}' with {option}: {value} (when its script is installed)")
            return

    if ctype == "custom:button-card":
        reason = button_card_reason(card, TEMPLATES)
        if reason:
            counts["tier3"] += 1
            triggers.append(f"  TIER 3 [GECKOVIEW]  {loc}: type='{ctype}' with {reason}")
            return
        expanded = expand_button_card(card, TEMPLATES) or card
        if expanded.get("styles") or expanded.get("state"):
            warnings.append(f"  WARN  {loc}: button-card styles are drawn natively only in Vitro's subset (see the site checker)")

    # weather-card needs an entity (its script shows an error without one), as the engine routes it.
    if ctype == "custom:weather-card" and not (isinstance(card.get("entity"), str) and card["entity"]):
        counts["tier3"] += 1
        triggers.append(f"  TIER 3 [GECKOVIEW]  {loc}: type='{ctype}' without an entity")
        return

    # swipe-card with Swiper parameters Vitro doesn't run is a web card, unless a built-in card inside keeps it native.
    if ctype == "custom:swipe-card":
        reason = swipe_card_reason(card)
        if reason and not needs_ha_frontend_inside(card):
            counts["tier3"] += 1
            triggers.append(f"  TIER 3 [GECKOVIEW]  {loc}: type='{ctype}' with {reason}")
            return

    if ctype == "tile":
        for feat in card.get("features") or []:
            ftype = feat.get("type") if isinstance(feat, dict) else str(feat)
            if ftype and ftype not in SUPPORTED_TILE_FEATURES:
                warnings.append(f"  WARN  {loc}: tile feature '{ftype}' is not yet rendered natively")

    if ctype in CONTAINER_TYPES:
        children = card.get("cards") if isinstance(card.get("cards"), list) else []
        if not children and isinstance(card.get("card"), dict):
            children = [card["card"]]
        for i, child in enumerate(children):
            walk_card(child, f"{loc}.{ctype}[{i}]", counts, triggers, warnings)
        return

    if ctype in NATIVE_CORE_TYPES:
        counts["tier1"] += 1
    elif ctype in NATIVE_HACS_TYPES:
        counts["tier2"] += 1
        if ctype == "custom:auto-entities":
            # Its cards are built at runtime: the inner card (entities by default), and with `card_param: cards`
            # each include rule's `options` card.
            # As the runtime builds it: a copy, `type: entities` when it names none, and without the list that
            # `card_param` (default `entities`) is filled in with at runtime.
            inner = dict(card["card"]) if isinstance(card.get("card"), dict) else {}
            inner.setdefault("type", "entities")
            inner.pop(card.get("card_param") or "entities", None)
            walk_card(inner, f"{loc}.card", counts, triggers, warnings)
            if card.get("card_param") == "cards":
                # The explicit entries: a string is a tile; a map keeps its type (tile when none) without `entity`.
                entries = card.get("entities") or []
                for i, entry in enumerate(entries if isinstance(entries, list) else []):
                    if isinstance(entry, str):
                        walk_card({"type": "tile"}, f"{loc}.entities[{i}]", counts, triggers, warnings)
                    elif isinstance(entry, dict):
                        built = dict({"type": "tile"}, **entry)
                        built.pop("entity", None)
                        walk_card(built, f"{loc}.entities[{i}]", counts, triggers, warnings)
                rules = (card.get("filter") or {}).get("include") or []
                for i, rule in enumerate(rules if isinstance(rules, list) else []):
                    opts = rule.get("options") if isinstance(rule, dict) else None
                    if isinstance(opts, dict):
                        walk_card(dict({"type": "tile"}, **opts), f"{loc}.include[{i}].options", counts, triggers, warnings)
    else:
        counts["tier3"] += 1
        triggers.append(f"  TIER 3 [GECKOVIEW]  {loc}: type='{ctype or '<missing>'}'")


def audit(data: dict) -> int:
    global TEMPLATES
    TEMPLATES = data.get("button_card_templates") if isinstance(data, dict) and isinstance(data.get("button_card_templates"), dict) else {}
    views = data.get("views") if isinstance(data, dict) else None
    if not isinstance(views, list):
        sys.stderr.write("error: expected a Lovelace dashboard with a top-level 'views' list\n")
        return 2

    total = {"tier1": 0, "tier2": 0, "tier3": 0}
    all_triggers: list[str] = []
    all_warnings: list[str] = []

    for vi, view in enumerate(views):
        if not isinstance(view, dict):
            continue
        vname = view.get("path") or view.get("title") or f"view_{vi}"
        vcounts = {"tier1": 0, "tier2": 0, "tier3": 0}
        vtriggers: list[str] = []
        vwarnings: list[str] = []

        for si, sec in enumerate(view.get("sections") or []):
            if not isinstance(sec, dict):
                continue
            for ci, card in enumerate(sec.get("cards") or []):
                walk_card(card, f"{vname}/section[{si}]/card[{ci}]", vcounts, vtriggers, vwarnings)

        for ci, card in enumerate(view.get("cards") or []):
            walk_card(card, f"{vname}/card[{ci}]", vcounts, vtriggers, vwarnings)

        for k in total:
            total[k] += vcounts[k]
        all_triggers.extend(vtriggers)
        all_warnings.extend(vwarnings)

        status = "NATIVE" if vcounts["tier3"] == 0 else "BOOTS GECKOVIEW"
        print(
            f"View '{vname}' ({view.get('type', 'masonry')}): "
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
