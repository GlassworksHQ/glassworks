// Image recipes are data from pull requests: Kiln refuses an unknown step, a file without its hash,
// a write before every file is checked, and any attempt to name, skip or move Kiln's safety steps.
import { describe, expect, it } from 'vitest';
import { SAFETY_STEPS, adbKeyStep, assertImages, imageFromLink, plan, sourceFile, systemKeyStep, validateImage, wifiStep, writtenAsIs, type FrameAccess, type ImageRecipe, type ImageStep } from '../src/images';

const FRAME: FrameAccess = { steps: ['download', 'patch', 'fastboot', 'flash', 'restart', 'first-start', 'wifi', 'wifi-on'], partitions: ['system', 'userdata'] };
const H = 'a'.repeat(64);
const link = (name: string) => ({ name, href: `https://example.com/${name}` });
const file = (name: string) => ({ name, url: `https://example.com/r/${name}`, refract: `demo/r1/${name}`, size: 4096, sha256: H });

const community = (o: Partial<ImageRecipe> = {}): ImageRecipe => ({
  id: 'demo-os', name: 'Demo OS', community: true, by: link('someone'), project: link('demo-os'), licence: 'MIT', summary: 'An image.',
  support: link('issues'), files: [file('system.img'), file('userdata.img')],
  steps: [
    { do: 'download', file: 'system.img' }, { do: 'download', file: 'userdata.img' }, { do: 'fastboot' },
    { do: 'flash', partition: 'system', file: 'system.img' }, { do: 'flash', partition: 'userdata', file: 'userdata.img' }, { do: 'restart' },
  ],
  ...o,
});
const glassworks = (): ImageRecipe => ({
  ...community(), id: 'glassworks', name: 'Glassworks', community: false, files: [file('system.img')],
  steps: [
    { do: 'download', file: 'system.img' }, { do: 'fastboot' }, { do: 'patch', file: 'system.img', makes: ['system', 'userdata'] },
    { do: 'flash', partition: 'system', file: 'system' }, { do: 'flash', partition: 'userdata', file: 'userdata' }, { do: 'restart' }, { do: 'first-start' },
  ],
});
const steps = (...s: unknown[]) => s as ImageStep[];

