---
topic: android
evidence: verified
---

# Stock Wi-Fi at boot: why the Mirror's Wi-Fi switches itself off for good

**Status (2026-11-25): cause and the app's recovery verified on a Mirror** (three reboots, each back on Wi-Fi about
40 s after the reboot). **Untested on a Mirror:** Kiln's `keepWifiOn` step, the 0.1 brightness floor on the glass,
and the setup hotspot's wait for Wi-Fi. On
the Mirror's stock Android 6.0.1, with Glassworks installed in place, a fast boot starts Android's Wi-Fi driver
before the Wi-Fi chip's firmware is up. The driver fails to load, Android switches Wi-Fi off, and the switch stays
off across reboots. Switching Wi-Fi on a few seconds later works. Glassworks now does that after the failure, and
Kiln's install sets the switch back on instead of running `svc wifi enable`, which stock's shell isn't allowed to
do.

Device: lululemon Mirror (Qualcomm MSM8916), stock software 1.x, Glassworks' legacy build installed in place as
Device Owner. Tools: adb (Kiln's key), `dmesg`, `logcat`.

## Question

After the in-place install the Mirror came up with Wi-Fi off, again and again, with nobody switching it off. Why,
and who should turn it back on now that the stock lululemon apps are hidden?

## How we got here

The first installs never showed it: Wi-Fi was on after each of the first boots. It started once the Mirror had
been restarted a few times, which pointed at timing rather than at anything the install wrote. The first boots
after an install are slow, because Android compiles every app (dex2oat); later boots are much faster.

So we read the kernel log and logcat side by side for one bad boot. The kernel shows the Wi-Fi driver (Qualcomm's
WCNSS/Pronto) asked for before its firmware is triggered; logcat shows Android giving up on the driver at that
moment and switching Wi-Fi off. Android's own switch-off writes `Settings.Global.WIFI_ON` = 0, which it reads again
at the next boot, so one lost race keeps Wi-Fi off for good.

On stock, the lululemon apps presumably switched Wi-Fi back on; the in-place install hides them, so nothing did.
Two places could take that over: Kiln at install time (once), and the app at every boot. Kiln alone can't fix a
race that happens on later boots, and its shell turned out not to be allowed to switch Wi-Fi on at all, so the
app is the fix and Kiln only sets the switch.

## What we tried

1. **One bad boot, kernel log and logcat** (2026-11-25, verified). Times are seconds since boot, approximate:

   | ~s | Where | What |
   |---|---|---|
   | 14 | logcat | WifiService starts the Wi-Fi driver |
   | 19 | kernel | `wlan: hdd_driver_init: WCNSS driver not ready` |
   | 19 | logcat | WifiStateMachine: `Failed to load driver`; WifiService: `Wi-Fi state is failed`, then `setWifiEnabled(false)` as system |
   | 26 | kernel | `wcnss_wlan triggered by userspace` (stock's scripts start the Pronto firmware) |

   Glassworks' own log lines start about 4 s before the failure, so the app is already running when it happens.
   Afterwards `settings get global wifi_on` reads `0`, and reads `0` again after a reboot.

2. **Switching Wi-Fi on afterwards** (2026-11-25, verified). Switched on again a few seconds after the failure,
   the driver loads and Wi-Fi joins the saved network. Nothing is wrong with the driver once the firmware
   is up.

3. **`svc wifi enable` from Kiln's adb shell** (2026-11-25, verified). It doesn't work on stock:
   ```
   $ adb shell svc wifi enable
   Killed
   ```
   logcat: `SecurityException: WifiService: Neither user 2000 nor current process has
   android.permission.CHANGE_WIFI_STATE`. Stock's shell (uid 2000) lacks `CHANGE_WIFI_STATE`; the Glass Panes
   image, whose framework is rebuilt, doesn't have this problem. The stock-in-place test build's install, which
   tried it, logged `Wi-Fi: not set (Killed)`.

4. **`settings put global wifi_on 1`** (read). The shell may write global settings (`WRITE_SECURE_SETTINGS` is
   granted to shell on Android 6). WifiService reads `WIFI_ON` once, when it starts (AOSP 6.0.1
   `WifiSettingsStore`), so the write takes effect at the next boot and changes nothing before it.

5. **The first Mirror run of the recovery: it never fired** (2026-11-25, verified, test build at `dbafc237f`). Three
   reboots over Wi-Fi adb, Ethernet unplugged. Each boot lost the race (`Wi-Fi state is failed` at about 19 s).
   Restarts 1 and 2 were back on Wi-Fi about 6.5 minutes after the reboot; restart 3 only after about 35 minutes.
   The recovery should have had Wi-Fi back within a minute of boot, so something else brought it back. logcat of
   restart 3 (Mirror clock):

   | Time | Who | What |
   |---|---|---|
   | 02:12:32 | WifiService | `Wi-Fi state is failed`, then `setWifiEnabled: false` as system |
   | 02:18:02 | Glassworks (uid of the app) | `setWifiEnabled: true`: the setup hotspot's start (`WifiRadio.ensureOn` before its scan), 5.5 minutes in, not the recovery |
   | 02:18:08 | WifiService | `wifiApState=12`, then `13`: the hotspot took the radio about 4 s after switching it on, before the saved network joined (kernel: `wlan: disconnected`, the driver reloaded in AP mode) |
   | 02:33:02 | WifiService | `wifiApState=10`, `11`, and `setWifiEnabled: true` from the app: the hotspot's 15 minutes were up, and Wi-Fi joined |

   Restarts 1 and 2 came back the same way, the saved network winning the race against the hotspot. The app's own
   lines from early boot weren't there to read: the Mirror's log rings are 256 KB each, Glassworks fills the main
   one in minutes, and stock's shell can't make them bigger (`setprop persist.logd.size` does nothing).

6. **Why the recovery didn't fire** (2026-11-25, verified). One more reboot with Ethernet plugged in, so adb was up
   about 40 s into the boot and logcat streamed from there. `KioskService started` came 250 ms before
   `Failed to load driver`, so the app's receiver was registered; it logged nothing. `dumpsys activity broadcasts`
   then showed the one broadcast the failure sends:
   ```
   * Sticky action android.net.wifi.WIFI_STATE_CHANGED:
     Bundle[{previous_wifi_state=1, wifi_state=5}]
   ```
   AOSP has no Wi-Fi state 5 (0 to 4, with `WIFI_STATE_UNKNOWN` = 4 for a failed load). The vendor's WifiService
   says the failure as its own state 5, and its switch-off right after sends no broadcast, so 5 stays the sticky
   state. The recovery only knew 4. It now treats 5 as the failure too (`VENDOR_STATE_FAILED`).

7. **The fixed recovery on the Mirror** (2026-11-25, verified, test build `test-build/mirror-wifi-902-923` at
   `2ff391706`, with `37041de8c`). Three reboots over Wi-Fi adb, Ethernet unplugged. Every boot lost the race
   (`Wi-Fi state is failed` at about 19–27 s), and every boot was back on Wi-Fi adb about 40 s after the reboot
   (6.5 minutes to 35 minutes before). Restart 1, Mirror clock:

   ```
   03:33:39.015 I/Glassworks: KioskService started
   03:33:40.752 E/WifiService: Wi-Fi state is failed
   03:33:40.753 D/WifiService: setWifiEnabled: false pid=714, uid=1000
   03:33:40.914 I/Glassworks.Wifi: Wi-Fi driver failed to load: switching Wi-Fi back on in 5 s
   03:33:45.919 D/WifiService: setWifiEnabled: true pid=928, uid=10009
   03:33:45.921 I/Glassworks.Wifi: switching Wi-Fi on (try 1 of 6): asked
   03:33:58.920 I/Glassworks.Wifi: Wi-Fi is on again
   ```

   Restart 2 was the same. On restart 3 the service started after the failure, so it saw the sticky broadcast (state
   5) and switched Wi-Fi on 5 s later, as the unit test for that case says. Each time one try was enough, the sticky
   state afterwards was `wifi_state=3` (enabled), `wifi_on` read `1`, and the screen stayed on at brightness 200.

   One more thing this run showed: with Ethernet plugged in, stock's Android 6 doesn't join a saved Wi-Fi network at
   all. Wi-Fi was on and scanning every 15 s, saw the saved network, and never connected (`wpa_state=SCANNING`,
   `STA_AUTOCONNECT 0`, so joining is the framework's call). Unplugging Ethernet, it joined within seconds.

## Result

| Who | What it does now |
|---|---|
| Glassworks (core, `WifiDriverRecovery` in `KioskService`) | When Wi-Fi goes to a failed state (the Mirror's vendor state 5, or AOSP's `WIFI_STATE_UNKNOWN`), or reads disabled right after it, and the app may switch Wi-Fi on (Android 9 and older, or the Device Owner), it switches Wi-Fi on after 5 s and tries again with backoff for about two and a half minutes, until Wi-Fi is on. A plain switch-off is left alone |
| Glassworks (core, the setup hotspot) | When its rule is about to open the hotspot by itself and Wi-Fi is switched off, it switches Wi-Fi on and gives the saved network the rule's minutes again before it opens the hotspot. One failed boot no longer costs 15 minutes offline. A hotspot asked for on the setup screen doesn't wait |
| Kiln (`keepWifiOn` in `installer/src/inplace.ts`) | Reads `wifi_on`; when it reads `0`, writes `1` for the next start and says so. No `svc wifi enable`. This covers a switch that is already off when a boot starts: Android then never tries the driver, so there is no failure for the app to react to |

The first build of the recovery didn't fire on the Mirror (attempts 5 and 6). The fixed one is **verified** on the
Mirror (attempt 7). Kiln's `keepWifiOn` step is still untested on a Mirror.

## How to reproduce

From a Mirror on stock with Glassworks installed in place, on adb. It reboots the Mirror and writes one setting,
`wifi_on`, which the last step puts back.

1. Note the switch as it is: `adb shell settings get global wifi_on` (`1` on, `0` off).
2. Reboot two or three times after the install, so the apps are compiled and the boot is fast. For each boot,
   before the next one:
   1. Switch Wi-Fi back on for the next start: `adb shell settings put global wifi_on 1`. After a lost race the
      switch stays off, and a boot that starts with it off never tries the driver, so it can't fail again.
   2. `adb reboot`, then once adb is back:
      `adb shell settings get global wifi_on` (`0` after a lost race),
      `adb logcat -d | grep -E "Failed to load driver|Wi-Fi state is failed|Glassworks.Wifi"` and
      `adb shell dumpsys activity broadcasts | grep -A2 "Sticky action android.net.wifi.WIFI_STATE_CHANGED"` and
      `adb shell dmesg | grep -E "WCNSS driver not ready|wcnss_wlan triggered"`: the order above. Save them per
      boot: the next reboot clears both logs.
   3. With the app's recovery, the `Glassworks.Wifi` lines show the tries, and Wi-Fi is on again within a minute
      or two. The main log ring rolls in minutes: with Ethernet plugged in, adb is up early, and
      `adb logcat -v time > boot.log` started then keeps the early lines.
3. Put the switch back as step 1 found it: `adb shell settings put global wifi_on <value>`.

## Open questions

- Whether a slower start of WifiService (or an earlier firmware trigger) could avoid the race altogether. Both live
  in stock's `/system`, which the in-place install leaves as it is apart from `services.jar`.
- What exactly the stock lululemon apps did to bring Wi-Fi back (read, not yet looked for).
- Why stock doesn't join a saved Wi-Fi network while Ethernet is up (attempt 7): AOSP's autojoin or a lululemon
  change. A Mirror on Ethernet has no Wi-Fi to fall back on if the cable comes out until Android scans and joins again.
- The setup hotspot's wait after it switches Wi-Fi on (`SetupHotspot`'s `switchRadioOn`) is unit-tested only: with the
  recovery working, a Mirror's Wi-Fi is on long before the hotspot's rule runs.
