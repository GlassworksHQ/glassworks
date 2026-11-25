---
topic: display
evidence: verified
---

# Display sleep: why the Mirror's screen comes back with lines, and how to sleep without them

**Status (2026-11-25): working, app path untested on a Mirror.** Android's own screen off (`lockNow()`, the
power manager's sleep, `KEYCODE_SLEEP`) leaves the Mirror's display with lines across it until the power is cut.
Turning only the backlight off, with Android's display left on, is pitch black and wakes clean (verified, 3 of 3
cycles). Glassworks now sleeps that way on the Mirror (`SleepMode.BACKLIGHT_OFF`); the app's window-brightness
path still needs a run on a real Mirror.

Device: lululemon Studio Mirror, original Qualcomm MSM8916 board, lululemon's signed boot image
(`androidboot.authorized_kernel=true`), on both stock software and the Glass Panes community `system`.
Tools: adb, a decompiler for `com.mirror.services`, the stock kernel's sources and config as published by the
community (see the frame README's credits).

## Question

Glassworks turns a panel's screen off when it's idle, and when Home Assistant's *Screen* switch says so. On every
other frame that's Android's screen off: `DevicePolicyManager.lockNow()` with device admin, else the system
timeout. On the Mirror, the screen came back from that with horizontal lines across the whole 40" panel, and only
a full power cut cleared them. A wall display that can't sleep and wake is not a panel, so we needed either a
fix for the display path or a way to sleep that never takes it.

## How we got here

The display path is longer than on a tablet. The MSM8916 drives MIPI DSI into a **Toshiba TC358775** DSI-to-LVDS
bridge, which drives the 40" LVDS panel; the backlight is a separate PWM LED (`lcd-backlight`). The kernel is
lululemon's and it's signed: every Mirror keeps that boot image whatever `system` it runs
([mirror-root.md](mirror-root.md), [mirror-android.md](mirror-android.md)). So anything in the kernel's bridge
driver is fixed, and a fix has to live in userspace.

The hypotheses, in the order we weighed them:

1. **The continuous splash handoff (`cont_splash`).** MDSS on this generation hands the bootloader's splash over
   to the kernel, and a botched handoff is a classic source of a corrupt LVDS picture. Ruled out: the picture is
   clean from boot through the first sleep, for as long as the device stays awake. The lines only ever follow a
   display off and on, never a cold boot. (Reasoned from the symptom; **read**.)
2. **The bridge loses sync across a power cycle it doesn't expect.** Reading the bridge driver showed it
   (below): the bridge's rails go away on display off, its reset and standby lines don't, and on the way back
   the driver only writes registers. That fits every observation, and nothing in userspace can change it.
3. **Stay out of Android's display off altogether.** If lululemon's own software never shows lines, it either
   fixed the driver (it can't have, the driver is what we read) or never turns the display off. Decompiling
   `com.mirror.services` answered it: it never does.

The insight was that "screen off" on the Mirror has never meant Android's display off. Its maker keeps the display
on, puts the backlight at 0 and draws black. Once that was clear, the fix is a sleep mode in the core that does
the same, and a live check that brightness 0 really is dark on this panel (the minimum isn't 0 on most devices).

## What we tried

1. **Read the TC358775 bridge driver in the stock kernel** (read, from the community-published kernel sources
   that match the signed image's config)
   - `mipi_d2l_probe` drives the bridge's four GPIOs high once, at probe: `lvds_panel_power_en`,
     `tc358775_stby`, `tc358775_reset`, `tc358775_disp_enable`. Nothing later pulses reset low again.
   - On display off, `mdss_dsi_panel_power_ctrl(off)` cuts the bridge's supplies, regulators `8916_l17` (3.3 V)
     and `8916_l2` (1.2 V), while reset and standby stay high.
   - On unblank, `dsi2lvds_init` writes the bridge's registers over I2C (`SYSRST`, `LVPHY0`), with no hardware
     reset pulse and no `LV_RST` sequence. The LVDS PHY and PLL come back out of sync: the lines.

   Rules out any fix from userspace inside the display-off path: the rails, the GPIOs and the init sequence all
   belong to the signed kernel. (A sysfs or debugfs route to re-pulse `tc358775_reset` wasn't found; see Open
   questions.)

2. **Reproduce it from adb** (verified, 2026-11-25, on the dev Mirror)
   ```
   $ adb shell input keyevent KEYCODE_SLEEP
   $ adb shell input keyevent KEYCODE_WAKEUP
   ```
   Lines across the panel after the wake, as with `lockNow()`. Each way into `Display.STATE_OFF` does it.

3. **Read what lululemon's software does instead** (read, decompiled `com.mirror.services` from a stock unit;
   no bytes in this repo)
   - Its `SleepTimer` calls `Mirror.toggleBacklightOff()`.
   - That keeps the display at `Display.STATE_ON`, sets `Settings.System.screen_brightness` to 0, and shows a
     full-screen black overlay. Nothing in the app enters `Display.STATE_OFF`.
   - Stock `framework-res` sets `config_screenBrightnessSettingMinimum` to 0, and the live power manager agrees:
     ```
     $ adb shell dumpsys power | grep mScreenBrightnessRangeMinimum
       mScreenBrightnessRangeMinimum=0
     ```
     So brightness 0 reaches the backlight as 0, where most devices clamp it to a dim minimum.

4. **CABL** (verified, 2026-11-25). The image runs Qualcomm's content-adaptive backlight daemon (`ppd`,
   `ro.qualcomm.cabl=2`), which scales the backlight itself and could have held it above 0. It didn't: see 5.

5. **Backlight 0 with the display on, three cycles** (verified, 2026-11-25, on the dev Mirror)
   ```
   $ adb shell cat /sys/class/leds/lcd-backlight/brightness
   200
   $ adb shell settings put system screen_brightness 0
   $ adb shell cat /sys/class/leds/lcd-backlight/brightness
   0
   $ adb shell settings put system screen_brightness 200
   $ adb shell cat /sys/class/leds/lcd-backlight/brightness
   200
   ```
   Repeated three times in a row. At 0 the 40" panel is completely black through the mirror glass; each time it
   came back with no lines. The display was never off, so the bridge never lost its rails. The setting was put
   back to the value read first.

6. **Glass Panes** (read). The community image changes `system` only and runs the same signed kernel, so its
   display path is the same and Android's display off draws the same lines there. Glassworks therefore detects
   the Mirror by its boot image (`ro.board.platform == msm8916` and `ro.bootimage.build.fingerprint` starting
   `mirror/mirror/`, `DeviceFrame.LULULEMON_MIRROR`), which matches on stock-in-place and Glass Panes alike.
   How the Glass Panes kiosk app itself sleeps wasn't checked.

## Result

| Way to sleep | Android display state | Bridge rails | After wake |
|---|---|---|---|
| `lockNow()`, `goToSleep`, `KEYCODE_SLEEP` | `STATE_OFF` | `l17`, `l2` cut, reset high | Lines until a power cut (verified) |
| Backlight 0, display on (lululemon's way) | `STATE_ON` | Kept | Clean, 3 of 3 (verified) |

What Glassworks does now: `Frame.sleepMode` is `SleepMode.LOCK` on every frame but the Mirror, whose
`DeviceFrame` says `SleepMode.BACKLIGHT_OFF`. In that mode `ScreenPolicy.sleep()` never calls `lockNow()` and keeps
`FLAG_KEEP_SCREEN_ON`. It sets the window's brightness override to 0 (`BRIGHTNESS_OVERRIDE_OFF`), and the
activity draws black over everything. *Screen* in Home Assistant reads off. Any wake (Home Assistant, presence,
voice, a notification, a timer, a key, a tap) takes the black away and puts the brightness back. The app uses
the window's brightness override, not the `screen_brightness` setting, because the app treats a change to that
setting as someone moving the system slider. Both go through the same power-manager clamp, whose minimum is 0
here, but the window path itself is **untested** on a Mirror.

## How to reproduce

From a Mirror on adb, display on. Everything is a setting or a key event; nothing writes storage beyond
`settings`.

1. Read and note the backlight: `adb shell cat /sys/class/leds/lcd-backlight/brightness`, and the setting:
   `adb shell settings get system screen_brightness`.
2. `adb shell settings put system screen_brightness 0`, read the backlight again (0), and look at the panel.
3. Put the setting back to the value from step 1 and read the backlight again.
4. Don't use `KEYCODE_SLEEP` or `lockNow()` to compare unless you can cut the Mirror's power afterwards: that's
   the only way back from the lines.

## Open questions

- A run of the app's own path on a Mirror: idle timeout, *Screen* off and on from Home Assistant, presence wake,
  three cycles, and `cat /sys/class/leds/lcd-backlight/brightness` reading 0 while asleep.
- Whether the backlight PWM at 0 also stops the LED driver's current, or only its duty cycle (power draw at
  "off").
- Whether a userspace path can pulse `tc358775_reset` (a GPIO export, a debugfs node) so Android's display off
  could work. Not needed now, and it touches the panel's power sequencing, so it should be tried only with the
  power-cut way back at hand.
- How the Glass Panes kiosk app sleeps, for the comparison.
