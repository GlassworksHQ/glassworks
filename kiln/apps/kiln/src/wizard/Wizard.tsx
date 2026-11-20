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
import { milestones, rail, stackLevel, type WizardState } from './state';

function announce(s: WizardState, frame: KilnFrame): string {
  if (s.ask) return `Your turn. ${frame.asks[s.ask.kind].title} Pick ${frame.asks[s.ask.kind].pick} in the list.`;
  if (s.screen === 'firing') { const m = milestones(s, frame.short).at(-1); return m ? `${m.text}${m.done ? ', done' : ''}.` : ''; }
  if (s.screen === 'error') return `${s.failure?.title} ${s.failure?.body}`;
  // the Silent updates read-back lands after Done opens: its word changes the text, so it is read out too
  if (s.screen === 'done') return s.done ? `${s.done.title}${s.done.updates ? ` Silent updates: ${s.done.updates.word}.` : ''}` : '';
  return '';
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
    <Shell steps={rail(s, session.standard.steps)} level={stackLevel(s)} error={s.screen === 'error'} chip={s.ask ? { kind: 'work', text: 'Your turn' } : chip}
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
