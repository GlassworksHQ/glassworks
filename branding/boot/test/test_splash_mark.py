"""The Android 12+ splash mark (branding/boot/splash_mark.py). Standard library only:
python3 -m unittest discover -s branding/boot/test"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import splash_mark  # noqa: E402


class SplashMarkTest(unittest.TestCase):
    def test_the_drawable_is_the_generators_output(self):
        with open(splash_mark.OUT, encoding="utf-8") as f:
            self.assertEqual(f.read(), splash_mark.render(), "run python3 branding/boot/splash_mark.py")

    def test_the_plates_stay_inside_the_splash_circle(self):
        self.assertLessEqual(splash_mark.reach(), splash_mark.FIT)
        self.assertGreater(splash_mark.reach(), splash_mark.FIT * 0.9, "the mark should fill the circle")

    def test_centred(self):
        ox, oy = splash_mark.offset()
        ps = [p for i in range(3) for p in splash_mark.points(splash_mark.plate(i))]
        half = splash_mark.OUTLINE / 2
        self.assertAlmostEqual(min(x for x, _ in ps) - half + ox, splash_mark.VIEW - (max(x for x, _ in ps) + half + ox), places=6)
        self.assertAlmostEqual(min(y for _, y in ps) - half + oy, splash_mark.VIEW - (max(y for _, y in ps) + half + oy), places=6)


if __name__ == "__main__":
    unittest.main()
