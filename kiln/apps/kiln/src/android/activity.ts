// /android's session in Kiln's registry (wizard/registry.ts), as the Mirror's is: it outlives a trip
// to the picker, the picker offers the way back while it works, and LeaveGuard asks before the page is
// left or reloaded while it writes. Demos and the locked page aren't kept.
import { registry, type Activity } from '../wizard/registry';
import type { UsbSession } from '../device/usbSession';

export const ANDROID_BASE = '/android';

export class AndroidActivity implements Activity {
  readonly base = ANDROID_BASE;
  readonly name = 'Android device';

  constructor(readonly session: UsbSession) {}

  writing(): boolean { return this.session.state.screen === 'working'; }

  running(): string | undefined {
    const s = this.session.state;
    return s.screen === 'working' || s.connecting ? `${ANDROID_BASE}?apk=${this.session.from}` : undefined;
  }

  subscribe(l: () => void): () => void { return this.session.subscribe(l); }
}

/**
 * The kept session, or a new one (`make`) kept from now on. A session that reached Done is finished: it lets go
 * and a new one starts, so coming back to /android starts another install rather than showing that Done again.
 * So is an idle one whose APK came from elsewhere (`?apk=local`, then `?apk=release`); a running one is kept.
 */
export function keptAndroid(make: () => UsbSession, from?: UsbSession['from']): AndroidActivity {
  const kept = registry.activity(ANDROID_BASE);
  if (kept instanceof AndroidActivity && kept.session.state.screen !== 'done' && (!from || kept.session.from === from || kept.running())) return kept;
  if (kept instanceof AndroidActivity) kept.session.close();
  return registry.keep(new AndroidActivity(make()));
}
