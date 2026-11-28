<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-core-on-dark.svg">
      <img alt="Glassworks Core" src="https://www.glassworks.dev/brand/banner-core-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Glassworks pane API</h1>

<p align="center">
  <b>Features for an open Home Assistant wall panel, written once and run on any device.</b><br>
  A pane brings its own entities, lifecycle and settings. The panel's core hosts it.
</p>

<p align="center">
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-core-on-dark.svg">
      <img alt="Glassworks Core" src="https://www.glassworks.dev/brand/badge-core-on-light.svg" height="30">
    </picture>
  </a>
  <a href="#licence">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-on-dark.svg">
      <img alt="License: MPL-2.0" src="https://www.glassworks.dev/brand/badge-license-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-stable-on-dark.svg">
    <img alt="Status: stable" src="https://www.glassworks.dev/brand/badge-status-stable-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

Glassworks is the device-neutral core in
[this repository](../../../../../../README.md), pulled out of the S1's app one step at a time. A **frame** describes a device.
A **pane** is a feature: the Music Assistant player, voice, timers, the radio. This package
(`dev.glassworks.pane`) is the contract between the two.

It's its own Gradle module, `:glassworks-pane-api` in `pane-api/`, and the core that builds on it is
`:glassworks-core` in `core/`. The API is stable as of Glassworks 1.0.

## What's in it

