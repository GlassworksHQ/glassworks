// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
// Written clean-room from docs/research/kiln-cleanroom/bootpatch-spec.md.
//
// The ramdisk as a cpio "newc" archive (cpio(5), the kernel's early-userspace buffer format):
// a map from name to entry, read leniently and written in one canonical form.

export const S_IFMT = 0o170000, S_IFREG = 0o100000, S_IFDIR = 0o040000;

export interface CpioEntry {
  /** Full st_mode: file type and permissions. */
  mode: number;
  uid: number;
  gid: number;
  rdevMajor: number;
  rdevMinor: number;
  data: Uint8Array;
}

/** Entries by name. Order doesn't matter: they are written sorted. */
export type Ramdisk = Map<string, CpioEntry>;

const enc = new TextEncoder();
const MAGIC = enc.encode('070701');
const TRAILER = 'TRAILER!!!';
const HEADER = 110;

// Plain arithmetic, not `& ~3`: a crafted size must not wrap to a small offset.
const align4 = (x: number) => Math.ceil(x / 4) * 4;
export const isRegular = (e: CpioEntry) => (e.mode & S_IFMT) === S_IFREG;

/** An entry Kiln adds: owned by root, no rdev, `perm & 07777` with the given file type. */
export function entry(type: number, perm: number, data: Uint8Array = new Uint8Array(0)): CpioEntry {
  return { mode: type | (perm & 0o7777), uid: 0, gid: 0, rdevMajor: 0, rdevMinor: 0, data };
}

function magicAt(d: Uint8Array, p: number): boolean {
  return p + MAGIC.length <= d.length && MAGIC.every((b, i) => d[p + i] === b);
}

function nextMagic(d: Uint8Array, from: number): number {
  for (let p = from; p + MAGIC.length <= d.length; p++) if (magicAt(d, p)) return p;
  return -1;
}

export function readCpio(d: Uint8Array): Ramdisk {
  const td = new TextDecoder();
  const out: Ramdisk = new Map();
  const field = (p: number, i: number) => {
    const s = td.decode(d.subarray(p + 6 + i * 8, p + 14 + i * 8));
    if (!/^[0-9a-fA-F]{8}$/.test(s)) throw new Error('Damaged ramdisk: a cpio header has a bad field.');
    return parseInt(s, 16);
  };
  let p = 0;
  while (p < d.length) {
    if (p + HEADER > d.length || !magicAt(d, p)) throw new Error(`Damaged ramdisk: no cpio header at offset ${p}.`);
    const nameSize = field(p, 11), size = field(p, 6);
    const dataAt = align4(p + HEADER + nameSize);
    if (nameSize < 1 || dataAt > d.length || dataAt + size > d.length) throw new Error(`Damaged ramdisk: the cpio entry at offset ${p} runs past the end.`);
    const name = td.decode(d.subarray(p + HEADER, p + HEADER + nameSize - 1));
    const data = d.slice(dataAt, dataAt + size);
    const e: CpioEntry = { mode: field(p, 1), uid: field(p, 2), gid: field(p, 3), rdevMajor: field(p, 9), rdevMinor: field(p, 10), data };
    p = align4(dataAt + size);
    if (name === TRAILER) {
      p = nextMagic(d, p);
      if (p < 0) break;
    } else if (name !== '.' && name !== '..') {
      out.delete(name);
      out.set(name, e);
    }
  }
  return out;
}

function compareBytes(a: Uint8Array, b: Uint8Array): number {
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) if (a[i] !== b[i]) return a[i]! - b[i]!;
  return a.length - b.length;
}

/** Names in the byte order of their UTF-8 encoding. */
export function sortedNames(names: Iterable<string>): string[] {
  return [...names].map((n) => ({ n, b: enc.encode(n) })).sort((x, y) => compareBytes(x.b, y.b)).map((x) => x.n);
}

export function writeCpio(r: Ramdisk): Uint8Array {
  const parts: Uint8Array[] = [];
  let len = 0;
  let ino = 300000;
  const push = (b: Uint8Array) => {
    parts.push(b);
    len += b.length;
    if (len % 4) { const pad = new Uint8Array(4 - (len % 4)); parts.push(pad); len += pad.length; }
  };
  const one = (name: string, e: CpioEntry) => {
    const nameBytes = enc.encode(`${name}\0`);
    const fields = [ino++, e.mode, e.uid, e.gid, 1, 0, e.data.length, 0, 0, e.rdevMajor, e.rdevMinor, nameBytes.length, 0];
    const header = enc.encode(`070701${fields.map((x) => (x >>> 0).toString(16).padStart(8, '0')).join('')}`);
    const head = new Uint8Array(header.length + nameBytes.length);
    head.set(header);
    head.set(nameBytes, header.length);
    push(head);
    if (e.data.length) push(e.data);
  };
  for (const name of sortedNames(r.keys())) one(name, r.get(name)!);
  one(TRAILER, { mode: 0o755, uid: 0, gid: 0, rdevMajor: 0, rdevMinor: 0, data: new Uint8Array(0) });
  const out = new Uint8Array(len);
  let p = 0;
  for (const b of parts) { out.set(b, p); p += b.length; }
  return out;
}
