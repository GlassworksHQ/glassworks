#!/usr/bin/env bash
# Off-device tests of frames/aqara-s1-plus/tools/install.sh's signing-key switch and Wi-Fi-keeping --undo, of the panel
# side (frames/aqara-s1-plus/tools/install/glassworks-switch.sh) and of frames/aqara-s1-plus/tools/install/improv-send.py. A fake `adb` simulates
# the panel (PackageManager, device admin, root, dumpsys wifi, Improv); nothing talks to a real one.
# Runs on the host (macOS or Linux, bash + python3), in a few seconds:
#
#   frames/aqara-s1-plus/tools/install/test/install-tests.sh
#
# The Wi-Fi password in the fixtures is fake. The tests check that it reaches the new app and
# appears nowhere else: not in install.sh's output, not in any adb command line. The same for the
# made-up Home Assistant sign-in and API key the switch carries (#584), and frames/aqara-s1-plus/tools/install/handover.py.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../../../.." && pwd)"
FIX="$HERE/fixtures"
PASS=0; FAILS=0
ok() { PASS=$((PASS + 1)); echo "ok   $*"; }
no() { FAILS=$((FAILS + 1)); echo "FAIL $*"; }
check() { local d=$1; shift; if "$@"; then ok "$d"; else no "$d"; fi; }
has() { case "$1" in *"$2"*) return 0 ;; esac; echo "    want '$2' in:"; sed 's/^/      | /' <<<"$1" | tail -25; return 1; }
hasnt() { case "$1" in *"$2"*) echo "    did not want '$2' in:"; sed 's/^/      | /' <<<"$1" | tail -25; return 1 ;; esac; return 0; }
before() { # <log> <first> <second>: first occurrence of <first> comes before the first of <second>
  local a b
  a=$(grep -nF -- "$2" <<<"$1" | head -1 | cut -d: -f1); b=$(grep -nF -- "$3" <<<"$1" | head -1 | cut -d: -f1)
  [[ -n $a && -n $b && $a -lt $b ]] || { echo "    want '$2' (line ${a:-none}) before '$3' (line ${b:-none})"; return 1; }
}
PSK='fixture <not> "real" & fake'
PSK_SHA=$(printf '%s' "$PSK" | shasum -a 256 | cut -d' ' -f1)
# Made up, base64 as the app's hand-over socket gives them: 32 bytes of 'k', and a token. Never real ones.
KEY=a2tra2tra2tra2tra2tra2tra2tra2tra2tra2tra2s=
TOKEN=bWFkZS11cC1yZWZyZXNoLXRva2Vu

T=$(mktemp -d "${TMPDIR:-/tmp}/s1-install-test.XXXXXX")
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/pbin"
w() { cat > "$1"; chmod 755 "$1"; }

# ---- the panel's own commands, for glassworks-switch.sh (run on this computer against $T/panel) ----
w "$T/pbin/stat" <<'EOF'
#!/bin/sh
# stat -c %u <path>: the app's uid for its data dirs
[ "$1" = -c ] && [ "$2" = %u ] || exit 1
[ -e "$3" ] || exit 1
cat "$S1T/uid"
EOF
w "$T/pbin/pm" <<'EOF'
#!/bin/sh
[ "$1 $2 $3" = "list packages -U" ] && [ "$(cat "$S1T/installed")" != none ] && echo "package:sh.edm.s1kiosk uid:$(cat "$S1T/uid")"
exit 0
EOF
w "$T/pbin/chown" <<'EOF'
#!/bin/sh
echo "chown $*" >> "$S1T/root.log"
EOF
w "$T/pbin/restorecon" <<'EOF'
#!/bin/sh
echo "restorecon $*" >> "$S1T/root.log"
EOF
# iptables and ip6tables: the INPUT chain, one rule per line, in $S1T/fw.<name>. fw-fail=<name> makes that one fail.
w "$T/pbin/iptables" <<'EOF'
#!/bin/sh
n=$(basename "$0"); f="$S1T/fw.$n"; touch "$f"
[ "$(cat "$S1T/fw-fail" 2>/dev/null)" = "$n" ] && { echo "$n: can't initialize iptables table \`filter'" >&2; exit 3; }
[ "$1" = -w ] || { echo "TEST: $n without -w" >> "$S1T/violations"; }
[ "$1" = -w ] && shift
op=$1; shift
case "$op" in
  -C) grep -qxF -- "$*" "$f" ;;
  -I) { echo "$*"; cat "$f"; } > "$f.new" && mv "$f.new" "$f" ;;
  -D) grep -qxF -- "$*" "$f" || exit 1
      awk -v r="$*" '!done && $0 == r { done = 1; next } { print }' "$f" > "$f.new" && mv "$f.new" "$f" ;;
  *) exit 2 ;;
esac
EOF
cp "$T/pbin/iptables" "$T/pbin/ip6tables"
w "$T/pbin/log" <<'EOF'
#!/bin/sh
echo "log $*" >> "$S1T/root.log"
EOF

# A fresh app data dir, as installd makes it: lib -> the install's native libs, empty cache dirs.
fresh_data() { # <uid> <build>
  rm -rf "$T/panel/ce" "$T/panel/de"; mkdir -p "$T/panel/ce/cache" "$T/panel/ce/code_cache" "$T/panel/de"
  ln -s "/data/app/sh.edm.s1kiosk-$2/lib/arm" "$T/panel/ce/lib"
  echo "$1" > "$T/uid"; echo fresh > "$T/panel/ce/fresh"
}

# ---- fake adb: one simulated panel, state in $T ----
w "$T/bin/adb" <<'EOF'
#!/usr/bin/env bash
S=$S1T
st() { cat "$S/$1" 2>/dev/null; }
put() { printf '%s' "$2" > "$S/$1"; }
fresh_data() {
  rm -rf "$S/panel/ce" "$S/panel/de"; mkdir -p "$S/panel/ce/cache" "$S/panel/ce/code_cache" "$S/panel/de"
  ln -s "/data/app/sh.edm.s1kiosk-$2/lib/arm" "$S/panel/ce/lib"; echo "$1" > "$S/uid"; echo fresh > "$S/panel/ce/fresh"
}
echo "$*" >> "$S/adb.log"
# Between two adb commands, Android starts an enabled HOME app at once (before `pm install` even returns). One with
# empty data while the switch still holds the real data serves an API with no key: a fault unless port 6053 is
# blocked for IPv4 and IPv6 (#626).
guarded() { for n in iptables ip6tables; do grep -qxF "INPUT -p tcp --dport 6053 -j DROP" "$S/fw.$n" 2>/dev/null || return 1; done; }
[ -n "$(st installed)" ] && [ "$(st installed)" != none ] && [ "$(st disabled)" != 1 ] && [ -f "$S/panel/ce/fresh" ] &&
  [ -f "$S/panel/work/complete" ] && ! guarded && echo "before: $*" >> "$S/unkeyed"
# The run stops after the command in stop-after (Ctrl-C, a cable pulled): SIGTERM to the install.sh in main.pid.
stop_here() { [ -n "$(st stop-after)" ] && [ "$*" = "$(st stop-after)" ] && kill -TERM "$(st main.pid)"; return 0; }
case "$1" in
  get-state) echo device; exit 0 ;;
  push) cp "$2" "$S/panel/$(basename "$3")"; exit 0 ;;
  forward) [ "$2" = tcp:0 ] && echo 41234; exit 0 ;;
  install)
    # Android checks the version before the signing key
    [ "$(st downgrade)" = 1 ] && [ "$(st installed)" != none ] && { echo "adb: failed to install x.apk: Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"; exit 1; }
    case "$(st installed)" in
      debug) echo "adb: failed to install x.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package sh.edm.s1kiosk signatures do not match previously installed version; ignoring!]"; exit 1 ;;
    esac
    [ "$(st new-install)" = fail ] && { echo "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"; exit 1; }
    [ "$(st installed)" = none ] && fresh_data 10072 release
    put installed release; put disabled 0; echo "Performing Streamed Install"; echo Success; stop_here install; exit 0 ;;
  shell) shift; cat > /dev/null ;; # like adb: `adb shell` forwards (and so drains) stdin
  *) exit 0 ;;
