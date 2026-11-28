#!/usr/bin/env python3
"""Audit a Home Assistant Lovelace dashboard (YAML or JSON) for Vitro-native compatibility.

Exits 0 if the entire dashboard renders 100% natively in Vitro (zero Tier 3 GeckoView triggers),
or exits 1 if any card would boot GeckoView.

Routes cards the way the engine does (compat/CardCompat.kt, WebFallback.kt): a custom card with something Vitro can't
draw goes to an island. A built-in card (or a custom card holding built-in ones) with an option or value Vitro doesn't
draw as HA does sends its view to HA's frontend: the strict compat rule, read from the engine's own declaration
(native-options.json, see NativeOptions.kt). For its card_mod, a built-in card stays native and gets a WARN line
instead, because the island can't draw built-in cards. The card_mod check is coarser than the engine's: the site's
checker (glassworks.dev/blueprints#vitro-check) and the panel's own report read every property.
"""
import argparse
import json
import math
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
    "shopping-list",
    "section",
    "statistic",
    "date",
    "plant-status",
    "toggle-group",
    "shortcut",
    "alert",
    "iframe",
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

# What each native card draws exactly as HA does: the engine's declaration, which NativeOptions.kt documents.
NATIVE_OPTIONS_FILE = (Path(__file__).resolve().parents[4]
                       / "engines/engine-vitro/src/main/resources/dev/glassworks/engine/vitro/compat/native-options.json")
NATIVE_OPTIONS = json.loads(NATIVE_OPTIONS_FILE.read_text(encoding="utf-8"))

# Values a reason may name: a key, a type, an enum value. Never a path, a URL or an entity id.
SAFE = re.compile(r"^[A-Za-z0-9_:+\-]{1,48}$")

# A number written as text, as all three checkers read one (no hex, `NaN`, `Infinity` or `_`).
NUMBER = re.compile(r"^\s*[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?\s*$")


class _Walk:
    """One check against the declaration (NativeOptions.Walk): the reasons it found, worded for `subject`."""

    def __init__(self, subject: str):
        self.subject = subject
        self.reasons: list[str] = []

    def fail(self, path: str, detail=None, why=None):
        where = f" `{path}`" if path else ""
        r = f"{self.subject}{where}" + (f" ({detail})" if detail is not None else "") + " isn't drawn natively" + (f": {why}" if why else "")
        if r not in self.reasons:
            self.reasons.append(r)

    @staticmethod
    def resolve(spec):
        hops = 0
        while isinstance(spec, str) and spec.startswith("@"):
            spec = NATIVE_OPTIONS["defs"][spec[1:]]
            hops += 1
            if hops > 16:
                raise ValueError("native-options.json: a def refers to itself")
        return spec

    def check(self, value, raw_spec, path: str, extra):
        spec = self.resolve(raw_spec)
        if spec == "any":
            return
        if spec == "text":
            if isinstance(value, (dict, list)):
                self.fail(path, "an object or a list")
            return
        if not isinstance(spec, dict):
            raise ValueError(f"native-options.json: unknown spec {spec!r} at {path}")
        why = spec.get("why") or None
        if "gap" in spec:
            self.fail(path, why=spec["gap"])
        elif "values" in spec:
            if not any(_same(v, value) for v in spec["values"]):
                self.fail(_join(path, _shown(value)), why=why)
        elif "pattern" in spec:
            if not isinstance(value, str) or not re.search(spec["pattern"], value):
                self.fail(path, why=why)
        elif "range" in spec:
            lo, hi = spec["range"]
            n = None
            try:
                if isinstance(value, (int, float)) and not isinstance(value, bool):
                    n = float(value)
                elif isinstance(value, str) and NUMBER.match(value):
                    n = float(value.strip())
            except OverflowError:
                n = None  # a number too large for a double, as Kotlin and JavaScript read it: not finite
            if n is None or not math.isfinite(n) or (lo is not None and n < lo) or (hi is not None and n > hi):
                # Never the value itself: a number that isn't one may be any text of the dashboard's.
                self.fail(path, "not a number in range", why)
        elif "entity" in spec:
            domain = value.split(".", 1)[0].strip() if isinstance(value, str) and "." in value else ""
            if not domain or not any(d in ("*", domain) for d in spec["entity"]):
                self.fail(path, f"a `{domain}` entity" if domain and SAFE.match(domain) else "this entity", why)
        elif "keys" in spec:
            if not isinstance(value, dict):
                return self.fail(path, "not an object", why)
            for k in spec.get("required", []):
                if value.get(k) is None:
                    self.fail(f"{path}.{k}" if path else k, "missing", why)
            for k, v in value.items():
                if v is None:
                    continue
                sub = spec["keys"].get(k)
                if sub is None and extra is not None:
                    sub = extra.get(k)
                at = f"{path}.{k}" if path else str(k)
                if sub is None:
                    self.fail(at if isinstance(k, str) and SAFE.match(k) else _join(path, "an option"), why=why)
                else:
                    self.check(v, sub, at, None)
        elif "list" in spec:
            if isinstance(value, list):
                for i, item in enumerate(value):
                    if item is not None:
                        self.check(item, spec["list"], f"{path}[{i}]", None)
            else:
                self.check(value, spec["list"], path, None)
        elif "anyOf" in spec:
            tries = []
            for option in spec["anyOf"]:
                w = _Walk(self.subject)
                w.check(value, option, path, extra)
                tries.append(w)
            if any(not w.reasons for w in tries):
                return
            fit = next((i for i, o in enumerate(spec["anyOf"]) if self.fits(value, o)), 0)
            for r in tries[fit].reasons:
                if r not in self.reasons:
                    self.reasons.append(r)
        elif "by" in spec:
            if not isinstance(value, dict):
                return self.fail(path, "not an object", why)
            field = spec["by"]
            raw = value.get(field)
            if raw is None:
                picked = spec.get("default", "")
            elif isinstance(raw, bool):
                picked = "true" if raw else "false"
            elif isinstance(raw, (dict, list)):
                picked = json.dumps(raw)
            else:
                picked = str(raw)
            case = spec["cases"].get(picked)
            if case is None:
                self.fail(_join(f"{path}.{field}" if path else field, _shown(picked)), why=why)
            else:
                self.check(value, case, path, extra)
        else:
            raise ValueError(f"native-options.json: unknown spec {spec!r} at {path}")

    def fits(self, value, spec) -> bool:
        s = self.resolve(spec)
        scalar = not isinstance(value, (dict, list))
        if s == "any":
            return True
        if s == "text":
            return scalar
        if not isinstance(s, dict):
            return False
        if "keys" in s or "by" in s:
            return isinstance(value, dict)
        if "list" in s:
            return isinstance(value, list)
        if "anyOf" in s:
            return any(self.fits(value, o) for o in s["anyOf"])
        return scalar


