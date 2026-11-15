# Agent guide: the web installer (`kiln/`)

The repo-wide rules are in [`../AGENTS.md`](../AGENTS.md). The module contracts are in
[`src/README.md`](src/README.md), and the design with its verified sources is in
[`../frames/aqara-s1-plus/research/web-installer.md`](../frames/aqara-s1-plus/research/web-installer.md).
A new package with a README needs a branded header: a library banner if anyone could use it on its
own (like `improv-serial`), or the Kiln badge if it's part of Kiln. See
[Branding](../AGENTS.md#branding).

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
| `src/`, `test/`, `index.html` | The page s1.edm.sh serves: `app/` (the UI). Stays until Kiln's Next.js app has passed a real install (#27) |
| `apps/kiln` | Kiln on Next.js: the device picker and one wizard per frame, on the Kiln design (`docs/design/kiln`). See its [README](apps/kiln/README.md) |
| `packages/brand` | The Glassworks tokens, mark, and Kiln's favicon (the small cut of its ember-floor mark) and app icon, generated from glassworks.dev by `sync.mjs`. Don't edit `src/` |
| `packages/fel`, `fastboot`, `adb`, `bootpatch`, `setup-channel`, `improv-serial` | Protocol modules: TypeScript source, each with its own tests. Not published (`"private": true`) |
| `packages/app-adb` | S1 Kiosk over adb, the same on every frame: install, permissions, start with the Home Assistant URL, its version, its device admin. The S1's provisioning and the Mirror's recipe both use it; the S1's command sequence is pinned (`frames/aqara-s1-plus/installer/test/adb-commands-pin.test.ts`) |
| `packages/ext4` | Android sparse images and ext4 in the browser: stream a sparse image into OPFS, edit its ext4 (files, owners, SELinux labels), build a fresh ext4, stream it back out for fastboot. Checked against e2fsprogs in CI. See its [README](packages/ext4/README.md) |
| `packages/kiln-core` | The step engine: `InstallFlow`, the `FlowUi` contract, the `Recipe` types, backups |
| `workers/refract` | Refract, the Cloudflare Worker at `refract.glassworks.dev`: streams an allowlist of other projects' GitHub release files (the Mirror's image) to Kiln with CORS headers, storing nothing. Not a root workspace: its own lockfile and CI job (`refract`), so wrangler and workerd stay out of the root `npm ci`. See its [README](workers/refract/README.md) |
| `../frames/aqara-s1-plus/installer` | The S1 recipe (`s1Plus`), provisioning, the signing-key switch, the app's Improv socket |
| `../frames/lululemon-mirror/installer` | The Mirror's recipe, test only for now (`/lululemon-mirror?apk=local`). Over fastboot (#40): the Glass Panes image checked and patched (`image.ts`), a fresh userdata with this browser's key, the fastboot checks, the writes and the first boot (`flash.ts`). Over adb on a Mirror already on the community image: S1 Kiosk's legacy APK, the home screen through Android's chooser, undo (`recipe.ts`) |
| `../frames/*/design` | Each frame's design data: `frame.json` (catalog fields, the 3D model in mm) and `recovery.json`. Kiln's picker reads it; format and how the website reads it in [frames/README.md](../frames/README.md) |
| `../packages/frame-card` | The frame card and 3D device mockup, shared with glassworks.dev ([README](../packages/frame-card/README.md)). Kiln's picker wraps it in a radio item |

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
