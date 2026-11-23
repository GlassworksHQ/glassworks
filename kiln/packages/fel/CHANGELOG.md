# Changelog: fel

What changed in each release of `@glassworks/fel` on npm. Versions follow [semver](https://semver.org); while
they start with 0, a minor version may change the API. Releases are cut from `npm/fel/v<version>` tags
([docs/releasing.md](../../../docs/releasing.md#npm-packages)), which publish the matching section below as
the release notes.

## [0.1.1] - 2026-11-23

### Changed
- **Bundlers keep only what you import.** The package now says it has no side effects (`"sideEffects": false`),
  so webpack, Rollup, esbuild and Vite can leave out every module you don't use.

## [0.1.0] - 2026-11-22

### Added
- **First release on npm.** FEL, the USB boot mode of Allwinner chips, from the browser over WebUSB:
  `FelDevice` (version, read, write, execute), `checkChip`, which reads the SoC id and whether secure
  boot is on, and `bootUboot`, which loads an SPL and U-Boot into RAM and starts them. Only the A133's
  memory map (`A133`) is in this release. Written clean-room from the protocol notes on the linux-sunxi
  wiki; it is what Kiln boots the Aqara S1 Plus with.
- **`@glassworks/fel/transport`.** `WebUsbBulk`, a bulk IN/OUT pair on a WebUSB device, and the
  `BulkTransport` interface it implements, so tests can run against a simulated device.
