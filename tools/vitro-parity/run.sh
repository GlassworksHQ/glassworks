#!/usr/bin/env bash
# Vitro parity harness (#793): one command for the report of how Vitro's render of each corpus card compares with
# Home Assistant's own.
#
# What it touches:
#   - Without --refresh: nothing outside the repo's build output. Runs VitroParityHarnessTest (Robolectric) against
#     the committed references; needs no device and no Home Assistant. This is what CI runs.
#   - With --refresh: the corpus's refs/ and assets/ folders, which capture.mjs rewrites. The committed corpus is
#     captured from the fake Home Assistant (tools/perf/fakeha.py + fakehome.py on 127.0.0.1, fed each case's own
#     fixture): no real HA, no credentials. Needs python3 with jinja2, Node 22+ and Chromium or Google Chrome; the
#     first run downloads HA's pinned frontend and the pinned community cards into ~/.cache/glassworks-perf.
#     Never touches a panel; no adb.
#   - --local: a local corpus of real dashboards (default ~/.t3/glassworks-m1/vitro-parity/harness-local), which never
#     enters the repo, captured from the real Home Assistant in the credentials file (VITRO_PARITY_HA, default
#     ~/.t3/glassworks-m1/vitro-parity/ha.json): writes ONLY its `vitro-harness` dashboard; reads states, themes and
#     config; renders templates.
#
#   tools/vitro-parity/run.sh                          compare with the committed references (CI-safe)
#   tools/vitro-parity/run.sh --refresh                capture new references from the fake HA, then compare
#   tools/vitro-parity/run.sh --local [--refresh]      the local corpus of real dashboards
#   options: --frames s1,tablet  --cases tile-light,clock  --update-baseline  --out <dir>
#
# The report: engines/engine-vitro/build/reports/vitro-parity/index.html (and report.md, report.json), or --out.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/tools/vitro-parity"
LOCAL_DEFAULT="$HOME/.t3/glassworks-m1/vitro-parity/harness-local"

refresh=0 local_dir="" frames="" cases="" update=0 out=""
# An option that takes a value must get a non-empty one: `--cases ""` is an error, not "every case".
need_value() {
  if [ $# -lt 2 ] || [ -z "$2" ] || [ "${2#--}" != "$2" ]; then echo "$1 needs a value" >&2; exit 2; fi
}
while [ $# -gt 0 ]; do
  case "$1" in
    --refresh) refresh=1 ;;
    --local)
      # Bare --local is the default local corpus; an explicitly empty path is an error, not the committed corpus.
      if [ $# -gt 1 ] && [ -z "$2" ]; then echo "--local needs a path (or none, for $LOCAL_DEFAULT)" >&2; exit 2; fi
      if [ $# -gt 1 ] && [ "${2#--}" = "$2" ]; then local_dir="$2"; shift; else local_dir="$LOCAL_DEFAULT"; fi ;;
    --frames) need_value "$@"; frames="$2"; shift ;;
    --cases) need_value "$@"; cases="$2"; shift ;;
    --update-baseline) update=1 ;;
    --out) need_value "$@"; out="$2"; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

gradle_props=()
node_args=()
[ -n "$frames" ] && gradle_props+=("-PvitroParityFrames=$frames") && node_args+=(--frames "$frames")
[ -n "$cases" ] && gradle_props+=("-PvitroParityCases=$cases") && node_args+=(--cases "$cases")
if [ -n "$out" ]; then
  # Gradle runs the test in engines/engine-vitro: a relative --out is the caller's, made absolute here.
  case "$out" in /*) ;; *) out="$PWD/$out" ;; esac
  gradle_props+=("-PvitroParityOut=$out")
fi
if [ -n "$local_dir" ]; then
  # Gradle runs the test in engines/engine-vitro: a relative --local is the caller's, made absolute here.
  case "$local_dir" in /*) ;; *) local_dir="$PWD/$local_dir" ;; esac
  [ -d "$local_dir/corpus" ] || { echo "no corpus at $local_dir/corpus (see tools/vitro-parity/README.md)" >&2; exit 2; }
  gradle_props+=("-PvitroParityLocal=$local_dir")
  # A local run selects the local corpus's case ids. A committed case with the same id as a local one runs too: the
  # harness applies the id filter to both corpora.
  [ -z "$cases" ] && gradle_props+=("-PvitroParityCases=$(cd "$local_dir/corpus" && ls ./*.json | sed 's#^\./##; s#\.json$##' | paste -sd, -)")
  node_args+=(--root "$local_dir")
fi

harness() {
  (cd "$ROOT" && ./gradlew :engine-vitro:testDebugUnitTest --tests '*VitroParityHarnessTest*' ${gradle_props[@]+"${gradle_props[@]}"} "$@")
}

if [ "$refresh" = 1 ]; then
  [ -d "$HERE/node_modules/playwright-core" ] || (cd "$HERE" && npm ci --no-audit --no-fund)
  echo "== 1/4 save the vitro-harness dashboard (real HA only: the fake serves it from the corpus)"
  node "$HERE/capture.mjs" prepare ${node_args[@]+"${node_args[@]}"}
  echo "== 2/4 Vitro: which templates do the cases ask Home Assistant for"
  harness -PvitroParityCollect=true -q
  echo "== 3/4 Home Assistant: render every case on every frame"
  node "$HERE/capture.mjs" capture ${node_args[@]+"${node_args[@]}"}
fi

echo "== compare Vitro with Home Assistant"
report="${out:-$ROOT/engines/engine-vitro/build/reports/vitro-parity}/index.html"
props=()
[ "$update" = 1 ] && props+=(-PvitroParityUpdateBaseline=true)
status=0
harness ${props[@]+"${props[@]}"} || status=$?
echo "report: $report"
exit $status
