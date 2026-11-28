// The lockdown extension's policy.js (#198) under node:test. Run by LockdownPolicyJsTest (or by hand:
// POLICY_JS=engines/engine-gecko-legacy/src/main/assets/lockdown/policy.js node --test this file).
// Touches nothing outside this process. Addresses are RFC 5737.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

// As background.js gets it: a classic script that defines LockdownPolicy.
const context = vm.createContext({ URL }); // the background script has URL too
vm.runInContext(`${readFileSync(process.env.POLICY_JS, "utf8")}\nthis.P = LockdownPolicy;`, context);
const P = context.P;
/** A verdict as a plain object of this realm, for deepEqual. */
const decide = (details, ha, local) => JSON.parse(JSON.stringify(P.decide(details, ha, local)));
const HA = "http://192.0.2.10:8123";

test("every case reads as Origin.kt reads it (origin-cases.json, run through Kotlin by LockdownPolicyJsTest)", () => {
  if (!process.env.KOTLIN_ORIGINS) return; // run by hand, without the Kotlin side
  const kotlin = JSON.parse(process.env.KOTLIN_ORIGINS);
  const cases = JSON.parse(readFileSync(new URL("./origin-cases.json", import.meta.url), "utf8"));
  assert.ok(cases.length >= 40);
  for (const url of cases) assert.equal(P.origin(url), kotlin[url], JSON.stringify(url));
});

test("origins match Origin.kt (OriginTest's cases)", () => {
  const cases = {
    "http://192.0.2.10:8123/lovelace/0": "http://192.0.2.10:8123",
    "HTTP://HomeAssistant.Local:8123": "http://homeassistant.local:8123",
    "http://homeassistant.local./": "http://homeassistant.local",
    "http://192.0.2.10:80/": "http://192.0.2.10",
    "https://192.0.2.10:443/x": "https://192.0.2.10",
    "ws://192.0.2.10:8123/api/websocket": "http://192.0.2.10:8123",
    "wss://homeassistant.local/api/websocket": "https://homeassistant.local",
    "blob:http://192.0.2.10:8123/1f2e-33": "http://192.0.2.10:8123",
    "http://user:pw@192.0.2.10:8123/": "http://192.0.2.10:8123",
    "http://[2001:db8::1]:8123/": "http://[2001:db8::1]:8123",
    "http://192.0.2.10:8123?x": "http://192.0.2.10:8123",
    "http://192.0.2.10:8123#x": "http://192.0.2.10:8123",
    "http://192.0.2.10:8123@example.com/": "http://example.com",
    "http://ält.example.org:8123/lovelace": "http://xn--lt-uia.example.org:8123",
    "http://xn--lt-uia.example.org:8123/api/websocket": "http://xn--lt-uia.example.org:8123",
    "https://ÄLT.example.org/": "https://xn--lt-uia.example.org",
    "http://xn--fa-hia.example.org:8123/": "http://xn--fa-hia.example.org:8123",
  };
  for (const [url, origin] of Object.entries(cases)) assert.equal(P.origin(url), origin, url);
  for (const none of [null, undefined, "", "about:blank", "data:text/html,x", "moz-extension://abc/x", "javascript:x",
    "file:///x", "ftp://192.0.2.10/", "http://", "http://:8123/", "http://192.0.2.10:0/", "http://192.0.2.10:99999/",
    "http://192.0.2.10:12a/", "http://[2001:db8::1/", "blob:about:blank",
    "http://example.com\\@192.0.2.10:8123/", "http://192.0.2.10:8123\\@example.com/", "http://exa\tmple.com@192.0.2.10:8123/",
    "http://exa\nmple.com@192.0.2.10:8123/", "http://exa mple.com@192.0.2.10:8123/", "http://exa\u0001mple.com@192.0.2.10:8123/",
    "http://192.0.2.10:+80/", "http://192.0.2.10:-1/", "http://192.0.2.10: 80/", "http://[2001:db8::1]8123/",
    "http://faß.example.org:8123/", "http://ς.example.org/", "http://a\u200db.example.org/", "http://a\u200cb.example.org/",
    "http://fa%C3%9F.example.org:8123/", "http://%78n--fa-hia.example.org/", "http://[fe80::1%25eth0]:8123/"]) {
    assert.equal(P.origin(none), null, String(none));
  }
});

const req = (url, type, extra = {}) => ({ url, type, documentUrl: `${HA}/lovelace/0`, frameAncestors: [], ...extra });

test("Home Assistant's own requests load", () => {
  for (const [url, type] of [
    [`${HA}/frontend_latest/app.js`, "script"],
    [`${HA}/api/websocket`.replace("http", "ws"), "websocket"],
    [`${HA}/api/camera_proxy/camera.door?token=t`, "image"],
    [`${HA}/local/card.html`, "sub_frame"],
    [`${HA}/api/states`, "xmlhttprequest"],
  ]) {
    assert.deepEqual(decide(req(url, type), HA), { allow: true }, url);
    assert.deepEqual(decide(req(url, type), null), { allow: true }, `${url}, before the app says the origin`);
  }
});

