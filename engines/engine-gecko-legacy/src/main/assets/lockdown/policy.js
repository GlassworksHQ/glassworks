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
  // A browser reads "\\" as "/" and drops tabs and newlines: such an authority may name another host. Not parsed.
  const BAD_AUTHORITY = /[\\\s\x00-\x1f\x7f]/;
  // Schemes with no network origin that a page may use: they fetch nothing from anywhere.
  const LOCAL = /^(data|moz-extension|about):/i;

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
    if (BAD_AUTHORITY.test(m[2])) return null;
    const authority = m[2].slice(m[2].lastIndexOf("@") + 1);
    let host, port;
    if (authority.startsWith("[")) {
      const end = authority.indexOf("]");
      if (end < 0) return null;
      const rest = authority.slice(end + 1);
      if (rest !== "" && !rest.startsWith(":")) return null;
      host = authority.slice(0, end + 1);
      port = rest.slice(1);
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
    // IDNA deviation characters: Android's IDN and Gecko map them to different hosts (Origin.kt). Refused in both.
    // A percent escape in the host too: Gecko decodes it (fa%C3%9F is faß) before writing the host.
    if (/[\u00df\u03c2\u200c\u200d%]/.test(host)) return null;
    if (/[^\x00-\x7f]/.test(host)) {
      // Punycode, as Origin.kt and Gecko write it. Without a URL parser to do it, refuse (fails closed).
      if (typeof URL !== "function") return null;
      try { host = new URL(`http://${host}/`).hostname; } catch (e) { return null; }
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

  const hostOf = (o) => (o === null ? "" : o.slice(o.indexOf("://") + 3));
  /** How a note names [target]: its host, or the whole origin when that host is [allowed]'s on another scheme (#455). */
  const label = (target, allowed) => (allowed && hostOf(target) === hostOf(allowed) ? target : hostOf(target));

  /**
   * The verdict on one webRequest: { allow: true } or { allow: false, host, frame }. [haOrigin] is the app's
   * (null until it says). A top-level load (a link, a redirect, a form, a meta refresh) must be Home Assistant's
   * once the app has said which that is; before that, the app's navigation delegate holds it alone. Anything else
   * must be Home Assistant's too: without [haOrigin], the top-level document's origin stands in. A network URL that
   * doesn't parse is refused (fails closed); only data:, moz-extension: and about: pass without an origin.
   *
   * [localOrigin] is the app's own loopback server (Vitro's islands, http://127.0.0.1:<port>), which the app names
   * with Home Assistant's. A top-level load may go there too, and a page there fetches only from there: Home
   * Assistant's page never reaches the loopback server, and the loopback page never reaches Home Assistant.
   */
  function decide(details, haOrigin, localOrigin) {
    const target = origin(details.url);
    const frame = details.type === "sub_frame";
    if (target === null) return LOCAL.test(String(details.url).trim()) ? { allow: true } : { allow: false, host: "", frame };
    const local = haOrigin && localOrigin && localOrigin !== haOrigin ? localOrigin : null;
    if (details.type === "main_frame") {
      if (haOrigin === null || haOrigin === undefined || target === haOrigin || target === local) return { allow: true };
      return { allow: false, host: label(target, haOrigin), frame: false };
    }
    const top = topOrigin(details);
    const allowed = local !== null && top === local ? local : haOrigin || top;
    if (allowed !== null && target === allowed) return { allow: true };
    return { allow: false, host: label(target, allowed), frame };
  }

  return { origin, topOrigin, decide };
})();
