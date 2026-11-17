#!/usr/bin/env python3
"""The icons of a built APK load on its oldest Android (#198, MIT). Read-only: runs aapt2 on the APK, writes nothing.

The legacy build's first start on the lululemon Mirror (Android 6, 2026-11-17) crashed in a loop: the service's
notification had the launcher icon as its small icon, an adaptive icon in mipmap-anydpi-v26 only, and SystemUI threw
"Couldn't create icon". The launcher and the home chooser showed Android's robot for the same reason. This fails
when the manifest's icon, its round icon, or a resource named on the command line has no version Android API
--api picks: every one of its configurations needs a -v qualifier above it, or a qualifier other than density.

  python3 tools/legacy/apk-icons.py APK [--api 23] [--aapt2 PATH] [type/name ...]
  python3 tools/legacy/apk-icons.py legacy.apk drawable/ic_notification

aapt2 is --aapt2, else $AAPT2, else the newest in $ANDROID_HOME/build-tools.
"""
import argparse
import glob
import os
import re
import subprocess
import sys

DENSITIES = {"ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi", "anydpi"}
MANIFEST_ICONS = ("icon", "roundIcon")


def loads_on(config, api):
    """Whether a configuration like "anydpi-v26" or "xhdpi" can be picked on a device at `api`, whatever its screen."""
    for q in filter(None, config.split("-")):
        version = re.fullmatch(r"v(\d+)", q)
        if version:
            if int(version.group(1)) > api:
                return False
        elif q not in DENSITIES and not re.fullmatch(r"\d+dpi", q):
            return False
    return True


def resources(dump):
    """{name: (id, [configs])} from `aapt2 dump resources`."""
    out, current = {}, None
    for line in dump.splitlines():
        m = re.match(r"\s+resource (0x[0-9a-f]+) (\S+)", line)
        if m:
            current = m.group(2)
            out[current] = (int(m.group(1), 16), [])
            continue
        c = re.match(r"\s+\(([^)]*)\) ", line)
        if c and current:
            out[current][1].append(c.group(1))
    return out


def manifest_icons(xmltree):
    """{attribute: resource id} of the <application> element's icon and roundIcon, from `aapt2 dump xmltree`."""
    app = xmltree.split("E: application", 1)
    if len(app) < 2:
        raise SystemExit("apk-icons: the manifest has no <application>")
    attrs = app[1].split("\n      E: ", 1)[0]
    found = {}
    for name in MANIFEST_ICONS:
        m = re.search(r"android:" + name + r"\(0x[0-9a-f]+\)=@(0x[0-9a-f]+)", attrs)
        if m:
            found[name] = int(m.group(1), 16)
    return found


def problems(res, manifest, named, api):
    """What fails: icons the manifest names and resources named on the command line, each with its configs."""
    by_id = {rid: name for name, (rid, _) in res.items()}
    wanted = []
    if "icon" not in manifest:
        wanted.append(("the manifest's icon", None))
    for attr, rid in manifest.items():
        wanted.append((f"the manifest's {attr}", by_id.get(rid, f"0x{rid:08x}")))
    wanted += [(name, name) for name in named]
    out = []
    for what, name in wanted:
        if name is None:
            out.append(f"{what}: none (Android shows its robot)")
        elif name not in res:
            out.append(f"{what}: {name} isn't in the APK")
        elif not any(loads_on(c, api) for c in res[name][1]):
            configs = ", ".join(f"({c})" for c in res[name][1])
            label = name if what == name else f"{what} ({name})"
            out.append(f"{label}: only {configs}, none of which API {api} picks")
    return out


def aapt2_path(given):
    if given:
        return given
    if os.environ.get("AAPT2"):
        return os.environ["AAPT2"]
    home = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    tools = sorted(glob.glob(os.path.join(home, "build-tools", "*", "aapt2")),
                   key=lambda p: [int(n) if n.isdigit() else n for n in re.split(r"[.-]", p.split(os.sep)[-2])])
    if not tools:
        raise SystemExit("apk-icons: no aapt2 (set AAPT2 or ANDROID_HOME)")
    return tools[-1]


def run(aapt2, *args):
    return subprocess.run([aapt2, *args], check=True, capture_output=True, text=True).stdout


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("apk")
    p.add_argument("named", nargs="*", help="more resources to check, as type/name")
    p.add_argument("--api", type=int, default=23)
    p.add_argument("--aapt2")
    a = p.parse_args(argv)
    aapt2 = aapt2_path(a.aapt2)
    res = resources(run(aapt2, "dump", "resources", a.apk))
    manifest = manifest_icons(run(aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", a.apk))
    bad = problems(res, manifest, a.named, a.api)
    for b in bad:
        print(f"::error::{b}" if os.environ.get("GITHUB_ACTIONS") else b, file=sys.stderr)
    if bad:
        return 1
    print(f"apk-icons: {', '.join(sorted(manifest) + a.named)} load on API {a.api}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
