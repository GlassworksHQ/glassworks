#!/usr/bin/env python3
"""Vitro's visual-parity and edge-case Home Assistant (MIT): one fake Home Assistant that serves the
same complicated, real-world-style dashboards to HA's real Lovelace (the pinned frontend, from
tools/perf/hafrontend.py) and to Vitro, the native Lovelace engine, so the two render side by side.

Runs locally on 127.0.0.1 only. Read-only and in-memory: it opens no connection out (the frontend is
fetched once by hafrontend.py, as for Clarity), touches no device and no real Home Assistant, and
forgets every change when it stops. Everything in the home is made up: the rooms, the entities, the
todo items, the tokens (anyone signs in, there is nothing behind it) and the addresses (127.0.0.1).

It speaks what both clients need of HA's API: the websocket (auth, config, services, panels, states,
themes, registries, translations, icons, `subscribe_entities` with live diffs, `call_service` that
changes the in-memory home and tells every subscriber, history, statistics, `render_template`,
todo lists, weather forecasts, cameras, humidifiers, logbook events) and HTTP (auth, `/local`, camera
stills and stream, calendars, the REST states). Commands it doesn't know are answered with
`unknown_command` and printed once.

  python3 tools/vitro/showcase_ha.py --port 8091
  open http://127.0.0.1:8091/vitro-parity/sections       # HA's Lovelace, signed in on its own
  # Vitro: the same address as the server, any access token.

The dashboard `vitro-parity` has seven views: sections, room-detail (a subview), themed, hacs (Tier 2
cards: Mushroom, Bubble, mini-graph, clock-weather), panel-custom (a full-screen custom card from an
inline `data:` module), mixed-island (native cards next to a custom card served from /local) and
advanced (a floorplan, the inline tile features, a humidifier, a calendar, a logbook and a HACS-style
wrapper card that builds its inner cards with `loadCardHelpers`).

Options: --hls serves the cameras' H.264 as HLS too (needs ffmpeg with libx264, tools/perf/camera.py);
--local-dir DIR serves DIR at /local/ as well (put a HACS bundle there); --resource URL adds a
Lovelace resource (repeatable, `.css` is a stylesheet, anything else a module).
"""
import argparse
import ast
import base64
import colorsys
import copy
import datetime
import functools
import hashlib
import json
import math
import mimetypes
import os
import re
import socket
import struct
import sys
import threading
import time
import urllib.parse
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "perf"))
import camera  # noqa: E402
import hafrontend  # noqa: E402
from fakeha import render_index, ws_accept, ws_frame, ws_read  # noqa: E402

HA_VERSION = "2026.11.0"
DASHBOARD = "vitro-parity"
DASHBOARD_TITLE = "Vitro Parity"
LOCAL_FILES = {}              # "vitro-demo/energy-ring-card.js" -> (content type, bytes); filled below
START = 1_794_500_000.0       # the home's fixed epoch for every first last_changed (2026-11-13)
USER = {"id": "vitro", "name": "Vitro Showcase", "is_owner": False, "is_admin": False,
        "credentials": [{"auth_provider_type": "homeassistant", "auth_provider_id": None}], "mfa_modules": []}
MANIFEST = {"name": "Vitro Parity", "short_name": "Vitro", "start_url": f"/{DASHBOARD}/sections",
            "display": "standalone", "theme_color": "#03a9f4", "background_color": "#FFFFFF", "icons": []}


# ---- the custom cards' JavaScript (made up here; ES modules, no dependencies)

ENERGY_RING_JS = r"""// vitro-energy-ring-card: a made-up custom card for the parity showcase (Shadow DOM, SVG ring).
class VitroEnergyRingCard extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._config = null;
    this._hass = null;
  }
  setConfig(config) {
    this._config = Object.assign({ entity: "sensor.solar_output", max: 100, title: "Solar output",
      toggle_entity: "input_boolean.focus_mode" }, config || {});
    this.shadowRoot.innerHTML = `
      <style>
        :host { display: block; }
        ha-card { padding: 16px; cursor: pointer; text-align: center; }
        .title { font-size: 14px; color: var(--secondary-text-color); margin-bottom: 8px; }
        svg { width: 140px; height: 140px; transform: rotate(-90deg); }
        .track { fill: none; stroke: var(--divider-color, #ccc); stroke-width: 12; }
        .ring { fill: none; stroke: var(--primary-color, #03a9f4); stroke-width: 12; stroke-linecap: round;
          transition: stroke-dasharray 0.4s ease; }
        .value { font-size: 28px; font-weight: 500; color: var(--primary-text-color); margin-top: -92px; height: 92px;
          display: flex; flex-direction: column; justify-content: center; }
        .unit { font-size: 12px; color: var(--secondary-text-color); }
        .foot { margin-top: 8px; font-size: 12px; color: var(--secondary-text-color); }
      </style>
      <ha-card>
        <div class="title"></div>
        <svg viewBox="0 0 120 120"><circle class="track" cx="60" cy="60" r="52"></circle>
          <circle class="ring" cx="60" cy="60" r="52" stroke-dasharray="0 326.7"></circle></svg>
        <div class="value"><span class="num">--</span><span class="unit"></span></div>
        <div class="foot"></div>
      </ha-card>`;
    this.shadowRoot.querySelector("ha-card").addEventListener("click", () => this._tap());
    this._update();
  }
  set hass(hass) { this._hass = hass; this._update(); }
  getCardSize() { return 4; }
  static getStubConfig() { return { entity: "sensor.solar_output" }; }
  _tap() {
    if (!this._hass || !this._config) return;
    const [domain] = this._config.toggle_entity.split(".");
    this._hass.callService(domain, "toggle", { entity_id: this._config.toggle_entity });
  }
  _update() {
    if (!this._hass || !this._config || !this.shadowRoot.querySelector(".ring")) return;
    const st = this._hass.states[this._config.entity];
    const raw = st ? parseFloat(st.state) : NaN;
    const max = Number(this._config.max) || 100;
    const frac = isNaN(raw) ? 0 : Math.max(0, Math.min(1, raw / max));
    const circ = 2 * Math.PI * 52;
    this.shadowRoot.querySelector(".title").textContent = this._config.title;
    this.shadowRoot.querySelector(".ring").setAttribute("stroke-dasharray", `${frac * circ} ${circ}`);
    this.shadowRoot.querySelector(".num").textContent = isNaN(raw) ? "--" : String(Math.round(raw));
    this.shadowRoot.querySelector(".unit").textContent = st ? (st.attributes.unit_of_measurement || "") : "";
    const t = this._hass.states[this._config.toggle_entity];
    this.shadowRoot.querySelector(".foot").textContent = t ? `${t.attributes.friendly_name || t.entity_id}: ${t.state}` : "";
  }
}
if (!customElements.get("vitro-energy-ring-card")) customElements.define("vitro-energy-ring-card", VitroEnergyRingCard);
window.customCards = window.customCards || [];
window.customCards.push({ type: "vitro-energy-ring-card", name: "Vitro energy ring", description: "A ring gauge for the parity showcase" });
"""

PANEL_CARD_JS = r"""// vitro-panel-card: a made-up full-screen custom card for the parity showcase: room cards from
// hass.states, a toggle through hass.callService, a checklist through hass.callWS.
class VitroPanelCard extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._config = null;
    this._hass = null;
    this._todoKey = null;
    this._items = [];
  }
  setConfig(config) {
    if (!config || !Array.isArray(config.rooms)) throw new Error("rooms is required");
    this._config = config;
    this.shadowRoot.innerHTML = `
      <style>
        :host { display: block; height: 100%; box-sizing: border-box; padding: 16px;
          background: color-mix(in srgb, var(--primary-background-color, #fafafa) 92%, var(--primary-color, #03a9f4)); }
        h1 { margin: 0 0 12px; font-size: 22px; font-weight: 500; color: var(--primary-text-color); }
        .rooms { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
        .room { border-radius: 16px; padding: 12px 14px; box-shadow: 0 1px 3px rgba(0,0,0,.2);
          background: color-mix(in srgb, var(--card-background-color, #fff) 88%, var(--primary-color, #03a9f4)); }
        .room h2 { margin: 0 0 8px; font-size: 16px; font-weight: 500; color: var(--primary-text-color); }
        .light { display: flex; align-items: center; gap: 10px; padding: 8px; border-radius: 12px; cursor: pointer;
          color: var(--primary-text-color); }
        .light.on { background: color-mix(in srgb, var(--state-light-active-color, #ffc107) 30%, transparent); }
        .light.on ha-icon { color: var(--state-light-active-color, #ffc107); }
        .light .state { margin-left: auto; font-size: 12px; color: var(--secondary-text-color); }
        .todo { margin-top: 16px; }
        .todo h2 { font-size: 16px; font-weight: 500; margin: 0 0 6px; color: var(--primary-text-color); }
        .todo li { color: var(--primary-text-color); padding: 2px 0; }
        .todo li.completed { text-decoration: line-through; color: var(--secondary-text-color); }
        .todo ul { margin: 0; padding-left: 20px; }
      </style>
      <h1></h1><div class="rooms"></div>
      <div class="todo"><h2>Checklist</h2><ul></ul></div>`;
    this.shadowRoot.querySelector("h1").textContent = config.title || "Studio control";
    this._renderRooms();
  }
  set hass(hass) {
    this._hass = hass;
    this._renderRooms();
    this._loadTodo();
  }
  getCardSize() { return 10; }
  _renderRooms() {
    const box = this.shadowRoot.querySelector(".rooms");
    if (!box || !this._config) return;
    box.textContent = "";
    for (const room of this._config.rooms) {
      const el = document.createElement("div");
      el.className = "room";
      const h = document.createElement("h2");
      h.textContent = room.name;
      el.appendChild(h);
      for (const id of room.lights || []) {
        const st = this._hass && this._hass.states[id];
        const row = document.createElement("div");
        row.className = "light" + (st && st.state === "on" ? " on" : "");
        row.dataset.entity = id;
        const icon = document.createElement("ha-icon");
        icon.setAttribute("icon", "mdi:lightbulb");
        const name = document.createElement("span");
        name.textContent = st ? (st.attributes.friendly_name || id) : id;
        const state = document.createElement("span");
        state.className = "state";
        state.textContent = st ? st.state : "missing";
        row.append(icon, name, state);
        row.addEventListener("click", () => this._hass && this._hass.callService("light", "toggle", { entity_id: id }));
        el.appendChild(row);
      }
      box.appendChild(el);
    }
  }
  async _loadTodo() {
    const id = this._config && this._config.todo_entity;
    const st = id && this._hass && this._hass.states[id];
    if (!st) return;
    const key = st.state + ":" + st.last_updated;      // refetch when the list changed
    if (key === this._todoKey) return;
    this._todoKey = key;
    try {
      const res = await this._hass.callWS({ type: "todo/item/list", entity_id: id });
      this._items = res.items || [];
    } catch (e) {
      this._items = [];
    }
    const ul = this.shadowRoot.querySelector(".todo ul");
    ul.textContent = "";
    for (const item of this._items) {
      const li = document.createElement("li");
      li.className = item.status;
      li.textContent = item.summary;
      ul.appendChild(li);
    }
  }
}
if (!customElements.get("vitro-panel-card")) customElements.define("vitro-panel-card", VitroPanelCard);
window.customCards = window.customCards || [];
window.customCards.push({ type: "vitro-panel-card", name: "Vitro panel", description: "A full-screen card for the parity showcase" });
"""

WRAPPER_CARD_JS = r"""// vitro-wrapper-card: a made-up HACS-style wrapper card for the parity showcase. It builds its inner cards
// with window.loadCardHelpers(), words its labels through hass.localize and hass.formatEntityState, and
// reads HA's location from hass.callApi("GET", "config"), all inside Shadow DOM.
class VitroWrapperCard extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._config = null;
    this._hass = null;
    this._helpers = null;
    this._cards = [];
    this._location = null;
    this._locationRequested = false;
  }
  setConfig(config) {
    if (!config || !config.entity) throw new Error("entity is required");
    this._config = Object.assign({ title: "HACS wrapper" }, config);
    this._cards = [];
    this.shadowRoot.innerHTML = `
      <style>
        :host { display: block; }
        ha-card { padding: 16px; }
        .title { font-size: 16px; font-weight: 500; color: var(--primary-text-color); margin-bottom: 4px; }
        .row { display: flex; justify-content: space-between; gap: 12px; padding: 4px 0; font-size: 13px;
          color: var(--secondary-text-color); border-bottom: 1px solid var(--divider-color, rgba(127, 127, 127, 0.25)); }
        .row b { color: var(--primary-text-color); font-weight: 500; text-align: right; }
        .wrapped { display: grid; gap: 8px; margin-top: 12px; }
        .note { font-size: 12px; color: var(--secondary-text-color); }
      </style>
      <ha-card>
        <div class="title"></div>
        <div class="row"><span class="entity-label"></span><b class="entity-state">--</b></div>
        <div class="row"><span>Location</span><b class="location">...</b></div>
        <div class="wrapped"><span class="note">Loading card helpers...</span></div>
      </ha-card>`;
    this._update();
    this._loadHelpers();
  }
  set hass(hass) {
    this._hass = hass;
    for (const card of this._cards) card.hass = hass;
    this._fetchLocation();
    this._update();
  }
  getCardSize() { return 6; }
  static getStubConfig() { return { entity: "sensor.studio_power" }; }
  async _loadHelpers() {
    try {
      this._helpers = typeof window.loadCardHelpers === "function" ? await window.loadCardHelpers() : null;
    } catch (e) {
      this._helpers = null;
    }
    this._mountCards();
  }
  _mountCards() {
    const slot = this.shadowRoot.querySelector(".wrapped");
    if (!slot || !this._config) return;
    slot.textContent = "";
    this._cards = [];
    if (!this._helpers) {
      const note = document.createElement("span");
      note.className = "note";
      note.textContent = "Card helpers are not available here.";
      slot.appendChild(note);
      return;
    }
    const entity = this._config.entity;
    for (const config of [{ type: "tile", entity }, { type: "entity", entity, name: this._config.title }]) {
      const card = this._helpers.createCardElement(config);
      if (this._hass) card.hass = this._hass;
      this._cards.push(card);
      slot.appendChild(card);
    }
  }
  async _fetchLocation() {
    if (this._locationRequested || !this._hass || typeof this._hass.callApi !== "function") return;
    this._locationRequested = true;
    try {
      const cfg = await this._hass.callApi("GET", "config");
      this._location = `${cfg.location_name} (HA ${cfg.version})`;
    } catch (e) {
      this._location = "unavailable";
    }
    this._update();
  }
  _update() {
    const root = this.shadowRoot;
    if (!this._config || !root.querySelector(".title")) return;
    root.querySelector(".title").textContent = this._config.title;
    const hass = this._hass;
    const st = hass && hass.states[this._config.entity];
    const label = hass && typeof hass.localize === "function"
      ? hass.localize("ui.panel.lovelace.editor.card.generic.entity") : "";
    const name = st ? (st.attributes.friendly_name || st.entity_id) : this._config.entity;
    root.querySelector(".entity-label").textContent = `${label || "Entity"}: ${name}`;
    let shown = st ? st.state : "--";
    if (st && typeof hass.formatEntityState === "function") shown = hass.formatEntityState(st);
    root.querySelector(".entity-state").textContent = shown;
    if (this._location) root.querySelector(".location").textContent = this._location;
  }
}
if (!customElements.get("vitro-wrapper-card")) customElements.define("vitro-wrapper-card", VitroWrapperCard);
window.customCards = window.customCards || [];
window.customCards.push({ type: "vitro-wrapper-card", name: "Vitro wrapper", description: "A HACS-style wrapper card for the parity showcase" });
"""

