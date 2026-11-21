// How complete Glassworks is on each frame (#423): the checklist's rules, the word, and the facts in every frame.json.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { completeness, factProblems, keyProblems, type CheckKey, type FrameDesign, type FrameFacts, type FrameRun } from '../src';

const FRAMES = join(__dirname, '../../../frames');
const ids = readdirSync(FRAMES, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name);
const read = (id: string) => JSON.parse(readFileSync(join(FRAMES, id, 'design', 'frame.json'), 'utf8')) as FrameDesign;

/** A frame where every item is ticked. */
const FULL: FrameFacts = {
  install: [],
  silentUpdates: "Yes: the boot image's root update service installs them",
  hardware: [],
  kiosk: { onlyHome: true, noVendorUi: true, survivesReboot: true, sleepsAndWakes: true },
  setup: ['screen'],
  branding: { bootAnimation: true, vendorLogo: false },
  panelRuns: true,
};
const rating = { value: 'yes' };
const frame = (o: Partial<FrameDesign> = {}): FrameDesign => ({
  id: 'a-panel', kind: 'full', name: 'A Panel', status: 'beta', summary: '', kiln: '/a-panel', specs: [], panes: {}, install: { how: '' },
  model: {} as FrameDesign['model'],
  ratings: Object.fromEntries(['difficulty', 'reversible', 'modifiesAndroid', 'root', 'opensDevice', 'vendorApps', 'wipes', 'backup', 'updates'].map((k) => [k, rating])) as FrameDesign['ratings'],
  facts: FULL,
  ...o,
});
const item = (f: FrameDesign, key: CheckKey, o?: Parameters<typeof completeness>[1]) => completeness(f, o).items.find((i) => i.key === key)!;
const word = (f: FrameDesign, o?: Parameters<typeof completeness>[1]) => completeness(f, { clarity: 'B', ...o }).word;
const facts = (x: FrameFacts) => frame({ facts: { ...FULL, ...x } });

describe('the word', () => {
  it('is Full when every item that counts is ticked', () => {
    expect(word(frame())).toBe('full');
    expect(completeness(frame(), { clarity: 'B' }).items.every((i) => i.state === 'yes')).toBe(true);
  });

  it('counts install, silent updates, hardware, kiosk and setup, and only those', () => {
    expect(completeness(frame()).items.filter((i) => i.counts).map((i) => i.key)).toEqual(['install', 'silentUpdates', 'hardware', 'kiosk', 'setup']);
  });

  it('stays Full when branding and proof are missing: they are shown and don\'t count', () => {
    const f = facts({ branding: { bootAnimation: false }, panelRuns: false });
    expect(completeness(f).items.filter((i) => !i.counts).map((i) => [i.key, i.state])).toEqual([['recovery', 'yes'], ['branding', 'no'], ['proof', 'no']]);
    expect(word(f, { clarity: undefined })).toBe('full');
  });

  it.each([
    ['install', { install: ['usb-debugging'] }],
    ['silent updates', { silentUpdates: 'No: Android 11 asks for a tap on each update' }],
    ['hardware', { hardware: ['the camera'] }],
    ['kiosk', { kiosk: { ...FULL.kiosk, noVendorUi: false } }],
    ['setup', { setup: ['computer'] }],
  ] as const)('is Good when %s is missing and it installs from Kiln', (_, x) => {
    expect(word(facts(x as FrameFacts))).toBe('good');
  });

  it('is Basic when something that counts is missing and it doesn\'t install from Kiln', () => {
    expect(word(frame({ kiln: undefined }))).toBe('basic');
  });

  it('is none while the frame doesn\'t ship, however complete its facts', () => {
    expect(word(frame({ status: 'planned' }))).toBeNull();
    expect(word(frame({ status: 'development' }))).toBeNull();
    expect(word(frame({ status: 'alpha' }))).toBe('full');
  });

  it('shows an n/a on an item that is shown only, with its reason', () => {
    const f = facts({ branding: undefined, na: ['branding'], notes: { branding: 'It has no screen at power-on.' } });
    expect(item(f, 'branding')).toMatchObject({ state: 'na', reason: 'It has no screen at power-on.' });
    expect(word(f)).toBe('full');
  });

  it('never lets an n/a on an item that counts earn a word: no Full without Kiln', () => {
    const f = frame({ kiln: undefined, facts: { ...FULL, na: ['install'], notes: { install: 'It comes with Glassworks.' } } });
    expect(item(f, 'install')).toMatchObject({ state: 'no', reason: 'Not in Kiln yet.' });
    expect(word(f)).toBe('basic');
  });

  it('says it is unverified when an item that counts isn\'t from a real unit, and not for one that is shown only', () => {
    expect(completeness(facts({ unverified: ['kiosk'] })).unverified).toBe(true);
    expect(completeness(facts({ unverified: ['branding'] })).unverified).toBe(false);
    expect(completeness(frame({ unverified: true })).unverified).toBe(true);
    expect(completeness(frame({ status: 'planned', unverified: true })).unverified).toBe(false);
  });
});