esac
cmd="$*"
case "$cmd" in
  "getprop ro.product.model") echo "Aqara S1 Plus" ;;
  "getprop ro.build.version.sdk") echo 29 ;;
  "pm list packages sh.edm.s1kiosk")
    [ "$(st list)" = fail ] && { echo "cmd: Failure calling service package"; exit 1; }
    [ "$(st installed)" != none ] && echo "package:sh.edm.s1kiosk" ;;
  "dumpsys package sh.edm.s1kiosk")
    if [ "$(st installed)" = release ]; then printf '    versionName=0.4.1\n    pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]\n'
    else printf '    versionName=0.1.0\n    pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]\n'; fi ;;
  "timeout 15 su -c 'id -u' 2>/dev/null") [ "$(st root)" = 1 ] && echo 0 || exit 1 ;;
  "dumpsys wifi") cat "$(st wifi-dump)" ;;
  "ip -4 -o addr show wlan0") echo "3: wlan0    inet 192.0.2.50/24 brd 192.0.2.255 scope global wlan0" ;;
  "pm path sh.edm.s1kiosk") echo "package:/data/app/sh.edm.s1kiosk-Abc==/base.apk" ;;
  "cp /data/app/sh.edm.s1kiosk-Abc==/base.apk /data/local/tmp/glassworks-old.apk") : ;;
  *"--ez remove_admin true") put admin 0; [ "$(st down-after-admin)" = 1 ] && put socket-down 1 ;;
  "dumpsys device_policy") [ "$(st admin)" = 1 ] && echo "Admin sh.edm.s1kiosk/.AdminReceiver" || echo "no admins" ;;
  "dpm set-active-admin sh.edm.s1kiosk/.AdminReceiver") put admin 1 ;;
  "pm disable-user --user 0 sh.edm.s1kiosk")
    [ "$(st disable)" = fail ] && { echo "Error: java.lang.SecurityException"; exit 255; }
    [ "$(st installed)" = none ] && { echo "Error: java.lang.IllegalArgumentException: Unknown package: sh.edm.s1kiosk"; exit 255; }
    put disabled 1 ;;
  "pm enable sh.edm.s1kiosk") put disabled 0 ;;
  "pm uninstall sh.edm.s1kiosk")
    if [ "$(st admin)" = 1 ] || [ "$(st uninstall)" = fail ] || [ "$(st installed)" = none ]; then echo "Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]"; exit 1; fi
    put installed none; rm -rf "$S/panel/ce" "$S/panel/de" "$S"/app.*; echo Success ;;
  "pm install -r -g /data/local/tmp/glassworks-old.apk")
    [ "$(st old-install)" = fail ] && { echo "Failure [INSTALL_FAILED_INTERNAL_ERROR]"; exit 1; }
    fresh_data 10073 debug; rm -f "$S"/app.*; put installed debug; put disabled 0; echo Success ;;
  # the app's secret hand-over socket (#584): listens while an app that has one runs
  "grep -c ' @glassworks_api_key\$' /proc/net/unix")
    c=both; [ "$(st installed)" = debug ] && c=$(st old-carries)
    if [ "$(st installed)" != none ] && [ "$(st disabled)" != 1 ] && [ "${c:-both}" != none ] && [ "$(st socket-down)" != 1 ]; then echo 1; else echo 0; fi ;;
  "grep -c ' @s1kiosk_improv\$' /proc/net/unix") echo 1 ;;
  "cmd package set-home-activity com.aqara.inhome.launcher/.MainActivity") put home aqara ;;
  "cmd package resolve-activity"*)
    # chooser: Glassworks still a HOME app next to Aqara's launcher (seen on the S1, 2026-11-19)
    if [ "$(st chooser)" = 1 ]; then echo "android/com.android.internal.app.ResolverActivity"
    elif [ "$(st home)" = aqara ]; then echo "com.aqara.inhome.launcher/.MainActivity"
    else echo "sh.edm.s1kiosk/.MainActivity"; fi ;;
  "su -c 'sh /data/local/tmp/glassworks-switch.sh "*"'")
    [ "$(st root)" = 1 ] || { echo "Permission denied"; exit 1; }
    a=${cmd#su -c \'sh /data/local/tmp/glassworks-switch.sh }; a=${a%\'}
    case "${a%% *}" in backup|restore) [ "$(st disabled)" = 1 ] || { echo "TEST: $a while the app is enabled" >> "$S/violations"; } ;; esac
    echo "$a" >> "$S/root-cmds"
    [ "${a%% *}" = restore ] && [ "$(st restore)" = fail ] && { echo "glassworks-switch: could not restore the app's data"; exit 1; }
    # the app's sealed secrets go with its data: copied by backup, back by restore, sealed with a key that is gone
    [ "$a" = backup ] && for f in key signin; do [ -f "$S/app.$f" ] && cp "$S/app.$f" "$S/copy.$f"; done
    if [ "${a%% *}" = restore ]; then for f in key signin; do [ -f "$S/copy.$f" ] && cp "$S/copy.$f" "$S/app.$f"; done; put app.key.lost 1; put app.signin.lost 1; put app.signinhere 1; fi
    # shellcheck disable=SC2086
    PATH="$S/pbin:$PATH" S1_CE="$S/panel/ce" S1_DE="$S/panel/de" S1_WORK="$S/panel/work" \
      S1_WIFI_STORES="$S/WifiConfigStore.xml" sh "$S/panel/glassworks-switch.sh" $a
    exit $? ;;
  *) : ;;
esac
stop_here "shell $cmd"
exit 0
EOF
# The new app's Improv over adb: records what it was sent (the password only as a hash).
w "$T/fake-improv.py" <<'EOF'
import hashlib, os, sys
ssid, psk = sys.stdin.buffer.read().split(b"\0", 1)
open(os.path.join(os.environ["S1T"], "improv.got"), "w").write(ssid.decode() + "\n" + hashlib.sha256(psk).hexdigest() + "\n")
print("improv-send: the panel joined " + ssid.decode())
EOF
# The app's hand-over socket, as handover.py reaches it (#584). State: app.key, app.signin (base64), app.<f>.lost (sealed
# with the deleted key), app.signinhere (a Home Assistant address and client id to carry a token into), old-carries
# (both, key: an app of #574, none). Logs request verbs only to handover.log, never a secret.
w "$T/fake-handover.py" <<'EOF'
import os, sys
S = os.environ["S1T"]
def st(n, d=""):
    try: return open(os.path.join(S, n)).read().strip()
    except OSError: return d
def put(n, v): open(os.path.join(S, n), "w").write(v)
if sys.argv[1:] == ["--fingerprint"]:
    sys.stdin.read(); print("f00d"); sys.exit(0)
carries = st("old-carries", "both") if st("installed") == "debug" else "both"
for line in sys.stdin.read().splitlines():
    verb, _, arg = line.strip().partition(" ")
    open(os.path.join(S, "handover.log"), "a").write(verb + "\n")
    known = ["get", "set"] if carries == "key" else ["carries", "get", "set", "get-signin", "set-signin", "done"]
    if verb not in known: r = "refused unknown request"
    elif verb == "carries": r = "carries api_key signin"
    elif verb == "done": r = "ok"
    elif verb in ("get", "get-signin"):
        f = "app.key" if verb == "get" else "app.signin"; v = st(f); lost = st(f + ".lost") == "1"
        r = "none" if not v else "unreadable" if lost or st("app.unreadable") == "1" else ("key " if verb == "get" else "signin ") + v
    else:
        f = "app.key" if verb == "set" else "app.signin"; held = "" if st(f + ".lost") == "1" else st(f)
        passing = int(st("handover.passing", "0") or 0)
        if passing > 0: put("handover.passing", str(passing - 1)); r = "refused the key store isn't available right now"
        elif verb == "set-signin" and st("app.signinhere") != "1": r = "refused no sign-in here to carry it into"
        elif not held or held == arg: put(f, arg); put(f + ".lost", "0"); r = "ok"
        else: r = "refused a different value is set already"
    print(r)
EOF
export S1T="$T" PATH="$T/bin:$PATH" S1_IMPROV_SEND="$T/fake-improv.py" S1_HANDOVER="$T/fake-handover.py"
APK="$T/kiosk-release.apk"; echo apk > "$APK"

