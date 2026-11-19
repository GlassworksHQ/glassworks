"""run.sh and perfrun.py end to end against test/fake_adb.py (no device)."""
import contextlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
RUN = os.path.join(os.path.dirname(HERE), "run.sh")
STUB_FRONTEND = os.path.join(HERE, "stub_frontend")       # the runner tests never fetch HA's frontend
STUB_CAMERA = tempfile.mkdtemp(prefix="clarity-camera-")  # nor make camera segments (no ffmpeg needed)
os.environ.setdefault("GW_PERF_CAMERA", STUB_CAMERA)
os.environ.setdefault("GW_PERF_FRONTEND", STUB_FRONTEND)
# The runs against the fake panel go at this speed (clock.py; #268): 0.2 waits a fifth of the real
# seconds. GW_PERF_TIME_SCALE=1 runs them in real time. Set per run, never in this process's environ,
# which the other test modules share.
TIME_SCALE = os.environ.get("GW_PERF_TIME_SCALE", "0.2")
# The ports of run.sh's fake (each run takes one and the one after it, the relay's) come from one
# block: PORT_BASE + 76 to + 100. parallel.py gives each of its workers a block of its own, so runs
# that go at the same time never share a port. One process's tests run one at a time, as before.
PORT_BASE = int(os.environ.get("GW_PERF_TEST_PORT_BASE", "18000"))


def port(n):
    return str(PORT_BASE + n)


sys.path.insert(0, os.path.dirname(HERE))
import fakeha  # noqa: E402
import perfrun  # noqa: E402
import report  # noqa: E402
import perflib as P  # noqa: E402
import summary  # noqa: E402
import grade  # noqa: E402
import threading  # noqa: E402


class FakeHA:
    """A fake Home Assistant on its own port, for perfrun runs without run.sh: perfrun sends it the
    views to open, and fake_adb takes them from it, as the harness does."""

    def __enter__(self):
        self.srv = fakeha.serve(0)
        self.url = f"http://127.0.0.1:{self.srv.server_address[1]}"
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        return self

    def __exit__(self, *exc):
        self.srv.shutdown()
        self.srv.server_close()


HA = None          # one fake Home Assistant for the perfrun runs that don't bring their own


def setUpModule():
    global HA
    HA = FakeHA().__enter__()


def tearDownModule():
    HA.__exit__()


class Runner(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        state = os.path.join(cls.tmp, "state")
        os.makedirs(state)
        adb = os.path.join(cls.tmp, "adb")
        with open(adb, "w") as f:
            f.write(f'#!/bin/sh\nexec "{sys.executable}" "{os.path.join(HERE, "fake_adb.py")}" "$@"\n')
        os.chmod(adb, 0o755)
        env = dict(os.environ, ADB=adb, FAKE_ADB_STATE=state, GW_PERF_IDLE_SETTLE="0", GW_PERF_IDLE_WINDOW="2",
                   GW_PERF_COLD_SETTLE="0", GW_PERF_RUN_GAP="0", GW_PERF_BETWEEN_QUIET="0.5", GW_PERF_FRONTEND=STUB_FRONTEND,
                   GW_PERF_SWITCH_SETTLE="0", FAKE_HA_URL=f"http://127.0.0.1:{port(89)}", GW_PERF_CAMERA=STUB_CAMERA,
                   GW_PERF_FAKE_PYTHON=sys.executable, GW_PERF_TIME_SCALE=TIME_SCALE)
        env.pop("ANDROID_SERIAL", None)
        cls.state = state
        cls.out = os.path.join(cls.tmp, "out")
        cls.proc = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", cls.out, "--port", port(89), "--taps", "2"],
                                  env=env, capture_output=True, text=True, timeout=300)
        with open(os.path.join(state, "commands.log")) as f:
            cls.commands = f.read().splitlines()
        cls.rep = None
        if cls.proc.returncode == 0:
            with open(os.path.join(cls.out, "report.json")) as f:
                cls.rep = json.load(f)

    def test_run_succeeds(self):
        self.assertEqual(self.proc.returncode, 0, self.proc.stderr[-3000:])

    def test_report_has_every_part(self):
        r = self.rep
        self.assertEqual(r["schema"], "glassworks.perf/4")
        self.assertEqual(r["frame"]["device"]["model"], "Test Panel")
        self.assertEqual(r["frame"]["app"]["geckoview"], "159.0")
        g = r["grade"]
        self.assertEqual(g["grade"], "A+")                          # the fake panel holds 60 fps everywhere
        self.assertFalse(g["provisional"], g["missing"])
        self.assertTrue(g["headline"].startswith("test-frame: Clarity A+, premium."),
                        g["headline"])
        self.assertEqual(g["viewport"]["state"], "ok")
        ref = r["graded"]["reference"]
        self.assertEqual(ref["errors"], [])
        self.assertEqual(set(ref["tiers"]), {"light", "typical-nocam", "typical", "heavy"})
        self.assertEqual(ref["engine_uris"], ["about:blank"])
        self.assertEqual(ref["own_page_states"], ["reference"])        # the kiosk came back to the fake HA
        self.assertGreater(ref["start_ms"]["median"], 0)                  # restart: process start to usable
        self.assertEqual(ref["restart_to"], "usable")
        self.assertEqual(ref["engine_ready_ms"]["n"], 1)                  # kept as detail
        self.assertEqual(ref["orders"], [["light", "typical-nocam", "typical", "heavy"]])
        # After the restart page and each page, the HLS copies included (#202).
        self.assertEqual(ref["per_run"][0]["between"], ["still"] * 6)
        for t in ("light", "typical-nocam", "typical", "heavy"):
            s = ref["tiers"][t]
            self.assertIsNotNone(s["load_ms"], t)
            self.assertIsNotNone(s["inp_ms"], t)
            self.assertEqual(s["scroll_sources"], ["surfaceflinger"], t)
            self.assertEqual(s["sf_valid"], [True], t)
            self.assertEqual(s["scrolled_px"]["median"], 1400, t)   # two swipes of 700 px
            self.assertEqual(s["updates_level"], "premium", t)           # changes on screen within a frame or two
            self.assertEqual(s["swipes_during_updates"], [2], t)
        self.assertEqual(g["start"]["value"], ref["start_ms"]["median"])
        self.assertEqual(r["real_use"]["android_ttid_ms"]["median"], 1714)
        self.assertEqual(r["real_use"]["settle_how"], ["quiet-gap"])
        self.assertEqual(r["real_use"]["memory"]["idle"]["app_pss_kb"]["median"], 570000)
        self.assertEqual(r["real_use"]["idle"]["samples"], 1)        # the reference dashboard's samples aren't real use
        self.assertEqual({p["id"] for p in r["real_use"]["panes"]}, {"radio"})

    def test_the_camera_videos_path_was_asked_and_recorded(self):
        """Protocol 7 (#202, option B): run.sh asked the panel (UDP, then TCP; this fake panel reaches
        nothing), fell back to adb, and the report says so beside the cameras."""
        with open(os.path.join(self.state, "lan_checks.json")) as f:
            self.assertEqual(json.load(f), ["udp", "tcp"])
        t = self.rep["webrtc_transport"]
        self.assertEqual((t["transport"], t["chosen_by"]), ("adb", "auto"))
        self.assertTrue(t["limited"])
        with open(os.path.join(self.out, "report.md")) as f:
            self.assertIn("Camera video through the test's adb link (USB)", f.read())

    def test_the_hls_copies_come_after_the_graded_views(self):
        """#202: run.sh opens the camera pages' HLS copies after each run's graded views, without
        swipes; they're detail beside the graded WebRTC cameras and never in the grade."""
        with open(os.path.join(self.state, "opened.json")) as f:
            self.assertEqual(json.load(f), ["light", "typical-nocam", "typical", "heavy", "typical-hls", "heavy-hls"])
        with open(os.path.join(self.out, "refstart-1.json")) as f:
            c = json.load(f)
        self.assertEqual(sorted(c["fallback"]), ["heavy-hls", "typical-hls"])
        self.assertEqual(c["fallback"]["heavy-hls"]["swipes"], [])
        # Clarity v3 (#211): the more-info dialogs on the graded views only; the copies are asked for none.
        self.assertNotIn("dialogs", c["fallback"]["heavy-hls"])
        self.assertEqual([len(c["tiers"][t]["dialogs"]) for t in c["order"]], [3, 3, 3, 3])
        with open(os.path.join(self.state, "views.json")) as f:
            asked = {v["view"]: v.get("dialogs") for v in json.load(f)}
        self.assertEqual(asked, {"light": None, "typical-nocam": None, "typical": None, "heavy": None,
                                 "typical-hls": "0", "heavy-hls": "0"})
        # Each gesture's time on this computer's clock and how long `input` took (#202, smoke 5).
        sent = c["gestures_sent"]
        self.assertEqual({g[2] for g in sent}, {"tap", "swipe"})
        self.assertTrue(all(isinstance(g[1], float) and g[3] >= 0 for g in sent))
        self.assertEqual(sum(1 for g in sent if g[0] == "heavy" and g[2] == "tap"), 2)
        # On fakeha.jsonl's clock: each gesture comes after its view's open there, and before the next.
        with open(os.path.join(self.out, "fakeha.jsonl")) as f:
            opens = [(e["t"], e["open"]["view"]) for e in map(json.loads, f) if (e.get("open") or {}).get("view")]
        for g in sent:
            i = max(k for k, (t, v) in enumerate(opens) if v == g[0])
            self.assertGreater(g[1], opens[i][0], g)
            self.assertLess(g[1], opens[i + 1][0] if i + 1 < len(opens) else float("inf"), g)
        self.assertEqual(c["order"], ["light", "typical-nocam", "typical", "heavy"])
        ref = self.rep["graded"]["reference"]
        for t in ("typical", "heavy"):
            self.assertEqual({r["path"] for r in ref["tiers"][t]["camera_runs"]}, {"web_rtc"}, t)
            fb = ref["tiers"][t]["camera_fallback"]
            self.assertEqual((fb["path"], [r["view"] for r in fb["runs"]]), ("hls", [f"{t}-hls"]), t)
        # The decoders: over HLS the hardware one per stream; over WebRTC none logged (software).
        self.assertEqual(ref["tiers"]["heavy"]["camera_fallback"]["runs"][0]["media"],
                         {"codecs": ["OMX.allwinner.video.decoder.avc"] * 4, "flushes": 2, "eos": 1})
        self.assertEqual(ref["tiers"]["typical"]["camera_fallback"]["runs"][0]["media"]["codecs"],
                         ["OMX.allwinner.video.decoder.avc"])
        self.assertIsNone(ref["tiers"]["heavy"]["camera_runs"][0]["media"])

    def test_ram_on_the_reference_dashboards(self):
        """#68: RAM per process at idle on the typical reference dashboard after the graded run, and the
        peak from the samples between the graded pages (one after each)."""
        ram = self.rep["ram"]
        self.assertEqual(ram["reference_idle"]["samples"], 1)
        self.assertEqual(ram["reference_idle"]["app_pss_kb"]["median"], 570000)
        self.assertEqual(set(ram["reference_idle"]["by_role_pss_kb"]), {"main", "gpu", "tab"})
        self.assertEqual(ram["graded_peak"]["samples"], 4)
        self.assertEqual(ram["graded_peak"]["app_pss_kb"], 570000)
        self.assertEqual(ram["graded_peak"]["app_rss_hwm_kb"], 900000)      # VmHWM of the three processes
        with open(os.path.join(self.out, "refstart-1.json")) as f:
            self.assertEqual([m["after"] for m in json.load(f)["memory"]], ["light", "typical-nocam", "typical", "heavy"])
        self.assertTrue(ram["budget"]["ok"])
        self.assertEqual((ram["panes"], ram["panes_not_switched"]), ([], []))       # test-frame has no panes
        with open(os.path.join(self.out, "report.md")) as f:
            self.assertIn("## RAM on the reference dashboards", f.read())
        with open(os.path.join(self.tmp, "state", "opened_still.json")) as f:
            self.assertEqual(json.load(f), [f"http://127.0.0.1:{port(89)}/clarity-ref/typical"])

    def test_it_changes_no_setting_and_shows_the_reference_dashboard_for_its_hold(self):
        """Protocol 4: the kiosk shows the fake HA's start view as its test dashboard for the graded part
        (with the hold, checked in its log), and goes back to its own after (with -W, checked). Its saved
        dashboard is never touched: since #496 a new HA address asks a panel to sign in again."""
        shell = [c[len("shell "):] for c in self.commands if c.startswith("shell ")]
        self.assertEqual([c for c in shell if "--es url" in c], [])
        dashes = [re.search(r"--es test_dashboard '([^']*)'", c).group(1) for c in shell if "--es test_dashboard" in c]
        self.assertEqual(dashes, [f"http://127.0.0.1:{port(89)}/clarity-ref/start", ""])
        self.assertIn("am start -W -n sh.edm.s1kiosk/.MainActivity --ez test_hold true --ei test_hold_s 1800 --es test_dashboard "
                      f"'http://127.0.0.1:{port(89)}/clarity-ref/start'", shell)
        self.assertIn("am start -W -n sh.edm.s1kiosk/.MainActivity --es test_dashboard ''", shell)
        self.assertFalse(os.path.exists(os.path.join(self.state, "saved_url.json")))
        i_set = next(i for i, c in enumerate(self.commands) if "--es test_dashboard" in c)
        i_back = max(i for i, c in enumerate(self.commands) if "--es test_dashboard" in c)
        graded = [i for i, c in enumerate(self.commands) if c.startswith("view ")]
        self.assertLess(i_set, graded[0])
        self.assertGreater(i_back, graded[-1])

    def test_it_changes_no_other_setting(self):
        shell = [c[len("shell "):] for c in self.commands if c.startswith("shell ")]
        inputs = sorted({c for c in shell if c.startswith("input ")})
        # Taps on the toggle and swipes where the page asked, with its coordinates: nothing else.
        self.assertEqual(inputs, ["input keyevent KEYCODE_WAKEUP", "input swipe 720 612 720 108 300", "input tap 720 360"])
        self.assertEqual(sum(1 for c in shell if c == "input tap 720 360"), 12)    # 2 on each page and HLS copy (#202)
        self.assertEqual(sum(1 for c in shell if c.startswith("input swipe ")), 8)            # 2 on each page; none on a copy
        forbidden = re.compile(r"(^|[;&|]\s*)(settings|svc|cmd|setprop|am broadcast|pm (?!path\b))|--ez (?!test_hold )")   # no pane is switched without --pane-ram
        self.assertEqual([c for c in shell if forbidden.search(c)], [])
        extras = {m for c in shell for m in re.findall(r"--e\w \w+", c)}
        self.assertEqual(extras, {"--es open", "--es test_dashboard", "--ez test_hold", "--ei test_hold_s"})
        opens = [re.search(r"--es open '([^']+)'", c).group(1) for c in shell if "--es open" in c]
        # The SurfaceFlinger check's page (#643), the animation test page and the idle sample's page load; the
        # graded views don't: they open inside the loaded frontend, by navigation, on the runner's command to the fake HA.
        self.assertEqual(len(opens), 3)
        self.assertEqual(opens[0], f"http://127.0.0.1:{port(89)}/clarity/page/sfcheck.html")
        self.assertTrue(opens[1].startswith(f"http://127.0.0.1:{port(89)}/clarity/page/index.html?"), opens[1])
        self.assertEqual(opens[2], f"http://127.0.0.1:{port(89)}/clarity-ref/typical")       # the idle sample's page, no nonce
        views = [c.split()[1:4:2] for c in self.commands if c.startswith("view ")]
        self.assertEqual(views, [["light", "taps=2"], ["typical-nocam", "taps=2"], ["typical", "taps=2"], ["heavy", "taps=2"],
                                 ["typical-hls", "taps=2"], ["heavy-hls", "taps=2"]])

    def test_no_gesture_without_the_current_pages_request(self):
        """The fake rejects any tap or swipe that no current *-ready line asked for."""
        with open(os.path.join(self.tmp, "state", "unasked.json")) if os.path.exists(
                os.path.join(self.tmp, "state", "unasked.json")) else open(os.devnull) as f:
            self.assertEqual(json.loads(f.read() or "[]"), [])

    def test_swipes_come_only_after_the_pages_own_request(self):
        """Each swipe follows a swipe-ready from the page being measured: the commands log has no swipe
        before the first reference page opened."""
        first_open = next(i for i, c in enumerate(self.commands) if c.startswith("view "))
        first_swipe = next(i for i, c in enumerate(self.commands) if c.startswith("shell input swipe"))
        self.assertGreater(first_swipe, first_open)

    def test_the_reference_dashboards_open_only_once_the_session_is_up(self):
        """The first view opens only after the restart (the restart page said it was usable)."""
        i_blank = next(i for i, c in enumerate(self.commands) if c.startswith("view "))
        stops = [i for i, c in enumerate(self.commands) if c.startswith("shell am force-stop")]
        self.assertGreater(i_blank, stops[0])

    def test_launch_without_a_system_relaunch_uses_am_start(self):
        self.assertEqual(self.rep["real_use"]["launch"], ["am-start"])

    def test_the_dashboard_comes_back_last(self):
        starts = [c for c in self.commands if c.startswith("shell am start") and "test_hold" not in c]
        self.assertTrue(starts[-1].startswith("shell am start -W"), starts[-3:])

    def test_the_test_hold_comes_first_and_ends_last(self):
        """#240: the kiosk's test hold keeps the screen on, set before anything else reaches the kiosk
        and ended by the exit trap. No wake intent."""
        am = [c for c in self.commands if c.startswith("shell am ")]
        hold = "--ez test_hold true --ei test_hold_s 1800"
        self.assertTrue(am[0].startswith("shell am start -W") and am[0].endswith(hold), am[:2])
        self.assertTrue(am[-1].endswith("--ez test_hold false"), am[-2:])
        self.assertEqual([c for c in self.commands if "--es screen" in c], [])
        # Again after each restart (before each part and view): the restart ended the last one.
        stops = [i for i, c in enumerate(self.commands) if c.startswith("shell am force-stop")]
        for i in stops:
            self.assertTrue(any(c.endswith(hold) for c in self.commands[i + 1:]), self.commands[i])


