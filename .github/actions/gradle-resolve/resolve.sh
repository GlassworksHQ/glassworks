#!/usr/bin/env bash
# Configures the Gradle build once (`./gradlew help`), from the repo root, before a job's own Gradle steps. That fetches
# the plugins and the classpath of build-logic, so the later steps find them in Gradle's cache. When a download fails,
# it drops Gradle's dependency metadata and tries again (docs/ci.md, Gradle downloads).
#
# Why: on 2026-11-18 and 19, 5 of the 13 red `app` attempts were the plugin portal answering "not found" (HTTP 404)
# for kotlin-stdlib 2.4.10 while configuring, and a rerun passed. Gradle's own retries
# (org.gradle.internal.repository.max.retries) cover timeouts and 5xx only, not a 404. And Gradle records a 404 as a
# missing module in its metadata, so a plain second try can fail on that record; deleting the metadata (as the jobs'
# "Forget" step does after a failure, #559) makes it ask again. The downloaded files stay.
#
# Any other failure (a build script that doesn't compile, say) fails at once, with Gradle's output.
# Touches only Gradle's metadata under GRADLE_USER_HOME (~/.gradle) on a retry. Env: GW_RESOLVE_ATTEMPTS (3),
# GW_RESOLVE_BACKOFF (15: seconds before the second try, twice that before the third).
set -uo pipefail
# What Gradle prints when a download failed: "Could not find …" (a 404 for a dependency), "Could not resolve …", and
# for a plugin "Plugin [id: …] was not found in any of the following sources … could not resolve plugin artifact".
download='could not (find|resolve|download|get|head) |was not found in any of the following sources'
attempts=${GW_RESOLVE_ATTEMPTS:-3}
backoff=${GW_RESOLVE_BACKOFF:-15}
home=${GRADLE_USER_HOME:-$HOME/.gradle}
log=$(mktemp)
trap 'rm -f "$log"' EXIT

for ((i = 1; i <= attempts; i++)); do
  if ./gradlew --no-daemon -q help > "$log" 2>&1; then
    if [ "$i" -gt 1 ]; then echo "Configured on attempt $i of $attempts."; else echo "Configured."; fi
    exit 0
  fi
  if ! grep -qiE "$download" "$log"; then
    cat "$log"
    echo "::error::Configuring the build failed, and not on a download: no retry."
    exit 1
  fi
  grep -iE "$download" "$log" | sed 's/^[[:space:]>]*//' | sort -u | head -n 5
  if [ "$i" -lt "$attempts" ]; then
    echo "::warning::Gradle couldn't fetch a dependency (attempt $i of $attempts). Dropping its metadata and trying again."
    rm -rf "$home"/caches/modules-2/metadata-*
    sleep $((i * backoff))
  fi
done
cat "$log"
echo "::error::Gradle couldn't fetch its dependencies in $attempts attempts."
exit 1
