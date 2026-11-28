---
title: "Why build on Glassworks: Home Assistant panels without reinventing the stack"
date: 2026-12-08
author: "Eduardo"
summary: "How Glassworks compares to Fully Kiosk, Kiosk Satellite, the Companion App, and ESPHome LVGL—and why hardware hackers and makers shouldn't have to rebuild kiosk plumbing for every new wall panel."
draft: true
---

<!--
Draft. Planned for Week 2 (after Glassworks 1.0 and Introducing Clarity are both live).
Update any bracketed launch-week text or new community frames before setting `draft: false`.
-->

[Since sharing [Glassworks 1.0](https://www.glassworks.dev/blog/glassworks-1-0) and [Clarity](https://www.glassworks.dev/blog/clarity), two questions have come up over and over:]

1. **For Home Assistant users:** *"Why should I use Glassworks over Fully Kiosk, Kiosk Satellite, the Companion App, or ESPHome LVGL—and when shouldn't I?"*
2. **For hardware hackers, image maintainers, and makers:** *"If I'm bringing up a new wall switch, smart mirror, or custom display, why should I build on Glassworks instead of shipping my own app or bare ROM?"*

Those are both fair questions—and answering them honestly is the clearest way to explain what Glassworks is trying to be (and what it isn't).

## Why use Glassworks

- **Your own Home Assistant dashboard, zero HA plugins.** Glassworks renders the dashboard you already built full screen and joins Home Assistant as a single native ESPHome device. Nothing to install from HACS, no cloud service, and no account with us.
- **True open source (MPL-2.0 core + MIT libraries).** You can inspect, fork, port new devices, ship commercial hardware on top of it, or drop our standalone MIT protocol libraries into your own project without worrying about paywalls or non-commercial restrictions.
- **Built for real wall hardware, not just modern tablets.** [Kiln](https://kiln.glassworks.dev) unlocks and flashes supported hardware straight from Chrome or Edge over USB, backs up stock firmware first where the bootloader allows it, and bundles a modern GeckoView web engine so older panels (whose stock WebView can't load Home Assistant) still work.
- **Modular hardware features that stay out of your way.** Local Assist wake word and timers, Sendspin synced multi-room audio, built-in Zigbee/Thread radio modes, lighting-invariant camera presence, Bluetooth proxy, and rich notifications are all modular panes—optional ones stay off until you turn them on so they never burn RAM or CPU.
- **Honest performance expectations.** Measured frames publish a public [Clarity](../../../docs/clarity.md) grade (A+ to E) on the real Home Assistant frontend—and unmeasured ones show clearly as pending—so you know what a panel can handle before you buy or mount it.
- **One shared stack for new devices.** If you're porting a new panel or maintaining a community ROM, you write only the thin device layer instead of rebuilding onboarding, ESPHome, updates, and a WebUSB flasher from scratch.

## Why not to use Glassworks

Glassworks is opinionated on purpose ([docs/principles.md](../../../docs/principles.md)), which means there are plenty of cases where another tool is a better fit:

- **You're using an ESP32 or microcontroller screen.** If you want instant boot, ultra-low power, and hand-crafted widgets on a small embedded display, use **ESPHome LVGL** or **OpenHASP**—Glassworks is for Android screens running the full Home Assistant web frontend.
- **You just want a quick dashboard on an everyday tablet.** If you don't need kiosk lockdown, local Assist wake word, Sendspin audio, or native ESPHome sensors, the official **Home Assistant Companion App** is simpler and already on the Play Store.
- **You want built-in photo-frame screensavers, view cycling, or tablet-to-tablet intercom today.** Glassworks intentionally keeps Core focused on hardware and Home Assistant rather than duplicating those features inside the base app (in **1.2**, [Shards](https://github.com/GlassworksHQ/glassworks/issues/940) will add an opt-in plugin SDK for community screensavers, photo frames, and intercom without bloating Core). If you want all of those baked into one Android tablet app today, **Kiosk Satellite** or **Fully Kiosk Browser** may fit you better.
- **You're running iPads (iOS) or Raspberry Pi / Linux touchscreens.** Glassworks targets Android-based wall panels, mirrors, and tablets today.
- **You want to keep vendor apps and cloud features on locked wall hardware.** On devices like the Aqara S1 Plus or lululemon Studio Mirror, Glassworks replaces the vendor experience. On the S1 Plus, Kiln backs up stock partitions before writing so you can restore stock Aqara firmware anytime. On supported Qualcomm Mirrors (stock 1.x), Kiln offers both the community Glass Panes image (which replaces stock firmware, with rollback to plain Glass Panes) and an experimental **stock-in-place** path that keeps lululemon's stock firmware intact and saves a byte-exact restore point back to a stock factory reset.

## How Glassworks compares to the alternatives

| Option | Best when you want… | Where Glassworks is different |
|---|---|---|
| **Home Assistant Companion App** | The simplest possible setup on a regular Android tablet or phone | No kiosk lockdown, no local Assist wake word, no Sendspin multi-room audio, no built-in Zigbee/Thread radio modes, and no browser ROM installer for locked wall hardware. |
| **Fully Kiosk Browser** | A battle-tested web wrapper on an everyday Android tablet | Closed source, with per-device paid licenses for features like motion detection and remote admin. Relies on the device's system WebView (which breaks on older panels) and doesn't act as a native ESPHome voice/radio/Sendspin device. |
| **Kiosk Satellite** | A feature-packed Android app with built-in photo-frame screensavers, view rotation, and tablet-to-tablet intercom | Kiosk Satellite is a great all-in-one Android app, but it's **source-available (`CC BY-NC-ND 4.0`)**—you can't fork it, ship modified builds, or bundle it on hardware—and it only runs on already-unlocked Android devices. Glassworks is **true open source (MPL-2.0 core + MIT libraries)**, includes a **WebUSB browser installer (Kiln)** for locked vendor hardware, bundles its own modern web engine for old Android panels, exposes hardware radios and wall-base relays, and stays strictly native ESPHome with nothing to install in Home Assistant. |
| **ESPHome LVGL / OpenHASP** | Tiny ESP32 microcontroller displays with instant boot and ultra-low power | You have to design and maintain a separate UI in YAML/JSON instead of using your existing Home Assistant dashboard. Glassworks targets Android screens where you want your real HA dashboard full screen. |
| **Rolling your own ROM or kiosk app** | Complete custom control for a one-off hardware hack | Every device ends up rebuilding Wi-Fi setup, ESPHome integration, OTA updates, camera presence, voice, and an installer—spreading maintenance across a dozen small repos. |

## The wall-panel tax (and why we built a shared core)

If you've ever followed a community thread for unlocking a wall switch, smart mirror, or discontinued smart display, you've seen the same pattern play out:

Someone does heroic reverse-engineering work—tearing down the board, finding UART pads, figuring out bootloaders and kernels, and getting Android or Linux to boot. And then they hit the second mountain:

- How do regular users flash this without installing `platform-tools` and nervously copy-pasting 15 `fastboot` commands?
- How do they get onto Wi-Fi if the screen has no touch (like a smart mirror) or no keyboard?
- How does the panel talk to Home Assistant without asking users to install a custom HACS integration and juggle long-lived tokens?
- What happens when the stock system WebView is from 2018 and refuses to load modern Home Assistant?
- Who maintains OTA updates, wake-word detection, Music Assistant playback, camera presence, and a browser settings page for the next three years?

Most hardware bring-up projects stall right there—not because the hardware hack failed, but because maintaining the software stack around a single device is a full-time job.

Glassworks exists so you never have to pay that tax again.

## What you get out of the box when you build on Glassworks

### 1. Frames as data—write only the thin hardware layer
Every supported device in Glassworks is a self-contained folder under [`frames/<id>/`](../../../frames/README.md): a `design/frame.json` declaring specs and hardware capabilities (`provides`), an optional `design/recovery.json` for hardware button diagrams (only frames with hardware recovery steps need one), an optional Kiln installer recipe, and research notes.

Glassworks Core handles everything else automatically:
- Full-screen Home Assistant dashboard rendering (with an embedded modern GeckoView engine—and a legacy engine path for older Android 6–7 devices)
- Local Wi-Fi onboarding (Improv Bluetooth, setup hotspot, or browser setup)
- Native ESPHome discovery, encrypted API, and automatic update checks under Home Assistant's *Settings → Updates*—tap **Install** in Home Assistant, with hands-free installation on rooted/system-app panels, Android 12+ with "Install unknown apps" allowed, or device-owner setups on Android 8–11
- Consistent native overlays for setup, Assist voice, timers, and notifications
- A local, PIN-protected Manage web UI that works across all your panels

### 2. One browser installer (Kiln) for every frame—and community image support
[Kiln](https://kiln.glassworks.dev) isn't hardcoded to one device. It's a single WebUSB engine driven by per-frame data and recipes: SHA-256 download verification, animated step-by-step recovery guides, automatic stock partition backups and guided restore where the bootloader allows it, and clean ADB/fastboot/FEL orchestration right inside Chrome or Edge.

Even better: **if you already maintain a custom community image for a smart display, mirror, or panel, I'm happy to collaborate so users can flash it cleanly through Kiln.** On the lululemon Studio Mirror, Kiln fetches [Glass Panes](https://github.com/git-nimrod/glass-panes) directly from its maintainer's GitHub release through a pass-through proxy without storing it—with full credit in the installer, docs, and website, plus a clean path back to the plain image anytime. And if your project needs direct hosting for a community image instead of pulling from your own release host, I'm happy to work with you on that too so your users never have to wrestle with `fastboot` in a terminal.

### 3. Modular panes matched by hardware capability
Features in Glassworks live behind the [Pane API](../../../pane-api/src/main/java/dev/glassworks/README.md). A pane declares what hardware it `requires` (microphone, speaker, camera, Bluetooth LE, Silicon Labs radio, etc.), and a frame declares what it `provides`.

- If your new panel has a microphone and speaker, it automatically gets Assist voice, timers, and Sendspin synced music—with zero extra code.
- If your panel has unique hardware (like wired relays or an ambient light sensor), you write one small frame-specific pane—and if it needs privileged root actions on a rooted board, the [frame agent](../../../docs/frame-agent.md) gives you a clean, audited boundary.
- Optional panes stay off until enabled, so low-RAM hardware never pays for features a user doesn't turn on.

### 4. Standalone MIT protocol libraries—even if you don't use Glassworks Core
We didn't lock our protocol code inside the app. Every core protocol client is split out as a standalone **MIT-licensed** library you can drop straight into your own project:

- [`esphome-api`](../../../libs/esphome-api/README.md) (published on Maven Central) & `esphome-api-android`
- [`improv`](../../../libs/improv/README.md) — Improv Wi-Fi provisioning
- [`sendspin`](../../../libs/sendspin/README.md) — Music Assistant synced multi-room audio
- [`bluetooth-proxy`](../../../libs/bluetooth-proxy/README.md) — Home Assistant Bluetooth proxy
- [`voice`](../../../libs/voice/README.md) — local wake-word detection and Assist satellite audio

If you're building a completely different Kotlin or Android app for Home Assistant, take the libraries and run with them.

### 5. MPL-2.0 open core: built for both hackers and hardware makers
We chose **MPL-2.0** for Glassworks Core intentionally. Because MPL-2.0 is file-level copyleft:
- **Community hackers** can fork, port new devices, and improve the core knowing the platform can't suddenly close up behind a paywall.
- **Commercial hardware makers** can ship Glassworks on their own wall panels and keep proprietary device drivers, frames, or panes separate—when distributing binaries, they only need to make the covered Glassworks source files (including any modifications to those files) available to recipients, with no requirement to open their own separate files.

Wall hardware stays mounted for years, usually longer than any single startup or vendor cloud. An open core keeps both users and hardware makers protected.

### 6. Honest benchmarks and permanent research notes
Bring-up knowledge usually dies inside a 600-post forum thread or disappears into Discord scrollback. Every Glassworks frame ships with:
- **[Open hardware research notes](../../../docs/research/README.md)** checked straight into the repo alongside the code—exact commands, redacted logs, dead ends, and confidence tags so the next person can build on what you learned.
- **[Clarity performance grading](../../../docs/clarity.md)** so buyers and contributors see honest, repeatable numbers on how a board handles light, typical, camera, and heavy HA dashboards.
- **Shared test harnesses and update-compatibility checks** so updates keep installing cleanly in place across releases.

## Build a frame—or bring your image to Kiln

If you have a locked-down wall panel sitting on your bench, a community ROM looking for a great Home Assistant app and browser installer, or custom smart-home hardware you want to ship with Glassworks:

- Browse the architecture and libraries on [glassworks.dev/developers](https://www.glassworks.dev/developers)
- Read [Adding a device](../../../frames/README.md), [Pane API](../../../pane-api/src/main/java/dev/glassworks/README.md), and [Glassworks Principles](../../../docs/principles.md)
- Jump into `#hardware-bringup` on [Discord](https://discord.gg/hHpf77fBMj) or start a thread in [GitHub Discussions](https://github.com/GlassworksHQ/glassworks/discussions) ([CONTRIBUTING.md](../../../CONTRIBUTING.md))

Let's stop rebuilding the same kiosk plumbing ten times over and put that energy into unlocking more great hardware.

Eduardo
