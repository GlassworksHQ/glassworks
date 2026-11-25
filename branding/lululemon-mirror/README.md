# Boot branding: lululemon Studio Mirror

The Mirror's Android boot animation: the Glassworks stack from #63, built by the frame-neutral
generator in [`branding/boot`](../boot/README.md) from the Mirror's `frame.json` (1080x1920 portrait,
Android 6, no splash). Kiln's Mirror recipe writes it into the Glass Panes system image as
`/system/media/bootanimation.zip` ([image-patch.md](../../frames/lululemon-mirror/research/image-patch.md)).

| File | What |
|---|---|
| `bootanimation.zip` | 1920x1080 @ 30 fps (the panel's own framebuffer; the portrait art turned 90° counter-clockwise, `boot.rotate`), STORED, full frames (Android 6 doesn't read `trim.txt`), 11.44 MB |
| `splash-landscape.png`, `preview.webp` | Previews only. The Mirror has no splash Kiln can change |
| `outputs.json` | What the build used and wrote. A test and CI check the files against it |
| `bootanimation-still.png`, `still.json` | The animation's last frame, cropped to a box centered on the screen, turned back upright for stock's own display (`--turn-back 90`) and quantized (13 KB), by [`branding/boot/still.py`](../boot/still.py) from `bootanimation.zip`. Kiln's in-place install wraps it in a one-frame animation the size of stock's own (22,901 bytes) and writes it over stock's in its blocks ([stock-inplace-parity.md](../../frames/lululemon-mirror/research/stock-inplace-parity.md)). Rebuild it whenever `bootanimation.zip` changes; `still.json` records the source's SHA-256 and a test checks it |

## The screen

Read on the maintainer's Mirror on 2026-11-15 (read-only adb, [image-patch.md](../../frames/lululemon-mirror/research/image-patch.md)):
the panel is 1920x1080, natural landscape, and stands portrait. The community image turns it with
`user_rotation=3`, which lives in `userdata`, so Kiln's first boot sets it again on the fresh one.
Kiln's image set `ro.sf.hwrotation=270` until 2026-11-17, but nothing on this image reads that
property: it turned neither the UI nor the boot animation.

The boot animation plays before any of that. On the first real flash (2026-11-16) the
earlier portrait 1080x1920 animation rendered sideways on the portrait glass (the maintainer's
photo). An earlier version of this README said "verified upright": that was inferred from the
property, not seen. So the frames are now turned themselves: drawn upright at 1080x1920, then turned
90° counter-clockwise into the panel's 1920x1080 framebuffer (`"boot": { "rotate": 90 }` in the
frame's `frame.json`). 90° counter-clockwise is the turn `user_rotation=3` makes: Android's
ROTATION_270 means the graphics are drawn turned 270° clockwise.

**Verified on the unit** (2026-11-16, the root's run; the maintainer's photo): the turned animation
runs upright on the portrait glass. If a later unit shows it upside down, its glass is mounted the
other way: set `rotate` to 270 and rebuild.

## Not changeable: the boot logo

The logo before Android starts stays lululemon's. It comes from the signed bootloader (`aboot`), and
the Mirror has no splash partition Kiln could write. Option A writes only `system` and `userdata`.

## Rebuild

```sh
python3 branding/boot/generate.py --frame lululemon-mirror --out branding/lululemon-mirror
```

With the tool versions in [`branding/boot/README.md`](../boot/README.md). CI rebuilds it in
`python:3.11-slim` and fails on any byte of difference, as it does for the S1's. On 2026-11-15 a
build on macOS arm64 (Python 3.11.3) gave the S1's committed files byte for byte, and this one.
