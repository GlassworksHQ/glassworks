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

**After #799 D3–D5 (2026-11-26, verified with the same probe; the probe now refuses subscriptions as it refuses
calls).** The island's `hass` has HA's whole `Connection` (`subscribeMessage`, `subscribeEvents`, `addEventListener`,
`haVersion`, …), `loadBackendTranslation`, `services` and a `localize` that returns "" on a miss, checked member by
member against HA's `src/types.ts` (`IslandHassContractTest`). scheduler-card now gets as far as HA's frontend does on
the fake (its "Could not connect" notice, 80 px), draws with `ha-button`, and its view hands off:

```
$ node tools/vitro-parity/island-probe.mjs tools/vitro-parity/island-cases/scheduler-card.json
island custom:scheduler-card: {"instance":true,"defined":true,"height":80,"elements":["ha-button","ha-card","hui-warning"],"undefined":["ha-button","hui-warning"]}
bridge: [{"type":"callWs","id":1,"msg":{"type":"scheduler"}},{"type":"subscribe","sid":1,"msg":{"type":"scheduler_updated"},"resubscribe":true},{"type":"callWs","id":2,"msg":{"type":"frontend/get_translations","language":"en","category":"services"}},{"type":"resize","height":80},{"type":"needsFrontend","tags":["ha-button"],"cards":["scheduler-card"]}]
```

For the next member an island lacks, a safety net: a card that throws a `TypeError` and has drawn nothing after 1.5 s
sends `cardBroken` with the member's name, and its view goes to HA's frontend as for `needsFrontend`. It's tested
under node against a fake page (`src/test/js/island-hass.test.mjs`), including Gecko's and Chromium's messages; on a
panel it's **untested**.

The made-up cards in `island-cases/cards/test-cards.js` check the rule in Chromium
(`test/island-detect.e2e.test.mjs`, pass 2026-11-25): one that draws `ha-control-circular-slider` and `ha-big-number`
hands off; one that draws only the island's stand-ins, one that defines its own `ha-` element, one that defines its
element a second after drawing it, and one whose option is off all stay; the same card with the option on hands off.
The watch's logic also runs in CI against a fake DOM (`test/island-frontend.test.mjs`).

What it rules out: a list of cards. Of four popular island cards, two use nothing an island lacks, one would hand off
(and draws blank for another reason), and Better Thermostat's two cards from one script land on both sides.

## Empty islands on the real-S1 run (2026-11-26)

A video run on an S1 (exp/vitro `4fb3b5e`, before #915) showed the Mushroom islands with "Entity not found" (media
player, fan, lock, entity cards, a climate card), mini-graph-card islands blank, Bubble Card islands fine, and white
arcs on the rounded ends of Bubble's slider. The dashboard came from a fake HA on a loopback origin through
`adb reverse`, while the panel stayed signed in to a real HA, so Vitro logged "Not sending the Home Assistant token to
a dashboard on another origin than the panel's" (every 30 s: the client asks for its token that often; now once).

### How we got here

The first guess was the test setup. The parity harness's fake home (`tools/vitro-parity/fakehome.py`) serves one
corpus case's fixture at a time, and its dashboard has one view per case, so every other case's entities really have
no state there; Bubble's slider uses `light.living_room_lamp`, which most fixtures share. That explains some of it but
not why a card whose state *is* there shows "Entity not found". So the cards ran in a real island page with their own
fixture (`island-probe.mjs`, Chromium, 2026-11-26, **verified**):

```
$ node tools/vitro-parity/island-probe.mjs /tmp/mushroom-fan.json --eval 'c => { try { c.render(); return "ok" } catch (e) { return e.stack } }'
eval: "TypeError: Cannot read properties of undefined (reading 'split')
    at ge (…/hacsfiles/lovelace-mushroom/mushroom.js?hacstag=v5.3.0:1:24368)
    at mg (…mushroom.js:816:29)
    at OA.render (…mushroom.js:2274:1471)"
```

