# ESPHome entities and device reference

Glassworks exposes your entire panel to Home Assistant as **one native ESPHome device** over the ESPHome API (TCP port `6053`). You do not need to install any custom integration, HACS add-on, Wyoming satellite, or MQTT broker—Home Assistant's built-in ESPHome integration discovers the panel automatically and gives you:

- **Screen and sensor controls:** Control the display like a dimmable light, read presence and ambient light levels, adjust idle timeout and zoom, or switch dashboards remotely.
- **Home Assistant Assist satellite:** On-device wake word detection (microWakeWord), Assist pipelines, spoken announcements, and voice timers ([Voice and timers guide](voice-and-timers.md)).
- **Built-in speaker:** A `media_player.<device>_speaker` entity for TTS, announcements, and audio playback, plus automatic pairing for the synchronized [Music Assistant (Sendspin)](sendspin.md) player.
- **Hardware panes:** Depending on your device, controls for the Zigbee/Thread radio, mains relay wall base, Bluetooth proxy, camera, notifications, time zone, and system diagnostics.

To keep Home Assistant's device page clean and uncluttered, Glassworks only enables everyday entities by default. One-time configuration and troubleshooting entities start disabled—you can enable any of them from the panel's device page anytime, or adjust the same settings on the panel itself or the [Manage page](manage.md).

> [!TIP]
> **Developers:** The pure-JVM ESPHome protocol implementation lives in [`libs/esphome-api`](../libs/esphome-api/README.md) (`dev.glassworks.esphome`) with mDNS discovery in [`libs/esphome-api-android`](../libs/esphome-api-android/README.md). Panel entities are wired in `core/src/main/java/dev/glassworks/device/PanelDevice.kt`, voice in [`libs/voice`](../libs/voice/README.md) and `panes/assist`, wall base in `frames/aqara-s1-plus/panes/wall-base`, and radio in `radio/RadioPane.kt`, all hosted inside the foreground `KioskService` so they keep running when the screen sleeps. View logs with `adb logcat -s Glassworks.ESPHome Glassworks.Voice`.

## Add it to Home Assistant

