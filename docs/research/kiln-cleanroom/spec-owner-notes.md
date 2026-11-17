# Spec owner's notes (implementer: don't open)

These notes quote the code being replaced, so the clean-room implementer must not read them (see
[README.md](README.md)). They record how the spec owner checked that the vectors catch real changes.

**Mutation checks** (2026-11-15, verified). Edit the old code in place, run the vectors, and restore the file each time.
The edits target the code before the rewrite (`aeacaca0`). `src/protocol.ts` was removed in #372: the
reset routine is now `warmReset64` in `src/routines.ts`, assembled from `src/routines.S`.

| Edit (in the package directory) | `npx vitest run test/vectors.test.ts` |
|---|---|
| `sed -i '' 's/if (secureBoot !== 0) {/if (secureBoot === 12345) {/' src/uboot.ts` (skip the secure-boot refusal) | 1 failed: `refuses: secure boot on` |
| `sed -i '' 's/(1 << 1) \| (aarch64 ? 1 : 0),/(1 << 1),/' src/protocol.ts` (reset into AArch32) | 1 failed: `bootUboot: what the routines did on the chip` |
| `sed -i '' 's/let ino = 300000;/let ino = 300001;/' src/cpio.ts` (bootpatch) | 6 failed: every `patches:` case |
| In `src/protocol.ts`, the reset routine's `0xe59f101c` (`ldr r1, [pc, #28]`, which loads 3) replaced by `0xe3a01003` (`mov r1, #3`) | all pass: different but equivalent code is accepted |

Restore with `git checkout -- <file>` after each.
