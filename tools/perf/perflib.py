"""Parsers and statistics for Clarity, the Glassworks perf test (MIT). Pure functions, no adb.

frame_stats() is the same definition as page/stats.js; test/stats-cases.json pins both.
"""
import calendar
import json
import math
import re
import statistics

BUDGET_MS = 1000 / 60
FROZEN_MS = 700
SF_PENDING = 9223372036854775807


def _r2(x):
    # Same rounding as the page's Math.round(x * 100) / 100 (Python's round() is half-to-even).
    return None if x is None else math.floor(x * 100 + 0.5) / 100


def nearest_rank(sorted_xs, p):
    if not sorted_xs:
        return None
    i = max(0, math.ceil(p * len(sorted_xs)) - 1)
    return sorted_xs[min(i, len(sorted_xs) - 1)]


def frame_stats(intervals_ms, vsync_ms=None):
    """Frame intervals -> median, vsync-snapped p95, jank % (frames over the 16.7 ms budget), frozen %."""
    period = vsync_ms if vsync_ms and vsync_ms > 0 else BUDGET_MS
    xs = [x for x in intervals_ms if isinstance(x, (int, float)) and math.isfinite(x) and x > 0]
    n = len(xs)
    if not n:
        return {"n": 0}
    # JS Math.round rounds .5 up; Python's round() rounds half to even. Match the page.
    snapped = [max(1, math.floor(x / period + 0.5)) * period for x in xs]
    raw = sorted(xs)
    q = sorted(snapped)
    over = sum(1 for x in snapped if x > BUDGET_MS + 0.5)
    frozen = sum(1 for x in xs if x > FROZEN_MS)
    return {
        "n": n,
        "median_ms": _r2(nearest_rank(raw, 0.5)),
        "p95_ms": _r2(nearest_rank(q, 0.95)),
        "p95_raw_ms": _r2(nearest_rank(raw, 0.95)),
        "max_ms": _r2(raw[-1]),
        "fps": _r2(1000 * n / sum(xs)),
        "jank_pct": _r2(100 * over / n),
        "frozen_pct": _r2(100 * frozen / n),
    }


def intervals(ts):
    return [b - a for a, b in zip(ts, ts[1:])]


def spread(values):
    """Median and range of repeated runs; None values (a run that measured nothing) are dropped."""
    xs = [v for v in values if v is not None]
    if not xs:
        return None
    return {"median": _r2(statistics.median(xs)), "min": _r2(min(xs)), "max": _r2(max(xs)), "n": len(xs)}


# d2(n): the expected range of n normal samples in standard deviations (the control-chart constant).
D2 = {2: 1.128, 3: 1.693, 4: 2.059, 5: 2.326, 6: 2.534, 7: 2.704}


def noise_band(xs):
    """Twice an estimate of one sample's standard deviation (#186), or None under two samples.
    Up to seven samples: the range / d2(n), since the median absolute deviation of three samples is
    the smaller of two gaps and is 0 when two agree, and at five it still averages only 1.64 σ. From
    eight: 1.4826 × the median absolute deviation (about 1.8 σ there), so one odd sample doesn't set
    it. Both estimate σ, so the band doesn't grow with more samples.
    test_report.NoiseBand checks it by simulation."""
    if len(xs) < 2:
        return None
    if len(xs) in D2:
        return 2 * (max(xs) - min(xs)) / D2[len(xs)]
    m = statistics.median(xs)
    return 2 * 1.4826 * statistics.median(abs(x - m) for x in xs)


# ---- SurfaceFlinger `dumpsys SurfaceFlinger --latency <layer>`
# Line 1 is the refresh period in ns; each later line is desired-present, actual-present and
# frame-ready time in ns (CLOCK_MONOTONIC). Unpresented rows are 0 or INT64_MAX.

def parse_sf_latency(text):
    lines = [l.split() for l in text.strip().splitlines() if l.strip()]
    if not lines or len(lines[0]) != 1 or not lines[0][0].isdigit():
        return None, []
    period_ms = int(lines[0][0]) / 1e6
    presents = []
    for parts in lines[1:]:
        if len(parts) >= 2 and parts[1].isdigit():
            t = int(parts[1])
            if 0 < t < SF_PENDING:
                presents.append(t)
    return period_ms, sorted(set(presents))


