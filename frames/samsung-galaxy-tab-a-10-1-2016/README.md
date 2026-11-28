<h1 align="center">Samsung Galaxy Tab A 10.1 (2016)</h1>

<p align="center">
  <b>A tested tablet running Glassworks for Generic Android on LineageOS</b><br>
  Tested by the community. No root or device-specific code required.
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

The Samsung Galaxy Tab A 10.1 (2016), model SM-T580, is a **tested device** that runs [Glassworks for Generic Android](../generic-android/README.md) as it is, with no device-specific code. Its catalog profile lives in [`design/frame.json`](design/frame.json) (see [frames/README.md](../README.md)) and is published at [glassworks.dev/frames/samsung-galaxy-tab-a-10-1-2016](https://www.glassworks.dev/frames/samsung-galaxy-tab-a-10-1-2016).

## Status

**Tested by the community on LineageOS.** Someone in the community flashed their tablet with an unofficial 64-bit LineageOS build, then installed Glassworks with Kiln, and it has run without issues so far ([read the report](https://github.com/GlassworksHQ/glassworks/issues/700)). LineageOS doesn't list this tablet among its official devices, so the build comes from the community too.

Samsung's own Android 8.1 on this tablet runs 32-bit apps only, and nobody has tested Glassworks on it yet. If you try it, a [device report](https://github.com/GlassworksHQ/glassworks/issues/new?template=device-report.yml) helps the next person.

To install, plug the tablet in over USB and open **[kiln.glassworks.dev/android](https://kiln.glassworks.dev/android)** in Chrome or Edge, or sideload the APK ([Generic Android install guide](../generic-android/README.md#install)). Kiln knows the tablet by its model, SM-T580, which LineageOS keeps.

Clarity hasn't measured it yet.

## Hardware

From published specifications. Nothing here has been read from a unit by Glassworks yet:

| Specification | Details |
|---|---|
| Dimensions | 254.2 × 155.3 × 8.2 mm, 525 g |
| Processor | Samsung Exynos 7870, eight Cortex-A53 cores at 1.6 GHz |
| Memory | 2 GB |
| Android | 6.0.1 at launch (2016), updated by Samsung to 8.1 (32-bit apps only). Tested on LineageOS, 64-bit (`arm64-v8a`) |
| Display | 10.1-inch, 1920 × 1200 |
| Root | Not required |

## Preview render

The preview image in `design/render.webp` shows the showcase dashboard at the tablet's full 1920 × 1200 resolution, drawn by `tools/showcase/render.mjs`. It matches Generic Android's render, which is drawn at the same size.