# A debug build with its data, as HOME + device admin, on Wi-Fi it saved.
panel() { # [wifi fixture]
  rm -rf "$T/panel" "$T"/{adb.log,root-cmds,root.log,violations,unkeyed,fw.iptables,fw.ip6tables,fw-fail,stop-after,main.pid,improv.got,installed,disabled,root,admin,home,new-install,uninstall,restore,disable,old-install,list,chooser,handover.log,old-carries,down-after-admin,socket-down,handover.passing,downgrade} "$T"/app.* "$T"/copy.*
  mkdir -p "$T/panel"
  cp "$FIX/WifiConfigStore.xml" "$T/WifiConfigStore.xml"
  fresh_data 10071 debug
  rm "$T/panel/ce/fresh"
  mkdir -p "$T/panel/ce/shared_prefs" "$T/panel/ce/files/mozilla"
  echo '<map><string name="ha_url">http://ha.local:8123</string></map>' > "$T/panel/ce/shared_prefs/kiosk.xml"
  echo profile > "$T/panel/ce/files/mozilla/prefs.js"; echo junk > "$T/panel/ce/cache/c"; echo de > "$T/panel/de/de.txt"
  printf debug > "$T/installed"; printf 0 > "$T/disabled"; printf 1 > "$T/root"; printf 1 > "$T/admin"
  printf '%s' "$KEY" > "$T/app.key"; printf '%s' "$TOKEN" > "$T/app.signin"; printf 1 > "$T/app.signinhere"
  printf '%s' "$FIX/${1:-dumpsys-wifi-kiosk.txt}" > "$T/wifi-dump"
}
# Online, but dumpsys doesn't say who saved the network.
unknown_wifi() { panel; printf 'Wi-Fi is enabled\n' > "$T/wifi-unknown.txt"; printf '%s' "$T/wifi-unknown.txt" > "$T/wifi-dump"; }
# The app took the sign-in and key back: the values, and readable again. The restore alone copies the same values,
# sealed with the key Android deleted, so the values alone don't show a hand-back (#608 review).
handed_back() {
  [[ "$(cat "$T/app.signin" 2>/dev/null) $(cat "$T/app.key" 2>/dev/null)" == "$TOKEN $KEY" ]] &&
    [[ "$(cat "$T/app.signin.lost" 2>/dev/null) $(cat "$T/app.key.lost" 2>/dev/null)" == "0 0" ]] ||
    { echo "    not handed back: $(ls "$T" | grep '^app\.' | tr '\n' ' ')"; return 1; }
}
inst() { "$REPO/frames/aqara-s1-plus/tools/install.sh" --apk "$APK" --no-models --no-debloat "$@" </dev/null 2>&1; }
# Port 6053 is open again: no rule left (#626).
port_open() { ! grep -q 6053 "$T/fw.iptables" "$T/fw.ip6tables" 2>/dev/null || { echo "    rules left: $(cat "$T"/fw.* 2>/dev/null | tr '\n' ';')"; return 1; }; }

echo "== glassworks-switch.sh (panel side)"
panel
SW="$REPO/frames/aqara-s1-plus/tools/install/glassworks-switch.sh"
cp "$SW" "$T/panel/glassworks-switch.sh"
out=$(S1_WIFI_STORES="$FIX/WifiConfigStore.xml" sh "$SW" wifi-list)
check "wifi-list: only kiosk-saved networks, no secrets" test "$out" = $'1 psk &quot;Home &amp; Garden&quot;\n2 open &quot;Cafe&quot;'
check "wifi-psk 1: that network's stored key" test "$(S1_WIFI_STORES="$FIX/WifiConfigStore.xml" sh "$SW" wifi-psk 1)" = '&quot;fixture &lt;not&gt; &quot;real&quot; &amp; fake&quot;'
check "wifi-psk of a missing network fails" bash -c '! S1_WIFI_STORES="$1" sh "$2" wifi-psk 3 2>/dev/null' _ "$FIX/WifiConfigStore.xml" "$SW"
check "wifi-psk refuses a non-number" bash -c '! sh "$1" wifi-psk "1;id" 2>/dev/null' _ "$SW"
env_sw() { PATH="$T/pbin:$PATH" S1_CE="$T/panel/ce" S1_DE="$T/panel/de" S1_WORK="$T/panel/work" sh "$SW" "$@"; }
out=$(env_sw backup)
check "backup: reports what it kept" has "$out" "kept 2 item(s)"
check "backup: complete marker, no lib/cache" bash -c '[ -f "$1/complete" ] && [ ! -e "$1/ce/lib" ] && [ ! -e "$1/ce/cache" ] && [ -f "$1/ce/files/mozilla/prefs.js" ] && [ -f "$1/de/de.txt" ]' _ "$T/panel/work"
fresh_data 10072 release; printf release > "$T/installed"
out=$(env_sw restore 2>&1)
check "restore: into the new uid" has "$out" "restored for uid 10072"
check "restore: data back, the fresh install's files gone" bash -c '[ -f "$1/shared_prefs/kiosk.xml" ] && [ -f "$1/files/mozilla/prefs.js" ] && [ ! -e "$1/fresh" ] && [ -f "$2/de.txt" ]' _ "$T/panel/ce" "$T/panel/de"
check "restore: lib still points at the new install" test "$(readlink "$T/panel/ce/lib")" = /data/app/sh.edm.s1kiosk-release/lib/arm
check "restore: no hand-over marker unless asked" test ! -e "$T/panel/ce/files/secrets-handover"
check "restore: owned by the new uid, labels restored" bash -c 'grep -q "chown -R 10072:10072 .*/ce/shared_prefs" "$1" && grep -q "restorecon -R .*/ce" "$1"' _ "$T/root.log"
out=$(env_sw restore handover 2>&1)
check "restore handover: restored, and the hand-over marked for the app (#608)" bash -c '[ -f "$1/files/secrets-handover" ] && [ ! -s "$1/files/secrets-handover" ] && grep -q "chown 10072:10072 .*/ce/files/secrets-handover" "$2"' _ "$T/panel/ce" "$T/root.log"
echo keep > "$T/system-file"
ln -sf "$T/system-file" "$T/panel/work/ce/files/secrets-handover" 2>/dev/null || { mkdir -p "$T/panel/work/ce/files"; ln -sf "$T/system-file" "$T/panel/work/ce/files/secrets-handover"; }
out=$(env_sw restore handover 2>&1)
check "restore handover: a copied marker that is a link is replaced, its target untouched (#608 review)" bash -c '[ "$(cat "$1")" = keep ] && [ -f "$2" ] && [ ! -L "$2" ] && ! grep -q "chown .*system-file" "$3"' _ "$T/system-file" "$T/panel/ce/files/secrets-handover" "$T/root.log"
rm -f "$T/panel/work/ce/files/secrets-handover"; mv "$T/panel/work/ce/files" "$T/panel/work/ce-files-real"; mkdir -p "$T/elsewhere"; ln -s "$T/elsewhere" "$T/panel/work/ce/files"
check "restore handover: files/ as a link is refused, nothing written through it" bash -c '! "$@" restore handover 2>/dev/null && [ ! -e "$0/secrets-handover" ]' "$T/elsewhere" env PATH="$T/pbin:$PATH" S1_CE="$T/panel/ce" S1_DE="$T/panel/de" S1_WORK="$T/panel/work" sh "$SW"
rm -f "$T/panel/work/ce/files"; mv "$T/panel/work/ce-files-real" "$T/panel/work/ce/files"
check "restore refuses another word" bash -c '! "$@" restore whatever 2>/dev/null' _ env PATH="$T/pbin:$PATH" S1_CE="$T/panel/ce" S1_DE="$T/panel/de" S1_WORK="$T/panel/work" sh "$SW"
env_sw discard
check "discard: the copy is gone" test ! -e "$T/panel/work"
check "restore without a copy fails" bash -c '! "$@" restore 2>/dev/null' _ env PATH="$T/pbin:$PATH" S1_CE="$T/panel/ce" S1_WORK="$T/panel/work" sh "$SW"
printf none > "$T/installed"
mkdir -p "$T/panel/work"; touch "$T/panel/work/complete"
check "restore refuses when PackageManager disagrees" bash -c '! "$@" restore 2>/dev/null' _ env PATH="$T/pbin:$PATH" S1_CE="$T/panel/ce" S1_WORK="$T/panel/work" sh "$SW"
rule="INPUT -p tcp --dport 6053 -j DROP"
env_sw guard >/dev/null; env_sw guard >/dev/null
check "guard: drops 6053 over IPv4 and IPv6, once however often it runs (#626)" bash -c '[ "$(cat "$1/fw.iptables")" = "$2" ] && [ "$(cat "$1/fw.ip6tables")" = "$2" ]' _ "$T" "$rule"
check "guard: says so in logcat" grep -q "log -t glassworks-switch API port 6053 blocked for the switch" "$T/root.log"
echo "$rule" >> "$T/fw.iptables"
out=$(env_sw unguard)
check "unguard: every copy gone" bash -c '[ "$1" = "API port 6053 open" ] && ! grep -q 6053 "$2/fw.iptables" "$2/fw.ip6tables"' _ "$out" "$T"
check "unguard: says so in logcat" grep -q "log -t glassworks-switch API port 6053 open again" "$T/root.log"
check "unguard with nothing to remove is fine" bash -c 'PATH="$1/pbin:$PATH" sh "$2" unguard >/dev/null' _ "$T" "$SW"
printf ip6tables > "$T/fw-fail"
check "guard: fails if IPv6 can't be blocked, and leaves no IPv4 rule" bash -c '! "$@" guard 2>/dev/null && ! grep -q 6053 "$0/fw.iptables"' "$T" env PATH="$T/pbin:$PATH" sh "$SW"
rm -f "$T/fw-fail"

