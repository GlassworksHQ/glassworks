#!/usr/bin/env python3
"""Clarity debugging helper (MIT, #634): which SurfaceFlinger layers the runner reads frames from on a device.

READ-ONLY. It runs `dumpsys SurfaceFlinger --list` and `dumpsys SurfaceFlinger --latency '<name>'` over adb,
nothing else: no tap, no intent, no setting. Use it before a graded run on a new device or a new Android, while
the app shows a page (its SurfaceView exists only then). It names the app's SurfaceView layers the way the runner
does (perflib.surfaceview_layers), and for each prints how many presented frames SurfaceFlinger keeps. It reads
them twice, --wait seconds apart, and counts the frames new in the second read: scroll the page by hand meanwhile
(or send your own `input swipe`) and they should be there. Writes nothing.

  tools/perf/debug/sf_layers.py [--package dev.glassworks.generic_android] [--wait 5]

The device is adb's default, or ANDROID_SERIAL (as run.sh --serial sets it); ADB names another adb binary.
Prints layer names with the package shown as <pkg>, and no serial or address.
"""
import argparse
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import perflib as P  # noqa: E402


def sh(cmd):
    """The command's output on the device. A failed adb call (no device, a dropped link) stops here, so it can't
    read as "no layer" or "no new frames"."""
    r = subprocess.run([os.environ.get("ADB", "adb"), "shell", cmd], capture_output=True, text=True, timeout=60)
    if r.returncode != 0:
        sys.exit(f"adb shell {cmd.split(' --')[0]!r} failed (exit {r.returncode}): {r.stderr.strip()[-300:]}")
    return r.stdout


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--package", default="dev.glassworks.generic_android")
    ap.add_argument("--wait", type=float, default=5, help="seconds between the two reads (scroll meanwhile)")
    a = ap.parse_args()
    listing = sh("dumpsys SurfaceFlinger --list")
    names = P.surfaceview_layers(listing, a.package)
    mine = [l.strip() for l in listing.splitlines() if a.package in l]
    hide = lambda s: s.replace(a.package, "<pkg>")
    print(f"--list: {len(listing.splitlines())} lines, {len(mine)} of the app's")
    for l in mine:
        print("  " + hide(l))
    if not names:
        print("no SurfaceView of the app the runner can name: a run's windows would have no frames" +
              (" (is a page on screen?)" if not mine else ""))
        return 1
    first = {n: P.parse_sf_latency(sh(f"dumpsys SurfaceFlinger --latency '{n}'"))[1] for n in names}
    print(f"reading again in {a.wait:g} s: scroll the page now")
    time.sleep(a.wait)
    new_frames = 0
    for n in sorted(names, key=P.layer_number, reverse=True):
        out = sh(f"dumpsys SurfaceFlinger --latency '{n}'")
        period, pres = P.parse_sf_latency(out)
        new = [t for t in pres if t > max(first[n], default=0)]
        new_frames = max(new_frames, len(new))
        print(f"{hide(n)}: {len(out.splitlines())} lines, period {period} ms, {len(pres)} presented, {len(new)} new")
    if not new_frames:
        print("named, but no new frames on any layer: scroll during the wait and run it again")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
