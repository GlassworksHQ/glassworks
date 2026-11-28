#!/system/bin/sh
# glassworks_agent: the S1's frame agent, the root-side service that gives the panes the hardware they
# need (docs/principles.md#the-frame-agent): the radio modes and their firewall, radio firmware, the time zone, the
# Bluetooth address, and the setup hotspot's portal and name (docs/radio.md). Contract v5: the radio
# helper's v4 with Glassworks names, run by the frame loader (frames/aqara-s1-plus/research/frame-loader.md).
#
# Runs in the glassworks_agent slot (glassworks.rc: root, u:r:su:s0, started at boot, never exits):
# glassworks_loader.sh runs this file from the frame bundle (services/agent.sh), or the boot image's
# copy. The old radio helper with the s1kiosk names, s1kiosk_radio.sh, is kept for one release only (#528).
# Reads persist.glassworks.radio_mode, brings Aqara's radio services to the state that mode needs,
# starts/supervises our own processes (EZSP TCP bridges, zigbeed, otbr-agent) and reports in
# sys.glassworks.radio.status as "<applying|ok|error> <mode>[: detail]".
# The app changes mode with: setprop persist.glassworks.radio_mode <id>; setprop ctl.restart glassworks_agent
# (init kills the whole process group on restart, so our children go with it.)
#
# Mode ids: off (default) | zigbee_tcp | zigbee_router | thread | thread_router
#   experimental, not offered by the app: zigbee_tcp_thread (Zigbee + Thread on one multi-PAN radio)
# There is no stock mode: Aqara's services are init services, so removing this agent (or the app,
# tools/install.sh --undo) and rebooting brings the stock stack back.
#
#   zigbee_tcp     zigbeed's EZSP on tcp:$ZIGBEE_PORT (ZHA: socket://<panel>:6638, Zigbee2MQTT:
#                  tcp://<panel>:6638). Zigbee stack (v4): Aqara's zigbeed 7.3.3 (EZSP 12, default) or
#                  Silicon Labs' zigbeed 8.2.2 (EZSP 18, persist.glassworks.radio_zigbee=sisdk). The 8.2.2
#                  binary is Silicon Labs MSLA code: never in our bundles; the app downloads it from
#                  Silicon Labs and this agent accepts it only with the pinned SHA-256.
#   zigbee_router  zigbeed's EZSP on 127.0.0.1:$ROUTER_PORT only; the app's router host
#                  (radio/ZigbeeRouterHost.kt) drives zigbeed as an EMBER_ROUTER. Always Aqara's
#                  zigbeed (the router host speaks EZSP 12). zigbeed's NVM is copied to
#                  $DIR/zigbee-nvm/ first, because joining replaces a coordinator network.
#   thread         otbr-agent on spinel IID 2. With a border-routing build (tools/radio/build-otbr.sh
#                  PROFILE=br): backbone $BACKBONE (wlan0), REST API on $REST_LISTEN:$REST_PORT for
#                  Home Assistant's OTBR integration, OpenThread mDNS/SRP, IPv6 forwarding + the
#                  policy-routing rules Android needs. With the minimal proof build: agent only.
#   thread_router  otbr-agent without a backbone (no border routing), joins with the active dataset
#                  in $DIR/thread-dataset. The app hands a new dataset over in $APP_DATASET (its own
#                  private files dir); the agent moves it into $DIR (root 0600) and deletes the
#                  app's copy. Without a dataset the stack runs and waits ("ready to join").
#
# Firewall (contract v3): in every mode but off, an iptables/ip6tables INPUT chain ($FW_CHAIN) lets
# only loopback and the allowed addresses reach tcp:$ZIGBEE_PORT and tcp:$REST_PORT; everyone else
# is dropped. The allowed addresses come from the app (persist.glassworks.radio_allow: Home Assistant's
# address as seen on the ESPHome API, or the owner's explicit list), or RADIO_ALLOW in radio.conf.
# Empty = nobody but loopback yet. "any" = no rules (the v2 behaviour). Removed in off.
#
# Frame bundles (contract v5): glassworks_loader.sh picks this script and passes GW_BOOT (the boot
# image's files), GW_BUNDLE (the checked frame bundle, whose binaries and firmware win over the boot
# image's), GW_SOURCE and GW_DEV. sys.glassworks.agent.source says which copy runs:
# "boot[ (why)]" | "bundle <frame version>" | "dev". At its first start on a panel it moves the app
# settings from the persist.s1kiosk.* names to persist.glassworks.*, once (#528, see below).
#
# Radio firmware (contract v4): never automatic. The app writes "<id> <sha256 prefix>" to its own
# files/radio/fw-request; within 3 s the agent stops the radio, flashes that image with Aqara's own
# /vendor/bin/cpcd -c /etc/cpcd_ota.conf -f (the only proven path on this board), reads the radio's
# version back over CPC and rolls back to Aqara's stock image from /vendor/firmware if that fails.
# Images: "stock" (Aqara's GBL on /vendor) or a "firmware" item of the signed radio bundle.
# Status: sys.glassworks.radio.fw = "<idle|flashing|ok|error> <id>[: detail]", targets in
# sys.glassworks.radio.fw_targets = "<id>:<sha256 prefix>:<APP version> ...".
#
# Optional root-owned overrides in $DIR/radio.conf (sourced):
#   ZIGBEE_PORT=6638  ZIGBEE_RANGE=lan|<cidr>  ROUTER_PORT=6639  BACKBONE=wlan0
#   REST_LISTEN=0.0.0.0  REST_PORT=8081  OTBR_ARGS="..."  RADIO_URL=...
#   RADIO_ALLOW="<ip|cidr> ..."|any|none   (wins over the app's list; empty = the app's list)
#   ZIGBEE_STACK=aqara|sisdk               (wins over the app's choice; empty = the app's choice)
#   ZIGBEED_ARGS="..."                     (extra arguments for Silicon Labs' zigbeed, e.g. "-v 1 -d 3")
# Time zone (contract v4): Aqara's SystemManager resets the zone at every boot (and whenever its own
# gateway time-zone setting changes): with that setting empty, as on our panels with Aqara's cloud
# blocked, it picks the first zone at UTC+8, Antarctica/Casey. A background loop of this agent keeps
# the owner's zone instead: persist.glassworks.timezone (an Olson id, set by the installer or the app's
# `Time zone` entity) is re-applied with `service call alarm 3 s16 <id>` (AlarmManager.setTimeZone)
# whenever persist.sys.timezone differs, every 5 s for the first 10 minutes, then every 15 s. A zone
# Android doesn't accept is reported once and not retried until the wanted zone changes. Status:
# sys.glassworks.timezone.status = "<ok|error|none> [<id>][: detail]". The loop runs whatever the radio
# mode does (also after "giving up"), and is restarted with the agent.
#
# Computers over USB adb: Manage answers "Allow USB debugging?" (see "computers allowed over USB adb" below).
#
# Bluetooth address (#246): Android 10 hides the Bluetooth adapter's address from apps, and the app's
# Bluetooth proxy sends it to Home Assistant (bluetooth_mac_address), as an ESPHome proxy does. Once
# Android knows a real one (settings secure bluetooth_address, after Bluetooth was first on), it is
# written to $PUB_DIR/bluetooth-address (root 0644, the app reads it). Tried at most once a minute until
# then, because `settings` starts a JVM, and given 10 s each time. Never logged. Placeholders (all zero, 02:00:00:00:00:00,
# broadcast) are never written.
#
# Who is connected (#217): in the coordinator modes, sys.glassworks.radio.peer = "none" | "<address> <epoch
# first seen>", the EZSP bridge's client from /proc/net/tcp and tcp6 (see peer_report); "" in other modes.
#
# Setup hotspot portal (#197): while the kiosk's setup hotspot is up, the hotspot's DNS and port 80 go to
# the kiosk's own responders, so a phone that joins opens the setup page by itself (see "the setup
# hotspot's captive portal" below). Status: sys.glassworks.portal.status. Runs in every mode.
#
# Setup hotspot name (#197): with the overlay in the frame bundle, a Magisk module puts it on /product, so Android
# names the setup hotspot Glassworks_NNNN from the next restart. Status: sys.glassworks.hotspot_name.status.
#
# Developer override: the loader's (/data/glassworks/dev/override and dev/agent.sh, root-made). Then
# binaries in $DIR win too.
#
# $DIR keeps its s1kiosk name: otbr-agent has $DIR/run compiled in (build-otbr.sh), with the Thread
# settings under it. It moves with an otbr built for the new path, in a later bundle.

DIR=${GWA_DIR:-/data/s1kiosk/radio}     # radio.conf, thread-dataset, zigbee-nvm/, fw/, sisdk/
CONTRACT=5
BOOT=${0%/*}                             # where this script lives (boot image: ${MAGISKTMP})

setp() { # name value: setprop refuses values over 91 bytes (PROP_VALUE_MAX) and keeps the old one
  v_=$2; [ ${#v_} -gt 91 ] && v_="$(printf '%.88s' "$v_")..."
  setprop "$1" "$v_"
}


BUNDLE=${GW_BOOT:-$BOOT}                 # the boot image's files (verifier, key, otbr-agent, ot-ctl)
RBUNDLE=${GW_BUNDLE:-}                   # the frame bundle glassworks_loader.sh checked, or empty
SOURCE=${GW_SOURCE:-boot}
DEV=${GW_DEV:-}
ZIGBEE_PORT=6638
# Who may connect to the coordinator bridge (socat range=). Empty = anyone (default: Home Assistant
# is often on another subnet/VLAN than the panel). "lan" = the panel's current wlan0 subnet, followed
# when DHCP changes it. Or a fixed CIDR such as 10.0.0.0/8.
ZIGBEE_RANGE=""
ROUTER_PORT=6639                         # zigbee_router: localhost-only bridge for the app's host
BACKBONE=""                              # thread: default wlan0 with a border-routing build
REST_LISTEN=0.0.0.0                      # thread (BR build): REST API for HA; 127.0.0.1 = local only
REST_PORT=8081
RADIO_URL='spinel+cpc://cpcd_0?iid=2&iid-list=0'  # zigbeed holds iid=1
OTBR_ARGS=""
# Who may reach tcp:$ZIGBEE_PORT / tcp:$REST_PORT (firewall, see the header). Empty = the app's list.
RADIO_ALLOW=""
ZIGBEE_STACK=""                          # radio.conf: aqara|sisdk; empty = persist.glassworks.radio_zigbee
ZIGBEED_ARGS=""
PROP_ALLOW=persist.glassworks.radio_allow # app -> agent: allowed addresses ("" | any | "<ip|cidr> ...")
PROP_ALLOWED=sys.glassworks.radio.allowed   # agent -> app: "<app|conf> <any|none|<ip|cidr> ...>", "" in off
FW_CHAIN=GWRADIO
APP_DATA=/data/data/sh.edm.s1kiosk
APP_DATASET=$APP_DATA/files/radio/thread-dataset  # app -> agent hand-off (thread_router)
EZSP_PTY=/dev/ttyZigbeeNCP               # host side of Aqara's socat pty pair (zigbeed is on /tmp/ttyZigbeeNCP)
ZIGBEED_PTY=/tmp/ttyZigbeeNCP
ZIGBEED_NVM=/data/zigbee_host/zigbeed_host_token.nvm
CPC_SOCK=/dev/shm/cpcd/cpcd_0/ctrl.cpcd.sock
RULE_PREF_IN=14500                       # thread BR: "iif <backbone> lookup <wpan0's table>" (LAN -> Thread)
RULE_PREF_OUT=14501                      # thread BR: "iif wpan0 lookup <backbone>" (Thread -> LAN)
# Silicon Labs zigbeed 8.2.2 (EmberZNet 8.2.2, EZSP 18): Simplicity SDK v2025.6.3 debian-bookworm.zip,
# deb/zigbeed_8.2.2_armhf.deb, usr/local/bin/zigbeed, unmodified (tools/radio/build-zigbeed.sh pins).
SISDK_VERSION=8.2.2
SISDK_SHA256=a66d45842a770e1c449a55208a6d2a3b36786eb1c94bf10d3c3aac317274e3bb
SISDK_SIZE=663312
SISDK_BIN=$DIR/sisdk/zigbeed-$SISDK_VERSION   # root 0700, taken from the app's download
SISDK_STATE=$DIR/zigbeed-sisdk                # its host_token.nvm (Aqara's NVM is never touched)
APP_ZIGBEED=radio/zigbeed-$SISDK_VERSION      # app -> agent hand-off, relative to the app's files/
ZIGBEED_URL='spinel+cpc://cpcd_0?iid=1&iid-list=0'
# Radio firmware: Aqara's stock RCP image (the rollback target) and the ones we know.
FW_DIR=$DIR/fw
STOCK_GLOB='/vendor/firmware/lichun#30000000#*.bin'
KNOWN_STOCK="d7b79c6fab1e36e438d0e510bc596de7ac053f6d7b4b99a291af3f323baa8fa9:0017"
APP_FW_REQ=radio/fw-request                   # app -> agent, relative to the app's files/
APP_THREAD_LEAVE=radio/thread-leave           # app -> agent: forget the Thread network (any mode)

# Root-only state dir; created on first run so a freshly flashed boot image needs no other install step.
[ -d "$DIR" ] || { mkdir -p "$DIR" && chown root:root "$DIR" && chmod 700 "$DIR"; }
# The app settings an app from before the loader keeps under the s1kiosk names (#528) are copied to the
# Glassworks names: at the first start where the new one is empty, then again whenever the old value
# changes (a 0.4.3 boot image or app changed it, e.g. after a Kiln update stopped at its RAM boot).
# $SM (root 0600) records the old values last copied, one "key value" line each, so an empty new value
# stays empty (an empty allow-list is loopback only) until the old one changes. A failed write keeps the
# old record, so it is tried again at the next start. TODO(#528): drop with the old names.
SM=$DIR/.settings-moved
for k_ in radio_mode radio_zigbee radio_allow timezone hotspot_name; do
  o_=$(getprop "persist.s1kiosk.$k_")
  if [ -f "$SM" ]; then
    r_=$(sed -n "s/^$k_ //p" "$SM" | head -1)
    [ "$o_" = "$r_" ] || setprop "persist.glassworks.$k_" "$o_" || o_=$r_
  elif [ -n "$o_" ] && [ -z "$(getprop "persist.glassworks.$k_")" ]; then
    setprop "persist.glassworks.$k_" "$o_" || o_=""
  fi
  echo "$k_ $o_"
done > "$SM.tmp" && chmod 600 "$SM.tmp" && mv -f "$SM.tmp" "$SM"
# Binaries: developer copies in $DIR (with dev-override only), then the radio bundle, then the boot image.
bin() {
  if [ -n "$DEV" ] && [ -x "$DIR/$1" ]; then echo "$DIR/$1"
  elif [ -n "$RBUNDLE" ] && [ -x "$RBUNDLE/$1" ]; then echo "$RBUNDLE/$1"
  else echo "$BUNDLE/$1"; fi
}
[ -r "$DIR/radio.conf" ] && . "$DIR/radio.conf"

MODE=${GWA_MODE:-$(getprop persist.glassworks.radio_mode)}  # GWA_MODE: manual test without touching the persist prop
[ -n "$MODE" ] || MODE=off

say() { log -t glassworks_agent "$*" 2>/dev/null; }
SHOWN=""
status() { # state [detail]; only writes when it changed
  if [ -n "$2" ]; then st_="$1 $MODE: $2"; else st_="$1 $MODE"; fi
  # setprop refuses values over 91 bytes (PROP_VALUE_MAX) and the old status would stay: cut it
  [ ${#st_} -gt 91 ] && st_="$(printf '%.88s' "$st_")..."
  [ "$st_" = "$SHOWN" ] && return 0
  SHOWN=$st_
  setprop sys.glassworks.radio.status "$st_"
  say "$st_"
}
setprop sys.glassworks.agent.version $CONTRACT   # v5 = v4 (v3 + bundles, Zigbee stack, radio firmware) with Glassworks names
setp sys.glassworks.agent.source "$SOURCE"

# What the installed otbr-agent can do (app: which Thread modes are real): br | agent | none
otbr_kind() {
  b=$(bin otbr-agent)
  if [ ! -x "$b" ]; then echo none
  elif grep -q -F /node/ba-id "$b" 2>/dev/null; then echo br
  else echo agent; fi
}
OTBR_KIND=$(otbr_kind)
setprop sys.glassworks.radio.otbr "$OTBR_KIND"
[ "$OTBR_KIND" = br ] && [ -z "$BACKBONE" ] && BACKBONE=wlan0

# --- time zone ------------------------------------------------------------------------------------
TZ_PROP=persist.glassworks.timezone          # wanted: an Olson id ("" = not managed)
TZ_STATUS=sys.glassworks.timezone.status
# What Aqara's sync picks for its default "GMT+08:00" (never adopted as the owner's choice).
TZ_AQARA_DEFAULT="Antarctica/Casey"
TZ_PID=""; TZ_BAD=""; TZ_SHOWN=""
tz_valid() { # an Olson id: letters/digits/_+- in up to 3 /-separated parts, or UTC/GMT forms
  case "$1" in ""|/*|*/|*//*|*[!A-Za-z0-9_+/-]*) return 1 ;; esac
  [ ${#1} -le 64 ] && [ "$(echo "$1" | tr -cd / | wc -c)" -le 2 ]
}
tz_say() { [ "$1" = "$TZ_SHOWN" ] && return 0; TZ_SHOWN=$1; setp $TZ_STATUS "$1"; say "time zone: $1"; }
tz_check() {
  want_=$(getprop $TZ_PROP); have_=$(getprop persist.sys.timezone)
  if [ -z "$want_" ]; then
    # Panels set up before this existed: the zone the installer set is still in persist.sys.timezone
    # at boot, until Aqara's sync overwrites it. Adopt it once if it is a real choice.
    if tz_valid "$have_" && [ "$have_" != "$TZ_AQARA_DEFAULT" ] && [ "$have_" != GMT ] && [ "$have_" != UTC ]; then
      setprop $TZ_PROP "$have_"; say "time zone: adopted $have_ as the zone to keep"; want_=$have_
    else
      tz_say "none: no time zone chosen (Time zone in Home Assistant, or the installer)"; return 0
    fi
  fi
  if ! tz_valid "$want_"; then tz_say "error: '$(printf '%.24s' "$want_")' is not a time zone id"; return 0; fi
  if [ "$want_" = "$have_" ]; then TZ_BAD=""; tz_say "ok $want_"; return 0; fi
  [ "$want_" = "$TZ_BAD" ] && return 0     # Android refused it before: don't loop on it
  [ "$(settings get global auto_time_zone 2>/dev/null)" = 0 ] || settings put global auto_time_zone 0 2>/dev/null
  service call alarm 3 s16 "$want_" >/dev/null 2>&1
  i_=0; while [ "$(getprop persist.sys.timezone)" != "$want_" ] && [ $i_ -lt 20 ]; do sleep 0.1; i_=$((i_ + 1)); done
  now_=$(getprop persist.sys.timezone)
  if [ "$now_" = "$want_" ]; then
    say "time zone: ${have_:-unset} -> $want_ (re-applied)"; tz_say "ok $want_"
  else
    TZ_BAD=$want_; tz_say "error $want_: Android did not accept it (now ${now_:-unset})"
  fi
}
tz_start() { # one check now (so the first pass is done before the radio work), then the loop
  tz_check
  parent_=$$   # the agent; the loop ends with it (also if it exits without tz_stop)
  ( t0_=$(date +%s)
    while :; do
      if [ $(( $(date +%s) - t0_ )) -lt 600 ]; then sleep 5; else sleep 15; fi
      kill -0 "$parent_" 2>/dev/null || exit 0
      tz_check
    done ) &
  TZ_PID=$!
}
tz_stop() { alive "$TZ_PID" && kill "$TZ_PID"; TZ_PID=""; }
tz_start

alive() { [ -n "$1" ] && kill -0 "$1" 2>/dev/null; }
sha256() { set -- $(sha256sum "$1" 2>/dev/null); echo "$1"; }

# --- files the app hands over --------------------------------------------------------------------
# take_app_file <path relative to the app's files/> <N|max:N> <dest> [move]: copies a file the kiosk
# wrote into a root-only place, with `glassworks_verify --copy` (or --move, which also removes the app's
# copy): an openat(O_NOFOLLOW) walk where every component must be the kiosk's, the file opened
# O_NONBLOCK and checked on the fd (regular, one link, size), exactly that many bytes copied. So a path
# swapped for a symlink, a FIFO or /dev/zero can't make root read something else or hang. Returns 1
# quietly when it isn't there, 2 with WHY when it is unacceptable; TAKE_MTIME = the file's mtime.
APP_UID=""; TAKE_MTIME=0
take_app_file() {
  [ -n "$APP_UID" ] || APP_UID=$(stat -c %u "$APP_DATA" 2>/dev/null)
  case "$APP_UID" in ''|*[!0-9]*) WHY="kiosk not installed"; return 2 ;; esac
  [ "$APP_UID" -ge 10000 ] || { WHY="kiosk data dir has a system owner"; return 2; }
  [ -x "$BUNDLE/glassworks_verify" ] || { WHY="no glassworks_verify in the boot image"; return 2; }
  rm -f "$3"
  o_=$("$BUNDLE/glassworks_verify" "--${4:-copy}" "$APP_DATA" "files/$1" "$APP_UID" "$2" "$3" 2>&1); r_=$?
  case $r_ in
    0) set -- $o_; TAKE_MTIME=${2:-0}; return 0 ;;
    3) return 1 ;;
    4) o_=${o_#*_verify: }; WHY="${1##*/} refused: ${o_%%: *}" ;;
    *) WHY="cannot copy ${1##*/}" ;;
  esac
  rm -f "$3"; return 2
}

