// Which frame a route is for, from the frames registry's catalog: only frames on Kiln's engine (ENGINE).
import { ENGINE, FRAMES, INSTALLERS } from '@glassworks/frames/catalog';
import { notFound } from 'next/navigation';

export type FrameParams = { params: Promise<{ frame: string }> };

/** Every frame's route segment, for generateStaticParams. */
export const segments = () => ENGINE.map((id) => ({ frame: INSTALLERS[id]!.route.slice(1) }));

/** The frame at a route segment: its id, route, page, and name as its frame.json gives it. */
export function frameAt(segment: string): { id: string; route: string; page?: 'wizard' | 'device'; name: string } {
  const id = ENGINE.find((f) => INSTALLERS[f]!.route === `/${segment}`);
  if (!id) notFound();
  const i = INSTALLERS[id]!;
  return { id, route: i.route, page: i.page, name: FRAMES.find((f) => f.id === id)?.name ?? id };
}
