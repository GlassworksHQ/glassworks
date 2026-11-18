<h1 align="center">Google Pixel Tablet</h1>

<p align="center">
  <b>A tested device: Glassworks for generic Android, as it is.</b><br>
  No recipe and no code of its own.
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

A **tested device** (#403): a device that runs [Glassworks for generic Android](../generic-android) with no
recipe or code of its own. Its data is in [`design/frame.json`](design/frame.json) (format:
[frames/README.md](../README.md)), with `"kind": "tested"` and `"base": "generic-android"`, and
[glassworks.dev/frames/google-pixel-tablet](https://www.glassworks.dev/frames/google-pixel-tablet) shows it.

It has no banner: a frame's banner is drawn from its `tagline`, and a tested device has none.

## Status

**Status: planned.** It installs the generic app (`dev.glassworks.generic_android`) as any Android 8+ device
does. Kiln's generic Android recipe (#403) will install it over USB; until then it is `adb install`, as in
[generic-android](../generic-android/README.md#install). Its Clarity results come with its first graded run.

## Hardware

Public specifications, not read from a unit yet (`"unverified": true`):

| | |
|---|---|
| Size | 258 × 169 × 8.1 mm |
| SoC | Google Tensor G2 |
| Android | 13 at launch (2023), updated by Google |
| Screen | 10.95-inch, 2560 × 1600 |
| Root | Not needed |

**Dock.** `"dock": true`: the tablet sits on its charging speaker dock, so Home Assistant gets its *Docked*
sensor from the start ([docs/esphome.md](../../docs/esphome.md#docked)). On the dock, Android says the battery
charges from a dock (`Dock powered: true`, read on a unit 2026-11-17).

`abis` is `arm64-v8a`: Tensor G2 devices run a 64-bit-only userspace. That is a public fact for the Pixel 7,
not read on a Pixel Tablet yet. `getprop ro.product.cpu.abilist` on a unit confirms it. The release builds
`arm64-v8a` for other frames anyway, so this entry changes no build.

## Render

`design/render.webp` is the showcase dashboard at the full 2560 × 1600, density 0.784, 3 rows of 4 sections
(`node tools/showcase/render.mjs google-pixel-tablet`, 2026-11-16, Chrome 157). The real tablet shows Chrome
pages at 1280 × 800 CSS pixels (2×), so the app on it shows fewer, larger sections than this.
