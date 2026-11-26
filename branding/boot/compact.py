"""The compact boot animation: a bootanimation-compact.zip small enough to replace a stock
bootanimation.zip in place, for a frame whose `boot.compactBudget` is that file's size.

The Mirror's stock-in-place install writes it over /system/media/bootanimation.zip in that file's own
ext4 blocks, so it may not grow past the stock file (22,901 bytes on stock 1.24.0). stock.ts
padZipToExactLength pads it to the stock file's exact length (the zip comment).

What it is: one full-screen frame, the full boot animation's resting frame (the Glassworks stack, the
wordmark and the device line) without its grain, held until boot completes. Full-screen like stock's
own, so there is no box around the art for the player to clear in another colour. An earlier version
breathed four 300x400 frames in the middle of the screen; on the glass the box and the 32-colour
banding showed (maintainer, 2026-11-25).

Upright, not turned: it plays under the stock image's own bootanimation, which reads
persist.panel.orientation (270 on the Mirror's stock 1.24.0) and turns the picture itself. Stock
1.24.0's own zip is upright portrait (desc.txt "1080 1920 10", one 1080x1920 PNG; read 2026-11-24).
`rotate` is for the Glass Panes image, whose player doesn't turn anything.

The largest palette in PALETTES that fits the budget wins, BG forced in exactly and every pixel mapped
to its exact nearest colour (Pillow's own lookup can miss BG by a level).
"""
import io
import os
import zipfile

import numpy as np
import oxipng
from PIL import Image

import stack
from formats import BG

NAME = "bootanimation-compact.zip"
PART = "part0"
FPS = 10
PALETTES = (256, 128, 64, 32)
DT = (2026, 1, 1, 0, 0, 0)


def desc_txt(target):
    """Stock's own shape: the screen at 10 fps, one part held until boot completes."""
    return f"{target.width} {target.height} {FPS}\np 0 0 {PART}\n"


def frame(target):
    """The screen as uint8 RGB: the stack at rest with the wordmark and the device line, no grain."""
    scene = stack.Scene(target)
    scene.grain[:] = 0     # seeded noise: KBs in the frame, invisible on the glass
    return stack.render(stack.INTRO_END, scene)


def palette(arrays, n):
    """n colours for all the frames together (median cut), the one nearest BG replaced by BG."""
    big = Image.fromarray(np.concatenate([a.reshape(-1, 3) for a in arrays])[None, :, :])
    q = big.quantize(colors=n, method=Image.Quantize.MEDIANCUT, kmeans=3, dither=Image.Dither.NONE)
    pal = np.array(q.getpalette()[:3 * n], np.int32).reshape(-1, 3)
    pal[int(np.argmin(((pal - np.array(BG)) ** 2).sum(1)))] = BG
    return pal


def png_bytes(a, pal):
    flat = a.reshape(-1, 3).astype(np.int32)
    uniq, inv = np.unique(flat, axis=0, return_inverse=True)
    idx = np.argmin(((uniq[:, None, :] - pal[None, :, :]) ** 2).sum(-1), 1).astype(np.uint8)
    im = Image.frombytes("P", (a.shape[1], a.shape[0]), idx[inv.reshape(-1)].tobytes())
    im.putpalette(pal.flatten().tolist())
    b = io.BytesIO()
    im.save(b, "PNG", optimize=True)
    return oxipng.optimize_from_memory(b.getvalue(), level=6, force=True, strip=oxipng.StripChunks.all(),
                                       deflate=oxipng.Deflaters.zopfli(30))


def write_zip(path, target, png):
    """STORED, no directory entries, fixed times: desc.txt, then part0/00.png."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        def add(name, data):
            zi = zipfile.ZipInfo(name, DT)
            zi.compress_type = zipfile.ZIP_STORED
            zi.create_system = 3
            zi.external_attr = 0o100644 << 16
            z.writestr(zi, data)
        add("desc.txt", desc_txt(target).encode())
        add(f"{PART}/00.png", png)
    return os.path.getsize(path)


def build(target, out):
    """Writes out/NAME within target.compact_budget. Returns the problems (empty = all good)."""
    px = frame(target)
    path = os.path.join(out, NAME)
    for n in PALETTES:
        size = write_zip(path, target, png_bytes(px, palette([px], n)))
        if size <= target.compact_budget:
            print(f"  {NAME} {size} bytes, {n} colours (budget {target.compact_budget})")
            return validate(target, path)
    return [f"{NAME} is {size} bytes even at {PALETTES[-1]} colours, over the {target.compact_budget} budget"]


def validate(target, path):
    P = []
    if os.path.getsize(path) > target.compact_budget:
        P.append(f"{NAME} is {os.path.getsize(path)} bytes, over the {target.compact_budget} budget")
    with zipfile.ZipFile(path) as z:
        if any(i.compress_type != zipfile.ZIP_STORED for i in z.infolist()):
            P.append(f"{NAME}: an entry isn't stored")
        if z.testzip() is not None:
            P.append(f"{NAME}: CRC error")
        if z.read("desc.txt").decode() != desc_txt(target):
            P.append(f"{NAME}: desc.txt differs from desc_txt()")
        im = Image.open(io.BytesIO(z.read(f"{PART}/00.png"))).convert("RGB")
        if im.size != (target.width, target.height):
            P.append(f"{NAME}: {PART}/00.png is {im.size}, not the screen's {target.width}x{target.height}")
        w, h = im.size
        for xy in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
            if im.getpixel(xy) != BG:
                P.append(f"{NAME}: {PART}/00.png corner {xy} is {im.getpixel(xy)}, not BG")
    return P
