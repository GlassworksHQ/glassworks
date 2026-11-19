# Changelog

What changed in each S1 Kiosk release, for people running a panel. The first line of each version is
what Home Assistant shows on the update card. Releases are built from tags by
[`.github/workflows/release.yml`](.github/workflows/release.yml), which publishes the matching section
below as the release notes. Glassworks for generic Android and its legacy build have their own:
[frames/generic-android/CHANGELOG.md](frames/generic-android/CHANGELOG.md).

## [Unreleased]

### Added
- **Set up every panel from one Manage page.** *All panels*, in the Manage page's menu, signs in to
  Home Assistant and lists the house's panels with their area and version. A panel that's down shows as
  down. *Manage* opens another panel's page, already signed in, and *Copy settings* makes one panel like
  another: it changes only what you tick, and greys out what the other panel's version doesn't have.
  Administrators only. Each panel checks the sign-in with Home Assistant itself, so panels never trust
  each other. Every panel needs this version for the page to reach it.
- **Kiln's Done says whether the S1's updates install by themselves.** After an install or a setup, Kiln reads the
  panel back and shows a *Silent updates* block: *On* when the boot image has its root update service, or *A tap
  each* with how to add it when it doesn't. The lululemon Mirror's test install shows the same block, read from
  whether Glassworks went in as a system app.
- **Manage sends a test notification and shows the panel's notifications.** The Notifications page on the Manage
  page lists what is on the panel now, read-only, and has a *Send* button for the test notification. On the panel,
  *Send a test notification* is now a row with a *Send* button.
- **Setup says when updates would ask for a tap.** On a new setup of an S1 whose boot image has no update
  service, a *Silent updates* step follows Panes: Kiln adds the service from a computer, with a QR code to it.
  It's never required: Continue moves on. Panels that are already set up don't see it, and an S1 with the
  service, whose updates install by themselves, skips it.
- **The panel shows its frame version.** The S1 Plus page in the panel's settings and on the Manage page has a new
  *Frame version* row: the version of the parts of the panel Glassworks owns, such as its boot-side services and boot
  animation, not Android's. A panel installed before frame versions shows *Before 1.0.0*. A new *Frame update*
  entity in Home Assistant says when a newer frame version is out and how to install it. The first one comes with
  Glassworks 1.0.0, and a panel installed before it needs one update with Kiln to get it.
- **The Screen settings warn about a screen lock.** On a tablet with a PIN, pattern or password, Android locks
  it when the screen turns off, and someone has to unlock it. The panel's **Settings → Screen** and the Manage
  page now say so in amber, with the way out: turn the lock off in Android's settings, or keep the screen on.
- **Glassworks for generic Android gets the Diagnostics pane.** The app on tablets and the lululemon Mirror, in
  both of its builds, now sends its CPU, RAM, storage, Wi-Fi and uptime to Home Assistant, as S1 Kiosk does, and
  shows them on its Diagnostics page on the panel and on the Manage page. These devices have no CPU temperature
  sensor the app knows, so there is no temperature entity. On a narrow screen, such as the Mirror's in portrait, a
  pane's reading tiles now stack one to a row instead of cutting off their values.
