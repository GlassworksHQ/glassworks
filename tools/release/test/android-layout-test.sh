#!/usr/bin/env bash
# Off-device test of tools/release/android-layout.sh against two scratch repos: today's layout (the
# Gradle root in app/) and #21's split one (the Gradle root at the repo root, the S1 APK module in
# frames/aqara-s1-plus/android). Writes only to a temp folder. pr.yml's scripts job runs it.
#   tools/release/test/android-layout-test.sh
set -euo pipefail
layout="$(cd "$(dirname "$0")/.." && pwd)/android-layout.sh"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fails=0
check() {   # check <what> <expected> <command…>: the command's output and success
  local what="$1" want="$2"; shift 2
  local got; if got=$("$@" 2>&1); then :; else got="FAILED: $got"; fi
  if [ "$got" = "$want" ]; then echo "ok   $what"; else printf 'FAIL %s\n  want: %s\n  got:  %s\n' "$what" "$want" "$got"; fails=$((fails + 1)); fi
}
fails_with() {   # fails_with <what> <message part> <command…>
  local what="$1" want="$2"; shift 2
  local got; if got=$("$@" 2>&1); then echo "FAIL $what: succeeded ($got)"; fails=$((fails + 1))
  elif [[ "$got" == *"$want"* ]]; then echo "ok   $what"; else printf 'FAIL %s\n  want: *%s*\n  got:  %s\n' "$what" "$want" "$got"; fails=$((fails + 1)); fi
}
put() { mkdir -p "$(dirname "$1")"; echo "${2:-x}" > "$1"; }
git_() { git -c user.email=t@example.invalid -c user.name=t -c init.defaultBranch=main "$@"; }

apk=build/outputs/apk/release/kiosk-release-unsigned.apk

# Today's layout.
old="$tmp/old"; git_ init -q "$old"
put "$old/app/settings.gradle.kts"; put "$old/app/kiosk/build.gradle.kts"; put "$old/app/libs/esphome-api/test-requirements.txt"
put "$old/frames/aqara-s1-plus/design/frame.json"; put "$old/README.md"; printf 'build/\nnode_modules/\n' > "$old/.gitignore"
git_ -C "$old" add -A; git_ -C "$old" commit -qm old
put "$old/app/kiosk/$apk"
put "$old/kiln/node_modules/x/$apk"   # pruned
check "old: Gradle root" app "$layout" gradle-root "$old"
both="$tmp/both"; put "$both/app/settings.gradle.kts"; put "$both/settings.gradle.kts"
check "both settings files: app wins" app "$layout" gradle-root "$both"
# shellcheck disable=SC2016 # $1… are bash -c's arguments
check "old: APK from the Gradle root" "./kiosk/$apk" bash -c 'cd "$1/app" && "$2" file . "$3"' _ "$old" "$layout" "$apk"
check "old: a library file from the repo root" "$old/app/libs/esphome-api/test-requirements.txt" "$layout" file "$old" esphome-api/test-requirements.txt

# #21's layout: the same repo after the move, so `changed` sees renames into the new roots.
new="$tmp/new"; git_ clone -q "$old" "$new"
git_ -C "$new" mv app/settings.gradle.kts settings.gradle.kts
mkdir -p "$new/libs" "$new/frames/aqara-s1-plus"
git_ -C "$new" mv app/libs/esphome-api libs/esphome-api
git_ -C "$new" mv app/kiosk frames/aqara-s1-plus/android
git_ -C "$new" commit -qm new
put "$new/frames/aqara-s1-plus/android/$apk"
put "$new/kiln/node_modules/x/$apk"
check "new: Gradle root" . "$layout" gradle-root "$new"
# shellcheck disable=SC2016
check "new: APK from the Gradle root" "./frames/aqara-s1-plus/android/$apk" bash -c 'cd "$1" && "$2" file . "$3"' _ "$new" "$layout" "$apk"
check "new: a library file" "$new/libs/esphome-api/test-requirements.txt" "$layout" file "$new" esphome-api/test-requirements.txt

# What the change filter sees.
cd "$new"
base=$(git rev-parse HEAD~1)
check "changed: the move, both sides" "$(printf '%s\n' app/kiosk/build.gradle.kts app/libs/esphome-api/test-requirements.txt \
  app/settings.gradle.kts frames/aqara-s1-plus/android/build.gradle.kts libs/esphome-api/test-requirements.txt settings.gradle.kts)" \
  "$layout" changed "$base"
put frames/aqara-s1-plus/design/frame.json y; put README.md y; put frames/aqara-s1-plus/androidx/a.kt; put docs/x.md
git_ add -A; git_ commit -qm "not android"
check "changed: frame design, docs, a look-alike folder: nothing" "" "$layout" changed HEAD~1
for f in frames/aqara-s1-plus/android/src/main/A.kt frames/x/panes/radio/B.kt core/C.kt pane-api/D.kt engines/gecko/E.kt \
         panes/voice/F.kt libs/improv/G.kt build.gradle.kts gradle.properties gradlew gradlew.bat gradle/wrapper/gradle-wrapper.properties \
         .github/workflows/pr.yml tools/release/android-layout.sh; do
  put "$f"; git_ add -A; git_ commit -qm "$f"
  check "changed: $f" "$f" "$layout" changed HEAD~1 HEAD
done
fails_with "changed: an unknown base fails" "fatal:" "$layout" changed 0000000000000000000000000000000000000000
fails_with "changed: a base that looks like an option is a revision" "fatal:" "$layout" changed "--output=$tmp/written"
[ ! -e "$tmp/written" ] || { echo "FAIL changed: git took --output= as an option"; fails=$((fails + 1)); }
fails_with "usage" "gradle-root [<repo>]" "$layout" nonsense

# Failures that must not pass silently.
fails_with "no Gradle root" "no settings.gradle.kts" "$layout" gradle-root "$tmp"
fails_with "no APK" "no file" "$layout" file "$tmp/old/frames" "$apk"
put "$new/app/kiosk/$apk"   # a stale copy next to the real one
fails_with "two APKs" "more than one" "$layout" file "$new" "$apk"
fails_with "no folder" "no folder" "$layout" file "$tmp/missing" "$apk"

[ "$fails" -eq 0 ] || { echo "$fails failed"; exit 1; }
echo "all passed"
