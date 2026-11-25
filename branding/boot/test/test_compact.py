"""The Mirror's compact boot animation, branding/lululemon-mirror/bootanimation-compact.zip
(branding/boot/compact.py). The checks on the committed zip use the standard library only (CI's step
has no Pillow): python3 -m unittest discover -s branding/boot/test. The rebuild check runs where
Pillow, numpy, uharfbuzz and pyoxipng are installed (README, Rebuild), and CI's branding job rebuilds
it byte for byte anyway."""
import os
import re
import struct
import sys
import tempfile
import unittest
import zipfile
import zlib

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)
from target import REPO, from_frame  # noqa: E402

ZIP = os.path.join(REPO, "branding", "lululemon-mirror", "bootanimation-compact.zip")
STOCK_BYTES = 22901        # stock 1.24.0's /system/media/bootanimation.zip (read 2026-11-24)
BG = (0x0a, 0x0b, 0x0f)
LINE = re.compile(r"^p (\d+) (\d+) (g[0-3]) #0a0b0f$")


def decode_png(data):
    """(width, height, rows of RGB tuples) of an 8-bit, non-interlaced palette or RGB PNG."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    at, idat, plte = 8, b"", None
    while at < len(data):
        n, kind = struct.unpack(">I4s", data[at:at + 8])
        body = data[at + 8:at + 8 + n]
        if kind == b"IHDR":
            w, h, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", body)
        elif kind == b"PLTE":
            plte = [tuple(body[i:i + 3]) for i in range(0, len(body), 3)]
        elif kind == b"IDAT":
            idat += body
        at += 12 + n
    assert depth == 8 and interlace == 0 and colour in (2, 3), (depth, colour, interlace)
    bpp = 3 if colour == 2 else 1
    raw, stride, prev, rows, i = zlib.decompress(idat), w * bpp, bytearray(w * bpp), [], 0
    for _ in range(h):
        f, line = raw[i], bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b, c = prev[x], (prev[x - bpp] if x >= bpp else 0)
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append([tuple(line[x:x + 3]) for x in range(0, stride, 3)] if colour == 2 else [plte[v] for v in line])
        prev = line
    return w, h, rows


class CommittedCompactZipTest(unittest.TestCase):
    def setUp(self):
        self.z = zipfile.ZipFile(ZIP)
        self.addCleanup(self.z.close)

    def test_fits_in_stock_and_every_entry_is_stored(self):
        self.assertLessEqual(os.path.getsize(ZIP), STOCK_BYTES)
        self.assertEqual(from_frame("lululemon-mirror").compact_budget, STOCK_BYTES)
        for i in self.z.infolist():
            self.assertEqual(i.compress_type, zipfile.ZIP_STORED, i.filename)
        self.assertIsNone(self.z.testzip())
        self.assertEqual(self.z.comment, b"")   # Kiln pads it to the stock size with the comment

    def test_desc_txt_breathes_then_rests_on_full_glow(self):
        lines = self.z.read("desc.txt").decode().split("\n")
        self.assertEqual(lines[-1], "")
        # upright portrait, as stock 1.24.0's own ("1080 1920 10"): stock's player turns it
        self.assertEqual(lines[0], "300 400 10")
        parts = [LINE.match(x) for x in lines[1:-1]]
        self.assertTrue(all(parts), "every line is a 'p' part clearing to #0a0b0f")
        breath = [(m.group(3), int(m.group(1)) + int(m.group(2))) for m in parts[:-1]]
        one = [("g3", 8), ("g2", 5), ("g1", 5), ("g0", 8), ("g1", 5), ("g2", 5)]
        self.assertEqual(breath, one * 40)
        self.assertTrue(all(m.group(1) == "1" for m in parts[:-1]))
        self.assertEqual(lines[-2], "p 0 0 g3 #0a0b0f")   # holds full glow until boot completes

    def test_four_frames_300x400_with_bg_corners(self):
        pngs = sorted(n for n in self.z.namelist() if n.endswith(".png"))
        self.assertEqual(pngs, ["g0/00.png", "g1/00.png", "g2/00.png", "g3/00.png"])
        for n in pngs:
            w, h, rows = decode_png(self.z.read(n))
            self.assertEqual((w, h), (300, 400), n)
            for x, y in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
                self.assertEqual(rows[y][x], BG, f"{n} corner {(x, y)}")
            self.assertTrue(all(p == BG for p in rows[0] + rows[-1]), f"{n}: top and bottom rows are BG")
            self.assertTrue(all(r[0] == BG and r[-1] == BG for r in rows), f"{n}: side columns are BG")


def _have_build_tools():
    # test_turn.py stands in empty modules for PIL, numpy and oxipng where they're missing
    try:
        import numpy, oxipng, uharfbuzz  # noqa: F401,E401
        import PIL
        return getattr(PIL, "__version__", None) == "13.0.0" and hasattr(oxipng, "optimize_from_memory")
    except ImportError:
        return False


@unittest.skipUnless(_have_build_tools(), "needs the generator's tools at the README's versions")
class RebuildTest(unittest.TestCase):
    def test_rebuilds_byte_for_byte(self):
        import compact
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(compact.build(from_frame("lululemon-mirror"), d), [])
            with open(os.path.join(d, compact.NAME), "rb") as a, open(ZIP, "rb") as b:
                self.assertEqual(a.read(), b.read())


if __name__ == "__main__":
    unittest.main()
