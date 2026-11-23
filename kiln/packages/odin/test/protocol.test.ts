// Packet layouts against Heimdall's packet classes (Grimler's fork, heimdall/source), and the file transfer's
// arithmetic against BridgeManager::SendFile's, ported line by line below.
import { describe, expect, it } from 'vitest';
import * as P from '../src/protocol.ts';

const words = (data: Uint8Array, n: number) => {
  const v = new DataView(data.buffer, data.byteOffset, data.byteLength);
  return Array.from({ length: n }, (_, i) => v.getUint32(i * 4, true));
};

describe('packets', () => {
  it('are 1024 bytes, zero after their words', () => {
    for (const p of [P.beginSession(), P.deviceType(), P.totalBytes(1), P.filePartSize(1), P.pitDump(), P.pitPart(3),
      P.pitEnd(), P.fileFlash(), P.fileSequence(1), P.filePhoneEnd(1, 2, 3, true), P.endSession(), P.reboot()]) {
      expect(p.length).toBe(1024);
      expect(p.subarray(32).every((b) => b === 0)).toBe(true);
    }
  });

  it('match Heimdall\'s Pack()', () => {
    expect(words(P.beginSession(), 3)).toEqual([0x64, 0, 4]); // BeginSessionPacket, protocol version 4
    expect(words(P.deviceType(), 3)).toEqual([0x64, 1, 0]); // DeviceTypePacket
    expect(words(P.filePartSize(1048576), 3)).toEqual([0x64, 5, 1048576]); // FilePartSizePacket
    expect(words(P.totalBytes(5 * 2 ** 30), 4)).toEqual([0x64, 2, 2 ** 30, 1]); // TotalBytesPacket: u64, low then high
    expect(words(P.pitDump(), 3)).toEqual([0x65, 1, 0]); // PitFilePacket(kRequestDump)
    expect(words(P.pitPart(7), 3)).toEqual([0x65, 2, 7]); // DumpPartPitFilePacket
    expect(words(P.pitEnd(), 3)).toEqual([0x65, 3, 0]); // PitFilePacket(kRequestEndTransfer)
    expect(words(P.fileFlash(), 3)).toEqual([0x66, 0, 0]); // FileTransferPacket(kRequestFlash)
    expect(words(P.fileSequence(3 << 20), 3)).toEqual([0x66, 2, 3 << 20]); // FlashPartFileTransferPacket
    // EndPhoneFileTransferPacket: destination 0, sequence bytes, unknown1 0, device type, file identifier, end of file
    expect(words(P.filePhoneEnd(1234, 2, 6, true), 8)).toEqual([0x66, 3, 0, 1234, 0, 2, 6, 1]);
    expect(words(P.filePhoneEnd(1234, 8, 6, false), 8)).toEqual([0x66, 3, 0, 1234, 0, 8, 6, 0]);
    expect(words(P.endSession(), 3)).toEqual([0x67, 0, 0]); // EndSessionPacket(kRequestEndSession)
    expect(words(P.reboot(), 3)).toEqual([0x67, 1, 0]); // EndSessionPacket(kRequestRebootDevice)
  });

  it('read 8-byte responses and nothing else', () => {
    expect(P.parseResponse(P.response(0x66, 0xffffffff))).toEqual({ type: 0x66, value: 0xffffffff });
    expect(() => P.parseResponse(new Uint8Array(7))).toThrow(/8-byte/);
    expect(() => P.parseResponse(new Uint8Array(9))).toThrow(/8-byte/);
  });

  it('refuse a total that isn\'t a whole number of bytes', () => {
    expect(() => P.totalBytes(-1)).toThrow(RangeError);
    expect(() => P.totalBytes(1.5)).toThrow(RangeError);
  });
});

/** BridgeManager::SendFile's arithmetic, as Heimdall writes it in C (unsigned integer division). */
function heimdall(fileSize: number, s: P.TransferShape) {
  const perSequence = s.sequenceLength * s.packetSize;
  let sequenceCount = Math.floor(fileSize / perSequence);
  let lastSequenceSize = s.sequenceLength;
  const partialPacketByteCount = fileSize % s.packetSize;
  if (fileSize % perSequence !== 0) {
    sequenceCount++;
    const lastSequenceBytes = fileSize % perSequence;
    lastSequenceSize = Math.floor(lastSequenceBytes / s.packetSize);
    if (partialPacketByteCount !== 0) lastSequenceSize++;
  }
  const out: { parts: number; effectiveBytes: number }[] = [];
  for (let i = 0; i < sequenceCount; i++) {
    const isLastSequence = i === sequenceCount - 1;
    const sequenceSize = isLastSequence ? lastSequenceSize : s.sequenceLength;
    const sequenceTotalByteCount = sequenceSize * s.packetSize;
    const effective = isLastSequence && partialPacketByteCount !== 0
      ? s.packetSize * (lastSequenceSize - 1) + partialPacketByteCount : sequenceTotalByteCount;
    out.push({ parts: sequenceSize, effectiveBytes: effective });
  }
  return out;
}

describe('plan', () => {
  const MiB = 1 << 20;
  const sizes = [1, 511, 512, 131071, 131072, 131073, MiB - 1, MiB, MiB + 1, 30 * MiB - 1, 30 * MiB, 30 * MiB + 1,
    64 * MiB + 12345, 100 * MiB, 100 * MiB + 1, 250 * MiB + 7];

  for (const [name, shape] of [['legacy', P.LEGACY_TRANSFER], ['fast', P.FAST_TRANSFER]] as const) {
    it(`cuts a file as Heimdall does (${name}: ${shape.packetSize} bytes, ${shape.sequenceLength} to a sequence)`, () => {
      for (const size of sizes) expect(P.plan(size, shape), `${size} bytes`).toEqual(heimdall(size, shape));
    });
  }

  it('adds up to the file', () => {
    const plan = P.plan(64 * MiB + 12345, P.FAST_TRANSFER);
    expect(plan.map((s) => s.parts)).toEqual([30, 30, 5]);
    expect(plan.reduce((n, s) => n + s.effectiveBytes, 0)).toBe(64 * MiB + 12345);
  });
});
