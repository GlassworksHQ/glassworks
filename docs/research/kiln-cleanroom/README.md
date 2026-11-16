# Kiln clean-room rewrite: rules, specs and vectors

**Status (2026-11-15): specs and vectors ready. The bootpatch rewrite passes the vectors and the S1 goldens, and waits on a real panel; the FEL rewrite is not started.** Tracked in #355.

Kiln's FEL client (`kiln/packages/fel`) and boot patcher (`kiln/packages/bootpatch`) hold code derived
from sunxi-tools (GPL-2.0-or-later) and Magisk v31.1 (GPL-3.0-only). The maintainer decided on
2026-11-15 to rewrite those parts in a clean room, so Kiln and both packages stay MIT and hard rule 4
holds. The audit that found the code is in #348.

The work is split between two people:

| Role | Has read the GPL sources? | Does |
|---|---|---|
| Spec owner | Yes | Wrote these specs and recorded the vectors from the old code. Answers questions in #355 in behaviour terms, never with code |
| Implementer | **No**, and must not | Deletes the listed files unread, writes new code from the specs, the public docs and the vectors |

## For the implementer

1. Read [fel-spec.md](fel-spec.md) and [bootpatch-spec.md](bootpatch-spec.md), and the public
   documents they cite.
2. Delete the files marked "delete without opening" in each spec's **Files** table. Use `git rm`, and
   don't open, `cat`, diff or `git log -p` them first.
3. Write the new code. For the ARM routines, write the assembly yourself from the ARM Architecture
   Reference Manual, and keep the `.S` source next to the words.
4. Make the vectors pass: `npx vitest run` in each package. Then pass the whole workspace
   (`npm run typecheck && npm test` from the repo root), the S1 goldens on a machine that has them,
   and one real panel install (rule 5; the root thread runs it).
5. When something in a spec is unclear, ask in #355. Don't look at the old code or upstream to settle
   it.

### Don't open

- **sunxi-tools**: any file of https://github.com/linux-sunxi/sunxi-tools, any local clone of it, its
  man page, and its tests. The linux-sunxi **wiki** is fine.
- **Magisk source**: any file of https://github.com/topjohnwu/Magisk or a clone, and the scripts inside
  the APK (`assets/boot_patch.sh`, `assets/util_functions.sh`). The published docs at
  https://topjohnwu.github.io/Magisk/ are fine, and so is running the CLI as a black box.
- **In this repo**, these files and their history (`git log -p`, `git blame`, `git show` of older
  versions, the old `web/src/fel` and `web/src/bootpatch` paths):
  - `kiln/packages/fel/src/protocol.ts`, `kiln/packages/fel/src/felDevice.ts`,
    `kiln/packages/fel/test/legacy-internals.test.ts`;
  - `kiln/packages/bootpatch/src/cpio.ts`, `src/magisk.ts`, `src/bootimg.ts`, `src/index.ts`,
    `src/wasm/src/lib.rs`, `test/legacy-internals.test.ts`, and the built `src/wasm/bootpatch.wasm`
    (don't disassemble it; you rebuild it);
  - `frames/aqara-s1-plus/research/web-installer.md`: the old design spec, which walks through
    the upstream sources line by line;
  - `docs/research/kiln-gpl-code.md` (#348): the audit, which names upstream functions and quotes
    their structure;
  - `docs/research/kiln-cleanroom/spec-owner-notes.md`: the spec owner's mutation commands, which
    quote the old code;
  - #348 and #359 on GitHub, their bodies and diffs (read the spec files in the repo instead), and the
    diffs of the PRs that first added these packages.

### Fine to open

Everything else in the repo, at the commit that adds these specs. Read `test/fakes.ts`, `src/apk.ts`
and `src/thunk.ts` as they are at that commit, not their git history, because their older versions
held or sat next to the replaced code. Fine to open, for example: these specs; both packages' tests, fakes and vectors
(`test/fakes.ts`, `test/arm.ts`, `test/vectors*`, `test/fel.test.ts`, `test/bootpatch.test.ts`); the
files the specs say to keep (`a133.ts`, `thunk.ts`, `transport.ts`, `uboot.ts`, `apk.ts`,
`compress.ts`, `overlay.ts`, `vbmeta.ts`, `bootlogo.ts`, `build-wasm.sh`, `Cargo.*`); the CLI scripts
`frames/aqara-s1-plus/tools/fel/fel-boot.sh`, `frames/aqara-s1-plus/tools/install/fel-install.sh` and
`frames/aqara-s1-plus/tools/root/patch-boot-magisk.sh` (ours, and they call sunxi-fel and magiskboot as
programs); and `frames/aqara-s1-plus/research/fel.md` and `root.md`.

The thunk in `kiln/packages/fel/src/thunk.ts` is MIT (Siarhei Siamashka, 2015) and stays as it is. It
was always under that licence, so it's no part of the rewrite.

## What the spec owner did (2026-11-15)

- Wrote the two specs from the behaviour of the old code and the public formats. They contain no code
  and no upstream identifiers or comments.
- Made the fake boot ROM independent of the host's code. It used to recognise each routine by its
  exact bytes, imported from the code being replaced. Now it runs whatever routine is uploaded in an
  A32 interpreter written from the ARM ARM (`kiln/packages/fel/test/arm.ts`), and checks what the
  routine did. The only code it still recognises by its bytes is the MIT thunk.
- Moved the MIT thunk into `src/thunk.ts`, with its licence. Moved our own APK reader out of
  `magisk.ts` into `src/apk.ts`. Added `patchBootImage`, so the vectors can use synthetic Magisk
  binaries.
- Moved the tests of the internals into `test/legacy-internals.test.ts` in each package, so the
  implementer can delete them whole.
- Recorded the vectors from the old code: `kiln/packages/fel/test/vectors/fel.json` and
  `kiln/packages/bootpatch/test/vectors/bootpatch.json`.
- Checked that they catch real changes, by editing the old code and running the vectors (2026-11-15,
  verified). A skipped secure-boot refusal, a reset into AArch32, and cpio inodes off by one each make
  them fail. A routine rewritten with different but equivalent instructions still passes. The exact
  edits quote the old code, so they are in [spec-owner-notes.md](spec-owner-notes.md), which the
  implementer must not open.
