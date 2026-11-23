// The PIT parser, on synthetic PITs only (never a real device's). One is packed by hand at libpit's offsets
// (PitData::Unpack), so the parser and the fake's buildPit are both checked against the reference, not each other.
import { describe, expect, it } from 'vitest';
import { DeviceType, PitError, blockSize, parsePit, partitionBytes } from '../src/index.ts';
import { buildPit, syntheticPit } from './fake-download-mode.ts';

/** One entry, packed byte by byte where libpit reads it. */
function handPacked(): Uint8Array {
  const data = new Uint8Array(28 + 132);
  const v = new DataView(data.buffer);
  v.setUint32(0, 0x12349876, true); // PitData::kFileIdentifier
  v.setUint32(4, 1, true); // entryCount
  data.set(new TextEncoder().encode('TAR2TEST'), 8); // com_tar2, all 8 bytes used
  data.set(new TextEncoder().encode('CPU'), 16); // cpu_bl_id
  v.setUint16(24, 3, true); // luCount
  const e = 28;
  [0, 8, 11, 5, 2, 4096, 16384, 0, 0].forEach((w, i) => v.setUint32(e + i * 4, w, true));
  data.set(new TextEncoder().encode('RECOVERY'), e + 36);
  data.set(new TextEncoder().encode('recovery.img'), e + 36 + 32);
  data.set(new TextEncoder().encode('recovery.fota'), e + 36 + 64);
  return data;
}

describe('parsePit', () => {
  it('reads every field where libpit does', () => {
    const pit = parsePit(handPacked());
    expect(pit.comTar2).toBe('TAR2TEST');
    expect(pit.cpuBlId).toBe('CPU');
    expect(pit.luCount).toBe(3);
    expect(pit.entries).toEqual([{
      binaryType: 0, deviceType: DeviceType.Ufs, identifier: 11, attributes: 5, updateAttributes: 2,
      blockSizeOrOffset: 4096, blockCount: 16384, fileOffset: 0, fileSize: 0,
      partitionName: 'RECOVERY', flashFilename: 'recovery.img', fotaFilename: 'recovery.fota',
    }]);
    expect(partitionBytes(pit.entries[0]!)).toBe(16384 * 4096);
  });

  it('agrees with the fake device\'s builder', () => {
    const { pit, entries } = syntheticPit();
    const parsed = parsePit(pit);
    expect(parsed.entries).toHaveLength(entries.length);
    expect(parsed.comTar2).toBe('TESTTAR');
    expect(parsed.cpuBlId).toBe('FAKECPU');
    const recovery = parsed.find('RECOVERY')!;
    expect(recovery).toMatchObject({ identifier: 6, deviceType: 2, blockSizeOrOffset: 20480, blockCount: 4096, flashFilename: 'recovery.img', attributes: 1 });
    expect(parsed.find('RADIO')?.binaryType).toBe(1);
    expect(parsed.find('SYSTEM')?.fotaFilename).toBe('system.fota');
    expect(parsed.entries.map((e) => e.partitionName)).toEqual(
      ['BOOTLOADER', 'PIT', 'BOOT', 'RECOVERY', 'VBMETA', 'SYSTEM', 'USERDATA', 'RADIO', '']);
  });

  it('finds only flashable entries, by their exact name (PitData::FindEntry)', () => {
    const parsed = parsePit(syntheticPit().pit);
    expect(parsed.find('recovery')).toBeUndefined();
    expect(parsed.find('RECOVERY ')).toBeUndefined();
    expect(parsed.find('')).toBeUndefined();
    expect(parsed.entries.at(-1)?.identifier).toBe(99); // the unnamed entry is listed all the same
  });

  it('keeps a copy of the bytes, and can\'t be changed', () => {
    const { pit } = syntheticPit();
    const parsed = parsePit(pit);
    expect(parsed.bytes).toEqual(pit);
    pit[40] = 0xff;
    expect(parsed.bytes[40]).not.toBe(0xff);
    expect(() => { (parsed.entries as unknown as unknown[]).push({}); }).toThrow();
    expect(() => { (parsed.entries[0] as { identifier: number }).identifier = 1; }).toThrow();
  });

  it('reads a 32-byte name with no NUL whole', () => {
    const data = buildPit([{ partitionName: 'X' }]);
    data.set(new TextEncoder().encode('A'.repeat(32)), 28 + 36);
    expect(parsePit(data).entries[0]?.partitionName).toBe('A'.repeat(32));
  });

  it('refuses what isn\'t a whole PIT', () => {
    const good = syntheticPit().pit;
    expect(() => parsePit(good.subarray(0, 20))).toThrow(PitError);
    const badMagic = good.slice();
    badMagic[0] = 0;
    expect(() => parsePit(badMagic)).toThrow(/magic/);
    expect(() => parsePit(good.subarray(0, good.length - 1))).toThrow(/has 1215 bytes/);
    const huge = good.slice();
    new DataView(huge.buffer).setUint32(4, 100000, true);
    expect(() => parsePit(huge)).toThrow(/100000 entries/);
  });
});

describe('partition sizes', () => {
  it('uses 512-byte blocks on eMMC and 4096 on UFS, as Heimdall\'s size check does', () => {
    const base = parsePit(syntheticPit().pit).find('BOOT')!;
    expect(blockSize(base)).toBe(512);
    expect(partitionBytes(base)).toBe(4096 * 512);
    expect(blockSize({ ...base, deviceType: DeviceType.Ufs })).toBe(4096);
  });

  it('gives no size where the PIT doesn\'t say one', () => {
    const base = parsePit(syntheticPit().pit).find('BOOT')!;
    expect(partitionBytes({ ...base, blockCount: 0 })).toBeUndefined();
    expect(partitionBytes({ ...base, deviceType: DeviceType.File })).toBeUndefined();
    expect(partitionBytes({ ...base, deviceType: DeviceType.OneNand })).toBeUndefined();
  });
});
