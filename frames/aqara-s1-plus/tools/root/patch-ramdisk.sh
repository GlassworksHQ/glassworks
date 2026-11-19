#!/bin/sh
# The S1 boot patch itself: Magisk's own boot_patch.sh, then our overlay.d files. One copy, run in two places:
#   - frames/aqara-s1-plus/tools/root/patch-boot-magisk.sh (the FEL install and the CLI), in an arm64 Linux container;
#   - glassworks_bootwriter.sh on the panel (the boot writer of #139, route 2), under Magisk's own busybox.
# Kiln's TypeScript patcher (kiln/packages/bootpatch) is checked byte for byte against the first.
#
# Runs in a work dir that holds, all next to each other:
#   boot.img                         the image to patch (stock, or one Magisk patched before)
#   magiskboot                       for the machine this runs on
#   magiskinit magisk init-ld        the panel's armeabi-v7a payload, from the same Magisk APK
#   boot_patch.sh util_functions.sh  from that APK's assets/
#   overlay.list                     what goes into overlay.d: one "<mode> <cpio path> <file in this dir>" a line
#   and every file overlay.list names.
# Writes s1-boot.img there. Environment:
#   PREINITDEVICE  Magisk's pre-init partition for a stock image; a patched image keeps its own (read below)
#   PATCH_SH       the shell for boot_patch.sh (default sh)
#   BOOTMODE       true on a running Android: then boot_patch.sh's abort never runs Magisk's recovery cleanup,
#                  which unmounts /system and /vendor (fine in a container, not on a live panel)
# Writes nothing outside this dir and touches no device. Needs: sh, awk, sed, chmod.
set -eu
W=$(pwd)
chmod 755 magiskboot magiskinit magisk init-ld

# A patched image keeps its pre-init partition. Outside Android boot_patch.sh reads it from the image's own
# config; with BOOTMODE=true it would ask `magisk --preinit-device` instead, so read it here, the same way,
# and both runs give the same config.
if [ "${BOOTMODE:-}" = true ]; then
  ./magiskboot unpack boot.img >/dev/null 2>&1
  st_=0; ./magiskboot cpio ramdisk.cpio test || st_=$?
  if [ "$st_" = 1 ]; then
    ./magiskboot cpio ramdisk.cpio "extract .backup/.magisk config.in" >/dev/null 2>&1
    chmod u+r config.in
    p_=$(sed -n 's/^PREINITDEVICE=//p' config.in | head -n 1)
    [ -z "$p_" ] || PREINITDEVICE=$p_
    rm -f config.in
  fi
  ./magiskboot cleanup
fi
export PREINITDEVICE

KEEPVERITY=true KEEPFORCEENCRYPT=true ${PATCH_SH:-sh} boot_patch.sh "$W/boot.img"
./magiskboot cleanup
./magiskboot unpack new-boot.img
# A boot image patched before still carries the overlay files added then, because the Magisk
# restore only undoes its own changes. Drop every overlay.d entry but the Magisk ones, so a re-patch
# equals a patch of stock (the s1kiosk_* services of a 0.4.3 image would otherwise run as well).
for e in $(./magiskboot cpio ramdisk.cpio "ls -r overlay.d" 2>/dev/null | awk '{print $NF}'); do
  case "$e" in
    overlay.d/sbin|overlay.d/sbin/magisk.xz|overlay.d/sbin/init-ld.xz) ;;
    overlay.d/*) ./magiskboot cpio ramdisk.cpio "rm -r $e" ;;
  esac
done
# Our files, in one cpio call (the cpio keeps its entries sorted, so the order of the list doesn't matter)
set --
while read -r m_ p_ f_; do
  [ -n "$m_" ] || continue
  [ -f "$f_" ] || { echo "patch-ramdisk: $f_ is missing (overlay.list)" >&2; exit 1; }
  set -- "$@" "add $m_ $p_ $f_"
done < overlay.list
[ $# -gt 0 ] || { echo "patch-ramdisk: overlay.list is empty" >&2; exit 1; }
./magiskboot cpio ramdisk.cpio "$@"
./magiskboot cpio ramdisk.cpio "extract .backup/.magisk magisk.cfg"
# From an already-patched input the entry extracts without read permission; through the mounts of some Docker
# hosts (macOS) root cannot override that. The config is only printed: the image is unchanged.
chmod u+r magisk.cfg
echo "--- Magisk config:"; cat magisk.cfg
echo "--- overlay.d:"; ./magiskboot cpio ramdisk.cpio "ls -r overlay.d"
./magiskboot repack new-boot.img s1-boot.img