describe('a recipe', () => {
  it('a good community recipe and Glassworks pass', () => {
    expect(validateImage(community(), FRAME)).toEqual([]);
    expect(validateImage(glassworks(), FRAME)).toEqual([]);
    expect(() => assertImages([glassworks(), community()], FRAME)).not.toThrow();
  });

  it('refuses a step that is not in Kiln\'s library, or one the frame can\'t run', () => {
    const r = community({ steps: steps(...community().steps.slice(0, 3), { do: 'shell', cmd: 'rm -rf /' }, ...community().steps.slice(3)) });
    expect(validateImage(r, FRAME)).toContain('step 4: "shell" isn\'t a step in Kiln\'s library');
    expect(validateImage(community(), { ...FRAME, steps: FRAME.steps.filter((s) => s !== 'fastboot') })).toContain('step 3: this frame can\'t run "fastboot"');
    expect(validateImage(community({ steps: steps({ do: 'toString' }) }), FRAME)).toContain('step 1: "toString" isn\'t a step in Kiln\'s library');
  });

  it('refuses extra fields on a step, so data can\'t smuggle in options', () => {
    const r = community({ steps: steps(...community().steps.slice(0, 2), { do: 'fastboot', oem: 'unlock' }, ...community().steps.slice(3)) });
    expect(validateImage(r, FRAME)).toContain('step 3: "fastboot" takes no oem');
  });

  it('requires every file\'s SHA-256, size and Refract id', () => {
    const r = community({ files: [{ ...file('system.img'), sha256: '' }, { ...file('userdata.img'), size: 0, refract: '' }] });
    expect(validateImage(r, FRAME)).toEqual(expect.arrayContaining([
      'system.img: sha256 must be 64 lower-case hex digits',
      'userdata.img: size must be a whole number of bytes',
      'userdata.img: refract must be its id on Refract\'s allowlist',
    ]));
    expect(validateImage(community({ files: [{ ...file('system.img'), sha256: H.toUpperCase() }, file('userdata.img')] }), FRAME))
      .toContain('system.img: sha256 must be 64 lower-case hex digits');
    expect(validateImage(community({ files: [{ ...file('system.img'), url: 'http://example.com/system.img' }, file('userdata.img')] }), FRAME))
      .toContain('system.img: url must be https');
  });

  it('only writes checked files, after fastboot, to partitions the frame allows', () => {
    const unchecked = community({ steps: steps({ do: 'download', file: 'system.img' }, { do: 'fastboot' }, { do: 'flash', partition: 'system', file: 'system.img' },
      { do: 'flash', partition: 'userdata', file: 'userdata.img' }, { do: 'restart' }) });
    expect(validateImage(unchecked, FRAME)).toEqual(expect.arrayContaining(['step 4: userdata.img isn\'t a checked download or something Kiln made from one', 'userdata.img is never downloaded']));
    const boot = community({ steps: steps(...community().steps.slice(0, 3), { do: 'flash', partition: 'boot', file: 'system.img' }, { do: 'restart' }) });
    expect(validateImage(boot, FRAME)).toContain('step 4: this frame doesn\'t let a recipe write "boot"');
    const early = community({ steps: steps(...community().steps.slice(0, 2), { do: 'flash', partition: 'system', file: 'system.img' }, { do: 'restart' }) });
    expect(validateImage(early, FRAME)).toContain('step 3: flash needs the device in fastboot first');
  });

  it('downloads every file before it touches the device', () => {
    const late = community({ steps: steps({ do: 'download', file: 'system.img' }, { do: 'fastboot' }, { do: 'download', file: 'userdata.img' },
      { do: 'flash', partition: 'system', file: 'system.img' }, { do: 'flash', partition: 'userdata', file: 'userdata.img' }, { do: 'restart' }) });
    expect(validateImage(late, FRAME)).toContain('step 3: "download" must come before Kiln connects to the device: every file is checked first');
  });

  it('runs patch after fastboot, where Kiln runs it (it uses what fastboot reports): before it would be skipped', () => {
    const early = glassworks();
    const st = early.steps;
    expect(validateImage({ ...early, steps: [st[0]!, st[2]!, st[1]!, ...st.slice(3)] }, FRAME)).toContain('step 2: patch comes after fastboot and before restart');
  });

  it('keeps a community image as published but for the library\'s own changes: no patch, no first start with Kiln\'s key', () => {
    const r = community({ steps: steps({ do: 'download', file: 'system.img' }, { do: 'download', file: 'userdata.img' }, { do: 'patch', file: 'system.img', makes: ['system'] },
      ...community().steps.slice(2), { do: 'first-start' }) });
    expect(validateImage(r, FRAME)).toEqual(expect.arrayContaining([
      'step 3: a community image gets no patch: only the library\'s own changes (adb-key, system-key, wifi)',
      'step 8: Kiln\'s first start needs an image that trusts this browser\'s key: only Glassworks',
    ]));
  });

  it('must write something and end by restarting', () => {
    expect(validateImage(community({ steps: community().steps.slice(0, 3) }), FRAME)).toEqual(expect.arrayContaining(['the recipe writes nothing', 'the recipe must end by restarting the device']));
  });

  it('checks the id, the links and who it belongs to', () => {
    expect(validateImage(community({ id: 'Demo OS' }), FRAME)).toContain('id "Demo OS": lower case letters, digits and dashes only');
    expect(validateImage(community({ id: 'glassworks' }), FRAME)).toContain('id "glassworks" is Glassworks\' own');
    expect(validateImage(community({ support: { name: 'x', href: 'javascript:alert(1)' } }), FRAME)).toContain('support needs a name and an https link');
    expect(() => assertImages([community(), glassworks()], FRAME)).toThrow(/Glassworks comes first/);
    expect(() => assertImages([glassworks(), community(), community()], FRAME)).toThrow(/two images share an id/);
  });
});

