// What a device is before USB debugging is on (#403): Chrome's WebUSB picker gives a plugged-in tablet's
// USB vendor ID and product name in charging or file-transfer mode, without claiming it. This maps them to
// a brand, that brand's steps to turn on USB debugging, and, for a few model codes, the model and its frame
// page. Unknown: generic steps and "untested device". After adb is on, the same tables read
// ro.product.manufacturer and ro.product.model.
//
// Every brand's steps come from a page named in `sources`, read on 2026-11-16. Brands whose own page
// couldn't be read get Android's general steps, never steps made up for them.
import type { OwnFrame } from '@glassworks/kiln-core';

export interface Source { title: string; href: string }

/** One place in Settings: the path to it, then what to do there. */
export interface Place { path: string[]; note?: string }

/** How to turn on USB debugging on a brand's devices. */
export interface DebugSteps {
  /** where the person taps `tap` seven times to turn on developer options */
  unlock: Place & { tap: string };
  /** where USB debugging is then, the first that applies (some differ by Android version) */
  debugging: (Place & { when?: string })[];
  sources: Source[];
}

const ANDROID_DOCS: Source = { title: 'Android Developers: Configure on-device developer options', href: 'https://developer.android.com/studio/debug/dev-options' };
const ISSUE_403: Source = { title: 'GlassworksHQ/glassworks#403: steps read on a Pixel Tablet, 2026-11-16', href: 'https://github.com/GlassworksHQ/glassworks/issues/403' };

/** Android's own steps (Android Developers, Table 1 and "Enable debugging on your device"). */
export const GENERIC_STEPS: DebugSteps = {
  unlock: { path: ['Settings', 'About tablet'], tap: 'Build number', note: 'On a phone it is About phone. Some brands put Build number one level deeper, e.g. under Software information.' },
  debugging: [
    { path: ['Settings', 'System', 'Advanced', 'Developer options', 'USB debugging'], when: 'Android 9 and newer' },
    { path: ['Settings', 'System', 'Developer options', 'USB debugging'], when: 'Android 8' },
    { path: ['Settings', 'Developer options', 'USB debugging'], when: 'Android 7 and older' },
  ],
  sources: [ANDROID_DOCS, ISSUE_403],
};

export type BrandId = 'google' | 'samsung' | 'amazon' | 'lenovo' | 'xiaomi' | 'motorola' | 'oneplus' | 'lg' | 'htc' | 'huawei' | 'sony' | 'asus' | 'zte' | 'oppo' | 'tcl' | 'hmd';

export interface Brand {
  id: BrandId;
  name: string;
  /** USB vendor IDs (usb.ids, version 2026.06.26) */
  vendorIds: number[];
  /** ro.product.manufacturer as these devices report it, lower case */
  manufacturers: string[];
  /** its own steps; Android's general ones when its own page wasn't read */
  steps: DebugSteps;
}

const generic = (sources: Source[] = []): DebugSteps => ({ ...GENERIC_STEPS, sources: [...GENERIC_STEPS.sources, ...sources] });
const doc = (unlock: string[], tap = 'Build number'): DebugSteps => ({ ...GENERIC_STEPS, unlock: { path: unlock, tap } });

