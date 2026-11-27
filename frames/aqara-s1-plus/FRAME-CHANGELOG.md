# Aqara S1 Plus frame changelog

The S1's frame version: the parts of the panel Glassworks owns besides the app (the loader in its boot image,
the service scripts, the radio's files and the boot animation). It counts separately from the app's
version ([CHANGELOG.md](../../CHANGELOG.md)) and starts at 1.0.0, with Glassworks 1.0.0. A release is tagged
`frames/aqara-s1-plus/v<version>` and carries the signed frame bundle
([docs/releasing.md](../../docs/releasing.md#the-s1-frame-bundle)). Its section here is the release notes, and
its first line is what Home Assistant's Frame update card shows.

## [Unreleased]

## [1.0.0] - YYYY-MM-DD

**The first Aqara S1 Plus frame release: a loader in the boot image, so the panel's own services update from Home Assistant.**

**Upgrading from 0.4.x.** Before this, the parts of the panel outside the app had no version and changed only with
a Kiln install. First check the *Radio helper* sensor in Home Assistant: if it says the boot image needs
refreshing, run a full Kiln install once more. Then update the S1 app, and its radio bundle puts the frame loader
into the panel's boot image over the air. From then on, the Frame update card in Home Assistant installs this
release and every one after it. Until the loader is in, the card says how to get it. Keep the backup `.zip` from
your first Kiln install, which holds the panel's original Aqara boot partition.

### Added

- **The frame loader.** The S1's boot image gets a small loader that runs the panel's services from a signed
  frame bundle, and falls back to its own copies when there is none. It comes with the S1 app's radio bundle,
  which writes it into the boot image over the air once the panel's boot image takes radio bundle updates. From
  then on, frame updates come from Home Assistant's Frame update card.
- **Frame updates install from Home Assistant.** Press Install on the Frame update card, or on the Device
  page, and the panel downloads this frame's signed bundle, checks it, and restarts once to load it. Needs the
  frame loader and an S1 app with frame updates.
