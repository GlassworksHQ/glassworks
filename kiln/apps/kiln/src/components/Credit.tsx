// The outside work a frame's install stands on, from its frame.json `credits` (frames/README.md#credits).
// A step that uses someone's work names them next to it, and the start page and the done screen name
// everything the install used. Links open the person's profile and their project.
import type { FrameCredit } from '@glassworks/frame-card';
import type { StepCredits } from '@glassworks/kiln-core';
import { FRAMES } from '../frames/catalog';
import s from './Credit.module.css';

/**
 * The credits a frame's steps use, each once, in step order; `only` keeps those steps. An id the frame
 * doesn't credit throws (test/frame-credits.test.ts fails first).
 */
export function stepCredits(frameId: string, steps: StepCredits, only?: readonly string[]): FrameCredit[] {
  const all = FRAMES.find((f) => f.id === frameId)?.credits ?? [];
  const ids = [...new Set(Object.entries(steps).filter(([k]) => !only || only.includes(k)).flatMap(([, v]) => v ?? []))];
  return ids.map((id) => {
    const c = all.find((x) => x.id === id);
    if (!c) throw new Error(`${frameId}: no credit "${id}" in its frame.json`);
    return c;
  });
}

const To = ({ l }: { l: { name: string; href: string } }) => <a className="link" href={l.href} target="_blank" rel="noopener">{l.name}</a>;
const Glyph = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="m12 4 8 4-8 4-8-4z" /><path d="m4 12 8 4 8-4" /><path d="m4 16 8 4 8-4" />
  </svg>
);

/**
 * One credit, next to the step that uses it: "Uses Magisk by topjohnwu." With `built` (what the step
 * hands over, e.g. `image`): "This image is built by git-nimrod as part of Glass Panes."
 */
export function Credit({ c, built }: { c: FrameCredit; built?: string }) {
  return (
    <p className={`small ${s.credit}`}>
      <Glyph />
      <span>{built
        ? <>This {built} is built by <To l={c.who} /> as part of <To l={c.project} />.</>
        : <>Uses <To l={c.project} /> by <To l={c.who} />.</>}</span>
    </p>
  );
}

/** Everything an install used, in one line: "Built on sunxi-tools by linux-sunxi and Magisk by topjohnwu." */
export function BuiltOn({ credits }: { credits: readonly FrameCredit[] }) {
  if (!credits.length) return null;
  return (
    <p className={`small ${s.credit}`}>
      <Glyph />
      <span>Built on {credits.map((c, i) => (
        <span key={c.id}>{i === 0 ? '' : i === credits.length - 1 ? ' and ' : ', '}<To l={c.project} /> by <To l={c.who} /></span>
      ))}.</span>
    </p>
  );
}
