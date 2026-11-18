# Sensors on the Panel Hub S1 Plus

Found with `dumpsys sensorservice`, `/sys/class/input/*` and `/sys/bus/i2c/drivers/*`
(stock firmware, no root needed for any of this).

| Part | Bus / node | Android sensor | What it is |
|---|---|---|---|
| **VI5300** | I2C `1-006c` (`twi@0x05002400/vi5300@6c`), input `event2` (`ABS_DISTANCE`), misc `/dev/vi5300` | `vi5300 Proximity Sensor` (vendor "lumi"), `TYPE_PROXIMITY`, **wake-up**, max range 5000 mm | Direct time-of-flight (dToF) ranging sensor, used for presence / wake on approach. |
| **MK6212** | I2C, input `event3` | `mk6212 Ambient Light Sensor`, `TYPE_LIGHT` (lux) | Ambient light. Polled about once per second. |
| SC7A20 | I2C driver `sc7a20` | not exposed | 3-axis accelerometer (Silan). Not registered as an Android sensor. |
| SD2059 | I2C driver `sd2059` | — | Battery-backed RTC. |
| (SoC) | — | `Temperature sensor` (vendor "AW"), values around 89000 | Allwinner thermal reading, probably milli-°C. Not room temperature. |
| GT9xx | I2C, input `event5` | — | Goodix touchscreen. |

## How the proximity sensor behaves

- It's **interrupt-driven**. The Lumi HAL emits an event only when the target crosses the
  configured threshold. Nothing is emitted while the state is unchanged (`getevent` on `event2`
  stays silent).
- Value **`1.0` = something within the threshold** ("near"). Other values (seen: 13, 14) mean
  "far".
- The threshold is set from `settings secure proximity_threshold`, an index into Lumi's table
  (`com.lumi.systemmanager…screenpolicy.Monitor.mRealProximityThreshHoldDMArray`, in decimetres):

  | index | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | **8** (default) | 9 | 10 |
  |---|---|---|---|---|---|---|---|---|---|---|---|
  | range | off | 0.1 m | 0.2 m | 0.4 m | 0.6 m | 0.8 m | 1.0 m | 1.2 m | **1.5 m** | 2.0 m | 2.5 m |

  `com.lumi.systemmanager` observes the setting and programs the driver
  (`WakeupService.nativeSetProximityThreshHold(mm)`).
- The sensor/driver **wakes the screen by itself** while something is in range. In testing,
  `lockNow()` was undone within a second while the sensor read "near".
- `secure proximity_wakeup_enable` (default 1) gates Lumi's own wake-on-approach wake lock.

> On a desk (or with the back cover open) something is almost always within 1.5 m, so the
> sensor reads "near" permanently. For a wall mount, about 1.0–1.5 m works. Test on the wall.

## How the S1 Kiosk uses them

See `dev/glassworks/screen/ScreenPolicy.kt` (the policy) and `sh/edm/s1kiosk/frame/S1Sensors.kt`
(the VI5300 range table and the light sensor):

- **Wake on approach**, and the screen stays on while someone is near.
- **Idle off** after N seconds with nobody near and no touches. This replaces Aqara's DND logic,
  which is now disabled via `service call A_device_control 7 i32 0 i32 0`.
- **Auto-brightness** from lux (log curve, up to *Max auto brightness*), applied as window brightness.
- **No clock of its own.** Up to 0.4.3 the panel had a night window (22:00–07:00) with a 20 s
  timeout and a 20% brightness cap. Home Assistant decides when it is night now: the
  [Panel night mode](../../../docs/blueprints.md) blueprint switches the idle timeout and the brightness
  limit between the day and night values you configure. It changes a value only when it matches the
  other period's configured value: at night a panel still at the day value, by day one still at the
  night value. Any other value, such as 300 s set by hand when the day value is 120 s, stays as it is.
  It never restores an earlier value of its own.
- **Presence range** is configurable in metres. It needs
  `pm grant sh.edm.s1kiosk android.permission.WRITE_SECURE_SETTINGS`.
- Instant screen-off uses `DevicePolicyManager.lockNow()` if the app is a device admin
  (`dpm set-active-admin sh.edm.s1kiosk/.AdminReceiver`). Otherwise it falls back
  to the system screen-off timeout.
- The live state is exposed to the dashboard page as `window.glassworks`
  (`{presence, proximityRaw, lux, screenOn, voice}`) plus a `glassworks-state` DOM event.
  The old names, `window.s1kiosk` and `s1kiosk-state`, still work and carry the same state.

All of this is exposed to Home Assistant through the panel's ESPHome device (presence, illuminance,
screen light, idle timeout, max auto brightness, presence range); see [esphome.md](../../../docs/esphome.md).

Not used yet: the SC7A20 accelerometer (a tap/knock-to-wake idea).
