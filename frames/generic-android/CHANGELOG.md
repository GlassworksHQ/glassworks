# Changelog: Glassworks for generic Android

What changed in each release of Glassworks for generic Android (`dev.glassworks.generic_android`, Android 8 and
newer) and its legacy build (`dev.glassworks.generic_android.legacy`, Android 6 and 7, the lululemon Mirror
included), for people running one. The S1 app's changes are in the repo's [CHANGELOG.md](../../CHANGELOG.md).

Both builds share this file and one version sequence. A version's section is the release notes of
`generic-android-v<version>` and of `generic-android-legacy-v<version>`, whichever of the two is tagged
([docs/releasing.md](../../docs/releasing.md#glassworks-for-generic-android-and-its-legacy-build)). The first
line of each version is what Home Assistant shows on the update card. A change only one build gets says which.

## [Unreleased]

### Added
- **Assist can show as an edge glow.** On the *Appearance* page, *Assist* now picks between the orb and an edge
  glow. With the edge glow, the dashboard stays in view: the theme's two colors light up at the microphone, run
  around the edge of the screen and meet at the bottom, and a small caption shows what you said and the reply.
  While you speak the light follows your voice, and while the reply plays it breathes calmly. Tap anywhere to
  cancel, as with the orb.
- **Kiln asks which panes to turn on.** After Home Assistant, Kiln's set-up shows the app's Panes step: one switch
  per pane the device can run, with each pane's icon, and Continue turns them on or off. It is the same answer as
  the Panes step on the device's own screen, and the same switches as the pane's settings, the Manage page and Home
  Assistant, so all of them agree. A pane whose hardware the device lacks isn't listed.
- **Themes for the voice overlay.** A new *Appearance* page in the panel's settings and on the Manage page picks one
  of five themes, Glassworks, Aurora, Dusk, Dune or Graphite, each light or dark. The voice overlay takes its
  colors: the orb glows in the theme's first color while it listens and its second while it speaks, and a light
  theme gives the overlay a light background with dark text. Done stays green and errors stay red. Glassworks dark
  is the default, so the orb now listens in blue rather than amber.
- **Pick your own theme colors.** The *Appearance* page now has a Custom theme next to the other five. Pick its
  accent, second accent and background, one set for light and one for dark, from swatches or by typing a hex code.
  The Manage page offers the same colors with your browser's color picker. Changing a color switches to Custom
  right away, and the overlay's text turns light or dark to stay readable on the background you chose.
- **Timers in your theme.** The timers on the dashboard and the timer done screen now take the theme from the
  *Appearance* page. The theme's two colors run down each timer's ring, the glass turns light with a light theme,
  and the done screen's orb and ripples glow in the theme's colors. The last 10 seconds stay amber in every theme so
  you can tell a timer is about to ring.
- **Recorders that record on motion get the camera's motion pushed to them.** With the ONVIF stream on, a network
  video recorder that subscribes to the camera's motion (many Xiongmai-based NVRs do) now hears about each change
  the moment it happens, so it can start recording. The recorder must be on your local network and sign in with the
  stream's username and password. Home Assistant's ONVIF integration can use this too, and then gets motion
  without asking for it.
- **Kiln installs Glassworks on Android devices for everyone, in beta.** Open `kiln.glassworks.dev/android` in
  Chrome or Edge: Kiln takes the newest release, with no `?apk=release` link needed. It is in Kiln's picker, marked
  Beta.
- **Reset the panel, and open or close setup over the network, from the Security page.** The panel's settings and
  the Manage page have a new *Security* page. *Reset the panel* clears the app's settings, its panes' settings and
  its Home Assistant sign-in, signs every browser out of the Manage page, and restarts into setup. It keeps Wi-Fi,
  the Log, the radio's settings (so its Zigbee or Thread network stays up) and the panel in Home Assistant. It
  asks first and says what goes and what stays. *Allow setup over the network* moved here from the dashboard's
  page, and *Close setup over the network* ends it before the 30 minutes are up. On the Manage page all three
  need the code the panel shows then.
- **Notifications and the Bluetooth proxy, in both builds.** Home Assistant can show notifications on the panel
  with the panel's notify action, and a device with Bluetooth LE can relay nearby BLE sensors to Home Assistant.
  Notifications are on by default, and the proxy is off until you turn it on. Either changes in the panel's
  settings, on the Manage page or from Home Assistant. A device
  without Bluetooth LE doesn't show the proxy anywhere. On a screen without touch, such as the lululemon Mirror, a
  high or critical notification with no `tag` and no `timeout` closes after 2 minutes, since nothing else could
  close it. On the Mirror the proxy scans only once the app has the location permission, which Kiln doesn't grant yet.
  If Home Assistant asks for Bluetooth data without the panel's encryption key, the proxy's settings page and its
  *Bluetooth proxy status* sensor say *Waiting for the panel's key*. Another app on your network that asks without
  the key doesn't change that.
- **Pick an update channel: Stable or Beta.** The device page in the panel's settings and on the Manage page has an
  *Update channel* row. Stable, the default, gets releases as before. Beta also gets betas, new versions before
  they're stable, which may have bugs; Home Assistant's update card says when the update it offers is a beta. Going
  back to Stable never installs an older version: the panel waits for a stable release newer than the one it runs.

### Fixed
- **Snapshot checks over ONVIF give the real size.** A recorder or tool that checks the camera's snapshot before
  downloading it now sees the picture's real size and no stray bytes, so its next request on the same connection
  works.
- **Setup stays on screen when the app restarts after finding Home Assistant.** Once the panel joins Wi-Fi and
  finds Home Assistant, the app restarts to apply it. The setup screen now comes back at once and says "Connecting
  to Home Assistant…" until it knows whether the panel needs to sign in, so Home Assistant's page no longer flashes
  before the sign-in step.
- **A device without a touchscreen keeps its screen on during setup.** On the lululemon Mirror and other devices
  without touch, the setup screen no longer goes dark while you set the device up from a computer.
- **Notifications on a screen without touch show nothing you would have to tap.** On the lululemon Mirror a
  notification showed Home Assistant's buttons, such as the test notification's OK, and after its card went away an
  "N notifications · Show all" pill stayed on screen for good. Now cards and critical alerts show without buttons
  or a close. A normal or low card goes away on its own and leaves nothing behind. A high or critical one with a
  `tag` stays until Home Assistant clears it, and one without closes after 2 minutes, as before. Do not disturb's
  pill only says it's on. Touch screens are unchanged.
- **The lululemon Mirror stays upright after an update.** Android turned auto-rotate back on with every update of
  the app, so the Mirror's dashboard came up sideways until someone fixed it over adb. The app now holds the Mirror
  in portrait itself, whatever auto-rotate says. Other devices turn as before.
- **The camera isn't restarted while it is still starting.** On the lululemon Mirror, turning the camera on could
  log "no frames" and open the camera a second time, so it took about 12 s to come up. A camera's first picture
  now gets 10 s from the moment the camera opens; "no frames" shows only after that. A camera that goes quiet
  later is still restarted after 5 s.
- **Settings > Log says what the screen senses in plain words.** *Screen and sensors* read like
  `presence=far (raw 7.7E-4) · lux=NaN · brightness=-1.00 · admin=true`. It now reads like *Someone near: no ·
  Room light: 120 lux · Brightness: 40 %*, with only what the device has: no room light without a light sensor, no
  one near without presence. The panel and the Manage page show the same. The raw values are in the app's log.
- **The Mirror's camera uses less memory while Home Assistant streams it.** On the lululemon Mirror the dashboard
  could reload every few minutes while Home Assistant streamed the camera, because the device ran out of memory. The
  camera now runs at most at 720p there, at a size its sensor takes directly, and the stream uses the device's own
  video encoder when it can, instead of encoding on the CPU.
- **Setup carries on after you sign in on the panel.** After *Sign in on this panel instead*, the dashboard showed for
  up to 2 minutes before setup came back on *Add it*, so setup looked finished. Setup now comes back as soon as you're
  signed in. While Home Assistant may still find the panel by itself, *Add it* says it's checking.
- **No log-access prompt on every start, on Android 10 and later.** The app asked for the system log for
  "Allow a new computer", which only works on Android 6 to 9. Where an install over adb granted it, Android 13 and
  later showed "Allow access to all device logs?" each time the app started. Now only Android 6 to 9 are asked.
- **Wi-Fi setup over Bluetooth works on the lululemon Mirror.** On a panel with no touchscreen, nobody could press
  Allow, so Home Assistant and phones could never send it Wi-Fi over Bluetooth. Now, when it starts with no network,
  Bluetooth setup is open by itself for 5 minutes, and the setup screen says to send the Wi-Fi from Home Assistant or
  improv-wifi.com on a phone, with the time left. Where the camera sees presence, walking up to the panel opens it
  again. It never opens while the panel is online. After the 5 minutes the panel opens its own setup Wi-Fi, if it
  can, and otherwise the setup screen says how to set it up from a computer.
- **The setup Wi-Fi opens on the lululemon Mirror.** The legacy build's setup screen said "This device doesn't let apps
  open a Wi-Fi hotspot." on the Mirror. Android 6 and 7 want one more permission for it, which the legacy build now
  asks for. An update is enough: no new flash. If Android still refuses, the reason is in the log.
- **Home Assistant's *Silent updates* follows within seconds.** After you allowed "Install unknown apps" for
  Glassworks, the panel and the Manage page said Silent updates was on, but Home Assistant's sensor could still say
  no for up to 15 minutes. It now changes when you come back from Android's settings, or as soon as setup, the
  panel's settings or the Manage page show the new answer.

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
