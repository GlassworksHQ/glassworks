import { describe, expect, it } from 'vitest';
import type { PanelShell } from '@glassworks/adb';
import { ManifestError, Steps, apiKeyFingerprint, apkHome, apkInfo, apkPermissions, axmlTree, dropAdmin, grantPermissions, handOverApiKey, kioskVersion, q, readApiKey, startApp, zipEntry } from '../src';
import { FakeKeySocket, HOME_ACTIVITY, app, fakeApk, filter, zip, type FakeEl } from './fakes';

class Shell implements PanelShell {
  cmds: string[] = [];
  constructor(private readonly answer: (cmd: string) => { out: string; code: number } = () => ({ out: '', code: 0 })) {}
  async run(cmd: string) { this.cmds.push(cmd); return this.answer(cmd); }
  async push(path: string) { this.cmds.push(`push ${path}`); }
}

describe('S1 Kiosk over adb', () => {
  it('starts the app with the URL and language quoted, or with neither', async () => {
    const s = new Shell();
    await startApp(new Steps(s), { haUrl: `http://ha.local:8123/it's`, locale: 'en-GB' });
    await startApp(new Steps(s), {});
    expect(s.cmds).toEqual([
      `am start -n sh.edm.s1kiosk/.MainActivity --es url 'http://ha.local:8123/it'\\''s' --es set_locale 'en-GB'`,
      'am start -n sh.edm.s1kiosk/.MainActivity',
    ]);
    expect(q(`a'b`)).toBe(`'a'\\''b'`);
  });

  it('grants what it can and carries on past a refusal', async () => {
    const s = new Shell((c) => (c.includes('CAMERA') ? { out: 'Security exception', code: 255 } : { out: '', code: 0 }));
    await grantPermissions(new Steps(s));
    expect(s.cmds.at(-1)).toBe('dpm set-active-admin sh.edm.s1kiosk/.AdminReceiver');
    expect(s.cmds).toHaveLength(6);
  });

  it('a run that fails throws with the command; an install pushes, installs and cleans up', async () => {
    const s = new Shell((c) => (c.startsWith('pm install') ? { out: 'Success\n', code: 0 } : c === 'false' ? { out: 'no', code: 1 } : { out: '', code: 0 }));
    await expect(new Steps(s).run('false')).rejects.toThrow(/failed \(1\): false/);
    await new Steps(s).install('x', new Uint8Array(1), true);
    expect(s.cmds.slice(1)).toEqual(['push /data/local/tmp/x.apk', 'pm install -r -g /data/local/tmp/x.apk', 'rm -f /data/local/tmp/x.apk']);
  });

  it('asks the app to drop its device admin and waits until Android no longer lists it', async () => {
    let polls = 0;
    const s = new Shell((c) => (c === 'dumpsys device_policy' ? { out: ++polls < 3 ? 'sh.edm.s1kiosk/.AdminReceiver' : '', code: 0 } : { out: '', code: 0 }));
    expect(await dropAdmin(s, async () => {})).toBe(true);
    expect(s.cmds[0]).toBe('am start -n sh.edm.s1kiosk/.MainActivity --ez remove_admin true');
    expect(polls).toBe(3);
    const stuck = new Shell((c) => ({ out: c === 'dumpsys device_policy' ? 'sh.edm.s1kiosk/.AdminReceiver' : '', code: 0 }));
    expect(await dropAdmin(stuck, async () => {})).toBe(false);
  });

  it('doesn\'t wait on another app\'s admin whose package starts with this one\'s', async () => {
    const app = { package: 'dev.glassworks.generic_android', activity: 'dev.glassworks.generic_android/dev.glassworks.MainActivity' };
    const s = new Shell((c) => ({ out: c === 'dumpsys device_policy' ? '    dev.glassworks.generic_android.legacy/dev.glassworks.AdminReceiver:\n' : '', code: 0 }));
    expect(await dropAdmin(s, async () => {}, app)).toBe(true);
    expect(s.cmds.filter((c) => c === 'dumpsys device_policy')).toHaveLength(1);
  });

  it('reads the installed version, or none', async () => {
    expect(await kioskVersion(new Shell(() => ({ out: '    versionCode=600 minSdk=23\n    versionName=0.5.0-legacy\n', code: 0 })))).toEqual({ name: '0.5.0-legacy', code: 600 });
    expect(await kioskVersion(new Shell())).toBeUndefined(); // Android 6 prints nothing for a missing package
  });
});

