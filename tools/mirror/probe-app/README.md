# Mirror probe: privileged permissions and silent updates

A tiny Android app that answers one question on the Mirror (#29): when Kiln's image puts our APK in
`/system/priv-app`, does Android 6 give it the privileged permissions, and does it keep them after
the app updates itself? If yes, Glassworks needs no root on the Mirror for silent updates, reboots
and secure settings. Why we expect yes, from AOSP 6.0.1's source:
[privileged-app.md](../../../frames/lululemon-mirror/research/privileged-app.md).

**Status: steps 0–5 passed on a Mirror on 2026-11-16** (the root's run, on #395): privileged, a silent
update, a secure setting and a reboot. Step 6, `allow-adb-key`, is new and untested on the unit.

It has no network and no Home Assistant. It asks for four permissions:

| Permission | Android 6 protection | Granted to |
|---|---|---|
| `INSTALL_PACKAGES` | `signature\|privileged` | a privileged system app, or one signed with the platform key |
| `REBOOT` | `signature\|privileged` | the same |
| `WRITE_SECURE_SETTINGS` | `signature\|privileged\|development` | the same, or any app adb grants it to with `pm grant` |
| `MANAGE_USB` | `signature\|privileged` (AOSP 6.0.1_r81 `core/res/AndroidManifest.xml:1164`; the same on the Mirror, `dumpsys package android`, verified 2026-11-16) | the same as `INSTALL_PACKAGES` |

## Why it is called S1 Kiosk

The Mirror pipeline accepts only the package `sh.edm.s1kiosk`: `patchSystem` in
`frames/lululemon-mirror/installer/src/image.ts` refuses any other, and the first boot looks for
`sh.edm.s1kiosk` and starts `sh.edm.s1kiosk/.MainActivity`. So the probe uses that package id and
that activity name. It is still a different app, signed with a throwaway key: it can't update or be
updated by a real S1 Kiosk build. That's fine on a test Mirror, where the probe is flashed in place
of S1 Kiosk with a fresh userdata.

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
| `allow-adb-key --es key <adb public key line>` | Calls the hidden `IUsbManager.allowUsbDebugging(true, key)` by reflection (`ServiceManager.getService("usb")`, `IUsbManager.Stub.asInterface`). It logs only the key's SHA-256 fingerprint prefix, never the key | Android took the call (it needs `MANAGE_USB`). The call is asynchronous and returns nothing: whether the key was written shows in `/data/misc/adb/adb_keys` (step 6). The probe has no action that clears keys, on purpose: `clearUsbDebuggingKeys` would delete the root's own key too |

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
   npx -y tsx@4.23.15 tools/mirror/flash-node.ts --image ~/Downloads/system.img --apk "$PROBE/probe-v1.apk"
   ```
   No `--ha-url` and no Wi-Fi: the probe uses neither. The first boot's `pm grant` of
   `RECORD_AUDIO`, `CAMERA` and `CHANGE_CONFIGURATION`, `appops` and `dpm set-active-admin` fail or
   do nothing for the probe, and that's expected. Its HOME check should report the probe as the home
   screen (`home set`). Then clear the log: `adb logcat -c`.
1. **Read the status lines.**
   ```sh
   adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
   adb logcat -d -s GwProbe
   adb shell dumpsys package sh.edm.s1kiosk | grep -E 'codePath|versionCode|flags|INSTALL_PACKAGES|REBOOT|WRITE_SECURE|MANAGE_USB'
   ```
   Expect version 1, `system yes`, `privileged yes`, `code-path /system/priv-app/S1Kiosk/S1Kiosk.apk`,
   and all four permissions granted.
2. **Update to version 2 with no tap.**
   ```sh
   adb push "$PROBE/probe-v2.apk" /data/local/tmp/probe-v2.apk
   adb shell chmod 644 /data/local/tmp/probe-v2.apk
   adb shell am start -n sh.edm.s1kiosk/.ProbeAction --es action update --es apk /data/local/tmp/probe-v2.apk
   sleep 20; adb logcat -d -s GwProbe | grep -E 'STEP update|RESULT update'
   ```
   Expect `RESULT update OK silent update, no tap`. If it says it can't read the file (SELinux
   refused `/data/local/tmp`), push it to `/sdcard/Android/data/sh.edm.s1kiosk/files/probe-v2.apk`
   instead and pass that path.
3. **Check the status again**, with the commands of step 1. Expect version 2,
   `updated-system-app yes`, `privileged yes`, `code-path /data/app/sh.edm.s1kiosk-1/base.apk` (the
   number may differ), and all four permissions still granted.
4. **Write a secure setting.**
   ```sh
   adb shell am start -n sh.edm.s1kiosk/.ProbeAction --es action settings
   adb logcat -d -s GwProbe | grep 'RESULT settings'
   ```
   Expect `RESULT settings OK global auto_time=<value> written back`.
5. **Reboot.**
   ```sh
   adb shell am start -n sh.edm.s1kiosk/.ProbeAction --es action reboot
   adb logcat -d -s GwProbe | grep -E 'STEP reboot|RESULT reboot'   # may cut off as the Mirror goes down
   adb wait-for-device; until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
   adb shell dumpsys activity activities | grep mFocusedActivity
   adb logcat -d -s GwProbe
   ```
   Expect the Mirror to restart, `mFocusedActivity` to be `sh.edm.s1kiosk/.MainActivity`, and the
   status lines to show version 2, still privileged, all four granted.

6. **Allow a new computer's adb key** (`MANAGE_USB`, `allowUsbDebugging`). First make a throwaway adb
   key pair. It goes in the git-ignored `out/adbkey-test/` (or a folder you name), and the script
   prints only its fingerprints:
   ```sh
   tools/mirror/probe-app/adbkey.py new            # writes out/adbkey-test/adbkey and adbkey.pub
   KEY=tools/mirror/probe-app/out/adbkey-test/adbkey.pub
   adb shell cat /data/misc/adb/adb_keys | tools/mirror/probe-app/adbkey.py fingerprints -   # before: your own key
   adb logcat -c
   adb shell am start -n sh.edm.s1kiosk/.ProbeAction --es action allow-adb-key --es key "'$(cat "$KEY")'"
   sleep 3; adb logcat -d -s GwProbe UsbDebuggingManager | grep -E 'RESULT allow-adb-key|Fingerprints'
   adb shell cat /data/misc/adb/adb_keys | wc -l
   adb shell cat /data/misc/adb/adb_keys | tools/mirror/probe-app/adbkey.py fingerprints -   # after
   tools/mirror/probe-app/adbkey.py fingerprint "$KEY"
   ```
   (The single quotes inside the double ones keep the key line, which has a space, as one argument
   through `adb shell`.) adb's shell can read `adb_keys`: Android 6 makes it `system:shell` 0640.

   **What to expect** (read from Android 6.0.1_r81, untested on the unit):
   - `perm.MANAGE_USB granted` in step 1, and `RESULT allow-adb-key OK sent sha256:…`.
   - **No new line in `adb_keys`**, and logcat's `UsbDebuggingManager: Fingerprints do not match. Got
     <MD5 of the test key>, expected <null or the last computer asked about>`. Android writes a key
     only when its fingerprint matches the last computer adb asked about
     (`UsbDebuggingManager.java:236`–`248`). It keeps that one even after a deny or an unplug. Here no
     computer with the test key has asked, so the call approves nothing. `adbkey.py fingerprint` prints the test key's MD5 to match logcat's `Got`.
   - **With a computer asking** (optional, needs a second computer):
     1. On the second computer, move its own `~/.android/adbkey*` aside and copy the test pair there,
        so its adb offers only the test key. Then `unset ADB_VENDOR_KEYS` and run `adb kill-server`
        there (that computer's own server, not the shared one), so the server loads the test key
        rather than the key it had in memory.
     2. Plug it into the Mirror instead of yours. `adb devices` there shows `unauthorized`, and the
        Mirror shows "Allow USB debugging?" with no way to tap it. Android now remembers that key's
        fingerprint as the one being asked about (`UsbDebuggingManager.java:279`), until another
        computer asks.
     3. Unplug it, plug yours back in, run `adb logcat -c` (the first try's "do not match" line would
        otherwise still be there), and run the `allow-adb-key` line again.
     4. Expect a new line in `adb_keys` with the test key's fingerprint, and no "do not match" line.
     5. Plug the second computer in again: its `adb devices` should show the Mirror as `device`.

     Don't stop the shared adb server on your own computer to do this: the S1 and the tablet are on it.

   **Cleanup.** The probe has no action that clears keys, on purpose. Android 6's
   `clearUsbDebuggingKeys` deletes the whole `adb_keys` file, your key with it, and the Mirror has no
   touchscreen to allow a computer again. adb's shell can only read the file, not edit it. So a test
   key that was written stays until the next flash, which writes a fresh `userdata` with only the keys
   Kiln puts there. The key pair is a throwaway: delete `out/adbkey-test/` when the test is done.

To put the APK back, flash again with the APK the unit had before (or the way back). That doesn't bring
back the unit's old `userdata`: every flash writes a fresh one, and the bootloader can't read the old
one back first.

## What each outcome means for Glassworks

| Outcome | Meaning |
|---|---|
| Step 1: `privileged yes`, `INSTALL_PACKAGES` and `REBOOT` granted | Android 6 on this image grants privileged permissions to `/system/priv-app` apps whatever their key, as read. S1 Kiosk in Kiln's image gets them too. `WRITE_SECURE_SETTINGS` alone proves little: the first boot also grants it with `pm grant` |
| Step 1: `privileged no`, or `INSTALL_PACKAGES` denied | The image doesn't do what AOSP 6 does (a vendor change, or the APK isn't where Kiln put it). Updates would need a tap, or root, or the platform key ([mirror-root.md](../../../frames/lululemon-mirror/research/mirror-root.md)) |
| Step 2: `RESULT update OK` | Glassworks can update itself on the Mirror with no tap and no root, from Home Assistant's update entity |
| Step 2: `FAIL needs a tap` | `INSTALL_PACKAGES` didn't count: updates need someone at the Mirror with a mouse, or another route |
| Step 2: `FAIL STATUS_FAILURE_CONFLICT: INSTALL_FAILED_UPDATE_INCOMPATIBLE … different signature` | The two APKs aren't signed by the same key: rebuild both from one `PROBE_OUT`. Android 6 maps a signature mismatch to `STATUS_FAILURE_CONFLICT` (`PackageManager.installStatusToPublicStatus`); `STATUS_FAILURE_INCOMPATIBLE` is something else (SDK, ABI, a missing feature) |
| Step 3: still privileged, permissions kept | An update keeps the privileged permissions, as long as the APK in `/system` asked for them. So the APK in Kiln's image must already ask for every privileged permission a later version needs: an update signed with our key can't add one (only a platform-signed one could) |
| Step 3: privileged lost after the update | Each update would lose the permissions: Kiln would have to flash every new version into `system` |
| Step 4: `RESULT settings OK` | Glassworks can write secure settings (screen timeout, stay awake, location) without adb's help |
| Step 5: restarts and comes back as HOME | Glassworks can restart the Mirror from Home Assistant, and boots straight into Glassworks, the updated version included |
| Step 6: `perm.MANAGE_USB granted`, `RESULT allow-adb-key OK`, no new key and "Fingerprints do not match" | As read: Glassworks can answer the last computer the Mirror asked about, but can't add a key no computer asked for. A Manage page "Allow a new computer over USB" works only after that computer has asked. The page needs that computer's full public key, not just its fingerprint: Kiln can send the key (it holds the browser's key), and the page shows the fingerprint so the person can confirm it |
| Step 6 with a computer asking: the key written, the computer connects | The Manage page can approve the computer that last asked, with no touchscreen. This is the case that makes the feature possible |
| Step 6: the test key written with no computer asking | Android on this image doesn't check the fingerprint (it differs from AOSP). Any app holding `MANAGE_USB` could then let any key in, so Glassworks would need to guard who can ask it |
| Step 6: `FAIL java.lang.SecurityException … MANAGE_USB` | The privileged grant didn't cover `MANAGE_USB` (it was missing from the flashed copy, or the image differs). No Manage-page approval without root |
| Step 6: `FAIL java.lang.ClassNotFoundException` or `NoSuchMethodException` | This image's framework names it differently: look at its `IUsbManager` before going further |

## Files

| File | What |
|---|---|
| `build.sh` | Both builds, the checks, the SHA-256s |
| `src/main/AndroidManifest.xml` | The permissions, HOME, the adb-only action activity |
| `src/main/java/sh/edm/s1kiosk/MainActivity.java` | The status screen and its `FACT` lines |
| `src/main/java/sh/edm/s1kiosk/ProbeAction.java` | `update`, `settings`, `reboot`, `allow-adb-key` |
| `adbkey.py` | A throwaway adb key pair in adb's formats (git-ignored `out/adbkey-test/`), and fingerprints of keys, never the keys |
| `src/main/java/sh/edm/s1kiosk/InstallResult.java` | PackageInstaller's answer, as one `RESULT update` line |
| `src/main/java/sh/edm/s1kiosk/Probe.java` | The log lines: status codes to OK/FAIL, protection levels, the adb key check and its fingerprint |
| `src/test/java/sh/edm/s1kiosk/ProbeTest.java` | JVM tests for `Probe` |