describe('each item', () => {
  it('install: needs Kiln, a way back and nothing to tap on the device', () => {
    const screen = "From Kiln, with nothing to tap on the device's screen and a way back.";
    expect(item(frame({ ratings: { ...frame().ratings, opensDevice: { value: 'no' } } }), 'install')).toMatchObject({ state: 'yes', reason: screen });
    // the S1's FEL button: opening the case is a physical step, which the install ratings show, not a tap
    expect(item(frame(), 'install').reason).toBe(`${screen} Opening it to press a button is a physical step, not a tap on its screen.`);
    expect(item(frame({ kiln: undefined }), 'install').reason).toBe('Not in Kiln yet.');
    expect(item(frame({ ratings: { ...frame().ratings, reversible: { value: 'no' } } }), 'install').reason).toBe('No way back.');
    expect(item(frame({ ratings: { ...frame().ratings, reversible: { value: 'partly' } } }), 'install').state).toBe('yes');
    expect(item(facts({ install: ['usb-debugging', 'home-pick'] }), 'install').reason).toBe('On the device: turn on USB debugging and pick Glassworks as the home app.');
    expect(item(facts({ install: undefined }), 'install')).toMatchObject({ state: 'no', reason: 'Taps on the device not checked yet.' });
  });

  it('silent updates: ticked only on the sensor\'s yes; a tap on any Android doesn\'t count', () => {
    expect(item(frame(), 'silentUpdates')).toMatchObject({ state: 'yes', reason: "The boot image's root update service installs them." });
    expect(item(facts({ silentUpdates: 'No: Android 8 asks for a tap on each update' }), 'silentUpdates')).toMatchObject({ state: 'no', reason: 'Android 8 asks for a tap on each update.' });
    expect(item(facts({ silentUpdates: 'No: Android needs “Install unknown apps” allowed for the app' }), 'silentUpdates').state).toBe('no');
    expect(item(facts({ silentUpdates: undefined }), 'silentUpdates')).toMatchObject({ state: 'no', reason: 'No panel has reported it yet.' });
  });

  it('hardware: what it provides needs a pane that runs it here, and what the frame says is missing fails it', () => {
    expect(item(facts({ hardware: [] }), 'hardware')).toMatchObject({ state: 'yes', reason: 'Everything it has works.' });
    expect(item(frame({ provides: ['camera'] }), 'hardware').state).toBe('yes');
    expect(item(frame({ provides: ['camera'], status: 'planned' }), 'hardware').reason).toBe('Not yet: a camera.');
    expect(item(frame({ provides: ['camera'], panes: { camera: { support: 'planned' } } }), 'hardware').reason).toBe('Not yet: a camera.');
    expect(item(facts({ hardware: ['the light sensor', 'the case button'] }), 'hardware').reason).toBe('Not yet: the light sensor and the case button.');
    expect(item(frame({ provides: [], facts: { ...FULL, hardware: undefined } }), 'hardware').reason).toBe('Not checked on a real unit yet.');
    expect(item(frame({ provides: undefined, facts: { ...FULL, hardware: undefined } }), 'hardware').reason).toBe('Depends on the device.');
  });

  it('kiosk: all four checks, saying which fail and which nobody checked', () => {
    expect(item(frame(), 'kiosk').state).toBe('yes');
    expect(item(facts({ kiosk: { onlyHome: true, noVendorUi: false } }), 'kiosk').reason).toBe(
      'Not yet: no vendor screens. Not checked: back after a reboot and the screen sleeps and wakes.',
    );
  });

  it('setup: a way that needs no computer', () => {
    expect(item(facts({ setup: ['screen', 'bluetooth', 'computer'] }), 'setup').reason).toBe('On its own screen, or over Bluetooth.');
    expect(item(facts({ setup: ['hotspot'] }), 'setup').state).toBe('yes');
    expect(item(facts({ setup: ['computer'] }), 'setup')).toMatchObject({ state: 'no', reason: 'Only from a computer.' });
  });

  it('recovery: back to stock, or at least to a working state', () => {
    expect(item(frame({ ratings: { ...frame().ratings, reversible: { value: 'yes', detail: 'Uninstall the app' } } }), 'recovery').reason).toBe('Uninstall the app.');
    expect(item(frame({ ratings: { ...frame().ratings, reversible: { value: 'partly' } } }), 'recovery').reason).toBe('Back to a working state.');
    expect(item(frame({ ratings: { ...frame().ratings, reversible: { value: 'unknown' } } }), 'recovery').state).toBe('no');
  });

  it('branding: our boot animation and no maker\'s logo; a tested device keeps the maker\'s', () => {
    expect(item(frame(), 'branding').state).toBe('yes');
    expect(item(facts({ branding: { bootAnimation: true } }), 'branding').reason).toBe("Our boot animation; the logo at power-on isn't checked yet.");
    expect(item(facts({ branding: { bootAnimation: true, vendorLogo: true } }), 'branding').state).toBe('no');
    expect(item(facts({ branding: undefined }), 'branding').reason).toBe("The maker's boot screens stay.");
  });

  it('proof: Clarity graded and the panel runs done', () => {
    expect(item(frame(), 'proof', { clarity: 'D' })).toMatchObject({ state: 'yes', reason: 'Clarity D, and its panel runs are done.' });
    expect(item(facts({ panelRuns: false }), 'proof').reason).toBe('Clarity not graded on a real unit yet. Panel runs not done yet.');
  });

  it('shows the frame\'s own note next to the reason', () => {
    expect(item(facts({ notes: { setup: 'It has no touch.' } }), 'setup').note).toBe('It has no touch.');
  });
});

