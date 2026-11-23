// Which computer the Mirror lets in over adb. The Glass Panes image's services.jar authorizes one key
// with no dialog: UsbDebuggingManager compares each new computer's key fingerprint with a constant
// (MIRROR_OWNER_ADB_FINGERPRINT), and on a match writes the key and answers "OK". Since image 3 it also
// lets in any key listed in /data/misc/adb/adb_keys (isListedInUserKeyFile; read 2026-11-22), which is
// how Kiln's fresh userdata gets this browser in. adbd itself ignores that file (verified 2026-11-16).
// The constant is still there in image 3, now a key Glass Panes never published, so every unit on the
// image as released lets that computer in. Kiln puts the fingerprint of the installer's own key in its
// place, so a Mirror Kiln installs, Glassworks or Glass Panes (the `system-key` step), lets in only the
// browser that installed it.
// Findings: frames/lululemon-mirror/research/image-patch.md#who-the-image-lets-in-over-adb.
import { zipEntry } from '@glassworks/app-adb';

/**
 * The Glass Panes project key, git-nimrod/glass-panes `mirror-app/android/project-key/adbkey.pub`
 * (commit 8ea31141, 2026-11-04), published there with its private half on purpose. Images 1 and 2 had
 * it as their services.jar constant, and image 3's userdata.sparse lists it in adb_keys. Kiln never
 * lets a Mirror trust it.
 */
export const GLASS_PANES_KEY_FINGERPRINT = 'D1:13:60:89:B6:16:00:75:63:92:56:96:F5:46:42:4A';

/**
 * The fingerprint the pinned image (mirror-android-3) hardcodes in services.jar, read from its
 * UsbDebuggingManager on 2026-11-22: a key Glass Panes didn't publish. Kiln looks for exactly this
 * value in services.jar and puts this browser's in its place.
 */
export const IMAGE_OWNER_FINGERPRINT = 'A1:EC:24:9E:F0:8B:80:55:C5:A7:0A:89:A4:F0:D5:8B';

export const SERVICES_JAR = '/framework/services.jar';

/** sizeof(RSAPublicKey): the only key size Android 6's adbd reads from a key line (adb_auth_client.cpp). */
const ADB_KEY_BYTES = 524;

const done = ' Nothing was changed.';

const S = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
const K = Array.from({ length: 64 }, (_, i) => Math.floor(Math.abs(Math.sin(i + 1)) * 2 ** 32) >>> 0);

/** MD5 (RFC 1321). WebCrypto has none, and getFingerprints uses it. */
export function md5(msg: Uint8Array): Uint8Array {
  const buf = new Uint8Array(((msg.length + 72) >>> 6) << 6);
  buf.set(msg);
  buf[msg.length] = 0x80;
  const dv = new DataView(buf.buffer);
  dv.setUint32(buf.length - 8, (msg.length * 8) >>> 0, true);
  dv.setUint32(buf.length - 4, Math.floor(msg.length / 0x20000000), true);
  const h = [0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476];
  for (let at = 0; at < buf.length; at += 64) {
    let [a, b, c, d] = h as [number, number, number, number];
    for (let i = 0; i < 64; i++) {
      const [f, g] = i < 16 ? [(b & c) | (~b & d), i] : i < 32 ? [(d & b) | (~d & c), (5 * i + 1) % 16]
        : i < 48 ? [b ^ c ^ d, (3 * i + 5) % 16] : [c ^ (b | ~d), (7 * i) % 16];
      const x = (a + f + K[i]! + dv.getUint32(at + 4 * g, true)) | 0;
      const s = S[(i >> 4) * 4 + (i % 4)]!;
      [a, d, c, b] = [d, c, b, (b + ((x << s) | (x >>> (32 - s)))) | 0];
    }
    h[0] = (h[0]! + a) | 0; h[1] = (h[1]! + b) | 0; h[2] = (h[2]! + c) | 0; h[3] = (h[3]! + d) | 0;
  }
  const out = new Uint8Array(16);
  const ov = new DataView(out.buffer);
  h.forEach((w, i) => ov.setUint32(i * 4, w, true));
  return out;
}