def _shown(value):
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int) and abs(value) >= 10**24:
        return None  # str() of a very long int raises on Python 3.11+, and the reason wouldn't show it anyway
    if isinstance(value, (int, float)):
        return str(value) if len(str(value)) <= 24 else None
    if isinstance(value, str) and SAFE.match(value):
        return value
    return None


def _join(path: str, value) -> str:
    return path if value is None else value if not path else f"{path}: {value}"


def _same(a, b) -> bool:
    if isinstance(a, bool) or isinstance(b, bool):
        return a is b
    # Python compares an int and a float by value without converting either (no overflow): 1 == 1.0.
    return a == b


class _Inner(dict):
    """auto-entities' inner card: the fields Vitro writes into it aren't the user's options (WebFallback drops them)."""

    def __init__(self, base: dict, **written):
        super().__init__(base, **written)
        self.written = set(written)


def native_option_reasons(card: dict, ctype: str) -> list[str]:
    """What a native card's config holds that it doesn't draw as HA does (NativeOptions.cardReasons)."""
    spec = NATIVE_OPTIONS["cards"].get(ctype)
    if spec is None:
        return []
    if isinstance(card, _Inner):
        card = {k: v for k, v in card.items() if k not in card.written}
    w = _Walk(f"`{ctype}`")
    w.check(card, spec, "", NATIVE_OPTIONS["common"])
    return w.reasons


