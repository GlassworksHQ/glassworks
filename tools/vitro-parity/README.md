# Vitro parity harness

The harness checks how closely Vitro draws each card compared with Home Assistant. Home Assistant's own render is the spec (#790), and the harness turns "looks off" into a list (#793). It renders every case in a corpus twice:

- **Home Assistant:** HA's real frontend in headless Chromium, at the panel's CSS size and density, with card-mod loaded before the dashboard (as `frontend: extra_module_url` does) and the kiosk's header hidden.
- **Vitro:** `VitroView` under Robolectric's native graphics, at the same size and density, fed the entity states HA's page held when it drew the reference, HA's themes, and the templates HA rendered.

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

The report is `engines/engine-vitro/build/reports/vitro-parity/index.html`, with `report.md` and `report.json` next to it for scripts and issues.

## What a refresh does

1. `capture.mjs prepare` saves the corpus to one dashboard, `vitro-harness` (admin only, hidden from the sidebar), as one sections view per card. It's the only thing the harness writes to Home Assistant: it never changes an entity or another dashboard. It also saves the `/static` images a case shows into `assets/`.
2. A collect run of the Robolectric test lists the templates Vitro asks Home Assistant to render for each case (Markdown content, Mushroom fields, card-mod sheets). They differ from the frontend's own, so they're rendered separately.
3. `capture.mjs capture` opens each case on each panel, waits until two screenshots in a row are identical, then crops the card plus 8 CSS px around it. It records the card's bounds, the text runs and their fonts, and the states of the entities the card reads, taken from the page's own `subscribe_entities` stream, so they're exactly what HA drew. It also records the templates Vitro asked for, rendered right then. The page's clock is frozen at one instant, and Vitro's clock card is drawn at the same one.
4. The comparison run writes the report.

HA access comes from `~/.t3/glassworks-m1/vitro-parity/ha.json` (`{"origin": …, "token": …}`, or `VITRO_PARITY_HA=<file>`). The token and origin are never printed or written.

## Corpus

| Path | What |
|---|---|
| `engines/engine-vitro/src/test/parity/frames.json` | The panels: S1 960×480 CSS at 1.5, Pixel Tablet 1280×800 at 2, Mirror 720×1280 at 1.5 |
| `…/corpus/<case>.json` | One card per file: `card`, and optionally `title`, `frames`, `dark` (default true), `theme` (a view theme), `span` (section columns), `threshold`, and `masks` (CSS px regions relative to the card, each with a `why`) |
| `…/refs/<frame>/<case>.png` and `.json` | Home Assistant's render and what came with it |
| `…/refs/shared.json` | HA's version, the config fields Vitro reads, and the themes |
| `…/assets/` | Images the fixture server hands Vitro, standing in for HA's `/static` |
| `…/baseline.json` | Known drift per `frame/case`, with a `reason` |

A local corpus (`--local [dir]`, default `~/.t3/glassworks-m1/vitro-parity/harness-local`) has the same layout. Its cases name a real dashboard view instead of a card, `{"dashboard": "s1-home-vitro", "view": "home", "frames": ["s1"]}`, and compare the whole view: every card slot in order, plus the full page.

### Adding a case

Write `corpus/<case>.json`, then run `tools/vitro-parity/run.sh --refresh --cases <case>`. Use entities whose names are harmless (rooms, lights) and whose state doesn't move every second. Commit the case, its `refs/*/<case>.*` files, and any new `assets/`.

## Thresholds and the baseline

| Check | Default | What it measures |
|---|---|---|
| Pixels | 2% | Share of the crop that differs, at one sample per CSS px (both images box-filtered first, which irons out font antialiasing), with pixelmatch's YIQ colour distance at 0.1. A pixel that matches a neighbour within 1 CSS px counts as a shift, not a difference |
| Bounds | 2 CSS px | The largest offset of any edge of the card's slot |
| Font | 1 CSS px | The largest font size difference among the texts found |
| Texts | 0 missing | HA texts not found in any of Vitro's TextViews. Text Vitro draws straight on a canvas (gauges, the thermostat dial) shows up here as missing |

A case over its threshold is a **known drift** if `baseline.json` lists it and it hasn't got worse, with 2% pixels, 1 px bounds and 0.5 px font of slack. Otherwise it's a **regression** and the test fails. A listed case that comes back within its threshold gets a note to drop it from the baseline. The audit (#794) works through the baseline until each case either matches or carries a reason.

## Privacy

Committed fixtures hold no tokens, origins, IPs (except RFC 5737), MACs, serials, locations or personal media lists. `lib/scrub.mjs` drops them before anything is written. Every output is also checked against a local denylist (`~/.t3/glassworks-m1/vitro-parity/deny.txt`, one name per line, never committed), and `ParityHarnessSelfTest` scans the committed JSON in CI. Real dashboards stay in the local corpus.

## Limits

- Robolectric draws with the host's Skia, not the panel's GPU, so a device can still differ by a few pixels. The 1 px shift tolerance and the baseline slack absorb most of it. Rendering on macOS and on CI's Linux may also differ slightly.
- History graphs get no history, and cameras get no stream. Neither is in the committed corpus.
- The pixel diff only sees the card's crop in HA's position. A card that renders taller or lower in Vitro shows up in the bounds check.
