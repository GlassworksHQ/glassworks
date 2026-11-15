import { describe, expect, it } from 'vitest';
import type { PanelShell } from '@glassworks/adb';
import { Steps, dropAdmin, grantPermissions, kioskVersion, q, startApp } from '../src';

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
    const stuck = new Shell((c) => ({ out: c === 'dumpsys device_policy' ? 'sh.edm.s1kiosk' : '', code: 0 }));
    expect(await dropAdmin(stuck, async () => {})).toBe(false);
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
  });

  it('refuses a file that isn\'t an APK', async () => {
    const { apkPackage } = await import('../src');
    await expect(apkPackage(new TextEncoder().encode('not a real APK'))).rejects.toThrow(/isn't an APK/);
  });
});
