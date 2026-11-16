#!/usr/bin/env bash
# Builds GlassworksSettings.apk, the Mirror's SettingsProvider overlay, from AndroidManifest.xml and
# res/. Touches no device: it reads the Android SDK's build tools, downloads AOSP's public test key
# (android-6.0.1_r81, checked by SHA-256) into a temporary folder, and writes only the APK here.
# Static overlays on Android 6 are trusted by where they are (/vendor/overlay), not by their key.
#   ANDROID_HOME=~/Library/Android/sdk frames/lululemon-mirror/overlay/build.sh
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | grep -v rc | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | grep -v '\.' | tail -1)/android.jar
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
KEYS=https://android.googlesource.com/platform/build/+/refs/tags/android-6.0.1_r81/target/product/security
key() { curl -sfL "$KEYS/$1?format=TEXT" | base64 -d > "$TMP/$1"; echo "$2  $TMP/$1" | shasum -a 256 -c - >/dev/null; }
key testkey.pk8 495675d32e89a149d5abe191f4e9c0e218b9068714e9b53a7c91e164a0741a23
key testkey.x509.pem a4384ba815b9499a5ce349b4e33c1755278873fe2eac150a068823f526e6dbde
"$BT/aapt2" compile --dir res -o "$TMP/res.zip"
"$BT/aapt2" link -o "$TMP/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 23 --target-sdk-version 23 --no-version-vectors "$TMP/res.zip"
"$BT/zipalign" -f 4 "$TMP/unsigned.apk" "$TMP/aligned.apk"
"$BT/apksigner" sign --key "$TMP/testkey.pk8" --cert "$TMP/testkey.x509.pem" \
  --min-sdk-version 23 --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
  --out GlassworksSettings.apk "$TMP/aligned.apk"
rm -f GlassworksSettings.apk.idsig
"$BT/apksigner" verify --min-sdk-version 23 GlassworksSettings.apk
shasum -a 256 GlassworksSettings.apk