describe('the APK\'s package, read before anything is installed', () => {
  it('reads it from binary XML with UTF-16 or UTF-8 strings', async () => {
    const { fakeApk } = await import('./fakes');
    const { apkPackage } = await import('../src');
    expect(await apkPackage(fakeApk('sh.edm.s1kiosk'))).toBe('sh.edm.s1kiosk');
    expect(await apkPackage(fakeApk('com.example.other', { utf8: true }))).toBe('com.example.other');
  });

  it('a deflated manifest, as aapt2 writes it', async () => {
    const { deflateRawSync } = await import('node:zlib');
    const { fakeApk } = await import('./fakes');
    const { apkPackage, zipEntry } = await import('../src');
    const stored = fakeApk('sh.edm.s1kiosk');
    const xml = (await zipEntry(stored, 'AndroidManifest.xml'))!;
    const z = new Uint8Array(deflateRawSync(xml));
    // rebuild the zip with method 8
    const local = Buffer.alloc(30 + 19); local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(8, 8); local.writeUInt32LE(z.length, 18); local.writeUInt32LE(xml.length, 22); local.writeUInt16LE(19, 26); local.write('AndroidManifest.xml', 30);
    const central = Buffer.alloc(46 + 19); central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(8, 10); central.writeUInt32LE(z.length, 20); central.writeUInt32LE(xml.length, 24); central.writeUInt16LE(19, 28); central.write('AndroidManifest.xml', 46);
    const end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(1, 8); end.writeUInt16LE(1, 10); end.writeUInt32LE(central.length, 12); end.writeUInt32LE(local.length + z.length, 16);
    expect(await apkPackage(new Uint8Array(Buffer.concat([local, z, central, end])))).toBe('sh.edm.s1kiosk');
  });

  it('reads minSdkVersion (by name, or by resource id when the name is stripped) and the ABIs', async () => {
    const { fakeApk } = await import('./fakes');
    const { apkInfo } = await import('../src');
    expect(await apkInfo(fakeApk('sh.edm.s1kiosk', { minSdk: 23, libs: { 'arm64-v8a': { 'libxul.so': new Uint8Array(1) } } }))).toEqual({ package: 'sh.edm.s1kiosk', minSdk: 23, abis: ['arm64-v8a'] });
    expect(await apkInfo(fakeApk('sh.edm.s1kiosk', { minSdk: 26, stripNames: true, utf8: true }))).toEqual({ package: 'sh.edm.s1kiosk', minSdk: 26, abis: [] });
    expect((await apkInfo(fakeApk('sh.edm.s1kiosk'))).minSdk).toBeUndefined();
    // an unqualified minSdkVersion first isn't the one Android checks: the framework attribute is (#354 review)
    expect((await apkInfo(fakeApk('sh.edm.s1kiosk', { minSdk: 26, shadow: 23 }))).minSdk).toBe(26);
    expect((await apkInfo(fakeApk('sh.edm.s1kiosk', { minSdk: 26, shadow: 23, utf8: true }))).minSdk).toBe(26);
    // a raw string that disagrees with the typed value: Android reads the typed one (#354 review)
    expect((await apkInfo(fakeApk('sh.edm.s1kiosk', { minSdk: 26, minSdkRaw: '23' }))).minSdk).toBe(26);
  });

  it('refuses a minSdkVersion it can\'t read (a reference) rather than reading it as absent', async () => {
    const { fakeApk } = await import('./fakes');
    const { apkInfo } = await import('../src');
    await expect(apkInfo(fakeApk('sh.edm.s1kiosk', { minSdk: 0x7f0a0001, minSdkKind: 0x01 }))).rejects.toThrow(/uses-sdk minSdkVersion is a value Kiln can't read \(type 0x1\)/);
  });

  it('reads only the un-namespaced package attribute', async () => {
    const { fakeApk } = await import('./fakes');
    const { zipEntry, axmlAttribute } = await import('../src');
    const xml = (await zipEntry(fakeApk('sh.edm.s1kiosk'), 'AndroidManifest.xml'))!.slice();
    // the fake's first attribute is <manifest package>: give it a namespace, as a crafted APK could
    const dv = new DataView(xml.buffer);
    let at = 8; at += dv.getUint32(at + 4, true); at += dv.getUint32(at + 4, true); // string pool, resource map
    dv.setUint32(at + 16 + 20, 1, true);
    expect(axmlAttribute(xml, 'manifest', 'package')).toBeUndefined();
  });

  it('refuses a file that isn\'t an APK', async () => {
    const { apkPackage } = await import('../src');
    await expect(apkPackage(new TextEncoder().encode('not a real APK'))).rejects.toThrow(/isn't an APK/);
  });
});

describe('the permissions an APK asks for, on one Android API', () => {
  const perm = (el: string, name: string, attrs: FakeEl['attrs'] = {}): FakeEl => ({ el, attrs: { name: `android.permission.${name}`, ...attrs } });
  const apk = fakeApk('dev.example', { home: [
    perm('uses-permission', 'INTERNET'),
    perm('uses-permission', 'BLUETOOTH', { maxSdkVersion: '30' }),
    perm('uses-permission', 'REBOOT', { maxSdkVersion: '22' }),
    perm('uses-permission-sdk-23', 'WRITE_SECURE_SETTINGS'),
  ] });
  it('drops one whose maxSdkVersion is below the API, and reads <uses-permission-sdk-23> from API 23 (#411 review)', async () => {
    expect(await apkPermissions(apk, 23)).toEqual(['android.permission.INTERNET', 'android.permission.BLUETOOTH', 'android.permission.WRITE_SECURE_SETTINGS']);
    expect(await apkPermissions(apk, 22)).toEqual(['android.permission.INTERNET', 'android.permission.BLUETOOTH', 'android.permission.REBOOT']);
    expect(await apkPermissions(apk, 31)).toEqual(['android.permission.INTERNET', 'android.permission.WRITE_SECURE_SETTINGS']);
  });
});

describe('the APK\'s home screen (the Mirror\'s first flash had none, 2026-11-16)', () => {
  const P = 'sh.edm.s1kiosk';
  const home = async (h: FakeEl[]) => apkHome(fakeApk(P, { home: h }));
  const act = (name: string, filters: FakeEl[], attrs: FakeEl['attrs'] = {}): FakeEl => ({ el: 'activity', attrs: { name, ...attrs }, children: filters });
  const alias = (name: string, target: string, attrs: FakeEl['attrs'] = {}): FakeEl =>
    ({ el: 'activity-alias', attrs: { name, targetActivity: target, ...attrs }, children: [filter(['MAIN'], ['HOME', 'DEFAULT'])] });

  it.each([[false], [true]])('finds an activity with MAIN, HOME and DEFAULT, and whether it is on (utf8 %s)', async (utf8) => {
    expect(await apkHome(fakeApk(P, { utf8 }))).toEqual({ on: [`${P}.MainActivity`], off: [], unclear: [] });
    expect(await apkHome(fakeApk(P, { utf8, home: 'off' }))).toEqual({ on: [], off: [`${P}.MainActivity`], unclear: [] });
    expect(await apkHome(fakeApk(P, { utf8, home: 'none' }))).toEqual({ on: [], off: [], unclear: [] });
  });

  // Android 6's am start -a MAIN -c HOME, and its chooser, resolve with MATCH_DEFAULT_ONLY: DEFAULT must be in the same filter
  it.each([
    ['MAIN without HOME (a launcher icon)', [filter(['MAIN'], ['LAUNCHER', 'DEFAULT'])]],
    ['HOME without DEFAULT', [filter(['MAIN'], ['HOME'])]],
    ['MAIN+HOME and MAIN+DEFAULT in separate filters', [filter(['MAIN'], ['HOME']), filter(['MAIN'], ['DEFAULT'])]],
    ['HOME and DEFAULT without MAIN', [filter(['VIEW'], ['HOME', 'DEFAULT'])]],
  ])('an enabled activity with %s is no home screen', async (_, filters) => {
    expect(await home(app(act('.MainActivity', filters)))).toEqual({ on: [], off: [], unclear: [] });
  });

  it.each([
    ['a <data> scheme', { el: 'data', attrs: { scheme: 'example' } }],
    ['a <data> MIME type', { el: 'data', attrs: { mimeType: 'text/plain' } }],
    // a build that strips attribute names keeps their resource ids, which Android reads
    ['a <data> scheme by resource id only', { el: 'data', attrs: { '#0x01010027': 'example' } }],
    ['a <data> MIME type by resource id only', { el: 'data', attrs: { '#0x01010026': 'text/plain' } }],
  ])('MAIN, HOME and DEFAULT with %s is no home screen: the HOME intent carries no data', async (_, data) => {
    const f = filter(['MAIN'], ['HOME', 'DEFAULT']);
    expect(await home(app(act('.MainActivity', [{ ...f, children: [...f.children!, data as FakeEl] }])))).toEqual({ on: [], off: [], unclear: [] });
  });

  it('enabled from a resource is unclear; an application turned off turns its activities off', async () => {
    expect(await home(app(HOME_ACTIVITY({ enabled: { ref: 0x7f040001 } })))).toEqual({ on: [], off: [], unclear: [`${P}.MainActivity`] });
    expect(await home(app(HOME_ACTIVITY(), { enabled: false }))).toEqual({ on: [], off: [`${P}.MainActivity`], unclear: [] });
    expect(await home(app(HOME_ACTIVITY({ enabled: true }), { enabled: true }))).toEqual({ on: [`${P}.MainActivity`], off: [], unclear: [] });
  });

  it('reads name and enabled by resource id when the build stripped their names', async () => {
    const stripped = (attrs: FakeEl['attrs']): FakeEl => ({ el: 'activity', attrs, children: [filter(['MAIN'], ['HOME', 'DEFAULT'])] });
    expect(await home(app(stripped({ '#0x01010003': '.Home' })))).toEqual({ on: [`${P}.Home`], off: [], unclear: [] });
    expect(await home(app(stripped({ '#0x01010003': '.Home', '#0x0101000e': false })))).toEqual({ on: [], off: [`${P}.Home`], unclear: [] });
  });

  it('an activity-alias counts by its own enabled bit, over an enabled or a disabled target', async () => {
    const tree = (target: FakeEl['attrs'], aliasAttrs: FakeEl['attrs'] = {}): FakeEl[] =>
      [{ el: 'application', children: [act('.MainActivity', [], target), alias('.Home', '.MainActivity', aliasAttrs)] }];
    expect(await home(tree({}))).toEqual({ on: [`${P}.Home`], off: [], unclear: [] });
    // decided (#385 verifier): PackageParser gives the alias its own enabled bit, and ActivityThread starts the target class by name
    expect(await home(tree({ enabled: false }))).toEqual({ on: [`${P}.Home`], off: [], unclear: [] });
    expect(await home(tree({}, { enabled: false }))).toEqual({ on: [], off: [`${P}.Home`], unclear: [] });
  });

  it('a manifest that runs off its own data is a ManifestError, not a RangeError', async () => {
    const apk = fakeApk(P);
    const at = new TextDecoder('latin1').decode(apk).indexOf('AndroidManifest.xml') + 'AndroidManifest.xml'.length; // the stored manifest
    new DataView(apk.buffer, apk.byteOffset).setUint32(at + 8 + 8, 0x00ffffff, true); // its string pool claims 16M strings
    await expect(apkHome(apk)).rejects.toBeInstanceOf(ManifestError);
    await expect(apkInfo(apk)).rejects.toThrow(/^this APK's AndroidManifest\.xml can't be read$/);
  });

  it('a manifest cut short (its last end element missing 8 bytes) is a ManifestError, not the tree read from what is left', async () => {
    const manifest = (await zipEntry(fakeApk(P), 'AndroidManifest.xml'))!;
    const cut = manifest.slice(0, manifest.length - 8);
    expect(() => axmlTree(cut)).toThrow(ManifestError);
    const apk = zip([['AndroidManifest.xml', cut]]);
    await expect(apkHome(apk)).rejects.toBeInstanceOf(ManifestError);
    await expect(apkInfo(apk)).rejects.toBeInstanceOf(ManifestError);
    expect(axmlTree(manifest)[0]!.name).toBe('manifest'); // the whole one reads
    // the document's size made to agree with the cut: its last chunk still claims 8 bytes more than are left
    const agreed = cut.slice();
    new DataView(agreed.buffer).setUint32(4, agreed.length, true);
    expect(() => axmlTree(agreed)).toThrow(ManifestError);
  });

  it('reads the tree with the package and minSdkVersion around it unchanged', async () => {
    const apk = fakeApk(P, { minSdk: 23 });
    expect(await apkInfo(apk)).toMatchObject({ package: P, minSdk: 23 });
    const tree = axmlTree((await zipEntry(apk, 'AndroidManifest.xml'))!);
    expect(tree.map((e) => e.name)).toEqual(['manifest']);
    expect(tree[0]!.children.map((e) => e.name)).toEqual(['uses-sdk', 'application']);
  });
});

describe('the ESPHome API key across a reflash (#574)', () => {
  // made up: base64 of 32 bytes 1..32, never a real key
  const KEY = btoa(String.fromCharCode(...Array.from({ length: 32 }, (_, i) => i + 1)));
  const shell = (s: FakeKeySocket, ran: string[] = []): PanelShell => ({
    async run(cmd) { ran.push(cmd); return s.shell(cmd) ?? { out: '', code: 0 }; },
    async push() {},
    openStream: (svc) => s.open(svc),
  });

  it('reads the key from the running app, and hands it to the fresh one', async () => {
    const before = new FakeKeySocket(KEY), ran: string[] = [];
    const key = await readApiKey(shell(before, ran));
    expect(key).toBe(KEY);
    const after = new FakeKeySocket();
    expect(await handOverApiKey(shell(after, ran), key!, async () => {})).toBe(true);
    expect(after.key).toBe(KEY);
    // only through the socket: never on a command line
    expect(ran.join('\n')).not.toContain(KEY);
  });

  it('has nothing to carry from an app with no key or one too old to hand it over', async () => {
    expect(await readApiKey(shell(new FakeKeySocket()))).toBeUndefined();
    const old = new FakeKeySocket(KEY, false);
    expect(await readApiKey(shell(old))).toBeUndefined();
    expect(old.verbs).toEqual([]);
  });

  it('gives up when the fresh app never listens, and says no when it refuses', async () => {
    const down = new FakeKeySocket(undefined, false);
    expect(await handOverApiKey(shell(down), KEY, async () => {}, 0)).toBe(false);
    expect(down.verbs).toEqual([]);
    expect(await handOverApiKey(shell(new FakeKeySocket(btoa('x'.repeat(32)))), KEY, async () => {})).toBe(false);
  });

  it('tries again when an answer is lost, and gets ok for the key the app already stored (#575 review)', async () => {
    const s = new FakeKeySocket();
    s.drop = 1;
    expect(await handOverApiKey(shell(s), KEY, async () => {}, 60_000, 5)).toBe(true);
    expect(s.key).toBe(KEY);
    expect(s.verbs).toEqual(['set', 'set']);
  });

  it('returns false, never throws, when the app never answers (#575 review)', async () => {
    const s = new FakeKeySocket();
    s.drop = 1_000;
    let slept = 0;
    expect(await handOverApiKey(shell(s), KEY, async () => { slept++; }, 0, 5)).toBe(false);
    expect(slept).toBeLessThanOrEqual(1);
  });

  it('throws when the app has a key it can\'t hand over now, so a flash doesn\'t lose it (#575 review)', async () => {
    const down = new FakeKeySocket(KEY);
    down.unreadable = true;
    await expect(readApiKey(shell(down))).rejects.toThrow(/can't read it right now/);
    const mute = new FakeKeySocket(KEY);
    mute.drop = 1;
    await expect(readApiKey(shell(mute), 5)).rejects.toThrow(/didn't answer/);
  });

  it('fingerprints a key as the app logs it', async () => {
    // NoiseKeys.fingerprint: SHA-256's first two bytes, in hex
    expect(await apiKeyFingerprint(KEY)).toMatch(/^[0-9a-f]{4}$/);
    expect(await apiKeyFingerprint(KEY)).not.toBe(await apiKeyFingerprint(btoa('x'.repeat(32))));
  });
});