PANEL_CARD_URL = "data:text/javascript;base64," + base64.b64encode(PANEL_CARD_JS.encode()).decode()
LOCAL_FILES["vitro-demo/energy-ring-card.js"] = ("application/javascript", ENERGY_RING_JS.encode())
LOCAL_FILES["vitro-demo/wrapper-card.js"] = ("application/javascript", WRAPPER_CARD_JS.encode())


# ---- the home

def _light(name, brightness=None, **extra):
    on = brightness is not None
    a = {"friendly_name": name, "supported_color_modes": ["color_temp", "rgb"], "color_mode": "color_temp" if on else None,
         "brightness": brightness, "color_temp_kelvin": 3000 if on else None, "min_color_temp_kelvin": 2000,
         "max_color_temp_kelvin": 6500, "rgb_color": [255, 180, 107] if on else None,
         "hs_color": [28.2, 57.9] if on else None, "xy_color": [0.5, 0.41] if on else None, "supported_features": 0}
    a.update(extra)
    return "on" if on else "off", a


def _sensor(name, value, dc, unit, **extra):
    return str(value), dict({"friendly_name": name, "device_class": dc, "unit_of_measurement": unit,
                             "state_class": "measurement"}, **extra)


def _binary(name, state, dc):
    return state, {"friendly_name": name, "device_class": dc}


F_MEDIA = 1 | 2 | 4 | 8 | 16 | 32 | 128 | 256 | 1024 | 4096 | 16384    # pause, seek, volume, prev/next, on/off, play
PLAYLIST = [("Synthetic Sunrise", "Test Pattern", 214), ("Glass Lattice", "Test Pattern", 187),
            ("Paper Moon", "Fixture Ensemble", 243)]

ENTITIES = {
    "person.alex": ("home", {"friendly_name": "Alex"}),
    "sun.sun": ("above_horizon", {"friendly_name": "Sun", "elevation": 23.4, "azimuth": 190.2, "rising": False}),
    "sensor.outdoor_temp": _sensor("Outdoor temperature", 7.4, "temperature", "°C"),
    "sensor.studio_humidity": _sensor("Studio humidity", 46, "humidity", "%"),
    "sensor.studio_power": _sensor("Studio power", 184, "power", "W"),
    "sensor.studio_co2": _sensor("Studio CO2", 612, "carbon_dioxide", "ppm"),
    "sensor.solar_output": _sensor("Solar output", 62, None, "%", icon="mdi:solar-power"),
    "sensor.energy_today": _sensor("Energy today", 4.8, "energy", "kWh", state_class="total_increasing"),
    "binary_sensor.front_door": _binary("Front door", "off", "door"),
    "binary_sensor.studio_motion": _binary("Studio motion", "on", "motion"),
    "binary_sensor.window_contact": _binary("Window", "off", "window"),
    "light.desk_lamp": _light("Desk lamp", 180),
    "light.ceiling_strip": _light("Ceiling strip", 120, color_mode="rgb"),
    "light.floor_lamp": _light("Floor lamp"),
    "light.reading_nook": _light("Reading nook", 90),
    "switch.coffee_maker": ("off", {"friendly_name": "Coffee maker", "device_class": "outlet"}),
    "switch.studio_fan_plug": ("on", {"friendly_name": "Fan plug", "device_class": "outlet"}),
    "input_boolean.focus_mode": ("off", {"friendly_name": "Focus mode", "icon": "mdi:head-snowflake"}),
    "fan.studio_fan": ("on", {"friendly_name": "Studio fan", "percentage": 40, "percentage_step": 20,
                              "preset_modes": ["auto", "sleep"], "preset_mode": None, "supported_features": 49}),
    "cover.studio_blind": ("open", {"friendly_name": "Studio blind", "device_class": "blind", "current_position": 70,
                                    "supported_features": 15}),
    "cover.garage_door": ("closed", {"friendly_name": "Garage door", "device_class": "garage", "supported_features": 3}),
    "humidifier.studio_humidifier": ("on", {
        "friendly_name": "Studio humidifier", "device_class": "humidifier", "humidity": 50, "current_humidity": 44,
        "min_humidity": 30, "max_humidity": 80, "mode": "comfort", "available_modes": ["eco", "comfort", "boost"],
        "action": "humidifying", "supported_features": 1}),
    "calendar.studio_schedule": ("off", {
        "friendly_name": "Studio schedule", "message": "Design review", "all_day": False, "start_time": "2026-11-13 10:00:00",
        "end_time": "2026-11-13 11:00:00", "location": "Studio", "description": ""}),
    "lock.front_door": ("locked", {"friendly_name": "Front door lock", "supported_features": 0}),
    "select.studio_scene": ("Focus", {"friendly_name": "Studio scene", "options": ["Focus", "Relax", "Presentation"]}),
    "input_select.studio_mode": ("Work", {"friendly_name": "Studio mode", "options": ["Work", "Play", "Away"]}),
    "climate.living_room": ("heat", {"friendly_name": "Living room", "hvac_modes": ["off", "heat", "cool", "heat_cool"],
                                     "min_temp": 7, "max_temp": 30, "target_temp_step": 0.5, "current_temperature": 19.4,
                                     "temperature": 21.5, "hvac_action": "heating", "supported_features": 385}),
    "weather.studio_forecast": ("partlycloudy", {
        "friendly_name": "Studio forecast", "temperature": 8.6, "temperature_unit": "°C", "humidity": 72,
        "pressure": 1014, "pressure_unit": "hPa", "wind_speed": 11, "wind_speed_unit": "km/h", "wind_bearing": 240,
        "visibility": 16, "visibility_unit": "km", "precipitation_unit": "mm", "attribution": "Made-up weather",
        "supported_features": 1}),
    "media_player.studio_speaker": ("playing", {
        "friendly_name": "Studio speaker", "device_class": "speaker", "volume_level": 0.4, "is_volume_muted": False,
        "media_title": PLAYLIST[0][0], "media_artist": PLAYLIST[0][1], "media_content_type": "music",
        "media_duration": PLAYLIST[0][2], "media_position": 42, "media_position_updated_at": "2026-11-13T09:00:00+00:00",
        "supported_features": F_MEDIA}),
    "alarm_control_panel.home_alarm": ("disarmed", {"friendly_name": "Home alarm", "code_arm_required": False,
                                                     "code_format": None, "changed_by": None, "supported_features": 3}),
    "todo.panel_tasks": ("3", {"friendly_name": "Panel tasks", "supported_features": 15}),
    "camera.studio_front": ("idle", {"friendly_name": "Studio front", "supported_features": 0, "access_token": "vitro",
                                     "entity_picture": "/api/camera_proxy/camera.studio_front?token=vitro"}),
    "button.restart_hub": ("unknown", {"friendly_name": "Restart hub", "device_class": "restart"}),
    "script.good_morning": ("off", {"friendly_name": "Good morning", "last_triggered": None, "mode": "single"}),
    "scene.movie_night": ("unknown", {"friendly_name": "Movie night", "icon": "mdi:movie-open"}),
}
AREAS = {"studio": ["light.desk_lamp", "light.ceiling_strip", "switch.coffee_maker", "switch.studio_fan_plug", "fan.studio_fan",
                    "cover.studio_blind", "sensor.studio_humidity", "sensor.studio_power", "sensor.studio_co2",
                    "binary_sensor.studio_motion", "camera.studio_front", "media_player.studio_speaker",
                    "humidifier.studio_humidifier", "calendar.studio_schedule"],
         "lounge": ["light.floor_lamp", "light.reading_nook", "climate.living_room", "binary_sensor.window_contact"]}
TODOS = {"todo.panel_tasks": [
    ("1", "Calibrate studio lights", "needs_action"), ("2", "Water the desk plants", "needs_action"),
    ("3", "Order spare filaments", "needs_action"), ("4", "Back up showcase config", "completed")]}


def _iso(t):
    return datetime.datetime.fromtimestamp(t, datetime.timezone.utc).isoformat()


def domain_of(entity_id):
    return entity_id.split(".", 1)[0]


# ---- the dashboard

def _badge(entity, **extra):
    return dict({"type": "entity", "entity": entity}, **extra)


def _nav(path):
    return {"action": "navigate", "navigation_path": path}


def _tile(entity, **extra):
    return dict({"type": "tile", "entity": entity}, **extra)


def _view_sections():
    return {
        "title": "Sections", "path": "sections", "type": "sections", "max_columns": 3, "icon": "mdi:view-dashboard",
        "badges": [_badge("person.alex"), _badge("sensor.outdoor_temp", show_name=True), _badge("binary_sensor.front_door")],
        "sections": [
            {"type": "grid", "cards": [
                {"type": "heading", "heading": "Controls", "heading_style": "title", "icon": "mdi:tune",
                 "badges": [_badge("sensor.studio_power")]},
                {"type": "heading", "heading": "Tap a tile to open the room, hold for more", "heading_style": "subtitle"},
                {"type": "clock", "clock_size": "medium", "show_seconds": True, "show_date": True, "time_format": "24"},
                _tile("light.desk_lamp", features=[{"type": "light-brightness"}], features_position="bottom",
                      tap_action=_nav(f"/{DASHBOARD}/room-detail"), grid_options={"columns": 6, "rows": 2}),
                _tile("select.studio_scene", features=[{"type": "select-options"}], features_position="bottom",
                      grid_options={"columns": 6, "rows": 2}),
                _tile("cover.studio_blind", features=[{"type": "cover-open-close"}], features_position="bottom",
                      grid_options={"columns": 6, "rows": 2}),
                _tile("lock.front_door", tap_action={"action": "toggle", "confirmation": {"text": "Change the front door lock?"}},
                      icon_tap_action={"action": "toggle", "confirmation": True}, grid_options={"columns": 6, "rows": 1}),
            ]},
            {"type": "grid", "cards": [
                {"type": "heading", "heading": "Climate & Media", "heading_style": "title", "icon": "mdi:home-thermometer"},
                {"type": "thermostat", "entity": "climate.living_room",
                 "features": [{"type": "climate-hvac-modes", "hvac_modes": ["off", "heat", "cool"]}]},
                {"type": "weather-forecast", "entity": "weather.studio_forecast", "forecast_type": "daily", "show_forecast": True,
                 "show_current": True},
                {"type": "gauge", "entity": "sensor.solar_output", "name": "Solar", "min": 0, "max": 100, "needle": False,
                 "severity": {"green": 50, "yellow": 20, "red": 0}},
                {"type": "media-control", "entity": "media_player.studio_speaker"},
            ]},
            {"type": "grid", "cards": [
                {"type": "heading", "heading": "Dynamic & Live", "heading_style": "title", "icon": "mdi:pulse"},
                {"type": "conditional", "conditions": [{"condition": "state", "entity": "light.desk_lamp", "state": "on"}],
                 "card": _tile("light.desk_lamp", name="Desk lamp is on", color="amber")},
                {"type": "entity-filter", "state_filter": ["on"],
                 "entities": ["light.desk_lamp", "light.ceiling_strip", "light.floor_lamp", "switch.studio_fan_plug",
                              "switch.coffee_maker"],
                 "card": {"type": "glance", "title": "Currently on", "show_state": False}},
                {"type": "markdown", "title": "Outside", "content":
                    "It is **{{ states('sensor.outdoor_temp') }} °C** outside; the desk lamp is "
                    "{{ states('light.desk_lamp') }}.\n\n{% if is_state('light.desk_lamp', 'on') %}"
                    "Brightness {{ state_attr('light.desk_lamp', 'brightness') }}/255.{% else %}Lamp off.{% endif %}"},
                {"type": "alarm-panel", "entity": "alarm_control_panel.home_alarm", "states": ["arm_home", "arm_away"]},
                {"type": "todo-list", "entity": "todo.panel_tasks", "title": "Panel tasks"},
            ]},
        ]}


def _view_room_detail():
    return {
        "title": "Room detail", "path": "room-detail", "subview": True, "back_path": f"/{DASHBOARD}/sections",
        "type": "masonry", "cards": [
            {"type": "entities", "title": "Studio", "show_header_toggle": True, "state_color": True, "entities": [
                {"entity": "light.desk_lamp", "name": "Desk lamp", "secondary_info": "last-changed"},
                {"entity": "switch.coffee_maker", "tap_action": {"action": "perform-action", "perform_action": "switch.toggle",
                                                                 "target": {"entity_id": "switch.coffee_maker"}},
                 "hold_action": {"action": "more-info"}},
                "fan.studio_fan", "cover.studio_blind", {"type": "divider"},
                {"type": "section", "label": "Sensors"},
                "sensor.studio_humidity", "sensor.studio_power", "binary_sensor.studio_motion"]},
            {"type": "glance", "title": "Glance", "columns": 5, "entities": [
                "light.desk_lamp", "switch.studio_fan_plug", "cover.studio_blind", "sensor.studio_co2",
                "binary_sensor.window_contact"]},
            {"type": "sensor", "entity": "sensor.studio_humidity", "graph": "line", "hours_to_show": 24, "detail": 2,
             "name": "Humidity"},
            {"type": "history-graph", "title": "Studio, last 24 hours", "hours_to_show": 24,
             "entities": ["sensor.studio_humidity", "sensor.studio_power", "sensor.studio_co2"]},
            {"type": "picture-entity", "entity": "camera.studio_front", "camera_view": "live", "show_name": False,
             "show_state": False},
            {"type": "picture-entity", "entity": "camera.studio_front", "camera_image": "camera.studio_front",
             "show_name": True, "show_state": True, "name": "Studio, front"},
            {"type": "horizontal-stack", "cards": [
                {"type": "button", "entity": "scene.movie_night", "name": "Movie", "show_state": False,
                 "tap_action": {"action": "perform-action", "perform_action": "scene.turn_on",
                                "target": {"entity_id": "scene.movie_night"}}},
                {"type": "button", "entity": "script.good_morning", "name": "Morning", "icon": "mdi:weather-sunset-up",
                 "tap_action": {"action": "perform-action", "perform_action": "script.turn_on",
                                "target": {"entity_id": "script.good_morning"}}},
                {"type": "button", "entity": "light.desk_lamp", "name": "Lamp", "tap_action": {"action": "toggle"}}]},
        ]}


def _view_themed():
    return {
        "title": "Themed", "path": "themed", "theme": "glass_dusk", "type": "sections", "max_columns": 2, "icon": "mdi:palette",
        "sections": [
            {"type": "grid", "cards": [
                {"type": "heading", "heading": "Glass dusk", "heading_style": "title", "icon": "mdi:weather-night"},
                _tile("light.ceiling_strip", features=[{"type": "light-brightness"}]),
                {"type": "entities", "title": "Translucent rows", "entities": [
                    "light.reading_nook", "switch.studio_fan_plug", "sensor.studio_power", "lock.front_door"]},
                {"type": "gauge", "entity": "sensor.solar_output", "name": "Solar", "min": 0, "max": 100}]},
            {"type": "grid", "cards": [
                {"type": "heading", "heading": "Overrides", "heading_style": "title", "icon": "mdi:format-paint"},
                dict(_tile("light.desk_lamp", name="Solar paper tile", features=[{"type": "light-brightness"}]),
                     theme="solar_paper"),
                dict({"type": "entities", "title": "Card mod", "entities": ["sensor.studio_humidity", "sensor.studio_co2"]},
                     card_mod={"style": "ha-card { opacity: 0.85; border: 1px solid rgba(255, 255, 255, 0.35); }"}),
                {"type": "thermostat", "entity": "climate.living_room"}]},
        ]}