def fake_env(tmp, **extra):
    state = os.path.join(tmp, "state")
    os.makedirs(state, exist_ok=True)
    adb = os.path.join(tmp, "adb")
    with open(adb, "w") as f:
        f.write(f'#!/bin/sh\nexec "{sys.executable}" "{os.path.join(HERE, "fake_adb.py")}" "$@"\n')
    os.chmod(adb, 0o755)
    extra.setdefault("FAKE_HA_URL", HA.url if HA else "")
    env = dict(os.environ, ADB=adb, FAKE_ADB_STATE=state, GW_PERF_IDLE_SETTLE="0", GW_PERF_IDLE_WINDOW="1",
               GW_PERF_FRONTEND=STUB_FRONTEND, GW_PERF_CAMERA=STUB_CAMERA, GW_PERF_SWITCH_SETTLE="0", GW_PERF_PANES_SAVE_S="0",
               GW_PERF_FAKE_PYTHON=sys.executable, GW_PERF_TIME_SCALE=TIME_SCALE)
    env.update(extra)
    env.pop("ANDROID_SERIAL", None)
    return env, state


class RealTime(unittest.TestCase):
    """#268: the time scale is for these tests only. A real run keeps real seconds, and a scaled
    clock is refused on any device that isn't the fake, before anything else on it is touched."""
    PERF = os.path.dirname(HERE)

    def env(self, scale):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_REAL_DEVICE="1")
        env.pop("GW_PERF_TIME_SCALE")
        if scale is not None:
            env["GW_PERF_TIME_SCALE"] = scale
        return tmp, env, os.path.join(state, "commands.log")

    def test_without_the_setting_the_clock_is_real(self):
        tmp, env, log = self.env(None)
        code = "import clock, time; print(clock.SCALE, clock.real(90), abs(clock.now() - time.time()) < 0.5)"
        p = subprocess.run([sys.executable, "-c", code], cwd=self.PERF, env=env, capture_output=True, text=True, timeout=30)
        self.assertEqual(p.stdout.split(), ["1.0", "90.0", "True"], p.stderr)
        p = subprocess.run([sys.executable, os.path.join(self.PERF, "perfrun.py"), "device", "--out", tmp],
                           env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(p.returncode, 0, p.stderr[-800:])
        # run.sh's first check, too: at scale 1 it passes without a word to the device.
        p = subprocess.run([sys.executable, os.path.join(self.PERF, "clock.py"), "--check"], env=env,
                           capture_output=True, text=True, timeout=30)
        self.assertEqual(p.returncode, 0, p.stderr)
        with open(log) as f:
            self.assertNotIn("gw.perf.fake_device", f.read())          # a real run doesn't even ask

    def test_a_scaled_clock_is_refused_on_a_real_device(self):
        runs = {"perfrun": [sys.executable, os.path.join(self.PERF, "perfrun.py"), "refstart", "--url", HA.url, "--out"],
                "run.sh": [RUN, "--frame", "test-frame", "--port", port(79), "--out"],
                "lanprobe": [sys.executable, os.path.join(self.PERF, "lanprobe.py"), "--port", port(78), "--out"]}
        for name, cmd in runs.items():
            with self.subTest(name):
                tmp, env, log = self.env("0.2")
                out = os.path.join(tmp, "out")
                os.makedirs(out)
                p = subprocess.run(cmd + [out], env=env, capture_output=True, text=True, timeout=60)
                self.assertNotEqual(p.returncode, 0)
                self.assertIn("GW_PERF_TIME_SCALE=0.2 is for the off-device tests only", p.stderr)
                # Every adb command it ran, not only the shell ones (#274's verifier): reads, and
                # the question only the fake answers. No exit trap, reverse, intent or setting yet.
                with open(log) as f:
                    ran = f.read().splitlines()
                self.assertIn("shell getprop gw.perf.fake_device", ran)
                self.assertEqual([c for c in ran if c not in
                                  ("get-state", "shell pm path sh.edm.s1kiosk", "shell getprop gw.perf.fake_device")], [])
                self.assertEqual(os.listdir(out), [])

    def test_a_scale_out_of_range_is_refused(self):
        for bad in ("0", "-1", "2", "fast"):
            with self.subTest(bad):
                tmp, env, log = self.env(bad)
                p = subprocess.run([sys.executable, os.path.join(self.PERF, "perfrun.py"), "device", "--out", tmp],
                                   env=env, capture_output=True, text=True, timeout=60)
                self.assertNotEqual(p.returncode, 0)
                self.assertIn("give a number above 0 and at most 1", p.stderr)
                self.assertFalse(os.path.exists(log))


class StopsPartway(unittest.TestCase):
    """The graded part changes the kiosk's dashboard; a run that stops partway still puts it back
    (run.sh's exit trap), so the panel is left as it was found."""

    def test_a_restore_the_kiosk_refuses_fails_the_run_and_says_how_to_fix_it(self):
        """F3: am exits 0 even when the intent wasn't taken. The runner reads its answer, tries again
        on exit, and exits 3 with the command to send, instead of assuming the panel is back (the hold's
        end, at exit, ends the test dashboard too)."""
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_RESTORE_FAILS="1", GW_PERF_COLD_SETTLE="0", GW_PERF_RUN_GAP="0",
                              GW_PERF_BETWEEN_QUIET="0.3", FAKE_HA_URL=f"http://127.0.0.1:{port(97)}")
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", os.path.join(tmp, "out"), "--port", port(97),
                            "--taps", "0", "--skip-synthetic", "--skip-real-use"],
                           env=env, capture_output=True, text=True, timeout=300)
        self.assertEqual(p.returncode, 3, p.stderr[-2000:])
        self.assertIn("COULD NOT end the test dashboard", p.stderr)
        self.assertIn("send it yourself: adb shell am start -n sh.edm.s1kiosk/.MainActivity --es test_dashboard \"''\"", p.stderr)
        with open(os.path.join(state, "restore_refused.json")) as f:
            self.assertEqual(json.load(f), 3)            # after the graded part, then twice on exit
        with open(os.path.join(state, "commands.log")) as f:
            after = f.read().split("--es test_dashboard ''", 1)[1]
        self.assertNotIn("am force-stop", after)          # no restart onto the fake, which is going away

    def test_the_dashboard_goes_back(self):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_IDLE_FAILS="1", GW_PERF_COLD_SETTLE="0", GW_PERF_RUN_GAP="0",
                              GW_PERF_BETWEEN_QUIET="0.3", FAKE_HA_URL=f"http://127.0.0.1:{port(95)}")
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "2", "--out", os.path.join(tmp, "out"), "--port", port(95),
                            "--taps", "0", "--skip-synthetic", "--skip-real-use"],
                           env=env, capture_output=True, text=True, timeout=300)
        self.assertNotEqual(p.returncode, 0)                       # the idle sample after graded run 1 failed
        self.assertTrue(os.path.exists(os.path.join(tmp, "out", "refstart-1.json")))
        self.assertFalse(os.path.exists(os.path.join(tmp, "out", "refstart-2.json")))
        with open(os.path.join(state, "test_dashes_set.json")) as f:
            self.assertEqual(json.load(f), [f"http://127.0.0.1:{port(95)}/clarity-ref/start", ""])
        self.assertIn("back to the kiosk's own dashboard", p.stderr)


class HomeRelaunch(unittest.TestCase):
    """The S1's kiosk is the home app: force-stop and the system starts it again at once."""

    def test_the_system_relaunch_is_the_cold_start(self):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_HOME_RELAUNCH="1")
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "coldstart",
                            "--out", tmp, "--run", "1", "--settle", "3"], env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        with open(os.path.join(tmp, "coldstart-1.json")) as f:
            c = json.load(f)
        self.assertEqual(c["launch"], "relaunched-by-system")
        self.assertEqual(c["ttid_ms"], 1714)
        self.assertIsNotNone(c["page_stop_mono"])
        with open(os.path.join(state, "commands.log")) as f:
            self.assertNotIn("am start -W", f.read())

    def test_graded_run_on_a_home_app_kiosk(self):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_HOME_RELAUNCH="1")
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart",
                            "--out", tmp, "--run", "1", "--settle", "3", "--url", HA.url,
                            "--taps", "0"], env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        with open(os.path.join(tmp, "refstart-1.json")) as f:
            c = json.load(f)
        self.assertEqual(c["launch"], "relaunched-by-system")
        self.assertEqual(c["own_page_state"], "finished-still")
        self.assertEqual(sorted(c["tiers"]), ["heavy", "light", "typical", "typical-nocam"])
        self.assertTrue(all("step" in e and "page" in e for e in c["tiers"].values()))
        self.assertGreater(c["engine_mono"], c["proc_mono"])


def refstart(tmp, run=1, taps=0, **env_extra):
    with FakeHA() as ha:
        # As run.sh leaves it for the graded part: the kiosk points at the reference dashboard's start view.
        env_extra.setdefault("FAKE_SAVED_URL", ha.url + "/clarity-ref/start")
        env, state = fake_env(tmp, GW_PERF_COLD_SETTLE="0", FAKE_HA_URL=ha.url, **env_extra)
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart",
                            "--out", tmp, "--run", str(run), "--settle", "0", "--url", ha.url,
                            "--taps", str(taps), "--timeout", "90", "--between-quiet", "0.3"], env=env, capture_output=True, text=True, timeout=150)
    with open(os.path.join(tmp, f"refstart-{run}.json")) as f:
        return p, json.load(f)


def unasked(tmp):
    p = os.path.join(tmp, "state", "unasked.json")
    if not os.path.exists(p):
        return []
    with open(p) as f:
        return json.load(f)