# --- Zigbee stack (zigbee_tcp only) ---------------------------------------------------------------
ZWANT=${GWA_ZIGBEE:-${ZIGBEE_STACK:-$(getprop persist.glassworks.radio_zigbee)}}
[ "$ZWANT" = sisdk ] || ZWANT=aqara
ZRUN=aqara; [ "$MODE" = zigbee_tcp ] && [ "$ZWANT" = sisdk ] && ZRUN=sisdk
SISDK_DIR=""; [ -n "$RBUNDLE" ] && [ -f "$RBUNDLE/sisdk/zigbeed.sh" ] && SISDK_DIR=$RBUNDLE/sisdk
ZSHOWN=""
zstate() { # what the app sees in sys.glassworks.radio.zigbee: "aqara" | "sisdk <ready|missing|no-runtime|bad ...>"
  if [ "$ZWANT" = aqara ]; then z_=aqara
  elif [ -z "$SISDK_DIR" ]; then z_="sisdk no-runtime"
  elif [ -x "$SISDK_BIN" ]; then z_="sisdk ready $SISDK_VERSION"
  elif [ -n "$SISDK_BAD" ]; then z_="sisdk bad $SISDK_BAD"
  else z_="sisdk missing $SISDK_VERSION $SISDK_SHA256"; fi
  [ "$z_" = "$ZSHOWN" ] || { setp sys.glassworks.radio.zigbee "$z_"; ZSHOWN=$z_; }
}
# The app downloads zigbeed 8.2.2 from Silicon Labs into its files dir; take it if it is the pinned one.
SISDK_BAD=""
take_sisdk() {
  [ "$ZWANT" = sisdk ] && [ -n "$SISDK_DIR" ] && [ ! -x "$SISDK_BIN" ] || return 0
  mkdir -p "$DIR/sisdk" && chmod 700 "$DIR/sisdk"
  take_app_file "$APP_ZIGBEED" "$SISDK_SIZE" "$SISDK_BIN.tmp"; r_=$?
  [ $r_ = 1 ] && return 0
  [ $r_ = 2 ] && { [ "$SISDK_BAD" = "$WHY" ] || say "zigbeed hand-off refused: $WHY"; SISDK_BAD=$(echo "$WHY" | cut -c 1-40); return 0; }
  h_=$(sha256 "$SISDK_BIN.tmp")
  if [ "$h_" = "$SISDK_SHA256" ] && [ "$(stat -c %s "$SISDK_BIN.tmp")" = "$SISDK_SIZE" ]; then
    chmod 700 "$SISDK_BIN.tmp" && mv "$SISDK_BIN.tmp" "$SISDK_BIN" && SISDK_BAD="" &&
      say "Silicon Labs zigbeed $SISDK_VERSION installed (sha256 matches the pin)"
  else
    rm -f "$SISDK_BIN.tmp"
    [ "$SISDK_BAD" = "$(echo "$h_" | cut -c 1-12)" ] || say "zigbeed hand-off refused: sha256 $h_ is not the pinned $SISDK_SHA256"
    SISDK_BAD=$(echo "$h_" | cut -c 1-12)
  fi
}

# --- Aqara's services --------------------------------------------------------------------------
# Consumers first (ha_matter = Aqara's Matter bridge for its Zigbee devices). Aqara's own consumers
# (Z3Gateway, zigbee_agent, ha_matter) never run in any of our modes.
STOP_ORDER="ha_matter zigbee_agent zigbee_host zigbee_zigbeed zigbee_socat zigbee_cpcd"
START_ORDER="zigbee_cpcd zigbee_socat zigbee_zigbeed"

case "$MODE" in
  off)                                        WANT="" ;;
  zigbee_tcp|zigbee_router|zigbee_tcp_thread) WANT="zigbee_cpcd zigbee_socat zigbee_zigbeed" ;;
  thread|thread_router)                       WANT="zigbee_cpcd" ;;
  *) status error "unknown mode"; exec sleep 2147483647 ;;
esac
# Silicon Labs' zigbeed replaces Aqara's: same pty pair, our process instead of zigbee_zigbeed.
[ "$ZRUN" = sisdk ] && WANT="zigbee_cpcd zigbee_socat"
MODE_WANT=$WANT

wanted() { case " $WANT " in *" $1 "*) return 0 ;; esac; return 1; }

svc_state() { getprop init.svc.$1; }

svc() { # name running|stopped -> waits up to 5 s
  s=$(svc_state $1)
  if [ "$2" = running ]; then
    [ "$s" = running ] && return 0
    setprop ctl.start $1
  else
    { [ "$s" = stopped ] || [ -z "$s" ]; } && return 0
    setprop ctl.stop $1
  fi
  i=0
  while [ $i -lt 50 ]; do
    s=$(svc_state $1)
    if [ "$2" = running ]; then
      [ "$s" = running ] && return 0
    else
      [ "$s" != running ] && [ "$s" != restarting ] && return 0
    fi
    sleep 0.1; i=$((i+1))
  done
  return 1
}

# Brings Aqara's stack to $WANT. Consumers are stopped first: the socat/zigbeed/Z3Gateway wrappers
# run `setprop sys.gateway.config 11` (= restart the whole stack) when their binary exits by itself.
enforce() {
  bad=""
  for s in $STOP_ORDER; do wanted $s || svc $s stopped || bad="$bad $s"; done
  for s in $START_ORDER; do if wanted $s; then svc $s running || bad="$bad $s"; fi; done
  [ -z "$bad" ]
}

wait_for() { # path seconds
  i=0; while [ ! -e "$1" ] && [ $i -lt $(($2*10)) ]; do sleep 0.1; i=$((i+1)); done; [ -e "$1" ]
}

# --- network helpers ---------------------------------------------------------------------------
# The panel's wlan0 IPv4 subnet as a.b.c.d/bits (network address), or nothing when offline.
lan_cidr() {
  a=$(ip -4 -o addr show dev wlan0 2>/dev/null); a=${a#*inet }; a=${a%% *}
  case "$a" in */*) ;; *) return 1 ;; esac
  ip4=${a%/*}; bits=${a#*/}
  o=$IFS; IFS=.; set -- $ip4; IFS=$o
  [ $# -eq 4 ] || return 1
  n=$(( ($1<<24) | ($2<<16) | ($3<<8) | $4 ))
  if [ "$bits" -eq 0 ]; then m=0; else m=$(( (0xffffffff << (32-bits)) & 0xffffffff )); fi
  n=$(( n & m ))
  echo "$(( (n>>24)&255 )).$(( (n>>16)&255 )).$(( (n>>8)&255 )).$(( n&255 ))/$bits"
}

# socat range for the coordinator bridge: "" = anyone, "lan" = current wlan0 subnet, else as given.
bridge_range() {
  case "$ZIGBEE_RANGE" in
    "") echo "" ;;
    lan) lan_cidr || echo "127.0.0.1/32" ;;  # offline: nobody but us until the LAN is back
    *) echo "$ZIGBEE_RANGE" ;;
  esac
}

# Thread border routing on Android: forwarding on, and two policy rules. Android's kernel puts each
# interface's routes in its own table (accept_ra_rt_table=-1000: ifindex + 1000), and netd's rules
# only consult those tables for locally generated traffic, so packets arriving on wlan0 for Thread
# (and on wpan0 for the LAN) would hit "unreachable". wpan0 is recreated with a new ifindex whenever
# the agent restarts, so the inbound rule follows its current table. Removed in every other mode.
FWD_SAVE=$DIR/forwarding.orig        # forwarding before thread mode turned it on
BR_TABLE=""; BR_OUT_TABLE=""
br_net_on() {
  [ -f "$FWD_SAVE" ] || cat /proc/sys/net/ipv6/conf/all/forwarding > "$FWD_SAVE" 2>/dev/null
  echo 1 > /proc/sys/net/ipv6/conf/all/forwarding
  # forwarding=1 disables RA processing unless accept_ra=2 (Android sets 2 on wlan0; keep it so)
  echo 2 > /proc/sys/net/ipv6/conf/$BACKBONE/accept_ra 2>/dev/null
  t=$(ip -6 route show table all dev wpan0 2>/dev/null | sed -n 's/.* table \([0-9A-Za-z_]*\) .*/\1/p' | head -1)
  if [ -n "$t" ] && { [ "$t" != "$BR_TABLE" ] || ! ip -6 rule show | grep -q "^$RULE_PREF_IN:"; }; then
    while ip -6 rule del pref $RULE_PREF_IN 2>/dev/null; do :; done
    ip -6 rule add pref $RULE_PREF_IN iif "$BACKBONE" lookup "$t" && BR_TABLE=$t
  fi
  # Thread -> LAN: the backbone's table by number (netd: ifindex + 1000). "lookup wlan0" would be
  # resolved once, when the rule is added, and Wi-Fi off/on recreates wlan0 with a new ifindex, so
  # the rule would keep pointing at the old, empty table (verified 2026-11-10: replies from Thread
  # dropped after a reconnect). Follow the current index like the inbound rule follows wpan0's.
  i_=$(cat /sys/class/net/"$BACKBONE"/ifindex 2>/dev/null)
  if [ -n "$i_" ]; then
    o_=$((i_ + 1000))
    if [ "$o_" != "$BR_OUT_TABLE" ] || ! ip -6 rule show | grep -q "^$RULE_PREF_OUT:"; then
      while ip -6 rule del pref $RULE_PREF_OUT 2>/dev/null; do :; done
      ip -6 rule add pref $RULE_PREF_OUT iif wpan0 lookup "$o_" && BR_OUT_TABLE=$o_
    fi
  fi
}
br_net_off() {
  while ip -6 rule del pref $RULE_PREF_IN 2>/dev/null; do :; done
  while ip -6 rule del pref $RULE_PREF_OUT 2>/dev/null; do :; done
  if [ -f "$FWD_SAVE" ]; then cat "$FWD_SAVE" > /proc/sys/net/ipv6/conf/all/forwarding; rm -f "$FWD_SAVE"; fi
  BR_TABLE=""; BR_OUT_TABLE=""
}

# --- firewall: who may reach the radio's network services --------------------------------------
# Android 10 here: legacy iptables/ip6tables 1.6.1 in /system/bin (no multiport match, so one jump
# per port; iptables-save needs no lock). netd owns INPUT's other jumps (bw_INPUT, fw_INPUT) and may
# flush INPUT when it restarts, so the rules are checked every loop and put back. The chain's
# contents are replaced atomically (iptables-restore --noflush with ":$FW_CHAIN -" flushes just that
# chain in the same commit), so a change of address never opens the ports in between.
FW_STATE=unknown   # what is in place: unknown | clear | any | "<list>|<ports>"
FW_EFF=""          # addresses actually allowed (normalized list, "any", or "" = loopback only)
FW_SRC=app         # where the list came from: app (persist prop) | conf (RADIO_ALLOW)
FW_BAD=""          # tokens that were ignored (not an address) or rejected by iptables

ipt() { f_=$1; shift; if [ "$f_" = 6 ]; then ip6tables -w 5 "$@"; else iptables -w 5 "$@"; fi; }
ipt_save() { if [ "$1" = 6 ]; then ip6tables-save -t filter; else iptables-save -t filter; fi; }
ipt_restore() { if [ "$1" = 6 ]; then ip6tables-restore -w 5 --noflush; else iptables-restore -w 5 --noflush; fi; }

# Wanted list -> FW_SRC, FW_LIST ("any" | "" | "a b ..."), FW_BAD. Tokens may be separated by spaces
# or commas; "none" adds nothing; anything that isn't made of address characters is ignored.
fw_wanted() {
  if [ -n "$RADIO_ALLOW" ]; then FW_SRC=conf; v_=$RADIO_ALLOW
  else FW_SRC=app; v_=$(getprop $PROP_ALLOW); fi
  FW_LIST=""; FW_BAD=""
  set -f  # no globbing of tokens such as '*'
  for t_ in $(echo "$v_" | tr ',' ' '); do
    case "$t_" in
      any|ANY) FW_LIST=any; break ;;
      none|NONE) ;;
      *:*) case "$t_" in *[!0-9a-fA-F:./]*) FW_BAD="$FW_BAD $t_" ;; *) FW_LIST="$FW_LIST $t_" ;; esac ;;
      *.*) case "$t_" in *[!0-9./]*) FW_BAD="$FW_BAD $t_" ;; *) FW_LIST="$FW_LIST $t_" ;; esac ;;
      *) FW_BAD="$FW_BAD $t_" ;;
    esac
  done
  set +f
  FW_LIST=${FW_LIST# }; FW_BAD=${FW_BAD# }
}

fw_family_count() { # 4|6 list -> number of addresses of that family
  n_=0
  for t_ in $2; do
    case "$t_" in *:*) [ "$1" = 6 ] && n_=$((n_+1)) ;; *) [ "$1" = 4 ] && n_=$((n_+1)) ;; esac
  done
  echo $n_
}

fw_jumps() { for p_ in $ZIGBEE_PORT $REST_PORT; do echo "-A INPUT -p tcp -m tcp --dport $p_ -j $FW_CHAIN"; done; }

# The rules for family $1 are in place for list $2: the chain has lo + one RETURN per address + DROP,
# and INPUT has exactly our jumps (iptables-save prints them in rule order).
fw_ok() {
  s_=$(ipt_save $1) || return 1
  n_=$(printf '%s\n' "$s_" | grep -c "^-A $FW_CHAIN ")
  [ "$n_" = $(( $(fw_family_count $1 "$2") + 2 )) ] || return 1
  printf '%s\n' "$s_" | grep -q "^-A $FW_CHAIN -j DROP\$" || return 1
  [ "$(printf '%s\n' "$s_" | grep "^-A INPUT .*-j $FW_CHAIN\$")" = "$(fw_jumps)" ]
}

