#!/system/bin/sh
# Glassworks first-boot provisioning. Runs as root from the Magisk boot image (the glassworks_firstboot slot in glassworks.rc),
# once per panel: a marker in /data/glassworks stops it from undoing a later user choice.
#
# Factory firmware exposes no adb. The installer bakes the installing computer's adb public key into
# the boot image (overlay.d/sbin/glassworks_adb_keys); this authorizes that key and enables adb over USB,
# so tools/install.sh can continue with no screen interaction.
MARK=/data/glassworks/.firstboot-done
KEYS=${GW_BOOT:-${0%/*}}/glassworks_adb_keys   # the boot image's, also when this script comes from a bundle
ADB_KEYS=/data/misc/adb/adb_keys
log() { echo "glassworks_firstboot: $*" > /dev/kmsg; }

[ -e "$MARK" ] && exit 0
mkdir -p /data/glassworks && chown root:root /data/glassworks && chmod 700 /data/glassworks

if [ -s "$KEYS" ]; then
  mkdir -p /data/misc/adb
  [ -e "$ADB_KEYS" ] || : > "$ADB_KEYS"
  n=0
  while IFS= read -r k; do
    [ -n "$k" ] || continue
    grep -qxF "$k" "$ADB_KEYS" || { echo "$k" >> "$ADB_KEYS"; n=$((n + 1)); }
  done < "$KEYS"
  chown system:shell "$ADB_KEYS"; chmod 0640 "$ADB_KEYS"; restorecon "$ADB_KEYS" 2>/dev/null
  cfg=$(getprop persist.sys.usb.config)
  case ",$cfg," in
    *,adb,*) ;;
    ,,|,none,) setprop persist.sys.usb.config adb ;;
    *) setprop persist.sys.usb.config "$cfg,adb" ;;
  esac
  settings put global adb_enabled 1
  log "authorized $n new adb key(s); usb config $(getprop persist.sys.usb.config)"
else
  log "no installer adb key in the boot image; adb left as is"
fi
# A factory-reset panel boots with Wi-Fi off, and Android 10 won't let the kiosk app (targetSdk > 28)
# turn it on, so Improv (over Bluetooth or from the installer) and "Choose network" couldn't join.
# Turn it on once here; the user can still switch it off later.
if [ "$(settings get global wifi_on)" != 1 ]; then svc wifi enable && log "Wi-Fi turned on"; fi
touch "$MARK"
