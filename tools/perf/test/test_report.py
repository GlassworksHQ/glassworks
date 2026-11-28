"""Parsers and report assembly on made-up device output (formats copied from Android 10)."""
import json
import shutil
import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import grade as G  # noqa: E402
import perflib as P  # noqa: E402
import statistics  # noqa: E402
import report as R  # noqa: E402
import summary as S  # noqa: E402

with open(os.path.join(os.path.dirname(HERE), "page", "tiers.json")) as _f:
    _TIERS = json.load(_f)


class T:
    DIALOGS = _TIERS["DIALOG"]["entities"]     # the more-info dialogs each view opens (Clarity v3, #211)

PKG = "sh.edm.s1kiosk"

MEMINFO_PID = """\
** MEMINFO in pid 4242 [sh.edm.s1kiosk] **
        TOTAL   197058   136344    32692       30    41280    33711     7569

 App Summary
                       Pss(KB)
                        ------
           Java Heap:     9032
         Native Heap:    24792
                Code:    21972
               Stack:       28
            Graphics:    29916
       Private Other:    83296
              System:    28022

               TOTAL:   197058       TOTAL SWAP PSS:       30
"""

MEMINFO_ALL = """\
Total PSS by process:
    209,946K: sh.edm.s1kiosk:gpu_disable_art_image_ (pid 4300)
    199,998K: sh.edm.s1kiosk (pid 4242 / activities)
    185,648K: sh.edm.s1kiosk:sandboxed_tab_disable_art_image_24 (pid 4301)
     48,428K: sh.edm.s1kiosk:sandboxed_tab_disable_art_image_31 (pid 4302)
      8,331K: sh.edm.s1kiosk:crashhelper_disable_art_image_ (pid 4303)
      2,508K: otbr-agent (pid 900)

Total PSS by OOM adjustment:
"""

SF = "16666666\n0\t0\t0\n100\t1000000000\t990\n200\t1016666666\t1000\n300\t9223372036854775807\t0\n"


class Parsers(unittest.TestCase):
    def test_app_summary(self):
        m = P.parse_meminfo_app(MEMINFO_PID)
        self.assertEqual(m["java_heap_kb"], 9032)
        self.assertEqual(m["graphics_kb"], 29916)
        self.assertEqual(m["total_pss_kb"], 197058)

    def test_pss_by_process_and_roles(self):
        rows = P.parse_meminfo_by_process(MEMINFO_ALL)
        self.assertEqual(len(rows), 6)
        self.assertEqual(rows[1], ("sh.edm.s1kiosk", 4242, 199998))
        roles = [P.process_role(n, PKG) for n, _, _ in rows]
        self.assertEqual(roles, ["gpu", "main", "tab", "tab", "crashhelper", None])

    def test_surfaceflinger_latency_drops_unpresented_rows(self):
        period, presents = P.parse_sf_latency(SF)
        self.assertAlmostEqual(period, 16.666666)
        self.assertEqual(presents, [1000000000, 1016666666])
        self.assertEqual(P.presents_in(presents, 0.999, 1.01), [1000.0])

    def test_logcat_monotonic_and_ttid(self):
        rec = P.parse_logcat_line("  4945.980  1918  1990 I ActivityTaskManager: Displayed sh.edm.s1kiosk/.MainActivity: +1s714ms")
        self.assertEqual(rec["mono"], 4945.98)
        self.assertEqual(P.parse_displayed(rec["msg"], "sh.edm.s1kiosk"), 1714)
        self.assertEqual(P.parse_displayed("Displayed sh.edm.s1kiosk/.MainActivity: +812ms", "sh.edm.s1kiosk"), 812)
        self.assertIsNone(P.parse_displayed("Displayed other/.Main: +812ms", "sh.edm.s1kiosk"))
        self.assertIsNone(P.parse_displayed("Displayed sh.edm.s1kiosk.other/.Main: +812ms", "sh.edm.s1kiosk"))
        # A home app relaunched by the system is displayed under its launcher alias (the generic app's).
        self.assertEqual(P.parse_displayed("Displayed dev.glassworks.generic_android/dev.glassworks.generic.Launcher: +1s127ms",
                                           "dev.glassworks.generic_android"), 1127)
        rec = P.parse_logcat_line('  12.5  4242  4250 I GeckoConsole: GWPERF {"t":"done","run":1}')
        self.assertEqual(P.gwperf_payload(rec["msg"]), {"t": "done", "run": 1})

    def test_android6_meminfo_writes_kb_and_the_legacy_content_process_is_a_tab(self):
        # The API 23 emulator, 2026-11-18: no thousands separator, " kB", and GeckoView 144's content process :tab32.
        rows = P.parse_meminfo_by_process("Total PSS by process:\n    65128 kB: system (pid 1218)\n"
                                          "   187001 kB: dev.glassworks.generic_android.legacy (pid 2689 / activities)\n"
                                          "    90210 kB: dev.glassworks.generic_android.legacy:tab32 (pid 2761)\n\n")
        self.assertEqual(rows[1:], [("dev.glassworks.generic_android.legacy", 2689, 187001),
                                    ("dev.glassworks.generic_android.legacy:tab32", 2761, 90210)])
        self.assertEqual([P.process_role(n, "dev.glassworks.generic_android.legacy") for n, _, _ in rows], [None, "main", "tab"])
        self.assertEqual(P.process_role("x:tabby", "x"), "tabby")

    def test_ps_in_both_formats(self):
        self.assertEqual(P.parse_ps("  PID NAME\n4242 sh.edm.s1kiosk\n 4301 sh.edm.s1kiosk:sandboxed_tab_disable_art_image_24\n"),
                         [(4242, "sh.edm.s1kiosk"), (4301, "sh.edm.s1kiosk:sandboxed_tab_disable_art_image_24")])
        # Android 6's toolbox ps ignores -A -o PID,NAME (the API 23 emulator, 2026-11-18).
        old = ("USER      PID   PPID  VSIZE  RSS   WCHAN              PC  NAME\n"
               "root      1     0     4264   1304  SyS_epoll_ 00004acfc4 S /init\n"
               "u0_a55    2689  1203  1534180 201496 SyS_epoll_ 7f9e3c1a4c S dev.glassworks.generic_android.legacy\n"
               "root      2     0     0      0       kthreadd 0000000000 S kthreadd\n")
        self.assertEqual(P.parse_ps(old), [(1, "/init"), (2689, "dev.glassworks.generic_android.legacy"), (2, "kthreadd")])
        self.assertEqual(P.parse_ps(""), [])


def clock_samples(nonce, pairs):
    """perfrun's Device.wall_clock output and logcat's marker lines, for pairs of (before, wall stamp, after)."""
    sandwich = "".join(f"CLOCKPAIR {i} now at {int(b * 1e9)} nsecs now at {int(a * 1e9)} nsecs\n"
                       for i, (b, _, a) in enumerate(pairs, 1)) + "2026\n"
    marks = "--------- beginning of main\n" + "".join(
        f"{w}  5151  5151 I ClarityClock: {nonce} {i}\n" for i, (_, w, _) in enumerate(pairs, 1))
    return sandwich, marks


def one_read_record(period, presents, m0, end_mono):
    """The record a single read after a window gave before #643 (perfrun.py's read_sf and the synthetic step, as
    they were), kept here word for word: the S1 path must still give exactly this."""
    return {"period_ms": period, "window_ms": [m0 * 1000, end_mono * 1000],
            "oldest_ms": min(presents) / 1e6 if presents else None, "presents_ms": P.presents_in(presents, m0, end_mono)}


class SurfaceFlingerReads(unittest.TestCase):
    """#643: reads during a window, joined by present time, and the preflight's plan."""
    NS = 1_000_000

    def frames(self, a_ms, b_ms):
        return [int(t * self.NS) for t in range(a_ms, b_ms, 16)]

    def test_one_read_gives_the_record_one_read_always_gave(self):
        # The S1's way (SF_POLL_S 0): byte for byte, in JSON, over full, partial and empty histories.
        cases = [(16.666, self.frames(10_000, 12_100), 10.5, 11.6), (16.666, self.frames(11_000, 11_200), 10.5, 11.6),
                 (16.666, [], 10.5, 11.6), (None, [], 1.0, 2.0), (16.666, self.frames(10_000, 12_100), 12.2, 12.4)]
        for period, ts, m0, end in cases:
            self.assertEqual(json.dumps(P.sf_window(period, [ts], m0, end)), json.dumps(one_read_record(period, ts, m0, end)))

    def test_overlapping_reads_reach_back_to_the_first(self):
        # Three reads of a 1 s history during a 2.5 s window, 0.8 s apart: each overlaps the one before.
        reads = [self.frames(9_200, 10_200), self.frames(10_000, 11_000), self.frames(10_800, 11_800)]
        frames, oldest = P.stitch_reads(reads)
        self.assertEqual(oldest, reads[0][0])
        self.assertEqual(frames, sorted(set(reads[0] + reads[1] + reads[2])))
        rec = P.sf_window(16.666, reads, 9.5, 11.7)
        self.assertLess(rec["oldest_ms"], 9500)                  # the window is covered: report.py trusts its gaps
        self.assertEqual(rec["presents_ms"][0], 9504.0)

    def test_a_gap_between_reads_keeps_only_what_follows_it(self):
        # The second read's oldest frame comes after the first's newest: frames between them may be lost.
        reads = [self.frames(9_000, 9_500), self.frames(10_000, 11_000), self.frames(10_500, 11_500)]
        _, oldest = P.stitch_reads(reads)
        self.assertEqual(oldest, reads[1][0])

    def test_a_still_screen_between_reads_is_no_gap(self):
        same = self.frames(9_000, 9_500)
        _, oldest = P.stitch_reads([same, same, same + self.frames(9_500, 9_600)])
        self.assertEqual(oldest, same[0])

    def test_no_frames_in_the_last_read(self):
        self.assertEqual(P.stitch_reads([self.frames(9_000, 9_500), []]), (self.frames(9_000, 9_500), None))
        self.assertEqual(P.stitch_reads([]), ([], None))

    def test_the_plan(self):
        self.assertEqual(P.sf_plan(2116.7, 300), (0, None))        # the S1: 127 frames, one read after each window
        self.assertEqual(P.sf_plan(1800, 900), (0, None))
        self.assertEqual(P.sf_plan(966.7, 171), (P.SF_POLL_MAX_S, None))   # the Pixel Tablet: 59 frames, run 3's p95 read
        self.assertEqual(P.sf_plan(983.3, 171, 342), (P.SF_POLL_MAX_S, None))   # and a listing read: two calls
        poll, why = P.sf_plan(983.3, 171, 600)                           # a listing read slower than half the history
        self.assertIsNone(poll)
        self.assertIn("one read takes 600 ms", why)
        self.assertEqual(P.sf_plan(400, 90), (0.2, None))
        for reach, read in ((90, 10), (966.7, 500)):                      # reads too close, or one too slow
            poll, why = P.sf_plan(reach, read)
            self.assertIsNone(poll)
            self.assertIn(f"reaches back {reach:.0f} ms and one read takes {read:.0f} ms", why)
        self.assertIsNone(P.sf_plan(None, 0)[0])


class SurfaceViewLayers(unittest.TestCase):
    def test_each_androids_naming(self):
        pkg = "dev.glassworks.generic_android"
        s1 = "Display Root#0\nSurfaceView - sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#0\n" \
             "Background for -SurfaceView - sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#0\n"
        self.assertEqual(P.surfaceview_layers(s1, "sh.edm.s1kiosk"), ["SurfaceView - sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#0"])
        s = (f"SurfaceView[{pkg}/dev.glassworks.MainActivity]#1052\nSurfaceView[{pkg}/dev.glassworks.MainActivity](BLAST)#1053\n"
             f"Background for SurfaceView[{pkg}/dev.glassworks.MainActivity]#1054\nSurfaceView[com.other/.Main]#9\n")
        self.assertEqual(P.surfaceview_layers(s, pkg), [f"SurfaceView[{pkg}/dev.glassworks.MainActivity]#1052",
                                                        f"SurfaceView[{pkg}/dev.glassworks.MainActivity](BLAST)#1053"])
        m = f"DimLayer\ncom.android.systemui.ImageWallpaper\nSurfaceView\n{pkg}.legacy/dev.glassworks.MainActivity\nStatusBar\n"
        self.assertEqual(P.surfaceview_layers(m, pkg + ".legacy"), ["SurfaceView"])
        # Plain SurfaceView with another app on screen: not ours.
        self.assertEqual(P.surfaceview_layers("SurfaceView\ncom.android.launcher3/com.android.launcher3.Launcher\n", pkg), [])

    def test_android_15_and_later_wrap_each_layer(self):
        # The lines API 35 and 37 emulators listed for the generic app with GeckoView on screen (2026-11-19).
        # Before the fix the runner named no layer and never asked `--latency`, so every window had no frames
        # (the Pixel Tablet's Android 17 run). The names come back whole: `--latency` takes nothing else.
        pkg = "dev.glassworks.generic_android"
        act = f"{pkg}/dev.glassworks.MainActivity"
        api35 = (
            "RequestedLayerState{Display 0 name=\"Built-in Screen\"#41}\n"
            f"RequestedLayerState{{Background for SurfaceView[{act}]#170 parentId=168 relativeParentId=164 z=-2147483648}}\n"
            f"RequestedLayerState{{SurfaceView[{act}](BLAST)#169 parentId=168}}\n"
            f"RequestedLayerState{{178b495 ActivityRecordInputSink {act}#125 parentId=121 !handle z=-2147483648}}\n"
            f"RequestedLayerState{{ActivityRecord{{4f16477 u0 {act}#121}}\n"
            f"RequestedLayerState{{SurfaceView[{act}]#168 parentId=167 relativeParentId=164 z=-2}}\n"
            "RequestedLayerState{SurfaceView[com.other/.Main](BLAST)#9 parentId=8}\n")
        self.assertEqual(P.surfaceview_layers(api35, pkg), [f"SurfaceView[{act}](BLAST)#169", f"SurfaceView[{act}]#168"])
        api37 = (
            f"RequestedLayerState{{Background for f5fea55 SurfaceView[{act}]#164 parentId=162 relativeParentId=158 z=-2147483648}}\n"
            f"RequestedLayerState{{f5fea55 SurfaceView[{act}](BLAST)#163 parentId=162}}\n"
            f"RequestedLayerState{{ActivityRecord{{104391098 u0 {act} t10}}#147 parentId=146}}\n"
            f"RequestedLayerState{{23cb354 {act}#155 parentId=147}}\n"
            f"RequestedLayerState{{f5fea55 SurfaceView[{act}]#162 parentId=161 relativeParentId=158 z=-2}}\n"
            "RequestedLayerState{9a6f5b2 SurfaceView[com.other/.Main](BLAST)#9 parentId=8}\n")
        self.assertEqual(P.surfaceview_layers(api37, pkg), [f"f5fea55 SurfaceView[{act}](BLAST)#163", f"f5fea55 SurfaceView[{act}]#162"])
        self.assertEqual(P.layer_number(f"f5fea55 SurfaceView[{act}](BLAST)#163"), 163)

    def test_vitro_window_on_android_15_and_later_is_its_vri_layer(self):
        # Vitro draws into the app's own window. Verified 2026-11-26 on the Pixel Tablet, Android 17 (API 37): the
        # frames are on the ViewRootImpl's BLAST layer, `VRI-<pkg>/<activity>#n` (WindowManager logs `setClientSurface
        # Surface(name=VRI-…)`); the window's container, `<hash> <pkg>/<activity>#n`, has none. Before the fix the
        # runner took only the container: open, live updates and restart were "not measured" on every page
        # (frames/google-pixel-tablet/research/window-layers.md).
        pkg = "dev.glassworks.generic_android"
        act = f"{pkg}/dev.glassworks.generic.Launcher"
        api37 = (
            "RequestedLayerState{Display 0 name=\"Built-in Screen\"#41}\n"
            f"RequestedLayerState{{ActivityRecord{{104391098 u0 {act} t10}}#6405 parentId=146}}\n"
            f"RequestedLayerState{{ac7b2d9 {act}#6407 parentId=6405}}\n"
            f"RequestedLayerState{{VRI-{act}#6408 parentId=6407}}\n"
            f"RequestedLayerState{{VRI-Splash Screen {pkg}#6402 parentId=6401}}\n"
            f"RequestedLayerState{{Background for f5fea55 SurfaceView[{act}]#6412 parentId=6410 z=-2147483648}}\n"
            f"RequestedLayerState{{f5fea55 SurfaceView[{act}](BLAST)#6411 parentId=6410}}\n"
            f"RequestedLayerState{{f5fea55 SurfaceView[{act}]#6410 parentId=6408 z=-2}}\n"
            f"RequestedLayerState{{VRI-{pkg}.legacy/dev.glassworks.MainActivity#77 parentId=76}}\n"
            "RequestedLayerState{VRI-com.android.settings/com.android.settings.SubSettings#6355 parentId=6354}\n"
            "RequestedLayerState{VRI-NotificationShade#88 parentId=87}\n")
        self.assertEqual(P.window_layers(api37, pkg), [f"ac7b2d9 {act}#6407", f"VRI-{act}#6408"])
        self.assertEqual(P.app_layers(api37, pkg, "vitro"), [f"ac7b2d9 {act}#6407", f"VRI-{act}#6408"])
        # The runner reads the newest layer first (sf_relist): the VRI layer, a child created after its container.
        self.assertEqual(max(P.window_layers(api37, pkg), key=P.layer_number), f"VRI-{act}#6408")
        # The web engine's frames stay GeckoView's SurfaceView on the same listing: nothing in the window.
        self.assertEqual(P.app_layers(api37, pkg, "web"), [f"f5fea55 SurfaceView[{act}](BLAST)#6411", f"f5fea55 SurfaceView[{act}]#6410"])

    def test_vitro_window_on_older_androids_is_unchanged(self):
        # The S1 (Android 10, 2026-11-24's probe): the window's one layer, never its SurfaceView.
        s1 = ("Display Root#0\nSurfaceView - sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#0\n"
              "sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#0\nBackground for -SurfaceView - sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#0\n")
        self.assertEqual(P.window_layers(s1, "sh.edm.s1kiosk"), ["sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#0"])
        self.assertEqual(P.app_layers(s1, "sh.edm.s1kiosk", "web"), ["SurfaceView - sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#0"])
        # Android 12–14: the window and its BLAST child, both in the app's name.
        a12 = "p.q/p.q.A#5\np.q/p.q.A(BLAST)#6\nSurfaceView[p.q/p.q.A]#7\n"
        self.assertEqual(P.window_layers(a12, "p.q"), ["p.q/p.q.A#5", "p.q/p.q.A(BLAST)#6"])
        # Android 6: the bare window name.
        self.assertEqual(P.window_layers("DimLayer\nSurfaceView\np.q/p.q.A\nStatusBar\n", "p.q"), ["p.q/p.q.A"])

    def test_layer_names(self):
        self.assertEqual(P.sf_layer_name("RequestedLayerState{SurfaceView[a/b](BLAST)#169 parentId=168}"), "SurfaceView[a/b](BLAST)#169")
        self.assertEqual(P.sf_layer_name("RequestedLayerState{Task=187#582}"), "Task=187#582")
        self.assertEqual(P.sf_layer_name("RequestedLayerState{ImeContainer#10 parentId=109 relativeParentId=124 z=1}"), "ImeContainer#10")
        self.assertEqual(P.sf_layer_name("RequestedLayerState{x#5 parentId=4 !handle z=-2147483648 layerStack=1}"), "x#5")
        # A name with a space, braces or a # of its own keeps them; only the fields after it go.
        self.assertEqual(P.sf_layer_name("RequestedLayerState{ActivityRecord{1 u0 p/a t10}#147 parentId=146}"),
                         "ActivityRecord{1 u0 p/a t10}#147")
        self.assertEqual(P.sf_layer_name("RequestedLayerState{Leaf:0:1#5 parentId=4}"), "Leaf:0:1#5")
        # A line with no #<id> (as the root copied one off the tablet): the name is what comes before the fields.
        self.assertEqual(P.sf_layer_name("RequestedLayerState{abc1d8b SurfaceView[p/a](BLAST) parentId=826}"),
                         "abc1d8b SurfaceView[p/a](BLAST)")
        # Older Androids print the bare name.
        self.assertEqual(P.sf_layer_name("  SurfaceView - p/p.A#0 "), "SurfaceView - p/p.A#0")


class ScreenSize(unittest.TestCase):
    def test_the_size_taps_use_is_the_turned_one(self):
        # The Pixel Tablet in landscape: wm size is the panel's own, portrait.
        self.assertEqual(P.current_size("Display: mDisplayId=0\n  init=1600x2560 320dpi cur=2560x1600 app=2560x1600 rng=1600x1480-2560x2440\n"),
                         (2560, 1600))
        self.assertEqual(P.current_size("  init=1080x1920 160dpi cur=1080x1920 app=1080x1872 rng=1080x1008-1872x1872\n"), (1080, 1920))
        self.assertIsNone(P.current_size(""))
        self.assertEqual(P.wm_size("Physical size: 1600x2560\nOverride size: 1200x1920\n"), (1200, 1920))
        self.assertIsNone(P.wm_size(""))


class LogClock(unittest.TestCase):
    """Android 6's logcat has no -v monotonic: its µs wall-clock stamps go onto the device's monotonic clock."""

    def test_monotonic_is_the_parser_it_was(self):
        line = "  4945.980  1918  1990 I ActivityTaskManager: Displayed sh.edm.s1kiosk/.MainActivity: +1s714ms"
        self.assertEqual(P.LogClock().parse(line), P.parse_logcat_line(line))
        self.assertIsNone(P.LogClock().parse("11-18 23:06:20.726229  1656  1656 D Tag: message"))

    def test_the_tightest_pair_sets_the_offset_and_its_error(self):
        # Monotonic 100.000 s is wall 11-18 23:06:20.000000; pair 2 is the tightest (2 ms wide).
        sandwich, marks = clock_samples("ab12", [(99.990, "11-18 23:06:20.000000", 100.020),
                                                 (100.199, "11-18 23:06:20.200000", 100.201),
                                                 (100.390, "11-18 23:06:20.400000", 100.420)])
        clock = P.wall_offset(sandwich, marks, "ab12", 2026)
        self.assertEqual((clock.fmt, clock.err_ms), ("usec", 1.0))
        rec = clock.parse("11-18 23:06:21.500250  2689  2689 I Glassworks.Screen: test hold on for 1800 s")
        self.assertAlmostEqual(rec["mono"], 101.50025, places=5)
        self.assertEqual((rec["pid"], rec["level"], rec["tag"], rec["msg"]),
                         (2689, "I", "Glassworks.Screen", "test hold on for 1800 s"))
        # GeckoView's console lines carry ": " in their message: only the first splits the tag off.
        rec = clock.parse('11-18 23:06:21.000000  2761  2790 I GeckoConsole: GWPERF {"t":"done","run":1}')
        self.assertEqual(P.gwperf_payload(rec["msg"]), {"t": "done", "run": 1})

    def test_only_this_runs_marks_count(self):
        sandwich, marks = clock_samples("ab12", [(10.0, "11-18 23:06:20.000000", 10.004)])
        stale = "11-18 22:00:00.000000  5151  5151 I ClarityClock: ffff 1\n"
        clock = P.wall_offset(sandwich, stale + marks, "ab12", 2026)
        self.assertEqual(clock.err_ms, 2.0)
        # The marker's stamp goes at the pair's middle: 10.002 s.
        self.assertAlmostEqual(clock.parse("11-18 23:06:20.000000  1  1 I x: y")["mono"], 10.002, places=5)
        self.assertIsNone(P.wall_offset(sandwich, stale, "ab12", 2026))
        self.assertIsNone(P.wall_offset("", marks, "ab12", 2026))         # /proc/timer_list unreadable

    def test_a_wall_clock_step_during_a_run_is_caught(self):
        self.assertFalse(P.clock_stepped(None))                                   # monotonic logcat
        # The emulator's (2026-11-18): within both errors and the tolerance.
        self.assertFalse(P.clock_stepped({"format": "usec", "offset_err_ms": 0.763, "end_err_ms": 1.896, "drift_ms": -0.132}))
        self.assertFalse(P.clock_stepped({"format": "usec", "offset_err_ms": 1.0, "end_err_ms": 1.0, "drift_ms": 6.9}))
        self.assertTrue(P.clock_stepped({"format": "usec", "offset_err_ms": 1.0, "end_err_ms": 1.0, "drift_ms": 7.1}))
        self.assertTrue(P.clock_stepped({"format": "usec", "offset_err_ms": 1.0, "end_err_ms": 1.0, "drift_ms": -3600000.0}))

    def test_a_run_over_new_year_stays_in_order(self):
        sandwich, marks = clock_samples("ab12", [(499.999, "12-31 23:59:59.000000", 500.001)])
        clock = P.wall_offset(sandwich, marks, "ab12", 2026)
        self.assertAlmostEqual(clock.parse("01-01 00:00:01.000000  1  1 I x: y")["mono"], 502.0, places=5)
        self.assertAlmostEqual(clock.parse("12-31 23:59:58.000000  1  1 I x: y")["mono"], 499.0, places=5)

    def test_pid_stat_with_spaces_in_comm(self):
        line = "4242 (Gecko Main) S 1 1 0 0 -1 1 2 3 4 5 470 15 1 1 10 -10"
        self.assertEqual(P.parse_pid_jiffies(line), 485)

    def test_cpu_percent(self):
        before = {"total": (100, 1000), "procs": {"app": 10}}
        after = {"total": (300, 2000), "procs": {"app": 60}}
        cpu = P.cpu_percent(before, after, ncpu=4)
        self.assertEqual(cpu["device_busy_pct"], 20.0)
        self.assertEqual(cpu["procs_pct_of_core"]["app"], 20.0)


