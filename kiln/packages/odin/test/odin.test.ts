// OdinDevice against the fake Download mode device (fake-download-mode.ts, built from Heimdall's packet formats).
import { describe, expect, it } from 'vitest';
import { OdinDevice, OdinError, type PitEntry, parsePit } from '../src/index.ts';
import { FakeDownloadMode, type FakeOptions, type Wire, buildPit, response, syntheticPit } from './fake-download-mode.ts';

const KiB = 1024;
const MiB = 1024 * KiB;

/** The synthetic PIT's partitions, sized from its block counts (512-byte blocks). */
function fake(options: Partial<FakeOptions> & { recoveryBlocks?: number } = {}): FakeDownloadMode {
  const { pit, entries } = syntheticPit({ recoveryBlocks: options.recoveryBlocks });
  return new FakeDownloadMode({
    pit,
    partitions: entries.filter((e) => e.blockCount).map((e) => [e.identifier!, e.blockCount! * 512]),
    ...options,
  });
}

/** A patterned image (period 4093 bytes, so no part lines up with another). */
function image(size: number, seed = 1): Uint8Array<ArrayBuffer> {
  const period = new Uint8Array(4093);
  for (let i = 0; i < period.length; i++) period[i] = (i * 31 + seed) & 0xff;
  const data = new Uint8Array(size);
  for (let at = 0; at < size; at += period.length) data.set(period.subarray(0, Math.min(period.length, size - at)), at);
  return data;
}

/** Byte-equal, fast on megabytes (vitest's toEqual walks them one element at a time). */
function same(a: Uint8Array, b: Uint8Array): boolean {
  return Buffer.from(a.buffer, a.byteOffset, a.byteLength).equals(Buffer.from(b.buffer, b.byteOffset, b.byteLength));
}

/** Connected, in a session that may write `totalBytes`, with the PIT downloaded. */
async function ready(device: FakeDownloadMode, totalBytes?: number, writable?: ('RECOVERY' | 'VBMETA')[]) {
  const odin = new OdinDevice(device, { writable });
  await odin.beginSession({ totalBytes });
  const pit = await odin.pit();
  return { odin, pit };
}

const RECOVERY_ID = 6;

