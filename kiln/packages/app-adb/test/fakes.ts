// A tiny stand-in APK: a zip with only an AndroidManifest.xml in binary XML, naming `pkg`. Enough for
// apkPackage, the tests and Kiln's demos. Nothing in it would install.

/** An element under <manifest>, after <uses-sdk>; its attributes are android: ones. A boolean is
 * TYPE_INT_BOOLEAN, `{ ref }` a resource reference (@bool/…), a string TYPE_STRING. A key `#0x0101…`
 * is an attribute whose name string the build stripped: an empty name, only its resource id. */
export interface FakeEl { el: string; attrs?: Record<string, string | boolean | { ref: number }>; children?: FakeEl[] }

/** An intent filter with these actions and categories (short names: MAIN, HOME, DEFAULT, LAUNCHER). */
export function filter(actions: string[], categories: string[]): FakeEl {
  const full = (kind: string, n: string) => (n.includes('.') ? n : `android.intent.${kind}.${n}`);
  return { el: 'intent-filter', children: [
    ...actions.map((a) => ({ el: 'action', attrs: { name: full('action', a) } })),
    ...categories.map((c) => ({ el: 'category', attrs: { name: full('category', c) } })),
  ] };
}

/** <application> with one <activity>, `name`, its filters, and android:enabled when given. */
export function app(activity: FakeEl, appAttrs: FakeEl['attrs'] = {}): FakeEl[] {
  return [{ el: 'application', attrs: appAttrs, children: [activity] }];
}

/** A home screen Android 6 opens: MAIN, HOME and DEFAULT in one filter (am start's MATCH_DEFAULT_ONLY). */
export const HOME_ACTIVITY = (attrs: FakeEl['attrs'] = {}): FakeEl =>
  ({ el: 'activity', attrs: { name: '.MainActivity', ...attrs }, children: [filter(['MAIN'], ['HOME', 'DEFAULT'])] });

/** `shadow`: an unqualified minSdkVersion before the real one; `minSdkKind`: its value type (0x10, int, by default). */
interface AxmlOptions {
  minSdk?: number; stripNames?: boolean; shadow?: number; minSdkKind?: number; minSdkRaw?: '23';
  /**
   * The rest of the manifest: on (the default), an activity with MAIN, HOME and DEFAULT; off, the same
   * with android:enabled="false"; none, no <application>; or the elements themselves.
   */
  home?: 'on' | 'off' | 'none' | FakeEl[];
}

