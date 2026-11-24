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
 * The page's recorder. `socket()` gives each WebSocket its own `sent(msg)` / `received(msg)` pair (message ids are
 * per connection, so an answer on one socket never matches a request made on another); `calls` lists what the page
 * asked for since the last `reset()`, from every socket, in order: `{ request, result?, error?, events }`, the request
 * without its id. `reset()` starts a new case: answers to an earlier case's ids are dropped from then on.
 */
export function wsRecorder() {
  const sockets = new Set();
  let calls = [];
  return {
    get calls() { return calls; },
    reset() {
      for (const byId of sockets) byId.clear();
      calls = [];
    },
    socket() {
      const byId = new Map();
      sockets.add(byId);
      return {
        sent(msg) {
          if (!msg || typeof msg !== 'object') return;
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
    },
  };
}

/** The commands answered by one result (everything recorded but `history/stream`, a subscription). */
const ONE_SHOT = new Set(['history/history_during_period', 'recorder/statistics_during_period', 'recorder/get_statistics_metadata']);

/** The one-shot calls in [calls] still waiting for their result or error, by type. */
export const pendingOneShots = (calls) =>
  calls.filter((c) => ONE_SHOT.has(c.request?.type) && !('result' in c) && !('error' in c)).map((c) => c.request.type);

/** A deep copy of [calls] that later frames can't change: what the screenshot showed. */
export const snapshotCalls = (calls) => JSON.parse(JSON.stringify(calls));