def frames(start, n, every):
    return [start + every * i for i in range(n)]


class WorseOfBoth(unittest.TestCase):
    """Shapes from the S1 smoke run (2026-11-12): rAF and SurfaceFlinger disagree both ways."""

    def stepline(self, raf_every, raf_n, sf_every, sf_n, oldest=9_000):
        raf = P.frame_stats([raf_every] * raf_n, 16.667)
        return {"raf": raf, "sf": {"period_ms": 16.667, "window_ms": [10_000, 12_000], "oldest_ms": oldest,
                                   "presents_ms": frames(10_005, sf_n, sf_every)}}

    def test_compositor_drops_frames_the_main_thread_never_sees(self):
        f = R.step_frames(self.stepline(16.667, 118, 31.25, 64))  # css-transform n=128
        self.assertTrue(f["sf_valid"])
        self.assertEqual((f["source"], f["limited_by"]), ("surfaceflinger", "compositor"))
        self.assertEqual(f["stats"]["n"], 64)   # 63 gaps, plus the 26 ms one at the window's end

    def test_main_thread_jank_while_the_compositor_still_animates(self):
        f = R.step_frames(self.stepline(50.0, 40, 33.0, 61))  # reference load
        self.assertEqual((f["source"], f["limited_by"]), ("raf", "main-thread"))

    def test_a_stall_at_the_edge_of_the_window_counts(self):
        f = R.step_frames(self.stepline(16.667, 120, 16.667, 40))  # frames stop 650 ms into a 2 s window
        self.assertTrue(f["sf_valid"])
        self.assertEqual((f["source"], f["limited_by"]), ("surfaceflinger", "compositor"))
        self.assertGreater(f["stats"]["frozen_pct"], 0)   # the 1.35 s with nothing on screen

    def test_a_still_screen_during_an_animation_is_one_long_frame(self):
        f = R.step_frames(self.stepline(16.667, 120, 16.667, 0))
        self.assertTrue(f["sf_valid"])
        self.assertEqual(f["sf"]["max_ms"], 2000)

    def test_evicted_history_falls_back_to_raf(self):
        f = R.step_frames(self.stepline(16.667, 120, 16.667, 110, oldest=10_400))
        self.assertFalse(f["sf_valid"])
        self.assertEqual((f["source"], f["limited_by"]), ("raf", None))

    def test_equal_views_say_so(self):
        f = R.step_frames(self.stepline(16.667, 120, 16.667, 120))
        self.assertEqual((f["source"], f["limited_by"]), ("surfaceflinger", "equal"))


class StartOnTheS1(unittest.TestCase):
    """The S1 smoke run's cold start (2026-11-12): the system relaunched the home-app kiosk."""

    def test_start_and_load_split_the_whole_wait(self):
        stop = 12741.766
        c = {"launch": "relaunched-by-system", "ttid_ms": 111, "stop_mono": stop, "proc_mono": 12741.99,
             "displayed_mono": 12742.161, "page_start_mono": 12746.225, "page_stop_mono": 12752.024, "ended_quiet": True,
             "presents_mono_ms": [stop * 1000 + d for d in (10336, 10353, 10619, 10669, 10819, 10902, 10952, 11202, 11235, 11518, 14115)]}
        r = R.coldstart_run(c)
        self.assertEqual(r["ttid_ms"], 111)              # MainActivity's loading screen
        self.assertEqual(r["app_start_to_dashboard_ms"], 4235)   # process start to the dashboard's PageStart
        self.assertEqual(r["dashboard_load_ms"], 7059)
        self.assertEqual(r["stop_to_dashboard_ms"], 11518)
        self.assertEqual(r["app_start_to_dashboard_ms"] + r["dashboard_load_ms"], round((stop * 1000 + 11518) - 12741.99 * 1000))


class LiveCheck(unittest.TestCase):
    """The updates window counts only when what changes, and the toggle, are on screen."""

    def run_with(self, measure):
        run = refstart(1, 100)
        if measure is None:
            del run["tiers"]["typical"]["measure"]
        else:
            run["tiers"]["typical"]["measure"].update(measure)
        return R.reference_run(run)["tiers"]["typical"]

    def test_without_a_live_check_updates_are_not_measured(self):
        r = self.run_with(None)
        self.assertIsNone(r["updates"])
        self.assertEqual(r["updates_error"], "no live check (a page from before it was added)")
        self.assertEqual(r["inp_ms"], 100)   # the taps still count: nothing says the toggle was off screen

    def test_off_screen_voids_updates_and_taps(self):
        r = self.run_with({"offscreen": 3, "moving": 12})
        self.assertIsNone(r["updates"])
        self.assertIsNone(r["inp_ms"])
        self.assertIn("off screen (3 of 12)", r["updates_error"])

    def test_the_flag_not_the_sentence_voids_the_taps(self):
        self.assertEqual(R.live_check_failed({"offscreen": 1, "moving": 5})[1], True)
        self.assertEqual(R.live_check_failed({})[1], False)
        self.assertEqual(R.live_check_failed({"offscreen": 0, "moving": 5, "targets": 4}), (None, False))

    def test_no_targets_is_not_a_live_page(self):
        self.assertEqual(R.live_check_failed({"offscreen": 0, "moving": 2, "targets": 0}),
                         ("nothing on the page to update (0 targets)", False))

    def test_the_updates_must_have_run(self):
        """1667 ms at one update per 200 ms holds 8; one short is the window's edge, two short isn't."""
        check = {"offscreen": 0, "moving": 12, "targets": 12, "update_every_ms": 200}
        few = [c for c in CHANGES if c[0] not in (400.0, 800.0)]
        self.assertEqual(R.live_check_failed(check, updates_step(30.0, changes=CHANGES)), (None, False))
        self.assertEqual(R.live_check_failed(check, updates_step(30.0, changes=CHANGES[1:])), (None, False))
        self.assertEqual(R.live_check_failed(check, updates_step(30.0, changes=few)),
                         ("only 6 of 8 state updates ran in the window", False))
        clock_only = [c for c in CHANGES if c[3] == 1]
        self.assertEqual(R.live_check_failed(check, updates_step(30.0, changes=clock_only))[0],
                         "only 0 of 8 state updates ran in the window")


class ChangeFrames(unittest.TestCase):
    """Live updates are graded on the frames that had a change to draw."""

    def test_idle_frames_do_not_count(self):
        """smoke5 asked whether a light page's long rAF gaps were 'nothing to draw'. Slow frames before
        the first change don't count; the same number of slow frames during changes does."""
        idle = R.change_frames(updates_step(30.0, idle_slow=6), 16.667)[0]
        busy = R.change_frames(updates_step(30.0, missed=6), 16.667)[0]
        self.assertEqual(idle["jank_pct"], 0)
        self.assertGreater(busy["jank_pct"], 10)
        self.assertEqual(G.frame_band(idle), "good")
        self.assertNotEqual(G.frame_band(busy), "good")

    def test_each_transition_frame_counts(self):
        """A tile's 180 ms transition is about 11 frames, a text change one."""
        st, _ = R.change_frames(updates_step(30.0, changes=[[200.0, 200.5, 180]]), 16.667)
        self.assertIn(st["n"], (11, 12, 13))
        st, _ = R.change_frames(updates_step(30.0, changes=[[200.0, 200.5, 0]]), 16.667)
        self.assertEqual(st["n"], 1)

    def test_a_change_that_ran_late_counts_against_the_page(self):
        """Due at 200 ms, run at 290 ms: a busy main thread held it back. The long frame it was due in counts."""
        ts = [0.0, 16.7, 33.4, 183.4, 300.0, 316.7]
        st, _ = R.change_frames({"raf_times": ts, "changes": [[200.0, 290.0, 0]]}, 16.667)
        self.assertEqual(st["max_ms"], 116.6)
        self.assertEqual(G.frame_band(st), "poor")

    def test_without_a_change_log_updates_are_not_measured(self):
        self.assertEqual(R.change_frames({"raf": {"n": 99}}), (None, "no change log (a page from before it was added)"))
        self.assertEqual(R.change_frames({"raf_times": [0, 16.7], "changes": []})[1], "nothing changed during the window")

    def test_pacing_is_detail_and_change_to_screen_is_graded(self):
        r = R.reference_run(refstart(1, 100))["tiers"]["light"]
        self.assertGreater(r["updates_all"]["n"], 0)
        self.assertEqual(r["latency"]["n"], 9)
        self.assertLess(r["latency"]["p95_ms"], 60)
        self.assertEqual(G.update_level(r["updates_run"]), "premium")


PAGE = {"mono": 31.0, "epoch_ms": 1e12 + 1000, "origin_epoch_ms": 1e12}   # page time x -> mono 30000 + x


def latency_step(changes, presents_rel, raf_rel=None, oldest_rel=-500, window=4000):
    """An updates step at page time t0 = 4000 (mono 34000): changes [due, ran, d, kind, tick] and
    SurfaceFlinger presents, both in ms after t0."""
    w0 = 34000
    return {"t0": 4000.0, "window_ms": window, "changes": changes, "raf_times": raf_rel or [],
            "sf": {"period_ms": 16.667, "window_ms": [w0, w0 + window], "oldest_ms": w0 + oldest_rel,
                   "presents_ms": [w0 + x for x in presents_rel]}}


class ChangeToScreen(unittest.TestCase):
    """Rubric v5: from when the page applies a change to the first frame on screen that can show it."""

    def lat(self, *a, **k):
        return R.change_to_screen(latency_step(*a, **k), PAGE)

    def test_on_a_still_screen_the_next_frame_shows_it(self):
        """smoke6/s1v4: a tile fade from a still screen reached the screen 59 to 92 ms later."""
        st, why = self.lat([[100.0, 101.0, 180, 2, 108.0]], [175.0, 192.0])
        self.assertIsNone(why)
        self.assertEqual((st["n"], st["p95_ms"]), (1, 74.0))

    def test_a_frame_already_on_its_way_doesnt_count(self):
        """During a fade the next present can come 2 to 10 ms after the change: a frame drawn before
        it. The change is drawn at the next tick (+39) and can show one refresh later at the earliest."""
        st, _ = self.lat([[100.0, 100.0, 0, 0, 139.0]], [105.0, 139.0, 155.0, 172.0])
        self.assertEqual(st["p95_ms"], 55.0)

    def test_a_frame_started_before_the_change_ran_can_carry_it(self):
        """The timer ran inside the frame begun at +98, before its paint: that frame shows it."""
        st, _ = self.lat([[100.0, 101.0, 0, 0, 98.0]], [116.0, 133.0])
        self.assertEqual(st["p95_ms"], 15.0)

    def test_no_frame_for_250_ms_counts_as_at_least_that_late(self):
        st, _ = self.lat([[100.0, 100.0, 0, 0, 110.0], [3000.0, 3000.0, 0, 0, 3010.0]], [130.0], window=4000)
        self.assertEqual(sorted([st["median_ms"], st["p95_ms"]]), [30.0, 1000.0])
        self.assertEqual(G.update_level({"latency_n": st["n"], "latency_p95_ms": st["p95_ms"]}), "slow")

    def test_a_change_too_close_to_the_end_is_not_counted(self):
        st, _ = self.lat([[100.0, 100.0, 0, 0, 110.0], [3900.0, 3900.0, 0, 0, 3910.0]], [130.0], window=4000)
        self.assertEqual((st["n"], st["skipped"]), (1, 1))

    def test_a_change_older_than_surfaceflingers_history_is_not_counted(self):
        why = self.lat([[100.0, 100.0, 0, 0, 110.0], [200.0, 200.0, 0, 0, 210.0], [300.0, 300.0, 0, 0, 310.0]],
                       [330.0], oldest_rel=250)[1]
        self.assertEqual(why, "SurfaceFlinger covered only 1 of 3 changes")

    def test_protocol_1_pages_take_the_frame_from_the_raf_times(self):
        """Their changes have no tick; the next rAF frame after the change is the one it's drawn in."""
        st, _ = self.lat([[100.0, 101.0, 0, 0]], [140.0, 156.0], raf_rel=[90.0, 106.7, 123.4])
        self.assertEqual(st["p95_ms"], 39.0)

    def test_not_measured_without_a_change_log(self):
        self.assertEqual(R.change_to_screen({"raf": {}}, PAGE)[1], "no change log (a page from before it was added)")


class ReferenceLoad(unittest.TestCase):
    """The graded load is a number only when it could be measured; never 0 by default."""

    def test_settles_on_the_last_paint_before_a_quiet_gap(self):
        self.assertEqual(R.settle_before([100, 200, 400], 150, 3000), (400, None))

    def test_not_before_the_load_event(self):
        self.assertEqual(R.settle_before([100, 200], 1200, 3000), (1200, None))

    def test_a_pause_before_the_load_event_is_mid_load(self):
        self.assertEqual(R.settle_before([100, 1000, 1100], 1050, 3000), (1100, None))

    def test_no_frames_is_not_measured(self):
        self.assertEqual(R.settle_before([], 120, 3000), (None, "no frames on screen while it loaded"))

    def test_never_quiet_is_not_measured(self):
        self.assertEqual(R.settle_before([100 * i for i in range(30)], 120, 3000)[0], None)

    def run_with(self, **page):
        run = refstart(1, 100)
        del run["tiers"]["light"]["page"]["usable"]       # a page before protocol 4: its own load event
        run["tiers"]["light"]["page"].update(page)
        return R.reference_run(run)["tiers"]["light"]

    def test_no_load_event_is_not_measured(self):
        r = self.run_with(load_ms=None)
        self.assertNotIn("load_ms", r)
        self.assertEqual(r["load_error"], "no load event or first paint reported")

    def test_no_frames_on_screen_is_not_measured(self):
        run = refstart(1, 100)
        run["presents_mono_ms"] = []
        r = R.reference_run(run)["tiers"]["light"]
        self.assertNotIn("load_ms", r)

    def test_no_frame_after_the_first_paint_is_not_measured(self):
        r = self.run_with(fcp_ms=900)   # first paint after the last frame (400 ms)
        self.assertEqual(r["load_error"], "no frame on screen after its first paint")

    def with_frames(self, fcp_ms, load_ms, rel_frames):
        run = refstart(1, 100)
        del run["tiers"]["light"]["page"]["usable"]
        run["tiers"]["light"]["page"].update(fcp_ms=fcp_ms, load_ms=load_ms)
        run["presents_mono_ms"] = [NAV["light"] * 1000 + d for d in rel_frames]
        return R.reference_run(run)["tiers"]["light"]

    def test_a_quiet_stretch_before_the_first_paint_reaches_the_screen_is_mid_load(self):
        """smoke5's light page, first after the kiosk's own dashboard: two frames of the old page, a
        second of nothing, then its first paint on screen 350 ms after the page's own first paint
        (907 ms), and frames until 1.75 s. The old rule found the quiet before the first paint and
        failed; it may not settle there."""
        burst = [1255 + 16.7 * i for i in range(30)]          # 1255 .. 1739
        r = self.with_frames(907, 896, [156, 256] + burst)
        self.assertEqual(r["load_ms"], 1739)
        self.assertEqual(r["paint_to_screen_ms"], 348)
        self.assertNotIn("load_error", r)

    def test_it_never_settles_at_the_load_event_before_the_first_paint_is_on_screen(self):
        """smoke5's heavy page: the old rule settled at its load event (1357 ms) while its first paint
        (1279 ms) reached the screen at 1360 ms and painted on until 2126 ms."""
        burst = [1360, 1410, 1427, 1444, 1477] + [1760 + 16.7 * i for i in range(23)]   # .. 2127
        r = self.with_frames(1279, 1357, [262] + burst)
        self.assertEqual(r["load_ms"], 2127)

    def test_the_first_paint_frame_may_be_stamped_a_little_early(self):
        """The clock mapping is a few ms off; a frame 30 ms before the mapped first paint shows it,
        but the load is never earlier than the first paint."""
        r = self.with_frames(400, 300, [370])
        self.assertEqual(r["load_ms"], 400)
        self.assertEqual(r["paint_to_screen_ms"], -30)
        self.assertEqual(self.with_frames(400, 300, [340])["load_error"], "no frame on screen after its first paint")

    def test_not_measured_load_leaves_the_grade_provisional(self):
        d = tempfile.mkdtemp()
        with open(os.path.join(d, "device.json"), "w") as f:
            json.dump({"frame": "f", "mem_total_kb": 1, "app": {"package": PKG}}, f)
        run = refstart(1, 100)
        run["presents_mono_ms"] = []
        with open(os.path.join(d, "refstart-1.json"), "w") as f:
            json.dump(run, f)
        g = R.build(d)["grade"]
        self.assertIn("light.load", g["missing"])
        self.assertTrue(g["provisional"])


class Settle(unittest.TestCase):
    def test_last_frame_before_a_quiet_gap(self):
        frames = [900, 1000, 1016, 1033, 1050, 2000]
        self.assertEqual(R.visual_settle(frames, 1000, ended_quiet=True), (1050, "quiet-gap"))

    def test_burst_that_ends_when_sampling_ends(self):
        self.assertEqual(R.visual_settle([1000, 1500, 2100], 1000, ended_quiet=True), (2100, "quiet-gap"))

    def test_constant_animation_falls_back_to_page_stop(self):
        frames = [1000 + 16.7 * i for i in range(600)]
        self.assertEqual(R.visual_settle(frames, 1000, ended_quiet=False), (1000, "no-quiet-gap"))

    def test_no_paint_after_page_stop(self):
        self.assertEqual(R.visual_settle([500], 1000, ended_quiet=True), (1000, "page-stop"))


def step(stage, n, p95_frames, run=1, sf=True):
    """A step line: 120 rAF frames, of which p95_frames missed one vsync."""
    ts = [0.0]
    for i in range(120):
        ts.append(ts[-1] + (33.333 if i < p95_frames else 16.667))
    raf = P.frame_stats(P.intervals(ts), 16.667)
    pl = {"t": "step", "stage": stage, "n": n, "run": run, "raf": raf}
    if sf:
        pl["sf"] = {"period_ms": 16.667, "window_ms": [5000, 5000 + ts[-1]], "oldest_ms": 3000,
                    "presents_ms": [5000 + t for t in ts]}
    return pl


def synthetic_lines(run, js_ms):
    lines = [{"t": "hello", "run": run, "ua": "Mozilla/5.0 (Android 10; Mobile; rv:159.0) Gecko/159.0 Firefox/159.0",
              "load": {"lcp_ms": None, "fcp_ms": 250, "lcp_from": "fcp"} if run == 2 else {"lcp_ms": 300, "fcp_ms": 250, "lcp_from": "lcp"}}]
    lines.append({"t": "js", "run": run, "median_ms": js_ms, "p95_ms": js_ms * 1.5, "checksum": 7, "expected": 7})
    for n, missed in [(1, 0), (2, 0), (4, 2), (8, 20), (16, 90)]:
        lines.append(step("css-transform", n, missed, run))
    for n in (1, 2):
        lines.append(step("svg", n, 0, run))
    sc = step("scroll", 240, 0, run, sf=False)
    sc["scrolled_px"] = 0 if run == 3 else 720
    lines.append(sc)
    lines.append({"t": "done", "run": run})
    return lines


# When each reference dashboard starts, s; typical without its camera (rubric v10) after heavy, so no
# two views' frames meet.
NAV = {"light": 30.0, "typical": 40.0, "heavy": 50.0, "typical-nocam": 70.0}
CARDS = {"light": 20, "typical": 50, "heavy": 120, "typical-nocam": 50}


def swipe(nav, i, intervals, moved=True, sf=True):
    """One swipe's step line: its window starts 5 + i s after navigation."""
    w0 = (nav + 5.0 + i) * 1000
    ts = [0.0]
    for x in intervals:
        ts.append(ts[-1] + x)
    pl = {"t": "step", "stage": "scroll", "n": i, "window_ms": round(ts[-1]), "scrolled_px": 700 if moved else 0,
          "first_scroll_ms": 40, "raf": P.frame_stats(intervals, 16.667), "raf_intervals": [round(x, 1) for x in intervals]}
    pl["sf"] = {"period_ms": 16.667, "window_ms": [w0, w0 + ts[-1]], "oldest_ms": (w0 - 500) if sf else (w0 + 1),
                "presents_ms": [w0 + t for t in ts]}
    return pl


def late(n, missed, slow=33.333):
    """n frame intervals, the first `missed` of them slow."""
    return [slow if k < missed else 16.667 for k in range(n)]


# The live updates as the page logs them: [due, ran, transition ms, kind (0 state, 1 clock)], every 200 ms; a tile (180 ms)
# and a row (0) in turn, and the clock at 1000 ms.
CHANGES = [[200.0 * k, 200.0 * k + 1.0, 180 if k % 2 else 0, 0] for k in range(1, 9)] + [[1000.0, 1000.5, 0, 1]]


def updates_step(nav, missed=0, slow=33.333, idle_slow=0, changes=CHANGES, window=1667, present_after=None):
    """The live-updates step: rAF frames over the window, the first `missed` frames that had a change
    to draw are `slow`; `idle_slow` frames with nothing to draw (at the window's start, before the
    first change) are slow too. It goes live 3 s after navigation and measures 1 s later."""
    ts, k_miss, k_idle = [0.0], 0, 0
    while ts[-1] < window:
        a = ts[-1]
        busy = any(c[0] < a + 16.667 and c[1] + c[2] >= a for c in changes)
        if busy and k_miss < missed:
            x, k_miss = slow, k_miss + 1
        elif not busy and a < 150 and k_idle < idle_slow:
            x, k_idle = slow, k_idle + 1
        else:
            x = 16.667
        ts.append(a + x)
    w0 = (nav + 4.0) * 1000
    return {"t": "step", "stage": "updates", "n": 1, "window_ms": window, "t0": 4000.0,
            "raf": P.frame_stats(P.intervals(ts), 16.667), "raf_times": [round(t, 1) for t in ts],
            "changes": changes,
            "sf": {"period_ms": 16.667, "window_ms": [w0, w0 + window], "oldest_ms": w0 - 500,
                   "presents_ms": ([w0 + t + 33.3 for t in ts] if present_after is None
                                   else sorted(w0 + c[1] + present_after for c in changes))}}


