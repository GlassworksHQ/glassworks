"""Tests for showcase_ha.py, Vitro's parity showcase: the server on an ephemeral port, its websocket and HTTP.

  python3 -m unittest tools/vitro/test_showcase_ha.py

Needs no network and no device: HA's real frontend is used only if it is already unpacked in Clarity's
cache (tools/perf/hafrontend.py); otherwise a one-page stub stands in, as in Clarity's off-device tests.
"""
import base64
import datetime
import json
import os
import re
import socket
import struct
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "perf"))

import hafrontend  # noqa: E402

import showcase_ha as S  # noqa: E402
from fakeha import ws_frame, ws_read  # noqa: E402


def setUpModule():
    """Offline, a stub frontend stands in for HA's (read when the server starts). The override and the stub go when
    the module's tests end, setup failures included, so a later test in the process never sees a deleted folder."""
    if os.environ.get("GW_PERF_FRONTEND") or os.path.exists(os.path.join(hafrontend.CACHE, f"hass_frontend-{hafrontend.VERSION}", ".unpacked")):
        return
    stub = tempfile.TemporaryDirectory()
    unittest.addModuleCleanup(stub.cleanup)
    prior = os.environ.get("GW_PERF_FRONTEND")
    unittest.addModuleCleanup(lambda: os.environ.__setitem__("GW_PERF_FRONTEND", prior) if prior is not None else os.environ.pop("GW_PERF_FRONTEND", None))
    with open(os.path.join(stub.name, "index.html"), "w") as f:
        f.write("<html><head><title>stub frontend</title></head><body>stub</body></html>")
    os.environ["GW_PERF_FRONTEND"] = stub.name


