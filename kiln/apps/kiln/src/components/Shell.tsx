'use client';
// The frame around every screen (docs/design/kiln): the header with Kiln's mark and the browser chip, the rail with the
// steps and the stack, and on a phone a slim step bar whose Steps sheet holds the same rail. The rail is the map
// (docs/design/kiln/navigation.md): a done step the flow lets you return to is a link, one behind a write shows a lock
// and the rail says why, and every screen's Back is the rail's step before this one (NavContext, read by Foot).
import * as Dialog from '@radix-ui/react-dialog';
import Link from 'next/link';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Mark } from './Mark';
import { Stack } from './Stack';
import { FlowAction, GoControl, LinkContext, LockGlyph, NavContext, type Go, type NavValue } from './Nav';

/** A flow's own action (Start over, Restore the original system): under the rail, and in the phone's Steps sheet. */
export interface FlowActionSpec { label: string; icon?: 'restart' | 'restore'; run(): void }
import type { RailStep } from '../wizard/state';

export type Chip = { kind: 'ok' | 'work' | 'bad'; text: string };

/** Demos run in `next dev` and in a build made with NEXT_PUBLIC_KILN_DEMO=1, as the pages' own do. */
const demoOn = () => process.env.NODE_ENV === 'development' || process.env.NEXT_PUBLIC_KILN_DEMO === '1';

/**
 * WebUSB needs Chrome or Edge, and a secure page. Known only in the browser. `&browser=no-usb` (where demos run) acts
 * as a browser without WebUSB, on any page, so its Browser screen can be seen in Chrome.
 */
export function useBrowserChip(): Chip | undefined {
  const [chip, setChip] = useState<Chip>();
  useEffect(() => {
    const usb = 'usb' in navigator && !(demoOn() && new URLSearchParams(location.search).get('browser') === 'no-usb');
    setChip(usb && isSecureContext ? { kind: 'ok', text: 'Ready' } : { kind: 'bad', text: usb ? 'Needs HTTPS' : 'Use Chrome or Edge' });
  }, []);
  return chip;
}

type SubRoute = NonNullable<RailStep['sub']>[number]['route'];

/** The set-up steps' links (`href`), as each sub-step's `go`: only the done ones, only while nothing runs. */
function withSubLinks(steps: RailStep[], href?: (route: SubRoute) => string): RailStep[] {
  if (!href) return steps;
  return steps.map((s) => (s.sub ? { ...s, sub: s.sub.map((x) => (x.mark === 'done' && !x.go ? { ...x, go: { href: href(x.route) } } : x)) } : s));
}

/**
 * Back, from the rail: the set-up step before this one, else the step before this one when the flow lets you return to
 * it. None while it runs (no `go` then), and `locked` when the step before is behind a write.
 */
export function backOf(steps: readonly RailStep[]): { back?: Go; locked?: boolean } {
  const i = steps.findIndex((s) => s.mark !== 'done');
  if (i < 0) return {};
  const cur = steps[i]!;
  const j = cur.sub ? cur.sub.findIndex((x) => x.mark !== 'done') : -1;
  if (cur.sub && j > 0) return cur.sub[j - 1]!.go ? { back: cur.sub[j - 1]!.go } : {};
  const prev = steps[i - 1];
  if (!prev) return {};
  return prev.go ? { back: prev.go } : { locked: !!prev.lock };
}

function Rail({ steps, onGo }: { steps: RailStep[]; onGo?: () => void }) {
  return (
    <ol className="steps">
      {steps.map((s) => (
        <li key={s.name} className={`${s.mark}${s.sub ? ' has-sub' : ''}${s.mark === 'done' && s.go ? ' can-go' : ''}`} aria-current={!s.sub && (s.mark === 'now' || s.mark === 'turn' || s.mark === 'bad') ? 'step' : undefined}>
          <span className="mk" />
          {s.mark === 'done' && s.go
            ? <GoControl go={s.go} className="step-go" onDone={onGo} aria-label={`Back to ${s.name}`}>{s.name}</GoControl>
            : <span className="step-name">{s.name}</span>}
          {s.mark === 'done' && s.lock && <span className="step-lock" title="Written to the device"><LockGlyph /></span>}
          {s.sub && (
            <ol className="sub">
              {s.sub.map((x) => (
                <li key={x.name} className={x.mark} aria-current={x.mark === 'now' ? 'step' : undefined}>
                  {x.mark === 'done' && x.go ? <GoControl go={x.go} onDone={onGo}>{x.name}</GoControl> : <span>{x.name}</span>}
                </li>
              ))}
            </ol>
          )}
        </li>
      ))}
    </ol>
  );
}