/**
 * A key's fingerprint as Android 6's UsbDebuggingManager.getFingerprints makes it: the MD5 of the
 * base64-decoded key (the line's first word), as uppercase hex pairs joined by colons.
 */
export function adbFingerprint(line: string): string {
  let raw: string;
  try { raw = atob(line.trim().split(/\s+/)[0] ?? ''); } catch { raw = ''; }
  if (raw.length !== ADB_KEY_BYTES) throw new Error(`that isn't an adb public key (Android 6's adb takes a ${ADB_KEY_BYTES}-byte RSA key in base64).${done}`);
  return [...md5(Uint8Array.from(raw, (c) => c.charCodeAt(0)))].map((b) => b.toString(16).toUpperCase().padStart(2, '0')).join(':');
}

/** One adb_keys line: the key in base64, then any comment of printable ASCII, spaces included (adb writes user@host). */
export const ADB_KEY_LINE = /^[A-Za-z0-9+/=]+( [\x20-\x7e]*)?$/;

/**
 * The adb_keys lines in `text`, each checked as an adb key, in order: the first is the one the image
 * lets in. Throws, naming the line, for any line that isn't one, or that is a key whose private half is
 * published (`refused`: the Glass Panes project key), so a caller can stop before anything is written or
 * restarted. Every line counts: image 3's framework lets in each key adb_keys lists.
 */
export function adbKeyLines(text: string, refused: readonly string[] = [GLASS_PANES_KEY_FINGERPRINT]): string[] {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  if (!lines.length) throw new Error(`no adb key given.${done}`);
  lines.forEach((l, i) => {
    try {
      if (!ADB_KEY_LINE.test(l)) throw new Error(`that isn't an adb public key line (base64, then a comment in printable ASCII).${done}`);
      if (refused.includes(adbFingerprint(l))) throw new Error(`that adb key is the Glass Panes project key, published with its private half: anyone could use it. Use this computer's own key.${done}`);
    } catch (e) { throw new Error(`adb key ${i + 1} of ${lines.length}: ${(e as Error).message}`); }
  });
  return lines;
}

// ---- dex (format 035, the only one Android 6 reads) ----

/** How a string index is stored where the dex refers to it. `key`: the place is sorted by it, so the moved string mustn't be there. */
type Enc = 'u2' | 'u4' | 'uleb' | 'ulebp1' | 'value';
type Visit = (at: number, enc: Enc, len: number, value: number, key: boolean) => void;

const td = (b: Uint8Array) => new TextDecoder().decode(b);

function uleb(b: Uint8Array, at: number): [number, number] {
  let v = 0, shift = 0, i = at;
  for (;;) {
    const x = b[i++];
    if (x === undefined || shift > 28) throw new Error('a broken uleb128');
    v += (x & 0x7f) * 2 ** shift;
    if (x < 0x80) return [v, i];
    shift += 7;
  }
}
function sleb(b: Uint8Array, at: number): [number, number] {
  let v = 0, shift = 0, i = at, x: number | undefined;
  do {
    x = b[i++];
    if (x === undefined || shift > 28) throw new Error('a broken sleb128');
    v |= (x & 0x7f) << shift;
    shift += 7;
  } while (x & 0x80);
  if (shift < 32 && x & 0x40) v |= -1 << shift;
  return [v, i];
}
const ulebLen = (v: number) => (v < 2 ** 7 ? 1 : v < 2 ** 14 ? 2 : v < 2 ** 21 ? 3 : v < 2 ** 28 ? 4 : 5);
const valueLen = (v: number) => (v < 2 ** 8 ? 1 : v < 2 ** 16 ? 2 : v < 2 ** 24 ? 3 : 4);

