// Tests of the internals that the clean-room rewrite replaces (#355). The implementer deletes this
// file without opening it; vectors.test.ts and docs/research/kiln-cleanroom/bootpatch-spec.md
// replace it.
import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { beforeAll, describe, expect, it } from 'vitest';
import { parseBootImage } from '../src/bootimg';
import { type Compressor, loadCompressor } from '../src/compress';
import { Cpio, S_IFDIR, S_IFREG, patchEncryption, patchVerity } from '../src/cpio';
import { grepProp, magiskConfigText } from '../src/magisk';

const GOLDEN = process.env.S1_WEB_GOLDEN ?? join(homedir(), 'src/aqara-panel-backups/web-golden');
const HAVE_GOLDEN = existsSync(join(GOLDEN, 'out/SHA256SUMS'));
const enc = new TextEncoder();
const g = (p: string) => new Uint8Array(readFileSync(join(GOLDEN, p)));
function expectSameBytes(got: Uint8Array, want: Uint8Array, what: string) {
  expect(Buffer.compare(got, want), what).toBe(0);
}
let comp: Compressor;
beforeAll(async () => { comp = await loadCompressor(); });

describe('cpio (unit)', () => {
  const sample = () => {
    const c = new Cpio();
    c.add(0o750, 'init', enc.encode('stock init'));
    c.mkdir(0o755, 'dev');
    c.add(0o644, 'fstab.x', enc.encode('/dev/a /a ext4 ro wait,verify,avb=vbmeta,forceencrypt=footer\n'));
    c.add(0o644, 'a/b', enc.encode('x'));
    c.ln('/system/bin/sh', 'sh');
    return c;
  };

  it('dumps canonically (sorted, ino from 300000) and round-trips', () => {
    const c = sample();
    const d = c.dump();
    expect(new TextDecoder().decode(d.subarray(0, 14))).toBe('070701000493e0'); // 300000
    const back = Cpio.load(d);
    expect(back.names()).toEqual(['a/b', 'dev', 'fstab.x', 'init', 'sh']);
    expectSameBytes(back.dump(), d, 'roundtrip');
    expect(back.entries.get('dev')!.mode).toBe(S_IFDIR | 0o755);
    expect(back.entries.get('init')!.mode).toBe(S_IFREG | 0o750);
    // insertion order does not matter
    const c2 = new Cpio();
    for (const n of [...c.names()].reverse()) c2.entries.set(n, c.entries.get(n)!);
    expectSameBytes(c2.dump(), d, 'order independence');
    expect(d.length % 4).toBe(0);
  });

  it('test(): stock / Magisk / unsupported', () => {
    const c = sample();
    expect(c.test()).toBe(0);
    c.add(0, '.backup/.magisk', enc.encode('x'));
    expect(c.test()).toBe(1);
    c.add(0o755, 'sbin/su', enc.encode(''));
    expect(c.test()).toBe(2);
  });

  it('backup then restore gives back the original', () => {
    const orig = sample();
    const c = orig.clone();
    c.add(0o750, 'init', enc.encode('magiskinit'));
    c.mkdir(0o750, 'overlay.d');
    c.add(0o644, 'overlay.d/x', enc.encode('new'));
    c.rm('a/b');
    c.backup(orig, comp);
    expect(c.names()).toEqual(['.backup', '.backup/.rmlist', '.backup/a/b.xz', '.backup/init.xz', 'dev', 'fstab.x', 'init', 'overlay.d', 'overlay.d/x', 'sh']);
    expect(new TextDecoder().decode(c.entries.get('.backup/.rmlist')!.data)).toBe('overlay.d\0overlay.d/x\0');
    expect(c.entries.get('.backup/.rmlist')!.mode).toBe(S_IFREG);
    c.add(0, '.backup/.magisk', enc.encode('cfg'));
    const r = Cpio.load(c.dump());
    r.restore(comp);
    expectSameBytes(r.dump(), orig.dump(), 'restore');
  });

  it('patch() is a no-op with KEEPVERITY/KEEPFORCEENCRYPT, and strips flags otherwise', () => {
    const c = sample();
    const before = c.dump();
    c.patch(true, true);
    expectSameBytes(c.dump(), before, 'patch(true,true)');
    c.patch(false, false);
    expect(new TextDecoder().decode(c.entries.get('fstab.x')!.data)).toBe('/dev/a /a ext4 ro wait\n');
    const b = enc.encode('wait,verifyatboot,fileencryption=aes,check');
    const n = patchEncryption(b);
    expect(new TextDecoder().decode(b.subarray(0, n))).toBe('wait,verifyatboot,check');
    expect(new TextDecoder().decode(b.subarray(0, patchVerity(b.subarray(0, n))))).toBe('wait,check');
  });
});

describe('magisk config (unit)', () => {
  it('formats like boot_patch.sh and reads back like grep_prop', () => {
    const s = magiskConfigText({ keepVerity: true, keepForceEncrypt: true, recoveryMode: false, vendorBoot: false, preinitDevice: 'cache', sha1: 'ab' });
    expect(s).toBe('KEEPVERITY=true\nKEEPFORCEENCRYPT=true\nRECOVERYMODE=false\nVENDORBOOT=false\nPREINITDEVICE=cache\nSHA1=ab\n');
    expect(grepProp('SHA1', 'A=1\r\nSHA1=x\r\nSHA1=y\n')).toBe('x');
    expect(grepProp('PREINITDEVICE', 'A=1\n')).toBe('');
    expect(magiskConfigText({ keepVerity: true, keepForceEncrypt: true, recoveryMode: false, vendorBoot: false, preinitDevice: '', sha1: '' }))
      .toBe('KEEPVERITY=true\nKEEPFORCEENCRYPT=true\nRECOVERYMODE=false\nVENDORBOOT=false\n');
  });
});

describe.skipIf(!HAVE_GOLDEN)('golden: internals', () => {
  it('parses the stock image like magiskboot', () => {
    const b = parseBootImage(g('in/boot-4.5.80.img'));
    expect([b.headerVersion, b.pageSize, b.kernel.length, b.ramdiskFormat, b.sha256Id, !!b.avb]).toEqual([2, 2048, 20568072, 'gzip', false, true]);
  });
});
