# lululemon Mirror frame changelog

The Mirror's frame version: the parts of the Mirror Glassworks owns besides the app, which is the legacy build of
Glassworks for generic Android ([its changelog](../generic-android/CHANGELOG.md)). Those parts are the changes Kiln
makes to the Glass Panes system image when it flashes the Mirror. The frame version counts separately from the
app's and starts at 1.0.0, with Glassworks 1.0.0. Kiln writes it into the image as `ro.glassworks.frame.version`.

A release is tagged `frames/lululemon-mirror/v<version>`. It's a notice with no files: a Mirror gets a new frame
version by flashing again with Kiln ([docs/releasing.md](../../docs/releasing.md#frame-releases)). Its section here
is the release notes, and its first line is what Home Assistant's Frame update card shows.

## [Unreleased]

### Added

- **The first Mirror frame.** Kiln flashes the Glass Panes image with Glassworks built in as a system app, so it
  updates itself from Home Assistant without asking. Kiln also adds the Glassworks boot animation and the frame
  version, removes the phone, demo and test apps the Mirror doesn't need, and lets in only Kiln's own adb key in
  place of the image's shared one.
