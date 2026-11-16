#!/usr/bin/env python3
"""The API 23 report (#198, MIT): every call in the Glassworks modules that needs more than Android 6 (API 23), the
floor of the legacy build. The checklist for making the app run on Android 6-7, and a CI guard against new ones.

It runs lint's NewApi check on every Android library module with minSdk lowered to 23 for this run only
(tools/legacy/api23-lint.gradle, a Gradle init script: no build file changes), then reads lint's XML reports.
Touches only build outputs.

  python3 tools/legacy/api23-report.py                         # the table, Markdown, on stdout
  python3 tools/legacy/api23-report.py --check BASELINE        # fail on calls BASELINE doesn't list (CI)
  python3 tools/legacy/api23-report.py --write-baseline BASELINE
  python3 tools/legacy/api23-report.py --no-gradle ...         # read the reports a run already left

A baseline line is `<package path>|<API>`, e.g. `sh/edm/s1kiosk/KioskService.kt|android.content.Context#startForegroundService`:
no module and no line number, so code that moves between modules, or within a file, stays listed. Calls that core
library desugaring covers are in the table but never fail the check: the legacy build enables desugaring.
"""
import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
INIT = os.path.join("tools", "legacy", "api23-lint.gradle")
SKIP = {":engine-gecko"}


def read(path):
    with open(path) as f:
        return f.read()  # the current engine (API 26); its sources are linted inside :engine-gecko-legacy


def modules():
    """(gradle path, folder) of every Android library in settings.gradle.kts, but SKIP."""
    text = read(os.path.join(ROOT, "settings.gradle.kts"))
    found = []
    for path, folder in re.findall(r'project\("(:[^"]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)', text):
        build = os.path.join(ROOT, folder, "build.gradle.kts")
        if path in SKIP or not os.path.isfile(build):
            continue
        if 'id("com.android.library")' in read(build):
            found.append((path, folder))
    if len(found) < 10:
        sys.exit(f"api23-report: found only {len(found)} Android library modules in settings.gradle.kts")
    return found


def run_lint(mods):
    for _, folder in mods:  # a stale report would read as this run's
        report = os.path.join(ROOT, folder, "build", "reports", "lint-results-debug.xml")
        if os.path.exists(report):
            os.remove(report)
    cmd = [os.path.join(ROOT, "gradlew"), "--init-script", INIT, "--continue"] + [f"{p}:lintDebug" for p, _ in mods]
    print("api23-report: " + " ".join(cmd[:4]) + f" … ({len(mods)} modules)", file=sys.stderr)
    r = subprocess.run(cmd, cwd=ROOT, stdout=sys.stderr)  # the table owns stdout
    if r.returncode != 0:
        sys.exit(f"api23-report: gradle failed ({r.returncode})")


def package_path(file):
    """The file's path inside its source root: sh/edm/s1kiosk/KioskService.kt."""
    rel = os.path.relpath(file, ROOT).replace(os.sep, "/")
    m = re.search(r"/src/[^/]+/(?:java|kotlin)/(.+)$", rel)
    return m.group(1) if m else rel


def findings(mods):
    """Every NewApi issue: dicts with module, file, line, api, level, desugar."""
    out = []
    for path, folder in mods:
        report = os.path.join(ROOT, folder, "build", "reports", "lint-results-debug.xml")
        if not os.path.isfile(report):
            sys.exit(f"api23-report: no lint report for {path} ({os.path.relpath(report, ROOT)})")
        for issue in ET.parse(report).getroot().iter("issue"):
            if issue.get("id") == "LintError":  # a detector failed: the file it was on went unchecked
                loc = issue.find("location")
                where = os.path.relpath(loc.get("file"), ROOT) if loc is not None else "?"
                sys.exit(f"api23-report: lint failed on {path} ({where}): {issue.get('message', '')[:300]}")
            if issue.get("id") != "NewApi":
                continue
            msg = issue.get("message", "")
            loc = issue.find("location")
            api = re.search(r"`([^`]+)`", msg)
            level = re.search(r"API level (\d+)", msg)
            out.append({
                "module": path,
                "file": package_path(loc.get("file")) if loc is not None else "?",
                "line": int(loc.get("line", 0)) if loc is not None else 0,
                "api": api.group(1) if api else msg,
                "level": int(level.group(1)) if level else 0,
                "desugar": "desugaring" in msg,
            })
    return sorted(out, key=lambda f: (f["module"], f["file"], f["line"], f["api"]))


