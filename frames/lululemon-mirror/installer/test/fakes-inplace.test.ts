import { describe, expect, it } from 'vitest';
import {
  FAKE_LAN_IP, FAKE_SERIAL, FakeStockAgent, FakeTwoStageFastboot, GWSB_MAGIC, IDX_END, IDX_INFO, OP_ABORT, OP_RESEND,
  OP_STREAM, STOCK_BLE, STOCK_PROBE_PORT, buildFakeGwsbBundle, chunkFor, hmacSha256Hex, sha256Hex, splitGwsb,
  verifyFakeGwsb, type FakeBleCharacteristic, type FakeStockAgentOptions,
} from './fakes-inplace';

const SECRET = Uint8Array.from({ length: 32 }, (_, i) => i * 7 + 1);
const URL_BUNDLE = `http://${FAKE_LAN_IP}:${STOCK_PROBE_PORT}/stock-bundle.bin`;

const le32 = (b: Uint8Array, at = 0) => new DataView(b.buffer, b.byteOffset, b.byteLength).getUint32(at, true);
const u32 = (...xs: number[]) => { const b = new Uint8Array(4 * xs.length); xs.forEach((x, i) => new DataView(b.buffer).setUint32(4 * i, x, true)); return b; };
const streamCmd = (start = 0, count = 0) => Uint8Array.from([OP_STREAM, ...u32(start, count)]);
/** Waits (on macrotasks, up to 2 s) until `ok()` holds. */
async function until(ok: () => boolean) {
  const deadline = Date.now() + 2000;
  while (!ok()) { if (Date.now() > deadline) throw new Error('timed out'); await new Promise((r) => setTimeout(r, 1)); }
}
const resendCmd = (idx: number[]) => Uint8Array.from([OP_RESEND, idx.length & 0xff, idx.length >> 8, ...u32(...idx)]);

async function agentWith(o: FakeStockAgentOptions = {}, secret: Uint8Array | undefined = SECRET) {
  const bundle = await buildFakeGwsbBundle(secret ? { secret } : {});
  return new FakeStockAgent(bundle, o);
}

/** Kiln's side of the BLE link, written from the protocol: connect, subscribe, and collect DATA frames until END. */
async function connectBle(agent: FakeStockAgent) {
  const device = await agent.fakeBluetooth.requestDevice({ filters: [{ services: [STOCK_BLE.SERVICE] }] });
  const server = await device.gatt!.connect();
  const svc = await server.getPrimaryService(STOCK_BLE.SERVICE);
  const [meta, ctrl, data] = await Promise.all([STOCK_BLE.META, STOCK_BLE.CTRL, STOCK_BLE.DATA].map((u) => svc.getCharacteristic(u)));
  const frames: Uint8Array[] = [];
  let onEnd: (() => void) | undefined;
  data!.addEventListener('characteristicvaluechanged', (e) => {
    const v = (e.target as FakeBleCharacteristic).value!;
    const f = new Uint8Array(v.buffer.slice(v.byteOffset, v.byteOffset + v.byteLength));
    frames.push(f);
    if (le32(f) === IDX_END) onEnd?.();
  });
  await data!.startNotifications();
  /** Writes a CTRL command and waits for its END frame; returns the frames it brought. */
  const run = async (cmd: Uint8Array<ArrayBuffer>) => {
    const from = frames.length;
    const ended = new Promise<void>((r) => { onEnd = r; });
    await ctrl!.writeValueWithResponse(cmd);
    await ended;
    return frames.slice(from);
  };
  return { device, server, meta: meta!, ctrl: ctrl!, data: data!, frames, run };
}

