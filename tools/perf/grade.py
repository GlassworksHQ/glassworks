"""Clarity's rubric (MIT): the grade of the Glassworks perf test. docs/perf.md explains each row and links its source.

Bands follow Core Web Vitals: good, needs-improvement, poor. Thresholds come from published
guidelines and do not depend on any frame's own numbers. Change them only on purpose, with the
docs and test/test_grade.py in the same PR.
"""
import json
import os

with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "page", "rubric.json")) as _f:
    RUBRIC = json.load(_f)

RUBRIC_VERSION = RUBRIC["version"]
# Clarity's version: v1, the one Glassworks 1.0.0 ships with (2026-11-19). A report or summary without it is
# pre-release, and one of another version is graded by its own rules: re-measure.
CLARITY = RUBRIC["clarity"]
# The numbers started again on 2026-11-19, as on 2026-11-14: the first v1's summaries also say 1. A v1 measured
# before this day (UTC, measured_on) is theirs (rubric.json clarity_measured_since).
CLARITY_SINCE = RUBRIC["clarity_measured_since"]
FIRST_V1 = "measured with the first Clarity v1 (2026-11-14), before the version started again at 1"


def first_v1(measured_on):
    """A v1 measured before CLARITY_SINCE is the first v1's. Every run records its day (device.json, since #89), the
    first v1's included; one without a day isn't judged by it."""
    return bool(measured_on) and measured_on < CLARITY_SINCE
PRE_RELEASE = "measured with a pre-release version of Clarity"
NAME = RUBRIC["name"]          # "Clarity": the test's name, kept in rubric.json only
GOOD, NI, POOR = "good", "needs-improvement", "poor"
ORDER = {GOOD: 0, NI: 1, POOR: 2}

# metric id -> (good if value <= good_max, poor if value > poor_above or >= poor_at)
METRICS = RUBRIC["metrics"]

# The letter rules live in rubric.json ("grade"), so grade.ts (the site) reads the same ones.
RULES = RUBRIC["grade"]
PROTOCOL_VERSION = RUBRIC["protocol_version"]
TIERS = RULES["pages"]
TASKS = list(RULES["tasks"])

# Scroll has its own levels, judged per run (rubric v5): premium is the 60 fps rule (frames band
# good), good is an average of >= 50 fps with p95 <= 50 ms, fluid >= 30 fps with p95 <= 67 ms.
SCROLL_LEVELS = RULES["scroll_levels"]
# Live updates too: good when 95% of changes reach the screen within 150 ms, fluid within 250 ms; premium adds
# the 60 fps bar on the frames that had a change to draw. Not tap's 100 ms: a live change answers no one's
# touch on the panel (Clarity v1, docs/perf.md "Why live updates aren't held to 100 ms").
UPDATE_LEVELS = RULES["update_levels"]
BELOW = RULES["below"]
CAMERA_LEVELS = RULES["camera_levels"]
CAMERA_PAGES = RULES["camera_pages"]
# HA's more-info dialog (rubric 3, #211): good when the slowest of a view's dialogs settles within 1 s
# of the tile's event, fluid within 2 s; premium adds the 60 fps bar on the open animation's frames.
DIALOG_LEVELS = RULES["dialog_levels"]
NO_CAMERA = "none"            # a page without streams: every level accepts it
# A frame without a touchscreen (rubric 2, 2026-11-23): nobody scrolls it, so its scroll isn't graded. Each task in
# no_touch.tasks takes no_touch.value on every page, which every level accepts: never a limit, never missing.
NO_TOUCH = RULES["no_touch"]
NOT_APPLICABLE = NO_TOUCH["value"]
NO_TOUCH_REASON = NO_TOUCH["reason"]
TASK_ORDER = [lv["level"] for lv in SCROLL_LEVELS] + [BELOW]
SCROLL_ORDER = TASK_ORDER

