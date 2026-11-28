<p align="center">
  <a href="https://www.glassworks.dev/frames/generic-android">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-frame-generic-android-on-dark.svg">
      <img alt="Generic Android, a Glassworks frame" src="https://www.glassworks.dev/brand/banner-frame-generic-android-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Generic Android</h1>

<p align="center">
  <b>Any Android device, installed as a standard app with no root required</b><br>
  Glassworks Core plus every pane your hardware supports—great for tablets, old phones, and off-the-shelf Android panels.
</p>

<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-on-dark.svg">
      <img alt="Built on Glassworks" src="https://www.glassworks.dev/brand/badge-on-light.svg" height="30">
    </picture>
  </a>
  <a href="https://kiln.glassworks.dev/android">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-stable-on-dark.svg">
    <img alt="Status: stable" src="https://www.glassworks.dev/brand/badge-status-stable-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
  <a href="https://www.glassworks.dev/frames/generic-android">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/frames/generic-android/clarity-on-dark.svg">
      <img alt="Clarity" src="https://www.glassworks.dev/frames/generic-android/clarity-on-light.svg" height="30">
    </picture>
  </a>
</p>

This folder defines the **Generic Android** frame (`design/frame.json`, [format](../README.md)), showcased at [glassworks.dev/frames/generic-android](https://www.glassworks.dev/frames/generic-android).

## Status

**Status: stable.** You can install Glassworks on any Android 8+ tablet or phone straight from Chrome or Edge at [kiln.glassworks.dev/android](https://kiln.glassworks.dev/android), with a separate legacy build available for Android 6 and 7 devices (including the lululemon Studio Mirror). On a Google Pixel Tablet, dashboard loading, Home Assistant ESPHome adoption, automatic pane hiding, and in-place updates are all verified; hardware testing for wake words, Sendspin audio, and the ambient light sensor is still underway.

**Tested devices:** the [Google Pixel Tablet](../google-pixel-tablet/README.md), tested by Glassworks on stock Android, and the [Samsung Galaxy Tab A 10.1 (2016)](../samsung-galaxy-tab-a-10-1-2016/README.md), tested by the community on LineageOS. Tried Glassworks on another device? Tell us how it went with a [device report](https://github.com/GlassworksHQ/glassworks/issues/new?template=device-report.yml), even if everything worked: it's how a device becomes a tested device. If you installed with Kiln, **Tell us how it goes** on its Done screen sends one for you, without a GitHub account.

*(For reference, the Aqara S1 Plus APK also automatically falls back to generic behavior if installed on non-S1 hardware, naming itself after the device model and hiding unsupported panes.)*

### What the Generic Android app includes

The app bundles Glassworks Core with the hardware-neutral panes: **Assist** (with **Timers**), **Sendspin**, **Notifications**, **Bluetooth proxy**, **Camera**, and **Diagnostics**. Any pane whose required hardware is missing (such as a microphone, speaker, Bluetooth LE, or camera) is hidden automatically from setup, settings, the Manage page, and Home Assistant.

Both builds also carry **Vitro**, an experimental renderer that draws your dashboard with native views instead of the browser. It's off by default and lives under *Settings → Labs*. The legacy build has seen the least testing so far. [Try Vitro](../../docs/vitro.md) explains how to turn it on and off, and what happens if it fails.

Key behaviors to know on non-rooted Android tablets and phones:

- **Screen and sensors:** Uses Android's built-in proximity sensor (short-range hand wave, typically under 5 cm) and ambient light sensor when available. On tablets without a proximity sensor, you can enable the **Camera** pane for motion-based wake on approach, or simply let the screen sleep after your idle timeout and wake on touch. The screen stays awake until initial setup finishes and until you configure an idle timeout under **Settings → Screen → Turn off when idle**.
- **Screen lock warning:** If your Android device has a PIN, pattern, or password enabled, turning the screen off locks Android and requires unlocking on wake. Both Screen settings and the Manage page display an amber reminder explaining how to disable the lock screen or keep the display awake.
- **Bluetooth proxy:** Without a root helper to read the Bluetooth adapter's hardware MAC, Home Assistant keys the scanner on the panel's ESPHome MAC.
- **Non-touch displays:** On touchless screens (like the lululemon Studio Mirror), high or critical notifications without a `timeout` or `tag` dismiss automatically after 2 minutes, and the Camera setup step stays in *Presence only* mode without blocking for touch input.
- **Intent security:** Configuration extras (`adb shell am start -n dev.glassworks.generic_android/dev.glassworks.MainActivity --es …`) require Android's `WRITE_SECURE_SETTINGS` permission (available to `adb shell` only). The launcher icon uses a separate alias that strips extras so other installed apps cannot modify Glassworks settings.

| Detail | S1 app on a tablet | Generic Android app |
|---|---|---|
| `applicationId` | `sh.edm.s1kiosk` | `dev.glassworks.generic_android` |
| Home Assistant model | `<Model> (Glassworks)` | `<Model> (Glassworks)` |
| Included panes | All S1 panes supported by hardware | Assist, Timers, Sendspin, Notifications, Bluetooth proxy, Camera, Diagnostics |
| Presence & ambient light | None | Android proximity and light sensors |

Both apps keep separate data and keys, so both can coexist on a test device—just stop one (`adb shell am force-stop <package>`) before launching the other since both listen on ports `6053` and `8099`.

### The legacy build (Android 6 and 7)

Devices running Android 6 or 7 (API 23–25), including older tablets and the **lululemon Studio Mirror**, use a dedicated legacy build (`dev.glassworks.generic_android.legacy`). It runs a frozen browser engine locked strictly to your Home Assistant address ([why](../../docs/research/legacy-engine.md)), disables remote debugging, and follows its own release channel so it never conflicts with the modern Android 8+ app.

On the Manage page's Overview screen, the legacy build displays a **Legacy** badge next to the version number summarizing those security boundaries.

*(For contributors building locally: run `./gradlew :generic-android:assembleLegacy -Ps1Abi=arm64-v8a -PlegacyAbi=arm64-v8a`, keeping `-PlegacyAbi` matched to `-Ps1Abi`. Because Android Gradle Plugin applies core library desugaring module-wide, the legacy APK includes `desugar_jdk_libs` under GPL-2.0 with the Classpath exception—see `THIRD-PARTY-NOTICES.md`—while the modern Android 8+ APK contains no desugared library code.)*

The app automatically adapts to the device without requiring custom per-device code:

- **Orientation:** Follows Android's auto-rotate or user rotation lock (Kiln locks the Mirror to portrait at first boot), while recognized frames like the Pixel Tablet and the Galaxy Tab A 10.1 (2016) use the orientation defined in their `frame.json`.
- **Phone screens:** On a phone, setup and settings rearrange to fit, whichever way it's held. Held upright, setup shows its steps across the top and stacks its buttons. Settings opens on its list of pages and goes back to it with **‹ Settings** or the Back button.
- **Touchless setup:** When no touchscreen input device is detected, first-boot setup automatically switches to network setup from a phone or computer ([Set up over the network](../../docs/manage.md#set-up-over-the-network)).

Neither build is distributed on Google Play because Glassworks manages its own updates from GitHub Releases and requests privileged permissions (`INSTALL_PACKAGES`, `REBOOT`, `WRITE_SECURE_SETTINGS`) needed for dedicated hardware like the Mirror.

## Hardware

| Component | Support |
|---|---|
| Radio | None |
| Presence | Built-in proximity sensor or front camera (if present) |
| Audio | Built-in microphone and speaker (if present) |
| Root | Not required |

## Install

Generic Android installs as a standard APK. Open `kiln.glassworks.dev/android` in Chrome or Edge: Kiln downloads the newest `generic-android-v*` release and checks it against the release's `.sha256`. That build needs Android 8 or newer. Kiln reads the device's Android version and processor before it writes anything, and installs the 64-bit (arm64-v8a) or 32-bit (armeabi-v7a) APK, whichever the device runs. Some tablets with a 64-bit chip run only 32-bit apps, such as Samsung's own Android 8.1 on the Galaxy Tab A 10.1, and they get the 32-bit one. For **Android 6 or 7** (the legacy build, `dev.glassworks.generic_android.legacy`), open `kiln.glassworks.dev/android?apk=local` and pick the APK that fits from your computer, as you would a test build.

Kiln's Generic Android recipe ([`installer/`](installer)) guides you through enabling USB debugging for your tablet's brand, then automatically:

1. Checks device compatibility (rejecting devices below Android 6 or devices with their own dedicated frame like the Aqara S1 Plus).
2. Installs or updates Glassworks in place.
3. Grants runtime permissions over adb (microphone, camera, overlay, secure settings, device admin) and verifies each grant.
4. Allows **Install unknown apps** on Android 12+ so future updates install silently ([Silent self-update](#silent-self-update-android-12)).
5. Sets Glassworks as the home screen and checks whether a screen lock PIN/password is active so it can warn you if unlocking will be required after sleep.
6. Hands off to network setup (`http://<panel>:8099/setup`).

To test an unreleased branch, grab the APK from the `glassworks-generic-test-<commit>` artifact produced by [generic-test-build.yml](../../.github/workflows/generic-test-build.yml) (which includes both standard and legacy APKs) and run `adb install -r <apk>`.

### Without a computer: setup's This device step

If you sideload the APK directly on your tablet without using Kiln over USB, first-boot setup adds a **This device** step right after Panes so you can grant the two key Android roles with a tap:

| Setting | Button | What Android shows |
|---|---|---|
| **Home screen** | Make it the home screen | Android's Home app role prompt (Android 10+) or Home app settings screen |
| **Turn the screen off** | Let it turn the screen off | Android's Device Admin activation prompt, allowing Glassworks to sleep the display immediately when idle or when Home Assistant turns it off |

If you tap **Not now**, both options stay available anytime on the **Device** settings page. When Kiln configures both over adb, setup skips this step automatically.

**Uninstalling later:** Android prevents uninstalling an app while it is an active device admin. Open Android's Settings, search for **Device admin apps**, and turn off Glassworks first (or release device owner if configured below), then uninstall normally. Kiln's **Undo** handles this automatically.

## Updates

Updates arrive in Home Assistant under **Settings → Updates** just like on the Aqara S1 Plus ([Updates guide](../../docs/updates.md), [ESPHome App updates](../../docs/esphome.md#app-updates)). Each build tracks only its own GitHub release line so builds never overwrite one another:

| Build | GitHub release tag | APK assets (with `.sha256`) |
|---|---|---|
| Generic Android (Android 8+) | `generic-android-v1.2.3` | `glassworks-generic-android-arm64-v8a-1.2.3.apk`, `glassworks-generic-android-armeabi-v7a-1.2.3.apk` |
| Legacy build (Android 6–7) | `generic-android-legacy-v1.2.3` | `glassworks-generic-android-legacy-arm64-v8a-1.2.3.apk`, `…-armeabi-v7a-…` |
| Aqara S1 Plus | `aqara-s1-plus-v1.2.3` or `v1.2.3` | `s1-kiosk-1.2.3.apk`, `s1-kiosk-arm64-v8a-1.2.3.apk` |

You can opt into pre-releases by changing **Update channel** from **Stable** to **Beta** on the panel's Device settings page or the [Manage page](../../docs/manage.md). Switching back to Stable waits for a newer stable release rather than downgrading.

Without a root update daemon, Generic Android installs updates via Android's `PackageInstaller`. Three paths allow hands-free updates without tapping the screen:

| Method | Android version | One-time setup |
|---|---|---|
| **Silent self-update** | Android 12+ | Allow **Install unknown apps** for Glassworks |
| **Device owner** | Android 8.0+ | Run `dpm set-device-owner` over adb on a device with no accounts |
| **Privileged system app** | Android 6.0+ | Automatic when Kiln flashes the legacy build into `/system/priv-app` (lululemon Mirror) |

Without one of those enabled, Android prompts for a confirmation tap on the screen. Check Home Assistant's **Silent updates** sensor (or the panel's Device page) to see your current status ([esphome.md](../../docs/esphome.md#silent-updates)).

*(Note: The adb commands below use `dev.glassworks.generic_android`. If you are running the S1 APK on a tablet, substitute `sh.edm.s1kiosk/.AdminReceiver` and `sh.edm.s1kiosk/.improv.ImprovActivity`.)*

### Silent self-update (Android 12+)

On Android 12 and newer, Glassworks requests silent self-updates automatically when:

1. **Install unknown apps** is enabled for Glassworks (*Settings → Apps → Glassworks → Install unknown apps*, or `adb shell appops set dev.glassworks.generic_android REQUEST_INSTALL_PACKAGES allow`). Kiln sets this automatically during USB setup unless you opt out.
2. The new APK targets a recent enough Android API level for the device's OS version (Android requires `targetSdk` ≥ 29 on Android 12, 30 on 13, 31 on 14, 33 on 15, 34 on 16, and 35 on 17). Glassworks currently targets API 35, covering everything through Android 17 ([releasing.md](../../docs/releasing.md#raising-targetsdk)).

### Device owner

Making Glassworks the Android **device owner** enables silent updates on any Android version from 8.0 onward and grants tighter kiosk control. Android only allows setting a device owner when the device has **no Google or user accounts** and a single user profile (for example, right after a factory reset):

```sh
adb shell dpm set-device-owner dev.glassworks.generic_android/dev.glassworks.AdminReceiver
adb shell dumpsys device_policy | grep -A3 "Device Owner"

# To undo later (dpm remove-active-admin does not work on release builds):
adb shell am start -n dev.glassworks.generic_android/dev.glassworks.provisioning.ImprovActivity --ez release_device_owner true
```

While active, Android Settings notes the device is managed and prevents uninstalling Glassworks until you release device owner using the command above. See [docs/onboarding.md](../../docs/onboarding.md#device-owner-optional) for additional notes.

### The legacy build's id

All Android 6–7 devices—including the lululemon Studio Mirror—share the single package ID `dev.glassworks.generic_android.legacy`. Keeping one unified legacy release line avoids duplicating releases and bug fixes while letting Kiln's Mirror recipe verify the package ID before flashing into `/system/priv-app` (where the package ID is permanent and privileged permissions are fixed at install time). Every legacy build declares `INSTALL_PACKAGES`, `REBOOT`, and `WRITE_SECURE_SETTINGS`, verified by `GenericAppTest`.

## Signing

Generic Android uses its own dedicated release signing key (`CN=Glassworks, O=GlassworksHQ`, RSA 4096, SHA-256 `564d1367c4eafeac81e80b5b51d4db090a5df9b615d5aa46a83c4c33d5c67caa`), shared by both `dev.glassworks.generic_android` and `.legacy` and kept separate from the S1 Plus release key. Both official releases ([release.yml](../../.github/workflows/release.yml)) and signed test builds ([generic-test-build.yml](../../.github/workflows/generic-test-build.yml)) sign with this key, and CI verifies the certificate fingerprint against [`tools/release/generic-release-cert.sha256`](../../tools/release/generic-release-cert.sha256) so updates always install cleanly in place.

Local release signing reads environment variables (if none are set, Gradle builds an unsigned APK):

| Environment variable | Purpose |
|---|---|
| `GENERIC_KEYSTORE_FILE` | Path to the PKCS12 keystore |
| `GENERIC_KEYSTORE_PASSWORD`, `GENERIC_KEY_ALIAS`, `GENERIC_KEY_PASSWORD` | Keystore password, alias (`glassworks`), and key password (must match for PKCS12) |
| `GENERIC_REQUIRE_RELEASE_SIGNING=true` | Fail the build instead of producing an unsigned APK if credentials are missing |

## Panes

| Pane | Support | Notes |
|---|---|---|
| Assist | Depends on device | Requires a microphone |
| Timers | Depends on device | Requires Assist |
| Sendspin | Depends on device | Requires a speaker |
| Zigbee / Thread radio | Not supported | No supported built-in radio |
| Time zone | Not supported | Aqara S1 Plus only |
| Diagnostics | Stable | Included in both builds (no CPU temperature sensor without a frame-defined thermal zone) |
| Bluetooth proxy | Depends on device | Requires Bluetooth LE; included in both builds |
| Notifications | Stable | Included in both builds |
| Camera | Depends on device | Requires a camera; included in both builds |
| Wall base | Not supported | Aqara S1 Plus only |

Panes without an explicit override in `frame.json` derive their compatibility automatically from device hardware capabilities ([how it works](../README.md#the-panes-a-frame-runs)).

## Tests

App-specific unit tests live in `GenericAppTest` (`:generic-android`). Across the broader core, CI runs `CoreConformanceTest` on Android emulators at API 23 (legacy), 26, 29, and 34 ([core-emulators.yml](../../.github/workflows/core-emulators.yml)).

## Research notes

- [kiln-install.md](research/kiln-install.md): ADB command compatibility across Android versions and pre-debugging USB brand detection
- [screen-lock.md](research/screen-lock.md): Detecting PIN, pattern, or password locks over adb without root

## Files

- [`design/frame.json`](design/frame.json): Catalog data and 3D model definition
- [`android/`](android): Gradle app module (`:generic-android`)
- [`installer/`](installer): Kiln WebUSB installer recipe
- [`budgets.json`](budgets.json): Per-ABI and per-pane APK size budgets ([docs/size.md](../../docs/size.md#budgets))
