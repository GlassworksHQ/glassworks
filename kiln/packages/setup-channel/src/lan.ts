// The setup channel over the LAN (#34): the panel's `/setup/channel` WebSocket as a ByteStream, so
// SetupChannel runs on it unchanged. The panel's page opens it on its own origin; until the browser has
// paired with the code on the panel's screen, every request but `state` is refused (NOT_PAIRED).
import type { ByteStream } from '@glassworks/improv-serial';

/** The WebSocket's path on the panel's web port (8099). */
export const LAN_CHANNEL_PATH = '/setup/channel';

/** What the panel answers to a request that changes something before the browser has paired. */
export const NOT_PAIRED = 'Pair with the panel first: enter the code it shows.';

/** A ByteStream over an open WebSocket, and when it ended (the close code). */
export type WebSocketStream = ByteStream & { closed: Promise<number> };

const REFUSED = 'The panel refused the connection. Reload this page, or try again in a minute.';

/**
 * Opens [url] and resolves once it is open; rejects when the panel refuses, errs, or doesn't open it within
 * `timeoutMs`. Each write goes as one text frame (the channel writes whole lines); every frame that comes back
 * goes to `onData` as UTF-8 bytes, also those that came before it was registered. An error after it opened
 * closes it, so `closed` always settles. `make` is for tests.
 */
export function openWebSocketStream(url: string, make: (url: string) => WebSocket = (u) => new WebSocket(u), timeoutMs = 10_000): Promise<WebSocketStream> {
  const ws = make(url);
  ws.binaryType = 'arraybuffer';
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  let receiver: ((c: Uint8Array) => void) | undefined;
  const early: Uint8Array[] = [];
  let ended!: (code: number) => void;
  const closed = new Promise<number>((r) => { ended = r; });
  ws.onmessage = (e: MessageEvent) => {
    const bytes = typeof e.data === 'string' ? enc.encode(e.data) : new Uint8Array(e.data as ArrayBuffer);
    if (receiver) receiver(bytes); else early.push(bytes);
  };
  const stream: WebSocketStream = {
    closed,
    onData(cb) {
      receiver = cb;
      for (const b of early.splice(0)) cb(b);
    },
    async write(data) {
      if (ws.readyState !== 1 /* OPEN */) throw new Error('the panel closed the connection');
      ws.send(dec.decode(data));
    },
    async close() { ws.close(); },
  };
  return new Promise((resolve, reject) => {
    let settled = false;
    const fail = (why: string) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try { ws.close(); } catch { /* already closing */ }
      reject(new Error(why));
    };
    const timer = setTimeout(() => fail('The panel didn\'t answer. Is it on, and on this network?'), timeoutMs);
    ws.onopen = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(stream);
    };
    // before it opened: refused; after: a browser closes it next, and this makes sure of it
    ws.onerror = () => { if (settled) ws.close(); else fail(REFUSED); };
    ws.onclose = (e: CloseEvent) => {
      ended(e.code);
      fail(REFUSED);
    };
  });
}
