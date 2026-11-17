# Frames

A **frame** is one device's definition: its installer recipe, and the design data that describes it to
people. Each frame has a folder here, named by its id, and that folder is the one home for both: Kiln
and glassworks.dev read them from here.

| Folder | What |
|---|---|
| `<id>/README.md` | The frame's page: its banner, status, hardware, how it installs, its panes and its research notes |
| `<id>/installer/` | Kiln's recipe for the device (`@glassworks/<id>-installer`), an npm workspace of the repo root. Only frames that install from Kiln have one |
| `<id>/design/frame.json` | What the device looks like and what the catalog says about it. Every frame has one |
| `<id>/research/` | Its research notes (the index of every note: [docs/research](../docs/research/README.md)) |
| `<id>/design/recovery.json` | The steps into recovery mode, as drawing data (format: [docs/design/kiln](../docs/design/kiln/README.md#the-recovery-section)) |

| Frame | Status | Installer | Design |
|---|---|---|---|
| [`aqara-s1-plus`](aqara-s1-plus) | Beta | yes | `frame.json`, `recovery.json` |
| [`lululemon-mirror`](lululemon-mirror) | Planned | test only: over fastboot with the community image, or adb onto it (`/lululemon-mirror?apk=local`) | `frame.json` only (no recovery drawing yet) |
| [`generic-android`](generic-android) | Planned | headless recipe, no screen yet (#403) | `frame.json` only. Any Android device as an app, no root. It has no `display`: there is no one screen size |
| [`google-pixel-tablet`](google-pixel-tablet) | Planned | none of its own: it installs as `generic-android` | `frame.json` only. A tested device: the generic app as it is |

### Three kinds of device

Every frame says which kind it is (`kind`; maintainer, #403, 2026-11-16). The site shows it on the card and
the frame's page.

| `kind` | Shown as | What | Frames |
|---|---|---|---|
| `full` | Full frame | Its own app and a Kiln recipe (FEL, boot patch, root) | `aqara-s1-plus` |
| `device` | Device frame | The generic app, with a recipe or settings picked at runtime; no code of its own | `lululemon-mirror`, `generic-android` |
| `tested` | Tested device | The generic app as it is, installed like on any Android device. No recipe, no code. `base` names the frame it installs as | `google-pixel-tablet` |

`kindProblems()` ([`packages/frame-card`](../packages/frame-card/README.md)) checks it: a tested device has a
`base` that is another kind of frame, and no `kiln`, `app` or `recovery`; no other kind has a `base`.
glassworks.dev's build and Kiln's `frame-designs.test.ts` run it.

Every device glassworks.dev lists has a frame here, planned ones included. The site's URL for a frame is
its id here, always (`/frames/<id>`): a frame id never changes, because Kiln's routes, the boot generator
and the release tags (`aqara-s1-plus-v<version>`) use it. The site's one older slug, `/frames/aqara-s1`,
redirects to `/frames/aqara-s1-plus`.

## `design/frame.json`

The catalog fields from planning's `kiln.md`, and the `model` section from decisions.md ("frame.json
describes the device's look in millimetres"). Kiln's picker reads it, and glassworks.dev can too.

| Field | What |
|---|---|
| `id` | The frame's id, the same as its folder (and Kiln's route, `/<id>`) |
| `kind` | `full`, `device` or `tested` ([above](#three-kinds-of-device)) |
| `base` | A tested device only: the frame whose app and install it uses (`generic-android`) |
| `name` | The product's name, e.g. `Aqara S1 Plus` |
| `brand` | Optional. The maker's part of `name` (`Aqara`), set light in the frame's banner; the rest of `name` is bold. `name` must start with it and a space |
| `aka` | Optional. Other names it is sold under |
| `status` | `planned`, `development`, `alpha`, `beta` or `stable` (the status badges) |
| `summary` | One sentence on why this device |
| `tagline` | Optional. The frame's banner's line under `frames/<id>`, at most 50 characters. glassworks.dev's `pnpm brand` draws the banner and fails if it doesn't fit |
| `project` | Optional. `{ name, href }` of the frame's own project |
| `kiln` | Optional. Kiln's route, once the frame installs from Kiln |
| `model` | The device's look in mm, for the 3D mockup (below) |
| `recovery` | Optional. The recovery drawing data's file, next to this one |
| `render` | The screen's render, a `.webp` next to this one: Home Assistant's real frontend drawing the shared showcase dashboard at the frame's screen size ([below](#the-render)) |
| `abis` | The ABIs the device's userspace runs, preferred first, as Android names them (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`). The app's release builds one APK for every ABI some frame lists (below) |
| `display` | `{ width, height }`: the screen in pixels, upright, as people see it (the Mirror: 1080x1920). On a panel that stands turned, the boot animation plays in the panel's own framebuffer instead: say so with `boot.rotate`, not here. With `name`, it is all the [boot generator](../branding/boot/README.md) needs |
| `app` | Optional, for a frame whose Kiln recipe installs another app than S1 Kiosk: `id` (the package), `activity` (what adb starts it with, the one that trusts adb's extras), `home` (the activity or alias its MAIN+HOME+DEFAULT filter is on) and `admin` (its device admin receiver), full class names. The Mirror installs the legacy build of Glassworks for generic Android |
| `boot` | Optional. The boot branding's limits: `androidApi` (the API level; 25+ gets trimmed frames), `splash` (the bootloader splash format, `allwinner-boot-resource`), `zipBudget` (the largest `bootanimation.zip`, bytes), `rotate` (90 or 270: frames turned counter-clockwise into a panel that stands turned, as the Mirror's does) |
| `specs` | Hardware rows: `{ label, value }`. Labels: `Size`, `SoC`, `Android`, `Screen`, `Radio`, `Presence`, `Audio`, `Extras`, `Mount`, `Root` |
| `panes` | The panes it rates, by pane id: `{ support, note? }`. Support is a status or `depends`, `unknown`, `no`. The ids are the app's (the pane catalog, below); `base`, the wall base's old id, still works. A pane it doesn't rate is worked out from `provides`, with the reason |
| `provides` | Optional. The hardware it has, in the pane catalog's words: `microphone`, `speaker`, `silabs-radio`, `s1-wall-base`, `bluetooth-le`, `camera`. Leave it out when it varies by device (Generic Android); a pane that needs hardware then reads `depends` |
| `install` | `{ how, href? }`: one sentence on how it installs. `href` is only for a frame that installs from somewhere other than Kiln; a frame with `kiln` has none (the S1 installs from Kiln) |
| `ratings` | The nine install ratings, each `{ value, detail?, label? }` (`label`: the card's tag in the frame's own words, e.g. the Mirror's reversibility): `difficulty`, `reversible`, `modifiesAndroid`, `root`, `opensDevice`, `vendorApps`, `wipes`, `backup`, `updates` |
| `ratingsNote`, `notice` | Optional. A sentence of context; something a buyer must know |
| `unverified` | Optional. `true` when any of it comes from writeups rather than a real device |
| `credits` | Optional. The outside work the frame stands on, and who made it ([below](#credits)) |
| `facts` | Optional, not on a tested device. What its panels show, for the checklist ([below](#how-complete-it-is)) |
| `run` | A tested device only: its run on a real unit, which its checklist comes from ([below](#a-tested-devices-run)) |

`model`:

| Field | What |
|---|---|
| `body` | `{ shape: "rect", width, height, depth, radius }` in mm |
| `bezel` | The frame around the screen, mm |
| `screen` | `{ radius }`, plus `width`, `height` and `top` (mm from the body's top edge) when the screen is smaller than the face minus the bezel, e.g. behind a mirror |
| `sides` | `{ dark, light }`: the body's side colour facing away from the light, and facing it |
| `finish` | `glass` or `mirror` |
| `mount` | `wall`, `floor` or `lean`; `lean` takes `lean`, the degrees the top tilts back |
| `parts` | Optional. `{ kind: "tube", from, to, diameter, color }` outside the body, in mm from its centre (x right, y down, z towards you), e.g. a stand |

There is no SVG illustration per frame: the device is drawn from `model`. That's the site's 3D
mockup (glassworks.dev `deviceGeometry.ts`), now in the shared package
[`packages/frame-card`](../packages/frame-card/README.md), which Kiln draws with. Its output is the
same as the site's for both frames to within 0.001 of a stage unit (checked 2026-11-12). The Glassworks mark, badges and banners stay the
supplied SVGs from glassworks.dev/brand ([docs/branding.md](../docs/branding.md)); nothing here
redraws them.

`frame.json` is written by hand, and the build reads it; nothing generates it (maintainer, 2026-11-13,
#98). It differs from decisions.md's sketch in two names: the renderer needs side colours (`sides`)
where the sketch has `color.body`, and the screen is placed with `top` where the sketch has `offset`.

### Credits

Some frames exist because other people did the hard part: an Android image, a bootloader unlock, the
research on a board. Glassworks names them wherever a person meets their work, and never presents
it as ours. **A frame that uses someone else's image, exploit, tool or research credits it in
`credits`**, one entry per piece of work:

| Field | What |
|---|---|
| `id` | kebab-case, unique in the frame. The frame's Kiln recipe names its credits by it |
| `who` | `{ name, href }`: the person or group, as they publish (their handle is fine), linked to their profile |
| `what` | What they made, in a few words: `Android image`, `Bootloader unlock`, `Research`, `Teardown` |
| `project` | `{ name, href }`: the project, as they name it |
| `licence` | Optional. The licence as the project states it. Leave it out when it states none; don't guess one |
| `uses` | One plain sentence: what Glassworks uses from it, and where (a step, a research note) |
| `quote` | Optional. `{ text, href }`: their own words for the work, copied exactly, and the page they're from |
| `card` | Optional. `true` names it on the frame's card ("Built on Glass Panes by git-nimrod"). For the work the device can't run without, such as its image |

Where it shows:

| Where | What |
|---|---|
| glassworks.dev, the frame's page | **Built on community work**, under the header: every credit, with its links, licence, use and quote |
| The frame's card (site and Kiln's picker) | The `card` credit, as one line under the summary |
| Kiln | Next to each step that uses the work (`Credit`, e.g. "This image is built by git-nimrod as part of Glass Panes."), and on the frame's start page and done screen (`BuiltOn`), from `kiln/apps/kiln/src/components/Credit.tsx` |
| `frames/<id>/README.md` | A **Credits** section near the top, in words, with what the project itself credits in turn |

**Checked.** `creditProblems()` ([`packages/frame-card`](../packages/frame-card/README.md)) fails a
credit without its links or its words; glassworks.dev's build and Kiln's `frame-designs.test.ts` run
it. Each frame installer exports `credits`, a `StepCredits` from kiln-core (step name → credit ids;
`{}` when it uses no outside work), and `kiln/apps/kiln/test/frame-credits.test.ts` fails if an id
isn't in the frame's `credits`, or if an installer or Kiln links someone else's code on GitHub,
GitLab or Codeberg (a release to download, say) without a credit that a recipe uses.

Credits are not licence notices. Code we ship is listed in
[THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md); a credit is for the work a frame stands on,
shipped by us or not. The Mirror's image is credited even though it isn't our code: Kiln fetches it
from Glass Panes' GitHub release through Refract, our download proxy, which passes it through without
storing it.

| Frame | Credits |
|---|---|
| `aqara-s1-plus` | sunxi-tools (linux-sunxi), the FEL protocol Kiln's recovery-mode step follows; Magisk (topjohnwu), the root the boot patch adds |
| `lululemon-mirror` | Glass Panes (git-nimrod), the Android image it runs; research from repairman29, Onemoremichael and olm3ca; MDME's teardown |
| `generic-android` | None |
| `google-pixel-tablet` | None |

### `abis`

What the device runs, not what its release ships. The release builds the union of every frame's
`abis`, in frame-id order (`tools/release/frame-abis.sh`, and the root `build.gradle.kts` the same
way), so today: `armeabi-v7a` and `arm64-v8a`. `ApkAbiListsTest` checks that the app's update check
(`ApkAbi`) knows exactly that list.

| Frame | `abis` | From |
|---|---|---|
| `aqara-s1-plus` | `armeabi-v7a` | The panel's 32-bit userspace (`ro.product.cpu.abilist` = `armeabi-v7a,armeabi`, read on a panel 2026-11-12) |
| `lululemon-mirror` | `arm64-v8a`, `armeabi-v7a` | The community image's `ro.product.cpu.abilist` (`arm64-v8a,armeabi-v7a,armeabi`, read from its `system.img` 2026-11-12). Not read on a stock unit ([mirror-abis.md](lululemon-mirror/research/mirror-abis.md)) |
| `generic-android` | `arm64-v8a`, `armeabi-v7a` | The ARM tablets and phones it targets. No x86 APK is built |
| `google-pixel-tablet` | `arm64-v8a` | Tensor G2's 64-bit-only userspace, a public fact for the Pixel 7. Not read on a Pixel Tablet yet |

### The render

Every frame's card shows the same dashboard (maintainer, #144), so the renders are comparable:
`render.webp`, made by `node tools/showcase/render.mjs [frame-id ...]`.

- **What it shows.** The showcase home ([`tools/showcase/home.py`](../tools/showcase/home.py)): a
  made-up house in the evening, on HA's sections view. Nine sections: a clock, the weather with its
  forecast and the day's temperature; the living room (thermostat, lights, speaker with album art);
  the front garden (camera, lock, lights, garage door, scenes); energy (the week's use, today's
  power); the kitchen (lights, appliances, a shopping list); the bedroom (thermostat, light, blinds);
  air (humidity, CO₂, PM2.5, the purifier); security (a driveway camera, the alarm, the vacuum);
  media (the TV with its show's still, the kitchen radio, the soundbar and more). The two camera
  stills are CC0 photos from Wikimedia Commons, graded to dusk, with nothing readable and no faces
  ([`tools/showcase/photos/`](../tools/showcase/photos/README.md)); the TV still and the album art are
  drawn by `home.py`.
  The clock shows a time and no date; the graphs cover today only, so their axes name no day.
- **Full screens.** Every section is 10 grid rows tall (`SECTION_ROWS`, tested), so each row of
  sections ends level and no column stops early. For each landscape frame the script picks the zoom,
  from density 0.6 to 1.1, so the screen's bottom edge falls just under a row of sections: as many
  whole rows as fit. The Mirror's zoom is set by hand (`PINNED` in `render.mjs`) to three columns of
  three sections: a fit can't hold three columns there, because the number of columns changes with
  the density it tries. Every render fails if the bottom edge cuts a row, a shown row ends unevenly
  (by its lowest card, more than 4 px), or the Mirror isn't three by three.
- **How.** It serves that home from Clarity's fake Home Assistant with the frontend build Clarity
  pins ([`tools/perf/hafrontend.py`](../tools/perf/hafrontend.py)), and opens it in headless Chrome as
  the kiosk shows it: no header, no sidebar, HA's default theme in dark mode. The page's clock is held
  at the home's `NOW` (19:30 UTC). A frame without one screen size (`generic-android`) is drawn at
  1920×1200, a common 10" tablet.
- **Same bytes every run.** All data and pictures are seeded, and Chrome rasters on one thread
  (`--num-raster-threads=1` and friends; without them a few edges changed from run to run). Checked
  with two runs on 2026-11-13, Chrome 157.

Redo the renders when HA's look changes, i.e. when Clarity's pinned frontend moves. **A new frame needs
its render too**: run `node tools/showcase/render.mjs <id>` and add `"render": "render.webp"` to its
`frame.json` (Kiln's `frame-designs.test.ts` fails without it). That needs Chrome (or `CHROME=<path>`),
Node 22+ and python3; the first run fetches the pinned frontend from PyPI (117 MB, checked by SHA-256)
into `~/.cache/glassworks-perf`. Kiln's `catalog.ts` then imports the new file.

| Frame | Size | Density | Shown | File |
|---|---|---|---|---|
| `aqara-s1-plus` | 1440×720 | 0.989 (fitted) | 1 row of 4 sections | 42.4 KiB |
| `lululemon-mirror` | 1080×1920 | 0.94 (pinned) | 3 rows of 3 | 77.5 KiB |
| `generic-android` | 1920×1200 | 0.867 (fitted) | 2 rows of 4 (the ninth section is below the edge) | 73.4 KiB |
| `google-pixel-tablet` | 2560×1600 | 0.784 (fitted) | 3 rows: 4, 4 and 1 sections | 73.8 KiB |

`kiln/apps/kiln/test/frame-designs.test.ts` checks every `design/frame.json`: Kiln lists it, every
rating and pane is there, `recovery` exists, the render is a WebP at the screen's size, and the mockup
draws without NaN.

## The panes a frame runs

Each pane in the app has a `pane.json` and an `icon.svg` next to its code (issue #231): its id, name,
what it does, what hardware it `requires`, its status, and `frame` for a frame pane such as the wall
base. `packages/frame-card` collects them (`PANES`, `PANE_IDS`; [its README](../packages/frame-card/README.md#the-pane-catalog)),
and `PaneCatalogTest` in the app fails if a pane has no file or a file has no pane. A pane's `blueprints`
lists its Home Assistant blueprints ([docs/blueprints.md](../docs/blueprints.md)).

A frame's rating of a pane is the one in its `panes`, if it gives one. Otherwise `paneSupport()` works
it out, and says why:

1. A frame pane (`frame`) runs on its own frame only: "Only on the aqara-s1-plus frame.", or with the optional
   `frameName` lookup the caller passes, "Only on the Aqara S1 Plus."
2. A pane that needs nothing special runs at its own status, or is `planned` while the frame doesn't ship.
3. A frame without `provides` can't say: `depends`, "Needs a microphone."
4. A frame that lacks something the pane needs can't run it: `no`, "Needs a microphone."
5. Otherwise it runs at the pane's status (`planned` while the frame doesn't ship): "Has a microphone."

## How complete it is

Each frame's page on glassworks.dev shows how complete Glassworks is on it: a checklist of eight
items, and one word above it (maintainer, #423, 2026-11-17). It is not a letter grade: Clarity owns A to
F. `completeness()` in [`packages/frame-card`](../packages/frame-card/README.md) works both out from
`frame.json` when the site is built, so they can't drift from the data. Nothing is written by hand
except a reason the data can't give (`facts.notes`).

### The items

Each item is ✓, ✗ or n/a, with a short reason. A fact that is left out is "not checked", and that is
a ✗, never a ✓.

| Item | Ticked when | From |
|---|---|---|
| **Install** | One path in Kiln, nothing to tap on the device's screen, and a way back. Opening the case to press a button (the S1's FEL) is a physical step, which the install ratings show, not a tap | `kiln` (a tested device: its `base`'s), `ratings.reversible` (`yes` or `partly`), `facts.install` |
| **Silent updates** | Updates install with no touch at all, from Home Assistant's update entity. An update that needs a tap doesn't count, on any Android version | `facts.silentUpdates`: the panel's *Silent updates* sensor, word for word. Ticked on "Yes: …" only |
| **Hardware** | Everything the device has works in Glassworks | Every word in `provides` needs a pane that runs on the frame now ([above](#the-panes-a-frame-runs)); `facts.hardware` lists the rest that doesn't work yet. A frame without `provides` says "Depends on the device" |
| **Kiosk** | Our app is the only home screen, no vendor screens show through, it comes back after a reboot, and the screen sleeps and wakes | `facts.kiosk`: `onlyHome`, `noVendorUi`, `survivesReboot`, `sleepsAndWakes` |
| **Setup** | It sets up without a computer: on its own screen, the setup hotspot, or Bluetooth | `facts.setup`: `screen`, `hotspot`, `bluetooth`, `computer` |
| **Recovery** | Back to stock, or at least to a working state | `ratings.reversible` and its `detail` |
| **Branding** | Our boot animation and splash, with no maker's logo after power-on | `facts.branding`: `{ bootAnimation, vendorLogo }`. A tested device has no recipe of its own, so the maker's stay |
| **Proof** | Clarity graded on a real unit, and the frame's panel runs done | `clarity/clarity.json` ([Clarity](../docs/perf.md)), `facts.panelRuns` |

`facts.na` lists the shown-only items that don't apply (recovery, branding, proof), each with its
reason in `facts.notes`. An n/a counts as ticked, so an item that decides the word can't be n/a:
`factProblems()` refuses it, and `completeness()` ignores it. `facts.unverified` lists the items whose facts come from code or notes, not yet from a real
unit, and the page marks them. A frame with `unverified: true` is unverified throughout.

### The word

Only install, silent updates, hardware, kiosk and setup decide it. Recovery, branding and proof are
shown and don't count: a stock tablet that does everything but the boot animation doesn't sit a level
below a rooted panel because the maker's logo shows for five seconds.

| Word | When |
|---|---|
| **Full** | The frame ships (`status` alpha or later), and every item that counts is ticked or n/a |
| **Good** | It ships and installs from Kiln, and something that counts is missing |
| **Basic** | It ships, and doesn't install from Kiln |
| none ("Not rated yet") | It doesn't ship yet. The checklist still shows, as its to-do list |

When a counted item is unverified, the page says the word is unverified too.

### A tested device's run

A tested device has no frame code, so its facts come from one recorded run on a real unit, like
Clarity's: Kiln's `--plan` and `--install` results
([`run-node.ts`](generic-android/installer/run-node.ts)), what the panel reports after, and what the
person saw. It goes in `run`, and a tested device has no `facts`.

| Field | What | Becomes |
|---|---|---|
| `on` | The day it ran, `YYYY-MM-DD`. Without it, the values are what we expect and every item says unverified | Proof's panel run |
| `app` | The app's version, or the test build, that ran (`test build 9755076`) | |
| `plan` | From `--plan`: `model`, `android` (the version's name), `sdk`, `abi`, `app` (the package). Nothing else: no serial, fingerprint or address, and `factProblems()` refuses any other key, here and in each part of `install`, `panel` and `seen` | |
| `install.grants` | From `--install`: `microphone`, `camera`, `secure-settings`, `overlay`, `device-admin`, each `granted`, `not-asked` or `refused` | A refused microphone or camera is missing hardware, unless `seen.hardware` is recorded (below) |
| `install.silentUpdates` | `silentUpdates.state` in the JSON `--install` prints | |
| `install.home` | `{ how, state }`: `set-home-activity` or `chooser`, `set` or `unconfirmed` | Kiosk's `onlyHome` (`set`). USB debugging is always a tap on the device, and the chooser is one more |
| `install.setupPage` | Kiln handed off to the app's setup page | |
| `panel.silentUpdates` | Optional until someone reads it: the panel's *Silent updates* sensor afterwards, word for word | Silent updates. Left out, it is ✗ "No panel has reported it yet" |
| `seen` | What the person saw that neither reports: `hardware`, `kiosk` (all but `onlyHome`), `setup`, `notes` | The same items. `seen.hardware` is what they saw last, so it supersedes the install's refusals: a grant given by hand after the install and seen working is `[]`, and a refusal that stayed is written there |

After the run, the root sets `on` and the values it found, and drops the frame's `unverified`. Each
fact the run didn't cover stays out, and its item says "not checked": a ✗, not an unverified ✓.

`keyProblems()` refuses any top-level key that isn't a `frame.json` field above, so a `serial` can't
sit beside `run` either. The site's build and Kiln's `frame-designs.test.ts` run it with
`factProblems()`.

### Where the frames stand

Worked out from today's data (2026-11-17):

| Frame | Word | Why |
|---|---|---|
| `aqara-s1-plus` | Good | Everything that counts is ticked but hardware: the wall base sends no button presses over its UART, and the Ethernet base isn't supported ([base-protocol.md](aqara-s1-plus/research/base-protocol.md)) |
| `lululemon-mirror` | Not rated yet | Planned, and not in Kiln yet. Its silent updates and home screen were seen with the probe app, not yet with Glassworks, so they're marked unverified. Nothing records it coming back after a reboot, so that isn't checked |
| `generic-android` | Not rated yet | Planned, and not in Kiln yet. Silent updates and hardware depend on the device |
| `google-pixel-tablet` | Not rated yet | Planned. The root's run on 2026-11-17 (`run-node.ts --plan`, then `--install`, generic test build 9755076; Home Assistant added it and the QR sign-in loaded the dashboard) gives setup ✓. Install isn't in Kiln on main and needs USB debugging. Its *Silent updates* sensor, hardware and three kiosk checks aren't read yet |

## What came from glassworks.dev

All read from [GlassworksHQ/glassworks.dev](https://github.com/GlassworksHQ/glassworks.dev) at
`main` = `fbfd364f5f87570b3f0a95639340b7ac4cb38a6e` (2026-11-12). Nothing was pushed there.

| Site file | Here | How |
|---|---|---|
| `src/lib/devices.ts`: the `aqara-s1` and `lululemon-mirror` entries | `aqara-s1-plus/design/frame.json`, `lululemon-mirror/design/frame.json` | Data copied; the S1's id is this repo's `aqara-s1-plus` and its project link is GlassworksHQ's |
| `src/lib/devices.ts`: the `generic-android` entry, read at `main` = `8abae2d` (2026-11-12) | `generic-android/design/frame.json` | Data copied field for field. The S1's `install.href` (s1.edm.sh) came from the same read, and was dropped later, because the S1 installs from Kiln. Nothing else differed, apart from the S1's project link |
| `src/components/deviceGeometry.ts`: `MODELS.s1`, `MODELS.mirror`, `MODELS.tablet` | The `model` sections of those files (`tablet` is generic-android's) | Data copied; the Mirror's stand, computed in code there, written out as 13 tubes. The tablet is a generic shape, not a measured device |
| `src/components/deviceGeometry.ts`: `geometry()` | `packages/frame-card/src/geometry.ts` | Ported; takes the model as an argument. Same output to 0.001 |
| `src/components/DeviceModel.tsx`, `DeviceModel.module.css` | `packages/frame-card/src/DeviceModel.*` | Ported; colours mapped to the brand tokens and the device paint (`paint.ts`) |
| `src/components/DeviceCard.tsx`, `DeviceCard.module.css`, `TiltCard.tsx`; from `DeviceBits.tsx`/`.module.css`: `StatusPill`, `StageMark`, `RatingTags`; from `src/lib/devices.ts`: the types, `cardTags`, `statusText`; four glyphs from `Glyphs.tsx` | `packages/frame-card/src/FrameCard.*`, `frame.ts` | Ported as the card's body, pill and tilt; the outer element is the caller's (Kiln's radio item is `kiln/apps/kiln/src/components/FrameCard.tsx`) |
| `src/app/frames/frames.module.css`: `.grid`, `.add`; `src/app/globals.css`: `.glass` | `kiln/apps/kiln/src/app/globals.css` (`.frames`, `.yours`, `.glass`) | Restyled from the same rules |
| `src/app/globals.css` tokens, `src/lib/markGeometry.ts`, `public/brand/favicon-kiln.svg`, `public/brand/app-icon-kiln.svg` | `kiln/packages/brand/src/` | Generated by `kiln/packages/brand/sync.mjs`, pinned in its `source.json`: today `33166cc` on the site's `brand/kiln-favicon` branch (GlassworksHQ/glassworks.dev#12, which adds the two Kiln icons from the site's generator), until that merges |

## How the website reads it

glassworks.dev ([`site/`](../site/README.md)) reads each frame's `design/frame.json` and the card
that draws it ([`packages/frame-card`](../packages/frame-card/README.md)) from this repo at build
time, so there is one copy of each, and a frame change and its page change in one PR. How is in
[that package's README](../packages/frame-card/README.md#how-glassworksdev-takes-it).

When frames move to their own repos (#35), the site points at each frame's released `frame.json`
instead.