# Android 15 and later list each layer as `RequestedLayerState{<name> parentId=… z=…}` (SurfaceFlinger's new
# front end): the name, then only these fields. Android 17 (API 37) also starts a SurfaceView's name with the
# view's hash: `f5fea55 SurfaceView[<pkg>/<activity>](BLAST)#163`. `--latency` takes only the whole name, the hash
# and the `#<id>` included; any other form answers with the refresh period alone, as an unknown name does
# (API 35 and 37 emulators, 2026-11-19). The Pixel Tablet's Android 17 run named no layer before this.
REQUESTED_LAYER_RE = re.compile(r"^RequestedLayerState\{(.*?)(?: (?:parentId|relativeParentId|z|layerStack)=-?\d+| !handle)*\}$")
# The view's hash in front of a SurfaceView's name (Android 17).
VIEW_HASH_RE = re.compile(r"^[0-9a-f]+ (?=SurfaceView)")


def sf_layer_name(line):
    """One `dumpsys SurfaceFlinger --list` line -> the layer's name, as `--latency` takes it."""
    line = line.strip()
    m = REQUESTED_LAYER_RE.match(line)
    return m.group(1) if m else line


def surfaceview_layers(listing, package):
    """The app's SurfaceView layers in `dumpsys SurfaceFlinger --list`, by Android's naming:
    `SurfaceView - <pkg>/<activity>#0` (Android 10, the S1); `SurfaceView[<pkg>/<activity>]#12` and its
    `(BLAST)` buffer layer (Android 12 and later, from AOSP's SurfaceView.java; the BLAST one holds the frames),
    wrapped in `RequestedLayerState{…}` on Android 15 and later and with the view's hash in front on 17
    (sf_layer_name; the names come back whole, for `--latency`);
    plain `SurfaceView` on Android 6, without the app (checked on an API 23 emulator, 2026-11-18): then that one,
    while the app has a window listed, as a kiosk is the only app on screen. Never their `Background for` layers."""
    listed = [sf_layer_name(l) for l in listing.splitlines()]
    names = [l for l in listed if VIEW_HASH_RE.sub("", l).startswith((f"SurfaceView - {package}/", f"SurfaceView[{package}/"))]
    if not names and "SurfaceView" in listed and any(l.startswith(f"{package}/") for l in listed):
        names = ["SurfaceView"]
    return names


def current_size(text):
    """(width, height) from the first display's `cur=WxH` in `dumpsys window displays`, or None."""
    m = re.search(r"\bcur=(\d+)x(\d+)", text)
    return (int(m.group(1)), int(m.group(2))) if m else None


def wm_size(text):
    """(width, height) from `wm size` (an override size, listed last, wins), or None."""
    m = re.findall(r"(\d+)x(\d+)", text)
    return (int(m[-1][0]), int(m[-1][1])) if m else None


def layer_number(name):
    """'SurfaceView - pkg/pkg.MainActivity#3' -> 3 (a newer layer of the same name gets a higher #n)."""
    m = re.search(r"#(\d+)$", name)
    return int(m.group(1)) if m else -1


def page_to_mono_ms(page, offset_ms):
    """A time on the page's clock (ms after performance.timeOrigin) -> logcat's monotonic clock, in ms.
    The page's log line carries Date.now() and logcat stamps the same line, which ties the clocks
    together (to within the few ms the console takes to reach logcat)."""
    return page["mono"] * 1000 - (page["epoch_ms"] - (page["origin_epoch_ms"] + offset_ms))


def presents_in(presents_ns, start_s, end_s):
    """Present times (ms) inside a monotonic window given in seconds."""
    lo, hi = start_s * 1e9, end_s * 1e9
    return [t / 1e6 for t in presents_ns if lo <= t <= hi]


# ---- logcat -v monotonic:  "  4945.980 15843 15981 I Tag     : message"

LOGCAT_RE = re.compile(r"^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.*?)\s*:\s(.*)$")


def parse_logcat_line(line):
    m = LOGCAT_RE.match(line.rstrip("\n"))
    if not m:
        return None
    return {"mono": float(m.group(1)), "pid": int(m.group(2)), "level": m.group(4), "tag": m.group(5).strip(), "msg": m.group(6)}


