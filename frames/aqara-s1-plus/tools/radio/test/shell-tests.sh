#!/usr/bin/env bash
# Runs inside the arm32v7 container of frames/aqara-s1-plus/tools/radio/test/run.sh (as root, disposable filesystem).
# /repo is the checkout (read-only). Builds an Android-like layout with mocks, then tests:
#   s1kiosk_verify + make-bundle.py, and the old radio bundle of 0.4.3 boot images (s1kiosk_bundle.sh,
#   s1kiosk_radio.sh's loader; kept for one release, #528),
#   glassworks_loader.sh (frame bundles: install, boot, slots, fallback, quick-exit marking, refusals),
#   the frame agent glassworks_agent.sh (off, firmware flashing + rollback + request guards, GBL
#   checks, Zigbee stack, the old setting names), glassworks_update.sh (incl. a framework restart
#   mid-install), glassworks_netwatch.sh's ActivityManager check (#248), the setup hotspot's portal rules and
#   name module (#197), and glassworks.rc's Magisk setup (static checks).
set -uo pipefail
R=/repo; T=/t; TB=/tb
PASS=0; FAILS=0
ok() { PASS=$((PASS + 1)); echo "ok   $*"; }
no() { FAILS=$((FAILS + 1)); echo "FAIL $*"; }
check() { local d=$1; shift; if "$@"; then ok "$d"; else no "$d"; fi; }
prop() { cat "$T/props/$1" 2>/dev/null; }
has() { case "$1" in *"$2"*) return 0 ;; esac; echo "    got: '$1' (want '$2')"; return 1; }
hasnt_pct() { case "$1" in *": %"*|*": % "*) echo "    got an empty percentage: '$1'"; return 1 ;; esac; return 0; }

# ---------------------------------------------------------------------------------- layout + mocks
mkdir -p "$T/props" "$T/bin" "$TB" /system/bin /vendor/bin /vendor/firmware /dev/shm/cpcd/cpcd_0 /data/data /data/s1kiosk /etc
ln -sf /bin/mksh /system/bin/sh
touch /etc/cpcd.conf /etc/cpcd_ota.conf
export PATH="$T/bin:$PATH"
w() { cat > "$1"; chmod 755 "$1"; }
w "$T/bin/getprop" <<'EOF'
#!/bin/sh
[ -f "/t/props/$1" ] && cat "/t/props/$1"; exit 0
EOF
w "$T/bin/setprop" <<'EOF'
#!/bin/sh
# /t/setprop-fail-once.<name>: the next write of that property fails, as setprop does when init refuses it
[ -e "/t/setprop-fail-once.$1" ] && { rm -f "/t/setprop-fail-once.$1"; exit 1; }
echo "$1=$2" >> /t/setprop.log
case "$1" in
  ctl.start) echo running > "/t/props/init.svc.$2" ;;
  ctl.stop) echo stopped > "/t/props/init.svc.$2" ;;
  ctl.restart) echo "$2" >> /t/restarts ;;
  *) [ ${#2} -le 91 ] || { echo "setprop: value too long" >&2; exit 1; }; printf '%s' "$2" > "/t/props/$1" ;;
esac
EOF
w "$T/bin/log" <<'EOF'
#!/bin/sh
shift 2; echo "$*" >> /t/log
EOF
w "$T/bin/pm" <<'EOF'
#!/bin/sh
case "$1" in
  install) cat > /dev/null
    # /t/pm-mode: fail = pm refuses; restart(-noup) = system_server dies mid-install (#248), with
    # (without) the package committed first. The fake system_server is /t/bin/system_server.
    # /t/pm-down-after: how many later pm calls fail while the framework is "down" (copied to /t/pm-down).
    case "$(cat /t/pm-mode 2>/dev/null)" in
      fail) echo "Failure [INSTALL_FAILED_TEST]"; exit 1 ;;
      restart|restart-noup)
        [ "$(cat /t/pm-mode)" = restart ] && echo 30300 > /t/pm-version
        pkill -x system_server; (system_server 2000 >/dev/null 2>&1 &)
        [ -f /t/pm-down-after ] && cp /t/pm-down-after /t/pm-down
        echo "Exception occurred while executing: android.os.DeadObjectException"; exit 1 ;;
    esac
    echo 30300 > /t/pm-version; echo Success; exit 0 ;;
esac
if [ -s /t/pm-down ] && [ "$(cat /t/pm-down)" -gt 0 ]; then
  echo $(( $(cat /t/pm-down) - 1 )) > /t/pm-down; echo "pm list" >> /t/pm-down.log
  echo "cmd: Can't find service: package"; exit 20
fi
echo "package:sh.edm.s1kiosk.other versionCode:1 uid:10077"
echo "package:sh.edm.s1kiosk versionCode:$(cat /t/pm-version 2>/dev/null || echo 30200) uid:10050"
EOF
printf '#!/bin/sh\nexit 0\n' | w "$T/bin/am"
w "$T/bin/pidof" <<'EOF'
#!/bin/sh
p=$(pgrep -x "$1" | tr '\n' ' '); [ -n "$p" ] && echo "${p% }"
EOF
# AlarmManager.setTimeZone as `service call alarm 3 s16 <id>`: Android takes known zones, and falls
# back to GMT for anything else (zones listed in /t/tz-unknown).
w "$T/bin/service" <<'EOF'
#!/bin/sh
# IAdbManager.allowDebugging(true, key) as `service call adb 1 i32 1 s16 <key>`: recorded in /t/adb-calls; with
# /t/adb-android-writes (naming an adb_keys file) Android writes the key there itself, as it does for a new key.
if [ "$1 $2 $3 $4 $5 $6" = "call adb 1 i32 1 s16" ]; then
  echo "$7" >> /t/adb-calls; [ -s /t/adb-android-writes ] && echo "$7" >> "$(cat /t/adb-android-writes)"
  echo "Result: Parcel(00000000    '....')"; exit 0
fi
# allowDebugging(false, key), Android's plain "Allow" (this connection only): recorded in /t/adb-once-calls.
if [ "$1 $2 $3 $4 $5 $6" = "call adb 1 i32 0 s16" ]; then
  echo "$7" >> /t/adb-once-calls; echo "Result: Parcel(00000000    '....')"; exit 0
fi
[ "$1 $2 $3 $4" = "call alarm 3 s16" ] || exit 1
echo "$5" >> /t/tz-calls
if grep -qx -- "$5" /t/tz-unknown 2>/dev/null; then printf GMT > /t/props/persist.sys.timezone
else printf '%s' "$5" > /t/props/persist.sys.timezone; fi
echo "Result: Parcel(00000000    '....')"
EOF
w "$T/bin/settings" <<'EOF'
#!/bin/sh
echo "$*" >> /t/settings.calls
[ -e /t/settings-hang ] && [ "$3" = bluetooth_address ] && exec sleep 1000
[ -e /t/settings-fail ] && [ "$3" = bluetooth_address ] && { echo 02:aa:00:00:00:03; exit 1; }
case "$1" in
  get) cat "/t/settings.$2.$3" 2>/dev/null || echo 1 ;;
  put) echo "$4" > "/t/settings.$2.$3" ;;
esac
EOF
for b in iptables ip6tables iptables-restore ip6tables-restore iptables-save ip6tables-save; do printf '#!/bin/sh\nexit 0\n' | w "$T/bin/$b"; done
w "$T/bin/ip" <<'EOF'
#!/bin/sh
case "$*" in *"rule del"*) exit 1 ;; esac; exit 0
EOF
# Aqara's socat: the pty drain reads /t/stale once; bridges just sit there. (The "EZSP bridge" section
# swaps in the real socat.)
w /vendor/bin/socat <<'EOF'
#!/bin/sh
case "$*" in
  -h) printf '      exec:<command-line>\tgroups=FD,FIFO,SOCKET,EXEC,FORK,TERMIOS,PTY,PARENT,UNIX\n'; exit 0 ;;
  *"-u -T 1 FILE:"*) if [ -s /t/stale ]; then cat /t/stale; : > /t/stale; echo drained >> /t/drains; fi; exit 0 ;;
  *TCP-LISTEN*) echo "$*" >> /t/bridge.log; exec sleep 1000 ;;
esac
exit 1
EOF
cp /vendor/bin/socat "$T/socat-mock"
# Aqara's cpcd: -p reports /t/radio-app, -f "flashes": the radio then reports the APP that /t/fw-apps
# maps the image's sha256 to (garbage if unknown). /t/cpcd-fail-once makes one flash fail. It prints
# "Info : progress:<n>" lines like the real one (CRLF, blank lines between); /t/cpcd-slow makes the
# transfer take ~5 s (so the helper reports mid-flash), /t/cpcd-quiet prints no progress at all.
w /vendor/bin/cpcd <<'EOF'
#!/bin/sh
case "$*" in
  "-c /etc/cpcd.conf -p") echo "[19:40:00:823934] Info : Secondary APP v$(cat /t/radio-app)"; exit 0 ;;
  "-c /etc/cpcd_ota.conf -f "*)
    img=$4; echo "$img" >> /t/flashed
    cat /data/s1kiosk/radio/fw/in-progress > /t/marker-at-flash 2>/dev/null || : > /t/marker-at-flash
    # two flashing cpcds at once = two processes on the SPI device: recorded in /t/cpcd-overlap (#645)
    pgrep -fc 'cpcd_ota.conf -f' > /t/cpcd-running
    [ "$(cat /t/cpcd-running)" -gt 1 ] && echo "$img" >> /t/cpcd-overlap
    # /t/cpcd-hang-once: this flash never ends; told to stop it takes 2 s more ("linger", releasing the
    # device) or ignores TERM altogether ("ignore", only KILL ends it). Later flashes are normal.
    if [ -e /t/cpcd-hang-once ]; then
      h=$(cat /t/cpcd-hang-once); rm -f /t/cpcd-hang-once
      if [ "$h" = ignore ]; then trap '' TERM; else trap 'sleep 2; exit 143' TERM; fi
      sleep 1000 & wait $!; while :; do wait; done
    fi
    # /t/cpcd-start-aqara: Aqara's boot sequence starts zigbee_cpcd in the middle of the flash
    if [ -e /t/cpcd-start-aqara ]; then setprop ctl.start zigbee_cpcd; sleep 3; fi
    prog() { [ -e /t/cpcd-quiet ] || printf '[19:40:49:485025] Info : progress:%s\r\n\r\n' "$1"; }
    echo "[19:40:49:419443] Info : Transferring firmware..."
    prog 0; prog 40
    if [ -e /t/cpcd-slow ]; then sleep 5; else sleep 1; fi
    prog 100
    if [ -e /t/cpcd-fail-always ]; then echo "[x] Info : Firmware upgrade failed"; exit 1; fi
    if [ -e /t/cpcd-fail-once ]; then rm -f /t/cpcd-fail-once; echo "[x] Info : Firmware upgrade failed"; exit 1; fi
    h=$(sha256sum "$img" | cut -d' ' -f1); a=$(grep "^$h " /t/fw-apps | cut -d' ' -f2)
    echo "${a:-garbage}" > /t/radio-app
    echo "[x] Info : Firmware upgrade successful"; exit 0 ;;
esac
exit 2
EOF
echo 0017 > "$T/radio-app"; : > "$T/fw-apps"
touch /dev/shm/cpcd/cpcd_0/ctrl.cpcd.sock

# The boot image: helper + loader, bundle service, verifier, a throwaway test key, fake otbr.
cp "$R/frames/aqara-s1-plus/tools/radio/s1kiosk_radio.sh" "$R/frames/aqara-s1-plus/tools/root/s1kiosk_bundle.sh" "$R/frames/aqara-s1-plus/tools/root/out/s1kiosk_verify" "$TB/"
cp "$R/frames/aqara-s1-plus/tools/radio/glassworks_agent.sh" "$TB/"; cp "$TB/s1kiosk_verify" "$TB/glassworks_verify"
openssl genpkey -algorithm ed25519 -out "$T/key.pem" 2>/dev/null
openssl pkey -in "$T/key.pem" -pubout -outform DER | tail -c 32 | xxd -p -c 64 > "$TB/s1kiosk_bundle.pub"
cp "$TB/s1kiosk_bundle.pub" "$TB/glassworks_bundle.pub"
openssl genpkey -algorithm ed25519 -out "$T/other.pem" 2>/dev/null
w "$TB/otbr-agent" <<'EOF'
#!/bin/sh
# fake otbr-agent (border-router build marker: /node/ba-id)
case "$*" in *--radio-version*) echo "VERSION:FAKE-RCP"; exit 0 ;; esac
exec sleep 1000
EOF
w "$TB/ot-ctl" <<'EOF'
#!/bin/sh
# fake ot-ctl: the Thread role and network name come from /t/ot-state and /t/ot-name (CRLF, like ot-ctl).
# Every call's first word goes to /t/ot-calls; /t/ot-fail names a first word that fails (e.g. "thread").
echo "$1" >> /t/ot-calls
[ -f /t/ot-fail ] && [ "$1" = "$(cat /t/ot-fail)" ] && { echo "Error 13: InvalidState"; exit 1; }
case "$1" in
  state) [ -f /t/ot-state ] && printf '%s\r\nDone\r\n' "$(cat /t/ot-state)" && exit 0 ;;
  networkname) [ -f /t/ot-name ] && printf '%s\r\nDone\r\n' "$(cat /t/ot-name)" && exit 0 ;;
esac
echo disabled
EOF

# The kiosk: uid 10050, its private files dir.
APP=/data/data/sh.edm.s1kiosk
mkdir -p "$APP/files/radio"; chown -R 10050:10050 "$APP"

# A synthetic GBL: header, application, eraseprog, end (little-endian tags as the Gecko bootloader).
gbl() { # out extra-tag-hex(optional) [truncate]
  python3 - "$@" <<'PY'
import struct, sys
def tag(i, body): return struct.pack('<II', i, len(body)) + body
data = tag(0x03A617EB, struct.pack('<II', 0x03000000, 0)) + tag(0xF40A0AF4, bytes(28))
if len(sys.argv) > 2 and sys.argv[2]:
    data += tag(int(sys.argv[2], 16), bytes(16))
data += tag(0xFD0303FD, struct.pack('<I', 0x4000) + bytes(range(256)) * 4) + tag(0xFC0404FC, b'\0\0\0\0')
if len(sys.argv) > 3: data = data[:-3]
open(sys.argv[1], 'wb').write(data)
PY
}
STOCK='/vendor/firmware/lichun#30000000#17#20250821#8f636f.bin'
gbl "$STOCK"
STOCK_SHA=$(sha256sum "$STOCK" | cut -d' ' -f1)
echo "$STOCK_SHA 0017" >> "$T/fw-apps"

mkbundle() { # out version key extra-args...
  local out=$1 v=$2 k=$3; shift 3
  python3 "$R/frames/aqara-s1-plus/tools/radio/make-bundle.py" --version "$v" --key "$k" --out "$out" --created test --force "$@" >/dev/null
}
stage() { # bundle-dir: copy it where the app stages it, owned by the kiosk
  rm -rf "$APP/files/radio/bundle-in"; cp -r "$1/radio-bundle" "$APP/files/radio/bundle-in"
  chown -R 10050:10050 "$APP/files/radio/bundle-in"
}
install_bundle() { /system/bin/sh "$TB/s1kiosk_bundle.sh"; prop sys.s1kiosk.bundle.status; }
reset_radio() { rm -rf /data/s1kiosk/radio; rm -f "$T"/props/sys.s1kiosk.* "$T/restarts" "$T/log"; }
helper() { # env... : run the boot helper for a couple of loops
  env S1R_ONCE=${LOOPS:-2} S1R_SLEEP=0 S1R_LOADER_ONCE=1 "$@" /system/bin/sh "$TB/s1kiosk_radio.sh" >/dev/null 2>&1
}

# ------------------------------------------------------------------------------------- verifier
echo "--- s1kiosk_verify / make-bundle.py"
mkbundle "$T/b1" 100 "$T/key.pem" --file "s1kiosk_radio.sh=$R/frames/aqara-s1-plus/tools/radio/s1kiosk_radio.sh:0700" \
  --file "otbr-agent=$TB/otbr-agent:0700" --dir "sisdk=$R/frames/aqara-s1-plus/tools/radio/sisdk"
check "make-bundle writes manifest + signature" test -s "$T/b1/radio-bundle/manifest" -a -s "$T/b1/radio-bundle/manifest.sig"
check "verifier accepts it" "$TB/s1kiosk_verify" "$TB/s1kiosk_bundle.pub" "$T/b1/radio-bundle/manifest" "$T/b1/radio-bundle/manifest.sig"
mkbundle "$T/bx" 100 "$T/other.pem" --file "s1kiosk_radio.sh=$R/frames/aqara-s1-plus/tools/radio/s1kiosk_radio.sh:0700"
"$TB/s1kiosk_verify" "$TB/s1kiosk_bundle.pub" "$T/bx/radio-bundle/manifest" "$T/bx/radio-bundle/manifest.sig" 2>/dev/null
check "verifier rejects another key (exit 1)" test $? = 1
printf '\x7fELFfake' > "$T/zigbeed"
python3 "$R/frames/aqara-s1-plus/tools/radio/make-bundle.py" --version 1 --key "$T/key.pem" --out "$T/bz" --file "sisdk/bin/zigbeed=$T/zigbeed" >/dev/null 2>&1
check "make-bundle refuses to package zigbeed (MSLA)" test $? != 0
python3 "$R/frames/aqara-s1-plus/tools/radio/make-bundle.py" --version 1 --key "$T/key.pem" --out "$T/bp" --force --file "../x=$TB/ot-ctl" >/dev/null 2>&1
check "make-bundle refuses a bad path" test $? != 0
python3 "$R/frames/aqara-s1-plus/tools/radio/make-bundle.py" --version 3020000 --app-version 3.2.0 --key "$T/key.pem" --out "$T/bv" --file "ot-ctl=$TB/ot-ctl" >/dev/null 2>&1
check "make-bundle refuses a version above the app's versionCode" test $? != 0
python3 "$R/frames/aqara-s1-plus/tools/radio/make-bundle.py" --version 30200 --app-version 3.2.0 --key "$T/key.pem" --out "$T/bv" --file "ot-ctl=$TB/ot-ctl" >/dev/null 2>&1
check "...and accepts the versionCode itself" test $? = 0
python3 "$R/frames/aqara-s1-plus/tools/radio/make-bundle.py" --version 30200 --key "$T/key.pem" --out "$T/bv" --file "ot-ctl=$TB/ot-ctl" >/dev/null 2>&1
check "...and needs --app-version (or --force)" test $? != 0

# ------------------------------------------------------------------------------- bundle service
echo "--- s1kiosk_bundle.sh (the old radio bundle, #528 transition)"
reset_radio; stage "$T/b1"
s=$(install_bundle)
check "installs a signed bundle" has "$s" "ok: radio bundle 100 installed"
check "files are in bundle/current, root-owned" test "$(stat -c %u /data/s1kiosk/radio/bundle/current/otbr-agent)" = 0
check "version floor recorded" test "$(cat /data/s1kiosk/radio/bundle/version)" = 100
check "app-readable state written (root, 0644)" test "$(cat /data/s1kiosk-pub/radio-bundle)/$(stat -c %a:%u /data/s1kiosk-pub/radio-bundle)" = "ok 100 100/644:0"
check "--check passes" has "$(/system/bin/sh "$TB/s1kiosk_bundle.sh" --check /data/s1kiosk/radio/bundle/current)" "100 helper"
s=$(install_bundle); check "same bundle again: no-op" has "$s" "already installed"

mkbundle "$T/b0" 99 "$T/key.pem" --file "otbr-agent=$TB/otbr-agent:0700"
stage "$T/b0"; s=$(install_bundle); check "older version refused (anti-rollback)" has "$s" "older than the installed 100"
stage "$T/bx"; s=$(install_bundle); check "wrong key refused" has "$s" "bad signature"

mkbundle "$T/b2" 101 "$T/key.pem" --file "otbr-agent=$TB/otbr-agent:0700" --file "ot-ctl=$TB/ot-ctl:0700"
stage "$T/b2"; echo tampered >> "$APP/files/radio/bundle-in/ot-ctl"
s=$(install_bundle); check "tampered file refused" has "$s" "wrong size"
check "...and the old bundle stays" test "$(cat /data/s1kiosk/radio/bundle/version)" = 100
stage "$T/b2"; printf 'x' | dd of="$APP/files/radio/bundle-in/ot-ctl" bs=1 seek=3 conv=notrunc 2>/dev/null
s=$(install_bundle); check "same-size tampering refused (sha256)" has "$s" "does not match the manifest"
stage "$T/b2"; rm "$APP/files/radio/bundle-in/ot-ctl"; ln -s /etc/passwd "$APP/files/radio/bundle-in/ot-ctl"
s=$(install_bundle); check "symlink refused" has "$s" "is a symlink"
stage "$T/b2"; chown 10077 "$APP/files/radio/bundle-in/ot-ctl"
s=$(install_bundle); check "file owned by another app refused" has "$s" "not owned by the uid"
stage "$T/b2"; ln "$APP/files/radio/bundle-in/ot-ctl" "$APP/files/radio/hardlink"
s=$(install_bundle); check "hard-linked file refused" has "$s" "extra hard links"; rm -f "$APP/files/radio/hardlink"
stage "$T/b2"; rm "$APP/files/radio/bundle-in/ot-ctl"; ln -s /dev/zero "$APP/files/radio/bundle-in/ot-ctl"
s=$(timeout 20 /system/bin/sh "$TB/s1kiosk_bundle.sh"; prop sys.s1kiosk.bundle.status); check "symlink to /dev/zero refused at once" has "$s" "is a symlink"
stage "$T/b2"; rm "$APP/files/radio/bundle-in/ot-ctl"; mkfifo "$APP/files/radio/bundle-in/ot-ctl"; chown 10050 "$APP/files/radio/bundle-in/ot-ctl"
s=$(timeout 20 /system/bin/sh "$TB/s1kiosk_bundle.sh"; prop sys.s1kiosk.bundle.status); check "FIFO refused without blocking" has "$s" "not a regular file"
stage "$T/b2"; mv "$APP/files/radio/bundle-in" "$T/elsewhere-in"; ln -s "$T/elsewhere-in" "$APP/files/radio/bundle-in"
s=$(install_bundle); check "staging dir swapped for a symlink refused" has "$s" "refused"; rm -f "$APP/files/radio/bundle-in"; rm -rf "$T/elsewhere-in"
stage "$T/b2"; echo "file 0700 1 $(printf %064d 0) evil" >> "$APP/files/radio/bundle-in/manifest"
s=$(install_bundle); check "manifest edited after signing refused" has "$s" "bad signature"
long=$(printf 'x%.0s' $(seq 1 150))
mkbundle "$T/b8" 101 "$T/key.pem" --file "$long=$TB/ot-ctl:0700"
stage "$T/b8"; rm "$APP/files/radio/bundle-in/$long"
s=$(install_bundle); check "a long error still reaches the status (cut to 91 bytes)" test "${#s}" -le 91 -a "${s#error}" != "$s"
stage "$T/b2"; s=$(install_bundle); check "newer bundle installs" has "$s" "ok: radio bundle 101 installed"
rm -f /data/s1kiosk/radio/bundle/version; stage "$T/b0"; s=$(install_bundle)
check "floor also comes from the installed manifest" has "$s" "older than the installed 101"
check "...and restarts the helper only if it runs" test ! -e "$T/restarts"
check "staging dir removed" test ! -e /data/s1kiosk/radio/bundle/stage

# ------------------------------------------------------------------------------------- loader
echo "--- s1kiosk_radio.sh's loader (the old radio bundle, #528 transition)"
reset_radio
echo off > "$T/props/persist.s1kiosk.radio_mode"
helper; check "no bundle: boot helper, bundle none" test "$(prop sys.s1kiosk.radio.source)/$(prop sys.s1kiosk.radio.bundle)" = "boot/none"
check "...published for the app" test "$(cat /data/s1kiosk-pub/radio-bundle)" = "none 0 0"
check "contract version 4" test "$(prop sys.s1kiosk.radio.version)" = 4
check "status ok off" has "$(prop sys.s1kiosk.radio.status)" "ok off"
stage "$T/b1"; install_bundle >/dev/null
helper; check "signed bundle helper runs" test "$(prop sys.s1kiosk.radio.source)" = "bundle 100"
check "bundle state ok 100" test "$(prop sys.s1kiosk.radio.bundle)" = "ok 100"
echo junk >> /data/s1kiosk/radio/bundle/current/otbr-agent
helper; check "tampered installed bundle: boot helper" has "$(prop sys.s1kiosk.radio.source)" "boot (bundle rejected"
check "...reported" has "$(prop sys.s1kiosk.radio.bundle)" "bad: bundle rejected: otbr-agent does not match"
reset_radio
printf '#!/bin/sh\nexit 3\n' > "$T/crash.sh"
mkbundle "$T/b3" 200 "$T/key.pem" --file "s1kiosk_radio.sh=$T/crash.sh:0700"
stage "$T/b3"; install_bundle >/dev/null
env S1R_ONCE=1 S1R_SLEEP=0 /system/bin/sh "$TB/s1kiosk_radio.sh" >/dev/null 2>&1
check "bundle helper exiting 3 times: marked bad" test -e /data/s1kiosk/radio/bundle/bad-200
check "...boot helper took over" has "$(prop sys.s1kiosk.radio.source)" "boot (bundle 200: its helper kept exiting)"
helper; check "...and is skipped next time" has "$(prop sys.s1kiosk.radio.bundle)" "bad: bundle 200"
check "...published as bad for the app" test "$(cat /data/s1kiosk-pub/radio-bundle)" = "bad 200 200"
stage "$T/b3"; s=$(install_bundle)
check "reinstalling the same version is not a no-op when marked bad" has "$s" "ok: radio bundle 200 installed"
check "...and clears the mark" test ! -e /data/s1kiosk/radio/bundle/bad-200
reset_radio; mkdir -p /data/s1kiosk/radio
printf '#!/bin/sh\nsetprop test.dev ran\n' > /data/s1kiosk/radio/s1kiosk_radio.sh
helper; check "old-style override ignored without dev-override" test "$(prop test.dev)" != ran
touch /data/s1kiosk/radio/dev-override
helper; check "dev override with the root marker" test "$(prop test.dev)" = ran
rm -f "$T/props/test.dev"

