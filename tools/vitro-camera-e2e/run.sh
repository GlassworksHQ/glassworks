#!/usr/bin/env bash
# Vitro's camera end-to-end test, start to finish (MIT): starts the fake Home Assistant with the test's home
# (serve.py, from webrtcenv.py's venv), points the emulator at it with `adb reverse`, installs Vitro's test APK, runs
# e2e.py, and stops the fake. README.md next to this file explains the networking and what each check proves.
#
# What it touches: the one emulator ANDROID_SERIAL names (or the only one attached): it installs, starts and
# force-stops dev.glassworks.vitro.test there, presses HOME, and sets two `adb reverse` ports (8089 and 8090), which
# it removes at the end. On this computer: the fake on 127.0.0.1:8089 and :8090, and the cache
# (~/.cache/glassworks-perf: the venv, the camera streams, HA's frontend). Emulators only: never a real panel.
#
#   tools/vitro-camera-e2e/run.sh [--apk PATH] [--out DIR] [-- e2e.py options]
#
# On METIS (WSL2 with the emulator on Windows): ADB=/mnt/c/.../platform-tools/adb.exe tools/vitro-camera-e2e/run.sh
# In CI: core-emulators.yml's vitro-camera job runs it inside the emulator runner.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
apk="$repo/engines/engine-vitro/test-app/build/outputs/apk/debug/vitro-test-debug.apk"
out="$repo/build/vitro-camera-e2e"
port=8089
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) apk=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --) shift; break ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
ADB=${ADB:-adb}
export ADB
# ADB is one executable, whose path may have spaces (/mnt/c/Program Files/...): an array, quoted at every call. The
# serial goes on the command line too: adb.exe started from WSL doesn't see ANDROID_SERIAL (e2e.py does the same).
adb=("$ADB")
if [ -n "${ANDROID_SERIAL:-}" ]; then adb+=(-s "$ANDROID_SERIAL"); fi
mkdir -p "$out"

[ -f "$apk" ] || { echo "no APK at $apk: ./gradlew :vitro-test:assembleDebug" >&2; exit 2; }
command -v ffmpeg >/dev/null || { echo "the camera streams need ffmpeg (with libx264)" >&2; exit 2; }

py=$(python3 "$repo/tools/perf/webrtcenv.py")
"$py" "$here/serve.py" --port "$port" --log "$out/fake.jsonl" > "$out/fake.out" 2>&1 &
fake=$!
cleanup() {
  kill "$fake" 2>/dev/null || true
  "${adb[@]}" reverse --remove "tcp:$port" < /dev/null 2>/dev/null || true
  "${adb[@]}" reverse --remove "tcp:$((port + 1))" < /dev/null 2>/dev/null || true
}
trap cleanup EXIT

# The first start makes HA's frontend, the camera streams and their MJPEG pictures into the cache: up to a few minutes.
for _ in $(seq 1 300); do
  curl -fs -o /dev/null "http://127.0.0.1:$port/clarity/cameras" && break
  kill -0 "$fake" 2>/dev/null || { cat "$out/fake.out" >&2; exit 1; }
  sleep 1
done
cat "$out/fake.out"

# No stdin for adb: it reads it, and would eat a calling script's input.
"${adb[@]}" wait-for-device < /dev/null
# Emulators only: nothing below installs, presses HOME or force-stops anything on a real device.
case "$("${adb[@]}" shell 'getprop ro.kernel.qemu; getprop ro.boot.qemu' < /dev/null | tr -d '\r')" in
  *1*) ;;
  *) echo "refusing: ${ANDROID_SERIAL:-the attached device} isn't an emulator" >&2; exit 2 ;;
esac
# An adb.exe on Windows (METIS) needs a Windows path for the APK.
case "$ADB" in *.exe) apk_arg=$(wslpath -w "$apk") ;; *) apk_arg=$apk ;; esac
"${adb[@]}" install -r "$apk_arg" < /dev/null >/dev/null
"${adb[@]}" reverse "tcp:$port" "tcp:$port" < /dev/null >/dev/null
"${adb[@]}" reverse "tcp:$((port + 1))" "tcp:$((port + 1))" < /dev/null >/dev/null

python3 "$here/e2e.py" --out "$out" "$@" < /dev/null
