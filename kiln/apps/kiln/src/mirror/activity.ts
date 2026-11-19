// The Mirror's sessions in Kiln's registry (wizard/registry.ts), as the S1's session is: they outlive
// a trip to the picker, the picker offers the way back while they work, and LeaveGuard asks before
// the page is left or reloaded while they write. Demos and the locked page aren't kept.
import { registry, type Activity } from '../wizard/registry';
import type { FlashSession } from './flash';
import type { MirrorSession } from './session';

export const MIRROR_BASE = '/lululemon-mirror';

export class MirrorActivity implements Activity {
  readonly base = MIRROR_BASE;
  readonly name = 'lululemon Studio Mirror';

  /** `flashMode`: the page shows Glassworks over fastboot (true) or the app alone over adb. */
  constructor(readonly sessions: { mirror: MirrorSession; flash: FlashSession }, public flashMode: boolean) {}

  /** Building, flashing or the first boot over fastboot; installing or undoing over adb. */
  writing(): boolean {
    return this.sessions.flash.state.screen === 'working' || this.sessions.mirror.state.screen === 'working';
  }

  /** Back to it while it works, or while the flashed Mirror waits for its first boot (the adb screen). */
  running(): string | undefined {
    const f = this.sessions.flash.state.screen;
    const apk = `?apk=${this.sessions.mirror.from}`;
    if (f === 'working' || f === 'adb') return `${MIRROR_BASE}${apk}`;
    if (this.sessions.mirror.state.screen === 'working') return `${MIRROR_BASE}${apk}&mode=app`;
    return undefined;
  }

  subscribe(l: () => void): () => void {
    const offs = [this.sessions.flash.subscribe(l), this.sessions.mirror.subscribe(l)];
    return () => { for (const off of offs) off(); };
  }
}

/** The kept Mirror sessions, or new ones (`make`) kept from now on. */
export function keptMirror(make: () => { mirror: MirrorSession; flash: FlashSession }, flashMode: boolean): MirrorActivity {
  const kept = registry.activity(MIRROR_BASE);
  return kept instanceof MirrorActivity ? kept : registry.keep(new MirrorActivity(make(), flashMode));
}
