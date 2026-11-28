# Changelog: Glassworks for generic Android

What changed in each release of Glassworks for generic Android (`dev.glassworks.generic_android`, Android 8 and
newer) and its legacy build (`dev.glassworks.generic_android.legacy`, Android 6 and 7, the lululemon Mirror
included), for people running one. The S1 app's changes are in the repo's [CHANGELOG.md](../../CHANGELOG.md).

Both builds share this file and one version sequence. A version's section is the release notes of
`generic-android-v<version>` and of `generic-android-legacy-v<version>`, whichever of the two is released
([docs/releasing.md](../../docs/releasing.md#glassworks-for-generic-android-and-its-legacy-build)). The first
line of each version is what Home Assistant shows on the update card. A change only one build gets says which.

## [Unreleased]

### Changed
- **The app starts faster.** The app now tells Android which of its code runs at start, so Android prepares that code
  ahead of time the next time it optimizes its apps, and Android 10 and older check less of the app's code at every start.

### Fixed
- **Home Assistant on standard HTTP (port 80) or HTTPS (port 443) now signs in and loads cleanly.** Discovering or
  saving an address with an explicit `:80` or `:443` used to mismatch the browser's normalized page origin, causing
  the panel to refuse dashboard authentication tokens, dashboard lists, theme updates, and permissions. Default ports
  are now stripped from origins and omitted for port 80 discovery.
- **Home Assistant addresses entered without `http://` or a port now work.** Typing a bare address such as
  `192.0.2.10` or `homeassistant.local` in Settings or manual setup now fills in `http://` and port `8123`
  automatically, while explicit `http://` and `https://` addresses keep their scheme and port. Invalid addresses
  are rejected with an inline error instead of being saved.
- **Sign-in errors and loading timeouts no longer trap the panel on the connecting screen or leave a blank dark screen.**
  If Home Assistant rejects authentication without revoking the panel's token (for example during a temporary error or
  IP ban), the panel now stops bouncing between the dashboard and the login page, hides the connecting screen right
  away, and reveals Home Assistant's launch screen on auth failure or fallback timeout so any error is visible.
- **Changing Screen settings to stay on wakes a sleeping panel right away.** Setting *Turn off when idle* to *Never*
  or turning *Wake when someone's near* off from Manage or Settings now wakes a sleeping or blacked-out display
  immediately instead of leaving it dark until restart.
- **Lockdown messages are no longer hidden behind the connecting screen.** When the legacy browser engine blocks a
  page or waits on its lockdown, the connecting screen now steps aside so you can read the message. In the legacy
  build.
- **Manage sign-in clarifies how to show a panel code and keeps the screen awake while it is displayed.** On the
  Manage sign-in page, the fallback link now reads *Show a code on the panel* so it is clear you need to click it to
  display a code, and the panel keeps its screen awake for the full 5 minutes while a code is on screen instead of
  dimming on its idle timeout.
- **Manage's Log page now captures app logs on Android 6.** Older Android builds do not support `logcat -v epoch`,
  which left Manage's Log showing only Manage events; Glassworks now falls back to `threadtime` format on Android 6
  so app, sign-in, and screen logs appear as expected. In the legacy build.

## [0.9.1] - 2026-11-27

**One volume for everything the panel plays, and a round of fixes for the lululemon Mirror: a real camera picture, Wi-Fi that stays on, and a screen that wakes without lines.**

### Added
- **Vitro, an experimental native dashboard renderer, in a new Labs section.** Turn on *Vitro renderer* under
  *Settings → Labs*, on the panel or the Manage page, and after the app restarts your Home Assistant dashboard is drawn
  with the device's own views instead of a browser. Vitro draws a card natively only when it can match Home Assistant's
  look: a custom card it can't draw shows in a small browser view, and a view it can't draw, such as one with a map,
  opens as Home Assistant draws it. It's off by default, and while it's off the app shows your dashboard in its browser
  as it always has. If the app keeps crashing or freezing right after it starts with Vitro on, it turns Vitro off by
  itself, and Labs and the Manage page say why. In both builds; the legacy build (Android 6 and 7, the Mirror
  included) has seen the least testing so far.
- **Vitro plays cameras over WebRTC, as Home Assistant does.** With Vitro on, a camera that Home Assistant streams
  over WebRTC (through go2rtc, Nest and other integrations) plays that way, with low delay and hardware decoding
  where the device has it. Cameras without WebRTC, or where it doesn't connect, play HLS or MJPEG as before, and a
  camera goes back to WebRTC once Home Assistant is back after a restart. Nothing changes in Home Assistant.
- **Vitro dashboards with live cameras scroll more smoothly.** With Vitro on, a live camera that's scrolled out of
  view stops drawing its video until it's back on screen, so cameras you can't see no longer slow down the rest of the
  dashboard.
- **Vitro scrolls long dashboards more smoothly.** With Vitro on, a dashboard with many cards now draws only the
  cards on screen and near it as you scroll, and a card's value that updates often (a power or temperature sensor)
  no longer makes the whole dashboard lay out again. Cards look the same as before.
- **Live cameras on Vitro cost the device less, and HLS cameras start sooner.** With Vitro on, each video frame
  now redraws only the video, not the whole camera card around it, and the camera's still picture is no longer
  drawn under the video once it plays, so a dashboard with several live cameras leaves the device more room for
  everything else. A camera that plays over HLS starts once it has half a second of video instead of two and a half.
- **One volume for everything the panel plays.** The new **Sound** page in the panel's settings and on the Manage
  page sets the volume of voice replies, timers, notifications, Music Assistant, and the dashboard's own sound,
  with a mute switch. It is Android's media volume, so the device's volume buttons move it too. Home Assistant's
  *Speaker* volume and mute now control the same thing, so turning the panel down in Home Assistant quiets all of
  it, not only announcements. The Music page's slider is now called
  **Music volume** and sets Music Assistant's level under it. On the few devices whose volume Android keeps fixed,
  such as some TV boxes, it still sets everything but the dashboard's own sound.
- **Critical alerts play at full volume.** A critical notification's alert tone now plays at full volume, even
  when the panel is turned down or muted, and the volume goes back to where it was after each tone.

### Changed
- **Vitro dashboards open faster and stay smoother while values change.** With Vitro on, big dashboards build their
  cards with less work, history graphs and sensor graphs read their history in the background, and a value that keeps
  changing, such as a power sensor or a clock with seconds, no longer makes the whole dashboard lay itself out again.
  Nothing looks different.

### Fixed
- **Kiln recovers from a brief USB hiccup during the Mirror's first start.** On Windows and slow first boots,
  a brief `getprop` stall or busy USB handle while reconnecting used to stop the install instead of retrying.
  Kiln now waits for the dropped connection to close, retries transient busy errors, and allows more time per
  first-start read. Comes with Kiln, not the app.
- **Setup wakes a sleeping Mirror instead of staying dark.** When a no-touch Mirror enters setup while its
  screen is blacked out (for example, after losing Wi-Fi), it now turns the backlight back on and keeps the
  screen awake until setup finishes.
- **Kiln explains a USB connection this computer won't open.** When Windows or Linux won't let the browser open
  the Mirror or the panel, or another program such as adb already holds it, Kiln now says which it is and what to do,
  instead of saying it disconnected. The Mirror's fastboot screen also shows the one-time USB setup for Windows
  (WinUSB via Zadig) and Linux (a udev rule), with a link to the steps. Comes with Kiln, not the app.
- **Kiln checks the Mirror's and Android's app against GitHub's own checksum.** The app Kiln downloads from a release
  is now checked only against the SHA-256 GitHub publishes for it, as the S1's already was, never against the
  checksum file that comes from the same download server as the app. Comes with Kiln, not the app.
- **The Mirror's Done screen no longer says its stock software can't come back.** After an install on the stock
  software, Done offered to restore the original system and then said, right under it, that the install couldn't go
  back to lululemon's software. It now only offers the restore. *Install Glassworks instead* on a community image
  also opens the recommended install, on the stock software, as the first screen does. Comes with Kiln, not the app.
- **Another device on your network can no longer replace the panel's Home Assistant sign-in.** Once the panel is
  signed in, its sign-in page only lets you sign in again from a browser signed in to the panel's Manage page. To use
  another account, sign the panel out first. The panel's web pages also answer only to the panel's own address now,
  which blocks pages on other sites from reaching them.
- **Changing the language from Kiln no longer leaves the old dashboard running.** When Kiln sets the panel's
  language, or the system font size changes, the panel now closes the old dashboard page before it opens the new
  one, so the two don't both hold memory.
- **Glassworks no longer crashes on devices with 16 KB memory pages.** The wake word engine's native library was
  built for 4 KB pages. On a device that uses 16 KB pages, the app crashed as soon as the wake word started and
  kept crashing each time Android restarted it. Debug builds on Android 15 and newer also showed an "Android App
  Compatibility" warning at start. The engine is now LiteRT, TensorFlow Lite under its new name, whose library
  is built for both page sizes, so the wake word works there too.
- **Setup over Bluetooth stays findable on the Mirror.** On Android 6 and 7, a hiccup while the panel updated what
  it broadcasts could leave it invisible to the phone or browser setting it up, with nothing trying again. It now
  tries again a few times, and if Bluetooth still refuses, setup stops and says why. Legacy build only.
- **The Mirror's camera shows a real picture.** Home Assistant used to show the Mirror's camera as mostly solid
  green with a streaky band, in stills and in the video stream alike. The panel asked the camera for a slower frame
  rate than it runs well at; it now asks every camera for its normal rate. If a camera still sends empty frames, the
  panel never passes them on and restarts the camera.
- **The Mirror's camera stands upright.** Its snapshots used to come out upside down and its video stream on its
  side, because the Mirror's camera reports the wrong mounting. Both now stand as you see the room, and the stream is
  portrait on the Mirror. Every camera's stream now turns the same way its snapshots do.
- **The panel waits its turn when another app has the camera.** It used to try for the camera every few seconds,
  several times at once, which on Android 6 could crash the system's camera and audio service. It now waits a
  little longer after each try, up to a minute, and takes the camera as soon as the other app lets it go.
- **The Mirror's camera stream runs at full speed.** With the video stream on, the Mirror's camera slowed to about
  7 frames a second, because it copied every frame for motion detection too. Motion detection now looks at a few
  frames a second while the stream gets all of them.
- **The Mirror keeps its Wi-Fi on after a restart.** On the Mirror's stock software, a quick restart could find
  the Wi-Fi chip not ready yet, and Android then switched Wi-Fi off and kept it off, so the panel stayed offline
  until someone turned Wi-Fi back on. Glassworks now turns Wi-Fi back on by itself when that happens, trying for a
  couple of minutes. A Wi-Fi you switch off on purpose stays off. Kiln's in-place install also sets Wi-Fi's switch
  back on when it finds it off. And when the setup hotspot finds Wi-Fi switched off, it turns Wi-Fi on and gives
  your saved network a few minutes to join before it opens, so it no longer takes the panel offline for 15 minutes.
- **The Mirror's screen never stays dark while it's awake.** On the Mirror, brightness 0 turns the backlight off, so
  a panel whose system brightness was at 0 came up awake but black, and waking it from Home Assistant didn't help.
  Glassworks now keeps the Mirror's screen at a visible level whenever it's awake, whatever the system or manual
  brightness says. Sleep still turns the backlight off. In the legacy build.
- **App updates fail fast when "Install unknown apps" is turned off.** On Android 8 and newer (unless Glassworks is a system app or device owner), starting a Home Assistant update without "Install unknown apps" allowed used to download the APK, open Android's refusal dialog, and wait 10 minutes before reporting a timeout. Glassworks now stops right away and tells you to enable "Install unknown apps" in Android's settings.
- **Wi-Fi setup over Bluetooth finishes on the Mirror.** When Home Assistant or its phone app sent the Wi-Fi
  details over Bluetooth, the Mirror joined the network, but the phone or Home Assistant kept saying it was
  connecting because the Mirror dropped the Bluetooth connection before it could report back. It now stays
  connected and reports the join, so the setup finishes. In the legacy build.
- **The Mirror's screen wakes without lines across it.** When the Mirror went idle or Home Assistant turned its screen off, it used to come back with lines across the picture until it was unplugged. Now it avoids Android's display-off path and sleeps the way lululemon's own software did — dropping the backlight to zero under a black screen so waking brings the picture straight back. In the legacy build.
- **A Wi-Fi network you give Glassworks stays saved after a restart on Android 6 and 7.** The legacy build joined
  the network from Kiln or your phone, but Android forgot it at the next restart, so a lululemon Mirror on its stock
  system asked you to send Wi-Fi from a phone every time it started. It now keeps the network, as Android 8 and newer
  always did.
- **The first Home Assistant sign-in after an install works on the first try.** Entering Home Assistant's address
  restarts the app once, and a sign-in started just then, like Kiln's, was forgotten by the time Home Assistant sent
  you back, so the panel said "This link has expired" until you signed in again. The panel now remembers a sign-in
  in progress across the restart.
- **The first sign-in no longer fails with "Can't reach Home Assistant" when you enter a `.local` address.**
  Android 11 and older can't look up names like `homeassistant.local`, so the panel reaches Home Assistant through
  the address it finds on your network. A sign-in started before the panel had found that address kept the name and
  failed, and it only worked when you tried again. The panel now finishes the sign-in through the address it has
  found by then, waiting a few seconds for it if needed.
- **The dashboard no longer keeps drawing behind another app after Android closes it.** When Android closed the
  dashboard's page to free memory while another app was in front, the page the panel opened in its place kept
  running as if it were on screen until you came back to the panel. It now waits in the background, as the page did
  before Android closed it.
- **Kiln asks for Chrome or Edge before it starts.** In a browser that can't talk to USB devices, such as Firefox or
  Safari, Kiln's Mirror and Android pages now say so on their first screen, as the S1 page does. Before, the Mirror
  page let you download and build its whole system image first, then stopped at the first USB step. Comes with Kiln,
  not the app.
- **The Mirror's setup screen no longer points at its own hotspot's address.** On a Mirror still running its stock setup mode with no Wi-Fi, the Wi-Fi step said to open `http://192.168.43.1:8099/setup` on a computer, an address no computer on your network can reach. It now waits for Wi-Fi and says why the setup Wi-Fi isn't up. On every device, the address Glassworks shows and hands to Home Assistant (setup page, sign-in code, Thread join, camera, *IP address*) comes only from the Wi-Fi or Ethernet network Android is connected to, never from a hotspot or tethering.
- **Only Home Assistant gets the microphone and the camera.** The panel used to hand the microphone, the camera and
  every other page permission to any page it showed, without asking. Now only pages on your Home Assistant and on the
  secure hosts in the panel's settings get them. A link that leads off your dashboard, or another site framed inside it,
  is refused, and the panel's log says so. An `allow` attribute on the frame doesn't change that: a site framed in
  your dashboard needs to be one of the secure hosts too. Permissions an older version saved for other sites, including
  framed ones, are forgotten when the app starts.
- **Some Home Assistant controls need the encryption key.** Until Home Assistant gives the panel its key, anyone on
  your network can reach the panel's ESPHome connection. **Dashboard URL**, **Sign out**, **Start Wi-Fi setup
  (Bluetooth)** and **Open device settings** now work only over the encrypted connection, so once Home Assistant has
  set the key, no other device can point the panel at its own page. Home Assistant 2026.11 or newer sets the key by
  itself when you add the panel; do that from a network you trust. On an older Home Assistant, change these on the
  panel or on the Manage page. The speaker, reload, restart and the other everyday controls work as before.
  Notifications sent with the `notify` action need the key too, so a stranger on your network can't put an alert on
  the screen. On an older Home Assistant, `notify` calls fail until you update it.

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
