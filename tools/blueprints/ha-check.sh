#!/usr/bin/env bash
# Checks the panes' Home Assistant blueprints with Home Assistant's own config check, as a real instance
# loads them: each blueprint goes into a scratch config folder, samples.yaml makes an automation from it
# with made-up inputs, and `hass --script check_config` validates the blueprint and the automation.
#
# Read-only: it writes only a temporary folder, deleted at the end. Needs a Python with Home Assistant core:
#   uv venv -p 3.14 /tmp/ha && VIRTUAL_ENV=/tmp/ha uv pip install homeassistant   (about 600 MB; delete it after)
#   tools/blueprints/ha-check.sh /tmp/ha/bin/python
# Fails when a blueprint has no sample, or Home Assistant finds an error.
set -euo pipefail
PY=${1:-${HA_PYTHON:-}}
[ -n "$PY" ] || { echo "usage: $0 <python with homeassistant installed>" >&2; exit 2; }
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CFG=$(mktemp -d)
trap 'rm -rf "$CFG"' EXIT
mkdir -p "$CFG/blueprints/automation/GlassworksHQ"
shopt -s nullglob
files=("$ROOT"/panes/*/blueprints/*.yaml "$ROOT"/frames/*/panes/*/blueprints/*.yaml)
[ ${#files[@]} -gt 0 ] || { echo "no blueprints found" >&2; exit 1; }
for f in "${files[@]}"; do
  name=$(basename "$f")
  grep -q "path: GlassworksHQ/$name" "$ROOT/tools/blueprints/samples.yaml" || { echo "$name: no sample in tools/blueprints/samples.yaml" >&2; exit 1; }
  cp "$f" "$CFG/blueprints/automation/GlassworksHQ/"
done
cat > "$CFG/configuration.yaml" <<'YAML'
homeassistant:
  name: Blueprint check
  time_zone: UTC
automation: !include automations.yaml
script:
  water: {sequence: []}
  remind: {sequence: []}
YAML
cp "$ROOT/tools/blueprints/samples.yaml" "$CFG/automations.yaml"
out=$(cd "$CFG" && "$PY" -I -m homeassistant --script check_config -c "$CFG" --info automation 2>&1) || { echo "$out" | tail -40; exit 1; }
want=$(grep -c '^- id:' "$ROOT/tools/blueprints/samples.yaml")
got=$(echo "$out" | grep -c '^      alias:' || true)
if echo "$out" | grep -E -q 'ERROR|Invalid config|generated invalid automation' || [ "$got" -ne "$want" ]; then
  echo "$out" | grep -E 'ERROR|Invalid|invalid' | head -20
  echo "Home Assistant loaded $got of $want sample automations" >&2
  exit 1
fi
echo "ok: ${#files[@]} blueprints, $got sample automations valid in Home Assistant $("$PY" -I -c 'import homeassistant.const as c; print(c.__version__)')"
