// The bundle an agent sends for an in-place install: Kiln refuses it unless it is one whole GWSB bundle, its payload
// matches its hash, it came from this device, and (with the stage's secret) its signature holds.
import { createHmac } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { hmacSha256Hex, memoryBundleCache, parseGwsbBundle, sha256Hex, verifyGwsbBundle } from '../src/bundle';
import { namedBackupZip } from '../src/backup';

const SECRET = new TextEncoder().encode('stage secret for tests');
const PAYLOAD = new TextEncoder().encode('the file the agent read, as bytes');

function frame(meta: Uint8Array, payload: Uint8Array): Uint8Array {
  const out = new Uint8Array(12 + meta.length + payload.length);
  out.set([0x47, 0x57, 0x53, 0x42]);
  const v = new DataView(out.buffer);
  v.setUint32(4, meta.length, true); v.setUint32(8, payload.length, true);
  out.set(meta, 12); out.set(payload, 12 + meta.length);
  return out;
}

/**
 * A bundle as the agent writes it (StockBundle): the meta, then (signed) the HMAC over the meta up to that field (no
 * closing brace) and the payload.
 */
async function bundle(o: { meta?: Record<string, unknown>; payload?: Uint8Array; sign?: boolean; secret?: Uint8Array } = {}): Promise<Uint8Array> {
  const payload = o.payload ?? PAYLOAD;
  const base = JSON.stringify({ serialno: 'TESTSERIAL0', servicesSha256: await sha256Hex(PAYLOAD), extents: [[0, 4096]], ...o.meta });
  if (o.sign === false) return frame(new TextEncoder().encode(base), payload);
  const mac = await hmacSha256Hex(o.secret ?? SECRET, new TextEncoder().encode(base.slice(0, -1)), payload);
  return frame(new TextEncoder().encode(`${base.slice(0, -1)},"hmacSha256":"${mac}"}`), payload);
}