export const BRANDS: Brand[] = [
  {
    id: 'google', name: 'Google Pixel', vendorIds: [0x18d1], manufacturers: ['google'],
    steps: {
      unlock: { path: ['Settings', 'About tablet'], tap: 'Build number' },
      debugging: [{ path: ['Settings', 'System', 'Developer options', 'USB debugging'] }],
      sources: [ISSUE_403, ANDROID_DOCS],
    },
  },
  {
    id: 'samsung', name: 'Samsung Galaxy', vendorIds: [0x04e8], manufacturers: ['samsung'],
    steps: {
      unlock: { path: ['Settings', 'About phone', 'Software information'], tap: 'Build number', note: 'On a tablet the first item may be About tablet or About device. Enter your PIN, pattern or password if it asks.' },
      debugging: [{ path: ['Settings', 'Developer options', 'USB debugging'], note: 'Developer options is at the very bottom of Settings.' }],
      sources: [
        { title: 'Samsung US: Turn on the Developer options menu on your Galaxy phone', href: 'https://www.samsung.com/us/support/answer/ANS10003430/' },
        { title: 'Samsung: Developer options on the Galaxy Tab 4 (About device)', href: 'https://www.samsung.com/africa_en/support/mobile-devices/how-do-i-turn-on-the-developer-options-menu-on-my-samsung-galaxy-tab-4/' },
        ANDROID_DOCS,
      ],
    },
  },
  {
    id: 'amazon', name: 'Amazon Fire', vendorIds: [0x1949], manufacturers: ['amazon'],
    steps: {
      unlock: { path: ['Settings', 'Device Options', 'About Fire Tablet'], tap: 'Serial Number', note: 'Only if Device Options has no Developer Options yet.' },
      debugging: [{ path: ['Settings', 'Device Options', 'Developer Options', 'USB debugging'], note: 'Turn on Developer options too. On 2013 models the menu may be called Security.' }],
      sources: [{ title: 'Amazon: Connect to Fire Tablet through ADB', href: 'https://developer.amazon.com/docs/fire-tablets/connecting-adb-to-device.html' }],
    },
  },
  // Android Developers, Table 1, gives these brands' Build number; the rest is Android's general steps.
  { id: 'oneplus', name: 'OnePlus', vendorIds: [0x2a70], manufacturers: ['oneplus'], steps: doc(['Settings', 'About phone']) },
  { id: 'lg', name: 'LG', vendorIds: [0x1004], manufacturers: ['lge', 'lg'], steps: doc(['Settings', 'About phone', 'Software info']) },
  { id: 'htc', name: 'HTC', vendorIds: [0x0bb4], manufacturers: ['htc'], steps: doc(['Settings', 'About', 'Software information', 'More']) },
  // Known by their USB vendor ID; their own pages weren't read (Lenovo's refuse automated reads: HTTP 403).
  { id: 'lenovo', name: 'Lenovo', vendorIds: [0x17ef], manufacturers: ['lenovo'], steps: generic() },
  { id: 'xiaomi', name: 'Xiaomi', vendorIds: [0x2717], manufacturers: ['xiaomi', 'redmi', 'poco'], steps: generic() },
  { id: 'motorola', name: 'Motorola', vendorIds: [0x22b8], manufacturers: ['motorola'], steps: generic() },
  { id: 'huawei', name: 'Huawei', vendorIds: [0x12d1], manufacturers: ['huawei'], steps: generic() },
  { id: 'sony', name: 'Sony', vendorIds: [0x0fce], manufacturers: ['sony'], steps: generic() },
  { id: 'asus', name: 'ASUS', vendorIds: [0x0b05], manufacturers: ['asus'], steps: generic() },
  { id: 'zte', name: 'ZTE', vendorIds: [0x19d2], manufacturers: ['zte'], steps: generic() },
  { id: 'oppo', name: 'OPPO', vendorIds: [0x22d9], manufacturers: ['oppo', 'realme'], steps: generic() },
  { id: 'tcl', name: 'TCL', vendorIds: [0x1bbb], manufacturers: ['tcl', 'alcatel'], steps: generic() },
  { id: 'hmd', name: 'Nokia (HMD)', vendorIds: [0x2e04], manufacturers: ['hmd global', 'nokia'], steps: generic() },
];

/**
 * Chip makers' vendor IDs (usb.ids): a no-name tablet often keeps its chip's, so the ID says nothing about
 * the brand. Kiln asks the person to pick one.
 */
export const CHIP_VENDORS: Record<number, string> = { 0x05c6: 'Qualcomm', 0x0e8d: 'MediaTek', 0x1f3a: 'Allwinner', 0x2207: 'Rockchip' };

/** A model Kiln knows by its code: its name, and the frame page that shows it, when there is one. */
export interface Model { code: RegExp; brand: BrandId; name: string; frame?: string; source: Source }

const SAMSUNG_A8: Source = { title: 'Samsung: Galaxy Tab A8 series spec sheet (SM-X200)', href: 'https://image-us.samsung.com/SamsungUS/samsungbusiness/pdfs/spec-sheet/Galaxy-Tab-A8-Series-Spec-Sheet.pdf' };
const FIRE_MODELS: Source = { title: 'Amazon: Identify Fire Tablet Devices', href: 'https://developer.amazon.com/docs/device-specs/ft-identify-tablet-devices.html' };

/** ro.product.model, or the USB product name when it carries the code. Matched whole, case as written. */
export const MODELS: Model[] = [
  { code: /^Pixel Tablet$/, brand: 'google', name: 'Pixel Tablet', frame: 'google-pixel-tablet', source: { title: 'Wikipedia: Pixel Tablet', href: 'https://en.wikipedia.org/wiki/Pixel_Tablet' } },
  // LineageOS builds for it keep Samsung's model (PRODUCT_MODEL := SM-T580 in its device tree, read 2026-11-20)
  { code: /^SM-T580$/, brand: 'samsung', name: 'Galaxy Tab A 10.1 (2016)', frame: 'samsung-galaxy-tab-a-10-1-2016', source: { title: 'Wikipedia: Samsung Galaxy Tab A 10.1 (2016)', href: 'https://en.wikipedia.org/wiki/Samsung_Galaxy_Tab_A_10.1_(2016)' } },
  { code: /^SM-X200$/, brand: 'samsung', name: 'Galaxy Tab A8', source: SAMSUNG_A8 },
  { code: /^SM-X110$/, brand: 'samsung', name: 'Galaxy Tab A9', source: { title: 'Samsung Levant: Galaxy Tab A9 (Wi-Fi), SM-X110', href: 'https://www.samsung.com/levant/tablets/galaxy-tab-a/galaxy-tab-a9-wifi-mystic-navy-128gb-sm-x110ndbemea/' } },
  { code: /^SM-X210$/, brand: 'samsung', name: 'Galaxy Tab A9+', source: { title: 'Samsung UK: Galaxy Tab A9+ (11", Wi-Fi) SM-X210', href: 'https://www.samsung.com/uk/support/model/SM-X210NZAAEUB/' } },
  { code: /^KFTRWI$/, brand: 'amazon', name: 'Fire HD 10 (2021)', source: FIRE_MODELS },
  { code: /^KFTRPWI$/, brand: 'amazon', name: 'Fire HD 10 Plus (2021)', source: FIRE_MODELS },
  { code: /^KFTUWI$/, brand: 'amazon', name: 'Fire HD 10 (2023)', source: FIRE_MODELS },
  { code: /^KFRAWI$/, brand: 'amazon', name: 'Fire HD 8 (2022)', source: FIRE_MODELS },
  { code: /^KFQUWI$/, brand: 'amazon', name: 'Fire 7 (2022)', source: FIRE_MODELS },
];

