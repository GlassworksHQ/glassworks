#!/usr/bin/env bash
# vendor-pull-check.sh: can adb's shell read each file option C needs off a running Mirror, and is
# it the file we expect? (#334, frames/lululemon-mirror/research/option-c.md)
#
# READ-ONLY ON THE DEVICE. For each row of the list it runs `cat` as adb's shell and hashes what
# comes back on this computer. Nothing is pushed, installed or written on the Mirror, and the bytes
# are never saved: only a hash is kept. Prints one line per file: ok, differs, or denied.
#
# Only the root drives a Mirror. Use the adb server that is already bound to it; this script
# never starts, restarts or kills one.
#
#   ANDROID_ADB_SERVER_PORT=<the Mirror's port> tools/mirror/vendor-pull-check.sh \
#     -s "$(cat /tmp/mirror-serial)" frames/lululemon-mirror/research/option-c-vendor-files.tsv
set -euo pipefail

usage() { echo "usage: $0 -s <serial> <list.tsv> [part ...]" >&2; exit 2; }
serial=
while getopts s: o; do case $o in s) serial=$OPTARG ;; *) usage ;; esac; done
shift $((OPTIND - 1))
[ -n "$serial" ] && [ $# -ge 1 ] || usage
list=$1; shift
parts=" $* "
ADB=${ADB:-adb}

ok=0; differs=0; denied=0
# columns: part path bytes sha256 label shell why
while IFS=$'\t' read -r part path _bytes sha _label _shell _why; do
  case $part in part|\#*) continue ;; esac
  if [ "$parts" != "  " ] && [[ $parts != *" $part "* ]]; then continue; fi
  # `cat` exits non-zero when SELinux or the mode refuses; the output goes nowhere on the device.
  if [ "$("$ADB" -s "$serial" shell "cat '$path' >/dev/null 2>&1 && echo y || echo n" | tr -d '\r')" != y ]; then
    echo "denied	$part	$path"; denied=$((denied + 1)); continue
  fi
  got=$("$ADB" -s "$serial" exec-out cat "$path" | shasum -a 256 | cut -c1-64)
  if [ "$got" = "$sha" ]; then
    echo "ok	$part	$path"; ok=$((ok + 1))
  else
    echo "differs	$part	$path"; differs=$((differs + 1))
  fi
done < "$list"
echo "# ok=$ok differs=$differs denied=$denied"