describe('a tested device', () => {
  const run: FrameRun = {
    on: '2026-11-18',
    plan: { model: 'A Tablet', android: '14', sdk: 34, abi: 'arm64-v8a', app: 'dev.glassworks.generic_android' },
    install: { grants: { microphone: 'granted' }, silentUpdates: 'allowed', home: { how: 'set-home-activity', state: 'set' }, setupPage: true },
    panel: { silentUpdates: 'Yes: Android 12 or newer lets the app update itself' },
    seen: { hardware: [], kiosk: { noVendorUi: true, survivesReboot: true, sleepsAndWakes: true }, setup: ['screen'] },
  };
  const tested = (r: Partial<FrameRun> = {}, o: Partial<FrameDesign> = {}) =>
    frame({ kind: 'tested', base: 'generic-android', kiln: undefined, facts: undefined, run: { ...run, ...r }, ...o });
  const base = { kiln: '/android' };

  it('takes its facts from its run, and installs from its base\'s Kiln route', () => {
    const c = completeness(tested(), { base });
    expect(Object.fromEntries(c.items.map((i) => [i.key, i.state]))).toEqual({
      install: 'no', silentUpdates: 'yes', hardware: 'yes', kiosk: 'yes', setup: 'yes', recovery: 'yes', branding: 'no', proof: 'no',
    });
    expect(c.items[0]!.reason).toBe('On the device: turn on USB debugging.');
    expect(c.word).toBe('good');
    expect(word(tested(), {})).toBe('basic');
  });

  it('counts Android\'s home chooser as a tap, and a refused microphone as missing hardware', () => {
    const r = tested({ install: { ...run.install, home: { how: 'chooser', state: 'set' }, grants: { microphone: 'refused' } }, seen: { ...run.seen, hardware: undefined } });
    expect(item(r, 'install').reason).toMatch(/pick Glassworks as the home app/);
    expect(item(r, 'hardware').reason).toBe('Not yet: the microphone: Android refused it.');
  });

  it('lets what the person saw later supersede a refusal at install', () => {
    const refused = { ...run.install, grants: { microphone: 'refused' as const } };
    // granted by hand after the install, and seen working
    expect(item(tested({ install: refused, seen: { ...run.seen, hardware: [] } }), 'hardware')).toMatchObject({ state: 'yes' });
    // still refused when they looked: they write it down
    expect(item(tested({ install: refused, seen: { ...run.seen, hardware: ['the microphone: Android refused it'] } }), 'hardware').reason)
      .toBe('Not yet: the microphone: Android refused it.');
  });

  it('fails kiosk when Kiln couldn\'t confirm the home screen', () => {
    const r = tested({ install: { ...run.install, home: { how: 'set-home-activity', state: 'unconfirmed' } } });
    expect(item(r, 'kiosk')).toMatchObject({ state: 'no', reason: 'Not yet: the only home screen.' });
    expect(item(tested(), 'kiosk').state).toBe('yes');
  });

  it('lets a later look at the home screen supersede Kiln\'s unconfirmed one', () => {
    const r = tested({ install: { ...run.install, home: { how: 'chooser', state: 'unconfirmed' } }, seen: { ...run.seen, kiosk: { ...run.seen.kiosk, onlyHome: true } } });
    expect(factProblems({ kind: 'tested', run: { ...run, seen: { kiosk: { onlyHome: true } } } })).toEqual([]);
    expect(item(r, 'kiosk').state).toBe('yes');
    expect(item(r, 'install').reason).toMatch(/pick Glassworks as the home app/);
  });

  it('says nobody read the sensor when the run has no panel report', () => {
    expect(item(tested({ panel: undefined }), 'silentUpdates')).toMatchObject({ state: 'no', reason: 'No panel has reported it yet.', unverified: false });
  });

  it('says its hardware isn\'t checked when the run saw none: it is one device, not many', () => {
    expect(item(tested({ seen: {} }), 'hardware').reason).toBe('Not checked on a real unit yet.');
  });

  it('is unverified, item by item, until the run has a day', () => {
    const c = completeness(tested({ on: undefined }), { base });
    expect(c.items.every((i) => i.unverified)).toBe(true);
    expect(c.unverified).toBe(true);
    expect(item(tested({ on: undefined }), 'proof').reason).toMatch(/Panel runs not done yet/);
    expect(completeness(tested(), { base }).unverified).toBe(false);
  });

  it('says nothing has run only when the run has no day, not when a dated run is marked unverified', () => {
    expect(completeness(tested({ on: undefined }), { base }).noRun).toBe(true);
    const dated = completeness(tested({}, { unverified: true }), { base });
    expect(dated.items.every((i) => i.unverified)).toBe(true);
    expect(dated.noRun).toBe(false);
    expect(completeness(frame({ unverified: true })).noRun).toBe(false);
  });
});

