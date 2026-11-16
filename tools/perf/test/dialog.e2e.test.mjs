// node --test tools/perf/test/dialog.e2e.test.mjs
//
// #211 end to end, no device: the fake Home Assistant serves HA's real frontend (the pinned build) to
// headless Chrome at the S1's viewport (960 x 480 CSS px, ratio 1.5), and the harness opens HA's
// more-info dialog on a view as the runner asks. HA shows it as a bottom sheet there (its adaptive
// dialog's max-height 500 px), with its 300 ms animation; each dialog must open, settle (the sensor's
// history graph drawn) and close, and the fake must know every command the dialogs send. Then the same
// with prefers-reduced-motion, where HA's sheet doesn't animate, and at a desktop's viewport, where
// HA shows its centred dialog.
//
// Needs Chrome (CHROME= picks one), ffmpeg with libx264 (the start view's camera), the WebRTC venv
// (webrtcenv.py; GW_PERF_FAKE_PYTHON= picks another) and the pinned frontend (fetched once, 117 MB).
// Without them it skips, except with GW_PERF_E2E=1 (CI), where it fails. Starts its own fake on free
// ports and its own Chrome profile; touches no device.
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { guard } from "./browserguard.mjs";

const PERF = join(dirname(fileURLToPath(import.meta.url)), "..");
const MUST = process.env.GW_PERF_E2E === "1";
const TIERS = JSON.parse(readFileSync(join(PERF, "page", "tiers.json"), "utf8"));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function chrome() {
  const paths = [process.env.CHROME, "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/opt/google/chrome/chrome"];
  return paths.find((p) => p && existsSync(p)) || null;
}

function python3(args) {
  const r = spawnSync("python3", args, { cwd: PERF, encoding: "utf8", timeout: 600000 });
  return r.status === 0 ? r.stdout.trim() : null;
}

function missing() {
  if (!chrome()) return "no Chrome (CHROME=)";
  if (!spawnSync("ffmpeg", ["-hide_banner", "-encoders"], { encoding: "utf8" }).stdout?.includes("libx264")) return "no ffmpeg with libx264";
  return null;
}

async function freePort() {
  // The relay takes port + 1, so ask for two in a row.
  for (;;) {
    const p = await new Promise((res) => { const s = createServer().listen(0, "127.0.0.1", () => { const { port } = s.address(); s.close(() => res(port)); }); });
    const ok = await new Promise((res) => { const s = createServer().once("error", () => res(false)).listen(p + 1, "127.0.0.1", () => s.close(() => res(true))); });
    if (ok) return p;
  }
}

// Whole GWPERF lines from the page's console (stats.js consoleLines splits long ones into parts).
function pageLines() {
  const parts = new Map(), lines = [];
  return {
    lines,
    feed(text) {
      let m = /^GWPERF\+ (\S+) (\d+)\/(\d+) ([\s\S]*)$/.exec(text);
      if (m) {
        const got = parts.get(m[1]) || new Map();
        got.set(Number(m[2]), m[4]);
        parts.set(m[1], got);
        if (got.size === Number(m[3])) {
          lines.push(JSON.parse([...Array(got.size).keys()].map((k) => got.get(k)).join("")));
          parts.delete(m[1]);
        }
        return;
      }
      m = /^GWPERF (.*)$/s.exec(text);
      if (m) lines.push(JSON.parse(m[1]));
    },
  };
}

async function until(what, f, ms) {
  const end = Date.now() + ms;
  for (;;) {
    const v = f();
    if (v) return v;
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
    await sleep(200);
  }
}

