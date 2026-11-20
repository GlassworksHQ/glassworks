#!/usr/bin/env bash
# Patch a stock S1 Plus boot.img with Magisk + the S1 frame loader, entirely on the host.
# No adb and no panel needed, so it works for factory panels (stock boot read over FEL fastboot).
#
#   frames/aqara-s1-plus/tools/root/patch-boot-magisk.sh <stock-boot.img> <out-boot.img>
#
# Runs Magisk's own boot_patch.sh in an arm64 Linux container (Docker): the arm64 magiskboot does
# the work, and the panel's armeabi-v7a magiskinit/magisk/init-ld are the payload. Then it adds the
# S1 frame's loader and the boot image's own copies of what a frame bundle may replace
# (frames/aqara-s1-plus/research/frame-loader.md):
#   overlay.d/glassworks.rc              the init service slots (frames/aqara-s1-plus/tools/root/glassworks.rc)
#   overlay.d/sbin/glassworks_loader.sh  the loader: runs each slot from the bundle or the copies below
#   overlay.d/sbin/glassworks_verify     Ed25519 checker (frames/aqara-s1-plus/tools/root/build-verify.sh)
#   overlay.d/sbin/glassworks_bundle.pub the project's bundle public key (frames/aqara-s1-plus/tools/root/glassworks_bundle.pub)
#   overlay.d/sbin/glassworks_adb_keys   this computer's adb public key, authorized on first boot so a
#                                        factory panel (no adb) can continue with frames/aqara-s1-plus/tools/install.sh
#                                        (ADB_PUBKEY=<file>, default ~/.android/adbkey.pub; none = skip)
#   overlay.d/sbin/glassworks_agent.sh   the frame agent (frames/aqara-s1-plus/tools/radio/glassworks_agent.sh)
#   overlay.d/sbin/{otbr-agent,ot-ctl}   Thread stack (frames/aqara-s1-plus/tools/radio/build-otbr.sh)
#   overlay.d/sbin/glassworks_firstboot.sh, glassworks_netwatch.sh, glassworks_update.sh
#   overlay.d/sbin/glassworks_bootanimation.zip   (BOOT_ANIMATION=<zip>, default branding/glass-tile; none = stock)
#                                        the loader bind-mounts it over /system/media/bootanimation.zip
#   overlay.d/sbin/magisk-busybox        Magisk's own busybox (lib/armeabi-v7a/libbusybox.so from the same Magisk APK);
#                                        glassworks.rc copies it to /data/adb/magisk, without which Magisk loads no module
# Nothing is flashed here; see frames/aqara-s1-plus/research/root.md.
#
# Magisk is GPLv3 (the busybox inside it GPLv2) and is not redistributed by this repo: the official release is downloaded and its
# SHA-256 checked against the digest GitHub publishes for the asset.
set -euo pipefail
MAGISK_TAG=${MAGISK_TAG:-v31.1}
MAGISK_SHA256=${MAGISK_SHA256:-89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7}
PAYLOAD_ABI=armeabi-v7a                 # the panel's userspace is 32-bit only
PREINITDEVICE=${PREINITDEVICE:-cache}   # what Magisk itself picks on this panel (--preinit-device)
IN=${1:?stock boot.img}; OUT=${2:?output boot.img}
HERE="$(cd "$(dirname "$0")" && pwd)"
RADIO="$HERE/../radio"
ADB_PUBKEY=${ADB_PUBKEY:-$HOME/.android/adbkey.pub}
BOOT_ANIMATION=${BOOT_ANIMATION:-$HERE/../../../../branding/glass-tile/bootanimation.zip}

for b in otbr-agent ot-ctl; do
  [ -x "$RADIO/out/$b" ] || { echo "missing $RADIO/out/$b: run OTBR_RUNTIME_DIR=/data/s1kiosk/radio/run frames/aqara-s1-plus/tools/radio/build-otbr.sh"; exit 1; }
done
grep -q '/data/s1kiosk/radio/run' "$RADIO/out/BUILD-INFO.txt" ||
  { echo "otbr was built with the wrong OTBR_RUNTIME_DIR (need /data/s1kiosk/radio/run)"; exit 1; }
