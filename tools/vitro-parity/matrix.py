#!/usr/bin/env python3
"""The parity corpus's state matrix: every card Vitro draws natively, across the entity states that change its drawing.

What it touches: writes `corpus/m-*.json` and `corpus/d-*.json` in engines/engine-vitro/src/test/parity (and deletes
the ones it wrote before that it no longer makes). Reads nothing else. No network, no HA, no device.

    python3 tools/vitro-parity/matrix.py            write the generated cases
    python3 tools/vitro-parity/matrix.py --check    exit 1 when the committed cases differ from what it makes (CI)
    python3 tools/vitro-parity/matrix.py --list     print the case ids

Why it exists: hand-written cases were made for the state a fixer changed. A dimmable light that is on, a person chip,
a cover that reports no position: the states real homes are in went uncompared. Each card here is crossed with the
states its HA or Mushroom source reads (on and off, unavailable, unknown, brightness, color, color temperature,
position, percentage, unit, entity_picture, a long name), never with ones it ignores.

Two kinds of case:

- `m-<card>-<state>`: one card, one entity state, on the S1 only. The states are drawn the same on every frame, and the
  S1 is the smallest landscape frame, so it is where a cut or a misplaced control shows first.
- `d-<name>`: a whole dashboard view (sections, masonry and a dense Mushroom one) on all three frames, because layout
  bugs show up between cards.

Names, rooms and states are made up. A case's refs come from the fake HA like any other case:
`tools/vitro-parity/run.sh --refresh --cases <ids>`.
"""
import argparse
import copy
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CORPUS = os.path.normpath(os.path.join(HERE, "..", "..", "engines", "engine-vitro", "src", "test", "parity", "corpus"))
MARK = "tools/vitro-parity/matrix.py"
LONG_ROOM = "Upstairs Guest Bedroom Reading Nook"

# ---- the states

# Each domain's entity and its states. A state is (state, attributes, last_changed); `None` attributes are dropped,
# as HA leaves out an attribute it doesn't have. Every state's entity is on a device (`np: device`), so its name is
# the room's and the entity's together, as for an integration's entities.

LIGHT_COLOR = {"supported_color_modes": ["color_temp", "hs"], "min_color_temp_kelvin": 2000, "max_color_temp_kelvin": 6535,
               "min_mireds": 153, "max_mireds": 500, "supported_features": 40}
LIGHT_DIM = {"supported_color_modes": ["brightness"], "supported_features": 40}