/** Dalvik instruction widths in 16-bit units by opcode; 0 is an opcode dex 035 doesn't have. */
const WIDTH = (() => {
  const w = new Uint8Array(256);
  const set = (n: number, ...ops: (number | [number, number])[]) => { for (const o of ops) { const [a, z] = Array.isArray(o) ? o : [o, o]; for (let i = a; i <= z; i++) w[i] = n; } };
  set(1, 0x00, 0x01, 0x04, 0x07, [0x0a, 0x12], 0x1d, 0x1e, 0x21, 0x27, 0x28, [0x7b, 0x8f], [0xb0, 0xcf]);
  set(2, 0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23, 0x29, [0x2d, 0x3d], [0x44, 0x6d], [0x90, 0xaf], [0xd0, 0xe2]);
  set(3, 0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, [0x24, 0x26], 0x2a, 0x2b, 0x2c, [0x6e, 0x72], [0x74, 0x78]);
  set(5, 0x18);
  return w;
})();

/** Every place a dex 035 refers to a string by index, read from its map list. */
function stringRefs(b: Uint8Array, visit: Visit): void {
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  const u2 = (at: number) => dv.getUint16(at, true), u4 = (at: number) => dv.getUint32(at, true);
  const ulebRef = (at: number, key: boolean, p1 = false): number => {
    const [v, next] = uleb(b, at);
    if (!p1) visit(at, 'uleb', next - at, v, key);
    else if (v !== 0) visit(at, 'ulebp1', next - at, v - 1, key); // 0 is NO_INDEX
    return next;
  };
  const skip = (at: number) => uleb(b, at)[1];

  const value = (at: number): number => {
    const type = b[at]! & 0x1f, arg = b[at]! >> 5;
    if (type === 0x17) { visit(at + 1, 'value', arg + 1, Array.from({ length: arg + 1 }, (_, i) => b[at + 1 + i]! * 2 ** (8 * i)).reduce((x, y) => x + y), false); return at + 2 + arg; }
    if (type === 0x1c) return array(at + 1);
    if (type === 0x1d) return annotation(at + 1);
    if (type === 0x1e || type === 0x1f) return at + 1;
    if ([0x00, 0x02, 0x03, 0x04, 0x06, 0x10, 0x11, 0x15, 0x16, 0x18, 0x19, 0x1a, 0x1b].includes(type)) return at + 2 + arg;
    throw new Error(`an encoded value of type 0x${type.toString(16)}`);
  };
  const array = (at: number): number => { let [n, i] = uleb(b, at); while (n--) i = value(i); return i; };
  const annotation = (at: number): number => {
    let i = skip(at); // type_idx
    let n: number;
    [n, i] = uleb(b, i);
    while (n--) i = value(ulebRef(i, true));
    return i;
  };

  const code = (at: number): number => {
    const tries = u2(at + 6), size = u4(at + 12), insns = at + 16;
    for (let pc = 0; pc < size;) {
      const unit = u2(insns + pc * 2), op = unit & 0xff;
      let w: number = WIDTH[op]!;
      if (op === 0x00 && unit !== 0) {
        const n = u2(insns + pc * 2 + 2);
        w = unit === 0x0100 ? 4 + n * 2 : unit === 0x0200 ? 2 + n * 4 : unit === 0x0300 ? 4 + Math.ceil((n * u4(insns + pc * 2 + 4)) / 2) : 0;
      }
      if (!w) throw new Error(`the opcode 0x${unit.toString(16)}`);
      if (op === 0x1a) visit(insns + pc * 2 + 2, 'u2', 2, u2(insns + pc * 2 + 2), false);
      if (op === 0x1b) visit(insns + pc * 2 + 2, 'u4', 4, u4(insns + pc * 2 + 2), false);
      pc += w;
    }
    let i = insns + size * 2;
    if (!tries) return i;
    i += (size % 2) * 2 + tries * 8;
    let [lists, j] = uleb(b, i);
    while (lists--) {
      let [n, k] = sleb(b, j);
      for (let c = 0; c < Math.abs(n); c++) k = skip(skip(k));
      if (n <= 0) k = skip(k);
      j = k;
    }
    return j;
  };

  const debug = (at: number): number => {
    let i = skip(at), n: number;
    [n, i] = uleb(b, i);
    while (n--) i = ulebRef(i, false, true);
    for (;;) {
      const op = b[i++];
      if (op === 0x00) return i;
      if (op === 0x01 || op === 0x02 || op === 0x05 || op === 0x06) i = skip(i);
      else if (op === 0x03) i = skip(ulebRef(skip(i), false, true));
      else if (op === 0x04) i = ulebRef(skip(ulebRef(skip(i), false, true)), false, true);
      else if (op === 0x09) i = ulebRef(i, false, true);
      else if (op === undefined) throw new Error('debug info runs past the end');
    }
  };

  const ids = (off: number, n: number, stride: number, field: number) => { for (let k = 0; k < n; k++) visit(off + k * stride + field, 'u4', 4, u4(off + k * stride + field), true); };
  const seq = (off: number, n: number, align: number, item: (at: number) => number) => { for (let k = 0, at = off; k < n; k++) { at = (at + align - 1) & ~(align - 1); at = item(at); } };

  const map = u4(0x34), items = u4(map);
  for (let k = 0; k < items; k++) {
    const e = map + 4 + k * 12, type = u2(e), n = u4(e + 4), off = u4(e + 8);
    if (type === 0x0002) ids(off, n, 4, 0);
    else if (type === 0x0003) ids(off, n, 12, 0);
    else if (type === 0x0004 || type === 0x0005) ids(off, n, 8, 4);
    else if (type === 0x0006) { for (let c = 0; c < n; c++) { const v = u4(off + c * 32 + 16); if (v !== 0xffffffff) visit(off + c * 32 + 16, 'u4', 4, v, false); } }
    else if (type === 0x2001) seq(off, n, 4, code);
    else if (type === 0x2003) seq(off, n, 1, debug);
    else if (type === 0x2004) seq(off, n, 1, (at) => annotation(at + 1));
    else if (type === 0x2005) seq(off, n, 1, array);
    else if (![0x0000, 0x0001, 0x1000, 0x1001, 0x1002, 0x1003, 0x2000, 0x2002, 0x2006].includes(type)) throw new Error(`a dex section of type 0x${type.toString(16)}`);
  }
}