def dialog_lines(tier, settled=320.0, graph=40.0, never=None, frames=None):
    """Clarity v3 (#211): the view's more-info dialogs as the page logs them, after the swipes: each opens
    in 10 ms, its 300 ms sheet at 60 fps (`frames`: other intervals), shown at 320 ms; the sensor's
    graph at `graph`, so it settles at the later of the two (`settled` for the others). `never`: the
    kind of a dialog that never opened."""
    out = []
    for i, d in enumerate(T.DIALOGS):
        if d["kind"] == never:
            out.append({"t": "dialog", "stage": tier, "n": i, "of": len(T.DIALOGS), "entity": d["entity"], "kind": d["kind"],
                        "graph": d["graph"], "mode": None, "opened": False, "open_ms": None, "shown_ms": None, "graph_ms": None,
                        "settled_ms": None, "anim": {"n": 0}, "anim_intervals": [], "dropped": 0, "longest_frame_ms": None,
                        "close_ms": None, "error": "it never opened within 20 s", "close_error": None})
            continue
        iv = frames or [16.667] * 18
        st = max(320.0, graph) if d["graph"] else settled
        out.append({"t": "dialog", "stage": tier, "n": i, "of": len(T.DIALOGS), "entity": d["entity"], "kind": d["kind"],
                    "graph": d["graph"], "mode": "bottom-sheet", "show_duration_ms": 300, "reduced_motion": False,
                    "opened": True, "open_ms": 10.0, "shown_ms": 320.0, "graph_ms": graph if d["graph"] else None,
                    "settled_ms": st, "anim": P.frame_stats(iv, 16.667), "anim_intervals": iv,
                    "dropped": sum(max(0, round(x / 16.667) - 1) for x in iv), "longest_frame_ms": max(iv),
                    "close_ms": 310.0, "close_longest_ms": 16.7, "error": None, "close_error": None})
    return out


def tier_entry(tier, inp, scroll_missed=0, updates_missed=0, load_ms=200, fcp_ms=150, dpr=1.5, blocking_ms=40.0, dialogs=None):
    """One reference dashboard in a graded run. Its page logs 1 s after navigation and goes live 3 s
    after it; three swipes of 60 frames follow. Its first screen is drawn 150 ms after navigation (on
    screen at 200 ms), its main thread free at 300 ms, and it blocks for `blocking_ms` in between."""
    nav = NAV[tier]
    return {"nonce": tier,
            "page": {"t": "refdash", "tier": tier, "protocol": G.PROTOCOL_VERSION, "clarity": G.CLARITY, "nodes": 1458, "cards": CARDS[tier], "css": [960, 480], "dpr": dpr,
                     "fcp_ms": fcp_ms, "load_ms": load_ms, "nav_ms": 0.0,
                     "usable": {"rendered_tick_ms": 150.0, "first_screen_cards": 14, "head_cards": 10, "interactive_ms": 300.0,
                                "blocking_ms": blocking_ms, "long_frames": 1, "longest_frame_ms": 90.0},
                     "origin_epoch_ms": 1e12 + nav * 1000, "epoch_ms": 1e12 + nav * 1000 + 1000, "mono": nav + 1.0},
            "live_mono": nav + 3.0,
            "measure": {"t": "measure", "stage": "updates", "moving": 12, "offscreen": 0, "targets": 10, "update_every_ms": 200},
            "step": updates_step(nav, updates_missed),
            "input": {"t": "input", "method": "event-timing", "taps": 10, "inp_ms": inp},
            "camera": camera_line(tier),
            "swipes": [swipe(nav, i, late(60, scroll_missed)) for i in range(3)],
            "dialogs": dialog_lines(tier) if dialogs is None else dialogs}


class DialogRuns(unittest.TestCase):
    """Clarity v3 (#211): HA's more-info dialogs in one run: the slowest to settle grades, a dialog that
    never opened or never settled fails the run, and lost lines leave it not measured."""

    def test_the_slowest_dialog_settles_the_run(self):
        run, why = R.dialog_run(dialog_lines("light", settled=300.0, graph=700.0))
        self.assertIsNone(why)
        self.assertEqual((run["n"], run["failed"], run["settled_ms"]), (3, 0, 700.0))     # the sensor's graph
        self.assertEqual(run["frames"]["n"], 54)                                         # three animations pooled
        self.assertEqual(G.dialog_level(run), "premium")
        self.assertEqual(run["modes"], ["bottom-sheet"])

    def test_a_slow_history_graph(self):
        run, _ = R.dialog_run(dialog_lines("light", graph=1500.0))
        self.assertEqual((run["settled_ms"], G.dialog_level(run)), (1500.0, "fluid"))
        run, _ = R.dialog_run(dialog_lines("light", graph=2400.0))
        self.assertEqual(G.dialog_level(run), G.BELOW)

    def test_a_dialog_that_never_opened_fails_the_run(self):
        run, why = R.dialog_run(dialog_lines("light", never="climate"))
        self.assertIsNone(why)
        self.assertEqual((run["failed"], run["settled_ms"]), (1, None))
        self.assertEqual(G.dialog_level(run), G.BELOW)
        self.assertEqual(run["errors"], ["climate: it never opened within 20 s"])

    def test_lost_lines_are_not_measured(self):
        run, why = R.dialog_run(dialog_lines("light")[:2])
        self.assertIsNone(run)
        self.assertEqual(why, "only 2 of 3 dialog lines arrived")
        self.assertEqual(R.dialog_run([]), (None, None))                 # a page before Clarity v3

    def test_late_animation_frames_cost_only_premium(self):
        run, _ = R.dialog_run(dialog_lines("light", frames=[33.333] * 9))
        self.assertGreater(run["dropped"], 0)
        self.assertEqual(G.dialog_level(run), "good")

    def test_a_tier_run_carries_it_and_a_timed_out_view_fails_it(self):
        run = R.tier_run("light", tier_entry("light", 100), load_frames())
        self.assertEqual(G.dialog_level(run["dialog_run"]), "premium")
        lost = R.tier_run("light", tier_entry("light", 100, dialogs=dialog_lines("light")[:1]), load_frames())
        self.assertIsNone(lost["dialog_run"])
        self.assertEqual(lost["dialog_error"], "only 1 of 3 dialog lines arrived")

    def test_report_md_and_the_headline_say_it(self):
        failed = R.dialog_run(dialog_lines("heavy", never="sensor"))[0]
        ok = R.dialog_run(dialog_lines("heavy", graph=1400.0))[0]
        s_ = {"dialog_runs": [ok, failed, ok], "dialog_settled_ms": P.spread([1400.0, None, 1400.0])}
        cell = R.dialog_cell(s_, {"dialog": "fluid"})
        self.assertIn("settled in 1400 ms", cell)
        self.assertIn("1 of 3 run(s) had one that didn't open fully", cell)
        self.assertEqual(R.task_phrase("dialog", {"dialog_runs": [ok, ok]}), "a more-info dialog takes 1.4 s to open")
        self.assertEqual(R.task_phrase("dialog", s_), "a more-info dialog didn't open fully in 1 of 3 run(s)")
        self.assertEqual(R.task_phrase("dialog", {}), "the more-info dialog wasn't measured")
        # A view that timed out failed them, measured: never "wasn't measured" (#410's CodeRabbit).
        self.assertEqual(R.task_phrase("dialog", {"dialog_runs": [{"timed_out": True}]}),
                         "the view didn't get to its more-info dialogs in 1 of 1 run(s)")
        self.assertEqual(R.task_phrase("dialog", {"dialog_runs": [ok, {"timed_out": True}]}),
                         "a more-info dialog didn't open fully in 1 of 2 run(s)")
        self.assertEqual(R.dialog_cell({}, {}), "n/a (not measured by this version)")

    def test_a_view_left_for_the_start_view_is_said_and_graded_as_measured(self):
        """#410 (s1v3a): HA held heavy's thermostat and sensor dialogs past the view; the page left it for
        the start view before the next view. Detail only: heavy's dialogs stay its failure."""
        failed = R.dialog_run(dialog_lines("heavy", never="sensor"))[0]
        away = {"ms": 31200, "clean": True, "closed": 1, "pending": [], "open": False, "history": False, "via": "start",
                "first": {"ms": 30000, "clean": False, "closed": 0, "pending": ["climate.clarity_0", "sensor.clarity_10"],
                          "open": False, "history": True}}
        run = R.tier_run("heavy", dict(tier_entry("heavy", 100, dialogs=dialog_lines("heavy", never="sensor")), settle=away),
                         load_frames())
        self.assertEqual(run["dialog_settle"], away)
        self.assertEqual(run["dialog_run"], failed)                     # the same record, the same failure
        s_ = {"dialog_runs": [failed, failed], "dialog_settles_away": [away]}
        lines = R.dialog_detail({"tiers": {"heavy": s_}})
        self.assertIn("heavy: HA still held climate.clarity_0, sensor.clarity_10 after the view's own wait in 1 of 2 run(s), "
                      "so the page moved to the start view, untimed, where they came and were closed; the next view "
                      "opened from wherever HA was then.", lines[-1])
        # Chrome, 2026-11-16: HA's held history.back() landed after the move and took it back to the view.
        back = dict(away, at="heavy")
        line = R.dialog_detail({"tiers": {"heavy": dict(s_, dialog_settles_away=[back])}})[-1]
        self.assertIn("the next view opened from wherever HA was then (heavy: HA's late history.back() took it back).", line)
        # A view HA was done with at once says nothing more.
        self.assertFalse(any("start view" in x for x in R.dialog_detail({"tiers": {"light": {"dialog_runs": [failed]}}})))


class CameraStreamsAll(unittest.TestCase):
    """The camera task counts every stream the view shows: heavy with 3 of its 4 streams playing (one
    never showed a frame, or wasn't found at all) fails it, though the 3 played well (#182's verifier)."""

    def test_three_of_four_fail(self):
        played = R.camera_run(camera_line("heavy"), "heavy")
        self.assertEqual(G.camera_level(played), "good")
        dead = camera_line("heavy")
        dead["streams"][3].update(first_frame_ms=None, shown_pct=None, frames=0, decoded=0)
        run = R.camera_run(dead, "heavy")
        self.assertIsNone(run["first_frame_ms"])
        self.assertEqual(G.camera_level(run), G.BELOW)
        missing = camera_line("heavy")
        missing["streams"] = missing["streams"][:3]
        missing["found"] = 3
        run = R.camera_run(missing, "heavy")
        self.assertEqual((run["streams"], run["expected"]), (3, 4))
        self.assertEqual(G.camera_level(run), G.BELOW)


class CameraFailedRuns(unittest.TestCase):
    """CodeRabbit on cbc2be6: the numbers are from the runs whose streams all played, and a run with a
    stream that showed no frame was hidden whenever another run played. Both outputs say how many."""

    def test_a_failed_run_is_said(self):
        ok = R.camera_run(camera_line("typical"), "typical")
        dead = camera_line("typical")
        dead["streams"][0].update(first_frame_ms=None, shown_pct=None)
        s_ = {"camera_runs": [ok, R.camera_run(dead, "typical"), ok]}
        self.assertIn("1 of 3 run(s) had a stream that showed no frame", R.camera_cell(s_, {"camera": "slow"}))
        self.assertIn("(1 of 3 run(s) had a stream that showed no frame)", R.task_phrase("camera", s_))
        self.assertNotIn("showed no frame", R.camera_cell({"camera_runs": [ok, ok]}, {"camera": "good"}))


class HeldBack(unittest.TestCase):
    """s1rtc10: the headline said "Held back by typical dashboards: the view takes 1.2 s to open" when
    typical's open was good and its blocking (2.9 s, poor) held it back. The headline names the tasks
    next_limits gives, each in its own words."""

    def test_a_page_held_back_only_by_blocking(self):
        ok = {"inp_ms": 100, "load_ms": 1241, "blocking_ms": 100, "scroll_level": "fluid", "updates_level": "fluid",
              "camera_level": "good", "dialog_level": "good"}
        tiers = {"light": dict(ok, camera_level=None), "typical-nocam": dict(ok, blocking_ms=2862.0, camera_level=None),
                 "typical": dict(ok, blocking_ms=2900.0), "heavy": dict(ok, blocking_ms=3100.0)}
        g = G.grade(tiers, 2000)
        self.assertEqual(g["grade"], "D")
        self.assertEqual(G.next_limits(g), [{"page": "typical-nocam", "needs": "fluid", "tasks": ["blocking"]}])
        ref = {"tiers": {"typical-nocam": {"load_ms": {"median": 1241}, "blocking_ms": {"median": 2862.0}}}}
        h = R.headline("Aqara S1 Plus", g, ref)
        self.assertIn("Held back by typical dashboards without a camera: the main thread is blocked for 2.9 s after the view is drawn.", h)
        self.assertNotIn("to open", h)

    def test_every_task_has_its_own_words(self):
        s_ = {"load_ms": {"median": 1241}, "blocking_ms": {"median": 2862.0}}
        self.assertEqual(R.task_phrase("load", s_), "the view takes 1.2 s to open")
        self.assertEqual(R.task_phrase("blocking", {}), "the blocking after the view is drawn wasn't measured")
        for t in G.TASKS:
            R.task_phrase(t, s_)                       # none falls through to another task's words
        self.assertRaises(ValueError, R.task_phrase, "nonsense", s_)


class TimedOut(unittest.TestCase):
    """#202, s1rtc3: a view that didn't finish within the runner's deadline fails its own unmeasured
    tasks, measured: open and tap at the deadline, the rest below the lowest bar. Not missing."""

    def test_a_view_that_never_became_usable_fails_every_task(self):
        run = R.tier_run("heavy", {"nonce": "ab12", "swipes": [], "timed_out_s": 300}, [])
        self.assertNotIn("error", run)
        self.assertEqual((run["load_ms"], run["inp_ms"]), (300000, 300000))
        self.assertEqual(run["timed_out_tasks"], ["open", "tap", "blocking", "scroll", "updates", "dialog", "camera"])
        self.assertEqual(G.dialog_level(run["dialog_run"]), G.BELOW)     # Clarity v3: it never got to its dialogs
        self.assertEqual(G.scroll_level(run["scroll"]), G.BELOW)
        self.assertEqual(G.update_level(run["updates_run"]), G.BELOW)
        self.assertEqual(G.camera_level(run["camera_run"]), G.BELOW)
        self.assertIsNone(R.tier_run("light", {"nonce": "ab12", "swipes": [], "timed_out_s": 300}, [])["camera_run"])  # no streams

    def drawn_only(self, tier, drawn_tier=None):
        """A view drawn 150 ms after its navigation (on screen at 200 ms) whose main thread was never
        free: the runner has its `drawn` line and no `refdash`."""
        nav = NAV[tier]
        drawn = {"t": "drawn", "tier": drawn_tier or tier, "protocol": G.PROTOCOL_VERSION, "clarity": G.CLARITY, "nav_ms": 0.0,
                 "usable": {"rendered_tick_ms": 150.0, "first_screen_cards": 14, "head_cards": 10},
                 "origin_epoch_ms": 1e12 + nav * 1000, "epoch_ms": 1e12 + nav * 1000 + 150, "mono": nav + 0.15}
        return {"nonce": tier, "drawn": drawn, "swipes": [], "timed_out_s": 300}

    def test_a_view_drawn_but_never_free_keeps_its_open(self):
        """Rubric 2 (#264's verifiers): the open is the draw, so it stands; blocking takes the deadline
        and usable isn't measured."""
        run = R.tier_run("typical", self.drawn_only("typical"), load_frames())
        self.assertNotIn("error", run)
        self.assertEqual((run["load_ms"], run["drawn_ms"], run["blocking_ms"], run["inp_ms"]), (200, 150, 300000, 300000))
        self.assertIsNone(run["usable_ms"])
        self.assertEqual(run["timed_out_tasks"], ["tap", "blocking", "scroll", "updates", "dialog", "camera"])
        self.assertEqual(G.band(run["load_ms"], G.METRICS["load_ms"]), "good")

    def test_a_drawn_line_of_another_view_is_not_this_ones(self):
        run = R.tier_run("typical", self.drawn_only("typical", drawn_tier="heavy"), load_frames())
        self.assertEqual(run["load_ms"], 300000)
        self.assertIn("open", run["timed_out_tasks"])

    def test_report_md_says_the_view_didnt_finish(self):
        ok = R.camera_run(camera_line("typical"), "typical")
        late = R.tier_run("typical", {"nonce": "ab12", "swipes": [], "timed_out_s": 300}, [])["camera_run"]
        cell = R.camera_cell({"camera_runs": [ok, late, ok]}, {"camera": "slow"})
        self.assertIn("the view didn't finish in 1 of 3 run(s)", cell)
        self.assertNotIn("showed no frame", cell)
        self.assertEqual(R.camera_cell({"camera_runs": [late]}, {"camera": "slow"}), "the view didn't finish in 1 of 1 run(s), slow")


class Published(unittest.TestCase):
    """A report published in the frame's folder (frames/<id>/clarity/): written with --out, the run
    directory untouched, and --app-version shown as given, which the grade never reads."""

    def test_out_and_app_version(self):
        b = Build()
        b.setUp()
        before = sorted(os.listdir(b.dir))
        out = tempfile.mkdtemp()
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "report.py"), b.dir, "--out", out,
                            "--app-version", "pre-v1"], capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(sorted(os.listdir(b.dir)), before)                    # nothing written into the run
        self.assertEqual(sorted(os.listdir(out)), ["report.json", "report.md"])
        with open(os.path.join(out, "report.json")) as f:
            rep = json.load(f)
        self.assertEqual((rep["frame"]["app"]["version_name"], rep["frame"]["app"]["version_label"]), ("pre-v1", "given"))
        self.assertIsNone(rep["frame"]["app"]["version_code"])
        with open(os.path.join(out, "report.md")) as f:
            md = f.read()
        self.assertIn("app pre-v1", md)
        self.assertNotIn("0.4.3", md)
        self.assertEqual(S.summarise(rep, allow_provisional=True)["app_version"], "pre-v1")
        # The grade is the same with or without the label.
        self.assertEqual(rep["grade"]["grade"], b.rep["grade"]["grade"])
        self.assertEqual(rep["grade"]["bands"], b.rep["grade"]["bands"])

    def test_the_apk_sizes_take_the_label(self):
        rep = {"frame": {"app": {"package": "sh.edm.s1kiosk", "version_name": "0.4.3", "version_code": "403"}},
               "apk": [{"file": "baseline-main-5d0a0d2.apk", "abis": ["armeabi-v7a"], "bytes": 1}]}
        R.label_app_version(rep, "pre-v1")
        self.assertEqual(rep["apk"][0]["file"], "sh.edm.s1kiosk-pre-v1.apk")


class CpuClock(unittest.TestCase):
    """#244: the CPU clock each view ran at, as detail (the S1's power HAL held 1.2 of 1.608 GHz)."""

    def test_per_view_clock_and_the_top(self):
        runs = []
        for k in (1, 2, 3):
            r = refstart(k, 100)
            r["cpu_max_khz"] = {"0": 1608000, "1": 1608000, "2": 1608000, "10": 1608000}
            # CPU numbers from the sysfs paths: cpu10 is cpu10, whatever order the shell listed it in.
            r["cpu_khz"] = [["light", 1.0, {"0": 1200000, "1": 1200000, "2": 1200000, "10": 1200000}],
                            ["heavy", 2.0, {"10": 1608000, "0": 1200000, "1": 1200000, "2": 1008000}]]
            runs.append(R.reference_run(r))
        ref = R.reference_summary(runs)
        self.assertEqual(ref["tiers"]["light"]["cpu_mhz"], {"median": 1200.0, "min": 1200.0, "max": 1200.0, "n": 12})
        self.assertEqual((ref["tiers"]["heavy"]["cpu_mhz"]["min"], ref["tiers"]["heavy"]["cpu_mhz"]["max"]), (1008.0, 1608.0))
        self.assertIsNone(ref["tiers"]["typical"]["cpu_mhz"])
        self.assertEqual(ref["cpu_max_mhz"], 1608.0)
        self.assertEqual({i: c["median"] for i, c in ref["tiers"]["heavy"]["cpu_mhz_by_core"].items()},
                         {"0": 1200.0, "1": 1200.0, "2": 1008.0, "10": 1608.0})
        self.assertEqual(list(ref["tiers"]["heavy"]["cpu_mhz_by_core"]), ["0", "1", "2", "10"])
        # report.md: the interval the run used, and each core when they differ (big.LITTLE).
        for r in runs:
            r["cpu_clock_s"] = 5.0
        ref = R.reference_summary(runs)
        clocks = [(t, s_["cpu_mhz"]) for t, s_ in ref["tiers"].items() if (s_.get("cpu_mhz") or {}).get("median") is not None]
        line = R.cpu_clock_line(ref, clocks, ref["cpu_max_mhz"], ref["cpu_clock_s"])
        self.assertEqual(line, "CPU clock while each view was open (detail, every core, every 5 s): light 1200 MHz (1200 to 1200); "
                               "heavy cpu0 1200, cpu1 1200, cpu2 1008, cpu10 1608 MHz (medians); the CPU's top clock is 1608 MHz.")
        # And without samples (a run from before): nothing.
        self.assertIsNone(R.reference_summary([R.reference_run(refstart(1, 100))])["cpu_max_mhz"])


class StallsNotReported(unittest.TestCase):
    """#205's verifier: a receiver that reports no freezeCount has stalls n/a, never 0 (which would
    pass the bars), and that run's camera level is not measured."""

    def test_na_not_zero(self):
        line = camera_line("typical")
        for st in line["streams"]:
            st.update(stall_total_ms=None, max_gap_ms=None, gaps_source="webrtc (no freeze counts: n/a)")
        run = R.camera_run(line, "typical")
        self.assertEqual((run["stall_total_ms"], run["max_gap_ms"], run["stalls_reported"]), (None, None, False))
        self.assertIsNone(G.camera_level(run))
        ok = R.camera_run(camera_line("typical"), "typical")
        self.assertNotIn("stalls_reported", ok)
        self.assertEqual(G.camera_level(ok), "good")
        # No span to measure gaps over (one sample, nothing decoded): max_gap null, also n/a.
        line = camera_line("typical")
        line["streams"][0].update(max_gap_ms=None)
        self.assertEqual(R.camera_run(line, "typical")["stalls_reported"], False)