# ------------------------------------------------------------------------------- frame loader
echo "--- glassworks_loader.sh (frame bundles)"
TBL=/tbl; mkdir -p "$TBL"
cp "$R/frames/aqara-s1-plus/tools/root/glassworks_loader.sh" "$TBL/"
cp "$TB/s1kiosk_verify" "$TBL/glassworks_verify"; cp "$TB/s1kiosk_bundle.pub" "$TBL/glassworks_bundle.pub"
printf 'boot-anim' > "$TBL/glassworks_bootanimation.zip"
for s in agent netwatch firstboot update; do   # fake boot copies: they report how they were run
  printf '#!/bin/sh\nsetprop test.%s "${GW_SOURCE}|${GW_BUNDLE}|${GW_BOOT}"\n' "$s" > "$TBL/glassworks_$s.sh"
done
w "$T/bin/mount" <<'MOUNT'
#!/bin/sh
echo "$*" >> /t/mounts
MOUNT
# mv that fails when its source is listed in /t/mv-fail (one path per line), for the loader's renames
w "$T/bin/mv" <<'MV'
#!/bin/sh
for a in "$@"; do case "$a" in -*) ;; *) grep -qxF -- "$a" /t/mv-fail 2>/dev/null && exit 1; break ;; esac; done
exec /bin/mv "$@"
MV
printf 'stock-anim' > "$T/anim.zip"; printf 'not a zip' > "$T/badanim.zip"
python3 -c 'import sys, zipfile; z = zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_STORED); z.writestr("desc.txt", "1440 720 30\n"); z.close()' "$T/banim.zip"
printf '#!/bin/sh\nsetprop test.agent "bundle-script|${GW_SOURCE}|${GW_BUNDLE}"\n' > "$T/svc-agent.sh"
printf '#!/bin/sh\nexit 3\n' > "$T/svc-crash.sh"
L() { GW_RUN="$T/run" GW_ANIM="$T/anim.zip" GW_KMSG="$T/kmsg" /system/bin/sh "$TBL/glassworks_loader.sh" "$@"; }
reset_frame() {
  rm -rf /data/glassworks /data/glassworks-pub "${T:?}/run" "${APP:?}/files/frame"
  rm -f "${T:?}"/props/ro.glassworks.* "${T:?}"/props/sys.glassworks.* "${T:?}"/props/test.* "${T:?}/mounts" "${T:?}/kmsg"
}
mkframe() { # out frame-version key extra-args...
  local out=$1 v=$2 k=$3; shift 3
  python3 "$R/frames/aqara-s1-plus/tools/radio/make-bundle.py" --frame aqara-s1-plus --frame-version "$v" --key "$k" --out "$out" --created test "$@" >/dev/null
}
stage_frame() { # a bundle dir: put it where the app stages it, owned by the kiosk
  rm -rf "${APP:?}/files/frame"; mkdir -p "$APP/files/frame"; cp -r "$1" "$APP/files/frame/bundle-in"
  chown -R 10050:10050 "$APP/files/frame"
}
install_frame() { L install >/dev/null 2>&1; prop sys.glassworks.bundle.status; }

reset_frame
L boot
check "no bundle: the boot image's frame id and version" test "$(prop ro.glassworks.frame.id)/$(prop ro.glassworks.frame.version)" = "aqara-s1-plus/1.0.0"
check "...state none, published for the app" test "$(prop sys.glassworks.bundle.state)/$(cat /data/glassworks-pub/frame-bundle)" = "none/none - 0 -"
check "...the boot image's animation is mounted" has "$(cat "$T/mounts")" "-o bind $TBL/glassworks_bootanimation.zip $T/anim.zip"
# L5: at post-fs-data logd isn't running yet, so the boot step's lines go to the kernel log as well
check "...its start and end in the kernel log, prefixed, for dmesg's timestamps" \
  test "$(grep -c '^glassworks_loader: boot: ' "$T/kmsg")" = 2
check "...the end line says what loaded" grep -qx "glassworks_loader: boot: done: frame 1.0.0, bundle none, boot animation $TBL/glassworks_bootanimation.zip" "$T/kmsg"
rm -f "$T/kmsg"
GW_LOADER_ONCE=1 L run agent
check "...the agent slot runs the boot copy" test "$(prop test.agent)" = "boot||$TBL"

mkframe "$T/f1" 1.0.1 "$T/key.pem" --file "services/agent.sh=$T/svc-agent.sh:0700" --file "bootanimation.zip=$T/banim.zip"
stage_frame "$T/f1/frame-bundle"; s=$(install_frame)
check "a signed frame bundle is staged" has "$s" "ok: frame bundle 1.0.1 staged"
check "...it loads at the next boot, not now" test -d /data/glassworks/bundle/next -a ! -e /data/glassworks/bundle/current
check "...the floor moves only when it loads" test ! -e /data/glassworks/bundle/version
check "...staged, restart to apply: in the state file and its mirror property" test "$(cat /data/glassworks-pub/frame-bundle)/$(prop sys.glassworks.bundle.staged)" = "none - 0 1.0.1/1.0.1"
L boot
check "boot loads it: frame version 1.0.1" test "$(prop ro.glassworks.frame.version)" = 1.0.1
check "...nothing staged any more" test -z "$(prop sys.glassworks.bundle.staged)"
check "...state ok, published with the floor" test "$(prop sys.glassworks.bundle.state)/$(cat /data/glassworks-pub/frame-bundle)" = "ok 1.0.1/ok 1.0.1 1000001 -"
check "...root-only, nothing left staged" test "$(stat -c %a:%u /data/glassworks/bundle/current)" = 700:0 -a ! -e /data/glassworks/bundle/next
check "...the bundle's animation is mounted" has "$(tail -1 "$T/mounts")" "/data/glassworks/bundle/current/bootanimation.zip $T/anim.zip"
GW_LOADER_ONCE=1 L run agent
check "the agent slot runs the bundle's script" test "$(prop test.agent)" = "bundle-script|bundle 1.0.1|/data/glassworks/bundle/current"
L run update
check "a slot without a bundle script runs the boot copy, with the bundle's files" test "$(prop test.update)" = "boot|/data/glassworks/bundle/current|$TBL"
L run svc1; rc=$?
check "a spare slot nothing uses exits at once" test $rc = 0 -a -z "$(prop test.svc1)"
L run nope >/dev/null 2>&1; rc=$?
check "an unknown slot is refused" test $rc = 2
s=$(install_frame); check "the same bundle again: nothing to do" has "$s" "already loaded"

mkframe "$T/f0" 1.0.0 "$T/key.pem" --file "services/agent.sh=$T/svc-agent.sh:0700"
stage_frame "$T/f0/frame-bundle"; s=$(install_frame); check "an older frame version is refused" has "$s" "below the floor"
python3 "$R/frames/aqara-s1-plus/tools/radio/make-bundle.py" --frame other-frame --frame-version 2.0.0 --key "$T/key.pem" \
  --out "$T/fo" --created test --file "services/agent.sh=$T/svc-agent.sh:0700" >/dev/null
stage_frame "$T/fo/frame-bundle"; s=$(install_frame); check "another frame's bundle is refused" has "$s" "a bundle for other-frame, not aqara-s1-plus"
mkframe "$T/fx" 2.0.0 "$T/other.pem" --file "services/agent.sh=$T/svc-agent.sh:0700"
stage_frame "$T/fx/frame-bundle"; s=$(install_frame); check "a bundle signed with another key is refused" has "$s" "bad signature"
stage_frame "$T/b1/radio-bundle"; s=$(install_frame); check "an old radio bundle is refused" has "$s" "not a frame bundle"
mkframe "$T/f2" 2.0.0 "$T/key.pem" --file "services/agent.sh=$T/svc-crash.sh:0700"
stage_frame "$T/f2/frame-bundle"; ln -sf /etc/passwd "$APP/files/frame/bundle-in/services/agent.sh"
s=$(install_frame); check "a symlink in the staging dir is refused" has "$s" "is a symlink"
check "...and nothing is staged" test ! -e /data/glassworks/bundle/next

echo junk >> /data/glassworks/bundle/current/services/agent.sh
L boot
check "a damaged bundle: the boot image's frame version" test "$(prop ro.glassworks.frame.version)" = 1.0.0
check "...state says why" has "$(prop sys.glassworks.bundle.state)" "bad: services/agent.sh does not match the manifest"
check "...and so does the kernel log, where it outlives the boot step" grep -q "^glassworks_loader: frame bundle rejected: services/agent.sh does not match the manifest" "$T/kmsg"
check "...published as bad" test "$(cat /data/glassworks-pub/frame-bundle)" = "bad - 1000001 -"
check "...the boot image's animation" has "$(tail -1 "$T/mounts")" "$TBL/glassworks_bootanimation.zip"
GW_LOADER_ONCE=1 L run agent
check "...the agent slot runs the boot copy" test "$(prop test.agent)" = "boot||$TBL"

stage_frame "$T/f2/frame-bundle"; s=$(install_frame); check "a newer bundle stages over a damaged one" has "$s" "staged"
L boot
check "...and loads" test "$(prop ro.glassworks.frame.version)" = 2.0.0
L run agent
check "a bundle script that keeps exiting is marked bad, for its version" test "$(cat /data/glassworks/bundle/bad-agent)" = 2.0.0
check "...and the boot copy takes over" test "$(prop test.agent)" = "boot (bundle 2.0.0: its agent script kept exiting)||$TBL"
rm -f "$T/props/test.agent"; GW_LOADER_ONCE=1 L run agent
check "...also on the next start" test "$(prop test.agent)" = "boot (bundle 2.0.0: its agent script kept exiting)||$TBL"
s=$(install_frame); check "the same bundle with a slot marked bad installs again" has "$s" "staged"
L boot; check "...and loading it clears the mark" test ! -e /data/glassworks/bundle/bad-agent

mkframe "$T/f3" 2.0.1 "$T/key.pem" --file "services/agent.sh=$T/svc-agent.sh:0700"
stage_frame "$T/f3/frame-bundle"; s=$(install_frame); check "2.0.1 staged" has "$s" "staged"
echo junk >> /data/glassworks/bundle/next/services/agent.sh
L boot
check "a staged bundle that fails its check is dropped at boot" test ! -e /data/glassworks/bundle/next -a "$(prop ro.glassworks.frame.version)" = 2.0.0
check "...and doesn't move the floor" test "$(cat /data/glassworks/bundle/version)" = 2000000
stage_frame "$T/f3/frame-bundle"; install_frame >/dev/null; mv /data/glassworks/bundle/next /data/glassworks/bundle/next.old
check "(setup: the staged bundle moved aside whole: no next, next.old checks as 2.0.1)" \
  test ! -e /data/glassworks/bundle/next -a "$(L check /data/glassworks/bundle/next.old)" = "2.0.1 2000001"
L boot
check "an install stopped with the staged bundle moved aside: it still loads" test "$(prop ro.glassworks.frame.version)" = 2.0.1 -a ! -e /data/glassworks/bundle/next.old

mkframe "$T/f4" 2.0.2 "$T/key.pem" --file "services/agent.sh=$T/svc-agent.sh:0700" --file "bootanimation.zip=$T/badanim.zip"
stage_frame "$T/f4/frame-bundle"; install_frame >/dev/null; rm -f "$T/mounts"; L boot
check "a bundle animation that is not a usable zip: the boot image's is mounted" test "$(cat "$T/mounts")" = "-o bind $TBL/glassworks_bootanimation.zip $T/anim.zip"
check "...and the bundle still loads" test "$(prop ro.glassworks.frame.version)" = 2.0.2
: > "$T/runblock"; GW_RUN="$T/runblock/run" GW_ANIM="$T/anim.zip" /system/bin/sh "$TBL/glassworks_loader.sh" boot
check "no active record can be written: the boot copies run, and it says why" test "$(prop ro.glassworks.frame.version)/$(prop sys.glassworks.bundle.state)" = "1.0.0/bad: cannot record the active bundle"
rm -f "$T/props/test.agent"; GW_LOADER_ONCE=1 GW_RUN="$T/runblock/run" /system/bin/sh "$TBL/glassworks_loader.sh" run agent
check "...the agent slot runs the boot copy" test "$(prop test.agent)" = "boot||$TBL"
GW_RUN="$T/runblock/run" GW_ANIM="$T/anim.zip" /system/bin/sh "$TBL/glassworks_loader.sh" install >/dev/null 2>&1
check "...installing that same bundle says it runs only after a restart" has "$(prop sys.glassworks.bundle.status)" "in place, but not running this boot"
rm -f "$T/runblock"; L boot

mkframe "$T/f5" 2.0.3 "$T/key.pem" --file "services/agent.sh=$T/svc-agent.sh:0700"
mkframe "$T/f6" 2.0.4 "$T/key.pem" --file "services/agent.sh=$T/svc-agent.sh:0700"
stage_frame "$T/f5/frame-bundle"; install_frame >/dev/null; mv /data/glassworks/bundle/next "$T/keep5"
stage_frame "$T/f6/frame-bundle"; install_frame >/dev/null; mv "$T/keep5" /data/glassworks/bundle/next.old
echo junk >> /data/glassworks/bundle/next/services/agent.sh
check "(setup: next.old is a valid 2.0.3)" test "$(L check /data/glassworks/bundle/next.old)" = "2.0.3 2000003"
check "(setup: next is there and fails its check)" has "$(L check /data/glassworks/bundle/next)" "does not match the manifest"
L boot
check "next fails its check but next.old passes: next.old loads" test "$(prop ro.glassworks.frame.version)" = 2.0.3 -a ! -e /data/glassworks/bundle/next -a ! -e /data/glassworks/bundle/next.old

: > "$T/pubblock"; stage_frame "$T/f6/frame-bundle"
GW_PUB="$T/pubblock/pub" GW_RUN="$T/run" GW_ANIM="$T/anim.zip" /system/bin/sh "$TBL/glassworks_loader.sh" install >/dev/null 2>&1; rc=$?
check "staged, but the state file can't be written: an error, not ok" has "$(prop sys.glassworks.bundle.status)" "error: frame bundle 2.0.4 is staged, but its state file can't be written"
check "...exit 1" test $rc = 1
check "...the bundle stays staged" test -d /data/glassworks/bundle/next
rm -rf "$APP/files/frame/bundle-in/services"   # the payload: a retry that copies again would fail now
rm -f /data/glassworks-pub/frame-bundle           # and the public record: the retry rebuilds it from this boot's own
L install >/dev/null 2>&1; rc=$?; s=$(prop sys.glassworks.bundle.status); rm -f "$T/pubblock"
check "...the same install again only writes the state file" test "$s" = "ok: frame bundle 2.0.4 staged; it loads at the next restart" -a $rc = 0
check "...from this boot's own record" test "$(cat /data/glassworks-pub/frame-bundle)" = "ok 2.0.3 2000003 2.0.4"
check "...which now names it" test "$(cat /data/glassworks-pub/frame-bundle | cut -d' ' -f4)" = 2.0.4
L boot; check "...and it loads" test "$(prop ro.glassworks.frame.version)" = 2.0.4
rm -f "$T/run/state"; before=$(cat /data/glassworks-pub/frame-bundle)
mkframe "$T/f7" 2.0.5 "$T/key.pem" --file "services/agent.sh=$T/svc-agent.sh:0700"; stage_frame "$T/f7/frame-bundle"
L install >/dev/null 2>&1; rc=$?
check "no record of this boot's decision: staged, but nothing made up for the state file" test $rc = 1 -a "$(cat /data/glassworks-pub/frame-bundle)" = "$before"
L boot; check "...it loads at the next boot all the same" test "$(prop ro.glassworks.frame.version)" = 2.0.5
mv /data/glassworks/bundle/current /data/glassworks/bundle/old
check "(setup: the loaded bundle is moved aside)" test ! -e /data/glassworks/bundle/current -a -d /data/glassworks/bundle/old
L boot; check "a promotion cut short between its renames: the loaded bundle comes back" test "$(prop ro.glassworks.frame.version)" = 2.0.5 -a -d /data/glassworks/bundle/current
echo 2.0.4 > /data/glassworks/bundle/bad-agent
check "(setup: a marker for 2.0.4 is there)" test "$(cat /data/glassworks/bundle/bad-agent)" = 2.0.4
rm -f "$T/props/test.agent"; GW_LOADER_ONCE=1 L run agent
check "a bad-script marker written for another bundle doesn't count" test "$(prop test.agent)" = "bundle-script|bundle 2.0.5|/data/glassworks/bundle/current"
rm -f /data/glassworks/bundle/bad-agent

B=/data/glassworks/bundle
mkframe "$T/f8" 2.0.6 "$T/key.pem" --file "services/agent.sh=$T/svc-agent.sh:0700"
stage_frame "$T/f8/frame-bundle"; install_frame >/dev/null; mv "$B/current" "$B/old"; echo "$B/old" > "$T/mv-fail"
L boot
check "the loaded bundle can't be put back from old: nothing is promoted" test -d "$B/old" -a -d "$B/next" -a ! -e "$B/current"
rm -f "$T/mv-fail"; L boot
check "...the next boot puts it back and promotes" test "$(prop ro.glassworks.frame.version)" = 2.0.6 -a ! -e "$B/old" -a ! -e "$B/next"
mkframe "$T/f9" 2.0.7 "$T/key.pem" --file "services/agent.sh=$T/svc-agent.sh:0700"
stage_frame "$T/f9/frame-bundle"; install_frame >/dev/null; printf '%s\n' "$B/next" "$B/old" > "$T/mv-fail"
L boot
check "move-in and rollback both fail: old and the staged bundle survive" test -d "$B/old" -a -d "$B/next" -a ! -e "$B/current"
rm -f "$T/mv-fail"; L boot
check "...the next boot restores and promotes" test "$(prop ro.glassworks.frame.version)" = 2.0.7 -a ! -e "$B/old" -a ! -e "$B/next"

mkdir -p /data/glassworks/dev; printf '#!/bin/sh\nsetprop test.agent "dev|${GW_SOURCE}|${GW_DEV}"\n' > /data/glassworks/dev/agent.sh
rm -f "$T/props/test.agent"; GW_LOADER_ONCE=1 L run agent
check "a developer script without the marker is ignored: the bundle's runs" test "$(prop test.agent)" = "bundle-script|bundle 2.0.7|/data/glassworks/bundle/current"
touch /data/glassworks/dev/override
GW_LOADER_ONCE=1 L run agent; check "...with the root-made marker it runs" test "$(prop test.agent)" = "dev|dev|1"
rm -f /data/glassworks/dev/override

sed -i 's/^version 2000007$/version 2000097/' "$B/current/manifest"
check "(setup: the loaded manifest says 2000097 now, and its signature no longer checks)" \
  sh -c 'grep -qx "version 2000097" "$1/manifest" && ! /system/bin/sh "$2" check "$1" >/dev/null 2>&1' _ "$B/current" "$TBL/glassworks_loader.sh"
mkframe "$T/f10" 2.0.8 "$T/key.pem" --file "services/agent.sh=$T/svc-agent.sh:0700"
stage_frame "$T/f10/frame-bundle"; s=$(install_frame)
check "a loaded manifest whose signature fails can't raise the floor" has "$s" "ok: frame bundle 2.0.8 staged"
L boot; check "...and the update loads" test "$(prop ro.glassworks.frame.version)" = 2.0.8 -a "$(cat "$B/version")" = 2000008
mkframe "$T/f11" 2.0.9 "$T/key.pem" --file "services/agent.sh=$T/svc-agent.sh:0700"
stage_frame "$T/f11/frame-bundle"; install_frame >/dev/null; echo "$B/version.tmp" > "$T/mv-fail"
L boot
check "the floor can't be recorded: the promotion is undone" test "$(prop ro.glassworks.frame.version)" = 2.0.8 -a "$(cat "$B/version")" = 2000008
check "...2.0.9 stays staged" test "$(L check "$B/next")" = "2.0.9 2000009" -a ! -e "$B/old"
rm -f "$T/mv-fail"; L boot
check "...and loads once it can" test "$(prop ro.glassworks.frame.version)" = 2.0.9 -a "$(cat "$B/version")" = 2000009

# The frame release's own bundle (frame-bundle.sh, as release.yml runs it), unpacked from its zip as the
# app will: it installs and loads.
mkdir -p "$T/fakert/lib"; cp "$R/frames/aqara-s1-plus/tools/radio/sisdk/zigbeed.sh" "$T/fakert/"
for l in ld-linux-armhf.so.3 libc.so.6 libcpc.so.3; do echo stub > "$T/fakert/lib/$l"; done
chmod 0755 "$T/fakert/zigbeed.sh" "$T/fakert/lib/ld-linux-armhf.so.3"
# the radio dir: the boot image's otbr-agent and ot-ctl, and a stand-in for hotspot-name/build.sh's overlay (no SDK here)
mkdir -p "$T/radio-in"; cp "$TB/otbr-agent" "$TB/ot-ctl" "$T/radio-in/"; printf 'overlay-release' > "$T/radio-in/hotspot-name.apk"
mkdir -p "$T/radio-nohn"; cp "$TB/otbr-agent" "$TB/ot-ctl" "$T/radio-nohn/"
out=$(bash "$R/frames/aqara-s1-plus/tools/radio/frame-bundle.sh" 3.0.0 "$T/key.pem" "$T/relnohn" "$T/radio-nohn" "$T/fakert" 2>&1 >/dev/null); rc=$?
check "a radio dir without the hotspot name overlay is refused before signing (the agent would undo the name)" \
  test $rc != 0 -a ! -e "$T/relnohn"
check "...saying which file" has "$out" "hotspot-name.apk is missing"
cp -r "$T/fakert" "$T/fakert-noexec"; chmod 0644 "$T/fakert-noexec/lib/ld-linux-armhf.so.3"
bash "$R/frames/aqara-s1-plus/tools/radio/frame-bundle.sh" 3.0.0 "$T/key.pem" "$T/relnx" "$T/radio-in" "$T/fakert-noexec" >/dev/null 2>&1
check "a runtime loader without its execute bit is refused before signing" test $? != 0 -a ! -e "$T/relnx"
cp -r "$T/fakert" "$T/fakert-noexec2"; chmod 0644 "$T/fakert-noexec2/zigbeed.sh"
out=$(bash "$R/frames/aqara-s1-plus/tools/radio/frame-bundle.sh" 3.0.0 "$T/key.pem" "$T/relnx2" "$T/radio-in" "$T/fakert-noexec2" 2>&1 >/dev/null); rc=$?
check "...so is a launcher without it" test $rc != 0 -a ! -e "$T/relnx2"
check "...saying which file" has "$out" "zigbeed.sh is not executable"
cp -r "$T/fakert" "$T/fakert-nolib"; rm "$T/fakert-nolib/lib/libcpc.so.3"
out=$(bash "$R/frames/aqara-s1-plus/tools/radio/frame-bundle.sh" 3.0.0 "$T/key.pem" "$T/relbad" "$T/radio-in" "$T/fakert-nolib" 2>&1 >/dev/null); rc=$?
check "a Silicon Labs runtime without a library is refused" test $rc != 0
check "...saying which file" has "$out" "lib/libcpc.so.3 is missing"
check "...before anything is signed or written (no output dir)" test ! -e "$T/relbad"
for d in rel rel2; do
  SOURCE_DATE_EPOCH=1800000000 bash "$R/frames/aqara-s1-plus/tools/radio/frame-bundle.sh" 3.0.0 "$T/key.pem" "$T/$d" "$T/radio-in" \
    "$T/fakert" "$TB/glassworks_bundle.pub" >/dev/null 2>&1
done
out=$(bash "$R/frames/aqara-s1-plus/tools/radio/frame-bundle.sh" 3.0.0 "$T/other.pem" "$T/relkey" "$T/radio-in" "$T/fakert" "$TB/glassworks_bundle.pub" 2>&1 >/dev/null); rc=$?
check "signed with a key panels don't trust: refused, no release zip" test $rc != 0 -a ! -e "$T/relkey/frame-bundle-aqara-s1-plus-3.0.0.zip"
check "...saying so" has "$out" "does not verify"
check "the release's frame bundle builds and checks with the project key" test -s "$T/rel/frame-bundle-aqara-s1-plus-3.0.0.zip"
check "...the same inputs give the same zip, byte for byte" cmp -s "$T/rel/frame-bundle-aqara-s1-plus-3.0.0.zip" "$T/rel2/frame-bundle-aqara-s1-plus-3.0.0.zip"
check "...its created time is SOURCE_DATE_EPOCH's" grep -qx "created 2027-01-15T08:00:00Z" "$T/rel/frame-bundle/manifest"
python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$T/rel/frame-bundle-aqara-s1-plus-3.0.0.zip" "$T/relx"
stage_frame "$T/relx"; s=$(install_frame)
check "...its zip, unpacked as the app will, stages" has "$s" "ok: frame bundle 3.0.0 staged"
L boot
check "...and loads at boot" test "$(prop ro.glassworks.frame.version)/$(prop sys.glassworks.bundle.state)" = "3.0.0/ok 3.0.0"
check "...with its boot animation mounted" has "$(tail -1 "$T/mounts")" "/data/glassworks/bundle/current/bootanimation.zip"
check "...with the release's Thread binaries, byte for byte" cmp -s "$TB/otbr-agent" /data/glassworks/bundle/current/otbr-agent
check "...otbr-agent executable" test "$(stat -c %a /data/glassworks/bundle/current/otbr-agent)" = 700
check "...ot-ctl too" cmp -s "$TB/ot-ctl" /data/glassworks/bundle/current/ot-ctl
check "...ot-ctl executable" test "$(stat -c %a /data/glassworks/bundle/current/ot-ctl)" = 700
check "...with the hotspot name overlay where the agent looks for it, readable" \
  test "$(cat /data/glassworks/bundle/current/hotspot-name.apk)/$(stat -c %a /data/glassworks/bundle/current/hotspot-name.apk)" = "overlay-release/644"
