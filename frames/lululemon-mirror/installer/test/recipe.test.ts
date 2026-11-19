import { fakeApk } from '@glassworks/app-adb/fakes';
import { describe, expect, it } from 'vitest';
import { HIDDEN_HOMES, PREVIOUS_HOME, checkMirror, installMirror, parseDfFreeMb, parsePreferredHome, undoMirror, type MirrorPhase } from '../src';
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
  it('installs, grants, turns the screen upright, starts with the URL, and makes it the only home: nothing waits on a tap', async () => {
    const p = new FakeMirror({ kiosk: 'app' });
    const phases: MirrorPhase[] = [];
    const res = await installMirror(p, { apk, haUrl: 'http://homeassistant.local:8123', sleep: noSleep, onPhase: (ph) => phases.push(ph) });
    expect(res).toEqual({
      app: { name: '0.5.0-legacy', code: 600 }, home: 'set', homeCheck: { result: 'set', focused: OURS, left: [] },
      hidden: ['com.android.launcher3', 'com.nimrod.mirrorkiosk'], adminHomes: [], rotation: 3, previousHome: MIRROR_KIOSK,
      updates: { state: 'normal-app', privileged: false },
    });
    expect(phases).toEqual([{ step: 'apk', stage: 'copy' }, { step: 'apk', stage: 'install' }, { step: 'grant' }, { step: 'portrait' }, { step: 'start' }, { step: 'home' }]);
    const at = (c: string) => { const i = p.cmds.findIndex((x) => x.startsWith(c)); expect(i, c).toBeGreaterThanOrEqual(0); return i; };
    const order = ['push /data/local/tmp/mirror-app.apk', 'pm install -r -g /data/local/tmp/mirror-app.apk', `echo '${MIRROR_KIOSK}' > ${PREVIOUS_HOME}`,
      `pm grant ${PKG} android.permission.RECORD_AUDIO`, 'dpm set-active-admin', `settings put system user_rotation 3`,
      `am start -n ${GLASSWORKS.activity} --es url 'http://homeassistant.local:8123'`,
      `am start -a android.intent.action.MAIN -c android.intent.category.HOME ${PKG}`, // ours answers HOME before any other goes
      'pm hide com.android.launcher3', 'pm hide com.nimrod.mirrorkiosk'].map(at);
    expect(order).toEqual([...order].sort((a, b) => a - b));
    expect(p.cmds.lastIndexOf('am start -a android.intent.action.MAIN -c android.intent.category.HOME')).toBeGreaterThan(p.cmds.indexOf('pm hide com.nimrod.mirrorkiosk'));
    expect(p.files.get(PREVIOUS_HOME)).toBe(`${MIRROR_KIOSK}\n`);
    expect(p.files.get(HIDDEN_HOMES)).toBe('com.android.launcher3 com.nimrod.mirrorkiosk\n');
    expect(p.focused).toBe(OURS);
    expect(p.userRotation).toBe(3);
    expect(p.cmds.some((c) => c.startsWith('cmd ') || c.includes('disable') || c.includes('APPLICATION_DETAILS_SETTINGS'))).toBe(false);
  });

  it('a home app it can\'t hide leaves HOME on Android\'s chooser: unconfirmed, said plainly, not a wait', async () => {
    const p = new FakeMirror({ hideFails: true, kiosk: 'app' });
    const res = await installMirror(p, { apk, sleep: noSleep });
    expect(res).toMatchObject({ home: 'unconfirmed', homeCheck: { result: 'chooser', left: ['com.android.launcher3', 'com.nimrod.mirrorkiosk'] }, hidden: [] });
    // recorded before each pm hide: unhiding a package that never hid changes nothing
    expect(p.files.get(HIDDEN_HOMES)).toBe('com.android.launcher3 com.nimrod.mirrorkiosk\n');
  });

  it('on a Mirror Kiln flashed (a fresh userdata, no kiosk), hides the image\'s launcher only', async () => {
    const p = new FakeMirror({ kiosk: 'none' });
    const res = await installMirror(p, { apk, sleep: noSleep });
    expect(res).toMatchObject({ home: 'set', hidden: ['com.android.launcher3'], adminHomes: [], previousHome: LAUNCHER3 });
    expect(p.focused).toBe(OURS);
  });

  it('leaves a home app with an active device admin alone, as Android 6 refuses to hide it, and says so', async () => {
    const p = new FakeMirror(); // the kiosk as Glass Panes' userdata has it: its device admin on
    const res = await installMirror(p, { apk, sleep: noSleep });
    expect(res).toMatchObject({ home: 'unconfirmed', adminHomes: ['com.nimrod.mirrorkiosk'], hidden: ['com.android.launcher3'], homeCheck: { result: 'chooser' } });
    expect(p.cmds).not.toContain('pm hide com.nimrod.mirrorkiosk');
    expect(p.files.get(HIDDEN_HOMES)).toBe('com.android.launcher3\n');
  });

  it('records each home app before hiding it, so undo can unhide what an interrupted install hid', async () => {
    const p = new FakeMirror({ kiosk: 'app' });
    const run = p.run.bind(p);
    p.run = async (cmd: string) => { if (cmd === 'pm hide com.nimrod.mirrorkiosk') throw new Error('USB transfer failed'); return run(cmd); };
    await expect(installMirror(p, { apk, sleep: noSleep })).rejects.toThrow(/USB transfer failed/);
    expect(p.hidden.has('com.android.launcher3')).toBe(true);
    expect(p.files.get(HIDDEN_HOMES)).toBe('com.android.launcher3 com.nimrod.mirrorkiosk\n');
  });

  it('stops when the screen won\'t turn upright', async () => {
    const p = new FakeMirror({ rotationStuck: true });
    await expect(installMirror(p, { apk, sleep: noSleep })).rejects.toThrow(/didn't turn to portrait/);
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
    // the S1 app's id is not the Mirror's app
    await expect(installMirror(p, { apk: fakeApk('sh.edm.s1kiosk'), sleep: noSleep })).rejects.toThrow(/is sh\.edm\.s1kiosk, not Glassworks/);
    expect(p.cmds.filter((c) => WRITES.test(c))).toEqual([]);
  });

  it('an update with another app kept as home: hides it, with no Settings page or chooser to tap', async () => {
    const p = new FakeMirror({ installed: 500, kiosk: 'app' });
    p.pick(MIRROR_KIOSK); // Android keeps this pick: the home apps don't change on an update
    const res = await installMirror(p, { apk, sleep: noSleep });
    expect(res).toMatchObject({ home: 'set', previousHome: MIRROR_KIOSK });
    expect(p.focused).toBe(OURS);
  });

  it('an update keeps the home app recorded the first time, and the hidden ones', async () => {
    const p = new FakeMirror({ installed: 500, kiosk: 'app' });
    p.files.set(PREVIOUS_HOME, `${MIRROR_KIOSK}\n`);
    p.files.set(HIDDEN_HOMES, 'com.nimrod.mirrorkiosk\n');
    const res = await installMirror(p, { apk, sleep: noSleep });
    expect(res).toMatchObject({ home: 'set', previousHome: MIRROR_KIOSK });
    expect(p.cmds.some((c) => c.startsWith(`echo '${MIRROR_KIOSK}'`))).toBe(false);
    expect(p.files.get(HIDDEN_HOMES)).toBe('com.nimrod.mirrorkiosk com.android.launcher3\n');
  });
});