class CpuClock(unittest.TestCase):
    """#244: the runner samples every core's clock while a view is open (read-only sysfs cats)."""

    def test_each_view_has_its_clock(self):
        tmp = tempfile.mkdtemp()
        # In real time: the margin below is the runner's own latency after the last swipe, which a
        # compressed clock would count five times over.
        p, c = refstart(tmp, GW_PERF_CPU_CLOCK_S="0.3", GW_PERF_TIME_SCALE="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["cpu_max_khz"], {"0": 1608000, "1": 1608000, "2": 1608000, "3": 1608000})
        views = {v for v, _, _ in c["cpu_khz"]}
        self.assertLessEqual(views, {"light", "typical-nocam", "typical", "heavy"})
        self.assertTrue(views, c.get("cpu_khz"))
        self.assertTrue(all(k == {"0": 1200000, "1": 1200000, "2": 1200000, "3": 1200000} for _, _, k in c["cpu_khz"]))
        self.assertEqual(c["cpu_clock_s"], 0.3)
        self.assertNotIn("thread_errors", c)                   # the sampler starts after the run's state exists
        # Only while a view is open, not after its done line: the runner then waits 1.5 s more before it
        # stops (break_after), and the old rule went on sampling the last view through it.
        last = c["order"][-1]
        swipe_end = max(t for v, t, kind, _ in c["gestures_sent"] if v == last)
        self.assertLess(max(t for v, t, _ in c["cpu_khz"] if v == last), swipe_end + 1.2)
        with open(os.path.join(tmp, "state", "..", "state", "commands.log")) as f:
            log = f.read()
        self.assertIn("cpufreq/scaling_cur_freq", log)
        self.assertNotRegex(log, r"echo .*> */sys|setprop|cpufreq/scaling_(max|min|setspeed)")   # reads only


class CpuNumbers(unittest.TestCase):
    """CodeRabbit on #250: the shell lists cpu10 before cpu2; each reading keeps its CPU's number."""

    def test_numbers_from_the_paths(self):
        sys.path.insert(0, os.path.dirname(HERE))
        import perfrun

        class Dev:
            def sh(self, cmd, timeout=60):
                assert "scaling_cur_freq" in cmd and "cat" in cmd
                return "".join(f"/sys/devices/system/cpu/cpu{i}/cpufreq/scaling_cur_freq {k}\n"
                               for i, k in ((0, 1200000), (1, 1200000), (10, 1608000), (2, 1008000)))
        self.assertEqual(perfrun.cpu_khz(Dev(), "scaling_cur_freq"), {"0": 1200000, "1": 1200000, "2": 1008000, "10": 1608000})
        self.assertEqual(list(perfrun.cpu_khz(Dev(), "scaling_cur_freq")), ["0", "1", "2", "10"])


class Threads(unittest.TestCase):
    """s1rtc5: a local named like the runner's stream-stop helper made it a bool, and its thread died
    on every view (TypeError: 'bool' object is not callable) while the runs went on. Any exception in
    a runner thread now fails the run, and a graded run raises none."""

    def test_a_graded_run_raises_nothing_in_any_thread(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, taps=2)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertNotIn("Exception in thread", p.stderr)
        self.assertNotIn("thread_errors", c)
        self.assertIsNone(c["error"])

    def test_a_thread_that_raises_is_recorded(self):
        sys.path.insert(0, os.path.dirname(HERE))
        import perfrun
        old = threading.excepthook
        try:
            errors = perfrun.watch_threads()
            th = threading.Thread(target=lambda: True(), name="still")
            with open(os.devnull, "w") as quiet, contextlib.redirect_stderr(quiet):
                th.start()
                th.join()
        finally:
            threading.excepthook = old
        self.assertEqual(errors, ["still: TypeError: 'bool' object is not callable"])


class FakeMatchesThePanel(unittest.TestCase):
    """smoke7 failed on the panel while CI was green: the fake logged shorter lines than the page
    does. The fake must log the reference page's lines in the shapes the S1 logged them
    (test/page-lines.json), and as long."""

    def test_every_line_has_the_panels_keys(self):
        with open(os.path.join(HERE, "page-lines.json")) as f:
            want = json.load(f)["shapes"]
        tmp = tempfile.mkdtemp()
        # #410: heavy's dialogs held past its own wait, so the fake logs a settle line too.
        p, _ = refstart(tmp, taps=2, FAKE_SWIPES="1", FAKE_SETTLE_VIA="heavy")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        got = {}
        with open(os.path.join(tmp, "state", "page-lines.jsonl")) as f:
            for l in f:
                d = json.loads(l)
                got.setdefault(d["shape"], set()).add(tuple(d["keys"]))
        self.assertEqual(sorted(got), sorted(want))
        for shape, keys in want.items():
            with self.subTest(shape):
                self.assertEqual(got[shape], {tuple(keys)})


class FakeStateIsAtomic(unittest.TestCase):
    """#251's CI flake: a fake adb process read a state file another was writing (truncated, not yet
    written) and got nothing; the fake screen then slept. Writes are now atomic."""

    def test_a_reader_never_sees_a_half_written_file(self):
        tmp = tempfile.mkdtemp()
        env = dict(os.environ, FAKE_ADB_STATE=tmp)
        code = ("import sys, time; sys.path.insert(0, %r); import fake_adb as F\n"
                "if sys.argv[1] == 'w':\n"
                "    for i in range(3000): F.save('awake_at.json', time.time())\n"
                "else:\n"
                "    F.save('awake_at.json', time.time()); bad = 0\n"
                "    for i in range(3000): bad += F.state('awake_at.json') is None\n"
                "    print(bad)\n") % HERE
        w = subprocess.Popen([sys.executable, "-c", code, "w"], env=env)
        r = subprocess.run([sys.executable, "-c", code, "r"], env=env, capture_output=True, text=True, timeout=120)
        w.wait(timeout=120)
        self.assertEqual(r.stdout.strip(), "0", r.stderr[-800:])


class FakeSplitsLikeThePage(unittest.TestCase):
    """The fake's page_lines must give exactly what the page's consoleLines (stats.js) gives."""

    def test_the_fake_and_the_page_split_alike(self):
        node = shutil.which("node")
        if not node:
            if os.environ.get("CI"):
                raise AssertionError("node is needed")
            raise unittest.SkipTest("node isn't installed (CI has it)")
        sys.path.insert(0, HERE)
        os.environ.setdefault("FAKE_ADB_STATE", tempfile.mkdtemp())
        import fake_adb as F
        texts = ['{"t":"done"}', json.dumps({"x": list(range(500))}, separators=(",", ":")),
                 json.dumps({"names": ["Küche %d 🌡 Wohnzimmer" % i for i in range(200)]}, ensure_ascii=False),
                 "a" * 899 + " " + "b" * 1200]
        js = ("const S=require(process.argv[1]);const ts=JSON.parse(require('fs').readFileSync(0,'utf8'));"
              "process.stdout.write(JSON.stringify(ts.map((t)=>S.consoleLines('GWPERF','k:1',t))))")
        out = subprocess.run([node, "-e", js, os.path.join(os.path.dirname(HERE), "page", "stats.js")],
                             input=json.dumps(texts), capture_output=True, text=True, timeout=30)
        page = json.loads(out.stdout)
        for text, want in zip(texts, page):
            with self.subTest(text[:20]):
                self.assertEqual(F.page_lines("k:1", text), want)
                self.assertTrue(all(len(l.encode()) <= 1023 for l in want))

    def test_the_fakes_protocol_is_the_pages(self):
        sys.path.insert(0, HERE)
        os.environ.setdefault("FAKE_ADB_STATE", tempfile.mkdtemp())
        import fake_adb as F
        with open(os.path.join(os.path.dirname(HERE), "page", "tiers.json")) as f:
            self.assertEqual(json.load(f)["PROTOCOL"], F.PROTOCOL)
        # Equal today also when the fake writes the number itself; it must read it from the pages.
        with open(os.path.join(HERE, "fake_adb.py")) as f:
            self.assertEqual(re.findall(r"PROTOCOL\s*=\s*\d|[\"']protocol[\"']\s*:\s*\d", f.read()), [])


class SurfaceFlingerNames(unittest.TestCase):
    """The Pixel Tablet's Android 17 run (2026-11-19): SurfaceFlinger listed every layer as
    `RequestedLayerState{…}`, the SurfaceView's after the view's hash; the runner named none, and open, restart and live updates were n/a on
    every view. The runner reads that listing, and says so when it can't read one."""

    def build(self, tmp):
        with open(os.path.join(tmp, "device.json"), "w") as f:
            json.dump({"frame": "f", "mem_total_kb": 1, "display": {"density": "240"}, "app": {"package": "sh.edm.s1kiosk"}}, f)
        return report.build(tmp)

    def test_android_15_and_later_layers_give_frames(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_SF_WRAPPED="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertNotIn("SurfaceView this runner can read", p.stderr)
        self.assertTrue(c["presents_mono_ms"])
        with open(os.path.join(tmp, "state", "commands.log")) as f:
            latency = {c for c in f.read().splitlines() if "--latency" in c}
        # By the whole name, the view's hash and #<id> included (the fake, as API 37, answers no other); the
        # BLAST child (the frames) and its parent (none) are both asked, and the newest frame wins.
        self.assertTrue(any("--latency 'f5fea55 SurfaceView[sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity](BLAST)#163'" in c
                            for c in latency), latency)
        run = self.build(tmp)["graded"]["reference"]["per_run"][0]
        self.assertIsNone(run["start_error"])
        self.assertGreater(run["start_ms"], 0)
        for t, r in run["tiers"].items():
            self.assertIsNotNone(r.get("load_ms"), (t, r.get("load_error")))
            self.assertIsNotNone(r.get("latency"), (t, r.get("updates_error")))
            self.assertIs(r["sf_valid"], True, t)               # scrolling from frames on screen, not rAF only

    def test_a_listing_it_cant_read_is_said_once(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_SF_UNNAMED="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(p.stderr.count("SurfaceView this runner can read"), 1, p.stderr[-1500:])
        self.assertIn("SomeNewLayer<sh.edm.s1kiosk/", p.stderr)
        self.assertEqual(c["presents_mono_ms"], [])


def latency_reads_inside_windows(state):
    """The `--latency` reads the runner sent while a page's window was open (the fake marks each window)."""
    with open(os.path.join(state, "commands.log")) as f:
        lines = f.read().splitlines()
    inside, n = False, 0
    for l in lines:
        if l.startswith("window open"):
            inside = True
        elif l.startswith("window closed"):
            inside = False
        elif inside and "--latency" in l:
            n += 1
    return n


def sfcheck(tmp, *extra, **env_extra):
    env, state = fake_env(tmp, **env_extra)
    p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "sfcheck", "--out", tmp,
                        "--url", "http://127.0.0.1:1/clarity/page/sfcheck.html", *extra], env=env, capture_output=True, text=True, timeout=120)
    with open(os.path.join(tmp, "sfcheck.json")) as f:
        return p, json.load(f)


class SurfaceFlingerHistory(unittest.TestCase):
    """#643: the Pixel Tablet's Android 17 keeps about 59 frames (0.98 s) in `--latency`, shorter than a swipe's
    window; the S1 keeps 127 (2.1 s), enough. Where one read can't cover a window, the runner reads during it."""

    def build(self, tmp):
        with open(os.path.join(tmp, "device.json"), "w") as f:
            json.dump({"frame": "f", "mem_total_kb": 1, "display": {"density": "240"}, "app": {"package": "sh.edm.s1kiosk"}}, f)
        return report.build(tmp)["graded"]["reference"]["per_run"][0]

    def test_the_s1_reads_once_after_each_window_and_never_inside_one(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        # Every read is Device.sf()'s: a --list, then --latency of its one layer. A read during a window (SfReads)
        # is a --latency alone.
        with open(os.path.join(tmp, "state", "commands.log")) as f:
            log = f.read().splitlines()
        sf = [l for l in log if "dumpsys SurfaceFlinger" in l]
        self.assertTrue(sf)
        self.assertEqual([b for a, b in zip(sf, sf[1:]) if "--latency" in b and "--list" not in a], [])
        self.assertEqual(sum("--latency" in l for l in sf), sum("--list" in l for l in sf))
        for t, e in c["tiers"].items():
            for sw in e["swipes"]:
                self.assertEqual(list(sw["sf"]), ["period_ms", "window_ms", "oldest_ms", "presents_ms"], t)  # no "reads"
        self.assertTrue(all(r["sf_valid"] for r in self.build(tmp)["tiers"].values()))

    def test_one_read_window_record_is_the_old_one(self):
        """The S1 path's record, from SfReads with no polling, is what one read gave (test_report's one_read_record)."""
        import perfrun
        import test_report

        class Dev:
            sf_name = "SurfaceView - p/p.A#0"

            def sf(self):
                return 16.666, [int(t * 1e6) for t in range(10_000, 12_100, 16)]

        rec = perfrun.SfReads(Dev(), 0).window(10.5, 11.6)
        self.assertEqual(json.dumps(rec), json.dumps(test_report.one_read_record(*Dev().sf(), 10.5, 11.6)))

    def test_a_short_history_is_read_during_each_window(self):
        # 28 frames (0.47 s) against the fake's 0.5 s windows, in real time (the frames and the reads are): one read,
        # after the step line and a --list, reaches back too little; reads every 0.05 s join up.
        runs = {}
        for poll in ("0", "0.05"):
            tmp = tempfile.mkdtemp()
            p, c = refstart(tmp, FAKE_SF_HISTORY="28", GW_PERF_TIME_SCALE="1", GW_PERF_SF_POLL=poll)
            self.assertEqual(p.returncode, 0, p.stderr[-1500:])
            runs[poll] = (c, self.build(tmp), latency_reads_inside_windows(os.path.join(tmp, "state")))
        c, run, inside = runs["0"]
        self.assertEqual([r["sf_valid"] for r in run["tiers"].values()], [False] * 4)    # the tablet's run 3
        c, run, inside = runs["0.05"]
        self.assertGreater(inside, 8)
        self.assertEqual([r["sf_valid"] for r in run["tiers"].values()], [True] * 4)
        self.assertIsNone(run["start_error"])
        for t, e in c["tiers"].items():
            self.assertTrue(all(sw["sf"]["reads"] >= 3 for sw in e["swipes"]), t)
            self.assertLess(e["step"]["sf"]["oldest_ms"], e["step"]["sf"]["window_ms"][0], t)  # the updates window too

    def test_the_check_reads_the_s1_once(self):
        p, out = sfcheck(tempfile.mkdtemp())
        self.assertEqual(p.returncode, 0, p.stderr[-800:])
        self.assertEqual(p.stdout.strip(), "0")
        self.assertEqual(out["poll_s"], 0)
        # It stops once the frames are enough for one read (at most the S1's 127), full or not.
        self.assertLessEqual(out["frames"], 127)
        self.assertGreaterEqual(out["reach_ms"], P.SF_ONE_READ_MS)

    def test_the_check_reads_a_short_history_during_each_window(self):
        # 100 frames (1.67 s): under the one-read bar, as the Pixel Tablet's 59 are (test_report has its numbers);
        # room for a fake read slowed by a busy test machine (a Python start each).
        p, out = sfcheck(tempfile.mkdtemp(), FAKE_SF_HISTORY="100", GW_PERF_TIME_SCALE="1")
        self.assertEqual(p.returncode, 0, p.stderr[-800:])
        self.assertEqual((out["frames"], out["full"]), (100, True))
        self.assertAlmostEqual(out["reach_ms"], 1666.7, delta=0.1)       # 100 frames, a refresh apart
        self.assertEqual(out["poll_s"], P.SF_POLL_MAX_S)
        self.assertEqual(float(p.stdout.strip()), out["poll_s"])
        self.assertIn("a read every 0.3 s", p.stderr)

    def test_the_check_fails_fast(self):
        p, out = sfcheck(tempfile.mkdtemp(), FAKE_SF_HISTORY="3", GW_PERF_TIME_SCALE="1")
        self.assertEqual(p.returncode, 2)
        self.assertIsNone(out["poll_s"])
        self.assertIn("reads can't come often enough", out["why"])
        p, out = sfcheck(tempfile.mkdtemp(), FAKE_SF_UNNAMED="1")
        self.assertEqual(p.returncode, 2)
        self.assertIn("lists no SurfaceView of sh.edm.s1kiosk", out["why"])

    def test_a_lockdown_reads_once(self):
        p, out = sfcheck(tempfile.mkdtemp(), FAKE_LOCKDOWN="1")
        self.assertEqual(p.returncode, 0, p.stderr[-800:])
        self.assertEqual(out["poll_s"], 0)
        self.assertIn("lockdown", out["why"])


class SurfaceFlingerCheckInRunSh(unittest.TestCase):
    """#643: run.sh runs the check before the test page and the graded part, and stops there when it fails."""

    def run_sh(self, *extra, **env_extra):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_HA_URL=f"http://127.0.0.1:{port(76)}", GW_PERF_COLD_SETTLE="0", GW_PERF_RUN_GAP="0",
                              **env_extra)
        out = os.path.join(tmp, "out")
        p = subprocess.run([RUN, "--frame", "aqara-s1-plus", "--runs", "1", "--out", out, "--port", port(76), "--quick", *extra],
                           env=env, capture_output=True, text=True, timeout=300)
        return p, out, state

    def test_check_only(self):
        p, out, state = self.run_sh("--sf-check-only")
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        with open(os.path.join(out, "sfcheck.json")) as f:
            self.assertEqual(json.load(f)["poll_s"], 0)
        self.assertEqual([f for f in os.listdir(out) if f.startswith(("synthetic-", "refstart-", "idle-"))], [])
        self.assertIn("SurfaceFlinger check done", p.stderr)
        with open(os.path.join(state, "commands.log")) as f:
            log = f.read()
        self.assertNotIn("lanprobe", log)
        self.assertNotIn("nc ", log)                                     # no LAN probe for the check alone

    def test_a_short_history_stops_the_run_before_it_starts(self):
        p, out, state = self.run_sh(FAKE_SF_HISTORY="3", GW_PERF_TIME_SCALE="1")
        self.assertEqual(p.returncode, 2, p.stderr[-2000:])
        self.assertIn("the SurfaceFlinger check failed, before the run", p.stderr)
        self.assertEqual([f for f in os.listdir(out) if f.startswith(("synthetic-", "refstart-"))], [])

    def test_a_set_way(self):
        # --sf-check-only is the check: a set way would skip it (CodeRabbit on #648).
        p, out, _ = self.run_sh("--sf-check-only", "--sf-poll", "0.2")
        self.assertEqual(p.returncode, 2)
        self.assertIn("--sf-check-only runs the check", p.stderr)
        p, _, _ = self.run_sh("--sf-poll", "often")
        self.assertEqual(p.returncode, 2)
        self.assertIn("--sf-poll is auto, 0 or seconds", p.stderr)

    def test_an_idle_only_sample_takes_the_set_way(self):
        """CodeRabbit on #648: --idle-only leaves before the check; it reads at --sf-poll's rhythm, the known layer
        alone between full listings (sf_poll_read), or once every 1.5 s without it."""
        counts = {}
        for poll in ("0", "0.1"):
            p, out, state = self.run_sh("--idle-only", "x", "--sf-poll", poll, GW_PERF_IDLE_WINDOW="3", GW_PERF_TIME_SCALE="1")
            self.assertEqual(p.returncode, 0, p.stderr[-2000:])
            with open(os.path.join(state, "commands.log")) as f:
                sf = [l for l in f.read().splitlines() if "dumpsys SurfaceFlinger" in l]
            counts[poll] = (sum("--list" in l for l in sf), sum("--latency" in l for l in sf))
        self.assertEqual(counts["0"][0], counts["0"][1])                 # each read a listing and its layer
        self.assertGreater(counts["0.1"][1], counts["0.1"][0])           # the layer alone between listings
        self.assertGreater(counts["0.1"][1], 2 * counts["0"][1])


class LongLines(unittest.TestCase):
    """The S1 keeps 1023 bytes of a page's console line (smoke7, 2026-11-12). Pages split longer
    lines into parts (stats.js consoleLines) and the runner joins them (perflib.PageLines)."""

    def test_a_run_with_real_sized_lines_completes(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertIsNone(c["error"])
        self.assertEqual((c["missing_steps"], c["bad_page_lines"]), ([], 0))
        for t, e in c["tiers"].items():
            self.assertEqual(len(e["step"]["changes"]), 48, t)            # about 1.8 KB, joined from parts

    def test_smoke7_unsplit_lines_are_cut_and_said_not_a_failed_run(self):
        """The page lines as d60ec95 logged them: the updates steps are cut at 1023 bytes. The run
        isn't failed: scroll, taps and loads stand, and the report says what didn't parse."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_NO_SPLIT="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertIsNone(c["error"])
        self.assertEqual(c["missing_steps"], ["light", "typical-nocam", "typical", "heavy"])
        # Each page's updates step, and the camera lines: since protocol 6 (#202) even typical's one
        # stream carries its WebRTC receiver's counts, so it is longer than a logcat line too.
        self.assertEqual(c["bad_page_lines"], 6)
        self.assertNotIn("camera", c["tiers"]["heavy"])
        self.assertNotIn("camera", c["tiers"]["typical"])
        with open(os.path.join(tmp, "device.json"), "w") as f:
            json.dump({"frame": "f", "mem_total_kb": 1, "display": {"density": "240"}, "app": {"package": "sh.edm.s1kiosk"}}, f)
        rep = report.build(tmp)
        self.assertIn("6 page line(s) didn't parse or never arrived whole (cut off in logcat?)", rep["grade"]["provisional_reasons"])
        t = rep["graded"]["reference"]["tiers"]["light"]
        self.assertIsNotNone(t["scroll"])
        self.assertEqual(t["updates_errors"], ["the page's updates step never arrived"])
        self.assertNotIn("protocol ;", rep["grade"]["headline"])

    def test_a_line_whose_parts_never_all_arrive_is_counted_at_done(self):
        """Verifier ce144dd0: a part left pending was dropped silently. At the page's done it counts."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_DROP_LAST_PART="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["missing_steps"], ["light", "typical-nocam", "typical", "heavy"])
        self.assertEqual(c["bad_page_lines"], 4)                         # each page's
        self.assertEqual([e["lost_lines"] for e in c["tiers"].values()], [1, 1, 1, 1])

    def test_a_lost_scroll_step_is_counted_and_makes_it_provisional(self):
        """A swipe answered with no scroll step back would leave 5 of 6 swipes pooled, unremarked."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_DROP_SCROLL_STEP="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual([e["lost_scroll_steps"] for e in c["tiers"].values()], [1, 1, 1, 1])
        self.assertEqual(c["bad_page_lines"], 0)                         # nothing cut: the line never came
        with open(os.path.join(tmp, "device.json"), "w") as f:
            json.dump({"frame": "f", "mem_total_kb": 1, "display": {"density": "240"}, "app": {"package": "sh.edm.s1kiosk"}}, f)
        g = report.build(tmp)["grade"]
        self.assertIn("4 swipe(s) answered with no scroll step back", g["provisional_reasons"])

    def test_page_lines_joins_parts_and_counts_what_doesnt_parse(self):
        pl = perfrun.P.PageLines()
        self.assertEqual(pl.feed('x GWPERF {"t":"done"}'), {"t": "done"})
        self.assertIsNone(pl.feed('GWPERF+ ab:1 0/2 {"t":"st'))
        self.assertIsNone(pl.feed("an unrelated line in between"))
        self.assertEqual(pl.feed('GWPERF+ ab:1 1/2 ep","n":1}'), {"t": "step", "n": 1})
        self.assertIsNone(pl.feed('GWPERF {"t":"step","changes":[[1,2'))     # cut off
        self.assertIsNone(pl.feed("GWPERF+ nonsense"))
        self.assertEqual(pl.bad, 2)
        self.assertIsNone(pl.feed('GWPERF+ cd:9 0/3 {"t":'))               # page cd's line, never finished
        self.assertIsNone(pl.feed('GWPERF+ ef:2 0/2 {"t":'))               # another page's
        self.assertEqual((pl.close("cd"), pl.bad), (1, 3))
        self.assertEqual(sorted(pl.parts), ["ef:2"])

    def test_the_pages_splitter_and_the_runners_joiner_agree(self):
        """stats.js (run in node) splits; perflib joins. Every part fits the panel's 1023 bytes."""
        node = shutil.which("node")
        if not node:
            if os.environ.get("CI"):
                raise AssertionError("node is needed")
            raise unittest.SkipTest("node isn't installed (CI has it)")
        text = json.dumps({"t": "step", "names": ["Entity %d sensor" % i for i in range(120)], "x": list(range(200))},
                          separators=(",", ":"))
        js = ("const S=require(process.argv[1]);const t=require('fs').readFileSync(0,'utf8');"
              "process.stdout.write(JSON.stringify(S.consoleLines('GWPERF','n0:7',t)))")
        out = subprocess.run([node, "-e", js, os.path.join(os.path.dirname(HERE), "page", "stats.js")],
                             input=text, capture_output=True, text=True, timeout=30)
        lines = json.loads(out.stdout)
        self.assertGreater(len(lines), 2)
        self.assertTrue(all(len(l.encode()) <= 1023 for l in lines), [len(l) for l in lines])
        self.assertTrue(all(l == l.strip() for l in lines))                  # logcat may trim
        pl = perfrun.P.PageLines()
        got = [pl.feed("I Isolated Web Content: " + l) for l in lines]
        self.assertEqual(got[-1], json.loads(text))
        self.assertEqual(pl.bad, 0)


class NonceGate(unittest.TestCase):
    """Only the current page's nonce may make the runner tap or swipe."""

    def test_a_stale_request_from_the_last_page_is_ignored(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_STALE_READY="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(unasked(tmp), [])
        with open(os.path.join(tmp, "state", "swipes.json")) as f:
            self.assertEqual(json.load(f), 8)                 # 2 on each graded page, none on the warm-up
        self.assertIsNone(c["error"])


class Gestures(unittest.TestCase):
    """Page-given coordinates are checked before anything reaches `adb shell`."""
    SIZE = (1440, 720)

    def test_good_requests(self):
        self.assertEqual(perfrun.gesture({"t": "input-ready", "x": 720, "y": 360, "taps": 10}, self.SIZE), ("input tap 720 360", 10))
        self.assertEqual(perfrun.gesture({"t": "swipe-ready", "x": 720, "y1": 612, "y2": 108, "ms": 300}, self.SIZE),
                         ("input swipe 720 612 720 108 300", 1))

    def test_bad_requests_are_refused_not_raised(self):
        bad = [{"t": "swipe-ready", "x": 720, "y1": 612, "y2": 108},                     # no ms (a KeyError before)
               {"t": "swipe-ready", "x": "720; reboot", "y1": 612, "y2": 108, "ms": 300},
               {"t": "swipe-ready", "x": 720.5, "y1": 612, "y2": 108, "ms": 300},
               {"t": "swipe-ready", "x": True, "y1": 612, "y2": 108, "ms": 300},
               {"t": "swipe-ready", "x": 720, "y1": 612, "y2": 720, "ms": 300},           # one past the bottom
               {"t": "swipe-ready", "x": 720, "y1": 612, "y2": 108, "ms": 10},
               {"t": "swipe-ready", "x": 720, "y1": 612, "y2": 108, "ms": 60000},
               {"t": "input-ready", "x": -1, "y": 360, "taps": 10},
               {"t": "input-ready", "x": 720, "y": 360, "taps": 0},
               {"t": "input-ready", "x": 720, "y": 360, "taps": 10_000}]
        for pl in bad:
            cmd, why = perfrun.gesture(pl, self.SIZE)
            self.assertIsNone(cmd, pl)
            self.assertIsInstance(why, str)
        self.assertEqual(perfrun.gesture({"t": "input-ready", "x": 1, "y": 1, "taps": 1}, None), (None, "the screen size is unknown"))

    def test_a_bad_swipe_line_is_an_error_on_that_page_and_nothing_is_sent(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_SWIPES="1", FAKE_SWIPE_WAIT="1",
                        FAKE_BAD_SWIPE=json.dumps({"t": "swipe-ready", "n": 0, "x": "720; reboot", "y1": 612, "y2": 108}))
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertTrue(c["tiers"]["light"]["error"].startswith("bad swipe-ready line: a swipe at '720; reboot'"))
        with open(os.path.join(tmp, "state", "commands.log")) as f:
            log = f.read()
        self.assertNotIn("reboot", log)
        self.assertNotIn("input swipe", log)


class OwnPageWait(unittest.TestCase):
    """The A fix's time rule, pinned directly: 2 s after the own page stopped, with no new load."""

    def test_the_tap_cap_holds_whatever_taps_asks(self):
        """--taps 100 doesn't let a page ask for 100 taps."""
        self.assertEqual(perfrun.tap_cap(100), perfrun.MAX_TAPS)
        self.assertEqual((perfrun.tap_cap(10), perfrun.tap_cap(0)), (10, perfrun.MAX_TAPS))
        req = {"t": "input-ready", "x": 720, "y": 360, "taps": 100}
        self.assertIsNone(perfrun.gesture(req, (1440, 720), max_taps=perfrun.tap_cap(100))[0])

    def test_two_seconds_after_the_stop(self):
        self.assertFalse(perfrun.own_page_finished(None, 0.0, 100.0))
        self.assertFalse(perfrun.own_page_finished(26.0, 100.0, 101.99))
        self.assertTrue(perfrun.own_page_finished(26.0, 100.0, 102.0))
        self.assertEqual(perfrun.OWN_PAGE_QUIET_S, 2.0)


class Order(unittest.TestCase):
    """smoke5: light always came first, right after the kiosk's own dashboard was torn down, and read
    worse than heavy. A warm-up page takes that, and the order turns each run."""

    def opened(self, tmp):
        with open(os.path.join(tmp, "state", "opened.json")) as f:
            return json.load(f)

    def test_run_2_starts_one_page_in_after_the_restart_page(self):
        """Protocol 4: the kiosk's own page after the restart (the light reference dashboard, not
        graded as light) is the warm-up; then the order turns."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, run=2)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["order"], ["typical-nocam", "typical", "heavy", "light"])
        self.assertEqual(self.opened(tmp), ["typical-nocam", "typical", "heavy", "light"])
        self.assertIsNotNone(c["restart_page"])
        self.assertEqual(len(c["between"]), 4)

    def test_run_3(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, run=3)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(self.opened(tmp), ["typical", "heavy", "light", "typical-nocam"])


class Restart(unittest.TestCase):
    """Protocol 4: restart = process start to the kiosk's own page (the reference dashboard run.sh
    pointed it at) being usable on screen."""

    def build(self, tmp):
        with open(os.path.join(tmp, "device.json"), "w") as f:
            json.dump({"frame": "f", "mem_total_kb": 1, "display": {"density": "240"}, "app": {"package": "sh.edm.s1kiosk"}}, f)
        return report.build(tmp)

    def test_restart_ends_at_the_usable_reference_dashboard(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["restart_page"]["usable"]["first_screen_cards"], 13)
        run = self.build(tmp)["graded"]["reference"]["per_run"][0]
        self.assertIsNone(run["start_error"])
        # Usable comes after the engine is ready: the restart now includes loading HA's frontend.
        self.assertGreater(run["start_ms"], run["engine_ready_ms"])

    def test_the_views_wait_for_the_restart_page_not_its_load_event(self):
        """HA's load event comes seconds before the start view is usable (on the S1, 10 s restarts).
        A page that has finished loading mustn't open the views: the restart's usable line would then
        arrive while a view is current and be dropped (CodeRabbit on 0c620b0)."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_RESTART_SLOW="3")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertIsNotNone(c.get("restart_page"))
        self.assertEqual(c["own_page_state"], "reference")
        self.assertIsNone(self.build(tmp)["graded"]["reference"]["per_run"][0]["start_error"])

    def test_a_lost_restart_done_line_doesnt_stall_the_run(self):
        """F8: only the restart page's `done` line is lost. The usable line is there, so the views
        open after RESTART_DONE_WAIT_S instead of waiting out the run, and the restart still counts."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_NO_RESTART_DONE="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertTrue(c["restart_done_missing"])
        self.assertEqual(sorted(c["tiers"]), ["heavy", "light", "typical", "typical-nocam"])
        self.assertIsNone(self.build(tmp)["graded"]["reference"]["per_run"][0]["start_error"])

    def run_short(self, timeout, fallback="", held_s=None, **env):
        with FakeHA() as ha:
            tmp = tempfile.mkdtemp()
            env.setdefault("FAKE_SAVED_URL", ha.url + "/clarity-ref/start")
            e, state = fake_env(tmp, GW_PERF_COLD_SETTLE="0", FAKE_HA_URL=ha.url, **env)
            if held_s:
                # The test hold run.sh sets before the graded part, on the fake's (scaled) clock.
                with open(os.path.join(state, "test_hold.json"), "w") as f:
                    json.dump(time.time() / float(e["GW_PERF_TIME_SCALE"]) + held_s, f)
            p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart", "--out", tmp,
                                "--run", "1", "--settle", "0", "--url", ha.url, "--taps", "0", "--timeout", str(timeout),
                                "--between-quiet", "0.3", "--fallback", fallback], env=e, capture_output=True, text=True,
                               timeout=timeout + 60)
            with open(os.path.join(tmp, "refstart-1.json")) as f:
                c = json.load(f)
            self.last_out = tmp
            with open(os.path.join(state, "commands.log")) as f:
                return p, c, f.read().splitlines()

    def test_a_screen_that_goes_off_fails_the_run(self):
        """smoke10: the kiosk's 20 s night timeout turned the screen off while views opened; nothing timed
        then is the panel's, so the run isn't graded."""
        p, c, _ = self.run_short(90, FAKE_SCREEN_OFF="typical")
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("the screen went off during the graded run", c["error"])
        self.assertEqual(c["screen_off"][0]["during"], "typical")

    def test_a_view_that_never_finishes_fails_the_run(self):
        """smoke10's heavy: usable, then nothing (the screen was off) until the run's limit."""
        p, c, _ = self.run_short(60, FAKE_HANG="heavy")
        self.assertNotEqual(p.returncode, 0)
        self.assertEqual(c["error"], "heavy didn't finish within 60 s")

    def test_a_fallback_view_that_never_finishes_fails_only_itself(self):
        """#202: the HLS copies come after the graded views; one that hangs (or a screen that goes off
        during it) leaves the graded run standing, and says why."""
        p, c, _ = self.run_short(90, fallback="typical-hls,heavy-hls", FAKE_HANG="heavy-hls", FAKE_SCREEN_OFF="heavy-hls")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertIsNone(c["error"])
        self.assertEqual(c["fallback_error"], "heavy-hls didn't finish within 90 s")
        self.assertTrue(c["fallback"]["typical-hls"]["done"])
        self.assertEqual(sorted(c["tiers"]), ["heavy", "light", "typical", "typical-nocam"])
        self.assertTrue(all(e.get("done") for e in c["tiers"].values()))
        self.assertEqual(c["fallback"]["heavy-hls"]["error"], "the screen went off during this view")

    def test_a_screen_off_in_a_graded_view_stays_the_runs_error_when_a_copy_follows(self):
        """CodeRabbit on #205: the copies after it must not take a graded view's screen-off away."""
        p, c, _ = self.run_short(90, fallback="typical-hls,heavy-hls", FAKE_SCREEN_OFF="typical")
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("the screen went off during the graded run", c["error"])
        self.assertNotIn("fallback_error", c)

    def test_a_view_that_cant_finish_times_out_and_the_run_is_still_graded(self):
        """#202, smoke 7: heavy went live and never finished; the run used to wait 900 s and be dropped.
        Now the view times out at the deadline, the test hold keeps the screen on while nothing touches
        it (the fake's sleeps 4 s after the last touch), what heavy measured stands, and what it didn't
        fails: measured, not missing, so the grade isn't provisional for it."""
        p, c, _ = self.run_short(90, FAKE_HANG_LIVE="heavy", GW_PERF_STAGE_IDLE_S="12", FAKE_SCREEN_SLEEP_S="4",
                                 GW_PERF_RESTART_WAKE_S="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertIsNone(c["error"])
        self.assertEqual(c["timed_out"], ["heavy"])
        self.assertGreaterEqual(c["tiers"]["heavy"]["timed_out_s"], 12)     # usable, then nothing for 12 s
        self.assertEqual(c["tiers"]["heavy"]["timed_out_after"], "warmup")
        self.assertNotIn("screen_off", c)
        self.assertNotIn("recoveries", c)                     # heavy was run 1's last view: nothing to carry on to
        rep = self.build(self.last_out)
        ref = rep["graded"]["reference"]
        self.assertEqual((ref["graded_runs"], ref["timed_out"]), (1, [[1, "heavy"]]))
        heavy = ref["tiers"]["heavy"]
        self.assertIsNotNone(heavy["load_ms"])                                  # what heavy measured stands
        self.assertEqual((heavy["timed_out_runs"], heavy["timed_out_tasks"]), (1, ["camera", "dialog", "scroll", "tap", "updates"]))
        self.assertIn("| the view didn't get to them, slow |", report.markdown(rep))   # Clarity v3: its dialogs fail
        self.assertNotIn("heavy.scroll", rep["grade"]["missing"])               # what it didn't fails, measured
        self.assertEqual(rep["grade"]["bands"]["heavy"]["scroll"], "slow")
        self.assertFalse(any("deadline" in r for r in rep["grade"]["provisional_reasons"]), rep["grade"]["provisional_reasons"])
        self.assertIn("heavy in run 1 (camera, dialog, scroll, tap, updates)", report.markdown(rep))   # named in the detail

    def test_after_a_view_times_out_the_kiosk_restarts_and_the_run_carries_on(self):
        """#202, s1rtc3: heavy timed out in every run, and in runs 2 and 3 the views after it were never
        opened, so the summary was refused. Now the runner restarts the kiosk and opens the rest: the
        view that can't finish fails its own tasks and nothing else."""
        p, c, log = self.run_short(90, fallback="typical-hls", FAKE_HANG_LIVE="typical", GW_PERF_STAGE_IDLE_S="8",
                                   GW_PERF_RECOVER_LAUNCH_S="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertIsNone(c["error"])
        self.assertEqual(c["timed_out"], ["typical"])
        self.assertEqual(len(c["recoveries"]), 1)
        self.assertEqual(c["holds"], sum(1 for x in log if x.startswith("view ")))   # one before each view
        self.assertEqual((c["recoveries"][0]["after"], c["recoveries"][0]["launch"]), ("typical", "am-start"))
        self.assertTrue(c["tiers"]["heavy"].get("done"))                       # opened after the restart
        self.assertTrue(c["fallback"]["typical-hls"].get("done"))
        # The order: typical's open, the stop, the relaunch, then heavy's open.
        order = [x for x in log if x.startswith(("view ", "shell am force-stop", "shell am start -W -n"))]
        i = next(k for k, x in enumerate(order) if x.startswith("view typical "))
        self.assertTrue(order[i + 1].startswith("shell am force-stop"), order)
        self.assertTrue(order[i + 2].startswith("shell am start -W -n"), order)
        self.assertTrue(order[i + 3].startswith("view heavy"), order)
        # The run's restart is the first one's, not the recovery's.
        self.assertEqual(c["restart_page"]["tier"], "start")
        self.assertLess(c["restart_page"]["mono"], c["tiers"]["light"]["page"]["mono"])
        rep = self.build(self.last_out)
        g = rep["grade"]
        self.assertNotIn("typical.scroll", g["missing"])
        self.assertEqual([t for t in ("light", "typical", "heavy") if g["bands"][t]["scroll"] is None], [])
        self.assertEqual(g["bands"]["typical"]["scroll"], "slow")
        self.assertNotEqual(g["bands"]["heavy"]["scroll"], "slow")          # heavy measured as any run's
        self.assertFalse(any("deadline" in r for r in g["provisional_reasons"]), g["provisional_reasons"])
        # The summary: typical's timed-out run is in it as failing, and nothing reads as not measured.
        out = summary.summarise(rep, allow_provisional=True)
        self.assertEqual(out["pages"]["typical"]["timed_out_runs"], 1)
        self.assertEqual(out["pages"]["typical"]["camera"]["runs"][0]["timed_out"], True)
        self.assertEqual(out["pages"]["heavy"]["timed_out_runs"], 0)
        # (This short run sends no taps, so light's and heavy's tap aren't measured; typical's fails.)
        self.assertEqual([m for m in grade.grade_summary(out).get("missing") or [] if m.startswith("typical.")], [])

    def test_the_screen_stays_on_through_a_slow_restart(self):
        """s1rtc7 run 2: the screen went off 20.4 s after the force-stop, before the kiosk's first
        frame (the S1's 20 s night timeout), and the restart then waited 280 s. Here the fake's
        screen sleeps 4 s after the last wake or touch, through a 9 s restart: the wake key from the
        stop on keeps it on, and the test hold after it (#240)."""
        # The fake's screen sleeps 4 s after the last wake or touch, and every nudge comes each second:
        # a 4x margin for a slow CI runner's adb stand-ins. The restart (9 s) outlasts the sleep twice.
        nudged = dict(FAKE_SCREEN_SLEEP_S="4", FAKE_RESTART_SLOW="9", GW_PERF_RESTART_WAKE_S="1")
        p, c, _ = self.run_short(90, **nudged)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertIsNone(c["error"])
        self.assertNotIn("screen_off", c)
        self.assertGreaterEqual(c["restart_wakes"], 6)                 # 9 s at one a second, and the relaunch's
        # Without the restart's nudges the same fake screen goes off in the restart, as on the S1.
        p, c, _ = self.run_short(90, **dict(nudged, GW_PERF_RESTART_WAKE_S="1000"))
        self.assertEqual(c["screen_off"][0]["during"], "the restart", c.get("screen_off"))
        self.assertIn("first the restart", c["error"])
        # Without the hold (a kiosk before #240) it goes off in the views, which this run doesn't touch.
        p, c, _ = self.run_short(90, **dict(nudged, FAKE_NO_TEST_HOLD="1"))
        self.assertNotIn("the restart", {x["during"] for x in c.get("screen_off", [])}, c.get("screen_off"))
        self.assertIn("the screen went off during the graded run", c["error"])

    def test_a_hold_from_before_the_restart_keeps_the_screen_on_through_it(self):
        """The S1's night run (2026-11-16): in each graded restart the new process's own 20 s idle
        timer turned the screen off before the runner sent the hold again, and the wake key doesn't
        reset that timer. The hold run.sh set now outlives the restart."""
        night = dict(FAKE_SCREEN_SLEEP_S="100", FAKE_KIOSK_IDLE_S="4", FAKE_RESTART_SLOW="9", GW_PERF_RESTART_WAKE_S="1")
        p, c, _ = self.run_short(90, held_s=1800, **night)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertNotIn("screen_off", c)
        # A hold that died with the process, as in that run: the screen goes off in the restart.
        p, c, _ = self.run_short(90, held_s=1800, **dict(night, FAKE_HOLD_DIES="1"))
        self.assertEqual(c["screen_off"][0]["during"], "the restart", c.get("screen_off"))

    def test_a_slow_view_that_keeps_going_isnt_cut_off(self):
        """Protocol 8 (#202, s1rtc4): heavy was usable after 43 s and still working at 300 s, before its
        swipes. The usable deadline covers only a view that never became usable; one that keeps
        saying what it does runs every task."""
        # In real time, as CpuClock: the 1.2 s stages against the 5 s stage deadline are the margin,
        # and on the compressed clock a busy CPU's delay counts five times (CI, 8 at a time, #268).
        p, c, _ = self.run_short(90, FAKE_STAGE_DELAY="1.2", FAKE_STAGE_VIEW="heavy", GW_PERF_VIEW_DEADLINE_S="4",
                                 GW_PERF_STAGE_IDLE_S="5", GW_PERF_TIME_SCALE="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertIsNone(c["error"])
        self.assertNotIn("timed_out", c)
        self.assertTrue(c["tiers"]["heavy"]["done"])
        self.assertGreater(len(c["tiers"]["heavy"]["swipes"]), 0)

    def test_a_view_that_never_becomes_usable_times_out_at_the_deadline(self):
        p, c, _ = self.run_short(90, FAKE_NEVER_USABLE="typical", GW_PERF_VIEW_DEADLINE_S="5", GW_PERF_STAGE_IDLE_S="60",
                                 GW_PERF_RECOVER_LAUNCH_S="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["timed_out"], ["typical"])
        self.assertEqual(c["tiers"]["typical"]["timed_out_after"], "usable")
        self.assertTrue(c["tiers"]["heavy"]["done"])
        rep = self.build(self.last_out)
        self.assertEqual(rep["graded"]["reference"]["tiers"]["typical"]["timed_out_tasks"], ["blocking", "camera", "dialog", "open", "scroll", "tap", "updates"])

    def test_a_view_drawn_but_never_free_keeps_its_open_and_blocks_for_the_deadline(self):
        """#264's verifier: rubric 2 times the open to the draw, so a view that draws fast and is never
        free gets its real open; its blocking takes the deadline, and usable isn't measured."""
        p, c, _ = self.run_short(90, FAKE_NEVER_FREE="typical", GW_PERF_VIEW_DEADLINE_S="5", GW_PERF_STAGE_IDLE_S="60",
                                 GW_PERF_RECOVER_LAUNCH_S="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["timed_out"], ["typical"])
        self.assertEqual(c["tiers"]["typical"]["timed_out_after"], "usable")
        self.assertNotIn("page", c["tiers"]["typical"])
        # Its own draw: the fake draws each view at its own time (typical 210 ms after its navigation).
        d = c["tiers"]["typical"]["drawn"]
        self.assertEqual(d["tier"], "typical")
        self.assertEqual(round(d["usable"]["rendered_tick_ms"] - d["nav_ms"]), 210)
        for v in ("light", "heavy"):
            self.assertEqual(c["tiers"][v]["drawn"]["tier"], v)
        self.assertTrue(c["tiers"]["heavy"]["done"])
        t = self.build(self.last_out)["graded"]["reference"]["tiers"]["typical"]
        self.assertEqual(t["timed_out_tasks"], ["blocking", "camera", "dialog", "scroll", "tap", "updates"])
        self.assertLess(t["load_ms"]["median"], 1000)             # drawn 210 ms after the navigation (and its frame)
        self.assertEqual(t["blocking_ms"]["median"], c["tiers"]["typical"]["timed_out_s"] * 1000)
        self.assertIsNone((t.get("usable_ms") or {}).get("median"))

    def test_a_test_hold_before_each_view_and_no_wake_after_it(self):
        """#240: the hold goes out before each view opens (a long run would outlast one), and from the
        first one on nothing else wakes the screen."""
        p, c, log = self.run_short(90)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assert_held_views(c, log)

    def test_the_views_get_the_hold_without_the_restart_page_too(self):
        """CodeRabbit on #332: a kiosk that comes back on its own page, with no restart page, opens the
        views by the other path. They get the hold too, and the wake key stops."""
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, GW_PERF_COLD_SETTLE="0", FAKE_SCREEN_SLEEP_S="4", GW_PERF_RESTART_WAKE_S="1")
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart",
                            "--out", tmp, "--run", "1", "--settle", "0", "--url", HA.url,
                            "--taps", "0"], env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        with open(os.path.join(tmp, "refstart-1.json")) as f:
            c = json.load(f)
        with open(os.path.join(state, "commands.log")) as f:
            log = f.read().splitlines()
        self.assertNotEqual(c["own_page_state"], "reference")
        self.assert_held_views(c, log)
        self.assertNotIn("screen_off", c)

    def assert_held_views(self, c, log):
        views = [i for i, x in enumerate(log) if x.startswith("view ")]
        holds = [i for i, x in enumerate(log) if "--ez test_hold true" in x]
        self.assertEqual(len(views), c["holds"])
        self.assertEqual(len(holds), c["holds"])
        for h, v in zip(holds, views):
            self.assertLess(h, v)
        self.assertLess(next(i for i, x in enumerate(log) if x.startswith("shell am force-stop")), holds[0])
        self.assertEqual([x for x in log[holds[0]:] if "KEYCODE_WAKEUP" in x], [])
        self.assertEqual([x for x in log if "--es screen" in x], [])

    def test_a_warm_up_view_that_is_also_graded_is_refused(self):
        with FakeHA() as ha:
            tmp = tempfile.mkdtemp()
            env, _ = fake_env(tmp, FAKE_HA_URL=ha.url)
            p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart", "--out", tmp,
                                "--url", ha.url, "--warmup-tier", "light"], env=env, capture_output=True, text=True, timeout=60)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("cached the second time", p.stderr)

    def test_no_reference_dashboard_after_the_restart_is_not_measured(self):
        """If the kiosk came back to something else, the restart isn't measured (never engine ready instead)."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_RESTART_PAGE_LOST="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        rep = self.build(tmp)
        run = rep["graded"]["reference"]["per_run"][0]
        self.assertIsNone(run["start_ms"])
        self.assertEqual(run["start_error"], "the kiosk didn't come back to a reference dashboard")
        self.assertIn("start", rep["grade"]["missing"])


class Cameras(unittest.TestCase):
    """#174: the views with streams carry a camera line; a stream that never plays fails the task
    (it was measured: the panel didn't play it), it doesn't make the page "not measured"."""

    def build(self, tmp):
        with open(os.path.join(tmp, "device.json"), "w") as f:
            json.dump({"frame": "f", "mem_total_kb": 1, "display": {"density": "240"}, "app": {"package": "sh.edm.s1kiosk"}}, f)
        return report.build(tmp)

    def test_streams_that_play(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["tiers"]["heavy"]["camera"]["expected"], 4)
        self.assertNotIn("camera", c["tiers"]["light"])
        tiers = self.build(tmp)["graded"]["reference"]["tiers"]
        self.assertEqual((tiers["typical"]["camera_level"], tiers["heavy"]["camera_level"]), ("good", "good"))
        self.assertIsNone(tiers["light"]["camera_level"])
        # Heavy's camera line is longer than a logcat line: it went out in parts and came back whole.
        with open(os.path.join(HERE, "page-lines.json")) as f:
            line_max = json.load(f)["max_line_bytes"]
        heavy = c["tiers"]["heavy"]["camera"]
        self.assertGreater(len(json.dumps({k: v for k, v in heavy.items() if k != "mono"}, separators=(",", ":"))), line_max)
        self.assertEqual((heavy["fetches"]["streams"], len(heavy["streams"])), (4, 4))
        self.assertEqual(tiers["heavy"]["camera_runs"][0]["fetches"]["segment"]["n"], 20)
        # Protocol 6: the graded streams play over WebRTC, which the S1 decodes in software (#202's
        # step 0): no hardware decoder in logcat. The HLS copies' decoders: FullRun.
        self.assertEqual({r["path"] for r in tiers["heavy"]["camera_runs"]}, {"web_rtc"})
        self.assertEqual(c.get("media") or {}, {})
        self.assertIsNone(tiers["heavy"]["camera_runs"][0]["media"])

    def test_a_stream_that_never_plays_fails_the_task(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_NO_VIDEO="heavy")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        rep = self.build(tmp)
        self.assertEqual(rep["graded"]["reference"]["tiers"]["heavy"]["camera_level"], "slow")
        self.assertNotIn("heavy.camera", rep["grade"]["missing"])
        self.assertEqual(rep["grade"]["bands"]["heavy"]["camera"], "slow")


class MoreInfoDialogs(unittest.TestCase):
    """Clarity v3 (#211): each graded view opens HA's more-info dialogs after its swipes, one page line
    each, and the report grades the slowest to settle: a dialog that never opens fails the task (it was
    measured: the panel didn't show it), and a slow history graph slows it, on its own view only."""

    build = Cameras.build

    def test_every_view_opens_its_dialogs(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        for t in ("light", "typical-nocam", "typical", "heavy"):
            ds = c["tiers"][t]["dialogs"]
            self.assertEqual([d["kind"] for d in ds], ["light", "climate", "sensor"], t)
            self.assertTrue(all(d["stage"] == t and d["nonce"] == c["tiers"][t]["nonce"] for d in ds), t)
        rep = self.build(tmp)
        tiers = rep["graded"]["reference"]["tiers"]
        for t in ("light", "typical-nocam", "typical", "heavy"):
            self.assertEqual((tiers[t]["dialog_level"], tiers[t]["dialog_settled_ms"]["median"]), ("premium", 320.0), t)
        self.assertNotIn("light.dialog", rep["grade"]["missing"])

    def test_a_dialog_that_never_opens_fails_the_task(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_DIALOG_NEVER_OPENS="typical")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual({d["error"] for d in c["tiers"]["typical"]["dialogs"]}, {"it never opened within 20 s"})
        rep = self.build(tmp)
        tiers = rep["graded"]["reference"]["tiers"]
        self.assertEqual((tiers["typical"]["dialog_level"], tiers["heavy"]["dialog_level"]), ("slow", "premium"))
        self.assertEqual(rep["grade"]["bands"]["typical"]["dialog"], "slow")
        self.assertNotIn("typical.dialog", rep["grade"]["missing"])
        self.assertIn("light: it never opened within 20 s", tiers["typical"]["dialog_errors"])

    def test_a_view_left_for_the_start_view_keeps_its_results_and_says_so(self):
        """#410 (s1v3a): HA held two of heavy's dialogs past its own wait; the page left heavy for the
        start view (untimed) so they didn't land on the next view. The runner keeps how (detail), the
        report says it, and nothing graded changes."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_SETTLE_VIA="heavy")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        heavy = c["tiers"]["heavy"]
        self.assertEqual((heavy["settle"]["via"], heavy["settle"]["clean"]), ("start", True))
        self.assertEqual(heavy["settle_first"]["pending"], ["climate.clarity_0", "sensor.clarity_10"])
        self.assertEqual(c["tiers"]["light"]["settle"], {"ms": 0, "clean": True, "closed": 0, "pending": [],
                                                         "open": False, "history": False})
        rep = self.build(tmp)
        tiers = rep["graded"]["reference"]["tiers"]
        self.assertEqual(len(tiers["heavy"]["dialog_settles_away"]), 1)
        self.assertEqual(tiers["light"]["dialog_settles_away"], [])
        self.assertEqual((tiers["heavy"]["dialog_level"], tiers["heavy"]["dialog_settled_ms"]["median"]), ("premium", 320.0))
        self.assertIn("heavy: HA still held climate.clarity_0, sensor.clarity_10 after the view's own wait in 1 of 1 run(s)",
                      report.markdown(rep))

    def test_the_warm_ups_settle_is_kept_even_after_the_first_view_opened(self):
        """#410's verifier: the restart's warm-up settles as a view does. Its lines have no nonce and can
        come after the runner opened the first view; they are still the restart's (warmup_settle), and
        the views are measured as usual."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_SETTLE_VIA="warmup")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        ws = c["warmup_settle"]
        self.assertEqual([(w["stage"], w["clean"], w.get("via")) for w in ws], [("warmup", False, None), ("warmup", False, "start")])
        self.assertEqual(ws[-1]["pending"], ["sensor.clarity_10"])
        self.assertTrue(all(c["tiers"][t].get("done") for t in ("light", "typical-nocam", "typical", "heavy")))

    def test_a_view_that_would_start_on_a_held_dialog_is_not_measured(self):
        """#410: HA still held the view before's dialogs at a view's check before its navigation. The page
        logs an `error` for that view (its nonce), and nothing of it is graded in that run."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_DIALOG_HELD="typical")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        typical = c["tiers"]["typical"]
        self.assertIn("this view isn't measured on them", typical["error"])
        self.assertNotIn("done", typical)
        self.assertTrue(all(c["tiers"][t].get("done") for t in ("light", "typical-nocam", "heavy")))   # the run goes on
        rep = self.build(tmp)
        self.assertNotIn("typical", rep["graded"]["reference"]["tiers"])
        for task in ("load", "blocking", "scroll", "updates", "camera", "dialog"):
            self.assertIn(f"typical.{task}", rep["grade"]["missing"])
        self.assertTrue(rep["grade"]["provisional"])                   # not graded on what it didn't measure

    def test_a_slow_history_graph_slows_the_task(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_DIALOG_SLOW_GRAPH="1500")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        sensor = [d for d in c["tiers"]["light"]["dialogs"] if d["kind"] == "sensor"][0]
        self.assertEqual((sensor["graph_ms"], sensor["settled_ms"]), (1500.0, 1500.0))
        tiers = self.build(tmp)["graded"]["reference"]["tiers"]
        self.assertEqual((tiers["light"]["dialog_level"], tiers["light"]["dialog_settled_ms"]["median"]), ("fluid", 1500.0))


class NoisyLogcat(unittest.TestCase):
    """logcat follows the whole device, so its queue is almost never empty. No wait may depend on it."""

    def test_unrelated_lines_twice_a_second(self):
        p, c = refstart(tempfile.mkdtemp(), FAKE_LOGCAT_NOISE="0.5")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(sorted(c["tiers"]), ["heavy", "light", "typical", "typical-nocam"])

    def test_unrelated_lines_twenty_a_second(self):
        p, c = refstart(tempfile.mkdtemp(), FAKE_LOGCAT_NOISE="0.05")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["own_page_state"], "reference")

    def test_the_own_page_wait_with_noise_when_the_kiosk_shows_its_own_dashboard(self):
        """The fallback, when the kiosk wasn't pointed at the fake HA: wait for its own page."""
        p, c = refstart(tempfile.mkdtemp(), FAKE_LOGCAT_NOISE="0.05", FAKE_SAVED_URL="")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["own_page_state"], "finished-still")

    def test_a_reload_more_than_2_s_later_restarts_the_wait_even_with_noise(self):
        p, c = refstart(tempfile.mkdtemp(), FAKE_OWN_RELOAD="1", FAKE_OWN_RELOAD_DELAY="2.6", FAKE_LOGCAT_NOISE="0.05",
                        FAKE_SAVED_URL="")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["own_page_loads"], 2)
        self.assertIsNone(c["error"])
        # The recorded stop is the second load's, and the first reference page opened 2 s after it.
        self.assertGreater(c["own_page_stop_mono"], c["own_page_start_mono"])
        # The first view opens 2 s after the kiosk's own page stopped (it says so in its refdash line:
        # a view opened by navigation loads no page).
        self.assertGreaterEqual(c["tiers"]["light"]["page"]["mono"] - c["own_page_stop_mono"], 2.0)


class ForeignPage(unittest.TestCase):
    """While the reference dashboards are measured, any other page the kiosk loads, before the
    reference page starts or after, makes the run an error: it isn't graded."""

    def run_with(self, where):
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp, FAKE_FOREIGN_PAGE=where, GW_PERF_COLD_SETTLE="0")
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart",
                            "--out", tmp, "--run", "1", "--settle", "0", "--url", HA.url,
                            "--taps", "0"], env=env, capture_output=True, text=True, timeout=120)
        with open(os.path.join(tmp, "refstart-1.json")) as f:
            return p.returncode, json.load(f)

    def test_a_page_before_the_reference_page(self):
        code, c = self.run_with("before")
        self.assertNotEqual(code, 0)
        self.assertEqual(c["error"], "the kiosk loaded another page while a reference dashboard was measured")

    def test_a_page_after_the_last_reference_page(self):
        code, c = self.run_with("end")
        self.assertNotEqual(code, 0)
        self.assertEqual(c["error"], "the kiosk loaded another page while a reference dashboard was measured")

    def test_a_second_load_of_the_kiosks_own_page_restarts_the_wait(self):
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp, FAKE_OWN_RELOAD="1", GW_PERF_COLD_SETTLE="0")
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart",
                            "--out", tmp, "--run", "1", "--settle", "0", "--url", HA.url,
                            "--taps", "0"], env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        with open(os.path.join(tmp, "refstart-1.json")) as f:
            c = json.load(f)
        self.assertEqual(c["own_page_loads"], 2)
        self.assertEqual(c["engine_uri"], "about:blank")
        self.assertIsNone(c["error"])

    def test_a_page_after_the_reference_page(self):
        code, c = self.run_with("after")
        self.assertNotEqual(code, 0)
        self.assertEqual(c["error"], "the kiosk loaded another page while a reference dashboard was measured")


