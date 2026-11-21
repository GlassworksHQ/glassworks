# Bluetooth: can the S1 be an ESPHome Bluetooth proxy, and what do Android 10's scan rules allow?

**Status (2026-11-13): untested on the panel.** The panel's Bluetooth state was read with read-only
adb. Android 10's scan rules were read from AOSP. The proxy hasn't scanned on the panel yet: the
panel plan in #121 does that, and measures Wi-Fi and Sendspin with scanning on and off.

Device: Aqara Panel Hub S1 Plus, Android 10, S1 Kiosk 0.4.3. Tools: adb (read-only), AOSP
`packages/apps/Bluetooth` at `android10-release`, aioesphomeapi 46.25.0, ESPHome 2026.10.2,
bleak-esphome 4.0.0 and `main` (2026-09-12), HA 2026.11.1.

## Question

Issue #121 makes the panel an ESPHome Bluetooth proxy, so HA hears the BLE sensors near it. Before
that ships we need to know:

- what Bluetooth chip the panel has, and whether it shares the radio with Wi-Fi;
- what Android 10 does to a scan that runs for days, with the screen off at night;
- what the app needs (permissions, location);
- for GATT connections later: how many, and what Android hides.

## What we tried

1. **The chip and its coexistence** (2026-11-13, verified)
   ```
   $ adb shell 'getprop | grep -iE "bluetooth|wlan.driver" | grep -viE "addr|mac"'
   [persist.vendor.bluetooth.rtkcoex]: [true]
   [persist.vendor.bluetooth_port]: [/dev/ttyS1]
   [persist.vendor.bluetooth_vendor]: [realtek]
   [vendor.realtek.bluetooth.chip_name]: [8821CS or 8821CSH or 8821CS-VB]
   [vendor.wlan.driver.version]: [v5.14.2.1-48-g54206815b.20221004_COEX20210319-5555]
   ```
   A Realtek RTL8821CS: Wi-Fi over SDIO and Bluetooth over UART (`/dev/ttyS1`) on one chip, with
   Realtek's coexistence on. Bluetooth and 2.4 GHz Wi-Fi most likely take turns on one antenna path
   (untested: not checked against a datasheet, not measured).

2. **The Wi-Fi band** (2026-11-13, verified)
   ```
   $ adb shell 'dumpsys wifi | grep -oE "Frequency: [0-9]+|Link speed: [0-9]+Mbps" | sort | uniq -c'
         1 Frequency: 5200
         2 Link speed: 390Mbps
   ```
   The dev panel is on 5 GHz, so Bluetooth doesn't share airtime with its Wi-Fi. A panel on
   2.4 GHz would. The panel plan measures 5 GHz; 2.4 GHz is an open question.

