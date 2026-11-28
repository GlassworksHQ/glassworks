# Contributing

## Where to ask

- **Community chat:** [Discord](https://discord.gg/hHpf77fBMj).
- **Questions, ideas and frame bring-up** (porting Glassworks to a new device): [Discussions](https://github.com/GlassworksHQ/glassworks/discussions). Answers there stay searchable for the next person.
- **Bugs and hardware reports:** an issue, from the templates below.
- **Security problems:** privately, as [SECURITY.md](SECURITY.md) says. Not in Discussions or an issue.
- **Anything else, in private:** [hi@glassworks.dev](mailto:hi@glassworks.dev).

Bring-up notes that others can build on belong in the same PR as the work: in `frames/<id>/research/` for one device, or in [`docs/research/`](docs/research/README.md) for what applies to every frame. Everyone here follows the [code of conduct](CODE_OF_CONDUCT.md).

## Before you propose a feature

Glassworks is opinionated on purpose. Read [Glassworks Principles](docs/principles.md) first: what
we build, what we don't, and the questions a new feature has to pass. New panes follow
[What makes a pane](pane-api/src/main/java/dev/glassworks/README.md#what-makes-a-pane).

## Issues

Pick a template: **Bug**, **Hardware report** (an Aqara S1 model, firmware or base we haven't tested),
**Device report** (an Android tablet or phone you ran Glassworks on, whether it worked or not), or
**Feature request**. Include the Glassworks version (and the Aqara firmware on an S1), and strip
tokens, Wi-Fi passwords and Thread datasets from logs.

A device report can make a device a tested device: its own page on glassworks.dev, known by Kiln,
marked as tested by the community ([frames/README.md](frames/README.md#who-tested-it)).
If you installed with Kiln, you can send one from Kiln itself, no GitHub account needed: **Tell us how it goes** on
its Done screen fills in what Kiln read, shows you the report before it goes on GitHub, and posts it for you.

## Pull requests

Small PRs against `main`. CI (`.github/workflows/pr.yml`) runs the app's unit tests and release
build, the installer's typecheck, tests and build, the install scripts' tests and the docs' links.

```sh
./gradlew test :kiosk:assembleDebug                 # the app
npm install && npm run stage -w kiln && npm test     # the web installer, from the repo root
frames/aqara-s1-plus/tools/install/test/install-tests.sh                  # the install scripts
python3 tools/check-doc-links.py                     # docs links
python3 tools/check-status.py                        # status labels agree with frame.json and pane.json
```

- Say in the PR what you tested on: a real panel (model, firmware, HA version) or tests only.
- Add a line to `CHANGELOG.md` under **[Unreleased]** if people running a panel will notice it.
- Changes to the boot patch, root helper or radio need testing on a real panel before merge.
- **UI changes need screenshots** in the PR: before and after for a changed screen, after for a new
  one. Without a panel, use the app's mockup tests or the installer's `?demo=` states
  ([AGENTS.md](AGENTS.md#pull-requests)).
- **A frame that uses someone else's image, exploit, tool or research credits them** in its
  `frame.json` `credits`, and its Kiln recipe names the credits its steps use
  ([frames/README.md](frames/README.md#credits)).
- **Write down what you find on the hardware** in `frames/<id>/research/` (or `docs/research/` if it
  applies to every frame), including what didn't work
  ([the playbook](docs/research/bringup.md), [template](docs/research/TEMPLATE.md)).

Coding agents: start at [AGENTS.md](AGENTS.md).

A signed test build is made for any branch named `test-build/vX.Y.Z/<name>`
([releasing.md](docs/releasing.md)).

## Branding

UI, READMEs and new libraries follow the Glassworks brand. [docs/branding.md](docs/branding.md) says
where to find it and which badge or banner to use.

## Labels

| Label | Use |
|---|---|
| `frame: …` | Only one device (frame), e.g. `frame: aqara-s1-plus`. The exact model goes in the issue form, not a label |
| `pane: …` | Which pane, named after its folder: `pane: zigbee-thread`, `pane: matter`; a frame's own panes as `pane: <frame>/<pane>`, e.g. `pane: aqara-s1-plus/wall-base` |
| `area: …` | Which part of the project |
| `needs info`, `needs hardware` | Waiting on the reporter, or on someone with that hardware |
| `upstream` | Caused by or waiting on HA, zigpy, Zigbee2MQTT or another project |
| `hardware report` | Results from untested hardware: the Hardware report and Device report forms |

Code is MIT ([LICENSE](LICENSE)), except Glassworks Core (`core/`, `pane-api/`, `engines/` and `panes/`), which
is MPL-2.0 ([details](pane-api/src/main/java/dev/glassworks/README.md#licence)). The libraries in `libs/` and the
apps built on the core are MIT. A new file in the core gets the MPL-2.0 header its neighbors have.