class LiveLayer(unittest.TestCase):
    """After a restart SurfaceFlinger can still list the old layer; frames come from the live one."""

    def test_the_layer_with_the_newest_frame_wins(self):
        sys.path.insert(0, os.path.dirname(HERE))
        import perfrun
        dev = perfrun.Device(None, "sh.edm.s1kiosk", ".MainActivity")
        old = "SurfaceView - sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#0"
        live = "SurfaceView - sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#2"
        lat = {old: "16666666\n1\t5000\t1\n", live: "16666666\n1\t9000\t1\n1\t9016\t1\n"}
        dev.sh = lambda cmd, timeout=60: (f"{old}\nBackground for -{live}\n{live}\n" if cmd.endswith("--list")
                                          else lat[cmd.split("'")[1]])
        self.assertEqual(dev.sf(), (16.666666, [9000, 9016]))


class FailedColdStart(unittest.TestCase):
    def test_the_run_goes_on_and_takes_no_idle_sample_for_it(self):
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp, FAKE_NO_DASHBOARD="1", GW_PERF_COLD_SETTLE="0", GW_PERF_COLD_TIMEOUT="3")
        out = os.path.join(tmp, "out")
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", out, "--skip-synthetic", "--skip-reference"],
                           env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        self.assertIn("cold start 1 failed", p.stderr)
        self.assertFalse(os.path.exists(os.path.join(out, "idle-all-on-1.json")))
        with open(os.path.join(out, "report.json")) as f:
            r = json.load(f)["real_use"]
        self.assertEqual(len(r["errors"]), 1)
        self.assertIsNone(r["app_start_to_dashboard_ms"])
        with open(os.path.join(tmp, "state", "commands.log")) as f:
            shell = [c for c in f.read().splitlines() if c.startswith("shell am ")]
        self.assertEqual(shell[-3:], ["shell am force-stop sh.edm.s1kiosk", "shell am start -n sh.edm.s1kiosk/.MainActivity",
                                      "shell am start -n sh.edm.s1kiosk/.MainActivity --ez test_hold false"])


