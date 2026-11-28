#!/usr/bin/env python3
"""Device side of Clarity, the Glassworks perf test (MIT). Called by tools/perf/run.sh; see docs/perf.md.

What it touches on the device:
  activity   read-only: the app's activity, from `dumpsys activity recents` (Device.resolve_activity).
  device     read-only (getprop, /proc, dumpsys, wm size).
  Every step on Android 6 (no `logcat -v monotonic`) writes five `ClarityClock` lines to the device's log, twice
  in steps that record the clock's drift (Device.wall_clock).
  engine     The test hold (as `hold`), whose answer says which engine draws the page and the Vitro setting
             (Settings → Labs). With --want vitro|web: `am start --ez vitro true|false` first when the setting
             differs (the kiosk restarts for it), then checks. run.sh puts the setting back with --want on exit.
  testdash   `am start --es test_dashboard <url>` with the hold: the kiosk shows that loopback page instead of its
             saved dashboard while the hold lasts (graded part, --pane-ram); `--off` (`''`) ends it. No setting
             and no sign-in changes. Then reads logcat for the kiosk's answer.
  hold       `am start --ez test_hold true --ei test_hold_s <s>`, the kiosk's test hold (#240): the
             screen on (no setting changes), through restarts of the app, until it expires or
             `hold --off` (`--ez test_hold false`). Then reads logcat for the kiosk's answer.
  sfcheck    As synthetic, with page/sfcheck.html (it draws for 20 s, then stops); then reads SurfaceFlinger only.
  synthetic  `am start` the app if it isn't running, then the test hold, then `am start --es open
             <page>` (one-off navigation; the saved dashboard URL is not changed), and `input tap` on
             the test page's tap target when --taps > 0.
  coldstart  `input keyevent KEYCODE_WAKEUP` (the app's own wake intent would start it), then
             `am force-stop` the app. A home-app kiosk is relaunched by the system; otherwise
             `am start -W` starts it. Either way it comes back on the saved dashboard.
  refstart   As coldstart, with `input keyevent KEYCODE_WAKEUP` through the restart; once the kiosk's
             own page has finished, the test hold again before each view, and
             each reference dashboard in turn (opened by the fake Home Assistant; the saved URL is not changed),
             `input tap` on its toggle when --taps > 0, and `input swipe` where the page asks (only
             on our page, only for the run's nonce).
  idle       The test hold before the sample; otherwise read-only.
Everything it writes goes to --out on this machine. It records no URLs, hosts or tokens.
"""
import argparse
import json
import os
import queue
import urllib.parse
import urllib.request
import re
import secrets
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import clock as C  # noqa: E402
import perflib as P  # noqa: E402

FRAMES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "frames")


class Device:
    def __init__(self, serial, package, activity=""):
        self.adb = [os.environ.get("ADB", "adb")] + (["-s", serial] if serial else [])
        self.package = package
        self.activity = activity          # "" until resolve_activity(), on the first use of `component`
        self._clock = None
        self.nudges = []                  # wake keys sent and maybe not yet through adb (nudge, nudged)
        self.sf_warned = False            # said once that SurfaceFlinger listed no layer of the app's (sf)
        self.sf_name = None               # the layer sf() last read its frames from
        self.sf_period = None             # and its refresh period
        # The engine that draws the page (run.sh's --engine, from the kiosk's answer: cmd_engine): its frames are on
        # GeckoView's SurfaceView, or on Vitro's own window (perflib.app_layers).
        self.engine = os.environ.get("GW_PERF_ENGINE") or "web"

    def sh(self, cmd, timeout=60):
        r = subprocess.run(self.adb + ["shell", cmd], capture_output=True, text=True, timeout=timeout)
        return r.stdout

    @property
    def component(self):
        if not self.activity:
            self.activity = self.resolve_activity()
        return f"{self.package}/{self.activity}"

    def resolve_activity(self):
        """The activity the kiosk runs in, which takes Clarity's extras: the `realActivity` of the package's
        most recent task, as ActivityManager lists it (Android 6 and later). Not the launcher entry that
        `cmd package resolve-activity` or `pm dump` name: the generic app's is an alias
        (dev.glassworks.generic.Launcher) whose extras the app drops. A task stays listed after the app
        stops; one that never ran since the device booted has none, and --activity names it then."""
        out = self.sh("dumpsys activity recents")
        m = re.search(rf"realActivity=\{{?{re.escape(self.package)}/([\w.$]+)", out)
        if not m:
            sys.exit(f"{self.package} has no task on the device to read its activity from: start the app, "
                     f"or give --activity (.MainActivity on the S1, dev.glassworks.MainActivity on the generic app)")
        return m.group(1)

    @property
    def clock(self):
        """How this device's logcat stamps lines (perflib.LogClock), found once per runner process: `-v monotonic`
        where logcat has it, else (Android 6) the wall clock to the µs, tied to CLOCK_MONOTONIC."""
        if self._clock is None:
            probe = self.sh("logcat -d -v monotonic -t 1")
            self._clock = self.wall_clock() if "Invalid parameter" in probe or "Usage:" in probe else P.LogClock()
        return self._clock

    def wall_clock(self, pairs=5):
        """Android 6's logcat has no monotonic stamps. `pairs` times: CLOCK_MONOTONIC from /proc/timer_list
        (read by the shell itself, no process started), a log line, the clock again; the tightest pair ties
        logcat's wall clock to the monotonic one (perflib.wall_offset)."""
        nonce = secrets.token_hex(4)
        read = "{ read a; read b; read c; } < /proc/timer_list"
        out = self.sh(f"for i in $(seq 1 {pairs}); do {read}; m1=$c; log -t ClarityClock \"{nonce} $i\"; {read}; "
                      f"echo \"CLOCKPAIR $i $m1 $c\"; done; date +%Y")
        years = re.findall(r"^\s*(\d{4})\s*$", out, re.M)
        marks = self.sh("logcat -d -v usec -s ClarityClock")
        clock = P.wall_offset(out, marks, nonce, int(years[-1]) if years else time.gmtime().tm_year)
        if clock is None:
            sys.exit("this device's logcat has no -v monotonic, and its monotonic clock couldn't be tied to "
                     "logcat's wall clock (is /proc/timer_list readable?): " + out.strip()[-200:])
        return clock

    def clock_note(self):
        """For a run's file, on a wall-clock logcat: the offset's error, and how far the offset moved by the
        end (a network-time step during the run shows here). Nothing on a monotonic logcat."""
        if self.clock.fmt == "monotonic":
            return {}
        again = self.wall_clock()
        return {"logcat_clock": {"format": self.clock.fmt, "offset_err_ms": self.clock.err_ms,
                                 "end_err_ms": again.err_ms,
                                 "drift_ms": round((again.offset_s - self.clock.offset_s) * 1000, 3)}}

    def parse(self, line):
        return self.clock.parse(line)

    def log_dump(self, tail=None, timeout=60):
        """`logcat -d` on this device's clock (its last `tail` lines only, if given)."""
        return self.sh(f"logcat -d -v {self.clock.fmt}" + (f" -t {tail}" if tail else ""), timeout=timeout)

    def hold(self):
        """The kiosk's test hold again, for TEST_HOLD_S from now, before a part that may outlast what is
        left of it. run.sh checked the kiosk takes it (cmd_hold). It
        returns once adb has sent it, so it never lands in a view being measured."""
        self.sh(f"am start -n {self.component} {HOLD_ON}")

    def nudge(self):
        """Android's own wake key, for while the kiosk is stopped or starting: the kiosk's intent
        (wake) would launch it, and change the restart being measured."""
        self.nudges = [p for p in self.nudges if p.poll() is None]
        self.nudges.append(subprocess.Popen(self.adb + ["shell", "input keyevent KEYCODE_WAKEUP"],
                                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))

    def nudged(self, timeout=10):
        """Waits (at most `timeout` s in all) until every wake key sent is through adb, so none lands
        after what is sent next (#410's CI: a wake key sent just before a view's hold reached the
        device after it)."""
        end = time.monotonic() + timeout
        for p in self.nudges:
            try:
                p.wait(timeout=max(0.0, end - time.monotonic()))
            except subprocess.TimeoutExpired:
                pass
        self.nudges = [p for p in self.nudges if p.poll() is None]

    def logcat(self):
        p = subprocess.Popen(self.adb + ["logcat", "-v", self.clock.fmt, "-T", "1"],
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors="replace")
        p.stdout.readline()  # -T 1 starts with one old line
        return p

    def sf(self):
        """(period_ms, present times ns) of the app's SurfaceView, the layer GeckoView draws into (Vitro: the app's
        own window). After a restart an old layer can still be listed; the live one has the newest frame."""
        listing = self.sh("dumpsys SurfaceFlinger --list")
        names = P.app_layers(listing, self.package, self.engine)
        if not names and not self.sf_warned and f"{self.package}/" in listing:
            # The app has a window but no SurfaceView this runner can name: every window falls back to
            # rAF and the graded tasks that need frames on screen are n/a. Say it where the run is
            # watched, with the app's lines (a new Android's naming shows up here first).
            self.sf_warned = True
            mine = [l.strip() for l in listing.splitlines() if self.package in l][:6]
            print(f"warning: SurfaceFlinger listed a window of {self.package} but no SurfaceView this runner can read. "
                  "If a page was open, its windows have no frames (rAF only; open, restart and live updates n/a). "
                  "Its lines for the app:\n  " + "\n  ".join(mine), file=sys.stderr)
        best, self.sf_name = (None, []), None
        for name in sorted(names, key=P.layer_number, reverse=True):
            period, presents = P.parse_sf_latency(self.sh(f"dumpsys SurfaceFlinger --latency '{name}'"))
            if period and max(presents, default=0) > max(best[1], default=0):
                best, self.sf_name = (period, presents), name
            elif period and best[0] is None:
                best, self.sf_name = (period, presents), name
        self.sf_period = best[0]
        return best

    def sf_relist(self):
        """The poll rhythm's listing read (#643): `--list`, then `--latency` of the app's newest layer that has
        frames (the newest first: after a restart the new layer has the higher #id). Two calls, where sf() reads
        every layer listed. (period_ms, present times ns); sets sf_name."""
        names = P.app_layers(self.sh("dumpsys SurfaceFlinger --list"), self.package, self.engine)
        for name in sorted(names, key=P.layer_number, reverse=True):
            period, ts = P.parse_sf_latency(self.sh(f"dumpsys SurfaceFlinger --latency '{name}'"))
            if ts:
                self.sf_name, self.sf_period = name, period
                return period, ts
        return self.sf_period, []

    def sf_layer(self, name):
        """One `--latency` read of a layer sf() picked: its present times, ns (SfReads, during a window)."""
        return P.parse_sf_latency(self.sh(f"dumpsys SurfaceFlinger --latency '{name}'"))[1]

    def pids(self, names):
        """name -> pid for the app's processes and any process named in `names`."""
        return {name: pid for pid, name in P.parse_ps(self.sh("ps -A -o PID,NAME"))
                if name.startswith(self.package) or name in names}

    def ncpu(self):
        """Online CPUs: nproc, or (Android 6 has none) the cpuN lines of /proc/stat, which count the same."""
        out = self.sh("nproc 2>/dev/null || grep -c '^cpu[0-9]' /proc/stat").strip()
        return int(out or 0)


# Seconds between SurfaceFlinger reads during a window (#643), from run.sh's preflight (cmd_sfcheck): 0 reads
# once after each window, as on the S1; more on a device whose history is shorter than a window.
SF_POLL_S = float(os.environ.get("GW_PERF_SF_POLL") or 0)


# At the poll rhythm, a listing read (Device.sf_relist) every this many reads; the known layer alone in between.
# A new layer (after a restart) is then read within three reads and one listing read's time: 0.9 s plus at most half
# the history on the tablet (sf_plan holds a listing read to half of it).
SF_RELIST = 3