describe('a whole flash, as Heimdall does it', () => {
  it('writes the image to RECOVERY and nothing else, with progress, then reboots', async () => {
    const device = fake({ beginAnswer: 0x20000, recoveryBlocks: 8192, dvif: '@#MODEL=SM-X000;SALES=XXX;VER=X000XXU1AAA1;UN=0123456789ABCDEF;CAPA=32;' });
    const odin = new OdinDevice(device);
    const info = await odin.info();
    expect(info).toMatchObject({ model: 'SM-X000', salesCode: 'XXX', bootloaderVersion: 'X000XXU1AAA1' });
    expect(info.properties).toEqual({ MODEL: 'SM-X000', SALES: 'XXX', VER: 'X000XXU1AAA1', CAPA: '32' });
    expect(JSON.stringify(info)).not.toContain('0123456789ABCDEF'); // the serial never leaves the parser

    const img = image(2 * MiB + 1000);
    await odin.beginSession({ totalBytes: img.length });
    expect(device.protocolVersion).toBe(4);
    expect(device.totalBytes).toBe(img.length);
    expect(odin.transferShape.packetSize).toBe(MiB);
    const pit = await odin.pit();
    const progress: number[] = [];
    await odin.flash({ partition: 'RECOVERY', entry: pit.find('RECOVERY')!, image: img, onProgress: (p) => progress.push(p.sentBytes) });
    await odin.end({ reboot: true });
    await odin.close();

    const recovery = device.partitions.get(RECOVERY_ID)!;
    expect(same(recovery.subarray(0, img.length), img)).toBe(true);
    expect(recovery.subarray(img.length).every((b) => b === 0xee)).toBe(true);
    for (const [id, part] of device.partitions) {
      if (id !== RECOVERY_ID) expect(part.every((b) => b === 0xee), `partition ${id}`).toBe(true);
    }
    expect(progress).toEqual([MiB, 2 * MiB, img.length]);
    expect(device.rebooted).toBe(true);
    expect(device.closed).toBe(true);
  });

  it('sends Heimdall\'s packets, with its empty transfers where its SendPacket flags put them', async () => {
    const device = fake(); // begin answer 0: 128 KiB parts, as on older devices
    const { odin, pit } = await ready(device, 300 * KiB);
    await odin.flash({ partition: 'RECOVERY', entry: pit.find('RECOVERY')!, image: image(300 * KiB) });
    await odin.end();

    const ctl = (type: number, request: number, ...args: number[]): Wire => ({ dir: 'out', what: 'control', type, request, args });
    const reply = (type: number, value = 0): Wire => ({ dir: 'in', what: 'reply', bytes: 8, type, value });
    const empty: Wire = { dir: 'out', what: 'empty' };
    const part: Wire = { dir: 'out', what: 'part', bytes: 128 * KiB };
    const pitBytes = pit.bytes.length; // 1216: three parts of at most 500
    expect(device.wire).toEqual([
      { dir: 'out', what: 'text', text: 'ODIN' }, { dir: 'in', what: 'reply', bytes: 4 },
      ctl(0x64, 0, 4), empty, reply(0x64, 0), // begin session; 0: no part size
      ctl(0x64, 2, 300 * KiB), empty, reply(0x64, 0), // total bytes
      ctl(0x65, 1), empty, reply(0x65, pitBytes), // PIT: its size
      ctl(0x65, 2), empty, { dir: 'in', what: 'reply', bytes: 500 },
      ctl(0x65, 2, 1), empty, { dir: 'in', what: 'reply', bytes: 500 },
      ctl(0x65, 2, 2), empty, { dir: 'in', what: 'reply', bytes: 216 },
      { dir: 'in', what: 'timeout' }, // the empty read after the last part; this device sends none
      ctl(0x65, 3), empty, reply(0x65, 0),
      ctl(0x66, 0), empty, reply(0x66, 0), // file transfer
      ctl(0x66, 2, 3 * 128 * KiB), empty, reply(0x66, 0), // one sequence of three whole parts
      part, reply(0x00, 0),
      empty, part, reply(0x00, 1), // "empty transfer before" from the second part on
      empty, part, reply(0x00, 2),
      empty, ctl(0x66, 3, 0, 300 * KiB, 0, 2, RECOVERY_ID, 1), empty, reply(0x66, 0), // its real bytes, end of file
      ctl(0x67, 0), empty, reply(0x67, 0), // end session, no reboot
    ]);
  });

  it('splits a long image into sequences and marks only the last one as the end of the file', async () => {
    const device = fake({ beginAnswer: 1, recoveryBlocks: 65536 }); // 32 MiB
    const img = image(31 * MiB + 5);
    const { odin, pit } = await ready(device, img.length);
    await odin.flash({ partition: 'RECOVERY', entry: pit.find('RECOVERY')!, image: new Blob([img]) });
    const ends = device.wire.flatMap((w) => w.what === 'control' && w.type === 0x66 && w.request === 3 ? [w.args] : []);
    expect(ends).toEqual([[0, 30 * MiB, 0, 2, RECOVERY_ID], [0, MiB + 5, 0, 2, RECOVERY_ID, 1]]);
    const sequences = device.wire.flatMap((w) => w.what === 'control' && w.type === 0x66 && w.request === 2 ? [w.args[0]] : []);
    expect(sequences).toEqual([30 * MiB, 2 * MiB]);
    expect(same(device.partitions.get(RECOVERY_ID)!.subarray(0, img.length), img)).toBe(true);
    expect(device.written).toBe(img.length);
  });

  it('writes VBMETA only when the caller made it writable', async () => {
    const device = fake();
    const { odin, pit } = await ready(device, 4096, ['RECOVERY', 'VBMETA']);
    await odin.flash({ partition: 'VBMETA', entry: pit.find('VBMETA')!, image: image(4096) });
    expect(device.partitions.get(7)!.subarray(0, 4096)).toEqual(image(4096));
  });

  it('uses 4096-byte blocks for a UFS partition\'s size', async () => {
    const { pit: pitBytes, entries } = syntheticPit({ deviceType: 8, recoveryBlocks: 256 }); // 1 MiB on UFS
    const device = new FakeDownloadMode({ pit: pitBytes, partitions: entries.filter((e) => e.blockCount).map((e) => [e.identifier!, e.blockCount! * 4096]) });
    const { odin, pit } = await ready(device, 2 * MiB);
    await odin.flash({ partition: 'RECOVERY', entry: pit.find('RECOVERY')!, image: image(MiB) });
    await expect(odin.flash({ partition: 'RECOVERY', entry: pit.find('RECOVERY')!, image: image(MiB + 1) }))
      .rejects.toThrow(/1048577 bytes and the partition 1048576/);
  });
});

