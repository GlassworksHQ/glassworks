#!/usr/bin/env bash
# Clarity, the Glassworks perf test: grade a frame (MIT). Docs: docs/perf.md.
#
# What it touches on the device:
#   - Shows the fake Home Assistant's reference dashboard as the kiosk's test dashboard for the graded part
#     (and --pane-ram): `--es test_dashboard <url>` with the test hold. The kiosk shows it instead of its own
#     dashboard until `--es test_dashboard ''` after the part, on any exit, or when the hold ends. Its
#     dashboard setting and its sign-in to Home Assistant don't change.
#   - Opens the SurfaceFlinger check's page (#643), the test page and the reference dashboards with the kiosk's
#     one-off `--es open` extra.
#     The pages are served by the fake Home Assistant on this machine over `adb reverse` (with --ha-transport lan:
#     over the LAN instead, below).
#   - With --pane-ram only: turns the switchable panes off and on with the kiosk's own extras
#     (`--ez voice_enabled`, `--ez sendspin_enabled`, `--ez wall_base_enabled`, `--ez time_zone_enabled`,
#     `--ez diagnostics_enabled`), restarts the app after each change (`--ez restart true`), and puts
#     them back to the given settings on exit.
#   - Sets the kiosk's test hold (`--ez test_hold true --ei test_hold_s 1800`, #240) at the start,
#     before each part and each view, and ends it on exit (`--ez test_hold false`): the screen stays
#     on past the idle timeout, at the brightness it would have anyway, without changing a setting.
#     The hold outlives a restart of the app and expires by itself.
#     Refuses to run if the kiosk doesn't say in its log that it took the hold.
#   - Sends Android's wake key (`input keyevent KEYCODE_WAKEUP`) through each restart, while the app
#     is down or starting.
#   - Taps each reference dashboard's "Living room" toggle (`input tap`) and swipes it (`input swipe`),
#     only where and when the page asks, to measure taps and scrolling.
#   - Force-stops the app for each cold start (KEYCODE_WAKEUP first, so the screen is on); the app
#     comes back on the saved dashboard. If the run stops early or the last cold start fails, the
#     exit trap restarts the app.
#   - On Android 6 only (no `logcat -v monotonic`): five `ClarityClock` lines in the device's log per runner
#     step, to tie logcat's wall clock to the monotonic one (docs/perf.md, "Other Glassworks apps, and Android 6").
#   - With --engine vitro|web: Settings → Labs → Vitro set for the run (`--ez vitro true|false`, which restarts the
#     app), and put back as it was on exit (restarting the app again). Without it, the engine the kiosk says it runs.
#     On Vitro, the animation test page and your own dashboard's restarts are skipped (they time GeckoView's page).
#   Read-only otherwise: getprop, /proc, dumpsys meminfo, SurfaceFlinger and activity recents (the app's
#   activity), logcat.
#   On this computer: 40 pings to its default gateway and 40 to the panel before the graded part (netcheck.py,
#   #649: a stalling link of this computer's makes the report provisional); a Python venv for the fake's WebRTC peer (aiortc, from webrtc-requirements.txt,
#   locked with hashes) in ~/.cache/glassworks-perf, and two `adb reverse` ports (--port and the
#   WebRTC relay's, --port + 1), removed on exit. With --ha-transport lan, no `adb reverse` at all: the fake listens
#   on this computer's LAN address (the one the camera video uses, webrtc.media_address), on that address only and
#   only for the run, and the panel reaches it over the network.
#
# Usage: tools/perf/run.sh --frame aqara-s1-plus [--name "Aqara S1 Plus"] [--serial S] [--runs 3] [--out DIR]
#          [--package sh.edm.s1kiosk] [--activity A]   # the app; its activity is read from the device unless given
#          [--pane-ram assist=on,sendspin=on,mic=on]
#          [--skip-synthetic] [--skip-reference] [--skip-real-use] [--taps 10] [--quick] [--apk FILE]...
#          [--webrtc-transport auto|adb|lan-turn|lan-udp]   # the camera video's path; auto (default) asks the panel
#          [--sf-poll auto|0|SECONDS] [--sf-check-only]     # how SurfaceFlinger is read (below)
#          [--engine web|vitro]                             # the engine to grade (default: the one the kiosk runs)
#          [--ha-transport adb|lan|auto] [--ha-over-lan]    # how the panel reaches the fake Home Assistant (below)
#
# The fake Home Assistant (docs/perf.md, "Home Assistant over the LAN"): with --ha-transport adb (the default) the panel
# reaches it through `adb reverse`, on the device's 127.0.0.1. With lan (--ha-over-lan says the same) it reaches it on
# this computer's LAN address, for a device whose `adb reverse` fails (the lululemon Mirror's Android 6 over network adb:
# "more than one device/emulator"). auto tries `adb reverse` and takes lan only on that error. Over the LAN the live
# updates cross this computer's network: netcheck.py's check counts them, and the report says how they went.
# Camera video (protocol 7, #202): with --webrtc-transport auto, lanprobe.py first has the panel send one
# UDP datagram and open one TCP connection to this computer's LAN address (two `toybox nc` over adb
# shell: connections from the panel, nothing written on it), and picks lan-udp, lan-turn or adb.
#        tools/perf/run.sh --frame ID --out DIR --idle-only LABEL   # one idle sample (pane diffs)
#
# SurfaceFlinger (#643): before the test page and the graded part, `perfrun.py sfcheck` opens a page that draws
# every frame and measures how far back one `dumpsys SurfaceFlinger --latency` read reaches. Where that covers a
# window (the S1: 2.1 s), the runner reads once after each window; where it doesn't (the Pixel Tablet: 1 s), it
# reads during each window too and joins the reads. If not even that can cover a window, the run stops there
# (exit 2, sfcheck.json). --sf-poll 0 or SECONDS skips the check and reads that way; --sf-check-only runs the
# checks before a run (this one and netcheck.py's) and stops.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-adb}"
FRAME="" NAME="" RUNS=3 OUT="" PORT=8089 TURN_PORT="" WEBRTC_TRANSPORT=auto PACKAGE=sh.edm.s1kiosk ACTIVITY="" TAPS=10 QUICK=0
SYNTH=1 REF=1 REAL=1 IDLE_ONLY="" MAX_TEMP_C=50 APKS=() PANE_RAM="" SF_POLL=auto SF_CHECK_ONLY=0 ENGINE=""
# How the panel reaches the fake Home Assistant (adb, lan or auto), and the host its pages are on for the panel.
HA_TRANSPORT=adb HA_HOST=127.0.0.1 HA_URL="" TESTDASH_LAN_HINT=""
# Waits, in seconds. Only the off-device test (test/test_runner.py) shortens them.
IDLE_SETTLE="${GW_PERF_IDLE_SETTLE:-30}" IDLE_WINDOW="${GW_PERF_IDLE_WINDOW:-15}"
COLD_SETTLE="${GW_PERF_COLD_SETTLE:-3}" COLD_TIMEOUT="${GW_PERF_COLD_TIMEOUT:-120}" RUN_GAP="${GW_PERF_RUN_GAP:-10}"
BETWEEN_QUIET="${GW_PERF_BETWEEN_QUIET:-2}" SWITCH_SETTLE="${GW_PERF_SWITCH_SETTLE:-10}"