3. **Bluetooth, the scan filter slots, location, permissions** (2026-11-13, verified)
   ```
   $ adb shell 'dumpsys bluetooth_manager | grep -iE "^  state|enabled:|mMaxScanFilters"'
     enabled: true
     state: ON
     mMaxScanFilters: 0
   $ adb shell 'dumpsys location | grep "Location mode"'
     Location mode: true
   $ adb shell 'dumpsys package sh.edm.s1kiosk | grep -E "LOCATION|BLUETOOTH"'
         android.permission.BLUETOOTH: granted=true
         android.permission.BLUETOOTH_ADMIN: granted=true
           android.permission.ACCESS_FINE_LOCATION: granted=true, ...
   ```
   Bluetooth is on, location is on, and S1 Kiosk already holds the permissions a scan needs (from
   the Matter work, #47). The controller has **no offloaded scan filter slots**.

4. **Android 10's scan rules** (2026-11-13, read: AOSP `packages/apps/Bluetooth`, `android10-release`)

   | Rule | Source |
   |---|---|
   | A 6th `registerScanner` within 30 s fails with `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` (6) | `GattService.registerScanner`; `AppScanStats.NUM_SCAN_DURATIONS_KEPT = 5`, `EXCESSIVE_SCANNING_PERIOD_MS = 30 s` |
   | A scan with no filters is suspended while every display is off, and while location is off. "Filtered" only means a non-empty filter list | `ScanManager.handleStartScan` (`isFiltered = filters != null && !filters.isEmpty()`) |
   | Results reach an app only with location on and FINE location (Android 10) | `GattService.hasScanResultPermission`, `Utils.blockedByLocationOff` |
   | After 30 min a scan becomes opportunistic, unless it is exempt. Its filters must fit in the controller to be exempt | `ScanManager.regularScanTimeout`, `isExemptFromScanDowngrade`, `shouldUseAllPassFilter` (`filters.size() > mFilterIndexStack.size()`) |
   | A scan from an app below foreground-service importance drops to `BLE_SCAN_BACKGROUND_MODE` (low power by default) | `ScanManager.handleImportanceChange` |
   | Duty cycles: low power 512/5120 ms, balanced 1024/4096 ms, low latency 4096/4096 ms | `ScanManager.SCAN_MODE_*_WINDOW_MS`, `_INTERVAL_MS` |

   With `mMaxScanFilters: 0`, one empty filter is already more than the controller holds. The scan
   then uses the all-pass filter, isn't exempt, and becomes opportunistic after 30 min. The empty
   filter still counts as "filtered", so it keeps running with the screen off.

5. **What Home Assistant needs from the device** (2026-11-13, read)

   | What | Source |
   |---|---|
   | `DeviceInfoResponse.bluetooth_proxy_feature_flags` = field 15; `bluetooth_mac_address` = 18 | aioesphomeapi 46.25.0 `api.proto` |
   | HA connects a Bluetooth scanner for a device with *any* proxy flag set. `PASSIVE_SCAN` (bit 0) isn't read; ESPHome sets it on every proxy, and so do we | HA 2026.11.1 `esphome/manager.py` line 808 |
   | Then it subscribes to raw advertisements for `RAW_ADVERTISEMENTS` (bit 5) and makes the scanner connectable for `ACTIVE_CONNECTIONS` (bit 1). The scanner's source is `bluetooth_mac_address`, else `mac_address` | bleak-esphome 4.0.0 (HA 2026.11.1 pins it) `connect.py` `connect_scanner` |
   | One advertisement subscriber at a time; a new one replaces the old | ESPHome 2026.10.2 `bluetooth_proxy.cpp` `subscribe_api_connection` |
   | Batches of 16 (`BLUETOOTH_PROXY_ADVERTISEMENT_BATCH_SIZE`), partial batches every other 100 ms tick on Wi-Fi | ESPHome 2026.10.2 `bluetooth_proxy/__init__.py`, `bluetooth_proxy.cpp` `loop` |
   | A raw advertisement holds at most 62 bytes of AD data | `api.proto` `BluetoothLERawAdvertisement.data` `fixed_array_size = 62` |
   | `FEATURE_STATE_AND_MODE` only where the scanner can switch between passive and active scans | ESPHome `bluetooth_proxy.h` `get_feature_flags` |

## Result

| Question | Answer |
|---|---|
| Chip | RTL8821CS, Wi-Fi and Bluetooth on one chip, Realtek coexistence on (verified) |
| Shares airtime with Wi-Fi? | Likely only with 2.4 GHz Wi-Fi; the dev panel is on 5 GHz (band verified; the sharing untested) |
| Permissions and location | Already in place on the dev panel (verified) |
| Scan with the screen off | Runs, with one empty `ScanFilter` (read) |
| A scan for days | Restart it every 25 min, at most 4 starts per 30 s (read) |
| Passive vs active scan type | Android doesn't let an app choose, so no `FEATURE_STATE_AND_MODE` (read) |

The `bluetooth-proxy` library (`libs/bluetooth-proxy`) follows all of these.

## How to reproduce

Every command above is read-only (`getprop`, `dumpsys`). `dumpsys bluetooth_manager` prints the
addresses of bonded and nearby devices: grep for the lines you need, or redact before you share it.

## Open questions

- **Wi-Fi and Sendspin with scanning on.** The panel plan in #121 measures throughput (`iperf3`)
  and Sendspin dropouts with the proxy off, at balanced and at low latency. Untested.
- **2.4 GHz.** A panel on 2.4 GHz Wi-Fi shares the antenna path with Bluetooth. Not measured.
- **Does Android pad the scan record? No, not between the two parts (read, 2026-11-20).** See
  [Scan-record padding](#scan-record-padding-read-2026-11-20) below.
- **GATT connections:** how many links the RTL8821CS holds at once, and whether the handles match an
  ESP32 proxy's. See [GATT connections](#gatt-connections-246) below. Untested.
- **The scanner's source: decided (maintainer, 2026-11-15).** The proxy sends the real Bluetooth MAC
  as `bluetooth_mac_address`, like ESPHome proxies. Android 10 hides it from apps (`getAddress()`
  answers `02:00:00:00:00:00`), so the root helper reads `settings get secure bluetooth_address` and
  publishes it in `/data/s1kiosk-pub/bluetooth-address`. Any failure falls back to the panel's ESPHome
  MAC (the Wi-Fi MAC, or one derived from the serial); the first real one is kept.
  **Verified on the S1 (2026-11-16, test build `s1-kiosk-test-6c948ce`, the root's run for #399):**
  - `settings get secure bluetooth_address` (as root) holds the adapter's address, and the helper's
    file matched it;
  - after the app restarted with the file in place, HA **re-keyed the panel's existing scanner entry in
    place** to the adapter's address: no second entry;
  - a second restart changed nothing; with the file deleted and the app restarted, HA kept the
    adapter's address (the app's kept copy): no flip back.
- **`svc bluetooth disable` needs root on this Android 10 build** (2026-11-16): as the adb shell it
  does nothing; as root it turns Bluetooth off.
- **Next to the Matter pane's BLE advertising (#47).** Android allows a scan and an advertiser at
  once; the chip doing both was not tried.

## Scan-record padding (read, 2026-11-20)

The question (#246): a legacy advertisement and its scan response each come from the controller in
a 31-byte field. Does Android 10 hand an app the advertisement's zero padding *before* the scan
response? The library keeps AD structures up to the first zero-length one, so padding in the middle
would lose the scan response.

**Answer (read): no.** Android 10 cuts each part's padding before it glues them, then pads the whole
record at the end. Sources, AOSP tag `android-10.0.0_r47`:

1. `system/bt/stack/btm/btm_ble_gap.cc`, `btm_ble_process_adv_pkt` (about line 1835): a legacy LE
   Advertising Report becomes `btm_ble_process_adv_pkt_cont` with the report's own `data_len`, and a
   `SCAN_RSP` becomes event type `0x001B`.
2. Same file, `btm_ble_process_adv_pkt_cont` (lines 1906–1931): for a legacy event it calls
   `AdvertiseDataParser::RemoveTrailingZeros(tmp)`, then `cache.Set` for the advertisement and
   `cache.Append` for the scan response. On an active scan it holds back a *scannable* advertisement
   until its scan response is in (`is_active_scan && is_scannable && !is_scan_resp`). A non-scannable
   one is reported straight away, with no scan response.
3. `system/bt/stack/include/advertise_data_parser.h`, `RemoveTrailingZeros` (lines 48–71): at the
   first zero-length field it erases everything from there to the end. Its comment gives the reason:
   "Otherwise i.e. gluing scan response to advertise data will result in data with zero padding in
   the middle."
4. `packages/apps/Bluetooth/src/com/android/bluetooth/gatt/GattService.java`, `onScanResult`
   (lines 965–1009): `legacyAdvData = Arrays.copyOfRange(advData, 0, 62)`, and a client with legacy
   `ScanSettings` (the default; `AndroidBleScanner` doesn't change it) gets those 62 bytes. So the
   record an app sees is the advertisement, then the scan response, then zeros to 62 bytes.

That is the shape the library already expects: it drops the zeros at the end and keeps both parts.
`BluetoothProxyEngineTest.adStructuresKeepTheScanResponseAndroidGluesOn` pins it.

What this doesn't cover:
- A vendor change to the panel's Bluetooth stack. The source read is AOSP's, and the panel's
  `com.android.bluetooth` and `libbluetooth` weren't compared with it. The Realtek parts sit below the
  stack, in the HCI transport, which doesn't touch advertising data (believed, not checked).
- A device that puts a zero-length field *before* real data. Android cuts the rest of that part,
  so neither the panel nor the library sees it.

To confirm on the panel (optional now): compare one scannable sensor's data, the one HA shows for it
through the panel and through an ESP32 proxy. The scan response's structures should be in both.

## GATT connections (#246)

**Status (2026-11-17): two real GATT devices verified on the S1, one link at a time**
([a thermostat](#verified-on-the-s1-2026-11-17), [a garden that holds its link](#verified-a-device-that-holds-its-link-2026-11-17)).
The connection engine (`libs/bluetooth-proxy` `BluetoothConnectionEngine`, `AndroidGattRadio`) is
built from what is read below. The link limit and the other open points are still untested.

### What Home Assistant needs (read)

| Fact | Source |
|---|---|
| aioesphomeapi refuses to connect through a proxy without `REMOTE_CACHING`: "old CONNECT method is no longer supported". Only the v3 connect types (4 with cache, 5 without) are sent | aioesphomeapi 46.25.0 `client.py` `bluetooth_device_connect` |
| With `REMOTE_CACHING`, HA keeps the services and the MTU per address, and writes the CCCD itself after a notify request. So the proxy's notify only arms notifications locally | bleak-esphome `client.py` (main, 2026-11-15) `connect`, `start_notify` |
| Answers go to the client that holds the advertisement subscription. A new subscriber, or none, closes every link | ESPHome 2026.10.2 `bluetooth_proxy.cpp` `send_device_connection`, `subscribe_api_connection`, `loop` |
| No free slot: answered "not connected" with error 0. Default `connection_slots`: 3 | `bluetooth_proxy.cpp` `answer_device_disconnected_`; `__init__.py` `DEFAULT_CONNECTION_SLOTS` |
| HA calls a proxy connectable, and asks for its slot table, only with `ACTIVE_CONNECTIONS` | bleak-esphome `connect.py` `connect_scanner` |

### What Android 10 gives an app (read)

| Fact | Source | What the engine does |
|---|---|---|
| `connectGatt(ctx, false, cb, TRANSPORT_LE)` connects. The address type is hidden from apps, but a `BluetoothDevice` from a scan result carries it | `BluetoothDevice`, `GattService` (android10-release) | Connects to the device the scanner heard (`RecentDevices`, the last 256, in memory). Else `getRemoteDevice`, which assumes a public address: a random-address device connects only if heard recently |
| One GATT operation at a time per `BluetoothGatt`: a second start returns false until the first's callback | `BluetoothGatt.mDeviceBusy` | A queue per link; an operation without a callback in 10 s drops the link, since Android won't start another |
| A service's and a characteristic's instance id is the ATT handle (the characteristic's value handle) | `BluetoothGatt.onSearchComplete` builds them from `GattDbElement.id`, the attribute handle | Sends instance ids as handles |
| A descriptor's instance id is hidden (`@hide getInstanceId()`). Lint's `SoonBlockedPrivateApi` flags reflection on it | `BluetoothGattDescriptor` (android10-release); AGP 9 lint | The ATT layout: a characteristic's descriptors are the attributes right after its value handle, in order (Core spec Vol 3 Part G 3.3). No private API |
| `BluetoothGatt.refresh()` is hidden and marked `@UnsupportedAppUsage` (greylist on 10). It clears Android's cache for one open client only | `BluetoothGatt`, `GattService.refreshDevice` | Not used. `CACHE_CLEARING` isn't sent; clear-cache requests are answered "failed" |
| A device that answers "insufficient authentication" can make Android start pairing on its own, which may show a dialog | `GattService`, bluedroid `bta_gattc` | Nothing it can stop. `PAIRING` isn't sent, so HA never asks the proxy to pair |
| `BLUETOOTH_CONNECT` is a runtime permission from Android 12; below that the install-time `BLUETOOTH` covers it | `Manifest.permission` | `Compat.connectPermissions` |

Read-only facts from the dev panel (2026-11-15): Android 10, S1 Kiosk 0.4.3 targets SDK 35, chip
"8821CS or 8821CSH or 8821CS-VB", `dumpsys bluetooth_manager` lists no GATT clients and no link limit.

### Verified on the S1 (2026-11-17)

**Verified** on 2026-11-17, 09:03–09:14 UTC, the root's run on #396. Setup:
- S1 Plus on the test build `s1-kiosk-test-1db558a` (the whole #246 stack on `main`), radio `thread_router`;
- the device: a GATT thermostat on a custom integration. HA reaches it over GATT through a proxy, and the integration reports which proxy in an *Active proxy* sensor;
- addresses were compared in the shell only; none is recorded here.

| What | Result |
|---|---|
| Before | The thermostat was off. Its active proxy was an ESP32 proxy next to it, at RSSI -31 |
| Proxy on, *Listen and connect* | The page: "Listening for Home Assistant", Scanner **On**, Connections **0 of 3** |
| The S1 becomes the active proxy | The nearer ESP32 was taken out (its ESPHome entry disabled; HA's Bluetooth entry untouched). **Two seconds later** *Active proxy* named the S1, at RSSI **-62**. No reconfiguration in HA. The page read **"1 of 3 · In use now"**. Android's GATT Client Map held one connection, owned by `sh.edm.s1kiosk` |
| Reads | The integration polled through the S1. Poll latency was **307 ms** through the S1 at -62, against **179 ms** through the ESP32 at -31 |
| A write | Setting a mode: HA showed the new mode **140 ms** after the call. Setting it back to off straight away was confirmed by the next poll. The thermostat reports no target temperature while off, so that write wasn't tried |
| Logs and screen | No "didn't come up" or "got no answer" lines, no address in any "Bluetooth proxy" log line, and no pairing dialog over the kiosk |
| *Listen only* | The link closed at once: no GATT client left, and the page read "— · None in listen only" |
| Put back | The ESP32's entry was re-enabled; the thermostat was off as found; the pane was off; the baseline build was reinstalled (same hash); radio `ok thread_router`, Bluetooth on |

What it shows:
- The S1 works as a connectable proxy for a real GATT device.
- HA moved the connection to the S1 as soon as it was the best connectable proxy left.
- At -62 against -31, a poll took 307 ms against 179 ms; the RSSI and the radio both differ, so this doesn't separate the two.
- **One link only, so the link limit is still untested.** There was one GATT device.
- Whether the integration keeps its link open between polls wasn't measured long enough to say. The link stayed up through the 4 minutes of the test.

### Verified: a device that holds its link (2026-11-17)

**Verified** on 2026-11-17, 22:11–22:17 UTC, the root's run (results on #246). Setup:
- S1 Plus on a test build of `main` at `d7e41b7a` (all of #246's GATT work), radio `thread_router`, Home Assistant 2026.11;
- the device: an indoor garden (Auk Mini 2) on the custom `auk_mini2` integration, which opens a GATT link and polls over it;
- two ESP32 proxies on the same network; addresses were compared in the shell only, and none is recorded here.

| What | Result |
|---|---|
| Proxy on, *Listen and connect* | HA's Bluetooth diagnostics list the S1 as connectable, `slots: 3, free: 3`. *Bluetooth proxy status* reads `Listening` |
| The S1 becomes the device's proxy | One of the ESP32 proxies held the link at first. Reloading the garden's config entry (nothing else in HA changed) made HA pick the S1. The S1's allocations then held one address, the garden's, with `free: 2`. Android's GATT client map held one connection, owned by `sh.edm.s1kiosk` |
| Read | The link came up at 22:14:21; every entity of the garden updated at 22:14:28, **7 s later** |
| Write, read back | *Sync clock* at 22:15:12; *Clock drift*, read back from the device, changed at 22:15:13.4, **1.4 s later** |
| Write and undo | *Light intensity* to `dim`, then back to `auto`: HA confirmed each |
| The link between polls | **Stays open**: one connection for the 80 s from connect until the proxy was turned off. This integration keeps its link, so it holds one slot for as long as HA uses the device through the S1 |
| Close | The pane turned off from HA at 22:15:43.5. The log reads `Pane bluetooth-proxy off`, then `scan stopped`. HA moved the garden to an ESP32 proxy and refreshed it at 22:15:44.2, **under 1 s later**. Nothing went unavailable, and Android's client map was empty |
| Errors | None: no Bluetooth proxy errors in logcat, no new `auk_mini2` or Bluetooth errors in HA, no pairing dialog |
| After the install | The LRU list was healthy after both restarts (no #248 crash loop) |
| Put back | The pane off and `bluetooth_proxy_connect` false, as found; radio `ok thread_router`; the garden back on an ESP32 proxy with *Light intensity* on `auto` |

How each check was made. These are the commands from the run's plan (`ADB` is the platform-tools `adb`, with
`ANDROID_SERIAL` set to the panel). The run's report gives the results above, not the raw output:

```sh
R='s/([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}/<mac>/g'
# Android's GATT clients: one entry, owned by sh.edm.s1kiosk, while the S1 held the link; none after the close
$ADB shell dumpsys bluetooth_manager | sed -n '/GATT Client Map/,/GATT Server Map/p' | sed -E "$R"
# Errors: no "didn't come up", "got no answer" or "connecting failed" line, and no address in any line
$ADB logcat -d | grep -E "Bluetooth proxy" | sed -E "$R"
# The S1's allocation is the garden: compare the two addresses in the shell and print only the verdict,
# with A from the S1 scanner's `allocated` in HA's Bluetooth diagnostics and B from the garden's device page
[ "$A" = "$B" ] && echo same || echo differs     # same
```

*Slots*, *free* and *allocated* are from HA's Bluetooth integration diagnostics (downloaded, read only). The times
are from HA's logbook and the entities' `last_updated`.

What it shows:
- A device whose integration holds its link works through the S1, and HA hands it to another proxy within a second when the S1 stops.
- Such a device takes one of the 3 slots for as long as HA uses it through the S1. Two of them would leave one slot for polling devices.
- Still **one link at a time**. The link was closed by turning the pane off, not by picking *Listen only*.

### Still open (untested)

- **The link limit.** Android's stack allows 7 GATT links (believed, not checked). The RTL8821CS
  may hold fewer. The engine starts at 3, like ESPHome. Only one link at a time has been tried, with
  two devices (2026-11-17).
- **Picking *Listen only* on the panel's page while a device holds its link.** The garden's link was
  closed by turning the pane off. The thermostat's closed on *Listen only* (above), but its
  integration may not hold a link between polls, so that run doesn't show the page closing a held one.
- **Handles against an ESP32 proxy.** Connect the same device through both and compare the services
  HA caches. A mismatch breaks a cache HA built from the other proxy.
- **`refresh()` on the panel.** Whether reflection reaches it with target SDK 35, and whether it
  clears anything. Needed before `CACHE_CLEARING`.
- **Scanning next to 1–3 links,** and next to the Matter pane's BLE advertising (#47).