fw_del_jumps() { # family: removes every INPUT jump to the chain (also stale ports, duplicates)
  ipt_save $1 | sed -n "s/^-A INPUT \(.*-j $FW_CHAIN\)\$/-D INPUT \1/p" | while read -r l_; do
    # shellcheck disable=SC2086
    ipt $1 $l_
  done
}

fw_set() { # family list: chain contents (atomic), then the jumps at the top of INPUT
  {
    echo "*filter"
    echo ":$FW_CHAIN - [0:0]"
    echo "-A $FW_CHAIN -i lo -j RETURN"
    for t_ in $2; do
      case "$t_" in *:*) [ "$1" = 6 ] ;; *) [ "$1" = 4 ] ;; esac && echo "-A $FW_CHAIN -s $t_ -j RETURN"
    done
    echo "-A $FW_CHAIN -j DROP"
    echo "COMMIT"
  } | ipt_restore $1 || return 1
  if [ "$(ipt_save $1 | grep "^-A INPUT .*-j $FW_CHAIN\$")" != "$(fw_jumps)" ]; then
    fw_del_jumps $1
    for p_ in $REST_PORT $ZIGBEE_PORT; do ipt $1 -I INPUT 1 -p tcp --dport $p_ -j $FW_CHAIN || return 1; done
  fi
}

fw_clear() { # removes everything of ours (off, "any")
  for f_ in 4 6; do
    fw_del_jumps $f_
    ipt $f_ -F $FW_CHAIN 2>/dev/null; ipt $f_ -X $FW_CHAIN 2>/dev/null
  done
  return 0
}

# Every loop in every mode but off. Returns 1 (status set) when the rules could not be put in place.
fw_apply() {
  fw_wanted
  [ -n "$FW_BAD" ] && [ "$FW_BAD" != "$FW_BAD_SAID" ] && { say "firewall: ignoring '$FW_BAD' (not an address)"; FW_BAD_SAID=$FW_BAD; }
  if [ "$FW_LIST" = any ]; then
    [ "$FW_STATE" = any ] || { fw_clear; FW_STATE=any; FW_EFF=any; say "firewall: off (allow any), $FW_SRC"; }
  else
    sig_="$FW_LIST|$ZIGBEE_PORT $REST_PORT"
    if [ "$FW_STATE" != "$sig_" ] || ! fw_ok 4 "$FW_EFF" || ! fw_ok 6 "$FW_EFF"; then
      FW_EFF=$FW_LIST
      if ! fw_set 4 "$FW_EFF" || ! fw_set 6 "$FW_EFF"; then
        # iptables refused an entry (e.g. a bad prefix length): loopback only rather than open
        say "firewall: iptables rejected '$FW_EFF', allowing loopback only"
        FW_BAD="${FW_BAD:+$FW_BAD }$FW_EFF"; FW_EFF=""
        fw_set 4 "" && fw_set 6 ""
      fi
      if ! fw_ok 4 "$FW_EFF" || ! fw_ok 6 "$FW_EFF"; then
        FW_STATE=unknown; status error "firewall rules for tcp:$ZIGBEE_PORT/$REST_PORT could not be set"; return 1
      fi
      [ "$FW_STATE" = "$sig_" ] || say "firewall: tcp:$ZIGBEE_PORT/$REST_PORT from ${FW_EFF:-loopback only} ($FW_SRC)"
      FW_STATE=$sig_
    fi
  fi
  a_=$(allowed_value)
  [ "$a_" = "$FW_ALLOWED_SHOWN" ] || { setprop $PROP_ALLOWED "$a_"; FW_ALLOWED_SHOWN=$a_; }
  return 0
}

# For $PROP_ALLOWED: "<src> <any|none|list>", at most 91 bytes (PROP_VALUE_MAX); a list that doesn't
# fit (long RADIO_ALLOW) ends with "+<n>" for the addresses left out.
allowed_value() {
  out_="$FW_SRC"; n_=0
  [ -n "$FW_EFF" ] || { echo "$out_ none"; return; }
  for t_ in $FW_EFF; do
    if [ $n_ -gt 0 ] || [ $(( ${#out_} + ${#t_} + 1 + 4 )) -gt 91 ]; then n_=$((n_+1)); else out_="$out_ $t_"; fi
  done
  [ $n_ -gt 0 ] && out_="$out_ +$n_"
  echo "$out_"
}

fw_off() {
  [ "$FW_STATE" = clear ] && return 0
  fw_clear; FW_STATE=clear; FW_EFF=""
  setprop $PROP_ALLOWED ""; FW_ALLOWED_SHOWN=""
}

# --- who is connected now (#217) ---------------------------------------------------------------
# $PROP_PEER: the EZSP bridge's client in the coordinator modes, from the kernel's socket tables (Android
# 10 doesn't let the app read them): "none" | "<address> <epoch seconds first seen>"; "" in every other
# mode. One peer at most (the bridge serves one client); the one shown stays while it is connected. The
# address is for the app and Home Assistant only, never for the log.
PROP_PEER=sys.glassworks.radio.peer
PROC_NET=${GWA_PROC_NET:-/proc/net}
PEER_KEY=""; PEER_VALUE=""; PEER_SHOWN=unset

hex_ip4() { # 8 hex digits as /proc/net/tcp prints them (little-endian, as this panel is) -> dotted
  h4_=$((0x$1)); echo "$((h4_ & 255)).$(((h4_ >> 8) & 255)).$(((h4_ >> 16) & 255)).$(((h4_ >> 24) & 255))"
}
hex_ip6() { # 32 hex digits, four little-endian words -> 8 groups without leading zeros; IPv4-mapped -> dotted
  case "$1" in 0000000000000000[Ff][Ff][Ff][Ff]0000*) hex_ip4 "${1#????????????????????????}"; return ;; esac
  o6_=""; r6_=$1
  while [ -n "$r6_" ]; do
    w6_=${r6_%"${r6_#????????}"}; r6_=${r6_#????????}; w6_=$((0x$w6_))
    o6_="$o6_:$(printf '%x:%x' $(( ((w6_ & 255) << 8) | ((w6_ >> 8) & 255) )) $(( (((w6_ >> 16) & 255) << 8) | ((w6_ >> 24) & 255) )))"
  done
  echo "${o6_#:}"
}
peer_find() { # port -> the remote "<hex address>:<hex port>" of each ESTABLISHED (01) socket on that local port
  pp_=$(printf '%04X' "$1")
  for f_ in "$PROC_NET/tcp" "$PROC_NET/tcp6"; do
    [ -r "$f_" ] || continue
    while read -r sl_ loc_ rem_ st_ rest_; do
      [ "$st_" = 01 ] && [ "${loc_##*:}" = "$pp_" ] && echo "$rem_"
    done < "$f_"
  done
}
peer_show() { [ "$1" = "$PEER_SHOWN" ] && return 0; PEER_SHOWN=$1; setprop $PROP_PEER "$1"; }
peer_report() { # every loop
  case "$MODE" in zigbee_tcp|zigbee_tcp_thread) ;; *) PEER_KEY=""; peer_show ""; return 0 ;; esac
  k_=""; first_=""
  for c_ in $(peer_find "$ZIGBEE_PORT"); do
    [ -n "$first_" ] || first_=$c_
    [ "$c_" = "$PEER_KEY" ] && k_=$c_
  done
  [ -n "$k_" ] || k_=$first_
  if [ -z "$k_" ]; then
    [ -n "$PEER_KEY" ] && say "EZSP client disconnected"
    PEER_KEY=""; peer_show none; return 0
  fi
  if [ "$k_" != "$PEER_KEY" ]; then
    PEER_KEY=$k_; a_=${k_%:*}
    if [ ${#a_} = 32 ]; then a_=$(hex_ip6 "$a_"); else a_=$(hex_ip4 "$a_"); fi
    PEER_VALUE="$a_ $(date +%s)"
    say "EZSP client connected"
  fi
  peer_show "$PEER_VALUE"
}

# --- the setup hotspot's captive portal (#197) -------------------------------------------------
# A phone that joins the panel's setup hotspot should open the setup page by itself, as on hotel Wi-Fi.
# Phones show that page when their connectivity check (iOS: http://captive.apple.com/hotspot-detect.html,
# Android: http://connectivitycheck.gstatic.com/generate_204) gets a redirect. Android 10's local-only
# hotspot hands out the AP's address as DNS (DHCP, and RDNSS in the RA), served by netd's dnsmasq with no
# upstream: nothing resolves, so no phone ever sees a portal (docs/research/setup-hotspot-portal-and-name.md).
# The app answers instead, but it can't bind ports below 1024. While the hotspot is up it runs a DNS
# responder on udp:$PORTAL_DNS (every name -> the AP) and a redirect to its setup page on tcp:$PORTAL_HTTP,
# bound to the AP's own addresses only. This loop sends the hotspot's udp:53 and tcp:80 to them (nat
# PREROUTING, chain $PORTAL_CHAIN).
# The rules exist only while all of these hold, checked every $PORTAL_EVERY s, and go within that time
# once one stops: hostapd runs (Android 10 runs it only for an AP), the kiosk's uid owns a udp socket on
# :$PORTAL_DNS and a listening tcp socket on :$PORTAL_HTTP bound to one IPv4 address, and that address is on a
# Wi-Fi interface. They match only packets that come in on that interface, from its subnet, addressed
# to the panel's own address there, so nothing on any other network can match them. No input but the
# kernel's socket tables; another app can at most point the hotspot's DNS at the kiosk's own sockets.
# IPv6: when the kiosk also has a udp socket on :$PORTAL_DNS at one IPv6 address, udp:53 to that address
# goes there too (the RA's RDNSS). Status: sys.glassworks.portal.status = "off" | "on <iface>" | "error: ...".
# The loop runs in every radio mode (also after "giving up") and starts with the agent, which first
# removes whatever an earlier run left.
PORTAL_DNS=8053
PORTAL_HTTP=8098
PORTAL_CHAIN=GWPORTAL
PORTAL_PROP=sys.glassworks.portal.status
PORTAL_EVERY=${GWA_PORTAL_SLEEP:-2}
PORTAL_STATE=""; PORTAL_SHOWN=""; PORTAL_PID=""

# The kiosk's sockets on one address: family proto port [state] -> each address (4: dotted, 6: hex).
# Android's Java sockets are dual-stack, so a bind to an IPv4 address shows in udp6/tcp6 as IPv4-mapped.
portal_addrs() {
  pp_=$(printf '%04X' "$3")
  for f_ in "$PROC_NET/$2" "$PROC_NET/${2}6"; do
    [ -r "$f_" ] || continue
    while read -r sl_ loc_ rem_ st_ q_ t_ r_ uid_ rest_; do
      [ "$uid_" = "$APP_UID" ] && [ "${loc_##*:}" = "$pp_" ] || continue
      [ -z "$4" ] || [ "$st_" = "$4" ] || continue
      a_=${loc_%:*}
      # 4: a wildcard bind comes out as 0.0.0.0, which no interface has, so portal_wanted drops it
      case "$1:${#a_}:$a_" in
        4:8:*) hex_ip4 "$a_" ;;
        4:32:0000000000000000[Ff][Ff][Ff][Ff]0000*) hex_ip4 "${a_#????????????????????????}" ;;
        6:32:0000000000000000[Ff][Ff][Ff][Ff]0000*|6:32:[Ff][Ee]80*) ;;
        6:32:*[!0]*) echo "$a_" ;;
      esac
    done < "$f_"
  done
}

# What the rules should be now: "<iface> <v4 address> <v4 prefix> [<v6 address>]", or nothing.
portal_wanted() {
  [ -n "$(pidof hostapd 2>/dev/null)" ] || return 0
  [ -n "$APP_UID" ] || APP_UID=$(stat -c %u "$APP_DATA" 2>/dev/null)
  case "$APP_UID" in ''|*[!0-9]*) return 0 ;; esac
  [ "$APP_UID" -ge 10000 ] || return 0
  set -- $(portal_addrs 4 udp $PORTAL_DNS); [ $# = 1 ] || return 0; a4_=$1
  set -- $(portal_addrs 4 tcp $PORTAL_HTTP 0A); [ "$*" = "$a4_" ] || return 0
  # "23: wlan0    inet 192.168.43.1/24 brd ... scope global wlan0"
  set -- $(ip -4 -o addr show 2>/dev/null | while read -r n_ i_ f_ c_ rest_; do
    [ "$f_" = inet ] && [ "${c_%/*}" = "$a4_" ] && echo "$i_ ${c_#*/}"
  done)
  case "$1" in wlan[0-9]*|ap[0-9]*|swlan[0-9]*|softap[0-9]*) ;; *) return 0 ;; esac   # also: no interface has it
  case "$2" in ''|*[!0-9]*) return 0 ;; esac
  [ "$2" -ge 16 ] && [ "$2" -le 30 ] || return 0
  w_="$1 $a4_ $2"
  set -- $(portal_addrs 6 udp $PORTAL_DNS)
  [ $# = 1 ] && w_="$w_ $(hex_ip6 "$1")"
  echo "$w_"
}

# the rules' lines in iptables-save's order: family iface v4 prefix [v6]
ip6_canon() { # an IPv6 address, short (2001:db8::1) or full -> 8 groups, lower case, no leading zeros (as hex_ip6)
  c_=$(printf '%s' "${1%%/*}" | tr 'A-F' 'a-f')
  case "$c_" in *::*) l6_=${c_%%::*}; r6_=${c_#*::} ;; *) l6_=$c_; r6_=- ;; esac
  o6_=""; n6_=0; ro6_=""
  for g_ in $(printf '%s' "$l6_" | tr ':' ' '); do o6_="$o6_:$(printf '%x' $((16#$g_)))"; n6_=$((n6_ + 1)); done
  if [ "$r6_" != - ]; then
    for g_ in $(printf '%s' "$r6_" | tr ':' ' '); do ro6_="$ro6_:$(printf '%x' $((16#$g_)))"; n6_=$((n6_ + 1)); done
    while [ "$n6_" -lt 8 ]; do o6_="$o6_:0"; n6_=$((n6_ + 1)); done
  fi
  echo "${o6_#:}$ro6_"
}
portal_rules() {
  if [ "$1" = 4 ]; then
    echo "-A $PORTAL_CHAIN -s $3/$4 -d $3/32 -i $2 -p udp -m udp --dport 53 -j DNAT --to-destination $3:$PORTAL_DNS"
    echo "-A $PORTAL_CHAIN -s $3/$4 -d $3/32 -i $2 -p tcp -m tcp --dport 80 -j DNAT --to-destination $3:$PORTAL_HTTP"
  elif [ -n "$5" ]; then
    echo "-A $PORTAL_CHAIN -d $5/128 -i $2 -p udp -m udp --dport 53 -j DNAT --to-destination [$5]:$PORTAL_DNS"
  fi
}
ip4_net() { # a.b.c.d prefix -> the network address (192.168.43.1 24 -> 192.168.43.0), as iptables-save prints -s
  in_=$2; ir_=""
  for io_ in $(printf '%s' "$1" | tr '.' ' '); do
    if [ "$in_" -ge 8 ]; then im_=255; in_=$((in_ - 8)); elif [ "$in_" -gt 0 ]; then im_=$(( (255 << (8 - in_)) & 255 )); in_=0; else im_=0; fi
    ir_="$ir_.$((io_ & im_))"
  done
  echo "${ir_#.}"
}
portal_save() { if [ "$1" = 6 ]; then ip6tables-save -t nat; else iptables-save -t nat; fi; }

# The rules for family $1 are in place for "$2": as many DNAT rules as wanted, each to the right address
# and port on the right interface, and exactly one jump.
portal_ok() {
  s_=$(portal_save $1) || return 1
  set -- $1 $2
  n_=$(portal_rules "$@" | grep -c .)
  [ "$(printf '%s\n' "$s_" | grep -c "^-A $PORTAL_CHAIN ")" = "$n_" ] || return 1
  if [ "$1" = 4 ]; then
    # the source match too: the hotspot's subnet now, as written ($3/$4) or as iptables-save prints it (the
    # network, 192.168.43.0/24), so a rule for another subnet or prefix is rebuilt
    net_=$(ip4_net "$3" "$4")
    for p_ in $PORTAL_DNS $PORTAL_HTTP; do
      printf '%s\n' "$s_" | grep -qE "^-A $PORTAL_CHAIN -s ($3|$net_)/$4 -d $3/32 -i $2 .*--to-destination $3:$p_\$" || return 1
    done
  elif [ -n "$5" ]; then
    # ip6tables-save shortens the address (2001:db8::20): the -d match and the target are compared with the
    # kiosk's socket address in one form, so a rule for an address the hotspot no longer has is rebuilt
    l_=$(printf '%s\n' "$s_" | grep "^-A $PORTAL_CHAIN .*-i $2 .*--to-destination \[[0-9a-fA-F:]*\]:$PORTAL_DNS\$") || return 1
    d_=$(printf '%s\n' "$l_" | sed -n 's/.* -d \([0-9a-fA-F:]*\)\/128 .*/\1/p')
    t_=$(printf '%s\n' "$l_" | sed -n 's/.*--to-destination \[\([0-9a-fA-F:]*\)\]:.*/\1/p')
    w6_=$(ip6_canon "$5")
    [ -n "$d_" ] && [ "$(ip6_canon "$d_")" = "$w6_" ] && [ "$(ip6_canon "$t_")" = "$w6_" ] || return 1
  fi
  [ "$(printf '%s\n' "$s_" | grep -c "^-A PREROUTING .*-j $PORTAL_CHAIN\$")" = 1 ]
}