while [ $# -gt 0 ]; do
  case "$1" in
    --frame) FRAME="$2"; shift 2 ;;
    --name) NAME="$2"; shift 2 ;;
    --serial) export ANDROID_SERIAL="$2"; shift 2 ;;
    --runs) RUNS="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --package) PACKAGE="$2"; shift 2 ;;
    --activity) ACTIVITY="$2"; shift 2 ;;
    --taps) TAPS="$2"; shift 2 ;;
    --quick) QUICK=1; shift ;;
    --skip-synthetic) SYNTH=0; shift ;;
    --skip-reference) REF=0; shift ;;
    --skip-real-use) REAL=0; shift ;;
    --idle-only) IDLE_ONLY="$2"; shift 2 ;;
    --max-start-temp) MAX_TEMP_C="$2"; shift 2 ;;
    --apk) APKS+=(--apk "$2"); shift 2 ;;
    --pane-ram) PANE_RAM="$2"; shift 2 ;;
    --webrtc-transport) WEBRTC_TRANSPORT="$2"; shift 2 ;;
    --sf-poll) SF_POLL="$2"; shift 2 ;;
    --sf-check-only) SF_CHECK_ONLY=1; shift ;;
    --engine) ENGINE="$2"; shift 2 ;;
    --ha-transport) HA_TRANSPORT="$2"; shift 2 ;;
    --ha-over-lan) HA_TRANSPORT=lan; shift ;;
    -h|--help) sed -n '2,/^set -e/p' "$0" | grep '^#' | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$FRAME" ] || { echo "--frame is required (e.g. aqara-s1-plus)" >&2; exit 2; }
[ "$RUNS" -ge 1 ] || { echo "--runs must be at least 1" >&2; exit 2; }
# The check alone needs only the fake Home Assistant's pages: no cameras, no WebRTC peer, no LAN probe.
[ "$SF_CHECK_ONLY" = 1 ] && { SYNTH=1 REF=0 REAL=0 PANE_RAM=""; }
[ "$SF_CHECK_ONLY" = 1 ] && [ -n "$IDLE_ONLY" ] && { echo "--sf-check-only and --idle-only can't be combined: one runs the checks, the other one idle sample" >&2; exit 2; }
[ "$SF_CHECK_ONLY" = 1 ] && [ "$SF_POLL" != auto ] && { echo "--sf-check-only runs the check: leave --sf-poll at auto" >&2; exit 2; }
[ -z "$ENGINE" ] || [ "$ENGINE" = web ] || [ "$ENGINE" = vitro ] || { echo "--engine is web or vitro" >&2; exit 2; }
[ "$SF_POLL" = auto ] || [[ "$SF_POLL" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "--sf-poll is auto, 0 or seconds between reads" >&2; exit 2; }
case "$HA_TRANSPORT" in adb|lan|auto) ;; *) echo "--ha-transport is adb, lan or auto" >&2; exit 2 ;; esac
[ "$HA_TRANSPORT" = lan ] && [ "$WEBRTC_TRANSPORT" = adb ] && {
  echo "--webrtc-transport adb goes through adb reverse, which --ha-transport lan doesn't use: lan-turn, lan-udp or auto" >&2; exit 2; }
