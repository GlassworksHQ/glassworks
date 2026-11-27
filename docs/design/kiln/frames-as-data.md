<p>
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30">
    </picture>
  </a>
</p>

# Kiln: one engine, frames as data

Kiln is one install engine. A frame doesn't get its own wizard: it declares its install, and the
engine runs it. Every frame gets the same forgiveness the S1 has, and no frame can turn it off. This
note says what a frame declares, what the engine owns, and how the contract checks it. Issue #621.

## Why

On 2026-11-19 the maintainer ran Kiln's Mirror install for real for the first time
([results on #607](https://github.com/GlassworksHQ/glassworks/pull/607#issuecomment-7003487963)). It
broke where the S1 never does:

| What happened on the Mirror | What the S1 does |
|---|---|
| Connect during the first boot was refused, and the run was over | Keeps asking through the boot |
| A reload went back to the start; finishing meant a reflash | Remembers the run in the tab, and `/set-up` resumes over adb |
| The app-only path waited for a tap; the Mirror has no touchscreen | No step waits for a tap |
| The copy asked to hold a fastboot button inside the case | Asks for a button only where nothing else gets in (stock S1: FEL) |

Three causes, all fixed by this plan:

1. `kiln/AGENTS.md` described `apps/kiln` as "one wizard per frame". The Mirror and Android pages
   took it at its word and each grew their own session and screens.
2. The shared wizard (`apps/kiln/src/wizard`) was never frame-neutral: it imports the S1's copy,
   radio plan and FEL. Only `kiln-core` was, and only `kiln-core` had a test for it.
3. Nothing compared frames. Each page had its own demos and tests, and no shared failure suite.

## What a frame declares

All of it at the frame level, in `frames/<id>/`. A new frame needs no change under `kiln/apps` or
`kiln/packages`.

| What | Where | Example (S1 / Mirror) |
|---|---|---|
| Its route in Kiln, its catalog facts | `design/frame.json` (`kiln`, `status`, …) | `/aqara-s1-plus` |
| Touch: can the person tap the device's screen during the install? | `design/installer.json` `touch` | `true` / `false` |
| Its flows, and each flow's steps in order | `installer.json` `flows.<flow>.steps` | S1: connect, install, first-boot, app |
| Which of the engine's flows runs each flow | flow `engine`: `image` (whole images over fastboot), `app` (the app alone over adb), `in-place` (the device's own software changed in place) | Mirror: `fastboot` is `image`, `stock` is `in-place`, `app` is `app` |
| Each step's transport | step `transport`: `fel`, `fastboot`, `adb`, `usb`, `agent`, `none` | Mirror install: `fastboot`; its in-place `receive`: `agent` |
| How the engine enters that transport | step `enter`: `button` (with `reachable`), `loader`, `boot` (with the key the image trusts), `usb-debugging`, `adb-reboot-bootloader` | Mirror fastboot: a button inside the case today; `adb-reboot-bootloader` in phase 2 |
| What the person gives | step `inputs` | Mirror: APK, Home Assistant, Wi-Fi |
| What the step checks before it writes | step `checks` | S1: chip, layout, boot read twice |
| What it downloads | step `downloads`: pinned assets or release files | S1: `uboot-spl`, `uboot`; Mirror: the Glass Panes image |
| Whether it writes, and whether the device restarts after | step `writes`, `reboots` | S1 install: both |
| Taps on the device's own screen | step `taps`; none on a frame without touch | Android: `allow-usb-debugging` |
| Its way back | `installer.json` `wayBack`, or a flow's own `wayBack` | S1: restore the backup; Mirror: flash the Glass Panes images, and for its `stock` flow, `restore-blocks` |
| Words that name its hardware | `installer.json` `words`: the USB names at each "your turn", help links, the way back's words, the Silent updates words by state, and, optionally, how long the install and its steps usually take (`time`) and the numbered steps into recovery mode (`recoverySteps`) | "USB Device (1f3a:efe8)", "about 10 minutes" |
| Recovery drawing | `design/recovery.json` | (unchanged) |

A frame that doesn't say a flow's `engine` gets `image` for a flow with a `fastboot` step and `app`
for one without, as before the field existed.

**The `agent` transport.** An in-place flow restarts the device into a small setup agent of the
frame's own (the Mirror's is the MIT stock probe app in `tools/mirror/stock-probe-app`). Kiln takes
the agent's bundle over the channels the frame names in its code (`InPlaceCode.channels`: the
agent's HTTP port and path, and its Bluetooth GATT service and characteristics): this browser's saved
copy first, then the local network, then Bluetooth, and a file the person downloads from the agent's
own page at any time. The step is read-only. Kiln checks the bundle (SHA-256, serial, the run's
signature) and then the frame's code does, before it is kept.

