# Sensors on the Aqara Panel Hub S1 Plus

The Aqara S1 Plus includes built-in presence and ambient light sensors that Glassworks uses to wake the screen as you approach, dim it comfortably for the room, and report occupancy and light levels to Home Assistant.

Here is the full sensor inventory discovered on stock hardware (via `dumpsys sensorservice`, `/sys/class/input/*`, and `/sys/bus/i2c/drivers/*`, with no root required):

| Component | Bus / device node | Android sensor | Purpose |
|---|---|---|---|
| **VI5300** | I2C `1-006c` (`twi@0x05002400/vi5300@6c`), input `event2` (`ABS_DISTANCE`), `/dev/vi5300` | `vi5300 Proximity Sensor` (vendor `lumi`), `TYPE_PROXIMITY`, **wake-up**, max range 5000 mm | Direct time-of-flight (dToF) ranging sensor used for presence detection and wake on approach. |
| **MK6212** | I2C, input `event3` | `mk6212 Ambient Light Sensor`, `TYPE_LIGHT` (lux) | Ambient light sensor, polled roughly once per second. |
| **SC7A20** | I2C driver `sc7a20` | Not exposed | 3-axis accelerometer (Silan), not registered as an Android sensor and not currently used by Glassworks. |
| **SD2059** | I2C driver `sd2059` | — | Battery-backed real-time clock (RTC). |
| **SoC thermal** | — | `Temperature sensor` (vendor `AW`, values around 89000) | Allwinner CPU thermal reading (likely millidegrees Celsius—not room temperature). |
| **GT9xx** | I2C, input `event5` | — | Goodix capacitive touchscreen controller. |

## How the proximity sensor behaves

- **Interrupt-driven:** The Lumi sensor HAL only emits events when an object crosses the configured distance threshold. While the state stays unchanged, `event2` stays quiet.
- **Near vs. far values:** A value of **`1.0`** means something is within range ("near"), while higher values (such as 13 or 14) indicate "far."
- **Configurable range:** The distance threshold is controlled by Android's `settings secure proximity_threshold`, which maps to an index in Lumi's lookup table (`com.lumi.systemmanager…screenpolicy.Monitor.mRealProximityThreshHoldDMArray`, in decimeters):

  | Index | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | **8** (default) | 9 | 10 |
  |---|---|---|---|---|---|---|---|---|---|---|---|
  | Range | off | 0.1 m | 0.2 m | 0.4 m | 0.6 m | 0.8 m | 1.0 m | 1.2 m | **1.5 m** | 2.0 m | 2.5 m |

  `com.lumi.systemmanager` watches this setting and reprograms the driver via `WakeupService.nativeSetProximityThreshHold(mm)`.
- **Hardware wake-up:** The driver automatically wakes the screen while someone is within range—calling `lockNow()` while the sensor reads "near" wakes the display right back up within a second.
- `secure proximity_wakeup_enable` (default `1`) controls Lumi's built-in wake-on-approach wake lock.

> [!TIP]
> When testing on a desk—or with the back cover removed—objects are almost always within 1.5 meters, so the sensor will report "near" continuously. For a typical wall mount, a range of 1.0 to 1.5 meters works well.

## How Glassworks uses them

Screen policy logic lives in `dev/glassworks/screen/ScreenPolicy.kt`, and the S1 Plus sensor bindings live in `dev/glassworks/s1/frame/S1Sensors.kt`:

- **Wake on approach:** Walking up to the panel wakes the screen automatically and keeps it awake while someone remains in front of it.
- **Idle screen-off:** Turns the display off after your configured idle timeout with no presence and no touches. This replaces Aqara's stock Do Not Disturb schedule, which the installer disables (`service call A_device_control 7 i32 0 i32 0`).
- **Auto-brightness:** Adjusts window brightness along a smooth logarithmic curve based on room lux, capped by your **Max auto brightness** setting.
- **Night dimming via Home Assistant:** Glassworks does not run a fixed internal night clock (versions up to 0.4.3 used a built-in 22:00–07:00 schedule). Instead, Home Assistant decides when it is night using the [Panel night mode](../../../docs/blueprints.md) blueprint, which switches **Idle timeout** and **Max auto brightness** between your daytime and nighttime values while preserving any manual overrides you make.
- **Adjustable presence range:** You can adjust the detection distance in meters from the panel, Manage page, or Home Assistant (requires `WRITE_SECURE_SETTINGS`, which Kiln and the CLI installer grant automatically).
- **Instant screen-off:** Uses `DevicePolicyManager.lockNow()` when Glassworks is an active device admin (`dpm set-active-admin sh.edm.s1kiosk/.AdminReceiver`); otherwise it falls back to Android's system display timeout.
- **Dashboard JavaScript API:** Live sensor state is exposed to your dashboard page on `window.glassworks` (`{presence, proximityRaw, lux, screenOn, voice}`) along with a `glassworks-state` DOM event. The legacy names `window.s1kiosk` and `s1kiosk-state` remain available with the same payload for backward compatibility.

All of these controls and readings—`Presence`, `Illuminance`, `Screen`, `Idle timeout`, `Max auto brightness`, and `Presence range`—are exposed to Home Assistant through the panel's ESPHome device. See the [ESPHome entities reference](../../../docs/esphome.md) for details.