TURN_PORT=$((PORT + 1))
OUT="${OUT:-perf-$FRAME-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
export ADB

run() { python3 "$HERE/perfrun.py" "$@" --package "$PACKAGE" ${ACTIVITY:+--activity "$ACTIVITY"} --frame "$FRAME" --out "$OUT"; }
sh_dev() { "$ADB" shell "$@" | tr -d '\r'; }
log() { echo "[$(date +%H:%M:%S)] $*" >&2; }

thermal() { # "<phase>\t<zone>=<°C> ..." into thermal.tsv; zones report m°C or °C
  local t
  t=$(sh_dev 'for z in /sys/class/thermal/thermal_zone*; do echo "$(cat $z/type) $(cat $z/temp)"; done 2>/dev/null' \
    | awk 'NF == 2 { v = $2 > 1000 ? $2 / 1000 : $2; printf "%s=%.1f ", $1, v }')
  printf '%s\t%s\n' "$1" "$t" >> "$OUT/thermal.tsv"
}

cpu_temp_c() {
  sh_dev 'for z in /sys/class/thermal/thermal_zone*; do case $(cat $z/type) in *cpu*) cat $z/temp;; esac; done 2>/dev/null' \
    | awk 'NF { t = $1 > 1000 ? $1 / 1000 : $1; if (t > m) m = t } END { print int(m) }'
}

wait_cool() {
  local i t
  for i in $(seq 1 20); do
    t=$(cpu_temp_c)
    [ "${t:-0}" -le "$MAX_TEMP_C" ] && { log "CPU ${t} °C"; return 0; }
    log "CPU ${t} °C > ${MAX_TEMP_C} °C; waiting 30 s ($i/20)"
    sleep 30
  done
  log "still above ${MAX_TEMP_C} °C after 10 min; going on (recorded in thermal.tsv)"
}

"$ADB" get-state >/dev/null || { echo "no device" >&2; exit 1; }
# Checks of a command's output match a pattern, not `| grep -q`: grep exits at its match, and under
# pipefail the writer's SIGPIPE (141) then fails the check (2026-11-15: a restore that worked, exit 3).
[[ $(sh_dev pm path "$PACKAGE") == *package:* ]] || { echo "$PACKAGE is not installed" >&2; exit 1; }
# GW_PERF_TIME_SCALE (clock.py) is for the off-device tests: refused on a real device here, before
# anything else on it is touched. Without it, this asks the device nothing.
python3 "$HERE/clock.py" --check || exit 2
# The activity that takes the kiosk's extras (perfrun.py Device.resolve_activity): read once from the
# device, unless --activity names it. The S1's is .MainActivity, the generic app's dev.glassworks.MainActivity.
if [ -z "$ACTIVITY" ]; then
  COMPONENT=$(run activity) || exit 2
  ACTIVITY=${COMPONENT#*/}
fi
# The kiosk's test hold keeps the screen on for the run (#240). An app without it ignores the extra,
# and its screen would sleep partway: no fallback.
release_hold() { run hold --off >/dev/null 2>&1 || true; }
HOLD_OUT=$(run hold) || {
  echo "$PACKAGE didn't take Clarity's test hold (--ez test_hold true): install an app built from main after #240" >&2
  exit 2
}
# The engine to grade (docs/perf.md, "Vitro"): --engine switches Settings → Labs → Vitro for the run and puts it back on
# exit; without it, the one the kiosk said it runs (web when it doesn't say). ENGINE_WAS: the setting to put back.
ENGINE_WAS=""
restore_engine() {
  [ -n "$ENGINE_WAS" ] || return 0
  local want=web
  [ "$ENGINE_WAS" = true ] && want=vitro
  log "putting Settings → Labs → Vitro back ($ENGINE_WAS; the app restarts)"
  if run engine --want "$want" --settle "$SWITCH_SETTLE" >/dev/null; then
    ENGINE_WAS=""
  else
    log "COULD NOT put Vitro back; send it yourself: adb shell am start -n $PACKAGE/$ACTIVITY --ez vitro $ENGINE_WAS"
  fi
}
# A setting that couldn't be put back fails the run (exit 3), as a dashboard that couldn't be does.
trap 'restore_engine; release_hold; [ -z "$ENGINE_WAS" ] || exit 3' EXIT
SAID_ENGINE=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("engine") or "")' "$HOLD_OUT")
if [ -n "$ENGINE" ] && [ "$ENGINE" != "${SAID_ENGINE:-web}" ]; then
  # The setting as it was, before anything is sent: a switch that fails after the kiosk saved it is put back too.
  ENGINE_WAS=$(python3 -c 'import json,sys; v = json.loads(sys.argv[1]).get("vitro_setting"); print("" if v is None else str(v).lower())' "$HOLD_OUT")
  log "switching the kiosk to $ENGINE (Settings → Labs → Vitro; the app restarts)"
  ENG_OUT=$(run engine --want "$ENGINE" --settle "$SWITCH_SETTLE") || {
    echo "$PACKAGE didn't switch to $ENGINE: install an app built after Clarity's Vitro check (docs/perf.md, \"Vitro\")" >&2
    exit 2
  }
  # Nothing to put back when the kiosk already had the setting asked for.
  python3 -c 'import json,sys; sys.exit(0 if json.loads(sys.argv[1])["changed"] else 1)' "$ENG_OUT" || ENGINE_WAS=""
