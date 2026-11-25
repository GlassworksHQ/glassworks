#!/bin/bash
# tab-memory.sh <out-file> <minutes> [visit-every-min]: the kiosk's web page memory, once a minute, for
# docs/research/gecko-hidden-tab-memory.md (#829). Debugging only; nothing in the grading path reads it.
#
# READ-ONLY on the device unless visit-every-min is given: `dumpsys meminfo`, `ps`, /proc/meminfo and each process's
# /proc/<pid>/stat. With visit-every-min N, every N minutes it brings the kiosk to the front for 3 s
# (`am start -n <activity>`) and then puts Android's Settings back in front (`am start -a android.settings.SETTINGS`),
# as the 2026-11-24 probe in front of it did. That changes what is on screen, nothing stored.
#
# ADB is the adb command that reaches the device (default `adb`, with ANDROID_SERIAL as for tools/perf/run.sh);
# PKG and ACTIVITY default to the S1 app's.
# Each sample: the time, MemAvailable, every PSS line of the app's processes from `dumpsys meminfo` (the tab is
# `:sandboxed_tab…`), and utime/stime (clock ticks) of each tab process. A new tab pid is a page that died and came
# back. The output holds no serials, MACs or addresses.
set -uo pipefail
OUT=${1:?out file}; MIN=${2:?minutes}; VISIT=${3:-0}
read -ra A <<< "${ADB:-adb}"
PKG=${PKG:-sh.edm.s1kiosk}; ACTIVITY=${ACTIVITY:-sh.edm.s1kiosk/.MainActivity}
sh() { "${A[@]}" shell "$1" < /dev/null | tr -d '\r'; }

echo "# tab-memory $PKG, $MIN min, visit every ${VISIT} min, start $(date -u +%H:%M:%SZ)" >> "$OUT"
for i in $(seq 0 "$MIN"); do
  if [ "$VISIT" != 0 ] && [ "$i" != 0 ] && [ $((i % VISIT)) = 0 ]; then
    sh "am start -n $ACTIVITY" > /dev/null; sleep 3
    sh "am start -a android.settings.SETTINGS" > /dev/null
    echo "visit $(date -u +%H:%M:%SZ)" >> "$OUT"
  fi
  {
    echo "== t=${i}m $(date -u +%H:%M:%SZ)"
    sh "grep MemAvailable /proc/meminfo"
    sh "dumpsys meminfo" | grep -F "$PKG" | grep -F "K: "
    for p in $(sh "ps -A -o PID,NAME" | awk -v p="$PKG:sandboxed_tab" 'index($2, p) == 1 {print $1}'); do
      sh "cat /proc/$p/stat" | awk '{print "tab", $1, "utime", $14, "stime", $15}'
    done
  } >> "$OUT" 2>&1
  [ "$i" = "$MIN" ] || sleep 57
done
echo "# end $(date -u +%H:%M:%SZ)" >> "$OUT"
