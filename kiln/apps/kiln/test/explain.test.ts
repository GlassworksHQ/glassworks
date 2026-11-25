import { describe, expect, it } from 'vitest';
import { BackupRefused } from '@glassworks/kiln-core';
import { explain } from '../src/wizard/explain';

describe('a failure, in words', () => {
  it.each([
    [new DOMException('No device selected.', 'NotFoundError'), 'No panel in recovery mode'],
    [new Error('secure boot is enabled on this chip'), 'This panel only runs signed software'],
    [new Error('this does not look like an Aqara S1 Plus (product sunxi, boot 1, super 2). Nothing was changed.'), 'This isn\'t a panel Kiln recognizes'],
    [new Error('two reads of boot differ: USB is unreliable here.'), 'The USB connection isn\'t steady'],
    [new Error('transferIn: the device was disconnected'), 'The panel disconnected'],
    [new Error('magisk failed its SHA-256 check (got 00…): refusing to use it'), 'A download failed'],
    // the app from its release (wizard/assets.ts): a download, never "disconnected" for the "stall" in "install"
    [new Error('Couldn\'t check for releases: the release server answered 502'), 'A download failed'],
    [new Error('Couldn\'t download Glassworks 1.0.0 (the download proxy sent nothing for 30 s)'), 'A download failed'],
    [new Error('Couldn\'t verify the release checksum for 1.0.0 (the download proxy answered 404)'), 'A download failed'],
    [new Error('The downloaded file didn\'t match the published release, so Kiln discarded it'), 'A download failed'],
    [new Error('There\'s no Glassworks release to install yet'), 'There\'s no release to install yet'],
    [new Error('something odd'), 'Something went wrong'],
  ])('%s', (e, title) => {
    const f = explain(e, 2, 'none');
    expect(f.title).toBe(title);
    expect(f.body.startsWith('Nothing was written.')).toBe(true);
    expect(f.wrote).toBe(false);
  });

  it('a backup undo refuses (As it came from Aqara): the refusal, not "something went wrong"', () => {
    const why = 'this backup\'s splash is already ours, not the original: use the one from your first install';
    const f = explain(new BackupRefused(why), 2, 'none');
    expect(f.title).toBe('This backup can\'t undo the install');
    expect(f.body).toBe('Nothing was written. Choose the backup from your first install, or follow the manual restore guide.');
    expect(f.raw).toBe(why);
    expect(f.wrote).toBe(false);
    // after an earlier try changed the panel: still the refusal's way on, not "use Undo" with this backup again
    const after = explain(new BackupRefused(why), 2, 'none', false, 'written');
    expect(after.title).toBe('This backup can\'t undo the install');
    expect(after.body).toBe('This attempt wrote nothing; an earlier try did. Choose the backup from your first install, or follow the manual restore guide.');
    expect(after.wrote).toBe(true); // the Undo screen is where another backup is picked
  });

  it('after a verified write, says so and points to Undo, whatever the error', () => {
    const f = explain(new Error('transferIn: the device was disconnected'), 2, 'verified');
    expect(f.title).toBe('The panel disconnected');
    expect(f.body).toBe('Part of the new software was written. Put the panel back into recovery mode and use Undo with your saved backup (in this browser or your backup ZIP file).');
    expect(f.wrote).toBe(true);
    expect(f.tips[0]![0]).toMatch(/Recovery mode still works/);
  });

  it('during setup, the new software is already in place', () => {
    const f = explain(new Error('the panel did not finish starting up'), 3, 'verified', true);
    expect(f.body).toBe('The new software is in place. Unplug it and plug it back in, then choose Finish setting up.');
    expect(f.wrote).toBe(false);
  });

  it('a closed device list, with no earlier write: nothing was written', () => {
    const f = explain(new DOMException('No device selected.', 'NotFoundError'), 1, 'none');
    expect(f.body).toBe('Nothing was written. Unplug it, hold the recovery button on the board first, then plug it in and keep holding for 10 seconds.');
    expect(f.wrote).toBe(false);
  });

  it('after an earlier try wrote, an attempt that wrote nothing says so, and keeps Undo', () => {
    const closed = explain(new DOMException('No device selected.', 'NotFoundError'), 1, 'none', false, 'written');
    expect(closed.title).toBe('No panel in recovery mode');
    expect(closed.body).toBe('This attempt wrote nothing; an earlier try did. Unplug it, hold the recovery button on the board first, then plug it in and keep holding for 10 seconds. Then use Undo with your backup.');
    expect(closed.wrote).toBe(true);
    expect(closed.tips[0]![0]).toMatch(/Recovery mode still works/);
    const step2 = explain(new Error('transferIn: the device was disconnected'), 2, 'none', false, 'written');
    expect(step2.body).toBe('This attempt wrote nothing; an earlier try did. Put the panel back into recovery mode and use Undo with your saved backup (in this browser or your backup ZIP file).');
    expect(step2.wrote).toBe(true);
    expect(explain(new Error('x'), 2, 'verified', false, 'written').body).toMatch(/^Part of the new software was written\./); // this attempt's write comes first
  });

  it('after the apps were given back (or partly), says so, not that software was written, and keeps Undo', () => {
    const f = explain(new DOMException('No device selected.', 'NotFoundError'), 1, 'none', false, 'apps');
    expect(f.body).toBe('This attempt wrote nothing; the panel\'s apps were changed earlier. Unplug it, hold the recovery button on the board first, then plug it in and keep holding for 10 seconds. Then use Undo with your backup.');
    expect(f.wrote).toBe(true);
  });

  it('on an installed panel (the setup step), an earlier write changes nothing: the new software is in place', () => {
    const f = explain(new Error('the panel did not finish starting up'), 3, 'none', true, 'written');
    expect(f.body).toBe('The new software is in place. Unplug it and plug it back in, then choose Finish setting up.');
    expect(f.wrote).toBe(false);
    expect(f.tips[0]![0]).toBe('Try another cable or USB port.');
  });

  it('keeps the raw message for More help, but not for a cancelled picker', () => {
    expect(explain(new Error('boom'), 2, 'none').raw).toBe('boom');
    expect(explain(new DOMException('No device selected.', 'NotFoundError'), 1, 'none').raw).toBe('');
  });

  it('after a write started but before it was verified, doesn\'t say nothing was written', () => {
    const f = explain(new Error('transferOut: the device was disconnected'), 2, 'maybe');
    expect(f.body).toBe('Part of the new software may have been written. Put the panel back into recovery mode and use Undo with your saved backup (in this browser or your backup ZIP file).');
    expect(f.wrote).toBe(true);
    expect(f.tips[0]![0]).toMatch(/Recovery mode still works/);
  });

  it('on an installed panel, a cancelled picker is "nothing was picked", not recovery mode', () => {
    const f = explain(new DOMException('No device selected.', 'NotFoundError'), 3, 'verified', true);
    expect(f.title).toBe('Nothing was picked');
    expect(f.body).toBe('The new software is in place. Try again, and pick the panel in the browser\'s list.');
    expect(f.body).not.toMatch(/recovery mode|board button/);
  });
});

