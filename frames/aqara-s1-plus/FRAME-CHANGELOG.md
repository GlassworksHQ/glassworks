# Aqara S1 Plus frame changelog

The S1's frame version: the parts of the panel Glassworks owns besides the app (the loader in its boot image,
the service scripts, the radio's files and the boot animation). It counts separately from the app's
version ([CHANGELOG.md](../../CHANGELOG.md)) and starts at 1.0.0, with Glassworks 1.0.0. A release is tagged
`frames/aqara-s1-plus/v<version>` and carries the signed frame bundle
([docs/releasing.md](../../docs/releasing.md#the-s1-frame-bundle)). Its section here is the release notes, and
its first line is what Home Assistant's Frame update card shows.

## [Unreleased]

### Added

- **The frame loader.** The S1's boot image gets a small loader that runs the panel's services from a signed
  frame bundle, and falls back to its own copies when there is none. After this one Kiln update, frame
  updates come from Home Assistant's Frame update card. Needs a Kiln update.
- **Frame updates install from Home Assistant.** Press Install on the Frame update card, or on the Device
  page, and the panel downloads this frame's signed bundle, checks it, and restarts once to load it. Needs the
  frame loader and an S1 app with frame updates.
