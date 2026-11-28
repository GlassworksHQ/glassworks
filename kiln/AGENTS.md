# Agent guide: the web installer (`kiln/`)

The repo-wide rules are in [`../AGENTS.md`](../AGENTS.md). The module contracts are in
[`src/README.md`](src/README.md), and the design with its verified sources is in
[`../frames/aqara-s1-plus/research/web-installer.md`](../frames/aqara-s1-plus/research/web-installer.md).
A new package with a README needs a branded header: a library banner if anyone could use it on its
own (like `improv-serial`), or the Kiln badge if it's part of Kiln. See
[Branding](../AGENTS.md#branding).

## One engine, frames as data

**Kiln's app and packages hold no frame-specific code or copy.** Everything about a frame is declared
in `frames/<id>/`: its steps and their transports, its inputs, checks, image and APK sources, the
words that name its hardware, its way back, and whether it has touch. That is `design/frame.json`
and `design/installer.json`, plus code in `frames/<id>/installer` only behind the typed step
interface, for what data can't say (patching an image, building a partition). **A new frame needs no
change under `kiln/apps` or `kiln/packages`.**

The engine gives every frame the S1's forgiveness, and no frame can turn it off: Try again from the
step's own screen, resume after a reload, waiting through a reboot, stall watchdogs, no touch it
doesn't declare, a way back on every screen that writes. A frame is public in Kiln (in the picker,
its page open to everyone) at `status` alpha or later with a `kiln` route, and only with a contract
for every flow. A tested device (`kind: tested`) has no route of its own: it installs as its `base`,
on the base's route, data and contract (`kilnRoute`, `installsAs` in `packages/frames`). It is `stable` only when it passes the whole contract: an alpha or beta frame may
still have marked failures. The design: [frames-as-data.md](../docs/design/kiln/frames-as-data.md).
The checks, in `apps/kiln/test/contract/`:

- `contract.test.ts`: every flow through the same failures on a fake device. Today's failures are
  marked in `expected-failures.json`; fix one and remove its line in the same PR.
- `gate.test.ts`: no `stable` frame with a mark, and no public frame or Kiln page without a contract.
- `frame-neutral.test.ts`: no new frame name in `apps/kiln/src` or `packages/*/src`. Its allowlist
  only shrinks.

**After its install, every frame runs the standard flow** (`apps/kiln/src/standard`): App, Wi-Fi,
Home Assistant, Panes, Radio, Done, on the same screens, routes and resume as the S1. Install paths
differ per frame; nothing after them does. A step goes only when the frame's data says so: hardware it
lacks (`frame.json`, `installer.json` `touch`), or `installer.json` `setUp.remove` with why. Never a
set-up of a frame's own, and never a step dropped because "the install already did it". Every flow
step has its `route` in `installer.json`; there is no per-frame page (`test/contract/routes.test.ts`).

Android's install (`/android`) runs on the engine's USB-debugging flow (`apps/kiln/src/device/usb*`, kiln-core
`UsbFrame`): a frame whose device runs its own Android declares its words in `installer.json` `device` and its code in
`frames/<id>/installer/src/kiln.ts`, as generic Android does. Its install from the device's own browser
(`/android/tablet`) is the same flow's `onDevice` page (`apps/kiln/src/device/onDevice`), at the route its
`installer.json` `onDevice` declares. Every frame's pages come from `[frame]`; `apps/kiln/src/app` has none of a frame's own.

## What it is

Plain TypeScript (ES2022, no framework), built with Vite, running over WebUSB in Chrome or Edge.
It does in the browser what the CLI does in `frames/aqara-s1-plus/tools/install/fel-install.sh` and `frames/aqara-s1-plus/tools/install.sh`:
FEL boot → our U-Boot's fastboot → backup → byte-exact Magisk patch → flash and read back → adb
provisioning. **The CLI is the reference.** When the two disagree, fix the browser side, or change
both in the same PR.

## Layout

The npm workspace root is the repo root (#114; the tool choice is #37, and switching to pnpm later is a
lockfile swap). Its `package.json` lists `kiln` (the page), `kiln/packages/*`, `kiln/apps/*`,
`frames/*/installer` and `packages/*`, and holds the shared tooling (TypeScript, Vite, Vitest) and
`tsconfig.base.json`. Run npm from the repo root.

