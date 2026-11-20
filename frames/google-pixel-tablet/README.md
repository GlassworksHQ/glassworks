<h1 align="center">Google Pixel Tablet</h1>

<p align="center">
  <b>A tested device running Glassworks for Generic Android</b><br>
  No custom install recipe or device-specific code required.
</p>

<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-on-dark.svg">
      <img alt="Built on Glassworks" src="https://www.glassworks.dev/brand/badge-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-planned-on-dark.svg">
    <img alt="Status: planned" src="https://www.glassworks.dev/brand/badge-status-planned-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

The Google Pixel Tablet is a **tested device** that runs [Glassworks for Generic Android](../generic-android/README.md) out of the box, with no custom firmware or device-specific code required. Its catalog profile lives in [`design/frame.json`](design/frame.json) (see [frames/README.md](../README.md)) and is published at [glassworks.dev/frames/google-pixel-tablet](https://www.glassworks.dev/frames/google-pixel-tablet).

## Status

**Status: planned.** The Pixel Tablet runs the standard Generic Android app (`dev.glassworks.generic_android`). Once Kiln's Generic Android installer opens to everyone, you will be able to install it straight from Chrome or Edge over USB; for now, follow the test install or `adb install` instructions in [Generic Android](../generic-android/README.md#install).

**Clarity v1: B (provisional)** — performs well as a wall panel for typical Home Assistant dashboards with a live camera. Large dashboards hold it back from an A grade: on the heavy reference view, the camera takes 2.1 seconds to show its first frame and the main thread blocks for 0.9 seconds after drawing the view. The grade is marked provisional because camera stall stats on the heavy view were not reported in time. You can inspect the full benchmark report in [clarity/report.md](clarity/report.md) and the structured summary in [clarity/clarity.json](clarity/clarity.json) (see [Clarity performance grades](../../docs/perf.md)).

## Hardware

Hardware specifications based on published device details (some specs such as the ABI list have not yet been read directly from a unit):

| Specification | Details |
|---|---|
| Dimensions | 258 × 169 × 8.1 mm |
| Processor | Google Tensor G2 (`arm64-v8a`) |
| Android | Android 13 at launch (updated by Google) |
| Display | 10.95-inch, 2560 × 1600 |
| Root | Not required |

**Charging Speaker Dock support:** Because the Pixel Tablet pairs with its Charging Speaker Dock (`"dock": true`), Glassworks automatically exposes a **Docked** binary sensor in Home Assistant from the start ([details](../../docs/esphome.md#docked)). That makes it easy to trigger automations—such as switching dashboards or muting announcements—whenever you lift the tablet off its dock or place it back.

## Render

The preview image in `design/render.webp` renders the showcase dashboard at full 2560 × 1600 resolution (3 rows of 4 sections). On the actual tablet, Android displays web content at 1280 × 800 CSS pixels (2× display scaling), so dashboards appear larger with fewer cards per row than in the full-pixel render.
