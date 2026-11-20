# Blueprints

Some panel features—such as dimming screens at night, ringing panels when someone presses the doorbell, or turning on hallway lights when you walk past—rely on Home Assistant automations. Rather than making you write that YAML from scratch, Glassworks ships ready-made [Home Assistant blueprints](https://www.home-assistant.io/docs/automation/using_blueprints/) alongside the features that use them.

A blueprint is simply a YAML template imported into Home Assistant's own config folder through Home Assistant's built-in import dialog. Nothing extra is installed in Home Assistant, and the panel never writes to your Home Assistant server. You can edit or delete any imported blueprint anytime under **Settings → Automations & scenes → Blueprints**.

> [!NOTE]
> **Feature status:** All five blueprints pass Home Assistant's blueprint validation checks, and *Panel night mode* has been verified against Home Assistant. Full end-to-end testing on wall panels is ongoing.

## The blueprints

| Settings page | Blueprint | What it does |
|---|---|---|
| **Screen** | **Panel night mode** | Shortens idle screen timeout and caps auto-brightness at night, then restores your daytime settings in the morning ([details below](#panel-night-mode)). |
| **Notifications** | **Doorbell on Glassworks panels** | Rings selected panels, displays a camera snapshot, and opens your doorbell view when the doorbell is pressed ([details](notifications.md#the-doorbell)). |
| **Notifications** | **Ask on the panels** | Displays an interactive question with up to three action buttons across selected panels; each button runs a script you choose ([details](notifications.md#ask-on-the-panels)). |
| **Camera** | **Lights on at the panel** | Turns on chosen lights when the panel's camera detects motion and turns them off again after a configurable delay. |
| **Camera** | **Snapshot when away** | Sends a snapshot from the panel's camera to your phone if motion is detected while nobody is home. |

You can inspect the YAML source files directly in each pane's `blueprints/` directory (such as [`panes/notifications/blueprints/`](../panes/notifications/blueprints/)) and in [`core/blueprints/`](../core/blueprints/) for Screen blueprints.

## Panel night mode

Glassworks panels do not run their own internal clock schedules—following our principle that Home Assistant is the brain ([Principles](principles.md)), Home Assistant decides when night begins and ends. (Older versions up to 0.4.3 had a built-in 22:00–07:00 night mode; this blueprint replaces that feature and works across any number of panels at once.)

1. Import **Panel night mode** from the bottom of **Settings → Screen** on the panel or on the [Manage page](manage.md).
2. Create an automation from the blueprint and select each panel's **Idle timeout** (`number.<panel>_idle_timeout`). If you also want screens dimmed at night, select each panel's **Max auto brightness** (`number.<panel>_max_auto_brightness`). *(Because Home Assistant cannot filter percentage number entities automatically in the picker, be sure to pick only Max auto brightness entities—the automation ignores any entity not measured in `%`.)*
3. Configure **Night is when** using any Home Assistant condition. It defaults to a Time condition from 22:00 to 07:00, which you can easily swap for a Sun condition (after sunset, before sunrise), an alarm state, or a house sleep helper.
4. Adjust your daytime and nighttime values if desired. The defaults match the classic settings: 20 seconds and 20% max brightness at night, and 120 seconds and 100% during the day.

**How it handles manual overrides:** Once a minute, the automation compares each panel's current values against your configured day and night settings. When night starts, panels currently at the daytime value switch to the nighttime value; when morning arrives, panels at the nighttime value switch back. If you manually set a different value (for example, 5 minutes while hosting guests), the automation respects your override and leaves it alone until you return the panel to either the day or night value. Because the panel's own state acts as memory, restarting Home Assistant or rebooting a panel never gets it stuck—it catches up within a minute.

Keep two details in mind:

- Setting an idle timeout above 0 switches **Screen mode** to `presence` so the timeout takes effect. Leave panels you want to stay on continuously (`always`) out of this automation.
- **Max auto brightness** caps automatic room-light brightness only—manual brightness set via the `Screen` light entity and high/critical notification wake-ups are not capped.
- On panels added to Home Assistant in earlier releases, make sure the **Idle timeout** entity is enabled on the panel's device page first. **Max auto brightness** starts enabled automatically.

## Adding one from the panel

Open **Settings → Panes** and select a pane (or open **Settings → Screen** for *Panel night mode*). At the bottom of the page, you will see a **Blueprints for Home Assistant** card. Disabled panes and panes unsupported by your hardware are hidden.

How the import buttons behave depends on the Home Assistant account signed in on the panel:

| Panel sign-in | What you see on the card |
|---|---|
| **Administrator** | Clicking **Add to Home Assistant** opens Home Assistant's blueprint import dialog right on the screen with the URL prefilled—tap **Preview**, then **Import blueprint**. Once imported, the row shows *In Home Assistant* with a **Create automation** button that jumps straight to that blueprint. *(Requires Home Assistant's built-in `my` integration, included in `default_config`; if you don't use `default_config`, add `my:` to `configuration.yaml`.)* |
| **Non-administrator** | Clicking **Add from your phone** displays a QR code. Scan it with a phone signed in to Home Assistant as an administrator to open the import dialog in the Home Assistant app, since Home Assistant restricts blueprint imports to admins. |
| **Not signed in or offline** | Buttons are disabled with a short explanation of why. |

To check admin status and see which blueprints are already imported, the panel sends read-only queries (`auth/current_user` and `blueprint/list`) every 15 seconds while the card is open so the status updates as soon as you finish importing.

> [!NOTE]
> Blueprint import links require Home Assistant to be served at the root of its domain or IP address. Reverse-proxy subpaths (such as `https://example.org/ha/`) are not supported.

## Adding one from the Manage page

The [Manage page](manage.md) displays the same **Blueprints for Home Assistant** card on each pane's page and on the Screen page. Clicking a button opens Home Assistant's import dialog in a new browser tab, where Home Assistant verifies your permissions directly without Manage storing any extra tokens.

## Which version you get

Every blueprint link points to the exact GitHub tag matching your panel's installed version (such as `aqara-s1-plus-v0.5.0`, `generic-android-v0.5.0`, or `generic-android-legacy-v0.5.0`; test builds point to their commit, and development builds point to `main`). That guarantees the blueprint matches the entities and actions available on your panel. When a future release updates a blueprint, importing it again lets you confirm replacing the previous version in Home Assistant.

You can also browse the latest published blueprints online at [glassworks.dev/blueprints](https://www.glassworks.dev/blueprints).

## For pane authors

To bundle a blueprint with a pane or core settings page:

1. Add the YAML file under your pane module at `blueprints/<name>.yaml` (or `core/blueprints/<name>.yaml` for core pages). File names must be unique across the repository because Home Assistant saves GitHub imports as `GlassworksHQ/<name>.yaml`.
2. Register the file and a one-line summary in the pane's `pane.json` (or `page.json` for core settings):
   `"blueprints": [{"file": "blueprints/<name>.yaml", "summary": "…"}]`.
   The display title is read from the YAML's `blueprint:` block.
3. Regenerate the catalog files so `BlueprintCatalogTest` passes:
   `./gradlew :kiosk:testDebugUnitTest --tests '*BlueprintCatalogTest*' -Ps1RegenGolden=true`
   followed by `npm run panes -w @glassworks/frame-card`.
4. For a core settings page (such as Screen), wire its card into the panel UI by passing the page ID to `blueprints(...)` in `S1Settings.model`—for example, `screenBlueprints = blueprints(CoreSettings.SCREEN)`. Panes pick up their blueprint cards automatically, while core pages require this explicit model binding.
5. Add sample inputs to [`tools/blueprints/samples.yaml`](../tools/blueprints/samples.yaml) and validate them with [`tools/blueprints/ha-check.sh`](../tools/blueprints/ha-check.sh) using a Python environment with Home Assistant installed. Only reference entities and actions that the panel actually exposes.
