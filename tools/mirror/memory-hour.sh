#!/bin/bash
# memory-hour.sh <out-dir> [minutes]: records a Mirror's memory while Glassworks runs, for
# frames/lululemon-mirror/research/mirror-memory.md. READ-ONLY on the Mirror: dumpsys, ps, logcat -d, dmesg and
# /proc reads. It changes nothing, plays nothing, and leaves the screen as it is.
# MIRROR_ADB is the adb command that reaches the Mirror, e.g. MIRROR_ADB="adb -s <serial>".
# Output: <out-dir>/summary.txt (counts and sizes, no serials, MACs or IPs), and the raw dumps, which hold
# device-unique values: never paste those into an issue or PR.
set -u
OUT=$1; MIN=${2:-60}; mkdir -p "$OUT"
read -ra ADB <<< "${MIRROR_ADB:?set MIRROR_ADB to the adb command for the Mirror, e.g. adb -s <serial>}"
P=dev.glassworks.generic_android.legacy
sh() { "${ADB[@]}" shell "$@" < /dev/null | tr -d '\r'; }

START_WALL=$(sh date '+%m-%d %H:%M:%S.000')
START_UP=$(sh cat /proc/uptime | awk '{print $1}')
sh dumpsys package $P | grep -E 'versionName|versionCode' > "$OUT/version.txt"
sh ps | grep $P | awk '{print $NF}' > "$OUT/procs-before.txt"
sh dumpsys meminfo > "$OUT/meminfo-before.txt"
: > "$OUT/free.txt"
for ((i = 0; i <= MIN; i += 5)); do
  echo "$(date -u +%H:%M:%SZ) $(sh dumpsys meminfo | grep -E 'Free RAM|Lost RAM' | awk '{printf "%s %s ", $1, $3}')" >> "$OUT/free.txt"
  [ $i -lt $MIN ] && sleep 300
done
sh dumpsys meminfo > "$OUT/meminfo-after.txt"
sh ps | grep $P | awk '{print $NF}' > "$OUT/procs-after.txt"
sh logcat -b events -d -T "$START_WALL" > "$OUT/events.txt"
sh dmesg | awk -v t="$START_UP" -F'[][]' '$2 + 0 >= t' > "$OUT/dmesg.txt"

died() { grep 'am_proc_died' "$OUT/events.txt" | grep -c "$1"; }
{
  echo "run: $MIN min from $START_WALL (Mirror clock); $(cat "$OUT/version.txt" | tr -s ' ' | tr '\n' ' ')"
  echo "our processes before: $(sed "s/$P/app/" "$OUT/procs-before.txt" | tr '\n' ' ')"
  echo "our processes after:  $(sed "s/$P/app/" "$OUT/procs-after.txt" | tr '\n' ' ')"
  echo "am_proc_died, ours: main $(grep 'am_proc_died' "$OUT/events.txt" | grep -c "$P\]"), tab $(died "$P:tab"), gpu $(died "$P:gpu"), crashhelper $(died "$P:crashhelper")"
  echo "am_proc_died, all: $(grep -c 'am_proc_died' "$OUT/events.txt")"
  echo "lowmemorykiller kills: all $(grep -c 'lowmemorykiller: Killing' "$OUT/dmesg.txt"), at adj 0 $(grep 'lowmemorykiller: Killing' "$OUT/dmesg.txt" | grep -c 'adj 0,'), ours $(grep 'lowmemorykiller: Killing' "$OUT/dmesg.txt" | grep -c -E "legacy|AndroidUI|'Gecko")"
  echo "am_low_memory events: $(grep -c 'am_low_memory' "$OUT/events.txt")"
  echo "app crashes (am_crash): $(grep -c 'am_crash' "$OUT/events.txt")"
  for f in before after; do
    echo "meminfo $f: $(grep -E 'Total RAM|Free RAM|Lost RAM|ZRAM' "$OUT/meminfo-$f.txt" | sed 's/^ *//' | tr '\n' ';')"
    echo "  ours (PSS kB): $(awk '/Total PSS by process/,/Total PSS by OOM/' "$OUT/meminfo-$f.txt" | grep -E "kB: $P" | sed -E "s/ *([0-9,]+) kB: $P(:[a-z0-9]+)? .*/\2=\1/; s/^=/main=/" | tr '\n' ' ')"
  done
  echo "free RAM every 5 min:"; cat "$OUT/free.txt"
} > "$OUT/summary.txt"
cat "$OUT/summary.txt"
