<h1 align="center">Google Pixel Tablet</h1>

<p align="center">
  <b>A tested tablet running Glassworks for Generic Android</b><br>
  No root, custom firmware, or device-specific code required.
</p>

<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-on-dark.svg">
      <img alt="Built on Glassworks" src="https://www.glassworks.dev/brand/badge-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-stable-on-dark.svg">
    <img alt="Status: stable" src="https://www.glassworks.dev/brand/badge-status-stable-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

The Google Pixel Tablet is a **tested device** that runs [Glassworks for Generic Android](../generic-android/README.md) out of the box—no root, custom firmware, or device-specific code required. Its catalog profile lives in [`design/frame.json`](design/frame.json) (see [frames/README.md](../README.md)) and is published at [glassworks.dev/frames/google-pixel-tablet](https://www.glassworks.dev/frames/google-pixel-tablet).

## Status

**Installable today via Generic Android**. Plug your Pixel Tablet in over USB and open **[kiln.glassworks.dev/android](https://kiln.glassworks.dev/android)** in Chrome or Edge to install Glassworks, grant permissions, and enable silent self-updates automatically—or sideload the APK directly ([Generic Android install guide](../generic-android/README.md#install)).

**Clarity v1: B** — performs well as a wall panel for typical Home Assistant dashboards with a live camera. Large dashboards hold it back from an A grade: on the heavy reference view, the camera takes 2.1 seconds to show its first frame and the main thread blocks for 0.9 seconds after drawing the view. Camera stall stats on the heavy view weren't reported in time, so they're missing from the report, but they can't change the letter, because the heavy view already falls short of an A on blocking. You can inspect the full benchmark report in [clarity/report.md](clarity/report.md) and the structured summary in [clarity/clarity.json](clarity/clarity.json) (see [Clarity performance grades](../../docs/clarity.md)).

## Hardware

Hardware specifications based on published device details (some specs such as the ABI list have not yet been read directly from a unit):

| Specification | Details |
|---|---|
| Dimensions | 258 × 169 × 8.1 mm |
| Processor | Google Tensor G2 (`arm64-v8a`) |
| Android | Android 13 at launch (updated by Google) |
| Display | 10.95-inch, 2560 × 1600 |
| Root | Not required |

**Charging Speaker Dock support:** Because the Pixel Tablet pairs with its Charging Speaker Dock, Glassworks automatically exposes a **Docked** binary sensor in Home Assistant from day one ([details](../../docs/esphome.md#docked)). That makes it easy to trigger automations—such as switching dashboards, adjusting volume, or muting announcements—whenever you lift the tablet off its dock or snap it back on.

## Preview render

The preview image in `design/render.webp` renders the showcase dashboard at full 2560 × 1600 resolution (3 rows of 4 sections). On the actual tablet, Android displays web content at 1280 × 800 CSS pixels (2× display scaling), so dashboards appear larger with fewer cards per row than in the full-pixel render.

## Research notes

Engineering notes from running Glassworks on the Pixel Tablet (see [all research notes](../../docs/research/README.md#the-research-notes)):

- [window-layers.md](research/window-layers.md) — Which layer holds an app window's frames on Android 17, and how Clarity finds Vitro's there
