"""The parity harness's home for Clarity's fake Home Assistant (MIT): a fixture-driven HA for Vitro's parity corpus
(#793), so every reference is HA's real, pinned frontend drawing fixed, made-up states, with nobody's real HA behind it.

It is a home module for tools/perf/fakeha.py (`serve(home=fakehome)`, like tools/showcase/home.py). Clarity's own
home is untouched. capture.mjs runs it (`capture.mjs capture`, the committed corpus's default) and drives it over
HTTP on 127.0.0.1:

  POST /vitro-parity/home   {"now", "config", "themes", "user", "fixtures_dir", "dashboard"}: the shared inputs
                            (the corpus's home.json), the harness dashboard (one view per case) and where the
                            corpus's fixtures/ folder is (served at /local/vitro-harness-fixtures/)
  POST /vitro-parity/case   {"id", "fixture"}: the case whose fixture the home serves from now on (its states,
                            registries, history, statistics, to-do items, logbook, forecast, calendar events)

What it answers, all from that fixture and the pinned data next to this file:
  - states (subscribe_entities, get_states), the entity, device and area registries, users (config/auth/list);
  - history/stream, history/history_during_period and REST /api/history/period, recorder statistics and their
    metadata, todo/item/subscribe, logbook/event_stream, weather/subscribe_forecast, calendar/event/subscribe;
  - render_template, with jinja2 and the HA helpers the corpus uses (states, is_state, state_attr, ...);
  - HA core's entity texts and icons (hacore-<version>.json, from the homeassistant wheel: hacore.py), in the language
    the page asks for, and the signed-in user's frontend profile (frontend/get_user_data, subscribe_user_data);
  - the community cards as Lovelace resources at /hacsfiles/ (cards.json, pinned by SHA-256: cards.py).

Times in a fixture are relative to the home's fixed "now" (home.json), so a capture never depends on the day it
runs: a number is seconds from now (`-3600`), a string "@<spec>" (in an attribute or a state) is resolved to an ISO
time ("@-2h", "@+30m", "@-1d"), a local date ("@D+1"), or a local date and time ("@D+1 14:30"). See
tools/vitro-parity/README.md.
"""
import datetime
import hashlib
import json
import math
import os
import re
import sys
import zoneinfo

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "perf"))
import dashboards as _clarity  # noqa: E402
import cards as _cards  # noqa: E402

DASHBOARD = "vitro-harness"
HA_VERSION = "2026.11.0"
FIXTURE_PREFIX = "/local/vitro-harness-fixtures/"
with open(os.path.join(HERE, f"hacore-{HA_VERSION}.json")) as _f:
    HACORE = json.load(_f)
CARDS = _cards.load()

# The home's shared inputs (POST /vitro-parity/home) and the current case (POST /vitro-parity/case).
HOME = {"now": 1794500880.0, "config": {}, "themes": {"themes": {}, "default_theme": "default", "default_dark_theme": None},
        "user": {"id": "parity-user", "name": "Parity"}, "fixtures_dir": None, "dashboard": {"views": []}}
CASE = {"id": None, "fixture": {}}
NOW = HOME["now"]


def __getattr__(name):
    """What this home doesn't set is Clarity's (context ids, manifest, the camera bits it never uses)."""
    return getattr(_clarity, name)


def tz():
    return zoneinfo.ZoneInfo(HOME["config"].get("time_zone") or "UTC")


# ---- times

_UNITS = {"s": 1, "m": 60, "h": 3600, "d": 86400, "w": 604800}


def offset(spec):
    """Seconds from now for "-2h", "+1d12h", "-90s", "0"."""
    s = spec.strip()
    if re.fullmatch(r"[+-]?\d+(\.\d+)?", s):
        return float(s)
    m = re.fullmatch(r"([+-]?)((?:\d+(?:\.\d+)?[smhdw])+)", s)
    if not m:
        raise ValueError(f"not a time offset: {spec!r}")
    total = sum(float(n) * _UNITS[u] for n, u in re.findall(r"(\d+(?:\.\d+)?)([smhdw])", m.group(2)))
    return total if m.group(1) == "+" else -total


def at(spec):
    """An epoch second: a number (seconds from now) or an offset string."""
    if spec is None:
        return None
    if isinstance(spec, (int, float)):
        return NOW + spec
    return NOW + offset(str(spec))


def iso(t):
    return datetime.datetime.fromtimestamp(t, datetime.timezone.utc).isoformat()


def resolve_state(v):
    """A fixture state: an "@" time ("@+3h", a timestamp sensor's) resolved as [resolve] does; any other text, one that
    merely starts with "@" ("@standby") included, stays as written."""
    if isinstance(v, str) and v.startswith("@"):
        try:
            return resolve(v)
        except ValueError:
            return v
    return v


def resolve(v):
    """A fixture value with its "@" times resolved: "@-2h" -> ISO UTC, "@D+1" -> local date, "@D+1 14:30" -> local ISO."""
    if isinstance(v, dict):
        return {k: resolve(x) for k, x in v.items()}
    if isinstance(v, list):
        return [resolve(x) for x in v]
    if isinstance(v, str) and v.startswith("@"):
        spec = v[1:]
        m = re.fullmatch(r"D([+-]\d+)(?: (\d\d):(\d\d)(?::(\d\d))?)?", spec)
        if m:
            day = datetime.datetime.fromtimestamp(NOW, tz()).date() + datetime.timedelta(days=int(m.group(1)))
            if m.group(2) is None:
                return day.isoformat()
            return datetime.datetime(day.year, day.month, day.day, int(m.group(2)), int(m.group(3)), int(m.group(4) or 0),
                                     tzinfo=tz()).isoformat()
        return iso(at(spec))
    return v


def parse_time(x):
    """An ISO time from the frontend (or epoch seconds) as epoch seconds."""
    if x is None:
        return None
    if isinstance(x, (int, float)):
        return float(x)
    return datetime.datetime.fromisoformat(str(x).replace("Z", "+00:00")).timestamp()


# ---- the shared inputs and the current case

def set_home(doc):
    global NOW
    HOME.update(doc)
    t = doc.get("now")
    if isinstance(t, str):
        dt = datetime.datetime.fromisoformat(t)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=tz())
        HOME["now"] = dt.timestamp()
    NOW = float(HOME["now"])


def fixture():
    return CASE["fixture"]


