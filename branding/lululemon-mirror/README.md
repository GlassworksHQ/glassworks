# Boot branding: lululemon Studio Mirror

The Mirror's Android boot animation: the Glassworks stack from #63, built by the frame-neutral
generator in [`branding/boot`](../boot/README.md) from the Mirror's `frame.json` (1080x1920 portrait,
Android 6, no splash). Kiln's Mirror recipe writes it into the Glass Panes system image as
`/system/media/bootanimation.zip` ([image-patch.md](../../frames/lululemon-mirror/research/image-patch.md)).

| File | What |
|---|---|
| `bootanimation.zip` | 1080x1920 @ 30 fps, STORED, full frames (Android 6 doesn't read `trim.txt`), 9.64 MB |
| `splash-landscape.png`, `preview.webp` | Previews only. The Mirror has no splash Kiln can change |
| `outputs.json` | What the build used and wrote. A test and CI check the files against it |

## The screen

Read on the maintainer's Mirror on 2026-11-15 (read-only adb, [image-patch.md](../../frames/lululemon-mirror/research/image-patch.md)):
the panel is 1920x1080, natural landscape, and stands portrait. The community image turns it with
`user_rotation=3`, which lives in `userdata`. Kiln's image sets `ro.sf.hwrotation=270` instead, so
SurfaceFlinger, and with it the boot animation, sees a 1080x1920 portrait screen. That's why this
animation is portrait. **Verified on the unit** (2026-11-16, the first real flash): it runs portrait
and upright. If a later image shows it sideways, the rotation is what to change, not this file.

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