/** The dex's strings, decoded from MUTF-8 to UTF-16, as Java and ART's sort compare them. */
function dexStrings(b: Uint8Array): { text: string[]; data: number[] } {
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  const n = dv.getUint32(0x38, true), off = dv.getUint32(0x3c, true);
  const text: string[] = [], data: number[] = [];
  for (let k = 0; k < n; k++) {
    let [units, i] = uleb(b, dv.getUint32(off + k * 4, true));
    data.push(i);
    const cs: number[] = [];
    while (units--) {
      const x = b[i]!;
      if (x < 0x80) { cs.push(x); i += 1; } else if ((x & 0xe0) === 0xc0) { cs.push(((x & 0x1f) << 6) | (b[i + 1]! & 0x3f)); i += 2; } else if ((x & 0xf0) === 0xe0) { cs.push(((x & 0x0f) << 12) | ((b[i + 1]! & 0x3f) << 6) | (b[i + 2]! & 0x3f)); i += 3; } else throw new Error('a string that isn\'t MUTF-8');
    }
    let s = '';
    for (let c = 0; c < cs.length; c += 0x2000) s += String.fromCharCode(...cs.slice(c, c + 0x2000));
    text.push(s);
  }
  return { text, data };
}

function count(hay: Uint8Array, needle: Uint8Array): number {
  let n = 0;
  for (let i = hay.indexOf(needle[0]!); i >= 0 && i <= hay.length - needle.length; i = hay.indexOf(needle[0]!, i + 1)) {
    let k = 1;
    while (k < needle.length && hay[i + k] === needle[k]) k++;
    if (k === needle.length) n++;
  }
  return n;
}