check "...with the Silicon Labs launcher the frame agent looks for" cmp -s "$T/fakert/zigbeed.sh" /data/glassworks/bundle/current/sisdk/zigbeed.sh
check "...the runtime's loader stays executable" test "$(stat -c %a /data/glassworks/bundle/current/sisdk/lib/ld-linux-armhf.so.3)" = 755
check "...and every slot script of the release" test -s /data/glassworks/bundle/current/services/agent.sh -a -s /data/glassworks/bundle/current/services/netwatch.sh \
  -a -s /data/glassworks/bundle/current/services/firstboot.sh -a -s /data/glassworks/bundle/current/services/update.sh

reset_frame

# ----------------------------------------------------------------------- frame agent v5 (frame loader)
# From here on `helper` runs glassworks_agent.sh, the frame agent in the loader's agent slot. A test bundle is a
# make-bundle.py output dir passed as GW_BUNDLE ($CUR), as the loader passes the checked bundle.
CUR=""
reset_radio() { rm -rf /data/s1kiosk/radio; rm -f "${T:?}"/props/sys.s1kiosk.* "${T:?}"/props/sys.glassworks.* "${T:?}/restarts" "${T:?}/log"; CUR=""; }
helper() { # env... : run the helper for a couple of loops
  env GWA_ONCE=${LOOPS:-2} GWA_SLEEP=0 GW_BOOT="$TB" GW_BUNDLE="$CUR" "$@" /system/bin/sh "$TB/glassworks_agent.sh" >/dev/null 2>&1
}
echo "--- glassworks_agent.sh: names"
reset_radio; rm -f "${T:?}"/props/persist.s1kiosk.* "${T:?}"/props/persist.glassworks.*
echo off > "$T/props/persist.glassworks.radio_mode"
LOOPS=1 helper
check "v5 reports on sys.glassworks.*" has "$(prop sys.glassworks.radio.status)" "ok off"
check "...contract version 5" test "$(prop sys.glassworks.agent.version)" = 5
check "...and sets no sys.s1kiosk.* property" test -z "$(ls "$T/props" | grep '^sys\.s1kiosk\.')"
check "...source boot" test "$(prop sys.glassworks.agent.source)" = boot
check "...the agent's own facts under sys.glassworks.agent.*, not radio" test -z "$(prop sys.glassworks.radio.version)$(prop sys.glassworks.radio.source)"

# -------------------------------------------------------------------------------------- firmware
echo "--- radio firmware"
reset_radio; echo off > "$T/props/persist.glassworks.radio_mode"
LOOPS=1 helper
check "fw idle at start" has "$(prop sys.glassworks.radio.fw)" "idle stock"
check "stock target published (unpinned, APP from the name)" has "$(prop sys.glassworks.radio.fw_targets)" "stock:${STOCK_SHA:0:12}:0017"
req() { # content [age-seconds]
  printf '%s\n' "$1" > "$APP/files/radio/fw-request"; chown 10050:10050 "$APP/files/radio/fw-request"
  [ -n "${2:-}" ] && touch -d "@$(( $(date +%s) - $2 ))" "$APP/files/radio/fw-request"
}
req "stock ${STOCK_SHA:0:12}"; : > "$T/flashed"; echo 0016 > "$T/radio-app"
LOOPS=1 helper
check "stock flash requested: flashed with Aqara's cpcd" test "$(cat "$T/flashed")" = "$STOCK"
check "...verified over CPC" has "$(prop sys.glassworks.radio.fw)" "ok stock: APP 0017 (VERSION:FAKE-RCP)"
check "...request taken" test ! -e "$APP/files/radio/fw-request"
check "...no in-progress marker left" test ! -e /data/s1kiosk/radio/fw/in-progress
check "...stock reference recorded" grep -q "^stock $STOCK_SHA 0017 " /data/s1kiosk/radio/fw/stock.ref
req "stock ${STOCK_SHA:0:12}" 600; : > "$T/flashed"; LOOPS=1 helper
check "stale request (10 min) ignored" test ! -s "$T/flashed"
check "...and said so" has "$(prop sys.glassworks.radio.fw)" "too old"
req "stock 000000000000"; LOOPS=1 helper
check "request naming another hash refused" has "$(prop sys.glassworks.radio.fw)" "not the one requested"
req "stock"; LOOPS=1 helper
check "request without a hash refused" has "$(prop sys.glassworks.radio.fw)" "names no image hash"
printf 'stock %s\n' "${STOCK_SHA:0:12}" > "$T/req-elsewhere"; chown 10050 "$T/req-elsewhere"
ln -s "$T/req-elsewhere" "$APP/files/radio/fw-request"; : > "$T/flashed"; LOOPS=1 helper
check "symlinked request refused" test ! -s "$T/flashed"; rm -f "$APP/files/radio/fw-request"
req "stock ${STOCK_SHA:0:12}"; chown 10077 "$APP/files/radio/fw-request"; : > "$T/flashed"; LOOPS=1 helper
check "request owned by another app refused" test ! -s "$T/flashed"; rm -f "$APP/files/radio/fw-request"

# A bundle image that flashes but reports the wrong version: rolled back to stock.
gbl "$T/new.gbl"; NEW_SHA=$(sha256sum "$T/new.gbl" | cut -d' ' -f1)
# (same bytes as the stock fake would collide: make it differ)
printf 'x' | dd of="$T/new.gbl" bs=1 seek=100 conv=notrunc 2>/dev/null; NEW_SHA=$(sha256sum "$T/new.gbl" | cut -d' ' -f1)
echo "$NEW_SHA 9999" >> "$T/fw-apps"
mkframe "$T/b4" 1.3.0 "$T/key.pem" --file "otbr-agent=$TB/otbr-agent:0700" --file "fw/new.gbl=$T/new.gbl:0644" \
  --firmware "test-rcp=fw/new.gbl:0018:Test RCP"
CUR=$T/b4/frame-bundle
LOOPS=1 helper
check "bundle firmware listed" has "$(prop sys.glassworks.radio.fw_targets)" "test-rcp:${NEW_SHA:0:12}:0018"
req "test-rcp ${NEW_SHA:0:12}"; : > "$T/flashed"; LOOPS=1 helper
check "wrong version after flash: rolled back to stock" has "$(prop sys.glassworks.radio.fw)" "rolled back to stock (APP 0017)"
check "...result kept after the helper restarts" has "$(LOOPS=1 helper; prop sys.glassworks.radio.fw)" "rolled back to stock"
check "...flashed the new image twice, then stock" test "$(wc -l < "$T/flashed")" = 3 -a "$(tail -1 "$T/flashed")" = "$STOCK"
sed -i "s/^$NEW_SHA 9999/$NEW_SHA 0018/" "$T/fw-apps"
req "test-rcp ${NEW_SHA:0:12}"; : > "$T/flashed"; touch "$T/cpcd-fail-once"; LOOPS=1 helper
check "one failed transfer is retried, then ok" has "$(prop sys.glassworks.radio.fw)" "ok test-rcp: APP 0018"
gbl "$T/btl.gbl" f50909f5
mkframe "$T/b5" 1.3.1 "$T/key.pem" --file "fw/btl.gbl=$T/btl.gbl:0644" --firmware "btl=fw/btl.gbl:0018:x"
CUR=$T/b5/frame-bundle; LOOPS=1 helper
check "an image with a bootloader tag is never offered" test "$(prop sys.glassworks.radio.fw_targets)" = "stock:${STOCK_SHA:0:12}:0017"
req "btl $(sha256sum "$T/btl.gbl" | cut -c 1-12)"; : > "$T/flashed"; LOOPS=1 helper
check "...nor flashed" test ! -s "$T/flashed"
check "...and the status says why" has "$(prop sys.glassworks.radio.fw)" "replace the bootloader"
gbl "$T/trunc.gbl" "" truncate
mkframe "$T/b6" 1.3.2 "$T/key.pem" --file "fw/t.gbl=$T/trunc.gbl:0644" --firmware "trunc=fw/t.gbl:0018:x"
CUR=$T/b6/frame-bundle; req "trunc $(sha256sum "$T/trunc.gbl" | cut -c 1-12)"; : > "$T/flashed"; rm -f "$T/props/sys.glassworks.radio.fw"; LOOPS=1 helper
check "a truncated image is refused" test ! -s "$T/flashed"
check "...by its format, in a fresh result" has "$(prop sys.glassworks.radio.fw)" "error trunc"
echo "stock $STOCK_SHA 1" > /data/s1kiosk/radio/fw/in-progress; LOOPS=1 helper
check "an interrupted flash is reported at start" has "$(prop sys.glassworks.radio.fw)" "flash was interrupted"
rm -f /data/s1kiosk/radio/fw/in-progress
python3 - "$T/huge.gbl" <<'PY'
import struct, sys
d = struct.pack('<II', 0x03A617EB, 8) + bytes(8) + struct.pack('<II', 0xF40A0AF4, 0xFFFFFFF8) + bytes(64)
open(sys.argv[1], 'wb').write(d)
PY
mkframe "$T/b9" 1.3.3 "$T/key.pem" --file "fw/h.gbl=$T/huge.gbl:0644" --firmware "huge=fw/h.gbl:0018:x"
CUR=$T/b9/frame-bundle; rm -f "$T/props/sys.glassworks.radio.fw_targets"
timeout 60 env GWA_ONCE=1 GWA_SLEEP=0 GW_BUNDLE="$CUR" /system/bin/sh "$TB/glassworks_agent.sh" >/dev/null 2>&1
check "a GBL tag length of 0xfffffff8 is refused (no endless loop)" test $? != 124 -a "$(prop sys.glassworks.radio.fw_targets)" = "stock:${STOCK_SHA:0:12}:0017"

# Aqara's zigbee_cpcd starting mid-flash is stopped again
req "stock ${STOCK_SHA:0:12}"; : > "$T/flashed"; : > "$T/log"; touch "$T/cpcd-start-aqara"; LOOPS=1 helper; rm -f "$T/cpcd-start-aqara"
check "zigbee_cpcd started during a flash is stopped" grep -q "stopped zigbee_cpcd during the flash" "$T/log"
check "...and the flash still completes" has "$(prop sys.glassworks.radio.fw)" "ok stock"
# another cpcd running: no flash
cp /bin/sleep "$T/cpcd"; "$T/cpcd" 300 & CP=$!
req "stock ${STOCK_SHA:0:12}"; : > "$T/flashed"; LOOPS=1 helper; kill $CP
check "no flash while another cpcd runs" test ! -s "$T/flashed"
check "...and said so" has "$(prop sys.glassworks.radio.fw)" "a cpcd process is still using the radio"

# Progress while flashing: cpcd's own "progress:<n>", or the seconds so far when it printed none
req "stock ${STOCK_SHA:0:12}"; : > "$T/setprop.log"; touch "$T/cpcd-slow"; LOOPS=1 helper
fwlog=$(sed -n 's/^sys.glassworks.radio.fw=//p' "$T/setprop.log")
check "flash progress: cpcd's percentage" has "$fwlog" "flashing stock: 40% (do not unplug the panel)"
req "stock ${STOCK_SHA:0:12}"; : > "$T/setprop.log"; touch "$T/cpcd-quiet"; LOOPS=1 helper
fwlog=$(sed -n 's/^sys.glassworks.radio.fw=//p' "$T/setprop.log")
check "no progress from cpcd: elapsed seconds instead" grep -qE '^flashing stock: [0-9]+ s \(do not unplug the panel\)$' <<<"$fwlog"
check "...never an empty percentage" hasnt_pct "$fwlog"
check "...and the flash still completes" has "$(prop sys.glassworks.radio.fw)" "ok stock"
rm -f "$T/cpcd-slow" "$T/cpcd-quiet"

# #645: the in-progress marker is written (and read back) before cpcd starts, or nothing is flashed
check "the flash marker is in place when cpcd starts" grep -q "^stock $STOCK_SHA [0-9]*\$" "$T/marker-at-flash"
mkdir -p /data/s1kiosk/radio/fw/in-progress.tmp   # the marker can't be written, as on a full /data
req "stock ${STOCK_SHA:0:12}"; : > "$T/flashed"; LOOPS=1 helper
check "no flash marker: nothing flashed" test ! -s "$T/flashed" -a ! -e /data/s1kiosk/radio/fw/in-progress
check "...the status says why" has "$(prop sys.glassworks.radio.fw)" "error stock: cannot write the flash marker"
check "...and supervision goes on in the same pass" has "$(prop sys.glassworks.radio.status)" "ok off"
rmdir /data/s1kiosk/radio/fw/in-progress.tmp
# #645: a cpcd stopped at the timeout is gone before the retry starts the next one on the SPI device
req "stock ${STOCK_SHA:0:12}"; : > "$T/flashed"; rm -f "$T/cpcd-overlap"; echo linger > "$T/cpcd-hang-once"
LOOPS=1 helper GWA_FW_TIMEOUT=2
check "a flash that times out: the retry waits for the stopped cpcd to exit" test ! -e "$T/cpcd-overlap" -a "$(wc -l < "$T/flashed")" = 2
check "...and then completes" has "$(prop sys.glassworks.radio.fw)" "ok stock"
req "stock ${STOCK_SHA:0:12}"; : > "$T/flashed"; rm -f "$T/cpcd-overlap"; echo ignore > "$T/cpcd-hang-once"
LOOPS=1 helper GWA_FW_TIMEOUT=2 GWA_FW_TERM_WAIT=10
check "...one that ignores TERM is killed before the retry" test ! -e "$T/cpcd-overlap" -a "$(wc -l < "$T/flashed")" = 2
check "...and then completes" has "$(prop sys.glassworks.radio.fw)" "ok stock"
pkill -f "sleep 1000"

# an interrupted flash: stock is restored before any stack starts
echo zigbee_tcp > "$T/props/persist.glassworks.radio_mode"; echo aqara > "$T/props/persist.glassworks.radio_zigbee"
echo "RADIO_ALLOW=any" > /data/s1kiosk/radio/radio.conf
echo "test-rcp $NEW_SHA 1" > /data/s1kiosk/radio/fw/in-progress; : > "$T/flashed"; echo garbage > "$T/radio-app"
LOOPS=1 helper
check "interrupted flash: stock restored at start" test "$(head -1 "$T/flashed")" = "$STOCK"
check "...said so" has "$(prop sys.glassworks.radio.fw)" "interrupted; restored stock (APP 0017)"
check "...then the mode starts" test "$(prop init.svc.zigbee_zigbeed)" = running -a ! -e /data/s1kiosk/radio/fw/in-progress
pkill -f "sleep 1000"
echo "test-rcp $NEW_SHA 1" > /data/s1kiosk/radio/fw/in-progress; touch "$T/cpcd-fail-always"; setprop ctl.stop zigbee_zigbeed; setprop ctl.stop zigbee_cpcd
: > "$T/flashed"; LOOPS=1 helper
check "restore fails: radio held off" has "$(prop sys.glassworks.radio.status)" "radio firmware missing after an interrupted flash"
check "...no stack started" test "$(prop init.svc.zigbee_cpcd)" != running -a "$(prop init.svc.zigbee_zigbeed)" != running
LOOPS=1 helper; : > "$T/flashed"; LOOPS=1 helper
check "...at most two automatic restores across restarts" test ! -s "$T/flashed" -a "$(cat /data/s1kiosk/radio/fw/restore-tries)" = 2
rm -f "$T/cpcd-fail-always"; req "stock ${STOCK_SHA:0:12}"; LOOPS=1 helper
check "...an explicit stock flash repairs it" test ! -e /data/s1kiosk/radio/fw/in-progress -a ! -e /data/s1kiosk/radio/fw/restore-tries
pkill -f "sleep 1000"
echo off > "$T/props/persist.glassworks.radio_mode"; rm -f /data/s1kiosk/radio/radio.conf

if [ -f /stock.gbl ]; then
  cp /stock.gbl "$STOCK"; LOOPS=1 helper
  check "the real stock GBL parses and is the pinned one" has "$(prop sys.glassworks.radio.fw_targets)" "stock:d7b79c6fab1e:0017"
  gbl "$STOCK"
fi

# --------------------------------------------------------------------------------- Zigbee stack
echo "--- Zigbee stack (Silicon Labs zigbeed)"
reset_radio
echo zigbee_tcp > "$T/props/persist.glassworks.radio_mode"; echo sisdk > "$T/props/persist.glassworks.radio_zigbee"
mkdir -p /data/s1kiosk/radio; echo "RADIO_ALLOW=any" > /data/s1kiosk/radio/radio.conf
LOOPS=1 helper
check "sisdk without a bundle: needs the runtime" has "$(prop sys.glassworks.radio.status)" "needs a radio bundle with its runtime"
check "...zigbee state no-runtime" test "$(prop sys.glassworks.radio.zigbee)" = "sisdk no-runtime"
check "...Aqara's zigbeed not started" test "$(prop init.svc.zigbee_zigbeed)" != running
# The runtime with a fake loader that just runs the binary, and a fake zigbeed that holds the pty.
mkdir -p "$T/rt/lib"; cp "$R/frames/aqara-s1-plus/tools/radio/sisdk/zigbeed.sh" "$T/rt/"
w "$T/rt/lib/ld-linux-armhf.so.3" <<'EOF'
#!/bin/sh
while [ "$1" != --argv0 ]; do shift; done; shift 2; exec "$@"
EOF
w "$T/fakezb" <<'EOF'
#!/bin/sh
echo "$*" > /t/zigbeed.args; pwd > /t/zigbeed.cwd
exec 3<> "$(readlink /tmp/ttyZigbeeNCP)"; exec sleep 1000
EOF
mkframe "$T/b7" 1.4.0 "$T/key.pem" --file "otbr-agent=$TB/otbr-agent:0700" --dir "sisdk=$T/rt"
CUR=$T/b7/frame-bundle
# RADIO_ALLOW=any: no iptables here (the firewall is unchanged since v3 and was verified on the panel)
echo "RADIO_ALLOW=any; SISDK_SHA256=$(sha256sum "$T/fakezb" | cut -d' ' -f1); SISDK_SIZE=$(stat -c %s "$T/fakezb")" > /data/s1kiosk/radio/radio.conf
touch /t/pty-host /t/pty-zb; ln -sf /t/pty-host /dev/ttyZigbeeNCP; ln -sf /t/pty-zb /tmp/ttyZigbeeNCP
LOOPS=1 helper
check "runtime present, binary missing: waiting for the download" has "$(prop sys.glassworks.radio.status)" "waiting for zigbeed 8.2.2"
check "...zigbee state tells the app what to fetch" has "$(prop sys.glassworks.radio.zigbee)" "sisdk missing 8.2.2"
check "...cpcd + socat wanted, Aqara's zigbeed not" test "$(prop init.svc.zigbee_cpcd)/$(prop init.svc.zigbee_socat)/$(prop init.svc.zigbee_zigbeed)" != "running/running/running" -a "$(prop init.svc.zigbee_cpcd)/$(prop init.svc.zigbee_socat)" = running/running
echo "not zigbeed" > "$APP/files/radio/zigbeed-8.2.2"; chown 10050 "$APP/files/radio/zigbeed-8.2.2"
LOOPS=1 helper
check "a wrong download is refused" has "$(prop sys.glassworks.radio.zigbee)" "sisdk bad"
check "...and not installed" test ! -e /data/s1kiosk/radio/sisdk/zigbeed-8.2.2
cp "$T/fakezb" "$APP/files/radio/zigbeed-8.2.2"; chown 10050 "$APP/files/radio/zigbeed-8.2.2"
printf '\xc1\x02\x0b\x0a\x52\x7e' > "$T/stale"; : > "$T/bridge.log"; rm -f "$T/drains"
LOOPS=3 helper
check "the pinned binary is installed root 0700" test "$(stat -c %a:%u /data/s1kiosk/radio/sisdk/zigbeed-8.2.2)" = 700:0
check "zigbeed runs on IID 1 and the pty, in its own state dir" test "$(cat "$T/zigbeed.cwd")" = /data/s1kiosk/radio/zigbeed-sisdk
check "...args" has "$(cat "$T/zigbeed.args")" "-c /dev/null -r spinel+cpc://cpcd_0?iid=1&iid-list=0 -p /tmp/ttyZigbeeNCP"
check "boot RSTACK drained before the bridge" test -s "$T/drains" -a ! -s "$T/stale"
check "bridge on :6638" has "$(cat "$T/bridge.log")" "TCP-LISTEN:6638"
check "...each client through the drain-on-connect relay" has "$(cat "$T/bridge.log")" "EXEC:/system/bin/sh /data/s1kiosk/radio/ezsp-relay.sh"
check "...root-only, draining the pty before it relays" test "$(stat -c %a:%u /data/s1kiosk/radio/ezsp-relay.sh)" = 600:0 -a \
  "$(grep -c -e '-u -T 0.2 FILE:/dev/ttyZigbeeNCP,raw,echo=0 - </dev/null' -e '^exec /vendor/bin/socat - FILE:/dev/ttyZigbeeNCP,raw,echo=0$' /data/s1kiosk/radio/ezsp-relay.sh)" = 2
check "status ok with EZSP 18" has "$(prop sys.glassworks.radio.status)" "ok zigbee_tcp: EZSP 18 on tcp:6638, Silicon Labs zigbeed 8.2.2"
check "zigbee state ready" test "$(prop sys.glassworks.radio.zigbee)" = "sisdk ready 8.2.2"
pkill -f fakezb; pkill -f "sleep 1000"
echo aqara > "$T/props/persist.glassworks.radio_zigbee"; printf 'xx' > "$T/stale"
LOOPS=1 helper
check "aqara stack: Aqara's zigbeed wanted again" test "$(prop init.svc.zigbee_zigbeed)" = running
check "...zigbee state aqara" test "$(prop sys.glassworks.radio.zigbee)" = aqara
pkill -f "sleep 1000"