/** What Kiln can say about a device. `pick`: the brand isn't known, so ask the person to pick one. */
export interface Identified {
  brand?: Brand;
  model?: Model;
  steps: DebugSteps;
  /** Kiln knows this model (it has a frame page): otherwise the done screen says "untested device" */
  tested: boolean;
  pick: boolean;
  /** why it couldn't tell, for the brand picker */
  why?: string;
}

/** Product names that say nothing about the device (charging-only mode, generic MTP stacks). */
const VAGUE = /^(|android|mtp|ptp|usb|adb|android device|mtp device|android phone|android tablet|.*_android)$/i;

function identified(brand: Brand | undefined, model: Model | undefined, why?: string): Identified {
  return { ...(brand ? { brand } : {}), ...(model ? { model } : {}), steps: brand?.steps ?? GENERIC_STEPS, tested: !!model?.frame, pick: !brand, ...(why ? { why } : {}) };
}

const modelFor = (text: string, brand?: Brand) => MODELS.find((m) => m.code.test(text.trim()) && (!brand || m.brand === brand.id));
const brandById = (id: BrandId) => BRANDS.find((b) => b.id === id)!;

/**
 * From WebUSB, before USB debugging is on: `vendorId` and `productName` (USBDevice's). A model code in
 * the product name wins; then the vendor ID. A chip maker's ID, or an unknown one, asks for the brand.
 */
export function identifyUsb(d: { vendorId: number; productName?: string }): Identified {
  const name = d.productName?.trim() ?? '';
  const brand = BRANDS.find((b) => b.vendorIds.includes(d.vendorId));
  const model = modelFor(name, brand);
  if (model) return identified(brandById(model.brand), model);
  if (brand) return identified(brand, undefined);
  const chip = CHIP_VENDORS[d.vendorId];
  if (chip) return identified(undefined, undefined, `It reports ${chip}, the maker of its chip, not its brand.`);
  return identified(undefined, undefined, VAGUE.test(name) ? 'It doesn\'t say what it is.' : `Kiln doesn't know "${name}".`);
}

/** From adb, once debugging is on: ro.product.manufacturer and ro.product.model. */
export function identifyAdb(d: { manufacturer: string; model: string }): Identified {
  const brand = BRANDS.find((b) => b.manufacturers.includes(d.manufacturer.trim().toLowerCase()));
  const model = modelFor(d.model, brand);
  return identified(model ? brandById(model.brand) : brand, model, brand ? undefined : `Kiln doesn't know the maker "${d.manufacturer.trim()}".`);
}

/** The person picked their brand in Kiln, or "Other" (undefined): their steps, and no need to ask again. */
export function identifyPicked(id?: BrandId): Identified {
  return { ...identified(id ? brandById(id) : undefined, undefined), pick: false };
}

/**
 * A device with a frame of its own, which Kiln installs from that frame's page: the generic app would take its
 * app's place as the home screen. Matched by `ro.hardware`, the check its own app uses to know its frame.
 */
export type { OwnFrame } from '@glassworks/kiln-core';

export const OWN_FRAMES: OwnFrame[] = [
  // AqaraS1Plus.HARDWARE (frames/aqara-s1-plus/android, read 2026-11-14). Its maker and model are Allwinner's SDK
  // defaults ("Allwinner", "QUAD-CORE R818 EVB2"), so they can't name it.
  { frame: 'aqara-s1-plus', name: 'Aqara S1 Plus', route: '/aqara-s1-plus', hardware: 'sun50iw10p1' },
];

/** The frame of its own this device is, from adb's `ro.hardware`; undefined for any other device. */
export const ownFrame = (d: { hardware: string }): OwnFrame | undefined => OWN_FRAMES.find((f) => f.hardware === d.hardware.trim());
