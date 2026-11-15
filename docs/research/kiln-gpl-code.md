# Kiln licence audit: which code in the MIT installer comes from GPL projects

**Status (2026-11-15): open, a maintainer decision.** Kiln's page holds code taken from two GPL
projects. In `kiln/packages/fel`, four small ARM routines are copied from sunxi-tools
(GPL-2.0-or-later) and the SPL loader is translated from it. In `kiln/packages/bootpatch`, parts are
translated or copied from Magisk v31.1 (GPL-3.0-only). Both packages say `"license": "MIT"`, and
both run on kiln.glassworks.dev and s1.edm.sh today. Nothing has been relicensed or rewritten yet.

Compared against: sunxi-tools `34dfa20f8284273085666436e4cac09af19646c8` (2026-11-05,
https://github.com/linux-sunxi/sunxi-tools) and Magisk tag `v31.1` =
`0972f67d97f9b6302b67c5171283330c74114b4a` (https://github.com/topjohnwu/Magisk). Repo at `73fe7d1a`.

This note records facts and options. It is not legal advice.

## Question

Another owner found on 2026-11-15 that `frames/aqara-s1-plus/research/web-installer.md` calls the FEL
package a "TS WebUSB port of the sunxi-fel subset" with thunk words "from sunxi-tools", and that
`THIRD-PARTY-NOTICES.md` doesn't list sunxi-tools. The repo and Kiln are MIT. Hard rule 4 in
`AGENTS.md` says we ship no GPL payloads. We wanted to know exactly what was taken, how it is
licensed upstream, and whether it reaches browsers.

## What we tried

1. **Upstream licences** (2026-11-15, read)
   - sunxi-tools `LICENSE.md` is the GPLv2 text. `fel.c`, `fel_lib.c` and `soc_info.c` say "version 2
     of the License, or (at your option) any later version", so GPL-2.0-or-later. Copyright lines:
     Henrik Nordstrom (2012), Siarhei Siamashka (2015), Bernhard Nortmann (2016).
   - `thunks/fel-to-spl-thunk.S` is **MIT**: "Copyright © 2015 Siarhei Siamashka", with the MIT
     permission text. `thunks/fel-to-spl-thunk.h` is the word list generated from it
     (`objdump_to_h.awk`). `thunks/README.md` says files with no licence header fall under GPLv2;
     `rmr-thunk.S` and `readl_writel.S` have no header.
   - Magisk is GPL-3.0-only (our notices already say so for the APK we download).

2. **Word-for-word comparison of the FEL package** (2026-11-15, verified). A Python script
   compares each ARM word array in `kiln/packages/fel/src/protocol.ts` with the matching upstream
   array (script under [How to reproduce](#how-to-reproduce)):
   ```
   FEL_TO_SPL_THUNK: 69 words, identical to thunks/fel-to-spl-thunk.h
   readlCode:        10 words, identical to fel_lib.c aw_fel_readl_n
   rmrCode:          12 words, identical to fel.c aw_rmr_request
   STACKINFO_CODE:    9 words, identical to fel.c aw_get_stackinfo (the "works everywhere" branch)
   SCTLR_CODE:        3 words, = fel.c aw_read_arm_cp_reg with (15, 0, 1, 0, 0) -> 0xee110f10
   protocol.ts: 91 of 91 32-bit hex constants occur in sunxi-tools
   ```

3. **Line and string overlap, all of `kiln/packages/fel` and `bootpatch`** (2026-11-15, verified).
   Same script: code lines (whitespace-normalised, 24 chars or more) identical to upstream, and
   string literals of 12 chars or more found upstream:
   ```
   fel/src/protocol.ts     lines 0/104   hex 91/91  strings 2/4  'SPL: bad length in the eGON header', 'SPL: checksum check failed'
   fel/src/felDevice.ts    lines 0/86    hex 1/1    strings 1/5  'SPL: bad thunk size'
   fel/src/a133.ts         lines 0/4     hex 2/4    strings 0/1
   fel/src/transport.ts    lines 0/49    hex 0/0    strings 0/5
   fel/src/uboot.ts        lines 0/16    hex 0/0    strings 0/2
   fel/test/fakes.ts       lines 0/190   hex 2/7    strings 0/20
   bootpatch/src/wasm/src/lib.rs  lines 26/72  (Rust; is_raw_arm32 and detect_bcj_filter)
   bootpatch/src/cpio.ts   lines 0/197   strings 16/26  'Record new entry', '.backup/.rmlist', 'verifyatboot', ...
   bootpatch/src/magisk.ts lines 0/125   strings 18/32  '- Stock boot image detected', 'extract .backup/.magisk config.orig', ...
   bootpatch/src/bootimg.ts lines 0/145  strings 2/15   'Corrupted boot image!', 'SEANDROIDENFORCE'
   bootpatch/src/index.ts  lines 0/50    strings 3/9    '- Unpacking boot image', '- Repacking boot image', ...
   bootpatch/src/{compress,overlay,vbmeta}.ts  no overlap
   ```
   Zero identical lines in the TypeScript is expected for a translation from C or Rust. The strings and
   the side-by-side reading below show what was translated.

4. **Side-by-side reading** (2026-11-15, read)
   - `splWritePlan` (`protocol.ts`) follows `aw_fel_write_and_execute_spl` (`fel.c:885-918`) branch for
     branch: the same `spl_len_limit` update, the two `if (len > 0 && cur_addr …)` cases and the thunk
     limit. `checkEgon` is `fel.c:839-853`. `runSpl` runs the steps of `fel.c:824-962` in the same
     order: stack info, the SCTLR mask check copied from `fel.c:726`, the thunk, the 250 ms sleep, and
     the `eGON.FEL` read-back. It uses the same error texts.
   - `cpio.ts` `Cpio.patch`, `backup` and `restore` are line-by-line translations of `cpio.rs:505-536`
     and `cpio.rs:562-700`, log lines included ("Patch with flag KEEPVERITY=[…]", "Backup […] -> […]").
     `removePattern` and `matchPatterns` translate `patch.rs:37-88`, with the same pattern lists.
   - `magisk.ts` `magiskPatchRamdisk` translates `scripts/boot_patch.sh` from "Ramdisk Checks" to the
     cpio edit, with the same `ui_print` texts. Its header says so, and it checks the SHA-256 of the
     two scripts it ports.
   - `bootimg.ts` parses the AOSP v2 boot image header, which is an AOSP format. It follows
     magiskboot's choices (what to refuse, the debug print layout, one error text), so it is more
     like a re-implementation than a translation.
   - `wasm/src/lib.rs`: `is_raw_arm32` and `detect_bcj_filter` are copied from `format.rs:223-268`. Only
     Magisk's comments were removed. `lib.rs` is compiled into `bootpatch.wasm`.
     `bootpatch/src/wasm/THIRD_PARTY.txt` and `THIRD-PARTY-NOTICES.md` §4 both say "No Magisk code
     is included". That is wrong.

5. **History and headers** (2026-11-15, read). `git log --follow` shows both packages came from
   `d4f034e7` (2026-11-09, "web: browser (WebUSB) installer", as `web/src/fel`, `web/src/bootpatch`).
   Later commits only moved files: `51dc4ad4` (#92), `99a130e8` (#130), `77db99bb` (#203), `6fd3b321` (#310). The
   headers name their sources openly ("Each is a port of sunxi-tools (fel_lib.c, fel.c,
   thunks/fel-to-spl-thunk.h)"; "exactly as magiskboot v31.1 handles it (native/src/boot/cpio.rs)").
   None carries an upstream copyright line or licence. Both `package.json` files say MIT.

6. **What the browsers get** (2026-11-15, verified against the live sites)
   ```
   $ curl -sL https://kiln.glassworks.dev/aqara-s1-plus/set-up    # then its chunks
   chunks/10aa-hfyrwjo2.js: 'eGON.FEL', '0xe28f40e8' (thunk word), 'Record new entry: [${e}] -> [.backup/.rmlist]', 'Patch with flag'
   media/bootpatch.09teyhlyf1tf7.wasm: sha256 d6fa9e4892b3a44e… = kiln/packages/bootpatch/src/wasm/bootpatch.wasm
   $ curl -sL https://s1.edm.sh/    # the older Vite page
   index-CUoj7J41.js: 'eGON.FEL', 'Record new entry'
   ```
   The FEL code, the bootpatch code and the wizard share one JavaScript chunk. Both packages are in
   the page people load.

## Result

### `kiln/packages/fel`

| Piece | Where from | Kind | Upstream licence |
|---|---|---|---|
| `FEL_TO_SPL_THUNK` (69 words) | `thunks/fel-to-spl-thunk.S` → `.h` | Machine code compiled from upstream source, copied verbatim | **MIT**, © 2015 Siarhei Siamashka |
| `readlCode`, `rmrCode`, `STACKINFO_CODE`, `SCTLR_CODE` (34 words) | `fel_lib.c`, `fel.c` (also `readl_writel.S`, `rmr-thunk.S`) | Machine code copied verbatim, with upstream's disassembly comments | GPL-2.0-or-later |
| `splWritePlan`, `checkEgon`, the `runSpl` steps, the SCTLR mask, error texts | `fel.c` `aw_fel_write_and_execute_spl`, `aw_backup_and_disable_mmu` | C translated to TypeScript (copyright-relevant, though small and mostly set by the hardware) | GPL-2.0-or-later |
| `usbRequest`, `felRequest`, `parseVersion`, the request codes, AWUC/AWUS, `1f3a:efe8` | `fel_lib.c`, `soc_info.h` | Wire format and protocol constants (facts, needed to work with the chip) | n/a |
| `A133` table: SPL, scratch and thunk addresses, swap buffer, SRAM size, RVBAR, SID | `soc_info.c` A133 entry | Facts about the chip | n/a |
| `transport.ts`, `uboot.ts`, the chunking, secure-boot check, `reset64` error handling | ours | Our own code | MIT |
| `test/fakes.ts` | ours | Tests only, not in the page | MIT |

About 40 lines of `protocol.ts` (the 34 GPL words and the SPL loader) are GPL-derived. The rest of the
package is facts or our own code.

### `kiln/packages/bootpatch` (Magisk v31.1, GPL-3.0-only)

| File | What | Kind |
|---|---|---|
| `src/cpio.ts` | `patch`, `backup`, `restore`, `removePattern`, the patterns, `test`'s file lists | Translated from `cpio.rs`, `patch.rs` |
| `src/magisk.ts` | `magiskPatchRamdisk`, the config text | Translated from `scripts/boot_patch.sh` |
| `src/wasm/src/lib.rs` → `bootpatch.wasm` | `is_raw_arm32`, `detect_bcj_filter` | Copied verbatim from `format.rs` |
| `src/bootimg.ts` | AOSP v2 parse and repack, magiskboot's refusals | Mostly an AOSP format; it mirrors magiskboot's behaviour |
| `src/index.ts`, `compress.ts`, `overlay.ts`, `vbmeta.ts`, `bootlogo.ts` | glue, wasm calls, our overlay, AVB flags, BMP | Ours (a few log texts match magiskboot's) |

### Elsewhere (brief check)

| Part | Licence | In the page? | Status |
|---|---|---|---|
| `kiln/packages/fastboot` | MIT fork of fastboot.js, with notice and LICENSE | yes | fine |
| `kiln/packages/adb` | ours, on `@yume-chan/adb` (MIT, in the notices) | yes | fine |
| `kiln-core`, `improv-serial`, `setup-channel`, `brand` | ours, MIT | yes | no upstream code seen |
| `frames/aqara-s1-plus/tools/fel/uboot/` (patches, defconfig, `.dts` = GPL-2.0+ OR MIT) | GPL-2.0 changes to U-Boot | no, built into the U-Boot downloads | covered by notices §3 and §6 |
| `tools/fel/*.sh`, `tools/install/*.sh` | ours | no | they run `sunxi-fel` as a separate program, nothing copied |
| `tools/root/patch-boot-magisk.sh` | ours | no | runs Magisk's own scripts from the downloaded APK |

## Options

### FEL package

**(a) Relicense `@glassworks/fel` as GPL-2.0-or-later, with notices.** The package itself is easy to
relabel. But it isn't a separate work in the page: it shares one chunk with the wizard (step 6), so
the page as served becomes one combined program. That program would have to be offered under the
GPL, which is fine here because all the source is public. MIT code can sit inside it. Because of
bootpatch's GPL-3.0-only code, the page would in practice already be GPL-3.0. The cost is that
`@glassworks/fel` stops being an MIT library other frames and projects can reuse, and hard rule 4
needs an exception.

**(b) Rewrite the copied parts from the protocol docs.** The scope is small. It covers four ARM
routines of 3 to 12 words, which should be written fresh from the ARM Architecture Reference Manual
(a `.S` source in the repo, assembled by us). It also covers the SPL write plan and eGON check, to
be re-derived from the linux-sunxi wiki pages "FEL/Protocol", "FEL/USBBoot" and "EGON". Keep
`FEL_TO_SPL_THUNK`: it is MIT, so it needs a notice, not a rewrite. It isn't a strict clean room,
since everyone working here has read sunxi-tools. The safest form is to give a fresh writer only
the wiki, the ARM manual and the fact tables above. A `readl` loop will look like any other
`readl` loop, and that's expected. FEL runs only in RAM, but the new code still needs one real
panel run (`bootUboot` end to end) before merge.

**(c) Facts only, add a notice.** This isn't true for this package. The 34 GPL words are copied
verbatim and the SPL loader is a translation. A notice alone would still ship GPL-2.0-or-later
code inside an MIT package.

**(d) Ask upstream.** Siarhei Siamashka already put the SPL thunk under MIT. Asking the authors of
`aw_fel_readl_n`, `aw_rmr_request` and `aw_get_stackinfo` for MIT use is cheap but slow, and
`git blame` needs the full history (our clone is shallow).

### bootpatch (bigger, and its own decision)

1. **Mark `bootpatch` GPL-3.0-only and serve the page under the GPL-3.0.** It is honest and costs
   no code: bootpatch only exists to reproduce Magisk byte for byte. Kiln's other packages stay MIT
   on their own. The notices say what the page is, and the source is this repo.
2. **Write a behaviour spec, then re-implement from it.** One writer describes the `.backup/` and
   `.rmlist` format and the patch steps without code, and another implements it. It is costly, and
   byte-identical output is hard to reach that way.
3. **Build magiskboot itself to wasm as a separate GPL-3.0 download**, like U-Boot, and call it with
   bytes in and bytes out. That is a clean split, but a new build and a larger asset.

## Recommendation

- **FEL: (b).** The GPL part is about 40 lines. The package is the FEL library every future Allwinner
  frame will use, so it should stay MIT. Until the rewrite, the notices state the facts (this PR).
- **bootpatch: option 1 now.** Fix the wrong "No Magisk code" lines, mark the package GPL-3.0-only,
  and treat the served page as GPL-3.0. Option 2 or 3 can come later if Kiln has to be MIT as a whole.

## How to reproduce

All read-only. Clone the two upstreams at the commits above. For Magisk, a sparse clone of
`native/src/boot` and `scripts` is enough. Then run:

```python
# overlap.py <our file> <upstream dir or file>... : identical code lines, 32-bit hex words, string literals
import re, sys, pathlib
ours = pathlib.Path(sys.argv[1]).read_text()
up = ''.join(p.read_text(errors='ignore') for a in sys.argv[2:] for p in
             ([pathlib.Path(a)] if pathlib.Path(a).is_file() else pathlib.Path(a).rglob('*'))
             if p.is_file() and p.suffix in {'.c', '.h', '.S', '.rs', '.cpp', '.hpp', '.sh'})
norm = lambda s: re.sub(r'\s+', ' ', s).strip()
up_lines = {norm(l) for l in up.splitlines()}
lines = [norm(l) for l in ours.splitlines() if len(norm(l)) >= 24 and not norm(l).startswith(('//', '*', '/*'))]
hexw = set(re.findall(r'0x[0-9a-f]{8}', ours.lower())); uphex = set(re.findall(r'0x[0-9a-f]{8}', up.lower()))
strs = set(re.findall(r"""['"`]([^'"`\n${}]{12,})['"`]""", ours))
print(sys.argv[1], 'lines', sum(l in up_lines for l in lines), '/', len(lines),
      'hex', len(hexw & uphex), '/', len(hexw), 'strings', [s for s in strs if s in up])
```

```sh
python3 -I overlap.py kiln/packages/fel/src/protocol.ts ~/src/sunxi-tools
python3 -I overlap.py kiln/packages/bootpatch/src/wasm/src/lib.rs magisk/native/src/boot
python3 -I overlap.py kiln/packages/bootpatch/src/cpio.ts magisk/native/src/boot magisk/scripts
```

For the word arrays, compare `FEL_TO_SPL_THUNK` with the 69 words at the start of each line of
`thunks/fel-to-spl-thunk.h`, and `readlCode`/`rmrCode`/`STACKINFO_CODE` with the `arm_code[]` arrays in
`aw_fel_readl_n`, `aw_rmr_request` and `aw_get_stackinfo`.

## Open questions

- Which sunxi-tools contributors wrote the three GPL routines (needs a full clone for `git blame`).
- Whether the maintainer wants Kiln's page to stay MIT as a whole. That decides bootpatch's option.
- `kiln/packages/bootpatch/src/wasm/THIRD_PARTY.txt` still says "No Magisk (GPLv3) code is
  included". This PR leaves it alone (scope). It should change together with the bootpatch decision.
