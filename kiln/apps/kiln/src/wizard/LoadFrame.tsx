'use client';
// The frame for a route, from the frames registry, then its wizard. Each frame's code loads once, when its route opens.
import type { DemoFrame } from '@glassworks/kiln-core';
import { KILN } from '@glassworks/frames/kiln';
import { useEffect, useState } from 'react';
import { FrameWizard } from './FrameWizard';

const loads = new Map<string, Promise<DemoFrame>>();

/** One load per frame; a load that failed (a chunk the network dropped) leaves, so the next visit tries again. */
function load(id: string): Promise<DemoFrame> {
  let p = loads.get(id);
  if (!p) {
    p = KILN[id]!().catch((e: unknown) => { loads.delete(id); throw e; });
    loads.set(id, p);
  }
  return p;
}

export function LoadFrame({ id, base }: { id: string; base: string }) {
  const [d, setD] = useState<DemoFrame>();
  const [failed, setFailed] = useState<unknown>();
  useEffect(() => {
    let here = true;
    load(id).then((x) => { if (here) setD(x); }, (e: unknown) => { if (here) setFailed(e ?? new Error(`couldn't load ${id}`)); });
    return () => { here = false; };
  }, [id]);
  // a chunk that didn't load goes to Next's error page, as a page's own code would
  if (failed) throw failed;
  return d ? <FrameWizard frame={d.frame} demo={d} base={base} /> : null;
}