# What a page needs to pass at each level: {task: accepted bands, or task levels}, best level first.
# A tap is good at INP <= 200 ms; a view may load in up to 4 s (needs improvement).
LEVELS = {lv["level"]: {task: set(ok) for task, ok in lv["accepts"].items()} for lv in RULES["levels"]}
LEVEL_ORDER = [lv["level"] for lv in RULES["levels"]]
LEVEL_RANK = dict([(lv, len(LEVEL_ORDER) - i) for i, lv in enumerate(LEVEL_ORDER)] + [(None, 0)])

# The letters, best first: (letter, plain meaning, {page: level it needs}). The bands underneath are
# the cited ones; the pages, the levels and this table are project choices (docs/perf.md, "The grade").
LETTERS = [(l["letter"], l["meaning"], l["needs"]) for l in RULES["letters"]]
E_MEANING = RULES["fallback"]["meaning"]
MEANINGS = dict([(l, m) for l, m, _ in LETTERS] + [(RULES["fallback"]["letter"], E_MEANING)])
START_CAP = RULES["start_cap"]["letter"]
# Letters rank by their place in the table (A+ first), the fallback last; never by string order.
LETTER_ORDER = [l for l, _, _ in LETTERS] + [RULES["fallback"]["letter"]]

# Not in the grade: no published standard, so the band is a project choice.
JS_BURST = RUBRIC["js_burst_ms"]

def band(value, rule):
    if value is None:
        return None
    if "poor_at" in rule and value >= rule["poor_at"]:
        return POOR
    if "poor_above" in rule and value > rule["poor_above"]:
        return POOR
    return GOOD if value <= rule["good_max"] else NI


def worst(bands):
    bands = [b for b in bands if b]
    return max(bands, key=ORDER.get) if bands else None


def frame_band(stats):
    """Band of one ladder step (or the reference load) from its frame stats."""
    if not stats or not stats.get("n"):
        return None
    return worst(band(stats.get(k), METRICS["frame_" + k]) for k in ("p95_ms", "jank_pct", "frozen_pct"))


def capacity(steps, accept):
    """Largest element count reached before the first step whose band is not in `accept`.
    steps: [(n, stats)] in ladder order."""
    best = 0
    for n, stats in steps:
        if frame_band(stats) not in accept:
            break
        best = n
    return best


def failed_run(run):
    """A run record that stands for a task the view never got to measure, measured as failing: the view didn't finish
    within the runner's deadline (timed_out), or its browser page was closed (lost, the app said its page's process was
    killed or crashed). Below fluid, never missing. grade.ts's failedRun is the same."""
    return bool(run) and bool(run.get("timed_out") or run.get("lost"))


def scroll_level(stats):
    """One run's scroll level: the first of rubric.json's scroll_levels whose rule holds, or "slow"
    (measured, below fluid); None when it wasn't measured."""
    if failed_run(stats):
        return BELOW                    # the view didn't finish, or its page was lost: measured, failed
    if not stats or not stats.get("n"):
        return None
    for lv in SCROLL_LEVELS:
        if "frames_band" in lv:
            if frame_band(stats) in lv["frames_band"]:
                return lv["level"]
        elif (stats.get("fps") or 0) >= lv["fps_min"] and stats.get("p95_ms") is not None and stats["p95_ms"] <= lv["p95_max_ms"]:
            return lv["level"]
    return BELOW


def update_level(run):
    """One run's live-updates level from its change-to-screen p95 (and, for premium, the frames that
    had a change to draw), or "slow"; None when it wasn't measured."""
    if failed_run(run):
        return BELOW
    if not run or not run.get("latency_n") or run.get("latency_p95_ms") is None:
        return None
    for lv in UPDATE_LEVELS:
        if run["latency_p95_ms"] > lv["latency_p95_max_ms"]:
            continue
        if "frames_band" in lv and frame_band(run.get("frames")) not in lv["frames_band"]:
            continue
        return lv["level"]
    return BELOW


