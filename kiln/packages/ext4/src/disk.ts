// A partition image as blocks, kept somewhere that holds a gigabyte (OPFS in the browser, a file or
// memory in the tests). It remembers which blocks hold data ("care") and which are known zeros, so
// writing it back out as a sparse image needs no second read to decide what to send: everything else
// is "don't care", left as it is on the device.

/** Where the bytes live. FileSystemSyncAccessHandle fits it as it is (`syncHandleStore`). */
export interface Store {
  read(offset: number, length: number): Promise<Uint8Array>;
  write(offset: number, data: Uint8Array): Promise<void>;
}

/** A store in memory, for the tests and small images. Unwritten bytes read as zero. */
export class MemoryStore implements Store {
  private pages = new Map<number, Uint8Array>();
  private static readonly PAGE = 1 << 16;
  async read(offset: number, length: number): Promise<Uint8Array> {
    const out = new Uint8Array(length);
    for (let o = 0; o < length;) {
      const at = offset + o, p = Math.floor(at / MemoryStore.PAGE), off = at % MemoryStore.PAGE;
      const n = Math.min(length - o, MemoryStore.PAGE - off);
      const page = this.pages.get(p);
      if (page) out.set(page.subarray(off, off + n), o);
      o += n;
    }
    return out;
  }
  async write(offset: number, data: Uint8Array): Promise<void> {
    for (let o = 0; o < data.length;) {
      const at = offset + o, p = Math.floor(at / MemoryStore.PAGE), off = at % MemoryStore.PAGE;
      const n = Math.min(data.length - o, MemoryStore.PAGE - off);
      let page = this.pages.get(p);
      if (!page) { page = new Uint8Array(MemoryStore.PAGE); this.pages.set(p, page); }
      page.set(data.subarray(o, o + n), off);
      o += n;
    }
  }
}

/** The parts of FileSystemSyncAccessHandle (OPFS, in a worker) a store needs. */
export interface SyncHandle {
  read(buffer: Uint8Array, options: { at: number }): number;
  write(buffer: Uint8Array, options: { at: number }): number;
}

/** OPFS: a FileSystemSyncAccessHandle, which only a dedicated worker can open. Reads past the end are zeros. */
export function syncHandleStore(h: SyncHandle): Store {
  return {
    async read(offset, length) { const b = new Uint8Array(length); h.read(b, { at: offset }); return b; },
    async write(offset, data) {
      if (h.write(data, { at: offset }) !== data.length) throw new Error(`short write at ${offset}`);
    },
  };
}

/** One bit per block. */
export class Bitset {
  readonly bits: Uint8Array;
  constructor(readonly size: number) { this.bits = new Uint8Array(Math.ceil(size / 8)); }
  get(i: number) { return (this.bits[i >> 3]! >> (i & 7)) & 1; }
  set(i: number, v: boolean | number = true) {
    if (v) this.bits[i >> 3]! |= 1 << (i & 7); else this.bits[i >> 3]! &= ~(1 << (i & 7));
  }
  setRange(from: number, count: number, v: boolean | number = true) { for (let i = from; i < from + count; i++) this.set(i, v); }
}

export class Disk {
  /** Blocks that hold data: read from the source image, or written since. */
  readonly care: Bitset;
  /** Blocks known to be all zeros (the sparse image says FILL 0 for them). A subset of `care`. */
  readonly zero: Bitset;

  /**
   * `zeroed`: the store reads unwritten bytes as zeros (a new OPFS file, a MemoryStore), so zero
   * fills only mark the blocks and don't write gigabytes of zeros.
   */
  constructor(readonly store: Store, readonly blockSize: number, readonly blocks: number, readonly o: { zeroed?: boolean } = {}) {
    this.care = new Bitset(blocks);
    this.zero = new Bitset(blocks);
  }

  private check(block: number, count: number) {
    if (block < 0 || count < 0 || block + count > this.blocks) throw new Error(`blocks ${block}+${count} are outside the image (${this.blocks} blocks)`);
  }

  async read(block: number, count = 1): Promise<Uint8Array> {
    this.check(block, count);
    return this.store.read(block * this.blockSize, count * this.blockSize);
  }

  async write(block: number, data: Uint8Array): Promise<void> {
    if (data.length % this.blockSize) throw new Error(`a write of ${data.length} bytes isn't whole blocks`);
    const count = data.length / this.blockSize;
    this.check(block, count);
    await this.store.write(block * this.blockSize, data);
    this.care.setRange(block, count);
    this.zero.setRange(block, count, false);
  }

  /** Marks blocks as zeros: written as zeros to the store, and sent as FILL 0. */
  async zeroFill(block: number, count: number): Promise<void> {
    this.check(block, count);
    const chunk = new Uint8Array(Math.min(count, 256) * this.blockSize);
    for (let b = block; !this.o.zeroed && b < block + count; b += 256) {
      const n = Math.min(256, block + count - b);
      await this.store.write(b * this.blockSize, n === 256 ? chunk : chunk.subarray(0, n * this.blockSize));
    }
    this.care.setRange(block, count);
    this.zero.setRange(block, count);
  }
}