// Every dialog of one view: opened, settled, closed, its timing points in order.
function checkDialogs(t, lines, stage, mode) {
  const ds = lines.filter((l) => l.t === "dialog" && l.stage === stage);
  t.diagnostic(`${stage}: ${JSON.stringify(ds.map((d) => [d.kind, d.mode, d.show_duration_ms, d.open_ms, d.shown_ms, d.graph_ms, d.settled_ms, d.close_ms, d.dropped, d.error]))}`);
  assert.deepEqual(ds.map((d) => d.entity), TIERS.DIALOG.entities.map((e) => e.entity));
  for (const d of ds) {
    assert.equal(d.error, null, `${d.kind}: ${d.error}`);
    assert.equal(d.close_error, null, `${d.kind}: ${d.close_error}`);
    assert.equal(d.mode, mode, `${d.kind}: shown as ${d.mode}`);
    assert.equal(d.of, TIERS.DIALOG.entities.length);
    // settled_ms is graded and unrounded; the detail points are to 0.1 ms.
    const settled = Math.round(d.settled_ms * 10) / 10;
    assert.ok(d.opened && d.open_ms >= 0 && d.open_ms <= d.shown_ms && d.shown_ms <= settled, JSON.stringify(d));
    assert.ok(d.close_ms > 0, `${d.kind}: close ${d.close_ms}`);
    if (d.graph) assert.ok(d.graph_ms !== null && d.graph_ms <= settled, `${d.kind}: graph ${d.graph_ms}`);
    else assert.equal(d.graph_ms, null);
  }
  return ds;
}