# ---- logcat -v usec (Android 6, which has no -v monotonic): "11-18 23:06:20.726229  1656  1656 D Tag: message",
# the device's wall clock in its own time zone, with no year.

LOGCAT_USEC_RE = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{6})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.*?)\s*:\s(.*)$")
NOW_AT_RE = re.compile(r"now at (\d+) nsecs")


def _wall_s(year, month, day, h, mi, s, us):
    """A logcat wall-clock stamp as seconds, read as UTC: only differences between stamps are used."""
    return calendar.timegm((year, month, day, h, mi, s)) + us / 1e6


class LogClock:
    """How logcat stamps a line, and how that becomes the device's monotonic clock (CLOCK_MONOTONIC, in s),
    the clock SurfaceFlinger's present times are on.

    "monotonic": `logcat -v monotonic` prints it. "usec" (Android 6): the device's wall clock to the µs, minus
    `offset_s`, which wall_offset() measures once per runner process. `err_ms` bounds that offset's error; a
    change of the wall clock during the process (a time zone, a daylight-saving or a network-time step) moves
    every later line by that much."""

    def __init__(self, fmt="monotonic", offset_s=0.0, err_ms=0.0, year=None, month=None):
        self.fmt, self.offset_s, self.err_ms, self.year, self.month = fmt, offset_s, err_ms, year, month

    def parse(self, line):
        if self.fmt == "monotonic":
            return parse_logcat_line(line)
        m = LOGCAT_USEC_RE.match(line.rstrip("\n"))
        if not m:
            return None
        month = int(m.group(1))
        # No year in the stamp: the one nearest the offset's sample, so a run over New Year stays in order.
        year = self.year + (1 if month < self.month - 6 else -1 if month > self.month + 6 else 0)
        wall = _wall_s(year, month, *(int(m.group(i)) for i in range(2, 7)))
        return {"mono": round(wall - self.offset_s, 6), "pid": int(m.group(7)), "level": m.group(9),
                "tag": m.group(10).strip(), "msg": m.group(11)}


# How far the wall-clock offset may move within one runner step beyond the two calibrations' own errors before
# the step's timings stop being trusted: a step of the wall clock (network time, daylight saving) moves every
# later logcat line, and its windows away from SurfaceFlinger's frames. The emulator's moved 0.35 ms at most
# (2026-11-18).
CLOCK_STEP_TOLERANCE_MS = 5.0


def clock_stepped(note):
    """True when a step's `logcat_clock` (perfrun.py Device.clock_note) says its offset moved more than both
    calibrations' errors plus CLOCK_STEP_TOLERANCE_MS. Never on a monotonic logcat (no note)."""
    if not note:
        return False
    return abs(note["drift_ms"]) > note["offset_err_ms"] + note["end_err_ms"] + CLOCK_STEP_TOLERANCE_MS


def wall_offset(sandwich, marks, nonce, year):
    """The usec LogClock from paired samples: `sandwich` has one "CLOCKPAIR <i> now at <ns> nsecs now at <ns>
    nsecs" line per sample, CLOCK_MONOTONIC read from /proc/timer_list just before and just after a log line
    "<nonce> <i>" was written; `marks` is `logcat -v usec` holding those lines. The line's stamp lies between
    the two reads, so the tightest pair gives the offset to within half its width. None without a pair."""
    reads = {}
    for line in sandwich.splitlines():
        m = re.match(r"CLOCKPAIR (\d+) (.*)$", line.strip())
        ns = NOW_AT_RE.findall(m.group(2)) if m else []
        if len(ns) == 2:
            reads[m.group(1)] = (int(ns[0]) / 1e9, int(ns[1]) / 1e9)
    best = None
    for line in marks.splitlines():
        m = LOGCAT_USEC_RE.match(line.rstrip("\n"))
        if not m or not m.group(11).startswith(nonce + " "):
            continue
        i = m.group(11)[len(nonce) + 1:].strip()
        if i not in reads:
            continue
        before, after = reads[i]
        month = int(m.group(1))
        wall = _wall_s(year, month, *(int(m.group(k)) for k in range(2, 7)))
        cand = (after - before, wall - (before + after) / 2, month)
        if best is None or cand[0] < best[0]:
            best = cand
    if best is None:
        return None
    width, offset, month = best
    return LogClock("usec", offset, round(width * 1000 / 2, 3), year, month)


