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
# name and run are this script's: a caller's own would come last on the am line and win, so the spike would write
# where this script doesn't look.
prev=""
for a in "$@"; do
  case "$prev" in
    --e*) case "$a" in name|run) echo "the '$a' extra is run.sh's own: give the run name as the first argument" >&2; exit 1 ;; esac ;;
  esac
  prev=$a
done
D=/sdcard/Android/data/dev.glassworks.camspike/files
# adb shell joins its arguments into one line for the device's shell: quote each one for it, so a value such as
# 'recording-hint=true;picture-size=1920x1080' reaches the spike whole.
quote() { local a out=""; for a in "$@"; do out+="'${a//\'/\'\\\'\'}' "; done; printf '%s' "$out"; }
# Each launch carries its own id: the spike writes it into the run's done file, and writes ignored-<id> for a launch it
# ignores, so this run never takes another run's result.
id="$(date +%s)-$$-$RANDOM"
$ADB shell "rm -f $(quote "$D/$name/done")"
$ADB shell "am start -n dev.glassworks.camspike/.Spike --es run $(quote "$id") --es name $(quote "$name" "$@")"
finished=""
for _ in $(seq 1 60); do
  # The if keeps a missing marker a good poll, on an adb that returns the device shell's status too.
  got=$($ADB shell "cat $(quote "$D/$name/done") 2>/dev/null; if test -f $(quote "$D/ignored-$id"); then echo ignored; fi" | tr -d '\r')
  case "$got" in
    *ignored*)
      $ADB shell "rm -f $(quote "$D/ignored-$id")" || true
      echo "the spike ignored this launch (no --es test, or a run still going)" >&2; exit 1 ;;
  esac
  if [ "$got" = "$id" ]; then finished=1; break; fi
  sleep 1
done
if [ -z "$finished" ]; then
  echo "no result in 60 s: the spike is stuck" >&2
  exit 1
fi
# Pulled beside out/<name> first: the last result stays until this run's has arrived whole.
mkdir -p "$OUT"
stage=$(mktemp -d "$OUT/.pull.XXXXXX")
trap 'rm -rf "$stage"' EXIT
$ADB pull "$D/$name" "$stage/" | tail -1
if [ "$(cat "$stage/$name/done" 2>/dev/null)" != "$id" ]; then
  echo "the pulled result isn't this run's; out/$name is left as it was" >&2
  exit 1
fi
rm -rf "${OUT:?}/$name" && mv "$stage/$name" "$OUT/$name"
# Every 30th frame is logged; show the first lines and the summary.
grep -vE '^frame [0-9]*[1-9] |^frame [0-9]*[1-9]0 ' "$OUT/$name/log.txt" | head -70
