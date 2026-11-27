# Changelog: Glassworks for generic Android

What changed in each release of Glassworks for generic Android (`dev.glassworks.generic_android`, Android 8 and
newer) and its legacy build (`dev.glassworks.generic_android.legacy`, Android 6 and 7, the lululemon Mirror
included), for people running one. The S1 app's changes are in the repo's [CHANGELOG.md](../../CHANGELOG.md).

Both builds share this file and one version sequence. A version's section is the release notes of
`generic-android-v<version>` and of `generic-android-legacy-v<version>`, whichever of the two is released
([docs/releasing.md](../../docs/releasing.md#glassworks-for-generic-android-and-its-legacy-build)). The first
line of each version is what Home Assistant shows on the update card. A change only one build gets says which.

## [Unreleased]

## [1.0.0] - YYYY-MM-DD

**Glassworks 1.0 needs Home Assistant 2026.11 or newer for notifications. It's the first stable release for Android tablets and the lululemon Mirror, with everything from 0.9.0.**

**Upgrading**

1. Update Home Assistant to 2026.11 or newer, so it can give the device its encryption key. On an older version,
   `notify` messages stop working.
2. Install 1.0.0 from the update card. Both builds update in place from 0.2.0 or newer and keep their sign-in and
   settings. Coming from 0.2.0, the notes for
   [0.3.0](https://github.com/GlassworksHQ/glassworks/blob/main/frames/generic-android/CHANGELOG.md#030---2026-11-23)
   and [0.9.0](https://github.com/GlassworksHQ/glassworks/blob/main/frames/generic-android/CHANGELOG.md#090---2026-11-24)
   cover themes, the Assist edge glow, and the notifications and Bluetooth proxy panes.
3. On the Mirror, the app and the Mirror frame update separately. The frame changes only when Kiln flashes the
   Mirror again.

### Added
- **One volume for everything the panel plays.** A new **Sound** page, in the panel's settings and on the Manage
  page, sets the volume for voice replies, timers, notifications, Music Assistant and the dashboard, with a mute
  switch. The device's volume buttons and Home Assistant's *Speaker* volume and mute control it too, and the Music
  page's slider is now **Music volume**.
- **Critical alerts play at full volume**, even when the panel is turned down or muted.

### Changed
- **Some Home Assistant controls need the encryption key.** Home Assistant 2026.11 or newer sets the key when you
  add the device, so add it from a network you trust. Until the key is set, `notify` messages, **Dashboard URL**,
  **Sign out**, **Start Wi-Fi setup (Bluetooth)** and **Open device settings** don't work from Home Assistant, so
  nobody else on your network can use them. Everyday controls like the speaker, reload and restart work as before.
- **Only Home Assistant gets the microphone and the camera.** The panel gives them to pages on your Home Assistant
  and the secure hosts in its settings, and refuses everything else, including sites framed in your dashboard.

### Fixed
- **Another device on your network can no longer replace the panel's Home Assistant sign-in.** Once signed in,
  signing in again requires a browser signed in to the panel's Manage page (or signing out first). The panel's web
  pages also answer only to the panel's own address, blocking other sites from reaching them.
- **Changing the language from Kiln no longer leaves the old dashboard running.** Changing the language or system
  font size now closes the old dashboard page before opening the new one.
- **No more crashes on devices with 16 KB memory pages.** The wake word engine is now LiteRT, which works with
  both page sizes.
- **The Mirror's camera works properly.** Its picture is no longer green and streaky, it stands upright in both
  snapshots and video, and the stream runs at full speed. On every device, the panel now waits its turn when another
  app has the camera, which on Android 6 used to crash the camera service.
- **The Mirror's screen wakes cleanly.** It comes back without lines across the picture, and it's never black while
  awake, even with the brightness at 0. In the legacy build.
- **The Mirror stays online after a restart.** If Android turns Wi-Fi off because the chip wasn't ready yet,
  Glassworks turns it back on. The setup hotspot also gives your saved network a few minutes to join before it
  opens.
- **Wi-Fi networks stay saved after a restart on Android 6 and 7**, so the Mirror no longer asks for Wi-Fi every
  time it starts.
- **Bluetooth setup finishes on the Mirror.** The Mirror stays visible to the phone setting it up, and stays
  connected long enough to report that it joined Wi-Fi. In the legacy build.
- **The first sign-in after an install works on the first try**, including with a `.local` address. The panel used
  to lose a sign-in in progress when the app restarted, or fail with "Can't reach Home Assistant" before it had
  found Home Assistant on your network.
- **The dashboard stops running behind other apps** after Android closes it to free memory.
- **The address Glassworks shows never comes from a hotspot.** It always comes from the Wi-Fi or Ethernet network
  Android is connected to, and the Mirror's stock setup mode no longer points you at an address only its own
  hotspot can reach.
- **App updates fail fast when "Install unknown apps" is off**, instead of timing out after 10 minutes.
- **Kiln asks for Chrome or Edge up front.** The Mirror and Android pages say so on their first screen, before the
  Mirror page downloads anything. Comes with Kiln, not the app.
- **Kiln explains USB permission errors and checks apps against GitHub's checksum.** When Windows, Linux, or
  another program like `adb` blocks USB access, Kiln says why and links to the driver or udev setup. App downloads
  now check only against GitHub's published SHA-256. Comes with Kiln, not the app.
- **The Mirror's Done screen matches the install.** After an in-place install on stock software, Done no longer
  says stock can't come back, and *Install Glassworks instead* opens the recommended stock-in-place install. Comes
  with Kiln, not the app.

## [0.9.0] - 2026-11-24

**The last release before 1.0. For Android 8 and newer it's the first release since 0.2.0, and brings everything in 0.3.0; the legacy build moves up from 0.3.0.**

What 0.3.0 added (themes and the Assist edge glow, the notifications and Bluetooth proxy panes, setup with no touch on the lululemon Mirror) is in [its notes](https://github.com/GlassworksHQ/glassworks/blob/main/frames/generic-android/CHANGELOG.md#030---2026-11-23).

### Fixed
- **Setup and settings fit a phone's screen.** On a phone, setup used to cut off its headings and hide what each step asks, and settings squeezed each page into a sliver beside its list. Now setup shows its steps across the top when the phone is upright, keeps every heading, QR code and button on screen, and scrolls a long step. Settings opens on its list of pages and shows each page across the whole screen, with **‹ Settings** to go back. Tablets and wall panels look the same as before.
- **Kiln installs Glassworks on the Mirror again.** Since Kiln moved to Glass Panes image 3, building the Mirror's system stopped with "no room for … libxul.so". Kiln now clears out the image's own kiosk app and the apps a Mirror never uses before it adds Glassworks, so the app fits with room to spare.

## [0.3.0] - 2026-11-23

**Themes and an edge glow for Assist, notifications and Bluetooth proxy panes, and setup with no touch on the lululemon Mirror.**

### Added
- **Appearance themes, Assist edge glow, and timer board.** A new *Appearance* page (on the panel and in Manage) adds five light/dark themes, a Custom color picker, and a **Home Assistant** theme that mirrors your dashboard's colors and dark mode (plus a toggle to follow Home Assistant's dark mode with any theme). Themes apply to both Assist and timers. Assist now shows as an **edge glow** by default, keeping your dashboard visible (the orb is still a pick under *Assist* on the *Appearance* page): the whole border swells and brightens with your voice, light trails around the edges while Assist thinks, and it rests in the corners while the reply plays. Three or more active timers group into a single **timer board** card.
- **Notifications and Bluetooth proxy panes (both builds).** Show rich Home Assistant notifications on screen (on by default; non-touch displays auto-dismiss untagged high/critical alerts after 2 minutes) and relay nearby BLE sensors to Home Assistant over an encrypted connection (off by default).
- **Security page for reset and network setup.** Reset app and pane settings while keeping Wi-Fi, logs, and Home Assistant pairing intact—or open/close 30-minute network setup—from *Settings → Security* or Manage (with on-panel code confirmation).
- **ONVIF push motion events.** Local network video recorders and Home Assistant's ONVIF integration now receive motion alerts immediately over authenticated subscriptions.
- **Public beta in Kiln & pane selection during setup.** `kiln.glassworks.dev/android` and `kiln.glassworks.dev/lululemon-mirror` now install the latest release directly (no test links needed) and let you choose which panes to enable during setup.
- **Update channels (Stable / Beta).** Choose between Stable and Beta releases from the device page in Settings or Manage.
- **The Samsung Galaxy Tab A 10.1 (2016) is a tested device.** The community ran Glassworks on an SM-T580 with LineageOS (64-bit), so it now has its own page on glassworks.dev, Kiln recognizes it by its model, and the app keeps it in landscape. Tested devices also say who tested them and on which Android, and a new *Device report* form on GitHub makes it easy to tell us how your own device did.
- **Send a device report from Kiln, no GitHub account needed.** After installing with Kiln, *Tell us how it goes* opens a short form with what Kiln read about your device already filled in (model, Android version, ABI, how you installed it). Add what worked and what didn't, see exactly what will be posted publicly, and Kiln opens the report on GitHub for you. Nothing is sent until you press Send, and you can always open it on GitHub yourself instead. Comes with Kiln, not the app.
- **Kiln installs Glass Panes' newest Mirror image, and lets only your browser in.** Both Mirror options in Kiln now use Glass Panes image 3. That image has one computer's USB key built in that isn't published, so Kiln puts your browser's key in its place, whether you install Glassworks or plain Glass Panes. Glassworks also leaves out the image's own kiosk app, so it doesn't run alongside. On the Mirror, Manage names the new image and shows a computer the image lets in as such. Comes with Kiln; the Manage part comes with the legacy build.

### Changed
- **Calmer, clearer wording across Glassworks and Kiln.** Rewrote onboarding steps, settings descriptions, Manage dialogs, sign-in pages, and Kiln install/recovery screens in plainer, more explanatory American English—removing leftover "kiosk" and protocol jargon, explaining why steps are needed, and clarifying what stays safe while waiting or recovering.

### Fixed
- **Kiln installs on tablets that run 32-bit Android.** Some tablets have a 64-bit chip but run only 32-bit apps, such as Samsung's own Android 8.1 on the Galaxy Tab A 10.1. Kiln now reads which one the tablet runs and installs the 32-bit build there. From the tablet's own browser it does the same wherever the browser says which one it runs, and otherwise offers the other build if the first doesn't install.
- **lululemon Studio Mirror & legacy Android 6–7 stability.** Keeps the Mirror locked upright in portrait across updates, reduces camera streaming RAM with 720p hardware encoding, prevents premature camera restarts during startup, re-notifies the legacy web engine during sustained low memory so dashboards reload less often, and restores device Wi-Fi and hotspot settings reliably after an interrupted setup hotspot.
- **Touchless setup & notification polish.** Keeps non-touch screens awake during setup, removes tap-only buttons and persistent notification pills on non-touch displays, fits the timer done screen cleanly on portrait screens, and enables automatic Bluetooth and hotspot Wi-Fi setup on the Mirror without touch input.
- **Setup flow, camera, and sensors.** Keeps the setup screen visible across the post-Wi-Fi restart and immediately after on-panel sign-in, reports the camera entity available as soon as ESPHome connects, returns accurate ONVIF snapshot sizes, formats *Screen and sensors* in plain English, updates Home Assistant's *Silent updates* sensor immediately after permission changes, and stops prompting for system log access on Android 10 and newer.
- **The screen stays on while Assist answers.** The screen no longer turns off in the middle of a voice run when the idle timeout hits, whether Assist is listening, thinking or reading a long answer, with the orb or the edge glow. The idle countdown starts again once the run is over.
- **Kiln's app download picks up where it stopped.** When the connection drops while Kiln downloads Glassworks for your tablet or Mirror, it carries on from where it was instead of failing, and when the download server is busy it waits a moment and tries again. The download still has to match the hash published with the release before Kiln uses it.

## [0.2.0] - 2026-11-19

**The first release of Glassworks for Android tablets and panels, with Assist, Sendspin and the camera pane.**

Experimental: run on a lululemon Mirror and a Pixel Tablet so far, not yet on other devices.

### Added
- **Allow a new computer over USB on a panel with no touchscreen.** On the lululemon Mirror (the legacy build), the Manage
  page's **Security** section shows a computer that asks for USB debugging, with its fingerprint, and
  **Allow this computer**. Allowed computers are listed with **Remove**, and the panel lets each one in
  every time it connects. Only a Home Assistant administrator can allow or remove one, and the Log
  records each, with the fingerprint. Android's own "Allow USB debugging?" no longer stays over the dashboard on
  a screen without touch: the Security page is where you answer it. Needs a fresh flash with Kiln: an update
  over an older install doesn't get the two permissions it needs.
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
- **Know when a tablet is on its dock.** A Pixel Tablet has a *Docked* sensor in Home Assistant, on while it
  sits on its dock and off when you lift it off, and its page in the settings and on the Manage page says
  *Docked* or *Not docked*. Another tablet gets both once it has been on a dock; ordinary tablets have neither.
- **Setup makes a downloaded app the panel on its own.** Installed from the device's own browser, the app shows
  a *This device* step after Panes with the two things Kiln would set: make it the home screen, and let it turn the
  screen off when idle. Each is one button that opens Android's own page or dialog. Turning the screen off makes
  Glassworks a device admin, so turn that off in Android's settings before you uninstall it. Kiln sets both, and
  setup skips the step when both are on already. Once it shows, it stays until you tap Continue or Not now. The
  Device page shows both as rows. In Kiln's `/android`, the grant that was called "Screen on and off" is now
  *Secure settings*, and "Lock the screen" is *Turn the screen off*, which is what each does.
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
- **The camera recovers by itself when it stops sending frames.** If the camera opens but sends no picture for
  5 seconds, the camera pane now closes it and opens it again, at once the first time and then less and less often
  while it stays silent. Before, presence and the stream stayed dead until something else reopened the camera, such
  as turning *Camera privacy* on and off. This was seen on the lululemon Mirror's first open after a flash.
- **With Screen mode set to always, walking up no longer turns on a screen you turned off.** If you turn the screen
  off from Home Assistant, it stays off when someone comes close, whether the panel senses them with its own sensor
  or with the camera pane. *Presence* in Home Assistant still turns on. In the presence mode, walking up still wakes
  the screen, as before.
- **A wrong Wi-Fi password on the lululemon Mirror no longer needs a reflash.** Send the right password with Improv,
  over USB from a computer or over Bluetooth, and the Mirror replaces the network Kiln saved and joins. Before, Android
  refused, and only a second flash fixed it. Comes with the next flash: the copy Kiln writes into the Mirror's image must
  have it. Legacy build only.
- **The Mirror uses less memory, and its drawing no longer dies when the screen turns off.** The legacy build
  draws in the app's own process instead of a separate GPU process, which Android killed whenever the screen went
  off or a system dialog came up. It also no longer runs GeckoView's crash helper, which did nothing for the app
  and was restarted about once a minute. Legacy build only. Not yet run on a real Mirror.
- **No crash when the app may not use the microphone.** Without the Microphone permission, voice no longer tries to
  open the microphone, which on Android 6 could crash the app. The Assist page on the device and on the Manage page
  says *Voice isn't allowed to use the microphone*, and Home Assistant's *Voice status* reads *Microphone not
  allowed*. Once the permission is given, voice starts listening within a few seconds. Both builds.
