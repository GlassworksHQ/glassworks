// @vitest-environment jsdom
// The in-place install's screens (src/device/InPlaceScreens.tsx): every receive stage renders and calls back, Bluetooth
// off falls back to the file, and the restore point holds Continue until it is saved and checked.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  fmtEta, fmtMb, fmtSpeed, InPlaceNotice, ReceiveScreen, RestorePointScreen,
  type ReceiveScreenProps, type ReceiveStage, type RestorePointScreenProps,
} from '../src/device/InPlaceScreens';

afterEach(() => cleanup());

const URL_ = 'http://192.0.2.10:18765/';

function receive(over: Partial<ReceiveScreenProps> = {}) {
  const p: ReceiveScreenProps = {
    stage: 'ready', channel: null, bluetoothAvailable: true, progress: null, agentPageUrl: URL_, fallbackReason: null,
    errorMessage: null, words: { the: 'the panel' },
    onConnectBluetooth: vi.fn(), onRetryLan: vi.fn(), onPickFile: vi.fn(), onCancel: vi.fn(), onRetry: vi.fn(),
    ...over,
  };
  render(<ReceiveScreen {...p} />);
  return p;
}
const button = (name: RegExp | string) => screen.getByRole('button', { name }) as HTMLButtonElement;
const embers = () => document.querySelectorAll('.btn.ember');
const pick = (p: ReceiveScreenProps) => {
  const f = new File(['bundle'], 'bundle.bin');
  const input = document.querySelector('input[type=file]') as HTMLInputElement;
  expect(input.accept).toBe('.bin,application/octet-stream');
  fireEvent.change(input, { target: { files: [f] } });
  expect(p.onPickFile).toHaveBeenCalledWith(f);
};

