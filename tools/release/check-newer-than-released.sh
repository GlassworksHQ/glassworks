#!/usr/bin/env bash
# Fails unless <version> is higher than the last GitHub release of one release line (tags <line>-vX.Y.Z).
# An installed app only offers a newer version, and Android refuses a lower versionCode. Read-only: lists the
# repo's releases with gh (GH_TOKEN, any read token). generic-release.yml runs it before the build, and again in
# its publish job, which runs one at a time per line, so two tags of one line can't publish out of order.
#   tools/release/check-newer-than-released.sh generic-android 1.2.3 [owner/repo]
set -euo pipefail
line="${1:?release line, e.g. generic-android}" version="${2:?version, e.g. 1.2.3}"
repo="${3:-${GITHUB_REPOSITORY:?owner/repo}}"
# Published releases only: a draft or a pre-release (which the app ignores) hasn't raised what devices are offered.
tags="$(gh release list --repo "$repo" --limit 1000 --exclude-drafts --exclude-pre-releases --json tagName --jq '.[].tagName')"
last="$(sed -n -E "s/^$line-v([0-9]+\.[0-9]+\.[0-9]+)$/\1/p" <<< "$tags" | sort -V | tail -n 1)"
echo "last $line release: ${last:-none}"
if [ -n "$last" ] && [ "$(printf '%s\n' "$last" "$version" | sort -V | tail -n 1)" = "$last" ]; then
  echo "::error::$version is not higher than $line's last release, $last"; exit 1
fi
