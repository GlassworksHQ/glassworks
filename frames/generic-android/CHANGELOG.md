# Changelog: Glassworks for generic Android

What changed in each release of Glassworks for generic Android (`dev.glassworks.generic_android`, Android 8 and
newer) and its legacy build (`dev.glassworks.generic_android.legacy`, Android 6 and 7, the lululemon Mirror
included), for people running one. S1 Kiosk's changes are in the repo's [CHANGELOG.md](../../CHANGELOG.md).

Both builds share this file and one version sequence. A version's section is the release notes of
`generic-android-v<version>` and of `generic-android-legacy-v<version>`, whichever of the two is tagged
([docs/releasing.md](../../docs/releasing.md#glassworks-for-generic-android-and-its-legacy-build)). The first
line of each version is what Home Assistant shows on the update card. A change only one build gets says which.

## [Unreleased]

**The first release of Glassworks for Android tablets and panels, with Assist, Sendspin and the camera pane.**

### Added
- **The lululemon Mirror shows its frame version.** Its device page in the settings and on the Manage page has a
  *Frame version* row: the version of the Mirror's image, the Glass Panes release plus what Kiln adds. A Mirror flashed
  before Kiln wrote it shows *Before 1.0.0* and its Glass Panes image. A *Frame update* entity in Home Assistant
  says when a newer one is out; you install it by flashing the Mirror again with Kiln. Tablets and other devices
  show neither, as Google or their maker updates them.
- **Glassworks as an app for any Android device.** It shows your Home Assistant dashboard full screen, and
  adds the device to Home Assistant over ESPHome with its screen, its sensors and the panes its hardware allows:
  Assist with timers on a device with a microphone, Sendspin on one with a speaker, the camera pane on one with a
  camera. It names itself after the device. Android 8 and newer.
- **A legacy build for Android 6 and 7.** The same app on an older browser engine (GeckoView 144), which opens
  only your Home Assistant. It is its own app with its own updates, and the build the lululemon Mirror runs.
- **Setup offers silent updates when it can.** On a new setup, a *Silent updates* step follows Panes when
  updates would ask for a tap and something can change that. On Android 12 or newer it opens Android's "Install
  unknown apps" page for Glassworks. On Android 6 to 11, and on newer Android while this release targets an older
  API than Android wants for silent updates, it says a device owner skips the tap, with a QR code to Kiln. It's
  never required: Continue or Not now moves on. A device already set up doesn't see it, and one whose updates
  already install by themselves (the Mirror, a device owner) skips it.
- **Updates from Home Assistant.** Each build offers only its own releases on its update entity. On Android 12
  and newer with "Install unknown apps" allowed, as device owner, or as a system app (the Mirror), an update
  installs without a tap. The *Silent updates* sensor says which applies.
- **The screen stays on, and says when a screen lock gets in the way.** The screen stays on until someone picks
  a time in **Settings → Screen → Turn off when idle**. On a device with a PIN, pattern or password, the Screen
  settings and the Manage page say in amber that someone has to unlock it after the screen turns off, and how to
  avoid that: turn the lock off in Android's settings, or keep the screen on.

### Fixed
- **The Mirror uses less memory, and its drawing no longer dies when the screen turns off.** The legacy build
  draws in the app's own process instead of a separate GPU process, which Android killed whenever the screen went
  off or a system dialog came up. It also no longer runs GeckoView's crash helper, which did nothing for the app
  and was restarted about once a minute. Legacy build only. Not yet run on a real Mirror.
