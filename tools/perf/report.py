#!/usr/bin/env python3
"""Build report.json and report.md from a tools/perf run directory (MIT). Offline; no adb.

    python3 tools/perf/report.py <run-dir> [--apk app.apk ...] [--budgets frames/<id>/budgets.json]
                                 [--out DIR] [--app-version LABEL]

--out writes report.json and report.md there instead of into the run directory (a published copy,
e.g. frames/<id>/clarity/; the run's own files stay as they are). --app-version shows LABEL as the
app's version, in place of the version the device reported (e.g. "pre-v1" for a build before the
app's first release): report.md, report.json and the APK sizes show it as given, and nothing in the
grade reads it.

The report schema (glassworks.perf/4) is documented in docs/perf.md.
"""
import argparse
import copy
import glob
import json
import os
import re
import statistics
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import grade as G  # noqa: E402
import notes as N  # noqa: E402
import perflib as P  # noqa: E402

SCHEMA = "glassworks.perf/4"
HERE = os.path.dirname(os.path.abspath(__file__))
# Idle samples on Clarity's reference dashboard carry this prefix (run.sh): `ref-idle` after each
# graded run, `ref-panes-off` and `ref-<pane>-on` for the panes. They aren't real use.
REF_LABEL = "ref-"
SETTLE_GAP_MS = 750
VIEW_DEADLINE_S = 300                 # perfrun.py's defaults, for report.md's words
STAGE_IDLE_S = 180
# The page's times reach logcat's clock through its log line, which logcat stamps a few ms late
# (nav start mapped 11 to 23 ms before PageStart on the S1, 2026-11-12). The frame that shows the
# first paint may so appear up to this much before it; three refreshes at 60 Hz.
PAINT_SLACK_MS = 50
# Protocol 6 (#202): the HLS copies of the camera pages (page/tiers.json FALLBACK), measured as detail.
with open(os.path.join(HERE, "page", "tiers.json")) as _f:
    CAMERA_FALLBACK = json.load(_f).get("FALLBACK", {})
FAMILIES = ["css-transform", "css-opacity", "svg", "canvas2d", "ha-cards"]


def read_json(path):
    with open(path) as f:
        return json.load(f)


def read_jsonl(path):
    with open(path) as f:
        return [json.loads(l) for l in f if l.strip()]


# ---- frames

def sf_intervals(pres, win, period):
    """Frame intervals over the whole window. Gaps at its edges count when they are longer than 1.5
    refreshes (a frame was due and none came); a shorter edge gap is only the vsync phase."""
    out = P.intervals(pres)
    if not pres:
        return [win[1] - win[0]]
    for gap in (pres[0] - win[0], win[1] - pres[-1]):
        if gap > 1.5 * period:
            out.append(gap)
    return out


def step_frames(pl, motion=True):
    """Frame stats for one window, graded on the worse of SurfaceFlinger and rAF.

    rAF misses frames the compositor drops (CSS animations run off the main thread); SurfaceFlinger
    misses main-thread jank while the compositor still animates something. SurfaceFlinger's data is
    complete when its history reached back before the window: then every gap in the window, edges
    included, is a real stall. If it didn't (evicted), or nothing is meant to move (motion=False:
    a still page presents nothing, which isn't a stall), only rAF is used."""
    raf = pl.get("raf") or {"n": 0}
    sf = pl.get("sf") or {}
    pres = sf.get("presents_ms") or []
    win, period, oldest = sf.get("window_ms"), sf.get("period_ms"), sf.get("oldest_ms")
    if "oldest_ms" in sf:
        sf_valid = bool(motion and win and period and oldest is not None and oldest <= win[0])
    else:  # runs from before oldest_ms was recorded
        sf_valid = bool(motion and win and period and len(pres) > 1)
    sf_stats = P.frame_stats(sf_intervals(pres, win, period), period) if sf_valid else {"n": 0}

    def key(st):
        return (G.ORDER.get(G.frame_band(st), -1), st.get("p95_ms") or 0, st.get("jank_pct") or 0)

    if not sf_valid:
        chosen, source, limited = raf, "raf", None
    elif key(sf_stats) > key(raf):
        chosen, source, limited = sf_stats, "surfaceflinger", "compositor"
    elif key(raf) > key(sf_stats):
        chosen, source, limited = raf, "raf", "main-thread"
    else:
        chosen, source, limited = sf_stats, "surfaceflinger", "equal"
    return {"source": source, "limited_by": limited, "stats": chosen, "raf": raf, "sf": sf_stats, "sf_valid": sf_valid}


# ---- the test page (detail, not graded)

def synthetic_run(lines):
    hello = next((l for l in lines if l["t"] == "hello"), {})
    steps = [dict(stage=l["stage"], n=l["n"], scrolled_px=l.get("scrolled_px"), **step_frames(l)) for l in lines if l["t"] == "step"]
    js = next((l for l in lines if l["t"] == "js"), None)
    ladder = {}
    for fam in FAMILIES:
        fs = [(s["n"], s["stats"]) for s in steps if s["stage"] == fam]
        if fs:
            ladder[fam] = {"held_60fps": G.capacity(fs, {G.GOOD}),
                           "held_30fps": G.capacity(fs, {G.GOOD, G.NI}),
                           "top_step": max(n for n, _ in fs),
                           "steps": [{"n": s["n"], "source": s["source"], "limited_by": s["limited_by"],
                                      "sf_valid": s["sf_valid"], "frames": s["stats"]} for s in steps if s["stage"] == fam]}
    scroll = next((s for s in steps if s["stage"] == "scroll"), None)
    return {
        "ua": hello.get("ua"), "page_load": hello.get("load") or {}, "dpr": hello.get("dpr"),
        "ladder": ladder,
        "scroll": scroll and {"source": scroll["source"], "scrolled_px": scroll["scrolled_px"],
                              "moved": bool(scroll["scrolled_px"]), **scroll["stats"]},
        "js": js and {"median_ms": js["median_ms"], "p95_ms": js["p95_ms"],
                      "checksum_ok": js["checksum"] == js["expected"]},
        "complete": any(l["t"] == "done" for l in lines),
        # The screen went off while the page ran (perfrun.py synthetic): its frames aren't the panel's at work.
        "screen_off": any(l["t"] == "screen_off" for l in lines),
    }


def geckoview_version(ua):
    m = re.search(r"rv:(\d+(?:\.\d+)*)", ua or "")
    return m.group(1) if m else None


def synthetic_summary(all_runs):
    # A run the screen went off in is listed (per_run) but left out of the medians.
    runs = [r for r in all_runs if not r.get("screen_off")]

    def sp(f):
        return P.spread([f(r) for r in runs])

    ladder = {}
    for fam in FAMILIES:
        if any(fam in r["ladder"] for r in runs):
            ladder[fam] = {k: sp(lambda r, k=k: (r["ladder"].get(fam) or {}).get(k)) for k in ("held_60fps", "held_30fps")}
            # Held at the ladder's last step in every run: the device can do more than the ladder asks.
            fam_runs = [r["ladder"][fam] for r in runs if fam in r["ladder"]]
            for k in ("held_60fps", "held_30fps"):
                ladder[fam][k + "_at_top"] = all(x[k] == x["top_step"] for x in fam_runs)
    return {
        "runs": len(runs),
        "screen_off_runs": len(all_runs) - len(runs),
        "complete_runs": sum(1 for r in runs if r["complete"]),
        "ladder": ladder,
        "steps_without_sf": sum(1 for r in runs for f in r["ladder"].values() for st in f["steps"] if not st["sf_valid"]),
        "scroll_moved": all(r["scroll"]["moved"] for r in runs if r["scroll"]) if any(r["scroll"] for r in runs) else None,
        "scroll": {k: sp(lambda r, k=k: (r["scroll"] or {}).get(k)) for k in ("p95_ms", "jank_pct", "fps")},
        "js_burst_ms": sp(lambda r: (r["js"] or {}).get("median_ms")),
        "js_checksum_ok": all((r["js"] or {}).get("checksum_ok") for r in runs) if runs else None,
        "page_lcp_ms": sp(lambda r: r["page_load"].get("lcp_ms") or r["page_load"].get("fcp_ms")),
        # Engines without LCP report FCP instead, an earlier paint, so the load reads as faster.
        "page_lcp_from": sorted({r["page_load"].get("lcp_from") or ("lcp" if r["page_load"].get("lcp_ms") else "fcp")
                                 for r in runs if r["page_load"]}),
    }


# ---- the reference dashboards (graded)

def settle_before(frames_ms, loaded_ms, live_ms, gap_ms=SETTLE_GAP_MS):
    """When a reference dashboard finished loading: the first frame followed by gap_ms with no new
    frame, where the quiet reaches past the load event (a pause before the load event is mid-load),
    and never before the load event. Frames are those between navigation start and the page going
    live; the page is still for 2 s before that, so the quiet before live_ms counts as a gap.
    Returns (ms, None), or (None, why) when it can't be measured: never 0 by default."""
    fs = sorted(frames_ms)
    if not fs:
        return None, "no frames on screen while it loaded"
    for i, f in enumerate(fs):
        nxt = fs[i + 1] if i + 1 < len(fs) else live_ms
        if nxt - f >= gap_ms and nxt > loaded_ms:
            return max(f, loaded_ms), None
    return None, "it never went still before going live"


def first_paint_on_screen(presents_ms, fcp_ms, live_ms):
    """The first frame that can show the page's first paint: at or after it (less PAINT_SLACK_MS for
    the clock mapping). Frames before it are the previous page or a blank one."""
    return next((t for t in sorted(presents_ms) if fcp_ms - PAINT_SLACK_MS <= t <= live_ms), None)


def usable_on_screen(page, presents_ms, period=None, free=True):
    """Protocol 4: when HA's real frontend was usable, on logcat's clock (ms), or (None, why). The
    harness logs the animation frame in which every first-screen card had rendered and the moment
    a posted task ran within 50 ms (the main thread free). Usable is the first frame SurfaceFlinger
    presented that can carry that render (one refresh after its frame at the earliest), and never
    before the main thread was free."""
    u = (page or {}).get("usable")
    if not u or u.get("rendered_tick_ms") is None:
        return None, "the page didn't report when it was usable"
    if u.get("head_cards") is not None and (u.get("first_screen_cards") or 0) < u["head_cards"]:
        # F1: every view's head (10 cards) is on its first screen; fewer means it wasn't all drawn.
        return None, f"usable with {u.get('first_screen_cards')} cards on the first screen, fewer than the {u['head_cards']} head cards"
    period = period or P.BUDGET_MS
    earliest = P.page_to_mono_ms(page, u["rendered_tick_ms"]) + period - PRESENT_SLACK_MS
    shown = next((t for t in sorted(presents_ms) if t >= earliest), None)
    if shown is None:
        return None, "no frame on screen after the dashboard rendered"
    if not free:
        # Rubric 2's open: the first screen drawn, on screen (the paint LCP times), not waiting for
        # the main thread to be free; that wait is its own task (blocking_ms).
        return shown, None
    at = P.page_to_mono_ms(page, u["interactive_ms"]) if u.get("interactive_ms") is not None else shown
    return max(shown, at), None


def change_frames(step, period=None):
    """The live-update frames that had a change to draw. A change is due at a fixed time, runs when the
    main thread gets to it, and its transition lasts d ms (HA's 180 ms on a tile; 0 for text). A rAF
    interval (a, b] counts when it overlaps some change's [due, ran + d]: the frame that should draw
    it, and each frame of its transition. Idle frames (nothing due or moving) don't count: on a still
    screen there is nothing to be late for. A change that runs late (the main thread was busy) makes
    the frame it was due in long, so that counts against the page.
    Returns (stats, None) or (None, why)."""
    times, chg = step.get("raf_times"), step.get("changes")
    if times is None or chg is None:
        return None, "no change log (a page from before it was added)"
    if not chg:
        return None, "nothing changed during the window"
    ts = sorted(times)
    # A change that never arrived (protocol 4: the main thread didn't get to it) spans to the end.
    end = ts[-1] if ts else 0
    spans = [(c[0], (c[1] if c[1] is not None else end) + c[2]) for c in chg]
    xs = [b - a for a, b in zip(ts, ts[1:]) if any(due < b and end >= a for due, end in spans)]
    return P.frame_stats(xs, period), None


# A change with no frame on screen by the window's end, and at least this long before it, counts as late as the
# window can show: from the change to the window's end, a lower bound (the change may show later still). It must
# never pass a level (CodeRabbit on #656), so its time is at least UNSHOWN_FLOOR_MS: just past the fluid bar. The
# cutoff stays fluid's 250 ms as it was, so the same changes count as before and a stored report's ranks hold.
NO_FRAME_MS = max(lv["latency_p95_max_ms"] for lv in G.UPDATE_LEVELS)
UNSHOWN_FLOOR_MS = NO_FRAME_MS + 0.1
# SurfaceFlinger's present times and the page's frame times are on clocks tied by the page's log
# line; allow this much for that and for vsync jitter when finding the first frame that can show it.
PRESENT_SLACK_MS = 3


def change_to_screen(step, page, period=None):
    """The graded live-updates measure (rubric v5): per change, from when it started (protocol 4,
    `from: sent`: when the fake Home Assistant sent it, from the stream's schedule; before, when the
    page applied it) to the first frame on screen that can show it. On HA's frontend the page reads a
    change on its main thread, so a busy main thread holds it back before anything is drawn: that
    wait counts. A change that hadn't arrived when the window was logged has no frame either. The change is drawn in the next animation frame
    (tick), which reaches the screen one refresh later at the earliest; presents before that are
    frames already on their way. A change with no frame by the window's end, at least NO_FRAME_MS
    after it, counts as the time to the window's end (conservative).
    A change SurfaceFlinger's history doesn't reach back to isn't counted.
    Returns ({n, median_ms, p95_ms, max_ms, skipped}, None) or (None, why)."""
    sf = step.get("sf") or {}
    pres = sorted(sf.get("presents_ms") or [])
    period = period or sf.get("period_ms") or P.BUDGET_MS
    if not page or step.get("t0") is None or step.get("changes") is None:
        return None, "no change log (a page from before it was added)"
    if not pres:
        return None, "no frames from SurfaceFlinger in the window"
    oldest = sf.get("oldest_ms")
    end = (sf.get("window_ms") or [None, None])[1]
    raf = sorted(step.get("raf_times") or [])
    out, skipped = [], 0
    sent_mode = step.get("from") == "sent"
    for c in step["changes"]:
        start, ran = (c[0] if sent_mode else c[1]), c[1]
        if start < 0:
            continue                      # before the window; its transition ran into it
        m_start = P.page_to_mono_ms(page, step["t0"] + start)
        if oldest is not None and m_start < oldest:
            skipped += 1
            continue
        if ran is None:
            nxt = None                    # it never reached the page in the window: no frame shows it
        else:
            tick = c[4] if len(c) > 4 and c[4] is not None else next((r for r in raf if r > ran), None)
            if tick is None and not sent_mode:
                skipped += 1
                continue
            m_ran = P.page_to_mono_ms(page, step["t0"] + ran)
            nxt = None if tick is None else next(
                (t for t in pres if t >= max(P.page_to_mono_ms(page, step["t0"] + tick) + period - PRESENT_SLACK_MS, m_ran)), None)
        if nxt is not None:
            out.append(round(nxt - m_start, 1))
        elif end is not None and end - m_start >= NO_FRAME_MS:
            out.append(max(round(end - m_start, 1), UNSHOWN_FLOOR_MS))
        else:
            skipped += 1                  # too close to the window's end to tell
    if not out:
        return None, "SurfaceFlinger showed none of the changes"
    if len(out) < skipped:
        return None, f"SurfaceFlinger covered only {len(out)} of {len(out) + skipped} changes"
    xs = sorted(out)
    return {"n": len(xs), "median_ms": P.nearest_rank(xs, 0.5), "p95_ms": P.nearest_rank(xs, 0.95),
            "max_ms": xs[-1], "skipped": skipped,
            # The rule its unshown changes were counted by (Clarity v1): regrade_report trusts a stored p95 with it.
            "unshown_floor_ms": UNSHOWN_FLOOR_MS}, None


def _good_max_gap_ms():
    """The longest gap the camera's "good" level allows (rubric.json camera_levels), by name."""
    good = next((lv for lv in G.CAMERA_LEVELS if lv.get("level") == "good"), None)
    if not good or "max_gap_max_ms" not in good:
        raise RuntimeError("rubric.json camera_levels needs a 'good' level with max_gap_max_ms (report.CAMERA_UNCOVERED_MAX_MS)")
    return good["max_gap_max_ms"]


# The most of a camera's window its receiver counts may leave out: the longest gap "good" allows
# (500 ms). A longer stretch could hold a stall the counts never saw.
CAMERA_UNCOVERED_MAX_MS = _good_max_gap_ms()
# Fluid's bar on the stalls added up (1000 ms), for the report's words.
FLUID_STALL_TOTAL_MS = next(lv["stall_total_max_ms"] for lv in G.CAMERA_LEVELS if "stall_total_max_ms" in lv)