DOMAINS = {
    "light": {
        "room": "Kitchen", "name": "Pendant", "platform": "demo",
        "states": {
            "on-dimmed": ("on", dict(LIGHT_DIM, color_mode="brightness", brightness=166), "-35m"),
            "on-low": ("on", dict(LIGHT_DIM, color_mode="brightness", brightness=13), "-35m"),
            "on-full": ("on", dict(LIGHT_DIM, color_mode="brightness", brightness=255), "-35m"),
            "on-color": ("on", dict(LIGHT_COLOR, color_mode="hs", brightness=204, hs_color=[24.0, 84.0],
                                    rgb_color=[255, 129, 40], xy_color=[0.594, 0.371], color_temp_kelvin=None), "-12m"),
            "on-ct": ("on", dict(LIGHT_COLOR, color_mode="color_temp", brightness=128, color_temp_kelvin=2702,
                                 color_temp=370, hs_color=[28.874, 72.522], rgb_color=[255, 167, 70],
                                 xy_color=[0.536, 0.389]), "-50m"),
            "on-onoff": ("on", {"supported_color_modes": ["onoff"], "color_mode": "onoff", "supported_features": 0}, "-285m"),
            "off": ("off", dict(LIGHT_COLOR, color_mode=None, brightness=None, hs_color=None, rgb_color=None,
                                xy_color=None, color_temp_kelvin=None), "-3h"),
            "unavailable": ("unavailable", {"supported_color_modes": ["brightness"], "restored": True, "supported_features": 40}, "-20m"),
            "long-name": ("on", dict(LIGHT_DIM, color_mode="brightness", brightness=166), "-35m"),
        },
    },
    "switch": {
        "room": "Patio", "name": "Heater", "platform": "demo",
        "states": {
            "on": ("on", {}, "-40m"),
            "off": ("off", {}, "-2h"),
            "outlet-on": ("on", {"device_class": "outlet"}, "-40m"),
            "unavailable": ("unavailable", {"restored": True}, "-20m"),
        },
    },
    "fan": {
        "room": "Bedroom", "name": "Ceiling Fan", "platform": "demo",
        "states": {
            "on-speed": ("on", {"percentage": 67, "percentage_step": 33.333333333333336, "preset_mode": None,
                                "preset_modes": None, "supported_features": 49}, "-25m"),
            "on-no-speed": ("on", {"supported_features": 48}, "-25m"),
            "off": ("off", {"percentage": 0, "percentage_step": 33.333333333333336, "preset_mode": None,
                            "preset_modes": None, "supported_features": 49}, "-2h"),
            "unavailable": ("unavailable", {"restored": True, "supported_features": 49}, "-20m"),
        },
    },
    "cover": {
        "room": "Office", "name": "Blind", "platform": "demo",
        "states": {
            "open-position": ("open", {"current_position": 40, "device_class": "blind", "supported_features": 15}, "-1h"),
            "closed-position": ("closed", {"current_position": 0, "device_class": "blind", "supported_features": 15}, "-5h"),
            "opening": ("opening", {"current_position": 60, "device_class": "blind", "supported_features": 15}, "-5s"),
            "open-no-position": ("open", {"device_class": "garage", "supported_features": 3}, "-1h"),
            "unknown": ("unknown", {"device_class": "garage", "supported_features": 3}, "-1h"),
            "unavailable": ("unavailable", {"device_class": "blind", "restored": True, "supported_features": 15}, "-20m"),
        },
    },
    "sensor": {
        "room": "Living Room", "name": "Temperature", "platform": "esphome", "dp": 1,
        "states": {
            "temperature": ("21.42", {"state_class": "measurement", "unit_of_measurement": "°C", "device_class": "temperature"}, "-2m"),
            "power": ("1234.5", {"state_class": "measurement", "unit_of_measurement": "W", "device_class": "power"}, "-2m"),
            "battery-low": ("12", {"state_class": "measurement", "unit_of_measurement": "%", "device_class": "battery"}, "-2h"),
            "no-unit": ("Washing", {}, "-15m"),
            "unknown": ("unknown", {"state_class": "measurement", "unit_of_measurement": "°C", "device_class": "temperature"}, "-2m"),
            "unavailable": ("unavailable", {"state_class": "measurement", "unit_of_measurement": "°C", "device_class": "temperature", "restored": True}, "-20m"),
            "long-name": ("21.42", {"state_class": "measurement", "unit_of_measurement": "°C", "device_class": "temperature"}, "-2m"),
        },
    },
    "binary_sensor": {
        "room": "Back", "name": "Door", "platform": "zha",
        "states": {
            "door-open": ("on", {"device_class": "door"}, "-4m"),
            "door-closed": ("off", {"device_class": "door"}, "-1h"),
            "motion": ("on", {"device_class": "motion"}, "-30s"),
            "unavailable": ("unavailable", {"device_class": "door", "restored": True}, "-20m"),
        },
    },
    "climate": {
        "room": "Hallway", "name": "Thermostat", "platform": "demo",
        "states": {
            "heat": ("heat", {"hvac_modes": ["off", "heat"], "min_temp": 7, "max_temp": 30, "target_temp_step": 0.5,
                              "current_temperature": 19.5, "temperature": 21, "hvac_action": "heating", "supported_features": 385}, "-3h"),
            "heat-cool": ("heat_cool", {"hvac_modes": ["off", "heat", "cool", "heat_cool"], "min_temp": 7, "max_temp": 35,
                                        "target_temp_step": 0.5, "current_temperature": 21.3, "target_temp_low": 19,
                                        "target_temp_high": 24, "hvac_action": "idle", "supported_features": 386}, "-3h"),
            "off": ("off", {"hvac_modes": ["off", "heat"], "min_temp": 7, "max_temp": 30, "target_temp_step": 0.5,
                            "current_temperature": 17.5, "temperature": None, "hvac_action": "off", "supported_features": 385}, "-6h"),
            "unavailable": ("unavailable", {"hvac_modes": ["off", "heat"], "min_temp": 7, "max_temp": 30, "restored": True,
                                            "supported_features": 385}, "-20m"),
        },
    },
    "humidifier": {
        "room": "Nursery", "name": "Humidifier", "platform": "demo",
        "states": {
            "on": ("on", {"min_humidity": 30, "max_humidity": 80, "available_modes": ["normal", "eco"], "current_humidity": 41,
                          "humidity": 45, "mode": "normal", "action": "humidifying", "device_class": "humidifier",
                          "supported_features": 1}, "-1h"),
            "off": ("off", {"min_humidity": 30, "max_humidity": 80, "available_modes": ["normal", "eco"], "current_humidity": 52,
                            "humidity": 45, "mode": "normal", "action": "off", "device_class": "humidifier",
                            "supported_features": 1}, "-4h"),
        },
    },
    "lock": {
        "room": "Front", "name": "Door Lock", "platform": "demo",
        "states": {
            "locked": ("locked", {"supported_features": 0}, "-2h"),
            "unlocked": ("unlocked", {"supported_features": 0}, "-5m"),
            "jammed": ("jammed", {"supported_features": 0}, "-5m"),
            "unavailable": ("unavailable", {"restored": True, "supported_features": 0}, "-20m"),
        },
    },
    "person": {
        "room": None, "name": "Robin", "platform": "person",
        "states": {
            "home": ("home", {"editable": True, "id": "robin", "device_trackers": [], "user_id": None}, "-3h"),
            "away": ("not_home", {"editable": True, "id": "robin", "device_trackers": [], "user_id": None}, "-3h"),
            "zone": ("Work", {"editable": True, "id": "robin", "device_trackers": [], "user_id": None}, "-3h"),
            "picture": ("home", {"editable": True, "id": "robin", "device_trackers": [], "user_id": None,
                                 "entity_picture": "/local/vitro-harness-fixtures/images/person.png"}, "-3h"),
            "unknown": ("unknown", {"editable": True, "id": "robin", "device_trackers": [], "user_id": None}, "-3h"),
        },
    },
    "media_player": {
        "room": "Den", "name": "Speaker", "platform": "demo",
        "states": {
            "playing": ("playing", {"volume_level": 0.31, "is_volume_muted": False, "media_content_type": "music",
                                    "media_duration": 245, "media_position": 92, "media_position_updated_at": "@-60s",
                                    "media_title": "Evening Tide", "media_artist": "The Placeholder Trio",
                                    "entity_picture": "/local/vitro-harness-fixtures/images/album.png",
                                    "device_class": "speaker", "supported_features": 152511}, "-4m"),
            "paused": ("paused", {"volume_level": 0.31, "is_volume_muted": False, "media_content_type": "music",
                                  "media_duration": 245, "media_position": 92, "media_position_updated_at": "@-10m",
                                  "media_title": "Evening Tide", "media_artist": "The Placeholder Trio",
                                  "entity_picture": "/local/vitro-harness-fixtures/images/album.png",
                                  "device_class": "speaker", "supported_features": 152511}, "-10m"),
            "idle": ("idle", {"volume_level": 0.31, "is_volume_muted": False, "device_class": "speaker",
                              "supported_features": 152511}, "-1h"),
            "off": ("off", {"device_class": "speaker", "supported_features": 152511}, "-5h"),
        },
    },
    "alarm_control_panel": {
        "room": "House", "name": "Alarm", "platform": "manual",
        "states": {
            "disarmed": ("disarmed", {"code_format": "number", "changed_by": None, "code_arm_required": False, "supported_features": 63}, "-2h"),
            "disarmed-no-code": ("disarmed", {"code_format": None, "changed_by": None, "code_arm_required": False, "supported_features": 63}, "-2h"),
            "armed-away": ("armed_away", {"code_format": "number", "changed_by": None, "code_arm_required": False, "supported_features": 63}, "-2h"),
            "arming": ("arming", {"code_format": "number", "changed_by": None, "code_arm_required": False, "supported_features": 63}, "-10s"),
            "triggered": ("triggered", {"code_format": "number", "changed_by": None, "code_arm_required": False, "supported_features": 63}, "-1m"),
            "unavailable": ("unavailable", {"restored": True, "supported_features": 63}, "-20m"),
        },
    },
    "vacuum": {
        "room": "Ground Floor", "name": "Robot", "platform": "demo",
        "states": {
            "cleaning": ("cleaning", {"fan_speed_list": ["silent", "standard"], "fan_speed": "standard", "battery_level": 64,
                                      "supported_features": 14204}, "-12m"),
            "docked": ("docked", {"fan_speed_list": ["silent", "standard"], "fan_speed": "standard", "battery_level": 100,
                                  "supported_features": 14204}, "-3h"),
            "returning": ("returning", {"fan_speed_list": ["silent", "standard"], "fan_speed": "standard", "battery_level": 22,
                                        "supported_features": 14204}, "-1m"),
            "error": ("error", {"fan_speed_list": ["silent", "standard"], "fan_speed": "standard", "battery_level": 51,
                                "supported_features": 14204}, "-6m"),
        },
    },
    "number": {
        "room": "Aquarium", "name": "Light Level", "platform": "demo",
        "states": {
            "value": ("40", {"min": 0, "max": 100, "step": 1, "mode": "slider", "unit_of_measurement": "%"}, "-1h"),
            "unavailable": ("unavailable", {"min": 0, "max": 100, "step": 1, "mode": "slider", "unit_of_measurement": "%", "restored": True}, "-20m"),
        },
    },
    "select": {
        "room": "Heat Pump", "name": "Mode", "platform": "demo",
        "states": {
            "option": ("Eco", {"options": ["Comfort", "Eco", "Away"]}, "-1h"),
            "unavailable": ("unavailable", {"options": ["Comfort", "Eco", "Away"], "restored": True}, "-20m"),
        },
    },
    "weather": {
        "room": None, "name": "Home", "platform": "met",
        "states": {
            "sunny": ("sunny", {"temperature": 14.2}, "-40m"),
            "rainy": ("rainy", {"temperature": 9.1}, "-40m"),
            "clear-night": ("clear-night", {"temperature": 3.4}, "-40m"),
            "unavailable": ("unavailable", {"restored": True}, "-20m"),
        },
    },
}