def _view_hacs():
    return {
        "title": "HACS", "path": "hacs", "type": "sections", "max_columns": 3, "icon": "mdi:puzzle", "sections": [
            {"type": "grid", "cards": [
                {"type": "custom:mushroom-title-card", "title": "Studio", "subtitle": "Mushroom cards"},
                {"type": "custom:mushroom-chips-card", "alignment": "center", "chips": [
                    {"type": "entity", "entity": "light.desk_lamp", "use_entity_picture": False},
                    {"type": "weather", "entity": "weather.studio_forecast", "show_conditions": True, "show_temperature": True},
                    {"type": "template", "icon": "mdi:thermometer", "content": "{{ states('sensor.outdoor_temp') }} °C"}]},
                {"type": "custom:mushroom-light-card", "entity": "light.desk_lamp", "show_brightness_control": True,
                 "show_color_temp_control": True, "use_light_color": True, "collapsible_controls": True},
                {"type": "custom:mushroom-entity-card", "entity": "sensor.studio_power", "name": "Power", "icon_color": "amber"}]},
            {"type": "grid", "cards": [
                {"type": "custom:mushroom-climate-card", "entity": "climate.living_room",
                 "hvac_modes": ["off", "heat", "cool"], "show_temperature_control": True, "collapsible_controls": False},
                {"type": "custom:mushroom-template-card",
                 "primary": "{{ states('sensor.studio_co2') }} ppm",
                 "secondary": "{% if states('sensor.studio_co2') | int > 800 %}Open a window{% else %}Air is fine{% endif %}",
                 "icon": "mdi:molecule-co2",
                 "icon_color": "{% if states('sensor.studio_co2') | int > 800 %}red{% else %}green{% endif %}"},
                {"type": "custom:bubble-card", "card_type": "separator", "name": "Lights", "icon": "mdi:lightbulb-group"},
                {"type": "custom:bubble-card", "card_type": "button", "entity": "light.ceiling_strip", "button_type": "switch",
                 "show_state": True, "show_attribute": True, "attribute": "brightness"}]},
            {"type": "grid", "cards": [
                {"type": "custom:mini-graph-card", "name": "Humidity", "entities": ["sensor.studio_humidity"],
                 "hours_to_show": 24, "points_per_hour": 2, "line_width": 3, "animate": True,
                 "show": {"fill": "fade", "extrema": True, "labels": False}},
                {"type": "custom:clock-weather-card", "entity": "weather.studio_forecast", "sun_entity": "sun.sun",
                 "temperature_sensor": "sensor.outdoor_temp", "forecast_rows": 5, "hide_today_section": False}]},
        ]}


def _view_panel_custom():
    return {
        "title": "Custom panel", "path": "panel-custom", "type": "panel", "icon": "mdi:application", "cards": [
            {"type": "custom:vitro-panel-card", "title": "Studio control", "todo_entity": "todo.panel_tasks", "rooms": [
                {"name": "Studio", "lights": ["light.desk_lamp", "light.ceiling_strip"]},
                {"name": "Lounge", "lights": ["light.floor_lamp", "light.reading_nook"]}]}]}


def _view_mixed_island():
    return {
        "title": "Mixed island", "path": "mixed-island", "type": "sections", "max_columns": 2, "icon": "mdi:island",
        "sections": [
            {"type": "grid", "cards": [
                {"type": "heading", "heading": "Native next to custom", "heading_style": "title", "icon": "mdi:vector-combine"},
                _tile("sensor.solar_output", name="Solar output", grid_options={"columns": 6}),
                _tile("input_boolean.focus_mode", grid_options={"columns": 6}),
                {"type": "entities", "title": "Native rows", "entities": ["sensor.studio_power", "sensor.energy_today",
                                                                         "input_boolean.focus_mode"]}]},
            {"type": "grid", "cards": [
                {"type": "heading", "heading": "Custom island", "heading_style": "title", "icon": "mdi:circle-slice-8"},
                {"type": "custom:vitro-energy-ring-card", "entity": "sensor.solar_output", "max": 100, "title": "Solar output",
                 "toggle_entity": "input_boolean.focus_mode"},
                _tile("light.desk_lamp", features=[{"type": "light-brightness"}]),
                {"type": "gauge", "entity": "sensor.solar_output", "min": 0, "max": 100}]},
        ]}


def _view_advanced():
    half = {"columns": 6}
    return {
        "title": "Advanced", "path": "advanced", "type": "sections", "max_columns": 3, "icon": "mdi:layers-triple", "sections": [
            {"type": "grid", "cards": [
                {"type": "heading", "heading": "Floorplan & Environment", "heading_style": "title", "icon": "mdi:floor-plan"},
                {"type": "picture-elements", "image": "/local/vitro-demo/floorplan.png", "elements": [
                    {"type": "state-icon", "entity": "light.desk_lamp", "tap_action": {"action": "toggle"},
                     "style": {"left": "30%", "top": "40%"}},
                    {"type": "state-icon", "entity": "binary_sensor.front_door", "style": {"left": "80%", "top": "25%"}},
                    {"type": "state-label", "entity": "sensor.studio_power", "style": {"left": "55%", "top": "70%"}},
                    {"type": "state-badge", "entity": "sensor.outdoor_temp", "style": {"left": "20%", "top": "75%"}}]},
                {"type": "humidifier", "entity": "humidifier.studio_humidifier"}]},
            {"type": "grid", "cards": [
                {"type": "heading", "heading": "Inline Tile Features", "heading_style": "title", "icon": "mdi:tune-variant"},
                _tile("climate.living_room", grid_options=half, features_position="bottom",
                      features=[{"type": "climate-hvac-modes", "hvac_modes": ["off", "heat", "cool"]},
                                {"type": "target-temperature"}]),
                _tile("fan.studio_fan", grid_options=half, features_position="bottom", features=[{"type": "fan-speed"}]),
                _tile("cover.studio_blind", grid_options=half, features_position="bottom", features=[{"type": "cover-position"}]),
                _tile("light.ceiling_strip", grid_options=half, features_position="bottom", features=[{"type": "light-color-temp"}]),
                _tile("media_player.studio_speaker", grid_options=half, features_position="bottom",
                      features=[{"type": "media-player-volume-slider"}])]},
            {"type": "grid", "cards": [
                {"type": "heading", "heading": "Agenda, Activity & Wrapper Card", "heading_style": "title", "icon": "mdi:calendar-clock"},
                {"type": "calendar", "entities": ["calendar.studio_schedule"]},
                {"type": "logbook", "entities": ["light.desk_lamp", "switch.studio_fan_plug", "cover.studio_blind"], "hours_to_show": 24},
                {"type": "custom:vitro-wrapper-card", "entity": "sensor.studio_power", "title": "HACS Wrapper Test"}]},
        ]}


VIEWS = ("sections", "room-detail", "themed", "hacs", "panel-custom", "mixed-island", "advanced")


def lovelace_config():
    return {"title": DASHBOARD_TITLE, "views": [_view_sections(), _view_room_detail(), _view_themed(), _view_hacs(),
                                                _view_panel_custom(), _view_mixed_island(), _view_advanced()]}


# ---- themes: what frontend/get_themes sends (a theme's keys are CSS variables without their --)

def themes():
    glass_common = {
        "primary-color": "#8ab4ff", "accent-color": "#ff9ec4", "ha-card-border-radius": "20px",
        "ha-card-border-width": "1px", "ha-card-box-shadow": "0 8px 32px rgba(8, 10, 30, 0.35)",
        # a 4-hop var() chain: card-background-color -> ha-card-background -> glass-card -> glass-surface -> rgba()
        "glass-surface": "rgba(255, 255, 255, 0.10)",
        "glass-card": "var(--glass-surface)",
        "glass-card-tinted": "var(--glass-card)",
        "ha-card-background": "var(--glass-card-tinted)",
        "card-background-color": "var(--ha-card-background)",
        "ha-card-border-color": "rgba(255, 255, 255, 0.22)",
        "lovelace-background": "linear-gradient(160deg, #1b1f3b 0%, #3a2a5e 55%, #0f2a3f 100%)",
        "primary-background-color": "#1b1f3b", "secondary-background-color": "rgba(255, 255, 255, 0.06)",
        "primary-text-color": "#f4f1ff", "secondary-text-color": "rgba(244, 241, 255, 0.7)",
        "divider-color": "rgba(255, 255, 255, 0.16)", "state-icon-active-color": "#ffd98a",
    }
    solar = {
        "primary-color": "#c77700", "accent-color": "#e0a030", "ha-card-border-radius": "8px", "ha-card-border-width": "1px",
        "ha-card-border-color": "#e8d9b5", "ha-card-box-shadow": "0 1px 2px rgba(80, 55, 10, 0.12)",
        "primary-background-color": "#f6efe0", "secondary-background-color": "#efe5cc",
        "card-background-color": "#fffaf0", "ha-card-background": "#fffaf0",
        "primary-text-color": "#3b2f1e", "secondary-text-color": "#7a6a4f", "divider-color": "#e8d9b5",
        "text-primary-color": "#fffaf0", "state-icon-active-color": "#c77700",
    }
    return {
        "default_theme": "default", "default_dark_theme": None, "darkMode": False, "theme": "default",
        "themes": {
            "glass_dusk": dict(glass_common, modes={
                "light": {"glass-surface": "rgba(255, 255, 255, 0.45)", "primary-text-color": "#241f3d",
                          "secondary-text-color": "rgba(36, 31, 61, 0.7)", "ha-card-border-color": "rgba(255, 255, 255, 0.6)",
                          "lovelace-background": "linear-gradient(160deg, #cfd8ff 0%, #e6d4f5 55%, #cfe9f2 100%)",
                          "primary-background-color": "#cfd8ff"},
                "dark": {"glass-surface": "rgba(255, 255, 255, 0.10)"}}),
            "solar_paper": solar,
        }}


# ---- translations and icons (HA core's entity_component words, for the domains in this home)

STATE_WORDS = {
    "light._": {"on": "On", "off": "Off"}, "switch._": {"on": "On", "off": "Off"}, "input_boolean._": {"on": "On", "off": "Off"},
    "fan._": {"on": "On", "off": "Off"}, "lock._": {"locked": "Locked", "unlocked": "Unlocked", "open": "Open"},
    "climate._": {"off": "Off", "heat": "Heat", "cool": "Cool", "heat_cool": "Heat/Cool"},
    "media_player._": {"playing": "Playing", "paused": "Paused", "off": "Off", "idle": "Idle"},
    "cover._": {"open": "Open", "closed": "Closed", "opening": "Opening", "closing": "Closing"},
    "camera._": {"idle": "Idle", "streaming": "Streaming"}, "person._": {"home": "Home", "not_home": "Away"},
    "binary_sensor.door": {"on": "Open", "off": "Closed"}, "binary_sensor.window": {"on": "Open", "off": "Closed"},
    "binary_sensor.motion": {"on": "Detected", "off": "Clear"},
    "weather._": {"partlycloudy": "Partly cloudy", "sunny": "Sunny", "cloudy": "Cloudy", "rainy": "Rainy"},
    "alarm_control_panel._": {"disarmed": "Disarmed", "armed_home": "Armed home", "armed_away": "Armed away",
                              "armed_night": "Armed night"},
    "sun._": {"above_horizon": "Above horizon", "below_horizon": "Below horizon"},
    "humidifier._": {"on": "On", "off": "Off"}, "calendar._": {"on": "On", "off": "Off"},
}
ATTR_WORDS = {"climate._.state_attributes.hvac_action.state": {"heating": "Heating", "cooling": "Cooling", "idle": "Idle",
                                                              "off": "Off"},
              "humidifier._.state_attributes.action.state": {"humidifying": "Humidifying", "drying": "Drying", "idle": "Idle",
                                                             "off": "Off"},
              "humidifier._.state_attributes.mode.state": {"eco": "Eco", "comfort": "Comfort", "boost": "Boost"}}


def translations(category):
    if category != "entity_component":
        return {}
    out = {}
    for key, names in STATE_WORDS.items():
        domain, dc = key.split(".")
        for state, text in names.items():
            out[f"component.{domain}.entity_component.{dc}.state.{state}"] = text
    for key, names in ATTR_WORDS.items():
        domain, rest = key.split(".", 1)
        for state, text in names.items():
            out[f"component.{domain}.entity_component.{rest}.{state}"] = text
    return out


ICONS = {
    "lock": {"_": {"default": "mdi:lock", "state": {"unlocked": "mdi:lock-open-variant", "open": "mdi:lock-open-variant"}}},
    "binary_sensor": {"_": {"default": "mdi:radiobox-blank", "state": {"on": "mdi:checkbox-marked-circle"}},
                      "door": {"default": "mdi:door-closed", "state": {"on": "mdi:door-open"}},
                      "window": {"default": "mdi:window-closed", "state": {"on": "mdi:window-open"}},
                      "motion": {"default": "mdi:motion-sensor-off", "state": {"on": "mdi:motion-sensor"}}},
    "sensor": {"_": {"default": "mdi:eye"}, "humidity": {"default": "mdi:water-percent"}, "power": {"default": "mdi:flash"},
               "energy": {"default": "mdi:lightning-bolt"}, "temperature": {"default": "mdi:thermometer"},
               "carbon_dioxide": {"default": "mdi:molecule-co2"}},
    "switch": {"_": {"default": "mdi:toggle-switch-variant", "state": {"off": "mdi:toggle-switch-variant-off"}},
               "outlet": {"default": "mdi:power-plug", "state": {"off": "mdi:power-plug-off"}}},
    "media_player": {"_": {"default": "mdi:cast", "state": {"playing": "mdi:cast-connected", "off": "mdi:cast-off"}},
                     "speaker": {"default": "mdi:speaker", "state": {"playing": "mdi:speaker-play", "off": "mdi:speaker-off"}}},
    "cover": {"_": {"default": "mdi:window-open", "state": {"closed": "mdi:window-closed"}},
              "garage": {"default": "mdi:garage", "state": {"open": "mdi:garage-open"}},
              "blind": {"default": "mdi:blinds-horizontal", "state": {"closed": "mdi:blinds-horizontal-closed"}}},
    "climate": {"_": {"default": "mdi:thermostat"}}, "camera": {"_": {"default": "mdi:video", "state": {"off": "mdi:video-off"}}},
    "person": {"_": {"default": "mdi:account", "state": {"not_home": "mdi:account-arrow-right"}}},
    "weather": {"_": {"default": "mdi:weather-partly-cloudy"}},
    "alarm_control_panel": {"_": {"default": "mdi:shield", "state": {"armed_home": "mdi:shield-home",
                                                                     "armed_away": "mdi:shield-lock", "disarmed": "mdi:shield-off"}}},
    "fan": {"_": {"default": "mdi:fan", "state": {"off": "mdi:fan-off"}}}, "todo": {"_": {"default": "mdi:clipboard-list"}},
    "scene": {"_": {"default": "mdi:palette"}}, "script": {"_": {"default": "mdi:script-text"}},
    "button": {"_": {"default": "mdi:gesture-tap-button"}, "restart": {"default": "mdi:restart"}},
    "light": {"_": {"default": "mdi:lightbulb", "state": {"off": "mdi:lightbulb-off"}}},
    "select": {"_": {"default": "mdi:format-list-bulleted"}}, "sun": {"_": {"default": "mdi:white-balance-sunny"}},
    "humidifier": {"_": {"default": "mdi:air-humidifier", "state": {"off": "mdi:air-humidifier-off"}}},
    "calendar": {"_": {"default": "mdi:calendar"}},
}