describe('Kiln\'s safety steps', () => {
  it('a recipe can\'t name any of them', () => {
    for (const s of SAFETY_STEPS) {
      const r = community({ steps: steps(...community().steps.slice(0, 3), { do: s }, ...community().steps.slice(3)) });
      expect(validateImage(r, FRAME)).toContain(`step 4: "${s}" is Kiln's own safety step; a recipe can't list it`);
    }
  });

  it('wraps every recipe: checks, backup and the warning right after connecting, before anything else; the way back last', () => {
    const order = (r: ImageRecipe) => plan(r).map((s) => s.do);
    expect(order(community())).toEqual(['download', 'download', 'fastboot', 'checks', 'backup', 'confirm', 'flash', 'flash', 'restart', 'way-back']);
    expect(order(glassworks())).toEqual(['download', 'fastboot', 'checks', 'backup', 'confirm', 'patch', 'flash', 'flash', 'restart', 'first-start', 'way-back']);
  });

  it('are there even for a recipe that would skip them (validation refuses it, plan still wraps it)', () => {
    const bare = community({ steps: steps({ do: 'flash', partition: 'system', file: 'system.img' }) });
    expect(plan(bare).map((s) => s.do)).toEqual(['checks', 'backup', 'confirm', 'flash', 'way-back']);
  });

  it('the backup step records each file written as published, with its hash', () => {
    expect(writtenAsIs(community()).map((f) => f.name)).toEqual(['system.img', 'userdata.img']);
    expect(writtenAsIs(glassworks())).toEqual([]); // what Kiln builds is hashed as it is made
  });
});

describe('?image=<id>', () => {
  const images = [glassworks(), community()];
  it('names a community image the frame offers', () => {
    expect(imageFromLink(images, '?image=demo-os')?.name).toBe('Demo OS');
  });
  it('none, an unknown id or glassworks: the normal page', () => {
    expect(imageFromLink(images, '')).toBeUndefined();
    expect(imageFromLink(images, '?image=nope')).toBeUndefined();
    expect(imageFromLink(images, '?image=glassworks')).toBeUndefined();
  });
});

describe('the Wi-Fi steps', () => {
  /** A community image that saves the person's network and switches Wi-Fi on with its own published key. */
  const withWifi = (o: Partial<ImageRecipe> = {}): ImageRecipe => community({
    files: [file('system.img'), file('userdata.img'), file('adbkey')],
    steps: [
      { do: 'download', file: 'system.img' }, { do: 'download', file: 'userdata.img' }, { do: 'download', file: 'adbkey' }, { do: 'fastboot' },
      { do: 'wifi', file: 'userdata.img', template: 'system.img', makes: 'userdata+wifi' },
      { do: 'flash', partition: 'system', file: 'system.img' }, { do: 'flash', partition: 'userdata', file: 'userdata+wifi' },
      { do: 'restart' }, { do: 'wifi-on', key: 'adbkey' },
    ],
    ...o,
  });

  it('a community image may save the network and switch Wi-Fi on, inside Kiln\'s safety steps', () => {
    expect(validateImage(withWifi(), FRAME)).toEqual([]);
    expect(plan(withWifi()).map((s) => s.do)).toEqual(['download', 'download', 'download', 'fastboot', 'checks', 'backup', 'confirm',
      'wifi', 'flash', 'flash', 'restart', 'wifi-on', 'way-back']);
    expect(wifiStep(withWifi(), 'userdata+wifi')?.file).toBe('userdata.img');
    expect(wifiStep(withWifi(), 'userdata.img')).toBeUndefined();
  });

  it('wifi takes only checked files and names one new file; no extra fields, so it can\'t be told to write anything else', () => {
    const steps = withWifi().steps;
    const at = (i: number, step: unknown) => withWifi({ steps: steps.map((s, k) => (k === i ? step : s)) as ImageStep[] });
    expect(validateImage(at(4, { do: 'wifi', file: 'userdata.img', template: 'system.img', makes: 'userdata+wifi', path: '/system/build.prop' }), FRAME))
      .toContain('step 5: "wifi" takes no path');
    expect(validateImage(at(4, { do: 'wifi', file: 'nope.img', template: 'system.img', makes: 'userdata+wifi' }), FRAME)).toContain('step 5: wifi needs nope.img downloaded first, or made by adb-key');
    expect(validateImage(at(4, { do: 'wifi', file: 'userdata.img', template: 'system.img', makes: 'userdata.img' }), FRAME)).toContain('step 5: wifi must name a new file it makes');
    expect(validateImage(at(4, { do: 'wifi', file: 'userdata.img', template: 'system.img', makes: '' }), FRAME)).toContain('step 5: wifi must name a new file it makes');
    // before fastboot it would run before Kiln's warning, where the install skips it: refused
    const early = withWifi({ steps: [steps[0]!, steps[1]!, steps[2]!, steps[4]!, steps[3]!, ...steps.slice(5)] });
    expect(validateImage(early, FRAME)).toContain('step 4: wifi comes after fastboot and before restart');
  });

  it('wifi-on only after the restart, only after a wifi step, only with a checked key, and takes no command', () => {
    const steps = withWifi().steps;
    expect(validateImage(withWifi({ steps: [...steps.slice(0, 7), { do: 'wifi-on', key: 'adbkey' }, steps[7]!] }), FRAME)).toContain('step 8: wifi-on comes after restart');
    expect(validateImage(withWifi({ steps: [...steps.slice(0, 8), { do: 'wifi-on', key: 'other' }] }), FRAME)).toContain('step 9: wifi-on\'s key must be a checked download');
    expect(validateImage(withWifi({ steps: [...steps.slice(0, 8), { do: 'wifi-on', key: 'adbkey', cmd: 'reboot' } as unknown as ImageStep] }), FRAME)).toContain('step 9: "wifi-on" takes no cmd');
    const noWifi = withWifi({ steps: [...steps.slice(0, 4), { do: 'flash', partition: 'system', file: 'system.img' }, { do: 'flash', partition: 'userdata', file: 'userdata.img' }, { do: 'restart' }, { do: 'wifi-on', key: 'adbkey' }] });
    expect(validateImage(noWifi, FRAME)).toContain('step 8: wifi-on needs a wifi step first: there is no network to join otherwise');
  });

  it('a frame that doesn\'t run them refuses them', () => {
    expect(validateImage(withWifi(), { ...FRAME, steps: FRAME.steps.filter((s) => s !== 'wifi-on') })).toContain('step 9: this frame can\'t run "wifi-on"');
  });
});