def set_case(case_id, fx, hub):
    CASE["id"], CASE["fixture"] = case_id, fx or {}
    fresh = states()
    with hub.lock:
        hub.states.clear()
        hub.states.update(fresh)


def states():
    """The fixture's states. An entity with a history series takes its state, last change and last update (and, for a
    series with attributes of its own, its attributes) from that series, so its state, history and statistics are one
    timeline (the corpus test checks the fixture says the same)."""
    out = {}
    for eid, st in (fixture().get("states") or {}).items():
        changed = at(st.get("last_changed", -3600))
        # A timestamp sensor's state may be an "@" time too ("@+3h"), resolved as attributes are.
        state, attrs = str(resolve_state(st["state"])), resolve(st.get("attributes") or {})
        updated = at(st["last_updated"]) if "last_updated" in st else changed
        s = _series(eid)
        if s:
            state, changed, updated = s.value(NOW), s.last_change(NOW), s.last_update(NOW)
            own = s.attributes(NOW)
            if own is not None:
                attrs = dict(attrs, **own)
        out[eid] = {"entity_id": eid, "state": state, "attributes": attrs, "last_changed": changed, "last_updated": updated,
                    "context_id": "01JPAR1TY" + hashlib.sha1(eid.encode()).hexdigest()[:17].upper()}
    return out


def state_objects(sts):
    return _clarity.state_objects(sts)


# ---- HA's config, panels and services

def _domains():
    return sorted({k for k, v in HACORE["translations"].items() if "entity_component" in v})


def config():
    c = HOME["config"]
    platforms = {e.get("pl") for e in fixture().get("entities") or [] if e.get("pl")}
    return dict(_clarity.config(), version=HA_VERSION, location_name=c.get("location_name", "Home"),
                time_zone=c.get("time_zone", "UTC"), unit_system=c.get("unit_system", _clarity.config()["unit_system"]),
                language=c.get("language", "en"), country=c.get("country", "US"), currency=c.get("currency", "USD"),
                latitude=c.get("latitude", 40.0), longitude=c.get("longitude", -100.0),
                components=sorted(set(_domains()) | platforms | {"frontend", "lovelace", "history", "recorder", "logbook",
                                                                 "http", "api", "websocket_api", "config", "auth", "stream"}))


_SERVICES = {
    "light": ["turn_on", "turn_off", "toggle"], "switch": ["turn_on", "turn_off", "toggle"],
    "fan": ["turn_on", "turn_off", "toggle", "set_percentage", "oscillate", "set_direction", "set_preset_mode"],
    "cover": ["open_cover", "close_cover", "stop_cover", "toggle", "set_cover_position", "open_cover_tilt", "close_cover_tilt"],
    "climate": ["turn_on", "turn_off", "set_temperature", "set_hvac_mode", "set_fan_mode", "set_preset_mode", "set_humidity"],
    "humidifier": ["turn_on", "turn_off", "toggle", "set_humidity", "set_mode"],
    "water_heater": ["turn_on", "turn_off", "set_temperature", "set_operation_mode", "set_away_mode"],
    "lock": ["lock", "unlock", "open"], "media_player": ["turn_on", "turn_off", "media_play", "media_pause",
                                                       "media_play_pause", "media_next_track", "media_previous_track",
                                                       "volume_set", "volume_mute", "select_source"],
    "alarm_control_panel": ["alarm_arm_home", "alarm_arm_away", "alarm_arm_night", "alarm_arm_vacation",
                            "alarm_arm_custom_bypass", "alarm_disarm", "alarm_trigger"],
    "vacuum": ["start", "pause", "stop", "return_to_base", "locate", "clean_spot", "set_fan_speed"],
    "input_boolean": ["turn_on", "turn_off", "toggle"], "input_number": ["set_value", "increment", "decrement"],
    "input_select": ["select_option", "select_next", "select_previous"], "select": ["select_option"],
    "number": ["set_value"], "button": ["press"], "input_button": ["press"], "scene": ["turn_on"],
    "script": ["turn_on", "turn_off", "toggle"], "automation": ["turn_on", "turn_off", "toggle", "trigger"],
    "todo": ["add_item", "update_item", "remove_item"], "valve": ["open_valve", "close_valve", "set_valve_position"],
    "siren": ["turn_on", "turn_off", "toggle"], "remote": ["turn_on", "turn_off", "toggle"],
    "lawn_mower": ["start_mowing", "pause", "dock"], "update": ["install", "skip"], "weather": ["get_forecasts"],
    "calendar": ["get_events", "create_event"], "homeassistant": ["turn_on", "turn_off", "toggle"],
}


def services():
    s = {"name": "", "description": "", "fields": {}, "target": {"entity": [{}]}}
    return {d: {n: s for n in ns} for d, ns in _SERVICES.items()}


def panels():
    out = _clarity.panels()
    del out[_clarity.DASHBOARD]
    out[DASHBOARD] = {"component_name": "lovelace", "icon": "mdi:view-dashboard", "title": "Vitro harness",
                      "config": {"mode": "storage"}, "url_path": DASHBOARD, "require_admin": False,
                      "show_in_sidebar": False, "config_panel_domain": None}
    return out


def dashboards_list():
    return [{"id": DASHBOARD, "url_path": DASHBOARD, "title": "Vitro harness", "icon": "mdi:view-dashboard",
             "show_in_sidebar": False, "require_admin": False, "mode": "storage"}]


def lovelace(url_path):
    if url_path == DASHBOARD:
        return HOME["dashboard"]
    return {"views": [{"title": "Home", "cards": []}]}


def user():
    """auth/current_user: the home's signed-in user, the owner and an administrator, unless the case's fixture says
    `"admin": false`: then a user who isn't (HA's error and warning cards show such a user less)."""
    u = HOME["user"]
    admin = fixture().get("admin", True) is not False
    return {"id": u["id"], "name": u["name"], "is_owner": admin, "is_admin": admin,
            "credentials": [{"auth_provider_type": "homeassistant", "auth_provider_id": None}], "mfa_modules": []}


# ---- registries

_DEVICE = {"area_id": None, "configuration_url": None, "config_entries": [], "config_entries_subentries": {},
           "connections": [], "created_at": 0, "disabled_by": None, "entry_type": None, "hw_version": None,
           "identifiers": [], "labels": [], "manufacturer": None, "model": None, "model_id": None, "modified_at": 0,
           "name_by_user": None, "primary_config_entry": None, "serial_number": None, "sw_version": None,
           "via_device_id": None}