test("map tiles, web cards and other origins are blocked", () => {
  assert.deepEqual(decide(req("https://a.tile.openstreetmap.org/3/4/2.png", "image"), HA),
    { allow: false, host: "a.tile.openstreetmap.org", frame: false });
  assert.deepEqual(decide(req("https://www.youtube.com/embed/x", "sub_frame"), HA),
    { allow: false, host: "www.youtube.com", frame: true });
  assert.deepEqual(decide(req("https://brands.home-assistant.io/hue/icon.png", "image"), HA),
    { allow: false, host: "brands.home-assistant.io", frame: false });
  assert.deepEqual(decide(req("wss://example.com/socket", "websocket"), HA),
    { allow: false, host: "example.com", frame: false });
  assert.equal(P.decide(req("https://192.0.2.10:8123/x", "script"), HA).allow, false, "another scheme is another origin");
  assert.equal(P.decide(req("http://192.0.2.10:8124/x", "script"), HA).allow, false, "another port is another origin");
});

test("Home Assistant's address on https stays blocked, and the note names its scheme (#455)", () => {
  // What GeckoView 144 asked for before the lockdown turned off its display upgrade: HA's http image as https.
  // Still another origin: only the pref keeps it from happening, and the lockdown fails closed if it ever does.
  for (const type of ["image", "media", "script", "xmlhttprequest", "sub_frame", "main_frame"]) {
    const v = P.decide(req("https://192.0.2.10:8123/api/image_proxy/image.door?token=t", type), HA);
    assert.equal(v.allow, false, type);
    assert.equal(v.host, "https://192.0.2.10:8123", `${type}: named with its scheme, not as HA's own address`);
  }
  assert.deepEqual(decide(req("wss://192.0.2.10:8123/api/websocket", "websocket"), HA),
    { allow: false, host: "https://192.0.2.10:8123", frame: false });
  for (const [url, host] of [
    ["https://192.0.2.10:8124/api/image_proxy/image.door", "192.0.2.10:8124"],
    ["http://192.0.2.10:8124/api/image_proxy/image.door", "192.0.2.10:8124"],
    ["https://192.0.2.11:8123/api/image_proxy/image.door", "192.0.2.11:8123"],
    ["https://192.0.2.10/api/image_proxy/image.door", "192.0.2.10"],
    ["https://homeassistant.local:8123/x.png", "homeassistant.local:8123"],
  ]) {
    assert.deepEqual(decide(req(url, "image"), HA), { allow: false, host, frame: false }, url);
  }
  const before = P.decide({ url: "https://192.0.2.10:8123/x.png", type: "image", documentUrl: `${HA}/lovelace/0` }, null);
  assert.deepEqual({ ...before }, { allow: false, host: "https://192.0.2.10:8123", frame: false }, "the document's origin stands in");
});

test("the app's Unicode dashboard URL matches Gecko's punycode requests", () => {
  const ha = P.origin("http://ält.example.org:8123/");
  assert.equal(P.decide(req("http://xn--lt-uia.example.org:8123/frontend_latest/app.js", "script"), ha).allow, true);
});

test("a dashboard URL whose host Gecko would write differently allows nothing (fails closed)", () => {
  // Gecko writes each of these hosts as xn--fa-hia.example.org; neither parser stores an origin for them. The app's
  // navigation delegate then refuses the page itself (LockdownPolicyTest), so no document of that host exists to
  // stand in, and a request in Gecko's spelling, or IDNA 2003's, is refused.
  for (const configured of ["http://faß.example.org:8123/", "http://fa%C3%9F.example.org:8123/"]) {
    const ha = P.origin(configured);
    assert.equal(ha, null, configured);
    for (const url of ["http://xn--fa-hia.example.org:8123/x.js", "http://fass.example.org:8123/x.js"]) {
      assert.equal(P.decide({ url, type: "script" }, ha).allow, false, `${configured} → ${url}`);
    }
  }
});

test("the app's origin wins over the document's", () => {
  const fromElsewhere = req("https://example.com/x.js", "script", { documentUrl: "https://example.com/" });
  assert.equal(P.decide(fromElsewhere, HA).allow, false);
});

test("without the app's origin the top-level document's stands in", () => {
  const inFrame = req("https://example.com/x.png", "image", {
    documentUrl: "https://example.com/frame.html",
    frameAncestors: [{ url: "https://example.com/frame.html", frameId: 2 }, { url: `${HA}/lovelace/0`, frameId: 0 }],
  });
  assert.equal(P.topOrigin(inFrame), HA);
  assert.equal(P.decide(inFrame, null).allow, false);
  const worker = { url: `${HA}/static/x.js`, type: "script", originUrl: `${HA}/sw-modern.js` };
  assert.equal(P.decide(worker, null).allow, true, "a service worker's own fetch, by its originUrl");
  assert.equal(P.decide({ url: "https://example.com/", type: "xmlhttprequest" }, null).allow, false, "no origin at all: blocked");
});

