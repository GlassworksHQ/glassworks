/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// What a page may fetch on the legacy engine (#198): only the panel's Home Assistant. The same origin rules
// as Origin.kt. Pure functions, run by background.js and by policy.test.mjs under node.
"use strict";

var LockdownPolicy = (function () {
  const DEFAULT_PORT = { http: 80, https: 443 };
  const AS_HTTP = { ws: "http", wss: "https" };
  const FORM = /^([a-zA-Z][a-zA-Z0-9+.-]*):\/\/([^/?#]*)/;

  /** scheme://host[:port], lower case, default port dropped, ws(s) as http(s), blob: by its inner URL; else null. */
  function origin(url) {
    if (typeof url !== "string") return null;
    let u = url.trim();
    if (/^blob:/i.test(u)) return origin(u.slice(5));
    const m = FORM.exec(u);
    if (!m) return null;
    const raw = m[1].toLowerCase();
    const scheme = AS_HTTP[raw] || raw;
    if (!(scheme in DEFAULT_PORT)) return null;
    const authority = m[2].slice(m[2].lastIndexOf("@") + 1);
    let host, port;
    if (authority.startsWith("[")) {
      const end = authority.indexOf("]");
      if (end < 0) return null;
      host = authority.slice(0, end + 1);
      port = authority.slice(end + 1).replace(/^:/, "");
    } else {
      const colon = authority.indexOf(":");
      host = colon < 0 ? authority : authority.slice(0, colon);
      port = colon < 0 ? "" : authority.slice(colon + 1);
    }
    if (!host) return null;
    let p = DEFAULT_PORT[scheme];
    if (port !== "") {
      if (!/^\d+$/.test(port)) return null;
      p = Number(port);
      if (p < 1 || p > 65535) return null;
    }
    host = host.toLowerCase().replace(/\.+$/, "");
    return p === DEFAULT_PORT[scheme] ? `${scheme}://${host}` : `${scheme}://${host}:${p}`;
  }

  /** The origin of the top-level document a webRequest came from, or null. */
  function topOrigin(details) {
    const anc = details.frameAncestors;
    if (Array.isArray(anc) && anc.length > 0) return origin(anc[anc.length - 1].url);
    return origin(details.documentUrl) || origin(details.originUrl);
  }

  /**
   * The verdict on one webRequest: { allow: true } or { allow: false, host, frame }. [haOrigin] is the app's
   * (null until it says): without it, the top-level document's origin stands in, which the app's navigation
   * delegate has already held to Home Assistant. A top-level load is the app's to decide, so it passes here.
   */
  function decide(details, haOrigin) {
    if (details.type === "main_frame") return { allow: true };
    const target = origin(details.url);
    if (target === null) return { allow: true }; // not a network load: data:, blob: of nothing, moz-extension:
    const allowed = haOrigin || topOrigin(details);
    if (allowed !== null && target === allowed) return { allow: true };
    return { allow: false, host: target.slice(target.indexOf("://") + 3), frame: details.type === "sub_frame" };
  }

  return { origin, topOrigin, decide };
})();