describe('the frames', () => {
  it.each(ids)('%s: its facts pass', (id) => {
    expect(factProblems(read(id))).toEqual([]);
  });

  const clarity: Record<string, string | undefined> = { 'aqara-s1-plus': 'D' };
  const all = ids.map(read);
  const of = (id: string) => {
    const f = read(id);
    return completeness(f, { base: all.find((b) => b.id === f.base), clarity: clarity[id] });
  };

  it('gives each frame that ships its word (the Pixel Tablet through its base\'s route), and the S1 misses wall base buttons', () => {
    expect(Object.fromEntries(ids.map((id) => [id, of(id).word]))).toEqual({
      'aqara-s1-plus': 'good', 'generic-android': 'good', 'google-pixel-tablet': 'good', 'lululemon-mirror': 'good',
      'samsung-galaxy-tab-a-10-1-2016': 'good',
    });
    expect(of('aqara-s1-plus').items.filter((i) => i.state !== 'yes').map((i) => i.key)).toEqual(['hardware']);
  });

  it('takes the Pixel Tablet\'s checklist from the root\'s run on 2026-11-17, with nothing unverified', () => {
    const f = read('google-pixel-tablet');
    expect(f.run?.on).toBe('2026-11-17');
    expect(f.unverified).toBeUndefined();
    const c = of('google-pixel-tablet');
    expect(c.items.filter((i) => i.unverified)).toEqual([]);
    expect(Object.fromEntries(c.items.map((i) => [i.key, i.state]))).toEqual({
      install: 'no', silentUpdates: 'no', hardware: 'no', kiosk: 'no', setup: 'yes', recovery: 'yes', branding: 'no', proof: 'no',
    });
  });

  it.each(ids)('%s: has only frame.json\'s own fields', (id) => {
    expect(keyProblems(read(id))).toEqual([]);
  });
});

