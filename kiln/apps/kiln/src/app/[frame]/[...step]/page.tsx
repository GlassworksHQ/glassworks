// Every frame's step routes, from its data (frames.ts routesOf): its flows' steps, the standard flow's, undo's. Each page
// only names its route: what it shows comes from the session (wizard/routes.ts, the device page).
import type { Metadata } from 'next';
import { frameAt, routesOf, segments } from '../frames';

type StepParams = { params: Promise<{ frame: string; step: string[] }> };

export const dynamicParams = false;

/** Every frame's step routes, with the frame's segment (bottom-up: each pair is one page). */
export function generateStaticParams() {
  return segments().flatMap(({ frame }) => Object.keys(routesOf(frameAt(frame).id)).map((s) => ({ frame, step: s.split('/') })));
}

export async function generateMetadata({ params }: StepParams): Promise<Metadata> {
  const p = await params;
  const f = frameAt(p.frame);
  return { title: `${routesOf(f.id)[p.step.join('/')]} · ${f.name}` };
}

export default function Page() {
  return null;
}
