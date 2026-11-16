import { describe, expect, it } from 'vitest';
import { NOT_PAIRED, SetupChannel, openWebSocketStream } from '../src/channel';

/** A browser WebSocket, driven by the test: `open`, `frame`, `end`; what the page sent is in `sent`. */
class FakeSocket {
  binaryType = 'blob';
  readyState = 0;
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onclose: ((e: { code: number }) => void) | null = null;
  onmessage: ((e: { data: string | ArrayBuffer }) => void) | null = null;
  constructor(readonly url: string) {}
  send(s: string) { this.sent.push(s); }
  close() { this.end(1000); }
  open() { this.readyState = 1; this.onopen?.(); }
  frame(data: string | ArrayBuffer) { this.onmessage?.({ data }); }
  end(code: number) { this.readyState = 3; this.onclose?.({ code }); }
}

const make = () => {
  let sock!: FakeSocket;
  return { make: (u: string) => (sock = new FakeSocket(u)) as unknown as WebSocket, get sock() { return sock; } };
};

describe('the setup channel over the LAN', () => {
  it('runs the same requests over the WebSocket, one text frame per line', async () => {
    const m = make();
    const opening = openWebSocketStream('ws://192.0.2.10:8099/setup/channel', m.make);
    m.sock.open();
    const c = new SetupChannel(await opening);
    const asked = c.armPairing();
    expect(m.sock.sent).toEqual(['{"id":1,"op":"pairing.arm"}\n']);
    m.sock.frame('{"id":1,"ok":false,"error":"' + NOT_PAIRED + '"}\n');
    await expect(asked).rejects.toThrow(NOT_PAIRED);
  });

  it('keeps frames that arrive before the channel listens, and binary ones', async () => {
    const m = make();
    const opening = openWebSocketStream('ws://192.0.2.10:8099/setup/channel', m.make);
    m.sock.open();
    const stream = await opening;
    m.sock.frame('{"event":"state","state":{"ha":{"signedIn":false},"radio":{"mode":"off"}}}\n');
    const c = new SetupChannel(stream);
    expect(c.state?.radio.mode).toBe('off');
    m.sock.frame(new TextEncoder().encode('{"event":"state","state":{"ha":{"signedIn":true},"radio":{"mode":"off"}}}\n').buffer as ArrayBuffer);
    expect(c.state?.ha.signedIn).toBe(true);
  });

  it('fails to open when the panel refuses, and says when an open one ends', async () => {
    const refused = make();
    const p = openWebSocketStream('ws://192.0.2.10:8099/setup/channel', refused.make);
    refused.sock.end(1006);
    await expect(p).rejects.toThrow(/refused/);

    const m = make();
    const opening = openWebSocketStream('ws://192.0.2.10:8099/setup/channel', m.make);
    m.sock.open();
    const stream = await opening;
    m.sock.end(1013);
    expect(await stream.closed).toBe(1013);
    await expect(stream.write(new TextEncoder().encode('{"id":1,"op":"state"}\n'))).rejects.toThrow(/closed/);
  });
});
