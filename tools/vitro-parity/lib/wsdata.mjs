// The data HA's page asked for and got over its own WebSocket (history and statistics for the graphs, a to-do list's
// items, the logbook, the weather forecast, calendar events and a live camera's capabilities), recorded so Vitro's cards can be handed exactly the data HA drew
// (the fixture answers Vitro's same calls with it).
//
// Touches: nothing (in-memory bookkeeping over the frames capture.mjs hands it).

/**
 * The WebSocket commands whose answers a card draws: history streams and periods and long-term statistics (graphs),
 * a to-do list's items, the logbook's stream, the weather forecast and a calendar's events.
 */
export const RECORDED_TYPES = new Set([
  'history/stream',
  'history/history_during_period',
  'recorder/statistics_during_period',
  'recorder/statistic_during_period',
  'recorder/get_statistics_metadata',
  'todo/item/subscribe',
  'logbook/event_stream',
  'weather/subscribe_forecast',
  'calendar/event/subscribe',
  // Not a WebSocket command: HA's REST history (`GET /api/history/period/<start>`), which mini-graph-card reads. The
  // capture records it in the same list (restHistoryCall), and Vitro's fetchHistory is answered from it.
  'rest/history/period',
  // Who a logbook entry's `context_user_id` is: recorded only from the fake HA, whose users are made up, and only for a
  // case with a logbook or a More Info dialog (capture.mjs keepCall). A real HA's user list is never kept.
  'config/auth/list',
  // Which streams a live camera offers (`camera_view: live`, ha-camera-stream): Vitro picks its player from the same
  // answer. The stream itself (`camera/stream`, the video) isn't recorded: the video is masked out of the comparison.
  'camera/capabilities',
  // An update's release notes (`more-info-update`): Vitro's sheet draws the same Markdown.
  'update/release_notes',
]);

/**
 * Subscriptions whose first answer is what the card draws: the capture waits for it. The logbook sends its history
 * in parts (`partial: true`), so it waits for the part that ends it.
 */
const FIRST_EVENT = new Set(['todo/item/subscribe', 'logbook/event_stream', 'weather/subscribe_forecast', 'calendar/event/subscribe']);

/** The subscriptions in [calls] still waiting for the event a card draws, by type. */
export const pendingFirstEvents = (calls) =>
  calls.filter((c) => FIRST_EVENT.has(c.request?.type) && !('error' in c) &&
    !(c.events || []).some((ev) => c.request.type !== 'logbook/event_stream' || !(ev && ev.partial)))
    .map((c) => c.request.type);

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
    /** A call made outside the WebSocket (REST history): recorded as is, its answer set on it later. */
    push(call) { calls.push(call); return call; },
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

/** The commands answered by one result (everything recorded but the subscriptions). */
const ONE_SHOT = new Set(['history/history_during_period', 'recorder/statistics_during_period', 'recorder/statistic_during_period', 'recorder/get_statistics_metadata', 'rest/history/period', 'config/auth/list', 'update/release_notes']);

/**
 * Whether a recorded call belongs in a reference: the user list only from the fake HA ([fake]) and only for a case
 * whose card shows a logbook ([config]) or that opens a More Info dialog ([moreInfo]), whose Activity section is one;
 * every other recorded call always.
 */
export function keepCall(call, fake, config, moreInfo) {
  if (call?.request?.type !== 'config/auth/list') return true;
  return Boolean(fake) && (Boolean(moreInfo) || JSON.stringify(config ?? null).includes('"logbook"'));
}

/**
 * The request a REST history URL stands for (`/api/history/period/<start>?filter_entity_id=a,b&end_time=..`), as a
 * recorded call's `request`, or null for another URL. Times as the page sent them.
 */
export function restHistoryRequest(url) {
  let u;
  try { u = new URL(url); } catch { return null; }
  const prefix = '/api/history/period';
  if (!u.pathname.startsWith(prefix)) return null;
  const start = decodeURIComponent(u.pathname.slice(prefix.length).replace(/^\//, '')) || null;
  const q = u.searchParams;
  return {
    type: 'rest/history/period',
    entity_ids: (q.get('filter_entity_id') || '').split(',').filter(Boolean),
    start_time: start,
    end_time: q.get('end_time'),
    minimal_response: q.has('minimal_response'),
    no_attributes: q.has('no_attributes'),
    significant_changes_only: q.has('significant_changes_only') ? q.get('significant_changes_only') !== '0' : true,
    skip_initial_state: q.has('skip_initial_state'),
  };
}

/** The one-shot calls in [calls] still waiting for their result or error, by type. */
export const pendingOneShots = (calls) =>
  calls.filter((c) => ONE_SHOT.has(c.request?.type) && !('result' in c) && !('error' in c)).map((c) => c.request.type);

/** A deep copy of [calls] that later frames can't change: what the screenshot showed. */
export const snapshotCalls = (calls) => JSON.parse(JSON.stringify(calls));