function adler32(b: Uint8Array): number {
  let a = 1, s = 0;
  for (let i = 0; i < b.length;) {
    for (const end = Math.min(b.length, i + 3800); i < end; i++) { a += b[i]!; s += a; }
    a %= 65521; s %= 65521;
  }
  return ((s << 16) | a) >>> 0;
}

async function sha1(b: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-1', b as Uint8Array<ArrayBuffer>));
}

async function checkHeader(b: Uint8Array): Promise<void> {
  if (td(b.subarray(0, 8)) !== 'dex\n035\0') throw new Error(`services.jar's classes.dex isn't dex 035, the format Android 6 reads.${done}`);
  if (b.length < 0x70) throw new Error(`services.jar's classes.dex is cut short.${done}`);
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  if (dv.getUint32(0x20, true) !== b.length || dv.getUint32(0x28, true) !== 0x12345678) throw new Error(`services.jar's classes.dex is cut short or not little-endian.${done}`);
  if (dv.getUint32(8, true) !== adler32(b.subarray(12)) || !(await sha1(b.subarray(32))).every((x, i) => x === b[12 + i])) throw new Error(`services.jar's classes.dex fails its own checksum.${done}`);
}

/**
 * Puts `to` where the string `from` is, in a dex. The string table must stay sorted (ART refuses a
 * dex with "Out-of-order string_ids"), so the string moves to its sorted place, and every reference to
 * a string index (ids, const-string, debug info, static values, annotations) is renumbered in place.
 * Nothing changes size: a reference whose new index needs another encoding length is refused. Then the
 * SHA-1 signature and the Adler-32 checksum are recomputed, and the result is read back: every
 * reference must name the same text as before, but `from` → `to`, and the table must be sorted.
 * `from` and `to`: ASCII of the same length. Throws, with the input untouched, otherwise.
 */
export async function replaceDexString(dex: Uint8Array, from: string, to: string): Promise<Uint8Array> {
  if (from.length !== to.length || /[^\x20-\x7e]/.test(from + to)) throw new Error('replaceDexString takes two printable ASCII strings of the same length');
  await checkHeader(dex);
  const raw = new TextEncoder().encode(from);
  const seen = count(dex, raw);
  if (seen !== 1) throw new Error(`services.jar's classes.dex holds the image's adb key fingerprint ${seen === 0 ? 'nowhere' : `${seen} times`}: Kiln knows the image with it exactly once.${done}`);
  const { text, data } = dexStrings(dex);
  const p = text.indexOf(from);
  if (p < 0) throw new Error(`services.jar's classes.dex has the image's adb key fingerprint inside a longer string, not as its own.${done}`);
  if (text.includes(to)) throw new Error(`services.jar's classes.dex has the new fingerprint already as another string.${done}`);
  // k: where `to` goes among the others; every other index keeps its order, shifted by one between p and k
  const k = text.reduce((n, s, i) => n + (i !== p && s < to ? 1 : 0), 0);
  const index = (i: number) => { if (i === p) return k; const j = i < p ? i : i - 1; return j < k ? j : j + 1; };

  const out = dex.slice();
  const ov = new DataView(out.buffer);
  const why = (e: unknown) => new Error(`Kiln can't change services.jar's classes.dex: ${(e as Error).message}.${done}`);
  try {
    stringRefs(dex, (at, enc, len, v, key) => {
      if (v >= text.length) throw new Error(`a reference to string ${v} of ${text.length}`);
      if (key && v === p) throw new Error('the fingerprint is also a name in it');
      const nv = index(v);
      if (enc === 'u2') { if (nv > 0xffff) throw new Error('a const-string would need const-string/jumbo'); ov.setUint16(at, nv, true); }
      else if (enc === 'u4') ov.setUint32(at, nv, true);
      else if (enc === 'value') { if (valueLen(nv) !== valueLen(v)) throw new Error('a static value would change size'); for (let i = 0; i < len; i++) out[at + i] = Math.floor(nv / 2 ** (8 * i)) & 0xff; }
      else {
        const [a, c] = enc === 'ulebp1' ? [v + 1, nv + 1] : [v, nv];
        if (ulebLen(a) !== ulebLen(c)) throw new Error('a uleb128 index would change size');
        for (let i = 0; i < len; i++) out[at + i] = (Math.floor(c / 2 ** (7 * i)) & 0x7f) | (i < len - 1 ? 0x80 : 0);
      }
    });
  } catch (e) { throw why(e); }
  const ids = ov.getUint32(0x3c, true), dv = new DataView(dex.buffer, dex.byteOffset, dex.byteLength);
  for (let i = 0; i < text.length; i++) ov.setUint32(ids + index(i) * 4, dv.getUint32(ids + i * 4, true), true);
  out.set(new TextEncoder().encode(to), data[p]!);
  out.set(await sha1(out.subarray(32)), 12);
  ov.setUint32(8, adler32(out.subarray(12)), true);

  // read it back: the same text at every reference, and a sorted table
  const after = dexStrings(out).text;
  for (let i = 1; i < after.length; i++) if (!(after[i - 1]! < after[i]!)) throw why(new Error('its strings would be out of order'));
  const before: string[] = [];
  stringRefs(dex, (_, __, ___, v) => { before.push(v === p ? to : text[v]!); });
  let n = 0;
  try {
    stringRefs(out, (_, __, ___, v) => { if (after[v] !== before[n++]) throw new Error('a reference that names another string after the change'); });
  } catch (e) { throw why(e); }
  if (n !== before.length) throw why(new Error('the references changed'));
  await checkHeader(out);
  return out;
}

