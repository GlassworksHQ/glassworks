/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// The lockdown's background script (#198). It blocks every request a page makes outside the panel's Home
// Assistant (policy.js) or, for the app's own loopback pages, outside that server, hears the allowed origin and the app's own refusals from the app (Lockdown.kt), and
// passes each refusal to the page's note (note.js). A blocked frame shows blocked.html in its place.
"use strict";

let haOrigin = null;
/** The app's own loopback server (Vitro's islands), named with haOrigin; its pages are held to it. */
let localOrigin = null;
const notes = new Set();
let app = null;
/** The current page's refusals, for a note that connects after them (note.js runs at document_idle). */
let recent = [];

function note(kind, host) {
  recent.push({ kind, host });
  if (recent.length > 20) recent.shift();
  for (const p of notes) {
    try { p.postMessage({ type: "blocked", kind, host }); } catch (e) { notes.delete(p); }
  }
}

function connectApp() {
  try {
    app = browser.runtime.connectNative("glassworks_lockdown");
  } catch (e) {
    console.error("lockdown: no app port", e);
    return;
  }
  app.onMessage.addListener((m) => {
    if (!m || typeof m !== "object") return;
    if (m.type === "origin" && typeof m.origin === "string") {
      haOrigin = m.origin;
      localOrigin = typeof m.local === "string" ? m.local : null;
    }
    else if (m.type === "blocked") note(String(m.kind || ""), String(m.host || ""));
  });
  app.onDisconnect.addListener(() => {
    app = null;
    setTimeout(connectApp, 1000);
  });
}

browser.runtime.onConnect.addListener((p) => {
  notes.add(p);
  for (const r of recent) p.postMessage({ type: "blocked", kind: r.kind, host: r.host });
  p.onDisconnect.addListener(() => notes.delete(p));
});

browser.webRequest.onBeforeRequest.addListener(
  (details) => {
    if (details.type === "main_frame") recent = []; // a new page: the last one's refusals aren't its own
    const v = LockdownPolicy.decide(details, haOrigin, localOrigin);
    if (v.allow) return {};
    console.warn(`lockdown: blocked ${details.type} from ${v.host}`);
    note(details.type, v.host);
    if (v.frame) return { redirectUrl: browser.runtime.getURL("blocked.html") + "#" + encodeURIComponent(v.host) };
    return { cancel: true };
  },
  { urls: ["http://*/*", "https://*/*", "ws://*/*", "wss://*/*"] },
  ["blocking"],
);

connectApp();
