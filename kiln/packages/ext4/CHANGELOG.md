# Changelog: ext4

What changed in each release of `@glassworks/ext4` on npm. Versions follow [semver](https://semver.org); while
they start with 0, a minor version may change the API. Releases are cut from `npm/ext4/v<version>` tags
([docs/releasing.md](../../../docs/releasing.md#npm-packages)), which publish the matching section below as
the release notes.

## [0.1.1] - 2026-11-23

### Changed
- **Bundlers keep only what you import.** The package now says it has no side effects (`"sideEffects": false`),
  so webpack, Rollup, esbuild and Vite can leave out every module you don't use.

## [0.1.0] - 2026-11-22

### Added
- **First release on npm.** ext4 and Android sparse images in the browser, with no dependencies:
  `readSparse` streams a sparse image into a block store (in memory, or OPFS through
  `syncHandleStore`), `Ext4` adds and removes files with their owner, mode and SELinux label, `mkfs`
  builds a fresh ext4, and `writeSparse` streams the result back out as a sparse image for fastboot.
  Checked against e2fsprogs in our tests. It is what Kiln builds the Lululemon Mirror's system and
  userdata images with.