[ -x "$HERE/out/s1kiosk_verify" ] || { echo "missing $HERE/out/s1kiosk_verify: run frames/aqara-s1-plus/tools/root/build-verify.sh"; exit 1; }
grep -qE '^[0-9a-f]{64}$' "$HERE/glassworks_bundle.pub" || { echo "$HERE/glassworks_bundle.pub is not a 32-byte hex public key"; exit 1; }
[ "$(head -c 8 "$IN")" = "ANDROID!" ] || { echo "$IN is not an Android boot image"; exit 1; }

W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
APK="$W/Magisk-$MAGISK_TAG.apk"
curl -fsSL -o "$APK" "https://github.com/topjohnwu/Magisk/releases/download/$MAGISK_TAG/Magisk-$MAGISK_TAG.apk"
echo "$MAGISK_SHA256  $APK" | shasum -a 256 -c -
unzip -q "$APK" assets/boot_patch.sh assets/util_functions.sh "lib/$PAYLOAD_ABI/*" "lib/arm64-v8a/libmagiskboot.so" -d "$W/x"
mkdir "$W/p"
cp "$W/x/assets/boot_patch.sh" "$W/x/assets/util_functions.sh" "$W/p/"
cp "$W/x/lib/arm64-v8a/libmagiskboot.so" "$W/p/magiskboot"           # runs on the host
for b in magiskinit magisk init-ld; do cp "$W/x/lib/$PAYLOAD_ABI/lib$b.so" "$W/p/$b"; done  # go in the image
cp "$W/x/lib/$PAYLOAD_ABI/libbusybox.so" "$W/p/magisk-busybox"
cp "$HERE/glassworks.rc" "$HERE/glassworks_loader.sh" "$HERE/glassworks_bundle.pub" "$W/p/"
cp "$HERE/out/s1kiosk_verify" "$W/p/glassworks_verify"   # the pinned release binary keeps its old file name
cp "$RADIO/glassworks_agent.sh" "$RADIO/out/otbr-agent" "$RADIO/out/ot-ctl" "$W/p/"
cp "$HERE/glassworks_firstboot.sh" "$HERE/glassworks_netwatch.sh" "$HERE/glassworks_update.sh" "$W/p/"
if [ "$ADB_PUBKEY" = none ]; then : > "$W/p/glassworks_adb_keys"
elif [ -s "$ADB_PUBKEY" ]; then
  grep -q '^QAAAA' "$ADB_PUBKEY" || { echo "$ADB_PUBKEY does not look like an adb public key"; exit 1; }
  cp "$ADB_PUBKEY" "$W/p/glassworks_adb_keys"
else echo "no adb public key at $ADB_PUBKEY (run 'adb start-server' once, or ADB_PUBKEY=none)"; exit 1
fi
BRANDING_CPIO=""
if [ "$BOOT_ANIMATION" != none ]; then
  [ -s "$BOOT_ANIMATION" ] || { echo "no boot animation at $BOOT_ANIMATION (BOOT_ANIMATION=none for stock)"; exit 1; }
  python3 - "$BOOT_ANIMATION" <<'PY' || exit 1
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
bad = [i.filename for i in z.infolist() if i.compress_type != zipfile.ZIP_STORED]
assert not bad, f"bootanimation.zip entries must be STORED, not compressed: {bad[:3]}"
PY
  cp "$BOOT_ANIMATION" "$W/p/glassworks_bootanimation.zip"
  BRANDING_CPIO='"add 0644 overlay.d/sbin/glassworks_bootanimation.zip glassworks_bootanimation.zip"'
fi
cp "$IN" "$W/p/boot.img"

# DEVELOPMENT ONLY: DEV_SU_HOURS=<n> adds frames/aqara-s1-plus/tools/root/dev/glassworks_devsu.{rc,sh}: adb shell root for n hours
# after each boot, plus the adb key authorized every boot. Never for a normal install.
DEV_CPIO=""
if [ -n "${DEV_SU_HOURS:-}" ]; then
  case "$DEV_SU_HOURS" in *[!0-9]*|'') echo "DEV_SU_HOURS must be a number of hours"; exit 1 ;; esac
  cp "$HERE/dev/glassworks_devsu.rc" "$W/p/"
  sed "s/__HOURS__/$DEV_SU_HOURS/" "$HERE/dev/glassworks_devsu.sh" > "$W/p/glassworks_devsu.sh"
  DEV_CPIO='"add 0644 overlay.d/glassworks_devsu.rc glassworks_devsu.rc" "add 0700 overlay.d/sbin/glassworks_devsu.sh glassworks_devsu.sh"'
  echo "WARNING: development image: adb shell gets root for $DEV_SU_HOURS h after each boot"
