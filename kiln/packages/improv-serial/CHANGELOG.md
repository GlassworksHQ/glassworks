# Changelog: improv-serial

What changed in each release of `@glassworks/improv-serial` on npm. Versions follow [semver](https://semver.org); while
they start with 0, a minor version may change the API. Releases are cut from `npm/improv-serial/v<version>` tags
([docs/releasing.md](../../../docs/releasing.md#npm-packages)), which publish the matching section below as
the release notes.

## [0.1.1] - 2026-11-23

### Changed
- **Bundlers keep only what you import.** The package now says it has no side effects (`"sideEffects": false`),
  so webpack, Rollup, esbuild and Vite can leave out every module you don't use.

## [0.1.0] - 2026-11-22

### Added
- **First release on npm.** An [Improv Wi-Fi Serial](https://www.improv-wifi.com/serial/) client for the
  browser: `ImprovSerialClient` asks a device for its state, its info and the networks it sees, and
  sends it Wi-Fi credentials, over any byte stream (`ByteStream`).
- **`@glassworks/improv-serial/adb`.** `openAdbStream` and `localAbstract` connect the client to an
  Android app over WebUSB adb ([Tango](https://github.com/yume-chan/ya-webadb), an optional peer
  dependency), the browser side of `ImprovAdbServer` in the `dev.glassworks:improv` Android library.