echo "== improv-send.py against a fake Glassworks"
python3 - "$REPO/frames/aqara-s1-plus/tools/install/improv-send.py" <<'EOF' && ok "improv-send: sends ssid + password, exit 0; wrong password exit 3; never prints it" || no "improv-send"
import os, socket, subprocess, sys, threading
sys.path.insert(0, os.path.dirname(sys.argv[1]))
import importlib.util
spec = importlib.util.spec_from_file_location("improv", sys.argv[1]); im = importlib.util.module_from_spec(spec); spec.loader.exec_module(im)
def serve(reject):
    srv = socket.socket(); srv.bind(("127.0.0.1", 0)); srv.listen(8)
    got = []
    def loop():
        while True:
            try: c, _ = srv.accept()
            except OSError: return
            r = im.Reader()
            while True:
                d = c.recv(4096)
                if not d: break
                for kind, data in r.push(d):
                    if data[0] == im.REQUEST_STATE: c.sendall(im.frame(im.STATE, bytes([2])))
                    if data[0] == im.WIFI_SETTINGS:
                        n = data[2]; ssid = data[3:3 + n].decode(); m = data[3 + n]; psk = data[4 + n:4 + n + m].decode()
                        got.append((ssid, psk))
                        if reject: c.sendall(im.frame(im.ERROR, bytes([3])))
                        else:
                            s = im.strings(""); c.sendall(im.frame(im.RPC_RESULT, bytes([im.WIFI_SETTINGS, len(s)]) + s))
            c.close()
    threading.Thread(target=loop, daemon=True).start()
    return srv, got
psk = 'fixture <not> "real" & fake'
for reject, want in ((False, 0), (True, 3)):
    srv, got = serve(reject)
    p = subprocess.run([sys.executable, sys.argv[1], str(srv.getsockname()[1])], input=b"Home & Garden\0" + psk.encode(), capture_output=True, timeout=60)
    srv.close()
    assert p.returncode == want, (p.returncode, p.stderr)
    assert got and got[-1] == ("Home & Garden", psk), got
    assert b"fixture" not in p.stdout + p.stderr, p.stdout + p.stderr
EOF

echo "== handover.py against a fake hand-over socket"
python3 - "$REPO/frames/aqara-s1-plus/tools/install/handover.py" <<'EOF' && ok "handover.py: one reply line per request, secrets only on stdin/stdout; fails without an answer; fingerprints" || no "handover.py"
import base64, hashlib, socket, subprocess, sys, threading
def serve(answer):
    srv = socket.socket(); srv.bind(("127.0.0.1", 0)); srv.listen(4)
    def loop():
        while True:
            try: c, _ = srv.accept()
            except OSError: return
            f = c.makefile("rwb")
            for line in f:
                if answer: f.write(answer(line.decode().strip()).encode() + b"\n"); f.flush()
                else: break
            c.close()
    threading.Thread(target=loop, daemon=True).start()
    return srv
token = base64.b64encode(b"made-up-token").decode()
srv = serve(lambda l: "signin " + token if l == "get-signin" else "ok" if l == "set-signin " + token else "refused")
p = subprocess.run([sys.executable, sys.argv[1], str(srv.getsockname()[1])], input=f"get-signin\nset-signin {token}\n".encode(), capture_output=True, timeout=30)
assert p.returncode == 0 and p.stdout.decode() == f"signin {token}\nok\n", p
assert token.encode() not in p.stderr, p.stderr
mute = serve(None)
p = subprocess.run([sys.executable, sys.argv[1], str(mute.getsockname()[1])], input=f"set-signin {token}\n".encode(), capture_output=True, timeout=30)
assert p.returncode == 1 and token.encode() not in p.stdout + p.stderr, p
p = subprocess.run([sys.executable, sys.argv[1], "--fingerprint"], input=token.encode(), capture_output=True, timeout=30)
assert p.stdout.decode().strip() == hashlib.sha256(b"made-up-token").hexdigest()[:4], p
EOF

echo "== install.sh: debug build -> this build, with root"
panel
out=$(inst --switch); rc=$?
log=$(cat "$T/adb.log")
check "exit 0" test $rc -eq 0
check "explains it" has "$out" "development build (0.1.0)"
check "says the settings are kept" has "$out" "so the app's settings are kept"
check "...and the sign-in and key, which the running app hands over (#584)" has "$out" "and so are its Home Assistant"$'\n'"  sign-in and encryption key: the installer reads them from the running app"
check "...but not the camera password or the Music Assistant pairing" has "$out" "The camera's stream password and the Music Assistant pairing are not carried"
check "Done: settings, sign-in and key kept" has "$out" "Switched builds: the app's settings, Home Assistant sign-in and encryption key were kept."
check "...so it doesn't say to log in" hasnt "$out" "Log in to Home Assistant on the panel once"
check "the new app has the sign-in and key" handed_back
check "read from the old app, handed to the new one, then done" test "$(tr '\n' ' ' < "$T/handover.log")" = "carries get-signin get set set-signin done "
check "the restore marked the hand-over (#608)" grep -qx "restore handover" "$T/root-cmds"
check "the sign-in and key are in no output" bash -c '! grep -qF -e "$2" -e "$3" <<<"$1"' _ "$out" "$TOKEN" "$KEY"
check "...and in no adb command" bash -c '! grep -qF -e "$2" -e "$3" <<<"$1"' _ "$log" "$TOKEN" "$KEY"
check "...only their fingerprints" has "$out" "Home Assistant sign-in: read from the running app (fingerprint f00d)"
check "app data restored" has "$out" "app data restored"
check "the Wi-Fi went to the new app" test "$(cat "$T/improv.got" 2>/dev/null)" = "Home & Garden"$'\n'"$PSK_SHA"
check "the password is in no output" hasnt "$out" "fixture"
check "the password is in no adb command" hasnt "$log" "fixture"
check "backup and restore with the app disabled" test ! -e "$T/violations"
check "the new app, keyless before the restore, was never reachable (#626)" test ! -s "$T/unkeyed"
check "port 6053 blocked before anything changed" before "$log" "glassworks-switch.sh guard" "remove_admin true"
check "...open again after the hand-over is done" before "$(sed -n '/pm uninstall/,$p' <<<"$log")" "forward tcp:0 localabstract:glassworks_api_key" "glassworks-switch.sh unguard"
check "...before the Wi-Fi is sent" before "$(sed -n '/pm uninstall/,$p' <<<"$log")" "glassworks-switch.sh unguard" "forward tcp:0 localabstract:s1kiosk_improv"
check "...and no rule left" port_open
check "...said so" has "$out" "ESPHome API port 6053 open again"
check "data carried into the new install" bash -c '[ -f "$1/shared_prefs/kiosk.xml" ] && [ ! -e "$1/fresh" ]' _ "$T/panel/ce"
check "ends enabled, release, admin" test "$(cat "$T/installed") $(cat "$T/disabled") $(cat "$T/admin")" = "release 0 1"
check "copy discarded" test ! -e "$T/panel/work"
check "Wi-Fi read before the uninstall" before "$log" "wifi-psk 1" "shell pm uninstall"
check "admin dropped before disabling" before "$log" "remove_admin true" "pm disable-user --user 0 sh.edm.s1kiosk"
check "backup before the uninstall" before "$log" "glassworks-switch.sh backup" "shell pm uninstall"
check "restore after the new install" before "$(sed -n '/pm uninstall/,$p' <<<"$log")" "install -r -g $APK" "glassworks-switch.sh restore"
check "Wi-Fi sent after the kiosk is started" before "$(sed -n '/pm uninstall/,$p' <<<"$log")" "am start -n sh.edm.s1kiosk/.MainActivity" "forward tcp:0 localabstract:s1kiosk_improv"
check "secrets read before the app is stopped" before "$log" "forward tcp:0 localabstract:glassworks_api_key" "pm disable-user --user 0 sh.edm.s1kiosk"
check "...and after the admin is dropped, as late as can be" before "$(sed -n '/remove_admin true/,$p' <<<"$log")" "remove_admin true" "forward tcp:0 localabstract:glassworks_api_key"
check "...handed back after the kiosk is started, before the Wi-Fi" before "$(sed -n '/am start -n sh.edm.s1kiosk\/.MainActivity$/,$p' <<<"$log")" "forward tcp:0 localabstract:glassworks_api_key" "forward tcp:0 localabstract:s1kiosk_improv"
check "temporary files removed" bash -c 'grep -qx "shell rm -f /data/local/tmp/glassworks-old.apk" <<<"$1" && grep -qx "shell rm -f /data/local/tmp/glassworks-switch.sh" <<<"$1"' _ "$log"
check "no 'forgot its Wi-Fi' note" hasnt "$out" "forgot its Wi-Fi"

