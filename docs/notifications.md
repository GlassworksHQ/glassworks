# Notifications

Glassworks lets Home Assistant display rich notifications directly over your panel's dashboard—complete with a title, message, camera snapshot, up to three action buttons, and a chime or alert tone. Tapping a button sends an event back to Home Assistant so your automations can react right away. Notifications can also temporarily open a Home Assistant dashboard view (full screen or as a half-screen dialog), making it easy to show a live doorbell camera and automatically return to your main dashboard afterward.

Everything runs over the panel's native ESPHome connection, with nothing extra to install in Home Assistant.

> [!NOTE]
> **Feature status:** Notifications are stable. Core cards, camera snapshots, action buttons, Do not disturb, and full-screen and half-screen views have been tested on hardware.

## Turn it on or off

The Notifications pane is on by default. It costs next to nothing while it waits, and it only shows something when Home Assistant sends a notification. You can turn it off, or back on, in any of three places:

- During first-boot setup, on the **Notifications** card in the Panes step
- On the panel or [Manage page](manage.md) under **Settings → Panes → Notifications** (using the switch at the top right)
- In Home Assistant, by enabling and toggling the panel's **Notifications** switch on its ESPHome device page

The Notifications settings page also lets you toggle **Do not disturb** and click **Send a test notification** to verify how alerts look and sound on your wall.

## Send one

Each panel exposes a single ESPHome action in Home Assistant: `esphome.<node_name>_notify`, where `<node_name>` is the panel's ESPHome node name with hyphens replaced by underscores (see [Names](esphome.md#names)). For example, on an Aqara S1 Plus the action looks like `esphome.aqara_s1_plus_a1b2c3_notify`. You can copy your panel's exact action name from **Settings → Panes → Notifications → Action**, or find it in Home Assistant under **Developer tools → Actions** by searching for `_notify`. The examples below use `a1b2c3` as a placeholder.