// ---- the jar ----

function crc32(b: Uint8Array): number {
  let c = ~0;
  for (let i = 0; i < b.length; i++) { c ^= b[i]!; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; }
  return ~c >>> 0;
}

async function deflateRaw(b: Uint8Array): Promise<Uint8Array> {
  const s = new Blob([b as Uint8Array<ArrayBuffer>]).stream().pipeThrough(new CompressionStream('deflate-raw'));
  return new Uint8Array(await new Response(s).arrayBuffer());
}

interface Entry { name: string; method: number; flags: number; crc: number; csize: number; local: number; cd: number; cdLen: number }

function entries(jar: Uint8Array): { list: Entry[]; eocd: number } {
  const dv = new DataView(jar.buffer, jar.byteOffset, jar.byteLength);
  let eocd = -1;
  for (let i = jar.length - 22; i >= Math.max(0, jar.length - 22 - 0xffff); i--) if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) throw new Error(`services.jar isn't a zip.${done}`);
  const list: Entry[] = [];
  for (let n = dv.getUint16(eocd + 10, true), at = dv.getUint32(eocd + 16, true); n--;) {
    if (dv.getUint32(at, true) !== 0x02014b50) throw new Error(`services.jar's zip directory is broken.${done}`);
    const nlen = dv.getUint16(at + 28, true), cdLen = 46 + nlen + dv.getUint16(at + 30, true) + dv.getUint16(at + 32, true);
    list.push({ name: td(jar.subarray(at + 46, at + 46 + nlen)), method: dv.getUint16(at + 10, true), flags: dv.getUint16(at + 8, true), crc: dv.getUint32(at + 16, true), csize: dv.getUint32(at + 20, true), local: dv.getUint32(at + 42, true), cd: at, cdLen });
    at += cdLen;
  }
  return { list, eocd };
}