| Path | What |
|---|---|
| `src/`, `test/`, `index.html` | The first installer page, which s1.edm.sh served: `app/` (the UI). s1.edm.sh now redirects to Kiln's S1 page; the page stays until #27 removes it |
| `s1-redirect/` | What s1.edm.sh serves: a redirect of every path to `kiln.glassworks.dev/aqara-s1-plus` (deployed by `web-deploy.yml`) |
| `apps/kiln` | Kiln on Next.js: the device picker and one install engine that runs every frame from its data, on the Kiln design (`docs/design/kiln`). See [One engine, frames as data](#one-engine-frames-as-data) and its [README](apps/kiln/README.md) |
| `packages/brand` | The Glassworks tokens, mark, and Kiln's favicon (the small cut of its ember-floor mark) and app icon, generated from glassworks.dev by `sync.mjs`. Don't edit `src/` |
| `packages/fel`, `fastboot`, `adb`, `bootpatch`, `setup-channel`, `improv-serial` | Protocol modules: TypeScript source, each with its own tests. `fel`, `fastboot`, `improv-serial` and `ext4` also go to npm as `@glassworks/<name>`, built from that source ([docs/releasing.md](../docs/releasing.md#npm-packages)); the rest aren't published. In the workspace every one stays `"private": true` |
| `packages/app-adb` | Our app over adb, the same on every frame (Glassworks by default, another app by its `AppId`): install, permissions, start with the Home Assistant URL, its version, its device admin. The S1's provisioning and the Mirror's recipe both use it; the S1's command sequence is pinned (`frames/aqara-s1-plus/installer/test/adb-commands-pin.test.ts`) |
| `packages/ext4` | Android sparse images and ext4 in the browser: stream a sparse image into OPFS, edit its ext4 (files, owners, SELinux labels), build a fresh ext4, stream it back out for fastboot. Checked against e2fsprogs in CI. See its [README](packages/ext4/README.md) |
| `packages/kiln-core` | The step engine: `InstallFlow`, the `FlowUi` contract, the `Recipe` types, backups, and image recipes (`images.ts`: the step library, Kiln's safety steps, validation; docs/kiln-images.md) |
| `workers/refract` | Refract, the Cloudflare Worker at `refract.glassworks.dev`: streams GitHub release files to Kiln with CORS headers and Range, storing nothing. An allowlist of other projects' files (the Mirror's image), and this repo's release lines (`releases.json`, written from the frames' `installer.json` `releases`): every Glassworks APK and its `.sha256`. **Every large file Kiln downloads goes through Refract, never through a Vercel function** (Kiln's own APK route got the Vercel team paused for Fast Origin Transfer, 2026-11-23). Not a root workspace: its own lockfile and CI job (`refract`), so wrangler and workerd stay out of the root `npm ci`. See its [README](workers/refract/README.md) |
| `../frames/aqara-s1-plus/installer` | The S1 recipe (`s1Plus`), provisioning, the signing-key switch, the app's Improv socket |
| `../frames/lululemon-mirror/installer` | The Mirror's recipe, stable (`/lululemon-mirror`: the app from its newest release, or `?apk=local`, a file). Over fastboot (#40): the Glass Panes image checked and patched (`image.ts`, which also leaves out the image's own kiosk app; `services.ts` makes it let in this browser's adb key, not the key the image names), a fresh userdata, the fastboot checks, the writes and the first boot (`flash.ts`). The app is the legacy build of Glassworks for generic Android, named in `design/frame.json` (`app`; `src/app.ts`). What the page can install, Glassworks, Glassworks on the stock software (Stock-in-place: `stage.ts` puts the MIT stock probe app on userdata as the setup agent, `inplace.ts` checks its bundle, patches `services.jar` in its own blocks with a restore image of the same blocks, and installs the app as Device Owner; the stock system stays) and the community images (Glass Panes), as recipes of data over kiln-core's step library (`src/choices.ts`; kiln-core `images.ts`; how to add one: `docs/kiln-images.md`); Glass Panes' copies with this browser's key in its system and userdata and the network saved (`src/wifi.ts`). Over adb on a Mirror already on the community image: that APK, upright, the only home screen by `pm hide` on the others (no tap: the Mirror has no touchscreen), undo unhides the one it had (`recipe.ts`) |
| `../frames/generic-android/installer` | Any Android 6+ device over adb (#403), Kiln's `/android` (stable: the newest `generic-android-v*` release, or `?apk=local` for a file; the engine's USB-debugging flow, `apps/kiln/src/device/usb*`, with this frame's `src/kiln.ts` and `src/sim.ts`). Reads the device, picks Glassworks or its legacy build from the APK, installs or updates in place, grants and reads back what adb can, sets HOME (`set-home-activity` on Android 7+, else the chooser), hands over to setup over the LAN. `devices.ts`: USB vendor ID or adb's maker and model to a brand and its sourced steps to turn on USB debugging. `browser.ts`: the same from the device's own browser, for Kiln's `/android/tablet` (no USB). Its command sequences per API level are pinned (`test/golden/generic-adb-commands.json`) |
| `../frames/*/design` | Each frame's design data: `frame.json` (catalog fields, the 3D model in mm) and `recovery.json`. Kiln's picker reads it; format and how the website reads it in [frames/README.md](../frames/README.md) |
| `../packages/frame-card` | The frame card and 3D device mockup, shared with glassworks.dev ([README](../packages/frame-card/README.md)). Kiln's picker wraps it in a radio item |
| `../packages/frames` | The frames registry: the one place that names frames, and Kiln's only way to one. `catalog` (each frame's design data, no code) and `kiln` (each engine frame's `kilnFrame` and `kilnSim`, loaded on demand). A new frame adds its imports to both |

A package never imports from `src/`. Packages import each other by name (`@glassworks/fel`), and
declare those names in their `package.json`. kiln-core names no frame (`test/no-frame-names.test.ts`).

Every workspace finds its dependencies in the repo root's `node_modules` by Node's own lookup, so
there is no `paths` or `resolve.dedupe` to keep in step. `test/workspace.test.ts` checks that each
workspace's dependencies resolve, to the one copy at the root.

## Rules

- **The boot patch must be byte-identical to `frames/aqara-s1-plus/tools/root/patch-boot-magisk.sh`.** That is why
  `packages/bootpatch/src/wasm/` compiles magiskboot's exact compression crates (`zlib-rs` level 9,
  `lzma-rust2` with the ARM Thumb BCJ filter). Don't swap in `CompressionStream`, pako or liblzma. If
  you change the overlay, change the shell script too and regenerate the goldens
  (`S1_WEB_GOLDEN=<dir> npm test`; without it the golden tests are skipped).
- **Backups come before writes, and writes are verified by read-back.** Keep the refusal points
  from the CLI: wrong SoC id, unexpected partition layout, a backup `boot` that is already patched,
  and on undo a backup whose splash is already ours (`test/refusals.test.ts`).
  **Check as much as you possibly can, and where a write can't be checked one way, check it another.**
  Read a write back where it was written when the device allows it. When it doesn't, verify it the
  next place Kiln reaches the device, and say in the log and the docs which check ran where. The
  example: the lululemon Mirror's bootloader has no fastboot upload, so its in-place install can't
  read its system write back in fastboot. Once the Mirror has started and Kiln is on adb, Kiln reads
  back each file the write changed (the frame's build names them, `InPlaceCode` `readBack`), hashes
  it in the browser and compares it with what it built, before the app's install. A mismatch stops
  there with the way back; a file Kiln can't read is logged as unchecked and the install goes on, since
  a failed read says nothing about the write. Which files and paths to check is the frame's data or
  code, never Kiln's.
  **The one write that isn't read back: the stock Mirror's restore (the in-place way back).** After it,
  nothing can read the device: its fastboot has no partition read, and the restore resets app data, so
  stock's adb no longer trusts Kiln (maintainer, 2026-11-25; the exception in
  [AGENTS.md](../AGENTS.md#hard-rules) rule 1). So Kiln checks everything it is about to write before
  the first write (`packages/kiln-core/src/restore.ts`), and writes nothing if any check fails: every
  file of the restore point has the size and SHA-256 recorded when it was made (its manifest's
  `kiln.files`); it was made on this device (the SHA-256 of fastboot's serial); its restore image is
  whole, sized for the partition, and fills nothing; that image puts back exactly the original files
  the frame names (`InPlaceCode` `restored`), byte for byte, and writes no other block; and, while adb
  still trusts Kiln before the restart, each of those files on the device is what the install wrote or
  still the original. After the writes, fastboot's OKAY on each one is the last check there is, and
  the log says so. Don't widen this exception to other writes, and don't drop a check before the write
  to make up for it.
- Assets (U-Boot, Magisk, the APK) are pinned by SHA-256 in `assets.lock.json` and checked on download.
  Bump the hash and the source together.
- Only `app/` touches the DOM. Other modules talk through their exports, so `test/flow.test.ts` can
  run the whole flow on simulated hardware (`packages/fel/test/fakes.ts`, imported as
  `@glassworks/fel/fakes`). New USB behaviour needs a fake for it.
- `oem run:` commands must fit fastboot's 64-byte command limit, prefix included.
- `packages/fastboot` is a fork of fastboot.js. Its [README](packages/fastboot/README.md) lists what
  differs from upstream and how to check that the S1's USB calls did not change.
- The installer is becoming **Kiln**, generic across frames. S1 specifics (partition sizes and
  names, the A133 check, `s1Overlay`, the debloat list, `PKG`) go in the S1 recipe, never in
  kiln-core.

## Commands

```sh
# from the repo root
npm install && npm run stage -w kiln   # stage pinned blobs into kiln/public/assets (gitignored)
npm run dev -w kiln                    # http://localhost:5173, a secure context, so WebUSB works
npm run typecheck && npm test && npm run build   # every workspace; both builds (the page, then Kiln)
npm test -w @glassworks/fel            # one package (or cd kiln/packages/fel && npm test)
npm run stage -w @glassworks/kiln-app && npm run dev -w @glassworks/kiln-app   # Kiln: http://localhost:5174
npm run build -w @glassworks/kiln-app  # next build
```

## Screenshots

UI changes need before and after screenshots in the PR. The dev server has simulated states that
need no panel: `?demo=wifi` and `?demo=wifi-fail` (see `demo()` in `src/app/ui.ts`). Add a demo
state when you add a screen, so the next person can capture it too. The images in
`docs/img/installer/` come from these states. Update them, as `.webp`, when a screen they show
changes.

Kiln's Next.js app has the same idea at `/aqara-s1-plus?demo=…` (`apps/kiln/src/wizard/demo.ts`):
`run` clicks through a simulated panel behind the real InstallFlow, the other names jump to one
screen, and `selftest` checks `bootpatch.wasm` and every staged blob in the built page. They work in
`next dev`, and in a build made with `NEXT_PUBLIC_KILN_DEMO=1`, never in a production build.
