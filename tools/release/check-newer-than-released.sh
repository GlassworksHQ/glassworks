#!/usr/bin/env bash
# Fails unless <version> is higher than the last GitHub release of one release line (tags <line>-vX.Y.Z; the S1 app's
# line, aqara-s1-plus, also its plain vX.Y.Z tags). An installed app only offers a newer version, and Android refuses
# a lower versionCode. Read-only: lists the repo's releases with gh (GH_TOKEN, any read token). release.yml runs it
# before the build; generic-release.yml before the build, and again in its publish job, which runs one at a time per
# line, so two tags of one line can't publish out of order.
#   tools/release/check-newer-than-released.sh generic-android 1.2.3 [owner/repo]
set -euo pipefail
line="${1:?release line, e.g. generic-android}" version="${2:?version, e.g. 1.2.3}"
repo="${3:-${GITHUB_REPOSITORY:?owner/repo}}"
# Published releases and pre-releases (betas, #605): devices on the Beta channel take a pre-release, so a stable must
# be higher than the last beta too. A draft hasn't reached any device.
tags="$(gh release list --repo "$repo" --limit 1000 --exclude-drafts --json tagName --jq '.[].tagName')"
if [ "$line" = aqara-s1-plus ]; then re='^(aqara-s1-plus-)?v([0-9]+\.[0-9]+\.[0-9]+)$'; else re="^($line-)v([0-9]+\.[0-9]+\.[0-9]+)$"; fi
last="$(sed -n -E "s/$re/\2/p" <<< "$tags" | sort -V | tail -n 1)"
echo "last $line release or beta: ${last:-none}"
if [ -n "$last" ] && [ "$(printf '%s\n' "$last" "$version" | sort -V | tail -n 1)" = "$last" ]; then
  echo "::error::$version is not higher than $line's last release or beta, $last"; exit 1
fi