fi
ENGINE="${ENGINE:-${SAID_ENGINE:-web}}"
export GW_PERF_ENGINE="$ENGINE"
if [ "$ENGINE" = vitro ]; then
  # Vitro draws no web page: the animation test page and your own dashboard's restart time GeckoView's page loads.
  if [ "$SF_CHECK_ONLY" != 1 ]; then
    [ "$SYNTH" = 1 ] && log "Vitro: skipping the animation test page (it times a web page; detail, not graded)"
    SYNTH=0
  fi
  [ "$REAL" = 1 ] && log "Vitro: skipping your dashboard's restarts (they time a web page load; detail, not graded)"
  REAL=0
fi

if [ -n "$IDLE_ONLY" ]; then
  # No check for one sample: the --sf-poll given, or one read at a time (#643).
  [ "$SF_POLL" = auto ] && SF_POLL=0
  export GW_PERF_SF_POLL="$SF_POLL"
  n=0
  for f in "$OUT"/idle-"$IDLE_ONLY"-*.json; do [ -e "$f" ] && n=$((n + 1)); done
  [ -f "$OUT/device.json" ] || run device ${NAME:+--name "$NAME"}
  thermal "idle-$IDLE_ONLY-$n"
  log "idle sample '$IDLE_ONLY' #$n"
  run idle --label "$IDLE_ONLY" --run "$n" --settle "$IDLE_SETTLE" --window "$IDLE_WINDOW"
  python3 "$HERE/report.py" "$OUT" --keep-apk ${APKS[@]+"${APKS[@]}"} >/dev/null
  log "report: $OUT/report.json"
  exit 0
fi

# A full run starts from an empty directory, so no earlier run's files end up in its report.
for f in "$OUT"/synthetic-*.jsonl "$OUT"/refstart-*.json "$OUT"/coldstart-*.json "$OUT"/idle-*.json; do
  [ -e "$f" ] && { echo "$OUT already holds a run ($(basename "$f")); use a new --out" >&2; exit 2; }
done

# DASHBOARD_OK=1 while the panel shows the saved dashboard as the runner found it. It clears before
# anything that leaves it (the test page, a force-stop), and only a cold start that loaded the
# dashboard sets it again. On exit, the app is restarted unless it is set; that is harmless if the
# dashboard was up after all.
SERVER_PID="" DASHBOARD_OK=1 SWITCHED=0 PANES_SWITCHED=0 PANES=""
# Protocol 4: the graded part shows the reference dashboard's start view on the fake Home Assistant as the
# kiosk's test dashboard (perfrun.py testdash), so a restart comes back to it. Not its saved dashboard: that
# moved the panel's Home Assistant address, and since #496 the panel then asks to sign in again. The test
# dashboard ends after the part, and here on any exit (exit status 3 if that failed); the hold's end ends
# it too. --pane-ram also turns the panes off and on; perfrun.py panes puts them back and checks the
# kiosk's log that they took. The runner can't read the pane settings itself (it never reads app data).
pane_set() { # the pane extras (extra=true|false,...) for: one pane on by itself (its id), "none", or "restore"
  awk -v s="$1" '$1 == "STATE" && $2 == s { print $3 }' <<< "$PANES"
}
point_back() {
  local out set=""
  [ "$PANES_SWITCHED" = 1 ] && set=$(pane_set restore)
  if [ "$SWITCHED" = 1 ] || [ "$PANES_SWITCHED" = 1 ]; then
    log "back to the kiosk's own dashboard${set:+ and its panes ($PANE_RAM)}"
    if [ -n "$set" ]; then
      DASHBOARD_OK=0
      # The panes and the dashboard together, checked in the kiosk's log (perfrun.py panes): the
      # live settings first, then voice_enabled and a restart. Retried if one didn't take.
      if out=$(run panes --set "$set" --url '' --settle "$SWITCH_SETTLE" --tries 3 2>&1); then
        SWITCHED=0 PANES_SWITCHED=0
        if printf '%s\n' "$out" | tail -n 1 | grep -q '"voice_applied": false'; then
          log "voice_enabled didn't take (this kiosk saves it asynchronously before it restarts); it is as it was"
        fi
      else
        log "COULD NOT put the panes back: $(printf '%s' "$out" | tail -n 1)"
        log "send it yourself: adb shell am start -n $PACKAGE/$ACTIVITY --es test_dashboard \"''\" $(printf '%s' "$set" | sed 's/\([a-z_]*\)=\([a-z]*\)/--ez \1 \2/g; s/,/ /g')"
      fi
      return 0
    fi
    # perfrun.py testdash --off reads am's answer: am exits 0 even when the kiosk didn't take the intent.
    if run testdash --off; then
      SWITCHED=0
      # Let the kiosk load its dashboard (the legacy engine restarts for it) before anything else restarts it.
      sleep "$SWITCH_SETTLE"
    else
      log "COULD NOT end the test dashboard"
      log "send it yourself: adb shell am start -n $PACKAGE/$ACTIVITY --es test_dashboard \"''\""
    fi
  fi
}
restored() { [ "$SWITCHED" = 0 ] && [ "$PANES_SWITCHED" = 0 ]; }
cleanup() {
  local status=$?
  point_back
  if ! restored; then
    point_back                     # once more; then say so in the exit status
  fi
  if [ "$DASHBOARD_OK" != 1 ] && restored; then
    log "restoring the dashboard (restart the app)"
    sh_dev am force-stop "$PACKAGE" || true
    sh_dev am start -n "$PACKAGE/$ACTIVITY" >/dev/null || true
  fi
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  # Over the LAN no reverse was made, and none is removed (another tool's on this device stays).
  if [ "$HA_TRANSPORT" != lan ]; then
    "$ADB" reverse --remove "tcp:$PORT" 2>/dev/null || true
    "$ADB" reverse --remove "tcp:$TURN_PORT" 2>/dev/null || true
  fi
  restore_engine
  release_hold
  restored || exit 3
  [ -z "$ENGINE_WAS" ] || exit 3     # Vitro's setting wasn't put back (restore_engine said how to)
  exit "$status"
}
trap cleanup EXIT

