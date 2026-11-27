'use client';
// Kiln's component kit (docs/design/kiln/system.md): the few shapes every screen is built from, so a person learns
// them once. Progress says what is done, what runs now and what comes next, with how long each takes. Wait makes a
// long wait feel alive: how long it usually takes, how long it has taken, and that it's normal. Hands says what the
// person does (usually nothing) and must not do. Instructions numbers a physical procedure and lets the person tick it
// off. BrowserAsk warns before the browser's own device list opens. Note is the one callout. Problem is every error.
// Nothing here names a frame: the words come in from the frame's data.
import * as Collapsible from '@radix-ui/react-collapsible';
import { useEffect, useRef, useState, type ReactNode } from 'react';

/* ------------------------------------------------------------------ glyphs */

const Svg = ({ children, size = 16, sw = 2 }: { children: ReactNode; size?: number; sw?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={sw} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{children}</svg>
);
export const CheckGlyph = ({ size = 14 }: { size?: number }) => <Svg size={size} sw={2.6}><path d="M5 12.5l4.2 4.2L19 7" /></Svg>;
export const PlugGlyph = () => <Svg size={18}><path d="M9 2v5M15 2v5M6 7h12v4a6 6 0 0 1-12 0zM12 17v5" /></Svg>;
export const ShieldGlyph = () => <Svg size={18}><path d="M12 3l8 3v6c0 4.5-3.4 8.2-8 9-4.6-.8-8-4.5-8-9V6z" /><path d="M8.5 12l2.5 2.5 4.5-5" /></Svg>;
export const InfoGlyph = () => <Svg size={18}><circle cx="12" cy="12" r="9" /><path d="M12 11v5M12 7.5v.01" /></Svg>;
export const AlertGlyph = () => <Svg size={18}><path d="M12 3l9.5 17h-19z" /><path d="M12 10v4M12 17v.01" /></Svg>;
export const ClockGlyph = () => <Svg size={18}><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></Svg>;
export const HandGlyph = () => <Svg size={18}><path d="M8 13V5.5a1.5 1.5 0 0 1 3 0V11M11 10.5V4a1.5 1.5 0 0 1 3 0v6.5M14 10.5V5.5a1.5 1.5 0 0 1 3 0V14c0 4-2.5 7-6 7-2.6 0-4-1.2-5.4-3.4L3.8 14a1.5 1.5 0 0 1 2.5-1.6L8 15" /></Svg>;

/* ------------------------------------------------------------------ time */

/**
 * Seconds a typical time says at most ("4–5 minutes" → 300, "about a minute" → 60, "30 seconds" → 30), so a wait can
 * tell when it runs long. Undefined when the words carry no number Kiln can read.
 */
export function typicalSeconds(t?: string): number | undefined {
  if (!t) return undefined;
  const unit = /\bsec/i.test(t) ? 1 : /\bmin/i.test(t) ? 60 : /\bhour/i.test(t) ? 3600 : 0;
  if (!unit) return undefined;
  const nums = [...t.matchAll(/\d+(?:\.\d+)?/g)].map((m) => Number(m[0]));
  const n = nums.length ? Math.max(...nums) : /\ba (?:minute|second|few)\b/i.test(t) ? (/\bfew\b/i.test(t) ? 3 : 1) : undefined;
  return n === undefined ? undefined : Math.round(n * unit);
}

/**
 * How long a whole install usually takes, as one quiet line with a clock: "Usually about 10 minutes". The time is the
 * frame's words; a frame that gives none shows nothing here.
 */
export function Usually({ time, id }: { time?: string; id?: string }) {
  if (!time) return null;
  return <span className="usual" id={id}><ClockGlyph />{`Usually ${time.charAt(0).toLowerCase()}${time.slice(1)}`}</span>;
}

/** m:ss, as a person reads a timer. */
export const clock = (sec: number) => `${Math.floor(sec / 60)}:${String(Math.floor(sec % 60)).padStart(2, '0')}`;

/** Seconds since this component first showed, ticking once a second. `now` and `every` are for the tests. */
export function useElapsed(o: { now?: () => number; every?: number } = {}): number {
  const now = o.now ?? Date.now;
  const start = useRef<number | undefined>(undefined);
  start.current ??= now();
  const [, tick] = useState(0);
  useEffect(() => {
    const t = setInterval(() => tick((x) => x + 1), o.every ?? 1000);
    return () => clearInterval(t);
  }, [o.every]);
  return Math.max(0, Math.floor((now() - start.current) / 1000));
}

/* ------------------------------------------------------------------ progress */

/** Where a step is: done, running now, waiting on the person (`turn`), still to come, or stopped. */
export type RowState = 'done' | 'now' | 'turn' | 'next' | 'bad';

export interface ProgressRow {
  id: string;
  label: string;
  state: RowState;
  /** How long it usually takes, said on a step still to come and on the one running ("About 5 minutes"). */
  time?: string;
  /** How far the running step is, 0..1, when it reports it. */
  x?: number;
  /** A line under the running step: what's happening, or that a pause is normal. */
  note?: ReactNode;
}

/** Rows from labels, with the one at `at` running (or waiting on the person, or stopped) and those before it done. */
export function rowsAt(rows: readonly Omit<ProgressRow, 'state'>[], at: number, cur: Exclude<RowState, 'done' | 'next'> = 'now'): ProgressRow[] {
  return rows.map((r, i) => ({ ...r, state: i < at ? 'done' : i === at ? cur : 'next' }));
}

/** An index that only moves forward while one screen shows: a step reported late never pulls the list back. */
export function useForward(at: number): number {
  const max = useRef(at);
  if (at > max.current) max.current = at;
  return max.current;
}

const META: Record<RowState, string> = { done: 'Done', now: 'Now', turn: 'Your turn', next: '', bad: 'Stopped' };

/**
 * The one way Kiln shows work: "Step 2 of 5", a bar, then every step with its mark and its word (Done, Now, Your turn,
 * or how long it takes). The running step carries its note. A screen reader hears the running step when it changes.
 */
export function Progress({ rows, label, aside, live = true }: {
  rows: readonly ProgressRow[];
  label: string;
  /** Said at the right of "Step n of m": the whole's estimate ("68% · under a minute"). */
  aside?: string;
  /** `false`: the screen announces its steps in its own words. */
  live?: boolean;
}) {
  const at = rows.findIndex((r) => r.state !== 'done');
  const cur = at >= 0 ? rows[at] : undefined;
  const done = at < 0 ? rows.length : at;
  const whole = rows.length ? Math.min(100, Math.round(((done + (cur?.state === 'now' ? cur.x ?? 0 : 0)) / rows.length) * 100)) : 100;
  const count = cur ? `Step ${at + 1} of ${rows.length}` : 'All done';
  return (
    <div className="progress">
      <div className="progress-head">
        <span className="progress-count">{count}</span>
        {aside && <span className="progress-time">{aside}</span>}
      </div>
      <div className={`meter${cur?.state === 'bad' ? ' bad' : ''}`} role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={whole}
        aria-valuetext={cur ? `${count}: ${cur.label}` : count}>
        <i style={{ width: `${Math.max(2, whole)}%` }} />
      </div>
      <ol className="plist" aria-label={label}>
        {rows.map((r, i) => {
          const pct = r.state === 'now' && r.x !== undefined && r.x > 0 && r.x < 1 ? `${Math.floor(r.x * 100)}%` : undefined;
          return (
            <li key={`${r.id}-${i}`} className={`prow ${r.state}`} aria-current={r.state === 'now' || r.state === 'turn' || r.state === 'bad' ? 'step' : undefined}>
              <span className="pmark" aria-hidden="true">{r.state === 'done' ? <CheckGlyph size={12} /> : r.state === 'bad' ? '!' : r.state === 'now' ? <span className="spin" /> : r.state === 'turn' ? <span className="dot" /> : i + 1}</span>
              <span className="pbody">
                <span className="plabel">{r.label}</span>
                {(r.state === 'now' || r.state === 'turn' || r.state === 'bad') && r.note && <span className="pnote">{r.note}</span>}
              </span>
              <span className="pmeta">{pct ?? (r.state === 'next' ? r.time ?? '' : META[r.state])}</span>
            </li>
          );
        })}
      </ol>
      {live && <p className="visually-hidden" role="status">{cur ? `${count}: ${cur.label}. ${META[cur.state]}.` : count}</p>}
    </div>
  );
}

/* ------------------------------------------------------------------ the person's part */

/**
 * What the person does while Kiln works: usually nothing, and what not to do. Shown on every screen that writes, the
 * same words in the same place.
 */
export function Hands({ the, children }: { the: string; children?: ReactNode }) {
  return (
    <div className="hands" role="note">
      <span className="ico"><PlugGlyph /></span>
      <span className="grow">
        <b>Nothing to do but wait</b>
        <span>{children ?? `Keep ${the} plugged in and this tab open until it’s done.`}</span>
      </span>
    </div>
  );
}

/* ------------------------------------------------------------------ waits */

/**
 * A long wait, said up front: what's happening, how long it usually takes, how long it has taken so far, and that it's
 * normal. Past the usual time it says so calmly, never as an error. Life comes from the ring and the clock; with reduced
 * motion the ring stands still and the clock still counts.
 */
export function Wait({ what, typical, note, longer, now }: {
  what: string;
  /** "Usually 4–5 minutes": how long it takes, in the frame's words. */
  typical?: string;
  /** Why it's normal, or what the person may see meanwhile. */
  note?: ReactNode;
  /** What to say once it runs past the usual time. */
  longer?: string;
  now?: () => number;
}) {
  const sec = useElapsed(now ? { now } : {});
  const max = typicalSeconds(typical);
  const over = max !== undefined && sec > max * 1.25;
  return (
    <div className={`wait${over ? ' long' : ''}`}>
      <span className="wait-ring" aria-hidden="true"><i /></span>
      <span className="grow">
        <b role="status">{what}</b>
        <span className="wait-time">
          {typical && <span>{`Usually ${typical.charAt(0).toLowerCase()}${typical.slice(1)}`}</span>}
          <span className="wait-clock" aria-hidden="true">{`${clock(sec)} so far`}</span>
        </span>
        <span className="wait-note">{over ? longer ?? 'This is taking longer than usual. That can still be normal, and Kiln keeps checking.' : note}</span>
      </span>
    </div>
  );
}

/* ------------------------------------------------------------------ notes */

export type Tone = 'info' | 'ok' | 'turn' | 'bad';
const TONE_ICON: Record<Tone, () => ReactNode> = { info: InfoGlyph, ok: ShieldGlyph, turn: HandGlyph, bad: AlertGlyph };

/** The one callout: an icon, a short bold line, a sentence, and at most one action. Tone is status only. */
export function Note({ tone = 'info', title, children, action, icon, role }: {
  tone?: Tone;
  title?: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
  icon?: ReactNode;
  role?: 'note' | 'status' | 'alert';
}) {
  const Icon = TONE_ICON[tone];
  return (
    <div className={`note ${tone}`} role={role ?? (tone === 'bad' ? 'alert' : 'note')}>
      <span className="ico">{icon ?? <Icon />}</span>
      <div className="grow">
        {title && <b>{title}</b>}
        {children && <div>{children}</div>}
      </div>
      {action && <span className="note-act">{action}</span>}
    </div>
  );
}

/* ------------------------------------------------------------------ instructions */

/**
 * A physical procedure, one numbered step per line. Each step can be ticked off while the person's hands are busy:
 * a mark for them only, nothing Kiln acts on.
 */
export function Instructions({ steps, label = 'Steps' }: { steps: readonly ReactNode[]; label?: string }) {
  const [ticked, setTicked] = useState<readonly boolean[]>(() => steps.map(() => false));
  const next = ticked.findIndex((x) => !x);
  return (
    <ol className="isteps" aria-label={label}>
      {steps.map((s, i) => (
        <li key={i} className={ticked[i] ? 'ticked' : i === next ? 'up' : undefined}>
          <button type="button" className="inum" aria-pressed={!!ticked[i]} aria-label={`Step ${i + 1}${ticked[i] ? ', done' : ''}`}
            onClick={() => setTicked((t) => t.map((x, j) => (j === i ? !x : x)))}>
            {ticked[i] ? <CheckGlyph size={13} /> : i + 1}
          </button>
          <span className="itext">{s}</span>
        </li>
      ))}
    </ol>
  );
}

/**
 * Before the browser opens its own device list: what it looks like, and the one line to pick. `listed`: the line as the
 * list shows it when that isn't the device's own name ("Android", a USB id); `short`: the device's name otherwise.
 */
export function BrowserAsk({ listed, short, the, children }: { listed?: string; short: string; the: string; children?: ReactNode }) {
  return (
    <div className="ask" role="note">
      <div className="ask-mock" aria-hidden="true">
        <span className="ask-top">Kiln wants to connect</span>
        <span className="ask-row"><span className="ask-dot" />{listed ?? short}<span className="ask-pick">Pick this</span></span>
        <span className="ask-btns"><i>Cancel</i><i className="on">Connect</i></span>
      </div>
      <span className="grow">
        <b>Your browser asks next</b>
        <span>{children ?? (listed
          ? <>It shows a list of devices. Pick <code>{listed}</code>, which is {the}, then click Connect.</>
          : <>It shows a list of devices. Pick {the}, then click Connect.</>)}</span>
      </span>
    </div>
  );
}

/* ------------------------------------------------------------------ errors */

/** Raw details and the log, closed until asked for: what happened in Kiln's words is on the screen, this is for help. */
export function Details({ children, label = 'Details' }: { children: ReactNode; label?: string }) {
  const [open, setOpen] = useState(false);
  return (
    <Collapsible.Root open={open} onOpenChange={setOpen} className="details">
      <Collapsible.Trigger className="link details-t">{open ? `Hide ${label.toLowerCase()}` : label}</Collapsible.Trigger>
      <Collapsible.Content className="details-c">{children}</Collapsible.Content>
    </Collapsible.Root>
  );
}

/**
 * Every error, laid out the same way: what happened as the heading, whether the device is safe (always, first), what to
 * do, the actions, then the details on request. `safe`: the device's state in one line; `fix`: the most likely fix.
 */
export function Problem({ title, safe, safeTone = 'ok', fix, children, details }: {
  title: string;
  safe?: ReactNode;
  safeTone?: Tone;
  fix?: ReactNode;
  /** The foot. */
  children?: ReactNode;
  details?: ReactNode;
}) {
  return (
    <section className="card err" aria-labelledby="h">
      <span className="err-mark" aria-hidden="true">!</span>
      <h2 id="h">{title}</h2>
      {safe && <Note tone={safeTone} role="status">{safe}</Note>}
      {fix && <div className="fix"><b>What to do</b><div>{fix}</div></div>}
      {children}
      {details && <Details>{details}</Details>}
    </section>
  );
}