test("HA's more-info dialogs open, settle and close on a view at the S1's viewport, with and without motion", { timeout: 600000 }, async (t) => {
  const why = missing();
  if (why) {
    if (MUST) assert.fail(`GW_PERF_E2E=1 but ${why}`);
    return t.skip(why);
  }
  const py = process.env.GW_PERF_FAKE_PYTHON || python3(["webrtcenv.py"]);
  assert.ok(py, "webrtcenv.py made no venv");
  assert.ok(python3(["hafrontend.py"]), "hafrontend.py couldn't fetch the pinned frontend");
  assert.ok(python3(["camera.py"]), "camera.py couldn't make the streams");

  const dir = mkdtempSync(join(tmpdir(), "gw-dialog-"));
  const unguard = guard(dir);           // Chrome and the fake die with this test, however it ends
  const port = await freePort(), base = `http://127.0.0.1:${port}`;
  const fake = spawn(py, [join(PERF, "fakeha.py"), "--port", String(port), "--log", join(dir, "fakeha.jsonl")],
                     { stdio: ["ignore", "pipe", "pipe"] });
  let fakeErr = "";
  fake.stderr.on("data", (d) => { fakeErr += d; });
  const browser = spawn(chrome(), ["--headless=new", "--remote-debugging-port=0", `--user-data-dir=${join(dir, "profile")}`,
    "--no-first-run", "--no-default-browser-check", "--autoplay-policy=no-user-gesture-required", "--mute-audio",
    "--window-size=1280,800", ...(process.env.CI ? ["--no-sandbox"] : []), "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
  t.after(() => {
    browser.kill("SIGKILL");
    unguard();
    fake.kill();
    if (!t.passed && fakeErr) t.diagnostic(`the fake's stderr:\n${fakeErr.slice(-4000)}`);
    rmSync(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 300 });
  });

  let banner = "";
  fake.stdout.on("data", (d) => { banner += d; });
  await until("the fake", () => banner.includes("WebRTC over"), 60000);
  let err = "";
  browser.stderr.on("data", (d) => { err += d; });
  const wsUrl = await until("Chrome's DevTools", () => (/DevTools listening on (ws:\S+)/.exec(err) || [])[1], 30000);

  const ws = new WebSocket(wsUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let id = 0;
  const pending = new Map(), page = pageLines();
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
    if (m.method === "Runtime.consoleAPICalled") {
      const v = m.params.args[0] && m.params.args[0].value;
      if (typeof v === "string") page.feed(v);
    }
  };
  const cdp = (method, params = {}, sessionId) => new Promise((r) => {
    pending.set(++id, r);
    ws.send(JSON.stringify({ id, method, params, sessionId }));
  });
  const { result: { targetId } } = await cdp("Target.createTarget", { url: "about:blank" });
  const { result: { sessionId } } = await cdp("Target.attachToTarget", { targetId, flatten: true });
  await cdp("Runtime.enable", {}, sessionId);
  // The S1: 1440 x 720 at a device pixel ratio of 1.5, a CSS viewport of 960 x 480.
  await cdp("Emulation.setDeviceMetricsOverride", { width: 960, height: 480, deviceScaleFactor: 1.5, mobile: false }, sessionId);
  await cdp("Page.navigate", { url: `${base}/clarity-ref/start` }, sessionId);
  await until("the start view's done", () => page.lines.find((l) => l.t === "done" && l.stage === "restart"), 180000);
  // The restart's warm-up: each dialog once, before any view opens.
  await until("the warm-up's dialogs", () => page.lines.filter((l) => l.t === "dialog" && l.stage === "warmup").length === TIERS.DIALOG.entities.length, 120000);
  checkDialogs(t, page.lines, "warmup", "bottom-sheet");

  const open = async (view, nonce) => {
    const q = new URLSearchParams({ view, nonce, run: "1", taps: "0", swipes: "0", vsync: "16.667" });
    assert.equal((await fetch(`${base}/clarity/open?${q}`)).status, 204);
    const end = await until(`${view} done`, () => page.lines.find((l) => l.nonce === nonce && (l.t === "done" || l.t === "error")), 180000);
    assert.equal(end.t, "done", end.message);
    return page.lines.filter((l) => l.nonce === nonce);
  };

  // HA's bottom sheet at the S1's viewport: 300 ms (ha-bottom-sheet.ts BOTTOM_SHEET_ANIMATION_DURATION_MS).
  const light = checkDialogs(t, await open("light", "d1a10001"), "light", "bottom-sheet");
  for (const d of light) {
    assert.equal(d.show_duration_ms, 300);
    assert.equal(d.reduced_motion, false);
    // The sheet slides for 300 ms: the animation's frames span about that.
    assert.ok(d.shown_ms - d.open_ms >= 250, `${d.kind}: animation ${d.shown_ms - d.open_ms} ms`);
    assert.ok(d.anim.n >= 5, `${d.kind}: ${d.anim.n} animation frames`);
  }
  // The sensor's graph is drawn once its history has come back over the websocket, never at once.
  const sensor = light.find((d) => d.graph);
  assert.ok(sensor.graph_ms > 0, `graph ${sensor.graph_ms}`);

  // prefers-reduced-motion: HA's sheet takes 1 ms and the graph doesn't animate.
  await cdp("Emulation.setEmulatedMedia", { features: [{ name: "prefers-reduced-motion", value: "reduce" }] }, sessionId);
  const still = checkDialogs(t, await open("typical-nocam", "d1a10002"), "typical-nocam", "bottom-sheet");
  for (const d of still) {
    assert.equal(d.reduced_motion, true);
    assert.equal(d.show_duration_ms, 1);
  }
  await cdp("Emulation.setEmulatedMedia", { features: [] }, sessionId);

  // A desktop's viewport: HA's centred dialog (ha-dialog, 200 ms). The adaptive dialog picks its mode
  // once, when HA first puts it on the page, so this needs a fresh load: its warm-up shows them.
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1280, height: 800, deviceScaleFactor: 1, mobile: false }, sessionId);
  const before = page.lines.length;
  await cdp("Page.navigate", { url: `${base}/clarity-ref/start` }, sessionId);
  await until("the reload's warm-up", () => page.lines.slice(before).filter((l) => l.t === "dialog" && l.stage === "warmup").length === TIERS.DIALOG.entities.length, 180000);
  for (const d of checkDialogs(t, page.lines.slice(before), "warmup", "dialog")) assert.equal(d.show_duration_ms, 200);

  const log = readFileSync(join(dir, "fakeha.jsonl"), "utf8").trim().split("\n").map((l) => JSON.parse(l));
  // Every command the dialogs sent is one the fake knows (else the grade is provisional on a panel).
  assert.deepEqual(log.filter((e) => e.unknown || e.failed), []);
  assert.ok(log.some((e) => e.type === "config/entity_registry/get"), "the dialogs never asked for their registry entry");
  assert.ok(log.some((e) => e.type === "history/stream" && (e.msg.entity_ids || []).includes("sensor.clarity_10")),
            "the sensor's dialog never asked for its history");
});