echo "== install.sh: without root"
panel; printf 0 > "$T/root"
out=$(inst --switch); rc=$?
log=$(cat "$T/adb.log")
check "exit 0" test $rc -eq 0
check "says it starts fresh" has "$out" "the app starts fresh"
check "no root commands" hasnt "$log" "su -c 'sh"
check "no port block without root" hasnt "$out" "6053 blocked"
check "no root script pushed" hasnt "$log" "push "
check "no password to send (no terminal to ask)" test ! -e "$T/improv.got"
check "says how to send the Wi-Fi again" has "$out" "forgot its Wi-Fi \"Home & Garden\""
check "settings reset, said so" has "$out" "settings were reset"
check "no secrets read without root (#584)" test ! -e "$T/handover.log"
check "ends enabled, release" test "$(cat "$T/installed") $(cat "$T/disabled")" = "release 0"

echo "== install.sh: from an app of #574, which hands over only the key (#584)"
panel; printf key > "$T/old-carries"
out=$(inst --switch); rc=$?
check "exit 0" test $rc -eq 0
check "the plan: the key is kept, the sign-in isn't" has "$out" "and so is its encryption key: the"
check "...sign in again" has "$out" "Its Home Assistant sign-in"$'\n'"  is not: this build of the app can't hand it over"
check "only the key read and handed over" test "$(tr '\n' ' ' < "$T/handover.log")" = "carries get set done "
check "Done: sign in again, the key kept" has "$out" "settings were kept, but not its Home Assistant sign-in"
check "...says to sign in, not to confirm the key" bash -c 'grep -q "Sign in again\.$" <<<"$1" && ! grep -q "confirm the key" <<<"$1"' _ "$out"

echo "== install.sh: from an app with no hand-over at all (before #574)"
panel; printf none > "$T/old-carries"
out=$(inst --switch); rc=$?
check "exit 0" test $rc -eq 0
check "the plan says both are lost, as #588 did" has "$out" "Its Home Assistant sign-in and"$'\n'"  encryption key are not: Android deletes the app's Keystore keys"
check "Done: sign in again, may confirm the key" has "$out" "settings were kept, but not its Home Assistant sign-in and encryption key"
check "...and says what to do" has "$out" "Sign in again. Home Assistant may ask to confirm the key."
check "no request to the app" bash -c '! grep -qv carries "$1" 2>/dev/null' _ "$T/handover.log"
check "...and no hand-over marked" grep -qx "restore" "$T/root-cmds"

echo "== install.sh: an app that can't read its sign-in now (#584)"
panel; printf 1 > "$T/app.unreadable"
out=$(inst --switch); rc=$?
check "goes on" test $rc -eq 0
check "says it could not read them" has "$out" "Home Assistant sign-in: could not read it (unreadable); sign in again afterwards"
check "Done: sign in again" has "$out" "but not its Home Assistant sign-in and encryption key"

echo "== install.sh: the old app stops answering before the read (#608 review)"
panel; printf 1 > "$T/down-after-admin"
out=$(S1_HANDOVER_READ_WAIT=2 inst --switch); rc=$?
check "goes on" test $rc -eq 0
check "lost, never taken for none" has "$out" "Home Assistant sign-in: could not read it (no answer); sign in again afterwards"
check "Done: sign in again" has "$out" "but not its Home Assistant sign-in and encryption key"

echo "== install.sh: the new app's key store is down for a moment at the hand-back (#608 review)"
panel; printf 2 > "$T/handover.passing"
out=$(inst --switch); rc=$?
check "tries again, and both are handed over" handed_back
check "...said so" has "$out" "Switched builds: the app's settings, Home Assistant sign-in and encryption key were kept."
check "...after the refusals" test "$(grep -c '^set' "$T/handover.log")" -ge 4

echo "== install.sh: a step after the switch stops the run (#608 review)"
panel
out=$(inst --switch --timezone 'not a zone!'); rc=$?
check "stops" test $rc -ne 0
check "the new app still gets the sign-in and key" handed_back
check "...says so" has "$out" "Stopping early: handing the sign-in and encryption key to the new app first"
check "...then opens port 6053 again" bash -c 'port_open() { ! grep -q 6053 "$1"/fw.* 2>/dev/null; }; port_open "$2" && grep -q "glassworks-switch.sh unguard" "$2/adb.log"' _ "$T" "$T"
check "...after starting it" before "$(sed -n '/pm uninstall/,$p' "$T/adb.log")" "shell am start -n sh.edm.s1kiosk/.MainActivity" "forward tcp:0 localabstract:glassworks_api_key"

echo "== install.sh: the data won't go into the new build (#584)"
panel; printf fail > "$T/restore"
out=$(inst --switch); rc=$?
check "hands nothing to the fresh app" test "$(tr '\n' ' ' < "$T/handover.log")" = "carries get-signin get "
check "port 6053 open again for the fresh app" port_open
check "says the settings were reset" has "$out" "settings were reset"

echo "== install.sh: an older build than the panel's (#608 panel run)"
panel; printf 1 > "$T/downgrade"
out=$(inst --switch); rc=$?
check "stops" test $rc -ne 0
check "says it's older, and how to build it" has "$out" "is an older version than the Glassworks on the panel (0.1.0"
check "...with the panel's version" has "$out" "-Ps1Version=0.1.0"
check "...nothing changed" bash -c '[ "$(cat "$1/installed") $(cat "$1/disabled")" = "debug 0" ] && ! grep -q "pm uninstall" "$1/adb.log"' _ "$T"

echo "== install.sh: a network Glassworks didn't save"
panel dumpsys-wifi-other.txt
out=$(inst --switch)
check "says it stays" has "$out" "wasn't saved by Glassworks and stays"
check "reads no Wi-Fi details" hasnt "$(cat "$T/root-cmds")" "wifi-"
check "nothing to send" test ! -e "$T/improv.got"

echo "== install.sh: no terminal and no --switch"
panel
out=$(inst); rc=$?
check "stops" test $rc -ne 0
check "says nothing changed" has "$out" "Nothing was changed"
check "nothing changed" test "$(cat "$T/installed") $(cat "$T/disabled") $(cat "$T/admin")" = "debug 0 1"
check "no uninstall" hasnt "$(cat "$T/adb.log")" "pm uninstall"

