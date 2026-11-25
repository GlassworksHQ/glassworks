// The `dpmd` hook against the real thing: stock 1.24.0's `libdpmframework.so` and a real backup
// `.ko`. Skipped unless both are named in the environment, because neither may live in the repo
// (Hard Rules 3 and 4):
//
//   MIRROR_STOCK_SO=/path/to/libdpmframework.so \
//   MIRROR_BACKUP_KO=/path/to/backup.ko \
//   MIRROR_DPMD_HOOK_RUN=1 \
//     npx vitest run --root tools/mirror test/dpmd-hook.dev.test.ts
//
// With `MIRROR_DPMD_HOOK_RUN=1` it also executes the constructor: the payload is mapped at a chosen
// address in an arm64 Linux container, the initialiser it chains to is replaced by `exit(42)`, and
// strace is the witness — the module pointer, both block paths, both syscall numbers and the branch
// all show up in the trace. Nothing here touches a Mirror.
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import {
  buildDpmdHook, changedRanges, ehFrameBoundaries, HOOK_BLOCK_NODES, parseElf64Aarch64, planDpmdHook,
} from '../src/dpmd-hook';

const STOCK_SO = process.env.MIRROR_STOCK_SO;
const BACKUP_KO = process.env.MIRROR_BACKUP_KO;
const RUN = process.env.MIRROR_DPMD_HOOK_RUN === '1';

const haveArtifacts = Boolean(STOCK_SO && BACKUP_KO && existsSync(STOCK_SO) && existsSync(BACKUP_KO));

