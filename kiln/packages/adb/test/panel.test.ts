// Panel.connect with authMs: one deadline covers every wait after the browser's list (finding the device again,
// claiming it, the key), so a stalled USB call can't hold a caller that asks again (review on PR 607).
import { describe, expect, it, vi } from 'vitest';

const never = <T>() => new Promise<T>(() => {});
const devices: { getDevices: () => Promise<unknown[]> } = { getDevices: async () => [] };
vi.mock('@yume-chan/adb-daemon-webusb', () => ({
  AdbDaemonWebUsbDeviceManager: { get BROWSER() { return devices; } },
  AdbDaemonWebUsbDevice: { DeviceBusyError: class extends Error {} },
}));

const { AdbAuthTimeout, Panel } = await import('../src/panel');
const key = { store: {} } as never;

describe('Panel.connect with authMs', () => {
  it('gives up when finding the device again stalls', async () => {
    devices.getDevices = () => never();
    await expect(Panel.connect(key, {} as USBDevice, { authMs: 20 })).rejects.toBeInstanceOf(AdbAuthTimeout);
  });

  it('gives up when claiming the device stalls, and lets go of it if the claim comes late', async () => {
    const raw = { close: vi.fn(async () => {}) };
    let claimed!: (c: unknown) => void;
    devices.getDevices = async () => [{ raw, serial: 'x', connect: () => new Promise((r) => { claimed = r; }) }];
    await expect(Panel.connect(key, raw as unknown as USBDevice, { authMs: 20 })).rejects.toBeInstanceOf(AdbAuthTimeout);
    claimed({});
    await new Promise((r) => setTimeout(r, 0));
    expect(raw.close).toHaveBeenCalled();
  });
});