def _iso_z(t):
    return datetime.datetime.fromtimestamp(t, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


class WS:
    """A minimal websocket client (masked text frames out, fakeha's reader in)."""

    def __init__(self, port):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET /api/websocket HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\n"
                           f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            b = self.sock.recv(1)
            if not b:       # closed during the upgrade: fail instead of spinning
                self.sock.close()
                raise AssertionError(f"websocket upgrade failed, server closed: {head!r}")
            head += b
        assert b" 101 " in head.split(b"\r\n")[0], head
        self.n = 0
        self.buf = []

    def send(self, msg):
        payload = json.dumps(msg).encode()
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        frame = ws_frame(masked)
        # ws_frame builds an unmasked header; set the mask bit and splice the key in after the length bytes
        frame = bytearray(frame)
        frame[1] |= 0x80
        hdr = 2 + (2 if frame[1] & 0x7F == 126 else 8 if frame[1] & 0x7F == 127 else 0)
        self.sock.sendall(bytes(frame[:hdr]) + mask + bytes(frame[hdr:]))

    def recv(self):
        op, data = ws_read(self.sock)
        return json.loads(data)

    def login(self):
        assert self.recv()["type"] == "auth_required"
        self.send({"type": "auth", "access_token": "anything"})
        msg = self.recv()
        assert msg["type"] == "auth_ok", msg
        return msg

    def call(self, type_, **kw):
        """Send one command; return its result message. Events that arrive before it wait in .buf."""
        self.n += 1
        mid = self.n
        self.send(dict(kw, id=mid, type=type_))
        return self.until(lambda m: m.get("id") == mid and m["type"] in ("result", "pong"))

    def until(self, pred):
        """The first message that matches, from those already read or the next ones; others wait in .buf for a later until."""
        for i, m in enumerate(self.buf):
            if pred(m):
                return self.buf.pop(i)
        while True:
            m = self.recv()
            if pred(m):
                return m
            self.buf.append(m)

    def close(self):
        self.sock.close()


def http(port, path, method="GET", body=None, headers=None):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=body, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


class ShowcaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = S.serve(0)
        cls.port = cls.srv.server_address[1]
        cls.hub = cls.srv.hub
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.hub.close_all()
        cls.srv.shutdown()
        cls.srv.server_close()

    def setUp(self):
        self.ws = WS(self.port)
        self.ws.login()
        self.addCleanup(self.ws.close)
        self.addCleanup(self.reset_home)

    def reset_home(self):
        """Every test starts from the fixed home: put back what a test changed."""
        fresh = S.Hub()
        with self.hub.lock:
            for e, st in fresh.states.items():
                self.hub.states[e]["state"], self.hub.states[e]["attributes"] = st["state"], st["attributes"]
            self.hub.todos = fresh.todos
            self.hub.memory.clear()

    # -- http

    def test_binds_loopback_only(self):
        self.assertEqual(self.srv.server_address[0], "127.0.0.1")

    def test_local_energy_ring_module(self):
        status, headers, body = http(self.port, "/local/vitro-demo/energy-ring-card.js")
        self.assertEqual(status, 200)
        self.assertIn("javascript", headers["Content-Type"])
        text = body.decode()
        self.assertIn('customElements.define("vitro-energy-ring-card"', text)
        self.assertIn("attachShadow", text)
        self.assertIn("callService", text)
        self.assertIn('states[this._config.entity]', text)
        self.assertEqual(http(self.port, "/local/vitro-demo/missing.js")[0], 404)
        self.assertEqual(http(self.port, "/local/../showcase_ha.py")[0], 404)

    def test_a_local_folder_serves_its_files_and_nothing_beside_it(self):
        # With --local-dir: a file inside is served; ../ out of the folder is not, even to a real file.
        with tempfile.TemporaryDirectory() as root:
            inside = os.path.join(root, "bundle")
            os.mkdir(inside)
            with open(os.path.join(inside, "card.js"), "w") as f:
                f.write("export {};")
            with open(os.path.join(root, "secret.txt"), "w") as f:
                f.write("outside")
            self.srv.local_dir = inside
            try:
                self.assertEqual(http(self.port, "/local/card.js")[0], 200)
                self.assertEqual(http(self.port, "/local/../secret.txt")[0], 404)
                self.assertEqual(http(self.port, "/local/%2e%2e/secret.txt")[0], 404)
            finally:
                self.srv.local_dir = None

    def test_local_wrapper_card_module(self):
        status, headers, body = http(self.port, "/local/vitro-demo/wrapper-card.js")
        self.assertEqual(status, 200)
        self.assertIn("javascript", headers["Content-Type"])
        text = body.decode()
        for needle in ('customElements.define("vitro-wrapper-card"', "attachShadow", "window.loadCardHelpers()",
                       "createCardElement", "hass.localize(", "hass.formatEntityState(", 'callApi("GET", "config")'):
            self.assertIn(needle, text)

    def test_floorplan_png(self):
        status, headers, png = http(self.port, "/local/vitro-demo/floorplan.png")
        self.assertEqual((status, headers["Content-Type"]), (200, "image/png"))
        self.assertEqual(png[:8], b"\x89PNG\r\n\x1a\n")
        w, h = struct.unpack(">II", png[16:24])
        self.assertEqual((w, h), (S.PLAN_W, S.PLAN_H))
        idat = png.index(b"IDAT")
        raw = zlib.decompress(png[idat + 4:png.index(b"IEND") - 8])
        self.assertEqual(len(raw), (1 + 3 * w) * h)
        px = lambda x, y: tuple(raw[y * (1 + 3 * w) + 1 + x * 3:][:3])  # noqa: E731
        self.assertEqual(px(100, 44), (71, 85, 105))          # an outer wall
        self.assertEqual(px(410, 305), (247, 249, 253))        # open floor, between two grid lines
        self.assertEqual(px(10, 10), (233, 238, 246))          # outside the plan
        self.assertEqual(len({px(x, y) for x in range(0, w, 7) for y in range(0, h, 7)}) >= 6, True)    # walls, floor, furniture, labels
        self.assertEqual(png, http(self.port, "/local/vitro-demo/floorplan.png")[2])                     # the same bytes every time

    def test_calendar_events_are_upcoming(self):
        status, headers, body = http(self.port, "/api/calendars/calendar.studio_schedule?start=2026-11-01T00:00:00Z&end=2026-12-01T00:00:00Z")
        self.assertEqual((status, headers["Content-Type"]), (200, "application/json"))
        events = json.loads(body)
        self.assertEqual(len(events), 4)
        now = time.time()
        for e in events:
            start = datetime.datetime.fromisoformat(e["start"]["dateTime"]).timestamp()
            end = datetime.datetime.fromisoformat(e["end"]["dateTime"]).timestamp()
            self.assertTrue(now < start < end, e)
            self.assertTrue(e["summary"] and e["description"])
        self.assertEqual(events[0]["summary"], self.state("calendar.studio_schedule")["attributes"]["message"])
        self.assertEqual([e["start"]["dateTime"] for e in events], sorted(e["start"]["dateTime"] for e in events))
        fixed = S.calendar_events(now=1_000_000_000.0)                                  # anchored to the time it is given
        self.assertEqual(fixed[0]["start"]["dateTime"], "2001-09-09T03:00:00+00:00")
        self.assertEqual(json.loads(http(self.port, "/api/calendars")[2])[0]["entity_id"], "calendar.studio_schedule")
        self.assertEqual(http(self.port, "/api/calendars/calendar.nope")[0], 404)
        self.assertEqual(http(self.port, "/api/calendars/light.desk_lamp")[0], 404)

    def test_camera_still_is_a_moving_png(self):
        status, headers, a = http(self.port, "/api/camera_proxy/camera.studio_front?frame=0")
        self.assertEqual((status, headers["Content-Type"]), (200, "image/png"))
        self.assertEqual(a[:8], b"\x89PNG\r\n\x1a\n")
        self.assertIn(b"IHDR", a[:16])
        b = http(self.port, "/api/camera_proxy/camera.studio_front?frame=1")[2]
        self.assertNotEqual(a, b)                                           # it moves
        self.assertEqual(a, http(self.port, "/api/camera_proxy/camera.studio_front?frame=0")[2])   # and repeats
        self.assertEqual(http(self.port, "/api/camera_proxy/camera.nope")[0], 404)

    def test_auth_manifest_index_and_rest(self):
        status, _, body = http(self.port, "/auth/token", "POST", b"grant_type=authorization_code&code=x",
                               {"Content-Type": "application/x-www-form-urlencoded"})
        tok = json.loads(body)
        self.assertEqual((status, tok["token_type"]), (200, "Bearer"))
        self.assertIn("refresh_token", tok)
        self.assertEqual(json.loads(http(self.port, "/manifest.json")[2])["name"], "Vitro Parity")
        status, headers, body = http(self.port, "/vitro-parity/sections")
        self.assertEqual((status, headers["Content-Type"]), (200, "text/html; charset=utf-8"))
        cfg = json.loads(http(self.port, "/api/config")[2])
        self.assertEqual((cfg["version"], cfg["location_name"]), ("2026.11.0", "Glassworks Lab"))
        self.assertEqual(json.loads(http(self.port, "/api/states/light.desk_lamp")[2])["state"], "on")
        self.assertEqual(http(self.port, "/api/states/light.nope")[0], 404)

    def test_authorize_redirect_without_following(self):
        class NoFollow(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k):
                return None
        opener = urllib.request.build_opener(NoFollow)
        with self.assertRaises(urllib.error.HTTPError) as cm:
            opener.open(f"http://127.0.0.1:{self.port}/auth/authorize?redirect_uri=http%3A%2F%2Fx%2Fcb&state=s1")
        self.assertEqual(cm.exception.code, 302)
        loc = cm.exception.headers["Location"]
        self.assertTrue(loc.startswith("http://x/cb?code=vitro&state=s1"), loc)

    def test_rest_service_call(self):
        status, _, body = http(self.port, "/api/services/light/turn_off", "POST", json.dumps({"entity_id": "light.desk_lamp"}).encode())
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)[0]["state"], "off")
        self.assertEqual(self.hub.states["light.desk_lamp"]["state"], "off")

    # -- websocket: the handshake and the plain answers

    def test_auth_and_version(self):
        ws = WS(self.port)
        self.assertEqual(ws.recv()["type"], "auth_required")
        ws.send({"type": "auth", "access_token": "x"})
        m = ws.recv()
        self.assertEqual((m["type"], m["ha_version"]), ("auth_ok", "2026.11.0"))
        ws.close()

    def test_config_services_panels_user_ping(self):
        cfg = self.ws.call("get_config")["result"]
        self.assertEqual(cfg["location_name"], "Glassworks Lab")
        self.assertEqual(cfg["time_zone"], "UTC")
        self.assertEqual((cfg["unit_system"]["temperature"], cfg["unit_system"]["length"]), ("°C", "km"))
        svcs = self.ws.call("get_services")["result"]
        for d, s in (("light", "toggle"), ("cover", "set_cover_position"), ("todo", "add_item"), ("alarm_control_panel", "alarm_disarm")):
            self.assertIn(s, svcs[d])
        panels = self.ws.call("get_panels")["result"]
        self.assertEqual(panels["vitro-parity"]["title"], "Vitro Parity")
        self.assertEqual(self.ws.call("auth/current_user")["result"]["id"], "vitro")
        self.assertIsNone(self.ws.call("supported_features", features={"coalesce_messages": 1})["result"])
        self.assertEqual(self.ws.call("ping")["type"], "pong")
        self.assertEqual(len(self.ws.call("get_states")["result"]), len(S.ENTITIES))

    def test_registries_and_small_commands(self):
        for cmd in ("config/device_registry/list", "repairs/list_issues"):
            self.assertTrue(self.ws.call(cmd)["success"], cmd)
        self.assertEqual({a["area_id"] for a in self.ws.call("config/area_registry/list")["result"]}, {"studio", "lounge"})
        self.assertEqual(self.ws.call("config/floor_registry/list")["result"][0]["floor_id"], "ground")
        self.assertEqual(self.ws.call("config/label_registry/list")["result"][0]["label_id"], "showcase")
        disp = self.ws.call("config/entity_registry/list_for_display")["result"]
        self.assertIn("light.desk_lamp", {e["ei"] for e in disp["entities"]})
        self.assertEqual(self.ws.call("config/entity_registry/get", entity_id="light.desk_lamp")["result"]["area_id"], "studio")
        self.assertEqual(self.ws.call("config/entity_registry/get", entity_id="person.alex")["error"]["code"], "not_found")
        self.assertIn("backlog", self.ws.call("recorder/info")["result"])
        self.assertIn("token", self.ws.call("brands/access_token")["result"])
        self.assertEqual(self.ws.call("lovelace/info")["result"]["resource_mode"], "storage")
        self.assertIn("authSig", self.ws.call("auth/sign_path", path="/api/x")["result"]["path"])
        self.assertEqual(self.ws.call("frontend/get_user_data", key="core")["result"], {"value": None})
        self.assertEqual(self.ws.call("no/such/command")["error"]["code"], "unknown_command")
        tr = self.ws.call("frontend/get_translations", category="entity_component", language="en")["result"]["resources"]
        self.assertEqual(tr["component.lock.entity_component._.state.locked"], "Locked")
        ic = self.ws.call("frontend/get_icons", category="entity_component")["result"]["resources"]
        self.assertIn("lightbulb", ic["light"]["_"]["default"])

    def test_user_data_and_notification_subscriptions(self):
        for cmd, kw in (("frontend/subscribe_user_data", {"key": "core"}), ("frontend/subscribe_system_data", {"key": "default_panel"}),
                        ("persistent_notification/subscribe", {}), ("labs/subscribe", {"domain": "x", "preview_feature": "y"})):
            self.ws.n += 1
            mid = self.ws.n
            self.ws.send(dict(kw, id=mid, type=cmd))
            self.assertTrue(self.ws.until(lambda m: m.get("id") == mid and m["type"] == "result")["success"])
            self.assertEqual(self.ws.until(lambda m: m.get("id") == mid and m["type"] == "event")["type"], "event")

    # -- themes, dashboards, resources

    def test_themes(self):
        t = self.ws.call("frontend/get_themes")["result"]
        self.assertEqual(t["default_theme"], "default")
        self.assertEqual(set(t["themes"]), {"glass_dusk", "solar_paper"})            # `default` is HA's own, always there
        g = t["themes"]["glass_dusk"]
        self.assertIn("linear-gradient", g["lovelace-background"])
        self.assertTrue(g["glass-surface"].startswith("rgba("))
        self.assertEqual(g["ha-card-border-radius"], "20px")
        self.assertEqual(set(g["modes"]), {"light", "dark"})
        # four hops: card-background-color -> ha-card-background -> glass-card-tinted -> glass-card -> glass-surface
        hops, key, seen = 0, "card-background-color", set()
        while g[key].startswith("var(--"):
            self.assertNotIn(key, seen, f"var() cycle at {key}")
            self.assertLess(hops, 4, f"more than four hops at {key}")
            seen.add(key)
            key = g[key][len("var(--"):-1]
            hops += 1
        self.assertEqual((hops, g[key].startswith("rgba(")), (4, True))
        self.assertEqual(t["themes"]["solar_paper"]["primary-color"], "#c77700")

    def test_dashboard_list_and_resources(self):
        dashes = self.ws.call("lovelace/dashboards/list")["result"]
        self.assertEqual([(d["url_path"], d["title"]) for d in dashes], [("vitro-parity", "Vitro Parity")])
        res = self.ws.call("lovelace/resources")["result"]
        urls = {r["url"]: r["type"] for r in res}
        self.assertEqual(urls["/local/vitro-demo/energy-ring-card.js"], "module")
        self.assertEqual(urls["/local/vitro-demo/wrapper-card.js"], "module")
        data = [u for u in urls if u.startswith("data:text/javascript;base64,")]
        self.assertEqual(len(data), 1)
        js = base64.b64decode(data[0].split(",", 1)[1]).decode()
        for needle in ("attachShadow", "color-mix(", "mdi:lightbulb", 'callService("light", "toggle"', "todo/item/list",
                       'customElements.define("vitro-panel-card"'):
            self.assertIn(needle, js)

    def test_lovelace_config_seven_views(self):
        cfg = self.ws.call("lovelace/config", url_path="vitro-parity", force=False)["result"]
        self.assertEqual(self.ws.call("lovelace/config", url_path=None)["result"], cfg)
        self.assertEqual(self.ws.call("lovelace/config", url_path="nope")["error"]["code"], "config_not_found")
        views = {v["path"]: v for v in cfg["views"]}
        self.assertEqual(list(views), ["sections", "room-detail", "themed", "hacs", "panel-custom", "mixed-island", "advanced"])
        types = {p: v["type"] for p, v in views.items()}
        self.assertEqual(types, {"sections": "sections", "room-detail": "masonry", "themed": "sections", "hacs": "sections",
                                 "panel-custom": "panel", "mixed-island": "sections", "advanced": "sections"})

        def cards(view):
            out = []

            def walk(c):
                out.append(c)
                for k in ("cards", "card"):
                    for sub in (c.get(k) if isinstance(c.get(k), list) else [c.get(k)] if c.get(k) else []):
                        walk(sub)
            for c in view.get("cards", []):
                walk(c)
            for s in view.get("sections", []):
                for c in s["cards"]:
                    walk(c)
            return out

        s = views["sections"]
        self.assertEqual(s["max_columns"], 3)
        self.assertEqual({b["entity"] for b in s["badges"]}, {"person.alex", "sensor.outdoor_temp", "binary_sensor.front_door"})
        self.assertEqual(len(s["sections"]), 3)
        kinds = {c["type"] for c in cards(s)}
        for k in ("heading", "clock", "tile", "thermostat", "weather-forecast", "gauge", "media-control", "conditional",
                  "entity-filter", "markdown", "alarm-panel", "todo-list"):
            self.assertIn(k, kinds)
        tiles = [c for c in cards(s) if c["type"] == "tile"]
        self.assertTrue(any(c.get("grid_options", {}).get("columns") == 6 for c in tiles))
        self.assertEqual({f["type"] for c in tiles for f in c.get("features", [])},
                         {"light-brightness", "select-options", "cover-open-close"})
        self.assertTrue(any(c.get("tap_action", {}).get("action") == "navigate" and c["tap_action"]["navigation_path"] == "/vitro-parity/room-detail"
                            for c in tiles))
        lock = next(c for c in tiles if c["entity"].startswith("lock."))
        self.assertIn("confirmation", lock["tap_action"])
        self.assertTrue(next(c for c in cards(s) if c["type"] == "clock")["show_seconds"])
        self.assertEqual(next(c for c in cards(s) if c["type"] == "entity-filter")["state_filter"], ["on"])
        self.assertIn("{{ states('sensor.outdoor_temp') }} °C", next(c for c in cards(s) if c["type"] == "markdown")["content"])
        self.assertEqual(next(c for c in cards(s) if c["type"] == "conditional")["conditions"][0]["entity"], "light.desk_lamp")

        r = views["room-detail"]
        self.assertTrue(r["subview"])
        self.assertEqual(r["back_path"], "/vitro-parity/sections")
        rc = cards(r)
        ent = next(c for c in rc if c["type"] == "entities")
        self.assertTrue(ent["show_header_toggle"])
        domains = {(e["entity"] if isinstance(e, dict) else e).split(".")[0] for e in ent["entities"] if not (isinstance(e, dict) and "type" in e)}
        self.assertTrue({"light", "switch", "fan", "cover", "sensor", "binary_sensor"} <= domains)
        self.assertEqual(len(next(c for c in rc if c["type"] == "glance")["entities"]), 5)
        spark = next(c for c in rc if c["type"] == "sensor")
        self.assertEqual((spark["graph"], spark["hours_to_show"]), ("line", 24))
        hist = next(c for c in rc if c["type"] == "history-graph")
        self.assertEqual((len(hist["entities"]), hist["hours_to_show"]), (3, 24))
        pics = [c for c in rc if c["type"] == "picture-entity"]
        self.assertEqual(len(pics), 2)
        self.assertEqual((pics[0]["camera_view"], pics[0]["show_name"], pics[0]["show_state"]), ("live", False, False))
        self.assertEqual((pics[1]["show_name"], pics[1]["show_state"]), (True, True))
        stack = next(c for c in rc if c["type"] == "horizontal-stack")
        self.assertEqual([c["type"] for c in stack["cards"]], ["button"] * 3)

        t = views["themed"]
        self.assertEqual(t["theme"], "glass_dusk")
        self.assertTrue(any(c.get("theme") == "solar_paper" for c in cards(t)))
        self.assertTrue(any("card_mod" in c for c in cards(t)))

        h = {c["type"] for c in cards(views["hacs"])}
        for k in ("mushroom-title-card", "mushroom-chips-card", "mushroom-light-card", "mushroom-entity-card", "mushroom-climate-card",
                  "mushroom-template-card", "bubble-card", "mini-graph-card", "clock-weather-card"):
            self.assertIn("custom:" + k, h)
        self.assertEqual({c["card_type"] for c in cards(views["hacs"]) if c["type"] == "custom:bubble-card"}, {"separator", "button"})

        p = views["panel-custom"]["cards"]
        self.assertEqual(len(p), 1)
        self.assertEqual(p[0]["type"], "custom:vitro-panel-card")
        self.assertEqual(p[0]["todo_entity"], "todo.panel_tasks")
        self.assertEqual(p[0]["rooms"][0], {"name": "Studio", "lights": ["light.desk_lamp", "light.ceiling_strip"]})

        m = {c["type"] for c in cards(views["mixed-island"])}
        self.assertTrue({"tile", "entities", "custom:vitro-energy-ring-card"} <= m)

        a = views["advanced"]
        self.assertEqual((a["title"], a["max_columns"], len(a["sections"])), ("Advanced", 3, 3))
        ac = cards(a)
        self.assertEqual([c["heading"] for c in ac if c["type"] == "heading"],
                         ["Floorplan & Environment", "Inline Tile Features", "Agenda, Activity & Wrapper Card"])
        plan = next(c for c in ac if c["type"] == "picture-elements")
        self.assertEqual(plan["image"], "/local/vitro-demo/floorplan.png")
        spots = {(e["type"], e["entity"]): (e["style"]["left"], e["style"]["top"]) for e in plan["elements"]}
        self.assertEqual(spots, {("state-icon", "light.desk_lamp"): ("30%", "40%"),
                                 ("state-icon", "binary_sensor.front_door"): ("80%", "25%"),
                                 ("state-label", "sensor.studio_power"): ("55%", "70%"),
                                 ("state-badge", "sensor.outdoor_temp"): ("20%", "75%")})
        self.assertEqual(next(c for c in ac if c["type"] == "humidifier")["entity"], "humidifier.studio_humidifier")
        feats = {c["entity"]: [f["type"] for f in c["features"]] for c in ac if c["type"] == "tile"}
        self.assertEqual(feats, {"climate.living_room": ["climate-hvac-modes", "target-temperature"],
                                 "fan.studio_fan": ["fan-speed"], "cover.studio_blind": ["cover-position"],
                                 "light.ceiling_strip": ["light-color-temp"],
                                 "media_player.studio_speaker": ["media-player-volume-slider"]})
        self.assertTrue(all(c["grid_options"]["columns"] == 6 for c in ac if c["type"] == "tile"))      # half width
        self.assertEqual(next(c for c in ac if c["type"] == "calendar")["entities"], ["calendar.studio_schedule"])
        log = next(c for c in ac if c["type"] == "logbook")
        self.assertEqual((log["entities"], log["hours_to_show"]),
                         (["light.desk_lamp", "switch.studio_fan_plug", "cover.studio_blind"], 24))
        wrap = next(c for c in ac if c["type"] == "custom:vitro-wrapper-card")
        self.assertEqual((wrap["entity"], wrap["title"]), ("sensor.studio_power", "HACS Wrapper Test"))

    def test_every_entity_the_dashboard_names_exists(self):
        cfg = self.ws.call("lovelace/config", url_path="vitro-parity")["result"]
        pat = re.compile(r"^(?:person|sensor|binary_sensor|light|switch|input_boolean|fan|cover|lock|select|input_select|climate|weather|"
                         r"media_player|alarm_control_panel|todo|camera|button|script|scene|sun|humidifier|calendar)\.[a-z0-9_]+$")
        found = set()

        def walk(o, key=None):
            if isinstance(o, dict):
                for k, v in o.items():
                    walk(v, k)
            elif isinstance(o, list):
                for v in o:
                    walk(v, key)
            elif isinstance(o, str) and pat.match(o) and key not in ("perform_action",):
                found.add(o)
        walk(cfg)
        self.assertGreater(len(found), 25)
        self.assertEqual(sorted(found - set(self.hub.states)), [])

    # -- subscribe_entities and call_service

    def subscribe(self, **kw):
        self.ws.n += 1
        mid = self.ws.n
        self.ws.send(dict(kw, id=mid, type="subscribe_entities"))
        self.assertTrue(self.ws.until(lambda m: m.get("id") == mid and m["type"] == "result")["success"])
        snap = self.ws.until(lambda m: m.get("id") == mid and m["type"] == "event")["event"]
        return mid, snap

    def next_diff(self, mid):
        return self.ws.until(lambda m: m.get("id") == mid and m["type"] == "event")["event"]["c"]

    def test_subscribe_entities_snapshot_and_filter(self):
        _, snap = self.subscribe()
        self.assertEqual(set(snap["a"]), set(S.ENTITIES))
        lamp = snap["a"]["light.desk_lamp"]
        self.assertEqual((lamp["s"], lamp["a"]["brightness"]), ("on", 180))
        self.assertTrue({"s", "a", "c", "lc", "lu"} <= set(lamp))
        _, part = self.subscribe(entity_ids=["light.desk_lamp", "sensor.outdoor_temp"])
        self.assertEqual(set(part["a"]), {"light.desk_lamp", "sensor.outdoor_temp"})

    def test_light_toggle_pushes_a_live_diff(self):
        mid, _ = self.subscribe()
        r = self.ws.call("call_service", domain="light", service="toggle", target={"entity_id": "light.desk_lamp"})
        self.assertTrue(r["success"])
        diff = self.next_diff(mid)
        self.assertEqual(diff["light.desk_lamp"]["+"]["s"], "off")
        self.assertIsNone(diff["light.desk_lamp"]["+"]["a"]["brightness"])
        self.ws.call("call_service", domain="light", service="turn_on", service_data={"brightness": 90, "rgb_color": [255, 0, 0]},
                     target={"entity_id": ["light.desk_lamp"]})
        on = self.next_diff(mid)["light.desk_lamp"]["+"]
        self.assertEqual((on["s"], on["a"]["brightness"], on["a"]["rgb_color"], on["a"]["color_mode"]), ("on", 90, [255, 0, 0], "rgb"))
        self.ws.call("call_service", domain="light", service="turn_on", service_data={"color_temp_kelvin": 4000},
                     target={"entity_id": "light.desk_lamp"})
        kelvin = self.next_diff(mid)["light.desk_lamp"]["+"]["a"]
        self.assertEqual((kelvin["color_mode"], kelvin["color_temp_kelvin"], kelvin["brightness"]), ("color_temp", 4000, 90))
        # a filtered subscriber hears only its own entities
        fid, _ = self.subscribe(entity_ids=["light.floor_lamp"])
        self.ws.call("call_service", domain="light", service="turn_off", target={"entity_id": "light.desk_lamp"})
        self.ws.call("ping")
        self.assertEqual([m for m in self.ws.buf if m.get("id") == fid], [])
        self.ws.call("call_service", domain="light", service="turn_on", target={"entity_id": "light.floor_lamp"})
        self.assertEqual(list(self.next_diff(fid)), ["light.floor_lamp"])

    def test_a_second_client_sees_the_change(self):
        other = WS(self.port)
        other.login()
        self.addCleanup(other.close)
        other.send({"id": 1, "type": "subscribe_entities"})
        other.until(lambda m: m.get("id") == 1 and m["type"] == "event")
        self.ws.call("call_service", domain="switch", service="toggle", target={"entity_id": "switch.coffee_maker"})
        diff = other.until(lambda m: m.get("id") == 1 and m["type"] == "event")["event"]["c"]
        self.assertEqual(diff["switch.coffee_maker"]["+"]["s"], "on")

    def state(self, entity):
        return self.hub.states[entity]

    def test_every_service_family(self):
        call = lambda d, s, e, **data: self.assertTrue(self.ws.call(  # noqa: E731
            "call_service", domain=d, service=s, service_data=data, target={"entity_id": e})["success"], (d, s))
        call("homeassistant", "turn_off", "switch.studio_fan_plug")
        self.assertEqual(self.state("switch.studio_fan_plug")["state"], "off")
        call("input_boolean", "toggle", "input_boolean.focus_mode")
        self.assertEqual(self.state("input_boolean.focus_mode")["state"], "on")
        call("fan", "set_percentage", "fan.studio_fan", percentage=80)
        self.assertEqual((self.state("fan.studio_fan")["state"], self.state("fan.studio_fan")["attributes"]["percentage"]), ("on", 80))
        call("fan", "set_percentage", "fan.studio_fan", percentage=0)
        self.assertEqual(self.state("fan.studio_fan")["state"], "off")
        call("climate", "set_temperature", "climate.living_room", temperature=17)
        c = self.state("climate.living_room")
        self.assertEqual((c["attributes"]["temperature"], c["attributes"]["hvac_action"]), (17.0, "idle"))
        call("climate", "set_hvac_mode", "climate.living_room", hvac_mode="off")
        self.assertEqual((self.state("climate.living_room")["state"], self.state("climate.living_room")["attributes"]["hvac_action"]), ("off", "off"))
        call("cover", "close_cover", "cover.studio_blind")
        self.assertEqual(self.state("cover.studio_blind")["state"], "closed")
        call("cover", "set_cover_position", "cover.studio_blind", position=30)
        self.assertEqual((self.state("cover.studio_blind")["state"], self.state("cover.studio_blind")["attributes"]["current_position"]), ("open", 30))
        call("cover", "toggle", "cover.studio_blind")
        self.assertEqual(self.state("cover.studio_blind")["state"], "closed")
        call("cover", "open_cover", "cover.studio_blind")
        call("cover", "stop_cover", "cover.studio_blind")
        self.assertEqual(self.state("cover.studio_blind")["state"], "open")
        call("lock", "unlock", "lock.front_door")
        self.assertEqual(self.state("lock.front_door")["state"], "unlocked")
        call("lock", "lock", "lock.front_door")
        self.assertEqual(self.state("lock.front_door")["state"], "locked")
        call("select", "select_option", "select.studio_scene", option="Relax")
        self.assertEqual(self.state("select.studio_scene")["state"], "Relax")
        call("input_select", "select_option", "input_select.studio_mode", option="Play")
        self.assertEqual(self.state("input_select.studio_mode")["state"], "Play")
        bad = self.ws.call("call_service", domain="select", service="select_option", service_data={"option": "Nope"},
                           target={"entity_id": "select.studio_scene"})
        self.assertFalse(bad["success"])
        call("media_player", "media_play_pause", "media_player.studio_speaker")
        self.assertEqual(self.state("media_player.studio_speaker")["state"], "paused")
        call("media_player", "media_next_track", "media_player.studio_speaker")
        self.assertEqual(self.state("media_player.studio_speaker")["attributes"]["media_title"], "Glass Lattice")
        call("media_player", "media_previous_track", "media_player.studio_speaker")
        self.assertEqual(self.state("media_player.studio_speaker")["attributes"]["media_title"], "Synthetic Sunrise")
        call("media_player", "volume_set", "media_player.studio_speaker", volume_level=0.8)
        self.assertEqual(self.state("media_player.studio_speaker")["attributes"]["volume_level"], 0.8)
        for svc, want in (("alarm_arm_home", "armed_home"), ("alarm_arm_away", "armed_away"), ("alarm_disarm", "disarmed")):
            call("alarm_control_panel", svc, "alarm_control_panel.home_alarm")
            self.assertEqual(self.state("alarm_control_panel.home_alarm")["state"], want)
        call("button", "press", "button.restart_hub")
        self.assertRegex(self.state("button.restart_hub")["state"], r"^20\d\d-")
        call("script", "turn_on", "script.good_morning")
        self.assertIsNotNone(self.state("script.good_morning")["attributes"]["last_triggered"])
        self.assertEqual(self.state("switch.coffee_maker")["state"], "on")           # the script's effect
        call("scene", "turn_on", "scene.movie_night")
        self.assertEqual((self.state("light.desk_lamp")["attributes"]["brightness"], self.state("light.ceiling_strip")["state"]), (60, "off"))
        self.assertEqual(self.ws.call("call_service", domain="light", service="explode", target={"entity_id": "light.desk_lamp"})["error"]["code"],
                         "service_not_found")

    def test_humidifier_services(self):
        mid, snap = self.subscribe(entity_ids=["humidifier.studio_humidifier"])
        h = snap["a"]["humidifier.studio_humidifier"]
        self.assertEqual((h["s"], h["a"]["humidity"], h["a"]["current_humidity"], h["a"]["mode"], h["a"]["action"]),
                         ("on", 50, 44, "comfort", "humidifying"))
        self.assertEqual(h["a"]["available_modes"], ["eco", "comfort", "boost"])
        call = lambda s, **data: self.ws.call("call_service", domain="humidifier", service=s, service_data=data,  # noqa: E731
                                              target={"entity_id": "humidifier.studio_humidifier"})
        self.assertTrue(call("set_humidity", humidity=60)["success"])
        self.assertEqual(self.next_diff(mid)["humidifier.studio_humidifier"]["+"]["a"]["humidity"], 60)
        call("set_humidity", humidity=95)
        self.assertEqual(self.state("humidifier.studio_humidifier")["attributes"]["humidity"], 80)        # clamped to max
        call("set_humidity", humidity=40)
        self.assertEqual((self.state("humidifier.studio_humidifier")["attributes"]["humidity"],
                          self.state("humidifier.studio_humidifier")["attributes"]["action"]), (40, "idle"))   # 44% is already above
        call("set_mode", mode="boost")
        self.assertEqual(self.state("humidifier.studio_humidifier")["attributes"]["mode"], "boost")
        self.assertFalse(call("set_mode", mode="turbo")["success"])
        call("toggle")
        self.assertEqual((self.state("humidifier.studio_humidifier")["state"],
                          self.state("humidifier.studio_humidifier")["attributes"]["action"]), ("off", "off"))
        call("turn_on")
        self.assertEqual(self.state("humidifier.studio_humidifier")["state"], "on")
        call("turn_off")
        self.assertEqual(self.state("humidifier.studio_humidifier")["state"], "off")
        svcs = self.ws.call("get_services")["result"]["humidifier"]
        self.assertEqual(set(svcs), {"toggle", "turn_on", "turn_off", "set_humidity", "set_mode"})

    def test_logbook_get_events(self):
        events = self.ws.call("logbook/get_events", start_time="2026-11-12T00:00:00+00:00", end_time="2026-11-13T00:00:00+00:00")["result"]
        self.assertEqual(len(events), 6)
        now = time.time()
        for e in events:
            self.assertEqual(set(e), {"when", "name", "state", "entity_id", "domain", "message"})
            self.assertIsInstance(e["when"], float)
            self.assertTrue(now - 86400 < e["when"] < now, e)                                # recent, inside a day
            self.assertEqual(e["domain"], e["entity_id"].split(".")[0])
            self.assertIn(e["entity_id"], self.hub.states)
        self.assertEqual([e["when"] for e in events], sorted(e["when"] for e in events))
        last = {e["entity_id"]: e["state"] for e in events}                                  # the log ends where the home starts
        for eid, state in last.items():
            self.assertEqual(self.state(eid)["state"], state, eid)
        only = self.ws.call("logbook/get_events", entity_ids=["cover.studio_blind"])["result"]
        self.assertEqual({e["entity_id"] for e in only}, {"cover.studio_blind"})
        self.ws.n += 1
        mid = self.ws.n
        self.ws.send({"id": mid, "type": "logbook/event_stream", "start_time": "2026-11-12T00:00:00+00:00",
                      "entity_ids": ["light.desk_lamp"]})
        self.assertTrue(self.ws.until(lambda m: m.get("id") == mid and m["type"] == "result")["success"])
        ev = self.ws.until(lambda m: m.get("id") == mid and m["type"] == "event")["event"]
        self.assertEqual({e["entity_id"] for e in ev["events"]}, {"light.desk_lamp"})

    def test_scene_arrives_as_one_diff(self):
        mid, _ = self.subscribe()
        self.ws.call("call_service", domain="scene", service="turn_on", target={"entity_id": "scene.movie_night"})
        diff = self.next_diff(mid)
        self.assertTrue({"scene.movie_night", "light.desk_lamp", "light.ceiling_strip", "cover.studio_blind"} <= set(diff))

    def test_area_target(self):
        for eid in ("light.floor_lamp", "light.reading_nook"):
            self.hub.set_state(eid, "off")
        mid, _ = self.subscribe(entity_ids=["light.floor_lamp", "light.reading_nook"])
        # The area also holds a climate entity and a sensor: only its lights take light.turn_on.
        self.assertTrue(self.ws.call("call_service", domain="light", service="turn_on", target={"area_id": "lounge"})["success"])
        self.assertEqual((self.state("light.floor_lamp")["state"], self.state("light.reading_nook")["state"]), ("on", "on"))
        self.assertEqual(set(self.next_diff(mid)), {"light.floor_lamp", "light.reading_nook"})

    def test_subscribe_events_gets_state_changed(self):
        self.ws.n += 1
        mid = self.ws.n
        self.ws.send({"id": mid, "type": "subscribe_events", "event_type": "state_changed"})
        self.ws.until(lambda m: m.get("id") == mid and m["type"] == "result")
        self.ws.call("call_service", domain="switch", service="turn_on", target={"entity_id": "switch.coffee_maker"})
        ev = self.ws.until(lambda m: m.get("id") == mid and m["type"] == "event")["event"]
        self.assertEqual(ev["event_type"], "state_changed")
        self.assertEqual((ev["data"]["old_state"]["state"], ev["data"]["new_state"]["state"]), ("off", "on"))
        self.assertTrue(self.ws.call("unsubscribe_events", subscription=mid)["success"])

    # -- history, statistics, templates, todo, weather

    def test_history_stream_and_period(self):
        ids = ["sensor.studio_humidity", "binary_sensor.studio_motion", "light.desk_lamp"]
        start, end = "2026-11-12T00:00:00+00:00", "2026-11-13T00:00:00+00:00"
        self.ws.n += 1
        mid = self.ws.n
        self.ws.send({"id": mid, "type": "history/stream", "entity_ids": ids, "start_time": start, "end_time": end,
                      "minimal_response": True, "no_attributes": False})
        self.assertTrue(self.ws.until(lambda m: m.get("id") == mid and m["type"] == "result")["success"])
        ev = self.ws.until(lambda m: m.get("id") == mid and m["type"] == "event")["event"]
        self.assertEqual(set(ev["states"]), set(ids))
        for e in ids:
            pts = ev["states"][e]
            self.assertEqual(len(pts), 48, e)
            self.assertEqual([p["lu"] for p in pts], sorted(p["lu"] for p in pts))
            self.assertTrue(all("s" in p for p in pts))
        self.assertIn("a", ev["states"]["sensor.studio_humidity"][0])
        self.assertEqual(ev["states"]["sensor.studio_humidity"][0]["lu"], 1794441600.0)
        float(ev["states"]["sensor.studio_humidity"][5]["s"])
        self.assertIn({p["s"] for p in ev["states"]["binary_sensor.studio_motion"]}.pop(), ("on", "off"))
        self.assertEqual(ev["states"]["light.desk_lamp"], self.ws.call(
            "history/history_during_period", entity_ids=ids, start_time=start, end_time=end)["result"]["light.desk_lamp"])
        again = self.ws.call("history/history_during_period", entity_ids=["sensor.studio_humidity"], start_time=start, end_time=end,
                             no_attributes=True)["result"]["sensor.studio_humidity"]
        self.assertEqual(len(again), 48)
        self.assertNotIn("a", again[0])
        self.assertEqual([p["s"] for p in again], [p["s"] for p in ev["states"]["sensor.studio_humidity"]])

    def test_statistics(self):
        r = self.ws.call("recorder/statistics_during_period", statistic_ids=["sensor.energy_today", "sensor.nope"], period="hour",
                         start_time="2026-11-12T00:00:00+00:00", end_time="2026-11-13T00:00:00+00:00", types=["mean", "sum"])["result"]
        self.assertEqual(list(r), ["sensor.energy_today"])
        pts = r["sensor.energy_today"]
        self.assertEqual(len(pts), 24)
        self.assertEqual(pts[1]["start"] - pts[0]["start"], 3600 * 1000)
        self.assertTrue({"mean", "min", "max", "sum", "state"} <= set(pts[0]))
        meta = self.ws.call("recorder/get_statistics_metadata", statistic_ids=["sensor.energy_today"])["result"]
        self.assertEqual(meta[0]["unit_of_measurement"], "kWh")

    def test_render_template_initial_and_live(self):
        self.ws.n += 1
        mid = self.ws.n
        tpl = ("{{ states('sensor.outdoor_temp') }} °C, {{ state_attr('light.desk_lamp', 'brightness') }}/255 "
               "{% if is_state('light.desk_lamp', 'on') %}on{% else %}off{% endif %}")
        self.ws.send({"id": mid, "type": "render_template", "template": tpl, "timeout": 3, "report_errors": True})
        self.assertTrue(self.ws.until(lambda m: m.get("id") == mid and m["type"] == "result")["success"])
        first = self.ws.until(lambda m: m.get("id") == mid and m["type"] == "event")["event"]
        self.assertEqual(first["result"], "7.4 °C, 180/255 on")
        self.assertIn("light.desk_lamp", first["listeners"]["entities"])
        self.ws.call("call_service", domain="light", service="turn_off", target={"entity_id": "light.desk_lamp"})
        second = self.ws.until(lambda m: m.get("id") == mid and m["type"] == "event")["event"]
        self.assertEqual(second["result"], "7.4 °C, None/255 off")

    def test_template_filters_and_numbers(self):
        def render(t):
            self.ws.n += 1
            mid = self.ws.n
            self.ws.send({"id": mid, "type": "render_template", "template": t})
            self.ws.until(lambda m: m.get("id") == mid and m["type"] == "result")
            return self.ws.until(lambda m: m.get("id") == mid and m["type"] == "event")["event"]["result"]
        self.assertEqual(render("{{ states('sensor.outdoor_temp') | float | round(0) }}"), 7.0)
        self.assertEqual(render("{{ states('sensor.studio_co2') | int > 600 }}"), True)
        self.assertEqual(render("{{ states('sensor.studio_co2') | int }}"), 612)
        self.assertEqual(render("{{ states.sensor.outdoor_temp.state }}"), 7.4)
        self.assertEqual(render("{{ states('light.nope') }}"), "unknown")
        self.assertEqual(render("{{ states('person.alex') | upper }}"), "HOME")

    def test_todo_list_updates(self):
        items = self.ws.call("todo/item/list", entity_id="todo.panel_tasks")["result"]["items"]
        self.assertEqual(items[0], {"uid": "1", "summary": "Calibrate studio lights", "status": "needs_action", "due": None, "description": None})
        self.assertEqual(len(items), 4)
        self.assertEqual(self.ws.call("todo/item/list", entity_id="todo.nope")["error"]["code"], "not_found")
        mid, _ = self.subscribe(entity_ids=["todo.panel_tasks"])
        self.ws.n += 1
        sid = self.ws.n
        self.ws.send({"id": sid, "type": "todo/item/subscribe", "entity_id": "todo.panel_tasks"})
        self.ws.until(lambda m: m.get("id") == sid and m["type"] == "result")
        self.assertEqual(len(self.ws.until(lambda m: m.get("id") == sid and m["type"] == "event")["event"]["items"]), 4)
        self.ws.call("call_service", domain="todo", service="update_item", service_data={"item": "Calibrate studio lights", "status": "completed"},
                     target={"entity_id": "todo.panel_tasks"})
        self.assertEqual(self.next_diff(mid)["todo.panel_tasks"]["+"]["s"], "2")
        self.assertEqual(self.ws.until(lambda m: m.get("id") == sid and m["type"] == "event")["event"]["items"][0]["status"], "completed")
        self.ws.call("call_service", domain="todo", service="add_item", service_data={"item": "Print a spare bracket"},
                     target={"entity_id": "todo.panel_tasks"})
        after = self.ws.call("todo/item/list", entity_id="todo.panel_tasks")["result"]["items"]
        self.assertEqual((len(after), after[-1]["summary"], after[-1]["status"]), (5, "Print a spare bracket", "needs_action"))
        got = self.ws.call("call_service", domain="todo", service="get_items", return_response=True,
                           service_data={"status": "completed"}, target={"entity_id": "todo.panel_tasks"})["result"]["response"]
        self.assertEqual({i["summary"] for i in got["todo.panel_tasks"]["items"]}, {"Calibrate studio lights", "Back up showcase config"})

    def test_conditions_one_shot_and_live(self):
        cond = {"condition": "and", "conditions": [{"condition": "state", "entity_id": "light.desk_lamp", "state": "on"},
                                                   {"condition": "numeric_state", "entity_id": "sensor.studio_co2", "above": 500}]}
        self.assertEqual(self.ws.call("test_condition", condition=cond)["result"], {"result": True})
        self.ws.n += 1
        mid = self.ws.n
        self.ws.send({"id": mid, "type": "subscribe_condition", "condition": cond})
        self.ws.until(lambda m: m.get("id") == mid and m["type"] == "result")
        self.assertTrue(self.ws.until(lambda m: m.get("id") == mid and m["type"] == "event")["event"]["result"])
        self.ws.call("call_service", domain="light", service="turn_off", target={"entity_id": "light.desk_lamp"})
        self.assertFalse(self.ws.until(lambda m: m.get("id") == mid and m["type"] == "event")["event"]["result"])

    def test_weather_forecast_subscription(self):
        self.ws.n += 1
        mid = self.ws.n
        self.ws.send({"id": mid, "type": "weather/subscribe_forecast", "entity_id": "weather.studio_forecast", "forecast_type": "daily"})
        self.ws.until(lambda m: m.get("id") == mid and m["type"] == "result")
        ev = self.ws.until(lambda m: m.get("id") == mid and m["type"] == "event")["event"]
        self.assertEqual((ev["type"], len(ev["forecast"])), ("daily", 5))

    def test_rest_history_period(self):
        t1 = time.time()
        a, b = _iso_z(t1 - 7200), _iso_z(t1)
        status, headers, body = http(self.port, f"/api/history/period/{a}?filter_entity_id=sensor.studio_power&end_time={b}&minimal_response&no_attributes")
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "application/json")
        data = json.loads(body)
        self.assertEqual(len(data), 1)
        series = data[0]
        self.assertGreater(len(series), 2)
        for p in series:
            self.assertEqual(p["entity_id"], "sensor.studio_power")
            float(p["state"])
            for k in ("last_changed", "last_updated"):
                t = datetime.datetime.fromisoformat(p[k]).timestamp()
                self.assertGreaterEqual(t, t1 - 7200 - 1)
                self.assertLessEqual(t, t1 + 1)
            self.assertIsInstance(p["attributes"], dict)
        times = [datetime.datetime.fromisoformat(p["last_updated"]).timestamp() for p in series]
        self.assertEqual(times, sorted(times))
        # the same curve the websocket serves for that window
        ws = self.ws.call("history/history_during_period", entity_ids=["sensor.studio_power"], start_time=a, end_time=b,
                          no_attributes=True)["result"]["sensor.studio_power"]
        self.assertEqual([p["state"] for p in series], [p["s"] for p in ws])

    def test_rest_history_multiple_and_edges(self):
        a = _iso_z(time.time() - 3600)
        data = json.loads(http(self.port, f"/api/history/period/{a}?filter_entity_id=sensor.studio_power,sensor.studio_humidity,sensor.nope")[2])
        self.assertEqual([s[0]["entity_id"] for s in data], ["sensor.studio_power", "sensor.studio_humidity"])
        self.assertEqual(data[0][0]["attributes"]["unit_of_measurement"], "W")      # attributes ride on the first point
        # no start (defaults to 24 h back), non-numeric entity, no filter
        data = json.loads(http(self.port, "/api/history/period?filter_entity_id=light.desk_lamp")[2])
        self.assertEqual(len(data), 1)
        self.assertIn(data[0][0]["state"], ("on", "off"))
        everything = json.loads(http(self.port, "/api/history/period")[2])
        self.assertEqual(len(everything), len(self.hub.states))
        self.assertEqual(json.loads(http(self.port, f"/api/history/period/{a}?filter_entity_id=sensor.nope")[2]), [])
        self.assertEqual(http(self.port, "/api/history/period/not-a-date")[0], 400)

    def test_camera_commands(self):
        caps = self.ws.call("camera/capabilities", entity_id="camera.studio_front")["result"]
        self.assertEqual(caps["frontend_stream_types"], [])             # no --hls: the card shows the multipart stream
        self.assertEqual(self.ws.call("camera/capabilities", entity_id="light.desk_lamp")["error"]["code"], "not_found")
        self.assertFalse(self.ws.call("camera/stream", entity_id="camera.studio_front")["success"])