/** What the rail says under its steps: why the locked ones can't be returned to, then the flow's own actions. */
function RailFoot({ steps, lock, actions, run = (a) => a.run() }: { steps: RailStep[]; lock?: string; actions?: readonly FlowActionSpec[]; run?: (a: FlowActionSpec) => void }) {
  const locked = !!lock && steps.some((s) => s.mark === 'done' && s.lock);
  if (!locked && !actions?.length) return null;
  return (
    <div className="rail-foot">
      {locked && <p className="rail-note"><LockGlyph />{lock}</p>}
      {!!actions?.length && <div className="rail-actions">{actions.map((a) => <FlowAction key={a.label} icon={a.icon} onClick={() => run(a)}>{a.label}</FlowAction>)}</div>}
    </div>
  );
}

export function Shell({ steps: given, level, error, chip, href, lock, actions, back, children }: {
  steps: RailStep[];
  /** where a set-up step on the rail goes back to; undefined while it can't (something runs) */
  href?: (route: SubRoute) => string;
  level: number;
  error?: boolean;
  chip?: Chip;
  /** Why the locked steps are behind you: what is written to the device. */
  lock?: string;
  /** The flow's own actions (Start over, Restore the original system): under the rail, and in the phone's Steps sheet. */
  actions?: readonly FlowActionSpec[];
  /** This screen's Back in place of the rail's; `null`: none. */
  back?: Go | null;
  children: ReactNode;
}) {
  const steps = useMemo(() => withSubLinks(given, href), [given, href]);
  const now = Math.max(0, steps.findIndex((s) => s.mark !== 'done'));
  const allDone = steps.every((s) => s.mark === 'done');
  const at = allDone ? steps.length : now + 1;
  const cur = steps[now];
  const curName = allDone ? 'Done' : cur?.sub?.find((x) => x.mark !== 'done')?.name ?? cur?.name ?? '';
  const [sheet, setSheet] = useState(false);
  // an action from the Steps sheet runs once the sheet has gone: its own dialog (Start over) must not open under a closing one
  const after = useRef<FlowActionSpec | undefined>(undefined);
  useEffect(() => {
    if (sheet || !after.current) return;
    const a = after.current;
    after.current = undefined;
    a.run();
  }, [sheet]);
  const nav = useMemo<NavValue>(() => {
    if (back === null) return {};
    if (back) return { back };
    const b = backOf(steps);
    return { ...(b.back ? { back: b.back } : {}), ...(b.locked && lock ? { lock } : {}) };
  }, [steps, back, lock]);
  return (
    <LinkContext.Provider value={Link}>
    <div className="app">
      <div className="pline" aria-hidden="true"><i style={{ width: `${allDone ? 100 : ((now + 0.5) / steps.length) * 100}%` }} /></div>
      <header className="top">
        {/* client-side, so a running install keeps going while the picker shows (wizard/registry.ts) */}
        <Link className="brand" href="/" aria-label="Glassworks Kiln, pick another panel">
          <Mark />
          <span className="wide">Glassworks <b>Kiln</b></span>
          <span className="narrow">Kiln</span>
        </Link>
        {chip && <span className={`chip ${chip.kind}`}><span className="dot" />{chip.text}</span>}
      </header>
      <div className="body">
        <nav className="rail" aria-label="Steps">
          <Rail steps={steps} />
          <RailFoot steps={steps} lock={lock} actions={actions} />
          <div className="rail-stack"><Stack level={level} error={error} /></div>
        </nav>
        {/* a phone: the step, its place, and the whole rail one tap away */}
        <div className="stepbar">
          <span className="sb-stack" aria-hidden="true"><Stack level={level} error={error} width={46} /></span>
          <span className="sb-t">
            <span className="sb-n">{allDone ? `All ${steps.length} steps done` : `Step ${at} of ${steps.length}`}</span>
            <b>{curName}</b>
          </span>
          <Dialog.Root open={sheet} onOpenChange={setSheet}>
            <Dialog.Trigger className="btn ghost sm sb-steps">Steps</Dialog.Trigger>
            <Dialog.Portal>
              <Dialog.Overlay className="overlay" />
              {/* focus stays put when it closes: a flow action's own dialog opens as it does, and focus going back to Steps would close it */}
              <Dialog.Content className="sheet steps-sheet" onCloseAutoFocus={(e) => e.preventDefault()}>
                <Dialog.Title asChild><h3>Steps</h3></Dialog.Title>
                <Dialog.Description className="visually-hidden">Where you are in the install, and the steps you can go back to</Dialog.Description>
                <Rail steps={steps} onGo={() => setSheet(false)} />
                <RailFoot steps={steps} lock={lock} actions={actions} run={(a) => { after.current = a; setSheet(false); }} />
                <Dialog.Close className="btn ghost">Close</Dialog.Close>
              </Dialog.Content>
            </Dialog.Portal>
          </Dialog.Root>
        </div>
        <main className="main"><NavContext.Provider value={nav}>{children}</NavContext.Provider></main>
      </div>
    </div>
    </LinkContext.Provider>
  );
}
