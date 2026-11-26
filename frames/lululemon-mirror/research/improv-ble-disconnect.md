---
topic: android
evidence: verified
---

# Improv over Bluetooth: why the Mirror dropped the client before it could report the Wi-Fi join

**Status (2026-11-25): working.** On Android 6 the Mirror dropped the connected Improv client about 270 ms after
the PROVISIONING state, so the Wi-Fi result never reached it. Holding back the advertisement's restart while a
client is connected fixes it (`libs/improv` 0.2.1).

Device: lululemon Studio Mirror, stock 1.24.0 with Glassworks in place (Android 6.0.1, API 23), legacy app 0.9.1.
Client: a Mac running Home Assistant's own Improv client, `py-improv-ble-client` 2.0.1 on bleak.

## Question

The maintainer set up the Mirror over Bluetooth from Home Assistant (the web flow and the iOS app). The Mirror joined
the Wi-Fi, but Home Assistant kept saying it was connecting and the flow never finished. Did the Mirror never send
the result, or did the result not get there?

## How we got here

- **The clients.** Read first, off-device: Home Assistant's web flow (`py-improv-ble-client`) waits only for the
  RPC result to the Wi-Fi command, or for an error, and has no timeout of its own. The iOS app (`improv-wifi/sdk-iOS`)
  waits for the PROVISIONED state. Both would hang if the result and the state never arrived after PROVISIONING.
- **The session.** `ImprovSession.onWifiResult` sends the result and then PROVISIONED, in ESPHome's order, so the
  state machine looked right. So the hypotheses were: the notifications are lost (too large for the MTU, a queue
  stall), or the Bluetooth link is gone by the time they are sent.
- **The Mirror's log** couldn't settle it: its 256 KB main buffer rolls over within minutes (the camera HAL and
  codec logs fill it), and adb over Wi-Fi is down while the Mirror is offline, which is exactly when Improv runs.
- **So the Mac played the phone.** It runs Home Assistant's own client over its own Bluetooth adapter and logs every
  notification with a timestamp. That reproduced the bug on the first try and timed the disconnect.
- **The 270 ms.** Between the PROVISIONING notification and the disconnect, the only Bluetooth work in
  `ImprovBleServer` is `updateAdvertisement()`. Below Android 8 there is no `AdvertisingSet`, so it stops the legacy
  advertisement and starts it again with the new state in the service data.

## What we tried

1. **Device info, read-only** (2026-11-25, verified). Device info came back whole in one 98-byte notification, so
   the MTU was negotiated and size isn't the problem.
2. **Provision from the Mac on 0.9.1** (2026-11-25, verified). Wi-Fi details redacted; the script never prints them.
   ```
   $ python provision.py <mirror> <ssid file> <password file>
   02:39:08.147 Notification: ...8001: PROVISIONING
   02:39:08.421 Execute disconnect            (the Mirror dropped the link)
   FAILED after 150.0s: UnexpectedDisconnect  (the client hung until the script's own 150 s limit)
   ```
   The Mirror's log still showed `Improv stopped: provisioned (state=PROVISIONED)` at 02:39:21, so the join worked
   and the result was produced with no client left to receive it.
3. **The same run on a build that skips the restart while a client is connected** (2026-11-25, verified;
   `test-build/improv-ble-legacy-adv`, the legacy 0.9.1 test APK, installed with `adb install -r`, Mirror rebooted
   so it came up offline):
   ```
   03:07:47.885 Notification: ...8001: PROVISIONING
   03:07:53.436 RX: WiFiSettingsRes url:'http://192.0.2.21:8099/'
   PROVISIONED after 5.7s
   ```

## Result

| Build | Link after PROVISIONING | Result at the client |
|---|---|---|
| 0.9.1 (restart on every state change) | Dropped after ~270 ms | Never; Home Assistant's client hangs |
| Restart held while a client is connected | Stays up | After 5.7 s, with the redirect URL |

Why the restart drops the link isn't proven at the stack level: Android 6's Bluedroid may tie the connection to the
advertising instance that accepted it. That is **untested**. The fix doesn't depend on it: while a client is
connected the advertisement isn't restarted, and it catches up with the current state once the last client leaves.
A refresh that fails is logged and no longer stops the session.

## How to reproduce

1. A Mirror on a build before `libs/improv` 0.2.1, offline: `adb reboot` is enough, since stock's Wi-Fi race leaves
   Wi-Fi off after boot. Improv opens 30 s after boot, authorized for 5 minutes, because the Mirror has no touch.
2. On a Mac with Bluetooth, `py-improv-ble-client` 2.0.1 in a venv: scan for the Improv service, then
   `ensure_connected()`, `need_authorization()` and `provision(ssid, password, None)`, exactly as Home Assistant's
   flow does. This writes the Wi-Fi settings to the Mirror and nothing else; the Mirror ends up on that network.
3. Watch for PROVISIONING and then a disconnect within a second, with no `WiFiSettingsRes`.

## Open questions

- The stack-level reason for the disconnect (the advertiser's client interface holding the link?), if it ever
  matters for another feature.
- `py-improv-ble-client` 2.0.1 didn't fail on the unexpected disconnect; it raised only when the caller's own
  timeout cancelled it. That is why Home Assistant's flow shows "connecting" forever instead of an error. That's an
  upstream issue, not reported yet.