echo "== install.sh: the new build won't install"
panel; printf fail > "$T/new-install"
out=$(inst --switch); rc=$?
check "fails" test $rc -ne 0
check "says the old build is back with its data" has "$out" "previous build is back with its data."
check "...and its sign-in and key, handed back to it (#584)" handed_back
check "...after starting it" before "$(sed -n '/pm install -r -g \/data\/local\/tmp\/glassworks-old.apk/,$p' "$T/adb.log")" "shell am start -n sh.edm.s1kiosk/.MainActivity" "forward tcp:0 localabstract:glassworks_api_key"
check "old build back, enabled, admin, data restored" bash -c '[ "$(cat "$1/installed") $(cat "$1/disabled") $(cat "$1/admin")" = "debug 0 1" ] && [ -f "$1/panel/ce/shared_prefs/kiosk.xml" ]' _ "$T"
check "copy discarded" test ! -e "$T/panel/work"
check "the old build, keyless before its restore, was never reachable (#626)" test ! -s "$T/unkeyed"
check "port 6053 open again after the hand-back" bash -c 'port_open() { ! grep -q 6053 "$1"/fw.* 2>/dev/null; }; port_open "$1" && [ "$(grep -n "unguard" "$1/adb.log" | cut -d: -f1)" -gt "$(grep -n "localabstract:glassworks_api_key" "$1/adb.log" | tail -1 | cut -d: -f1)" ]' _ "$T"

echo "== install.sh: the new build won't install, and the old one's data won't go back in"
panel; printf fail > "$T/new-install"; printf fail > "$T/restore"
out=$(inst --switch); rc=$?
check "fails" test $rc -ne 0
check "says the old build is back without its data" has "$out" "previous build is back, but its data is not (glassworks-switch: could not restore the app's data)"
check "...not that the data is back" hasnt "$out" "with its data"
check "says where the copy is" has "$out" "Its data is kept on the panel in /data/s1kiosk/switch (root only)"
check "copy kept" test -f "$T/panel/work/complete"
check "no discard" hasnt "$(cat "$T/root-cmds")" "discard"
check "old build back, enabled, admin" test "$(cat "$T/installed") $(cat "$T/disabled") $(cat "$T/admin")" = "debug 0 1"
check "port 6053 open again" port_open

echo "== install.sh: neither build installs"
panel; printf fail > "$T/new-install"; printf fail > "$T/old-install"
out=$(inst --switch); rc=$?
check "fails" test $rc -ne 0
check "says neither installed, and where the copy is" has "$out" "Its data is kept on the panel in /data/s1kiosk/switch (root only)"
check "copy kept" test -f "$T/panel/work/complete"
check "port 6053 open again" port_open

echo "== install.sh: Android won't remove the old build"
panel; printf fail > "$T/uninstall"
out=$(inst --switch); rc=$?
check "fails, unchanged" bash -c '[ "$1" -ne 0 ] && [ "$(cat "$2/installed") $(cat "$2/disabled") $(cat "$2/admin")" = "debug 0 1" ]' _ $rc "$T"
check "says so" has "$out" "It is unchanged"
check "port 6053 open again" port_open

echo "== install.sh: port 6053 can't be blocked (#626)"
panel; printf ip6tables > "$T/fw-fail"
out=$(inst --switch); rc=$?
check "stops" test $rc -ne 0
check "says so, nothing changed" has "$out" "could not block the ESPHome API port for the switch (glassworks-switch: could not block port 6053 with ip6tables). Nothing was changed."
check "nothing changed" bash -c '[ "$(cat "$1/installed") $(cat "$1/disabled") $(cat "$1/admin")" = "debug 0 1" ] && ! grep -q "pm uninstall\|remove_admin" "$1/adb.log"' _ "$T"
check "no IPv4 rule left" port_open

echo "== install.sh: the run stops after the new build is in, before its data (#626)"
panel; printf install > "$T/stop-after"
"$REPO/frames/aqara-s1-plus/tools/install.sh" --apk "$APK" --no-models --no-debloat --switch </dev/null > "$T/out" 2>&1 &
printf '%s' $! > "$T/main.pid"; wait $!; rc=$?; out=$(cat "$T/out")
check "stops" test $rc -ne 0
check "no restore ran" hasnt "$(cat "$T/root-cmds")" "restore"
check "port 6053 stays blocked: the build has no data or key (#628 review)" bash -c 'grep -qx "INPUT -p tcp --dport 6053 -j DROP" "$1/fw.iptables" && grep -qx "INPUT -p tcp --dport 6053 -j DROP" "$1/fw.ip6tables"' _ "$T"
check "...said so, with the way out" has "$out" "ESPHome API port 6053 stays blocked: Glassworks has no data or key yet. Restart the panel to open it."
check "...and the helper is gone" bash -c 'tail -1 "$1/adb.log" | grep -qx "shell rm -f /data/local/tmp/glassworks-switch.sh"' _ "$T"
check "Glassworks on, the copy kept" bash -c '[ "$(cat "$1/installed") $(cat "$1/disabled")" = "release 0" ] && [ -f "$1/panel/work/complete" ]' _ "$T"

echo "== install.sh: the run stops while the new build is copied, before the uninstall (#626)"
panel; printf 'shell pm uninstall sh.edm.s1kiosk' > "$T/stop-after"
"$REPO/frames/aqara-s1-plus/tools/install.sh" --apk "$APK" --no-models --no-debloat --switch </dev/null > "$T/out" 2>&1 &
printf '%s' $! > "$T/main.pid"; wait $!; rc=$?; out=$(cat "$T/out")
check "stops" test $rc -ne 0
check "no build with empty data: port 6053 open again" port_open
check "...said so" has "$out" "ESPHome API port 6053 open again"

echo "== install.sh --dry-run on a debug build"
panel
out=$(inst --dry-run); rc=$?
log=$(cat "$T/adb.log")
check "exit 0" test $rc -eq 0
check "prints the plan" has "$out" "(as root) /data/local/tmp/glassworks-switch.sh backup"
check "...with the port block and its end (#626)" bash -c 'grep -q "glassworks-switch.sh guard " <<<"$1" && grep -q "glassworks-switch.sh unguard " <<<"$1"' _ "$out"
check "prints the Wi-Fi re-send" has "$out" "Wi-Fi \"Home & Garden\" to the new app over Improv"
check "the plan: settings, sign-in and key kept (#584)" has "$out" "so the app's settings are kept, and so are its Home Assistant"
check "prints the secrets' read and hand-back" has "$out" "the app's hand-over socket: Home Assistant sign-in and encryption key, into memory only"
check "...asks the app only what it carries, no secret" test "$(tr '\n' ' ' < "$T/handover.log")" = "carries "
check "changes nothing" test "$(cat "$T/installed") $(cat "$T/disabled") $(cat "$T/admin")" = "debug 0 1"
for c in "install " "push " "pm uninstall" "pm disable-user" "glassworks-switch.sh" "forward tcp:0 localabstract:s1kiosk_improv"; do
  check "dry run: no '$c'" hasnt "$log" "$c"
done

echo "== install.sh --dry-run: a network the installer can't place"
unknown_wifi; printf 0 > "$T/root"
out=$(inst --dry-run)
check "doesn't say Glassworks saved it" hasnt "$out" "which Glassworks saved"
check "says the panel may lose its Wi-Fi" has "$out" "can't tell who saved its Wi-Fi network, so the panel may lose its Wi-Fi"
unknown_wifi
out=$(inst --dry-run)
check "with root: may forget, not would" has "$out" "Android may forget its Wi-Fi network with the old app"

echo "== install.sh --dry-run on a release build (the keys may well match)"
panel; printf release > "$T/installed"
out=$(inst --dry-run); rc=$?
check "exit 0" test $rc -eq 0
check "the switch is only a possibility" has "$out" "If the keys differ (Glassworks on the panel signed with another key than"
check "...never stated as a fact" hasnt "$out" "is signed with a different key"
check "...nor a development build" hasnt "$out" "development build"

echo "== install.sh --dry-run: de-bloat disables the launcher in the same run"
panel; printf release > "$T/installed"
out=$("$REPO/frames/aqara-s1-plus/tools/install.sh" --apk "$APK" --no-models --dry-run </dev/null 2>&1)
check "one debloat call with --launcher" has "$out" "debloat.sh disable --launcher"
check "...no separate disable-launcher" hasnt "$out" "disable-launcher"

