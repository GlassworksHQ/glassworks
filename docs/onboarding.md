# Onboarding plan: from "fresh panel" to "showing my dashboard" with minimal config

> **Setting up a panel?** Follow [install.md](../frames/aqara-s1-plus/docs/install.md) (steps 4 and 5). This page is the design and
> research behind those steps: how each piece works and what was verified.

Goal: a user who has just installed Glassworks never types a URL, token, IP or key on the panel.
Everything rides on standard Home Assistant mechanisms (ESPHome discovery, HA OAuth, Improv),
not on a custom integration.

Status: build-order steps 1–6 are ✅ implemented (dynamic key, HA discovery, QR sign-in + external
auth, setup screen, dashboard select, Improv), and released since 0.2.0. Improv over Bluetooth is only
lightly tested end to end. Nothing below is implemented unless marked ✅.

## The flow the user sees

1. **Wi-Fi.** Usually already done: a panel that was set up with the Aqara app keeps its Wi-Fi
   after our install. The web installer sends it from the computer over USB (**Improv Serial**
   through adb, see [From the web installer](#from-the-web-installer-improv-serial--setup-channel-over-adb-)).
   Without it, a fresh or reset panel gets Wi-Fi through **Improv via BLE**, with an on-screen
   picker as fallback.
2. **Sign in with your phone.** The panel shows a QR code. Scan it, sign in to HA on the phone (normal
   HA login, password manager works), done. The panel never shows a login form.
3. **HA finds it.** "New device discovered: Aqara S1 Plus" appears in HA → click **Add**. HA generates
   and pushes the encryption key itself. If HA added it before the sign-in, the panel shows Add it as done.
4. **Choose the panes**: voice, music and the Zigbee / Thread radio. A pane that is on then asks what
   it needs (the radio: its mode, then what to do in HA).
   **Silent updates** follows, only when updates ask for a tap and there's a way to change that: an S1
   whose boot image has no update service (Kiln adds it), or a tablet where Android needs "Install unknown
   apps" allowed or a device owner (#422). Never required: Continue or Not now answers it. A panel that was
   set up before this step existed never sees it.
5. **Pick a dashboard** right after signing in during setup (Kiln or `/setup`), from **Home Assistant & dashboard** in panel settings or on Manage, or from the **Dashboard** dropdown on the panel's device page in Home Assistant (defaults to your default dashboard).
6. **Optional: night mode.** The panel has no night schedule of its own. To turn the screen off sooner
   and dim it at night, add the [Panel night mode](blueprints.md#panel-night-mode) blueprint from
   **Settings → Screen** and pick your panels. Setup doesn't ask for it.

A first-boot **setup screen** (same visual style as the dashboard) walks through these and moves on
by itself as each step completes ✅ (see [Setup screen](#setup-screen-)).

## Pieces and how they work

### 1. Wi-Fi: Improv via BLE (+ fallback) ✅ (lightly tested end to end over Bluetooth)

- [Improv](https://www.improv-wifi.com/) is the open Wi-Fi provisioning standard ESPHome uses.
  HA's `improv_ble` integration discovers Improv devices over Bluetooth (or through an ESPHome
  Bluetooth proxy), shows a notification, and lets you enter Wi-Fi credentials in the HA UI.
  Phones can use improv-wifi.com (Web Bluetooth) too.
- Fallback: the setup screen offers Android's Wi-Fi panel (`Settings.Panel.ACTION_WIFI`).

#### What's implemented (`frames/aqara-s1-plus/android/…/improv/`; the protocol, session, BLE server, `WifiProvisioner` and `WifiSecurity` are the reusable [Improv library](../libs/improv/README.md))

| File | Role |
|---|---|
| `ImprovProtocol.kt` | UUIDs, enums, RPC encode/parse + checksum, fragment reassembly, scan packing, service data (pure, unit-tested) |
| `ImprovSession.kt` | State machine: authorization + timeout, RPC dispatch, Wi-Fi result handling (pure, unit-tested) |
| `ImprovBleServer.kt` | `BluetoothGattServer` + `AdvertisingSet` (legacy PDU), notification queue, long writes, MTU |
| `WifiProvisioner.kt` / `WifiSecurity.kt` (library) | Join the network (legacy API or suggestion), verify, restore the previous network on failure |
| `ImprovOverlay.kt` | "Allow Wi-Fi setup over Bluetooth?" card (dashboard style), status/countdown/result |
| `ImprovController.kt` | Lifecycle: auto/on-demand start, 10 min window, identify (wake + flash + 3 beeps), device-owner escape hatch |
| `ImprovActivity.kt` | Invisible trampoline for the adb intent extras |

Hooks outside the package: `KioskService` (init/shutdown/intent), two new `KioskPrefs` members,
manifest (permissions + `ImprovActivity`), and one ESPHome button in `PanelDevice`.

**When it runs** (never permanently):
- automatically if the panel has **no network 30 s after `KioskService` starts** (boot), unless
  `improv_auto` is off;
- on demand: `adb shell am start -n sh.edm.s1kiosk/.improv.ImprovActivity --ez improv true`
  (`--ez improv false` stops), or the HA button **"Start Wi-Fi setup (Bluetooth)"**
  (`button.s1_panel_start_wi_fi_setup_bluetooth` on a panel added before #63, `glassworks_panel_…` on
  one added before #260, `aqara_s1_plus_…` on a new S1, [Names](esphome.md#names); config category);
- for **10 minutes** (extended while connecting / after a failure), then advertising and the GATT
  server stop. After PROVISIONED it keeps advertising that state for 10 s, then stops (like ESPHome).
- In auto mode, if a network appears by other means it reports PROVISIONED and stops.

**Authorization** (physical action): the overlay's **Allow** button. Allowed for **2 minutes**
(spec suggests ~1 min; 2 gives time to type a password on a phone), restarted after a failed connect.
**Not now** stops Improv. Optional alternative: a proximity far→near approach
(`--ez improv_proximity_auth true`, off by default on a touchscreen because on a desk the VI5300 reads
"near" permanently and only fires on threshold crossings).

**Without a touchscreen** (the frame's `Input.NONE`, the lululemon Mirror; #611) nobody can press Allow.
There Improv starts **authorized for 5 minutes** when it starts with no network, and the setup screen says
"Send the Wi-Fi from a phone" with the countdown. After the 5 minutes it asks for authorization again,
and the setup hotspot opens if it can start (it waits for the window: 30 s + 5 min instead of 3 min). When it
can't (Wi-Fi switched off, or a failed start), the Wi-Fi step says why and how to set the panel up from a
computer. An approach opens the window again: approach authorization is on by default there, and on a
device without a proximity sensor the camera pane's presence is the approach. Both only while the panel has **no network**: an online panel
is never sent new Wi-Fi without a person. Once Wi-Fi joins, Improv ends as above. 5 minutes is the
exposure of an ESPHome device, which asks for no authorization at all.

**Capabilities** = `0x07`: identify, device info, scan Wi-Fi. Hostname (0x05) and device name
(0x06) are answered with error 0x02 *unknown RPC*: Android 10 doesn't let an app set the DHCP
hostname.

#### Verified protocol facts (sources checked 2026-11-09)

- Service `00467768-6228-2272-4663-277478268000`; characteristics `…8001` state (R/N), `…8002`
  error (R/N), `…8003` RPC command (W), `…8004` RPC result (R/N), `…8005` capabilities (R).
  [spec](https://www.improv-wifi.com/ble/), `improv-wifi/sdk-cpp` `src/improv.h`.
- States 1 authorization required, 2 authorized, 3 provisioning, 4 provisioned (0 "stopped" is
  internal to sdk-cpp/ESPHome and must never be advertised). Errors 0 none, 1 invalid RPC,
  2 unknown RPC, 3 unable to connect, 4 not authorized, 5 bad hostname, 0xFF unknown.
- RPC packet `[cmd, len, data…, checksum]`, checksum = sum of the previous bytes & 0xFF; data is
  length-prefixed UTF-8 strings. Wi-Fi settings = `[ssid, password]`; its result's first string is
  the redirect URL (omit for none). Identify has no result. Device info returns firmware name,
  version, chip, device name (spec allows OS name/version after them, but **py-improv-ble-client
  2.0.1 `DeviceInfoRes` rejects anything but exactly 4 strings**, so we send 4). Scan Wi-Fi is ONE
  result with (SSID, RSSI, auth) triples (spec rev 2.2), auth one of WEP/WPA/WPA2/WPA2 EAP/WPA3/WAPI/NO.
  Results are capped at 255 data bytes (sdk-cpp `RPC_RESPONSE_MAX_SIZE` 258) and at the
  notification MTU.
- Writes longer than MTU-3 arrive in pieces; we accumulate until `size-3 == len` (ESPHome
  `improv_ble_component.cpp process_incoming_data_`), drop stale fragments after 2 s, and also
  support prepared (long) writes.
- Advertisement: service UUID **and** service data UUID `0x4677` = `[state, caps, 0, 0, 0, 0]` in the
  same PDU, not in the scan response (spec rev 2.0+). Flags 3 + UUID128 18 + service data 10 =
  exactly 31 bytes, so the name goes into the scan response. HA's bluetooth matcher needs both
  (`improv_ble/manifest.json` at 2026.11.0: `service_uuid` + `service_data_uuid`
  `00004677-0000-1000-8000-00805f9b34fb`); `improv_ble_client.device_filter` and
  `ImprovServiceData.from_bytes` require exactly 6 bytes and a state of 1..4, and HA's config flow
  aborts on PROVISIONING/PROVISIONED (`config_flow.py _abort_if_provisioned`).
- HA's flow (`config_flow.py`): connect → read capabilities → menu (identify / provision) → ask SSID
  + password → if state is 1, show "authorize" and wait for a state notification ≠ 1 → write the
  Wi-Fi settings RPC (write with response) → wait for error ≠ 0 or the RPC result → shows the
  redirect URL, or chains to another integration's discovery flow (e.g. ESPHome) if one registers
  for the BLE MAC within `PROVISIONING_TIMEOUT`.

#### Verified on the panel (2026-11-09)

- Realtek RTL8821CS (BT 4.2): `isMultipleAdvertisementSupported()` = **false**,
  `bluetoothLeAdvertiser` = **present**, extended advertising false. Despite the first value,
  `startAdvertisingSet` (legacy PDU, connectable) **works**, and `setAdvertisingData` updates the
  state in place (seen from the Mac: `010700000000` → `020700000000` after tapping Allow).
- From the Mac (bleak 3.1 + py-improv-ble-client 2.0.1): found as "MagicPad S1 Plus" (the BT
  adapter name, unchanged) with RSSI around -50, `device_filter()` = True (HA would discover it),
  MTU 517, capabilities 7, `need_authorization` True, identify OK (screen wakes, card pulses, 3 beeps),
  device info `Glassworks, 0.1.0, Aqara Panel Hub S1 Plus/sun50iw10p1, Glassworks Panel` (the name is the
  friendly name since #266, "Aqara S1 Plus" on the S1), hostname RPC →
  error 0x02. Scan returned 0 networks because the app doesn't hold the location permission yet
  (see below). After `--ez improv false` nothing advertises the Improv service.
- **At the time not tested on the device**: sending Wi-Fi credentials / joining a network (unit
  tests + review only). The join itself (`WifiProvisioner.connect`) has since been used on panels
  through the web installer's Wi-Fi step, which shares it.

#### Joining the network on Android 10 (API 29) with targetSdk 35

From AOSP `android10-release` `frameworks/opt/net/wifi` `WifiServiceImpl.java`:
`addOrUpdateNetwork`, `enableNetwork`, `removeNetwork`, `reconnect` and `getConfiguredNetworks`
check `isTargetSdkLessThanQOrPrivileged()`, which is true for target < Q, privileged/system apps,
**device/profile owners**, and **apps holding the SYSTEM_ALERT_WINDOW app-op**
(`WifiPermissionsUtil.checkSystemAlertWindowPermission`). The panel already grants our app that
app-op (`frames/aqara-s1-plus/tools/install.sh`), so the "legacy" path works **without device owner** on this Android
version. (That SAW exemption is gone from Android 11; there only device owner works.)
`setWifiEnabled` is not covered: if Wi-Fi is switched off we report "unable to connect". With the switch on but the
client down (a few seconds after the panel's own setup hotspot stops, on a device without STA+AP) the join waits up to
15 s for the client instead, and says "The Wi-Fi radio didn't come back. Try again" if it never does (#197).

Order of attempts in `WifiProvisioner.connect()`:
1. Already connected to the requested SSID → success, config untouched.
2. Legacy (`device owner` or `SAW` on API 29): `addNetwork` (PSK / SAE / OWE / WEP / open, chosen
   from the scan result; transition WPA2/WPA3 → PSK) → `enableNetwork(id, true)` → `reconnect()`.
   Wait up to 30 s (ESPHome's timeout) for supplicant COMPLETED on that network with an IPv4;
   2 authentication errors = wrong password. Before that, up to 15 s for Wi-Fi to come on and 6 s for a first scan:
   about 51 s in the worst case, which the setup screen rounds to "up to a minute".
3. Otherwise `WifiNetworkSuggestion`: Android 10 shows a notification the user must approve, and
   never switches away from a working saved network, so this only helps a panel with no Wi-Fi.
   The overlay tells the user to approve it; waits up to 2 min.

On failure: our new config is removed (or the suggestion withdrawn), the previous network is
re-selected with `enableNetwork(prevId, true)` + `reconnect()` (autojoin otherwise), and the client
gets error 0x03 *unable to connect*; state goes back to authorized. On success the RPC result is
`http://<new-ip>:8099/` (the panel's setup page), then state 4.

**Location:** reading the current SSID/networkId (`getConnectionInfo` redacts them) and scan results
needs `ACCESS_FINE_LOCATION` **and** location mode on (`WifiPermissionsUtil.enforceCanAccessScanResults`).
Location mode is on (`location_mode=3`) on the panel; the permission is not granted. Without it the
app refuses to change networks while already on Wi-Fi (it couldn't tell the new network from the old
one) and the scan RPC returns an empty list. Grant it once with
`adb shell pm grant sh.edm.s1kiosk android.permission.ACCESS_FINE_LOCATION`
(`adb install -g` also grants it), or make the app device owner, which grants it to itself
(`setPermissionGrantState`).

#### Device owner (optional)

Needed on Android 11+, or if SYSTEM_ALERT_WINDOW is ever revoked. `device_admin.xml` needs no extra
policies (device-owner Wi-Fi rights aren't policy-gated); the existing `AdminReceiver` is used.

```sh
# needs: no accounts on the device (verified: 0) and a single user (verified)
adb shell dpm set-device-owner sh.edm.s1kiosk/.AdminReceiver
adb shell dumpsys device_policy | grep -A3 "Device Owner"
# rollback (escape hatch; `dpm remove-active-admin` doesn't work for non-testOnly apps):
adb shell am start -n sh.edm.s1kiosk/.improv.ImprovActivity --ez release_device_owner true
# clearDeviceOwnerApp also removes the active admin: re-enable lockNow screen-off with
adb shell dpm set-active-admin sh.edm.s1kiosk/.AdminReceiver
```

While device owner, the app can't be uninstalled (release first), and Settings shows "managed by your
organization". `adb install -r` updates keep working.

#### Open risks

- The connect path is used on hardware through the web installer's Wi-Fi step; over Bluetooth it is
  only lightly tested (vendor builds can differ from AOSP).
- Android uses a resolvable private address for advertising; it rotates (~15 min), so HA may
  rediscover the panel under a new address during a long provisioning window.
- If a saved (but not connected) network with the same SSID exists, `addNetwork` overwrites its
  password; a failure then removes it.
- The suggestion fallback needs someone to open the notification shade on a kiosk with hidden bars.
- Only one Improv client at a time is tested; notifications go to every subscribed client.

### From the web installer: Improv Serial + setup channel (over adb) ✅

The same setup channel also runs over the network, from the panel's own page at `http://<panel>:8099/setup`,
paired with a code the panel shows: see [Set up over the network](manage.md#set-up-over-the-network) (#34).

After installing the app, the web installer (kiln/, Tango over WebUSB) does the rest of onboarding
from the computer through two byte streams it opens with adb's `localabstract:<name>` service. Both
are abstract Unix sockets in the app, started by `KioskService` (`AdbSocketServer`, in the
[Improv library](../libs/improv/README.md)), and only accept
adb: the peer uid (`LocalSocket.peerCredentials`) must be 2000 (adbd as shell) or 0 (`adb root`)
(`AdbPeer`, `AdbPeerTest`); anything else is closed and logged. The USB connection, authorized with
the installer's adb key, is the physical authorization, so there is no Allow tap. Connections can
come one after another (or side by side, up to 4). The client side is the protocol source of truth:
the [`improv-serial`](../kiln/packages/improv-serial/README.md) package (`kiln/packages/improv-serial/`)
and `kiln/packages/setup-channel/src/channel.ts`.

**Wi-Fi: Improv Serial on `@s1kiosk_improv`** (`ImprovSerial.kt` in the [Improv library](../libs/improv/README.md), pure, `ImprovSerialTest`;
glue `frames/aqara-s1-plus/android/…/improv/ImprovSerialServer.kt`). [Improv Serial](https://www.improv-wifi.com/serial/) framing
(`IMPROV`, version 1, type, length, data, checksum) over the stream instead of a UART.
- State: Ready (authorized) from the start, or Provisioned when the panel is already on Wi-Fi (the
  installer then says so; new Wi-Fi settings are still accepted).
- Wi-Fi settings: state Provisioning, then joins through `ImprovController.connectFromInstaller` on
  the same `WifiProvisioner` as Improv via BLE (it turns Wi-Fi on first). Then state Provisioned +
  an RPC result with `http://<ip>:8099/signin`, or error 0x03 and back to Ready.
- Request state (+ the URL as a result when provisioned), request device info (`Glassworks`, app
  version, the frame's chip (`Allwinner A133` on the S1), and the friendly name as Home Assistant shows
  it, "Aqara S1 Plus" on the S1).
- Scan: a fresh WifiManager scan (Wi-Fi turned on if off; results on `SCAN_RESULTS_AVAILABLE` or after
  15 s at most, cached ones at once if the scan is throttled). Hidden SSIDs are skipped, one entry per
  SSID keeping the strongest, strongest first; one RPC result `[ssid, rssi, YES|NO]` each, then an
  empty result.
- A malformed packet (bad checksum or version, bad lengths, wrong strings) gets error 0x01, an
  unknown command 0x02. Passwords are never logged (only their length, by `WifiProvisioner`).

While the installer's join runs, the panel's setup screen shows it in its Wi-Fi step like any join
from the computer (`ImprovController.installerWifi`: connecting to <SSID> with WifiProvisioner's
progress, e.g. "Turning Wi-Fi on…" or "Waiting for Wi-Fi…", then connected or failed with the reason).

**Home Assistant and radio: the setup channel on `@s1kiosk_setup`** (`setup/SetupChannel.kt`, pure,
`SetupChannelTest`; glue `setup/SetupChannelServer.kt`). The core answers `state` and the Home
Assistant address; every pane's setup step (`PaneDefinition.setupSteps`, the radio's is in
`radio/RadioDefinition.kt`) adds its part of the state under its id and its own requests as
`<step id>.<op>`. `SetupChannelStepsTest` finishes every step with requests alone and pins the
replies Kiln gets (`kiln/packages/setup-channel/test/aqara-s1-plus-transcript.json`). One JSON object per
line, both ways:
- `{"id":1,"op":"state"}` → `{"id":1,"ok":true,"state":…}`. The state is channel.ts's `SetupState`:
  `ip` (wlan0 IPv4), `ha.discovered` (the HA address the app found itself: `ha_url_source` `mdns` or
  `esphome`, not `manual`), `ha.url` (the address in use, `KioskPrefs.haBase`, "" until known),
  `ha.signedIn`, `ha.signinUrl` (`http://<ip>:8099/signin` once there is an ip and an HA address),
  `ha.dashboards` (selectable dashboard labels once signed in and custom dashboards exist),
  `ha.dashboard` (the currently selected dashboard label),
  `radio.mode` (RadioMode id), `radio.status` (RadioController's status text), `radio.options` (the
  radio's modes: id, label, detail, `experimental` only when true), `radio.helper`
  (root helper installed) and `radio.answered` (the panel's radio step is answered; an app that doesn't
  send `answered` restarts the radio on a same-mode request). Unknown optional fields are left out.
- `ha.set_url` (first name `set_ha`, which still works) with `url` (http/https only): applied on the main thread exactly as the `url` intent extra
  (dashboard URL setting saved, then the dashboard reloads, or the process restarts when the
  secure-context hosts or the dashboard's origin (scheme, host or port) change, after the reply). The panel's setup screen moves on by itself.
- `ha.set_dashboard` with `dashboard` (a label from `ha.dashboards`): resolves the Lovelace dashboard URL on the panel's existing Home Assistant host, saves it, and reloads the dashboard in place without restarting.
- `radio.set_mode` (first name `set_radio`) with `mode`: through `RadioController.request`, like the
  radio card on the panel's Panes step. Only the ids in `radio.options` (the radio step's options, `RadioStep.OPTIONS`)
  are accepted. Any other id, also a `RadioMode` id the step doesn't offer, is `ok:false` with
  "Unknown radio mode '<id>'." and changes nothing. The reply waits for its outcome: success (or still applying after 10 s) marks
  the panel's radio step answered (`setup_radio_done`); a refusal or error is `ok:false` with
  RadioController's reason (e.g. the missing root helper). The mode in effect already, running or on its
  way (`RadioStep.classify` OK or applying), only marks the step answered: no request, no helper
  restart, the same rule as the Panes card. Kiln sends it for a fresh panel kept on Off, when
  `radio.answered` is false.
- `radio.set_allow` (first name `set_radio_allow`) with `allow`: who may reach the radio's TCP
  services, as the `Radio allowed clients` entity ([radio.md](../frames/aqara-s1-plus/docs/radio.md)).
- `radio.thread_join`, no arguments (#166): starts *Join Home Assistant's Thread network*, the same
  join as the button on the panel and in HA (`auth/ThreadJoin`), and answers at once. A join already
  running is left to run. Refused (`ok:false`) when `radio.mode` isn't `thread_router` ("The radio isn't
  a Thread router: …") and when `ha.signedIn` is false ("The panel isn't signed in to Home Assistant:
  …"). Its progress is `radio.threadJoin`, sent only in `thread_router`:
  `phase` is `idle`, `fetching` (getting the dataset with the panel's sign-in), `needs_admin` (the
  panel's HA user isn't an administrator: the panel shows a QR code for `adminUrl`,
  `http://<ip>:8099/thread`), `joining` (the dataset is fetched and handed to the radio), `joined` or
  `failed` (also when the radio reports an error after the hand-off). Until the root helper has taken
  the dataset and reported a new status, the phase stays `joining` and `role` is left out: the status
  before that is the previous network's. A dataset the helper doesn't take within a minute is `failed`
  ("the radio's root helper didn't take …"). `reason` says why for `needs_admin` and `failed`, in plain words. `role` is the radio's
  Thread role once it has a dataset (`detached`, `child`, `router`, `leader`, …). `idle` is also what a
  restart shows, so a panel already on a network reads `idle` with a `role`. After *Leave Thread network*
  (no dataset again) a finished join reads `idle` too. Never the dataset, network
  key or PSKc: `RadioThreadJoinTest` checks every reply and log.
- While connected, `{"event":"state","state":…}` is pushed whenever the serialized state changes
  (checked every 1.5 s, once the client has asked for the state).
- No secrets ever: no tokens, no API key, no passwords.

The intent hand-off this replaces (`--es wifi_ssid/wifi_psk`, results on logcat) is gone.

### 2. Adoption + encryption: ESPHome dynamic keys ✅

- ✅ The panel advertises `_esphomelib._tcp` and implements the ESPHome native API with Noise.
- ✅ **Dynamic key** (`NoiseKeys.kt`, `ApiServer` in [`libs/esphome-api`](../libs/esphome-api/README.md)). A panel without a key behaves like an
  ESPHome build with `api: encryption:` and no YAML key; a panel with a key works as before.

  Verified against the sources:
  - HA 2026.11.0 `components/esphome/manager.py`: `on_connect` → `_handle_dynamic_encryption_key`.
    If the entry has no key and `device_info.api_encryption_supported`, HA generates
    `base64.b64encode(secrets.token_bytes(32))` (or reuses its stored key for that MAC). With
    `api_encryption_provisionable` it opens a second client with `noise_psk=ZERO_NOISE_PSK`
    (`_async_provision_key_over_noise`) and calls `noise_encryption_set_key(new_key)`, then stores
    the key and writes it to the entry. "The device drops the keyless session and the reauth flow
    reloads the entry with the stored key" (`_async_register_outgoing_target` docstring). The
    plaintext set-key path is HA's fallback for old firmware only. Removing the entry sends an
    empty key (`__init__.py` `_async_clear_dynamic_encryption_key`).
  - `config_flow.py`: `PROBE_NOISE_PSK` is a deliberately *wrong* key (base64 of 32 ASCII "0"s),
    used only to read the name from the "Handshake MAC failure" reply. It is not the provisioning
    key. Zeroconf only treats TXT `api_encryption` as "key required".
  - aioesphomeapi 46.25.0 (pinned in HA's `esphome/manifest.json`) `api.proto`:
    `DeviceInfoResponse.api_encryption_supported = 19`, `api_encryption_provisionable = 26`,
    `NoiseEncryptionSetKeyRequest` (124, `bytes key = 1`, the base64 *text*),
    `NoiseEncryptionSetKeyResponse` (125, `bool success = 1`). `ZERO_NOISE_PSK` = 32 zero bytes.
  - ESPHome 2026.10.2 (the version we report) device side. `api_frame_helper_plaintext.cpp`: with
    no key, a first byte `0x01` hands the socket to a Noise helper with the all-zeros PSK
    (`upgrade_helper_to_noise_`). `api_connection.cpp`: supported = true, provisionable =
    `!has_psk()`. Set-key decodes base64 to exactly 32 bytes, rejects all zeros, empty = clear,
    and over plaintext it still works but logs a deprecation warning. `api_server.cpp`
    `save_noise_psk`: the same key is a no-op success. The key is persisted first and activated
    **100 ms later**; then a `DisconnectRequest` goes to every client and they are force-closed.
    `mdns_component.cpp`: TXT `api_encryption=Noise_NNpsk0_25519_ChaChaPoly_SHA256` with a key,
    otherwise `api_encryption_supported=<same>` + `api_provisioning=zero-psk`.
  - The panel implements exactly that. The key goes to `KioskPrefs` (`api_key` + `api_encryption`,
    written with `commit()`) and survives restarts. Logs show only "key set (fingerprint xxxx)", the
    first 4 hex digits of SHA-256(key). The old "generate and log a key on first start" is gone.
  - Tested in `EspHomeApiTest` with aioesphomeapi playing HA: keyless connect → zero-PSK client
    `noise_encryption_set_key` → the keyless session is dropped → plaintext is refused
    (`RequiresEncryptionAPIError`), the zero PSK is refused (`InvalidEncryptionKeyAPIError`) →
    reconnecting with the new key works. Also: bad and all-zero keys are refused, the same key is a
    no-op, the key survives a server restart, and an empty key clears it. State machine unit tests
    are in `NoiseKeysTest`.
- Caveat: discovery is mDNS, so it needs HA and the panel on the same L2 network or an mDNS
  reflector. Otherwise it's a one-time "Add ESPHome → panel IP" in HA.

### 3. Finding Home Assistant ✅

The dashboard URL setting now defaults to **`auto`** (`BuildConfig.DEFAULT_URL` is gone). A URL that
is already saved in the prefs is kept as is. In `auto` mode the panel loads `<HA>/`. `HaDiscovery`
learns HA's URL without asking, in order:
1. ✅ HA's own mDNS advertisement `_home-assistant._tcp`, browsed with `NsdManager`. HA 2026.11.0
   `components/zeroconf/__init__.py` publishes TXT `location_name`, `uuid`, `version`,
   `external_url`, `internal_url` and `base_url` (= external or internal, "for backward
   compatibility"). Candidates are tried in order: `internal_url`, `base_url`, then the resolved
   `http://<ip>:<port>` (Android 10 can't resolve `.local` names). Verified: HA here advertises
   `internal_url=http://<HA's LAN address>:8123` (redacted here), and the panel found it over mDNS.
2. ✅ The address of the first ESPHome API client whose Hello `client_info` starts with
   "Home Assistant" (HA's `CLIENT_INFO`), with port 8123.
3. ✅ Manual entry on the sign-in screen when nothing was found.

Every candidate is confirmed with `GET <url>/manifest.json` (the frontend's PWA manifest, `name`
"Home Assistant") before it is stored (`ha_url`, `ha_url_source`). A manual entry is never
overwritten. ESPHome exposes the URL the panel uses as the text_sensor **Home Assistant URL**.

A dashboard URL set by hand on a `.local` host (`http://homeassistant.local:8123/…`) loads through
the address discovery stored, on Android 6 to 11, whose resolver can't look up `.local` names (#449).
The setting keeps the name; the engine's secure-context host, the sign-in base and relative media URLs
use the address. A failed load browses for Home Assistant again, in case DHCP moved it, and a new
address restarts the app onto it. `https://` URLs keep the name, since the certificate is for it.
Android 12 and later load the URL as set.

Finding Home Assistant restarts the app: Gecko reads the Home Assistant host into its secure-context
allowlist only at startup, and the legacy engine holds every page to the origin it starts with. When the
setup screen is up at that restart, the new process puts it up again at once (`setup_up_at_restart`, read
once), and for its first 20 s the Sign in step says *Connecting to Home Assistant…* until the page shows
whether a sign-in is needed: Home Assistant's login page asks for one, and a session the page already has
goes on. So Home Assistant's page never shows between the Wi-Fi step and the sign-in (a Mirror run,
2026-11-20). On a frame without touch, the screen stays on for as long as the setup screen is up.

### 4. Login without typing on the panel: QR sign-in + external auth ✅

This is the part that removes the login screen. Implemented in `auth/` (`OAuth.kt`,
`SignInServer.kt`, `HaAuth.kt`), the setup screen's Sign-in step (`setup/SetupOverlay.kt`, formerly
`SignInOverlay.kt`), `MainActivity` and
`assets/extension/content.js`. Verified details:

- **IndieAuth** (HA 2026.11.0 `components/auth/indieauth.py`): `verify_redirect_uri` accepts a
  redirect_uri with the same scheme + netloc as the client_id without fetching anything.
  `_parse_client_id` accepts any *local* IP host (192.168.x is fine). `/signin` builds both from
  the `Host` header the phone used: `client_id=http://<panel-ip>:8099/`,
  `redirect_uri=http://<panel-ip>:8099/callback`. HA's authorize page loads for them (GET checked
  against this HA).
- **Login flow → callback** (`login_flow.py`, frontend `src/data/auth.ts` `redirectWithAuthCode`):
  when the flow finishes, the redirect_uri is checked and the browser goes to
  `<redirect_uri>?code=<code>&state=<state>` (plus `&storeToken=true` if ticked).
- **Token endpoint** (`components/auth/__init__.py` `TokenView`): form-encoded.
  `authorization_code` + `code` + `client_id` returns `access_token`, `refresh_token`,
  `expires_in` (1800), `token_type`, `ha_auth_provider`. Codes are single use and expire after 10
  min (`{"error":"invalid_request","error_description":"Invalid code"}`).
  `refresh_token` + `client_id` must use the *same* client_id the token was issued to, otherwise
  `invalid_request`. A revoked token gives `invalid_grant`. `POST /auth/revoke token=…` always
  answers 200.
- The panel keeps `{refresh_token, client_id, ha_base}` in the private `auth` prefs (never
  logged). The access token is cached until 60 s before `expires_in`. `invalid_grant` /
  `access_denied` on refresh deletes the token and shows the sign-in screen.
- **External auth** (frontend 20261028.4, the version HA 2026.11.0 pins). `src/data/external.ts`:
  `isExternal = window.externalAppV2 || window.externalApp || webkit… || ?external_auth=1`,
  evaluated once at startup. `external_auth.ts` throws unless `externalApp` exists, calls
  `externalApp.getExternalAuth(JSON.stringify({callback:"externalAuthSetToken", force?}))` and
  waits for `window.externalAuthSetToken(true, {access_token, expires_in})` (or `(false)`). On
  logout it calls `revokeExternalAuth('{"callback":"externalAuthRevokeToken"}')`, and the app
  answers `window.externalAuthRevokeToken(true)`. If `externalApp.externalBus` exists,
  `createExternalAuth` *awaits* `config/get` over the bus, so the bridge answers it with
  `window.externalBus({id, type:"result", success:true, result:{…}})` (asynchronously, because the
  frontend registers the id after the call). The other answer-expecting messages
  (`entity/add_to/get_actions`, `matter/share_device`, `webrtc/stream/start`) get
  `success:false`. Fire-and-forget messages are ignored. The callback names match the Companion
  app's `FrontendJsBridge.kt` (`EXPECTED_GET_AUTH_CALLBACK` / `EXPECTED_REVOKE_AUTH_CALLBACK`).
- The content script installs `window.externalApp` (`createObjectIn` / `exportFunction`) only on
  pages loaded with `external_auth=1`. The app adds the flag to every top-level page load on the
  signed-in HA origin once a refresh token exists (`onLoadRequest`), so a page on another origin
  (Home Assistant moved, #467) gets no flag although a token exists. Tokens are only given to
  top-level pages on the signed-in HA origin. **Without a refresh token nothing changes**: the
  GeckoView's own cookie/localStorage session works as before.
- The sign-in screen (now the setup screen's Sign-in step) appears when the GeckoView lands on HA's
  `/auth/authorize` (logged out) and there is no refresh token, after a sign-out, or when Home
  Assistant moved (a token is stored, but for another address; see below). It goes away by
  itself when the token arrives. `--ez show_signin true` previews it without touching the session.
- **Home Assistant moves (#467).** The sign-in is for the origin it was issued at (`ha_base`). When the
  dashboard's Home Assistant has another origin (DHCP moved it and discovery found the new address, or https
  was turned on at the same host), the panel asks to sign in again: the Sign-in step shows instead of HA's
  login page, with "Home Assistant moved to a new address. Sign in again.", and its QR code works on a frame
  without touch. On a screen nothing else can wake, the step wakes it and keeps it on. The phone's setup page
  and Manage say the dashboard isn't signed in and offer the sign-in, HA's *Signed in* sensor says `no`, and
  the settings menu drops its Dashboard sign-in row, as with no sign-in.
- The token goes to no address other than the one the sign-in is for. While the dashboard is elsewhere it
  goes nowhere: no refresh, no cached token, no revoke. Signing in again stores a new sign-in. The old refresh
  token stays in the old Home Assistant, under your profile, Security, Refresh tokens, where you can delete it.
- The panel never follows a move by itself, because discovery can't prove which device answers at the new
  address. HA's mDNS `uuid` is public, and anything on the LAN can advertise it with its own `internal_url`
  and a copy of `/manifest.json`.

The original design notes:

- **QR → phone → HA OAuth.** The panel runs a tiny HTTP server (e.g. `:8099`) and shows a QR code for
  `http://<panel-ip>:8099/signin`. That page says what signing in is for (#63: "Sign in this panel.",
  the Home Assistant address, and that the panel never sees the password); its *Continue to Home
  Assistant* (`/signin/continue`) redirects the phone to HA's standard OAuth endpoint:
  `/auth/authorize?client_id=http://<panel-ip>:8099/&redirect_uri=http://<panel-ip>:8099/callback`.
  HA uses IndieAuth: the client is identified by its URL, so nothing has to be registered in HA. The
  user signs in on the phone. HA redirects the phone back to the panel with an auth code, and the panel
  exchanges it for a **refresh token** (`/auth/token`). The phone shows "Panel signed in.". The Thread
  join's QR code (`/thread`) works the same way: a page that asks for an administrator, then
  `/thread/continue`. The panel's setup page starts the same flow in its own tab with
  `/signin/continue?then=setup`; its callback goes back to `/setup` instead (#453). Kiln over USB opens
  `/signin/continue?then=kiln` in a pop-up; its callback closes that window, and Kiln moves on when the
  panel reports the sign-in. The pages are one template in `SignInServer.kt` (system font, inline mark, dark
  or light as the phone is). Once the panel is signed in, `/signin`, `/signin/continue` and `/callback` no
  longer replace that sign-in for anyone on the network: only a browser signed in to the Manage page may,
  and only when that sign-in started from it. After a sign-out, a revoked token or a move of Home
  Assistant the panel isn't signed in, and the pages work for anyone again. Like the Manage page, they answer
  only to the panel's own names (its addresses, `localhost`, its `.local` names), so a page on another site
  can't reach them by DNS rebinding.
- **PKCE.** The callback is plain HTTP on the LAN, so both sign-ins (this one and the Thread join's
  admin sign-in) use PKCE (RFC 7636, S256). Each attempt gets a fresh secret that stays on the panel.
  HA gets its hash at `/auth/authorize` and the secret itself at `/auth/token`, so a code read off the
  phone-to-panel callback can't be redeemed by anyone else. It doesn't protect the panel's own request
  to HA: if HA is on plain `http://`, that request and its tokens can be read on the network as before.
  HA checks PKCE from 2026.10 on (verified against 2026.11.0, 2026-11-11); older versions ignore it and
  sign in as before.
- **External auth bridge.** The HA frontend supports being embedded by an app that owns the tokens.
  This is how the Companion app avoids the login page. Load the dashboard with
  `?external_auth=1` and expose `window.externalApp.getExternalAuth()/revokeExternalAuth()` (our
  built-in web extension can inject it). The frontend then asks the app for an access token, and the
  app mints it from the refresh token. No login page, sessions don't expire, and **revoking** is
  standard: HA → Profile → Security → refresh tokens.
- Recommended: a dedicated non-admin HA user for panels (e.g. "Panel"), so a stolen panel can't
  administer HA.

Alternatives, kept as options:
- **Sign in on the panel** (works today). Uses the on-screen keyboard.
- **Paste a long-lived token** into a `text` entity on the panel's ESPHome device page in HA (no
  typing on the panel, but tokens never rotate; HA text entities allow up to 255 chars, enough for a
  JWT).
- **`trusted_networks` auth provider** with `allow_bypass_login`. Truly zero-touch, but needs YAML
  edits, a static panel IP and a security trade-off. Document only.

**Verified end to end on the panel (2026-11-09):** QR flow → HA login (as a non-admin user) →
`/callback` stores the refresh token → the panel reloads HA with `?external_auth=1` and the frontend
connects with app-minted access tokens (no login page). The sign-in survives a full app restart
(fresh refresh), and HA shows `sensor.<panel>_signed_in = yes`. Two GeckoView 159 quirks were found and fixed
on the way:
- Replies to `browser.runtime.sendNativeMessage` never reach the page (the parent logs
  "Invalid event data for callback", and the promise rejects with `undefined`). All app ↔ page
  request/response now goes over the long-lived native port with request ids (`content.js` `request()`,
  `MainActivity.onPortRequest`).
- `GeckoBundle` has no `Long`; reply fields must be Int, Double, String or Boolean (`expires_in` is sent as Int).

### 5. Which dashboard ✅

- ✅ ESPHome **select** `Dashboard` (`select.s1_panel_dashboard` on a panel added before #63, `glassworks_panel_`
  on one added before #260, `aqara_s1_plus_` on a new S1, [Names](esphome.md#names); object_id `dashboard`) on the
  device page. Options: **Default** (HA's root, i.e. the user's default dashboard), then every
  Lovelace dashboard by title (duplicates get the `url_path` appended), then **Custom** only while
  the saved URL matches none of them. Choosing one saves the dashboard URL and reloads; the URL stays
  on the same HA host, so no restart for the secure-context hosts. Default goes back to `auto` when
  discovery found that same HA, else to `<ha>/`. Code: `dashboard/DashboardOptions.kt` (pure,
  `DashboardOptionsTest`), `PanelDevice`.
- ✅ Current state: `auto`, `<ha>/` and `<ha>/lovelace…` → Default; `<ha>/<url_path>[/view][?…]` → that
  dashboard; any other path or host → Custom.
- ✅ **Where the list comes from:** the logged-in HA frontend in the GeckoView, so it works with
  today's cookie session as well as with external auth. `content.js` calls the page's own
  `document.querySelector('home-assistant').hass.callWS({type: 'lovelace/dashboards/list'})`
  (through `wrappedJSObject`, callbacks via `exportFunction`) 5 s after an HA page loads (retrying
  every 5 s for 3 min until `hass.connected`), then every 10 min. It sends
  `{type: "dashboards", json: "[{url_path,title}]"}` fire-and-forget on the native port (a JSON
  string, so no GeckoBundle typing issues). `MainActivity.onPortRequest` only accepts it from the
  top-level page on the HA the panel uses. For non-admin users, `require_admin` dashboards are left
  out. HA 2026.11.0 `lovelace/dashboard.py` `DashboardsCollectionWebSocket.ws_list_item` returns
  `dashboard.config` for every dashboard that has one (storage and YAML). The built-in default has
  none, so it is never listed.
- ✅ **App-side WebSocket fetch on sign-in:** `HaGatewayClient.dashboards` connects to Home Assistant's
  `/api/websocket` with the panel's access token right when sign-in completes, checks `auth/current_user`
  (`is_admin`, filtering out `require_admin` dashboards for non-admins just like `content.js`), and calls
  `lovelace/dashboards/list`. That makes the dashboard list available immediately during setup (Kiln and
  `/setup`), in panel settings and Manage, and in Home Assistant before the dashboard frontend finishes
  loading. The frontend path keeps refreshing the list every 10 minutes and covers cookie sessions. The
  last list is persisted in prefs (`dashboards`).
- ✅ **Options are static per entity listing**, so a changed option list rebuilds the entity set and
  drops the API connections (`ApiServer.setEntities`, the same mechanism as the wall base), and HA
  re-lists. It's debounced in `dashboard/StableChange.kt` (`StableChangeTest`): only when the
  options really changed, stayed stable for 10 s, and at most once a minute. A list change that
  keeps the same options only updates the mapping. The key never changes (fixed object_id). The
  list the options were built from is persisted, so a restart doesn't rebuild. Expect **one**
  rebuild (an HA reconnect) the first time the list arrives after updating to this version, and
  one each time "Custom" appears or disappears.
- ✅ The existing `text.s1_panel_dashboard_url` stays for anything custom.

### Setup screen ✅

`setup/SetupOverlay.kt` (UI), `setup/SetupState.kt` (rules, pure, `SetupStateTest`), wired in
`MainActivity`. A native full-screen overlay over the GeckoView, drawn in #63 on the Glassworks brand
(`ui/Glass.kt`: night `#0a0b0f` with sky/violet glows, glass cards, one white button per screen, sky
to violet for "on", green/amber/rose for status only). Left: the Glassworks mark, the four steps (a
green dot when done, sky to violet for the current one, a dashed ring for the rest) and the stack,
whose plates set one per step (`ui/StackView.kt`). Right: a card for the current step.
Laid out for 960×480 dp. On a screen taller than wide (`onMeasure`, #415) it stacks into one column: the
rail above the card, without the stack art, and the QR code under the card's text at full size.

The rail (`ui/GroupedRail.kt`, #63 round 2) groups the steps: Wi-Fi, **Home Assistant** (Sign in, Add
it), **Panes** (Choose, then each pane's own steps while that pane is on), the device's own steps (Silent
updates, each a row of its own), Done. A group shows its rows while one of them is current and folds to one row
otherwise. The order is Wi-Fi → Home Assistant → Panes → Silent updates → Done (`SetupFlow.ids`): nothing a pane asks needs to come before signing in, and the radio's
Thread router can then join Home Assistant's Thread network from its own step. Tapping a row opens
it: in a live setup only a step that is done or current, in a preview any step. A step that is done
says so and leads with **Continue** ("Connected to <SSID>.", "Signed in.", "Added to Home
Assistant."), with the way to change it second ("Use another network", "Add it by hand").

1. **Wi-Fi**: "Connected to <SSID>" (Ethernet too), or "Not connected: set up Wi-Fi over Bluetooth
   from Home Assistant, or [Choose network]". The button opens `Settings.Panel.ACTION_WIFI` (falls
   back to `Settings.ACTION_WIFI_SETTINGS`). The status comes from the `WifiSetupStatus` interface
   (`setup/WifiSetupStatus.kt`: state `CONNECTED | NOT_CONNECTED | CONNECTING`, SSID, optional
   message), here `improv/ImprovWifiStatus.kt` (connectivity + "connecting to <SSID>" while Improv
   or an installer join runs). The SSID needs location permission on Android 10; without it the
   step shows "Connected".
   **Improv inline.** While the setup screen is up, Improv is a block in this step and the separate
   `ImprovOverlay` window is never shown (`ImprovController.InlineHost`); it is still used when
   Improv is started later with the setup screen gone (intent, HA button). The block follows
   `ImprovController.setupFacts` through `setup/WifiStep.kt` (pure, `WifiStepTest`): off [Use
   Bluetooth] · couldn't start (reason) [Try again] · starting Bluetooth · on, with where HA lists it
   (Settings → Devices & services → Discovered → "Improv via BLE") [Allow] [Not now] · a device
   connected and asking [Allow] [Not now] · allowed m:ss, waiting for Wi-Fi details [Stop] ·
   connecting to <SSID> · connected · failed (reason, still allowed). Allow / Not now mean the same
   as the overlay's. Improv running (or an installer join) shows the screen without the 30 s grace.
   **Wi-Fi from the computer.** The web installer sends Wi-Fi over Improv Serial (see
   [From the web installer](#from-the-web-installer-improv-serial--setup-channel-over-adb-)); the
   join uses the same `WifiProvisioner.connect` as Improv (it turns Wi-Fi on first) and its progress
   shows here: connecting to <SSID> (with the provisioner's progress), then connected or failed.
2. **Sign in** (under Home Assistant): "Scan to sign in.", the QR code, and "Sign in on this panel
   instead" (or "Find Home Assistant." with a URL field when HA wasn't found). It is answered by a refresh
   token, or once the dashboard loads while a later step still waits (a panel signed in with a cookie
   session). "Sign in on this panel instead" is the same sign-in on the panel's screen (#633,
   `auth/PanelSignIn.kt`): the page loads HA's `/auth/authorize` with the client_id `<HA>/glassworks/` and the
   redirect_uri `<HA>/glassworks/callback`, both on HA's own origin, so the legacy engine loads it and HA shows its
   plain login page. The app takes the redirect back before the page loads it, trades the code (with PKCE) and stores
   the refresh token as the QR code's callback does. A session the page already had never counts: the login page
   shows anyway. If the trade fails, the Sign-in step comes back over a new login page. Without touch it shows the setup page's QR code until Home Assistant has added the panel,
   then the sign-in's own. If Home Assistant added the panel first, Add it is green and Sign in current.
3. **Add it** (under Home Assistant): done once an ESPHome client whose Hello says "Home Assistant"
   **subscribes to states**. HA's config flow only probes DeviceInfo during discovery, so a plain
   Hello doesn't count. `ApiServer.onStatesSubscribed` → `PanelDevice` persists `ha_seen` and
   notifies `SetupSignals`. Until then the step shows "Add it in Home Assistant. Settings → Devices &
   services → <the panel's name> → Add", "Waiting for Home Assistant…", and the panel's IP + port
   6053 for a manual ESPHome add. In the first 2 min after the app starts, Home Assistant may still connect on its own
   (an entry it already has), and the waiting line says "Checking whether Home Assistant knows this panel…" (#665). Without touch, once signed in, it waits for Home Assistant to add the
   panel (#484). **Skip** persists `setup_ha_skipped` (for kiosk-only use without the ESPHome device).
4. **Panes**: "Choose", one card per pane that offers one (`PaneDefinition.setupChoice`): Voice
   (Assist and timers), Music (a Sendspin speaker), the Zigbee / Thread radio, the Bluetooth proxy
   (for BLE sensors, off by default; no card on a panel without Bluetooth LE) and the Wall base (the
   S1's wired base), each with a switch, two to a row in a list that scrolls above Continue. It
   only chooses. **Continue** applies every card off the main
   thread, each within 30 s: voice, music and the wall base set their on/off (`PaneSwitch`), which the
   panel applies at once, with no restart (#67); the radio's switch off sets the mode Off through
   `RadioController`. A refusal, or a card
   that doesn't answer ("The Music didn't answer."), shows in rose with every problem listed and keeps
   the step; the cards are locked while it applies. Then each pane that is on has its own steps under
   Panes (`PaneDefinition.setupSteps`, each with a `title` for its row and a `screen` for the card).
   A pane with steps but no card is left out, with an error in the `Glassworks` log.
   **Radio**: "What should the radio do?", technology first (`radio/ui/RadioTechPicker.kt`): Zigbee
   (Coordinator, Repeater β) or Thread (Border router, Repeater β), no Off (that is the Panes card).
   Continue applies it through the same request as the installer's `radio.set_mode`, within 30 s
   ("The radio didn't answer. Try again, or turn it off on Panes."). Then the mode's follow-up card,
   the one Settings → Radio shows: the ZHA serial path, the QR codes, or Join for a Thread router. The
   step stays until its Continue. Answering persists `setup_radio_done` (so does a mode set from the
   web installer over the setup channel). A panel set up before the radio step existed has it marked
   done once at the first start of the new build (`RadioDefinition`'s one-time check: a known HA,
   `ha_seen`, an API key, a skipped HA step or a stored radio mode), so it never sees it.
5. **Silent updates** (`SilentUpdatesStep`, #422), only when updates ask for a tap and there's a way to change
   that, and only on a screen that takes a tap: Android 12+ with "Install unknown apps" off opens Android's page for
   it; Android 6 to 11, or a release that targets too low an API, says a device owner skips the tap (QR code to
   Kiln); an S1 whose boot image has no update service says Kiln adds one. Continue or Not now answers it
   (`setup_silent_updates_answered`), and so does `silent_updates.answer` over the setup channel. A panel set up
   before the step existed has it marked answered once, at the first start of the new build.
6. **Done**: the lit stack on its own, "You're all set." and "Opening your dashboard…". The dashboard
   loads (unless it's already showing), then the screen fades out.

**When it shows** (`SetupState.decide`). Only strong evidence counts, so it never covers a panel
that already works (the desk panel: Wi-Fi, ESPHome connection from HA, a saved URL, a cookie
session without a refresh token):
- offline for 30 s (boot grace; a short Wi-Fi dropout doesn't trigger it), or offline at all on a panel
  that was never set up (no grace there: a fresh panel goes straight to the Wi-Fi step). On a screen
  without touch (the Mirror), no grace either unless Wi-Fi is joining or has a network to join: the
  loading screen's offline text would point at a button nobody can press (#448);
- online but HA isn't known at all (no saved URL, nothing discovered);
- HA never added the panel: no HA client connected within 2 min of startup *and* no `ha_seen`, no
  API key, not skipped (not after "Sign in on this panel instead" until the page shows Home Assistant: the person
  signs in on HA's own login page first);
- no refresh token *and* the GeckoView actually reached HA's `/auth/authorize` (or the user signed
  out on the panel), unless "Sign in on this panel instead" was chosen;
- online, HA step done, the panes not chosen yet or a chosen pane's step not answered (so it comes back
  after a reboot);
- online, HA step done, every pane's step answered, and the Silent updates step pending: something to offer, on a
  screen that takes a tap, not answered yet (step 5 above). Until the app has read whether updates are silent (a
  moment after it starts), the step shows *Checking updates* and setup waits for that first answer; with nothing to
  offer it then moves on by itself.

The current step is the first one not done (Wi-Fi → Sign in → Add it → Panes → each pane's steps → Silent updates). Once shown, the screen stays
until all steps are done, so it doesn't flicker between steps. It hides for HA's login page after "Sign in on this panel
instead", and comes back as soon as that sign-in ends, also within the 2 min grace (#665). It is re-evaluated on connectivity,
sign-in, HA connection and page changes, and every 2 s. The trigger is logged (`Glassworks`:
"Setup screen: <reasons> (step …)").

Preview without changing state: `--ez show_setup true` (live step), `--es setup_step
wifi|ha|signin|panes|radio|silent_updates|done` (a given step; a pane's or the device's step id opens that step), `--ez show_setup false` to close.
In preview, Skip, the manual URL and Continue save nothing, and "Preview · Close" is shown. `--ez show_signin true` = the Sign-in step.

## Build order

| # | Piece | Size | Notes |
|---|---|---|---|
| 1 | ✅ Dynamic encryption key (`api_encryption_provisionable` + set-key over zero-PSK Noise) | S | aioesphomeapi test of HA's exact flow |
| 2 | ✅ HA URL discovery (`_home-assistant._tcp` + ESPHome peer) | S | Found HA over mDNS on the panel |
| 3 | ✅ QR sign-in (mini HTTP server + IndieAuth code exchange) + external-auth bridge | M | Verified end to end on the panel |
| 4 | ✅ First-boot setup screen | M | Rules unit-tested (desk panel must not show it); in use on panels since 0.2.0 |
| 5 | ✅ Dashboard select entity | S | List from the HA frontend (cookie session OK); aioesphomeapi test of the option rebuild; no app-side websocket fallback |
| 6 | Improv via BLE ✅ | M | Advertising verified on the RTL8821CS; the join path is used through the installer; Bluetooth end to end lightly tested |

## Open questions

- Dedicated "Panel" HA user, or sign in as yourself?
- Should a panel that can't reach HA's mDNS (separate VLAN) ask for the URL on the setup screen, or
  rely on HA's first ESPHome connection to learn it?
