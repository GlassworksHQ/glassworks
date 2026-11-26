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
name=$1; shift
D=/sdcard/Android/data/dev.glassworks.camspike/files
$ADB shell rm -f "$D/$name/done"
$ADB shell am start -n dev.glassworks.camspike/.Spike --es name "$name" "$@"
for _ in $(seq 1 60); do $ADB shell "test -f $D/$name/done && echo y" | grep -q y && break; sleep 1; done
mkdir -p "$OUT" && rm -rf "${OUT:?}/$name"
$ADB pull "$D/$name" "$OUT/" | tail -1
# Every 30th frame is logged; show the first lines and the summary.
grep -vE '^frame [0-9]*[1-9] |^frame [0-9]*[1-9]0 ' "$OUT/$name/log.txt" | head -70
