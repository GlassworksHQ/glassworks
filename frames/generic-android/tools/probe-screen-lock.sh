#!/usr/bin/env bash
# Which adb commands tell whether an Android device has a secure screen lock (PIN, pattern, password), without
# root (#522). Prints each candidate's answer. Read-only, unless --set-pin: then it sets PIN 1234 with
# `locksettings set-pin`, probes again and clears it with `locksettings clear --old 1234`. Use --set-pin on an
# emulator only, never on a device someone uses. Results: frames/generic-android/research/screen-lock.md.
#
#   ADB_USB=0 ADB_MDNS=0 ANDROID_ADB_SERVER_PORT=<yours> frames/generic-android/tools/probe-screen-lock.sh -s emulator-5590 [--set-pin]
set -euo pipefail
adb_args=()
set_pin=0
while [ $# -gt 0 ]; do
  case "$1" in
    -s) adb_args=(-s "$2"); shift 2 ;;
    --set-pin) set_pin=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
adb() { "${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}" "${adb_args[@]}" "$@"; }
sh() { adb shell "$@" 2>&1 | tr -d '\r'; }

probe() {
  echo "== $1 (API $(sh getprop ro.build.version.sdk))"
  echo "locksettings get-disabled: $(sh locksettings get-disabled | head -1)"
  echo "dumpsys trust:";        sh dumpsys trust | grep -i -E 'secure|locked' | head -4 | sed 's/^/  /' || true
  echo "dumpsys window:";       sh dumpsys window | grep -i -E 'secure|keyguard' | head -6 | sed 's/^/  /' || true
  echo "dumpsys window policy:"; sh dumpsys window policy | grep -i -E 'secure' | head -4 | sed 's/^/  /' || true
  echo "dumpsys lock_settings:"; sh dumpsys lock_settings | grep -i -E 'quality|credential|secure' | head -6 | sed 's/^/  /' || true
}

probe "as it is"
if [ "$set_pin" = 1 ]; then
  echo "set-pin: $(sh locksettings set-pin 1234 | head -1)"
  probe "with PIN 1234"
  echo "clear: $(sh locksettings clear --old 1234 | head -1)"
  probe "cleared"
fi
