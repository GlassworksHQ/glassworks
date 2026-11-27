---
title: "Introducing Vitro: why your wall panel shouldn't need a browser"
date: 2026-12-10
author: "Eduardo"
summary: "Every Home Assistant wall panel app has fought the same WebView race. Vitro steps out of that race altogether—reading the Lovelace dashboard you already built and drawing it natively on Android, with zero browser process."
cover_alt: "Drop the browser. Keep your dashboard. The Vitro mark and four native Home Assistant cards, above a bar showing 954 MB of GeckoView processes on the Aqara S1 Plus against 0 browser processes with Vitro."
draft: true
---

<!--
Draft. Goes out with Glassworks 1.1, the release Vitro lands in (not 1.0), once #799 is on main,
the wired S1 Clarity rerun clears the provisional tag, and the Mirror Vitro run (#796) is in.
Text in [brackets] waits on #796 and release day. Once `exp/vitro` merges to `main`, update the
GitHub links to relative repo links (`../../../engines/engine-vitro/README.md`,
`../../../docs/vitro.md`, `../../../docs/engine-contract.md`,
`../../../docs/research/vitro-island-census.md`).
-->

For years, every Home Assistant wall panel app has been running the same race: wrap a WebView around Home Assistant's web frontend, pile on kiosk settings, and hope the tablet has enough CPU and RAM to keep up.

Some apps are really good at that. If you mount a recent flagship tablet on the wall and want a feature-packed WebView app, you already have great options.

**We aren't fighting that race.**

The moment you put Home Assistant on *real* wall hardware—a light-switch panel like the Aqara S1 Plus, an Android 6 smart mirror with under 1 GB of RAM, or the ten-year-old tablet in your drawer—wrapping a desktop browser around a modern JavaScript frontend hits a wall. When we published [Clarity](https://www.glassworks.dev/blog/clarity), our own S1 Plus scored a **D** and the lululemon Studio Mirror scored an **E**. On the S1, GeckoView alone ate **954 MB of RAM** at idle and pegged the main thread just to keep a typical dashboard alive.

In **Glassworks 1.1** [link to the release], we're introducing **Vitro** under **Settings → Labs**: a native Android rendering engine for the Home Assistant dashboard you already built. No browser process. No custom Home Assistant integration. No rebuilding your dashboard in someone else's editor. On the exact same Aqara S1 Plus, it drops idle memory from **954 MB to 235 MB**, cuts cold restarts from **14 seconds to 3.5**, and moves the panel from Clarity **D** to a provisional **B**.

## No more choosing between fast and yours

Until now, putting Home Assistant on a wall meant picking between two compromises:

1. **Browser / WebView kiosk apps:** Full compatibility with your Lovelace dashboards, at the cost of dragging a 500–950 MB browser engine onto a light switch—slow cold starts, janky scrolls, memory pressure, and broken stock WebViews on older Android hardware.
2. **"Native" panel apps and microcontroller screens:** Fast and lean, but they throw away your Home Assistant dashboards and force you to rebuild every screen inside their own editor or custom YAML format.

Vitro doesn't make you choose. It connects straight to Home Assistant's WebSocket API in Kotlin, reads the Lovelace dashboard you already have—layouts, themes, fonts, templates, and history—and draws it natively with Android views and Canvas.

Your dashboard stays in Home Assistant. Nothing gets installed there ([the principles](../../../docs/principles.md)). The only thing that disappears is the browser sitting between Home Assistant and the glass.

[Your own aha moment in 2–3 lines: e.g., watching the Mirror come alive on Android 6, or seeing your real dashboard render identically with zero GeckoView processes in `ps`.]

## How Vitro works: three automatic tiers

Pulling this off on real dashboards means accepting reality: Home Assistant's ecosystem is huge, and plenty of dashboards mix core cards, HACS favorites, and one or two exotic custom cards. Vitro handles that automatically with three tiers:

![Diagram: five cards from a Lovelace dashboard pass through Vitro's Kotlin data plane (view-scoped subscribe_entities, O(changed) EntityStore, themes and fonts, tier routing) to Tier 1 native core (tile, camera), Tier 2 native HACS (Mushroom, button-card) or a Tier 3 GeckoView micro-island (apexcharts-card)](./architecture.webp)

| Tier | What it handles | How it renders |
|---|---|---|
| **Tier 1: Native core** | Layouts (`sections`, `masonry`, `panel`, `grid`, stacks, `visibility`), **32 built-in Home Assistant cards**, native More-Info dialogs, native **WebRTC / HLS / MJPEG** cameras, 14 inline tile features, themes, custom fonts, and all **7,447 MDI icons** | Native Android views and Canvas: zero JavaScript and zero browser process |
| **Tier 2: Native HACS fast-paths** | **30 popular HACS cards**: the entire Mushroom suite (16 cards), `custom:button-card` (standard configs without JS templates), Bubble Card, `mini-graph-card`, `mini-media-player`, `clock-weather-card`, `modern-circular-gauge`, `bar-card`, `weather-card`, `swipe-card`, `auto-entities`, `stack-in-card`, `vertical-stack-in-card`, and `layout-card` | Native Kotlin renderers, using Home Assistant's own `render_template` subscriptions for Jinja2 |
| **Tier 3: Micro-Islands** | Any other `custom:*` card, JS-templated custom cards, or any custom card using CSS/options Vitro can't match faithfully | A tiny isolated GeckoView micro-island served from loopback (`127.0.0.1`) with a `hass` proxy shim, shared storage, and your resolved theme variables |

Two architectural decisions make this work in the real world:

- **Zero browser until a card actually needs one.** If your dashboard stays in Tier 1 and Tier 2, **GeckoView never even starts**. No tab process, no browser GPU process, no JavaScript runtime eating your panel's RAM.
- **Zero dependence on Android's system WebView.** Even when a card drops into a Tier 3 island, Vitro never touches `android.webkit.WebView` (which is dead or stuck on Chromium 74 on devices like the S1 Plus and Mirror). Only that single card pays for a micro-island, and because Vitro passes your resolved theme variables into it, it blends seamlessly into the native cards around it.

And the small interactions stay native too. Tapping or holding a tile opens native More-Info dialogs modeled after Home Assistant—an HSV color wheel and Kelvin picker for lights, a circular thermostat dial, a cover position slider, a 24-hour stepped sensor graph, live camera playback (up to four WebRTC feeds at once), and an alarm PIN pad. Themes resolve recursive `var(--...)` chains, gradients, and shadows natively, and custom `@font-face` / Google Fonts stylesheets from your Lovelace resources are fetched and cached right on the panel.

## "Home Assistant's own render is the spec"

The reason nobody has pulled off a general native Lovelace renderer before isn't that drawing a toggle button is hard. It's that **85% visual parity feels broken**. If padding is off, a Markdown table collapses, or your `card_mod` sheet behaves differently than in Chrome, you immediately stop trusting it.

So we set one non-negotiable rule: **Home Assistant's own browser render is the spec.**

- If Home Assistant draws a card, graph, font, or supported `card_mod` sheet a certain way, Vitro matches it closely enough that you can't tell them apart at a glance.
- If Home Assistant drops something—like inline `style=` attributes in Markdown—Vitro drops it too, so you never build a dashboard that only looks right in Vitro.
- If a custom card uses something Vitro can't draw faithfully, Vitro **refuses to fake it** and routes that card to a Tier 3 island instead.

We don't eyeball that bar. Every pull request runs an automated **visual parity harness** (`tools/vitro-parity`) in CI: **135 card and layout cases** rendered twice—once in headless Chromium running Home Assistant's real frontend, and once in Vitro at the exact screen sizes and densities of the S1 Plus, the Mirror, and a tablet. It diffs every case on pixels, card bounds, and font sizes. If a card drifts past its threshold, CI fails.

<!-- [Add side-by-side screenshot here: Home Assistant in Chrome (left) vs. Vitro on the lululemon Mirror or Aqara S1 Plus (right). Caption: "Try telling which side is Chrome and which side is native Android."] -->

## What changed on real hardware

Owning the rendering pipeline also let us fix three things that punish low-power panels in a browser:

- **View-scoped subscriptions:** Vitro scans the active view and subscribes only to the entities actually on screen.
- **O(changed) updates:** When one sensor ticks, `EntityStore` updates only the card bound to it—never cloning `hass.states` across a whole DOM tree.
- **Keep-alive view cache:** Switching tabs flips visibility on pre-built native views instead of tearing down and rebuilding cards from scratch.

Clarity now grades Vitro under the **exact same rubric and reference dashboards** as the web
engine—same fake Home Assistant, same taps, swipes, live updates, cameras, and More-Info dialogs.

<!--
[Embed the S1 side-by-side video here once video support lands in site/src/lib/docs/render.ts
(currently sanitized out) and the final 1.1 cut is refreshed from
C:\Users\Eduardo\scratch\vitro-video\compose\vitro-vs-web-cut-1280.mp4 (76 s, 5.1 MB, on METIS):
Part 1 heavy cold start + first tap, Part 2 14 s real-time heavy scroll with live fps counters,
Part 3–4 theme switch & native cards, count-up summary. Refresh on final 1.1 build so Bubble Card
and mini-graph-card can also be included in Part 4.]
-->

![Clarity performance comparison on the Aqara S1 Plus: Web engine (GeckoView, grade D) versus Vitro (Labs, grade B provisional) across restart, memory, blocking, camera first frame, heavy scrolling, and live updates](./performance-comparison.webp)

Here is what changed on the **Aqara S1 Plus** (Allwinner A133, 2 GB RAM; median of 3 runs; Vitro
run 3 is provisional only because the test Mac fell back to Wi-Fi—wired rerun and Mirror numbers
pending before 1.1 publishes):

| Metric (Aqara S1 Plus) | Web engine (GeckoView) | Vitro (Labs) | Change |
|---|---|---|---|
| **Clarity grade** | **D** *(light dashboards)* | **B** *(provisional — typical + camera)* | **+2 letter grades** |
| **Cold restart to dashboard** | 14.0 s | **3.5 s** | **4× faster** |
| **Idle RAM (all processes, PSS)** | 954 MB *(peak 964 MB)* | **235 MB** *(peak 253 MB)* | **4× less RAM (719 MB saved)** |
| **Typical + camera: blocked after drawing** | 3.4 s | **0 ms** | **Eliminated** |
| **Typical + camera: camera first frame** | 4.4 s | **1.1 s** | **4× faster** |
| **Heavy (120 cards): scrolling** | 4.2 fps | **49.7 fps** | **12× smoother** |
| **Heavy (120 cards): live update (p95)** | 1,512 ms | **56 ms** | **27× faster** |
| **Heavy (120 cards): tap latency (INP)** | 416 ms | **112 ms** | **3.7× faster** |
| **Heavy (120 cards): More-Info dialog** | Failed (3 of 3 runs) | **381 ms** | **Instant vs. broken** |

On the exact same wall switch, dropping the browser cut idle memory by **719 MB**, made cold
restarts **4× faster**, turned a 4.2 fps slide show on a 120-card dashboard into **49.7 fps**
(0.3 fps shy of Clarity's 50 fps bar), and dropped live-update latency from **1.5 seconds to 56 milliseconds**.
*[Add lululemon Mirror before/after numbers and one-island cost from #796 once the Mirror run finishes.]*

## Built on data, not guesswork

Instead of guessing which cards matter next, we ran a census across **60 real community Home Assistant dashboards** from GitHub ([read the island census](https://github.com/GlassworksHQ/glassworks/blob/main/docs/research/vitro-island-census.md)).

Even before our latest wave of HACS cards, **~77% of cards** in those community dashboards were already native—and nearly half of the remaining islands came from a single card (`custom:button-card`), plus `entity-button`, `mushroom-empty-card`, `weather-card`, and `swipe-card`. We've already built native renderers for all five in Vitro, cutting the remaining community island share roughly in half.

And for the long tail of custom cards, two things are on the way:

- In **Glassworks 1.2**, **[third-party Vitro cards](https://github.com/GlassworksHQ/glassworks/issues/940)** will let HACS authors ship native Vitro support right inside their existing cards—no Android tooling required.
- For inherently web content like `map`, `iframe`, and `energy-*` (plus custom cards without a native driver), Vitro sits cleanly behind Glassworks' [`Engine` contract](https://github.com/GlassworksHQ/glassworks/blob/main/docs/engine-contract.md)—so we can swap in a lighter island engine behind that seam later without touching a single native card or pane.

## Where this puts Glassworks

Put the three pieces together:

- **[Kiln](https://kiln.glassworks.dev)** unlocks locked wall hardware from a browser tab, with backups and a way back.
- **Glassworks' MPL-2.0 open core** turns that hardware into one native ESPHome device—local Assist voice, Sendspin audio, Zigbee/Thread radios, and updates inside Home Assistant.
- **Vitro** removes the browser itself, so a light switch, a smart mirror, or a tablet from 2016 feels like it was built to run Home Assistant from day one.

When a 32-bit wall switch can run a 120-card Home Assistant dashboard at 50 fps in 235 MB of RAM, the question stops being *"which kiosk app has the most WebView settings?"*—it becomes *"why is my wall panel still running a desktop browser at all?"*

## Try it today—zero risk

You don't even have to touch a panel to see how your dashboard fares:

1. **Test your dashboard right in your browser.** Paste your Lovelace YAML or JSON into the **[Vitro compatibility checker](https://www.glassworks.dev/blueprints#vitro-check)** on `glassworks.dev/blueprints`. It runs 100% client-side—nothing leaves your machine—and tells you card by card what renders natively and what triggers an island. (There's also a copyable prompt right above it if you want to convert a custom JS card to pure Vitro-native YAML.)
2. **Flip the switch in Labs.** On any Glassworks 1.1 panel (or from its Manage page), open **Settings → Labs** and enable **Vitro renderer**. The app restarts into Vitro. It's experimental and off by default—turn it off anytime and you're immediately back on GeckoView, with nothing touched in Home Assistant.

Vitro gets better with every real dashboard we throw at it. Try yours, send us any card that looks even slightly off compared to Home Assistant, and come talk about it in [GitHub Discussions](https://github.com/GlassworksHQ/glassworks/discussions) or on [Discord](https://discord.gg/hHpf77fBMj).

Eduardo
