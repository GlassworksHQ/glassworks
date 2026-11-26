// kiln-core's FlowUi, as wizard actions. The engine calls these; the reducer turns them into screens.
import type { AskKind, FlowUi } from '@glassworks/kiln-core';
import type { Action } from './state';

const notPicked = (e: unknown) => e instanceof DOMException && e.name === 'NotFoundError';

/**
 * The "your turn" sheet's other half. WebUSB opens a new device only from a click, so the engine
 * hands over an `action` that calls `requestDevice()`, and the sheet's button calls `click()`.
 * `click()` starts `action` before anything else, with no `await` in between, so the browser still
 * counts the call as part of the click (test/in-click.test.tsx guards this).
 */
export class AskBridge {
  /** The ask whose picker is open. Tied to that ask, so aborting it never blocks the next one. */
  private picking?: object;
  private pending?: { kind: AskKind; action: () => Promise<unknown>; resolve: (v: unknown) => void; reject: (e: unknown) => void; signal: AbortSignal };

  constructor(private readonly dispatch: (a: Action) => void) {}

  open<T>(kind: AskKind, action: () => Promise<T>, signal: AbortSignal): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const end = () => {
        if (this.pending?.signal !== signal) return;
        this.pending = undefined;
        this.dispatch({ type: 'ask-end' });
      };
      // Found by itself after all (the browser had allowed it before): the sheet just goes.
      signal.addEventListener('abort', end, { once: true });
      this.pending = { kind, action, resolve: resolve as (v: unknown) => void, reject, signal };
      this.dispatch({ type: 'ask', kind });
    });
  }

  /**
   * Not now, on the set-up's connect ask (kind `connect`) only: the ask ends with `Declined`, so the step that asked
   * stops on its error screen, with Try again. An install's own asks (fastboot, adb, mid-write) can't be declined.
   */
  decline(): boolean {
    const p = this.pending;
    if (!p || p.kind !== 'connect' || this.picking === p) return false;
    this.pending = undefined;
    this.dispatch({ type: 'ask-end' });
    p.reject(new Declined());
    return true;
  }

  /** Whether the open ask can be declined (decline). */
  declinable(): boolean { return this.pending?.kind === 'connect' && this.picking !== this.pending; }

  /** Call straight from the button's onClick. */
  click(): Promise<void> {
    const p = this.pending;
    if (!p || this.picking === p) return Promise.resolve(); // a second click while its picker is open
    this.picking = p;
    let run: Promise<unknown>;
    try { run = p.action(); } catch (e) { run = Promise.reject(e); } // a synchronous throw takes the same path
    this.dispatch({ type: 'ask-busy' });
    return run.finally(() => { if (this.picking === p) this.picking = undefined; }).then(
      (v) => {
        if (this.pending === p) { this.pending = undefined; this.dispatch({ type: 'ask-end' }); }
        p.resolve(v);
      },
      (e: unknown) => {
        if (this.pending !== p) return; // a stale picker, from an ask that was called off
        this.dispatch({ type: 'ask-error', error: notPicked(e) ? 'No device was selected. Click the button again to open the list.' : String((e as Error)?.message ?? e) });
      },
    );
  }
}

/** The person said Not now to a connect ask (AskBridge.decline). */
export class Declined extends Error {
  constructor() { super('Not connected yet. When it\'s plugged in and running, click Try again.'); this.name = 'Declined'; }
}

const stamp = () => new Date().toTimeString().slice(0, 8);

/** The adapter InstallFlow talks to. */
export function flowUi(dispatch: (a: Action) => void, asks: AskBridge): FlowUi & { note(line: string): void } {
  return {
    log: (line) => dispatch({ type: 'log', line }),
    /** A line of the wizard's own, stamped like the engine's. */
    note: (line) => dispatch({ type: 'log', line: `${stamp()} ${line}` }),
    progress: (p) => dispatch({ type: 'progress', p }),
    partition: (part, state, info) => dispatch({ type: 'partition', part, state, info }),
    ask: (kind, action, signal) => asks.open(kind, action, signal),
  };
}
