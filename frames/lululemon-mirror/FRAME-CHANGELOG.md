# lululemon Mirror frame changelog

The Mirror's frame version: the parts of the Mirror Glassworks owns besides the app, which is the legacy build of
Glassworks for generic Android ([its changelog](../generic-android/CHANGELOG.md)). Those parts are the changes Kiln
makes to the Glass Panes system image when it flashes the Mirror. The frame version counts separately from the
app's and starts at 1.0.0, with Glassworks 1.0.0. Kiln writes it into the image as `ro.glassworks.frame.version`.

A release is tagged `frames/lululemon-mirror/v<version>`. It's a notice with no files: a Mirror gets a new frame
version by flashing again with Kiln ([docs/releasing.md](../../docs/releasing.md#frame-releases)). Its section here
is the release notes, and its first line is what Home Assistant's Frame update card shows.

## [Unreleased]

## [1.0.0] - YYYY-MM-DD

**The first lululemon Mirror frame: Kiln flashes Glass Panes' newest image with Glassworks built in, so the app updates itself from Home Assistant.**

**Upgrading.** A Mirror that Kiln flashed before this release reports an older frame version, or none. Home
Assistant's Frame update card offers this one and says how to install it: flash the Mirror again with Kiln. The
Glassworks app updates on its own card either way.

### Added

- **The first Mirror frame.** Kiln flashes Glass Panes' newest image with Glassworks built in as a system app, so it
  updates itself from Home Assistant without asking. Kiln adds the Glassworks boot animation, leaves out the apps
  the Mirror doesn't need, and lets in only your browser's adb key. Installing plain Glass Panes from Kiln swaps in
  your browser's key the same way.
- **The screen goes fully dark when Glassworks turns the backlight off**, as it does on lululemon's software. While
  the screen is awake, Glassworks keeps it visible even with the brightness slider all the way down.

