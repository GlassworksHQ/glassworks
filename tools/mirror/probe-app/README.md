# Mirror probe: privileged permissions and silent updates

A tiny Android app that answers one question on the Mirror (#29): when Kiln's image puts our APK in
`/system/priv-app`, does Android 6 give it the privileged permissions, and does it keep them after
the app updates itself? If yes, Glassworks needs no root on the Mirror for silent updates, reboots
and secure settings. Why we expect yes, from AOSP 6.0.1's source:
[privileged-app.md](../../../frames/lululemon-mirror/research/privileged-app.md).

**Status: steps 0–5 passed on a Mirror on 2026-11-16** (the root's run, on #395): privileged, a silent
update, a secure setting and a reboot. Step 6, `allow-adb-key`, ran on 2026-11-16 too: the probe
approved a computer that was asking, which then connected, but the approval didn't last to its next
connection ([privileged-app.md](../../../frames/lululemon-mirror/research/privileged-app.md#the-run-on-the-unit-2026-11-16)).

It has no network and no Home Assistant. It asks for four permissions:

| Permission | Android 6 protection | Granted to |
|---|---|---|
| `INSTALL_PACKAGES` | `signature\|privileged` | a privileged system app, or one signed with the platform key |
| `REBOOT` | `signature\|privileged` | the same |
| `WRITE_SECURE_SETTINGS` | `signature\|privileged\|development` | the same, or any app adb grants it to with `pm grant` |
| `MANAGE_USB` | `signature\|privileged` (AOSP 6.0.1_r81 `core/res/AndroidManifest.xml:1164`; the same on the Mirror, `dumpsys package android`, verified 2026-11-16) | the same as `INSTALL_PACKAGES` |

## Its package and folder

The Mirror pipeline normally installs Glassworks' legacy build (`dev.glassworks.generic_android.legacy`).
With `--probe` it takes the probe, `dev.glassworks.mirror.probe`, instead. Until #527 the probe borrowed
the S1 app's id, `dev.glassworks.mirror.probe`, which the pipeline took then. The probe is a different app, signed with a
throwaway key, so it never updates or is updated by a Glassworks build. Its folder in the image stays
`/priv-app/S1Kiosk`, the one the first runs were verified with: the pipeline clears that folder and
Glassworks' by name, so one image holds one app.

## What it shows

`MainActivity` answers MAIN with HOME and DEFAULT, and LAUNCHER, so the Mirror boots into it. It
shows these lines in large text on a black screen, and logs each one to logcat with the tag
`GwProbe`:

```
FACT version 1 (code 1)
FACT system yes
FACT updated-system-app no
FACT privileged yes
FACT code-path /system/priv-app/S1Kiosk/S1Kiosk.apk
FACT perm.INSTALL_PACKAGES granted signature|privileged
FACT perm.REBOOT granted signature|privileged
FACT perm.WRITE_SECURE_SETTINGS granted signature|privileged|development
FACT perm.MANAGE_USB granted signature|privileged
```

(That's the outcome we expect, not one we have seen.) `privileged` comes from the hidden
`ApplicationInfo.privateFlags` and `PRIVATE_FLAG_PRIVILEGED`, read by reflection; it says `unknown`
if either can't be read. `MainActivity` reads no intent extras, so the first boot's
`--es url …` does nothing.

## The actions

`ProbeAction` runs one action and logs one `RESULT <action> <OK|FAIL> <detail>` line. Its manifest
entry requires `WRITE_SECURE_SETTINGS` of the caller, as the generic app's `MainActivity` does
(#364): adb's shell holds it, and a normal app can't get it. So only adb starts the actions.

| Action | What it does | OK means |
|---|---|---|
| `update --es apk <path>` | Installs the APK at `<path>` through `PackageInstaller`: a session, the write, a commit whose result comes back to `InstallResult`. It logs `STEP update committing session …` first | Android installed it with no confirmation. `FAIL needs a tap` is `STATUS_PENDING_USER_ACTION`: without `INSTALL_PACKAGES`, Android 6 asks the person first. The probe then drops the session, because the Mirror has no touchscreen |
| `settings` | Reads `Settings.Global` `auto_time` and writes the same value back | The write went through. A `SecurityException` is a FAIL |
| `reboot` | Logs `STEP reboot calling PowerManager.reboot(null)`, then calls it | The Mirror restarts. If the call returns, it logs `RESULT reboot FAIL` (a refusal throws: `FAIL java.lang.SecurityException …`) |
| `allow-adb-key --es key <adb public key line>` | Calls the hidden `IUsbManager.allowUsbDebugging(true, key)` by reflection (`ServiceManager.getService("usb")`, `IUsbManager.Stub.asInterface`). It logs only the key's SHA-256 fingerprint prefix, never the key | Android took the call (it needs `MANAGE_USB`). The call is asynchronous and returns nothing: whether it worked shows in the asking computer's `adb devices` and in `dumpsys usb` (step 6). The probe has no action that clears keys, on purpose: `clearUsbDebuggingKeys` would delete the root's own key too |

After a successful update Android stops the old version to replace it, so the `RESULT update` line
comes from version 2.

## Build

```sh
tools/mirror/probe-app/build.sh
PROBE_OUT=/some/folder tools/mirror/probe-app/build.sh   # put the APKs and the key somewhere else
```

It needs the Android SDK (`$ANDROID_HOME`, default `~/Library/Android/sdk`, with platform 36 and
build-tools) and a JDK 17 or newer. It runs the unit tests (`ProbeTest`), builds version 1
(`versionCode` 1, `versionName` "1") and version 2 from the same code, signs both with the same
key, checks each manifest with `aapt2` (package, versions, minSdk and targetSdk 23, the four
permissions, HOME, the action's permission), and prints both SHA-256s. Output: `probe-v1.apk` and
`probe-v2.apk` in `$PROBE_OUT` (default `tools/mirror/probe-app/out/`, git-ignored).

**The key.** The first run makes a throwaway keystore, `$PROBE_OUT/probe.keystore` (password
`probe-only`), and later runs reuse it. Version 2 updates a Mirror only if it was built with the same
keystore as the version 1 flashed on it, so keep the folder until the test is done. The key signs
nothing else. Never commit it or the APKs (the repo's `.gitignore` already drops `*.apk` and
`*.keystore`).

The build is its own Gradle project, apart from the repo's (the root `settings.gradle.kts` doesn't
include it). `build.sh` runs it with the repo's Gradle wrapper (`gradlew -p tools/mirror/probe-app`),
in plain Java with AGP's built-in Kotlin off, so the APK is about 17 KB.

## The test on the Mirror

For the root, on a test Mirror. Every `adb` command takes `-s "$(cat /tmp/mirror-serial)"`; it is
left out below. `PROBE` is the output folder of `build.sh`.

0. **Flash** with the probe as the APK, with the harness ([README](../README.md)):
   ```sh
   npx -y tsx@4.23.15 tools/mirror/flash-node.ts --image ~/Downloads/system.img --apk "$PROBE/probe-v1.apk" --probe
   ```
   No `--ha-url` and no Wi-Fi: the probe uses neither. The flashed image lets in only the first
   `--adb-key` (default `~/.android/adbkey.pub`) without asking, and refuses the published Glass Panes
   project key. If adb's server here runs with another key, follow "A throwaway key" in the
   [harness README](../README.md#a-throwaway-key). The first boot's `pm grant` of
   `RECORD_AUDIO`, `CAMERA` and `CHANGE_CONFIGURATION`, `appops` and `dpm set-active-admin` fail or
   do nothing for the probe, and that's expected. Its HOME check should report the probe as the home
   screen (`home set`). Then clear the log: `adb logcat -c`.
1. **Read the status lines.**
   ```sh
   adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
   adb logcat -d -s GwProbe
   adb shell dumpsys package dev.glassworks.mirror.probe | grep -E 'codePath|versionCode|flags|INSTALL_PACKAGES|REBOOT|WRITE_SECURE|MANAGE_USB'
   ```
   Expect version 1, `system yes`, `privileged yes`, `code-path /system/priv-app/S1Kiosk/S1Kiosk.apk`,
   and all four permissions granted.
2. **Update to version 2 with no tap.**
   ```sh
   adb push "$PROBE/probe-v2.apk" /data/local/tmp/probe-v2.apk
   adb shell chmod 644 /data/local/tmp/probe-v2.apk
   adb shell am start -n dev.glassworks.mirror.probe/.ProbeAction --es action update --es apk /data/local/tmp/probe-v2.apk
   sleep 20; adb logcat -d -s GwProbe | grep -E 'STEP update|RESULT update'
   ```
   Expect `RESULT update OK silent update, no tap`. If it says it can't read the file (SELinux
   refused `/data/local/tmp`), push it to `/sdcard/Android/data/dev.glassworks.mirror.probe/files/probe-v2.apk`
   instead and pass that path.
3. **Check the status again**, with the commands of step 1. Expect version 2,
   `updated-system-app yes`, `privileged yes`, `code-path /data/app/dev.glassworks.mirror.probe-1/base.apk` (the
   number may differ), and all four permissions still granted.
4. **Write a secure setting.**
   ```sh
   adb shell am start -n dev.glassworks.mirror.probe/.ProbeAction --es action settings
   adb logcat -d -s GwProbe | grep 'RESULT settings'
   ```
   Expect `RESULT settings OK global auto_time=<value> written back`.
5. **Reboot.**
   ```sh
   adb shell am start -n dev.glassworks.mirror.probe/.ProbeAction --es action reboot
   adb logcat -d -s GwProbe | grep -E 'STEP reboot|RESULT reboot'   # may cut off as the Mirror goes down
   adb wait-for-device; until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
   adb shell dumpsys activity activities | grep mFocusedActivity
   adb logcat -d -s GwProbe
   ```
   Expect the Mirror to restart, `mFocusedActivity` to be `dev.glassworks.mirror.probe/.MainActivity`, and the
   status lines to show version 2, still privileged, all four granted.

6. **Allow a new computer's adb key** (`MANAGE_USB`, `allowUsbDebugging`). This is the method that
   worked on 2026-11-16. Two things on this image shape it:
   - Android answers only **the last computer it asked about**, and every connection sets that,
     including your own computer's when it reconnects and gets in by itself. So the approval must
     fire while the test key's computer is the one connected. It is queued on the Mirror first.
   - adb's shell can't read `/data/misc/adb` here. `adb shell dumpsys usb` lists the user keys
     (that file) and the system keys (the boot image's `/adb_keys`), and `adbkey.py fingerprints -`
     reads that output.

   One computer does it, with two adb servers in turn. This stops adb for every other device on this
   computer (the S1, the tablet) until step 6.6: check no one is using them first.

   1. Make a throwaway key pair, alone in a folder that stands in for a home folder. The script prints
      only fingerprints:
      ```sh
      T=$(mktemp -d); mkdir "$T/.android"
      tools/mirror/probe-app/adbkey.py new "$T/.android"
      KEY="$T/.android/adbkey.pub"
      adb shell dumpsys usb | tools/mirror/probe-app/adbkey.py fingerprints -   # before
      ```
   2. Queue the approval on the Mirror, 20 s from now, so it runs in the background after you switch
      servers:
      ```sh
      adb logcat -c
      adb shell "nohup sh -c \"sleep 20; am start -n dev.glassworks.mirror.probe/.ProbeAction --es action allow-adb-key --es key '$(cat "$KEY")'\" > /dev/null 2>&1 &"
      ```
   3. At once, switch to a server that has only the test key, for the Mirror only:
      ```sh
      adb kill-server
      HOME="$T" adb --one-device "$(cat /tmp/mirror-serial)" start-server   # --one-device first: after start-server, adb ignores it
      adb devices   # unauthorized: the Mirror asks about the test key now
      ```
   4. Within the 20 s, nothing to do. Then:
      ```sh
      sleep 25; adb devices   # device: the queued approval let the test key in
      adb shell dumpsys usb | tools/mirror/probe-app/adbkey.py fingerprints -   # the test key now in the user keys
      adb logcat -d -s GwProbe UsbDebuggingManager | grep -E 'RESULT allow-adb-key|Fingerprints'
      ```
      It went from `unauthorized` to `device` within about a second of the call, with a shell.
   5. Unplug and plug the Mirror back in, or `adb kill-server; HOME="$T" adb --one-device … start-server`
      again: `adb devices` shows `unauthorized`. **The approval doesn't last on this image.** The
      key was written to `adb_keys`, but this image's adbd doesn't read that file
      ([image-patch.md](../../../frames/lululemon-mirror/research/image-patch.md#who-the-image-lets-in-over-adb)).
   6. Put the normal server back:
      ```sh
      adb kill-server; adb start-server; adb devices
      ```

   **What it means.** `allowUsbDebugging` works for the one connection that is asking, and nothing
   more here. `adbkey.py fingerprint "$KEY"` prints the test key's MD5, to match the `Got …` in a
   "Fingerprints do not match" line: that line means the approval fired while another computer was
   the last one asked about (step 2's timing missed).

   **Cleanup.** The probe has no action that clears keys, on purpose. Android 6's
   `clearUsbDebuggingKeys` deletes the whole `adb_keys` file, and the Mirror has no touchscreen to
   allow a computer again. A test key written there stays until the next flash, which writes a fresh
   `userdata`. Delete `$T` when the test is done.

To put the APK back, flash again with the APK the unit had before (or the way back). That doesn't bring
back the unit's old `userdata`: every flash writes a fresh one, and the bootloader can't read the old
one back first.

## What each outcome means for Glassworks

| Outcome | Meaning |
|---|---|
| Step 1: `privileged yes`, `INSTALL_PACKAGES` and `REBOOT` granted | Android 6 on this image grants privileged permissions to `/system/priv-app` apps whatever their key, as read. Glassworks in Kiln's image gets them too. `WRITE_SECURE_SETTINGS` alone proves little: the first boot also grants it with `pm grant` |
| Step 1: `privileged no`, or `INSTALL_PACKAGES` denied | The image doesn't do what AOSP 6 does (a vendor change, or the APK isn't where Kiln put it). Updates would need a tap, or root, or the platform key ([mirror-root.md](../../../frames/lululemon-mirror/research/mirror-root.md)) |
| Step 2: `RESULT update OK` | Glassworks can update itself on the Mirror with no tap and no root, from Home Assistant's update entity |
| Step 2: `FAIL needs a tap` | `INSTALL_PACKAGES` didn't count: updates need someone at the Mirror with a mouse, or another route |
| Step 2: `FAIL STATUS_FAILURE_CONFLICT: INSTALL_FAILED_UPDATE_INCOMPATIBLE … different signature` | The two APKs aren't signed by the same key: rebuild both from one `PROBE_OUT`. Android 6 maps a signature mismatch to `STATUS_FAILURE_CONFLICT` (`PackageManager.installStatusToPublicStatus`); `STATUS_FAILURE_INCOMPATIBLE` is something else (SDK, ABI, a missing feature) |
| Step 3: still privileged, permissions kept | An update keeps the privileged permissions, as long as the APK in `/system` asked for them. So the APK in Kiln's image must already ask for every privileged permission a later version needs: an update signed with our key can't add one (only a platform-signed one could) |
| Step 3: privileged lost after the update | Each update would lose the permissions: Kiln would have to flash every new version into `system` |
| Step 4: `RESULT settings OK` | Glassworks can write secure settings (screen timeout, stay awake, location) without adb's help |
| Step 5: restarts and comes back as HOME | Glassworks can restart the Mirror from Home Assistant, and boots straight into Glassworks, the updated version included |
| Step 6: `perm.MANAGE_USB granted`, `RESULT allow-adb-key OK`, "Fingerprints do not match" | Glassworks can answer only the last computer the Mirror asked about, and can't add a key no computer asked for. The call fired while another computer was the last one asked |
| Step 6 with the test key's computer asking: it connects (seen 2026-11-16) | Glassworks can let in the computer that is asking right now, with no touchscreen. On this image that lasts one connection: adbd ignores `adb_keys`, so a lasting "Allow a new computer" needs the computer's key where this image reads it (services.jar, as Kiln's flash does) |
| Step 6: the test key written with no computer asking | Android on this image doesn't check the fingerprint (it differs from AOSP). Any app holding `MANAGE_USB` could then let any key in, so Glassworks would need to guard who can ask it |
| Step 6: `FAIL java.lang.SecurityException … MANAGE_USB` | The privileged grant didn't cover `MANAGE_USB` (it was missing from the flashed copy, or the image differs). No Manage-page approval without root |
| Step 6: `FAIL java.lang.ClassNotFoundException` or `NoSuchMethodException` | This image's framework names it differently: look at its `IUsbManager` before going further |

## Files

| File | What |
|---|---|
| `build.sh` | Both builds, the checks, the SHA-256s |
| `src/main/AndroidManifest.xml` | The permissions, HOME, the adb-only action activity |
| `src/main/java/dev/glassworks/mirror/probe/MainActivity.java` | The status screen and its `FACT` lines |
| `src/main/java/dev/glassworks/mirror/probe/ProbeAction.java` | `update`, `settings`, `reboot`, `allow-adb-key` |
| `adbkey.py` | A throwaway adb key pair in adb's formats (git-ignored `out/adbkey-test/`), and fingerprints of keys, never the keys |
| `src/main/java/dev/glassworks/mirror/probe/InstallResult.java` | PackageInstaller's answer, as one `RESULT update` line |
| `src/main/java/dev/glassworks/mirror/probe/Probe.java` | The log lines: status codes to OK/FAIL, protection levels, the adb key check and its fingerprint |
| `src/test/java/dev/glassworks/mirror/probe/ProbeTest.java` | JVM tests for `Probe` |
