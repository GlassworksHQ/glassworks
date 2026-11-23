#!/usr/bin/env python3
"""Count LineageOS's supported devices by install method, branch and form factor (#748).

Reads public data only: the device files in LineageOS's wiki
(github.com/LineageOS/lineage_wiki, `_data/devices/*.yml`). Touches no device and writes nothing
but its output. With --fetch it makes a shallow, sparse clone of the wiki into a temporary
directory and deletes it afterwards; with --wiki it reads a checkout you already have.

  python3 tools/kiln/lineage-devices.py --fetch            # Markdown tables, as in docs/design/kiln/lineageos.md
  python3 tools/kiln/lineage-devices.py --wiki ~/lineage_wiki --json

Needs PyYAML (`pip install pyyaml`). The method families and what Kiln needs for each are this
script's reading of the wiki's install templates (`_includes/templates/recovery_install_<method>.md`)
on 2026-11-23; check them again when the wiki adds a method.
"""
from __future__ import annotations

import argparse
import collections
import json
import pathlib
import subprocess
import sys
import tempfile

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("needs PyYAML: pip install pyyaml")

WIKI = "https://github.com/LineageOS/lineage_wiki.git"

# install_method -> (family, how the bootloader unlocks). Families:
#   fastboot  the device's bootloader speaks fastboot over USB (Kiln has fastboot and adb over WebUSB)
#   samsung   Samsung's Download mode (the Odin protocol)
#   other     a method of its own (see OTHER below)
# Unlock, for fastboot methods:
#   kiln      a fastboot command alone, after the OEM unlocking switch (Kiln can send it)
#   vendor    needs a token, code, file, app or account from the vendor first (Kiln can't get it)
#   closed    the vendor stopped unlocking; the wiki says only already-unlocked devices can install
#   done      the bootloader ships unlocked
#   device    on the device itself: the OEM unlocking switch, then a key press in Download mode (Samsung)
# A file with `is_unlockable: false` counts as `closed` in the family table, whatever its method:
# the wiki says no official unlock exists for it.
METHODS: dict[str, tuple[str, str]] = {
    "fastboot_nexus": ("fastboot", "kiln"),
    "fastboot_generic": ("fastboot", "kiln"),
    "fastboot_nubia": ("fastboot", "kiln"),
    "fastboot_unlocked": ("fastboot", "done"),
    "fastboot_custom": ("fastboot", "vendor"),
    "fastboot_fairphone": ("fastboot", "vendor"),
    "fastboot_htc": ("fastboot", "vendor"),
    "fastboot_lenovo": ("fastboot", "vendor"),
    "fastboot_motorola": ("fastboot", "vendor"),
    "fastboot_oneplus_tmo": ("fastboot", "vendor"),
    "fastboot_oplus": ("fastboot", "vendor"),
    "fastboot_oppo": ("fastboot", "vendor"),
    "fastboot_realme_china": ("fastboot", "vendor"),
    "fastboot_sony": ("fastboot", "vendor"),
    "fastboot_xiaomi": ("fastboot", "vendor"),
    "fastboot_xiaomi_hyperos": ("fastboot", "vendor"),
    "fastboot_huawei": ("fastboot", "closed"),
    "fastboot_lg": ("fastboot", "closed"),
    "fastboot_nokia": ("fastboot", "closed"),
    "fastboot_realme": ("fastboot", "closed"),
    "fastboot_zenfone": ("fastboot", "closed"),
    "fastboot_zte": ("fastboot", "closed"),
    "samloader_rs": ("samsung", "device"),
    "odin_revision": ("samsung", "device"),
    "dd": ("other", "-"),
    "oor": ("other", "-"),
    "edl_custom": ("other", "-"),
    "apx": ("other", "-"),
    "amlogic_update": ("other", "-"),
    "nintendo": ("other", "-"),
}

TABLETS = {"tablet"}
PHONES = {"phone", "phone (slider)", "foldable"}


def fetch(dest: pathlib.Path) -> pathlib.Path:
    repo = dest / "lineage_wiki"
    subprocess.run(["git", "clone", "-q", "--depth", "1", "--filter=blob:none", "--sparse", WIKI, str(repo)], check=True)
    subprocess.run(["git", "-C", str(repo), "sparse-checkout", "set", "_data/devices"], check=True)
    return repo


def load(repo: pathlib.Path) -> tuple[list[dict], str]:
    files = sorted((repo / "_data" / "devices").glob("*.yml"))
    if not files:
        sys.exit(f"no device files under {repo}/_data/devices")
    rows = []
    for f in files:
        d = yaml.safe_load(f.read_text(encoding="utf-8"))
        rows.append({
            "file": f.name,
            "codename": d["codename"],
            "method": d["install_method"],
            "branch": str(d["current_branch"]),
            "type": d["type"],
            "vendor": d.get("vendor", ""),
            "maintained": bool(d.get("maintainers")),
            "unlockable": d.get("is_unlockable", True) is not False,
            "ab": bool(d.get("is_ab_device")),
            "models": d.get("models") or [],
        })
    try:
        head = subprocess.run(["git", "-C", str(repo), "log", "-1", "--format=%h %cs"],
                              capture_output=True, text=True, check=True).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        head = "unknown"
    return rows, head


