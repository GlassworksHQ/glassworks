#!/usr/bin/env bash
# GlowBench (test-build/glowbench-s1, never merged): measures the #691 edge glow (Spark, drawn by RenderThread) and
# today's voice orb inside a Glassworks app, and prints their frame times. Works with the S1 app (sh.edm.s1kiosk), the
# generic app (dev.glassworks.generic_android) and its legacy build (dev.glassworks.generic_android.legacy). The app
# must be a test build of this branch, installed and in front.
#
# Modes:
#   loops (default)  the dev.glassworks.debug.GLOW broadcast: N scripted runs (entrance, listening with a fake
#                    voice, thinking, speaking, exit), for each look in --look.
#   --live N         real Assist runs: N voice runs per look. With --say "<wake word>, <question>" this Mac speaks it
#                    (macOS `say`) for each run; without it, start them yourself.
# Options: --package ID (default: whichever Glassworks app is installed; asks if more than one is), --look
# glow|orb|both (default both: the orb first, as the baseline), --gap MS (idle time between scripted runs, default 700;
# a short gap tests whether late entrance frames come from the clocks ramping down), --out DIR.
#
# Touches: wakes the screen; sends the broadcast (it also sets the look for live runs); resets and reads
# `dumpsys gfxinfo` for the app; reads logcat (never clears it); copies the result JSON off the device. Installs,
# uninstalls and changes nothing else: installing the test build and putting the original back are the root's steps.
#
#   tools/perf/glowbench/run.sh [--package ID] [--loops N] [--look both] [--gap 700] [--out DIR] -- <adb command...>
#   tools/perf/glowbench/run.sh [--package ID] --live N [--say "<wake word>, what time is it"] -- <adb command...>
set -euo pipefail

pkg=""
loops=3
live=0
say_text=""
looks="orb glow"
gap=700
out="glowbench-$(date +%Y%m%d-%H%M%S)"
while [[ $# -gt 0 ]]; do
  case $1 in
    --package) pkg=$2; shift 2 ;;
    --loops) loops=$2; shift 2 ;;
    --live) live=$2; shift 2 ;;
    --say) say_text=$2; shift 2 ;;
    --look) case $2 in both) looks="orb glow" ;; glow|orb) looks=$2 ;; *) echo "--look glow|orb|both" >&2; exit 2 ;; esac; shift 2 ;;
    --gap) gap=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --) shift; break ;;
    *) echo "unknown option $1 (the adb command goes after --)" >&2; exit 2 ;;
  esac
done
[[ $# -gt 0 ]] || { echo "usage: $0 [--package ID] [--loops N | --live N [--say TEXT]] [--look glow|orb|both] [--gap MS] [--out DIR] -- <adb command...>" >&2; exit 2; }
ADB=("$@")
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"

# adb shell joins its arguments with spaces and the device's shell splits them again: pass one quoted string when an
# argument has spaces of its own.
sh_() { "${ADB[@]}" shell "$@" | tr -d '\r'; }
# This run's lines only: every line GlowBench logs for a broadcast carries its tag. No `logcat -T`: its time format
# varies between Android versions, and a missed start line made the check give up on a run that had started.
gb_log() { sh_ "logcat -d -v brief -s GlowBench:I GlowBench:W" | grep -F "tag=$1" || true; }

if [[ -z $pkg ]]; then
  installed=$(sh_ pm list packages | sed -n 's/^package://p' | grep -xE 'sh\.edm\.s1kiosk|dev\.glassworks\.generic_android(\.legacy)?' || true)
  case $(printf '%s\n' "$installed" | grep -c . || true) in
    1) pkg=$installed ;;
    0) echo "no Glassworks app installed (sh.edm.s1kiosk, dev.glassworks.generic_android[.legacy])" >&2; exit 1 ;;
    *) echo "more than one Glassworks app is installed; pick one with --package:" >&2; printf '  %s\n' $installed >&2; exit 2 ;;
  esac