describe('buildFakeGwsbBundle', () => {
  it('writes the GWSB header, the meta and the jar, self-consistent', async () => {
    const b = await buildFakeGwsbBundle();
    expect(new TextDecoder().decode(b.bytes.subarray(0, 4))).toBe(GWSB_MAGIC);
    const metaLen = le32(b.bytes, 4), jarLen = le32(b.bytes, 8);
    expect(metaLen).toBe(new TextEncoder().encode(b.metaJson).length);
    expect(jarLen).toBe(16384);
    expect(jarLen % 4096).toBe(0);
    expect(b.bytes.length).toBe(12 + metaLen + jarLen);
    expect(b.bytes.subarray(12 + metaLen)).toEqual(b.jarBytes);
    expect(Array.from(b.jarBytes.subarray(0, 4))).toEqual([0x50, 0x4b, 0x03, 0x04]);
    expect(b.sha256).toBe(await sha256Hex(b.jarBytes));
    expect(b.meta).toMatchObject({
      version: 1, mirrorVersion: '1.24.0', servicesPath: '/system/framework/services.jar', servicesSize: 16384,
      servicesSha256: b.sha256, servicesOdexFound: false, servicesOdexPath: null, servicesHasClassesDex: true,
      lanIp: '192.0.2.42', httpPort: 18765, serialno: FAKE_SERIAL,
      fiemap: { size: 16384, extents: [{ logical: 0, physical: 409600, length: 16384, flags: 1 }] },
    });
    expect(b.hmacSha256).toBeUndefined();
    expect(b.metaJson).toBe(b.metaBaseJson);
    expect(await verifyFakeGwsb(b.bytes)).toEqual({ shaOk: true, hmacOk: null });
  });

  it('keeps StockBundle.java\'s meta field order', async () => {
    const { meta } = await buildFakeGwsbBundle();
    expect(Object.keys(meta)).toEqual(['version', 'mirrorVersion', 'fingerprint', 'servicesPath', 'servicesSize', 'servicesSha256',
      'servicesOdexFound', 'servicesOdexPath', 'servicesHasClassesDex', 'fiemap', 'lanIp', 'httpPort', 'serialno']);
  });

  it('takes its options: odex, no LAN, port, serial, version', async () => {
    const b = await buildFakeGwsbBundle({ servicesOdexFound: true, lanIp: null, httpPort: 18000, serialno: '0000fake99', mirrorVersion: '2.0.0' });
    expect(b.meta).toMatchObject({ servicesOdexFound: true, servicesOdexPath: '/system/framework/oat/arm64/services.odex',
      servicesHasClassesDex: false, lanIp: null, httpPort: 18000, serialno: '0000fake99', mirrorVersion: '2.0.0' });
  });

  it('signs with the per-run secret: HMAC-SHA256 over the meta up to hmacSha256 (no closing brace), then the jar', async () => {
    const b = await buildFakeGwsbBundle({ secret: SECRET });
    expect(b.metaJson).toBe(`${b.metaBaseJson.slice(0, -1)},"hmacSha256":"${b.hmacSha256}"}`);
    expect(b.metaJson.endsWith(`,"hmacSha256":"${b.hmacSha256}"}`)).toBe(true);
    expect(b.meta.hmacSha256).toBe(b.hmacSha256);
    expect(b.hmacSha256).toBe(await hmacSha256Hex(SECRET, new TextEncoder().encode(b.metaBaseJson.slice(0, -1)), b.jarBytes));
    expect(await verifyFakeGwsb(b.bytes, SECRET)).toEqual({ shaOk: true, hmacOk: true });
    // another run's secret doesn't verify it
    expect((await verifyFakeGwsb(b.bytes, new Uint8Array(32))).hmacOk).toBe(false);
  });

  it('corruptHmac and corruptSha each break one check', async () => {
    const badHmac = await buildFakeGwsbBundle({ secret: SECRET, corruptHmac: true });
    expect(await verifyFakeGwsb(badHmac.bytes, SECRET)).toEqual({ shaOk: true, hmacOk: false });
    const badSha = await buildFakeGwsbBundle({ secret: SECRET, corruptSha: true });
    expect(badSha.meta.servicesSha256).not.toBe(badSha.sha256);
    expect(await verifyFakeGwsb(badSha.bytes, SECRET)).toEqual({ shaOk: false, hmacOk: true }); // signed as written
    await expect(buildFakeGwsbBundle({ corruptHmac: true })).rejects.toThrow(/secret/);
  });

  it('splitGwsb refuses a bad magic and lengths that don\'t add up', async () => {
    const b = await buildFakeGwsbBundle();
    const wrong = b.bytes.slice(); wrong[0] = 0x58;
    expect(() => splitGwsb(wrong)).toThrow(/not a GWSB/);
    expect(() => splitGwsb(b.bytes.subarray(0, b.bytes.length - 1))).toThrow(/don't match/);
  });
});

describe('FakeStockAgent over HTTP', () => {
  it('answers the CORS and Private Network Access preflight', async () => {
    const agent = await agentWith();
    const r = await agent.fakeFetch(URL_BUNDLE, { method: 'OPTIONS' });
    expect(r.status).toBe(204);
    expect(r.headers.get('access-control-allow-origin')).toBe('*');
    expect(r.headers.get('access-control-allow-private-network')).toBe('true');
    expect(r.headers.get('access-control-allow-methods')).toBe('GET, HEAD, OPTIONS');
    expect((await agent.fakeFetch(`http://${FAKE_LAN_IP}:${STOCK_PROBE_PORT}/`, { method: 'OPTIONS' })).status).toBe(405);
  });

  it('serves the whole bundle byte for byte, HEAD with its length, and the page', async () => {
    const agent = await agentWith();
    const r = await agent.fakeFetch(URL_BUNDLE);
    expect(r.status).toBe(200);
    expect(r.headers.get('access-control-allow-origin')).toBe('*');
    expect(r.headers.get('content-length')).toBe(String(agent.bundle.bytes.length));
    const got = new Uint8Array(await r.arrayBuffer());
    expect(got).toEqual(agent.bundle.bytes);
    expect(await verifyFakeGwsb(got, SECRET)).toEqual({ shaOk: true, hmacOk: true });
    const head = await agent.fakeFetch(URL_BUNDLE, { method: 'HEAD' });
    expect(head.headers.get('content-length')).toBe(String(agent.bundle.bytes.length));
    expect(head.body).toBeNull();
    const page = await agent.fakeFetch(`http://${FAKE_LAN_IP}:${STOCK_PROBE_PORT}/`);
    expect(page.headers.get('content-type')).toBe('text/html');
    expect(await page.text()).toContain('href="/stock-bundle.bin"');
    expect((await agent.fakeFetch(`http://${FAKE_LAN_IP}:${STOCK_PROBE_PORT}/nope`)).status).toBe(404);
    expect(agent.stats.lanRequests).toBe(4);
  });

  it('drop-midway: the body errors after dropAfterBytes (half by default)', async () => {
    const agent = await agentWith({ lanMode: 'drop-midway' });
    const r = await agent.fakeFetch(URL_BUNDLE);
    expect(r.status).toBe(200);
    const reader = r.body!.getReader();
    let got = 0;
    await expect((async () => { for (;;) { const { done, value } = await reader.read(); if (done) return; got += value.length; } })()).rejects.toThrow(/network error/);
    expect(got).toBe(Math.floor(agent.bundle.bytes.length / 2));

    const early = await agentWith({ lanMode: 'drop-midway', dropAfterBytes: 100 });
    await expect((await early.fakeFetch(URL_BUNDLE)).arrayBuffer()).rejects.toThrow();
  });

  it('lna-denied and offline reject like a network error; timeout waits for the abort', async () => {
    for (const lanMode of ['lna-denied', 'offline'] as const) {
      const agent = await agentWith({ lanMode });
      await expect(agent.fakeFetch(URL_BUNDLE)).rejects.toThrow(TypeError);
      await expect(agent.fakeFetch(URL_BUNDLE, { method: 'OPTIONS' })).rejects.toThrow('Failed to fetch');
    }
    const slow = await agentWith({ lanMode: 'timeout' });
    await expect(slow.fakeFetch(URL_BUNDLE, { signal: AbortSignal.timeout(20) })).rejects.toMatchObject({ name: 'TimeoutError' });
  });

  it('only its own origin answers, and nothing with no LAN address', async () => {
    const agent = await agentWith();
    await expect(agent.fakeFetch(`http://192.0.2.43:${STOCK_PROBE_PORT}/stock-bundle.bin`)).rejects.toThrow(TypeError);
    const noLan = new FakeStockAgent(await buildFakeGwsbBundle({ lanIp: null }));
    expect(noLan.origin).toBeNull();
    await expect(noLan.fakeFetch(URL_BUNDLE)).rejects.toThrow(TypeError);
  });
});

describe('FakeStockAgent over BLE', () => {
  it('reads META: the bundle meta with bundleSize and chunkSize, within 512 bytes', async () => {
    const agent = await agentWith();
    const { meta } = await connectBle(agent);
    const v = await meta.readValue();
    expect(v.byteLength).toBeLessThanOrEqual(512);
    const m = JSON.parse(new TextDecoder().decode(v)) as Record<string, unknown>;
    expect(m).toMatchObject({ lanIp: FAKE_LAN_IP, httpPort: STOCK_PROBE_PORT, bundleSize: agent.bundle.bytes.length, chunkSize: 178,
      chunkLocked: false, servicesSha256: agent.bundle.sha256, serialno: FAKE_SERIAL, servicesOdexFound: false });
    expect(m.hmacSha256).toBeUndefined(); // the signature travels in the bundle's own header
    expect(chunkFor(185)).toBe(178);
    expect(chunkFor(23)).toBe(16);
    expect(chunkFor(1000)).toBe(508);
  });

  it('META trims a FIEMAP that would pass 512 bytes', async () => {
    const extents = Array.from({ length: 12 }, (_, i) => ({ logical: i * 4096, physical: (200 + 2 * i) * 4096, length: 4096, flags: i === 11 ? 1 : 0 }));
    const bundle = await buildFakeGwsbBundle({ jarBytes: new Uint8Array(12 * 4096), extents });
    expect((bundle.meta.fiemap as { extents: unknown[] }).extents).toHaveLength(12); // the header keeps all of it
    const { meta } = await connectBle(new FakeStockAgent(bundle));
    const v = await meta.readValue();
    expect(v.byteLength).toBeLessThanOrEqual(512);
    expect(JSON.parse(new TextDecoder().decode(v)).fiemap).toEqual({ truncated: true, extentCount: 12 });
  });

  it('META tiers down as StockBundle.bleMetaJson does, and a fixture no real agent could describe is refused', async () => {
    const secret = Uint8Array.from({ length: 32 }, (_, i) => i + 1);
    const signed = new FakeStockAgent(await buildFakeGwsbBundle({ secret }));
    const m = JSON.parse(new TextDecoder().decode(signed.bleMeta())) as Record<string, unknown>;
    expect(m).toMatchObject({ fiemap: { truncated: true, extentCount: 1 }, hasHmac: true });
    expect(m.hmacSha256).toBeUndefined();
    const long = new FakeStockAgent(await buildFakeGwsbBundle({ secret, fingerprint: `fake/${'x'.repeat(400)}` }));
    const c = JSON.parse(new TextDecoder().decode(long.bleMeta())) as Record<string, unknown>;
    expect(c).toMatchObject({ compact: true, serialno: FAKE_SERIAL, servicesOdexFound: false, servicesHasClassesDex: true, hasHmac: true });
    expect(c.fingerprint).toBeUndefined();
    expect(long.bleMeta().length).toBeLessThanOrEqual(512);
    const absurd = new FakeStockAgent(await buildFakeGwsbBundle({ lanIp: `192.0.2.${'9'.repeat(500)}` }));
    expect(() => absurd.bleMeta()).toThrow(/still over 512 bytes/);
  });

  it('0x01 streams INFO, the chunks and END; a dropped chunk comes back on 0x02, byte-identical', async () => {
    const agent = await agentWith({ bleDropChunks: [1, 5, 40] });
    const ble = await connectBle(agent);
    const size = agent.bundle.bytes.length, cs = 178, total = Math.ceil(size / cs);
    expect(total).toBeGreaterThan(41);

    const first = await ble.run(streamCmd());
    const info = first[0]!;
    expect(info.length).toBe(16);
    expect([le32(info), le32(info, 4), le32(info, 8), le32(info, 12)]).toEqual([IDX_INFO, cs, size, total]);
    const end = first.at(-1)!;
    expect(end.length).toBe(8);
    expect(le32(end)).toBe(IDX_END);
    expect(le32(end, 4)).toBe(total); // the peripheral sent every chunk; three were lost on the way

    const chunks = new Map<number, Uint8Array>();
    for (const f of first.slice(1, -1)) chunks.set(le32(f), f.subarray(4));
    const missing = Array.from({ length: total }, (_, i) => i).filter((i) => !chunks.has(i));
    expect(missing).toEqual([1, 5, 40]);
    expect((JSON.parse(new TextDecoder().decode(await ble.meta.readValue())) as { chunkLocked: boolean }).chunkLocked).toBe(true);

    const again = await ble.run(resendCmd(missing));
    expect(again.map((f) => le32(f))).toEqual([1, 5, 40, IDX_END]);
    expect(le32(again.at(-1)!, 4)).toBe(3);
    for (const f of again.slice(0, -1)) chunks.set(le32(f), f.subarray(4));

    const out = new Uint8Array(size);
    for (let i = 0; i < total; i++) out.set(chunks.get(i)!, i * cs);
    expect(out).toEqual(agent.bundle.bytes);
    expect(await verifyFakeGwsb(out, SECRET)).toEqual({ shaOk: true, hmacOk: true });
    expect(agent.stats).toMatchObject({ bleStreams: 1, bleResends: 1, bleAborts: 0, totalBleChunksSent: total + 3, bleChunksDropped: 3 });
  });

  it('0x01 with a start and count streams that range; 0x02 before 0x01 does nothing', async () => {
    const agent = await agentWith();
    const ble = await connectBle(agent);
    await ble.ctrl.writeValueWithResponse(resendCmd([0]));
    await new Promise((r) => setTimeout(r, 5));
    expect(ble.frames).toHaveLength(0);
    const part = await ble.run(streamCmd(10, 4));
    expect(part.map((f) => le32(f))).toEqual([IDX_INFO, 10, 11, 12, 13, IDX_END]);
    expect(part[2]!.subarray(4)).toEqual(agent.bundle.bytes.subarray(11 * 178, 12 * 178));
  });

  it('0x03 aborts the stream: no END follows', async () => {
    const agent = await agentWith();
    const ble = await connectBle(agent);
    await ble.ctrl.writeValueWithResponse(streamCmd());
    await until(() => ble.frames.length >= 4);
    await ble.ctrl.writeValueWithResponse(Uint8Array.of(OP_ABORT));
    const seen = ble.frames.length;
    await new Promise((r) => setTimeout(r, 10));
    expect(ble.frames.length).toBeLessThanOrEqual(seen + 1); // at most the frame already in flight
    expect(ble.frames.some((f) => le32(f) === IDX_END)).toBe(false);
    expect(agent.stats.bleAborts).toBe(1);
  });

  it('a stall holds the stream until the next CTRL write', async () => {
    const agent = await agentWith({ bleStallAfterChunks: 3 });
    const ble = await connectBle(agent);
    await ble.ctrl.writeValueWithResponse(streamCmd());
    await until(() => ble.frames.length >= 4);
    await new Promise((r) => setTimeout(r, 20)); // nothing more comes while it's stalled
    expect(ble.frames.map((f) => le32(f))).toEqual([IDX_INFO, 0, 1, 2]);
    const rest = await ble.run(resendCmd([2]));
    expect(le32(rest.at(-1)!)).toBe(IDX_END);
    expect(rest.map((f) => le32(f))).toContain(2);
  });

  it('disconnecting ends the stream and fires gattserverdisconnected', async () => {
    const agent = await agentWith();
    const ble = await connectBle(agent);
    let gone = false;
    ble.device.addEventListener('gattserverdisconnected', () => { gone = true; });
    await ble.ctrl.writeValueWithResponse(streamCmd());
    ble.server.disconnect();
    await new Promise((r) => setTimeout(r, 10));
    expect(gone).toBe(true);
    expect(ble.frames.some((f) => le32(f) === IDX_END)).toBe(false);
    await expect(ble.meta.readValue()).rejects.toMatchObject({ name: 'NetworkError' });
  });

  it('no adapter: unavailable and requestDevice rejects; a chooser without the service finds nothing', async () => {
    const off = await agentWith({ bleUnavailable: true });
    expect(await off.fakeBluetooth.getAvailability()).toBe(false);
    await expect(off.fakeBluetooth.requestDevice({ filters: [{ services: [STOCK_BLE.SERVICE] }] })).rejects.toMatchObject({ name: 'NotFoundError' });
    const on = await agentWith();
    expect(await on.fakeBluetooth.getAvailability()).toBe(true);
    await expect(on.fakeBluetooth.requestDevice({ filters: [{ services: ['0000180f-0000-1000-8000-00805f9b34fb'] }] })).rejects.toMatchObject({ name: 'NotFoundError' });
  });
});

describe('FakeTwoStageFastboot', () => {
  const blob = (n: number) => new Blob([new Uint8Array(n)]);

  it('records the staging session and the surgical session', async () => {
    const fb = new FakeTwoStageFastboot();
    expect(await fb.getvar('serialno')).toBe(FAKE_SERIAL);
    await fb.flashBlob('userdata', blob(4096));
    await fb.reboot();
    expect(fb.connected).toBe(false);
    await expect(fb.getvar('serialno')).rejects.toThrow(/disconnected/);

    fb.nextSession();
    expect(fb.session).toBe(2);
    expect(await fb.getvar('serialno')).toBe(FAKE_SERIAL);
    const progress: number[] = [];
    await fb.flashBlob('system', blob(8192), (x) => progress.push(x));
    await fb.flashBlob('userdata', blob(4096));
    await fb.reboot();
    expect(progress.at(-1)).toBe(1);
    expect(fb.flashedPartitions).toEqual([
      { session: 1, partition: 'userdata', bytes: 4096 },
      { session: 2, partition: 'system', bytes: 8192 },
      { session: 2, partition: 'userdata', bytes: 4096 },
    ]);
    expect(fb.rebootCount).toBe(2);
  });

  it('injects a different Mirror on session 2, a refused flash, and a mid-flash disconnect', async () => {
    const swapped = new FakeTwoStageFastboot({ session2Serial: '0000fake02' });
    await swapped.reboot();
    expect(await swapped.nextSession().getvar('serialno')).toBe('0000fake02');

    const refused = new FakeTwoStageFastboot({ refuseFlash: { session: 2, partition: 'system' } });
    await refused.flashBlob('system', blob(10)); // session 1 is fine
    await refused.reboot();
    await expect(refused.nextSession().flashBlob('system', blob(10))).rejects.toThrow(/FAILED \(remote: write failed\)/);
    expect(refused.flashedPartitions.map((f) => f.session)).toEqual([1]);

    const cut = new FakeTwoStageFastboot({ disconnectMidFlash: { session: 1, partition: 'userdata', at: 0.5 } });
    const seen: number[] = [];
    await expect(cut.flashBlob('userdata', blob(10), (x) => seen.push(x))).rejects.toThrow(/disconnected/);
    expect(seen).toEqual([0.25, 0.5]);
    expect(cut.connected).toBe(false);
    expect(cut.flashedPartitions).toEqual([]);

    const big = new FakeTwoStageFastboot({ userdataBytes: 100 });
    await expect(big.flashBlob('userdata', blob(101))).rejects.toThrow(/size too large/);
  });
});