class ReceiverSpanShort(unittest.TestCase):
    """#255's review: on a busy page getStats answers late, and the receiver's counts end at the last
    answer. Stalls after it are unknown, not 0, so a stream whose counts leave more than 500 ms of the
    window out has stalls n/a, and that run's camera level is not measured."""

    def line(self, spans):
        line = camera_line("heavy")
        line["window_ms"] = 30043
        for st, span in zip(line["streams"], spans):
            st.update(span_ms=span, source="webrtc+rvfc", gaps_source="webrtc (bound)", max_gap_ms=216.9)
        return line

    def test_the_webrtc_detail_says_freezes_n_a_when_the_counts_miss_the_window(self):
        """#255's review: s1rtc10's heavy detail said "0 freezes" from counts that covered about 6.5 s of
        30; the stalls were n/a, the freezes must be too, with the time the counts cover."""
        def run(spans):
            line = self.line(spans)
            for st in line["streams"]:
                st["webrtc"] = {"inbound": True, "frames_decoded": 100, "frames_dropped": 0, "freezes": 0, "freeze_s": 0.0}
            return R.camera_run(line, "heavy")
        short = R.webrtc_detail(run([6600, 6600, 6700, 6700]))
        self.assertIn("freezes n/a (the receiver's counts cover 6.6 s of the window's 30.0 s)", short)
        self.assertNotIn("0 freezes", short)
        whole = R.webrtc_detail(run([30043] * 4))
        self.assertIn("0 freezes (0.0 s)", whole)
        self.assertNotIn("freezes n/a", whole)
        self.assertNotIn("receiver's counts cover", whole)

    def test_freezes_n_a_when_a_stream_never_played_and_the_rest_are_short(self):
        """#255's review: with a stream that never played the run is not whole and has no uncovered_ms;
        the freezes still go n/a, from the played streams' spans. The one that never played (its span
        the window's) doesn't count, as in camera_run."""
        line = self.line([30043, 6600, 6700, 6700])
        for st in line["streams"]:
            st["webrtc"] = {"inbound": True, "frames_decoded": 100, "frames_dropped": 0, "freezes": 0, "freeze_s": 0.0}
        line["streams"][0].update(first_frame_ms=None, shown_pct=None)
        run = R.camera_run(line, "heavy")
        self.assertNotIn("uncovered_ms", run)
        self.assertIn("freezes n/a (the receiver's counts cover 6.6 s of the window's 30.0 s)", R.webrtc_detail(run))
        # Only the stream that never played is short: the played ones cover the window.
        line = self.line([6600, 30043, 30043, 30043])
        for st in line["streams"]:
            st["webrtc"] = {"inbound": True, "frames_decoded": 100, "frames_dropped": 0, "freezes": 0, "freeze_s": 0.0}
        line["streams"][0].update(first_frame_ms=None, shown_pct=None)
        self.assertIn("0 freezes (0.0 s)", R.webrtc_detail(R.camera_run(line, "heavy")))

    def test_a_short_span_is_not_measured(self):
        # s1rtc9, heavy run 2: three streams' counts covered 6821 ms of 30043.
        run = R.camera_run(self.line([6821, 6821, 6821, 24465]), "heavy")
        self.assertEqual((run["stall_total_ms"], run["max_gap_ms"], run["stalls_reported"]), (None, None, False))
        self.assertEqual(run["uncovered_ms"], 23222.0)
        self.assertIsNone(G.camera_level(run))
        self.assertEqual(run["first_frame_ms"], 600.0)     # what was measured stays

    def test_the_bar_is_the_longest_gap_good_allows(self):
        self.assertEqual(R.CAMERA_UNCOVERED_MAX_MS, 500)
        ok = R.camera_run(self.line([29543] * 4), "heavy")             # 500 ms left out: covered
        self.assertNotIn("stalls_reported", ok)
        self.assertEqual(ok["uncovered_ms"], 500.0)                    # recorded: the graders read it
        # Graded, but good can't be confirmed: the 216.9 ms gap could run on into the 500 ms left out.
        self.assertIsNone(G.camera_level(ok))
        close = R.camera_run(self.line([29760] * 4), "heavy")            # 283 ms left out: 216.9 + 283 <= 500
        self.assertEqual(G.camera_level(close), "good")
        # A span past the window (nothing left out) stays good too; checked below.
        one = R.camera_run(self.line([29543, 29543, 29542, 29543]), "heavy")   # 501 ms: not
        self.assertIs(one["stalls_reported"], False)
        self.assertEqual(one["uncovered_ms"], 501.0)
        # A span past the window (the reports' clock against the page's): covered, nothing left out.
        past = R.camera_run(self.line([30050] * 4), "heavy")
        self.assertNotIn("stalls_reported", past)
        self.assertEqual(past["uncovered_ms"], 0.0)
        self.assertEqual(G.camera_level(past), "good")

    def test_hls_has_no_span_and_is_unaffected(self):
        run = R.camera_run(camera_line("heavy", path="hls"), "heavy")
        self.assertNotIn("stalls_reported", run)
        self.assertNotIn("uncovered_ms", run)

    def test_the_fluid_total_counts_what_was_left_out(self):
        """#265's verifier: 600 ms of stalls with 400 ms left out could be 1 s (the fluid bar): open,
        with the reason in the report and in report.md's cell; 399 ms left out stays fluid."""
        def run(left):
            line = self.line([30043 - left] * 4)
            for st in line["streams"]:
                st.update(stall_total_ms=600.0, max_gap_ms=600.0)
            return R.camera_run(line, "heavy")
        self.assertEqual(G.camera_level(run(399)), "fluid")
        edge = run(400)
        self.assertNotIn("stalls_reported", edge)                       # measured; the level is what's open
        self.assertIsNone(G.camera_level(edge))
        heavy = {"inp_ms": None, "scroll_bands": [], "updates_runs": [], "load_ms": None, "camera_runs": [edge, run(0)]}
        reasons = R.coverage_reasons({"graded_runs": 2, "tiers": {"heavy": heavy}}, 2)
        self.assertTrue(any("could reach fluid's 1000 ms in all: fluid couldn't be confirmed" in x for x in reasons), reasons)
        self.assertFalse(any("good's" in x for x in reasons), reasons)
        self.assertIn("the level not measured in 1 of 2 run(s)", R.camera_cell(heavy, {"camera": None}))

    def test_a_rubric_without_a_good_camera_gap_fails_clearly(self):
        with mock.patch.object(G, "CAMERA_LEVELS", [lv for lv in G.CAMERA_LEVELS if lv["level"] != "good"]):
            self.assertRaisesRegex(RuntimeError, "'good' level with max_gap_max_ms", R._good_max_gap_ms)

    def test_the_note_names_good_s_gap_when_that_is_what_is_open(self):
        """#265's CodeRabbit: a 450 ms gap, no stalls and 100 ms left out leaves good's gap unsettled,
        not fluid's total; the note says which."""
        line = self.line([30043 - 100] * 4)
        for st in line["streams"]:
            st.update(stall_total_ms=0.0, max_gap_ms=450.0)
        run = R.camera_run(line, "heavy")
        self.assertEqual(G.camera_level_why(run), (None, G.OPEN_GOOD_GAP))
        heavy = {"inp_ms": None, "scroll_bands": [], "updates_runs": [], "load_ms": None, "camera_runs": [run]}
        reasons = R.coverage_reasons({"graded_runs": 1, "tiers": {"heavy": heavy}}, 1)
        self.assertEqual([x for x in reasons if "camera" in x],
                         ["heavy: 1 camera run(s) whose longest gap, with the part of the window their receiver counts left out, "
                          "could pass good's 500 ms: good couldn't be confirmed, not measured"])

    def test_report_md_says_when_stalls_are_na_in_some_runs(self):
        """#265's verifier: s1rtc9's typical read "stalls 0 ms, n=2" with run 3 n/a."""
        typical = {"camera_runs": [R.camera_run(self.line([30043] * 4), "heavy"),
                                   R.camera_run(self.line([6821] * 4), "heavy")]}
        cell = R.camera_cell(typical, {"camera": "good"})
        self.assertIn("stalls n/a in 1 of 2 run(s)", cell)
        self.assertIn("the level not measured in 1 of 2 run(s)", cell)
        # Every run n/a: the cell's own "stalls n/a" says it, no "in 2 of 2" after it (#265's delta verifier).
        every = R.camera_cell({"camera_runs": [R.camera_run(self.line([6821] * 4), "heavy")] * 2}, {"camera": None})
        self.assertIn("stalls n/a", every)
        self.assertNotIn("of 2 run(s)", every)

    def test_the_report_says_why(self):
        heavy = {"inp_ms": None, "scroll_bands": [], "updates_runs": [], "load_ms": None,
                 "camera_runs": [R.camera_run(self.line([6821, 6821, 6821, 24465]), "heavy")]}
        ref = {"graded_runs": 1, "tiers": {"heavy": heavy}}
        reasons = R.coverage_reasons(ref, 1)
        self.assertTrue(any("left up to 23.2 s of the window out" in x for x in reasons), reasons)
        self.assertFalse(any("no freeze counts" in x for x in reasons), reasons)
        self.assertIn("its stalls weren't measured in 1 of 1 run(s)", R.task_phrase("camera", ref["tiers"]["heavy"]))
        # s1rtc9's heavy: the first frame after 4 s already fails every level, so the run is slow and
        # the short span leaves nothing open: no reason, not provisional for it.
        late = self.line([6821, 6821, 6821, 24465])
        for st in late["streams"]:
            st["first_frame_ms"] = 7083.4
        heavy["camera_runs"] = [R.camera_run(late, "heavy")]
        self.assertEqual(G.camera_level(heavy["camera_runs"][0]), "slow")
        self.assertEqual(R.coverage_reasons(ref, 1), [])


class CameraDetail(unittest.TestCase):
    """Detail for the camera task (smoke-cam1, 2026-11-13): which decoders a view got and where its
    segments were late, so a slow camera can be told apart: the decoder, the link or the page."""

    def test_the_decoders_and_fetches_reach_report_md(self):
        fetches = {"segment": {"n": 120, "queued_ms": {"median": 2, "p95": 900, "max": 1500},
                               "wait_ms": {"median": 10, "p95": 40, "max": 80},
                               "total_ms": {"median": 30, "p95": 1100, "max": 2000}, "kb": 4000, "arrival_gap_max_ms": 5200},
                   "playlist": {"n": 130, "queued_ms": None, "wait_ms": None, "total_ms": None}, "streams": 4}
        line = dict(camera_line("heavy"), fetches=fetches)
        c = {"proc_mono": 20.0, "tiers": {"heavy": dict(tier_entry("heavy", 100), camera=line)},
             "media": {"heavy": {"codecs": ["OMX.allwinner.video.decoder.avc"] * 4, "flushes": 117, "eos": 43}}}
        run = R.reference_run(c)["tiers"]["heavy"]["camera_run"]
        self.assertEqual((run["fetches"]["segment"]["n"], run["media"]["flushes"]), (120, 117))
        ref = {"tiers": {"heavy": {"camera_runs": [run]}}}
        md = R.camera_detail(ref)
        self.assertEqual(md, ["heavy cameras, run 1 (detail): decoders 4 (OMX.allwinner.video.decoder.avc), 117 flushes, "
                              "43 drains; 120 segments, fetch p95 1100 ms (queued p95 900 ms), longest gap between a "
                              "stream's segments 5200 ms."])
        self.assertEqual(R.camera_detail({"tiers": {"heavy": {"camera_runs": [dict(run, media=None, fetches=None)]}}}), [])

    def test_frames_presented_inside_the_stalls_are_said(self):
        line = camera_line("heavy", stall=2500.0, max_gap=1200.0)
        for k, st in enumerate(line["streams"]):
            st["stall_presented"] = 30 if k == 0 else 0
        run = R.camera_run(line, "heavy")
        self.assertEqual(run["detail"][0]["stall_presented"], 30)
        self.assertEqual(run["stall_total_ms"], 2500.0)               # graded as before
        self.assertEqual(R.camera_detail({"tiers": {"heavy": {"camera_runs": [run]}}}),
                         ["heavy cameras, run 1 (detail): 30 frames presented inside the stalls (the callbacks', not the stream's)."])

    def test_the_headline_names_the_camera_not_the_view_again(self):
        """smoke-cam1's headline read "the view takes 6.6 s to open" twice: camera had no phrase of its
        own and fell through to the open-the-view one."""
        s_ = {"load_ms": {"median": 6620}, "camera_runs": [{"first_frame_ms": 4405.1, "shown_pct": 100.0}]}
        self.assertEqual(R.task_phrase("camera", s_), "a camera takes 4.4 s to show its first frame and shows 100% of its frames")
        self.assertEqual(R.task_phrase("camera", {"camera_runs": [{"first_frame_ms": None, "shown_pct": None}]}),
                         "a camera never showed a frame")


class Transport(unittest.TestCase):
    """Protocol 7 (#202, option B): report.json carries how the camera video went, and report.md says
    it beside the camera results; over adb, heavy's camera result is marked as possibly the link's."""

    def write(self, transport, chosen_by="auto", reason="r"):
        d = tempfile.mkdtemp()
        with open(os.path.join(d, "transport.json"), "w") as f:
            json.dump({"transport": transport, "chosen_by": chosen_by, "reason": reason, "checks": {}}, f)
        return d

    def test_the_record_and_its_words(self):
        heavy = {"tiers": {"heavy": {"camera_runs": [{"first_frame_ms": 600}]}}}
        adb = R.webrtc_transport(self.write("adb", reason="the panel couldn't reach this computer over the LAN"), heavy)
        self.assertEqual((adb["transport"], adb["chosen_by"]), ("adb", "auto"))
        self.assertIn("may be the link's, not the panel's", adb["limited"])
        line = R.transport_line({"webrtc_transport": adb})[0]
        self.assertTrue(line.startswith("Camera video through the test's adb link (USB): the panel couldn't reach"))
        self.assertIn("**Possibly limited by the test:**", line)
        lan = R.webrtc_transport(self.write("lan-udp", reason="the panel reached this computer over UDP"), heavy)
        self.assertIsNone(lan["limited"])
        self.assertEqual(R.transport_line({"webrtc_transport": lan}),
                         ["Camera video over the LAN (UDP), as in a real home: the panel reached this computer over UDP."])
        flag = R.webrtc_transport(self.write("lan-turn", "flag", "set with --webrtc-transport"), heavy)
        self.assertEqual(R.transport_line({"webrtc_transport": flag}),
                         ["Camera video over the LAN (TCP, through the test's relay): set with --webrtc-transport."])
        # Before protocol 7 (no transport.json), or no reference part: nothing.
        self.assertIsNone(R.webrtc_transport(tempfile.mkdtemp(), heavy))
        self.assertIsNone(R.webrtc_transport(self.write("adb"), None))
        self.assertEqual(R.transport_line({"webrtc_transport": None}), [])
        # Over adb without heavy cameras measured: nothing to caveat.
        self.assertIsNone(R.webrtc_transport(self.write("adb"), {"tiers": {"heavy": {}}})["limited"])


class CameraFallback(unittest.TestCase):
    """#202: the graded camera task is the WebRTC path's; HLS, on the camera pages' copies, is
    measured as detail and never moves the level or the letter."""

    def test_the_fallback_is_detail_beside_the_graded_path(self):
        runs = [R.reference_run(refstart(k, 100)) for k in (1, 2, 3)]
        ref = R.reference_summary(runs)
        heavy = ref["tiers"]["heavy"]
        self.assertEqual({r["path"] for r in heavy["camera_runs"]}, {"web_rtc"})
        self.assertEqual(heavy["camera_level"], "good")
        fb = heavy["camera_fallback"]
        self.assertEqual((fb["path"], fb["level"], fb["levels"]), ("hls", G.BELOW, [G.BELOW] * 3))
        self.assertEqual([(r["view"], r["shown_pct"]) for r in fb["runs"]], [("heavy-hls", 35.0)] * 3)
        self.assertEqual(ref["tiers"]["typical"]["camera_fallback"]["level"], "good")
        self.assertIsNone(ref["tiers"]["light"]["camera_fallback"])
        # The same runs without the copies grade the same: the fallback is never in the grade.
        bare = [R.reference_run(dict(refstart(k, 100), fallback={})) for k in (1, 2, 3)]
        self.assertEqual(G.grade(*R.grade_inputs({"reference": ref})),
                         G.grade(*R.grade_inputs({"reference": R.reference_summary(bare)})))
        md = R.camera_detail(ref)
        self.assertIn("heavy cameras over HLS, the fallback (detail, not graded; slow by the camera bars): run 1: first frame "
                      "3700 ms, 35 % shown, stalls 26600 ms, slow", "\n".join(md))

    def test_a_failed_copy_fails_only_itself(self):
        c = dict(refstart(1, 100), fallback={"heavy-hls": {"error": "heavy-hls didn't finish within 900 s"}},
                 fallback_error="heavy-hls didn't finish within 900 s")
        run = R.reference_run(c)
        self.assertIsNone(run["error"])
        self.assertEqual(run["camera_fallback"]["heavy"]["error"], "heavy-hls didn't finish within 900 s")
        self.assertIsNone(run["camera_fallback"]["heavy"]["first_frame_ms"])

    def test_webrtc_detail_names_the_decoder_and_the_relay(self):
        line = camera_line("typical")
        line["streams"][0]["webrtc"] = {"inbound": True, "frames_decoded": 290, "frames_dropped": 3, "freezes": 1, "freeze_s": 0.6,
                                        "decoder": "libvpx", "power_efficient": False,
                                        "candidate": {"local": "relay", "relay": "tcp", "protocol": "udp"}}
        run = R.camera_run(line, "typical")
        self.assertEqual(R.webrtc_detail(run), "decoder libvpx (software), 290 frames decoded, 3 dropped, 1 freezes (0.6 s), "
                                               "candidate relay/tcp")
        self.assertEqual(R.webrtc_detail(R.camera_run(camera_line("typical", path="hls"), "typical")), "")
        # A camera that needed another session (the S1's heavy, smoke3): said, with its time without video.
        again = camera_line("typical")
        again["streams"][0]["webrtc"] = {"inbound": True, "frames_decoded": 200, "sessions": 1, "no_video_ms": 31000,
                                         "connections": [{"n": 3, "made_ms": 900, "offers": 2, "ice": [[2000, "connected"], [33000, "failed"]]}]}
        self.assertTrue(R.webrtc_detail(R.camera_run(again, "typical")).endswith(
            "1 of 1 streams needed another WebRTC session (no video for 31.0 s in all)"))
        # Counters the browser didn't report read n/a, never 0; a partial sum says so.
        bare = camera_line("heavy")
        for k, st in enumerate(bare["streams"]):
            st["webrtc"] = {"inbound": True, "frames_decoded": 100, "frames_dropped": None if k else 2,
                            "freezes": None, "freeze_s": None, "decoder": None, "power_efficient": None, "candidate": None}
        self.assertEqual(R.webrtc_detail(R.camera_run(bare, "heavy")),
                         "decoder not reported, 400 frames decoded, 2 (from 1 of 4 streams) dropped, n/a freezes (n/a s), candidate n/a")
        # Protocol 5's lines don't name a path: HLS was the only one.
        old = camera_line("typical")
        del old["path"]
        self.assertEqual(R.camera_run(old, "typical")["path"], "hls")


def vitro_camera_line(tier, shown="web_rtc"):
    """VitroClarity's camera line (ClarityCameras.window): each camera played on `shown`; on another kind than the view's
    WebRTC, its WebRTC stream never showed a picture (a fallback)."""
    n = {"typical": 1, "heavy": 4}[tier]
    ok = shown == "web_rtc"
    return {"t": "camera", "stage": tier, "path": "web_rtc", "window_ms": 30000, "fps": 15, "expected": n, "found": n,
            "streams": [{"path": "web_rtc", "entity": f"camera.clarity_cam_{i}", "found": True, "w": None, "h": None,
                         "first_frame_ms": 640.0 if ok else None, "shown_pct": 100.0 if ok else None,
                         "max_gap_ms": 66.7 if ok else None, "stall_total_ms": 0.0 if ok else None,
                         "span_ms": 30000.0 if ok else None, "freezes": 0 if ok else None,
                         "gaps_source": "vitro-frames (freeze rule)" if ok else None, "transport": shown,
                         "others": [] if ok else [{"path": shown, "first_frame_ms": 400.0, "connected": True}],
                         "errors": [] if ok else ["web_rtc: ice failed"]} for i in range(n)],
            "fetches": None}


class VitroCameras(unittest.TestCase):
    """docs/perf.md, "Vitro": Vitro's camera line is read as the web harness's, and says which kind each camera played."""

    def test_cameras_that_played_are_graded_with_nothing_left_out(self):
        run = R.camera_run(vitro_camera_line("heavy"), "heavy")
        self.assertEqual(run["transports"], ["web_rtc"] * 4)
        self.assertNotIn("fell_back", run)
        self.assertEqual(run["uncovered_ms"], 0.0)
        self.assertEqual(G.camera_level(run), "good")
        self.assertEqual(run["detail"][0]["transport"], "web_rtc")
        self.assertEqual(run["detail"][0]["freezes"], 0)

    def test_a_fallback_to_mjpeg_fails_the_task_and_is_said_plainly(self):
        run = R.camera_run(vitro_camera_line("typical", shown="mjpeg"), "typical")
        self.assertEqual(run["fell_back"], ["mjpeg"])
        self.assertIsNone(run["first_frame_ms"])
        self.assertEqual(G.camera_level(run), G.BELOW)
        ref = {"tiers": {"typical": {"camera_runs": [run, R.camera_run(vitro_camera_line("typical"), "typical")]}}}
        self.assertEqual(R.camera_transports(ref), [
            "typical cameras on Vitro: over WebRTC, 0 of 1 and 1 of 1 camera in runs 1 to 2; **fell back to MJPEG, 1 of 1 "
            "and 0 of 1 camera in runs 1 to 2**, graded on WebRTC as on the web engine (web_rtc: ice failed)."])
        self.assertEqual(R.task_phrase("camera", {"camera_runs": [run]}),
                         "a camera fell back to MJPEG on Vitro (it never played over WebRTC)")
        import notes as N
        self.assertEqual(N._camera_options(run), [(10.0, "has a camera that fell back to MJPEG")])

    def test_a_fallback_that_stopped_before_the_windows_end_is_still_said(self):
        line = vitro_camera_line("typical", shown="mjpeg")
        line["streams"][0].update(transport=None, others=[{"path": "mjpeg", "first_frame_ms": 400.0, "connected": False}])
        run = R.camera_run(line, "typical")
        self.assertEqual(run["fell_back"], ["mjpeg"])
        self.assertEqual(R.camera_transports({"tiers": {"typical": {"camera_runs": [run]}}}), [
            "typical cameras on Vitro: over WebRTC, 0 of 1 camera in 1 run; **fell back to MJPEG, 1 of 1 camera in 1 run**, "
            "graded on WebRTC as on the web engine (web_rtc: ice failed)."])

    def test_the_hidden_player_webrtc_replaced_is_no_fallback(self):
        # The graded S1 Vitro run 2: HA's side-by-side start shows HLS until WebRTC has video, so a camera that played
        # WebRTC the whole window has an HLS player with a first frame among its others. That isn't a fallback.
        line = vitro_camera_line("typical")
        line["streams"][0]["others"] = [{"path": "hls", "first_frame_ms": 2900.0, "connected": False}]
        run = R.camera_run(line, "typical")
        self.assertNotIn("fell_back", run)
        self.assertEqual(run["fallbacks"], [None])
        self.assertEqual(R.camera_transports({"tiers": {"typical": {"camera_runs": [run] * 3}}}),
                         ["typical cameras on Vitro: over WebRTC, 1 of 1 camera in each of 3 runs."])

    def test_heavy_reads_camera_by_camera(self):
        # Run 2's heavy: cam_0 to cam_2 on WebRTC (with HLS beside them while they started), cam_3 on HLS only.
        line = vitro_camera_line("heavy")
        for st in line["streams"][:3]:
            st["others"] = [{"path": "hls", "first_frame_ms": 1500.0, "connected": False}]
        line["streams"][3].update(first_frame_ms=None, shown_pct=None, max_gap_ms=None, stall_total_ms=None, transport="hls",
                                  others=[{"path": "hls", "first_frame_ms": 8600.0, "connected": True},
                                          {"path": "mjpeg", "first_frame_ms": None, "connected": False}],
                                  errors=["mjpeg: the stream failed or dropped"])
        run = R.camera_run(line, "heavy")
        self.assertEqual(run["fallbacks"], [None, None, None, "hls"])
        self.assertEqual(run["fell_back"], ["hls"])
        self.assertEqual(R.camera_transports({"tiers": {"heavy": {"camera_runs": [run] * 3}}}), [
            "heavy cameras on Vitro: over WebRTC, 3 of 4 cameras in each of 3 runs; **fell back to HLS, 1 of 4 cameras in "
            "each of 3 runs**, graded on WebRTC as on the web engine (mjpeg: the stream failed or dropped)."])

    def test_a_camera_that_played_webrtc_then_fell_back_is_graded_on_its_webrtc_frames(self):
        # WebRTC from 100 ms, then HLS at the window's end: it fell back, but it played, so it isn't failed outright.
        line = vitro_camera_line("typical")
        line["streams"][0].update(first_frame_ms=100.0, shown_pct=60.0, max_gap_ms=12000.0, stall_total_ms=12000.0,
                                  transport="hls", others=[{"path": "hls", "first_frame_ms": 18100.0, "connected": True}])
        run = R.camera_run(line, "typical")
        self.assertEqual(run["fallbacks"], ["hls"])
        self.assertEqual(run["fell_back"], ["hls"])
        self.assertEqual(run["first_frame_ms"], 100.0)
        self.assertEqual(G.camera_level(run), G.camera_level({k: v for k, v in run.items() if k not in ("fallbacks", "fell_back")}))
        self.assertNotIn("never played", R.task_phrase("camera", {"camera_runs": [run]}))
        import notes as N
        self.assertNotIn("fell back", " ".join(w for _, w in N._camera_options(run)))

    def test_a_stored_run_without_fallbacks_reads_as_it_did(self):
        # regrade.py reads a report.json written before `fallbacks`: they come from its detail by the same rule.
        line = vitro_camera_line("heavy")
        line["streams"][3].update(first_frame_ms=None, shown_pct=None, max_gap_ms=None, stall_total_ms=None,
                                  transport="mjpeg", others=[{"path": "mjpeg", "first_frame_ms": 900.0, "connected": True}])
        run = R.camera_run(line, "heavy")
        old = {k: v for k, v in run.items() if k != "fallbacks"}
        ref = lambda r: {"tiers": {"heavy": {"camera_runs": [r]}}}  # noqa: E731
        self.assertEqual(R.camera_transports(ref(old)), R.camera_transports(ref(run)))
        self.assertIn("**fell back to MJPEG, 1 of 4 cameras in 1 run**", R.camera_transports(ref(old))[0])

    def test_a_camera_that_showed_nothing_is_said(self):
        line = vitro_camera_line("typical", shown="mjpeg")
        line["streams"][0].update(transport=None, others=[])
        run = R.camera_run(line, "typical")
        self.assertNotIn("fell_back", run)
        self.assertEqual(R.camera_transports({"tiers": {"typical": {"camera_runs": [run]}}}), [
            "typical cameras on Vitro: over WebRTC, 0 of 1 camera in 1 run; showing nothing at the window's end, 1 of 1 "
            "camera in 1 run."])

    def test_web_lines_say_nothing_about_transports(self):
        run = R.camera_run(camera_line("typical"), "typical")
        self.assertNotIn("transports", run)
        self.assertEqual(R.camera_transports({"tiers": {"typical": {"camera_runs": [run]}}}), [])