fi
version=$(sh_ dumpsys package "$pkg" | grep -m1 -E 'versionName|versionCode' || true)
[[ -n $version ]] || { echo "$pkg is not installed" >&2; exit 1; }
echo "app: $pkg $(sh_ dumpsys package "$pkg" | grep -E 'versionName|versionCode' | tr -s ' ' | tr '\n' ' ')"
# mResumedActivity, ResumedActivity (Android 10) or topResumedActivity: any of them naming the app is enough.
if ! sh_ dumpsys activity activities | grep -E 'ResumedActivity' | grep -q "$pkg/"; then
  echo "$pkg is not in front:" >&2
  sh_ dumpsys activity activities | grep -E 'ResumedActivity' >&2 || true
  exit 1
fi

for look in $looks; do
  tag="gb$$-$look-$RANDOM"
  sh_ input keyevent KEYCODE_WAKEUP >/dev/null
  sh_ dumpsys gfxinfo "$pkg" reset >/dev/null
  name="$look-$([[ $live -gt 0 ]] && echo live || echo loops)"
  if [[ $live -gt 0 ]]; then
    # ----------------------------------------------------------------- real Assist runs
    sh_ am broadcast -a dev.glassworks.debug.GLOW --es look "$look" --es tag "$tag" --ei loops 0 >/dev/null
    for i in $(seq 1 "$live"); do
      sh_ input keyevent KEYCODE_WAKEUP >/dev/null
      if [[ -n $say_text ]]; then echo "== $look, run $i: saying \"$say_text\""; say "$say_text"
      else echo "== $look, run $i: start a voice run on the panel (wake word and a question)"; fi
      deadline=$((SECONDS + 90)); json=""
      while [[ $SECONDS -lt $deadline ]]; do
        sleep 2
        log=$(gb_log "$tag")
        done_lines=$(printf '%s\n' "$log" | grep -c "live run .* done tag=" || true)
        if [[ $done_lines -ge $i ]]; then json=$(printf '%s\n' "$log" | sed -n 's/.*live run .* done tag=[^ ]* json=//p' | tail -1); break; fi
      done
      [[ -n $json ]] || { echo "run $i: no live run finished within 90 s (did the wake word trigger?)" >&2; printf '%s\n' "${log:-}" | tail -5 >&2; exit 1; }
      sleep 3 # let the panel settle before the next wake word
    done
  else
    # ----------------------------------------------------------------- scripted loops
    secs=$(( loops * (12 + gap / 1000 + 1) ))
    echo "== $look: $loops loops (about $secs s)"
    sh_ am broadcast -a dev.glassworks.debug.GLOW --es look "$look" --es tag "$tag" --ei gap "$gap" --ei loops "$loops" >/dev/null
    start_by=$((SECONDS + 20)); deadline=$((SECONDS + secs + 60)); started=0; json=""
    while [[ $SECONDS -lt $deadline ]]; do
      sleep 3
      log=$(gb_log "$tag")
      [[ $log == *"start tag=$tag"* ]] && started=1
      if [[ $log == *" done tag=$tag json="* ]]; then json=$(printf '%s\n' "$log" | sed -n 's/.* done tag=[^ ]* json=//p' | tail -1); break; fi
      if [[ $started == 0 && $SECONDS -gt $start_by ]]; then
        echo "no start in logcat after 20 s: is this a test build of test-build/glowbench-s1, with $pkg in front?" >&2
        sh_ "logcat -d -v brief -s GlowBench:I GlowBench:W" | tail -5 >&2 || true
        exit 1
      fi
    done
    [[ -n $json ]] || { echo "$look: timed out" >&2; exit 1; }
  fi
  printf '%s\n' "$log" > "$out/$name.log"
  "${ADB[@]}" exec-out cat "$json" > "$out/$name.json"
  sh_ dumpsys gfxinfo "$pkg" > "$out/$name-gfxinfo.txt"
  echo "   $out/$name.json"
done

python3 "$here/report.py" "$out"
