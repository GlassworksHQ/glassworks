#!/usr/bin/env bash
# apk-dm.sh: the dex metadata file (.dm) that has Android compile an APK's start path when it installs it
# (docs/research/art-start-path.md).
#
# A release APK carries its baseline profile in assets/dexopt/baseline.prof (AGP builds it from every module's
# baseline-prof.txt). Installed beside the APK, a .dm holding that profile as primary.prof makes Android 9 to 11
# compile the profile's code during the install (`dumpsys package dexopt`: [reason=install-dm]) instead of only
# verifying it. Android 12 and newer read a newer profile format, which this doesn't write: there ProfileInstaller
# and the background optimizer do it. The .dm takes the APK's name, so adb pairs them:
#
#   tools/perf/apk-dm.sh app.apk            writes app.dm beside it
#   adb install-multiple -r app.apk app.dm
#
# What it touches: it reads the APK and writes only the .dm (and a temporary folder it deletes). No device.
set -euo pipefail

apk="$1"
out="${2:-${apk%.apk}.dm}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

unzip -p "$apk" assets/dexopt/baseline.prof > "$work/primary.prof" 2>/dev/null || true
[ -s "$work/primary.prof" ] || { echo "apk-dm: $apk has no assets/dexopt/baseline.prof (a debug build has none)" >&2; exit 1; }
# The format Android 9 to 11 read: "pro\0" then version "010\0".
[ "$(head -c 8 "$work/primary.prof" | od -An -tx1 | tr -d ' \n')" = 70726f0030313000 ] \
  || { echo "apk-dm: $apk's baseline.prof isn't the version 010 profile Android 9 to 11 read" >&2; exit 1; }
rm -f "$out"
(cd "$work" && zip -q -X -D dm.zip primary.prof)
mv "$work/dm.zip" "$out"
echo "apk-dm: wrote $out ($(wc -c < "$work/primary.prof" | tr -d ' ') byte profile)"
