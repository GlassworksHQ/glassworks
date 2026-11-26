---
topic: install
evidence: read
---

# Stock Mirror hotspot: why stock 1.24.0 keeps opening `mirror-<serial>`, and how the in-place patch turns it off

**Status (2026-11-25): untested on a Mirror.** The patch is written and checked off-device against the
real stock 1.24.0 `services.jar`: `MirrorApService.systemReady()` decompiles to an empty method, the jar
keeps its exact length, and the other three edits are still there. Whether the hotspot stays off after a
reboot, and whether Glassworks' own setup hotspot then opens, needs a run on a real Mirror.

Device: lululemon Studio Mirror (MSM8916), stock software 1.24.0, with Glassworks installed in place
(Stock-in-place: the stock system kept, `services.jar` patched). Tools: jadx (Homebrew), Android SDK
`dexdump` (build-tools 36.0.0), the patcher in
[`installer/src/stock.ts`](../installer/src/stock.ts) run under Vitest.

## Question

On a Mirror running stock software with Glassworks in place, stock still brings up its own onboarding
hotspot, `mirror-<serial>` at 192.168.43.1, about a minute after every boot with no network, and again
whenever the network drops. Glassworks then reports 192.168.43.1 as "on this network", and its own setup
hotspot can't start while stock holds the radio in AP mode. The hotspot exists only for lululemon's own
onboarding, which Glassworks replaces. Can Kiln turn it off without writing anything it doesn't already
write, and with the same way back?

## How we got here

