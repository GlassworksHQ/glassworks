#!/usr/bin/env python3
"""Guard the pinned GlassworksHQ/glassworks-device-bins artifacts.

The main repo ships no GPL source or GPL binaries (Hard Rule 4). The GPL device binaries it needs —
the Mirror's `selinux_permissive` backup module, and in future others — live in the separate
`glassworks-device-bins` repository and are consumed here strictly by pinned release URL + SHA-256.

Two files hold that pin and must never drift apart:

  * `tools/mirror/device-bins.json`           — what the consuming tool (tools/mirror) fetches.
  * `kiln/workers/refract/allowlist.json`     — the Refract allowlist Refract streams from.

This check makes sure every consumer pin is backed by a matching, well-formed allowlist entry
(offline). With `--verify-release` it also asks GitHub whether the pinned release actually serves
that exact size + SHA-256 (used once the release is cut; a draft or missing release is reported, not
silently passed).

Exit status is non-zero if anything is inconsistent.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PIN_FILE = REPO / "tools" / "mirror" / "device-bins.json"
ALLOWLIST_FILE = REPO / "kiln" / "workers" / "refract" / "allowlist.json"

DEVICE_BINS_OWNER = "GlassworksHQ"
DEVICE_BINS_REPO = "glassworks-device-bins"
REFRACT_BASE = "https://refract.glassworks.dev"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text())
    except FileNotFoundError:
        sys.exit(f"missing {path.relative_to(REPO)}")
    except json.JSONDecodeError as e:
        sys.exit(f"{path.relative_to(REPO)} is not valid JSON: {e}")


def check_pin(name: str, pin: dict, allowlist: dict, problems: list[str]) -> None:
    where = f"device-bins.json:{name}"

    required = ["component", "allowlistId", "refractUrl", "releaseUrl", "name", "size", "sha256", "license"]
    missing = [k for k in required if k not in pin]
    if missing:
        problems.append(f"{where}: missing fields {', '.join(missing)}")
        return

    component = pin["component"]
    allowlist_id = pin["allowlistId"]
    sha = pin["sha256"]
    size = pin["size"]

    if not SHA256_RE.match(sha):
        problems.append(f"{where}: sha256 is not 64 lowercase hex digits")
    if not isinstance(size, int) or size <= 0:
        problems.append(f"{where}: size must be a positive integer (got {size!r})")

    # The allowlist key is `<repo>/<tag>/<name>`; the tag is `<component>-v<N>`.
    parts = allowlist_id.split("/")
    if len(parts) != 3 or parts[0] != DEVICE_BINS_REPO:
        problems.append(f"{where}: allowlistId must be '{DEVICE_BINS_REPO}/<tag>/<name>' (got {allowlist_id!r})")
        return
    _, tag, asset_name = parts
    if asset_name != pin["name"]:
        problems.append(f"{where}: allowlistId name {asset_name!r} != pin name {pin['name']!r}")
    if not re.match(rf"^{re.escape(component)}-v[0-9]+$", tag):
        problems.append(f"{where}: tag {tag!r} must be '{component}-v<N>'")

    expect_refract = f"{REFRACT_BASE}/{allowlist_id}"
    if pin["refractUrl"] != expect_refract:
        problems.append(f"{where}: refractUrl should be {expect_refract!r}")
    expect_release = (
        f"https://github.com/{DEVICE_BINS_OWNER}/{DEVICE_BINS_REPO}/releases/download/{tag}/{asset_name}"
    )
    if pin["releaseUrl"] != expect_release:
        problems.append(f"{where}: releaseUrl should be {expect_release!r}")

    entry = allowlist.get(allowlist_id)
    if entry is None:
        problems.append(f"{where}: allowlistId {allowlist_id!r} is not in {ALLOWLIST_FILE.relative_to(REPO)}")
        return
    for field, want in (("owner", DEVICE_BINS_OWNER), ("repo", DEVICE_BINS_REPO), ("tag", tag), ("name", asset_name)):
        if entry.get(field) != want:
            problems.append(f"allowlist[{allowlist_id}]: {field} is {entry.get(field)!r}, expected {want!r}")
    for field in ("size", "sha256"):
        if entry.get(field) != pin[field]:
            problems.append(f"allowlist[{allowlist_id}]: {field} {entry.get(field)!r} != pin {pin[field]!r}")


def verify_release(name: str, pin: dict, problems: list[str]) -> None:
    """Ask GitHub whether the pinned release serves exactly this size + SHA-256 (needs `gh` + network)."""
    tag = pin["allowlistId"].split("/")[1]
    try:
        out = subprocess.run(
            ["gh", "release", "view", tag, "--repo", f"{DEVICE_BINS_OWNER}/{DEVICE_BINS_REPO}",
             "--json", "assets,isDraft"],
            capture_output=True, text=True, check=True,
        ).stdout
    except FileNotFoundError:
        problems.append(f"--verify-release: `gh` not found")
        return
    except subprocess.CalledProcessError:
        problems.append(f"device-bins.json:{name}: release {tag} not found on {DEVICE_BINS_OWNER}/{DEVICE_BINS_REPO} (cut it before pinning)")
        return
    data = json.loads(out)
    if data.get("isDraft"):
        problems.append(f"device-bins.json:{name}: release {tag} is still a draft (verify on hardware, then publish before trusting the pin)")
    asset = next((a for a in data.get("assets", []) if a.get("name") == pin["name"]), None)
    if asset is None:
        problems.append(f"device-bins.json:{name}: release {tag} has no asset {pin['name']!r}")
        return
    if asset.get("size") != pin["size"]:
        problems.append(f"device-bins.json:{name}: release {tag} asset is {asset.get('size')} B, pin says {pin['size']}")
    digest = (asset.get("digest") or "").removeprefix("sha256:")
    if digest and digest != pin["sha256"]:
        problems.append(f"device-bins.json:{name}: release {tag} asset SHA-256 {digest} != pin {pin['sha256']}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--verify-release", action="store_true",
                    help="also confirm each pinned release exists and serves the pinned size + SHA-256 (needs gh + network)")
    args = ap.parse_args()

    pins = load_json(PIN_FILE)
    allowlist = load_json(ALLOWLIST_FILE)

    problems: list[str] = []
    checked = 0
    for name, pin in pins.items():
        if name.startswith("_"):
            continue
        checked += 1
        check_pin(name, pin, allowlist, problems)
        if args.verify_release and not problems:
            verify_release(name, pin, problems)

    if problems:
        print("device-bins pin check FAILED:", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    print(f"device-bins pin check OK ({checked} pin{'s' if checked != 1 else ''})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
