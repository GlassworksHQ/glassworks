#!/system/bin/sh
# A trial of the adbd key check (frames/aqara-s1-plus/research/adb-keys.md, "The fix") on a panel where the frame agent
# has made the glassworks_adbd_auth module: its patched adbd runs for at most $1 seconds (default 900), then Aqara's
# adbd again. RAM-only: a bind mount over /system/bin/adbd and two adbd restarts; no file on /system changes, and a
# reboot undoes it too. Every adb connection drops at each restart. Run it detached, as root, in the global mount
# namespace, from /data/local/tmp/gw20c/:
#   su -mm -c 'nohup sh /data/local/tmp/gw20c/trial.sh 900 </dev/null >/dev/null 2>&1 &'
# It ends early once /data/local/tmp/gw20c/done exists. Its log: /data/local/tmp/gw20c/log.
D=/data/local/tmp/gw20c
M=/data/adb/modules/glassworks_adbd_auth/system/bin/adbd
FIXED=a5648f6c772ca074d84b8b1fab332f58faf652dab30bcd47fc120fbb8657d16d
W=${1:-900}
log() { echo "$(date +%s) $*" >> $D/log; }
h() { sha256sum "$1" 2>/dev/null | cut -c1-12; }
restart() { stop adbd; sleep 1; start adbd; }
back() {
  umount /system/bin/adbd 2>/dev/null
  log "unmounted: /system/bin/adbd $(h /system/bin/adbd)"
  restart; sleep 5
  log "Aqara's adbd again: $(getprop init.svc.adbd), exe $(h /proc/$(pidof adbd)/exe)"
}
rm -f $D/done
[ "$(sha256sum $M 2>/dev/null | cut -d' ' -f1)" = "$FIXED" ] || { log "STOP: the module's adbd isn't the patched one"; exit 1; }
grep -q ' /system/bin/adbd ' /proc/mounts && { log "STOP: something is already mounted on /system/bin/adbd"; exit 1; }
sleep 3   # let the adb shell that started this return first
mount -o bind $M /system/bin/adbd || { log "STOP: bind mount failed"; exit 1; }
log "mounted: /system/bin/adbd $(h /system/bin/adbd)"
restart
i=0
while [ $i -lt "$W" ] && [ ! -e $D/done ]; do
  sleep 5; i=$((i + 5))
  case $i in 10|30|60) log "at ${i}s: adbd $(getprop init.svc.adbd), pid $(pidof adbd), exe $(h /proc/$(pidof adbd)/exe)" ;; esac
done
[ -e $D/done ] && log "ended early (done) at ${i}s" || log "time's up at ${i}s"
back