| Type | What it is |
|---|---|
| `Pane` | A feature: its Home Assistant entities, `start`/`stop`, and what it does when the panel changes. |
| `PaneContext` | What the core gives a pane: the app context, `prefs(namespace)` for its settings, the device identity, the app's `build` (its version and its name, `product`, which a pane announces itself with; a pane never reads the APK's `BuildConfig`), `publishStates()`, `emit()`, `entitiesChanged()` and `sendEvent()`. |
| `PanelEvent` | Things that happen on the panel: a setting changed, voice took the speaker, a timer changed or finished, Home Assistant subscribed. A pane raises one with `PaneContext.emit`. |
| `EntityRegistry` | Builds the device's entity list from the core's entities and every pane's, and refuses two entities with the same object id. |
| `PaneDefinition` | What the core knows about a pane for the whole process, also while it isn't running: its on/off, setup steps, settings sections and adb extras. |
| `PaneSwitch` | A pane's on/off (#67): a flag in the pane's own settings file. The shell runs the pane only while it is on. The settings switch, the setup card, the Home Assistant switch and the adb extra all come from it. |
| `SetupStep` | Something first-boot setup waits for from a pane: state plus setup channel requests. |
| `PaneChoice` | A pane's card on setup's Panes step: a title, a line and a switch. |
| `SettingsSection` | A section of the settings: typed items (`SettingsItem`) that the panel's menu and the Manage page both draw and change (#242). `settingsOrder` places them; `SettingsChange` checks and applies a change for both. |
| `ScreenPolicy` | (`dev.glassworks.screen`) Wake on approach, idle off, the brightness curve up to its limit and the screen mode. It has no clock: Home Assistant sets the idle timeout and the limit at night (#473). It reads the frame's `PresenceSource` and `LightSource` and the app's `ScreenSettings`. One per process, on `GlassworksRuntime.screen`. |
| `Platform` | (`dev.glassworks.platform`) What the running Android can do, as named queries (`hasNotificationChannels`, `hasWindowInsetsController`, …) and small shims. The only place that reads `Build.VERSION.SDK_INT`. |
| `NotificationsPane` | (`dev.glassworks.notifications`) Notifications from Home Assistant: the `notify` action, Do not disturb, the rules for each priority, and a tapped button as the bus event `esphome.notification_action`. A core pane, on by default. The panel's overlay draws what its `NotificationsHub` holds. How to use it: [docs/notifications.md](../../../../../../docs/notifications.md). |
| `DiagnosticsPane` | (`dev.glassworks.diagnostics`) The panel's health as diagnostic entities: CPU, temperature, RAM, storage, Wi-Fi, uptime, the last restart reason. A core pane, so every frame has it. The entities are in [docs/esphome.md](../../../../../../docs/esphome.md#diagnostics). |
| `BluetoothProxyPane` | (`dev.glassworks.bluetoothproxy`) The panel as an ESPHome Bluetooth proxy (#121). The work is in the [bluetooth-proxy library](../../../../../../libs/bluetooth-proxy/README.md); the pane gives it the device's server and adds two diagnostic entities. Its page is `BluetoothProxyPage`, drawn from a [`BluetoothProxyModel`](../../../../../../panes/bluetooth-proxy/src/main/java/dev/glassworks/bluetoothproxy/ProxyPhase.kt), the same in both apps. See [its README](../../../../../../panes/bluetooth-proxy/src/main/java/dev/glassworks/bluetoothproxy/README.md). |
| `Engine` | (`dev.glassworks.engine`) The web engine that shows the dashboard: open a page, load, reload, go back, restart after a crash, and talk to the page's scripts. `GeckoEngine` (`dev.glassworks.engine.gecko`) is the only code that uses GeckoView. One per process, on `GlassworksRuntime.engine`. |

## What makes a pane

Glassworks is native to Home Assistant: a panel joins HA as an ESPHome device and needs nothing
else. Panes keep it that way. The reasons are in
[Glassworks Principles](../../../../../../docs/principles.md). Before you write a pane, check
it against these four rules.

### 1. It adds something only the panel can do

A pane uses the panel's hardware or its place on the wall: the camera, mics, speaker, radios,
Bluetooth, NFC, its sensors. If a Home Assistant dashboard can already do it, it belongs in a
dashboard, not in a pane.

| A pane | A dashboard |
|---|---|
| Wake on presence, the room's light level | Clock, weather, a photo frame |
| Voice, timers, announcements | An alarm keypad |
| A Zigbee or Thread radio, a Bluetooth proxy | A calendar, a to-do list |
| The panel's camera in HA | Showing other cameras |

### 2. It works through ESPHome alone

Every pane works fully through the panel's ESPHome device. Adopting the device in HA is the only
setup.

- Its **state** is entities.
- The **settings worth changing from HA** are config entities (`EntityCategory.CONFIG`). The rest
  stay on the panel and its Manage page.
- Its **commands** are ESPHome actions (`esphome.<node>_<action>`).
- What it **reports** goes out as events (`EventEntity`). The one exception (#118, approved
  2026-11-13): a report whose name the automation chose, like a notification's button id, goes out
  as a bus event, `ctx.fireEvent(HomeAssistantEvent("esphome.<name>", data))`. HA refuses event types
  an event entity didn't list up front, and a bus event needs no option in HA either.

Settings for the whole house are HA's job: a scene or a script sets the same entity on every panel.

### 3. Other protocols are opt-in outputs

A pane can also speak another protocol when that makes the panel do more. Sendspin (Music
Assistant) and Matter already do. That's allowed when all of these hold:

- **It's an open standard that something else already speaks**, with no code of ours on the other
  end: ONVIF and RTSP (HA's ONVIF integration, Frigate, UniFi Protect), Sendspin, Matter, AirPlay.
- **It's off by default**, with its own switch in the pane. That switch is a config entity too.
- **The pane works without it.** Turn it off, and rule 2 still holds.
- **It has no settings screen of its own.** Its settings are entities, like everything else.

### 4. The panel's HA sign-in only reads

The panel signs in to Home Assistant as a user, to show the dashboard. A pane may use that sign-in
to read from HA what ESPHome can't carry: the preferred Thread network (the radio pane), or a
camera's stream. It never uses it to control HA, or to watch entities in place of ESPHome.

When a read needs an administrator and the panel's user isn't one, ask an admin to sign in once,
use the token for that read and revoke it straight away, as
[`ThreadJoin.kt`](../../../../../../core/src/main/java/dev/glassworks/auth/ThreadJoin.kt) does. The panel keeps no admin
credentials.

A *person* signed in on the Manage page is different: they act with their own HA rights, like the
HA app does.

### Never

- A Home Assistant custom integration, or anything installed from the marketplace.
- A cloud service, or an account with us.
- A protocol only our own code speaks.
- Panels coordinating among themselves in place of HA.

### Entities

The ESPHome device is an API, not the settings screen. Keep the list a person sees short:

| Kind | Category | Enabled by default | Example |
|---|---|---|---|
| Something people put on a dashboard or automate on | none | Yes | Presence, the media player |
| A setting someone would change from HA | `CONFIG` | Only the common ones | Night mode, wake word, do not disturb |
| Health and versions | `DIAGNOSTIC` | No, except the few people check | Wi-Fi signal, CPU, radio firmware |

- A pane adds at most a handful of uncategorised entities.
- **A light that is the panel itself gets `CONFIG`**, though people put it on dashboards. Home Assistant leaves
  an entity with a category out of area, floor and device targets (and labels on a device or area) and out of
  Assist, so a room's "lights off" doesn't blank the panel. The screen is one (#591).
- **Enabled by default only if people reach for it day to day.** "Restart app" is on: people use
  it. "Radio mode" is off: you set it once. Everything else gets `disabledByDefault = true`. HA
  creates it and leaves it off, so it doesn't show on the device's cards. Anyone can turn it on from the device page, and
  the panel's settings menu and Manage page can always change it.
- A pane that's off keeps its entities for now, with no state where it has none to give (#67), so
  the list doesn't change. Whether an off pane should remove them waits on a panel test of what Home
  Assistant then does with them (#67, #132).

How to apply it in code:

1. Pick the category from the table: what people look at or automate on gets none, settings get
   `EntityCategory.CONFIG`, health and versions get `EntityCategory.DIAGNOSTIC`.
2. Pass `disabledByDefault = true` to the entity's constructor unless people use it day to day. A
   setting you change once, a one-off action (join, flash, sign out) and a value only troubleshooting
   needs are off. An update entity stays on: HA shows updates only while it is on.
3. Regenerate the pin goldens. A line ends with `disabledByDefault` when the entity is off, so the
   review shows the choice.

HA uses `disabledByDefault` only when it first registers the entity. Changing it later doesn't
change a panel HA already has; changing the category does
([esphome.md](../../../../../../docs/esphome.md#a-panel-that-ha-already-has)). Decide it when you
add the entity.

**A pane's own on/off switch (#67)** is a `SwitchEntity` with `EntityCategory.CONFIG` and
`disabledByDefault = true`: you set it once, from the panel's settings or the device page. It stays
in the list while the pane is off, so turning the pane back on doesn't need HA to list the panel's
entities again.

Every S1 entity, with its category, default and reason:
[esphome.md](../../../../../../docs/esphome.md#categories-and-defaults).

### Example: the camera pane

| Part | Rule | On by default |
|---|---|---|
| Presence from the camera (`binary_sensor`) | 2 | Yes, when the pane is on |
| Snapshots as an ESPHome camera entity | 2 | Yes, when the pane is on |
| ONVIF/RTSP stream for Frigate or UniFi Protect | 3 | No |

## Writing a pane

A pane takes its `PaneContext` in the constructor and overrides only what it needs. This is the
shape of the Sendspin pane, the first one moved out of the S1 app
([`SendspinPane.kt`](../../../../../../panes/sendspin/src/main/java/dev/glassworks/speaker/SendspinPane.kt)):

```kotlin
class MusicPane(ctx: PaneContext) : Pane {
    override val id = "music"

    private val player = MyPlayer(ctx.context, onChange = ctx.publishStates)

    override fun entities() = listOf(
        TextSensorEntity("music_status", "Music status", "mdi:speaker", get = { player.status() }),
    )

    override fun start() = player.start()
    override fun stop() = player.stop()

    override fun onEvent(event: PanelEvent) {
        if (event is PanelEvent.SpeakerClaimed) player.setDucked(event.claimed)
    }
}
```

`PanelEvent` is an open interface. A pane handles the events it cares about and ignores the rest:
a `when` statement (not an expression) may simply leave out the events it does not use, as
`SendspinPane` does. A new event does not touch the panes that do not use it. A pane does not have
to add entities: [`TimersPane.kt`](../../../../../../panes/timers/src/main/java/dev/glassworks/timers/TimersPane.kt) has none and only
reacts to `TimersChanged` and `TimerFinished`. The voice assistant
([`AssistPane.kt`](../../../../../../panes/assist/src/main/java/dev/glassworks/assist/AssistPane.kt)) is the pane that raises those: it calls
`ctx.emit(PanelEvent.TimerFinished(name))`, and `SpeakerClaimed` while it listens and talks.

**Who builds the pane.** The app does, once per device run. The panel's device (`PanelDevice`) hands the
app a `DeviceHost`: the pane context, the device's thread, the speaker's players and chime player, the
panel's address, and hooks for its stop and for cancelling a voice run. The app builds its panes from it in
the order they start and list their entities, the frame's own panes included (the S1's:
[`S1Panes.kt`](../../../../../../frames/aqara-s1-plus/android/src/main/java/dev/glassworks/s1/S1Panes.kt)). The device drops
the ones the frame can't run (`CantRun`), then starts, stops and lists the rest. A pane that needs
something from the device takes it in its constructor; it never reaches for the device itself.

A pane's **layer over the dashboard** (the voice orb, the timers, the notifications, the camera's privacy dot)
is built the same way. The activity hands the app an `OverlayHost` (itself, opening a view in the page or
going back to the dashboard, closing the settings menu, the main thread), and the app returns its
`PaneOverlay`s bottom first (`AppParts.overlays`). The activity adds them over the page, puts the ones that
must never be covered above its menus, and tells each about screen changes, Back (topmost layer first) and
its own end.

These types live in the core (`:glassworks-core`):
[`DeviceHost.kt`](../../../../../../core/src/main/java/dev/glassworks/device/DeviceHost.kt),
[`PaneOverlay.kt`](../../../../../../core/src/main/java/dev/glassworks/PaneOverlay.kt) (with
`OverlayHost`) and [`AppParts.kt`](../../../../../../core/src/main/java/dev/glassworks/AppParts.kt).

Every pane also has a **`pane.json` and an `icon.svg`** in its folder, next to its `PaneDefinition` (or its
`Pane`, for a pane without a definition of its own, like timers). They describe it to Kiln and glassworks.dev:
`id`, `name`, `does` (one line), `requires` (hardware words), `status`, `frame` for a frame pane, and
optionally `docs`. The icon is 24 × 24, stroked with `currentColor` at 1.6 with round caps and joins, and
has no fills or colours; a new icon is UI, so the maintainer approves it.
[`PaneCatalogTest`](../../../../../../frames/aqara-s1-plus/android/src/test/java/dev/glassworks/pane/PaneCatalogTest.kt) fails if a pane
has no files or a file has no pane. Then run `npm run panes -w @glassworks/frame-card`
([frames/README.md](../../../../../../frames/README.md#the-panes-a-frame-runs)).

A pane may also list **Home Assistant blueprints** in its `pane.json`: `"blueprints": [{"file":
"blueprints/<name>.yaml", "summary": "…"}]`, each file in the pane's module and its name unique in the repo.
The core puts a *Blueprints for Home Assistant* card on the pane's page (`SettingsItem.Blueprints`, from
`paneSections`' `blueprints`), above its *In Home Assistant* list, while the pane is on. Its
buttons open Home Assistant's own import dialog; the panel never writes to Home Assistant
([docs/blueprints.md](../../../../../../docs/blueprints.md)).

The core raises `HomeAssistantSubscribed(address, authenticated)` each time Home Assistant subscribes
to the states. `authenticated` is true when the connection uses the key Home Assistant provisioned.
Only then is the address proven to be Home Assistant's, so a pane that trusts the address checks it.
The radio ([`RadioPane.kt`](../../../../../../panes/zigbee-thread/src/main/java/dev/glassworks/radio/RadioPane.kt)) does: it lets only that
address reach its TCP services.

On an authenticated connection the core also asks Home Assistant for the time, and raises
`HomeAssistantTimeZone(posix)` with HA's zone as the POSIX rule it sends (`CET-1CEST,M3.5.0,M10.5.0/3`,
not an IANA id). The time zone pane offers it on its page.

### Entities that change, and events

A pane's entity list may change while the panel runs. The wall base
([`WallBasePane.kt`](../../../../../../frames/aqara-s1-plus/panes/wall-base/src/main/java/dev/glassworks/s1/base/WallBasePane.kt)) adds relays, buttons and power
sensors when a switch base is mounted. The pane then calls `ctx.entitiesChanged()`. The core builds
the device's list again from its own entities and every pane's `entities()`, and gives it to Home
Assistant, which reconnects and lists the entities again. `entities()` must return the new list from
then on.

To fire an ESPHome event, a pane calls `ctx.sendEvent(entity, type)` with an `EventEntity` from its
current list. For an event whose name isn't known up front, `ctx.fireEvent(HomeAssistantEvent(...))`
fires it on HA's bus instead ([the exception above](#2-it-works-through-esphome-alone)). Keep the instances: the server only fires events for the entity objects it was given,
so build the list once per state, not on every `entities()` call. A pane does not hold the API
server for this. The core owns it, including its restarts.

Entities come from [esphome-api](../../../../../../libs/esphome-api/README.md). Home Assistant
keys them by object id, so an object id is permanent: renaming one makes a new entity in every
home that already has the panel.

## Settings

A pane gets its settings file with `ctx.prefs(id)`, where the namespace is the pane's own id. It
wraps the file in its own typed class and reads and writes only its own keys there, as the Sendspin
pane does with [`SendspinPrefs`](../../../../../../panes/sendspin/src/main/java/dev/glassworks/speaker/SendspinPrefs.kt):

```kotlin
private val prefs = SendspinPrefs(ctx.prefs(id))
```

The frame decides which file holds a namespace. A namespace is the owner of a set of keys, not a
file or a key prefix. On the S1 every pane's settings stay in `kiosk.xml`, under the key names they
had before panes ([`PanePrefs.PANE_FILES`](../../../../../../frames/aqara-s1-plus/android/src/main/java/dev/glassworks/s1/PanePrefs.kt)), because panels
update in place and no stored value may move. `PrefsPinTest` pins every file, key, type and default.
`ctx.prefs` refuses a namespace the frame does not know.

When a key in a pane's file changes, the core raises `PanelEvent.SettingChanged(key)` to every pane.
A pane matches its own key constants and ignores the rest.

A pane may also keep a file of its own that no other code reads, such as Sendspin's pairing secrets
in `sendspin.xml`. The settings menu, the setup screen and the adb extras reach a pane's settings
through its definition (below), which uses the same typed class.

## Setup steps, settings sections and extras

A running `Pane` exists only while the device runs, but the setup screen decides on its first frame
and the settings menu opens at any time. So a pane also has a `PaneDefinition`, made once per
process. The app makes its own (`AppParts.paneDefinitions`, the S1's in `S1Panes`) and places the
frame's (`Frame.paneDefinitions`) among them; the runtime marks the ones the frame can't run `CantRun`.
The definition's `id` is the pane's.

| Member | What it is |
|---|---|
| `switch` | The pane's on/off, a `PaneSwitch`, or null for a pane that always runs or keeps its own (the radio's is its mode). See [On and off](#on-and-off). |
| `setupSteps` | What first-boot setup waits for from the pane, while it is on. Each is its own step under Panes, after the person chose the panes. |
| `setupChoice` | The pane's card on the Panes step, or null. By default the switch's, when it is `inSetup`. A pane with `setupSteps` needs one. |
| `settings()` | The pane's settings sections, as they are now. Called each time the menu opens. |
| `extras` | adb intent extras for its settings (`IntentExtra.bool`, `float`, `string`). `restartsApp` for a setting read only at start. The switch's extra comes on its own (`intentExtras()`). |
| `permissions` | Android's runtime permissions the pane needs while it is on (Assist: the microphone; the camera: the camera). The app asks for them when it starts, only if the app's manifest declares them and only on a screen that takes a tap (`permissionsToAsk()`). Without touch, the installer grants them. |

**A setup step is state plus requests.** `done()` says whether the step is answered; `channelState()`
is its part of the setup channel's state, under the step's id; `ops` are the requests that answer
it, on the wire as `<step id>.<op>`. That way the installer can finish every step without the
screen, and the setup screen is one more client. `SetupChannelStepsTest` fails if a step has no
requests that finish it. The radio's step is
[`RadioDefinition`](../../../../../../panes/zigbee-thread/src/main/java/dev/glassworks/radio/RadioDefinition.kt). Its `radio.set_mode` accepts
only the ids the step offers (`RadioStep.OPTIONS`, the state's `radio.options`). Any other mode is
refused, so a mode only appears to the installer once the step offers it. Asking for the mode in
effect, running or on its way, answers the step and leaves the radio alone, and the state's
`radio.answered` says whether the step is answered. Kiln uses both for a fresh panel kept on Off. A
request can also start work that outlives its reply: `radio.thread_join` starts the join from Home
Assistant and answers at once, and the state's `radio.threadJoin` follows it to the end. No request,
reply or state carries a secret.

**A step id is the step's own.** `GlassworksRuntime.setupSteps` gathers the steps through
`SetupFlow.usable`. It leaves out, with an error in the `Glassworks` log, a step whose id is a core
step (`wifi`, `ha`, `panes`, `signin`, `done`) or a setup-channel state key (`ip`, `ha`), or an earlier step's,
and a step whose request names are already taken. A bad step costs only itself: the app still
starts and the channel still answers. `FramePanesTest` checks a frame's step and section with a
fake frame.

**On the panel, panes are a group.** The setup screen's Panes step only chooses: one card per
`setupChoice` in definition order, a title, a line under it and a switch. Continue calls every card's
`apply(on)` off the main thread, each within 30 s; it returns null or why not, in words. Then each
pane that is on has its steps under Panes, in order. A step draws its own card: `title` names its row in the rail,
and `screen(context, host)` returns a `SetupScreen` that fills a `SetupCard` (title, body, content,
status, primary and secondary buttons) and calls `host.next()` when it is answered. The shell names
no pane. `GlassworksRuntime.setupPanes` pairs each choice with its steps and leaves out a pane with
steps but no card, with an error in the log: the person could never turn it on or off.

**A settings section is data.** Items are `Text`, `Switch`, `Slider`, `Duration`, `TimeRange`, `Choice`,
`Action`, `Link`, `Note`, `Info` and `Group`, each with its value and a `save(value, done)`, and the read-only
`Status` (the line under a page's heading), `Tiles` (readings with their last hour, #212) and `States`
(entities as Home Assistant shows them). A slider, a duration (picked from its choices, with units), a
choice and a daily window are never typed (#63 round 2), so a browser can draw the same controls.
A `Link` with a `section` opens that section's page instead of something outside the menu (the S1's
Device page opens the Radio pane this way), on the panel and the Manage page alike. A `Link` that opens
something of the pane's own on the panel gives a `twin` (#518): what it opens, as typed items, which the
Manage page draws under the link's title (the notifications on the panel, read-only).
`Info`, `Status`, `Tiles`, `States` and a `Slider` not being dragged are read again every couple of
seconds while the page is open. If the page itself changes (a base mounted, the pane turned off from
Home Assistant, the device started), the menu draws it again from the definitions. A `Switch` can ask first (`confirm` gives a `Confirm`: the question, what it costs,
the button's word). Each change applies on its own, as the person makes it
(#63): there is no "Save & restart". `done` can come from another thread (the radio's allow-list) and
reports a problem in words. An item that only takes effect when the app starts again sets
`restartsApp`; the menu then says so and offers *Restart now*. A `Text` says its value in words with
`shown` ("After 2 minutes"). A section's `heading` is its page's title when it says more than its
name in the list. An `Action` is a button on a row ("Use it", a reset): `run(done)`, asked first with
its `confirm` when it costs something, as a `Switch` asks. A long `Choice` gives each option a `group`
(the time zone's regions), and a browser lists it as one grouped list, not chips; its `line` is the row's
words for the picked one when they say more than its name, and its `confirm` asks before a pick that
costs something (the radio's role), on every surface. A `Choice` with `cards` gives each option a line,
and a browser draws the options as cards, the picked one lit (the camera's use). A `Switch`'s `see` names the item its line points
at ("Needs Presence + camera." at the camera's use), and the Manage page links the line and a refusal
to it. `Native` is a view only the panel draws,
where its own layout is nicer than rows (the time zone's picker). Give it a `twin` (#269): the same
rows and actions as typed items, which every other surface draws instead, so the Manage page has the
same features. Never put a secret in a twin: it goes to a browser and the diagnostics. Every native view
has one: `PanelManageParityTest` fails on a view without it (#518).

**One way to change a setting (#242).** The panel's menu and the Manage page change an item through
`SettingsChange.change(item, raw, done)`: it checks the value as the panel's control limits it (a
number for a `NUMBER` text, the slider's range snapped to its step, one of a duration's choices or a
choice's option ids, `on`/`off`, `HH:MM-HH:MM`) and then calls the item's own `save`. Put a setting's
limits in its item (`min`, `max`, `step`, `choices`, `options`, `enabled`), not in a view, so a
browser can't send what the panel wouldn't take. An item's key on a page is its title, under its
groups' titles (`Who may connect/IP addresses …`), or `on` for the section's `master`: keep titles
unique within a section.

**A sign-in's password is write-only.** `SettingsItem.SignIn` (a stream's user name and password)
says Set or Not set. No surface reads the stored password back: the item has no getter for it, the
panel's sheet and the Manage page's dialog start with an empty field, and Manage has no route that
returns it (maintainer, 2026-11-15). `save` gets the user name and the new password, or null for the
password when the field was left empty (keep the stored one), or null for Clear.
`SettingsChange.signIn` checks the name (no colon or spaces, at most 64) and the password (at most
128), and refuses an empty one while none is stored. Never put a password in a log, an entity or the
diagnostics.

**Order.** `settingsOrder` puts the core's Dashboard, Screen and Network first, then the panes'
sections in definition order (Voice, Music, Radio, Wall base, Time zone), then Log, Security and
About: the Manage page's nav (#33). A section without items is left out. The panel's menu draws them
in the same rail as setup (`ui/GroupedRail`): the panes' sections under a Panes group that opens while
one of them is picked. Panes whose `master` is off fold under "Off · N" at the end of the group
(#212); the fold opens while one of them is picked. Every pane's row has a status chip after its name
(#243): Running, On, Off, Can't run or Problem, from `paneState`, which the Manage page uses too. A
section's `status` is the pane's own state and line (the radio's role, Voice's wake word); without
one, its switch says On or Off. Status colours mean status only, and every name starts at the same x.

**A pane's section has its on/off.** `paneSections` gives each pane's section a `master`, the
switch at the top right of its page, made from the pane's setup card (`setupChoice`), else from its
`switch`, unless the pane gives its own (the radio's asks before turning off). `unavailable` says, in
a few words, why the pane can't run now (the radio without its root helper): the nav greys it with
"Can't run" and its page says why, without a switch. A pane whose `requires` the frame doesn't
`provide` is `CantRun`: it has no section, so the rail, the Manage page and setup don't show it at all
(#23; maintainer, 2026-11-15).

**Settings inside a page follow the same rule (#471).** The Screen page shows a row only when the device
can back it. `ScreenHardware.of` (core) is the one answer both surfaces read: who senses presence (the
frame's sensor, whose range changes only when `PresenceSource.ranged`; else a pane with `givesPresence`,
like the camera; else nobody), whether `LightSource.available`, and whether the screen takes a tap.
Without presence there is no wake switch or range. A pane that could give presence but is off gets a row
that opens its page. Without a light sensor there is no Brightness row, and without touch no link to
Android's settings.

**Every pane's page shows its state (#212).** No page says the pane has no settings.

- A page reads its running pane: override `settings(running: Pane?)` instead of `settings()`. `running`
  is the pane the shell built, also while it is off, or null while the panel's device isn't running
  (`GlassworksRuntime.panes`). Show what Home Assistant has for the pane, and the controls it has there.
- A pane with no section of its own gets one built from its entities (`inHomeAssistant`): each name
  and its state as Home Assistant shows it (`ApiEntity.shownState`). Entities off by default in HA are
  dimmed.
- A page of its own says which of the pane's entities it covers (`SettingsSection.covers`: it shows
  them, or leaves them out on purpose). The core then ends the page with "In Home Assistant" for the
  other entities, or adds nothing when none is left, so no entity is listed twice. A page that leaves
  `covers` null gets nothing added.
- Entities another page shows (the wall base's Aqara cloud is on the S1's Device page) go in the
  definition's `shownElsewhere`: they are left out of the pane's own "In Home Assistant", with the pane on
  or off.
- While a pane's `switch` is off, its page is the switch's `offNote` (what being off means, in a
  sentence) and its entities, each `Unavailable`. Its own sections wait until it is on.
- Without a running device, the page says so (`NOT_RUNNING`).
- Every "In Home Assistant" list, the core's and a pane's own `States(IN_HOME_ASSISTANT)`, follows
  whether Home Assistant has the panel (`HaLink`, #261). Never added: one line says so, with no rows.
  Added but not connected now: a line says so, and the rows (the last known states) are dimmed. Each
  surface reads the list through `States.shown()`.

## On and off

Every pane the person may turn off has a `PaneSwitch` on its definition (#67): a flag under its own
key, in its own settings file, saved with `commit()` so a restart right after can't lose it (#185).
The default is on for the S1's panes, as before the switch.

- **The shell runs a pane only while it is on.** It builds every pane, so an off pane's entities
  stay, but starts only the ones that are on. When a switch's key changes, it starts or stops that
  pane on the device thread, then raises `SettingChanged` for the key, so a pane that has work to
  undo outside the app (the time zone, Aqara's daemon) hears its own switch after `stop`. At start,
  once it listens to the settings, the shell applies the switches again and raises `SettingChanged`
  for each pane that is off, so such a pane puts that work right after a restart too.
- **A pane can start again after `stop`.** Make the threads in `start`, not in the constructor, and
  let `stop` free them. `stop` also runs when the app stops, so a pane that must undo something only
  when it is turned off does it on its own `SettingChanged`.
- **From one switch the core makes:** the switch at the top of the pane's settings page, its card on
  setup's Panes step (`inSetup`), a Home Assistant config switch (`objectId`, always off by default
  in HA: people set it once, #132, #208; `authOnly`, since turned on a pane can open the microphone
  or a port), and the adb extra `--ez <key> true|false`.
- **A voice assistant pane** says so on its switch (`voiceAssistant`): the shell offers Home Assistant
  voice features only while that switch is on, so turning it on or off asks Home Assistant to
  reconnect. The shell names no pane for this.

| Pane | Key | HA switch | Setup card |
|---|---|---|---|
| Voice (`assist`) | `voice_enabled` | `voice` | yes |
| Music (`sendspin`) | `sendspin_enabled` | `sendspin` (the entity it always had) | yes |
| Wall base (`wall-base`) | `wall_base_enabled` | `wall_base` | yes |
| Time zone (`time-zone`) | `time_zone_enabled` | `keep_time_zone` | no |
| Diagnostics (`diagnostics`) | `diagnostics_enabled` | `diagnostics` | no |
| Bluetooth proxy (`bluetooth-proxy`) | `bluetooth_proxy_enabled` (off by default) | `bluetooth_proxy` | yes, unless the panel has no Bluetooth LE |
| Camera (`camera`) | `camera_enabled` (off by default) | `camera_pane` | no; on a panel without a camera it isn't shown |

The radio has no `PaneSwitch`: its on/off is its mode (`Off`), and its card and settings switch are
its own. Timers have none either: they come only from voice.

## The Manage page

The panel serves `http://<panel>:8099/` ([docs/manage.md](../../../../../../docs/manage.md), #33),
on the same port as the QR-code sign-in pages. The core has the whole page; a frame gives it one
`ManageHost`.

| Part | What it does |
|---|---|
| `web.HttpServer`, `web.PageShell` | The small HTTP server, and the one look every page shares, the QR pages included. Change the look in `PageShell` only |
| `manage.ManageServer` | Routes, the session gate, the `Origin` and CSRF checks, the actions. The QR paths it's given go to the QR server untouched, without a session |
| `manage.HaSignIn` | Sign in through HA as an OAuth client of the local HA only: PKCE, `auth/current_user` must say `is_admin`, the refresh token revoked at once |
| `manage.SessionStore` | The page's own sessions: a SHA-256 of the cookie on disk, 30 days when remembered, tied to the HA address |
| `manage.PanelCode` | The 6-digit code on the panel's screen: one purpose and browser, 5 minutes, 5 tries, 10 wrong in 15 min locks entry |
| `manage.ManageLog`, `LogcatTail`, `Redact` | The 7-day log across reboots, fed from the app's own logcat, and the redaction for it and the diagnostics file |
| `manage.ManageHost` | What the frame supplies: `status()` (`PanelStatus`: tiles, `PaneRow`s with a `PaneState`, the settings sections), `actions`, `diagnostics()` |

The settings sections are the ones the panel's menu draws, from the frame's one model (the S1's is
`S1Settings.model`), and the Manage page draws each page as the menu does (#242): the heading with
"Panes ›", the `master` switch at top right, the items in the same order and words. A `Switch` and a
`Slider` change in their row; a `Text`, `Duration`, `TimeRange` or `Choice` opens its sheet as a
dialog. Each change is a POST to `/settings` (CSRF token, `Origin` check) with the section, the item's
key and the value, applied with `SettingsChange.change` on the thread `ManageHost.onMain` gives (the
main thread on Android), so a change from the browser takes the panel's own path. A `Switch` that
asks first on the panel asks here too, and the change runs only with that dialog's answer: a one-shot
token for the same session and change, good for five minutes and dropped when that session signs out (#269). Either sign-in may change any item, a pane's `master`
included (maintainer, 2026-11-15: the sign-in is the only extra layer). An `Action` is a button that
posts `run`; one with a `confirm` asks first. One with `needsCode` (factory reset, setup over the network) then shows a code on the panel and runs only when that code comes back, as Reboot does; the panel's own menu, where the person already stands, asks only its `confirm`, and a page on another panel can't run any `Action`. A `Native` draws its `twin`'s rows (#269), and a `Link`
its `twin` under its title (#518). Only a `Link` to another app (Android's or Aqara's settings, the
licences) says "On the panel". A pane's row (`manage.paneRow`) takes its name and on/off from its section: the title,
`unavailable` for Can't run, the `master` switch (#67) for Off; its chip is `chipWord` and
`chipTone`, as on the rail. Off panes fold under "Off · N" in the nav, as in the rail.
`PanelManageParityTest` walks every item the menu draws and fails when one is missing on the Manage
page, or changeable on one side only.

**One look (#243).** `dev.glassworks.brand.Tokens` holds glassworks.dev's colours, the chip, the
switch, corners and the settings row: the panel's `Glass` and the pages' CSS (`PageShell.TOKENS`,
`ManagePages.MANAGE_CSS`) read it, and `TokensTest` pins it to the brand's
`kiln/packages/brand/src/tokens.css`.

## Android versions

Code outside `dev.glassworks.platform` never reads `Build.VERSION.SDK_INT`. If you need a version
check, add a query to `Platform` that names the capability and says in its KDoc where the boundary
comes from, then call it. `PlatformTest` pins each boundary, and `PlatformConfinementTest` fails
`./gradlew test` with the file and line of any other read. The libraries in `libs/` do not depend
on Glassworks, so each keeps its own checks in an internal `Compat.kt`, which the same test allows.

## Setup hotspot

A panel with no working Wi-Fi opens its own, so a phone can join it and open the setup page (#197, the
rule decided in #38). `SetupHotspotPolicy` (`dev.glassworks.setup`) holds the rule, and `SetupHotspot`
keeps a `dev.glassworks.platform.Hotspot` on exactly while the rule says so:

1. It starts when the panel has had no connection for 3 minutes since the app started, or when the
   setup screen asks for it.
2. It never runs while the panel is connected.
3. It stops when Wi-Fi details arrive (`configured`), after 15 minutes, on Stop or at the end of
   setup (`cancel`), and when the service ends (`close`). After `configured` or 15 minutes the saved
   network gets 3 more minutes, then rule 1 applies again.

The automatic start is armed only until the panel first connects: a panel that has been online since
it started doesn't take itself off a router that reboots. Stop disarms it until Wi-Fi is configured
again. Wi-Fi details sent while the panel is still on its old network keep it armed for 3 minutes, so
a new network that fails brings the hotspot back. An ordinary app can't list the saved networks since Android 10, so "none saved" waits the same 3
minutes; the setup screen can ask for the hotspot at once.

`Hotspot.of` picks the way by `Platform.hotspotKind`:

| Android | Frames | How | Network | Permission |
|---|---|---|---|---|
| 8 and newer | S1 (Android 10), tablets | `startLocalOnlyHotspot`, a public API (`LocalOnlyHotspot`) | Android's name (`AndroidShare_<n>`) and random WPA2 key | Fine location and location on (8 to 12), `NEARBY_WIFI_DEVICES` (13 and newer) |
| 6 and 7 | Mirror, API 23–25 tablets | The hidden `setWifiApEnabled`, by reflection (`HiddenApHotspot`) | `Glassworks-XXXX` and a random key | `CHANGE_WIFI_STATE` and `WRITE_SETTINGS`: a system app such as the Mirror's gets it at install, any other app needs "Modify system settings" allowed |

The S1 needs no root for it: the local-only hotspot is open to any app in the foreground, and the
kiosk's foreground service counts. The hidden API changes two things on the device, and
`HiddenApHotspot` puts both back whenever the hotspot ends: the device's saved hotspot settings, and
the Wi-Fi client that Android turns off to start the AP. Android keeps that AP up after the app dies, so
what a start changes is kept in a sealed file until it is put back, and the app's next start puts back
what a run that died left behind. On most devices, the S1 among them, the Wi-Fi
client is off while the hotspot runs. A captive portal needs root, so only a frame whose agent declares
`hotspot-portal` can have one ([docs/frame-agent.md](../../../../../../docs/frame-agent.md)): on the S1, a phone that
joins shows its Wi-Fi sign-in prompt, which opens the setup page. The address on the screen stays the way in when no
prompt comes up, and on every other frame.

`HotspotPermission` decides what the setup screen shows before it asks: the reason first, then
Android's dialog, and where to allow it once Android won't ask again.

## Frames

A frame (`dev.glassworks.frame.Frame`) is what the core asks the device:

| Member | What it is |
|---|---|
| `board` | The `board` TXT record in the mDNS advert. |
| `presence`, `light` | The sensors `ScreenPolicy` reads. Null when the device has none. A `PresenceSource` reports near or not with the raw value, and may support `setRange(metres)`. |
| `cpuThermalZone` | The `type` of the thermal zone that measures the CPU, for the diagnostics pane. Null (the default) means no temperature entity. The S1's is `cpu_thermal_zone`. |
| `dock` | Whether the device docks, for the core's *Docked* sensor: `NO` (the default, the S1: no sensor, nothing listens), `IF_SEEN` (the sensor comes once the device reports a dock, and stays) or `YES` (there from the start). `GenericAndroid` says `YES` for a frame with `"dock": true` or a device with Google's dock manager ([docs/esphome.md](../../../../../../docs/esphome.md#docked)). |
| `cameraMaxPixels` | The most pixels the camera and its stream may run at, from the frame's `frame.json` (`camera.maxPixels`). Null (the default) means the camera pane's own limit, 1080p. The Mirror's is 1280 × 720. |
| `cameraSensorDegrees` | How the camera's sensor really sits, from the frame's `frame.json` (`camera.sensorDegrees`), for a device whose camera reports it wrong. Null (the default) goes by Android's sensor orientation. The Mirror's is 180. |
| `systemSettingsIntents()` | The device's own settings screens, best first. `Frame.systemSettings(context)` picks the first that resolves. |
| `wifiSettingsIntents()` | Where to choose a Wi-Fi network, best first. The default is Android's Wi-Fi panel and settings. |
| `paneDefinitions(context)` | The definitions of the frame's panes: their setup steps, settings sections and adb extras. |
| `panes(ctx, device)` | The panes only this device has. The app builds them with its own, once per device run, and the device runs them like any other. |
| `agent` | The frame's agent, the service outside the app that does what needs root ([docs/frame-agent.md](../../../../../../docs/frame-agent.md)): what it `declared` in frame.json, the `version()` the running one reports, whether it `offers(capability)` now, and the transports the core and panes use: `appUpdates` (app updates without Android's confirmation; null means PackageInstaller), `bluetoothAddress()`, and `radio`, `timeZone` and `bundle` (`RadioAgent`, `TimeZoneAgent`, `BundleAgent`), through which the radio and time zone panes send every request and read every status. A pane checks a capability, never the frame's id. `NoAgent` (the default) declares and offers nothing. The S1's is `S1Agent`: its root helper and its update and bundle services, by the names in `dev.glassworks.s1.radio.RadioHelper` and `S1TimeZone`. With `hotspot-portal` declared, the app runs the setup hotspot's portal listeners (udp 8053, tcp 8098), and the agent sends the hotspot's DNS and port 80 to them (#197). |
| `frameRelease` | The frame's own version and releases (#139, `FrameRelease`): its id, the version the device carries in `ro.glassworks.frame.id` and `ro.glassworks.frame.version`, and how an update installs. The core adds a *Frame version* row to the Device page and a *Frame update* entity for it. Null (the default) means neither: a device its vendor updates. The S1 and the Mirror have one. |
| `shortName` | A short name for the panel on its own pages (the S1's `S1 Plus`). Null (the default) means its friendly name. |
| `systemSettingsTitle` | What the settings call the device's own settings app when it isn't Android's (the S1's: Aqara's). Null (the default) means Android's. |
| `input` | `TOUCH` (the default), or `NONE` for a screen that takes no tap (the lululemon Mirror's). With `NONE` the panel only displays during setup: the setup page's address and QR code, and the pairing code without Close. Setup happens from a computer over the network (`/setup`, #34). |
| `serviceWork(context, onForegroundOnly)` | Work the frame runs while the app's service runs: the S1's Wi-Fi scans while the screen is off (#3). Null (the default) means none. |
| `deviceSection(context, running)` | The frame's Device page: settings and facts about the device that belong to no pane. Every settings surface lists it under Panel, after Screen, named after the frame ("S1 Plus"; a generic device's model). `running` gives a running pane by id. Null (the default) means no page. |

The app makes one frame at start, and `GlassworksRuntime` holds it. The device shell
(`PanelDevice`) reads only these members; `PanelDeviceFrameTest` fails if it reaches past them.

The S1's frame is [`AqaraS1Plus`](../../../../../../frames/aqara-s1-plus/android/src/main/java/dev/glassworks/s1/frame/AqaraS1Plus.kt) (`aqara-s1-plus`). Its
presence source is the VI5300 time-of-flight sensor, with Lumi's `proximity_threshold` range table,
and its light source is the MK6212 sensor. Its panes are the wall base (with Aqara's cloud daemon,
which holds the wall base UART) and the time zone. It also holds its agent (`S1Agent`, with the root update service) and
the Wi-Fi scans while the screen is off (`serviceWork`). The rest of the planned frame (installer) comes in
the next migration steps.

## Engine

The shell shows the dashboard through `dev.glassworks.engine.Engine`, not through GeckoView.
`engine.open(activity, host, background)` gives a `Page`. The shell adds `page.view` to its layout
and calls `load`, `reload`, `goBack`, `restart` and `post`. The page reports to the shell's
`PageHost`, always on the main thread:

| `PageHost` call | When |
|---|---|
| `onPageStart(url)`, `onPageStop(success)`, `onFirstPaint()`, `onLocationChange(url)` | The page loads. |
| `redirect(url)` | Before each top-level load in the page's own window. Return a URL to load instead, or null. |
| `onContentGone(killed)` | The content process crashed or was killed. The shell calls `page.restart()` and loads again. |
| `onAndroidPermissions(permissions, answer)` | The page needs Android runtime permissions, which only an activity can ask for. |
| `onConnect(port)`, `onPortMessage(port, message)` | A page script opened a native port, or sent a JSON object on one. `PagePort` has the page's `url` and `topLevel`. |

`engine.isContentProcess(name)` says whether one of the app's processes is a web content process.
The diagnostics pane counts them. For GeckoView these are the `:sandboxed_tab…` processes (`:tab<n>` on
Android 6 and 7).

It is a kiosk engine. It allows every page permission without a prompt and grants the microphone and
the camera. It reads `EngineSettings` (secure-context hosts, zoom, remote debugging) once, at the
first `open`, so a change needs a process restart. `engine.started` says what it started with.

`GeckoEngine` takes the S1's values: the built-in extension (`assets/extension`, id
`helper@s1kiosk.edm.sh`, native app `s1kiosk`), the log tag and the settings from `KioskPrefs`.
GeckoView allows one runtime per process, so the app makes the one engine (the S1's: `KioskApp`) and
hands it to the `GlassworksRuntime` it runs, which holds it.
`EngineConfinementTest` fails `./gradlew test` if code outside `dev.glassworks.engine.gecko` names
`org.mozilla.gecko`.

### The legacy engine

Android 6 and 7 (API 23–25) can't run current GeckoView, so they get a frozen one: GeckoView 144, in
`engines/engine-gecko-legacy` (#198). It compiles engine-gecko's `GeckoEngine.kt` unchanged, so the
`Engine` API, the extension and every `PageHost` call are the same. Its `GeckoVariant` adds a
`PageGuard`, and the current engine has none. A frozen engine gets no security fixes, so the guard
holds the page to one origin: the configured dashboard's (`EngineSettings.dashboard`), taken once when
the engine starts. Nothing in the session moves or widens it, not even the app's own `page.load` of
another origin, which is refused. A new dashboard address restarts the process: the settings menu's
field does, and so does the installer when `KioskPrefs.engineStart()` changes.

`engine.lockedDown` is true for the legacy engine (its `GeckoVariant.LOCKED_DOWN`). The Manage page's Overview then
shows a Legacy chip that says what it can't do, and the core ignores the `remote_debug` extra (#29).

| What the page tries | Legacy engine |
|---|---|
| A page, iframe, image, fetch or WebSocket from the dashboard's origin | Loads |
| The same from another origin (map tiles, web cards, cross-origin iframes) | Refused, with a note at the bottom of the page |
| A new window (`window.open`, `target=_blank`) to the dashboard's origin | Loads in place, as on the current engine |
| A new window elsewhere, a download, a redirect, form or meta refresh to another origin | Refused, with a note |
| WebRTC, and Gecko's own early connections (DNS prefetch, preconnect) | Off: HA's frontend uses HLS for cameras |
| Anything, before the lockdown extension is up | Waits; after 20 s, or if the extension can't start, the page says so and nothing loads |

A pane that needs another origin in the page doesn't work on the legacy engine; none of today's do
(their `requires` are hardware only). Which core features and panes run on Android 6–7, and what each
still calls above API 23, is in [legacy-engine.md](../../../../../../docs/research/legacy-engine.md#what-runs-on-the-legacy-engine),
with the research and the emulator and Mirror runs.

## Threads

The core calls a pane from more than one thread. `start` runs on the device thread, `stop` runs
from the service when the panel stops and on the device thread when the pane is turned off, and
`onEvent` runs on whichever thread the event happened. A pane guards its own
state. `publishStates()` is safe from any thread, including while the panel stops.

A pane that throws in `bind`, `start`, `stop` or `onEvent` is logged (`pane <id> failed in <step>`)
and skipped. The other panes still run. An interruption during `bind`, `start` or `onEvent` is the
exception: it means the panel is stopping, so it goes back to the caller and the panes after it
don't run. `stop` always reaches every pane: `stopAll()` reports the interruption, and the shell
sets the flag again at the end of its own shutdown.

## Licence

Glassworks Core is licensed under the [Mozilla Public License 2.0](../../../../../../LICENSES/MPL-2.0.txt):
the files in `core/`, `pane-api/`, `engines/` and `panes/`. A few files there keep their own MIT header because
they were ported from an MIT project: the camera pane's ONVIF, RTSP and motion detection code, which came from
android-ip-camera ([THIRD-PARTY-NOTICES.md](../../../../../../THIRD-PARTY-NOTICES.md)).

The rest of the repository is MIT, including the libraries in [`libs/`](../../../../../../libs)
(`dev.glassworks.esphome`, `dev.glassworks.improv` and the others) and the apps built on the core, such as the S1
app and the generic Android app.

The MPL works file by file. You can ship Glassworks inside a closed product, and your own frames
and panes, in their own files, can use any licence. Changes to Glassworks' own files must be
published. The reasoning is in the planning repo's decision log.

Saying "Built on Glassworks" is fine. Naming a product Glassworks or using the mark needs
permission: see the [trademark policy](https://www.glassworks.dev/trademarks).
