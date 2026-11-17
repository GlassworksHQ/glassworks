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
    expect(screen.getByRole('heading').textContent).toBe('Sent to the panel.');
    const text = container.textContent ?? '';
    expect(text).toMatch(/switching to Example-Home now/);
    expect(text).toMatch(/can't follow it from here/);
    expect(text).toMatch(/watch the panel's screen/);
    expect(text).toMatch(/Put this phone back on your own Wi-Fi/);
    expect(container.querySelector('ol')).toBeNull();
    expect(container.querySelector('.bar, [role=progressbar]')).toBeNull();
    expect(screen.queryAllByRole('button')).toHaveLength(0);
    expect(text).not.toMatch(/Connected|joins the network/);
  });

  it('while the reply is on its way: sending, with nothing that waits for more', () => {
    const { container } = render(<Wifi busy={false} steps={steps} setup={sent('lan', 'sent')} />);
    expect(screen.getByRole('heading').textContent).toBe('Sending to the panel…');
    expect(container.querySelector('ol')).toBeNull();
  });

  it('the panel answering later that it never left is still an error, with Try again', () => {
    const { container } = render(<Wifi busy={false} steps={steps} setup={sent('lan', 'err')} />);
    expect(screen.getByRole('heading').textContent).toBe('Couldn\'t join Example-Home.');
    expect(container.querySelector('ol li')?.textContent).toBe('Sent to the panel');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
  });

  it('Kiln over USB still follows the join step by step', () => {
    const { container } = render(<Wifi busy={false} steps={steps} setup={sent(undefined, 'join')} />);
    expect(screen.getByRole('heading').textContent).toBe('Joining Example-Home…');
    expect([...container.querySelectorAll('ol li')].map((li) => li.textContent)).toEqual(['Sent to the panel over USB', 'The panel joins the network', 'Connected']);
  });
});
