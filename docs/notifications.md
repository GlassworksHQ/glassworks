# Notifications

Home Assistant sends a notification, and the panel shows it over the dashboard: a title, a message, a
camera's picture, up to three buttons and a sound. A button press goes back to Home Assistant, where
an automation acts on it. A notification can also open a dashboard view, full screen or half, like a doorbell
view with its camera, and go back to the normal dashboard afterwards.

It works through the panel's ESPHome device alone. Nothing to install in Home Assistant.

**Status (2026-11-13): new, not yet run on a panel.** The unit tests and the real `aioesphomeapi`
client pass; the first run on a real panel is pending.

## Turn it on

Notifications are off until you turn them on:

- on the panel's setup screen, the **Notifications** card on the Panes step, or
- in the panel's settings: **Panes → Notifications**, the switch at the top right, or
- in Home Assistant, the panel's `Notifications` switch (it starts disabled in HA; turn it on on the
  device page first).

The settings page shows what is on the panel, has **Do not disturb**, and **Send a test notification**.

## Send one

The panel adds one action to Home Assistant: `esphome.<node name>_notify`, with the panel's ESPHome
node name, `-` as `_`. The node name ends in the panel's MAC ([Names](esphome.md#names)), so an S1's
action looks like `esphome.aqara_s1_plus_a1b2c3_notify`. The panel's settings show its exact name
(**Panes → Notifications → Action**), and *Developer tools → Actions* lists it: search for `_notify`.
The examples below use `a1b2c3`; put your panel's in.

**Who can call it.** On a panel without an API key, anyone on your network can call `notify`, so
anyone there could raise a critical alert or open a view of your Home Assistant on the panel. The
key closes that: Home Assistant 2026.11 and later sets it when you add the panel.

It takes the Home Assistant Companion app's fields: `message`, `title` and `data`. Home Assistant
makes every ESPHome action field required, so all three are always there. `title` may be empty, and
`data` is a JSON object, or `{}` for none.

```yaml
action: esphome.aqara_s1_plus_a1b2c3_notify
data:
  title: Washing machine
  message: Done. 1 h 32 min.
  data: "{}"
```

### The `data` fields