test("a top-level load must be Home Assistant's once the app has said which: redirects, forms, meta refresh", () => {
  assert.deepEqual(decide(req("https://example.com/", "main_frame"), HA), { allow: false, host: "example.com", frame: false });
  assert.deepEqual(decide(req(`${HA}/lovelace/0`, "main_frame"), HA), { allow: true });
  assert.deepEqual(decide(req("https://example.com/", "main_frame"), null), { allow: true }, "the app's first load, before it says");
});

test("the app's loopback server (Vitro's islands): its pages load, each page held to its own origin", () => {
  const LOCAL = "http://127.0.0.1:39123";
  const island = (url, type, extra = {}) => ({ url, type, documentUrl: `${LOCAL}/__vitro/island/s0123`, frameAncestors: [], ...extra });
  // Top-level: Home Assistant's pages and the island page, nothing else on loopback.
  assert.deepEqual(decide(req(`${LOCAL}/__vitro/island/s0123`, "main_frame"), HA, LOCAL), { allow: true });
  assert.deepEqual(decide(req(`${HA}/history`, "main_frame"), HA, LOCAL), { allow: true });
  assert.deepEqual(decide(req("http://127.0.0.1:39124/", "main_frame"), HA, LOCAL), { allow: false, host: "127.0.0.1:39124", frame: false });
  assert.equal(P.decide(req(`${LOCAL}/`, "main_frame"), HA, null).allow, false, "no local origin: loopback is another origin");
  // The island page fetches its cards, its bridge and its icons from the server, and nothing from anywhere else.
  for (const [url, type] of [[`${LOCAL}/local/card.js`, "script"], [`ws://127.0.0.1:39123/__vitro/ws/s0123`, "websocket"],
    [`${LOCAL}/__vitro/icon/mdi:lamp`, "xmlhttprequest"], [`${LOCAL}/api/camera_proxy/camera.door`, "image"]]) {
    assert.deepEqual(decide(island(url, type), HA, LOCAL), { allow: true }, url);
  }
  assert.deepEqual(decide(island(`${HA}/api/states`, "xmlhttprequest"), HA, LOCAL), { allow: false, host: "192.0.2.10:8123", frame: false },
    "the island page never reaches Home Assistant past the server");
  assert.equal(P.decide(island("https://example.com/x.js", "script"), HA, LOCAL).allow, false);
  // Home Assistant's page never reaches the loopback server, nor frames it.
  assert.deepEqual(decide(req(`${LOCAL}/api/states`, "xmlhttprequest"), HA, LOCAL), { allow: false, host: "127.0.0.1:39123", frame: false });
  assert.deepEqual(decide(req(`${LOCAL}/__vitro/island/s0123`, "sub_frame"), HA, LOCAL), { allow: false, host: "127.0.0.1:39123", frame: true });
  assert.deepEqual(decide(req(`ws://127.0.0.1:39123/__vitro/ws/s0123`, "websocket"), HA, LOCAL), { allow: false, host: "127.0.0.1:39123", frame: false });
  // HA's own page, its WebSocket included, is as before.
  assert.deepEqual(decide(req(`${HA}/api/websocket`.replace("http", "ws"), "websocket"), HA, LOCAL), { allow: true });
  // A frame in the island page, and a request without a document, keep to their own rules.
  assert.equal(P.decide(island(`${LOCAL}/local/frame.html`, "sub_frame"), HA, LOCAL).allow, true);
  assert.equal(P.decide({ url: `${LOCAL}/local/x.js`, type: "script" }, HA, LOCAL).allow, false, "no document: Home Assistant's only");
  // Before the app names Home Assistant, the local origin opens nothing of its own.
  assert.equal(P.decide({ url: `${LOCAL}/local/x.js`, type: "script", documentUrl: `${HA}/` }, null, LOCAL).allow, false);
});

test("data:, moz-extension: and about: pass; a network URL that doesn't parse is refused", () => {
  assert.equal(P.decide(req("data:image/png;base64,AA==", "image"), HA).allow, true);
  assert.equal(P.decide(req("moz-extension://4b1d/blocked.html", "sub_frame"), HA).allow, true);
  assert.deepEqual(decide(req("http://example.com\\@192.0.2.10:8123/x.png", "image"), HA), { allow: false, host: "", frame: false });
  assert.deepEqual(decide(req("http://192.0.2.10:+8123/", "sub_frame"), HA), { allow: false, host: "", frame: true });
  assert.deepEqual(decide(req("http://192.0.2.10:8123\\@example.com/", "main_frame"), HA), { allow: false, host: "", frame: false });
});