1. **Install Glassworks** on your panel ([Getting started](getting-started.md)). The first time voice is needed, the panel automatically downloads the four official microWakeWord models (`okay_nabu`, `hey_jarvis`, `alexa`, `hey_mycroft`) from [esphome/micro-wake-word-models](https://github.com/esphome/micro-wake-word-models) and verifies their SHA-256 hashes. *(The CLI installer also pushes them via `tools/voice/fetch-models.sh`.)*
2. **Automatic discovery:** The panel advertises `_esphomelib._tcp` over mDNS using its unique node name (for example, `aqara-s1-plus-a1b2c3`). Open **Settings → Devices & services** in Home Assistant and click **Add** on the discovered ESPHome device.
3. **Manual setup (across VLANs):** If mDNS does not cross your network segments, click **Add integration → ESPHome**, enter the panel's IP address, and use port `6053`.
4. **Configure voice (optional):** On the device page, pick your **Assist pipeline** and **Wake word**, then say the wake word to test.

### Encryption (automatic)

Glassworks uses standard ESPHome Noise encryption (`Noise_NNpsk0_25519_ChaChaPoly_SHA256`) with dynamic key provisioning:

- **Fresh panel (no key yet):** Accepts plaintext and zero-PSK Noise connections and advertises `api_encryption_supported` and `api_provisioning=zero-psk`. When you add the panel in **Home Assistant 2026.11 or newer**, Home Assistant generates a random encryption key and sends it over a zero-PSK Noise session (`NoiseEncryptionSetKeyRequest`). The panel saves it securely in the Android Keystore, activates encryption 100 ms later, drops older sessions, and reconnects encrypted—you never have to touch a key.
- **Once keyed:** Only encrypted Noise connections using that key are accepted. Logs display only a 4-hex fingerprint, never the key itself.
- **Fail-closed security:** If the Android Keystore is temporarily unavailable ([secrets.md](secrets.md#when-the-key-is-gone)), the panel refuses all connections rather than falling back to plaintext, retrying until the key is readable again.
- **Removing the device:** Deleting the ESPHome integration entry in Home Assistant clears the encryption key only when Home Assistant can reach the panel. If the panel is offline when you delete the entry, the old key remains on the panel and it will refuse automatic re-provisioning until you clear encryption manually (see **Manual key control** below).
- **Manual key control (adb only):** Set a manual key with `--es api_key $(openssl rand -base64 32) --ez api_encryption true`, or clear encryption to start over with `--ez api_encryption false` (only on a trusted network).

See [onboarding.md](onboarding.md#2-adoption--encryption-esphome-dynamic-keys-) for wire-level provisioning details.

### What works without encryption

Until Home Assistant gives the panel its key, anyone on your network can talk to the panel's ESPHome API, not only Home Assistant. That covers a panel you haven't added yet, and one that a Home Assistant older than 2026.11 added without a key. The panel keeps the everyday controls working in that case. Anything that changes which page the panel shows or who it's signed in as waits for an encrypted connection, and so do notifications, the pane switches, the camera's pictures, stream and privacy switch, and every control that changes the radio.

| Works without encryption | Needs an encrypted connection with the panel's key |
|---|---|
| Sensors and diagnostics, the screen light, screen mode, idle timeout, brightness, zoom, **Hide HA header**, the **Dashboard** select (Home Assistant's own dashboards only) | **Dashboard URL** (`dashboard_url`) |
| **Speaker**: playback, volume, TTS and announcements | **Sign out** (`sign_out`) |
| **Reload dashboard**, **Restart app** | **Start Wi-Fi setup (Bluetooth)** (`improv_ble`) and **Open device settings** (`open_settings`) |
| **Allow setup over the network**: pairing still needs the code shown on the panel's screen | The `notify` action ([Notifications](notifications.md)) |
| App and frame updates, voice, **Do not disturb**, **Camera use** and the camera's motion sensitivity | Pane switches, the time zone, the Bluetooth proxy, and the camera's snapshots, **ONVIF stream** and **Camera privacy** |
| | Every radio control: **Radio mode**, **Radio allowed clients**, **Zigbee stack**, **Join Zigbee network** and **Leave Zigbee network**, **Radio firmware image** and **Flash radio firmware**, **Join Home Assistant's Thread network**, **Leave Thread network** and **Thread dataset** |

Snapshots reach Home Assistant only over the encrypted connection, and turning on the **ONVIF stream** needs it too. Once the stream is on, though, it's read with its own sign-in (see [Camera entities](#camera-entities)), not with the ESPHome key.

Without a key, the controls in the right column do nothing when Home Assistant uses them, and the entity snaps back to its old state. A `notify` call fails, and the reason shows in the script's trace. You can still change every control on the panel itself or on the [Manage page](manage.md).

The key protects the panel from the moment Home Assistant sets it. Until then, any device on your network could set a key of its own first, so add a new panel to Home Assistant from a network you trust.

The dashboard's address matters because the panel gives the microphone and the camera, with no prompt, only to pages on your Home Assistant and on the secure hosts set in the panel's settings. Any other page gets neither, so a link that leads off your dashboard can't listen in.

## Device identity

| Field | Value |
|---|---|
| `name` / `friendly_name` | Device node name with MAC suffix (e.g. `aqara-s1-plus-a1b2c3`) and friendly display name (`Aqara S1 Plus`). See [Names](#names). |
| MAC (unique ID in HA) | Persisted in app preferences (`device_mac`). Derived on first start from the first available source: `ro.glassworks.device.mac` (stamped by Kiln on the Mirror), `wlan0` Wi-Fi MAC, SHA-256 of `ro.serialno` (locally administered), `ANDROID_ID`, or a random fallback. |
| Manufacturer / model | Frame-defined on dedicated frames (`Aqara` / `Panel Hub S1 Plus (Glassworks)`), or read from Android (`Build.MANUFACTURER` / `<Model> (Glassworks)`) on Generic Android. |
| `esphome_version` / `compilation_time` | `2026.10.2` / `Glassworks <app version>` |
| API version | `1.20` (supports `DeviceCapabilitiesRequest`) |
| Sendspin capability | `DeviceCapabilitiesResponse.sendspin` (`PAIRING_TOKEN`, `client_id`) and `SendspinPairingTokenRequest` (159) → `Response` (160) over encrypted sessions only ([Sendspin hand-off](sendspin.md#esphome-hand-off-how-ha-gives-ma-the-token)). |
| `voice_assistant_feature_flags` | `61` (`VOICE_ASSISTANT` + `API_AUDIO` + `TIMERS` + `ANNOUNCE` + `START_CONVERSATION`), or `0` while Voice is turned off. Toggling Voice asks Home Assistant to reconnect so capabilities refresh cleanly. |

### Identity across reinstalls

Home Assistant keys each device and all of its entities by the panel's MAC address. Keeping that MAC stable ensures updates and reflashes reconnect to the same device instead of creating duplicates:

| Frame | After reinstall or reflash | How identity is preserved |
|---|---|---|
| **Aqara S1 Plus** | Same device | Updates install in place and keep saved preferences. Even after a full data wipe, Android 10 allows reading `wlan0`, producing the exact same MAC. |
| **lululemon Studio Mirror** | Same device (once flashed with `ro.glassworks.device.mac`) | Because reflashing wipes `userdata`, Kiln derives a deterministic MAC from `fastboot getvar serialno` and writes it into `build.prop`. |
| **Generic Android (Android 6–7)** | Same device | Derived deterministically from `ro.serialno`. |
| **Generic Android (Android 8+)** | Same device (unless factory reset or signing key changes) | Derived from `ANDROID_ID`, which stays stable for the app's signing key until a factory reset. |

**Preserving the ESPHome encryption key across reflashes:** When reflashing a Mirror (or switching a rooted S1 Plus from a debug build to a release), wiping app data would normally erase the API key and cause Home Assistant to prompt for re-authentication. To avoid that interruption, Kiln and the CLI installers read the existing key over the adb-only `@glassworks_api_key` socket right before flashing, keep it strictly in memory (never written to disk or logs), signal the fresh app via `ro.glassworks.api_key.handover=1` (or `files/secrets-handover` on S1) to hold its ESPHome API closed for up to 15 minutes until handover completes, and inject the key back on first boot so Home Assistant reconnects automatically without prompting.

## Entities

Home Assistant displays entities as `<Friendly Name> <Entity Name>` (for example, *Aqara S1 Plus Screen*). Each entity's internal `object_id` uses ESPHome snake_case with a stable FNV-1a key. Entity IDs on newly added S1 Plus panels start with `aqara_s1_plus_*` (panels added in earlier releases keep their original `glassworks_panel_*` or `s1_panel_*` entity IDs).

### Names

Every panel automatically appends the last three bytes of its MAC address to its ESPHome node name (matching ESPHome's `name_add_mac_suffix`), so adding multiple panels or tablets never triggers a name conflict in Home Assistant:

| Frame naming mode | Node name (`device_name`, mDNS) | Friendly name (HA device page) |
|---|---|---|
| Dedicated frame (`namedBy = FRAME`, e.g. S1 Plus) | `aqara-s1-plus-a1b2c3` | `Aqara S1 Plus` |
| Generic Android (`namedBy = MODEL`) | `<model>-a1b2c3` (e.g. `pixel-tablet-a1b2c3`) | Device model (e.g. `Pixel Tablet`) |
| Recognized device on Generic Android (e.g. lululemon Mirror) | `lululemon-mirror-a1b2c3` | `lululemon Studio Mirror` |

The Music Assistant Sendspin player and Bluetooth Improv use the same friendly name. When an existing panel updates to a newer naming scheme, Home Assistant matches it automatically by MAC: your config entry and existing entity IDs remain intact, your device name updates unless you renamed it manually, and any *new* entities added later use Home Assistant's standard naming rules (derived from your device name on Home Assistant 2025.12+, or including area prefix when configured on 2026.8+).

### Categories and defaults

Following [Glassworks Principles](principles.md#entities), entities you use every day start enabled, while one-time setup and deep diagnostic entities start disabled so your Home Assistant device page stays readable. On a fresh Aqara S1 Plus, 16 entities are enabled out of the box (23 with a two-channel wall base).

To enable any disabled entity in Home Assistant: open **Settings → Devices & services → ESPHome → \<Your panel\>**, click **+N entities not shown**, select the entity, and toggle **Enabled** on.

| Entity | `object_id` | Source | Category | Enabled by default | Notes |
|---|---|---|---|---|---|
| Presence | `presence` | Core | None | Yes | Everyday automations |
| Illuminance | `illuminance` | Core | None | Yes | Everyday automations |
| Docked | `docked` | Core | None | Yes | Docking tablets only ([Docked](#docked)) |
| Screen | `screen` | Core | Config | Yes | Config category keeps it out of room "lights off" ([details](#the-screen-is-a-config-light)) |
| Speaker | `speaker` | Core | None | Yes | TTS, announcements, HA media |
| Reload dashboard | `reload_dashboard` | Core | None | Yes | Quick refresh action |
| Restart app | `restart_app` | Core | Config | Yes | Quick recovery action |
| Screen mode | `screen_mode` | Core | Config | Yes | Switch between `presence` and `always` |
| Idle timeout | `idle_timeout` | Core | Config | Yes | Used by [Panel night mode](blueprints.md) |
| Max auto brightness | `max_brightness` | Core | Config | Yes | Used by [Panel night mode](blueprints.md) |
| App update | `app_update` | Core | Config | Yes | Surfaces Glassworks releases in HA Updates |
| Frame update | `frame_update` | Core | Config | Yes | S1 Plus and Mirror only ([Frame updates](#frame-updates)) |
| Allow setup over the network | `lan_setup` | Core | Config | Yes | Reopens `/setup` pairing for 30 minutes |
| IP address | `ip_address` | Core | Diagnostic | Yes | Quick network lookup |
| Silent updates | `silent_updates` | Core | Diagnostic | Yes | Shows whether updates install hands-free |
| Auto brightness | `auto_brightness` | Core | Config | No | Usually configured once |
| Presence range | `presence_range` | Core | Config | No | Frames with adjustable range only (S1 Plus) |
| Zoom | `zoom` | Core | Config | No | Usually configured once |
| Dashboard | `dashboard` | Core | Config | No | Enable to pick a Lovelace dashboard from HA |
| Dashboard URL | `dashboard_url` | Core | Config | No | Custom dashboard URL override |
| Hide HA header | `hide_ha_header` | Core | Config | No | Usually configured once |
| Open device settings | `open_settings` | Core | Config | No | Opens the device's Android settings on screen |
| Start Wi-Fi setup (Bluetooth) | `improv_ble` | Core | Config | No | Needed only when changing Wi-Fi via BLE |
| Sign out | `sign_out` | Core | Config | No | Revokes dashboard sign-in |
| App version, Update status, Home Assistant URL, Signed in | `app_version`, `update_status`, `home_assistant_url`, `signed_in` | Core | Diagnostic | No | Troubleshooting |
| CPU usage, CPU temperature, Wi-Fi signal, Uptime | `cpu_usage`, `cpu_temperature`, `wifi_signal`, `uptime` | Diagnostics | Diagnostic | Yes | Everyday health metrics ([Diagnostics](#diagnostics)) |
| Diagnostics (pane switch) | `diagnostics` | Diagnostics | Config | No | Pane on/off |
| App CPU usage, RAM available, App RAM, Storage free, Wi-Fi link speed, App uptime, Web content processes, Last restart reason | `app_cpu_usage`, `memory_available`, `app_memory`, `storage_free`, `wifi_link_speed`, `app_uptime`, `web_content_processes`, `last_restart_reason` | Diagnostics | Diagnostic | No | Detailed troubleshooting |
| Mute microphone | `mute_microphone` | Assist | Config | Yes | Everyday privacy toggle |
| Voice status | `voice_status` | Assist | Diagnostic | Yes | Wake word readiness & error reporting |
| Voice, Wake sound, Voice overlay, Mic gain, Wake word threshold | `voice`, `wake_sound`, `voice_overlay`, `mic_gain`, `wake_word_threshold` | Assist | Config | No | One-time voice tuning |
| Music Assistant player (Sendspin) | `sendspin` | Sendspin | Config | No | Pane on/off |
| Music Assistant player status | `sendspin_status` | Sendspin | Diagnostic | No | Pairing and playback status |
| Do not disturb | `do_not_disturb` | Notifications | Config | Yes | Bedtime / quiet-hours automations |
| Notifications (pane switch) | `notifications` | Notifications | Config | No | Pane on/off (on by default) |
| `notify` action (`esphome.<panel>_notify`) | `notify` | Notifications | Action | Listed | Always listed when connected ([Notifications](notifications.md)) |
| Bluetooth proxy, Bluetooth advertisements, Bluetooth proxy status | `bluetooth_proxy`, `bluetooth_advertisements`, `bluetooth_proxy_status` | Bluetooth proxy | Config / Diagnostic | No | Pane on/off (off by default) and stats |
| Camera, Camera presence, Camera privacy | `camera`, `camera_presence`, `camera_privacy` | Camera | None | Yes | Camera-equipped devices only ([Camera entities](#camera-entities)) |
| Camera use, ONVIF stream, Camera sensitivity, Camera pane | `camera_use`, `onvif_stream`, `camera_sensitivity`, `camera_pane` | Camera | Config | No | Camera-equipped devices only |
| Radio status | `radio_status` | Radio | Diagnostic | Yes | Current radio role and client info |
| Radio mode, Zigbee stack, Radio allowed clients, Radio firmware image, Flash radio firmware, Join/Leave Zigbee, Join/Leave Thread, Thread dataset | `radio_mode`, `zigbee_stack`, `radio_allowed_clients`, `radio_firmware_image`, `radio_firmware_flash`, `zigbee_router_join`, `zigbee_router_leave`, `thread_join_ha`, `thread_leave`, `thread_dataset` | Radio | Config | No | One-time radio configuration (S1 Plus) |
| Thread join, Radio helper, Radio firmware, Radio connected client | `thread_join`, `radio_helper`, `radio_firmware`, `radio_connected` | Radio | Diagnostic | No | Radio troubleshooting (S1 Plus) |
| Keep time zone, Time zone, Time zone status | `keep_time_zone`, `time_zone`, `time_zone_status` | Time zone | Config / Diagnostic | No | S1 Plus persistent time zone |
| Base relay N, Base button N, Base power, Base energy | `base_relay_N`, `base_button_N`, `base_power`, `base_energy` | Wall base | None | Yes | S1 Plus relay base controls (present when mounted) |
| Base protection | `base_protection` | Wall base | Diagnostic | Yes | Overheat and overload alerts |
| Wall base, Block Aqara cloud, Aqara cloud daemon, Base, Base firmware | `wall_base`, `block_aqara_cloud`, `aqara_cloud`, `base`, `base_firmware` | Wall base | Config / Diagnostic | No | Base and Aqara cloud controls (S1 Plus) |

Home Assistant also creates its own built-in Assist entities automatically (**Assist satellite**, **Assist pipeline**, **Wake word**, and **Finished speaking detection**) using Home Assistant's defaults.

#### The screen is a config light

Most people do not want *"Turn off the bedroom lights"* or an area-wide Good Night script to accidentally shut off their wall panel screen. Because the **Screen** entity uses the `CONFIG` category, Home Assistant excludes it from area, floor, device, and device-label `light.turn_off` calls and hides it from Assist by default—while still letting you control it directly by its `entity_id` on dashboards and in automations.

| Action | Behavior |
|---|---|
| `light.turn_off` targeting an area, floor, or device | Skips the panel screen |
| `light.turn_off` targeting `light.<panel>_screen` directly (or a dashboard card) | Controls screen power and brightness normally |
| Assist voice commands (newly added panels) | Ignored unless you explicitly expose it |
| Assist voice commands (panels added in earlier releases) | If previously exposed, turn off **Expose** once under **Settings → Voice assistants → Expose** |

If you updated an existing panel from an older release where Screen had no category, reload the ESPHome integration entry (**Settings → Devices & services → ESPHome → \<panel\> → ⋮ → Reload**) or restart Home Assistant so the updated category applies.

#### A panel that HA already has

Home Assistant applies `disabled_by_default` only when an entity is registered for the first time. Updating an existing panel preserves your current enabled/disabled choices, entity IDs, names, and area assignments; only entity categories update (for instance, moving *Screen* to Configuration and *Base protection* to Diagnostic). Any brand-new entities added by future features or newly attached hardware use the current defaults.

### What each entity does

| Entity | `object_id` | Behavior |
|---|---|---|
| `light` **Screen** | `screen` | Turns the screen on or off (`ScreenPolicy.wake/sleep`). Setting brightness applies a fixed brightness level and turns **Auto brightness** off. |
| `switch` **Auto brightness** | `auto_brightness` | Adjusts screen brightness automatically using the ambient light sensor (listed only on frames with a light sensor). |
| `binary_sensor` **Presence** (`occupancy`) | `presence` | Reports `on` when someone is within range of the panel's presence/proximity sensor (VI5300 ToF on S1 Plus). Camera-based presence uses `camera_presence` instead. |
| `sensor` **Illuminance** (`lx`) | `illuminance` | Ambient light reading in lux (MK6212 on S1 Plus). |
| `binary_sensor` **Docked** (`plug`) | `docked` | Reports whether a docking tablet is sitting on its charging dock ([Docked](#docked)). |
| `select` **Screen mode** | `screen_mode` | `presence` (wakes on approach and sleeps after idle timeout) or `always` (stays awake and ignores approach wake so Home Assistant can turn the screen off and keep it off). Presence sensor continues reporting in both modes. |
| `number` **Idle timeout** (`s`) | `idle_timeout` | Seconds of inactivity before turning the screen off (`0` = never). Setting a value above 0 switches **Screen mode** to `presence`. Automated by [Panel night mode](blueprints.md). |
| `number` **Max auto brightness** (`%`, 5–100, step 5) | `max_brightness` | Caps the maximum brightness that **Auto brightness** can reach (default 100%). Manual brightness and high/critical notifications are not capped. |
| `number` **Presence range** (`m`, 0–2.5, step 0.1) | `presence_range` | Adjusts VI5300 detection distance on the S1 Plus (`0` = firmware default; requires `WRITE_SECURE_SETTINGS`). |
| `text` **Dashboard URL** | `dashboard_url` | Loads a specific dashboard URL (`auto` loads your default Home Assistant dashboard). Encrypted connection only ([why](#what-works-without-encryption)). |
| `select` **Dashboard** | `dashboard` | Lists `Default` plus every Lovelace dashboard fetched from Home Assistant (and `Custom` if a custom URL is saved). Selecting an option saves and reloads immediately. |
| `number` **Zoom** (`0` = auto) | `zoom` | Overrides CSS display scale ratio and restarts the app. |
| `button` **Reload dashboard** | `reload_dashboard` | Reloads the active dashboard web page. |
| `button` **Open device settings** | `open_settings` | Opens the device's own Android settings app on screen (encrypted connection only). |
| `button` **Restart app** | `restart_app` | Restarts the Glassworks kiosk process. |
| `button` **Start Wi-Fi setup (Bluetooth)** | `improv_ble` | Starts Improv Wi-Fi provisioning over Bluetooth on demand (encrypted connection only). |
| `button` **Allow setup over the network** | `lan_setup` | Opens `/setup` network pairing for 30 minutes ([Manage guide](manage.md#set-up-over-the-network)). |
| `switch` **Hide HA header** | `hide_ha_header` | Hides Home Assistant's top header bar and sidebar live. |
| `media_player` **Speaker** | `speaker` | Supports play, pause, stop, volume, mute, `play_media`, and `announce` (MP3 format). Its volume and mute are the panel's master volume: they apply to everything the panel plays, including voice replies, timers, notifications, Music Assistant, and the dashboard's own sound, and they match the **Sound** page in the panel's settings. |
| `switch` **Music Assistant player (Sendspin)** | `sendspin` | Enables or disables the native synchronized Sendspin player (encrypted connection required; [Sendspin guide](sendspin.md)). |
| `text_sensor` **Music Assistant player status** | `sendspin_status` | Reports Sendspin pairing, active stream codec, track metadata, or `off`. |
| `switch` **Mute microphone** | `mute_microphone` | Completely releases the microphone. |
| `switch` **Wake sound** | `wake_sound` | Plays a short two-tone chime when the wake word fires (default on). |
| `switch` **Voice overlay** | `voice_overlay` | Displays the full-screen Assist overlay during voice interactions; tap anywhere to cancel (default on). |
| `number` **Mic gain** | `mic_gain` | Scales microphone input gain from `0.5` to `4.0×`. |
| `number` **Wake word threshold** | `wake_word_threshold` | Detection cutoff (`0.10`–`0.99`; lower = more sensitive). Resets to each microWakeWord model's default cutoff when switching wake words (`0.97` for Okay Nabu and Hey Jarvis, `0.95` for Hey Mycroft, `0.90` for Alexa; `0.50` for legacy openWakeWord). |
| `text_sensor` **Voice status** | `voice_status` | Wake word readiness (`Ready`, `Getting ready`, or specific failure reason; see [When voice can't get ready](#when-voice-cant-get-ready)). |
| `select` **Radio mode** | `radio_mode` | `Off` (default), `Zigbee coordinator (TCP)`, `Zigbee router (repeater)`, `Thread border router`, `Thread router (repeater)` ([Radio guide](../frames/aqara-s1-plus/docs/radio-guide.md)). Requires root helper (encrypted connection only). |
| `text_sensor` **Radio status** | `radio_status` | Active radio mode, port/URL details, and allowed clients. |
| `text` **Radio allowed clients** | `radio_allowed_clients` | Space- or comma-separated IPs/CIDRs allowed alongside Home Assistant (`any` disables firewall; blank = Home Assistant only; encrypted connection only). |
| `select` **Zigbee stack** | `zigbee_stack` | `Aqara 7.3.3 (EZSP 12, ZHA)` or `Silicon Labs 8.2.2 (EZSP 18, Zigbee2MQTT, experimental)` (encrypted connection only; requires helper v4). |
| `text_sensor` **Radio helper** | `radio_helper` | Reports active root helper source and version, noting if a boot image refresh is needed. |
| `text_sensor` **Radio connected client** | `radio_connected` | Shows the client currently connected to the Zigbee coordinator port (e.g. `Home Assistant · 192.0.2.20 · since 14:32` or `Nobody`). |
| `text_sensor` **Radio firmware** | `radio_firmware` | Active radio firmware version and flashing progress/result. |
| `select` **Radio firmware image** / `button` **Flash radio firmware** | `radio_firmware_image` / `radio_firmware_flash` | Two-step armed radio firmware flash (`Keep current`, `Aqara stock image`, `Image from the radio bundle`; encrypted connection only). |
| `button` **Join / Leave Zigbee network** | `zigbee_router_join` / `zigbee_router_leave` | Joins or leaves a Zigbee network in repeater mode (encrypted connection only). |
| `button` **Join Home Assistant's Thread network** / **Leave Thread network** | `thread_join_ha` / `thread_leave` | Automatically fetches your preferred Thread dataset from Home Assistant (via QR admin prompt if needed) or forgets stored Thread credentials (encrypted connection only). |
| `text_sensor` **Thread join** / `text` **Thread dataset** | `thread_join` / `thread_dataset` | Join progress status and write-only manual Thread TLV dataset input (whitespace stripped, never logged; encrypted connection only). |
| `text_sensor` **IP address** | `ip_address` | Panel's current IP address. |
| `text` **Time zone** / `text_sensor` **Time zone status** | `time_zone` / `time_zone_status` | IANA time zone (e.g. `America/New_York`) persisted across reboots by the S1 Plus root helper (encrypted connection only). |
| `text_sensor` **App version** / `update` **App update** / `text_sensor` **Update status** | `app_version`, `app_update`, `update_status` | Installed version, Home Assistant update control, and detailed download/install progress ([App updates](#app-updates)). |
| `update` **Frame update** | `frame_update` | Tracks Glassworks frame-layer releases on S1 Plus and Mirror ([Frame updates](#frame-updates)). |
| `text_sensor` **Silent updates** | `silent_updates` | Reports whether updates install hands-free (`Yes: …` or `No: …`; [Silent updates](#silent-updates)). |
| `text_sensor` **Home Assistant URL** / **Signed in** / `button` **Sign out** | `home_assistant_url`, `signed_in`, `sign_out` | Active dashboard server URL, QR sign-in state (`yes`/`no`), and remote sign-out (encrypted connection only). |

#### Screen entities by frame

Screen and sensor entities adapt automatically to each device's hardware so Home Assistant only lists entities your panel can actually support:

| Entities | Hardware required | Aqara S1 Plus | Pixel Tablet | lululemon Mirror | Other Android |
|---|---|---|---|---|---|
| `screen`, `screen_mode`, `idle_timeout` | None | Yes | Yes | Yes | Yes |
| `presence` | Built-in presence/proximity sensor | Yes (VI5300) | No | No | If proximity sensor exists |
| `presence_range` | Adjustable-range presence sensor | Yes | No | No | No |
| `auto_brightness`, `illuminance`, `max_brightness` | Ambient light sensor | Yes (MK6212) | Yes | No | If light sensor exists |
| `camera_presence` | Camera ([Camera entities](#camera-entities)) | No | Yes | Yes | If camera exists |

Camera motion presence always uses `camera_presence` rather than `presence`, keeping the entity list stable whenever you toggle the Camera pane on or off (and letting devices with both sensors report them separately).

### Docked

On tablets that sit on a charging dock—such as the Google Pixel Tablet—the **Docked** binary sensor (`plug` device class, enabled by default) tells Home Assistant whether the tablet is currently mounted on its dock or lifted off into someone's hands. Use it to switch dashboards, dim the screen, or pause announcements whenever the tablet is undocked.

Glassworks watches Android's `ACTION_BATTERY_CHANGED` (`BATTERY_PLUGGED_DOCK` on Android 13+) and `ACTION_DOCK_EVENT` broadcasts passively with zero polling overhead, and also shows **Docked / Not docked** on the Device page of the panel and Manage page. To avoid cluttering non-docking tablets, the entity only appears when the frame declares `"dock": true`, Google's dock manager package is installed, or Android has reported a dock event at least once.

### Panes on and off

Each modular pane exposes a configuration switch so you can enable or disable it live without restarting the panel (except Radio, which uses **Radio mode** = `Off`, and Timers, which follow the **Voice** switch). Toggling a pane switch over ESPHome requires an encrypted connection, and disabled panes keep their entity registrations in Home Assistant (with empty states) so automations do not break:

| Switch entity | `object_id` | Pane | adb extra |
|---|---|---|---|
| **Voice** | `voice` | Assist voice assistant and timers | `voice_enabled` |
| **Music Assistant player (Sendspin)** | `sendspin` | Native Sendspin music player | `sendspin_enabled` |
| **Wall base** | `wall_base` | Aqara S1 Plus wall base relay driver | `wall_base_enabled` |
| **Keep time zone** | `keep_time_zone` | S1 Plus persistent time zone | `time_zone_enabled` |
| **Diagnostics** | `diagnostics` | System and app health sensors | `diagnostics_enabled` |
| **Bluetooth proxy** | `bluetooth_proxy` | ESPHome BLE proxy (off by default) | `bluetooth_proxy_enabled` |
| **Notifications** | `notifications` | Rich dashboard alerts (on by default) | `notifications_enabled` |
| **Camera pane** | `camera_pane` | Camera presence, snapshots, ONVIF (off by default) | `camera_enabled` |

Toggling **Voice** or **Bluetooth proxy** updates advertised device capabilities and briefly reconnects Home Assistant (a few seconds). While **Voice** is off, Home Assistant's built-in Assist entities disappear while the panel's **Speaker** remains available for media and TTS.

### Bluetooth proxy

Turn on **Bluetooth proxy** (off by default) from setup, the panel settings, the Manage page, or Home Assistant to let your panel act as a standard ESPHome Bluetooth proxy under **Settings → Devices & services → Bluetooth**.

- **Listen only vs. Listen and connect:** By default, the proxy passively forwards BLE advertisements (thermometers, plant sensors, presence tags). Switch to **Listen and connect** on the panel or Manage page to allow up to 3 simultaneous active GATT connections (for devices like SwitchBot or smart locks; PIN pairing is not supported). Switching modes triggers a brief reconnect.
- **Zero wasted scanning:** The panel only scans while Home Assistant is actively subscribed over an encrypted ESPHome connection, adding no measurable impact to 5 GHz Wi-Fi or Sendspin playback on the S1 Plus.
- **Permissions and status:** Android requires **Bluetooth** and **Location** enabled to deliver BLE scan results; the settings page prompts with quick shortcuts if either is off. Check **Bluetooth proxy status** (`Listening`, `Waiting for Home Assistant`, `Waiting for the panel's key`, `Bluetooth off`, `Location off`, `No permission`, `Retrying`, or `No Bluetooth LE`) and **Bluetooth advertisements** (packets forwarded in the last minute) on the settings page or by enabling them in Home Assistant.
- **Scanner identity:** On the S1 Plus, the root helper reads the panel's real Bluetooth MAC address so Home Assistant registers it like a dedicated ESPHome proxy. On non-rooted devices, Home Assistant keys the scanner on the panel's ESPHome MAC.

For Android BLE scanning details and architecture, see [`libs/bluetooth-proxy`](../libs/bluetooth-proxy/README.md).

### Diagnostics

The Diagnostics pane samples device health once a minute without root or shell commands, only reporting updates when a reading changes past its reporting band so idle panels generate almost no traffic. Four core health sensors (**CPU usage**, **CPU temperature**, **Wi-Fi signal**, and **Uptime**) start enabled by default; the rest can be enabled on the device page for troubleshooting:

| Entity | `object_id` | Default | Reporting band | Source |
|---|---|---|---|---|
| `sensor` **CPU usage** (`%`) | `cpu_usage` | On | 1% | `/proc/stat` across all cores (unavailable on Android 8+ where SELinux restricts `/proc/stat`) |
| `sensor` **App CPU usage** (`%`) | `app_cpu_usage` | Off | 0.5% | `/proc/<pid>/stat` for all Glassworks and GeckoView processes |
| `sensor` **CPU temperature** (`°C`) | `cpu_temperature` | On | 0.5 °C | Frame thermal zone (`cpu_thermal_zone` on S1 Plus; omitted on Generic Android) |
| `sensor` **RAM available** (`MiB`) | `memory_available` | Off | 8 MiB | `ActivityManager.MemoryInfo.availMem` |
| `sensor` **App RAM** (`MiB`) | `app_memory` | Off | 4 MiB | Total PSS across app processes (`smaps_rollup`, or `smaps` on kernels older than 4.14) |
| `sensor` **Storage free** (`GiB`) | `storage_free` | Off | 0.05 GiB | Free space on `/data` |
| `sensor` **Wi-Fi signal** (`dBm`) | `wifi_signal` | On | 2 dB | Active `WifiInfo` RSSI |
| `sensor` **Wi-Fi link speed** (`Mbit/s`) | `wifi_link_speed` | Off | 1 Mbit/s | Active `WifiInfo` link speed |
| `sensor` **Uptime** (`timestamp`) | `uptime` | On | 120 s | System boot timestamp |
| `sensor` **App uptime** (`timestamp`) | `app_uptime` | Off | 120 s | App process start timestamp |
| `sensor` **Web content processes** | `web_content_processes` | Off | 1 | Active GeckoView tab processes |
| `text_sensor` **Last restart reason** | `last_restart_reason` | Off | — | Android 11+ exit reason (`crash`, `low memory`, `app updated`; `unknown` on Android 10) |

### Wall base entities

On the Aqara S1 Plus, Glassworks detects the attached mains relay wall base using our direct local UART driver (default) or Aqara's stock LocalService ([bases.md](../frames/aqara-s1-plus/research/bases.md#implementation-s1-kiosk)). Only **Base** is always listed; relay, power, and button entities appear automatically whenever a matching switch base is mounted:

| Entity | `object_id` | When present | Details |
|---|---|---|---|
| `text_sensor` **Base** | `base` | Always | `none`, `switch (2ch)`, `ethernet`, `unknown (0xNN)`, or `unavailable` |
| `text_sensor` **Base firmware** | `base_firmware` | Any base | Raw firmware version (e.g. `8.0.2022`) |
| `switch` **Base relay N** | `base_relay_1`..`3` | Switch base | Controls each mains relay channel (`4.N.85`) |
| `sensor` **Base power** (`W`) | `base_power` | Switch base | Instantaneous load power (`0.12.85`) |
| `sensor` **Base energy** (`total_increasing`) | `base_energy` | Switch base | Cumulative energy (Wh, integrated from power under the UART driver) |
| `text_sensor` **Base protection** | `base_protection` | Switch base | `normal`, `over_temperature`, `over_load`, and related fault/clear states |
| `event` **Base button N** | `base_button_1`..`3` | Switch base | Click events (`single`, `double`, `triple`, `long`, `release`, `unknown`; LocalService backend only) |

Channel names stay fixed (`Base relay 1`, etc.) rather than pulling Aqara app labels so Home Assistant unique IDs never break—rename them inside Home Assistant if desired. When a wall base is attached or removed for 5 seconds, the panel updates its entity list and prompts Home Assistant to reconnect cleanly.

### Camera entities

Available only on camera-equipped devices (hidden automatically on the Aqara S1 Plus). Starts turned off until you enable it. Motion detection uses lighting-insensitive processing locally on the device, and no image ever leaves the panel while set to **Presence only**:

| Entity | `object_id` | Category / default | Behavior |
|---|---|---|---|
| `camera` **Camera** | `camera` | None / On | Serves on-demand snapshots to Home Assistant in **Presence + camera** mode over encrypted connections only |
| `binary_sensor` **Camera presence** (`occupancy`) | `camera_presence` | None / On | Triggers on movement in view and clears 8 seconds after motion stops |
| `switch` **Camera privacy** | `camera_privacy` | None / On | Closes the camera completely (stops presence, snapshots, and streaming) |
| `select` **Camera use** | `camera_use` | Config / Off | `Presence only` or `Presence + camera` (enables snapshots and optional ONVIF stream) |
| `switch` **ONVIF stream** | `onvif_stream` | Config / Off | Exposes H.264 RTSP (port `8554`) and ONVIF (port `8000`) for Home Assistant ONVIF, Frigate, or UniFi Protect once credentials are set (encrypted HA connection required) |
| `number` **Camera sensitivity** | `camera_sensitivity` | Config / Off | Motion sensitivity from `1` (least) to `10` (most), default `5` |
| `switch` **Camera pane** | `camera_pane` | Config / Off | Pane on/off switch |

**Privacy and stream security:** Whenever images leave the panel, Android 12+ shows its system camera indicator and older Android versions display a small green dot in the top-right corner. The ONVIF/RTSP stream refuses to start without a username and password configured on the Camera settings page (default username `glassworks`, write-only password with no default). Because local RTSP and ONVIF run over unencrypted TCP, only enable streaming on a trusted home network ([technical notes](research/onvif-rtsp.md#result)).

The Camera settings page also offers two ready-made [blueprints](blueprints.md): **Lights on at the panel** and **Snapshot when away**. For adb automation, camera settings are isolated behind an adb-only activity (`<package>/dev.glassworks.camera.CameraAdbActivity`, accepting `--es camera_use snapshots`, `--ez onvif_stream true`, and `--es camera_stream_sign_in 'user:password'`).

### App updates

Managed by `update/UpdateManager.kt` (see [Updates and restoring stock software](updates.md) for the user walkthrough and [releasing.md](releasing.md) for release mechanics):

- **Release checks:** Queries `GET https://api.github.com/repos/GlassworksHQ/glassworks/releases?per_page=100` (with ETag caching) 30 seconds after boot, 10 seconds after Wi-Fi reconnects following an offline check, every 24 hours, and on demand via `homeassistant.update_entity` (rate-limited to once per minute). Filters strictly to each app's release line and compatible ABI.
- **Update channels:** Configured on the panel's Device page or Manage page (**Stable** default, or **Beta** to include GitHub pre-releases; `-rc` tags and drafts are always skipped). Returning to Stable waits for a newer stable version rather than downgrading.
- **Verification before install:** Downloads the `.sha256` and APK over HTTPS from `github.com`, verifies the digest against both the `.sha256` file and GitHub's asset digest, and checks that package ID, version, and signing certificate match the running app.
- **Install path:** On the Aqara S1 Plus with the root update service, writes `files/update/pending.apk` for silent installation and automatic restart. Elsewhere, uses Android's `PackageInstaller` (silent on Android 12+ with *Install unknown apps*, as device owner, or as a `/system/priv-app` on the Mirror; prompts on screen otherwise).

### Frame updates

Tracked by `update/FrameUpdate.kt` on dedicated frames that ship Glassworks system layers (**Aqara S1 Plus** and **lululemon Studio Mirror**; omitted on Generic Android tablets where the manufacturer updates the OS).

Reads `ro.glassworks.frame.id` and `ro.glassworks.frame.version` (showing *Before 1.0.0* on panels installed prior to frame-version stamping) and compares them against `frames/<id>/v<version>` GitHub releases.

**Installing from Home Assistant (Aqara S1 Plus with the frame loader).** Press **Install** on the **Frame update** card, call `update.install` from an automation, or press **Install** on the **Frame update** row of the Device page (on the panel, or on the Manage page with the code the panel shows). Glassworks then:

1. Downloads the release's `frame-bundle-<id>-<version>.zip` and its `.sha256` over HTTPS from `github.com`, and checks the zip against both the `.sha256` file and GitHub's asset digest, like an app update. The card shows progress while it downloads.
2. Unpacks only the files the bundle should hold (its `manifest`, `manifest.sig` and the files the manifest lists, at their listed sizes and SHA-256) into the app's staging folder. It refuses links, folders, unlisted files, paths outside the loader's rules, and a bundle for another frame or version.
3. Hands the bundle to the frame loader's install, which checks the signature itself, and reads the result from the loader's own record (`/data/glassworks-pub/frame-bundle`).
4. Restarts the panel as soon as the bundle is staged. The loader loads it at boot, and the card and the Device page show the new version.

A panel that hasn't loaded a frame bundle yet runs its boot image's own copies, so the card shows the installed version as, for example, `1.0.0 (boot image)` and offers that same version's bundle.

If anything stops it, the card says why: a SHA-256 mismatch, the loader's refusal (with its reason), no network, or a busy panel. Nothing restarts then. An app update and a frame update never run together: whichever comes second is refused with a message, so press Install again once the first is done.

**Everywhere else** (the lululemon Studio Mirror, or an S1 Plus whose boot image has no frame loader yet), the card and the Device / Manage page say how to install the update instead.

### Silent updates

The **Silent updates** diagnostic sensor (`silent_updates`, enabled by default and refreshed every 15 minutes) reports whether Home Assistant app updates install hands-free—and tells you exactly how to enable hands-free installs if they do not:

| Reported state | Meaning | How to enable hands-free updates |
|---|---|---|
| `Yes: the boot image's root update service installs them` | Aqara S1 Plus with root update service | Already enabled |
| `Yes: the app is a system app that may install apps` | lululemon Mirror (`/system/priv-app`) | Already enabled |
| `Yes: the app is the device owner` | Any Android device in device-owner mode | Already enabled |
| `Yes: Android 12 or newer lets the app update itself` | Android 12+ with *Install unknown apps* allowed and matching `targetSdk` | Already enabled |
| `No: this boot image has no root update service` | Older S1 Plus boot image | Refresh boot image with Kiln |
| `No: Android <n> asks for a tap on each update` | Android 6–11 without device owner or system privileges | Set as [device owner](../frames/generic-android/README.md#device-owner) on an account-free device |
| `No: Android needs “Install unknown apps” allowed for the app` | Android 12+ without permission | Enable *Install unknown apps* for Glassworks in Android Settings |
| `No: Android <n> needs an app that targets API <t>, and this one targets <s>` | OS newer than current `targetSdk` threshold | Wait for an updated release targeting a newer API level, or use device owner |

## Voice assistant

For everyday setup, wake word choices, and troubleshooting, start with **[Voice and timers](voice-and-timers.md)**.

Under the hood, Glassworks implements the ESPHome voice assistant protocol identically to Home Assistant Voice PE: audio streams over the ESPHome API connection (16 kHz 16-bit mono PCM in 80 ms / 2560-byte frames, with up to 2 seconds of pre-roll audio), and TTS/announcements stream over HTTP using **Media3 ExoPlayer** (`ExoAudioPlayer`, chosen over Android's `MediaPlayer` to avoid an Allwinner CedarX file-descriptor crash in `mediaserver`).

| Direction | ESPHome message | Panel behavior |
|---|---|---|
| HA → panel | `SubscribeVoiceAssistantRequest` | Registers this connection for voice traffic |
| HA → panel | `VoiceAssistantConfigurationRequest` | Lists the four microWakeWord models plus any legacy openWakeWord models on disk (max 1 active) |
| HA → panel | `VoiceAssistantSetConfiguration` | Switches the active wake word live and persists `wake_word` |
| panel → HA | `VoiceAssistantRequest` (`USE_VAD`) | Fired on local wake word detection (HA starts directly at STT) |
| HA → panel | `VoiceAssistantResponse{port=0}` | Begins streaming microphone audio over the API |
| HA → panel | `RUN_START`, `STT_VAD_END`, `INTENT_PROGRESS`, `INTENT_END`, `TTS_END`, `RUN_END`, `ERROR` | Drives overlay states, streaming TTS playback, and 5-minute continued conversations |
| panel → HA | `VoiceAssistantAnnounceFinished` | Signals playback completion (re-triggers listening automatically when `continue_conversation` is set) |
| HA → panel | `VoiceAssistantAnnounceRequest` | Plays optional pre-announce chime and announcement media, optionally starting a conversation |
| HA → panel | `VoiceAssistantTimerEventResponse` | Updates on-screen timer orbs; wakes screen and rings 3 chimes when a timer finishes |

Safety watchdogs cap audio streaming at 15 seconds, start replies at 10 seconds, and response waits at 60 seconds, with a 3-second cooldown between detections.

### When voice can't get ready

Wake word readiness is reported in three places: the **Assist** settings page (on the panel and Manage page, with a **Try again** button), the `voice_status` sensor in Home Assistant, and the **Voice** log (`Glassworks.Voice`). Failed downloads or engine starts retry automatically (30 s, 1 m, 2 m, 5 m, then every 10 minutes), while microphone failures retry every 5 seconds.

| `voice_status` | Meaning and remedy |
|---|---|
| `Ready` | Wake word engine is actively listening. |
| `Getting ready` | Downloading or loading the selected model (takes a few seconds on first run). |
| `Download failed` | Could not reach `github.com` / `raw.githubusercontent.com`, TLS verification failed (check panel date/time), or GitHub returned an HTTP error. |
| `Model check failed` | Downloaded file failed SHA-256 verification (often a captive portal or intercepting proxy) and was rejected. |
| `Model not saved` | Storage is full or not writable; free up space. |
| `Model missing` | A custom legacy openWakeWord model is selected that cannot be fetched automatically; choose an official wake word. |
| `Engine failed` | LiteRT (TensorFlow Lite) failed to initialize; check logs and report an issue. |
| `Microphone not allowed` | Android's Microphone permission is denied; grant it in Android app settings. |
| `Microphone unavailable` | No microphone hardware, another app is holding the mic, or repeated audio reads failed; recovers automatically once available. |

### microWakeWord parameters

The engine runs ESPHome microWakeWord v2 streaming models (`MicroWakeWord.kt`):

| Parameter | Specification |
|---|---|
| Audio frontend | `MicroFrontend.kt` (Kotlin port of TFLite Micro frontend + kissfft): 30 ms Hann window every 10 ms, 512-point fixed-point FFT, 40 mel channels (125–7500 Hz), noise reduction, PCAN, log scale |
| Model input / output | `int8 [1, 3, 40]` (one inference every 30 ms); streaming `uint8 [1, 1]` scaled by `1/256` |
| Scoring & trigger | Mean of last 5 outputs ≥ `wake_word_threshold` (default: manifest `probability_cutoff`), followed by a 3-second cooldown |
| Official models | `okay_nabu`, `hey_jarvis`, `alexa`, `hey_mycroft` from `esphome/micro-wake-word-models` `models/v2`, pinned by SHA-256 in `WakeWords.kt` |

Technical benchmarks and implementation notes live in [research/microwakeword.md](research/microwakeword.md).

### Timers on screen

Home Assistant owns all timer state, and the panel renders active timers natively (`TimerOverlay`, `TimerBoard`, `panes/timers`):

- **Running & paused orbs:** Glass orbs in the bottom-right corner ordered soonest-first, showing remaining time, an amber countdown arc, and the timer name (gray ring with pause icon when paused; enlarged glowing countdown with 1-second ripples during the final 10 seconds).
- **Drag to reposition:** Drag orbs to any of the eight edge/corner anchor positions; saved in preferences.
- **Hide & restore:** Tap a timer → **Hide timers** to tuck them into an edge half-orb; tap to restore. Automatically reappears for the final 10 seconds or when a new timer starts.
- **Completion screen:** Wakes the display, shows a full-screen amber completion overlay (grouping simultaneous finishes), and chimes three times (~3 seconds apart). Tap anywhere to dismiss, or it closes automatically after 60 seconds.

### Announce on every panel

Because every panel with Voice enabled exposes an `assist_satellite` entity, you can broadcast spoken announcements across your house using Home Assistant's built-in `assist_satellite.announce` action—no custom integration needed:

1. Create a **Text** helper named **Announcement** (`input_text.announcement`) under **Settings → Devices & services → Helpers**.
2. Create a script (**Edit in YAML**) listing your panels' `assist_satellite` entities:

   ```yaml
   alias: Announce on every panel
   mode: queued
   fields:
     message:
       name: Message
       description: What the panels say (defaults to the Announcement helper).
       selector:
         text:
   sequence:
     - action: assist_satellite.announce
       target:
         entity_id:
           - assist_satellite.kitchen_panel_assist_satellite
           - assist_satellite.hall_panel_assist_satellite
       data:
         message: "{{ message | default(states('input_text.announcement'), true) }}"
   ```

3. Add the helper and a button card to your dashboard (or call `script.announce_on_every_panel` with `data: {message: "Dinner's ready"}` from any automation):

   ```yaml
   type: button
   name: Announce
   icon: mdi:bullhorn
   tap_action:
     action: perform-action
     perform_action: script.announce_on_every_panel
   ```

Each panel plays Home Assistant's pre-announce chime (pass `preannounce: false` to skip), wakes the screen, shows the text on the voice overlay, speaks it using that panel's Assist pipeline voice, and resumes any paused music afterward. Using `mode: queued` prevents overlapping announcements from hitting a busy satellite.

To include a panel whose **Voice** pane is turned off, add a `tts.speak` step targeting its `media_player.<panel>_speaker` entity (plays as an announcement with music ducking, without a chime or overlay):

```yaml
- action: tts.speak
  target:
    entity_id: tts.piper  # replace with your TTS engine
  data:
    media_player_entity_id: media_player.office_panel_speaker
    message: "{{ message | default(states('input_text.announcement'), true) }}"
```

### openWakeWord parameters (legacy)

Retained temporarily as a fallback for existing custom or pre-downloaded openWakeWord models (`AudioFeatures` / `Model.predict` port with 1280-sample / 80 ms frames, 1760-sample mel windows, 76-frame embeddings, 16-row wake model input, 5-frame warm-up, default threshold `0.5`, and in-memory FlatBuffer input shape patching in `TfliteShapePatch` for TFLite Java).

## Wire protocol notes

- **Keepalive:** Mirrors ESPHome's `api_connection.cpp`—sends `PingRequest` after 60 seconds without client traffic and disconnects only after 150 seconds of silence so streaming-only connections stay alive reliably.
- **Framing & Noise:** Unprovisioned sockets inspect byte 0 (`0x00` plaintext varint framing, `0x01` Noise with zero-PSK hand-off). Provisioned Noise uses `0x01` + 16-bit length, `ClientHello` → `ServerHello` → `NNpsk0` (`NoiseAPIInit\0\0` prologue) via BouncyCastle lightweight API.
- **Protobuf & entities:** Hand-written encoders (`Proto.kt`, `Entities.kt`, `ApiServer.kt`) aligned with `aioesphomeapi` `api.proto`, including event entities (`107`/`108`), update entities (`116`/`117`/`118`), and dynamic entity rebuilds via `DisconnectRequest`.
- **Home Assistant time zone lookup:** After state subscription on encrypted sessions, sends `GetTimeRequest` (36) and matches Home Assistant's POSIX `TZ` string in `GetTimeResponse` (37) against IANA time zones (`PosixTimeZone`) to offer a one-click *Use it* hint on the Time zone settings page.
- **Syncing panel-selected wake words back to HA:** Because Home Assistant only re-reads `VoiceAssistantConfigurationRequest` on reconnect and clears stale selections on the first read, picking a wake word on the panel requests two brief reconnects (~2 seconds apart) so Home Assistant's wake word selector immediately reflects the new choice.

## Tests (host)

Run the full JVM protocol and voice verification suite against real `aioesphomeapi` and reference fixtures:

```sh
python3 -m venv /tmp/esphome-venv && /tmp/esphome-venv/bin/pip install aioesphomeapi==46.25.0
MODELS_CACHE=/tmp/oww-models tools/voice/fetch-models.sh --no-push
ESPHOME_PYTHON=/tmp/esphome-venv/bin/python OWW_MODELS_DIR=/tmp/oww-models ./gradlew test
```

The test suite exercises `EspHomeApiTest` (end-to-end against real `aioesphomeapi`), `EspHomeKeepaliveTest`, `NoiseKeysTest`, `OAuthTest` / `SignInServerTest`, `BaseProtocolTest` / `UartBaseLinkEmulatorTest`, `update/*Test`, `VoiceAssistantControllerTest`, `MicroFrontendTest`, `MicroDetectorTest`, `MicroWakeWordClipTest` (LiteRT over real audio clips in CI), `WakeWordCatalogTest`, `AssistPaneTest`, and `OwwFeaturesTest`.

## Licensing

- Glassworks' ESPHome API and voice libraries are **MIT** licensed; `MicroFrontend.kt` retains its upstream **Apache-2.0** (TFLite Micro) and **BSD-3-Clause** (kissfft) licenses.
- Official **microWakeWord models** and **LiteRT** (TensorFlow Lite) are **Apache-2.0** (models are downloaded on demand, not bundled).
- Legacy **openWakeWord pre-trained models** are **CC BY-NC-SA 4.0** (non-commercial, share-alike) and are never bundled in the repository or APKs.
- **BouncyCastle** is licensed under the MIT-style Bouncy Castle License.

## Limitations / known gaps

- **mDNS hostname target:** mDNS advertises Android's hostname rather than `<node name>.local`; Home Assistant connects via the advertised IP and adopts `DeviceInfoResponse.name` automatically.
- **URL-based TTS:** Speech responses stream from Home Assistant URLs via ExoPlayer rather than raw PCM API audio (`SPEAKER` flag is not advertised).
- **One active wake word:** `max_active_wake_words = 1`; custom wake word downloads pushed from Home Assistant are not supported (use the four built-in microWakeWord models).
- **No acoustic echo cancellation / barge-in:** Wake word detections are paused while listening or speaking; tap the overlay to interrupt.
- **Shared microphone with GeckoView:** Running in-browser Assist inside the dashboard competes with the on-device wake word engine; toggle **Mute microphone** if using in-browser Assist.
- **No Chromecast:** Google Cast receivers require Google device certification; use native [Sendspin](sendspin.md) for synced audio instead.
- **Privileged controls require encryption:** Every radio control, the `notify` action, the time zone, pane switches, the camera's snapshots, ONVIF stream and **Camera privacy**, **Dashboard URL**, **Sign out**, **Start Wi-Fi setup (Bluetooth)** and **Open device settings** refuse changes over unencrypted plaintext connections ([What works without encryption](#what-works-without-encryption)).

### Aqara cloud

Displayed on the **S1 Plus** Device page (on both the panel and the Manage page), keeping their original wall-base entity IDs:

- `switch` **Block Aqara cloud** (`block_aqara_cloud`, default on): Keeps Aqara's `ha_master` cloud daemon stopped. Under the default UART wall-base driver it stays stopped unconditionally; under the legacy LocalService backend it runs only when a switch base requires it.
- `text_sensor` **Aqara cloud daemon** (`aqara_cloud`): Reports `blocked`, `running`, `stopped (UART wall base backend owns /dev/ttyS3)`, `running (needed by the wall base, LocalService backend)`, or an error string.
