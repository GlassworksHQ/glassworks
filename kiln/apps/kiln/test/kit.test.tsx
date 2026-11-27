// @vitest-environment jsdom
// Kiln's component kit (src/components/Kit.tsx, docs/design/kiln/system.md): progress that says done, now and next
// and never goes back, waits that say how long and stay alive, physical steps to tick off, the browser's list before it
// opens, and errors that say the device is safe first. And the shell around them: focus goes to a new screen's heading,
// and on a phone the flow's ways out sit under the card.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BrowserAsk, Instructions, Problem, Progress, Wait, clock, rowsAt, typicalSeconds, useForward } from '../src/components/Kit';
import { Shell } from '../src/components/Shell';
import { Foot } from '../src/components/Nav';

afterEach(() => { cleanup(); vi.useRealTimers(); });

describe('typicalSeconds', () => {
  it.each([
    ['4–5 minutes', 300], ['about a minute', 60], ['1–2 minutes', 120], ['30 seconds', 30], ['about 10 minutes', 600], ['a few minutes', 180],
  ])('%s → %d s', (t, s) => expect(typicalSeconds(t)).toBe(s));

  it('is undefined for words with no time in them', () => {
    expect(typicalSeconds(undefined)).toBeUndefined();
    expect(typicalSeconds('soon')).toBeUndefined();
  });

  it('a clock reads m:ss', () => {
    expect(clock(0)).toBe('0:00');
    expect(clock(83)).toBe('1:23');
    expect(clock(605)).toBe('10:05');
  });
});

describe('Progress', () => {
  const ROWS = [{ id: 'a', label: 'Write the new system' }, { id: 'b', label: 'Restart it', time: 'About 5 minutes' }, { id: 'c', label: 'Install it' }];
  const row = (label: string) => screen.getByText(label).closest('li')!;

  it('marks every step done, now or next, says so in words, and counts where it is', () => {
    render(<Progress label="The install" rows={rowsAt(ROWS, 1)} />);
    expect(row('Write the new system').className).toContain('done');
    expect(row('Write the new system').textContent).toContain('Done');
    expect(row('Restart it').className).toContain('now');
    expect(row('Restart it').getAttribute('aria-current')).toBe('step');
    expect(row('Restart it').textContent).toContain('Now');
    // a step still to come says how long it usually takes
    expect(row('Install it').textContent).toBe('3Install it');
    expect(screen.getByText('Step 2 of 3')).toBeTruthy();
    expect(screen.getByRole('progressbar').getAttribute('aria-valuetext')).toBe('Step 2 of 3: Restart it');
    expect(screen.getByRole('status').textContent).toBe('Step 2 of 3: Restart it. Now.');
  });

  it('the running step shows its own percent and its note; one waiting on the person says Your turn', () => {
    const { unmount } = render(<Progress label="x" rows={rowsAt(ROWS, 0).map((r, i) => (i === 0 ? { ...r, x: 0.42, note: 'The bar may pause. That’s normal.' } : r))} />);
    expect(row('Write the new system').textContent).toContain('42%');
    expect(screen.getByText('The bar may pause. That’s normal.')).toBeTruthy();
    expect(row('Restart it').textContent).toContain('About 5 minutes');
    unmount();
    render(<Progress label="x" rows={rowsAt(ROWS, 1, 'turn')} />);
    expect(row('Restart it').textContent).toContain('Your turn');
  });

  it('a stopped step is marked, and the bar says so', () => {
    render(<Progress label="x" rows={rowsAt(ROWS, 1, 'bad')} />);
    expect(row('Restart it').textContent).toContain('Stopped');
    expect(document.querySelector('.meter.bad')).not.toBeNull();
  });

  it('all done', () => {
    render(<Progress label="x" rows={rowsAt(ROWS, 3)} />);
    expect(document.querySelector('.progress-count')!.textContent).toBe('All done');
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('100');
  });

  it('a screen that announces its steps itself turns the list\'s own announcement off', () => {
    render(<Progress label="x" live={false} rows={rowsAt(ROWS, 1)} />);
    expect(screen.queryByRole('status')).toBeNull();
  });
});

describe('useForward', () => {
  function Probe() {
    const [at, setAt] = useState(2);
    const shown = useForward(at);
    return <button type="button" onClick={() => setAt((x) => (x === 2 ? 0 : 3))}>{shown}</button>;
  }
  it('only moves forward while a screen shows', () => {
    render(<Probe />);
    const b = screen.getByRole('button');
    expect(b.textContent).toBe('2');
    fireEvent.click(b); // a step reported late
    expect(b.textContent).toBe('2');
    fireEvent.click(b);
    expect(b.textContent).toBe('3');
  });
});

