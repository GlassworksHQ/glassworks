#!/usr/bin/env python3
"""The parity corpus's own pictures (MIT): drawn here, pixel by pixel, so they come from no one's media or photos.

  python3 tools/vitro-parity/fixture_images.py     # writes engines/engine-vitro/src/test/parity/fixtures/images/*.png

Touches: only those files. The fake HA serves them at /local/vitro-harness-fixtures/images/ (a fixture's
entity_picture), and Vitro's fixture server hands them over from the same folder.
"""
import math
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "engines", "engine-vitro", "src", "test", "parity", "fixtures", "images")


def png(w, h, pixel):
    """An 8-bit RGB PNG of w x h, pixel(u, v) -> (r, g, b) with u, v in [0, 1)."""
    rows = b"".join(b"\0" + bytes(c for x in range(w) for c in pixel((x + 0.5) / w, (y + 0.5) / h)) for y in range(h))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))


def mix(a, b, f):
    f = max(0.0, min(1.0, f))
    return tuple(round(x + (y - x) * f) for x, y in zip(a, b))


def album(u, v):
    """Album art: a dusk gradient, a sun and three ridges."""
    sky = mix((38, 30, 92), (247, 135, 84), v * 1.1)
    d = math.hypot(u - 0.5, v - 0.55)
    if d < 0.18:
        sky = mix((255, 214, 120), sky, (d / 0.18) ** 6)
    for k, (base, amp, col) in enumerate(((0.70, 0.06, (64, 44, 110)), (0.80, 0.05, (44, 30, 82)), (0.90, 0.04, (24, 18, 52)))):
        if v > base + amp * math.sin(u * math.pi * (2 + k) + k):
            sky = col
    return sky


def person(u, v):
    """A person's picture: a plain silhouette on a teal disc's colour."""
    bg = mix((20, 140, 150), (12, 96, 120), v)
    head = math.hypot(u - 0.5, (v - 0.40) * 1.05) < 0.17
    body = v > 0.66 and math.hypot((u - 0.5) * 0.9, v - 1.02) < 0.40
    return (236, 240, 241) if head or body else bg


def camera(u, v):
    """A camera's still, 16:9: a driveway at dusk, a garage door and a lamp, seen from above the door."""
    if v < 0.42:
        px = mix((52, 64, 110), (150, 120, 140), v / 0.42)
    else:
        px = mix((70, 74, 80), (40, 42, 48), (v - 0.42) / 0.58)
        # The driveway narrows towards the house.
        half = 0.12 + (v - 0.42) * 0.55
        if abs(u - 0.5) < half:
            px = mix((112, 110, 104), (86, 84, 80), (v - 0.42) / 0.58)
    if 0.30 < u < 0.70 and 0.18 < v < 0.42:
        px = (196, 188, 172) if int((v - 0.18) / 0.04) % 2 == 0 else (178, 170, 156)
    d = math.hypot(u - 0.80, (v - 0.22) * 1.78)
    if d < 0.05:
        px = mix((255, 226, 150), px, (d / 0.05) ** 3)
    return px


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, (w, h, fn) in {"album.png": (300, 300, album), "person.png": (160, 160, person), "camera.png": (480, 270, camera)}.items():
        with open(os.path.join(OUT, name), "wb") as f:
            f.write(png(w, h, fn))
        print(os.path.relpath(os.path.join(OUT, name)))


if __name__ == "__main__":
    main()