describe('factProblems', () => {
  it.each<[string, unknown, string]>([
    ['a sensor answer in its own words', { kind: 'full', facts: { silentUpdates: 'yes' } }, 'facts.silentUpdates: the panel\'s Silent updates sensor, word for word ("Yes: …" or "No: …")'],
    ['an unknown step', { kind: 'full', facts: { install: ['press-a-button'] } }, 'facts.install: should be a list of usb-debugging, home-pick'],
    ['an unknown kiosk check', { kind: 'full', facts: { kiosk: { fast: true } } }, 'facts.kiosk: should map onlyHome, noVendorUi, survivesReboot, sleepsAndWakes to true or false'],
    ['no way to set it up', { kind: 'full', facts: { setup: [] } }, 'facts.setup: should be a list of screen, hotspot, bluetooth, computer'],
    ['n/a without a reason', { kind: 'full', facts: { na: ['branding'] } }, 'facts.na: branding needs its reason in facts.notes.branding'],
    ...(['install', 'silentUpdates', 'hardware', 'kiosk', 'setup'] as const).map((k) => [`n/a on ${k}, which counts`, { kind: 'full', facts: { na: [k], notes: { [k]: 'A reason.' } } }, `facts.na: ${k} decides the word, so it can't be n/a`] as [string, unknown, string]),
    ['a verdict written by hand', { kind: 'full', facts: { word: 'full' } }, 'facts.word: not part of the record (no serials, fingerprints or addresses)'],
    ['a run on a full frame', { kind: 'full', run: {} }, 'run: only a tested device has one; record what this frame\'s panels show in facts'],
    ['facts on a tested device', { kind: 'tested', facts: {}, run: undefined }, 'facts: a tested device\'s come from its run on a real unit; record them in run'],
  ])('fails %s', (_, f, why) => {
    expect(factProblems(f as FrameDesign)).toContain(why);
  });

  it('fails a tested device without a run, unless the community tested it: its report is the record', () => {
    const why = ['run: a tested device records its run on a real unit (frames/README.md#a-tested-devices-run)'];
    expect(factProblems({ kind: 'tested' })).toEqual(why);
    expect(factProblems({ kind: 'tested', tested: { by: 'glassworks' } })).toEqual(why);
    expect(factProblems({ kind: 'tested', tested: { by: 'community' } })).toEqual([]);
  });

  it('marks every item of a community-tested device without a run unverified', () => {
    const c = completeness(frame({ kind: 'tested', base: 'generic-android', run: undefined, tested: { by: 'community', os: 'LineageOS' } }), { base: { kiln: '/android' } });
    expect(c.noRun).toBe(true);
    expect(c.items.every((i) => i.unverified)).toBe(true);
  });

  it('rejects a field frame.json doesn\'t have, such as a serial beside the run', () => {
    expect(keyProblems({ ...read('google-pixel-tablet'), serial: 'x' })).toEqual(['serial: not a frame.json field (frames/README.md#designframejson)']);
  });

  it('keeps serials and fingerprints out of a run', () => {
    const run = { ...read('google-pixel-tablet').run!, plan: { ...read('google-pixel-tablet').run!.plan, serial: 'x', fingerprint: 'y' } };
    expect(factProblems({ kind: 'tested', run })).toEqual([
      'run.plan.serial: not part of the record (no serials, fingerprints or addresses)',
      'run.plan.fingerprint: not part of the record (no serials, fingerprints or addresses)',
    ]);
  });

  it('rejects a field Kiln\'s home result doesn\'t have', () => {
    const good = read('google-pixel-tablet').run!;
    expect(factProblems({ kind: 'tested', run: { ...good, install: { ...good.install, home: { ...good.install.home, serial: 'example' } } } })).toEqual([
      'run.install.home.serial: not part of the record (no serials, fingerprints or addresses)',
    ]);
  });

  it('checks a run\'s day and its Kiln results', () => {
    const good = read('google-pixel-tablet').run!;
    expect(factProblems({ kind: 'tested', run: { ...good, on: '18 Nov' } })).toEqual(['run.on: should be the day it ran, YYYY-MM-DD']);
    expect(factProblems({ kind: 'tested', run: { ...good, install: { ...good.install, home: { how: 'tap', state: 'set' } } } })).toEqual([
      'run.install.home: should be { how: set-home-activity or chooser, state: set or unconfirmed }',
    ]);
  });
});
