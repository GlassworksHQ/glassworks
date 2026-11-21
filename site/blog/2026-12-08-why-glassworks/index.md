---
title: "Why build on Glassworks: Home Assistant panels without reinventing the stack"
date: 2026-12-08
author: "Eduardo"
summary: "How Glassworks compares to Fully Kiosk, Kiosk Satellite, the Companion App, and ESPHome LVGL—and why hardware hackers and makers shouldn't have to rebuild kiosk plumbing for every new wall panel."
draft: true
---

<!--
Draft. Planned for Week 2 (after Glassworks 1.0 and Introducing Clarity are both live).
Update any launch-week questions or new community frames before setting `draft: false`.
-->

Since sharing [Glassworks 1.0](https://www.glassworks.dev/blog/glassworks-1-0) and [Clarity](https://www.glassworks.dev/blog/clarity), two questions have come up over and over:

1. **For Home Assistant users:** *"How is Glassworks different from Fully Kiosk, Kiosk Satellite, the Companion App, or ESPHome LVGL?"*
2. **For hardware hackers, image maintainers, and makers:** *"If I'm bringing up a new wall switch, smart mirror, or custom display, why should I build on Glassworks instead of shipping my own app or bare ROM?"*

Those are both fair questions—and answering them honestly is the clearest way to explain what Glassworks is trying to be (and what it isn't).

## How Glassworks compares to the alternatives

There are genuinely great projects in the Home Assistant wall-panel space, and Glassworks isn't trying to replace every single one of them. Different tools shine on different hardware:

| Option | Best when you want… | Where Glassworks is different |
|---|---|---|
| **Home Assistant Companion App** | The simplest possible setup on a regular Android tablet or phone | No kiosk lockdown, no local Assist wake word, no Sendspin multi-room audio, no built-in Zigbee/Thread radio modes, and no browser ROM installer for locked wall hardware. |
| **Fully Kiosk Browser** | A battle-tested web wrapper on an everyday Android tablet | Closed source, with per-device paid licenses for features like motion detection and remote admin. Relies on the device's system WebView (which breaks on older panels) and doesn't act as a native ESPHome voice/radio/Sendspin device. |
| **Kiosk Satellite** | A feature-packed Android app with built-in photo-frame screensavers, view rotation, and tablet-to-tablet intercom | Kiosk Satellite is a great all-in-one Android app, but it's **source-available (`CC BY-NC-ND 4.0`)**—you can't fork it, ship modified builds, or bundle it on hardware—and it only runs on already-unlocked Android devices. Glassworks is **true open source (MPL-2.0 core + MIT libraries)**, includes a **WebUSB browser installer (Kiln)** for locked vendor hardware, bundles its own modern web engine for old Android panels, exposes hardware radios and wall-base relays, and stays strictly native ESPHome with nothing to install in Home Assistant. |
| **ESPHome LVGL / OpenHASP** | Tiny ESP32 microcontroller displays with instant boot and ultra-low power | You have to design and maintain a separate UI in YAML/JSON instead of using your existing Home Assistant dashboard. Glassworks targets Android/Linux screens where you want your real HA dashboard full screen. |
| **Rolling your own ROM or kiosk app** | Complete custom control for a one-off hardware hack | Every device ends up rebuilding Wi-Fi setup, ESPHome integration, OTA updates, camera presence, voice, and an installer—spreading maintenance across a dozen small repos. |

The short version: **if you want an all-in-one photo frame + intercom app on a regular tablet, Kiosk Satellite or Fully Kiosk might already fit what you want. If you want an open-source, zero-HACS, native ESPHome platform that unlocks real wall hardware from Chrome—and lets you build on or ship it freely—that's Glassworks.**

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
Every supported device in Glassworks is a self-contained folder under [`frames/<id>/`](../../../frames/README.md): a `frame.json` declaring specs and hardware capabilities (`provides`), a `recovery.json` for hardware button diagrams, an optional Kiln installer recipe, and research notes.

Glassworks Core handles everything else automatically:
- Full-screen Home Assistant dashboard rendering (with an embedded modern GeckoView engine—and a legacy engine path for older Android 6–7 devices)
- Local Wi-Fi onboarding (Improv Bluetooth, setup hotspot, or browser setup)
- Native ESPHome discovery, encrypted API, and automatic updates under Home Assistant's *Settings → Updates*
- Consistent native overlays for setup, Assist voice, timers, and notifications
- A local, PIN-protected Manage web UI that works across all your panels

### 2. One browser installer (Kiln) for every frame—and community image hosting
[Kiln](https://kiln.glassworks.dev) isn't hardcoded to one device. It's a single WebUSB engine driven by per-frame data and recipes: SHA-256 download verification, animated step-by-step recovery guides, automatic stock partition backups and guided restore where the bootloader allows it, and clean ADB/fastboot/FEL orchestration right inside Chrome or Edge.

Even better: **if you already maintain a custom community image for a smart display, mirror, or panel, I'm happy to collaborate and host your community image right in Kiln**—just like we did with [Glass Panes](https://github.com/git-nimrod/glass-panes) on the lululemon Studio Mirror, with full credit in the installer, docs, and website. Users get a reliable browser install (and a clean path back to your plain image anytime), and you don't have to build or host a WebUSB flasher yourself.

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
- **Commercial hardware makers** can ship Glassworks on their own wall panels and keep proprietary device drivers, frames, or panes separate—as long as improvements to Glassworks core files themselves are shared back.

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
- Jump into `#hardware-bringup` on [Discord](https://discord.gg/BTn9NstFM) or start a thread in [GitHub Discussions](https://github.com/GlassworksHQ/glassworks/discussions) ([CONTRIBUTING.md](../../../CONTRIBUTING.md))

Let's stop rebuilding the same kiosk plumbing ten times over and put that energy into unlocking more great hardware.

Eduardo