describe('ReceiveScreen', () => {
  it.each<ReceiveStage>(['waiting-boot', 'ready', 'connecting', 'cache-hit', 'transferring', 'lna-denied', 'file-fallback', 'verifying', 'error'])('renders %s with one heading and at most one ember button', (stage) => {
    receive({ stage, channel: 'lan', progress: { receivedBytes: 1, totalBytes: 2, bytesPerSecond: 1, etaSeconds: 1 }, errorMessage: 'boom' });
    expect(screen.getAllByRole('heading')).toHaveLength(1);
    expect(embers().length).toBeLessThanOrEqual(1);
  });

  it('waiting-boot: says it is restarting, shows the frame\'s note and connects over Bluetooth', () => {
    const p = receive({ stage: 'waiting-boot', words: { the: 'the panel', coverNote: 'Keep the back cover off.' } });
    expect(screen.getByRole('heading').textContent).toBe('Waiting for the panel to restart');
    expect(screen.getByText(/first start can take a few minutes/)).toBeTruthy();
    expect(screen.getByText('Keep the back cover off.')).toBeTruthy();
    fireEvent.click(button('Connect over Bluetooth'));
    expect(p.onConnectBluetooth).toHaveBeenCalledOnce();
    fireEvent.click(button('Stop')); // it stops what's running, never the install
    expect(p.onCancel).toHaveBeenCalledOnce();
  });

  it('the hotspot join screen without Bluetooth: Continue stays the main action, and the helper\'s page is one click away', () => {
    const onByHand = vi.fn();
    receive({ stage: 'ready', network: 'Example-Setup', bluetoothAvailable: false, onByHand });
    expect(screen.queryByRole('button', { name: 'Use Bluetooth instead' })).toBeNull();
    fireEvent.click(button('Download it by hand instead'));
    expect(onByHand).toHaveBeenCalledOnce();
    expect(button('Continue')).toBeTruthy();
    cleanup();
    receive({ stage: 'ready', network: 'Example-Setup', bluetoothAvailable: true, onByHand });
    expect(screen.queryByRole('button', { name: 'Download it by hand instead' })).toBeNull(); // Bluetooth is the other way there
  });

  it('a frame\'s title and boot hint replace the defaults', () => {
    receive({ stage: 'waiting-boot', words: { title: 'Hold on', bootHint: 'It restarts twice.' } });
    expect(screen.getByRole('heading').textContent).toBe('Hold on');
    expect(screen.getByText('It restarts twice.')).toBeTruthy();
  });

  it('ready: Bluetooth is the main action', () => {
    const p = receive({ stage: 'ready' });
    const b = button('Connect over Bluetooth');
    expect(b.className).toContain('ember');
    fireEvent.click(b);
    expect(p.onConnectBluetooth).toHaveBeenCalledOnce();
    expect(document.querySelector('input[type=file]')).toBeNull();
  });

  it('ready without Bluetooth: the button is off, says why, and the file is one step away', () => {
    const p = receive({ stage: 'ready', bluetoothAvailable: false });
    expect(button('Connect over Bluetooth').disabled).toBe(true);
    expect(screen.getByText(/can’t use Bluetooth/)).toBeTruthy();
    expect((screen.getByRole('link', { name: /download page/ }) as HTMLAnchorElement).href).toBe(URL_);
    pick(p);
  });

  it('without Bluetooth or an address: no link, and the router is where the address is (#944)', () => {
    receive({ stage: 'ready', bluetoothAvailable: false, agentPageUrl: null, agentPort: 18765 });
    expect(screen.queryByRole('link', { name: /download page/ })).toBeNull();
    expect(screen.getByText(/router’s list of devices, then go to http:\/\/<address>:18765/)).toBeTruthy();
    expect(screen.queryByText(/shown on/)).toBeNull(); // the setup helper has no screen
  });

  it('a guessed address links, and says it may have changed', () => {
    receive({ stage: 'ready', bluetoothAvailable: false, agentPageGuessed: true });
    expect((screen.getByRole('link', { name: /download page/ }) as HTMLAnchorElement).href).toBe(URL_);
    expect(screen.getByText(/If nothing opens once .* has restarted, its address may have changed/)).toBeTruthy();
  });

  it('connecting: says so and offers no second connect', () => {
    receive({ stage: 'connecting' });
    expect(screen.getByRole('status').textContent).toMatch(/Connecting/);
    expect(screen.queryByRole('button', { name: /Bluetooth/ })).toBeNull();
  });

  it('cache-hit: a saved copy skips the transfer', () => {
    receive({ stage: 'cache-hit', channel: 'cache' });
    expect(screen.getByRole('status').textContent).toMatch(/Matched a saved copy on this computer/);
  });

  it('transferring: the percent and the time left first, then the channel, sizes and speed', () => {
    receive({ stage: 'transferring', channel: 'ble', progress: { receivedBytes: 25 * 1024 * 1024, totalBytes: 100 * 1024 * 1024, bytesPerSecond: 20 * 1024, etaSeconds: 240 } });
    expect(screen.getByText('Bluetooth')).toBeTruthy();
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('25');
    expect(screen.getByText('25%')).toBeTruthy();
    expect(screen.getByText('About 4 minutes left')).toBeTruthy();
    expect(screen.getByText('25 MB of 100 MB · 20 KB/s')).toBeTruthy();
    expect(screen.queryByText(/missing pieces/)).toBeNull();
    // Bluetooth is slow: said up front, as normal, with what to keep doing
    expect(screen.getByText(/Bluetooth is slow, and that’s normal/)).toBeTruthy();
  });

  it('transferring with no time yet: the channel\'s usual time stands in', () => {
    receive({ stage: 'transferring', channel: 'ble', progress: { receivedBytes: 1, totalBytes: 100, bytesPerSecond: 0, etaSeconds: null } });
    expect(screen.getByText('Usually about 5 minutes')).toBeTruthy();
  });

  it('transferring: a resend pass and a calm fallback note', () => {
    receive({ stage: 'transferring', channel: 'ble', fallbackReason: 'The local network didn’t answer, so Kiln moved to Bluetooth.', progress: { receivedBytes: 1, totalBytes: 2, bytesPerSecond: 1, etaSeconds: null, resendPass: 1 } });
    expect(screen.getByRole('status').textContent).toBe('Checking missing pieces…');
    const note = screen.getByText(/moved to Bluetooth/);
    expect(note.className).not.toContain('err');
    expect(document.querySelector('.card.err')).toBeNull();
  });

  it('transferring: the manual download opens the file picker', () => {
    const p = receive({ stage: 'transferring', channel: 'lan', progress: { receivedBytes: 1, totalBytes: 2, bytesPerSecond: 1, etaSeconds: 2 } });
    expect(screen.getByText('Local network')).toBeTruthy();
    fireEvent.click(button('Use manual download instead'));
    pick(p);
  });

  it('transferring with no device page: no manual escape', () => {
    receive({ stage: 'transferring', channel: 'lan', agentPageUrl: null, progress: null });
    expect(screen.queryByRole('button', { name: /manual download/ })).toBeNull();
  });

  it('lna-denied: Bluetooth first, then allow the network, open the page or pick the file', () => {
    const p = receive({ stage: 'lna-denied' });
    const b = button('Continue over Bluetooth');
    expect(b.className).toContain('ember');
    fireEvent.click(b);
    expect(p.onConnectBluetooth).toHaveBeenCalledOnce();
    fireEvent.click(button('Allow local network'));
    expect(p.onRetryLan).toHaveBeenCalledOnce();
    const page = screen.getByRole('link', { name: 'Open device page' }) as HTMLAnchorElement;
    expect(page.target).toBe('_blank');
    expect(page.rel).toBe('noopener noreferrer');
    pick(p);
  });

  it('file-fallback: open the page, then select the file', () => {
    const p = receive({ stage: 'file-fallback' });
    expect(screen.getByText('Select the downloaded file')).toBeTruthy();
    const page = screen.getByRole('link', { name: /download page/ }) as HTMLAnchorElement;
    expect(page.href).toBe(URL_);
    expect(page.rel).toBe('noopener noreferrer');
    pick(p);
  });

  it('verifying: a check is running', () => {
    receive({ stage: 'verifying' });
    expect(screen.getByRole('heading').textContent).toBe('Checking what arrived');
    expect(screen.getByRole('status')).toBeTruthy();
  });

  it('error: the device is safe, Try again, the file as a way around, and the message on request', () => {
    const p = receive({ stage: 'error', errorMessage: 'The connection dropped.' });
    expect(document.querySelector('.card.err')).not.toBeNull();
    expect(screen.getByText('The panel’s system hasn’t changed.')).toBeTruthy();
    expect(screen.queryByText('The connection dropped.')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Details' }));
    expect(screen.getByText('The connection dropped.')).toBeTruthy();
    fireEvent.click(button('Try again'));
    expect(p.onRetry).toHaveBeenCalledOnce();
    pick(p);
  });
});

function restore(over: Partial<RestorePointScreenProps> = {}) {
  const p: RestorePointScreenProps = {
    status: 'ready', verifiedDigest: 'a'.repeat(12) + 'b'.repeat(52), downloaded: false, restoreFilename: 'restore-point.bin',
    errorMessage: null, words: { coverNote: 'Keep the back cover off.' }, onDownload: vi.fn(), onContinue: vi.fn(), onRetry: vi.fn(),
    ...over,
  };
  render(<RestorePointScreen {...p} />);
  return p;
}

describe('RestorePointScreen', () => {
  it.each(['saving', 'verifying'] as const)('%s: Continue stays off', (status) => {
    const p = restore({ status });
    const c = button('Continue');
    expect(c.disabled).toBe(true);
    fireEvent.click(c);
    expect(p.onContinue).not.toHaveBeenCalled();
    expect(screen.getByRole('status')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Download/ })).toBeNull();
  });

  it('ready: saved, checked, and Continue waits for a copy on this computer, saying so beside it', () => {
    const p = restore();
    expect(screen.getByText('Saved in this browser')).toBeTruthy();
    const code = screen.getByText('aaaaaaaaaaaa');
    expect(code.getAttribute('title')).toBe(p.verifiedDigest);
    expect(screen.queryByText(/SHA-256/)).toBeNull();
    // until the copy is downloaded, downloading it is the one ember action, and Continue waits
    expect(button('Download a copy').className).toContain('ember');
    const c = button('Continue');
    expect(c.className).not.toContain('ember');
    expect(c.disabled).toBe(true);
    fireEvent.click(c);
    expect(p.onContinue).not.toHaveBeenCalled();
    const why = document.getElementById(c.getAttribute('aria-describedby')!)!;
    expect(why.textContent).toBe('Continue opens once a copy is on this computer.');
    expect(why.closest('.foot')).toBe(c.closest('.foot'));
    fireEvent.click(button('Download a copy'));
    expect(p.onDownload).toHaveBeenCalledOnce();
    expect(screen.getByText(/go back to it at any time/)).toBeTruthy();
    // after the last VOL−: the cover note would ask for it once more
    expect(screen.queryByText('Keep the back cover off.')).toBeNull();
  });

  it('ready and downloaded: says so, and Continue goes on', () => {
    const p = restore({ downloaded: true });
    expect(button('Downloaded ✓').className).not.toContain('ember');
    const c = button('Continue');
    expect(c.className).toContain('ember');
    expect(c.disabled).toBe(false);
    expect(c.hasAttribute('aria-describedby')).toBe(false);
    expect(screen.queryByText(/Continue opens once/)).toBeNull();
    expect(screen.getByText('A copy is on this computer')).toBeTruthy();
    fireEvent.click(c);
    expect(p.onContinue).toHaveBeenCalledOnce();
  });

  it('a download that failed says why, and Continue still waits', () => {
    restore({ downloadError: 'QuotaExceededError' });
    expect(screen.getByRole('alert').textContent).toContain('QuotaExceededError');
    expect(button('Continue').disabled).toBe(true);
  });

  it('error: nothing changed yet, Try again, no Continue', () => {
    const p = restore({ status: 'error', errorMessage: 'QuotaExceededError' });
    expect(document.querySelector('.card.err')).not.toBeNull();
    expect(screen.getByText(/the device’s system hasn’t changed/)).toBeTruthy();
    fireEvent.click(button('Details'));
    expect(screen.getByText('QuotaExceededError')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Continue' })).toBeNull();
    fireEvent.click(button('Try again'));
    expect(p.onRetry).toHaveBeenCalledOnce();
  });
});

describe('InPlaceNotice', () => {
  it('says what changes, with the frame\'s cover note only between the two fastboot sessions', () => {
    const { unmount } = render(<InPlaceNotice words={{ coverNote: 'Keep the back cover off.' }} cover />);
    expect(screen.getByText(/app data starts fresh/)).toBeTruthy();
    expect(screen.getByText('Keep the back cover off.')).toBeTruthy();
    unmount();
    render(<InPlaceNotice words={{ coverNote: 'Keep the back cover off.' }} />);
    expect(screen.queryByText('Keep the back cover off.')).toBeNull();
  });
});

describe('formatting', () => {
  it.each([[0.4, '~1s left'], [2, '~2s left'], [240, '~4 min left'], [null, null], [-1, null]] as const)('time left %s', (s, want) => {
    expect(fmtEta(s)).toBe(want);
  });
  it('sizes and speeds', () => {
    expect(fmtMb(1.5 * 1024 * 1024)).toBe('1.5 MB');
    expect(fmtMb(250 * 1024 * 1024)).toBe('250 MB');
    expect(fmtSpeed(512 * 1024)).toBe('512 KB/s');
    expect(fmtSpeed(3 * 1024 * 1024)).toBe('3.0 MB/s');
  });
});