WEATHER_BASE = {"dew_point": 7.8, "temperature_unit": "°C", "humidity": 82, "cloud_coverage": 40.0, "uv_index": 1.2,
                "pressure": 1018.2, "pressure_unit": "hPa", "wind_bearing": 210.0, "wind_speed": 12.4,
                "wind_speed_unit": "km/h", "visibility_unit": "km", "precipitation_unit": "mm", "supported_features": 1,
                "attribution": "Weather forecast from met.no, delivered by the Norwegian Meteorological Institute."}
FORECAST = [
    {"condition": c, "datetime": f"@D+{i} 12:00", "temperature": t, "templow": lo, "precipitation": p,
     "wind_bearing": 180.0, "wind_speed": 14.0, "humidity": 70}
    for i, (c, t, lo, p) in enumerate([("partlycloudy", 14.7, 9.1, 0.4), ("rainy", 12.5, 8.7, 6.1), ("cloudy", 11.2, 6.4, 0.8),
                                        ("sunny", 13.9, 4.2, 0), ("partlycloudy", 15.3, 7.0, 0.1), ("fog", 10.8, 7.9, 0.2)])
]


def _object_id(domain, variant):
    return f"{domain}_{variant}".replace("-", "_") if domain not in ("person", "weather") else variant.replace("-", "_")


