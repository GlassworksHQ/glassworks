---
topic: display
evidence: verified
---

# Vitro: why did Better Thermostat's card draw blank islands on the tablet?

**Status (2026-11-25): explained and fixed in the island bridge; one card type routed to HA's frontend.** The tablet's
dashboard named a card type the installed script doesn't define, and the island bridge hid that behind an empty
page. A second, general bug kept any card that skips renders by comparing states from ever drawing once it had
rendered before the bridge's first snapshot. Both are fixed. Better Thermostat's large climate card still can't draw in
an island, because its dial is part of HA's frontend, so its view opens in HA's frontend.

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
| `better-thermostat-normal-climate-card` draws its dial with HA's `ha-control-circular-slider` and `ha-big-number`, which only HA's frontend has | Its view opens in HA's frontend (`WebFallback.FRONTEND_CUSTOM_CARDS`) |

The tablet's dashboard should use `custom:better-thermostat-normal-climate-card` (or the mini card). With the old name,
HA's frontend and Vitro now both show the error card.

## How to reproduce

1. `node tools/vitro-parity/island-check.mjs <case.json> --card better-thermostat-ui-card`, with a case file holding a
   `card` and a `fixture` for a climate entity (see `tools/vitro-parity/README.md`, "Fixtures"). It runs the fake HA on
   127.0.0.1 and a throwaway Chromium, and writes nothing (add `--shot out.png` for a screenshot). It downloads HA's
   pinned frontend and the card into `~/.cache/glassworks-perf` on first use.
2. The island side: `WebIslandCardView.buildIslandHtml` for the card, served on the fake HA's origin with the bridge
   socket stubbed, as in step 3 above.

## Open questions

- The island waits up to 4 s before it reports a missing element; HA shows its error card after 2 s. The longer wait
  covers resources that load over the bridge on a slow panel. Untested on a panel.
- The tablet's island memory numbers in its run (about 24 MiB per island) were taken with these blank islands; a card
  that draws will cost more.
- Other cards that draw with HA's own elements (any `ha-control-*`, `hui-card-features`) can be found the same way;
  `island-check.mjs` lists them per card.
