// The Aqara Panel Hub S1 Plus for Kiln: frames/aqara-s1-plus/tools/install/fel-install.sh's device-specific steps. FEL on
// the A133 boots our U-Boot from RAM, fastboot shows the S1 Plus layout, the patched boot carries the
// root helper, radio and update services, and provisioning is frames/aqara-s1-plus/tools/install.sh (provision.ts).
import { isMagiskPatched, magiskBusybox, patchBoot, replaceBootlogo, vbmetaFlags, withVbmetaFlags } from '@glassworks/bootpatch';
import { WrongChip, bootUboot, checkChip } from '@glassworks/fel';
import type { FlashStep, Recipe } from '@glassworks/kiln-core';
import { provision, type Provisioned, type ProvisionOptions } from './provision';
import { isOurSplash } from './splash';
import { s1Overlay } from './overlay';

/** The blobs in kiln/assets.lock.json (staged by kiln/scripts/stage-assets.mjs). */
export type AssetName =
  | 'uboot-spl' | 'uboot' | 'magisk' | 'otbr-agent' | 'ot-ctl'
  | 'rc' | 'loader-sh' | 'verify' | 'bundle-pub' | 'agent-sh' | 'firstboot-sh' | 'netwatch-sh' | 'update-sh'
  | 'bootanimation' | 'bootlogo' | 'kiosk-apk' | 'keyboard-apk';

export type SetUpOptions = Omit<ProvisionOptions, 'kioskApk' | 'keyboardApk'> & { keyboard?: boolean };

const BOOT_SIZE = 0x2000000;      // 32 MiB: S1 Plus layout check (fel-install.sh step 3)
const SUPER_SIZE = 0x60000000;    // 1.5 GiB

/** The FEL package's refusal of another chip, in the S1's words. */
const asS1 = <T>(p: Promise<T>): Promise<T> => p.catch((e: unknown) => {
  throw e instanceof WrongChip ? new Error(`this is not an Aqara S1 Plus (SoC 0x${e.socId.toString(16)}, expected an A133, 0x1855)`) : e;
});

export const s1Plus: Recipe<AssetName, SetUpOptions, Provisioned> = {
  fel: {
    async checkChip(fel) {
      const chip = await asS1(checkChip(fel));
      return `SoC 0x${chip.socId.toString(16)} (A133), secure boot off`;
    },
    loader: ['uboot-spl', 'uboot'],
    boot: (fel, [spl, uboot], onProgress) => asS1(bootUboot(fel, spl!, uboot!, onProgress)),
  },

  async checkLayout(fb) {
    const product = await fb.getvar('product');
    const bootSize = await fb.partitionSize('boot');
    const superSize = await fb.partitionSize('super');
    if (product !== 'sunxi' || bootSize !== BOOT_SIZE || superSize !== SUPER_SIZE) {
      throw new Error(`this does not look like an Aqara S1 Plus (product ${product}, boot ${bootSize}, super ${superSize}). Nothing was changed.`);
    }
    return 'partition layout OK (S1 Plus)';
  },

  backup: {
    parts: ['boot', 'vbmeta', 'vbmeta_system', 'vbmeta_vendor', 'dtbo', 'misc', 'recovery', 'env', 'bootloader'],
    // fel-install.sh --undo: boot, vbmeta and splash
    restore: ['boot', 'vbmeta', 'bootloader'],
    idPrefix: 's1-backup',
    db: 's1-unleashed-backups',
    isPatched: isMagiskPatched,
    // A backup taken after our splash went on (boot put back without it, then installed again)
    ours: { bootloader: { what: 'splash', is: isOurSplash } },
  },

  patching: 'Adding root, the frame loader and this browser\'s key',

  async patchBoot({ backup, asset: a, adbPublicKey, branding, log }) {
    const [magiskApk, rc, loaderSh, verify, bundlePub, agentSh, otbrAgent, otCtl, firstbootSh, netwatchSh, updateSh] =
      await Promise.all([a('magisk'), a('rc'), a('loader-sh'), a('verify'), a('bundle-pub'), a('agent-sh'), a('otbr-agent'), a('ot-ctl'),
        a('firstboot-sh'), a('netwatch-sh'), a('update-sh')]);
    const overlay = s1Overlay({
      rc, loaderSh, verify, bundlePub, agentSh, otbrAgent, otCtl, firstbootSh, netwatchSh, updateSh,
      magiskBusybox: await magiskBusybox(magiskApk),
      adbKeys: new TextEncoder().encode(`${adbPublicKey}\n`),
      bootAnimation: branding ? await a('bootanimation') : undefined,
    });
    const patched = await patchBoot({ boot: backup.get('boot')!, magiskApk, overlay });
    patched.log.forEach((l) => log(l));
    return patched.image;
  },

  async writesBeforeBoot({ backup, asset, branding, log }) {
    // 6: vbmeta flags 3 (verification off), only if needed
    const vbmeta = backup.get('vbmeta')!;
    const flags = vbmetaFlags(vbmeta);
    log(`vbmeta flags: ${flags}`);
    const steps: FlashStep[] = [];
    if (flags !== 3) steps.push({ part: 'vbmeta', data: withVbmetaFlags(vbmeta, 3), what: 'Turning off verified boot' });
    // 7: splash
    if (branding) {
      const bl = backup.get('bootloader')!;
      const branded = replaceBootlogo(bl, await asset('bootlogo'));
      if (branded.some((x, i) => x !== bl[i])) steps.push({ part: 'bootloader', data: branded, what: 'Writing the boot logo' });
      else log('splash already in place: unchanged');
    }
    return steps;
  },

  async setUp(panel, opts, { asset, hasAsset, step }) {
    const kioskApk = await asset('kiosk-apk');
    const keyboardApk = opts.keyboard !== false && (await hasAsset('keyboard-apk')) ? await asset('keyboard-apk') : undefined;
    return provision(panel, { ...opts, kioskApk, keyboardApk }, step);
  },
};