def entity(domain, variant):
    """The entity id, state and registry entry of one domain's state, as a fixture holds them."""
    d = DOMAINS[domain]
    state, attrs, changed = d["states"][variant]
    room, name = (LONG_ROOM, "Ceiling Light Over The Desk") if variant == "long-name" and domain == "light" else \
                 (LONG_ROOM, "Temperature Next To The Window") if variant == "long-name" else (d["room"], d["name"])
    eid = f"{domain}.{_object_id(domain, variant)}" if domain != "weather" else f"weather.{variant.replace('-', '_')}"
    attrs = {k: v for k, v in attrs.items() if v is not None}
    if domain == "weather" and state != "unavailable":
        attrs = dict(WEATHER_BASE, **attrs)
    attrs["friendly_name"] = f"{room} {name}" if room else name
    st = {"state": state, "last_changed": changed, "attributes": attrs}
    reg = {"ei": eid, "pl": d["platform"]}
    dev = None
    if room:
        dev = {"id": "dev-" + room.lower().replace(" ", "-"), "name": room}
        reg.update(di=dev["id"], en=name, np="device")
    if "dp" in d and domain == "sensor" and variant in ("temperature", "unknown", "unavailable", "long-name"):
        reg["dp"] = d["dp"]
    if domain == "sensor" and variant == "power":
        reg["dp"] = 0
    return eid, st, reg, dev


def fixture(pairs):
    """A fixture with the entities of [(domain, variant)], their registry entries and devices."""
    states, regs, devs = {}, [], {}
    eids = []
    for domain, variant in pairs:
        eid, st, reg, dev = entity(domain, variant)
        if eid in states:
            eids.append(eid)
            continue
        states[eid] = st
        regs.append(reg)
        if dev:
            devs[dev["id"]] = dev
        eids.append(eid)
    fx = {"states": states, "entities": regs}
    if devs:
        fx["devices"] = list(devs.values())
    persons = [e for e in states if e.startswith("person.")]
    if persons:
        fx["persons"] = [{"id": states[e]["attributes"]["id"], "name": states[e]["attributes"]["friendly_name"],
                          "user_id": None, "device_trackers": []} for e in persons[:1]]
    weathers = [e for e in states if e.startswith("weather.") and states[e]["state"] != "unavailable"]
    if weathers:
        fx["forecast"] = {e: {"daily": FORECAST} for e in weathers}
    return eids, fx


# ---- the cards

def every(domain, *skip):
    return [(domain, v) for v in DOMAINS[domain]["states"] if v not in skip]


def pick(domain, *variants):
    return [(domain, v) for v in variants]


MUSH_LIGHT = {"show_brightness_control": True, "use_light_color": True}


def _bubble(e, button_type, **options):
    """A Bubble Card button (3.4.3) of [button_type] for entity [e]."""
    return dict({"type": "custom:bubble-card", "card_type": "button", "button_type": button_type, "entity": e}, **options)

