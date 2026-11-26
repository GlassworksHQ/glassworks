"""python3 -m unittest discover -s tools/size/test

tools/size/apksize.py on small made-up APKs and apkanalyzer listings: who owns what, the parts adding up
to the file, the assets lint, the budgets (and a budget raised in the same PR), and the comment
rendered from numbers only. No Android SDK needed."""
import copy
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(os.path.dirname(HERE), "apksize.py")
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.dirname(HERE))
import apksize as A  # noqa: E402
sys.path.insert(0, os.path.join(HERE, "..", ".."))
from pane_ids import main_sources, pane_ids_in  # noqa: E402

PARTS = A.read_json(os.path.join(os.path.dirname(HERE), "parts.json"))

# apkanalyzer `dex packages --defined-only` lines: C d <defined methods> <referenced methods> <size> <name>
CLASSES = """P d 10	10	10000	<TOTAL>
C d 1	1	4000	org.mozilla.geckoview.GeckoView
C d 1	1	1000	sh.edm.s1kiosk.MainActivity
C d 1	1	1000	dev.glassworks.assist.AssistPane
C d 1	1	500	dev.glassworks.VoiceOverlay$Ring
C d 1	1	500	dev.glassworks.speaker.SendspinPane
C d 1	1	1000	org.bouncycastle.crypto.Digest
C d 1	1	1000	org.tensorflow.lite.Interpreter
C d 1	1	500	dev.glassworks.s1.frame.TimeZonePane
C d 1	1	500	dev.glassworks.s1.frame.AqaraS1Plus
"""


def make_apk(path, extra=(), dex=20_000, xul=500_000, arm64=False):
    abi = "arm64-v8a" if arm64 else "armeabi-v7a"
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        z.writestr("classes.dex", b"d" * dex)
        z.writestr(f"lib/{abi}/libxul.so", b"x" * xul)
        z.writestr(f"lib/{abi}/libtensorflowlite_jni.so", b"t" * 30_000)
        z.writestr(f"lib/{abi}/libown.so", b"o" * 1_000)
        z.writestr("assets/omni.ja", b"j" * 100_000)
        z.writestr("assets/extension/content.js", b"c" * 2_000)
        z.writestr("org/bouncycastle/x509/CertPathReviewerMessages.properties", b"b" * 3_000)
        z.writestr("resources.arsc", b"r" * 4_000)
        for name, data in extra:
            z.writestr(name, data)
    return path


