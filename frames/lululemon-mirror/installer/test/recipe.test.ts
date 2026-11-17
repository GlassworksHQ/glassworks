import { fakeApk } from '@glassworks/app-adb/fakes';
import { describe, expect, it } from 'vitest';
import { PREVIOUS_HOME, checkMirror, installMirror, parseDfFreeMb, parsePreferredHome, undoMirror, type MirrorPhase } from '../src';
import { GLASSWORKS } from '../src/app';
import { FakeMirror, LAUNCHER3, MIRROR_KIOSK, fakeMirrorApk } from './fakes';

/** the HOME component Android records for Glassworks: its Launcher alias */
const OURS = GLASSWORKS.home;
const PKG = GLASSWORKS.package;
const noSleep = async () => {};
const apk = fakeMirrorApk();
/** Commands that change the Mirror. Everything else the recipe runs only reads. */
const WRITES = /^(push |pm (install|uninstall|grant|disable|enable|hide)|appops set|dpm |echo |rm |am (start|force-stop))/;

describe('reading the Mirror', () => {
  it('finds the "Always" home app in Android 6\'s preferred-activities dump', () => {
    const p = new FakeMirror();
    return p.run('dumpsys package preferred-activities').then(({ out }) => {
      expect(parsePreferredHome(out)).toBe(MIRROR_KIOSK);
      // the SMS entry is mAlways too, and the chooser's "Selected from" names other home apps: neither counts
      expect(parsePreferredHome(out.replace('Category: "android.intent.category.HOME"', ''))).toBeUndefined();
      expect(parsePreferredHome('no such section')).toBeUndefined();
    });
  });

  it('reads free space from Android 6\'s df and from toybox\'s', () => {
    expect(parseDfFreeMb('Filesystem               Size     Used     Free   Blksize\n/data                    5.1G     1.1G     4.0G   4096\n')).toBe(4096);
    expect(parseDfFreeMb('Filesystem               Size     Used     Free   Blksize\n/data                    5.1G     4.9G   212.5M   4096\n')).toBe(212.5);
    expect(parseDfFreeMb('Filesystem     1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-0  5242880 1153434   4089446  23% /data\n')).toBeCloseTo(3993.6, 0);
    expect(parseDfFreeMb('df: /data: Permission denied\n')).toBeUndefined();
  });

  it('passes the community image on the original Mirror, and only reads', async () => {
    const p = new FakeMirror();
    const r = await checkMirror(p);
    expect(r.ok).toBe(true);
    expect(r.checks.map((c) => [c.what, c.found])).toEqual([
      ['Android', '6.0.1 (API 23)'], ['Board', 'msm8916'], ['Processor', 'arm64-v8a, armeabi-v7a, armeabi'], ['Free space', '4096 MB'],
    ]);
    expect(r).toMatchObject({ home: MIRROR_KIOSK });
    expect(r.app).toBeUndefined();
    expect(p.cmds.filter((c) => WRITES.test(c))).toEqual([]);
  });

  it.each([
    ['Android 10', { sdk: 29, release: '10' }, 'Android'],
    ['another board', { platform: 'imx8mq' }, 'Board'],
    ['a 32-bit x86 tablet', { abilist: 'x86' }, 'Processor'],
    ['a full disk', { free: '212.5M' }, 'Free space'],
  ])('fails %s', async (_, o, what) => {
    const r = await checkMirror(new FakeMirror(o));
    expect(r.ok).toBe(false);
    expect(r.checks.filter((c) => !c.ok).map((c) => c.what)).toEqual([what]);
  });
});

