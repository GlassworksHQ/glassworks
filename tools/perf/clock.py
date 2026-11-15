"""Clarity's clock (MIT): every wait and timestamp of the runner and its off-device fakes goes through it.

A real run uses real seconds: now() is time.time(), sleep() is time.sleep(). The off-device tests set
GW_PERF_TIME_SCALE (0 < scale <= 1) to compress the runs: every wait takes `scale` times as long, and
the clock reads 1/scale as fast, so recorded times and durations keep their units and the tests their
numbers. A scaled clock is refused on a real device (check_device), so no graded run is ever scaled.

What isn't on this clock: run.sh's own sleeps and polls (the tests set its settle and gap times to
0, and it never waits to cool on the fake), and the gesture times in a run's file (gestures_sent),
which stay on fakeha.jsonl's real clock.

  python3 tools/perf/clock.py --check    # run.sh, first: exits 2 if the clock is scaled on a real device
"""
import os
import subprocess
import sys
import time

# Only test/fake_adb.py answers this property; a real device has no such property and answers "".
FAKE_PROP = "gw.perf.fake_device"


def _scale():
    raw = os.environ.get("GW_PERF_TIME_SCALE", "1")
    try:
        s = float(raw)
    except ValueError:
        s = 0.0
    if not 0 < s <= 1:
        sys.exit(f"GW_PERF_TIME_SCALE={raw!r}: give a number above 0 and at most 1")
    return s


SCALE = _scale()


def now():
    """Wall-clock seconds, as time.time(), on the scaled clock."""
    return time.time() / SCALE


def mono():
    """Monotonic seconds, as time.monotonic(), on the scaled clock."""
    return time.monotonic() / SCALE


def sleep(seconds):
    time.sleep(seconds * SCALE)


def real(seconds):
    """A duration in real seconds, for what waits by itself (Event.wait, Timer, queue.get, sockets)."""
    return seconds * SCALE


def check_device(getprop):
    """Exit unless the clock is real or the device is the off-device fake. getprop(name) -> value."""
    if SCALE != 1 and getprop(FAKE_PROP).strip() != "1":
        sys.exit(f"GW_PERF_TIME_SCALE={SCALE:g} is for the off-device tests only; "
                 "this is a real device, and Clarity runs on one in real time")


def _adb_getprop(name):
    adb = os.environ.get("ADB", "adb")
    try:
        return subprocess.run([adb, "shell", f"getprop {name}"], capture_output=True, text=True, timeout=30).stdout
    except (OSError, subprocess.TimeoutExpired):
        return ""


if __name__ == "__main__" and sys.argv[1:] == ["--check"]:
    # A real clock asks the device nothing, so a real run is the same as before the scale.
    try:
        check_device(_adb_getprop)
    except SystemExit as e:
        if e.code:
            print(e.code, file=sys.stderr)
            sys.exit(2)