class Owners(unittest.TestCase):
    def setUp(self):
        self.apk = make_apk(os.path.join(tempfile.mkdtemp(), "a.apk"))
        self.b = A.breakdown(self.apk, A.parse_classes(CLASSES), PARTS)
        self.rows = A.rows(self.b, PARTS)

    def test_the_parts_add_up_to_the_file(self):
        self.assertEqual(sum(r["total"] for r in self.rows.values()), os.path.getsize(self.apk))

    def test_each_part_gets_its_own(self):
        r = self.rows
        self.assertEqual(r["engine"]["native"], 500_000)
        self.assertEqual(r["engine"]["assets"], 100_000)
        self.assertEqual(r["engine"]["dex"], 8_000)                  # 4000 of 10000 class bytes, of 20 000
        # Assist: its package, its overlay class, and TensorFlow Lite, which only it uses.
        self.assertEqual(r["pane:assist"]["dex"], 2_000 + 1_000 + 2_000)
        self.assertEqual(r["pane:assist"]["native"], 30_000)
        self.assertEqual(r["pane:time-zone"]["dex"], 1_000)           # one class, not its whole package
        self.assertEqual(r["pane:sendspin"]["dex"], 1_000)
        # BouncyCastle is the core's and Sendspin's: shared, in neither's own total.
        self.assertEqual(r["shared:bouncycastle"]["dex"], 2_000)
        self.assertEqual(r["shared:bouncycastle"]["other"], 3_000)
        self.assertEqual(r["core"]["native"], 1_000)                 # a native library nobody claims
        self.assertEqual(r["core"]["dex"], 2_000 + 1_000)            # MainActivity, AqaraS1Plus

    def test_a_prefix_needs_a_package_or_class_boundary(self):
        self.assertEqual(A.owner_of_class("dev.glassworks.s1.frame.TimeZonePane$1", PARTS), ("pane", "time-zone"))
        self.assertEqual(A.owner_of_class("dev.glassworks.s1.frame.TimeZonePanel", PARTS), ("core", None))
        self.assertEqual(A.owner_of_class("org.mozillax.Foo", PARTS), ("core", None))

    def test_the_longest_prefix_wins(self):
        """A library prefix that is a prefix of a pane's (the verifier's note 5), and the other way round."""
        parts = {"engine": {"dex": []}, "panes": {"p": {"dex": ["org.example.pane."]}, "q": {"dex": ["org.lib."]}},
                 "libraries": {"lib": {"dex": ["org.example."], "used_by": ["core"]},
                               "inner": {"dex": ["org.lib.inner."], "used_by": ["core"]}}}
        self.assertEqual(A.owner_of_class("org.example.pane.Foo", parts), ("pane", "p"))
        self.assertEqual(A.owner_of_class("org.example.Other", parts), ("library", "lib"))
        self.assertEqual(A.owner_of_class("org.lib.inner.X", parts), ("library", "inner"))
        self.assertEqual(A.owner_of_class("org.lib.Y", parts), ("pane", "q"))

    def test_vitro_is_its_own_engine_row(self):
        """Vitro, the second engine: its classes and its icon table are `engine:vitro`, a row of its own (not core's),
        and the icon table isn't an assets lint finding."""
        self.assertEqual(A.owner_of_class("dev.glassworks.engine.vitro.VitroEngine", PARTS), ("engine", "vitro"))
        self.assertEqual(A.owner_of_class("dev.glassworks.engine.gecko.GeckoEngine", PARTS), ("core", None))
        lint = []
        self.assertEqual(A.owner_of_file("assets/vitro-mdi.tsv", PARTS, lint), (("engine", "vitro"), "assets"))
        self.assertEqual(lint, [])
        b = {"owners": {"engine:vitro": dict(A.empty(), dex=7, assets=3)}, "bytes": 10, "stored_bytes": 10}
        r = A.rows(b, PARTS)
        self.assertEqual(r["engine:vitro"]["total"], 10)
        self.assertEqual(A.label("engine:vitro"), "Engine (Vitro)")

    def test_vitro_owns_its_java_resources(self):
        """Vitro's Java resources (its locale tables, native-options.json) sit at the APK's root under its package
        path: Vitro's row, not core's."""
        for path in ("dev/glassworks/engine/vitro/locale/de.json", "dev/glassworks/engine/vitro/compat/native-options.json"):
            self.assertEqual(A.owner_of_file(path, PARTS, []), (("engine", "vitro"), "other"))
        self.assertEqual(A.owner_of_file("dev/glassworks/other.json", PARTS, []), (("core", None), "other"))

    def test_vitro_carries_libwebrtc(self):
        """Vitro's WebRTC cameras (libs/ha-webrtc, engines/engine-vitro-webrtc): libwebrtc's classes, the library's and
        its native library count in Vitro's row, not core's."""
        lint = []
        self.assertEqual(A.owner_of_file("lib/arm64-v8a/liblkjingle_peerconnection_so.so", PARTS, lint),
                         (("engine", "vitro"), "native"))
        self.assertEqual(lint, [])
        for cls in ("livekit.org.webrtc.PeerConnection", "dev.glassworks.hawebrtc.HaWebRtcPlayer",
                    "dev.glassworks.engine.vitro.webrtc.VitroWebRtc"):
            self.assertEqual(A.owner_of_class(cls, PARTS), ("engine", "vitro"))

    def test_a_panes_classes_outside_its_package(self):
        """The verifier's note 6: classes only a pane uses, outside its package, are that pane's."""
        for name in ("dev.glassworks.VoiceOverlay", "dev.glassworks.VoiceOverlay$show$1",
                     "dev.glassworks.voice.VoiceAssistantController", "dev.glassworks.voice.WakeWordCatalog"):
            self.assertEqual(A.owner_of_class(name, PARTS), ("pane", "assist"), name)
        self.assertEqual(A.owner_of_class("dev.glassworks.s1.frame.TimeZoneKeeper", PARTS), ("pane", "time-zone"))
        self.assertEqual(A.owner_of_class("dev.glassworks.device.PanelDevice", PARTS), ("core", None))

    def test_the_voice_package_is_split_between_the_assist_pane_and_the_core(self):
        """#352: dev.glassworks.assist has classes in panes/assist and in core. Each counts for its module, so a
        class added to the pane must be listed in parts.json, and the core's players stay the core's."""
        decl = re.compile(r"^(?:(?:public|internal|data|sealed|enum|abstract|open|fun|annotation)\s+)*(?:class|object|interface)\s+(\w+)", re.M)
        top_level = re.compile(r"^(?:(?:private|internal|public)\s+)?(?:fun|val|var|const val)\s", re.M)

        def classes(module):
            d = os.path.join(ROOT, module, "src/main/java/dev/glassworks/assist")
            out = set()
            for f in sorted(os.listdir(d)):
                if f.endswith(".kt"):
                    text = open(os.path.join(d, f), encoding="utf-8").read()
                    out |= set(decl.findall(text))
                    if top_level.search(text):
                        out.add(f[:-3] + "Kt")  # Kotlin's class for a file's top-level functions
            return out

        pane, core = classes("panes/assist"), classes("core")
        self.assertGreaterEqual(len(pane), 10, pane)
        self.assertGreaterEqual(len(core), 5, core)
        for name in sorted(pane):
            self.assertEqual(A.owner_of_class(f"dev.glassworks.assist.{name}", PARTS), ("pane", "assist"), name)
            self.assertEqual(A.owner_of_class(f"dev.glassworks.assist.{name}$1", PARTS), ("pane", "assist"), name)
        for name in sorted(core):
            self.assertEqual(A.owner_of_class(f"dev.glassworks.assist.{name}", PARTS), ("core", None), name)
        self.assertEqual(A.owner_of_class("dev.glassworks.device.UrlPlayer", PARTS), ("core", None))
        self.assertEqual(PARTS["libraries"]["media3"]["used_by"], ["core", "engine"])

    def test_a_pane_with_no_code_is_noted(self):
        self.assertIn("radio", self.b["panes_without_code"])
        self.assertNotIn("assist", self.b["panes_without_code"])