describe('the adb-key step', () => {
  const KEYED: FrameAccess = { ...FRAME, steps: [...FRAME.steps, 'adb-key'] };
  /** A community image whose userdata gets this browser's key, then the network on top, then Wi-Fi on with this browser's key. */
  const keyed = (o: Partial<ImageRecipe> = {}): ImageRecipe => community({
    files: [file('system.img'), file('userdata.img')],
    steps: [
      { do: 'download', file: 'system.img' }, { do: 'download', file: 'userdata.img' }, { do: 'fastboot' },
      { do: 'adb-key', file: 'userdata.img', makes: 'userdata+key' },
      { do: 'wifi', file: 'userdata+key', template: 'system.img', makes: 'userdata+wifi' },
      { do: 'flash', partition: 'system', file: 'system.img' }, { do: 'flash', partition: 'userdata', file: 'userdata+wifi' },
      { do: 'restart' }, { do: 'wifi-on' },
    ],
    ...o,
  });

  it('puts the key in a copy, the network goes on that copy, and wifi-on uses this browser\'s key', () => {
    expect(validateImage(keyed(), KEYED)).toEqual([]);
    expect(plan(keyed()).map((s) => s.do)).toEqual(['download', 'download', 'fastboot', 'checks', 'backup', 'confirm',
      'adb-key', 'wifi', 'flash', 'flash', 'restart', 'wifi-on', 'way-back']);
    // what gets written, followed back to the checked download it comes from
    expect(sourceFile(keyed(), 'userdata+wifi')?.name).toBe('userdata.img');
    expect(sourceFile(keyed(), 'userdata+key')?.name).toBe('userdata.img');
    expect(sourceFile(keyed(), 'system.img')?.name).toBe('system.img');
    expect(sourceFile(keyed(), 'system')).toBeUndefined();
    expect(adbKeyStep(keyed(), 'userdata+key')?.file).toBe('userdata.img');
  });

  it('takes a checked download, names one new file, runs once between fastboot and restart, and takes no path', () => {
    const steps = keyed().steps;
    const at = (i: number, step: unknown) => keyed({ steps: steps.map((s, k) => (k === i ? step : s)) as ImageStep[] });
    expect(validateImage(at(3, { do: 'adb-key', file: 'userdata.img', makes: 'userdata+key', path: '/misc/adb/adb_keys' }), KEYED)).toContain('step 4: "adb-key" takes no path');
    expect(validateImage(at(3, { do: 'adb-key', file: 'nope.img', makes: 'userdata+key' }), KEYED)).toContain('step 4: adb-key needs nope.img downloaded first');
    expect(validateImage(at(3, { do: 'adb-key', file: 'userdata.img', makes: 'userdata.img' }), KEYED)).toContain('step 4: adb-key must name a new file it makes');
    const early = keyed({ steps: [steps[0]!, steps[1]!, steps[3]!, steps[2]!, ...steps.slice(4)] });
    expect(validateImage(early, KEYED)).toContain('step 3: adb-key comes after fastboot and before restart');
    const twice = keyed({ steps: [...steps.slice(0, 4), { do: 'adb-key', file: 'userdata.img', makes: 'userdata+key2' }, ...steps.slice(4)] });
    expect(validateImage(twice, KEYED)).toContain('step 5: the adb key is put in once');
    expect(validateImage(keyed(), FRAME)).toContain('step 4: this frame can\'t run "adb-key"');
  });

  it('wifi-on with no key needs an adb-key step first; wifi starts only from a download or adb-key\'s copy', () => {
    const steps = keyed().steps;
    const unkeyed = keyed({ steps: [...steps.slice(0, 3), { do: 'wifi', file: 'userdata.img', template: 'system.img', makes: 'userdata+wifi' }, ...steps.slice(5)] });
    expect(validateImage(unkeyed, KEYED)).toContain('step 8: wifi-on without a key connects with this browser\'s, so it needs an adb-key step first');
    // the network saved on the published userdata, not adb-key's copy: what gets written has no browser key
    const besideKey = keyed({ steps: steps.map((s) => (s.do === 'wifi' ? { ...s, file: 'userdata.img' } : s)) });
    expect(validateImage(besideKey, KEYED)).toEqual(expect.arrayContaining([
      'adb-key makes userdata+key, but nothing writes it: flash it, or a wifi step\'s copy of it',
      'wifi starts from userdata.img, not the copy with this browser\'s key: what it makes would leave the key out',
    ]));
    // the network saved on the keyed copy, but userdata written as published: neither copy reaches the Mirror
    const wifiUnwritten = keyed({ steps: steps.map((s) => (s.do === 'flash' && s.partition === 'userdata' ? { ...s, file: 'userdata.img' } : s)) });
    expect(validateImage(wifiUnwritten, KEYED)).toEqual(expect.arrayContaining([
      'adb-key makes userdata+key, but nothing writes it: flash it, or a wifi step\'s copy of it',
      'wifi makes userdata+wifi, but nothing writes it: flash it',
    ]));
    // the keyed copy written, then the published userdata over it: the Mirror would keep the published key
    const overwritten = keyed({ steps: [...steps.slice(0, 7), { do: 'flash', partition: 'userdata', file: 'userdata.img' }, ...steps.slice(7)] });
    expect(validateImage(overwritten, KEYED)).toContain('step 8: userdata is written once');
    const fromBuilt = keyed({ steps: steps.map((s) => (s.do === 'wifi' ? { ...s, file: 'system' } : s)) });
    expect(validateImage(fromBuilt, KEYED)).toContain('step 5: wifi needs system downloaded first, or made by adb-key');
  });
});

