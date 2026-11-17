# Blueprints

Some things a panel does need an automation in Home Assistant: ring the panels when the doorbell rings,
turn the lights on when someone walks up to the panel, dim the screens at night. Each pane that has such
automations ships them as
Home Assistant [blueprints](https://www.home-assistant.io/docs/automation/using_blueprints/). You add one
to Home Assistant, then make an automation from it and pick your own doorbell, lights and panels.

A blueprint is a YAML file that Home Assistant's own blueprint feature keeps in its config folder. Nothing
gets installed in Home Assistant, and the panel never writes to it: Home Assistant's own import dialog
adds the file, after you press Import. Delete it from **Settings → Automations & scenes → Blueprints**.

**Status (2026-11-17): new.** The five blueprints pass Home Assistant's own config check
([how](research/pane-blueprints.md)). Panel night mode also ran in a scratch Home Assistant 2026.11.2
against stand-in numbers. None has run on a panel yet.

## The blueprints

| Page | Blueprint | What it does |
|---|---|---|
| Screen | Panel night mode | At night the panels turn their screen off sooner and keep it dimmer, then go back to your day settings ([below](#panel-night-mode)) |
| Notifications | Doorbell on Glassworks panels | When the doorbell rings, the panels ring and show the door's view and camera ([the doorbell](notifications.md#the-doorbell)) |
| Notifications | Ask on the panels | A question with buttons on the panels. Each button runs a script you pick ([details](notifications.md#ask-on-the-panels)) |
| Camera | Lights on at the panel | Someone moves in front of the panel: lights on, and off again after a while |
| Camera | Snapshot when away | Movement at the panel while nobody is home sends its picture to your phone |

The files are in each pane's `blueprints/` folder, such as
[`panes/notifications/blueprints/`](../panes/notifications/blueprints/). The Screen page's is in
[`core/blueprints/`](../core/blueprints/).

## Panel night mode

The panel has no clock-based schedule of its own. Up to 0.4.3 it had one: **At night** (22:00–07:00)
with **At night, turn off after** (20 s) and auto brightness capped at 20%. Home Assistant decides when
it is night now ([principles](principles.md): HA is the brain), and this blueprint does what that
setting did, for any number of panels.

1. Add it from the panel's **Settings → Screen**, at the bottom of the page, or from the Manage page's
   Screen page.
2. Make an automation from it. Pick each panel's **Idle timeout** (`number.<panel>_idle_timeout`) and,
   to dim the screens too, each panel's **Max auto brightness** (`number.<panel>_max_auto_brightness`).
3. **Night is when** takes any condition. It starts as a Time condition from 22:00 to 07:00. For the sun,
   replace it with a Sun condition: after sunset, before sunrise. Any other condition works too, such
   as an alarm being armed.
4. The defaults are the old setting's: 20 s and 20% at night, the panel's own 120 s and 100% by day.

It checks once a minute and changes only a panel whose value differs, so a panel that was off or
restarting catches up within a minute. It keeps nothing between runs, so a restart of Home Assistant in
the night loses nothing. *Max auto brightness* limits the brightness that follows the room's light: a
brightness you set on the panel's `Screen` light, and a screen held on for a high or critical
notification, don't follow it.

A panel that Home Assistant added before this version has its *Idle timeout* entity turned off: turn it
on from the panel's device page in Home Assistant first. *Max auto brightness* is new and on.

## Adding one from the panel

Open **Settings → Panes →** the pane. Its page has a **Blueprints for Home Assistant** card between the
pane's own settings and its *In Home Assistant* list. The Screen page (**Settings → Screen**) has the same
card at its end, with Panel night mode. A pane that is off shows none, and one this
panel can't run isn't listed at all. What the buttons do depends on the panel's Home Assistant
sign-in (the QR code from setup):

| The panel's sign-in | The card |
|---|---|
| An administrator | **Add to Home Assistant** opens Home Assistant's import dialog in the dashboard, with the blueprint filled in. Press Preview, then Import blueprint. Back on the card, one that Home Assistant has shows *In Home Assistant* and **Create automation**, which opens Home Assistant's blueprint list filtered to it: tap the row to start an automation. These links use Home Assistant's `my` integration, which `default_config` loads; without it, add `my:` to `configuration.yaml` |
| Not an administrator | **Add from your phone** shows a QR code. Scan it with a phone signed in to Home Assistant as an administrator: the Home Assistant app opens the same import dialog. Home Assistant's blueprint pages are for administrators only, so the panel's own dashboard can't open them |
| Not signed in, or Home Assistant can't be reached | The buttons are off, and one line says why |

To know whether you're an administrator and which blueprints Home Assistant has, the panel asks Home
Assistant with its sign-in (`auth/current_user`, then `blueprint/list`). Both only read. It asks again
every 15 seconds while the page is open, so the card follows an import you just made.

The import link opens Home Assistant at the root of its address. A Home Assistant served under a
subpath of a reverse proxy (`https://example.org/ha/`) isn't supported.

## Adding one from the Manage page

The pane's page on the [Manage page](manage.md) has the same card. Each button is a link that opens your
Home Assistant's import dialog in a new tab, whichever way you signed in to Manage: Home Assistant checks
who you are there. Manage keeps no Home Assistant token for it.

## Which version you get

The buttons point at the file on GitHub at the app's own version: the release tag (`v0.5.0`) on a
release, the commit on a test build, `main` on a development build. A blueprint fits the panel version
that offers it. A fix to a blueprint reaches you with the next release: add it again then, and Home
Assistant asks before it replaces the one you have.

## For pane authors

1. Put the YAML in your pane's module, `blueprints/<name>.yaml`. The file name must be unique in the
   repo: Home Assistant saves an import from GitHub as `GlassworksHQ/<name>.yaml`, without the folder.
2. List it in the pane's `pane.json`, with a one-line summary:
   `"blueprints": [{"file": "blueprints/<name>.yaml", "summary": "…"}]`.
   The name comes from the YAML's `blueprint:` block.
3. Write the app's list again:
   `./gradlew :kiosk:testDebugUnitTest --tests '*BlueprintCatalogTest*' -Ps1RegenGolden=true`, then
   `npm run panes -w @glassworks/frame-card`. `BlueprintCatalogTest` fails until both are done.
4. A page of the core's own settings (Screen) lists its blueprints the same way, in a `page.json` next to
   its code, with the YAML in `core/blueprints/`. The panel's page gets the card from
   `S1Settings.model`'s `blueprints`, as a pane's does.
5. Add sample inputs to [`tools/blueprints/samples.yaml`](../tools/blueprints/samples.yaml) and run
   [`tools/blueprints/ha-check.sh`](../tools/blueprints/ha-check.sh) with a Python that has Home
   Assistant installed. Use only the entities and actions the panel really has.
