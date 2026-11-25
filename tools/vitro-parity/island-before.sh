#!/usr/bin/env bash
# The island page as an older Vitro served it, for island-probe.mjs: reproduces what a card drew before a fix to the
# island page (docs/research/vitro-island-better-thermostat.md, "Before the fix"). It makes a throwaway git worktree of
# HEAD, puts back WebIslandCardView.kt from <commit>, drops the engine's other unit tests there (they test today's
# page and wouldn't build against the old one), writes each case's island page with IslandPageDumpTest, and runs the
# probe on it with the arguments after `--`.
#
# Touches: a temporary worktree under $TMPDIR (removed at the end, with its build), Gradle (--max-workers=2), a fake HA
# on 127.0.0.1 and a throwaway headless Chromium through island-probe.mjs. Nothing in this checkout, no device, no real
# HA.
#
#   tools/vitro-parity/island-before.sh <commit> <case.json>[,<case.json>…] [-- <island-probe.mjs options>]
set -euo pipefail
commit=${1:?usage: island-before.sh <commit> <case.json>[,<case.json>…] [-- probe options]}
cases=${2:?usage: island-before.sh <commit> <case.json>[,<case.json>…] [-- probe options]}
shift 2
[ "${1:-}" = "--" ] && shift
repo=$(git rev-parse --show-toplevel)
git -C "$repo" cat-file -e "$commit^{commit}"
tree=$(mktemp -d "${TMPDIR:-/tmp}/island-before.XXXXXX")
cleanup() { git -C "$repo" worktree remove --force "$tree" >/dev/null 2>&1 || rm -rf "$tree"; }
trap cleanup EXIT
git -C "$repo" worktree add --detach --quiet "$tree" HEAD
island=engines/engine-vitro/src/main/java/dev/glassworks/engine/vitro/cards/WebIslandCardView.kt
git -C "$tree" show "$commit:$island" > "$tree/$island"
find "$tree/engines/engine-vitro/src/test/java" -name '*.kt' ! -name IslandPageDumpTest.kt -delete
# The probe reads node_modules next to it: this checkout's (npm ci --prefix tools/vitro-parity).
ln -s "$repo/tools/vitro-parity/node_modules" "$tree/tools/vitro-parity/node_modules"
abs=()
IFS=, read -ra list <<< "$cases"
for c in "${list[@]}"; do abs+=("$(cd "$(dirname "$c")" && pwd)/$(basename "$c")"); done
(cd "$tree" && ./gradlew --max-workers=2 -q :engine-vitro:testDebugUnitTest --tests '*IslandPageDumpTest*' \
  -PvitroIslandCase="$(IFS=,; echo "${abs[*]}")")
for c in "${abs[@]}"; do
  echo "== $(basename "$c") as $commit served it"
  node "$tree/tools/vitro-parity/island-probe.mjs" "$c" "$@"
done