describe('Wait', () => {
  it('says how long it usually takes, how long it has taken, and that it\'s normal; past the usual time, calmly', () => {
    vi.useFakeTimers();
    let t = 1_000_000;
    render(<Wait what="Waiting for its first start" typical="4–5 minutes" note="The screen stays dark. That’s normal." now={() => t} />);
    expect(screen.getByRole('status').textContent).toBe('Waiting for its first start');
    expect(screen.getByText('Usually 4–5 minutes')).toBeTruthy();
    expect(screen.getByText('0:00 so far')).toBeTruthy();
    expect(screen.getByText('The screen stays dark. That’s normal.')).toBeTruthy();
    t += 83_000;
    act(() => { vi.advanceTimersByTime(1000); });
    expect(screen.getByText('1:23 so far')).toBeTruthy();
    // five minutes is usual; it says so only well past that, and never as an error
    t += 6 * 60_000;
    act(() => { vi.advanceTimersByTime(1000); });
    expect(screen.getByText(/taking longer than usual\. That can still be normal/)).toBeTruthy();
    expect(document.querySelector('.wait.long')).not.toBeNull();
    expect(document.querySelector('.err, [role=alert]')).toBeNull();
  });
});

describe('Instructions', () => {
  it('numbers each step, and the person ticks them off as they go', () => {
    render(<Instructions label="Fastboot" steps={['Switch it off.', 'Hold VOL−.', 'Switch it on.']} />);
    const steps = () => [...document.querySelectorAll('.isteps li')];
    expect(steps()[0]!.className).toBe('up');
    fireEvent.click(screen.getByRole('button', { name: 'Step 1' }));
    expect(screen.getByRole('button', { name: 'Step 1, done' }).getAttribute('aria-pressed')).toBe('true');
    expect(steps()[0]!.className).toBe('ticked');
    expect(steps()[1]!.className).toBe('up'); // the next one to do
    fireEvent.click(screen.getByRole('button', { name: 'Step 1, done' }));
    expect(steps()[0]!.className).toBe('up');
  });
});

describe('BrowserAsk', () => {
  it('before the browser\'s list: the line to pick, as the list shows it', () => {
    const { unmount } = render(<BrowserAsk listed="Android" short="Mirror" the="the Mirror" />);
    expect(document.querySelector('.ask-row')!.textContent).toContain('Android');
    expect(screen.getByRole('note').textContent).toContain('Pick Android, which is the Mirror, then click Connect.');
    unmount();
    render(<BrowserAsk short="panel" the="the panel" />);
    expect(screen.getByRole('note').textContent).toContain('Pick the panel, then click Connect.');
  });
});

describe('Problem', () => {
  it('says whether the device is safe first, what to do next, and keeps the raw error for whoever asks', () => {
    render(<Problem title="The copy didn’t finish" safe="The panel’s system hasn’t changed." fix="Try again." details={<pre>ECONNRESET</pre>}><Foot><button type="button" className="btn ember">Try again</button></Foot></Problem>);
    const order = [...document.querySelector('.card.err')!.children].map((x) => x.className || x.tagName);
    expect(order.slice(0, 4)).toEqual(['err-mark', 'H2', 'note ok', 'fix']);
    expect(screen.queryByText('ECONNRESET')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Details' }));
    expect(screen.getByText('ECONNRESET')).toBeTruthy();
  });
});

describe('the shell', () => {
  const steps = (at: number) => ['One', 'Two', 'Three'].map((name, i) => ({ name, mark: (i < at ? 'done' : i === at ? 'now' : 'todo') as 'done' | 'now' | 'todo' }));
  function Flow() {
    const [at, setAt] = useState(0);
    return (
      <Shell steps={steps(at)} level={0}>
        <section className="card"><h2>{`Screen ${at}`}</h2>{at === 0 && <Foot><button type="button" className="btn ember" onClick={() => setAt(1)}>Continue</button></Foot>}</section>
      </Shell>
    );
  }

  it('a new screen under a click moves focus to its heading; the first screen keeps it where it is', () => {
    render(<Flow />);
    expect(document.activeElement).toBe(document.body);
    const go = screen.getByRole('button', { name: 'Continue' });
    go.focus();
    fireEvent.click(go);
    const h = screen.getByRole('heading', { name: 'Screen 1' });
    expect(document.activeElement).toBe(h);
    expect(h.getAttribute('tabindex')).toBe('-1');
  });

  it('on a phone, the flow\'s ways out sit under the card too, not only in the Steps sheet', () => {
    const mm = vi.fn((q: string) => ({ matches: q.includes('760'), media: q, addEventListener: vi.fn(), removeEventListener: vi.fn() }));
    vi.stubGlobal('matchMedia', mm);
    try {
      const run = vi.fn();
      render(<Shell steps={steps(1)} level={0} actions={[{ label: 'Start over', run }]}><section className="card"><h2>x</h2></section></Shell>);
      const under = document.querySelector('.flow-under') as HTMLElement;
      fireEvent.click(under.querySelector('button')!);
      expect(run).toHaveBeenCalledOnce();
    } finally { vi.unstubAllGlobals(); }
    cleanup();
    render(<Shell steps={steps(1)} level={0} actions={[{ label: 'Start over', run: vi.fn() }]}><section className="card"><h2>x</h2></section></Shell>);
    expect(document.querySelector('.flow-under')).toBeNull(); // a computer: under the rail only
  });
});