function axml(pkg: string, utf8: boolean, o: AxmlOptions = {}): Uint8Array {
  const { minSdk, stripNames = false } = o;
  // strings: 0 manifest, 1 package, 2 pkg, 3 uses-sdk, 4 minSdkVersion (left empty, as some builds do, with
  // stripNames), 5 the android namespace, 6 a second, unqualified "minSdkVersion" (no resource id), 7 "23" (a
  // raw string for minSdkRaw, which can disagree with the typed value); then the tree's own strings
  const strs = ['manifest', 'package', pkg, 'uses-sdk', stripNames ? '' : 'minSdkVersion', 'http://schemas.android.com/apk/res/android', 'minSdkVersion', '23'];
  const idx = (str: string) => { const i = strs.indexOf(str, 8); if (i >= 0) return i; strs.push(str); return strs.length - 1; };
  // resource ids by string index: minSdkVersion's, then a stripped attribute's (its own empty string each)
  const ids = new Map<number, number>([[4, 0x0101020c]]);
  const stripped = new Map<string, number>();
  const attrIdx = (k: string) => {
    if (!k.startsWith('#')) return idx(k);
    if (!stripped.has(k)) { strs.push(''); stripped.set(k, strs.length - 1); ids.set(strs.length - 1, Number(k.slice(1))); }
    return stripped.get(k)!;
  };
  const home = o.home ?? 'on';
  const tree: FakeEl[] = home === 'none' ? [] : home === 'on' ? app(HOME_ACTIVITY()) : home === 'off' ? app(HOME_ACTIVITY({ enabled: false })) : home;
  const intern = (e: FakeEl) => { idx(e.el); for (const [k, val] of Object.entries(e.attrs ?? {})) { attrIdx(k); if (typeof val === 'string') idx(val); } e.children?.forEach(intern); };
  tree.forEach(intern);

  const enc = new TextEncoder();
  const bodies = strs.map((str) => {
    if (utf8) { const b = enc.encode(str); return Uint8Array.of(str.length, b.length, ...b, 0); }
    const out = new Uint8Array(2 + str.length * 2 + 2);
    const dv = new DataView(out.buffer);
    dv.setUint16(0, str.length, true);
    for (let i = 0; i < str.length; i++) dv.setUint16(2 + i * 2, str.charCodeAt(i), true);
    return out;
  });
  const data = new Uint8Array(Math.ceil(bodies.reduce((n, b) => n + b.length, 0) / 4) * 4);
  const offsets: number[] = [];
  bodies.reduce((at, b) => { offsets.push(at); data.set(b, at); return at + b.length; }, 0);
  const poolHdr = 28, poolSize = poolHdr + strs.length * 4 + data.length;
  const chunks: Uint8Array[] = [];
  const chunk = (size: number, fill: (v: DataView, x: Uint8Array) => void) => { const x = new Uint8Array(size); fill(new DataView(x.buffer), x); chunks.push(x); };
  chunk(poolSize, (v, x) => {
    v.setUint16(0, 0x0001, true); v.setUint16(2, poolHdr, true); v.setUint32(4, poolSize, true);
    v.setUint32(8, strs.length, true); v.setUint32(12, 0, true); v.setUint32(16, utf8 ? 0x100 : 0, true);
    v.setUint32(20, poolHdr + strs.length * 4, true); v.setUint32(24, 0, true);
    offsets.forEach((off, i) => v.setUint32(poolHdr + i * 4, off, true));
    x.set(data, poolHdr + strs.length * 4);
  });
  // the resource map: one id per string up to the last one with an id (0 for the others)
  const mapSize = 8 + (Math.max(...ids.keys()) + 1) * 4;
  chunk(mapSize, (v) => { v.setUint16(0, 0x0180, true); v.setUint16(2, 8, true); v.setUint32(4, mapSize, true); for (const [i, id] of ids) v.setUint32(8 + i * 4, id, true); });
  const NONE = 0xffffffff;
  type Attr = { ns: number; attr: number; kind: number; data: number; raw?: number };
  const element = (name: number, attrs: Attr[]) => chunk(36 + 20 * attrs.length, (v, x) => {
    v.setUint16(0, 0x0102, true); v.setUint16(2, 16, true); v.setUint32(4, 36 + 20 * attrs.length, true);
    v.setUint32(8, 1, true); v.setUint32(12, NONE, true);
    v.setUint32(16, NONE, true); v.setUint32(20, name, true); v.setUint16(24, 20, true); v.setUint16(26, 20, true); v.setUint16(28, attrs.length, true);
    attrs.forEach(({ ns, attr, kind, data: d, raw }, i) => {
      const a = 36 + i * 20;
      v.setUint32(a, ns, true); v.setUint32(a + 4, attr, true); v.setUint32(a + 8, raw ?? (kind === 0x03 ? d : NONE), true);
      v.setUint16(a + 12, 8, true); x[a + 15] = kind; v.setUint32(a + 16, d, true);
    });
  });
  const end = (name: number) => chunk(24, (v) => {
    v.setUint16(0, 0x0103, true); v.setUint16(2, 16, true); v.setUint32(4, 24, true);
    v.setUint32(8, 1, true); v.setUint32(12, NONE, true); v.setUint32(16, NONE, true); v.setUint32(20, name, true);
  });
  const write = (e: FakeEl) => {
    element(idx(e.el), Object.entries(e.attrs ?? {}).map(([k, val]): Attr => {
      if (typeof val === 'string') return { ns: 5, attr: attrIdx(k), kind: 0x03, data: idx(val) };
      if (typeof val === 'boolean') return { ns: 5, attr: attrIdx(k), kind: 0x12, data: val ? NONE : 0 };
      return { ns: 5, attr: attrIdx(k), kind: 0x01, data: val.ref };
    }));
    e.children?.forEach(write);
    end(idx(e.el));
  };
  element(0, [{ ns: NONE, attr: 1, kind: 0x03, data: 2 }]);
  if (minSdk !== undefined) {
    element(3, [
      ...(o.shadow !== undefined ? [{ ns: NONE, attr: 6, kind: 0x10, data: o.shadow }] : []),
      { ns: 5, attr: 4, kind: o.minSdkKind ?? 0x10, data: minSdk, ...(o.minSdkRaw ? { raw: 7 } : {}) },
    ]);
    end(3);
  }
  tree.forEach(write);

  const total = 8 + chunks.reduce((n, c) => n + c.length, 0);
  const x = new Uint8Array(total);
  const v = new DataView(x.buffer);
  v.setUint16(0, 0x0003, true); v.setUint16(2, 8, true); v.setUint32(4, total, true);
  chunks.reduce((at, c) => { x.set(c, at); return at + c.length; }, 8);
  return x;
}

/** A zip of stored (uncompressed) files. */
export function zip(files: [string, Uint8Array][]): Uint8Array {
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
