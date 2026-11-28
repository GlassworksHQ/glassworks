<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-principles-on-dark.svg">
      <img alt="Glassworks Principles" src="https://www.glassworks.dev/brand/banner-principles-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Glassworks Principles</h1>

<p align="center">
  <b>An opinionated, simple Home Assistant panel controller</b><br>
  What Glassworks builds, what it avoids, and the design rules every part follows.
</p>

<p align="center">
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-core-on-dark.svg">
      <img alt="Glassworks Core" src="https://www.glassworks.dev/brand/badge-core-on-light.svg" height="30">
    </picture>
  </a>
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
</p>

---

Glassworks turns a wall panel into a clean, dependable Home Assistant device. Because wall panels easily become bloated when they try to duplicate everything Home Assistant already does, Glassworks is deliberately opinionated. This page outlines what we build, what we leave to Home Assistant, and the rules that guide every component. If a proposal or pull request conflicts with these rules, point to this page—and if a rule itself needs to change, open a `Decide:` issue to update this document before changing the code.

## In one sentence

**Home Assistant is the brain, while the panel provides the screen, microphones, speaker, sensors, and radios on the wall—and Glassworks connects them simply.**

## Principles

1. **Home Assistant is the brain.** Glassworks does not include its own automations, rules, scenes, schedules, or user accounts. Anything that makes decisions about your home belongs in Home Assistant.
2. **ESPHome is the wire.** Every panel presents itself as a native ESPHome device. State, settings, and commands travel over the ESPHome API rather than a custom control channel.
3. **The dashboard is the UI.** The panel displays a Home Assistant dashboard full screen instead of inventing a separate home interface. Glassworks only draws native UI for panel-specific tasks like setup, voice runs, timers, notifications, and calls.
4. **Nothing to install in Home Assistant.** You never need a custom integration, HACS add-on, cloud account, or third-party service. Adopting the discovered ESPHome device is the entire Home Assistant setup.
5. **Open standards out.** Whenever a panel speaks to software outside Home Assistant, it uses an existing open standard such as Sendspin, Matter, or ONVIF rather than a proprietary protocol.
6. **Local only.** Glassworks runs without a cloud backend, telemetry, or phoning home, and continues working even when your internet connection is down.
7. **Off by default, cheap when on.** Features you leave disabled should consume zero resources. Performance and memory budgets are set by the slowest supported hardware—currently the 32-bit Aqara S1 Plus, which shares its RAM with the embedded web engine.
8. **Write it once.** Features are built to work across every compatible frame. Device-specific drivers and workarounds stay isolated inside that device's frame folder.
9. **Never brick, always a way back.** Read before writing, back up and verify every partition change, and provide a reliable undo path for every device modification. Where a device can't give its original software back, the way back is the closest working state it can return to, and we say so before anything is written: the lululemon Studio Mirror's bootloader can't read its partitions back, so its image install's way back is the community Glass Panes image, while its experimental install on the stock software keeps a restore point of the parts it changes ([details](updates.md#lululemon-studio-mirror)). Kiln uses a single installer engine driven by per-frame data ([frames as data](design/kiln/frames-as-data.md)) so every device receives the same safety checks as the S1 Plus.
10. **Simple beats complete.** One feature done thoughtfully is better than ten confusing toggles. When a configuration option only exists because we could not agree on a good default, we make the decision instead of passing it to the user.

## What we're not building

| Out of scope | Why | Use instead |
|---|---|---|
| A kiosk browser with hundreds of knobs | Every extra option adds complexity someone has to learn and maintain | A Home Assistant dashboard plus the small set of panel controls Glassworks needs |
| A custom dashboard designer or home UI | Home Assistant already excels at dashboards | Home Assistant dashboards and community cards |
| An on-panel automation engine | Home Assistant already handles automations | Home Assistant automations, scripts, and blueprints |
| A separate fleet manager that bypasses Home Assistant | Panel settings are ESPHome entities, so Home Assistant can already manage them across rooms | Home Assistant scenes and scripts, or the Manage page (which authenticates through Home Assistant) |
| A standalone voice assistant | Home Assistant Assist handles speech and intent | The panel acting as an Assist satellite |
| A standalone media center | Music Assistant already solves multi-room audio | The panel acting as a Sendspin player |
| A security system or NVR | Security systems require dedicated storage and reliability guarantees | The panel acting as a camera or presence sensor feeding Home Assistant or an NVR |
| A cloud service | Conflicts with Principle 6 (local only) | Local network operation |
| A Home Assistant custom integration | Conflicts with Principle 4 (nothing to install in HA) | Native ESPHome integration |

## The parts

### Core (`dev.glassworks`)

<a href="https://www.glassworks.dev/brand#parts"><picture><source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-core-on-dark.svg"><img alt="Glassworks Core" src="https://www.glassworks.dev/brand/badge-core-on-light.svg" height="30"></picture></a>

Glassworks Core provides the shared runtime for every panel: the ESPHome device server, screen policy (presence wake, ambient light, idle timeout), embedded browser engine, first-boot setup, the Manage web page, and the pane host.

- **Device-neutral:** Hardware paths and vendor services stay out of Core and belong in individual frames.
- **Small by design:** Code moves into Core only when every panel needs it or at least two panes share it; otherwise it stays inside its pane.
- **Strictly layered:** Android version checks live exclusively in `dev.glassworks.platform`, and GeckoView code lives exclusively in `dev.glassworks.engine.gecko`, enforced by automated tests.
- **Stable pane API:** After v1, breaking changes to the pane API require a strong justification.
- **Licensed under MPL-2.0**, file by file.

### Frames (`frames/<id>`, e.g. `aqara-s1-plus`)

<a href="https://www.glassworks.dev/brand#badge"><picture><source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-on-dark.svg"><img alt="Built on Glassworks" src="https://www.glassworks.dev/brand/badge-on-light.svg" height="30"></picture></a>

A frame defines a single hardware device: its sensors, CPU architectures (ABIs), Kiln installation recipe, and any hardware-specific panes that only exist on that device (such as the Aqara S1 Plus wall base).

- **Frames provide hardware, not general features.** If a capability makes sense across multiple devices, it belongs in a shared pane while each frame supplies the hardware interface (such as a `PresenceSource` or radio backend).
- **Frames isolate vendor internals.** Vendor daemons, binder calls, and serial ports stay hidden behind clean frame interfaces.
- **No vendor firmware in the repository.** Document offsets and protocol behavior in research notes, and never commit proprietary binaries.

#### The frame agent

The **frame agent** is the privileged root-side service a rooted frame runs to handle hardware tasks the Android app cannot perform directly—such as managing a Zigbee/Thread radio, stopping vendor daemons, or binding privileged ports. On the Aqara S1 Plus, this service is `glassworks_agent` ([frame-loader.md](../frames/aqara-s1-plus/research/frame-loader.md)), which handles radio modes and firewall rules, radio firmware flashing, persistent time zones, Bluetooth address lookup, and the setup hotspot portal ([radio.md](../frames/aqara-s1-plus/docs/radio.md)).

- **Executes, never decides:** The agent carries out requests from the app and reports status back, leaving all user decisions to the app and Home Assistant.
- **Validates every input:** It only reads verified files owned by the app and never lets unprivileged Android properties trigger unsigned code (see the S1 Plus threat model in [root.md](../frames/aqara-s1-plus/research/root.md)).
- **Updatable via the frame bundle:** Fixes reach panels without a full reflash, while the boot image keeps a fallback copy.
- **One agent per frame:** Root capabilities live in a single unified agent rather than a separate root daemon per pane.

### Panes

<a href="https://www.glassworks.dev/brand#parts"><picture><source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-pane-on-dark.svg"><img alt="Glassworks Pane" src="https://www.glassworks.dev/brand/badge-pane-on-light.svg" height="30"></picture></a>

Panes are modular features that a panel can enable or disable. Full guidelines live in [What makes a pane](../pane-api/src/main/java/dev/glassworks/README.md#what-makes-a-pane). In summary:

1. **Adds hardware or panel-native value.** If a Home Assistant dashboard card can already do the job, it should stay in the dashboard.
2. **Communicates through ESPHome.** State is exposed as entities, automatable settings as config entities, commands as ESPHome actions, and reports as events. (One documented exception exists for dynamic user-chosen action names such as notification buttons, which fire a bus event `esphome.<name>` because Home Assistant event entities require fixed event types upfront—this still uses ESPHome alone with no extra Home Assistant configuration.)
3. **External protocols stay opt-in.** Any secondary open protocol must be disabled by default, and the pane must remain useful without it.
4. **Read-only panel sign-in.** The panel's own Home Assistant login only reads data that ESPHome cannot carry (dashboards, preferred Thread credentials, camera snapshots) and never modifies Home Assistant state. When a human administrator signs in on the Manage page, actions run under their own Home Assistant permissions.

Every pane also declares its hardware requirements (`requires`) so devices lacking that hardware hide the pane completely. Each pane owns its own entities and settings keys, communicating with other panes only through `PanelEvent` and `PaneContext` services.

#### Entities

Home Assistant's ESPHome device page works best as a clean control surface rather than a wall of rarely touched settings. Users configure one-time options on the panel or the Manage page, while Home Assistant exposes the controls worth automating:

| Kind | Category | Enabled by default | Examples |
|---|---|---|---|
| Everyday sensors and controls | none | Yes | Presence, speaker, wall base relays |
| Settings commonly automated per panel or across the house | `CONFIG` | Common ones only | Idle timeout, max auto brightness, Do not disturb, Mute microphone |
| Health, status, and versions | `DIAGNOSTIC` | Key status values only | Wi-Fi signal, CPU usage, radio status, voice status |

- Each pane adds only a small number of uncategorized primary entities; everything else goes under Configuration or Diagnostic.
- Entities start enabled by default only when people use them regularly. One-time setup options (such as changing the radio mode) start disabled in Home Assistant and can be enabled on the device page anytime.
- Hardware calibration and purely local setup options stay on the panel and Manage page instead of cluttering Home Assistant.
- Panes that are turned off avoid adding noise to the entities a user interacts with.

### Libraries (`libs/*`)

<a href="https://www.glassworks.dev/brand#parts"><picture><source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-library-on-dark.svg"><img alt="Glassworks Library" src="https://www.glassworks.dev/brand/badge-library-on-light.svg" height="30"></picture></a>

Protocol implementations are extracted into standalone MIT-licensed libraries (`esphome-api`, `esphome-api-android`, `improv`, `bluetooth-proxy`, `sendspin`, and `voice`) so other projects can reuse them directly:

- **One protocol per library:** Libraries may depend on other Glassworks libraries (for example, `voice` and `sendspin` build on `esphome-api`), but never on the Pane API, Glassworks Core, or any frame app (`LibraryBoundaryTest` enforces this boundary).
- **Pure Kotlin where possible:** Keeping JVM libraries free of Android imports makes them easy to embed and fast to unit test.
- **Verified against reference implementations:** `esphome-api` is tested against `aioesphomeapi`, and `sendspin` against a real `aiosendspin` server. When behavior diverges, the reference implementation wins.
- **Clean licensing:** Libraries are MIT licensed, with ported upstream files preserving their original licenses (such as Apache-2.0 and BSD-3-Clause in `voice`'s `MicroFrontend.kt`).

### Kiln (`kiln/`)

<a href="https://www.glassworks.dev/brand#parts"><picture><source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg"><img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30"></picture></a>

Kiln is the browser-based WebUSB installer that provisions supported panels with nothing to install on your computer.

- **RAM first:** Boot temporary recovery tools in memory (FEL, `fastboot boot`) before touching persistent storage.
- **Back up, hash, and verify:** Every modified partition is backed up beforehand and read back afterward to confirm integrity, wherever the device can read its partitions back. Where it can't, as on the lululemon Studio Mirror, Kiln checks what it writes before flashing and says before anything is written when there's no backup.
- **Reversible by design:** Every install recipe includes a tested way back. That's the original software wherever the device lets Kiln back it up. On the lululemon Studio Mirror, whose bootloader can't read lululemon's software back, the image install's way back is the community Glass Panes image, and the experimental install on the stock software saves the parts it changes as a restore point first.
- **Data-driven recipes:** Kiln's engine stays device-agnostic; each frame declares its own steps and hardware checks.

## Who does what

| Responsibility | Home Assistant | The panel |
|---|---|---|
| Decisions and schedules | Automations, scripts, scenes | None |
| Home interface | Dashboards | Displays the dashboard full screen |
| House-wide configuration | Scenes and scripts targeting config entities | Applies its local state |
| Permissions and access | Home Assistant users and admin roles | Enforces Home Assistant permissions |
| Voice | Assist pipelines (STT, intent, TTS) | Microphones, speaker, on-device wake word |
| Music | Music Assistant | Native Sendspin player |
| Hardware (presence, light, radio, Bluetooth, camera) | Consumes entities in automations and dashboards | Exposes hardware cleanly |
| Interruptions (alerts, chimes, waking screen) | Triggers ESPHome actions | Renders overlays, sounds, and screen wake |

## Before you add something

Run any new feature idea through these seven questions in order. If the answer to any question fails the check, reconsider the design before building it:

1. **Can Home Assistant already do this** with an automation, script, blueprint, or dashboard card? If so, write a guide or blueprint instead of app code.
2. **Does it require installing something inside Home Assistant?** If yes, redesign it around native ESPHome capabilities.
3. **Does it work over ESPHome alone** once the device is adopted?
4. **If it uses another protocol**, is that protocol an open standard, opt-in, and non-essential to the core experience?
5. **Is it off by default**, and lightweight enough for our slowest supported hardware when turned on?
6. **Does it work across every frame with the required hardware**, rather than only one device?
7. **Is there a clean undo path** for anything that modifies device storage?

When the answer is not clear-cut, open a `Decide:` issue to discuss it before writing code.
