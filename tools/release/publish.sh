#!/usr/bin/env bash
# Publishes what the Release workflow built (docs/releasing.md, "Release"): for each due item of the plan, its tag on
# the run's commit and its GitHub Release, with its files and its changelog section as the notes. Only the workflow's
# publish job runs it, under one lock for every line, after every build and check passed.
#
#   tools/release/publish.sh <plan.json> <dist dir> <published file>
#
#   plan.json       the plan job's `plan` output (tools/release/plan.py): {version, channel, items: [...]}
#   dist dir        the build artifacts: line-<line>/ for an app line, frame-bundles/<frame>/ for a signed bundle
#   published file  gets each tag that has its release once this is done, one per line (the Discord feed reads it)
#   GITHUB_REPOSITORY, GITHUB_SHA  the repo and the commit every tag must name
#   TAG_REMOTE      where tags are pushed (the workflow: the repo over SSH with the deploy key, GIT_SSH_COMMAND)
#   GH_TOKEN        creates the releases (contents: write)
#
# First it checks every item and changes nothing: the files are exactly the plan's and match their .sha256, the notes
# are the version's changelog section, the version is higher than the line's last release or beta
# (check-newer-than-released.sh), and a tag that exists already names this commit. Then, item by item, it pushes the
# tag (if missing) and creates the release with every file in one `gh release create`, so a release never has half
# its ABIs. A release this run's files are already on (a rerun after a later item failed) is left as it is; a draft
# (an upload that stopped) is deleted and made again, since no one saw it; a published release with other files
# fails the run. Writes tags to TAG_REMOTE and releases to GitHub; reads nothing outside the checkout and dist dir.
set -euo pipefail
plan=${1:?plan.json}; dist=${2:?dist dir}; published=${3:?published file}
repo=${GITHUB_REPOSITORY:?owner/repo}; sha=${GITHUB_SHA:?the commit of the run}; remote=${TAG_REMOTE:?where tags go}
here="$(cd "$(dirname "$0")" && pwd)"
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
fail() { echo "::error::$*" >&2; exit 1; }
n=$(jq '.items | length' "$plan")
[ "$n" -gt 0 ] || fail "the plan has nothing to publish"

# The commit a remote tag names (an annotated tag's ^{} line wins), or nothing.
remote_tag() { git ls-remote "$remote" "refs/tags/$1" "refs/tags/$1^{}" | awk 'NR==1 || /\^\{\}$/ {c=$1} END {print c}'; }

# "<name>  <sha256>" per uploaded file of a release, by downloading them.
release_files() {
  local tag=$1 d="$work/have"; rm -rf "$d"; mkdir -p "$d"
  local states; states=$(gh release view "$tag" -R "$repo" --json assets --jq '.assets[] | "\(.name) \(.state)"') ||
    fail "cannot list the files of $tag"
  if printf '%s\n' "$states" | awk 'NF == 2 && $2 != "uploaded" {bad = 1} END {exit !bad}'; then echo "unfinished"; return; fi
  if [ -n "$states" ]; then gh release download "$tag" -R "$repo" --dir "$d"; fi
  (cd "$d" && ls -1A | LC_ALL=C sort | while read -r f; do sha256sum -- "$f"; done)
}

