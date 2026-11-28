// The Odin protocol's packets, as Samsung's Download mode speaks it over USB bulk transfers.
//
// Ported from Heimdall (MIT, Copyright (c) 2010-2017 Benjamin Dobell, Glass Echidna; Copyright (c) 2021-2024
// Henrik Grimler; see ../LICENSE and ../README.md "Heimdall"). Read from Henrik Grimler's maintained fork,
// heimdall/source at commit 8f3044db985fd9710038f04886b51240ddbb2834: ControlPacket.h, SessionSetupPacket.h,
// BeginSessionPacket.h, FilePartSizePacket.h, TotalBytesPacket.h, PitFilePacket.h, DumpPartPitFilePacket.h,
// FileTransferPacket.h, FlashPartFileTransferPacket.h, EndFileTransferPacket.h, EndPhoneFileTransferPacket.h,
// EndSessionPacket.h, ResponsePacket.h, ReceiveFilePartPacket.h, and the sizes and timeouts in BridgeManager.cpp.
//
// Every integer is a little-endian u32. Every packet the host sends is 1024 bytes, zero-padded, except the
// handshake and the file parts. Every response is 8 bytes: the response type, then one u32 value.

/** "ODIN", the host's handshake (BridgeManager::InitialiseProtocol). Sent as 4 bytes. */
export const HANDSHAKE = new Uint8Array([0x4f, 0x44, 0x49, 0x4e]);
/** "LOKE", the device's answer: exactly 4 bytes. */
export const HANDSHAKE_REPLY = new Uint8Array([0x4c, 0x4f, 0x4b, 0x45]);

/** ControlPacket: every host packet but the file parts is this long. */
export const CONTROL_PACKET_SIZE = 1024;
/** ResponsePacket: every device response but the PIT's parts is this long. */
export const RESPONSE_SIZE = 8;
/** ReceiveFilePartPacket::kDataSize: the PIT comes back in parts of at most this many bytes. */
export const PIT_PART_SIZE = 500;
/** Heimdall reads "empty" transfers with a length of 1 (WinUSB ignores 0); this side asks for one packet. */
export const EMPTY_READ_SIZE = 512;

/** ControlPacket's types. Responses carry the same number back (ResponsePacket), and 0 for a file part. */
export const Control = {
  Session: 0x64,
  PitFile: 0x65,
  FileTransfer: 0x66,
  EndSession: 0x67,
} as const;
export const RESPONSE_FILE_PART = 0x00;

/** SessionSetupPacket's requests. */
export const SessionRequest = {
  BeginSession: 0,
  DeviceType: 1,
  TotalBytes: 2,
  FilePartSize: 5,
  EnableTFlash: 8,
} as const;

/** PitFilePacket's requests. */
export const PitRequest = { Flash: 0, Dump: 1, Part: 2, EndTransfer: 3 } as const;
/** FileTransferPacket's requests. */
export const FileRequest = { Flash: 0, Dump: 1, Part: 2, End: 3 } as const;
/** EndSessionPacket's requests. */
export const EndRequest = { EndSession: 0, Reboot: 1 } as const;
/** EndFileTransferPacket's destinations: the application processor ("phone") or the modem. */
export const Destination = { Phone: 0, Modem: 1 } as const;

/** The Odin protocol version BeginSessionPacket announces (Grimler's fork; upstream Heimdall sent 0). */
export const PROTOCOL_VERSION = 4;

/**
 * The file transfer's shape. Heimdall starts with the old one and moves to the fast one when the device's
 * begin-session answer is non-zero ("0 means changing the packet size is not supported").
 */
export interface TransferShape {
  /** Bytes in each file part. Every part is sent this long, the last one zero-padded. */
  packetSize: number;
  /** File parts per sequence: each sequence opens with its byte count and ends with an end packet. */
  sequenceLength: number;
  /** How long the device may take to answer a sequence's end packet (it writes the sequence then). */
  sequenceTimeoutMs: number;
}
/** kFileTransferPacketSizeDefault and friends: 128 KiB parts, 800 to a sequence, 30 s. */
export const LEGACY_TRANSFER: TransferShape = { packetSize: 131072, sequenceLength: 800, sequenceTimeoutMs: 30_000 };
/** BridgeManager::BeginSession on a device that takes a part size: 1 MiB parts, 30 to a sequence, 2 minutes. */
export const FAST_TRANSFER: TransferShape = { packetSize: 1048576, sequenceLength: 30, sequenceTimeoutMs: 120_000 };