# ------------------------------------------------------------------------- zigbee_router backup
# #645: the router host's join replaces the coordinator network in Aqara's zigbeed NVM, so the router
# bridge starts only once a complete copy of that NVM is in zigbee-nvm/.
echo "--- zigbee_router: NVM backup before the bridge (#645)"
reset_radio; pkill -f "sleep 1000"
echo zigbee_router > "$T/props/persist.glassworks.radio_mode"; echo aqara > "$T/props/persist.glassworks.radio_zigbee"
mkdir -p /data/s1kiosk/radio /data/zigbee_host; echo "RADIO_ALLOW=any" > /data/s1kiosk/radio/radio.conf
NVM=/data/zigbee_host/zigbeed_host_token.nvm; NB=/data/s1kiosk/radio/zigbee-nvm
touch /t/pty-host /t/pty-zb; ln -sf /t/pty-host /dev/ttyZigbeeNCP; ln -sf /t/pty-zb /tmp/ttyZigbeeNCP; : > "$T/stale"
mkdir -p "$T/zb"; w "$T/zb/zigbeed" <<'EOF'
#!/bin/sh
# Aqara's zigbeed for pidof: holds its end of the pty pair
exec 3<> /t/pty-zb
while :; do sleep 1; done
EOF
"$T/zb/zigbeed" & ZBR=$!
# cp, for this section only: /t/cp-nvm makes a copy into zigbee-nvm/ fail half way ("partial": 16 bytes, then an
# error, as on a full /data) or end short with success ("short"). Every other copy is the real cp.
w "$T/bin/cp" <<'EOF'
#!/bin/sh
for a; do last=$a; done
case "$last:$(cat /t/cp-nvm 2>/dev/null)" in
  */zigbee-nvm/*:partial) head -c 16 "$1" > "$last"; exit 1 ;;
  */zigbee-nvm/*:short) head -c 16 "$1" > "$last"; exit 0 ;;
esac
exec /bin/cp "$@"
EOF
mkdir -p "$NB"; for d in 20000101-000001 20000101-000002 20000101-000003; do echo "old $d" > "$NB/zigbeed-$d.nvm"; done
echo manual > "$NB/my-backup.bin"; head -c 4096 /dev/urandom > "$NVM"
newest() { ls "$NB" | grep '^zigbeed-.*\.nvm$' | tail -1; }
: > "$T/bridge.log"; LOOPS=1 helper
check "zigbee_router: the NVM is backed up, then the bridge starts on 127.0.0.1:6639" test "$(grep -c 'TCP-LISTEN:6639' "$T/bridge.log")" = 1
check "...the newest backup is the NVM" cmp -s "$NVM" "$NB/$(newest)"
check "...root 0600" test "$(stat -c %a:%u "$NB/$(newest)")" = 600:0
check "...the last three kept, the manual one untouched" test "$(ls "$NB" | grep -c '^zigbeed-.*\.nvm$')" = 3 -a "$(cat "$NB/my-backup.bin")" = manual
check "...status ok" has "$(prop sys.glassworks.radio.status)" "ok zigbee_router"
pkill -f "sleep 1000"
kept=$(cd "$NB" && sha256sum -- * | sort); head -c 4096 /dev/urandom > "$NVM"
echo partial > "$T/cp-nvm"; : > "$T/bridge.log"; LOOPS=1 helper
check "a backup that fails half way (full /data): no router bridge" test ! -s "$T/bridge.log"
check "...the status says why" has "$(prop sys.glassworks.radio.status)" "error zigbee_router: no complete backup of the zigbeed NVM"
check "...every backup kept, no partial one added" test "$(cd "$NB" && sha256sum -- * | sort)" = "$kept"
echo short > "$T/cp-nvm"; LOOPS=1 helper
check "a copy that ends short without an error: no bridge either, backups as they were" test ! -s "$T/bridge.log" -a "$(cd "$NB" && sha256sum -- * | sort)" = "$kept"
echo partial > "$T/cp-nvm"; : > "$T/log"
timeout 120 env GWA_ONCE=25 GWA_SLEEP=0 GW_BOOT="$TB" GW_BUNDLE="$CUR" /system/bin/sh "$TB/glassworks_agent.sh" >/dev/null 2>&1
check "a backup failing for 25 passes isn't counted: no 'giving up', the agent goes on" test $? != 124 -a -z "$(grep 'giving up' "$T/log")"
check "...still waiting, with the reason" has "$(prop sys.glassworks.radio.status)" "error zigbee_router: no complete backup of the zigbeed NVM (/data full?): router waits"
: > "$T/setprop.log"; LOOPS=60 helper GWA_SLEEP=0.2 & AG=$!
sleep 4; rm -f "$T/cp-nvm"; wait $AG
check "space again in the same process: the backup is retried" grep -q 'router waits' "$T/setprop.log"
check "...then the bridge starts" test "$(grep -c 'TCP-LISTEN:6639' "$T/bridge.log")" = 1
check "...status ok" has "$(prop sys.glassworks.radio.status)" "ok zigbee_router"
check "...the newest backup is the NVM as it is now" cmp -s "$NVM" "$NB/$(newest)"
kill $ZBR 2>/dev/null; pkill -f "sleep 1000"; rm -rf /data/zigbee_host "$T/zb"; rm -f /dev/ttyZigbeeNCP /tmp/ttyZigbeeNCP
/bin/rm -f "$T/bin/cp" "$T/cp-nvm"; hash -r   # this shell may have hashed cp to the shim
echo off > "$T/props/persist.glassworks.radio_mode"; rm -f /data/s1kiosk/radio/radio.conf

# ---------------------------------------------------------------------------------- EZSP bridge
# The real thing: real socat as Aqara's, a pty pair like zigbee_socat's, and a fake zigbeed (python
# named zigbeed, so pidof finds it) that echoes what it gets and writes "stale" bytes on request, the
# way zigbeed's ASH layer leaves an ERROR frame in the pty after a client went away.
echo "--- EZSP bridge: drain on connect (real socat)"
reset_radio; pkill -f "sleep 1000"
printf '#!/bin/sh\nexec /usr/bin/socat "$@"\n' | w /vendor/bin/socat
echo zigbee_tcp > "$T/props/persist.glassworks.radio_mode"; echo aqara > "$T/props/persist.glassworks.radio_zigbee"
mkdir -p /data/s1kiosk/radio; echo "RADIO_ALLOW=any" > /data/s1kiosk/radio/radio.conf
rm -f /dev/ttyZigbeeNCP /tmp/ttyZigbeeNCP
/usr/bin/socat PTY,link=/dev/ttyZigbeeNCP,raw,echo=0 PTY,link=/tmp/ttyZigbeeNCP,raw,echo=0 >/dev/null 2>&1 &
i=0; while [ ! -e /tmp/ttyZigbeeNCP ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
cat > "$T/fakezb.py" <<'PY'
import os, time, tty
fd = os.open('/tmp/ttyZigbeeNCP', os.O_RDWR | os.O_NOCTTY); tty.setraw(fd); os.set_blocking(fd, False)
def put(b):
    while b:
        try: b = b[os.write(fd, b):]
        except BlockingIOError: time.sleep(0.01)
got = open('/t/zb-got', 'ab', buffering=0)
while True:
    if os.path.exists('/t/zb-emit'):
        d = open('/t/zb-emit', 'rb').read(); os.unlink('/t/zb-emit'); put(d)
    try: b = os.read(fd, 4096)
    except (BlockingIOError, OSError): b = b''
    if b: got.write(b); put(b)
    else: time.sleep(0.02)
PY
cat > "$T/client.py" <<'PY'
import os, socket, sys, time
payload = bytes([0x1a, 0xc0, 0x38, 0xbc, 0x7e, 0x11, 0x13, 0x7d, 0x00, 0xff, 0x0d, 0x0a]) * 50 + os.urandom(3000)
open(sys.argv[1], 'wb').write(payload)
s = socket.create_connection(('127.0.0.1', int(sys.argv[2]) if len(sys.argv) > 2 else 6638), timeout=20)
s.sendall(payload)   # at once, before the relay runs: these must wait for it, not be drained
buf, end = b'', time.time() + 40
s.settimeout(1)
while time.time() < end and len(buf) < len(payload) + 64:
    try: d = s.recv(65536)
    except socket.timeout:
        if len(buf) >= len(payload): break
        continue
    if not d: break
    buf += d
s.close()
print('ok' if buf == payload else 'got %d bytes, want %d; starts %s' % (len(buf), len(payload), buf[:12].hex()))
PY
cp -L /usr/bin/python3 "$T/bin/zigbeed"; : > "$T/zb-got"
"$T/bin/zigbeed" "$T/fakezb.py" & ZBP=$!
sleep 2
LOOPS=3 helper
check "real bridge up through the relay" has "$(prop sys.glassworks.radio.status)" "ok zigbee_tcp: EZSP on tcp:6638"
session() { # stale bytes into the pty, then a client that sends at once
  : > "$T/zb-got"; printf '\x1a\xc0\x51\x02\x9c\x7e' > "$T/zb-emit"
  i=0; while [ -e "$T/zb-emit" ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done; sleep 0.5
  python3 "$T/client.py" "$T/sent" "${1:-6638}"
}
r=$(session); check "stale bytes in the pty never reach the next client; its own bytes come back intact" test "$r" = ok; [ "$r" = ok ] || echo "    $r"
check "...and zigbeed got exactly the client's bytes" cmp -s "$T/sent" "$T/zb-got"
sleep 3   # the single slot (max-children=1) frees once the session's processes are gone
r=$(session); check "the next client: the same" test "$r" = ok; [ "$r" = ok ] || echo "    $r"
check "...and zigbeed got exactly its bytes" cmp -s "$T/sent" "$T/zb-got"
# Who is connected (#217), from this container's own socket tables: a client holding the bridge's slot.
sleep 3
python3 -c 'import socket, time; s = socket.create_connection(("127.0.0.1", 6638)); time.sleep(8)' & HOLD=$!
sleep 1; LOOPS=1 helper
check "connected now: the client in the kernel's table (real /proc/net/tcp)" has "$(prop sys.glassworks.radio.peer)" "127.0.0.1 "
kill $HOLD 2>/dev/null; wait $HOLD 2>/dev/null; sleep 1; LOOPS=1 helper
check "...and nobody once it has gone" test "$(prop sys.glassworks.radio.peer)" = none
# control: the same through a bridge without the relay (as before) does hand the stale bytes over
/usr/bin/socat TCP-LISTEN:6640,reuseaddr FILE:/dev/ttyZigbeeNCP,raw,echo=0 >/dev/null 2>&1 &
sleep 2; r=$(session 6640)
check "(control: without the relay the client gets the stale bytes first)" has "$r" "starts 1ac051029c7e"
kill $ZBP 2>/dev/null; pkill -f /usr/bin/socat; pkill -f "sleep 1000"
cp "$T/socat-mock" /vendor/bin/socat; rm -f /dev/ttyZigbeeNCP /tmp/ttyZigbeeNCP "$T/bin/zigbeed"
echo off > "$T/props/persist.glassworks.radio_mode"; rm -f /data/s1kiosk/radio/radio.conf

# ------------------------------------------------------------------------------- connected now
# Fixture socket tables in the kernel's format (addresses as the little-endian panel prints them):
# 0A0200C0 = 192.0.2.10 (the panel), 140200C0 = 192.0.2.20, C87100CB = 203.0.113.200; 19EE = 6638.
echo "--- connected now (#217)"
reset_radio; mkdir -p /data/s1kiosk/radio "$T/net"; echo "RADIO_ALLOW=any" > /data/s1kiosk/radio/radio.conf
HDR='  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode'
HDR6='  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode'
tcp() { { echo "$HDR"; i=0; for l in "$@"; do printf '%4d: %s 00000000:00000000 00:00000000 00000000     0        0 %d 1 00000000 20 4 30 10 -1\n' $i "$l" $((10000 + i)); i=$((i + 1)); done; } > "$T/net/tcp"; }
tcp6() { { echo "$HDR6"; i=0; for l in "$@"; do printf '%4d: %s 00000000:00000000 00:00000000 00000000     0        0 %d 1 00000000 20 4 30 10 -1\n' $i "$l" $((20000 + i)); i=$((i + 1)); done; } > "$T/net/tcp6"; }
LISTEN='00000000:19EE 00000000:0000 0A'
peer() { LOOPS=${1:-1} helper GWA_PROC_NET="$T/net"; prop sys.glassworks.radio.peer; }
echo off > "$T/props/persist.glassworks.radio_mode"; tcp "$LISTEN" "0A0200C0:19EE 140200C0:C350 01"; tcp6
p=$(peer); check "off: nothing reported" test -z "$p" -a -e "$T/props/sys.glassworks.radio.peer"
echo zigbee_tcp > "$T/props/persist.glassworks.radio_mode"; tcp "$LISTEN"
check "coordinator, the bridge listening: nobody" test "$(peer)" = none
tcp "$LISTEN" "0A0200C0:19EE 140200C0:C350 01"; t0=$(date +%s)
p=$(peer 3)
check "a client connected: its address and when it was first seen" test "${p% *}" = 192.0.2.20 -a "${p#* }" -ge "$t0" -a "${p#* }" -le $((t0 + 10))
check "...written once while it stays" test "$(grep -c '^sys.glassworks.radio.peer=192.0.2.20 ' "$T/setprop.log")" = 1
check "...and never in the log" test -z "$(grep -e 192.0.2 -e 140200C0 "$T/log")"
check "...though its coming is" grep -q "EZSP client connected" "$T/log"
tcp "$LISTEN" "0A0200C0:19EE C87100CB:C351 01"
check "an address with the top bit set (203.0.113.200)" has "$(peer)" "203.0.113.200 "
tcp "$LISTEN" "0A0200C0:19EE 140200C0:C350 06" "0A0200C0:1F91 140200C0:C352 01" "0A0200C0:C353 140200C0:19EE 01"
check "closing (TIME_WAIT), another port, or a connection out to :6638: nobody" test "$(peer)" = none
tcp "$LISTEN"; tcp6 "0000000000000000FFFF00000A0200C0:19EE 0000000000000000FFFF0000140200C0:C354 01"
check "tcp6, IPv4-mapped: as IPv4" has "$(peer)" "192.0.2.20 "
tcp6 "B80D0120000000000000000010000000:19EE B80D0120000000000000000020000000:C355 01"
check "tcp6, IPv6: its 8 groups (2001:db8::20)" has "$(peer)" "2001:db8:0:0:0:0:0:20 "
tcp "$LISTEN" "0A0200C0:19EE 140200C0:C350 01" "0A0200C0:19EE C87100CB:C351 01"; tcp6
check "two in the table: one shown" test "$(peer | wc -w)" = 2
echo thread_router > "$T/props/persist.glassworks.radio_mode"
p=$(peer); check "another mode: cleared" test -z "$p"
pkill -f "sleep 1000"; rm -rf "$T/net"; rm -f /data/s1kiosk/radio/radio.conf
echo off > "$T/props/persist.glassworks.radio_mode"

# ------------------------------------------------------------------------------- Thread dataset
echo "--- Thread dataset hand-off"
echo thread_router > "$T/props/persist.glassworks.radio_mode"; echo "RADIO_ALLOW=any" > /data/s1kiosk/radio/radio.conf
echo 0e080000000000010000 > "$APP/files/radio/thread-dataset"; chown 10050 "$APP/files/radio/thread-dataset"
LOOPS=1 helper
check "dataset taken into the root dir and the app's copy removed" test "$(cat /data/s1kiosk/radio/thread-dataset 2>/dev/null)" = 0e080000000000010000 -a ! -e "$APP/files/radio/thread-dataset"
check "...a stored Thread network is reported (yes/no only)" test "$(prop sys.glassworks.radio.thread_stored)" = yes
echo child > "$T/ot-state"; printf 'Home "Net"\001\n' > "$T/ot-name"
LOOPS=1 helper
check "Thread router status: role and network name (sanitised)" test "$(prop sys.glassworks.radio.status)" = 'ok thread_router: joined as child, network "Home Net"'
echo router > "$T/ot-state"; echo ABCDEFGHIJKLMNOPQRSTUVWXYZ > "$T/ot-name"
LOOPS=1 helper
check "...promoted to router; the name is cut at 16" test "$(prop sys.glassworks.radio.status)" = 'ok thread_router: joined as router, network "ABCDEFGHIJKLMNOP"'
echo detached > "$T/ot-state"; rm -f "$T/ot-name"
LOOPS=1 helper
check "...detached, no name" has "$(prop sys.glassworks.radio.status)" "ok thread_router: detached: looking for network"
check "...and never more than the role and the name" test -z "$(grep '^sys.glassworks.radio' /t/setprop.log | grep -i -E 'key|pskc|extpanid|0e08')"
rm -f "$T/ot-state" "$T/ot-name"; pkill -f "sleep 1000"
mkfifo "$APP/files/radio/thread-dataset"; chown 10050 "$APP/files/radio/thread-dataset"
timeout 60 env GWA_ONCE=1 GWA_SLEEP=0 GW_BUNDLE="$CUR" /system/bin/sh "$TB/glassworks_agent.sh" >/dev/null 2>&1
check "a FIFO dataset never blocks the helper" test $? != 124
check "...and changes nothing" test "$(cat /data/s1kiosk/radio/thread-dataset)" = 0e080000000000010000
rm -f "$APP/files/radio/thread-dataset"; pkill -f "sleep 1000"
# #645: the hand-off is the only copy once taken: it stays until the new dataset is stored whole
handoff() { echo "$1" > "$APP/files/radio/thread-dataset"; chown 10050 "$APP/files/radio/thread-dataset"; }
mkdir /data/s1kiosk/radio/thread-dataset.tmp   # writing the new dataset fails, as on a full /data
handoff 0e080000000000020000; : > "$T/log"; LOOPS=1 helper
check "a new dataset that can't be written: the current one stays" test "$(cat /data/s1kiosk/radio/thread-dataset)" = 0e080000000000010000
check "...the hand-off is kept" test "$(cat /data/s1kiosk/radio/thread-dataset.in 2>/dev/null)" = 0e080000000000020000
check "...said so, not 'new Thread dataset'" test "$(grep -c 'cannot store the new Thread dataset' "$T/log")/$(grep -c 'new Thread dataset from the app' "$T/log")" = 1/0
pkill -f "sleep 1000"; handoff 0e080000000000030000; LOOPS=1 helper
check "...a newer hand-off waits in the app's files meanwhile" test -e "$APP/files/radio/thread-dataset" -a "$(cat /data/s1kiosk/radio/thread-dataset.in)" = 0e080000000000020000
pkill -f "sleep 1000"; rmdir /data/s1kiosk/radio/thread-dataset.tmp; LOOPS=1 helper
check "once it can be written: stored, the hand-off gone" test "$(cat /data/s1kiosk/radio/thread-dataset)" = 0e080000000000020000 -a ! -e /data/s1kiosk/radio/thread-dataset.in
check "...root 0600" test "$(stat -c %a:%u /data/s1kiosk/radio/thread-dataset)" = 600:0
pkill -f "sleep 1000"; LOOPS=1 helper
check "...then the newer one" test "$(cat /data/s1kiosk/radio/thread-dataset)" = 0e080000000000030000 -a ! -e "$APP/files/radio/thread-dataset"
pkill -f "sleep 1000"
# Leave discards a hand-off staged before it (pending in .in, or waiting in the app's files); a newer one stays
leave() { echo leave > "$APP/files/radio/thread-leave"; chown 10050 "$APP/files/radio/thread-leave"; }
mkdir /data/s1kiosk/radio/thread-dataset.tmp; handoff 0e080000000000050000; LOOPS=1 helper; pkill -f "sleep 1000"
handoff 0e080000000000060000; touch -d "@$(( $(date +%s) - 30 ))" "$APP/files/radio/thread-dataset"
rmdir /data/s1kiosk/radio/thread-dataset.tmp; leave; LOOPS=1 helper; pkill -f "sleep 1000"
check "Leave with a pending and an older staged hand-off: neither is stored" test ! -e /data/s1kiosk/radio/thread-dataset -a ! -e /data/s1kiosk/radio/thread-dataset.in -a ! -e "$APP/files/radio/thread-dataset"
check "...Leave reported ok" has "$(prop sys.glassworks.radio.thread_leave)" "ok "
handoff 0e080000000000070000; touch -d "@$(( $(date +%s) + 30 ))" "$APP/files/radio/thread-dataset"
leave; LOOPS=1 helper; pkill -f "sleep 1000"
check "...a hand-off newer than the Leave (a Join after it) is stored" test "$(cat /data/s1kiosk/radio/thread-dataset 2>/dev/null)" = 0e080000000000070000 -a ! -e /data/s1kiosk/radio/thread-dataset.in
check "...and the Leave's time is dropped once it is" test ! -e /data/s1kiosk/radio/thread-left
mkdir /data/s1kiosk/radio/thread-dataset.tmp; handoff 0e080000000000080000; touch -d "@$(( $(date +%s) + 30 ))" "$APP/files/radio/thread-dataset"
LOOPS=1 helper; pkill -f "sleep 1000"; leave; LOOPS=1 helper; pkill -f "sleep 1000"
check "a pending hand-off newer than a Leave survives it" test "$(cat /data/s1kiosk/radio/thread-dataset.in 2>/dev/null)" = 0e080000000000080000 -a ! -e /data/s1kiosk/radio/thread-dataset
rmdir /data/s1kiosk/radio/thread-dataset.tmp; LOOPS=1 helper; pkill -f "sleep 1000"
check "...and is stored once it can be" test "$(cat /data/s1kiosk/radio/thread-dataset 2>/dev/null)" = 0e080000000000080000
echo off > "$T/props/persist.glassworks.radio_mode"
handoff 0e080000000000090000; touch -d "@$(( $(date +%s) - 30 ))" "$APP/files/radio/thread-dataset"; leave; LOOPS=1 helper
check "Leave in Off: the dataset goes, an older staged hand-off waits in the app's files" test ! -e /data/s1kiosk/radio/thread-dataset -a -e "$APP/files/radio/thread-dataset"
echo thread_router > "$T/props/persist.glassworks.radio_mode"; : > "$T/log"; LOOPS=1 helper; pkill -f "sleep 1000"
check "...taken later in Thread router, it is discarded, never joined" test ! -e /data/s1kiosk/radio/thread-dataset -a ! -e /data/s1kiosk/radio/thread-dataset.in -a ! -e "$APP/files/radio/thread-dataset"
check "...and said so" grep -q 'staged before a Leave' "$T/log"
D=/data/s1kiosk/radio
( umask 077; echo 0e0800000000000a0000 > $D/thread-dataset.in ); rm -f $D/thread-dataset.in.t; LOOPS=1 helper; pkill -f "sleep 1000"
check "a pending hand-off without its time (agent stopped after the move), after a Leave: discarded" test ! -e $D/thread-dataset -a ! -e $D/thread-dataset.in
mkdir $D/thread-dataset.in.t; handoff 0e0800000000000b0000; touch -d "@$(( $(date +%s) - 30 ))" "$APP/files/radio/thread-dataset"
LOOPS=1 helper; pkill -f "sleep 1000"
check "...one whose time can't be written, after a Leave: discarded, not stored" test ! -e $D/thread-dataset -a ! -e $D/thread-dataset.in -a ! -e "$APP/files/radio/thread-dataset"
rm -f $D/thread-left; handoff 0e0800000000000c0000; LOOPS=1 helper; pkill -f "sleep 1000"; rmdir $D/thread-dataset.in.t
check "...with no Leave recorded, a hand-off without its time is stored" test "$(cat $D/thread-dataset 2>/dev/null)" = 0e0800000000000c0000
# (the cases below join: a stored dataset again, as a hand-off after the Leave above would store it)
( umask 077; echo 0e080000000000010000 > /data/s1kiosk/radio/thread-dataset ); rm -f /data/s1kiosk/radio/thread-left
# #645: otbr-agent failing to start shows its own error (JOINED is cleared before, not after, start_otbr)
CUR_KEEP=$CUR; CUR=""; chmod 644 "$TB/otbr-agent"; LOOPS=1 helper
check "otbr-agent that can't start: its error, not 'waiting' or ok" has "$(prop sys.glassworks.radio.status)" "error thread_router: otbr-agent not installed"
chmod 755 "$TB/otbr-agent"; CUR=$CUR_KEEP
# #645: failed joins count across loops (FAILS restarts with every pass whose processes are up)
echo thread > "$T/ot-fail"; : > "$T/ot-calls"; LOOPS=30 helper
check "a join that always fails stops after 21 tries" has "$(prop sys.glassworks.radio.status)" "error thread_router: joining failed 21 times"
check "...thread start was tried 21 times in 30 passes" test "$(grep -c '^thread$' "$T/ot-calls")" = 21
rm -f "$T/ot-fail"; pkill -f "sleep 1000"; LOOPS=1 helper
check "...a restart tries again" has "$(prop sys.glassworks.radio.status)" "ok thread_router"
pkill -f "sleep 1000"
mkdir -p /data/s1kiosk/radio/thread-router; echo settings > /data/s1kiosk/radio/thread-router/0_1.data
mkdir -p /data/s1kiosk/radio/thread; echo br > /data/s1kiosk/radio/thread/0_1.data
echo leave > "$APP/files/radio/thread-leave"; chown 10050 "$APP/files/radio/thread-leave"
LOOPS=1 helper
check "Leave Thread network (router mode): dataset and router settings deleted" test ! -e /data/s1kiosk/radio/thread-dataset -a ! -e /data/s1kiosk/radio/thread-router/0_1.data
check "...the border router's own network is kept" test -s /data/s1kiosk/radio/thread/0_1.data
check "...reported" has "$(prop sys.glassworks.radio.thread_leave)" "Thread dataset and router settings deleted"
check "...request taken" test ! -e "$APP/files/radio/thread-leave"
check "...and the router waits for a new dataset" has "$(prop sys.glassworks.radio.status)" "waiting for a Thread dataset"
pkill -f "sleep 1000"
echo off > "$T/props/persist.glassworks.radio_mode"
echo 0e08 > /data/s1kiosk/radio/thread-dataset; mkdir -p /data/s1kiosk/radio/thread-router
echo leave > "$APP/files/radio/thread-leave"; chown 10050 "$APP/files/radio/thread-leave"
LOOPS=1 helper
check "Leave Thread network works in Off too" test ! -e /data/s1kiosk/radio/thread-dataset -a ! -e /data/s1kiosk/radio/thread-router
LOOPS=1 helper
check "...and nothing is stored afterwards" test "$(prop sys.glassworks.radio.thread_stored)" = no
echo leave > "$APP/files/radio/thread-leave"; chown 10050 "$APP/files/radio/thread-leave"; LOOPS=1 helper
check "...and says so when nothing was stored" has "$(prop sys.glassworks.radio.thread_leave)" "no Thread network was stored"
ln -s /data/s1kiosk/radio/thread "$APP/files/radio/thread-leave"; LOOPS=1 helper
check "...a symlinked request is refused" has "$(prop sys.glassworks.radio.thread_leave)" "error" ; rm -f "$APP/files/radio/thread-leave"
check "...and deletes nothing" test -s /data/s1kiosk/radio/thread/0_1.data
rm -f /data/s1kiosk/radio/radio.conf

# ------------------------------------------------------------------- the old helper keeps step
# 0.4.3 panels run s1kiosk_radio.sh from the old radio bundle until the boot writer moves them (#528), so
# it carries the #645 fixes too: the functions they touch are the agent's, names aside (the cases above
# run the agent's copy). Goes with s1kiosk_radio.sh.
echo "--- s1kiosk_radio.sh: the #645 fixes, as in the agent"
fnbody() { awk -v f="$2" '$0 ~ "^"f"\\(\\)" {p=1} p {print} p && /^}/ {exit}' "$1"; }
for f in backup_nvm router_bridge children_ok num_in take_app_dataset thread_leave start_thread_router thread_router_join fw_stop_cpcd fw_flash_one fw_attempt fw_mark fw_do; do
  a=$(fnbody "$R/frames/aqara-s1-plus/tools/radio/glassworks_agent.sh" $f | sed 's/glassworks/s1kiosk/g; s/GWA_/S1R_/g; s/the agent/the helper/g')
  b=$(fnbody "$R/frames/aqara-s1-plus/tools/radio/s1kiosk_radio.sh" $f)
  check "old helper's $f is the agent's, names aside" test -n "$a" -a "$a" = "$b"
done
# Less the computers over USB adb (adb_*): the agent's alone, the old helper never had them.
a=$(sed -n '/^while true; do/,$p' "$R/frames/aqara-s1-plus/tools/radio/glassworks_agent.sh" | grep -v '^  adb_' | sed 's/glassworks/s1kiosk/g; s/GWA_/S1R_/g; s/the agent/the helper/g')
check "...and its main loop is the agent's" test -n "$a" -a "$a" = "$(sed -n '/^while true; do/,$p' "$R/frames/aqara-s1-plus/tools/radio/s1kiosk_radio.sh")"

# ------------------------------------------------------------------------------- app updates
echo "--- glassworks_update.sh"
cp "$R/frames/aqara-s1-plus/tools/root/glassworks_update.sh" "$TB/"
mkdir -p "$APP/files/update"; printf 'PK-apk' > "$APP/files/update/pending.apk"; chown -R 10050 "$APP/files/update"
echo 30200 > "$T/pm-version"
/system/bin/sh "$TB/glassworks_update.sh" >/dev/null 2>&1
check "update service installs through the safe copy" has "$(prop sys.glassworks.update.status)" "ok: sh.edm.s1kiosk 30200 -> 30300"
rm -f "$APP/files/update/pending.apk"; mkfifo "$APP/files/update/pending.apk"; chown 10050 "$APP/files/update/pending.apk"
timeout 30 /system/bin/sh "$TB/glassworks_update.sh" >/dev/null 2>&1
check "update service: a FIFO is refused, no hang" test $? != 124
check "...with an error status" has "$(prop sys.glassworks.update.status)" "error"
# A framework restart in the middle of the install (#248): pm fails, but the update may have gone in.
cp /bin/sleep "$T/bin/system_server"
upd() { # pm-mode: stage the APK, run the service, print its status
  pkill -x system_server; (system_server 2000 >/dev/null 2>&1 &); sleep 0.2
  rm -f "$APP/files/update/pending.apk"; printf 'PK-apk' > "$APP/files/update/pending.apk"; chown 10050 "$APP/files/update/pending.apk"
  echo 30200 > "$T/pm-version"; echo "$1" > "$T/pm-mode"; rm -f "$T/pm-down"   # PackageManager up at the start, whatever a case before left
  GWU_RESTART_WAIT=10 timeout 60 /system/bin/sh "$TB/glassworks_update.sh" >/dev/null 2>&1
  prop sys.glassworks.update.status
}
check "pm fails, framework still up: an error, pm's reason" has "$(upd fail)" "error: pm install: Failure [INSTALL_FAILED_TEST]"
check "framework restarted mid-install, package committed: ok" has "$(upd restart)" "ok: sh.edm.s1kiosk 30200 -> 30300"
# PackageManager stays away for 2 calls after the restart: the service polls every 5 s (twice in its
# GWU_RESTART_WAIT=10), then checks. Without the wait it would give up at the first call.
echo 2 > "$T/pm-down-after"; : > "$T/pm-down.log"
check "framework slow to come back: the service waits for PackageManager" has "$(upd restart)" "ok: sh.edm.s1kiosk 30200 -> 30300"
check "...polling it while it is down" test "$(wc -l < "$T/pm-down.log")" = 2
echo 1000 > "$T/pm-down-after"
check "framework never comes back: an error after the wait (GWU_RESTART_WAIT=10)" has "$(upd restart)" "error: framework restarted mid-install, did not come back"
rm -f "$T/pm-down-after" "$T/pm-down" "$T/pm-down.log"
check "a test wait (not 300 s) is logged" grep -q "RESTART_WAIT is 10s (GWU_RESTART_WAIT), not 300" "$T/log"
check "framework restarted, package not committed: an error that says so" has "$(upd restart-noup)" "error: framework restarted mid-install, not updated; pm install: Exc"
check "...cut to what a property holds" test "$(prop sys.glassworks.update.status | wc -c)" -le 91
rm -f "$T/pm-mode"; pkill -x system_server

# ------------------------------------------------------------------ Magisk's setup (boot image)
# glassworks.rc's post-fs-data copies Magisk's busybox in init, before Magisk's own step (docs/research/magisk-modules.md).
# init can't run here, so these pin what keeps it safe; the panel run checks that it works.
echo "--- glassworks.rc: Magisk's setup"
RC="$R/frames/aqara-s1-plus/tools/root/glassworks.rc"
# the post-fs-data action's commands, comments and blank lines left out
body=$(awk '/^on post-fs-data$/ { on = 1; next } /^(on|service) / { on = 0 } on && !/^[[:space:]]*(#|$)/' "$RC")
check "magiskenv: one post-fs-data action" test "$(grep -c '^on post-fs-data$' "$RC")" = 1
check "...Magisk's setup first, init built-ins only, then the loader's exec" \
  test "$(printf '%s\n' "$body" | awk '{print $1}' | tr '\n' ' ')" = "mkdir copy chmod exec "
check "...never makes a top-level /data directory (init would give it an encryption policy, or boot into recovery)" \
  sh -c '! printf "%s\n" "$1" | grep -Eq "^[[:space:]]*mkdir[[:space:]]+/data/[^/[:space:]]+/?([[:space:]]|$)"' _ "$body"
check "...copies the boot image's busybox to where Magisk checks, executable" sh -c 'printf "%s\n" "$1" | grep -qx "    copy \${MAGISKTMP}/magisk-busybox /data/adb/magisk/busybox" && printf "%s\n" "$1" | grep -qx "    chmod 0755 /data/adb/magisk/busybox"' _ "$body"
check "patch-boot-magisk.sh puts the busybox in the ramdisk (its overlay.list), from the Magisk APK" sh -c 'grep -qx "0700 overlay.d/sbin/magisk-busybox magisk-busybox" "$1" && grep -q "libbusybox.so\" \"\$W/p/magisk-busybox\"" "$1"' _ "$R/frames/aqara-s1-plus/tools/root/patch-boot-magisk.sh"
check "...and so does the boot writer, from the same APK" sh -c 'grep -q "echo \"0700 overlay.d/sbin/magisk-busybox magisk-busybox\"" "$1" && grep -q "libbusybox.so\" \"\$W/p/magisk-busybox\"" "$1"' _ "$R/frames/aqara-s1-plus/tools/root/glassworks_bootwriter.sh"
check "...and Kiln's overlay the same path and mode" sh -c 'grep -q "path: .overlay.d/sbin/magisk-busybox., mode: 0o700" "$1"' _ "$R/frames/aqara-s1-plus/installer/src/overlay.ts"

# --------------------------------------------------------------- ActivityManager watchdog (#248)
echo "--- glassworks_netwatch.sh: ActivityManager"
cp "$R/frames/aqara-s1-plus/tools/root/glassworks_netwatch.sh" "$TB/"
# dumpsys prints /t/lru (one "total non-act non-svc" line) as Android 10's header; none = no answer.
# /t/lru-seq instead: one call per line, printed as is ("-" prints nothing), for odd outputs in turn.
w "$T/bin/dumpsys" <<'EOF2'
#!/bin/sh
[ "$1 $2" = "activity processes" ] || exit 1
if [ -f /t/lru-seq ]; then
  l=$(head -n 1 /t/lru-seq); sed -i 1d /t/lru-seq
  # "RESTART <line>": the framework restarts by itself while dumpsys runs, then <line> is printed.
  # "<line> THEN-RESTART": <line> is printed, then the framework restarts before the next check.
  case "$l" in "RESTART "*) l=${l#RESTART }; pkill -x system_server; (system_server 2000 >/dev/null 2>&1 &); sleep 0.2 ;; esac
  case "$l" in *" THEN-RESTART")
    printf '%s\n' "${l% THEN-RESTART}"; pkill -x system_server; (system_server 2000 >/dev/null 2>&1 &); sleep 0.2; exit 0 ;; esac
  [ "$l" = - ] || printf '%s\n' "$l"; exit 0
fi
[ -s /t/lru ] || exit 1
read -r n a s < /t/lru
echo "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)"
echo "  Process LRU list (sorted by oom_adj, $n total, non-act at $a, non-svc at $s):"
EOF2
amw() { # lru-line loops: run the watchdog for <loops> checks with a fresh system_server; print its pid
  pkill -x system_server; (system_server 2000 >/dev/null 2>&1 &); sleep 0.2
  rm -f "$T/props/sys.glassworks.am.status" "$T/props/sys.glassworks.am.restarts"
  if [ -n "$1" ]; then echo "$1" > "$T/lru"; else : > "$T/lru"; fi
  pidof system_server > "$T/ss-pid"
  GWN_ONCE=$2 GWN_SLEEP=0 timeout 60 /system/bin/sh "$TB/glassworks_netwatch.sh" >/dev/null 2>&1
}
alive() { kill -0 "$(cat "$T/ss-pid")" 2>/dev/null; }
amw "27 3 3" 3
check "healthy LRU starts: reported" test "$(prop sys.glassworks.am.status)" = "ok 24 24 of 27"
check "...system_server left alone" alive
amw "18 19 19" 1
check "one broken read: counted" has "$(prop sys.glassworks.am.status)" "broken: LRU starts -1 -1 of 18 (1/2)"
check "...but nothing killed yet" alive
amw "18 19 19" 2
check "two broken reads in a row: framework restarted" sh -c '! kill -0 "$(cat /t/ss-pid)" 2>/dev/null'
check "...status says so" has "$(prop sys.glassworks.am.status)" "restarted the framework at uptime"
check "...and counts it" has "$(prop sys.glassworks.am.restarts)" "1 "
amw "18 18 19" 4
check "only the service start broken also counts, and one restart per cooldown" has "$(prop sys.glassworks.am.restarts)" "1 "
check "...the rest waits for the cooldown" has "$(prop sys.glassworks.am.status)" "in the cooldown"
amw "" 2
check "no answer from dumpsys: nothing killed" alive
check "...and said" test "$(prop sys.glassworks.am.status)" = "no answer from dumpsys"
(system_server 2000 >/dev/null 2>&1 &); sleep 0.2
echo "18 19 19" > "$T/lru"; GWN_ONCE=2 GWN_SLEEP=0 timeout 60 /system/bin/sh "$TB/glassworks_netwatch.sh" >/dev/null 2>&1
check "two system_server pids: nobody is killed" test "$(pidof system_server | wc -w)" = 2
pkill -x system_server; (system_server 2000 >/dev/null 2>&1 &); sleep 0.2; pidof system_server > "$T/ss-pid"
echo "18 19 19" > "$T/lru"; rm -f "$T/props/sys.glassworks.am.restarts"
GWN_AM_DRY=1 GWN_ONCE=2 GWN_SLEEP=0 timeout 60 /system/bin/sh "$TB/glassworks_netwatch.sh" >/dev/null 2>&1
check "dry run: says it would restart" has "$(prop sys.glassworks.am.status)" "would restart the framework at uptime"
check "...and kills nothing" alive
check "...nor counts a restart" test -z "$(prop sys.glassworks.am.restarts)"
# Odd dumpsys output never counts as broken, and resets the count of broken reads.
H='  Process LRU list (sorted by oom_adj,'
dseq() { # dumpsys outputs, one per check: run that many checks with a fresh system_server
  pkill -x system_server; (system_server 2000 >/dev/null 2>&1 &); sleep 0.2; pidof system_server > "$T/ss-pid"
  rm -f "$T/props/sys.glassworks.am.status" "$T/props/sys.glassworks.am.restarts" "$T/lru"
  printf '%s\n' "$@" > "$T/lru-seq"
  GWN_ONCE=$# GWN_SLEEP=0 timeout 60 /system/bin/sh "$TB/glassworks_netwatch.sh" >/dev/null 2>&1
}
dseq "$H 18 total, non-act at" "$H 18 total, non-act at"
check "a truncated header twice: nothing killed" alive
check "...reported as no answer" test "$(prop sys.glassworks.am.status)" = "no answer from dumpsys"
dseq "Failed to write while dumping service activity: Broken pipe" "Failed to write while dumping service activity: Broken pipe"
check "only 'Failed to write while dumping', twice: nothing killed" alive
dseq "$H 27 total, non-act at 3, non-svc at 3):" "$H 27 total, non-act at"
check "healthy, then truncated: nothing killed" alive
check "...the truncated read reported as no answer" test "$(prop sys.glassworks.am.status)" = "no answer from dumpsys"
dseq "$H 18 total, non-act at 19, non-svc at 19):" "$H 18 total, non-act" "$H 18 total, non-act at 19, non-svc at 19):"
check "broken, truncated, broken: the count starts again, nothing killed" alive
check "...one broken read counted" has "$(prop sys.glassworks.am.status)" "(1/2)"
dseq "$H 18 total, non-act at 19, non-svc at 19):" "$H 18 total, non-act at 19, non-svc at 19):"
check "...and the same header printed as is, twice: killed (the seq mock itself works)" sh -c '! kill -0 "$(cat /t/ss-pid)" 2>/dev/null'
# The broken framework restarts by itself between (or during) the reads: its healthy replacement is
# never killed, and the count starts again with it.
B="$H 18 total, non-act at 19, non-svc at 19):"
dseq "$B" "RESTART $B"
check "system_server replaced during the second read: the replacement is not killed" test -n "$(pidof system_server)" -a "$(pidof system_server)" != "$(cat "$T/ss-pid")"
check "...no restart counted" test -z "$(prop sys.glassworks.am.restarts)"
dseq "$B" "RESTART $B" "$B"
check "...a broken read of the new one counts from 1 again" has "$(prop sys.glassworks.am.status)" "(1/2)"
check "...and nothing was killed" test -z "$(prop sys.glassworks.am.restarts)"
# Restarted between two checks: the second broken read is of a new pid, which pidof still gives at the
# kill. Only the reset on a new pid stops the kill here.
dseq "$B THEN-RESTART" "$B"
check "framework restarted between two broken reads: the new one is not killed" test "$(pidof system_server)" != "$(cat "$T/ss-pid")" -a -n "$(pidof system_server)"
check "...its broken read counts as the first" has "$(prop sys.glassworks.am.status)" "(1/2)"
check "...no restart counted" test -z "$(prop sys.glassworks.am.restarts)"
# Any GWN_AM_DRY other than 1 is armed.
pkill -x system_server; (system_server 2000 >/dev/null 2>&1 &); sleep 0.2; pidof system_server > "$T/ss-pid"
echo "18 19 19" > "$T/lru"; rm -f "$T/lru-seq" "$T/props/sys.glassworks.am.restarts"
GWN_AM_DRY=yes GWN_ONCE=2 GWN_SLEEP=0 timeout 60 /system/bin/sh "$TB/glassworks_netwatch.sh" >/dev/null 2>&1
check "GWN_AM_DRY=yes is not a dry run: killed" sh -c '! kill -0 "$(cat /t/ss-pid)" 2>/dev/null'
# The kernel log's start line says which mode runs. Here /dev/kmsg is a plain file that each log line
# replaces, so these runs do no checks (GWN_ONCE=0) and leave only the start line.
startline() { [ -c /dev/kmsg ] && rm -f /dev/kmsg; : > /dev/kmsg
  env "$@" GWN_ONCE=0 timeout 20 /system/bin/sh "$TB/glassworks_netwatch.sh" >/dev/null 2>&1; cat /dev/kmsg; }
check "start line: armed by default" has "$(startline GWN_SLEEP=0)" "ActivityManager check armed"
check "...armed for GWN_AM_DRY=yes" has "$(startline GWN_AM_DRY=yes)" "ActivityManager check armed"
check "...dry run only for GWN_AM_DRY=1" has "$(startline GWN_AM_DRY=1)" "ActivityManager check dry run"
check "...a bad GWN_SLEEP falls back to 15 s" has "$(startline GWN_SLEEP=x)" "start: every 15s"
pkill -x system_server; rm -f "$T/lru" "$T/lru-seq" "$T/bin/dumpsys"

# ---------------------------------------------------------------------------------- time zone
echo "--- time zone"
reset_radio; echo off > "$T/props/persist.glassworks.radio_mode"
rm -f "$T/props/persist.glassworks.timezone" "$T/tz-calls" "$T/tz-unknown"
printf 'Antarctica/Casey' > "$T/props/persist.sys.timezone"
LOOPS=1 helper
check "no zone chosen and Aqara's default in place: nothing done" test ! -e "$T/tz-calls" -a ! -e "$T/props/persist.glassworks.timezone"
check "...status says what to do" has "$(prop sys.glassworks.timezone.status)" "none: no time zone chosen"
printf 'Europe/Berlin' > "$T/props/persist.glassworks.timezone"
LOOPS=1 helper
check "Aqara's reset is undone at start" test "$(prop persist.sys.timezone)" = Europe/Berlin -a "$(cat "$T/tz-calls")" = Europe/Berlin
check "...auto time zone switched off" test "$(cat "$T/settings.global.auto_time_zone")" = 0
check "...status ok" test "$(prop sys.glassworks.timezone.status)" = "ok Europe/Berlin"
: > "$T/tz-calls"; LOOPS=1 helper
check "already right: no call" test ! -s "$T/tz-calls"
# Aqara's sync running later (its gateway prop changed, or a slow boot): caught by the loop
LOOPS=4 GWA_SLEEP=3 env GWA_ONCE=4 GWA_SLEEP=3 GW_BUNDLE="$CUR" /system/bin/sh "$TB/glassworks_agent.sh" >/dev/null 2>&1 &
H=$!; sleep 2; printf 'Antarctica/Casey' > "$T/props/persist.sys.timezone"; wait $H
check "a later reset is re-applied by the watch loop" test "$(prop persist.sys.timezone)" = Europe/Berlin
# The loop notices a gone helper at its next check (<= 5 s); allow for a loaded/emulated host.
i=0; while [ -n "$(pgrep -f 'glassworks_agent.sh' | head -1)" ] && [ $i -lt 20 ]; do sleep 0.5; i=$((i + 1)); done
check "...and the loop ends with the helper" test -z "$(pgrep -f 'glassworks_agent.sh' | head -1)"
printf 'Mars/Olympus_Mons' > "$T/props/persist.glassworks.timezone"; echo Mars/Olympus_Mons > "$T/tz-unknown"; : > "$T/tz-calls"
LOOPS=1 helper
check "a zone Android doesn't know is reported" has "$(prop sys.glassworks.timezone.status)" "error Mars/Olympus_Mons: Android did not accept it (now GMT)"
check "...after one try" test "$(wc -l < "$T/tz-calls")" = 1
printf 'Europe/Berlin; reboot' > "$T/props/persist.glassworks.timezone"; : > "$T/tz-calls"
LOOPS=1 helper
check "a malformed value is never passed on" test ! -s "$T/tz-calls"
check "...and reported" has "$(prop sys.glassworks.timezone.status)" "is not a time zone id"
# migration: a panel set up before this, booting with the installer's zone still in place
rm -f "$T/props/persist.glassworks.timezone"; printf 'America/Los_Angeles' > "$T/props/persist.sys.timezone"
LOOPS=1 helper
check "the zone in place at boot is adopted when none was chosen" test "$(prop persist.glassworks.timezone)" = America/Los_Angeles
check "...and kept from then on" test "$(prop sys.glassworks.timezone.status)" = "ok America/Los_Angeles"
# The app's time zone pane turned off (#67) writes "(off)": not a zone id, so this helper keeps no zone,
# adopts none and calls nothing. An empty value would be adopted again, as above.
printf '(off)' > "$T/props/persist.glassworks.timezone"; printf 'America/Los_Angeles' > "$T/props/persist.sys.timezone"; : > "$T/tz-calls"
LOOPS=1 helper
check "the app's off marker: no zone kept or adopted" test "$(prop persist.glassworks.timezone)" = "(off)" -a ! -s "$T/tz-calls"
check "...and the status names it, for the app to confirm" has "$(prop sys.glassworks.timezone.status)" "error: '(off)' is not a time zone id"
pkill -f "sleep 1000"

# An app from before the loader set the s1kiosk names: they move once, at the helper's first start (#528).
reset_radio; rm -f "$T"/props/persist.glassworks.*
echo off > "$T/props/persist.s1kiosk.radio_mode"; printf 'any' > "$T/props/persist.s1kiosk.radio_allow"
printf 'Europe/Madrid' > "$T/props/persist.s1kiosk.timezone"; printf 'Asia/Tokyo' > "$T/props/persist.glassworks.timezone"
LOOPS=1 helper
check "old settings move to the new names once" test "$(prop persist.glassworks.radio_mode)/$(prop persist.glassworks.radio_allow)" = "off/any"
check "...a new name already set is kept" test "$(prop persist.glassworks.timezone)" = Asia/Tokyo
check "...and a root-only marker says it is done" test "$(stat -c %a:%u /data/s1kiosk/radio/.settings-moved)" = 600:0
rm -f "$T/props/persist.glassworks.radio_allow"
LOOPS=1 helper
check "an allow-list cleared later stays empty (loopback only), never the old 'any'" test -z "$(prop persist.glassworks.radio_allow)"
echo sisdk > "$T/props/persist.s1kiosk.radio_zigbee"
LOOPS=1 helper
check "an old value changed later (a 0.4.3 image or app) is copied again" test "$(prop persist.glassworks.radio_zigbee)" = sisdk
echo aqara > "$T/props/persist.s1kiosk.radio_zigbee"; touch "$T/setprop-fail-once.persist.glassworks.radio_zigbee"
LOOPS=1 helper
check "a copy that fails keeps the new value" test "$(prop persist.glassworks.radio_zigbee)" = sisdk
check "...and the old record, so it is tried again" has "$(cat /data/s1kiosk/radio/.settings-moved)" "radio_zigbee sisdk"
LOOPS=1 helper
check "...the same old value goes through at the next start" test "$(prop persist.glassworks.radio_zigbee)" = aqara
check "...and the record moves on" has "$(cat /data/s1kiosk/radio/.settings-moved)" "radio_zigbee aqara"
rm -f "$T"/props/persist.s1kiosk.* "$T/props/persist.glassworks.timezone"


# ------------------------------------------------------------------------------------ first boot
echo "--- glassworks_firstboot.sh"
cp "$R/frames/aqara-s1-plus/tools/root/glassworks_firstboot.sh" "$TB/"; echo 'QAAAAfake-key test@host' > "$TB/glassworks_adb_keys"
fb() { rm -f "$T/settings.calls"; GW_BOOT="$TB" GWF_ROOT="$T/fbnew" GWF_OLD="$T/fbold" /system/bin/sh "$TB/glassworks_firstboot.sh" >/dev/null 2>&1; }
mkdir -p "$T/fbold"; touch "$T/fbold/.firstboot-done"
fb
check "a panel provisioned by a 0.4.3 boot image is not provisioned again" test ! -e "$T/settings.calls" -a ! -e "$T/fbnew/.firstboot-done"
rm -f "$T/fbold/.firstboot-done"
fb
check "...a fresh panel is: adb on" has "$(cat "$T/settings.calls" 2>/dev/null)" "put global adb_enabled 1"
check "...and marked done" test -e "$T/fbnew/.firstboot-done"
fb
check "...once" test ! -e "$T/settings.calls"

# ---------------------------------------------------------------------------------- Bluetooth address
# #246: the adapter's address for the app's Bluetooth proxy. Made-up, locally administered addresses only.
echo "--- Bluetooth address"
reset_radio; echo off > "$T/props/persist.glassworks.radio_mode"
BTP=$T/pub; rm -rf "$BTP"; rm -f "$T/settings.secure.bluetooth_address"
LOOPS=1 helper GWA_PUB="$BTP"
check "Android has none yet (the mock answers 1): nothing written" test ! -e "$BTP/bluetooth-address"
for bad in null 02:00:00:00:00:00 00:00:00:00:00:00 ff:ff:ff:ff:ff:ff 02:00:00:00:00 02:00:00:00:00:0g '02:00:00:00:00:01; reboot' ' 02:00:00:00:00:01'; do
  printf '%s' "$bad" > "$T/settings.secure.bluetooth_address"; LOOPS=1 helper GWA_PUB="$BTP"
  check "placeholder or malformed '$bad': never written" test ! -e "$BTP/bluetooth-address"
done
: > "$T/settings.calls"; printf null > "$T/settings.secure.bluetooth_address"
LOOPS=3 helper GWA_PUB="$BTP"
check "until there is one, asked once a minute, not every loop" test "$(grep -c 'get secure bluetooth_address' "$T/settings.calls")" = 1
printf '02:aa:00:00:00:01' > "$T/settings.secure.bluetooth_address"; : > "$T/log"
LOOPS=1 helper GWA_PUB="$BTP"
check "a real address is written for the app, upper case" test "$(cat "$BTP/bluetooth-address")" = 02:AA:00:00:00:01
check "...root 0644 in a root 0755 dir" test "$(stat -c %a:%u "$BTP/bluetooth-address")/$(stat -c %a:%u "$BTP")" = "644:0/755:0"
check "...and never logged" sh -c "! grep -qi '02:aa:00:00:00:01' '$T/log' && grep -q 'bluetooth address published' '$T/log'"
: > "$T/settings.calls"; LOOPS=3 helper GWA_PUB="$BTP"
check "once written, asked once per start only" test "$(grep -c 'get secure bluetooth_address' "$T/settings.calls")" = 1
echo keep > "$T/not-ours"; rm -f "$BTP/bluetooth-address"; ln -s "$T/not-ours" "$BTP/bluetooth-address"
LOOPS=1 helper GWA_PUB="$BTP"
check "a planted symlink is replaced, its target untouched" test ! -L "$BTP/bluetooth-address" -a "$(cat "$T/not-ours")" = keep -a "$(cat "$BTP/bluetooth-address")" = 02:AA:00:00:00:01
rm -rf "$BTP"; touch "$T/settings-hang"; rm -f "$T/props/sys.glassworks.radio.status"; t0=$(date +%s)
( sleep 90; pkill -f "sleep 1000" ) & W=$!   # an outer deadline: an agent without its own bound fails, not hangs
LOOPS=1 helper GWA_PUB="$BTP" GWA_BT_TIMEOUT=1
kill "$W" 2>/dev/null
check "a settings call that never returns doesn't stall the loop (bounded, then retried later)" test $(( $(date +%s) - t0 )) -lt 15 -a ! -e "$BTP/bluetooth-address"
check "...and the loop went on to the radio: a fresh status, from this run" has "$(prop sys.glassworks.radio.status)" "ok off"
rm -f "$T/settings-hang"; pkill -f "sleep 1000" 2>/dev/null
touch "$T/settings-fail"; LOOPS=1 helper GWA_PUB="$BTP"
check "a lookup that prints an address and then fails: nothing written" test ! -e "$BTP/bluetooth-address"
rm -f "$T/settings-fail"
rm -rf "$BTP" "$T/not-ours" "$T/settings.secure.bluetooth_address"

# ------------------------------------------------------------------------- computers over USB adb
# Manage's "Allow a new computer" on the S1: the agent follows Android's log for the computer asking (from
# system_server only), answers it with allowDebugging, keeps adb_keys, and turns Android's 7-day key timeout off.
# Every key here is made up (random bytes in adb's key layout).
echo "--- computers over USB adb"
reset_radio; echo off > "$T/props/persist.glassworks.radio_mode"
AP=$T/adbpub; AK=$T/adb/adb_keys; rm -rf "$AP" "$T/adb" /t/adb-calls /t/adb-once-calls /t/adb-android-writes /t/logcat.args; mkdir -p "$T/adb"
rm -f "$T/settings.global.adb_allowed_connection_time"; : > "$T/settings.calls"
w "$T/bin/logcat" <<'EOF'
#!/bin/sh
echo "$*" >> /t/logcat.args; cat /t/logcat.out 2>/dev/null; exit 0
EOF
mkkey() { python3 -c 'import base64, os, struct, sys; print(base64.b64encode(struct.pack("<I", 64) + os.urandom(520)).decode() + " " + sys.argv[1])' "$1"; }
kfp() { printf '%s' "${1%% *}" | base64 -d | md5sum | cut -c1-32 | tr a-f A-F | sed 's/../&:/g; s/:$//'; }
K1=$(mkkey installer@example); K2=$(mkkey me@laptop); K3=$(mkkey old@desk); K4=$(mkkey forged@app); K5=$(mkkey new@desk)
F1=$(kfp "$K1"); F2=$(kfp "$K2"); F3=$(kfp "$K3"); F4=$(kfp "$K4"); F5=$(kfp "$K5")
cp "$TB/glassworks_adb_keys" "$T/boot-adb-keys.bak"; echo "$K1" > "$TB/glassworks_adb_keys"
# Android dropped the installer's key (7 days without a connection): only K3 is left.
printf '%s\n' "$K3" > "$AK"; chown 1000:2000 "$AK"; chmod 640 "$AK"
pkill -x system_server 2>/dev/null; sleep 0.2; system_server 1000 & SSP=$!
now=$(date +%s)
{ echo "$((now - 2000)).100  $SSP  900 D AdbDebuggingManager: Received public key: $K5"
  echo "$((now - 9)).100  4242  4243 D AdbDebuggingManager: Received public key: $K4"
  echo "$((now - 5)).100  $SSP   900 D AdbDebuggingManager: Received public key: $K2"
  echo "$((now - 4)).100  4242  4243 D AdbDebuggingManager: Received public key: $K4"
  echo "$((now - 3)).100  $SSP   900 D AdbDebuggingManager: Received public key: not-a-key me@laptop"; } > /t/logcat.out
adbrun() { LOOPS=${1:-3} helper GWA_PUB="$AP" GWA_ADB_KEYS="$AK" GWA_SLEEP=1 GWA_ADB_RETRY=1 GWA_ADB_SETTLE=0; }
adbreq() { # content [age-seconds]
  mkdir -p "$APP/files/adb"; printf '%s\n' "$1" > "$APP/files/adb/request"; chown -R 10050:10050 "$APP/files/adb"
  [ -n "${2:-}" ] && touch -d "@$(( $(date +%s) - $2 ))" "$APP/files/adb/request"
}
asking() { cut -d' ' -f2 "$AP/adb-asking" 2>/dev/null; }
result() { cat "$AP/adb-result" 2>/dev/null; }
adbrun
check "the installer's key Android dropped is back in adb_keys" test "$(grep -cxF "$K1" "$AK")/$(grep -cxF "$K3" "$AK")" = 1/1
check "...owner and mode kept" test "$(stat -c %u:%g:%a "$AK")" = 1000:2000:640
check "Android's 7-day key timeout is turned off" test "$(cat "$T/settings.global.adb_allowed_connection_time" 2>/dev/null)" = 0
check "...and the card's status is ok" test "$(prop sys.glassworks.adb.status)" = ok
check "the computer asking is the one system_server logged: not a line any app could write, not one too old" test "$(asking)" = "$F2"
check "...its key published with it, for the page" has "$(cat "$AP/adb-asking")" "$K2"
check "...root 0644" test "$(stat -c %a:%u "$AP/adb-asking")" = 644:0
check "the list: the installer's key marked, the other one added" \
  sh -c "grep -qxF 'installer $F1 $K1' '$AP/adb-keys' && grep -qxF 'added $F3 $K3' '$AP/adb-keys' && [ \$(wc -l < '$AP/adb-keys') = 2 ]"
check "the log is read from 10 minutes back first" sh -c "head -1 /t/logcat.args | grep -q -- '-b system -v epoch -T [0-9]*\.000 -s AdbDebuggingManager:D'"
check "...and from its newest line once that logcat ends at once" grep -q -- '-T 1 ' /t/logcat.args
: > "$T/settings.calls"; adbrun 1
check "the timeout already off: only read again" sh -c "! grep -q 'put global adb_allowed_connection_time' '$T/settings.calls'"

adbreq "r1 allow $F4"; adbrun
check "allow names a computer other than the one asking: refused" test "$(result)" = "r1 error changed"
check "...the request taken" test ! -e "$APP/files/adb/request"
check "...nothing asked of Android, nothing written" test ! -e /t/adb-calls -a "$(grep -c . "$AK")" = 2
echo "$AK" > /t/adb-android-writes; adbreq "r2 allow $F2"; adbrun
check "allow the computer asking: Android answers it (allowDebugging, always)" test "$(cat /t/adb-calls)" = "$K2"
check "...ok" test "$(result)" = "r2 ok allowed"
check "...its key in adb_keys once (Android wrote it)" test "$(grep -cxF "$K2" "$AK")" = 1
check "...on the list" grep -qxF "added $F2 $K2" "$AP/adb-keys"
cp "$AK" "$AK.one"; echo "$K2" >> "$AK"; touch -d '@1' "$AK"; adbrun 1
check "a key twice in adb_keys (Android wrote it again): listed once" test "$(grep -c " $F2 " "$AP/adb-keys")" = 1
cat "$AK.one" > "$AK"; rm -f "$AK.one"
check "...no longer asking, also with its old log line read again" test -z "$(asking)"
rm -f /t/adb-android-writes /t/adb-calls
echo "$((now - 2)).100  $SSP   900 D AdbDebuggingManager: Received public key: $K5" >> /t/logcat.out; adbrun
check "a new computer asks" test "$(asking)" = "$F5"
adbreq "r3 allow $F5"; adbrun
check "allowed where Android writes nothing (it knew the key): the agent writes it" test "$(result)/$(grep -cxF "$K5" "$AK")" = "r3 ok allowed/1"
check "...adb_keys keeps its owner and mode" test "$(stat -c %u:%g:%a "$AK")" = 1000:2000:640
check "...and the keys it had" sh -c "grep -qxF '$K1' '$AK' && grep -qxF '$K2' '$AK' && grep -qxF '$K3' '$AK'"
echo "$(($(date +%s) - 1)).100  $SSP   900 D AdbDebuggingManager: Received public key: $K4" >> /t/logcat.out; adbrun
adbreq "r3b once $F4"; adbrun
check "once: Android answers it for this connection only (allowDebugging, not always)" test "$(cat /t/adb-once-calls 2>/dev/null)" = "$K4"
check "...ok once, nothing written, no longer asking" test "$(result)/$(grep -cxF "$K4" "$AK")/$(asking)" = "r3b ok once/0/"
echo "$(date +%s).200  $SSP   900 D AdbDebuggingManager: Received public key: $K4" >> /t/logcat.out; sleep 1; adbrun
check "...and when it connects again (a new log line), it asks again" test "$(asking)" = "$F4"
adbreq "r3c once $F4"; adbrun
check "...once again: answered, nothing written" test "$(result)/$(grep -cxF "$K4" "$AK")/$(asking)" = "r3c ok once/0/"
adbreq "r4 allow $F5" 200; adbrun 1
check "a request over 2 minutes old: ignored" has "$(result)" "r4 error too old ("
adbreq "r5 allow $F3"; adbrun 1
check "allow with no computer asking: refused" test "$(result)" = "r5 error none-asking"
adbreq "r6 remove $F1"; adbrun 1
check "remove the installer's key on Manage: removed" test "$(result)/$(grep -cxF "$K1" "$AK")" = "r6 ok removed/0"
check "...recorded, root 0600" test "$(cat /data/s1kiosk/radio/adb-revoked)/$(stat -c %a:%u /data/s1kiosk/radio/adb-revoked)" = "$F1/600:0"
adbrun 1
check "...and not put back at the next start" test "$(grep -cxF "$K1" "$AK")" = 0
adbreq "r7 remove $F3"; adbrun 1
check "remove an added key: gone from adb_keys" test "$(result)/$(grep -cxF "$K3" "$AK")" = "r7 ok removed/0"
check "...the others kept, owner and mode too" test "$(grep -c . "$AK")/$(stat -c %u:%g:%a "$AK")" = "2/1000:2000:640"
check "...and off the list" sh -c "! grep -q '$F3' '$AP/adb-keys'"
adbreq "r8 remove $F3"; adbrun 1
check "remove one that isn't there: says so" test "$(result)" = "r8 error not-listed"
for bad in "R9 allow $F5" "r9; allow $F5" "r10 allow ${F5%:*}" "r11 allow $(echo "$F5" | tr A-F a-f)" "r12 open $F5"; do
  adbreq "$bad"; adbrun 1
  check "a bad request '${bad%% *} ...': nothing done" sh -c "test \"\$(cut -d' ' -f1-2 '$AP/adb-result')\" != 'r9 ok' && test \$(grep -c . '$AK') = 2"
done
check "...a bad fingerprint is answered, a bad id isn't" test "$(result)" = "r12 error unknown request"
echo "$(($(date +%s) - 1)).100  $SSP   900 D AdbDebuggingManager: Received public key: $K1" >> /t/logcat.out; adbrun
adbreq "r13 allow $F1"; adbrun
check "the installer's computer allowed again: in, and no longer recorded as removed" \
  test "$(result)/$(grep -cxF "$K1" "$AK")/$(grep -c . /data/s1kiosk/radio/adb-revoked)" = "r13 ok allowed/1/0"
grep -vxF "$K1" "$AK" > "$AK.t"; cat "$AK.t" > "$AK"; rm -f "$AK.t"; adbrun 1
check "...so it comes back the next time Android drops it" test "$(grep -cxF "$K1" "$AK")" = 1
pkill -x system_server 2>/dev/null; mv "$T/boot-adb-keys.bak" "$TB/glassworks_adb_keys"
rm -rf "$AP" "$T/adb" "$APP/files/adb" /t/logcat.out /t/logcat.args /t/adb-calls /t/adb-once-calls "$T/bin/logcat" /data/s1kiosk/radio/adb-once "$T/settings.global.adb_allowed_connection_time"

# ------------------------------------------------------------------------- setup hotspot portal
# #197: while hostapd runs and the kiosk (uid 10050) has its DNS (udp:8053) and redirect (tcp:8098)
# sockets on one address of a Wi-Fi interface, the helper sends the hotspot's udp:53 and tcp:80 there
# (nat PREROUTING -> GWPORTAL), and takes it all away as soon as one of those stops. A fake iptables
# keeps the nat and filter tables in /t/ipt so the rules can be read back. Addresses: the AP 192.168.43.1
# (012BA8C0, little-endian), the AP's IPv6 2001:db8::1, a home network 192.0.2.10.
echo "--- setup hotspot portal (#197)"
reset_radio; mkdir -p /data/s1kiosk/radio "$T/net" "$T/ipt" "$T/hostapd"; echo "RADIO_ALLOW=any" > /data/s1kiosk/radio/radio.conf
# an unrelated filter rule in each family, which nothing in this section may touch
FILTER='-A INPUT -p tcp -m tcp --dport 22 -j ACCEPT'
printf '%s\n' "$FILTER" > "$T/ipt/4-filter"; printf '%s\n' "$FILTER" > "$T/ipt/6-filter"
echo off > "$T/props/persist.glassworks.radio_mode"
cat > "$T/ipt.py" <<'PY'
import os, sys
name = sys.argv[1]; fam = '6' if name.startswith('ip6') else '4'
args = sys.argv[2:]
if '-w' in args: i = args.index('-w'); del args[i:i + 2]
table = 'filter'
if '-t' in args: i = args.index('-t'); table = args[i + 1]; del args[i:i + 2]
def path(t): return '/t/ipt/%s-%s' % (fam, t)
def load(t):
    chains, rules = ['PREROUTING'] if t == 'nat' else ['INPUT'], []
    if os.path.exists(path(t)):
        for l in open(path(t)).read().splitlines():
            if l.startswith(':'): chains.append(l[1:])
            elif l: rules.append(l)
    return chains, rules
def store(t, chains, rules):
    builtin = ['PREROUTING', 'INPUT']
    open(path(t), 'w').write(''.join(':%s\n' % c for c in chains if c not in builtin) + ''.join(r + '\n' for r in rules))
def spec(r): return r.split(' ', 2)  # ['-A', chain, rest]
if name.endswith('-save'):
    # /t/ipt-save-fail: how many reads of the nat table fail next (a table that can't be read for a moment)
    # /t/ipt-nat-unreadable: every read of the nat table fails while it exists
    if table == 'nat' and os.path.exists('/t/ipt-nat-unreadable'):
        open('/t/ipt-nat-fails', 'a').write(name + '\n'); sys.exit(1)
    if table == 'nat' and os.path.exists('/t/ipt-save-fail'):
        n = int(open('/t/ipt-save-fail').read() or 0)
        if n > 0:
            open('/t/ipt-save-fail', 'w').write(str(n - 1)); sys.exit(1)
    chains, rules = load(table); print('*' + table)
    for c in chains: print(':%s %s [0:0]' % (c, 'ACCEPT' if c in ('PREROUTING', 'INPUT') else '-'))
    for r in rules: print(r)
    print('COMMIT'); sys.exit(0)
if name.endswith('-restore'):
    open('/t/ipt/restores', 'a').write(name + '\n')
    if os.path.exists('/t/ipt-refuse') and fam in open('/t/ipt-refuse').read(): sys.exit(1)
    t = None
    for l in sys.stdin.read().splitlines():
        if l.startswith('*'): t = l[1:]; chains, rules = load(t)
        elif l.startswith(':'):
            c = l[1:].split()[0]
            if c not in chains: chains.append(c)
            rules = [r for r in rules if spec(r)[1] != c]
        elif l.startswith('-A '): rules.append(l)
        elif l == 'COMMIT': store(t, chains, rules)
    sys.exit(0)
chains, rules = load(table); op, c, rest = args[0], args[1], ' '.join(args[2:])
if op in ('-D', '-F', '-X') and os.path.exists('/t/ipt-nodel'): sys.exit(1)   # removals refused (a busy xtables lock)
if op == '-I':
    n, rest = (int(args[2]), ' '.join(args[3:])) if len(args) > 2 and args[2].isdigit() else (1, rest)
    if c not in chains: sys.exit(1)
    idx = [k for k, r in enumerate(rules) if spec(r)[1] == c]
    rules.insert(idx[n - 1] if len(idx) >= n else len(rules), '-A %s %s' % (c, rest))
elif op == '-A': rules.append('-A %s %s' % (c, rest))
elif op == '-D':
    r = '-A %s %s' % (c, rest)
    if r not in rules: sys.exit(1)
    rules.remove(r)
elif op == '-F':
    if c not in chains: sys.exit(1)
    rules = [r for r in rules if spec(r)[1] != c]
elif op == '-X':
    if c not in chains or any(spec(r)[1] == c or r.endswith('-j ' + c) for r in rules): sys.exit(1)
    chains.remove(c)
store(table, chains, rules)
PY
for b in iptables ip6tables iptables-restore ip6tables-restore iptables-save ip6tables-save; do
  printf '#!/bin/sh\nexec python3 /t/ipt.py %s "$@"\n' "$b" > "$T/bin/$b"; chmod 755 "$T/bin/$b"
done
# `ip -4 -o addr show` from /t/ip-addr4
w "$T/bin/ip" <<'EOF'
#!/bin/sh
case "$*" in *"rule del"*) exit 1 ;; "-4 -o addr show") cat /t/ip-addr4 2>/dev/null ;; esac; exit 0
EOF
cp "$(command -v sleep)" "$T/hostapd/hostapd"
hostapd_on() { "$T/hostapd/hostapd" 1000 & HOSTAPD=$!; sleep 0.2; }
hostapd_off() { kill "$HOSTAPD" 2>/dev/null; wait "$HOSTAPD" 2>/dev/null; }
socks() { # file lines...: "<local hex>:<port hex> <state> <uid>"
  f=$1; shift
  { echo "$HDR6"; i=0; for l in "$@"; do set -- $l
      printf '%4d: %s 00000000000000000000000000000000:0000 %s 00000000:00000000 00:00000000 00000000 %5d        0 %d 2 00000000 0\n' $i "$1" "$2" "$3" $((30000 + i)); i=$((i + 1)); done; } > "$T/net/$f"
}
AP4=0000000000000000FFFF0000012BA8C0; AP6=B80D0120000000000000000001000000
ap_up() { # the kiosk's sockets as Android's dual-stack Java sockets show them
  socks udp6 "$AP4:1F75 07 10050" "$AP6:1F75 07 10050"; socks tcp6 "$AP4:1FA2 0A 10050"; : > "$T/net/udp"; : > "$T/net/tcp"
  echo "23: wlan0    inet 192.168.43.1/24 brd 192.168.43.255 scope global wlan0\       valid_lft forever preferred_lft forever" > "$T/ip-addr4"
}
# ipt_idle: until no fake iptables call is running (a helper's loop it killed on exit may have one in flight)
ipt_idle() { i=0; while pgrep -f /t/ipt.py >/dev/null && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done; }
portal() { LOOPS=1 helper GWA_PROC_NET="$T/net"; ipt_idle; }
nat() { cat "$T/ipt/$1-nat" 2>/dev/null; }
RULE4_DNS='-A GWPORTAL -s 192.168.43.1/24 -d 192.168.43.1/32 -i wlan0 -p udp -m udp --dport 53 -j DNAT --to-destination 192.168.43.1:8053'
RULE4_WEB='-A GWPORTAL -s 192.168.43.1/24 -d 192.168.43.1/32 -i wlan0 -p tcp -m tcp --dport 80 -j DNAT --to-destination 192.168.43.1:8098'
RULE6_DNS='-A GWPORTAL -d 2001:db8:0:0:0:0:0:1/128 -i wlan0 -p udp -m udp --dport 53 -j DNAT --to-destination [2001:db8:0:0:0:0:0:1]:8053'

ap_up; portal
check "no hostapd: no rules" test -z "$(nat 4 | grep GWPORTAL)" -a "$(prop sys.glassworks.portal.status)" = off
hostapd_on; portal
check "hotspot up, the kiosk's sockets on its address: DNS and web sent to them (IPv4)" \
  test "$(nat 4 | grep '^-A GWPORTAL')" = "$(printf '%s\n%s' "$RULE4_DNS" "$RULE4_WEB")"
check "...DNS to the AP's IPv6 address too" test "$(nat 6 | grep '^-A GWPORTAL')" = "$RULE6_DNS"
check "...one jump from PREROUTING in each" test "$(nat 4 | grep -c '^-A PREROUTING -j GWPORTAL$')$(nat 6 | grep -c '^-A PREROUTING -j GWPORTAL$')" = 11
check "...and the status says on which interface" test "$(prop sys.glassworks.portal.status)" = "on wlan0"
check "...never with an address in the log" test -z "$(grep -e 192.168 -e 2001: "$T/log")"
: > "$T/setprop.log"; rm -f "$T/ipt/restores"; LOOPS=3 helper GWA_PROC_NET="$T/net" GWA_PORTAL_SLEEP=0.3; ipt_idle
check "a new helper keeps rules already in place: not rebuilt, one jump, the status written once" \
  test "$(cat "$T/ipt/restores" 2>/dev/null | wc -l)/$(nat 4 | grep -c '^-A PREROUTING -j GWPORTAL$')/$(grep -c '^sys.glassworks.portal.status=' "$T/setprop.log")" = 0/1/1
# ip6tables-save prints the address short; a rule in that form is the same rule, not a stale one
sed -i 's/2001:db8:0:0:0:0:0:1/2001:db8::1/g' "$T/ipt/6-nat"; rm -f "$T/ipt/restores"; portal
check "an IPv6 rule as ip6tables-save prints it (2001:db8::1) is kept, not rebuilt" \
  test ! -s "$T/ipt/restores" -a "$(prop sys.glassworks.portal.status)" = "on wlan0"
socks udp6 "$AP4:1F75 07 10050" "B80D0120000000000000000002000000:1F75 07 10050"; portal
check "the hotspot's IPv6 address moved (IPv4 the same): the IPv6 rule follows it" \
  test "$(nat 6 | grep -c 'to-destination \[2001:db8:0:0:0:0:0:2\]:8053$')/$(nat 6 | grep -c '2001:db8::1\|0:0:0:0:0:1\]')" = 1/0
ap_up; portal
echo "23: wlan0    inet 192.168.43.1/25 brd 192.168.43.127 scope global wlan0" > "$T/ip-addr4"; rm -f "$T/ipt/restores"; portal
check "the hotspot's subnet changed (/24 to /25, same address): the IPv4 rules are rebuilt for it" \
  test -s "$T/ipt/restores" -a "$(nat 4 | grep -c '^-A GWPORTAL -s 192.168.43.1/25 ')" = 2 -a -z "$(nat 4 | grep '/24 ')"
ap_up; portal
# as iptables-save prints them, the source is the network: the same rules, kept
sed -i 's|-s 192.168.43.1/24 |-s 192.168.43.0/24 |' "$T/ipt/4-nat"; rm -f "$T/ipt/restores"; portal
check "the source as iptables-save prints it (192.168.43.0/24) is the same rule: kept, not rebuilt" \
  test ! -s "$T/ipt/restores" -a "$(prop sys.glassworks.portal.status)" = "on wlan0"
sed -i 's|-s 192.168.43.0/24 |-s 192.168.44.0/24 |' "$T/ipt/4-nat"; portal
check "a source network that isn't the hotspot's (same prefix, address and interface): rebuilt" \
  test -s "$T/ipt/restores" -a "$(nat 4 | grep -c '^-A GWPORTAL -s 192.168.43.1/24 ')" = 2 -a -z "$(nat 4 | grep '192.168.44')"
ap_up; portal
# first_check: until the helper started in the background has done its first check (its status line)
# logged <pattern>: waits (60 s at most, an emulated ARM under load is slow) until setprop.log has a line for it
logged() { i=0; until grep -q "$1" "$T/setprop.log" || [ $i -ge 600 ]; do sleep 0.1; i=$((i + 1)); done; }
first_check() { logged '^sys.glassworks.portal.status='; }
# bg_until <test...>: waits (60 s at most, an emulated ARM under load is slow) until it holds, then stops the helper
# helpers_gone: after a pkill, until no helper and no fake iptables call of one is left (one already running would
# still write its table after the test has moved on)
helpers_gone() { pkill -f glassworks_agent.sh; wait "$H" 2>/dev/null; i=0; while pgrep -f '/t/ipt.py|glassworks_agent.sh' >/dev/null && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done; }
bg_until() { i=0; until "$@" || [ $i -ge 600 ]; do sleep 0.1; i=$((i + 1)); done; helpers_gone; }
jumps4() { test "$(nat 4 | grep -c '^-A PREROUTING -j GWPORTAL$')" = 1; }
rules4() { test -n "$(nat 4 | grep '^-A GWPORTAL')"; }
: > "$T/setprop.log"; LOOPS=100000 helper GWA_PROC_NET="$T/net" GWA_PORTAL_SLEEP=0.2 GWA_SLEEP=0.1 &
H=$!; first_check
# pause the agent and its loops while the jump goes, so the setup can see it gone before the loop runs again
P=$(pgrep -P "$H" -f glassworks_agent.sh | head -1); PL="$P $(pgrep -P "$P" | tr '\n' ' ')"; kill -STOP $PL
sed -i '/^-A PREROUTING -j GWPORTAL$/d' "$T/ipt/4-nat"
check "(setup: the jump is gone, with the agent paused)" test $? = 0 -a "$(nat 4 | grep -c '^-A PREROUTING -j GWPORTAL$')" = 0
kill -CONT $PL; bg_until jumps4
check "a jump lost while it runs (netd restarted) is put back by the loop" test "$(nat 4 | grep -c '^-A PREROUTING -j GWPORTAL$')" = 1
socks tcp6; portal
check "the redirect's socket gone: everything removed, both families" \
  test -z "$(nat 4 | grep GWPORTAL)$(nat 6 | grep GWPORTAL)" -a "$(prop sys.glassworks.portal.status)" = off
ap_up; portal; hostapd_off; portal
check "hostapd gone (the hotspot stopped): removed" test -z "$(nat 4 | grep GWPORTAL)$(nat 6 | grep GWPORTAL)"
ap_up; hostapd_on; portal; hostapd_off; touch /t/ipt-nat-unreadable; : > "$T/setprop.log"
LOOPS=100000 helper GWA_PROC_NET="$T/net" GWA_PORTAL_SLEEP=0.2 GWA_SLEEP=0.1 &
H=$!; logged '^sys.glassworks.portal.status=error'
check "a rule table that can't be read at the hotspot's end: not called off" \
  sh -c "grep -q '^sys.glassworks.portal.status=error' '$T/setprop.log' && ! grep -q '^sys.glassworks.portal.status=off' '$T/setprop.log'"
gone() { test -z "$(nat 4 | grep GWPORTAL)$(nat 6 | grep GWPORTAL)" && test "$(prop sys.glassworks.portal.status)" = off; }
rm -f /t/ipt-nat-unreadable; bg_until gone
check "...and the next check removes the rules, then says off" gone
# The helper itself dies (killed, as init does on a restart) while the hotspot is up: the loop that outlives it
# takes the rules away, and tries again when a table can't be read the first time.
ap_up; hostapd_on; portal; : > "$T/setprop.log"
LOOPS=100000 helper GWA_PROC_NET="$T/net" GWA_PORTAL_SLEEP=0.2 GWA_SLEEP=0.1 &
H=$!; first_check; P=$(pgrep -P "$H" -f glassworks_agent.sh | head -1)   # the helper itself, not its loops
# its two loops (time zone, portal) are its sh children; wait until both are there, then pause them
i=0; until [ "$(pgrep -P "$P" -f glassworks_agent.sh | wc -l)" -ge 2 ] || [ $i -ge 600 ]; do sleep 0.1; i=$((i + 1)); done
L=$(pgrep -P "$P" -f glassworks_agent.sh | tr '\n' ' ')
check "(the helper's loops are up)" test "$(echo $L | wc -w)" -ge 2
kill -STOP $L; kill -9 "$P"
i=0; while kill -0 "$P" 2>/dev/null && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done   # until its parent reaps it
check "(the helper is killed)" sh -c "! kill -0 $P 2>/dev/null"
: > /t/ipt-nat-fails; touch /t/ipt-nat-unreadable; kill -CONT $L
# Unreadable until the leftover loop has failed 8 reads (several cleanup tries) or has ended: a loop that tried
# once and gave up is gone by then, with the rules still there.
i=0; until [ "$(wc -l < /t/ipt-nat-fails)" -ge 8 ] || [ -z "$(pgrep -f glassworks_agent.sh)" ] || [ $i -ge 600 ]; do
  sleep 0.1; i=$((i + 1)); done
check "(the leftover loop's cleanup reads fail)" test -s /t/ipt-nat-fails
rm -f /t/ipt-nat-unreadable
i=0; until test -z "$(nat 4 | grep GWPORTAL)$(nat 6 | grep GWPORTAL)" || [ $i -ge 600 ]; do sleep 0.1; i=$((i + 1)); done
check "the helper killed, a table unreadable at first: the leftover loop still removes the rules, both families" \
  test -z "$(nat 4 | grep GWPORTAL)$(nat 6 | grep GWPORTAL)"
i=0; while pgrep -f glassworks_agent.sh >/dev/null && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
check "...and then it ends too" test -z "$(pgrep -f glassworks_agent.sh)"
helpers_gone; hostapd_off
hostapd_on
socks udp6 "$AP4:1F75 07 10077" "$AP6:1F75 07 10077"; portal
check "the sockets of another app: no rules" test -z "$(nat 4 | grep GWPORTAL)"
socks udp6 "0000000000000000FFFF000000000000:1F75 07 10050"; socks tcp6 "00000000000000000000000000000000:1FA2 0A 10050"; portal
check "the kiosk on every address (not the hotspot's): no rules" test -z "$(nat 4 | grep GWPORTAL)"
ap_up; socks udp6 "$AP4:1F75 07 10050" "00000000000000000000000000000000:1F75 07 10050"; portal
check "an IPv6 DNS socket on every address ([::]): IPv4 rules only" test -n "$(nat 4 | grep '^-A GWPORTAL')" -a -z "$(nat 6 | grep '^-A GWPORTAL')"
ap_up; echo "5: eth0    inet 192.168.43.1/24 brd 192.168.43.255 scope global eth0" > "$T/ip-addr4"; portal
check "the address on a wired interface: no rules" test -z "$(nat 4 | grep GWPORTAL)"
ap_up; echo "23: wlan0    inet 192.0.2.10/24 brd 192.0.2.255 scope global wlan0" > "$T/ip-addr4"; portal
check "the address not on any interface: no rules" test -z "$(nat 4 | grep GWPORTAL)"
ap_up; socks udp "012BA8C0:1F75 07 10050"; socks udp6 "0000000000000000FFFF00000A0200C0:1F75 07 10050"; portal
check "DNS on two addresses: no rules (which one is the hotspot's?)" test -z "$(nat 4 | grep GWPORTAL)"
ap_up; socks udp6 "$AP4:1F75 07 10050"; portal
check "no IPv6 socket: IPv4 only" test -n "$(nat 4 | grep '^-A GWPORTAL')" -a -z "$(nat 6 | grep '^-A GWPORTAL')"
ap_up; socks udp "012BA8C0:1F75 07 10050"; socks udp6; socks tcp "012BA8C0:1FA2 0A 10050"; socks tcp6; portal
check "plain IPv4 sockets (udp, tcp) count too" test "$(nat 4 | grep -c '^-A GWPORTAL')" = 2
ap_up; portal; hostapd_off; : > "$T/setprop.log"; LOOPS=100000 helper GWA_PROC_NET="$T/net" GWA_PORTAL_SLEEP=0.2 GWA_SLEEP=0.1 &
# the whole portal: both families' rules, one jump each, and the status
portal_up() {
  test "$(prop sys.glassworks.portal.status)" = "on wlan0" && jumps4 && test "$(nat 4 | grep -c '^-A GWPORTAL')" = 2 &&
    test "$(nat 6 | grep -c '^-A GWPORTAL')" = 1 && test "$(nat 6 | grep -c '^-A PREROUTING -j GWPORTAL$')" = 1
}
H=$!; first_check; hostapd_on; bg_until portal_up
check "the loop between the helper's passes: up again within seconds, the whole of it" portal_up
ap_up; portal; echo 6 > /t/ipt-refuse; rm -f "$T/ipt/6-nat"; portal
check "ip6tables refuses after the IPv4 rules went in: nothing left half-done (no chain, no jump), and an error" \
  test -z "$(nat 4 | grep GWPORTAL)$(nat 6 | grep GWPORTAL)" -a "$(prop sys.glassworks.portal.status)" = "error: iptables refused the rules"
rm -f /t/ipt-refuse; portal
check "...and on once it takes them" test "$(prop sys.glassworks.portal.status)" = "on wlan0"
# One agent all along: ip6tables refuses, and the IPv4 rules can't be removed either. When the hotspot then stops,
# the next check must still clear them, not take the failed clear for "off".
hostapd_off; ap_up; portal; hostapd_on; echo 6 > /t/ipt-refuse; touch /t/ipt-nodel; rm -f "$T/ipt/6-nat"; : > "$T/setprop.log"
LOOPS=100000 helper GWA_PROC_NET="$T/net" GWA_PORTAL_SLEEP=0.2 GWA_SLEEP=0.1 &
H=$!; logged '^sys.glassworks.portal.status=error: iptables refused the rules'
check "(the half-done IPv4 rules stay, the clear refused)" rules4
hostapd_off; rm -f /t/ipt-refuse /t/ipt-nodel; bg_until gone
check "...once the hotspot stops and iptables takes removals again, the same agent clears them and says off" gone
hostapd_off; ap_up
# what an old agent left: our chain, a rule and the jump, in both families
printf ':GWPORTAL\n-A PREROUTING -j GWPORTAL\n%s\n' "$RULE4_DNS" > "$T/ipt/4-nat"
printf ':GWPORTAL\n-A PREROUTING -j GWPORTAL\n%s\n' "$RULE6_DNS" > "$T/ipt/6-nat"
check "(setup: an old agent's chain, rule and jump in both families)" test "$(nat 4 | grep -c GWPORTAL)/$(nat 6 | grep -c GWPORTAL)" = 3/3
portal
check "a new helper removes what an old one left" test -z "$(nat 4 | grep GWPORTAL)$(nat 6 | grep GWPORTAL)"
check "...the filter tables untouched" test "$(cat "$T/ipt/4-filter")" = "$FILTER" -a "$(cat "$T/ipt/6-filter")" = "$FILTER"
for b in iptables ip6tables iptables-restore ip6tables-restore iptables-save ip6tables-save; do printf '#!/bin/sh\nexit 0\n' | w "$T/bin/$b"; done
w "$T/bin/ip" <<'EOF'
#!/bin/sh
case "$*" in *"rule del"*) exit 1 ;; esac; exit 0
EOF
rm -rf "$T/net" "$T/ipt" "$T/ip-addr4"; rm -f /data/s1kiosk/radio/radio.conf

# ------------------------------------------------------------------------------ adbd checks keys
# Aqara's adbd stores false over auth_required (0x20d0); a Magisk module mounts a copy with that store a nop. A made-up
# file stands in for adbd: random bytes, with 4.5.80's four bytes at 0x20ce (movs r0,#0; strb r0,[r4]).
echo "--- adbd checks keys"
reset_radio; echo off > "$T/props/persist.glassworks.radio_mode"
AA=$T/aa; rm -rf "$AA"; mkdir -p "$AA/modules" "$AA/magisk"; : > "$AA/magisk/busybox"
python3 -c 'import os,sys; b=bytearray(os.urandom(30336)); b[0x20ce:0x20d2]=bytes([0,0x20,0x20,0x70]); open(sys.argv[1],"wb").write(b); b[0x20d0:0x20d2]=bytes([0,0xbf]); open(sys.argv[2],"wb").write(b)' "$AA/adbd" "$AA/adbd.fixed"
AS=$(sha256sum "$AA/adbd" | cut -d' ' -f1); AF=$(sha256sum "$AA/adbd.fixed" | cut -d' ' -f1)
rm -f "$T/props/persist.glassworks.adb_auth" "$T/props/sys.glassworks.adb.auth"
aa() { LOOPS=1 helper GWA_MODULES="$AA/modules" GWA_MAGISK_BB="$AA/magisk/busybox" GWA_AA_STOCK="$AS" GWA_AA_FIXED="$AF" GWA_AA_WAIT=3 GWA_AA_EVERY=1 GWA_AA_STEADY=2 "$@"; prop sys.glassworks.adb.auth; }
AM=$AA/modules/glassworks_adbd_auth
st=$(aa GWA_AA_LIVE="$AA/adbd")
check "Aqara's adbd: the module is made, from the next restart" test "$st" = "on after a restart"
check "...its adbd is the panel's with the one store a nop, byte for byte" cmp -s "$AM/system/bin/adbd" "$AA/adbd.fixed"
check "...0755 root:shell, module.prop with its id" test "$(stat -c %a:%u:%g "$AM/system/bin/adbd")" = 755:0:2000 -a "$(grep -c '^id=glassworks_adbd_auth$' "$AM/module.prop")" = 1
check "...the panel's own adbd untouched" test "$(sha256sum "$AA/adbd" | cut -d' ' -f1)" = "$AS"
check "...no stage left in the agent's dir" test ! -e /data/s1kiosk/radio/adbd-auth-module.new
echo adb > "$T/props/sys.usb.config"; echo running > "$T/props/init.svc.adbd"
st=$(aa GWA_AA_LIVE="$AA/adbd.fixed")
check "mounted (the live adbd is the patched one) and running: on" test "$st" = on
sleep 3; check "...and the watch leaves it" test -f "$AM/system/bin/adbd" -a ! -e /data/s1kiosk/radio/adbd-auth-failed
touch "$AM/disable"; st=$(aa GWA_AA_LIVE="$AA/adbd")
check "disabled in Magisk: said so" test "$st" = "off: the module is disabled in Magisk"
rm -f "$AM/disable"; st=$(aa GWA_AA_LIVE="$AA/adbd" GWA_MAGISK_BB="$AA/magisk/none")
check "Magisk loads no modules here: said so" has "$st" "off: Magisk loads no modules here"
echo stopped > "$T/props/init.svc.adbd"
aa GWA_AA_LIVE="$AA/adbd.fixed" >/dev/null; sleep 5
check "the patched adbd mounted but not running: the module goes" test ! -e "$AM"
check "...remembered, root 0600" test "$(stat -c %a:%u /data/s1kiosk/radio/adbd-auth-failed)" = 600:0
check "...and said" has "$(prop sys.glassworks.adb.auth)" "error: the patched adbd didn't run"
st=$(aa GWA_AA_LIVE="$AA/adbd")
check "...not made again at the next start" test ! -e "$AM" -a "$st" = "off: the patched adbd didn't run, so it was taken out"
rm -f /data/s1kiosk/radio/adbd-auth-failed; echo running > "$T/props/init.svc.adbd"
aa GWA_AA_LIVE="$AA/adbd" >/dev/null
echo off > "$T/props/persist.glassworks.adb_auth"; st=$(aa GWA_AA_LIVE="$AA/adbd.fixed")
check "the undo (persist.glassworks.adb_auth=off): the module goes, from the next restart" test ! -e "$AM" -a "$st" = "off after a restart"
st=$(aa GWA_AA_LIVE="$AA/adbd"); check "...then off" test "$st" = off
rm -f "$T/props/persist.glassworks.adb_auth"; head -c 30336 /dev/urandom > "$AA/other"
st=$(aa GWA_AA_LIVE="$AA/other")
check "another firmware's adbd: left alone" test ! -e "$AM"
check "...said so" has "$st" "off: not the adbd this patch knows"
rm -rf "$AA" "$T/props/sys.usb.config" "$T/props/init.svc.adbd" "$T/props/sys.glassworks.adb.auth" /data/s1kiosk/radio/adbd-auth-failed

# ------------------------------------------------------------------------------ setup hotspot name
# #197: with hotspot-name.apk in the running frame bundle, the agent puts it on /product through a Magisk module
# (Android applies it from the next restart); without it, or with persist.glassworks.hotspot_name=android, it removes
# the module. A fake `cmd overlay list` says whether Android applies the overlay now.
echo "--- setup hotspot name (#197)"
reset_radio; mkdir -p /data/s1kiosk/radio "$T/rb" "$T/modules"; echo "RADIO_ALLOW=any" > /data/s1kiosk/radio/radio.conf
echo off > "$T/props/persist.glassworks.radio_mode"
w "$T/bin/cmd" <<'EOF'
#!/bin/sh
[ "$1 $2 $3" = "overlay list sh.edm.s1kiosk.overlay.hotspotname" ] || exit 1
[ -f /t/cmd-fail ] && exit 20   # the overlay service not answering
[ -f /t/cmd-hang ] && exec sleep 1000   # ...or never answering
# /t/overlay-on: Android applies it (its record, and the APK mounted, which name() points the helper at);
# /t/overlay-stale: only Android's record, which outlives the APK on Android 10
{ [ -f /t/overlay-on ] || [ -f /t/overlay-stale ]; } && echo "[x] sh.edm.s1kiosk.overlay.hotspotname"; exit 0
EOF
printf 'overlay-v1' > "$T/rb/hotspot-name.apk"
MOD="$T/modules/s1kiosk_hotspot_name"; APKP="$MOD/system/product/overlay/S1HotspotName/S1HotspotName.apk"
mkdir -p "$T/magisk" && : > "$T/magisk/busybox"   # Magisk's own setup, as the Magisk app leaves it
name() { LOOPS=1 helper GW_BUNDLE="$T/rb" GWA_MODULES="$T/modules" GWA_MAGISK_BB="$T/magisk/busybox" GWA_HN_LIVE=/t/overlay-on "$@"; prop sys.glassworks.hotspot_name.status; }
st=$(umask 077; name)   # as init runs services: what root writes is private unless the helper says otherwise
check "the overlay in the bundle: a Magisk module puts it on /product" test "$(cat "$APKP")" = overlay-v1
check "...with its module.prop" grep -qx "id=s1kiosk_hotspot_name" "$MOD/module.prop"
check "...root-owned, readable by Android (system_server), not writable by others" \
  test "$(stat -c %a:%u "$APKP")/$(stat -c %a "$MOD")/$(stat -c %a "${APKP%/*}")/$(stat -c %a "$MOD/module.prop")" = "644:0/755/755/644"
check "...and Glassworks from the next restart" test "$st" = "glassworks after a restart"
st=$(name GWA_MAGISK_BB="$T/magisk/none")
check "Magisk without its setup (no busybox in /data/adb/magisk): said so, no promise of a restart" \
  test "$st" = "android: Magisk loads no modules here (no $T/magisk/none)"
check "...and the module stays in place for when it has one" test "$(cat "$APKP")" = overlay-v1
touch /t/overlay-on; t0=$(stat -c %Y "$APKP"); sleep 1.1
st=$(name)
check "after the restart Android applies it: glassworks" test "$st" = glassworks
check "...and the module isn't written again" test "$(stat -c %Y "$APKP")" = "$t0"
printf 'overlay-v2' > "$T/rb/hotspot-name.apk"; name >/dev/null
check "a new overlay in a newer bundle replaces it" test "$(cat "$APKP")" = overlay-v2 -a ! -e /data/s1kiosk/radio/hotspot-name-module.new
printf android > "$T/props/persist.glassworks.hotspot_name"; st=$(name)
check "persist.glassworks.hotspot_name=android: the module goes" test ! -e "$MOD"
check "...and Android's name from the next restart" test "$st" = "android after a restart"
rm -f /t/overlay-on; touch /t/overlay-stale; st=$(name)
check "...then android, though Android 10 keeps its [x] record of the overlay with the APK gone" test "$st" = android
rm -f /t/overlay-stale
rm -f "$T/props/persist.glassworks.hotspot_name"; name >/dev/null
check "(the module is back)" test -e "$MOD"
# TODO(#528): the old name, which a 0.4.3 app sets, until the old names go
printf android > "$T/props/persist.s1kiosk.hotspot_name"; st=$(name)
check "the old name persist.s1kiosk.hotspot_name=android: moved to the new one, and the module goes" \
  test ! -e "$MOD" -a "$(prop persist.glassworks.hotspot_name)" = android
rm -f "$T/props/persist.s1kiosk.hotspot_name"; name >/dev/null
check "...and its undo follows too: the module is back" test -e "$MOD" -a -z "$(prop persist.glassworks.hotspot_name)"
rm -f "$T/rb/hotspot-name.apk"; name >/dev/null
check "a bundle without the overlay removes the module" test ! -e "$MOD"
printf 'overlay-v1' > "$T/rb/hotspot-name.apk"; name >/dev/null
check "(the module is back)" test -e "$MOD"
name GW_BUNDLE= >/dev/null
check "the boot image's helper (no bundle) gives Android's name back" test ! -e "$MOD"
printf 'overlay-dev' > /data/s1kiosk/radio/hotspot-name.apk; name GW_BUNDLE= GW_DEV=1 >/dev/null
check "with dev-override, a developer's copy in the state dir" test "$(cat "$APKP")" = overlay-dev
check "...nothing staged where Magisk loads modules: one module dir there, ours" test "$(ls -A "$T/modules")" = s1kiosk_hotspot_name
# where the copy is staged: a cp that logs its arguments and does the copy
w "$T/bin/cp" <<'EOF'
#!/bin/sh
echo "$@" >> /t/cp.log; exec /bin/cp "$@"
EOF
: > /t/cp.log; printf 'overlay-v3' > "$T/rb/hotspot-name.apk"; name >/dev/null; /bin/rm -f "$T/bin/cp"
check "the module is staged outside Magisk's dir, never in it" \
  test -n "$(grep hotspot-name.apk /t/cp.log)" -a -z "$(grep "$T/modules" /t/cp.log)" -a "$(cat "$APKP")" = overlay-v3
# A helper stopped after staging: its stage, complete, is outside Magisk's dir, and the next start clears it
mkdir -p /data/s1kiosk/radio/hotspot-name-module.new/system && cp "$MOD/module.prop" /data/s1kiosk/radio/hotspot-name-module.new/
printf android > "$T/props/persist.glassworks.hotspot_name"; name >/dev/null; rm -f "$T/props/persist.glassworks.hotspot_name"
check "a stage an interrupted run left, then the undo: no module and no stage anywhere" \
  test -z "$(ls -A "$T/modules")" -a ! -e /data/s1kiosk/radio/hotspot-name-module.new
# A /data that refuses a removal (read-only, full): a fake rm fails for the path in /t/rm-fail.
w "$T/bin/rm" <<'EOF'
#!/bin/sh
for a in "$@"; do case "$a" in -*) ;; *) [ -f /t/rm-fail ] && [ "$a" = "$(cat /t/rm-fail)" ] && { echo "rm: $a: Read-only file system" >&2; exit 1; } ;; esac; done
exec /bin/rm "$@"
EOF
w "$T/bin/mv" <<'EOF'
#!/bin/sh
src=""; for a in "$@"; do case "$a" in -*) ;; *) [ -n "$src" ] || src=$a ;; esac; done
[ -f /t/mv-fail ] && [ "$src" = "$(cat /t/mv-fail)" ] && { echo "mv: $src: Read-only file system" >&2; exit 1; }
exec /bin/mv "$@"
EOF
rm -f /data/s1kiosk/radio/hotspot-name.apk; printf 'overlay-v1' > "$T/rb/hotspot-name.apk"; name >/dev/null
check "(the module is in place)" test "$(cat "$APKP")" = overlay-v1
printf '%s' /data/s1kiosk/radio/hotspot-name.apk.new > /t/mv-fail; printf 'overlay-v2' > "$T/rb/hotspot-name.apk"; st=$(name)
check "the new overlay can't be renamed in: an error, the working one untouched, nothing half-made" \
  test "$st" = "error: could not put the module in place" -a "$(cat "$APKP")" = overlay-v1 \
    -a ! -e /data/s1kiosk/radio/hotspot-name.apk.new
/bin/rm -f /t/mv-fail "$T/bin/mv"
# a permission step that fails: the stage never replaces the module
w "$T/bin/chown" <<'EOF'
#!/bin/sh
[ -f /t/chown-fail ] && exit 1; exec /bin/chown "$@"
EOF
touch /t/chown-fail; st=$(name)
check "a failed chown: an error, the working module unchanged" \
  test "$st" = "error: could not write the module" -a "$(cat "$APKP")" = overlay-v1 -a ! -e /data/s1kiosk/radio/hotspot-name.apk.new
/bin/rm -f /t/chown-fail "$T/bin/chown"; name >/dev/null
check "(then it goes in)" test "$(cat "$APKP")" = overlay-v2
# switched off in Magisk: no promise of a name the next boot won't bring
touch "$MOD/disable"; st=$(name)
check "the module disabled in Magisk: said so, not \"after a restart\"" test "$st" = "android: the module is disabled in Magisk"
touch /t/overlay-on; st=$(name); rm -f /t/overlay-on
check "...but still mounted this boot: Android's name from the next restart" test "$st" = "android after a restart"
ino=$(stat -c %i "$MOD"); printf 'overlay-v4' > "$T/rb/hotspot-name.apk"; name >/dev/null
check "a new overlay replacing a disabled module keeps it disabled" test "$(cat "$APKP")" = overlay-v4 -a -e "$MOD/disable"
check "...the module's own dir never moves: only the APK is renamed in" test "$(stat -c %i "$MOD")" = "$ino"
rm -f "$MOD/disable"
# what an interrupted run left: a new overlay not yet renamed in, and a stage with a disable mark of its own
printf junk > /data/s1kiosk/radio/hotspot-name.apk.new
mkdir -p /data/s1kiosk/radio/hotspot-name-module.new && : > /data/s1kiosk/radio/hotspot-name-module.new/disable
printf 'overlay-v5' > "$T/rb/hotspot-name.apk"; name >/dev/null
check "an earlier run's leftovers are cleared first, and nothing of them gets into the module" \
  test "$(cat "$APKP")" = overlay-v5 -a ! -e "$MOD/disable" -a ! -e /data/s1kiosk/radio/hotspot-name.apk.new \
    -a ! -e /data/s1kiosk/radio/hotspot-name-module.new
# the overlay service not answering: an error, not "android"
touch /t/cmd-fail; st=$(name); rm -f /t/cmd-fail
check "Android's overlay query fails: an error, not a guess" test "$st" = "error: Android didn't say whether the overlay is on"
# never answering: an outer deadline (a watchdog ends the hang after 120 s) so a helper without its own bound is
# caught as slow, not left hanging the suite
touch /t/cmd-hang; : > "$T/setprop.log"; ( sleep 120; pkill -f "sleep 1000" ) & W=$!
t0=$(date +%s); st=$(name GWA_HN_TIMEOUT=2); t1=$(date +%s)
kill "$W" 2>/dev/null; rm -f /t/cmd-hang; pkill -f "sleep 1000" 2>/dev/null
check "a query that never returns: bounded, an error, and the helper goes on to its radio loop" \
  test "$st" = "error: Android didn't say whether the overlay is on" -a $((t1 - t0)) -lt 90 \
    -a -n "$(grep '^sys.glassworks.radio.status=ok off' "$T/setprop.log")"
printf '%s' "$MOD" > /t/rm-fail
printf android > "$T/props/persist.glassworks.hotspot_name"; st=$(name)
check "the undo can't remove it: an error, not \"android after a restart\"" test "$st" = "error: could not remove the module" -a -e "$MOD"
/bin/rm -f /t/rm-fail "$T/bin/rm"; name >/dev/null
check "...and once /data takes it, the next start does the undo" test ! -e "$MOD"
rm -f "$T/props/persist.glassworks.hotspot_name"
rm -f /data/s1kiosk/radio/hotspot-name.apk; rm -rf "$T/modules"
st=$(name)
check "no Magisk modules dir: an error, and nothing made" test "$st" = "error: no Magisk modules dir" -a ! -e "$T/modules"
printf '#!/bin/sh\nexit 1\n' | w "$T/bin/cmd"; rm -rf "$T/rb" "$T/modules" /t/overlay-on /t/overlay-stale; rm -f /data/s1kiosk/radio/radio.conf

# ----------------------------------------------------------------------------- boot writer (#139, route 2)
# glassworks_bootwriter.sh puts the frame loader into a 0.4.3 boot image from the radio bundle. Magisk can't be in
# this repo, so a stand-in APK holds a fake magiskboot (Python, over a toy image format: "ANDROID!", a kernel, a
# header and cpio entries, padded with zeros to the partition), a fake boot_patch.sh and a busybox that is sh. The
# real patch-ramdisk.sh (the FEL install's patch) and the real writer run against them. The boot partition is a file.
echo "--- glassworks_bootwriter.sh"
BW=/tbw; rm -rf "$BW"; mkdir -p "$BW/apk/lib/armeabi-v7a" "$BW/apk/assets"
bash "$R/frames/aqara-s1-plus/tools/root/bootwriter-files.sh" "$BW/files" >/dev/null
cp "$TB/otbr-agent" "$TB/ot-ctl" "$BW/" 2>/dev/null || { printf 'otbr' > "$BW/otbr-agent"; printf 'otctl' > "$BW/ot-ctl"; }
cat > "$BW/apk/lib/armeabi-v7a/libmagiskboot.so" <<'PY'
#!/usr/bin/env python3
# fake magiskboot: the commands patch-ramdisk.sh, boot_patch.sh and the writer use, over the toy image format
import hashlib, os, sys
def load(img):
    b = open(img, 'rb').read().rstrip(b'\0').decode()
    if not b.startswith('ANDROID!\n'): sys.exit(1)
    k, h, e = '', [], []
    for l in b.split('\n')[1:]:
        if l.startswith('kernel '): k = l[7:]
        elif l.startswith('header '): h.append(l[7:])
        elif l.startswith('entry '): e.append(l[6:])
    return k, h, e
def ents(f):
    d = {}
    for l in open(f).read().splitlines():
        p, m, x = l.split(' ', 2); d[p] = (m, x)
    return d
def save(f, d): open(f, 'w').write(''.join('%s %s %s\n' % (p, *d[p]) for p in sorted(d)))
a = sys.argv[1:]
if a[0] == 'unpack':
    img = a[-1]; k, h, e = load(img)
    open('kernel', 'w').write(k); open('ramdisk.cpio', 'w').write(''.join(x + '\n' for x in e))
    if '-h' in a: open('header', 'w').write(''.join(x + '\n' for x in h))
elif a[0] == 'repack':
    orig = a[1]; out = a[2] if len(a) > 2 else 'new-boot.img'
    k, h, _ = load(orig); size = os.path.getsize(orig)
    if os.path.exists('kernel'): k = open('kernel').read()
    e = open('ramdisk.cpio').read().splitlines()
    b = ('ANDROID!\nkernel %s\n' % k + ''.join('header %s\n' % x for x in h) + ''.join('entry %s\n' % x for x in e)).encode()
    if len(b) > size: sys.exit(1)
    open(out, 'wb').write(b + b'\0' * (size - len(b)))
elif a[0] == 'cleanup':
    for f in ('kernel', 'ramdisk.cpio', 'header'):
        if os.path.exists(f): os.remove(f)
elif a[0] == 'sha1': print(hashlib.sha1(open(a[1], 'rb').read()).hexdigest())
elif a[0].startswith('compress'): open(a[2], 'wb').write(open(a[1], 'rb').read())
elif a[0] in ('hexpatch', 'dtb'): sys.exit(1)
elif a[0] == 'cpio':
    f = a[1]; d = ents(f); rc = 0
    for c in a[2:]:
        w = c.split()
        if not w or w[0].startswith('#'): continue
        if w[0] == 'test': rc = 1 if '.backup/.magisk' in d else (2 if 'init.unsupported' in d else 0)
        elif w[0] == 'exists': rc = 0 if w[1] in d else 1
        elif w[0] == 'extract':
            if w[1] not in d: sys.exit(1)
            open(w[2], 'wb').write(bytes.fromhex(d[w[1]][1]) if d[w[1]][1] != 'dir' else b'')
        elif w[0] == 'ls':
            for p in sorted(d):
                if p.startswith(w[-1] + '/'): print('%s 0 0 %s' % (d[p][0], p))
        elif w[0] == 'rm': d = {p: v for p, v in d.items() if p != w[-1] and not p.startswith(w[-1] + '/')}
        elif w[0] == 'add': d[w[2]] = (w[1], open(w[3], 'rb').read().hex() or '00')
        elif w[0] == 'mkdir': d[w[2]] = (w[1], 'dir')
    save(f, d); sys.exit(rc)
PY
cat > "$BW/apk/assets/boot_patch.sh" <<'SH'
#!/bin/sh
# fake Magisk boot_patch.sh: unpack, Magisk's entries and config (KEEPVERITY, PREINITDEVICE), repack to new-boot.img
cd "$(dirname "$0")"
./magiskboot unpack "$1" || exit 1
./magiskboot cpio ramdisk.cpio test; [ $? = 2 ] && exit 1
printf 'KEEPVERITY=%s\nPREINITDEVICE=%s\n' "$KEEPVERITY" "$PREINITDEVICE" > config
./magiskboot cpio ramdisk.cpio "add 0750 init magiskinit" "mkdir 0750 overlay.d" "mkdir 0750 overlay.d/sbin" \
  "add 0644 overlay.d/sbin/magisk.xz magisk" "add 000 .backup/.magisk config"
[ -f /t/bp-kernel-change ] && printf 'another-kernel' > kernel
./magiskboot repack "$1"
SH
: > "$BW/apk/assets/util_functions.sh"
printf '#!/bin/sh\n[ "$1" = sh ] || exit 1; shift; exec /bin/sh "$@"\n' > "$BW/apk/lib/armeabi-v7a/libbusybox.so"
for b in magiskinit magisk init-ld; do printf 'fake %s' "$b" > "$BW/apk/lib/armeabi-v7a/lib$b.so"; done
( cd "$BW/apk" && python3 -c 'import os, zipfile
z = zipfile.ZipFile("../magisk.apk", "w")
for r, _, fs in os.walk("."):
    for f in fs: z.write(os.path.join(r, f), os.path.relpath(os.path.join(r, f), "."))' )
MAPK_SHA=$(sha256sum "$BW/magisk.apk" | cut -d' ' -f1); MAPK_SIZE=$(stat -c %s "$BW/magisk.apk")
command -v unzip >/dev/null || w "$T/bin/unzip" <<'PY'
#!/usr/bin/env python3
import sys, zipfile
a = [x for x in sys.argv[1:] if not x.startswith('-')]
z = zipfile.ZipFile(a[0]); names = set(z.namelist())
for n in a[1:]:
    if n not in names: sys.exit(11)
    z.extract(n, '.')
PY
# a 0.4.3 image: Magisk's entries, the old services, the adb key, the boot animation (toy format, 1 MiB)
mkimg() { # out kind: patched043 | dev043 | loader | stock | nosvc | garbage
  python3 - "$1" "$2" <<'PY'
import sys
out, kind = sys.argv[1], sys.argv[2]
e = {}
def add(p, m, data): e[p] = (m, data.encode().hex() if data != 'dir' else 'dir')
add('init', '0750', 'magiskinit')
if kind != 'stock':
    add('.backup/.magisk', '000', 'KEEPVERITY=true\nPREINITDEVICE=metadata\n')
    add('overlay.d', '0750', 'dir'); add('overlay.d/sbin', '0750', 'dir'); add('overlay.d/sbin/magisk.xz', '0644', 'magisk')
if kind in ('patched043', 'dev043'):
    add('overlay.d/s1kiosk_radio.rc', '0644', 'service s1kiosk_radio'); add('overlay.d/sbin/s1kiosk_radio.sh', '0700', 'old helper')
    add('overlay.d/sbin/s1kiosk_adb_keys', '0600', 'QAAAAfake-installer-key test@host')
    add('overlay.d/sbin/s1kiosk_bootanimation.zip', '0644', 'anim-043')
if kind == 'dev043':
    add('overlay.d/s1kiosk_devsu.rc', '0644', 'service s1kiosk_devsu'); add('overlay.d/sbin/s1kiosk_devsu.sh', '0700', 'devsu 8760')
if kind == 'loader':
    add('overlay.d/glassworks.rc', '0644', 'service glassworks_agent')
b = 'ANDROID!\nkernel stock-kernel-4.5.80\nheader cmdline=console=fake\nheader os_version=10.0.0\n' + ''.join('entry %s %s %s\n' % (p, *e[p]) for p in sorted(e))
if kind == 'garbage': b = 'NOTANIMAGE\n'
b = b.encode(); open(out, 'wb').write(b + b'\0' * (1048576 - len(b)))
PY
}
ent() { python3 -c 'import sys
b = open(sys.argv[1], "rb").read().rstrip(b"\0").decode()
for l in b.split("\n"):
    if l.startswith("entry " + sys.argv[2] + " "):
        m, x = l.split(" ")[2:4]; print(m, bytes.fromhex(x).decode() if x != "dir" else "dir")' "$1" "$2"; }
entmode() { ent "$1" "$2" 2>/dev/null | head -1 | cut -d' ' -f1; }
kern() { sed -n 's/^kernel //p' "$1" | head -1; }
BWD=/data/s1kiosk/radio/boot-writer; BDEV=$T/bootdev
w "$T/bin/fake-reboot" <<'EOF'
#!/bin/sh
echo reboot >> /t/reboots
EOF
# dd that mangles what it reads or writes, once per marker: /t/dd-read-flip (the next export of $T/bootdev comes out
# different), /t/dd-write-flip (the next write to it lands with a byte changed); a number = how many times
DD=$(PATH=/usr/bin:/bin command -v dd)   # the real one, never this wrapper
w "$T/bin/dd" <<EOF
#!/bin/sh
$DD "\$@"; rc=\$?
for a in "\$@"; do case "\$a" in if=$T/bootdev) src=1 ;; of=$T/bootdev) dst=1 ;; of=*) out=\${a#of=} ;; esac; done
flip() { python3 -c 'import sys; f = open(sys.argv[1], "r+b"); f.seek(100); c = f.read(1); f.seek(100); f.write(bytes([c[0] ^ 1]))' "\$1"; }
take() { n=\$(cat "\$1" 2>/dev/null) || return 1; [ "\${n:-1}" -gt 1 ] && echo \$((n - 1)) > "\$1" || rm -f "\$1"; return 0; }
[ -n "\$src" ] && [ -n "\$out" ] && take /t/dd-read-flip && flip "\$out"
[ -n "\$dst" ] && take /t/dd-write-flip && flip $T/bootdev
exit \$rc
EOF
bw_reset() { # a 0.4.3 panel: this image in boot, the app's Magisk download in place, no writer state
  rm -rf "$BWD" /data/glassworks-pub /t/reboots /t/dd-read-flip /t/dd-write-flip /t/bp-kernel-change
  rm -f "$T/props/ro.glassworks.frame.id" "$T/props/sys.s1kiosk.bootwriter.status"
  mkimg "$BDEV" "${1:-patched043}"; cp "$BDEV" "$T/boot-before.img"
  mkdir -p "$APP/files/boot-writer"; cp "$BW/magisk.apk" "$APP/files/boot-writer/magisk.apk"; chown -R 10050:10050 "$APP/files/boot-writer"
  echo thread_router > "$T/props/persist.s1kiosk.radio_mode"
}
bwrun() { # env...: one writer run, as the bundle's helper starts it
  env GWB_FILES="$BW/files" GWB_RADIO="$BW" GWB_BOOTFILES="$TB" GWB_BOOTDEV="$BDEV" GWB_DROP_CACHES="$T/drop_caches" \
    GWB_MAGISK_SHA256="$MAPK_SHA" GWB_MAGISK_SIZE="$MAPK_SIZE" GWB_NEED_KB=1024 GWB_BUNDLE_VERSION=40400 GWB_MAGISK_WAIT=0 \
    GWB_REBOOT="$T/bin/fake-reboot" "$@" /system/bin/sh "$BW/files/glassworks_bootwriter.sh" >/dev/null 2>&1
}
bwpub() { cut -d' ' -f1 /data/glassworks-pub/boot-writer 2>/dev/null; }
same_boot() { cmp -s "$BDEV" "$T/boot-before.img"; }

bw_reset; bwrun
check "a 0.4.3 image: written, read back, restarted once" test "$(bwpub)/$(wc -l < /t/reboots 2>/dev/null)" = restarting/1
check "...the loader in, the 0.4.3 services out" \
  test -n "$(ent "$BDEV" overlay.d/glassworks.rc)" -a -z "$(ent "$BDEV" overlay.d/s1kiosk_radio.rc)$(ent "$BDEV" overlay.d/sbin/s1kiosk_radio.sh)"
check "...the frame agent and the slot scripts, as the FEL install adds them" \
  test "$(entmode "$BDEV" overlay.d/sbin/glassworks_agent.sh)/$(entmode "$BDEV" overlay.d/sbin/glassworks_loader.sh)/$(entmode "$BDEV" overlay.d/glassworks.rc)" = 0700/0700/0644
check "...the installer's adb key and the boot animation carried over" \
  test "$(ent "$BDEV" overlay.d/sbin/glassworks_adb_keys)/$(ent "$BDEV" overlay.d/sbin/glassworks_bootanimation.zip)" = "0600 QAAAAfake-installer-key test@host/0644 anim-043"
check "...Magisk's busybox for its own setup" test -n "$(ent "$BDEV" overlay.d/sbin/magisk-busybox)"
check "...the same kernel" test "$(kern "$BDEV")" = stock-kernel-4.5.80
check "...the image's own pre-init partition, not cache: patch-ramdisk.sh reads it, as boot_patch.sh does outside Android" \
  has "$(ent "$BDEV" .backup/.magisk)" "PREINITDEVICE=metadata"
check "...the backup is the image as it was, root 0600 in a root 0700 dir" \
  test "$(cmp -s "$BWD/backup.img" "$T/boot-before.img" && echo same)/$(stat -c %a:%u "$BWD/backup.img")/$(stat -c %a:%u "$BWD")" = "same/600:0/700:0"
check "...the state says restarting, with the radio mode before the write" has "$(cat "$BWD/state")" "restarting "
check "...and thread_router" test "$(cut -d' ' -f5 "$BWD/state")" = thread_router
check "...the record for Manage is root 0644 in a root 0755 dir" \
  test "$(stat -c %a:%u /data/glassworks-pub/boot-writer)/$(stat -c %a:%u /data/glassworks-pub)" = "644:0/755:0"
check "...and the work dir is gone, the lock released" test ! -e "$BWD/work" -a ! -e "$BWD/run.lock"
after=$(sha256sum "$BDEV"); bwrun
check "started again on the old system after the restart: no second write, no second restart, an error" \
  test "$(bwpub)/$(wc -l < /t/reboots)/$(sha256sum "$BDEV")" = "error/1/$after"
echo aqara-s1-plus > "$T/props/ro.glassworks.frame.id"; bwrun
check "a boot that runs the loader: the writer does nothing" test "$(wc -l < /t/reboots)" = 1

bw_reset loader; bwrun
check "boot already has the loader: left alone, said so" test "$(bwpub)/$(ls /t/reboots 2>/dev/null)" = installed/
check "...not written" same_boot

bw_reset dev043; bwrun
check "a dev image keeps its su service, as it was" \
  test "$(ent "$BDEV" overlay.d/sbin/s1kiosk_devsu.sh)" = "0700 devsu 8760" -a -n "$(ent "$BDEV" overlay.d/s1kiosk_devsu.rc)"

bw_reset; echo 1 > /t/dd-read-flip; bwrun
check "two exports that differ: refused, nothing written, no restart" test "$(bwpub)/$(ls /t/reboots 2>/dev/null)" = refused/
check "...boot as it was" same_boot
check "...and the reason" has "$(cat /data/glassworks-pub/boot-writer)" "two exports of boot differ"
bwrun
check "...not tried again with the same bundle" test "$(bwpub)" = refused -a ! -e /t/reboots
bwrun GWB_BUNDLE_VERSION=40401
check "...a newer bundle tries again (and the exports agree now)" test "$(bwpub)" = restarting

bw_reset; touch /t/bp-kernel-change; bwrun
check "a patch that would change the kernel: refused, nothing written" test "$(bwpub)/$(ls /t/reboots 2>/dev/null)" = refused/ -a -n "$(same_boot && echo y)"
check "...saying the kernel differs" has "$(cat /data/glassworks-pub/boot-writer)" "kernel differs"

for k in stock nosvc garbage; do
  bw_reset "$k"; bwrun
  check "an image this writer doesn't know ($k): refused, nothing written" test "$(bwpub)/$(ls /t/reboots 2>/dev/null)" = refused/ -a -n "$(same_boot && echo y)"
done

bw_reset; bwrun GWB_NEED_KB=999999999
check "low space on /data: waits, nothing written, no backup" \
  test "$(bwpub)/$(ls /t/reboots 2>/dev/null)/$(ls "$BWD/backup.img" 2>/dev/null)" = waiting// -a -n "$(same_boot && echo y)"
check "...saying how much it needs" has "$(cat /data/glassworks-pub/boot-writer)" "not enough free space"
bwrun
check "...and goes ahead once there is room" test "$(bwpub)" = restarting

bw_reset; rm -f "$APP/files/boot-writer/magisk.apk"; bwrun
check "no Magisk APK yet: asks the app for it (need-magisk), nothing read or written" \
  test "$(bwpub)/$(ls "$BWD/backup.img" 2>/dev/null)" = need-magisk/ -a -n "$(same_boot && echo y)"
printf 'not magisk' > "$APP/files/boot-writer/magisk.apk"; chown 10050 "$APP/files/boot-writer/magisk.apk"; bwrun
check "...one of another size or hash: still need-magisk" test "$(bwpub)" = need-magisk
rm -f "$APP/files/boot-writer/magisk.apk"
bwrun GWB_MAGISK_WAIT=60 GWB_MAGISK_POLL=0.2 & BWP=$!
i=0; until [ "$(bwpub)" = need-magisk ] || [ $i -ge 100 ]; do sleep 0.1; i=$((i + 1)); done
check "...one run waits for it, saying need-magisk meanwhile" test "$(bwpub)" = need-magisk
cp "$BW/magisk.apk" "$APP/files/boot-writer/magisk.apk"; chown 10050:10050 "$APP/files/boot-writer/magisk.apk"
wait "$BWP"
check "...and goes on as soon as the app's download is there, in the same run" test "$(bwpub)" = restarting
bw_reset; rm -f "$APP/files/boot-writer/magisk.apk"
bwrun GWB_MAGISK_WAIT= GWB_MAGISK_POLL=0.1 & BWP=$!
sleep 3   # 30 polls: as long as the test can afford to stand for hours
check "...with no deadline (the default) it is still waiting, long after it asked" test "$(bwpub)" = need-magisk -a -n "$(kill -0 $BWP 2>/dev/null && echo y)"
cp "$BW/magisk.apk" "$APP/files/boot-writer/magisk.apk"; chown 10050:10050 "$APP/files/boot-writer/magisk.apk"
wait "$BWP"
check "...and an APK that arrives that late still goes through, in that run" test "$(bwpub)" = restarting

bw_reset; echo 1 > /t/dd-write-flip; bwrun
check "a readback that differs after the write: the backup goes back, checked, no restart" \
  test "$(bwpub)/$(ls /t/reboots 2>/dev/null)" = restored/ -a -n "$(same_boot && echo y)"
check "...and the state says restored, for this bundle" test "$(cut -d' ' -f1,2 "$BWD/state")" = "restored 40400"
bwrun
check "...not tried again with that bundle" test "$(bwpub)" = restored -a ! -e /t/reboots
bw_reset; echo 2 > /t/dd-write-flip; bwrun
check "the backup reads back wrong too: an error that says FEL, no restart, never again from here" \
  test "$(bwpub)/$(ls /t/reboots 2>/dev/null)/$(cut -d' ' -f1 "$BWD/state")" = error//torn
check "...saying so" has "$(cat /data/glassworks-pub/boot-writer)" "recover with FEL"
bwrun GWB_BUNDLE_VERSION=40409
check "...a newer bundle doesn't touch it either" test "$(cut -d' ' -f1 "$BWD/state")" = torn -a ! -e /t/reboots

bw_reset; mkdir -p "$BWD"; touch "$BWD/force-readback-mismatch"; bwrun
check "the root-made test switch forces the mismatch: the real restore runs" test "$(bwpub)" = restored -a -n "$(same_boot && echo y)"
check "...and the switch is used up" test ! -e "$BWD/force-readback-mismatch"
bw_reset; mkdir -p "$BWD"; touch "$BWD/force-readback-mismatch"; chown 10050 "$BWD/force-readback-mismatch"; bwrun
check "...one the app could have made doesn't count" test "$(bwpub)" = restarting

# A checkpoint /data won't take (full after the space check): no write without "written", no restart without
# "restarting". A mv that refuses the state file whose first word is in /t/state-refuse.
w "$T/bin/mv" <<'MV'
#!/bin/sh
for a in "$@"; do case "$a" in */boot-writer/state.tmp)
  [ -f /t/state-refuse ] && [ "$(cut -d' ' -f1 "$a")" = "$(cat /t/state-refuse)" ] && exit 1 ;; esac; done
