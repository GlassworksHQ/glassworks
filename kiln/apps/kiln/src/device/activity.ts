// A device frame's sessions in Kiln's registry (wizard/registry.ts), as the wizard's session is: they outlive a trip to
// the picker, the picker offers the way back while they work, and LeaveGuard asks before the page is left or reloaded
// while they write. Demos and the locked page aren't kept.
import { registry, type Activity } from '../wizard/registry';
import type { AppSession } from './appSession';
import type { ImageSession } from './imageSession';

export class DeviceActivity implements Activity {
  /** `imageMode`: the page shows the image over fastboot (true) or the app alone over adb. */
  constructor(readonly base: string, readonly name: string, readonly sessions: { app: AppSession; image: ImageSession }, public imageMode: boolean) {}

  /** Building, flashing or the first boot over fastboot; installing or undoing over adb. */
  writing(): boolean {
    return this.sessions.image.state.screen === 'working' || this.sessions.app.state.screen === 'working';
  }

  /** Back to it while it works, or while the flashed device waits for its first boot (the adb screen). */
  running(): string | undefined {
    const f = this.sessions.image.state.screen;
    const apk = `?apk=${this.sessions.app.from}`;
    if (f === 'working' || f === 'adb') return `${this.base}${apk}`;
    if (this.sessions.app.state.screen === 'working') return `${this.base}${apk}&mode=app`;
    return undefined;
  }

  subscribe(l: () => void): () => void {
    const offs = [this.sessions.image.subscribe(l), this.sessions.app.subscribe(l)];
    return () => { for (const off of offs) off(); };
  }
}

/** The kept sessions of the frame at `base`, or new ones (`make`) kept from now on. */
export function keptDevice(base: string, name: string, make: () => { app: AppSession; image: ImageSession }, imageMode: boolean): DeviceActivity {
  const kept = registry.activity(base);
  return kept instanceof DeviceActivity ? kept : registry.keep(new DeviceActivity(base, name, make(), imageMode));
}
