// `adb sideload` over WebUSB: serves an OTA package (a LineageOS build, 1–2 GB) to a recovery waiting in
// "Apply update → Apply from ADB", one block at a time, read from a Blob (a File the person picked) so the whole zip
// never sits in memory.
//
// The protocol, as AOSP implements it (read at tag android-15.0.0_r36):
// - Host: packages/modules/adb/client/commandline.cpp, adb_sideload_install(). Opens `sideload-host:<size>:<block
//   size>` with a block size of SIDELOAD_HOST_BLOCK_SIZE = CHUNK_SIZE = 64 KiB (adb.h). Then, until the end: reads 8
//   bytes; "DONEDONE" or "FAILFAIL" (services.h, kMinadbdServicesExitSuccess/Failure) ends it; anything else is a block
//   number in decimal, and the host sends that block (the last one short, not padded). A block past the end is an error.
// - Device: bootable/recovery/minadbd/minadbd_services.cpp (RunAdbFuseSideload, SideloadHostService) and
//   minadbd/fuse_adb_provider.cpp (ReadBlockAlignedData: writes "%08u", then reads exactly the block). The recovery
//   reads the package through a FUSE file, so blocks come in any order and may come again (adb's progress estimate
//   counts on ~2.13× the size for a package read to verify and again to install; LineageOS's wiki says its builds usually read about
//   once). fuse_sideload/fuse_sideload.cpp hashes each block on its first read and refuses a later read that differs:
//   the source must not change during the transfer.
// - In sideload mode minadbd sends DONEDONE whatever the install's result; FAILFAIL only comes in rescue mode. So
//   DONEDONE means the transfer is over, not that the install worked: the device's screen says that.
// - The device may also just close the stream once it has what it needs: LineageOS's wiki
//   (LineageOS/lineage_wiki, _includes/alerts/specific/tip_adb_flash_success.html, read 2026-11-23) says adb can stop
//   at 47% with "adb: failed to read command: Success" and the install still be fine. So a clean close after the
//   device has had blocks ends the call too (ended: 'closed'), and only a close before any block is an error.
// - LineageOS Recovery (LineageOS/android_bootable_recovery, lineage-22.2) has the same minadbd for all of the above.
//
// Not here: the pre-KitKat `sideload:<size>` service adb falls back to when `sideload-host` is refused. Today's minadbd
// exits on it with an error that asks for a newer adb, and only Android 4.3 and older recoveries speak it. Nor rescue
// mode (`rescue-install`).
import type { ReadableStream, WritableStream } from '@yume-chan/stream-extra';

/** The block size this host asks for, as adb does (SIDELOAD_HOST_BLOCK_SIZE, 64 KiB). */
export const SIDELOAD_BLOCK_SIZE = 64 * 1024;

const DONE = 'DONEDONE';
const FAIL = 'FAILFAIL';
const MESSAGE = 8;

/** Without `stallMs`: how long the device may go without asking for a block before the sideload counts as stalled. */
const STALL_MS = 10 * 60 * 1000;

/** How long the stream's close may take at the end before the call settles anyway. */
const CLOSE_MS = 1000;

/** Bytes by offset, for a package that isn't a Blob (an HTTP range reader, say). */
export interface SideloadReader {
  readonly size: number;
  /** Exactly `length` bytes from `offset`. Called once per block the device asks for, never past `size`. */
  read(offset: number, length: number): Promise<Uint8Array>;
}

export type SideloadSource = Blob | SideloadReader;

export interface SideloadProgress {
  /** The block the device just got. */
  block: number;
  /** Bytes sent so far, repeats included: the device may read a block more than once. */
  sent: number;
  /** The package's size. */
  size: number;
}

export interface SideloadOptions {
  onProgress?: (p: SideloadProgress) => void;
  /** Stops the sideload: the stream is closed (the device stops the install) and the call rejects with the signal's reason. */
  signal?: AbortSignal;
  /**
   * How long the device may go without asking for a block (or taking one) before the call gives up with `stalled`.
   * Default 10 minutes. A prompt on the device's screen (an unsigned build's "Install anyway?") stops its requests
   * until someone answers it. `Infinity` waits for ever.
   */
  stallMs?: number;
}