**`wayBack: restore-blocks`.** For an in-place flow, `{ "how": "restore-blocks", "restore": "<file>" }`
names the restore image its `in-place` step makes. Before the first write of what `in-place` built,
Kiln puts in its own **restore point** step, which a recipe can't name or skip: the restore image is
saved in this browser (the frame's `storage.inPlaceDb`), read back and checked by SHA-256 before
Continue opens, and offered as a zip download. The way back writes it over the blocks the install
changed, from this browser or from that zip, after restarting the device into fastboot over adb when
it can (or with the button, when it can't). The frame gets undo's routes (`undo`, `undo/connect`,
`undo/restore`, `undo/done`) for it, as a `restore-backup` frame does.

`installer.json` is a draft today. The contract reads `route`, `touch` and each step's `id`,
`writes`, `reboots`, `downloads` and `taps`. The engine starts reading the rest in phase 2, and the
format may change then.

### What stays code

Data can't patch a boot image or build an ext4 partition. That code stays in the frame's installer
package (`frames/<id>/installer`), behind a small typed step interface the engine calls:

```ts
interface FrameSteps {
  /** Throws unless this is the frame's device. Read-only. */
  check(step: string, device: Transport): Promise<string>;
  /** The bytes a writing step writes, made from the backup and the downloads. No device access. */
  build(step: string, ctx: BuildContext): Promise<Write[]>;
  /** After the device is up: set the app up over adb. */
  setUp?(panel: PanelShell, inputs: Inputs): Promise<void>;
}
```

`kiln-core`'s `Recipe` is the S1's version of this today (`checkChip`, `checkLayout`, `patchBoot`,
`writesBeforeBoot`, `setUp`). The Mirror's image building (`image.ts`, `buildImages`) and Android's
`installGeneric` become `build` and `setUp`. Code never decides order, retries, waits or screens.

Since phase 2's first step, the shared wizard reads a frame only through `KilnFrame` (kiln-core's
`frame.ts`): the `words` above, checked where they come in (`frameWords`), its recovery drawing and
photo, and `FrameCode`. Each frame's installer package exports its whole `kilnFrame`, the S1's in
`frames/aqara-s1-plus/installer/src/kiln.ts`:

| `FrameCode` | What it says | The S1's |
|---|---|---|
| `recipe` | The device steps | `s1Plus` |
| `appId` | The app on the device | Glassworks' `sh.edm.s1kiosk` |
| `appOptions` | The recipe's setUp options from the settings step | Asks before a signing-key switch |
| `wifiName` | The network the device is on, read-only | `dumpsys wifi` |
| `updates`, `updateStates` | A key of `words.silent`, read-only, and every key it can give (each needs its words) | The boot image's root update service |
| `giveBack` | Undo's "keep its settings": the device's own apps back | `unprovision` |

**A frame's real install and its demo device are separate files.** `kiln.ts` is the real install: its recipe is
`recipe.ts`, the steps the panel runs. `sim.ts` (`kilnSim`) is only for Kiln's `?demo=…` states and the tests: the
recipe with FEL, the layout check and the patch faked, the getprops a current device answers, and the log lines a demo
install shows. The frame Kiln installs (`KilnFrame`) never carries it. The demo path takes it beside the frame, as a
`DemoFrame` (`{ frame, sim }`), and `FrameWizard` uses it only when demos are on.

**The frames registry** (`packages/frames`, `@glassworks/frames`) is the one place that names frames,
and the only way Kiln reaches one. `catalog` is each frame's design data (frame.json, installer.json,
its render), light enough for the picker, the routes and the release list (Refract's `releases.json` is written from it). `kiln` loads each engine
frame's `kilnFrame` and `kilnSim` on demand. One route, `apps/kiln/src/app/[frame]`, serves every frame
on the shared wizard (the catalog's `ENGINE`), titled from its `words.name`. Release lines come from
`installer.json` `releases`, and the first installer page's links from `legacy`. A wizard frame's `apk` names the
recipe asset that is its app and the release line it comes from (`{ "line", "asset" }`): Kiln downloads that APK from
the line's newest release when someone installs (`/api/release/<line>/latest`, then Refract, checked against the
release's `.sha256`), never from the files its deploy staged, so a release reaches Kiln with no deploy. A file staged
under the same name (a local `npm run stage`) still wins, for testing a build before its release.
`frame-neutral.test.ts` checks both halves: no frame name in Kiln's sources, and no import of a
frame's package or a path into `frames/` except through the registry. Its allowlists only shrink.
**A frame installed by image and adb** (the Mirror) declares a `DeviceFrame` (kiln-core `device.ts`)
instead of the wizard's `KilnFrame`. Its data is its `installer.json` `device`: what it is called
(`short`, `the`), its board, the APK's release line, its timings, the assets its image adds, the
browser storage it keeps, every word only it says, the rows its app install shows, and its Silent
updates words. Its code, in its installer package, is `appFlow` (check the APK, read the device,
install, undo) and `imageFlow` (its image recipes, its fastboot checks, the first start, the
only-home-screen step, Wi-Fi on, and its image builder in a worker). The engine's device flows
(`apps/kiln/src/device`) run every step and every screen for any frame of that kind. Its install
hands the device to the standard flow, below.

**Every frame has the same page and the same kind of routes.** There is no `page` in `installer.json`
any more (the catalog refuses one). Each flow step declares its `route` under the frame's route (`""`
is the route itself) and a `title`; Kiln builds those, the standard flow's and, for a frame whose way
back restores a backup, undo's (`apps/kiln/src/app/[frame]/frames.ts` `routesOf`). The address follows
the step, a reload there resumes it, and going to one never starts anything. `test/contract/routes.test.ts`
checks every step has its route and the standard flow's are there, and the contract's `reload@<step>`
cases check the resume.

### The standard flow

After its install, every frame runs the same steps on the same screens: **App** (the app's language,
time zone and clock), **Wi-Fi**, **Home Assistant**, **Panes**, **Radio**, then **Done**
(`apps/kiln/src/standard/`). The engine owns them, their routes (`/set-up`, `/set-up/wifi`,
`/set-up/home-assistant`, `/set-up/panes`, `/set-up/radio`, `/done`) and their resume. The S1's
install session extends it; a device frame's page hands its device to one at the end of its install,
and its Done carries what that install found (the home screen, Home Assistant's key, the way back).

The App step installs the app where the install didn't (the S1, over FEL), and applies the settings
to the app already on where it did (the Mirror), with no reinstall. Panes shows once the app's setup
state lists its cards (the setup channel's `panes`, `panes.set`). Every frame's app does: the step is the core's
(`PanesStep`), the same answer as the device's own Panes screen, and a pane the frame lacks the hardware for has no
card, so Kiln never shows it.

A frame changes the list only in its data. A step its hardware lacks goes by itself: no radio in
`frame.json` `panes.radio`, no Radio step; no touch (`installer.json` `touch`), no on-screen keyboard
on the App step. Any other step a frame leaves out is in `installer.json` `setUp.remove`, by name, with
why (`{ "setUp": { "remove": { "panes": "…" } } }`); a name that isn't a step, or no reason, fails where
the data comes in. No frame has a set-up of its own.

The radio's next steps (`setup/radioPlan.ts`) are the radio pane's, not a frame's: the panel's own
setup page shows them on any frame whose app reports a radio.

## What the engine owns

For every frame, with no flag to turn it off:

1. **Retry from the step's own screen.** Try again lands on the step that stopped, or on the step
   that enters its transport when the device needs entering again. Never on an earlier input screen.
2. **Resume after a reload or an error**, at any step, without redoing a finished step's writes. A
   read-only step may run again (a download, a loader in RAM). The tab remembers that the device may
   have been changed (the S1's `registry.ts`, in sessionStorage).
3. **Wait through a reboot.** After a restart, keep asking the device (refused auth, no device yet,
   `sys.boot_completed` still 0) instead of failing on the first refusal or timeout.
4. **Stall watchdogs** on every network and USB wait. A download that stops sending ends with an
   error the person can retry (`kiln-core`'s `Assets`: 30 s without a byte).
5. **No touch it doesn't declare.** Never a step that needs a tap on a frame without touch, or a
   button inside the case when the device runs adb (`adb reboot bootloader` instead).
6. **A way back on every screen that writes**, from `wayBack`.
7. **Errors say whether anything was written**, and the one most likely fix.

## The contract

`kiln/apps/kiln/test/contract/` drives every frame's flow through the same failures on a fake
device, and checks it ends where the S1 does:

| Case | The failure | Passes when |
|---|---|---|
| `clean` | none | it reaches Done with the device set up: the app in, and the home screen read back off the device |
| `auth-refused-while-booting` | adb refuses 3 times as the device comes up | it keeps asking through all 3 and reaches Done, with no extra click |
| `usb-drop` | USB drops halfway through the first writing step | an error, Try again on that step's screen (or the one before), then Done with nothing left partly written and no finished step's writes redone |
| `reload@<step>` | the page reloads during each step | it knows a write may have happened, and reaches Done without redoing a finished step's writes |
| `stalled-download` | the first download stops sending | a watchdog ends it with an error, Try again on that step's screen (or the one before), then Done |
| `no-touch` | nobody taps the device beyond the declared taps | it reaches Done |
| `channel-drop` | in a flow with an `agent` step, the local network transfer drops halfway | Kiln carries on over Bluetooth by itself and reaches Done, with no error screen |

The cases come from `installer.json`: a reload in each step, a drop in the first step that writes,
a stall only for a flow that downloads, a channel drop only for a flow with an `agent` step. A driver per flow (`s1.ts`, `mirror.ts` for the Mirror's three, `android.ts`) does
what a person does: one click per screen visit, and the physical steps the frame declares.

**Expected failures.** A case a flow fails today is in `expected-failures.json` with the issue that
fixes it, and runs as `it.fails`. The suite is green now, and turns red when someone fixes a case
without removing its line. So the marks stay honest.

**The gate.** A frame is public in Kiln when its `frame.json` has a shipping `status` (alpha, beta or
stable) and a `kiln` route, the picker's rule: its card can be picked and its page is open to everyone.
`gate.test.ts` refuses a `stable` frame while any of its flows has an expected failure (decided
2026-11-20: an alpha or beta frame may keep its marks, and the picker shows its stage). It also fails
when a public frame or a Kiln page has no `installer.json`, or a flow has no driver.

**Frame-neutral.** `frame-neutral.test.ts` fails when `kiln/apps/kiln/src` or `kiln/packages/*/src`
gain a frame's id, path or hardware name outside `frame-neutral-allowlist.json`. Today's hits are
there with #621 as the reason. The list only shrinks: an entry with no hit left fails too.

### Where each frame stands (2026-11-19, main)

The S1 passes every case. It needed one engine fix: `kiln-core`'s `Assets` had no stall watchdog.

| Flow | Fails |
|---|---|
| Mirror, fastboot | `auth-refused-while-booting`, `usb-drop` (Try again goes back to the files), `reload@install`, `reload@first-boot`, `stalled-download` |
| Mirror, app only | `auth-refused-while-booting`, `reload@install`, `no-touch` (Android's chooser) |
| Android | `auth-refused-while-booting`, `reload@install` |

## Phases

**Phase 1** (#621, done with the PR that adds this note): this note, `installer.json` for the three
frames, the contract with today's expected failures, the gate, the frame-neutral check, and the rule
in `kiln/AGENTS.md`, `docs/principles.md` and the root `AGENTS.md`. Files that open PRs change
(#596, #603, #607, #617) were left alone.

**Phase 2**, after #596, #603, #607 and #617 merge:

1. Make `apps/kiln/src/wizard` frame-neutral first. The S1's copy, `radioPlan` and FEL move behind
   the frame's declaration and its `FrameSteps`. The allowlist shrinks as they go. Done: the wizard,
   `setup/radioPlan.ts` and the S1's words in `SilentUpdates.tsx` name no frame. FEL stays an engine
   transport the S1 declares (`connect`'s `transport`).
2. Move the Mirror onto the shared engine, then Android, one frame per PR. Delete their own sessions
   (`mirror/session.ts`, `mirror/flash.ts`, `android/session.ts`) and screens. Each move deletes
   that frame's lines in `expected-failures.json`, and its driver becomes the shared one. The
   Mirror's move came in two parts. First the frames registry and one `[frame]` route (#652). Then
   the Mirror as a `DeviceFrame` on the engine's device flows, with no Mirror code left in Kiln.
   Both left its behaviour as it was, demo states byte-identical. So its expected failures stay
   until the flows themselves get the S1's forgiveness: resume after a reload, waiting through a
   refused auth, a stall watchdog on the image download.
3. Enter fastboot over adb (`adb reboot bootloader`) where a frame declares its button unreachable.
   Add contract cases for it, for the way back on every writing screen, and for errors that say
   whether anything was written (engine rules 5 to 7 have no case yet).
4. Add `docs/design/kiln/README.md`'s row for this note (the file is open in #596).
5. When the Mirror has no expected failures, its page can go public.