exec /bin/mv "$@"
MV
bw_reset; echo written > /t/state-refuse; bwrun; rm -f /t/state-refuse
check "the 'written' checkpoint can't be recorded: nothing written, no restart, said so" \
  test "$(bwpub)/$(ls /t/reboots 2>/dev/null)/$(cut -d' ' -f1 "$BWD/state")" = waiting//prepared -a -n "$(same_boot && echo y)"
bwrun
check "...the next start resumes from prepared and goes through" test "$(bwpub)/$(wc -l < /t/reboots)" = restarting/1
bw_reset; echo restarting > /t/state-refuse; bwrun; rm -f /t/state-refuse
check "the 'restarting' checkpoint can't be recorded: written and read back, but no restart" \
  test "$(bwpub)/$(ls /t/reboots 2>/dev/null)/$(cut -d' ' -f1 "$BWD/state")" = error//written
bwrun
check "...the next start only restarts" test "$(bwpub)/$(wc -l < /t/reboots)" = restarting/1
/bin/rm -f "$T/bin/mv"

# Cut short (power, a helper restart): each state resumes, nothing twice
bw_reset; bwrun GWB_REBOOT=true; new=$(sha256sum "$BDEV" | cut -d' ' -f1)
cp "$T/boot-before.img" "$BDEV"; set -- $(cat "$BWD/state"); echo "prepared $2 $3 $4" > "$BWD/state"; bwrun
check "cut after the patch, before the write (state prepared, boot = backup): done from the start" \
  test "$(bwpub)/$(wc -l < /t/reboots)/$(sha256sum "$BDEV" | cut -d' ' -f1)" = "restarting/1/$new"
