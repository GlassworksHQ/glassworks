"""apk-icons.py on aapt2's output (#198): which configurations Android 6 picks, and what it reports. No aapt2, no APK."""
import importlib.util
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("apk_icons", os.path.join(HERE, "..", "apk-icons.py"))
icons = importlib.util.module_from_spec(spec)
spec.loader.exec_module(icons)

# The legacy APK the Mirror crashed on (d4ce398), as `aapt2 dump resources` and `dump xmltree` print it, cut down.
CRASHED = """Package name=dev.glassworks.generic_android.legacy id=7f
  type drawable id=04 entryCount=2
    resource 0x7f040003 drawable/glassworks_mark
      (mdpi) (file) res/CF.png type=PNG
      (anydpi-v24) (file) res/ar.xml type=XML
  type mipmap id=09 entryCount=1
    resource 0x7f090000 mipmap/ic_launcher
      (anydpi-v26) (file) res/BW.xml type=XML
"""
FIXED = (CRASHED + """    resource 0x7f090001 mipmap/ic_round
      (xxhdpi) (file) res/r.png type=PNG
  type drawable id=05 entryCount=1
    resource 0x7f050000 drawable/ic_notification
      (anydpi-v21) (file) res/n.xml type=XML
""").replace("(anydpi-v26) (file) res/BW.xml type=XML", "(mdpi) (file) res/a.png type=PNG\n      (anydpi-v26) (file) res/BW.xml type=XML")
MANIFEST = """N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    E: application (line=102)
      A: http://schemas.android.com/apk/res/android:label(0x01010001)="Glassworks"
      A: http://schemas.android.com/apk/res/android:icon(0x01010002)=@0x7f090000
      A: http://schemas.android.com/apk/res/android:roundIcon(0x0101052c)=@0x7f090000
      E: activity (line=110)
        A: http://schemas.android.com/apk/res/android:icon(0x01010002)=@0x7f040003
"""


class ApkIconsTest(unittest.TestCase):
    def test_android_6_picks_a_density_or_an_older_version(self):
        for config in ("", "mdpi", "xxxhdpi", "anydpi-v21", "anydpi-v23", "480dpi"):
            self.assertTrue(icons.loads_on(config, 23), config)
        for config in ("anydpi-v26", "v24", "night", "land-mdpi", "sw600dp"):
            self.assertFalse(icons.loads_on(config, 23), config)

    def test_the_apk_the_mirror_crashed_on_fails(self):
        out = icons.problems(icons.resources(CRASHED), icons.manifest_icons(MANIFEST), ["drawable/ic_notification"], 23)
        self.assertEqual([
            "the manifest's icon (mipmap/ic_launcher): only (anydpi-v26), none of which API 23 picks",
            "the manifest's roundIcon (mipmap/ic_launcher): only (anydpi-v26), none of which API 23 picks",
            "drawable/ic_notification: drawable/ic_notification isn't in the APK",
        ], out)

    def test_png_mipmaps_and_a_plain_vector_pass(self):
        manifest = MANIFEST.replace("roundIcon(0x0101052c)=@0x7f090000", "roundIcon(0x0101052c)=@0x7f090001")
        self.assertEqual({"icon": 0x7f090000, "roundIcon": 0x7f090001}, icons.manifest_icons(manifest))
        self.assertEqual([], icons.problems(icons.resources(FIXED), icons.manifest_icons(manifest), ["drawable/ic_notification"], 23))

    def test_a_named_resource_only_for_newer_or_other_screens_fails(self):
        res = icons.resources(FIXED.replace("(mdpi) (file) res/CF.png", "(night-mdpi) (file) res/CF.png"))
        self.assertEqual(["drawable/glassworks_mark: only (night-mdpi), (anydpi-v24), none of which API 23 picks"],
                         icons.problems(res, {"icon": 0x7f090000}, ["drawable/glassworks_mark"], 23))

    def test_an_app_without_an_icon_fails(self):
        self.assertEqual(["the manifest's icon: none (Android shows its robot)"], icons.problems({}, {}, [], 23))


if __name__ == "__main__":
    unittest.main()