def dialog_level(run):
    """One run's more-info level (rubric 3): from the slowest dialog's time to settled and, for
    premium, the open animations' frames; "slow" when a dialog never opened or never settled, or the
    view didn't get to them; None when they weren't measured."""
    if failed_run(run):
        return BELOW
    if not run or not run.get("n"):
        return None
    if run.get("failed") or run.get("settled_ms") is None:
        return BELOW
    for lv in DIALOG_LEVELS:
        if run["settled_ms"] > lv["settled_max_ms"]:
            continue
        if "frames_band" in lv and frame_band(run.get("frames")) not in lv["frames_band"]:
            continue
        return lv["level"]
    return BELOW


def camera_level(run):
    """One run's camera level from its worst stream (#174): first frame, frames shown, the longest gap
    and the gaps over 500 ms added up; "slow" when a stream showed no frame; None when the run has no
    camera record."""
    return camera_level_why(run)[0]


# Why camera_level_why left a level open (report.py's words for them).
OPEN_STALLS_NA, OPEN_GOOD_GAP, OPEN_FLUID_TOTAL = "stalls n/a", "good's gap", "fluid's total"


def camera_level_why(run):
    """(level, why): camera_level, and when the level is open (None, measured), which bar couldn't be
    settled: OPEN_STALLS_NA, OPEN_GOOD_GAP or OPEN_FLUID_TOTAL. why is None otherwise. grade.ts gives
    the same levels; the reasons are the report's."""
    if not run:
        return None, None
    if failed_run(run):
        return BELOW, None
    if run.get("first_frame_ms") is None or run.get("shown_pct") is None:
        return BELOW, None
    if run.get("stalls_reported") is False:
        # Stalls n/a (no freeze counts, or counts that left part of the window out): not measured,
        # unless the first frame and frames shown already fail every level, as no stalls could lift.
        if not any(run["first_frame_ms"] <= lv["first_frame_max_ms"] and run["shown_pct"] >= lv["shown_min_pct"]
                   for lv in CAMERA_LEVELS):
            return BELOW, None
        return None, OPEN_STALLS_NA
    for lv in CAMERA_LEVELS:
        if run["first_frame_ms"] > lv["first_frame_max_ms"] or run["shown_pct"] < lv["shown_min_pct"]:
            continue
        if "max_gap_max_ms" in lv:
            if run.get("max_gap_ms") is None or run["max_gap_ms"] > lv["max_gap_max_ms"]:
                continue
            if run["max_gap_ms"] + (run.get("uncovered_ms") or 0) > lv["max_gap_max_ms"]:
                return None, OPEN_GOOD_GAP      # a gap open at the counts' end could run on into what they left out (#265)
        if "stall_total_max_ms" in lv:
            if run.get("stall_total_ms") is None or run["stall_total_ms"] >= lv["stall_total_max_ms"]:
                continue
            if run["stall_total_ms"] + (run.get("uncovered_ms") or 0) >= lv["stall_total_max_ms"]:
                return None, OPEN_FLUID_TOTAL   # under the bar as counted, maybe not in what the counts left out (#265)
        return lv["level"], None
    return BELOW, None