`mg` is Mushroom 5.3's name helper: `ge(hass.config.version, 2026, 4) ? hass.formatEntityName(...) : friendly_name`.
The island's `hass.config` had no `version` (the page bakes in `get_config` as the panel has it when the page is
made: `{}` before the client's answer, and on a new client after a reconnect), so `ge` threw. With a version of 2026.4
or later, Mushroom calls `hass.formatEntityName`, which the island didn't have (#915 had listed it as a known gap), so
it threw there instead. Mushroom's first render came before the bridge's states (`if (!stateObj) renderNotFound()`,
which needs neither), so "Entity not found" was drawn, and every later render, now with the state, threw and left it
there. Bubble and mini-graph don't read either member. **This is not an artifact of the setup:** any island built
before the config arrived, or any Mushroom 5.3 island on HA 2026.4 or later, showed it.

mini-graph-card fetches its history over REST through the island proxy; with the token withheld for the other origin
(right), HA's answer is a 401 and the graph stays empty. That one is the setup: on a real panel the dashboard's origin
is the panel's (another origin never authenticates, and the panel goes to sign-in).

#915's safety net didn't catch it: the card wasn't blank (it had drawn "Entity not found"), and the net measured the
card's host, which for an inline custom element around a block `ha-card` is 0 px tall (a false positive the probe
showed too).

### What changed

- `hass.config` comes over the bridge with the states (`config`), and again when the panel's client reads it.
- The page makes the card only once it has the panel's states, and HA's config (up to 3 s), as HA's frontend draws a
  card once `hass` has them; while no states come, an honest notice ("Connection lost. Reconnecting…", HA's words)
  takes the card's place instead of a blank or a card drawn with none.
- `hass.formatEntityName` is HA's (`compute_entity_name_display.ts`), with the registries (`next_name_part` now
  reaches the island); the attribute formatters are HA's for numbers, units and translations (Mushroom's fan shows
  "20%", as HA, not "20").
- The safety net measures what the card drew (its shadow root's content), and a card whose every update fails on a
  missing member (3 in 10 s) goes to HA's frontend, drawn or not.

### The white arcs on Bubble's slider (**untested on a panel**)

Bubble's CSS has no white fallback on that path, the same card is clean in HA's own page on the same GeckoView, and a
transparent Chromium shot of the island composites with dark edges. What differs is that an island page is
transparent and HA's is opaque: every anti-aliased edge of an island is half transparent, and Android composites the
island's surface over Vitro's view. Edges that come out lighter than their color, most visible on Bubble's light slider
fill (`rgb(225,225,210)` at 0.7), fit alpha that isn't premultiplied the way the compositor expects. Gecko under
Playwright's Firefox wouldn't start in this environment, so this is reasoned, not seen. The change: an inline island on
a view of one opaque color paints that color itself (it has no half-transparent pixels left); on a picture or a
gradient it stays transparent. A panel run decides it.

### The check that would have caught it

`IslandEndToEndTest` runs the real path: the corpus fixtures' states and registries in Vitro's store, Vitro's island
server and bridge, and each island page drawn by headless Chromium with the pinned bundles from a fake HA on loopback
(`src/test/js/island-e2e.mjs`). Mushroom (entity, media, fan), mini-graph-card and Bubble Card must show their states
and HA's names, never "Entity not found" or a blank card; a guard case makes the page before the config, which arrives
over the bridge 1.5 s later. With the guard's config held back, it fails with the panel's error (`reading 'split'`,
drew nothing); with the fixes it passes in about 7 s. CI's `app` job runs it (`GW_VITRO_ISLAND_E2E=1`).

## Open questions

- The island waits up to 4 s before it reports a missing element; HA shows its error card after 2 s. The longer wait
  covers resources that load over the bridge on a slow panel. Untested on a panel.
- The tablet's island memory numbers in its run (about 24 MiB per island) were taken with these blank islands; a card
  that draws will cost more.
- ~~scheduler-card draws blank in an island~~: answered above (the missing members, and `cardBroken` for the next
  one). The white arcs on Bubble's slider: the opaque island page is the change; a panel run checks it. Each island page reads HA's strings (about 1 MB of JSON for English) once from the browser's cache; what that
  costs per island on the S1 isn't measured yet.
- scheduler-card's `ha-button` shows its label when undefined (its text is its own children) but loses its button look.
  A stand-in for `ha-button` would keep such cards in islands; none is written yet.
- The 2.5 s grace on the tablet: untested on a panel. A slower panel defines nothing faster, since nothing but the
  card's own script defines anything in an island.
