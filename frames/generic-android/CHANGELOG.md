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
- **Allow a new computer over USB on a panel with no touchscreen.** On the lululemon Mirror (the legacy build), the Manage
  page's **Security** section shows a computer that asks for USB debugging, with its fingerprint, and
  **Allow this computer**. Allowed computers are listed with **Remove**, and the panel lets each one in
  every time it connects. Only a Home Assistant administrator can allow or remove one, and the Log
  records each, with the fingerprint. Android's own "Allow USB debugging?" no longer stays over the dashboard on
  a screen without touch: the Security page is where you answer it. Needs a fresh flash with Kiln: an update
  over an older install doesn't get the two permissions it needs.
- **Glassworks as an app for any Android device.** It shows your Home Assistant dashboard full screen, and
  adds the device to Home Assistant over ESPHome with its screen, its sensors and the panes its hardware allows:
  Assist with timers on a device with a microphone, Sendspin on one with a speaker, the camera pane on one with a
  camera. It names itself after the device. Android 8 and newer.
- **A legacy build for Android 6 and 7.** The same app on an older browser engine (GeckoView 144), which opens
  only your Home Assistant. It is its own app with its own updates, and the build the lululemon Mirror runs.
- **Updates from Home Assistant.** Each build offers only its own releases on its update entity. On Android 12
  and newer with "Install unknown apps" allowed, as device owner, or as a system app (the Mirror), an update
  installs without a tap. The *Silent updates* sensor says which applies.
