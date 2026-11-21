// A UsbFrame's session in Kiln's registry (wizard/registry.ts), as the device flows' are: it outlives a trip to the
// picker, the picker offers the way back while it works, and LeaveGuard asks before the page is left or reloaded while
// it writes. Demos and the locked page aren't kept.
import { registry, type Activity } from '../wizard/registry';
import type { UsbSession } from './usbSession';

export class UsbActivity implements Activity {
  constructor(readonly base: string, readonly name: string, readonly session: UsbSession) {}

  writing(): boolean { return this.session.state.screen === 'working'; }

  running(): string | undefined {
    const s = this.session.state;
    return s.screen === 'working' || s.connecting ? `${this.base}?apk=${this.session.from}` : undefined;
  }

  subscribe(l: () => void): () => void { return this.session.subscribe(l); }
}

/**
 * The session kept at `base`, or a new one (`make`) kept from now on. A session that reached Done is finished: it lets
 * go and a new one starts, so coming back starts another install rather than showing that Done again. So is an idle
 * one whose APK came from elsewhere (`?apk=local`, then `?apk=release`); a running one is kept.
 */
export function keptUsb(base: string, name: string, make: () => UsbSession, from?: UsbSession['from']): UsbActivity {
  const kept = registry.activity(base);
  if (kept instanceof UsbActivity && kept.session.state.screen !== 'done' && (!from || kept.session.from === from || kept.running())) return kept;
  if (kept instanceof UsbActivity) kept.session.close();
  return registry.keep(new UsbActivity(base, name, make()));
}