describe('install', () => {
  it('installs, grants, starts with the URL, then waits for Glassworks + Always in the chooser', async () => {
    const p = new FakeMirror({ picks: () => OURS, pickAfter: 2 });
    const phases: MirrorPhase[] = [];
    const res = await installMirror(p, { apk, haUrl: 'http://homeassistant.local:8123', sleep: noSleep, onPhase: (ph) => phases.push(ph) });
    expect(res).toEqual({ app: { name: '0.5.0-legacy', code: 600 }, home: 'set', previousHome: MIRROR_KIOSK });
    expect(phases).toEqual([{ step: 'apk', stage: 'copy' }, { step: 'apk', stage: 'install' }, { step: 'grant' }, { step: 'start' }, { step: 'home', pick: 'Glassworks' }]);
    const at = (c: string) => { const i = p.cmds.findIndex((x) => x.startsWith(c)); expect(i, c).toBeGreaterThanOrEqual(0); return i; };
    const order = ['push /data/local/tmp/mirror-app.apk', 'pm install -r -g /data/local/tmp/mirror-app.apk', `echo '${MIRROR_KIOSK}' > ${PREVIOUS_HOME}`,
      `pm grant ${PKG} android.permission.RECORD_AUDIO`, 'dpm set-active-admin', `am start -n ${GLASSWORKS.activity} --es url 'http://homeassistant.local:8123'`,
      'am start -a android.intent.action.MAIN -c android.intent.category.HOME'].map(at);
    expect(order).toEqual([...order].sort((a, b) => a - b));
    expect(p.files.get(PREVIOUS_HOME)).toBe(`${MIRROR_KIOSK}\n`);
    expect(p.preferred?.comp).toBe(OURS);
    expect(p.cmds.some((c) => c.startsWith('cmd ') || c.includes('disable'))).toBe(false); // neither exists for adb's shell on Android 6
  });

  it('says so, without failing, when nobody picks Glassworks in time', async () => {
    const p = new FakeMirror();
    const res = await installMirror(p, { apk, sleep: noSleep, homeTimeoutMs: 0 });
    expect(res.home).toBe('unconfirmed');
    expect(p.chooser).toEqual([MIRROR_KIOSK, LAUNCHER3, OURS]);
  });

  it('refuses a device that fails a check, with nothing written', async () => {
    const p = new FakeMirror({ sdk: 29, release: '10' });
    await expect(installMirror(p, { apk, sleep: noSleep })).rejects.toThrow(/Android: 10 \(API 29\).*Nothing was changed/);
    expect(p.cmds.filter((c) => WRITES.test(c))).toEqual([]);
  });

  it('names the wrong APK plainly and goes no further', async () => {
    const p = new FakeMirror({ installFails: 'Failure [INSTALL_FAILED_OLDER_SDK]' });
    await expect(installMirror(p, { apk, sleep: noSleep })).rejects.toThrow(/legacy build/);
    expect(p.cmds.some((c) => c.startsWith('pm grant') || c.startsWith('am start'))).toBe(false);
    expect(p.files.has(PREVIOUS_HOME)).toBe(false); // "nothing was changed" holds: no undo record either
    expect((await checkMirror(p)).pendingHome).toBeUndefined();
    expect(p.cmds).toContain('rm -f /data/local/tmp/mirror-app.apk');
  });

  it('refuses an APK that isn\'t Glassworks before anything reaches the Mirror', async () => {
    const p = new FakeMirror();
    await expect(installMirror(p, { apk: fakeApk('com.nimrod.mirrorkiosk'), sleep: noSleep })).rejects.toThrow(/is com\.nimrod\.mirrorkiosk, not Glassworks \(dev\.glassworks\.generic_android\.legacy\).*Nothing was changed/);
    await expect(installMirror(p, { apk: new TextEncoder().encode('not an apk'), sleep: noSleep })).rejects.toThrow(/isn't an APK/);
    await expect(installMirror(p, { apk: fakeMirrorApk({ home: 'none' }), sleep: noSleep })).rejects.toThrow(/can't be the Mirror's home screen/);
    // the old S1 Kiosk id is no longer the Mirror's app
    await expect(installMirror(p, { apk: fakeApk('sh.edm.s1kiosk'), sleep: noSleep })).rejects.toThrow(/is sh\.edm\.s1kiosk, not Glassworks/);
    expect(p.cmds.filter((c) => WRITES.test(c))).toEqual([]);
  });

  it('an update with another app kept as home: opens its settings to clear its defaults, then the chooser', async () => {
    const p = new FakeMirror({ installed: 500, clearsDefaults: true, picks: () => OURS, pickAfter: 1 });
    p.pick(MIRROR_KIOSK); // Android keeps this pick: the home apps don't change on an update
    const phases: MirrorPhase[] = [];
    const res = await installMirror(p, { apk, sleep: noSleep, onPhase: (ph) => phases.push(ph) });
    expect(res.home).toBe('set');
    expect(phases.filter((ph) => ph.step === 'home')).toEqual([
      { step: 'home', pick: 'Glassworks' }, { step: 'home', pick: 'Glassworks', clear: 'com.nimrod.mirrorkiosk' }, { step: 'home', pick: 'Glassworks' },
    ]);
    expect(p.cmds).toContain('am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.nimrod.mirrorkiosk');
    expect(p.preferred?.comp).toBe(OURS);
  });

  it('an update keeps Glassworks as home, and keeps the home app recorded the first time', async () => {
    const p = new FakeMirror({ installed: 500 });
    p.files.set(PREVIOUS_HOME, `${MIRROR_KIOSK}\n`);
    const res = await installMirror(p, { apk, sleep: noSleep, homeTimeoutMs: 0 });
    expect(res).toMatchObject({ home: 'set', previousHome: MIRROR_KIOSK });
    expect(p.cmds.some((c) => c.startsWith('echo '))).toBe(false);
    expect(p.cmds).not.toContain('am start -a android.intent.action.MAIN -c android.intent.category.HOME');
  });
});

describe('undo', () => {
  it('takes back the device admin, removes Glassworks, and waits for the old home app to be picked', async () => {
    const p = new FakeMirror({ picks: (c) => c.find((x) => x === MIRROR_KIOSK), pickAfter: 2 });
    await installMirror(p, { apk, sleep: noSleep, homeTimeoutMs: 0 });
    p.pick(OURS);
    p.cmds = [];
    const phases: MirrorPhase[] = [];
    const res = await undoMirror(p, { sleep: noSleep, onPhase: (ph) => phases.push(ph) });
    expect(res).toEqual({ removed: true, home: 'set', previousHome: MIRROR_KIOSK });
    expect(phases).toEqual([{ step: 'admin' }, { step: 'remove' }, { step: 'home', pick: MIRROR_KIOSK }]);
    expect(p.cmds.indexOf(`pm uninstall ${PKG}`)).toBeGreaterThan(p.cmds.lastIndexOf('dumpsys device_policy'));
    expect(p.installed).toBeUndefined();
    expect(p.preferred?.comp).toBe(MIRROR_KIOSK);
    expect(p.files.has(PREVIOUS_HOME)).toBe(false);
  });

  it('fails loudly when Glassworks keeps its device admin, and keeps the record', async () => {
    const p = new FakeMirror({ installed: 600, keepsAdmin: true });
    p.files.set(PREVIOUS_HOME, `${MIRROR_KIOSK}\n`);
    await expect(undoMirror(p, { sleep: noSleep })).rejects.toThrow(/DELETE_FAILED_DEVICE_POLICY_MANAGER.*Device administrators/);
    expect(p.installed).toBe(600);
    expect(p.files.has(PREVIOUS_HOME)).toBe(true);
  });

  it('without a record, any home app but Glassworks will do', async () => {
    const p = new FakeMirror({ installed: 600, picks: () => LAUNCHER3 });
    const res = await undoMirror(p, { sleep: noSleep });
    expect(res).toEqual({ removed: true, home: 'set' });
    expect(p.preferred?.comp).toBe(LAUNCHER3);
  });

  it('keeps the record when the home app isn\'t picked in time, so a second undo can finish', async () => {
    const p = new FakeMirror({ installed: 600 });
    p.files.set(PREVIOUS_HOME, `${MIRROR_KIOSK}\n`);
    expect(await undoMirror(p, { sleep: noSleep, homeTimeoutMs: 0 })).toMatchObject({ removed: true, home: 'unconfirmed' });
    expect(p.files.has(PREVIOUS_HOME)).toBe(true);
    expect(await checkMirror(p)).toMatchObject({ pendingHome: MIRROR_KIOSK }); // the checks screen offers undo again
    p.o.picks = () => MIRROR_KIOSK;
    expect(await undoMirror(p, { sleep: noSleep })).toEqual({ removed: false, home: 'set', previousHome: MIRROR_KIOSK });
    expect(p.files.has(PREVIOUS_HOME)).toBe(false);
  });
});
