# Why Glassworks

<a href="https://www.glassworks.dev/brand#parts"><picture><source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-core-on-dark.svg"><img alt="Glassworks Core" src="https://www.glassworks.dev/brand/badge-core-on-light.svg" height="30"></picture></a>

Glassworks is an open-source, device-neutral Home Assistant panel core and browser-based hardware unlock engine. Home Assistant stays the brain, ESPHome stays the wire, your existing dashboard stays the UI, and nothing gets installed in Home Assistant ([Glassworks Principles](principles.md)).

This page summarizes how Glassworks compares to other wall-panel approaches and documents the systems engineering underneath it.

## How Glassworks compares

| Option | Best when you want… | Where Glassworks is different |
|---|---|---|
| **Home Assistant Companion App** | The simplest setup on an everyday Android tablet or phone | No kiosk lockdown, no local Assist wake word, no Sendspin multi-room audio, no built-in Zigbee/Thread radio modes, and no WebUSB installer for locked wall hardware. |
| **Fully Kiosk Browser** | A mature, closed-source web wrapper on an everyday tablet | Proprietary with per-device paid licenses. Relies on Android's system WebView (which breaks on older wall panels) and does not run as a native ESPHome voice, radio, or Sendspin device. |
| **Kiosk Satellite** | An all-in-one Android tablet suite with built-in screensavers, photo frames, intercom, and a companion vision server | Source-available (`CC BY-NC-ND 4.0`—cannot be forked, modified for redistribution, or shipped on hardware) and targets already-open Android tablets. Glassworks is **true OSI open source (MPL-2.0 core + MIT libraries)**, unlocks locked vendor hardware from a browser tab (**Kiln**) with a path back to stock, exposes built-in radios and wall-base hardware, stays minimal with zero companion server, and drops the browser altogether in 1.1 (**Vitro**). |
| **ESPHome LVGL / OpenHASP** | Tiny ESP32 microcontroller screens with instant boot and ultra-low power | Requires rebuilding a separate UI in YAML/JSON instead of rendering your existing Home Assistant dashboard. Glassworks targets Android wall panels, smart mirrors, and tablets running real HA dashboards. |
| **Device-specific custom ROMs or kiosk apps** | Full control for a single hardware hack | Every device duplicates onboarding, ESPHome, OTA updates, voice, audio, and flashing tools. Glassworks shares one core, one Pane API, and one WebUSB installer across frames. |

## Engineering under the hood

Glassworks is not a thin WebView wrapper. Most of the hard work lives in five layers that are inspectable in the repository:

### 1. Clean-room WebUSB hardware unlock and flashing (Kiln)