portal_clear() { # removes everything of ours, both families (one read each when there is nothing). 1 when a
  # table couldn't be read, or our chain is still there afterwards: the caller tries again on its next check
  r_=0
  for f_ in 4 6; do
    s_=$(portal_save $f_ 2>/dev/null) || { r_=1; continue; }
    case "$s_" in *"$PORTAL_CHAIN"*) ;; *) continue ;; esac
    printf '%s\n' "$s_" | grep "^-A PREROUTING .*-j $PORTAL_CHAIN\$" | while read -r l_; do
      # shellcheck disable=SC2086
      ipt $f_ -t nat -D ${l_#-A } 2>/dev/null
    done
    ipt $f_ -t nat -F $PORTAL_CHAIN 2>/dev/null; ipt $f_ -t nat -X $PORTAL_CHAIN 2>/dev/null
    s_=$(portal_save $f_ 2>/dev/null) || { r_=1; continue; }
    case "$s_" in *"$PORTAL_CHAIN"*) r_=1 ;; esac
  done
  return $r_
}

portal_set() { # family "<iface> <v4> <prefix> [<v6>]": the chain's contents (atomic), then one jump
  f_=$1; set -- $1 $2
  {
    echo "*nat"
    echo ":$PORTAL_CHAIN - [0:0]"
    portal_rules "$@"
    echo "COMMIT"
  } | if [ "$f_" = 6 ]; then ip6tables-restore -w 5 --noflush; else iptables-restore -w 5 --noflush; fi || return 1
  [ "$(portal_save $f_ | grep -c "^-A PREROUTING .*-j $PORTAL_CHAIN\$")" = 1 ] && return 0
  portal_save $f_ | grep "^-A PREROUTING .*-j $PORTAL_CHAIN\$" | while read -r l_; do
    # shellcheck disable=SC2086
    ipt $f_ -t nat -D ${l_#-A }
  done
  ipt $f_ -t nat -I PREROUTING 1 -j $PORTAL_CHAIN
}

portal_say() { [ "$1" = "$PORTAL_SHOWN" ] && return 0; PORTAL_SHOWN=$1; setp $PORTAL_PROP "$1"; say "setup hotspot portal: $1"; }
portal_check() {
  w_=$(portal_wanted)
  if [ -z "$w_" ]; then
    [ "$PORTAL_STATE" = off ] && return 0
    # "off" only once both tables are seen clear; until then every check tries again
    if portal_clear; then PORTAL_STATE=off; portal_say off; else portal_say "error: the rules could not be removed yet"; fi
    return 0
  fi
  if portal_ok 4 "$w_" && portal_ok 6 "$w_"; then
    # in place (also a new agent finding its predecessor's): kept as they are, no gap for the phones
    PORTAL_STATE=$w_; portal_say "on ${w_%% *}"
  else
    portal_clear
    if portal_set 4 "$w_" && portal_set 6 "$w_" && portal_ok 4 "$w_" && portal_ok 6 "$w_"; then
      PORTAL_STATE=$w_; portal_say "on ${w_%% *}"
    else
      # "off" only once the clear went through: else the next check, wanted or not, clears again
      if portal_clear; then PORTAL_STATE=off; else PORTAL_STATE=""; fi
      portal_say "error: iptables refused the rules"
    fi
  fi
}
portal_start() { # one check now (it also removes an earlier run's rules), then the loop
  portal_check
  parent_=$$
  ( while :; do
      sleep "$PORTAL_EVERY"
      if ! kill -0 "$parent_" 2>/dev/null; then
        # The agent is gone (init restarts it, and its first check clears too). Take the rules away now, and
        # if a table can't be read, try again for a minute: cleanup only, never portal_check, which could put
        # rules back. Bounded, so a loop that outlived its agent doesn't stay.
        i_=0; until portal_clear || [ $i_ -ge 30 ]; do sleep "$PORTAL_EVERY"; i_=$((i_ + 1)); done
        exit 0
      fi
      portal_check
    done ) &
  PORTAL_PID=$!
}
portal_stop() { alive "$PORTAL_PID" && kill "$PORTAL_PID"; PORTAL_PID=""; }

# --- the setup hotspot's name (#197) ------------------------------------------------------------
# Android 10 names its local-only hotspot "<framework string>_<1000..9999>", the string being "AndroidShare"
# (WifiApConfigStore.generateLocalOnlyHotspotConfig), and gives apps no way to change it. A resource overlay of that
# string makes it "Glassworks_NNNN". Android 10 applies an overlay of framework-res only when it is preinstalled
# (framework-res declares no overlayable, and the S1's is signed with Aqara's key: docs/research/
# setup-hotspot-portal-and-name.md), so it goes on /product through a Magisk module, which Magisk mounts at boot:
#   $HN_MOD/module.prop
#   $HN_MOD/$HN_PATH      the overlay, hotspot-name.apk from the signed frame bundle (tools/radio/hotspot-name/),
#                         or with dev-override a root-made $DIR/hotspot-name.apk
# Once per agent start: with the overlay in the running bundle, the module is put in place (or replaced when the
# bundle's overlay differs); without it, or with persist.glassworks.hotspot_name=android (the undo; any app may set it,
# which can only give Android's name back), the module is removed. Either takes effect at the next restart.
# Nothing else is touched: no file of Android's, no setting. Status: sys.glassworks.hotspot_name.status =
#   glassworks | android | "glassworks after a restart" | "android after a restart"
#   | "android: the module is disabled in Magisk" | "android: Magisk loads no modules here (no .../busybox)"
#   | "error: ..."
HN_APK=hotspot-name.apk
HN_PKG=sh.edm.s1kiosk.overlay.hotspotname
HN_MODS=${GWA_MODULES:-/data/adb/modules}
# Magisk mounts modules only once its own setup is in /data/adb/magisk: without busybox there, post-fs-data stops
# with "Magisk environment incomplete, abort" before any module (Magisk v31.1 native/src/core/bootstages.rs:76-79,
# :132-134). The Magisk app puts it there; our boot image does since #615 (glassworks.rc in the loader image). A panel installed
# before that still lacks it until its next install with Kiln.
HN_MAGISK_BB=${GWA_MAGISK_BB:-/data/adb/magisk/busybox}
# Staged in the agent's own root-only dir, never in $HN_MODS: Magisk loads every directory there at boot, dot-names
# included, so a stage a stopped agent left would load. Same /data filesystem, so the final mv is a rename.
HN_STAGE=$DIR/hotspot-name-module.new
HN_NEWAPK=$DIR/hotspot-name.apk.new   # a new overlay for an installed module, renamed into it when complete
# The module id and the overlay's package keep their s1kiosk names (#528): a 0.4.3 helper made this module, and a
# second id would leave two modules, one the undo can't see.
HN_MOD=$HN_MODS/s1kiosk_hotspot_name
HN_PATH=system/product/overlay/S1HotspotName/S1HotspotName.apk
HN_PROP=persist.glassworks.hotspot_name
HN_STATUS=sys.glassworks.hotspot_name.status
# Where Magisk mounts the overlay while it is on: AssetManager loads framework overlays from the APKs it finds there
HN_LIVE=${GWA_HN_LIVE:-/product/${HN_PATH#system/product/}}

hn_say() { setp $HN_STATUS "$1"; say "hotspot name: $1"; }
hn_apply() {
  # where binaries come from (bin): a developer's copy with dev-override, else the signed frame bundle
  # what an interrupted run left (a stage, a new overlay not yet renamed in): gone before anything is staged again,
  # so nothing of an abandoned run (a disable mark in a stage) can end up in the module
  rm -rf "$HN_STAGE" "$HN_NEWAPK"
  if [ -e "$HN_STAGE" ] || [ -e "$HN_NEWAPK" ]; then hn_say "error: could not clear an earlier run's stage"; return 0; fi
  src_=""
  if [ -n "$DEV" ] && [ -f "$DIR/$HN_APK" ]; then src_=$DIR/$HN_APK
  elif [ -n "$RBUNDLE" ] && [ -f "$RBUNDLE/$HN_APK" ]; then src_=$RBUNDLE/$HN_APK; fi
  if [ "$(getprop $HN_PROP)" = android ] || [ -z "$src_" ]; then
    want_=android
    if [ -e "$HN_MOD" ]; then
      # a /data that refuses (read-only, full) is said as it is: no "after a restart" for a module still there
      if ! rm -rf "$HN_MOD" || [ -e "$HN_MOD" ]; then hn_say "error: could not remove the module"; return 0; fi
      say "hotspot name: module removed (Android's name from the next restart)"
    fi
  else
    want_=glassworks
    if ! cmp -s "$src_" "$HN_MOD/$HN_PATH"; then
      [ -d "$HN_MODS" ] || { hn_say "error: no Magisk modules dir"; return 0; }
      # Every step checked: a file with the wrong owner or modes never goes in (later starts compare only the bytes,
      # so they wouldn't fix it).
      if [ -d "$HN_MOD/${HN_PATH%/*}" ]; then
        # An installed module: only its APK changes, by a rename (same /data). Its dir, module.prop and the operator's
        # disable switch in Magisk stay where they are, and Magisk finds the old APK or the new one, whole.
        a_=$HN_NEWAPK
        if ! { cp "$src_" "$a_" && chown root:root "$a_" && chmod 644 "$a_"; }; then
          rm -f "$a_"; hn_say "error: could not write the module"; return 0
        fi
        chcon u:object_r:system_file:s0 "$a_" 2>/dev/null
        if ! mv "$a_" "$HN_MOD/$HN_PATH"; then rm -f "$a_"; hn_say "error: could not put the module in place"; return 0; fi
        say "hotspot name: new overlay in the module (from the next restart)"
      else
        # A first install, or a module without its APK's dir: the whole module, staged under $DIR and renamed in.
        t_=$HN_STAGE
        if mkdir -p "$t_/${HN_PATH%/*}" && cp "$src_" "$t_/$HN_PATH" &&
           printf '%s\n' "id=s1kiosk_hotspot_name" "name=Glassworks setup hotspot name" "version=1" "versionCode=1" \
             "author=Glassworks" "description=Names the S1's setup hotspot Glassworks_NNNN (installed by the frame agent)" \
             > "$t_/module.prop" &&
           { [ ! -e "$HN_MOD/disable" ] || : > "$t_/disable"; } &&   # the operator's off switch in Magisk stays
           chown -R root:root "$t_" && find "$t_" -type d -exec chmod 755 {} + && find "$t_" -type f -exec chmod 644 {} +; then
          chcon -R u:object_r:system_file:s0 "$t_" 2>/dev/null
          # a module without its APK has nothing to keep: it goes, so the stage doesn't land inside it
          if { [ -e "$HN_MOD" ] && ! rm -rf "$HN_MOD"; } || [ -e "$HN_MOD" ] || ! mv "$t_" "$HN_MOD"; then
            rm -rf "$t_"; hn_say "error: could not put the module in place"; return 0
          fi
          say "hotspot name: module in place (Glassworks from the next restart)"
        else
          rm -rf "$t_"; hn_say "error: could not write the module"; return 0
        fi
      fi
    fi
  fi
  # Switched off in Magisk (its "disable" file): Magisk won't mount it at the next boot, so Android's name from then
  # on; a module still mounted this boot keeps Glassworks until that restart
  off_=""; if [ "$want_" = glassworks ] && [ -e "$HN_MOD/disable" ]; then want_=android; off_=1; fi
  # Magisk without its setup mounts no module at all: the module waits in place, and the status says why
  noenv_=""; if [ "$want_" = glassworks ] && [ ! -f "$HN_MAGISK_BB" ]; then want_=android; noenv_=1; fi
  # Bounded, and a query that fails is said as such: a framework that doesn't answer isn't "android", and mustn't
  # hold up the radio work after this
  if ! o_=$(timeout ${GWA_HN_TIMEOUT:-20} cmd overlay list $HN_PKG 2>/dev/null); then
    hn_say "error: Android didn't say whether the overlay is on"; return 0
  fi
  # Android 10 never forgets a static overlay of framework-res whose APK went away: its boot-time cleanup skips
  # those (OverlayManagerSettings.getOverlaysForUser), so after the undo `cmd overlay list` keeps saying [x] for good.
  # The framework loads them from the APKs present at boot, so it's live only with the APK mounted too
  # (verified on Android 10, 2026-11-19: docs/research/setup-hotspot-portal-and-name.md).
  case "$o_" in *"[x] $HN_PKG"*) if [ -f "$HN_LIVE" ]; then live_=glassworks; else live_=android; fi ;; *) live_=android ;; esac
  if [ "$want_" != "$live_" ]; then hn_say "$want_ after a restart"
  elif [ -n "$off_" ]; then hn_say "android: the module is disabled in Magisk"
  elif [ -n "$noenv_" ]; then hn_say "android: Magisk loads no modules here (no $HN_MAGISK_BB)"
  else hn_say "$want_"; fi
}
# --- adbd checks keys (#981) -----------------------------------------------------------------------
# Aqara's adbd lets every computer in. adbd_main reads ro.adb.secure into auth_required (0x20c2: strb r0,[r4]), then
# stores false over it (0x20ce: movs r0,#0; 0x20d0: strb r0,[r4]); handle_packet in libadbd.so only reads the flag (read
# from 4.5.80's /system/bin/adbd, 2026-11-28: frames/aqara-s1-plus/research/adb-keys.md). A Magisk module mounts a copy
# where that second store is a nop (file offset 0x20d0: 20 70 -> 00 bf), so auth_required is ro.adb.secure again (1) and
# adbd checks adb_keys. The copy is made here from the panel's own adbd and checked by hash before and after: no Aqara
# bytes ship in a bundle. An adbd of another firmware (another hash) is left alone.
#   $AA_MOD/module.prop
#   $AA_MOD/$AA_PATH     the patched adbd (0755 root:shell, adbd_exec)
# Once per agent start, taking effect at the next restart, when Magisk mounts it. persist.glassworks.adb_auth=off (the
# undo; any app may set it, which can only turn the check off) removes the module. Safety: with the module mounted and
# adb on, if adbd isn't running steadily ($AA_STEADY checks in a row) within $AA_WAIT s the module goes and $AA_FAILED (root 0600) keeps it from coming back,
# so the next restart has Aqara's adbd again. Status: sys.glassworks.adb.auth = on | off | "on after a restart" |
# "off after a restart" | "off: ..." (why it can't be on) | "error: ..."
AA_MOD=$HN_MODS/glassworks_adbd_auth
AA_PATH=system/bin/adbd
AA_LIVE=${GWA_AA_LIVE:-/system/bin/adbd}
AA_STOCK=${GWA_AA_STOCK:-f122608055852e0b5a809703b7cafb4d04e4aab225680b414d502486766fe2ee}   # 4.5.80
AA_FIXED=${GWA_AA_FIXED:-a5648f6c772ca074d84b8b1fab332f58faf652dab30bcd47fc120fbb8657d16d}   # the same, 0x20d0 nop
AA_AT=8400                           # 0x20d0: the second strb, after "movs r0,#0" at 0x20ce
AA_STAGE=$DIR/adbd-auth-module.new
AA_FAILED=$DIR/adbd-auth-failed
AA_PROP=persist.glassworks.adb_auth
AA_STATUS=sys.glassworks.adb.auth
AA_WAIT=${GWA_AA_WAIT:-120}; AA_STEADY=${GWA_AA_STEADY:-4}
aa_say() { [ "$1" = "$(getprop $AA_STATUS)" ] || { setp $AA_STATUS "$1"; say "adbd key check: $1"; }; }
aa_bytes() { dd if="$1" bs=1 skip=$((AA_AT - 2)) count=4 2>/dev/null | od -An -tx1 | tr -d ' \n'; }   # 4 bytes at 0x20ce
aa_remove() { # the module goes; 0 when it's gone
  [ -e "$AA_MOD" ] || return 0
  rm -rf "$AA_MOD" && [ ! -e "$AA_MOD" ]
}
aa_apply() {
  rm -rf "$AA_STAGE"
  live_=$(sha256 "$AA_LIVE")
  if [ "$(getprop $AA_PROP)" = off ] || [ -e "$AA_FAILED" ]; then
    aa_remove || { aa_say "error: could not remove the module"; return 0; }
    why_=off; [ -e "$AA_FAILED" ] && [ "$(getprop $AA_PROP)" != off ] && why_="off: the patched adbd didn't run, so it was taken out"
    if [ "$live_" = "$AA_FIXED" ]; then aa_say "off after a restart"; else aa_say "$why_"; fi
    return 0
  fi
  if [ ! -f "$AA_MOD/$AA_PATH" ] || [ "$(sha256 "$AA_MOD/$AA_PATH")" != "$AA_FIXED" ]; then
    if [ "$live_" != "$AA_STOCK" ]; then
      aa_remove
      if [ "$live_" = "$AA_FIXED" ]; then aa_say "off after a restart"
      else aa_say "off: not the adbd this patch knows ($(printf '%.12s' "$live_"))"; fi
      return 0
    fi
    [ -d "$HN_MODS" ] || { aa_say "error: no Magisk modules dir"; return 0; }
    t_=$AA_STAGE
    # Patched in the stage, checked twice: the 4 bytes are what 4.5.80 has there, and the result has the known hash.
    if mkdir -p "$t_/${AA_PATH%/*}" && cp "$AA_LIVE" "$t_/$AA_PATH" && [ "$(aa_bytes "$t_/$AA_PATH")" = 00202070 ] &&
       printf '\000\277' | dd of="$t_/$AA_PATH" bs=1 seek=$AA_AT conv=notrunc 2>/dev/null &&
       [ "$(sha256 "$t_/$AA_PATH")" = "$AA_FIXED" ] &&
       printf '%s\n' "id=glassworks_adbd_auth" "name=Glassworks adb key check" "version=1" "versionCode=1" \
         "author=Glassworks" "description=Makes adbd check adb keys again (installed by the frame agent)" > "$t_/module.prop" &&
       { [ ! -e "$AA_MOD/disable" ] || : > "$t_/disable"; } &&   # the operator's off switch in Magisk stays
       chown -R root:root "$t_" && chmod 755 "$t_" "$t_/system" "$t_/${AA_PATH%/*}" && chmod 644 "$t_/module.prop" &&
       chown root:2000 "$t_/$AA_PATH" && chmod 755 "$t_/$AA_PATH"; then
      chcon u:object_r:adbd_exec:s0 "$t_/$AA_PATH" 2>/dev/null
      if ! aa_remove || ! mv "$t_" "$AA_MOD"; then rm -rf "$t_"; aa_say "error: could not put the module in place"; return 0; fi
      say "adbd key check: module in place (from the next restart)"
    else
      rm -rf "$t_"; aa_say "error: could not make the patched adbd"; return 0
    fi
  fi
  if [ "$live_" = "$AA_FIXED" ]; then aa_say on; aa_watch
  elif [ -e "$AA_MOD/disable" ]; then aa_say "off: the module is disabled in Magisk"
  elif [ ! -f "$HN_MAGISK_BB" ]; then aa_say "off: Magisk loads no modules here (no $HN_MAGISK_BB)"
  else aa_say "on after a restart"; fi
}
aa_watch() { # the patched adbd is mounted: if adb is on and adbd doesn't run, take the module out for the next restart
  ( i_=0; up_=0
    while [ $i_ -lt "$AA_WAIT" ]; do
      # adb on: over USB (sys.usb.config lists adb) or TCP (a port set). Off, adbd isn't meant to run.
      case ",$(getprop sys.usb.config)," in *,adb,*) ;; *) case "$(getprop service.adb.tcp.port)" in [1-9]*) ;; *) exit 0 ;; esac ;; esac
      # Running at several checks in a row, so an adbd that crashes and init restarts doesn't pass on one look.
      if [ "$(getprop init.svc.adbd)" = running ]; then up_=$((up_ + 1)); [ $up_ -ge "$AA_STEADY" ] && exit 0; else up_=0; fi
      sleep ${GWA_AA_EVERY:-5}; i_=$((i_ + ${GWA_AA_EVERY:-5}))
    done
    ( umask 077; : > "$AA_FAILED" ); aa_remove
    aa_say "error: the patched adbd didn't run; Aqara's adbd is back from the next restart"
  ) &
}

