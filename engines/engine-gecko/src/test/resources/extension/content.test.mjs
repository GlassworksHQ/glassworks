// content.js's "ready" for the connecting screen (#63, round 4 panel run), run under node:test with
// the browser, the DOM, frames and timers stubbed. Run by ContentScriptReadyTest (or by hand:
// CONTENT_JS=engines/engine-gecko/src/main/assets/extension/content.js node --test this file).
// Touches nothing outside this process.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const SOURCE = readFileSync(process.env.CONTENT_JS, "utf8");

/** content.js in a fresh page: [launchScreen] in the body or not, [externalAuth] in the URL or not. */
function page({ launchScreen = true, externalAuth = true, search = null, hass = null, vars = {}, storage = null, frame = false } = {}) {
  const posted = [];
  const timers = [];
  let frames = [];
  let now = 0;
  let observer = null;
  let launch = launchScreen ? { id: "ha-launch-screen", parentNode: {} } : null;
  const domListeners = {};
  const toPage = [];
  const appended = [];
  const events = [];
  const fromApp = [];
  const winListeners = {};
  // The page's localStorage ([storage]: its items, changed by the test as it goes).
  const items = storage ?? {};
  let storageReads = 0;

  const window = {
    wrappedJSObject: { externalBus: (m) => toPage.push(m) },
    dispatchEvent: (e) => events.push(e),
    addEventListener: (type, fn) => { (winListeners[type] ||= []).push(fn); },
  };
  window.top = frame ? {} : window;
  const document = {
    readyState: "loading",
    hidden: false,
    addEventListener: (type, fn) => { (domListeners[type] ||= []).push(fn); },
    getElementById: (id) => (id === "ha-launch-screen" ? launch : null),
    // <home-assistant> with the frontend's hass object, when the test gives one.
    querySelector: (sel) => (sel === "home-assistant" && hass ? { wrappedJSObject: { hass } } : null),
    createElement: (tag) => ({ tag, removed: false, remove() { this.removed = true; } }),
    head: null, // document_start: no <head> yet
    documentElement: { appendChild: (el) => appended.push(el) },
  };
  const ctx = {
    window, document, console,
    location: { search: search ?? (externalAuth ? "?external_auth=1" : "") },
    URLSearchParams,
    localStorage: {
      get length() { storageReads++; return Object.keys(items).length; },
      key: (i) => Object.keys(items)[i] ?? null,
      getItem: (k) => (k in items ? items[k] : null),
      setItem: (k, v) => { items[k] = String(v); },
    },
    CustomEvent: class { constructor(t, o) { this.type = t; Object.assign(this, o); } },
    browser: { runtime: { connectNative: () => ({
      postMessage: (m) => posted.push(JSON.parse(JSON.stringify(m))),
      onMessage: { addListener: (fn) => fromApp.push(fn) },
      onDisconnect: { addListener() {} },
    }) } },
    MutationObserver: class {
      constructor(cb) { this.cb = cb; observer = this; }
      observe() {}
      disconnect() { observer = null; }
    },
    createObjectIn: (target, { defineAs }) => (target[defineAs] = {}),
    exportFunction: (fn, target, opts) => { if (opts?.defineAs) target[opts.defineAs] = fn; return fn; },
    cloneInto: (x) => x,
    setTimeout: (fn, ms = 0) => { timers.push({ at: now + ms, fn }); return timers.length; },
    clearTimeout() {},
    setInterval: () => 0,
    requestAnimationFrame: (fn) => { frames.push(fn); return frames.length; },
    // The theme variables Home Assistant puts on the document ([vars], changed by the test as it goes).
    getComputedStyle: () => ({ getPropertyValue: (name) => vars[name] ?? "" }),
  };
  vm.runInNewContext(SOURCE, ctx);

  return {
    posted, toPage, window, appended, events, items,
    /** How often the script looked at localStorage. */
    storageReads: () => storageReads,
    storage: () => posted.filter((m) => m.type === "pageStorage").map((m) => JSON.parse(m.json)),
    /** The page goes away (pagehide). */
    pagehide() { (winListeners.pagehide || []).forEach((f) => f()); },
    /** The app sends [m] over the native port. */
    send(m) { fromApp.forEach((f) => f(m)); },
    ready: () => posted.filter((m) => m.type === "ready"),
    domContentLoaded() { document.readyState = "interactive"; (domListeners.DOMContentLoaded || []).forEach((f) => f()); },
    /** HA's frontend removes its launch screen. */
    removeLaunchScreen() { launch = null; observer?.cb([]); },
    /** One painted frame: the requestAnimationFrame callbacks queued before it run. */
    frame() { const due = frames; frames = []; due.forEach((f) => f()); },
    /** Time passes: due timers run. */
    advance(ms) {
      now += ms;
      for (;;) {
        const i = timers.findIndex((t) => t.at <= now);
        if (i < 0) break;
        timers.splice(i, 1)[0].fn();
      }
    },
  };
}

