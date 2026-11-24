#!/usr/bin/env node
// Reads Lovelace dashboards from Home Assistant over its websocket API, for the Vitro island census
// (docs/research/vitro-island-census.md).
//
// Touches: read-only. It sends only `lovelace/dashboards/list` and `lovelace/config` and never saves or calls a
// service. The token comes from the environment, and nothing prints or stores it.
//
//   HA_URL=http://homeassistant.local:8123 HA_TOKEN=<long-lived token> node tools/vitro-census/ha-dashboards.mjs list
//   HA_URL=... HA_TOKEN=... node tools/vitro-census/ha-dashboards.mjs get <url_path>   # "lovelace" for the default one
//
// Needs Node 22+ (built-in WebSocket). With an http:// HA_URL the token crosses the network unencrypted, as any
// plain-HTTP Home Assistant client's does; use https:// (wss://) when Home Assistant serves HTTPS.
const [, , cmd, urlPath] = process.argv;
const { HA_URL, HA_TOKEN } = process.env;
if (!HA_URL || !HA_TOKEN || !['list', 'get'].includes(cmd) || (cmd === 'get' && !urlPath)) {
  console.error('usage: HA_URL=... HA_TOKEN=... ha-dashboards.mjs list | get <url_path>');
  process.exit(2);
}
const msg = cmd === 'list'
  ? { type: 'lovelace/dashboards/list' }
  : { type: 'lovelace/config', url_path: urlPath === 'lovelace' ? null : urlPath, force: true };
const wsUrl = HA_URL.replace(/^http/i, 'ws').replace(/\/$/, '') + '/api/websocket';
if (!/^wss:/i.test(wsUrl)) {
  console.error('warning: the connection is not wss://, so the token is sent unencrypted; use https:// when Home Assistant serves it');
}
const ws = new WebSocket(wsUrl);
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.type === 'auth_required') ws.send(JSON.stringify({ type: 'auth', access_token: HA_TOKEN }));
  else if (m.type === 'auth_ok') ws.send(JSON.stringify({ id: 1, ...msg }));
  else if (m.type === 'auth_invalid') { console.error('auth invalid'); process.exit(1); }
  else if (m.id === 1) {
    if (!m.success) { console.error(JSON.stringify(m.error)); process.exit(1); }
    ws.close();
    // Exit only once stdout has flushed: a large dashboard written to a pipe would otherwise be cut short.
    process.stdout.write(JSON.stringify(m.result, null, 1) + '\n', () => process.exit(0));
  }
};
ws.onerror = () => { console.error('websocket error'); process.exit(1); };