echo "== install.sh: a normal update is unchanged"
panel; printf release > "$T/installed"
out=$(inst); rc=$?
check "exit 0" test $rc -eq 0
check "no switch" hasnt "$out" "Switch Glassworks"
check "no uninstall" hasnt "$(cat "$T/adb.log")" "pm uninstall"

echo "== install.sh --undo keeps the panel on its Wi-Fi"
panel
out=$("$REPO/frames/aqara-s1-plus/tools/install.sh" --undo </dev/null 2>&1); rc=$?
log=$(cat "$T/adb.log")
check "exit 0" test $rc -eq 0
check "disables instead of uninstalling" bash -c 'grep -q "shell pm disable-user --user 0 sh.edm.s1kiosk" <<<"$1" && ! grep -q "pm uninstall sh.edm.s1kiosk" <<<"$1"' _ "$log"
check "says why" has "$out" "it saved the panel's Wi-Fi \"Home & Garden\""
panel dumpsys-wifi-other.txt
out=$("$REPO/frames/aqara-s1-plus/tools/install.sh" --undo </dev/null 2>&1)
check "someone else's network: uninstalls" has "$(cat "$T/adb.log")" "shell pm uninstall sh.edm.s1kiosk"
check "and says it is removed" has "$out" "Glassworks is removed"
panel dumpsys-wifi-other.txt; printf fail > "$T/uninstall"
out=$("$REPO/frames/aqara-s1-plus/tools/install.sh" --undo </dev/null 2>&1); rc=$?
check "uninstall refused: exit 0, says it is disabled instead" bash -c '[ "$1" -eq 0 ] && grep -q "would not remove Glassworks, so it is disabled instead" <<<"$2"' _ $rc "$out"
check "...not that it is removed" hasnt "$out" "Glassworks is removed"
panel dumpsys-wifi-other.txt; printf fail > "$T/uninstall"; printf fail > "$T/disable"
out=$("$REPO/frames/aqara-s1-plus/tools/install.sh" --undo </dev/null 2>&1); rc=$?
check "uninstall and disable refused: fails" test $rc -ne 0
check "...says Aqara's apps are back but Glassworks is not disabled" has "$out" "Aqara's apps are back, but Android would not disable Glassworks"
check "...not that it is removed" hasnt "$out" "Glassworks is removed"
panel; printf fail > "$T/disable"
out=$("$REPO/frames/aqara-s1-plus/tools/install.sh" --undo </dev/null 2>&1); rc=$?
check "Glassworks' Wi-Fi, disable refused: fails" test $rc -ne 0
check "...says so" has "$out" "Android would not disable Glassworks"
check "...not that it is disabled" hasnt "$out" "Glassworks is disabled"
check "...still restarts Aqara's radio services" has "$(cat "$T/adb.log")" "setprop ctl.start ha_master"
check "...still checks HOME" has "$(cat "$T/adb.log")" "cmd package resolve-activity"
for fix in dumpsys-wifi-other.txt dumpsys-wifi-kiosk.txt; do
  panel "$fix"; printf fail > "$T/uninstall"; printf fail > "$T/disable"; printf 1 > "$T/chooser"
  out=$("$REPO/frames/aqara-s1-plus/tools/install.sh" --undo </dev/null 2>&1); rc=$?
  check "disable refused, HOME on the chooser ($fix): fails" test $rc -ne 0
  check "...says Glassworks is still on" has "$out" "Aqara's apps are back, but Android would not disable Glassworks"
  check "...not the HOME error" hasnt "$out" "the home screen is still"
  check "...doesn't open the chooser" hasnt "$(cat "$T/adb.log")" "am start -a android.intent.action.MAIN"
done
panel; printf 1 > "$T/chooser"
out=$("$REPO/frames/aqara-s1-plus/tools/install.sh" --undo </dev/null 2>&1); rc=$?
check "HOME on the chooser with Glassworks disabled: still the HOME error" bash -c '[ "$1" -ne 0 ] && grep -q "the home screen is still android/com.android.internal.app.ResolverActivity" <<<"$2"' _ $rc "$out"
for fix in dumpsys-wifi-other.txt dumpsys-wifi-kiosk.txt; do
  panel "$fix"; printf none > "$T/installed"
  out=$("$REPO/frames/aqara-s1-plus/tools/install.sh" --undo </dev/null 2>&1); rc=$?
  check "retry after an undo that removed Glassworks ($fix): exit 0, says removed" bash -c '[ "$1" -eq 0 ] && grep -q "Glassworks is removed" <<<"$2"' _ $rc "$out"
done
for fix in dumpsys-wifi-other.txt dumpsys-wifi-kiosk.txt; do
  panel "$fix"; printf none > "$T/installed"; printf fail > "$T/list"
  out=$("$REPO/frames/aqara-s1-plus/tools/install.sh" --undo </dev/null 2>&1); rc=$?
  check "...a listing that fails is not taken as removed ($fix)" bash -c '[ "$1" -ne 0 ] && ! grep -q "Glassworks is removed" <<<"$2"' _ $rc "$out"
done
unknown_wifi
out=$("$REPO/frames/aqara-s1-plus/tools/install.sh" --undo </dev/null 2>&1)
check "can't tell who saved the network: disables" has "$(cat "$T/adb.log")" "shell pm disable-user --user 0 sh.edm.s1kiosk"
check "...and doesn't say Glassworks saved it" hasnt "$out" "it saved the panel's Wi-Fi"
check "...says it can't tell" has "$out" "can't tell who saved the panel's Wi-Fi"
panel
out=$("$REPO/frames/aqara-s1-plus/tools/install.sh" --undo --dry-run </dev/null 2>&1)
check "dry run: shows the disable" has "$out" "+ adb shell pm disable-user --user 0 sh.edm.s1kiosk"
check "dry run: changes nothing" test "$(cat "$T/installed") $(cat "$T/disabled")" = "debug 0"

echo "== fel-install.sh step 9: waits on the device that came back, with others on the same adb server"
# The two helpers, out of the script, against a fake adb whose device list is $T/adbdev.
sed -n '/^adb_serials() {/p; /^pick_adb_serial() {/,/^}/p' "$REPO/frames/aqara-s1-plus/tools/install/fel-install.sh" > "$T/pick.sh"
printf '#!/usr/bin/env bash\necho "List of devices attached"; cat "%s" 2>/dev/null\n' "$T/adbdev" > "$T/adb-list"; chmod 755 "$T/adb-list"
pick() { printf "$2" > "$T/adbdev"; ADB="$T/adb-list" bash -c '. "$1"; pick_adb_serial "$2"' _ "$T/pick.sh" "$1"; }
check "the Mirror was there during fastboot, the S1 came back: the S1" test "$(pick MIRROR1 'MIRROR1\tdevice\nS1PANEL\tdevice\n')" = S1PANEL
check "...also while it is still unauthorized" test "$(pick MIRROR1 'MIRROR1\tdevice\nS1PANEL\tunauthorized\n')" = S1PANEL
check "...not yet back: nothing (keep waiting), never the Mirror" test -z "$(pick MIRROR1 'MIRROR1\tdevice\n')"
check "alone on adb: the one device" test "$(pick '' 'S1PANEL\tdevice\n')" = S1PANEL
check "two new devices: nothing, it can't tell" test -z "$(pick MIRROR1 'MIRROR1\tdevice\nA\tdevice\nB\tdevice\n')"
check "nothing during fastboot, two now: nothing" test -z "$(pick '' 'A\tdevice\nB\tdevice\n')"

