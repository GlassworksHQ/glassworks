/// <reference types="w3c-web-usb" preserve="true" />
// The WebUSB transport on a stand-in USBDevice: finding and claiming the interface, the errors a person sees when
// the browser can't claim it, zero-length packets, and a read that outlives its timeout.
import { describe, expect, it } from 'vitest';
import { CLAIM_HELP, ODIN_USB_FILTERS, OdinError, WebUsbOdinTransport, findInterface, isDownloadMode } from '../src/index.ts';

type Ep = { endpointNumber: number; direction: 'in' | 'out'; type: 'bulk' | 'interrupt' };
const iface = (interfaceNumber: number, cls: number, endpoints: Ep[], alternateSetting = 0) => {
  const alt = { alternateSetting, interfaceClass: cls, interfaceSubclass: 0, interfaceProtocol: 0, endpoints };
  return { interfaceNumber, alternate: alt, alternates: [alt], claimed: false } as unknown as USBInterface;
};
const bulkPair: Ep[] = [{ endpointNumber: 1, direction: 'in', type: 'bulk' }, { endpointNumber: 2, direction: 'out', type: 'bulk' }];

/** A Download mode device as Heimdall finds it: a CDC communications interface, then the CDC data interface. */
function usbDevice(overrides: Partial<Record<'claimInterface' | 'open' | 'selectAlternateInterface', () => Promise<void>>> & { productId?: number; interfaces?: USBInterface[] } = {}) {
  const calls: string[] = [];
  const out: Uint8Array[] = [];
  const reads: ((r: USBInTransferResult) => void)[] = [];
  const rejects: ((e: unknown) => void)[] = [];
  const interfaces = overrides.interfaces ?? [
    iface(0, 0x02, [{ endpointNumber: 3, direction: 'in', type: 'interrupt' }]),
    iface(1, 0x0a, bulkPair),
  ];
  const device = {
    vendorId: 0x04e8,
    productId: overrides.productId ?? 0x685d,
    opened: false,
    configuration: { interfaces },
    async open() { calls.push('open'); await overrides.open?.(); device.opened = true; },
    async selectConfiguration() { calls.push('selectConfiguration'); },
    async claimInterface(n: number) { calls.push(`claim ${n}`); await overrides.claimInterface?.(); },
    async selectAlternateInterface(n: number, alt: number) { calls.push(`alternate ${n} ${alt}`); await overrides.selectAlternateInterface?.(); },
    async releaseInterface(n: number) { calls.push(`release ${n}`); },
    async close() { calls.push('close'); },
    async transferOut(ep: number, data: Uint8Array) {
      out.push(data.slice());
      calls.push(`out ${ep} ${data.length}`);
      return { status: 'ok', bytesWritten: data.length } as USBOutTransferResult;
    },
    transferIn(ep: number, length: number) {
      calls.push(`in ${ep} ${length}`);
      return new Promise<USBInTransferResult>((resolve, reject) => { reads.push(resolve); rejects.push(reject); });
    },
  };
  const deliver = (bytes: number[]) => {
    const resolve = reads.shift();
    rejects.shift();
    if (!resolve) throw new Error('no read is waiting');
    resolve({ status: 'ok', data: new DataView(new Uint8Array(bytes).buffer) } as USBInTransferResult);
  };
  const fail = (e: unknown) => { reads.shift(); rejects.shift()!(e); };
  return { device: device as unknown as USBDevice, calls, out, deliver, fail, reads };
}