def icons(category):
    return ICONS if category == "entity_component" else {}


# ---- HA's config, panels, dashboards and registries

COMPONENTS = ["frontend", "lovelace", "history", "recorder", "sensor", "binary_sensor", "light", "switch", "fan", "cover", "lock",
              "climate", "weather", "media_player", "alarm_control_panel", "todo", "button", "script", "scene", "select",
              "input_select", "input_boolean", "person", "sun", "camera", "homeassistant", "persistent_notification",
              "humidifier", "calendar", "logbook"]


def config(hls=False):
    return {"latitude": 0.0, "longitude": 0.0, "elevation": 0, "radius": 100,
            "unit_system": {"length": "km", "accumulated_precipitation": "mm", "area": "m²", "mass": "g", "pressure": "Pa",
                            "temperature": "°C", "volume": "L", "wind_speed": "m/s"},
            "location_name": "Glassworks Lab", "time_zone": "UTC", "components": COMPONENTS + (["stream"] if hls else []),
            "config_dir": "/config", "allowlist_external_dirs": [], "allowlist_external_urls": [], "version": HA_VERSION,
            "config_source": "storage", "recovery_mode": False, "safe_mode": False, "state": "RUNNING", "external_url": None,
            "internal_url": None, "currency": "EUR", "country": "NL", "language": "en", "debug": False}


def panels():
    # "home" is the pinned frontend's default panel (src/data/panel.ts), as fakeha.py serves it
    return {
        "home": {"component_name": "home", "icon": "mdi:home", "title": "home", "config": None, "url_path": "home",
                 "require_admin": False, "config_panel_domain": None},
        "lovelace": {"component_name": "lovelace", "icon": None, "title": None, "config": {"mode": "storage"},
                     "url_path": "lovelace", "require_admin": False, "config_panel_domain": None},
        DASHBOARD: {"component_name": "lovelace", "icon": "mdi:view-dashboard", "title": DASHBOARD_TITLE,
                    "config": {"mode": "storage"}, "url_path": DASHBOARD, "require_admin": False,
                    "show_in_sidebar": True, "config_panel_domain": None}}


def dashboards_list():
    return [{"id": "vitro_parity", "url_path": DASHBOARD, "title": DASHBOARD_TITLE, "icon": "mdi:view-dashboard",
             "show_in_sidebar": True, "require_admin": False, "mode": "storage"}]


def resources(extra=()):
    out = [{"id": "vitro_energy_ring", "url": "/local/vitro-demo/energy-ring-card.js", "type": "module"},
           {"id": "vitro_panel_card", "url": PANEL_CARD_URL, "type": "module"},
           {"id": "vitro_wrapper_card", "url": "/local/vitro-demo/wrapper-card.js", "type": "module"}]
    for k, url in enumerate(extra):
        out.append({"id": f"extra_{k}", "url": url, "type": "css" if url.split("?")[0].endswith(".css") else "module"})
    return out


FLOORS = [{"floor_id": "ground", "name": "Ground floor", "level": 0, "icon": "mdi:home-floor-0", "aliases": [],
           "created_at": 0, "modified_at": 0}]
AREA_REGISTRY = [{"area_id": "studio", "name": "Studio", "picture": None, "icon": "mdi:desk", "floor_id": "ground", "labels": [],
                  "aliases": [], "temperature_entity_id": None, "humidity_entity_id": None, "created_at": 0, "modified_at": 0},
                 {"area_id": "lounge", "name": "Lounge", "picture": None, "icon": "mdi:sofa", "floor_id": "ground", "labels": [],
                  "aliases": [], "temperature_entity_id": None, "humidity_entity_id": None, "created_at": 0, "modified_at": 0}]
LABELS = [{"label_id": "showcase", "name": "Showcase", "icon": "mdi:star", "color": "amber", "description": None,
           "created_at": 0, "modified_at": 0}]


def entity_registry():
    out = {}
    for area, ids in AREAS.items():
        for e in ids:
            out[e] = {"entity_id": e, "area_id": area, "platform": "vitro_demo"}
    return out


def registry_entry(entity_id, ent, state):
    """config/entity_registry/get: the full entry of an entity that has one."""
    return {"entity_id": entity_id, "name": None, "icon": None, "platform": ent["platform"], "area_id": ent["area_id"],
            "device_id": None, "config_entry_id": None, "disabled_by": None, "hidden_by": None, "entity_category": None,
            "original_name": state["attributes"].get("friendly_name"), "unique_id": f"vitro_{entity_id}", "labels": [],
            "aliases": [], "capabilities": None, "options": {}, "categories": {}, "has_entity_name": False,
            "original_device_class": state["attributes"].get("device_class"), "translation_key": None,
            "created_at": 0, "modified_at": 0}


def services_catalog():
    def one(domain, service):
        d = {"name": service.replace("_", " ").title(), "description": "", "fields": {}, "target": {"entity": [{"domain": [domain]}]}}
        if (domain, service) == ("todo", "get_items"):
            d["response"] = {"optional": False}
        return d
    out = {}
    for domain, svcs in SERVICES.items():
        out[domain] = {s: one(domain, s) for s in svcs}
    return out


# ---- time windows, history and statistics (all derived from the entity and the window, so they repeat)

def window(start, end, now=None):
    """A request's time window in epoch seconds (ISO strings or numbers; the frontend sends no end for a live graph)."""
    def parse(x):
        if isinstance(x, (int, float)):
            return float(x)
        return datetime.datetime.fromisoformat(x.replace("Z", "+00:00")).timestamp()
    now = time.time() if now is None else now
    t1 = parse(end) if end not in (None, "") else now           # 0 is the epoch, not "no bound"
    t0 = parse(start) if start not in (None, "") else t1 - 86400
    return t0, max(t1, t0)


def _seed(entity_id):
    return zlib.crc32(entity_id.encode())


def _numeric(text):
    try:
        return float(text)
    except (TypeError, ValueError):
        return None