describe.skipIf(!haveArtifacts)('dpmd hook against stock 1.24.0', () => {
  if (!haveArtifacts) return;
  const so = new Uint8Array(readFileSync(STOCK_SO!));
  const ko = new Uint8Array(readFileSync(BACKUP_KO!));
  const { bytes, plan } = buildDpmdHook(so, ko);
  const layout = parseElf64Aarch64(so);

  it('is the ELF64 library dpmd links, at the size the image carries', () => {
    expect(so.length).toBe(223232);
    expect(layout.initArrayVaddr).toBe(0x43f80);
    expect(layout.initArrayBytes).toBe(80);
    expect(layout.ehFrame.addr).toBe(0x2ebe0);
    expect(layout.ehFrame.size).toBe(0x5210);
    expect(layout.relocations.find((r) => r.rOffset === layout.initArrayVaddr)?.addend).toBe(0xfd70);
  });

  it('plans a payload that fits the RX tail and starts on an unwind boundary', () => {
    const boundaries = ehFrameBoundaries(layout);
    expect(boundaries).toContain(plan.payloadVaddr);
    expect(plan.payloadVaddr).toBeGreaterThanOrEqual(layout.ehFrame.addr);
    expect(plan.payloadVaddr + plan.payloadBytes).toBeLessThanOrEqual(layout.ehFrame.addr + layout.ehFrame.size);
    expect(plan.chainedVaddr).toBe(0xfd70);
    expect(plan.entryVaddr).toBe(plan.payloadVaddr + 8);
    expect(plan.payloadOffset).toBe(plan.payloadVaddr); // the RX LOAD maps vaddr == offset here
  });

  it('writes only the payload and one relocation addend', () => {
    expect(bytes.length).toBe(so.length);
    const ranges = changedRanges(so, bytes);
    for (const r of ranges) {
      const inPayload = r.start >= plan.payloadOffset && r.end <= plan.payloadOffset + plan.payloadBytes;
      const inAddend = r.start >= plan.relocEntryOffset + 16 && r.end <= plan.relocEntryOffset + 24;
      expect(inPayload || inAddend).toBe(true);
    }
    const after = parseElf64Aarch64(bytes);
    expect(after.relocations.find((r) => r.rOffset === after.initArrayVaddr)?.addend).toBe(plan.entryVaddr);
  });

  it('carries the module inside the read+execute segment, byte for byte', () => {
    expect([...bytes.subarray(plan.moduleOffset, plan.moduleOffset + ko.length)]).toEqual([...ko]);
    const rx = layout.segments.find((s) => (s.flags & 5) === 5)!;
    expect(plan.moduleOffset + ko.length).toBeLessThanOrEqual(rx.offset + rx.filesz);
  });

  describe.skipIf(!RUN)('executes the constructor', () => {
    let dir: string | undefined;
    afterAll(() => {
      if (dir) rmSync(dir, { recursive: true, force: true });
    });

    it('calls init_module, then fchmodat on both nodes, then the initialiser it replaced', () => {
      dir = mkdtempSync(join(tmpdir(), 'gw-dpmd-hook-'));
      const payloadPath = join(dir, 'payload.bin');
      writeFileSync(payloadPath, bytes.subarray(plan.payloadOffset, plan.payloadOffset + plan.payloadBytes));

      const harness = `
#define _GNU_SOURCE
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
int main(int argc, char **argv) {
  if (argc != 4) { fprintf(stderr, "usage: %s payload payloadVaddr chainedVaddr\\n", argv[0]); return 2; }
  FILE *f = fopen(argv[1], "rb");
  if (!f) { perror("fopen"); return 3; }
  fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
  uint64_t payloadVaddr = strtoull(argv[2], 0, 0);
  uint64_t chained = strtoull(argv[3], 0, 0);
  uint64_t at = 0x20100000;
  void *m = mmap((void *)0x20000000, 0x400000, PROT_READ | PROT_WRITE | PROT_EXEC,
                 MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
  if (m == MAP_FAILED) { perror("mmap"); return 4; }
  if (fread((void *)at, 1, n, f) != (size_t)n) { perror("fread"); return 5; }
  int64_t bias = (int64_t)at - (int64_t)payloadVaddr;
  uint64_t stub = (uint64_t)(bias + (int64_t)chained);
  uint32_t code[3] = {0xd2800540u, 0xd2800ba8u, 0xd4000001u}; /* movz x0,#42; movz x8,#93; svc 0 */
  memcpy((void *)stub, code, sizeof code);
  __builtin___clear_cache((char *)at, (char *)at + n);
  __builtin___clear_cache((char *)stub, (char *)stub + sizeof code);
  printf("harness: payload=%p len=%ld payloadVaddr=0x%lx stub=0x%lx\\n",
         (void *)at, n, (unsigned long)payloadVaddr, (unsigned long)stub);
  fflush(stdout);
  ((void (*)(void))(at + 8))();
  printf("harness: constructor returned\\n");
  return 6;
}
`;
      writeFileSync(join(dir, 'harness.c'), harness);

      const script = `
set -e
apt-get update -qq >/dev/null 2>&1
apt-get install -y -qq gcc libc6-dev strace >/dev/null 2>&1
gcc -O1 -o /w/harness /w/harness.c
set +e
strace -f -o /w/trace -e trace=init_module,fchmodat,exit_group /w/harness /w/payload.bin ${plan.payloadVaddr} ${plan.chainedVaddr}
echo "harness exit: $?"
cat /w/trace
`;
      const out = execFileSync('docker', [
        'run', '--rm', '-v', `${dir}:/w`, 'arm64v8/debian:bookworm-slim', 'bash', '-c', script,
      ], { encoding: 'utf8', timeout: 300_000 });

      const expectedModule = 0x20100000 + (plan.moduleOffset - plan.payloadOffset);
      expect(out).toContain(`harness: payload=0x20100000 len=${plan.payloadBytes}`);
      // init_module takes the embedded module, of the module's real length, and "" (not NULL)
      expect(out).toMatch(new RegExp(`init_module\\(0x${expectedModule.toString(16)}, ${ko.length}, ""\\)`));
      // both nodes, mode 0444, through fchmodat (53) and not fchownat (54)
      for (const node of HOOK_BLOCK_NODES) {
        expect(out).toContain(`fchmodat(AT_FDCWD, "${node}", 0444)`);
      }
      expect(out).not.toContain('fchownat');
      // and the branch lands on the initialiser we replaced, which exits 42
      expect(out).toContain('harness exit: 42');
      expect(out).toMatch(/\+\+\+ exited with 42 \+\+\+/);
      expect(out).not.toContain('constructor returned');
    }, 300_000); // one apt-get install plus a docker run
  });
});
