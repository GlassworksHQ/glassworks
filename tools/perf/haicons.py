#!/usr/bin/env python3
"""HA core's icons for Clarity's fake Home Assistant (MIT; the icons themselves are HA core's, Apache-2.0).

HA core answers `frontend/get_icons` with the icons.json of each integration it has loaded
(homeassistant/helpers/icon.py at the pinned release): for a category such as `entity_component`,
the part of each file under that key, for the integrations the request names, or else every
top-level component. HA's frontend draws an entity's default icon from that answer (the bulb of a
light, a sensor's device-class icon) whenever a card names none, which is almost every card.

haicons.json keeps those files for the integrations the fake loads (dashboards.py `config()`, plus
`homeassistant`, which every HA loads), taken from HA core's open source at the release the fake
reports (fakeha.py HA_VERSION) and pinned by commit, with each file's SHA-256. The fake reads only
that file: it never fetches anything at test or run time. Nothing in it comes from anyone's install.

  python3 tools/perf/haicons.py           # check haicons.json against HA core's source (reads GitHub)
  python3 tools/perf/haicons.py --write   # write it again from HA core's source (after a bump below)

Touches no device; --check and --write read raw.githubusercontent.com, and --write writes only
haicons.json.
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request

CORE = "2026.11.0"
COMMIT = "74cf5a49c63ed8d4d87717844bf14f79c28c9cea"      # the tag 2026.11.0 of home-assistant/core
LICENSE = "Apache-2.0"
SOURCE = f"https://raw.githubusercontent.com/home-assistant/core/{COMMIT}/homeassistant/components/{{}}/icons.json"
# What frontend/get_icons takes at the pinned release (homeassistant/components/frontend/__init__.py,
# websocket_get_icons); HA refuses any other category as an invalid format.
CATEGORIES = ("conditions", "entity", "entity_component", "integration_category", "integration_connectivity",
              "services", "triggers")

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "haicons.json")


def components():
    """The integrations the fake's HA loads, as HA's top-level components: dashboards.py's
    `config()` components and `homeassistant`, the core integration every HA sets up first."""
    sys.path.insert(0, HERE)
    import dashboards
    return sorted(set(dashboards.config()["components"]) | {"homeassistant"})


def fetch(component):
    """(raw bytes, SHA-256) of a component's icons.json at the pinned commit, or (None, None) when it
    has none there (history and stream, for instance)."""
    try:
        with urllib.request.urlopen(SOURCE.format(component), timeout=60) as r:
            raw = r.read()
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None, None
        raise
    return raw, hashlib.sha256(raw).hexdigest()


def build(names=None):
    """haicons.json's content, from HA core's source."""
    out = {}
    for c in names or components():
        raw, digest = fetch(c)
        out[c] = None if raw is None else {"sha256": digest, "icons": json.loads(raw)}
    return {"_comment": "HA core's icons.json for each integration Clarity's fake Home Assistant loads, at the "
                        "release it reports, written by tools/perf/haicons.py --write; don't edit it by hand. "
                        "null: the integration has no icons.json at that commit. The icons are HA core's "
                        "(Apache-2.0, Copyright Home Assistant contributors).",
            "core": CORE, "commit": COMMIT, "license": LICENSE,
            "source": f"https://github.com/home-assistant/core/tree/{COMMIT}/homeassistant/components",
            "components": out}


def _service_icon(value):
    """HA's convert_shorthand_service_icon: a service's icon may be just the icon's name."""
    return {"service": value} if isinstance(value, str) else value


_loaded = None


def load():
    """{component: its icons.json as HA reads it}, with HA's shorthand service icons expanded
    (helpers/icon.py _load_icons_file). Components without a file aren't in it. Built whole, then
    kept in one assignment: the fake answers each connection on its own thread, and a request that
    came in meanwhile must never see some components and not others."""
    global _loaded
    if _loaded is None:
        with open(DATA, encoding="utf-8") as f:
            data = json.load(f)
        built = {}
        for c, entry in data["components"].items():
            if entry is None:
                continue
            icons = dict(entry["icons"])
            if "services" in icons:
                icons["services"] = {s: _service_icon(v) for s, v in icons["services"].items()}
            built[c] = icons
        _loaded = built
    return _loaded


def resources(category, integration=None, loaded=None):
    """frontend/get_icons' `resources`, as HA core builds them (helpers/icon.py async_get_icons and
    translation.py build_resources): the named integrations (a name or a list, as HA's
    `ensure_list` takes it), else every loaded one; each with its part of its file under
    `category`, left out when it has none. An integration HA has but the fake doesn't load answers
    nothing, as one without an icons.json does."""
    if integration:
        names = [integration] if isinstance(integration, str) else list(integration)
    else:
        names = loaded if loaded is not None else components()
    icons = load()
    return {c: icons[c][category] for c in names if c in icons and icons[c].get(category)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--write", action="store_true", help="write haicons.json from HA core's source")
    a = ap.parse_args()
    fresh = build()
    if a.write:
        with open(DATA, "w", encoding="utf-8", newline="\n") as f:
            json.dump(fresh, f, indent=1, ensure_ascii=False, sort_keys=True)
            f.write("\n")
        print(f"wrote {DATA}: {sum(v is not None for v in fresh['components'].values())} icons.json files "
              f"from home-assistant/core {CORE} ({COMMIT[:12]})")
        return 0
    with open(DATA, encoding="utf-8") as f:
        kept = json.load(f)
    if kept != fresh:
        diff = sorted(c for c in set(kept["components"]) | set(fresh["components"])
                      if kept["components"].get(c) != fresh["components"].get(c))
        print(f"haicons.json differs from home-assistant/core {CORE} for: {', '.join(diff) or 'its header'}; "
              f"run tools/perf/haicons.py --write", file=sys.stderr)
        return 1
    print(f"haicons.json matches home-assistant/core {CORE} ({COMMIT[:12]}), file for file")
    return 0


if __name__ == "__main__":
    sys.exit(main())
