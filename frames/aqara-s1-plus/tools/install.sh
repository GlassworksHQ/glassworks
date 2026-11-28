#!/usr/bin/env bash
# One-shot setup of an Aqara Panel Hub S1 Plus as a Home Assistant kiosk, over ADB (no root).
#
# Prerequisite: ADB reachable (USB or Wi-Fi). Stock firmware exposes no ADB; see
# frames/aqara-s1-plus/docs/install.md#command-line for how to enable it. Everything below is reversible: see --undo.
#
#   export ANDROID_SERIAL=<panel serial>        # if more than one device is attached
#   frames/aqara-s1-plus/tools/install.sh --url http://homeassistant.local:8123/my-dashboard [options]
#
# Options:
#   --apk PATH          kiosk APK (default: frames/aqara-s1-plus/android/build/outputs/apk/debug/kiosk-debug.apk)
#   --url URL           dashboard URL (default: keep the app's current/default URL)
#   --timezone TZ       e.g. America/Los_Angeles (default: leave unchanged)
#   --locale TAG        e.g. en-US (default: leave unchanged; applies after reboot)
#   --keyboard APK      install this IME APK, make it default and disable the stock Baidu IME
#   --no-models         don't download/push the wake word models (the panel fetches them itself)
#   --no-debloat        keep the Aqara apps enabled (the kiosk still becomes HOME)
#   --keep-dnd          keep Aqara's 22:00-07:00 Do-Not-Disturb screen-off
#   --switch            if Glassworks on the panel has another signing key (a debug build) and Android
#                       refuses the APK: switch without asking (default: explain and ask). See
#                       frames/aqara-s1-plus/docs/install.md#switching-a-development-build-to-a-release
#   --undo              revert: re-enable Aqara apps + launcher, restore DND and the IME, remove
#                       Glassworks (or disable it, if it saved the panel's Wi-Fi), start Aqara's radio
#                       services again, check HOME is Aqara's
#   --dry-run           print the commands instead of running them (only reads the panel's state)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
PKG=sh.edm.s1kiosk
ACT="$PKG/.MainActivity"
APK="$ROOT/frames/aqara-s1-plus/android/build/outputs/apk/debug/kiosk-debug.apk"
URL="" TZ_="" LOCALE="" KEYBOARD="" MODELS=1 DEBLOAT=1 DND_OFF=1 UNDO=0 DRY=0 SWITCH_OK=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --url) URL="$2"; shift 2 ;;
    --timezone) TZ_="$2"; shift 2 ;;
    --locale) LOCALE="$2"; shift 2 ;;
    --keyboard) KEYBOARD="$2"; shift 2 ;;
    --no-models) MODELS=0; shift ;;
    --no-debloat) DEBLOAT=0; shift ;;
    --keep-dnd) DND_OFF=0; shift ;;
    --switch) SWITCH_OK=1; shift ;;
    --undo) UNDO=1; shift ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

step() { printf '\n== %s\n' "$*"; }
run() { if [[ $DRY -eq 1 ]]; then echo "  + $*"; else "$@"; fi; }
# Like run, but never fails the script (for idempotent steps such as re-granting).
try() { if [[ $DRY -eq 1 ]]; then echo "  + $* || true"; else "$@" || true; fi; }
sh_() { run adb shell "$@"; }

# ---- Wi-Fi ownership and the signing-key switch (frames/aqara-s1-plus/docs/install.md; frames/aqara-s1-plus/installer/src/switch.ts) ----
# Android 10 forgets every Wi-Fi network an app saved when that app is uninstalled, and Glassworks
# saves the panel's Wi-Fi itself (Improv). The Wi-Fi password is only ever held in a shell variable:
# never echoed, never in a command line (it reaches improv-send.py on stdin), never in a file.
SW_SH=/data/local/tmp/glassworks-switch.sh
OLD_APK=/data/local/tmp/glassworks-old.apk
SW_DATA=/data/s1kiosk/switch # where glassworks-switch.sh keeps the app's data between backup and restore
IMPROV_SEND=${S1_IMPROV_SEND:-$HERE/install/improv-send.py}
WIFI_OWNER=none WIFI_SSID="" HAS_ROOT=0 DEBUG_BUILD=0 INSTALLED_VER="" SWITCHED=0 DATA_KEPT=0 WIFI_LOST=0
WIFI_PSK="" HAVE_PSK=0 APP_AFTER_UNDO=removed
# The switch has Glassworks disabled (SW_HELD), or port 6053 blocked (SW_GUARD, #626). Whatever stops the run, the app
# is enabled and the port opened again (carry_on_exit), unless the build installed now has empty data (SW_FRESH): it
# serves an API anyone could provision, so then the port stays blocked until the panel restarts.
SW_HELD=0 SW_GUARD=0 SW_FRESH=0
# Sealed secrets (#584): Android deletes the app's Keystore key with the old app, so the copied data can't open its
# Home Assistant sign-in or ESPHome API key. With root they are read from the running app over its adb-only hand-over
# socket into these variables (base64, never printed, never in a command line: they reach handover.py on stdin), and
# handed to the new app once it runs. CARRY_*: the running app can hand it over. *_STATE: carry (read, to hand over),
# kept, lost, or none (there was none).
HANDOVER=${S1_HANDOVER:-$HERE/install/handover.py}
READ_WAIT=${S1_HANDOVER_READ_WAIT:-15} # seconds the read waits for the old app's socket (it may be restarting)
# Refusals that may pass within the hand-back's wait (the key store down for a moment, a failed write). The others are final.
PASSING_RE="^refused (the key store isn't available right now|the .+ can't be stored right now)$"
CARRY_KEY=0 CARRY_SIGNIN=0 SEC_KEY="" SEC_SIGNIN="" KEY_STATE=lost SIGNIN_STATE=lost

