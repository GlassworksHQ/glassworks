#!/usr/bin/env python3
"""Clarity summary (MIT), from the Glassworks perf test: a small, stable, frame-level summary of a finished report.json, for
places that show a frame's grade (glassworks.dev, a frame's design/frame.json). docs/perf.md, "The
summary", documents every field.

It holds measurements only, never the letter: the letter is derived, so the site computes it at
render time from the summary and rubric.json (tools/perf/grade.ts; grade.py gives the same answer).
Only graded, deterministic measurements go in: each reference dashboard's numbers and each run's
frame stats, the restart, and what they were measured with. Two optional parts carry measurements
that aren't graded: `ram` (on the reference dashboards, with each switchable pane's share) and
`apk` (sizes per ABI). Nothing from real use (your own dashboard, memory, CPU, panes) and nothing
device-unique (no serial, no addresses).

  tools/perf/summary.py perf-s1/report.json perf-s1/summary.json [--report-url report.json]
                        [--allow-provisional]

A summary with gaps (a run failed, a task not measured that could change the letter) or one that isn't
comparable (not at the default zoom) is not written unless --allow-provisional is given; then `complete` is
false and the facts say why. One missing only what can't change the letter is written: `complete` is false,
and `unmeasured` says why it is missing.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import grade as G  # noqa: E402
import notes as N  # noqa: E402

SCHEMA = "glassworks.perf-summary/3"


def _med(spread, digits=None):
    """The median of a {median, min, max, n} spread, rounded for display; None if not measured."""
    if not spread or spread.get("median") is None:
        return None
    v = spread["median"]
    return round(v) if digits is None else round(v, digits)


def _graded(spread):
    """A graded median, exactly as the report graded it (/3): rounding could carry it across a band's
    edge (a blocking median of 600.4 ms is poor, 600 isn't)."""
    if not spread or spread.get("median") is None:
        return None
    return spread["median"]


def _n(spread):
    return (spread or {}).get("n") or 0


class Refused(Exception):
    pass


def page_summary(s):
    """One reference dashboard's measurements."""
    sc, up = s.get("scroll") or {}, s.get("updates") or {}
    out = {
        # The graded medians unrounded (/3), so a summary bands them as the report did.
        "inp_ms": _graded(s.get("inp_ms")),
        # Rubric 2 (/3): open_ms is navigation to the first screen drawn (/2: to usable); blocking_ms
        # the main thread's blocking from then to usable (graded); usable_ms when it was usable
        # (rubric 1's open; detail).
        "open_ms": _graded(s.get("load_ms")),
        "blocking_ms": _graded(s.get("blocking_ms")),
        "usable_ms": _med(s.get("usable_ms")),
        "scroll": {"fps": _med(sc.get("fps"), 1), "p95_ms": _med(sc.get("p95_ms"), 2),
                   "scrolled_px": _med(s.get("scrolled_px")), "sf_valid": s.get("sf_valid") or [],
                   "during_updates": s.get("swipes_during_updates") or [],
                   "swipes_moved": s.get("swipes_moved") or 0, "swipes_at_edge": s.get("swipes_at_edge") or 0,
                   "runs": s.get("scroll_runs") or []},
        # Change to screen (graded), each run's record (latency and the frames that had a change to
        # draw), and frame pacing (detail).
        "updates": {"latency_p95_ms": _med(s.get("latency_p95_ms"), 1), "latency_median_ms": _med(s.get("latency_median_ms"), 1),
                    "pacing_p95_ms": _med(up.get("p95_ms"), 2), "runs": s.get("updates_runs") or []},
        # Live cameras (#174), on the pages with streams: each run's worst stream (graded per run).
        # Protocol 6 (#202): `path`, what the graded streams played over (web_rtc), and `fallback`,
        # the same page's cameras over HLS (detail, not graded; null before protocol 6).
        "camera": camera_summary(s),
        # Runs in which this page didn't finish within the runner's deadline (#202): its unmeasured
        # tasks are in the runs as failing (`timed_out` records; tap at the deadline, blocking at it
        # when the main thread was never free, open at it only when the view was never drawn).
        "timed_out_runs": s.get("timed_out_runs") or 0,
        "runs": {"tap": _n(s.get("inp_ms")), "scroll": len(s.get("scroll_runs") or []),
                 "updates": len(s.get("updates_runs") or []), "open": _n(s.get("load_ms")),
                 "blocking": _n(s.get("blocking_ms")),
                 "camera": len(s.get("camera_runs") or [])},
    }
    if s.get("lost_runs"):
        # Runs in which the panel closed this page's browser page (report.py lost): every task failed in them, as
        # `{"lost": true}` records and tap, open and blocking at the runner's view deadline; `lost_why` says why in
        # words. Only when there was one, so every other summary stays as it was, byte for byte (test_summary).
        out["lost_runs"] = s["lost_runs"]
        out["lost_why"] = list(s.get("lost_why") or [])
    if "dialog_runs" in s:
        # Clarity v3 (#211), added within /3: HA's more-info dialogs, each run's record (graded per run:
        # the slowest to settle, how many failed, the open animations' frames). Absent for a report
        # from before v3, so its summary stays as it was, byte for byte (test_summary).
        out["dialog"] = dialog_summary(s)
        out["runs"]["dialog"] = len(s["dialog_runs"])
    return out


def dialog_summary(s):
    keys = ("n", "failed", "settled_ms")
    return {"settled_ms": _med(s.get("dialog_settled_ms")),
            "runs": [dict({"timed_out": True} if r.get("timed_out") else {"lost": True} if r.get("lost") else
                          dict({k: r.get(k) for k in keys}, frames=r.get("frames")))
                     for r in s["dialog_runs"]]}


def camera_summary(s):
    keys = ("first_frame_ms", "shown_pct", "max_gap_ms", "stall_total_ms")
    runs = s.get("camera_runs") or []
    tag = lambda r: dict({"timed_out": True} if r.get("timed_out") else {},  # noqa: E731
                         **({"lost": True} if r.get("lost") else {}),
                         **({"stalls_reported": False} if r.get("stalls_reported") is False else {}),
                         # #265: what the receiver's counts left out of the window (the graders hold
                         # the fluid total bar against stalls + it); WebRTC runs only.
                         **({"uncovered_ms": r["uncovered_ms"]} if r.get("uncovered_ms") is not None else {}))
    # A lost run (its page closed before the camera window) has no transport: the path comes from the others, and
    # is unknown (None) when every run was lost.
    fb, paths = s.get("camera_fallback"), {r.get("path") or "hls" for r in runs if not r.get("lost")}
    return {"runs": [dict({k: r.get(k) for k in keys}, **tag(r)) for r in runs],
            "path": paths.pop() if len(paths) == 1 else None,
            "fallback": fb and {"path": fb["path"], "runs": [{k: r.get(k) for k in keys} for r in fb["runs"]]}}


def ram_summary(ram):
    """The report's `ram` (reference dashboards, #68), in kB: medians at idle, the peak during the
    graded tasks, and each pane's share with its tolerance. None if it wasn't measured."""
    if not ram:
        return None
    idle, peak = ram.get("reference_idle") or {}, ram.get("graded_peak") or {}
    budget = ram.get("pane_budget") or {}
    return {
        "idle_app_pss_kb": _med(idle.get("app_pss_kb")),
        "idle_by_process_pss_kb": {r: _med(v) for r, v in sorted((idle.get("by_role_pss_kb") or {}).items())},
        "peak_app_pss_kb": peak.get("app_pss_kb"),
        "peak_by_process_pss_kb": dict(sorted((peak.get("by_role_pss_kb") or {}).items())),
        # Only panes whose every sample was checked against the kiosk's log (report: `verified`). A
        # pane with no number (`error`) or unchecked samples (runs before 2026-11-13, such as s1v7,
        # whose switches the kiosk lost) isn't exported, so the site can't show it as a measurement.
        "panes": [{"id": p["id"], "pss_kb": p["pss_kb"], "tolerance_kb": p["pss_tolerance_kb"],
                   "measurable": p["pss_measurable"], "runs": min(p["samples_on"], p["samples_off"]),
                   # The frame's budget for the pane and whether its share is within it. budget_ok is
                   # None, and budget_measurable false, when the tolerance is too wide to tell (#186).
                   "budget_kb": (budget.get(p["id"]) or {}).get("budget_kb"),
                   "budget_ok": (budget.get(p["id"]) or {}).get("ok"),
                   "budget_measurable": (budget.get(p["id"]) or {}).get("measurable"),
                   **({"off_by": p["off_by"]} if p.get("off_by") else {})}
                  for p in ram.get("panes") or [] if not p.get("error") and p.get("verified") is True],
        "panes_waiting": [p["id"] for p in ram.get("panes_waiting") or []],
        "panes_not_switched": [p["id"] for p in ram.get("panes_not_switched") or []],
        "budget_ok": (ram.get("budget") or {}).get("ok"),
    }


def notes_summary(rep):
    """What's good and what holds it back, in words (#201; notes.py). Judged by the report's rubric,
    which `rubric` names: a site with another rubric shouldn't show it."""
    n = rep.get("notes") or N.notes(rep)
    return {k: n[k] for k in ("good", "limits", "causes", "text", "rubric")}


def measurements(rep, report_url="report.json"):
    """report.json (glassworks.perf/4) -> the summary: measurements and facts, no grade."""
    if not str(rep.get("schema", "")).startswith("glassworks.perf/"):
        raise Refused(f"not a perf report (schema {rep.get('schema')!r})")
    g = rep["grade"]
    ref = (rep.get("graded") or {}).get("reference") or {}
    tiers = ref.get("tiers") or {}
    seen = sorted({(tuple(css) if css else None, dpr) for s in tiers.values() for css, dpr in s.get("viewport") or []}, key=str)
    dens = ((rep["frame"].get("device") or {}).get("display") or {}).get("density")
    try:
        default_dpr = int(str(dens)) / 160
    except ValueError:
        default_dpr = None
    vp = g.get("viewport") or {}
    # Rubric 2: a frame without touch isn't graded on scrolling (grade_summary reads `touch`). Only said when false, so
    # a frame with touch keeps its summary byte for byte (test_summary).
    no_touch = {"touch": False} if (rep["frame"].get("device") or {}).get("touch") is False else {}
    # The engine, only when it isn't the web engine (Vitro, Settings → Labs): a Vitro result is never shown as the
    # frame's web one, and a web summary stays byte for byte.
    engine = (rep["frame"].get("device") or {}).get("engine")
    vitro = {"engine": engine} if engine and engine != "web" else {}
    return {
        "schema": SCHEMA,
        "test": G.NAME,
        # Clarity's version: 1 from 2026-11-14; None for a pre-release report (grade.ts: re-measure).
        "clarity": rep.get("clarity"),
        "protocol_version": rep.get("protocol_version"),
        "report_schema": rep["schema"],
        "measured_on": rep.get("measured_on"),
        "frame": {"id": rep["frame"]["id"], "name": rep["frame"]["name"]},
        **no_touch,
        **vitro,
        "app_version": (rep["frame"].get("app") or {}).get("version_name"),
        "viewport": {"css": list(seen[0][0]) if len(seen) == 1 and seen[0][0] else None,
                     "dpr": seen[0][1] if len(seen) == 1 else None, "default_dpr": default_dpr},
        "runs": {"attempted": rep.get("runs_attempted") or ref.get("runs", 0), "graded": ref.get("graded_runs", 0)},
        "pages": {t: page_summary(tiers[t]) for t in G.TIERS if t in tiers},
        "restart_ms": _graded(ref.get("start_ms")),
        # Measurement facts, not grade states: what is short, and why the numbers can't be compared.
        "gaps": list(g.get("provisional_reasons") or []),
        # Why a measurement is missing that can't change the letter (grade.py missing_settled_by): a fact, not a gap.
        # Only when there is one, so every other summary stays as it was.
        **({"unmeasured": list(g["unmeasured"])} if g.get("unmeasured") else {}),
        # Not comparable: not at the default zoom, or nothing was measured to grade (--skip-reference).
        "not_comparable": (vp.get("reason") if vp.get("state") == "refused"
                           else g.get("refused") if g.get("grade") is None else None),
        "report": report_url,
        "ram": ram_summary(rep.get("ram")),
        "apk": [{"file": x["file"], "abis": x["abis"], "bytes": x["bytes"]} for x in rep.get("apk") or []] or None,
        "notes": notes_summary(rep),
        # Protocol 7 (#202): how the cameras' WebRTC video reached the panel (lan-udp, lan-turn or adb),
        # who chose it, and whether heavy's camera result may be the test's link's (adb). Results over
        # different transports aren't comparable; grade_summary passes it on. None before protocol 7.
        "webrtc_transport": transport_summary(rep.get("webrtc_transport")),
        # The fake Home Assistant over the LAN (run.sh --ha-transport lan), not adb reverse: said, so the grade says how
        # it was taken. Only then, so every other summary stays as it was.
        **({"ha_transport": "lan"} if (rep.get("ha_transport") or {}).get("transport") == "lan" else {}),
    }


def transport_summary(t):
    if not t:
        return None
    return {"transport": t.get("transport"), "chosen_by": t.get("chosen_by"), "reason": t.get("reason"),
            "possibly_limited": bool(t.get("limited"))}


def summarise(rep, report_url="report.json", allow_provisional=False):
    """The summary, refused (Refused) when its letter is provisional (gaps, or missing measurements that could change
    it), there is none, or it isn't comparable, unless allow_provisional. `complete` says whether everything was
    measured: a summary missing only what can't change the letter is exported, with complete false."""
    out = measurements(rep, report_url)
    g = G.grade_summary(out)
    missing = g.get("missing") or []
    complete = not out["gaps"] and not out["not_comparable"] and not missing and g["status"] == "graded"
    final = not out["not_comparable"] and g["status"] == "graded" and not g["provisional"]
    if not final and not allow_provisional:
        why = out["not_comparable"] or "; ".join(out["gaps"] + (["not measured: " + ", ".join(missing)] if missing else [])) \
            or g.get("why", "")
        raise Refused(f"the measurements are incomplete or not comparable ({why}); "
                      "pass --allow-provisional to export them marked as such")
    out["complete"] = complete
    return out


# The file a frame's published grade lives in (frames/<id>/clarity/clarity.json), which glassworks.dev shows as the
# frame's grade.
PUBLISHED = "clarity.json"


def check_publishable(summary, path):
    """A summary of another engine than the web engine (Vitro, docs/perf.md "Vitro") is never written as a frame's
    published grade: the site shows clarity.json as the frame's web grade. Refused there; anywhere else it is written,
    with its `engine`."""
    engine = summary.get("engine")
    if engine and engine != "web" and os.path.basename(path) == PUBLISHED:
        raise Refused(f"this summary is {engine}'s, not the web engine's: a frame's {PUBLISHED} is its web grade, so it "
                      "isn't written there (write it under another name)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("report", help="a finished report.json")
    ap.add_argument("out", help="where to write the summary (JSON)")
    ap.add_argument("--report-url", default="report.json", help="relative path or URL of the full report")
    ap.add_argument("--allow-provisional", action="store_true", help="export incomplete measurements, marked")
    a = ap.parse_args()
    with open(a.report) as f:
        rep = json.load(f)
    try:
        out = summarise(rep, a.report_url, a.allow_provisional)
        check_publishable(out, a.out)
    except Refused as e:
        sys.exit(f"summary not written: {e}")
    with open(a.out, "w") as f:
        json.dump(out, f, indent=1)
        f.write("\n")
    print(f"{out['frame']['name']}: summary{'' if out['complete'] else ' (incomplete)'} -> {a.out}")


if __name__ == "__main__":
    main()
