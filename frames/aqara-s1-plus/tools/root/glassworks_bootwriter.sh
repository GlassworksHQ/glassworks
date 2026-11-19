#!/system/bin/sh
# glassworks_bootwriter: puts the frame loader into a 0.4.3 S1's own boot image, over the air (#139, route 2;
# docs/research/s1-boot-writer.md). It ships in the signed radio bundle (bootwriter/), and the bundle's radio
# helper (s1kiosk_radio.sh, root, u:r:su:s0) starts it when this boot runs no loader. An app update from the
# update card and one from Kiln over USB both bring that bundle, so both end here: there is no other path.
#
# What it does, in order. Every check comes before the write; a failed check writes nothing.
#   1. Waits for the official Magisk APK (the app downloads it; rule 4: we never ship Magisk), takes it with
#      s1kiosk_verify --copy and checks the pinned SHA-256. Needs $NEED_KB free on /data.
#   2. Exports boot twice; the two must be byte-identical. The first is the backup, kept in $D for good.
#   3. Refuses an image it doesn't know: it must be one Magisk patched (magiskboot cpio test = 1) with our
#      0.4.3 services in it. One with the loader already is left alone.
#   4. Patches that same image with patch-ramdisk.sh, the FEL install's patch, under Magisk's busybox.
#      Carried over from the old image: the adb keys, the boot animation and a dev image's su service.
#   5. Refuses the result unless its kernel, dtb and header are the panel's own. Only overlay.d changes.
#   6. Writes boot, reads it back. A mismatch writes the backup back, reads that back, and stops: no restart.
#   7. Restarts once. glassworks_agent.sh, from the new image, checks the result after that restart.
# State: $D/state, one line, so a cut run (power, a helper restart) resumes from what it shows:
#   prepared <backup sha> <new sha> <bundle version>   checked and patched, not written yet
#   written|restarting <backup sha> <new sha> <bundle version> <radio mode>
#   restored|refused <bundle version> <why>            not tried again with this bundle
#   torn <backup sha> <new sha> <bundle version>       neither image read back: nothing more from here (FEL)
# For the app (Manage's Device page): $PUB/boot-writer, root 0644, one line "<state> <epoch> <detail>":
#   need-magisk | checking | refused | restored | restarting | installed | ok | error   (also the writer's log)
# Root only. Writes: $D, $PUB/boot-writer, and the boot partition (step 6 only).
D=${GWB_DIR:-/data/s1kiosk/radio/boot-writer}
FILES=${GWB_FILES:-${0%/*}}                   # this script's dir: the loader's files, patch-ramdisk.sh
BOOTFILES=${GWB_BOOTFILES:-/sbin}             # the running boot image's files (s1kiosk_verify)
RADIOFILES=${GWB_RADIO:-${FILES%/*}}          # the radio bundle (otbr-agent, ot-ctl)
DEV=${GWB_BOOTDEV:-/dev/block/by-name/boot}
APP_DATA=${GWB_APP_DATA:-/data/data/sh.edm.s1kiosk}
PUB=${GWB_PUB:-/data/glassworks-pub}
DROP_CACHES=${GWB_DROP_CACHES:-/proc/sys/vm/drop_caches}
NEED_KB=${GWB_NEED_KB:-262144}                # 256 MiB: backup, a second export, the APK and the work dir
BUNDLE_V=${GWB_BUNDLE_VERSION:-0}
APP_MAGISK=boot-writer/magisk.apk             # app -> writer, relative to the app's files/
MAGISK_SHA256=${GWB_MAGISK_SHA256:-89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7}  # v31.1 = kiln/assets.lock.json
MAGISK_SIZE=${GWB_MAGISK_SIZE:-10679283}
ABI=armeabi-v7a
W=$D/work

say() { log -t glassworks_bootwriter "$*" 2>/dev/null; }
sha() { set -- $(sha256sum "$1" 2>/dev/null); echo "$1"; }
pub() { # state detail: the app-readable record, written whole (tmp + rename)
  mkdir -p "$PUB" && chown root:root "$PUB" && chmod 755 "$PUB" || return 0
  printf '%s %s %s\n' "$1" "$(date +%s)" "$2" > "$PUB/boot-writer.tmp" && chmod 644 "$PUB/boot-writer.tmp" &&
    mv -f "$PUB/boot-writer.tmp" "$PUB/boot-writer"
  setprop sys.s1kiosk.bootwriter.status "$1" 2>/dev/null
  say "$1: $2"
}
state_set() { echo "$*" > "$D/state.tmp" && chmod 600 "$D/state.tmp" && mv -f "$D/state.tmp" "$D/state" && sync; }
refuse() { state_set refused "$BUNDLE_V" "$*"; pub refused "$*"; rm -rf "$W"; exit 0; }
wait_() { pub "$1" "$2"; rm -rf "$W"; exit 0; }   # a condition that may clear: tried again at the next start
fail() { pub error "$*"; rm -rf "$W"; exit 1; }
part_size() { blockdev --getsize64 "$DEV" 2>/dev/null || stat -c %s "$DEV" 2>/dev/null; }
export_boot() { # dest: the whole partition
  dd if="$DEV" of="$1" bs=1048576 2>/dev/null && [ "$(stat -c %s "$1" 2>/dev/null)" = "$SIZE" ]
}
readback() { # the partition's SHA-256 as read from the device, not the page cache
  sync; echo 3 > "$DROP_CACHES" 2>/dev/null
  set -- $(dd if="$DEV" bs=1048576 2>/dev/null | sha256sum); echo "$1"
}
write_boot() { # image: the whole partition, flushed
  dd if="$1" of="$DEV" bs=1048576 conv=fsync 2>/dev/null && sync
}
mode_now() { getprop persist.s1kiosk.radio_mode; }
restart() { # once: the state says so first, so a second start never restarts again
  set -- $(cat "$D/state")
  state_set restarting "$2" "$3" "$4" "$5"
  pub restarting "the loader is written and checked; restarting once"
  ${GWB_REBOOT:-setprop sys.powerctl reboot,glassworks-loader}
  exit 0
}

[ -n "$(getprop ro.glassworks.frame.id)" ] && exit 0   # this boot runs the loader: nothing to do here
{ mkdir -p "$D" && chown root:root "$D" && chmod 700 "$D"; } || exit 1   # root only, whoever made it
# One writer at a time: every helper start starts one. A lock whose writer is gone (killed) is taken over.
if ! mkdir "$D/run.lock" 2>/dev/null; then
  p_=$(cat "$D/run.lock/pid" 2>/dev/null)
  [ -n "$p_" ] && kill -0 "$p_" 2>/dev/null && exit 0
  rm -rf "$D/run.lock"; mkdir "$D/run.lock" 2>/dev/null || exit 0
fi
echo $$ > "$D/run.lock/pid"; trap 'rm -rf "$D/run.lock"' EXIT
SIZE=$(part_size)
case "$SIZE" in ''|*[!0-9]*) fail "cannot read the size of $DEV" ;; esac

# --- what an earlier run left ----------------------------------------------------------------------------
set -- $(cat "$D/state" 2>/dev/null)
case "${1:-}" in
  restarting)
    # We restarted, and this boot still runs no loader: never restart again; say what boot holds.
    if [ "$(readback)" = "$3" ]; then fail "boot holds the loader, but this boot didn't run it"
    else fail "boot changed after the write: it no longer holds the image written"; fi ;;
  written)
    # Cut short around the write (power, a helper restart). Boot holds the new image: restart into it. Boot holds
    # the backup: the write never started, so start over. Anything else: stop.
    rb_=$(readback)
    if [ "$rb_" = "$3" ]; then restart
    elif [ "$rb_" != "$2" ]; then fail "boot is neither the backup nor the image written: not touching it"; fi ;;
  torn)
    exit 0 ;;   # the error stays as it was said: recovery is FEL, never another write from here
  restored|refused)
    [ "$2" = "$BUNDLE_V" ] && exit 0 ;;   # this bundle tried already; a new bundle tries again
esac

# --- 1. space and the Magisk APK ---------------------------------------------------------------------------
free_=$(df -k "$D" 2>/dev/null | awk 'NR == 2 { print $4 }')
case "$free_" in ''|*[!0-9]*) wait_ checking "cannot read the free space on /data" ;; esac
[ "$free_" -ge "$NEED_KB" ] || wait_ waiting "not enough free space on /data: $((free_ / 1024)) MiB, needs $((NEED_KB / 1024)) MiB"
rm -rf "$W"; mkdir -p "$W/x" "$W/p" && chmod 700 "$W" || fail "cannot make $W"
uid_=$(stat -c %u "$APP_DATA" 2>/dev/null)
case "$uid_" in ''|*[!0-9]*) fail "the app isn't installed" ;; esac
"$BOOTFILES/s1kiosk_verify" --copy "$APP_DATA" "files/$APP_MAGISK" "$uid_" "$MAGISK_SIZE" "$W/magisk.apk" >/dev/null 2>&1 ||
  wait_ need-magisk "waiting for the app to download the official Magisk APK"
[ "$(sha "$W/magisk.apk")" = "$MAGISK_SHA256" ] || { rm -f "$W/magisk.apk"; wait_ need-magisk "the Magisk APK has the wrong SHA-256"; }
( cd "$W/x" && unzip -o -q "$W/magisk.apk" assets/boot_patch.sh assets/util_functions.sh \
    "lib/$ABI/libmagiskboot.so" "lib/$ABI/libmagiskinit.so" "lib/$ABI/libmagisk.so" "lib/$ABI/libinit-ld.so" \
    "lib/$ABI/libbusybox.so" ) >/dev/null 2>&1 || fail "cannot unpack the Magisk APK"
cp "$W/x/assets/boot_patch.sh" "$W/x/assets/util_functions.sh" "$W/p/" &&
  cp "$W/x/lib/$ABI/libmagiskboot.so" "$W/p/magiskboot" && cp "$W/x/lib/$ABI/libbusybox.so" "$W/p/busybox" &&
  cp "$W/x/lib/$ABI/libbusybox.so" "$W/p/magisk-busybox" && cp "$W/x/lib/$ABI/libmagiskinit.so" "$W/p/magiskinit" &&
  cp "$W/x/lib/$ABI/libmagisk.so" "$W/p/magisk" && cp "$W/x/lib/$ABI/libinit-ld.so" "$W/p/init-ld" ||
  fail "cannot unpack the Magisk APK"
chmod 755 "$W/p/magiskboot" "$W/p/busybox"
MB=$W/p/magiskboot

# --- 2. two exports, the backup ----------------------------------------------------------------------------
pub checking "exporting boot twice"
export_boot "$W/export2.img" && export_boot "$W/export1.img" || fail "cannot read $DEV"
SHA=$(sha "$W/export1.img")
[ "$SHA" = "$(sha "$W/export2.img")" ] || refuse "two exports of boot differ: nothing written"
rm -f "$W/export2.img"
# One backup per panel: the image this panel ran before any write of ours. A run cut short after it was made
# finds it again (same bytes); boot that differs from it isn't ours to judge, so nothing is written.
if [ -f "$D/backup.img" ]; then
  [ "$(sha "$D/backup.img")" = "$SHA" ] || refuse "boot differs from the backup an earlier run kept: nothing written"
else
  cp "$W/export1.img" "$D/backup.img.tmp" && chmod 600 "$D/backup.img.tmp" && sync &&
    [ "$(sha "$D/backup.img.tmp")" = "$SHA" ] && mv -f "$D/backup.img.tmp" "$D/backup.img" ||
    { rm -f "$D/backup.img.tmp"; wait_ waiting "cannot write the backup to /data"; }
  echo "$SHA" > "$D/backup.sha256"
fi

# --- 3. an image this writer knows ------------------------------------------------------------------------
mkdir -p "$W/cur" && cp "$W/export1.img" "$W/cur/boot.img" || fail "no space for the work dir"
( cd "$W/cur" && "$MB" unpack -h boot.img ) >/dev/null 2>&1 || refuse "boot is not an image Magisk's tools can read"
st_=0; ( cd "$W/cur" && "$MB" cpio ramdisk.cpio test ) >/dev/null 2>&1 || st_=$?
has() { ( cd "$W/cur" && "$MB" cpio ramdisk.cpio "exists $1" ) >/dev/null 2>&1; }
if [ "$st_" = 1 ] && has overlay.d/glassworks.rc; then
  pub installed "boot already has the frame loader"; rm -rf "$W"; exit 0
fi
[ "$st_" = 1 ] || refuse "boot is not an image Magisk patched (cpio test $st_)"
has overlay.d/s1kiosk_radio.rc || refuse "boot is a Magisk image without the 0.4.3 services: not one this writer knows"

# --- 4. the patch: the FEL install's, on this same image ---------------------------------------------------
take() { ( cd "$W/cur" && "$MB" cpio ramdisk.cpio "extract $1 $W/p/$2" ) >/dev/null 2>&1; }
cp "$W/export1.img" "$W/p/boot.img" || fail "no space for the work dir"
for f in glassworks.rc glassworks_loader.sh glassworks_bundle.pub glassworks_agent.sh glassworks_firstboot.sh \
    glassworks_netwatch.sh glassworks_update.sh patch-ramdisk.sh; do
  cp "$FILES/$f" "$W/p/" || fail "the bundle has no $f"
done
cp "$BOOTFILES/s1kiosk_verify" "$W/p/glassworks_verify" || fail "no s1kiosk_verify in this boot image"
for b in otbr-agent ot-ctl; do cp "$RADIOFILES/$b" "$W/p/" || fail "the bundle has no $b"; done
take overlay.d/sbin/s1kiosk_adb_keys glassworks_adb_keys || : > "$W/p/glassworks_adb_keys"
{
  echo "0644 overlay.d/glassworks.rc glassworks.rc"
  echo "0700 overlay.d/sbin/glassworks_loader.sh glassworks_loader.sh"
  echo "0700 overlay.d/sbin/glassworks_verify glassworks_verify"
  echo "0644 overlay.d/sbin/glassworks_bundle.pub glassworks_bundle.pub"
  echo "0600 overlay.d/sbin/glassworks_adb_keys glassworks_adb_keys"
  echo "0700 overlay.d/sbin/glassworks_agent.sh glassworks_agent.sh"
  echo "0700 overlay.d/sbin/otbr-agent otbr-agent"
  echo "0700 overlay.d/sbin/ot-ctl ot-ctl"
  echo "0700 overlay.d/sbin/glassworks_firstboot.sh glassworks_firstboot.sh"
  echo "0700 overlay.d/sbin/glassworks_netwatch.sh glassworks_netwatch.sh"
  echo "0700 overlay.d/sbin/glassworks_update.sh glassworks_update.sh"
  echo "0700 overlay.d/sbin/magisk-busybox magisk-busybox"
  # the image's own boot animation (none: stock), and a dev image's su service, as they are
  take overlay.d/sbin/s1kiosk_bootanimation.zip glassworks_bootanimation.zip &&
    echo "0644 overlay.d/sbin/glassworks_bootanimation.zip glassworks_bootanimation.zip"
  take overlay.d/s1kiosk_devsu.rc s1kiosk_devsu.rc && take overlay.d/sbin/s1kiosk_devsu.sh s1kiosk_devsu.sh &&
    echo "0644 overlay.d/s1kiosk_devsu.rc s1kiosk_devsu.rc" && echo "0700 overlay.d/sbin/s1kiosk_devsu.sh s1kiosk_devsu.sh"
} > "$W/p/overlay.list"
pub checking "patching a copy of boot"
( cd "$W/p" && BOOTMODE=true ASH_STANDALONE=1 PREINITDEVICE=cache PATCH_SH="./busybox sh" ./busybox sh patch-ramdisk.sh ) \
  > "$W/patch.log" 2>&1 || { cp "$W/patch.log" "$D/patch.log"; refuse "the patch failed on this image (patch.log)"; }
cp "$W/patch.log" "$D/patch.log"
NEW=$W/p/s1-boot.img
[ "$(stat -c %s "$NEW" 2>/dev/null)" -le "$SIZE" ] 2>/dev/null || refuse "the patched image is larger than boot"
# padded to the partition with zeros, so the readback hash covers all of what is there afterwards
n_=$(( SIZE - $(stat -c %s "$NEW") ))
[ "$n_" = 0 ] || head -c "$n_" /dev/zero >> "$NEW"
[ "$(stat -c %s "$NEW")" = "$SIZE" ] || fail "cannot pad the patched image"

# --- 5. same kernel, dtb and header ------------------------------------------------------------------------
mkdir -p "$W/new" && cp "$NEW" "$W/new/boot.img" && ( cd "$W/new" && "$MB" unpack -h boot.img ) >/dev/null 2>&1 ||
  refuse "the patched image can't be read back"
for part in kernel dtb second extra kernel_dtb; do
  if [ -f "$W/cur/$part" ] || [ -f "$W/new/$part" ]; then
    [ "$(sha "$W/cur/$part")" = "$(sha "$W/new/$part")" ] || refuse "the patched image's $part differs from the panel's"
  fi
done
grep -v '^ramdisk' "$W/cur/header" > "$W/cur/header.cmp"; grep -v '^ramdisk' "$W/new/header" > "$W/new/header.cmp"
cmp -s "$W/cur/header.cmp" "$W/new/header.cmp" || refuse "the patched image's header (command line, version) differs"
( cd "$W/new" && "$MB" cpio ramdisk.cpio "exists overlay.d/glassworks.rc" && ! "$MB" cpio ramdisk.cpio "exists overlay.d/s1kiosk_radio.rc" ) \
  >/dev/null 2>&1 || refuse "the patched image doesn't hold the loader in place of the 0.4.3 services"
NSHA=$(sha "$NEW")
MODE0=$(mode_now)
state_set prepared "$SHA" "$NSHA" "$BUNDLE_V"

# --- 6. the write ------------------------------------------------------------------------------------------
# init kills a service's whole process group, by cgroup, when it restarts it (any app may ask, SELinux being
# permissive). Out of that group, a helper restart during the write can't cut it short. Best effort.
[ -w /acct/cgroup.procs ] && echo $$ > /acct/cgroup.procs 2>/dev/null
pub checking "writing boot"
state_set written "$SHA" "$NSHA" "$BUNDLE_V" "${MODE0:--}"
if ! write_boot "$NEW"; then got_=""; else got_=$(readback); fi
# Root-made test switch for the panel run (docs/research/s1-boot-writer.md): treat this readback as a mismatch
if [ -f "$D/force-readback-mismatch" ] && [ ! -L "$D/force-readback-mismatch" ] &&
   [ "$(stat -c %u "$D/force-readback-mismatch")" = 0 ]; then
  rm -f "$D/force-readback-mismatch"; say "forced readback mismatch (test switch)"; got_=forced
fi
if [ "$got_" != "$NSHA" ]; then
  state_set prepared "$SHA" "$NSHA" "$BUNDLE_V"   # boot isn't known good: never restart into it
  say "readback ${got_:-failed}, wanted $NSHA: writing the backup back"
  write_boot "$D/backup.img"
  if [ "$(readback)" = "$SHA" ]; then
    state_set restored "$BUNDLE_V" "readback mismatch"
    pub restored "boot read back wrong after the write; the backup is back and checked; no restart"
    rm -rf "$W"; exit 1
  fi
  state_set torn "$SHA" "$NSHA" "$BUNDLE_V"
  pub error "boot read back wrong, and so did the backup written back: do not restart; recover with FEL"
  exit 1
fi
rm -rf "$W"

# --- 7. once -----------------------------------------------------------------------------------------------
restart