# `dumpsys wifi` on stdin -> "<kiosk|other|unknown|none><TAB><ssid>": the current network id from
# "mWifiInfo ... Net ID: n", then that network's "ID: n SSID: "x" ..." entry and its " cname=<pkg>".
wifi_owner() {
  awk -v pkg="$PKG" '
    !info && /mWifiInfo/ { info = 1; id = "?"; if (match($0, /Net ID: -?[0-9]+/)) id = substr($0, RSTART + 8, RLENGTH - 8) }
    /^[ \t]*[*-]?[ \t]*ID: -?[0-9]+ SSID: .* PROVIDER-NAME:/ {
      if (state == 1) state = 2
      if (state == 0 && info) {
        h = $0; sub(/^[ \t]*[*-]?[ \t]*ID: /, "", h); bid = h; sub(/ .*/, "", bid)
        if (bid == id) {
          state = 1; s = h; sub(/^-?[0-9]+ SSID: /, "", s); sub(/ PROVIDER-NAME:.*/, "", s)
          if (s ~ /^".*"$/) s = substr(s, 2, length(s) - 2)
          ssid = s
        }
      }
      next
    }
    state == 1 && /Configured networks End/ { state = 2 }
    state == 1 && / cname=/ { c = $0; sub(/.* cname=/, "", c); sub(/ .*/, "", c); owner = (c == pkg) ? "kiosk" : "other"; state = 2 }
    END {
      if (!info || id == "?") { print "unknown\t"; exit }
      if (id + 0 < 0) { print "none\t"; exit }
      print (owner == "" ? "unknown" : owner) "\t" ssid
    }'
}
panel_wifi() {
  local r
  r=$(adb shell dumpsys wifi | tr -d '\r' | wifi_owner) || r=unknown$'\t'
  WIFI_OWNER=${r%%$'\t'*}; WIFI_SSID=${r#*$'\t'}
  if [[ $WIFI_OWNER == unknown ]] && ! adb shell ip -4 -o addr show wlan0 2>/dev/null | grep -q 'inet '; then WIFI_OWNER=none; fi
  return 0
}
# Glassworks is gone, by a package listing that worked (a failed listing says nothing).
absent() { local l; l=$(adb shell pm list packages "$PKG") || return 1; ! tr -d '\r' <<<"$l" | grep -qx "package:$PKG"; }
wifi_at_risk() { [[ $WIFI_OWNER == kiosk || $WIFI_OWNER == unknown ]]; }
net_name() { if [[ -n $WIFI_SSID ]]; then printf '"%s"' "$WIFI_SSID"; else printf 'its Wi-Fi network'; fi; }
is_key_mismatch() { grep -qiE 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match' <<<"$1"; }
# Android checks the version before the signing key: an older build is refused before any switch could start.
is_downgrade() { grep -q 'INSTALL_FAILED_VERSION_DOWNGRADE' <<<"$1"; }
has_root() { [[ "$(adb shell "timeout 15 su -c 'id -u' 2>/dev/null" | tr -d '\r' | tail -1)" == 0 ]]; }
su_() { adb shell "su -c 'sh $SW_SH $*'"; }
# Request lines on stdin -> reply lines on stdout, over the app's hand-over socket (adb forward; adb only). Fails when
# the app doesn't listen or answer. The adb calls read /dev/null: `adb shell` forwards stdin, and would eat the request
# meant for handover.py (#608 review).
handover_up() { [[ "$(adb shell "grep -c ' @glassworks_api_key\$' /proc/net/unix" </dev/null | tr -d '\r')" -gt 0 ]] 2>/dev/null; }
handover_req() { # [seconds to wait for each reply, 10 by default]
  local port rc=0
  handover_up && command -v python3 >/dev/null || return 1
  port=$(adb forward tcp:0 localabstract:glassworks_api_key </dev/null | tr -d '\r') && [[ $port =~ ^[0-9]+$ ]] || return 1
  python3 "$HANDOVER" "$port" "${1:-10}" || rc=$?
  adb forward --remove "tcp:$port" </dev/null >/dev/null 2>&1 || true
  return $rc
}
fingerprint() { printf '%s' "$1" | python3 "$HANDOVER" --fingerprint 2>/dev/null || echo "?"; }
# One request (on stdin: it may hold a secret) until it gets an answer that isn't a passing refusal, or until the
# deadline <$1, in $SECONDS> passes. Each try waits at most the time left. Prints the last reply, empty for none.
ask_until() {
  local req r="" left
  req=$(cat)
  while :; do
    left=$(( $1 - SECONDS ))
    (( left >= 1 )) || break
    r=$(printf '%s\n' "$req" | handover_req "$(( left < 10 ? left : 10 ))" 2>/dev/null) || r=""
    if [[ -n $r && ! $r =~ $PASSING_RE ]]; then break; fi
    (( $1 - SECONDS > 1 )) || break
    sleep 1.5
  done
  req=""
  printf '%s' "$r"
}
# XML text as Android writes it (&quot; &apos; &lt; &gt; &amp;); fails on anything else (&#...;).
# The value goes to sed on stdin, not as an argument.
xml_unescape() {
  [[ $1 == *'&#'* ]] && return 1
  printf '%s\n' "$1" | sed -e 's/&quot;/"/g' -e "s/&apos;/'/g" -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&amp;/\&/g'
}
unquote() { local v=$1; if [[ ${#v} -ge 2 && $v == \"*\" ]]; then v=${v:1:${#v}-2}; fi; printf '%s' "$v"; }

# Read-only look at the installed app, root and Wi-Fi (also what --dry-run reports).
probe_switch() {
  local d
  d=$(adb shell dumpsys package "$PKG" | tr -d '\r')
  INSTALLED_VER=$(sed -n 's/^ *versionName=\([^ ]*\).*/\1/p' <<<"$d" | head -1)
  if grep -qE 'pkgFlags=\[[^]]*DEBUGGABLE' <<<"$d"; then DEBUG_BUILD=1; else DEBUG_BUILD=0; fi
  if has_root; then HAS_ROOT=1; else HAS_ROOT=0; fi
  # What the running app can hand over (#584): an app from #574 refuses `carries` but hands over the key.
  CARRY_KEY=0 CARRY_SIGNIN=0
  if [[ $HAS_ROOT -eq 1 ]]; then
    case "$(printf 'carries\n' | handover_req 2>/dev/null)" in
      "carries "*api_key*signin*) CARRY_KEY=1 CARRY_SIGNIN=1 ;;
      "refused unknown request") CARRY_KEY=1 ;;
    esac
  fi
  panel_wifi
}
# What the plan says about the sealed secrets on a panel with root (frames/aqara-s1-plus/installer/src/switch.ts says the same).
explain_secrets() {
  if [[ $CARRY_KEY -eq 1 && $CARRY_SIGNIN -eq 1 ]]; then
    echo "  adb has root on this panel, so the app's settings are kept, and so are its Home Assistant"
    echo "  sign-in and encryption key: the installer reads them from the running app and hands them to"
    echo "  the new one. The camera's stream password and the Music Assistant pairing are not carried: set"
    echo "  and pair them again if you use them."
  elif [[ $CARRY_KEY -eq 1 ]]; then
    echo "  adb has root on this panel, so the app's settings are kept, and so is its encryption key: the"
    echo "  installer reads it from the running app and hands it to the new one. Its Home Assistant sign-in"
    echo "  is not: this build of the app can't hand it over, and Android deletes the app's Keystore keys,"
    echo "  which seal it, when it removes the old app. Sign in again afterwards. The camera's stream"
    echo "  password and the Music Assistant pairing are not carried either: set and pair them again if you"
    echo "  use them."
  else
    echo "  adb has root on this panel, so the app's settings are kept. Its Home Assistant sign-in and"
    echo "  encryption key are not: Android deletes the app's Keystore keys when it removes the old app,"
    echo "  and those secrets are sealed with them. Sign in again; Home Assistant may ask to confirm the"
    echo "  panel's encryption key."
  fi
}
# Reads the sign-in and the API key from the running app into memory. One it holds but can't hand over is lost to
# the switch: the run goes on and says so, as for the Wi-Fi.
read_secrets() {
  local r
  SIGNIN_STATE=lost KEY_STATE=lost
  if [[ $CARRY_SIGNIN -eq 1 ]]; then
    r=$(printf 'get-signin\n' | ask_until $(( SECONDS + READ_WAIT )))
    if [[ $r =~ ^signin\ ([A-Za-z0-9+/]+={0,2})$ ]]; then
      SEC_SIGNIN=${BASH_REMATCH[1]} SIGNIN_STATE=carry
      echo "  Home Assistant sign-in: read from the running app (fingerprint $(fingerprint "$SEC_SIGNIN")), kept in memory for the new app"
    elif [[ $r == none ]]; then SIGNIN_STATE=none; echo "  Home Assistant sign-in: none to carry"
    else r=${r%% *}; echo "  Home Assistant sign-in: could not read it (${r:-no answer}); sign in again afterwards"; fi
  else echo "  Home Assistant sign-in: this build of the app can't hand it over; sign in again afterwards"; fi
  if [[ $CARRY_KEY -eq 1 ]]; then
    r=$(printf 'get\n' | ask_until $(( SECONDS + READ_WAIT )))
    if [[ $r =~ ^key\ ([A-Za-z0-9+/]{43}=)$ ]]; then
      SEC_KEY=${BASH_REMATCH[1]} KEY_STATE=carry
      echo "  Encryption key: read from the running app (fingerprint $(fingerprint "$SEC_KEY")), kept in memory for the new app"
    elif [[ $r == none ]]; then KEY_STATE=none; echo "  Encryption key: none to carry"
    else r=${r%% *}; echo "  Encryption key: could not read it (${r:-no answer}); Home Assistant may ask to confirm it"; fi
  else echo "  Encryption key: this build of the app can't hand it over; Home Assistant may ask to confirm it"; fi
  r=""; [[ x =~ x ]] # clears BASH_REMATCH, which held a secret (read-only in bash 3.2)
}
forget_secrets() { SEC_KEY="" SEC_SIGNIN=""; }
# Hands what read_secrets read to the app installed now (it must be started), then forgets it. Tries each for up to
# 90 s while the socket isn't up, doesn't answer, or refuses for a reason that may pass; any other refusal is final.
hand_back() {
  local r
  if [[ $KEY_STATE == carry ]]; then
    r=$(printf 'set %s\n' "$SEC_KEY" | ask_until $(( SECONDS + 90 )))
    if [[ $r == ok ]]; then KEY_STATE=kept; echo "  Encryption key: handed to the app"
    else KEY_STATE=lost; echo "  Encryption key: the app did not take it (${r:-no answer}); Home Assistant may ask to confirm it"; fi
  fi
  if [[ $SIGNIN_STATE == carry ]]; then
    r=$(printf 'set-signin %s\n' "$SEC_SIGNIN" | ask_until $(( SECONDS + 90 )))
    if [[ $r == ok ]]; then SIGNIN_STATE=kept; echo "  Home Assistant sign-in: handed to the app"
    else SIGNIN_STATE=lost; echo "  Home Assistant sign-in: the app did not take it (${r:-no answer}); sign in again"; fi
  fi
  forget_secrets
  # the app kept what its old key sealed locked for this hand-over (the restore's marker): end that now
  r=$(printf 'done\n' | handover_req 2>/dev/null) || r=""
  if [[ $r != ok ]]; then echo "  The app did not hear the hand-over is done; it stops waiting for it within 15 minutes"; fi
}
# The sealed secrets the switch lost, as words for a message ("Home Assistant sign-in and encryption key"), or empty.
lost_secrets() {
  local l=()
  [[ $SIGNIN_STATE == lost || $SIGNIN_STATE == carry ]] && l+=("Home Assistant sign-in")
  [[ $KEY_STATE == lost || $KEY_STATE == carry ]] && l+=("encryption key")
  if [[ ${#l[@]} -eq 2 ]]; then echo "${l[0]} and ${l[1]}"; elif [[ ${#l[@]} -eq 1 ]]; then echo "${l[0]}"; fi
}
explain_switch() { # [if]: --dry-run, before Android said anything (the keys may well match)
  if [[ $DEBUG_BUILD -eq 1 ]]; then echo "  Glassworks on the panel is a development build${INSTALLED_VER:+ ($INSTALLED_VER)}."
  elif [[ ${1:-} == if ]]; then echo "  If the keys differ (Glassworks on the panel signed with another key than $(basename "$APK")),"
  else echo "  Glassworks on the panel is signed with a different key than $(basename "$APK")."; fi
  echo "  Android can't update it in place: it has to be removed and installed again."
  if [[ $HAS_ROOT -eq 1 ]]; then
    explain_secrets
    if [[ $WIFI_OWNER == kiosk ]]; then echo "  Android would forget $(net_name) with the old app; it is sent to the new app afterwards."
    elif wifi_at_risk; then echo "  Android may forget $(net_name) with the old app; it is sent to the new app afterwards."; fi
  else
    echo "  adb has no root on this panel, so the app starts fresh: its settings, Home Assistant sign-in"
    echo "  and pairing key are reset (Home Assistant may ask to set the panel up again)."
    if [[ $WIFI_OWNER == kiosk ]]; then echo "  Android also forgets $(net_name), which Glassworks saved: type its password when asked, or send it again afterwards."
    elif wifi_at_risk; then echo "  The installer can't tell who saved $(net_name), so the panel may lose its Wi-Fi: type its password when asked, or send it again afterwards."; fi
  fi
  if [[ $WIFI_OWNER == other ]]; then echo "  The panel's Wi-Fi $(net_name) wasn't saved by Glassworks and stays."; fi
  echo "  If installing the new build fails, the old one is put back."
}

# Uninstall the installed Glassworks and install $APK, carrying its data over when adb has root.
# The usual steps (permissions, admin, HOME) follow in the main script.
switch_key() {
  local path out list n kind raw i k v ans owner=0 rout lost
  step "Switch Glassworks to this build"
  probe_switch
  explain_switch
  if [[ $SWITCH_OK -eq 0 ]]; then
    if [[ ! -t 0 ]]; then echo "Not switching without asking (no terminal): re-run with --switch. Nothing was changed." >&2; exit 1; fi
    read -r -p "  Switch now? Type yes: " ans
    [[ $ans == yes ]] || { echo "Nothing was changed."; exit 1; }
  fi
  if [[ $HAS_ROOT -eq 0 ]] && wifi_at_risk && [[ -n $WIFI_SSID && -t 0 ]]; then
    read -r -s -p "  Password for \"$WIFI_SSID\" (Enter to skip; sent to the new app, not stored): " WIFI_PSK; echo
    if [[ -n $WIFI_PSK ]]; then HAVE_PSK=1; fi
  fi
  # 1. the installed APK, to fall back on
  path=$(adb shell pm path "$PKG" | tr -d '\r' | sed -n 's/^package://p' | head -1)
  if [[ -z $path ]] || ! adb shell cp "$path" "$OLD_APK"; then
    echo "ERROR: could not keep a copy of the installed Glassworks to fall back on. Nothing was changed." >&2; exit 1
  fi
  if [[ $HAS_ROOT -eq 1 ]]; then
    if ! adb push "$HERE/install/glassworks-switch.sh" "$SW_SH" >/dev/null; then
      adb shell rm -f "$OLD_APK" || true
      echo "ERROR: could not copy the switch helper to the panel. Nothing was changed." >&2; exit 1
    fi
    # 1b. the API port closed until the new build has its data and key. Android starts each build it installs as HOME
    #     at once, before `pm install` returns, and with empty data it serves an API with no key (#626).
    if ! out=$(su_ guard 2>&1); then
      adb shell rm -f "$OLD_APK" "$SW_SH" || true
      echo "ERROR: could not block the ESPHome API port for the switch ($(tr -d '\r' <<<"$out" | tail -1)). Nothing was changed." >&2; exit 1
    fi
    SW_GUARD=1
    echo "  ESPHome API port 6053 blocked until the switch is done"
    # 2. the Wi-Fi details of the current network (or the only kiosk-saved one), into memory
    if wifi_at_risk; then
      list=$(su_ wifi-list | tr -d '\r') || list=""
      n="" kind=""
      while read -r i k v; do
        [[ -n $i ]] || continue
        raw=$(xml_unescape "$v") || continue
        if [[ $(unquote "$raw") == "$WIFI_SSID" || $(grep -c . <<<"$list") -eq 1 ]]; then n=$i kind=$k WIFI_SSID=$(unquote "$raw"); break; fi
      done <<<"$list"
      if [[ $kind == open ]]; then WIFI_PSK="" HAVE_PSK=1
      elif [[ $kind == psk ]] && raw=$(su_ "wifi-psk $n" | tr -d '\r') && raw=$(xml_unescape "$raw"); then
        WIFI_PSK=$(unquote "$raw"); HAVE_PSK=1
      fi
      raw="" list=""
      if [[ $HAVE_PSK -eq 1 ]]; then echo "  Wi-Fi $(net_name): read as root, kept in memory for the new app"
      else echo "  Wi-Fi: could not read it as root; send it again afterwards (see the end)"; fi
    fi
  fi
  # 3. Android won't uninstall an active device admin (a device owner is released first). The app
  #    has to be running for these, so they come before it is stopped.
  if adb shell dumpsys device_policy | tr -d '\r' | grep -A3 'Device Owner' | grep -q "$PKG"; then
    owner=1; adb shell am start -n "$PKG/.improv.ImprovActivity" --ez release_device_owner true >/dev/null || true; sleep 2
  fi
  adb shell am start -n "$ACT" --ez remove_admin true >/dev/null || true
  for _ in $(seq 1 30); do adb shell dumpsys device_policy | grep -q "$PKG" || break; sleep 0.5; done
  # 3b. the sign-in and the API key, from the running app into memory (#584), as late as can be: right before it is
  #     stopped, so a sign-in finished meanwhile is the one read, and the one the backup copies
  if [[ $HAS_ROOT -eq 1 ]]; then read_secrets; fi
  # 4. stop it for good: as the HOME app a force-stopped kiosk is started again at once, and its data
  #    must not change while it is copied. Disabling keeps its Wi-Fi networks (uninstalling doesn't).
  adb shell pm disable-user --user 0 "$PKG" >/dev/null || true
  SW_HELD=1
  if [[ $HAS_ROOT -eq 1 ]]; then
    if ! out=$(su_ backup 2>&1); then
      su_ discard >/dev/null 2>&1 || true; switch_undo; adb shell rm -f "$OLD_APK" || true; switch_done
      WIFI_PSK=""; forget_secrets
      echo "ERROR: could not keep Glassworks' data: $(tr -d '\r' <<<"$out" | tail -1). Nothing was changed." >&2; exit 1
    fi
    echo "  app data kept: $(tr -d '\r' <<<"$out" | tail -1)"
  fi
  # 5. remove the old build
  out=$(adb shell pm uninstall "$PKG" | tr -d '\r') || true
  if ! grep -q Success <<<"$out"; then
    switch_undo
    if [[ $HAS_ROOT -eq 1 ]]; then su_ discard >/dev/null 2>&1 || true; fi
    adb shell rm -f "$OLD_APK" || true; switch_done
    WIFI_PSK=""; forget_secrets
    echo "ERROR: Android would not remove the installed Glassworks ($out). It is unchanged." >&2; exit 1
  fi
  echo "  removed the old build"
  # 6. install the new one; on failure put the old one back (with its data, given root)
  SW_FRESH=1 # from before the install: Android may have it running before `adb install` returns
  out=$(adb install -r -g "$APK" 2>&1) || true
  if ! grep -q Success <<<"$out"; then
    sed 's/^/  /' <<<"$out"
    # The data copy is the only one left until a restore worked: keep it otherwise, and say where.
    if adb shell pm install -r -g "$OLD_APK" | grep -q Success; then
      if [[ $HAS_ROOT -eq 0 ]]; then
        switch_undo
        echo "ERROR: the new Glassworks would not install. The previous build is back." >&2
      elif rout=$(restore_into 2>&1); then
        SW_FRESH=0; switch_undo; su_ discard >/dev/null 2>&1 || true
        # The old build lost its Keystore key too: hand its sign-in and key back, as to a new one (#584).
        lost=""
        if [[ $KEY_STATE == carry || $SIGNIN_STATE == carry ]]; then adb shell am start -n "$ACT" >/dev/null || true; hand_back; fi
        lost=$(lost_secrets)
        echo "ERROR: the new Glassworks would not install. The previous build is back with its data${lost:+, but not its $lost}$([[ $SIGNIN_STATE == lost ]] && echo ': sign in again')." >&2
      else
        switch_undo
        echo "ERROR: the new Glassworks would not install. The previous build is back, but its data is not ($(tr -d '\r' <<<"$rout" | tail -1))." >&2
        echo "  Its data is kept on the panel in $SW_DATA (root only)." >&2
      fi
    else
      SW_FRESH=0
      echo "ERROR: the new Glassworks would not install, and neither would the previous one. Run $0 again." >&2
      if [[ $HAS_ROOT -eq 1 ]]; then echo "  Its data is kept on the panel in $SW_DATA (root only)." >&2; fi
    fi
    adb shell rm -f "$OLD_APK" || true; switch_done
    WIFI_PSK=""; forget_secrets; exit 1
  fi
  echo "  installed the new build"
  # 7. the data back in, owned by the new uid, labels restored
  if [[ $HAS_ROOT -eq 1 ]]; then
    if out=$(restore_into 2>&1); then DATA_KEPT=1 SW_FRESH=0; su_ discard >/dev/null 2>&1 || true; echo "  app data restored"
    else
      forget_secrets; KEY_STATE=lost SIGNIN_STATE=lost # a fresh app has no sign-in to take them
      echo "  WARNING: could not restore the app's data ($(tr -d '\r' <<<"$out" | tail -1)); it starts fresh. The copy stays in $SW_DATA (root only)."
    fi
  fi
  SW_HELD=0
  if [[ $owner -eq 1 ]] && ! adb shell dpm set-device-owner "$PKG/.AdminReceiver" >/dev/null 2>&1; then
    echo "  NOTE: Glassworks was device owner and could not be made device owner again; it works without."
  fi
  adb shell rm -f "$OLD_APK" || true # the helper stays for switch_done, after the hand-over
  SWITCHED=1
  if wifi_at_risk; then WIFI_LOST=1; fi
}
# Puts the old build back as it was: enabled, device admin (and device owner if it was).
switch_undo() {
  adb shell pm enable "$PKG" >/dev/null 2>&1 || true
  SW_HELD=0
  adb shell dpm set-active-admin "$PKG/.AdminReceiver" >/dev/null 2>&1 || true
  if [[ ${owner:-0} -eq 1 ]]; then adb shell dpm set-device-owner "$PKG/.AdminReceiver" >/dev/null 2>&1 || true; fi
}
# The end of a switch with root, once the new (or old) build has its data and key: port 6053 open again, the helper
# gone. A rule left behind goes with the next reboot.
switch_done() {
  if [[ $SW_GUARD -eq 1 ]]; then
    SW_GUARD=0
    if su_ unguard </dev/null >/dev/null 2>&1; then echo "  ESPHome API port 6053 open again"
    else echo "  WARNING: could not open port 6053 again; Home Assistant can't reach the panel until it restarts"; fi
  fi
  adb shell rm -f "$SW_SH" </dev/null >/dev/null 2>&1 || true
}
# The kept data into whichever build is installed now, with it disabled (as HOME it would start).
restore_into() {
  local rc=0
  adb shell pm disable-user --user 0 "$PKG" >/dev/null || true
  # with a hand-over to come, the restore marks it: the app keeps the copied secrets locked until then (#608)
  if [[ $KEY_STATE == carry || $SIGNIN_STATE == carry ]]; then su_ restore handover || rc=$?; else su_ restore || rc=$?; fi
  adb shell pm enable "$PKG" >/dev/null || true
  return $rc
}

# Sends the remembered Wi-Fi to the new app over Improv through adb, then forgets it.
resend_wifi() {
  local port rc=1
  step "Wi-Fi $(net_name) to the new app"
  for _ in $(seq 1 40); do
    [[ "$(adb shell "grep -c ' @s1kiosk_improv\$' /proc/net/unix" | tr -d '\r')" -gt 0 ]] 2>/dev/null && break
    sleep 1.5
  done
  if ! command -v python3 >/dev/null; then
    echo "  python3 not found: can't send it from here"
  elif port=$(adb forward tcp:0 localabstract:s1kiosk_improv | tr -d '\r') && [[ $port =~ ^[0-9]+$ ]]; then
    printf '%s\0%s' "$WIFI_SSID" "$WIFI_PSK" | python3 "$IMPROV_SEND" "$port" && rc=0 || rc=$?
    adb forward --remove "tcp:$port" >/dev/null 2>&1 || true
  fi
  WIFI_PSK="" HAVE_PSK=0
  if [[ $rc -eq 0 ]]; then WIFI_LOST=0; elif [[ $rc -eq 3 ]]; then echo "  The panel could not join with that password."; fi
  return 0
}

# Once a switch has read the sign-in and key, the new app gets them whatever stops the run after it (a bad
# --timezone, a failed step): they live only in this process, and the old app's copy can't be opened any more.
carry_on_exit() {
  local rc=$?
  trap - EXIT
  set +e
  if [[ $SW_HELD -eq 1 ]]; then
    echo "  Stopping early: turning Glassworks back on" >&2
    adb shell pm enable "$PKG" </dev/null >/dev/null 2>&1
  fi
  if [[ $SWITCHED -eq 1 && ( $KEY_STATE == carry || $SIGNIN_STATE == carry ) ]]; then
    echo "  Stopping early: handing the sign-in and encryption key to the new app first" >&2
    adb shell am start -n "$ACT" </dev/null >/dev/null 2>&1
    hand_back >&2
  fi
  if [[ $SW_GUARD -eq 1 && $SW_FRESH -eq 1 ]]; then
    SW_GUARD=0
    echo "  ESPHome API port 6053 stays blocked: Glassworks has no data or key yet. Restart the panel to open it." >&2
    adb shell rm -f "$SW_SH" </dev/null >/dev/null 2>&1
  fi
  if [[ $SW_GUARD -eq 1 ]]; then switch_done >&2; fi
  forget_secrets
  exit $rc
}
trap carry_on_exit EXIT

step "Device"
adb get-state >/dev/null
MODEL=$(adb shell getprop ro.product.model | tr -d '\r')
SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "  model=$MODEL sdk=$SDK"
[[ "$SDK" == "29" ]] || echo "  WARNING: expected Android 10 (sdk 29); continuing"

if [[ $UNDO -eq 1 ]]; then
  step "Undo"
  # The kiosk is a device admin, and Android won't disable or uninstall an active admin: ask the app
  # to drop it, and wait until it has.
  try adb shell am start -n "$ACT" --ez remove_admin true
  if [[ $DRY -eq 0 ]]; then
    for _ in $(seq 1 30); do adb shell dumpsys device_policy | grep -q "$PKG" || break; sleep 0.5; done
  fi
  run env ADB_SERIAL="${ANDROID_SERIAL:-}" "$HERE/debloat/debloat.sh" enable
  # after debloat re-enabled the launcher: set-home-activity fails on a disabled package
  try adb shell cmd package set-home-activity com.aqara.inhome.launcher/.MainActivity
  try adb shell service call A_device_control 7 i32 1 i32 0
  try adb shell pm enable com.baidu.input_auto
  try adb shell ime set com.baidu.input_auto/.ImeService
  # Remove the kiosk (it restarts itself as a foreground service, so stopping it is not enough);
  # fall back to disabling it. Reinstall with this script.
  try adb shell am force-stop "$PKG"
  # Uninstalling makes Android 10 forget the Wi-Fi networks Glassworks saved, which would take the panel
  # off its network. Then Glassworks is disabled instead (reversible: pm enable), and the network stays.
  # APP_AFTER_UNDO comes from each step's exit status: removed, kept (disabled for its Wi-Fi),
  # disabled (Android would not uninstall it) or failed (it would not disable either).
  panel_wifi
  if wifi_at_risk; then
    if [[ $DRY -eq 1 ]]; then echo "  + adb shell pm disable-user --user 0 $PKG"; APP_AFTER_UNDO=kept
    elif adb shell pm disable-user --user 0 "$PKG" >/dev/null; then APP_AFTER_UNDO=kept
    else APP_AFTER_UNDO=failed; fi
    if [[ $APP_AFTER_UNDO == failed ]] && absent; then APP_AFTER_UNDO=removed; fi
    if [[ $APP_AFTER_UNDO == kept ]]; then
      if [[ $WIFI_OWNER == kiosk ]]; then
        echo "  kept Glassworks installed but disabled: it saved the panel's Wi-Fi $(net_name), and Android"
        echo "  forgets that network when Glassworks is uninstalled"
      else
        echo "  kept Glassworks installed but disabled: the installer can't tell who saved the panel's Wi-Fi"
        echo "  $(net_name), and if Glassworks did, Android forgets it when Glassworks is uninstalled"
      fi
    fi
  elif [[ $DRY -eq 1 ]]; then
    echo "  + adb shell pm uninstall $PKG || adb shell pm disable-user --user 0 $PKG"
  elif ! adb shell pm uninstall "$PKG" | grep -q Success; then
    if adb shell pm disable-user --user 0 "$PKG" >/dev/null; then APP_AFTER_UNDO=disabled
    elif ! absent; then APP_AFTER_UNDO=failed
    fi # pm refuses both for a package that isn't there: an earlier --undo that stopped at the HOME check removed it
  fi
  # Radio (frames/aqara-s1-plus/docs/radio.md): the app kept Aqara's radio services stopped ("Off" mode) and blocked
  # ha_master, Aqara's cloud link. Start them in provider-first order; they are init services, so a
  # reboot would bring them back as well.
  for svc in zigbee_cpcd zigbee_socat zigbee_zigbeed zigbee_host zigbee_agent ha_matter ha_master; do
    try adb shell setprop ctl.start "$svc"
  done
  # The frame agent turned Android's 7-day adb key timeout off (frames/aqara-s1-plus/docs/radio.md, computers over
  # USB adb): back to Android's default.
  try adb shell settings delete global adb_allowed_connection_time
  if [[ $DRY -eq 0 ]]; then
    HOME_ACT=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1)
    case "$HOME_ACT" in
      com.aqara.inhome.launcher/*) adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null ;;
      # Glassworks still enabled is still a HOME app, so HOME is the chooser (or Glassworks): the
      # failed message below says that, not this one.
      *) if [[ $APP_AFTER_UNDO != failed ]]; then echo "ERROR: the home screen is still $HOME_ACT, not Aqara's launcher" >&2; exit 1; fi ;;
    esac
  fi
  case $APP_AFTER_UNDO in
    removed) echo "Done. Aqara's apps are back and Glassworks is removed (reinstall: $0 --url ...)." ;;
    kept)
      echo "Done. Aqara's apps are back and Glassworks is disabled, so the panel stays on its Wi-Fi."
      echo "To remove Glassworks completely, see frames/aqara-s1-plus/docs/install.md#undo. Reinstall: $0 --url ..." ;;
    disabled)
      echo "Done. Aqara's apps are back. Android would not remove Glassworks, so it is disabled instead."
      echo "To remove Glassworks completely, see frames/aqara-s1-plus/docs/install.md#undo. Reinstall: $0 --url ..." ;;
    failed)
      echo "ERROR: Aqara's apps are back, but Android would not disable Glassworks. It is still installed, so the" >&2
      echo "  panel may start it again or ask which home screen to use. Run $0 --undo again." >&2
      exit 1 ;;
  esac
  exit 0
fi

step "Install kiosk app"
[[ -f "$APK" ]] || { echo "APK not found: $APK (build it: ./gradlew :kiosk:assembleDebug)" >&2; exit 1; }
if [[ $DRY -eq 1 ]]; then
  echo "  + adb install -r -g $APK"
  if adb shell pm list packages "$PKG" | tr -d '\r' | grep -qx "package:$PKG"; then
    probe_switch
    echo "  If Android refuses it because the installed Glassworks has another signing key, the switch"
    echo "  would (after you confirm, or with --switch):"
    explain_switch if
    echo "  + adb shell cp <installed APK> $OLD_APK      # to fall back on"
    if [[ $HAS_ROOT -eq 1 ]]; then
      if wifi_at_risk; then echo "  + (as root) $SW_SH wifi-list / wifi-psk: Wi-Fi details of $(net_name), into memory only"; fi
      if [[ $CARRY_KEY -eq 1 || $CARRY_SIGNIN -eq 1 ]]; then
        echo "  + the app's hand-over socket: $([[ $CARRY_SIGNIN -eq 1 ]] && echo 'Home Assistant sign-in and ')encryption key, into memory only"
      fi
      echo "  + (as root) $SW_SH guard                  # drop TCP 6053 (the ESPHome API) until the switch is done"
      echo "  + (as root) $SW_SH backup                 # app data -> /data/s1kiosk/switch"
    fi
    echo "  + adb shell am start -n $ACT --ez remove_admin true"
    echo "  + adb shell pm uninstall $PKG"
    echo "  + adb install -r -g $APK      # on failure: pm install -r -g $OLD_APK$([[ $HAS_ROOT -eq 1 ]] && echo ' + restore')"
    if [[ $HAS_ROOT -eq 1 ]]; then echo "  + (as root) $SW_SH restore; $SW_SH discard"; fi
    if [[ $HAS_ROOT -eq 1 && ( $CARRY_KEY -eq 1 || $CARRY_SIGNIN -eq 1 ) ]]; then
      echo "  + after starting the kiosk: the sign-in and key to the new app over its hand-over socket (frames/aqara-s1-plus/tools/install/handover.py)"
    fi
    if [[ $HAS_ROOT -eq 1 ]]; then echo "  + (as root) $SW_SH unguard                # port 6053 open again"; fi
    if wifi_at_risk; then echo "  + after starting the kiosk: Wi-Fi $(net_name) to the new app over Improv (frames/aqara-s1-plus/tools/install/improv-send.py)"; fi
  fi
else
  out=$(adb install -r -g "$APK" 2>&1) || true
  sed 's/^/  /' <<<"$out"
  if ! grep -q Success <<<"$out"; then
    if is_downgrade "$out"; then
      d=$(adb shell dumpsys package "$PKG" | tr -d '\r')
      v=$(sed -n 's/^ *versionName=\([^ ]*\).*/\1/p' <<<"$d" | head -1); c=$(sed -n 's/^ *versionCode=\([0-9]*\).*/\1/p' <<<"$d" | head -1)
      echo "ERROR: $(basename "$APK") is an older version than the Glassworks on the panel${v:+ ($v, versionCode ${c:-?})}, and" >&2
      echo "  Android won't install an older version over a newer one. Nothing was changed. A development build" >&2
      echo "  takes the panel's version with ./gradlew :kiosk:assembleDebug -Ps1Version=${v:-<version>}." >&2
      exit 1
    fi
    is_key_mismatch "$out" || { echo "ERROR: installing $APK failed" >&2; exit 1; }
    switch_key
  fi
fi

step "Permissions"
try adb shell pm grant "$PKG" android.permission.RECORD_AUDIO
try adb shell pm grant "$PKG" android.permission.CAMERA
try adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS   # presence range
try adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow                 # start from boot
try adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow            # PackageInstaller fallback without root update service
# Device admin (force-lock only) for instant screen-off. "already active" is fine.
try adb shell dpm set-active-admin "$PKG/.AdminReceiver"

step "Make the kiosk the HOME app"
sh_ cmd package set-home-activity "$ACT"

if [[ $DND_OFF -eq 1 ]]; then
  step "Disable Aqara DND screen-off (the kiosk handles night mode)"
  try adb shell service call A_device_control 7 i32 0 i32 0
fi

if [[ $DEBLOAT -eq 1 ]]; then
  step "De-bloat (reversible pm disable-user)"
  # the launcher too (HOME is the kiosk now), in the same run: no "launcher NOT disabled" note
  run env ADB_SERIAL="${ANDROID_SERIAL:-}" "$HERE/debloat/debloat.sh" disable --launcher
fi

if [[ -n "$KEYBOARD" ]]; then
  step "Keyboard"
  run adb install -r "$KEYBOARD"
  IME=$(adb shell ime list -a -s | tr -d '\r' | grep -v baidu | head -1)
  [[ -n "$IME" ]] && { sh_ ime enable "$IME"; sh_ ime set "$IME"; try adb shell pm disable-user --user 0 com.baidu.input_auto; }
fi

if [[ -n "$TZ_" ]]; then
  # adb shell runs it through the panel's shell: only Olson-id characters (same rule as the web installer)
  [[ "$TZ_" =~ ^[A-Za-z0-9_+/-]+$ ]] || { echo "bad --timezone '$TZ_' (an id like Europe/Berlin)" >&2; exit 1; }
  step "Time zone $TZ_"
  sh_ settings put global auto_time_zone 0
  sh_ service call alarm 3 s16 "$TZ_"
  # kept by the root helper (v4): Aqara's SystemManager resets the zone at every boot
  sh_ setprop persist.s1kiosk.timezone "$TZ_"
fi
if [[ -n "$LOCALE" ]]; then
  step "Locale $LOCALE (applies after reboot)"
  sh_ settings put system system_locales "$LOCALE"
fi

if [[ $MODELS -eq 1 ]]; then
  step "Wake word models"
  run "$ROOT/tools/voice/fetch-models.sh"
fi

step "Start the kiosk"
if [[ -n "$URL" ]]; then
  sh_ am start -n "$ACT" --es url "$URL"
else
  sh_ am start -n "$ACT"
fi

# Before the Wi-Fi: joining a network can restart the app, and the sign-in shows the dashboard at once.
if [[ $SWITCHED -eq 1 && ( $KEY_STATE == carry || $SIGNIN_STATE == carry ) ]]; then
  step "Sign-in and encryption key to the new app"
  hand_back
fi
if [[ $SW_GUARD -eq 1 ]]; then switch_done; fi
forget_secrets
if [[ $SWITCHED -eq 1 && $HAVE_PSK -eq 1 ]]; then resend_wifi; fi
WIFI_PSK=""

# A factory-fresh panel has no Wi-Fi yet (no wlan0): that must not fail the run under set -e.
IP=$(adb shell ip -4 -o addr show wlan0 2>/dev/null | sed -n 's/.*inet \([0-9.]*\).*/\1/p' | head -1 || true)
printf '\nDone.\n'
# a switch that carried the sign-in (#584) leaves the panel signed in
if [[ $SIGNIN_STATE != kept ]]; then echo "- Log in to Home Assistant on the panel once (the session is remembered)."; fi
cat <<EOF
- Home Assistant: Settings -> Devices & services -> Add integration -> ESPHome,
  host ${IP:-<panel IP>}, port 6053 (or accept the auto-discovered "S1 Panel").
- Hidden settings on the panel: tap the top-left corner 5 times.
- Undo everything: $0 --undo
EOF
if [[ $SWITCHED -eq 1 ]]; then
  if [[ $DATA_KEPT -eq 1 ]]; then
    lost=$(lost_secrets)
    if [[ -z $lost ]]; then
      if [[ $SIGNIN_STATE == kept && $KEY_STATE == kept ]]; then kept="settings, Home Assistant sign-in and encryption key"
      elif [[ $SIGNIN_STATE == kept ]]; then kept="settings and Home Assistant sign-in"
      elif [[ $KEY_STATE == kept ]]; then kept="settings and encryption key"
      else kept="settings"; fi
      echo "- Switched builds: the app's $kept were kept."
    else
      echo "- Switched builds: the app's settings were kept, but not its $lost"
      echo "  (Android deleted the old app's Keystore keys).$([[ $SIGNIN_STATE == lost || $SIGNIN_STATE == carry ]] && echo ' Sign in again.')$([[ $KEY_STATE == lost || $KEY_STATE == carry ]] && echo ' Home Assistant may ask to confirm the key.')"
    fi
  else echo "- Switched builds: the app's settings were reset (sign in again; Home Assistant may ask to set the panel up again)."; fi
fi
if [[ $WIFI_LOST -eq 1 ]]; then
  cat <<EOF
- The panel $([[ $WIFI_OWNER == kiosk ]] && echo forgot || echo 'may have forgotten') its Wi-Fi $(net_name) with the old build. Send it again: the web installer's
  #/wifi page over USB, or Improv over Bluetooth (the panel's setup screen, or Home Assistant).
EOF
fi