if [ -n "$PANE_RAM" ]; then
  # "<id>=on|off,…" for every pane the frame can switch (frames/<id>/budgets.json or its base
  # frame's, `switch`), and mic=on|off when one has a microphone mute (`mute`): what to put back.
  # Out: one "STATE <none|id|restore> extra=true|false,..." line per state the runner sets.
  PANES=$(python3 - "$HERE" "$FRAME" "$PANE_RAM" <<'PY'
import json, os, re, sys
here, frame, spec = sys.argv[1:]
sys.path.insert(0, here)
from perflib import frame_budgets_path
path = frame_budgets_path(os.path.join(here, "..", "..", "frames"), frame)
panes = json.load(open(path))["panes"] if path else {}
switch = {k: v for k, v in panes.items() if v.get("switch")}
if not switch:
    sys.exit(f"--pane-ram: {path or f'frames/{frame}'} names no pane with a switch")
keys = set(switch) | ({"mic"} if any(v.get("mute") for v in switch.values()) else set())
got = {}
for part in spec.split(","):
    m = re.fullmatch(r"([a-z0-9-]+)=(on|off)", part)
    if not m or m.group(1) not in keys:
        sys.exit(f"--pane-ram: {part!r} isn't <name>=on|off for one of {', '.join(sorted(keys))}")
    got[m.group(1)] = m.group(2) == "on"
if set(got) != keys:
    sys.exit(f"--pane-ram needs the setting now of: {', '.join(sorted(keys))} (mic: the microphone, on unless muted)")
def state(on):
    out = []
    for pid in sorted(switch):
        p = switch[pid]
        v = got[pid] if on == "restore" else pid == on
        out.append(f"{p['switch']}={'true' if v else 'false'}")
        if p.get("mute"):
            muted = (not got["mic"]) if on == "restore" else not v
            out.append(f"{p['mute']}={'true' if muted else 'false'}")
    return ",".join(out)
for name in ["none"] + sorted(switch) + ["restore"]:
    print("STATE", name, state(name))
PY
) || exit 2
fi

run device ${NAME:+--name "$NAME"}
thermal start
VSYNC=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["display"]["vsync_ms"] or 16.667)' "$OUT/device.json")
log "device recorded; vsync ${VSYNC} ms"
wait_cool