class PageLines:
    """Page console lines -> payloads. A long line comes in numbered parts ("GWPERF+ key k/n chunk",
    stats.js consoleLines), because logcat keeps only 1023 bytes of a page's console line. feed()
    returns the payload once a whole line is in, else None. A GWPERF line that doesn't parse (cut
    off) is counted in `bad`, never dropped silently."""

    PART_RE = re.compile(r"(\S+) (\d+)/(\d+) (.*)$", re.S)

    def __init__(self):
        self.parts, self.bad = {}, 0

    def _parse(self, text):
        try:
            pl = json.loads(text)
        except ValueError:
            self.bad += 1
            return None
        return pl if isinstance(pl, dict) else None

    def feed(self, msg):
        i = msg.find("GWPERF+ ")
        if i >= 0:
            m = self.PART_RE.match(msg[i + 8:])
            if not m:
                self.bad += 1
                return None
            key, k, n, chunk = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4)
            got = self.parts.setdefault(key, {})
            got[k] = chunk
            if len(got) < n:
                return None
            del self.parts[key]
            return self._parse("".join(got[j] for j in range(n))) if all(j in got for j in range(n)) else None
        i = msg.find("GWPERF ")
        return self._parse(msg[i + 7:]) if i >= 0 else None

    def close(self, nonce):
        """The page `nonce` is done: a line of its whose parts never all arrived is lost. Counted in
        `bad`, and returned."""
        lost = [k for k in self.parts if k.split(":")[0] == nonce]
        for k in lost:
            del self.parts[k]
        self.bad += len(lost)
        return len(lost)


def gwperf_payload(msg):
    i = msg.find("GWPERF ")
    if i < 0:
        return None
    try:
        return json.loads(msg[i + 7:])
    except ValueError:
        return None


DISPLAYED_RE = re.compile(r"Displayed (\S+): \+(?:(\d+)s)?(\d+)ms")


def parse_displayed(msg, package):
    """ActivityManager's TTID line for one of the package's activities, `Displayed pkg/.Activity: +1s714ms` ->
    1714. Any of them: a home app relaunched by the system shows under its launcher alias (the generic app's
    `dev.glassworks.generic.Launcher`), not under the activity the runner sends its intents to."""
    m = DISPLAYED_RE.search(msg)
    if not m or m.group(1).split("/")[0] != package:
        return None
    return int(m.group(2) or 0) * 1000 + int(m.group(3))


# ---- memory

APP_SUMMARY_KEYS = {
    "Java Heap": "java_heap_kb", "Native Heap": "native_heap_kb", "Code": "code_kb", "Stack": "stack_kb",
    "Graphics": "graphics_kb", "Private Other": "private_other_kb", "System": "system_kb",
}


def parse_meminfo_app(text):
    """`dumpsys meminfo <pid>` App Summary in KB, plus total_pss_kb."""
    out = {}
    in_summary = False
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("App Summary"):
            in_summary = True
            continue
        if in_summary:
            m = re.match(r"([A-Za-z ]+):\s+(\d+)", s)
            if m and m.group(1) in APP_SUMMARY_KEYS:
                out[APP_SUMMARY_KEYS[m.group(1)]] = int(m.group(2))
            m = re.match(r"TOTAL(?: PSS)?:\s+(\d+)", s)
            if m:
                out["total_pss_kb"] = int(m.group(1))
                break
    return out


# "209,946K: name (pid 4300)"; Android 6 writes "65128 kB: name (pid 1218)".
PSS_LINE_RE = re.compile(r"^\s*([\d,]+) ?(?:K|kB): (\S+) \(pid (\d+)")


def parse_meminfo_by_process(text):
    """`dumpsys meminfo` "Total PSS by process" -> [(name, pid, pss_kb)]."""
    out = []
    in_section = False
    for line in text.splitlines():
        if line.startswith("Total PSS by process"):
            in_section = True
            continue
        if in_section:
            if not line.strip():
                break
            m = PSS_LINE_RE.match(line)
            if m:
                out.append((m.group(2), int(m.group(3)), int(m.group(1).replace(",", ""))))
    return out