describe('parseGwsbBundle', () => {
  it('splits a whole bundle into its meta and payload', async () => {
    const p = parseGwsbBundle(await bundle());
    expect(p.meta.serialno).toBe('TESTSERIAL0');
    expect(p.payload).toEqual(PAYLOAD);
    expect(new TextDecoder().decode(p.rawMetaBytes)).toMatch(/^\{"serialno".*"hmacSha256":"[0-9a-f]{64}"\}$/);
  });

  it('refuses a bad magic, a truncated bundle, trailing bytes and meta that isn\'t a JSON object', async () => {
    const b = await bundle();
    const bad = b.slice(); bad[0] = 0x58;
    expect(() => parseGwsbBundle(bad)).toThrow(/wrong magic/);
    expect(() => parseGwsbBundle(b.slice(0, 8))).toThrow(/shorter than its header/);
    expect(() => parseGwsbBundle(b.slice(0, b.length - 1))).toThrow(/truncated/);
    const more = new Uint8Array(b.length + 2); more.set(b);
    expect(() => parseGwsbBundle(more)).toThrow(/2 bytes after its payload/);
    expect(() => parseGwsbBundle(frame(new TextEncoder().encode('[1]'), PAYLOAD))).toThrow(/isn't a JSON object/);
    expect(() => parseGwsbBundle(frame(new TextEncoder().encode('{nope'), PAYLOAD))).toThrow(/isn't UTF-8 JSON/);
  });
});

describe('verifyGwsbBundle', () => {
  it('passes a bundle signed byte for byte as the probe app signs it (StockBundle.computeHmacHex)', async () => {
    // The probe's HMAC input is the meta JSON with no hmacSha256 field and no closing brace, then the payload. The first
    // real Mirror run (2026-11-25) refused every bundle while Kiln put the brace back.
    const base = `{"version":1,"serialno":"TESTSERIAL0","servicesSha256":"${await sha256Hex(PAYLOAD)}","httpPort":18765`;
    const mac = createHmac('sha256', Buffer.from(SECRET)).update(base).update(PAYLOAD).digest('hex');
    const signed = frame(new TextEncoder().encode(`${base},"hmacSha256":"${mac}"}`), PAYLOAD);
    await expect(verifyGwsbBundle(signed, { secret: SECRET, requireHmac: true, expectedSerial: 'TESTSERIAL0' })).resolves.toBeTruthy();
    // and the same meta signed with the brace in is someone else's bytes
    const withBrace = createHmac('sha256', Buffer.from(SECRET)).update(`${base}}`).update(PAYLOAD).digest('hex');
    const wrong = frame(new TextEncoder().encode(`${base},"hmacSha256":"${withBrace}"}`), PAYLOAD);
    await expect(verifyGwsbBundle(wrong, { secret: SECRET, requireHmac: true })).rejects.toThrow(/signature doesn't match/);
  });

  it('passes a signed bundle from this device', async () => {
    const p = await verifyGwsbBundle(await bundle(), { secret: SECRET, requireHmac: true, expectedSerial: 'TESTSERIAL0' });
    expect(p.payload).toEqual(PAYLOAD);
  });

  it('refuses a payload that doesn\'t match its hash, under the default field or another', async () => {
    await expect(verifyGwsbBundle(await bundle({ payload: new TextEncoder().encode('something else') }))).rejects.toThrow(/doesn't match servicesSha256/);
    const other = await bundle({ meta: { servicesSha256: undefined, blobSha256: await sha256Hex(PAYLOAD) } });
    await expect(verifyGwsbBundle(other, { payloadHashField: 'blobSha256' })).resolves.toBeTruthy();
    await expect(verifyGwsbBundle(await bundle({ meta: { blobSha256: '0'.repeat(64) } }), { payloadHashField: 'blobSha256' })).rejects.toThrow(/doesn't match blobSha256/);
    await expect(verifyGwsbBundle(other, { requirePayloadHash: true })).rejects.toThrow(/no servicesSha256/);
  });

  it('refuses a bundle read from another device', async () => {
    await expect(verifyGwsbBundle(await bundle(), { expectedSerial: 'OTHERSERIAL' })).rejects.toThrow(/another device/);
    const noSerial = await bundle({ meta: { serialno: undefined } });
    await expect(verifyGwsbBundle(noSerial, { expectedSerial: 'TESTSERIAL0' })).resolves.toBeTruthy();
    await expect(verifyGwsbBundle(noSerial, { expectedSerial: 'TESTSERIAL0', requireSerial: true })).rejects.toThrow(/no serialno/);
  });

  it('refuses a tampered bundle, a wrong secret, and a missing signature when one is required', async () => {
    const b = await bundle();
    const tampered = b.slice(); tampered[tampered.length - 1] = tampered[tampered.length - 1]! ^ 1;
    // the payload hash catches a changed payload first
    await expect(verifyGwsbBundle(tampered, { secret: SECRET })).rejects.toThrow(/doesn't match servicesSha256/);
    // signed with another secret
    const otherSecret = await bundle({ secret: new TextEncoder().encode('not the secret') });
    await expect(verifyGwsbBundle(otherSecret, { secret: SECRET })).rejects.toThrow(/signature doesn't match/);
    // a changed payload with its hash changed to match: the signature still catches it
    const swapped = new TextEncoder().encode('another file');
    const forged = await bundle({ payload: swapped, meta: { servicesSha256: await sha256Hex(swapped) } });
    const original = await bundle();
    // forged was signed over its own meta and payload, so it verifies; replace its signature with the original's
    const sigOf = (x: Uint8Array) => /"hmacSha256":"([0-9a-f]{64})"/.exec(new TextDecoder().decode(parseGwsbBundle(x).rawMetaBytes))![1]!;
    const replayed = frame(new TextEncoder().encode(new TextDecoder().decode(parseGwsbBundle(forged).rawMetaBytes).replace(sigOf(forged), sigOf(original))), swapped);
    await expect(verifyGwsbBundle(replayed, { secret: SECRET })).rejects.toThrow(/signature doesn't match/);
    // the meta changed after signing
    const text = new TextDecoder().decode(parseGwsbBundle(b).rawMetaBytes).replace('TESTSERIAL0', 'TESTSERIAL1');
    await expect(verifyGwsbBundle(frame(new TextEncoder().encode(text), PAYLOAD), { secret: SECRET })).rejects.toThrow(/signature doesn't match/);
    const unsigned = await bundle({ sign: false });
    await expect(verifyGwsbBundle(unsigned, { secret: SECRET })).resolves.toBeTruthy();
    await expect(verifyGwsbBundle(unsigned, { secret: SECRET, requireHmac: true })).rejects.toThrow(/not signed/);
    await expect(verifyGwsbBundle(b, { requireHmac: true })).rejects.toThrow(/no secret/);
    // hmacSha256 not last: refused rather than guessed at
    const notLast = frame(new TextEncoder().encode(JSON.stringify({ hmacSha256: 'a'.repeat(64), servicesSha256: await sha256Hex(PAYLOAD) })), PAYLOAD);
    await expect(verifyGwsbBundle(notLast, { secret: SECRET })).rejects.toThrow(/must be the meta's last field/);
  });
});

describe('the bundle cache', () => {
  it('keeps bundles by serial and digest, newest first', async () => {
    const c = memoryBundleCache();
    await c.put('A', 'd1', new Uint8Array([1]));
    await c.put('A', 'd2', new Uint8Array([2]));
    await c.put('B', 'd3', new Uint8Array([3]));
    expect(await c.get('A', 'd1')).toEqual(new Uint8Array([1]));
    expect(await c.get('B', 'd1')).toBeUndefined();
    expect((await c.latest('A'))?.digest).toBe('d2');
    await c.delete('A', 'd2');
    expect((await c.latest('A'))?.digest).toBe('d1');
    expect(await c.latest('C')).toBeUndefined();
  });
});

describe('namedBackupZip', () => {
  it('keeps each file\'s own name, with a SHA256SUMS of them', async () => {
    const zip = new Uint8Array(await (await namedBackupZip([
      { filename: 'restore-blocks.sparse', data: new Uint8Array([1, 2, 3]) }, { filename: 'manifest.json', data: new TextEncoder().encode('{}') },
    ], { dir: 'restore-20261124' })).arrayBuffer());
    const text = new TextDecoder('latin1').decode(zip);
    expect(text).toContain('restore-20261124/restore-blocks.sparse');
    expect(text).toContain('restore-20261124/manifest.json');
    expect(text).toContain(`${await sha256Hex(new Uint8Array([1, 2, 3]))}  restore-blocks.sparse\n`);
  });

  it('refuses paths, duplicates and SHA256SUMS as a name', async () => {
    await expect(namedBackupZip([{ filename: '../x', data: new Uint8Array() }])).rejects.toThrow(/plain file name/);
    await expect(namedBackupZip([{ filename: 'SHA256SUMS', data: new Uint8Array() }])).rejects.toThrow(/plain file name/);
    await expect(namedBackupZip([{ filename: 'a', data: new Uint8Array() }, { filename: 'a', data: new Uint8Array() }])).rejects.toThrow(/share a name/);
  });
});
