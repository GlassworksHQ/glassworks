#!/usr/bin/env python3
"""The community cards the parity harness's fake Home Assistant serves as Lovelace resources (MIT; each card keeps
its own licence, listed below and in THIRD-PARTY-NOTICES.md).

Each card is pinned by release and by the SHA-256 of every file it loads (cards.json, committed). The files
themselves are never committed: they are downloaded on first use into a cache outside the repo
(GW_PERF_CACHE, default ~/.cache/glassworks-perf/cards/<name>-<version>/) and refused when a hash differs. Nothing in
them runs on this computer; the fake only hands them to the browser, as HACS's `/hacsfiles/` does.

  python3 tools/vitro-parity/cards.py pin       # resolve CARDS' files and hash them into cards.json (network)
  python3 tools/vitro-parity/cards.py fetch     # download and check every pinned file into the cache

Touches: the network (GitHub releases and raw files), the cache folder, and, for `pin`, cards.json.
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PINS = os.path.join(HERE, "cards.json")
CACHE = os.path.join(os.environ.get("GW_PERF_CACHE") or os.path.join(os.path.expanduser("~"), ".cache", "glassworks-perf"), "cards")

# name: the folder HACS would install it to (/hacsfiles/<name>/); main: the module the resource points at; files:
# the release's assets to keep (every asset whose name ends with one of `assets`), or `raw` files from the repo at
# the tag, for cards that publish no release asset (HACS takes those from the repo).
CARDS = [
    {"name": "lovelace-card-mod", "repo": "thomasloven/lovelace-card-mod", "tag": "v4.2.1", "license": "MIT",
     "main": "card-mod.js", "raw": ["card-mod.js"]},
    {"name": "lovelace-mushroom", "repo": "piitaya/lovelace-mushroom", "tag": "v5.3.0", "license": "Apache-2.0",
     "main": "mushroom.js", "assets": ["mushroom.js"]},
    {"name": "stack-in-card", "repo": "custom-cards/stack-in-card", "tag": "0.2.0", "license": "MIT",
     "main": "stack-in-card.js", "assets": ["stack-in-card.js"]},
    {"name": "lovelace-layout-card", "repo": "thomasloven/lovelace-layout-card", "tag": "v2.4.7", "license": "MIT",
     "main": "layout-card.js", "raw": ["layout-card.js"]},
    {"name": "lovelace-auto-entities", "repo": "thomasloven/lovelace-auto-entities", "tag": "v1.16.1", "license": "MIT",
     "main": "auto-entities.js", "raw": ["auto-entities.js"]},
    {"name": "mini-graph-card", "repo": "kalkih/mini-graph-card", "tag": "v0.13.0", "license": "MIT",
     "main": "mini-graph-card-bundle.js", "assets": ["mini-graph-card-bundle.js"]},
    {"name": "mini-media-player", "repo": "kalkih/mini-media-player", "tag": "v1.16.12", "license": "MIT",
     "main": "mini-media-player-bundle.js", "assets": ["mini-media-player-bundle.js"]},
    {"name": "Bubble-Card", "repo": "Clooos/Bubble-Card", "tag": "v3.4.3", "license": "MIT",
     "main": "bubble-card.js", "assets": ["bubble-card.js", "bubble-pop-up-fix.js"]},
    {"name": "vertical-stack-in-card", "repo": "ofekashery/vertical-stack-in-card", "tag": "v1.1.4", "license": "MIT",
     "main": "vertical-stack-in-card.js", "assets": ["vertical-stack-in-card.js"]},
    {"name": "bar-card", "repo": "custom-cards/bar-card", "tag": "3.2.0", "license": "MIT",
     "main": "bar-card.js", "assets": ["bar-card.js"]},
    # Its module imports a chunk and loads its weather icons (Meteocons, MIT) next to itself: every .js and .svg.
    {"name": "clock-weather-card", "repo": "pkissling/clock-weather-card", "tag": "v3.1.0", "license": "MIT",
     "main": "clock-weather-card.js", "assets": [".js", ".svg"]},
    {"name": "modern-circular-gauge", "repo": "selvalt7/modern-circular-gauge", "tag": "v0.15.0", "license": "MIT",
     "main": "modern-circular-gauge.js", "assets": ["modern-circular-gauge.js"]},
    # The tablet's blank island (#885). AGPL-3.0: downloaded into the cache for the harness only, never committed or
    # shipped in an app. On demand: served at /hacsfiles/ but not a Lovelace resource of the fake, so the other cases'
    # pages don't load it (it bundles its own Mushroom elements and wraps customElements.define).
    {"name": "better-thermostat-ui-card", "repo": "KartoffelToby/better-thermostat-ui-card", "tag": "3.4.0", "license": "AGPL-3.0",
     "main": "better-thermostat-ui-card.js", "assets": ["better-thermostat-ui-card.js"], "on_demand": True},
    # Popular island cards (docs/research/vitro-island-census.md), for island-check.mjs and island-probe.mjs: which of
    # HA's own elements each draws with. On demand, so no parity case's page loads them.
    {"name": "battery-state-card", "repo": "maxwroc/battery-state-card", "tag": "v4.3.0", "license": "MIT",
     "main": "battery-state-card.js", "assets": ["battery-state-card.js"], "on_demand": True},
    {"name": "scheduler-card", "repo": "nielsfaber/scheduler-card", "tag": "v4.0.20", "license": "Apache-2.0",
     "main": "scheduler-card.js", "assets": ["scheduler-card.js"], "on_demand": True},
    # Its editor chunks load only in HA's card editor, which neither probe opens.
    {"name": "calendar-card-pro", "repo": "alexpfau/calendar-card-pro", "tag": "v6.0.0", "license": "MIT",
     "main": "calendar-card-pro.js", "assets": ["calendar-card-pro.js"], "on_demand": True},
]


def _download(url, dest):
    tmp = dest + ".part"
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "glassworks-vitro-parity"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f)
    except urllib.error.URLError as e:
        # A Python without a CA store (python.org's macOS build before "Install Certificates"): curl has the system's.
        if "CERTIFICATE_VERIFY_FAILED" not in str(e) or not shutil.which("curl"):
            raise
        subprocess.run(["curl", "-fsSL", "--max-time", "300", "-o", tmp, url], check=True)
    os.replace(tmp, dest)


def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def load():
    with open(PINS) as f:
        return json.load(f)["cards"]


def resources(pins=None):
    """The Lovelace resources an HA with these cards installed through HACS lists: one module per card, but for the
    cards pinned `on_demand` (served at /hacsfiles/ for a page that loads them itself, not a resource)."""
    pins = pins or load()
    return [{"id": c["name"], "type": "module", "url": f"/hacsfiles/{c['name']}/{c['main']}?hacstag={c['version']}"}
            for c in pins if not c.get("on_demand")]


def path(name, file, pins=None):
    """The cached, checked file `file` of card `name` (downloaded on first use), or None if it isn't pinned."""
    pins = pins or load()
    card = next((c for c in pins if c["name"] == name), None)
    pin = card and card["files"].get(file)
    if not pin:
        return None
    dest = os.path.join(CACHE, f"{name}-{card['version']}", file)
    if not os.path.exists(dest):
        _download(pin["url"], dest)
    got = _sha256(dest)
    if got != pin["sha256"]:
        os.remove(dest)
        raise RuntimeError(f"{name}/{file}: SHA-256 {got}, expected {pin['sha256']}; deleted")
    return dest


