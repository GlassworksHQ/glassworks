// What a device report starts with, from what Kiln read: the model and its maker, Android and its API level, the ABIs,
// how it was installed, the Glassworks version and how far Kiln got. A whitelist: nothing else of the device goes in,
// never its serial, a MAC, an address, a network, Home Assistant's URL or a key.
import type { OnDevice, UsbFrame } from '@glassworks/kiln-core';
import type { UsbState } from '../device/usbSession';
import type { ReportPrefill, Step } from './report';

/** The model as people say it, with the code Android reports when that adds something ("Galaxy Tab A, SM-T580"). */
function modelLine(name: string | undefined, maker: string | undefined, code: string | undefined): string {
  const made = [maker, code].filter(Boolean).join(' ').trim();
  if (!name) return made;
  return code && !name.toLowerCase().includes(code.toLowerCase()) ? `${name}, ${code}` : name;
}

/** From the USB-debugging flow: what adb read, or before that what USB said. */
export function usbPrefill(dev: UsbFrame, s: UsbState, step: Step): ReportPrefill {
  const d = s.plan?.device;
  const known = s.identified?.model?.name;
  const model = d ? modelLine(known, d.manufacturer, d.model) : known ?? s.usbName ?? '';
  return {
    model,
    android: d ? `${d.release || dev.androidName(d.sdk)} (API ${d.sdk})` : '',
    abi: d?.abis.join(',') ?? '',
    flow: 'usb',
    step,
    appVersion: s.release?.state === 'ready' ? s.release.version : '',
  };
}

/** From the device's own browser: as far as it lets Kiln read (the model's code, the version and bitness when shown). */
export function onDevicePrefill(on: OnDevice, step: Step, version?: string): ReportPrefill {
  const named = on.identified.model?.name;
  const model = named || on.modelCode ? modelLine(named, undefined, on.modelCode) : on.identified.brand ? `${on.identified.brand.name} ${on.form}` : '';
  return {
    model,
    android: on.release ?? '',
    abi: on.bits ? `${on.bits}-bit` : '',
    flow: 'browser',
    step,
    appVersion: version ?? '',
  };
}
