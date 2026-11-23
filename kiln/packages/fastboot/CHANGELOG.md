# Changelog: fastboot

What changed in each release of `@glassworks/fastboot` on npm. Versions follow [semver](https://semver.org); while
they start with 0, a minor version may change the API. Releases are cut from `npm/fastboot/v<version>` tags
([docs/releasing.md](../../../docs/releasing.md#npm-packages)), which publish the matching section below as
the release notes.

## [0.1.1] - 2026-11-23

### Changed
- **Bundlers keep only what you import.** The package now says it has no side effects (`"sideEffects": false`),
  so webpack, Rollup, esbuild and Vite can leave out every module you don't use.
- Needs `@glassworks/fel` 0.1.1, which says the same.

## [0.1.0] - 2026-11-22

### Added
- **First release on npm.** Fastboot over WebUSB, a fork of
  [fastboot.js](https://github.com/kdrag0n/fastboot.js) (MIT): `getvar`, `download`, `flashBlob` (raw or
  sparse, split at `max-download-size`) and `bootBlob` for any bootloader, plus `oem run`, partition
  read-back (`readPartition`) and `flashVerified` (write, read back, compare) for a U-Boot with the
  upload command. It runs on `@glassworks/fel`'s transport. It is what Kiln flashes every frame with.