def history_points(entity_id, state, t0, t1, n=48):
    """48 points across [t0, t1]: a smooth curve around the entity's value for a number, a block pattern for the rest."""
    seed = _seed(entity_id)
    base = _numeric(state["state"])
    out = []
    for k in range(n):
        t = round(t0 + (t1 - t0) * k / max(1, n - 1), 3)
        if base is not None:
            amp = max(abs(base) * 0.12, 0.5)
            wave = math.sin(2 * math.pi * (k / n) * (1 + seed % 2) + (seed % 628) / 100) + 0.35 * math.sin(k * 0.9 + seed % 7)
            val = round(base + amp * wave, 1)
            out.append({"s": str(val), "lu": t})
        else:
            options = state["attributes"].get("options")
            if options:
                out.append({"s": options[((k + seed) // 8) % len(options)], "lu": t})
            elif state["state"] in ("on", "off"):
                out.append({"s": "on" if ((k + seed) // 6) % 2 == 0 else "off", "lu": t})
            else:
                out.append({"s": state["state"], "lu": t})
    return out


def history(hub, entity_ids, start, end, no_attributes=False, now=None):
    t0, t1 = window(start, end, now)
    out = {}
    for e in entity_ids:
        st = hub.states.get(e)
        if not st:
            continue
        pts = history_points(e, st, t0, t1)
        if not no_attributes:
            pts[0]["a"] = dict(st["attributes"])
        out[e] = pts
    return out, t0, t1


def rest_history(hub, start, end, entity_ids=None, no_attributes=False, now=None):
    """GET /api/history/period[/<start>]: HA's REST shape, one list per entity of {entity_id, state, attributes, last_changed,
    last_updated}, from the same synthetic curves as the websocket's history (so both agree for one window). Without
    filter_entity_id every entity in the home is returned; an unknown id gives no list. `minimal_response` and
    `significant_changes_only` change nothing here: every point carries the full set of keys."""
    ids = entity_ids if entity_ids else list(hub.states)
    pts, _, _ = history(hub, ids, start, end, no_attributes, now)
    out = []
    for e in ids:
        if e not in pts:
            continue
        series = []
        for p in pts[e]:
            t = _iso(p["lu"])
            series.append({"entity_id": e, "state": p["s"], "attributes": p.get("a", {}), "last_changed": t, "last_updated": t})
        out.append(series)
    return out


STAT_STEP = {"5minute": 300, "hour": 3600, "day": 86400, "week": 7 * 86400, "month": 30 * 86400}


def statistics(hub, statistic_ids, start, end, period, now=None):
    t0, t1 = window(start, end, now)
    step = STAT_STEP.get(period or "hour", 3600)
    n = min(500, max(1, round((t1 - t0) / step)))
    out = {}
    for sid in statistic_ids or []:
        st = hub.states.get(sid)
        base = _numeric(st["state"]) if st else None
        if base is None:
            continue
        seed, total, pts = _seed(sid), 0.0, []
        for i in range(n):
            t = t1 - (n - i) * step
            mean = round(base * (1 + 0.12 * math.sin(i * 0.5 + seed % 9)), 2)
            total += abs(mean) * step / 3600 / 1000
            pts.append({"start": int(t * 1000), "end": int((t + step) * 1000), "mean": mean, "min": round(mean * 0.92, 2),
                        "max": round(mean * 1.08, 2), "last_reset": None, "state": mean, "sum": round(total, 3),
                        "change": round(abs(mean) * step / 3600 / 1000, 3)})
        out[sid] = pts
    return out


def statistics_metadata(hub, ids):
    out = []
    for sid in ids or [e for e in hub.states if e.startswith("sensor.")]:
        st = hub.states.get(sid)
        if not st or _numeric(st["state"]) is None:
            continue
        unit = st["attributes"].get("unit_of_measurement")
        total = st["attributes"].get("state_class") == "total_increasing"
        out.append({"statistic_id": sid, "source": "recorder", "name": None, "has_mean": not total, "has_sum": total,
                    "unit_of_measurement": unit, "statistics_unit_of_measurement": unit, "unit_class": None, "mean_type": 0 if total else 1})
    return out


def forecast(now=None):
    """weather/subscribe_forecast: five days from today (UTC)."""
    now = time.time() if now is None else now
    day = 86400
    conds = ["rainy", "partlycloudy", "sunny", "cloudy", "partlycloudy"]
    out = []
    for k, c in enumerate(conds):
        t = now - now % day + (k + 1) * day + 12 * 3600
        out.append({"datetime": _iso(t), "condition": c, "temperature": [10, 11, 12, 9, 8][k], "templow": [5, 6, 4, 3, 2][k],
                    "precipitation": [4.2, 0.6, 0, 0.2, 0.4][k], "is_daytime": True})
    return {"type": "daily", "forecast": out}


def calendar_events(now=None):
    """GET /api/calendars/<entity>: four made-up events, each one in the future of `now` (the next quarter hour is the base)."""
    now = time.time() if now is None else now
    base = now - now % 900 + 900
    plan = [(1, 1.0, "Design review", "Walk through the synthetic floorplan mockups."),
            (5, 0.5, "Lighting calibration", "Check every lamp against the test pattern."),
            (26, 1.5, "Studio planning", "Plan next week's made-up builds."),
            (50, 1.0, "Prototype demo", "Show the fixture ensemble to the test audience.")]
    return [{"start": {"dateTime": _iso(base + h * 3600)}, "end": {"dateTime": _iso(base + (h + length) * 3600)},
             "summary": summary, "description": text} for h, length, summary, text in plan]


# (minutes ago, entity, state, what happened): the last state of each entity matches the home's own start state
LOGBOOK = [(330, "light.desk_lamp", "off", "turned off"), (280, "cover.studio_blind", "closed", "closed"),
           (200, "switch.studio_fan_plug", "off", "turned off"), (120, "cover.studio_blind", "open", "opened"),
           (75, "light.desk_lamp", "on", "turned on"), (30, "switch.studio_fan_plug", "on", "turned on")]


def logbook_events(entity_ids=None, now=None):
    """logbook/get_events: six made-up recent events, oldest first, anchored to `now`; `entity_ids` narrows them."""
    now = time.time() if now is None else now
    wanted = set(entity_ids) if entity_ids else None
    return [{"when": round(now - minutes * 60, 3), "name": ENTITIES[eid][1]["friendly_name"], "state": state, "entity_id": eid,
             "domain": domain_of(eid), "message": message}
            for minutes, eid, state, message in LOGBOOK if wanted is None or eid in wanted]


# ---- a small Jinja subset for render_template: {{ }}, {% if/elif/else %}, states(), state_attr(), is_state() and filters

class TemplateError(Exception):
    pass


_TOK = re.compile(r"""\s*(?:(?P<num>\d+\.\d+|\d+)|(?P<str>'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*")|(?P<op>==|!=|<=|>=|[-+*/%<>()\[\],.|~])|(?P<name>[A-Za-z_][A-Za-z_0-9]*))""")


class _StateObj:
    def __init__(self, st):
        self.entity_id, self.state, self.attributes = st["entity_id"], st["state"], st["attributes"]
        self.name = st["attributes"].get("friendly_name", st["entity_id"])


class _Domain:
    def __init__(self, hub, domain):
        self._hub, self._domain = hub, domain

    def __getattr__(self, name):
        st = self._hub.states.get(f"{self._domain}.{name}")
        return _StateObj(st) if st else None


class _States:
    def __init__(self, hub):
        self._hub = hub

    def __call__(self, entity_id):
        st = self._hub.states.get(entity_id)
        return st["state"] if st else "unknown"

    def __getattr__(self, domain):
        return _Domain(self._hub, domain)


def _num(v, default=0.0):
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def _jinja_round(v, precision=0, method="common"):
    v = _num(v)
    return math.floor(v) if method == "floor" else math.ceil(v) if method == "ceil" else round(v, int(precision))


FILTERS = {
    "float": lambda v, d=0.0: _num(v, d), "int": lambda v, d=0: int(_num(v, d)), "round": _jinja_round, "abs": lambda v: abs(_num(v)),
    "upper": lambda v: str(v).upper(), "lower": lambda v: str(v).lower(), "title": lambda v: str(v).title(),
    "capitalize": lambda v: str(v).capitalize(), "string": str, "length": len, "count": len, "bool": lambda v: str(v).lower() in ("1", "true", "yes", "on"),
    "default": lambda v, d="": d if v is None or v == "" else v, "replace": lambda v, a, b: _bounded_replace(str(v), str(a), str(b)),
    "trim": lambda v: str(v).strip(),
}


MAX_TEMPLATE_OUTPUT = 64 * 1024


def _bounded_replace(s, a, b):
    """str.replace, refused before it allocates past MAX_TEMPLATE_OUTPUT (`'x' * 60000 | replace('x', 'yyyy')`)."""
    n = len(s) + 1 if a == "" else s.count(a)
    if len(s) + n * (len(b) - len(a)) > MAX_TEMPLATE_OUTPUT:
        raise TemplateError("result too large")
    return s.replace(a, b)
_DATE_FIELDS = {"year", "month", "day", "hour", "minute", "second", "microsecond", "tzinfo"}


class _Eval:
    """Recursive-descent evaluator for one expression (values come out directly, no tree)."""

    def __init__(self, text, hub):
        self.hub, self.toks, self.i = hub, [], 0
        self.dry = 0        # > 0 while parsing an operand `and`/`or` doesn't need: consumed, not evaluated
        pos = 0
        text = text.rstrip()
        while pos < len(text):
            m = _TOK.match(text, pos)
            if not m:
                raise TemplateError(f"bad token near {text[pos:pos + 10]!r}")
            kind = m.lastgroup
            self.toks.append((kind, m.group(kind)))
            pos = m.end()
        self.env = {"states": _States(hub), "true": True, "false": False, "none": None, "True": True, "False": False, "None": None}
        self.funcs = {
            # A detached copy: a template must not be able to change the home through a list or dict it reads.
            "state_attr": lambda e, a: copy.deepcopy((hub.states.get(e) or {"attributes": {}})["attributes"].get(a)),
            "is_state": lambda e, s: (hub.states.get(e) or {}).get("state") in (s if isinstance(s, (list, tuple)) else [s]),
            "is_state_attr": lambda e, a, v: (hub.states.get(e) or {"attributes": {}})["attributes"].get(a) == v,
            "has_value": lambda e: (hub.states.get(e) or {}).get("state") not in (None, "unknown", "unavailable"),
            "float": lambda v, d=0.0: _num(v, d), "int": lambda v, d=0: int(_num(v, d)), "iif": lambda c, a=True, b=False, n=None: a if c else b,
            "min": min, "max": max, "round": _jinja_round, "abs": abs, "now": lambda: datetime.datetime.now(datetime.timezone.utc),
        }

    def peek(self, *vals):
        return self.i < len(self.toks) and self.toks[self.i][1] in vals

    def take(self):
        t = self.toks[self.i]
        self.i += 1
        return t

    def run(self):
        v = self.or_()
        if self.i != len(self.toks):
            raise TemplateError("unexpected " + self.toks[self.i][1])
        return v

    def _skip(self, part):
        self.dry += 1
        try:
            part()
        finally:
            self.dry -= 1

    def or_(self):
        v = self.and_()
        while self.peek("or"):
            self.take()
            if v and not self.dry:
                self._skip(self.and_)       # Jinja short-circuits: the right side isn't evaluated
            else:
                r = self.and_()
                v = v or r
        return v

    def and_(self):
        v = self.not_()
        while self.peek("and"):
            self.take()
            if not v and not self.dry:
                self._skip(self.not_)
            else:
                r = self.not_()
                v = v and r
        return v

    def not_(self):
        if self.peek("not"):
            self.take()
            v = self.not_()
            return None if self.dry else not v
        return self.cmp()

    def cmp(self):
        v = self.add()
        if self.peek("==", "!=", "<", ">", "<=", ">=", "in"):
            op = self.take()[1]
            r = self.add()
            if self.dry:
                return None
            try:
                return {"==": lambda: v == r, "!=": lambda: v != r, "<": lambda: v < r, ">": lambda: v > r,
                        "<=": lambda: v <= r, ">=": lambda: v >= r, "in": lambda: v in r}[op]()
            except TypeError as e:
                raise TemplateError(str(e))
        return v

    def add(self):
        v = self.mul()
        while self.peek("+", "-", "~"):
            op = self.take()[1]
            r = self.mul()
            if not self.dry:
                if op == "~" or (op == "+" and isinstance(v, (str, list)) and isinstance(r, (str, list))):
                    # Concatenation: charged against the same budget as repetition, before it allocates.
                    if len(str(v) if op == "~" else v) + len(str(r) if op == "~" else r) > MAX_TEMPLATE_OUTPUT:
                        raise TemplateError("result too large")
                v = str(v) + str(r) if op == "~" else v + r if op == "+" else v - r
        return v

    def mul(self):
        v = self.filt()
        while self.peek("*", "/", "%"):
            op = self.take()[1]
            r = self.filt()
            if self.dry:
                continue
            if op == "*" and (isinstance(v, (str, list)) or isinstance(r, (str, list))):
                # Repetition: bounded before it allocates (a tiny template could ask for gigabytes).
                seq, n = (v, r) if isinstance(v, (str, list)) else (r, v)
                if not isinstance(n, int) or len(seq) * max(n, 0) > MAX_TEMPLATE_OUTPUT:
                    raise TemplateError("result too large")
            if op == "%" and isinstance(v, str):
                # printf-style formatting: a width like '%1000000000s' allocates before any check could see it.
                raise TemplateError("string % formatting is not supported")
            v = v * r if op == "*" else v / r if op == "/" else v % r
        return v

    def filt(self):
        v = self.unary()
        while self.peek("|"):
            self.take()
            name = self.take()[1]
            args = self.args() if self.peek("(") else []
            if name not in FILTERS:
                raise TemplateError(f"no filter {name}")
            if not self.dry:
                v = FILTERS[name](v, *args)
        return v

    def unary(self):
        if self.peek("-"):
            self.take()
            v = self.unary()
            return None if self.dry else -v
        return self.postfix()

    def args(self):
        self.take()
        out = []
        while not self.peek(")"):
            out.append(self.or_())
            if self.peek(","):
                self.take()
        self.take()
        return out

    def postfix(self):
        v = self.atom()
        while True:
            if self.peek("."):
                self.take()
                name = self.take()[1]
                if name.startswith("_"):
                    raise TemplateError("private name")
                if self.dry:
                    continue
                # Attributes of the template's own objects and of dates only: never a Python method.
                if isinstance(v, dict):
                    v = v.get(name)
                elif isinstance(v, (_States, _Domain, _StateObj)) or (isinstance(v, datetime.datetime) and name in _DATE_FIELDS):
                    v = getattr(v, name, None)
                else:
                    v = None
            elif self.peek("["):
                self.take()
                k = self.or_()
                self.take()
                if self.dry:
                    continue
                v = v.get(k) if isinstance(v, dict) else None if v is None else v[k]
            elif self.peek("(") and (self.dry or self._callable(v)):
                args = self.args()
                if not self.dry:
                    v = v(*args)
            else:
                return v

    def _callable(self, v):
        """Only the template helpers (and `states(...)`) can be called."""
        return isinstance(v, _States) or any(v is f for f in self.funcs.values())

    def atom(self):
        if self.i >= len(self.toks):
            raise TemplateError("unexpected end")
        kind, text = self.take()
        if kind == "num":
            return float(text) if "." in text else int(text)
        if kind == "str":
            return ast.literal_eval(text)
        if text == "(":
            v = self.or_()
            self.take()
            return v
        if kind == "name":
            if text in self.env:
                return self.env[text]
            if text in self.funcs:
                return self.funcs[text]
            raise TemplateError(f"unknown name {text}")
        raise TemplateError("unexpected " + text)


_BLOCK = re.compile(r"(\{\{-?.*?-?\}\}|\{%-?.*?-?%\}|\{#.*?#\})", re.S)


def _parse_template(text):
    """Nodes: ('text', s) | ('expr', e) | ('if', [(cond, nodes)...], else_nodes)."""
    parts = _BLOCK.split(text)
    pos = [0]

    def nodes(until):
        out = []
        while pos[0] < len(parts):
            p = parts[pos[0]]
            pos[0] += 1
            if p.startswith("{{"):
                out.append(("expr", p[2:-2].strip("-").strip()))
            elif p.startswith("{%"):
                tag = p[2:-2].strip("-").strip()
                word = tag.split(None, 1)[0] if tag else ""
                if word in until:
                    return out, word, tag
                if word == "if":
                    branches, other = [], []
                    cond = tag[2:].strip()
                    while True:
                        body, end, endtag = nodes(("elif", "else", "endif"))
                        if end is None:
                            raise TemplateError("if without endif")
                        branches.append((cond, body))
                        if end == "elif":
                            cond = endtag[4:].strip()
                        elif end == "else":
                            other, end2, _ = nodes(("endif",))
                            if end2 is None:
                                raise TemplateError("if without endif")
                            break
                        else:
                            break
                    out.append(("if", branches, other))
                else:
                    raise TemplateError(f"unsupported tag {word}")
            elif p.startswith("{#"):
                continue
            else:
                out.append(("text", p))
        return out, None, None
    tree, end, _ = nodes(())
    if end:
        raise TemplateError("unexpected " + end)
    # `{{-`/`-}}`/`{%-`/`-%}` trim the whitespace beside them
    return tree


def _trim_markers(text):
    text = re.sub(r"\s*(\{\{-|\{%-)", r"\1", text)
    return re.sub(r"(-\}\}|-%\})\s*", r"\1", text)


def _show(v):
    if isinstance(v, bool):
        return "True" if v else "False"
    return "None" if v is None else str(v)


def render_template(hub, template):
    """The template's text and the entities it read. Raises TemplateError."""
    tree = _parse_template(_trim_markers(template))
    seen = set()

    def run(nodes):
        out = []
        size = 0
        for n in nodes:
            if n[0] == "text":
                part = n[1]
            elif n[0] == "expr":
                part = _show(_Eval(n[1], hub).run())
            else:
                for cond, body in n[1]:
                    if _Eval(cond, hub).run():
                        part = run(body)
                        break
                else:
                    part = run(n[2])
            # The output as a whole stays in the budget too, not only each operation.
            size += len(part)
            if size > MAX_TEMPLATE_OUTPUT:
                raise TemplateError("result too large")
            out.append(part)
        return "".join(out)
    text = run(tree).strip()
    for m in re.finditer(r"""['"]((?:[a-z_]+)\.(?:[a-z0-9_]+))['"]""", template):
        seen.add(m.group(1))
    return text, sorted(seen)


def parse_result(text):
    """HA's native types: a template that renders a number or a boolean sends one."""
    if re.fullmatch(r"-?\d+", text):
        return int(text)
    if re.fullmatch(r"-?\d+\.\d+", text):
        return float(text)
    return {"True": True, "False": False}.get(text, text)


def eval_condition(hub, cond):
    """A Lovelace/automation condition (state, numeric_state, template, and/or/not) against the home, as a bool."""
    kind = cond.get("condition")
    listed = lambda v: [v] if isinstance(v, str) else list(v or [])  # noqa: E731
    if kind in ("and", "or"):
        results = [eval_condition(hub, c) for c in cond.get("conditions", [])]
        return all(results) if kind == "and" else any(results)
    if kind == "not":
        return not any(eval_condition(hub, c) for c in cond.get("conditions", []))
    if kind == "template":
        text, _ = render_template(hub, cond["value_template"])
        return text.strip().lower() in ("true", "1", "yes", "on")
    ids = listed(cond.get("entity_id") or cond.get("entity"))
    if kind not in ("state", "numeric_state") or not ids:
        raise TemplateError(f"unsupported condition {kind!r}")
    for e in ids:
        st = hub.states.get(e)
        if not st:
            return False
        value = st["attributes"].get(cond["attribute"]) if cond.get("attribute") else st["state"]
        if kind == "state":
            want = listed(cond.get("state") or cond.get("state_not") or [])
            ok = str(value) in [str(w) for w in want]
            if "state_not" in cond and "state" not in cond:
                ok = not ok
        else:
            num = _numeric(value)
            bound = lambda b: _numeric(hub.states[b]["state"]) if isinstance(b, str) and b in hub.states else _numeric(b)  # noqa: E731
            ok = num is not None and ("above" not in cond or num > bound(cond["above"])) and ("below" not in cond or num < bound(cond["below"]))
        if not ok:
            return False
    return True


# ---- services: each takes the entity's state and the call's data and returns (state, attributes to merge)

class ServiceError(Exception):
    pass


def _hs_xy(rgb):
    r, g, b = (c / 255 for c in rgb)
    h, s, _ = colorsys.rgb_to_hsv(r, g, b)
    lin = [((c + 0.055) / 1.055) ** 2.4 if c > 0.04045 else c / 12.92 for c in (r, g, b)]
    x = lin[0] * 0.664511 + lin[1] * 0.154324 + lin[2] * 0.162028
    y = lin[0] * 0.283881 + lin[1] * 0.668433 + lin[2] * 0.047685
    z = lin[0] * 0.000088 + lin[1] * 0.072310 + lin[2] * 0.986039
    tot = x + y + z or 1
    return [round(h * 360, 3), round(s * 100, 3)], [round(x / tot, 4), round(y / tot, 4)]


def _kelvin_rgb(k):
    t = k / 100
    r = 255 if t <= 66 else max(0, min(255, 329.698727446 * (t - 60) ** -0.1332047592))
    g = max(0, min(255, 99.4708025861 * math.log(t) - 161.1195681661)) if t <= 66 else max(0, min(255, 288.1221695283 * (t - 60) ** -0.0755148492))
    b = 255 if t >= 66 else 0 if t <= 19 else max(0, min(255, 138.5177312231 * math.log(t - 10) - 305.0447927307))
    return [round(r), round(g), round(b)]


def svc_light(hub, eid, s, svc, d):
    a = s["attributes"]
    on = s["state"] == "on"
    if svc == "toggle":
        svc = "turn_off" if on else "turn_on"
    if svc == "turn_off":
        if on:      # only on the way off: a second turn_off must not overwrite what the lamp was
            hub.memory[eid] = {k: a.get(k) for k in ("brightness", "color_mode", "color_temp_kelvin", "rgb_color", "hs_color", "xy_color")}
        return "off", {"brightness": None, "color_mode": None, "color_temp_kelvin": None, "rgb_color": None, "hs_color": None, "xy_color": None}
    if svc != "turn_on":
        raise ServiceError("service_not_found")
    attrs = dict(hub.memory.get(eid) or {}) if not on else {}
    if not on and not attrs.get("brightness"):
        attrs.update({"brightness": 255, "color_mode": "color_temp", "color_temp_kelvin": 3000})
    modes = a.get("supported_color_modes") or []
    if "brightness_pct" in d:
        attrs["brightness"] = round(float(d["brightness_pct"]) * 255 / 100)
    if "brightness" in d:
        attrs["brightness"] = int(d["brightness"])
    if "color_temp" in d and "color_temp_kelvin" not in d:           # mireds
        d = dict(d, color_temp_kelvin=round(1_000_000 / float(d["color_temp"])))
    if "color_temp_kelvin" in d and "color_temp" in modes:
        k = max(a.get("min_color_temp_kelvin", 2000), min(a.get("max_color_temp_kelvin", 6500), int(d["color_temp_kelvin"])))
        rgb = _kelvin_rgb(k)
        hs, xy = _hs_xy(rgb)
        attrs.update({"color_mode": "color_temp", "color_temp_kelvin": k, "rgb_color": rgb, "hs_color": hs, "xy_color": xy})
    if "rgb_color" in d and "rgb" in modes:
        rgb = [max(0, min(255, int(c))) for c in d["rgb_color"]]
        hs, xy = _hs_xy(rgb)
        attrs.update({"color_mode": "rgb", "rgb_color": rgb, "hs_color": hs, "xy_color": xy, "color_temp_kelvin": None})
    if attrs.get("brightness") == 0:
        return svc_light(hub, eid, s, "turn_off", {})
    return "on", attrs


def _onoff(svc, s):
    if svc == "toggle":
        return "off" if s["state"] == "on" else "on"
    return {"turn_on": "on", "turn_off": "off"}[svc]


def svc_switch(hub, eid, s, svc, d):
    return _onoff(svc, s), {}


def svc_fan(hub, eid, s, svc, d):
    a = s["attributes"]
    if svc == "set_percentage":
        p = int(d["percentage"])
        return ("on" if p > 0 else "off"), {"percentage": p}
    if svc == "set_preset_mode":
        return "on", {"preset_mode": d["preset_mode"]}
    state = _onoff(svc, s)
    attrs = {}
    if state == "on" and "percentage" in d:
        attrs["percentage"] = int(d["percentage"])
    elif state == "on" and not a.get("percentage"):
        attrs["percentage"] = 40
    return state, attrs


def _hvac_action(mode, cur, target):
    if mode == "off":
        return "off"
    if mode == "heat":
        return "heating" if cur < target - 0.2 else "idle"
    if mode == "cool":
        return "cooling" if cur > target + 0.2 else "idle"
    return "idle"


def svc_climate(hub, eid, s, svc, d):
    a = s["attributes"]
    mode, target = s["state"], a.get("temperature")
    if svc == "set_temperature":
        target = float(d["temperature"])
        if "hvac_mode" in d:
            mode = d["hvac_mode"]
    elif svc == "set_hvac_mode":
        mode = d["hvac_mode"]
    elif svc in ("turn_on", "turn_off", "toggle"):
        mode = "heat" if (svc == "turn_on" or (svc == "toggle" and mode == "off")) else "off"
    else:
        raise ServiceError("service_not_found")
    if mode not in a["hvac_modes"]:
        raise ServiceError(f"hvac mode {mode} is not supported")
    target = max(a["min_temp"], min(a["max_temp"], target))
    return mode, {"temperature": target, "hvac_action": _hvac_action(mode, a["current_temperature"], target)}


def svc_cover(hub, eid, s, svc, d):
    if svc == "toggle":
        svc = "close_cover" if s["state"] in ("open", "opening") else "open_cover"
    if svc == "open_cover":
        return "open", {"current_position": 100}
    if svc == "close_cover":
        return "closed", {"current_position": 0}
    if svc == "stop_cover":
        return s["state"], {}
    if svc == "set_cover_position":
        p = max(0, min(100, int(d["position"])))
        return ("open" if p > 0 else "closed"), {"current_position": p}
    raise ServiceError("service_not_found")


def svc_lock(hub, eid, s, svc, d):
    return {"lock": "locked", "unlock": "unlocked", "open": "open"}[svc], {}


def svc_select(hub, eid, s, svc, d):
    options = s["attributes"]["options"]
    if svc == "select_option":
        if d["option"] not in options:
            raise ServiceError(f"option {d['option']} is not valid for {eid}")
        return d["option"], {}
    i = options.index(s["state"]) if s["state"] in options else 0
    return options[(i + (1 if svc == "select_next" else -1)) % len(options)], {}


def _track(i):
    title, artist, dur = PLAYLIST[i % len(PLAYLIST)]
    return {"media_title": title, "media_artist": artist, "media_duration": dur, "media_position": 0,
            "media_position_updated_at": _iso(time.time())}


def svc_media(hub, eid, s, svc, d):
    a = s["attributes"]
    cur = next((i for i, t in enumerate(PLAYLIST) if t[0] == a.get("media_title")), 0)
    if svc == "media_play_pause":
        return ("paused" if s["state"] == "playing" else "playing"), {}
    if svc == "media_play":
        return "playing", {}
    if svc == "media_pause":
        return "paused", {}
    if svc == "media_stop":
        return "idle", {}
    if svc == "media_next_track":
        return "playing", _track(cur + 1)
    if svc == "media_previous_track":
        return "playing", _track(cur - 1)
    if svc == "volume_set":
        return s["state"], {"volume_level": max(0.0, min(1.0, float(d["volume_level"])))}
    if svc == "volume_mute":
        return s["state"], {"is_volume_muted": bool(d["is_volume_muted"])}
    if svc == "turn_on":
        return "idle", {}
    if svc == "turn_off":
        return "off", {}
    if svc == "toggle":
        return ("off" if s["state"] != "off" else "idle"), {}
    raise ServiceError("service_not_found")


def _humidifier_action(state, current, target):
    if state != "on":
        return "off"
    return "humidifying" if current < target else "idle"


def svc_humidifier(hub, eid, s, svc, d):
    a = s["attributes"]
    if svc == "set_humidity":
        target = max(a["min_humidity"], min(a["max_humidity"], int(d["humidity"])))
        return s["state"], {"humidity": target, "action": _humidifier_action(s["state"], a["current_humidity"], target)}
    if svc == "set_mode":
        if d["mode"] not in a["available_modes"]:
            raise ServiceError(f"mode {d['mode']} is not valid for {eid}")
        return s["state"], {"mode": d["mode"]}
    state = _onoff(svc, s)
    return state, {"action": _humidifier_action(state, a["current_humidity"], a["humidity"])}


def svc_alarm(hub, eid, s, svc, d):
    return {"alarm_arm_home": "armed_home", "alarm_arm_away": "armed_away", "alarm_arm_night": "armed_night",
            "alarm_disarm": "disarmed"}[svc], {}


def svc_button(hub, eid, s, svc, d):
    return _iso(time.time()), {}


def svc_script(hub, eid, s, svc, d):
    hub.effects(eid)
    return "off", {"last_triggered": _iso(time.time())}


def svc_scene(hub, eid, s, svc, d):
    hub.effects(eid)
    return _iso(time.time()), {}


def svc_homeassistant(hub, eid, s, svc, d):
    if svc == "update_entity":
        return s["state"], {}
    return hub.dispatch(domain_of(eid), svc, eid, d)


SERVICES = {
    "light": {k: svc_light for k in ("turn_on", "turn_off", "toggle")},
    "switch": {k: svc_switch for k in ("turn_on", "turn_off", "toggle")},
    "input_boolean": {k: svc_switch for k in ("turn_on", "turn_off", "toggle")},
    "fan": {k: svc_fan for k in ("turn_on", "turn_off", "toggle", "set_percentage", "set_preset_mode")},
    "climate": {k: svc_climate for k in ("set_temperature", "set_hvac_mode", "turn_on", "turn_off", "toggle")},
    "cover": {k: svc_cover for k in ("open_cover", "close_cover", "stop_cover", "toggle", "set_cover_position")},
    "lock": {k: svc_lock for k in ("lock", "unlock", "open")},
    "select": {k: svc_select for k in ("select_option", "select_next", "select_previous")},
    "input_select": {k: svc_select for k in ("select_option", "select_next", "select_previous")},
    "media_player": {k: svc_media for k in ("media_play_pause", "media_play", "media_pause", "media_stop", "media_next_track",
                                            "media_previous_track", "volume_set", "volume_mute", "turn_on", "turn_off", "toggle")},
    "alarm_control_panel": {k: svc_alarm for k in ("alarm_arm_home", "alarm_arm_away", "alarm_arm_night", "alarm_disarm")},
    "humidifier": {k: svc_humidifier for k in ("toggle", "turn_on", "turn_off", "set_humidity", "set_mode")},
    "button": {"press": svc_button}, "script": {"turn_on": svc_script}, "scene": {"turn_on": svc_scene},
    "todo": {k: None for k in ("add_item", "update_item", "remove_item", "remove_completed_items", "get_items")},
    "homeassistant": {k: svc_homeassistant for k in ("turn_on", "turn_off", "toggle", "update_entity")},
}
# What a script or a scene does to the home: (domain, service, entity, data)
EFFECTS = {
    "scene.movie_night": [("light", "turn_on", "light.desk_lamp", {"brightness": 60}), ("light", "turn_off", "light.ceiling_strip", {}),
                          ("cover", "close_cover", "cover.studio_blind", {})],
    "script.good_morning": [("light", "turn_on", "light.desk_lamp", {"brightness": 255}), ("cover", "open_cover", "cover.studio_blind", {}),
                            ("switch", "turn_on", "switch.coffee_maker", {})],
}


# ---- the home's state and its subscribers

class Hub:
    """The fixed home: states, todo lists, every open connection. One lock for the home; a message is sent outside it."""

    def __init__(self, hls=False, extra_resources=(), log=None):
        self.lock = threading.RLock()
        self.publish_lock = threading.Lock()   # held from commit to the end of its announce (lock order: lock, then this)
        self._ctx = 0
        self.states = {}
        for e, (state, attrs) in ENTITIES.items():
            self.states[e] = {"entity_id": e, "state": state, "attributes": json.loads(json.dumps(attrs)),
                              "last_changed": START, "last_updated": START, "context_id": self.context()}
        self.areas = {a: list(ids) for a, ids in AREAS.items()}
        self.todos = {e: [{"uid": u, "summary": s, "status": st, "due": None, "description": None} for u, s, st in items]
                      for e, items in TODOS.items()}
        self.todo_n = 100
        self.memory = {}
        self.conns = set()
        self.hls, self.extra_resources, self.log = hls, list(extra_resources), log
        self.unknown = set()
        if hls:
            self.states["camera.studio_front"]["attributes"]["supported_features"] = 2
        self._sync_todo_states()

    def context(self):
        with self.lock:
            self._ctx += 1
            return f"01JVITRO{self._ctx:018d}"

    def record(self, entry):
        if self.log:
            with self.lock:
                self.log.write(json.dumps(entry) + "\n")
                self.log.flush()

    def state_object(self, st):
        return {"entity_id": st["entity_id"], "state": st["state"], "attributes": st["attributes"],
                "last_changed": _iso(st["last_changed"]), "last_updated": _iso(st["last_updated"]),
                "context": {"id": st["context_id"], "parent_id": None, "user_id": None}}

    @staticmethod
    def compressed(st):
        return {"s": st["state"], "a": st["attributes"], "c": st["context_id"], "lc": st["last_changed"], "lu": st["last_updated"]}

    # -- changing the home (all under the lock; the diffs go out after)

    def _apply(self, entity_id, state, attrs, now):
        s = self.states[entity_id]
        old = self.state_object(s)
        changed_state = state != s["state"]
        merged = dict(s["attributes"], **(attrs or {}))
        if not changed_state and merged == s["attributes"]:
            return None
        s["state"], s["attributes"] = state, merged
        s["last_updated"] = now
        if changed_state:
            s["last_changed"] = now
        s["context_id"] = self.context()
        diff = {"s": state, "a": merged, "c": s["context_id"], "lu": now}
        if changed_state:
            diff["lc"] = now
        return {"entity_id": entity_id, "diff": {"+": diff}, "old": old, "new": self.state_object(s)}

    def dispatch(self, domain, svc, eid, data):
        fn = SERVICES.get(domain, {}).get(svc)
        if fn is None:
            raise ServiceError("service_not_found")
        return fn(self, eid, self.states[eid], svc, data)

    def effects(self, eid):
        """A scene or script ran: its changes join the same call (called inside call_service)."""
        for domain, svc, target, data in EFFECTS.get(eid, []):
            state, attrs = self.dispatch(domain, svc, target, data)
            ch = self._apply(target, state, attrs, time.time())
            if ch:
                self._pending.append(ch)

    def call_service(self, domain, service, data, entity_ids, return_response=False):
        """Run a service. Returns the response (todo.get_items) or None. Raises ServiceError."""
        if domain not in SERVICES or service not in SERVICES[domain]:
            raise ServiceError("service_not_found")
        data = dict(data or {})
        with self.lock:
            self._pending, todo_touched, response = [], set(), None
            now = time.time()
            # Every target first: a bad one later in the list must not leave earlier ones changed but unannounced.
            for eid in entity_ids:
                if eid not in self.states:
                    raise ServiceError(f"entity {eid} not found")
                if domain not in ("todo", "homeassistant") and domain_of(eid) != domain:
                    raise ServiceError(f"{eid} is not a {domain} entity")
            # A target can still fail once dispatched (`homeassistant.turn_off` on a cover, a missing field): the
            # call then changes nothing at all, so no earlier target is left changed but unannounced.
            saved = (copy.deepcopy(self.states), copy.deepcopy(self.todos), self.todo_n, copy.deepcopy(self.memory))
            try:
                for eid in entity_ids:
                    if domain == "todo":
                        response = self._todo_service(eid, service, data, response)
                        todo_touched.add(eid)
                        continue
                    try:
                        state, attrs = self.dispatch(domain if domain != "homeassistant" else "homeassistant", service, eid, data)
                    except KeyError as e:
                        raise ServiceError(f"missing {e}")
                    ch = self._apply(eid, state, attrs, now)
                    if ch:
                        self._pending.append(ch)
            except BaseException:
                # In place: anything holding the dicts keeps seeing the hub's own.
                self.states.clear()
                self.states.update(saved[0])
                self.todos.clear()
                self.todos.update(saved[1])
                self.todo_n = saved[2]
                # Remembered light settings too: a turn_off inside the failed batch mustn't keep what it saved.
                self.memory.clear()
                self.memory.update(saved[3])
                self._pending = []
                raise
            for eid in todo_touched:
                open_items = sum(i["status"] == "needs_action" for i in self.todos[eid])
                ch = self._apply(eid, str(open_items), None, now)
                if ch:
                    self._pending.append(ch)
            changes, self._pending = self._pending, []
            conns = list(self.conns)
            # Templates and conditions rendered now, from this commit's state: a later commit before the announce
            # must not make them report its state instead (and skip this one's).
            rendered = {c: c.render_subs() for c in conns}
            # The touched lists as this commit left them, sent with those results (a later commit can't slip in).
            todo_lists = {eid: copy.deepcopy(self.todos[eid]) for eid in todo_touched}
            # Taken before the hub lock goes: batches go out in the order they were committed.
            self.publish_lock.acquire()
        try:
            for c in conns:
                c.changed(changes, todo_touched, rendered[c], todo_lists)
        finally:
            self.publish_lock.release()
        return response

    def set_state(self, entity_id, state, attrs=None):
        """For tests and scripts: change one entity as HA would, and tell everyone."""
        with self.lock:
            ch = self._apply(entity_id, state, attrs, time.time())
        if ch:
            self.announce([ch], ())

    def _sync_todo_states(self):
        for eid, items in self.todos.items():
            self.states[eid]["state"] = str(sum(i["status"] == "needs_action" for i in items))

    def _todo_service(self, eid, service, data, response):
        items = self.todos[eid]
        if service == "get_items":
            want = data.get("status")
            want = [want] if isinstance(want, str) else want
            return {eid: {"items": [i for i in items if not want or i["status"] in want]}}
        if service == "add_item":
            self.todo_n += 1
            items.append({"uid": str(self.todo_n), "summary": data["item"], "status": "needs_action",
                          "due": data.get("due_date"), "description": data.get("description")})
        elif service == "update_item":
            found = next((i for i in items if data["item"] in (i["uid"], i["summary"])), None)
            if not found:
                raise ServiceError(f"item {data['item']} not found")
            if data.get("rename"):
                found["summary"] = data["rename"]
            if data.get("status"):
                found["status"] = data["status"]
            if "due_date" in data:
                found["due"] = data["due_date"]
            if "description" in data:
                found["description"] = data["description"]
        elif service == "remove_item":
            gone = data["item"] if isinstance(data["item"], list) else [data["item"]]
            self.todos[eid] = [i for i in items if i["uid"] not in gone and i["summary"] not in gone]
        elif service == "remove_completed_items":
            self.todos[eid] = [i for i in items if i["status"] != "completed"]
        return response

    def announce(self, changes, todo_touched):
        with self.lock:
            conns = list(self.conns)
        for c in conns:
            c.changed(changes, todo_touched)

    def close_all(self):
        with self.lock:
            conns = list(self.conns)
        for c in conns:
            try:
                c.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


# ---- the websocket API

# A subscription made after the commit's render: rendered when it's announced instead.
_LIVE = object()


class Conn:
    def __init__(self, hub, sock):
        self.hub, self.sock, self.send_lock = hub, sock, threading.Lock()
        self.entity_subs = {}      # id -> set of entity ids, or None for all
        self.event_subs = {}       # id -> event type
        self.template_subs = {}    # id -> [template, last result]
        self.todo_subs = {}        # id -> todo entity
        self.cond_subs = {}        # id -> [condition, last result]
        self.forecast_subs = set()

    def send(self, msg):
        data = ws_frame(json.dumps(msg, separators=(",", ":")).encode())
        with self.send_lock:
            self.sock.sendall(data)

    def result(self, mid, result=None, success=True, error=None):
        msg = {"id": mid, "type": "result", "success": success}
        msg["result" if success else "error"] = result if success else error
        self.send(msg)

    def error(self, mid, code, message):
        self.result(mid, None, False, {"code": code, "message": message})

    def event(self, mid, ev):
        self.send({"id": mid, "type": "event", "event": ev})

    def render_subs(self):
        """Each template and condition subscription's result now (under the hub lock: one commit's state)."""
        out = {}
        for mid, sub in list(self.template_subs.items()):
            try:
                out[("t", mid)] = render_template(self.hub, sub[0])
            except Exception as e:     # reported by push_template, never to the service caller
                out[("t", mid)] = e
        for mid, sub in list(self.cond_subs.items()):
            try:
                out[("c", mid)] = eval_condition(self.hub, sub[0])
            except Exception as e:
                out[("c", mid)] = e
        return out

    def changed(self, changes, todo_touched, rendered=None, todo_lists=None):
        """What a service call changed: compressed diffs, state_changed events, templates and todo lists. [rendered]:
        the templates' and conditions' results at the commit (render_subs); without it they're rendered now."""
        rendered = rendered or {}
        todo_lists = todo_lists or {}
        try:
            for mid, wanted in list(self.entity_subs.items()):
                diff = {c["entity_id"]: c["diff"] for c in changes if wanted is None or c["entity_id"] in wanted}
                if diff:
                    self.event(mid, {"c": diff})
            for mid, etype in list(self.event_subs.items()):
                if etype in (None, "state_changed"):
                    for c in changes:
                        self.event(mid, {"event_type": "state_changed", "time_fired": _iso(time.time()), "origin": "LOCAL",
                                         "context": c["new"]["context"],
                                         "data": {"entity_id": c["entity_id"], "old_state": c["old"], "new_state": c["new"]}})
            for mid, sub in list(self.template_subs.items()):
                self.push_template(mid, sub, at=rendered.get(("t", mid), _LIVE))
            for mid, sub in list(self.cond_subs.items()):
                self.push_condition(mid, sub, at=rendered.get(("c", mid), _LIVE))
            for mid, eid in list(self.todo_subs.items()):
                if eid in todo_touched:
                    self.event(mid, {"items": todo_lists.get(eid, self.hub.todos[eid])})
        except OSError:
            pass

    def push_template(self, mid, sub, first=False, at=None):
        try:
            if at is None or at is _LIVE:
                text, seen = render_template(self.hub, sub[0])
            elif isinstance(at, Exception):
                raise at
            else:
                text, seen = at
        except Exception as e:     # any failure is this subscription's alone, never the service caller's
            if not isinstance(e, TemplateError):
                e = TemplateError(repr(e))
            if first:
                self.event(mid, {"error": str(e), "level": "ERROR"})
            return
        if first or text != sub[1]:
            sub[1] = text
            self.event(mid, {"result": parse_result(text), "listeners": {"all": False, "domains": [], "entities": seen, "time": False}})

    def push_condition(self, mid, sub, first=False, at=None):
        try:
            if at is None or at is _LIVE:
                ok = eval_condition(self.hub, sub[0])
            elif isinstance(at, Exception):
                raise at
            else:
                ok = at
        except Exception as e:     # any failure is this subscription's alone
            if not isinstance(e, TemplateError):
                e = TemplateError(repr(e))
            if first:
                self.event(mid, {"error": {"code": "invalid_format", "message": str(e)}})
            return
        if first or ok != sub[1]:
            sub[1] = ok
            self.event(mid, {"result": ok})

    def run(self):
        self.send({"type": "auth_required", "ha_version": HA_VERSION})
        op, data = ws_read(self.sock)
        if json.loads(data).get("type") != "auth":
            return
        self.send({"type": "auth_ok", "ha_version": HA_VERSION})
        with self.hub.lock:
            self.hub.conns.add(self)
        try:
            while True:
                op, data = ws_read(self.sock)
                if op == 8:
                    return
                if op == 9:
                    with self.send_lock:
                        self.sock.sendall(ws_frame(data, 10))
                    continue
                if op != 1:
                    continue
                msgs = json.loads(data)
                for m in msgs if isinstance(msgs, list) else [msgs]:
                    self.handle(m)
        finally:
            with self.hub.lock:
                self.hub.conns.discard(self)

    def handle(self, m):
        mid, t = m.get("id"), m.get("type")
        # The command and its id only: paths and service data can carry tokens (auth/sign_path).
        self.hub.record({"t": time.time(), "type": t, "id": mid})
        h = HANDLERS.get(t)
        if h is None:
            if t not in self.hub.unknown:
                self.hub.unknown.add(t)
                print(f"showcase_ha: unknown websocket command {t!r}", file=sys.stderr, flush=True)
            return self.error(mid, "unknown_command", "Unknown command.")
        try:
            h(self, m)
        except ServiceError as e:
            self.error(mid, "service_not_found" if str(e) == "service_not_found" else "home_assistant_error", str(e))
        except OSError:
            raise
        except Exception as e:      # answer, never drop the connection: the frontend would reconnect forever
            self.error(mid, "home_assistant_error", repr(e))


def _const(value):
    return lambda c, m: c.result(m["id"], value() if callable(value) else value)


def _sub_entities(c, m):
    wanted = set(m["entity_ids"]) if m.get("entity_ids") else None
    c.result(m["id"])
    with c.hub.lock:
        snap = {e: Hub.compressed(s) for e, s in c.hub.states.items() if wanted is None or e in wanted}
        # Sent before the hub lock goes: no delta for this subscription can overtake its snapshot.
        c.event(m["id"], {"a": snap})
        c.entity_subs[m["id"]] = wanted


def _sub_events(c, m):
    c.event_subs[m["id"]] = m.get("event_type")
    c.result(m["id"])


def _unsub(c, m):
    sub = m.get("subscription")
    for table in (c.entity_subs, c.event_subs, c.template_subs, c.todo_subs, c.cond_subs):
        table.pop(sub, None)
    c.forecast_subs.discard(sub)
    c.result(m["id"])


def _targets(hub, m):
    ids = []
    domain = m.get("domain")
    for src in (m.get("target") or {}, m.get("service_data") or {}):
        v = src.get("entity_id")
        ids += [v] if isinstance(v, str) else list(v or [])
        area = src.get("area_id")
        for a in [area] if isinstance(area, str) else list(area or []):
            # An area expands to its entities of the service's domain (all of them for `homeassistant`).
            ids += [e for e in hub.areas.get(a, []) if domain in ("homeassistant", None) or domain_of(e) == domain]
    return [e for k, e in enumerate(ids) if e not in ids[:k]]


def _call_service(c, m):
    domain, service = m["domain"], m["service"]
    data = {k: v for k, v in (m.get("service_data") or {}).items() if k != "entity_id"}
    ids = _targets(c.hub, m)
    if not ids and domain not in SERVICES:
        return c.error(m["id"], "service_not_found", f"Service {domain}.{service} not found.")
    response = c.hub.call_service(domain, service, data, ids)
    out = {"context": {"id": c.hub.context(), "parent_id": None, "user_id": USER["id"]}}
    if m.get("return_response"):
        out["response"] = response or {}
    c.result(m["id"], out)


def _history_stream(c, m):
    c.result(m["id"])
    pts, t0, t1 = history(c.hub, m.get("entity_ids") or [], m.get("start_time"), m.get("end_time"), m.get("no_attributes", False))
    ev = {"states": pts, "start_time": t0}
    if m.get("end_time"):
        ev["end_time"] = t1
    c.event(m["id"], ev)


def _history_period(c, m):
    pts, _, _ = history(c.hub, m.get("entity_ids") or [], m.get("start_time"), m.get("end_time"), m.get("no_attributes", False))
    c.result(m["id"], pts)


def _render_template(c, m):
    sub = [m["template"], None]
    c.result(m["id"])
    c.template_subs[m["id"]] = sub
    c.push_template(m["id"], sub, first=True)


def _sub_condition(c, m):
    sub = [m["condition"], None]
    c.result(m["id"])
    c.cond_subs[m["id"]] = sub
    c.push_condition(m["id"], sub, first=True)


def _test_condition(c, m):
    try:
        c.result(m["id"], {"result": eval_condition(c.hub, m["condition"])})
    except TemplateError as e:
        c.error(m["id"], "invalid_format", str(e))


def _todo_list(c, m):
    items = c.hub.todos.get(m.get("entity_id"))
    if items is None:
        return c.error(m["id"], "not_found", "Entity not found")
    c.result(m["id"], {"items": items})


def _todo_subscribe(c, m):
    eid = m.get("entity_id")
    if eid not in c.hub.todos:
        return c.error(m["id"], "not_found", "Entity not found")
    c.result(m["id"])
    c.todo_subs[m["id"]] = eid
    c.event(m["id"], {"items": c.hub.todos[eid]})


def _forecast(c, m):
    c.result(m["id"])
    c.forecast_subs.add(m["id"])
    c.event(m["id"], forecast())


def _logbook_get_events(c, m):
    c.result(m["id"], logbook_events(m.get("entity_ids")))


def _logbook_stream(c, m):
    """logbook/event_stream (what the real frontend's logbook card subscribes to): the same events, once."""
    c.result(m["id"])
    now = time.time()
    c.event(m["id"], {"events": logbook_events(m.get("entity_ids"), now), "start_time": now - 86400, "end_time": now})


def _registry_get(c, m):
    ent = entity_registry().get(m.get("entity_id"))
    if not ent:
        return c.error(m["id"], "not_found", "Entity not found")
    c.result(m["id"], registry_entry(m["entity_id"], ent, c.hub.states[m["entity_id"]]))


def _camera_capabilities(c, m):
    if m.get("entity_id") not in c.hub.states or domain_of(m["entity_id"]) != "camera":
        return c.error(m["id"], "not_found", "Camera not found")
    c.result(m["id"], {"frontend_stream_types": ["hls"] if c.hub.hls else [], "two_way_audio": False})


def _camera_stream(c, m):
    if not c.hub.hls:
        return c.error(m["id"], "start_stream_failed", "The camera has no stream (start the showcase with --hls)")
    c.result(m["id"], {"url": "/api/hls/vitro/master_playlist.m3u8"})


HANDLERS = {
    "supported_features": _const(None), "ping": lambda c, m: c.send({"id": m["id"], "type": "pong"}),
    "subscribe_entities": _sub_entities, "subscribe_events": _sub_events, "unsubscribe_events": _unsub,
    "get_config": lambda c, m: c.result(m["id"], config(c.hub.hls)), "get_services": lambda c, m: c.result(m["id"], services_catalog()),
    "get_panels": _const(panels),
    "get_states": lambda c, m: c.result(m["id"], [c.hub.state_object(s) for s in c.hub.states.values()]),
    "auth/current_user": _const(USER), "frontend/get_themes": _const(themes),
    "frontend/get_translations": lambda c, m: c.result(m["id"], {"resources": translations(m.get("category"))}),
    "frontend/get_icons": lambda c, m: c.result(m["id"], {"resources": icons(m.get("category"))}),
    "frontend/get_user_data": _const({"value": None}), "frontend/set_user_data": _const(None),
    "frontend/subscribe_user_data": lambda c, m: (c.result(m["id"]), c.event(m["id"], {"value": None})),
    "frontend/subscribe_system_data": lambda c, m: (c.result(m["id"]), c.event(m["id"], {"value": None})),
    "config/entity_registry/list_for_display": lambda c, m: c.result(m["id"], {
        "entity_categories": {"0": "config", "1": "diagnostic"},
        "entities": [{"ei": e, "pl": v["platform"], "ai": v["area_id"]} for e, v in entity_registry().items()]}),
    "config/entity_registry/list": lambda c, m: c.result(m["id"], [registry_entry(e, v, c.hub.states[e]) for e, v in entity_registry().items()]),
    "config/entity_registry/get": _registry_get,
    "config/device_registry/list": _const([]), "config/area_registry/list": _const(AREA_REGISTRY),
    "config/floor_registry/list": _const(FLOORS), "config/label_registry/list": _const(LABELS),
    "repairs/list_issues": _const({"issues": []}),
    "recorder/info": _const({"backlog": 0, "db_in_default_location": True, "max_backlog": 40000, "migration_in_progress": False,
                             "migration_is_live": False, "recording": True, "thread_running": True}),
    "brands/access_token": _const({"token": "vitro"}), "lovelace/info": _const({"resource_mode": "storage"}),
    "persistent_notification/subscribe": lambda c, m: (c.result(m["id"]), c.event(m["id"], {"type": "current", "notifications": {}})),
    "labs/subscribe": lambda c, m: (c.result(m["id"]), c.event(m["id"], {
        "preview_feature": m.get("preview_feature"), "domain": m.get("domain"), "enabled": False, "is_built_in": True,
        "feedback_url": None, "learn_more_url": None, "report_issue_url": None})),
    "auth/sign_path": lambda c, m: c.result(m["id"], {"path": f"{m.get('path', '')}{'&' if '?' in m.get('path', '') else '?'}authSig=vitro"}),
    "lovelace/dashboards/list": _const(dashboards_list),
    "lovelace/resources": lambda c, m: c.result(m["id"], resources(c.hub.extra_resources)),
    "call_service": _call_service, "history/stream": _history_stream, "history/history_during_period": _history_period,
    "recorder/statistics_during_period": lambda c, m: c.result(m["id"], statistics(
        c.hub, m.get("statistic_ids"), m.get("start_time"), m.get("end_time"), m.get("period"))),
    "recorder/get_statistics_metadata": lambda c, m: c.result(m["id"], statistics_metadata(c.hub, m.get("statistic_ids"))),
    "recorder/list_statistic_ids": lambda c, m: c.result(m["id"], statistics_metadata(c.hub, None)),
    "subscribe_condition": _sub_condition, "test_condition": _test_condition, "render_template": _render_template, "todo/item/list": _todo_list, "todo/item/subscribe": _todo_subscribe,
    "weather/subscribe_forecast": _forecast, "camera/capabilities": _camera_capabilities, "camera/stream": _camera_stream,
    "camera/get_prefs": _const({"preload_stream": False, "orientation": 1}),
    "logbook/get_events": _logbook_get_events, "logbook/event_stream": _logbook_stream,
}


def _lovelace_config(c, m):
    if m.get("url_path") not in (None, DASHBOARD):
        return c.error(m["id"], "config_not_found", "No config found.")
    c.result(m["id"], lovelace_config())


HANDLERS["lovelace/config"] = _lovelace_config


# ---- the camera: a moving colour-bar still, and the same as a multipart stream

BARS = [(235, 235, 235), (235, 235, 16), (16, 235, 235), (16, 235, 16), (235, 16, 235), (235, 16, 16), (16, 16, 235), (16, 16, 16)]
CAM_W, CAM_H = 320, 180


def _chunk(t, d):
    return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)


@functools.lru_cache(maxsize=256)
def camera_png(frame):
    """Frame `frame` of the colour bars: eight bars that scroll 8 px a frame, with a white marker bar sweeping the bottom."""
    shift = (frame * 8) % CAM_W
    base = bytearray()
    for x in range(CAM_W):
        base += bytes(BARS[(((x + shift) % CAM_W) * len(BARS)) // CAM_W])
    marker = (frame * 12) % (CAM_W - 40)
    mark_row = bytearray(base)
    mark_row[marker * 3:(marker + 40) * 3] = b"\xff\xff\xff" * 40
    rows = b"".join(b"\0" + bytes(mark_row if y >= CAM_H - 24 else base) for y in range(CAM_H))
    return (b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", struct.pack(">IIBBBBB", CAM_W, CAM_H, 8, 2, 0, 0, 0))
            + _chunk(b"IDAT", zlib.compress(rows, 1)) + _chunk(b"IEND", b""))


def now_frame():
    return int(time.time() * 4)


# ---- the floorplan: a made-up studio plan for the picture-elements card (stdlib only, the same bytes every time)

PLAN_W, PLAN_H = 800, 480
_GLYPHS = {
    "S": (" ####", "#    ", "#    ", " ### ", "    #", "    #", "#### "),
    "T": ("#####", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "  #  "),
    "U": ("#   #", "#   #", "#   #", "#   #", "#   #", "#   #", " ### "),
    "D": ("#### ", "#   #", "#   #", "#   #", "#   #", "#   #", "#### "),
    "I": ("#####", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "#####"),
    "O": (" ### ", "#   #", "#   #", "#   #", "#   #", "#   #", " ### "),
    "L": ("#    ", "#    ", "#    ", "#    ", "#    ", "#    ", "#####"),
    "N": ("#   #", "##  #", "# # #", "#  ##", "#   #", "#   #", "#   #"),
    "G": (" ### ", "#   #", "#    ", "# ###", "#   #", "#   #", " ### "),
    "E": ("#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#####"),
    "R": ("#### ", "#   #", "#   #", "#### ", "# #  ", "#  # ", "#   #"),
    "Y": ("#   #", "#   #", " # # ", "  #  ", "  #  ", "  #  ", "  #  "),
}


def floorplan_png():
    """An 800x480 slate-blue plan: outer walls, two partitions with door gaps, windows, furniture blocks, three room labels."""
    outside, floor, grid = (233, 238, 246), (247, 249, 253), (234, 239, 247)
    wall, door, window = (71, 85, 105), (147, 170, 214), (186, 210, 238)
    fill, edge, ink = (190, 203, 223), (148, 163, 184), (100, 116, 139)
    img = [bytearray(bytes(outside) * PLAN_W) for _ in range(PLAN_H)]

    def rect(x0, y0, x1, y1, color):
        run = bytes(color) * (x1 - x0)
        for y in range(y0, y1):
            img[y][x0 * 3:x1 * 3] = run

    def block(x0, y0, x1, y1):
        rect(x0, y0, x1, y1, edge)
        rect(x0 + 2, y0 + 2, x1 - 2, y1 - 2, fill)

    def text(x, y, word, scale=3):
        for n, ch in enumerate(word):
            for r, row in enumerate(_GLYPHS[ch]):
                for c, bit in enumerate(row):
                    if bit == "#":
                        rect(x + (n * 6 + c) * scale, y + r * scale, x + (n * 6 + c + 1) * scale, y + (r + 1) * scale, ink)

    rect(48, 48, 752, 432, floor)
    for x in range(80, 752, 40):
        rect(x, 48, x + 1, 432, grid)
    for y in range(80, 432, 40):
        rect(48, y, 752, y + 1, grid)
    rect(40, 40, 760, 48, wall)         # outer walls, 8 px thick
    rect(40, 432, 760, 440, wall)
    rect(40, 40, 48, 440, wall)
    rect(752, 40, 760, 440, wall)
    rect(476, 48, 484, 300, wall)       # the studio's wall, with a doorway to the lounge
    rect(476, 360, 484, 432, wall)
    rect(484, 236, 580, 244, wall)      # the entry's wall, with a doorway
    rect(640, 236, 752, 244, wall)
    rect(600, 40, 680, 48, door)        # the front door
    rect(40, 150, 48, 270, window)      # two windows
    rect(120, 432, 300, 440, window)
    block(180, 150, 360, 230)           # desk
    block(240, 245, 290, 285)           # chair
    block(380, 52, 470, 84)             # shelf
    block(56, 56, 96, 96)               # plant
    block(500, 52, 580, 84)             # entry bench
    block(520, 380, 720, 424)           # sofa
    block(570, 310, 670, 350)           # coffee table
    text(64, 404, "STUDIO")
    text(500, 252, "LOUNGE")
    text(500, 200, "ENTRY")
    rows = b"".join(b"\0" + bytes(r) for r in img)
    return (b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", struct.pack(">IIBBBBB", PLAN_W, PLAN_H, 8, 2, 0, 0, 0))
            + _chunk(b"IDAT", zlib.compress(rows, 9)) + _chunk(b"IEND", b""))


LOCAL_FILES["vitro-demo/floorplan.png"] = ("image/png", floorplan_png())


# ---- HTTP

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    @property
    def hub(self):
        return self.server.hub

    def send_bytes(self, code, body, ctype, cache="no-store", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache)
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                self.close_connection = True          # the browser went away mid-file; nothing to say

    def send_json(self, obj, code=200):
        self.send_bytes(code, json.dumps(obj).encode(), "application/json", extra={"Access-Control-Allow-Origin": "*"})

    def do_HEAD(self):
        self.do_GET()

    def do_OPTIONS(self):
        self.send_response(204)
        for k, v in (("Access-Control-Allow-Origin", "*"), ("Access-Control-Allow-Headers", "authorization, content-type"),
                     ("Access-Control-Allow-Methods", "GET, POST, OPTIONS"), ("Content-Length", "0")):
            self.send_header(k, v)
        self.end_headers()

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        path, q = url.path, urllib.parse.parse_qs(url.query)
        if path == "/api/websocket":
            return self.websocket()
        if path == "/auth/authorize":
            # HA's sign-in, without its login page: straight back to the frontend with a code
            back = q.get("redirect_uri", [""])[0]
            if not back or any(ord(ch) < 0x20 or ord(ch) == 0x7F for ch in back):
                return self.send_bytes(400, b"bad redirect_uri", "text/plain")
            loc = f"{back}{'&' if '?' in back else '?'}code=vitro&state={urllib.parse.quote(q.get('state', [''])[0])}&storeToken=true"
            self.send_response(302)
            self.send_header("Location", loc)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if path == "/manifest.json":
            return self.send_bytes(200, json.dumps(MANIFEST).encode(), "application/manifest+json")
        if path == "/api/" or path == "/api":
            return self.send_json({"message": "API running."})
        if path == "/api/config":
            return self.send_json(config(self.hub.hls))
        if path == "/api/states":
            with self.hub.lock:
                return self.send_json([self.hub.state_object(s) for s in self.hub.states.values()])
        if path.startswith("/api/states/"):
            st = self.hub.states.get(path[len("/api/states/"):])
            return self.send_json(self.hub.state_object(st)) if st else self.send_json({"message": "Entity not found."}, 404)
        if path == "/api/history/period" or path.startswith("/api/history/period/"):
            start = urllib.parse.unquote(path[len("/api/history/period/"):]) or None
            # a "+00:00" offset in the query arrives as a space once decoded
            end = (q.get("end_time") or [None])[0]
            ids = [e for part in q.get("filter_entity_id", []) for e in part.split(",") if e]
            try:
                with self.hub.lock:
                    body = rest_history(self.hub, start and start.replace(" ", "+"), end and end.replace(" ", "+"), ids,
                                        "no_attributes" in urllib.parse.parse_qs(url.query, keep_blank_values=True))
            except (ValueError, AttributeError):
                return self.send_json({"message": "Invalid datetime"}, 400)
            return self.send_json(body)
        if path == "/api/calendars":
            return self.send_json([{"entity_id": e, "name": s["attributes"]["friendly_name"]}
                                   for e, s in self.hub.states.items() if domain_of(e) == "calendar"])
        if path.startswith("/api/calendars/"):
            eid = path[len("/api/calendars/"):]
            if domain_of(eid) != "calendar" or eid not in self.hub.states:
                return self.send_json({"message": "Entity not found."}, 404)
            return self.send_json(calendar_events())
        if path.startswith("/api/camera_proxy_stream/"):
            return self.camera_stream(path[len("/api/camera_proxy_stream/"):])
        if path.startswith("/api/camera_proxy/"):
            if path[len("/api/camera_proxy/"):] not in self.hub.states:
                return self.send_bytes(404, b"", "text/plain")
            frame = int(q["frame"][0]) if q.get("frame", [""])[0].isdigit() else now_frame()
            return self.send_bytes(200, camera_png(frame), "image/png")
        if path.startswith("/api/hls/"):
            return self.hls(path)
        if path.startswith("/local/"):
            return self.local(path[len("/local/"):])
        if path.startswith(("/frontend_latest/", "/frontend_es5/", "/static/")) or path in ("/service_worker.js", "/sw-modern.js", "/sw-legacy.js"):
            return self.static(path)
        if path.startswith(("/api/", "/auth/", "/hacsfiles/")):
            return self.send_bytes(404, b"", "text/plain")
        return self.send_bytes(200, self.server.index, "text/html; charset=utf-8")      # every other path is a frontend route

    def do_POST(self):
        url = urllib.parse.urlsplit(self.path)
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        if url.path == "/auth/token":
            body = urllib.parse.parse_qs(raw.decode())
            tok = {"access_token": "vitro-access", "token_type": "Bearer", "expires_in": 86400 * 365}
            if (body.get("grant_type") or [""])[0] == "authorization_code":
                tok["refresh_token"] = "vitro-refresh"
            return self.send_bytes(200, json.dumps(tok).encode(), "application/json")
        if url.path == "/auth/revoke":
            return self.send_bytes(200, b"", "text/plain")
        m = re.fullmatch(r"/api/services/([a-z_]+)/([a-z_]+)", url.path)
        if m:
            try:
                data = json.loads(raw or b"{}")
                ids = data.pop("entity_id", [])
                ids = [ids] if isinstance(ids, str) else list(ids)
                response = self.hub.call_service(m.group(1), m.group(2), data, ids)
            except (ServiceError, ValueError, KeyError) as e:
                return self.send_json({"message": str(e)}, 400)
            with self.hub.lock:
                return self.send_json({"service_response": response} if response else [self.hub.state_object(self.hub.states[e]) for e in ids])
        return self.send_bytes(404, b"", "text/plain")

    def local(self, name):
        if name in LOCAL_FILES:
            ctype, body = LOCAL_FILES[name]
        else:
            base = self.server.local_dir
            full = os.path.realpath(os.path.join(base, name)) if base else None
            if not full or not full.startswith(os.path.realpath(base) + os.sep) or not os.path.isfile(full):
                return self.send_bytes(404, b"", "text/plain")
            with open(full, "rb") as f:
                body = f.read()
            ctype = "application/javascript" if full.endswith(".js") else mimetypes.guess_type(full)[0] or "application/octet-stream"
        return self.send_bytes(200, body, ctype, extra={"Access-Control-Allow-Origin": "*"})

    def static(self, path):
        front = self.server.front
        full = os.path.realpath(os.path.join(front, path.lstrip("/")))
        if not full.startswith(front + os.sep) or not os.path.isfile(full):
            return self.send_bytes(404, b"", "text/plain")
        with open(full, "rb") as f:
            body = f.read()
        ctype = "application/javascript" if full.endswith(".js") else mimetypes.guess_type(full)[0] or "application/octet-stream"
        cache = "public, max-age=31536000" if path.startswith(("/frontend_", "/static/")) else "no-cache"
        return self.send_bytes(200, body, ctype, cache=cache)

    def camera_stream(self, entity_id):
        """MJPEG-style multipart/x-mixed-replace of the moving bars (PNG parts), four frames a second."""
        if entity_id not in self.hub.states:
            return self.send_bytes(404, b"", "text/plain")
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        if self.command == "HEAD":
            return
        try:
            for _ in range(2400):         # ten minutes at most
                png = camera_png(now_frame())
                self.wfile.write(b"--frame\r\nContent-Type: image/png\r\nContent-Length: " + str(len(png)).encode() + b"\r\n\r\n" + png + b"\r\n")
                self.wfile.flush()
                time.sleep(0.25)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass

    def hls(self, path):
        """--hls: tools/perf/camera.py's 360p loop as one VOD playlist (no live window)."""
        if not self.hub.hls:
            return self.send_bytes(404, b"", "text/plain")
        name = path[len("/api/hls/vitro/"):] if path.startswith("/api/hls/vitro/") else ""
        if name == "master_playlist.m3u8":
            prof = camera.PROFILES["360p"]
            w, h = prof["size"].split("x")
            body = (f"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH={int(prof['bitrate'][:-1]) * 1000},CODECS=\"avc1.4d401f\","
                    f"RESOLUTION={w}x{h},FRAME-RATE={camera.FPS}\nplaylist.m3u8\n")
            return self.send_bytes(200, body.encode(), "application/vnd.apple.mpegurl")
        if name == "playlist.m3u8":
            lines = ["#EXTM3U", "#EXT-X-VERSION:7", f"#EXT-X-TARGETDURATION:{camera.SEGMENT_S}", "#EXT-X-MEDIA-SEQUENCE:0",
                     "#EXT-X-PLAYLIST-TYPE:VOD", '#EXT-X-MAP:URI="init.mp4"']
            for n in range(camera.segments("360p")):
                lines += [f"#EXTINF:{camera.SEGMENT_S:.6f},", f"segment/{n}.m4s"]
            return self.send_bytes(200, ("\n".join(lines + ["#EXT-X-ENDLIST"]) + "\n").encode(), "application/vnd.apple.mpegurl")
        m = re.fullmatch(r"init\.mp4|segment/(\d{1,5})\.m4s", name)
        if not m:
            return self.send_bytes(404, b"", "text/plain")
        try:
            d = os.path.join(camera.root(), "360p")
        except camera.Unavailable as e:
            return self.send_bytes(500, str(e).encode(), "text/plain")
        f = "init.mp4" if name == "init.mp4" else f"seg{int(m.group(1)) % camera.segments('360p'):03d}.m4s"
        with open(os.path.join(d, f), "rb") as fh:
            return self.send_bytes(200, fh.read(), "video/mp4" if f.endswith(".mp4") else "video/iso.segment")

    def websocket(self):
        key = self.headers.get("Sec-WebSocket-Key")
        if not key:
            return self.send_bytes(400, b"", "text/plain")
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", ws_accept(key))
        self.end_headers()
        self.wfile.flush()
        sock = self.connection
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        try:
            Conn(self.hub, sock).run()
        except (ConnectionError, OSError, ValueError):
            pass
        self.close_connection = True


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 256         # the frontend fetches dozens of modules at once

    def handle_error(self, request, client_address):
        if not isinstance(sys.exc_info()[1], ConnectionError):      # a browser that went away is not news
            super().handle_error(request, client_address)


def serve(port=8091, hls=False, local_dir=None, extra_resources=(), log_path=None):
    """The server, bound to 127.0.0.1 (port 0: any free port; read it from `srv.server_address`). `srv.hub` is the home."""
    front = os.path.realpath(hafrontend.root())
    srv = Server(("127.0.0.1", port), Handler)
    srv.front = front
    srv.index = render_index(front, None)
    srv.local_dir = local_dir
    srv.hub = Hub(hls, extra_resources, open(log_path, "a") if log_path else None)
    return srv


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8091)
    ap.add_argument("--hls", action="store_true", help="serve the cameras' H.264 as HLS too (needs ffmpeg with libx264)")
    ap.add_argument("--local-dir", help="serve this folder at /local/ too (a HACS bundle, say)")
    ap.add_argument("--resource", action="append", default=[], help="add a Lovelace resource URL (repeatable)")
    ap.add_argument("--log", help="append every websocket command to this file (JSON lines)")
    a = ap.parse_args()
    srv = serve(a.port, a.hls, a.local_dir, a.resource, a.log)
    base = f"http://127.0.0.1:{srv.server_address[1]}"
    print(f"Vitro showcase Home Assistant {HA_VERSION}, frontend {hafrontend.VERSION}, on {base}", flush=True)
    for view in VIEWS:
        print(f"  {base}/{DASHBOARD}/{view}", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
