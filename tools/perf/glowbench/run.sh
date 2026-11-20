#!/usr/bin/env bash
# GlowBench (test-build/glowbench-s1, never merged): runs the #691 edge glow benchmark inside the S1 kiosk and
# prints its frame times. The kiosk must be a test build of this branch, installed and in front.
#
# Touches: wakes the screen; sends the kiosk the dev.glassworks.debug.GLOW broadcast (it draws the glow over the
# dashboard for about 12 s per run, then removes it); resets and reads `dumpsys gfxinfo sh.edm.s1kiosk`; reads
# logcat (never clears it); copies the result JSON off the device. Installs, uninstalls and changes nothing else:
# installing the test build and putting the original back are the root's steps.
#
#   tools/perf/glowbench/run.sh [--loops N] [--variants "spark sheen pour"] [--out DIR] -- <adb command...>
#   e.g. tools/perf/glowbench/run.sh --loops 5 -- adb -s "$(cat /tmp/s1-serial)"
set -euo pipefail

loops=3
variants="spark sheen pour"
out="glowbench-$(date +%Y%m%d-%H%M%S)"
while [[ $# -gt 0 ]]; do
  case $1 in
    --loops) loops=$2; shift 2 ;;
    --variants) variants=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --) shift; break ;;
    *) echo "unknown option $1 (the adb command goes after --)" >&2; exit 2 ;;
  esac
done
[[ $# -gt 0 ]] || { echo "usage: $0 [--loops N] [--variants \"spark sheen pour\"] [--out DIR] -- <adb command...>" >&2; exit 2; }
ADB=("$@")
here=$(cd "$(dirname "$0")" && pwd)
pkg=sh.edm.s1kiosk
mkdir -p "$out"

sh_() { "${ADB[@]}" shell "$@" | tr -d '\r'; }

version=$(sh_ dumpsys package $pkg | grep -m1 versionName || true)
[[ -n $version ]] || { echo "$pkg is not installed" >&2; exit 1; }
echo "kiosk: ${version// /}"
resumed=$(sh_ dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity' || true)
[[ $resumed == *$pkg* ]] || { echo "the kiosk is not in front: $resumed" >&2; exit 1; }

for v in $variants; do
  sh_ input keyevent KEYCODE_WAKEUP >/dev/null
  sh_ dumpsys gfxinfo $pkg reset >/dev/null
  since=$(sh_ date '+%m-%d %H:%M:%S.000')
  echo "== $v: $loops loops (about $((loops * 13)) s)"
  sh_ am broadcast -a dev.glassworks.debug.GLOW --es variant "$v" --ei loops "$loops" >/dev/null
  deadline=$((SECONDS + loops * 13 + 40)); started=0; json=""
  while [[ $SECONDS -lt $deadline ]]; do
    sleep 3
    log=$(sh_ logcat -d -v brief -T "$since" -s GlowBench:I GlowBench:W || true)
    [[ $log == *"GlowBench"*"start "* ]] && started=1
    if [[ $log == *"done json="* ]]; then json=$(printf '%s\n' "$log" | sed -n 's/.*done json=//p' | tail -1); break; fi
    if [[ $started == 0 && $SECONDS -gt $((deadline - loops * 13 - 25)) ]]; then
      echo "no start in logcat: is this a test build of test-build/glowbench-s1, with the kiosk in front?" >&2
      printf '%s\n' "$log" >&2; exit 1
    fi
  done
  [[ -n $json ]] || { echo "$v: timed out" >&2; exit 1; }
  printf '%s\n' "$log" > "$out/$v.log"
  "${ADB[@]}" exec-out cat "$json" > "$out/$v.json"
  sh_ dumpsys gfxinfo $pkg > "$out/$v-gfxinfo.txt"
  echo "   $out/$v.json"
done

python3 "$here/report.py" "$out"
