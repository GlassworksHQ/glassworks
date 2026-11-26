#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# run.sh <name> [am start extras...]: runs one camera spike test on a Mirror and pulls its results to out/<name>.
# Opens the camera for up to 20 s; writes only the spike's own folder on the Mirror
# (/sdcard/Android/data/dev.glassworks.camspike/files/<name>) and out/<name> here. Install the APK first
# (build.sh, then `$MIRROR_ADB install -r -g out/camspike.apk`) and close Glassworks' camera pane or turn camera
# privacy on: two apps can't hold the camera, and connecting while another app holds it crashed mediaserver.
# MIRROR_ADB is the adb command that reaches the Mirror, e.g. MIRROR_ADB="adb -s <serial>".
# The log holds no serials, MACs or addresses; logcat does, so redact it before you paste it anywhere.
set -euo pipefail

HERE="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
OUT="${CAMSPIKE_OUT:-$HERE/out}"
ADB=${MIRROR_ADB:-adb}
name=${1:?usage: run.sh <name> [am start extras...]}; shift
case "$name" in
  .|..|*/*) echo "run name must be one folder name, without '/', '.' or '..'" >&2; exit 1 ;;
esac
D=/sdcard/Android/data/dev.glassworks.camspike/files
# adb shell joins its arguments into one line for the device's shell: quote each one for it, so a value such as
# 'recording-hint=true;picture-size=1920x1080' reaches the spike whole.
quote() { local a out=""; for a in "$@"; do out+="'${a//\'/\'\\\'\'}' "; done; printf '%s' "$out"; }
$ADB shell "rm -f $(quote "$D/$name/done")"
$ADB shell "am start -n dev.glassworks.camspike/.Spike --es name $(quote "$name" "$@")"
finished=""
for _ in $(seq 1 60); do
  if $ADB shell "test -f $(quote "$D/$name/done") && echo y" | grep -q y; then finished=1; break; fi
  sleep 1
done
if [ -z "$finished" ]; then
  echo "no result in 60 s: the spike ignored the launch (no --es test, or a run still going) or is stuck" >&2
  exit 1
fi
mkdir -p "$OUT" && rm -rf "${OUT:?}/$name"
$ADB pull "$D/$name" "$OUT/" | tail -1
# Every 30th frame is logged; show the first lines and the summary.
grep -vE '^frame [0-9]*[1-9] |^frame [0-9]*[1-9]0 ' "$OUT/$name/log.txt" | head -70
