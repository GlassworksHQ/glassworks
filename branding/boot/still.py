#!/usr/bin/env python3
"""The boot animation's last frame as one small PNG, for a device where Kiln can only overwrite the stock
bootanimation.zip in its own blocks (the lululemon Mirror's Stock-in-place: 22,901 bytes, so the full
animation can't fit).

    still.py --zip branding/lululemon-mirror/bootanimation.zip --out branding/lululemon-mirror --turn-back 90

Reads the zip generate.py wrote (it is not regenerated), takes the last frame of its last part (the
still the animation ends on), and crops it to a box centered on the screen around everything that
isn't the background. Android 6 draws a frame at the top-left of a rectangle the size desc.txt names,
centered on the screen, so a centered box with that size keeps the still exactly where the full
animation leaves it. Quantized to 256 colors.

--turn-back undoes generate.py's --rotate (the frame's `rotate` in design/frame.json): the still is for
the device's stock software, whose display turns everything it draws upright itself (the Mirror's
stock 1.24.0 sets persist.panel.orientation=270 and ships a 1080x1920 portrait animation), where the
full animation is turned for a community image whose framebuffer doesn't. Turned back, the box stays
centered.

Writes into <dir>: bootanimation-still.png and still.json (the source zip's SHA-256, the box, the
background, the output's SHA-256 and the tools). Read-only on everything else. Needs Pillow and numpy.
"""
import argparse
import hashlib
import io
import json
import os
import re
import sys
import zipfile

import numpy as np
from PIL import Image

# A pixel that differs from the background by more than this (summed over R, G, B) is content.
CONTENT_DELTA = 6
# Room around the content, in pixels, so antialiased edges aren't cut.
MARGIN = 4


def last_frame(z):
    """The last frame of the last part desc.txt plays, and the background color that part names."""
    desc = z.read("desc.txt").decode("ascii").splitlines()
    parts = [l.split() for l in desc[1:] if l.strip()]
    if not parts:
        raise SystemExit("desc.txt names no parts")
    last = parts[-1]
    path = last[3]
    color = next((t for t in last[4:] if t.startswith("#")), "#000000")
    frames = sorted(n for n in z.namelist() if n.startswith(path + "/") and n.endswith(".png"))
    if not frames:
        raise SystemExit(f"part {path} has no frames")
    return frames[-1], color


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--zip", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--turn-back", type=int, choices=(0, 90, 270), default=0,
                    help="degrees generate.py turned each frame counter-clockwise (--rotate); the still is turned back upright")
    a = ap.parse_args()

    raw = open(a.zip, "rb").read()
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        name, color = last_frame(z)
        im = Image.open(io.BytesIO(z.read(name))).convert("RGB")
    if not re.fullmatch(r"#[0-9a-fA-F]{6}", color):
        raise SystemExit(f"background {color!r} isn't #rrggbb")
    bg = np.array([int(color[i:i + 2], 16) for i in (1, 3, 5)])
    px = np.asarray(im).astype(int)
    ys, xs = np.nonzero(np.abs(px - bg).sum(axis=2) > CONTENT_DELTA)
    if xs.size == 0:
        raise SystemExit(f"{name} is all background")
    w, h = im.size
    # half sizes of a box centered on the screen that holds all the content; even, so it centers exactly
    hw = int(max(w / 2 - xs.min(), xs.max() + 1 - w / 2)) + MARGIN
    hh = int(max(h / 2 - ys.min(), ys.max() + 1 - h / 2)) + MARGIN
    hw, hh = min(hw, w // 2), min(hh, h // 2)
    box = (w // 2 - hw, h // 2 - hh, w // 2 + hw, h // 2 + hh)
    upright = im.crop(box)
    if a.turn_back:
        upright = upright.rotate(-a.turn_back, expand=True)  # clockwise, undoing generate.py's counter-clockwise turn
    still = upright.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    buf = io.BytesIO()
    still.save(buf, "PNG", optimize=True)
    png = buf.getvalue()

    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "bootanimation-still.png"), "wb") as f:
        f.write(png)
    manifest = {
        "about": "What branding/boot/still.py last wrote here, from the boot animation's last frame. Kiln wraps it in a one-frame bootanimation.zip the size of a stock one it overwrites in place.",
        "source": {"zip": os.path.basename(a.zip), "sha256": hashlib.sha256(raw).hexdigest(), "frame": name},
        "framebuffer": [w, h],
        "box": list(box),
        "turnedBack": a.turn_back,
        "screen": [h, w] if a.turn_back in (90, 270) else [w, h],
        "size": list(still.size),
        "background": color.lower(),
        "output": {"bootanimation-still.png": hashlib.sha256(png).hexdigest(), "bytes": len(png)},
        "tools": {"pillow": Image.__version__, "numpy": np.__version__},
    }
    with open(os.path.join(a.out, "still.json"), "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print(f"{name} -> bootanimation-still.png {still.size[0]}x{still.size[1]}, {len(png)} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
