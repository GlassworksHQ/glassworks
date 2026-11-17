// services.jar's adb key: the Glass Panes image lets one key in without asking, by its fingerprint in
// UsbDebuggingManager. Kiln moves that string to the installer's key fingerprint, keeping the dex's
// string table sorted. Checked here on d8's output (test/fixtures/dex) and minimal dexes, and against
// the SDK's dexdump, which verifies as ART does, when it is installed.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { zipEntry } from '@glassworks/app-adb';
import { GLASS_PANES_KEY_FINGERPRINT as FROM, adbFingerprint, adbKeyLines, md5, replaceDexString, trustAdbKey, trustFingerprint } from '../src';
import { DEXDUMP, disassemble } from './dexdump';
import { SERVICES_STRINGS, fakeAdbKey, fakeDex, fakeServicesJar } from './fakes';

const FIXTURE = new Uint8Array(readFileSync(join(__dirname, 'fixtures/dex/classes.dex')));
/** before every other string, after every other one, and in the fingerprint's own slot ("D" < it < "D: ") */
const TARGETS = { early: '00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF', late: 'FF:EE:DD:CC:BB:AA:99:88:77:66:55:44:33:22:11:00', same: 'D5:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00' };

const enc = (s: string) => new TextEncoder().encode(s);
const occurrences = (b: Uint8Array, s: string) => Buffer.from(b).toString('latin1').split(s).length - 1;

/** The dex's strings by index, read here without the code under test. */
function strings(dex: Uint8Array): string[] {
  const b = Buffer.from(dex);
  const n = b.readUInt32LE(0x38), off = b.readUInt32LE(0x3c);
  return Array.from({ length: n }, (_, i) => { let at = b.readUInt32LE(off + 4 * i); while (b[at]! & 0x80) at++; at++; return b.toString('latin1', at, b.indexOf(0, at)); });
}

function checksumsValid(dex: Uint8Array): boolean {
  const b = Buffer.from(dex);
  let a = 1, s = 0;
  for (const x of b.subarray(12)) { a = (a + x) % 65521; s = (s + a) % 65521; }
  return b.readUInt32LE(8) === (((s << 16) | a) >>> 0) && createHash('sha1').update(b.subarray(32)).digest().equals(b.subarray(12, 32));
}

