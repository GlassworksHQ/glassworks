#!/usr/bin/env bash
# Runs the Panel night mode blueprint (core/blueprints/panel-night-mode.yaml, #473) in a scratch Home
# Assistant, once as night and once as day. Template numbers stand in for two panels' Idle timeout (device
# class duration) and Max auto brightness (in %), plus a number of the wrong kind picked by mistake (a Mic
# gain stand-in, in x) and a panel that doesn't exist. Each stand-in logs every value it is set to.
# Checks that a run moves only a panel at the other time's value, leaves a value set by hand and the wrong
# kind of number alone, skips the missing panel, and writes nothing on the later once-a-minute runs.
#
# Read-only: it writes only a temporary folder, deleted at the end, and talks to no device or network.
# About 2 x 130 s. Needs a Python with Home Assistant core, as ha-check.sh does:
#   tools/blueprints/night-mode-run.sh /tmp/ha/bin/python
set -euo pipefail
PY=${1:-${HA_PYTHON:-}}
[ -n "$PY" ] || { echo "usage: $0 <python with homeassistant installed>" >&2; exit 2; }
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CFG=$(mktemp -d)
trap 'rm -rf "$CFG"' EXIT

# run <night: true|false> <a_idle> <b_idle> <a_max> <b_max> <c_gain>: start values; prints the SET lines.
run() {
  rm -rf "$CFG"/* "$CFG"/.storage
  mkdir -p "$CFG/blueprints/automation/GlassworksHQ"
  cp "$ROOT/core/blueprints/panel-night-mode.yaml" "$CFG/blueprints/automation/GlassworksHQ/"
  {
    cat <<YAML
homeassistant:
  name: Night mode run
  time_zone: UTC
  customize:
    number.a_panel_idle_timeout: {device_class: duration, unit_of_measurement: s}
    number.b_panel_idle_timeout: {device_class: duration, unit_of_measurement: s}
    number.a_panel_max_auto_brightness: {unit_of_measurement: "%"}
    number.b_panel_max_auto_brightness: {unit_of_measurement: "%"}
    number.c_panel_mic_gain: {unit_of_measurement: x}
logger:
  default: warning
  logs:
    homeassistant.components.system_log.external: info
system_log:
input_number:
  a_idle: {min: 0, max: 3600, step: 5, initial: $2}
  b_idle: {min: 0, max: 3600, step: 5, initial: $3}
  a_max: {min: 0, max: 3600, step: 5, initial: $4}
  b_max: {min: 0, max: 3600, step: 5, initial: $5}
  c_gain: {min: 0, max: 3600, step: 5, initial: $6}
template:
  - number:
YAML
    for n in a_idle:a_panel_idle_timeout b_idle:b_panel_idle_timeout a_max:a_panel_max_auto_brightness \
             b_max:b_panel_max_auto_brightness c_gain:c_panel_mic_gain; do
      cat <<YAML
      - name: ${n#*:}
        state: "{{ states('input_number.${n%%:*}') }}"
        min: 0
        max: 3600
        step: 5
        set_value:
          - action: input_number.set_value
            target: {entity_id: input_number.${n%%:*}}
            data: {value: "{{ value }}"}
          - action: system_log.write
            data: {message: "SET ${n%%:*} {{ value }}", level: info}
YAML
    done
    cat <<YAML
automation:
  - id: night_mode
    alias: night mode
    use_blueprint:
      path: GlassworksHQ/panel-night-mode.yaml
      input:
        idle_timeouts: [number.a_panel_idle_timeout, number.b_panel_idle_timeout, number.c_panel_mic_gain, number.missing_panel_idle_timeout]
        max_brightness: [number.a_panel_max_auto_brightness, number.b_panel_max_auto_brightness, number.c_panel_mic_gain]
        night: [{condition: template, value_template: "{{ $1 }}"}]
YAML
  } > "$CFG/configuration.yaml"
  # Long enough for the start trigger and at least one once-a-minute run after it.
  (cd "$CFG" && timeout 130 "$PY" -I -m homeassistant -c "$CFG" > "$CFG/out.log" 2>&1 || true)
  grep -q 'SET\|Setting up' "$CFG/home-assistant.log" 2>/dev/null || { tail -30 "$CFG/out.log" >&2; exit 1; }
  grep -o 'SET [a-z_]* [0-9.]*' "$CFG/home-assistant.log" | sort || true
}

check() {
  if [ "$2" != "$3" ]; then
    printf '%s: expected\n%s\ngot\n%s\n' "$1" "$2" "$3" >&2
    exit 1
  fi
  echo "ok: $1"
}

# Night. a at the day's values moves; b's idle was set by hand (300) and stays; both limits move from 100;
# the Mic gain stand-in sits at 120, the day idle, but is no duration and not in %, so it stays.
check night "$(printf 'SET a_idle 20.0\nSET a_max 20.0\nSET b_max 20.0')" "$(run true 120 300 100 100 120)"
# Day, after a night. a's idle and limit move back; b's limit was set by hand (50) and b's idle is still 300:
# both stay. The Mic gain stand-in at 20, the night idle, stays.
check day "$(printf 'SET a_idle 120.0\nSET a_max 100.0')" "$(run false 20 300 20 50 20)"
echo "ok: Panel night mode in Home Assistant $("$PY" -I -c 'import homeassistant.const as c; print(c.__version__)')"
