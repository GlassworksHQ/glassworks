<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-library-fel-on-dark.svg">
      <img alt="fel, a Glassworks library" src="https://www.glassworks.dev/brand/banner-library-fel-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">fel</h1>

<p align="center">
  <b>Allwinner FEL boot over WebUSB, in the browser.</b><br>
  It starts an SPL and U-Boot in RAM, and writes nothing to flash.
</p>

<p align="center">
  <a href="../../../LICENSE">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-mit-on-dark.svg">
      <img alt="License: MIT" src="https://www.glassworks.dev/brand/badge-license-mit-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-alpha-on-dark.svg">
    <img alt="Status: alpha" src="https://www.glassworks.dev/brand/badge-status-alpha-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

FEL is the USB boot mode in the boot ROM of Allwinner chips. This package speaks it from a web page
over WebUSB: it reads the chip's id, reads and writes its memory, runs code, and boots an SPL and
U-Boot in RAM. Nothing is written to the device's flash, so a reset puts it back as it was.

[Kiln](../../apps/kiln/README.md) uses it to start the Aqara Panel Hub S1 Plus (Allwinner A133) in a
U-Boot of our own, which then switches to USB fastboot for
[`@glassworks/fastboot`](../fastboot/README.md). It was written clean-room from the protocol notes on
the [linux-sunxi wiki](https://linux-sunxi.org/FEL/Protocol), as described in
[fel-spec.md](../../../docs/research/kiln-cleanroom/fel-spec.md), and holds no sunxi-tools code.

## Install

```sh
npm install @glassworks/fel
```

An ES module with its own types. It runs in a browser with WebUSB (Chrome, Edge and other Chromium
browsers), on a secure origin, from a user gesture. The types of WebUSB come with it
(`@types/w3c-web-usb`).

## Use

```ts
import { FelDevice, WrongChip, bootUboot } from '@glassworks/fel';

// From a click: the browser asks which device to use (USB id 1f3a:efe8, a chip in FEL mode).
export async function boot(spl: Uint8Array, uboot: Uint8Array): Promise<void> {
  const fel = await FelDevice.request();
  try {
    await bootUboot(fel, spl, uboot, (p) => console.log(`${Math.round(p * 100)} %`));
  } catch (e) {
    if (e instanceof WrongChip) console.log(`not an A133: SoC 0x${e.socId.toString(16)}`);
    throw e;
  }
}
```

`bootUboot` checks the chip (`checkChip`: an A133 whose boot ROM still runs unsigned code), runs the
SPL, which brings up DRAM, uploads U-Boot to DRAM in 64 KiB writes, and resets the CPU into it in
AArch64. The U-Boot must be built to start at `A133.ubootAddr` without a BL31 (TF-A), as the
S1's is.

Exports:

| From | What |
|---|---|
| `@glassworks/fel` | `FelDevice` (`request`, `fromDevice`, `known`, `version`, `read`, `readl`, `write`, `exec`, `runSpl`, `reset64`, `close`), `checkChip`, `bootUboot`, `WrongChip`, `FEL_FILTER`, `A133` (its memory map), `FEL_CHUNK`, `hex` |
| `@glassworks/fel/transport` | `WebUsbBulk`, a bulk IN/OUT endpoint pair on a WebUSB device, and the `BulkTransport` interface, which tests can implement with a simulated device |

## Status

Alpha. Only the A133 is described (`A133`), and only the boot Kiln needs is there. Another SoC needs
its own memory map, and its SPL may need another way back from the boot ROM. Versions start at 0, so a
minor version may change the API ([changelog](CHANGELOG.md)).

## Tests

`test/fel.test.ts` runs the whole boot against a simulated boot ROM (`test/fakes.ts`), which runs the
routines the host uploads in a small ARM interpreter (`test/arm.ts`). `test/vectors.test.ts` checks
what the boot ROM sees on the wire against recorded vectors (`test/vectors/fel.json`), and
`readme.test.ts` type-checks the example above. `scripts/check-routines.sh` checks that the instruction
words in `src/routines.ts` are what `src/routines.S` assembles to (it needs LLVM with the ARM target).
`npm test` in this package runs the tests, and so does `npm test` at the repo root.