def camera_run(line, tier):
    """One run's live cameras (#174), from the harness's camera line: the worst stream's first frame
    (from the navigation, so it includes building the view), share of frames shown, longest gap and stalls (gaps over 500 ms) added
    up, and each stream as detail. A stream that never showed a frame makes the run's first frame
    and shown None, which fails the task (it was measured: the panel didn't play it). None on a view
    without streams, or when the line never came (then the task isn't measured)."""
    if tier not in G.CAMERA_PAGES or not line:
        return None
    streams = line.get("streams") or []
    played = [s for s in streams if s.get("first_frame_ms") is not None and s.get("shown_pct") is not None]
    # Streams that showed a frame but whose stalls or gaps couldn't be measured: their receiver
    # reported no freeze counts (stall_total_ms null), or there was no span to measure gaps over (one
    # sample, nothing decoded: max_gap_ms null). Both are n/a, never 0.
    unreported = [s for s in played if s.get("stall_total_ms") is None or s.get("max_gap_ms") is None]
    # Streams whose receiver counts cover less of the window than a stall can hide in (#255): a busy
    # page answers getStats late, and the counts end at the last answer back by statsWaitMs. What
    # came after it is unknown, not stall-free, so these stalls and gaps are n/a too.
    window = line.get("window_ms")
    short = [s for s in played if s.get("span_ms") is not None and window and window - s["span_ms"] > CAMERA_UNCOVERED_MAX_MS]
    unreported += [s for s in short if s not in unreported]
    # Shorter ones are graded, but their stalls can be under-read by up to what they left out: the
    # graders hold the fluid total bar against stalls + that (grade.camera_level).
    left_out = [max(0.0, window - s["span_ms"]) for s in played if s.get("span_ms") is not None and window]
    whole = len(played) == len(streams) and len(streams) >= (line.get("expected") or 0)
    path = line.get("path") or "hls"
    # Vitro (VitroClarity): the kind each camera showed at the window's end (web_rtc, hls or mjpeg; null: nothing). The
    # web harness's lines don't say: its streams are the view's path's by definition. A camera fell back when another
    # kind was what it showed while the view's path wasn't: the kind showing at the window's end, or, for a camera that
    # never played on its path, any other kind that showed a picture (an MJPEG fallback that stopped before the end).
    # Not the hidden player HA's side-by-side start runs beside it (HLS until WebRTC has video): once the path played,
    # that one was replaced, not fallen back to. One entry per camera (`fallbacks`, beside `transports`).
    shown_on = [s.get("transport") for s in streams] if any("transport" in s for s in streams) else None
    fallbacks = [camera_fallback(s, path) for s in streams] if shown_on is not None else None
    fell_back = sorted({k for k in fallbacks or [] if k})
    return {
        # The path the frontend played the graded streams on: web_rtc since protocol 6, hls before
        # (protocol 5's lines don't say; HLS was the only path then).
        "path": path,
        **({"transports": shown_on, "fallbacks": fallbacks} if shown_on is not None else {}),
        **({"fell_back": fell_back} if fell_back else {}),
        "first_frame_ms": max(s["first_frame_ms"] for s in played) if whole and played else None,
        "shown_pct": min(s["shown_pct"] for s in played) if whole and played else None,
        # A stream whose stalls or gaps are n/a (above) makes the run's n/a too, not 0, and its camera
        # level not measured (grade.camera_level).
        "max_gap_ms": max(s["max_gap_ms"] for s in played) if whole and played and not unreported else None,
        "stall_total_ms": max(s["stall_total_ms"] for s in played) if whole and played and not unreported else None,
        **({"stalls_reported": False} if whole and played and unreported else {}),
        # The most of the window a stream's receiver counts left out (0 when they cover it; none over HLS).
        **({"uncovered_ms": round(max(left_out), 1)} if whole and left_out else {}),
        "streams": len(streams), "expected": line.get("expected"), "window_ms": line.get("window_ms"),
        "detail": [{k: s.get(k) for k in ("w", "h", "first_frame_ms", "shown_pct", "max_gap_ms", "stall_total_ms", "stall_presented",
                                            "frames", "decoded", "dropped", "waiting", "source", "webrtc", "others",
                                            "gaps_source", "callback_gaps", "span_ms", "stalls_at",
                                            "transport", "errors", "freezes")}
                   for s in streams],
        # Detail, not graded: the streams' HLS fetches in the window (stats.js hlsFetches), and the
        # decoders the view got (reference_run adds `media`, from logcat).
        "fetches": line.get("fetches"),
    }


def camera_fallback(stream, path):
    """The kind a Vitro camera fell back to (camera_run's `fallbacks`), or None: the kind it showed at the window's end
    when that isn't its view's path; else, when it never played on its path, the other kind that showed a picture."""
    showing = stream.get("transport")
    if showing and showing != path:
        return showing
    if stream.get("first_frame_ms") is None:
        other = sorted(o["path"] for o in stream.get("others") or []
                       if o.get("path") and o["path"] != path and o.get("first_frame_ms") is not None)
        return " and ".join(other) or None
    return None


def camera_fallback_runs(c):
    """One run's HLS fallback views (#202, detail): per camera page, its HLS copy's camera_run, with
    the decoders it got and the view's error if it failed. Empty before protocol 6."""
    out = {}
    for view, e in (c.get("fallback") or {}).items():
        page = CAMERA_FALLBACK.get(view)
        if not page:
            continue
        r = camera_run(e.get("camera"), page) or {"first_frame_ms": None, "shown_pct": None, "max_gap_ms": None,
                                                 "stall_total_ms": None, "streams": 0, "path": "hls"}
        r.update(view=view, media=(c.get("media") or {}).get(view), error=e.get("error") or (
            c.get("fallback_error") if not e.get("done") else None))
        out[page] = r
    return out


def camera_fallback_summary(runs, tier):
    """A page's HLS fallback over the runs (#202): each run's worst stream, and the level the camera
    bars would give it. Detail: the grade uses the graded path only."""
    fb = [r["camera_fallback"][tier] for r in runs if tier in (r.get("camera_fallback") or {})]
    if not fb:
        return None
    return {"path": "hls", "runs": fb, "levels": [G.camera_level(x) for x in fb],
            "level": G.median_level(G.camera_level(x) for x in fb)}


def arrivals(step):
    """Protocol 4 detail: of the changes the fake sent in the window, how many reached the page's main
    thread before the window was logged, and how long they waited to (from sent to read). A long wait
    is HA's main thread busy; the change-to-screen latency includes it. None before protocol 4."""
    if step.get("from") != "sent":
        return None
    win = step.get("window_ms") or 0
    sent = [c for c in step.get("changes") or [] if 0 <= c[0] <= win]
    waits = sorted(c[1] - c[0] for c in sent if c[1] is not None)
    return {"sent": len(sent), "arrived": len(waits),
            "wait_p95_ms": round(P.nearest_rank(waits, 0.95), 1) if waits else None,
            "wait_max_ms": round(waits[-1], 1) if waits else None,
            # The page's clock tied to the fake's to within half this; None: not tied (the send times
            # are then when the harness asked for the stream, so too early: against the panel).
            "clock_rtt_ms": step.get("clock_rtt_ms")}


def state_ticks(step):
    """State updates (not clock ticks) due inside the window: [due, ran, d, kind], kind 0 a state."""
    win = step.get("window_ms") or 0
    return sum(1 for c in step.get("changes") or [] if (len(c) < 4 or c[3] == 0) and 0 <= c[0] <= win)