def camera_line(tier, first_ms=600.0, shown=100.0, max_gap=70.0, stall=0.0, path="web_rtc"):
    """The harness's camera line (#174) for a view with streams: each plays at once and in full, over
    WebRTC (protocol 6) unless `path` says otherwise."""
    n = {"typical": 1, "heavy": 4}.get(tier, 0)
    if not n:
        return None
    return {"t": "camera", "stage": tier, "path": path, "window_ms": 20000, "fps": 15, "expected": n, "found": n,
            "streams": [{"found": True, "w": 1280 if i == 0 else 640, "h": 720 if i == 0 else 360, "first_frame_ms": first_ms,
                         "shown_pct": shown, "max_gap_ms": max_gap, "stall_total_ms": stall, "frames": 300, "decoded": 300,
                         "dropped": 0, "waiting": 0, "source": "quality+rvfc"} for i in range(n)]}


def load_frames():
    """Each reference dashboard paints at 0.2, 0.3 and 0.4 s after navigation, then stays still."""
    return [NAV[t] * 1000 + d for t in NAV for d in (200, 300, 400)]


def refstart(k, inp, heavy_missed=20):
    """A graded run: process at 20.0 s, engine ready 0.6 + 0.1k s later. Light and typical scroll
    smoothly; heavy misses `heavy_missed` of every 60 frames (20: about 45 fps, fluid, not smooth)."""
    return {"run": k, "ttid_ms": 111, "launch": "relaunched-by-system", "proc_mono": 20.0, "engine_mono": 20.6 + 0.1 * k,
            "engine_uri": "about:blank", "own_page_start_mono": 23.0, "own_page_stop_mono": 26.0, "own_page_loads": 1,
            "own_page_state": "finished-still",
            "tiers": {"light": tier_entry("light", inp), "typical": tier_entry("typical", inp),
                      "typical-nocam": tier_entry("typical-nocam", inp),
                      "heavy": tier_entry("heavy", inp, scroll_missed=heavy_missed)},
            # Protocol 6: the camera pages' HLS copies after them (#202, detail); heavy's starves.
            "fallback": {"typical-hls": {"done": True, "camera": camera_line("typical", path="hls")},
                         "heavy-hls": {"done": True, "camera": camera_line("heavy", first_ms=3700.0, shown=35.0,
                                                                            max_gap=4600.0, stall=26600.0, path="hls")}},
            "presents_mono_ms": load_frames()}


def idle(label, run, app_pss, cpu_app):
    return {"label": label, "run": run, "window_s": 15, "ncpu": 4,
            "cpu": {"device_busy_pct": 20, "procs_pct_of_core": {PKG: cpu_app, PKG + ":gpu": 1.0, "otbr-agent": 0.5}},
            "frames": {"period_ms": 16.667, "presents_mono_ms": [1000, 2000, 3000]},
            "processes": {
                PKG: {"pid": 1, "role": "main", "pss_kb": app_pss - 100_000, "rss_peak_kb": 300_000, "summary": {"java_heap_kb": 9000}},
                PKG + ":gpu": {"pid": 2, "role": "gpu", "pss_kb": 100_000, "rss_peak_kb": 150_000},
                "otbr-agent": {"pid": 3, "role": "radio", "pss_kb": 2500, "rss_peak_kb": 2500},
            },
            "device_mem": {"mem_total_kb": 2_000_000, "mem_available_kb": 900_000}}


def write_fake_log(d, extra=()):
    """fakeha.jsonl as run.sh leaves it: the frontend's commands, the stream sent on time."""
    with open(os.path.join(d, "fakeha.jsonl"), "w") as f:
        for e in [{"t": 1.0, "type": "auth/current_user", "keys": ["id", "type"], "msg": {"id": 1, "type": "auth/current_user"}},
                  {"t": 1.1, "stream": "S", "k": 1, "late_ms": 0.4}, *extra]:
            f.write(json.dumps(e) + "\n")


class Build(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        dev = {"frame": "test-frame", "model": "Panel", "android": "10", "api": 29, "abi": "armeabi-v7a",
               "cpu": {"cores": 4}, "mem_total_kb": 2_000_000, "display": {"vsync_ms": 16.667, "density": "240"},
               "app": {"package": PKG, "version_name": "0.4.3"}}
        self.write("device.json", dev)
        for k, (inp, js) in enumerate([(120, 9.0), (180, 10.0), (150, 12.0)], 1):
            with open(os.path.join(self.dir, f"synthetic-{k}.jsonl"), "w") as f:
                f.write("\n".join(json.dumps(l) for l in synthetic_lines(k, js)))
            self.write(f"coldstart-{k}.json", {"run": k, "ttid_ms": 1700 + 100 * k, "am_total_ms": None,
                                                "launch": "relaunched-by-system", "stop_mono": 9.5, "proc_mono": 9.6 + 0.1 * k,
                                                "page_start_mono": 10.0, "page_stop_mono": 13.0, "ended_quiet": True,
                                                "presents_mono_ms": [13000, 13100, 13200 + 100 * k, 15000]})
            self.write(f"refstart-{k}.json", refstart(k, inp))
        # Panes alternate on, off, on, off, ...
        for k, (on, off) in enumerate([(600_000, 560_000), (602_000, 559_000), (601_000, 561_000)], 1):
            self.write(f"idle-all-on-{k}.json", idle("all-on", k, on, 10.0 + k * 0.1))
            self.write(f"idle-sendspin-off-{k}.json", idle("sendspin-off", k, off, 8.0))
        self.write("idle-assist-off-1.json", idle("assist-off", 1, 600_500, 10.1))
        write_fake_log(self.dir)
        self.rep = R.build(self.dir)

    def write(self, name, obj):
        with open(os.path.join(self.dir, name), "w") as f:
            json.dump(obj, f)

    def test_schema_and_frame(self):
        self.assertEqual(self.rep["schema"], "glassworks.perf/4")
        self.assertEqual(self.rep["frame"]["id"], "test-frame")
        self.assertEqual(self.rep["frame"]["app"]["geckoview"], "159.0")

    def test_animation_capacity(self):
        lad = self.rep["detail"]["synthetic"]["ladder"]["css-transform"]
        self.assertEqual(lad["held_60fps"]["median"], 4)   # 2 of 120 over budget is 1.7 %: still good
        self.assertEqual(lad["held_30fps"]["median"], 8)
        self.assertFalse(lad["held_60fps_at_top"])
        steps = self.rep["detail"]["synthetic"]["per_run"][0]["ladder"]["css-transform"]["steps"]
        self.assertEqual([st["n"] for st in steps], [1, 2, 4, 8, 16])   # element counts, not frame counts
        self.assertEqual(steps[0]["frames"]["n"], 120)
        svg = self.rep["detail"]["synthetic"]["ladder"]["svg"]
        self.assertEqual(svg["held_60fps"]["median"], 2)
        self.assertTrue(svg["held_60fps_at_top"])   # never missed: a lower bound
        self.assertNotIn("canvas2d", self.rep["detail"]["synthetic"]["ladder"])

    def test_summary_carries_the_engine_uri_and_sf_valid(self):
        ref = self.rep["graded"]["reference"]
        self.assertEqual(ref["engine_uris"], ["about:blank"])
        self.assertEqual(ref["tiers"]["heavy"]["sf_valid"], [True, True, True])

    def test_tasks_per_page(self):
        t = self.rep["graded"]["reference"]["tiers"]
        self.assertEqual(t["heavy"]["scroll_sources"], ["surfaceflinger"])
        self.assertEqual(t["heavy"]["scroll"]["p95_ms"]["median"], 33.33)
        self.assertEqual(t["heavy"]["scroll"]["fps"]["median"], 45.0)
        self.assertEqual(t["heavy"]["scroll_bands"], ["needs-improvement"] * 3)
        self.assertEqual(t["light"]["scroll_band"], "good")
        self.assertEqual(t["light"]["updates_band"], "good")
        self.assertEqual(t["light"]["scrolled_px"]["median"], 2100)   # three swipes of 700 px
        self.assertEqual(t["light"]["cards"], [20])
        self.assertEqual(t["light"]["load_ms"]["median"], 200)        # rubric 2: navigation -> first screen drawn, on screen at 0.2 s
        self.assertEqual(t["light"]["usable_ms"]["median"], 300)      # usable (rubric 1's open): the main thread free at 0.3 s
        self.assertEqual(t["light"]["blocking_ms"]["median"], 40)

    def test_headline_grade(self):
        g = self.rep["grade"]
        self.assertEqual(g["grade"], "B")
        self.assertEqual(g["levels"], {"light": "premium", "typical-nocam": "premium", "typical": "premium", "heavy": "fluid"})
        self.assertEqual(g["bands"]["heavy"], {"tap": "good", "scroll": "fluid", "updates": "premium", "load": "good", "camera": "good", "blocking": "good",
                                               "dialog": "premium"})
        self.assertEqual(g["bands"]["light"]["camera"], "none")              # no streams on light (#174)
        heavy = self.rep["graded"]["reference"]["tiers"]["heavy"]
        self.assertEqual(heavy["camera_levels"], ["good", "good", "good"])
        self.assertEqual(heavy["camera_runs"][0]["detail"][1]["w"], 640)
        self.assertEqual(g["start"], {"value": 800, "band": "good"})   # process 20.0 s -> engine 20.7 to 20.9 s
        self.assertEqual(g["viewport"], {"state": "ok", "reason": None})
        self.assertFalse(g["provisional"])
        self.assertEqual(g["headline"], "test-frame: Clarity B, a good wall panel for typical dashboards, with a camera. "
                                        "Held back by large dashboards: scrolling averages 45.0 fps (p95 33 ms).")
        self.assertEqual(self.rep["graded"]["reference"]["android_ttid_ms"]["median"], 111)   # reported, not graded
        self.assertEqual(self.rep["real_use"]["dashboard_load_ms"]["median"], 3400)            # not graded either

    def test_the_headline_names_what_limits_the_letter(self):
        """smoke5: light scrolled poorly and its load wasn't measured, while large dashboards scrolled at
        about 53 fps. Next to an E, '53 fps' read against the letter; the headline names the limit."""
        for k in (1, 2, 3):
            run = refstart(k, 100)                              # large: about 45 fps, fluid
            run["tiers"]["light"]["swipes"] = [swipe(30.0, i, late(60, 45, 66.67)) for i in range(3)]
            run["tiers"]["typical"]["swipes"] = [swipe(40.0, i, late(60, 45, 66.67)) for i in range(3)]
            run["tiers"]["typical-nocam"]["swipes"] = [swipe(70.0, i, late(60, 45, 66.67)) for i in range(3)]
            run["tiers"]["light"]["page"]["usable"]["first_screen_cards"] = 5   # not all of its first screen drawn: not measured
            self.write(f"refstart-{k}.json", run)
        g = R.build(self.dir)["grade"]
        self.assertEqual(g["grade"], "E")
        self.assertEqual(g["headline"], "test-frame: Clarity E, not a good wall panel, even for light dashboards. Held back by "
                                        "light dashboards: scrolling averages 18.5 fps (p95 67 ms); opening the "
                                        "view wasn't measured. Opening the view on light wasn't measured fully, but that can't "
                                        "change the letter: light already falls short on scrolling.")
        self.assertNotIn("53 fps", g["headline"])

    def test_an_a_says_every_task_holds(self):
        for k in (1, 2, 3):
            self.write(f"refstart-{k}.json", refstart(k, 100, heavy_missed=0))
        g = R.build(self.dir)["grade"]
        self.assertEqual(g["headline"], "test-frame: Clarity A+, premium. Every task holds 60 fps on large dashboards.")

    def test_an_a_short_of_premium_says_what_is_short(self):
        """Heavy scrolls at the good rule (54.5 fps, p95 33 ms) but 10% of its frames are late: an A,
        not premium."""
        for k in (1, 2, 3):
            self.write(f"refstart-{k}.json", refstart(k, 100, heavy_missed=6))
        g = R.build(self.dir)["grade"]
        self.assertEqual(g["grade"], "A")
        self.assertEqual(g["headline"], "test-frame: Clarity A, a good wall panel even for large dashboards. Short of A+ "
                                        "on large dashboards: scrolling averages 54.5 fps (p95 33 ms).")

    def test_the_measured_update_level_reaches_the_grade(self):
        """Typical's changes take about 200 ms to show: over good's 150 ms, so its live updates are fluid, typical is
        fluid and the letter drops from B to C."""
        for k in (1, 2, 3):
            run = refstart(k, 100)
            run["tiers"]["typical"]["step"] = updates_step(NAV["typical"], present_after=200.0)
            self.write(f"refstart-{k}.json", run)
        g = R.build(self.dir)["grade"]
        self.assertEqual((g["bands"]["typical"]["updates"], g["levels"]["typical"], g["grade"]), ("fluid", "fluid", "C"))
        self.assertIn("a change takes 200 ms to show (p95)", g["headline"])

    def test_each_page_is_reported_at_each_place_in_the_order(self):
        for k in (1, 2, 3):
            run = refstart(k, 100)
            run["order"] = (["light", "typical", "heavy"] * 2)[k - 1:k + 2]
            run["warmup"] = {"tier": "light", "done": True}
            self.write(f"refstart-{k}.json", run)
        ref = R.build(self.dir)["graded"]["reference"]
        self.assertEqual(ref["orders"], [["light", "typical", "heavy"], ["typical", "heavy", "light"], ["heavy", "light", "typical"]])
        self.assertEqual(ref["warmups"], [{"tier": "light", "finished": True}] * 3)
        self.assertEqual(ref["tiers"]["light"]["positions"], [1, 3, 2])
        self.assertEqual(sorted(ref["tiers"]["heavy"]["by_position"]), ["1", "2", "3"])
        self.assertEqual(ref["tiers"]["heavy"]["by_position"]["1"][0]["scroll_level"], "fluid")

    def test_four_runs_put_two_in_one_place_of_the_order(self):
        for k in (1, 2, 3, 4):
            run = refstart(k, 100)
            run["order"] = (["light", "typical", "heavy"] * 3)[(k - 1) % 3:(k - 1) % 3 + 3]
            self.write(f"refstart-{k}.json", run)
        rep = R.build(self.dir)
        self.assertEqual(len(rep["graded"]["reference"]["tiers"]["light"]["by_position"]["1"]), 2)
        md = R.markdown(rep)
        self.assertIn("| Page | 1st | 2nd | 3rd |", md)
        light = next(l for l in md.splitlines() if l.startswith("| light | "))
        self.assertEqual(light.split(" | ")[1].count("scroll "), 2)              # runs 1 and 4 both opened light first

    def test_a_failed_run_doesnt_say_an_empty_protocol(self):
        """smoke7's report printed "the pages ran protocol ;" when its only run had failed."""
        for k in (1, 2, 3):
            run = refstart(k, 100)
            run["error"] = "the reference dashboards did not finish within 600 s"
            self.write(f"refstart-{k}.json", run)
        g = R.build(self.dir)["grade"]
        self.assertFalse(any("protocol" in r for r in g["provisional_reasons"]), g["provisional_reasons"])
        self.assertIn("3 of 3 graded runs failed or are missing", g["provisional_reasons"])

    def test_the_grade_does_not_move_with_your_home_assistant(self):
        """Every grade input comes from the reference dashboards; nothing from your own dashboard,
        your panes or your network reaches it, on the real-use side or inside a graded run."""
        before = self.rep["grade"]
        for k in (1, 2, 3):
            self.write(f"coldstart-{k}.json", {"run": k, "ttid_ms": 9000, "launch": "am-start", "proc_mono": 1.0,
                                                "page_start_mono": 2.0, "page_stop_mono": 30.0, "ended_quiet": False,
                                                "presents_mono_ms": [30000 + 16.7 * i for i in range(600)]})
            self.write(f"idle-all-on-{k}.json", idle("all-on", k, 1_900_000, 95.0))
            # Inside the graded run: your dashboard loaded slowly first, reloaded, kept painting (a clock card).
            run = refstart(k, [120, 180, 150][k - 1])
            run.update(own_page_start_mono=21.0, own_page_stop_mono=29.9, own_page_loads=2,
                       own_page_state="finished-moving", ttid_ms=4000)
            run["presents_mono_ms"] = run["presents_mono_ms"] + [21000 + 16.7 * i for i in range(500)]
            self.write(f"refstart-{k}.json", run)
        rep = R.build(self.dir)
        self.assertEqual(rep["real_use"]["dashboard_load_ms"]["median"], 28000)
        self.assertFalse(rep["real_use"]["memory"]["budget"]["ok"])   # the 1.9 GB samples did arrive
        self.assertEqual(rep["grade"], before)                        # a clock card isn't a reason to doubt it

    def test_a_kiosk_page_that_never_finished_keeps_the_letter_and_flags_it(self):
        before = self.rep["grade"]
        for k in (1, 2, 3):
            run = refstart(k, [120, 180, 150][k - 1])
            run.update(own_page_stop_mono=None, own_page_state="never-finished")
            self.write(f"refstart-{k}.json", run)
        g = R.build(self.dir)["grade"]
        for key in ("grade", "meaning", "bands", "levels", "start", "capped_by_start", "missing"):
            self.assertEqual(g[key], before[key], key)
        self.assertTrue(g["provisional"])
        self.assertEqual(g["provisional_reasons"], ["the kiosk's own page never finished loading in a graded run (Home Assistant down?)"])

    def test_a_non_default_zoom_is_not_graded(self):
        """At another zoom each screen holds a different amount of the same dashboard."""
        for k in (1, 2, 3):
            run = refstart(k, 100)
            for t in run["tiers"].values():
                t["page"]["dpr"] = 2.25
            self.write(f"refstart-{k}.json", run)
        rep = R.build(self.dir)
        g = rep["grade"]
        self.assertIsNone(g["grade"])
        self.assertEqual(g["viewport"]["state"], "refused")
        self.assertIn("expected 1.5 (density 240 / 160)", g["refused"])
        self.assertTrue(g["headline"].startswith("test-frame: no Clarity grade."))

    def test_an_unknown_density_makes_it_provisional(self):
        with open(os.path.join(self.dir, "device.json")) as f:
            dev = json.load(f)
        dev["display"].pop("density")
        self.write("device.json", dev)
        g = R.build(self.dir)["grade"]
        self.assertEqual(g["viewport"]["state"], "unknown")
        self.assertTrue(g["provisional"])

    def test_a_page_that_did_not_scroll_is_not_measured(self):
        run = refstart(1, 100)
        run["tiers"]["typical"]["swipes"] = [swipe(40.0, i, late(60, 0), moved=False) for i in range(3)]
        r = R.reference_run(run)["tiers"]["typical"]
        self.assertIsNone(r["scroll"])
        self.assertEqual(r["scroll_error"], "the page didn't scroll")

    def test_an_evicted_swipe_uses_raf_and_the_others_keep_surfaceflinger(self):
        """SurfaceFlinger is decided swipe by swipe, not for the whole run."""
        run = refstart(1, 100)
        run["tiers"]["heavy"]["swipes"][1] = swipe(50.0, 1, late(60, 20), sf=False)
        r = R.reference_run(run)["tiers"]["heavy"]
        self.assertEqual((r["sf_swipes"], r["swipes_moved"], r["raf_only"], r["sf_valid"]), (2, 3, False, False))
        self.assertEqual(r["scroll_source"], "surfaceflinger")

    def test_a_swipe_that_reached_the_end_of_the_page_is_left_out(self):
        """smoke6 and s1v4 (protocol 1): light's page could scroll 380 px, so every swipe ended at the
        edge after about 380 ms, mostly the gesture's slow start. Such a swipe isn't the same flick."""
        run = refstart(1, 100)
        sw = run["tiers"]["light"]["swipes"]
        sw[0] = dict(swipe(30.0, 0, late(20, 12, 50.0)), hit_edge=True)
        r = R.reference_run(run)["tiers"]["light"]
        self.assertEqual((r["swipes_moved"], r["scroll"]["p95_ms"]), (2, 16.67))
        for i in range(3):
            sw[i] = dict(sw[i], hit_edge=True)
        r = R.reference_run(run)["tiers"]["light"]
        self.assertEqual((r["scroll"], r["scroll_error"]), (None, "every swipe reached the end of the page"))

    def test_updates_must_not_break_scroll(self):
        """The live updates keep running while it scrolls, so the scroll level is scroll during the
        updates. A run where no swipe overlapped a change says so and is provisional."""
        run = refstart(1, 100)
        for t in run["tiers"].values():
            t["swipes"] = [dict(sw, changes_during=4) for sw in t["swipes"]]
        r = R.reference_run(run)["tiers"]["heavy"]
        self.assertEqual(r["swipes_during_updates"], 3)
        for k in (1, 2, 3):
            run = refstart(k, 100)
            for t in run["tiers"].values():
                t["swipes"] = [dict(sw, changes_during=0) for sw in t["swipes"]]
            self.write(f"refstart-{k}.json", run)
        g = R.build(self.dir)["grade"]
        self.assertIn("heavy: no swipe ran while the live updates did, so scroll wasn't measured during updates",
                      g["provisional_reasons"])

    def test_scroll_on_raf_only_is_provisional_and_said(self):
        """No swipe had SurfaceFlinger's data: the band can't see frames the compositor dropped."""
        before = self.rep["grade"]
        for k in (1, 2, 3):
            run = refstart(k, 100)
            run["tiers"]["heavy"]["swipes"] = [swipe(50.0, i, late(60, 20), sf=False) for i in range(3)]
            self.write(f"refstart-{k}.json", run)
        rep = R.build(self.dir)
        g = rep["grade"]
        self.assertEqual(g["grade"], before["grade"])
        self.assertTrue(g["provisional"])
        self.assertIn("heavy: scrolling rests on rAF only in 3 run(s); SurfaceFlinger's history didn't cover its swipes",
                      g["provisional_reasons"])
        self.assertIn("heavy: scrolling from rAF only in 3 run(s)", R.markdown(rep))

    def test_bands_are_over_the_runs_attempted(self):
        """Three runs planned, two left a file: the grade is provisional, and says so."""
        self.write("plan.json", {"runs": 3})
        os.remove(os.path.join(self.dir, "refstart-3.json"))
        g = R.build(self.dir)["grade"]
        self.assertTrue(g["provisional"])
        self.assertIn("1 of 3 graded runs failed or are missing", g["provisional_reasons"])

    def test_a_task_measured_in_fewer_runs_is_provisional(self):
        run = refstart(2, 100)
        run["tiers"]["typical"]["swipes"] = [swipe(40.0, i, late(60, 0), moved=False) for i in range(3)]
        self.write("refstart-2.json", run)
        g = R.build(self.dir)["grade"]
        self.assertIn("typical: measured in fewer runs than attempted (scroll 2 of 3)", g["provisional_reasons"])

    def test_a_run_that_ended_in_an_error_is_not_graded(self):
        before = self.rep["grade"]
        bad = refstart(4, 900, heavy_missed=50)
        bad.update(error="the kiosk loaded another page while a reference dashboard was measured", engine_mono=29.0)
        self.write("refstart-4.json", bad)
        rep = R.build(self.dir)
        ref = rep["graded"]["reference"]
        self.assertEqual((ref["runs"], ref["graded_runs"], len(ref["errors"])), (4, 3, 1))
        self.assertEqual(rep["grade"]["bands"], before["bands"])
        self.assertEqual(rep["grade"]["start"], before["start"])

    def test_scroll_is_banded_per_run_then_the_median_band(self):
        """Three runs, each poor for a different reason. Per-measure medians would read needs-improvement."""
        runs = []
        for k, intervals in enumerate([late(60, 6, 50.0), late(60, 36), late(60, 1, 800.0)], 1):
            run = refstart(k, 100)
            run["tiers"]["heavy"]["swipes"] = [swipe(50.0, 0, intervals, sf=False)]
            runs.append(R.reference_run(run))
        ref = R.reference_summary(runs)
        self.assertEqual(ref["tiers"]["heavy"]["scroll_bands"], ["poor", "poor", "poor"])
        self.assertEqual(ref["tiers"]["heavy"]["scroll_band"], "poor")
        medians = {k: ref["tiers"]["heavy"]["scroll"][k]["median"] for k in ("p95_ms", "jank_pct", "frozen_pct")}
        self.assertEqual(G.frame_band(dict(medians, n=60)), "needs-improvement")   # what per-measure medians would say

    def test_median_band_of_an_even_count_is_the_worse_middle(self):
        self.assertEqual(R.median_band(["good", "poor"]), "poor")
        self.assertEqual(R.median_band(["good", "good", "needs-improvement", "poor"]), "needs-improvement")

    def test_start_needs_about_blank_as_the_engines_first_page(self):
        run = refstart(1, 100)
        run["engine_uri"] = "http://192.0.2.10:8123/lovelace"
        r = R.reference_run(run)
        self.assertIsNone(r["start_ms"])
        self.assertIn("not about:blank", r["start_error"])
        run["engine_uri"] = "about:blank"
        self.assertEqual(R.reference_run(run)["start_ms"], 700)

    def test_grade_inputs_come_only_from_the_reference_dashboards(self):
        tiers, start = R.grade_inputs(self.rep["graded"])
        self.assertEqual(G.grade(tiers, start)["grade"], self.rep["grade"]["grade"])
        self.assertEqual(set(self.rep["graded"]), {"reference"})
        self.assertNotIn("real_use", R.grade_inputs.__code__.co_names)

    def test_no_reference_dashboards_is_not_graded(self):
        """--skip-reference: nothing was measured, so no letter (not a machine-readable E), and the
        summary says it isn't comparable."""
        for k in (1, 2, 3):
            os.remove(os.path.join(self.dir, f"refstart-{k}.json"))
        rep = R.build(self.dir)
        g = rep["grade"]
        self.assertIsNone(g["grade"])
        self.assertEqual(g["refused"], "the reference dashboards weren't measured")
        self.assertTrue(g["headline"].startswith("test-frame: no Clarity grade."))
        s = S.summarise(rep, allow_provisional=True)
        self.assertEqual(s["not_comparable"], "the reference dashboards weren't measured")
        self.assertEqual(G.grade_summary(s)["status"], "not-comparable")

    def test_one_lost_page_doesnt_make_the_protocol_unknown(self):
        """A lost page has no protocol; it mustn't turn a measured frame into "re-measure"."""
        run = refstart(2, 180)
        run["tiers"]["typical"] = {"nonce": "typical", "swipes": [], "error": "the page didn't finish"}   # nothing arrived
        self.write("refstart-2.json", run)
        rep = R.build(self.dir)
        self.assertEqual(rep["graded"]["reference"]["protocols"], [G.PROTOCOL_VERSION])
        self.assertEqual(rep["protocol_version"], G.PROTOCOL_VERSION)
        s = S.summarise(rep, allow_provisional=True)
        self.assertEqual(G.grade_summary(s)["status"], "graded")

    def test_an_a_plus_with_pages_missing_doesnt_claim_every_task(self):
        for k in (1, 2, 3):
            run = refstart(k, 100, heavy_missed=0)
            del run["tiers"]["light"], run["tiers"]["typical"]
            self.write(f"refstart-{k}.json", run)
        g = R.build(self.dir)["grade"]
        self.assertEqual(g["grade"], "A+")                     # the rule: A+ needs only heavy
        self.assertNotIn("Every task holds", g["headline"])
        # The missing pages can't change it: not provisional, and the headline says what is missing and why.
        self.assertFalse(g["provisional"])
        self.assertNotIn("Provisional", g["headline"])
        self.assertIn("weren't measured fully, but that can't change the letter: A+ needs only heavy.", g["headline"])

    def build_with(self, measured_on=None, **page):
        if measured_on:
            with open(os.path.join(self.dir, "device.json")) as f:
                dev = json.load(f)
            self.write("device.json", dict(dev, measured_on=measured_on))
        for k in (1, 2, 3):
            run = refstart(k, 100)
            for t in run["tiers"].values():
                t["page"].update(page)
                if page.get("clarity", 0) is None:
                    del t["page"]["clarity"]
            self.write(f"refstart-{k}.json", run)
        return R.build(self.dir)

    def test_pre_release_data_says_re_measure_and_is_never_v1(self):
        """Clarity v1 (2026-11-14) starts its numbers again, so a page line without `clarity` is
        pre-release whatever its protocol: provisional here, "re-measure" on the site."""
        for proto in (7, 4, 1):                       # 1: pre-release protocol 1, which v1's number repeats
            with self.subTest(protocol=proto):
                rep = self.build_with(protocol=proto, clarity=None)
                g = rep["grade"]
                self.assertEqual(g["grade"], "B")                                   # kept for the record
                self.assertTrue(g["provisional"])
                self.assertIn(f"measured with a pre-release version of Clarity (protocol {proto}): re-measure", g["provisional_reasons"])
                self.assertIn("Re-measure to publish: the site shows no letter for these pages.", g["headline"])
                self.assertIsNone(rep["clarity"])
                s = S.summarise(rep, allow_provisional=True)
                self.assertIsNone(s["clarity"])
                self.assertEqual(G.grade_summary(s)["status"], "re-measure")

    def test_v1s_release_candidate_reads_as_v1(self):
        """Pre-release protocol 8 (#205's s1rtc5, before the lines carried `clarity`) is v1's measurement,
        but only its own lines, which carry the round's usable.busy_ms (the one bypass of the marker)."""
        self.assertEqual(R.page_version({"protocol": 8, "usable": {"busy_ms": [12, 0]}}), (1, 1))      # v1's
        self.assertEqual(R.page_version({"protocol": 8, "usable": {"interactive_ms": 1}}), (8, None))
        self.assertEqual(R.page_version({"protocol": 8}), (8, None))
        self.assertEqual(R.page_version({"protocol": 1, "clarity": 1}), (1, 1))
        self.assertEqual(R.page_version({"protocol": 1}), (1, None))          # pre-release protocol 1
        # The earlier v3's pages measured what v1 measures now (2026-11-19): read as v1. The earlier v2's didn't.
        self.assertEqual(R.page_version({"protocol": 3, "clarity": 3}), (1, 1))
        self.assertEqual(R.page_version({"protocol": 2, "clarity": 2}), (2, 2))
        self.assertTrue(R.is_current({"protocols": [G.PROTOCOL_VERSION], "clarity": [G.CLARITY]}))
        self.assertTrue(R.is_current({"protocols": [1], "clarity": [1]}, "2026-11-20"))
        self.assertFalse(R.is_current({"protocols": [1], "clarity": [1]}, "2026-11-14"))        # the first v1
        self.assertFalse(R.is_current({"protocols": [2], "clarity": [2]}, "2026-11-20"))        # the earlier v2
        self.assertFalse(R.is_current({"protocols": [G.PROTOCOL_VERSION], "clarity": [None]}))
        rep = self.build_with(protocol=8, clarity=None)                       # the fixture's lines have no busy_ms
        self.assertIsNone(rep["clarity"])
        self.assertIn("measured with a pre-release version of Clarity (protocol 8): re-measure", rep["grade"]["provisional_reasons"])

    def test_v1_with_another_protocol_number_is_another_protocol(self):
        rep = self.build_with(protocol=G.PROTOCOL_VERSION + 1, clarity=G.CLARITY)
        self.assertIn(f"the pages ran protocol {G.PROTOCOL_VERSION + 1}; this rubric is for protocol {G.PROTOCOL_VERSION}",
                      rep["grade"]["provisional_reasons"])
        self.assertEqual(rep["clarity"], G.CLARITY)          # as the pages recorded it; the protocol says the rest

    def test_the_first_v1s_run_is_re_measured(self):
        """The numbers started again at 1 on 2026-11-19, as on 2026-11-14: a run of the first v1 (its pages said
        `clarity: 1`, protocol 1, measured 2026-11-14) says so, in the report and in both graders from its summary."""
        rep = self.build_with(measured_on="2026-11-14", protocol=1, clarity=1)
        self.assertEqual((rep["clarity"], rep["protocol_version"]), (1, 1))
        why = f"{G.FIRST_V1}: re-measure"
        self.assertIn(why, rep["grade"]["provisional_reasons"])
        self.assertIn("Re-measure to publish", rep["grade"]["headline"])
        s = S.summarise(rep, allow_provisional=True)
        self.assertEqual((s["clarity"], s["protocol_version"], s["measured_on"]), (1, 1, "2026-11-14"))
        for g in (G.grade_summary(s), self.ts_mod().ts_grades([s])[0]):
            self.assertEqual((g["status"], g["grade"], g["why"]), ("re-measure", None, why))

    def test_the_earlier_v2s_run_is_re_measured(self):
        rep = self.build_with(measured_on="2026-11-15", protocol=2, clarity=2)
        why = f"measured with Clarity v2; these rules are Clarity v{G.CLARITY}'s: re-measure"
        self.assertIn(why, rep["grade"]["provisional_reasons"])
        s = S.summarise(rep, allow_provisional=True)
        for g in (G.grade_summary(s), self.ts_mod().ts_grades([s])[0]):
            self.assertEqual((g["status"], g["grade"], g["why"]), ("re-measure", None, why))

    def test_the_earlier_v3s_run_is_v1(self):
        """Its pages (protocol 3, clarity 3) measured what v1 measures: rebuilt, the run is v1's and graded by v1."""
        rep = self.build_with(measured_on="2026-11-19", protocol=3, clarity=3)
        self.assertEqual((rep["clarity"], rep["protocol_version"]), (1, 1))
        self.assertNotIn("Re-measure", rep["grade"]["headline"])
        s = S.summarise(rep, allow_provisional=True)
        self.assertEqual(G.grade_summary(s)["status"], "graded")

    @staticmethod
    def ts_mod():
        import importlib.util
        spec = importlib.util.spec_from_file_location("gw_test_summary", os.path.join(os.path.dirname(os.path.abspath(__file__)), "test_summary.py"))
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod

    def test_this_clarity_is_this_clarity(self):
        self.assertEqual((self.rep["clarity"], self.rep["protocol_version"]), (G.CLARITY, G.PROTOCOL_VERSION))
        self.assertEqual((G.CLARITY, G.PROTOCOL_VERSION, G.RUBRIC_VERSION), (1, 1, 2))   # Clarity v1, Glassworks 1.0.0; rubric 2: no_touch

    def test_js_band_is_reported_but_not_graded(self):
        self.assertEqual(self.rep["detail"]["synthetic"]["js_burst_ms"]["median"], 10.0)
        self.assertEqual(self.rep["detail"]["synthetic"]["js_band"], "good")
        self.assertTrue(self.rep["detail"]["synthetic"]["js_checksum_ok"])
        self.assertNotIn("js", self.rep["grade"]["bands"])

    def test_memory_and_budget(self):
        m = self.rep["real_use"]["memory"]
        self.assertEqual(m["idle"]["app_pss_kb"]["median"], 601_000)
        self.assertEqual(m["idle"]["by_role_pss_kb"]["gpu"]["median"], 100_000)
        self.assertEqual(m["peak"]["app_pss_kb"], 602_000)
        self.assertTrue(m["budget"]["ok"])

    def test_pane_diffs_against_all_on(self):
        panes = {p["id"]: p for p in self.rep["real_use"]["panes"]}
        self.assertEqual(panes["sendspin"]["pss_kb"], 41_000)
        self.assertTrue(panes["sendspin"]["pss_measurable"])
        self.assertEqual(panes["assist"]["pss_kb"], 500)
        self.assertFalse(panes["assist"]["pss_measurable"])   # inside the 2 MB spread of the all-on runs
        self.assertEqual(panes["radio"]["method"], "process")
        self.assertEqual(panes["radio"]["pss_kb"], 2500)

    def test_pane_diffs_leave_out_a_sample_the_screen_went_off_in(self):
        self.write("idle-all-on-9.json", dict(idle("all-on", 9, 900_000, 1.0), screen_off=[12.0]))
        self.write("idle-sendspin-off-9.json", dict(idle("sendspin-off", 9, 100_000, 1.0), screen_off=[12.0]))
        panes = {p["id"]: p for p in R.build(self.dir)["real_use"]["panes"]}
        self.assertEqual((panes["sendspin"]["pss_kb"], panes["sendspin"]["samples_on"], panes["sendspin"]["samples_off"]),
                         (41_000, 3, 3))

    def test_a_sample_without_app_processes_is_not_zero_memory(self):
        empty = idle("all-on", 9, 600_000, 10.0)
        empty["processes"] = {}
        empty["device_mem"] = {"mem_total_kb": 2_000_000, "mem_available_kb": None}
        self.write("idle-all-on-9.json", empty)
        self.write("idle-sendspin-off-9.json", dict(empty, label="sendspin-off"))
        rep = R.build(self.dir)
        self.assertEqual(rep["real_use"]["memory"]["idle"]["app_pss_kb"]["n"], 3)
        self.assertEqual(rep["real_use"]["memory"]["idle"]["device_used_pct"]["n"], 3)
        self.assertEqual(rep["real_use"]["memory"]["peak"]["app_pss_kb"], 602_000)
        self.assertEqual({p["id"]: p for p in rep["real_use"]["panes"]}["sendspin"]["samples_off"], 3)

    def test_a_measured_zero_is_the_lowest_available_memory(self):
        zero = idle("all-on", 9, 600_000, 10.0)
        zero["device_mem"] = {"mem_total_kb": 2_000_000, "mem_available_kb": 0}
        self.write("idle-all-on-9.json", zero)
        rep = R.build(self.dir)
        self.assertEqual(rep["real_use"]["memory"]["peak"]["mem_available_min_kb"], 0)
        self.assertFalse(rep["real_use"]["memory"]["budget"]["checks"]["device_available_min"]["ok"])

    def test_a_ladder_step_without_surfaceflinger_is_counted(self):
        lines = synthetic_lines(1, 9.0)
        lines[2]["sf"]["oldest_ms"] = 99_999
        self.assertEqual(R.synthetic_summary([R.synthetic_run(lines)])["steps_without_sf"], 1)

    def test_no_scroll_step_is_not_a_moved_scroll(self):
        lines = [l for l in synthetic_lines(1, 9.0) if l.get("stage") != "scroll"]
        self.assertIsNone(R.synthetic_summary([R.synthetic_run(lines)])["scroll_moved"])

    def test_a_scroll_that_did_not_move_is_flagged(self):
        s = self.rep["detail"]["synthetic"]
        self.assertFalse(s["scroll_moved"])
        self.assertEqual(s["per_run"][0]["scroll"]["scrolled_px"], 720)
        self.assertIn("did not move", R.markdown(self.rep))

    def test_load_that_fell_back_to_fcp_says_so(self):
        self.assertEqual(self.rep["detail"]["synthetic"]["page_lcp_from"], ["fcp", "lcp"])
        self.assertIn("FCP/LCP", R.markdown(self.rep))

    def test_cold_start_records_the_launch_and_the_whole_restart(self):
        r = self.rep["real_use"]
        self.assertEqual(r["launch"], ["relaunched-by-system"])
        self.assertEqual(r["stop_to_dashboard_ms"]["median"], 3900)   # stopped at 9.5 s, settled at 13.4 s

    def test_a_failed_cold_start_is_counted_not_graded(self):
        self.write("coldstart-4.json", {"run": 4, "ttid_ms": 900, "launch": "relaunched-by-system",
                                        "error": "dashboard did not finish loading within 120 s (no GeckoView:PageStop)"})
        r = R.build(self.dir)["real_use"]
        self.assertEqual(len(r["errors"]), 1)
        self.assertEqual(r["dashboard_load_ms"]["n"], 3)
        self.assertEqual(r["android_ttid_ms"], {"median": 1900, "min": 1800, "max": 2000, "n": 3})
        self.assertEqual(r["app_start_to_dashboard_ms"]["n"], 3)
        self.assertEqual(r["per_run"][-1]["ttid_ms"], 900)   # kept for diagnosis

    def test_apk_sizes_are_kept_only_when_asked(self):
        with open(os.path.join(self.dir, "report.json"), "w") as f:
            json.dump({"apk": [{"file": "old.apk"}]}, f)
        for keep, want in ((False, []), (True, [{"file": "old.apk"}])):
            with open(os.path.join(self.dir, "report.json"), "w") as f:
                json.dump({"apk": [{"file": "old.apk"}]}, f)
            subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "report.py"), self.dir]
                           + (["--keep-apk"] if keep else []), check=True, capture_output=True)
            with open(os.path.join(self.dir, "report.json")) as f:
                self.assertEqual(json.load(f)["apk"], want)

    def test_no_real_use_section_without_real_use_samples(self):
        d = tempfile.mkdtemp()
        for name in ("device.json", "synthetic-1.jsonl"):
            with open(os.path.join(self.dir, name)) as f, open(os.path.join(d, name), "w") as g:
                g.write(f.read())
        self.write_to(d, "idle-after-synthetic-1.json", idle("after-synthetic", 1, 500_000, 10.0))
        md = R.markdown(R.build(d))
        self.assertNotIn("## Real use", md)
        self.assertIn("## Memory budget", md)

    def write_to(self, d, name, obj):
        with open(os.path.join(d, name), "w") as f:
            json.dump(obj, f)

    def test_markdown_renders(self):
        md = R.markdown(self.rep)
        self.assertIn("**test-frame: Clarity B, a good wall panel for typical dashboards, with a camera.", md)
        self.assertIn("| heavy (120 cards) | fluid |", md)
        self.assertIn("| scroll |", md)                                 # what holds heavy back from smooth
        self.assertIn("Restart, app process to engine ready: 800 ms (700 to 900, n=3)", md)
        self.assertIn("zoom check: ok", md)
        self.assertIn("## Real use on this dashboard (not graded)", md)
        self.assertLess(md.index("| light ("), md.index("## Detail: animations"))
        self.assertLess(md.index("## Detail: animations"), md.index("## Real use on this dashboard"))
        self.assertIn("| css-transform | 4 (4 to 4, n=3) |", md)
        # Clarity v3 (#211): the more-info dialogs, a column and a detail line.
        self.assertIn("| More-info dialog |", md)
        self.assertIn("settled in 320 ms (320 to 320, n=3), premium", md)
        self.assertIn("heavy: more-info dialogs (detail): the first frame of the open 10 ms", md)

    def test_apk_sizes_per_abi(self):
        apk = os.path.join(self.dir, "app.apk")
        with zipfile.ZipFile(apk, "w") as z:
            z.writestr("classes.dex", b"x" * 100)
            z.writestr("lib/armeabi-v7a/libxul.so", b"y" * 300)
            z.writestr("assets/extension/content.js", b"z" * 50)
        out = R.apk_sizes([apk])
        self.assertEqual(out[0]["abis"], ["armeabi-v7a"])
        self.assertEqual(out[0]["parts_bytes"]["native"], 300)



