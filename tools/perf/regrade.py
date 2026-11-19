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
else summary.json. As summary.py, it refuses incomplete or not comparable measurements unless --allow-provisional;
the report is written either way, and a summary it refuses is removed, so no summary is left with the earlier grade.

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
    raw = os.path.exists(os.path.join(run_dir, "device.json")) and any(
        f.startswith("refstart-") and f.endswith(".json") for f in os.listdir(run_dir))
    if raw:
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
    report_url = R.read_json(published).get("report", "report.json") if os.path.exists(published) else "report.json"
    was = (old or {}).get("grade") or {}
    now = rep["grade"]
    print(f"{a.run_dir}: graded again from its {how} under Clarity v{rep.get('clarity') or '?'} "
          f"(rubric {R.G.RUBRIC_VERSION}, protocol {R.G.PROTOCOL_VERSION})")
    print(f"  was: {was.get('grade') or 'no letter'}{' (provisional)' if was.get('provisional') else ''}"
          f" (Clarity v{(old or {}).get('clarity') or '?'})")
    print(f"  now: {now.get('grade') or 'no letter'}{' (provisional)' if now.get('provisional') else ''}")
    print(f"  {now['headline']}")
    try:
        summary = S.summarise(rep, report_url, a.allow_provisional)
    except S.Refused as e:
        summary = None
        print(f"  summary not written: {e}")
    if a.dry_run:
        return 0
    md = R.markdown(rep)
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