class AssetsLint(unittest.TestCase):
    def lint(self, *names):
        apk = make_apk(os.path.join(tempfile.mkdtemp(), "a.apk"), [(n, b"z" * 10) for n in names])
        return A.breakdown(apk, A.parse_classes(CLASSES), PARTS)

    def test_pane_assets_belong_under_assets_panes_id(self):
        b = self.lint("assets/panes/assist/hey_jarvis.tflite")
        self.assertEqual(b["lint"], [])
        self.assertEqual(b["owners"]["pane:assist"]["assets"], 10)

    def test_an_asset_anywhere_else_fails(self):
        b = self.lint("assets/models/hey_jarvis.tflite", "assets/panes/kitchen/x.bin")
        self.assertEqual(len(b["lint"]), 2)
        self.assertIn("assets/models/hey_jarvis.tflite", b["lint"][0])
        self.assertIn("`kitchen` isn't a pane", b["lint"][1])

    def test_the_app_today_passes(self):
        """The kiosk's own assets (the GeckoView extension, the baseline profile, the licences) are the
        core's; GeckoView's omni.ja is the engine's."""
        self.assertEqual(self.lint("assets/dexopt/baseline.prof", "assets/licenses/LICENSE", "assets/blueprints.json")["lint"], [])
        # A file entry is that file only: a stray copy beside it isn't the core's.
        self.assertEqual(len(self.lint("assets/blueprints.json.bak")["lint"]), 1)

    def test_the_radio_bundle_is_the_radios(self):
        """The release's armeabi-v7a APK carries the signed radio bundle at assets/radio-bundle/, where
        RadioBundle.kt and release.yml's checks expect it: the radio pane's assets, not a lint failure."""
        b = self.lint("assets/radio-bundle/manifest", "assets/radio-bundle/sisdk/lib/libc.so.6")
        self.assertEqual(b["lint"], [])
        self.assertEqual(b["owners"]["pane:radio"]["assets"], 20)
        self.assertEqual(self.lint("assets/radio-bundle-extra/x")["lint"][0][:28], "assets/radio-bundle-extra/x:")


