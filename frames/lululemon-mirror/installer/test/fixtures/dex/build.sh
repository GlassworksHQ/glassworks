#!/usr/bin/env bash
# Rebuilds classes.dex from UsbDebuggingFixture.java: javac (Java 8 bytecode, debug info), then d8
# from the Android SDK's build-tools (minSdk 23, as the Mirror). Touches no device; writes only
# classes.dex here and a temporary folder.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
d8=$(ls -d "$sdk"/build-tools/*/d8 | sort -V | tail -1)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
javac --release 8 -g -d "$tmp/classes" "$here/UsbDebuggingFixture.java"
"$d8" --debug --min-api 23 --output "$tmp" $(find "$tmp/classes" -name '*.class')
cp "$tmp/classes.dex" "$here/classes.dex"
echo "$here/classes.dex: $(wc -c < "$here/classes.dex") bytes"