loops_stop() { tz_stop; portal_stop; adb_stop; }

# --- our processes ---------------------------------------------------------------------------
BRIDGE_PID=""; BRIDGE_RANGE=""; OTBR_PID=""; JOINED=""; FAILS=0
ZB_PID=""; ZB_SERVED=""; ZB_DEATHS=0

# The zigbeed process the bridge serves: ours (Silicon Labs) or Aqara's (under zigbee_zigbeed).
zb_pid() {
  if [ "$ZRUN" = sisdk ]; then alive "$ZB_PID" && echo "$ZB_PID"
  else p_=$(pidof zigbeed) && echo "${p_%% *}"; fi
}

# zigbeed is up and holds its end of the pty pair (it opens it a few seconds after start).
zigbeed_ready() {
  p=$(zb_pid) || return 1
  t=$(readlink $ZIGBEED_PTY) || return 1
  ls -l /proc/$p/fd 2>/dev/null | grep -q -- "-> $t\$"
}

# A fresh zigbeed writes an RSTACK (ASH "reset done") to the pty as it starts. Nobody reads it, so it
# waits in $EZSP_PTY for the first client: Zigbee2MQTT's ember adapter takes it for the answer to its
# own RST, sees zigbeed's real RSTACK ~4.3 s later as an NCP crash and fails to start (found on the
# panel, docs/research/zigbee2mqtt.md). bellows and herdsman's ezsp adapter only log CRC errors. So,
# before the bridge serves anyone after a zigbeed (re)start: read the pty until it has been quiet for
# a second after the first bytes (at most ~8 s when nothing comes).
drain_pty() {
  i_=0; got_=0
  while [ $i_ -lt 8 ]; do
    i_=$((i_ + 1))
    n_=$(timeout 3 /vendor/bin/socat -u -T 1 FILE:$EZSP_PTY,raw,echo=0 - 2>/dev/null | wc -c)
    n_=${n_##* }
    if [ "${n_:-0}" -gt 0 ]; then got_=$((got_ + n_)); elif [ $got_ -gt 0 ]; then break; fi
  done
  say "drained $got_ stale byte(s) from $EZSP_PTY before serving clients"
}
# Drain on connect: every client session runs $RELAY (socat EXEC; a root-only copy rewritten at each
# start), which first reads and drops what is already waiting in the pty, then relays. A client that
# goes away leaves zigbeed's ASH layer to time out and write an ERROR frame to the pty, which would
# otherwise greet the next client (Zigbee2MQTT: "CRC error", then ERROR_EXCEEDED_MAXIMUM_ACK_TIMEOUT_COUNT;
# ZHA after a Home Assistant restart alike). The client's own first bytes wait in the socket meanwhile:
# the relay only reads them once the drain is done. The drain ends after $RELAY_QUIET s without a byte,
# at most 2 s (timeout) if zigbeed kept talking. /system/bin/sh runs it (0600, no exec bit needed):
# Android has no /bin/sh, so socat's SYSTEM: address would not work.
RELAY=$DIR/ezsp-relay.sh
RELAY_QUIET=0.2
write_relay() { # (stdin from /dev/null for the drain: the client's bytes must stay in the socket)
  rm -f "$RELAY.tmp"
  ( umask 077
    { echo '#!/system/bin/sh'
      echo "# Written by glassworks_agent.sh at every start; one EZSP client session of the bridge (drain, relay)."
      echo "timeout 2 /vendor/bin/socat -u -T $RELAY_QUIET FILE:$EZSP_PTY,raw,echo=0 - </dev/null >/dev/null 2>&1"
      echo "exec /vendor/bin/socat - FILE:$EZSP_PTY,raw,echo=0"
    } > "$RELAY.tmp" ) && mv -f "$RELAY.tmp" "$RELAY"
}
# Aqara's socat is its own build: the relay needs its EXEC address. Without it (never seen, but a
# Zigbee bridge that can't start would be worse) the bridge relays straight to the pty as before v4.
RELAY_ADDR="FILE:$EZSP_PTY,raw,echo=0"
if /vendor/bin/socat -h 2>/dev/null | grep -q '^ *exec:'; then
  write_relay && RELAY_ADDR="EXEC:/system/bin/sh $RELAY"
else
  say "this socat has no EXEC address: no drain on connect"
fi

# The bridge has a connected client (socat forks one child per connection).
bridge_busy() { alive "$BRIDGE_PID" && pgrep -P "$BRIDGE_PID" >/dev/null 2>&1; }
# Called once zigbeed is ready: a new zigbeed process gets its boot bytes drained first. A connected
# client keeps its session (it is mid-conversation with the new process anyway and will reset).
zb_fresh() {
  p_=$(zb_pid)
  [ "$p_" = "$ZB_SERVED" ] && return 0
  if alive "$BRIDGE_PID" && ! bridge_busy; then kill "$BRIDGE_PID"; BRIDGE_PID=""; fi
  alive "$BRIDGE_PID" || drain_pty
  ZB_SERVED=$p_
}

# One EZSP host at a time (max-children=1); each connection reopens the pty (in $RELAY, after the
# drain), so a new client works even after Aqara's watchdog recreated the pty pair. TCP keepalive frees
# the slot within ~1 min when a client vanished without closing (HA host crash, Wi-Fi drop).
start_bridge() { # listen-address-option port range
  wait_for $EZSP_PTY 30 || { status error "$EZSP_PTY missing (zigbee_socat not up)"; return 1; }
  r=""; [ -n "$3" ] && r=",range=$3"
  /vendor/bin/socat TCP-LISTEN:$2,reuseaddr,fork,max-children=1,keepalive,keepidle=30,keepintvl=10,keepcnt=3$1$r \
    "$RELAY_ADDR" &
  BRIDGE_PID=$!; BRIDGE_RANGE=$3
}
coordinator_bridge() {
  zb_fresh
  r=$(bridge_range)
  if alive "$BRIDGE_PID" && [ "$r" != "$BRIDGE_RANGE" ]; then
    say "client range changed: '${BRIDGE_RANGE:-any}' -> '${r:-any}'"; kill "$BRIDGE_PID"; BRIDGE_PID=""
  fi
  alive "$BRIDGE_PID" || start_bridge "" "$ZIGBEE_PORT" "$r"
}

# A copy of a zigbeed NVM (network + keys) whenever it changed; the last 3 per stack are kept, other
# files in zigbee-nvm/ (manual backups) are never touched. Root-only, never leaves $DIR.
# 0 = the newest copy is the NVM as it is now (just made, or unchanged since); 1 = no complete copy (WHY).
# A copy is made under a temporary name, compared with the NVM and only then renamed, so a copy that
# failed half way (a full /data) never counts as the newest and never makes the pruning drop a good one.
backup_nvm() { # nvm-file prefix
  [ -s "$1" ] || return 0
  { mkdir -p "$DIR/zigbee-nvm" && chmod 700 "$DIR/zigbee-nvm"; } || { WHY="cannot create zigbee-nvm/"; return 1; }
  last=$(ls "$DIR/zigbee-nvm" 2>/dev/null | grep "^$2-.*\.nvm\$" | tail -1)
  [ -n "$last" ] && cmp -s "$1" "$DIR/zigbee-nvm/$last" && return 0
  n_=$DIR/zigbee-nvm/$2-$(date +%Y%m%d-%H%M%S).nvm
  rm -f "$n_.tmp"
  if ! { ( umask 077; cp "$1" "$n_.tmp" ) && cmp -s "$1" "$n_.tmp" && chmod 600 "$n_.tmp" && mv -f "$n_.tmp" "$n_"; }; then
    rm -f "$n_.tmp"; WHY="no complete backup of the $2 NVM (/data full?)"; return 1
  fi
  set -- $(ls "$DIR/zigbee-nvm" | grep "^$2-.*\.nvm\$"); while [ $# -gt 3 ]; do rm -f "$DIR/zigbee-nvm/$1"; shift; done
}
router_bridge() {
  zb_fresh
  alive "$BRIDGE_PID" && return 0
  # the router host's join replaces the coordinator network in this NVM: never without a copy of it
  # A failed copy (a full /data) can clear up: the bridge waits, supervision goes on, and it isn't counted
  # towards the give-up limit (the main loop shows BLOCKED as the error).
  backup_nvm "$ZIGBEED_NVM" zigbeed || { BLOCKED="$WHY: router waits"; return 0; }
  start_bridge ",bind=127.0.0.1" "$ROUTER_PORT" ""
}

# Silicon Labs' zigbeed 8.2.2 through the bundle's glibc runtime (sisdk/zigbeed.sh), on spinel IID 1
# and Aqara's pty pair, with its own NVM in $SISDK_STATE.
start_sisdk() {
  wait_for $CPC_SOCK 30 || { status error "cpcd socket missing"; return 1; }
  sleep 5   # cpcd needs ~4 s to reach the secondary after a (re)start (see start_otbr)
  mkdir -p "$SISDK_STATE" && chmod 700 "$SISDK_STATE"
  backup_nvm "$SISDK_STATE/host_token.nvm" sisdk || say "$WHY"   # a coordinator: a copy only, nothing replaces it
  # shellcheck disable=SC2086
  ZIGBEED_BIN=$SISDK_BIN ZIGBEED_STATE=$SISDK_STATE /system/bin/sh "$SISDK_DIR/zigbeed.sh" \
    -r "$ZIGBEED_URL" -p "$ZIGBEED_PTY" $ZIGBEED_ARGS >/dev/null 2>&1 &
  ZB_PID=$!; ZB_SERVED=""
  say "started Silicon Labs zigbeed $SISDK_VERSION (pid $ZB_PID)"
}
sisdk_ok() { # zigbee_tcp with the Silicon Labs stack: 0 = zigbeed is (being) run, 1 = status set
  take_sisdk; zstate
  [ -n "$SISDK_DIR" ] || { status error "the Silicon Labs Zigbee stack needs a radio bundle with its runtime (update the app)"; return 1; }
  [ -x "$SISDK_BIN" ] || return 0     # not_ready: waiting for the app's download
  if ! alive "$ZB_PID"; then
    [ -n "$ZB_PID" ] && { ZB_DEATHS=$((ZB_DEATHS + 1)); say "Silicon Labs zigbeed exited ($ZB_DEATHS)"; }
    [ $ZB_DEATHS -gt 10 ] && { status error "Silicon Labs zigbeed keeps exiting (try the Aqara stack)"; return 1; }
    start_sisdk || return 1
  fi
}

ot() { # ot-ctl without its CRLF line ends, keeping its exit status
  o_=$("$(bin ot-ctl)" "$@" 2>/dev/null); r_=$?
  [ -n "$o_" ] && printf '%s\n' "$o_" | tr -d '\r'
  return $r_
}

start_otbr() { # settings-dir extra-args...
  data=$1; shift
  [ -x "$(bin otbr-agent)" ] || { status error "otbr-agent not installed ($DIR or $BUNDLE)"; return 1; }
  wait_for $CPC_SOCK 30 || { status error "cpcd socket missing"; return 1; }
  # cpcd needs ~4 s to reach the secondary after a (re)start, and endpoint 12 closes asynchronously:
  # an early start fails ("CPC init failed -2" / "CPC endpoint open failed"); the loop retries.
  sleep 5
  mkdir -p "$DIR/run" "$data"  # TUN is /dev/tun (compiled in by build-otbr.sh)
  # shellcheck disable=SC2086
  "$(bin otbr-agent)" -s -I wpan0 "$@" --data-path "$data" $OTBR_ARGS "$RADIO_URL" &
  OTBR_PID=$!
}

start_thread_br() {
  if [ "$OTBR_KIND" = br ]; then
    br_net_on
    start_otbr "$DIR/thread" -B "$BACKBONE" --rest-listen-address "$REST_LISTEN" --rest-listen-port "$REST_PORT"
  else
    start_otbr "$DIR/thread"  # proof build: agent only (no border routing/REST/mDNS)
  fi
}

# thread_router: no backbone (no border routing, no mDNS on the LAN). Never forms a network on its
# own initiative: it only starts Thread once it has a dataset the user supplied. Its own settings dir
# ($DIR/thread-router), so it never auto-attaches to the network the border-router mode stored.
# The hand-off is the only copy once taken (--move): it stays in thread-dataset.in until the new
# dataset is complete in thread-dataset (written to a temp file, read back, renamed over it). Until
# then the router keeps the dataset it has and the next loop tries again; a newer hand-off from the app
# waits in the app's files until this one is stored.
# A Leave (thread_leave) records its request's time in thread-left, and each hand-off its file's time in
# thread-dataset.in.t when taken: a hand-off the app staged before the last Leave belongs to the network
# left, so it is discarded here, whenever and in whichever mode it is taken. A newer one (a Join after it)
# is stored as usual.
DS_FAILED=""
num_in() { n_=$(cat "$1" 2>/dev/null); case "$n_" in ''|*[!0-9]*) echo "$2" ;; *) echo "$n_" ;; esac; }
take_app_dataset() {
  if [ ! -e "$DIR/thread-dataset.in" ]; then
    take_app_file "${APP_DATASET#"$APP_DATA"/files/}" max:1024 "$DIR/thread-dataset.in" move; r_=$?
    [ $r_ = 1 ] && return 0
    [ $r_ = 2 ] && { say "ignoring app dataset hand-off ($WHY)"; return 0; }
    echo "$TAKE_MTIME" > "$DIR/thread-dataset.in.t"
  fi
  # A time that wasn't recorded (a full /data, or the agent stopped between the move and the write) never gets past
  # a recorded Leave: such a hand-off counts as older than it. With no Leave recorded it is kept.
  if [ "$(num_in "$DIR/thread-dataset.in.t" 0)" -le "$(num_in "$DIR/thread-left" -1)" ]; then
    rm -f "$DIR/thread-dataset.in" "$DIR/thread-dataset.in.t"
    say "ignoring app dataset hand-off (staged before a Leave)"; return 0
  fi
  d=$(tr -d ' \r\n' < "$DIR/thread-dataset.in")
  case "$d" in ""|*[!0-9a-fA-F]*) rm -f "$DIR/thread-dataset.in" "$DIR/thread-dataset.in.t"; say "ignoring app dataset hand-off (not hex TLVs)"; return 0 ;; esac
  [ $(( ${#d} % 2 )) -eq 0 ] || { rm -f "$DIR/thread-dataset.in" "$DIR/thread-dataset.in.t"; say "ignoring app dataset hand-off (odd length)"; return 0; }
  rm -f "$DIR/thread-dataset.tmp"
  if ! { ( umask 077; echo "$d" > "$DIR/thread-dataset.tmp" ) && [ "$(cat "$DIR/thread-dataset.tmp")" = "$d" ] &&
         mv -f "$DIR/thread-dataset.tmp" "$DIR/thread-dataset"; }; then
    rm -f "$DIR/thread-dataset.tmp"
    [ -n "$DS_FAILED" ] || say "cannot store the new Thread dataset (/data full?): keeping the current one, trying again"
    DS_FAILED=1; return 0
  fi
  # stored: anything the app stages from now on is newer than this one, so the Leave's time has done its job
  rm -f "$DIR/thread-dataset.in" "$DIR/thread-dataset.in.t" "$DIR/thread-left"; DS_FAILED=""; JOINED=""; JFAILS=0
  say "new Thread dataset from the app"  # never log the dataset itself
  thread_stored
  # a running agent may be attached elsewhere: restart it, the next pass joins with the new dataset
  alive "$OTBR_PID" && { kill "$OTBR_PID"; OTBR_PID=""; }
}
# "Leave Thread network" (v4, any mode, Off included): the app writes files/radio/thread-leave; the
# agent detaches a running router, then deletes the dataset it was given ($DIR/thread-dataset, the
# network key) and the router's OpenThread settings ($DIR/thread-router). The border-router mode's own
# network ($DIR/thread) is not touched. Result in sys.glassworks.radio.thread_leave = "<ok|error> <epoch>: ...".
thread_leave() {
  take_app_file "$APP_THREAD_LEAVE" max:64 "$DIR/thread-leave.req" move; r_=$?
  [ $r_ = 1 ] && return 0
  rm -f "$DIR/thread-leave.req"
  [ $r_ = 2 ] && { setp sys.glassworks.radio.thread_leave "error $(date +%s): $WHY"; return 0; }
  [ $(( $(date +%s) - TAKE_MTIME )) -le 120 ] || { setp sys.glassworks.radio.thread_leave "error $(date +%s): request too old, ignored"; return 0; }
  lt_=$TAKE_MTIME
  if [ "$MODE" = thread_router ] && alive "$OTBR_PID"; then
    timeout 10 "$(bin ot-ctl)" detach >/dev/null 2>&1   # tell the network we're going (best effort)
    ot thread stop >/dev/null 2>&1; ot ifconfig down >/dev/null 2>&1
    kill "$OTBR_PID"; i_=0; while alive "$OTBR_PID" && [ $i_ -lt 50 ]; do sleep 0.1; i_=$((i_ + 1)); done
    alive "$OTBR_PID" && kill -9 "$OTBR_PID"
    OTBR_PID=""
  fi
  had_=""; [ -e "$DIR/thread-dataset" ] && had_=1
  rm -f "$DIR/thread-dataset" "$DIR/thread-dataset.tmp"
  # A pending hand-off stays only when it is newer than this request (a Join after the Leave); one staged in the
  # app's files is judged by take_app_dataset against thread-left when it is taken.
  [ "$(num_in "$DIR/thread-dataset.in.t" 0)" -gt "$lt_" ] || rm -f "$DIR/thread-dataset.in" "$DIR/thread-dataset.in.t"
  # our own root dir; a symlink there (only root could make one) is removed, never followed
  if [ -L "$DIR/thread-router" ]; then rm -f "$DIR/thread-router"; elif [ -e "$DIR/thread-router" ]; then rm -rf "$DIR/thread-router"; had_=1; fi
  JOINED=""; JFAILS=0
  if [ -e "$DIR/thread-dataset" ] || [ -e "$DIR/thread-router" ]; then
    setp sys.glassworks.radio.thread_leave "error $(date +%s): could not delete the Thread settings"; return 0
  fi
  # what makes a hand-off staged before this request never join (take_app_dataset): no "ok" without it
  if ! { echo "$lt_" > "$DIR/thread-left.tmp" && mv -f "$DIR/thread-left.tmp" "$DIR/thread-left"; }; then
    rm -f "$DIR/thread-left.tmp"
    setp sys.glassworks.radio.thread_leave "error $(date +%s): could not record the Leave (/data full?)"; return 0
  fi
  if [ -n "$had_" ]; then m_="Thread dataset and router settings deleted"; else m_="no Thread network was stored"; fi
  setp sys.glassworks.radio.thread_leave "ok $(date +%s): $m_"
  say "Thread network left: dataset and router settings deleted"   # never the dataset itself
}
start_thread_router() { # start_otbr's result: its failure (and the status it set) reaches the loop
  JOINED=""
  if [ "$OTBR_KIND" = br ]; then start_otbr "$DIR/thread-router" --rest-listen-address 127.0.0.1 --rest-listen-port "$REST_PORT"
  else start_otbr "$DIR/thread-router"; fi
}
# Called every loop once the agent answers: joins once a dataset is there. Failed joins count across
# loops (JFAILS; the main loop's FAILS restarts with every pass whose processes are up). After 21 in a
# row with one dataset it stops trying that one and says so; a new dataset, Leave or a restart tries again.
JFAILS=0
thread_router_join() {
  [ -n "$JOINED" ] && return 0
  [ -s "$DIR/thread-dataset" ] || return 0
  [ $JFAILS -gt 20 ] && { status error "joining failed $JFAILS times (dataset rejected?): send a new one"; return 1; }
  if ot dataset set active "$(cat "$DIR/thread-dataset")" >/dev/null && ot ifconfig up >/dev/null &&
     ot thread start >/dev/null; then
    JOINED=1; JFAILS=0
  else
    JFAILS=$((JFAILS + 1))
    status error "ot-ctl join steps failed (dataset rejected?)"; return 1
  fi
}

# --- radio firmware ------------------------------------------------------------------------------
# Only ever on an explicit request from the app (fw-request, at most 2 minutes old), only images whose
# hash the request names, only app-area GBLs, only with Aqara's own cpcd (its reset pin is inverted
# compared with upstream cpcd; docs/radio.md "Flash path, verified"), and only while a stock image to
# roll back to is on /vendor.
fwstat() { setp sys.glassworks.radio.fw "$*"; say "firmware: $*"; }
fwdone() { fwstat "$*"; echo "$*" > "$FW_DIR/last"; }   # a final outcome: shown again after restarts

# gbl_ok <file>: a Gecko bootloader image that can only write the application area: tags we know,
# no bootloader or Secure Engine upgrade, no encryption, ending with the end tag exactly at EOF.
gbl_ok() {
  g_=$1; sz_=$(stat -c %s "$g_" 2>/dev/null)
  { [ -n "$sz_" ] && [ "$sz_" -ge 32 ] && [ "$sz_" -le 1048576 ]; } || { WHY="not a radio image (size)"; return 1; }
  off_=0; end_=""
  while [ $off_ -lt "$sz_" ]; do
    # tag id and length, little-endian u32 (od prints host order; this panel is little-endian)
    set -- $(od -A n -t x4 -j $off_ -N 8 "$g_")
    [ $# = 2 ] || { WHY="truncated GBL tag"; return 1; }
    if [ $off_ = 0 ] && [ "$1" != 03a617eb ]; then WHY="not a GBL file"; return 1; fi
    case "$1" in
      03a617eb|f40a0af4|f60808f6|fe0101fe|fd0303fd|fd0505fd|fd0707fd|76a617eb|f70a0af7|f30b0bf3) ;;
      fc0404fc) end_=1 ;;
      f50909f5) WHY="the image would replace the bootloader"; return 1 ;;
      5ea617eb) WHY="the image would upgrade the Secure Engine"; return 1 ;;
      *) WHY="unknown or encrypted GBL tag 0x$1"; return 1 ;;
    esac
    l_=$((0x$2))   # mksh arithmetic is 32-bit signed: 0xfffffff8 is -8, which would loop forever
    { [ "$l_" -ge 0 ] && [ "$l_" -le $((sz_ - off_ - 8)) ]; } || { WHY="GBL tag length out of range"; return 1; }
    off_=$((off_ + 8 + l_))
    [ -n "$end_" ] && break
  done
  { [ -n "$end_" ] && [ $off_ = "$sz_" ]; } || { WHY="GBL does not end with its end tag"; return 1; }
}

