"""Which way the boot frames turn into a rotated panel (branding/boot/formats.py to_panel/upright).
Standard library only: python3 -m unittest discover -s branding/boot/test. Without Pillow, numpy or oxipng,
small stand-ins take their place; the test only checks which turn each function asks for."""
import enum
import os
import sys
import types
import unittest

try:
    from PIL import Image  # noqa: F401
except ImportError:   # CI's step has the standard library only
    pil = types.ModuleType("PIL")
    pil.Image = types.SimpleNamespace(Transpose=enum.Enum("Transpose", "ROTATE_90 ROTATE_270"))
    sys.modules["PIL"] = pil
for name in ("numpy", "oxipng"):
    try:
        __import__(name)
    except ImportError:
        sys.modules[name] = types.ModuleType(name)

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from PIL import Image  # noqa: E402
import formats  # noqa: E402
from target import from_frame  # noqa: E402


class Turned:
    """An image that records the turn asked of it."""
    def transpose(self, how):
        return how


class TurnTest(unittest.TestCase):
    def test_the_mirror_turns_its_frames_90_degrees_counter_clockwise(self):
        # Upright content is stored turned 90 deg counter-clockwise (PIL ROTATE_90): the photo of the
        # turned frames on the unit showed them upright (2026-11-16). It is the same turn as Android's
        # ROTATION_270 (user_rotation=3), which stands the Mirror's UI upright; rotate: 270 is the fallback.
        mirror = from_frame("lululemon-mirror")
        self.assertEqual(mirror.rotate, 90)
        self.assertIs(formats.to_panel(mirror, Turned()), Image.Transpose.ROTATE_90)
        self.assertIs(formats.upright(mirror, Turned()), Image.Transpose.ROTATE_270)

    def test_270_turns_the_other_way(self):
        turned = formats.dataclasses.replace(from_frame("lululemon-mirror"), rotate=270)
        self.assertIs(formats.to_panel(turned, Turned()), Image.Transpose.ROTATE_270)
        self.assertIs(formats.upright(turned, Turned()), Image.Transpose.ROTATE_90)

    def test_no_turn_leaves_the_frame(self):
        s1 = from_frame("aqara-s1-plus")
        img = Turned()
        self.assertIs(formats.to_panel(s1, img), img)
        self.assertIs(formats.upright(s1, img), img)


if __name__ == "__main__":
    unittest.main()
