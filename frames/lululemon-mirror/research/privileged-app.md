---
topic: access
evidence: verified
---

# Privileged app: does a /system/priv-app app get Android 6's privileged permissions, and keep them after it updates?

**Status (2026-11-16): verified on the unit.** AOSP 6.0.1's package manager grants
`signature|privileged` permissions to any app scanned from `/system/priv-app`, whatever its signing
key, and an update of that app keeps them while it is signed with the same key. The probe app
([tools/mirror/probe-app](../../../tools/mirror/probe-app/README.md)) showed it on the Mirror on
2026-11-16 (the root's run, on #395): privileged, `INSTALL_PACKAGES`, `REBOOT` and
`WRITE_SECURE_SETTINGS` granted, a silent update with all three kept, a secure setting written, and a
reboot back into the app. Such an app can approve the computer that is asking, for that one
connection: [below](#can-a-privileged-app-approve-an-adb-key) (verified on the unit 2026-11-16).

Device: lululemon Mirror (MSM8916), community image Glass Panes `mirror-android-1` (Android 6.0.1,
`MMB29M`, `userdebug`, `test-keys`). Source: AOSP tag `android-6.0.1_r81`
(`platform/frameworks/base` and `platform/external/sepolicy` on android.googlesource.com, read
2026-11-15). The image is built from CAF's tree for the DragonBoard; that its package manager matches
AOSP here is an assumption the probe tests.

## Question

Kiln's Mirror image put S1 Kiosk in `/system/priv-app/S1Kiosk` ([image-patch.md](image-patch.md)). Since
2026-11-16 it puts the legacy build of Glassworks in `/system/priv-app/Glassworks`, and the probe still
goes to `S1Kiosk` (the harness's `--probe`).
If that makes it privileged, Glassworks gets three things on the Mirror without root:

- `INSTALL_PACKAGES`: its own updates install with no confirmation;
- `REBOOT`: it can restart the Mirror;
- `WRITE_SECURE_SETTINGS`: it can change secure and global settings itself.

Does it, and do the permissions survive an update, which installs to `/data/app`?

## What we read

All **read** from source (2026-11-15). Line numbers are in the files at `android-6.0.1_r81`.

1. **The three permissions' protection levels** (`core/res/AndroidManifest.xml`):

   | Permission | Lines | `protectionLevel` |
   |---|---|---|
   | `WRITE_SECURE_SETTINGS` | 1762–1763 | `signature\|privileged\|development` |
   | `INSTALL_PACKAGES` | 2098–2099 | `signature\|privileged` |
   | `REBOOT` | 2236–2237 | `signature\|privileged` |

2. **`/system/priv-app` makes an app privileged** (`services/core/java/com/android/server/pm/PackageManagerService.java`).
   At boot it scans the folder with the privileged flag, lines 2077–2081:
   ```java
   // Collected privileged system packages.
   final File privilegedAppDir = new File(Environment.getRootDirectory(), "priv-app");
   scanDirLI(privilegedAppDir, PackageParser.PARSE_IS_SYSTEM
           | PackageParser.PARSE_IS_SYSTEM_DIR
           | PackageParser.PARSE_IS_PRIVILEGED, scanFlags, 0);
   ```
   and `scanPackageDirtyLI` turns that into the app's flag, lines 6499–6501:
   ```java
   if ((parseFlags&PackageParser.PARSE_IS_PRIVILEGED) != 0) {
       pkg.applicationInfo.privateFlags |= ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
   }
   ```
   No signature check is involved. There is no privileged-permission whitelist on Android 6 (that
   came with Android 8's `privapp-permissions`).

3. **Signature permissions go through `grantSignaturePermission`.** `grantPermissionsLPw`
   (line 8339) sends every `signature` permission there, lines 8446–8452 ("For all apps signature
   permissions are install time ones"). `grantSignaturePermission`, lines 8623–8668:
   ```java
   allowed = (compareSignatures(
           bp.packageSetting.signatures.mSignatures, pkg.mSignatures)
                   == PackageManager.SIGNATURE_MATCH)
           || (compareSignatures(mPlatformPackage.mSignatures, pkg.mSignatures)
                   == PackageManager.SIGNATURE_MATCH);
   if (!allowed && (bp.protectionLevel
           & PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0) {
       if (isSystemApp(pkg)) {
           // For updated system applications, a system permission
           // is granted only if it had been defined by the original application.
           if (pkg.isUpdatedSystemApp()) {
               final PackageSetting sysPs = mSettings
                       .getDisabledSystemPkgLPr(pkg.packageName);
               if (sysPs.getPermissionsState().hasInstallPermission(perm)) {
                   …
                   if (sysPs.isPrivileged()) {
                       allowed = true;
                   }
               } else {
                   …
                   if (sysPs.pkg != null && sysPs.isPrivileged()) {
                       for (int j=0;
                               j<sysPs.pkg.requestedPermissions.size(); j++) {
                           if (perm.equals(
                                   sysPs.pkg.requestedPermissions.get(j))) {
                               allowed = true;
   …
           } else {
               allowed = isPrivilegedApp(pkg);
           }
   ```
   with `isPrivilegedApp`, lines 12878–12880:
   ```java
   private static boolean isPrivilegedApp(PackageParser.Package pkg) {
       return (pkg.applicationInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0;
   }
   ```
   So a system app from `/system/priv-app`, signed with any key, gets `INSTALL_PACKAGES`, `REBOOT`
   and `WRITE_SECURE_SETTINGS`. The first branch (platform key) is the other way in: the image is
   `test-keys`, so an app signed with AOSP's public platform test key gets them too, as uid
   `system` or not ([mirror-root.md](mirror-root.md)).

4. **An update keeps the app a privileged system app.** An update installs over a system app
   through `replacePackageLI`, which first needs the same signature, lines 12103–12108
   (`"New package has a different signature"`, `INSTALL_FAILED_UPDATE_INCOMPATIBLE`), then sends a
   system app down the system path, lines 12119–12121:
   ```java
   boolean sysPkg = (isSystemApp(oldPackage));
   if (sysPkg) {
       replaceSystemPackageLI(oldPackage, pkg, parseFlags, scanFlags,
   ```
   `replaceSystemPackageLI` scans the new APK as system, and as privileged if the old one was,
   lines 12230–12234:
   ```java
   parseFlags |= PackageParser.PARSE_IS_SYSTEM;
   if ((deletedPackage.applicationInfo.privateFlags&ApplicationInfo.PRIVATE_FLAG_PRIVILEGED)
           != 0) {
       parseFlags |= PackageParser.PARSE_IS_PRIVILEGED;
   }
   ```
   and keeps the `/system` copy as the disabled system package, line 12263
   (`mSettings.disableSystemPackageLPw(packageName)`). That copy is the `sysPs` that
   `grantSignaturePermission` checks for an updated system app (read 3). Its `isPrivileged()` is the
   same flag (`PackageSetting.java`, lines 66–68).

5. **After a reboot the update stays privileged.** The boot scan finds the disabled `/system` copy
   and sets its flag from where it lies, lines 5779–5786 ("If new package is not located in
   "/system/priv-app" (e.g. due to an OTA), it needs to drop FLAG_PRIVILEGED"). Then the `/data/app`
   copy is scanned with the privileged flag again, lines 5849–5853:
   ```java
   // An updated privileged app will not have the PARSE_IS_PRIVILEGED
   // flag set initially
   if ((updatedPkg.pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0) {
       parseFlags |= PackageParser.PARSE_IS_PRIVILEGED;
   }
   ```
   If the `/system` copy's `versionCode` is higher than the update's (a re-flashed image with a
   newer app), the system copy wins and the update is dropped (around line 5793).

6. **Without `INSTALL_PACKAGES`, a commit waits for a tap**
   (`services/core/java/com/android/server/pm/PackageInstallerSession.java`). Any app may open a
   session (`PackageInstallerService.createSessionInternal`, line 573, checks only that the caller
   owns its installer package name). The session decides at creation, lines 226–233:
   ```java
   if ((mPm.checkUidPermission(android.Manifest.permission.INSTALL_PACKAGES, installerUid)
           == PackageManager.PERMISSION_GRANTED)
           || (installerUid == Process.ROOT_UID)
           || mIsInstallerDeviceOwner) {
       mPermissionsAccepted = true;
   ```
   and at commit, lines 488–495, a session without it sends `ACTION_CONFIRM_PERMISSIONS` back
   through `onUserActionRequired`, which the app sees as `STATUS_PENDING_USER_ACTION` (-1,
   `PackageInstaller.java` line 177). With it, the commit goes straight to the install.

7. **The probe can read the update APK from `/data/local/tmp`.** A priv-app not signed with the
   platform key runs as `untrusted_app` (`seapp_contexts` lines 49–50; `mac_permissions.xml` gives
   `platform` only to `@PLATFORM`), and `untrusted_app.te` lines 47–48 allow
   `shell_data_file` reads ("Used by Finsky / Android "Verify Apps" functionality"). A vendor policy
   could differ; the probe's README gives `/sdcard/Android/data/<package>/files` as the fallback.

8. **An `activity-alias` doesn't take its target's permission** (`core/java/android/content/pm/PackageParser.java`,
   `parseActivityAlias`, lines 3357–3398): it copies the target's flags, theme and launch mode, but
   `permission` only from its own `android:permission`. So the generic app's pattern (#364), a
   permission-guarded `MainActivity` and an unguarded HOME alias, works on Android 6 as intended.
   The probe uses two plain activities instead: `MainActivity` for HOME, `ProbeAction` for adb.

## Result

| Claim | Evidence | How sure |
|---|---|---|
| An app in `/system/priv-app` gets `INSTALL_PACKAGES`, `REBOOT`, `WRITE_SECURE_SETTINGS`, whatever its key | Reads 1–3; the probe on the unit (2026-11-16): `privileged yes`, `INSTALL_PACKAGES` and `REBOOT` granted from `/system/priv-app` | **Verified** (2026-11-16) |
| An update signed with the same key keeps them, and stays privileged across reboots | Reads 4–5; the probe on the unit (2026-11-16): after the silent update, an updated system app in `/data/app`, still `PRIVILEGED`, all three kept, and again after a reboot | **Verified** (2026-11-16) |
| An update gets a privileged permission only if the `/system` copy asked for it, unless the update is signed with the platform key (or the permission's own key) | Read 3: the signature check comes first, then the updated-system-app branch | Read |
| With `INSTALL_PACKAGES`, a `PackageInstaller` commit needs no tap; without it, `STATUS_PENDING_USER_ACTION` | Read 6; the probe on the unit (2026-11-16): v1 to v2 through `PackageInstaller` with no prompt | **Verified** (2026-11-16) for the case with `INSTALL_PACKAGES`; the case without it is read |
| `WRITE_SECURE_SETTINGS` granted on the unit proves little by itself | Kiln's first boot runs `pm grant … WRITE_SECURE_SETTINGS` (`grantPermissions` in `@glassworks/app-adb`), which a `development` permission allows | Read |

What it means for Glassworks, now that the probe confirmed it: Glassworks updates itself on the Mirror from
Home Assistant with no tap and no root, can restart the Mirror, and writes its own settings. The APK
Kiln flashes into `system` must already ask for every privileged permission a later version will
need, because an update signed with our key can't add one (row 3). Kiln refuses an APK that doesn't
ask for all three.

## Can a privileged app approve an adb key?

**Status (2026-11-16): verified on the unit.** The approval works for the computer that is asking, and
doesn't last on this image ([the run](#the-run-on-the-unit-2026-11-16)). The Mirror has no touchscreen, so its "Allow USB debugging?" dialog can't
be answered on the glass. If Glassworks could answer it, a Manage page "Allow
a new computer over USB" would be possible.

Read at `android-6.0.1_r81` (`platform/frameworks/base`):

| # | File and line | What it says |
|---|---|---|
| 1 | `core/res/AndroidManifest.xml:1164` | `MANAGE_USB` is `signature\|privileged`: a privileged system app gets it, as with `INSTALL_PACKAGES` above |
| 2 | `core/java/android/hardware/usb/IUsbManager.aidl:103` | `void allowUsbDebugging(boolean alwaysAllow, String publicKey)`. Hidden; the app reaches it through `ServiceManager.getService("usb")` and `IUsbManager.Stub.asInterface`, by reflection |
| 3 | `services/usb/java/com/android/server/usb/UsbService.java:329–330` | `enforceCallingOrSelfPermission(MANAGE_USB)` (line 330), then `UsbDeviceManager.allowUsbDebugging` (`UsbDeviceManager.java:940`), which posts `MESSAGE_ADB_ALLOW` to `UsbDebuggingManager` |
| 4 | `UsbDebuggingManager.java:236`–`248` | On `MESSAGE_ADB_ALLOW`, the key's fingerprint (MD5 of the decoded key) must equal `mFingerprints`, else it logs "Fingerprints do not match" and **does nothing**. On a match with `alwaysAllow`, `writeKey` appends the key to `/data/misc/adb/adb_keys` (line 403). Any match, with or without `alwaysAllow`, also sends "OK" to the computer connected at that moment (line 251, only if one is) |
| 5 | `UsbDebuggingManager.java:271`–`281` | `mFingerprints` is set only on `MESSAGE_ADB_CONFIRM`: adbd asking about a computer that just connected with an unknown key. It keeps that value until the next computer asks: a deny, an unplug or the dialog going away doesn't clear it, so a later call with that same key still writes it |
| 6 | `IUsbManager.aidl:109`, `UsbDebuggingManager.java` `MESSAGE_ADB_CLEAR` | `clearUsbDebuggingKeys` deletes the whole `adb_keys` file. The probe never calls it: it would remove every key, the root's included, with no touchscreen to allow one again |

On the unit (2026-11-16, verified, read-only: the root, over adb's shell on the Mirror):

| Command | What came back | What it settles |
|---|---|---|
| `dumpsys package android` | `Permission [android.permission.MANAGE_USB] … prot=signature\|privileged` | Read 1 holds on this image: the probe's `/system` copy can hold `MANAGE_USB` |
| `service list` | `usb: [android.hardware.usb.IUsbManager]` | The `usb` service the probe reaches through `ServiceManager.getService("usb")` exists, with that interface |
| (`MANAGE_DEBUGGING`) | Not on this image | Later Android added `MANAGE_DEBUGGING` for this; on Android 6, `MANAGE_USB` is the permission `allowUsbDebugging` checks (read 3) |

**What we expect.** `allowUsbDebugging` is not a way to add any key. It answers the last computer the
Mirror asked about.
- A key no computer asked for is ignored.
- A key that matches the last computer asked about is written, even after a deny or an unplug, and
  that computer connects when it next tries.

The call is asynchronous and returns nothing, so the app can't tell which happened: `adb_keys` and
logcat do.

**For Glassworks.** A Manage page could approve "the computer that last asked", if it has that
computer's full public key. A fingerprint can only confirm a key, not supply it. Kiln can send the
key, since it holds the browser's key, and the page can show its fingerprint to confirm. It can't
pre-approve keys. The probe's step 6 tests both cases: no computer asking, and with a second computer asking.

### The run on the unit (2026-11-16)

The root ran the probe's step 6 on the Mirror (Glass Panes image 2, the probe in `/system/priv-app`), with
the method now in the probe's README: a throwaway key pair, the approval queued on the Mirror with
`nohup sh -c "sleep 20; am start … allow-adb-key …"`, then adb's server restarted with only the test
key (`HOME=<dir> adb start-server --one-device <serial>`), then the normal server again. In that
order adb ignores `--one-device` (it parses global options only before the command), and the server
did see the S1 too. The right form is `HOME=<dir> adb --one-device <serial> start-server`.

| What | Result |
|---|---|
| The approval while the test key's computer was asking | `unauthorized` to `device` within about 1 s of the queued call. One-time approval works |
| The key afterwards | Written to `/data/misc/adb/adb_keys` (`dumpsys usb` lists it) |
| The next connection with the same key | `unauthorized` again: **it doesn't last**. This image's adbd ignores `adb_keys` ([image-patch.md](image-patch.md#who-the-image-lets-in-over-adb)) |
| Reconnecting the root's own computer between queueing and switching | Resets "the last computer asked about" to that computer: every connection sets `mFingerprints` (the image's `MESSAGE_ADB_CONFIRM`, case 5), its own included. Hence the queued call |
| `adb shell cat /data/misc/adb/adb_keys` | Not readable by the shell here. `dumpsys usb` lists the keys instead |

What it means for Glassworks: an "Allow a new computer" page could let a computer in for one
connection. A lasting one needs the key where this image looks, which is `services.jar`'s constant
(what Kiln's flash now writes), or a fix in adbd.

### How the app sees who asks (#435, read 2026-11-17)

The app keeps its own list and approves a listed computer each time it connects (the plan in #435).
The root checked the plan on the unit on 2026-11-17 (verified, recorded in a comment on #435, Glass Panes
image 2 with the probe as a priv-app holding `MANAGE_USB`). A shell loop on the Mirror played the app: each
second it checked whether `UsbDebuggingActivity` had focus and whether `dumpsys usb`'s "Last key received"
was the listed fingerprint, and then ran the probe's `allow-adb-key` with that key. A second adb server on
the computer, with only a throwaway key, connected four times, with a server restart between each:

| Connection | Result |
|---|---|
| 1 | `device` after 4 s, shell ran |
| 2, 3, 4 | `device` after 3 s each, shell ran |

The app itself, with the system log below in place of the shell loop, is **untested** on the unit. It needs the asking computer's **full key**: Android
checks the fingerprint, but `allowUsbDebugging` takes the key. `dumpsys usb` gives only the fingerprint.
Read at `android-6.0.1_r81`, `services/usb/java/com/android/server/usb/UsbDebuggingManager.java`:

| Line | What it says | What the app does with it |
|---|---|---|
| 138 | `Slog.d(TAG, "Received public key: " + key)`, once per computer asking, before `MESSAGE_ADB_CONFIRM` | Reads the system log (`READ_LOGS`, `signature\|privileged\|development`): each line is a new request with the full key. No polling |
| 139–141, 279 | The log line is written first, then the confirm message is queued on the handler that sets `mFingerprints` | An approval sent as soon as the line is read can reach the handler before that message and be refused. The app sends a listed computer's approval once more after a refusal, then shows it as asking |
| 240–243 | A key that isn't the one asking: logs `Fingerprints do not match. Got <fp>, expected <fp>` and does nothing. A match logs nothing | Allow waits 3 s for that line: only without it does the page say the computer is in and save it to the list |
| 246 | `writeKey` only when `alwaysAllow` is set | The app passes `false`: `adb_keys` doesn't grow, and Remove takes a computer out for good |
| 458 | `dump` prints "Last key received" as the fingerprint only | Not used: it can't supply the key, and it doesn't change when the same computer asks again |

**Any app can write these lines.** Logging under the `UsbDebuggingManager` tag needs no permission, so another
app on the panel can forge a request or a refusal. That can only mislead the card: a forged request shows a
computer that isn't asking, and Android refuses its approval because the fingerprint isn't the one it waits on
(lines 240–243). A forged refusal makes Allow report a failure. Neither lets a computer in.

**Untested on the unit:** that the Glass Panes image's `services.jar` still logs line 138 (its
`handleMessage` was changed, [image-patch.md](image-patch.md#what-the-image-does); `listenToSocket` wasn't
read), and that the `/system` copy gets `READ_LOGS`. The panel plan for #435 checks both first.

## How to reproduce

Off-device: fetch the files above at `android-6.0.1_r81` from android.googlesource.com
(`…/+/refs/tags/android-6.0.1_r81/<path>?format=TEXT`, base64) and read the lines given.

On the unit: the test sequence in [tools/mirror/probe-app](../../../tools/mirror/probe-app/README.md#the-test-on-the-mirror).
It flashes `system` and `userdata` with the probe as the APK, then works over adb only. To put the
APK back, flash again with the APK the unit had, or the way back. Neither brings back the unit's old
`userdata`: every flash writes a fresh one, and the bootloader can't read the old one back first.

## Open questions

- ~~All of the above, on the unit: the probe's steps 1 to 5.~~ Done, 2026-11-16: all passed.
- ~~The adb key approval above, on the unit: the probe's step 6.~~ Done, 2026-11-16: works for one connection, doesn't last.
- Whether this image logs "Received public key" (above, #435's panel run).
- Whether CAF's package manager in this image differs from AOSP's in these paths.
- Whether the Mirror's vendor SELinux policy lets `untrusted_app` read `/data/local/tmp` (read 7).