# fw_resolve <id>: FW_FILE, FW_SHA, FW_APP (the CPC "Secondary APP" version it reports), FW_LABEL.
fw_resolve() {
  FW_FILE=""; FW_SHA=""; FW_APP=""; FW_LABEL=""
  if [ "$1" = stock ]; then
    # shellcheck disable=SC2086
    set -- $STOCK_GLOB
    { [ $# = 1 ] && [ -f "$1" ]; } || { WHY="no single stock radio image in /vendor/firmware"; return 1; }
    FW_FILE=$1; FW_SHA=$(sha256 "$1")
    for k_ in $KNOWN_STOCK; do [ "${k_%%:*}" = "$FW_SHA" ] && FW_APP=${k_#*:}; done
    if [ -n "$FW_APP" ]; then FW_LABEL="Aqara stock"
    else # an Aqara image we haven't seen: its file name carries the version (lichun#30000000#<n>#...)
      n_=${FW_FILE#*#30000000#}; n_=${n_%%#*}
      case "$n_" in ""|*[!0-9]*) WHY="stock image name without a version"; return 1 ;; esac
      FW_APP=$(printf '%04d' "$n_"); FW_LABEL="Aqara stock (unpinned)"
    fi
  else
    [ -n "$RBUNDLE" ] && [ -f "$RBUNDLE/manifest" ] || { WHY="no radio bundle"; return 1; }
    l_=$(grep "^firmware $1 " "$RBUNDLE/manifest" | head -1)
    [ -n "$l_" ] || { WHY="the radio bundle has no firmware '$1'"; return 1; }
    set -f; set -- $l_; set +f
    FW_FILE=$RBUNDLE/$3; FW_APP=$4; shift 4; FW_LABEL="$*"
    FW_SHA=$(sha256 "$FW_FILE")
    # the loader checked every file at start; check this one again right before use
    grep -q "^file [0-7]* [0-9]* $FW_SHA ${FW_FILE#"$RBUNDLE"/}\$" "$RBUNDLE/manifest" || { WHY="image does not match the signed manifest"; return 1; }
  fi
  gbl_ok "$FW_FILE"
}

fw_targets() { # sys.glassworks.radio.fw_targets: "<id>:<sha12>:<app> ..." (stock first)
  t_=""
  fw_resolve stock && t_="stock:$(echo "$FW_SHA" | cut -c 1-12):$FW_APP"
  if [ -n "$RBUNDLE" ] && [ -f "$RBUNDLE/manifest" ]; then
    for i_ in $(sed -n 's/^firmware \([a-z0-9-]*\) .*/\1/p' "$RBUNDLE/manifest"); do
      fw_resolve "$i_" && t_="$t_ $i_:$(echo "$FW_SHA" | cut -c 1-12):$FW_APP"
    done
  fi
  setp sys.glassworks.radio.fw_targets "${t_# }"
}

# The radio's application version as Aqara's cpcd reads it over CPC (normal config, nothing written:
# every cpcd start soft-resets the RCP). Needs the SPI bus free (no cpcd running).
fw_read_app() {
  timeout 30 /vendor/bin/cpcd -c /etc/cpcd.conf -p 2>&1 | tr -d '\r' |
    sed -n 's/.*Secondary APP v\([0-9A-Za-z._-]*\).*/\1/p' | tail -1
}

# How far cpcd -f is: the last "Info : progress:<n>" line it printed (0-100), or nothing when it printed
# none yet (or its output is still buffered). sys.zb_ota_progress is only set by Aqara's OTA wrapper.
fw_progress() {
  { tr -d '\r' < "$1"; } 2>/dev/null | sed -n 's/.*progress:[ ]*\([0-9][0-9]*\).*/\1/p' | tail -1 |
    { read -r n_ && [ "$n_" -le 100 ] 2>/dev/null && echo "$n_"; }
}

# A cpcd we stop must be gone before anything opens the SPI device again: the retry's cpcd would start
# while the old one may still hold the device's lock. TERM, up to 10 s; then KILL, up to 5 s more.
# 1 = it is still there (FW_STUCK: nothing else may touch the radio in this run).
FW_STUCK=""
fw_stop_cpcd() { # pid
  kill "$1" 2>/dev/null
  i_=0; while alive "$1" && [ $i_ -lt "${GWA_FW_TERM_WAIT:-100}" ]; do sleep 0.1; i_=$((i_ + 1)); done
  if alive "$1"; then
    kill -9 "$1" 2>/dev/null
    i_=0; while alive "$1" && [ $i_ -lt 50 ]; do sleep 0.1; i_=$((i_ + 1)); done
  fi
  alive "$1" && { FW_STUCK=1; return 1; }
  wait "$1" 2>/dev/null; return 0
}

fw_flash_one() { # file id -> 0 if cpcd reports success
  ( umask 077; : > "$FW_DIR/flash.log" )
  /vendor/bin/cpcd -c /etc/cpcd_ota.conf -f "$1" >> "$FW_DIR/flash.log" 2>&1 &
  p_=$!; t0_=$(date +%s); t_=0
  while alive $p_; do
    sleep 2; t_=$(( $(date +%s) - t0_ ))
    fw_guard
    # a percentage only when cpcd said one; otherwise the time it has been flashing
    if pc_=$(fw_progress "$FW_DIR/flash.log"); then d_="$pc_%"; else d_="$t_ s"; fi
    fwstat "flashing $2: $d_ (do not unplug the panel)"
    if [ $t_ -ge "${GWA_FW_TIMEOUT:-240}" ] && alive $p_; then
      if fw_stop_cpcd $p_; then WHY="cpcd timed out"; else WHY="cpcd timed out and did not exit"; fi
      return 1
    fi
  done
  wait $p_; r_=$?
  grep -q "Firmware upgrade successful" "$FW_DIR/flash.log" && [ $r_ = 0 ] && return 0
  WHY="cpcd: $(tr -d '\r' < "$FW_DIR/flash.log" | grep -v '^ *$' | tail -1 | sed 's/^\[[^]]*\] //' | cut -c 1-50)"
  return 1
}

# After a flash: the bootloader starts the new app by itself. Its version must be the one expected;
# the spinel version (otbr-agent --radio-version on IID 2) is recorded when an otbr-agent is there.
fw_verify() { # expected-app -> 0 if it matches; FW_GOT, FW_SPINEL
  FW_GOT=""; FW_SPINEL=""
  for i_ in 1 2 3; do
    sleep 3; FW_GOT=$(fw_read_app); [ "$FW_GOT" = "$1" ] && break
  done
  [ "$FW_GOT" = "$1" ] || { WHY="radio reports APP ${FW_GOT:-nothing}, not $1"; return 1; }
  if [ -x "$(bin otbr-agent)" ] && svc zigbee_cpcd running && wait_for $CPC_SOCK 30; then
    sleep 5
    FW_SPINEL=$(timeout 30 "$(bin otbr-agent)" -s -v --radio-version "$RADIO_URL" 2>/dev/null | tr -d '\r' | tail -1 | cut -c 1-60)
  fi
  svc zigbee_cpcd stopped
  return 0
}

stop_children() {
  for p_ in $BRIDGE_PID $OTBR_PID $ZB_PID; do alive "$p_" && kill "$p_"; done
  sleep 1
  for p_ in $BRIDGE_PID $OTBR_PID $ZB_PID; do alive "$p_" && kill -9 "$p_"; done
  BRIDGE_PID=""; OTBR_PID=""; ZB_PID=""
}

# Before a flash: every radio process and service stopped, and nothing else holding the radio: Aqara's
# boot sequence (zigbee_cpcd_factory -> sys.gateway.config=10) and its watchdog (=11) can start
# zigbee_cpcd at any time, and its OTA service runs a cpcd of its own. 0 = quiet, else WHY.
fw_quiet() {
  stop_children; WANT=""; enforce
  i_=0
  while [ $i_ -lt 15 ]; do
    busy_=""
    for s_ in $STOP_ORDER start_rcp_ota zigbee_cpcd_factory; do
      case "$(svc_state $s_)" in running|restarting) busy_=$s_; setprop ctl.stop $s_ ;; esac
    done
    [ -z "$busy_" ] && pidof cpcd >/dev/null && busy_="a cpcd process"
    [ -z "$busy_" ] && return 0
    sleep 1; i_=$((i_ + 1))
  done
  WHY="$busy_ is still using the radio (sys.gateway.config=$(getprop sys.gateway.config))"; return 1
}
# While cpcd -f runs (every 2 s): anything of Aqara's that starts gets stopped again. Our cpcd holds the
# SPI device's lock, so a late zigbee_cpcd can't disturb the transfer; it would only restart in a loop.
fw_guard() {
  for s_ in $STOP_ORDER start_rcp_ota; do
    case "$(svc_state $s_)" in running|restarting) setprop ctl.stop $s_; say "firmware: stopped $s_ during the flash" ;; esac
  done
}

fw_attempt() { # id -> flash it (2 tries) and verify; FW_* from fw_resolve
  for a_ in 1 2; do
    [ -n "$FW_STUCK" ] && return 1   # a cpcd that would not exit still holds the radio: no second one
    fwstat "flashing $1: try $a_ (do not unplug the panel)"
    fw_flash_one "$FW_FILE" "$1" && fw_verify "$FW_APP" && return 0
    say "firmware: $1 try $a_ failed: $WHY"
  done
  return 1
}

# fw_mark <line>: $FW_DIR/in-progress, written to a temp file, read back, renamed and synced. 1 = not there.
fw_mark() {
  rm -f "$FW_DIR/in-progress.tmp"
  if { echo "$1" > "$FW_DIR/in-progress.tmp" && mv -f "$FW_DIR/in-progress.tmp" "$FW_DIR/in-progress" &&
       [ "$(cat "$FW_DIR/in-progress" 2>/dev/null)" = "$1" ]; } 2>/dev/null; then
    sync; return 0
  fi
  rm -f "$FW_DIR/in-progress.tmp"; return 1
}

fw_do() { # id sha-prefix: the whole explicit operation; the agent restarts afterwards
  id_=$1
  fw_resolve "$id_" || { fwstat "error $id_: $WHY"; return 0; }
  case "$FW_SHA" in "$2"*) ;; *) fwstat "error $id_: the image is not the one requested (${2})"; return 0 ;; esac
  T_FILE=$FW_FILE; T_APP=$FW_APP; T_SHA=$FW_SHA
  # The rollback target must exist and check out before anything else happens.
  fw_resolve stock || { fwstat "error $id_: no stock image to roll back to ($WHY)"; return 0; }
  echo "stock $FW_SHA $FW_APP $FW_FILE" > "$FW_DIR/stock.ref"
  FW_FILE=$T_FILE; FW_APP=$T_APP; FW_SHA=$T_SHA

  fwstat "flashing $id_: stopping the radio"
  status applying "radio firmware update ($id_)"
  fw_quiet || { fwstat "error $id_: $WHY"; WANT=$MODE_WANT; return 0; }
  before=$(fw_read_app)
  say "firmware: radio reports APP '${before:-nothing}' before flashing $id_ (APP $FW_APP, sha256 $FW_SHA)"
  # The marker is what makes the next start restore stock after an interrupted flash: no flash without it.
  fw_mark "$id_ $FW_SHA $(date +%s)" || { fwstat "error $id_: cannot write the flash marker (/data full?), nothing flashed"; WANT=$MODE_WANT; return 0; }
  if fw_attempt "$id_"; then
    rm -f "$FW_DIR/in-progress" "$FW_DIR/restore-tries"
    echo "$id_ $FW_APP $FW_SPINEL" > "$FW_DIR/current"
    fwdone "ok $id_: APP $FW_APP${FW_SPINEL:+ ($FW_SPINEL)}"
  else
    why_=$WHY
    if [ "$id_" != stock ] && [ -z "$FW_STUCK" ] && fw_resolve stock; then
      fwstat "flashing stock: $id_ failed ($why_), rolling back"
      if fw_attempt stock; then
        rm -f "$FW_DIR/in-progress" "$FW_DIR/restore-tries"
        echo "stock $FW_APP $FW_SPINEL" > "$FW_DIR/current"
        fwdone "error $id_: $why_; rolled back to stock (APP $FW_APP)"
      else
        fwdone "error $id_: $why_; ROLLBACK FAILED ($WHY): flash Aqara stock again"
      fi
    else
      fwdone "error $id_: $why_"
    fi
  fi
  # Start over cleanly (children are gone; the loop state belongs to the old services).
  say "firmware: restarting the agent"
  loops_stop
  exec /system/bin/sh "$0"
}

