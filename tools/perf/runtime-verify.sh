#!/usr/bin/env bash
# runtime-verify.sh: which of the app's classes would Android 10 verify again at every start? (docs/research/art-start-path.md)
#
# Runs the emulator's own dex2oat on the APK (the `verify` filter, as an install does) and reads the class status it
# leaves in the oat file. A class that soft-fails there (a call Android 10 can't find, Kotlin's interface bridges) is
# `RetryVerificationAtRuntime`: ART verifies it again on the main thread each time the app starts. Prints those of
# dev.glassworks and sh.edm.s1kiosk, and why, and fails when one isn't an `ApiNN` holder (StartPathVerifyTest's rule,
# which a JVM test checks on every PR without an emulator; this is the check against ART itself).
#
# What it touches: an emulator only (it refuses a real device), through an adb server of its own on its own port with
# USB and mDNS off, which it stops at the end. It installs nothing: it pushes a copy of the APK to /data/local/tmp,
# compiles it there and deletes what it wrote. It needs `adb root` (a google_apis image) for oatdump. Use an emulator
# at the frame's API level: 29 for the S1. The dex doesn't depend on the ABI, so any ABI's APK works.
#
#   tools/perf/runtime-verify.sh <app.apk> <emulator serial>     RUNTIME_VERIFY_ADB_PORT picks the port (5079)
set -euo pipefail

apk="$1"
serial="$2"
adb="${ADB:-adb}"
work="$(mktemp -d)"
remote=/data/local/tmp/runtime-verify

export ADB_USB=0 ADB_MDNS=0 ADB_MDNS_AUTO_CONNECT=0 ANDROID_ADB_SERVER_PORT="${RUNTIME_VERIFY_ADB_PORT:-5079}"
if (exec 3<>"/dev/tcp/127.0.0.1/$ANDROID_ADB_SERVER_PORT") 2>/dev/null; then
  echo "runtime-verify: port $ANDROID_ADB_SERVER_PORT is in use; pick a free one with RUNTIME_VERIFY_ADB_PORT" >&2
  rm -rf "$work"
  exit 2
fi
pushed=""
trap '[ -z "$pushed" ] || "$adb" -s "$serial" shell rm -rf "$remote" >/dev/null 2>&1 || true; "$adb" kill-server >/dev/null 2>&1 || true; rm -rf "$work"' EXIT
"$adb" start-server >/dev/null 2>&1
case "$serial" in *:*) "$adb" connect "$serial" >/dev/null ;; esac
"$adb" -s "$serial" wait-for-device

# Older emulator images say so in ro.kernel.qemu, newer ones in ro.boot.qemu.
qemu="$("$adb" -s "$serial" shell 'getprop ro.kernel.qemu; getprop ro.boot.qemu' | tr -d '\r')"
printf '%s\n' "$qemu" | grep -qx 1 || { echo "runtime-verify: $serial isn't an emulator" >&2; exit 2; }
"$adb" -s "$serial" root >/dev/null
"$adb" -s "$serial" wait-for-device
sdk="$("$adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
isa="$("$adb" -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r' | sed 's/-v8a$//; s/^armeabi.*/arm/')"

pushed=1
"$adb" -s "$serial" shell mkdir -p "$remote"
"$adb" -s "$serial" push "$apk" "$remote/base.apk" >/dev/null
"$adb" -s "$serial" logcat -c || true
# As installd runs it for an install: hidden APIs checked, the verify filter.
"$adb" -s "$serial" shell "dex2oat --dex-file=$remote/base.apk --dex-location=/data/app/runtime-verify/base.apk \
  --oat-file=$remote/base.odex --instruction-set=$isa --compiler-filter=verify \
  --runtime-arg -Xhidden-api-policy:enabled --runtime-arg -verbose:verifier"
"$adb" -s "$serial" logcat -d -s dex2oat:V > "$work/dex2oat.log"
"$adb" -s "$serial" shell "oatdump --oat-file=$remote/base.odex --no-disassemble 2>/dev/null" \
  | grep -E '^[0-9]+: L(dev/glassworks|sh/edm/s1kiosk)/' > "$work/classes.txt" || true

total="$(wc -l < "$work/classes.txt" | tr -d ' ')"
[ "$total" -gt 1000 ] || { echo "runtime-verify: oatdump listed only $total of the app's classes" >&2; exit 2; }
retry="$(grep RetryVerificationAtRuntime "$work/classes.txt" | sed -E 's/^[0-9]+: L([^;]+);.*/\1/' || true)"
# An ApiNN holder is meant to soft-fail below its level: only its own query's branch reaches it.
unexpected="$(printf '%s\n' "$retry" | grep -v -E '\$Api[0-9]+($|\$)' | grep . || true)"

echo "runtime-verify: API $sdk, $total of the app's classes, $(printf '%s\n' "$retry" | grep -c . || true) left to run-time verification"
printf '%s\n' "$retry" | grep . | sed 's/^/  /' || true
if [ -n "$unexpected" ]; then
  echo "runtime-verify: verified at every start (not an ApiNN holder), with dex2oat's reasons:" >&2
  for c in $unexpected; do
    dotted="${c//\//.}"
    grep -F " $dotted." "$work/dex2oat.log" | grep -E '\[0x[0-9A-F]+\]' | sed 's/^.*dex2oat *: /  /' >&2 || true
  done
  exit 1
fi
