---
topic: install
evidence: read
---

# Stock-in-place parity: can the in-place install give what the Glassworks image gives?

**Status (2026-11-25): untested on a Mirror.** Kiln's in-place install now covers four more things the full image gives:
- the computers the Manage page lets in over USB;
- Glassworks' boot animation, as a still;
- one Home Assistant device across both ways of installing;
- the display and Wi-Fi defaults.

Each is checked off-device, the `services.jar` change against the real stock 1.24.0 jar. None has run on a Mirror yet.

Device: lululemon Studio Mirror, stock 1.24.0 (Android 6.0.1, MSM8916). Tools: jadx, dexdump (Android SDK build-tools 36),
`strings`, Pillow 9.5.0, the repo's vitest suites.

## Question

The full Glassworks image ([image-patch.md](image-patch.md)) puts the app in `/system/priv-app`, writes a boot animation
and a settings overlay, and carries the Home Assistant identity in `build.prop`. Stock-in-place
([stock-firmware-probe.md](stock-firmware-probe.md)) writes only `services.jar`, in its own blocks, plus a fresh
`userdata`. The app goes in over adb as a plain app. The maintainer's ask, after the first in-place run on 2026-11-25:
the in-place install should give what the image gives, boot animation and USB debugging included.

## How we got here

The in-place write has one hard limit. Kiln changes a file only at its exact size, in the blocks FIEMAP reports for it,
and never touches ext4's metadata: no new files, no new inodes, no directory changes. So each feature had to be a
change to an existing file at the same size, or a change outside `/system`.

- **USB debugging.** On the image, the app answers Android's "Allow USB debugging?" through the hidden
  `IUsbManager.allowUsbDebugging`, which checks `MANAGE_USB` (signature|privileged). An adb-installed app can't hold
  `MANAGE_USB`. Two ways out:
  - Making the app privileged would mean a new file in `/system/priv-app`, which the in-place write can't make.
  - Kiln already rewrites `services.jar`, so the check itself can change. The permission it checks is one string
    operand, and Android 6 has development permissions that adb's `pm grant` can grant. `WRITE_SECURE_SETTINGS` is one
    of them. An app holding it can already turn adb on (`Settings.Global.ADB_ENABLED`), so approving the computer
    that's asking adds no power.
- **Boot animation.** The image writes `/system/media/bootanimation.zip`, but that's a new file there: stock has no
  file at that path in the community image. Stock 1.24.0 has its own, at 22,901 bytes
  ([stock-firmware-probe.md](stock-firmware-probe.md)), so the same overwrite in place applies.
  - image-patch.md said Android 6 also reads `/data/local/bootanimation.zip`, which would have needed no system write
    at all. The stock binary says otherwise (attempt 2).
  - Ours is 11.4 MB, about 500 times too big. A one-frame animation of the still it ends on fits, padded to the stock
    size.
- **Home Assistant identity.** Home Assistant showed two "lululemon Studio Mirror" devices after the first in-place run.
  - The image writes `ro.glassworks.device.mac` (from the serial) into `build.prop`. The in-place install has no
    `build.prop` to write, so the app fell back to the Wi-Fi MAC, a different id.
  - A global setting can carry the same value: adb's shell writes it, and any app reads it.
- **Display and Wi-Fi.** The image's overlay changes `SettingsProvider` defaults for a fresh `userdata`, and stock
  keeps its own. adb's shell can set the same values after install, except the lock screen.

## What we tried

1. **UsbService in the stock jar** (2026-11-25, read). In scratch, outside the repo. The jar's SHA-256 starts
   `fe16a798`.
   ```
   $ jadx -d out --single-class com.android.server.usb.UsbService services.jar.orig
   $ grep -n 'allowUsbDebugging' -A2 out/.../UsbService.java
   265:    public void allowUsbDebugging(boolean alwaysAllow, String publicKey) {
   266:        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
   267:        this.mDeviceManager.allowUsbDebugging(alwaysAllow, publicKey);
   ```
   The string ids in `classes.dex` (51,323 strings): `MANAGE_USB` 21631, `WRITE_SECURE_SETTINGS` 21731, `READ_LOGS`
   21660. All are under 65,536, so a 4-byte `const-string` takes either one, and nothing moves.

2. **Which boot animation paths stock's `bootanimation` reads** (2026-11-25, read). This was over the stock system
   image Kiln's tools read earlier (scratch, not in the repo).
   ```
   $ LC_ALL=C strings -n 12 system-stock-1.24.0.sparse | grep -E 'bootanimation[a-z-]*\.zip' | sort | uniq -c
      1 /oem/media/bootanimation.zip
      1 /system/media/bootanimation-encrypted.zip
      3 /system/media/bootanimation.zip
      1 bootanimation.zip
   ```
   There's no `/data/local` path. The Mirror has no `/oem`, so `/system/media/bootanimation.zip` is the only one.
   image-patch.md's "`/data/local`" was wrong for this build and is corrected there.

