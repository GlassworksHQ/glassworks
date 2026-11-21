// The device table (#403): a USB vendor ID and product name, or adb's manufacturer and model, to a brand,
// its steps to turn on USB debugging, and a known model's frame. Every brand's steps cite a page.
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { BRANDS, CHIP_VENDORS, GENERIC_STEPS, MODELS, identifyAdb, identifyPicked, identifyUsb } from '../src';

const FRAMES = join(__dirname, '../../..');

describe('from WebUSB, before debugging is on', () => {
  it('a Pixel Tablet: its brand, its steps, its frame', () => {
    const d = identifyUsb({ vendorId: 0x18d1, productName: 'Pixel Tablet' });
    expect(d).toMatchObject({ brand: { id: 'google' }, model: { name: 'Pixel Tablet', frame: 'google-pixel-tablet' }, tested: true, pick: false });
    expect(d.steps.unlock).toEqual({ path: ['Settings', 'About tablet'], tap: 'Build number' });
  });

  it('a Samsung in file-transfer mode: the brand, untested', () => {
    expect(identifyUsb({ vendorId: 0x04e8, productName: 'SAMSUNG_Android' })).toMatchObject({ brand: { id: 'samsung' }, tested: false, pick: false });
  });

  it('a model code in the product name', () => {
    expect(identifyUsb({ vendorId: 0x04e8, productName: 'SM-X200' })).toMatchObject({ model: { name: 'Galaxy Tab A8' }, tested: false });
  });

  it('a model code under another brand\'s vendor ID doesn\'t count', () => {
    expect(identifyUsb({ vendorId: 0x18d1, productName: 'SM-X200' }).model).toBeUndefined();
  });

  it('a no-name tablet with its chip maker\'s ID: ask for the brand', () => {
    expect(identifyUsb({ vendorId: 0x1f3a, productName: 'Android' })).toEqual({ steps: GENERIC_STEPS, tested: false, pick: true, why: 'It reports Allwinner, the maker of its chip, not its brand.' });
  });

  it.each([['', 'It doesn\'t say what it is.'], ['Android', 'It doesn\'t say what it is.'], ['Tab 10 Pro', 'Kiln doesn\'t know "Tab 10 Pro".']])('an unknown vendor, product name "%s": generic steps and ask', (productName, why) => {
    expect(identifyUsb({ vendorId: 0x1234, productName })).toEqual({ steps: GENERIC_STEPS, tested: false, pick: true, why });
  });

  it('the person picks a brand, or Other', () => {
    expect(identifyPicked('amazon')).toMatchObject({ brand: { id: 'amazon' }, pick: false, steps: { unlock: { tap: 'Serial Number' } } });
    expect(identifyPicked()).toEqual({ steps: GENERIC_STEPS, tested: false, pick: false });
  });
});

describe('from adb, once debugging is on', () => {
  it.each([
    ['Google', 'Pixel Tablet', 'google', 'Pixel Tablet'],
    ['Amazon', 'KFTRWI', 'amazon', 'Fire HD 10 (2021)'],
    ['samsung', 'SM-X210', 'samsung', 'Galaxy Tab A9+'],
    ['LENOVO', 'Lenovo TB-X606F', 'lenovo', undefined],
  ])('%s %s', (manufacturer, model, brand, name) => {
    const d = identifyAdb({ manufacturer, model });
    expect(d.brand?.id).toBe(brand);
    expect(d.model?.name).toBe(name);
  });

  it('a Galaxy Tab A 10.1 (2016) on LineageOS, which keeps Samsung\'s maker and model: its frame', () => {
    expect(identifyAdb({ manufacturer: 'Samsung', model: 'SM-T580' })).toMatchObject({
      brand: { id: 'samsung' }, model: { name: 'Galaxy Tab A 10.1 (2016)', frame: 'samsung-galaxy-tab-a-10-1-2016' }, tested: true,
    });
  });

  it('an unknown maker: untested, generic steps', () => {
    expect(identifyAdb({ manufacturer: 'Teclast', model: 'P20HD' })).toMatchObject({ steps: GENERIC_STEPS, tested: false, why: 'Kiln doesn\'t know the maker "Teclast".' });
  });
});

describe('the table', () => {
  const sources = [GENERIC_STEPS, ...BRANDS.map((b) => b.steps)].flatMap((s) => s.sources).concat(MODELS.map((m) => m.source));

  it('cites an https page for every brand\'s steps and every model', () => {
    for (const s of [GENERIC_STEPS, ...BRANDS.map((b) => b.steps)]) expect(s.sources.length).toBeGreaterThan(0);
    for (const s of sources) {
      expect(s.title).toMatch(/\S/);
      expect(new URL(s.href).protocol, s.href).toBe('https:');
    }
  });

  it('every path starts in Settings, and debugging is a switch called USB debugging', () => {
    for (const b of [{ name: 'generic', steps: GENERIC_STEPS }, ...BRANDS]) {
      expect(b.steps.unlock.path[0], b.name).toBe('Settings');
      for (const d of b.steps.debugging) {
        expect(d.path[0], b.name).toBe('Settings');
        expect(d.path.at(-1), b.name).toBe('USB debugging');
      }
    }
  });

  it('one brand per vendor ID, none a chip maker\'s, unique ids and manufacturers', () => {
    const ids = BRANDS.flatMap((b) => b.vendorIds);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids.filter((v) => v in CHIP_VENDORS)).toEqual([]);
    expect(new Set(BRANDS.map((b) => b.id)).size).toBe(BRANDS.length);
    const makers = BRANDS.flatMap((b) => b.manufacturers);
    expect(new Set(makers).size).toBe(makers.length);
    expect(makers.every((m) => m === m.toLowerCase())).toBe(true);
  });

  it('every model\'s brand is in the table, and its frame is a frame', () => {
    for (const m of MODELS) {
      expect(BRANDS.some((b) => b.id === m.brand), m.name).toBe(true);
      if (!m.frame) continue;
      const file = join(FRAMES, m.frame, 'design', 'frame.json');
      expect(existsSync(file), file).toBe(true);
      expect((JSON.parse(readFileSync(file, 'utf8')) as { base?: string }).base ?? m.frame, `${m.frame} installs as generic-android`).toBe('generic-android');
    }
  });
});