- **Voice says when it can't get ready.** When the wake word model doesn't download (no internet, a
  certificate the panel can't verify, an HTTP error), fails its check, can't be saved, or the microphone
  doesn't work or isn't allowed, the Assist page on the panel and on the Manage page says so in words, with
  what to do and when the panel retries. A model or engine failure also gets a **Try again** button. A new *Voice status* sensor in Home
  Assistant reads *Ready*, *Download failed*, *Microphone unavailable* and so on, so you can see it on the device page
  or automate on it. A failed download is retried after 30 seconds, then less often, up to every 10 minutes.
- **The Bluetooth proxy can connect to devices.** Its settings page has a new choice: *Listen only*,
  as before, or *Listen and connect*. With the second, Home Assistant can connect through the panel to
  BLE devices that need a connection, such as locks, SwitchBots and sensors with a key, up to 3 at
  once. A *Connections* tile shows how many are in use. It stays on *Listen only* until you pick it,
  and the panel never pairs with a device.
- **Panel night mode, a blueprint for Home Assistant.** At night your panels turn their screen off
  sooner and keep it dimmer, and in the morning they go back to your day settings. Night can be two
  times, sunset to sunrise, or any condition Home Assistant can check, and one automation covers all
  your panels. It only switches a panel between your two values: at night one that is still at the
  day value, by day one still at the night value. A value you set by hand stays, unless it is one of
  those two. A panel that was off or restarting catches up within a minute of coming back.
  Add it from the panel's **Settings → Screen** or the Manage page. A new *Max auto brightness* number
  in Home Assistant sets how bright the screen may get, and *Idle timeout* is now on by default for new
  panels. Setting *Idle timeout* above 0 from Home Assistant also turns *Screen mode* back to presence,
  as the panel's own **Turn off when idle** does.
- **The panel opens its own Wi-Fi for setup when no Wi-Fi works.** With no connection 3 minutes after it
  starts, or when you press **Set up from a phone** on its setup screen, the panel opens a Wi-Fi network of
  its own for 15 minutes. Scan the QR code on its screen with a phone, open the address it shows, pair, and
  send your Wi-Fi from the setup page. It never runs while the panel is connected, and it closes when Wi-Fi
  is set, after 15 minutes, or with **Close it**; then the panel tries your Wi-Fi again. Android may close it
  sooner when nobody joins (10 minutes on the S1); it opens again 3 minutes later if no Wi-Fi works. With the
  panel's Wi-Fi turned off it doesn't open, since the panel couldn't turn Wi-Fi on to join: the screen says
  so, with **Turn on Wi-Fi**. On Android 13 and newer the panel asks for Android's *Nearby devices*
  permission first, and says why.
- **On the S1, joining the panel's own Wi-Fi brings up its setup page.** As with a hotel's Wi-Fi, an iPhone
  shows the panel's setup page as soon as it joins, with no address to type, and so does an Android 11 or newer
  phone that joined from its Wi-Fi settings. Android 10 shows a "Sign in to Wi-Fi network" notification to tap.
  Comes with the radio bundle.
- **See how complete Glassworks is on each device.** Every frame's page on glassworks.dev has a
  checklist under its Clarity grade: install, silent updates, hardware, kiosk, setup, recovery, branding
  and proof, each ticked or not with a short reason, and one word above it: Full, Good or Basic. Only the
  first five decide the word. The S1 is Good today: the wall base's buttons don't reach the panel yet.
- **The camera pane in Glassworks for generic Android, the Mirror's legacy build included.** On a device
  with a camera and no proximity sensor of its own, turn the Camera pane on and the screen wakes when someone
  walks up, with no image leaving the device in Presence only. A device without a camera gets no camera entities. On a screen without
  touch, such as the lululemon Mirror, setup never waits on the camera step: the use stays Presence only, and
  Kiln grants the camera. On a camera without 1280x720, such as Android 6-7's, the ONVIF stream runs at a size the
  camera offers. Where the panel can't encode that size, the Camera page says why and the stream stays off. Presence
  works either way. Not yet run on a real Mirror.
- **Kiln installs Glassworks on any Android tablet, as a test.** At `kiln.glassworks.dev/android?apk=local`, plug
  the tablet into a computer and Kiln names it, or asks for its brand, then shows how to turn on USB debugging on
  that brand. After you tap Allow on the tablet, Kiln installs the app, gives it what it can (microphone, screen on
  and off, the overlay, the device admin) and checks each one, makes it the home screen and opens its setup page.
  A tablet that refuses a step is told plainly, and undo removes the app. The APK is a test build you pick on the
  computer until Glassworks for Android has a release.
- **Get Glassworks on a tablet from the tablet itself.** Open `kiln.glassworks.dev/android/tablet` in the tablet's
  browser. Kiln says what the tablet is, even in Chrome's desktop mode, and offers Glassworks as a download: the
  build for its Android version, through Kiln's own address. It then says what the app's setup asks, and how to
  finish with Kiln over USB for the one setting that needs a computer. Until Glassworks for Android has a release,
  the page says it isn't out yet.
- **Know when the tablet is on its dock.** A Pixel Tablet now has a *Docked* sensor in Home Assistant: on
  while it sits on its dock, off when you lift it off. Automate on it, for example to
  show another dashboard while someone holds the tablet. The tablet's page in the panel's settings, and on the
  Manage page, says *Docked* or *Not docked* as it changes. Other tablets get both once they have been on a
  dock; the S1 and ordinary tablets have neither.
- **See whether updates install with nobody at the panel.** A new *Silent updates* sensor in Home
  Assistant says yes or no, and why: the S1's boot image installs them, the app is device owner or a
  system app, or Android 12+ lets it update itself. The device's page in the panel's settings and on
  the Manage page shows the same answer, and where updates ask for a tap, how to change that, such as
  allowing "Install unknown apps" for the app.
- **Every device says what kind it is, and the Pixel Tablet is listed.** On glassworks.dev and in Kiln's
  device list, each card now says whether it is a full frame with its own code (the S1), a device frame
  that runs the generic app with its own settings (the Mirror, generic Android), or a tested device that
  runs the generic app as it is. The Google Pixel Tablet is the first tested device. Its support is
  planned and not yet checked on a real unit.
- **See who is connected to the Zigbee radio.** In the Zigbee coordinator role, the radio's settings
  page says who uses the radio now, for example "Home Assistant · 192.0.2.20 · since 14:32", or
  "Nobody". It follows a connect or disconnect within a few seconds. Home Assistant has the same text as
  the *Radio connected client* sensor, off by default. Comes with the radio bundle.
- **Set the panel up from a laptop over Wi-Fi.** The panel serves its own setup page at
  `http://<panel>:8099/setup`: pair with the 6-digit code the panel shows, then the Home Assistant and
  radio steps, as in Kiln. No cable and no tap on the panel. Kiln's Done screen links it after an install
  over USB.
- **Allow setup over the network again.** Once Home Assistant has added the panel, nobody can pair with
  it from the network until you allow it for 30 minutes: a new **Allow setup over the network** button on
  the panel's device page in Home Assistant, the same row in the panel's settings (Home Assistant &
  dashboard) and on the Manage page, or Kiln over USB.
- **Kiln credits the projects an install is built on.** On the S1, the first screen, the install and
  the done screen say "Built on sunxi-tools by linux-sunxi and Magisk by topjohnwu", with links to
  both. The frames' pages on glassworks.dev have a "Built on community work" section, and the Mirror's
  card names the Glass Panes image it runs.
- **Pick the panel's time zone from the Manage page.** The time zone page in the browser now has
  what the panel's own page has: the zone, picked from a list grouped by region, whether the panel
  keeps it, and Home Assistant's time zone. When Home Assistant keeps another zone's clock, **Use it**
  sets that zone, as on the panel.
- **The Bluetooth proxy page on the Manage page.** The browser shows what the panel's page shows: whether
  it's listening, the advertisements a minute and the scanner, and its entities. When Bluetooth or
  location is off, **Turn on Bluetooth** or **Turn on location** works from the browser too.
- **Switch the wall base's relays from the Manage page.** The browser's wall base page has a switch
  for each relay and each button's last press, as the panel's page has. Without a base, it says what
  one adds.
- **Change the radio's role from the Manage page.** The browser's radio page has the role, what to do
  next for it, the Zigbee stack, and the role's buttons (Join, Leave, Get it from Home Assistant), as
  on the panel. Changing the role or the stack, and leaving a network, ask first with the same words.
  Pasting a Thread dataset stays on the panel.
- **Add a pane's Home Assistant blueprints from its settings page.** Notifications and Camera have a
  *Blueprints for Home Assistant* card: the doorbell, a question with buttons on the panels, lights on
  when someone is at the panel, and a snapshot to your phone when nobody is home. **Add to Home
  Assistant** opens Home Assistant's own import dialog in the dashboard, filled in; the panel itself
  never writes to Home Assistant. If the panel's Home Assistant user isn't an administrator, a QR code
  lets you add it from your phone. The Manage page has the same card, with links that open Home
  Assistant in a new tab. The doorbell blueprint moved to `panes/notifications/blueprints/`.
- **The panel's settings open on an Overview, laid out like the Manage page.** Tap the top-left
  corner five times and the menu shows whether the panel runs, its version and how long it has run,
  with Reload dashboard, Restart app and Check for updates. Tiles show Home Assistant, Wi-Fi, the
  engine and memory, and a list shows every pane with its state. The sidebar groups the pages under
  Panel, Panes and System, and Done stays at the bottom. "Dashboard" is now "Home Assistant &
  dashboard", on the panel and the Manage page, and it says which Home Assistant account the
  dashboard is signed in as. Each page shows its settings in cards, one per group, with the value
  on the right of its name, and "In Home Assistant" in a card of its own. On the Manage page, a browser signed in with the panel's code says so
  at the foot of the sidebar.
- **A page for the device itself in the settings.** Under Panel, the S1 has an "S1 Plus" page with
  Block Aqara cloud (moved there from the wall base's page; its Home Assistant entity is unchanged),
  whether Aqara's cloud daemon runs, which of Aqara's apps are turned off, and the radio's mode,
  which opens the Radio page. On another device the page is named after it and says whether app
  updates install without a tap, whether the app is the device owner, the model and the Android
  version. It's on the panel and the Manage page.
- **On Android 12 and newer, app updates from Home Assistant install without a tap.** On a device
  that isn't an Aqara S1, such as a tablet, pressing Install in Home Assistant now updates the app
  with nobody at the screen, once "Install unknown apps" is allowed for it. If Android still asks,
  *Update status* says "waiting for a tap on the screen". The first update to this version still
  asks once. The S1 is unchanged: it updates through its root service as before.
- **Change the panel's settings from the Manage page.** Every setting in the panel's own menu is on
  the Manage page too, in the same order and words, and you can change it there: switches, sliders,
  addresses, times and choices, and each pane's on/off, with either sign-in. The
  panel checks each value as its own controls would. The open settings menu on the panel follows a
  change made in the browser. A few parts only the panel can draw yet (the radio's roles, the time
  zone picker, the wall base's relays, the Bluetooth proxy's devices) say "On the panel".
- **On a device that isn't an Aqara S1, the app runs as that device.** On a tablet, for example, it
  names itself after the device in Home Assistant ("Pixel Tablet", `pixel-tablet-a1b2c3`) with its
  own maker and model. It runs the panes the device's hardware allows: Voice and Music with a
  microphone and a speaker, the Bluetooth proxy with Bluetooth LE, and Diagnostics. A pane the
  device has no hardware for isn't shown at all: no radio on a tablet, and the S1's own Wall base and
  Time zone aren't there. On the S1, only the names in
  Music Assistant and Improv change (below).
- **Every pane's settings page shows what it is doing.** No page says "no settings of its own" any
  more. Diagnostics shows CPU, temperature, RAM, Wi-Fi, storage and uptime, with the last hour as a
  line. Wall base shows the base, its firmware, protection and power, and each relay with its switch
  and its button's last press. Time zone lets you pick a zone from a list, or plain UTC offsets, and
  keep Home Assistant's in one tap. Voice has the wake word, its threshold, mute, mic gain, the wake
  sound and the overlay. Music shows what plays and its volume. Radio shows its firmware and the root
  helper. A pane with nothing of its own lists its entities as Home Assistant shows them.
- **A notification can open its view over half the screen, and views take turns.** Add
  `"size": "half"` to a notification with a `url`, and the view opens as a dialog on the right of the
  screen (at the bottom of a portrait one), with the dashboard dimmed behind it. A view that arrives
  while another is open now waits its turn instead of taking the screen. The bar's one button says
  *Next* while another waits. A critical notification with a `url` shows the alert first, with an
  **Open the view** button. The time-left bars on a view and on the cards slide smoothly instead of
  stepping each second. See [Open a view](docs/notifications.md#open-a-view).
- **Glassworks has its own icon.** The launcher, the home app chooser and Settings show the Glassworks
  mark on night, the brand's app icon, where they showed Android's green robot on Android 6 and 7. On
  Android 13 and later it follows themed icons. The notification shows the mark in white.
- **Glassworks for Android 6 and 7 tablets and the lululemon Mirror.** Devices on Android 6.0 to 7.1 get
  a legacy build of Glassworks for generic Android, a separate app with its own updates. Kiln knows which
  build a device needs and refuses the other one, and the legacy build is what it installs on the Mirror.
  It runs a frozen web engine that gets no more security fixes, so it opens only your Home Assistant: maps,
  iframe cards and anything else from another address stay blank, with a note. There is no release yet;
  Kiln installs it as a test, from an APK you pick.
- **Dashboards can read the panel's state as `window.glassworks`.** The presence, light and screen state a
  dashboard sees now has the Glassworks name, with a `glassworks-state` event when it changes. The old
  `window.s1kiosk` and `s1kiosk-state` keep working, so existing dashboards need no change.
- **The Manage page says what the legacy build can't do.** On Android 6 and 7 devices and the lululemon
  Mirror, the Overview has a *Legacy* chip next to the version. Hover it, tap it or tab to it: its web engine
  is frozen and gets no security fixes, so it loads only your Home Assistant, and remote debugging is off.
  Remote debugging now stays off there for real: the adb extra that turns it on is ignored, and so is the
  setting if it was already on. Other devices don't show the chip.

### Changed
- **A room's "lights off" no longer turns the panel's screen off.** The screen is still a light with the same
  entity id, but Home Assistant now lists it under the device's Configuration. Actions aimed at an area, floor or
  device skip it, so a presence automation's "room empty, lights off" and a Good Night script leave the panel
  alone. So does Assist's "turn off the bedroom lights" on a panel Home Assistant adds from now on. On a panel it
  already has, Assist still sees the screen until you turn off its **Expose** toggle once in **Settings → Voice
  assistants → Expose**. Dashboards, automations and scripts that name the screen entity still work, and so does a
  label you put on the screen itself. Home Assistant picks this up after a restart, or a **Reload** of the panel's
  ESPHome entry, once the update is in.
- **S1 Kiosk is now Glassworks.** The panel's app shows as Glassworks, with the Glassworks icon, in the launcher,
  in Android's Settings → Apps, in About and in its notification. In Home Assistant the device's model reads
  *Panel Hub S1 Plus (Glassworks)* and the update card is called *Glassworks*. The rename keeps the device, its entities and
  their ids, and the update installs over the old app with its settings. With the radio as a Zigbee
  router, ZHA reads the maker as Glassworks after it interviews the panel again.
- **The screen stays on until you pick a timeout.** A panel that never chose a screen mode now keeps its screen
  on (*Screen mode* `always`), so an S1 that never chose stops turning off after the update. To have it turn
  off again, pick a time in **Settings → Screen → Turn off when idle**, turn on **Wake when someone's near**, or
  set *Screen mode* or *Idle timeout* from Home Assistant. A choice you made stays as it is. While the screen is
  always on, **Turn off when idle** reads *Never*, also on the S1, and Home Assistant's *Screen* light can still
  turn it off on a device without device admin.
- **The setup screen's Wi-Fi step leads with one way to join.** It shows the best way the panel can do
  right now: Bluetooth setup from Home Assistant, else **Set up from a phone** through the panel's own
  Wi-Fi, else **Choose a network** to type it on the panel. The other ways are one tap away behind
  **Other ways to set up ›**, and **Not now** is the quietest button on the card. The step now fits on a
  small phone too. A panel without touch (the Mirror) looks as before.
- **Kiln's Mirror flash leaves out apps a Mirror never uses.** The image Kiln writes no longer has
  36 of the community image's apps: the phone and SMS apps, Qualcomm's diagnostic and modem services,
  demo apps and Bluetooth test tools. They kept restarting and took memory the dashboard needs. The
  phone stack, the keyboard, the display's own services, Bluetooth and Settings stay. Kiln's way back,
  the plain community image, brings them all back. Not yet run on a real Mirror.
- **Home Assistant shows only the screen entities your device can back.** *Presence range* is only on a
  device whose presence sensor has a range (the S1), and *Auto brightness*, *Illuminance* and *Max auto
  brightness* only on one with a light sensor. On a tablet with a camera, presence is the Camera pane's
  *Camera presence*. Home Assistant removes the ones a device can't back on the next reconnect after the
  update; if the hardware comes back, so does the entity, usually with its old id. An automation that used
  one stops working. Nothing changes on the S1.
- **Setup signs in to Home Assistant without leaving you in another tab.** On the panel's setup page (`:8099/setup`),
  **Sign in** now takes you to Home Assistant's sign-in in the same tab and brings you back to the setup
  page at the next step, instead of leaving you in a second tab. Kiln over USB signs in in a small window
  that closes by itself, and Kiln moves on to the radio without a click. The Home Assistant step offers one
  way on: **Sign in** until the panel is signed in, then **Continue**; "Skip, sign in on the panel" keeps
  the address and leaves the sign-in to the panel's screen. The list of steps shows only the steps
  your panel has: a panel without a radio no longer lists a Radio step it skips.
- **The S1's Bluetooth proxy goes by the panel's Bluetooth address.** The proxy now tells Home
  Assistant the panel's own Bluetooth address, as ESPHome proxies do, instead of its network address.
  Home Assistant moves the panel's scanner to the new address by itself, once, with nothing for you to
  do. If the panel can't read its Bluetooth address (Bluetooth never turned on yet), nothing changes
  until it can. Going back to an older version moves the scanner back to the network address. Comes
  with the radio bundle.
- **The panel's own night setting is gone: Home Assistant decides when it is night.** The **At night**
  window (22:00–07:00) and **At night, turn off after** (20 s) are no longer in the settings, on the
  Manage page or in Home Assistant (*Night window*, *Night idle timeout*). After the update a panel
  keeps the same idle timeout day and night, and brightness is no longer capped at 20% at night, even
  if you had changed those settings. To get them back, add the **Panel night mode** blueprint from
  **Settings → Screen** ([how](docs/blueprints.md#panel-night-mode)). On a panel Home Assistant already
  has, turn its *Idle timeout* entity on first. Your other screen settings stay as they were. Dashboards
  that read `window.s1kiosk.night` get nothing there now.
- **The panel's own Wi-Fi says when it is starting, then shows the setup page's QR code once a phone is on it.**
  **Set up from a phone** now shows "Starting the setup Wi-Fi…" at once, where the screen used to sit unchanged
  for several seconds. A start that fails says why in the same place, with **Try again**. Once a phone on the
  panel's Wi-Fi opens a page on the panel, the QR code changes to the setup page's address, so nobody types
  it. The panel's logs no longer name the Wi-Fi network you pick, nor the panel's own network and
  its password.
- **The panel's setup page now looks like the panel, not the installer.** The page the panel serves at
  `http://<panel>:8099/setup`, also reached from a phone on the panel's setup Wi-Fi, now has the panel's
  setup-screen look: the Glassworks name and stack, a night background, and one white button. It no longer
  shows Kiln's name or its orange colours. The steps and words are the same.
- **Screen settings show only what the device has.** On a tablet or the Mirror, the Screen page in the
  panel's settings and on the Manage page no longer shows the S1's presence range. *Wake when someone's
  near* shows only with a sensor that can wake the screen: a proximity sensor, or the Camera pane, and
  where the camera could do it but its pane is off, the row says so and opens it. *Brightness* needs a
  light sensor, and a device without touch has no link to Android's settings. Without presence, *Turn off
  when idle* is the screen's one on/off: it says Never while Home Assistant's *Screen mode* keeps the screen
  on, and picking a time there turns that off. The S1's page is unchanged.
- **On a device that isn't an Aqara S1, the app follows the device's rotation; the Mirror stands portrait.**
  The app turns with Android's auto-rotate, or stays as you locked the screen, where it was always landscape.
  A Pixel Tablet stays landscape, as on its dock. The lululemon Mirror stands portrait, as its Android does.
  The S1 stays landscape. On a portrait screen the setup screen stacks into one column: the steps above, the
  card under them, its QR code under the text.
- **Kiln's technical log words the boot patch differently.** Kiln's boot patcher was rewritten, so the
  patch step now logs a few short lines such as "boot image: page 2048, gzip ramdisk, AVB footer"
  instead of Magisk's own messages. You see them under **Technical log** and in a saved failure
  report. The patched boot image is the same, byte for byte.
- **The panel's secrets are encrypted on its storage.** The Home Assistant sign-in, the ESPHome API
  key, the camera password and the Sendspin pairing are sealed with a key in Android's Keystore, so a
  copy of the app's settings files holds none of them. The update keeps them all: the panel stays
  signed in and paired. If the Keystore is unavailable when the updated app first starts, the secrets
  stay in plaintext until a later start can seal them. Installing an older version afterwards means
  setting those up again.
- **The camera stream's password is never shown again once saved.** The panel's settings and the
  Manage page no longer have a Show button for it, and the panel no longer makes a password for you.
  You type your own when you turn the stream on, so you know it for Home Assistant's ONVIF
  integration, Frigate or Protect. To change it, type a new one; **Clear** removes it and closes the
  stream until you set another. A panel that already had a stream keeps streaming, but its password
  can no longer be shown; set a new one if you need to type it into Home Assistant.
- **Generic Android: the launch screen shows the Glassworks mark instead of a plain app icon.** On
  Android 12 and newer, such as a tablet, the screen Android shows while the app starts is the
  Glassworks stack on the app's dark background, and it fades into the connecting screen. Where the
  app also stands in for the launcher before the first unlock, that screen no longer names the
  Aqara S1. The S1 is unchanged.
- **The panel and the Manage page look the same.** Both read one set of Glassworks colours and
  shapes: the status chips, switches, rows and dialogs match, and the Manage page's menu folds the
  panes you turned off under "Off · N", as the panel does. On the panel, hairlines and tile corners
  move to the brand's values.
- **Music Assistant and Improv use the panel's own name.** The Music Assistant player and the Wi-Fi
  setup over Bluetooth now say what the device is, "Aqara S1 Plus" on the S1 (the name Home Assistant
  shows), instead of "Glassworks Panel". So an S1 and a tablet no longer show up under the same name.
  Two S1s still share it: rename one in Music Assistant's player settings.
- **Panes you turned off fold at the bottom of the list.** In the settings menu they sit under
  "Off · N"; open it, or a pane in it, to see them. An off pane's page says what being off means.
  Every pane shows its state after its name, Running, On, Off or Can't run, as the Manage page does,
  so every pane's name starts in line.
- **Turning a pane on or off from Home Assistant needs the panel's key.** The pane switches, the
  *Music Assistant player (Sendspin)* switch included, only answer an encrypted connection, as *Time
  zone* does. Home Assistant 2026.11 and later sets the key when you add the panel. On a panel without
  one, use the panel's settings.
- **The project moved to [GlassworksHQ/glassworks](https://github.com/GlassworksHQ/glassworks).**
  Panels now check for updates there directly. Older versions keep updating through GitHub's
  redirect, so nothing needs doing on your panel.
- **The wake word uses far less CPU.** The panel now listens with microWakeWord, the engine Home
  Assistant Voice PE uses, instead of openWakeWord. On the S1, the microphone's thread went from
  about 37% of a core to about 4%. Your wake word stays as it was (Hey Jarvis, Alexa or Hey
  Mycroft), and Okay Nabu is new. The panel downloads the new models itself once. *Wake word
  threshold* now means the new engine's cutoff: each word starts at its own (0.9 to 0.97), lower
  is more sensitive, and a threshold you set for openWakeWord doesn't carry over. The old
  openWakeWord words stay in the list as "(openWakeWord)" for this release.
- **Kiln's panel cards show a real Home Assistant dashboard.** Each device on kiln.glassworks.dev now
  shows the same made-up home, drawn by Home Assistant's own frontend and filling that device's
  screen: the clock and weather, nine rooms and areas with thermostats, lights, music and TV, two
  cameras, a shopping list, the alarm and the day's graphs. Before, it was a sketch with a fixed date. The
  Mirror's is portrait.
- **A new boot screen.** The power-on logo and the boot animation now show the Glassworks mark:
  three glass plates drop into place, the top one lights up from sky to violet, and "Glassworks" and
  "On Aqara S1 Plus" fade in. While the panel finishes starting, the stack floats and the top plate
  breathes. It reaches a panel when you run the installer again.
- **New connecting and setup screens.** They use the Glassworks look: the glass stack tells you what
  is happening. Light rises through it while the panel connects, the plates dim when there is no
  network, and the top one turns red and dashed when Home Assistant doesn't answer, with a countdown
  to the next try. The connecting screen starts on the boot animation's last frame and the stack
  settles from there, so power-on is one continuous picture. It stays until Home Assistant has drawn
  the dashboard, so Home Assistant's white loading page no longer flashes in between. Setup goes
  Wi-Fi, Home Assistant (add it, then sign in), Panes, Done. On Panes you choose what the panel
  does: voice, music and the Zigbee / Thread radio. A pane you turn on then asks what it needs: the
  radio asks Zigbee or Thread and the role, then shows what to do in Home Assistant, where a Thread
  router can join Home Assistant's Thread network. A step that is already done says so with
  Continue, and the list on the left opens a step you've done.
- **Settings apply as you change them.** The settings menu has the new look: one section at a time,
  each setting with its value under it. A switch takes effect when you tap it, and a value when you
  save it. There is no *Save & restart* any more; the few changes that need the app to restart (the
  dashboard's address, zoom, secure hosts) say so and offer *Restart now*. The list on the left is
  the same as setup's: Dashboard, Screen, then your panes (Voice, Music, Radio) under Panes, then
  Log and About. Each pane has its on/off at the top right of its page; turning the radio off asks
  first. The radio's roles are picked as in setup, Zigbee or Thread first. On the Screen page the
  presence range is a slider, and the idle times and the night window are picked, not typed. About
  shows the version; the screen and sensor line moved to Log.
- **The panel is called Glassworks Panel, in Home Assistant too.** A panel you already added keeps all
  its entity ids, so automations and dashboards keep working; Home Assistant shows the device under
  the new name after the update, unless you renamed it yourself. On Home Assistant 2025.12 or later,
  new entities take the device's name as Home Assistant shows it (`glassworks_panel`, or your own
  name), and from 2026.8 the area's name can come first. On older versions they still start with
  `s1_panel`.
- **New pages on your phone when you scan the panel's code.** Signing in and sharing your Thread
  network open a short page first that says what happens, then *Continue to Home Assistant*. They
  follow your phone's light or dark mode.
- **A shorter entity list in Home Assistant.** When Home Assistant adds a panel, it now turns on 15 of
  its 63 entities, down from 55: presence, light level, the screen, the speaker, screen mode, mute,
  reload and restart, app updates, IP address, radio status and four health values (plus a wall
  base's relays, buttons, power, energy and protection). Settings you set once, the radio's controls
  and the troubleshooting sensors are created turned off. To use one, turn it on from the panel's
  device page in Home Assistant; many settings are also in the panel's settings menu. A panel Home
  Assistant already has keeps its entities as they were; only *Base protection* moves to the
  Diagnostic section.

### Added
- **A camera pane for panels with a camera.** On a tablet with a camera (not the S1), the panel can
  wake its screen when someone walks up, using motion that ignores lights switching on, dusk and TV
  light; no image leaves the panel for that (*Presence only*). With *Presence + camera* you can also
  let Home Assistant take snapshots, and turn on an ONVIF and RTSP stream (ports 8554 and 8000) for
  Home Assistant's ONVIF integration, Frigate or UniFi Protect. The stream needs a sign-in you
  set yourself, on the panel or on the Manage page. Everything is off until you turn it on, and
  *Camera privacy* closes the camera. The camera has its own page in the panel's settings, with the
  panel's address and the stream's URLs, and a Camera step in setup that asks Android for the
  camera. Whenever images leave the panel, Android 12 and later show their own camera indicator, and
  older Android a small green dot in the top-right corner. On the S1, which has no camera, the
  pane isn't shown, and Home Assistant gets nothing new.
- **The panel can be a Bluetooth proxy for Home Assistant.** Turn on *Bluetooth proxy* in the
  panel's settings or on setup's Panes step, and Home Assistant finds it by itself and hears the
  Bluetooth thermometers, plant sensors and tags near the panel. Devices that need a
  connection, like locks, can use it too with *Listen and connect*. Off by default. It never logs or keeps the
  addresses it hears, and sends them only over an encrypted connection with the panel's key, which
  Home Assistant 2026.11 and later sets when you add the panel. If Home Assistant connects without that
  key, the proxy's settings page and its *Bluetooth proxy status* sensor say *Waiting for the panel's
  key*. Another app on your network that tries without the key doesn't change that.
- **Setup's Panes step scrolls when there are more panes than fit.** Continue stays where it is,
  below the list, and every pane's name shows in full.
- **Notifications from Home Assistant on the panel.** Call `esphome.<panel>_notify` with a title, a
  message and the Companion app's fields, and the panel shows a card over the dashboard, with a
  camera's picture, up to three buttons and a sound. A button press fires `esphome.notification_action`
  in Home Assistant. A high or critical one wakes the screen, `url` opens a dashboard view full screen
  and comes back after the timeout, and *Do not disturb* holds everything but critical. Off until you
  turn it on, on the setup screen or in the settings. A doorbell blueprint is in the repo. Examples:
  docs/notifications.md.
- **Turn any pane off, and back on, without a restart.** Voice, music, the wall base, the time zone
  and diagnostics each have an on/off: at the top of the pane's page in the panel's settings, in
  Home Assistant (new switches Voice, Wall base, Keep time zone and Diagnostics, off on the device
  page until you turn them on there), and with adb. A pane that is off stops its work and comes
  back when you turn it on. Voice no longer restarts the app. The wall base now has its card on
  setup's Panes step. The radio's on/off stays its mode.
- **A Manage page for the panel, in your browser.** Open the panel's device page in Home Assistant
  and click *Visit*, or go to `http://<panel>:8099/`. Sign in with a Home Assistant administrator, or
  with a code the panel shows when Home Assistant is down. It shows how the panel is doing and which
  features run, keeps a log across reboots, downloads diagnostics with tokens and keys removed, and
  can reload the dashboard, restart the app, reboot (with the code on the panel) and check for
  updates. Settings show there but are changed on the panel for now.
- **Kiln shows what the panel is doing while S1 Kiosk goes in.** On kiln.glassworks.dev the app's
  install now lists each step as the panel goes through it: S1 Kiosk and the on-screen keyboard
  going in, the home screen, your settings, and the first start. When it updates an app that's
  already on the panel, it says the app restarting meanwhile is expected. The Wi-Fi step says
  *Connected to <network>* when the panel is on Wi-Fi already, with another network as the other
  choice. Back and the step list on the left go back through the
  set-up steps without losing what you entered.
- **Kiln's picker lists Generic Android as planned.** Next to the S1 and the Mirror, a card for any
  Android tablet or panel, which will install as a normal app without root. It can't be picked yet.
- **The panel's health shows in Home Assistant.** The device page's *Diagnostic* section gets CPU
  usage, CPU temperature, Wi-Fi signal and uptime, so you can graph them and alert on them. More
  sensors are there but off until you turn them on: the app's own CPU and RAM, free RAM and storage,
  Wi-Fi link speed, the app's uptime, its web content processes and why it last restarted. The panel
  reads them once a minute and sends a value only when it changed.
- **Releases now include an APK for 64-bit Android devices.** Each release carries
  `s1-kiosk-arm64-v8a-<version>.apk` beside the S1's APK, for arm64-v8a tablets and similar devices.
  It is experimental and has only run on an emulator. S1 panels keep updating from
  `s1-kiosk-<version>.apk` as before.
- **The panel gets your Thread network from Home Assistant**, instead of you copying the dataset.
  *Get it from Home Assistant* on the panel's Thread router card (setup screen and settings menu), or
  the *Join Home Assistant's Thread network* button on its device page, reads your preferred Thread
  network from Home Assistant and makes the panel a Thread router. Home Assistant only gives a Thread
  network's settings to administrators, so unless the panel is signed in as one, it shows a QR code:
  scan it and sign in as an administrator on your phone. The panel uses that sign-in once and deletes
  it straight away. The *Thread join* sensor says how it went. Pasting the dataset still works.

### Fixed
- **With Screen mode set to always, walking up no longer turns on a screen you turned off.** If you turn the screen
  off from Home Assistant, it stays off when someone comes close, whether the panel senses them with its own sensor
  or with the camera pane. *Presence* in Home Assistant still turns on. In the presence mode, walking up still wakes
  the screen, as before.
- **Undo and a failed switch say what really happened.** When the installer gives the panel back to
  Aqara's apps, it now says whether Glassworks was removed, turned off, or is still on because Android
  refused to turn it off. Before, it could say Glassworks was gone when it wasn't. When a switch to a
  new build fails and the old build comes back, the installer says so if the app's data didn't come back
  with it, and keeps the copy on the panel instead of deleting it. It no longer says Glassworks saved the
  panel's Wi-Fi when it can't tell who did. This is in Kiln and in the command line installer.
- **Switching a development build to a release keeps you signed in to Home Assistant.** On a panel where adb has
  root, the switch keeps the app's settings, and now its Home Assistant sign-in and encryption key too: the
  installer reads them from the running app before it removes it, and gives them to the new one. The panel goes
  straight back to its dashboard, and Home Assistant reconnects without asking to confirm the key. Before, the
  sign-in and key were lost, because Android deletes the old app's Keystore keys, which seal them; the installer
  even said the sign-in was kept. The camera's stream password and the Music Assistant pairing are still not
  carried, and the installer says so. A switch from a build without this change still loses the sign-in (and,
  before the Mirror key hand-over, the key), and the plan and the Done line say to sign in again. This is in Kiln
  and in the command line installer, and needs this app version on the panel before the switch. A build older than
  the panel's is now refused with a clear message instead of a plain install failure.
- **A Mirror reflash keeps its Home Assistant device.** Kiln and the headless flash give the Mirror an id from its
  serial number and keep it in the image, so a later reflash comes back as the same device with the same entity
  ids. The first flash with this still shows up as a new device once. On other Android devices, an app reinstall
  now keeps the device where Android allows it. There too, the first reinstall after this update can show up as a
  new device once.
- **A Mirror reflash with the headless flash needs no click in Home Assistant.** The flash reads the key Home
  Assistant set on the Mirror before it wipes it, and gives it back to the app on the first boot, so Home
  Assistant reconnects by itself instead of asking to re-authenticate. The Mirror needs an app with this change
  before the flash.
- **A Mirror reflash with Kiln needs no click in Home Assistant either.** On a Mirror that already runs Glassworks,
  Kiln has a step before fastboot: switch the Mirror on, click Connect, and Kiln reads the key Home Assistant set,
  restarts the Mirror into fastboot itself, and gives the key back at the first start. No buttons to hold. The key
  stays in the browser tab, never saved or shown, so keep the tab open until Done. Done says whether Home Assistant
  kept the same device. Only the browser that last installed Glassworks on the Mirror gets in: from another one, the
  reflash still works and Home Assistant asks once, and Kiln says what to click. The Mirror needs an app from this
  release or later before the flash. This keeps the device from the second flash that gives the Mirror its id from its
  serial: the first one still shows up as a new device once, as above. Not yet run on a real Mirror.
- **Assist's reply stays on a narrow screen.** On the Mirror, which stands portrait, the reply ran off
  both edges of the screen on one line. It now wraps inside the screen and gets smaller for a long
  reply, with room for up to five lines before it is cut short. The S1 and landscape tablets look as before.
- **The Manage page can pick what the camera may do.** Its camera page showed the use as "On the panel",
  which a panel without touch, such as the Mirror, can't offer: turning on the ONVIF stream ended at
  "Needs Presence + camera." It now has the panel's two cards, *Presence only* and *Presence + camera*,
  on every panel, and the Log says who changed it. "Needs Presence + camera." links to them.
- **Setup's Home Assistant step: sign in first, then add it.** The two sub-steps now always read *Sign in*,
  then *Add it*, and the marker sits on the one the card shows. Before, the screen could show the sign-in
  under *Add it*, and after signing in *Sign in* turned green while *Add it* stayed current. If Home
  Assistant added the panel before the sign-in, *Add it* shows green and *Sign in* is current.
- **A screen without touch waits for Home Assistant after phone setup.** On the Mirror, the setup screen
  kept showing "Scan to set it up." after the phone signed in. It now says it is waiting for Home
  Assistant to add the panel, and where: Settings → Devices & services, or the old entry's
  re-authenticate after a reinstall. When the setup screen comes up on a device with no touch and no
  presence sensor that Home Assistant hasn't added yet, it also turns the screen on. A set-up panel that
  loses Wi-Fi stays dark.
- **The Bluetooth proxy says when it can't run.** With the pane on but Bluetooth or location off, or
  the location permission missing, the settings menu and the Manage page said it was running. They
  now say *Can't run* and why.
- **The lululemon Mirror shows in Home Assistant as the Mirror.** Glassworks for generic Android used to
  name it after the board its image reports, `msm8916-for-arm64`. Now HA finds it as `lululemon-mirror-…`,
  named "lululemon Studio Mirror", made by lululemon. A Mirror HA already has keeps its entry and entities and
  takes the new name on its next connection. Any other device keeps its own name.
- **The panel asks to sign in again when Home Assistant gets a new address.** If your router gave Home
  Assistant a new address, or you turned on https, the dashboard showed Home Assistant's login page, which a
  panel without touch can't use. Now the setup screen shows the sign-in code and says Home Assistant moved.
  The panel never sends its sign-in to the new address, and the phone's setup page and Manage say it isn't
  signed in.
- **Phone setup finishes on a device without a radio.** On the Mirror and other Android devices, the
  setup page on the phone stopped after sign-in with "The panel stopped answering". It now skips the
  radio step there and goes to Done.
- **No permission prompt the panel doesn't need.** At start, the app asks Android for the microphone only while
  Voice is on, and for the camera only while the Camera pane is on. On a screen without touch, such as the
  lululemon Mirror, it never shows Android's prompt: Kiln grants the permissions at install.
- **Home Assistant's images show on Android 6 and 7.** On the lululemon Mirror, image entities and other
  pictures from Home Assistant stayed empty when the dashboard was at an `http://` address, and the panel
  said it had blocked content from Home Assistant's own address. The panel's frozen browser engine asked
  for those pictures over https, which Home Assistant doesn't serve. It now asks over http, as the page
  does. If something from Home Assistant's address is ever blocked again, the panel names it with its
  `https://`, so it doesn't read as your Home Assistant.
- **A Mirror flashed by Kiln comes up in portrait, upright.** Its first start came up sideways, in
  landscape. Kiln now turns the screen to portrait over USB on the first start, checks that Android
  did, and stops with a clear message if it didn't. The Mirror's install is still test only.
- **Wake word models and updates download on Android 6 and 7.0.** The lululemon Mirror and other Android
  6–7.0 devices could not download the wake word models, and would have failed to download an update:
  their built-in list of trusted certificate authorities is missing the one GitHub's downloads use. The
  app now adds that one certificate authority on those Android versions, and checks certificates as
  before. Android 7.1 and newer are unchanged. A device without the fix can't download it either, so
  install this version with Kiln over USB.
- **Glassworks starts on Android 6 and 7.** On its first start on the lululemon Mirror (Android 6), the
  app's legacy build crashed six times in a row, and Android stopped it and asked for a home app. Its
  notification used the app's icon, which only Android 8 and later can draw. The notification now has its
  own icon, and every icon the app shows has a version Android 6 can draw.
- **The Manage code shows on Android 6 and 7.** On panels older than Android 8, the code a phone asks
  for on the setup page never appeared, so setup couldn't go on. It does now.
- **A screen without touch stays on setup when it has no network.** A Mirror with no Wi-Fi saved used to
  leave the setup screen for one that said "Wi-Fi is off", which was not true, with a "Set up Wi-Fi"
  button that nobody could press. It now stays on the setup screen, which says how to set it up from a
  computer. On every panel, the offline screen says "Wi-Fi is off" only when Android says so.
- **The panel's "Wi-Fi setup · done" card says how the Wi-Fi came.** It showed the Bluetooth mark whenever the
  panel came online while Bluetooth setup was open, also when the Wi-Fi came from the setup page on a phone. Now
  Bluetooth shows only for Wi-Fi sent over Bluetooth; Wi-Fi from the setup page, from Kiln or from Android's
  settings shows a Wi-Fi mark and says where it came from.
- **The phone's setup page no longer waits for news that can't come.** Once it sends your Wi-Fi over the panel's
  own Wi-Fi, the panel closes that network within moments, and the page with it. The page now says so once:
  sent, watch the panel's screen, and put the phone back on your own Wi-Fi, instead of steps that seemed to
  still be updating.
- **A Mirror flashed by Kiln lets in only your browser's key, not the shared project key.** The Glass
  Panes image lets one adb key in without asking: the project's own key, published with its private
  half, so anyone with a USB cable could control the Mirror. Kiln now puts your browser's key in its
  place when it builds the image. Before, Kiln's own key never got in, and the install would have
  stopped at "Wait for adb". The Mirror's install is still test only.
- **A `homeassistant.local` dashboard loads on Android 6 to 11.** Android before 12 can't look up
  `.local` names, so a dashboard set as `http://homeassistant.local:8123` never loaded on the Mirror,
  and it kept saying it was trying again. The panel now loads it through the address where it found
  Home Assistant, and the setting stays as you entered it. If that address changes, the panel looks for
  Home Assistant again. An `https://` address on a `.local` name still loads as set, since its
  certificate is for the name. Android 12 and later are unchanged.
- **A panel with no presence sensor stays on until it is set up.** The Mirror turned its screen off 20 s
  after it started at night, while setup or the connecting screen was up, and nothing could wake it.
  The screen now stays on until setup is done and the dashboard has loaded, at its usual brightness.
  After that the idle timeout works as before. Panels with a presence sensor, such as the S1, are
  unchanged.
- **Kiln's device cards stay still with reduced motion.** With "reduce motion" on in the operating
  system, a card no longer jumps up when you point at it or tab to it.
- **The Overview's pane list reads cleanly.** On the Manage page, each status chip has its gap
  between the dot and the word again ("● Running", not "●Running"). The radio's line says its role once,
  in plain words: "Thread router (repeater) · not on a Thread network yet" instead of repeating the role
  after "ok". The same line shows on the panel's Overview. The radio's `Radio status` entity is unchanged.
  On a phone, the Manage page's menu shows the "Off · 1" group of turned-off panes on one line again,
  instead of squashed into a narrow column over "System".
- **A pane's "In Home Assistant" list says when Home Assistant doesn't have the panel.** On a panel
  Home Assistant never added, it says so once, with where to add it (Settings → Devices & services),
  instead of "Unknown" on each row. When Home Assistant was added but isn't connected now, it says so
  and dims the last known states. On the panel and the Manage page.
- **The Manage page shows the right uptime, Wi-Fi and version.** "Up" next to the app's version is
  now how long the app has run, not the device. Wi-Fi reads "Connected" while it is, also on newer
  Android that hides the network's name from the app; Home Assistant's Wi-Fi signal and link speed
  read the same link. A test build says so next to its version ("0.4.3 · test build 28c157d").
- **The screen stays on after Home Assistant wakes it.** After the panel had gone dark on its own,
  a wake that wasn't a touch (Home Assistant's screen switch, a timer, voice, Wi-Fi setup) turned the
  screen off again after about 20 seconds. Now it stays on until the panel's idle timeout, as after
  a touch.
- **An update or app restart no longer leaves the panel crash-looping for minutes.** Android 10 on
  the S1 has a bug that shows after about two dozen restarts of the app: from then on, no app can
  start, and the screen stays black or white until Android restarts itself, after anything from a
  second to about 11 minutes. The panel now notices within 30 seconds and restarts Android's
  framework at once, which takes about a minute. An update that was interrupted this way now checks
  whether it went in, instead of reporting an error. It reaches a panel when you run the installer again.
- **A pane you turn off stays off after a restart.** Turning voice or music off, then restarting
  the app at once, could bring the pane back on: the setting was saved after the app had gone.
  Zoom, secure hosts and remote debugging, which restart the app, could lose their change the same
  way. They are now saved before the app restarts.
- **Keeping the radio Off in Kiln finishes the panel's radio step.** A fresh panel installed with
  Kiln no longer asks *Radio* again on its own screen when you left the radio Off. Choosing the mode
  the radio already runs doesn't restart it. Needs the S1 Kiosk release with this fix.
- **Kiln's Done always offers your backup.** After an install, Done shows the backup's download even
  when set-up ran after leaving and coming back to the wizard, or after a reload. Before, it could
  show no download, and the backup was only in that browser. When a browser blocks site storage,
  Kiln no longer says "nothing was written" after a reload: it says it can't tell. Every screen now
  has one main button, the recovery steps no longer look like buttons ("Remove the back cover"), and
  the ends of the main buttons are no longer cut off. For a Thread router, Kiln points you to the
  panel's *Join Home Assistant's Thread network* when its S1 Kiosk has it, instead of copying the
  dataset; with an older S1 Kiosk, copying stays, under *More help*.
- **Undo no longer puts our splash back as Aqara's.** If a panel's `boot` was put back without its
  splash and the panel was then installed again, that install's backup held a stock `boot` with our
  power-on splash, and undo restored our splash while saying the stock one was back. Both installers
  now refuse such a backup before writing anything, and point you to the first install's backup or
  the by-hand undo in frames/aqara-s1-plus/docs/install.md.
- **A Thread dataset typed in the settings menu no longer shows an error.** After you pasted the
  dataset under the radio's *Advanced* and the radio took it, the menu said "The kiosk service isn't
  running yet" instead of "Dataset handed to the radio". The dataset was stored either way; now the
  menu says so.
- **Kiln: Undo and Try again say the right thing in rare cases.** On kiln.glassworks.dev, once a
  try has written to the panel, or given it back to Aqara's apps, a later try that fails before
  writing says so and keeps *Undo with your backup*, instead of "Nothing was written." This holds in
  the same browser tab after going back to Kiln's first page or reloading, until a run finishes.
  Closing the browser's device list on such a retry asks for recovery mode, then Undo. On Undo, a
  backup this browser can't read now says so on the screen. Once *Keep its settings* has given
  Aqara's apps back, that backup is the one Undo restores: another can't be picked, even after
  changing how far back to go. A rescan that changes the picked Wi-Fi network clears the password
  typed for the old one. Links that are off now look off.
- **The screen starts at your saved brightness.** With *Auto brightness* off, the app used the
  system brightness after a restart until the light sensor reported a change, while Home Assistant
  already showed your saved brightness. In a dark, still room that could last a long time. The app
  now applies your saved brightness as soon as it starts.
- **Android 14 and newer: the app no longer crashes on a fresh install.** Before the microphone
  permission was granted, the app's always-on service stopped at once and Android showed "S1 Kiosk
  keeps stopping". The service now starts without the microphone, and takes it as soon as you allow
  it, so voice starts without a restart. S1 panels run Android 10 and are not affected.
- **Sendspin: switching the Music Assistant player off and on quickly leaves one connection.** After a
  quick off and on, or a setting change that restarts the player, the old connection could end the new
  stream, make the status say "disconnected" while the panel was connected, or connect a second time
  next to the new one. Now the old connection can't touch the new one.
- **Undo refuses an incomplete backup instead of half-restoring it.** Undo puts back boot, vbmeta and
  the splash. A backup missing one of them (a damaged or hand-made `.zip` or folder) used to restore
  the rest and report success, leaving our splash on the panel. Now the web installer and
  `fel-install.sh --undo` both refuse it before writing anything, and the
  [install guide](frames/aqara-s1-plus/docs/install.md#undo) says how to put back boot and vbmeta by hand.
- **Sendspin: Music Assistant pairing was still offered after you switched Sendspin off.** Once the
  player had started, turning it off in Home Assistant still let the pairing request through. It now
  says Sendspin is disabled until you turn it back on.
- **Thread border router: replies from Thread devices stopped after Wi-Fi reconnected.** Turning
  Wi-Fi off and on gives Android's Wi-Fi interface a new number, and the radio helper's routing rule
  for traffic from Thread to your network kept pointing at the old one. It now follows the
  interface. Comes with the radio bundle.
- **Settings menu: cards were cut off 30 dp from the top and bottom of the screen.** They now scroll
  all the way to the screen edges.
- **Signing in works when Home Assistant's name also gives an IPv6 address the panel can't reach.** On a
  network where `homeassistant.local` resolves to Home Assistant's IPv6 address as well as its IPv4 one,
  for example a VLAN without IPv6 behind an mDNS reflector, the Manage page's sign-in stopped after about
  9 seconds with "Home Assistant didn't say who signed in", and checking a panel's sign-in failed the same
  way. The dashboard was fine. The panel now tries Home Assistant's addresses the way a browser does, so it
  connects over IPv4 straight away. This covers the sign-in, keeping it signed in, finding Home Assistant,
  notification pictures and the Thread and blueprint calls.

### Changed
- **Thread border router docs:** the panel has to be on Home Assistant's network to be its border
  router, and has now been tested as a second border router next to an existing one.

### Security
- **Sign-in for the Thread join: someone on your network who reads its code can no longer use it.** The
  administrator sign-in for the Thread join now uses PKCE too, like the dashboard's QR-code sign-in since
  0.4.4. Needs Home Assistant 2026.10 or later to take effect. It doesn't cover the panel's own requests to a
  Home Assistant on plain `http://`, which anyone on that network can read. A sign-in that was halfway through
  when the update arrived needs a new scan.

## [0.4.4] - 2026-11-19

**Each panel now has its own name in Home Assistant, so you can add a second panel without a name conflict.**

### Fixed
- **Each panel has its own name in Home Assistant, so a second one can be added.** Every panel used
  to call itself `s1-panel`, and adding a second one made Home Assistant offer to replace the first.
  Now the panel announces itself as "Aqara S1 Plus", with a node name such as `aqara-s1-plus-a1b2c3`
  that ends in part of its MAC. A panel you already added stays in place with its device and all its
  entity ids. Home Assistant shows "Aqara S1 Plus" after the update, unless you renamed it.
- **Updates are found among the repo's other releases.** The panel now picks its own newest release
  (tag `v1.2.3` or `aqara-s1-plus-v1.2.3`) instead of whatever release is newest in the repo, so
  releases of other devices and libraries can't hide an update.
- **Updates come from the project's new address.** The panel checks GlassworksHQ/glassworks
  directly instead of going through GitHub's redirect from the old name.
- **One stuck connection no longer freezes the panel's entities in Home Assistant.** If a client
  stopped reading (a Home Assistant that hangs, or a connection that is half gone), states, replies
  and keepalive pings for every other client waited behind it. Now only that client waits, until the
  panel drops it.
- **Wi-Fi reconnects while the screen is off.** If Wi-Fi dropped at night (a router restart, an
  outage), the panel stayed offline until someone woke the screen. The app now asks for a scan 10 s
  after Wi-Fi drops, then after 40 s, 60 s and every 120 s, while Wi-Fi is not connected and the
  screen is off. It needs the location permission, which the installers grant, and location turned
  on. The app's always-on service now also runs as a location service, because Android 10 only lets
  an app use location with the screen off that way. The app reads no location.

### Security
- **Other apps can no longer change the panel's settings.** Until now any app on the panel could
  open S1 Kiosk in a way that changed its settings, such as the dashboard's address, the API key or
  remote debugging. Now only adb (Kiln, the command line), the boot image's root side and the panel
  itself can. The launcher icon still opens the app, and the panel still starts it as its home screen.
- **Sign-in: someone on your network who reads the sign-in code can no longer use it.** The
  dashboard's QR-code sign-in now uses PKCE, so Home Assistant only hands out the panel's token to the
  panel itself. Needs Home Assistant 2026.10 or later to take effect; older versions sign in as before.

## [0.4.3] - 2026-11-11

**Pick the radio's role on the panel with role tiles and QR codes, voice timers you can move or hide, and a new typeface.**

### Added
- **Radio in the panel's settings menu.** A row of role tiles (*Off*, *Zigbee coordinator*, *Zigbee
  router (repeater)*, *Thread border router*, *Thread router (repeater)*), each with its icon in the
  technology's colour (Zigbee blue, Thread violet) and, on the active one, a status dot. Under them, a
  panel for the active role: its status, what to do next, and the same buttons as in Home Assistant
  (*Zigbee stack*, *Join / Leave Zigbee network* with the router's live status, *Leave Thread
  network*). Tapping another tile previews that role with *Turn on*; switching, changing the Zigbee
  stack and leaving a network ask first. In Home Assistant these only work over the panel's encrypted
  connection; on the panel, being there is what allows them, as for the *who may connect* field.
- **QR codes for the Thread roles**, as the Zigbee coordinator already had. On the setup screen and in
  the settings menu: *Thread border router* shows the URL to enter and a code that opens Home
  Assistant's *Add OpenThread Border Router*; *Thread router (repeater)* shows a code that opens Home
  Assistant's Thread page, with the steps to copy the dataset. No code while the panel doesn't know
  Home Assistant's address; the text is there anyway.
- **The Thread router says whether it has joined**, on the panel and in *Radio status*: the Thread
  role (child, router, leader, or still looking) and the network's name, never its key. It keeps
  updating, so *joined as a router* shows up when the network promotes the panel. This comes
  with the app's radio package: no new boot software is needed.
- *Leave Thread network* is offered on the panel whenever a Thread network is still stored, also after
  switching to another mode, so it can be cleaned up.
- The Thread dataset can also be pasted on the panel (settings menu, Thread router, *Advanced*),
  including datasets longer than Home Assistant's 255-character limit for text fields.
- The web installer's next steps for the Thread roles link straight to the right pages in Home
  Assistant.
- **New look for voice timers**, to match the voice assistant: each timer is a small glass orb whose
  amber ring runs down, with its name underneath, at the bottom right. Its last 10 seconds glow and
  count down in large digits; a paused timer shows a pause sign and a grey ring. A timer you set by
  voice comes out of the assistant's orb as it closes.
- **Move or hide the timers** if they cover something: drag them to any corner or edge (they snap
  into place and stay there for later timers), or tap one and choose *Hide timers*. Hidden timers
  become a small half-orb at the screen edge that still shows the time left; tap it to bring them
  back. They come back by themselves for their last 10 seconds, and when you set a new one.
- **Timer done** is now a full-screen moment like the voice assistant: the orb glows amber with each
  chime, and it shows the timer's name and length (or "2 timers done" when several finish together)
  and which timers are still running. Tap anywhere to dismiss it, which also stops the remaining
  chimes.

### Changed
- **New typeface: Inter**, bundled with the app (SIL Open Font License), on the voice assistant, the
  timers, the settings menu and the setup and loading screens, matching the boot animation.
- The repeater roles are now called *Zigbee router (repeater)* and *Thread router (repeater)*
  everywhere, so they aren't mixed up with the Thread border router. The border router's card says
  it runs its own Thread network, and that joining yours is the *Thread router (repeater)* role.
- The setup screen's radio step shows each role's icon in its technology's colour, with shorter
  descriptions so *Apply* and *Skip* always fit.

### Fixed
- A blank strip about 85 px tall could cover the bottom of the dashboard. The kiosk hides Home
  Assistant's header, but cards that subtract the header's height from the screen height still
  subtracted it; they now get the full screen.
- Pasting the Thread dataset works even when the copy includes spaces or line breaks.
- Small capital labels, such as the voice assistant's *LISTENING*, showed in lower case.

### Note
- The repeater roles are now called *Zigbee router (repeater)* and *Thread router (repeater)* in the
  Radio mode select; automations that set the old names need updating.

## [0.4.2] - 2026-11-10

**A new settings menu, and the panel looks like S1 Kiosk from the boot animation on, with no Aqara screen in between.**

### Added
- **Kiosk settings menu.** The hidden settings (tap the top-left corner five times) are now a full-screen
  menu in the setup screen's look, in sections: Home Assistant & dashboard, Radio: who may connect,
  Panel, About (version and open source licences). Every setting from the old dialog is still there,
  and *Save & restart* works as before. Under Panel, *Panel settings (Aqara)* opens Aqara's own settings
  app (Wi-Fi, sound, display, updates, factory reset).
- **Tapping S1 Kiosk's notification opens the menu**, as a second way in besides the five taps. Its
  *Panel settings* button opens Aqara's settings app. Anyone at the panel can pull the notification
  shade down, so the menu is exactly as hidden as the corner gesture.

### Fixed
- **No Aqara screen at boot any more.** For about 4 seconds after the boot animation, Android showed
  Aqara's fallback home screen while it finished starting up. S1 Kiosk's loading screen now shows
  instead, straight through to the dashboard. It is turned on the first time the new version starts,
  so you see it from the reboot after that. On panels where Aqara's launcher or factory app is still
  enabled (no de-bloat), it stays off so the choice of home app isn't disturbed.
- After a reboot the screen said *No network yet* and how to set up Wi-Fi, while the panel was just
  rejoining its saved network. It now says *Connecting to Wi-Fi…* and *Home Assistant loads as soon
  as it's connected*, and loads the dashboard as soon as Wi-Fi is up. The Wi-Fi setup hint appears
  only when Wi-Fi is off, no network is saved, or the panel hasn't connected within 30 seconds.
- The loading screen's background no longer looks banded right after a reboot. It was drawn as
  plain gradients until the smooth version was ready, which took seconds while the panel was still
  starting up. The smooth version is now prepared straight away and much faster.
- **Every new Zigbee2MQTT or ZHA connection to the panel's radio starts clean.** Messages left over
  from the previous client no longer greet the next one with a *CRC error* and an ack-timeout error
  (Zigbee2MQTT recovered, but it was untidy). This fix comes with the app's radio package: no new
  boot software is needed.
- Flashing the radio firmware shows real progress (or the seconds so far) instead of
  *flashing stock: %*. Also part of the app's radio package.
- The web installer offers *Zigbee router* and *Thread router* as experimental choices, as the panel
  does, instead of listing them as *coming soon*.
- `tools/install.sh --dry-run` no longer states that the panel's app is signed with a different key
  when it may not be; it says what happens *if* the keys differ.
- `tools/debloat/debloat.sh` no longer says the launcher was not disabled and then disables it.

## [0.4.1] - 2026-11-10

**Updates from Home Assistant install again: earlier versions always said "the APK is not signed".**

### Fixed
- Installing an update from Home Assistant failed with *the APK is not signed*, although the release
  is signed: on this Android version the app couldn't read a downloaded APK's certificate the way it
  asked for it. It now reads it the way Android 10 supports.

### Note
- Panels on 0.4.0 or older can't install this update from Home Assistant (the bug is in the version
  doing the installing). Update once with the web installer (*Already installed? Finish setting it
  up*) or `adb install -r`; from 0.4.1 on, updates from Home Assistant work.

## [0.4.0] - 2026-11-10

**Zigbee2MQTT on the panel's radio, and the panel's root side now updates with the app.**

### Added
- **Zigbee2MQTT support (experimental).** A new *Zigbee stack* select in Home Assistant switches the
  Zigbee coordinator from Aqara's stack (ZHA only) to Silicon Labs' newer one, which Zigbee2MQTT's
  `ember` adapter needs. The panel downloads it straight from Silicon Labs (it can't be bundled for
  licence reasons) and checks it. Then use `adapter: ember`, `port: tcp://<panel IP>:6638`; *Radio
  status* shows the values. No radio firmware change is needed. ZHA works with either stack.
- **Updates for the root side through the app.** After one more update of the panel's boot software
  with the web installer, fixes to the radio helper, new radio stacks and radio firmware arrive with
  normal app updates, as a package signed by the project that the panel checks before installing. If
  anything is wrong with it, the panel keeps using the version built into its boot software. The new
  *Radio helper* sensor says which one runs and whether the boot software needs updating.
- **Radio firmware updates**, never automatic: choose an image in *Radio firmware image*, then press
  *Flash radio firmware* within two minutes. The panel stops the radio, flashes it with Aqara's own
  tool, checks the version, and goes back to Aqara's original firmware if anything fails. *Radio
  firmware* shows progress. No new firmware is offered yet; Aqara's original can always be put back.
- **Leave Thread network** button: the panel forgets the Thread network it was given (the dataset with
  the network key, and the router's settings), in any radio mode. Needs the new boot software.
- *Radio allowed clients* can also be changed on the panel: in the hidden settings (tap the top-left
  corner five times), under *Radio: who may connect*. It's the same setting as in Home Assistant, and the
  dialog shows who is allowed right now.
- **Open source licences** in the hidden settings list the open-source software the app and the
  panel's radio package include, with each licence's full text.

### Fixed
- **Moving a panel from a development build to a release no longer leaves it off Wi-Fi.** The
  installers explain the switch and ask first. Where adb has root they carry the Wi-Fi and the app's
  settings over by themselves; otherwise the settings are reset and the installer asks for the Wi-Fi
  password again.
- Undoing an install over adb (`install.sh --undo`, or *Keep the panel's settings* in the web
  installer) no longer makes the panel forget its Wi-Fi: S1 Kiosk is turned off instead of removed
  when it saved the network.
- **The time zone no longer resets to Antarctica/Casey (UTC+8) at every boot.** Aqara's system software
  set it back on each start. The panel now keeps the zone you chose in the installer, and a new *Time zone*
  setting in Home Assistant changes it later (*Time zone status* shows whether it is kept). Needs the new
  boot software; panels set up earlier keep the zone they have at their first boot with it, or set *Time
  zone* once.
- Home Assistant no longer loses the panel's connection every 90 seconds. The panel now checks on a
  quiet Home Assistant the way ESPHome devices do, instead of hanging up on it.
- The app update card no longer shows an update with a blank version when the panel started without
  Wi-Fi. It shows "up to date" until the first real check, and checks again as soon as Wi-Fi is back
  instead of 15 minutes later.

### Changed
- The app no longer includes Google's Play services libraries, which came with the built-in browser
  but could never run on the panel.
- The Zigbee coordinator now waits until the radio's start-up message is cleared before letting a
  client in, which avoids a failed first start in Zigbee2MQTT (ZHA was not affected).
- Old hand-made copies of the radio helper on the panel are no longer used automatically (they would
  have hidden updates).
- Radio firmware, Zigbee stack and Thread leave controls only work from Home Assistant's encrypted
  connection, not from an unencrypted one on a panel that was never paired.
- After *Leave Zigbee network*, the panel no longer briefly says the network is rejoining.

### Note
- The new features need the panel's boot software from the web installer once more (one press of the
  button on the back). Panels that skip it keep working as before; Home Assistant says what needs it.

## [0.3.1] - 2026-11-10

**Only Home Assistant can reach the radio now, and update notes no longer get cut off.**

### Changed
- **The radio's network ports only accept Home Assistant.** The Zigbee coordinator (port 6638) and the
  Thread border router's REST API (port 8081, which can hand out the Thread network key) used to accept
  anyone on the network. The panel now learns Home Assistant's address from its encrypted ESPHome
  connection and lets only that address in. *Radio status* says who is allowed. To allow other
  computers, set *Radio allowed clients* in Home Assistant (addresses or ranges, or `any`). This needs the
  new root helper from the web installer; panels with the older helper keep working as before, and
  *Radio status* says so.
- The web installer reminds you to add the panel to Home Assistant first when you pick a radio role.

### Fixed
- The update card in Home Assistant showed release notes cut off mid-sentence. It now shows whole
  sections, and no longer shows a "Last update" line.
- Long radio status messages could leave *Radio status* stuck on "applying".

## [0.3.0] - 2026-11-10

**Zigbee and Thread: the panel's built-in radio can now work for Home Assistant.**

### Added
- **Radio modes**, chosen in the panel's setup or with the *Radio mode* select in Home Assistant:
  - **Zigbee coordinator** for ZHA: radio type EZSP, serial path `socket://<panel IP>:6638`.
  - **Thread border router** (experimental): add *OpenThread Border Router* in Home Assistant with
    `http://<panel IP>:8081`.
  - **Zigbee router** and **Thread router** (experimental): the panel joins your existing network as a
    repeater. New *Join Zigbee network* / *Leave Zigbee network* buttons, and a write-only
    *Thread dataset* field for handing over your Thread network.
- The panel's radio step shows the ZHA serial path and a QR code that opens Zigbee pairing in Home
  Assistant on your phone.
- The web installer explains, for the mode you pick, what to do next in Home Assistant.

### Changed
- The radio helper waits until the Zigbee stack is really ready before reporting the mode as on, and
  keeps the Zigbee connection alive so a vanished client can't block it.
- The installer ships a border-router build of OpenThread (REST API, border routing, SRP).

### Known issues
- In ZHA, **creating a new network** or **restoring a backup** onto the panel hangs, because of a bug in
  ZHA's radio library (fix submitted: [zigpy/bellows#774](https://github.com/zigpy/bellows/pull/774)).
  Choose *keep the radio's network settings* instead.
- Zigbee2MQTT doesn't work with this radio yet (it needs a newer Zigbee stack, EZSP 13+).

## [0.2.0] - 2026-11-10

**First signed release: from now on, updates come through Home Assistant.**

### Added
- **App updates in Home Assistant**: the panel shows an update under Settings → Updates and installs it
  when you press Install, with no prompt on the panel. Each update is checked against its checksum and
  signing key before it's installed.
- **New voice assistant overlay** ("Ambient Stage"): a full-screen glass orb that listens, thinks and
  speaks, shows what it heard and the answer, and can be cancelled with a tap.
- **Setup without touching the panel**: the web installer can send Wi-Fi, the Home Assistant address,
  sign-in and the radio mode over USB. On the panel, Wi-Fi over Bluetooth (Improv) now lives inside the
  setup screen, and there's a radio step.
- Language, time zone and 12/24-hour clock can be set from the installer.
- Wake-word models are downloaded by the panel itself.

### Fixed
- Wi-Fi over Bluetooth on a factory-reset panel (Bluetooth was off, and the Allow prompt went missing).
- A black screen while the app starts, and a "Waiting for a network" screen on a new panel's first
  boot: it now goes straight to setup.
- Joining Wi-Fi could restart the app halfway and lose the result.
- Visible banding in the setup and loading backgrounds.

### Note
- Panels running a development build need a one-time reinstall to switch to release builds (Android
  won't update an app across signing keys). That resets the app's settings.

[Unreleased]: https://github.com/GlassworksHQ/glassworks/compare/v0.4.3...HEAD
[0.4.3]: https://github.com/GlassworksHQ/glassworks/releases/tag/v0.4.3
[0.4.2]: https://github.com/GlassworksHQ/glassworks/releases/tag/v0.4.2
[0.4.1]: https://github.com/GlassworksHQ/glassworks/releases/tag/v0.4.1
[0.4.0]: https://github.com/GlassworksHQ/glassworks/releases/tag/v0.4.0
[0.3.1]: https://github.com/GlassworksHQ/glassworks/releases/tag/v0.3.1
[0.3.0]: https://github.com/GlassworksHQ/glassworks/releases/tag/v0.3.0
[0.2.0]: https://github.com/GlassworksHQ/glassworks/releases/tag/v0.2.0