class NoTestHold(unittest.TestCase):
    def test_a_kiosk_without_the_test_hold_is_refused_before_anything_changes(self):
        """#240: a kiosk that ignores `test_hold` would let the screen sleep partway. No fallback."""
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_NO_TEST_HOLD="1")
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", os.path.join(tmp, "out"), "--port", port(86)], env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 2, p.stderr[-2000:])
        self.assertIn("didn't take Clarity's test hold", p.stderr)
        with open(os.path.join(state, "commands.log")) as f:
            am = [c for c in f.read().splitlines() if c.startswith("shell am ")]
        self.assertEqual([c for c in am if "test_hold" not in c], [])


class ReusedOut(unittest.TestCase):
    def test_a_full_run_refuses_a_directory_with_an_earlier_run(self):
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp)
        out = os.path.join(tmp, "out")
        os.makedirs(out)
        with open(os.path.join(out, "idle-all-on-1.json"), "w") as f:
            f.write("{}")
        p = subprocess.run([RUN, "--frame", "test-frame", "--out", out, "--skip-synthetic"],
                           env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(p.returncode, 2)
        self.assertIn("use a new --out", p.stderr)


class PortInUse(unittest.TestCase):
    def test_a_port_another_fake_holds_is_refused(self):
        """An earlier run's fake left on the port would answer for this run's, with its own code."""
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp)
        port = HA.url.rsplit(":", 1)[1]
        p = subprocess.run([RUN, "--frame", "test-frame", "--out", os.path.join(tmp, "out"), "--port", port, "--skip-synthetic",
                            "--skip-real-use"], env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(p.returncode, 2)
        self.assertIn(f"port {port} is already in use", p.stderr)
        with open(os.path.join(state, "commands.log")) as f:
            self.assertNotIn("--es url", f.read())           # the kiosk was never pointed anywhere


    def test_the_relays_port_held_is_refused(self):
        """#202: the WebRTC relay listens on --port + 1; a fake left there would answer the panel's TURN."""
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp)
        # Both from this worker's block: an ephemeral port's neighbour may be another test's.
        at = port(76)
        held = socket.socket()
        held.bind(("127.0.0.1", int(at) + 1))
        held.listen(1)
        self.addCleanup(held.close)
        p = subprocess.run([RUN, "--frame", "test-frame", "--out", os.path.join(tmp, "out"), "--port", at, "--skip-synthetic",
                            "--skip-real-use"], env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(p.returncode, 2, p.stderr[-800:])
        self.assertIn(f"port {held.getsockname()[1]} (the WebRTC relay's, --port + 1) is already in use", p.stderr)
        with open(os.path.join(state, "commands.log")) as f:
            self.assertNotIn("--es url", f.read())


class CamerasOnlyWhenNeeded(unittest.TestCase):
    """CodeRabbit on cbc2be6: the camera segments (ffmpeg with libx264) are made only when the
    reference dashboards run; the animation test page alone doesn't need them."""

    def test_without_libx264_only_the_reference_part_refuses(self):
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp)
        env.pop("GW_PERF_CAMERA", None)
        env["GW_PERF_CACHE"] = os.path.join(tmp, "cache")
        bindir = os.path.join(tmp, "bin")
        os.makedirs(bindir)
        with open(os.path.join(bindir, "ffmpeg"), "w") as f:
            f.write("#!/bin/sh\necho ' V..... mpeg4  MPEG-4 part 2'\n")   # an ffmpeg without libx264
        os.chmod(os.path.join(bindir, "ffmpeg"), 0o755)
        env["PATH"] = bindir + os.pathsep + env["PATH"]
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", os.path.join(tmp, "a"), "--port", port(81),
                            "--skip-reference", "--skip-real-use"], env=env, capture_output=True, text=True, timeout=180)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", os.path.join(tmp, "b"), "--port", port(81),
                            "--skip-synthetic", "--skip-real-use"],
                           env=env, capture_output=True, text=True, timeout=180)
        self.assertEqual(p.returncode, 2)
        self.assertIn("need ffmpeg with libx264", p.stderr)


