#!/usr/bin/env bash
# Checks that an APK still installs *over* the app already on devices, keeping its data, its
# device-admin rights and the names the root helper and the installers rely on. Run on every PR
# (unsigned build) and on releases and test builds (signed, with --signed).
#
#   tools/release/check-update-compat.sh <apk>                  the S1 app, identifiers only
#   tools/release/check-update-compat.sh --signed <apk>         also the release signing certificate
#   tools/release/check-update-compat.sh --app <app> [--signed] <apk>
#       <app>: s1-kiosk (the default), generic-android or generic-android-legacy (Glassworks for
#       generic Android and its legacy build, #23 and #198; their own key, generic-release-cert.sha256)
#
# What breaks updates in place, and is checked here:
#   - another applicationId: Android installs it as a second app, and the device keeps the old one;
#   - a different signing key: Android refuses the update (only with --signed; PR builds are unsigned);
#   - adding android:sharedUserId: Android refuses the update;
#   - a renamed component that something outside the APK names. AdminReceiver: device admin (and the
#     generic app's device owner) is granted to the component, so a rename silently drops it (no
#     lockNow, no silent updates). MainActivity, ImprovActivity: started by name by
#     frames/aqara-s1-plus/tools/install.sh, the web installer and the docs. BootReceiver: autostart.
#     The generic app's Launcher alias: Kiln and the Mirror's image make it the home screen by name.
#     The Kotlin package rename (#22) must keep these names, e.g. with a subclass under the old name.
# The versionCode isn't checked here: the release workflows check it against the tag's version.
# Needs aapt2 (and apksigner for --signed) from the newest Android build-tools under $ANDROID_HOME
# (or $ANDROID_SDK_ROOT, or ~/Library/Android/sdk).
set -euo pipefail

usage="usage: check-update-compat.sh [--app aqara-s1-plus|generic-android|generic-android-legacy] [--signed] <apk>"
signed=false app=aqara-s1-plus
while [ $# -gt 1 ]; do
  case "$1" in
    --signed) signed=true; shift ;;
    --app) app="$2"; shift 2 ;;
    *) break ;;
  esac
done
apk="${1:?$usage}"
[ -f "$apk" ] || { echo "no such APK: $apk" >&2; exit 2; }

HERE="$(cd "$(dirname "$0")" && pwd)"
# Both generic builds run the core's classes under their own names since #527, plus the launcher alias.
GENERIC_COMPONENTS=(
  dev.glassworks.AdminReceiver
  dev.glassworks.MainActivity
  dev.glassworks.provisioning.ImprovActivity
  dev.glassworks.BootReceiver
  dev.glassworks.generic.Launcher
)
case "$app" in
  aqara-s1-plus)
    PKG=sh.edm.s1kiosk CERT_FILE=release-cert.sha256
    COMPONENTS=(
      "$PKG.AdminReceiver"
      "$PKG.MainActivity"
      "$PKG.improv.ImprovActivity"
      "$PKG.BootReceiver"
    ) ;;
  generic-android) PKG=dev.glassworks.generic_android CERT_FILE=generic-release-cert.sha256 COMPONENTS=("${GENERIC_COMPONENTS[@]}") ;;
  generic-android-legacy) PKG=dev.glassworks.generic_android.legacy CERT_FILE=generic-release-cert.sha256 COMPONENTS=("${GENERIC_COMPONENTS[@]}") ;;
  *) echo "unknown app '$app'; $usage" >&2; exit 2 ;;
esac

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
bt="$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -n 1)"
[ -x "$bt/aapt2" ] || { echo "aapt2 not found under $sdk/build-tools" >&2; exit 2; }

fails=0
fail() { echo "::error::$*"; fails=$((fails + 1)); }

manifest="$("$bt/aapt2" dump xmltree --file AndroidManifest.xml "$apk")"
package="$(sed -n 's/^ *A: package="\([^"]*\)".*/\1/p' <<<"$manifest" | head -n 1)"
[ "$package" = "$PKG" ] || fail "applicationId is '$package', not $PKG: devices would get a second app instead of an update"
if grep -q 'sharedUserId' <<<"$manifest"; then fail "android:sharedUserId was added: Android refuses to update an app into a shared user"; fi
for c in "${COMPONENTS[@]}"; do
  grep -qF "android:name(0x01010003)=\"$c\"" <<<"$manifest" || fail "component $c is gone or renamed"
done

if $signed; then
  want="$(tr -d ' \n' < "$HERE/$CERT_FILE")"
  # Every signer's certificate, whatever apksigner calls them ("Signer #1 certificate …" in older
  # build-tools, "V2 Signer: certificate …" in newer ones). Exactly one certificate is allowed.
  got="$("$bt/apksigner" verify --print-certs "$apk" | sed -n -E 's/^.*[Ss]igner.*certificate SHA-256 digest: ([0-9a-f]{64})$/\1/p' | sort -u | tr '\n' ' ' | sed 's/ $//')"
  [ "$got" = "$want" ] || fail "signed with certificate ${got:-<none>}, not the release key $want: devices would refuse the update"
fi

if [ "$fails" -gt 0 ]; then echo "$fails problem(s): this APK would not update devices in place"; exit 1; fi
echo "ok: $PKG, ${#COMPONENTS[@]} stable components, no sharedUserId$($signed && echo ', release certificate')"
