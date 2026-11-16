// A tiny stand-in APK: a zip with only an AndroidManifest.xml in binary XML, naming `pkg`. Enough for
// apkPackage, the tests and Kiln's demos. Nothing in it would install.

/** `shadow`: an unqualified minSdkVersion before the real one; `minSdkKind`: its value type (0x10, int, by default). */
interface AxmlOptions { minSdk?: number; stripNames?: boolean; shadow?: number; minSdkKind?: number; minSdkRaw?: '23' }

function axml(pkg: string, utf8: boolean, o: AxmlOptions = {}): Uint8Array {
  const { minSdk, stripNames = false } = o;
  // strings: 0 manifest, 1 package, 2 pkg, 3 uses-sdk, 4 minSdkVersion (left empty, as some builds do, with
  // stripNames), 5 the android namespace, 6 a second, unqualified "minSdkVersion" (no resource id), 7 "23" (a
  // raw string for minSdkRaw, which can disagree with the typed value)
  const strs = ['manifest', 'package', pkg, 'uses-sdk', stripNames ? '' : 'minSdkVersion', 'http://schemas.android.com/apk/res/android', 'minSdkVersion', '23'];
  const enc = new TextEncoder();
  const bodies = strs.map((s) => {
    if (utf8) { const b = enc.encode(s); return Uint8Array.of(s.length, b.length, ...b, 0); }
    const out = new Uint8Array(2 + s.length * 2 + 2);
    const dv = new DataView(out.buffer);
    dv.setUint16(0, s.length, true);
    for (let i = 0; i < s.length; i++) dv.setUint16(2 + i * 2, s.charCodeAt(i), true);
    return out;
  });
  const data = new Uint8Array(Math.ceil(bodies.reduce((n, b) => n + b.length, 0) / 4) * 4);
  const offsets: number[] = [];
  bodies.reduce((o, b) => { offsets.push(o); data.set(b, o); return o + b.length; }, 0);
  const poolHdr = 28, poolSize = poolHdr + strs.length * 4 + data.length;
  const mapSize = 8 + 5 * 4; // resource ids for strings 0-4: only minSdkVersion has one
  const elemSize = (attrs: number) => 16 + 20 + 20 * attrs;
  const sdkAttrs = o.shadow !== undefined ? 2 : 1;
  const total = 8 + poolSize + mapSize + elemSize(1) + (minSdk !== undefined ? elemSize(sdkAttrs) : 0);
  const x = new Uint8Array(total);
  const v = new DataView(x.buffer);
  v.setUint16(0, 0x0003, true); v.setUint16(2, 8, true); v.setUint32(4, total, true);
  let p = 8;
  v.setUint16(p, 0x0001, true); v.setUint16(p + 2, poolHdr, true); v.setUint32(p + 4, poolSize, true);
  v.setUint32(p + 8, strs.length, true); v.setUint32(p + 12, 0, true); v.setUint32(p + 16, utf8 ? 0x100 : 0, true);
  v.setUint32(p + 20, poolHdr + strs.length * 4, true); v.setUint32(p + 24, 0, true);
  offsets.forEach((o, i) => v.setUint32(p + poolHdr + i * 4, o, true));
  x.set(data, p + poolHdr + strs.length * 4);
  p += poolSize;
  v.setUint16(p, 0x0180, true); v.setUint16(p + 2, 8, true); v.setUint32(p + 4, mapSize, true);
  v.setUint32(p + 8 + 4 * 4, 0x0101020c, true);
  p += mapSize;
  const NONE = 0xffffffff;
  const element = (name: number, attrs: { ns: number; attr: number; kind: number; data: number; raw?: number }[]) => {
    const size = elemSize(attrs.length);
    v.setUint16(p, 0x0102, true); v.setUint16(p + 2, 16, true); v.setUint32(p + 4, size, true);
    v.setUint32(p + 8, 1, true); v.setUint32(p + 12, NONE, true);
    const e = p + 16;
    v.setUint32(e, NONE, true); v.setUint32(e + 4, name, true); v.setUint16(e + 8, 20, true); v.setUint16(e + 10, 20, true); v.setUint16(e + 12, attrs.length, true);
    attrs.forEach(({ ns, attr, kind, data, raw }, i) => {
      const a = e + 20 + i * 20;
      v.setUint32(a, ns, true); v.setUint32(a + 4, attr, true); v.setUint32(a + 8, raw ?? (kind === 0x03 ? data : NONE), true);
      v.setUint16(a + 12, 8, true); x[a + 15] = kind; v.setUint32(a + 16, data, true);
    });
    p += size;
  };
  element(0, [{ ns: NONE, attr: 1, kind: 0x03, data: 2 }]);
  if (minSdk !== undefined) {
    element(3, [
      ...(o.shadow !== undefined ? [{ ns: NONE, attr: 6, kind: 0x10, data: o.shadow }] : []),
      { ns: 5, attr: 4, kind: o.minSdkKind ?? 0x10, data: minSdk, ...(o.minSdkRaw ? { raw: 7 } : {}) },
    ]);
  }
  return x;
}

