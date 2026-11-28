<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-glassworks-on-dark.svg">
      <img alt="Glassworks: the open core for Home Assistant wall panels" src="https://www.glassworks.dev/brand/banner-glassworks-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Glassworks</h1>

<p align="center">
  <b>The open core for Home Assistant wall panels</b><br>
  Turn a wall panel or Android tablet into a local ESPHome device with your full-screen dashboard, voice assistant, music player, sensors, and radios.
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-under-development-on-dark.svg">
    <img alt="Status: under development" src="https://www.glassworks.dev/brand/badge-status-under-development-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
  <a href="https://discord.gg/hHpf77fBMj">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="site/public/brand/badge-discord-on-dark.svg">
      <img alt="Community: Discord" src="site/public/brand/badge-discord-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

Glassworks turns a wall panel or Android tablet into a clean, local Home Assistant device. Home Assistant handles all of your automations and dashboards, while Glassworks hands it the panel's screen, microphones, speaker, sensors, and radios as a single ESPHome device. You install nothing in Home Assistant—no custom integration, no cloud service, and no Glassworks account.

New here? Read the **[Getting started guide](docs/getting-started.md)** or browse the full docs at **[glassworks.dev/docs](https://www.glassworks.dev/docs)**.

## Have an Aqara Panel Hub S1 Plus? Start here

This repository began as **S1 Unleashed**. The Aqara Panel Hub S1 Plus is now Glassworks' reference device, and its dedicated page has everything you need:

- **[Install from your browser with Kiln →](https://kiln.glassworks.dev/aqara-s1-plus)** — Plug in a USB-C cable and use Chrome or Edge. Kiln backs up your panel first, verifies every write, and includes built-in undo.
- **[Aqara S1 Plus device guide](frames/aqara-s1-plus/README.md)** — Features, current status, Zigbee/Thread radio setup, updates, known issues, undo, and FAQ.

Already running Glassworks? New releases continue to appear in Home Assistant under *Settings → Updates*.

Jump to:
<a id="install"></a><a id="what-you-need"></a><a id="the-web-installer-recommended"></a><a id="the-command-line"></a>[Install](frames/aqara-s1-plus/README.md#install) ·
<a id="why"></a><a id="what-you-get"></a>[Features](frames/aqara-s1-plus/README.md#what-you-get) ·
<a id="status"></a>[Status](frames/aqara-s1-plus/README.md#status) ·
<a id="the-radio"></a>[Radio](frames/aqara-s1-plus/README.md#the-radio) ·
<a id="updates"></a>[Updates](frames/aqara-s1-plus/README.md#updates) ·
<a id="known-issues"></a>[Known issues](frames/aqara-s1-plus/README.md#known-issues) ·
<a id="undo-and-safety"></a>[Undo &amp; safety](frames/aqara-s1-plus/README.md#undo-and-safety) ·
<a id="supported-hardware"></a>[Supported hardware](frames/aqara-s1-plus/README.md#supported-hardware) ·
<a id="faq"></a>[FAQ](frames/aqara-s1-plus/README.md#faq)

## How Glassworks is organized

Glassworks is built around three core concepts, guided by our [Principles](docs/principles.md):

- **Frames** describe individual hardware devices—their sensors, screen sizes, and install recipes.
- **Panes** are modular features (like voice, music, notifications, or a radio) that run on any frame with the required hardware.
- **Kiln** is the browser-based WebUSB installer that backs up, installs, and restores panels without requiring command-line tools.

## Supported frames

| Frame | Status | Overview |
|---|---|---|
| [Aqara S1 Plus](frames/aqara-s1-plus/README.md) | Stable | Rooted Allwinner A133 wall panel and Glassworks' reference device. Runs every pane, including Zigbee/Thread radio and wall base relays. |
| [Generic Android](frames/generic-android/README.md) | Stable | Standard Android app for Android 8+ tablets and phones (plus a legacy build for Android 6–7), with no root required. Includes tested devices like the [Google Pixel Tablet](frames/google-pixel-tablet/README.md) and the [Samsung Galaxy Tab A 10.1 (2016)](frames/samsung-galaxy-tab-a-10-1-2016/README.md). |
| [lululemon Studio Mirror](frames/lululemon-mirror/README.md) | Stable | 40-inch smart mirror running Android behind the glass. |

Each device definition lives in `frames/<id>/design/frame.json` (see [the frame format](frames/README.md)) and is showcased with interactive 3D models at [glassworks.dev/frames](https://www.glassworks.dev/frames).

## Available panes

Panes are optional features that a panel can enable or turn off at any time:

- **Assist** — Home Assistant voice assistant with an on-device wake word ([guide](docs/voice-and-timers.md)).
- **Timers** — On-screen countdown orbs and completion chimes for Home Assistant voice timers ([guide](docs/voice-and-timers.md#voice-timers)).
- **Sendspin** — Native Music Assistant player that plays in sync with your other Sendspin speakers ([guide](docs/sendspin.md)).
- **Notifications** — Rich Home Assistant alerts over your dashboard with camera snapshots, action buttons, chimes, and full-screen or half-screen views ([guide](docs/notifications.md)).
- **Radio** — Exposes the Aqara S1 Plus Zigbee/Thread radio as a Zigbee coordinator, Thread border router, or network repeater ([guide](frames/aqara-s1-plus/docs/radio-guide.md)).
- **Wall base** — Direct local control of the Aqara S1 Plus mains relay base ([entities](docs/esphome.md#wall-base-entities)).
- **Bluetooth proxy** — Forwards nearby BLE sensors to Home Assistant, with optional active GATT connections ([details](docs/esphome.md#bluetooth-proxy)).
- **Camera** — On-device motion presence, Home Assistant snapshots, and an opt-in ONVIF/RTSP stream on panels with a camera ([entities](docs/esphome.md#camera-entities)).
- **Time zone** — Preserves the panel's time zone across reboots as an ESPHome entity.
- **Diagnostics** — Reports CPU, temperature, memory, storage, Wi-Fi signal, and uptime to Home Assistant ([details](docs/esphome.md#diagnostics)).

Several features also include ready-made Home Assistant [blueprints](docs/blueprints.md) that you can import directly through Home Assistant's built-in blueprint dialog.

Each frame's README lists which panes it supports. If you want to build a new pane, read the [Pane API](pane-api/src/main/java/dev/glassworks/README.md) and [What makes a pane](pane-api/src/main/java/dev/glassworks/README.md#what-makes-a-pane).

## Kiln installer

Kiln installs Glassworks straight from Chrome or Edge over WebUSB, backing up original partitions first wherever the device allows it and giving every install a built-in way back. On the lululemon Studio Mirror, whose bootloader can't read lululemon's software back, the Glassworks image's way back is the community Glass Panes image, and the experimental install that keeps lululemon's system saves a restore point that puts it back ([details](docs/updates.md#lululemon-studio-mirror)). Launch it at [kiln.glassworks.dev](https://kiln.glassworks.dev), or browse its code and architecture in [`kiln/`](kiln/apps/kiln/README.md). If you prefer working from a terminal, the Aqara S1 Plus also includes a [command-line installer](frames/aqara-s1-plus/README.md#the-command-line).

## Clarity performance grades

**Clarity** is our standardized performance benchmark for wall panels ([docs/perf.md](docs/perf.md)). It loads real Home Assistant dashboards of increasing complexity on a device and assigns a letter grade from **A+** to **E**, showing the largest dashboard size where taps, scrolling, live updates, dialogs, and cameras still feel responsive.

## Documentation

- **[Getting started](docs/getting-started.md)** — Quickstart guide from installation to your first dashboard.
- **[Full docs index](docs/README.md)** — User guides, device instructions, developer references, and research notes.
- **[Glassworks Principles](docs/principles.md)** — What Glassworks builds, what it avoids, and the design rules behind it.
- **[How we got here](docs/research/README.md)** — Engineering and reverse-engineering notes, from the first UART boot log to the browser installer.
- **[CHANGELOG.md](CHANGELOG.md)** — Release history and user-facing changes.

## Community & contributing

<p align="center">
  <a href="https://discord.gg/hHpf77fBMj">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="site/public/brand/card-discord-on-dark.svg">
      <img alt="Join the Glassworks Discord community" src="site/public/brand/card-discord-on-light.svg" width="580">
    </picture>
  </a>
</p>

Read [CONTRIBUTING.md](CONTRIBUTING.md) and [Glassworks Principles](docs/principles.md) before proposing a feature. Chat with the community on [Discord](https://discord.gg/hHpf77fBMj), share questions, ideas, and new device bring-up threads in [Discussions](https://github.com/GlassworksHQ/glassworks/discussions), and file bugs or hardware reports as GitHub issues. Report security vulnerabilities privately following [SECURITY.md](SECURITY.md). For other private inquiries, email [hi@glassworks.dev](mailto:hi@glassworks.dev). Everyone participating in this project follows the [code of conduct](CODE_OF_CONDUCT.md).

## Repository layout

| Path | Description |
|---|---|
| [`kiln/`](kiln/apps/kiln/README.md) | Kiln, the browser-based WebUSB installer |
| [`frames/`](frames/README.md) | Per-device definitions: README, Kiln recipe (`installer/`), design data (`design/frame.json`), and device research |
| [`packages/`](packages/frame-card/README.md) | Shared React and TypeScript packages used by Kiln and glassworks.dev |
| [`site/`](site/README.md) | [glassworks.dev](https://www.glassworks.dev) website, docs portal, and brand asset generator |
| [`frames/aqara-s1-plus/android`](frames/aqara-s1-plus/android/README.md) | Aqara S1 Plus Android kiosk app built on Glassworks Core (`dev.glassworks`) |
| [`frames/generic-android/android`](frames/generic-android/README.md) | Glassworks for Generic Android (`dev.glassworks.generic_android`) and its Android 6–7 legacy build |
| [`frames/aqara-s1-plus/tools/`](frames/aqara-s1-plus/tools/AGENTS.md) | S1 Plus command-line installer, U-Boot/FEL tooling, boot patch scripts, and radio helper |
| `tools/` | Shared Clarity benchmark suite, release scripts, APK size checks, and doc validators |
| [`branding/`](branding/glass-tile/README.md) | Boot splash, boot animation, and social preview assets |
| [`docs/`](docs/README.md) | User guides, developer documentation, and [hardware research](docs/research/README.md) |
| [`CHANGELOG.md`](CHANGELOG.md) | Release notes and change history |

## Credits and licenses

Built by [@aguilaair](https://github.com/aguilaair), standing on the open-source work of [linux-sunxi](https://linux-sunxi.org/) (sunxi-tools, U-Boot), [Magisk](https://github.com/topjohnwu/Magisk), [Home Assistant](https://www.home-assistant.io/), [ESPHome](https://esphome.io/), [Music Assistant](https://music-assistant.io/), [OpenThread](https://openthread.io/), [microWakeWord](https://github.com/kahrendt/microWakeWord), [openWakeWord](https://github.com/dscripka/openWakeWord), and [Tango / ya-webadb](https://github.com/yume-chan/ya-webadb).

Code in this repository is licensed under **MIT** ([LICENSE](LICENSE)), except for Glassworks Core (`core/`, `pane-api/`, `engines/` and `panes/`), which is licensed under **MPL-2.0** ([details](pane-api/src/main/java/dev/glassworks/README.md#licence)). The libraries in `libs/`, such as `dev.glassworks.esphome` and `dev.glassworks.improv`, and the apps built on the core are MIT. Third-party dependencies keep their original licenses; see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for the complete list with versions and sources, or open *Open source licenses* in the panel's hidden settings menu (tap the top-left corner five times).

| Component | License | How it is used |
|---|---|---|
| U-Boot (with Allwinner A133 patches) | GPL-2.0-or-later | Built from source in [`frames/aqara-s1-plus/tools/fel/`](frames/aqara-s1-plus/tools/fel); runs from RAM only |
| Magisk v31.1 | GPL-3.0 | Official release APK pinned by SHA-256 (downloaded by the CLI or served by Kiln); [source](https://github.com/topjohnwu/Magisk/tree/v31.1) |
| ot-br-posix (`otbr-agent`, `ot-ctl`) | BSD-3-Clause | Built by [`frames/aqara-s1-plus/tools/radio/`](frames/aqara-s1-plus/tools/radio) |
| cJSON, cpp-httplib (in `otbr-agent`) | MIT | Bundled by ot-br-posix's REST server |
| Silicon Labs `libcpc` and CPC interface | Silicon Labs MSLA, Zlib | Built from Silicon Labs' public source into `otbr-agent` and the radio bundle |
| GNU C library, GCC runtime | LGPL-2.1-or-later, GPL-3.0 with GCC exception | Unmodified runtime libraries from Ubuntu 22.04 for the radio tools |
| Simple Keyboard | Apache-2.0 | Optional on-screen keyboard |
| GeckoView | MPL-2.0 | Embedded browser engine dependency |
| LiteRT (TensorFlow Lite), Media3 ExoPlayer, ZXing | Apache-2.0 | Wake word inference, audio playback, and QR codes |
| TFLite Micro audio frontend, kissfft | Apache-2.0, BSD-3-Clause | Ported to Kotlin for wake word audio feature extraction |
| microWakeWord models | Apache-2.0 | **Not shipped.** Downloaded on demand by the panel |
| Bouncy Castle | MIT-style | ESPHome API Noise encryption |
| Tango (ya-webadb) | MIT | ADB over WebUSB in Kiln |
| Inter | SIL OFL 1.1 | Boot branding typeface, fetched at build time |
| openWakeWord models (legacy fallback) | **CC BY-NC-SA 4.0** | **Not shipped.** Used only if a legacy wake word is selected; non-commercial |

For scope, trademark, and firmware policies, see [docs/legal.md](docs/legal.md).

**Not affiliated with Aqara, Lumi United, or lululemon.** Aqara, lululemon, and their product names are trademarks of their respective owners and are used here solely to identify compatible hardware. No Aqara software is included in this repository.
