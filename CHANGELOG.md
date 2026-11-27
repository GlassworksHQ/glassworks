# Changelog

What changed in each release of Glassworks for the Aqara S1 Plus, for people running a panel. The
first line of each version is what Home Assistant shows on the update card. Releases are built by the
Release button, [`.github/workflows/release.yml`](.github/workflows/release.yml), which publishes the matching section
below as the release notes. Glassworks for generic Android and its legacy build have their own:
[frames/generic-android/CHANGELOG.md](frames/generic-android/CHANGELOG.md).

## [Unreleased]

## [1.0.0] - YYYY-MM-DD

**Glassworks 1.0 needs Home Assistant 2026.11 or newer for notifications and the radio controls. It's the first stable release for the Aqara S1 Plus, with everything from 0.9.0.**

**Upgrading from 0.4.x**

1. Check the panel's *Radio helper* sensor in Home Assistant. If it says the boot image needs refreshing, run a full
   Kiln install once more and skip the steps below ([the frame's
   notes](https://github.com/GlassworksHQ/glassworks/blob/main/frames/aqara-s1-plus/FRAME-CHANGELOG.md) explain why).
2. Update Home Assistant to 2026.11 or newer, so it can give the panel its encryption key.
3. Install 1.0.0 from the panel's update card. A panel on 0.4.1 to 0.4.3 gets 0.4.4 first, then 1.0.0. A panel on
   0.4.0 or older updates once with Kiln's [Finish setting up](https://kiln.glassworks.dev/aqara-s1-plus/set-up), and
   Home Assistant takes over from there.
4. Do the two things [0.9.0](https://github.com/GlassworksHQ/glassworks/blob/main/CHANGELOG.md#090---2026-11-24)
   asks for: reload the ESPHome integration and turn off Expose for the panel's *Screen* light, and import the Panel
   night mode blueprint if you used the built-in night window.

The panel keeps its Home Assistant device, entity IDs and settings.

### Added
- **One volume for everything the panel plays.** A new **Sound** page, in the panel's settings and on the Manage
  page, sets the volume for voice replies, timers, notifications, Music Assistant and the dashboard, with a mute
  switch. Home Assistant's *Speaker* volume and mute control it too, and the Music page's slider is now
  **Music volume**.
- **Critical alerts play at full volume**, even when the panel is turned down or muted.

### Changed
- **Some Home Assistant controls need the encryption key.** Home Assistant 2026.11 or newer sets the key when you
  add the panel, so add it from a network you trust. Until the key is set, `notify` messages, the radio controls,
  **Dashboard URL**, **Sign out**, **Start Wi-Fi setup (Bluetooth)** and **Open device settings** don't work from
  Home Assistant, so nobody else on your network can use them. Everyday controls like the speaker, reload and
  restart work as before.
- **Only Home Assistant gets the microphone and the camera.** The panel gives them to pages on your Home Assistant
  and the secure hosts in its settings, and refuses everything else, including sites framed in your dashboard.
- **Kiln is easier to find your way around.** Every screen has a Back button where going back is safe, finished
  steps are links, and reopening Kiln partway through picks up where you stopped. On a phone, a slim step bar opens
  the full list of steps.

### Fixed
- **Another device on your network can no longer replace the panel's Home Assistant sign-in.** Once signed in,
  signing in again requires a browser signed in to the panel's Manage page (or signing out first). The panel's web
  pages also answer only to the panel's own address, blocking other sites from reaching them.
- **Changing the language from Kiln no longer leaves the old dashboard running.** Changing the language or system
  font size now closes the old dashboard page before opening the new one.
- **The first sign-in after an install works on the first try**, including with a `.local` address. The panel used
  to lose a sign-in in progress when the app restarted, or fail with "Can't reach Home Assistant" before it had
  found Home Assistant on your network.
- **The dashboard stops running behind other apps** after Android closes it to free memory.
- **The panel's address never comes from a hotspot.** The address it shows and gives Home Assistant always comes
  from the Wi-Fi network it's connected to.
- **Kiln installs the newest release**, so a new panel doesn't start one version behind.
- **App updates fail fast when Android blocks them.** On a panel without the root update service, an update with
  "Install unknown apps" turned off now says so right away instead of timing out after 10 minutes. Kiln and
  `install.sh` now turn that setting on for the S1 Plus.
- **The Radio firmware sensor says `none`** when no root helper is installed, instead of `v1`.

## [0.9.0] - 2026-11-24

**S1 Kiosk is now Glassworks, in its last release before 1.0: a browser Manage page for every panel, themes and an edge glow for Assist, notifications and Bluetooth proxy panes, and frame updates from Home Assistant.**

### Added
- **Frame updates install from Home Assistant.** On a panel with the frame loader, Install on the Frame update card
  (or an automation's `update.install`) downloads the S1's signed frame bundle, checks it, and restarts the panel
  once to load it. The card shows the download's progress, and says why when it stops: a download that doesn't match,
  a bundle the panel refuses, no network, or an app update in progress. The Device page and the Manage page have the
  same Install button; from the Manage page it asks for the code the panel shows, as Reboot does.
- **Browser Manage page & multi-panel control.** Click *Visit* from Home Assistant (or open `http://<panel>:8099/`) to inspect health, edit every setting live (including radio roles, wall base relays, Bluetooth proxy, and time zone), view rolling 7-day logs, download redacted diagnostics, send test notifications, and reset the panel or toggle network setup from a new **Security** page. Under **All panels**, Home Assistant administrators can manage every panel from one sign-in and copy settings between them.
- **Appearance themes, Assist edge glow, and timer board.** A new **Appearance** page offers five light/dark themes, a **Custom** color picker, and a **Home Assistant** theme that mirrors your dashboard's colors and dark mode (plus a toggle to follow Home Assistant's dark mode with any theme). Themes apply to both Assist and timers. Assist now shows as an **edge glow** by default, keeping your dashboard visible (the orb is still a pick under *Assist* on the *Appearance* page): the whole border swells and brightens with your voice, light trails around the edges while Assist thinks, and it rests in the corners while the reply plays. Three or more active timers group into one **timer board** card.
- **Notifications pane.** Show rich Home Assistant notifications with `esphome.<panel>_notify`, including camera snapshots, up to three action buttons, sounds, *Do not disturb*, and full-screen or half-screen (`"size": "half"`) dashboard views that queue cleanly ([docs/notifications.md](docs/notifications.md)). On by default.
- **Bluetooth proxy pane.** Relay nearby BLE sensors—and optionally connect to up to 3 active BLE devices (locks, SwitchBots, encrypted sensors) with *Listen and connect*—over the panel's encrypted Home Assistant connection. Off by default.
- **Diagnostics pane.** Exposes panel health (CPU, temperature, RAM, storage, Wi-Fi signal, and uptime) in Home Assistant and on the panel/Manage pages with 1-hour history lines.
- **Toggle any pane live without restarting.** Turn Voice, Music, Notifications, Bluetooth proxy, Wall base, Time zone, and Diagnostics on or off from the panel, Manage page, or Home Assistant (encrypted connection required for HA switches).
- **One-click Home Assistant blueprints.** Import ready-made blueprints—including doorbell, interactive notifications, and **Panel night mode**—straight from Settings or Manage ([docs/blueprints.md](docs/blueprints.md)).
- **Over-the-air frame loader & frame versions.** The S1 Plus now tracks its **Frame version** (*Frame update* entity in Home Assistant) and installs the boot-side frame loader automatically through the signed radio bundle (backing up and verifying before rebooting) so future frame updates arrive over the air.
- **Network setup (`:8099/setup`) and automatic setup Wi-Fi.** Set up a panel from a laptop or phone over Wi-Fi; if no Wi-Fi works after 3 minutes, the panel opens its own `Glassworks_XXXX` setup hotspot with a captive portal and QR code (hotspot name and captive portal come with the radio bundle).
- **One-tap Thread join & Zigbee client visibility.** Join Home Assistant's preferred Thread network without copying a raw dataset (via one-time admin QR sign-in if needed), and see who is connected to the Zigbee coordinator (*Radio connected client* sensor; comes with the radio bundle).
- **Update channels (Stable / Beta) & Silent updates status.** Pick Stable or Beta releases from the device page, and check the *Silent updates* sensor in Home Assistant to see whether updates install hands-free.
- **Android tablets & lululemon Studio Mirror in Kiln.** [kiln.glassworks.dev](https://kiln.glassworks.dev) now installs Glassworks in beta on the lululemon Studio Mirror (including community Glass Panes Wi-Fi setup and recovery) and on Android tablets (including a direct tablet download page and a legacy Android 6–7 build), alongside the stable Aqara S1 Plus installer.
- **Dashboard JS API (`window.glassworks`).** Dashboards can read presence, ambient light, and screen state via `window.glassworks` and `glassworks-state` events (`window.s1kiosk` stays supported).

### Changed
- **Calmer, clearer wording across Glassworks and Kiln.** Rewrote onboarding steps, settings descriptions, Manage dialogs, sign-in pages, and Kiln install/recovery screens in plainer, more explanatory American English—removing leftover "kiosk" and protocol jargon, explaining why steps are needed, and clarifying what stays safe while waiting or recovering.
- **S1 Kiosk is now Glassworks.** The app, icon, boot logo/animation (on next Kiln install), connecting screen, setup flow, and settings menu now use the Glassworks brand and unified layout. Existing Home Assistant devices, entity IDs, and settings carry over on update.
- **Switched wake-word engine to microWakeWord (~9× lower CPU).** Drops S1 microphone-thread CPU from ~37% to ~4% and adds **Okay Nabu**. Models download automatically; custom openWakeWord thresholds reset to per-word defaults (old openWakeWord models remain selectable for this release).
- **Room "lights off" no longer turns off the panel screen.** The *Screen* light entity is now categorized under Configuration in Home Assistant so area, floor, and Assist "turn off all lights" actions skip it (reload the ESPHome integration after updating; on existing panels, turn off Expose once in *Settings → Voice assistants → Expose*).
- **Night mode moved to a Home Assistant blueprint.** The built-in *At night* window and night brightness cap were removed from the panel; import the **Panel night mode** blueprint from **Settings → Screen** ([docs/blueprints.md#panel-night-mode](docs/blueprints.md#panel-night-mode)) to automate day/night timeouts and brightness from Home Assistant.
- **Screen stays on by default until you pick a timeout.** Panels without a saved screen mode now default to always on; existing saved preferences stay unchanged.
- **Shorter default entity list in Home Assistant.** New panels enable 15 core entities by default (down from 55), keeping advanced controls and diagnostic sensors disabled until enabled. Existing panels keep their current entities.
- **On-device secrets encrypted with Android Keystore.** Home Assistant tokens, ESPHome API keys, and Sendspin pairings are now sealed at rest (existing logins carry over automatically).
- **Live-applying settings & cleaner menus.** Changes apply immediately without *Save & restart* (only dashboard URL, zoom, and secure hosts prompt to restart), turned-off panes fold under *Off*, and Music Assistant / Improv now advertise the device's real model name.
- **Moved to `GlassworksHQ/glassworks` & streamlined Kiln.** Updates check [GlassworksHQ/glassworks](https://github.com/GlassworksHQ/glassworks) directly, and Kiln shows concise steps with live panel progress and upstream community credits.

### Fixed
- **A rejected frame bundle is reported.** When the panel refuses its installed frame bundle at startup, the Frame
  update card in Home Assistant, the Device page and the Manage page now say it was rejected and why, instead of
  looking like a panel with no bundle. The *Radio helper* sensor says the same. Install still offers the release.
- **Automatic recovery from Android 10 framework hangs on the S1.** Detects Android 10's multi-restart ActivityManager stall within 30 seconds and recovers automatically (comes with next Kiln install).
- **More reliable S1 Zigbee/Thread radio & Thread routing.** Protects Zigbee router backups and Thread datasets against partial writes, caps runaway Thread join retries, and updates Thread border router routes when Wi-Fi reconnects (comes with the radio/frame bundle).
- **Screen wake timeout & startup brightness.** Non-touch wakes (Home Assistant, voice, timers) now respect the full idle timeout instead of dimming after 20 seconds, and manual brightness applies immediately on boot.
- **Sign-in reliability over IPv4/IPv6 and on-panel login.** Fixes sign-in hangs when `.local` resolves an unreachable IPv6 address, ensures on-panel sign-in stores credentials properly, and re-prompts cleanly if Home Assistant changes address.
- **Settings persistence, Sendspin stability, and Kiln backup safety.** Prevents rapid changes before restart from being lost, prevents duplicate Sendspin connections or pairing while disabled, fixes Magisk busybox loading for modules (comes with next Kiln boot image), and hardens Kiln download timeouts, Undo backup validation, and dev-to-release credential hand-off.
- **The screen stays on while Assist answers.** The screen no longer turns off in the middle of a voice run when the idle timeout hits, whether Assist is listening, thinking or reading a long answer, with the orb or the edge glow. The idle countdown starts again once the run is over.

### Security
- **PKCE for Thread-join admin sign-in.** Matches the dashboard QR sign-in's PKCE protection (requires Home Assistant 2026.10 or newer).

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