describe('reads', () => {
  it('asks the device type during a session', async () => {
    const device = fake({ deviceType: 0x1234 });
    const odin = new OdinDevice(device);
    await odin.beginSession();
    expect(await odin.deviceType()).toBe(0x1234);
    expect(device.totalBytes).toBeUndefined(); // a read-only session announces nothing
  });

  it('takes the empty packet some devices send after the PIT', async () => {
    const device = fake({ emptyAfterPit: true });
    const { pit } = await ready(device);
    expect(pit.find('RECOVERY')?.identifier).toBe(RECOVERY_ID);
    expect(device.wire.some((w) => w.what === 'timeout')).toBe(false);
  });

  it('refuses a PIT the device announces at an absurd size', async () => {
    const device = fake();
    device.override = (type, request) => type === 0x65 && request === 1 ? response(0x65, 0) : undefined;
    const odin = new OdinDevice(device);
    await odin.beginSession();
    await expect(odin.pit()).rejects.toThrow(/0-byte PIT/);
  });

  it('says when a device doesn\'t answer the info query', async () => {
    await expect(new OdinDevice(fake()).info()).rejects.toMatchObject({ code: 'protocol' });
  });
});

describe('the handshake', () => {
  it('fails clearly when the device answers something else', async () => {
    const device = fake();
    device.send = async () => {};
    device.receive = async () => new TextEncoder().encode('NOPE');
    await expect(new OdinDevice(device).beginSession()).rejects.toMatchObject({ code: 'handshake', message: /"NOPE"/ });
  });

  it('skips a FAIL left over from before, alone or in the same read', async () => {
    for (const replies of [['FAIL', 'LOKE'], ['FAILLOKE']]) {
      const device = fake();
      const inner = device.receive.bind(device);
      const queued = replies.map((r) => new TextEncoder().encode(r));
      device.receive = async (max) => queued.shift() ?? inner(max);
      const send = device.send.bind(device);
      device.send = async (data) => { await send(data); if (data.length === 4) await inner(64); }; // drop the fake's own LOKE
      await new OdinDevice(device).beginSession();
    }
  });

  it('is skipped on resume', async () => {
    const device = fake({ resumed: true });
    await new OdinDevice(device).beginSession({ resume: true });
    expect(device.wire[0]).toMatchObject({ what: 'control', type: 0x64, request: 0 });
  });
});

