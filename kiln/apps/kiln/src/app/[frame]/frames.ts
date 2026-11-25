// Which frame a route is for, from the frames registry's catalog: only frames on Kiln's engine (ENGINE). And each
// frame's step routes, from its data: every frame has the same kind of page (issue 621).
import { ENGINE, FRAMES, INSTALLERS } from '@glassworks/frames/catalog';
import { notFound } from 'next/navigation';

export type FrameParams = { params: Promise<{ frame: string }> };

/** Every frame's route segment, for generateStaticParams. */
export const segments = () => ENGINE.map((id) => ({ frame: INSTALLERS[id]!.route.slice(1) }));

/** The frame at a route segment: its id, route, and name as its frame.json gives it. */
export function frameAt(segment: string): { id: string; route: string; name: string } {
  const id = ENGINE.find((f) => INSTALLERS[f]!.route === `/${segment}`);
  if (!id) notFound();
  const i = INSTALLERS[id]!;
  return { id, route: i.route, name: FRAMES.find((f) => f.id === id)?.name ?? id };
}

/** The standard flow's routes (wizard/routes.ts), every frame's after its install, and their titles' first part. */
export const STANDARD_ROUTES: Readonly<Record<string, string>> = {
  'set-up': 'Set up', 'set-up/wifi': 'Wi-Fi', 'set-up/home-assistant': 'Home Assistant', 'set-up/panes': 'Panes', 'set-up/radio': 'Radio',
  done: 'Done', resume: 'Finish setting up',
};

/**
 * Undo's routes, for a frame whose way back restores its backup (installer.json `wayBack.how`: restore-backup), or a
 * flow's way back writes its restore point over the blocks it changed (a flow's `wayBack.how`: restore-blocks).
 */
export const UNDO_ROUTES: Readonly<Record<string, string>> = {
  undo: 'Undo', 'undo/connect': 'Undo: connect', 'undo/restore': 'Undo: restore', 'undo/done': 'Undo: done',
};

/**
 * A frame's step routes under its own and their titles: each flow step's `route` (installer.json), the standard flow's,
 * and undo's when its way back is a restore. A frame can't leave the standard flow's out: its routes are every frame's.
 */
export function routesOf(id: string): Record<string, string> {
  const i = INSTALLERS[id]!;
  const own: Record<string, string> = {};
  for (const flow of Object.values(i.flows ?? {})) {
    for (const s of flow.steps) if (s.route) own[s.route] = s.title ?? s.id;
  }
  // an install from the device's own browser, when the frame declares one (installer.json `onDevice`)
  if (i.onDevice) own[i.onDevice.route] = i.onDevice.title;
  const restores = i.wayBack?.how === 'restore-backup' || Object.values(i.flows ?? {}).some((f) => f.wayBack?.how === 'restore-blocks');
  return { ...own, ...STANDARD_ROUTES, ...(restores ? UNDO_ROUTES : {}) };
}