class Budgets(unittest.TestCase):
    def report(self, budgets, base_budgets=None, xul=500_000):
        d = tempfile.mkdtemp()
        head = make_apk(os.path.join(d, "head.apk"), xul=xul)
        base = make_apk(os.path.join(d, "base.apk"))
        return A.build_report("aqara-s1-plus", {"armeabi-v7a": head}, {"armeabi-v7a": base}, PARTS, budgets, base_budgets,
                              classes_of=lambda p: A.parse_classes(CLASSES))

    def test_within_budget(self):
        rep = self.report({"size": {"apk_max_bytes": {"armeabi-v7a": 10_000_000}}, "panes": {"assist": {"size_max_bytes": 100_000}}})
        self.assertTrue(rep["budget"]["ok"], rep["budget"])

    def test_over_the_apk_budget_fails(self):
        rep = self.report({"size": {"apk_max_bytes": {"armeabi-v7a": 600_000}}}, xul=700_000)
        self.assertFalse(rep["budget"]["ok"])
        self.assertIn("armeabi-v7a: the APK is", rep["budget"]["fails"][0])

    def test_over_a_pane_budget_fails(self):
        rep = self.report({"size": {}, "panes": {"assist": {"size_max_bytes": 10_000}}})
        self.assertFalse(rep["budget"]["ok"])
        self.assertIn("assist is", rep["budget"]["fails"][0])

    def test_raising_the_budget_in_the_same_pr_passes_and_leads_the_comment(self):
        base = {"size": {"apk_max_bytes": {"armeabi-v7a": 600_000}}}
        head = {"size": {"apk_max_bytes": {"armeabi-v7a": 900_000}}}
        rep = self.report(head, base, xul=700_000)
        self.assertTrue(rep["budget"]["ok"])
        self.assertTrue(rep["budget"]["raised"])
        self.assertEqual(rep["budget"]["changes"], ["armeabi-v7a APK: this PR raises the budget from 0.60 MB to 0.90 MB"])
        lines = A.markdown(rep).splitlines()
        self.assertEqual(lines[3], "**This PR raises the size budget.**")     # first, before "Within budget."
        self.assertLess(lines.index("**This PR raises the size budget.**"), lines.index("Within budget."))

    def test_a_budget_the_base_didnt_have_is_said(self):
        rep = self.report({"size": {"apk_max_bytes": {"armeabi-v7a": 900_000}}, "panes": {"radio": {"size_max_bytes": 5_000}}},
                          {"size": {}})
        self.assertIn("armeabi-v7a APK: this PR adds a budget of 0.90 MB (the base had none)", rep["budget"]["changes"])
        self.assertIn("pane radio: this PR adds a budget of 0.01 MB (the base had none)", rep["budget"]["changes"])
        self.assertTrue(rep["budget"]["raised"])

    def test_a_lowered_budget_is_a_change_not_a_raise(self):
        rep = self.report({"size": {"apk_max_bytes": {"armeabi-v7a": 900_000}}}, {"size": {"apk_max_bytes": {"armeabi-v7a": 950_000}}})
        self.assertFalse(rep["budget"]["raised"])
        self.assertIn("**This PR changes the size budget.**", A.markdown(rep))
        # Without the base's budgets file, nothing can be called a change.
        self.assertEqual(self.report({"size": {"apk_max_bytes": {"armeabi-v7a": 900_000}}})["budget"]["changes"], [])

    def test_a_removed_budget_is_a_change(self):
        """CodeRabbit on 226cdbd: a budget the base had and this PR drops was silent ("Within budget.")."""
        base = {"size": {"apk_max_bytes": {"armeabi-v7a": 900_000}},
                "panes": {"radio": {"size_max_bytes": 5_000}, "gone": {"size_max_bytes": 7_000}}}
        rep = self.report({"size": {}, "panes": {"radio": {}}}, base)
        self.assertEqual(rep["budget"]["changes"], [
            "armeabi-v7a APK: this PR removes the budget of 0.90 MB",
            "pane gone: this PR removes the budget of 0.01 MB",
            "pane radio: this PR removes the budget of 0.01 MB"])
        self.assertFalse(rep["budget"]["raised"])
        md = A.markdown(rep)
        self.assertIn("**This PR changes the size budget.**", md)
        self.assertIn("- pane radio: this PR removes the budget of 0.01 MB", md)

    def test_a_missing_base_is_said(self):
        d = tempfile.mkdtemp()
        head = make_apk(os.path.join(d, "head.apk"))
        rep = A.build_report("aqara-s1-plus", {"armeabi-v7a": head}, {}, PARTS, {"size": {}}, None,
                             classes_of=lambda p: A.parse_classes(CLASSES), base_missing={"armeabi-v7a": "base-build-failed"})
        self.assertEqual(rep["abis"]["armeabi-v7a"]["base_missing"], "base-build-failed")
        self.assertIn("- armeabi-v7a: no change column: the base didn't build.", A.markdown(rep))
        rep["abis"]["armeabi-v7a"]["base_missing"] = "anything [else](x)"
        with self.assertRaises(ValueError):
            A.markdown(rep)

    def test_an_apk_of_another_abi_is_refused(self):
        d = tempfile.mkdtemp()
        apk = make_apk(os.path.join(d, "a.apk"))                 # lib/armeabi-v7a/
        with self.assertRaises(SystemExit) as e:
            A.build_report("aqara-s1-plus", {"arm64-v8a": apk}, {}, PARTS, {"size": {}}, None,
                           classes_of=lambda p: A.parse_classes(CLASSES))
        self.assertEqual(e.exception.code, 2)

    def test_the_change_from_the_base(self):
        rep = self.report({"size": {}}, xul=510_000)
        a = rep["abis"]["armeabi-v7a"]
        self.assertEqual(a["rows"]["engine"]["total"] - a["base_rows"]["engine"]["total"], 10_000)
        self.assertIn("+10.0 kB", A.markdown(rep))

    def test_the_s1s_budgets(self):
        """The frame's own file has a size budget per ABI and per switchable pane."""
        s1 = A.read_json(os.path.join(ROOT, "frames", "aqara-s1-plus", "budgets.json"))
        self.assertEqual(set(s1["size"]["apk_max_bytes"]), {"armeabi-v7a", "arm64-v8a"})
        for pid in PARTS["panes"]:
            self.assertIsInstance(s1["panes"][pid]["size_max_bytes"], int, pid)

    def test_the_generic_apps_budgets(self):
        """Glassworks for generic Android (#23): a size budget per ABI its frame runs and per pane it
        ships, and a RAM budget per pane Clarity switches."""
        g = A.read_json(os.path.join(ROOT, "frames", "generic-android", "budgets.json"))
        frame = A.read_json(os.path.join(ROOT, "frames", "generic-android", "design", "frame.json"))
        self.assertEqual(set(g["size"]["apk_max_bytes"]), set(frame["abis"]))
        with open(os.path.join(ROOT, "frames", "generic-android", "android", "build.gradle.kts")) as f:
            ships = set(re.findall(r'project\(":pane-([a-z0-9-]+)"\)', f.read()))
        self.assertEqual(set(g["panes"]), ships)
        for pid, pane in g["panes"].items():
            self.assertIn(pid, PARTS["panes"])
            self.assertIsInstance(pane["size_max_bytes"], int, pid)
            self.assertEqual("ram_pss_max_kb" in pane, "switch" in pane, pid)
        self.assertNotIn("_todo_ram", g)

    def test_a_check_that_builds_no_base_says_so(self):
        d = tempfile.mkdtemp()
        rep = A.build_report("generic-android", {"armeabi-v7a": make_apk(os.path.join(d, "head.apk"))}, {}, PARTS, {"size": {}}, None,
                             classes_of=lambda p: A.parse_classes(CLASSES), base_missing={"armeabi-v7a": "not-built"})
        self.assertIn("this check builds no base", A.markdown(rep))