describe('the key fingerprint Android 6 makes', () => {
  it('md5 is RFC 1321\'s, at every padding length', () => {
    for (const n of [0, 1, 55, 56, 63, 64, 65, 119, 120, 128, 524, 100_003]) {
      const b = new Uint8Array(n).map((_, i) => (i * 31 + n) & 0xff);
      expect(Buffer.from(md5(b)).toString('hex'), `${n} bytes`).toBe(createHash('md5').update(b).digest('hex'));
    }
  });
  it('is the MD5 of the decoded key as uppercase hex pairs and colons, whatever the comment', () => {
    const key = fakeAdbKey(7);
    const want = createHash('md5').update(Buffer.from(key.split(' ')[0]!, 'base64')).digest('hex').toUpperCase().match(/../g)!.join(':');
    expect(adbFingerprint(key)).toBe(want);
    expect(adbFingerprint(`${key.split(' ')[0]} someone@else\n`)).toBe(want);
  });
  it('adbKeyLines takes every line, in order, and names the one that isn\'t a key (#426 review)', () => {
    const [a, b] = [fakeAdbKey(1), fakeAdbKey(2)];
    expect(adbKeyLines(`${a}\r\n\n${b}\n`)).toEqual([a, b]);
    expect(() => adbKeyLines(`${a}\nQAAA= x@y`)).toThrow(/^adb key 2 of 2: that isn't an adb public key/);
    expect(() => adbKeyLines('\n')).toThrow(/no adb key given/);
    expect(() => adbKeyLines(`${a}\n${fakeAdbKey(3, 'x\ty')}`)).toThrow(/^adb key 2 of 2: that isn't an adb public key line/);
  });
  it('refuses a line that isn\'t a 524-byte adb key', () => {
    for (const line of ['QAAA= kiln@glassworks', 'not base64!', '', fakeAdbKey().slice(0, 100)]) expect(() => adbFingerprint(line)).toThrow(/isn't an adb public key.*Nothing was changed/);
  });
});

describe('replaceDexString: the fingerprint moves to its sorted place, every reference renumbered', () => {
  it.each(Object.entries(TARGETS))('on d8\'s output, %s: valid checksums, a sorted table, the same text at every reference', async (_, to) => {
    const input = FIXTURE.slice();
    const out = await replaceDexString(input, FROM, to);
    expect(input).toEqual(FIXTURE); // the input is never written
    expect(out.length).toBe(FIXTURE.length);
    expect(checksumsValid(out)).toBe(true);
    const s = strings(out);
    expect([...s].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))).toEqual(s);
    expect(s).toContain(to);
    expect(s).not.toContain(FROM);
    expect(occurrences(out, FROM)).toBe(0);
    expect(occurrences(out, to)).toBe(1);
    // the strings other than the fingerprint are the same set
    expect(s.filter((x) => x !== to)).toEqual(strings(FIXTURE).filter((x) => x !== FROM));
  });

  it.skipIf(!DEXDUMP).each(Object.entries(TARGETS))('dexdump verifies it (%s) and prints the same code, locals and annotations', async (_, to) => {
    const before = disassemble(FIXTURE), after = disassemble(await replaceDexString(FIXTURE, FROM, to));
    expect(after.err).toBe('');
    expect(before.text).toContain('"<FP>"');
    expect(after.text).toBe(before.text);
  });

  it.skipIf(!DEXDUMP)('without the renumbering, dexdump refuses the dex, as ART would: the bytes swapped in place', () => {
    const swapped = Buffer.from(FIXTURE);
    swapped.write(TARGETS.early, swapped.indexOf(FROM, 0, 'latin1'), 'latin1');
    createHash('sha1').update(swapped.subarray(32)).digest().copy(swapped, 12);
    let a = 1, s = 0;
    for (const x of swapped.subarray(12)) { a = (a + x) % 65521; s = (s + a) % 65521; }
    swapped.writeUInt32LE(((s << 16) | a) >>> 0, 8);
    expect(disassemble(swapped).err).toMatch(/Out-of-order string_ids/);
  });

  it('works on a dex with only strings', async () => {
    const out = await replaceDexString(await fakeDex(SERVICES_STRINGS), FROM, TARGETS.late);
    expect(strings(out)).toEqual(SERVICES_STRINGS.filter((x) => x !== FROM).concat(TARGETS.late).sort((a, b) => (a < b ? -1 : 1)));
    expect(checksumsValid(out)).toBe(true);
  });

  it.each([
    ['without the fingerprint', SERVICES_STRINGS.filter((x) => x !== FROM), /holds the Glass Panes key's fingerprint nowhere/],
    ['with it twice (once inside another string)', [...SERVICES_STRINGS, `owner ${FROM}`], /holds the Glass Panes key's fingerprint 2 times/],
    ['with it only inside a longer string', [...SERVICES_STRINGS.filter((x) => x !== FROM), `owner ${FROM}`], /inside a longer string/],
  ])('refuses a dex %s, changing nothing', async (_, list, msg) => {
    const dex = await fakeDex(list);
    const copy = dex.slice();
    await expect(replaceDexString(dex, FROM, TARGETS.early)).rejects.toThrow(msg);
    await expect(replaceDexString(dex, FROM, TARGETS.early)).rejects.toThrow(/Nothing was changed\.$/);
    expect(dex).toEqual(copy);
  });

  it('refuses a damaged dex and another dex version', async () => {
    const bad = FIXTURE.slice(); bad[bad.length - 1]! ^= 1;
    await expect(replaceDexString(bad, FROM, TARGETS.early)).rejects.toThrow(/fails its own checksum/);
    const v = FIXTURE.slice(); v.set(enc('037'), 4);
    await expect(replaceDexString(v, FROM, TARGETS.early)).rejects.toThrow(/isn't dex 035/);
  });
});

describe('trustAdbKey: services.jar that lets in the installer\'s key, not the project key', () => {
  it.each([['deflated, as the image stores it', false], ['stored', true]])('rewrites classes.dex %s, the rest of the jar as it was', async (_, stored) => {
    const jar = await fakeServicesJar({ dex: FIXTURE, stored });
    const key = fakeAdbKey(3);
    const out = (await trustAdbKey(jar, key))!;
    const dex = (await zipEntry(out, 'classes.dex'))!;
    expect(occurrences(dex, adbFingerprint(key))).toBe(1);
    expect(occurrences(dex, FROM)).toBe(0);
    expect(checksumsValid(dex)).toBe(true);
    expect(await zipEntry(out, 'META-INF/MANIFEST.MF')).toEqual(await zipEntry(jar, 'META-INF/MANIFEST.MF'));
    const method = (z: Uint8Array) => Buffer.from(z).readUInt16LE(Buffer.from(z).lastIndexOf('classes.dex', undefined, 'latin1') - 46 + 10);
    expect(method(out)).toBe(stored ? 0 : 8);
    // a CRC that zip tools check too
    const cd = Buffer.from(out).lastIndexOf('classes.dex', undefined, 'latin1') - 46;
    const crc = (await import('node:zlib')).crc32(dex);
    expect(Buffer.from(out).readUInt32LE(cd + 16)).toBe(crc);
    // a second run with the same key changes nothing; with another key, Kiln doesn't know the jar
    expect(await trustAdbKey(out, key)).toBeUndefined();
    await expect(trustAdbKey(out, fakeAdbKey(4))).rejects.toThrow(/nowhere.*Nothing was changed/);
  });

  it('accepts "already patched" only from a valid dex that names the key as a string of its own (#426 review)', async () => {
    const key = fakeAdbKey(6), fp = adbFingerprint(key);
    // a CRC-correct classes.dex that is only the fingerprint's bytes: no dex at all
    await expect(trustAdbKey(await fakeServicesJar({ dex: enc(fp) }), key)).rejects.toThrow(/isn't dex 035.*Nothing was changed/);
    // a valid dex without the project key, the fingerprint only inside a longer string
    const inside = await fakeServicesJar({ dex: await fakeDex([...SERVICES_STRINGS.filter((x) => x !== FROM), `owner ${fp}`]) });
    await expect(trustAdbKey(inside, key)).rejects.toThrow(/only inside a longer string.*Nothing was changed/);
    // a damaged dex that names it properly
    const named = await fakeDex([...SERVICES_STRINGS.filter((x) => x !== FROM), fp]);
    const bad = named.slice(); bad[bad.length - 1]! ^= 1;
    await expect(trustAdbKey(await fakeServicesJar({ dex: bad }), key)).rejects.toThrow(/fails its own checksum/);
    expect(await trustAdbKey(await fakeServicesJar({ dex: named }), key)).toBeUndefined();
  });

  it('refuses the published project key itself', async () => {
    await expect(trustFingerprint(await fakeServicesJar(), FROM)).rejects.toThrow(/is the Glass Panes project key, published with its private half.*Nothing was changed/);
  });

  it('refuses a jar with the fingerprint twice, a second dex, or a classes.dex that fails its CRC', async () => {
    await expect(trustAdbKey(await fakeServicesJar({ dex: await fakeDex([...SERVICES_STRINGS, `x${FROM}`]) }), fakeAdbKey())).rejects.toThrow(/2 times/);
    const two = await fakeServicesJar({ extra: [['classes2.dex', await fakeDex(['x'])]] });
    await expect(trustAdbKey(two, fakeAdbKey())).rejects.toThrow(/has classes\.dex, classes2\.dex; Kiln knows it with one classes\.dex/);
    const bad = Buffer.from(await fakeServicesJar({ stored: true }));
    bad[bad.indexOf('dex\n035', 0, 'latin1') + 200]! ^= 1;
    await expect(trustAdbKey(new Uint8Array(bad), fakeAdbKey())).rejects.toThrow(/doesn't match its CRC/);
  });
});
