#!/usr/bin/env bash
# vendor-pull-check.sh: can adb's shell read each file option C needs off a running Mirror, and is
# it the file we expect? (#334, frames/lululemon-mirror/research/option-c.md)
#
# READ-ONLY ON THE DEVICE. For each row of the list it runs `cat` as adb's shell and hashes what
# comes back on this computer. Nothing is pushed, installed or written on the Mirror, and the bytes
# are never saved: only a hash is kept. Prints one line per file: ok, differs, or denied.
#
# Only the root drives a Mirror. Use the adb server that is already bound to it; this script
# never starts, restarts or kills one. It needs ANDROID_ADB_SERVER_PORT, asks that server for its
# version over a plain TCP socket first, and stops unless it answers with the adb client's own
# version (a mismatched client would kill and restart the server). Paths in the list must be plain
# (letters, digits, `._+-/`); anything else stops the script before any adb call.
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

# The server must already run, on a port we were given, and speak the client's version: otherwise
# the first adb call would start a server, or replace this one.
port=${ANDROID_ADB_SERVER_PORT:-}
[ -n "$port" ] || { echo "set ANDROID_ADB_SERVER_PORT to the port of the server bound to the Mirror" >&2; exit 2; }
want=$("$ADB" version | sed -n 's/^Android Debug Bridge version 1\.0\.\([0-9][0-9]*\).*/\1/p')
[ -n "$want" ] || { echo "can't read the adb client's version" >&2; exit 2; }
if ! exec 3<>"/dev/tcp/127.0.0.1/$port"; then
  echo "no adb server on port $port: start the Mirror's server first" >&2; exit 2
fi
printf '000chost:version' >&3
reply=$(head -c 12 <&3 || true)
exec 3<&- 3>&-
case $reply in OKAY????????) have=$((16#${reply:8:4})) ;; *) echo "port $port didn't answer as an adb server" >&2; exit 2 ;; esac
[ "$have" = "$want" ] || { echo "server on $port is version $have, the client is $want: refusing (adb would restart it)" >&2; exit 2; }
[ "$("$ADB" -s "$serial" get-state </dev/null 2>/dev/null)" = device ] || { echo "the Mirror isn't in state 'device' on port $port" >&2; exit 2; }

# Every path must be plain before anything runs on the Mirror.
if awk -F'\t' '$1!="part" && $1 !~ /^#/ && $2 !~ /^\/[A-Za-z0-9._+\/-]+$/ {print "bad path: " $2; bad=1} END {exit bad}' "$list" >&2; then :; else exit 2; fi

ok=0; differs=0; denied=0
# columns: part path bytes sha256 label shell why
while IFS=$'\t' read -r part path _bytes sha _label _shell _why; do
  case $part in part|\#*) continue ;; esac
  if [ "$parts" != "  " ] && [[ $parts != *" $part "* ]]; then continue; fi
  # `cat` exits non-zero when SELinux or the mode refuses; the output goes nowhere on the device.
  # stdin from /dev/null: adb forwards its stdin, and would eat the rest of the list.
  if [ "$("$ADB" -s "$serial" shell "cat '$path' >/dev/null 2>&1 && echo y || echo n" </dev/null | tr -d '\r')" != y ]; then
    echo "denied	$part	$path"; denied=$((denied + 1)); continue
  fi
  got=$("$ADB" -s "$serial" exec-out cat "$path" </dev/null | shasum -a 256 | cut -c1-64)
  if [ "$got" = "$sha" ]; then
    echo "ok	$part	$path"; ok=$((ok + 1))
  else
    echo "differs	$part	$path"; differs=$((differs + 1))
  fi
done < "$list"
echo "# ok=$ok differs=$differs denied=$denied"