def median_level(levels):
    """The middle level of several runs (the worse of the two middles for an even count)."""
    ls = sorted((l for l in levels if l), key=TASK_ORDER.index)
    return ls[len(ls) // 2] if ls else None


def tier_bands(tier, page=None, touch=True):
    """The band of each task on one page (None when it wasn't measured). Frames bands come from the
    caller as the median of each run's band. The camera task is `none` on a page without streams; on a
    frame without touch (touch False), each no_touch task is NOT_APPLICABLE, whatever was measured."""
    tier = tier or {}
    out = {
        "tap": band(tier.get("inp_ms"), METRICS["inp_ms"]),
        "scroll": tier.get("scroll_level"),
        "updates": tier.get("updates_level"),
        "load": band(tier.get("load_ms"), METRICS["load_ms"]),
        "camera": tier.get("camera_level") if page in CAMERA_PAGES else NO_CAMERA,
        # Rubric 2: the main thread's blocking from the first screen drawn to usable (TBT's bands).
        "blocking": band(tier.get("blocking_ms"), METRICS["blocking_ms"]),
        # Rubric 3: HA's more-info dialogs, the median of each run's level.
        "dialog": tier.get("dialog_level"),
    }
    if touch is False:
        out.update({task: NOT_APPLICABLE for task in NO_TOUCH["tasks"]})
    return out


def page_level(bands):
    """"smooth", "fluid" or None: the best level whose every task passes."""
    for level in LEVEL_ORDER:
        if all(bands.get(task) in ok for task, ok in LEVELS[level].items()):
            return level
    return None


def limits(bands, level):
    """The tasks that keep a page from `level`."""
    return [task for task, ok in LEVELS[level].items() if bands.get(task) not in ok]


# The best each task can be: the first of its levels (scroll, live updates, the camera, dialogs), else good. Every
# level accepts it, so a missing measurement set to it holds no page back: grade() grades with it for the best case.
BEST = {task: (RULES[src][0]["level"] if isinstance(RULES.get(src), list) else GOOD) for task, src in RULES["tasks"].items()}


def letter_of(levels, start):
    """(letter, capped): the first letter whose every page need holds, then the start cap (never raises it)."""
    letter = next((l for l, _, needs in LETTERS
                   if all(LEVEL_RANK[levels[t]] >= LEVEL_RANK[need] for t, need in needs.items())),
                  RULES["fallback"]["letter"])
    capped = start == RULES["start_cap"]["band"] and LETTER_ORDER.index(letter) < LETTER_ORDER.index(START_CAP)
    return (START_CAP if capped else letter), capped


def grade(tiers, start_ms, touch=True):
    """tiers: {light|typical|heavy: {"inp_ms", "load_ms", "blocking_ms", "scroll_level", "updates_level", "camera_level",
    "dialog_level"}};
    start_ms: the restart, process start to the start view usable (protocol 4; engine ready before
    it). touch: False for a frame without a touchscreen (rubric 2): its no_touch tasks are not applicable.
    Returns the letter, its plain meaning, each page's bands and level, and what is missing. A
    missing measurement fails whatever needs it, and a missing restart caps nothing. The grade is provisional only
    when what is missing could change the letter: when the best case (each missing band, and a missing restart, at the
    best it can be) and the worst (each missing band failing, as in the letter, and a missing restart slow enough to
    cap it) give different letters; the start cap applies in both. When they agree, missing_settled_by says what
    decides the letter instead."""
    bands = {t: tier_bands((tiers or {}).get(t), t, touch) for t in TIERS}
    levels = {t: page_level(bands[t]) for t in TIERS}
    start = band(start_ms, METRICS["start_ms"])
    missing = [f"{t}.{m}" for t in TIERS for m, b in bands[t].items() if b is None] + ([] if start else ["start"])
    letter, capped = letter_of(levels, start)
    # The worst case is the letter, but for a missing restart: the letter doesn't cap it, the worst case does.
    lowest, _ = letter_of(levels, start or RULES["start_cap"]["band"])
    highest, _ = letter_of({t: page_level({m: BEST[m] if b is None else b for m, b in bands[t].items()}) for t in TIERS},
                        start or GOOD)
    out = {
        "rubric_version": RUBRIC_VERSION,
        "grade": letter,
        "meaning": MEANINGS[letter],
        "capped_by_start": capped,
        "provisional": highest != lowest,
        "missing": missing,
        "bands": bands,
        "levels": levels,
        "start": {"value": start_ms, "band": start},
    }
    if missing and highest == lowest:
        out["missing_settled_by"] = settled_by(out)
    if touch is False:
        # Said, not only implied by the bands: report.md and the headline read it (only on such a frame, so a frame
        # with touch keeps its record as it was).
        out["not_applicable"] = {task: NO_TOUCH_REASON for task in NO_TOUCH["tasks"]}
    return out


def settled_by(g):
    """Why a grade's missing measurements can't change its letter: {"capped_by_start": the restart caps the letter
    (it was measured, and slow), "limits": next_limits without the missing tasks, so what measured already keeps the
    grade from the next letter up}. Both empty when the next letter up doesn't need the missing pages (or there is
    none)."""
    missing = set(g["missing"])
    lims = [dict(x, tasks=[k for k in x["tasks"] if f"{x['page']}.{k}" not in missing]) for x in next_limits(g)]
    return {"capped_by_start": g["start"]["band"] == RULES["start_cap"]["band"] and g["grade"] == START_CAP,
            "limits": [x for x in lims if x["tasks"]]}


def median_band(bands):
    """The middle band of several runs (the worse of the two middles for an even count)."""
    bs = sorted((b for b in bands if b), key=ORDER.get)
    if not bs:
        return None
    return bs[len(bs) // 2]


def next_limits(g):
    """What keeps a grade from the next letter up: [{"page", "needs", "tasks"}] for each page below
    the level that letter needs. Empty for the best letter."""
    letters = [l for l, _, _ in LETTERS]
    if g.get("grade") is None or g["grade"] == letters[0]:
        return []
    up = letters[-1] if g["grade"] not in letters else letters[letters.index(g["grade"]) - 1]
    needs = next(n for l, _, n in LETTERS if l == up)
    return [{"page": t, "needs": needs[t], "tasks": limits(g["bands"][t], needs[t])}
            for t in TIERS if t in needs and LEVEL_RANK[g["levels"][t]] < LEVEL_RANK[needs[t]]]


def summary_inputs(summary):
    """A summary's measurements -> grade()'s inputs. Frames bands: each run's band, then the median."""
    tiers = {}
    for t in TIERS:
        p = (summary.get("pages") or {}).get(t)
        if not p:
            continue
        tiers[t] = {"inp_ms": p.get("inp_ms"), "load_ms": p.get("open_ms"), "blocking_ms": p.get("blocking_ms"),
                    "scroll_level": median_level(scroll_level(r) for r in (p.get("scroll") or {}).get("runs") or []),
                    "updates_level": median_level(update_level(r) for r in (p.get("updates") or {}).get("runs") or []),
                    "camera_level": median_level(camera_level(r) for r in (p.get("camera") or {}).get("runs") or []),
                    "dialog_level": median_level(dialog_level(r) for r in (p.get("dialog") or {}).get("runs") or [])}
    return tiers, summary.get("restart_ms")


def protocol_name(v):
    """A protocol version in words; grade.ts says the same."""
    return "unknown" if v is None else str(v)


def grade_summary(summary):
    """The letter from a summary (glassworks.perf-summary/3), as the site computes it with grade.ts.
    status: graded, re-measure (pre-release, or another protocol: these rules don't apply) or not-comparable (the
    measurement says why, e.g. not at the default zoom)."""
    # Not comparable first: it says why there is nothing to grade, whatever the protocol.
    if summary.get("not_comparable"):
        return {"status": "not-comparable", "grade": None, "meaning": None, "why": summary["not_comparable"]}
    if summary.get("clarity") != CLARITY:
        # Before v1 (pre-release protocols 1 to 8): the numbers can repeat, so the marker decides. A
        # summary of another Clarity version (v1 under rubric 2) is graded only by its own rules.
        why = (f"{PRE_RELEASE} (protocol {protocol_name(summary.get('protocol_version'))}): re-measure"
               if summary.get("clarity") is None else
               f"measured with Clarity v{summary['clarity']}; these rules are Clarity v{CLARITY}'s: re-measure")
        return {"status": "re-measure", "grade": None, "meaning": None, "why": why}
    if first_v1(summary.get("measured_on")):
        return {"status": "re-measure", "grade": None, "meaning": None, "why": f"{FIRST_V1}: re-measure"}
    if summary.get("protocol_version") != PROTOCOL_VERSION:
        return {"status": "re-measure", "grade": None, "meaning": None,
                "why": f"measured with protocol {protocol_name(summary.get('protocol_version'))}, "
                       f"these rules are for protocol {PROTOCOL_VERSION}"}
    # A summary says touch: false only for a frame without a touchscreen (summary.py); without it, touch.
    g = grade(*summary_inputs(summary), touch=summary.get("touch") is not False)
    # Missing measurements only when they could change the letter (grade); a gap always.
    g["provisional"] = bool(g["provisional"] or summary.get("gaps"))
    g["status"] = "graded"
    g["limits"] = next_limits(g)
    # The camera video's path (protocol 7): letters from different transports aren't comparable, so
    # it goes with the letter, and over adb a caveat for heavy's cameras. Never changes the letter.
    t = summary.get("webrtc_transport") or {}
    g["transport"] = t.get("transport")
    g["transport_caveat"] = TRANSPORT_CAVEAT if t.get("possibly_limited") else None
    return g


TRANSPORT_CAVEAT = "heavy's cameras went through the test's adb link and may be limited by it, not by the panel"


def memory_budget(memory, budget):
    """#68-style budget check. memory: app_pss_peak_kb, mem_total_kb, mem_available_min_kb.
    budget: app_pss_max_pct_of_ram, device_available_min_pct (the `ram` of frames/<id>/budgets.json).
    ok is None unless every budgeted value was measured."""
    total = memory.get("mem_total_kb")
    if not total:
        return None
    pss = memory.get("app_pss_peak_kb")
    avail = memory.get("mem_available_min_kb")
    pss_max = total * budget["app_pss_max_pct_of_ram"] / 100
    avail_min = total * budget["device_available_min_pct"] / 100
    checks = {
        "app_pss_peak": {"value_kb": pss, "budget_kb": round(pss_max), "ok": None if pss is None else pss <= pss_max},
        "device_available_min": {"value_kb": avail, "budget_kb": round(avail_min),
                                 "ok": None if avail is None else avail >= avail_min},
    }
    oks = [c["ok"] for c in checks.values()]
    ok = False if False in oks else (None if None in oks else True)
    return {"budget": budget, "checks": checks, "ok": ok}


def frame_budget(default, frame=None):
    """A frame's budgets: its `ram` keys over the default's (so a frame may name one key), and its own
    `panes` (a frame's panes are its own; the default has none)."""
    frame = frame or {}
    return {"ram": dict(default.get("ram") or {}, **(frame.get("ram") or {})),
            "panes": frame.get("panes") or default.get("panes") or {}}


# A pane's share is checked against its budget only with this many samples on each side and a
# tolerance at most this part of the budget (#186). The tolerance is about 2 σ of one sample, not a
# confidence interval, so "ok" means the share is under the budget by this test's noise, no more.
PANE_TOLERANCE_MAX_OF_BUDGET = 0.25
PANE_BUDGET_MIN_SAMPLES = 3


def pane_budgets(panes, budgets):
    """Each measured pane's PSS against its budget (ram_pss_max_kb). ok is None when the pane has no
    budget, wasn't measured, or can't be told (measurable false: too few samples, or too wide a
    tolerance)."""
    out = {}
    for p in panes:
        cap = (budgets.get(p["id"]) or {}).get("ram_pss_max_kb")
        tol = p.get("pss_tolerance_kb")
        n = min(p.get("samples_on") or 0, p.get("samples_off") or 0)
        measurable = None if cap is None else (tol is not None and n >= PANE_BUDGET_MIN_SAMPLES
                                               and tol <= PANE_TOLERANCE_MAX_OF_BUDGET * cap)
        out[p["id"]] = {"value_kb": p.get("pss_kb"), "budget_kb": cap, "tolerance_kb": tol, "measurable": measurable,
                        "ok": None if cap is None or p.get("pss_kb") is None or measurable is False else p["pss_kb"] <= cap}
    return out