def _release_assets(repo, tag):
    out = subprocess.run(["gh", "api", f"repos/{repo}/releases/tags/{tag}", "--jq",
                          "[.assets[] | {name, url: .browser_download_url}]"], check=True, capture_output=True, text=True)
    return json.loads(out.stdout)


def pin():
    cards = []
    for c in CARDS:
        files = {}
        if "raw" in c:
            wanted = [{"name": f, "url": f"https://raw.githubusercontent.com/{c['repo']}/{c['tag']}/{f}"} for f in c["raw"]]
        else:
            wanted = [a for a in _release_assets(c["repo"], c["tag"]) if any(a["name"] == p or (p.startswith(".") and a["name"].endswith(p)) for p in c["assets"])]
        for a in sorted(wanted, key=lambda a: a["name"]):
            dest = os.path.join(CACHE, f"{c['name']}-{c['tag']}", a["name"])
            if not os.path.exists(dest):
                _download(a["url"], dest)
            files[a["name"]] = {"url": a["url"], "sha256": _sha256(dest)}
        if c["main"] not in files:
            raise SystemExit(f"{c['name']}: {c['main']} isn't among the pinned files")
        cards.append({"name": c["name"], "repo": c["repo"], "version": c["tag"], "license": c["license"],
                      "main": c["main"], "files": files, **({"on_demand": True} if c.get("on_demand") else {})})
        print(f"{c['name']} {c['tag']} ({c['license']}): {len(files)} file(s)")
    with open(PINS, "w") as f:
        json.dump({"_comment": "Community cards the parity harness's fake HA serves at /hacsfiles/, pinned by release and "
                               "SHA-256 (tools/vitro-parity/cards.py pin). The files are downloaded to a cache, never committed.",
                   "cards": cards}, f, indent=1)
        f.write("\n")


def fetch():
    pins = load()
    for c in pins:
        for file in c["files"]:
            path(c["name"], file, pins)
        print(f"{c['name']} {c['version']}: {len(c['files'])} file(s) checked")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "pin":
        pin()
    elif cmd == "fetch":
        fetch()
    else:
        raise SystemExit(__doc__)
