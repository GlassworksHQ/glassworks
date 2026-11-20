# Community images in Kiln (#579)

**Status: approved on 2026-11-19 (round three, draft PR #582) and built in Kiln** for the lululemon
Mirror with Glass Panes. The Mirror's page is still test only (`?apk=local`), and the Glass Panes
install waits on a run on a real Mirror. How to add an image: [docs/kiln-images.md](../../kiln-images.md).

A frame's page in Kiln installs Glassworks, first and picked. A quiet link under it shows the
community images, such as Glass Panes on the lululemon Mirror. Kiln downloads a community image
itself, checks every file against the release's SHA-256, and flashes it with the same safety steps
as Glassworks. We want community projects to be glad to add their images, so the screens credit
them warmly and say plainly where their support lives.

## Rounds

| Round | Maintainer's feedback (2026-11-19) |
|---|---|
| 1 | Cards, the community install, its Done and the way back |
| 2 | Community images hidden until asked for. A link per image (`?image=<id>`) skips the picker. No Copy link button. Each GitHub address linked once per screen |
| 3 | No manual download path: a download Kiln can't serve is a bug, shown with *Try again* and *Report this*. A welcoming tone: no "Recommended" badge, no "it isn't Glassworks" warnings. **Approved**, with: each image is a recipe of data over Kiln's step library, inside Kiln's safety steps; Kiln downloads community images itself; the first version is the Mirror with Glass Panes |

## See the screens

The screens are Kiln's own, on the real session (`kiln/apps/kiln/src/device/imageSession.ts`, the
screens in `ImageScreens.tsx` and `choice/`), with demo states that need no Mirror:

```sh
npm run dev -w @glassworks/kiln-app
# http://localhost:5174/lululemon-mirror?demo=image-choose
```

| Demo (`?demo=…`) | Screen |
|---|---|
| `image-choose` | The page's first screen: Glassworks alone, picked, with "Show community images (1)" under it |
| `image-choose-open` | The community images shown, Glassworks still picked |
| `image-choose-panes` | The community images shown, Glass Panes picked |
| `image-link` | Arriving by `/lululemon-mirror?image=glass-panes`: Glass Panes' install, no choice |
| `image-download` | Glass Panes downloading: `system.img` at 42 %, `userdata.sparse` next |
| `image-download-done` | Both files in and checked |
| `image-download-error` | Refract couldn't serve a file: *Try again* and *Report this* |
| `image-download-bad` | A file whose SHA-256 doesn't match the release: emptied, *Try again* and *Report this* |
| `image-fastboot` | The fastboot steps, with who makes the image above them |
| `image-confirm` | Kiln's checks and the wipe warning |
| `image-writing` | Flashing |
| `image-done` | Glass Panes' Done: what's next in its project, and the way back to Glassworks |
| `image-error` | A write the bootloader refused |
| `image-back-glassworks` | The choice, opened from Glass Panes' way back (community images hidden) |
| `image-back-panes` | The choice, opened from Glassworks' way back (community images shown) |

Glassworks' own screens are the `flash-…` demos (Kiln's README lists them); `flash-download-error`
and `flash-download-bad` show its download failing the same way, and `flash-done` its way back.
The screenshots, at desktop and phone width in both themes, are in the PRs and on #579, not in the
repo.

## Choices

1. **Cards, not a dropdown.** Each image shows its name, author, project link, licence, a one-line
   description and where its support lives before anyone picks it. A dropdown hides all of that,
   and it hides that the other choice exists. Two or three cards fit, as on the panel picker. If a
   frame ever offers more than four, the list becomes Kiln's `.list` rows.
2. **Glassworks first and picked; the others one click away.** The page opens with Glassworks alone
   and picked, with no badge. Under it, "Show community images (1)" and a quiet "Made by other
   projects." The link shows the community cards next to Glassworks. Hiding them again while one is
   picked goes back to Glassworks, so the picked image is always on screen. A community card wears
   a neutral "Community image" pill: amber and the other status colours mean status only, and ember
   marks the picked card, as on the panel picker.
3. **Who makes it, in friendly words.** Every screen of a community install opens with "Glass Panes
   is made by git-nimrod. Questions about it go to its project.", with the author and the project
   linked. That line is the image's credit, from the frame's `credits` entry, so the screens carry
   no second one and each address is linked once. The card's support row says where support lives:
   "Its project, on GitHub".
4. **The download starts on Continue.** The choice is the click. The two files download one after
   the other, each with its progress and its hash check.
5. **A download that fails is Kiln's bug.** There is no "download it yourself" path. If Kiln's route
   can't serve a file, or a file isn't the release's, the screen says what happened, that nothing
   was written, and that it's a bug in Kiln, not something the person did. *Try again* is the one
   ember button. *Report this* opens a new issue on Glassworks with the image, the file and what
   Kiln saw filled in.
6. **"Install Glassworks instead", quietly.** The download and confirm screens of a community install
   have it as a link in the foot, with no claim about which is better. It drops what was downloaded
   for the community image.
7. **The stack stays empty during a community install.** The rail shows the progress. The stack's
   layers are Glassworks' layers.
8. **The way back is the other choice.** Each Done screen has a "The way back" block with a button
   that opens the choice with the other image picked, and a line that says what it replaces. The old
   *Way back* screen, where the person downloaded and selected two files, is gone: installing Glass
   Panes is the way back.
9. **Glassworks' own download fails the same way.** Its `system.img` comes through Refract too, and a
   failure shows the same error with *Try again* and *Report this*. The old fallback, download it
   yourself and select it, is gone.

## A link to an image

A community project can send people straight into its install, for example from its README:
`https://kiln.glassworks.dev/lululemon-mirror?image=glass-panes`.

1. **The link skips the picker.** The page opens on the image's own first screen, "Install Glass
   Panes on the Mirror.", with the line that says who makes it. Nothing downloads until the person
   clicks "Download Glass Panes", and every step after it stays: the hash checks, fastboot, Kiln's
   checks and the wipe warning. The rail has no Choose step.
2. **Glassworks stays one quiet click away**, as "Install Glassworks instead" in the foot.
3. **Only a community image the frame offers.** The id comes from the frame's image list. No id, an
   unknown one, or `glassworks` opens the normal page with the picker.
4. **No Copy link in the page.** The link is the frame's page plus `?image=<id>`. The contributor
   docs for adding an image give each image's link.

## How it is built

Each image is a recipe of data (`ImageRecipe`, `kiln/packages/kiln-core/src/images.ts`): its name,
author, links, licence, one line, its files (URL, Refract id, size, SHA-256) and an ordered list of
steps from Kiln's step library. The Mirror's two recipes are in
`frames/lululemon-mirror/installer/src/choices.ts`:

| Image | Steps, as Kiln runs them (Kiln's own in *italics*) |
|---|---|
| Glassworks | download `system.img` → fastboot → *checks* → *backup* → *warning* → patch (Glassworks, fresh userdata) → flash system → flash userdata → restart → first start over adb → *way back* |
| Glass Panes | download `system.img` → download `userdata.sparse` → fastboot → *checks* → *backup* → *warning* → flash system → flash userdata → restart → *way back* |

The Mirror's bootloader can't read anything back, so its *backup* step writes each file's SHA-256 to
the log instead, and the warning says lululemon's software can't be restored. `ImageSession` runs
either recipe from `plan()`; the format, the step library and the rules a recipe must pass are in
[docs/kiln-images.md](../../kiln-images.md).

## Scope of the first version

Decided 2026-11-19: the Mirror with Glass Panes only. The S1's stock Aqara restore stays in Undo,
because it restores a backup rather than installing a published image.
