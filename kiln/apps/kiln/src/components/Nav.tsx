'use client';
// How a person moves through Kiln (docs/design/kiln/navigation.md): the rail is the map, Back is the rail's step before
// this one when the flow lets you return to it, and every screen's foot has the same order: Back on the left, the
// screen's other actions as ghost buttons, the one ember button on the right. Leaving the path (Start over) is a
// dialog that says what starts again and what stays.
import * as Dialog from '@radix-ui/react-dialog';
import { createContext, useContext, type ComponentType, type ReactNode } from 'react';

/** Where a step or Back goes: a route (the picker, a set-up step) or something the session does. */
export type Go = { href: string } | { run: () => void };

/** What the shell gives every screen's foot: the way back, or why there is none. */
export interface NavValue {
  back?: Go;
  /** No way back, and why (something is written to the device). */
  lock?: string;
}

export const NavContext = createContext<NavValue>({});

type LinkLike = ComponentType<{ href: string; className?: string; onClick?: () => void; 'aria-label'?: string; children: ReactNode }>;
const Plain: LinkLike = ({ children, ...p }) => <a {...p}>{children}</a>;
/**
 * How a route is followed: Next's Link in Kiln (the Shell gives it, so a running install keeps its session), a plain
 * link on the panel's own setup page, which shares the set-up screens and has no router.
 */
export const LinkContext = createContext<LinkLike>(Plain);

const Chevron = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M15 18l-6-6 6-6" /></svg>
);

export const LockGlyph = () => (
  <svg className="lock-glyph" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></svg>
);

/** A Go as a control: a link for a route, a button for a session's action. */
export function GoControl({ go, className, children, onDone, ...rest }: { go: Go; className?: string; children: ReactNode; onDone?: () => void; 'aria-label'?: string; disabled?: boolean }) {
  const Link = useContext(LinkContext);
  if ('href' in go) return <Link className={className} href={go.href} onClick={onDone} aria-label={rest['aria-label']}>{children}</Link>;
  return <button type="button" className={className} disabled={rest.disabled} aria-label={rest['aria-label']} onClick={() => { go.run(); onDone?.(); }}>{children}</button>;
}

/** Back, as a button: always in the same place, the left of the foot. */
export function BackButton({ go, label = 'Back', disabled }: { go: Go; label?: string; disabled?: boolean }) {
  return <GoControl go={go} className="btn back" disabled={disabled}><Chevron />{label}</GoControl>;
}

/**
 * Every screen's foot. `back`: this screen's own way back (inside its step), in place of the rail's; `null`: none here,
 * even if the rail has one. `children`: the other actions, the ember one last.
 */
export function Foot({ back, backLabel, children, busy }: { back?: Go | null; backLabel?: string; children?: ReactNode; busy?: boolean }) {
  const nav = useContext(NavContext);
  const to = back === null ? undefined : back ?? nav.back;
  return (
    <div className="foot">
      {to ? <BackButton go={to} label={backLabel} disabled={busy} />
        : back === undefined && nav.lock ? <span className="foot-lock"><LockGlyph />{nav.lock}</span>
        : null}
      <div className="foot-main">{children}</div>
    </div>
  );
}

/**
 * Start over, as a dialog: what starts again and what stays, then the confirm. `open` follows the session (its
 * `startingOver`), so a reload or a second tab never finds it half open.
 */
export function StartOverDialog({ open, onOpenChange, onConfirm, resets, keeps, the }: {
  open: boolean;
  onOpenChange(open: boolean): void;
  onConfirm(): void;
  /** What starts again (the frame's words first). */
  resets: string[];
  keeps: string[];
  the: string;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="overlay" />
        <Dialog.Content className="sheet dialog">
          <Dialog.Title asChild><h3>Start over?</h3></Dialog.Title>
          <Dialog.Description>{`The install begins again from its first screen. Nothing more is written to ${the} until you go on.`}</Dialog.Description>
          <div className="dlg-cols">
            <div>
              <span className="dlg-h">Starts again</span>
              <ul className="dlg-list dlg-resets">{resets.map((x) => <li key={x}>{x}</li>)}</ul>
            </div>
            <div>
              <span className="dlg-h">Stays</span>
              <ul className="dlg-list dlg-stays">{keeps.map((x) => <li key={x}>{x}</li>)}</ul>
            </div>
          </div>
          <div className="dlg-foot">
            <Dialog.Close className="btn ghost">Keep going</Dialog.Close>
            <button type="button" className="btn strong" onClick={onConfirm}>Start over</button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

const Restart = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 12a9 9 0 1 0 3-6.7" /><path d="M3 4v5h5" /></svg>
);

const Undo = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 14L4 9l5-5" /><path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11" /></svg>
);

/** A flow action under the rail (Start over, Restore the original system): a quiet button with its icon. */
export function FlowAction({ onClick, children, icon = 'restart' }: { onClick(): void; children: ReactNode; icon?: 'restart' | 'restore' }) {
  return <button type="button" className="btn ghost flow-action" onClick={onClick}>{icon === 'restart' ? <Restart /> : <Undo />}{children}</button>;
}
