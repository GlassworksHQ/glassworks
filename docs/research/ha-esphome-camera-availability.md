---
topic: panes
evidence: read
---

# ESPHome camera availability: why HA showed the panel's camera as unavailable after a restart

**Status (2026-11-20): fixed in the library, panel run pending.** Home Assistant's ESPHome integration
writes an entity back from "unavailable" only when a state for that entity arrives, and a camera's only
state is an image. The panel sent images only on request, so the camera stayed unavailable until
something asked for one. `ApiServer` now sends each camera one empty image (no bytes) when a client
subscribes to states.

Device: Pixel Tablet, Glassworks for generic Android 0.2.0 test build (seen by the root, 2026-11-21).
Tools: Home Assistant core `dev` at 908a1056e3 (2026-10-27), aioesphomeapi 46.25.0 (the version HA
pins).

## Question

After the app restarts, HA shows the panel's ESPHome camera entity as unavailable. Reloading the ESPHome
entry doesn't help. One `camera_proxy` request makes it idle at once. Why, and what is the least the
panel can send to fix it without sending a picture nobody asked for?

## What we tried

1. **Read HA's ESPHome entity code** (2026-11-20, read: `homeassistant/components/esphome/entity.py`)
   - `_on_entry_data_changed` sets `_attr_available = entry_data.available`, in memory only.
   - `_on_device_update` (called on connect and on disconnect) writes the state only when the device
     went unavailable: "since `_on_state_update` will be called if the device is available when the
     full state arrives through the next entity state packet".
   - `_on_state_update` is the only other writer, and it runs when a state for the entity's key arrives.

   So the written state stays "unavailable" from the disconnect until the entity's own state arrives.
   Sensors and switches send theirs on `SubscribeStatesRequest`. A camera has none.
2. **Read HA's ESPHome camera** (2026-11-20, read: `homeassistant/components/esphome/camera.py`)
   `EsphomeCamera._on_state_update` calls the base one (which writes the state) and resolves pending
   image requests. Its state type is `CameraState`, the image bytes.
3. **Read why a reload doesn't help** (2026-11-20, read: `manager.py` `on_connect`, `entry_data.py`)
   On setup, the entities are created from the stored entity list before the device connects, so they
   are written as unavailable. `async_on_connect` sets `available = True`, and
   `async_update_device_state()` calls `_on_device_update`, which doesn't write. The same holds as for a
   restart.
4. **Read how aioesphomeapi turns camera chunks into a state** (2026-11-20, read:
   `aioesphomeapi/client_base.py`, `on_state_msg`) Every `CameraImageResponse` chunk is appended to a
   buffer per key, and `done=True` emits `CameraState(key, data=bytes(buf))`. Nothing checks the length,
   so an empty `done` chunk is a state with `data=b""`.
5. **An empty image on subscribe, against the real client** (2026-11-20, verified:
   `EspHomeActionsCameraTest` with aioesphomeapi 46.25.0) The client subscribes to states and, before
   any request, gets a `CameraState` with zero bytes. Without the change it gets nothing within 5 s.
6. **What a real ESPHome camera does** (2026-11-20, read: ESPHome `esp32_camera/__init__.py`, `CONF_IDLE_FRAMERATE`)
   `idle_framerate` (0.1 fps by default) sends a frame every 10 s to every API client unasked. That is
   why ESPHome cameras don't stay unavailable. We don't copy it: a picture would leave the panel
   without a request.

## Result

| Claim | Evidence | Confidence |
|---|---|---|
| The entity stays unavailable until its own state arrives | `entity.py` `_on_device_update`, `_on_state_update` | read |
| A camera's state is an image, and an empty `done` image is a state | `client_base.py` `on_state_msg` | read, verified with the real client |
| One empty image per camera on subscribe makes HA show it available | the two above | read; panel run pending |

The empty image carries no bytes and doesn't call the camera's frame function, so it needs no privacy
gate: it goes out in Presence only and with privacy on too, and nothing from the camera leaves the
panel. A `camera_proxy` request still gets the empty image there ("Unable to get image"), as before.

## How to reproduce

Off-device, read-only: with a Python 3.11+ venv from `libs/esphome-api/test-requirements.txt`,

```
ESPHOME_PYTHON=<venv>/bin/python ./gradlew :esphome-api:test --tests dev.glassworks.esphome.EspHomeActionsCameraTest --rerun
```

The client's output, with `on_subscribe`, is in the test report.

## Open questions

- Whether HA ever drops the `CameraState` with empty data in a later version (aioesphomeapi or the
  camera platform could start ignoring empty images). `EspHomeActionsCameraTest` would catch the first.