describe('refusals: nothing is written', () => {
  /** Runs `attempt` and checks it was refused before any file transfer packet went out. */
  async function refused(device: FakeDownloadMode, attempt: () => Promise<unknown>, why: RegExp) {
    await expect(attempt()).rejects.toMatchObject({ code: 'refused', message: why });
    expect(device.wire.some((w) => w.what === 'control' && w.type === 0x66)).toBe(false);
    expect(device.written).toBe(0);
  }

  it('anything but RECOVERY by default', async () => {
    const device = fake();
    const { odin, pit } = await ready(device, MiB);
    await refused(device, () => odin.flash({ partition: 'VBMETA', entry: pit.find('VBMETA')!, image: image(10) }), /isn't writable on this device/);
    const boot = 'BOOT' as 'RECOVERY'; // past the types, as plain JavaScript could
    await refused(device, () => odin.flash({ partition: boot, entry: pit.find('BOOT')!, image: image(10) }), /only RECOVERY and VBMETA can ever be written/);
  });

  it('a device made writable for anything else', () => {
    expect(() => new OdinDevice(fake(), { writable: ['BOOT' as 'RECOVERY'] })).toThrow(/BOOT can't be made writable/);
  });

  it('an entry that isn\'t the partition named', async () => {
    const device = fake();
    const { odin, pit } = await ready(device, MiB);
    await refused(device, () => odin.flash({ partition: 'RECOVERY', entry: pit.find('BOOT')!, image: image(10) }), /the entry is "BOOT"/);
  });

  it('an entry from another PIT, or a copy of one', async () => {
    const device = fake();
    const { odin, pit } = await ready(device, MiB);
    const other = parsePit(pit.bytes).find('RECOVERY')!;
    await refused(device, () => odin.flash({ partition: 'RECOVERY', entry: other, image: image(10) }), /isn't from the PIT this session downloaded/);
    const copy: PitEntry = { ...pit.find('RECOVERY')! };
    await refused(device, () => odin.flash({ partition: 'RECOVERY', entry: copy, image: image(10) }), /isn't from the PIT/);
  });

  it('before the PIT is downloaded', async () => {
    const device = fake();
    const odin = new OdinDevice(device);
    await odin.beginSession({ totalBytes: 10 });
    const entry = parsePit(syntheticPit().pit).find('RECOVERY')!;
    await refused(device, () => odin.flash({ partition: 'RECOVERY', entry, image: image(10) }), /download the PIT/);
  });

  it('an image too big for the partition, or empty', async () => {
    const device = fake();
    const { odin, pit } = await ready(device, 4 * MiB);
    const entry = pit.find('RECOVERY')!; // 4096 blocks: 2 MiB
    await refused(device, () => odin.flash({ partition: 'RECOVERY', entry, image: image(2 * MiB + 1) }), /2097153 bytes and the partition 2097152/);
    await refused(device, () => odin.flash({ partition: 'RECOVERY', entry, image: new Uint8Array(0) }), /empty/);
  });

  it('more than the session announced, or anything in a session that announced nothing', async () => {
    const device = fake();
    const { odin, pit } = await ready(device, 100);
    await refused(device, () => odin.flash({ partition: 'RECOVERY', entry: pit.find('RECOVERY')!, image: image(101) }), /announced 100 more bytes/);
    const readOnly = fake();
    const session = await ready(readOnly);
    await refused(readOnly, () => session.odin.flash({ partition: 'RECOVERY', entry: session.pit.find('RECOVERY')!, image: image(1) }), /announced 0 more bytes/);
  });

  it('a partition the PIT can\'t size, has twice, or gives the modem', async () => {
    const cases: [string, Parameters<typeof buildPit>[0], RegExp][] = [
      ['no block count', [{ partitionName: 'RECOVERY', identifier: 6, deviceType: 2, blockCount: 0 }], /doesn't give its size/],
      ['not eMMC or UFS', [{ partitionName: 'RECOVERY', identifier: 6, deviceType: 1, blockCount: 100 }], /device type 1/],
      ['twice', [{ partitionName: 'RECOVERY', identifier: 6, deviceType: 2, blockCount: 100 },
        { partitionName: 'RECOVERY', identifier: 9, deviceType: 2, blockCount: 100 }], /more than once/],
      ['the modem', [{ partitionName: 'RECOVERY', identifier: 6, binaryType: 1, deviceType: 2, blockCount: 100 }], /application processor/],
    ];
    for (const [name, entries, why] of cases) {
      const device = new FakeDownloadMode({ pit: buildPit(entries), partitions: [[6, 51200], [9, 51200]] });
      const { odin, pit } = await ready(device, 10);
      await refused(device, () => odin.flash({ partition: 'RECOVERY', entry: pit.entries[0]!, image: image(10) }), why);
      expect(name).toBeTruthy();
    }
  });
});

describe('failures part way', () => {
  it('surfaces the device\'s failure status, and refuses to write again in that session', async () => {
    const device = fake();
    device.override = (type, request) => type === 0x66 && request === 3 ? response(0xffffffff, -5) : undefined;
    const { odin, pit } = await ready(device, MiB);
    const entry = pit.find('RECOVERY')!;
    const error = await odin.flash({ partition: 'RECOVERY', entry, image: image(1000) }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(OdinError);
    expect(error).toMatchObject({ code: 'device', status: -5 });
    device.override = undefined;
    await expect(odin.flash({ partition: 'RECOVERY', entry, image: image(10) })).rejects.toThrow(/stopped part way/);
    await odin.end(); // ending still works
  });

  it('stops when a part is acknowledged out of order', async () => {
    const device = fake();
    const { odin, pit } = await ready(device, MiB);
    const receive = device.receive.bind(device);
    device.receive = async (max) => {
      const r = await receive(max);
      return r.length === 8 && r[0] === 0 && r[4] === 1 ? response(0, 7) : r;
    };
    await expect(odin.flash({ partition: 'RECOVERY', entry: pit.find('RECOVERY')!, image: image(300 * KiB) }))
      .rejects.toMatchObject({ code: 'protocol', message: /acknowledged part 7, expected 1/ });
  });

  it('stops between packets when aborted', async () => {
    const device = fake();
    const { odin, pit } = await ready(device, MiB);
    const abort = new AbortController();
    const error = await odin.flash({
      partition: 'RECOVERY', entry: pit.find('RECOVERY')!, image: image(300 * KiB), signal: abort.signal,
      onProgress: () => abort.abort(),
    }).catch((e: unknown) => e);
    expect((error as Error).name).toBe('AbortError');
    expect(device.wire.filter((w) => w.what === 'part')).toHaveLength(1);
    await expect(odin.flash({ partition: 'RECOVERY', entry: pit.find('RECOVERY')!, image: image(10) })).rejects.toThrow(/stopped part way/);
  });

  it('doesn\'t commit a sequence when aborted after its last part', async () => {
    const device = fake();
    const { odin, pit } = await ready(device, MiB);
    const abort = new AbortController();
    await expect(odin.flash({
      partition: 'RECOVERY', entry: pit.find('RECOVERY')!, image: image(1000), signal: abort.signal,
      onProgress: () => abort.abort(),
    })).rejects.toMatchObject({ name: 'AbortError' });
    expect(device.wire.some((w) => w.what === 'control' && w.type === 0x66 && w.request === 3)).toBe(false);
    expect(device.written).toBe(0);
  });

  it('doesn\'t start an aborted flash at all', async () => {
    const device = fake();
    const { odin, pit } = await ready(device, MiB);
    await expect(odin.flash({ partition: 'RECOVERY', entry: pit.find('RECOVERY')!, image: image(10), signal: AbortSignal.abort() }))
      .rejects.toMatchObject({ name: 'AbortError' });
    expect(device.wire.some((w) => w.what === 'control' && w.type === 0x66)).toBe(false);
  });

  it('a response of the wrong type', async () => {
    const device = fake();
    device.override = (type, request) => type === 0x64 && request === 0 ? response(0x65, 0) : undefined;
    await expect(new OdinDevice(device).beginSession()).rejects.toMatchObject({ code: 'protocol', message: /type 0x64, got 0x65/ });
  });

  it('ends the connection when a session setup fails part way, keeping nothing from it', async () => {
    const device = fake({ beginAnswer: 1 });
    device.override = (type, request) => type === 0x64 && request === 2 ? response(0x64, 3) : undefined;
    const odin = new OdinDevice(device);
    await expect(odin.beginSession({ totalBytes: 5 })).rejects.toThrow(/refused the session's total/);
    expect(odin.transferShape.packetSize).toBe(128 * KiB); // not the 1 MiB the device took before the failure
    // The device kept that part size; this side can't undo it, so the same connection can't try again.
    await expect(odin.beginSession({ totalBytes: 5, resume: true })).rejects.toMatchObject({ code: 'state', message: /failed/ });
    await expect(odin.pit()).rejects.toMatchObject({ code: 'state' });
    // A new connection resumes and agrees the part size again, so both sides match.
    device.override = undefined;
    const again = new OdinDevice(device);
    await again.beginSession({ totalBytes: 5, resume: true });
    expect(again.transferShape.packetSize).toBe(MiB);
    expect(device.controls().filter((c) => c.startsWith('0x64.5('))).toEqual(['0x64.5(1048576)', '0x64.5(1048576)']);
  });

  it('a device that refuses the part size or the total', async () => {
    const partSize = fake({ beginAnswer: 1 });
    partSize.override = (type, request) => type === 0x64 && request === 5 ? response(0x64, 1) : undefined;
    await expect(new OdinDevice(partSize).beginSession()).rejects.toThrow(/refused a 1048576-byte part size/);
    const total = fake();
    total.override = (type, request) => type === 0x64 && request === 2 ? response(0x64, 3) : undefined;
    await expect(new OdinDevice(total).beginSession({ totalBytes: 5 })).rejects.toThrow(/refused the session's total/);
  });
});

describe('the session\'s order', () => {
  it('refuses calls out of order', async () => {
    const odin = new OdinDevice(fake());
    await expect(odin.pit()).rejects.toMatchObject({ code: 'state' });
    await expect(odin.end()).rejects.toMatchObject({ code: 'state' });
    await odin.beginSession();
    await expect(odin.info()).rejects.toMatchObject({ code: 'state' });
    await expect(odin.beginSession()).rejects.toMatchObject({ code: 'state' });
    const pit = await odin.pit();
    await odin.end();
    await expect(odin.flash({ partition: 'RECOVERY', entry: pit.find('RECOVERY')!, image: image(1) })).rejects.toMatchObject({ code: 'state' });
  });

  it('runs one call at a time, and refuses a second one while the first runs', async () => {
    const device = fake();
    const { odin, pit } = await ready(device, MiB);
    const inside: unknown[] = [];
    const flashing = odin.flash({
      partition: 'RECOVERY', entry: pit.find('RECOVERY')!, image: image(300 * KiB),
      onProgress: () => { inside.push(odin.end().catch((e: unknown) => e)); }, // from a progress callback
    });
    await expect(odin.deviceType()).rejects.toMatchObject({ code: 'state', message: /while flash\(\) is running/ });
    await expect(odin.close()).rejects.toMatchObject({ code: 'state' });
    await flashing;
    for (const e of await Promise.all(inside)) expect(e).toMatchObject({ code: 'state', message: /end\(\) can't start/ });
    expect(device.partitions.get(RECOVERY_ID)!.subarray(0, 300 * KiB)).toEqual(image(300 * KiB));
    await odin.end(); // free again
  });

  it('ends with a reboot even when the device restarts before it answers', async () => {
    const device = fake({ answersReboot: false });
    const odin = new OdinDevice(device);
    await odin.beginSession();
    await odin.end({ reboot: true });
    expect(device.rebooted).toBe(true);
  });
});
