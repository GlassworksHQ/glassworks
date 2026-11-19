// The forgiveness contract's harness (#621, docs/design/kiln/frames-as-data.md). A driver runs one frame's Kiln
// flow on a fake device, doing what a person does: one click per screen visit, the physical steps the frame
// declares (a recovery button, plugging in again), and nothing else. The cases in contract.test.ts inject the same
// failures into every flow and check what the S1 guarantees.
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

export const REPO = join(__dirname, '../../../../..');

/** One step of a flow in frames/<id>/design/installer.json. */
export interface StepData {
  id: string;
  transport: string;
  writes?: boolean;
  reboots?: boolean;
  downloads?: string[];
  inputs?: string[];
  /** taps the person makes on the device's own screen; a frame without touch has none */
  taps?: string[];
  enter?: { how: string; reachable?: boolean };
}
export interface InstallerData {
  route: string;
  touch: boolean;
  flows: Record<string, { steps: StepData[] }>;
}

/** Every frame's installer.json, by frame id. */
export function installers(): Map<string, InstallerData> {
  const out = new Map<string, InstallerData>();
  for (const id of readdirSync(join(REPO, 'frames'))) {
    const p = join(REPO, 'frames', id, 'design', 'installer.json');
    if (existsSync(p)) out.set(id, JSON.parse(readFileSync(p, 'utf8')) as InstallerData);
  }
  return out;
}

export function flowData(frame: string, flow: string): InstallerData['flows'][string] & { touch: boolean } {
  const d = installers().get(frame);
  const f = d?.flows[flow];
  if (!d || !f) throw new Error(`frames/${frame}/design/installer.json has no flow ${flow}`);
  return { ...f, touch: d.touch };
}

/** What a case does to the device and the page. */
export interface Knobs {
  /** At the flow's first adb connection after a restart (or its first, if none restarts), adb refuses this many times. */
  refuseAuth?: number;
  /** USB drops once in the middle of this step's first write. */
  dropUsbIn?: string;
  /** The page reloads in the middle of this step. */
  reloadIn?: string;
  /** The flow's first download sends nothing after it starts. */
  stall?: boolean;
  /** Nobody touches the device's screen beyond the taps its flow declares. */
  noTouch?: boolean;
}

/** A write that reached the device, and the step it belongs to. */
export interface Write { what: string; step: string }

/** What a look at the screen comes to. */
export type Look = 'acted' | 'wait' | { end: 'done' | 'error'; goal?: boolean };

/** One page load of a flow. */
export interface Page {
  /** What a person does now: at most one click per screen visit. */
  look(): Look;
  /** The step whose screen this is (installer.json ids). */
  step(): string;
  /** The error screen's way to try again; resolves once it has landed. */
  retry(): Promise<void>;
  /** The page knows, after a reload, that an earlier page load may have changed the device. */
  remembers(): boolean;
  /** Anything that changes when the flow moves. */
  snapshot(): string;
  /** The page goes away (a reload): stop what it polls. */
  close(): void;
}

/** One device and one browser, across page loads. */
export interface World {
  /** A page load: a new session over the same device and browser storage. */
  load(): Promise<Page>;
  writes: Write[];
  /** Set by the fake when the flow reached the reload point (the device call there never answers). */
  reloadNow: boolean;
  /** Which injections fired, for the harness checks. */
  fired: { refused: number; dropped: boolean; stalled: boolean; reloaded: boolean };
}

export interface Driver {
  /** `<frame id>` or `<frame id>/<flow>` */
  id: string;
  frame: string;
  flow: string;
  world(k: Knobs): World;
}

export const tick = (ms = 0) => new Promise<void>((r) => setTimeout(r, ms));
/** Sleep for the flows' own waits: real, but short, so a minute of polling takes milliseconds. */
export const fastSleep = (ms: number) => tick(Math.min(ms, 5));
/** Never answers: a device call the page was waiting on when it went away. */
export const never = <T>() => new Promise<T>(() => {});

/** How long nothing may change on screen before the run counts as stuck. */
export const IDLE_MS = 1500;
/** The stall watchdog's time in these runs (the real one is kiln-core's STALL_MS). */
export const STALL_MS = 100;

export type Stop = { end: 'done' | 'error' | 'stuck' | 'reload'; goal?: boolean; at: string };

/** Looks at the page until Done, an error screen, a reload, or nothing moves for IDLE_MS. */
export async function drive(world: World, page: Page): Promise<Stop> {
  let last = page.snapshot();
  let quiet = Date.now();
  for (;;) {
    if (world.reloadNow) return { end: 'reload', at: page.step() };
    const l = page.look();
    if (typeof l === 'object') return { end: l.end, goal: l.goal, at: page.step() };
    await tick(5);
    const now = page.snapshot();
    if (now !== last || l === 'acted') { last = now; quiet = Date.now(); continue; }
    if (Date.now() - quiet > IDLE_MS) return { end: 'stuck', at: page.step() };
  }
}

/**
 * Clicks for a page: `act(key, fn)` runs fn the first time a screen visit with that key is seen, and
 * waits after. `reset` starts new visits (after Try again).
 */
export class Clicks {
  private done = new Set<string>();
  act(key: string, fn: () => unknown): Look {
    if (this.done.has(key)) return 'wait';
    this.done.add(key);
    void Promise.resolve().then(fn).catch(() => {});
    return 'acted';
  }
  has(key: string) { return this.done.has(key); }
  reset() { this.done.clear(); }
}

/** A Response whose body sends `bytes` in one chunk, or (stalled) nothing and never ends. */
export function body(bytes: Uint8Array, stalled = false): Response {
  return new Response(new ReadableStream<Uint8Array>({
    pull(c) {
      if (stalled) return never();
      c.enqueue(bytes);
      c.close();
    },
  }), { headers: { 'content-length': String(bytes.length) } });
}

export const REFUSED = 'No authenticator can handle the request';
export const UNPLUGGED = 'transferOut: the device was disconnected';