# (card id, card config for an entity id, extra options per config, the states it's crossed with). One case per
# state. The states are the ones the card's source reads: a Mushroom light reads brightness, color and its color mode;
# HA's tile reads the state, brightness, position, percentage and unit; a gauge reads only a number.
SINGLE = [
    ("tile", lambda e: {"type": "tile", "entity": e},
     every("light") + every("switch") + every("fan") + every("cover") + every("sensor") + every("binary_sensor")
     + every("lock") + every("media_player") + pick("alarm_control_panel", "disarmed", "armed-away", "triggered", "unavailable")
     + every("vacuum") + every("number") + every("select") + pick("weather", "sunny", "unavailable")),
    ("tile-vertical", lambda e: {"type": "tile", "entity": e, "vertical": True},
     pick("light", "on-dimmed", "long-name") + pick("sensor", "temperature") + pick("cover", "open-position")),
    ("button", lambda e: {"type": "button", "entity": e},
     pick("light", "on-dimmed", "on-low", "on-color", "on-ct", "off", "unavailable") + pick("switch", "on", "off")
     + pick("fan", "on-speed") + pick("cover", "open-position") + pick("lock", "locked", "unlocked")
     + pick("person", "home", "away") + pick("binary_sensor", "door-open")),
    ("button-state", lambda e: {"type": "button", "entity": e, "show_state": True},
     pick("light", "on-dimmed", "unavailable") + pick("sensor", "temperature", "unknown") + pick("cover", "open-position")),
    ("entity", lambda e: {"type": "entity", "entity": e},
     every("sensor") + pick("light", "on-color", "off", "unavailable") + pick("binary_sensor", "door-open", "unavailable")
     + pick("climate", "heat") + pick("person", "home", "zone") + pick("cover", "open-position") + pick("weather", "sunny")),
    ("gauge", lambda e: {"type": "gauge", "entity": e},
     pick("sensor", "temperature", "battery-low", "power", "unknown")),
    ("light", lambda e: {"type": "light", "entity": e}, every("light")),
    ("thermostat", lambda e: {"type": "thermostat", "entity": e}, every("climate")),
    ("humidifier", lambda e: {"type": "humidifier", "entity": e}, every("humidifier")),
    ("alarm-panel", lambda e: {"type": "alarm-panel", "entity": e}, every("alarm_control_panel")),
    ("media-control", lambda e: {"type": "media-control", "entity": e}, every("media_player")),
    ("weather-forecast", lambda e: {"type": "weather-forecast", "entity": e, "show_forecast": True, "forecast_type": "daily"},
     every("weather")),
    ("weather-current", lambda e: {"type": "weather-forecast", "entity": e, "show_forecast": False}, every("weather")),
    ("mushroom-entity", lambda e: {"type": "custom:mushroom-entity-card", "entity": e},
     pick("light", "on-color", "off") + pick("switch", "on") + pick("sensor", "temperature", "unavailable", "long-name")
     + pick("binary_sensor", "door-open") + pick("person", "home") + pick("lock", "locked")),
    ("mushroom-light", lambda e: dict({"type": "custom:mushroom-light-card", "entity": e}, **MUSH_LIGHT), every("light")),
    ("mushroom-light-collapsible", lambda e: dict({"type": "custom:mushroom-light-card", "entity": e, "collapsible_controls": True}, **MUSH_LIGHT),
     pick("light", "on-dimmed", "off")),
    ("mushroom-light-plain", lambda e: {"type": "custom:mushroom-light-card", "entity": e},
     pick("light", "on-dimmed", "on-color", "off")),
    # Layouts move the controls: the horizontal light's bar was a square (#928) while the default layout drew it right.
    ("mushroom-light-horizontal", lambda e: dict({"type": "custom:mushroom-light-card", "entity": e, "layout": "horizontal"}, **MUSH_LIGHT),
     pick("light", "on-dimmed", "on-color", "off")),
    ("mushroom-light-vertical", lambda e: {"type": "custom:mushroom-light-card", "entity": e, "layout": "vertical", "use_light_color": True},
     pick("light", "on-color", "off")),
    ("mushroom-fan-horizontal", lambda e: {"type": "custom:mushroom-fan-card", "entity": e, "layout": "horizontal",
                                           "show_percentage_control": True}, pick("fan", "on-speed", "off")),
    ("mushroom-cover-horizontal", lambda e: {"type": "custom:mushroom-cover-card", "entity": e, "layout": "horizontal",
                                             "show_position_control": True}, pick("cover", "open-position", "closed-position")),
    ("mushroom-number-horizontal", lambda e: {"type": "custom:mushroom-number-card", "entity": e, "layout": "horizontal"},
     pick("number", "value")),
    ("mushroom-entity-vertical", lambda e: {"type": "custom:mushroom-entity-card", "entity": e, "layout": "vertical"},
     pick("light", "on-color") + pick("sensor", "long-name")),
    ("mushroom-fan", lambda e: {"type": "custom:mushroom-fan-card", "entity": e, "show_percentage_control": True}, every("fan")),
    ("mushroom-cover", lambda e: {"type": "custom:mushroom-cover-card", "entity": e, "show_position_control": True,
                                  "show_buttons_control": True}, every("cover")),
    ("mushroom-climate", lambda e: {"type": "custom:mushroom-climate-card", "entity": e, "show_temperature_control": True},
     every("climate")),
    ("mushroom-alarm", lambda e: {"type": "custom:mushroom-alarm-control-panel-card", "entity": e,
                                  "states": ["armed_home", "armed_away"]},
     pick("alarm_control_panel", "disarmed", "armed-away", "arming", "triggered", "unavailable")),
    ("mushroom-vacuum", lambda e: {"type": "custom:mushroom-vacuum-card", "entity": e, "commands": ["start_pause", "return_home"]},
     every("vacuum")),
    ("mushroom-number", lambda e: {"type": "custom:mushroom-number-card", "entity": e}, every("number")),
    ("mushroom-select", lambda e: {"type": "custom:mushroom-select-card", "entity": e}, every("select")),
    # Bubble Card's buttons read the state's on-ness (its own list), a light's rgb_color, a digit or a degree unit in
    # the state (the icon color), unavailable (the card's opacity) and, for the state line, the domain's default
    # attribute. In a sections view they take Bubble's large layout.
    ("bubble-switch", lambda e: _bubble(e, "switch"),
     pick("light", "on-dimmed", "on-color", "on-ct", "off", "unavailable") + pick("switch", "on", "off")
     + pick("lock", "unlocked", "locked") + pick("sensor", "temperature") + pick("binary_sensor", "door-open")),
    ("bubble-state", lambda e: _bubble(e, "state"),
     pick("light", "on-dimmed", "off") + pick("sensor", "temperature", "power", "no-unit", "unavailable")
     + pick("cover", "open-position", "closed-position") + pick("fan", "on-speed")),
    ("bubble-switch-details", lambda e: _bubble(e, "switch", show_state=True, show_last_changed=True),
     pick("light", "on-dimmed") + pick("switch", "off")),
    # Far past one line, so the case is about the wrap and not where a line happens to break.
    ("bubble-state-wrap", lambda e: _bubble(e, "state", scrolling_effect=False,
                                            name="Upstairs Guest Bedroom Reading Nook Temperature Next To The Window By The Big Old Oak Desk"),
     pick("sensor", "long-name")),
]

# Chips: one chip per case, each chip type Vitro draws natively (entity, weather, action, spacer, template,
# conditional). Light, back, menu and alarm-control-panel chips send the card to an island (native-options-cases.json).
CHIP_ENTITY = (pick("person", "home", "away", "zone", "picture") + pick("light", "on-color", "on-dimmed", "off")
               + pick("switch", "on", "off") + pick("binary_sensor", "door-open", "door-closed")
               + pick("sensor", "temperature", "unavailable", "no-unit") + pick("lock", "locked", "unlocked")
               + pick("alarm_control_panel", "armed-away", "disarmed") + pick("climate", "heat")
               + pick("media_player", "playing") + pick("cover", "open-position") + pick("fan", "on-speed"))


