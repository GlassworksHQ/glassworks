#!/usr/bin/env bash
# Prints the update channel a release tag is published on (#605, docs/releasing.md "Betas"): `beta` when the
# annotated tag's message has the line `Channel: beta`, else `stable`. A beta is published as a GitHub pre-release,
# which only apps on the Beta channel take. Read-only: reads the local tag (fetch it first, since actions/checkout
# can leave a lightweight copy of an annotated tag). release.yml and generic-release.yml run it.
#   git tag -a aqara-s1-plus-v1.2.3 -m "Glassworks 1.2.3" -m "Channel: beta"
#   tools/release/tag-channel.sh aqara-s1-plus-v1.2.3            # beta
#   tools/release/tag-channel.sh --missing-ok generic-android-v0.3.0   # stable while the tag doesn't exist (a dry run)
# A `Channel:` line with anything but beta or stable fails, so a typo never publishes a stable by mistake.
set -euo pipefail
missing_ok=false
if [ "${1:-}" = --missing-ok ]; then missing_ok=true; shift; fi
tag="${1:?tag, e.g. aqara-s1-plus-v1.2.3}"
if ! type="$(git cat-file -t "refs/tags/$tag" 2>/dev/null)"; then
  if [ "$missing_ok" = true ]; then echo stable; exit 0; fi
  echo "::error::no tag $tag here" >&2; exit 1
fi
# A lightweight tag has no message of its own: stable.
[ "$type" = tag ] || { echo stable; exit 0; }
# The message is everything after the tag object's header; a signed tag's signature follows it.
lines="$(git cat-file tag "refs/tags/$tag" | sed '1,/^$/d' | sed '/^-----BEGIN /,$d' | grep -iE '^[[:space:]]*channel:' || true)"
[ -n "$lines" ] || { echo stable; exit 0; }
[ "$(wc -l <<< "$lines")" -eq 1 ] || { echo "::error::$tag has more than one Channel: line" >&2; exit 1; }
value="$(sed -E 's/^[[:space:]]*[Cc][Hh][Aa][Nn][Nn][Ee][Ll]:[[:space:]]*//; s/[[:space:]]+$//' <<< "$lines" | tr '[:upper:]' '[:lower:]')"
case "$value" in
  beta|stable) echo "$value" ;;
  *) echo "::error::$tag says 'Channel: $value': only beta or stable" >&2; exit 1 ;;
esac