echo "== fel-install.sh --undo: refuses an incomplete backup, restores a complete one"
# Stub tools: nothing reaches FEL or a real fastboot. The fake fastboot is our U-Boot, already up,
# with partitions in $T/emmc: flash writes one, and fb-read-partition.sh's oem run + get_staged read
# it back.
mkdir -p "$T/fbin" "$T/ub" "$T/undo" "$T/emmc"
for t in sunxi-fel adb docker; do w "$T/fbin/$t" <<STUB
#!/bin/sh
echo "$t \$*" >> "$T/tools.log"; exit 1
STUB
done
w "$T/fbin/fastboot" <<STUB
#!/bin/sh
echo "fastboot \$*" >> "$T/tools.log"
case "\$1 \${2:-}" in
  "devices "*) echo "FAKE fastboot" ;;
  "getvar product") echo "product: sunxi" >&2 ;;
  "getvar partition-size:boot") echo "partition-size:boot: 0x0000000002000000" >&2 ;;
  "getvar partition-size:super") echo "partition-size:super: 0x0000000060000000" >&2 ;;
  "flash "*) cp "\$3" "$T/emmc/\$2" ;;
  "oem run:part start mmc "*) set -- \$2; echo "\$5" > "$T/emmc/.cur" ;;
  "oem run:"*) ;;
  "get_staged "*) cp "$T/emmc/\$(cat "$T/emmc/.cur")" "\$2" ;;
  "reboot "*) ;;
  *) exit 1 ;;
esac
STUB
: > "$T/ub/u-boot-sunxi-with-spl.bin"
printf 'not a boot image' > "$T/undo/boot.img"; printf 'vbmeta' > "$T/undo/vbmeta.img"
(cd "$T/undo" && shasum -a 256 boot.img vbmeta.img > SHA256SUMS)
fel_undo() { : > "$T/tools.log"; PATH="$T/fbin:$PATH" UBOOT_BUILD="$T/ub" "$REPO/frames/aqara-s1-plus/tools/install/fel-install.sh" --undo "$T/undo" </dev/null 2>&1; }
out=$(fel_undo); rc=$?
check "no bootloader.img: refused" test "$rc" -ne 0
check "and says why" has "$out" "has no bootloader.img: the backup is incomplete. Nothing was changed."
check "and the way forward" has "$out" "flash them by hand from our U-Boot"
check "before any tool runs" test ! -s "$T/tools.log"
printf 'splash' > "$T/undo/bootloader.img"
(cd "$T/undo" && shasum -a 256 boot.img vbmeta.img bootloader.img > SHA256SUMS)
out=$(fel_undo); rc=$?
check "complete backup: exit 0" test "$rc" -eq 0
check "flashes boot, vbmeta, splash, in that order" test "$(grep '^fastboot flash ' "$T/tools.log")" = "fastboot flash boot $T/undo/boot.img
fastboot flash vbmeta $T/undo/vbmeta.img
fastboot flash bootloader $T/undo/bootloader.img"
check "each one read back the same" bash -c 'for p in boot vbmeta bootloader; do cmp -s "$1/undo/$p.img" "$1/emmc/$p" || exit 1; done' _ "$T"
check "says all three are back" has "$out" "stock boot, vbmeta and splash are back"

echo "== fel-install.sh --undo: refuses a backup whose splash is ours (#106)"
# A FAT16 boot-resource like the panel's bootloader partition, with <bmp> as BOOTLOGO.BMP in clusters
# laid out backwards (so the FAT chain is followed); "other" changes one pixel: a splash not ours.
boot_resource() { # <bmp> <out.img> [other]
  python3 - "$@" <<'PY'
import struct, sys
bmp = bytearray(open(sys.argv[1], 'rb').read())
if len(sys.argv) > 3: bmp[1054] ^= 0xff
bps, spc, reserved, nfats, nroot = 512, 4, 4, 2, 512
cs = bps * spc; n = -(-len(bmp) // cs); total = n + 1; spf = -(-(total + 2) * 2 // bps)
root = (reserved + nfats * spf) * bps; data = root + nroot * 32; fat = reserved * bps
img = bytearray(data + total * cs)
struct.pack_into('<HBHBH', img, 11, bps, spc, reserved, nfats, nroot); img[21] = 0xf8
struct.pack_into('<H', img, 22, spf); img[54:62] = b'FAT16   '
def entry(i, name, first, size):
    e = root + i * 32; img[e:e + 11] = name; img[e + 11] = 0x20; struct.pack_into('<HI', img, e + 26, first, size)
entry(0, b'BAT     BMP', 2, 16); struct.pack_into('<H', img, fat + 4, 0xffff)
cl = [total + 1 - i for i in range(n)]
for i, c in enumerate(cl):
    struct.pack_into('<H', img, fat + 2 * c, cl[i + 1] if i + 1 < n else 0xffff)
    img[data + (c - 2) * cs: data + (c - 2) * cs + len(bmp[i * cs:(i + 1) * cs])] = bmp[i * cs:(i + 1) * cs]
entry(1, b'BOOTLOGOBMP', cl[0], len(bmp))
open(sys.argv[2], 'wb').write(img)
PY
}
LOGO="$REPO/branding/glass-tile/bootlogo.bmp"
boot_resource "$LOGO" "$T/undo/bootloader.img"
(cd "$T/undo" && shasum -a 256 boot.img vbmeta.img bootloader.img > SHA256SUMS)
rm -f "$T/emmc/bootloader"
out=$(fel_undo); rc=$?
check "our splash: refused" test "$rc" -ne 0
LOGO_NAME=$(python3 -c 'import hashlib, json, sys; h = hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest(); print(next(b["name"] for b in json.load(open(sys.argv[2]))["bootlogos"] if b["sha256"] == h))' "$LOGO" "$REPO/branding/bootlogos.json")
check "and says why" has "$out" "bootloader.img holds our splash ($LOGO_NAME), not Aqara's: this backup was taken after an install. Nothing was changed."
check "and the way forward" has "$out" "put back only boot and vbmeta by hand"
check "before any tool runs" test ! -s "$T/tools.log"
check "nothing written" test ! -e "$T/emmc/bootloader"
boot_resource "$LOGO" "$T/undo/bootloader.img" other
(cd "$T/undo" && shasum -a 256 boot.img vbmeta.img bootloader.img > SHA256SUMS)
out=$(fel_undo); rc=$?
check "a splash that isn't ours: exit 0" test "$rc" -eq 0
check "and it is restored" cmp -s "$T/undo/bootloader.img" "$T/emmc/bootloader"
# The splash check itself fails (a broken list, a crash): undo stops rather than guess.
REAL_PY=$(command -v python3)
w "$T/fbin/python3" <<STUB
#!/bin/sh
case " \$* " in *" --ours "*) echo "Traceback: the list is broken" >&2; exit 1 ;; esac
exec "$REAL_PY" "\$@"
STUB
rm -f "$T/emmc/bootloader"
out=$(fel_undo); rc=$?
rm -f "$T/fbin/python3"
check "splash check fails: refused" test "$rc" -ne 0
check "and says why" has "$out" "could not check the splash in $T/undo/bootloader.img. Nothing was changed."
check "before any tool runs" test ! -s "$T/tools.log"
check "nothing written" test ! -e "$T/emmc/bootloader"

echo "== frames/aqara-s1-plus/docs/install.md#undo: the by-hand check passes boot and vbmeta, and stops on either"
CHK=$(awk '/install-tests.sh runs this block/ {f = 1; next} f && /^```sh$/ {g = 1; next} g && /^```$/ {exit} g' "$REPO/frames/aqara-s1-plus/docs/install.md")
check "the doc has the check" test -n "$CHK"
bh() { (cd "$1" && sh -c "$CHK" 2>&1); }
mkdir -p "$T/bh"
printf 'boot' > "$T/bh/boot.img"; printf 'vbmeta' > "$T/bh/vbmeta.img"; printf 'splash' > "$T/bh/bootloader.img"
(cd "$T/bh" && shasum -a 256 boot.img vbmeta.img bootloader.img > SHA256SUMS) && rm "$T/bh/bootloader.img"
check "bootloader.img missing: goes on" has "$(bh "$T/bh")" "boot and vbmeta OK: go on"
printf 'damaged' > "$T/bh/vbmeta.img"
check "vbmeta damaged: stops" hasnt "$(bh "$T/bh")" "go on"
printf 'vbmeta' > "$T/bh/vbmeta.img"; grep -v ' vbmeta\.img$' "$T/bh/SHA256SUMS" > "$T/bh/s" && mv "$T/bh/s" "$T/bh/SHA256SUMS"
check "vbmeta entry missing: stops" hasnt "$(bh "$T/bh")" "go on"

echo
echo "$PASS passed, $FAILS failed"
[ "$FAILS" -eq 0 ]