class SynthDataTest(unittest.TestCase):
    """Hard rule 2: nothing in the home is real. Names and addresses are made up; no address but loopback."""

    def test_no_real_addresses_or_secrets(self):
        with open(os.path.join(HERE, "showcase_ha.py")) as f:
            text = f.read()
        for ip in re.findall(r"\b\d{1,3}(?:\.\d{1,3}){3}\b", text):
            self.assertIn(ip, ("127.0.0.1", "0.0.0.0"), ip)
        self.assertNotRegex(text, r"(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")                 # no MAC
        self.assertNotRegex(text, r"eyJ[A-Za-z0-9_-]{20,}")                              # no JWT-like token

    def test_template_rejects_private_names(self):
        hub = S.Hub()
        with self.assertRaises(S.TemplateError):
            S.render_template(hub, "{{ states.__class__ }}")

    def test_template_calls_only_its_helpers_and_short_circuits(self):
        hub = S.Hub()
        hub.set_state("light.desk_lamp", "off", {"brightness": None})
        # `and` stops at a false left side: the comparison with None is never evaluated.
        text, _ = S.render_template(hub, "{{ is_state('light.desk_lamp', 'on') and state_attr('light.desk_lamp', 'brightness') > 10 }}")
        self.assertEqual(text.strip(), "False")
        # A Python method is no template helper.
        with self.assertRaises(Exception):
            S.render_template(hub, "{{ 'x{0}'.format(1) }}")
        # Repetition is bounded before it allocates.
        with self.assertRaises(S.TemplateError):
            S.render_template(hub, "{{ 'x' * 1000000000 }}")
        with self.assertRaises(S.TemplateError):
            S.render_template(hub, "{{ '%1000000000s' % 'x' }}")
        self.assertEqual(S.render_template(hub, "{{ 7 % 4 }}")[0].strip(), "3")       # numeric modulo still works
        # state_attr hands out a copy: changing what it returns leaves the home alone.
        hub.states["select.studio_scene"]["attributes"]["options"] = ["a", "b"]
        got = hub.states["select.studio_scene"]["attributes"]["options"]
        _ = S.render_template(hub, "{{ state_attr('select.studio_scene', 'options') | length }}")
        self.assertIsNot(got, None)
        self.assertEqual(hub.states["select.studio_scene"]["attributes"]["options"], ["a", "b"])