class SyntheticScreenOff(unittest.TestCase):
    def test_a_run_the_screen_went_off_in_is_recorded_and_left_out(self):
        """The test page isn't graded, but a run with the screen off isn't the panel's frames: the
        runner writes the screen-off into the run's log and the report leaves the run out."""
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp, FAKE_SYNTH_SCREEN_OFF="1")
        out = os.path.join(tmp, "out")
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", out, "--port", port(87),
                            "--skip-reference", "--skip-real-use"], env=env, capture_output=True, text=True, timeout=180)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        with open(os.path.join(out, "synthetic-1.jsonl")) as f:
            self.assertIn("screen_off", [json.loads(l)["t"] for l in f if l.strip()])
        with open(os.path.join(out, "report.json")) as f:
            s = json.load(f)["detail"]["synthetic"]
        self.assertEqual((s["runs"], s["screen_off_runs"]), (0, 1))
        with open(os.path.join(out, "report.md")) as f:
            self.assertIn("The screen went off in 1 run(s)", f.read())


class SignInStays(unittest.TestCase):
    """The Pixel Tablet's run of 2026-11-18: the runner pointed the saved dashboard at the fake HA, the panel's Home
    Assistant address moved, and since #496 the kiosk asked to sign in again: the graded runs failed, and the panel
    still asked after the run. FAKE_SIGNED_IN: a kiosk signed in to its HA, which moves its sign-in on any new address."""

    def test_a_signed_in_kiosk_is_graded_and_stays_signed_in(self):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_SIGNED_IN="http://192.0.2.10:8123", FAKE_HA_URL=f"http://127.0.0.1:{port(81)}",
                              GW_PERF_COLD_SETTLE="0", GW_PERF_RUN_GAP="0", GW_PERF_BETWEEN_QUIET="0.3")
        out = os.path.join(tmp, "out")
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", out, "--port", port(81), "--taps", "0",
                            "--skip-synthetic"], env=env, capture_output=True, text=True, timeout=300)
        self.assertEqual(p.returncode, 0, p.stderr[-3000:])
        self.assertFalse(os.path.exists(os.path.join(state, "signin_moved.json")), "the sign-in moved")
        self.assertFalse(os.path.exists(os.path.join(state, "saved_url.json")), "the saved dashboard changed")
        with open(os.path.join(out, "report.json")) as f:
            r = json.load(f)
        self.assertEqual(r["graded"]["reference"]["errors"], [])
        self.assertEqual(r["real_use"]["errors"], [])                   # its own dashboard after

    def test_a_kiosk_without_the_test_dashboard_is_refused_before_the_graded_part(self):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_NO_TEST_DASHBOARD="1", FAKE_HA_URL=f"http://127.0.0.1:{port(79)}")
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", os.path.join(tmp, "out"), "--port", port(79),
                            "--skip-synthetic", "--skip-real-use"], env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 2, p.stderr[-2000:])
        self.assertIn("didn't take Clarity's test dashboard", p.stderr)
        with open(os.path.join(state, "commands.log")) as f:
            commands = f.read().splitlines()
        self.assertEqual([c for c in commands if c.startswith(("view ", "shell am force-stop"))], [])
        self.assertEqual([c for c in commands if "--es url" in c], [])