class Usable(unittest.TestCase):
    """Protocol 4: open the view and restart end at the first frame on screen that can carry the
    render in which every first-screen card was drawn, and never before the main thread was free."""
    # The page's clock: navigation start is at 98 000 ms on logcat's.
    page = {"mono": 100.0, "epoch_ms": 1_000_000.0, "origin_epoch_ms": 998_000.0,
            "usable": {"rendered_tick_ms": 1500.0, "first_screen_cards": 13, "interactive_ms": 1510.0}}

    def test_the_first_frame_that_can_carry_the_render(self):
        # Rendered at 99 500: a frame presented sooner than a refresh later (less 3 ms) was already on its way.
        when, why = R.usable_on_screen(self.page, [99_505, 99_510, 99_520, 99_540])
        self.assertEqual((when, why), (99_520, None))

    def test_never_before_the_main_thread_is_free(self):
        page = dict(self.page, usable=dict(self.page["usable"], interactive_ms=1600.0))
        self.assertEqual(R.usable_on_screen(page, [99_520]), (99_600, None))

    def test_the_restart_ends_on_the_start_view(self):
        """The kiosk was pointed at the start view; coming back to any other view isn't the restart."""
        run = lambda tier: R.reference_run({"proc_mono": 90.0, "restart_page": dict(self.page, tier=tier),  # noqa: E731
                                            "presents_mono_ms": [99_520]})
        self.assertEqual((run("start")["start_ms"], run("start")["start_error"]), (9520, None))
        self.assertIsNone(run("light")["start_ms"])
        self.assertIn("the 'light' view, not 'start'", run("light")["start_error"])

    def test_a_restart_measured_in_fewer_runs_is_provisional(self):
        ref = {"graded_runs": 3, "start_ms": {"n": 2}, "tiers": {}}
        self.assertIn("restart: measured in fewer runs than attempted (2 of 3)", R.coverage_reasons(ref, 3))
        self.assertEqual(R.coverage_reasons(dict(ref, start_ms={"n": 3}), 3), [])

    def test_not_measured_without_a_frame_or_a_usable_line(self):
        self.assertEqual(R.usable_on_screen(self.page, [99_505])[1], "no frame on screen after the dashboard rendered")
        self.assertEqual(R.usable_on_screen(dict(self.page, usable=None), [99_520])[1], "the page didn't report when it was usable")


class SentToScreen(unittest.TestCase):
    """Protocol 4: change to screen runs from when the fake sent the change, so a main thread too busy
    to read it counts; one it never read in the window counts as not shown by the window's end."""
    page = {"mono": 100.0, "epoch_ms": 1_000_000.0, "origin_epoch_ms": 998_000.0}     # page t -> 98 000 + t

    def step(self, changes):
        # Window t0 = 1000 on the page's clock: 99 000 to 103 000 on logcat's; a frame every 16.7 ms.
        pres = [99_000 + 16.7 * i for i in range(240)]
        return {"t0": 1000.0, "window_ms": 4000, "from": "sent", "changes": changes, "raf_times": [16.7 * i for i in range(240)],
                "sf": {"presents_ms": pres, "period_ms": 16.7, "window_ms": [99_000, 103_000], "oldest_ms": 98_000}}

    def test_waiting_for_the_main_thread_counts(self):
        # Sent at 100, read at 900 (the main thread was busy), drawn in the frame at 910.
        lat, why = R.change_to_screen(self.step([[100.0, 900.0, 0, 0, 910.0]]), self.page)
        self.assertIsNone(why)
        self.assertGreaterEqual(lat["max_ms"], 800 + 16.7 - 3)

    def test_one_never_read_counts_to_the_windows_end(self):
        lat, _ = R.change_to_screen(self.step([[100.0, 110.0, 0, 0, 120.0], [1000.0, None, 0, 0, None]]), self.page)
        self.assertEqual(lat["n"], 2)
        self.assertEqual(lat["max_ms"], 3000.0)                         # 103 000 - 100 000
        a = R.arrivals(self.step([[100.0, 110.0, 0, 0, 120.0], [1000.0, None, 0, 0, None]]))
        self.assertEqual((a["sent"], a["arrived"], a["wait_max_ms"]), (2, 1, 10.0))

    def test_a_change_no_frame_showed_never_passes_fluid(self):
        """CodeRabbit on #656: a change with no frame by the window's end counts as the time to the window's end, a lower
        bound. It counts from 250 ms before the end, as before, but never at fluid's bar (inclusive): at least 250.1."""
        fluid = {lv["level"]: lv for lv in G.UPDATE_LEVELS}["fluid"]["latency_p95_max_ms"]
        self.assertEqual((fluid, R.NO_FRAME_MS, R.UNSHOWN_FLOOR_MS), (250, 250, 250.1))
        # Sent 249, 250 and 260 ms before the window's end (page t 3751, 3750, 3740), never read: the first isn't
        # counted; the second counts as 250.1, past fluid; the third as 260. Each beside a change shown 30 ms after it.
        shown = [100.0, 110.0, 0, 0, 120.0]
        lat, _ = R.change_to_screen(self.step([shown, [3751.0, None, 0, 0, None]]), self.page)
        self.assertEqual((lat["n"], lat["skipped"]), (1, 1))
        lat, _ = R.change_to_screen(self.step([shown, [3750.0, None, 0, 0, None]]), self.page)
        self.assertEqual((lat["n"], lat["p95_ms"], lat["unshown_floor_ms"]), (2, 250.1, 250.1))
        self.assertEqual(G.update_level({"latency_n": 2, "latency_p95_ms": lat["p95_ms"], "frames": None}), G.BELOW)
        lat, _ = R.change_to_screen(self.step([shown, [3740.0, None, 0, 0, None]]), self.page)
        self.assertEqual((lat["n"], lat["p95_ms"]), (2, 260.0))
        # A change a frame showed at exactly 250 ms still passes fluid.
        self.assertEqual(G.update_level({"latency_n": 2, "latency_p95_ms": 250.0, "frames": None}), "fluid")

    def test_it_is_never_skipped_for_updates_that_didnt_arrive(self):
        """smoke9's heavy: "only 17 of 20 state updates ran" made the page n/a. In protocol 4 the
        schedule says what was sent; the rest is the panel's to answer for."""
        why, _ = R.live_check_failed({"offscreen": 0, "moving": 8, "targets": 12, "update_every_ms": 200},
                                     self.step([[100.0 * k, None, 0, 0, None] for k in range(1, 20)]))
        self.assertIsNone(why)


