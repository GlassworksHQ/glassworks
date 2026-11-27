// A device frame's sessions in Kiln's registry (wizard/registry.ts), as the wizard's session is: they outlive a trip to
// the picker, the picker offers the way back while they work, and LeaveGuard asks before the page is left or reloaded
// while they write. Demos and the locked page aren't kept.
import { registry, type Activity } from '../wizard/registry';
import type { AppSession } from './appSession';
import type { ImageSession } from './imageSession';

export class DeviceActivity implements Activity {
  /** `imageMode`: the page shows the image over fastboot (true) or the app alone over adb. */
  constructor(readonly base: string, readonly name: string, readonly sessions: { app: AppSession; image: ImageSession }, public imageMode: boolean) {}

  /** Building, flashing or the first boot over fastboot; an in-place install's restore while it writes; installing or undoing over adb. */
  writing(): boolean {
    const i = this.sessions.image.state;
    return i.screen === 'working' || (i.screen === 'undo' && i.undo?.at === 'working') || this.sessions.app.state.screen === 'working';
  }

  /**
   * Back to it while it works, while the flashed device waits for its first boot (the adb screen), and while an
   * in-place install waits between its steps (its bundle, its second fastboot, its restore point): the device is
   * changed already, so the picker says so.
   */
  running(): string | undefined {
    const i = this.sessions.image.state;
    const f = i.screen;
    const apk = `?apk=${this.sessions.app.from}`;
    const between = f === 'receive' || f === 'restore-point' || (f === 'fastboot' && i.round === 2) || (f === 'undo' && i.undo?.at === 'working');
    if (f === 'working' || f === 'adb' || between) return `${this.base}${apk}`;
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