test("no ready while Home Assistant's launch screen is up; it says launch", () => {
  const p = page();
  p.domContentLoaded();
  p.advance(5_000);
  p.frame(); p.frame();
  assert.deepEqual(p.ready(), []);
  assert.ok(p.posted.some((m) => m.type === "launch"));
});

test("after the launch screen goes, ready waits for two painted frames and the margin", () => {
  // Round 4: "ready" went out on the DOM change, and the app faded over a frame Gecko hadn't
  // repainted yet, with the white splash still in it.
  const p = page();
  p.domContentLoaded();
  p.removeLaunchScreen();
  p.advance(1_000);
  assert.deepEqual(p.ready(), [], "not before a frame is painted");
  p.frame();
  p.advance(1_000);
  assert.deepEqual(p.ready(), [], "not after one frame");
  p.frame();
  p.advance(149);
  assert.deepEqual(p.ready(), [], "not before the margin");
  p.advance(1);
  assert.deepEqual(p.ready().map((m) => m.why), ["removed"]);
});

test("the frontend's own frontend/loaded says ready the same way, once", () => {
  const p = page();
  p.domContentLoaded();
  p.window.externalApp.externalBus(JSON.stringify({ id: 7, type: "frontend/loaded" }));
  p.frame(); p.frame(); p.advance(150);
  assert.deepEqual(p.ready().map((m) => m.why), ["frontend/loaded"]);
  p.removeLaunchScreen();
  p.frame(); p.frame(); p.advance(500);
  assert.equal(p.ready().length, 1);
});

test("config/get tells the frontend the app has its own splash screen", () => {
  const p = page();
  p.window.externalApp.externalBus(JSON.stringify({ id: 1, type: "config/get" }));
  p.advance(0);
  const reply = p.toPage.find((m) => m.id === 1);
  assert.equal(reply.success, true);
  assert.equal(reply.result.hasSplashscreen, true);
});

test("a page without a launch screen is ready once it has painted", () => {
  const p = page({ launchScreen: false, externalAuth: false });
  p.domContentLoaded();
  assert.deepEqual(p.ready(), []);
  p.frame(); p.frame(); p.advance(150);
  assert.deepEqual(p.ready().map((m) => m.why), ["page"]);
});

