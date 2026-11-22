#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# build.sh: builds the zero-Gradle Mirror stock probe APK (dev.glassworks.stockprobe) and,
# when an Android NDK is installed, the optional arm64 libfiemap.so JNI library.
# Touches no device and no network. Writes only to $STOCK_PROBE_OUT (default: out/, git-ignored).
set -euo pipefail

HERE="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
OUT="${STOCK_PROBE_OUT:-$HERE/out}"
mkdir -p "$OUT"
OUT="$(CDPATH= cd -- "$OUT" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

BT="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | grep -v -- -rc | sort -V | tail -1 || true)"
PLAT_JAR="$(ls "$ANDROID_HOME"/platforms/android-23/android.jar 2>/dev/null || ls "$ANDROID_HOME"/platforms/android-*/android.jar 2>/dev/null | sort -V | head -1 || true)"
[ -n "$BT" ] || { echo "stock-probe-app: no Android SDK build-tools under $ANDROID_HOME/build-tools" >&2; exit 1; }
[ -n "$PLAT_JAR" ] || { echo "stock-probe-app: no android.jar under $ANDROID_HOME/platforms" >&2; exit 1; }

WORK="$OUT/work"
KS="$OUT/stock-probe.keystore"
PASS=stock-probe-only
rm -rf "$WORK" && mkdir -p "$WORK/classes" "$WORK/dex" "$OUT"

if [[ ! -f "$KS" ]]; then
  keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias probe \
    -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Glassworks Stock Probe (throwaway)" >/dev/null 2>&1
fi

javac --release 8 -cp "$PLAT_JAR" -d "$WORK/classes" \
  "$HERE"/src/dev/glassworks/stockprobe/*.java
"$BT/d8" --min-api 23 --lib "$PLAT_JAR" --output "$WORK/dex" \
  "$WORK"/classes/dev/glassworks/stockprobe/*.class
"$BT/aapt2" link -I "$PLAT_JAR" --manifest "$HERE/AndroidManifest.xml" -o "$WORK/unsigned.apk"
(cd "$WORK/dex" && zip -q -u "$WORK/unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --v1-signing-enabled true --v2-signing-enabled false \
  --ks "$KS" --ks-pass "pass:$PASS" --ks-key-alias probe \
  --out "$OUT/stock-probe.apk" "$WORK/aligned.apk"
rm -f "$OUT/stock-probe.apk.idsig"
keytool -exportcert -keystore "$KS" -storepass "$PASS" -alias probe | xxd -p -c 100000 > "$OUT/cert.hex"

# Optional arm64 JNI library for FS_IOC_FIEMAP (unpacked into /data/app/.../lib/arm64/libfiemap.so)
NDK_CC="$(ls "$ANDROID_HOME"/ndk/*/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android23-clang 2>/dev/null | sort -V | tail -1 || true)"
rm -f "$OUT/libfiemap.so"
if [[ -n "$NDK_CC" ]]; then
  "$NDK_CC" -shared -fPIC -O2 -Wall -o "$OUT/libfiemap.so" "$HERE/jni/fiemap.c"
  echo "built optional libfiemap.so (arm64-v8a)"
else
  echo "note: no Android NDK found; skipping optional libfiemap.so"
fi

rm -rf "$WORK"
echo "built: $OUT/stock-probe.apk"
echo "cert:  $OUT/cert.hex"
