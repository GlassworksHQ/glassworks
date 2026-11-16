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

## The screen

Read on the maintainer's Mirror on 2026-11-15 (read-only adb, [image-patch.md](../../frames/lululemon-mirror/research/image-patch.md)):
the panel is 1920x1080, natural landscape, and stands portrait. The community image turns it with
`user_rotation=3`, which lives in `userdata`. Kiln's image sets `ro.sf.hwrotation=270` instead.

That turns Android's UI, **but not the boot animation**. On the first real flash (2026-11-16) the
earlier portrait 1080x1920 animation rendered sideways on the portrait glass (the maintainer's
photo). An earlier version of this README said "verified upright": that was inferred from the
property, not seen. So the frames are now turned themselves: drawn upright at 1080x1920, then turned
90° counter-clockwise into the panel's 1920x1080 framebuffer (`"boot": { "rotate": 90 }` in the
frame's `frame.json`). 90° counter-clockwise is the turn `user_rotation=3` makes: Android's
ROTATION_270 means the graphics are drawn turned 270° clockwise.

**Untested on the unit: needs a photo.** If it shows upside down, the glass is mounted the other way:
set `rotate` to 270 and rebuild.

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