def process_role(name, package):
    """sh.edm.s1kiosk:sandboxed_tab_disable_art_image_31 -> 'tab'; the legacy engine's :tab32 -> 'tab'; the app
    itself -> 'main'."""
    if name == package:
        return "main"
    if not name.startswith(package + ":"):
        return None
    role = name[len(package) + 1:]
    role = re.sub(r"_disable_art_image_\d*$", "", role)
    return "tab" if role.startswith("sandboxed_tab") or re.fullmatch(r"tab\d*", role) else role


def parse_proc_meminfo(text):
    out = {}
    for line in text.splitlines():
        m = re.match(r"(\w+):\s+(\d+) kB", line)
        if m:
            out[m.group(1)] = int(m.group(2))
    return {"mem_total_kb": out.get("MemTotal"), "mem_available_kb": out.get("MemAvailable")}


def parse_ps(text):
    """`ps -A -o PID,NAME` -> [(pid, name)]. Android 6's ps ignores those options and prints its own columns
    (USER PID PPID VSIZE RSS WCHAN PC S NAME): the PID column by its header, the name last."""
    lines = text.splitlines()
    if not lines:
        return []
    head = lines[0].split()
    if "PID" not in head:
        return []
    own = head == ["PID", "NAME"]
    col = head.index("PID")
    out = []
    for line in lines[1:]:
        parts = line.split(None, 1) if own else line.split()
        if len(parts) >= (2 if own else col + 2) and parts[col].isdigit():
            out.append((int(parts[col]), parts[-1].strip()))
    return out


# ---- CPU from /proc

def parse_proc_stat_total(text):
    """First `cpu` line of /proc/stat -> (busy_jiffies, total_jiffies)."""
    for line in text.splitlines():
        if line.startswith("cpu "):
            f = [int(x) for x in line.split()[1:]]
            idle = f[3] + (f[4] if len(f) > 4 else 0)
            return sum(f) - idle, sum(f)
    return None


def parse_pid_jiffies(text):
    """/proc/<pid>/stat -> utime + stime. The comm field can hold spaces, so split after ')'."""
    rest = text[text.rfind(")") + 2:].split()
    return int(rest[11]) + int(rest[12])


def cpu_percent(before, after, ncpu):
    """Per-process and whole-device CPU over a window, in % of one core and % of the device."""
    busy0, tot0 = before["total"]
    busy1, tot1 = after["total"]
    dt = tot1 - tot0
    if dt <= 0:
        return None
    procs = {}
    for name, j0 in before["procs"].items():
        if name in after["procs"]:
            d = after["procs"][name] - j0
            procs[name] = _r2(100 * d / dt * ncpu)
    return {"device_busy_pct": _r2(100 * (busy1 - busy0) / dt), "procs_pct_of_core": procs}


# ---- what the kiosk started with (the S1's panes), from its log. Read-only.

ESPHOME_START_RE = re.compile(r"ESPHome device .*\bvoice=(true|false)")
# Each pane with a switch, at start and when it is turned on or off (#67).
PANE_SWITCH_RE = re.compile(r"\bPane ([a-z0-9-]+) (on|off)$")


def pane_state(logcat_text):
    """The panes the kiosk's current process runs, from its log (`logcat -d`): `voice` (the ESPHome
    line it logs at start, `voice=`), `sendspin` (its player started after that line), `mic` (the
    wake word's microphone open: voice on, opened, not muted since) and `pane:<id>` for each pane the
    kiosk says is on or off (#67). None when the log has no start line (it rotated, or the kiosk
    hasn't started). Reads the lines only; keeps nothing of them."""
    lines = logcat_text.splitlines()
    idx = None
    for i, line in enumerate(lines):
        if ESPHOME_START_RE.search(line):
            idx = i
    if idx is None:
        return {"voice": None, "sendspin": None, "mic": None}
    voice = ESPHOME_START_RE.search(lines[idx]).group(1) == "true"
    sendspin, mic = False, False
    switched = {}
    for line in lines[idx + 1:]:
        m = PANE_SWITCH_RE.search(line)
        if m:
            switched["pane:" + m.group(1)] = m.group(2) == "on"
        elif "Sendspin player starting" in line:
            sendspin = True
        elif "Microphone open" in line or "Microphone unmuted" in line:
            mic = True
        elif "Microphone muted" in line:
            mic = False
    return {"voice": voice, "sendspin": sendspin, "mic": voice and mic, **switched}