if [ "$SYNTH" = 1 ] || [ "$REF" = 1 ] || [ -n "$PANE_RAM" ]; then
  # The fake Home Assistant (fakeha.py) serves HA's pinned frontend, the reference dashboards and
  # the animation test page (under /clarity/page/). The first use fetches the frontend (117 MB).
  python3 "$HERE/hafrontend.py" >/dev/null
  # The reference dashboards' camera streams (#174): HLS segments made once with ffmpeg (about 40 s).
  # Only the reference dashboards play them (the graded part, its idle samples and --pane-ram); the
  # animation test page alone doesn't need ffmpeg.
  if [ "$REF" = 1 ] || [ -n "$PANE_RAM" ]; then
    python3 "$HERE/camera.py" >/dev/null || { echo "Clarity's camera streams need ffmpeg with libx264 (camera.py)" >&2; exit 2; }
  fi
  # Protocol 6 (#202): the fake answers HA's WebRTC offers with aiortc, from a venv of
  # webrtc-requirements.txt (hashes checked, wheels only; made once, about 93 MB on macOS, 2026-11-14).
  # The animation test page alone plays no camera: plain python3 then.
  FAKE_PY="${GW_PERF_FAKE_PYTHON:-}"
  if [ -z "$FAKE_PY" ] && { [ "$REF" = 1 ] || [ -n "$PANE_RAM" ]; }; then
    FAKE_PY=$(python3 "$HERE/webrtcenv.py") || { echo "the fake's WebRTC peer needs Python 3.11+ and its venv (webrtcenv.py)" >&2; exit 2; }
  fi
  FAKE_PY="${FAKE_PY:-python3}"
  # How the panel reaches the fake (--ha-transport): auto asks adb reverse first, and takes the LAN only when it fails
  # as on the Mirror's Android 6 over network adb, where the device's adbd can't pick a transport for it.
  HA_CHOSEN_BY=flag HA_REASON="set with --ha-transport lan"
  if [ "$HA_TRANSPORT" = auto ]; then
    HA_CHOSEN_BY=auto
    if reverse_err=$("$ADB" reverse "tcp:$PORT" "tcp:$PORT" 2>&1 >/dev/null); then
      HA_TRANSPORT=adb
    elif [[ $reverse_err == *"more than one device"* ]]; then
      HA_TRANSPORT=lan
      HA_REASON="adb reverse failed on this device (\"more than one device/emulator\"): the fake over the LAN"
      log "adb reverse failed on this device (more than one device/emulator); the fake Home Assistant goes over the LAN"
    else
      echo "adb reverse failed: ${reverse_err:-no reason given}" >&2
      exit 2
    fi
  fi
  if [ "$HA_TRANSPORT" = lan ]; then
    # This computer's LAN address, the one the camera video uses (GW_PERF_WEBRTC_ADDR sets it). The fake listens on
    # it alone, for this run only.
    HA_HOST=$(python3 -c 'import sys; sys.path.insert(0, sys.argv[1]); import webrtc; print(webrtc.media_address())' "$HERE") \
      || { echo "--ha-transport lan needs an IPv4 address on this computer that the panel can reach (GW_PERF_WEBRTC_ADDR sets one)" >&2; exit 2; }
    # The kiosk takes a test dashboard only on a local network address (TestHoldFile.isTestDashboard): checked here by
    # the same rule, so a VPN or public address on the default route stops the run now, saying so.
    python3 -c 'import sys; sys.path.insert(0, sys.argv[1]); import perflib; sys.exit(0 if perflib.local_address(sys.argv[2]) else 1)' \
      "$HERE" "$HA_HOST" || {
      echo "this computer's LAN address (its default route's) isn't on a private network, a VPN's or a public one perhaps, and the panel takes a test dashboard only on a local network address: set GW_PERF_WEBRTC_ADDR to this computer's address on the panel's network" >&2
      exit 2
    }
    [ "$WEBRTC_TRANSPORT" = adb ] && { echo "--webrtc-transport adb needs adb reverse, which the fake over the LAN doesn't use" >&2; exit 2; }
    # Recorded for the report, without the address: the fake's traffic crossed this computer's network.
    python3 -c 'import json,sys; json.dump({"transport": "lan", "chosen_by": sys.argv[1], "reason": sys.argv[2]}, open(sys.argv[3], "w"), indent=1)' \
      "$HA_CHOSEN_BY" "$HA_REASON" "$OUT/ha_transport.json"
    TESTDASH_LAN_HINT="; over the LAN (--ha-transport lan) it needs an app that takes a test dashboard on a local network address"
    log "Home Assistant (the fake) over the LAN, on this computer's LAN address only, for this run: no adb reverse; its live updates cross this computer's network"
  elif [ "$HA_CHOSEN_BY" = auto ]; then
    python3 -c 'import json,sys; json.dump({"transport": "adb", "chosen_by": "auto", "reason": "adb reverse works on this device"}, open(sys.argv[1], "w"), indent=1)' \
      "$OUT/ha_transport.json"
  fi
  HA_URL="http://$HA_HOST:$PORT"
  # The camera video's path (lanprobe.py: lan-udp, lan-turn or adb), before the fake takes the port;
  # report.py reads transport.json. Without cameras (the animation page alone) it stays adb.
  if [ "$REF" = 1 ] || [ -n "$PANE_RAM" ]; then
    WEBRTC_TRANSPORT=$("$FAKE_PY" "$HERE/lanprobe.py" --port "$TURN_PORT" --out "$OUT" --transport "$WEBRTC_TRANSPORT" \
      $([ "$HA_TRANSPORT" = lan ] && echo --ha-over-lan)) \
      || { echo "lanprobe.py failed" >&2; exit 2; }
    log "camera video over $WEBRTC_TRANSPORT ($(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["reason"])' "$OUT/transport.json"))"
  elif [ "$WEBRTC_TRANSPORT" = auto ]; then
    # No cameras: the relay isn't used. Over the LAN it listens there too, with nothing through adb.
    WEBRTC_TRANSPORT=adb
    [ "$HA_TRANSPORT" = lan ] && WEBRTC_TRANSPORT=lan-turn
  fi
  if [ "$REF" = 1 ] || [ "$SF_CHECK_ONLY" = 1 ]; then
    # This computer's own network (#649): 40 pings to its gateway and to the panel. A stalling link (macOS Wi-Fi)
    # adds to the live updates and cameras where they cross it; the report is then provisional. Never fails the run.
    python3 "$HERE/netcheck.py" --out "$OUT" $([ "$HA_TRANSPORT" = lan ] && echo --ha-over-lan) || log "the network check failed (netcheck.py); the report won't have it"
  fi
  # Something else on the ports (an earlier run's fake left behind) would answer for ours.
  if curl -s -o /dev/null --connect-timeout 1 --max-time 3 "$HA_URL/manifest.json"; then
    echo "port $PORT is already in use (an earlier run's fake Home Assistant?); stop it or use --port" >&2
    exit 2
  fi
  if (exec 3<>"/dev/tcp/127.0.0.1/$TURN_PORT") 2>/dev/null || { [ "$HA_TRANSPORT" = lan ] && (exec 3<>"/dev/tcp/$HA_HOST/$TURN_PORT") 2>/dev/null; }; then
    echo "port $TURN_PORT (the WebRTC relay's, --port + 1) is already in use (an earlier run's fake?); stop it or use --port" >&2
    exit 2
  fi
  # fakeha.jsonl: every command the frontend sent, and the stream's sends (report.py checks them).
  "$FAKE_PY" "$HERE/fakeha.py" --port "$PORT" --turn-port "$TURN_PORT" --webrtc-transport "$WEBRTC_TRANSPORT" \
    --bind "$HA_HOST" --log "$OUT/fakeha.jsonl" >"$OUT/server.log" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 60); do curl -s -o /dev/null --connect-timeout 1 --max-time 3 "$HA_URL/manifest.json" && break; sleep 0.5; done
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "the fake Home Assistant didn't start; see $OUT/server.log" >&2; exit 2; }
  if [ "$HA_TRANSPORT" = adb ]; then
    reverse_err=$("$ADB" reverse "tcp:$PORT" "tcp:$PORT" 2>&1 >/dev/null) || {
      echo "adb reverse failed: ${reverse_err:-no reason given}" >&2
      [[ $reverse_err == *"more than one device"* ]] && echo "this device can't make one (the Mirror's Android 6 over network adb): run with --ha-transport lan (docs/perf.md, \"Home Assistant over the LAN\")" >&2
      exit 2
    }
    # The WebRTC relay (TURN over TCP): the panel's only way to this computer's WebRTC peer over adb.
    "$ADB" reverse "tcp:$TURN_PORT" "tcp:$TURN_PORT" >/dev/null
  fi
  if [ "$SF_POLL" = auto ]; then
    log "checking how far back SurfaceFlinger's history reaches (perfrun.py sfcheck)"
    SF_POLL=$(run sfcheck --url "$HA_URL/clarity/page/sfcheck.html") || {
      echo "the SurfaceFlinger check failed, before the run: see $OUT/sfcheck.json (run.sh --sf-poll to read a set way)" >&2
      exit 2
    }
  fi