action=()
for ((i = 0; i < n; i++)); do
  item() { jq -r ".items[$i].$1 // empty" "$plan"; }
  tag=$(item tag) kind=$(item kind) name=$(item name) version=$(item version)
  [ "$(item status)" = due ] || fail "$tag: not due in the plan"
  [ "$version" = "$(jq -r .version "$plan")" ] || fail "$tag: version $version is not the run's"
  # The notes: the version's own section. A dry run's Unreleased stand-in never publishes.
  [ "$(item notes)" = "$version" ] || fail "$tag: no [$version] section in $(item changelog): refusing to publish"
  "$here/changelog-section.sh" "$version" "$(item changelog)" > "$work/notes-$i.md"
  # The files: exactly the plan's, each matching its .sha256.
  case "$kind/$(item bundle)" in
    line/) dir="$dist/line-$name" ;;
    frame/signed) dir="$dist/frame-bundles/$name" ;;
    frame/notice) dir="" ;;
    *) fail "$tag: unknown kind $kind" ;;
  esac
  want=$(jq -r ".items[$i].assets[]" "$plan" | LC_ALL=C sort)
  if [ -n "$dir" ]; then
    [ -d "$dir" ] || fail "$tag: no build output in $dir"
    have=$(cd "$dir" && ls -1A | LC_ALL=C sort)
    [ "$have" = "$want" ] || { printf 'want:\n%s\nhave:\n%s\n' "$want" "$have" >&2; fail "$tag: the build's files aren't the plan's"; }
    (cd "$dir" && for f in *.sha256; do sha256sum -c "$f"; done)
    (cd "$dir" && ls -1A | LC_ALL=C sort | while read -r f; do sha256sum -- "$f"; done) > "$work/want-$i"
  else
    [ -z "$want" ] || fail "$tag: a notice has no files"
    : > "$work/want-$i"
  fi
  # The tag: missing, or on this commit. A tag pushed by hand on another commit is never published over.
  at=$(remote_tag "$tag")
  [ -z "$at" ] || [ "$at" = "$sha" ] || fail "$tag already names $at, not this run's $sha: refusing to publish"
  # The release: none yet (then the version must be new), this run's files already, or a draft to make again.
  if draft=$(gh release view "$tag" -R "$repo" --json isDraft --jq .isDraft 2>/dev/null); then
    if [ "$draft" = true ]; then
      # A draft isn't a release: the version must still be higher than the line's last one, as for a new release.
      "$here/check-newer-than-released.sh" "$(item check)" "$version" "$repo"
      action[i]=redo
    elif [ "$(release_files "$tag")" = "$(cat "$work/want-$i")" ]; then action[i]=done
    else fail "$tag is published with other files: refusing to touch it (fix it with a higher version)"; fi
  else
    "$here/check-newer-than-released.sh" "$(item check)" "$version" "$repo"
    action[i]=create
  fi
  echo "$tag: ${action[i]}"
done

: > "$published"
for ((i = 0; i < n; i++)); do
  item() { jq -r ".items[$i].$1 // empty" "$plan"; }
  tag=$(item tag) name=$(item name)
  if [ "${action[i]}" = done ]; then echo "$tag: already published with these files"; echo "$tag" >> "$published"; continue; fi
  # The tag again: someone may have pushed it since the checks above, and --verify-tag only asks that it exists.
  at=$(remote_tag "$tag")
  if [ -z "$at" ]; then
    git tag -f "$tag" "$sha"   # lightweight: the release carries the title, notes and channel
    git push "$remote" "refs/tags/$tag:refs/tags/$tag"
    [ "$(remote_tag "$tag")" = "$sha" ] || fail "$tag did not reach $remote on $sha"
  else
    [ "$at" = "$sha" ] || fail "$tag now names $at, not this run's $sha: refusing to publish"
  fi
  if [ "${action[i]}" = redo ]; then
    echo "$tag: a draft from an earlier try, never public: making it again"
    gh release delete "$tag" -R "$repo" --yes
  fi
  # --latest for the line marked latest (none yet, 1.0.0 included: v0.4.4 stays releases/latest for panels up to 0.4.3),
  # and only on a stable; everything else --latest=false. A beta is a pre-release, which only apps on the Beta channel take.
  flags=(--latest=false)
  [ "$(item latest)" = true ] && flags=(--latest)
  [ "$(item prerelease)" = true ] && flags=(--prerelease --latest=false)
  files=()
  case "$(item kind)/$(item bundle)" in
    line/) files=("$dist/line-$name"/*) ;;
    frame/signed) files=("$dist/frame-bundles/$name"/*) ;;
  esac
  gh release create "$tag" -R "$repo" ${files[@]+"${files[@]}"} --verify-tag "${flags[@]}" \
    --title "$(item title)" --notes-file "$work/notes-$i.md"
  echo "$tag" >> "$published"
done