fi
docker run --rm --platform linux/arm64 -e PREINITDEVICE="$PREINITDEVICE" -e BRANDING_CPIO="$BRANDING_CPIO" -e DEV_CPIO="$DEV_CPIO" -v "$W/p":/w -w /w \
  arm64v8/ubuntu:22.04 sh -euc '
  chmod 755 magiskboot magiskinit magisk init-ld
  # Outside Android: boot_patch.sh needs dos2unix (its grep_prop reads the stock SHA1/PREINITDEVICE
  # back from an already-patched image) and an OUTFD for ui_print.
  mkdir -p /tmp/bin && printf "#!/bin/sh\nexec sed \"s/\\r\$//\"\n" > /tmp/bin/dos2unix && chmod 755 /tmp/bin/dos2unix
  export PATH=/tmp/bin:$PATH OUTFD=1
  KEEPVERITY=true KEEPFORCEENCRYPT=true sh boot_patch.sh /w/boot.img
  ./magiskboot cleanup
  ./magiskboot unpack new-boot.img
  # A boot image patched before still carries the overlay files added then, because the Magisk
  # restore only undoes its own changes. Drop every overlay.d entry but the Magisk ones, so a re-patch
  # equals a patch of stock (the s1kiosk_* services of a 0.4.3 image would otherwise run as well).
  for e in $(./magiskboot cpio ramdisk.cpio "ls -r overlay.d" 2>/dev/null | awk "{print \$NF}"); do
    case "$e" in
      overlay.d/sbin|overlay.d/sbin/magisk.xz|overlay.d/sbin/init-ld.xz) ;;
      overlay.d/*) ./magiskboot cpio ramdisk.cpio "rm -r $e" ;;
    esac
  done
  ./magiskboot cpio ramdisk.cpio \
    "add 0644 overlay.d/glassworks.rc glassworks.rc" \
    "add 0700 overlay.d/sbin/glassworks_loader.sh glassworks_loader.sh" \
    "add 0700 overlay.d/sbin/glassworks_verify glassworks_verify" \
    "add 0644 overlay.d/sbin/glassworks_bundle.pub glassworks_bundle.pub" \
    "add 0600 overlay.d/sbin/glassworks_adb_keys glassworks_adb_keys" \
    "add 0700 overlay.d/sbin/glassworks_agent.sh glassworks_agent.sh" \
    "add 0700 overlay.d/sbin/otbr-agent otbr-agent" \
    "add 0700 overlay.d/sbin/ot-ctl ot-ctl" \
    "add 0700 overlay.d/sbin/glassworks_firstboot.sh glassworks_firstboot.sh" \
    "add 0700 overlay.d/sbin/glassworks_netwatch.sh glassworks_netwatch.sh" \
    "add 0700 overlay.d/sbin/glassworks_update.sh glassworks_update.sh" \
    "add 0700 overlay.d/sbin/magisk-busybox magisk-busybox"
  [ -z "$BRANDING_CPIO" ] || eval "./magiskboot cpio ramdisk.cpio $BRANDING_CPIO"
  [ -z "$DEV_CPIO" ] || eval "./magiskboot cpio ramdisk.cpio $DEV_CPIO"
  ./magiskboot cpio ramdisk.cpio "extract .backup/.magisk magisk.cfg"
  # From an already-patched input the entry extracts without read permission; through the mounts of some Docker
  # hosts (macOS) root cannot override that, and set -e would stop here. The config is only printed: the image is
  # unchanged. (No apostrophes in this block: it is one single-quoted sh -c argument.)
  chmod u+r magisk.cfg
  echo "--- Magisk config:"; cat magisk.cfg
  echo "--- overlay.d:"; ./magiskboot cpio ramdisk.cpio "ls -r overlay.d"
  ./magiskboot repack new-boot.img s1-boot.img'
cp "$W/p/s1-boot.img" "$OUT"
[ "$(wc -c < "$OUT")" = "$(wc -c < "$IN")" ] || { echo "size changed: refusing"; exit 1; }
echo "patched: $OUT"
shasum -a 256 "$IN" "$OUT"
