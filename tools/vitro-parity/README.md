# Vitro parity harness

The harness checks how closely Vitro draws each card compared with Home Assistant. Home Assistant's own render is the spec, and the harness turns "looks off" into a list. It renders every case in a corpus twice:

- **Home Assistant:** HA's real frontend in headless Chromium, at the panel's CSS size and density, with card-mod loaded before the dashboard (as `frontend: extra_module_url` does) and the kiosk's header hidden. A panel with a touch screen (`"input": "touch"` in `frames.json`: the S1 and the tablet) gets a touch screen in Chromium too, so HA's page has the coarse pointer it has on the panel; the Mirror has none.
- **Vitro:** `VitroView` under Robolectric's native graphics, at the same size and density, fed the entity states HA's page held when it drew the reference, HA's themes, the templates HA rendered, and the history and statistics HA's graphs got.

It then compares each card: a perceptual pixel diff, the card's bounds, and every text HA drew (found in Vitro's views or not, and at what font size). It writes a report with HA, Vitro and the diff side by side, and fails when a card drifts past its threshold.

## Commands

```sh
tools/vitro-parity/run.sh                    # compare with the committed references: no HA, no device (CI runs this)
tools/vitro-parity/run.sh --refresh          # HA reachable: capture new references, then compare
tools/vitro-parity/run.sh --local --refresh  # the real dashboards, from a local corpus that never enters the repo
```

`run.sh` without `--refresh` is the same test CI already runs in `./gradlew test`:

```sh
./gradlew :engine-vitro:testDebugUnitTest --tests '*VitroParityHarnessTest*'
```

Options: `--frames s1,tablet`, `--cases tile-light,clock`, `--out <dir>`, and `--update-baseline`, which rewrites `baseline.json` with the drift found now. The Gradle properties behind them are `-PvitroParityFrames`, `-PvitroParityCases`, `-PvitroParityOut`, `-PvitroParityUpdateBaseline=true`, `-PvitroParityLocal=<dir>` and `-PvitroParityCollect=true`.

The capture tool's helpers (options, asset paths, scrubbing) have their own tests, which need nothing but Node: `npm test --prefix tools/vitro-parity`.

The report is `engines/engine-vitro/build/reports/vitro-parity/index.html`, with `report.md` and `report.json` next to it for scripts and issues.

## What a refresh does

