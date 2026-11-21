// The tablet page's detection (#403, part 3): Android or not, tablet or phone, its version and the build that
// fits, and the brand and model where the browser lets Kiln tell.
import { describe, expect, it } from 'vitest';
import { GENERIC_STEPS, identifyBrowser } from '../src';
import { BROWSERS } from './browsers';

const read = (name: keyof typeof BROWSERS) => identifyBrowser(BROWSERS[name]);

describe('Android or not', () => {
  it.each(['PIXEL_DESKTOP', 'UNKNOWN_DESKTOP', 'GALAXY_TAB', 'GALAXY_PHONE', 'NOUGAT_TAB', 'FIRE_SILK', 'FIREFOX_TAB', 'LOLLIPOP'] as const)('%s is Android', (n) => {
    expect(read(n).android).toBe(true);
  });

  it('desktop mode says Linux, but the frozen platform still says Android', () => {
    expect(read('PIXEL_DESKTOP')).toMatchObject({ android: true, desktopSite: true });
    expect(read('GALAXY_TAB').desktopSite).toBe(false);
  });

  it.each(['MAC', 'PI_TOUCH'] as const)('%s is not, an ARM touch screen on Linux included', (n) => {
    expect(read(n)).toMatchObject({ android: false, desktopSite: false });
  });
});

describe('tablet or phone', () => {
  it('by the short side, so a tablet held upright is still a tablet', () => {
    expect(read('PIXEL_DESKTOP').form).toBe('tablet');
    expect(read('PIXEL_PORTRAIT').form).toBe('tablet');
    expect(read('GALAXY_PHONE').form).toBe('phone');
  });
});

describe('the version and the build that fits', () => {
  it('client hints give the real version, not the reduced user agent\'s "10"', () => {
    expect(read('GALAXY_TAB')).toMatchObject({ release: '14', app: 'generic' });
    expect(read('GALAXY_PHONE')).toMatchObject({ release: '15', app: 'generic' });
  });

  it('a user agent from before the reduction, and Firefox\'s, say it', () => {
    expect(read('NOUGAT_TAB')).toMatchObject({ release: '7', app: 'legacy' });
    expect(read('FIRE_SILK')).toMatchObject({ release: '11', app: 'generic' });
    expect(read('FIREFOX_TAB')).toMatchObject({ release: '14', app: 'generic' });
  });

  it('a version below Android 6 is too old', () => {
    expect(read('LOLLIPOP')).toMatchObject({ release: '5.1.1', app: 'too-old' });
  });

  it('hidden in desktop mode: Chrome 120 or newer means Android 8 or newer', () => {
    expect(read('PIXEL_DESKTOP').release).toBeUndefined();
    expect(read('PIXEL_DESKTOP').app).toBe('generic');
  });

  it('hidden on an older Chrome: either build may fit', () => {
    expect(read('OLD_CHROME_DESKTOP')).toMatchObject({ app: 'either' });
    expect(read('OLD_CHROME_DESKTOP').release).toBeUndefined();
  });

  it('bitness from client hints, where the browser gives one', () => {
    expect(read('GALAXY_TAB').bits).toBe(64);
    expect(read('PIXEL_DESKTOP').bits).toBeUndefined();
    expect(identifyBrowser({ ...BROWSERS.GALAXY_TAB, hints: { ...BROWSERS.GALAXY_TAB.hints, bitness: '32' } }).bits).toBe(32);
  });

  it('bitness from what the browser runs as, where its platform isn\'t frozen (issue 700)', () => {
    // a 32-bit browser on a 64-bit chip: Samsung's Android 8.1 on a Galaxy Tab A 10.1 runs only 32-bit code
    expect(read('GALAXY_TAB_A_2016_SAMSUNG')).toMatchObject({ android: true, bits: 32, release: '8.1', app: 'generic', identified: { brand: { id: 'samsung' } } });
    expect(read('NOUGAT_TAB').bits).toBe(32);
    expect(read('LOLLIPOP').bits).toBe(32);
    expect(identifyBrowser({ ...BROWSERS.GALAXY_TAB, platform: 'Linux aarch64', hints: { ...BROWSERS.GALAXY_TAB.hints, bitness: '' } }).bits).toBe(64);
    // the platform says what the browser runs as, so it wins over a hint
    expect(identifyBrowser({ ...BROWSERS.GALAXY_TAB, platform: 'Linux armv8l' }).bits).toBe(32);
    // the same tablet in Chrome: the frozen "Linux armv81" and an empty hint say nothing
    expect(read('GALAXY_TAB_A_2016')).toMatchObject({ release: '8.1', modelCode: 'SM-T580', app: 'generic' });
    expect(read('GALAXY_TAB_A_2016')).not.toHaveProperty('bits');
    expect(read('UNKNOWN_DESKTOP')).not.toHaveProperty('bits');
    // a computer's or a Pi's machine isn't an Android device's
    expect(read('PI_TOUCH').bits).toBeUndefined();
  });
});