| Key | What it does |
|---|---|
| `tag` | A name for the notification. A new one with the same tag replaces it, and `clear_notification` closes it |
| `priority` | `low`, `normal` (the default), `high` or `critical`. See [Priorities](#priorities) |
| `image` | A picture: a path on this Home Assistant, like `/api/camera_proxy/camera.porch`, `/local/…` or `/media/…` |
| `entity_id` | A camera, like `camera.porch`, when there is no `image` |
| `actions` | Up to 3 buttons: `[{"action": "open_gate", "title": "Open the gate"}]`. Add `"uri": "/lovelace/garage"` to also open that view |
| `sound` | `chime`, `doorbell`, `alert` or `none`. Without it: none for low, a chime for normal and high, the alert for critical |
| `timeout` | Seconds until it closes by itself |
| `url` | A dashboard view on this Home Assistant, like `/dashboard-door/0`. It opens at once, or after the view that is open. See [Open a view](#open-a-view) |
| `sticky` | `true`: a button press leaves it open |
| `size` | `full` (the default) or `half`: the view, and the views of its buttons' `uri`, as a dialog over half the screen |

Write `data` as a JSON string. A block string is the easiest to read:

```yaml
action: esphome.aqara_s1_plus_a1b2c3_notify
data:
  title: Parcel at the door
  message: The porch camera saw a delivery.
  data: >
    {"tag": "parcel", "priority": "high", "image": "/api/camera_proxy/camera.porch",
     "actions": [{"action": "open_gate", "title": "Open the gate"},
                 {"action": "snooze", "title": "Snooze 10 min"}]}
```

With values from a template, build it with `to_json`:

```yaml
data:
  title: "{{ trigger.to_state.name }} is open"
  message: "Open since {{ now().strftime('%H:%M') }}"
  data: "{{ {'tag': 'door', 'priority': 'high', 'timeout': 600} | to_json }}"
```

A plain YAML mapping under `data` doesn't work: Home Assistant refuses it ("value should be a string").

### Coming from the Companion app

Change the action's name and put `data` in a string. Keys the panel doesn't use (`channel`, `color`,
`ttl`, `group`, …) are ignored. iOS's `push: {interruption-level: …}` works as a priority when there
is no `priority`. The call fails, with the reason in the script's trace, when:

- `sound` isn't one of the panel's four,
- there are more than 3 actions, or one is `REPLY` (the panel has no keyboard),
- `url`, `image` or an action's `uri` points anywhere but this Home Assistant,
- `timeout` isn't a number of seconds between 1 and a day.

One difference: on a phone, `url` opens when you tap the notification. On the panel it opens at once,
which is what a doorbell wants. Use an action's `uri` for "open it when I tap". A critical one is the
exception: see [Open a view](#open-a-view).

### Clear one

```yaml
action: esphome.aqara_s1_plus_a1b2c3_notify
data:
  message: clear_notification
  title: ""
  data: '{"tag": "parcel"}'
```

## Open a view

`url` opens a dashboard view of this Home Assistant on the panel, in the dashboard that is already
loaded, and goes back to the dashboard afterwards.

```yaml
action: esphome.aqara_s1_plus_a1b2c3_notify
data:
  title: Doorbell
  message: Someone is at the door.
  data: '{"tag": "doorbell", "url": "/dashboard-door/0", "size": "half", "timeout": 90}'
```

- **Full or half.** Without `size`, the view takes the whole screen. With `"size": "half"` it is a
  dialog over half the screen: a panel on the right of a landscape screen (the S1), a sheet at the
  bottom of a portrait one. The dashboard stays behind it as a still picture, dimmed. On the S1 the
  picture takes under 0.1 s. If it isn't there 0.75 s after the screen is on, the view opens over a
  plain dark backdrop instead. With the screen off, the picture is taken when the screen comes back
  on. A normal notification doesn't wake the screen, so that is when someone walks up.
- **One button.** A bar at the foot of the view shows its title, the time left, and one button. It
  says *Next* when another view is waiting, *Back now* on the last full view, and *Close* on the last
  half one. Tapping outside a half view does the same, and so do Back and the time running out.
- **One at a time.** The open view keeps the screen. A view that arrives meanwhile waits its turn, and
  the bar says "Next in 0:24 · 1 more waiting". A new one with the tag of a waiting one takes its
  place in the line. A button's `uri`, or a tap in the list, opens at once, in front. A waiting view's
  `timeout` keeps running, so one that runs out while it waits never opens.
- **Critical ones don't open by themselves.** A critical notification is the alert screen, always.
  With a `url`, the alert gets an **Open the view** button, which opens it full screen. That matches
  the phone, where `url` opens when you tap the notification.
- **A screen without touch** (the lululemon Mirror) has no button. The view moves on when its time runs
  out, or when Home Assistant clears it. A view with no `timeout` closes after 2 minutes there.
- Do not disturb holds a view like any other notification: it goes to the list, and opens from there.

## When someone presses a button

The panel fires the event `esphome.notification_action` in Home Assistant, with the button's `action`,
the notification's `tag` (if it has one), and the panel's `device_id`. This is the shape of the
Companion app's `mobile_app_notification_action`.

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

It is an event on Home Assistant's bus, not an event entity, so it doesn't show on the device page.
An event entity can only fire the names it listed up front, and a button's name is whatever your
script chose ([why](research/ha-esphome-actions-events.md)). It needs no "Allow the device to perform
Home Assistant actions" option. Listen for it in *Developer tools → Events* to see what arrives.

## Priorities

| Priority | On the panel | Sound | Screen |
|---|---|---|---|
| `low` | A card when the screen is on; folds into the list after 10 s | none | Waits for the screen to wake |
| `normal` | A card; folds into the list after 15 s | chime | Waits for the screen to wake |
| `high` | A card marked *Important*, until answered | chime | Wakes it, at full brightness also at night |
| `critical` | The alert screen, until answered or dismissed | the alert, every 4 s for about 2 minutes (the last one may end 1.45 s past) | Wakes it, at full brightness also at night |

The sounds are made by the app from sine tones
([`NotificationSounds.kt`](../panes/notifications/src/main/java/dev/glassworks/notifications/NotificationSounds.kt),
MPL-2.0 like the rest of the core). There are no recordings, so no other licence applies.

The screen stays on for a high or critical one while it is up, for at most 10 minutes, so one nobody
answers doesn't keep a room lit all night. A sound pauses the music, as the voice assistant does.

## Do not disturb

The panel's `Do not disturb` switch in Home Assistant (`switch.aqara_s1_plus_do_not_disturb` on a new
S1; `glassworks_panel_*` or `s1_panel_*` on one added earlier: [Names](esphome.md#names)), the same
switch in the panel's list, and on the settings page. While it is on, every notification but a critical one goes to the list without a
sound or a card, and the screen stays as it is. Turning it on also does that to the ones already up:
a high one's card goes to the list and lets its hold on the screen go, so the screen goes dark at the
idle timeout; a critical one stays, and keeps the screen on. A pill at the top right says how many are held. When it turns off, the held ones stay
in the list. Bedtime is one automation:

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

**N notifications · Show all** under the cards (or the pill) opens the list. Tap one to see it again.
The list is kept until the panel restarts: Home Assistant holds the facts and can send again.

## The doorbell

No doorbell pane: your doorbell is in Home Assistant already, and a dashboard does the rest.

1. Make a dashboard view for the door: the doorbell's camera, an *Open the gate* button, and a *Done*
   button. The panel gives the page its microphone, so a camera card with two-way audio works.
2. Import the blueprint *Doorbell on Glassworks panels*
   ([`panes/notifications/blueprints/doorbell-panels.yaml`](../panes/notifications/blueprints/doorbell-panels.yaml)):
   on the panel, **Settings → Panes → Notifications → Blueprints for Home Assistant → Add to Home Assistant**
   opens Home Assistant's import dialog with it filled in ([Blueprints](blueprints.md)). Pick the
   doorbell, the camera and the view, and type each panel's notify action: `esphome.` plus the
   panel's ESPHome node name with `-` as `_`, plus `_notify`, like `esphome.aqara_s1_plus_a1b2c3_notify`.
   Each panel's settings show its own (**Panes → Notifications → Action**).
   Home Assistant has no selector that picks one action, so they are typed. When it rings, every panel rings,
   shows the camera's picture and opens the view; each goes back to its dashboard after the timeout.
3. Make *Done* run a script that clears `doorbell` on every panel, so the others stop too. The
   blueprint's file has that script at the end, to copy.

Not yet run with a real doorbell (2026-11-13).

## Ask on the panels

The blueprint *Ask on the panels*
([`panes/notifications/blueprints/ask-panels.yaml`](../panes/notifications/blueprints/ask-panels.yaml))
puts a question with up to three buttons on the panels you pick, when its trigger fires (a time, a door,
anything Home Assistant can trigger on). Each button runs a script you choose. The first answer, on any
panel, clears the question from the others; with no answer it closes by itself after the time you set.
It listens for `esphome.notification_action` with the question's tag, so other notifications' buttons
don't count. Add it from the same card as the doorbell.

Not yet run on a panel (2026-11-15).

## When notifications are off

`esphome.<panel>_notify` stays in Home Assistant, like every pane's entities (#67), and answers
"Notifications are off on this panel". Turn them on, and the same scripts work.

## What it can't do yet

- Pictures need the panel's dashboard sign-in (the QR code) on the same Home Assistant. Without it,
  the card says "No picture" and why.
- Pictures from other sites: only this Home Assistant's. Put a camera or a file in Home Assistant
  instead.
- A sound from Home Assistant (`media_id`): only the three built-in sounds so far.
- A live camera view on the card: open a dashboard view with `url` instead.