**The symptom (2026-11-25, verified on the maintainer's Mirror over adb).** With Glassworks in place on
stock 1.24.0 and no upstream network, the Mirror came up with:

- `wlan0_ap` holding `inet 192.168.43.1/24`;
- `hostapd` and `dnsmasq` running, both children of `mapd`;
- `dumpsys connectivity` listing `wlan0_ap - TetheredState` under its tether state;
- `dumpsys wifi` showing WifiStateMachine in `ConnectModeState`, that is, client mode.

The reads behind each line, all read-only over adb (the serial is redacted):

```
$ adb shell ip addr show wlan0_ap | grep inet
    inet 192.168.43.1/24 brd 192.168.43.255 scope global wlan0_ap
$ adb shell ps | grep -E 'mapd|hostapd|dnsmasq'      # hostapd's and dnsmasq's PPID is mapd's PID
$ adb shell getprop init.svc.mapd
running
$ adb shell dumpsys connectivity | grep -A2 -i tether   # wlan0_ap - TetheredState
$ adb shell dumpsys wifi | grep -m1 -i 'curState'       # ConnectModeState
```

The `ps`, `dumpsys connectivity` and `dumpsys wifi` lines are given as what to look for, not as a capture:
the session's raw output wasn't kept.

The last one matters. Wi-Fi's own state machine was in client mode, so this AP isn't Android's SoftAP
(`WifiManager.setWifiApEnabled`): something outside WifiManager adds a second interface, `wlan0_ap`, and
runs `hostapd` on it. `hostapd` being a child of `mapd`, a Mirror-specific init service
(`init.svc.mapd=running`, [stock-firmware-probe.md §4](stock-firmware-probe.md#4-stock-adbd-initqcompost_bootsh-servicesjar-and-mirrorapservice-verified--read)),
pointed at `mapd` as the one doing the work, and at whoever drives `mapd` as the one deciding when.

**Who drives `mapd` (2026-11-25, read).** jadx on the stock 1.24.0 `services.jar` finds
`com.android.server.MirrorApService` in system_server. Its constructor builds a `NativeDaemonConnector` on
the `mapd` socket, a thread named `MapdConnector` to run it, and a state machine
(`MirrorApService$ApStateMachine`). With no upstream network for `START_HOSTAP_TIMEOUT_MS = 60000`, the state
machine asks `mapd` to add `wlan0_ap`, start `hostapd` with SSID `mirror-<serial>` at 192.168.43.1 and tether
it. It restarts the AP when connectivity drops, and also starts it on the broadcast
`com.mirror.framework.ACTION_START_HOST_APD`.

**Why hiding the stock apps didn't stop it.** The Stock-in-place flow hides lululemon's eight apps with
`pm hide` ([stock-firmware-probe.md](stock-firmware-probe.md), Track A, verified 2026-11-25), which stops
every one of their processes. None of them owns this: `MirrorApService` is a class inside
`/system/framework/services.jar` that runs in system_server, and `mapd` is an init service. `pm hide`
only reaches packages. (read)

**The levers we weighed.**

| Lever | Why not |
|---|---|
| Hide or disable more packages | There is no package to hide: the service is in system_server. |
| `setprop ctl.stop mapd` | Android 6's init lets a process start or stop a service only if SELinux lets it set that `ctl.*` property. In AOSP 6.0.1's policy `shell` has that for a few named services only, and `untrusted_app` (Glassworks without root) for none (from AOSP 6.0.1's init and policy; **untested** against stock 1.24.0's own `/sepolicy`). It wouldn't survive a reboot either, and `MirrorApService` would ask `mapd` again. |
| Drop `mapd` from init | Android 6's init reads services only from the ramdisk's rc files (`/system/etc/init` arrived with Android 7), and the ramdisk is in the `boot` partition, which Kiln never writes on the Mirror. (From AOSP; which stock rc file defines `mapd` wasn't checked.) |
| Replace `/system/bin/mapd` in `system` | It would be writable with the same surgical sparse write, but init would restart a missing or failing service in a loop, `MirrorApService` would log connector failures forever, and we'd be shipping a stand-in for a vendor binary. More risk than the problem. |
| Make Glassworks live with it | Ignoring 192.168.43.1 in "on this network" would hide the symptom, but the radio stays in AP mode on `wlan0_ap`, so Glassworks' own setup hotspot still can't start. |
| Patch `MirrorApService`'s timeout or state machine | Several edits in code we'd have to model state by state. Each one is a guess about behavior we'd only understand from a decompile. |
| **Empty `MirrorApService.systemReady()`** | **One method, the whole body, in the jar we already patch, with the same exact-size write and the same restore image.** |

**Why `systemReady()` is the smallest lever (2026-11-25, read).** In the stock 1.24.0 `classes.dex`,
only `com.android.server.SystemServer` refers to `MirrorApService`. `startOtherServices()` calls
`MirrorApService.create(context)`, and `SystemServer$2.run()`, the Runnable that ActivityManagerService
runs at `systemReady`, calls `mirrorApService.systemReady()` inside a try/catch that reports to
`reportWtf`. The service is never added to ServiceManager, so nothing else can reach it. The constructor
starts nothing: it builds the connector object, an unstarted `Thread`, an unstarted state machine on the
existing `FgThread` looper, four receiver objects and the SSID string. `systemReady()` is what starts
everything:

```java
void systemReady() {
    this.mWifiManager = (WifiManager) this.mContext.getApplicationContext().getSystemService("wifi");
    this.mConnectivityManager = (ConnectivityManager) this.mContext.getSystemService("connectivity");
    this.mStateMachine.start();
    this.mContext.registerReceiver(this.mWifiStateBroadcastReceiver, new IntentFilter("android.net.wifi.WIFI_STATE_CHANGED"));
    this.mContext.registerReceiver(this.mConnectivityChangedBroadcastReceiver, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));
    this.mContext.registerReceiver(this.mTetherStateChangedBroadcastReceiver, new IntentFilter("android.net.conn.TETHER_STATE_CHANGED"));
    this.mContext.registerReceiver(this.mStartHostApBroadcastReceiver, new IntentFilter(ACTION_START_HOST_APD));
    this.mThread.start();
}
```

If `systemReady()` returns at once, the state machine never starts, no receiver is registered, the
connector thread never runs, and system_server never opens the `mapd` socket. SystemServer still builds
the object and still calls the method, so its own code is untouched, and a `return-void` can't throw, so
its try/catch never fires. `mapd` itself keeps running as an idle init service. That it does nothing
without a client is the one part we can't read from `services.jar`; it's what the Mirror run checks.

**Why Kiln's in-place stages still work.** An in-place install runs, in order: *stage* (a small userdata
that starts the frame's agent on the stock software), a restart, *receive* (Kiln gets the bundle the agent
reads, over the LAN, Bluetooth LE or a file), fastboot, the *in-place* write (the patched `services.jar`
blocks), a restart, then *app-install* over USB adb ([kiln-core `images.ts`](../../../kiln/packages/kiln-core/src/images.ts)).
On a Mirror that was never set up, the LAN channel is stock's `mirror-<serial>` hotspot, as it was for
the Phase 1 probe ([stock-firmware-probe.md](stock-firmware-probe.md)). Everything that needs it runs
before the write, on an untouched `system`, so the hotspot is still there for them. It goes away only
after the write and the restart, when Glassworks takes over setup with its own hotspot. Undo writes the
restore image, which puts back every block of the stock `services.jar` that the write changed, and with
it `MirrorApService` and the stock hotspot.

## What we tried

1. **Read `systemReady()`'s code_item in the stock jar** (2026-11-25, read)
   ```
   $ dexdump -d "$STOCK_JAR" > orig.dump        # $STOCK_JAR: a copy of stock 1.24.0 services.jar, outside the repo
   Class descriptor  : 'Lcom/android/server/MirrorApService;'
     Virtual methods   -
       name          : 'systemReady'
       type          : '()V'
       access        : 0x0000 ()
       registers     : 5
       ins           : 1
       outs          : 3
       insns size    : 95 16-bit code units
   0f54dc: ... |[0f54dc] com.android.server.MirrorApService.systemReady:()V
   ...
   0f5598: ... |0056: invoke-virtual {v0, v1, v2}, Landroid/content/Context;.registerReceiver:...
   0f55a8: 0e00 |005e: return-void
         catches       : (none)
   ```
   No `try` blocks, one `this` in-register, and the method lives at file offset 0xf54dc in `classes.dex`.
   This is what the patch's checks require: `ins_size` 1, `tries_size` 0, a code_item in range, and a body
   that loads the string `com.mirror.framework.ACTION_START_HOST_APD`.

2. **Who refers to `MirrorApService`** (2026-11-25, verified on the jar)
   ```
   $ awk '/^[0-9a-f]{6}: +\|\[/ {sub(/^.*\|\[[0-9a-f]+\] /,""); cur=$0}
          /Lcom\/android\/server\/MirrorApService[;$]/ && /invoke|new-instance|const-class|sget|sput/ {print cur}' orig.dump \
       | grep -v '^com\.android\.server\.MirrorApService' | sort | uniq -c
      1 com.android.server.SystemServer$2.run:()V
      2 com.android.server.SystemServer.startOtherServices:()V
   ```
   Outside the class and its inner classes, only SystemServer: `create()` from `startOtherServices()` and
   `systemReady()` from `SystemServer$2.run()`.

3. **Run the patcher on the real stock jar** (2026-11-25, verified off-device). A throwaway Vitest file,
   deleted afterwards and never committed, read `$STOCK_JAR`, called `patchStockServicesJar(jar, key)`
   with a made-up adb key (defaults: Device Owner fixes and the hotspot patch on) and wrote the result to
   `/tmp`. To run it again, save this as `frames/lululemon-mirror/installer/test/real-jar.local.test.ts`
   (keep it out of commits), with the stock jar outside the repo:
   ```ts
   import { readFileSync, writeFileSync } from 'node:fs';
   import { test } from 'vitest';
   import { patchStockServicesJar } from '../src/stock';
   import { fakeAdbKey } from './fakes';

   test.skipIf(!process.env.STOCK_JAR)('patch the real stock services.jar', async () => {
     const jar = new Uint8Array(readFileSync(process.env.STOCK_JAR!));
     const r = await patchStockServicesJar(jar, fakeAdbKey(1));
     writeFileSync('/tmp/services.patched.jar', r.patched);
     console.log(JSON.stringify(r, (k, v) => (v instanceof Uint8Array ? undefined : v)));
   });
   ```
   ```
   $ cd frames/lululemon-mirror/installer
   $ STOCK_JAR=/path/outside/the/repo/services.jar npx vitest run test/real-jar.local.test.ts
   ```
   It printed:
   ```
   {"inLen":2411806,"outLen":2411806,"padBytes":3670,
    "startConfirmationCodeOff":"0x2d8b08","patchedCodeUnits":25,
    "deviceOwner":{"hasFeatureMoveResultPc":51,"hasFeatureReg":0,"setBackupServiceActivePc":71},
    "stockAp":{"codeOff":"0xf54dc","insnsSize":95}}
   $ dexdump -c /tmp/services.patched.jar
   Checksum verified
   ```
   Same length in and out, and the patcher found `systemReady()` at the offset dexdump shows.

4. **Decompile the patched jar** (2026-11-25, verified off-device)
   ```
   $ jadx -d out --single-class com.android.server.MirrorApService /tmp/services.patched.jar
   $ grep -n 'void systemReady' -A1 out/sources/com/android/server/MirrorApService.java
   60:    void systemReady() {
   61-    }
   $ jadx -d out-usb --single-class com.android.server.usb.UsbDebuggingManager /tmp/services.patched.jar
       public void startConfirmation(String str, String str2) {
           if ("unable to start <FP>        ".substring(16, 63).equals(str2)) {
               allowUsbDebugging(true, str);
           } else {
               denyUsbDebugging();
           }
       }
   $ jadx -d out-dpms --single-class com.android.server.devicepolicy.DevicePolicyManagerService /tmp/services.patched.jar
   $ grep -n 'this.mHasFeature = ' .../DevicePolicyManagerService.java
   898:        this.mHasFeature = true;
   $ grep -c setBackupServiceActive .../DevicePolicyManagerService.java
   0
   ```
   A diff of jadx's `MirrorApService` before and after the patch, blank lines aside, shows only
   `systemReady()` changed (its body gone, its import of `IntentFilter` with it; jadx also moves the
   now line-less method up the file):
   ```
   $ jadx -d out-orig --single-class com.android.server.MirrorApService "$STOCK_JAR"
   $ diff <(grep -v '^\s*$' out-orig/sources/com/android/server/MirrorApService.java) \
          <(grep -v '^\s*$' out/sources/com/android/server/MirrorApService.java)
   5d4
   < import android.content.IntentFilter;
   57a57,58
   >     void systemReady() {
   >     }
   81,90d81
   <     }
   <     void systemReady() {
   <         this.mWifiManager = (WifiManager) ...
   ...                                   # the rest of the stock body shown in "How we got here"
   ```

5. **Diff the whole `classes.dex`** (2026-11-25, verified off-device). Both dexdump listings, with the
   address and raw code-unit columns and the `string@`/`method@`/`field@`/`type@` indexes stripped (the
   string swap renumbers them), differ in exactly four methods. Run in a scratch directory outside the
   repo; read-only:
   ```
   $ dexdump -d "$STOCK_JAR" > orig.dump
   $ dexdump -d /tmp/services.patched.jar > patched.dump
   $ norm() { tail -n +3 "$1" | sed -E \
       -e 's/^[0-9a-f]{6}: ([0-9a-f]{4} )+ *\|/|/' -e 's/^[0-9a-f]{6}: *\|/|/' \
       -e 's/(string|method|field|type)@[0-9a-f]+//g' -e 's/\[[0-9a-f]{6}\]//'; }
   $ norm orig.dump > orig.norm; norm patched.dump > patched.norm   # tail drops "Processing/Opened <path>"
   # each diff hunk starts at a line of orig.norm: map it to the last method header ("| com.x.Y.m:(...)") above it
   $ diff orig.norm patched.norm | grep -E '^[0-9]' | sed -E 's/^([0-9]+).*/\1/' | while read -r n; do
       awk -v n="$n" '/^\| [A-Za-z]/ {m=$2} NR==n {print m; exit}' orig.norm
     done | sort -u
   com.android.server.MirrorApService.systemReady:()V
   com.android.server.devicepolicy.DevicePolicyManagerService.<init>:(Landroid/content/Context;)V
   com.android.server.devicepolicy.DevicePolicyManagerService.setDeviceOwner:(Ljava/lang/String;Ljava/lang/String;)Z
   com.android.server.usb.UsbDebuggingManager.startConfirmation:(Ljava/lang/String;Ljava/lang/String;)V
   ```

## Result

`patchStockServicesJar` takes a third edit, on by default (`disableStockAp`): it finds
`MirrorApService.systemReady()V` through the dex's method and class tables, checks it is the method read
above (a code_item, `ins_size` 1, no tries, and a body that loads `ACTION_START_HOST_APD`), and rewrites its
95 code units to one `return-void` and 94 `nop`s. It keeps registers, ins and outs, zeroes
`debug_info_off`, and leaves the DEX checksums and the exact-size repack to the existing steps after it.
The result reports `stockAp: { codeOff, insnsSize }`. A `services.jar` without the method, or whose
method has other ins, has tries, or doesn't load `ACTION_START_HOST_APD`, fails the patch with a clear
error instead of being patched on a guess. The body isn't compared instruction by instruction: one that
passes those checks is emptied in full, whatever else it does. Stock 1.24.0 always has the method read
above, and the in-place flow only patches stock. The Node surgical-ADB tool
(`tools/mirror/src/surgical-adb.ts`) passes `disableStockAp: false`, as it does `enableDeviceOwner:
false`: it's a reversible probe that only changes who USB debugging lets in, and Phase 1 and its own
transport rely on the stock hotspot.

| Claim | Evidence |
|---|---|
| The hotspot is `MirrorApService` + `mapd`, outside WifiManager | Symptom above (verified 2026-11-25), jadx (read) |
| Only SystemServer calls `create()` and `systemReady()` | Attempt 2 (verified on the jar) |
| `systemReady()` starts the state machine, receivers and connector thread | jadx, attempt 1 (read) |
| The patched jar keeps its length and passes dexdump's checksum | Attempt 3 (verified off-device) |
| Only four methods change, `systemReady()` is empty | Attempts 4 and 5 (verified off-device) |
| No hotspot after a reboot on a real Mirror | **untested** |

## How to reproduce

Off-device, read-only, nothing on a Mirror:

1. Keep a copy of stock 1.24.0's `/system/framework/services.jar` outside the repo (`$STOCK_JAR`).
2. Run `patchStockServicesJar` on it, for example from a throwaway test file in
   `frames/lululemon-mirror/installer/test/` that writes `res.patched` to `/tmp`, then delete that file.
3. `dexdump -c` the result, decompile and diff `MirrorApService` with jadx as in attempt 4, and run the
   normalized `dexdump -d` comparison exactly as in attempt 5. It should print the same four methods.

The unit tests (`frames/lululemon-mirror/installer/test/stock.test.ts`) build a synthetic
`MirrorApService` with javac and d8 (no vendor bytes) and check the patch by default, with
`disableStockAp: false`, on a jar without the class, and on a `systemReady()` with another body.

On a Mirror, the in-place install writes it, and its undo puts the stock `services.jar` blocks back.

## Open questions

1. **The Mirror run (needed before this ships).** Reinstall Glassworks in place on a stock 1.24.0 Mirror,
   reboot with no upstream network, wait past two minutes, and check: no `wlan0_ap` in `ip addr`, no
   `hostapd` under `mapd`, no `mirror-<serial>` in a Wi-Fi scan, `dumpsys connectivity` with nothing
   tethered, and Glassworks' own setup hotspot opening. Then drop the home network and check again, and
   send `com.mirror.framework.ACTION_START_HOST_APD` from `adb shell am broadcast` to check nothing answers.
2. **Does `mapd` do anything on its own?** Everything above says system_server tells it what to do. If it
   ever starts the AP with no client, the run in 1 shows it, and the next lever is the init side.
3. **Re-running the stages on a patched Mirror.** After this patch, a Mirror that runs the stage again
   (to update the in-place install, for example) has no stock hotspot to receive over. The LAN channel
   then needs the home network, or Bluetooth LE or a file. The patcher already refuses a jar it patched
   before (the `startConfirmation` string is gone), so the flow has to start from the restore image's
   stock blocks either way.