def form(t: str) -> str:
    return "tablet" if t in TABLETS else "phone" if t in PHONES else "other"


def summary(rows: list[dict], head: str) -> dict:
    unknown = sorted({r["method"] for r in rows} - METHODS.keys())
    live = [r for r in rows if r["maintained"]]
    by_method = collections.defaultdict(lambda: collections.Counter())
    for r in rows:
        c = by_method[r["method"]]
        c["entries"] += 1
        if r["maintained"]:
            c["maintained"] += 1
            c[f"maintained_{form(r['type'])}"] += 1
            if not r["unlockable"]:
                c["maintained_not_unlockable"] += 1
    fam = collections.defaultdict(collections.Counter)
    for r in rows:
        family, unlock = METHODS.get(r["method"], ("unknown", "-"))
        if not r["unlockable"] and family != "other":
            unlock = "closed"
        key = f"{family}:{unlock}"
        c = fam[key]
        c["entries"] += 1
        if r["maintained"]:
            c["maintained"] += 1
            c[f"maintained_{form(r['type'])}"] += 1
    return {
        "wiki": head,
        "entries": len(rows),
        "codenames": len({r["codename"] for r in rows}),
        "codenames_with_variants": sum(1 for v in collections.Counter(r["codename"] for r in rows).values() if v > 1),
        "maintained": len(live),
        "maintained_codenames": len({r["codename"] for r in live}),
        "type": dict(collections.Counter(r["type"] for r in rows).most_common()),
        "type_maintained": dict(collections.Counter(r["type"] for r in live).most_common()),
        "branch_maintained": dict(sorted(collections.Counter(r["branch"] for r in live).items(), reverse=True)),
        "methods": {m: dict(c) for m, c in sorted(by_method.items(), key=lambda kv: -kv[1]["maintained"])},
        "families": {k: dict(v) for k, v in sorted(fam.items())},
        "tablets_maintained": sorted({
            (f"{r['vendor']} {r['codename']}", r["method"], r["branch"]) for r in live if r["type"] in TABLETS}),
        "unknown_methods": unknown,
    }


def markdown(s: dict) -> str:
    out = [f"LineageOS wiki at {s['wiki']}: {s['entries']} device files, {s['codenames']} codenames "
           f"({s['codenames_with_variants']} with more than one file), {s['maintained']} files maintained "
           f"({s['maintained_codenames']} codenames).", ""]
    out += ["| Family | Unlock | Files | Maintained | Tablets | Phones | Other |", "|---|---|---|---|---|---|---|"]
    for k, c in s["families"].items():
        family, _, unlock = k.partition(":")
        out.append(f"| {family} | {unlock} | {c['entries']} | {c.get('maintained', 0)} | "
                   f"{c.get('maintained_tablet', 0)} | {c.get('maintained_phone', 0)} | {c.get('maintained_other', 0)} |")
    out += ["", "| install_method | Files | Maintained | Tablets | Phones | Other | Maintained, not unlockable |",
            "|---|---|---|---|---|---|---|"]
    for m, c in s["methods"].items():
        out.append(f"| `{m}` | {c['entries']} | {c.get('maintained', 0)} | {c.get('maintained_tablet', 0)} | "
                   f"{c.get('maintained_phone', 0)} | {c.get('maintained_other', 0)} | {c.get('maintained_not_unlockable', 0)} |")
    out += ["", "| Type | Files | Maintained |", "|---|---|---|"]
    for t, n in s["type"].items():
        out.append(f"| {t} | {n} | {s['type_maintained'].get(t, 0)} |")
    out += ["", "| current_branch (maintained) | Files |", "|---|---|"]
    out += [f"| {b} | {n} |" for b, n in s["branch_maintained"].items()]
    out += ["", "Maintained tablets:", ""]
    out += [f"- {name}: `{m}`, {b}" for name, m, b in s["tablets_maintained"]]
    if s["unknown_methods"]:
        out += ["", f"Methods this script doesn't know yet: {', '.join(s['unknown_methods'])}"]
    return "\n".join(out)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--wiki", type=pathlib.Path, help="a lineage_wiki checkout")
    src.add_argument("--fetch", action="store_true", help="shallow-clone the wiki into a temporary directory")
    p.add_argument("--json", action="store_true", help="JSON instead of Markdown")
    a = p.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        repo = fetch(pathlib.Path(tmp)) if a.fetch else a.wiki
        rows, head = load(repo)
    s = summary(rows, head)
    print(json.dumps(s, indent=2) if a.json else markdown(s))


if __name__ == "__main__":
    main()
