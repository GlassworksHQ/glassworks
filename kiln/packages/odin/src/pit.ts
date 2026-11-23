// The PIT (partition information table) a Samsung device sends in Download mode.
//
// The layout is ported from Heimdall's libpit (MIT, Copyright (c) 2010-2017 Benjamin Dobell, Glass Echidna;
// Copyright (c) 2021-2024 Henrik Grimler; see ../LICENSE), libpit/source/libpit.{h,cpp} in Henrik Grimler's fork
// at commit 8f3044db985fd9710038f04886b51240ddbb2834 (PitData::Unpack, PitEntry, the UFS block size in FlashAction.cpp).
//
// Header, 28 bytes: magic 0x12349876, entry count, com_tar2 (8 bytes), cpu_bl_id (8 bytes), LU count (u16), 2 unused.
// Then one 132-byte entry each: binary type, device type, identifier, attributes, update attributes, block size
// or offset, block count, file offset, file size (u32 each), then the partition name, the flash file name and the
// FOTA file name (32 bytes each, NUL-terminated). Little-endian throughout.

export const PIT_MAGIC = 0x12349876;
export const PIT_HEADER_SIZE = 28;
export const PIT_ENTRY_SIZE = 132;
const NAME_SIZE = 32;
/** No real PIT is near this; a count above it means the bytes aren't a PIT. */
const MAX_ENTRIES = 4096;

/** PitEntry's binary types: the application processor, or the modem (communication processor). */
export const BinaryType = { ApplicationProcessor: 0, CommunicationProcessor: 1 } as const;
/** PitEntry's device types: where the partition lives. */
export const DeviceType = { OneNand: 0, File: 1, Mmc: 2, All: 3, Ufs: 8 } as const;

export interface PitEntry {
  /** 0 application processor, 1 modem ({@link BinaryType}). */
  binaryType: number;
  /** 2 eMMC, 8 UFS, … ({@link DeviceType}). */
  deviceType: number;
  /** The partition's identifier; a flash names it as the file identifier. */
  identifier: number;
  attributes: number;
  updateAttributes: number;
  /** The first block on eMMC and UFS. "Different versions of Loke … interpret this differently" (libpit). */
  blockSizeOrOffset: number;
  /** The partition's size in blocks: 512 bytes on eMMC, 4096 on UFS ({@link partitionBytes}). */
  blockCount: number;
  /** Obsolete (libpit). */
  fileOffset: number;
  /** Obsolete (libpit). */
  fileSize: number;
  /** E.g. `RECOVERY`. Empty for an entry that can't be flashed. */
  partitionName: string;
  /** The file name Samsung's firmware packages use for it, e.g. `recovery.img`. */
  flashFilename: string;
  fotaFilename: string;
}

export interface Pit {
  /** com_tar2, the PIT's firmware tag (8 characters at most). */
  comTar2: string;
  /** cpu_bl_id, the chip's bootloader id (8 characters at most). */
  cpuBlId: string;
  /** The number of logical units (UFS). */
  luCount: number;
  entries: readonly PitEntry[];
  /** The PIT as the device sent it (a copy), e.g. to keep with a backup. */
  bytes: Uint8Array;
  /** The flashable entry with exactly this partition name (PitData::FindEntry), or undefined. */
  find(partitionName: string): PitEntry | undefined;
}

export class PitError extends Error {
  override name = 'PitError';
}

function cString(bytes: Uint8Array): string {
  const end = bytes.indexOf(0);
  return new TextDecoder('latin1').decode(end < 0 ? bytes : bytes.subarray(0, end));
}

/** Parses a PIT. Throws a {@link PitError} when the bytes aren't one, or are cut short. */
export function parsePit(data: Uint8Array): Pit {
  if (data.length < PIT_HEADER_SIZE) throw new PitError(`a PIT is at least ${PIT_HEADER_SIZE} bytes, got ${data.length}`);
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const magic = view.getUint32(0, true);
  if (magic !== PIT_MAGIC) throw new PitError(`not a PIT: magic 0x${magic.toString(16)}, expected 0x${PIT_MAGIC.toString(16)}`);
  const count = view.getUint32(4, true);
  if (count > MAX_ENTRIES) throw new PitError(`not a PIT: ${count} entries`);
  const needed = PIT_HEADER_SIZE + count * PIT_ENTRY_SIZE;
  if (data.length < needed) throw new PitError(`the PIT names ${count} entries (${needed} bytes) but has ${data.length} bytes`);

  const entries: PitEntry[] = [];
  for (let i = 0; i < count; i++) {
    const at = PIT_HEADER_SIZE + i * PIT_ENTRY_SIZE;
    const u32 = (n: number) => view.getUint32(at + n * 4, true);
    const name = (n: number) => cString(data.subarray(at + 36 + n * NAME_SIZE, at + 36 + (n + 1) * NAME_SIZE));
    entries.push(Object.freeze({
      binaryType: u32(0), deviceType: u32(1), identifier: u32(2), attributes: u32(3), updateAttributes: u32(4),
      blockSizeOrOffset: u32(5), blockCount: u32(6), fileOffset: u32(7), fileSize: u32(8),
      partitionName: name(0), flashFilename: name(1), fotaFilename: name(2),
    }));
  }
  Object.freeze(entries);
  return Object.freeze({
    comTar2: cString(data.subarray(8, 16)),
    cpuBlId: cString(data.subarray(16, 24)),
    luCount: view.getUint16(24, true),
    entries,
    bytes: data.slice(),
    find: (partitionName: string) => entries.find((e) => e.partitionName !== '' && e.partitionName === partitionName),
  });
}

/** Bytes per block for the entry's device type, or undefined where Heimdall doesn't check sizes (not eMMC or UFS). */
export function blockSize(entry: PitEntry): number | undefined {
  if (entry.deviceType === DeviceType.Mmc) return 512;
  if (entry.deviceType === DeviceType.Ufs) return 4096;
  return undefined;
}

/** The partition's size in bytes, or undefined when the PIT doesn't say (see {@link blockSize}). */
export function partitionBytes(entry: PitEntry): number | undefined {
  const size = blockSize(entry);
  return size === undefined || entry.blockCount === 0 ? undefined : entry.blockCount * size;
}
