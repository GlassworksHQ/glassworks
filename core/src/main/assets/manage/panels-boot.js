/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// All panels' way out when its script can't start (CodeRabbit on #488). If panels.js or what it imports doesn't load, the
// module never runs and the page would stay on "Finding your panels…" for good. A classic script, served from this panel
// like the module (the page's CSP allows nothing else), so it also runs in a browser without modules.
(function () {
  var root = document.getElementById('gw-panels');
  setTimeout(function () {
    // panels.js marks the page as soon as it runs, imports loaded.
    if (!root || root.getAttribute('data-started')) return;
    var note = document.querySelector('#gw-list .note span');
    if (note) note.textContent = "This page couldn't start. Reload it to try again.";
  }, 8000);
})();
