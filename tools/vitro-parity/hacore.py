#!/usr/bin/env python3
"""HA core's entity texts (English, and the LANGUAGES below) and icons for the parity harness's fake Home Assistant (MIT; the data itself is
HA core's, Apache-2.0).

The frontend doesn't carry the words a card shows for a state ("Heat/Cool", "Armed away") or the icon of a state:
Home Assistant's backend sends them (`frontend/get_translations`, `frontend/get_icons`) from each integration's
compiled `translations/en.json` and `icons.json`. This script takes them from the `homeassistant` wheel on PyPI,
pinned by version and SHA-256 (the release that ships the frontend build Clarity pins, hafrontend.py), and writes
the part the fake answers with to hacore-<version>.json next to it, which is committed. Rerun it for a newer HA.

Touches: downloads the wheel once into the cache Clarity uses (GW_PERF_CACHE, default ~/.cache/glassworks-perf/dl),
reads it as a zip (nothing in it is run), and writes tools/vitro-parity/hacore-<version>.json.

  python3 tools/vitro-parity/hacore.py
"""
import hashlib
import json
import os
import shutil
import sys
import urllib.request
import zipfile

VERSION = "2026.11.0"
WHEEL = f"homeassistant-{VERSION}-py3-none-any.whl"
SHA256 = "7ca5fbde9dea879ac7723949fc84347aac5182e6309c357b2e4973fd391b00f7"
URL = ("https://files.pythonhosted.org/packages/28/0a/10c900142dfff3c8bda64e75d0077519c4ee9a3e0f18aabc67c023ac079f/"
       + WHEEL)
LICENSE = "Apache-2.0"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, f"hacore-{VERSION}.json")
CACHE = os.environ.get("GW_PERF_CACHE") or os.path.join(os.path.expanduser("~"), ".cache", "glassworks-perf")

# Translation categories the frontend asks for while it draws a dashboard (entity_component: the domains' state
# words; entity: an integration's own names for its entities, by translation key), and `title` (integration names).
CATEGORIES = ("entity_component", "entity", "title")
# The languages the corpus's cases may set as the user's (a fixture's `user_data.language.language`), besides
# English: their texts come from each integration's translations/<language>.json, in the same categories.
LANGUAGES = ("de", "fr")
# Integrations whose own entity texts (category `entity`) and icons the fixtures may use as a platform. The entity
# domains' texts (entity_component) are always all in.
PLATFORMS = ("demo", "template", "sun", "met", "person", "local_calendar", "local_todo", "shopping_list",
             "input_boolean", "input_number", "input_select", "input_text", "input_button", "input_datetime",
             "group", "switch_as_x", "zone", "mqtt", "esphome", "zha", "hue", "sonos", "cast")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def wheel_path():
    os.makedirs(os.path.join(CACHE, "dl"), exist_ok=True)
    path = os.path.join(CACHE, "dl", WHEEL)
    if not os.path.exists(path):
        tmp = path + ".part"
        with urllib.request.urlopen(URL, timeout=600) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f)
        os.replace(tmp, path)
    got = sha256(path)
    if got != SHA256:
        raise SystemExit(f"{WHEEL}: SHA-256 {got}, expected {SHA256}; not used (delete it to fetch again)")
    return path


def extract(z):
    """{"translations": {component: {category: tree}}, "icons": {component: {category: tree}}}: every entity domain's
    entity_component texts and icons, and the PLATFORMS' own entity texts and icons."""
    translations, icons = {}, {}
    localized = {lang: {} for lang in LANGUAGES}
    for name in sorted(z.namelist()):
        parts = name.split("/")
        if len(parts) < 4 or parts[0] != "homeassistant" or parts[1] != "components":
            continue
        comp = parts[2]
        if parts[3:] == ["translations", "en.json"]:
            d = json.loads(z.read(name))
            keep = {c: d[c] for c in CATEGORIES if c in d and (c != "entity" or comp in PLATFORMS)}
            if "entity_component" in d or comp in PLATFORMS:
                translations[comp] = keep
        elif len(parts) == 5 and parts[3] == "translations" and parts[4][:-5] in LANGUAGES and parts[4].endswith(".json"):
            d = json.loads(z.read(name))
            keep = {c: d[c] for c in CATEGORIES if c in d and (c != "entity" or comp in PLATFORMS)}
            if keep:
                localized[parts[4][:-5]][comp] = keep
        elif parts[3:] == ["icons.json"]:
            d = json.loads(z.read(name))
            keep = {c: d[c] for c in ("entity_component", "entity") if c in d and (c != "entity" or comp in PLATFORMS)}
            if keep:
                icons[comp] = keep
    # Icons only for components that have texts here too (an entity domain or a listed platform).
    icons = {k: v for k, v in icons.items() if k in translations}
    localized = {lang: {k: v for k, v in comps.items() if k in translations} for lang, comps in localized.items()}
    return {"translations": translations, "icons": icons, "localized": localized}


def main():
    with zipfile.ZipFile(wheel_path()) as z:
        data = extract(z)
    doc = {"_comment": f"HA core {VERSION}'s entity texts and icons (homeassistant/components/*/translations/en.json, "
                       f"the LANGUAGES' <language>.json under 'localized', and icons.json, {LICENSE}), cut to what the parity harness's fake HA answers with. Written by "
                       "tools/vitro-parity/hacore.py from the PyPI wheel (SHA-256 pinned there); don't edit by hand.",
           "version": VERSION, "wheel_sha256": SHA256, **data}
    with open(OUT, "w") as f:
        json.dump(doc, f, indent=None, separators=(",", ":"), sort_keys=True, ensure_ascii=False)
        f.write("\n")
    print(f"{OUT}: {len(data['translations'])} components, {os.path.getsize(OUT) // 1024} KiB")


if __name__ == "__main__":
    sys.exit(main())