# Every loop: an explicit request from the app?
fw_request() {
  # --move: taken (and removed through the verified directory) in one step, never acted on twice
  take_app_file "$APP_FW_REQ" max:128 "$FW_DIR/request" move; r_=$?
  [ $r_ = 1 ] && return 0
  [ $r_ = 2 ] && { fwstat "error request: $WHY"; return 0; }
  age_=$(( $(date +%s) - TAKE_MTIME ))
  [ $age_ -le 120 ] || { fwstat "error request: too old (${age_} s), ignored"; return 0; }
  read -r id_ sha_ rest_ < "$FW_DIR/request"; rm -f "$FW_DIR/request"
  case "$id_" in ""|*[!a-z0-9-]*) fwstat "error request: bad image id"; return 0 ;; esac
  case "$sha_" in *[!0-9a-f]*) sha_="" ;; esac
  [ ${#sha_} -ge 12 ] || { fwstat "error $id_: the request names no image hash"; return 0; }
  fw_do "$id_" "$sha_"
}

# At start: a flash that was interrupted (the agent was killed, the panel lost power) may have left
# the radio without an application. Then the mode's stack must not start; instead Aqara's stock image
# is flashed back (at most twice across restarts), and if that fails too the radio is held off until
# the owner flashes it from Home Assistant.
FW_HOLD=""
fw_recover() {
  read -r i_ rest_ < "$FW_DIR/in-progress"
  tries_=$(cat "$FW_DIR/restore-tries" 2>/dev/null); case "$tries_" in ''|*[!0-9]*) tries_=0 ;; esac
  if [ "$tries_" -lt 2 ] && fw_resolve stock; then   # (fw_resolve uses n_ itself)
    echo $((tries_ + 1)) > "$FW_DIR/restore-tries"
    fwstat "flashing stock: the flash of $i_ was interrupted, restoring Aqara's stock image"
    status applying "restoring the radio's stock firmware"
    if fw_quiet && fw_attempt stock; then
      rm -f "$FW_DIR/in-progress" "$FW_DIR/restore-tries"
      echo "stock $FW_APP $FW_SPINEL" > "$FW_DIR/current"
      fwdone "error $i_: the flash was interrupted; restored stock (APP $FW_APP)"
      say "firmware: restarting the agent"
      loops_stop
      exec /system/bin/sh "$0"
    fi
  fi
  FW_HOLD=1
  fwstat "error $i_: interrupted flash, radio held off; flash Aqara stock from Home Assistant"
}

fw_init() { # status at start: an interrupted flash, the last verified version, or idle
  mkdir -p "$FW_DIR" && chmod 700 "$FW_DIR"
  if [ -s "$FW_DIR/in-progress" ]; then
    fw_recover
  elif [ -s "$FW_DIR/last" ]; then
    fwstat "$(head -1 "$FW_DIR/last")"
  else
    fwstat "idle stock: never flashed by the kiosk"
  fi
  fw_targets
}

BLOCKED=""   # a child that waits on something the owner can fix: the status error, not counted in FAILS
children_ok() {
  BLOCKED=""
  case "$MODE" in
    off)               br_net_off; fw_off ;;
    zigbee_tcp)        br_net_off; fw_apply || return 1
                       if [ "$ZRUN" = sisdk ]; then sisdk_ok || return 1; fi
                       zigbeed_ready && { coordinator_bridge || return 1; } ;;
    zigbee_router)     br_net_off; fw_apply || return 1
                       zigbeed_ready && { router_bridge || return 1; } ;;
    thread)            fw_apply || return 1
                       alive "$OTBR_PID"   || start_thread_br || return 1
                       [ "$OTBR_KIND" = br ] && br_net_on ;;
    thread_router)     br_net_off; fw_apply || return 1; take_app_dataset
                       alive "$OTBR_PID"   || start_thread_router || return 1 ;;
    zigbee_tcp_thread) fw_apply || return 1
                       zigbeed_ready && { coordinator_bridge || return 1; }
                       alive "$OTBR_PID"   || start_otbr "$DIR/thread" || return 1 ;;
  esac
  return 0
}

# While the mode's stack isn't usable yet: prints why and returns 0. Ready: returns 1.
not_ready() {
  case "$MODE" in
    zigbee_tcp|zigbee_router|zigbee_tcp_thread)
      if [ "$ZRUN" = sisdk ] && [ ! -x "$SISDK_BIN" ]; then
        echo "waiting for zigbeed $SISDK_VERSION (the app downloads it from Silicon Labs)"; return 0
      fi
      zigbeed_ready || { echo "waiting for zigbeed"; return 0; }
      alive "$BRIDGE_PID" || { echo "waiting for the EZSP bridge"; return 0; } ;;
  esac
  case "$MODE" in
    thread|thread_router|zigbee_tcp_thread)
      ot state >/dev/null || { echo "waiting for otbr-agent"; return 0; } ;;
  esac
  return 1
}

detail() {
  case "$MODE" in
    off) echo "Aqara radio services stopped, radio idle" ;;
    zigbee_tcp)
      r=$BRIDGE_RANGE
      if [ "$ZRUN" = sisdk ]; then s="EZSP 18 on tcp:$ZIGBEE_PORT, Silicon Labs zigbeed $SISDK_VERSION"
      else s="EZSP on tcp:$ZIGBEE_PORT"; fi
      echo "$s${r:+ (clients from $r)}" ;;
    zigbee_router) echo "EZSP on 127.0.0.1:$ROUTER_PORT for the router host" ;;
    thread)
      if [ "$OTBR_KIND" = br ]; then
        echo "border router on wpan0, backbone $BACKBONE, REST :$REST_PORT ($(ot state | head -1))"
      else
        echo "otbr-agent on wpan0, agent build without border routing ($(ot state | head -1))"
      fi ;;
    thread_router)
      # The role and the network's name only (the app says them plainly): never the key, PSKc or ids.
      if [ -s "$DIR/thread-dataset" ]; then
        r_=$(ot state | head -1)
        n_=$(ot networkname | head -1 | tr -cd 'A-Za-z0-9 ._+-' | cut -c1-16)
        w_=""; [ -n "$n_" ] && w_=", network \"$n_\""
        case "$r_" in
          router|leader|child) echo "joined as $r_$w_" ;;
          detached)            echo "detached: looking for network${n_:+ \"$n_\"}" ;;
          disabled|"")         echo "starting Thread$w_" ;;
          *)                   echo "Thread state $(printf '%s' "$r_" | tr -cd 'a-z')$w_" ;;
        esac
      else echo "ready to join: waiting for a Thread dataset from Home Assistant"; fi ;;
    zigbee_tcp_thread) echo "EZSP on tcp:$ZIGBEE_PORT; otbr-agent on wpan0" ;;
  esac
}

# A Thread router network is stored (what "Leave Thread network" deletes), for the app's Leave
# button in every mode: sys.glassworks.radio.thread_stored = yes|no. Only whether, never what.
thread_stored() {
  if [ -s "$DIR/thread-dataset" ] || [ -e "$DIR/thread-router" ]; then t_=yes; else t_=no; fi
  [ "$t_" = "$TSTORED" ] && return 0
  TSTORED=$t_; setprop sys.glassworks.radio.thread_stored "$t_"
}
TSTORED=""

PUB_DIR=${GWA_PUB:-/data/glassworks-pub} # as glassworks_loader.sh: root 0755, app-readable

# --- after the boot writer's restart (#139, route 2) ------------------------------------------------------
# glassworks_bootwriter.sh (from a 0.4.3 panel's radio bundle) wrote this boot image and restarted once, leaving
# $BW_DIR/state = "written|restarting <backup sha> <new sha> <bundle version> <radio mode>". This boot is the first
# with the loader, so this agent checks what the writer promised: the loader ran (ro.glassworks.frame.id), the
# slots are up (glassworks_agent, glassworks_netwatch), boot still holds the image written, and the radio mode
# carried over to persist.glassworks.radio_mode. The result goes to $PUB_DIR/boot-writer, as the writer's own
# lines did, for Manage's Device page: "ok|error <epoch> <detail>". Once: the state becomes "checked".
BW_DIR=$DIR/boot-writer
bw_pub() {
  { [ -d "$PUB_DIR" ] || { mkdir -p "$PUB_DIR" && chown root:root "$PUB_DIR" && chmod 755 "$PUB_DIR"; }; } || return 0
  printf '%s %s %s\n' "$1" "$(date +%s)" "$2" > "$PUB_DIR/boot-writer.tmp" && chmod 644 "$PUB_DIR/boot-writer.tmp" &&
    mv -f "$PUB_DIR/boot-writer.tmp" "$PUB_DIR/boot-writer"
  say "boot writer: $1: $2"
}
bw_check() {
  set -- $(cat "$BW_DIR/state" 2>/dev/null)
  case "${1:-}" in written|restarting) ;; *) return 0 ;; esac
  want_sha=$3; want_mode=$5; [ "$want_mode" = - ] && want_mode=""
  ( i_=0
    while :; do
      why_=""
      if [ "$(getprop ro.glassworks.frame.id)" != aqara-s1-plus ]; then why_="the loader didn't run"
      else
        for s_ in glassworks_agent glassworks_netwatch; do
          [ "$(getprop init.svc.$s_)" = running ] || { why_="$s_ isn't running"; break; }
        done
      fi
      [ -z "$why_" ] && break
      i_=$((i_ + 1)); [ $i_ -ge ${GWA_BW_TRIES:-60} ] && break   # 5 min
      sleep ${GWA_BW_EVERY:-5}
    done
    if [ -z "$why_" ]; then
      m_=$(getprop persist.glassworks.radio_mode)
      [ "$m_" = "$want_mode" ] || why_="the radio mode is '${m_:-off}', was '${want_mode:-off}' before the write"
    fi
    if [ -z "$why_" ]; then
      dev_=${GWA_BOOTDEV:-/dev/block/by-name/boot}
      set -- $(dd if="$dev_" bs=1048576 2>/dev/null | sha256sum)
      [ "$1" = "$want_sha" ] || why_="boot no longer holds the image written"
    fi
    if [ -z "$why_" ]; then
      bw_pub ok "the frame loader is in boot: it ran, the services are up, radio mode ${want_mode:-off} kept"
      r_=ok
    else bw_pub error "after the restart: $why_"; r_=error; fi
    echo "checked $r_" > "$BW_DIR/state.tmp" && chmod 600 "$BW_DIR/state.tmp" && mv -f "$BW_DIR/state.tmp" "$BW_DIR/state"
  ) &
}
BT_DONE=""; BT_NEXT=0
bt_valid() { # AA:BB:CC:DD:EE:FF, and not a placeholder Android hands out
  h_='[0-9A-Fa-f][0-9A-Fa-f]'
  case "$1" in $h_:$h_:$h_:$h_:$h_:$h_) ;; *) return 1 ;; esac
  case "$(echo "$1" | tr a-f A-F)" in 00:00:00:00:00:00|02:00:00:00:00:00|FF:FF:FF:FF:FF:FF) return 1 ;; esac
  return 0
}
bt_publish() {
  [ -n "$BT_DONE" ] && return 0
  now_=$(date +%s); [ "$now_" -lt "$BT_NEXT" ] && return 0
  BT_NEXT=$((now_ + ${GWA_BT_EVERY:-60}))
  # Bounded: this runs before enforce, so a `settings` that never returns must not stall supervision.
  # Its own exit status decides: what a failed or timed-out lookup printed is dropped.
  a_=$(timeout ${GWA_BT_TIMEOUT:-10} settings get secure bluetooth_address 2>/dev/null) || a_=""
  a_=$(printf '%s' "$a_" | tr -d '\r')
  bt_valid "$a_" || return 0
  a_=$(echo "$a_" | tr a-f A-F)
  if [ -L "$PUB_DIR" ] || { [ -e "$PUB_DIR" ] && [ ! -d "$PUB_DIR" ]; }; then rm -rf "$PUB_DIR"; fi
  mkdir -p "$PUB_DIR" && chown root:root "$PUB_DIR" && chmod 755 "$PUB_DIR" || return 0
  if [ -L "$PUB_DIR/bluetooth-address" ] || [ "$(cat "$PUB_DIR/bluetooth-address" 2>/dev/null)" != "$a_" ]; then
    rm -f "$PUB_DIR/.bluetooth-address.tmp" "$PUB_DIR/bluetooth-address"
    { echo "$a_" > "$PUB_DIR/.bluetooth-address.tmp" && chmod 644 "$PUB_DIR/.bluetooth-address.tmp" &&
      mv -f "$PUB_DIR/.bluetooth-address.tmp" "$PUB_DIR/bluetooth-address"; } || return 0
  fi
  BT_DONE=1; say "bluetooth address published for the app"
}