/** A zip of stored (uncompressed) files. */
function zip(files: [string, Uint8Array][]): Uint8Array {
  const te = new TextEncoder();
  const locals: Uint8Array[] = [], centrals: Uint8Array[] = [];
  let off = 0;
  for (const [name, body] of files) {
    const n = te.encode(name);
    const local = new Uint8Array(30 + n.length + body.length), central = new Uint8Array(46 + n.length);
    const lv = new DataView(local.buffer), cv = new DataView(central.buffer);
    lv.setUint32(0, 0x04034b50, true); lv.setUint16(4, 20, true); lv.setUint32(18, body.length, true); lv.setUint32(22, body.length, true); lv.setUint16(26, n.length, true);
    local.set(n, 30); local.set(body, 30 + n.length);
    cv.setUint32(0, 0x02014b50, true); cv.setUint16(4, 20, true); cv.setUint16(6, 20, true); cv.setUint32(20, body.length, true); cv.setUint32(24, body.length, true);
    cv.setUint16(28, n.length, true); cv.setUint32(42, off, true);
    central.set(n, 46);
    locals.push(local); centrals.push(central);
    off += local.length;
  }
  const cdSize = centrals.reduce((x, c) => x + c.length, 0);
  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true); ev.setUint16(8, files.length, true); ev.setUint16(10, files.length, true);
  ev.setUint32(12, cdSize, true); ev.setUint32(16, off, true);
  const out = new Uint8Array(off + cdSize + 22);
  let p = 0;
  for (const x of [...locals, ...centrals, end]) { out.set(x, p); p += x.length; }
  return out;
}

/** `libs`: native libraries by ABI, e.g. { 'arm64-v8a': { 'libxul.so': bytes } }. */
/**
 * `minSdk`: a <uses-sdk android:minSdkVersion>; `stripNames`: its attribute named only by resource id;
 * `shadow`: an unqualified minSdkVersion before it (Android ignores it); `minSdkKind`: its value type.
 */
export function fakeApk(pkg = 'sh.edm.s1kiosk', o: { utf8?: boolean; libs?: Record<string, Record<string, Uint8Array>>; extraEntries?: [string, Uint8Array][] } & AxmlOptions = {}): Uint8Array {
  const libs = Object.entries(o.libs ?? {}).flatMap(([abi, fs]) => Object.entries(fs).map(([n, b]): [string, Uint8Array] => [`lib/${abi}/${n}`, b]));
  // extraEntries goes in verbatim, so a test can repeat a name (a zip may list the same path twice)
  return zip([['AndroidManifest.xml', axml(pkg, !!o.utf8, o)], ['classes.dex', new TextEncoder().encode('dex\n035\0')], ...libs, ...(o.extraEntries ?? [])]);
}
