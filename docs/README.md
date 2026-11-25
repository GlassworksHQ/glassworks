# Glassworks documentation

New to Glassworks? Start with **[Getting started](getting-started.md)** to install Glassworks on your panel and connect it to Home Assistant.

## User guide

Guides for setting up and using Glassworks every day on any supported panel.

| Guide | What it covers |
|---|---|
| [Getting started](getting-started.md) | What Glassworks is, supported devices, installing with Kiln, first boot, and picking your dashboard |
| [The Manage page](manage.md) | Managing your panel from a browser at `http://<panel>:8099/`: settings, logs, diagnostics, network setup, and managing multiple panels |
| [Voice and timers](voice-and-timers.md) | Using Home Assistant Assist, on-device wake words, on-screen voice timers, announcements, and troubleshooting |
| [Music Assistant (Sendspin)](sendspin.md) | Setting up the native synchronized Music Assistant player |
| [Notifications](notifications.md) | Sending alerts from Home Assistant with camera pictures, action buttons, sounds, Do not disturb, and full-screen or half-screen views |
| [ESPHome entities](esphome.md) | Complete reference for every sensor, switch, control, and action your panel exposes to Home Assistant |
| [Blueprints](blueprints.md) | Ready-made Home Assistant automations for night mode, doorbells, interactive questions, and motion-triggered lights |
| [Updates and restoring stock software](updates.md) | Updating Glassworks from Home Assistant, silent updates, beta channels, and returning to stock firmware |
| [Clarity performance grades](clarity.md) | What Clarity A+ to E grades mean, what gets tested, and how to read a device's grade |
| [Try Vitro](vitro.md) | The experimental native dashboard renderer: turning it on in Labs, what works, and reporting a card that looks wrong |

## Devices

Device-specific setup instructions, hardware details, and supported features live in each device's folder under `frames/`.

| Device / guide | What it covers |
|---|---|
| [Aqara Panel Hub S1 Plus](../frames/aqara-s1-plus/README.md) | Overview, supported hardware, features, status, known issues, and FAQ for the reference frame |
| [S1 Plus install guide](../frames/aqara-s1-plus/docs/install.md) | Step-by-step Kiln web installer walkthrough, OS setup, command-line install, undo, and troubleshooting |
| [S1 Plus Zigbee/Thread radio guide](../frames/aqara-s1-plus/docs/radio-guide.md) | Using the built-in radio with ZHA, Zigbee2MQTT, Thread border router, and repeater modes |
| [S1 Plus sensors](../frames/aqara-s1-plus/docs/sensors.md) | Presence (time-of-flight) and ambient light sensors, wake on approach, and auto-brightness |
| [Generic Android](../frames/generic-android/README.md) | Running Glassworks on Android 8+ tablets and phones (plus the legacy build for Android 6–7) |
| [Google Pixel Tablet](../frames/google-pixel-tablet/README.md) | Tested device profile, dock sensor behavior, and Clarity results |
| [Samsung Galaxy Tab A 10.1 (2016)](../frames/samsung-galaxy-tab-a-10-1-2016/README.md) | Tested device profile: tested by the community on LineageOS |
| [lululemon Studio Mirror](../frames/lululemon-mirror/README.md) | Hardware details and Kiln installation for the 40-inch mirror |

## Developers and hardware makers

Architecture, design philosophy, pane and frame development, standalone libraries, security, and project operations.

| Guide | What it covers |
|---|---|
| [Glassworks Principles](principles.md) | Design philosophy, core rules, and what Glassworks deliberately does not build |
| [Pane API & what makes a pane](../pane-api/src/main/java/dev/glassworks/README.md) | Writing a pane plugin and the criteria for new features |
| [Writing a frame](../frames/README.md) | Defining a new device (`frame.json`, 3D models, recovery animations, and Kiln recipes) |
| [The frame agent](frame-agent.md) | Contract between Glassworks Core and a rooted frame's privileged helper service |
| [S1 Plus radio technical reference](../frames/aqara-s1-plus/docs/radio.md) | Deep-dive technical reference for radio modes, firmware, firewall rules, and the root helper |
| [Clarity benchmark spec](perf.md) | Full Clarity benchmark specification, scoring rubric, and `tools/perf/run.sh` runner reference |
| [Kiln web installer](../kiln/apps/kiln/README.md) | Architecture and development guide for the WebUSB installer |
| [Kiln design prototypes](design/kiln/README.md) | Screen prototypes and data-driven recovery-mode animations |
| [Adding a community image to Kiln](kiln-images.md) | Recipe format, safety steps, review process, and `?image=` links |
| [Panel secrets and security](secrets.md) | How credentials and keys are sealed with the Android Keystore, and threat-model boundaries |
| [APK size budgets](size.md) | Per-frame and per-pane APK size tracking and CI budgets |
| [CI workflows](ci.md) | Which CI jobs run for each change, required checks, and adding new jobs |
| [Releasing](releasing.md) | Maintainer guide for signing keys, GitHub secrets, tagging releases, and bundles |
| [Branding](branding.md) | Official Glassworks logos, badges, banners, and usage rules |
| [Legal notes](legal.md) | Scope, trademarks, licensing, and [third-party notices](../THIRD-PARTY-NOTICES.md) |
| [Changelog](../CHANGELOG.md) | User-visible changes in each release |

The Android app internals are documented in [frames/aqara-s1-plus/android/README.md](../frames/aqara-s1-plus/android/README.md), and the boot splash and animation generator live in [branding/glass-tile/README.md](../branding/glass-tile/README.md).

## Hardware research

Reverse-engineering notes, hardware bring-up logs, protocol captures, and design studies—including dead ends and dated measurements.

- **[How we got here](research/README.md)** — The full engineering index, covering hardware teardowns, boot logs, FEL recovery and DRAM fixes, Magisk root, de-bloating, wall base UART protocols, and bring-up playbooks.
- **[Onboarding design notes](onboarding.md)** — Technical design and protocol notes for first-boot Wi-Fi (Improv), automatic ESPHome encryption, QR sign-in, and the dashboard picker.

## Images

`img/` stores the screenshots and diagrams used across these guides, including `fel-button.jpg` (also referenced by Kiln—keep its path stable), `kiln/` (web installer screens), and `panel/` (on-device screens).
