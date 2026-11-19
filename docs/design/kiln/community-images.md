# Community images in Kiln (#579): mockups for approval

**Status (2026-11-19): waiting for the maintainer's approval.** Nothing here installs anything yet.

A frame's page in Kiln offers a choice of what to install: Glassworks, the default, or a community
image such as Glass Panes on the lululemon Mirror. Kiln downloads the community image itself, checks
every file against the release's SHA-256, and flashes it with the same safety steps as Glassworks.

## See the screens

The mockups are Kiln's own screens on still demo states, in
`kiln/apps/kiln/src/mirror/choice/`. They have no session behind them, so they are cheap to throw
away once the real flow is built.

```sh
npm run dev -w @glassworks/kiln-app
# http://localhost:5174/lululemon-mirror?demo=image-choose
```

| Demo (`?demo=…`) | Screen |
|---|---|
| `image-choose` | The choice, Glassworks picked (the default) |
| `image-choose-panes` | The choice, Glass Panes picked |
| `image-download` | Glass Panes downloading: `system.img` at 42 %, `userdata.sparse` next |
| `image-download-done` | Both files in and checked |
| `image-download-bad` | A file whose SHA-256 doesn't match the release |
| `image-confirm` | The fastboot checks and the wipe warning |
| `image-writing` | Flashing |
| `image-done` | Glass Panes' Done: next steps, support, the way back to Glassworks |
| `image-done-glassworks` | Glassworks' Done with the way back to Glass Panes |
| `image-back-glassworks` | The choice, opened from Glass Panes' way back |
| `image-back-panes` | The choice, opened from Glassworks' way back |

The fastboot screen is unchanged (`?demo=flash-fastboot`). The screenshots, at desktop and phone
width in both themes, are in the PR and on #579, not in the repo.

## Choices

1. **Cards, not a dropdown.** Each image has to show six things before anyone picks it: name,
   author, link, licence, a one-line description and who gives support. A dropdown hides all of
   them until it opens, and it hides that the other choice exists. Two or three cards fit, as on the
   panel picker. If a frame ever offers more than four, the list becomes Kiln's `.list` rows.
2. **Glassworks is first and picked.** Its pill says "Default". The pill is the neutral one: amber
   and the other status colours mean status only, and ember marks the picked card, as on the panel
   picker.
3. **Every screen of a community install says whose it is.** A line above the heading: "Glass
   Panes, by git-nimrod. Kiln installs it; it isn't Glassworks." The confirm screen adds who to ask
   for help. The credit uses the frame's `credits` entry, as the current Mirror screens do.
4. **The download starts on Continue.** The choice is the click, and the download screen has no
   second one. The two files download one after the other, each with its progress and its hash
   check. A file that doesn't match is deleted, and the screen says nothing was written.
5. **The stack stays empty during a community install.** The rail shows the progress. The stack's
   layers are Glassworks' layers, and lighting them for another project's software would be wrong.
6. **The way back is the other choice.** Each Done screen has a "The way back" block with a button
   that opens the choice with the other image picked, and a line that says what it replaces. The
   current *Way back* screen, where the person downloads and selects two files, goes away: installing
   Glass Panes is now the way back.

## The data each image needs

The mockups read this shape from `options.ts`. The real one goes in the frame's installer recipe
(`frames/<id>/installer`), with tests for the hashes and partitions.

| Field | Glassworks | Glass Panes |
|---|---|---|
| Name, author, project link | Glassworks, GlassworksHQ, glassworks.dev | From the frame's `credits` (`glass-panes-image`) |
| Licence | MPL-2.0 and MIT | MIT; the image's parts keep their own |
| One line | Your Home Assistant dashboard on the glass | Android 6.0.1 as its release has it, with its own Mirror Kiosk app |
| Support | Glassworks on GitHub | Glass Panes on GitHub |
| Files, each with size and SHA-256 | `system.img` (patched in the browser) | `system.img` and `userdata.sparse`, as published |
| After Done | Kiln's first start over adb | The project's own install guide |

## Needs a decision

1. **When Refract can't serve a file.** Today the Mirror falls back to "download it yourself, then
   select it". The mockups show no fallback. Proposal: keep that fallback, but only on the error
   path. `userdata.sparse` also has to go on Refract's allowlist.
2. **How strongly to say it isn't ours.** The mockups say "Kiln installs it; it isn't Glassworks"
   and "Glassworks can't help with it beyond this install".
3. **Scope of the first version.** Proposal: the Mirror with Glass Panes only. The S1's stock Aqara
   restore stays in Undo, because it restores a backup rather than installing a published image.