export interface SideloadResult {
  /**
   * How the transfer ended: `done`, the device said so (DONEDONE); `closed`, the device closed the stream after it had
   * blocks, which a LineageOS install that worked can do too. Neither says the install worked: the device's screen does.
   */
  ended: 'done' | 'closed';
  /** Bytes sent in all, repeats included. */
  sent: number;
}

export type SideloadErrorCode =
  | 'not-in-sideload-mode' // the device refused the sideload service
  | 'closed' // the device closed the stream before it asked for a block, or while it waited for one; the connection went
  | 'out-of-range' // the device asked for a block past the end of the package
  | 'bad-request' // the device sent something that is neither a block number nor the end
  | 'stalled' // no request for stallMs
  | 'failed' // the device ended with FAILFAIL (rescue mode only)
  | 'source'; // the package couldn't be read (a file changed or removed on disk, a short read)

export class SideloadError extends Error {
  constructor(readonly code: SideloadErrorCode, message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'SideloadError';
  }
}

/** An adb stream, as Tango's AdbSocket. */
export interface SideloadSocket {
  readonly readable: ReadableStream<Uint8Array>;
  readonly writable: WritableStream<Uint8Array>;
  close(): unknown;
}

/** What sideload needs from a device. Panel provides it; the tests use a fake device. */
export interface SideloadTransport {
  /** The device's mode from its adb banner ("sideload" when it waits for one), when known. */
  readonly mode?: string | undefined;
  /** Opens an adb stream to `service`; rejects when the device refuses it. */
  open(service: string): Promise<SideloadSocket>;
}

function sourceReader(source: SideloadSource): SideloadReader {
  if (typeof Blob !== 'undefined' && source instanceof Blob) {
    return { size: source.size, read: async (o, n) => new Uint8Array(await source.slice(o, o + n).arrayBuffer()) };
  }
  return source as SideloadReader;
}

/**
 * Sends `source` to a device waiting in sideload mode (LineageOS Recovery's "Apply update → Apply from ADB"), the
 * blocks it asks for, in the order it asks, until it says it's done. Resolves when the transfer is over, which in
 * sideload mode isn't proof the install worked (see the top of this file). Rejects with a SideloadError, or the
 * signal's reason when `signal` aborts.
 */