class HubTest(unittest.TestCase):
    """The hub without a server: a service batch that fails midway, and the template output budget."""

    def test_a_target_that_fails_after_dispatch_leaves_no_earlier_change(self):
        hub = S.Hub()
        self.assertEqual(hub.states["light.desk_lamp"]["state"], "on")
        with self.assertRaises(S.ServiceError):
            # The blind has no turn_off: the lamp, dispatched first, must not stay off unannounced.
            hub.call_service("homeassistant", "turn_off", {}, ["light.desk_lamp", "cover.studio_blind"])
        self.assertEqual(hub.states["light.desk_lamp"]["state"], "on")
        hub.call_service("homeassistant", "turn_off", {}, ["light.desk_lamp"])
        self.assertEqual(hub.states["light.desk_lamp"]["state"], "off")

    def test_concatenation_replace_and_the_whole_output_share_the_budget(self):
        hub = S.Hub()
        for tpl in ("{{ 'x' * 60000 ~ 'x' * 60000 }}", "{{ ('x' * 60000) | replace('x', 'yy') }}",
                    "{{ 'x' * 40000 }}{{ 'y' * 40000 }}"):
            with self.assertRaises(S.TemplateError, msg=tpl):
                S.render_template(hub, tpl)
        self.assertEqual(S.render_template(hub, "{{ 'ab' ~ 'cd' | replace('c', 'C') }}")[0], "abCd")


