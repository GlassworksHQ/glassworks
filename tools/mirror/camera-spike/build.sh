#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# build.sh: builds the Mirror camera spike (dev.glassworks.camspike) without Gradle: javac, d8, aapt2, zipalign,
# apksigner. Touches no device and no network. Writes only to $CAMSPIKE_OUT (default: out/ here, git-ignored),
# including a throwaway debug key made on the first run. Needs the Android SDK's android-23 platform and build-tools.
set -euo pipefail

HERE="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
OUT="${CAMSPIKE_OUT:-$HERE/out}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | grep -v -- -rc | sort -V | tail -1)"
JAR="$ANDROID_HOME/platforms/android-23/android.jar"

rm -rf "$OUT/build" && mkdir -p "$OUT/build/classes" "$OUT/build/dex"
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$JAR" -d "$OUT/build/classes" $(find "$HERE/src" -name '*.java')
"$BT/d8" --min-api 23 --lib "$JAR" --output "$OUT/build/dex" $(find "$OUT/build/classes" -name '*.class')
"$BT/aapt2" link -o "$OUT/build/base.apk" -I "$JAR" --manifest "$HERE/AndroidManifest.xml"
(cd "$OUT/build/dex" && zip -q ../base.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/build/base.apk" "$OUT/build/aligned.apk"
KS="$OUT/camspike.keystore"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass camspike -keypass camspike -alias spike -keyalg RSA \
  -validity 3650 -dname CN=camspike >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:camspike --out "$OUT/camspike.apk" "$OUT/build/aligned.apk"
echo "built $OUT/camspike.apk"
