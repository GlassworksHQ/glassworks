// The entry of the setup page the panel serves at /setup (#34). Built by lan/vite.config.ts into
// core's assets; see kiln/apps/kiln/README.md (The panel's setup page).
import { LAN_CHANNEL_PATH, openWebSocketStream } from '@glassworks/setup-channel';
import { createRoot } from 'react-dom/client';
import { MarkDefs } from '../components/Mark';
import { LanPage } from './LanPage';
import { LanSession } from './lanSession';

const lan = new LanSession({
  fetch: async (path, init) => {
    const r = await fetch(path, {
      method: init?.method ?? 'GET',
      credentials: 'same-origin',
      headers: init?.body ? { 'content-type': 'application/x-www-form-urlencoded' } : undefined,
      body: init?.body,
    });
    return { status: r.status, json: () => r.json() };
  },
  openStream: () => openWebSocketStream(`ws://${location.host}${LAN_CHANNEL_PATH}`),
});

createRoot(document.getElementById('root')!).render(<><MarkDefs /><LanPage lan={lan} /></>);
void lan.start();