describe('finding the device', () => {
  it('filters on Samsung\'s vendor id and Heimdall\'s Download mode product ids', () => {
    expect(ODIN_USB_FILTERS).toEqual([
      { vendorId: 0x04e8, productId: 0x6601 }, { vendorId: 0x04e8, productId: 0x685d }, { vendorId: 0x04e8, productId: 0x68c3 },
    ]);
    expect(isDownloadMode({ vendorId: 0x04e8, productId: 0x685d })).toBe(true);
    expect(isDownloadMode({ vendorId: 0x04e8, productId: 0x6860 })).toBe(false); // a Samsung phone in MTP mode
    expect(isDownloadMode({ vendorId: 0x18d1, productId: 0x685d })).toBe(false);
  });

  it('takes the first CDC Data setting with exactly one bulk IN and one bulk OUT', () => {
    expect(findInterface([
      iface(0, 0x02, [{ endpointNumber: 3, direction: 'in', type: 'interrupt' }]),
      iface(1, 0x0a, [...bulkPair, { endpointNumber: 4, direction: 'in', type: 'bulk' }]), // three endpoints
      iface(2, 0xff, bulkPair), // vendor class
      iface(3, 0x0a, [{ endpointNumber: 5, direction: 'in', type: 'bulk' }, { endpointNumber: 6, direction: 'out', type: 'bulk' }], 1),
    ])).toEqual({ interfaceNumber: 3, alternateSetting: 1, epIn: 5, epOut: 6, claimed: false });
    expect(findInterface([iface(0, 0x02, bulkPair)])).toBeUndefined();
  });
});

describe('opening it', () => {
  it('opens, claims the data interface, and releases it on close', async () => {
    const usb = usbDevice();
    const t = await WebUsbOdinTransport.open(usb.device);
    expect(t.interfaceNumber).toBe(1);
    await t.close();
    expect(usb.calls).toEqual(['open', 'claim 1', 'release 1', 'close']);
  });

  it('selects the alternate setting when it isn\'t the first (SetupDeviceInterface)', async () => {
    const usb = usbDevice({ interfaces: [iface(0, 0x0a, bulkPair, 2)] });
    await WebUsbOdinTransport.open(usb.device);
    expect(usb.calls).toEqual(['open', 'claim 0', 'alternate 0 2']);
  });

  it('gives the interface back when its alternate setting can\'t be selected', async () => {
    const usb = usbDevice({
      interfaces: [iface(0, 0x0a, bulkPair, 2)],
      selectAlternateInterface: async () => { throw new DOMException('Unable to set device interface.', 'NetworkError'); },
    });
    await expect(WebUsbOdinTransport.open(usb.device)).rejects.toMatchObject({ code: 'claim-failed' });
    expect(usb.calls).toEqual(['open', 'claim 0', 'alternate 0 2', 'release 0', 'close']);
  });

  it('refuses an interface this page has claimed already, and leaves that claim alone', async () => {
    const claimed = iface(0, 0x0a, bulkPair, 2);
    (claimed as { claimed: boolean }).claimed = true;
    const usb = usbDevice({ interfaces: [claimed] });
    await expect(WebUsbOdinTransport.open(usb.device)).rejects.toMatchObject({ code: 'claim-failed', message: /already has/ });
    expect(usb.calls).toEqual(['open']);
  });

  it('says what to do when the browser can\'t claim it (WinUSB on Windows)', async () => {
    const usb = usbDevice({ claimInterface: async () => { throw new DOMException('Unable to claim interface.', 'NetworkError'); } });
    const error = await WebUsbOdinTransport.open(usb.device).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(OdinError);
    expect(error).toMatchObject({ code: 'claim-failed' });
    expect((error as Error).message).toContain(CLAIM_HELP);
    expect((error as Error).message).toContain('Unable to claim interface.');
    expect(CLAIM_HELP).toMatch(/WinUSB/);
    expect(CLAIM_HELP).toMatch(/Zadig/);
  });

  it('gives the same help when the browser can\'t open it', async () => {
    const usb = usbDevice({ open: async () => { throw new DOMException('Access denied.', 'SecurityError'); } });
    await expect(WebUsbOdinTransport.open(usb.device)).rejects.toMatchObject({ code: 'claim-failed', message: /WinUSB.*Access denied/s });
  });

  it('checks OdinDevice.open\'s options before it claims anything', async () => {
    const { OdinDevice } = await import('../src/index.ts');
    const usb = usbDevice();
    await expect(OdinDevice.open(usb.device, { writable: ['BOOT' as 'RECOVERY'] })).rejects.toMatchObject({ code: 'refused' });
    expect(usb.calls).toEqual([]);
    await OdinDevice.open(usb.device, { writable: ['RECOVERY', 'VBMETA'] });
    expect(usb.calls).toEqual(['open', 'claim 1']);
  });

  it('uses the options as they were when open was called, even if they change while the device opens', async () => {
    const { OdinDevice } = await import('../src/index.ts');
    const writable: string[] = ['RECOVERY'];
    const usb = usbDevice({ open: async () => { writable[0] = 'BOOT'; } }); // changed mid-open
    const odin = await OdinDevice.open(usb.device, { writable: writable as 'RECOVERY'[] });
    expect(usb.calls).toEqual(['open', 'claim 1']);
    await odin.close();
    expect(usb.calls).toEqual(['open', 'claim 1', 'release 1', 'close']);
  });

  it('refuses a device that isn\'t in Download mode', async () => {
    await expect(WebUsbOdinTransport.open(usbDevice({ productId: 0x6860 }).device)).rejects.toMatchObject({ code: 'not-download-mode', message: /04e8:6860/ });
    const noData = usbDevice({ interfaces: [iface(0, 0x02, [])] });
    await expect(WebUsbOdinTransport.open(noData.device)).rejects.toMatchObject({ code: 'not-download-mode' });
  });
});

