#!/usr/bin/env bash
# Whether the build still configures from scratch while Maven Central fails (docs/ci.md, Caches): CI's 429s, without
# waiting for them. Runs `./gradlew help` (or the tasks you give) with an empty Gradle home and every request to
# repo.maven.apache.org sent to a closed local port, so Central answers each one with an error, never a 404.
#
# Touches: a temporary Gradle home it deletes after (the wrapper's distribution is copied in, so Gradle itself isn't
# downloaded again), the repo's build outputs, and the network (Google's Maven, the mirror, the plugin portal).
# No device. It doesn't touch ~/.gradle.
#
#   tools/ci/gradle-without-central.sh                 # ./gradlew help
#   tools/ci/gradle-without-central.sh :sendspin:dependencies --configuration debugUnitTestRuntimeClasspath
set -euo pipefail
cd "$(dirname "$0")/../.."

home=$(mktemp -d "${TMPDIR:-/tmp}/gradle-without-central.XXXXXX")
trap 'rm -rf "$home"' EXIT
if [ -d "${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists" ]; then
  mkdir -p "$home/wrapper"
  cp -R "${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists" "$home/wrapper/"
fi
# Gradle itself, before Central is cut off: when no copied distribution matches the wrapper's, this downloads it
# through services.gradle.org and wherever that redirects (GitHub's release hosts today), which the proxy below would
# refuse. --version runs no build and resolves no dependency.
GRADLE_USER_HOME="$home" ./gradlew --version > /dev/null

# The JVM's proxy settings: every https host goes straight out, except Central, which goes to port 9 on this machine
# (nothing listens there, so the connection is refused).
direct='dl.google.com|*.google.com|*.googleapis.com|*.gradle.org|maven.mozilla.org|localhost|127.0.0.1'
[ $# -gt 0 ] || set -- help
GRADLE_USER_HOME="$home" ./gradlew --no-daemon --max-workers=2 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9 -Dhttps.nonProxyHosts="$direct" \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9 -Dhttp.nonProxyHosts="$direct" \
  "$@"