describe('a failure during a write, through the session', () => {
  it('says the software may have been written once flashing began, before any write is verified', async () => {
    const [{ demoSession }, { demoFrame }] = await Promise.all([import('../src/wizard/demo'), import('./s1-frame')]);
    const s = demoSession(demoFrame, 'connect');
    s.store.dispatch({ type: 'start', flow: 'install', now: 0 });
    s.store.dispatch({ type: 'progress', p: { overall: 0.62, phase: 'flash', phasePct: 0.1, title: 'Turning off verified boot', sub: 'Writing vbmeta' } });
    (s as unknown as { fail(at: number, e: unknown): void }).fail(2, new Error('transferOut: the device was disconnected'));
    expect(s.store.state.wrote).toBe(false);
    expect(s.store.state.failure?.body).toMatch(/^Part of the new software may have been written\./);
    s.close();
  });
});

describe('the panel\'s history in a failure', () => {
  it('on an installed panel (set-up), an earlier write changes neither the words nor the way on', () => {
    const closed = new DOMException('No device selected.', 'NotFoundError');
    for (const at of [1, 3]) expect(explain(closed, at, 'none', true, 'written')).toEqual(explain(closed, at, 'none', true));
    const refused = new BackupRefused('this backup has no boot');
    expect(explain(refused, 3, 'none', true, 'written').body).toBe(explain(refused, 3, 'none', true).body);
  });

  it('when the tab can\'t remember earlier tries, it neither claims nothing was written nor sends to Undo', () => {
    const f = explain(new Error('transferIn: the device was disconnected'), 2, 'none', false, 'unknown');
    expect(f.body).toBe('This attempt wrote nothing. Kiln can\'t tell if an earlier try in this browser tab did. Plug the cable straight into this computer or try another USB port, then try again.');
    expect(f.wrote).toBe(false);
  });
});
