"""The compact boot animation ("Native Portrait Pulse"): a bootanimation-compact.zip small enough to
replace a stock bootanimation.zip in place, for a frame whose `boot.compactBudget` is that file's size.

The Mirror's stock-in-place install will write it over /system/media/bootanimation.zip in that file's
own ext4 blocks, so it may not grow past the stock file (22,901 bytes on stock 1.24.0). stock.ts
padZipToExactLength pads a zip to the stock file's exact length (the zip comment); the stock install
doesn't write this zip yet.

What it is: the Glassworks stack and wordmark at rest, cropped to a BOX in the middle of the screen at
the screen's own pixels (no scaling), in four glow levels of the top plate. desc.txt plays them as a
breath (full, down, dimmest, up) BREATHS times at FPS, holding each level about as long as a cosine
spends there, then rests on full glow until boot completes. Every part clears to BG, which is also
every pixel at the PNGs' edges, so the box doesn't show.

Upright, not turned: it plays under the stock image's own bootanimation, which reads
persist.panel.orientation (270 on the Mirror's stock 1.24.0) and turns the picture itself. Stock
1.24.0's own zip is upright portrait (desc.txt "1080 1920 10", one 1080x1920 PNG; read 2026-11-24).
`rotate` is for the Glass Panes image, whose player doesn't turn anything.

One palette for the four frames (so nothing shimmers between levels), BG forced in exactly, every
pixel mapped to its exact nearest colour (Pillow's own lookup can miss BG by a level). The largest
palette in PALETTES that fits the budget wins.
"""
import io
import os
import re
import zipfile

import numpy as np
import oxipng
from PIL import Image

import stack
from formats import BG

NAME = "bootanimation-compact.zip"
BOX = (300, 400)                       # width, height: the stack and wordmark at 1:1 on a 1080x1920 screen
FPS = 10
GLOW = (0.78, 0.86, 0.93, 1.0)         # the top plate's brightness in g0..g3
BREATH = (("g3", 8), ("g2", 5), ("g1", 5), ("g0", 8), ("g1", 5), ("g2", 5))   # 3.6 s
BREATHS = 40                           # 144 s, then it rests on g3
PALETTES = (32, 28, 24, 20, 16)
DT = (2026, 1, 1, 0, 0, 0)
BGHEX = "#%02x%02x%02x" % BG


def desc_txt():
    lines = [f"{BOX[0]} {BOX[1]} {FPS}"]
    for _ in range(BREATHS):
        lines += [f"p 1 {ticks - 1} {part} {BGHEX}" for part, ticks in BREATH]
    lines.append(f"p 0 0 g3 {BGHEX}")
    return "\n".join(lines) + "\n"


def frames(target):
    """{part: BOX-sized uint8 RGB}, the stack at rest with the wordmark, no device line, no grain."""
    if target.width < BOX[0] or target.height < BOX[1]:
        raise ValueError(f"the compact animation's {BOX[0]}x{BOX[1]} box doesn't fit a {target.width}x{target.height} screen")
    scene = stack.Scene(target)
    scene.grain[:] = 0     # seeded noise: KBs in every frame, invisible at this size
    rest = tuple(stack.Plate(lift=0.0, op=1.0, ghost=0.0) for _ in range(3))
    x0, y0 = (target.width - BOX[0]) // 2, (target.height - BOX[1]) // 2   # the player centres the box
    out = {}
    for i, glow in enumerate(GLOW):
        cv = stack.Canvas(scene.base)
        stack.paint_stack(cv, stack.State(rest, 1.0, glow, 1.0, 0.0), scene)
        cov = scene.word(0.0)
        cv.px += (np.asarray(stack.FG, np.float64) - cv.px) * cov[..., None]
        px = np.clip(cv.px + 0.5, 0, 255).astype(np.uint8)
        art = (px != np.array(BG, np.uint8)).any(-1)
        art[y0:y0 + BOX[1], x0:x0 + BOX[0]] = False
        if art.any():   # the layout scales with the screen; BOX doesn't
            ys, xs = np.nonzero(art)
            raise ValueError(f"the artwork on a {target.width}x{target.height} screen reaches past the compact "
                             f"{BOX[0]}x{BOX[1]} box (e.g. x={xs[0]}, y={ys[0]}): it would be cropped")
        out[f"g{i}"] = np.ascontiguousarray(px[y0:y0 + BOX[1], x0:x0 + BOX[0]])
    return out


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


def write_zip(path, pngs):
    """STORED, no directory entries, fixed times: desc.txt, then g0/00.png .. g3/00.png."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        def add(name, data):
            zi = zipfile.ZipInfo(name, DT)
            zi.compress_type = zipfile.ZIP_STORED
            zi.create_system = 3
            zi.external_attr = 0o100644 << 16
            z.writestr(zi, data)
        add("desc.txt", desc_txt().encode())
        for part in sorted(pngs):
            add(f"{part}/00.png", pngs[part])
    return os.path.getsize(path)


def build(target, out):
    """Writes out/NAME within target.compact_budget. Returns the problems (empty = all good)."""
    arrays = frames(target)
    path = os.path.join(out, NAME)
    for n in PALETTES:
        pal = palette(list(arrays.values()), n)
        size = write_zip(path, {k: png_bytes(a, pal) for k, a in arrays.items()})
        if size <= target.compact_budget:
            print(f"  {NAME} {size} bytes, {n} colours (budget {target.compact_budget})")
            return validate(target, path)
    return [f"{NAME} is {size} bytes even at {PALETTES[-1]} colours, over the {target.compact_budget} budget"]


DESC_LINE = re.compile(r"^p (\d+) (\d+) (g\d) (#[0-9a-f]{6})$")


def validate(target, path):
    P = []
    if os.path.getsize(path) > target.compact_budget:
        P.append(f"{NAME} is {os.path.getsize(path)} bytes, over the {target.compact_budget} budget")
    with zipfile.ZipFile(path) as z:
        if any(i.compress_type != zipfile.ZIP_STORED for i in z.infolist()):
            P.append(f"{NAME}: an entry isn't stored")
        if z.testzip() is not None:
            P.append(f"{NAME}: CRC error")
        if z.read("desc.txt").decode() != desc_txt():
            P.append(f"{NAME}: desc.txt differs from desc_txt()")
        parts = {m.group(3) for m in map(DESC_LINE.match, desc_txt().splitlines()[1:]) if m}
        for part in sorted(parts):
            im = Image.open(io.BytesIO(z.read(f"{part}/00.png"))).convert("RGB")
            if im.size != BOX:
                P.append(f"{NAME}: {part}/00.png is {im.size}, not {BOX}")
            w, h = im.size
            for xy in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
                if im.getpixel(xy) != BG:
                    P.append(f"{NAME}: {part}/00.png corner {xy} is {im.getpixel(xy)}, not {BGHEX}")
    return P