def view_option_reasons(view: dict) -> list[str]:
    """What a view's own config holds that Vitro doesn't draw as HA does (NativeOptions.viewReasons)."""
    w = _Walk("the view")
    w.check(view, NATIVE_OPTIONS["view"], "", None)
    return w.reasons


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
    "custom:swipe-card",
}


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
        if not isinstance(a.get("action"), str) or a["action"] not in BUTTON_CARD_ACTIONS:
            return f"a {key} type it doesn't run natively"
        if any(a.get(f) for f in ("repeat", "repeat_limit", "sound", "protect")):
            return f"a {key} it doesn't run natively"
        if isinstance(a.get("confirmation"), dict) and "exemptions" in a["confirmation"]:
            return f"{key} confirmation exemptions"
    if isinstance(cfg.get("confirmation"), dict) and "exemptions" in cfg["confirmation"]:
        return "confirmation exemptions"
    # button-card counts a timer down every second, which the engine doesn't draw (unless state_display replaces it).
    entity = cfg.get("entity")
    state_display = cfg.get("state_display")
    if isinstance(entity, str) and entity.split(".")[0] == "timer" and cfg.get("show_state") \
            and not (isinstance(state_display, str) and state_display):
        return "show_state on a timer (its countdown)"
    layout = cfg.get("layout")
    if layout is not None and (not isinstance(layout, str) or layout not in BUTTON_CARD_LAYOUTS):
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
# At-rules the renderer doesn't evaluate (@media, @supports) send the card off: only keyframes and @charset are
# harmless there. This coarse check also sends @keyframes off, as the animation it serves isn't drawn natively.
HARMLESS_AT_RULES = {"-webkit-keyframes", "charset"}
AT_RULE = re.compile(r"@([a-zA-Z-]+)")
# Jinja is masked before reading selectors, as the renderer replaces it first.
JINJA = re.compile(r"\{\{[\s\S]*?\}\}|\{%[\s\S]*?%\}|\{#[\s\S]*?#\}")


def strip_css_comments(css: str) -> str:
    """The CSS with each comment replaced by a space, as the browser reads it (ThemeResolver.stripCssComments).

    A slash-star inside a quoted string doesn't open a comment, so it can't hide the rules after it. A string ends at
    its closing quote or at a newline, a backslash and the character after it are copied as they are, and a comment
    that is never closed runs to the end.
    """
    if "/*" not in css:
        return css
    out = []
    quote = ""
    i = 0
    while i < len(css):
        c = css[i]
        if c == "\\" and i + 1 < len(css):
            out.append(css[i:i + 2])
            i += 2
            continue
        if quote:
            if c == quote or c == "\n":
                quote = ""
        elif c in "\"'":
            quote = c
        elif css.startswith("/*", i):
            end = css.find("*/", i + 2)
            out.append(" ")
            i = len(css) if end < 0 else end + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def at_rules(css: str) -> list[str]:
    """The names of the sheet's at-rules, as CardCompat.topLevelAtRules finds them.

    An @ counts only where a rule can start (the start of the sheet, or after a {, } or ;, whitespace aside) and
    outside quoted strings and parentheses, so url(/local/a@home.png) or [data-label="@media"] isn't one. Unlike the
    engine, this coarse check also counts one nested in a rule.
    """
    out = []
    parens = 0
    quote = ""
    rule_start = True
    i = 0
    while i < len(css):
        c = css[i]
        if c == "\\":
            i += 1
        elif quote:
            if c == quote or c == "\n":
                quote = ""
        elif c in "\"'":
            quote = c
        elif c == "(":
            parens += 1
        elif c == ")":
            parens = max(parens - 1, 0)
        elif c == "@" and parens == 0 and rule_start:
            m = AT_RULE.match(css, i)
            if m:
                out.append(m.group(1).lower())
        if not c.isspace():
            rule_start = not quote and c in "{};"
        i += 1
    return out


def rule_preludes(css: str) -> list[str]:
    """The text before each rule's {, as the engine's rule readers find it (ThemeResolver.cssRulesImportant).

    Quoted text and escapes aren't structure: a { or ; in a string ([data-label="a;b"]) neither opens a rule nor ends
    a selector. A prelude starts after the last {, } or ; before it (a bare declaration ahead of a rule isn't part of
    it), where a ; inside brackets or parentheses doesn't count.
    """
    out = []
    quote = ""
    nested = 0
    start = 0
    i = 0
    while i < len(css):
        c = css[i]
        if c == "\\":
            i += 2
            continue
        if quote:
            if c == quote or c == "\n":
                quote = ""
        elif c in "\"'":
            quote = c
        elif c in "([":
            nested += 1
        elif c in ")]":
            nested = max(nested - 1, 0)
        elif c == "{":
            out.append(css[start:i])
            start = i + 1
            nested = 0
        elif c == "}" or (c == ";" and nested == 0):
            start = i + 1
            nested = 0
        i += 1
    return out


