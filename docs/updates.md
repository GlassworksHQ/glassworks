# Updates and restoring stock software

Glassworks updates right from Home Assistant, just like any other smart home device. On rooted panels and modern Android tablets, updates install completely hands-free, and every supported device includes a clean way to undo the install and return to stock software.

## Updating Glassworks from Home Assistant

Every panel checks GitHub Releases automatically—30 seconds after startup, whenever Wi-Fi reconnects, and once every 24 hours. When a new version is available for your device, it appears in Home Assistant under **Settings → Updates** (via the panel's **App update** entity).

1. Open **Settings → Updates** in Home Assistant and select the Glassworks update.
2. Click **Install**.
3. The panel downloads the APK over HTTPS, verifies its SHA-256 checksum against both the release checksum file and GitHub's asset digest, and checks that the package ID and signing certificate match the installed app.
4. Once verified, the panel installs the update in place and restarts the app. Your settings, Home Assistant sign-in, and dashboard configuration stay intact.

You can also trigger an update check manually from the **Overview** page of the [Manage page](manage.md) (*Check for updates*) or by calling `homeassistant.update_entity` in Home Assistant.

### Stable and Beta channels

By default, panels only offer **Stable** releases. To test upcoming features early, switch **Update channel** to **Beta** on the panel's Device settings page or on the [Manage page](manage.md).

If you switch back from Beta to Stable, Glassworks never downgrades your app—it simply waits until a stable release with a higher version number is published.

## Automatic (silent) updates vs. confirming on screen

Whether an update installs silently or asks for a confirmation tap on the panel's screen depends on your device and Android version. You can check your panel's current status anytime via the **Silent updates** diagnostic sensor in Home Assistant, or on the panel's Device page (and Manage page), which also explains how to enable silent updates if they are currently off.

| Device / setup | Installs silently? | What is needed |
|---|---|---|
| **Aqara S1 Plus** (installed with Kiln) | Yes | Handled automatically by the boot image's root update service. |
| **lululemon Studio Mirror** (installed with Kiln) | Yes | Kiln installs Glassworks as a privileged system app in `/system/priv-app`. |
| **Generic Android (Android 12 or newer)** | Yes | Allow **Install unknown apps** for Glassworks (*Settings → Apps → Glassworks → Install unknown apps*). Kiln enables this automatically during USB install. |
| **Generic Android (Android 8–11)** | Only as device owner | By default, Android asks for a tap on the screen to confirm each update. To enable hands-free updates, make Glassworks the device owner over adb (requires a device with no accounts, such as right after a factory reset). See [Device owner](../frames/generic-android/README.md#device-owner). |

While an update is running, the **Update status** diagnostic sensor shows whether it is installing hands-free (*"Installing 1.2.3 without a tap"*) or waiting for confirmation (*"Installing 1.2.3: waiting for a tap on the screen"*).

> [!NOTE]
> **Upgrading an Aqara S1 Plus from v0.4.0 or older:** Versions up to `0.4.0` had an Android 10 signature-check bug that caused in-app Home Assistant updates to fail with *"the APK is not signed"*. Perform one manual update by plugging the panel into USB, opening [Kiln](https://kiln.glassworks.dev/aqara-s1-plus/set-up) (**Finish setting up**), or running `adb install -r` with the latest APK. Starting with `0.4.1`, all future updates work directly from Home Assistant.

## Frame updates (boot software and system images)

In addition to the Glassworks app itself, dedicated frames like the **Aqara S1 Plus** and **lululemon Studio Mirror** have a **Frame update** entity in Home Assistant (and a **Frame version** row on the panel's Device page and Manage page). This tracks the Glassworks-managed system layer—such as the S1 Plus boot-side helper scripts and boot animation, or the Mirror's patched system image.

- **Aqara S1 Plus:** Once the panel's boot image has the frame loader, press **Install** on the **Frame update** card (or on the Device page). The panel downloads the signed frame bundle, checks it, and restarts once to finish. Until then, the card says how to get the loader. Keep the backup `.zip` from your **first** Kiln install, as it holds your original Aqara boot partition.
- **lululemon Studio Mirror:** Frame updates are installed by reflashing the updated image with Kiln over USB.
- **Generic Android tablets and phones:** Your device manufacturer handles Android OS updates, so there is no Frame update entity.

## Switching from a development build to a release

Android only allows in-place updates when the new APK is signed with the same key as the installed app. If you previously installed a local debug build from the command line and want to move to official releases, the app must be replaced once.

Both Kiln (**Finish setting up**) and the command-line installer detect this automatically, explain what will happen before making any changes, and prompt you to confirm. On a standard install without adb root, replacing the app resets app settings and requires signing in to Home Assistant again; on a development install with adb root, the installer preserves your settings, Home Assistant sign-in, and encryption key. See [Switching a development build to a release](../frames/aqara-s1-plus/docs/install.md#switching-a-development-build-to-a-release) for full details.

## Undoing and restoring stock software

Every Glassworks install is designed to be reversible.

### Aqara S1 Plus

To return an Aqara S1 Plus to Aqara's stock software, open [Kiln's Undo page](https://kiln.glassworks.dev/aqara-s1-plus/undo) in Chrome or Edge with the backup `.zip` from your **first** install (Kiln also keeps a copy in your browser unless you cleared site data):

- **As it came from Aqara:** Restores your original `boot`, `vbmeta`, and boot splash partitions over USB recovery mode (FEL) and verifies each write. Afterward, run a factory reset from the panel's settings to remove Glassworks and return to Aqara's out-of-the-box setup.
- **Keep its settings:** Re-enables Aqara's apps, launcher, keyboard, and radio services over USB while keeping your Wi-Fi configuration, removes or disables Glassworks, and restores the original partitions.

For full step-by-step instructions and command-line undo commands, see [Undo in the Aqara S1 Plus install guide](../frames/aqara-s1-plus/docs/install.md#undo).

### Generic Android tablets and phones

- **With Kiln:** Plug the tablet in over USB and choose **Undo** in Kiln. Kiln clears the home screen assignment, deactivates device admin, and uninstalls Glassworks cleanly.
- **Directly on the device:** Search Android's Settings for **Device admin apps** and turn off Glassworks first (Android blocks uninstalling an active device admin). If you set Glassworks as the device owner, release device owner over adb first as described in [Device owner](../frames/generic-android/README.md#device-owner). Once deactivated, uninstall Glassworks like any normal Android app.
