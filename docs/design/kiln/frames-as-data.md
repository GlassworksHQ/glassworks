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
| Each step's transport | step `transport`: `fel`, `fastboot`, `adb`, `usb`, `none` | Mirror install: `fastboot` |
| How the engine enters that transport | step `enter`: `button` (with `reachable`), `loader`, `boot` (with the key the image trusts), `usb-debugging`, `adb-reboot-bootloader` | Mirror fastboot: a button inside the case today; `adb-reboot-bootloader` in phase 2 |
| What the person gives | step `inputs` | Mirror: APK, Home Assistant, Wi-Fi |
| What the step checks before it writes | step `checks` | S1: chip, layout, boot read twice |
| What it downloads | step `downloads`: pinned assets or release files | S1: `uboot-spl`, `uboot`; Mirror: the Glass Panes image |
| Whether it writes, and whether the device restarts after | step `writes`, `reboots` | S1 install: both |
| Taps on the device's own screen | step `taps`; none on a frame without touch | Android: `allow-usb-debugging` |
| Its way back | `installer.json` `wayBack` | S1: restore the backup; Mirror: flash the Glass Panes images |
| Words that name its hardware | the frame's copy (today `apps/kiln/src/frames/aqara-s1-plus.ts`; it moves in phase 2) | "Hold VOL−" |
| Recovery drawing | `design/recovery.json` | (unchanged) |

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

## What the engine owns

For every frame, with no flag to turn it off:

1. **Retry from the step's own screen.** Try again lands on the step that stopped, or on the step
   that enters its transport when the device needs entering again. Never on an earlier input screen.
2. **Resume after a reload or an error**, at any step, without redoing a step that finished. The tab
   remembers that the device may have been changed (the S1's `registry.ts`, in sessionStorage).
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
| `clean` | none | it reaches Done with the device set up |
| `auth-refused-while-booting` | adb refuses 3 times as the device comes up | it keeps asking and reaches Done, with no extra click |
| `usb-drop` | USB drops in the first writing step | an error, Try again on that step's screen (or the one before), then Done without redoing a finished step |
| `reload@<step>` | the page reloads during each step | it knows a write may have happened, and reaches Done without redoing a finished step |
| `stalled-download` | the first download stops sending | a watchdog ends it with an error, and Try again reaches Done |
| `no-touch` | nobody taps the device beyond the declared taps | it reaches Done |

The cases come from `installer.json`: a reload in each step, a drop in the first step that writes,
a stall only for a flow that downloads. A driver per flow (`s1.ts`, `mirror.ts`, `android.ts`) does
what a person does: one click per screen visit, and the physical steps the frame declares.

**Expected failures.** A case a flow fails today is in `expected-failures.json` with the issue that
fixes it, and runs as `it.fails`. The suite is green now, and turns red when someone fixes a case
without removing its line. So the marks stay honest.

**The gate.** `gate.test.ts` refuses a frame that is public in Kiln (a shipping `status` and a `kiln`
route in `frame.json`, the picker's rule) while any of its flows has an expected failure. It also
fails when a Kiln page has no `installer.json` or a flow has no driver.

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
   the frame's declaration and its `FrameSteps`. The allowlist shrinks as they go.
2. Move the Mirror onto the shared engine, then Android, one frame per PR. Delete their own sessions
   (`mirror/session.ts`, `mirror/flash.ts`, `android/session.ts`) and screens. Each move deletes
   that frame's lines in `expected-failures.json`, and its driver becomes the shared one.
3. Enter fastboot over adb (`adb reboot bootloader`) where a frame declares its button unreachable.
   Add contract cases for it, for the way back on every writing screen, and for errors that say
   whether anything was written (engine rules 5 to 7 have no case yet).
4. Add `docs/design/kiln/README.md`'s row for this note (the file is open in #596).
5. When the Mirror has no expected failures, its page can go public.
