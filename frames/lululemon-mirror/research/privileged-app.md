# Privileged app: does a /system/priv-app app get Android 6's privileged permissions, and keep them after it updates?

**Status (2026-11-15): read, untested on the unit.** AOSP 6.0.1's package manager grants
`signature|privileged` permissions to any app scanned from `/system/priv-app`, whatever its signing
key, and an update of that app keeps them while it is signed with the same key. A probe app to test
it on the Mirror is ready: [tools/mirror/probe-app](../../../tools/mirror/probe-app/README.md).

Device: lululemon Mirror (MSM8916), community image Glass Panes `mirror-android-1` (Android 6.0.1,
`MMB29M`, `userdebug`, `test-keys`). Source: AOSP tag `android-6.0.1_r81`
(`platform/frameworks/base` and `platform/external/sepolicy` on android.googlesource.com, read
2026-11-15). The image is built from CAF's tree for the DragonBoard; that its package manager matches
AOSP here is an assumption the probe tests.

## Question

Kiln's Mirror image puts S1 Kiosk in `/system/priv-app/S1Kiosk` ([image-patch.md](image-patch.md)).
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
| An app in `/system/priv-app` gets `INSTALL_PACKAGES`, `REBOOT`, `WRITE_SECURE_SETTINGS`, whatever its key | Reads 1–3 | Read, untested on the unit |
| An update signed with the same key keeps them, and stays privileged across reboots | Reads 4–5 | Read, untested on the unit |
| An update gets a privileged permission only if the `/system` copy asked for it, unless the update is signed with the platform key (or the permission's own key) | Read 3: the signature check comes first, then the updated-system-app branch | Read |
| With `INSTALL_PACKAGES`, a `PackageInstaller` commit needs no tap; without it, `STATUS_PENDING_USER_ACTION` | Read 6 | Read, untested on the unit |
| `WRITE_SECURE_SETTINGS` granted on the unit proves little by itself | Kiln's first boot runs `pm grant … WRITE_SECURE_SETTINGS` (`grantPermissions` in `@glassworks/app-adb`), which a `development` permission allows | Read |

What it means for Glassworks, if the probe confirms it: S1 Kiosk updates itself on the Mirror from
Home Assistant with no tap and no root, can restart the Mirror, and writes its own settings. The APK
Kiln flashes into `system` must already ask for every privileged permission a later version will
need, because an update signed with our key can't add one (row 3).

## How to reproduce

Off-device: fetch the files above at `android-6.0.1_r81` from android.googlesource.com
(`…/+/refs/tags/android-6.0.1_r81/<path>?format=TEXT`, base64) and read the lines given.

On the unit: the test sequence in [tools/mirror/probe-app](../../../tools/mirror/probe-app/README.md#the-test-on-the-mirror).
It flashes `system` and `userdata` with the probe as the APK, then works over adb only. To put the
APK back, flash again with the APK the unit had, or the way back. Neither brings back the unit's old
`userdata`: every flash writes a fresh one, and the bootloader can't read the old one back first.

## Open questions

- All of the above, on the unit: the probe's steps 1 to 5.
- Whether CAF's package manager in this image differs from AOSP's in these paths.
- Whether the Mirror's vendor SELinux policy lets `untrusted_app` read `/data/local/tmp` (read 7).