_AREA = {"aliases": [], "floor_id": None, "humidity_entity_id": None, "icon": None, "labels": [], "picture": None,
         "temperature_entity_id": None, "created_at": 0, "modified_at": 0}


def registry():
    return {"entity_categories": {"0": "config", "1": "diagnostic"}, "entities": fixture().get("entities") or []}


def registry_full():
    """config/entity_registry/list (an admin's page asks for it: the calendar card does): each display entry in the
    full shape, made up from the short one (`ei`, `pl`, `di`, `ai`, `en`, `ic`, `tk`, `hn`, `ec`)."""
    out = []
    for e in fixture().get("entities") or []:
        eid = e["ei"]
        out.append({"area_id": e.get("ai"), "categories": {}, "config_entry_id": None, "config_subentry_id": None,
                    "created_at": 0, "device_id": e.get("di"), "disabled_by": None,
                    "entity_category": {0: "config", 1: "diagnostic"}.get(e.get("ec")), "entity_id": eid,
                    "has_entity_name": bool(e.get("hn")), "hidden_by": None, "icon": e.get("ic"),
                    "id": "reg-" + eid.replace(".", "-"), "labels": [], "modified_at": 0, "name": None,
                    "options": {}, "original_name": e.get("en"), "platform": e.get("pl"),
                    "translation_key": e.get("tk"), "unique_id": eid})
    return out


def devices():
    return [dict(_DEVICE, **d) for d in fixture().get("devices") or []]


def areas():
    return [dict(_AREA, **a) for a in fixture().get("areas") or []]


def users():
    """config/auth/list: the home's user and the fixture's (a logbook entry's `context_user_id` names one)."""
    me = user()
    out = [dict(me, system_generated=False, group_ids=["system-admin" if me["is_admin"] else "system-users"],
                username="parity", is_active=True, local_only=False)]
    for u in fixture().get("users") or []:
        out.append(dict({"is_owner": False, "is_admin": False, "system_generated": False, "group_ids": ["system-users"],
                         "credentials": [], "username": None, "is_active": True, "local_only": False}, **u))
    return out


# ---- HA core's texts and icons (hacore-<version>.json)

def _flatten(prefix, tree, out):
    if isinstance(tree, dict):
        for k, v in tree.items():
            _flatten(f"{prefix}.{k}", v, out)
    else:
        out[prefix] = tree


def translations(category, integrations=None):
    """frontend/get_translations, flattened as HA's helpers/translation.py sends it."""
    comps = integrations if integrations else config()["components"]
    out = {}
    for comp in comps:
        data = HACORE["translations"].get(comp, {})
        if category == "title":
            if "title" in data:
                out[f"component.{comp}.title"] = data["title"]
        elif category in data:
            _flatten(f"component.{comp}.{category}", data[category], out)
    return out


def translations_for(category, integrations=None, language="en"):
    """frontend/get_translations in [language], as HA core's helpers/translation.py answers: the language's texts over
    English's, key by key (English fills every key the language lacks). The languages are hacore.py's LANGUAGES."""
    out = translations(category, integrations)
    if language == "en":
        return out
    comps = integrations if integrations else config()["components"]
    own = {}
    for comp in comps:
        data = HACORE.get("localized", {}).get(language, {}).get(comp, {})
        if category == "title":
            if "title" in data:
                own[f"component.{comp}.title"] = data["title"]
        elif category in data:
            _flatten(f"component.{comp}.{category}", data[category], own)
    out.update({k: v for k, v in own.items() if k in out or category == "title"})
    return out


def user_data(key):
    """frontend/get_user_data and subscribe_user_data: the case's `user_data` (`{"language": {"language": "de",
    "number_format": "decimal_comma", ...}}`), else the home's, else none (a user who never set a profile)."""
    for src in (fixture().get("user_data"), HOME.get("user_data")):
        if isinstance(src, dict) and key in src:
            return src[key]
    return None


def icons(category, integrations=None):
    """frontend/get_icons: {component: icons.json[category]}."""
    comps = integrations if integrations else config()["components"]
    return {c: HACORE["icons"][c][category] for c in comps if category in HACORE["icons"].get(c, {})}


# ---- history and statistics

def _hash01(*parts):
    return int(hashlib.sha256(":".join(map(str, parts)).encode()).hexdigest()[:8], 16) / 0xFFFFFFFF


