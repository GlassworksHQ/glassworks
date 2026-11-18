#!/bin/bash
# memory-hour.sh <out-dir> [minutes]: records a Mirror's memory while Glassworks runs, for
# frames/lululemon-mirror/research/mirror-memory.md. READ-ONLY on the Mirror: dumpsys, ps, logcat -d, dmesg and
# /proc reads. It changes nothing, plays nothing, and leaves the screen as it is.
# MIRROR_ADB is the adb command that reaches the Mirror, e.g. MIRROR_ADB="adb -s <serial>".
# Every 5 minutes it saves the event log and dmesg, so nothing is lost when their buffers wrap, and the counts come
# from all the saves together. It stops with an error if any read fails, rather than report zero.
# Output: <out-dir>/summary.txt (counts and sizes, no serials, MACs or IPs), and the raw dumps, which hold
# device-unique values: never paste those into an issue or PR.
set -euo pipefail
OUT=$1; MIN=${2:-60}; mkdir -p "$OUT/samples"
read -ra ADB <<< "${MIRROR_ADB:?set MIRROR_ADB to the adb command for the Mirror, e.g. adb -s <serial>}"
P=dev.glassworks.generic_android.legacy
# One remote command line, quoted for the Mirror's shell: adb shell joins its arguments with spaces.
sh() { local out; out=$("${ADB[@]}" shell "$1" < /dev/null) || { echo "memory-hour: adb failed: $1" >&2; exit 1; }; printf '%s\n' "$out" | tr -d '\r'; }

START_WALL=$(sh "date '+%m-%d %H:%M:%S.000'")
START_UP=$(sh 'cat /proc/uptime' | awk '{print $1}')
[[ $START_WALL =~ ^[0-9]{2}-[0-9]{2}\ [0-9:.]+$ && -n $START_UP ]] || { echo "memory-hour: can't read the Mirror's clock" >&2; exit 1; }
sh "dumpsys package $P" | grep -E 'versionName|versionCode' > "$OUT/version.txt" || true
sh 'ps' | grep "$P" | awk '{print $NF}' > "$OUT/procs-before.txt" || true
sh 'dumpsys meminfo' > "$OUT/meminfo-before.txt"
: > "$OUT/free.txt"
sample() {
  sh "logcat -b events -d -T '$START_WALL'" > "$OUT/samples/events-$1.txt"
  sh 'dmesg' | awk -v t="$START_UP" -F'[][]' '$2 + 0 >= t' > "$OUT/samples/dmesg-$1.txt"
  echo "$(date -u +%H:%M:%SZ) $(sh 'dumpsys meminfo' | grep -E 'Free RAM|Lost RAM' | awk '{printf "%s %s ", $1, $3}')" >> "$OUT/free.txt"
}
left=$((MIN * 60)); n=0
sample $n
while ((left > 0)); do
  step=$((left < 300 ? left : 300)); sleep $step; left=$((left - step)); n=$((n + 1)); sample $n
done
sh 'dumpsys meminfo' > "$OUT/meminfo-after.txt"
sh 'ps' | grep "$P" | awk '{print $NF}' > "$OUT/procs-after.txt" || true
# every save from the start, merged: a line appears in several saves, and a buffer that wrapped lost only old ones
sort -u "$OUT"/samples/events-*.txt | grep -v '^--------- beginning' > "$OUT/events.txt" || true
sort -u "$OUT"/samples/dmesg-*.txt > "$OUT/dmesg.txt" || true

count() { grep -c "$@" || true; }
died() { grep 'am_proc_died' "$OUT/events.txt" | count -F "$1"; }
{
  echo "run: $MIN min from $START_WALL (Mirror clock), $((n + 1)) saves; $(tr -s ' ' < "$OUT/version.txt" | tr '\n' ' ')"
  echo "our processes before: $(sed "s/$P/app/" "$OUT/procs-before.txt" | tr '\n' ' ')"
  echo "our processes after:  $(sed "s/$P/app/" "$OUT/procs-after.txt" | tr '\n' ' ')"
  echo "am_proc_died, ours: main $(died "$P]"), tab $(died "$P:tab"), gpu $(died "$P:gpu"), crashhelper $(died "$P:crashhelper")"
  echo "am_proc_died, all: $(count 'am_proc_died' "$OUT/events.txt")"
  echo "lowmemorykiller kills: all $(count 'lowmemorykiller: Killing' "$OUT/dmesg.txt"), at adj 0 $(grep 'lowmemorykiller: Killing' "$OUT/dmesg.txt" | count 'adj 0,'), ours $(grep 'lowmemorykiller: Killing' "$OUT/dmesg.txt" | count -E "legacy|AndroidUI|'Gecko")"
  echo "am_low_memory events: $(count 'am_low_memory' "$OUT/events.txt")"
  echo "app crashes (am_crash): $(count 'am_crash' "$OUT/events.txt")"
  for f in before after; do
    echo "meminfo $f: $(grep -E 'Total RAM|Free RAM|Lost RAM|ZRAM' "$OUT/meminfo-$f.txt" | sed 's/^ *//' | tr '\n' ';')"
    echo "  ours (PSS kB): $(awk '/Total PSS by process/,/Total PSS by OOM/' "$OUT/meminfo-$f.txt" | grep -E "kB: $P" | sed -E "s/ *([0-9,]+) kB: $P(:[a-z0-9]+)? .*/\2=\1/; s/^=/main=/" | tr '\n' ' ')"
  done
  echo "free RAM at each save:"; cat "$OUT/free.txt"
} > "$OUT/summary.txt"
cat "$OUT/summary.txt"
