#!/usr/bin/env bash
# Builds the S1's hotspot-name overlay (AndroidManifest.xml, res/) into <out>/hotspot-name.apk for the radio
# bundle. Off-device only: needs the Android SDK's build-tools (aapt2, zipalign, apksigner) and a platform
# android.jar, plus keytool. Signed with a key made for this one build and thrown away: Android checks a
# preinstalled overlay's signature only for being intact, and the radio bundle's signature is what the panel
# trusts. Usage: build.sh <out-dir>
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT=${1:?usage: build.sh <out-dir>}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -n 1)"
JAR="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -n 1)/android.jar"
[ -f "$JAR" ] || { echo "no android.jar under $SDK/platforms" >&2; exit 1; }
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mkdir -p "$OUT"
"$BT/aapt2" compile --dir "$HERE/res" -o "$W/res.zip"
"$BT/aapt2" link -o "$W/unsigned.apk" -I "$JAR" --manifest "$HERE/AndroidManifest.xml" \
  --min-sdk-version 29 --target-sdk-version 29 --version-code 1 --version-name 1 "$W/res.zip"
"$BT/zipalign" -p -f 4 "$W/unsigned.apk" "$W/aligned.apk"
keytool -genkeypair -keystore "$W/k.p12" -storetype PKCS12 -storepass throwaway -keypass throwaway -alias k \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Glassworks hotspot name" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$W/k.p12" --ks-pass pass:throwaway --ks-key-alias k --min-sdk-version 29 \
  --out "$OUT/hotspot-name.apk" "$W/aligned.apk"
"$BT/apksigner" verify "$OUT/hotspot-name.apk"
echo "$OUT/hotspot-name.apk $(wc -c < "$OUT/hotspot-name.apk") bytes"
