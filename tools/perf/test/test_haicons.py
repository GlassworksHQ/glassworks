"""python3 -m unittest discover -s tools/perf/test

HA core's icons for the fake Home Assistant (haicons.py, haicons.json, #780): the kept files are the
pinned release's for exactly the integrations the fake loads, and get_icons' resources are built
from them as HA core builds them, so the reference dashboards draw HA's default icons. Offline:
`python3 tools/perf/haicons.py` is the check against HA core's source."""
import json
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import dashboards as D  # noqa: E402
import haicons  # noqa: E402

LOADED = sorted(set(D.config()["components"]) | {"homeassistant"})


def default_icon(state):
    """The icon HA's frontend draws for a state from the entity_component resources, where nothing
    names one (src/data/icons.ts getEntityIcon at the pinned frontend: the device class's
    translations, else the domain's `_`; a state's own icon, else the default)."""
    domain = state["entity_id"].split(".")[0]
    comp = D.icons("entity_component").get(domain)
    if not comp:
        return None
    tr = comp.get(state["attributes"].get("device_class")) or comp["_"]
    return (tr.get("state") or {}).get(state["state"]) or tr.get("default")


class Data(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(haicons.DATA, encoding="utf-8") as f:
            cls.data = json.load(f)

    def test_pinned_to_the_release_the_fake_reports(self):
        import fakeha
        self.assertEqual((self.data["core"], self.data["commit"], self.data["license"]),
                         (haicons.CORE, haicons.COMMIT, "Apache-2.0"))
        self.assertEqual(haicons.CORE, fakeha.HA_VERSION)
        self.assertEqual(haicons.CORE, D.config()["version"])
        self.assertRegex(haicons.COMMIT, r"^[0-9a-f]{40}$")

    def test_one_entry_for_each_integration_the_fake_loads(self):
        """A component added to the fake's config without its icons fails here: run haicons.py --write."""
        self.assertEqual(sorted(self.data["components"]), LOADED)
        self.assertEqual(haicons.components(), LOADED)
        for c, entry in self.data["components"].items():
            if entry is not None:
                self.assertRegex(entry["sha256"], r"^[0-9a-f]{64}$", c)
                self.assertLessEqual(set(entry["icons"]), set(haicons.CATEGORIES), c)
        # The two the fake loads that have no icons.json at 2026.11.0.
        self.assertEqual(sorted(c for c, e in self.data["components"].items() if e is None), ["history", "stream"])

    def test_every_icon_is_an_mdi_name(self):
        names = re.findall(r'"([a-z]+:[^"]+)"', json.dumps(self.data["components"]))
        self.assertTrue(names)
        self.assertEqual({n.split(":")[0] for n in names if not n.startswith("http")}, {"mdi"})


class Resources(unittest.TestCase):
    def test_entity_component_for_every_loaded_domain_that_has_one(self):
        res = D.icons("entity_component")
        self.assertEqual(sorted(res), ["binary_sensor", "camera", "climate", "light", "sensor", "switch"])
        self.assertEqual(res["light"]["_"]["default"], "mdi:lightbulb")
        self.assertEqual(res["light"]["_"]["state"]["off"], "mdi:lightbulb-off")
        self.assertEqual(res["climate"]["_"]["default"], "mdi:thermostat")

    def test_every_entity_in_the_home_gets_an_icon_from_ha(self):
        """Before #780 none did: the frontend drew its own fallback (a bookmark for switches,
        binary sensors and cameras). Now each domain and device class the home uses has HA's."""
        drawn = {}
        for s in D.states().values():
            icon = default_icon(s)
            self.assertIsNotNone(icon, s["entity_id"])
            key = (s["entity_id"].split(".")[0], s["attributes"].get("device_class"), s["state"])
            drawn[key] = icon
        by_class = lambda dc: {v for (d, c, _), v in drawn.items() if d == "sensor" and c == dc}  # noqa: E731
        self.assertEqual([by_class(dc) for dc in ("power", "energy", "temperature", "humidity")],
                         [{"mdi:flash"}, {"mdi:lightning-bolt"}, {"mdi:thermometer"}, {"mdi:water-percent"}])
        self.assertEqual(drawn[("light", None, "on")], "mdi:lightbulb")
        self.assertEqual(drawn[("light", None, "off")], "mdi:lightbulb-off")
        self.assertEqual(drawn[("switch", None, "off")], "mdi:toggle-switch-variant-off")
        self.assertEqual(drawn[("binary_sensor", "door", "on")], "mdi:door-open")
        self.assertEqual(drawn[("camera", None, "streaming")], "mdi:video")

    def test_services_are_expanded_from_the_shorthand_as_ha_does(self):
        res = D.icons("services")
        self.assertIn("homeassistant", res)
        for domain, services in res.items():
            for name, icon in services.items():
                self.assertIsInstance(icon, dict, (domain, name))
                self.assertTrue(icon["service"].startswith("mdi:"), (domain, name))
        self.assertEqual(res["light"]["turn_on"]["service"], "mdi:lightbulb-on")
        self.assertEqual(haicons._service_icon("mdi:x"), {"service": "mdi:x"})

    def test_a_named_integration_alone_as_a_name_or_a_list(self):
        self.assertEqual(list(D.icons("services", "light")), ["light"])
        self.assertEqual(sorted(D.icons("services", ["light", "switch"])), ["light", "switch"])
        # HA has it but the fake doesn't load it, or it has no icons in that category: nothing.
        self.assertEqual(D.icons("services", "hue"), {})
        self.assertEqual(D.icons("entity_component", "frontend"), {})

    def test_concurrent_first_loads_each_see_every_component(self):
        """The fake answers each connection on its own thread: a request during the first load gets
        all of it or waits for none of it, never a part."""
        import threading
        from unittest import mock
        whole = {c for c, e in json.load(open(haicons.DATA, encoding="utf-8"))["components"].items() if e}
        seen, real = [], haicons._service_icon

        def slow(value):
            threading.Event().wait(0.01)        # widen the window, between components, a partial cache shows in
            return real(value)
        with mock.patch.object(haicons, "_loaded", None), mock.patch.object(haicons, "_service_icon", slow):
            threads = [threading.Thread(target=lambda: seen.append(set(haicons.load()))) for _ in range(8)]
            for t in threads:
                t.start()
            for t in threads:
                t.join()
        self.assertEqual(seen, [whole] * 8)

    def test_the_other_categories(self):
        # No loaded integration has platform entity icons (`entity`), as on a HA with only these.
        self.assertEqual(D.icons("entity"), {})
        self.assertIn("turned_on", D.icons("triggers")["light"])
        self.assertIn("is_on", D.icons("conditions", "switch")["switch"])
        self.assertIn("energy", D.icons("integration_category")["homeassistant"])
        self.assertIn("bluetooth", D.icons("integration_connectivity", "homeassistant")["homeassistant"])


if __name__ == "__main__":
    unittest.main()
