---
topic: android
evidence: verified
---

# Stock Wi-Fi at boot: why the Mirror's Wi-Fi switches itself off for good

**Status (2026-11-25): cause verified on a Mirror; the app's fix and Kiln's step are untested on a Mirror.** On
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

## Result

| Who | What it does now |
|---|---|
| Glassworks (core, `WifiDriverRecovery` in `KioskService`) | When Wi-Fi goes to `WIFI_STATE_UNKNOWN` (the driver failed), or reads disabled right after it, and the app may switch Wi-Fi on (Android 9 and older, or the Device Owner), it switches Wi-Fi on after 5 s and tries again with backoff for about two and a half minutes, until Wi-Fi is on. A plain switch-off is left alone |
| Kiln (`keepWifiOn` in `installer/src/inplace.ts`) | Reads `wifi_on`; when it reads `0`, writes `1` for the next start and says so. No `svc wifi enable`. This covers a switch that is already off when a boot starts: Android then never tries the driver, so there is no failure for the app to react to |

The app's recovery and Kiln's step are **untested on a Mirror** so far: the next run should reboot the Mirror a
few times after the apps are optimized and check `settings get global wifi_on` and the `Glassworks.Wifi` log
lines.

## How to reproduce

From a Mirror on stock with Glassworks installed in place, on adb. Read-only apart from the reboot.

1. Reboot two or three times after the install, so the apps are compiled and the boot is fast.
2. `adb shell settings get global wifi_on`: `0` after a lost race.
3. `adb logcat -d | grep -E "Failed to load driver|Wi-Fi state is failed|Glassworks.Wifi"` and
   `adb shell dmesg | grep -E "WCNSS driver not ready|wcnss_wlan triggered"`: the order above.
4. With the app's recovery, the `Glassworks.Wifi` lines show the tries, and Wi-Fi is on again within a minute or
   two.

## Open questions

- Whether a slower start of WifiService (or an earlier firmware trigger) could avoid the race altogether. Both live
  in stock's `/system`, which the in-place install leaves as it is apart from `services.jar`.
- What exactly the stock lululemon apps did to bring Wi-Fi back (read, not yet looked for).