def chip_cases():
    out = []
    for domain, variant in CHIP_ENTITY:
        out.append((f"mushroom-chip-entity-{domain.replace('_', '-')}-{variant}", [(domain, variant)],
                    lambda e: [{"type": "entity", "entity": e[0]}]))
    for variant in ("sunny", "rainy", "clear-night", "unavailable"):
        out.append((f"mushroom-chip-weather-{variant}", [("weather", variant)],
                    lambda e: [{"type": "weather", "entity": e[0], "show_conditions": True, "show_temperature": True}]))
    out.append(("mushroom-chip-action", [], lambda e: [{"type": "action", "icon": "mdi:home-assistant", "icon_color": "blue"}]))
    out.append(("mushroom-chip-spacer", pick("person", "home") + pick("sensor", "temperature"),
                lambda e: [{"type": "entity", "entity": e[0]}, {"type": "spacer"}, {"type": "entity", "entity": e[1]}]))
    out.append(("mushroom-chip-template-entity", pick("light", "on-dimmed"),
                lambda e: [{"type": "template", "entity": e[0], "icon": "mdi:lightbulb",
                            "icon_color": "{{ 'amber' if is_state(entity, 'on') else 'grey' }}",
                            "content": "{{ state_attr(entity, 'brightness') | int(0) * 100 // 255 }}%"}]))
    out.append(("mushroom-chip-conditional-shown", pick("person", "home") + pick("lock", "unlocked"),
                lambda e: [{"type": "conditional", "conditions": [{"condition": "state", "entity": e[1], "state": "unlocked"}],
                            "chip": {"type": "entity", "entity": e[1]}}, {"type": "entity", "entity": e[0]}]))
    out.append(("mushroom-chip-conditional-hidden", pick("person", "home") + pick("lock", "locked"),
                lambda e: [{"type": "conditional", "conditions": [{"condition": "state", "entity": e[1], "state": "unlocked"}],
                            "chip": {"type": "entity", "entity": e[1]}}, {"type": "entity", "entity": e[0]}]))
    out.append(("mushroom-chip-row", pick("person", "home") + pick("person", "away") + pick("light", "on-color")
                + pick("sensor", "temperature") + pick("weather", "sunny") + pick("lock", "locked"),
                lambda e: [{"type": "entity", "entity": x} for x in e[:2]]
                + [{"type": "entity", "entity": e[2]}, {"type": "entity", "entity": e[3]},
                   {"type": "weather", "entity": e[4], "show_conditions": True, "show_temperature": True},
                   {"type": "entity", "entity": e[5]}]))
    return out


# Cards of many entities: one case per domain, a row (or a glance item) per state. Glance sends a person or a media
# player to HA's frontend (it shows their picture), so they aren't here.
MULTI = [
    ("entities", lambda es: {"type": "entities", "entities": es},
     {"light": every("light"), "switch": every("switch"), "fan": every("fan"), "sensor": every("sensor"),
      "binary-sensor": every("binary_sensor"), "alarm": every("alarm_control_panel")}),
    ("glance", lambda es: {"type": "glance", "entities": es},
     {"light": every("light", "long-name"), "switch": every("switch"), "cover": every("cover"),
      "sensor": every("sensor", "long-name"), "binary-sensor": every("binary_sensor"), "lock": every("lock")}),
    ("toggle-group", lambda es: {"type": "toggle-group", "entities": es},
     {"mixed": pick("light", "on-dimmed", "on-color", "off", "unavailable") + pick("switch", "on", "off")}),
]


def matrix_cases():
    cases = {}

    def add(cid, title, card, pairs):
        eids, fx = fixture(pairs)
        expect = {e: fx["states"][e]["state"] for e in dict.fromkeys(eids)}
        case = {"title": title, "generated": MARK, "frames": ["s1"]}
        if expect:
            case["expect"] = expect
        case["card"] = card
        case["fixture"] = fx
        assert cid not in cases, cid
        cases[cid] = case

    for card_id, build, pairs in SINGLE:
        for domain, variant in pairs:
            eid = entity(domain, variant)[0]
            dom = domain.replace("_", "-")
            add(f"m-{card_id}-{dom}-{variant}", f"{card_id}: {domain} {variant} (state matrix)", build(eid), [(domain, variant)])
    for cid, pairs, chips in chip_cases():
        eids = [entity(d, v)[0] for d, v in pairs]
        add(f"m-{cid}", f"Mushroom chips: {cid.removeprefix('mushroom-chip-')} (state matrix)",
            {"type": "custom:mushroom-chips-card", "chips": chips(eids)}, pairs)
    for card_id, build, groups in MULTI:
        for group, pairs in groups.items():
            eids = [entity(d, v)[0] for d, v in pairs]
            add(f"m-{card_id}-{group}", f"{card_id}: every {group} state (state matrix)", build(eids), pairs)
    return cases


# ---- whole dashboards

def _t(e, **o):
    return dict({"type": "tile", "entity": e}, **o)


