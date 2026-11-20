// The wizard's step routes (wizard/routes.ts), for every frame whose page is the wizard. Each page only names its
// route: what it shows comes from the session. Other frames have none, so these paths are not found there.
import type { Metadata } from 'next';
import { frameAt, segments } from '../frames';

/** Each step route and its title's first part. */
const STEPS: Record<string, string> = {
  install: 'Install', resume: 'Finish setting up', done: 'Done',
  'set-up': 'Set up', 'set-up/home-assistant': 'Home Assistant', 'set-up/panes': 'Panes', 'set-up/radio': 'Radio', 'set-up/wifi': 'Wi-Fi',
  undo: 'Undo', 'undo/connect': 'Undo: connect', 'undo/done': 'Undo: done', 'undo/restore': 'Undo: restore',
};

type StepParams = { params: Promise<{ frame: string; step: string[] }> };

export const dynamicParams = false;

/** Every wizard frame's step routes, with the frame's segment (bottom-up: each pair is one page). */
export function generateStaticParams() {
  return segments().filter(({ frame }) => frameAt(frame).page === 'wizard').flatMap(({ frame }) => Object.keys(STEPS).map((s) => ({ frame, step: s.split('/') })));
}

export async function generateMetadata({ params }: StepParams): Promise<Metadata> {
  const p = await params;
  return { title: `${STEPS[p.step.join('/')]} · ${frameAt(p.frame).name}` };
}

export default function Page() {
  return null;
}
