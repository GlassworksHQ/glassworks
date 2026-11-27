# Boot branding: lululemon Studio Mirror

The Mirror's Android boot animation: the Glassworks stack from #63, built by the frame-neutral
generator in [`branding/boot`](../boot/README.md) from the Mirror's `frame.json` (1080x1920 portrait,
Android 6, no splash). Kiln's Mirror recipe writes it into the Glass Panes system image as
`/system/media/bootanimation.zip` ([image-patch.md](../../frames/lululemon-mirror/research/image-patch.md)).

| File | What |
|---|---|
| `bootanimation.zip` | 1920x1080 @ 30 fps (the panel's own framebuffer; the portrait art turned 90° counter-clockwise, `boot.rotate`), STORED, full frames (Android 6 doesn't read `trim.txt`), 11.44 MB |
| `bootanimation-compact.zip` | The boot animation for Glassworks on stock firmware (stock-in-place): one full-screen 1080x1920 frame, upright, STORED, within the 22,901 bytes of stock's own `bootanimation.zip` ([below](#on-stock-firmware)) |
| `splash-landscape.png`, `preview.webp` | Previews only. The Mirror has no splash Kiln can change |
| `outputs.json` | What the build used and wrote. A test and CI check the files against it |

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

## On stock firmware

Glassworks can also install on the Mirror's stock firmware without replacing the system image. That
install writes the boot animation over stock's `/system/media/bootanimation.zip` in the blocks the
file already has, so ours can't be any larger. On stock 1.24.0 that file is 22,901 bytes:
`desc.txt` reads `1080 1920 10`, and its one frame is the lululemon Studio logo, upright on a
1080x1920 portrait canvas (read from the stock system image: [stock-firmware-probe.md](../../frames/lululemon-mirror/research/stock-firmware-probe.md)). Stock also sets
`persist.panel.orientation=270`, which its boot animation player reads, so the player turns the
picture itself.

`bootanimation-compact.zip` is built for that player: upright like stock's own, one full-screen
frame with the Glassworks stack, the wordmark and the device line, held until Android has booted.
It's about 10 KB. The generator builds it because the Mirror's `frame.json` sets
`"compactBudget": 22901`. The stock install writes it in place of stock's own file, padded to stock's
exact size with an empty zip comment (`padZipToExactLength` in the installer's `stock.ts`), which every
zip reader skips.

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