bw_reset; bwrun GWB_REBOOT=true; set -- $(cat "$BWD/state"); echo "written $2 $3 $4 $5" > "$BWD/state"
bwrun
check "cut after the write, before the restart (state written, boot = new): only the restart" \
  test "$(bwpub)/$(wc -l < /t/reboots)" = restarting/1
bw_reset; bwrun GWB_REBOOT=true; set -- $(cat "$BWD/state"); cp "$T/boot-before.img" "$BDEV"; echo "written $2 $3 $4 $5" > "$BWD/state"
bwrun
check "cut just before the write began (state written, boot = backup): written now, restarted once" \
  test "$(bwpub)/$(wc -l < /t/reboots)/$(sha256sum "$BDEV" | cut -d' ' -f1)" = "restarting/1/$new"
owner() { st=$(cat "/proc/$1/stat"); st=${st##*) }; set -- "$1" $st; echo "$(cat /proc/sys/kernel/random/boot_id) $1 ${21}"; }
bw_reset; mkdir -p "$BWD/run.lock"; owner $$ > "$BWD/run.lock/owner"; bwrun
check "another writer running (its lock: this boot, its pid, its start time): this one leaves" \
  test ! -e /data/glassworks-pub/boot-writer -a -n "$(same_boot && echo y)"
set -- $(owner $$); echo "$1 $2 $(( $3 + 1 ))" > "$BWD/run.lock/owner"; bwrun
check "...a lock whose pid now belongs to another process (start time differs) is taken over" test "$(bwpub)" = restarting
bw_reset; mkdir -p "$BWD/run.lock"; set -- $(owner $$); echo "00000000-0000-0000-0000-000000000000 $2 $3" > "$BWD/run.lock/owner"; bwrun
check "...so is one from an earlier boot" test "$(bwpub)" = restarting
bw_reset; mkdir -p "$BWD/run.lock"; echo "x 999999 1" > "$BWD/run.lock/owner"; bwrun
check "...and one whose writer is gone" test "$(bwpub)" = restarting

# The radio helper of a bundle that carries the writer starts it (the hook in s1kiosk_radio.sh), the boot copy doesn't
reset_radio; mkdir -p /data/s1kiosk/radio/bundle/current/bootwriter
printf '#!/bin/sh\necho "$GWB_FILES|$GWB_RADIO|$GWB_BOOTFILES|$GWB_DIR|$GWB_BUNDLE_VERSION" > /t/bw-ran\n' \
  > /data/s1kiosk/radio/bundle/current/bootwriter/glassworks_bootwriter.sh
printf 'S1RADIO-BUNDLE 1\nversion 40400\n' > /data/s1kiosk/radio/bundle/current/manifest; rm -f /t/bw-ran
env S1R_ONCE=1 S1R_SLEEP=0 S1R_NO_OVERRIDE=1 S1R_BUNDLE="$TB" S1R_RBUNDLE=/data/s1kiosk/radio/bundle/current \
  /system/bin/sh "$TB/s1kiosk_radio.sh" >/dev/null 2>&1
i=0; until [ -e /t/bw-ran ] || [ $i -ge 50 ]; do sleep 0.1; i=$((i + 1)); done
check "a bundle's helper starts the writer with the bundle's files and its version" \
  test "$(cat /t/bw-ran 2>/dev/null)" = "/data/s1kiosk/radio/bundle/current/bootwriter|/data/s1kiosk/radio/bundle/current|$TB|/data/s1kiosk/radio/boot-writer|40400"
rm -f /t/bw-ran; env S1R_ONCE=1 S1R_SLEEP=0 S1R_NO_OVERRIDE=1 S1R_BUNDLE="$TB" /system/bin/sh "$TB/s1kiosk_radio.sh" >/dev/null 2>&1; sleep 0.5
check "...the boot image's own helper (no bundle) never does" test ! -e /t/bw-ran
rm -rf /data/s1kiosk/radio/bundle

# After the restart: the frame agent checks what the writer promised, once, for Manage
bwagent() { # state-line radio-mode-now [netwatch state]: one agent start on the new image; waits for its check
  rm -rf /data/glassworks-pub; mkdir -p "$BWD"; echo "$1" > "$BWD/state"
  printf '%s' "$2" > "$T/props/persist.glassworks.radio_mode"; rm -f "$T/props/persist.s1kiosk.radio_mode"
  echo aqara-s1-plus > "$T/props/ro.glassworks.frame.id"; echo running > "$T/props/init.svc.glassworks_agent"
  echo "${3:-running}" > "$T/props/init.svc.glassworks_netwatch"
  LOOPS=1 helper GWA_BOOTDEV="$BDEV" GWA_BW_EVERY=0.1 GWA_BW_TRIES=5
  i=0; until grep -q '^checked' "$BWD/state" 2>/dev/null || [ $i -ge 100 ]; do sleep 0.1; i=$((i + 1)); done
}
mkimg "$BDEV" loader; NS=$(sha256sum "$BDEV" | cut -d' ' -f1)
bwagent "restarting aaa $NS 40400 thread_router" thread_router
check "the first boot with the loader: ok for Manage, once" test "$(bwpub)/$(cat "$BWD/state")" = "ok/checked ok"
check "...in words" has "$(cat /data/glassworks-pub/boot-writer)" "radio mode thread_router kept"
bwagent "restarting aaa $NS 40400 thread_router" zigbee_tcp
check "the radio mode didn't carry over: an error that says so" has "$(cat /data/glassworks-pub/boot-writer)" "radio mode is 'zigbee_tcp', was 'thread_router'"
bwagent "written aaa $NS 40400 thread_router" thread_router stopped
check "a slot that stays down through the wait: an error naming it (state written: a restart power made counts too)" \
  has "$(cat /data/glassworks-pub/boot-writer)" "glassworks_netwatch isn't running"
bwagent "restarting aaa 0000 40400 thread_router" thread_router
check "boot no longer holds the image written: an error" has "$(cat /data/glassworks-pub/boot-writer)" "no longer holds"
bwagent "checked ok" thread_router
check "checked once: a later start writes nothing" test ! -e /data/glassworks-pub/boot-writer
rm -f "$T/bin/dd" "$T/props/ro.glassworks.frame.id" "$T/props/init.svc.glassworks_agent" "$T/props/init.svc.glassworks_netwatch"
rm -rf "$BW" "$BWD" /data/glassworks-pub "$APP/files/boot-writer"


echo
echo "shell tests: $PASS passed, $FAILS failed"
[ $FAILS = 0 ]
