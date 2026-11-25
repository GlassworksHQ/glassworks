---
topic: display
evidence: verified
---

# Vitro: why did Better Thermostat's card draw blank islands on the tablet?

**Status (2026-11-25): explained and fixed in the island bridge; cards that draw with HA's own elements are found by
the island itself.** The tablet's dashboard named a card type the installed script doesn't define, and the island
bridge hid that behind an empty page. A second, general bug kept any card that skips renders by comparing states from
ever drawing once it had rendered before the bridge's first snapshot. Both are fixed. Better Thermostat's large climate
card still can't draw in an island, because its dial is part of HA's frontend. A first fix named that card in a list;
the island page now notices any card that draws with HA elements it lacks and sends that card's view to HA's frontend
(see [From one card to every card](#from-one-card-to-every-card-the-islands-own-check)).

Device: none (the tablet's run is the trigger, see below). Tools: the parity harness's fake HA (`tools/perf/fakeha.py`
with `tools/vitro-parity/fakehome.py`), HA frontend 20261028.5, headless Chromium 141 through Playwright,
`better-thermostat-ui-card` 3.4.0 (AGPL-3.0, pinned in `tools/vitro-parity/cards.json`, cache only).

## Question

On the Pixel Tablet's second Vitro run (exp/vitro at 97536aca3), every `custom:better-thermostat-ui-card` island drew a
blank white box about 100 px tall. The module loaded ("Better Thermostat UI - 3.4.0" in the log), the bridge connected,
and nothing threw. The run couldn't compare with HA, because its user isn't an admin and HA refused the admin-only test
dashboard. Is the card one an island can't run, or is the bridge missing something every card needs?

## How we got here

Without a panel, the question had to be answered against the fake HA, where both renders can be compared: HA's own
frontend with the card's script, and an island page as `WebIslandCardView.buildIslandHtml` writes it.

The suspects were a missing piece of `hass`, a missing HA element (cards often lean on elements HA's frontend defines
lazily), a font, or sizing. The first look at the bundle pointed at missing elements: it renders
`ha-control-circular-slider`, `ha-big-number`, `ha-svg-icon` and `hui-card-features`, none of which an island page has.
But HA's own frontend drew a "Configuration error" card for the same config. That made the config itself the first
suspect, and it was: the 3.x script defines `better-thermostat-mini-climate-card` and
`better-thermostat-normal-climate-card`, and no `better-thermostat-ui-card` at all. That name is the card's old type.

That explained an empty island, but not why HA showed an error and Vitro showed nothing. Reading the island's `boot()`
answered it: it waited up to 4 s for the element, then created it anyway, so an undefined element became an empty
page. Running the card under its real name in the island page then showed the second bug: the card rendered an empty
template once and never again, although `hass.states` held its entity. The card's `shouldUpdate` compares
`oldHass.states[id]` with `hass.states[id]`, as HA's own cards do, and the island handed every `hass` the same proxy
over one live map, so the old and new states were always the same object.

## What we tried

1. **The card in HA's frontend and in an island-like page** (2026-11-25, verified). A Playwright probe on the fake HA,
   with the card's module added to HA's page before the dashboard and to a page carrying the island's own stand-ins.
   ```
   HA's frontend, type custom:better-thermostat-ui-card: "Configuration error" card
   window.customCards: better-thermostat-mini-climate-card, better-thermostat-normal-climate-card (and the pinned cards)
   customElements.get('better-thermostat-ui-card'): undefined
   ```
   The 3.3.0 release defines the same two types and no `better-thermostat-ui-card` (read from its release asset).

2. **What HA does with an undefined custom element** (read, frontend 20261028.8,
   `src/panels/lovelace/create-element/create-element-base.ts`, `_customCreate`): it creates its error card with
   "Custom element doesn't exist: <tag>.", hidden for 2 s in case the element gets defined, and a `setConfig` that throws
   becomes the same error card with the error's message.

3. **The real island page in Chromium** (2026-11-25, verified). `buildIslandHtml`'s output, written by a throwaway
   Robolectric test, served on the fake HA's origin, with the island's bridge socket answered by Playwright's
   `routeWebSocket` (a snapshot holding `climate.study`).
   ```
   old type, before the fix:   mount 0 px, nothing on the bridge, no errors
   old type, after the fix:    bridge <- {"type":"cardError","message":"Custom element doesn't exist: better-thermostat-ui-card."}
   normal type, before:        shadow root "<!---->", card._stateObj unset, no errors
   normal type, after states fix: 410 px, ha-control-circular-slider and ha-big-number undefined
   mini type, after states fix:   "o.bind is not a function" (it binds HA's action-handler)
   mini type, after the action-handler stand-in: 108 px, no errors; a tap sends call_service homeassistant.toggle
   ```

4. **Which HA elements a card draws with** (2026-11-25, verified), now a tool:
   ```
   $ node tools/vitro-parity/island-check.mjs <case.json> --card better-thermostat-ui-card
   normal-climate-card: HA's frontend draws it 492×424 px with ha-big-number, ha-card, ha-control-circular-slider,
     ha-icon, ha-icon-button, ha-svg-icon, mushroom-button, mushroom-button-group
     An island lacks: ha-big-number, ha-control-circular-slider
   mini-climate-card: 492×110 px, An island has every element it uses.
   ui-card: isn't defined by better-thermostat-ui-card 3.4.0 (HA shows its error card)
   ```

Ruled out: fonts and sizing (the card's box sizes itself once it renders, and the island reports its height), and
missing `hass` fields (with `unit_system` from HA's config the card reads everything it needs).

## Result

| Cause | Fix |
|---|---|
| The dashboard names `custom:better-thermostat-ui-card`, which the 3.x script doesn't define. HA shows its error card; the island showed an empty page | The island reports `cardError` when its element isn't defined after its wait or `setConfig` throws, and the card becomes HA's error card natively (`WebIslandCardView`, `IslandCardError`) |
| Every `hass` shared one `states` proxy, so cards that compare old and new states never redrew, and one that rendered before the first snapshot stayed blank | A new `states` object (a proxy over a copy) after every snapshot or patch, as HA hands cards; unchanged entities keep their objects |
| Mushroom-style cards bind HA's `action-handler`, which the island lacked (a render error, and no taps) | An `action-handler` stand-in with HA's tap, hold (500 ms) and double tap (250 ms), and an `ha-svg-icon` one |
| `better-thermostat-normal-climate-card` draws its dial with HA's `ha-control-circular-slider` and `ha-big-number`, which only HA's frontend has | Its view opens in HA's frontend. First through a list of one card (`WebFallback.FRONTEND_CUSTOM_CARDS`), now through the island's own check (`needsFrontend`, below), and the list is gone |

The tablet's dashboard should use `custom:better-thermostat-normal-climate-card` (or the mini card). With the old name,
HA's frontend and Vitro now both show the error card.

## How to reproduce

Both sides run against the fake HA on 127.0.0.1 and a throwaway headless Chromium (`CHROME=<path>` picks the browser,
as for `capture.mjs`). Neither touches a real HA or a device, and neither writes anything but build output (and a PNG
with `--shot`). The first run downloads HA's pinned frontend and the card into `~/.cache/glassworks-perf`. The three
case files in `tools/vitro-parity/island-cases/` hold one made-up climate entity under each card type.

1. **HA's side:** which HA elements the card draws with that an island lacks.
   ```
   $ node tools/vitro-parity/island-check.mjs tools/vitro-parity/island-cases/better-thermostat-normal-climate.json --card better-thermostat-ui-card
   HA's frontend draws custom:better-thermostat-normal-climate-card (better-thermostat-ui-card 3.4.0) 492×424 px with: ha-big-number, ha-card, ha-control-circular-slider, ha-icon, ha-icon-button, ha-svg-icon, mushroom-button, mushroom-button-group
   An island lacks: ha-big-number, ha-control-circular-slider
   $ node tools/vitro-parity/island-check.mjs tools/vitro-parity/island-cases/better-thermostat-ui.json --card better-thermostat-ui-card
   HA's frontend: custom:better-thermostat-ui-card isn't defined by better-thermostat-ui-card 3.4.0 (HA shows its error card)
   ```
   It exits 1 when HA doesn't draw the card (undefined, or registered with no instance on the page).

2. **The island's side:** the page Vitro would serve, written by an opt-in Robolectric test, then run with the bridge
   socket answered by the probe (a snapshot of the fixture's states, empty registries, every `callWS` refused).
   ```
   $ ./gradlew :engine-vitro:testDebugUnitTest --tests '*IslandPageDumpTest*' --max-workers=2 \
       -PvitroIslandCase=$PWD/tools/vitro-parity/island-cases/better-thermostat-mini-climate.json
   $ node tools/vitro-parity/island-probe.mjs tools/vitro-parity/island-cases/better-thermostat-mini-climate.json --click 60,30
   island custom:better-thermostat-mini-climate-card: {"instance":true,"defined":true,"height":108,…,"undefined":[]}
   bridge: [{"type":"resize","height":108}]
   tap at 60,30 sent: [{"type":"callWs","id":1,"msg":{"type":"call_service","domain":"homeassistant","service":"toggle",…}}]
   ```
   With the other two cases (dump each first): `better-thermostat-ui.json` gives `"instance":false` and
   `bridge: [{"type":"cardError","message":"Custom element doesn't exist: better-thermostat-ui-card."}]`;
   `better-thermostat-normal-climate.json` gives a 410 px card with `"undefined":["ha-big-number","ha-control-circular-slider"]`.
   The test is skipped without `-PvitroIslandCase`, so CI's runs pass over it. Cleanup: `rm -r engines/engine-vitro/build/vitro-island`.

### Before the fix

`tools/vitro-parity/island-before.sh` builds the island page as an older commit served it, in a throwaway worktree of
HEAD with that commit's `WebIslandCardView.kt` (the engine's other unit tests are dropped there, since they test
today's page), and runs the probe on it. `97536aca3` is the exp/vitro the tablet ran. `--snapshot-delay` holds the
bridge's first snapshot back, so the card draws once with no states; `--eval` reads the card element in the page.

```
$ tools/vitro-parity/island-before.sh 97536aca3 tools/vitro-parity/island-cases/better-thermostat-ui.json
== better-thermostat-ui.json as 97536aca3 served it
island custom:better-thermostat-ui-card: {"instance":true,"defined":false,"height":0,"elements":[],"undefined":[]}
bridge: []
$ tools/vitro-parity/island-before.sh 97536aca3 tools/vitro-parity/island-cases/better-thermostat-normal-climate.json \
    -- --snapshot-delay 3000 --eval 'c => c && ({ shadow: c.shadowRoot.innerHTML.slice(0, 40), stateObj: c._stateObj === undefined ? "unset" : "set" })'
== better-thermostat-normal-climate.json as 97536aca3 served it
island custom:better-thermostat-normal-climate-card: {"instance":true,"defined":true,"height":410,…,"undefined":["ha-big-number","ha-control-circular-slider","ha-svg-icon"]}
bridge: [{"type":"resize","height":410}]
eval: {"shadow":"<!---->\n      <ha-card style=\"\">\n       ","stateObj":"set"}
```

The first result is the one the tablet showed: an undefined element mounted at 0 px, nothing on the bridge (**verified**
2026-11-25). The second did **not** reproduce the empty normal card of step 3: with the snapshot held back 3 s, the
pre-fix page draws it at 410 px with `_stateObj` set, the same as today's (whose probe differs only in also defining
`ha-svg-icon`). Step 3's page was a throwaway Robolectric dump whose exact timing wasn't kept, so that observation stands
as recorded then (**untested** now); the per-change `states` object is what HA's frontend does either way. The script
removes its worktree and build when it ends.

## From one card to every card: the island's own check

**The problem with a list.** Naming Better Thermostat's card in `WebFallback` fixed the tablet, but only for that
card and that release. Which custom cards a panel meets follows what the community installs, not our dashboards
([vitro-island-census.md](vitro-island-census.md)), and any card can start leaning on one of HA's elements in its next
release. The island page is the one place that sees what a card really draws, so the check moved there.

**What counts as "HA's element".** We listed every custom element HA frontend 20261028.5 defines (read from its
`frontend_latest` chunks, 2026-11-25: 1,551 names; the `customElements.define` and Lit `customElement` calls):

```
$ cat hass_frontend/frontend_latest/*.js | grep -ohE '\(0,[a-zA-Z_$]+\.EM\)\("[a-z0-9]+(-[a-z0-9]+)+"\)|customElements\.define\("[a-z0-9]+(-[a-z0-9]+)+"' \
    | grep -oE '"[^"]+"' | tr -d '"' | sort -u | sed -E 's/^([a-z0-9]+)-.*/\1/' | sort | uniq -c | sort -rn | head
 704 ha    380 hui    118 dialog    30 more    29 wa    25 state    24 zha    23 cloud    18 supervisor ...
```

`ha-*` and `hui-*` are most of them. `state-*` (`state-badge`, `state-info`, `state-display`, `state-card-*`) and
`more-info-*` are the other names a card draws inline. `dialog-*`, `wa-*` (Web Awesome, inside HA's own elements),
the config panels' (`zha-*`, `cloud-*`, …) and `mwc-*` (four left, a ripple among them) are not what a card puts in
its own render. So the rule is a tag matching `ha-`, `hui-`, `state-` or `more-info-` that nothing on the page defines
(**read**, frontend 20261028.5).

**One tag is enough.** An undefined element draws nothing of its own, and the page can't tell a card's dial from its
decoration. Handing a view to HA's frontend costs a browser page, which an island already is, and HA draws every card
right; drawing a card without its control costs the user the card. The exceptions are tags, never cards, that leave
nothing a user misses when undefined: `ha-ripple` (the press ripple; the island also defines a stand-in) and
`hui-warning` (HA's "Entity not available" notice: its text is its own children, which an undefined element still
shows, and an entity that is missing for a moment shouldn't move a view to HA for the session).

**Not too early, not forever.** HA defines many elements lazily, and a card may define one of its own a moment after it
draws it, so a tag counts only when it's still in the tree and still undefined 2.5 s after it was first seen. A tag
that appears later (a config option, a state, a lazy render) is caught by a `MutationObserver` on every shadow root
the scan walked (and the card itself), on a 400 ms debounce, and by scans 1.5, 4, 8, 15 and 30 s in, because attaching a shadow root late (an element upgraded after its definition loads) notifies no observer. Each scan is one `querySelectorAll('*')` per shadow root, at most 5,000
elements, and the watch stops after 30 scans or once it reported. A card that defines its own `ha-`-named element
never counts, because that element is defined.

**Who gets blamed.** The report names the card each tag sits in: the nearest custom element with `setConfig` above it,
up to the island's own card. A stack holding a thermostat card blames the thermostat card, so another stack doesn't
leave Vitro.

**The native side** (`VitroView.cardNeedsFrontend`, `IslandFrontend`) remembers the card type for the session, keyed by
the dashboard's script URLs (HACS's `?hacstag=` changes with each release, so an update is looked at again). Every view
with that card goes to HA's frontend from then on, the one on screen at once, and their built trees are dropped. HA's
frontend reporting such a view is not handed back to Vitro, so a view never bounces between the two. The decision
isn't persisted: one island draw per card type and session finds it again, and a stored one would outlive the
frontend and stand-ins it was made with.

### What the check finds (2026-11-25, verified)

Each card on the fake HA in HA's frontend (`island-check.mjs`) and in the island page (`island-probe.mjs`), pinned in
`cards.json` (cache only, `on_demand`):

| Card | HA's frontend draws it with | Island | Verdict |
|---|---|---|---|
| Better Thermostat `normal-climate-card` 3.4.0 | `ha-big-number`, `ha-control-circular-slider`, `ha-icon`, `ha-icon-button`, `ha-svg-icon`, Mushroom's own | 410 px, `needsFrontend {"tags":["ha-big-number","ha-control-circular-slider"],"cards":["better-thermostat-normal-climate-card"]}` | Hands off |
| Better Thermostat `mini-climate-card` 3.4.0 | `ha-icon`, `ha-state-icon`, Mushroom's own | 108 px, nothing undefined | Stays an island |
| battery-state-card v4.3.0 | `ha-card`, `ha-icon`, `ha-relative-time`, its own `battery-state-entity` | 156 px, nothing undefined | Stays an island |
| calendar-card-pro v6.0.0 | `ha-card`, `ha-icon` | 57 px, nothing undefined | Stays an island |
| scheduler-card v4.0.20 | `ha-button`, `hui-warning` (the fake has no scheduler integration, so HA shows the card's "Could not connect" notice) | 0 px: the card throws on `hass.connection.subscribeMessage` and `hass.loadBackendTranslation`, which the island's `hass` lacks | Blank, and the check can't see it (nothing drew) |

```
$ node tools/vitro-parity/island-check.mjs tools/vitro-parity/island-cases/scheduler-card.json --card scheduler-card
HA's frontend draws custom:scheduler-card (scheduler-card v4.0.20) 492×217 px with: ha-button, ha-card, hui-warning
An island lacks: ha-button, hui-warning
$ node tools/vitro-parity/island-probe.mjs tools/vitro-parity/island-cases/scheduler-card.json
island custom:scheduler-card: {"instance":true,"defined":true,"height":0,"elements":[],"undefined":[]}
bridge: [{"type":"callWs","id":1,"msg":{"type":"scheduler"}}]
page errors: this.hass.connection.subscribeMessage is not a function | this.hass.loadBackendTranslation is not a function | …
```

The made-up cards in `island-cases/cards/test-cards.js` check the rule in Chromium
(`test/island-detect.e2e.test.mjs`, pass 2026-11-25): one that draws `ha-control-circular-slider` and `ha-big-number`
hands off; one that draws only the island's stand-ins, one that defines its own `ha-` element, one that defines its
element a second after drawing it, and one whose option is off all stay; the same card with the option on hands off.
The watch's logic also runs in CI against a fake DOM (`test/island-frontend.test.mjs`).

What it rules out: a list of cards. Of four popular island cards, two use nothing an island lacks, one would hand off
(and draws blank for another reason), and Better Thermostat's two cards from one script land on both sides.

## Open questions

- The island waits up to 4 s before it reports a missing element; HA shows its error card after 2 s. The longer wait
  covers resources that load over the bridge on a slow panel. Untested on a panel.
- The tablet's island memory numbers in its run (about 24 MiB per island) were taken with these blank islands; a card
  that draws will cost more.
- scheduler-card draws blank in an island because its `hass` has no `connection.subscribeMessage` or
  `loadBackendTranslation`. The frontend check only sees elements a card drew, so a card that throws before it draws
  anything stays a blank island. Whether such page errors should also hand the view to HA's frontend is open.
- scheduler-card's `ha-button` shows its label when undefined (its text is its own children) but loses its button look.
  A stand-in for `ha-button` would keep such cards in islands; none is written yet.
- The 2.5 s grace on the tablet: untested on a panel. A slower panel defines nothing faster, since nothing but the
  card's own script defines anything in an island.
