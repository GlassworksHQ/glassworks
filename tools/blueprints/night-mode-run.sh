#!/usr/bin/env bash
# Runs the Panel night mode blueprint (core/blueprints/panel-night-mode.yaml, #473) in a scratch Home
# Assistant, once as night and once as day, with template numbers standing in for two panels' Idle timeout
# and Max auto brightness, and a third panel that doesn't exist. Each stand-in logs every value it is set
# to. Checks that each run sets only the entities whose value differs, skips the missing one, and writes
# nothing on the later once-a-minute runs.
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

# run <night: true|false> <a_idle> <b_idle> <a_max> <b_max>: start values; prints the SET lines.
run() {
  rm -rf "$CFG"/* "$CFG"/.storage
  mkdir -p "$CFG/blueprints/automation/GlassworksHQ"
  cp "$ROOT/core/blueprints/panel-night-mode.yaml" "$CFG/blueprints/automation/GlassworksHQ/"
  {
    cat <<YAML
homeassistant:
  name: Night mode run
  time_zone: UTC
logger:
  default: warning
  logs:
    homeassistant.components.system_log.external: info
system_log:
input_number:
  a_idle: {min: 0, max: 3600, step: 5, initial: $2}
  b_idle: {min: 0, max: 3600, step: 5, initial: $3}
  a_max: {min: 5, max: 100, step: 5, initial: $4}
  b_max: {min: 5, max: 100, step: 5, initial: $5}
template:
  - number:
YAML
    for n in a_idle:a_panel_idle_timeout b_idle:b_panel_idle_timeout a_max:a_panel_max_auto_brightness b_max:b_panel_max_auto_brightness; do
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
        idle_timeouts: [number.a_panel_idle_timeout, number.b_panel_idle_timeout, number.missing_panel_idle_timeout]
        max_brightness: [number.a_panel_max_auto_brightness, number.b_panel_max_auto_brightness]
        night: [{condition: template, value_template: "{{ $1 }}"}]
YAML
  } > "$CFG/configuration.yaml"
  # Long enough for the start trigger and at least one once-a-minute run after it.
  (cd "$CFG" && timeout 130 "$PY" -I -m homeassistant -c "$CFG" > "$CFG/out.log" 2>&1 || true)
  grep -o 'SET [a-z_]* [0-9.]*' "$CFG/home-assistant.log" | sort || true
}

check() {
  if [ "$2" != "$3" ]; then
    printf '%s: expected\n%s\ngot\n%s\n' "$1" "$2" "$3" >&2
    exit 1
  fi
  echo "ok: $1"
}

# Night: a at the day's values, b's idle already at night's. Only what differs is set, once.
check night "$(printf 'SET a_idle 20.0\nSET a_max 20.0\nSET b_max 20.0')" "$(run true 120 20 100 100)"
# Day: both idles at night's, a's limit at night's. Back to the day's values, once.
check day "$(printf 'SET a_idle 120.0\nSET a_max 100.0\nSET b_idle 120.0')" "$(run false 20 20 20 100)"
echo "ok: Panel night mode in Home Assistant $("$PY" -I -c 'import homeassistant.const as c; print(c.__version__)')"
