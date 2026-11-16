# Clean-room spec: `kiln/packages/fel`

**Status: panel-verified 2026-11-16: the rewrite FEL-booted U-Boot on a real S1 (fastboot answered `sunxi`).** This page says what Kiln's FEL client
must do, in behaviour and facts. It is the input for an implementer who has not read sunxi-tools.
Read [README.md](README.md) first: it says what you may open and what you must not.

**The panel run** (2026-11-16, verified, RAM only). The maintainer put the S1 in FEL. The root ran
#372's `kiln/packages/fel` from Node over WebUSB. It used the same transfer-timeout wrapper as
`tools/mirror`. What came back:

1. `checkChip`: SoC id `0x1855`, secure boot `0`.
2. `bootUboot`: the SPL ran, U-Boot uploaded in 64 KiB writes, and `reset64` jumped to `0x4a000000`.
3. U-Boot came up as USB fastboot. `fastboot getvar product` answered `sunxi`.
4. Nothing was written to eMMC. After a replug, the panel booted its flashed image as before.

The full report is in [#372's comment](https://github.com/GlassworksHQ/glassworks/pull/372#issuecomment-6906668434).
To repeat it in a browser, follow the panel plan in #372's description: Kiln's dev page, a fresh Chrome
profile, **Install** then **Connect**. It stops at the fastboot prompt.

## What the package does

Kiln boots our U-Boot on an Allwinner A133 (the Aqara S1 Plus) from the chip's USB recovery mode,
FEL, in the browser over WebUSB. Nothing is written to storage. The steps:

1. Ask the boot ROM for its version, and check that the chip is an A133.
2. Read the chip's secure-boot word. Refuse if secure boot is on.
3. Load the SPL (U-Boot's first stage) into SRAM and run it. It sets up DRAM and returns to FEL.
4. Upload `u-boot.bin` to DRAM.
5. Make the CPU warm-reset into AArch64 at U-Boot's address. The chip leaves FEL.

Steps 1, 2, 4 and the order are already in `src/uboot.ts`, which stays. You write the FEL client
underneath it: the USB framing, the four FEL requests, the small ARM routines run on the chip, the
SPL handoff and the reset.

## Public API to keep

Other code imports these by name, so keep them as they are (`src/index.ts` exports them):

| Export | From | Behaviour |
|---|---|---|
| `FEL_FILTER` | `felDevice.ts` | `{ vendorId: 0x1f3a, productId: 0xefe8 }`, the boot ROM's USB id |
| `class FelDevice` | `felDevice.ts` | One FEL session on a `BulkTransport` |
| `new FelDevice(t: BulkTransport)` | | Wraps a transport (the tests pass the fake boot ROM) |
| `FelDevice.request()`, `.fromDevice(d)`, `.known()` | | WebUSB: prompt for the device, open one, list already-permitted ones. Open with `WebUsbBulk.open(d)` from `transport.ts` |
| `fel.log: (line: string) => void` | | One line per step, for the installer's log. Wording is yours |
| `fel.version(): Promise<FelVersion>` | | The version request, parsed (below) |
| `fel.read(addr, len): Promise<Uint8Array>` | | A FEL read |
| `fel.write(addr, data, onProgress?)` | | FEL writes, at most 64 KiB each, `onProgress(done, total)` after each |
| `fel.exec(addr)` | | A FEL execute |
| `fel.readl(addr): Promise<number>` | | Read a 32-bit register by running a routine on the chip |
| `fel.runSpl(splWithUboot)` | | The SPL handoff (below). DRAM is up when it resolves |
| `fel.reset64(entry)` | | Warm reset into AArch64 at `entry` (below) |
| `fel.close()` | | Close the transport |
| `hex(n)` | `felDevice.ts` | `n` as 8 upper-case hex digits, no prefix |
| `type FelVersion` | | `{ signature: string; socId: number; protocol: number; scratchpad: number; raw: Uint8Array }` |

`A133`, `FEL_CHUNK` (`a133.ts`), `bootUboot`, `checkChip` (`uboot.ts`), `BulkTransport` and
`WebUsbBulk` (`transport.ts`) stay as they are.

Today's `FelDevice` also has public `sctlr()` and `stackInfo()` methods. Nothing in the repo calls
them, so the rewrite drops them. Read SCTLR inside `runSpl`, and the stack pointers aren't needed.

**Transport rule.** Send each USB transfer below with one `BulkTransport.out(bytes)` call, and read
each with one `BulkTransport.in(length)` call. The vectors compare transfers one by one.

## Facts

### USB

The boot ROM shows up as `1f3a:efe8`, with one interface (number 0) that has one bulk IN and one bulk
OUT endpoint. All integers below are little-endian.

### Framing (every exchange)

Each exchange is a request, an optional data phase, and a status (linux-sunxi wiki,
[FEL/Protocol](https://linux-sunxi.org/FEL/Protocol)):

| Transfer | Size | Content |
|---|---|---|
| Request, OUT | 32 bytes | `"AWUC"` and 4 zero bytes (8 bytes in all), u32 length at 8, u32 `0x0c000000` at 12, u16 direction at 16 (`0x12` the host sends, `0x11` the host receives), u32 length again at 18 (unaligned), zeros to 32 |
| Data, OUT or IN | `length` bytes | The payload |
| Status, IN | 13 bytes | Starts with `"AWUS"`. Anything else is an error |

### FEL requests

A FEL request is a 16-byte message, sent as the data of a host-to-device exchange: u32 request
code, u32 address, u32 length, u32 zero. Then:

| Request | Code | After the request message | Then |
|---|---|---|---|
| Version | `0x001` | Device-to-host exchange of 32 bytes: the version reply | FEL status |
| Write | `0x101` | Host-to-device exchange of `length` bytes: the data for `address` | FEL status |
| Execute | `0x102` | Nothing. The chip calls `address` in ARM state | FEL status |
| Read | `0x103` | Device-to-host exchange of `length` bytes from `address` | FEL status |

The **FEL status** is a device-to-host exchange of 8 bytes, whose content you ignore.

So one FEL write is nine transfers: request, message, status; request, data, status; request,
8-byte status, status. The vector file lists them all.

**Version reply** (32 bytes): signature, 8 ASCII bytes with trailing NULs dropped (`"AWUSBFEX"`). Then
a u32 at 8, of which bits 8..23 are the SoC id (`0x1855` for the A133), a u16 protocol at 16, and a
u32 scratchpad address at 20. Keep the 32 bytes as `raw`.

### The A133

From `src/a133.ts`, which stays. The values are chip facts: the
[A133 user manual](https://linux-sunxi.org/A133) and the linux-sunxi wiki's FEL pages.

| What | Value |
|---|---|
| SoC id | `0x1855` |
| SRAM A1, where the SPL runs | from `0x20000`, 148 KiB (`sramSize`) |
| Scratch area for host routines | `0x21000`. Routines, their data and their results stay inside `0x21000`..`0x213ff`. Small routines there work: the current installer runs them on real panels |
| Parked buffer | The SPL's bytes for `0x21000`..`0x213ff` must not be written there while the chip is in FEL, because the ROM needs that range until the SPL runs. They are parked at `0x40000` and swapped in by the thunk (`swapBuffers`, one entry: buf1 `0x21000`, buf2 `0x40000`, size `0x400`) |
| SPL thunk address | `0x40400`, at most `0x200` bytes |
| RVBAR (64-bit reset vector, CPU0) | `0x08100040` |
| SID (eFuse) base | `0x03006000` |
| Secure-boot word | `0x030060a0` (SID + `0xa0`). Non-zero: the ROM runs only signed code |
| U-Boot load and entry address | `0x4a000000` |

### Routines run on the chip

Some steps need code on the chip: reading a register, reading a CP15 register, and the reset. You
write these routines yourself as A32 (ARM state) machine code. Keep their assembly source in the repo
next to the words, e.g. `src/routines.S` with the assembled words in `src/routines.ts`. Each word
needs a comment with its instruction. Encodings are in the ARM Architecture Reference Manual,
ARMv7-A/R (DDI 0406C), sections A5 and A8.

Rules for a routine:
- It is written to the scratch area with FEL writes and started with an execute at `0x21000`.
- The boot ROM calls it in ARM state, Supervisor mode, with `lr` set to return into the ROM. A
  routine that completes must return with `bx lr`, keeping `sp` and `r4`..`r11` intact (the AAPCS).
  The warm-reset routine is the exception: it never returns, it waits in its `WFI` loop until the
  chip resets.
- Parameters go in the uploaded bytes (e.g. literal words after the code). The host reads results
  back with a FEL read. Everything stays inside `0x21000`..`0x213ff`.
- The fake boot ROM in the tests runs routines in a small interpreter,
  [`test/arm.ts`](../../../kiln/packages/fel/test/arm.ts). It covers data processing, MOVW/MOVT,
  LDR/STR (word, byte, halfword), LDM/STM, branches, MRS/MSR, MRC/MCR, barriers and WFI. If your
  routine needs more, add it to `arm.ts` from the ARM ARM. It is test code you may change.

The routines you need, by behaviour:

| Routine | Does | Used by |
|---|---|---|
| Read a word | Loads the 32-bit word at a given address and stores it where the host reads it back | `readl` (the secure-boot word) |
| Read SCTLR | Reads the System Control Register (CP15: `opc1 0, CRn c1, CRm c0, opc2 0`) and stores it for the host | `runSpl` |
| Warm reset to AArch64 | Writes the entry address to RVBAR (`0x08100040`); makes it visible (`DSB`, `ISB`); reads the AArch32 Reset Management Register (CP15: `opc1 0, CRn c12, CRm c0, opc2 2`), sets bit 1 (RR, request a warm reset) and bit 0 (AA64, reset into AArch64), writes it back; `ISB`; then waits in a `WFI` loop. Why this order: RVBAR holds the address the core starts at after a warm reset, RMR.RR requests the warm reset and RMR.AA64 picks AArch64 (the RMR and RVBAR register descriptions: [Arm A-profile registers, DDI 0601, RMR_EL3](https://developer.arm.com/documentation/ddi0601/2024-03/AArch64-Registers/RMR-EL3--Reset-Management-Register--EL3-) and its AArch32 RMR, and the [Cortex-A53 TRM, DDI 0500](https://developer.arm.com/documentation/ddi0500/j/), "Reset Management Register" and "Reset Vector Base Address Register"). The `DSB`/`ISB` make the RVBAR write and the RMR write complete before what depends on them, per the Arm ARM's barrier rules (DDI 0487), and the core takes the reset from `WFI`. I found no single code listing of this sequence in Arm's documents (searched 2026-11-15). | `reset64` |

Reading the stack pointers for the log is optional. Kiln doesn't need it.

### The SPL and its eGON header

The input to `runSpl` is `u-boot-sunxi-with-spl.bin`: an SPL with an eGON header, padding, then
U-Boot. Only the SPL part is used (linux-sunxi wiki, [EGON](https://linux-sunxi.org/EGON)):

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | A branch instruction to the code |
| 4 | 8 | Magic `"eGON.BT0"` |
| 12 | 4 | Checksum |
| 16 | 4 | SPL length in bytes: a multiple of 4, at least 32 (the header), at most the file's length |

**Checksum rule.** Take the first `length` bytes as little-endian 32-bit words, with the checksum
field replaced by the stamp `0x5F0A6C39`. Their sum, mod 2^32, must equal the checksum field.

**Size limit.** The SPL must fit in SRAM below both the parked-buffer address and the thunk:
`limit = min(sramSize, buf2 - splAddr, thunkAddr - splAddr)`, which is `0x20000` (128 KiB) on the A133.

### The SPL thunk

The thunk is the code that swaps the parked bytes into place, runs the SPL, and swaps them back.
It stays as it is: it is MIT licensed (Siarhei Siamashka, 2015) and lives in
[`src/thunk.ts`](../../../kiln/packages/fel/src/thunk.ts) as `FEL_TO_SPL_THUNK`, 69 words, with its
licence. You call it. You don't change it.

**Its interface.** In memory at the thunk address, write the 69 thunk words, then the SPL address
(one word), then one triple of words (buf1, buf2, size) per parked buffer, then a triple of zeros.
On the A133 that is 69 + 1 + 3 + 3 = 76 words, 304 bytes. Then execute the thunk address. The thunk
checks the eGON checksum in SRAM itself, and it refuses to run the SPL when the data or instruction
cache is on (SCTLR bit 2 or bit 12), leaving `"eGON.???"`. When the SPL returns, the thunk overwrites
the 8 bytes at SPL address + 4: `"eGON.FEL"` means success. Any other value is a failure code from the
thunk or the SPL. The A133's ROM leaves the caches off, so Kiln doesn't need to check them itself:
the thunk's code reaches the user through the error in step 6.

## Behaviour

### `version()`, `read()`, `write()`, `exec()`

- `version()`: the Version request, then the parsed reply.
- `read(addr, len)`: one Read request.
- `write(addr, data)`: split `data` into pieces of `FEL_CHUNK` (64 KiB). Each piece is its own Write
  request for `addr + offset` and that piece's length. Call `onProgress(done, total)` after each
  piece. Why 64 KiB: with the SPL's clock at 408 MHz, the ROM's USB times out on larger transfers
  (`frames/aqara-s1-plus/research/fel.md`, "USB uploads").
- `exec(addr)`: one Execute request.

### `readl(addr)`

Run the "read a word" routine for `addr` and return the value. The fake answers any address in the
peripheral range (`0x01000000`..`0x3fffffff`) from its register table.

### `runSpl(splWithUboot)`

In this order:

1. Check the eGON header. Refuse a missing magic, a length that is not a multiple of 4, is shorter
   than the 32-byte header or is longer than the input, and a bad checksum. Refuse an SPL longer than the size limit. No SPL byte may be
   written before these checks pass (routines may run before them).
2. Read SCTLR. Refuse if bit 0 (M, MMU on) is set. The A133's ROM leaves the MMU off, and Kiln has no
   path for an MMU-on ROM. Checking other bits is optional.
3. Write the SPL's first `length` bytes so that, once the thunk swaps buffers, they sit at
   `splAddr`. For the A133 that is three writes, in this order:
   - bytes `0` .. `0x1000` (the part before buf1) to `0x20000`;
   - bytes `0x1000` .. `0x1400` (the part that belongs at buf1) to buf2, `0x40000`;
   - bytes `0x1400` .. `length` to `0x21400`.

   A shorter SPL leaves out the later writes. Each write goes through `write()`, so it is split at
   64 KiB too (an SPL is at most 128 KiB).
4. Write the thunk image (above) to `0x40400` and execute `0x40400`.
5. Wait 250 ms. The ROM needs a pause after the SPL returns, before the next request. This is
   untested on our side; keep it.
6. Read 8 bytes at `splAddr + 4`. Anything but `"eGON.FEL"` is an error that names the 8 bytes.

### `reset64(entry)`

Upload the warm-reset routine with `entry` and send its Execute request. The chip resets at once,
so the FEL status may never come (or USB drops first). After the Execute request is sent, a missing
or failed status is not an error: log it and resolve.

### Errors Kiln shows

Kiln shows the error text to the user. The vectors check for a required substring, and the wording
around it is yours. Keep it short and plain.

| Condition | Must contain | Suggested wording |
|---|---|---|
| Not an A133 (already in `uboot.ts`) | `not an Aqara S1 Plus` | (kept) |
| Secure boot on (already in `uboot.ts`) | `secure boot is enabled` | (kept) |
| SCTLR has the MMU on | `MMU` | "The boot ROM left the MMU on, which Kiln does not expect on an A133." |
| No eGON magic | `eGON` | "This file has no eGON header, so it is not an Allwinner SPL." |
| Length not a multiple of 4, or longer than the file | `length` | "The SPL's length field is wrong." |
| Length shorter than the 32-byte header | `length` or `checksum` | "The SPL's length field is wrong." |
| Bad checksum | `checksum` | "The SPL's checksum does not match: the file is damaged." |
| Too long | `too large` | "The SPL is too large for SRAM (need N bytes, have M)." |
| Not `"eGON.FEL"` after the run | the 8 bytes it read, e.g. `eGON.BAD`, or `eGON.???` when the thunk found a cache on | "The SPL did not finish (it left 'eGON.BAD')." |
| Bad USB status | `AWUS` | "FEL: unexpected USB status (no AWUS)." |

## Test vectors

[`kiln/packages/fel/test/vectors.test.ts`](../../../kiln/packages/fel/test/vectors.test.ts) and
[`test/vectors/fel.json`](../../../kiln/packages/fel/test/vectors/fel.json), recorded on
2026-11-15 from the implementation being replaced. Run `npx vitest run` in `kiln/packages/fel`.

- **bootUboot, on the wire.** SPL = `makeSpl(0x9000, 0x40000)` from `test/fakes.ts`, U-Boot = 300000
  bytes with byte `i` = `(i * 13) & 0xff`. Every FEL request that is **not** a routine is compared
  exactly: request, address, length, and each USB transfer (direction, length, and the bytes in hex,
  or their SHA-256 when longer than 64 bytes). In order these are: the version, the three SPL
  writes, the thunk write, the thunk execute, the 8-byte check read, and five U-Boot writes.
- **bootUboot, what the routines did.** A routine request is one whose address lies in the scratch
  area. The bytes of a routine are yours, so they are not compared. The fake records what each routine
  did on the chip, and the test checks:
  - the secure-boot word is read after the version and before the first non-routine write;
  - SCTLR is read before the thunk runs;
  - no other register is read;
  - the only register write is RVBAR = `0x4a000000`;
  - there is exactly one warm reset, into AArch64 at `0x4a000000`, and it is the last thing that
    happens.
- **Refusals.** Eleven cases, each with its required substring. For the nine refused before the
  upload, no non-routine write may happen first. For all but the SPL that runs and fails, the SPL
  must not run.

`test/fel.test.ts` checks the public API on the same fake: version, readl, chunked writes,
bootUboot, and the refusals.

**When a vector fails.** If the gap is in the fake (an instruction `arm.ts` doesn't know, a CP15
register the fake doesn't model), extend the fake from the ARM ARM. Never edit `fel.json`. If you
think a vector is wrong, say so in #355, and the spec owner checks it.

## Files

| File | What to do |
|---|---|
| `src/protocol.ts` | **Delete without opening.** Rewrite what you need from this spec (framing, version parsing, eGON check, routines, write plan) in new files |
| `src/felDevice.ts` | **Delete without opening.** Write a new one with the public API above |
| `test/legacy-internals.test.ts` | **Delete without opening.** The vectors replace it |
| `src/thunk.ts` | Keep, unchanged (MIT, with its notice) |
| `src/a133.ts`, `src/transport.ts`, `src/uboot.ts`, `src/index.ts` | Keep. You may read and change them. `index.ts` re-exports `FelVersion` from `./protocol`: point it at your new file |
| `test/fakes.ts`, `test/arm.ts`, `test/fel.test.ts`, `test/vectors.test.ts`, `test/vectors/fel.json` | The contract and its harness. Read them. You may extend the fake, but don't edit `fel.json` |