class Android6(unittest.TestCase):
    """The lululemon Mirror's Android 6 (FAKE_ANDROID6, as an API 23 emulator answered on 2026-11-18): logcat has
    no -v monotonic, ps ignores -A -o, there is no nproc, the app's layer is plain `SurfaceView`, and the kiosk is
    the generic app, whose activity (dev.glassworks.MainActivity) sits behind a launcher alias."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        env, cls.state = fake_env(cls.tmp, FAKE_ANDROID6="1", FAKE_HA_URL=f"http://127.0.0.1:{port(83)}", GW_PERF_COLD_SETTLE="0",
                                  GW_PERF_RUN_GAP="0", GW_PERF_BETWEEN_QUIET="0.5")
        cls.out = os.path.join(cls.tmp, "out")
        cls.proc = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", cls.out, "--port", port(83), "--taps", "2",
                                   "--quick"], env=env, capture_output=True, text=True, timeout=300)
        with open(os.path.join(cls.state, "commands.log")) as f:
            cls.commands = f.read().splitlines()
        cls.rep = None
        if cls.proc.returncode == 0:
            with open(os.path.join(cls.out, "report.json")) as f:
                cls.rep = json.load(f)

    def test_the_whole_run_is_graded(self):
        self.assertEqual(self.proc.returncode, 0, self.proc.stderr[-3000:])
        g = self.rep["grade"]
        self.assertEqual(g["grade"], "A+")
        self.assertFalse(g["provisional"], g["missing"])
        ref = self.rep["graded"]["reference"]
        self.assertEqual(ref["errors"], [])
        for t in ("light", "typical-nocam", "typical", "heavy"):
            # The plain SurfaceView layer's frames, cut to windows on the monotonic clock logcat's wall stamps went to.
            self.assertEqual(ref["tiers"][t]["sf_valid"], [True], t)
            self.assertEqual(ref["tiers"][t]["scrolled_px"]["median"], 1400, t)
            self.assertIsNotNone(ref["tiers"][t]["inp_ms"], t)
        ru = self.rep["real_use"]
        self.assertEqual(ru["android_ttid_ms"]["median"], 1714)                  # displayed under the launcher alias
        self.assertEqual(ru["memory"]["idle"]["app_pss_kb"]["median"], 570000)   # meminfo's "kB"
        self.assertEqual(self.rep["frame"]["device"]["cpu"]["cores"], 4)         # no nproc

    def test_its_intents_go_to_the_activity_behind_the_alias(self):
        am = [c for c in self.commands if c.startswith("shell am start")]
        self.assertTrue(am)
        self.assertEqual([c for c in am if "sh.edm.s1kiosk/dev.glassworks.MainActivity" not in c], [])
        # Read once by run.sh, and once by each perfrun process that wasn't told it.
        self.assertEqual(self.commands.count("shell dumpsys activity recents"), 1)

    def test_logcat_is_read_on_its_wall_clock(self):
        streams = [c for c in self.commands if c.startswith("logcat ")]
        self.assertTrue(streams)
        self.assertEqual([c for c in streams if c != "logcat -v usec -T 1"], [])
        self.assertEqual([c for c in self.commands if c.startswith("shell logcat -d -v monotonic") and "-t 1" not in c], [])
        with open(os.path.join(self.out, "refstart-1.json")) as f:
            note = json.load(f)["logcat_clock"]
        self.assertEqual(note["format"], "usec")
        # The fake's pairs are this computer's own clock reads, microseconds apart on the 0.2 clock.
        self.assertLess(note["offset_err_ms"], 50)
        self.assertLess(abs(note["drift_ms"]), 100)


class PaneRamTestDashboard(unittest.TestCase):
    def test_a_kiosk_that_ignores_the_test_dashboard_takes_no_pane_sample(self):
        """CodeRabbit on #623: am says "Status: ok" to a kiosk that ignores the extra, and the pane samples would be of
        its own dashboard. --pane-ram checks the kiosk's log first, as the graded part does."""
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_NO_TEST_DASHBOARD="1")
        out = os.path.join(tmp, "out")
        p = subprocess.run([RUN, "--frame", "aqara-s1-plus", "--runs", "1", "--out", out, "--port", port(77),
                            "--skip-synthetic", "--skip-reference", "--skip-real-use",
                            "--pane-ram", "assist=on,sendspin=on,mic=on,wall-base=on,time-zone=on,diagnostics=on"],
                           env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 2, p.stderr[-2000:])
        self.assertIn("didn't take Clarity's test dashboard", p.stderr)
        self.assertFalse(os.path.exists(os.path.join(state, "intents.json")))       # no pane switched
        self.assertEqual([f for f in os.listdir(out) if f.startswith("idle-")], [])


class Android6ClockStep(unittest.TestCase):
    def test_a_wall_clock_step_during_a_graded_run_fails_it(self):
        """CodeRabbit on #619: on Android 6 the logcat lines carry the wall clock; a step of it moves the windows away
        from SurfaceFlinger's frames. The run fails rather than grade frames it didn't measure."""
        tmp = tempfile.mkdtemp()
        p, r = refstart(tmp, FAKE_ANDROID6="1", FAKE_CLOCK_STEP_S="2")
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("the device's wall clock moved +", r["error"])
        # 2 s, give or take the two calibrations' errors (1999.8 ms in CI, 2026-11-19), and 5 ms of rounding slack.
        c = r["logcat_clock"]
        self.assertAlmostEqual(c["drift_ms"], 2000, delta=c["offset_err_ms"] + c["end_err_ms"] + 5)


class Android6Refusals(unittest.TestCase):
    def test_no_task_to_read_the_activity_from_is_refused_before_anything_changes(self):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_ANDROID6="1", FAKE_NO_TASK="1")
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", os.path.join(tmp, "out"), "--port", port(85)], env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 2, p.stderr[-2000:])
        self.assertIn("give --activity", p.stderr)
        with open(os.path.join(state, "commands.log")) as f:
            self.assertEqual([c for c in f.read().splitlines() if c.startswith("shell am ")], [])

    def test_activity_names_it_and_nothing_is_read(self):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_ANDROID6="1", FAKE_NO_TASK="1")
        p = subprocess.run([RUN, "--frame", "test-frame", "--out", os.path.join(tmp, "out"), "--idle-only", "all-on",
                            "--activity", "dev.glassworks.MainActivity"], env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        with open(os.path.join(state, "commands.log")) as f:
            commands = f.read().splitlines()
        self.assertNotIn("shell dumpsys activity recents", commands)
        self.assertIn("shell am start -n sh.edm.s1kiosk/dev.glassworks.MainActivity --ez test_hold false", commands)

    def test_the_legacy_engines_lockdown_skips_the_animation_page_and_the_run_goes_on(self):
        """#198: the legacy engine loads only its dashboard's origin, and refuses the test page opened on the fake's."""
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_ANDROID6="1", FAKE_LOCKDOWN="1", GW_PERF_COLD_SETTLE="0", GW_PERF_RUN_GAP="0")
        out = os.path.join(tmp, "out")
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "2", "--out", out, "--port", port(87), "--quick",
                            "--skip-reference"], env=env, capture_output=True, text=True, timeout=180)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        self.assertIn("lockdown refused the animation test page", p.stderr)
        self.assertEqual(p.stderr.count("synthetic run "), 2)       # one try, and its refusal: no second run
        self.assertFalse(os.path.exists(os.path.join(out, "idle-after-synthetic-1.json")))
        self.assertTrue(os.path.exists(os.path.join(out, "idle-all-on-1.json")))