def dashboard_cases():
    """Three whole views, on every frame: sections (a home like the showcase's), masonry, and a dense Mushroom one."""
    out = {}

    def add(cid, title, pairs, view):
        _, fx = fixture(pairs)
        out[cid] = {"title": title, "generated": MARK, "view_config": view, "fixture": fx}

    E = lambda d, v: entity(d, v)[0]  # noqa: E731
    home = (pick("light", "on-dimmed", "on-color", "on-ct", "off", "unavailable") + pick("switch", "on", "off", "outlet-on")
            + pick("sensor", "temperature", "power", "battery-low", "no-unit") + pick("binary_sensor", "door-open", "motion")
            + pick("cover", "open-position", "closed-position") + pick("climate", "heat") + pick("lock", "locked")
            + pick("media_player", "playing") + pick("weather", "sunny") + pick("fan", "on-speed")
            + pick("alarm_control_panel", "disarmed"))
    add("d-sections-home", "Whole dashboard: a sections view of a home (state matrix)", home, {
        "title": "Home", "type": "sections", "max_columns": 4,
        "sections": [
            {"type": "grid", "cards": [
                {"type": "heading", "heading": "Living room", "heading_style": "title"},
                _t(E("light", "on-dimmed")), _t(E("light", "on-color")), _t(E("light", "off")), _t(E("switch", "on")),
                {"type": "tile", "entity": E("light", "on-ct"), "features": [{"type": "light-brightness"}], "features_position": "bottom",
                 "grid_options": {"columns": 12, "rows": 2}},
                {"type": "media-control", "entity": E("media_player", "playing")}]},
            {"type": "grid", "cards": [
                {"type": "heading", "heading": "Climate", "heading_style": "title"},
                {"type": "thermostat", "entity": E("climate", "heat")},
                _t(E("sensor", "temperature")), _t(E("fan", "on-speed")), _t(E("sensor", "battery-low"))]},
            {"type": "grid", "cards": [
                {"type": "heading", "heading": "Security", "heading_style": "title"},
                _t(E("lock", "locked")), _t(E("binary_sensor", "door-open")), _t(E("binary_sensor", "motion")),
                _t(E("cover", "open-position")), _t(E("cover", "closed-position")), _t(E("light", "unavailable")),
                {"type": "alarm-panel", "entity": E("alarm_control_panel", "disarmed")}]},
            {"type": "grid", "cards": [
                {"type": "heading", "heading": "Energy", "heading_style": "title"},
                {"type": "weather-forecast", "entity": E("weather", "sunny"), "show_forecast": True, "forecast_type": "daily"},
                {"type": "entities", "title": "Plugs", "entities": [E("switch", "outlet-on"), E("switch", "off"), E("sensor", "power"),
                                                                    E("sensor", "no-unit")]},
                {"type": "glance", "entities": [E("sensor", "temperature"), E("binary_sensor", "door-open"), E("lock", "locked")]}]},
        ]})
    add("d-masonry-home", "Whole dashboard: a masonry view of a home (state matrix)", home, {
        "title": "Home",
        "cards": [
            {"type": "entities", "title": "Lights", "entities": [E("light", "on-dimmed"), E("light", "on-color"), E("light", "off"),
                                                                  E("light", "unavailable"), E("switch", "on")]},
            {"type": "thermostat", "entity": E("climate", "heat")},
            {"type": "weather-forecast", "entity": E("weather", "sunny"), "show_forecast": True, "forecast_type": "daily"},
            {"type": "horizontal-stack", "cards": [{"type": "button", "entity": E("light", "on-color")},
                                                    {"type": "button", "entity": E("lock", "locked")},
                                                    {"type": "button", "entity": E("switch", "off")}]},
            {"type": "glance", "title": "Sensors", "entities": [E("sensor", "temperature"), E("sensor", "power"),
                                                                 E("binary_sensor", "door-open"), E("binary_sensor", "motion")]},
            {"type": "light", "entity": E("light", "on-dimmed")},
            {"type": "media-control", "entity": E("media_player", "playing")},
            {"type": "vertical-stack", "cards": [_t(E("cover", "open-position")), _t(E("cover", "closed-position")),
                                                  _t(E("fan", "on-speed"))]},
            {"type": "alarm-panel", "entity": E("alarm_control_panel", "disarmed")},
            {"type": "gauge", "entity": E("sensor", "battery-low")},
        ]})
    dense = (pick("person", "home") + pick("person", "away") + pick("weather", "sunny")
             + pick("light", "on-dimmed", "on-color", "on-ct", "off") + pick("fan", "on-speed", "off")
             + pick("cover", "open-position", "closed-position") + pick("sensor", "temperature", "power")
             + pick("climate", "heat") + pick("lock", "locked") + pick("switch", "on", "off")
             + pick("vacuum", "docked") + pick("number", "value") + pick("select", "option")
             + pick("alarm_control_panel", "disarmed-no-code") + pick("binary_sensor", "door-open"))
    M = lambda kind, e, **o: dict({"type": f"custom:mushroom-{kind}-card", "entity": e}, **o)  # noqa: E731
    add("d-mushroom-dense", "Whole dashboard: a dense Mushroom view (state matrix)", dense, {
        "title": "Home", "type": "sections", "max_columns": 4,
        "sections": [
            {"type": "grid", "cards": [
                {"type": "custom:mushroom-chips-card", "chips": [
                    {"type": "entity", "entity": E("person", "home")}, {"type": "entity", "entity": E("person", "away")},
                    {"type": "weather", "entity": E("weather", "sunny"), "show_conditions": True, "show_temperature": True},
                    {"type": "entity", "entity": E("lock", "locked")}, {"type": "entity", "entity": E("sensor", "power")}]},
                {"type": "custom:mushroom-title-card", "title": "Good evening", "subtitle": "Two lights are on"},
                M("light", E("light", "on-dimmed"), **MUSH_LIGHT), M("light", E("light", "on-color"), **MUSH_LIGHT),
                M("light", E("light", "on-ct"), use_light_color=True), M("light", E("light", "off"), **MUSH_LIGHT)]},
            {"type": "grid", "cards": [
                {"type": "custom:mushroom-title-card", "title": "Climate"},
                M("climate", E("climate", "heat"), show_temperature_control=True),
                M("fan", E("fan", "on-speed"), show_percentage_control=True), M("fan", E("fan", "off")),
                M("entity", E("sensor", "temperature")), M("entity", E("binary_sensor", "door-open"))]},
            {"type": "grid", "cards": [
                {"type": "custom:mushroom-title-card", "title": "House"},
                M("cover", E("cover", "open-position"), show_position_control=True, show_buttons_control=True),
                M("cover", E("cover", "closed-position"), show_buttons_control=True),
                M("vacuum", E("vacuum", "docked"), commands=["start_pause", "return_home"]),
                M("alarm-control-panel", E("alarm_control_panel", "disarmed-no-code"), states=["armed_home", "armed_away"]),
                M("number", E("number", "value")), M("select", E("select", "option")),
                {"type": "grid", "columns": 2, "square": False, "cards": [
                    M("entity", E("switch", "on"), layout="vertical"), M("entity", E("switch", "off"), layout="vertical")]},
                {"type": "custom:mushroom-template-card", "primary": "Front door", "secondary": "{{ states('" + E("lock", "locked") + "') | title }}",
                 "icon": "mdi:lock", "icon_color": "green", "entity": E("lock", "locked")}]},
        ]})
    bubble = (pick("light", "on-dimmed", "on-color", "off") + pick("switch", "on", "off") + pick("sensor", "temperature")
              + pick("lock", "unlocked") + pick("cover", "open-position"))
    B = _bubble  # noqa: N806
    sep = lambda name, icon: {"type": "custom:bubble-card", "card_type": "separator", "name": name, "icon": icon}  # noqa: E731
    # Bubble Card picks its layout from the view: normal (50 px buttons, 40 px separators) in a masonry view, large
    # (a 56 px row) in every other one.
    add("d-bubble-masonry", "Whole dashboard: Bubble Card buttons and separators in a masonry view (normal layout)", bubble, {
        "title": "Home",
        "cards": [
            sep("Lights", "mdi:lightbulb-group"),
            B(E("light", "on-dimmed"), "switch"), B(E("light", "on-color"), "switch", use_accent_color=True),
            B(E("light", "off"), "switch", show_state=True),
            {"type": "custom:bubble-card", "card_type": "button", "button_type": "name", "name": "Scenes", "icon": "mdi:palette"},
            sep("House", "mdi:home"),
            B(E("switch", "on"), "switch", name="Patio heater"), B(E("lock", "unlocked"), "switch"),
            B(E("sensor", "temperature"), "state"), B(E("cover", "open-position"), "state", show_icon=False),
            B(E("switch", "off"), "state", show_name=False),
        ]})
    add("d-bubble-sections", "Whole dashboard: Bubble Card buttons and separators in a sections view (large layout)", bubble, {
        "title": "Home", "type": "sections", "max_columns": 3,
        "sections": [
            {"type": "grid", "cards": [
                sep("Lights", "mdi:lightbulb-group"),
                B(E("light", "on-dimmed"), "switch"), B(E("light", "off"), "switch"),
                dict(B(E("light", "on-color"), "state"), grid_options={"columns": 6}),
                dict(B(E("switch", "on"), "switch", card_layout="normal"), grid_options={"columns": 6})]},
            {"type": "grid", "cards": [
                sep("House", ""),
                B(E("sensor", "temperature"), "state"), B(E("lock", "unlocked"), "switch"),
                dict(B(E("cover", "open-position"), "state"), grid_options={"rows": 2})]},
        ]})
    return out


