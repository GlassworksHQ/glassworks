#!/usr/bin/env bash
# GlowBench (test-build/glowbench-s1, never merged), round 3: measures the #691 edge glow (Spark, drawn by
# RenderThread) and today's voice orb inside the S1 kiosk, and prints their frame times. The kiosk must be a test
# build of this branch, installed and in front.
#
# Modes:
#   loops (default)  the dev.glassworks.debug.GLOW broadcast: N scripted runs (entrance, listening with a fake
#                    voice, thinking, speaking, exit), for each look in --look.
#   --live N         real Assist runs: N voice runs per look. With --say "<wake word>, <question>" this Mac speaks it
#                    (macOS `say`) for each run; without it, start them yourself.
# Options: --look glow|orb|both (default both: the orb first, as the baseline), --fps 60|30 (the cap for the glow's
# voice-level updates; RenderThread animations always run at the display's rate), --out DIR.
#
# Touches: wakes the screen; sends the broadcast (it also sets the look and cap for live runs); resets and reads
# `dumpsys gfxinfo sh.edm.s1kiosk`; reads logcat (never clears it); copies the result JSON off the device. Installs,
# uninstalls and changes nothing else: installing the test build and putting the original back are the root's steps.
#
#   tools/perf/glowbench/run.sh [--loops N] [--look both] [--fps 60] [--out DIR] -- <adb command...>
#   tools/perf/glowbench/run.sh --live N [--say "<wake word>, what time is it"] [--look both] -- <adb command...>
#   e.g. tools/perf/glowbench/run.sh --loops 5 -- adb -s "$(cat /tmp/s1-serial)"
set -euo pipefail

loops=3
live=0
say_text=""
looks="orb glow"
fps=60
out="glowbench-$(date +%Y%m%d-%H%M%S)"
while [[ $# -gt 0 ]]; do
  case $1 in
    --loops) loops=$2; shift 2 ;;
    --live) live=$2; shift 2 ;;
    --say) say_text=$2; shift 2 ;;
    --look) case $2 in both) looks="orb glow" ;; glow|orb) looks=$2 ;; *) echo "--look glow|orb|both" >&2; exit 2 ;; esac; shift 2 ;;
    --fps) fps=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --) shift; break ;;
    *) echo "unknown option $1 (the adb command goes after --)" >&2; exit 2 ;;
  esac
done
[[ $# -gt 0 ]] || { echo "usage: $0 [--loops N | --live N [--say TEXT]] [--look glow|orb|both] [--fps 60|30] [--out DIR] -- <adb command...>" >&2; exit 2; }
ADB=("$@")
here=$(cd "$(dirname "$0")" && pwd)
pkg=sh.edm.s1kiosk
mkdir -p "$out"

# adb shell joins its arguments with spaces and the device's shell splits them again: pass one quoted string when an
# argument has spaces of its own.
sh_() { "${ADB[@]}" shell "$@" | tr -d '\r'; }
now_on_device() { sh_ "date '+%m-%d %H:%M:%S.000'"; }
gb_log() { sh_ "logcat -d -v brief -T '$1' -s GlowBench:I GlowBench:W" || true; }

version=$(sh_ dumpsys package $pkg | grep -m1 versionName || true)
[[ -n $version ]] || { echo "$pkg is not installed" >&2; exit 1; }
echo "kiosk: $(echo "$version" | tr -d ' ')"
# Android 10 indents these lines; any of them naming the kiosk is enough.
if ! sh_ dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | grep -q "$pkg"; then
  echo "the kiosk is not in front:" >&2
  sh_ dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' >&2 || true
  exit 1
fi

for look in $looks; do
  sh_ input keyevent KEYCODE_WAKEUP >/dev/null
  sh_ dumpsys gfxinfo $pkg reset >/dev/null
  since=$(now_on_device)
  name="$look-$([[ $live -gt 0 ]] && echo live || echo loops)"
  if [[ $live -gt 0 ]]; then
    # ----------------------------------------------------------------- real Assist runs
    sh_ am broadcast -a dev.glassworks.debug.GLOW --es look "$look" --ei fps "$fps" --ei loops 0 >/dev/null
    for i in $(seq 1 "$live"); do
      sh_ input keyevent KEYCODE_WAKEUP >/dev/null
      if [[ -n $say_text ]]; then echo "== $look, run $i: saying \"$say_text\""; say "$say_text"
      else echo "== $look, run $i: start a voice run on the panel (wake word and a question)"; fi
      deadline=$((SECONDS + 90)); json=""
      while [[ $SECONDS -lt $deadline ]]; do
        sleep 2
        log=$(gb_log "$since")
        done_lines=$(printf '%s\n' "$log" | grep -c "live run .* done json=" || true)
        if [[ $done_lines -ge $i ]]; then json=$(printf '%s\n' "$log" | sed -n 's/.*live run .* done json=//p' | tail -1); break; fi
      done
      [[ -n $json ]] || { echo "run $i: no live run finished within 90 s (did the wake word trigger?)" >&2; printf '%s\n' "${log:-}" | tail -5 >&2; exit 1; }
      sleep 3 # let the panel settle before the next wake word
    done
  else
    # ----------------------------------------------------------------- scripted loops
    secs=$(( loops * 13 ))
    echo "== $look: $loops loops (about $secs s)"
    sh_ am broadcast -a dev.glassworks.debug.GLOW --es look "$look" --ei fps "$fps" --ei loops "$loops" >/dev/null
    deadline=$((SECONDS + secs + 40)); started=0; json=""
    while [[ $SECONDS -lt $deadline ]]; do
      sleep 3
      log=$(gb_log "$since")
      [[ $log == *"start mode=loops"* ]] && started=1
      if [[ $log == *" done json="* ]]; then json=$(printf '%s\n' "$log" | sed -n 's/.* done json=//p' | tail -1); break; fi
      if [[ $started == 0 && $SECONDS -gt $((deadline - secs - 25)) ]]; then
        echo "no start in logcat: is this a test build of test-build/glowbench-s1, with the kiosk in front?" >&2
        printf '%s\n' "$log" >&2; exit 1
      fi
    done
    [[ -n $json ]] || { echo "$look: timed out" >&2; exit 1; }
  fi
  printf '%s\n' "$log" > "$out/$name.log"
  "${ADB[@]}" exec-out cat "$json" > "$out/$name.json"
  sh_ dumpsys gfxinfo $pkg > "$out/$name-gfxinfo.txt"
  echo "   $out/$name.json"
done

python3 "$here/report.py" "$out"
