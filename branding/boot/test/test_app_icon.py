"""The apps' icons (branding/boot/app_icon.py). Standard library only:
python3 -m unittest discover -s branding/boot/test"""
import os
import struct
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import app_icon  # noqa: E402


class AppIconTest(unittest.TestCase):
    def test_the_vectors_are_the_generators_output(self):
        for res in app_icon.APPS:
            for rel, text in app_icon.outputs().items():
                with open(os.path.join(res, rel), encoding="utf-8") as f:
                    self.assertEqual(f.read(), text, f"{res}/{rel}: run python3 branding/boot/app_icon.py")

    def test_both_apps_launch_with_the_brands_icon(self):
        for res in app_icon.APPS:
            manifest = os.path.join(os.path.dirname(res), "AndroidManifest.xml")
            with open(manifest, encoding="utf-8") as f:
                self.assertIn('android:icon="@mipmap/ic_launcher"', f.read(), manifest)

    def test_android_6_has_a_png_at_every_density(self):
        for density, px in app_icon.PNGS.items():
            with open(os.path.join(app_icon.RES, f"mipmap-{density}", "ic_launcher.png"), "rb") as f:
                head = f.read(24)
            self.assertEqual(b"\x89PNG", head[:4], density)
            self.assertEqual((px, px), struct.unpack(">II", head[16:24]), density)

    def test_the_notification_icon_is_plain(self):
        text = app_icon.outputs()["drawable/ic_notification.xml"]
        self.assertNotIn("gradient", text, "a gradient needs API 24: Android 6 couldn't load it")
        colours = {c for c in text.split('"') if c.startswith("#")}
        self.assertEqual({"FFFFFF"}, {c[-6:] for c in colours}, "white, at the mono mark's opacities")

    def test_the_mark_sits_in_the_shown_part_as_on_the_brands_icon(self):
        s, dx, dy = app_icon.adaptive_map(app_icon.load("app-icon.svg"))
        self.assertAlmostEqual(54.0, dx + 60 * s, places=2)  # the mark's 120 units centred on the 108 dp
        self.assertAlmostEqual(54.0, dy + 60 * s, places=2)
        self.assertLess(60 * s, 33.0, "inside the 66 dp a round mask keeps")


if __name__ == "__main__":
    unittest.main()