[Kiln](https://kiln.glassworks.dev) runs entirely in Chrome or Edge over WebUSB and Web Serial using clean-room, MIT-licensed protocol packages—no `platform-tools`, no Python scripts, and no vendored GPL binaries:

- **Low-level protocols in TypeScript and Rust/WASM:** `@glassworks/fel` (Allwinner FEL boot and A32 SRAM routines), `@glassworks/fastboot` (streaming sparse flashing and verified read-back), `@glassworks/ext4` (OPFS-backed random-access ext4 and Android sparse image editing without expanding multi-gigabyte images), `@glassworks/bootpatch` (clean-room Rust/WASM Android boot image and Magisk ramdisk patcher), and `@glassworks/improv-serial`.
- **Frames as data:** A single Kiln engine drives every frame from declarative data and recipes under `frames/<id>/`. Automated AST contract tests verify that Kiln's app and packages never hardcode frame-specific logic.
- **Zero-storage split-trust downloads (Refract):** A pass-through Cloudflare Worker streams upstream releases with strict host and SHA-256 allowlists (`Cache-Control: no-store`), paired with GitHub API digest checks so no single host can forge an APK.
- **Unlocking hardware with a way back to stock:**
  - **Aqara Panel Hub S1 Plus:** Completely sealed out of the box—unlocked from scratch via Allwinner FEL recovery, UART serial bring-up, and DDR4 timing work (upstreamed to U-Boot). Kiln double-reads and hashes stock partitions before writing, verifies every write by read-back, and restores stock Aqara firmware cleanly ([S1 Plus research](../frames/aqara-s1-plus/README.md#research-notes)).
  - **lululemon Studio Mirror (Qualcomm MSM8916, stock 1.x):** Previously only modifiable via a destructive, one-way image flash. Using a `userdata`-only fastboot probe app, `FS_IOC_FIEMAP` block mapping, and a root `dpmd` kernel-module dump, we extracted untouched raw `system` and `boot` images for the first time—unlocking an experimental browser-based **stock-in-place** patcher (in-browser DEX 035 string-table patching, silent Device Owner updates, and a byte-exact restore point back to stock firmware alongside the community Glass Panes image) ([Mirror stock-probe research](../frames/lululemon-mirror/research/stock-firmware-probe.md)).
- **Permanent open research:** Over 60 hardware research notes live in [docs/research/](research/README.md), including exact commands, redacted outputs, confidence tags, and dead ends.

### 2. Zero-`su` root boundary and defense-in-depth security

- **No `su` grant to the app:** On rooted frames like the Aqara S1 Plus, the Android app never invokes `su`. Privileged operations pass through a single unified [frame agent](frame-agent.md) (`glassworks_agent`) and a TOCTOU-hardened C verifier (`s1kiosk_verify.c`) using Monocypher Ed25519 verification, component-by-component `openat(O_NOFOLLOW)`, and strict ownership, link-count, and size checks. Frame bundles are Ed25519-signed against a boot-baked public key with monotonic anti-rollback and crash-loop fallback.
- **Fail-closed secret storage:** Credentials, Noise PSKs, and tokens are sealed at rest with AES-256-GCM in Android Keystore using AAD bound to each `(file, key)` pair ([secrets.md](secrets.md)). Transient Keystore outages fail closed rather than falling back to plaintext or overwriting keys.
- **Hardened local Manage & pairing surface:** Session tokens are persisted only as SHA-256 hashes; `HttpOnly; SameSite=Strict` cookies, `Host` allowlisting, `Origin` validation, and constant-time CSRF checks guard LAN requests. HA OAuth uses PKCE S256 and enforces admin checks; 6-digit on-panel pairing codes use `SecureRandom` with constant-time compare and lockout throttling.
- **Protocol and legacy-engine isolation:** Privileged ESPHome entities require Noise encryption (`Noise_NNpsk0_25519_ChaChaPoly_SHA256`); ONVIF camera streams are opt-in with pre-parse XXE refusal and SSRF protections; the Android 6–7 legacy engine runs a fail-closed origin lockdown extension disabling WebRTC and speculative connections.
- **Verified in-place updates:** App updates enforce HTTPS, dual SHA-256 verification, package-ID and monotonic `versionCode` checks, and signing-certificate matching against the installed APK.

### 3. Modular Pane API and standalone MIT libraries

- **Hardware-matched panes:** Every capability (Assist voice, Timers, Sendspin audio, lighting-invariant camera presence, Zigbee/Thread radio, BLE proxy, Notifications, Diagnostics, and frame-specific panes like the S1 wall base) lives behind the [Pane API](../pane-api/src/main/java/dev/glassworks/README.md). Panes declare what hardware they `requires`; frames declare what they `provides`; disabled panes consume zero resources.
- **Reusable MIT libraries:** Core protocol implementations live outside the app as standalone MIT-licensed libraries published on Maven Central and npm—including `esphome-api`, `esphome-api-android`, `improv`, `sendspin`, `voice`, `bluetooth-proxy`, `fel`, `fastboot`, `ext4`, and `improv-serial`—so other Home Assistant projects can adopt them directly.
- **MPL-2.0 open core:** Glassworks Core (`core/`, `pane-api/`) uses file-level MPL-2.0 so hardware makers can ship custom frames or commercial hardware while improvements to core files stay open for everyone.
- **Upstream contributions:** Bugs uncovered during bring-up go upstream rather than staying as local workarounds (such as Silicon Labs radio fixes in `zigpy/bellows`, ESPHome satellite fixes in Home Assistant Core, and A133 DDR4 fixes on the U-Boot mailing list).

### 4. Honest, repeatable benchmarking (Clarity)

Spec sheets and subjective "feels snappy" reviews hide real-world lag. [Clarity](clarity.md) runs a pinned build of Home Assistant's real frontend on fixed light, typical, and heavy reference dashboards over a local mock HA server and computes an **A+ to E** grade from raw measurements using published Core Web Vitals, RAIL, and Android vitals thresholds ([full spec](perf.md)).

Grades are computed at build time from committed raw summaries and never tweaked per device—which is why our own flagship Aqara S1 Plus publishes an honest **D** alongside the Google Pixel Tablet (**B**) and lululemon Studio Mirror (**E**). Clarity runs on any Android panel or kiosk app, not just Glassworks.

### 5. Roadmap: dropping the browser and opening the platform

Every 1.0 panel updates automatically under Home Assistant's *Settings → Updates*:

- **1.1 — [Vitro](https://github.com/GlassworksHQ/glassworks/issues/790) (`Settings → Labs`):** A native Kotlin/Android renderer for your existing Home Assistant Lovelace dashboard—connecting directly to HA's WebSocket API and drawing supported core and HACS cards natively with zero browser process, while routing unsupported custom cards into isolated loopback GeckoView micro-islands. Visual fidelity is enforced in CI by a 135-case pixel-parity harness diffing Vitro against headless Chromium running Home Assistant's real frontend.
- **1.2 — [Shards & third-party Vitro cards](https://github.com/GlassworksHQ/glassworks/issues/940):** A versioned MIT plugin SDK letting the community build opt-in screensavers, photo frames, custom voice skins, doorbell pop-ups, intercom, and third-party native Vitro cards without bloating Core.
- **1.3 — [Shard marketplace](https://github.com/GlassworksHQ/glassworks/issues/941):** An open, auditable Manage-page catalog with SHA-256 verification, hardware/SDK compatibility filtering, and crash-sentinel rollback.
- **Continuous frames:** New hardware frames land in Kiln outside any version number as devices are brought up.

## Where to go next

- [Glassworks Principles](principles.md) — design rules and explicit non-goals
- [Getting Started](getting-started.md) — install Glassworks with Kiln
- [Developers](../frames/README.md) — how frames, panes, and the frame agent fit together
- [Open Hardware Research](research/README.md) — teardowns, boot chains, and bring-up logs