def key(f):
    return f"{f['file']}|{f['api']}"


def markdown(found, mods):
    lines = ["| Module | File | Line | Call | API | Desugared |", "|---|---|---|---|---|---|"]
    for f in found:
        lines.append(f"| `{f['module']}` | `{f['file']}` | {f['line']} | `{f['api']}` | {f['level']} | {'yes' if f['desugar'] else ''} |")
    counted = {}
    for f in found:
        c = counted.setdefault(f["module"], [0, 0])
        c[1 if f["desugar"] else 0] += 1
    summary = ["| Module | Calls above API 23 | Of them desugared |", "|---|---|---|"]
    for path, _ in mods:
        n, d = counted.get(path, [0, 0])
        summary.append(f"| `{path}` | {n + d} | {d} |")
    return "\n".join(summary) + "\n\n" + "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", metavar="BASELINE", help="fail on calls the baseline doesn't list (desugared ones aside)")
    ap.add_argument("--write-baseline", metavar="BASELINE", help="write every call found as the baseline")
    ap.add_argument("--merge", metavar="BASELINE", help="with --write-baseline: keep this file's lines too")
    ap.add_argument("--no-gradle", action="store_true", help="read the reports a run already left")
    a = ap.parse_args()

    mods = modules()
    if not a.no_gradle:
        run_lint(mods)
    found = findings(mods)

    if a.write_baseline:
        keys = {key(f) for f in found}
        if a.merge and os.path.isfile(a.merge):
            keys |= {l.strip() for l in read(a.merge).splitlines() if l.strip() and not l.startswith("#")}
        with open(a.write_baseline, "w") as out:
            out.write("# API 24+ calls the Glassworks modules already make (tools/legacy/api23-report.py). One line\n"
                      "# per <package path>|<API>. CI fails on a call not listed here; add one only on purpose. Lint\n"
                      "# can't see a Platform check, so a call behind one stays listed: remove a line only when lint\n"
                      "# stops reporting the call (the call is gone, or behind an SDK_INT check lint understands).\n"
                      "# The key is per file and call, not per line, on purpose: lines move with every edit, and a listed call\n"
                      "# that a file makes again is already known there. A new call, or a known one in a new file, fails.\n")
            out.writelines(k + "\n" for k in sorted(keys))
        print(f"api23-report: wrote {len(keys)} lines to {a.write_baseline}", file=sys.stderr)
        return

    if a.check:
        known = {l.strip() for l in read(a.check).splitlines() if l.strip() and not l.startswith("#")}
        new = [f for f in found if not f["desugar"] and key(f) not in known]
        gone = sorted(known - {key(f) for f in found})
        if gone:
            print(f"api23-report: {len(gone)} baseline line(s) no longer found (fixed or moved); "
                  f"you can remove them from {a.check}:", file=sys.stderr)
            for g in gone:
                print(f"  {g}", file=sys.stderr)
        if new:
            print(f"api23-report: {len(new)} new call(s) above API 23, which the legacy build (Android 6-7, #198) "
                  "can't make. Put each behind a Platform query with an API 23 fallback (pane-api's "
                  "dev.glassworks.platform), or, if it must stay, add its line to " + a.check + ":", file=sys.stderr)
            for f in new:
                print(f"  {f['module']} {f['file']}:{f['line']}  {f['api']} (API {f['level']})   line: {key(f)}", file=sys.stderr)
            sys.exit(1)
        print(f"api23-report: no new calls above API 23 ({len(found)} known, {sum(f['desugar'] for f in found)} desugared)",
              file=sys.stderr)
        return

    sys.stdout.write(markdown(found, mods))


if __name__ == "__main__":
    main()