3. **The still, and its size** (2026-11-25, verified off-device).
   - `branding/boot/still.py` takes the animation's last frame, `part2/frame_0000.png`, already turned into the panel's
     1920×1080 framebuffer.
   - It crops a box centered on the screen around everything that isn't the background (`#0a0b0f`). Android 6 centers
     `desc.txt`'s rectangle and draws the frame from its top-left, so a centered box puts the still where the full
     animation leaves it.
   - It quantizes to 256 colors.
   ```
   $ python3 branding/boot/still.py --zip branding/lululemon-mirror/bootanimation.zip --out branding/lululemon-mirror
   part2/frame_0000.png -> bootanimation-still.png 1858x262, 14067 bytes
   ```
   Side by side with the original, the 256-color still looks the same. 64 colors would be 9,519 bytes, but there's no
   need.

4. **The patched jar, against the real one** (2026-11-25, verified off-device). This ran as a local test that read the
   jar from scratch and was deleted after, never in the repo.
   ```
   $ diff <(jadx UsbService, stock) <(jadx UsbService, patched)
   235c235
   <         this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
   ---
   >         this.mContext.enforceCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS", null);
   ```
   - That's one line, in `allowUsbDebugging`. `denyUsbDebugging` and `clearUsbDebuggingKeys` keep `MANAGE_USB`.
   - The jar keeps its exact length.
   - Reinstalling over a jar an earlier install patched without this change gives the same bytes as patching stock
     directly.

5. **What the patched `startConfirmation` does with another computer's key** (2026-11-25, read; AOSP 6.0.1
   `UsbDebuggingManager`, `adb_auth_client.cpp`).
   - The in-place patch denies any key but this browser's at once: `denyUsbDebugging()` sends `NO` to adbd.
   - adbd acts only on `OK`, so `NO` changes nothing.
   - `UsbDebuggingManager` still remembers that computer's fingerprint as the one asking.
   - So the app's later `allowUsbDebugging(false, key)` sends `OK`, and adbd lets that connection in. Untested on the
     Mirror.

6. **The first start after the in-place write** (2026-11-25, verified on the unit, the maintainer's run). Kiln's
   first-start wait failed once with `Failed to execute 'transferOut' on 'USBDevice': A transfer error has occurred.`,
   after Kiln was already in over adb. Try again worked.
   - Android 6 resets USB partway through its start, so the poll for `sys.boot_completed` loses the device.
   - Kiln now connects again on its own when that poll fails, with no list, within the same 6 minutes.

## Result

| What the image gives | How Stock-in-place gives it now | How sure |
|---|---|---|
| The Manage page lets in other computers over USB | `services.jar`: `UsbService.allowUsbDebugging` checks `WRITE_SECURE_SETTINGS` (`patchUsbAllow`, `installer/src/stock.ts`). The install grants it and `READ_LOGS` over adb, and sets `glassworks_usb_allow_permission`, which the app's `UsbDebugging.available` reads | Read and checked off-device against the real jar. Untested on a Mirror |
| Glassworks' boot animation | The agent sends stock's `bootanimation.zip` (bytes, SHA-256, FIEMAP) in its signed metadata. Kiln writes a one-frame animation of the still (`bootStillZip`, `installer/src/bootstill.ts`), exactly stock's size, in its blocks. The restore point keeps lululemon's | Checked off-device (two zip readers, the build's sparse write and restore). Untested on a Mirror: how Android 6 draws the 1858×262 frame is read, not seen |
| One Home Assistant device across reinstalls | Kiln writes `glassworks_device_mac` (the serial's MAC, the image's formula) before the app's first start. The app reads it as it reads `ro.glassworks.device.mac` (`PanelDevice.INSTALLER_MAC_SETTING`) | Off-device tests. Untested on a Mirror |
| The overlay's display and Wi-Fi defaults | `screen_off_timeout` 2147483647, `stay_on_while_plugged_in` 7, `svc wifi enable` over adb. Rotation already goes through `setPortrait` | Off-device tests. The lock screen is left as stock has it: Android 6 moves that setting into LockSettings on the first boot, out of adb's reach. Stock showed none on the 2026-11-25 run |

## How to reproduce

- Off-device, writes nothing to any device: `npx vitest run` in `frames/lululemon-mirror/installer` (`stock.test.ts`,
  `bootstill.test.ts`, `inplace.test.ts`), and
  `./gradlew --max-workers=2 :glassworks-core:testDebugUnitTest --tests '*UsbDebugging*' --tests '*PickMac*'`.
- The check against the real jar needs the stock jar outside the repo. Patch it with `patchStockServicesJar`, then diff
  jadx's `UsbService` before and after, as in attempt 4.
- On a Mirror: an in-place install with this build writes `services.jar` and `bootanimation.zip` in their own blocks.
  The restore point puts both back (`restore-services.sparse` covers every block the write changed). Kiln's way back
  writes it, with a fresh `userdata`.

## Open questions

- Does the still show centered and upright on the glass? It's turned the way the full animation is, which was verified
  upright on 2026-11-16.
- Does the Manage page's Allow work end to end on stock? The next test: plug in a second computer, Allow it from Manage,
  and run `adb devices` from that computer.
- The API key isn't carried over an in-place reinstall yet, as the image's flash carries it
  (`ro.glassworks.api_key.handover`). With the same device id, Home Assistant asks to re-authenticate instead of
  adding a second device.
