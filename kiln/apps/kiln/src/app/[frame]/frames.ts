// Which frame a route is for, from the frames registry's catalog: only frames the shared wizard runs (ENGINE).
import { ENGINE, INSTALLERS } from '@glassworks/frames/catalog';
import type { Metadata } from 'next';
import { notFound } from 'next/navigation';

export type FrameParams = { params: Promise<{ frame: string }> };

/** Every frame's route segment, for generateStaticParams. */
export const segments = () => ENGINE.map((id) => ({ frame: INSTALLERS[id]!.route.slice(1) }));

export function frameAt(segment: string): { id: string; route: string; name: string } {
  const id = ENGINE.find((f) => INSTALLERS[f]!.route === `/${segment}`);
  if (!id) notFound();
  const i = INSTALLERS[id]!;
  return { id, route: i.route, name: i.words?.name ?? id };
}

/** A step route's title: "<label> · <frame>". */
export const titled = (label: string) => async ({ params }: FrameParams): Promise<Metadata> => ({ title: `${label} · ${frameAt((await params).frame).name}` });