class Series:
    """One entity's past: `wave` (a smooth value sampled every `step`, rounded to `quantum` if given, written with
    `decimals`), or `points` / `steps` ([[t, state], ...] or [[t, state, attributes], ...]: states that hold until the
    next). Times are fixture times (seconds from now, or "-2h")."""

    def __init__(self, eid, spec):
        self.eid, self.spec = eid, spec
        if "wave" in spec:
            w = spec["wave"]
            self.lo, self.hi = float(w["min"]), float(w["max"])
            self.period = offset("+" + w.get("period", "12h")) if isinstance(w.get("period", "12h"), str) else float(w["period"])
            self.step = offset("+" + w.get("step", "10m")) if isinstance(w.get("step", "10m"), str) else float(w["step"])
            self.phase, self.noise, self.decimals = float(w.get("phase", 0)), float(w.get("noise", 0)), int(w.get("decimals", 1))
            self.quantum = float(w["quantum"]) if w.get("quantum") else None
            self.changes, self.attrs = None, {}
        else:
            pts = spec.get("points") or spec.get("steps") or []
            # Points at one instant: the last one in the fixture holds, state and attributes, as the recorder keeps
            # it. Collapsed here once, so the state, the history and the statistics all read the same timeline.
            last = {}
            for p in pts:
                last[at(p[0])] = p
            # A point's state may be an "@" time too, served resolved as the live state is.
            self.changes = sorted((t, str(resolve_state(p[1]))) for t, p in last.items())
            self.attrs = {t: resolve(p[2]) for t, p in last.items() if len(p) > 2}

    def attributes(self, t):
        """The attributes the last point at or before t gave, or None."""
        best = None
        for pt in sorted(self.attrs):
            if pt <= t:
                best = self.attrs[pt]
        return best

    def value(self, t):
        if self.changes is not None:
            cur = None
            for ct, s in self.changes:
                if ct <= t:
                    cur = s
            return cur
        # A sampled sensor: the value read at the start of each step holds until the next.
        t = math.floor(t / self.step) * self.step
        mid, amp = (self.lo + self.hi) / 2, (self.hi - self.lo) / 2
        v = mid + amp * 0.85 * math.sin(2 * math.pi * (t - NOW) / self.period + self.phase)
        v += amp * 0.15 * (2 * _hash01(self.eid, int(t // self.step)) - 1) + self.noise * (2 * _hash01("n", self.eid, int(t)) - 1)
        if self.quantum:
            v = round(v / self.quantum) * self.quantum
        return f"{round(v, self.decimals):.{self.decimals}f}" if self.decimals > 0 else str(int(round(v)))

    def last_change(self, t):
        """When the state at t began: the last point (or step) at or before t whose state differs from the one before."""
        if self.changes is not None:
            lc, prev = None, object()
            for ct, s in self.changes:
                if ct > t:
                    break
                if s != prev:
                    lc, prev = ct, s
            return lc if lc is not None else t
        v, k = self.value(t), math.floor(t / self.step) * self.step
        for _ in range(int(30 * 86400 / self.step)):
            if self.value(k - self.step) != v:
                return k
            k -= self.step
        return k

    def last_update(self, t):
        """The last point at or before t (an attribute update counts), or the last change for a sampled series."""
        if self.changes is not None:
            return max((ct for ct, _ in self.changes if ct <= t), default=self.last_change(t))
        return self.last_change(t)

    def changes_between(self, t0, t1):
        """[(t, state)]: the state at t0 (at t0), then each change after it up to t1 (a point with attributes of its own
        is a change too, as HA records an attribute change of a climate entity)."""
        if self.changes is not None:
            first = self.value(t0)
            out = [(t0, first)] if first is not None else []
            out += [(t, s) for t, s in self.changes if t0 < t <= t1 and (s != self.value(t - 1e-6) or t in self.attrs)]
            return out
        out, last = [], None
        t = t0
        while t <= t1:
            v = self.value(t)
            if v != last:
                out.append((t, v))
                last = v
            t = (math.floor(t / self.step) + 1) * self.step
        return out


def _series(eid):
    spec = (fixture().get("history") or {}).get(eid)
    return Series(eid, spec) if spec else None


_ATTR_DOMAINS = {"climate", "humidifier", "water_heater"}     # HA's history keeps every state's attributes for these


def history(entity_ids, start, end, no_attributes=True, minimal=True):
    """{entity_id: [{"s", "a"?, "lu"}]}: HA's compressed history from `start` to `end` (default now): the state at the
    start, each change after it, and the current state at its last change."""
    t0 = parse_time(start) if start else NOW - 86400
    t1 = min(parse_time(end), NOW) if end else NOW
    out = {}
    sts = states()
    for eid in entity_ids or []:
        cur = sts.get(eid)
        s = _series(eid)
        # One timeline: the live state comes from the series too (states()), so the series is the history as it is.
        # Without a series, the history is the current state from its last change (or the window's start).
        if s:
            pts = s.changes_between(t0, t1)
        elif cur and cur["last_changed"] <= t1:
            pts = [(max(cur["last_changed"], t0), cur["state"])]
        else:
            pts = []
        if not pts:
            continue
        rows = []
        dom = eid.split(".")[0]
        for i, (t, v) in enumerate(pts):
            row = {"s": v}
            if not no_attributes and (i == 0 or dom in _ATTR_DOMAINS or not minimal):
                own = s.attributes(t) if s and s.changes is not None else None
                row["a"] = dict(own) if own is not None else (dict(cur["attributes"]) if cur else {})
            row["lu"] = round(t, 6)
            rows.append(row)
        out[eid] = rows
    return out


def _state_class(eid):
    """The entity's state class for statistics: the fixture's `statistics` override, else its attribute."""
    st = states().get(eid)
    override = (fixture().get("statistics") or {}).get(eid) or {}
    return override.get("state_class", ((st or {}).get("attributes") or {}).get("state_class"))


def _change(values, increasing):
    """How much a counter rose across [values] (the previous close, then each value held), as HA's recorder sums it.
    A total_increasing value below 0.9 of the one before is a meter reset (`reset_detected`): the new cycle starts
    from 0, so the value after the reset counts in full. A smaller drop, or any drop of a `total`, counts as it is.
    A negative total_increasing reading is skipped (`warn_negative`, then the recorder moves on to the next one)."""
    if increasing:
        values = [v for v in values if v >= 0]
    change = 0.0
    for prev, cur in zip(values, values[1:]):
        change += cur if increasing and cur < 0.9 * prev else cur - prev
    return change


def _accepted_before(s, t, increasing):
    """The reading the recorder holds at [t]: the series' value then, or for a total_increasing meter whose value then
    is negative (skipped, `warn_negative`), the last non-negative reading before it. None when there is none."""
    v = s.value(t)
    if v is None or not _num(v):
        return None
    if not increasing or float(v) >= 0:
        return float(v)
    if s.changes is not None:
        for ct, cv in reversed(s.changes):
            if ct < t and cv is not None and _num(cv) and float(cv) >= 0:
                return float(cv)
        return None
    back = t - s.step
    for _ in range(10_000):
        bv = s.value(back)
        if bv is not None and _num(bv) and float(bv) >= 0:
            return float(bv)
        back -= s.step
    return None


def _meta(eid):
    st = states().get(eid)
    override = (fixture().get("statistics") or {}).get(eid) or {}
    if not st and not override:
        return None
    a = (st or {}).get("attributes", {})
    sc = _state_class(eid)
    if sc not in ("measurement", "total", "total_increasing"):
        return None
    unit = a.get("unit_of_measurement")
    unit_class = {"temperature": "temperature", "pressure": "pressure", "energy": "energy", "power": "power",
                  "humidity": None, "carbon_dioxide": None}.get(a.get("device_class"))
    has_mean = sc == "measurement"
    return {"statistic_id": eid, "display_unit_of_measurement": unit, "has_mean": has_mean, "mean_type": 1 if has_mean else 0,
            "has_sum": not has_mean, "name": None, "source": "recorder", "statistics_unit_of_measurement": unit,
            "unit_class": unit_class}


def statistics_metadata(ids):
    return [m for m in (_meta(e) for e in ids or []) if m]


def _held(s, b, e):
    """[(t, value, seconds held)]: every numeric value series [s] holds in the bucket [b, e), as HA's recorder
    compiles one: the value at b, then each change before e (a change at e itself starts the next bucket). A value
    that isn't a number (`unavailable`) holds its own time, which counts for none. (Points at one instant are one
    point already: Series keeps the last.)"""
    pts = s.changes_between(b, e - 1e-6)
    spans = [(t, v, (pts[i + 1][0] if i + 1 < len(pts) else e) - t) for i, (t, v) in enumerate(pts)]
    return [(t, float(v), w) for t, v, w in spans if v is not None and _num(v)]


def statistics(ids, start, end, period, types):
    """recorder/statistics_during_period: hourly (or 5-minute, daily, ...) buckets of each entity's series."""
    step = {"5minute": 300, "hour": 3600, "day": 86400, "week": 604800, "month": 2592000}.get(period or "hour", 3600)
    t0 = parse_time(start) if start else NOW - 86400
    t1 = min(parse_time(end), NOW) if end else NOW
    types = set(types or ["change", "state", "sum", "min", "max", "mean"])
    out = {}
    for eid in ids or []:
        m, s = _meta(eid), _series(eid)
        if not m or not s:
            continue
        rows, b = [], math.floor(t0 / step) * step
        total = 0.0
        while b + step <= t1 + 1e-6:
            held = _held(s, b, b + step)
            if held:
                samples = [v for _, v, _ in held]
                row = {"start": int(b * 1000), "end": int((b + step) * 1000)}
                if m["has_mean"]:
                    # HA's mean is weighted by how long each value held; min and max are over every value held.
                    mean = sum(v * w for _, v, w in held) / sum(w for _, _, w in held)
                    for k, v in (("mean", mean), ("min", min(samples)), ("max", max(samples))):
                        if k in types:
                            row[k] = round(v, 6)
                else:
                    # HA's change runs from the previous hour's close (a step right at b belongs to this bucket),
                    # through every value held, a meter reset included.
                    # A meter's skipped negative reading carries the last accepted one over the hour.
                    increasing = _state_class(eid) == "total_increasing"
                    before = _accepted_before(s, b - 1e-6, increasing)
                    opening = before if before is not None else samples[0]
                    change = _change([opening] + samples, increasing)
                    total += change
                    taken = [v for v in [opening] + samples if v >= 0] if increasing else samples
                    state = taken[-1] if taken else samples[-1]
                    for k, v in (("change", change), ("state", state), ("sum", total)):
                        if k in types:
                            row[k] = round(v, 6)
                rows.append(row)
            b += step
        if rows:
            out[eid] = rows
    return out


def statistic_during_period(m):
    """recorder/statistic_during_period: one entity's max, mean and min (a measurement) or change (a total) over a
    `calendar` (hour, day, week, month, year, with `offset`), `fixed_period` or `rolling_window` window. Read from the
    entity's series at HA's short-term granularity (5 minutes) across the window's own bounds, as HA joins its
    short-term statistics at a partial hour: a total's change is its newest less its oldest sum across the window
    (a meter's resets counted as _change does). Null for a value the window holds no data for."""
    eid = m.get("statistic_id")
    now = datetime.datetime.fromtimestamp(NOW, tz())
    end = NOW
    if m.get("fixed_period"):
        fp = m["fixed_period"]
        start = parse_time(fp.get("start_time")) if fp.get("start_time") else None
        end = parse_time(fp.get("end_time")) if fp.get("end_time") else NOW
    elif m.get("rolling_window"):
        rw = m["rolling_window"]
        def dur(d):
            d = d or {}
            return d.get("days", 0) * 86400 + d.get("hours", 0) * 3600 + d.get("minutes", 0) * 60 + d.get("seconds", 0)
        end = NOW + dur(rw.get("offset"))
        start = end - dur(rw.get("duration"))
    elif m.get("calendar"):
        cal = m["calendar"]
        unit, off = cal.get("period", "day"), int(cal.get("offset", 0) or 0)
        day0 = now.replace(hour=0, minute=0, second=0, microsecond=0)
        if unit == "hour":
            s0 = now.replace(minute=0, second=0, microsecond=0) + datetime.timedelta(hours=off)
            e0 = s0 + datetime.timedelta(hours=1)
        elif unit == "day":
            s0 = day0 + datetime.timedelta(days=off)
            e0 = s0 + datetime.timedelta(days=1)
        elif unit == "week":
            first = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"].index(cal.get("first_weekday", "mon"))
            s0 = day0 - datetime.timedelta(days=(day0.weekday() - first) % 7) + datetime.timedelta(weeks=off)
            e0 = s0 + datetime.timedelta(weeks=1)
        elif unit == "month":
            y, mo = now.year, now.month + off
            y, mo = y + (mo - 1) // 12, (mo - 1) % 12 + 1
            s0 = day0.replace(year=y, month=mo, day=1)
            e0 = s0.replace(year=y + (mo // 12), month=mo % 12 + 1)
        else:
            s0 = day0.replace(year=now.year + off, month=1, day=1)
            e0 = s0.replace(year=s0.year + 1)
        start, end = s0.timestamp(), e0.timestamp()
    else:
        start = None
    meta, series = _meta(eid) or {}, _series(eid)
    t0, t1 = (start if start is not None else NOW - 365 * 86400), min(end, NOW)
    # (t, value or None): the window's start, every change of the series inside it (a step series' own points, a
    # wave's steps), and its end. A value that isn't a number (unavailable) is None: a boundary, held for no mean.
    points = []
    if series and t1 > t0:
        if series.changes is not None:
            times = [t0] + [t for t, _ in series.changes if t0 < t < t1] + [t1]
        else:
            times = [t0]
            t = (math.floor(t0 / series.step) + 1) * series.step
            while t < t1:
                times.append(t)
                t += series.step
            times.append(t1)
        for t in times:
            v = series.value(t)
            points.append((t, float(v) if v is not None and _num(v) else None))
    out = {}
    if meta.get("has_mean"):
        # HA's mean is time-weighted: each value counts for as long as it's held; the end point holds for no time.
        held = [(v, points[i + 1][0] - t) for i, (t, v) in enumerate(points[:-1]) if points[i + 1][0] > t and v is not None]
        span = sum(d for _, d in held)
        out["max"] = round(max(v for v, _ in held), 6) if held else None
        out["mean"] = round(sum(v * d for v, d in held) / span, 6) if held else None
        out["min"] = round(min(v for v, _ in held), 6) if held else None
    elif meta:
        # The recorder's sum over the window: a meter's reset, dip and negative readings as statistics() reads them.
        increasing = _state_class(eid) == "total_increasing"
        readings = [v for _, v in points if v is not None and (v >= 0 or not increasing)]
        # The window opens from the reading the recorder held then, a skipped negative one's predecessor included.
        if increasing and points and points[0][1] is not None and points[0][1] < 0:
            held = _accepted_before(series, points[0][0], True)
            if held is not None:
                readings.insert(0, held)
        out["change"] = round(_change(readings, increasing), 6) if readings else None
    types = m.get("types")
    return {k: v for k, v in out.items() if not types or k in types}


def _num(v):
    try:
        float(v)
        return True
    except (TypeError, ValueError):
        return False


# ---- templates (render_template): jinja2 with the HA helpers the corpus uses

class _TemplateState:
    def __init__(self, st):
        self._st = st
        self.entity_id, self.state, self.attributes = st["entity_id"], st["state"], st["attributes"]
        self.domain, self.object_id = self.entity_id.split(".", 1)
        self.name = st["attributes"].get("friendly_name", self.object_id)
        self.last_changed = datetime.datetime.fromtimestamp(st["last_changed"], tz())
        self.last_updated = datetime.datetime.fromtimestamp(st["last_updated"], tz())

    def __str__(self):
        return f"<template TemplateState({self.entity_id}={self.state})>"


def _template_attr(name):
    """Whether `states.<name>` / `states.<domain>.<name>` may be an entity: never a dunder or private name, nor the
    flags jinja's sandbox reads on a callable (it would take an entity object for a yes)."""
    if name.startswith("_") or name in ("unsafe_callable", "alters_data"):
        raise AttributeError(name)


class _Domain:
    def __init__(self, sts, domain):
        self._sts, self._domain = sts, domain

    def __getattr__(self, name):
        _template_attr(name)
        st = self._sts.get(f"{self._domain}.{name}")
        return _TemplateState(st) if st else None

    def __iter__(self):
        return iter(sorted((_TemplateState(s) for e, s in self._sts.items() if e.startswith(self._domain + ".")),
                           key=lambda x: x.entity_id))


class _States:
    def __init__(self, sts):
        self._sts = sts

    def __call__(self, entity_id, rounded=False, with_unit=False):
        st = self._sts.get(entity_id)
        return st["state"] if st else "unknown"

    def __getattr__(self, domain):
        _template_attr(domain)
        return _Domain(self._sts, domain)

    def __iter__(self):
        return iter(sorted((_TemplateState(s) for s in self._sts.values()), key=lambda x: x.entity_id))


def render_template(template, variables=None):
    import jinja2
    sts = states()
    from jinja2.sandbox import SandboxedEnvironment
    # Sandboxed: anything that reaches the fake's port can send a template, and a plain environment would run Python.
    env = SandboxedEnvironment(undefined=jinja2.ChainableUndefined, extensions=["jinja2.ext.loopcontrols"])
    attr = lambda e, a: (sts.get(e) or {}).get("attributes", {}).get(a)  # noqa: E731
    now = lambda: datetime.datetime.fromtimestamp(NOW, tz())  # noqa: E731
    helpers = {
        "states": _States(sts), "is_state": lambda e, s: (sts.get(e) or {}).get("state") in (s if isinstance(s, list) else [s]),
        "state_attr": attr, "is_state_attr": lambda e, a, v: attr(e, a) == v,
        "has_value": lambda e: (sts.get(e) or {}).get("state") not in (None, "unknown", "unavailable"),
        "now": now, "utcnow": lambda: datetime.datetime.fromtimestamp(NOW, datetime.timezone.utc),
        "as_timestamp": lambda v, default=None: v.timestamp() if hasattr(v, "timestamp") else (parse_time(v) if v else default),
        "iif": lambda c, t=True, f=False, n=None: n if c is None and n is not None else (t if c else f),
    }
    env.globals.update(helpers)
    env.filters.update({"as_timestamp": helpers["as_timestamp"], "iif": helpers["iif"], "is_state": helpers["is_state"],
                        "state_attr": helpers["state_attr"]})
    result = env.from_string(template).render(**(variables or {}))
    # As HA's render_template parses a result that is a plain number or boolean into one.
    stripped = result.strip()
    if re.fullmatch(r"-?\d+", stripped) and str(int(stripped)) == stripped:
        return int(stripped)
    if re.fullmatch(r"-?\d+\.\d+", stripped) and repr(float(stripped)) == stripped:
        return float(stripped)
    if stripped in ("True", "False"):
        return stripped == "True"
    return result


# ---- websocket commands

def _sub(c, m, *events):
    c.result(m["id"])
    for ev in events:
        c.event(m["id"], ev)


def _render_template(c, m):
    try:
        out = render_template(m["template"], m.get("variables"))
    except Exception as e:     # noqa: BLE001 - HA answers a broken template with an error, not a dropped socket
        if m.get("report_errors"):
            return c.result(m["id"], None, success=False, error={"code": "template_error", "message": str(e)})
        return _sub(c, m, {"error": str(e), "level": "ERROR"})
    _sub(c, m, {"result": out, "listeners": {"all": False, "domains": [], "entities": sorted(states()), "time": False}})


def _history_stream(c, m):
    t0 = parse_time(m.get("start_time"))
    t1 = min(parse_time(m["end_time"]), NOW) if m.get("end_time") else NOW
    _sub(c, m, {"states": history(m.get("entity_ids"), m.get("start_time"), m.get("end_time"),
                                  m.get("no_attributes", False), m.get("minimal_response", False)),
                "start_time": t0, "end_time": t1})


def _history_period(c, m):
    c.result(m["id"], history(m.get("entity_ids"), m.get("start_time"), m.get("end_time"),
                              m.get("no_attributes", False), m.get("minimal_response", False)))


def _logbook(c, m):
    t0 = parse_time(m.get("start_time")) or NOW - 86400
    t1 = parse_time(m.get("end_time")) or NOW
    want = set(m.get("entity_ids") or [])
    events = []
    for e in fixture().get("logbook") or []:
        when = at(e["when"])
        if t0 <= when <= min(t1, NOW) and (not want or e.get("entity_id") in want):
            events.append(dict(resolve({k: v for k, v in e.items() if k != "when"}), when=when))
    events.sort(key=lambda e: e["when"])
    _sub(c, m, {"events": events, "start_time": t0, "end_time": NOW, "partial": True},
         {"events": [], "start_time": t0, "end_time": NOW})


def _todo(c, m):
    items = ((fixture().get("todo") or {}).get(m.get("entity_id")))
    if items is None:
        return c.result(m["id"], None, success=False, error={"code": "not_found", "message": "Entity not found"})
    _sub(c, m, {"items": resolve(items)})


def _forecast(c, m):
    fc = ((fixture().get("forecast") or {}).get(m.get("entity_id")) or {}).get(m.get("forecast_type"))
    if fc is None:
        return c.result(m["id"], None, success=False, error={"code": "invalid_format", "message": "Forecast type not supported"})
    _sub(c, m, {"type": m.get("forecast_type"), "forecast": resolve(fc)})


def _calendar(c, m):
    evs = (fixture().get("calendar") or {}).get(m.get("entity_id"))
    if evs is None:
        return c.result(m["id"], None, success=False, error={"code": "not_found", "message": "Calendar not found"})
    t0, t1 = parse_time(m.get("start")), parse_time(m.get("end"))
    out = []
    for e in resolve(evs):
        s, en = e["start"], e["end"]
        # An all-day event's dates are local days; a timed one's are ISO times.
        ts = parse_time(s if "T" in s else datetime.datetime.fromisoformat(s).replace(tzinfo=tz()).isoformat())
        te = parse_time(en if "T" in en else datetime.datetime.fromisoformat(en).replace(tzinfo=tz()).isoformat())
        if te > t0 and ts < t1:
            out.append(dict({"description": None, "location": None, "all_day": "T" not in s}, **e))
    _sub(c, m, {"events": out})


def check_condition(cond):
    """HA's condition check on the fixture's states (helpers/condition.py): state, numeric_state, template, and, or, not."""
    sts = states()
    if isinstance(cond, list):
        return all(check_condition(c) for c in cond)
    kind = cond.get("condition")
    if kind in ("and", "or", "not"):
        results = [check_condition(c) for c in cond.get("conditions") or []]
        return all(results) if kind == "and" else any(results) if kind == "or" else not any(results)
    if kind == "template":
        return render_template(cond.get("value_template", "")) in (True, "True", "true")
    ids = cond.get("entity_id")
    ids = [ids] if isinstance(ids, str) else ids or []

    def one(eid):
        st = sts.get(eid)
        if st is None:
            return False
        value = st["attributes"].get(cond["attribute"]) if cond.get("attribute") else st["state"]
        if kind == "state":
            want = cond.get("state")
            return str(value) in [str(w) for w in (want if isinstance(want, list) else [want])]
        if kind == "numeric_state":
            try:
                v = float(value)
            except (TypeError, ValueError):
                return False
            above, below = cond.get("above"), cond.get("below")
            return (above is None or v > float(above)) and (below is None or v < float(below))
        raise ValueError(f"condition {kind} isn't in the fake")
    return (all if cond.get("match", "all") == "all" else any)(one(e) for e in ids)


def _subscribe_condition(c, m):
    try:
        out = {"result": check_condition(m["condition"])}
    except Exception as e:  # noqa: BLE001 - HA sends a condition it can't check as an error event
        out = {"error": str(e)}
    _sub(c, m, out)


def _const(fn):
    return lambda c, m: c.result(m["id"], fn())


HANDLERS = {
    "auth/current_user": _const(user),
    "config/auth/list": _const(users),
    "frontend/get_themes": _const(lambda: HOME["themes"]),
    "lovelace/resources": _const(lambda: _cards.resources(CARDS)),
    "config/entity_registry/list_for_display": _const(registry),
    "config/entity_registry/list": _const(registry_full),
    # The more-info dialog and some cards ask for one entry; the fixture's, or HA's answer for an entity without one.
    "config/entity_registry/get": lambda c, m: (lambda hit: c.result(m["id"], hit) if hit else c.result(
        m["id"], None, success=False, error={"code": "not_found", "message": "Entity not found"}))(
        next((dict(e, aliases=[], capabilities=None, device_class=None, original_device_class=None, original_icon=None)
              for e in registry_full() if e["entity_id"] == m.get("entity_id")), None)),
    # An admin's page reads HA's HTTP settings (a banner about a proxy); the fake has no proxy and no pending change.
    "http/config": _const(lambda: {"config": {}, "pending_config": None}),
    "config/device_registry/list": _const(devices),
    "config/area_registry/list": _const(areas),
    "config/floor_registry/list": _const(lambda: fixture().get("floors") or []),
    "config/label_registry/list": _const(lambda: []),
    "person/list": _const(lambda: {"storage": fixture().get("persons") or [], "config": []}),
    # In the language the page asks for (the user's profile, user_data below), English filling the rest.
    "frontend/get_translations": lambda c, m: c.result(m["id"], {"resources": translations_for(
        m.get("category"), m.get("integration"), m.get("language") or "en")}),
    # The user's frontend profile (a case's `user_data`): HA's page reads `language` for its language and formats.
    "frontend/get_user_data": lambda c, m: c.result(m["id"], {"value": user_data(m.get("key"))}),
    "frontend/subscribe_user_data": lambda c, m: (c.result(m["id"]), c.event(m["id"], {"value": user_data(m.get("key"))})),
    "frontend/get_icons": lambda c, m: c.result(m["id"], {"resources": icons(m.get("category"), m.get("integration"))}),
    "render_template": _render_template,
    "subscribe_condition": _subscribe_condition,
    # The logbook asks which automation runs it has traces for: none in a made-up home.
    "trace/contexts": _const(lambda: {}),
    # Bubble Card asks for its optional companion integration's modules: not installed, so HA's unknown-command error.
    "bubble_card_tools/list_modules": lambda c, m: c.result(m["id"], None, success=False, error={
        "code": "unknown_command", "message": "Unknown command."}),
    "history/stream": _history_stream,
    "history/history_during_period": _history_period,
    "recorder/get_statistics_metadata": lambda c, m: c.result(m["id"], statistics_metadata(m.get("statistic_ids"))),
    "recorder/statistic_during_period": lambda c, m: c.result(m["id"], statistic_during_period(m)),
    "recorder/statistics_during_period": lambda c, m: c.result(m["id"], statistics(
        m.get("statistic_ids"), m.get("start_time"), m.get("end_time"), m.get("period"), m.get("types"))),
    "logbook/event_stream": _logbook,
    # An update entity's release notes (`update/release_notes`): the fixture's Markdown, or none (HA answers null).
    "update/release_notes": lambda c, m: c.result(m["id"], (fixture().get("release_notes") or {}).get(m.get("entity_id"))),
    "todo/item/subscribe": _todo,
    "weather/subscribe_forecast": _forecast,
    "calendar/event/subscribe": _calendar,
}


# ---- HTTP: the harness's own routes, fixtures, community cards, REST history

def _rest_history(path, query):
    """GET /api/history/period/<start>?filter_entity_id=a,b&end_time=..&minimal_response&no_attributes: full state
    objects for each entity's first row (unless minimal), as HA's REST API answers (mini-graph-card asks this way)."""
    start = urllib_unquote(path[len("/api/history/period/"):]) or None
    ids = [i for i in (query.get("filter_entity_id", [""])[0]).split(",") if i]
    minimal = "minimal_response" in query
    no_attr = "no_attributes" in query
    rows = history(ids, start, query.get("end_time", [None])[0], no_attr, minimal)
    out = []
    for eid in ids:
        lst = []
        for i, r in enumerate(rows.get(eid, [])):
            t = iso(r["lu"])
            if i == 0 or not minimal:
                lst.append({"entity_id": eid, "state": r["s"], "attributes": r.get("a", {}), "last_changed": t, "last_updated": t})
            else:
                lst.append({"state": r["s"], "last_changed": t})
        out.append(lst)
    return out


def urllib_unquote(s):
    import urllib.parse
    return urllib.parse.unquote(s)


def http_get(path, query):
    """(status, body, content type) for a route this home serves, or None for fakeha's own."""
    import mimetypes
    if path.startswith("/hacsfiles/"):
        parts = path[len("/hacsfiles/"):].split("/", 1)
        if len(parts) != 2 or ".." in parts[1].split("/"):
            return 404, b"", "text/plain"
        try:
            p = _cards.path(parts[0], parts[1], CARDS)
        except Exception as e:  # noqa: BLE001 - a bad download is a 502, said out loud
            return 502, str(e).encode(), "text/plain"
        if not p:
            return 404, b"", "text/plain"
        with open(p, "rb") as f:
            body = f.read()
        ctype = "application/javascript" if p.endswith(".js") else (mimetypes.guess_type(p)[0] or "application/octet-stream")
        return 200, body, ctype
    if path.startswith("/api/camera_proxy/"):
        # A camera's still (HA's poster for a live camera) is the case's own picture for it, its entity_picture under
        # fixtures/: HA's poster and Vitro's still are then one drawn picture. Without one, Clarity's still.
        st = (CASE["fixture"].get("states") or {}).get(path[len("/api/camera_proxy/"):]) or {}
        pic = str((st.get("attributes") or {}).get("entity_picture") or "").split("?")[0]
        if pic.startswith(FIXTURE_PREFIX):
            path = pic
    if path.startswith(FIXTURE_PREFIX) and HOME.get("fixtures_dir"):
        base = os.path.realpath(HOME["fixtures_dir"])
        full = os.path.realpath(os.path.join(base, path[len(FIXTURE_PREFIX):]))
        if not full.startswith(base + os.sep) or not os.path.isfile(full):
            return 404, b"", "text/plain"
        with open(full, "rb") as f:
            return 200, f.read(), mimetypes.guess_type(full)[0] or "application/octet-stream"
    if path.startswith("/api/history/period/"):
        return 200, json.dumps(_rest_history(path, query)).encode(), "application/json"
    if path == "/vitro-parity/state":
        return 200, json.dumps({"case": CASE["id"], "now": NOW, "unknown": sorted(_hub().unknown)}).encode(), "application/json"
    return None


def _hub():
    import fakeha
    return fakeha.Handler.hub


def http_post(path, body):
    if path == "/vitro-parity/home":
        set_home(json.loads(body))
        return 200, json.dumps({"now": NOW}).encode(), "application/json"
    if path == "/vitro-parity/case":
        doc = json.loads(body)
        set_case(doc["id"], doc.get("fixture"), _hub())
        return 200, json.dumps({"case": CASE["id"], "states": len(_hub().states)}).encode(), "application/json"
    return None


# ---- keeping the corpus's fixtures on one timeline

def sync_fixtures(corpus_dir, check=False):
    """Writes into each case's fixture the state, last change and last update (and a series' own attributes) that its
    entities' history series give at the home's now, as states() serves them, so the JSON says what HA draws. With
    check, changes nothing and returns the cases whose fixtures disagree."""
    with open(os.path.join(corpus_dir, "home.json")) as f:
        set_home(json.load(f))
    out = []
    folder = os.path.join(corpus_dir, "corpus")
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".json"):
            continue
        path = os.path.join(folder, name)
        with open(path) as f:
            doc = json.load(f)
        fx = doc.get("fixture") or {}
        CASE.update(id=name[:-5], fixture=fx)
        changed = False
        for eid in fx.get("history") or {}:
            st = (fx.get("states") or {}).get(eid)
            s = _series(eid)
            if st is None or s is None:
                continue
            want = {"state": s.value(NOW), "last_changed": round(s.last_change(NOW) - NOW)}
            if s.last_update(NOW) != s.last_change(NOW):
                want["last_updated"] = round(s.last_update(NOW) - NOW)
            own = s.attributes(NOW)
            if own is not None:
                want["attributes"] = dict(st.get("attributes") or {}, **own)
            for k, v in want.items():
                if st.get(k) != v:
                    st[k] = v
                    changed = True
            if "last_updated" in st and "last_updated" not in want:
                del st["last_updated"]
                changed = True
        if changed:
            out.append(name[:-5])
            if not check:
                with open(path, "w") as f:
                    json.dump(doc, f, indent=1, ensure_ascii=False)
                    f.write("\n")
    return out


if __name__ == "__main__":
    # python3 tools/vitro-parity/fakehome.py sync [--check]: the fixtures' states from their own history series.
    if len(sys.argv) > 1 and sys.argv[1] == "sync":
        corpus = os.path.join(HERE, "..", "..", "engines", "engine-vitro", "src", "test", "parity")
        changed = sync_fixtures(corpus, check="--check" in sys.argv)
        print(("out of step: " if "--check" in sys.argv else "updated: ") + (", ".join(changed) or "none"))
        sys.exit(1 if changed and "--check" in sys.argv else 0)