describe('the system-key step', () => {
  const KEYED: FrameAccess = { ...FRAME, steps: [...FRAME.steps, 'system-key', 'adb-key'] };
  /** A community image whose system lets in this browser's key in place of the one it hardcodes, and whose userdata lists it. */
  const keyed = (o: Partial<ImageRecipe> = {}): ImageRecipe => community({
    files: [file('system.img'), file('userdata.img')],
    steps: [
      { do: 'download', file: 'system.img' }, { do: 'download', file: 'userdata.img' }, { do: 'fastboot' },
      { do: 'system-key', file: 'system.img', makes: 'system+key' },
      { do: 'adb-key', file: 'userdata.img', makes: 'userdata+key' },
      { do: 'flash', partition: 'system', file: 'system+key' }, { do: 'flash', partition: 'userdata', file: 'userdata+key' },
      { do: 'restart' },
    ],
    ...o,
  });

  it('puts the key in a copy of the checked system image, which is what gets written', () => {
    expect(validateImage(keyed(), KEYED)).toEqual([]);
    expect(plan(keyed()).map((s) => s.do)).toEqual(['download', 'download', 'fastboot', 'checks', 'backup', 'confirm',
      'system-key', 'adb-key', 'flash', 'flash', 'restart', 'way-back']);
    expect(sourceFile(keyed(), 'system+key')?.name).toBe('system.img');
    expect(systemKeyStep(keyed(), 'system+key')?.file).toBe('system.img');
    expect(systemKeyStep(keyed(), 'userdata+key')).toBeUndefined();
    // made by Kiln, hashed as it reads the download: not a file written as published
    expect(writtenAsIs(keyed())).toEqual([]);
  });

  it('takes a checked download, names one new file, runs once between fastboot and restart, and takes no path', () => {
    const steps = keyed().steps;
    const at = (i: number, step: unknown) => keyed({ steps: steps.map((s, k) => (k === i ? step : s)) as ImageStep[] });
    expect(validateImage(at(3, { do: 'system-key', file: 'system.img', makes: 'system+key', path: '/framework/services.jar' }), KEYED)).toContain('step 4: "system-key" takes no path');
    expect(validateImage(at(3, { do: 'system-key', file: 'nope.img', makes: 'system+key' }), KEYED)).toContain('step 4: system-key needs nope.img downloaded first');
    expect(validateImage(at(3, { do: 'system-key', file: 'system.img', makes: 'userdata.img' }), KEYED)).toContain('step 4: system-key must name a new file it makes');
    expect(validateImage(at(3, { do: 'system-key', file: 'system.img', makes: 'userdata+key' }), KEYED)).toContain('step 5: adb-key must name a new file it makes');
    const early = keyed({ steps: [steps[0]!, steps[1]!, steps[3]!, steps[2]!, ...steps.slice(4)] });
    expect(validateImage(early, KEYED)).toContain('step 3: system-key comes after fastboot and before restart');
    const twice = keyed({ steps: [...steps.slice(0, 4), { do: 'system-key', file: 'system.img', makes: 'system+key2' }, ...steps.slice(4)] });
    expect(validateImage(twice, KEYED)).toContain('step 5: the system image\'s key is changed once');
    expect(validateImage(keyed(), FRAME)).toContain('step 4: this frame can\'t run "system-key"');
    // made, then not written: the device would keep the image's own key
    const unwritten = keyed({ steps: steps.map((s) => (s.do === 'flash' && s.partition === 'system' ? { ...s, file: 'system.img' } : s)) });
    expect(validateImage(unwritten, KEYED)).toContain('system-key makes system+key, but nothing writes it: flash it, or a wifi step\'s copy of it');
  });
});

describe('names a step makes', () => {
  it('are plain file names, never one of Object\'s own (constructor, toString…)', () => {
    const steps = (makes: string) => [
      { do: 'download', file: 'system.img' }, { do: 'download', file: 'userdata.img' }, { do: 'fastboot' },
      { do: 'wifi', file: 'userdata.img', template: 'system.img', makes },
      { do: 'flash', partition: 'system', file: 'system.img' }, { do: 'flash', partition: 'userdata', file: makes }, { do: 'restart' },
    ] as ImageStep[];
    expect(validateImage(community({ steps: steps('userdata+wifi') }), FRAME)).toEqual([]);
    for (const bad of ['constructor', 'toString', '__proto__', '../x', 'a b', '']) {
      expect(validateImage(community({ steps: steps(bad) }), FRAME), bad).toContain('step 4: wifi must name a new file it makes');
    }
  });
});
