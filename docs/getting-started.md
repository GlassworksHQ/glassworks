# Getting started with Glassworks

Glassworks turns a wall panel or Android tablet into a dedicated, local Home Assistant device. Once installed, your panel boots straight into your Home Assistant dashboard full screen and appears in Home Assistant as a single ESPHome device—giving you screen controls, sensors, voice control, timers, synced Music Assistant playback, notifications, and (on supported hardware) a Zigbee or Thread radio.

Everything runs on your local network. There is no custom Home Assistant integration to install, no cloud service, and no Glassworks account to create.

## Supported devices

Glassworks supports dedicated wall panels as well as standard Android tablets:

| Device | Support | How it installs |
|---|---|---|
| [Aqara Panel Hub S1 Plus](../frames/aqara-s1-plus/README.md) | Stable | Rooted install from your browser with Kiln over USB-C. Supports every feature, including the built-in Zigbee/Thread radio, presence sensor, and wall base relays. |
| [Generic Android](../frames/generic-android/README.md) | Stable | Installs as a regular Android app from Kiln or APK (Android 8+, plus a legacy build for Android 6–7). No root needed. Runs every feature your device's hardware supports, including tested tablets like the [Google Pixel Tablet](../frames/google-pixel-tablet/README.md) and the [Samsung Galaxy Tab A 10.1 (2016)](../frames/samsung-galaxy-tab-a-10-1-2016/README.md). |
| [lululemon Studio Mirror](../frames/lululemon-mirror/README.md) | Stable | Installs the legacy Android build via Kiln onto the 40-inch mirror display over fastboot. |

You can also browse all supported and planned hardware with interactive 3D previews at [glassworks.dev/frames](https://www.glassworks.dev/frames).

## What you need

- A **supported panel or Android device**
- A **USB data cable** plugged directly into your computer (avoid USB hubs and charge-only cables)
- **Google Chrome or Microsoft Edge** on Windows, macOS, or Linux (Kiln uses WebUSB, which Firefox and Safari do not support)
- **Home Assistant** on the same local network (**2026.11 or newer** is recommended so Home Assistant sets up ESPHome encryption automatically)
- About **10 minutes**

If you are installing on an Aqara S1 Plus, you will also need a small screwdriver to open the back cover once and reach the recovery button on the circuit board.

## 1. Install Glassworks with Kiln

**[Kiln](https://kiln.glassworks.dev)** is the Glassworks web installer. It runs entirely inside your browser—nothing is uploaded to a server—and walks you through the installation step by step.

1. Open [kiln.glassworks.dev](https://kiln.glassworks.dev) in Chrome or Edge and choose your device.
2. Follow the on-screen instructions to connect your panel over USB.
   - On the **Aqara S1 Plus**, Kiln first boots a temporary installer in RAM, backs up your panel's original partitions, patches the boot image, and verifies every write by reading it back. See the [Aqara S1 Plus install guide](../frames/aqara-s1-plus/docs/install.md) for OS-specific USB setup and troubleshooting.
   - On **Generic Android**, Kiln installs the app over USB debugging, grants the permissions it needs, and sets it as your home screen. See the [Generic Android guide](../frames/generic-android/README.md#install) for details.
3. Complete the setup prompts in Kiln to configure your language, time zone, Wi-Fi, Home Assistant sign-in, and (on the S1 Plus) your radio role.
4. **Download and save your backup** on the final screen if Kiln created one. On the Aqara S1 Plus, the `.zip` archive from your first install is the only copy of your original Aqara software and lets you undo the install at any time.

## 2. Finish first-boot setup

If you skipped Wi-Fi or Home Assistant sign-in in Kiln—or installed the app directly on an Android tablet—Glassworks opens a guided setup screen on the panel itself.

### Connect to Wi-Fi

You can connect the panel to Wi-Fi in any of three ways:

- **During Kiln setup** over USB (the fastest option).
- **Over Bluetooth (Improv)** from Home Assistant or a phone, after approving the prompt on the panel's screen.
- **From your phone using the panel's setup Wi-Fi.** Tap **Set up from a phone** (or wait 3 minutes with no network connection), scan the QR code on the panel to join its temporary Wi-Fi network, and open the setup address (`http://<panel>:8099/setup`) to enter your home Wi-Fi credentials.

### Sign in to Home Assistant

Rather than typing a password on a wall screen, Glassworks signs in with a QR code:

1. Confirm the Home Assistant address found on your network (or enter it manually).
2. Scan the QR code on the panel with your phone and sign in to Home Assistant as usual.
3. The panel receives its dashboard sign-in automatically and moves to the next step.

You can also complete setup from a laptop on the same network at `http://<panel>:8099/setup` by pairing with a 6-digit code shown on the panel. See [Set up over the network](manage.md#set-up-over-the-network) for details.

### Adopt the panel in Home Assistant

Once the panel is on your network, Home Assistant discovers it automatically over mDNS.

1. Open Home Assistant and go to **Settings → Devices & services**.
2. Look for the discovered **ESPHome** device (for example, **Aqara S1 Plus** or **Pixel Tablet**) and click **Add**.
3. On Home Assistant 2026.11 and newer, encryption is provisioned automatically—you never need to copy or manage an encryption key.

If your panel is on a separate VLAN where mDNS does not reach Home Assistant, click **Add integration → ESPHome**, enter the panel's IP address, and use port `6053`.

## 3. Pick your dashboard

By default, Glassworks loads your default Home Assistant dashboard and hides the Home Assistant header and sidebar so the view fills the screen cleanly.

To display a specific dashboard:

- **During setup:** After you sign in to Home Assistant in Kiln or on the panel's `/setup` page, pick a dashboard from the **Dashboard** dropdown whenever your Home Assistant has custom dashboards.
- **In Home Assistant:** Open the panel's ESPHome device page and use the **Dashboard** select entity (enabled by default). For an arbitrary URL, expand the disabled entities and enable **Dashboard URL**.
- **On the panel or Manage page:** Open the hidden settings menu on the panel by **tapping the top-left corner five times**, or open the [Manage page](manage.md) at `http://<panel>:8099/` from your browser, go to **Home Assistant & dashboard**, and choose **Dashboard**.

## Where to go next

Once your dashboard is up and running, explore what else your panel can do:

- **[The Manage page](manage.md)** — Check panel health, adjust settings, view logs, and manage multiple panels from your browser.
- **[Voice and timers](voice-and-timers.md)** — Set up on-device wake words for Home Assistant Assist, on-screen voice timers, and spoken announcements.
- **[Music Assistant (Sendspin)](sendspin.md)** — Stream synchronized multi-room audio straight to your panel from Music Assistant.
- **[Notifications](notifications.md)** — Display rich alerts with camera snapshots, action buttons, chimes, or full-screen doorbell views.
- **[ESPHome entities](esphome.md)** — Browse every sensor, switch, and control your panel exposes to Home Assistant.
- **[Blueprints](blueprints.md)** — Import ready-made Home Assistant automations for night mode, doorbells, interactive prompts, and motion lighting.
- **[Using the Zigbee/Thread radio](../frames/aqara-s1-plus/docs/radio-guide.md)** — Turn the Aqara S1 Plus radio into a ZHA coordinator, Zigbee2MQTT coordinator, Thread border router, or network repeater.
- **[Updates and restoring stock software](updates.md)** — Keep Glassworks updated through Home Assistant and learn how to revert to stock firmware.
- **[Clarity performance grades](clarity.md)** — Understand what each Clarity grade means and how devices compare on real Home Assistant dashboards.
