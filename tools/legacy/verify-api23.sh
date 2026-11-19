#!/usr/bin/env bash
# verify-api23.sh: does Android 6's verifier take every Glassworks class in the legacy APK? (#435, #198)
#
# Installs the APK on an Android 6 (API 23) emulator and reads what dex2oat logs at install. Android 6
# verifies every class then; a class it rejects throws VerifyError when the app first uses it, which on the Mirror
# crashed the app at every start (2026-11-18, UsbDebugging$Requests). Newer Android accepts the same dex, so only
# an API 23 install shows it. Fails on a rejected class in dev.glassworks or sh.edm.s1kiosk.
#
# What it touches: an emulator only (it refuses a real device), through an adb server of its own on its own port
# with USB and mDNS off, which it stops at the end; the shared server isn't used or stopped. It installs the APK there
# and removes it after, and stops if the app is there already. The dex doesn't depend on the ABI, so the APK's native libraries are dropped from a copy first, which lets the arm64 APK install on the CI
# runners' x86_64 emulators. The copy is signed with a throwaway key made for the run.
#
#   tools/legacy/verify-api23.sh <legacy.apk> <emulator serial>     VERIFY_ADB_PORT picks the port (5077)
set -euo pipefail

apk="$1"
serial="$2"
adb="${ADB:-adb}"
build_tools="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n 1)"
work="$(mktemp -d)"

# A server of its own: one that also claims USB or mDNS devices takes them from whoever drives a real panel (on macOS
# even with --one-device, 2026-11-18). A port someone listens on already is someone else's server.
export ADB_USB=0 ADB_MDNS=0 ADB_MDNS_AUTO_CONNECT=0 ANDROID_ADB_SERVER_PORT="${VERIFY_ADB_PORT:-5077}"
if (exec 3<>"/dev/tcp/127.0.0.1/$ANDROID_ADB_SERVER_PORT") 2>/dev/null; then
  echo "verify-api23: port $ANDROID_ADB_SERVER_PORT is in use; pick a free one with VERIFY_ADB_PORT" >&2
  rm -rf "$work"
  exit 2
fi
# On any exit: the app this run installed comes off (never one that was there before), then this server stops.
installed=""
trap '[ -z "$installed" ] || "$adb" -s "$serial" uninstall "$installed" >/dev/null 2>&1 || true; "$adb" kill-server >/dev/null 2>&1 || true; rm -rf "$work"' EXIT
"$adb" start-server >/dev/null 2>&1
"$adb" -s "$serial" wait-for-device

[ "$("$adb" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo "verify-api23: $serial isn't an emulator" >&2; exit 2; }
sdk="$("$adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
[ "$sdk" = 23 ] || { echo "verify-api23: $serial runs API $sdk, not 23" >&2; exit 2; }

cp "$apk" "$work/app.apk"
zip -q -d "$work/app.apk" 'lib/*' 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.EC' 'META-INF/*.MF' >/dev/null 2>&1 || true
keytool -genkeypair -keystore "$work/k.jks" -storepass verify -keypass verify -alias v -keyalg RSA -keysize 2048 \
  -validity 1 -dname CN=verify-api23 >/dev/null 2>&1
"$build_tools/zipalign" -f 4 "$work/app.apk" "$work/aligned.apk"
"$build_tools/apksigner" sign --ks "$work/k.jks" --ks-pass pass:verify --key-pass pass:verify "$work/aligned.apk"

package="$("$build_tools/aapt2" dump badging "$work/aligned.apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
# A device that has the app already would lose it and its data here: this check wants an emulator without it.
if "$adb" -s "$serial" shell pm path "$package" 2>/dev/null | grep -q '^package:'; then
  echo "verify-api23: $package is installed on $serial; use an emulator without it (this check removes the app after)" >&2
  exit 2
fi
"$adb" -s "$serial" logcat -c
installed="$package"
"$adb" -s "$serial" install "$work/aligned.apk"
"$adb" -s "$serial" logcat -d -s dex2oat:V > "$work/dex2oat.log"

ours='(dev\.glassworks|sh\.edm\.s1kiosk)'
lines="$(wc -l < "$work/dex2oat.log" | tr -d ' ')"
rejected="$(grep -E "Verifier rejected class $ours|Verification failed on class $ours" "$work/dex2oat.log" || true)"

if [ -n "$rejected" ]; then
  echo "verify-api23: Android 6 rejects these Glassworks classes (each throws VerifyError when first used):" >&2
  grep -E "Verification error in .*$ours|failed to verify: .*$ours|Verifier rejected class $ours|Verification failed on class $ours" "$work/dex2oat.log" >&2
  exit 1
fi
echo "verify-api23: dex2oat took every Glassworks class in $package ($lines dex2oat log lines read)"
