"""python3 -m unittest discover -s tools/vitro-parity/test

The parity harness's home for the fake HA (fakehome.py): fixture times, history, statistics, templates, HA core's
texts and icons, and the WebSocket answers the cards read, without a browser or a download. Also checks the committed
corpus: every case has a fixture that covers the entities its card names, and nothing in it looks real."""
import datetime
import json
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.dirname(HERE))
os.environ.setdefault("GW_PERF_FRONTEND", os.path.join(ROOT, "tools", "perf", "test", "stub_frontend"))
import fakehome as H  # noqa: E402

CORPUS = os.path.join(ROOT, "engines", "engine-vitro", "src", "test", "parity")


class Conn:
    """What fakeha's Conn gives a handler: result() and event(), kept here."""

    def __init__(self):
        self.out = []

    def result(self, mid, result=None, success=True, error=None):
        self.out.append({"id": mid, "type": "result", "success": success, ("result" if success else "error"): result if success else error})

    def event(self, mid, ev):
        self.out.append({"id": mid, "type": "event", "event": ev})


def load(path):
    with open(path) as f:
        return json.load(f)


def call(msg):
    c = Conn()
    H.HANDLERS[msg["type"]](c, dict(msg, id=1))
    return c.out


class Home(unittest.TestCase):
    def setUp(self):
        H.set_home({"now": "2026-11-12T10:08:00", "config": {"time_zone": "America/Chicago"}, "user": {"id": "parity-user", "name": "Parity"}})
        H.CASE.update(id=None, fixture={})

    def case(self, fx):
        H.CASE.update(id="t", fixture=fx)

    def test_now_is_local_time_in_the_homes_zone(self):
        self.assertEqual(H.NOW, datetime.datetime(2026, 11, 12, 16, 8, tzinfo=datetime.timezone.utc).timestamp())

    def test_fixture_times(self):
        self.assertEqual(H.at(-60), H.NOW - 60)
        self.assertEqual(H.at("-2h30m"), H.NOW - 9000)
        self.assertEqual(H.at("+1d"), H.NOW + 86400)
        self.assertEqual(H.resolve("@D+1"), "2026-11-13")
        self.assertEqual(H.resolve("@D-1 09:30"), "2026-11-11T09:30:00-06:00")
        self.assertEqual(H.resolve({"a": ["@0s"]}), {"a": [H.iso(H.NOW)]})
        with self.assertRaises(ValueError):
            H.offset("yesterday")

    def test_states_from_the_fixture(self):
        self.case({"states": {"light.a": {"state": "on", "last_changed": "-1h", "attributes": {"friendly_name": "A"}}}})
        st = H.states()["light.a"]
        self.assertEqual((st["state"], st["last_changed"], st["last_updated"]), ("on", H.NOW - 3600, H.NOW - 3600))
        self.assertEqual(st["attributes"], {"friendly_name": "A"})

    def test_history_starts_at_the_window_and_ends_in_the_current_state(self):
        self.case({"states": {"binary_sensor.m": {"state": "off", "last_changed": "-10m", "attributes": {}}},
                   "history": {"binary_sensor.m": {"steps": [["-30h", "off"], ["-3h", "on"], ["-10m", "off"]]}}})
        start = H.iso(H.NOW - 86400)
        rows = H.history(["binary_sensor.m"], start, None)["binary_sensor.m"]
        self.assertEqual([r["s"] for r in rows], ["off", "on", "off"])
        self.assertEqual(rows[0]["lu"], round(H.NOW - 86400, 6))
        self.assertNotIn("a", rows[0])

    def test_a_wave_is_the_same_every_time_and_keeps_its_quantum(self):
        spec = {"wave": {"min": 1010, "max": 1020, "period": "12h", "step": "10m", "decimals": 1, "quantum": 1}}
        self.case({"states": {}, "history": {"sensor.p": spec}})
        a = H.history(["sensor.p"], H.iso(H.NOW - 86400), None)
        self.assertEqual(a, H.history(["sensor.p"], H.iso(H.NOW - 86400), None))
        vals = [float(r["s"]) for r in a["sensor.p"]]
        self.assertTrue(all(1009 <= v <= 1021 and v == round(v) for v in vals), vals[:5])
        self.assertTrue(all(r["s"].endswith(".0") for r in a["sensor.p"]))

    def test_climate_rows_keep_their_own_attributes(self):
        self.case({"states": {"climate.c": {"state": "off", "last_changed": "-1h", "attributes": {"current_temperature": 18}}},
                   "history": {"climate.c": {"points": [["-5h", "heat", {"current_temperature": 20}], ["-1h", "off", {"current_temperature": 19}]]}}})
        rows = H.history(["climate.c"], H.iso(H.NOW - 6 * 3600), None, no_attributes=False, minimal=True)["climate.c"]
        self.assertEqual([(r["s"], r["a"]["current_temperature"]) for r in rows], [("heat", 20), ("off", 19)])

    def test_statistics_are_hourly_means_of_the_series(self):
        self.case({"states": {"sensor.t": {"state": "21", "attributes": {"state_class": "measurement", "unit_of_measurement": "°C",
                                                                           "device_class": "temperature"}}},
                   "history": {"sensor.t": {"wave": {"min": 20, "max": 22, "period": "24h", "step": "10m"}}}})
        meta = H.statistics_metadata(["sensor.t", "sensor.none"])
        self.assertEqual([(m["statistic_id"], m["has_mean"], m["unit_class"]) for m in meta], [("sensor.t", True, "temperature")])
        out = H.statistics(["sensor.t"], H.iso(H.NOW - 6 * 3600), None, "hour", ["mean", "min", "max"])["sensor.t"]
        self.assertGreaterEqual(len(out), 5)
        self.assertTrue(all(r["min"] <= r["mean"] <= r["max"] and r["end"] - r["start"] == 3600000 for r in out))

    def test_statistic_during_period_reads_the_window_itself(self):
        # A measurement that drops from 100 to 20 at 9:30 (local): a rolling 30 minutes back from 10:08 holds 20 only.
        self.case({"states": {"sensor.m": {"state": "20", "attributes": {"state_class": "measurement", "unit_of_measurement": "W"}},
                              "sensor.c": {"state": "20", "attributes": {"state_class": "total_increasing", "unit_of_measurement": "kWh"}}},
                   "history": {"sensor.m": {"points": [["-3h", "100"], ["-38m", "20"]]},
                               "sensor.c": {"points": [["-2h08m", "0"], ["-1h08m", "10"], ["-4m", "20"]]}}})
        win = H.statistic_during_period({"statistic_id": "sensor.m", "rolling_window": {"duration": {"minutes": 30}}})
        self.assertEqual((win["mean"], win["max"], win["min"]), (20.0, 20.0, 20.0))
        # A counter's change is its value at the end less at the start: both steps inside the window count.
        out = H.statistic_during_period({"statistic_id": "sensor.c", "fixed_period": {
            "start_time": H.iso(H.NOW - 2 * 3600 - 8 * 60), "end_time": H.iso(H.NOW)}})
        self.assertEqual(out, {"change": 20.0})
        # Time-weighted: 0 held from 8:00 to 9:00, 100 only at its end, is a mean of 0.
        self.case({"states": {"sensor.m": {"state": "100", "attributes": {"state_class": "measurement", "unit_of_measurement": "W"}},
                              "sensor.r": {"state": "10", "attributes": {"state_class": "total_increasing", "unit_of_measurement": "kWh"}},
                              "sensor.c": {"state": "20", "attributes": {"state_class": "total_increasing", "unit_of_measurement": "kWh"}}},
                   "history": {"sensor.m": {"points": [["-3h", "0"], ["-1h08m", "100"]]},
                               "sensor.r": {"points": [["-3h", "100"], ["-2h", "110"], ["-1h", "0"], ["-30m", "10"]]},
                               "sensor.c": {"points": [["-2h08m", "0"], ["-1h08m", "10"], ["-4m", "20"]]}}})
        weighted = H.statistic_during_period({"statistic_id": "sensor.m", "fixed_period": {
            "start_time": H.iso(H.NOW - 2 * 3600 - 8 * 60), "end_time": H.iso(H.NOW - 3600 - 8 * 60)}})
        self.assertEqual(weighted["mean"], 0.0)
        # Between steps: 0 until 10:01, then 100, over 10:00-10:08 is 87.5 (the series' own change, not a 5-minute grid).
        self.case({"states": {"sensor.m": {"state": "100", "attributes": {"state_class": "measurement", "unit_of_measurement": "W"}}},
                   "history": {"sensor.m": {"points": [["-3h", "0"], ["-7m", "100"]]}}})
        between = H.statistic_during_period({"statistic_id": "sensor.m", "fixed_period": {
            "start_time": H.iso(H.NOW - 8 * 60), "end_time": H.iso(H.NOW)}})
        self.assertEqual(between["mean"], 87.5)
        # Unavailable is a boundary held for no mean: 0 for 5 min, unavailable 10 min, 100 for 5 min is 50.
        self.case({"states": {"sensor.m": {"state": "100", "attributes": {"state_class": "measurement", "unit_of_measurement": "W"}}},
                   "history": {"sensor.m": {"points": [["-28m", "0"], ["-23m", "unavailable"], ["-13m", "100"], ["-8m", "100"]]}}})
        gap = H.statistic_during_period({"statistic_id": "sensor.m", "fixed_period": {
            "start_time": H.iso(H.NOW - 28 * 60), "end_time": H.iso(H.NOW - 8 * 60)}})
        self.assertEqual(gap["mean"], 50.0)
        self.case({"states": {"sensor.r": {"state": "10", "attributes": {"state_class": "total_increasing", "unit_of_measurement": "kWh"}},
                              "sensor.c": {"state": "20", "attributes": {"state_class": "total_increasing", "unit_of_measurement": "kWh"}}},
                   "history": {"sensor.r": {"points": [["-3h", "100"], ["-2h", "110"], ["-1h", "0"], ["-30m", "10"]]},
                               "sensor.c": {"points": [["-2h08m", "0"], ["-1h08m", "10"], ["-4m", "20"]]}}})
        # A meter that resets keeps what it counted: 100 -> 110, reset, -> 10 is 20.
        reset = H.statistic_during_period({"statistic_id": "sensor.r", "rolling_window": {"duration": {"hours": 3}}})
        self.assertEqual(reset, {"change": 20.0})
        # The current hour, not the year to date.
        hour = H.statistic_during_period({"statistic_id": "sensor.c", "calendar": {"period": "hour"}})
        self.assertEqual(hour, {"change": 10.0})

    def test_templates_render_as_ha_does(self):
        self.case({"states": {"sensor.h": {"state": "47.3", "attributes": {"unit_of_measurement": "%"}},
                              "climate.c": {"state": "heat_cool", "attributes": {}}}})
        self.assertEqual(H.render_template("{{ states('sensor.h') | float(0) | round(0) | int }}%"), "47%")
        self.assertEqual(H.render_template("{{ states('sensor.h') | float(0) | round(0) | int }}"), 47)
        # Jinja's own title filter, which HA keeps: an underscore doesn't start a word.
        self.assertEqual(H.render_template("{{ states('climate.c') | title }}"), "Heat_cool")
        self.assertEqual(H.render_template("{{ state_attr('sensor.h', 'unit_of_measurement') }}"), "%")
        self.assertEqual(H.render_template("{{ is_state('sensor.h', '47.3') }}"), True)
        self.assertEqual(H.render_template("{{ states.sensor.h.state }}"), 47.3)
        self.assertEqual(H.render_template("{{ states('sensor.missing') }}"), "unknown")
        self.assertEqual(H.render_template("{{ now().hour }}"), 10)

    def test_render_template_subscription(self):
        self.case({"states": {"sensor.h": {"state": "5", "attributes": {}}}})
        out = call({"type": "render_template", "template": "{{ states('sensor.h') }} W"})
        self.assertEqual(out[0]["success"], True)
        self.assertEqual(out[1]["event"]["result"], "5 W")
        bad = call({"type": "render_template", "template": "{{ 1 / 0 }}", "report_errors": True})
        self.assertFalse(bad[0]["success"])

    def test_ha_core_texts_and_icons(self):
        tr = H.translations("entity_component", ["humidifier", "alarm_control_panel"])
        self.assertEqual(tr["component.humidifier.entity_component._.state.on"], "On")
        self.assertEqual(tr["component.alarm_control_panel.entity_component._.state.armed_away"], "Armed away")
        self.assertEqual(H.icons("entity_component", ["lock"])["lock"]["_"]["default"], "mdi:lock")
        self.assertTrue(all(k.startswith("component.") for k in H.translations("entity_component")))
        self.assertEqual(H.HACORE["version"], H.HA_VERSION)

    def test_translations_in_the_users_language_fall_back_to_english_per_key(self):
        de = H.translations_for("entity_component", ["weather", "light"], "de")
        en = H.translations("entity_component", ["weather", "light"])
        self.assertEqual(de["component.weather.entity_component._.state.partlycloudy"], "Teilweise bewölkt")
        self.assertEqual(de["component.light.entity_component._.state.off"], "Aus")
        # Every English key is there, in German where HA has it (HA core's helpers/translation.py).
        self.assertEqual(set(de), set(en))
        self.assertEqual(H.translations_for("entity_component", ["weather"], "en"), H.translations("entity_component", ["weather"]))
        self.assertEqual(H.translations_for("entity_component", ["weather"], "xx"), H.translations("entity_component", ["weather"]))

    def test_the_users_profile_is_the_cases(self):
        self.assertIsNone(H.user_data("language"))
        self.case({"user_data": {"language": {"language": "fr", "number_format": "space_comma"}}})
        self.assertEqual(H.user_data("language"), {"language": "fr", "number_format": "space_comma"})
        self.assertIsNone(H.user_data("core"))
        # What the page gets over the socket.
        out = call({"type": "frontend/subscribe_user_data", "key": "language"})
        self.assertEqual(out[1]["event"], {"value": {"language": "fr", "number_format": "space_comma"}})
        out = call({"type": "frontend/get_translations", "category": "entity_component", "integration": ["weather"], "language": "fr"})
        self.assertEqual(out[0]["result"]["resources"]["component.weather.entity_component._.state.cloudy"], "Nuageux")

    def test_the_signed_in_user_is_an_administrator_unless_the_case_says_not(self):
        self.case({})
        me = call({"type": "auth/current_user"})[0]["result"]
        self.assertTrue(me["is_admin"])
        self.assertTrue(me["is_owner"])
        # A case about what HA shows someone who isn't (its error cards' titles): the same user, no administrator.
        self.case({"admin": False})
        me = call({"type": "auth/current_user"})[0]["result"]
        self.assertFalse(me["is_admin"])
        self.assertFalse(me["is_owner"])
        listed = call({"type": "config/auth/list"})[0]["result"][0]
        self.assertEqual((listed["id"], listed["is_admin"], listed["group_ids"]), (me["id"], False, ["system-users"]))

    def test_the_cards_subscriptions(self):
        self.case({"todo": {"todo.c": [{"summary": "x", "uid": "item-1", "status": "needs_action", "due": "@D+1"}]},
                   "forecast": {"weather.h": {"daily": [{"datetime": "@D+1 12:00", "condition": "sunny"}]}},
                   "calendar": {"calendar.f": [{"summary": "All day", "start": "@D+0", "end": "@D+1"},
                                               {"summary": "Later", "start": "@D+40 09:00", "end": "@D+40 10:00"}]},
                   "logbook": [{"entity_id": "light.a", "state": "on", "when": "-2h"}, {"entity_id": "light.b", "state": "on", "when": "-1h"}]})
        self.assertEqual(call({"type": "todo/item/subscribe", "entity_id": "todo.c"})[1]["event"]["items"][0]["due"], "2026-11-13")
        fc = call({"type": "weather/subscribe_forecast", "entity_id": "weather.h", "forecast_type": "daily"})[1]["event"]
        self.assertEqual((fc["type"], fc["forecast"][0]["datetime"]), ("daily", "2026-11-13T12:00:00-06:00"))
        self.assertFalse(call({"type": "weather/subscribe_forecast", "entity_id": "weather.h", "forecast_type": "hourly"})[0]["success"])
        cal = call({"type": "calendar/event/subscribe", "entity_id": "calendar.f", "start": H.iso(H.NOW - 86400 * 10),
                    "end": H.iso(H.NOW + 86400 * 10)})[1]["event"]["events"]
        self.assertEqual([(e["summary"], e["all_day"]) for e in cal], [("All day", True)])
        log = call({"type": "logbook/event_stream", "start_time": H.iso(H.NOW - 86400), "entity_ids": ["light.a"]})
        self.assertEqual([e["entity_id"] for e in log[1]["event"]["events"]], ["light.a"])
        self.assertTrue(log[1]["event"]["partial"])
        self.assertEqual(log[2]["event"]["events"], [])

    def test_registries_and_users(self):
        self.case({"entities": [{"ei": "light.a", "pl": "zha", "di": "dev-a", "hn": True}], "devices": [{"id": "dev-a", "name": "A"}],
                   "areas": [{"area_id": "kitchen", "name": "Kitchen"}], "users": [{"id": "u2", "name": "Alex"}]})
        self.assertEqual(H.registry()["entities"][0]["ei"], "light.a")
        self.assertEqual((H.devices()[0]["name"], H.devices()[0]["name_by_user"]), ("A", None))
        self.assertEqual(H.areas()[0]["picture"], None)
        self.assertEqual([u["name"] for u in H.users()], ["Parity", "Alex"])
        self.assertTrue(H.user()["is_admin"])

    def test_rest_history_as_mini_graph_reads_it(self):
        self.case({"states": {"sensor.t": {"state": "21.5", "last_changed": "-5m", "attributes": {"unit_of_measurement": "°C"}}},
                   "history": {"sensor.t": {"steps": [["-2d", "20.0"], ["-3h", "21.0"], ["-5m", "21.5"]]}}})
        start = H.iso(H.NOW - 86400).replace("+00:00", "Z")
        code, body, ctype = H.http_get(f"/api/history/period/{start}", {"filter_entity_id": ["sensor.t"], "minimal_response": [""]})
        rows = json.loads(body)[0]
        self.assertEqual((code, ctype), (200, "application/json"))
        self.assertEqual(rows[0]["entity_id"], "sensor.t")
        self.assertEqual([r["state"] for r in rows], ["20.0", "21.0", "21.5"])
        self.assertNotIn("entity_id", rows[1])

    def test_a_series_is_the_one_timeline_of_state_history_and_statistics(self):
        # The fixture's own state and time say otherwise: the series wins, for the live state too.
        self.case({"states": {"sensor.p": {"state": "999", "last_changed": "-5h", "attributes": {"state_class": "measurement"}},
                              "climate.c": {"state": "heat", "last_changed": "-1m", "attributes": {"current_temperature": 30}}},
                   "history": {"sensor.p": {"wave": {"min": 1010, "max": 1020, "period": "12h", "step": "10m", "decimals": 1, "quantum": 1}},
                               "climate.c": {"points": [["-5h", "heat", {"current_temperature": 20}], ["-2h", "off", {"current_temperature": 19}],
                                                        ["-1h", "off", {"current_temperature": 18.5}]]}}})
        sts = H.states()
        rows = H.history(["sensor.p"], H.iso(H.NOW - 86400), None)["sensor.p"]
        self.assertEqual((sts["sensor.p"]["state"], sts["sensor.p"]["last_changed"]), (rows[-1]["s"], rows[-1]["lu"]))
        stats = H.statistics(["sensor.p"], H.iso(H.NOW - 3 * 3600), None, "hour", ["max", "min"])["sensor.p"]
        last = stats[-1]
        inside = [float(r["s"]) for r in rows if last["start"] / 1000 <= r["lu"] < last["end"] / 1000]
        self.assertTrue(all(last["min"] <= v <= last["max"] for v in inside))
        c = sts["climate.c"]
        self.assertEqual((c["state"], c["last_changed"], c["last_updated"], c["attributes"]["current_temperature"]),
                         ("off", H.NOW - 7200, H.NOW - 3600, 18.5))
        crow = H.history(["climate.c"], H.iso(H.NOW - 6 * 3600), None, no_attributes=False)["climate.c"]
        self.assertEqual([(r["s"], r["a"]["current_temperature"]) for r in crow], [("heat", 20), ("off", 19), ("off", 18.5)])

    def test_templates_cannot_reach_python(self):
        with self.assertRaises(Exception):
            H.render_template("{{ cycler.__init__.__globals__.os.popen('id').read() }}")

    def test_fixture_files_stay_in_the_fixtures_folder(self):
        H.HOME["fixtures_dir"] = os.path.join(CORPUS, "fixtures")
        code, body, ctype = H.http_get("/local/vitro-harness-fixtures/images/album.png", {})
        self.assertEqual((code, ctype), (200, "image/png"))
        self.assertEqual(H.http_get("/local/vitro-harness-fixtures/../home.json", {})[0], 404)
        self.assertIsNone(H.http_get("/lovelace/0", {}))

    def test_community_cards_are_pinned(self):
        res = {r["url"].split("/")[2] for r in H._cards.resources(H.CARDS)}
        for name in ("lovelace-mushroom", "lovelace-card-mod", "mini-graph-card", "Bubble-Card", "clock-weather-card"):
            self.assertIn(name, res)
        for c in H.CARDS:
            self.assertIn(c["main"], c["files"])
            self.assertTrue(all(re.fullmatch(r"[0-9a-f]{64}", f["sha256"]) for f in c["files"].values()))
            self.assertIn(c["license"], ("MIT", "Apache-2.0"))
        self.assertEqual(H.http_get("/hacsfiles/mini-graph-card/not-pinned.js", {})[0], 404)
        self.assertEqual(H.http_get("/hacsfiles/mini-graph-card/../x.js", {})[0], 404)


