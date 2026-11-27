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

**Upgrading.** A Mirror that Kiln flashed before this release reports an older frame version, or none, and Home
Assistant's Frame update card offers this one and says how to install it: flash the Mirror again with Kiln. The
Glassworks app updates on its own card, with or without a new frame.

### Added

- **The first Mirror frame.** Kiln flashes Glass Panes' newest image (image 3) with Glassworks built in as a system
  app, so it updates itself from Home Assistant without asking. Kiln also adds the Glassworks boot animation and the
  frame version, leaves out the image's own kiosk app and the phone, demo and test apps the Mirror doesn't need, and
  lets in only Kiln's own adb key in place of the one the image names. Installing plain Glass Panes from Kiln does
  the same for who it lets in over USB: your browser's key replaces the one built into their system image, and the
  shared key in their userdata.
- **The screen goes fully dark when Glassworks turns the backlight off.** The image's lowest backlight level
  used to leave a faint glow; Kiln now adds a small overlay that lets the backlight reach zero, as the stock
  software does. While the screen is awake, Glassworks keeps it visible even with the brightness slider all the
  way down.