> [!IMPORTANT]
> **Encryption required:** The panel runs `notify` only for a Home Assistant that holds its ESPHome encryption key. Without the key, anyone on your local network could send a critical alert or open a view on your screen, so the call fails instead. Home Assistant 2026.11 and newer set up encryption automatically when you add the panel. On an older version, notifications wait until you update Home Assistant ([What works without encryption](esphome.md#what-works-without-encryption)).

The action accepts the same three fields as the Home Assistant Companion app—`title`, `message`, and `data`. Because Home Assistant requires all ESPHome action arguments to be present, always include all three (use `""` for an empty title and `"{}"` when you don't need extra `data` fields):

```yaml
action: esphome.aqara_s1_plus_a1b2c3_notify
data:
  title: Washing machine
  message: Cycle finished in 1 h 32 min.
  data: "{}"
```

### The `data` fields

Pass extra options inside `data` as a JSON string:

| Key | Description |
|---|---|
| `tag` | Identifier for updating or clearing a notification. Sending a new notification with the same `tag` replaces the existing one, and `clear_notification` dismisses it. |
| `priority` | `low`, `normal` (default), `high`, or `critical`. See [Priorities](#priorities). |
| `image` | Snapshot or image path hosted on your Home Assistant instance, such as `/api/camera_proxy/camera.porch`, `/local/…`, or `/media/…`. |
| `entity_id` | A Home Assistant camera entity (such as `camera.porch`) used to fetch a snapshot when `image` is omitted. |
| `actions` | Up to 3 buttons in the form `[{"action": "open_gate", "title": "Open gate"}]`. Add `"uri": "/lovelace/garage"` to also open a dashboard view when tapped. |
| `sound` | `chime`, `doorbell`, `alert`, or `none`. Defaults to `none` for `low`, `chime` for `normal` and `high`, and `alert` for `critical`. |
| `timeout` | Number of seconds before the notification closes automatically (1 second to 24 hours). |
| `url` | A dashboard view path on your Home Assistant instance (such as `/dashboard-door/0`). Opens immediately—or queues behind an already open view. See [Open a view](#open-a-view). |
| `sticky` | Set to `true` to keep the notification open after a button is tapped. |
| `size` | `full` (default) or `half`. Displays the `url` view (and any button `uri` views) either full screen or as a half-screen overlay. |

A YAML folded block string (`>`) makes JSON options easy to read:

```yaml
action: esphome.aqara_s1_plus_a1b2c3_notify
data:
  title: Package at the door
  message: The porch camera spotted a delivery.
  data: >
    {"tag": "parcel", "priority": "high", "image": "/api/camera_proxy/camera.porch",
     "actions": [{"action": "open_gate", "title": "Open gate"},
                 {"action": "snooze", "title": "Snooze 10 min"}]}
```

When using Jinja templates, serialize the dictionary with `to_json`:

```yaml
data:
  title: "{{ trigger.to_state.name }} is open"
  message: "Open since {{ now().strftime('%H:%M') }}"
  data: "{{ {'tag': 'door', 'priority': 'high', 'timeout': 600} | to_json }}"
```

Note that passing a raw YAML mapping under `data` will fail because Home Assistant expects an ESPHome string argument.

### Coming from the Companion app

If you are adapting an existing phone notification automation, change the action name and format `data` as a JSON string. Unsupported mobile keys (`channel`, `color`, `ttl`, `group`, etc.) are safely ignored, and iOS `push.interruption-level` values map automatically to `priority` when `priority` is not set.

Home Assistant rejects the call (with the exact reason shown in your automation trace) if:

- `sound` is not one of the four supported values (`chime`, `doorbell`, `alert`, `none`)
- `actions` contains more than 3 buttons, or uses `REPLY` (panels do not display an inline reply keyboard)
- `url`, `image`, or an action `uri` points outside your Home Assistant instance
- `timeout` is not a number between 1 and 86400 seconds

One behavioral difference to keep in mind: on a phone, `url` opens only after you tap the notification. On a wall panel, `url` opens right away so doorbell and security views appear automatically (except on `critical` alerts—see below). If you want a view to open only when tapped, put it in a button's `uri` field instead.

### Clear one

To dismiss an active or queued notification by tag from an automation or script:

```yaml
action: esphome.aqara_s1_plus_a1b2c3_notify
data:
  message: clear_notification
  title: ""
  data: '{"tag": "parcel"}'
```

## Open a view

Setting `url` temporarily opens a Home Assistant dashboard view inside your already loaded frontend, then switches back to your main dashboard once closed or timed out:

```yaml
action: esphome.aqara_s1_plus_a1b2c3_notify
data:
  title: Doorbell
  message: Someone is at the front door.
  data: '{"tag": "doorbell", "url": "/dashboard-door/0", "size": "half", "timeout": 90}'
```

- **Full-screen or half-screen layout:** Without `size`, the view takes over the whole display. With `"size": "half"`, it appears as a side panel on landscape screens (like the Aqara S1 Plus) or a bottom sheet on portrait screens, laid over a dimmed snapshot of your dashboard. If the snapshot takes longer than 0.75 seconds to capture—or if the screen was asleep—the panel falls back smoothly to a dark backdrop until the screen wakes.
- **Footer bar:** A slim bar at the bottom shows the view title, countdown timer, and an action button (*Next* if another view is queued, *Back now* on the last full-screen view, or *Close* on a half-screen view). Tapping outside a half-screen view, pressing Back, or letting the timeout expire also closes it.
- **One view at a time:** An active view stays on screen while any newly arriving views wait in a queue (the footer shows *"Next in 0:24 · 1 more waiting"*). A replacement notification with a matching `tag` updates its queued entry in place, and waiting views continue counting down their timeouts so stale views do not pop up later. Tapping a button `uri` or an item from the notification list opens it immediately in front.
- **Critical notifications:** A `critical` notification always shows the full-screen alert screen first. If it includes a `url`, the alert provides an **Open the view** button that launches the view full screen when tapped.
- **Non-touch displays (such as the lululemon Studio Mirror):** Because nobody can tap to dismiss an alert on a mirror, views without a `timeout` close automatically after 2 minutes, as do `high` or `critical` notifications that have neither a `timeout` nor a `tag`. Give an alert a `tag` if you want it to stay up until Home Assistant clears it. Cards and alerts there show no buttons and no close, since nobody could press them, and there is no *Show all* pill: a `normal` or `low` card disappears once it has been on screen for its usual 15 or 10 seconds.
- **Do not disturb:** While Do not disturb is active, incoming views are held in the notification list instead of interrupting the screen, and can still be opened from there manually.

## When someone presses a button

When someone taps a notification button, the panel fires an `esphome.notification_action` event on Home Assistant's event bus containing the button's `action`, the notification's `tag` (if set), and the panel's `device_id`—matching the structure of the Companion app's `mobile_app_notification_action`:

```yaml
triggers:
  - trigger: event
    event_type: esphome.notification_action
    event_data:
      action: open_gate
actions:
  - action: cover.open_cover
    target:
      entity_id: cover.gate
```

This is a standard Home Assistant bus event rather than a fixed event entity, so you will not see it listed on the device page (Home Assistant event entities cannot accept arbitrary button names chosen by your automations—see [our technical note](research/ha-esphome-actions-events.md)). It works out of the box without enabling *"Allow the device to perform Home Assistant actions"*. You can inspect incoming payloads anytime in **Developer tools → Events**.

## Priorities

| Priority | On-screen behavior | Default sound | Screen wake |
|---|---|---|---|
| `low` | Shows a card when the screen is awake; folds into the list after 10 seconds | None | Waits until the screen wakes |
| `normal` | Shows a card; folds into the list after 15 seconds | Chime | Waits until the screen wakes |
| `high` | Shows a card labeled *Important* until answered or dismissed | Chime | Wakes the screen at full brightness (even at night) |
| `critical` | Opens the full-screen alert overlay until answered or dismissed | Alert tone every 4 seconds for ~2 minutes | Wakes the screen at full brightness (even at night) |

All notification tones are synthesized cleanly inside the app from sine waves ([`NotificationSounds.kt`](../panes/notifications/src/main/java/dev/glassworks/notifications/NotificationSounds.kt)), so no external audio files are required.

High and critical notifications keep the screen awake for up to 10 minutes while unanswered, ensuring an alert does not leave a room lit all night. Playing a notification sound briefly pauses active music just like Assist voice runs do. The critical alert tone plays at full volume, even when the panel's volume is low or muted, and the volume goes back to where it was after each tone.

## Do not disturb

Turn on **Do not disturb** from Home Assistant (`switch.<panel>_do_not_disturb`), from the panel's notification drawer, or on the Notifications settings page.

While Do not disturb is on:

- Every notification except `critical` goes straight into the notification list silently without waking the screen.
- Any `high`-priority cards currently on screen move quietly into the list and release their screen wake lock.
- A compact pill in the top-right corner shows how many notifications are waiting.
- `critical` alerts still break through with full sound and screen wake.

Turning Do not disturb off leaves held notifications waiting in the list until you review or clear them. Automating bedtime takes just a few lines of YAML:

```yaml
triggers:
  - trigger: time
    at: "22:30:00"
actions:
  - action: switch.turn_on
    target:
      entity_id: switch.aqara_s1_plus_do_not_disturb
```

## The list

Tap **N notifications · Show all** below the cards (or tap the Do not disturb pill) to open the notification list, and tap any item to view it again. The list is kept in memory until the panel restarts. A screen without touch has no list to open; the Manage page still shows what is in it.

## The doorbell

You do not need a dedicated doorbell plugin—your doorbell is already in Home Assistant, and a dashboard view handles the interface:

1. Create a dedicated Home Assistant dashboard view for your front door with your camera card, an *Unlock* or *Open gate* button, and a *Done* button. Glassworks grants the dashboard access to the panel's microphone, so camera cards with two-way audio work automatically.
2. Import the **Doorbell on Glassworks panels** blueprint ([`panes/notifications/blueprints/doorbell-panels.yaml`](../panes/notifications/blueprints/doorbell-panels.yaml)) from **Settings → Panes → Notifications → Blueprints for Home Assistant → Add to Home Assistant** (see [Blueprints](blueprints.md)). Pick your doorbell trigger, camera, and door view, and list each panel's `notify` action (such as `esphome.aqara_s1_plus_a1b2c3_notify`, shown on each panel's Notifications settings page). When the doorbell rings, every listed panel chimes, displays the camera snapshot, opens the door view, and returns to its normal dashboard after the timeout.
3. Wire your *Done* button to a script that clears the `doorbell` tag across all panels so dismissing it in one room clears the rest. A ready-to-copy clear script is included at the bottom of the blueprint file.

## Ask on the panels

The **Ask on the panels** blueprint ([`panes/notifications/blueprints/ask-panels.yaml`](../panes/notifications/blueprints/ask-panels.yaml)) lets you send an interactive question with up to three buttons to one or more panels whenever any Home Assistant trigger fires (such as a time of day or an open garage door). Each button runs a Home Assistant script you choose. As soon as someone answers on any panel, the prompt clears automatically from all the other panels; if nobody responds, it dismisses itself when its timeout expires. Import it from the same **Blueprints for Home Assistant** card on the Notifications page.

## When notifications are off

If you turn the Notifications pane off, the `esphome.<panel>_notify` action stays registered in Home Assistant so your automations do not break, and replies with *"Notifications are off on this panel"*. Turn the pane back on anytime and your existing automations resume immediately.

## What it can't do yet

- **Camera snapshots require dashboard sign-in:** Fetching images from Home Assistant requires the panel to be signed in to that same Home Assistant instance (via QR sign-in). Without it, the card shows *"No picture"* with the reason.
- **Local Home Assistant images only:** External image URLs are blocked for security; serve images through Home Assistant (`/api/camera_proxy/…`, `/local/…`, or `/media/…`).
- **Built-in sounds only:** Custom Home Assistant `media_id` notification sounds are not supported yet—use `chime`, `doorbell`, `alert`, or `none`.
- **Live video on cards:** Cards display still snapshots. For live video, open a dashboard view using `url`.
