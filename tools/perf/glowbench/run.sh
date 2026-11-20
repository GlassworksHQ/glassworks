#!/usr/bin/env bash
# GlowBench (test-build/glowbench-s1, never merged): measures the #691 edge glow (Spark) inside the S1 kiosk and
# prints its frame times. The kiosk must be a test build of this branch, installed and in front.
#
# Two modes:
#   loops (default)  sends the kiosk the dev.glassworks.debug.GLOW broadcast: N scripted runs of about 12 s each
#                    (entrance, listening with a fake voice, thinking, speaking, exit), repeatable.
#   --live N         real Assist runs: waits for N voice runs on the panel. With --say "<wake word>, <question>",
#                    this Mac speaks it (macOS `say`) for each run; without it, start them yourself.
#
# Touches: wakes the screen; sends the broadcast (loops mode); resets and reads `dumpsys gfxinfo sh.edm.s1kiosk`;
# reads logcat (never clears it); copies the result JSON off the device. Installs, uninstalls and changes nothing
# else: installing the test build and putting the original back are the root's steps.
#
#   tools/perf/glowbench/run.sh [--loops N] [--out DIR] -- <adb command...>
#   tools/perf/glowbench/run.sh --live N [--say "Okay Nabu, turn off the kitchen lights"] [--out DIR] -- <adb command...>
#   e.g. tools/perf/glowbench/run.sh --loops 5 -- adb -s "$(cat /tmp/s1-serial)"
set -euo pipefail

loops=3
live=0
say_text=""
out="glowbench-$(date +%Y%m%d-%H%M%S)"
while [[ $# -gt 0 ]]; do
  case $1 in
    --loops) loops=$2; shift 2 ;;
    --live) live=$2; shift 2 ;;
    --say) say_text=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --) shift; break ;;
    *) echo "unknown option $1 (the adb command goes after --)" >&2; exit 2 ;;
  esac
done
[[ $# -gt 0 ]] || { echo "usage: $0 [--loops N | --live N [--say TEXT]] [--out DIR] -- <adb command...>" >&2; exit 2; }
ADB=("$@")
here=$(cd "$(dirname "$0")" && pwd)
pkg=sh.edm.s1kiosk
mkdir -p "$out"

sh_() { "${ADB[@]}" shell "$@" | tr -d '\r'; }
gb_log() { sh_ logcat -d -v brief -T "$1" -s GlowBench:I GlowBench:W || true; }

version=$(sh_ dumpsys package $pkg | grep -m1 versionName || true)
[[ -n $version ]] || { echo "$pkg is not installed" >&2; exit 1; }
echo "kiosk: ${version// /}"
resumed=$(sh_ dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity' || true)
[[ $resumed == *$pkg* ]] || { echo "the kiosk is not in front: $resumed" >&2; exit 1; }

sh_ input keyevent KEYCODE_WAKEUP >/dev/null
sh_ dumpsys gfxinfo $pkg reset >/dev/null
since=$(sh_ date '+%m-%d %H:%M:%S.000')

if [[ $live -gt 0 ]]; then
  # ----------------------------------------------------------------- real Assist runs
  name=live
  for i in $(seq 1 "$live"); do
    sh_ input keyevent KEYCODE_WAKEUP >/dev/null
    if [[ -n $say_text ]]; then
      echo "== run $i: saying \"$say_text\""
      say "$say_text"
    else
      echo "== run $i: start a voice run on the panel (wake word and a question)"
    fi
    deadline=$((SECONDS + 90)); json=""
    while [[ $SECONDS -lt $deadline ]]; do
      sleep 2
      log=$(gb_log "$since")
      done_lines=$(printf '%s\n' "$log" | grep -c "live run .* done json=" || true)
      if [[ $done_lines -ge $i ]]; then json=$(printf '%s\n' "$log" | sed -n 's/.*live run .* done json=//p' | tail -1); break; fi
    done
    [[ -n $json ]] || { echo "run $i: no live run finished within 90 s (did the wake word trigger?)" >&2; printf '%s\n' "$log" | tail -5 >&2; exit 1; }
    sleep 3 # let the panel settle before the next wake word
  done
else
  # ----------------------------------------------------------------- scripted loops
  name=loops
  echo "== spark: $loops loops (about $((loops * 13)) s)"
  sh_ am broadcast -a dev.glassworks.debug.GLOW --es variant spark --ei loops "$loops" >/dev/null
  deadline=$((SECONDS + loops * 13 + 40)); started=0; json=""
  while [[ $SECONDS -lt $deadline ]]; do
    sleep 3
    log=$(gb_log "$since")
    [[ $log == *"start mode=bench"* ]] && started=1
    if [[ $log == *" done json="* ]]; then json=$(printf '%s\n' "$log" | sed -n 's/.* done json=//p' | tail -1); break; fi
    if [[ $started == 0 && $SECONDS -gt $((deadline - loops * 13 - 25)) ]]; then
      echo "no start in logcat: is this a test build of test-build/glowbench-s1, with the kiosk in front?" >&2
      printf '%s\n' "$log" >&2; exit 1
    fi
  done
  [[ -n $json ]] || { echo "timed out" >&2; exit 1; }
fi

printf '%s\n' "$log" > "$out/$name.log"
"${ADB[@]}" exec-out cat "$json" > "$out/$name.json"
sh_ dumpsys gfxinfo $pkg > "$out/$name-gfxinfo.txt"
echo "   $out/$name.json"
python3 "$here/report.py" "$out"
