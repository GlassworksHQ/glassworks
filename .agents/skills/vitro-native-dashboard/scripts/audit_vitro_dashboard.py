#!/usr/bin/env python3
"""Audit a Home Assistant Lovelace dashboard (YAML or JSON) for Vitro-native compatibility.

Exits 0 if the entire dashboard renders 100% natively in Vitro (zero Tier 3 GeckoView triggers),
or exits 1 if any card would boot GeckoView.
"""
import argparse
import json
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

CONTAINER_TYPES = {"grid", "horizontal-stack", "vertical-stack", "conditional"}


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


def check_card_mod(card: dict, loc: str, warnings: list[str]):
    cm = card.get("card_mod")
    if not isinstance(cm, dict):
        return
    style = cm.get("style")
    if isinstance(style, dict) or (isinstance(style, str) and ("$:" in style or "@keyframes" in style or "::" in style)):
        warnings.append(f"  WARN  {loc}: complex Shadow-DOM / pseudo / keyframe card_mod (only ha-card container styles mirror natively)")


def walk_card(card: dict, loc: str, counts: dict[str, int], triggers: list[str], warnings: list[str]):
    if not isinstance(card, dict):
        return
    ctype = str(card.get("type") or "").strip()
    check_card_mod(card, loc, warnings)

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
    else:
        counts["tier3"] += 1
        triggers.append(f"  TIER 3 [GECKOVIEW]  {loc}: type='{ctype or '<missing>'}'")


def audit(data: dict) -> int:
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
