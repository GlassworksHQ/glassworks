# Agent guide: Glassworks

Glassworks is a device-neutral, open Home Assistant panel core. Its reference device ("frame") is the
Aqara Panel Hub S1 Plus (Allwinner A133, Android 10, 32-bit userspace), which it turns into an open
wall panel. The repo was S1 Unleashed until #527.

This file applies to the whole repo. Each area has its own file with the details:
[`frames/aqara-s1-plus/android/AGENTS.md`](frames/aqara-s1-plus/android/AGENTS.md) (the kiosk app),
[`kiln/AGENTS.md`](kiln/AGENTS.md) (the browser installer),
[`frames/aqara-s1-plus/tools/AGENTS.md`](frames/aqara-s1-plus/tools/AGENTS.md) (FEL, U-Boot, root side,
radio, install scripts), [`tools/AGENTS.md`](tools/AGENTS.md) (the shared tools).
Breaking into hardware has its own playbook: [`docs/research/bringup.md`](docs/research/bringup.md).

## Layout

| Path | What | Language |
|---|---|---|
| `settings.gradle.kts` | The Gradle root is the repo root. This file maps every module to its folder | Kotlin |
| `build-logic/` | What every module builds with: `glassworks.android` (compileSdk, minSdk 26, JDK 17, the `testing/kotlin` tests) and `glassworks.jvm` (JDK 17). A module's build file keeps its namespace, dependencies and what differs | Kotlin |
| `frames/aqara-s1-plus/android` | The S1 app (`:kiosk`), the GeckoView kiosk app: code in `dev.glassworks.s1`, app id `sh.edm.s1kiosk` (never change it) | Kotlin, Android |
| `frames/generic-android/android` | Glassworks for generic Android (`:generic-android`, `dev.glassworks.generic_android`): the core as an app for any Android 8+ device, no root | Kotlin, Android |
| `core/`, `pane-api/` | The Glassworks core (`dev.glassworks.*`) and the pane API every pane builds on | Kotlin, Android |
| `engines/engine-gecko` | The GeckoView engine and the kiosk's web extension | Kotlin, JavaScript |
| `engines/engine-vitro` | Vitro, the experimental native Lovelace renderer (Settings → Labs), wrapping GeckoView; its debug test app (`:vitro-test`) in `test-app/`. Its WebRTC cameras are `engines/engine-vitro-webrtc` on `libs/ha-webrtc`. Users' guide: [docs/vitro.md](docs/vitro.md), internals: [its README](engines/engine-vitro/README.md) | Kotlin, JavaScript |
| `engines/conformance` | The engine contract every engine passes (`:engine-conformance`, emulator tests only): [docs/engine-contract.md](docs/engine-contract.md) | Kotlin |
| `panes/<id>` | One module per pane (`:pane-<id>`): assist, camera, diagnostics, notifications, sendspin, timers, bluetooth-proxy. A frame's own panes sit in `frames/<id>/panes/` | Kotlin, Android |
| `libs/*` | Libraries split out of the app: `esphome-api` (JVM), `esphome-api-android`, `improv`, `bluetooth-proxy`, `sendspin`, `voice`, `ha-webrtc` | Kotlin |
| `kiln/` | Kiln, the WebUSB installer (FEL → fastboot → boot patch → adb), served at kiln.glassworks.dev (s1.edm.sh redirects there). The old page in `src/`, protocol modules in `packages/`, the Next.js app in `apps/kiln`. One engine for every frame: a frame is declared in `frames/<id>/`, never coded into Kiln ([kiln/AGENTS.md](kiln/AGENTS.md#one-engine-frames-as-data)) | TypeScript, Vite, Next.js, Rust→WASM |
| `frames/<id>/` | Everything about one frame: its README, its Kiln recipe (`installer/`), its design data (`design/frame.json`, `recovery.json`) and its research notes (`research/`) | TypeScript, JSON, Markdown |
| `packages/` | Packages Kiln and glassworks.dev share: `frame-card`, the frame card and 3D device mockup; `frames`, the frames registry Kiln reaches every frame through | TypeScript, React |
| `site/` | glassworks.dev, the project site: landing page, `/frames`, `/panes`, `/brand` and the brand generator. Reads `frames/`, `packages/frame-card` and Clarity's grade from this repo at build time | TypeScript, Next.js |
| `testing/` | Test helpers every Gradle module's tests share: `RepoFiles`, which finds source files across all modules from the repo root (`testing/kotlin`); the GeckoView shadows and harness that run the real MainActivity in the two apps' tests (`testing/gecko`) | Kotlin, Java |
| `frames/aqara-s1-plus/tools/` | The S1's own tools. `fel/`, `install/`, `install.sh`: FEL boot, U-Boot board files and patches, the CLI installer. `root/`, `radio/`: init services in the patched boot image (root helper, update and bundle services) | bash, `/system/bin/sh` (mksh), C |
| `tools/` | Tools every frame shares: Clarity (`perf/`), release, size, showcase, doc checks | Python, bash |
| `docs/` | User guides; `docs/research/` holds the general engineering notes and the index of every note | Markdown |
| `branding/` | Boot logo, boot animation, splash | Python |

## Commands

```sh
./gradlew test :kiosk:assembleDebug                 # app + libs: unit tests and a debug APK
npm install && npm run stage -w kiln && npm test     # npm workspaces, from the repo root (also: npm run typecheck, npm run build)
npm run lint -w site && npm run build -w site        # glassworks.dev (site/), after the install above
frames/aqara-s1-plus/tools/install/test/install-tests.sh   # install scripts, off-device
python3 tools/check-doc-links.py                     # relative links and #anchors in docs
python3 tools/check-status.py                        # status tables and badges agree with frame.json and pane.json
frames/aqara-s1-plus/tools/root/build-verify.sh && frames/aqara-s1-plus/tools/radio/test/run.sh  # root side, in an armhf container (Docker)
python3 -m unittest discover -s branding/boot/test   # boot generator: frame targets and layout
python3 -m unittest discover -s tools/size/test      # APK size per frame and pane (CI's size job)
python3 branding/boot/generate.py --frame aqara-s1-plus --out branding/glass-tile && git diff --exit-code branding
                                                     # the S1's boot branding rebuilds byte for byte (CI: in python:3.11-slim)
```

CI (`.github/workflows/pr.yml`) runs all of these. Run the ones for the area you touched before
you open a PR.

## Principles

Glassworks is an opinionated, simple Home Assistant panel controller. **Read
[`docs/principles.md`](docs/principles.md) before you propose or build a feature**, and check new
panes against [What makes a pane](pane-api/src/main/java/dev/glassworks/README.md#what-makes-a-pane).
In short: HA is the brain, ESPHome is the wire, the dashboard is the UI, and nothing gets installed
in HA. If a request goes against it, say so and point at the page instead of building around it.

## Hard rules

1. **Never brick the panel, and always keep a way back.** Read first, write second. Back up every
   partition you are about to write, hash it, and verify writes by reading them back. Prefer
   RAM-only paths (FEL, `fastboot boot`) to flashing. Every user-facing change needs an undo path:
   `--undo`, `debloat.sh enable`, the installer's restore. One exception, decided (maintainer,
   2026-11-25): Kiln's restore of a stock lululemon Mirror (the in-place way back) isn't read back
   after it is written, because nothing can read it then: the Mirror's fastboot has no partition read,
   and the restore resets app data, so stock's adb no longer trusts Kiln. It is verified before the
   write instead, as far as Kiln can reach
   ([kiln/AGENTS.md](kiln/AGENTS.md#rules)). The exception covers only that restore, and only its
   read-back after the write.
2. **No secrets or device-unique values anywhere**: not in commits, logs, PR bodies, screenshots or
   test fixtures. That covers HA tokens, API keys, Wi-Fi passwords, Thread datasets and network keys,
   serials, MACs, EUI64s, PAN IDs and vbmeta hashes. Use RFC 5737 addresses (`192.0.2.x`),
   `homeassistant.local` and made-up network names in examples and mockups. Crop or blur real ones.
3. **No Aqara binaries or firmware in the repo.** Dumps and backups live outside it (`dumps/` is
   ignored). Describe what you read, and give offsets and symbols, but never commit the bytes.
4. **No GPL payloads shipped by us.** Magisk is downloaded from its official release and checked by
   hash, never vendored. Licences of new dependencies go in `THIRD-PARTY-NOTICES.md` and `LICENSES/`.
   One exception is decided (maintainer, 2026-11-16): `desugar_jdk_libs` (GPL-2.0 with the Classpath exception)
   in the generic app's legacy APK, for Android 6–7, and in the legacy engine's debug test app
   (`dev.glassworks.s1.legacy`). The maintainer extended it on 2026-11-25 to Vitro's debug test app
   (`:vitro-test`, `engines/engine-vitro/test-app`, `dev.glassworks.vitro.test`, never released). Any other GPL
   code in an APK needs the maintainer's call.
5. **Changes to the boot patch, the root helper, the radio or anything in `frames/aqara-s1-plus/tools/root` need a real
   panel before merge.** Say so in the PR. Off-device tests alone are not enough there.
6. **Updates must keep installing in place.** Don't change `applicationId`, the signing setup or
   `versionCode` logic. `tools/release/check-update-compat.sh` guards this, so don't weaken it.
7. **Don't drive the shared dev panel blindly.** Other sessions may be using it. Check what is
   installed (`adb shell dumpsys package sh.edm.s1kiosk | grep version`) and which radio mode is on
   before you change anything, and put it back the way you found it.

## Pull requests

- Small PRs against `main`, filled in from `.github/pull_request_template.md`: **What and why**,
  **Tested on**, **Checklist**, **Screenshots**.
- **Tested on** says exactly what ran: a real panel (model, the S1 app version or test build branch,
  HA version), the installer in which browser, or unit tests only. List what you did on the panel,
  step by step, as in #52.
- **Screenshots are required for every UI change.** That covers the app (setup screen, settings
  menu, overlays, voice, timers), the web installer, and anything the boot branding shows. Give
  **before and after** for changes to an existing screen, and after alone for a new screen. Caption
  each image with what to look at. How to capture them:
  - On the panel: `adb exec-out screencap -p > after.png` (1440×720). Wake the screen first with
    `adb shell input keyevent KEYCODE_WAKEUP`.
  - Without a panel: the app's Robolectric mockups at the panel's size
    (`./gradlew :kiosk:testDebugUnitTest --tests '*Mockups*' -Ps1MockupDir=<dir>`); add a mockup
    for a new view. The installer's dev states: `npm run dev -w kiln`, then `?demo=wifi`, `?demo=wifi-fail`, ….
  - *Before* comes from `main`, at the same state and size as *after*.
  - Upload them to the PR body (GitHub user attachments). Don't commit PR screenshots to the repo;
    only images that docs use go in `docs/img/` (as `.webp`).
  - From the CLI, `gh` (2.101+) uploads them with `--attach '<file>#<alt text>'` on `gh pr create`,
    `gh pr edit` and `gh pr comment` (repeat the flag, up to 50 files). A body reference such as
    `![Before: setup screen](./before.png)` is rewritten to the uploaded asset; files the body
    doesn't reference are appended. `gh pr edit --attach` without a body flag keeps the body and
    appends. Example: `gh pr edit --attach './after.png#After: the Wi-Fi step'`.
- **Changelog.** Add a line under **[Unreleased]** in `CHANGELOG.md` (the S1 app's) or
  `frames/generic-android/CHANGELOG.md` (the generic app and its legacy build; both, if both change) when someone running a panel
  or using the installer would notice the change: a feature, fixed behaviour, a new entity, a
  changed default, a new requirement. Internal refactors, CI and docs-only changes usually don't
  get one. The style:
  - Put it under `### Added`, `### Changed` or `### Fixed` (create the heading if it's missing).
  - Start with a **bold one-line summary**, then a sentence or two in plain words about what the
    user sees, not how the code does it.
  - Say if it needs something to reach panels, e.g. "Comes with the radio bundle."
  - The release workflow publishes this section as the release notes, and the first line of each
    version is what Home Assistant shows on the update card.
- **Docs.** Update the frame README's status table (`frames/<id>/README.md`) and `docs/` when
  behaviour, entities or install steps change. A frame's or pane's status lives only in its `frame.json` or
  `pane.json`: change it there, then make the README tables and badges say the same
  (`python3 tools/check-status.py` fails until they do). Keep `python3 tools/check-doc-links.py` passing.
  The docs are also published at glassworks.dev/docs: a new doc file gets a line in
  `site/src/lib/docs/sources.ts`, and the site's build fails on a link it can't resolve.
- Labels: `frame: …`, `pane: …`, `area: …` ([CONTRIBUTING.md](CONTRIBUTING.md#labels)).
- To try a branch on a panel without wiping app data, push it to `test-build/<name>` (admins only)
  and `adb install -r` the artifact. Details in [`app/AGENTS.md`](frames/aqara-s1-plus/android/AGENTS.md#trying-a-branch-on-the-panel).

## Document the break-in work

Reverse-engineering and bring-up work is the most expensive knowledge in this project, and the
easiest to lose. Whenever you probe hardware, firmware, a protocol or a vendor binary, **write it
down in the same PR**, even if the attempt failed. Frame-specific notes go in
`frames/<id>/research/`, general ones in `docs/research/`:

- **The thought process ("How we got here"):** explain the problem and constraints, the hypotheses
  and paths you weighed, why earlier ideas failed or didn't fit, and the chain of insights that led
  to the solution. A research note should read like the real investigation—not just a bare dump of
  final facts.
- What you tried, the exact commands, and what came back (redacted logs).
- What it rules out. Dead ends save the next person days; the S1's `findings.md` on the stock USB burn mode
  is the model for this.
- How sure you are: **verified** (ran it, with the date), **read** (from source or a binary, with
  the file and offset), or **untested**.
- Scripts you used to find it. Put reusable ones under `tools/` with a header comment that says
  what they touch (RAM only? read-only?). Don't leave them only on your machine.

Use [`docs/research/TEMPLATE.md`](docs/research/TEMPLATE.md) for a new note, add it to the table
in [`docs/research/README.md`](docs/research/README.md) (the index of every note), and, for a
frame's note, to that frame's README. The general approach and the S1's lessons
are in [`docs/research/bringup.md`](docs/research/bringup.md).

## Writing style

Match the voice to the audience:

- **User docs, READMEs, website copy, blog posts, and UI strings:** Write natural, clear, conversational **American English** (`color`, `license`, `center`, `behavior`). Vary sentence length so prose reads smoothly—avoid choppy staccato fragments, periods on headings or short UI labels, repetitive "Not X, Y" antitheses, and awkward possessive chains ("the panel's own page", "an earlier one's"). Keep user-facing docs completely free of PR numbers (`#240`), commit SHAs, internal issue references, and dated lab timestamps (`measured 2026-11-14`). Prefer tables for comparisons and numbered steps for procedures, and stay honest about beta or experimental status without overselling.
- **Hardware research notes (`docs/research/`, `frames/*/research/`):** Keep these clear, direct, and grounded in evidence, while still telling the story of the investigation. Open with the problem, the hypotheses and alternatives you weighed, and the reasoning that led to the breakthrough ("How we got here"), then back every claim with exact commands, redacted outputs, dated measurements (`2026-11-10`), dead ends, and confidence tags (**verified**, **read**, or **untested**).
- **Upstream communication:** Mail and patches to upstream projects (U-Boot, TF-A, Magisk, Home Assistant) should stay concise and follow each project's own conventions.

## Branding

Follow the Glassworks brand: [glassworks.dev/brand](https://www.glassworks.dev/brand). Which badge or
banner each part of this repo uses, and how to add one for a new library, is in
[`docs/branding.md`](docs/branding.md). Use the supplied SVGs. Don't redraw or recolour them. Ember is
Kiln's colour only, and status colours mean status only.

**Give every README you create a header, and check the README of any part you touch.** Copy the
header from the example in the table, then change the name, the tagline and the status:

| The README is for | Header | Copy it from |
|---|---|---|
| The repository's front page (the root `README.md`) | `banner-glassworks`, centred title and tagline, then the status and roadmap badges | [README.md](README.md) |
| A library: something another project could use on its own (`libs/*`, `packages/*`, or such a package in `kiln/packages/`) | `banner-library-<name>`, centred title and tagline, then the MIT, status and roadmap badges | [improv](libs/improv/README.md) |
| The Glassworks core or a pane | `banner-core` or `banner-pane`, title and tagline, then the part badge, licence, status and roadmap badges | [core](pane-api/src/main/java/dev/glassworks/README.md) |
| Kiln | `banner-kiln`, title and tagline, then the Kiln, MIT, status and roadmap badges | [Kiln](kiln/apps/kiln/README.md) |
| An app or product built on the core | Title and tagline, then Built on Glassworks, MIT, status and roadmap badges. No banner | [the S1 app](frames/aqara-s1-plus/android/README.md) |
| A frame (`frames/<id>/README.md`) | `banner-frame-<id>`, centred title and tagline, then Built on Glassworks, the Kiln badge if Kiln installs it, the status, roadmap and Clarity badges | [aqara-s1-plus](frames/aqara-s1-plus/README.md) |
| Kiln's internals and design work | The Kiln badge alone | [docs/design/kiln](docs/design/kiln/README.md) |
| A Glassworks spec in `docs/` that every frame follows (e.g. the perf grade) | The core badge alone, under the title | [principles.md](docs/principles.md) (its part badges) |

Not every README gets one: research notes, scripts in `tools/*` (the doc they serve carries the badge), the old S1 installer in `kiln/src`
and S1's own branding stay plain. The S1's own detail (status, install, radio, FAQ) lives in
[its frame README](frames/aqara-s1-plus/README.md), not the root one.

- **A new library needs its banner first.** If `banner-library-<name>` isn't on
  [/brand#banners](https://www.glassworks.dev/brand#banners), add the library to glassworks.dev
  (steps in [docs/branding.md](docs/branding.md#adding-a-library)) and say in the PR that it waits on
  that deploy. Don't merge a README that points at a banner that isn't live yet. The banner's line
  fits about 50 characters: render it (`rsvg-convert`) in both themes and look before you open that PR.
- **A fork credits upstream in the header**: the second line under the title says "A fork of
  [owner/repo](link) (licence).", and the body records the upstream commit. Keep upstream's
  copyright in `THIRD-PARTY-NOTICES.md`.
- **Use the Glassworks info badges, not shields.io.** The licence badge is `badge-license-mit` for
  MIT and `badge-license` for MPL-2.0. Pick the one status badge that's true now, and change it when
  the stage changes.
- Every image is a `<picture>` with `-on-dark.svg` and `-on-light.svg`, so it follows GitHub's theme.
  Alt text uses the brand's names: "Glassworks Core", "Glassworks Kiln", "<name>, a Glassworks library".

## Naming (Glassworks)

**Frame** = a device definition (the S1 is `aqara-s1-plus`). **Pane** = a plugin (radio, wall base,
voice, Sendspin), which declares the hardware it `requires`. **Kiln** = the installer (`kiln/` plus
`frames/aqara-s1-plus/tools/install.sh`). All Kotlin goes under `dev.glassworks.*`: the core, the panes, and each app's own code (the S1's in
`dev.glassworks.s1`, the generic app's in `dev.glassworks.generic`). The S1 app keeps its id, `sh.edm.s1kiosk`, because
updates install in place and the boot helpers hardcode it. The core's Android components (`MainActivity`, `AdminReceiver`,
`BootReceiver`, `KioskService`, `ImprovActivity`, `UpdateInstallReceiver`, `CameraAdbActivity`) live in `dev.glassworks.*`.
The S1 app declares one-line subclasses at five `sh.edm.s1kiosk.*` names that Android, the field and the root side hold
(MainActivity, AdminReceiver, BootReceiver, `improv.ImprovActivity`, `update.UpdateInstallReceiver`) and hands them to
the core (`AppComponents`). Nothing else goes under `sh/edm` (`OldPackageGuardTest`, #527).