def all_cases():
    out = matrix_cases()
    out.update(dashboard_cases())
    return out


def dump(case):
    return json.dumps(case, indent=1, ensure_ascii=False) + "\n"


def generated_on_disk():
    out = {}
    for f in sorted(os.listdir(CORPUS)):
        if not f.endswith(".json"):
            continue
        p = os.path.join(CORPUS, f)
        with open(p, encoding="utf-8") as fh:
            text = fh.read()
        try:
            if json.loads(text).get("generated") == MARK:
                out[f[:-5]] = text
        except ValueError:
            pass
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true", help="fail when the committed cases differ from the generated ones")
    ap.add_argument("--list", action="store_true", help="print the case ids")
    a = ap.parse_args(argv)
    cases = all_cases()
    if a.list:
        print("\n".join(cases))
        return 0
    have = generated_on_disk()
    # A hand-written case with a generated case's name: never overwritten. Checked before anything is written.
    taken = sorted(cid for cid in cases if cid not in have and os.path.exists(os.path.join(CORPUS, cid + ".json")))
    if taken:
        print(f"matrix.py: {len(taken)} generated id(s) name hand-written cases, which it won't overwrite: "
              f"{', '.join(taken)}. Rename the generated case or the hand-written one.", file=sys.stderr)
        return 1
    stale = sorted(set(have) - set(cases))
    changed = sorted(cid for cid, c in cases.items() if have.get(cid) != dump(c))
    if a.check:
        if stale or changed:
            print(f"matrix.py: {len(changed)} case(s) differ, {len(stale)} stale: run python3 tools/vitro-parity/matrix.py "
                  f"(then refresh their refs). {', '.join((changed + stale)[:12])}", file=sys.stderr)
            return 1
        print(f"matrix.py: {len(cases)} generated cases match")
        return 0
    for cid in stale:
        os.remove(os.path.join(CORPUS, cid + ".json"))
    for cid in changed:
        with open(os.path.join(CORPUS, cid + ".json"), "w", encoding="utf-8") as fh:
            fh.write(dump(cases[cid]))
    print(f"matrix.py: {len(cases)} cases ({len(changed)} written, {len(stale)} removed)")
    if stale:
        # The harness refuses an open.json entry (or a baseline.json one) for a case that no longer exists.
        print(f"matrix.py: retired {', '.join(stale)}: remove their entries from open.json and baseline.json "
              f"(and their refs) before refreshing the other cases.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
