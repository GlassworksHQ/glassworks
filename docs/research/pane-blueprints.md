# Pane blueprints: can a panel add Home Assistant blueprints without writing to Home Assistant?

**Status (2026-11-15): working off the panel, untested on one.** The four blueprints pass Home
Assistant 2026.11.1's own config check, and every link and API the card uses was checked against Home
Assistant's source. No panel has opened the import dialog yet, and no blueprint has run in a real home.

Home Assistant core 2026.11.1 (pip `homeassistant`), frontend 20261028.5. Tools: `uv`, Python 3.14,
headless Chrome for the Manage mockups.

## Question

Each pane can ship Home Assistant blueprints (the doorbell, a question on the panels, lights on at the
panel). The panel's own Home Assistant sign-in only reads (pane rule 4, [principles](../principles.md)).
So: how does a person on the panel, or on the Manage page, get a blueprint into Home Assistant, and how
does the card know whether it is already there? The design and its reasoning are in the maintainer's
approval of 2026-11-15; this note keeps the evidence.

## What we tried

1. **Home Assistant's blueprint API is admin-only** (2026-11-15, read in core 2026.11.1,
   `components/blueprint/websocket_api.py`). `blueprint/list`, `blueprint/import`, `blueprint/save`,
   `blueprint/delete` and `blueprint/substitute` all carry `@websocket_api.require_admin` (lines 66,
   100, 154, 212, 240). So even "is it there already?" needs an administrator. A non-admin token gets
   `unauthorized`; the panel doesn't ask for the list then.

2. **What `blueprint/list` answers** (2026-11-15, read, same file, `ws_list_blueprints`, line 74):
   `{"<path>": {"metadata": {...}}}`, or `{"<path>": {"error": "..."}}` for a file it can't read. The
   metadata carries `source_url` when the blueprint was imported. `HaBlueprintClientTest` scripts this.

3. **The name an import from GitHub gets** (2026-11-15, read, `components/blueprint/importer.py`,
   `fetch_blueprint_from_github_url`, line 174): `<owner>/<file>` without `.yaml`, which the save adds
   back: `GlassworksHQ/doorbell-panels.yaml`. The ref and the folders are dropped, so two files with
   the same name in the repo would overwrite each other. `BlueprintCatalogTest` fails on that.
   `blueprint/save` with `source_url` stores it in the metadata (`websocket_api.py` line 183).

4. **The import and list links** (2026-11-15, read, frontend 20261028.5,
   `src/panels/my/ha-panel-my.ts`, lines 426 and 433). `blueprint_import` goes to
   `/config/blueprint/dashboard/import` with `blueprint_url`; `blueprints` goes to
   `/config/blueprint/dashboard` and takes `search`, `sort`, `direction` and `group`. The instance's own
   `/_my_redirect/...` is the hidden panel the `my` integration registers (core,
   `components/my/__init__.py` line 9), which `default_config` loads (its `manifest.json`). An install
   without `default_config` and without `my:` doesn't have it. `my.home-assistant.io/redirect/...` does
   the same through My Home Assistant, which the Home Assistant phone app opens signed in.
   ```
   $ curl -sf https://raw.githubusercontent.com/home-assistant/frontend/20261028.5/src/panels/my/ha-panel-my.ts | grep -n -A4 'blueprint'
   426:  blueprint_import: {
   428:    redirect: "/config/blueprint/dashboard/import",
   430:      blueprint_url: "url",
   433:  blueprints: {
   435:    redirect: "/config/blueprint/dashboard",
   436:    params: blueprintsTableParams,      (search, sort, direction, group)
   ```

5. **Raw GitHub at a short commit** (2026-11-15, verified). A test build knows only its 7-character
   commit (`S1_TEST_BUILD`). raw.githubusercontent.com takes it:
   ```
   $ curl -s -o /dev/null -w '%{http_code}' https://raw.githubusercontent.com/GlassworksHQ/glassworks/d60fac0/README.md
   200
   $ curl -s -o /dev/null -w '%{http_code}' https://raw.githubusercontent.com/GlassworksHQ/glassworks/v0.4.3/README.md
   200
   ```

6. **The blueprints are valid** (2026-11-15, verified). Home Assistant's own `check_config` on a scratch
   config folder that uses each blueprint with made-up inputs
   ([`tools/blueprints/ha-check.sh`](../../tools/blueprints/ha-check.sh), inputs in
   [`samples.yaml`](../../tools/blueprints/samples.yaml)):
   ```
   $ uv venv -p 3.14 /tmp/ha && VIRTUAL_ENV=/tmp/ha uv pip install homeassistant
   $ tools/blueprints/ha-check.sh /tmp/ha/bin/python
   ok: 4 blueprints, 5 sample automations valid in Home Assistant 2026.11.1
   ```
   A negative control: with `condition: triggr` in *Lights on at the panel*, the same script exits 1
   with `Blueprint 'Lights on at the panel' generated invalid automation ... Invalid condition "triggr"`.

7. **A template condition that returns a string is false** (2026-11-15, verified with Home Assistant's
   `template.result_as_boolean`). The first draft of *Ask on the panels* ended its answer check with
   `and scripts[n - 1]`, which renders `script.remind`: Home Assistant reads that as false, so no answer
   would ever run. It now compares with `!= ''`. The `buttons` variable renders to a real list
   (`[{'action': 'ask_x_1', 'title': 'Yes'}, ...]`), which `to_json` then sends to the panel.

## Result

| Question | Answer |
|---|---|
| Can the panel import with its own token? | It could (if admin), but that writes to Home Assistant. The panel opens Home Assistant's own import dialog in the dashboard instead; the person presses Import there |
| A non-admin panel user? | Home Assistant's blueprint pages are closed to them, also in the dashboard. A QR code of the My Home Assistant link, for an administrator's phone |
| Already in Home Assistant? | `blueprint/list`, admin only, matched by `source_url` (any ref of this repo's file) or by the name an import gets (`GlassworksHQ/<file>`) |
| "Create automation"? | No link opens a new automation on a blueprint. `/_my_redirect/blueprints?search=<name>` opens the list filtered to it; one tap on the row starts one |
| Which file? | Raw GitHub at the app's ref: `v<versionName>` on a release, the commit on a test build, `main` on a development build |

## How to reproduce

- The links and button states: `./gradlew :glassworks-pane-api:testDebugUnitTest --tests '*BlueprintsTest*'`
  (JVM only).
- The catalog and the YAML's shape: `./gradlew :kiosk:testDebugUnitTest --tests '*BlueprintCatalogTest*'`.
- Home Assistant's own check: step 6 above. It writes only a temporary folder. Delete `/tmp/ha` after
  (about 600 MB).

## Open questions

- None of the four blueprints has run against a real panel and a real home. The doorbell hasn't met a
  real doorbell.
- The dashboard opens the instance's `/_my_redirect/...` in place (the content script's `navigate`).
  Not yet seen on the S1's Gecko.
- An outdated copy (imported from an older tag) could be spotted by comparing the tag in its
  `source_url` with the app's version. Not drawn or built.