/** BridgeManager's defaults, in ms. */
export const Timeouts = { send: 3000, receive: 3000, empty: 100, handshake: 1000 } as const;

/** A 1024-byte control packet: the type, then each u32 in `words` from offset 4 on. */
export function controlPacket(type: number, ...words: number[]): Uint8Array {
  const data = new Uint8Array(CONTROL_PACKET_SIZE);
  const view = new DataView(data.buffer);
  view.setUint32(0, type, true);
  words.forEach((w, i) => view.setUint32(4 + i * 4, w >>> 0, true));
  return data;
}

export const beginSession = () => controlPacket(Control.Session, SessionRequest.BeginSession, PROTOCOL_VERSION);
export const deviceType = () => controlPacket(Control.Session, SessionRequest.DeviceType);
export const filePartSize = (bytes: number) => controlPacket(Control.Session, SessionRequest.FilePartSize, bytes);

/** TotalBytesPacket: a u64, low word then high word (Grimler's fork; upstream sent only the low word). */
export function totalBytes(bytes: number): Uint8Array {
  if (!Number.isSafeInteger(bytes) || bytes < 0) throw new RangeError(`total bytes must be a whole number, not ${bytes}`);
  return controlPacket(Control.Session, SessionRequest.TotalBytes, bytes % 2 ** 32, Math.floor(bytes / 2 ** 32));
}

export const pitDump = () => controlPacket(Control.PitFile, PitRequest.Dump);
export const pitPart = (index: number) => controlPacket(Control.PitFile, PitRequest.Part, index);
/** The end of a PIT download: PitFilePacket(kRequestEndTransfer), no size (ReceivePitFile). */
export const pitEnd = () => controlPacket(Control.PitFile, PitRequest.EndTransfer);

export const fileFlash = () => controlPacket(Control.FileTransfer, FileRequest.Flash);
/** FlashPartFileTransferPacket: the bytes the sequence's parts add up to, padding included. */
export const fileSequence = (sequenceBytes: number) => controlPacket(Control.FileTransfer, FileRequest.Part, sequenceBytes);

/** EndPhoneFileTransferPacket: what the sequence really held, and where it goes. */
export function filePhoneEnd(effectiveBytes: number, pitDeviceType: number, fileIdentifier: number, endOfFile: boolean): Uint8Array {
  // destination, sequenceByteCount, unknown1 (0, "EFS?"), deviceType, fileIdentifier, endOfFile
  return controlPacket(Control.FileTransfer, FileRequest.End, Destination.Phone, effectiveBytes, 0, pitDeviceType,
    fileIdentifier, endOfFile ? 1 : 0);
}

export const endSession = () => controlPacket(Control.EndSession, EndRequest.EndSession);
export const reboot = () => controlPacket(Control.EndSession, EndRequest.Reboot);

/** A decoded 8-byte response. */
export interface Response { type: number; value: number }

export function parseResponse(data: Uint8Array): Response {
  if (data.length !== RESPONSE_SIZE) {
    throw new RangeError(`expected an ${RESPONSE_SIZE}-byte response, got ${data.length} bytes`);
  }
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  return { type: view.getUint32(0, true), value: view.getUint32(4, true) };
}

/** A response, as the fake device and tests build it. */
export function response(type: number, value = 0): Uint8Array {
  const data = new Uint8Array(RESPONSE_SIZE);
  const view = new DataView(data.buffer);
  view.setUint32(0, type, true);
  view.setUint32(4, value >>> 0, true);
  return data;
}

/**
 * How BridgeManager::SendFile cuts a file of `size` bytes: how many sequences, the parts in each, and the bytes
 * each sequence's end packet names. Pure, so the tests can check it against Heimdall's arithmetic.
 */
export function plan(size: number, shape: TransferShape): { parts: number; effectiveBytes: number }[] {
  const perSequence = shape.packetSize * shape.sequenceLength;
  const out: { parts: number; effectiveBytes: number }[] = [];
  for (let start = 0; start < size; start += perSequence) {
    const bytes = Math.min(perSequence, size - start);
    out.push({ parts: Math.ceil(bytes / shape.packetSize), effectiveBytes: bytes });
  }
  return out;
}
