#!/bin/bash
# tab-memory.sh <out-file> <minutes> [visit-every-min]: the kiosk's web page memory, once a minute, for
# docs/research/gecko-hidden-tab-memory.md (#829). Debugging only; nothing in the grading path reads it.
#
# READ-ONLY on the device unless visit-every-min is given: `dumpsys meminfo`, `ps`, /proc/meminfo and each process's
# /proc/<pid>/stat. With visit-every-min N, every N minutes it wakes the screen (`input keyevent KEYCODE_WAKEUP`),
# brings the kiosk to the front for 3 s (`am start -n <activity>`), and then hides it again, as the 2026-11-24 probe
# in front of it did. BEHIND says how: `am start` arguments for another app to put in front (default
# `-a android.settings.SETTINGS`), or `sleep` to turn the screen off (`input keyevent KEYCODE_SLEEP`), which stops the
# kiosk too: the S1 has no Settings app, so use `BEHIND=sleep` there. That changes what is on screen, nothing stored.
# An `am start` that prints an error (Android 10 returns 0 for an intent nothing resolves) stops the run.
#
# ADB is the adb command that reaches the device (default `adb`, with ANDROID_SERIAL as for tools/perf/run.sh);
# PKG and ACTIVITY default to the S1 app's.
# Each sample: the time, MemAvailable, every PSS line of the app's processes from `dumpsys meminfo` (the tab is
# `:sandboxed_tab…`), and utime/stime (clock ticks) of each tab process. A new tab pid is a page that died and came
# back. The output holds no serials, MACs or addresses.
# A failed device read (adb gone, a command refused) stops the run with an error, so a run never ends looking complete
# without its numbers. A tab process that ends between the list and its /proc read is not an error: the page restarts.
set -uo pipefail
OUT=${1:?out file}; MIN=${2:?minutes}; VISIT=${3:-0}
read -ra A <<< "${ADB:-adb}"
PKG=${PKG:-sh.edm.s1kiosk}; ACTIVITY=${ACTIVITY:-sh.edm.s1kiosk/.MainActivity}; BEHIND=${BEHIND:--a android.settings.SETTINGS}
fail() { echo "tab-memory: $1" >&2; echo "# stopped: $1" >> "$OUT"; exit 1; }
# One device command: its output without carriage returns, or the run stops.
need() {
  local out
  out=$("${A[@]}" shell "$1" < /dev/null) || fail "adb failed: $1"
  printf '%s\n' "$out" | tr -d '\r'
}

echo "# tab-memory $PKG, $MIN min, visit every ${VISIT} min, start $(date -u +%H:%M:%SZ)" >> "$OUT"
for i in $(seq 0 "$MIN"); do
  if [ "$VISIT" != 0 ] && [ "$i" != 0 ] && [ $((i % VISIT)) = 0 ]; then
    need "input keyevent KEYCODE_WAKEUP" > /dev/null || exit 1
    started=$(need "am start -n $ACTIVITY 2>&1") || exit 1
    case "$started" in *Error*) fail "am start -n $ACTIVITY: $started" ;; esac
    sleep 3
    if [ "$BEHIND" = sleep ]; then
      need "input keyevent KEYCODE_SLEEP" > /dev/null || exit 1
    else
      started=$(need "am start $BEHIND 2>&1") || exit 1
      case "$started" in *Error*) fail "am start $BEHIND: $started" ;; esac
    fi
    echo "visit $(date -u +%H:%M:%SZ)" >> "$OUT"
  fi
  # Each read in its own variable: `need` runs in a subshell there, so its failure is checked here.
  avail=$(need "grep MemAvailable /proc/meminfo") || exit 1
  meminfo=$(need "dumpsys meminfo") || exit 1
  procs=$(need "ps -A -o PID,NAME") || exit 1
  {
    echo "== t=${i}m $(date -u +%H:%M:%SZ)"
    echo "$avail"
    printf '%s\n' "$meminfo" | grep -F "$PKG" | grep -F "K: "
    for p in $(printf '%s\n' "$procs" | awk -v p="$PKG:sandboxed_tab" 'index($2, p) == 1 {print $1}'); do
      "${A[@]}" shell "cat /proc/$p/stat 2>/dev/null" < /dev/null | tr -d '\r' | awk '{print "tab", $1, "utime", $14, "stime", $15}'
    done
  } >> "$OUT"
  [ "$i" = "$MIN" ] || sleep 57
done
echo "# end $(date -u +%H:%M:%SZ)" >> "$OUT"