describe('undo', () => {
  it('takes back the device admin, removes Glassworks, and unhides the home app the Mirror had', async () => {
    const p = new FakeMirror({ kiosk: 'app' });
    await installMirror(p, { apk, sleep: noSleep });
    p.cmds = [];
    const phases: MirrorPhase[] = [];
    const res = await undoMirror(p, { sleep: noSleep, onPhase: (ph) => phases.push(ph) });
    expect(res).toEqual({ removed: true, home: 'set', previousHome: MIRROR_KIOSK, focused: MIRROR_KIOSK });
    expect(phases).toEqual([{ step: 'admin' }, { step: 'remove' }, { step: 'home' }]);
    expect(p.cmds.indexOf(`pm uninstall ${PKG}`)).toBeGreaterThan(p.cmds.lastIndexOf('dumpsys device_policy'));
    expect(p.cmds).toContain('pm unhide com.nimrod.mirrorkiosk');
    expect(p.installed).toBeUndefined();
    expect(p.focused).toBe(MIRROR_KIOSK);
    expect(p.files.has(PREVIOUS_HOME)).toBe(false);
    expect(p.files.get(HIDDEN_HOMES)).toBe('com.android.launcher3\n'); // the image's launcher stays hidden: no chooser
  });

  it('fails loudly when Glassworks keeps its device admin, and keeps the record', async () => {
    const p = new FakeMirror({ installed: 600, keepsAdmin: true });
    p.files.set(PREVIOUS_HOME, `${MIRROR_KIOSK}\n`);
    await expect(undoMirror(p, { sleep: noSleep })).rejects.toThrow(/DELETE_FAILED_DEVICE_POLICY_MANAGER.*Device administrators/);
    expect(p.installed).toBe(600);
    expect(p.files.has(PREVIOUS_HOME)).toBe(true);
  });

  it('without a record of the old home, unhides what Kiln hid and wants one of those in front', async () => {
    const p = new FakeMirror({ installed: 600, kiosk: 'app' });
    p.hidden.add('com.nimrod.mirrorkiosk');
    p.files.set(HIDDEN_HOMES, 'com.nimrod.mirrorkiosk\n');
    p.hidden.add('com.android.launcher3'); // hidden before Kiln: stays hidden
    const res = await undoMirror(p, { sleep: noSleep });
    expect(res).toEqual({ removed: true, home: 'set', focused: MIRROR_KIOSK });
    expect(p.files.has(HIDDEN_HOMES)).toBe(false);
  });

  it('keeps the record when HOME doesn\'t open the old home app, so a second undo can finish', async () => {
    const p = new FakeMirror({ installed: 600, kiosk: 'app' });
    p.files.set(PREVIOUS_HOME, `${MIRROR_KIOSK}\n`); // launcher3 still visible: two home apps, so the chooser
    expect(await undoMirror(p, { sleep: noSleep })).toMatchObject({ removed: true, home: 'unconfirmed', focused: 'android/com.android.internal.app.ResolverActivity' });
    expect(p.files.has(PREVIOUS_HOME)).toBe(true);
    expect(await checkMirror(p)).toMatchObject({ pendingHome: MIRROR_KIOSK }); // the checks screen offers undo again
    p.hidden.add('com.android.launcher3');
    expect(await undoMirror(p, { sleep: noSleep })).toEqual({ removed: false, home: 'set', previousHome: MIRROR_KIOSK, focused: MIRROR_KIOSK });
    expect(p.files.has(PREVIOUS_HOME)).toBe(false);
  });

  it('keeps both records when HOME fails to start, whatever is in front (Settings is no home screen)', async () => {
    const p = new FakeMirror({ installed: 600, kiosk: 'app' });
    p.hidden.add('com.nimrod.mirrorkiosk');
    p.files.set(HIDDEN_HOMES, 'com.nimrod.mirrorkiosk\n');
    const run = p.run.bind(p);
    p.run = async (cmd: string) => {
      if (cmd === 'am start -a android.intent.action.MAIN -c android.intent.category.HOME') { p.focused = 'com.android.settings/.Settings'; return { out: 'Error: Activity not started\n', code: 0 }; }
      return run(cmd);
    };
    expect(await undoMirror(p, { sleep: noSleep })).toMatchObject({ home: 'unconfirmed', focused: 'com.android.settings/.Settings' });
    expect(p.files.get(HIDDEN_HOMES)).toBe('com.nimrod.mirrorkiosk\n');
  });
});
