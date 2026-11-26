'use client';
// The wizard for one frame: the shell, the screen for the current state, the "your turn" sheet, and
// live announcements. The routes are useRouteSync's; the leave-page warning and the wake lock are
// LeaveGuard's (root layout), so they hold on every route.
import { useEffect, useSyncExternalStore } from 'react';
import { Shell, useBrowserChip } from '../components/Shell';
import type { KilnFrame } from '@glassworks/kiln-core';
import { Browser, Connect, Done, Failure, Firing, SetUp, Undo, YourTurn } from './screens';
import type { Session } from './session';
import { ROUTES, status } from './routes';
import { milestones, rail, stackLevel, type RailStep, type WizardState } from './state';

function announce(s: WizardState, frame: KilnFrame): string {
  if (s.ask) return `Your turn. ${frame.asks[s.ask.kind].title}. Pick ${frame.asks[s.ask.kind].pick} in the list.`;
  if (s.screen === 'firing') { const m = milestones(s, frame.short).at(-1); return m ? `${m.text}${m.done ? ', done' : ''}.` : ''; }
  if (s.screen === 'error') return `${s.failure?.title}. ${s.failure?.body}`;
  // the Silent updates read-back lands after Done opens: its word changes the text, so it is read out too
  if (s.screen === 'done') return s.done ? `${s.done.title}.${s.done.updates ? ` Silent updates: ${s.done.updates.word}.` : ''}` : '';
  return '';
}

/**
 * The wizard's rail with its way back (docs/design/kiln/navigation.md): from Connect to the picker, from the undo's
 * Connect to its backup step, while nothing runs. Once the new software is written, the steps before are locked.
 */
export function wizardNav(s: WizardState, session: Session): RailStep[] {
  const idle = status(s) === 'idle' && !s.busy;
  const written = s.wrote || (s.flow === 'install' && (s.screen === 'setup' || s.screen === 'done'));
  return rail(s, session.standard.steps).map((x, i) => {
    if (x.mark !== 'done') return x;
    if (idle && s.screen === 'connect' && i === 0) return { ...x, go: s.flow === 'install' ? { href: '/' } : { run: () => void session.click('undo', () => session.openUndo()) } };
    return written && s.flow === 'install' && i < 3 ? { ...x, lock: true } : x;
  });
}

export function Wizard({ session, frame, base }: { session: Session; frame: KilnFrame; base: string }) {
  const s = useSyncExternalStore(session.store.subscribe, session.store.get, session.store.get);
  const chip = useBrowserChip();

  useEffect(() => {
    document.title = s.ask ? `Your turn · ${frame.name} · Kiln` : `${frame.name} · Kiln`;
  }, [s.ask, frame]);

  const p = { s, session, frame };
  const sp = { s, session, frame: session.standard };
  const unsupported = chip?.kind === 'bad';
  return (
    <Shell steps={wizardNav(s, session)} lock={`The new software is on ${frame.the}`} level={stackLevel(s)} error={s.screen === 'error'} chip={s.ask ? { kind: 'work', text: 'Your turn' } : chip}
      href={status(s) === 'idle' ? (r) => base + ROUTES[r] : undefined}>
      {unsupported ? <Browser />
        : s.screen === 'connect' ? <Connect {...p} />
        : s.screen === 'firing' ? <Firing {...p} />
        : s.screen === 'setup' ? <SetUp {...sp} />
        : s.screen === 'done' ? <Done {...sp} />
        : s.screen === 'undo' ? <Undo {...p} />
        : <Failure s={s} session={session} help={frame.help} />}
      <YourTurn s={s} session={session} asks={frame.asks} />
      <p className="visually-hidden" aria-live="polite">{announce(s, frame)}</p>
    </Shell>
  );
}