export async function sideload(device: SideloadTransport, source: SideloadSource, options: SideloadOptions = {}): Promise<SideloadResult> {
  const { onProgress, signal } = options;
  const stallMs = options.stallMs ?? STALL_MS;
  const reader = sourceReader(source);
  const size = reader.size;
  if (!Number.isSafeInteger(size) || size <= 0) throw new RangeError(`a sideload needs a package with bytes in it (size ${size})`);
  signal?.throwIfAborted();
  if (device.mode !== undefined && device.mode !== 'sideload') {
    throw new SideloadError('not-in-sideload-mode', `the device is in ${device.mode} mode, not waiting for a sideload: on the device, choose Apply update, then Apply from ADB`);
  }

  // One way out for every wait: the caller's signal, or the stall timer. Each wait races it.
  const stop = new AbortController();
  const onAbort = () => stop.abort(signal!.reason);
  signal?.addEventListener('abort', onAbort, { once: true });
  const stopped = new Promise<never>((_, reject) => {
    stop.signal.addEventListener('abort', () => reject(stop.signal.reason), { once: true });
  });
  stopped.catch(() => {});
  /** `p`, unless the sideload stops first; with `stall`, unless the device takes longer than stallMs. */
  const wait = async <T>(p: Promise<T>, stall: string | undefined): Promise<T> => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    if (stall !== undefined && Number.isFinite(stallMs)) {
      timer = setTimeout(() => stop.abort(new SideloadError('stalled', `${stall} for ${Math.round(stallMs / 1000)} s`)), stallMs);
    }
    try {
      return await Promise.race([p, stopped]);
    } finally {
      clearTimeout(timer);
    }
  };
  /** An error from a closed stream is the stop's reason when the sideload was stopped (closing it caused it). */
  const closed = (what: string, e?: unknown): unknown =>
    stop.signal.aborted ? stop.signal.reason : new SideloadError('closed', `the device closed the sideload ${what}: its screen says why`, { cause: e });

  let socket: SideloadSocket;
  const opening = device.open(`sideload-host:${size}:${SIDELOAD_BLOCK_SIZE}`);
  try {
    socket = await wait(opening, 'the device didn\'t answer the sideload');
  } catch (e) {
    signal?.removeEventListener('abort', onAbort);
    void opening.then((s) => s.close(), () => {}); // a stream that opens after all: let go of it
    if (stop.signal.aborted) throw stop.signal.reason;
    throw new SideloadError('not-in-sideload-mode', 'the device isn\'t waiting for a sideload: on the device, choose Apply update, then Apply from ADB', { cause: e });
  }

  const input = socket.readable.getReader();
  const output = socket.writable.getWriter();
  stop.signal.addEventListener('abort', () => { void Promise.resolve().then(() => socket.close()).catch(() => {}); }, { once: true });
  let buffered = new Uint8Array(0);
  let sent = 0;
  let blocks = 0;

  /** The device's next 8-byte message; undefined when it closed the stream cleanly after it had blocks. */
  const next = async (): Promise<string | undefined> => {
    while (buffered.length < MESSAGE) {
      let r: Awaited<ReturnType<typeof input.read>>;
      try {
        r = await wait(input.read(), blocks ? 'the device stopped asking for blocks' : 'the device didn\'t ask for the first block');
      } catch (e) {
        if (stop.signal.aborted) throw stop.signal.reason;
        throw closed(blocks ? `after ${blocks} block${blocks === 1 ? '' : 's'}` : 'before it asked for a block', e);
      }
      if (r.done) {
        if (blocks) return undefined;
        throw closed(blocks ? `after ${blocks} block${blocks === 1 ? '' : 's'}` : 'before it asked for a block');
      }
      const joined = new Uint8Array(buffered.length + r.value.length);
      joined.set(buffered);
      joined.set(r.value, buffered.length);
      buffered = joined;
    }
    const message = String.fromCharCode(...buffered.subarray(0, MESSAGE));
    buffered = buffered.slice(MESSAGE);
    return message;
  };

  try {
    for (;;) {
      const message = await next();
      if (message === undefined) return { ended: 'closed', sent };
      if (message === DONE) return { ended: 'done', sent };
      if (message === FAIL) throw new SideloadError('failed', 'the device says the install failed: its screen says why');
      if (!/^\d{8}$/.test(message)) throw new SideloadError('bad-request', `the device asked for something that isn't a block: ${JSON.stringify(message)}`);
      const block = Number(message);
      const offset = block * SIDELOAD_BLOCK_SIZE;
      if (offset >= size) throw new SideloadError('out-of-range', `the device asked for block ${block}, past the end of the package (${size} bytes)`);
      const length = Math.min(SIDELOAD_BLOCK_SIZE, size - offset);
      let data: Uint8Array;
      try {
        data = await wait(reader.read(offset, length), undefined);
      } catch (e) {
        if (stop.signal.aborted) throw stop.signal.reason;
        throw new SideloadError('source', `couldn't read block ${block} of the package: was the file changed or moved?`, { cause: e });
      }
      if (data.length !== length) throw new SideloadError('source', `the package gave ${data.length} bytes for block ${block}, not ${length}`);
      try {
        await wait(output.write(data), 'the device didn\'t take a block');
      } catch (e) {
        if (stop.signal.aborted) throw stop.signal.reason;
        throw closed(`while it was getting block ${block}`, e);
      }
      sent += length;
      blocks++;
      onProgress?.({ block, sent, size });
    }
  } finally {
    signal?.removeEventListener('abort', onAbort);
    // adb closes its end after the end message too; minadbd waits up to 3 s for that before it exits. A close that
    // never settles holds no one for more than CLOSE_MS.
    const closing = Promise.resolve().then(() => socket.close()).catch(() => {});
    await Promise.race([closing, new Promise((r) => setTimeout(r, CLOSE_MS))]);
    try { input.releaseLock(); } catch { /* a read still pending on a stream that won't close */ }
    try { output.releaseLock(); } catch { /* likewise */ }
  }
}
