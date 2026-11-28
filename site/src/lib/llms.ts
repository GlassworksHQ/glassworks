import { STAGE_TEXT } from "@glassworks/frame-card";
import { clarityFor } from "./clarity";
import { SOURCES } from "./docs/sources";
import { KILN, frames, installLink } from "./frames";
import libraries from "./libraries.json";
import { links, panes, SITE } from "./site";

/*
 * /llms.txt: a compact, factual Markdown index of Glassworks generated at build time from the
 * repository's live catalogs (frame.json, pane.json, clarity.json, libraries.json, docs/sources.ts)
 * so AI coding/research agents always read accurate, up-to-date ground truth without scraping
 * interactive UI components.
 */
export function buildLlmsTxt(): string {
  const frameLines = frames.map((f) => {
    const clarity = clarityFor(f.id);
    const grade = clarity.state === "graded" ? `Clarity ${clarity.grade.grade}` : "Clarity unmeasured";
    const install = installLink(f);
    const via = install ? (install.kiln ? `Kiln: ${install.href}` : `Install: ${install.href}`) : "No installer yet";
    return `- [${f.name}](${SITE}/frames/${f.id}) (${STAGE_TEXT[f.status]}, ${grade}): ${f.summary} ${via}`;
  });

  const paneLines = panes.map((p) => {
    const scope = p.frame ? `, ${p.frame} only` : "";
    return `- [${p.name}](${SITE}/panes/${p.id}) (${STAGE_TEXT[p.status]}${scope}): ${p.does} Needs ${p.needs}.`;
  });

  const libLines = libraries.map(
    (lib) => `- [${lib.name}](${SITE}/docs/libraries/${lib.name}) (MIT): ${lib.description}`,
  );

  const researchCount = SOURCES.filter((s) => s.section === "notes" && s.url !== "/docs/notes").length;

  return `# Glassworks

> Open-source, device-neutral Home Assistant wall-panel core (MPL-2.0 core, MIT standalone libraries) and WebUSB browser installer (Kiln). Home Assistant is the brain, ESPHome is the wire, your existing dashboard is the UI, and nothing gets installed in Home Assistant—no HACS integration, no cloud, no account.

- Website: ${SITE}
- Browser installer (Kiln): ${KILN}
- GitHub repository: ${links.s1}
- Discord: ${links.discord}

## Why Glassworks (Architecture & Engineering)

- [Why Glassworks (technical overview & comparison)](${SITE}/docs/why-glassworks): Side-by-side comparison vs. Companion App, Fully Kiosk, Kiosk Satellite (CC BY-NC-ND 4.0), and ESPHome LVGL, plus full architectural details.
- [Glassworks Principles](${SITE}/docs/principles): Design rules and explicit non-goals.
- [Developers & 6-layer architecture](${SITE}/developers): Core, Frame, Pane API, Engine, Kiln, and MIT libraries.
- [Panel secrets & security](${SITE}/docs/secrets): Fail-closed AES-256-GCM Keystore sealing, SHA-256-hashed Manage sessions, PKCE S256, ESPHome Noise encryption, and zero-su Ed25519 root verification (\`s1kiosk_verify.c\`).
- [Clarity performance benchmark](${SITE}/docs/clarity) & [full spec](${SITE}/docs/perf): Open A+ to E wall-panel benchmark running Home Assistant's real frontend on fixed reference dashboards over a local mock HA server.
- [Open hardware research (${researchCount} notes)](${SITE}/research): Full bring-up logs, boot chains, DRAM timings, Mirror stock-firmware probe & \`dpmd\` kernel-module extraction, and dead ends.

## Key Technical Differentiators

- **Clean-room WebUSB hardware unlock (Kiln):** Browser-based Allwinner FEL, fastboot, OPFS-backed ext4/sparse editing, Rust/WASM Android boot patching, and in-browser DEX 035 patching—with stock partition backups, write verification, and a guided path back to stock (including Aqara S1 Plus unlocked from scratch and lululemon Studio Mirror stock-in-place with a byte-exact restore point).
- **Zero HA plugins & native updates:** Each panel joins Home Assistant as one native ESPHome device and updates itself under Home Assistant's *Settings → Updates*.
- **True OSI open source:** MPL-2.0 file-level core + ${libraries.length} standalone MIT libraries published on Maven Central and npm.
- **Honest benchmarks:** Measured frames publish raw Clarity summaries and honest grades computed at build time (including a D for our own flagship Aqara S1 Plus).

## Supported Frames

${frameLines.join("\n")}

## Panes (Modular Hardware Features)

${paneLines.join("\n")}

## Standalone MIT Libraries

${libLines.join("\n")}

## Core Documentation

- [Getting Started](${SITE}/docs/getting-started)
- [The Manage page](${SITE}/docs/manage)
- [Voice and Timers](${SITE}/docs/voice-and-timers)
- [Music Assistant (Sendspin)](${SITE}/docs/sendspin)
- [Notifications](${SITE}/docs/notifications)
- [ESPHome & Automations](${SITE}/docs/esphome)
- [Updates](${SITE}/docs/updates)
- [Writing a frame](${SITE}/docs/frames)
- [Pane API](${SITE}/docs/core/pane-api)
- [The frame agent](${SITE}/docs/frame-agent)
- [Kiln documentation](${SITE}/docs/kiln)

## Roadmap

- **1.1 — Vitro** (https://github.com/GlassworksHQ/glassworks/issues/790): Native Kotlin/Android renderer for your existing Home Assistant Lovelace dashboard (*Settings → Labs*) with zero browser process for native core/HACS cards, automatic loopback GeckoView micro-islands for unsupported custom cards, and a 135-case Chromium pixel-parity CI harness.
- **1.2 — Shards & third-party Vitro cards** (https://github.com/GlassworksHQ/glassworks/issues/940): Versioned MIT plugin SDK for opt-in screensavers, photo frames, custom voice skins, intercom, and third-party native Vitro cards without bloating Core.
- **1.3 — Shard marketplace** (https://github.com/GlassworksHQ/glassworks/issues/941): Open, auditable Manage-page catalog with SHA-256 verification and crash-sentinel rollback.
`;
}
