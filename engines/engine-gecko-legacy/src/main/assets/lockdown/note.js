/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// The lockdown's in-page note (#198): when the legacy engine blocks something, a bar at the bottom of the
// page says what and why, for 12 seconds. Text only (textContent), in a closed shadow root so the page's
// styles can't reach it.
"use strict";

(function () {
  const SHOW_MS = 12000;
  const hosts = [];
  let bar = null;
  let title = null;
  let hide = null;

  function headline(kind, host) {
    if (kind === "download") return "Downloads are off on this panel.";
    if (kind === "page" || kind === "window" || kind === "main_frame") {
      return host ? `${host} isn't your Home Assistant's address, so it didn't open.` : "That isn't your Home Assistant's address, so it didn't open.";
    }
    if (hosts.length > 1) return `Blocked content from ${hosts.slice(-3).join(", ")}.`;
    return host ? `Blocked content from ${host}.` : "Blocked content.";
  }

  function show(kind, host) {
    if (host && !hosts.includes(host)) hosts.push(host);
    if (!bar) {
      const holder = document.createElement("div");
      const root = holder.attachShadow({ mode: "closed" });
      const link = document.createElement("link");
      link.rel = "stylesheet";
      link.href = browser.runtime.getURL("note.css");
      bar = document.createElement("div");
      bar.className = "bar";
      bar.setAttribute("role", "status");
      title = document.createElement("strong");
      const why = document.createElement("span");
      why.textContent = "This panel's browser engine is frozen, so it only loads your Home Assistant.";
      bar.append(title, why);
      root.append(link, bar);
      (document.body || document.documentElement).append(holder);
    }
    title.textContent = headline(kind, host);
    bar.classList.add("on");
    clearTimeout(hide);
    hide = setTimeout(() => bar.classList.remove("on"), SHOW_MS);
  }

  const port = browser.runtime.connect({ name: "note" });
  port.onMessage.addListener((m) => {
    if (m && m.type === "blocked") show(String(m.kind || ""), String(m.host || ""));
  });
})();