1. `capture.mjs prepare` saves the corpus to one dashboard, `vitro-harness` (admin only, hidden from the sidebar), as one sections view per card. It's the only thing the harness writes to Home Assistant: it never changes an entity or another dashboard. Each save is marked (`glassworks_vitro_harness` in the config), and a `vitro-harness` dashboard the tool didn't make (not hidden and admin-only, or a config without the marker) stops the run instead of being overwritten. One exception: a config saved by an older version of the tool, before the marker, is still taken as the tool's own when it's titled `Vitro harness` and every view path is a corpus case ID. Such a config is replaced.
2. A collect run of the Robolectric test lists the templates Vitro asks Home Assistant to render for each case (Markdown content, Mushroom fields, card-mod sheets). They differ from the frontend's own, so they're rendered separately.
3. `capture.mjs capture` opens each case on each panel, waits until two screenshots in a row are identical, then crops the card plus 8 CSS px around it. It records the card's bounds, the text runs and their fonts, and the states of the entities the card reads, taken from the page's own `subscribe_entities` stream, so they're exactly what HA drew. It also records the templates Vitro asked for, rendered right then. The images HA drew in the card (an `<img>`, an SVG image or a CSS background inside the card, read from the rendered page) go into `assets/` when HA loaded them from its own `/local/` or `/static/`, so nothing is guessed from configs or Markdown. For the committed corpus, only `/static` is allowed. If Vitro later asks the fixture server for an image the capture didn't save, that case fails as a missing input instead of counting as drift. Along with the states, it saves the display data Vitro reads live from HA for those entities, so Vitro formats values the way HA does (display precision, icons, state labels, device names). That data is the entity registry's display entries, their devices' names, the areas the case's area cards name (name, icon, picture, temperature and humidity sensors), and the icons and English state labels of their domains and integrations. It's cut down to what Vitro reads, and HA's device ids are replaced by `device-1`, `device-2` and so on. The page's clock is frozen at the most recent 10:08 in HA's time zone, so the clock's digits are the same in every capture, and Vitro's clock card is drawn at that same instant. Graphs get the same treatment: the capture records every `history/*` and `recorder/*` call the card's page makes, with what came back (the reference's `ws`), and Vitro's cards get those answers for the same calls at the same instant. A call of Vitro's that matches none of HA's, field for field (its times included), fails the case: Vitro asked for other data than HA drew.
   The capture writes nothing until every selected case has succeeded. Then the references, their images and `refs/shared.json` (HA's version, config and themes) are written together. A refresh of only some cases (`--cases`, `--frames`) stops if HA's shared inputs changed since the last full refresh: the references it leaves alone were drawn with the old ones.
4. The comparison run writes the report.

HA access comes from `~/.t3/glassworks-m1/vitro-parity/ha.json` (`{"origin": …, "token": …}`, or `VITRO_PARITY_HA=<file>`). The token and origin are never printed or written.

## Corpus

| Path | What |
|---|---|
| `engines/engine-vitro/src/test/parity/frames.json` | The panels: S1 960×480 CSS at 1.5, Pixel Tablet 1280×800 at 2, Mirror 720×1280 at 1.5, and whether each has a touch screen |
| `…/corpus/<case>.json` | One card per file: `card`, and optionally `title`, `frames`, `dark` (default true), `theme` (a view theme), `span` (section columns), `threshold`, `masks` (CSS px regions relative to the card, each with a `why`), `resources` (Lovelace resources of type `css` from `fixtures/`, see below), and `expect` (`{entity_id: state}`: a refresh writes no reference for the case while an entity is in another state, so a case about a light that is on never quietly becomes one about a light that is off) |
| `…/refs/<frame>/<case>.png` and `.json` | Home Assistant's render and what came with it |
| `…/refs/shared.json` | HA's version, the config fields Vitro reads, and the themes |
| `…/assets/` | Images the fixture server hands Vitro, standing in for HA's `/static` |
| `…/fixtures/` | A case's own stylesheets and fonts, at `/local/vitro-harness-fixtures/` on HA's origin. The capture answers those requests in the browser and adds each sheet to HA's page the way HA loads a `type: css` resource, so nothing is added to Home Assistant. Vitro gets the same files from the fixture server |
| `…/baseline.json` | Known drift per `frame/case`, with a `reason` |

A local corpus (`--local [dir]`, default `~/.t3/glassworks-m1/vitro-parity/harness-local`) has the same layout. Its cases name a real dashboard view instead of a card, `{"dashboard": "s1-home-vitro", "view": "home", "frames": ["s1"]}`, and compare the whole view: every card slot in order, plus the full page.

### Cases with their own stylesheets

Home Assistant is read-only for the harness, apart from its own dashboard, so a case that needs a stylesheet (a web font, say) doesn't add a Lovelace resource. It lists the sheet instead: `"resources": [{"type": "css", "url": "/local/vitro-harness-fixtures/fonts/cardmod.css"}]`, with the file in `fixtures/fonts/cardmod.css`. Only `type: css` under that path is accepted. Such a case gets a page of its own in the capture, and the next case a fresh one, because what a sheet declares stays in the page. The font cases (`font-*`) use fonts under the SIL Open Font License; their licenses sit next to them.

### Adding a case

Write `corpus/<case>.json`, then run `tools/vitro-parity/run.sh --refresh --cases <case>`. Use entities whose names are harmless (rooms, lights) and whose state doesn't move every second. Commit the case, its `refs/*/<case>.*` files, and any new `assets/`.

## Thresholds and the baseline

| Check | Default | What it measures |
|---|---|---|
| Pixels | 2% | Share of the crop that differs, at one sample per CSS px (both images box-filtered first, which irons out font antialiasing), with pixelmatch's YIQ color distance at 0.1. A pixel that matches a neighbour within 1 CSS px counts as a shift, not a difference |
| Bounds | 2 CSS px | The largest offset of any edge of the card's slot |
| Font | 1 CSS px | The largest font size difference among the texts found |
| Texts | 0 missing | HA texts not found in any of Vitro's TextViews. Text Vitro draws straight on a canvas (gauges, the thermostat dial) shows up here as missing. HA text that is visually hidden (a 1×1 or clipped screen-reader label, `.visually-hidden`, `.sr-only`) isn't counted |

A refresh takes new references with live states, so the scores of cases that drift move a little with it. After a refresh with no Vitro change, run `run.sh --update-baseline` and commit the references and the baseline together (the reasons stay).

> **An update records whatever drifts now, regressions included.** `--update-baseline` rewrites the entry of every case it measures: a real regression becomes "known drift", and the next run stops reporting it. Before you commit `baseline.json`, read its diff: every entry that got worse must be a change you mean to accept, not a Vitro bug. Keep the entry of a case with an open regression as it is (run the update with `--cases` for the others).

A case over its threshold is a **known drift** if `baseline.json` lists it and it hasn't got worse, with 2% pixels, 1 px bounds and 0.5 px font of slack. Otherwise it's a **regression** and the test fails. A listed case that comes back within its threshold gets a note to drop it from the baseline. The parity audit works through the baseline until each case either matches or carries a reason.

## Privacy

Committed fixtures hold no tokens, origins, IPs (except RFC 5737), MACs, serials, locations or personal media lists. `lib/scrub.mjs` drops them before anything is written. Every output is also checked against a local denylist (`~/.t3/glassworks-m1/vitro-parity/deny.txt`, one name per line, never committed), and `ParityHarnessSelfTest` scans the committed JSON in CI. Real dashboards stay in the local corpus.

The screenshot shows what the JSON scrubs, so for the committed corpus the capture also checks the raw text HA drew in the card and the rendered templates (`findPrivate`), and writes neither file when they show an address, a token or a denylisted word.

Some checks stay manual, because no tool reads pixels or image metadata:

- **Look at every new or changed PNG** in `refs/` before you commit it: a picture, a camera still or a chart can show what no text check sees.
- **Inspect every image added to `assets/`**, its pixels and its metadata (`exiftool <file>`), even one from HA's `/static`.
- Keep the committed corpus to synthetic or harmless entities, and leave cameras and personal media out of it.

## Limits

- Robolectric draws with the host's Skia, not the panel's GPU, so a device can still differ by a few pixels. The 1 px shift tolerance and the baseline slack absorb most of it. Rendering on macOS and on CI's Linux may also differ slightly.
- Relative times ("5 minutes ago") can't be compared: HA's page counts from the pinned 10:08, and Vitro counts from the real clock. Keep them out of the corpus or mask them.
- Cameras get no stream, so none is in the committed corpus. History graphs get the history HA's page got, as of the capture: a refresh redraws them with that day's data.
- A reference records whether its page had a touch screen (`viewport.touch`), and Vitro draws that case with the same pointer, so a reference taken before touch capture still compares like for like until it is refreshed. Only HA's chart legend looks different with a coarse pointer (40 px rows) at rest. Playwright's full-page screenshot turns touch emulation off for a page taller than the window, so on a touch panel the capture makes the window as tall as the page instead, and stops a case whose page has lost its coarse pointer.
- Chart labels (ECharts draws them on a canvas) aren't text runs, so the text check doesn't see them; the pixel diff does. HA measures its axis labels with `measureTextWidth`, and Chrome on a capture host without Roboto measures them in its fallback font: the axis comes out ~2 px wider than on a panel, where Roboto is a system font.
- The pixel diff only sees the card's crop in HA's position. A card that renders taller or lower in Vitro shows up in the bounds check.
