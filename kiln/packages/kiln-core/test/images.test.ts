// Image recipes are data from pull requests: Kiln refuses an unknown step, a file without its hash,
// a write before every file is checked, and any attempt to name, skip or move Kiln's safety steps.
import { describe, expect, it } from 'vitest';
import { SAFETY_STEPS, assertImages, imageFromLink, plan, validateImage, writtenAsIs, type FrameAccess, type ImageRecipe, type ImageStep } from '../src/images';

const FRAME: FrameAccess = { steps: ['download', 'patch', 'fastboot', 'flash', 'restart', 'first-start'], partitions: ['system', 'userdata'] };
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

  it('keeps a community image as published: no patch, no first start with Kiln\'s key', () => {
    const r = community({ steps: steps({ do: 'download', file: 'system.img' }, { do: 'download', file: 'userdata.img' }, { do: 'patch', file: 'system.img', makes: ['system'] },
      ...community().steps.slice(2), { do: 'first-start' }) });
    expect(validateImage(r, FRAME)).toEqual(expect.arrayContaining([
      'step 3: a community image goes on as its project publishes it, with no patch',
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