# --- computers allowed over USB adb (Manage's Security page) -----------------------------------------
# adbd lets in a computer whose key is in $ADB_KEYS. Any other computer's key goes to Android, which logs
# "Received public key: <key>" (AdbDebuggingManager, system log) and puts "Allow USB debugging?" on the screen.
# Manage answers that question instead, for an administrator signed in with Home Assistant (read at
# android10-release: frames/aqara-s1-plus/research/adb-keys.md):
# - The asking computer: a background loop follows that log line. It takes only lines from system_server's pid,
#   because any app may log under any tag, and a key whose fingerprint is already in $ADB_KEYS isn't asking. It
#   publishes $PUB_DIR/adb-asking = "<epoch> <fingerprint> <key line>" (root 0644). A key is public; the
#   fingerprint is the MD5 of the decoded key, as Android's dialog shows it.
# - The list: $PUB_DIR/adb-keys = one "<installer|added> <fingerprint> <key line>" per key in $ADB_KEYS, where
#   installer marks the boot image's own key (glassworks_adb_keys, the computer that installed Glassworks).
# - The app writes "<id> allow|once|remove <fingerprint>" to its files/adb/request (taken with --move, at most 2
#   minutes old). allow and once take only the computer asking now, by its fingerprint: the app can't name a key of
#   its own. allow answers Android's question with IAdbManager.allowDebugging(true, key) (`service call adb 1`, as
#   root), which lets the computer in at once, and makes sure the key is in $ADB_KEYS. once answers it with
#   allowDebugging(false, key): this connection only, nothing written, so it asks again next time. remove takes a
#   key out of $ADB_KEYS, the installer's too. The answer goes to $PUB_DIR/adb-result = "<id> <ok|error> <detail>".
# - The installer's key comes back: at every start, a boot image key missing from $ADB_KEYS (Android dropped it, see
#   below) is put back, unless an administrator removed it on Manage. That removal is kept in $ADB_REVOKED (root
#   0600, one fingerprint a line) until the same computer is allowed again.
# - Android 10 forgets a key 7 days after its computer last connected (adb_allowed_connection_time, rewriting
#   $ADB_KEYS without it). The list on Manage is the record, so the loop sets that to 0 (never), as Android 11's
#   "Disable adb authorization timeout" does. tools/install.sh --undo deletes the setting.
# Status: sys.glassworks.adb.status = "ok" | "starting" | "error <detail>". The app shows the card when it's set.
ADB_KEYS=${GWA_ADB_KEYS:-/data/misc/adb/adb_keys}
ADB_STATUS=sys.glassworks.adb.status
APP_ADB_REQ=adb/request                 # app -> agent, relative to the app's files/
ADB_ASK=$DIR/adb-asking                 # root 0600: the computer asking, as the follower saw it
ADB_REVOKED=$DIR/adb-revoked            # root 0600: installer keys an administrator removed on Manage
ADB_ONCE=$DIR/adb-once                  # root 0600: "<epoch> <fingerprint>", the last question answered "once"
ADB_TTL=${GWA_ADB_TTL:-600}             # how long a question counts (Android keeps it until the next one)
ADB_PID=""; ADB_LISTED=""; ADB_WHY=""
adb_say() { [ "$1" = "$(getprop $ADB_STATUS)" ] && return 0; setp $ADB_STATUS "$1"; say "adb: $1"; }
adb_fp() { # key line -> its fingerprint (AA:BB:..., 16 pairs), or 1 when it isn't a key adb makes (524 bytes)
  b_=${1%% *}
  case "$b_" in ''|*[!A-Za-z0-9+/=]*) return 1 ;; esac
  [ ${#b_} = 700 ] || return 1
  [ $(( $(printf '%s' "$b_" | base64 -d 2>/dev/null | wc -c) + 0 )) -eq 524 ] || return 1
  h_=$(printf '%s' "$b_" | base64 -d 2>/dev/null | md5sum) || return 1
  h_=${h_%% *}; [ ${#h_} = 32 ] || return 1
  printf '%s\n' "$h_" | tr a-f A-F | sed 's/../&:/g; s/:$//'
}
adb_line_ok() { # a key line as adb writes it: the key, then an optional printable name of up to 64 bytes
  case "$1" in *[!\ -~]*) return 1 ;; esac
  ln_=${1#* }; [ "$ln_" = "$1" ] && return 0
  [ ${#ln_} -le 64 ]
}
adb_has() { # fingerprint -> 0 when a key with it is in $ADB_KEYS
  [ -n "$1" ] || return 1
  [ -f "$ADB_KEYS" ] || return 1
  while IFS= read -r hk_ || [ -n "$hk_" ]; do [ "$(adb_fp "$hk_")" = "$1" ] && return 0; done < "$ADB_KEYS"
  return 1
}
adb_put() { # name: stdin to $PUB_DIR/<name>, root 0644, atomically (a planted symlink is replaced, not followed)
  if [ -L "$PUB_DIR" ] || { [ -e "$PUB_DIR" ] && [ ! -d "$PUB_DIR" ]; }; then rm -rf "$PUB_DIR"; fi
  { mkdir -p "$PUB_DIR" && chown root:root "$PUB_DIR" && chmod 755 "$PUB_DIR"; } || { cat > /dev/null; return 1; }
  rm -f "$PUB_DIR/.$1.tmp"
  cat > "$PUB_DIR/.$1.tmp" && chmod 644 "$PUB_DIR/.$1.tmp" && mv -f "$PUB_DIR/.$1.tmp" "$PUB_DIR/$1"
}
adb_installer() { # fingerprint -> 0 when it is the boot image's key
  [ -n "$1" ] || return 1
  [ -s "$BUNDLE/glassworks_adb_keys" ] || return 1
  while IFS= read -r ik_ || [ -n "$ik_" ]; do [ "$(adb_fp "$ik_")" = "$1" ] && return 0; done < "$BUNDLE/glassworks_adb_keys"
  return 1
}
adb_list() { # publishes $PUB_DIR/adb-keys when $ADB_KEYS changed (or [force])
  s_=$(stat -c %Y:%s:%i "$ADB_KEYS" 2>/dev/null)
  [ -z "$1" ] && [ "$s_" = "$ADB_LISTED" ] && [ -f "$PUB_DIR/adb-keys" ] && return 0
  # One line a computer: Android may write a key that's already there again (run 20d saw it twice).
  { [ -f "$ADB_KEYS" ] && sn_=" " && while IFS= read -r lk_ || [ -n "$lk_" ]; do
      adb_line_ok "$lk_" || continue
      f_=$(adb_fp "$lk_") || continue
      case "$sn_" in *" $f_ "*) continue ;; esac; sn_="$sn_$f_ "
      if adb_installer "$f_"; then echo "installer $f_ $lk_"; else echo "added $f_ $lk_"; fi
    done < "$ADB_KEYS"; } | adb_put adb-keys && ADB_LISTED=$s_
  # The computer asking got in some other way (Android's own dialog on the screen): it isn't asking any more.
  read -r a_ af_ rest_ < "$ADB_ASK" 2>/dev/null && adb_has "$af_" && { rm -f "$ADB_ASK"; : | adb_put adb-asking; }
  return 0
}
adb_write() { # file: replaces $ADB_KEYS with it, keeping the owner, mode and label adbd and Android read it with
  own_=$(stat -c %u:%g "$ADB_KEYS" 2>/dev/null); mode_=$(stat -c %a "$ADB_KEYS" 2>/dev/null)
  chown "${own_:-1000:2000}" "$1" && chmod "${mode_:-640}" "$1" && mv -f "$1" "$ADB_KEYS" || { rm -f "$1"; return 1; }
  restorecon "$ADB_KEYS" 2>/dev/null; return 0
}
adb_revoked() { [ -n "$1" ] && grep -qxF "$1" "$ADB_REVOKED" 2>/dev/null; }
adb_revoke() { # fingerprint add|drop: the record of installer keys removed on Manage
  { grep -vxF "$1" "$ADB_REVOKED" 2>/dev/null; [ "$2" = add ] && echo "$1"; } > "$ADB_REVOKED.tmp"
  chmod 600 "$ADB_REVOKED.tmp" && mv -f "$ADB_REVOKED.tmp" "$ADB_REVOKED"
}
adb_restore() { # once per start: boot image keys Android dropped from $ADB_KEYS go back, unless removed on Manage
  [ -s "$BUNDLE/glassworks_adb_keys" ] || return 0
  rs_=""
  while IFS= read -r bk_ || [ -n "$bk_" ]; do
    adb_line_ok "$bk_" || continue
    bf_=$(adb_fp "$bk_") || continue
    adb_has "$bf_" || adb_revoked "$bf_" || rs_="$rs_$bk_
"
  done < "$BUNDLE/glassworks_adb_keys"
  [ -n "$rs_" ] || return 0
  mkdir -p "${ADB_KEYS%/*}" && { [ -f "$ADB_KEYS" ] && while IFS= read -r ck_ || [ -n "$ck_" ]; do printf '%s\n' "$ck_"; done < "$ADB_KEYS"; printf '%s' "$rs_"; } > "$ADB_KEYS.gw.tmp" &&
    adb_write "$ADB_KEYS.gw.tmp" && say "adb: the installer's key is back in the adb key list" || say "adb: couldn't put the installer's key back"
}
adb_heard() { # one line of the follower's logcat (-v epoch): "<epoch>.<ms> <pid> <tid> <level> <tag>: <message>"
  case "$1" in *"Received public key: "*|*"Received connected key message: "*) ;; *) return 0 ;; esac
  l_=${1#"${1%%[! ]*}"}; t_=${l_%%.*}; l_=${l_#* }; l_=${l_#"${l_%%[! ]*}"}; p_=${l_%% *}
  case "$t_" in ''|*[!0-9]*) return 0 ;; esac
  [ "$p_" = "$(pidof system_server 2>/dev/null)" ] || return 0
  case "$1" in
    *"Received connected key message: "*)  # the computer that asked got in: no longer asking
      f_=$(adb_fp "$(printf '%s' "${1#*Received connected key message: }" | tr -d '\r')") || return 0
      read -r a_ af_ rest_ < "$ADB_ASK" 2>/dev/null && [ "$af_" = "$f_" ] && { rm -f "$ADB_ASK"; : | adb_put adb-asking; }
      return 0 ;;
  esac
  nk_=$(printf '%s' "${1#*Received public key: }" | tr -d '\r')
  adb_line_ok "$nk_" || return 0
  f_=$(adb_fp "$nk_") || return 0
  [ $(( $(date +%s) - t_ )) -le "$ADB_TTL" ] || return 0
  adb_has "$f_" && return 0
  # A question answered "once" stays answered when its line is read again; the computer's next connection logs anew.
  read -r ot_ of_ < "$ADB_ONCE" 2>/dev/null && [ "$of_" = "$f_" ] && [ "$t_" -le "$ot_" ] 2>/dev/null && return 0
  ( umask 077; printf '%s %s %s\n' "$t_" "$f_" "$nk_" > "$ADB_ASK.tmp" ) && mv -f "$ADB_ASK.tmp" "$ADB_ASK" || return 0
  printf '%s %s %s\n' "$t_" "$f_" "$nk_" | adb_put adb-asking
  say "adb: a computer is asking to connect over USB"
}
adb_timeout() { # Android forgets nothing by itself: adb_allowed_connection_time 0. 0 when it is, after this.
  v_=$(timeout ${GWA_ADB_SETTINGS_TIMEOUT:-10} settings get global adb_allowed_connection_time 2>/dev/null | tr -d '\r') || return 1
  [ "$v_" = 0 ] && return 0
  timeout ${GWA_ADB_SETTINGS_TIMEOUT:-10} settings put global adb_allowed_connection_time 0 >/dev/null 2>&1 || return 1
  [ "$(timeout ${GWA_ADB_SETTINGS_TIMEOUT:-10} settings get global adb_allowed_connection_time 2>/dev/null | tr -d '\r')" = 0 ] || return 1
  say "adb: Android's 7-day adb key timeout turned off (was ${v_:-unset})"
}
adb_start() { # the follower: the timeout, then Android's log, from $ADB_TTL ago, again whenever logcat ends
  adb_say starting   # the last question stays: it carries its own time ($ADB_TTL), and the follower replays it
  parent_=$$
  ( i_=0
    until adb_timeout; do
      i_=$((i_ + 1)); [ $i_ -ge ${GWA_ADB_TRIES:-20} ] && { adb_say "error Android's adb key timeout couldn't be turned off"; break; }
      sleep ${GWA_ADB_RETRY:-30}
    done
    [ $i_ -lt ${GWA_ADB_TRIES:-20} ] && adb_say ok
    # From $ADB_TTL ago, so a computer that asked before this start still shows. A logcat that won't take a time
    # (it ends at once) is run from its newest line instead, from then on.
    from_=time
    while kill -0 "$parent_" 2>/dev/null; do
      fs_=$(date +%s)
      if [ $from_ = time ]; then ft_="$((fs_ - ADB_TTL)).000"; else ft_=1; fi
      logcat -b system -v epoch -T "$ft_" -s AdbDebuggingManager:D 2>/dev/null |
        while IFS= read -r l_; do adb_heard "$l_"; done
      [ $(( $(date +%s) - fs_ )) -lt 5 ] && from_=newest
      sleep ${GWA_ADB_RETRY:-30}
    done ) &
  ADB_PID=$!
}
adb_stop() { alive "$ADB_PID" && { pkill -P "$ADB_PID" 2>/dev/null; kill "$ADB_PID"; }; ADB_PID=""; }
adb_answer() { # id ok|error detail
  printf '%s %s %s\n' "$1" "$2" "$3" | adb_put adb-result; say "adb: $2: $3"
}
adb_request() { # one request from the app, if there is one
  take_app_file "$APP_ADB_REQ" max:128 "$DIR/adb-request" move; r_=$?
  [ $r_ = 1 ] && return 0
  [ $r_ = 2 ] && { [ "$WHY" = "$ADB_WHY" ] || say "adb: request refused: $WHY"; ADB_WHY=$WHY; return 0; }
  ADB_WHY=""
  read -r id_ op_ fp_ rest_ < "$DIR/adb-request"; rm -f "$DIR/adb-request"
  case "$id_" in ''|*[!0-9a-z]*) say "adb: request refused: bad id"; return 0 ;; esac
  [ ${#id_} -le 32 ] || { say "adb: request refused: bad id"; return 0; }
  age_=$(( $(date +%s) - TAKE_MTIME ))
  [ $age_ -le 120 ] || { adb_answer "$id_" error "too old (${age_} s), ignored"; return 0; }
  case "$fp_" in [0-9A-F][0-9A-F]:*) ;; *) adb_answer "$id_" error "not a fingerprint"; return 0 ;; esac
  [ ${#fp_} = 47 ] && [ -z "$(printf '%s' "$fp_" | tr -d '0-9A-F:')" ] || { adb_answer "$id_" error "not a fingerprint"; return 0; }
  case "$op_" in
    allow) adb_allow "$id_" "$fp_" ;;
    once) adb_allow "$id_" "$fp_" once ;;
    remove) adb_remove "$id_" "$fp_" ;;
    *) adb_answer "$id_" error "unknown request" ;;
  esac
  adb_list force
}
adb_allow() { # id fingerprint [once]
  if ! read -r at_ af_ ak_ < "$ADB_ASK" 2>/dev/null; then adb_answer "$1" error "none-asking"; return 0; fi
  case "$at_" in ''|*[!0-9]*) adb_answer "$1" error "none-asking"; return 0 ;; esac
  [ $(( $(date +%s) - at_ )) -le "$ADB_TTL" ] || { adb_answer "$1" error "none-asking"; return 0; }
  [ "$af_" = "$2" ] || { adb_answer "$1" error "changed"; return 0; }
  adb_revoked "$2" && adb_revoke "$2" drop   # allowed again: the installer's key may come back again
  if adb_has "$2"; then rm -f "$ADB_ASK"; : | adb_put adb-asking; adb_answer "$1" ok "already"; return 0; fi
  if [ "$3" = once ]; then   # Android's plain "Allow": this connection only, and no line in adb_keys
    timeout 10 service call adb 1 i32 0 s16 "$ak_" >/dev/null 2>&1 || { adb_answer "$1" error "Android's allowDebugging call failed"; return 0; }
    ( umask 077; printf '%s %s\n' "$at_" "$2" > "$ADB_ONCE" )
    rm -f "$ADB_ASK"; : | adb_put adb-asking
    adb_answer "$1" ok "once"; return 0
  fi
  # Android's own "Always allow": it answers adbd now (if the computer still waits) and writes the key the first time
  # it sees one. Root holds every permission, MANAGE_DEBUGGING too. Its refusal (another computer asked) is silent.
  timeout 10 service call adb 1 i32 1 s16 "$ak_" >/dev/null 2>&1 || say "adb: Android's allowDebugging call failed"
  sleep ${GWA_ADB_SETTLE:-2}
  if ! adb_has "$2"; then   # a key Android saw before keeps no line (its keystore has it): ours, then
    mkdir -p "${ADB_KEYS%/*}" && { [ -f "$ADB_KEYS" ] && while IFS= read -r ck_ || [ -n "$ck_" ]; do printf '%s\n' "$ck_"; done < "$ADB_KEYS"; echo "$ak_"; } > "$ADB_KEYS.gw.tmp" && adb_write "$ADB_KEYS.gw.tmp" ||
      { adb_answer "$1" error "couldn't write the adb key list"; return 0; }
  fi
  if ! adb_has "$2"; then adb_answer "$1" error "the key isn't in the adb key list after writing it"; return 0; fi
  rm -f "$ADB_ASK"; : | adb_put adb-asking
  adb_answer "$1" ok "allowed"
}
adb_remove() { # id fingerprint
  adb_has "$2" || { adb_answer "$1" error "not-listed"; return 0; }
  # The installer's key stays out at later starts: recorded before the write, so a failed write can't lose it.
  if adb_installer "$2"; then adb_revoke "$2" add || { adb_answer "$1" error "couldn't record the removal"; return 0; }; fi
  while IFS= read -r rk_ || [ -n "$rk_" ]; do [ "$(adb_fp "$rk_")" = "$2" ] || printf '%s\n' "$rk_"; done < "$ADB_KEYS" > "$ADB_KEYS.gw.tmp" &&
    adb_write "$ADB_KEYS.gw.tmp" || { adb_answer "$1" error "couldn't write the adb key list"; return 0; }
  if adb_has "$2"; then adb_answer "$1" error "the key is still in the adb key list"; return 0; fi
  adb_answer "$1" ok "removed"
}

# --- main ------------------------------------------------------------------------------------
status applying
zstate
fw_init
portal_start
hn_apply
aa_apply     # adbd checks keys again (a Magisk module, from the next restart)
adb_restore  # the installer's key, if Android dropped it and nobody removed it on Manage
adb_start    # the computers allowed over USB adb: the follower, and Android's key timeout off
bw_check     # once, on the first boot after the boot writer's restart
[ -n "$GWA_ONCE" ] && LOOPS=$GWA_ONCE   # tests: run this many loops, then exit

while true; do
  fw_request   # an explicit firmware request runs first (and restarts the agent when done)
  thread_leave # "Leave Thread network", in every mode
  thread_stored
  adb_request  # Manage's Allow or Remove of a computer over USB adb, in every mode
  adb_list
  bt_publish   # the Bluetooth adapter's address for the app (#246), in every mode
  peer_report
  if [ -n "$FW_HOLD" ]; then
    # radio without a trusted application after an interrupted flash: everything stays off
    WANT=""; enforce; br_net_off; fw_off
    status error "radio firmware missing after an interrupted flash: flash Aqara stock"
    if [ -n "$LOOPS" ]; then LOOPS=$((LOOPS - 1)); [ $LOOPS -le 0 ] && { loops_stop; exit 0; }; fi
    sleep ${GWA_SLEEP:-3}; continue
  fi
  # Re-enforce every 3 s, in every mode incl. off: Aqara's boot sequence (zigbee_cpcd_factory ->
  # sys.gateway.config=10, sys.svc.gateway=5 -> ha_matter) and its watchdog (sys.gateway.config=11)
  # start the stock stack again; Z3Gateway's wrapper sleeps 6 s before opening the pty, so we stop it first.
  if enforce; then
    if children_ok; then
      FAILS=0
      if [ -n "$BLOCKED" ]; then
        status error "$BLOCKED"
      elif w=$(not_ready); then
        status applying "$w"
      elif [ "$MODE" = thread_router ] && ! thread_router_join; then
        :   # thread_router_join set the status and counts its failures across loops (JFAILS)
      else
        status ok "$(detail)"
        ZB_DEATHS=0
      fi
    else
      FAILS=$((FAILS+1)) # start_* set the status
    fi
  else
    FAILS=$((FAILS+1))
    status error "services not in wanted state:$bad"
  fi
  if [ $FAILS -gt 20 ]; then status error "giving up after repeated failures"; exec sleep 2147483647; fi
  if [ -n "$LOOPS" ]; then LOOPS=$((LOOPS - 1)); [ $LOOPS -le 0 ] && { loops_stop; exit 0; }; fi
  sleep ${GWA_SLEEP:-3}
done
