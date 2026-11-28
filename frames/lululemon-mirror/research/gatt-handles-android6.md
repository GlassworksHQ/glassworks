---
topic: android
evidence: verified
---

# GATT handles on Android 6: why Home Assistant saw one service with one characteristic through the Mirror

**Status (2026-11-28): the cause is verified on a Mirror; the fix is unit-tested only.** In *Listen and connect*
Home Assistant connected to a BLE device through the Mirror and saw one service with one characteristic. On
Android 6 a GATT instance id is not a handle: it was 0 for every service and characteristic of the device
tested, so they all went to Home Assistant with the same handle. The proxy library now numbers the attributes
itself below Android 7 (#987). That fix has not run on a Mirror yet.

Device: lululemon Studio Mirror, Glass Panes community image (Android 6.0.1), Glassworks legacy build 0.9.1,
Home Assistant 2026.11.3 (aioesphomeapi, bleak-esphome). The BLE device: a WHOOP 4.0 heart-rate strap. Tools:
Home Assistant's own ESPHome client (`APIClient.bluetooth_device_connect`, `bluetooth_gatt_get_services`) from a
custom integration, and `bleak` on a Linux BlueZ adapter for the comparison. Nothing on the Mirror was changed.

## Question

The Bluetooth proxy pane's *Listen and connect* (#246) was built and verified on the S1 (Android 10). Does it
work on the Mirror, the legacy build's Android 6?

## How we got here

Home Assistant listed the Mirror as a connectable scanner with 3 slots and connected to the strap
(`connected=True mtu=247`), but `start_notify` on the heart rate characteristic failed with "Characteristic
00002a37-… was not found", with a cached service list and with a fresh discovery alike. The same strap from a
BlueZ adapter showed all of its services, so the device and the connection were fine and the list Home Assistant
got was not.

`AndroidGattRadio` sends `BluetoothGattService.instanceId` and `BluetoothGattCharacteristic.instanceId` as ATT
handles, and its comment says that holds "from Android 7 on". That pointed at the handles, so the next step was
to read what the proxy sends before Home Assistant's client builds its service collection from it.

## What we tried

1. **Home Assistant's Bluetooth client through the proxy** (2026-11-28, verified). `client.services` after
   connecting, cache cleared:
   ```
   1800: 2a00[read,write]
   ```
   One service, one characteristic. bleak keeps one attribute per handle.

2. **The same strap from a BlueZ adapter** (2026-11-28, verified). `bleak` listed the services `180a`, `180f`,
   `180d`, `181e`, `1801` and the strap's vendor service (BlueZ hides `1800`). So the strap offers them all.

3. **The proxy's raw answer** (2026-11-28, verified). `bluetooth_gatt_get_services` straight from the ESPHome
   client, written as `uuid@handle`, descriptors in braces:
   ```
   1800@0: 2a00@0[read,write] 2a01@0[read] 2a04@0[read] 2aa6@0[read]
   1801@0: 2a05@0[indicate]{2902@1}
   0001@0: 0002@0[write-no-response,write] 0003@0[notify]{2902@1} 0004@0[notify]{2902@1} 0005@0[notify]{2902@1} 0007@0[notify]{2902@1}
   181e@0: 2aa5@0[read] 2aa4@0[write]{2900@1}
   180d@0: 2a37@0[notify]{2902@1}
   180a@0: 2a29@0[read]
   180f@0: 2a19@0[read,notify]{2902@1}
   ```
   (`0001`…`0007` are the vendor service's 128-bit UUIDs, shortened.) Every service and every characteristic
   has handle 0, and every first descriptor has handle 1: `instanceId + 1`.

## Result

| Claim | Evidence |
|---|---|
| On Android 6.0.1 a service's and a characteristic's `instanceId` is 0 unless two share a UUID | Attempt 3: 7 services and 16 characteristics, all 0 |
| Home Assistant keeps one attribute per handle, so it sees one service with one characteristic | Attempt 1 |
| Inside the proxy a handle reaches only the last attribute given that number | `GattTable.characteristics[c.instanceId] = c` (`AndroidGattRadio.kt`): here handle 0 is the battery level (`2a19`), the last one, and descriptor 1 is its CCCD |
| So no client-side workaround reaches another characteristic | A read, a write or a notification for handle 0 all resolve to `2a19`; Android delivers notifications only for a characteristic it was asked to watch |

The fix (#987): below Android 7 `GattTable` numbers the attributes in the ATT table's order (a service, then each
characteristic's declaration, value and descriptors), and the callbacks find a characteristic's handle by the
object Android hands back. Android 7 and newer keep their real handles.

## How to reproduce

On a Mirror with the legacy build, turn on **Bluetooth proxy**, set **Mode** to **Listen and connect**, and
connect to any BLE device through it from Home Assistant. List `client.services`, or call
`bluetooth_gatt_get_services` on the panel's ESPHome client to see the handles.

## Open questions

- The fix on a real Mirror: connect, subscribe to a notifying characteristic, and read one.
- Android 7.0 and 7.1 (API 24 and 25), also the legacy build: taken as "instance id is the handle", as the
  comment in `AndroidGattRadio.kt` says. Not checked on a device.
- Whether Android 6 hands the callbacks the same `BluetoothGattCharacteristic` objects `getServices()` returned.
  The fix's lookup relies on it, as the descriptor lookup already did.
