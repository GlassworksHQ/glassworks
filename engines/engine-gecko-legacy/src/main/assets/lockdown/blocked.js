/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// blocked.html, in place of a frame the lockdown blocked (#198): names the host from the fragment.
"use strict";
try {
  const host = decodeURIComponent(location.hash.slice(1));
  if (host) document.getElementById("what").textContent = `Blocked content from ${host}.`;
} catch (e) { /* a bad fragment: the generic text stays */ }