def mask_css(css: str) -> str:
    """The CSS without comments and Jinja: what the renderer reads rules from."""
    return JINJA.sub(" ", strip_css_comments(css))


def reaches_inside(card_type: str, css: str) -> bool:
    """A rule in the card's own sheet for something inside it (ha-card .card-header): not applied natively."""
    if card_type in ("markdown", "clock"):
        return False
    text = mask_css(css)
    for prelude in rule_preludes(text):
        for sel in prelude.split(","):
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
        if not isinstance(css, str):
            return True
        # Read without comments and Jinja, as the engine reads the sheet.
        text = mask_css(css)
        return ("$:" in text or "::" in text or LEGACY_PSEUDO.search(text) is not None
                or any(at not in HARMLESS_AT_RULES for at in at_rules(text)))
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
    """What sends a custom card Vitro draws to its island (CardCompat.checkOptions): the strict rule, then its own checks."""
    out = list(native_option_reasons(card, ctype))
    if ctype == "custom:button-card":
        reason = button_card_reason(card, TEMPLATES)
        if reason:
            out.append(f"button-card with {reason}")
    if ctype == "custom:swipe-card":
        reason = swipe_card_reason(card)
        if reason:
            out.append(f"swipe-card with {reason}")
    if ctype == "custom:weather-card" and not (isinstance(card.get("entity"), str) and card["entity"]):
        out.append("weather-card without an entity")
    return out


def as_list(value) -> list:
    """A list as the engine reads one (optJSONArray): anything else is no list."""
    return value if isinstance(value, list) else []


def produced_cards(card: dict, ctype: str, per_entity: bool) -> list[tuple[str, int, dict]]:
    """The cards a card builds one level down (CardRouter.producedCards): a report label, the index, the card."""
    out = []
    if ctype in CONTAINER_TYPES and ctype != "conditional":
        # Each child keeps its index among the parsed cards, also when a layout-break before it isn't built.
        kids = list(enumerate(c for c in as_list(card.get("cards")) if isinstance(c, dict)))
        if ctype == "custom:layout-card" and str(card.get("layout_type") or "").removeprefix("custom:") in ("vertical-layout", "vertical"):
            kids = [(i, c) for i, c in kids if card_type_of(c) != "custom:layout-break"]
        out = [(f"{ctype}[{i}]", i, c) for i, c in kids]
    elif ctype == "conditional" and isinstance(card.get("card"), dict):
        out = [("conditional[0]", 0, card["card"])]
    if ctype != "custom:auto-entities":
        return out
    # As the runtime builds it: a copy, `type: entities` when it names none, without the field `card_param` fills.
    # Only top-level keys change, so a shallow copy keeps the config (and its YAML aliases) as written.
    inner = dict(card["card"]) if isinstance(card.get("card"), dict) else {}
    inner.setdefault("type", "entities")
    # As the engine reads it (optString): missing or empty is `entities`, anything else as text.
    raw_param = card.get("card_param")
    param = raw_param if isinstance(raw_param, str) and raw_param else (
        "entities" if raw_param in (None, "") else json.dumps(raw_param))
    inner.pop(param, None)
    # Its explicit `entities:` entries are always among them: the strict rule checks them as the inner card's own, as
    # the engine's WebFallback does. Under `cards`, each is a card.
    if param != "cards" and isinstance(card.get("entities"), list):
        inner = _Inner(inner)
        inner[param] = card["entities"]
    generated = []
    if param == "cards":
        for entry in as_list(card.get("entities")):
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
        inner = _Inner(inner, cards=generated)
    out.append(("card", len(out), inner))
    if per_entity:
        out += [(f"entities[{i}]", len(out) + i, g) for i, g in enumerate(generated)]
    return out


def needs_ha_frontend(card: dict) -> bool:
    """A built-in card, or a card that builds one at any depth: the island would draw those as plain summaries."""
    ctype = card_type_of(card)
    return (not ctype.startswith("custom:") or ctype == "custom:stack-in-card"
            or any(needs_ha_frontend(c) for _, _, c in produced_cards(card, ctype, True)))


CONFIG_ROUTES = {"automation", "scene", "script", "tools", "integrations", "devices", "entities"}


def _opt_string(o, key: str, fallback: str = "") -> str:
    """A field as the engine reads it (optString): missing or null is the fallback, anything else as text."""
    v = o.get(key) if isinstance(o, dict) else None
    if v is None:
        return fallback
    return v if isinstance(v, str) else json.dumps(v)