/** The same zip with one entry's data replaced (stored or deflated as the original was), every other byte as it was. */
async function replaceEntry(jar: Uint8Array, name: string, data: Uint8Array): Promise<Uint8Array> {
  const { list, eocd } = entries(jar);
  const dv = new DataView(jar.buffer, jar.byteOffset, jar.byteLength);
  const parts: Uint8Array[] = [], central: Uint8Array[] = [];
  let pos = 0;
  for (const e of list) {
    const head = 30 + dv.getUint16(e.local + 26, true) + dv.getUint16(e.local + 28, true);
    const local = jar.slice(e.local, e.local + head), cd = jar.slice(e.cd, e.cd + e.cdLen);
    let body = jar.subarray(e.local + head, e.local + head + e.csize);
    if (e.name === name) {
      body = e.method === 8 ? await deflateRaw(data) : data;
      const crc = crc32(data);
      for (const [b, at] of [[local, 14], [cd, 16]] as const) { const v = new DataView(b.buffer); v.setUint32(at, crc, true); v.setUint32(at + 4, body.length, true); v.setUint32(at + 8, data.length, true); }
    }
    new DataView(cd.buffer).setUint32(42, pos, true);
    parts.push(local, body);
    central.push(cd);
    pos += local.length + body.length;
  }
  const end = jar.slice(eocd);
  const cdSize = central.reduce((n, c) => n + c.length, 0);
  new DataView(end.buffer).setUint32(12, cdSize, true);
  new DataView(end.buffer).setUint32(16, pos, true);
  const out = new Uint8Array(pos + cdSize + end.length);
  let at = 0;
  for (const b of [...parts, ...central, end]) { out.set(b, at); at += b.length; }
  return out;
}

/**
 * services.jar that trusts only `adbKey` (an adb_keys line) in place of the image's own constant,
 * or undefined if it trusts that key already (a second run). Throws, before anything is written,
 * for the project key itself, a jar Kiln doesn't know, or a dex it can't change.
 */
export async function trustAdbKey(jar: Uint8Array, adbKey: string): Promise<Uint8Array | undefined> {
  return trustFingerprint(jar, adbFingerprint(adbKey));
}

/** trustAdbKey by the key's fingerprint (`XX:XX:…`, as adbFingerprint makes it). */
export async function trustFingerprint(jar: Uint8Array, to: string): Promise<Uint8Array | undefined> {
  if (!/^([0-9A-F]{2}:){15}[0-9A-F]{2}$/.test(to)) throw new Error(`that isn't an adb key fingerprint.${done}`);
  if (to === GLASS_PANES_KEY_FINGERPRINT) throw new Error(`that adb key is the Glass Panes project key, published with its private half: anyone could use it. Use this computer's own key.${done}`);
  const { list } = entries(jar);
  const dexes = list.filter((e) => /^classes\d*\.dex$/.test(e.name));
  if (dexes.length !== 1 || dexes[0]!.name !== 'classes.dex') throw new Error(`services.jar has ${dexes.map((e) => e.name).join(', ') || 'no dex'}; Kiln knows it with one classes.dex.${done}`);
  const e = dexes[0]!;
  if (e.flags & 0x9 || (e.method !== 0 && e.method !== 8) || e.csize === 0xffffffff) throw new Error(`services.jar stores classes.dex in a way Kiln doesn't rewrite.${done}`);
  const dex = await zipEntry(jar, 'classes.dex');
  if (!dex || crc32(dex) !== e.crc) throw new Error(`services.jar's classes.dex doesn't match its CRC.${done}`);
  await checkHeader(dex);
  const enc = new TextEncoder();
  // a second run: the dex already names this key, as a string of its own, once
  if (count(dex, enc.encode(IMAGE_OWNER_FINGERPRINT)) === 0 && count(dex, enc.encode(to)) === 1) {
    if (!dexStrings(dex).text.includes(to)) throw new Error(`services.jar's classes.dex has this key's fingerprint only inside a longer string.${done}`);
    return undefined;
  }
  const next = await replaceDexString(dex, IMAGE_OWNER_FINGERPRINT, to);
  const out = await replaceEntry(jar, 'classes.dex', next);
  const back = await zipEntry(out, 'classes.dex');
  if (!back || back.length !== next.length || !back.every((x, i) => x === next[i])) throw new Error(`the rewritten services.jar doesn't read back.${done}`);
  return out;
}