ID = re.compile(r"\b([a-z_]+\.[a-z0-9_]+)\b")
DOMAINS = set(H._domains())


class Corpus(unittest.TestCase):
    """The committed corpus: each case's fixture serves what its card shows, and is made up."""

    def cases(self):
        d = os.path.join(CORPUS, "corpus")
        for f in sorted(os.listdir(d)):
            if f.endswith(".json"):
                with open(os.path.join(d, f)) as fh:
                    yield f[:-5], json.load(fh)

    def test_every_case_has_a_fixture_with_the_entities_its_card_names(self):
        # An entity whose object id starts with "missing" is one HA doesn't have, on purpose (HA's "Entity not found").
        missing = []
        for cid, doc in self.cases():
            fx = doc.get("fixture")
            if not isinstance(fx, dict):
                missing.append(f"{cid}: no fixture")
                continue
            named = {m for m in ID.findall(json.dumps(doc.get("card") or doc.get("view_config"))) if m.split(".")[0] in DOMAINS and not m.startswith("sun.") and not m.split(".", 1)[1].startswith("missing")}
            named = {m for m in named if not re.match(r"^(todo|calendar|person)\.(item|event)", m)}
            for e in sorted(named - set(fx.get("states") or {})):
                missing.append(f"{cid}: {e} has no state")
            for e in sorted(set(doc.get("expect") or {}) - set(fx.get("states") or {})):
                missing.append(f"{cid}: expects {e}, which has no state")
            for e, want in (doc.get("expect") or {}).items():
                got = (fx.get("states") or {}).get(e, {}).get("state")
                if got is not None and got != want:
                    missing.append(f"{cid}: expects {e} {want}, the fixture says {got}")
        self.assertEqual(missing, [])

    def test_the_fixtures_times_and_series_resolve(self):
        H.set_home(load(os.path.join(CORPUS, "home.json")))
        for cid, doc in self.cases():
            H.CASE.update(id=cid, fixture=doc["fixture"])
            sts = H.states()
            for eid in (doc["fixture"].get("history") or {}):
                self.assertTrue(H.history([eid], H.iso(H.NOW - 7 * 86400), None).get(eid), f"{cid}: {eid} has no history")
            self.assertEqual(set(sts), set(doc["fixture"].get("states") or {}))

    def test_each_fixture_is_on_one_timeline(self):
        """A state with a history series says what the series says at now (fakehome.py sync writes it)."""
        self.assertEqual(H.sync_fixtures(CORPUS, check=True), [], "run: python3 tools/vitro-parity/fakehome.py sync")

    def test_each_reference_holds_what_its_fixture_serves(self):
        """A fixture edited without a new capture is caught here, with no browser: each committed reference's states
        and recorded WebSocket answers are what the fake serves for that fixture now."""
        H.set_home(load(os.path.join(CORPUS, "home.json")))
        cases = dict(self.cases())
        stale = []
        refs = os.path.join(CORPUS, "refs")
        for frame in sorted(os.listdir(refs)):
            if not os.path.isdir(os.path.join(refs, frame)):
                continue
            for f in sorted(os.listdir(os.path.join(refs, frame))):
                cid = f[:-5]
                if not f.endswith(".json") or cid not in cases:
                    continue
                ref = load(os.path.join(refs, frame, f))
                H.CASE.update(id=cid, fixture=cases[cid]["fixture"])
                if ref.get("now") != round(H.NOW * 1000):
                    stale.append(f"{frame}/{cid}: drawn at {ref.get('now')}, home.json says {round(H.NOW * 1000)}")
                    continue
                sts = H.states()
                for eid, st in ref.get("states", {}).items():
                    want = sts.get(eid)
                    same_time = want is not None and abs(st.get("lc", 0) - want["last_changed"]) < 1e-3 and \
                        abs(st.get("lu", st.get("lc", 0)) - want["last_updated"]) < 1e-3
                    if want is None or (st["s"], st["a"]) != (want["state"], want["attributes"]) or not same_time:
                        stale.append(f"{frame}/{cid}: {eid}")
                for c in ref.get("ws", []):
                    req = c["request"]
                    if req["type"] == "rest/history/period":
                        query = {"filter_entity_id": [",".join(req["entity_ids"])]}
                        if req.get("end_time"):
                            query["end_time"] = [req["end_time"]]
                        for flag in ("minimal_response", "no_attributes"):
                            if req.get(flag):
                                query[flag] = [""]
                        code, body, _ = H.http_get(f"/api/history/period/{req['start_time']}", query)
                        if code != 200 or json.loads(body) != c.get("result"):
                            stale.append(f"{frame}/{cid}: {req['type']}")
                        continue
                    out = call(dict(req))
                    if "error" in c:
                        ok = not out[0]["success"]
                    elif not c.get("events"):        # a one-shot command: its result
                        ok = out[0].get("result") == c.get("result")
                    else:
                        ok = [m["event"] for m in out[1:]][:len(c["events"])] == c["events"]
                    if not ok:
                        stale.append(f"{frame}/{cid}: {req['type']}")
        self.assertEqual(stale, [], "references out of date with their fixtures: recapture them (tools/vitro-parity/run.sh --refresh --cases ...)")


if __name__ == "__main__":
    unittest.main()
