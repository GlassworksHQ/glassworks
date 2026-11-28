#!/usr/bin/env python3
"""Grade a Clarity run again under the current rubric (MIT, Clarity v1). Touches no device.

A run's report says what its rubric made of it on the day. When the rubric changes, or the version starts again
(Clarity v1 for Glassworks 1.0.0, 2026-11-19), this rewrites the run's report.json, report.md and summary under the
rules now in tools/perf/page/rubric.json:

- From the raw files, when the run directory has them (device.json and refstart-*.json, as run.sh leaves them):
  report.py's build, as `report.py <dir>` does. The APK sizes and an --app-version label of the report.json there
  are kept (they came from run.sh's arguments, not from the raw files).
- Else from the report.json alone (a published report, whose raw files aren't kept): report.regrade_report, which
  grades the stored graded runs again. What was measured stays; the grade, the version and the notes are new.

Either way the earlier Clarity v3's pages (protocol 3) read as v1's: they measured exactly what v1 measures. Pages
of other versions, or the first v1's (2026-11-14), come out "re-measure".

Then the summary: clarity.json when the directory has one (a frame's published folder: its report URL is kept),
else summary.json. As summary.py, it refuses incomplete or not comparable measurements unless --allow-provisional, or
the summary there was itself published incomplete (complete: false);
in a run folder the report is written either way and a summary it refuses is removed, so none is left with the
earlier grade; a published folder (clarity.json) is left as it was, report and summary together, and it says so.
From the raw files, it stops, writing nothing, unless they are refstart-1 to refstart-N, each the run its name says
(its own "run"), with every graded run the report.json there names (reports since Clarity v1 do) and no fewer runs,
and still hold the other raw files that report.json was built from (netcheck.json, transport.json, plan.json).

  tools/perf/regrade.py <run-dir> [--allow-provisional] [--dry-run]

Prints the new headline and what changed in the letter. --dry-run writes nothing.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import report as R  # noqa: E402
import summary as S  # noqa: E402


def regrade(run_dir):
    """(new report, how): from the raw files when the directory has them, else from its report.json."""
    old_path = os.path.join(run_dir, "report.json")
    old = R.read_json(old_path) if os.path.exists(old_path) else None
    refstarts = [f for f in os.listdir(run_dir) if f.startswith("refstart-") and f.endswith(".json")]
    raw = os.path.exists(os.path.join(run_dir, "device.json")) and bool(refstarts)
    if raw:
        # Each raw file says which run it is ("run"): it must match its name, once each, or the files aren't the
        # runs they claim to be (a copy, a rename). Then there must be at least as many as the report graded.
        bad = []
        for f in sorted(refstarts):
            want = f[len("refstart-"):-len(".json")]
            got = R.read_json(os.path.join(run_dir, f)).get("run")
            if str(got) != want:
                bad.append(f"{f} says run {got}")
        if bad:
            sys.exit(f"{run_dir}: the raw files aren't the runs their names say ({'; '.join(bad)}). Nothing written.")
        # A run folder holds refstart-1 to refstart-N (a failed run leaves its file too): a gap means some are gone.
        numbers = sorted(int(f[len("refstart-"):-len(".json")]) for f in refstarts)
        if numbers != list(range(1, len(numbers) + 1)):
            sys.exit(f"{run_dir}: the raw files are runs {numbers}, not 1 to {len(numbers)}: some are missing. Nothing "
                     "written. Restore them, or move the raw files aside to grade it again from report.json alone.")
        stored_runs = (((old or {}).get("graded") or {}).get("reference") or {}).get("per_run") or []
        named = {r.get("run") for r in stored_runs if r.get("run") is not None}
        if named - set(numbers):
            # Reports since Clarity v1 name each graded run: the raw files must hold every one.
            sys.exit(f"{run_dir}: report.json graded runs {sorted(named)}, the raw files hold {numbers}. Nothing written.")
        stored = len(stored_runs)
        if len(refstarts) < stored:
            # Some graded runs' raw files are gone: a rebuild would drop them and overwrite the stored report.
            sys.exit(f"{run_dir}: report.json has {stored} graded runs but only {len(refstarts)} refstart-*.json: "
                     "a rebuild from the raw files would drop the others. Nothing written. Restore the files, or move "
                     "the raw files aside to grade it again from report.json alone.")
        # What the stored report took from other raw files must still be there: a rebuild without them would put
        # "not measured" in place of what was (the network's limit, the camera video's path, the runs attempted).
        lost = [f"{name} (report.json has {key})" for key, name in
                (("test_network", "netcheck.json"), ("webrtc_transport", "transport.json"), ("ha_transport", "ha_transport.json"))
                if (old or {}).get(key) and not os.path.exists(os.path.join(run_dir, name))]
        # Without plan.json the runs attempted are the files there: only fewer than the report counted is a loss.
        if ((old or {}).get("runs_attempted") or 0) > len(refstarts) and not os.path.exists(os.path.join(run_dir, "plan.json")):
            lost.append(f"plan.json (report.json has runs_attempted {old['runs_attempted']})")
        if lost:
            sys.exit(f"{run_dir}: raw files the report.json was built from are gone: {', '.join(lost)}. Nothing written. "
                     "Restore them, or move the raw files aside to grade it again from report.json alone.")
        rep = R.build(run_dir)
        if old:
            rep["apk"] = old.get("apk", rep.get("apk"))
            app = (old.get("frame") or {}).get("app") or {}
            if app.get("version_label") == "given":
                R.label_app_version(rep, app.get("version_name"))
        return rep, old, "raw files"
    if old is None:
        sys.exit(f"{run_dir}: no raw files (device.json, refstart-*.json) and no report.json to grade again")
    return R.regrade_report(old), old, "report.json"


def write_json(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=1)
        f.write("\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("run_dir")
    ap.add_argument("--allow-provisional", action="store_true", help="write the summary even when it's incomplete, marked")
    ap.add_argument("--dry-run", action="store_true", help="say what the grade would be; write nothing")
    a = ap.parse_args()
    rep, old, how = regrade(a.run_dir)
    published = os.path.join(a.run_dir, "clarity.json")
    summary_path = published if os.path.exists(published) else os.path.join(a.run_dir, "summary.json")
    earlier = R.read_json(summary_path) if os.path.exists(summary_path) else {}
    report_url = earlier.get("report", "report.json") if os.path.exists(published) else "report.json"
    # A summary published incomplete (complete: false, as summary.py --allow-provisional writes it) stays published
    # that way: graded again, marked, rather than removed.
    allow = a.allow_provisional or earlier.get("complete") is False
    was = (old or {}).get("grade") or {}
    now = rep["grade"]
    print(f"{a.run_dir}: graded again from its {how} under Clarity v{rep.get('clarity') or '?'} "
          f"(rubric {R.G.RUBRIC_VERSION}, protocol {R.G.PROTOCOL_VERSION})")
    print(f"  was: {was.get('grade') or 'no letter'}{' (provisional)' if was.get('provisional') else ''}"
          f" (Clarity v{(old or {}).get('clarity') or '?'})")
    print(f"  now: {now.get('grade') or 'no letter'}{' (provisional)' if now.get('provisional') else ''}")
    print(f"  {now['headline']}")
    try:
        summary = S.summarise(rep, report_url, allow)
        S.check_publishable(summary, summary_path)
    except S.Refused as e:
        summary = None
        print(f"  summary not written: {e}")
    if a.dry_run:
        return 0
    md = R.markdown(rep)
    if summary is None and os.path.exists(published):
        # A published folder changes all at once or not at all: its report and clarity.json (and its report URL) stay
        # as they were, rather than a new report beside no summary (CodeRabbit on #656).
        print("  nothing written: a published folder keeps its report and clarity.json together. "
              "Pass --allow-provisional to publish the new grade marked incomplete.")
        return 1
    # The report and its summary must agree: a summary refused now goes, rather than stay with the old grade.
    if summary is None and os.path.exists(summary_path):
        os.remove(summary_path)
        print(f"  removed the old {os.path.basename(summary_path)}: it had the earlier grade")
    write_json(os.path.join(a.run_dir, "report.json"), rep)
    with open(os.path.join(a.run_dir, "report.md"), "w", encoding="utf-8") as f:
        f.write(md)
    if summary is not None:
        write_json(summary_path, summary)
        print(f"  wrote report.json, report.md and {os.path.basename(summary_path)}")
        return 0
    print("  wrote report.json and report.md")
    return 1


if __name__ == "__main__":
    sys.exit(main())