class FakeLog(unittest.TestCase):
    """F5: the report reads the fake Home Assistant's log and is provisional on anything wrong."""

    def log(self, *entries):
        d = tempfile.mkdtemp()
        write_fake_log(d, entries)
        return R.fake_ha_log(os.path.join(d, "fakeha.jsonl"))

    def test_a_clean_log(self):
        log = self.log()
        self.assertEqual((log["commands"], log["unknown"], log["failed"]), (1, {}, {}))
        self.assertEqual(R.fake_ha_reasons(log, True), [])

    def test_unknown_failed_or_late(self):
        log = self.log({"t": 2, "unknown": "energy/get_prefs", "msg": {}}, {"t": 3, "failed": "history/stream", "error": "x"},
                       {"t": 4, "stream": "S", "k": 2, "late_ms": 250.0})
        reasons = R.fake_ha_reasons(log, True)
        self.assertEqual(len(reasons), 3)
        self.assertIn("energy/get_prefs (1)", reasons[0])
        self.assertIn("history/stream (1)", reasons[1])
        self.assertIn("250 ms late", reasons[2])
        # smoke10's 92 ms isn't a reason: each change is timed from when it was actually sent.
        self.assertEqual(R.fake_ha_reasons(self.log({"t": 4, "stream": "S", "k": 2, "late_ms": 92.0}), True), [])

    def test_only_a_graded_views_late_stream_makes_it_provisional(self):
        """s1rtc8: one 2 s burst of late sends, all on typical-hls (an HLS copy, not graded), made the
        graded result provisional. Each send belongs to the view open then; a copy's or a warm-up's
        lateness is detail. Only which streams the check reads changes, not its 200 ms."""
        log = self.log({"t": 2, "open": {"view": "light", "nonce": "a1"}}, {"t": 3, "stream": "S", "k": 1, "late_ms": 40.0},
                       {"t": 4, "open": {"view": "typical-hls", "nonce": "a2"}}, {"t": 5, "stream": "S", "k": 1, "late_ms": 1952.0},
                       {"t": 6, "open": {"view": "light", "nonce": "a3", "warmup": "1"}}, {"t": 7, "stream": "F", "k": 1, "late_ms": 900.0})
        self.assertEqual((log["stream_late_max_ms"], log["stream_late_detail_max_ms"], log["stream_sent"]), (40.0, 1952.0, 4))  # and the fixture's own
        self.assertEqual(R.fake_ha_reasons(log, True), [])
        # A whole run: the copy's burst is a line in report.md, and the grade isn't provisional for it.
        b = Build()
        b.setUp()
        write_fake_log(b.dir, [{"t": 2, "open": {"view": "typical-hls", "nonce": "a2"}},
                               {"t": 5, "stream": "S", "k": 1, "late_ms": 1952.0}])
        rep = R.build(b.dir)
        self.assertFalse(any("late" in r for r in rep["grade"].get("provisional_reasons") or []))
        self.assertIn("The fake Home Assistant sent a live change 1952 ms late during a view that isn't graded", R.markdown(rep))
        # The same burst on a graded view is a reason, as before.
        log = self.log({"t": 2, "open": {"view": "heavy", "nonce": "b1"}}, {"t": 3, "stream": "S", "k": 1, "late_ms": 1952.0})
        self.assertIn("the fake Home Assistant sent a live change 1952 ms late (more than 200 ms)", R.fake_ha_reasons(log, True))

    def test_protocol_4_without_the_log_is_provisional(self):
        self.assertEqual(R.fake_ha_reasons(None, True), ["the fake Home Assistant's log (fakeha.jsonl) is missing"])
        self.assertEqual(R.fake_ha_reasons(None, False), [])

    def test_a_budgets_file_that_isnt_there_is_an_error(self):
        """F9: --budgets naming a missing file must not fall back to the defaults silently."""
        with self.assertRaises(SystemExit):
            R.load_budgets("aqara-s1-plus", "/nonexistent/budgets.json")
        self.assertIn("assist", R.load_budgets("aqara-s1-plus")["panes"])       # the frame's own file

    def test_a_tested_frame_has_its_base_frames_budgets(self):
        """#23: the Pixel Tablet runs Glassworks for generic Android, so its report checks that app's panes."""
        tablet = R.load_budgets("google-pixel-tablet")
        self.assertEqual(tablet, R.load_budgets("generic-android"))
        self.assertEqual(tablet["panes"]["camera"]["ram_pss_max_kb"], 49152)
        tmp = tempfile.mkdtemp()
        for fid, files in {"base": {"budgets.json": {}}, "own": {"budgets.json": {}, "design/frame.json": {"base": "base"}},
                           "tested": {"design/frame.json": {"base": "base"}}, "orphan": {"design/frame.json": {"base": "nowhere"}},
                           "plain": {"design/frame.json": {}}}.items():
            for name, body in files.items():
                os.makedirs(os.path.dirname(os.path.join(tmp, fid, name)), exist_ok=True)
                with open(os.path.join(tmp, fid, name), "w") as f:
                    json.dump(body, f)
        for fid, want in {"own": "own", "tested": "base", "orphan": None, "plain": None, "missing": None}.items():
            got = P.frame_budgets_path(tmp, fid)
            self.assertEqual(got, want and os.path.join(tmp, want, "budgets.json"), fid)

    def test_the_head_must_be_on_the_first_screen(self):
        page = dict(Usable.page, usable=dict(Usable.page["usable"], first_screen_cards=8, head_cards=10))
        self.assertIn("fewer than the 10 head cards", R.usable_on_screen(page, [99_520])[1])


def ram_idle(label, main_kb, cpu=1.0):
    pkg = "sh.edm.s1kiosk"
    return {"label": label, "window_s": 15,
            "processes": {pkg: {"pid": 1, "role": "main", "pss_kb": main_kb, "rss_peak_kb": main_kb},
                          pkg + ":gpu_process": {"pid": 2, "role": "gpu", "pss_kb": 200_000, "rss_peak_kb": 250_000}},
            "device_mem": {"mem_total_kb": 2_000_000, "mem_available_kb": 900_000},
            "cpu": {"procs_pct_of_core": {pkg: cpu}, "device_busy_pct": 10}}


def with_boot(sample, boot_kb=29_000):
    s = dict(sample, processes=dict(sample["processes"]))
    s["processes"]["sh.edm.s1kiosk:boot"] = {"pid": 3, "role": "boot", "pss_kb": boot_kb, "rss_peak_kb": boot_kb}
    return s


class NoiseBand(unittest.TestCase):
    """docs/perf.md's claims for perflib.noise_band, by simulation (seeded, normal noise, σ = 1)."""

    def sim(self, n, draws=4000):
        import random
        rnd = random.Random(186)
        bands, low, false = [], 0, 0
        for _ in range(draws):
            a, b = [rnd.gauss(0, 1) for _ in range(n)], [rnd.gauss(0, 1) for _ in range(n)]
            ba, bb = P.noise_band(a), P.noise_band(b)
            bands.append(ba)
            low += ba < 0.2
            false += abs(statistics.median(a) - statistics.median(b)) > max(ba, bb)
        return statistics.mean(bands), low / draws, false / draws

    def test_three_samples(self):
        mean, low, false = self.sim(3)
        self.assertAlmostEqual(mean, 2.0, delta=0.1)       # about 2 σ of one sample
        self.assertLess(low, 0.02)                         # it rarely collapses (the MAD: 11 %)
        self.assertLess(false, 0.06)                       # a zero difference reads measurable about 3.5 % of the time

    def test_five_samples(self):
        """The verifier on #196: the MAD at five averaged 1.64 σ (5% false "measurable");
        range / d2 runs through seven."""
        mean, low, false = self.sim(5)
        self.assertAlmostEqual(mean, 2.0, delta=0.1)
        self.assertLess(false, 0.03)

    def test_it_doesnt_grow_with_more_samples(self):
        self.assertLess(self.sim(9)[0], self.sim(3)[0])

    def test_under_two_samples_there_is_none(self):
        self.assertIsNone(P.noise_band([1.0]))
        self.assertGreater(P.noise_band([100, 100, 130]), 0)


class PaneOnAlone(unittest.TestCase):
    """#68: a pane's share is the median of its on-alone samples less the median of the all-off ones;
    the tolerance is the larger noise band (2 × 1.4826 × MAD) of the two sets (#186)."""

    def test_share_and_tolerance(self):
        idles = {"ref-panes-off": [ram_idle("ref-panes-off", v) for v in (100_000, 104_000, 101_000)],
                 "ref-assist-on": [ram_idle("ref-assist-on", v) for v in (131_000, 130_000, 136_000)],
                 "ref-sendspin-on": [ram_idle("ref-sendspin-on", v) for v in (102_000, 103_000, 101_000)]}
        panes = {p["id"]: p for p in R.pane_on_alone(idles, "sh.edm.s1kiosk")}
        self.assertEqual(panes["assist"]["pss_kb"], 30_000)                  # 331 000 - 301 000
        self.assertEqual(panes["assist"]["pss_tolerance_kb"], 7_088)         # 2 × 6 000 / d2(3); the on set is the noisier
        self.assertTrue(panes["assist"]["pss_measurable"])
        self.assertEqual(panes["sendspin"]["pss_kb"], 1_000)
        self.assertFalse(panes["sendspin"]["pss_measurable"])                # inside the off set's 4 725
        self.assertEqual(panes["assist"]["by_role_pss_kb"], {"gpu": 0, "main": 30_000})
        self.assertEqual((panes["assist"]["boot_samples_on"], panes["assist"]["boot_pss_kb"]), (0, None))

    def test_one_odd_sample_doesnt_set_the_tolerance_of_many(self):
        """#186: max - min only grows with n. From five samples the band is the MAD's, so ten samples
        with one 28 MB off stay near three samples' band, not the 30 MB range."""
        three = [100_000, 101_000, 102_000]
        ten = three * 3 + [130_000]
        def tol(off):
            idles = {"ref-panes-off": [ram_idle("ref-panes-off", v) for v in off],
                     "ref-assist-on": [ram_idle("ref-assist-on", v + 30_000) for v in three]}
            return R.pane_on_alone(idles, "sh.edm.s1kiosk")[0]["pss_tolerance_kb"]
        self.assertEqual(tol(three), 2_363)                                  # 2 × 2 000 / d2(3)
        self.assertEqual(tol(ten), 2_965)                                    # 2 × 1.4826 × MAD 1 000

    def test_two_agreeing_samples_and_an_odd_one_dont_give_zero(self):
        """The verifier on #196: the MAD of three samples is the smaller gap, so 100, 100, 130 gave 0."""
        idles = {"ref-panes-off": [ram_idle("ref-panes-off", v) for v in (100_000, 100_000, 130_000)],
                 "ref-assist-on": [ram_idle("ref-assist-on", v) for v in (110_000, 110_000, 110_000)]}
        p = R.pane_on_alone(idles, "sh.edm.s1kiosk")[0]
        self.assertEqual(p["pss_tolerance_kb"], 35_440)                      # 2 × 30 000 / d2(3)
        self.assertFalse(p["pss_measurable"])

    def test_one_sample_a_side_has_no_tolerance(self):
        """s1p1 (n=1) read ±0.0 MB, measurable, budget ok: no evidence for any of it."""
        idles = {"ref-panes-off": [ram_idle("ref-panes-off", 100_000)], "ref-assist-on": [ram_idle("ref-assist-on", 130_000)] * 3}
        p = R.pane_on_alone(idles, "sh.edm.s1kiosk")[0]
        self.assertEqual((p["pss_tolerance_kb"], p["pss_measurable"], p["cpu_tolerance_pct"], p["cpu_measurable"]),
                         (None, False, None, False))
        b = G.pane_budgets([p], {"assist": {"ram_pss_max_kb": 49152}})["assist"]
        self.assertEqual((b["measurable"], b["ok"]), (False, None))
        md = "\n".join(R.ram_markdown({"panes": [p], "pane_budget": {"assist": b | {"budget_kb": 49152}}}))
        row = next(l for l in md.splitlines() if l.startswith("| assist |"))
        # The verifier on #196: the row said "not measurable" three times, and "one sample" at 3 on.
        self.assertEqual(row, "| assist | +29.3 MB | none: under 2 samples a side (n=3 on, 1 off) | 48 MB, not measurable "
                              "| +0.0 % of a core |")
        self.assertNotIn("within tolerance", md)

    def test_boot_is_reported_apart_from_the_share(self):
        """#186: `:boot` (29 MB) ran in 1 of 3 Sendspin samples in s1p3 and made its tolerance ±22 MB."""
        off = [ram_idle("ref-panes-off", v) for v in (100_000, 101_000, 102_000)]
        on = [ram_idle("ref-sendspin-on", 110_000)] + [with_boot(ram_idle("ref-sendspin-on", v)) for v in (111_000, 112_000)]
        p = R.pane_on_alone({"ref-panes-off": off, "ref-sendspin-on": on}, "sh.edm.s1kiosk")[0]
        # With :boot in two of three, it moves the median: counted, the share would read +39 MB.
        self.assertEqual((p["pss_kb"], p["pss_tolerance_kb"]), (10_000, 2_363))
        self.assertEqual((p["boot_samples_on"], p["boot_samples_off"], p["boot_pss_kb"]), (2, 0, 29_000))
        self.assertNotIn("boot", p["by_role_pss_kb"])
        md = "\n".join(R.ram_markdown({"panes": [p], "pane_budget": {}}))
        self.assertIn("`:boot` (28.3 MB) ran in 2 of 3 on and 0 of 3 off sample(s)", md)

    def test_nothing_without_the_all_off_samples(self):
        self.assertEqual(R.pane_on_alone({"ref-assist-on": [ram_idle("ref-assist-on", 1)]}, "sh.edm.s1kiosk"), [])

    def test_the_reference_samples_arent_real_use_and_real_use_labels_arent_panes(self):
        budgets = {"ram": {"app_pss_max_pct_of_ram": 50, "device_available_min_pct": 15},
                   "panes": {"assist": {"switch": "voice_enabled", "ram_pss_max_kb": 20_000}, "radio": {"not_switched": "its mode"}}}
        ram = R.reference_ram({"ref-idle": [ram_idle("ref-idle", 300_000)],
                               "ref-panes-off": [ram_idle("ref-panes-off", v) for v in (100_000, 100_500, 101_000)],
                               "ref-assist-on": [ram_idle("ref-assist-on", v) for v in (130_000, 130_500, 131_000)]},
                              [{"memory": [ram_idle("x", 320_000), ram_idle("x", 340_000)]}], "sh.edm.s1kiosk", budgets, 2_000_000)
        self.assertEqual(ram["reference_idle"]["app_pss_kb"]["median"], 500_000)
        self.assertEqual(ram["graded_peak"]["app_pss_kb"], 540_000)
        self.assertEqual(ram["graded_peak"]["by_role_pss_kb"], {"gpu": 200_000, "main": 340_000})
        self.assertEqual(ram["pane_budget"]["assist"]["ok"], False)          # 30 MB against 20 MB, three samples each
        self.assertEqual(ram["panes_not_switched"], [{"id": "radio", "why": "its mode"}])
        self.assertEqual(ram["panes_waiting"], [])                            # kept in glassworks.perf/4, empty since #67
        md = "\n".join(R.ram_markdown(ram))
        self.assertIn("Not measured on their own: radio (its mode).", md)
        # #255's review: the peak is sampled between pages, and the report says so where it's shown.
        self.assertIn("| Process | Idle (median of runs) | Peak, sampled between the graded pages |", md)
        self.assertIn("a short peak inside a task can be missed", md)
        # The peak is from the between-page samples only (the idle median can be above it): as the code does.
        self.assertIn("The peak is the largest of the between-page samples only (the idle samples aren't in it", md)
        self.assertNotIn("Peak during the graded tasks", md)
        self.assertIn("- app_pss_peak (sampled between pages): ", md)
        self.assertTrue(ram["budget"]["ok"])
        self.assertIsNone(R.reference_ram({}, [], "sh.edm.s1kiosk", budgets, 2_000_000))
        # F6: a run that failed may have stopped mid-page; its samples aren't the peak.
        ram = R.reference_ram({}, [{"memory": [ram_idle("x", 320_000)]}, {"error": "x", "memory": [ram_idle("x", 900_000)]}],
                              "sh.edm.s1kiosk", budgets, 2_000_000)
        self.assertEqual((ram["graded_peak"]["samples"], ram["graded_peak"]["app_pss_kb"]), (1, 520_000))


S1_PANES = {"assist": {"switch": "voice_enabled", "mute": "mic_muted", "state": "mic"},
            "sendspin": {"switch": "sendspin_enabled", "state": "sendspin"}, "radio": {"not_switched": "its mode"}}


def ran(sample, voice, sendspin, mic, screen_off=None):
    return dict(sample, panes_state={"voice": voice, "sendspin": sendspin, "mic": mic}, screen_off=screen_off)


class PanesAsAsked(unittest.TestCase):
    """s1v7: The S1 app 0.4.3 lost the voice and Sendspin switches, so the "panes off" samples ran
    both, and Assist read -0.4 % of a core. Only samples whose kiosk log shows what their label says
    count, and the report says when Assist was off by its microphone mute alone."""

    LOG = ("11-13 00:00:01.000  100  100 I Glassworks.ESPHome: ESPHome device 'panel' mac 00:00:00:00:00:00, voice=true\n"
           "11-13 00:00:02.000  100  100 I Glassworks.Sendspin: Sendspin player starting\n"
           "11-13 00:00:03.000  100  100 I Glassworks.Voice: Microphone open: source=VOICE_RECOGNITION\n")

    def test_the_kiosk_log_gives_what_ran(self):
        self.assertEqual(P.pane_state(self.LOG), {"voice": True, "sendspin": True, "mic": True})
        muted = self.LOG + "11-13 00:00:04.000  100  100 I Glassworks.Voice: Microphone muted (released)\n"
        self.assertEqual(P.pane_state(muted), {"voice": True, "sendspin": True, "mic": False})
        # A later start (the app restarted) is the one that counts: nothing after it ran yet.
        restarted = self.LOG + "11-13 00:00:09.000  200  200 I Glassworks.ESPHome: ESPHome device 'panel' mac 00:00:00:00:00:00, voice=false\n"
        self.assertEqual(P.pane_state(restarted), {"voice": False, "sendspin": False, "mic": False})
        self.assertIsNone(P.pane_state("nothing from the kiosk")["voice"])

    def test_a_sample_counts_when_it_ran_its_label(self):
        x = ram_idle("ref-assist-on", 1)
        self.assertTrue(R.sample_matches(ran(x, True, False, True), "ref-assist-on", S1_PANES))
        self.assertFalse(R.sample_matches(ran(x, True, True, True), "ref-assist-on", S1_PANES))      # Sendspin too
        self.assertTrue(R.sample_matches(ran(x, True, False, False), "ref-panes-off", S1_PANES))     # off by its mute
        self.assertFalse(R.sample_matches(ran(x, True, False, True), "ref-panes-off", S1_PANES))
        self.assertFalse(R.sample_matches(ran(x, False, False, False, screen_off=[12.5]), "ref-panes-off", S1_PANES))
        self.assertIsNone(R.sample_matches(x, "ref-panes-off", S1_PANES))                            # not recorded

    def test_a_pane_the_sample_didnt_record_leaves_it_unverified(self):
        """#210 review: a run from before a pane's switch (#67) has no `pane:<id>` in its log. Its
        samples still count, but the share isn't marked verified."""
        x = ram_idle("ref-panes-off", 100_000)
        panes = dict(S1_PANES, **{"wall-base": {"switch": "wall_base_enabled", "state": "pane:wall-base"}})
        self.assertIsNone(R.sample_matches(ran(x, True, False, False), "ref-panes-off", panes))
        recorded = dict(ran(x, True, False, False)["panes_state"], **{"pane:wall-base": False})
        self.assertTrue(R.sample_matches(dict(x, panes_state=recorded), "ref-panes-off", panes))
        self.assertFalse(R.sample_matches(dict(x, panes_state=dict(recorded, **{"pane:wall-base": True})), "ref-panes-off", panes))

    def test_samples_that_ran_something_else_are_left_out(self):
        idles = {"ref-panes-off": [ran(ram_idle("ref-panes-off", 100_000), False, False, False),
                                   ran(ram_idle("ref-panes-off", 150_000), True, True, True)],   # s1v7's off
                 "ref-assist-on": [ran(ram_idle("ref-assist-on", 130_000), True, False, True)],
                 "ref-sendspin-on": [ran(ram_idle("ref-sendspin-on", 110_000), True, True, True)]}
        panes = {p["id"]: p for p in R.pane_on_alone(idles, PKG, S1_PANES)}
        self.assertEqual((panes["assist"]["pss_kb"], panes["assist"]["excluded"]), (30_000, 1))
        self.assertTrue(panes["assist"]["verified"])
        self.assertNotIn("off_by", panes["assist"])
        self.assertIn("didn't run sendspin on by itself", panes["sendspin"]["error"])
        self.assertNotIn("pss_kb", panes["sendspin"])

    def test_every_off_sample_wrong_is_an_error_not_a_number(self):
        idles = {"ref-panes-off": [ran(ram_idle("ref-panes-off", 150_000), True, True, True)] * 2,
                 "ref-assist-on": [ran(ram_idle("ref-assist-on", 150_000), True, False, True)]}
        panes = R.pane_on_alone(idles, PKG, S1_PANES)
        self.assertEqual([p["id"] for p in panes], ["assist", "sendspin"])
        self.assertTrue(all("panes-off" in p["error"] for p in panes))
        md = "\n".join(R.ram_markdown({"panes": panes, "pane_budget": {}}))
        self.assertIn("| assist | not measured:", md)
        # The export leaves it out instead of failing on it.
        self.assertEqual(S.ram_summary({"panes": panes})["panes"], [])

    def test_assist_off_by_its_mute_is_labelled(self):
        idles = {"ref-panes-off": [ran(ram_idle("ref-panes-off", 100_000), True, False, False)],
                 "ref-assist-on": [ran(ram_idle("ref-assist-on", 101_000), True, False, True)]}
        panes = R.pane_on_alone(idles, PKG, S1_PANES)
        self.assertEqual(panes[0]["off_by"], "mute")
        self.assertIn("off by its microphone mute only", "\n".join(R.ram_markdown({"panes": panes, "pane_budget": {}})))
        self.assertEqual(S.ram_summary({"panes": panes})["panes"][0]["off_by"], "mute")

    def test_samples_from_before_the_log_was_read_say_so(self):
        idles = {"ref-panes-off": [ram_idle("ref-panes-off", 100_000)], "ref-assist-on": [ram_idle("ref-assist-on", 130_000)]}
        panes = R.pane_on_alone(idles, PKG, S1_PANES)
        self.assertFalse(panes[0]["verified"])
        self.assertIn("not checked", "\n".join(R.ram_markdown({"panes": panes, "pane_budget": {}})))