fi
[ "$SF_POLL" = auto ] && SF_POLL=0
# Seconds between SurfaceFlinger reads during each window; 0: one read after it (perfrun.py SF_POLL_S).
export GW_PERF_SF_POLL="$SF_POLL"
if [ "$SF_CHECK_ONLY" = 1 ]; then
  log "checks done (--sf-check-only): SurfaceFlinger read every ${SF_POLL} s during a window (0: once after it); the network in netcheck.json"
  exit 0
fi

if [ "$SYNTH" = 1 ]; then
  for k in $(seq 1 "$RUNS"); do
    nonce=$(python3 -c 'import secrets; print(secrets.token_hex(4))')
    url="$HA_URL/clarity/page/index.html?run=$k&seed=1&vsync=$VSYNC&nonce=$nonce&quick=$QUICK"
    log "synthetic run $k/$RUNS"
    DASHBOARD_OK=0
    thermal "synthetic-$k"
    status=0
    run synthetic --run "$k" --url "$url" --nonce "$nonce" || status=$?
    if [ "$status" = 4 ]; then
      # perfrun.py LOCKDOWN_EXIT: the legacy engine loads only its dashboard's origin (#198).
      log "the app's lockdown refused the animation test page (legacy engine): skipping it (detail, not graded)"
      SYNTH=0
      break
    fi
    [ "$status" = 0 ] || exit "$status"
    sleep "$RUN_GAP"
  done
  if [ "$SYNTH" = 1 ]; then
    log "memory on the test page after the ladder"
    run idle --label after-synthetic --run 1 --settle 5 --window "$IDLE_WINDOW"
  fi
fi