def known_navigation(path: str) -> bool:
    """ShortcutDefaults.navigation: a path whose default label and icon Vitro knows without HA's frontend."""
    if not path:
        return True
    segments = re.split(r"[/?]", path.removeprefix("/"))
    panel = segments[0]
    sub = segments[1] if len(segments) > 1 else None
    at = lambda i: segments[i] if len(segments) > i else None  # noqa: E731
    if panel == "config" and sub == "areas" and at(2) == "area" and at(3):
        return True
    if panel == "config" and sub == "devices" and at(2) == "device" and at(3):
        return True
    if panel == "config" and sub in CONFIG_ROUTES:
        return True
    return sub is not None and sub.startswith("areas-")


def own_frontend_reasons(card: dict, ctype: str) -> list[str]:
    """WebFallback's own card rules: a `shortcut` whose default label or icon only HA's frontend knows (a page's title,
    an action's name, Assist), and an `iframe` of HA's own pages, which a loopback island page can't frame."""
    if ctype == "iframe":
        url = _opt_string(card, "url").strip()
        return [] if re.match(r"[A-Za-z][A-Za-z0-9+.\-]*://", url) else ["an `iframe` card of a Home Assistant page"]
    if ctype != "shortcut":
        return []
    if any(isinstance(card.get(k), dict) and card[k].get("action") == "assist" for k in ("tap_action", "hold_action")):
        return ["a `shortcut` card that opens Assist"]
    if _opt_string(card, "label") and _opt_string(card, "icon"):
        return []
    tap = card.get("tap_action") if isinstance(card.get("tap_action"), dict) else None
    action = _opt_string(tap, "action", "none") if tap is not None else "none"
    if action in ("perform-action", "call-service"):
        return ["a `shortcut` card that shows its action's name or icon"]
    if action == "navigate" and not known_navigation(_opt_string(tap, "navigation_path")):
        return ["a `shortcut` card that shows the page it opens by its title or icon"]
    return []