test("from the start, HA's launch screen draws at night, and only while it is there", () => {
  // Round 5 panel run: one fully white frame in two cold starts, timing alone can't rule it out.
  const p = page();
  const style = p.appended.find((el) => el.tag === "style" && el.id === "glassworks-launch");
  assert.ok(style, "the style is added at document_start, before anything is drawn");
  const css = style.textContent;
  assert.match(css, /#ha-launch-screen\s*\{[^}]*background:\s*#0a0b0f/);
  // Every selector names the launch screen, so HA's theme is untouched once the frontend removes it.
  const rules = css.split("}").map((r) => r.split("{")).filter((r) => r[0].trim());
  const selectors = rules.flatMap(([sel]) => sel.split(","));
  assert.ok(selectors.length >= 4);
  for (const sel of selectors) assert.match(sel, /ha-launch-screen/, `unscoped rule: ${sel.trim()}`);
  // ... and the style goes with it (verifier d7acb506, F3).
  p.domContentLoaded();
  assert.equal(style.removed, false);
  p.removeLaunchScreen();
  assert.equal(style.removed, true);
});

test("only the logo and the attribution are hidden: a failed start still says why", () => {
  // Verifier d7acb506, F1: HA writes "Could not load Home Assistant…" into the info box, inside the
  // launch screen. Hiding everything in it turned a failed start into a black screen.
  const css = page().appended.find((el) => el.id === "glassworks-launch").textContent;
  const rules = css.split("}").map((r) => r.split("{")).filter((r) => r[0].trim());
  const hidden = rules.filter(([, decl]) => /visibility:\s*hidden|display:\s*none|opacity:\s*0/.test(decl))
    .flatMap(([sel]) => sel.split(",").map((x) => x.trim()));
  assert.deepEqual(hidden.sort(), ["#ha-launch-screen .ha-lockup", "#ha-launch-screen .ohf-logo"]);
  assert.ok(!/#ha-launch-screen \*/.test(css), "no rule for everything inside the launch screen");
  const info = rules.find(([sel]) => /ha-launch-screen-info-box/.test(sel));
  assert.ok(info, "the info box has its own rule");
  assert.match(info[1], /color:\s*#f4f4f5/);
});

test("not on a page opened with ?kiosk=0 or ?edit=1", () => {
  // Verifier d7acb506, F2: like the header and sidebar rules.
  for (const search of ["?kiosk=0", "?edit=1"]) {
    assert.equal(page({ search }).appended.filter((el) => el.id === "glassworks-launch").length, 0, search);
  }
});

test("a page without a launch screen drops the style at once", () => {
  const p = page({ launchScreen: false, externalAuth: false });
  const style = p.appended.find((el) => el.id === "glassworks-launch");
  p.domContentLoaded();
  assert.equal(style.removed, true);
});

test("getExternalAuth failure drops the launch style so HA's error is visible", async () => {
  const p = page();
  const style = p.appended.find((el) => el.id === "glassworks-launch");
  const callbacks = [];
  p.window.wrappedJSObject.externalAuthSetToken = (...args) => callbacks.push(args);
  p.domContentLoaded();
  assert.equal(style.removed, false);

  // A successful reply leaves the launch style in place until the frontend removes #ha-launch-screen.
  p.window.externalApp.getExternalAuth(JSON.stringify({ callback: "externalAuthSetToken", force: false }));
  const okReq = p.posted.find((m) => m.type === "getExternalAuth");
  p.send({ type: "reply", id: okReq.id, success: true, access_token: "tok", expires_in: 1800 });
  await Promise.resolve();
  assert.equal(style.removed, false);
  assert.equal(callbacks.length, 1);
  assert.equal(callbacks[0][0], true);

  // A failed reply drops the launch style immediately so the launch screen isn't left solid dark.
  p.window.externalApp.getExternalAuth(JSON.stringify({ callback: "externalAuthSetToken", force: true }));
  const failReq = p.posted.filter((m) => m.type === "getExternalAuth").at(-1);
  p.send({ type: "reply", id: failReq.id, success: false });
  await Promise.resolve();
  assert.equal(style.removed, true);
  assert.deepEqual(callbacks[1], [false]);
});

test("revealLaunch from the app drops the launch style on fallback", () => {
  const p = page();
  const style = p.appended.find((el) => el.id === "glassworks-launch");
  p.domContentLoaded();
  assert.equal(style.removed, false);
  p.send({ type: "revealLaunch" });
  assert.equal(style.removed, true);
});

test("only one ready, however many signals come", () => {
  const p = page();
  p.domContentLoaded();
  p.window.externalApp.externalBus(JSON.stringify({ id: 7, type: "frontend/loaded" }));
  p.removeLaunchScreen();
  p.window.externalApp.externalBus(JSON.stringify({ id: 8, type: "frontend/loaded" }));
  p.frame(); p.frame(); p.advance(500);
  assert.equal(p.ready().length, 1);
});

test("panel state reaches the page as window.glassworks, and as window.s1kiosk for older dashboards", () => {
  const p = page();
  const state = { presence: true, proximityRaw: 3, lux: 120, screenOn: true, voice: "idle" };
  p.send({ type: "state", state });
  assert.deepEqual(p.window.wrappedJSObject.glassworks, state);
  assert.equal(p.window.wrappedJSObject.s1kiosk, p.window.wrappedJSObject.glassworks, "one object, two names");
  assert.deepEqual(p.events.map((e) => e.type), ["glassworks-state", "s1kiosk-state"]);
  assert.ok(p.events.every((e) => e.detail === p.window.wrappedJSObject.glassworks));
});

test("Home Assistant's dark mode is sent when it is known, and again only when it changes", () => {
  const hass = { themes: { darkMode: true } };
  const p = page({ launchScreen: false, hass });
  const theme = () => p.posted.filter((m) => m.type === "haTheme");
  assert.deepEqual(theme(), [], "nothing before the first check");
  p.advance(2000);
  assert.deepEqual(theme(), [{ type: "haTheme", dark: true }]);
  p.advance(6000);
  assert.equal(theme().length, 1, "the same mode isn't sent again");
  hass.themes.darkMode = false;
  p.advance(2000);
  assert.deepEqual(theme().at(-1), { type: "haTheme", dark: false });
  assert.equal(theme().length, 2);
});

test("no dark mode is sent before the frontend has one", () => {
  const hass = { themes: {} };
  const p = page({ launchScreen: false, hass });
  p.advance(10000);
  assert.deepEqual(p.posted.filter((m) => m.type === "haTheme"), []);
  hass.themes.darkMode = true;
  p.advance(2000);
  assert.deepEqual(p.posted.filter((m) => m.type === "haTheme"), [{ type: "haTheme", dark: true }]);
});

test("Home Assistant's colors are sent as #rrggbb with its mode, and again when they change", () => {
  const hass = { themes: { darkMode: true } };
  const vars = { "--primary-color": " #03A9F4", "--accent-color": "rgb(255, 152, 0)", "--primary-background-color": "#111" };
  const p = page({ launchScreen: false, hass, vars });
  const theme = () => p.posted.filter((m) => m.type === "haTheme");
  p.advance(2000);
  assert.deepEqual(theme(), [{ type: "haTheme", dark: true, primary: "#03a9f4", accent: "#ff9800", background: "#111111" }]);
  p.advance(4000);
  assert.equal(theme().length, 1);
  vars["--primary-color"] = "rgba(76, 175, 80, 0.9)";
  p.advance(2000);
  assert.equal(theme().at(-1).primary, "#4caf50");
});

test("colors that aren't plain are left out, and a missing accent takes the primary", () => {
  const hass = { themes: { darkMode: false } };
  const p = page({ launchScreen: false, hass, vars: { "--primary-color": "#fafafa", "--primary-background-color": "#ffffff" } });
  p.advance(2000);
  assert.deepEqual(p.posted.filter((m) => m.type === "haTheme").at(-1), { type: "haTheme", dark: false, primary: "#fafafa", accent: "#fafafa", background: "#ffffff" });
  const q = page({ launchScreen: false, hass, vars: { "--primary-color": "hsl(200 50% 50%)", "--primary-background-color": "#fff" } });
  q.advance(2000);
  assert.deepEqual(q.posted.filter((m) => m.type === "haTheme"), [{ type: "haTheme", dark: false }]);
});


/** The app's config with Vitro on: it asks for the page's storage. */
const VITRO_CONFIG = { type: "config", config: { pageStorage: true } };

test("with Vitro off (no pageStorage in the app's config), localStorage is never read or sent", () => {
  const p = page({ hass: { themes: {} }, storage: { thc_snooze: '{"h_1":99}' } });
  p.advance(20_000);
  p.items.thc_snooze = '{"h_1":100}';
  p.pagehide();
  p.send({ type: "config", config: { hideHeader: false } });
  p.advance(20_000);
  p.pagehide();
  assert.deepEqual(p.storage(), []);
  assert.equal(p.storageReads(), 0, "no 5 s walk over the page's storage either");
});

test("the copy starts when the app's config asks for it, from the reply or a later push", async () => {
  const p = page({ hass: { themes: {} }, storage: { a: "1" } });
  const ask = p.posted.find((m) => m.type === "config");
  p.send({ type: "reply", id: ask.id, config: { pageStorage: true } });
  await new Promise((r) => setImmediate(r)); // the reply's promise settles
  p.advance(5_000);
  assert.deepEqual(p.storage(), [{ a: "1", dockedSidebar: '"always_hidden"' }]);
  p.send(VITRO_CONFIG); // a second config doesn't start a second watch
  p.items.a = "2";
  p.advance(5_000);
  assert.equal(p.storage().length, 2);
});

test("Home Assistant's localStorage is sent without credentials, and again only when it changes", () => {
  const p = page({ hass: { themes: {} }, storage: { thc_snooze: '{"h_1":99}', hassTokens: '{"access_token":"x"}', selectedTheme: '"dark"' } });
  p.send(VITRO_CONFIG);
  p.advance(5_000);
  // The helper's own dockedSidebar is there too: HA's page holds it, so an island sees it as well.
  assert.deepEqual(p.storage(), [{ dockedSidebar: '"always_hidden"', selectedTheme: '"dark"', thc_snooze: '{"h_1":99}' }]);
  p.advance(5_000);
  assert.equal(p.storage().length, 1, "nothing new: nothing sent");
  p.items.thc_snooze = '{"h_1":99,"h_2":120}';
  p.items.my_card_auth = "secret";
  p.advance(5_000);
  assert.equal(p.storage().length, 2);
  assert.equal(p.storage()[1].thc_snooze, '{"h_1":99,"h_2":120}');
  assert.ok(!("my_card_auth" in p.storage()[1]), "a key that names auth stays in the page");
  assert.ok(!("hassTokens" in p.storage()[1]));
});

test("a change is sent as the page goes away, before the next check", () => {
  const p = page({ hass: { themes: {} }, storage: {} });
  p.send(VITRO_CONFIG);
  p.advance(5_000);
  p.items.thc_kiosk = "1";
  p.pagehide();
  assert.equal(p.storage().at(-1).thc_kiosk, "1");
});

test("storage is never sent from a frame or from a page that isn't Home Assistant", () => {
  const f = page({ hass: { themes: {} }, storage: { a: "1" }, frame: true });
  f.send(VITRO_CONFIG);
  f.advance(20_000);
  f.pagehide();
  assert.deepEqual(f.storage(), []);
  const other = page({ storage: { a: "1" } });
  other.send(VITRO_CONFIG);
  other.advance(20_000);
  assert.deepEqual(other.storage(), []);
});