def sf_poll_read(dev, k):
    """The k-th read at the poll rhythm (#643): (period_ms, present times ns). One `--latency` of the layer last
    picked, as the window reads; a listing read (Device.sf_relist, two calls) every SF_RELIST reads, or when that
    layer answers nothing (gone); Device.sf before any layer is known. The check times both kinds (sf_history)."""
    if dev.sf_name is None:
        return dev.sf()
    if k % SF_RELIST == 0:
        return dev.sf_relist()
    ts = dev.sf_layer(dev.sf_name)
    return (dev.sf_period, ts) if ts else dev.sf_relist()


class SfReads:
    """SurfaceFlinger's history read during one window, every SF_POLL_S seconds (Clarity's clock, as the frames),
    from the layer sf() last picked. Nothing at all when SF_POLL_S is 0: one read after the window, as before.
    stop() gives the reads, oldest first; the read after the window (sf()) goes last (perflib.sf_window)."""

    def __init__(self, dev, poll_s=None):
        self.dev, self.poll_s = dev, SF_POLL_S if poll_s is None else poll_s
        self.reads, self.done, self.thread = [], threading.Event(), None
        if self.poll_s > 0:
            if self.dev.sf_name is None:
                self.dev.sf()
            self.thread = threading.Thread(target=self.run, daemon=True)
            self.thread.start()

    def run(self):
        # A read every poll_s from the last one's start: a slow read (wireless adb: up to 1.9 s once on the
        # tablet, run 3) is followed at once, not poll_s later.
        name, due = self.dev.sf_name, C.mono()
        while name and not self.done.is_set():
            self.reads.append(self.dev.sf_layer(name))
            due += self.poll_s
            self.done.wait(C.real(max(0.0, due - C.mono())))

    def stop(self):
        self.done.set()
        if self.thread:
            self.thread.join(timeout=30)
        return self.reads

    def window(self, start_s, end_s):
        """The window's `sf` record: these reads and one more just now (Device.sf)."""
        reads = self.stop()
        period, last = self.dev.sf()
        rec = P.sf_window(period, reads + [last], start_s, end_s)
        if self.poll_s > 0:
            rec["reads"] = len(reads) + 1        # detail (#643); not there when one read is all
        return rec


def write_json(path, obj):
    with open(path, "w") as f:
        json.dump(obj, f, indent=1)


# ---- device

def cmd_activity(dev, a):
    """Print the kiosk's component (package/activity), for run.sh's own intents."""
    print(dev.component)