class IdleOnly(unittest.TestCase):
    """The pane-diff steps in docs/perf.md, starting from an empty directory."""

    def test_samples_count_up_and_the_pane_diff_appears(self):
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp)
        out = os.path.join(tmp, "panes")
        apk = os.path.join(tmp, "app.apk")
        with zipfile.ZipFile(apk, "w") as z:
            z.writestr("lib/armeabi-v7a/libxul.so", b"y" * 300)
        for label, more in (("all-on", ["--apk", apk]), ("all-on", []), ("sendspin-off", [])):
            p = subprocess.run([RUN, "--frame", "test-frame", "--out", out, "--idle-only", label] + more,
                               env=env, capture_output=True, text=True, timeout=120)
            self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        self.assertTrue(os.path.exists(os.path.join(out, "idle-all-on-0.json")))
        self.assertTrue(os.path.exists(os.path.join(out, "idle-all-on-1.json")))
        with open(os.path.join(out, "report.json")) as f:
            rep = json.load(f)
        panes = {p["id"]: p for p in rep["real_use"]["panes"]}
        self.assertEqual(panes["sendspin"]["samples_on"], 2)
        self.assertEqual(rep["apk"][0]["abis"], ["armeabi-v7a"])   # kept from the first call

    def test_a_sample_the_screen_went_off_in_is_recorded_and_left_out(self):
        """s1v7: the screen went off at the end of an idle window. The sample says so (from logcat,
        only lines after the window began), and the report leaves it out."""
        tmp = tempfile.mkdtemp()
        out = os.path.join(tmp, "panes")
        for label, extra in (("all-on", {}), ("all-on", {"FAKE_IDLE_SCREEN_OFF": "1"})):
            env, _ = fake_env(tmp, **extra)
            p = subprocess.run([RUN, "--frame", "test-frame", "--out", out, "--idle-only", label],
                               env=env, capture_output=True, text=True, timeout=120)
            self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        with open(os.path.join(out, "idle-all-on-0.json")) as f:
            self.assertEqual(json.load(f)["screen_off"], [])
        with open(os.path.join(out, "idle-all-on-1.json")) as f:
            self.assertEqual(len(json.load(f)["screen_off"]), 1)
        with open(os.path.join(out, "report.json")) as f:
            r = json.load(f)["real_use"]
        self.assertEqual((r["idle"]["samples"], r["screen_off_samples"]), (1, ["all-on-1"]))



class PaneRam(unittest.TestCase):
    """#68, --pane-ram: each switchable pane on by itself against every pane off, on the typical
    reference dashboard, with the kiosk's own extras; the panes and the dashboard go back after. The
    fake's Assist adds 30 MB, Sendspin 10 MB, the wall base 3 MB, the time zone 1 MB and diagnostics 2 MB."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        env, cls.state = fake_env(cls.tmp)
        cls.out = os.path.join(cls.tmp, "out")
        cls.proc = subprocess.run([RUN, "--frame", "aqara-s1-plus", "--runs", "2", "--out", cls.out, "--port", port(91),
                                   "--skip-synthetic", "--skip-reference", "--skip-real-use",
                                   "--pane-ram", "assist=on,sendspin=off,mic=on,wall-base=on,time-zone=on,diagnostics=on"],
                                  env=env, capture_output=True, text=True, timeout=300)

    def read(self, name):
        with open(os.path.join(self.state, name)) as f:
            return json.load(f)

    def test_run_succeeds(self):
        self.assertEqual(self.proc.returncode, 0, self.proc.stderr[-3000:])

    def test_each_pane_on_by_itself_then_all_back(self):
        """Per state: the live settings (the other panes' switches, the microphone mute) in an intent
        that doesn't restart the app, then voice_enabled and a restart (s1v7: in one intent, the
        writes were lost; since #67 voice applies live, so the restart is asked for)."""
        def st(on=None, mic_muted="true"):
            flag = lambda pid: "true" if pid == on else "false"
            return [{"mic_muted": mic_muted, "sendspin_enabled": flag("sendspin"), "wall_base_enabled": flag("wall-base"),
                     "time_zone_enabled": flag("time-zone"), "diagnostics_enabled": flag("diagnostics")},
                    {"voice_enabled": flag("assist"), "restart": "true"}]
        cycle = st() + st("assist", mic_muted="false") + st("diagnostics") + st("sendspin") + st("time-zone") + st("wall-base")
        restore = [{"mic_muted": "false", "sendspin_enabled": "false", "wall_base_enabled": "true", "time_zone_enabled": "true",
                    "diagnostics_enabled": "true"}, {"voice_enabled": "true", "restart": "true"}]
        # A warm-up cycle first (#186), then the two recorded ones.
        self.assertEqual(self.read("intents.json"), cycle * 3 + restore)
        self.assertEqual(self.read("settings.json"), {"voice_enabled": "true", "sendspin_enabled": "false", "mic_muted": "false",
                                                      "wall_base_enabled": "true", "time_zone_enabled": "true", "diagnostics_enabled": "true"})   # as given
        self.assertFalse(os.path.exists(os.path.join(self.state, "saved_url.json")))          # never the saved dashboard
        # Checked once before the samples, then with each state's intent (18), and cleared after.
        self.assertEqual(self.read("test_dashes_set.json"), [f"http://127.0.0.1:{port(91)}/clarity-ref/typical"] * 19 + [""])

    def test_the_warm_up_cycle_is_sampled_but_not_reported(self):
        """#186: s1p3's first sample had the GPU process 15 MB low. Cycle 0 runs every state and keeps
        its files in warmup/, which the report doesn't read."""
        self.assertEqual(sorted(os.listdir(os.path.join(self.out, "warmup"))),
                         sorted([f"{k}-ref-{s}-0.json" for k in ("idle", "panes")
                                 for s in ("panes-off", "assist-on", "sendspin-on", "wall-base-on", "time-zone-on", "diagnostics-on")]
                                + ["thermal.tsv"]))
        self.assertFalse([f for f in os.listdir(self.out) if f.endswith("-0.json")])
        with open(os.path.join(self.out, "thermal.tsv")) as f:
            self.assertFalse([l for l in f if l.split("\t")[0].endswith("-0")])         # its heat rows stay in warmup/
        with open(os.path.join(self.out, "report.json")) as f:
            ram = json.load(f)["ram"]
        self.assertEqual({(p["samples_on"], p["samples_off"]) for p in ram["panes"]}, {(2, 2)})

    def test_the_report_gives_each_panes_share(self):
        with open(os.path.join(self.out, "report.json")) as f:
            ram = json.load(f)["ram"]
        panes = {p["id"]: p for p in ram["panes"]}
        self.assertEqual(set(panes), {"assist", "sendspin", "wall-base", "time-zone", "diagnostics"})
        self.assertEqual([panes[k]["pss_kb"] for k in ("assist", "sendspin", "wall-base", "time-zone", "diagnostics")],
                         [30000, 10000, 3000, 1000, 2000])
        self.assertTrue(panes["wall-base"]["verified"])                   # checked against the kiosk's `Pane wall-base on` line
        self.assertEqual(panes["assist"]["by_role_pss_kb"]["main"], 30000)
        self.assertEqual((panes["assist"]["samples_on"], panes["assist"]["samples_off"]), (2, 2))
        self.assertEqual(panes["assist"]["pss_tolerance_kb"], 0)
        self.assertTrue(panes["assist"]["pss_measurable"])
        self.assertEqual(panes["assist"]["method"], "on-alone")
        self.assertTrue(panes["assist"]["verified"])                      # every sample ran what its label says
        self.assertNotIn("off_by", panes["assist"])
        with open(os.path.join(self.out, "panes-ref-assist-on-1.json")) as f:
            self.assertEqual(json.load(f)["state"], {"voice": True, "sendspin": False, "mic": True,
                                                     "pane:wall-base": False, "pane:time-zone": False, "pane:diagnostics": False})
        # Two runs: a share, but no budget verdict (that needs three samples a side).
        self.assertEqual(ram["pane_budget"]["assist"], {"value_kb": 30000, "budget_kb": 49152, "tolerance_kb": 0,
                                                        "measurable": False, "ok": None})
        self.assertEqual({p["id"] for p in ram["panes_not_switched"]}, {"radio", "timers", "bluetooth-proxy", "notifications", "camera"})
        self.assertIsNone(ram["reference_idle"])          # no graded runs here


class PaneRamRuns(unittest.TestCase):
    """--pane-ram runs of their own: what goes back, and what is refused (#68). Apart from PaneRam,
    whose tests share one run, so parallel.py runs each of these on its own."""

    def test_a_pane_setting_it_cant_put_back_is_refused_before_the_device_is_touched(self):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp)
        for spec in ("assist=on", "assist=yes,sendspin=on,mic=on", "radio=on,assist=on,sendspin=on,mic=on", "assist=on,sendspin=on"):
            with self.subTest(spec):
                p = subprocess.run([RUN, "--frame", "aqara-s1-plus", "--out", os.path.join(tmp, "out"), "--skip-synthetic",
                                    "--skip-reference", "--skip-real-use", "--pane-ram", spec],
                                   env=env, capture_output=True, text=True, timeout=60)
                self.assertEqual(p.returncode, 2, p.stderr)
                self.assertIn("--pane-ram", p.stderr)
                self.assertFalse(os.path.exists(os.path.join(state, "intents.json")))

    def test_a_run_that_stops_partway_puts_the_panes_and_the_dashboard_back(self):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_IDLE_FAILS="1")
        p = subprocess.run([RUN, "--frame", "aqara-s1-plus", "--runs", "1", "--out", os.path.join(tmp, "out"), "--port", port(93),
                            "--skip-synthetic", "--skip-reference", "--skip-real-use",
                            "--pane-ram", "assist=off,sendspin=on,mic=on,wall-base=on,time-zone=on,diagnostics=on"],
                           env=env, capture_output=True, text=True, timeout=120)
        self.assertNotEqual(p.returncode, 0)                       # the first idle sample failed
        with open(os.path.join(state, "intents.json")) as f:
            intents = json.load(f)
        self.assertEqual(len(intents), 4)                          # all off (two intents), then the exit trap's two
        self.assertEqual(intents[-2:], [{"mic_muted": "false", "sendspin_enabled": "true", "wall_base_enabled": "true",
                                         "time_zone_enabled": "true", "diagnostics_enabled": "true"},
                                        {"voice_enabled": "false", "restart": "true"}])
        with open(os.path.join(state, "test_dashes_set.json")) as f:
            self.assertEqual(json.load(f)[-1], "")
        self.assertFalse(os.path.exists(os.path.join(state, "saved_url.json")))

    def test_a_kiosk_that_loses_voice_enabled_is_measured_by_its_mute_and_says_so(self):
        """The S1 app 0.4.3 saves voice_enabled asynchronously and exits to restart: the write is lost
        (s1v7). Assist is then off by its microphone mute, which stops the wake word: its running
        cost, labelled as such; the runner tries voice_enabled twice and goes on."""
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_VOICE_LOST="1")
        with open(os.path.join(state, "settings.json"), "w") as f:
            json.dump({"voice_enabled": "true"}, f)                # on, as on the panel in s1v7
        out = os.path.join(tmp, "out")
        p = subprocess.run([RUN, "--frame", "aqara-s1-plus", "--runs", "1", "--out", out, "--port", port(99),
                            "--skip-synthetic", "--skip-reference", "--skip-real-use",
                            "--pane-ram", "assist=on,sendspin=on,mic=on,wall-base=on,time-zone=on,diagnostics=on"],
                           env=env, capture_output=True, text=True, timeout=300)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        with open(os.path.join(out, "panes-ref-panes-off-1.json")) as f:
            off = json.load(f)
        self.assertEqual((off["ok"], off["voice_applied"], off["attempts"]), (True, False, 2))
        self.assertEqual(off["state"], {"voice": True, "sendspin": False, "mic": False,
                                        "pane:wall-base": False, "pane:time-zone": False, "pane:diagnostics": False})
        with open(os.path.join(out, "report.json")) as f:
            panes = {x["id"]: x for x in json.load(f)["ram"]["panes"]}
        self.assertEqual(panes["assist"]["off_by"], "mute")
        self.assertEqual(panes["assist"]["pss_kb"], 30000)
        self.assertIn("voice_enabled didn't take", p.stderr)

    def test_a_refused_restore_url_fails_even_when_the_panes_match(self):
        """The last sample (Sendspin on by itself) already has the panes to put back, so only the
        test dashboard intent's answer can tell that the kiosk is still on the fake: am exits 0 either way."""
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_RESTORE_FAILS="1")
        p = subprocess.run([RUN, "--frame", "aqara-s1-plus", "--runs", "1", "--out", os.path.join(tmp, "out"), "--port", port(85),
                            "--skip-synthetic", "--skip-reference", "--skip-real-use",
                            "--pane-ram", "assist=off,sendspin=on,mic=off,wall-base=on,time-zone=on,diagnostics=on"],
                           env=env, capture_output=True, text=True, timeout=300)
        self.assertEqual(p.returncode, 3, p.stderr[-2000:])
        self.assertIn("COULD NOT put the panes back", p.stderr)
        self.assertIn('"url_taken": false', p.stderr)
        with open(os.path.join(state, "hold_log.json")) as f:
            said = [m for _, m in json.load(f)]
        # The refused clear left it on; the hold's end at exit ended it, as on a kiosk.
        self.assertEqual(said[-2:], ["test hold off (ended)", "test dashboard off (ended)"])

    def test_a_refused_sample_dashboard_takes_no_sample(self):
        """The verifier's note 15: when the kiosk refuses the reference dashboard's intent, the panes
        may still match, but the idle sample wouldn't be on that dashboard. No sample is taken."""
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_REF_URL_FAILS="1")
        out = os.path.join(tmp, "out")
        p = subprocess.run([RUN, "--frame", "aqara-s1-plus", "--runs", "1", "--out", out, "--port", port(83),
                            "--skip-synthetic", "--skip-reference", "--skip-real-use",
                            "--pane-ram", "assist=on,sendspin=on,mic=on,wall-base=on,time-zone=on,diagnostics=on"],
                           env=env, capture_output=True, text=True, timeout=300)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])                 # the restore isn't refused
        self.assertIn("no sample taken", p.stderr)
        self.assertEqual([f for f in os.listdir(out) if f.startswith("idle-ref-")], [])
        with open(os.path.join(out, "panes-ref-panes-off-1.json")) as f:
            self.assertFalse(json.load(f)["url_taken"])


if __name__ == "__main__":
    unittest.main()