if [ "$REF" = 1 ]; then
  FALLBACK=$(python3 -c 'import json,sys; print(",".join(json.load(open(sys.argv[1])).get("FALLBACK", {})))' "$HERE/page/tiers.json")
  # How many graded runs were attempted, so a run that left no file still counts against the grade.
  printf '{"runs": %d}\n' "$RUNS" > "$OUT/plan.json"
  # The graded part. The kiosk shows the reference dashboard's start view as its test dashboard; each
  # run force-stops it, times the restart to that view being usable, then opens the four reference
  # views in turn within the loaded frontend (the order turns each run).
  log "showing the reference dashboard for the graded runs (the kiosk's own dashboard and sign-in stay as they are)"
  SWITCHED=1
  run testdash --url "$HA_URL/clarity-ref/start" >/dev/null || {
    echo "$PACKAGE didn't take Clarity's test dashboard (--es test_dashboard): install an app built from main after it came in (docs/perf.md)$TESTDASH_LAN_HINT" >&2
    exit 2
  }
  DASHBOARD_OK=0
  sleep "$SWITCH_SETTLE"
  for k in $(seq 1 "$RUNS"); do
    log "graded run $k/$RUNS: restart to the reference dashboard, then the four reference dashboards"
    thermal "refstart-$k"
    # Protocol 6: then the camera pages' HLS copies, the fallback (detail, not graded).
    if run refstart --run "$k" --settle "$COLD_SETTLE" --url "$HA_URL" \
      --vsync "$VSYNC" --taps "$TAPS" --between-quiet "$BETWEEN_QUIET" --fallback "$FALLBACK"; then
      # RAM at idle on the typical reference dashboard, opened with no nonce: nothing is timed and
      # the live updates stay off (docs/perf.md, "RAM").
      log "idle on the typical reference dashboard $k/$RUNS"
      run idle --label ref-idle --run "$k" --url "$HA_URL/clarity-ref/typical" \
        --settle "$IDLE_SETTLE" --window "$IDLE_WINDOW"
    else
      log "graded run $k failed; see refstart-$k.json"
    fi
    sleep "$RUN_GAP"
  done
fi

if [ -n "$PANE_RAM" ]; then
  # Each switchable pane on by itself against every pane off, on the typical reference dashboard.
  # The first intent sets the live settings (the other panes' switches, the microphone mute) and shows
  # the dashboard as the kiosk's test dashboard; the second sets voice_enabled and restarts the app, so every sample
  # starts from a fresh process. perfrun.py panes reads the kiosk's log to check what it runs. A sample that
  # ran something else, or saw the screen go off, is left out of the report (docs/perf.md).
  log "RAM per pane: every pane off, then each on by itself, $((RUNS + 1)) cycles, the first a warm-up (they go back to $PANE_RAM after)"
  SWITCHED=1
  # Checked once in the kiosk's log: am says "Status: ok" to a kiosk that ignores the extra, and the
  # samples would then be of its own dashboard. Each state's intent below sends it again, unchanged.
  run testdash --url "$HA_URL/clarity-ref/typical" >/dev/null || {
    echo "$PACKAGE didn't take Clarity's test dashboard (--es test_dashboard): install an app built from main after it came in (docs/perf.md)$TESTDASH_LAN_HINT" >&2
    exit 2
  }
  DASHBOARD_OK=0
  PANES_SWITCHED=1
  # Cycle 0 is a warm-up, not recorded: its files go to warmup/, which the report doesn't read. In
  # s1p1 and s1p3 the first sample had the GPU process 15 MB under every later one (#186).
  mkdir -p "$OUT/warmup"
  for k in 0 $(seq 1 "$RUNS"); do
    dir=$OUT
    [ "$k" = 0 ] && dir=$OUT/warmup
    for state in $(awk '$1 == "STATE" && $2 != "restore" { print $2 }' <<< "$PANES"); do
      label=$([ "$state" = none ] && echo ref-panes-off || echo "ref-$state-on")
      log "panes: $label $([ "$k" = 0 ] && echo "warm-up (not recorded)" || echo "$k/$RUNS")"
      # Set and check what the kiosk runs (perfrun.py panes); each sample starts from a fresh app
      # process, since the second intent restarts it. The idle sample records what it ran too.
      # A state that didn't take (a pane, or the dashboard intent refused: url_taken false) gets no
      # sample: it wouldn't be this label on the reference dashboard.
      if ! OUT=$dir run panes --set "$(pane_set "$state")" --url "$HA_URL/clarity-ref/typical" \
          --settle "$SWITCH_SETTLE" --tries 3 > "$dir/panes-$label-$k.json"; then
        log "panes: the kiosk didn't run $label (see $dir/panes-$label-$k.json); no sample taken"
        continue
      fi
      if grep -q '"voice_applied": false' "$dir/panes-$label-$k.json"; then
        log "panes: voice_enabled didn't take (this kiosk saves it asynchronously before it restarts); Assist is off by its microphone mute"
      fi
      OUT=$dir thermal "$label-$k"
      OUT=$dir run idle --label "$label" --run "$k" --settle "$IDLE_SETTLE" --window "$IDLE_WINDOW"
    done
  done
fi
point_back
restored || { log "the kiosk's dashboard or panes weren't put back"; exit 3; }

if [ "$REAL" = 1 ]; then
  for k in $(seq 1 "$RUNS"); do
    log "cold start $k/$RUNS"
    thermal "coldstart-$k"
    # A failed cold start (no dashboard within 120 s) is recorded and the run goes on. Its idle
    # sample is skipped: it wouldn't be the dashboard's.
    DASHBOARD_OK=0
    if ! run coldstart --run "$k" --settle "$COLD_SETTLE" --timeout "$COLD_TIMEOUT"; then
      log "cold start $k failed; see coldstart-$k.json"
      continue
    fi
    DASHBOARD_OK=1
    log "idle on the dashboard $k/$RUNS"
    run idle --label all-on --run "$k" --settle "$IDLE_SETTLE" --window "$IDLE_WINDOW"
  done
fi

thermal end
python3 "$HERE/report.py" "$OUT" ${APKS[@]+"${APKS[@]}"}
log "report: $OUT/report.json and $OUT/report.md"
