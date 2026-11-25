// @vitest-environment jsdom
// The panel's own page, once it sends the Wi-Fi (issue 460): the join closes the panel's setup Wi-Fi and this page's
// connection within moments, so the page shows one last, plain state. No steps that wait for news that can't come.
// Kiln's wizard over USB still follows the join step by step: Improv Serial reports it.
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { joinNote } from '../src/setup/channelSetup';
import { Wifi } from '../src/setup/steps';
import type { Setup } from '../src/wizard/state';

afterEach(() => cleanup());

const steps = { click: async () => {}, wifi: async () => {}, scanWifi: async () => {}, joinWifi: async () => {}, homeAssistant: async () => {} };
type WifiSetup = Extract<Setup, { phase: 'wifi' }>;
const sent = (over: 'lan' | undefined, step: 'sent' | 'join' | 'ok' | 'err'): WifiSetup => ({
  phase: 'wifi', status: '', over, joining: { ssid: 'Example-Home', step, error: step === 'err' ? 'the reason' : undefined },
  joinNote: over === 'lan' && step === 'join' ? joinNote('Example-Home') : undefined,
});

describe('the panel\'s page after the Wi-Fi is sent', () => {
  it('says it is sent, that the page can\'t follow, and to watch the panel; no step list, no buttons', () => {
    const { container } = render(<Wifi busy={false} steps={steps} setup={sent('lan', 'join')} />);
    expect(screen.getByRole('heading').textContent).toBe('Sent to the panel');
    const text = container.textContent ?? '';
    expect(text).toMatch(/switching to Example-Home now/);
    expect(text).toMatch(/can't follow it across networks/);
    expect(text).toMatch(/continue on the panel's screen/);
    expect(text).toMatch(/reconnect this device to your regular Wi-Fi/);
    expect(container.querySelector('ol')).toBeNull();
    expect(container.querySelector('.bar, [role=progressbar]')).toBeNull();
    expect(screen.queryAllByRole('button')).toHaveLength(0);
    expect(text).not.toMatch(/Connected|joins the network/);
  });

  it('says it in one polite live region, there from "Sending…" on, so a screen reader hears the change', () => {
    const { rerender } = render(<Wifi busy={false} steps={steps} setup={sent('lan', 'sent')} />);
    const region = screen.getByRole('status');
    expect(region.getAttribute('aria-live')).toBe('polite');
    rerender(<Wifi busy={false} steps={steps} setup={sent('lan', 'join')} />);
    expect(screen.getByRole('status')).toBe(region);
    expect(region.textContent).toMatch(/Sent to the panel[\s\S]*continue on the panel's screen/);
  });

  it('while the reply is on its way: sending, with nothing that waits for more', () => {
    const { container } = render(<Wifi busy={false} steps={steps} setup={sent('lan', 'sent')} />);
    expect(screen.getByRole('heading').textContent).toBe('Sending to the panel…');
    expect(container.querySelector('ol')).toBeNull();
  });

  it('the panel answering later that it never left is still an error, with Try again', () => {
    const { container } = render(<Wifi busy={false} steps={steps} setup={sent('lan', 'err')} />);
    expect(screen.getByRole('heading').textContent).toBe('Couldn\'t join Example-Home');
    expect(container.querySelector('.plist .prow.done .plabel')?.textContent).toBe('Send it to the panel');
    expect(container.querySelector('.plist .prow.bad .plabel')?.textContent).toBe('The panel joins the network');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
  });

  it('Kiln over USB still follows the join step by step', () => {
    const { container } = render(<Wifi busy={false} steps={steps} setup={sent(undefined, 'join')} />);
    expect(screen.getByRole('heading').textContent).toBe('Joining Example-Home…');
    expect([...container.querySelectorAll('.plist .prow')].map((li) => `${li.className.replace('prow ', '')} ${li.querySelector('.plabel')!.textContent}`))
      .toEqual(['done Send it to the panel over USB', 'now The panel joins the network', 'next Check it’s connected']);
  });
});