class ScreenOff(unittest.TestCase):
    """s1v7: the screen went off for 0.14 s at the end of a reference idle window. A sample, or a
    synthetic run, the screen went off in isn't the panel at work: it is listed and left out."""

    def test_an_idle_sample_with_the_screen_off_is_left_out(self):
        budgets = {"ram": {"app_pss_max_pct_of_ram": 50, "device_available_min_pct": 15}, "panes": {}}
        off = dict(ram_idle("ref-idle", 900_000), run=3, screen_off=[14.86])
        ram = R.reference_ram({"ref-idle": [dict(ram_idle("ref-idle", 300_000), run=1), off]}, [], PKG, budgets, 2_000_000)
        self.assertEqual(ram["reference_idle"]["samples"], 1)
        self.assertEqual(ram["screen_off_samples"], ["ref-idle-3"])
        self.assertIn("The screen went off in ref-idle-3", "\n".join(R.ram_markdown(ram)))

    def test_a_real_use_sample_with_the_screen_off_is_left_out(self):
        on = dict(ram_idle("all-on", 300_000), run=1)
        r = R.real_use_summary([], {"all-on": [on, dict(on, run=2, screen_off=[3.0])]}, PKG)
        self.assertEqual((r["idle"]["samples"], r["screen_off_samples"]), (1, ["all-on-2"]))

    def test_a_synthetic_run_with_the_screen_off_is_left_out(self):
        good, bad = synthetic_lines(1, 9.0), synthetic_lines(2, 90.0) + [{"t": "screen_off", "mono": 12.0}]
        runs = [R.synthetic_run(good), R.synthetic_run(bad)]
        self.assertEqual([r["screen_off"] for r in runs], [False, True])
        s = R.synthetic_summary(runs)
        self.assertEqual((s["runs"], s["screen_off_runs"]), (1, 1))
        self.assertEqual(s["js_burst_ms"]["median"], 9.0)


class TestNetwork(unittest.TestCase):
    """#649: the test computer's own link. A stalling one (a Mac's Wi-Fi: max 68 to 84 ms to its gateway, 2026-11-19)
    that carried graded traffic makes the grade provisional; the numbers are in report.json either way."""
    setUp, write = Build.setUp, Build.write
    NOISY = {"sent": 40, "received": 40, "min_ms": 2.3, "median_ms": 3.7, "p95_ms": 68.6, "max_ms": 89.8}
    QUIET = {"sent": 40, "received": 40, "min_ms": 0.4, "median_ms": 0.6, "p95_ms": 1.1, "max_ms": 2.0}

    def net(self, gateway, adb_over_network, transport=None, link="wifi"):
        quiet = gateway["p95_ms"] <= 20 and gateway["max_ms"] <= 20
        self.write("netcheck.json", {"gateway": gateway, "panel": dict(gateway, max_ms=97.0), "link": link,
                                     "adb_over_network": adb_over_network, "tail_ms": 20, "quiet": quiet})
        if transport:
            self.write("transport.json", {"transport": transport, "chosen_by": "auto", "reason": "r", "checks": {}})
        return R.build(self.dir)

    def test_a_noisy_link_carrying_the_live_updates(self):
        before = self.rep["grade"]
        rep = self.net(self.NOISY, True)
        n = rep["test_network"]
        self.assertEqual((n["link"], n["carried"], n["quiet"]), ("wifi", ["live updates"], False))
        self.assertEqual(n["gateway"], self.NOISY)
        self.assertTrue(rep["grade"]["provisional"])
        self.assertIn("the test computer's network: its own link to its gateway reached p95 68.6 ms, max 89.8 ms "
                      "(over 20 ms) and carried the live updates", rep["grade"]["provisional_reasons"][-1])
        self.assertEqual(rep["grade"]["grade"], before["grade"])            # the letter is kept, flagged
        md = R.markdown(rep)
        self.assertIn("The test computer's network (wifi): to its gateway p95 68.6 ms, max 89.8 ms; to the panel p95 68.6 ms, "
                      "max 97.0 ms. It carried the live updates. **Limited by the test:**", md)

    def test_a_noisy_link_carrying_the_cameras_only(self):
        rep = self.net(self.NOISY, False, "lan-udp")
        self.assertEqual(rep["test_network"]["carried"], ["camera video"])
        self.assertIn("carried the camera video", rep["grade"]["provisional_reasons"][-1])

    def test_a_noisy_link_that_carried_nothing_graded(self):
        # The S1 on USB with its video through adb: the computer's Wi-Fi is on no graded path.
        before = self.rep["grade"]
        rep = self.net(self.NOISY, False, "adb")
        self.assertEqual(rep["test_network"]["carried"], [])
        self.assertIsNone(rep["test_network"]["limited"])
        self.assertEqual(rep["grade"].get("provisional_reasons"), before.get("provisional_reasons"))
        self.assertIn("It carried no graded traffic (adb over USB, video over adb).", R.markdown(rep))

    def test_a_quiet_link(self):
        before = self.rep["grade"]
        rep = self.net(self.QUIET, True, "lan-udp", link="wired")
        self.assertIsNone(rep["test_network"]["limited"])
        self.assertEqual(rep["grade"].get("provisional_reasons"), before.get("provisional_reasons"))

    def test_runs_before_the_check(self):
        self.assertIsNone(self.rep["test_network"])
        self.assertNotIn("The test computer's network", R.markdown(self.rep))


class NoTouch(unittest.TestCase):
    """Rubric 2 (2026-11-23): a frame without touch (the Mirror) isn't graded on scrolling. Nobody scrolls a panel they
    can't touch, so the runner sends no swipes there, the scroll is n/a on every page, the letter comes from the other
    tasks, and the scroll makes nothing provisional. Every other task's missing evidence still does, and a frame with
    touch whose scroll is missing is provisional as before."""

    def build(self, touch, tweak=None):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d)
        dev = {"frame": "test-frame", "model": "Panel", "android": "6.0.1", "api": 23, "abi": "armeabi-v7a",
               "cpu": {"cores": 4}, "mem_total_kb": 2_000_000, "display": {"vsync_ms": 16.667, "density": "240"},
               "app": {"package": PKG, "version_name": "0.2.0"}, "measured_on": "2026-11-23", "touch": touch}
        for name, obj in [("device.json", dev)] + [(f"refstart-{k}.json", refstart(k, 100, heavy_missed=0)) for k in (1, 2, 3)]:
            if name.startswith("refstart"):
                for t in obj["tiers"].values():
                    t["swipes"] = []                      # no swipes ran: the runner sends none without touch
                if tweak:
                    tweak(obj)
            with open(os.path.join(d, name), "w") as f:
                json.dump(obj, f)
        write_fake_log(d)
        return R.build(d)

    def test_scroll_is_not_applicable_and_the_grade_comes_from_the_rest(self):
        rep = self.build(False)
        g = rep["grade"]
        self.assertEqual((g["grade"], g["provisional"], g["missing"]), ("A+", False, []))
        self.assertNotIn("provisional_reasons", g)
        self.assertEqual({b["scroll"] for b in g["bands"].values()}, {G.NOT_APPLICABLE})
        self.assertEqual(g["not_applicable"], {"scroll": "this frame has no touch"})
        self.assertIs(rep["frame"]["device"]["touch"], False)
        # The headline says nothing about scrolling; A+ claims 60 fps only for the tasks it was graded on.
        self.assertNotIn("scroll", g["headline"].lower())
        self.assertIn("Every graded task holds 60 fps", g["headline"])
        self.assertNotIn("scroll", rep["notes"]["text"].lower())
        md = R.markdown(rep)
        self.assertIn("\nScrolling: not applicable, this frame has no touch.\n", md)
        self.assertIn("| n/a (no touch) |", md)
        self.assertNotIn("scroll not measured", md)
        # A report graded again from report.json alone (a published folder) keeps it: the fact is in frame.device.
        again = R.regrade_report(json.loads(json.dumps(rep)))
        self.assertEqual(again["grade"], g)
        # And its summary says so, complete: nothing is missing.
        s = S.summarise(rep)
        self.assertEqual((s["touch"], s["complete"]), (False, True))

    def test_swipes_that_ran_anyway_say_nothing(self):
        """A no-touch run with swipe records (rAF only, at an edge): not graded, so neither a reason for provisional nor a
        line in report.md (CodeRabbit on #776)."""
        def swiped(run):
            for t, e in run["tiers"].items():
                e["swipes"] = [swipe(NAV[t], i, late(60, 20), sf=False) for i in range(3)]
        rep = self.build(False, swiped)
        self.assertEqual((rep["grade"]["grade"], rep["grade"]["provisional"]), ("A+", False))
        md = R.markdown(rep)
        self.assertNotIn("rAF only", md)
        self.assertNotIn("swipe", md.split("## ")[0].split("| Page |")[0].lower())
        self.assertTrue(sum(self.build(True, swiped)["graded"]["reference"]["tiers"]["heavy"]["scroll_raf_only"]))
        self.assertIn("rAF only", R.markdown(self.build(True, swiped)))      # with touch: as before

    def test_other_missing_evidence_is_still_provisional(self):
        def no_tap(run):
            run["tiers"]["heavy"]["input"] = None         # heavy's taps lost: not measured, and they decide an A+
        g = self.build(False, no_tap)["grade"]
        self.assertTrue(g["provisional"])
        self.assertEqual(g["missing"], ["heavy.tap"])
        self.assertFalse(any("scroll" in r for r in g.get("provisional_reasons") or []), g.get("provisional_reasons"))

    def test_a_frame_with_touch_whose_scroll_is_missing_is_provisional_as_before(self):
        rep = self.build(True)
        g = rep["grade"]
        self.assertTrue(g["provisional"])
        self.assertEqual([m for m in g["missing"] if m.endswith(".scroll")], [f"{t}.scroll" for t in G.TIERS])
        self.assertNotIn("not_applicable", g)
        self.assertNotIn("Scrolling: not applicable", R.markdown(rep))
        self.assertIn("scroll not measured", R.markdown(rep))
        self.assertNotIn("touch", S.summarise(rep, allow_provisional=True))

    def test_a_report_from_before_the_field_has_touch(self):
        rep = self.build(True)
        del rep["frame"]["device"]["touch"]
        self.assertEqual(R.regrade_report(rep)["grade"], rep["grade"])

    def test_scroll_reasons_are_left_out_only_without_touch(self):
        ref = {"graded_runs": 1, "tiers": {"heavy": {"inp_ms": {"n": 1}, "scroll_bands": [], "updates_runs": [{}],
                                                    "load_ms": {"n": 1}, "swipes_during_updates": [False],
                                                    "scroll_raf_only": [True]}}}
        with_touch = R.coverage_reasons(ref, 1)
        self.assertTrue(any("scroll" in r for r in with_touch), with_touch)
        self.assertFalse(any("scroll" in r for r in R.coverage_reasons(ref, 1, touch=False)))

    def test_the_frame_data_says_which_frames_have_touch(self):
        """frames/<id>/design/installer.json `touch`, else the base frame's: the Mirror alone has none."""
        frames = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(HERE))), "frames")
        self.assertIs(P.frame_touch(frames, "lululemon-mirror"), False)
        for f in ("aqara-s1-plus", "generic-android", "google-pixel-tablet", "samsung-galaxy-tab-a-10-1-2016",
                  "test-frame", "unknown", "", "../frames"):
            self.assertIs(P.frame_touch(frames, f), True, f)


class Regrade(unittest.TestCase):
    """regrade.py (Clarity v1, 2026-11-19): a run's report and summary again under the rubric now, from its raw files
    or from its report.json alone; the earlier v3's runs come out v1."""
    setUp, write = Build.setUp, Build.write
    PERF = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    def regrade(self, folder, *extra):
        return subprocess.run([sys.executable, os.path.join(self.PERF, "regrade.py"), folder, *extra],
                              capture_output=True, text=True, timeout=120)

    def v3_pages(self):
        """The fixture's pages as the earlier v3's harness logged them (run 4 on the tablet, 2026-11-19)."""
        for k in (1, 2, 3):
            with open(os.path.join(self.dir, f"refstart-{k}.json")) as f:
                run = json.load(f)
            for t in run["tiers"].values():
                t["page"].update(protocol=3, clarity=3)
            self.write(f"refstart-{k}.json", run)
        with open(os.path.join(self.dir, "device.json")) as f:
            self.write("device.json", dict(json.load(f), measured_on="2026-11-19"))

    def test_a_v3_run_from_its_raw_files(self):
        self.v3_pages()
        p = self.regrade(self.dir)
        self.assertEqual(p.returncode, 0, p.stderr + p.stdout)
        with open(os.path.join(self.dir, "report.json")) as f:
            rep = json.load(f)
        self.assertEqual((rep["clarity"], rep["protocol_version"]), (1, 1))
        self.assertNotIn("Re-measure", rep["grade"]["headline"])
        with open(os.path.join(self.dir, "summary.json")) as f:
            summ = json.load(f)
        self.assertEqual((summ["clarity"], G.grade_summary(summ)["status"]), (1, "graded"))
        self.assertEqual(G.grade_summary(summ)["grade"], rep["grade"]["grade"])
        with open(os.path.join(self.dir, "report.md")) as f:
            md = f.read()
        self.assertIn("95% of live changes show within 150 ms", md)
        self.assertIn("changes within 250 ms", md)
        self.assertIn("graded again from its raw files under Clarity v1", p.stdout)

    def test_a_report_json_alone_is_the_raw_files_grade(self):
        """With the raw files gone, report.json's stored runs grade as the raw files do: the same graded summary and
        grade, byte for byte."""
        self.v3_pages()
        from_raw = R.build(self.dir)
        old = json.loads(json.dumps(from_raw))
        for r in old["graded"]["reference"]["per_run"]:            # as a v3 report stored them
            for t in r["tiers"].values():
                t["protocol"], t["clarity"] = 3, 3
        old.update(clarity=3, protocol_version=3)
        again = R.regrade_report(old)
        for key in ("graded", "grade", "clarity", "protocol_version", "notes"):
            self.assertEqual(json.dumps(again[key], sort_keys=True), json.dumps(json.loads(json.dumps(from_raw[key])), sort_keys=True), key)

    def test_a_published_folder_from_its_report_json(self):
        src = os.path.join(os.path.dirname(os.path.dirname(self.PERF)), "frames", "aqara-s1-plus", "clarity")
        d = tempfile.mkdtemp()
        for name in ("report.json", "report.md", "clarity.json"):
            shutil.copy(os.path.join(src, name), d)
        p = self.regrade(d)
        self.assertEqual(p.returncode, 0, p.stderr + p.stdout)
        self.assertIn("graded again from its report.json", p.stdout)
        for name in ("report.json", "report.md", "clarity.json"):              # the committed folder is regrade.py's output
            with open(os.path.join(src, name)) as a, open(os.path.join(d, name)) as b:
                self.assertEqual(a.read(), b.read(), name)
        with open(os.path.join(d, "clarity.json")) as f:
            g = G.grade_summary(json.load(f))
        self.assertEqual((g["status"], g["grade"]), ("graded", "D"))              # the S1 stays D, on blocking

    def test_a_refused_summary_leaves_no_stale_one(self):
        """CodeRabbit on #656: when the summary is refused now (the first v1's run is re-measure), the old summary
        beside it goes, so the folder never holds a new report with an earlier grade."""
        with open(os.path.join(self.dir, "device.json")) as f:
            self.write("device.json", dict(json.load(f), measured_on="2026-11-14"))
        self.write("summary.json", {"clarity": 1, "stale": True})
        p = self.regrade(self.dir)
        self.assertEqual(p.returncode, 1, p.stdout)
        self.assertIn("summary not written", p.stdout)
        self.assertFalse(os.path.exists(os.path.join(self.dir, "summary.json")))
        with open(os.path.join(self.dir, "report.json"), encoding="utf-8") as f:
            self.assertIn(G.FIRST_V1, " ".join(json.load(f)["grade"]["provisional_reasons"]))

    def test_a_stored_run_the_unshown_rule_could_change_is_flagged(self):
        """CodeRabbit on #656: both rules count the same changes (from 250 ms before the window's end); the earlier one
        stored an unshown change exactly 250 ms from the end as 250.0, which passed fluid, and this one as 250.1. The
        counts and the ranks don't move, so only a stored p95 of exactly 250.0 can grade differently, and only in a
        report stored before the rule (no unshown_floor_ms). regrade_report flags that view and no other."""
        rep = R.build(self.dir)
        self.assertEqual(R.censoring_band(rep["graded"]["reference"]["per_run"]), 0)
        old = json.loads(json.dumps(rep))
        runs = old["graded"]["reference"]["per_run"]
        view = runs[0]["tiers"]["typical"]
        self.assertEqual(view["latency"]["unshown_floor_ms"], R.UNSHOWN_FLOOR_MS)          # this rule's record
        earlier = {k: v for k, v in view["latency"].items() if k != "unshown_floor_ms"}   # a report from before it

        def flagged(**lat):
            view["latency"] = dict(earlier, **lat)
            return R.censoring_band(runs) == 1

        # Case 1: one shown at 100 ms, one unshown 250 ms from the end: stored p95 250.0 (fluid); now 250.1 (slow).
        self.assertTrue(flagged(n=2, median_ms=100.0, p95_ms=250.0, max_ms=250.0, skipped=0))
        again = R.regrade_report(old)
        self.assertTrue(again["grade"]["provisional"])
        self.assertIn("1 stored run(s) with a live-update p95 of exactly 250.0 ms", " ".join(again["grade"]["provisional_reasons"]))
        # A 250.0 that isn't the p95 (a median, a max, or anywhere else): it becomes 250.1 in place, its rank holds, the
        # p95 is the same value, and so is the level. Nor do the counts move: no view goes unmeasured.
        self.assertFalse(flagged(n=2, median_ms=250.0, p95_ms=708.0, max_ms=708.0, skipped=1))
        self.assertFalse(flagged(n=20, median_ms=100.0, p95_ms=240.0, max_ms=250.0, skipped=0))
        self.assertFalse(flagged(n=20, median_ms=100.0, p95_ms=260.0, max_ms=1500.0, skipped=0))
        # The same p95 in a report under this rule is a shown change at 250.0 (an unshown one would be 250.1): trusted.
        view["latency"] = dict(earlier, p95_ms=250.0, unshown_floor_ms=R.UNSHOWN_FLOOR_MS)
        self.assertEqual(R.censoring_band(runs), 0)

    def test_a_published_incomplete_summary_stays_published(self):
        """The Pixel Tablet's published result (run 4, 2026-11-19) misses heavy's camera stalls (getStats answered
        late), so its clarity.json was exported incomplete (complete: false). Graded again, it stays so: the same three
        files. Heavy already fails on blocking, so the B is final."""
        src = os.path.join(os.path.dirname(os.path.dirname(self.PERF)), "frames", "google-pixel-tablet", "clarity")
        d = tempfile.mkdtemp()
        for name in ("report.json", "report.md", "clarity.json"):
            shutil.copy(os.path.join(src, name), d)
        p = self.regrade(d)
        self.assertEqual(p.returncode, 0, p.stderr + p.stdout)
        for name in ("report.json", "report.md", "clarity.json"):
            with open(os.path.join(src, name)) as a, open(os.path.join(d, name)) as b:
                self.assertEqual(a.read(), b.read(), name)
        with open(os.path.join(d, "clarity.json")) as f:
            summ = json.load(f)
        g = G.grade_summary(summ)
        # Heavy's camera is missing, but heavy already fails on blocking: a final B, and complete stays false.
        self.assertEqual((summ["complete"], g["status"], g["grade"], g["provisional"]), (False, "graded", "B", False))
        self.assertEqual((g["missing"], summ["gaps"]), (["heavy.camera"], []))
        self.assertIn("getStats answered late", " ".join(summ["unmeasured"]))

    def test_raw_files_that_lost_a_run_dont_overwrite_the_report(self):
        """CodeRabbit on #656: with a refstart file gone, a rebuild would drop that graded run from the report."""
        p = self.regrade(self.dir)
        self.assertEqual(p.returncode, 0, p.stderr + p.stdout)
        with open(os.path.join(self.dir, "report.json")) as f:
            before = f.read()
        os.remove(os.path.join(self.dir, "refstart-3.json"))
        p = self.regrade(self.dir)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("report.json graded runs [1, 2, 3], the raw files hold [1, 2]", p.stderr)
        with open(os.path.join(self.dir, "report.json")) as f:
            self.assertEqual(f.read(), before)
        # A report from before runs were named (the S1's): the count still tells.
        older = json.loads(before)
        for r in older["graded"]["reference"]["per_run"]:
            del r["run"]
        self.write("report.json", older)
        p = self.regrade(self.dir)
        self.assertIn("report.json has 3 graded runs but only 2 refstart-*.json", p.stderr)
        self.write("report.json", json.loads(before))
        with open(os.path.join(self.dir, "report.json")) as f:
            before = f.read()
        # The same count with a gap (run 3 lost, a valid run 4 in its place): runs 1 to N or nothing.
        run4 = json.load(open(os.path.join(self.dir, "refstart-2.json")))
        self.write("refstart-4.json", dict(run4, run=4))
        p = self.regrade(self.dir)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("the raw files are runs [1, 2, 4], not 1 to 3", p.stderr)
        os.remove(os.path.join(self.dir, "refstart-4.json"))
        # Reports since v1 name their graded runs: the raw files must hold each.
        with open(os.path.join(self.dir, "report.json")) as f:
            self.assertEqual([r["run"] for r in json.load(f)["graded"]["reference"]["per_run"]], [1, 2, 3])
        # The same count from a copy (run 3 lost, refstart-4.json a copy of run 1's): each file says which run it is.
        shutil.copy(os.path.join(self.dir, "refstart-1.json"), os.path.join(self.dir, "refstart-4.json"))
        p = self.regrade(self.dir)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("the raw files aren't the runs their names say (refstart-4.json says run 1)", p.stderr)
        with open(os.path.join(self.dir, "report.json")) as f:
            self.assertEqual(f.read(), before)

    def test_raw_files_that_lost_other_evidence_dont_overwrite_the_report(self):
        """CodeRabbit on #656: a report built with netcheck.json, transport.json or plan.json keeps what they said; a
        rebuild without one would replace it with "not measured" (or fewer runs attempted), so it stops."""
        self.write("netcheck.json", {"gateway": {"sent": 40, "received": 40, "min_ms": 2.3, "median_ms": 3.7, "p95_ms": 68.6,
                                                 "max_ms": 89.8}, "panel": None, "link": "wifi", "adb_over_network": True,
                                     "tail_ms": 20, "quiet": False})
        self.write("plan.json", {"runs": 4})                              # run 4 attempted, failed, left no file
        p = self.regrade(self.dir)
        self.assertEqual(p.returncode, 1, p.stdout)                         # provisional on the network: summary refused
        with open(os.path.join(self.dir, "report.json")) as f:
            before = f.read()
        self.assertIsNotNone(json.loads(before)["test_network"]["limited"])
        for name in ("netcheck.json", "plan.json"):
            os.rename(os.path.join(self.dir, name), os.path.join(self.dir, name + ".gone"))
            p = self.regrade(self.dir)
            self.assertNotEqual(p.returncode, 0)
            self.assertIn(f"raw files the report.json was built from are gone: {name}", p.stderr)
            with open(os.path.join(self.dir, "report.json")) as f:
                self.assertEqual(f.read(), before, name)
            os.rename(os.path.join(self.dir, name + ".gone"), os.path.join(self.dir, name))

    def test_a_published_folder_whose_summary_is_refused_is_left_whole(self):
        """CodeRabbit on #656: a published folder (clarity.json, with its report URL) changes all at once or not at
        all: a refused summary leaves the report and clarity.json as they were."""
        src = os.path.join(os.path.dirname(os.path.dirname(self.PERF)), "frames", "aqara-s1-plus", "clarity")
        d = tempfile.mkdtemp()
        for name in ("report.json", "report.md", "clarity.json"):
            shutil.copy(os.path.join(src, name), d)
        with open(os.path.join(d, "report.json")) as f:
            rep = json.load(f)
        rep["measured_on"] = "2026-11-14"                          # the first v1's day: refused now
        self.write_to(d, "report.json", rep)
        before = {n: open(os.path.join(d, n)).read() for n in ("report.json", "report.md", "clarity.json")}
        p = self.regrade(d)
        self.assertEqual(p.returncode, 1, p.stdout)
        self.assertIn("nothing written: a published folder keeps its report and clarity.json together", p.stdout)
        self.assertEqual({n: open(os.path.join(d, n)).read() for n in before}, before)

    @staticmethod
    def write_to(d, name, obj):
        with open(os.path.join(d, name), "w") as f:
            json.dump(obj, f, indent=1)
            f.write("\n")

    def test_dry_run_writes_nothing(self):
        before = sorted(os.listdir(self.dir))
        p = self.regrade(self.dir, "--dry-run")
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertEqual(sorted(os.listdir(self.dir)), before)
        self.assertIn("now:", p.stdout)


if __name__ == "__main__":
    unittest.main()