def cmd_device(dev, a):
    props = {k: dev.sh(f"getprop {k}").strip() for k in (
        "ro.product.manufacturer", "ro.product.model", "ro.build.version.release", "ro.build.version.sdk",
        "ro.product.cpu.abi", "ro.product.cpu.abilist", "ro.board.platform", "ro.hardware")}
    cpuinfo = dev.sh("cat /proc/cpuinfo")
    parts = sorted(set(re.findall(r"CPU part\s*:\s*(\S+)", cpuinfo)))
    freqs = dev.sh("cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq 2>/dev/null").split()
    pkg = dev.sh(f"dumpsys package {dev.package}")
    apk_path = dev.sh(f"pm path {dev.package}").strip().replace("package:", "").splitlines()
    apk_bytes = dev.sh(f"stat -c %s {apk_path[0]}").strip() if apk_path else ""
    period, _ = dev.sf()
    size = re.search(r"(\d+)x(\d+)", dev.sh("wm size"))
    zones = dev.sh("for z in /sys/class/thermal/thermal_zone*; do echo $(cat $z/type) $(cat $z/temp); done 2>/dev/null")
    write_json(os.path.join(a.out, "device.json"), {
        "frame": a.frame, "frame_name": a.name or a.frame,
        # From the frame's data (design/installer.json `touch`): without touch nobody scrolls it, so the runner sends
        # no swipes and the scroll isn't graded (rubric.json no_touch, rubric 2).
        "touch": P.frame_touch(FRAMES, a.frame),
        # The engine that drew the page (run.sh --engine; cmd_engine): web (GeckoView) or vitro.
        "engine": dev.engine,
        "measured_on": time.strftime("%Y-%m-%d", time.gmtime()),   # UTC date of the run, from this computer
        "model": props["ro.product.model"], "manufacturer": props["ro.product.manufacturer"],
        "android": props["ro.build.version.release"], "api": int(props["ro.build.version.sdk"] or 0),
        "abi": props["ro.product.cpu.abi"], "abilist": props["ro.product.cpu.abilist"].split(","),
        "soc": props["ro.board.platform"] or props["ro.hardware"],
        "cpu": {"cores": dev.ncpu(), "parts": parts,
                "max_mhz": sorted({int(f) // 1000 for f in freqs if f.isdigit()})},
        "mem_total_kb": P.parse_proc_meminfo(dev.sh("cat /proc/meminfo"))["mem_total_kb"],
        "display": {"px": [int(size.group(1)), int(size.group(2))] if size else None,
                    "density": (re.findall(r"\d+", dev.sh("wm density")) or [None])[-1],
                    "vsync_ms": round(period, 3) if period else None},
        "app": {"package": dev.package,
                "version_name": (re.findall(r"versionName=(\S+)", pkg) or [None])[0],
                "version_code": (re.findall(r"versionCode=(\d+)", pkg) or [None])[0],
                "apk_bytes": int(apk_bytes) if apk_bytes.isdigit() else None},
        "thermal_c": {l.split()[0]: int(l.split()[1]) / 1000 for l in zones.splitlines()
                      if len(l.split()) == 2 and l.split()[1].lstrip("-").isdigit()},
        "loadavg": dev.sh("cat /proc/loadavg").split()[:3],
    })


# ---- synthetic: the test page

def cmd_synthetic(dev, a):
    path = os.path.join(a.out, f"synthetic-{a.run}.jsonl")
    # The kiosk applies `open` only to a running activity (onNewIntent), so start it first.
    if dev.package not in dev.pids(()):
        dev.sh(f"am start -n {dev.component}")
        C.sleep(20)
    dev.hold()
    lc = dev.logcat()
    dev.sh(f"am start -n {dev.component} --es open '{a.url}'")
    deadline = C.now() + a.timeout
    measure_mono, reads = None, None
    status = "timeout"
    seen = threading.Event()
    timer = threading.Timer(C.real(a.timeout), lc.terminate)
    timer.start()
    no_page = threading.Timer(C.real(90), lambda: None if seen.is_set() else lc.terminate())
    no_page.start()
    with open(path, "w") as f:
        lines_in = P.PageLines()
        for line in lc.stdout:
            rec = dev.parse(line)
            if rec and not seen.is_set() and LOCKDOWN_RE.search(rec["msg"]):
                status = "lockdown"
                break
            if rec and seen.is_set() and SLEEP_RE.search(rec["msg"]):
                # The screen went off while the test page ran: its frames aren't the panel's then.
                f.write(json.dumps({"t": "screen_off", "mono": rec["mono"]}) + "\n")
                f.flush()
            pl = lines_in.feed(rec["msg"]) if rec else None
            if not pl or pl.get("nonce") != a.nonce:
                continue
            pl["mono"] = rec["mono"]
            seen.set()
            t = pl.get("t")
            if t == "measure":
                measure_mono = rec["mono"]
                if reads:
                    reads.stop()
                reads = SfReads(dev)
            elif t == "step" and measure_mono is not None:
                # oldest_ms: how far back SurfaceFlinger's history reached when it was read (with the reads
                # during the window, #643, joined). If that is before the window, every frame of the window
                # is in it, and gaps are real.
                # The page's own window length: the step line itself lands a little after the window.
                end_mono = measure_mono + pl["window_ms"] / 1000 if pl.get("window_ms") else rec["mono"]
                pl["sf"] = (reads or SfReads(dev, 0)).window(measure_mono, end_mono)
                reads = None
            f.write(json.dumps(pl) + "\n")
            f.flush()
            print(f"  {t} {pl.get('stage', '')} {pl.get('n', '')}".rstrip(), file=sys.stderr)
            if t in ("done", "error"):
                status = t
                break
            if C.now() > deadline:
                break
    timer.cancel()
    no_page.cancel()
    lc.terminate()
    if reads:
        reads.stop()
    if status == "lockdown":
        print(f"synthetic run {a.run}: the app's lockdown refused the test page", file=sys.stderr)
        sys.exit(LOCKDOWN_EXIT)
    if not seen.is_set():
        status = "the test page sent nothing in 90 s (is the app in front, and the fake Home Assistant reachable: adb reverse up, or the LAN with --ha-transport lan?)"
    if status != "done":
        sys.exit(f"synthetic run {a.run}: {status}")


# ---- sfcheck: how SurfaceFlinger's history is read on this device (#643)

# Reads in a row, each with new frames and the same number of them: the history is full, and its span is how far
# one read reaches back.
SFCHECK_FULL_READS = 3
SFCHECK_GAP_S = 0.3        # between the check's reads
SFCHECK_LIST_READS = 5     # listing reads (Device.sf_relist) timed after them


def sf_history(dev, timeout, stop=None):
    """Reads the app's layer (dev.sf picks it) every SFCHECK_GAP_S until its history is full while new frames
    come, or holds enough frames for one read to cover a window, or `timeout` s pass, or `stop` is set.
    -> (layer name or None, refresh period ms, the most frames the history held, full reads in a row,
    each read's time in ms, each listing read's time in ms)."""
    deadline = C.mono() + timeout
    name, period, last, full, frames, times = None, None, None, 0, 0, []
    while C.mono() < deadline and not (stop and stop.is_set()):
        if name is None:
            period, _ = dev.sf()
            name = dev.sf_name
            if name is None:
                C.sleep(SFCHECK_GAP_S)
                continue
        t0 = C.mono()
        ts = dev.sf_layer(name)
        times.append((C.mono() - t0) * 1000)
        if ts and last and ts[-1] > last[-1]:            # new frames: the page draws
            # Full: no more frames than the most so far, read after read, while new ones come. Until then the
            # history still holds frames from before the page (an idle screen's, seconds apart), so its span
            # says nothing; its size does. (Rows never presented aren't counted, so a full history's count can
            # dip by one or two: an API 37 emulator's did, 2026-11-19.)
            full = full + 1 if len(ts) <= frames else 0
            frames = max(frames, len(ts))
            if full >= SFCHECK_FULL_READS or frames * period >= P.SF_ONE_READ_MS:
                break
        last = ts or last
        C.sleep(SFCHECK_GAP_S)
    # The poll rhythm's listing reads, timed too: sf_plan holds them to half the history as well.
    lists = []
    for _ in range(SFCHECK_LIST_READS if name else 0):
        t0 = C.mono()
        dev.sf_relist()
        lists.append((C.mono() - t0) * 1000)
    return name, period, frames, full, times, lists


def cmd_sfcheck(dev, a):
    """The preflight run.sh runs before a run: opens a page that draws every frame (page/sfcheck.html) and reads
    the app's SurfaceFlinger layer until its history is full, timing each read. sfcheck.json gets the numbers
    and perflib.sf_plan's decision; stdout the seconds between reads during a window (0: one read after it).
    Exits 2, before a run is spent, when the layer can't be read or its history is too short to cover a window
    even read often."""
    out = {"reach_ms": None, "frames": None, "full": None, "read_ms": None, "list_ms": None, "reads": 0, "poll_s": None,
           "why": None}
    path = os.path.join(a.out, "sfcheck.json")
    if dev.package not in dev.pids(()):
        dev.sh(f"am start -n {dev.component}")
        C.sleep(20)
    dev.hold()
    # The check page is a web page, which Vitro shows in its GeckoView fallback: its frames are on the SurfaceView. A
    # layer's history is SurfaceFlinger's own, the same length for the app's window on the same Android.
    dev.engine = "web"
    lc = dev.logcat()
    refused = threading.Event()

    def watch():
        for line in lc.stdout:
            rec = dev.parse(line)
            if rec and LOCKDOWN_RE.search(rec["msg"]):
                refused.set()
                return
    threading.Thread(target=watch, daemon=True).start()
    dev.sh(f"am start -n {dev.component} --es open '{a.url}'")
    name, period, frames, full, times, lists = sf_history(dev, a.timeout, refused)
    lc.terminate()
    out["reads"] = len(times)
    out["read_ms"] = round(P.nearest_rank(sorted(times), 0.95), 1) if times else None
    out["list_ms"] = round(P.nearest_rank(sorted(lists), 0.95), 1) if lists else None
    if refused.is_set():
        # The legacy engine's lockdown (#198) loads only its dashboard's origin: no check, one read as before.
        out.update(poll_s=0, why="the app's lockdown refused the check page; reading once after each window")
    elif name is None:
        out["why"] = f"SurfaceFlinger lists no SurfaceView of {dev.package} this runner can read (sf_layers.py, the debugging helper, prints its lines)"
    elif not frames:
        out["why"] = "the check page drew no new frames on the app's layer (is it on screen, and the fake Home Assistant reachable: adb reverse up, or the LAN with --ha-transport lan?)"
    elif full < SFCHECK_FULL_READS and frames * period < P.SF_ONE_READ_MS:
        out["why"] = (f"SurfaceFlinger's history never filled in {a.timeout:.0f} s ({frames} frames): the check page "
                      "didn't draw every frame (is it on screen?)")
    else:
        # How far back one read reaches when every frame is drawn, as in a swipe: the frames it keeps, a refresh
        # apart (a page that draws less often reaches further).
        # full: the whole history; else enough frames for one read and it stopped there (reach is at least this).
        out.update(reach_ms=round(frames * period, 1), frames=frames, full=full >= SFCHECK_FULL_READS)
        out["poll_s"], out["why"] = P.sf_plan(out["reach_ms"], out["read_ms"] or 0, out["list_ms"])
    write_json(path, out)
    if out["poll_s"] is None:
        print(f"SurfaceFlinger check failed: {out['why']} (sfcheck.json)", file=sys.stderr)
        sys.exit(2)
    how = "one read after each window" if out["poll_s"] == 0 else f"a read every {out['poll_s']:g} s during each window"
    print(f"SurfaceFlinger: one read reaches back {out['reach_ms']} ms ({out['frames']} frames), a read takes "
          f"{out['read_ms']} ms ({out['list_ms']} ms with the listing): {how}" if out["reach_ms"] is not None else f"SurfaceFlinger: {out['why']}", file=sys.stderr)
    print(out["poll_s"])


def parse_set(spec):
    """"voice_enabled=false,mic_muted=true,..." -> {extra: bool}."""
    out = {}
    for part in (spec or "").split(","):
        if part:
            k, _, v = part.partition("=")
            if not re.fullmatch(r"[a-z_]{1,40}", k) or v not in ("true", "false"):
                raise SystemExit(f"--set {part!r}: give extra=true|false")
            out[k] = v == "true"
    return out


def expected_state(want, voice_now):
    """What pane_state should read for `want`. The microphone is open only with voice on and not
    muted; when the kiosk didn't apply voice_enabled (the S1 app 0.4.3 saves it asynchronously, then
    exits to restart, so the write is lost), that is judged with the voice it really runs. Another
    pane's switch `<id>_enabled` shows as `pane:<id>` (#67: wall_base_enabled, pane:wall-base)."""
    voice = want.get("voice_enabled", voice_now)
    out = {}
    if "sendspin_enabled" in want:
        out["sendspin"] = want["sendspin_enabled"]
    for k, v in want.items():
        if k.endswith("_enabled") and k not in ("voice_enabled", "sendspin_enabled"):
            out["pane:" + k[:-len("_enabled")].replace("_", "-")] = v
    if "mic_muted" in want or "voice_enabled" in want:
        out["mic"] = bool(voice_now if voice_now is not None else voice) and not want.get("mic_muted", False)
    return out


def cmd_panes(dev, a):
    """Set the panes the kiosk runs, then check from its log that it runs them (#158's s1v7: every
    switch was lost). The settings applied live (the other panes' switches, the microphone mute, the
    test dashboard to measure on, --url) go first, in an intent that doesn't restart the app, and are saved while the runner
    waits; then voice_enabled and a restart, so each sample starts from a fresh process (a kiosk
    before #67 restarts for voice_enabled alone). Up to --tries times. Prints the result as JSON; exit
    0 when everything but voice_enabled took (voice_applied says whether that did too), else 1."""
    want = parse_set(a.set)
    live = " ".join(f"--ez {k} {'true' if v else 'false'}" for k, v in want.items() if k != "voice_enabled")
    # The page to measure on, as the kiosk's test dashboard ("" ends it): never its saved dashboard.
    url = f"{HOLD_ON} --es {TEST_DASHBOARD_EXTRA} '{a.url}'" if a.url is not None else ""
    res = {"want": want, "attempts": 0, "state": None, "ok": False, "voice_applied": None,
           "url_taken": None if a.url is None else False}
    for attempt in range(1, a.tries + 1):
        res["attempts"] = attempt
        if live or url:
            out = dev.sh(f"am start -W -n {dev.component} {url} {live}".strip(), timeout=60)
            if a.url is not None:
                # am exits 0 even when the kiosk didn't take the intent: read its answer (as run.sh does).
                res["url_taken"] = any(l.strip() == "Status: ok" for l in out.splitlines())
            C.sleep(PANES_SAVE_S)
        if "voice_enabled" in want:
            dev.sh(f"am start -W -n {dev.component} --ez voice_enabled {'true' if want['voice_enabled'] else 'false'} --ez restart true", timeout=60)
        else:
            dev.sh(f"am start -W -n {dev.component} --ez restart true", timeout=60)
        C.sleep(a.settle)
        st = P.pane_state(dev.log_dump())
        res["state"] = st
        exp = expected_state(want, st["voice"])
        res["ok"] = st["voice"] is not None and all(st.get(k) == v for k, v in exp.items()) and res["url_taken"] is not False
        res["voice_applied"] = None if "voice_enabled" not in want else st["voice"] == want["voice_enabled"]
        if res["ok"] and res["voice_applied"] is not False:
            break
        if res["ok"] and attempt >= 2:
            break                     # voice_enabled doesn't take on this kiosk; the rest did
    print(json.dumps(res))
    sys.exit(0 if res["ok"] else 1)


# The kiosk's test hold (#240): the screen on past the idle timeout for TEST_HOLD_S, and nothing else
# changed (brightness and night dimming stay as they are). It outlives a restart of the app (the S1's
# night run, 2026-11-16: without that, the new process's 20 s night timeout turned the screen off in
# every graded restart). The runner sends it again before each part and each view, so a long run
# never outlasts it. The app logs it as TEST_HOLD_RE.
TEST_HOLD_S = 1800
HOLD_ON = f"--ez test_hold true --ei test_hold_s {TEST_HOLD_S}"
TEST_HOLD_RE = re.compile(r"test hold (?:on|renewed) for \d+ s")


def cmd_hold(dev, a):
    """The kiosk's test hold, checked: exit 0 once its log says the hold is on, 1 if it says nothing
    within --timeout (an app before #240 ignores the extra). --off ends it, unchecked (exit trap)."""
    if a.off:
        dev.sh(f"am start -n {dev.component} --ez test_hold false")
        return
    said = kiosk_says(dev, f"am start -W -n {dev.component} {HOLD_ON}", TEST_HOLD_RE, a.timeout)
    if not said:
        sys.exit(1)
    # The engine the kiosk said it draws with, in the same answer (MainActivity logs it right after the hold): run.sh
    # measures Vitro with its own steps. None from an app that doesn't say (one before Clarity's Vitro check).
    # With it, the Vitro setting, which a Vitro run puts back however its switch ends.
    found = [m for m in (ENGINE_RE.search(l) for l in dev.log_dump(tail=200).splitlines()) if m]
    engine = None if not found else ("vitro" if found[-1].group(1).startswith("Vitro") else "web")
    setting = None if not found else found[-1].group(2) == "on"
    print(json.dumps({"hold_s": TEST_HOLD_S, "said": said, "engine": engine, "vitro_setting": setting}))


# The kiosk's answer to a test hold (MainActivity): the engine that draws the page (VitroEngine names itself "Vitro …"
# once it runs; GeckoView "GeckoView …") and the Vitro setting (Settings → Labs), which a Vitro run puts back.
ENGINE_RE = re.compile(r"test hold engine: (.*); vitro setting (on|off)")
VITRO_EXTRA = "vitro"


def engine_state(dev, timeout):
    """{name, engine (web or vitro), vitro_setting} from the kiosk's answer to the test hold, or None."""
    said = kiosk_says(dev, f"am start -W -n {dev.component} {HOLD_ON}", ENGINE_RE, timeout)
    m = ENGINE_RE.search(said or "")
    if not m:
        return None
    return {"name": m.group(1), "engine": "vitro" if m.group(1).startswith("Vitro") else "web",
            "vitro_setting": m.group(2) == "on"}


def cmd_engine(dev, a):
    """The engine the kiosk draws with, and its Vitro setting; with --want, switched to that engine first. Prints
    {name, engine, vitro_setting, was (the setting before), changed}: exit 0 when the kiosk runs the engine wanted
    (or, without --want, says which it runs), 1 when it doesn't say (an app from before this check), 2 when it didn't
    switch. The setting restarts the app (MainActivity: `--ez vitro` restarts it), so the runner waits --settle s."""
    now = engine_state(dev, a.timeout)
    if now is None:
        print(f"{dev.package} doesn't say which engine draws its page (install an app built after Clarity's Vitro check)",
              file=sys.stderr)
        sys.exit(1)
    out = dict(now, was=now["vitro_setting"], changed=False)
    if a.want:
        on = a.want == "vitro"
        if now["vitro_setting"] != on or now["engine"] != a.want:
            dev.sh(f"am start -n {dev.component} --ez {VITRO_EXTRA} {'true' if on else 'false'}")
            out["changed"] = True
            end = C.now() + a.timeout + a.settle
            C.sleep(a.settle)
            while True:
                got = engine_state(dev, a.timeout)
                if got and got["engine"] == a.want and got["vitro_setting"] == on:
                    out.update(got)
                    break
                if C.now() > end:
                    out.update(got or {})
                    print(json.dumps(out))
                    print(f"the kiosk didn't switch to {a.want} (it says {got and got['name']})", file=sys.stderr)
                    sys.exit(2)
                C.sleep(2)
    print(json.dumps(out))


# The kiosk's test dashboard: a loopback page it shows instead of its saved dashboard while the test hold
# lasts (ScreenPolicy.testDashboard). The saved dashboard and the sign-in stay as they are: pointing the saved
# dashboard at the fake Home Assistant moved the panel's Home Assistant address, and since #496 a panel then asks
# to sign in again (the Pixel Tablet, 2026-11-18). It survives a restart of the app and ends with the hold.
TEST_DASHBOARD_EXTRA = "test_dashboard"


def origin(url):
    u = urllib.parse.urlsplit(url)
    return f"{u.scheme}://{u.netloc}"


def cmd_testdash(dev, a):
    """Show --url instead of the kiosk's saved dashboard for the test hold, checked: exit 0 once the kiosk's log
    says so, 1 if it says nothing within --timeout (an app without the extra ignores it). --off ends it: exit 0
    once `am` says the kiosk took the intent (clearing one that already ended changes nothing, and says nothing)."""
    if a.off:
        out = dev.sh(f"am start -W -n {dev.component} --es {TEST_DASHBOARD_EXTRA} ''", timeout=a.timeout)
        sys.exit(0 if any(l.strip() == "Status: ok" for l in out.splitlines()) else 1)
    said = kiosk_says(dev, f"am start -W -n {dev.component} {HOLD_ON} --es {TEST_DASHBOARD_EXTRA} '{a.url}'",
                      re.compile(re.escape(f"test dashboard on: {origin(a.url)}")), a.timeout)
    if not said:
        sys.exit(1)
    print(json.dumps({"said": said}))


def kiosk_says(dev, cmd, pattern, timeout):
    """Sends `cmd`, then reads the kiosk's log until a line after it matches `pattern`: that line's message,
    or None within `timeout` seconds."""
    end = C.now() + timeout

    def sh(c):
        """adb within what is left of `timeout`: a stalled adb fails the check instead of outlasting it."""
        return dev.sh(c, timeout=max(1.0, C.real(end - C.now())))

    fmt = dev.clock.fmt
    try:
        mark = last_mono(dev, sh(f"logcat -d -v {fmt} -t 1"))
        sh(cmd)
    except subprocess.TimeoutExpired:
        return None
    while True:
        try:
            lines = sh(f"logcat -d -v {fmt}").splitlines()
        except subprocess.TimeoutExpired:
            return None
        for line in lines:
            rec = dev.parse(line)
            if rec and (mark is None or rec["mono"] > mark) and pattern.search(rec["msg"]):
                return rec["msg"]
        if C.now() >= end:
            return None
        C.sleep(1)


# Decoders, from logcat (refstart's note_media). MediaCodec and CCodec are Android's own; omx_vdec_aw
# and omx_vdec are the Allwinner decoder's (the S1), read 2026-11-13 from the panel's logcat.
MEDIA_TAGS = {"MediaCodec", "CCodec", "omx_vdec_aw", "omx_vdec"}
MEDIA_ALLOC_RE = re.compile(r"^\[([\w.]+)\] setting surface generation|^allocate\(([\w.]+)\)")

# The live settings are saved with apply(): give the write this long before the app restarts.
PANES_SAVE_S = float(os.environ.get("GW_PERF_PANES_SAVE_S", "3"))

GESTURE_MS = (50, 2000)   # a swipe's duration the page may ask for
MAX_TAPS = 50
OWN_PAGE_QUIET_S = 2.0    # the kiosk's own page must have stopped this long, with no new load


def screen_size(dev):
    """(width, height) in pixels as `input tap` takes them: the display's current size, turned as the screen is
    (`cur=` in `dumpsys window displays`, Android 6 to 17). `wm size` gives the panel's own, unturned: the Pixel
    Tablet's 1600x2560 while it shows 2560x1600. Without `cur=`, `wm size`, whose override, listed last, wins."""
    return P.current_size(dev.sh("dumpsys window displays")) or P.wm_size(dev.sh("wm size"))


def _whole(v, lo, hi):
    return type(v) is int and lo <= v <= hi


def gesture(pl, size, max_taps=MAX_TAPS):
    """A page's tap or swipe request, checked before anything reaches `adb shell`: whole pixels on
    the screen, a sane count and duration. Returns (command, count) or (None, why)."""
    if not size:
        return None, "the screen size is unknown"
    w, h = size
    if pl.get("t") == "input-ready":
        x, y, n = pl.get("x"), pl.get("y"), pl.get("taps")
        if not (_whole(x, 0, w - 1) and _whole(y, 0, h - 1)):
            return None, f"a tap at {x!r}, {y!r} is not a whole pixel on the {w}x{h} screen"
        if not _whole(n, 1, max_taps):
            return None, f"a tap count of {n!r} is not 1 to {max_taps}"
        return f"input tap {x} {y}", n
    x, y1, y2, ms = pl.get("x"), pl.get("y1"), pl.get("y2"), pl.get("ms")
    if not (_whole(x, 0, w - 1) and _whole(y1, 0, h - 1) and _whole(y2, 0, h - 1)):
        return None, f"a swipe at {x!r}, {y1!r} to {y2!r} is not whole pixels on the {w}x{h} screen"
    if not _whole(ms, *GESTURE_MS):
        return None, f"a swipe of {ms!r} ms is not {GESTURE_MS[0]} to {GESTURE_MS[1]} ms"
    return f"input swipe {x} {y1} {x} {y2} {ms}", 1


def tap_cap(taps):
    """The most taps a page may ask for: what --taps asked, never more than MAX_TAPS."""
    return min(taps or MAX_TAPS, MAX_TAPS)


def own_page_finished(stop_mono, stop_at, now):
    """The kiosk's own page counts as finished once it has stopped and OWN_PAGE_QUIET_S have passed
    (wall clock) with no new load. A new load resets stop_mono to None."""
    return stop_mono is not None and now - stop_at >= OWN_PAGE_QUIET_S


# ---- cold start to dashboard

PAGE_RE = re.compile(r"GeckoView:(PageStart|PageStop) uri=(\S+)")
# VitroEngine.open's line: Vitro draws the page (a GeckoView page load after it is a fallback's or a web island's).
VITRO_START_RE = re.compile(r"Starting Vitro \d")
# The legacy engine's lockdown (#198) loads pages only from the dashboard's origin, fixed when its process
# starts: the animation test page, opened one-off on the fake's origin, is refused while the kiosk shows
# another dashboard. cmd_synthetic exits LOCKDOWN_EXIT then, and run.sh skips that part (detail, not graded).
LOCKDOWN_RE = re.compile(r"Lockdown: refused page from")
LOCKDOWN_EXIT = 4
# Android's PowerManagerService, on any device: the screen is going off. With the screen off Gecko
# runs no animation frames and slows its timers, so nothing timed then is the panel's (smoke10: the
# kiosk's 20 s night idle timeout held light's view switch back 22 s and stopped heavy).
SLEEP_RE = re.compile(r"PowerManagerService.*Going to sleep|Going to sleep due to")
# The app's line when its browser page's process is gone (MainActivity.onContentGone, any frame and engine): GeckoView
# calls the process "killed" when Android ended it (onKill) and "crashed" when it died by itself (onCrash). The app then
# restarts the session and loads the kiosk's page again (the test dashboard's start view), which reaches the runner as
# another page load. With this line before it, the open view is lost, not the run: the lululemon Mirror's
# lowmemorykiller closed typical's page in all 3 graded runs (2026-11-23), and the run used to fail as a whole.
CONTENT_GONE_TAG = "Glassworks"
CONTENT_GONE_RE = re.compile(r"Content process (killed|crashed); restarting session")
# Android's low-memory killer naming the process it ended: lmkd's line in logcat (Android 9 and later, tag
# lowmemorykiller, the full name), or the kernel's in dmesg (Android 8 and earlier: "lowmemorykiller: Killing '<name>'
# (<pid>)", the task name, which Android's zygote cuts to the last 15 characters of a longer process name, so a long
# child name such as `<package>:sandboxed_tab_disable_art_image_24` keeps no colon: `le_art_image_24`). Only these
# name the killer; without one the report says only that Android closed the page.
LMK_TAG = "lowmemorykiller"
LMK_RE = re.compile(r"\bKill(?:ing)? '([^']+)' \((\d+)\)")
DMESG_TIME_RE = re.compile(r"\[\s*(\d+\.\d+)\]")
# ActivityManager starting one of the kiosk's other processes (its browser page's among them): its pid and full name,
# which the kernel's cut name and pid are matched against.
CHILD_PROC_RE = r"Start proc (\d+):({pkg}:[^/\s]+)/"
# The kernel's task names hold at most this many characters.
TASK_COMM_LEN = 15
# A low-memory kill counts for the page when it came this long before the app's line at most (or up to 5 s after:
# the kernel's clock and logcat's needn't agree to the millisecond).
LMK_WINDOW_S = 120


def own_process(name, pid, package, procs):
    """True when a process the low-memory killer named (`name`, `pid`) is one of the kiosk's own other processes (its
    page's). `procs`: {pid: full name} of the ones ActivityManager started during the run (CHILD_PROC_RE). By its pid
    or full name among those; by a part of one of those names when the kernel cut it (no colon left); else, for a
    process started before the runner read logcat, `<package>:<name>` or the end of it that still keeps the colon."""
    procs = procs or {}
    if procs.get(str(pid)) or name in procs.values():
        return True
    # The kernel keeps the last TASK_COMM_LEN characters of a longer name. Some zygote versions copy one character
    # fewer than they cut to, so a name one shorter may also miss the last character. Only those two forms: a short
    # name that merely occurs inside a longer one ("sh") is someone else's process.
    if any(len(full) > TASK_COMM_LEN and name in (full[-TASK_COMM_LEN:], full[-TASK_COMM_LEN:-1])
           for full in procs.values()):
        return True
    base, sep, rest = name.partition(":")
    return bool(sep and rest) and package.endswith(base)


def low_memory_kill(dev, seen, gone_mono, procs=None):
    """Whether the low-memory killer closed one of the kiosk's processes just before the app said its page was killed:
    lmkd's line already in logcat (`seen`: [(mono, name, pid)]), or the kernel's in dmesg, read once now (read-only; a
    build whose shell can't read it gives nothing). `procs`: own_process's. False when neither names it: then nothing
    says why."""
    if any(gone_mono - LMK_WINDOW_S <= m <= gone_mono + 5 and own_process(n, pid, dev.package, procs) for m, n, pid in seen):
        return True
    try:
        out = dev.sh("dmesg", timeout=20)
    except (subprocess.SubprocessError, OSError):
        return False
    for line in out.splitlines():
        if LMK_TAG not in line:
            continue
        m, t = LMK_RE.search(line), DMESG_TIME_RE.search(line)
        if m and t and gone_mono - LMK_WINDOW_S <= float(t.group(1)) <= gone_mono + 5 \
                and own_process(m.group(1), m.group(2), dev.package, procs):
            return True
    return False
# Once the restart page is done, the kiosk's test hold keeps the screen on through the views (#240).
# Before it, the wake intents every 8 s and a live view's after 10 s without a touch did, and still
# lost it: s1rtc5 run 1 (23:50) lost heavy's screen 16 to 20 s after its last tap, while the page
# waited out its camera window before the swipes.
# From the force-stop until the next view's hold (and through a recovery's restart), Android's wake key
# this often, and once at the stop and once at the relaunch, for the time the app is down: s1rtc7 run 2
# lost the screen 20.4 s after the stop, before the kiosk's first frame, which then waited 280 s for
# the screen to come back. The kiosk's own intent would start the stopped app and change the restart
# being measured. The wake key doesn't reset the kiosk's own idle timer once it runs: the hold,
# which outlives the restart, does that.
RESTART_WAKE_S = float(os.environ.get("GW_PERF_RESTART_WAKE_S", "8"))
# The CPU clock each view ran at (detail, #244: the S1's power HAL held 1.2 of its 1.608 GHz): every
# core's scaling_cur_freq this often while a view is open, and each core's cpuinfo_max_freq once.
# Read-only `cat`s of sysfs over adb; one every 5 s is a fraction of a percent of a core.
CPU_CLOCK_S = float(os.environ.get("GW_PERF_CPU_CLOCK_S", "5"))
CPU_FREQ = "/sys/devices/system/cpu/cpu[0-9]*/cpufreq/"


def cpu_khz(dev, name):
    """Each core's cpufreq `name` (scaling_cur_freq, cpuinfo_max_freq) in kHz, by CPU number from its
    sysfs path ({"0": khz, ...}); {} if the device has none. The shell sorts cpu10 before cpu2, so the
    number comes from the path, never the position (CodeRabbit on #250)."""
    try:
        out = dev.sh(f"for f in {CPU_FREQ}{name}; do echo \"$f $(cat $f 2>/dev/null)\"; done", timeout=15)
    except (subprocess.SubprocessError, OSError):
        return {}
    got = {}
    for line in out.splitlines():
        m = re.search(r"/cpu(\d+)/cpufreq/\S+ (\d+)$", line.strip())
        if m:
            got[m.group(1)] = int(m.group(2))
    return dict(sorted(got.items(), key=lambda kv: int(kv[0])))
# A view not done this long after it opened is recorded as timed out: what it measured stands, and
# what it didn't fails (#202, smoke 7: heavy never finished, and the whole run was dropped after 900 s).
# The runner then restarts the kiosk, as at the run's start, and opens the run's remaining views: a
# view that can't finish fails its own tasks and nothing else (#202, s1rtc3).
VIEW_DEADLINE_S = float(os.environ.get("GW_PERF_VIEW_DEADLINE_S", "300"))
# Protocol 8 (#202, s1rtc4): that deadline covers only a view that never became usable. After that the
# page gives each task its own budget, and a view is timed out only when it says nothing for
# STAGE_IDLE_S (stuck in one task); s1rtc4's heavy was usable after 43 s and still busy at 300 s,
# before its swipes, which never ran.
STAGE_IDLE_S = float(os.environ.get("GW_PERF_STAGE_IDLE_S", "180"))
# After that restart: if nothing relaunched the kiosk within this, `am start` it; if its reference
# dashboard isn't usable within RECOVER_WAIT_S, the run fails.
RECOVER_LAUNCH_S = float(os.environ.get("GW_PERF_RECOVER_LAUNCH_S", "10"))
RECOVER_WAIT_S = float(os.environ.get("GW_PERF_RECOVER_WAIT_S", "180"))


def watch_threads():
    """Every exception a thread of this process raises, from now on: [str], printed as usual too.
    cmd_coldstart fails the run on any (s1rtc5's runs went on with a dead helper thread)."""
    errors = []

    def hook(args):
        name = args.thread.name if args.thread else "a thread"
        errors.append(f"{name}: {args.exc_type.__name__}: {args.exc_value}")
        threading.__excepthook__(args)
    threading.excepthook = hook
    return errors


def cmd_coldstart(dev, a):
    """Force-stop, then time the next launch. A kiosk that is the home app is relaunched by the
    system at once (the S1 does it in 0.2 s), so logcat is followed from before the stop, and
    `am start -W` is sent only if nothing relaunched the app within --settle seconds.

    Both record when the engine is ready: GeckoView's first PageStart (its own about:blank), which
    comes before the kiosk does anything for Home Assistant.
    coldstart (real use, not graded) then follows the kiosk's own dashboard to its PageStop.
    refstart (graded, protocol 4) waits until the kiosk is back on the reference dashboard's start
    view (run.sh pointed it there) and the harness says it is usable: the restart's end. Then it opens
    each reference view (--tiers; --warmup-tier adds one, not graded) through the fake Home
    Assistant (--url): the harness, in the frontend that is already loaded, navigates to it as HA's
    view tabs do. It follows each view's lines: taps and swipes where the view asks. The order turns
    by one view each run (tiers.json ORDER: run 1 light, typical-nocam, typical, heavy; run 2 from
    typical-nocam; run 3 from typical), so no view always comes first. Each opens only once the screen has been still
    for --between-quiet seconds (the view before stops its updates when it is done). After the first
    open, any page load is an error: a view switch loads no page. One exception: when the app said just
    before that its browser page's process was killed or crashed (content_gone), the open view is lost
    in this run, and the run carries on once the kiosk's page is back. Without a restart page (the kiosk
    came back to its own dashboard), it waits for that page as protocols 1 to 3 did.

    logcat follows the whole device, so its queue is rarely empty: no decision here waits for an
    empty queue. Before deciding, the loop reads everything queued so far (drain)."""
    ref = a.cmd == "refstart"
    base = [t for t in a.tiers.split(",") if t] if ref else []
    if ref and a.warmup_tier and a.warmup_tier in base:
        # The frontend keeps a view it has shown (hui-root's _viewCache): the graded open of that view
        # would skip building it.
        sys.exit(f"--warmup-tier {a.warmup_tier} is also graded; a view shown twice is cached the second time")
    turn = (a.run - 1) % len(base) if base else 0
    # A frame without touch (its design/installer.json says so): nobody scrolls it, so its graded views get no swipes,
    # and the scroll isn't graded (rubric.json no_touch). The taps still go: injected taps reach the page there.
    touch = P.frame_touch(FRAMES, a.frame)
    if ref and not touch:
        print(f"{a.frame} has no touch: no swipes, and scrolling isn't graded", file=sys.stderr)
    tiers = base[turn:] + base[:turn]
    # The HLS fallback views (#202; detail, not graded) come after the graded ones, in a fixed order.
    fallback = [t for t in a.fallback.split(",") if t] if ref else []
    slots = (["warmup"] if ref and a.warmup_tier else []) + tiers + fallback
    lc = dev.logcat()
    lines = queue.Queue()
    threading.Thread(target=lambda: [lines.put(l) for l in lc.stdout], daemon=True).start()
    presents, period = set(), [None]
    stop, paused, kick = threading.Event(), threading.Event(), threading.Event()

    def poll():
        if SF_POLL_S:
            # As often as the window reads go (#643), from one read's start to the next: a shorter history than
            # a second loses the frames the open and the restart are timed to.
            due, k = C.mono(), 0
            while not stop.is_set():
                if not paused.is_set():
                    p, ts = sf_poll_read(dev, k)
                    k += 1
                    if p:
                        period[0] = p
                        presents.update(ts)
                due += SF_POLL_S
                stop.wait(C.real(max(0.0, due - C.mono())))
            return
        while not stop.is_set():
            if not paused.is_set():
                p, ts = dev.sf()
                if p:
                    period[0] = p
                    presents.update(ts)
            # Every second, or at once when a view says it is usable (read_open).
            kick.wait(C.real(1.0))
            kick.clear()

    def read_open():
        """A view's usable line: read SurfaceFlinger now, while the page is still (stillAfterUsableMs). Its open is
        timed to the frames on screen after it rendered (report.usable_on_screen), and these reads are the only ones
        that keep them: the view's live updates pause the reads, and after the run's last view there is no read
        between views. A read that began before the view painted, followed by the next one a second later, could miss
        the whole still time (the fake's, under load: the last view had no open)."""
        kick.set()

    # keep_awake's look at `held` and its wake key, against hold()'s: one at a time, so no wake key
    # is sent after a hold.
    wake_lock = threading.Lock()

    def keep_awake():
        """Android's wake key from the force-stop (and a recovery's) until the next view's test hold."""
        last_nudge = 0.0
        relaunch_seen = False
        while not stop.wait(C.real(min(1.0, RESTART_WAKE_S / 2))):
            with wake_lock:
                if st.get("held"):
                    continue
                # The wake key at once when the app comes back up, and every RESTART_WAKE_S.
                relaunched = res["proc_mono"] is not None and not relaunch_seen
                relaunch_seen = relaunch_seen or relaunched
                if relaunched or C.now() - last_nudge >= RESTART_WAKE_S:
                    last_nudge = C.now()
                    dev.nudge()
                    res["restart_wakes"] = res.get("restart_wakes", 0) + 1

    def cpu_clock():
        """[view, time, [kHz per core]] every CPU_CLOCK_S while a view is open, from its open to its
        done (cpu_khz in the run's file); each core's top clock once, when the thread starts. It runs
        while the poller is paused too (the one adb call inside timed windows: a sysfs cat)."""
        res["cpu_max_khz"] = cpu_khz(dev, "cpuinfo_max_freq")
        res["cpu_clock_s"] = CPU_CLOCK_S
        while not stop.wait(C.real(CPU_CLOCK_S)):
            cur = st.get("current") if st.get("view_open") else None
            if cur and not st.get("recovering"):
                khz = cpu_khz(dev, "scaling_cur_freq")
                # The read takes a moment: keep it only if the same view is still open (no next view,
                # recovery or end meanwhile), so it is never put under the view before (CodeRabbit).
                if st.get("current") != cur or not st.get("view_open") or st.get("recovering") or stop.is_set():
                    continue
                if khz:
                    res.setdefault("cpu_khz", []).append([cur[0], round(C.now(), 1), khz])

    def hold():
        """The test hold, for TEST_HOLD_S from now, before each view opens, so a long run never outlasts
        it. Not in a timed window: the view's open starts after it."""
        with wake_lock:
            st["held"] = True                  # keep_awake's wake key stops now
            dev.nudged()                       # and the last one sent is through adb before the hold
        dev.hold()
        res["holds"] = res.get("holds", 0) + 1

    def quiet_for(secs, cap):
        """Wait until no new frame has been presented for `secs` (at most `cap` s). True if it went quiet."""
        end, last, since = C.now() + cap, len(presents), C.now()
        while C.now() < end and C.now() - since < secs:
            C.sleep(0.25)
            if len(presents) != last:
                last, since = len(presents), C.now()
        return C.now() - since >= secs

    thread_errors = watch_threads()
    res = {"run": a.run, "launch": None, "am_total_ms": None, "ttid_ms": None, "stop_mono": None, "proc_mono": None,
           "displayed_mono": None, "engine_mono": None, "engine_uri": None, "own_page_start_mono": None,
           "own_page_stop_mono": None, "own_page_loads": 0, "own_page_state": None,
           "page_start_mono": None, "page_stop_mono": None, "tiers": {}, "order": tiers, "warmup": None,
           "between": [], "bad_page_lines": 0, "protocol_restart": ref, "memory": [], "error": None,
           "fallback": {}}
    page_lines = P.PageLines()
    st = {"engine_at": None, "own_stop_at": 0.0, "current": None, "measure_mono": None, "sf_reads": None, "break_after": None,
          "opened": 0, "next_at": None, "gestures": [], "lmk_seen": [], "own_procs": {}}
    # The threads start once `res` and `st` exist: they write to both at once (#250's verifier).
    poller = threading.Thread(target=poll, daemon=True)
    poller.start()
    if ref:
        threading.Thread(target=keep_awake, daemon=True).start()
        threading.Thread(target=cpu_clock, daemon=True).start()
    size = screen_size(dev) if ref else None
    proc_re = re.compile(rf"Start proc \d+:{re.escape(dev.package)}/")
    child_re = re.compile(CHILD_PROC_RE.format(pkg=re.escape(dev.package)))

    def entry_of(slot):
        if slot == "warmup":
            return res["warmup"]
        return res["fallback"][slot] if slot in fallback else res["tiers"][slot]

    def note_media(rec, cur):
        """Which decoders each view got, from Android's own log lines (any device): the codec name
        when MediaCodec puts one on a surface (video) or Codec2 allocates one. On the S1 also the
        Allwinner decoder's flushes and end-of-stream drains (Gecko drains a decoder that ran out of
        data). Names and counts only; read-only."""
        tag, msg = rec["tag"], rec["msg"]
        if tag not in MEDIA_TAGS:
            return
        m = MEDIA_ALLOC_RE.search(msg) if tag in ("MediaCodec", "CCodec") else None
        flush = tag == "omx_vdec_aw" and "decoder flush" in msg
        eos = tag == "omx_vdec" and "found eos flag" in msg
        if not (m or flush or eos):
            return
        where = cur[0] if cur else "between"
        rec_ = res.setdefault("media", {}).setdefault(where, {"codecs": [], "flushes": 0, "eos": 0})
        if m:
            rec_["codecs"].append(m.group(1) or m.group(2))
        rec_["flushes"] += flush
        rec_["eos"] += eos

    def send(nonce, cmd, n, gap):
        """Taps or a swipe for the page that asked, and only while it is still the current page."""
        for i in range(n):
            cur = st["current"]
            if not cur or cur[1] != nonce:
                return
            t0 = time.time()
            dev.sh(cmd)
            # When each gesture went over adb and how long `input` took on the panel, on this
            # computer's clock (fakeha.jsonl's): #202, smoke 5's heavy stalled during the taps. Real
            # seconds even on the tests' scaled clock, as fakeha.jsonl's are (CodeRabbit on #274).
            res.setdefault("gestures_sent", []).append([cur[0], round(t0, 3), cmd.split()[1],
                                                         round(time.time() - t0, 3)])
            if i + 1 < n:
                C.sleep(gap)

    def open_next():
        # No gesture for the last page may reach the next one: wait for them (the page has said done).
        for th in st["gestures"]:
            th.join(timeout=15)
        st["gestures"] = []
        hold()
        slot = slots[st["opened"]]
        st["opened"] += 1
        st["opened_at"] = C.now()
        nonce = secrets.token_hex(4)
        st["current"], st["measure_mono"], st["next_at"] = (slot, nonce), None, None
        st["view_open"] = True                 # until its done or error (or its timeout): cpu_clock
        st["line_at"], st["last_line"] = None, None
        paused.clear()
        if slot == "warmup":
            tier, extra = a.warmup_tier, {"taps": 0, "swipes": 0, "dialogs": 0, "warmup": 1}
            res["warmup"] = {"tier": tier, "nonce": nonce, "swipes": []}
        elif slot in fallback:
            # As a graded view, taps included, so the camera window is the same; no swipes, no
            # more-info dialogs (protocol 3: they come after the camera window, and aren't the copy's).
            tier, extra = slot, {"taps": a.taps, "swipes": 0, "dialogs": 0}
            res["fallback"][slot] = {"nonce": nonce, "swipes": []}
        else:
            tier, extra = slot, dict({"taps": a.taps}, **({} if touch else {"swipes": 0}))
            res["tiers"][tier] = {"nonce": nonce, "swipes": []}
        # Protocol 4: the frontend is already loaded on the start view. The fake Home Assistant hands
        # this command to the harness, which opens the view with HA's own navigation (no page load).
        q = urllib.parse.urlencode(dict({"view": tier, "nonce": nonce, "run": a.run, "vsync": a.vsync}, **extra))
        try:
            with urllib.request.urlopen(f"{a.url.rstrip('/')}/clarity/open?{q}", timeout=10) as r:
                r.read()
        except OSError as e:
            res["error"] = f"the fake Home Assistant didn't take the command to open {tier}: {e}"

    def still(nonce):
        try:
            with urllib.request.urlopen(f"{a.url.rstrip('/')}/clarity/still?nonce={nonce}", timeout=10) as r:
                r.read()
        except OSError:
            pass

    def read_sf(pl, rec):
        """SurfaceFlinger's history just after a window (and during it, #643), cut to the window the page logged."""
        m0 = st["measure_mono"]
        end_mono = m0 + pl["window_ms"] / 1000 if pl.get("window_ms") else rec["mono"]
        reads, st["sf_reads"] = st["sf_reads"] or SfReads(dev, 0), None
        pl["sf"] = reads.window(m0, end_mono)

    def window_open():
        """A page's measure line: its window has started (reads during it, #643, when SF_POLL_S is set)."""
        if st["sf_reads"]:
            st["sf_reads"].stop()
        st["sf_reads"] = SfReads(dev)

    def handle(rec):
        """One logcat line. Returns True when the run is over (done or failed)."""
        msg = rec["msg"]
        if res["stop_mono"] is None:
            if f"Force stopping {dev.package} " in msg:
                res["stop_mono"] = rec["mono"]
            return False
        if res["proc_mono"] is None and proc_re.search(msg):
            res["proc_mono"] = rec["mono"]
            res["launch"] = res["launch"] or "relaunched-by-system"
        if res["engine_mono"] is None and res["proc_mono"] is not None and VITRO_START_RE.search(msg):
            # Vitro's start: the native renderer's page is open (it loads no about:blank). Engine ready, detail.
            res["engine_mono"], res["engine_uri"] = rec["mono"], "vitro"
            st["engine_at"] = C.now()
        if res["ttid_ms"] is None:
            res["ttid_ms"] = P.parse_displayed(msg, dev.package)
            if res["ttid_ms"] is not None:
                res["displayed_mono"] = rec["mono"]
        cur = st["current"]
        if ref:
            note_media(rec, cur)
        if ref and SLEEP_RE.search(msg) and res["proc_mono"] is not None:
            res.setdefault("screen_off", []).append({"mono": rec["mono"], "during": cur[0] if cur else
                                                     "recovery" if st.get("recovering") else "the restart"})
        if ref and "Start proc" in msg:
            m = child_re.search(msg)
            if m:
                st["own_procs"][m.group(1)] = m.group(2)
        if ref and rec["tag"] == LMK_TAG:
            m = LMK_RE.search(msg)
            if m:
                st["lmk_seen"].append((rec["mono"], m.group(1), m.group(2)))
        if ref and rec["tag"] == CONTENT_GONE_TAG and st["opened"] and not st.get("recovering"):
            m = CONTENT_GONE_RE.search(msg)
            if m:
                return content_gone(m.group(1), rec)
        if st.get("recovering"):
            # The kiosk coming back after a view timed out: only its restart page's lines count, and
            # they end the recovery; the run's own restart measurement stays the first one's.
            if proc_re.search(msg):
                st["recovering"]["launched"] = True
            if ref and rec["tag"] == CONTENT_GONE_TAG and CONTENT_GONE_RE.search(msg):
                # The replacement page was lost too: its usable line no longer says the kiosk is back.
                st["recovering"].pop("usable_at", None)
            pl = page_lines.feed(msg)
            if pl and pl.get("nonce") == "" and pl.get("t") == "refdash":
                st["recovering"].setdefault("usable_at", C.now())   # the done line may be lost: end_recovery
            if pl and pl.get("nonce") == "" and pl.get("t") in ("done", "error"):
                end_recovery(**({"error": pl.get("message")} if pl.get("t") == "error" else {}))
            return False
        pl = page_lines.feed(msg)
        res["bad_page_lines"] = page_lines.bad
        if ref and pl and pl.get("nonce") == "" and (cur is None or pl.get("t") == "settle"):
            # A warm-up's settle (#410) can come after the first view was opened: still the restart's.
            return handle_restart(pl, rec)
        if cur and pl and pl.get("nonce") == cur[1]:
            return handle_page(cur[0], pl, rec)
        m = PAGE_RE.search(msg)
        if not m or res["proc_mono"] is None:
            return False
        kind, uri = m.group(1), m.group(2)
        if kind == "PageStart" and res["engine_mono"] is None:
            res["engine_mono"], res["engine_uri"] = rec["mono"], uri
            st["engine_at"] = C.now()
        if kind == "PageStart" and not uri.startswith("http"):
            return False
        if cur is None:
            # Before the first open: the kiosk's own page (its dashboard). A second load (a reload, a
            # sign-in redirect) restarts the wait: the reference pages must not meet it.
            if kind == "PageStart":
                res["own_page_loads"] += 1
                res["own_page_start_mono"], res["own_page_stop_mono"] = rec["mono"], None
                res["own_page_uri"] = uri
            elif kind == "PageStop" and res["own_page_start_mono"] is not None and res["own_page_stop_mono"] is None:
                res["own_page_stop_mono"] = rec["mono"]
                st["own_stop_at"] = C.now()
                return not ref
        elif kind == "PageStart":
            if cur[1] in uri:
                entry_of(cur[0]).setdefault("page_start_mono", rec["mono"])
            else:
                res["error"] = "the kiosk loaded another page while a reference dashboard was measured"
                return True
        return False

    def end_recovery(**extra):
        """The kiosk's start view is back after a lost or timed-out view (its restart page's done or error line, or its
        usable line RESTART_DONE_WAIT_S ago with no done line since): the next view opens once the screen is still."""
        rv = st.pop("recovering")
        res["recoveries"][-1].update(back_s=round(C.now() - rv["at"], 1), launch=rv.get("launch") or "relaunched-by-system",
                                     **extra)
        st["no_memory"] = True        # RAM right after a restart isn't the views' peak
        st["next_at"] = C.now()
        print(f"  the kiosk is back after {res['recoveries'][-1]['back_s']} s; carrying on", file=sys.stderr)

    def content_gone(cause, rec):
        """The app's browser page process is gone (CONTENT_GONE_RE) and the app is loading the kiosk's page again. The
        open view is lost in this run: a graded one is measured as failing every task (report.py), a fallback copy
        fails only itself, the warm-up says so; a view already done loses nothing. Then, as after a timed-out view, the
        runner waits for the start view to be usable again and opens the rest. True when nothing is left to open."""
        cur, now0 = st["current"], C.now()
        slot = cur[0] if cur else None
        entry = entry_of(slot) if slot else None
        lost = entry is not None and not entry.get("done") and not entry.get("error")
        at_end = st["opened"] == len(slots) and (lost or entry is None or entry.get("done") or entry.get("error"))
        # The lost view ends before anything here can block (the dmesg read below takes up to 20 s, while the app is
        # already loading its page again): its gestures stop (send checks the page), and its command goes, so the
        # page the app loads doesn't run it when it asks the fake Home Assistant for one (CodeRabbit on #781).
        st["current"], st["next_at"] = None, None
        if lost:
            st["live"] = st["view_open"] = False
        # The run's last view too: the page coming back must find no command, whatever comes after.
        try:
            with urllib.request.urlopen(f"{a.url.rstrip('/')}/clarity/cancel", timeout=10) as r:
                r.read()
        except OSError:
            pass
        gone = {"cause": cause,
                "low_memory": cause == "killed" and low_memory_kill(dev, st["lmk_seen"], rec["mono"], st["own_procs"])}
        res.setdefault("content_gone", []).append(dict(gone, during=slot if lost else "between views", mono=rec["mono"]))
        if lost:
            entry["content_gone"] = dict(gone, after_s=round(now0 - st["opened_at"], 1),
                                         after=st.get("last_line") or "usable")
            entry["lost_lines"] = page_lines.close(cur[1])
            if slot in fallback:
                entry["error"] = P.content_gone_why(gone)
            if st["sf_reads"]:
                st["sf_reads"].stop()
                st["sf_reads"] = None
        print(f"  {slot if lost else 'between views'}: {P.content_gone_why(gone)}", file=sys.stderr)
        if at_end:
            st["gone_at_end"] = True
            return True                       # the run's last view: nothing left to open
        st["recovering"] = {"at": now0, "launched": True, "launch": "session-restart"}
        st["held"] = False                    # the wake key again until the next view's hold
        res.setdefault("recoveries", []).append({"after": slot, "content_gone": cause})
        print("  waiting for the kiosk's page to come back", file=sys.stderr)
        return False

    def handle_restart(pl, rec):
        """The kiosk's own page after the restart: the reference dashboard it shows as its test dashboard
        (run.sh, perfrun.py testdash), with no nonce. Its `refdash` holds the restart's usable time; its `done`
        lets the graded pages open."""
        t = pl.get("t")
        pl["mono"] = rec["mono"]
        if t == "refdash":
            res["restart_page"] = pl
            st["restart_usable_at"] = C.now()
        elif t == "done":
            st["restart_done_at"] = C.now()
        elif t == "error":
            res["restart_page_error"] = pl.get("message")
            st["restart_done_at"] = C.now()
        elif t == "settle":
            # #410: HA still held a warm-up dialog after the page's wait (detail; the first view's
            # check before its navigation decides whether it is measured).
            res.setdefault("warmup_settle", []).append(pl)
        return False

    def handle_page(tier, pl, rec):
        entry, t = entry_of(tier), pl.get("t")
        pl["mono"] = rec["mono"]
        st["line_at"], st["last_line"] = C.now(), t
        if t == "refdash":
            entry["page"] = pl
            read_open()
        elif t == "drawn":
            # Clarity v2: the first screen drawn, before the main thread is free. Kept apart from
            # `page` (usable), which the deadlines go by; the report reads it for a view that never
            # became usable.
            entry["drawn"] = pl
        elif t == "warmup":
            entry["live_mono"] = rec["mono"]
            # No dumpsys while frames are measured, taps are timed or the page scrolls; but the window's own
            # SurfaceFlinger reads where its history is shorter than a window (SfReads, #643).
            paused.set()
            st["live"] = True
        elif t == "measure":
            st["measure_mono"] = rec["mono"]
            window_open()
            if pl.get("stage") == "updates":
                entry["measure"] = pl
        elif t == "step" and st["measure_mono"] is not None:
            read_sf(pl, rec)
            if pl.get("stage") == "scroll":
                entry["swipes"].append(pl)
            else:
                entry["step"] = pl
            st["measure_mono"] = None
        elif t == "step":
            if pl.get("stage") == "scroll":
                entry["swipes"].append(pl)   # it never scrolled, so no window to read
        elif t in ("input-ready", "swipe-ready") and (t == "swipe-ready" or a.taps):
            cmd, n = gesture(pl, size, max_taps=tap_cap(a.taps))
            if cmd and t == "swipe-ready":
                entry["swipes_asked"] = entry.get("swipes_asked", 0) + 1
            if cmd is None:
                # Not sent: the page asked for something off the screen or malformed. That page isn't graded.
                entry["error"] = f"bad {t} line: {n}"
                print(f"  {tier}: {entry['error']}", file=sys.stderr)
            else:
                th = threading.Thread(target=send, args=(st["current"][1], cmd, n, 0.6), daemon=True)
                th.start()
                st["gestures"].append(th)
        elif t == "input":
            entry["input"] = pl
        elif t == "camera":
            entry["camera"] = pl
        elif t == "dialog":
            # Protocol 3 (#211): one line per more-info dialog, after the swipes.
            entry.setdefault("dialogs", []).append(pl)
        elif t == "settle":
            # #410: HA still held one of the view's dialogs after its own wait; the page leaves the view
            # for the start view next (detail; its done line says how that ended).
            entry["settle_first"] = pl
        elif t in ("done", "error"):
            st["live"] = st["view_open"] = False
            if st["sf_reads"]:
                st["sf_reads"].stop()              # a window that never got its step line
                st["sf_reads"] = None
            # Stop the view's stream at the fake (protocol 8: the page asks too, but doesn't wait on a
            # busy main thread for the answer); a stop for this view's nonce only.
            threading.Thread(target=still, args=(pl.get("nonce") or st["current"][1],), daemon=True).start()
            if t == "error":
                entry["error"] = pl.get("message")
            else:
                entry["done"] = True
                if pl.get("settle") is not None:
                    entry["settle"] = pl["settle"]     # #410: HA done with its dialogs (detail, untimed)
            # Anything of this page's that didn't arrive whole is lost: a line cut into parts that
            # never all came, and a swipe answered with no scroll step back.
            entry["lost_lines"] = page_lines.close(pl.get("nonce"))
            entry["lost_scroll_steps"] = max(0, entry.get("swipes_asked", 0) - len(entry["swipes"]))
            res["bad_page_lines"] = page_lines.bad
            print(f"  {tier} {t}", file=sys.stderr)
            if st["opened"] == len(slots):
                # Watch a little longer: another page now means one was on its way during the last tier.
                st["break_after"] = C.now() + 1.5
            else:
                st["next_at"] = C.now()   # the main loop opens it once the screen is still
        return False

    def drain():
        while True:
            try:
                line = lines.get_nowait()
            except queue.Empty:
                return False
            rec = dev.parse(line)
            if rec and handle(rec):
                return True

    if ref:
        # No command left from a view that was cut short: the frontend after the restart must not run it.
        try:
            with urllib.request.urlopen(f"{a.url.rstrip('/')}/clarity/cancel", timeout=10) as r:
                r.read()
        except OSError:
            pass                          # the open below says so if the fake is gone
    dev.sh("input keyevent KEYCODE_WAKEUP")
    dev.sh(f"am force-stop {dev.package}")
    if ref:
        dev.nudge()                       # the restart starts awake; keep_awake goes on from here
    t0 = C.now()
    while C.now() - t0 < a.timeout:
        if res["error"]:
            break                         # e.g. a view couldn't be opened
        if st["break_after"] and C.now() > st["break_after"]:
            break
        late = None
        if ref and st["current"] and st.get("opened_at"):
            entry = entry_of(st["current"][0])
            if not entry.get("page") and C.now() - st["opened_at"] > VIEW_DEADLINE_S:
                late = ("usable", VIEW_DEADLINE_S)
            elif entry.get("page") and C.now() - max(st.get("line_at") or 0, st["opened_at"]) > STAGE_IDLE_S:
                late = (st.get("last_line") or "usable", STAGE_IDLE_S)
        if late:
            if not entry.get("done") and not entry.get("error"):
                view = st["current"][0]
                entry["timed_out_s"] = round(C.now() - st["opened_at"], 1)
                entry["timed_out_after"] = late[0]      # the last thing it said (or "usable": never was)
                entry["lost_lines"] = page_lines.close(st["current"][1])
                res.setdefault("timed_out", []).append(view)
                st["live"], st["current"], st["view_open"] = False, None, False   # its gestures stop (send checks the page)
                if st["opened"] == len(slots):
                    print(f"  {view} timed out ({'not usable within' if late[0] == 'usable' else 'nothing after ' + late[0] + ' for'} "
                          f"{late[1]:.0f} s); it was the run's last view", file=sys.stderr)
                    break
                # Carry on: restart the kiosk as at the run's start (the frontend is reloaded, so the
                # next view isn't measured on what the stuck one left behind), then open the rest.
                print(f"  {view} timed out ({'not usable within' if late[0] == 'usable' else 'nothing after ' + late[0] + ' for'} "
                      f"{late[1]:.0f} s); restarting the kiosk for the run's other views",
                      file=sys.stderr)
                for th in st["gestures"]:
                    th.join(timeout=15)
                st["gestures"] = []
                try:
                    with urllib.request.urlopen(f"{a.url.rstrip('/')}/clarity/cancel", timeout=10) as r:
                        r.read()
                except OSError:
                    pass
                paused.clear()
                st["recovering"] = {"at": C.now()}
                st["held"] = False            # the wake key again until the next view's hold
                res.setdefault("recoveries", []).append({"after": view})
                dev.nudge()
                dev.sh(f"am force-stop {dev.package}")
                dev.nudge()
                continue
        if st.get("recovering"):
            rv = st["recovering"]
            if rv.get("usable_at") and C.now() - rv["usable_at"] > RESTART_DONE_WAIT_S:
                # Its usable line came, its done line didn't (lost in logcat), as for the run's own restart below:
                # carry on from the usable line rather than fail the run at RECOVER_WAIT_S (CodeRabbit on #781).
                end_recovery(done_missing=True)
                continue
            if not rv.get("launched") and not rv.get("launch") and C.now() - rv["at"] > RECOVER_LAUNCH_S:
                if drain():
                    break
                if st.get("recovering") and not st["recovering"].get("launched"):
                    rv["launch"] = "am-start"
                    dev.sh(f"am start -W -n {dev.component}", timeout=120)
            elif not rv.get("usable_at") and C.now() - rv["at"] > RECOVER_WAIT_S:
                # RECOVER_WAIT_S is for the page to come back usable; once it has, the fallback above ends the wait.
                # The lines logcat already gave first: this loop gets here before it reads the next one, so a usable
                # line that came while the runner was busy (the kernel's log read for the lost page, a loaded
                # computer) was still waiting in the queue, and the run failed though the page was back in time.
                if drain():
                    break
                if st.get("recovering") is rv and not rv.get("usable_at"):
                    res["error"] = (f"the kiosk didn't come back within {RECOVER_WAIT_S:.0f} s after "
                                    f"{res['recoveries'][-1]['after']} timed out")
                    break
                continue
        if res["launch"] is None and C.now() - t0 > a.settle:
            if drain():
                break
            if res["launch"] is None:   # still nothing relaunched it
                res["launch"] = "am-start"
                am = dev.sh(f"am start -W -n {dev.component}", timeout=120)
                res["am_total_ms"] = int((re.findall(r"TotalTime: (\d+)", am) or [0])[0]) or None
        if ref and st.get("restart_usable_at") and not st.get("restart_done_at") \
                and C.now() - st["restart_usable_at"] > RESTART_DONE_WAIT_S:
            # Its usable line came, its done line didn't (lost in logcat): the restart is measured
            # from the usable line alone; go on rather than wait out the whole run (F8).
            res["restart_done_missing"] = True
            st["restart_done_at"] = C.now()
        if ref and st["current"] is None and st.get("restart_done_at") and st["next_at"] is None and not st["opened"]:
            # Protocol 4: the kiosk came back to the reference dashboard and it is usable: open the
            # graded pages once the screen is still (the next_at branch below).
            res["own_page_state"] = "reference"
            st["next_at"] = C.now()
        if ref and st["current"] is None and st["engine_at"] and not st.get("restart_done_at") and not st["opened"] \
                and not res.get("restart_page"):
            # The kiosk came back to the reference dashboard: only its harness says when the restart
            # ended (refdash, then done), and HA's load event comes well before that, so a page that
            # finished loading mustn't open the views (it would cut the restart's lines off). The
            # wait for the kiosk's own page is for a kiosk that came back somewhere else.
            on_reference = "/clarity-ref/" in (res.get("own_page_uri") or "")
            finished = not on_reference and own_page_finished(res["own_page_stop_mono"], st["own_stop_at"], C.now())
            if finished or C.now() - st["engine_at"] > a.own_page_wait:
                before = (res["own_page_stop_mono"], res["own_page_loads"])
                calm = quiet_for(2.0, 10.0) if finished else False
                if drain():
                    break
                if (res["own_page_stop_mono"], res["own_page_loads"]) != before:
                    continue  # the kiosk loaded its page again meanwhile: wait for that one
                # finished-moving: a clock card keeps painting; normal, and not a reason to doubt the run.
                res["own_page_state"] = "never-finished" if not finished else ("finished-still" if calm else "finished-moving")
                open_next()
                continue
        if st["next_at"] is not None:
            # The page before has stopped its updates; open the next one on a still screen, so no
            # page is measured over the last one's work. (Its teardown on navigation is part of
            # opening a view, as in HA, and the warm-up makes it the same for every page.)
            if a.memory and st["opened"] and slots[st["opened"] - 1] not in fallback and not st.pop("no_memory", False):
                # RAM after each graded page, between pages: nothing is timed now. The largest of
                # these is the peak during the graded tasks (report.py, ram); the fallback views
                # aren't graded tasks.
                res["memory"].append(dict(memory_sample(dev, [x for x in a.radio_procs.split(",") if x], summaries=False),
                                          after=slots[st["opened"] - 1]))
            paused.clear()
            calm = quiet_for(a.between_quiet, 10.0) if a.between_quiet > 0 else True
            if drain():
                break
            if st["next_at"] is None:
                continue                      # the page went (content_gone): wait for it to come back first
            res["between"].append("still" if calm else "moving")
            open_next()
            continue
        try:
            rec = dev.parse(lines.get(timeout=C.real(0.2)))
        except queue.Empty:
            continue
        if rec and handle(rec):
            break
    if ref and a.memory and st["opened"] == len(slots) and not res["error"] and slots[-1] not in fallback \
            and not (res.get("timed_out") or [None])[-1] == slots[-1] and not st.get("gone_at_end"):
        res["memory"].append(dict(memory_sample(dev, [x for x in a.radio_procs.split(",") if x], summaries=False),
                                  after=slots[-1]))
    lc.terminate()
    if res["error"]:
        pass
    elif res["engine_mono"] is None:
        res["error"] = (f"Vitro never started within {a.timeout:.0f} s" if dev.engine == "vitro"
                        else f"GeckoView never started a page within {a.timeout:.0f} s")
    elif ref and (st["opened"] < len(slots) or st.get("recovering")):
        res["error"] = f"the reference dashboards did not finish within {a.timeout:.0f} s"
    elif ref and st["current"] and not entry_of(st["current"][0]).get("done") and not entry_of(st["current"][0]).get("error") \
            and not entry_of(st["current"][0]).get("timed_out_s"):
        # The last view never said done (smoke10's heavy, with the screen off): not a lost line.
        res["error"] = f"{st['current'][0]} didn't finish within {a.timeout:.0f} s"
    graded_done = ref and all(any((res["tiers"].get(t) or {}).get(k) for k in ("done", "error", "timed_out_s", "content_gone"))
                              for t in tiers)
    if res["error"] and graded_done and fallback and (st["current"] is None or st["current"][0] in fallback):
        # A fallback view that failed fails only itself: the graded views were done. Only the
        # errors above, which come from the view the run stopped in; the screen check comes after.
        res["fallback_error"], res["error"] = res["error"], None
        print(f"  fallback: {res['fallback_error']}", file=sys.stderr)
    # Off while the kiosk restarted after a timed-out view: nothing was measured then.
    if thread_errors:
        # A runner thread died (s1rtc5: a local named like the stop helper made it a bool): what it
        # kept (wakes, gestures, the stream's stop) is gone, so the run isn't trusted. Loud, not quiet.
        res["thread_errors"] = thread_errors
        res["error"] = f"a runner thread failed ({thread_errors[0]}); the run isn't trusted"
    offs = [x for x in res.get("screen_off", []) if x["during"] not in fallback and x["during"] != "recovery"]
    if ref and not res["error"] and offs:
        res["error"] = (f"the screen went off during the graded run ({len(offs)} time(s), first "
                        f"{offs[0]['during']}): with the screen off nothing timed is the panel's")
    for x in res.get("screen_off", []):
        if x["during"] in res["fallback"]:
            # A copy measured with the screen off isn't the panel's either: said on that copy.
            res["fallback"][x["during"]].setdefault("error", "the screen went off during this view")
    # A page that said done without its updates step (smoke7: the line was cut off in logcat) isn't a
    # failed run: its other measurements stand, and the report says what is missing.
    # A view whose page was closed (content_gone) is lost whole, not missing a step.
    res["missing_steps"] = [t for t, e in res["tiers"].items() if "step" not in e and "content_gone" not in e] if ref else []
    if res["missing_steps"]:
        print(f"  {', '.join(res['missing_steps'])}: no updates step; {page_lines.bad} page line(s) didn't parse or never arrived whole",
              file=sys.stderr)
    elif not ref and res["own_page_stop_mono"] is None:
        res["error"] = f"the dashboard did not finish loading within {a.timeout:.0f} s"
    elif not ref:
        # Keep sampling until nothing new has been painted for 2 s (or 20 s pass).
        res["ended_quiet"] = quiet_for(2.0, 20.0)
    if not ref:
        res["page_start_mono"], res["page_stop_mono"] = res["own_page_start_mono"], res["own_page_stop_mono"]
    stop.set()
    kick.set()
    poller.join()
    res["sf_period_ms"] = period[0]
    res["presents_mono_ms"] = sorted(t / 1e6 for t in presents)
    res.update(dev.clock_note())
    if P.clock_stepped(res.get("logcat_clock")) and not res["error"]:
        # Android 6: the wall clock moved during the run, and with it every logcat line after the move; the
        # windows no longer line up with SurfaceFlinger's frames. The run fails, as one with the screen off does.
        res["error"] = (f"the device's wall clock moved {res['logcat_clock']['drift_ms']:+.1f} ms during the run "
                        "(Android 6's logcat stamps lines with it): its timings aren't trusted")
    write_json(os.path.join(a.out, f"{a.cmd}-{a.run}.json"), res)
    if res["error"]:
        sys.exit(f"{a.cmd} {a.run}: {res['error']}")


# The restart page logs done right after its usable line; this long without it, the line was lost.
RESTART_DONE_WAIT_S = 10


# ---- idle: CPU, frames and memory on the dashboard

def cpu_snapshot(dev, pids):
    out = dev.sh("cat /proc/stat; " + "; ".join(f"echo PID {p}; cat /proc/{p}/stat" for p in pids.values()))
    total = P.parse_proc_stat_total(out)
    procs = {}
    for name, pid in pids.items():
        m = re.search(rf"PID {pid}\n(.*)", out)
        if m and ")" in m.group(1):
            procs[name] = P.parse_pid_jiffies(m.group(1))
    return {"total": total, "procs": procs}


def cmd_idle(dev, a):
    radio = [x for x in a.radio_procs.split(",") if x]
    if a.url:
        # A reference dashboard (no nonce: the harness only says it is usable, and the live updates
        # stay off), opened one-off as the graded pages are.
        dev.sh(f"am start -n {dev.component} --es open '{a.url}'")
    dev.hold()
    C.sleep(a.settle + 3)
    pids = dev.pids(radio)
    ncpu = dev.ncpu() or 1
    # Where the log is now, so a screen that goes off in the window can be found in it afterwards.
    mark = last_mono(dev, dev.log_dump(tail=1))
    before = cpu_snapshot(dev, pids)
    # Frames from before the window are still in SurfaceFlinger's history; keep only newer ones.
    period, history = dev.sf()
    newest_before = max(history, default=0)
    presents = set()
    t_end = C.now() + a.window
    due, k = C.mono(), 0
    while C.now() < t_end:
        if SF_POLL_S:
            # As often as the window reads go where the history is shorter (#643), from one read's start to the next.
            due += SF_POLL_S
            C.sleep(max(0.0, due - C.mono()))
            p, ts = sf_poll_read(dev, k)
            k += 1
        else:
            C.sleep(1.5)
            p, ts = dev.sf()
        period = p or period
        presents.update(t for t in ts if t > newest_before)
    after = cpu_snapshot(dev, pids)
    log = dev.log_dump(timeout=120)
    mem = memory_sample(dev, radio)
    write_json(os.path.join(a.out, f"idle-{a.label}-{a.run}.json"), dict({
        "label": a.label, "run": a.run, "window_s": a.window, "ncpu": ncpu,
        "cpu": P.cpu_percent(before, after, ncpu),
        "frames": {"period_ms": period, "presents_mono_ms": sorted(t / 1e6 for t in presents)},
        # The screen going off in the window: its CPU and frames aren't the panel's at work.
        "screen_off": screen_offs(dev, log, mark),
        # What the kiosk ran (voice, Sendspin, the wake word's microphone), from its log.
        "panes_state": P.pane_state(log),
    }, **mem, **dev.clock_note()))


def last_mono(dev, logcat_text):
    """The monotonic time of the last line of a `logcat -d` dump (None if it has none)."""
    recs = [dev.parse(l) for l in logcat_text.splitlines()]
    monos = [r["mono"] for r in recs if r]
    return monos[-1] if monos else None


def screen_offs(dev, logcat_text, since):
    """When the screen went off after `since` (monotonic s), from Android's PowerManagerService."""
    out = []
    for line in logcat_text.splitlines():
        rec = dev.parse(line)
        if rec and (since is None or rec["mono"] > since) and SLEEP_RE.search(rec["msg"]):
            out.append(round(rec["mono"], 3))
    return out


def memory_sample(dev, radio, summaries=True):
    """PSS of every app process (main, :boot, Gecko's GPU, content and crash helper processes) and
    the radio's, with each one's peak RSS, and the device's MemTotal/MemAvailable. Read-only."""
    procs = {}
    for name, pid, pss in P.parse_meminfo_by_process(dev.sh("dumpsys meminfo", timeout=120)):
        role = P.process_role(name, dev.package)
        if role or name in radio:
            hwm = re.search(r"VmHWM:\s+(\d+)", dev.sh(f"cat /proc/{pid}/status"))
            entry = {"pid": pid, "role": role or "radio", "pss_kb": pss, "rss_peak_kb": int(hwm.group(1)) if hwm else None}
            if role and summaries:
                entry["summary"] = P.parse_meminfo_app(dev.sh(f"dumpsys meminfo {pid}", timeout=60))
            procs[name] = entry
    return {"processes": procs, "device_mem": P.parse_proc_meminfo(dev.sh("cat /proc/meminfo"))}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["activity", "device", "hold", "engine", "testdash", "sfcheck", "synthetic", "refstart", "coldstart", "idle", "panes"])
    ap.add_argument("--off", action="store_true", help="hold: end the test hold; testdash: end the test dashboard")
    ap.add_argument("--set", help="panes: the kiosk's pane extras, extra=true|false,...")
    ap.add_argument("--want", choices=["web", "vitro"], help="engine: switch the kiosk to this engine first")
    ap.add_argument("--tries", type=int, default=3, help="panes: attempts")
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--package", default="sh.edm.s1kiosk")
    ap.add_argument("--activity", default="", help="the kiosk's activity (.MainActivity, dev.glassworks.MainActivity); "
                    "default: read from the device (Device.resolve_activity)")
    ap.add_argument("--frame", default="unknown")
    ap.add_argument("--name", default=None, help="device: the frame's display name for the report")
    ap.add_argument("--out", required=True)
    ap.add_argument("--run", type=int, default=0)
    ap.add_argument("--url")
    ap.add_argument("--nonce")
    ap.add_argument("--taps", type=int, default=0)
    ap.add_argument("--label", default="all-on")
    ap.add_argument("--settle", type=float, default=30)
    ap.add_argument("--window", type=float, default=15)
    ap.add_argument("--timeout", type=float, default=None, help="seconds; default 1200 (synthetic), 120 (coldstart)")
    ap.add_argument("--radio-procs", default="otbr-agent,cpcd,zigbeed")
    ap.add_argument("--own-page-wait", type=float, default=30, help="refstart: seconds to wait for the kiosk's own page")
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "page", "tiers.json")) as f:
        order = ",".join(json.load(f)["ORDER"])
    ap.add_argument("--tiers", default=order, help="refstart: reference dashboards (tiers.json ORDER); run k starts k-1 pages in")
    ap.add_argument("--fallback", default="", help="refstart: views opened after the graded ones, measured as detail "
                    "(protocol 6: typical-hls,heavy-hls, the cameras over HLS)")
    ap.add_argument("--warmup-tier", default="", help="refstart: a warm-up page opened first and not graded (protocol 4: none; the restart page is the warm-up)")
    ap.add_argument("--memory", type=int, default=1, help="refstart: sample RAM between the graded pages (0: no)")
    ap.add_argument("--between-quiet", type=float, default=2.0, help="refstart: seconds of still screen before each page opens")
    ap.add_argument("--vsync", default="16.667", help="refstart: the display's refresh period in ms")
    a = ap.parse_args()
    if a.timeout is None:
        # refstart: room for views that time out (VIEW_DEADLINE_S each) and the restarts after them.
        a.timeout = {"hold": 15, "engine": 20, "testdash": 15, "sfcheck": 30, "coldstart": 120, "refstart": 1800}.get(a.cmd, 1200)
    os.makedirs(a.out, exist_ok=True)
    dev = Device(a.serial, a.package, a.activity)
    C.check_device(lambda name: dev.sh(f"getprop {name}"))
    {"activity": cmd_activity, "device": cmd_device, "hold": cmd_hold, "engine": cmd_engine, "testdash": cmd_testdash, "sfcheck": cmd_sfcheck,
     "synthetic": cmd_synthetic, "refstart": cmd_coldstart, "coldstart": cmd_coldstart,
     "idle": cmd_idle, "panes": cmd_panes}[a.cmd](dev, a)


if __name__ == "__main__":
    main()
