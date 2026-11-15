#!/usr/bin/env bash
# Where the Android build lives, so the workflows work in both repo layouts of #21: today's (the
# Gradle root in app/, the S1 APK module in app/kiosk, the libraries in app/libs) and the split one
# (the Gradle root at the repo root, modules in libs/, core/, pane-api/, engines/, panes/,
# frames/<id>/android, frames/<id>/panes). pr.yml, test-build.yml and release.yml use it, and pr.yml's
# size job runs this copy on the PR's base, which may have the other layout.
# Read-only. Off-device test: tools/release/test/android-layout-test.sh.
#
#   android-layout.sh gradle-root [<repo>]     prints the Gradle root relative to <repo> (default: this
#                                              repo): app if app/settings.gradle.kts exists, else .
#   android-layout.sh file <dir> <path>        prints the one file under <dir> whose path ends in /<path>
#                                              (find -path wildcards allowed), e.g. an APK by its name:
#                                              file . build/outputs/apk/release/kiosk-release-unsigned.apk
#                                              Fails if none or several match.
#   android-layout.sh changed <base> [<head>]  prints the files under every Android root that differ
#                                              between two commits (default head: HEAD), both sides of a
#                                              move. Fails if git does.
set -euo pipefail

# Every place a Gradle module, or what builds it, can live in either layout (#21 move plan, S-ci).
# frames/<id>/design etc. aren't Android code; :(glob) so * stays inside one folder.
ANDROID_ROOTS=(app libs core pane-api engines panes ':(glob)frames/*/android/**' ':(glob)frames/*/panes/**'
  settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle
  .github/workflows/pr.yml tools/release/android-layout.sh)

usage() {
  cat >&2 <<'USAGE'
android-layout.sh gradle-root [<repo>]     the Gradle root relative to <repo>: app or .
android-layout.sh file <dir> <path>        the one file under <dir> whose path ends in /<path>
android-layout.sh changed <base> [<head>]  the files under every Android root that differ
USAGE
  exit 2
}

case "${1:-}" in
  gradle-root)
    repo="${2:-$(dirname "$0")/../..}"
    if [ -f "$repo/app/settings.gradle.kts" ]; then echo app
    elif [ -f "$repo/settings.gradle.kts" ]; then echo .
    else echo "android-layout.sh: no settings.gradle.kts in $repo/app or $repo" >&2; exit 1
    fi ;;
  file)
    [ $# -eq 3 ] || usage
    dir="$2" path="$3"
    [ -d "$dir" ] || { echo "android-layout.sh: no folder $dir" >&2; exit 1; }
    found=$(find "$dir" \( -name node_modules -o -name .git -o -name .gradle \) -prune \
      -o -type f -path "*/$path" -print)
    case "$(printf '%s' "$found" | grep -c '^')" in
      1) echo "$found" ;;
      0) echo "android-layout.sh: no file */$path under $dir" >&2; exit 1 ;;
      *) printf 'android-layout.sh: more than one file */%s under %s:\n%s\n' "$path" "$dir" "$found" >&2; exit 1 ;;
    esac ;;
  changed)
    [ $# -ge 2 ] && [ $# -le 3 ] || usage
    # --end-of-options: a revision that starts with - is a revision, not an option (a bare -- would
    # make git read the revisions as paths).
    git diff --no-renames --name-only --end-of-options "$2" "${3:-HEAD}" -- "${ANDROID_ROOTS[@]}" ;;
  *) usage ;;
esac