describe('the brand and model', () => {
  it('a Pixel Tablet in desktop mode, by its GPU and screen, either way up', () => {
    for (const n of ['PIXEL_DESKTOP', 'PIXEL_PORTRAIT'] as const) {
      expect(read(n).identified).toMatchObject({ brand: { id: 'google' }, model: { name: 'Pixel Tablet', frame: 'google-pixel-tablet' }, tested: true, pick: false });
    }
  });

  it('the same GPU on another screen is not a Pixel Tablet', () => {
    const other = identifyBrowser({ ...BROWSERS.PIXEL_DESKTOP, screen: { width: 1340, height: 800, dpr: 2 } });
    expect(other.identified).toMatchObject({ tested: false, pick: true, why: 'In desktop mode the browser doesn\'t say the model.' });
    expect(other.identified.steps).toBe(GENERIC_STEPS);
  });

  it('a model in client hints: the model table, else the brand from its prefix', () => {
    expect(read('GALAXY_TAB')).toMatchObject({ modelCode: 'SM-X200', identified: { brand: { id: 'samsung' }, model: { name: 'Galaxy Tab A8' }, tested: false, pick: false } });
    expect(read('GALAXY_PHONE')).toMatchObject({ modelCode: 'SM-S921B', identified: { brand: { id: 'samsung' }, pick: false } });
    expect(read('GALAXY_PHONE').identified.model).toBeUndefined();
  });

  it('a model in an unreduced user agent', () => {
    expect(read('NOUGAT_TAB')).toMatchObject({ modelCode: 'SM-T580', identified: { brand: { id: 'samsung' } } });
    expect(read('FIRE_SILK')).toMatchObject({ modelCode: 'KFTRWI', identified: { brand: { id: 'amazon' }, model: { name: 'Fire HD 10 (2021)' } } });
  });

  it('never the reduced user agent\'s K, nor Firefox\'s Tablet', () => {
    expect(read('PIXEL_DESKTOP').modelCode).toBeUndefined();
    expect(identifyBrowser({ ...BROWSERS.GALAXY_TAB, hints: {} }).modelCode).toBeUndefined();
    expect(read('FIREFOX_TAB').modelCode).toBeUndefined();
    expect(read('FIREFOX_TAB').identified).toMatchObject({ pick: true, why: 'The browser doesn\'t say the model.' });
  });

  it('Silk and Samsung Internet name their brand when the model can\'t', () => {
    const silk = identifyBrowser({ ...BROWSERS.FIRE_SILK, userAgent: BROWSERS.FIRE_SILK.userAgent.replace('Android 11; KFTRWI', 'Android 10; K') });
    expect(silk.identified).toMatchObject({ brand: { id: 'amazon' }, pick: false });
    const samsung = identifyBrowser({ ...BROWSERS.FIREFOX_TAB, userAgent: 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/28.0 Chrome/130.0.0.0 Safari/537.36' });
    expect(samsung.identified.brand?.id).toBe('samsung');
  });
});
