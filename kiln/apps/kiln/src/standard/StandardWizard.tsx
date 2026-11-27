'use client';
// The standard flow's page for a frame whose install ran on its own screens (a device frame's page): the same shell,
// rail, set-up screens, Done, error screen and "your turn" sheet as the wizard's (wizard/screens.tsx), on any frame's
// StandardSession. The routes are useRouteSync's, as the wizard's are: /<frame>/set-up, /set-up/wifi, …, /done.
import { usePathname, useRouter } from 'next/navigation';
import { useEffect, useMemo, useSyncExternalStore, type ReactNode } from 'react';
import { Shell, useBrowserChip } from '../components/Shell';
import type { Entry } from '../wizard/registry';
import { ROUTES, status } from '../wizard/routes';
import { Browser, Done, Failure, SetUp, Waiting, YourTurn } from '../wizard/screens';
import { stackLevel, standardRail, type WizardState } from '../wizard/state';
import { useRouteSync } from '../wizard/useRouteSync';
import type { StandardFrame } from './frame';
import type { StandardSession } from './session';

function announce(s: WizardState, f: StandardFrame): string {
  if (s.ask) return `Your turn. ${f.connect.title}. Pick ${f.connect.pick} in the list.`;
  if (s.screen === 'error') return `${s.failure?.title}. ${s.failure?.body}`;
  if (s.screen === 'done') return s.done ? `${s.done.title}.${s.done.updates ? ` Silent updates: ${s.done.updates.word}.` : ''}` : '';
  return '';
}

/**
 * `before`: the rail's steps of the frame's install, all done here. `outcome`: what the install itself left to say on
 * Done (the home screen, Home Assistant's key, the way back), under the standard flow's own lines.
 */
export function StandardWizard({ session, base, before, outcome }: { session: StandardSession; base: string; before: readonly string[]; outcome?: ReactNode }) {
  const s = useSyncExternalStore(session.store.subscribe, session.store.get, session.store.get);
  const chip = useBrowserChip();
  const f = session.standard;
  useEffect(() => {
    document.title = s.ask ? `Your turn · ${f.name} · Kiln` : `${f.name} · Kiln`;
  }, [s.ask, f]);
  const sp = { s, session, frame: f };
  return (
    <Shell steps={standardRail(s, before, f.steps)} level={stackLevel(s)} error={s.screen === 'error'} chip={s.ask ? { kind: 'work', text: 'Your turn' } : chip}
      href={status(s) === 'idle' ? (r) => base + ROUTES[r] : undefined}>
      {chip?.kind === 'bad' ? <Browser />
        : s.screen === 'setup' ? <SetUp {...sp} />
        : s.screen === 'done' ? <Done {...sp}>{outcome}</Done>
        : s.screen === 'error' ? <Failure s={s} session={session} />
        : <Waiting setup={{ phase: 'waiting', note: '' }} />}
      <YourTurn s={s} session={session} asks={{ connect: f.connect }} the={f.the} />
      <p className="visually-hidden" aria-live="polite">{announce(s, f)}</p>
    </Shell>
  );
}

/** The standard flow on a frame's routes: its session's entry in the registry, kept in step with the address bar. */
export function StandardRoutes({ entry, renew, before, outcome }: { entry: Entry; renew: () => Entry; before: readonly string[]; outcome?: ReactNode }) {
  const router = useRouter();
  const path = usePathname();
  const nav = useMemo(() => ({
    replace: (url: string) => router.replace(url, { scroll: false }),
    push: (url: string) => router.push(url, { scroll: false }),
  }), [router]);
  useRouteSync(entry, path, nav, renew);
  return <StandardWizard session={entry.session} base={entry.base} before={before} outcome={outcome} />;
}
