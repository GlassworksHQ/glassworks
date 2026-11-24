// The history and statistics HA's page asked for and got, recorded from its own WebSocket, so Vitro's graphs can
// be handed exactly the data HA drew (the fixture answers Vitro's same calls with it).
//
// Touches: nothing (in-memory bookkeeping over the frames capture.mjs hands it).

/** The WebSocket commands whose answers a graph draws: history streams and periods, and long-term statistics. */
export const RECORDED_TYPES = new Set([
  'history/stream',
  'history/history_during_period',
  'recorder/statistics_during_period',
  'recorder/get_statistics_metadata',
]);

/**
 * One page socket's recorder. `sent(msg)` and `received(msg)` take each parsed frame (one command or message);
 * `calls` lists what the page asked for since the last `reset()`, in order: `{ request, result?, error?, events }`,
 * the request without its id. `reset()` starts a new case: answers to an earlier case's ids are dropped from then on.
 */
export function wsRecorder() {
  let byId = new Map();
  let calls = [];
  return {
    get calls() { return calls; },
    reset() {
      byId = new Map();
      calls = [];
    },
    sent(msg) {
      if (!msg || typeof msg !== 'object') return;
      if (msg.type === 'unsubscribe_events') return;
      if (!RECORDED_TYPES.has(msg.type) || typeof msg.id !== 'number') return;
      const { id, ...request } = msg;
      const call = { request, events: [] };
      byId.set(id, call);
      calls.push(call);
    },
    received(msg) {
      if (!msg || typeof msg !== 'object') return;
      const call = byId.get(msg.id);
      if (!call) return;
      if (msg.type === 'result') {
        if (msg.success) call.result = msg.result ?? null;
        else call.error = msg.error ?? { code: 'unknown' };
      } else if (msg.type === 'event') {
        call.events.push(msg.event ?? null);
      }
    },
  };
}

/** A deep copy of [calls] that later frames can't change: what the screenshot showed. */
export const snapshotCalls = (calls) => JSON.parse(JSON.stringify(calls));
