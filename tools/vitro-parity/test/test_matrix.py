"""python3 -m unittest discover -s tools/vitro-parity/test

The corpus's state matrix (matrix.py): the committed generated cases are what the generator makes, each has a
reference, and the matrix crosses each card with the states real homes are in."""
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import matrix as M  # noqa: E402

PARITY = os.path.dirname(M.CORPUS)


class MatrixTest(unittest.TestCase):
    def test_the_committed_cases_are_what_the_generator_makes(self):
        self.assertEqual(M.main(["--check"]), 0, "run python3 tools/vitro-parity/matrix.py, then refresh the new cases' refs")

    def test_every_generated_case_has_its_references(self):
        with open(os.path.join(PARITY, "frames.json"), encoding="utf-8") as f:
            frames = [fr["id"] for fr in json.load(f)["frames"]]
        # ParityReference.find needs both the PNG and its JSON.
        missing = [f"{fr}/{cid}{ext}" for cid, case in M.all_cases().items() for fr in case.get("frames", frames)
                   for ext in (".png", ".json")
                   if not os.path.isfile(os.path.join(PARITY, "refs", fr, cid + ext))]
        self.assertEqual(missing, [], "tools/vitro-parity/run.sh --refresh --cases <ids>")

    def test_a_hand_written_case_with_a_generated_name_is_never_overwritten(self):
        import tempfile
        cid = next(iter(M.all_cases()))
        with tempfile.TemporaryDirectory() as d:
            hand = os.path.join(d, cid + ".json")
            with open(hand, "w", encoding="utf-8") as f:
                f.write('{"id": "hand-written"}\n')
            saved = M.CORPUS
            M.CORPUS = d
            try:
                self.assertEqual(M.main([]), 1)
                self.assertEqual(M.main(["--check"]), 1)
            finally:
                M.CORPUS = saved
            with open(hand, encoding="utf-8") as f:
                self.assertEqual(f.read(), '{"id": "hand-written"}\n')
            self.assertEqual(os.listdir(d), [cid + ".json"], "nothing else written")

    def test_the_states_that_hid_bugs_are_in_the_matrix(self):
        cases = M.all_cases()
        # A dimmable light that is on, with a brightness: the Mushroom bar drawn as a square went unseen without it.
        light = cases["m-mushroom-light-light-on-dimmed"]["fixture"]["states"]
        (st,) = light.values()
        self.assertEqual((st["state"], st["attributes"]["brightness"]), ("on", 166))
        # A person chip.
        self.assertIn("m-mushroom-chip-entity-person-home", cases)
        # Unavailable, unknown and a long name, somewhere in each kind.
        for want in ("unavailable", "unknown", "long-name"):
            self.assertTrue(any(cid.endswith(want) for cid in cases), want)

    def test_the_dashboards_run_on_every_frame(self):
        for cid, case in M.dashboard_cases().items():
            self.assertNotIn("frames", case, cid)
            self.assertIn("view_config", case, cid)

    def test_generated_names_are_made_up(self):
        for cid, case in M.all_cases().items():
            for eid, st in case["fixture"]["states"].items():
                self.assertNotIn("@", st["attributes"].get("friendly_name", ""), f"{cid} {eid}")


if __name__ == "__main__":
    unittest.main()