def walk_card(card: dict, loc: str, counts: dict[str, int], triggers: list[str], warnings: list[str], explicit: int = -1):
    if not isinstance(card, dict):
        return
    ctype = card_type_of(card)
    if card.get("type") in (None, ""):
        warnings.append(f"  WARN  {loc}: no type, so Vitro draws it as an entities card")

    if ctype in NATIVE_CORE_TYPES or (ctype in NATIVE_HACS_TYPES and needs_ha_frontend(card)):
        # The island can't draw it: what its native card doesn't draw as HA does sends the view to HA's frontend.
        frontend = own_frontend_reasons(card, ctype) + native_option_reasons(card, ctype)
        if ctype == "markdown":
            frontend += [f"Markdown <{t.lower()}> isn't drawn natively"
                         for t in re.findall(r"<([a-zA-Z][a-zA-Z0-9-]*)", str(card.get("content") or ""))
                         if t.lower() in UNDRAWN_MARKDOWN_TAGS]
        if frontend:
            counts["tier3"] += 1
            triggers.append(f"  TIER 3 [HA FRONTEND]  {loc}: type='{ctype}': {'; '.join(dict.fromkeys(frontend))}")
            return
        reasons = [r for r in option_reasons(card, ctype) if r not in frontend]
        cm = card_mod_reason(card, ctype)
        if cm:
            reasons.append(cm)
    elif ctype in NATIVE_HACS_TYPES:
        reasons = option_reasons(card, ctype)
        cm = card_mod_reason(card, ctype)
        if cm:
            reasons.append(cm)
    elif ctype.startswith("custom:"):
        reasons = ["a custom card with no native renderer"]
    else:
        # A built-in card Vitro has no renderer for (`map`, the energy cards): the island can't draw it, so its view
        # opens in HA's frontend (WebFallback).
        counts["tier3"] += 1
        triggers.append(f"  TIER 3 [HA FRONTEND]  {loc}: type='{ctype}': the built-in `{ctype}` card")
        return

    if ctype == "iframe" and not reasons:
        # The view stays native, but the frame's own page runs in a GeckoView island.
        counts["tier3"] += 1
        triggers.append(f"  TIER 3 [GECKOVIEW]  {loc}: type='iframe': its page runs in a GeckoView island (the view stays native)")
        return

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
    if ctype == "custom:auto-entities":
        # The cards a filter rule's `options` build aren't listed (they depend on live states), but the engine routes
        # them, so one Vitro can't draw sends the view to HA's frontend or itself to an island.
        # The explicit entries come first, and the inner card's walk below lists them.
        entries = sum(isinstance(e, (str, dict)) for e in as_list(card.get("entities"))) if card.get("card_param") == "cards" else 0
        # Every generated card at any depth: one that needs HA's frontend decides, whatever comes before it.
        found: list[str] = []
        for label, _, gen in produced_cards(card, ctype, True)[len(children) + entries:]:
            sub_triggers: list[str] = []
            walk_card(gen, f"{loc}.generated", {"tier1": 0, "tier2": 0, "tier3": 0}, sub_triggers, [])
            found += sub_triggers
        frontend = next((t for t in found if "[HA FRONTEND]" in t), None)
        if frontend:
            # The whole view goes to HA's frontend: nothing else in the card changes that.
            counts["tier3"] += 1
            triggers.append(frontend)
            return
        if found:
            # An island for a generated card; the inner card and its explicit entries are still checked below, since
            # one of them may send the view to HA's frontend.
            counts["tier3"] += 1
            triggers.append(found[0])
        # Its rows are picked at run time, so no config check can claim them: the engine checks what it matched.
        warnings.append(f"  WARN  {loc}: {MATCHED_ROWS_NOTE}")
    if explicit >= 0 and ctype in CONTAINER_TYPES and ctype != "conditional":
        # auto-entities' inner card: only its explicit `entities:` entries are known before it runs.
        for _, i, child in children:
            if i < explicit:
                walk_card(child, f"{loc}.entities[{i}]", counts, triggers, warnings)
        return
    if ctype == "custom:button-card":
        expanded = expand_button_card(card, TEMPLATES) or card
        if expanded.get("styles") or expanded.get("state"):
            warnings.append(f"  WARN  {loc}: button-card styles are drawn natively only in Vitro's subset (see the site checker)")
    if not (ctype in CONTAINER_TYPES and children):
        counts["tier1" if ctype in NATIVE_CORE_TYPES else "tier2"] += 1
    # Under `card_param: cards`, the inner card's first cards are the explicit `entities:` entries (none without a list).
    holds = len(as_list(card.get("entities"))) if ctype == "custom:auto-entities" and card.get("card_param") == "cards" else -1
    for label, _, child in children:
        walk_card(child, f"{loc}.{label}", counts, triggers, warnings, holds)


# The engine's WebFallback.MATCHED_ROWS_NOTE (and the site checker's): what a native auto-entities card is native on.
MATCHED_ROWS_NOTE = ("auto-entities picks its rows at run time: the view is native if the rows it matches are, and a row "
                     "Vitro doesn't draw natively opens the view in Home Assistant's frontend")


def view_type(view: dict) -> str:
    """The view's type as the engine infers it."""
    sections = [s for s in view.get("sections") or [] if isinstance(s, dict)]
    # `panel` as org.json's optBoolean reads it: true, or the text "true" in any case.
    panel = view.get("panel") is True or (isinstance(view.get("panel"), str) and view["panel"].lower() == "true")
    return view.get("type") or ("panel" if panel else "sections" if sections else "masonry")


def view_roots(view: dict, vname: str) -> list[tuple[str, dict]]:
    """The cards the engine builds for a view: a panel view's first card, a sections view's sections, or its cards."""
    sections = [s for s in view.get("sections") or [] if isinstance(s, dict)]
    vtype = view_type(view)
    cards = view.get("cards") or []
    objs = [(i, c) for i, c in enumerate(cards) if isinstance(c, dict)]
    if vtype == "panel" and objs:
        return [(f"{vname}/card[{objs[0][0]}]", objs[0][1])]
    if vtype == "sections" and sections:
        return [(f"{vname}/section[{si}]/card[{ci}]", c) for si, s in enumerate(sections) for ci, c in enumerate(s.get("cards") or [])]
    return [(f"{vname}/card[{ci}]", c) for ci, c in enumerate(cards)]


def audit(data: dict) -> int:
    global TEMPLATES
    TEMPLATES = data.get("button_card_templates") if isinstance(data, dict) and isinstance(data.get("button_card_templates"), dict) else {}
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

        own = view_option_reasons(view)
        if own:
            vcounts["tier3"] += 1
            vtriggers.append(f"  TIER 3 [HA FRONTEND]  {vname}: {'; '.join(own)}")
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
