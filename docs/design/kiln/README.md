<p>
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30">
    </picture>
  </a>
</p>

# Kiln: design prototypes

Design work for Kiln, the Glassworks installer (#32). Nothing here is part of the shipped installer
in `kiln/`. It's the reference for building Kiln's UI, and for the per-frame recovery animation.

| File | What |
|---|---|
| [sketch.html](sketch.html) | The Kiln screens: panel, connect, your turn, install, Wi-Fi, Home Assistant, done, undo, error |
| [navigation.md](navigation.md) | Moving through Kiln: the rail as the map, one Back in every foot, locks behind a write, Start over as a dialog, the resume screen, and the phone's step bar. Draft, built on a branch |
| [system.md](system.md) | Kiln's screens, round two: what people hit on a real install, the principles, and the component kit every screen is built from (progress, waits, instructions, the browser's list, notes, errors, the restore point's copy). Draft, built on a branch |
| [community-images.md](community-images.md) | Community images (#579): the choice on a frame's page, the community install, its Done and the way back. Approved 2026-11-19 and built |
| [lineageos.md](lineageos.md) | Kiln installs LineageOS, then Glassworks (#748): LineageOS's devices counted by install method, how Kiln knows the device, downloads and CORS, each step with its undo, the copy, the spike and the libraries. Design, post-v1 |
| [recovery/player.js](recovery/player.js) | The recovery-mode player: draws any frame's device and animates its steps from data |
| [recovery/aqara-s1-plus.json](recovery/aqara-s1-plus.json) | The S1 Plus's recovery data |
| [recovery/index.html](recovery/index.html) | Preview: plays `?frame=<id>` from `<id>.json` in that folder |
| `img/` | The screens and the recovery loop, rendered for the issue |

To view them, serve this folder (the pages load their JSON with `fetch`):

```sh
cd docs/design/kiln && python3 -m http.server 8765
# http://localhost:8765/sketch.html
# http://localhost:8765/recovery/index.html?frame=aqara-s1-plus
```

![The S1 Plus recovery loop](img/kiln-recovery-s1.gif)

## Principles

- **One idea per screen.** A short heading, at most one line under it, one ember button.
- **Details on request.** The browser check is a chip in the header and only becomes a screen when
  it fails. Partitions, hashes and the raw log sit behind "Technical log". Driver setup appears only
  on Windows and Linux.
- **The stack is the progress.** The rail's stack sets layer by layer from Kiln's ember floor as
  the install goes; a failure shows the layer dashed in rose.
- **Plain log.** A few ✓ lines in plain words ("Backed up and checked"), not partition names.
- **Errors** say what happened, whether anything was written, and the one most likely fix.

Kept from the current installer, in this lighter form: "your turn" when the browser needs a click
(now a sheet over the step), progress that never goes backwards, the backup download on Done, undo
with its two depths, and "skip, do this on the panel" during setup. Worth adding in Kiln: a
leave-page warning during writes, a screen wake lock, and live-region announcements.

## The recovery section

Each frame describes the physical steps into recovery mode as data, in millimetres, in the
panel's own coordinates (lying face down, the long edge at `y = 0` furthest away). The player
draws the device at the brand's 30° angle and animates the steps. Adding a frame means measuring
it, not drawing an animation. This section moves into the generated `frame.json` once #35 lands.

Kiln reads the S1's data from [`frames/aqara-s1-plus/design/recovery.json`](../../../frames/aqara-s1-plus/design/recovery.json),
which is the canonical copy, and the Mirror's from
[`frames/lululemon-mirror/design/recovery.json`](../../../frames/lululemon-mirror/design/recovery.json).
The same test checks every frame's file: known fields and moves, everything on the device, and a
drawing with no `NaN` at any moment. [`recovery/aqara-s1-plus.json`](recovery/aqara-s1-plus.json) is this
prototype's input and adds only `photo`. Kiln's tests fail if the two differ otherwise
(`kiln/apps/kiln/test/recovery-data.test.ts`).

| Field | Meaning |
|---|---|
| `body` | `w`, `h`, `thickness`, `radius` in mm |
| `cover.screws` | Screw centres, `[x, y]` mm, in the order they come out. A screw in a side face is `[x, y, z]`: on the face at `x = w` or `y = h` (the two the drawing shows), `z` mm down from the back |
| `cover.features` | What the back looks like: `ribs`, `plate`, `hole`, `clip`, `grille` (a speaker's holes), `recess` rectangles (`at`, `size`). A `recess` is the body's: the back has a hole there, and it stays when the back lifts off |
| `stand` | Optional. A stand fixed to a side face the drawing shows: `edge` (`right` is `x = w`, `bottom` is `y = h`) and, for a bar, `depth`: mm out from that face. The side screws in that face hold it, and it comes off once they're out. Optional `tube`: a U of tube from that face (or the bar's ends), `inset` mm in from the corners and `z` mm down from the back, out to `reach` mm, `diameter` thick, its corners bent to `bend` mm; `closed` adds its top bar along the face, which the screws go through (the Mirror's matches its 3D model in `frame.json`) |
| `board` | What's under the cover: `speaker`, `shield`, `chip` rectangles, `window` circles, and `button` (`at` only): a button next to the one to hold, drawn plain |
| `button` | The button to hold: `at` in mm, and `what` (a sentence for the photo's caption) |
| `port` | `edge` (`top`, `bottom`, `left`, `right`), `at` (mm along that edge), `kind` |
| `cord` | Optional. A power socket under the back: `at` in mm, `toward` (`right` or `bottom`), the face its cord runs out of, and `kind`. The `cord` move plugs it in |
| `power` | Optional. A power switch in a side face the drawing shows: `edge` (`right` or `bottom`), `at` (mm along it), `kind` |
| `steps` | Moves, in order: `screws`, `cover`, `cord`, `hold`, `plug`, `power`. The last of `plug` and `power` is the one that switches the device on; `ready` counts from it |
| `labels` | Optional. A step chip's words in place of the default, by move (the Mirror's `hold` is "Hold VOL−") |
| `ready` | `seconds` until recovery mode, and what the label `says` |
| `photo` | A real photo behind "Show me a photo" |

Between moves the player adds its own transitions: a faint pass of light over the board once the
cover is off, a spotlight that closes on the button, then opens to the port while a guide line
shows where the cable goes. The plug slides in square to its edge; if that face is out of view, the
panel hides the part that goes in. With reduced motion it shows one still frame.

Buttons, the plug and the switch are drawn to the device's scale. On a device far bigger than the S1
(the Mirror) that makes them small, so the camera goes in close on each of them, and pulls back
halfway when it moves between two close-ups far apart.

Moves still to add when a frame needs them: `clip` (pry the case open), `press` (a button
combination on the case) and `wait`.

## S1 Plus

Measured from photos of the back and of the board. Nine silver screws hold the back. The button is
right of the shield's notch, below the round sensor window. With the button held, the panel enters
recovery mode as soon as USB-C goes in, within about two seconds.
