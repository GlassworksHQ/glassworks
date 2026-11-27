#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# fps.sh <name>:<lower>,<upper> ...: runs the camera spike's Camera2 reader once per fps range and reports, for each,
# the session's range, the reader's frame count and rate, and how much of a saved frame is the LEGACY shim's drawn
# green (rows whose Y sits at 79, give or take 6). The spike is force-stopped before each run, so every run is a
# fresh process and a fresh camera session. `template` instead of a range keeps the request template's own.
# Touches: the Mirror (am force-stop and am start of dev.glassworks.camspike only, through run.sh) and out/<name>.
# Needs ffmpeg and python3. MIRROR_ADB as for run.sh.
#
# The note's table (2026-11-25): fps.sh a30:30,30 a15:15,15 a24:24,24 a730:7,30 b30:30,30 b15:15,15
set -euo pipefail

HERE="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
OUT="${CAMSPIKE_OUT:-$HERE/out}"
ADB=${MIRROR_ADB:-adb}
for run in "$@"; do
  name=${run%%:*}; fps=${run#*:}
  $ADB shell am force-stop dev.glassworks.camspike; sleep 2
  "$HERE/run.sh" "$name" --es test c2 --es c2out reader --es fps "$fps" >/dev/null 2>&1 || { echo "$name want=$fps: no result"; continue; }
  jpg=$(ls "$OUT/$name"/*.jpg 2>/dev/null | tail -1 || true)
  green=""
  if [ -n "$jpg" ]; then
    green=$(ffmpeg -v error -i "$jpg" -f rawvideo -pix_fmt yuvj420p - | python3 -I -c '
import sys; d=sys.stdin.buffer.read(); w,h=1280,720; Y=d[:w*h]
rows=range(0,h,10); g=sum(1 for r in rows if all(abs(Y[r*w+x]-79)<=6 for x in range(0,w,20)))
print("green rows %d%%" % (100*g//len(rows)))')
  fi
  echo "$name want=$fps: $(grep -E 'session configured|reader frames' "$OUT/$name/log.txt" | tr '\n' ' ') $green"
done