def live_check_failed(check, step=None):
    """(why, voids_taps): why this page's live-update frames can't be trusted, or (None, False).
    The check says whether the changing elements and the toggle were on screen (off screen, the
    updates paint nothing and the taps would land elsewhere), and the window's change log proves the
    updates ran: at least one less than the window holds at the page's update rate."""
    if "offscreen" not in check:
        return "no live check (a page from before it was added)", False
    if check["offscreen"]:
        return f"the toggle or an updating element is off screen ({check['offscreen']} of {check['moving']})", True
    if not check.get("targets"):
        return "nothing on the page to update (0 targets)", False
    every = check.get("update_every_ms")
    if step is not None and step.get("from") == "sent":
        # Protocol 4: every change the fake sent is in the log, from its schedule, whether or not the
        # page got to it; one it never got to counts as not shown. Nothing here is a reason to skip.
        return None, False
    if step is not None and every:
        expected = int((step.get("window_ms") or 0) // every)
        ran = state_ticks(step)
        if ran < expected - 1:
            return f"only {ran} of {expected} state updates ran in the window", False
    return None, False


def scroll_frames(swipes):
    """One run's scrolling: every swipe's frames pooled, the worse of SurfaceFlinger and rAF.
    SurfaceFlinger is used swipe by swipe: a swipe whose history didn't reach back before its window
    (evicted) gives rAF only, the others still give both. If no swipe has SurfaceFlinger's data, the
    run rests on rAF only (`raf_only`), which misses frames the compositor drops; the grade is then
    provisional."""
    moved_all = [sw for sw in swipes if sw.get("scrolled_px")]
    if not moved_all:
        return {"error": "the page didn't scroll" if swipes else "no swipes"}
    # A swipe that reached the end of the page stops early, so its window is mostly the gesture's
    # slow start (smoke6 and s1v4, protocol 1: light's 380 px of scrolling ended every swipe at about
    # 380 ms). It isn't the same flick as the others: left out, and counted.
    moved = [sw for sw in moved_all if not sw.get("hit_edge")]
    if not moved:
        return {"error": "every swipe reached the end of the page"}
    raf, sf, sf_swipes, period = [], [], 0, None
    for sw in moved:
        f = step_frames(sw, True)
        raf += sw.get("raf_intervals") or []
        sfd = sw.get("sf") or {}
        period = sfd.get("period_ms") or period
        if f["sf_valid"]:
            sf_swipes += 1
            sf += sf_intervals(sfd.get("presents_ms") or [], sfd["window_ms"], sfd["period_ms"])
    raf_st = P.frame_stats(raf, period)
    sf_st = P.frame_stats(sf, period) if sf_swipes else {"n": 0}

    def key(st):
        # Worse is a lower scroll level (rubric v5), then a longer p95, then fewer whole frames a
        # second (the page rounds its rAF intervals to 0.1 ms, which moves fps by a few hundredths).
        lv = G.scroll_level(st)
        return (G.SCROLL_ORDER.index(lv) if lv else -1, st.get("p95_ms") or 0, -round(st.get("fps") or 0))

    if not sf_swipes:
        chosen, source, limited = raf_st, "raf", None
    elif key(sf_st) > key(raf_st):
        chosen, source, limited = sf_st, "surfaceflinger", "compositor"
    elif key(raf_st) > key(sf_st):
        chosen, source, limited = raf_st, "raf", "main-thread"
    else:
        chosen, source, limited = sf_st, "surfaceflinger", "equal"
    firsts = [sw["first_scroll_ms"] for sw in moved if sw.get("first_scroll_ms") is not None]
    return {"frames": chosen, "source": source, "limited_by": limited, "sf_valid": sf_swipes == len(moved),
            "sf_swipes": sf_swipes, "raf_only": not sf_swipes, "swipes_at_edge": len(moved_all) - len(moved),
            # The live updates run while it scrolls, so the scroll level is scroll during updates.
            # None for pages from before the swipes counted them.
            "swipes_during_updates": (sum(1 for sw in moved if sw["changes_during"] > 0)
                                      if all("changes_during" in sw for sw in moved) else None),
            "swipes": len(swipes), "swipes_moved": len(moved), "scrolled_px": sum(sw["scrolled_px"] for sw in moved),
            "first_scroll_ms": statistics.median(firsts) if firsts else None}


# Pre-release protocol 8 was Clarity v1's release candidate (#205: s1rtc5 ran it on 2026-11-13): the
# same measurement as v1's protocol 1, before its lines carried `clarity`. Its lines read as v1.
RC_PROTOCOL = 8
# (protocol, clarity) of the pages that measured what Clarity v1's protocol 1 measures, from before the numbers
# started again at 1 (2026-11-19): the earlier v3's.
SAME_AS_V1 = (3, 3)


def page_version(page):
    """(protocol, clarity) of a view's page line: v1's say `clarity: 1`; v1's release candidate
    (RC_PROTOCOL, no marker, with its `usable.busy_ms`) reads as v1; any other line without the marker is pre-release
    (clarity None, its protocol as it said), whatever its number: pre-release protocols 1 to 8 reuse
    the numbers v1 starts again from."""
    if not page:
        return None, None
    if (page.get("protocol"), page.get("clarity")) == SAME_AS_V1:
        # The earlier Clarity v3's pages: protocol 3 measured exactly what protocol 1 measures now. When the
        # numbers started again (2026-11-19) only the rubric moved, so their runs are v1's (rubric.json _clarity).
        return 1, 1
    if page.get("clarity") is not None:
        return page.get("protocol"), page["clarity"]
    if page.get("protocol") == RC_PROTOCOL and (page.get("usable") or {}).get("busy_ms") is not None:
        # The one bypass of the marker, and only for the release candidate's own lines: they carry
        # `usable.busy_ms`, which the harness added with it (#205 round 10). An earlier line that said
        # protocol 8 (none ran outside #205's branch) stays pre-release. It is v1's (protocol 1).
        return 1, 1
    return page.get("protocol"), None


def recorded_clarity(ref):
    """The one Clarity version every graded page recorded, or None (pre-release, or mixed)."""
    cl = (ref or {}).get("clarity") or []
    return cl[0] if len(cl) == 1 else None


def is_current(ref, measured_on=None):
    """Every page of the graded runs ran this rubric's protocol, in this Clarity version (v1), and not the first
    v1's (2026-11-14), which said the same numbers: measured on or after G.CLARITY_SINCE."""
    return (bool(ref) and ref.get("protocols") == [G.PROTOCOL_VERSION] and ref.get("clarity") == [G.CLARITY]
            and not G.first_v1(measured_on))


def tier_run(tier, entry, presents_ms):
    """One reference dashboard in one run: open the view (load), live updates (frames), tap (INP),
    scroll (frames while swiping)."""
    page, step = entry.get("page"), entry.get("step")
    if page is None and entry.get("timed_out_s") and (entry.get("drawn") or {}).get("tier") == tier:
        # Clarity v2: drawn, but never free within the deadline. The open stands (rubric 2 times it to
        # the draw); blocking takes the deadline (timed_out) and usable isn't measured.
        page = entry["drawn"]
        entry = dict(entry, page=page)
    proto, clarity = page_version(page)
    out = {"tier": tier, "css": page and page.get("css"), "dpr": page and page.get("dpr"),
           "protocol": proto, "clarity": clarity}
    if entry.get("content_gone"):
        return lost(tier, out, entry["content_gone"])
    if entry.get("timed_out_s") and not entry.get("error"):
        return dict(timed_out(tier, tier_run(tier, dict(entry, timed_out_s=None), presents_ms) if page else out,
                              entry["timed_out_s"]), timed_out_after=entry.get("timed_out_after"))
    if entry.get("error") or not page:
        return dict(out, error=entry.get("error") or "incomplete")
    # A lost updates step (smoke7: cut off in logcat) leaves only the live updates unmeasured.
    no_step = not step
    step = step or {}
    if page.get("usable") is not None:
        # Rubric 2: open the view = navigation start to the first screen drawn, on screen; usable
        # (drawn and the main thread free, rubric 1's open) is detail, `usable_ms`.
        nav0 = P.page_to_mono_ms(page, page.get("nav_ms") or 0)
        when, why = usable_on_screen(page, presents_ms, free=False)
        if why:
            out["load_error"] = why
        else:
            out["load_ms"] = round(when - nav0)
            out["first_screen_cards"] = page["usable"].get("first_screen_cards")
            free_at, _ = usable_on_screen(page, presents_ms)
            free = page["usable"].get("interactive_ms") is not None
            out["usable_ms"] = round(free_at - nav0) if free and free_at is not None else None
        # Rubric 2: the main thread's blocking from the draw to usable (the page's frameBlocking);
        # none from a page before protocol 2.
        out["blocking_ms"] = page["usable"].get("blocking_ms")
        out["long_frames"] = page["usable"].get("long_frames")
        # Detail (#202): when the first screen was drawn, and how long the main thread's probes
        # waited each second until it was free (the usable time waits for that).
        u = page["usable"]
        if u.get("rendered_tick_ms") is not None:
            out["drawn_ms"] = round(u["rendered_tick_ms"] - (page.get("nav_ms") or 0))
        if u.get("busy_ms") is not None:
            out["busy_ms"] = u["busy_ms"]
    elif not page.get("load_ms") or not page.get("fcp_ms"):
        out["load_error"] = "no load event or first paint reported"
    else:
        nav = P.page_to_mono_ms(page, 0)
        loaded = P.page_to_mono_ms(page, page["load_ms"])
        fcp = P.page_to_mono_ms(page, page["fcp_ms"])
        live = entry.get("live_mono", 0) * 1000
        # Settling is looked for only from the frame that shows the first paint on: a pause before
        # it is mid-load, like a pause before the load event. (smoke5's first page: two frames of
        # the old page, a second of nothing, then its first paint on screen 350 ms after the page's
        # own first paint.)
        first = first_paint_on_screen([t for t in presents_ms if nav <= t], fcp, live)
        if first is None:
            out["load_error"] = "no frame on screen after its first paint"
        else:
            out["paint_to_screen_ms"] = round(first - fcp)
            settle, why = settle_before([t for t in presents_ms if first <= t <= live], loaded, live)
            if why:
                out["load_error"] = why
            else:
                out["load_ms"] = round(max(settle, fcp) - nav)
    # Live updates (rubric v5): graded on how fast each change reaches the screen (change_to_screen),
    # with frame pacing over the frames that had a change to draw (change_frames) for the premium
    # label and as detail. The page's frames over the whole pacing stretch are detail too.
    out["updates_all"] = (step.get("raf") or {"n": 0}) if step.get("raf", {}).get("n") else None
    period = (step.get("sf") or {}).get("period_ms")
    pacing, _ = change_frames(step, period)
    out["updates"] = pacing
    lat, why = change_to_screen(step, page, period)
    out["latency"] = lat
    out["arrivals"] = arrivals(step)
    out["updates_run"] = None if lat is None else {
        "latency_n": lat["n"], "latency_median_ms": lat["median_ms"], "latency_p95_ms": lat["p95_ms"],
        "frames": frame_facts(pacing) if pacing else None,
        **({"sent": out["arrivals"]["sent"], "arrived": out["arrivals"]["arrived"]} if out["arrivals"] else {})}
    if why:
        out["updates_error"] = why
    why, voids_taps = live_check_failed(entry.get("measure") or {}, step)
    out["state_ticks"] = state_ticks(step)
    if no_step:
        why = "the page's updates step never arrived"
    if why:
        out.update(updates=None, latency=None, updates_run=None, updates_error=why)
    out["camera_run"] = camera_run(entry.get("camera"), tier)
    # Protocol 3 (#211): HA's more-info dialogs, after the swipes.
    out["dialog_run"], why = dialog_run(entry.get("dialogs"), period)
    if why:
        out["dialog_error"] = why
    # #410 (detail, untimed): HA done with the view's dialogs after them, and how (perfrun.py `settle`).
    out["dialog_settle"] = entry.get("settle")
    inp = entry.get("input") or {}
    out.update(inp_ms=None if voids_taps or not inp.get("taps") else inp.get("inp_ms"), input_method=inp.get("method"),
               fcp_ms=page.get("fcp_ms"), nodes=page.get("nodes"), cards=page.get("cards"))
    sc = scroll_frames(entry.get("swipes") or [])
    if "error" in sc:
        out.update(scroll=None, scroll_error=sc["error"])
    else:
        out.update(scroll=sc["frames"], scroll_source=sc["source"], scroll_limited_by=sc["limited_by"],
                   sf_valid=sc["sf_valid"], sf_swipes=sc["sf_swipes"], raf_only=sc["raf_only"],
                   swipes_at_edge=sc["swipes_at_edge"], swipes_during_updates=sc["swipes_during_updates"],
                   scrolled_px=sc["scrolled_px"], swipes=sc["swipes"],
                   swipes_moved=sc["swipes_moved"], first_scroll_ms=sc["first_scroll_ms"])
    return out


def dialog_run(lines, period=None):
    """One view's more-info dialogs in one run (protocol 3, #211): (record, why). The record: how many
    dialogs ran and how many failed (never opened, or never settled), the slowest to settle (None when
    one failed: the graders read a failure as below fluid), the open animations' frames pooled (the
    premium level's 60 fps bar), and as detail the slowest response, close and longest frame, the
    refreshes missed, the mode HA showed and each dialog. (None, None) when the page logged none (a page
    before protocol 3); (None, why) when some of its lines were lost: not measured."""
    ds = sorted((d for d in lines or [] if d.get("t") == "dialog"), key=lambda d: d.get("n") or 0)
    if not ds:
        return None, None
    of = max(d.get("of") or 0 for d in ds)
    if len({d.get("n") for d in ds}) < of:
        return None, f"only {len({d.get('n') for d in ds})} of {of} dialog lines arrived"
    failed = [d for d in ds if d.get("settled_ms") is None]
    period = period or 1000 / 60
    pooled = [x for d in ds for x in d.get("anim_intervals") or []]
    frames = P.frame_stats(pooled, period) if pooled else {"n": 0}
    top = lambda k: max((d[k] for d in ds if d.get(k) is not None), default=None)  # noqa: E731
    return {"n": len(ds), "failed": len(failed), "settled_ms": None if failed else top("settled_ms"),
            "frames": frame_facts(frames), "open_ms": top("open_ms"), "close_ms": top("close_ms"),
            "longest_frame_ms": top("longest_frame_ms"), "dropped": sum(d.get("dropped") or 0 for d in ds),
            "modes": sorted({d["mode"] for d in ds if d.get("mode")}),
            "reduced_motion": any(d.get("reduced_motion") for d in ds),
            "errors": [f"{d.get('kind')}: {d['error']}" for d in failed if d.get("error")],
            "dialogs": [{k: d.get(k) for k in ("entity", "kind", "open_ms", "shown_ms", "graph_ms", "settled_ms", "close_ms",
                                               "dropped", "longest_frame_ms", "show_duration_ms", "error", "close_error")}
                        for d in ds]}, None


def timed_out(tier, out, deadline_s):
    """A view that didn't finish within the runner's deadline (#202): what it measured stands, and each
    task it didn't measure in this run is measured as failed, not missing: it didn't finish. Tap takes
    the deadline as its value (nothing answered within it), and so do blocking when the main thread
    was never free and open when the view was never drawn (rubric 2: a view drawn but never free
    keeps its open, from its `drawn` line); scroll, live updates and the cameras a `timed_out` record,
    which the graders read as below fluid."""
    out = {k: v for k, v in out.items() if k != "error"}
    for k in TASK_KEYS:
        out.setdefault(k, None)                  # a view that timed out before it was usable reported none
    out["timed_out_s"] = deadline_s
    out["timed_out_tasks"] = []
    return fail_unmeasured(tier, out, round(deadline_s * 1000), "timed_out", "timed out")


# What a view's run record holds for its tasks (tier_run), each None when it wasn't measured.
TASK_KEYS = ("inp_ms", "input_method", "fcp_ms", "nodes", "cards", "load_ms", "updates", "latency", "arrivals",
             "camera_run", "updates_run", "scroll", "blocking_ms", "dialog_run")


def fail_unmeasured(tier, out, ms, flag, source):
    """Each task `out` has no measurement for, as failing in this run (timed_out, lost): tap, and blocking and open
    when they are missing too, take `ms`; scroll, live updates, the cameras and the dialogs a `{flag: true}` record,
    which every grader reads as below fluid. Each one goes in out[flag + "_tasks"]."""
    tasks = out[flag + "_tasks"]
    if out.get("load_ms") is None:
        out.update(load_ms=ms, load_error=None)
        tasks.append("open")
    if out.get("inp_ms") is None:
        out["inp_ms"] = ms
        tasks.append("tap")
    if out.get("blocking_ms") is None:
        # Never free within the deadline: blocked for the whole of it (rubric 2).
        out["blocking_ms"] = ms
        tasks.append("blocking")
    if not out.get("scroll"):
        out.update(scroll={flag: True, "p95_ms": None, "jank_pct": None, "frozen_pct": None, "fps": None, "n": 0},
                   scroll_error=None, scroll_source=source)
        tasks.append("scroll")
    if not out.get("updates_run"):
        out.update(updates_run={flag: True}, updates_error=None)
        tasks.append("updates")
    # The more-info dialogs are part of v1 (#211): a view of another version (the earlier v2, the first v1's
    # pages, which read as v1 but are re-measure by date anyway) or a pre-release one that timed out didn't miss
    # them. By the marker, not the number: pre-release protocols reuse the numbers.
    known_older = out.get("clarity") not in (None, G.CLARITY) or \
        out.get("clarity") is None and out.get("protocol") is not None
    if not out.get("dialog_run") and not known_older:
        # Protocol 3: the view never got to its dialogs (they come last). A view that said nothing
        # (never usable) is taken to be this version's, as its other tasks are.
        out.update(dialog_run={flag: True}, dialog_error=None)
        tasks.append("dialog")
    if tier in G.CAMERA_PAGES and not out.get("camera_run"):
        out["camera_run"] = {flag: True, "first_frame_ms": None, "shown_pct": None, "max_gap_ms": None,
                             "stall_total_ms": None, "streams": 0, "detail": []}
        tasks.append("camera")
    return out


def lost(tier, out, gone):
    """A view whose browser page was closed in this run (perfrun.py content_gone: the app said its page's process was
    killed or crashed, and loaded the kiosk's page again): lost whole. Nothing it measured before stands, and every
    task fails in this run, measured, not missing: the panel couldn't hold the page, and that is the result. Tap, open
    and blocking take the runner's view deadline (VIEW_DEADLINE_S), as a view that never answered within it; scroll,
    live updates, the cameras and the dialogs a `{"lost": true}` record, which every grader reads as below fluid. The
    page can't reach a level in this run, and the grade isn't provisional for it. `lost` says why, in words."""
    out = dict(out)
    for k in TASK_KEYS:
        out[k] = None
    out["lost"] = P.content_gone_why(gone)
    out["lost_tasks"] = []
    return fail_unmeasured(tier, out, round(VIEW_DEADLINE_S * 1000), "lost", "page lost")


def reference_run(c):
    """One graded run: restart (protocol 4: process start to the reference dashboard the kiosk comes
    back to being usable; before it, to engine ready), then each reference dashboard."""
    base = {"run": c.get("run"), "ttid_ms": c.get("ttid_ms"), "launch": c.get("launch"), "own_page_state": c.get("own_page_state"),
            "own_page_loads": c.get("own_page_loads"), "engine_uri": c.get("engine_uri")}
    proc, engine = c.get("proc_mono"), c.get("engine_mono")
    start, start_error = None, None
    base["engine_ready_ms"] = round((engine - proc) * 1000) if proc is not None and engine is not None else None
    restart_page = c.get("restart_page")
    base["restart_to"] = "usable" if c.get("protocol_restart") or restart_page is not None else "engine-ready"
    if base["restart_to"] == "usable":
        # Protocol 4: restart = process start to the kiosk's own page (the reference dashboard it was
        # pointed at) being usable on screen. Engine ready stays as detail.
        if proc is None:
            start_error = "no process start in logcat"
        elif restart_page is None:
            start_error = c.get("restart_page_error") or "the kiosk didn't come back to a reference dashboard"
        elif restart_page.get("tier") != "start":
            # The restart ends on the start view, the one the kiosk was pointed at: any other view
            # (a stale setting, a page the kiosk restored) isn't the cold load this times.
            start_error = f"the kiosk came back to the {restart_page.get('tier')!r} view, not 'start'"
        else:
            when, why = usable_on_screen(restart_page, c.get("presents_mono_ms") or [])
            if why:
                start_error = why
            else:
                start = round(when - proc * 1000)
    elif proc is None or engine is None:
        start_error = "no process start or engine ready in logcat"
    elif c.get("engine_uri") not in (None, "about:blank"):
        # Engine ready is GeckoView's own first page; any other first page may already wait on the network.
        start_error = f"the engine's first page was {c['engine_uri']}, not about:blank"
    else:
        start = round((engine - proc) * 1000)
    tiers = {t: tier_run(t, e, c.get("presents_mono_ms") or []) for t, e in (c.get("tiers") or {}).items()}
    for t, r in tiers.items():
        if r.get("camera_run") is not None:
            r["camera_run"]["media"] = (c.get("media") or {}).get(t)
    base["camera_fallback"] = camera_fallback_runs(c)
    base["bad_page_lines"] = c.get("bad_page_lines") or 0
    base["lost_scroll_steps"] = sum((e.get("lost_scroll_steps") or 0) for e in (c.get("tiers") or {}).values())
    # Views that timed out (#202): what they measured stands, the rest fails (timed_out above); the
    # runner restarted the kiosk and opened the run's other views.
    base["timed_out"] = list(c.get("timed_out") or [])
    if c.get("content_gone"):
        # Each time the app said its browser page's process was gone (perfrun.py content_gone): the view it took is
        # lost (lost above); one between views took none. Only when there was one, so other records stay as they were.
        base["content_gone"] = [dict(x, why=P.content_gone_why(x)) for x in c["content_gone"]]
    # The CPU clock while each view was open (detail, #244), in kHz per core per sample.
    base["cpu_khz"] = [[v, t, by_cpu(ks)] for v, t, ks in c.get("cpu_khz") or []]
    base["cpu_max_khz"] = by_cpu(c.get("cpu_max_khz"))
    base["cpu_clock_s"] = c.get("cpu_clock_s")
    order = c.get("order") or list(c.get("tiers") or {})
    for t, r in tiers.items():
        r["position"] = order.index(t) + 1 if t in order else None
    warm = c.get("warmup")
    return dict(base, start_ms=start, start_error=start_error, tiers=tiers, order=order,
                warmup=None if warm is None else {"tier": warm.get("tier"), "finished": "done" in warm},
                between=c.get("between") or [], error=c.get("error"))


median_band = G.median_band


def frame_facts(st):
    """The frame stats a band reads (and their count); a timed-out view's run says so (#202)."""
    return dict({k: st.get(k) for k in ("p95_ms", "jank_pct", "frozen_pct", "fps", "n")},
                **({"timed_out": True} if st.get("timed_out") else {}), **({"lost": True} if st.get("lost") else {}))


def by_position(rs):
    """One tier's runs keyed by where it came in the order: {position: [results]}."""
    out = {}
    for x in rs:
        if x.get("position") is None:
            continue
        sc, up = x.get("scroll") or {}, x.get("updates") or {}
        out.setdefault(str(x["position"]), []).append({
            "scroll_fps": sc.get("fps"), "scroll_p95_ms": sc.get("p95_ms"), "scroll_level": G.scroll_level(sc) if sc else None,
            "latency_p95_ms": (x.get("latency") or {}).get("p95_ms"),
            "updates_level": G.update_level(x.get("updates_run")),
            "inp_ms": x.get("inp_ms"), "load_ms": x.get("load_ms"),
            **({"dialog_settled_ms": (x.get("dialog_run") or {}).get("settled_ms"),
                "dialog_level": G.dialog_level(x.get("dialog_run"))} if x.get("dialog_run") else {})})
    return out


def reference_summary(all_runs):
    # A run that ended in an error (another page loaded, a page didn't finish) isn't graded at all.
    runs = [r for r in all_runs if not r.get("error")]
    tiers = {}
    for t in G.TIERS:
        rs = [r["tiers"][t] for r in runs if t in r["tiers"] and not r["tiers"][t].get("error")]
        if not rs:
            continue
        sc = [x["scroll"] for x in rs if x.get("scroll")]
        up = [x["updates"] for x in rs if x.get("updates")]
        tiers[t] = {
            "inp_ms": P.spread([x["inp_ms"] for x in rs]),
            "input_method": sorted({x["input_method"] for x in rs if x["input_method"]}),
            "load_ms": P.spread([x.get("load_ms") for x in rs]),
            "load_errors": [x["load_error"] for x in rs if x.get("load_error")],
            # Rubric 2: the main thread's blocking from the draw to usable (graded), and usable itself
            # (rubric 1's open; detail).
            "blocking_ms": P.spread([x.get("blocking_ms") for x in rs]),
            "usable_ms": P.spread([x.get("usable_ms") for x in rs]),
            # Detail (#202): the first screen drawn, from the navigation; `load_ms` waits for the main
            # thread to be free as well.
            "drawn_ms": P.spread([x.get("drawn_ms") for x in rs]),
            # The CPU clock while this view was open (detail, #244): every core's samples, in MHz.
            "cpu_mhz": P.spread([k / 1000 for r in runs for v, _, ks in r.get("cpu_khz") or [] if v == t for k in ks.values()]),
            # Each CPU on its own, by its number (a big.LITTLE CPU's clusters run at different clocks).
            "cpu_mhz_by_core": {i: P.spread([ks[i] / 1000 for r in runs for v, _, ks in r.get("cpu_khz") or [] if v == t and i in ks])
                                for i in sorted({i for r in runs for v, _, ks in r.get("cpu_khz") or [] if v == t for i in ks}, key=int)},
            # Frames: each run's band, then the median band. Three runs each poor for a different
            # reason are poor, not the band of per-measure medians.
            "scroll": {k: P.spread([f.get(k) for f in sc]) for k in ("p95_ms", "jank_pct", "frozen_pct", "fps", "n")},
            "scroll_bands": [G.frame_band(f) for f in sc],
            # The grade's scroll: each run's level (rubric.json scroll_levels), then the median level.
            "scroll_levels": [G.scroll_level(f) for f in sc],
            "scroll_level": G.median_level(G.scroll_level(f) for f in sc),
            # Each run's frame stats: what a summary carries, so the site can band them itself.
            "scroll_runs": [frame_facts(f) for f in sc],
            "updates_runs": [x["updates_run"] for x in rs if x.get("updates_run")],
            "scroll_band": median_band(G.frame_band(f) for f in sc),
            "scroll_sources": sorted({x["scroll_source"] for x in rs if x.get("scroll")}),
            "scroll_limited_by": sorted({x["scroll_limited_by"] for x in rs if x.get("scroll_limited_by")}),
            "scroll_errors": [x["scroll_error"] for x in rs if x.get("scroll_error")],
            "scrolled_px": P.spread([x.get("scrolled_px") for x in rs]),
            "first_scroll_ms": P.spread([x.get("first_scroll_ms") for x in rs]),
            "sf_valid": [x.get("sf_valid") if x.get("scroll") else None for x in rs],
            "scroll_raf_only": [bool(x.get("raf_only")) for x in rs if x.get("scroll")],
            "sf_swipes": sum(x.get("sf_swipes") or 0 for x in rs if x.get("scroll")),
            "swipes_moved": sum(x.get("swipes_moved") or 0 for x in rs if x.get("scroll")),
            "swipes_at_edge": sum(x.get("swipes_at_edge") or 0 for x in rs if x.get("scroll")),
            "updates": {k: P.spread([f.get(k) for f in up]) for k in ("p95_ms", "jank_pct", "frozen_pct", "fps", "n")},
            "updates_bands": [G.frame_band(f) for f in up],
            "updates_band": median_band(G.frame_band(f) for f in up),
            "updates_errors": [x["updates_error"] for x in rs if x.get("updates_error")],
            "updates_all_p95_ms": P.spread([(x.get("updates_all") or {}).get("p95_ms") for x in rs]),
            # The grade's updates: change to screen, each run's level, then the median level.
            "latency_p95_ms": P.spread([(x.get("latency") or {}).get("p95_ms") for x in rs]),
            "latency_median_ms": P.spread([(x.get("latency") or {}).get("median_ms") for x in rs]),
            "camera_runs": [x["camera_run"] for x in rs if x.get("camera_run")],
            "camera_levels": [G.camera_level(x["camera_run"]) for x in rs if x.get("camera_run")],
            "camera_level": G.median_level(G.camera_level(x["camera_run"]) for x in rs if x.get("camera_run")),
            # Runs in which this view didn't finish within the runner's deadline, and the tasks that
            # failed for it (#202): measured, not missing.
            "timed_out_runs": sum(1 for x in rs if x.get("timed_out_s")),
            "timed_out_tasks": sorted({k for x in rs for k in x.get("timed_out_tasks") or []}),
            "camera_fallback": camera_fallback_summary(runs, t),
            # Protocol 3 (#211): HA's more-info dialogs, each run's slowest to settle, its level, then
            # the median level; the response, the close and the frames are detail.
            "dialog_runs": [x["dialog_run"] for x in rs if x.get("dialog_run")],
            "dialog_levels": [G.dialog_level(x["dialog_run"]) for x in rs if x.get("dialog_run")],
            "dialog_level": G.median_level(G.dialog_level(x["dialog_run"]) for x in rs if x.get("dialog_run")),
            "dialog_settled_ms": P.spread([(x.get("dialog_run") or {}).get("settled_ms") for x in rs]),
            "dialog_open_ms": P.spread([(x.get("dialog_run") or {}).get("open_ms") for x in rs]),
            "dialog_close_ms": P.spread([(x.get("dialog_run") or {}).get("close_ms") for x in rs]),
            "dialog_errors": [x["dialog_error"] for x in rs if x.get("dialog_error")]
                             + [e for x in rs for e in (x.get("dialog_run") or {}).get("errors") or []],
            # #410 (detail): runs in which HA still held one of this view's dialogs after its own wait,
            # so the page left it for the start view before the next view (none of it timed).
            "dialog_settles_away": [x["dialog_settle"] for x in rs if (x.get("dialog_settle") or {}).get("via")],
            "updates_levels": [G.update_level(x["updates_run"]) for x in rs if x.get("updates_run")],
            "updates_level": G.median_level(G.update_level(x["updates_run"]) for x in rs if x.get("updates_run")),
            "swipes_during_updates": [x.get("swipes_during_updates") for x in rs if x.get("scroll")],
            "paint_to_screen_ms": P.spread([x.get("paint_to_screen_ms") for x in rs]),
            "positions": [x.get("position") for x in rs],
            # The same page at each place in the order (the runs rotate it), so an order effect shows.
            "by_position": by_position(rs),
            "fcp_ms": P.spread([x["fcp_ms"] for x in rs]),
            "clock_rtt_ms": [(x.get("arrivals") or {}).get("clock_rtt_ms") for x in rs if x.get("arrivals")],
            "arrivals": [x.get("arrivals") for x in rs if x.get("arrivals")],
            "nodes": sorted({x["nodes"] for x in rs if x.get("nodes")}),
            "cards": sorted({x["cards"] for x in rs if x.get("cards")}),
            "viewport": sorted({(tuple(x["css"]) if x.get("css") else None, x.get("dpr")) for x in rs}, key=str),
        }
        k = sum(1 for x in rs if x.get("lost"))
        if k:
            # Runs in which this view's browser page was closed (lost): every task failed in them, measured, not
            # missing. Only when there was one, so other reports stay as they were, byte for byte.
            tiers[t].update(lost_runs=k, lost_why=sorted({x["lost"] for x in rs if x.get("lost")}))
    lost_views = [[i + 1, t, x["lost"]] for i, r in enumerate(all_runs) if not r.get("error")
                  for t, x in (r.get("tiers") or {}).items() if x.get("lost") and t in G.TIERS]
    return {
        # Graded views whose browser page was closed, as [run, view, why] (lost). Not a reason for "provisional": the
        # panel couldn't hold the page, and every task of it failed in that run. Only when there was one.
        **({"lost": lost_views} if lost_views else {}),
        "runs": len(all_runs),
        "graded_runs": len(runs),
        "start_ms": P.spread([r["start_ms"] for r in runs]),
        "start_errors": [r["start_error"] for r in runs if r.get("start_error")],
        "engine_uris": sorted({r["engine_uri"] for r in runs if r.get("engine_uri")}),
        # Android's TTID: the first frame of the launched activity, which on a kiosk is its own
        # loading screen. Reported, not graded.
        "android_ttid_ms": P.spread([r["ttid_ms"] for r in runs]),
        "engine_ready_ms": P.spread([r.get("engine_ready_ms") for r in runs]),
        # Protocol 4: the restart ends at a usable reference dashboard (the kiosk was pointed at it).
        "restart_to": "usable" if any(r.get("restart_to") == "usable" for r in all_runs) else "engine-ready",
        "launch": sorted({r["launch"] for r in runs if r.get("launch")}),
        "own_page_states": sorted({r["own_page_state"] for r in runs if r.get("own_page_state")}),
        "orders": [r.get("order") for r in runs],
        # The CPU's top clock (cpuinfo_max_freq, the highest core's), against which cpu_mhz reads.
        "cpu_max_mhz": max((k / 1000 for r in runs for k in (r.get("cpu_max_khz") or {}).values()), default=None),
        "cpu_clock_s": max((r["cpu_clock_s"] for r in runs if r.get("cpu_clock_s")), default=None),
        "bad_page_lines": sum(r.get("bad_page_lines") or 0 for r in all_runs),
        "lost_scroll_steps": sum(r.get("lost_scroll_steps") or 0 for r in all_runs),
        # Graded views that didn't finish within the runner's deadline, as [run, view] (#202). Not a
        # reason for "provisional": not finishing is the measurement, and its tasks fail.
        "timed_out": [[i + 1, t] for i, r in enumerate(all_runs) for t in r.get("timed_out") or [] if t in G.TIERS],
        # The protocol of every page that reported; a page that was lost says nothing about it.
        "protocols": sorted({x.get("protocol") for r in runs for x in r["tiers"].values() if said_version(x)}, key=str),
        # And their Clarity version: [1] for v1; None for a page from before v1 (pre-release).
        "clarity": sorted({x.get("clarity") for r in runs for x in r["tiers"].values() if said_version(x)}, key=str),
        "warmups": [r.get("warmup") for r in runs],
        "tiers": tiers,
        "errors": [r["error"] for r in all_runs if r.get("error")],
        "per_run": all_runs,
    }


def said_version(x):
    """A view's run record that says which protocol its page ran: not one that failed, nor one that timed out or was
    lost before its page said anything."""
    return not x.get("error") and not ((x.get("timed_out_s") or x.get("lost")) and x.get("protocol") is None)


def grade_inputs(graded):
    """The grade's inputs: from the reference dashboards only, so the grade can't depend on anyone's
    own Home Assistant."""
    ref = graded.get("reference") or {}
    med = lambda x: x and x["median"]  # noqa: E731
    tiers = {t: {"inp_ms": med(s["inp_ms"]), "load_ms": med(s["load_ms"]), "blocking_ms": med(s.get("blocking_ms")),
                 "scroll_level": s["scroll_level"], "updates_level": s["updates_level"],
                 "camera_level": s.get("camera_level"), "dialog_level": s.get("dialog_level")}
             for t, s in (ref.get("tiers") or {}).items()}
    return tiers, med(ref.get("start_ms"))


def viewport_check(ref, device):
    """The grade compares frames only at their default zoom. The kiosk's zoom (an HA entity, 0 to 3)
    sets Gecko's device pixel ratio, which changes how much of a dashboard fits on a screen. The
    default ("auto") is the display's density / 160. Returns (state, reason): ok, refused, unknown."""
    seen = {vp for s in ((ref or {}).get("tiers") or {}).values() for vp in s["viewport"]}
    try:
        expected = int(str(device.get("display", {}).get("density"))) / 160
    except ValueError:
        return "unknown", "the display's density wasn't recorded, so the default zoom can't be checked"
    dprs = {d for _, d in seen if d is not None}
    if not dprs:
        return "unknown", "the pages didn't report their pixel ratio"
    if any(abs(d - expected) > 0.01 for d in dprs):
        return "refused", (f"the kiosk's zoom isn't the frame's default: device pixel ratio {sorted(dprs)}, "
                           f"expected {expected:g} (density {device['display']['density']} / 160)")
    return "ok", None


LABEL = {"light": "light", "typical-nocam": "typical", "typical": "typical (with a camera)", "heavy": "large"}


def task_phrase(task, s_):
    """What one task measured, in words, for the headline."""
    med = lambda k: (s_ or {}).get(k) and s_[k]["median"]  # noqa: E731
    if task == "tap":
        return f"taps take {med('inp_ms'):.0f} ms to show" if med("inp_ms") is not None else "taps weren't measured"
    if task == "scroll":
        sc = (s_ or {}).get("scroll") or {}
        if not sc.get("fps") or sc["fps"]["median"] is None:
            return "scrolling wasn't measured"
        # One decimal: 49.9 fps is under the 50 fps bar and must not read as 50.
        return f"scrolling averages {sc['fps']['median']:.1f} fps (p95 {sc['p95_ms']['median']:.0f} ms)"
    if task == "updates":
        if med("latency_p95_ms") is None:
            return "live updates weren't measured"
        return f"a change takes {med('latency_p95_ms'):.0f} ms to show (p95)"
    if task == "camera":
        # Each run's worst stream (camera_run); the median of the runs, as the level is.
        runs = (s_ or {}).get("camera_runs") or []
        if not runs:
            return "the cameras weren't measured"
        gone = sum(1 for r in runs if r.get("lost"))
        ff = [r["first_frame_ms"] for r in runs if r.get("first_frame_ms") is not None]
        if not ff:
            if gone == len(runs):
                return f"the page was lost in {gone} of {len(runs)} run(s)"
            fb = fell_back_words(runs)
            return f"a camera fell back to {fb} on Vitro (it never played over {path_word(runs)})" if fb else "a camera never showed a frame"
        shown = statistics.median(r["shown_pct"] for r in runs if r.get("shown_pct") is not None)
        failed = len(runs) - len(ff) - gone
        na = sum(1 for r in runs if r.get("stalls_reported") is False)
        return (f"a camera takes {statistics.median(ff) / 1000:.1f} s to show its first frame and shows {shown:.0f}% of its frames"
                + (f" ({failed} of {len(runs)} run(s) had a stream that showed no frame)" if failed else "")
                + (f" (the page was lost in {gone} of {len(runs)} run(s))" if gone else "")
                + (f"; its stalls weren't measured in {na} of {len(runs)} run(s)" if na else ""))
    if task == "blocking":
        # Rubric 2: the wait after the draw, its own task; never the open's words (s1rtc10's headline
        # read "the view takes 1.2 s to open" for a page held back by 2.9 s of blocking).
        if med("blocking_ms") is None:
            return "the blocking after the view is drawn wasn't measured"
        return f"the main thread is blocked for {med('blocking_ms') / 1000:.1f} s after the view is drawn"
    if task == "dialog":
        # A view that timed out before its dialogs failed them, measured (timed_out), as a dialog that
        # never settled did: neither is "not measured" (#410's CodeRabbit).
        runs = (s_ or {}).get("dialog_runs") or []
        if not runs:
            return "the more-info dialog wasn't measured"
        late = sum(1 for r in runs if r.get("timed_out"))
        if late == len(runs):
            return f"the view didn't get to its more-info dialogs in {late} of {len(runs)} run(s)"
        gone = sum(1 for r in runs if r.get("lost"))
        failed = sum(1 for r in runs if r.get("failed") or r.get("timed_out"))
        if failed or gone:
            return ", and ".join(x for x in (
                failed and f"a more-info dialog didn't open fully in {failed} of {len(runs)} run(s)",
                gone and f"the page was lost in {gone} of {len(runs)} run(s)") if x)
        return f"a more-info dialog takes {statistics.median(r['settled_ms'] for r in runs) / 1000:.1f} s to open"
    if task != "load":
        raise ValueError(f"no words for task {task!r}")
    if med("load_ms") is not None:
        return f"the view takes {med('load_ms') / 1000:.1f} s to open"
    return "opening the view wasn't measured"


def lost_phrase(s_):
    """'the page was lost in 3 of 3 run(s) because the panel ran out of memory ...'."""
    return (f"the page was lost in {s_['lost_runs']} of {len(s_.get('positions') or [])} run(s) because "
            + " or ".join(s_.get("lost_why") or ["the panel closed it"]))


def held_back(g, ref):
    """What keeps the frame from the next letter up, in words: [(tier, [task phrases])]. A page whose lost runs decide
    it (notes.lost_decides) gets one phrase for them."""
    tiers = (ref or {}).get("tiers") or {}
    return [(x["page"], [lost_phrase(tiers[x["page"]])] if N.lost_decides(tiers.get(x["page"])) and x["tasks"] else
             [task_phrase(task, tiers.get(x["page"])) for task in x["tasks"]]) for x in G.next_limits(g)]


def headline(name, g, ref):
    """'Aqara S1 Plus: C, a good wall panel for typical dashboards. Held back by large dashboards:
    scrolling averages 41.5 fps (p95 50 ms).' The second sentence names the tasks that keep it
    from the next letter up, so it never reads against the letter."""
    if g["grade"] is None:
        return f"{name}: no {G.NAME} grade. {g['refused']}."
    hb = held_back(g, ref)
    out = f"{name}: {G.NAME} {g['grade']}, {g['meaning']}."
    if g["grade"] == G.LETTER_ORDER[0] and not g["missing"]:
        # Only when everything was measured: A+ needs only heavy, and it isn't absolute without the rest. On a frame
        # without touch, only of the tasks it was graded on: it says nothing about scrolling.
        out += f" Every {'graded ' if g.get('not_applicable') else ''}task holds 60 fps on large dashboards."
    lead = "Short of A+ on" if g["grade"] == G.LETTER_ORDER[1] else "Held back by"
    for t, phrases in hb:
        if phrases:
            # "typical dashboards", "typical dashboards with a camera" (rubric v10, #205).
            # And "without a camera" for typical-nocam: its numbers aren't typical-with-a-camera's (#255's review).
            label = {"typical": "typical dashboards with a camera",
                     "typical-nocam": "typical dashboards without a camera"}.get(t, f"{LABEL.get(t, t)} dashboards")
            out += f" {lead} {label}: " + "; ".join(phrases) + "."
    if g["capped_by_start"]:
        out += f" A slow restart ({g['start']['value'] / 1000:.1f} s) caps it at {G.START_CAP}."
    settled = "missing_settled_by" in g
    if g["provisional"]:
        why = list(g.get("provisional_reasons") or []) + (["not everything was measured"] if g["missing"] and not settled else [])
        out += " Provisional: " + "; ".join(why) + "."
    if settled:
        out += f" {settled_note(g)}."
    return out


# What a missing measurement is, in settled_note's words, and whether the words are plural (the site's are the same).
MISSING_WORDS = {"tap": ("taps", True), "scroll": ("scrolling", False), "updates": ("live updates", True),
                 "load": ("opening the view", False), "camera": ("cameras", True), "blocking": ("blocking", False),
                 "dialog": ("more-info dialogs", True)}


def _and(words):
    return words[0] if len(words) == 1 else ", ".join(words[:-1]) + " and " + words[-1]


def settled_note(g):
    """"Cameras on heavy weren't measured fully, but that can't change the letter: heavy already falls short on
    blocking": what is missing, said plainly when it can't change the letter (grade.py missing_settled_by), and why."""
    def word(task):
        return MISSING_WORDS.get(task, (task, False))
    named = [("the restart", False) if m == "start" else (f"{word(m.split('.')[1])[0]} on {m.split('.')[0]}",
                                                          word(m.split(".")[1])[1]) for m in g["missing"]]
    plural = len(named) > 1 or named[0][1]
    by = g["missing_settled_by"]
    if by["capped_by_start"]:
        why = f"a slow restart already caps it at {G.START_CAP}"
    elif by["limits"]:
        why = "; ".join(f"{x['page']} already falls short on {_and([word(k)[0] for k in x['tasks']])}" for x in by["limits"])
    else:
        # Nothing above the letter: the best one, which needs only its pages.
        needs = next((n for l, _, n in G.LETTERS if l == g["grade"]), None)
        why = f"{g['grade']} needs only {_and(list(needs))}" if needs else "the letter doesn't rest on it"
    what = _and([n for n, _ in named])
    return f"{what[0].upper()}{what[1:]} {'weren' if plural else 'wasn'}'t measured fully, but that can't change the letter: {why}"


# ---- real use (not graded)

def visual_settle(presents_ms, stop_ms, ended_quiet, gap_ms=SETTLE_GAP_MS):
    """When the dashboard stopped painting: the first frame at or after PageStop that is followed by
    gap_ms of no new frames. ended_quiet: sampling stopped because nothing painted for a while.
    A dashboard that never goes quiet (a constant animation) falls back to PageStop. Returns (ms, how)."""
    after = sorted(t for t in presents_ms if t >= stop_ms)
    if not after:
        return stop_ms, "page-stop"
    for a, b in zip(after, after[1:]):
        if b - a >= gap_ms:
            return a, "quiet-gap"
    if ended_quiet:
        return after[-1], "quiet-gap"
    return stop_ms, "no-quiet-gap"


def coldstart_run(c):
    base = {"ttid_ms": c.get("ttid_ms"), "launch": c.get("launch"), "am_total_ms": c.get("am_total_ms")}
    if c.get("error") or c.get("page_start_mono") is None:
        return dict(base, error=c.get("error") or "no dashboard PageStart")
    start, stop = c["page_start_mono"] * 1000, c["page_stop_mono"] * 1000
    settle, how = visual_settle(c.get("presents_mono_ms") or [], stop, c.get("ended_quiet", False))
    killed, proc, engine = c.get("stop_mono"), c.get("proc_mono"), c.get("engine_mono")
    return dict(base, page_stop_ms=round(stop - start), dashboard_load_ms=round(settle - start), settle=how,
                app_start_to_dashboard_ms=round(start - proc * 1000) if proc else None,
                engine_ready_ms=round((engine - proc) * 1000) if proc and engine else None,
                stop_to_dashboard_ms=round(settle - killed * 1000) if killed else None)


def app_memory(idle, package_roles=None):
    procs = idle["processes"]
    app = {n: p for n, p in procs.items() if p["role"] != "radio"}
    roles = {}
    for p in app.values():
        r = roles.setdefault(p["role"], {"pss_kb": 0, "rss_peak_kb": 0, "count": 0})
        r["pss_kb"] += p["pss_kb"]
        r["rss_peak_kb"] += p.get("rss_peak_kb") or 0
        r["count"] += 1
    main = next((p.get("summary") for p in app.values() if p["role"] == "main"), {}) or {}
    dm = idle.get("device_mem") or {}
    return {
        # No app process in the sample means nothing was measured, not zero memory.
        "app_pss_kb": sum(p["pss_kb"] for p in app.values()) if app else None,
        "by_role": roles,
        "main": main,
        "radio_pss_kb": sum(p["pss_kb"] for p in procs.values() if p["role"] == "radio"),
        "mem_total_kb": dm.get("mem_total_kb"),
        "mem_available_kb": dm.get("mem_available_kb"),
    }


def app_cpu(idle, package):
    cpu = idle.get("cpu") or {}
    procs = cpu.get("procs_pct_of_core") or {}
    roles = {n: p["role"] for n, p in idle["processes"].items()}
    app = sum(v for n, v in procs.items() if n.startswith(package))
    radio = sum(v for n, v in procs.items() if roles.get(n) == "radio")
    return {"app_pct_of_core": round(app, 2), "radio_pct_of_core": round(radio, 2), "device_busy_pct": cpu.get("device_busy_pct")}


def idle_frames(idle):
    fr = idle.get("frames") or {}
    pres = fr.get("presents_mono_ms") or []
    st = P.frame_stats(P.intervals(pres), fr.get("period_ms"))
    return {"presented_fps": round(len(pres) / idle["window_s"], 2), "p95_ms": st.get("p95_ms"), "max_ms": st.get("max_ms")}


def real_use_summary(cold, idles, package):
    # A sample the screen went off in isn't the panel at work: listed, and left out.
    off = sorted(f"{i['label']}-{i['run']}" for runs in idles.values() for i in runs if i.get("screen_off"))
    idles = {k: [i for i in v if not i.get("screen_off")] for k, v in idles.items()}
    on = idles.get("all-on", [])
    mems = [app_memory(i) for i in on]
    cpus = [app_cpu(i, package) for i in on]
    allmem = [app_memory(i) for runs in idles.values() for i in runs]
    roles = sorted({r for m in mems for r in m["by_role"]})
    return {
        "app_start_to_dashboard_ms": P.spread([c.get("app_start_to_dashboard_ms") for c in cold if not c.get("error")]),
        "android_ttid_ms": P.spread([c["ttid_ms"] for c in cold if not c.get("error")]),
        "dashboard_load_ms": P.spread([c.get("dashboard_load_ms") for c in cold]),
        "dashboard_page_stop_ms": P.spread([c.get("page_stop_ms") for c in cold]),
        "stop_to_dashboard_ms": P.spread([c.get("stop_to_dashboard_ms") for c in cold]),
        "settle_how": sorted({c["settle"] for c in cold if c.get("settle")}),
        "launch": sorted({c["launch"] for c in cold if c.get("launch")}),
        "errors": [c["error"] for c in cold if c.get("error")],
        "screen_off_samples": off,
        "idle": {
            "samples": len(on),
            "app_pss_kb": P.spread([m["app_pss_kb"] for m in mems]),
            "by_role_pss_kb": {r: P.spread([m["by_role"].get(r, {}).get("pss_kb") for m in mems]) for r in roles},
            "main_kb": {k: P.spread([m["main"].get(k) for m in mems]) for k in sorted({k for m in mems for k in m["main"]})},
            "radio_pss_kb": P.spread([m["radio_pss_kb"] for m in mems]),
            "device_used_pct": P.spread([100 - 100 * m["mem_available_kb"] / m["mem_total_kb"] for m in mems
                                         if m["mem_total_kb"] and m["mem_available_kb"] is not None]),
            "cpu_app_pct_of_core": P.spread([c["app_pct_of_core"] for c in cpus]),
            "cpu_radio_pct_of_core": P.spread([c["radio_pct_of_core"] for c in cpus]),
            "cpu_device_busy_pct": P.spread([c["device_busy_pct"] for c in cpus]),
            "frames": {k: P.spread([idle_frames(i)[k] for i in on]) for k in ("presented_fps", "p95_ms", "max_ms")},
        },
        "peak": {
            "app_pss_kb": max((m["app_pss_kb"] for m in allmem if m["app_pss_kb"] is not None), default=None),
            "app_rss_hwm_kb": max((sum(r["rss_peak_kb"] for r in m["by_role"].values()) for m in allmem), default=None),
            "mem_available_min_kb": min((m["mem_available_kb"] for m in allmem if m["mem_available_kb"] is not None), default=None),
        },
    }


def pane_diffs(idles, package):
    """Each `<pane>-off` label against the all-on samples. A delta inside the all-on range is noise.
    A sample the screen went off in is left out (real_use_summary lists it)."""
    idles = {k: [i for i in v if not i.get("screen_off")] for k, v in idles.items()}
    on = [i for i in idles.get("all-on", []) if app_memory(i)["app_pss_kb"] is not None]
    if not on:
        return []
    on_pss = [app_memory(i)["app_pss_kb"] for i in on]
    on_cpu = [app_cpu(i, package)["app_pct_of_core"] for i in on]
    out = []
    for label, runs in sorted(idles.items()):
        runs = [i for i in runs if app_memory(i)["app_pss_kb"] is not None]
        if not label.endswith("-off") or not runs:
            continue
        off_pss = [app_memory(i)["app_pss_kb"] for i in runs]
        off_cpu = [app_cpu(i, package)["app_pct_of_core"] for i in runs]
        d_pss = statistics.median(on_pss) - statistics.median(off_pss)
        d_cpu = statistics.median(on_cpu) - statistics.median(off_cpu)
        out.append({
            "id": label[:-4], "method": "off-diff", "samples_on": len(on), "samples_off": len(runs),
            "pss_kb": round(d_pss), "pss_noise_kb": max(on_pss) - min(on_pss),
            "pss_measurable": abs(d_pss) > max(on_pss) - min(on_pss),
            "cpu_pct_of_core": round(d_cpu, 2), "cpu_noise_pct": round(max(on_cpu) - min(on_cpu), 2),
            "cpu_measurable": abs(d_cpu) > max(on_cpu) - min(on_cpu),
        })
    return out


# ---- RAM on the reference dashboard (#68)

def _valid(samples):
    return [i for i in samples if app_memory(i)["app_pss_kb"] is not None]


def sample_matches(sample, label, panes):
    """Whether an idle sample ran what its label says, from the kiosk's log (panes_state): True,
    False, or None when the sample didn't record it (runs before 2026-11-13: unverified). The screen
    going off in the sample's window also makes it False: its CPU isn't the panel's at work."""
    if sample.get("screen_off"):
        return False
    st = sample.get("panes_state")
    if not st or st.get("voice") is None:
        return None
    on = label[len(REF_LABEL):-3] if label.endswith("-on") else None
    unrecorded = False
    for pid, p in panes.items():
        if not p.get("state"):
            continue
        ran_it = st.get(p["state"])
        if ran_it is None:
            # A run from before the pane's switch (#67): it counts, but isn't checked for this pane.
            unrecorded = True
        elif ran_it != (pid == on):
            return False
    return None if unrecorded else True


def _without_boot(m):
    return m["app_pss_kb"] - m["by_role"].get("boot", {}).get("pss_kb", 0)


def pane_on_alone(ref_idles, package, panes=None):
    """Each switchable pane on by itself (`ref-<pane>-on`) against every pane off (`ref-panes-off`),
    on the same reference dashboard: the difference of the medians. The tolerance is the larger of
    the two sets' noise bands (perflib.noise_band); a difference inside it is noise. Only samples that
    ran what their label says count (sample_matches); without that record a result is `verified:
    false`, and with none left it is an error, not a number (s1v7: the kiosk lost every switch).
    The direct-boot home activity's process (`:boot`, about 29 MB on the S1) isn't a pane and comes
    and goes between samples (#186), so the share leaves it out and gives how often it ran."""
    panes = {k: v for k, v in (panes or {}).items() if v.get("switch")}
    checked = {lab: [(i, sample_matches(i, lab, panes)) for i in _valid(runs)] for lab, runs in ref_idles.items()}
    off = [i for i, ok in checked.get("ref-panes-off", []) if ok is not False]
    off_bad = sum(1 for _, ok in checked.get("ref-panes-off", []) if ok is False)
    off_verified = all(ok for _, ok in checked.get("ref-panes-off", []) if ok is not False)
    if not off:
        if off_bad:
            return [{"id": pid, "method": "on-alone", "error": f"the kiosk didn't run every pane off in {off_bad} panes-off sample(s)"}
                    for pid in sorted(panes)]
        return []
    # Assist off by its microphone mute alone (the kiosk kept voice on): its running cost only.
    muted_only = any((i.get("panes_state") or {}).get("voice") for i in off)
    off_m = [app_memory(i) for i in off]
    off_pss = [_without_boot(m) for m in off_m]
    off_cpu = [app_cpu(i, package)["app_pct_of_core"] for i in off]
    out = []
    for label, pairs in sorted(checked.items()):
        if not (label.startswith(REF_LABEL) and label.endswith("-on")) or not pairs:
            continue
        runs = [i for i, ok in pairs if ok is not False]
        bad = sum(1 for _, ok in pairs if ok is False)
        if not runs:
            out.append({"id": label[len(REF_LABEL):-3], "method": "on-alone",
                        "error": f"the kiosk didn't run {label[len(REF_LABEL):-3]} on by itself in {bad} sample(s)"})
            continue
        on_m = [app_memory(i) for i in runs]
        on_pss = [_without_boot(m) for m in on_m]
        on_cpu = [app_cpu(i, package)["app_pct_of_core"] for i in runs]
        d = statistics.median(on_pss) - statistics.median(off_pss)
        # Under two samples on either side there is no spread to read: no tolerance, not measurable.
        bands = [P.noise_band(off_pss), P.noise_band(on_pss)]
        tol = None if None in bands else round(max(bands))
        roles = sorted({r for m in on_m + off_m for r in m["by_role"]} - {"boot"})
        boot = [m["by_role"]["boot"]["pss_kb"] for m in on_m + off_m if "boot" in m["by_role"]]
        d_cpu = statistics.median(on_cpu) - statistics.median(off_cpu)
        cpu_bands = [P.noise_band(off_cpu), P.noise_band(on_cpu)]
        cpu_tol = None if None in cpu_bands else max(cpu_bands)
        out.append({
            "id": label[len(REF_LABEL):-3], "method": "on-alone", "samples_on": len(runs), "samples_off": len(off),
            "pss_kb": round(d), "pss_tolerance_kb": tol, "pss_measurable": tol is not None and abs(d) > tol,
            "boot_samples_on": sum(1 for m in on_m if "boot" in m["by_role"]),
            "boot_samples_off": sum(1 for m in off_m if "boot" in m["by_role"]),
            "boot_pss_kb": round(statistics.median(boot)) if boot else None,
            "by_role_pss_kb": {r: round(statistics.median([m["by_role"].get(r, {}).get("pss_kb", 0) for m in on_m])
                                        - statistics.median([m["by_role"].get(r, {}).get("pss_kb", 0) for m in off_m])) for r in roles},
            "cpu_pct_of_core": round(d_cpu, 2), "cpu_tolerance_pct": None if cpu_tol is None else round(cpu_tol, 2),
            "cpu_measurable": cpu_tol is not None and abs(d_cpu) > cpu_tol,
            "verified": off_verified and all(ok for _, ok in pairs if ok is not False),
            "excluded": bad + off_bad,
            **({"off_by": "mute"} if muted_only and label == "ref-assist-on" else {}),
        })
    return out


def reference_ram(ref_idles, refstarts, package, budgets, mem_total_kb):
    """RAM on Clarity's reference dashboards, per process: at idle on the typical dashboard after each
    graded run (`ref-idle`), and the largest sample taken between the graded pages (the peak during
    the graded tasks). Then each switchable pane's share, and the frame's budgets. Not graded."""
    # A sample the screen went off in (perfrun.py idle: screen_off) isn't the panel at work: left out.
    idle = [i for i in _valid(ref_idles.get("ref-idle", [])) if not i.get("screen_off")]
    # Only graded runs that finished: a run that failed may have stopped mid-page (F6).
    between = [s for r in refstarts if not r.get("error") for s in r.get("memory") or []]
    between = [s for s in between if app_memory(s)["app_pss_kb"] is not None]
    if not idle and not between and not ref_idles:
        return None
    im = [app_memory(i) for i in idle]
    bm = [app_memory(s) for s in between]
    roles = sorted({r for m in im + bm for r in m["by_role"]})
    out = {
        "reference_idle": {
            "samples": len(im),
            "app_pss_kb": P.spread([m["app_pss_kb"] for m in im]),
            "by_role_pss_kb": {r: P.spread([m["by_role"].get(r, {}).get("pss_kb") for m in im]) for r in roles},
            "main_kb": {k: P.spread([m["main"].get(k) for m in im]) for k in sorted({k for m in im for k in m["main"]})},
            "device_used_pct": P.spread([100 - 100 * m["mem_available_kb"] / m["mem_total_kb"] for m in im
                                         if m["mem_total_kb"] and m["mem_available_kb"] is not None]),
            "cpu_app_pct_of_core": P.spread([app_cpu(i, package)["app_pct_of_core"] for i in idle if i.get("cpu")]),
        } if im else None,
        "graded_peak": {
            "samples": len(bm),
            "app_pss_kb": max(m["app_pss_kb"] for m in bm),
            "by_role_pss_kb": {r: max(m["by_role"].get(r, {}).get("pss_kb", 0) for m in bm) for r in roles},
            # Each process's peak RSS since it started (VmHWM), summed: includes a peak between two samples, as RSS (an upper bound on PSS).
            "app_rss_hwm_kb": max(sum(r["rss_peak_kb"] for r in m["by_role"].values()) for m in bm),
            "mem_available_min_kb": min((m["mem_available_kb"] for m in bm if m["mem_available_kb"] is not None), default=None),
        } if bm else None,
        "panes": pane_on_alone(ref_idles, package, budgets["panes"]),
        "screen_off_samples": sorted(f"{i['label']}-{i['run']}" for runs in ref_idles.values() for i in runs if i.get("screen_off")),
        # Kept in glassworks.perf/4, where fields are only added: no frame's pane waits on a switch since #67.
        "panes_waiting": [{"id": k, "waiting_on": v["waiting_on"], **({"note": v["note"]} if v.get("note") else {})}
                          for k, v in sorted(budgets["panes"].items()) if v.get("waiting_on")],
        "panes_not_switched": [{"id": k, "why": v["not_switched"], **({"note": v["note"]} if v.get("note") else {})}
                               for k, v in sorted(budgets["panes"].items()) if v.get("not_switched")],
    }
    peak = out["graded_peak"] or {}
    avail = [m["mem_available_kb"] for m in im + bm if m["mem_available_kb"] is not None]
    out["budget"] = G.memory_budget({"app_pss_peak_kb": peak.get("app_pss_kb"), "mem_total_kb": mem_total_kb,
                                     "mem_available_min_kb": min(avail) if avail else None}, budgets["ram"])
    out["pane_budget"] = G.pane_budgets(out["panes"], budgets["panes"])
    return out


def load_budgets(frame_id, path=None):
    """The frame's budgets (frames/<id>/budgets.json or its base frame's, or `path`) over the default (tools/perf/budgets.json)."""
    default = read_json(os.path.join(HERE, "budgets.json"))
    if path and not os.path.exists(path):
        raise SystemExit(f"--budgets {path}: no such file")       # F9: never fall back silently
    own = path or P.frame_budgets_path(os.path.join(HERE, "..", "..", "frames"), frame_id)
    return G.frame_budget(default, read_json(own) if own else None)


# ---- APK size

def apk_sizes(paths):
    """Per-ABI APK size and its parts (compressed bytes), for #68."""
    out = []
    for path in paths:
        with zipfile.ZipFile(path) as z:
            parts = {"dex": 0, "native": 0, "assets": 0, "resources": 0, "other": 0}
            abis = set()
            for i in z.infolist():
                n = i.filename
                if n.startswith("lib/"):
                    abis.add(n.split("/")[1])
                    parts["native"] += i.compress_size
                elif re.match(r"classes\d*\.dex$", n):
                    parts["dex"] += i.compress_size
                elif n.startswith("assets/"):
                    parts["assets"] += i.compress_size
                elif n.startswith("res/") or n == "resources.arsc":
                    parts["resources"] += i.compress_size
                else:
                    parts["other"] += i.compress_size
        out.append({"file": os.path.basename(path), "abis": sorted(abis) or ["universal"],
                    "bytes": os.path.getsize(path), "parts_bytes": parts})
    return out


# ---- assemble

# ---- assemble

# The page's clock is tied to the fake's by the shortest of 12 round trips; one longer than this
# leaves the send times uncertain by more than half of it (20 ms: a fifth of the 100 ms bar).
CLOCK_RTT_MS = 40
# Each change is timed from when the fake actually sent it (its lateness is in its context id), so a
# late send doesn't count against the panel. More than one update interval late and the stream's own
# rhythm (5 a second) wasn't what the protocol says.
STREAM_LATE_MS = 200


def fake_ha_log(path):
    """What fakeha.jsonl (run.sh --log) says about the fake Home Assistant: the commands the frontend
    sent, any it didn't know or that failed (the frontend then got an error HA wouldn't send), and how
    late the fake itself sent the stream's changes. None without the file (runs before 2026-11-13).

    Each change belongs to the view open when it was sent (the last `open` before it in the log): a
    graded view's lateness is `stream_late_max_ms`, which the validity check reads; an ungraded
    view's (the HLS copies, a warm-up) is `stream_late_detail_max_ms`, detail (#205, s1rtc8: one 2 s
    burst on typical-hls made the graded result provisional)."""
    if not os.path.exists(path):
        return None
    commands, unknown, failed, late, detail = 0, {}, {}, [], []
    view = None
    with open(path) as f:
        for line in f:
            try:
                e = json.loads(line)
            except ValueError:
                continue
            if "type" in e and "msg" in e:
                commands += 1
            if "unknown" in e:
                unknown[e["unknown"]] = unknown.get(e["unknown"], 0) + 1
            if "failed" in e:
                failed[e["failed"]] = failed.get(e["failed"], 0) + 1
            if "open" in e:
                cmd = e["open"] or {}
                view = "warmup" if str(cmd.get("warmup", "0")) not in ("0", "") else cmd.get("view")
            if "stream" in e:
                # Before any view opened (nothing should stream then), it counts as graded.
                (late if view is None or view in G.TIERS else detail).append(e.get("late_ms") or 0)
    return {"commands": commands, "unknown": unknown, "failed": failed, "stream_sent": len(late) + len(detail),
            "stream_late_max_ms": max(late) if late else None,
            "stream_late_detail_max_ms": max(detail) if detail else None}


def fake_ha_reasons(log, protocol4):
    """Provisional reasons from the fake's log. Protocol 4 needs it: without it nobody can tell."""
    if log is None:
        return ["the fake Home Assistant's log (fakeha.jsonl) is missing"] if protocol4 else []
    out = []
    if log["unknown"]:
        out.append("the fake Home Assistant didn't know command(s) the frontend sent: "
                   + ", ".join(f"{k} ({n})" for k, n in sorted(log["unknown"].items())))
    if log["failed"]:
        out.append("command(s) failed in the fake Home Assistant: " + ", ".join(f"{k} ({n})" for k, n in sorted(log["failed"].items())))
    if (log["stream_late_max_ms"] or 0) > STREAM_LATE_MS:
        out.append(f"the fake Home Assistant sent a live change {log['stream_late_max_ms']:.0f} ms late (more than {STREAM_LATE_MS} ms)")
    return out


def coverage_reasons(ref, attempted, touch=True, settled=(), unmeasured=None):
    """Why the grade is short of data: each band is over the runs attempted, not only those that
    finished. A run that failed, a page that didn't finish, a task not measured, or a scroll that rests
    on rAF only, each make the grade provisional. On a frame without touch (touch False) the scroll isn't
    graded (rubric.json no_touch), so nothing about it is a reason; every other task's still is.
    settled: the missing measurements that can't change the letter (grade.py missing_settled_by). Why a page's
    camera is one of them (its runs left open) isn't a reason: it goes to the list `unmeasured`, when given."""
    if not ref:
        return []
    out = []
    if ref["graded_runs"] < attempted:
        out.append(f"{attempted - ref['graded_runs']} of {attempted} graded runs failed or are missing")
    n_start = (ref.get("start_ms") or {}).get("n", 0) or 0
    if 0 < n_start < attempted:
        out.append(f"restart: measured in fewer runs than attempted ({n_start} of {attempted})")
    for t in G.TIERS:
        s_ = ref["tiers"].get(t)
        got = {"tap": s_ and (s_["inp_ms"] or {}).get("n", 0) or 0,
               **({"scroll": s_ and len(s_["scroll_bands"]) or 0} if touch else {}),
               "updates": s_ and len(s_["updates_runs"]) or 0,
               "load": s_ and (s_["load_ms"] or {}).get("n", 0) or 0,
               "blocking": s_ and (s_.get("blocking_ms") or {}).get("n", 0) or 0}
        if t in G.CAMERA_PAGES:
            got["camera"] = s_ and len(s_.get("camera_runs") or []) or 0
        if s_ and "dialog_runs" in s_:
            got["dialog"] = len(s_["dialog_runs"])
        short = [f"{task} {n} of {attempted}" for task, n in got.items() if 0 < n < attempted]
        if short:
            out.append(f"{t}: measured in fewer runs than attempted ({', '.join(short)})")
        # Camera runs whose level is left open, and which bar couldn't be settled (grade.camera_level_why):
        # stalls n/a; good's gap, which with what the counts left out could pass 500 ms; or fluid's
        # total, which could reach 1 s. A first frame or frames shown that already fail every level
        # decide it, and those aren't open.
        whys = [G.camera_level_why(c)[1] for c in (s_ or {}).get("camera_runs") or [] if not c.get("timed_out") and not c.get("lost")]
        open_ = [c for c in (s_ or {}).get("camera_runs") or []
                 if not c.get("timed_out") and not c.get("lost") and G.camera_level_why(c)[1] == G.OPEN_STALLS_NA]
        short = [c for c in open_ if (c.get("uncovered_ms") or 0) > CAMERA_UNCOVERED_MAX_MS]
        na = len(open_) - len(short)
        gap, total = whys.count(G.OPEN_GOOD_GAP), whys.count(G.OPEN_FLUID_TOTAL)
        # Every run open (the page's camera missing) and the letter settled without it: said, not a reason.
        cam = unmeasured if unmeasured is not None and f"{t}.camera" in settled else out
        if gap:
            cam.append(f"{t}: {gap} camera run(s) whose longest gap, with the part of the window their receiver counts "
                       f"left out, could pass good's {CAMERA_UNCOVERED_MAX_MS} ms: good couldn't be confirmed, not measured")
        if total:
            cam.append(f"{t}: {total} camera run(s) whose stalls, with the part of the window their receiver counts left "
                       f"out, could reach fluid's {FLUID_STALL_TOTAL_MS} ms in all: fluid couldn't be confirmed, not measured")
        if short:
            worst = max(c["uncovered_ms"] for c in short)
            cam.append(f"{t}: {len(short)} camera run(s) whose receiver counts left up to {worst / 1000:.1f} s of the "
                       "window out (getStats answered late): stalls n/a, not measured")
        if na:
            cam.append(f"{t}: {na} camera run(s) whose stalls couldn't be measured (no freeze counts from the receiver, "
                       "or no frames to measure gaps over): n/a, not measured")
        rtts = (s_ or {}).get("clock_rtt_ms") or []
        if any(r is None or r > CLOCK_RTT_MS for r in rtts):
            out.append(f"{t}: the live changes' send times rest on a clock tie looser than ±{CLOCK_RTT_MS // 2} ms in some run")
        during = [d for d in (s_ or {}).get("swipes_during_updates") or [] if d is not None]
        if touch and during and not any(during):
            out.append(f"{t}: no swipe ran while the live updates did, so scroll wasn't measured during updates")
        # Protocol 3: HA's sheet and graph don't animate under prefers-reduced-motion, so its dialogs
        # aren't timed as a panel at its defaults is (the S1's is off).
        k = sum(1 for d in (s_ or {}).get("dialog_runs") or [] if d.get("reduced_motion"))
        if k:
            out.append(f"{t}: the more-info dialogs ran with prefers-reduced-motion in {k} run(s): no open animation, "
                       "so they aren't timed as at the panel's defaults")
        k = sum((s_ or {}).get("scroll_raf_only") or [])
        if touch and k:
            out.append(f"{t}: scrolling rests on rAF only in {k} run(s); SurfaceFlinger's history didn't cover its swipes")
    return out


def grade_part(device, refs, attempted, fake_log, transport_of, net_of):
    """The graded part of a report from its graded runs (reference_run's records) and the run's device: the reference
    summary, the grade with its provisional reasons and headline, and the camera video's path and the test network
    (transport_of(ref), net_of(ref, transport): from the run's files, or from a stored report). build() and
    regrade_report() share it, so a report graded again from report.json is the one its raw files would give.
    -> (graded, grade, transport, net)."""
    measured_on = device.get("measured_on")
    # A frame without touch (device.json `touch`, from its frame data; rubric 2): its scroll isn't graded. A run from
    # before the field had touch.
    touch = device.get("touch") is not False
    graded = {"reference": reference_summary(refs) if refs else None}
    tiers, start = grade_inputs(graded)
    grade = G.grade(tiers, start, touch=touch)
    ref = graded["reference"]
    reasons = []
    if ref and "never-finished" in ref["own_page_states"]:
        # The 30 s fallback: Home Assistant was down or hung in at least one run, so the kiosk may still
        # have been retrying its page while the reference dashboards ran. The letter is kept (dropping a
        # measurement would let Home Assistant move it) and flagged. A page that finished but kept
        # painting (a clock card) is normal and changes nothing.
        reasons.append("the kiosk's own page never finished loading in a graded run (Home Assistant down?)")
    unmeasured = []
    settled = grade["missing"] if "missing_settled_by" in grade else ()
    reasons += coverage_reasons(ref, attempted, touch, settled, unmeasured)
    if device.get("engine") == "vitro" and ref:
        # A Vitro build whose cameras don't report to Clarity (before ClarityCameras) logs no camera line: it played a
        # camera over MJPEG only, and the reference views' cameras are WebRTC, so the task is missing, under the rules for
        # any missing measurement (docs/perf.md, "Vitro"). Said here, as a reason when it could change the letter, and as
        # unmeasured when it can't. A build whose cameras report is measured as the web engine is: nothing to say here.
        cams = [t for t in G.CAMERA_PAGES if f"{t}.camera" in grade["missing"]
                and not any("path" in r for r in (ref["tiers"].get(t) or {}).get("camera_runs") or [])]
        if cams:
            why = (f"{', '.join(cams)}: cameras not measured on Vitro (this build's cameras don't report to Clarity: it "
                   "plays them over MJPEG, and the reference views' cameras are WebRTC)")
            (unmeasured if all(f"{t}.camera" in settled for t in cams) else reasons).append(why)
    if unmeasured:
        # Why a measurement is missing that can't change the letter: kept, and said, but not a reason for provisional.
        grade["unmeasured"] = unmeasured
    # The fake's log exists since pre-release protocol 4, and in v1.
    reasons += fake_ha_reasons(fake_log, is_current(ref, measured_on) or any(isinstance(p, int) and p >= 4 for p in (ref or {}).get("protocols") or []))
    if ref and ref.get("bad_page_lines"):
        reasons.append(f"{ref['bad_page_lines']} page line(s) didn't parse or never arrived whole (cut off in logcat?)")
    if touch and ref and ref.get("lost_scroll_steps"):
        # A swipe whose step never came isn't in the pool, so the scroll level rests on fewer swipes.
        reasons.append(f"{ref['lost_scroll_steps']} swipe(s) answered with no scroll step back")
    # Only the graded runs' pages count; a run that failed has none (smoke7 printed "protocol " with
    # nothing after it). A page that didn't say reads as "unknown", as in grade.ts.
    if ref and ref["protocols"] and not is_current(ref, measured_on):
        if is_current(ref, G.CLARITY_SINCE):
            # v1's numbers, from before they started again (2026-11-19): the first v1's (2026-11-14).
            reasons.append(f"{G.FIRST_V1}: re-measure")
        elif None in (ref.get("clarity") or [None]):
            # Before v1 (2026-11-14): never graded as v1, whatever its protocol's number.
            reasons.append(f"{G.PRE_RELEASE} (protocol {', '.join(G.protocol_name(p) for p in ref['protocols'])}): re-measure")
        elif recorded_clarity(ref) not in (None, G.CLARITY):
            # Another Clarity version (v1 under rubric 2): its own rules only, as grade.py says.
            reasons.append(f"measured with Clarity v{recorded_clarity(ref)}; these rules are Clarity v{G.CLARITY}'s: re-measure")
        else:
            reasons.append(f"the pages ran protocol {', '.join(G.protocol_name(p) for p in ref['protocols'])}; "
                           f"this rubric is for protocol {G.PROTOCOL_VERSION}")
    transport = transport_of(ref)
    net = net_of(ref, transport)
    if net and net["limited"]:
        # The test rig's network, not the frame's (#649): a real Home Assistant sits on a wired or quiet link.
        reasons.append(net["limited"])
    vp_state, vp_reason = viewport_check(ref, device) if ref else ("unknown", None)
    grade["viewport"] = {"state": vp_state, "reason": vp_reason}
    if vp_state == "refused":
        # Refused, not provisional: at another zoom each screen holds a different amount of the same
        # dashboard, so a letter would look comparable when it isn't.
        grade.update(grade=None, meaning=None, refused=vp_reason)
    elif vp_state == "unknown" and ref:
        reasons.append(vp_reason)
    if not ref:
        # No reference dashboard was measured (--skip-reference, or no graded run left a file): there is
        # nothing to grade, so no letter, not an E.
        grade.update(grade=None, meaning=None, refused="the reference dashboards weren't measured")
    if reasons:
        grade["provisional"] = True
        grade["provisional_reasons"] = reasons
    name = device.get("frame_name") or device["frame"]
    grade["headline"] = headline(name, grade, graded["reference"])
    if grade["grade"] and ref and ref["protocols"] and not is_current(ref, measured_on):
        # The report still gives the letter (for the record); the site won't, for these pages.
        grade["headline"] += " Re-measure to publish: the site shows no letter for these pages."

    return graded, grade, transport, net


def build(run_dir, apks=(), budgets=None):
    device = read_json(os.path.join(run_dir, "device.json"))
    package = device["app"]["package"]
    files = lambda pat: sorted(glob.glob(os.path.join(run_dir, pat)))  # noqa: E731
    syn = [synthetic_run(read_jsonl(p)) for p in files("synthetic-*.jsonl")]
    raw_refs = [read_json(p) for p in files("refstart-*.json")]
    refs = [reference_run(c) for c in raw_refs]
    plan = read_json(os.path.join(run_dir, "plan.json")) if os.path.exists(os.path.join(run_dir, "plan.json")) else {}
    attempted = max(len(refs), plan.get("runs") or 0) if refs or plan.get("runs") else 0
    cold = [coldstart_run(read_json(p)) for p in files("coldstart-*.json")]
    idles, ref_idles = {}, {}
    for p in files("idle-*.json"):
        i = read_json(p)
        (ref_idles if i["label"].startswith(REF_LABEL) else idles).setdefault(i["label"], []).append(i)
    if not isinstance(budgets, dict):
        # A path (--budgets) or nothing: the frame's own file over the default.
        budgets = load_budgets(device["frame"], budgets)

    fake_log = fake_ha_log(os.path.join(run_dir, "fakeha.jsonl"))
    graded, grade, transport, net = grade_part(device, refs, attempted, fake_log,
                                               lambda ref: webrtc_transport(run_dir, ref),
                                               lambda ref, t: test_network(run_dir, ref, t))
    ref = graded["reference"]
    name = device.get("frame_name") or device["frame"]

    s = synthetic_summary(syn) if syn else None
    med = lambda x: x and x["median"]  # noqa: E731
    detail = {"synthetic": s and dict(s, js_band=G.band(med(s["js_burst_ms"]), G.JS_BURST), per_run=syn)}

    r = real_use_summary(cold, idles, package) if (cold or idles) else None
    real_use = None
    if r:
        memory = {"idle": r["idle"], "peak": r["peak"], "budget": G.memory_budget(
            {"app_pss_peak_kb": r["peak"]["app_pss_kb"], "mem_total_kb": device["mem_total_kb"],
             "mem_available_min_kb": r["peak"]["mem_available_min_kb"]}, budgets["ram"])}
        real_use = dict(r, per_run=cold, memory=memory, panes=pane_diffs(idles, package) + radio_pane(r))

    gv = next((geckoview_version(x["ua"]) for x in syn if x["ua"]), None)
    rep = {
        "schema": SCHEMA,
        "protocol_version": (ref["protocols"][0] if ref and len(ref["protocols"]) == 1 else None),
        # The Clarity version the pages ran (v1 from 2026-11-14), as they recorded it: a v1 run rebuilt
        # by v2's code stays 1 (then re-measured, never graded by v2's rules). None for a report from
        # before v1, or pages of mixed versions; the site shows those as "re-measure".
        "clarity": recorded_clarity(ref),
        "measured_on": device.get("measured_on"),
        "runs_attempted": attempted,
        "frame": {"id": device["frame"], "name": name,
                  "device": {k: v for k, v in device.items() if k not in ("frame", "frame_name", "app", "measured_on")},
                  "app": dict(device["app"], geckoview=gv)},
        "apk": apk_sizes(apks),
        "ram": reference_ram(ref_idles, raw_refs, package, budgets, device.get("mem_total_kb")),
        "fake_ha": fake_log,
        "webrtc_transport": transport,
        "test_network": net,
        "grade": grade,
        "graded": graded,
        "detail": detail,
        "real_use": real_use,
        "thermal": read_thermal(os.path.join(run_dir, "thermal.tsv")),
    }
    rep["notes"] = N.notes(rep)
    return rep


def censoring_band(runs):
    """How many stored views an unshown change could grade differently under this rule. The earlier one counted the
    same changes (from 250 ms before the window's end) at their own time, so one exactly 250 ms from the end was
    250.0 and passed fluid; this one makes it 250.1. The counts and the ranks are the same, so only a view whose
    stored p95 is 250.0 is in doubt, and only if it was stored before this rule (no unshown_floor_ms). report.json
    keeps latencies, not the changes: the raw files tell."""
    return sum(1 for r in runs for t in (r.get("tiers") or {}).values()
               if (t.get("latency") or {}).get("p95_ms") == NO_FRAME_MS and "unshown_floor_ms" not in (t.get("latency") or {}))


def regrade_report(rep):
    """A report.json graded again under this rubric from what it stored, for a run whose raw files are gone (the S1's
    published report): its graded runs (graded.reference.per_run, reference_run's records as measured) go through
    grade_part as build()'s do, their pages' versions read again (page_version: the earlier v3's pages are v1's).
    Everything measured stays as it was; the grade, the graded summary, the version and the notes are this
    rubric's. The camera video's path and the test network are the stored ones. One thing it can't redo: the
    censoring of changes no frame showed (NO_FRAME_MS), which report.json keeps only as latencies; a run where that
    could decide a level is flagged (censoring_band), and only the raw files settle it."""
    out = copy.deepcopy(rep)
    old = (out.get("graded") or {}).get("reference") or {}
    refs = old.get("per_run") or []
    for r in refs:
        for t in (r.get("tiers") or {}).values():
            t["protocol"], t["clarity"] = page_version({"protocol": t.get("protocol"), "clarity": t.get("clarity")})
    frame = out["frame"]
    device = dict(frame.get("device") or {}, frame=frame["id"], frame_name=frame.get("name"), app=frame.get("app") or {},
                  measured_on=out.get("measured_on"))
    graded, grade, _, _ = grade_part(device, refs, out.get("runs_attempted") or len(refs), out.get("fake_ha"),
                                     lambda ref: out.get("webrtc_transport"), lambda ref, t: out.get("test_network"))
    ref = graded["reference"]
    band = censoring_band(refs)
    if band:
        # Stored latencies can't be counted again (the changes aren't in report.json): a 250.0 ms p95 stored before
        # UNSHOWN_FLOOR_MS may be an unshown change, which now counts as 250.1 (CodeRabbit on #656).
        grade["provisional"] = True
        grade.setdefault("provisional_reasons", []).append(
            f"{band} stored run(s) with a live-update p95 of exactly {NO_FRAME_MS:.1f} ms, measured before unshown "
            "changes counted past it, may be a change no frame showed: grade it again from the raw files")
        grade["headline"] = headline(device.get("frame_name") or device["frame"], grade, ref)
    ref = graded["reference"]
    out.update(graded=graded, grade=grade,
               protocol_version=(ref["protocols"][0] if ref and len(ref["protocols"]) == 1 else None),
               clarity=recorded_clarity(ref))
    out["notes"] = N.notes(out)
    return out


def webrtc_transport(run_dir, ref):
    """How the graded cameras' WebRTC video reached the panel (protocol 7, #202): lanprobe.py's choice
    in transport.json (transport: lan-udp, lan-turn or adb; chosen_by: auto or flag; reason). Over adb,
    `limited` says heavy's camera result may be the test's USB link's, not the panel's (#202's smokes
    4 and 5). None before protocol 7, or without the reference part."""
    path = os.path.join(run_dir, "transport.json")
    if not ref or not os.path.exists(path):
        return None
    with open(path) as f:
        t = json.load(f)
    out = {k: t.get(k) for k in ("transport", "chosen_by", "reason")}
    heavy_cams = bool(((ref.get("tiers") or {}).get("heavy") or {}).get("camera_runs"))
    out["limited"] = ("heavy's camera video went through the test's adb link (USB), which on the S1 couldn't carry "
                      "four streams; heavy's camera result may be the link's, not the panel's"
                      if t.get("transport") == "adb" and heavy_cams else None)
    return out


def test_network(run_dir, ref, transport):
    """The test computer's own network (#649), from netcheck.py's netcheck.json: its pings to its gateway and to
    the panel, its link, and which graded traffic crossed it: the live updates when adb reaches the panel over the
    network (the fake's stream goes through `adb reverse`), the cameras when their video went over the LAN.
    `limited` (a reason for "provisional") when its own link wasn't quiet and carried some. None without the file
    (runs before #649) or without the reference part."""
    path = os.path.join(run_dir, "netcheck.json")
    if not ref or not os.path.exists(path):
        return None
    with open(path) as f:
        n = json.load(f)
    out = {k: n.get(k) for k in ("gateway", "panel", "link", "adb_over_network", "quiet", "tail_ms")}
    cams = any((t or {}).get("camera_runs") for t in (ref.get("tiers") or {}).values())
    out["carried"] = (["live updates"] if n.get("adb_over_network") else []) + (
        ["camera video"] if cams and (transport or {}).get("transport") in ("lan-udp", "lan-turn") else [])
    g = n.get("gateway") or {}
    out["limited"] = (f"the test computer's network: its own link to its gateway reached p95 {g.get('p95_ms')} ms, "
                      f"max {g.get('max_ms')} ms (over {n.get('tail_ms')} ms) and carried the {' and the '.join(out['carried'])}; "
                      "measure again with that computer on a wired or quiet link"
                      if n.get("quiet") is False and out["carried"] else None)
    return out


def network_line(rep):
    """report.md, beside the camera video's path: the test computer's own network (#649)."""
    n = rep.get("test_network")
    if not n:
        return []
    fmt = lambda s: f"p95 {s['p95_ms']} ms, max {s['max_ms']} ms" if s else "no answer"  # noqa: E731
    line = (f"The test computer's network ({n['link'] or 'link unknown'}): to its gateway {fmt(n['gateway'])}; "
            f"to the panel {fmt(n['panel'])}. It carried "
            + (f"the {' and the '.join(n['carried'])}." if n["carried"] else "no graded traffic (adb over USB, video over adb)."))
    if n.get("limited"):
        line += f" **Limited by the test:** {n['limited']}."
    return [line]


TRANSPORT_WORDS = {"lan-udp": "over the LAN (UDP), as in a real home", "lan-turn": "over the LAN (TCP, through the test's relay)",
                   "adb": "through the test's adb link (USB)"}


def transport_line(rep):
    """report.md, beside the camera results: the path the video took, why, and over adb the caveat."""
    t = rep.get("webrtc_transport")
    if not t:
        return []
    line = (f"Camera video {TRANSPORT_WORDS.get(t['transport'], t['transport'])}: {t['reason']}"
            + (" (chosen with --webrtc-transport)." if t.get("chosen_by") == "flag" and not t["reason"].startswith("set ") else "."))
    if t.get("limited"):
        line += f" **Possibly limited by the test:** {t['limited']}."
    return [line]


def read_thermal(path):
    """thermal.tsv (run.sh): each stage's thermal zones in °C, in the order they were logged."""
    if not os.path.exists(path):
        return []
    out = []
    with open(path) as f:
        for line in f:
            phase, _, zones = line.rstrip("\n").partition("\t")
            out.append({"phase": phase, "zones": {k: float(v) for k, _, v in (z.partition("=") for z in zones.split()) if v}})
    return out


def radio_pane(r):
    if not r or not r["idle"]["radio_pss_kb"]:
        return []
    return [{"id": "radio", "method": "process", "pss_kb": r["idle"]["radio_pss_kb"]["median"],
             "cpu_pct_of_core": (r["idle"]["cpu_radio_pct_of_core"] or {}).get("median"),
             "note": "the radio's own processes (outside the app); measured in place, not switched off"}]


def ordinal(k):
    return f"{k}{'st' if k % 10 == 1 and k != 11 else 'nd' if k % 10 == 2 and k != 12 else 'rd' if k % 10 == 3 and k != 13 else 'th'}"


def fmt_n(v, unit=""):
    """One number, or n/a."""
    return "n/a" if v is None else f"{v:.0f}{unit}"


def fmt(sp, unit="", scale=1, plus=False):
    if not sp:
        return "n/a"
    m, lo, hi = sp["median"] / scale, sp["min"] / scale, sp["max"] / scale
    d = 1 if max(abs(m), abs(hi)) < 100 and any(x != int(x) for x in (m, lo, hi)) else 0
    return f"{m:.{d}f}{'+' if plus else ''}{unit} ({lo:.{d}f} to {hi:.{d}f}, n={sp['n']})"


def device_line(dev, app):
    cpu = dev.get("cpu") or {}
    parts = [" ".join(x for x in (dev.get("manufacturer"), dev.get("model")) if x),
             dev.get("android") and f"Android {dev['android']} (API {dev.get('api')})", dev.get("abi"),
             cpu.get("cores") and f"{cpu['cores']} cores", dev.get("mem_total_kb") and f"{round(dev['mem_total_kb'] / 1024)} MB RAM",
             app.get("version_name") and f"app {app['version_name']}", app.get("geckoview") and f"GeckoView {app['geckoview']}",
             dev.get("engine") == "vitro" and "measured on Vitro (Settings → Labs)"]
    return ", ".join(p for p in parts if p) + "."


# The camera kinds in report.md's words (the page lines' path names, and Vitro's MJPEG).
KIND_WORDS = {"web_rtc": "WebRTC", "hls": "HLS", "mjpeg": "MJPEG"}


def fell_back_words(runs):
    """What the cameras of these runs fell back to on Vitro (camera_run `fell_back`), in words ("MJPEG"), or ""."""
    return " and ".join(KIND_WORDS.get(k, k) for k in sorted({k for r in runs for k in r.get("fell_back") or []}))


def path_word(runs):
    """The path the runs' cameras are graded on, in words."""
    paths = sorted({r.get("path") for r in runs if r.get("path")})
    return " and ".join(KIND_WORDS.get(p, p) for p in paths) or "its path"


def stored_fallbacks(r):
    """A Vitro camera run's `fallbacks`, one per camera. A run stored before the field (a report.json regrade.py reads
    again) gets them from its `detail` by the same rule (camera_fallback reads `transport`, `first_frame_ms` and `others`,
    which detail keeps), so it reads as it did."""
    if r.get("fallbacks") is not None:
        return r["fallbacks"]
    detail = r.get("detail") or []
    return [camera_fallback(detail[i], r.get("path")) if i < len(detail) else None for i in range(len(r["transports"]))]


def per_camera(counts, totals):
    """How many cameras of each run, in words: "3 of 4 cameras in each of 3 runs", or run by run when they differ
    ("3 of 4, 4 of 4 and 3 of 4 cameras in runs 1 to 3")."""
    n = len(counts)
    noun = "camera" if max(totals) == 1 else "cameras"
    if len(set(zip(counts, totals))) == 1:
        return f"{counts[0]} of {totals[0]} {noun} in {'each of ' if n > 1 else ''}{n} run{'s' if n > 1 else ''}"
    parts = [f"{c} of {t}" for c, t in zip(counts, totals)]
    return f"{', '.join(parts[:-1])} and {parts[-1]} {noun} in runs 1 to {n}"


def camera_transports(ref):
    """report.md (Vitro): what each camera page's cameras played over, camera by camera, and plainly when they fell back
    to another kind (an MJPEG fallback): a camera graded on WebRTC that showed something else instead fails the task, as
    on the web engine a camera that doesn't play over WebRTC does. Web runs don't say (their streams are the path's):
    nothing."""
    out = []
    for t, s_ in ref["tiers"].items():
        runs = [r for r in s_.get("camera_runs") or [] if r.get("transports") is not None]
        if not runs:
            continue
        path = path_word(runs)
        totals = [len(r["transports"]) for r in runs]
        fbs = [stored_fallbacks(r) for r in runs]
        on = [sum(1 for x in r["transports"] if x == r.get("path")) for r in runs]
        fell = [sum(1 for k in f if k) for f in fbs]
        blank = [sum(1 for x, k in zip(r["transports"], f) if x is None and not k) for r, f in zip(runs, fbs)]
        errors = sorted({e for r, f in zip(runs, fbs) for s, k in zip(r.get("detail") or [], f) if k
                         for e in s.get("errors") or []})
        line = f"{t} cameras on Vitro: over {path}, {per_camera(on, totals)}"
        if any(fell):
            kinds = " and ".join(KIND_WORDS.get(k, k) for k in sorted({k for f in fbs for k in f if k}))
            line += (f"; **fell back to {kinds}, {per_camera(fell, totals)}**, graded on {path} as on the web engine"
                     + (f" ({'; '.join(errors[:3])})" if errors else ""))
        if any(blank):
            line += f"; showing nothing at the window's end, {per_camera(blank, totals)}"
        out.append(line + ".")
    return out


def camera_detail(ref):
    """report.md: per camera page and run, the decoders it got and where its segments were late
    (detail, not graded)."""
    out = []
    for t, s_ in ref["tiers"].items():
        for k, c in enumerate(s_.get("camera_runs") or [], 1):
            m, f = c.get("media"), c.get("fetches")
            pres = sum(s.get("stall_presented") or 0 for s in c.get("detail") or [])
            if not m and not f and not pres:
                continue
            parts = []
            if m:
                names = sorted(set(m["codecs"]))
                parts.append(f"decoders {len(m['codecs'])} ({', '.join(names) or 'none logged'}), "
                             f"{m['flushes']} flushes, {m['eos']} drains")
            if pres:
                # rVFC's presentedFrames moved during the stalls: the callbacks stalled, the compositor didn't.
                parts.append(f"{pres} frames presented inside the stalls (the callbacks', not the stream's)")
            if f:
                sg = f.get("segment") or {}
                p95 = lambda x: "n/a" if not x else f"{x['p95']:.0f} ms"  # noqa: E731
                parts.append(f"{sg.get('n', 0)} segments, fetch p95 {p95(sg.get('total_ms'))} (queued p95 "
                             f"{p95(sg.get('queued_ms'))}), longest gap between a stream's segments "
                             + ("n/a" if sg.get("arrival_gap_max_ms") is None else f"{sg['arrival_gap_max_ms']:.0f} ms"))
            out.append(f"{t} cameras, run {k} (detail): " + "; ".join(parts) + ".")
    for t, s_ in ref["tiers"].items():
        for k, c in enumerate(s_.get("camera_runs") or [], 1):
            w = webrtc_detail(c)
            if w:
                out.append(f"{t} cameras over WebRTC, run {k} (detail): {w}.")
        fb = s_.get("camera_fallback")
        if fb:
            cells = []
            for k, (r, lv) in enumerate(zip(fb["runs"], fb["levels"]), 1):
                if r.get("first_frame_ms") is None:
                    cells.append(f"run {k}: no frame{' (' + r['error'] + ')' if r.get('error') else ''}")
                else:
                    cells.append(f"run {k}: first frame {fmt_n(r['first_frame_ms'], ' ms')}, {fmt_n(r['shown_pct'], ' %')} shown, "
                                 f"stalls {fmt_n(r['stall_total_ms'], ' ms')}, {lv}")
            out.append(f"{t} cameras over HLS, the fallback (detail, not graded; {fb['level'] or 'n/a'} by the camera bars): "
                       + "; ".join(cells) + ".")
    return out


def webrtc_detail(c):
    """A run's WebRTC streams in a few words (#202): the decoder the browser reports, frames dropped
    and frozen by its own counts, and the candidate pair. Empty for HLS."""
    ws = [s["webrtc"] for s in c.get("detail") or [] if (s.get("webrtc") or {}).get("inbound")]
    if not ws:
        return ""
    decs = sorted({w.get("decoder") or "not reported" for w in ws})
    hw = sorted({"hardware" if w.get("power_efficient") else "software" for w in ws if w.get("power_efficient") is not None})
    cand = sorted({f"{(w.get('candidate') or {}).get('local')}/{(w.get('candidate') or {}).get('relay')}" for w in ws
                   if w.get("candidate")})

    def total(k, fmt="{:.0f}"):
        """The streams' sum; n/a when no stream reported it, and marked when only some did."""
        got = [w[k] for w in ws if w.get(k) is not None]
        if not got:
            return "n/a"
        return fmt.format(sum(got)) + ("" if len(got) == len(ws) else f" (from {len(got)} of {len(ws)} streams)")
    # More than one session for a camera: HA's player made another (after ICE failed, #202's S1 heavy).
    again = [w for w in ws if (w.get("sessions") or 0) > 1 or any((cn.get("offers") or 0) > 1 for cn in w.get("connections") or [])]
    restarts = (f", {len(again)} of {len(ws)} streams needed another WebRTC session"
                f" (no video for {sum(w.get('no_video_ms') or 0 for w in again) / 1000:.1f} s in all)") if again else ""
    # The frame callbacks' gaps beside the graded ones (protocol 8): how busy the page was, not the video.
    cbs = [s["callback_gaps"] for s in c.get("detail") or [] if s.get("callback_gaps")]
    busy = (f"; the page's frame callbacks (detail, how busy the page was): {sum(x.get('callbacks') or 0 for x in cbs)} "
            f"for {total('frames_decoded')} frames, stalls by them up to {max((x.get('stall_total_ms') or 0) for x in cbs) / 1000:.1f} s"
            if cbs else "")
    # Receiver counts that leave more of the window out than a stall can hide in (#265): their freezes
    # are n/a there too, as the stalls are, and the counts say the time they cover (#255's review).
    # From the spans themselves, of the streams that played (as camera_run's uncovered_ms), so a run in
    # which a stream never played, with no uncovered_ms, still gets it (#255's review).
    spans = [s["span_ms"] for s in c.get("detail") or [] if (s.get("webrtc") or {}).get("inbound")
             and s.get("span_ms") is not None and s.get("first_frame_ms") is not None and s.get("shown_pct") is not None]
    window = c.get("window_ms")
    short = bool(spans and window and window - min(spans) > CAMERA_UNCOVERED_MAX_MS)
    freezes = ("freezes n/a" if short else f"{total('freezes')} freezes ({total('freeze_s', '{:.1f}')} s)")
    covered = (f" (the receiver's counts cover {min(spans) / 1000:.1f} s of the window's {window / 1000:.1f} s)"
               if short else "")
    return (f"decoder {', '.join(decs)}" + (f" ({', '.join(hw)})" if hw else "")
            + f", {total('frames_decoded')} frames decoded, {total('frames_dropped')} dropped, "
            f"{freezes}{covered}, candidate {', '.join(cand) or 'n/a'}{restarts}{busy}")


def by_cpu(khz):
    """A sample's kHz by CPU number ({"0": khz, ...}, the runner's); an early list counts from cpu0."""
    if isinstance(khz, dict):
        return {str(k): v for k, v in khz.items()}
    return {str(i): v for i, v in enumerate(khz or [])}


def cpu_clock_line(ref, clocks, top, every):
    """report.md (detail, #244): each view's clock, its cores pooled, or each core's when they differ
    (a big.LITTLE CPU's clusters)."""
    def one(t, c):
        cores = {i: x["median"] for i, x in (ref["tiers"][t].get("cpu_mhz_by_core") or {}).items() if x}
        if len(set(cores.values())) > 1:
            return f"{t} " + ", ".join(f"cpu{i} {m:.0f}" for i, m in cores.items()) + " MHz (medians)"
        return f"{t} {c['median']:.0f} MHz ({c['min']:.0f} to {c['max']:.0f})"
    when = f"every {every:g} s" if every else "while open"
    return (f"CPU clock while each view was open (detail, every core, {when}): " + "; ".join(one(t, c) for t, c in clocks)
            + (f"; the CPU's top clock is {top:.0f} MHz." if top else "."))


def camera_cell(s_, b):
    """The page's live cameras in report.md: the worst stream's first frame, frames shown and stalls
    (median of runs), and the level; `none` on a page without streams."""
    if b.get("camera") == G.NO_CAMERA:
        return "none"
    runs = s_.get("camera_runs") or []
    if not runs:
        return "not measured"
    med = lambda k: P.spread([r.get(k) for r in runs])  # noqa: E731
    ff = med("first_frame_ms")
    late = sum(1 for r in runs if r.get("timed_out"))
    gone = sum(1 for r in runs if r.get("lost"))
    late_note = ", ".join(x for x in (late and f"the view didn't finish in {late} of {len(runs)} run(s)",
                                      gone and f"the page was lost in {gone} of {len(runs)} run(s)") if x)
    if ff is None:
        return f"{late_note or 'a stream showed no frame'}, {b.get('camera') or 'n/a'}"
    # The numbers are the runs whose streams all played; say how many didn't (they make the level).
    failed = sum(1 for r in runs if r.get("first_frame_ms") is None and not r.get("timed_out") and not r.get("lost"))
    # The stalls median is over the runs that measured them: say how many didn't, and how many runs'
    # levels are left open (#265: s1rtc9's typical read "stalls 0 ms, n=2" with run 3 n/a).
    na = sum(1 for r in runs if r.get("stalls_reported") is False)
    open_ = sum(1 for r in runs if not r.get("timed_out") and not r.get("lost") and G.camera_level(r) is None)
    note = (f", {failed} of {len(runs)} run(s) had a stream that showed no frame" if failed else "") \
        + (f", {late_note}" if late_note else "") \
        + (f", stalls n/a in {na} of {len(runs)} run(s)" if 0 < na < len(runs) else "") \
        + (f", the level not measured in {open_} of {len(runs)} run(s)" if 0 < open_ < len(runs) else "")
    return (f"first frame {fmt(ff, ' ms')}, {fmt(med('shown_pct'), ' %')} shown, stalls {fmt(med('stall_total_ms'), ' ms')}, "
            f"{b.get('camera') or 'n/a'}{note}")


def dialog_cell(s_, b):
    """The page's more-info dialogs in report.md (protocol 3): the slowest to settle (median of runs),
    the level, and how many runs had one that didn't open fully."""
    runs = s_.get("dialog_runs")
    if runs is None:
        return "n/a (not measured by this version)"
    if not runs:
        return "not measured"
    failed = sum(1 for r in runs if r.get("failed"))
    late = sum(1 for r in runs if r.get("timed_out"))
    gone = sum(1 for r in runs if r.get("lost"))
    st = s_.get("dialog_settled_ms")
    head = (f"settled in {fmt(st, ' ms')}" if st and st.get("median") is not None
            else "the view didn't get to them" if late == len(runs)
            else "the page was lost" if gone == len(runs) else "never settled")
    return (f"{head}, {b.get('dialog') or 'n/a'}"
            + (f", {failed} of {len(runs)} run(s) had one that didn't open fully" if failed else "")
            + (f", the view didn't finish in {late} of {len(runs)} run(s)" if late and late < len(runs) else "")
            + (f", the page was lost in {gone} of {len(runs)} run(s)" if gone and gone < len(runs) else ""))


def dialog_detail(ref):
    """report.md (detail, protocol 3): per page, the dialogs' response, close and frames, how HA showed
    them, and why any didn't open fully."""
    out = []
    for t, s_ in ref["tiers"].items():
        runs = [r for r in s_.get("dialog_runs") or [] if not r.get("timed_out") and not r.get("lost")]
        if not runs:
            continue
        modes = sorted({m for r in runs for m in r.get("modes") or []})
        lf = [r["longest_frame_ms"] for r in runs if r.get("longest_frame_ms") is not None]
        out.append(f"{t}: more-info dialogs (detail): the first frame of the open {fmt(s_.get('dialog_open_ms'), ' ms')} "
                   f"after the tile's event, closed in {fmt(s_.get('dialog_close_ms'), ' ms')}; their open animations "
                   f"missed {sum(r.get('dropped') or 0 for r in runs)} refresh(es) in all"
                   + (f", the longest frame of an open {max(lf):.0f} ms" if lf else "")
                   + f"; shown as {', '.join(modes) or 'n/a'}.")
        errs = s_.get("dialog_errors") or []
        if errs:
            out.append(f"{t}: more-info dialogs that didn't open fully or weren't measured: {'; '.join(sorted(set(errs)))}.")
        away = s_.get("dialog_settles_away") or []
        if away:
            held = sorted({e for x in away for e in (x.get("first") or {}).get("pending") or []})
            back = sorted({x["at"] for x in away if x.get("at") and x.get("at") != x.get("via")})
            out.append(f"{t}: HA still held {', '.join(held) or 'a more-info dialog'} after the view's own wait in "
                       f"{len(away)} of {len(runs)} run(s), so the page moved to the start view, untimed, where "
                       + ("they came and were closed" if all(x.get("clean") for x in away) else "not all of them came")
                       + "; the next view opened from wherever HA was then"
                       + (f" ({', '.join(back)}: HA's late history.back() took it back)" if back else "")
                       + ". What the dialogs did within their wait is this view's result.")
    return out


def lost_words(ref):
    """'typical in 3 of 3 runs (runs 1, 2 and 3): the panel ran out of memory ...': each lost page, in how many of its
    runs, and why, lightest page first."""
    out = []
    for t in G.TIERS:
        ks = [k for k, v, _ in ref.get("lost") or [] if v == t]
        if not ks:
            continue
        s_ = (ref.get("tiers") or {}).get(t) or {}
        n = len(s_.get("positions") or []) or len(ks)
        whys = sorted({w for _, v, w in ref["lost"] if v == t})
        out.append(f"{t} in {len(ks)} of {n} run(s) (run{'s' if len(ks) > 1 else ''} {_and([str(k) for k in ks])}): "
                   + "; ".join(whys))
    return "; ".join(out)


def band_cell(b):
    return b or "n/a"


def notes_md(n):
    """The notes under the headline, which already says why the grade is provisional or refused."""
    return " ".join(x for x in [n["good"], n["limits"]] + n["causes"] if x)


# The live-update bars, by level (rubric.json update_levels): what report.md says a page is held to.
UPDATE_MS = {lv["level"]: lv["latency_p95_max_ms"] for lv in G.UPDATE_LEVELS}


# The tasks a frame can be exempt from (rubric.json no_touch), as report.md names them.
NA_WORDS = {"scroll": "Scrolling"}


def markdown(rep):
    g = rep["grade"]
    dev = rep["frame"]["device"]
    app = rep["frame"]["app"]
    na = g.get("not_applicable") or {}
    out = [f"# {G.NAME} report: {rep['frame']['name']}", "", device_line(dev, app), "",
           f"**{g['headline']}**", "", notes_md(rep.get("notes") or N.notes(rep)), "",
           # Rubric 2: a frame without touch isn't graded on scrolling, and says so plainly.
           *[x for task, why in na.items() for x in (f"{NA_WORDS.get(task, task)}: not applicable, {why}.", "")],
           # A measurement missing that can't change the letter: why it is missing (the headline says it can't).
           *(["Not measured, and it can't change the letter (not a reason for provisional):", "",
              *[f"- {x}" for x in g["unmeasured"]], ""] if g.get("unmeasured") else []),
           "The grade comes only from the reference dashboards this test ships (light, typical without and with a "
           "camera, heavy), "
           "so it doesn't depend on your Home Assistant. Background load (panes, the radio, heat) still reaches it.", ""]
    ref = rep["graded"]["reference"]
    if ref:
        out += ["Each page's tasks (median of runs; scroll and live-update levels are the median of each run's level). "
                "A page is good when scrolling averages at least 50 fps (p95 at most 50 ms), 95% of live changes "
                f"show within {UPDATE_MS['good']:.0f} ms, taps within 200 ms, the view is drawn within 4 s and its main thread blocks for at "
                "most 600 ms after that, and HA's more-info dialogs (a light's, a thermostat's and a sensor's with its history "
                "graph) settle within 1 s (the slowest of them, from the tile's event); fluid at 30 fps (scroll p95 at "
                f"most 67 ms), changes within {UPDATE_MS['fluid']:.0f} ms and dialogs within 2 s; premium when scrolling, the live changes "
                "and the dialogs' open animation also hold 60 fps (A+ on heavy). Live-update pacing is detail, not graded.", "",
                "| Page | Level | Tap (INP) | Scroll | Live updates | Open the view (drawn) | Blocking after it | Cameras | More-info dialog | Holds it back |",
                "|---|---|---|---|---|---|---|---|---|---|"]
        for t in G.TIERS:
            s_, b = ref["tiers"].get(t), g["bands"].get(t) or {}
            if not s_:
                out.append(f"| {t} | not measured | | | | | | | | |")
                continue
            lvl = g["levels"].get(t)
            up = {None: "fluid", "fluid": "good", "good": "premium"}
            target = up.get(lvl)
            held = ", ".join(G.limits(b, target)) if target else ""
            # Lost in most runs (notes.lost_decides): its medians are the deadline that stands for those runs, not the
            # panel's numbers, so the cells say the page was lost instead.
            lost_cell = (f"page lost in {s_['lost_runs']} of {len(s_.get('positions') or [])} run(s)"
                         if N.lost_decides(s_) else None)
            val = lambda sp: lost_cell or fmt(sp, " ms")  # noqa: E731
            cards = f" ({', '.join(map(str, s_['cards']))} cards)" if s_["cards"] else ""
            scroll = (f"{G.NOT_APPLICABLE} (no touch)" if "scroll" in na else f"{lost_cell}, {b['scroll'] or 'n/a'}" if lost_cell
                      else f"{fmt(s_['scroll']['fps'], ' fps')}, p95 {fmt(s_['scroll']['p95_ms'], ' ms')}, {b['scroll'] or 'n/a'}")
            updates = (f"{lost_cell}, {b['updates'] or 'n/a'}" if lost_cell else
                       f"{fmt(s_['latency_p95_ms'], ' ms')} to show (p95), {b['updates'] or 'n/a'}; pacing p95 {fmt(s_['updates']['p95_ms'], ' ms')}")
            usable = "" if lost_cell else f" (usable at {fmt(s_.get('usable_ms'), ' ms')})"
            out.append(
                f"| {t}{cards} | {lvl or 'neither'} | {val(s_['inp_ms'])}, {band_cell(b['tap'])} | {scroll} | {updates} "
                f"| {val(s_['load_ms'])}, {band_cell(b['load'])} | {val(s_.get('blocking_ms'))}, {band_cell(b.get('blocking'))}{usable} "
                f"| {camera_cell(s_, b)} | {dialog_cell(s_, b)} | {held} |")
        vp = g.get("viewport") or {}
        seen = sorted({str(v) for s_ in ref["tiers"].values() for v in s_["viewport"]})
        to = "a usable reference dashboard" if ref.get("restart_to") == "usable" else "engine ready"
        out += ["", f"Restart, app process to {to}: {fmt(ref['start_ms'], ' ms')} ({band_cell(g['start']['band'])}; "
                    f"{', '.join(ref['launch']) or 'n/a'}; first page {', '.join(ref['engine_uris']) or 'n/a'}). "
                    + (f"Engine ready {fmt(ref.get('engine_ready_ms'), ' ms')}, not graded. " if to != "engine ready" else "")
                    + f"Android's TTID {fmt(ref['android_ttid_ms'], ' ms')}: the first frame of the kiosk's loading screen; not graded.",
                f"Viewport (CSS px, device pixel ratio): {', '.join(seen) or 'n/a'}; zoom check: {vp.get('state', 'n/a')}"
                + (f" ({vp['reason']})" if vp.get("reason") else "") + "."]
        if ref["start_errors"]:
            out.append(f"Restart not measured in {len(ref['start_errors'])} run(s) ({'; '.join(sorted(set(ref['start_errors'])))}).")
        clocks = [(t, s_["cpu_mhz"]) for t, s_ in ref["tiers"].items() if (s_.get("cpu_mhz") or {}).get("median") is not None]
        if clocks:
            top = ref.get("cpu_max_mhz")
            every = ref.get("cpu_clock_s")
            out.append(cpu_clock_line(ref, clocks, top, every))
        slow_free = [(t, s_) for t, s_ in ref["tiers"].items()
                     if (s_.get("drawn_ms") or {}).get("median") is not None and (s_.get("load_ms") or {}).get("median") is not None
                     and s_["load_ms"]["median"] - s_["drawn_ms"]["median"] > 5000]
        if slow_free:
            out.append("Drawn long before usable (detail): " + "; ".join(
                f"{t}'s first screen was drawn {s_['drawn_ms']['median'] / 1000:.1f} s after the navigation, and its main "
                f"thread was free (usable) after {s_['load_ms']['median'] / 1000:.1f} s" for t, s_ in slow_free)
                + ". Each run's `busy_ms` in report.json says how long the probes waited each second.")
        if ref.get("timed_out"):
            out.append(f"Views that timed out (not usable within {int(VIEW_DEADLINE_S)} s, or stuck in a task, saying nothing "
                       f"for {int(STAGE_IDLE_S)} s), measured as failing what they didn't get to (not \"not measured\"): "
                       + "; ".join(f"{t} in run {k} ({', '.join((ref['tiers'].get(t) or {}).get('timed_out_tasks') or []) or 'nothing left'})"
                                   for k, t in ref["timed_out"]) + ".")
        if ref.get("lost"):
            out.append("Pages lost when the panel closed its browser page, measured as failing every task in that run "
                       "(not \"not measured\"; the run's other pages count as usual): " + lost_words(ref) + ".")
        detail_late = (rep.get("fake_ha") or {}).get("stream_late_detail_max_ms")
        if (detail_late or 0) > STREAM_LATE_MS:
            # Not a reason for "provisional": no graded view's stream was late (#205).
            out.append(f"The fake Home Assistant sent a live change {detail_late:.0f} ms late during a view that isn't "
                       "graded (an HLS copy or a warm-up; detail: this computer was busy then).")
        out += transport_line(rep)
        out += camera_transports(ref)
        out += network_line(rep)
        out += camera_detail(ref)
        out += dialog_detail(ref)
        for t, s_ in ref["tiers"].items():
            for what in [w for w in ("scroll", "updates", "load") if w not in na]:
                errs = s_[f"{what}_errors"]
                if errs:
                    out.append(f"{t}: {what} not measured in {len(errs)} run(s) ({'; '.join(sorted(set(errs)))}).")
        if ref["errors"]:
            out.append(f"Failed runs, not graded: {len(ref['errors'])} ({'; '.join(sorted(set(ref['errors'])))}).")
        if "never-finished" in ref["own_page_states"]:
            out.append("Some runs opened the reference dashboards before the kiosk's own page finished loading.")
        for t, s_ in ref["tiers"].items():
            if "scroll" in na:
                break                        # rubric 2: no scroll graded, so no word on its swipes (CodeRabbit on #776)
            k = sum(s_["scroll_raf_only"])
            if k:
                out.append(f"{t}: scrolling from rAF only in {k} run(s): SurfaceFlinger's history didn't cover any of "
                           "those swipes, so frames the compositor dropped can't be seen (provisional).")
            if s_["swipes_at_edge"]:
                out.append(f"{t}: {s_['swipes_at_edge']} swipe(s) reached the end of the page and were left out.")
            if not k and s_["sf_swipes"] < s_["swipes_moved"]:
                out.append(f"{t}: SurfaceFlinger covered {s_['sf_swipes']} of {s_['swipes_moved']} swipes; "
                           "the others count with rAF only.")
        orders = [o for o in ref.get("orders") or [] if o]
        if orders:
            warm = {w["tier"] for w in ref.get("warmups") or [] if w}
            out += ["", f"Order of the pages ({'a warm-up ' + ', '.join(sorted(warm)) + ' page first, not graded; ' if warm else ''}"
                        f"it turns each run): " + "; ".join(", ".join(o) for o in orders) + ".", ""]
            npos = max(len(o) for o in orders)   # one column per place in the order, however many runs
            out += ["| Page | " + " | ".join(ordinal(k) for k in range(1, npos + 1)) + " |",
                    "|---|" + "---|" * npos]
            for t in G.TIERS:
                bp = (ref["tiers"].get(t) or {}).get("by_position") or {}
                cells = []
                for pos in map(str, range(1, npos + 1)):
                    cells.append("; ".join(
                        ("scroll n/a, " if "scroll" in na else f"scroll {fmt_n(x['scroll_fps'], ' fps')} ({x['scroll_level'] or 'n/a'}), ")
                        + "updates p95 "
                        f"{fmt_n(x['latency_p95_ms'], ' ms')} ({x['updates_level'] or 'n/a'}), opens in {fmt_n(x['load_ms'], ' ms')}"
                        for x in bp.get(pos, [])) or "")
                out.append(f"| {t} | " + " | ".join(cells) + " |")
    out += ram_markdown(rep.get("ram"))
    s = rep["detail"]["synthetic"]
    if s:
        out += ["", "## Detail: animations it can handle (test page, not graded)", "",
                "Largest element count before the first step that missed the target (median of runs). "
                "A + means it held at the ladder's last step.", "",
                "| Family | 60 fps | 30 fps |", "|---|---|---|"]
        for fam, v in s["ladder"].items():
            out.append(f"| {fam} | {fmt(v['held_60fps'], plus=v['held_60fps_at_top'])} | {fmt(v['held_30fps'], plus=v['held_30fps_at_top'])} |")
        out += ["", f"Scroll p95 {fmt(s['scroll']['p95_ms'], ' ms')}" + (" (the list did not move: not a measurement)" if s["scroll_moved"] is False else "")
                + f". JS state burst {fmt(s['js_burst_ms'], ' ms')} (band {s['js_band'] or 'n/a'}, checksum {'ok' if s['js_checksum_ok'] else 'MISMATCH'}). "
                f"Test page {'/'.join(x.upper() for x in s['page_lcp_from']) or 'LCP'} {fmt(s['page_lcp_ms'], ' ms')}."]
        if s["steps_without_sf"]:
            out.append(f"{s['steps_without_sf']} step(s) had no complete SurfaceFlinger history and use rAF only.")
        if s.get("screen_off_runs"):
            out.append(f"The screen went off in {s['screen_off_runs']} run(s); they are left out of these numbers.")
    r = rep["real_use"]
    if r and (r["idle"]["samples"] or r["per_run"]):
        i = r["idle"]
        out += ["", "## Real use on this dashboard (not graded)", "",
                "Your own dashboard and panes. These numbers change with your Home Assistant, so they are not in the grade.", "",
                f"App process start to dashboard load {fmt(r['app_start_to_dashboard_ms'], ' ms')} ({', '.join(r['launch']) or 'n/a'}). "
                f"Dashboard load {fmt(r['dashboard_load_ms'], ' ms')} ({', '.join(r['settle_how']) or 'n/a'}); "
                f"its load event {fmt(r['dashboard_page_stop_ms'], ' ms')}; force-stop to a settled dashboard "
                f"{fmt(r['stop_to_dashboard_ms'], ' ms')}." + (f" Failed cold starts: {len(r['errors'])}." if r["errors"] else "")
                + (f" The screen went off in {', '.join(r['screen_off_samples'])}; left out." if r.get("screen_off_samples") else ""),
                f"Idle: app PSS {fmt(i['app_pss_kb'], ' MB', 1024)}, device RAM in use {fmt(i['device_used_pct'], ' %')}, "
                f"app CPU {fmt(i['cpu_app_pct_of_core'], ' % of a core')}, device busy {fmt(i['cpu_device_busy_pct'], ' %')}, "
                f"{fmt(i['frames']['presented_fps'], ' frames/s')} presented."]
        out += ["", "| Process | PSS at idle |", "|---|---|"] + [f"| {k} | {fmt(v, ' MB', 1024)} |" for k, v in i["by_role_pss_kb"].items()]
    m = r and r["memory"]
    if m and m["budget"]:
        out += ["", "## Memory budget (real use, not graded)", ""]
        for k, c in m["budget"]["checks"].items():
            val = "n/a" if c["value_kb"] is None else f"{c['value_kb'] / 1024:.0f} MB"
            out.append(f"- {k}: {val} against {c['budget_kb'] / 1024:.0f} MB: {'ok' if c['ok'] else 'over' if c['ok'] is False else 'n/a'}")
    if r and r["panes"]:
        out += ["", "## Panes (real use, not graded)", "", "| Pane | Method | PSS | CPU |", "|---|---|---|---|"]
        for p in r["panes"]:
            pss = f"{p['pss_kb'] / 1024:.1f} MB" + ("" if p.get("pss_measurable", True) else " (within noise)")
            cpu = p.get("cpu_pct_of_core")
            cpu = "n/a" if cpu is None else f"{cpu:.1f} %" + ("" if p.get("cpu_measurable", True) else " (within noise)")
            out.append(f"| {p['id']} | {p['method']} | {pss} | {cpu} |")
    return "\n".join(out) + "\n"


def noise_word(measurable, tol):
    """A share's suffix: nothing when measurable or when there is no tolerance (its cell says why)."""
    return "" if measurable or tol is None else " (within tolerance)"


def tol_cell(p):
    n = f"n={p['samples_on']} on, {p['samples_off']} off"
    return f"none: under 2 samples a side ({n})" if p["pss_tolerance_kb"] is None else f"±{p['pss_tolerance_kb'] / 1024:.1f} MB ({n})"


def budget_word(ok):
    return "ok" if ok else "over" if ok is False else "n/a"


def ram_markdown(ram):
    """RAM on Clarity's reference dashboards (#68): per process at idle, the peak during the graded
    tasks, each switchable pane's share, and the frame's budgets."""
    if not ram:
        return []
    out = ["", "## RAM on the reference dashboards (not graded)", "",
           "PSS per process, from `dumpsys meminfo`: at idle on the typical reference dashboard after each "
           "graded run, and between the graded pages. The peak is the largest of the between-page samples only (the "
           "idle samples aren't in it, so the idle median can be above it). They are taken between tasks, not inside "
           "them (a dumpsys would disturb what a task measures), so a short peak inside a task can be missed. The VmHWM "
           "line below is each process's peak RSS since it started, so it includes such a peak, but RSS counts "
           "shared pages in full: it bounds the PSS peak from above and doesn't measure it. It covers only the processes "
           "still running, with a readable VmHWM, when it is read: one that exited earlier isn't in it.", ""]
    idle, peak = ram.get("reference_idle"), ram.get("graded_peak")
    roles = sorted(set((idle or {}).get("by_role_pss_kb", {})) | set((peak or {}).get("by_role_pss_kb", {})))
    if idle or peak:
        out += ["| Process | Idle (median of runs) | Peak, sampled between the graded pages |", "|---|---|---|"]
        for r in roles:
            pk = (peak or {}).get("by_role_pss_kb", {}).get(r)
            pk = "n/a" if pk is None else f"{pk / 1024:.0f} MB"
            out.append(f"| {r} | {fmt((idle or {}).get('by_role_pss_kb', {}).get(r), ' MB', 1024)} | {pk} |")
        peak_app = "n/a" if not peak else f"{peak['app_pss_kb'] / 1024:.0f} MB"
        out.append(f"| app, all processes | {fmt((idle or {}).get('app_pss_kb'), ' MB', 1024)} | {peak_app} |")
        if peak:
            out += ["", f"Peak RSS of the app's processes since they started (VmHWM, summed): {peak['app_rss_hwm_kb'] / 1024:.0f} MB, "
                        f"from {peak['samples']} sample(s) between pages."]
    b = ram.get("budget")
    if b:
        out += ["", "Budgets (frames/<id>/budgets.json):"]
        for k, c in b["checks"].items():
            val = "n/a" if c["value_kb"] is None else f"{c['value_kb'] / 1024:.0f} MB"
            # A peak here is the sampled one above (between pages), not a maximum inside the tasks.
            what = " (sampled between pages)" if "peak" in k else ""
            out.append(f"- {k}{what}: {val} against {c['budget_kb'] / 1024:.0f} MB: {budget_word(c['ok'])}")
    if ram.get("panes"):
        out += ["", "Each pane on by itself against every pane off, on the typical reference dashboard (median of runs; "
                    "the tolerance is twice the noisier set's estimated standard deviation; `:boot` left out). Only samples "
                    "whose kiosk log shows what their label says count. A budget is checked only with at least "
                    f"{G.PANE_BUDGET_MIN_SAMPLES} samples each side and a tolerance at most "
                    f"{G.PANE_TOLERANCE_MAX_OF_BUDGET:.0%} of it:", "",
                "| Pane | PSS | Tolerance | Budget | CPU |", "|---|---|---|---|---|"]
        notes = []
        for p in ram["panes"]:
            if p.get("error"):
                out.append(f"| {p['id']} | not measured: {p['error']} | | | |")
                continue
            if p.get("off_by") == "mute":
                notes.append(f"{p['id']}: the kiosk kept voice on in every sample, so it is off by its microphone mute only. "
                             "This is its running cost (the wake word), not its whole cost (the code it loads).")
            if p.get("verified") is False:
                notes.append(f"{p['id']}: not checked (samples from before the kiosk's log was read); it may not have been off.")
            if p.get("excluded"):
                notes.append(f"{p['id']}: {p['excluded']} sample(s) left out, the kiosk didn't run what their label says.")
            if p.get("boot_samples_on") or p.get("boot_samples_off"):
                notes.append(f"{p['id']}: `:boot` ({p['boot_pss_kb'] / 1024:.1f} MB) ran in {p['boot_samples_on']} of "
                             f"{p['samples_on']} on and {p['boot_samples_off']} of {p['samples_off']} off sample(s); "
                             "it isn't a pane, so the share leaves it out.")
            pb = (ram.get("pane_budget") or {}).get(p["id"]) or {}
            word = "not measurable" if pb.get("measurable") is False else budget_word(pb.get("ok"))
            cap = "none" if pb.get("budget_kb") is None else f"{pb['budget_kb'] / 1024:.0f} MB, {word}"
            out.append(f"| {p['id']} | {p['pss_kb'] / 1024:+.1f} MB{noise_word(p['pss_measurable'], p['pss_tolerance_kb'])} "
                       f"| {tol_cell(p)} | {cap} "
                       f"| {p['cpu_pct_of_core']:+.1f} % of a core{noise_word(p['cpu_measurable'], p['cpu_tolerance_pct'])} |")
        out += [""] + [f"- {n}" for n in notes] if notes else []
    if ram.get("screen_off_samples"):
        out += ["", "The screen went off in " + ", ".join(ram["screen_off_samples"]) + "; those samples are left out."]
    if ram.get("panes_not_switched"):
        out += ["", "Not measured on their own: " + "; ".join(f"{p['id']} ({p['why']})" for p in ram["panes_not_switched"]) + "."]
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run_dir")
    ap.add_argument("--apk", action="append", default=[])
    ap.add_argument("--budgets", default=None, help="a frame's budgets file; default frames/<id>/budgets.json, else tools/perf/budgets.json")
    ap.add_argument("--keep-apk", action="store_true", help="keep the apk sizes of the existing report.json (run.sh --idle-only)")
    ap.add_argument("--out", default=None, help="write report.json and report.md here (default: the run directory)")
    ap.add_argument("--app-version", default=None, help="show this label as the app's version (never graded)")
    a = ap.parse_args()
    rep = build(a.run_dir, a.apk, a.budgets)
    old = os.path.join(a.run_dir, "report.json")
    if a.keep_apk and not a.apk and os.path.exists(old):
        rep["apk"] = read_json(old).get("apk", [])
    if a.app_version:
        label_app_version(rep, a.app_version)
    out = a.out or a.run_dir
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "report.json"), "w") as f:
        json.dump(rep, f, indent=1)
        f.write("\n")
    md = markdown(rep)
    with open(os.path.join(out, "report.md"), "w") as f:
        f.write(md)
    print(md)


def label_app_version(rep, label):
    """The app's version as given (--app-version), in place of the device's: the frame's app, and the
    APK sizes' file names (a test build's carry its commit). Only labels: the grade reads none of it,
    and the summary's app_version is this label."""
    app = rep["frame"]["app"]
    app.update(version_name=label, version_code=None, version_label="given")
    for x in rep.get("apk") or []:
        x["file"] = f"{app.get('package', 'app')}-{label}.apk"
    return rep


if __name__ == "__main__":
    main()