class Comment(unittest.TestCase):
    """The PR comment is rendered by a workflow with write access, from a report another workflow
    built from the PR's code: only numbers and known words may reach it."""

    def rep(self):
        d = tempfile.mkdtemp()
        apk = make_apk(os.path.join(d, "a.apk"))
        return A.build_report("aqara-s1-plus", {"armeabi-v7a": apk}, {}, PARTS, {"size": {}}, None,
                              classes_of=lambda p: A.parse_classes(CLASSES))

    def test_it_starts_with_the_marker_it_is_found_by(self):
        md = A.markdown(self.rep())
        self.assertTrue(md.startswith(A.MARKER))
        self.assertIn("| Pane `assist` |", md)

    def test_a_report_with_anything_else_in_its_names_is_refused(self):
        for bad in (lambda r: r["abis"]["armeabi-v7a"]["rows"].update({"pane:x](http://evil)": r["abis"]["armeabi-v7a"]["rows"]["core"]}),
                    lambda r: r.update(frame="s1<script>"),
                    lambda r: r["abis"].update({"evil": r["abis"]["armeabi-v7a"]}),
                    lambda r: r["abis"]["armeabi-v7a"].update(bytes="10 MB"),
                    lambda r: r.update(schema="other")):
            rep = self.rep()
            bad(rep)
            with self.assertRaises(ValueError):
                A.markdown(rep)

    def test_findings_are_plain_text(self):
        rep = self.rep()
        rep["budget"]["fails"] = ["x <img src=x onerror=alert(1)> [link](http://evil) @someone", "see https://evil.example/x now"]
        md = A.markdown(rep)
        body = md.split("How this is measured")[0]
        self.assertNotIn("](", body)
        self.assertNotIn("<img", body)
        self.assertNotIn("@someone", md)
        self.assertNotIn("://", body)
        self.assertIn("see (link removed) now", body)

    def test_the_comment_has_limits(self):
        rep = self.rep()
        rep["budget"]["notes"] = [f"note {i}" for i in range(50)]
        md = A.markdown(rep)
        self.assertIn("- note 19", md)
        self.assertNotIn("- note 20", md)
        self.assertIn("- and 30 more (the job summary has them all)", md)
        rows = rep["abis"]["armeabi-v7a"]["rows"]
        many = copy.deepcopy(rep)
        many["abis"]["armeabi-v7a"]["rows"] = {f"pane:p{i}": rows["core"] for i in range(A.MAX_ROWS + 1)}
        for bad in (many,
                    dict(rep, abis={"armeabi-v7a": dict(rep["abis"]["armeabi-v7a"], rows=[1, 2])}),
                    dict(rep, abis={"armeabi-v7a": dict(rep["abis"]["armeabi-v7a"], rows={"core": "big"})}),
                    dict(rep, abis={"armeabi-v7a": dict(rep["abis"]["armeabi-v7a"], base_rows=[])}),
                    dict(rep, abis={"armeabi-v7a": "x"}),
                    dict(rep, budget=["x"])):
            with self.assertRaises(ValueError):
                A.markdown(bad)

    def test_one_comment_for_two_frames(self):
        """The S1's report and generic Android's (#68): one marker, a section each in the order given,
        each with its own change column, and the footer once."""
        d = tempfile.mkdtemp()
        generic = A.build_report("generic-android", {"arm64-v8a": make_apk(os.path.join(d, "head.apk"), arm64=True)},
                                 {"arm64-v8a": make_apk(os.path.join(d, "base.apk"), arm64=True, dex=10_000)}, PARTS,
                                 {"size": {}}, None, classes_of=lambda p: A.parse_classes(CLASSES))
        md = A.markdown(self.rep(), generic)
        self.assertEqual(md.count(A.MARKER), 1)
        self.assertTrue(md.startswith(A.MARKER))
        self.assertLess(md.index("### APK size: `aqara-s1-plus`"), md.index("### APK size: `generic-android`"))
        self.assertIn("#### arm64-v8a: ", md.split("`generic-android`")[1])
        self.assertIn("+10.0 kB against the base branch", md.split("`generic-android`")[1])
        self.assertEqual(md.count("How this is measured"), 1)
        for bad in ((), (self.rep(), self.rep()), (self.rep(), {"schema": "x"}), tuple(self.rep() for _ in range(A.MAX_REPORTS + 1))):
            with self.assertRaises(ValueError):
                A.markdown(*bad)

    def test_render_from_the_command_line(self):
        d = tempfile.mkdtemp()
        p = os.path.join(d, "size.json")
        with open(p, "w") as f:
            json.dump(self.rep(), f)
        out = subprocess.run([sys.executable, TOOL, "render", p], capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertTrue(out.stdout.startswith(A.MARKER))
        g = os.path.join(d, "generic.json")
        with open(g, "w") as f:
            json.dump(dict(self.rep(), frame="generic-android"), f)
        out = subprocess.run([sys.executable, TOOL, "render", p, g], capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("### APK size: `generic-android`", out.stdout)
        with open(p, "w") as f:
            f.write('{"schema": "x"}')
        self.assertNotEqual(subprocess.run([sys.executable, TOOL, "render", p], capture_output=True).returncode, 0)
        self.assertNotEqual(subprocess.run([sys.executable, TOOL, "render", g, p], capture_output=True).returncode, 0)


class Parts(unittest.TestCase):
    def test_every_pane_in_the_code_has_its_parts(self):
        # Ids written as literals and as constants (CameraPane.ID): a pane can't escape its size row (#117).
        ids = pane_ids_in(main_sources(ROOT))
        self.assertIn("camera", ids)
        self.assertEqual(set(), ids - set(PARTS["panes"]), "panes in the code without parts in parts.json")
        self.assertEqual(set(PARTS["panes"]), ids)

    def test_ids_are_safe_in_the_comment(self):
        for pid in list(PARTS["panes"]) + list(PARTS["libraries"]):
            self.assertRegex(pid, A.ID_RE)
        for lib in PARTS["libraries"].values():
            self.assertTrue(set(lib["used_by"]) <= set(PARTS["panes"]) | {"core", "engine"})


class Cli(unittest.TestCase):
    def test_exit_status_and_files(self):
        d = tempfile.mkdtemp()
        apk = make_apk(os.path.join(d, "a.apk"))
        fake = os.path.join(d, "apkanalyzer")
        with open(fake, "w") as f:
            f.write("#!/bin/sh\ncat <<'EOF'\n" + CLASSES + "EOF\n")
        os.chmod(fake, 0o755)
        ok = {"size": {"apk_max_bytes": {"armeabi-v7a": 10_000_000}}, "panes": {}}
        over = copy.deepcopy(ok)
        over["size"]["apk_max_bytes"]["armeabi-v7a"] = 1_000
        for budgets, want in ((ok, 0), (over, 1)):
            b = os.path.join(d, "b.json")
            with open(b, "w") as f:
                json.dump(budgets, f)
            p = subprocess.run([sys.executable, TOOL, "report", "--frame", "aqara-s1-plus", "--apk", f"armeabi-v7a={apk}",
                                "--budgets", b, "--apkanalyzer", fake, "--json", os.path.join(d, "s.json"),
                                "--md", os.path.join(d, "s.md")], capture_output=True, text=True)
            self.assertEqual(p.returncode, want, p.stderr)
            self.assertTrue(os.path.exists(os.path.join(d, "s.json")))      # written either way
        # Bad input is 2, not 1 (1 means over budget).
        for bad in (["--apk", "mips=x.apk"], ["--apk", f"armeabi-v7a={apk}", "--base-missing", "armeabi-v7a=maybe"],
                    ["--apk", f"arm64-v8a={apk}", "--apkanalyzer", fake, "--budgets", b]):
            p = subprocess.run([sys.executable, TOOL, "report", "--frame", "aqara-s1-plus", *bad,
                                "--json", os.path.join(d, "x"), "--md", os.path.join(d, "y")], capture_output=True, text=True)
            self.assertEqual(p.returncode, 2, (bad, p.stderr))
        self.assertTrue(os.access(TOOL, os.X_OK))

    def test_generic_androids_size_reaches_the_comment(self):
        """#68: size-generic builds its base for the change column and uploads its report under the
        name size-comment.yml downloads and renders, after the S1's."""
        wf = os.path.join(ROOT, ".github", "workflows")
        with open(os.path.join(wf, "pr.yml")) as f:
            pr = f.read()
        with open(os.path.join(wf, "size-comment.yml")) as f:
            comment = f.read()
        job = pr.split("\n  size-generic:\n", 1)[1].split("\n  branding:\n", 1)[0]
        self.assertIn("name: Release APKs of the base", job)
        self.assertIn("--base-apk $a=", job)
        self.assertNotIn("=not-built", job)
        upload = job.split("name: apk-size-generic", 1)[1].split("\n\n", 1)[0]
        self.assertIn("generic/size.json", upload)
        self.assertIn("-n apk-size-generic -D \"$RUNNER_TEMP/in/generic\"", comment)
        self.assertIn('for f in "$in/size.json" "$in/generic/size.json"', comment)
        self.assertIn('apksize.py render "${reports[@]}"', comment)

    def test_the_size_job_bundles_what_the_release_does(self):
        """tools/size/radio-bundle.sh and release.yml's "Sign the radio bundle" step bundle the same files."""
        def bundled(text):
            return sorted(re.findall(r"--(?:file|dir) \"?([a-z0-9_.-]+)=", text))
        with open(os.path.join(ROOT, ".github", "workflows", "release.yml")) as f:
            release = f.read()
        with open(os.path.join(os.path.dirname(HERE), "radio-bundle.sh")) as f:
            mine = f.read()
        def bundled_any(text):   # radio-bundle.sh builds its optional parts' arguments in arrays first
            return sorted(set(re.findall(r"--(?:file|dir) \"?([a-z0-9_.-]+)=", text)))
        self.assertEqual(bundled_any(mine), bundled_any(release))
        self.assertEqual(bundled_any(mine), ["bootwriter", "hotspot-name.apk", "ot-ctl", "otbr-agent", "s1kiosk_radio.sh", "sisdk"])
        self.assertIn("bootwriter-files.sh", release)


if __name__ == "__main__":
    unittest.main()