class HubMemoryTest(unittest.TestCase):
    def test_a_failed_batch_keeps_the_lights_remembered_settings(self):
        hub = S.Hub()
        hub.call_service("light", "turn_on", {"brightness": 180}, ["light.desk_lamp"])
        hub.call_service("light", "turn_off", {}, ["light.desk_lamp"])
        before = dict(hub.memory)
        hub.call_service("light", "turn_on", {}, ["light.desk_lamp"])
        # Dimmed to 90: a turn_off would now save 90, unlike the 180 remembered.
        hub.call_service("light", "turn_on", {"brightness": 90}, ["light.desk_lamp"])
        with self.assertRaises(S.ServiceError):
            # The lamp's turn_off saves its settings; the blind then fails: the save goes with the rollback.
            hub.call_service("homeassistant", "turn_off", {}, ["light.desk_lamp", "cover.studio_blind"])
        self.assertEqual(hub.states["light.desk_lamp"]["state"], "on")
        self.assertEqual(hub.memory, before)
        hub.call_service("light", "turn_off", {}, ["light.desk_lamp"])
        hub.call_service("light", "turn_on", {}, ["light.desk_lamp"])
        self.assertEqual(hub.states["light.desk_lamp"]["attributes"].get("brightness"), 90)


if __name__ == "__main__":
    unittest.main()