describe('transfers', () => {
  it('sends a zero-length packet for an empty array', async () => {
    const usb = usbDevice();
    const t = await WebUsbOdinTransport.open(usb.device);
    await t.send(new Uint8Array(0), 100);
    await t.send(new Uint8Array(1024), 100);
    expect(usb.out.map((d) => d.length)).toEqual([0, 1024]);
    expect(usb.calls.slice(-2)).toEqual(['out 2 0', 'out 2 1024']);
  });

  it('takes the answer of a read that timed out, instead of queuing a second read behind it', async () => {
    const usb = usbDevice();
    const t = await WebUsbOdinTransport.open(usb.device);
    await expect(t.receive(512, 10)).rejects.toMatchObject({ code: 'timeout' }); // e.g. the empty read after the PIT
    const next = t.receive(8, 1000);
    usb.deliver([0x65, 0, 0, 0, 0, 0, 0, 0]); // the answer the next read is waiting for
    expect(Array.from(await next)).toEqual([0x65, 0, 0, 0, 0, 0, 0, 0]);
    expect(usb.calls.filter((c) => c.startsWith('in '))).toEqual(['in 1 512']); // one read in all
  });

  it('starts a new read after the browser fails one, even one that had timed out', async () => {
    const usb = usbDevice();
    const t = await WebUsbOdinTransport.open(usb.device);
    await expect(t.receive(512, 10)).rejects.toMatchObject({ code: 'timeout' });
    usb.fail(new DOMException('The transfer was cancelled.', 'AbortError'));
    await new Promise((r) => setTimeout(r, 0));
    const next = t.receive(8, 1000);
    usb.deliver([0x64, 0, 0, 0, 0, 0, 0, 0]);
    expect(Array.from(await next)).toEqual([0x64, 0, 0, 0, 0, 0, 0, 0]);
    expect(usb.calls.filter((c) => c.startsWith('in '))).toEqual(['in 1 512', 'in 1 8']);

    const failing = t.receive(8, 1000); // and one that fails while it's being waited on
    usb.fail(new DOMException('The device was disconnected.', 'NetworkError'));
    await expect(failing).rejects.toThrow(/disconnected/);
    const after = t.receive(8, 1000);
    usb.deliver([0x67, 0, 0, 0, 0, 0, 0, 0]);
    expect(Array.from(await after)).toEqual([0x67, 0, 0, 0, 0, 0, 0, 0]);
  });

  it('skips the zero-length packet a timed-out read was waiting for', async () => {
    const usb = usbDevice();
    const t = await WebUsbOdinTransport.open(usb.device);
    await expect(t.receive(512, 10)).rejects.toMatchObject({ code: 'timeout' });
    const next = t.receive(8, 1000);
    usb.deliver([]);
    await Promise.resolve();
    await new Promise((r) => setTimeout(r, 0));
    usb.deliver([0x66, 0, 0, 0, 0, 0, 0, 0]);
    expect(Array.from(await next)).toEqual([0x66, 0, 0, 0, 0, 0, 0, 0]);
    expect(usb.calls.filter((c) => c.startsWith('in '))).toEqual(['in 1 512', 'in 1 8']);
  });
});
