// "Keep the panel's settings": the apps go back (Glassworks removed) only for a backup undo can restore.
import { describe, expect, it } from 'vitest';
import { s1Plus } from '@glassworks/aqara-s1-plus-installer';
import { BackupRefused, type BackupPlan } from '@glassworks/kiln-core';
import { GIVE_BACK_STEP, appsBackNote, giveBackApps } from '../src/app/undo';

describe('giving the apps back before an undo', () => {
  it('refuses an S1 backup without the splash before the apps go back', async () => {
    let gaveBack = 0;
    const files = new Map([['boot', new Uint8Array(4096)], ['vbmeta', new Uint8Array(4096)]]);
    const p = giveBackApps(s1Plus.backup, files, async () => { gaveBack++; });
    await expect(p).rejects.toThrow('this backup has no bootloader');
    await expect(p).rejects.toBeInstanceOf(BackupRefused);
    expect(gaveBack).toBe(0);
  });

  it('refuses a backup whose boot is already patched before the apps go back', async () => {
    let gaveBack = 0;
    const plan: BackupPlan = { ...s1Plus.backup, isPatched: async () => true };
    const files = new Map(s1Plus.backup.restore.map((p) => [p, new Uint8Array(16)]));
    await expect(giveBackApps(plan, files, async () => { gaveBack++; })).rejects.toThrow('already patched');
    expect(gaveBack).toBe(0);
  });

  it('gives the apps back for a backup undo can restore', async () => {
    let gaveBack = 0;
    const plan: BackupPlan = { ...s1Plus.backup, isPatched: async () => false };
    const files = new Map(s1Plus.backup.restore.map((p) => [p, new Uint8Array(16)]));
    await giveBackApps(plan, files, async () => { gaveBack++; });
    expect(gaveBack).toBe(1);
  });
});

describe('what the give-back says', () => {
  it('the step names both outcomes: removed, or turned off when Glassworks saved the Wi-Fi', () => {
    expect(GIVE_BACK_STEP[1]).toMatch(/removes Glassworks, or turns it off if it saved the panel's Wi-Fi/);
  });

  it('passes the give-back\'s result through', async () => {
    const plan: BackupPlan = { ...s1Plus.backup, isPatched: async () => false };
    const files = new Map(s1Plus.backup.restore.map((p) => [p, new Uint8Array(16)]));
    expect(await giveBackApps(plan, files, async () => 'disabled')).toBe('disabled');
  });

  it('says what happened to Glassworks, and only moves on when it is off', () => {
    const off = appsBackNote({ app: 'disabled', note: 'Glassworks is installed but turned off, so the panel keeps its Wi-Fi.' });
    expect(off).toMatchObject({ done: true, kind: 'ok', title: 'Aqara\'s apps are back' });
    expect(off.sub).toMatch(/^Glassworks is installed but turned off/);
    const on = appsBackNote({ app: 'running', note: 'Android would not turn Glassworks off. It is still installed, so the panel may start it again or ask which home screen to use.' });
    expect(on).toMatchObject({ done: false, kind: 'err', title: 'Aqara\'s apps are back, but Glassworks is still on' });
    expect(on.sub).toMatch(/would not turn Glassworks off[\s\S]*Try again/);
  });
});
