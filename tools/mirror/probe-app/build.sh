#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 aguilaair
#
# build.sh: builds the Mirror probe (#29) twice from the same code and key, version 1 and version 2,
# runs its unit tests, checks each manifest, and prints the SHA-256 of both APKs.
# Touches no device and no network but Gradle's own downloads. Writes only build/ here and the
# output folder: $PROBE_OUT, default tools/mirror/probe-app/out/ (git-ignored).
#
# The signing key is a throwaway made on the first run, $PROBE_OUT/probe.keystore, and reused after:
# version 2 only updates a Mirror flashed with version 1 from the same folder. It signs nothing else.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
OUT="${PROBE_OUT:-$HERE/out}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | grep -v -- -rc | sort -V | tail -1)"
KS="$OUT/probe.keystore"
PASS=probe-only

mkdir -p "$OUT"
if [[ ! -f "$KS" ]]; then
  keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias probe \
    -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Glassworks Mirror probe (throwaway)" >/dev/null 2>&1
  echo "new throwaway key: $KS"
fi

"$REPO/gradlew" -q -p "$HERE" test

for v in 1 2; do
  "$REPO/gradlew" -q -p "$HERE" assembleRelease -PprobeVersion="$v"
  apk="$OUT/probe-v$v.apk"
  "$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --ks-key-alias probe --min-sdk-version 23 \
    --out "$apk" "$HERE"/build/outputs/apk/release/*-release-unsigned.apk
  rm -f "$apk.idsig"
  badging="$("$BT/aapt2" dump badging "$apk")"
  for want in "package: name='dev.glassworks.mirror.probe' versionCode='$v' versionName='$v'" "minSdkVersion:'23'" "targetSdkVersion:'23'" \
    "uses-permission: name='android.permission.INSTALL_PACKAGES'" "uses-permission: name='android.permission.REBOOT'" \
    "uses-permission: name='android.permission.WRITE_SECURE_SETTINGS'" "uses-permission: name='android.permission.MANAGE_USB'" \
    "launchable-activity: name='dev.glassworks.mirror.probe.MainActivity'"; do
    grep -qF "$want" <<<"$badging" || { echo "probe-v$v.apk: no \"$want\" in aapt2 dump badging" >&2; exit 1; }
  done
  tree="$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$apk")"
  grep -qF '"android.intent.category.HOME"' <<<"$tree" || { echo "probe-v$v.apk: no HOME intent filter" >&2; exit 1; }
  grep -qF 'android:permission(0x01010006)="android.permission.WRITE_SECURE_SETTINGS"' <<<"$tree" \
    || { echo "probe-v$v.apk: the action activity doesn't require WRITE_SECURE_SETTINGS" >&2; exit 1; }
done

# AGP's build metadata under META-INF/ isn't covered by v1 signing: apksigner warns, Android ignores it
for v in 1 2; do "$BT/apksigner" verify --min-sdk-version 23 "$OUT/probe-v$v.apk" | { grep -v 'not protected by signature' || true; }; done
[[ "$("$BT/apksigner" verify --print-certs "$OUT/probe-v1.apk" | grep SHA-256)" == "$("$BT/apksigner" verify --print-certs "$OUT/probe-v2.apk" | grep SHA-256)" ]] \
  || { echo "the two APKs aren't signed by the same key" >&2; exit 1; }
echo "signed by: $("$BT/apksigner" verify --print-certs "$OUT/probe-v1.apk" | grep 'SHA-256 digest')"
(cd "$OUT" && shasum -a 256 probe-v1.apk probe-v2.apk)
