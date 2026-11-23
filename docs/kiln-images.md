<p>
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30">
    </picture>
  </a>
</p>

# Adding an image to Kiln

Kiln installs Glassworks on a frame by default. It can also install a **community image**: another
project's software for the same device, as that project publishes it. On the lululemon Mirror that's
[Glass Panes](https://github.com/git-nimrod/glass-panes). We'd like more of them: if your project
makes an image for a frame Kiln supports, this page says how to add it.

Kiln downloads the image itself, through Refract, checks every file against the SHA-256 you publish,
and writes it with the same safety steps as Glassworks. The person doesn't download or select anything. Your image
gets a card on the frame's page, a line that says you make it and that questions go to your project,
and a link of its own that opens straight into its install.

Status: the first version supports the Mirror (`lululemon-mirror`), whose page is in beta, open to everyone.
The design is in [docs/design/kiln/community-images.md](design/kiln/community-images.md).

## What an image is: a recipe

An image is **data, never code**. It's an `ImageRecipe`
([`kiln/packages/kiln-core/src/images.ts`](../kiln/packages/kiln-core/src/images.ts)) in the frame's
installer, in the frame's list of images. The Mirror's list is `MIRROR_IMAGES` in
[`frames/lululemon-mirror/installer/src/choices.ts`](../frames/lululemon-mirror/installer/src/choices.ts).
Glassworks is always first.

```ts
export const GLASS_PANES_IMAGE: ImageRecipe = {
  id: 'glass-panes',                 // the ?image= id: lower case, digits, dashes
  name: 'Glass Panes',
  community: true,
  by: { name: 'git-nimrod', href: 'https://github.com/git-nimrod' },
  project: { name: 'git-nimrod/glass-panes', href: 'https://github.com/git-nimrod/glass-panes' },
  licence: 'MIT; the image’s parts keep their own',
  summary: 'Android 6.0.1 for the Mirror as its release has it, with its own Mirror Kiosk app.',
  support: { name: 'Glass Panes on GitHub', href: 'https://github.com/git-nimrod/glass-panes/issues' },
  release: { name: 'mirror-android-3', href: 'https://github.com/git-nimrod/glass-panes/releases/tag/mirror-android-3' },
  next: { name: 'its README', href: 'https://github.com/git-nimrod/glass-panes#readme' },
  files: [
    { name: 'system.img', url: 'https://github.com/…/system.img', refract: 'glass-panes/mirror-android-3/system.img',
      size: 1074051108, sha256: 'bd7f2e33…ec19' },
    { name: 'userdata.sparse', url: 'https://github.com/…/userdata.sparse', refract: 'glass-panes/mirror-android-3/userdata.sparse',
      size: 154952764, sha256: '81eb5648…c521d' },
  ],
  steps: [
    { do: 'download', file: 'system.img' },
    { do: 'download', file: 'userdata.sparse' },
    { do: 'fastboot' },
    { do: 'system-key', file: 'system.img', makes: 'system+key' },
    { do: 'adb-key', file: 'userdata.sparse', makes: 'userdata+key' },
    { do: 'wifi', file: 'userdata+key', template: 'system.img', makes: 'userdata+wifi' },
    { do: 'flash', partition: 'system', file: 'system+key' },
    { do: 'flash', partition: 'userdata', file: 'userdata+wifi' },
    { do: 'restart' },
    { do: 'wifi-on' },
  ],
};
```

| Field | What it is |
|---|---|
| `id` | The image's id in Kiln's link. `glassworks` is taken |
| `name`, `by`, `project` | Shown on the card, and on every screen of the install: "Glass Panes is made by git-nimrod. Questions about it go to its project." They must match the frame's `credits` entry for the image (`frames/<id>/design/frame.json`) |
| `licence`, `summary` | The card's licence row and its one line |
| `support` | Where questions go. The card says "Its project, on GitHub" |
| `release` | The release the files come from, named on the download screen |
| `next` | Where the person carries on after Done, e.g. your install guide |
| `files` | Every file Kiln downloads: the release URL, its id on Refract, its exact size in bytes, and its SHA-256 in lower-case hex |
| `steps` | What Kiln does, in order, from the step library below |

## The step library

Recipes can only use these steps. Each is reviewed code in Kiln; the frame says which ones it can
run and which partitions a recipe may write (`MIRROR_ACCESS`: `system` and `userdata`, never the
bootloader, `boot` or the modem).

| Step | Fields | What Kiln does |
|---|---|---|
| `download` | `file` | Downloads the file through Refract and checks its size and SHA-256 as it arrives. A file that doesn't match is emptied, and the screen shows it as Kiln's bug, with *Try again* and *Report this* |
| `fastboot` | | Shows the frame's own steps to put the device in fastboot, then connects. Read-only |
| `flash` | `partition`, `file` | Checks the stored file's size and SHA-256 again, as the very copy it sends, then writes it to the partition |
| `restart` | | Restarts the device into what was written |
| `patch` | `file`, `makes` | Kiln's own change to a downloaded image. Glassworks only |
| `first-start` | | Kiln's first start over adb with the browser's key. Glassworks only |
| `adb-key` | `file`, `makes` | Puts this browser's adb key in a copy of a checked userdata image (`file`), named `makes`, in place of the key your image publishes there. It writes one file, `/misc/adb/adb_keys`, keeping its owner, mode and label, and re-hashes the userdata as it reads it. For an image whose system lets in a key listed in `adb_keys`, so the device lets in only the computer that installed it. Not optional: a published key's private half is everyone's |
| `system-key` | `file`, `makes` | Puts this browser's adb key in a copy of a checked system image (`file`), named `makes`, in place of a key your image's framework lets in without asking. It changes one file, the one the frame names (on the Mirror, `/framework/services.jar`, and in it only that key's fingerprint), keeping its owner, mode and label, and re-hashes the system image as it reads it. For an image with a key built in that nobody on the device can take back. It needs room in the browser's storage for the expanded image while it works (about 1.3 GB on the Mirror) |
| `wifi` | `file`, `template`, `makes` | Optional: when the person gives a network, saves it in a copy of a checked userdata image (`file`), or of what `adb-key` made from one, named `makes`. It writes one file, `/misc/wifi/wpa_supplicant.conf`: the template from the checked system image (`template`'s `/etc/wifi/wpa_supplicant.conf`, read where it lies), then the network with its PSK, never the password. It re-hashes a downloaded userdata as it reads it. Without a network, `makes` is its `file` as it was |
| `wifi-on` | `key` (optional) | After the restart, when a network was saved: connects over adb with this browser's key, which an `adb-key` step put in, or with the image's own **published** adb key (`key`, a checked download, held in memory for that one connection), and runs `svc wifi enable`, nothing else, then waits for `dumpsys wifi` to say "Wi-Fi is enabled" (the driver up, not only the switch asked for). For an image that ships with Wi-Fi off, so the saved network joins without anyone opening its Settings |

A new kind of step is a change to Kiln's code, with its own review and tests, not part of adding an
image.

## Kiln's safety steps

Kiln puts its own steps around every recipe. A recipe can't list, skip or move them:

1. **Checks**, right after the device connects: read-only, and a failed check stops the install
   before anything is written.
2. **Backup**: what the device has, or, where it can't be read back (the Mirror), the SHA-256 of each
   file about to be written, in the log.
3. **The warning**: what will be replaced and what can't come back. The person must tick it.
4. **The way back**, on Done: how to put the other image back.

Every file is downloaded and checked before Kiln connects to the device, so a failed download never
leaves a half-written device.

## The rules a recipe must pass

`validateImage()` checks every recipe in the frame's tests, so a pull request that breaks one fails
CI:

- Only steps from the library, only steps the frame can run, and no extra fields on a step.
- Every file has an https URL, a Refract id, a size and a SHA-256, and is downloaded exactly once.
- Every `download` comes before the device is connected.
- `flash` writes a checked file, after `fastboot`, to a partition the frame allows.
- The recipe writes something and ends with `restart`.
- A community image has no `patch` and no `first-start`: it goes on as its project publishes it,
  but for the library's own changes (`system-key`, `adb-key`, `wifi`).
- `system-key` and `adb-key` each take a checked download and name one new file, between `fastboot`
  and `restart`; each is listed once.
- `wifi` takes a checked download, or what `adb-key` made, and names one new file; it's listed once.
- `wifi-on` comes after `restart` and a `wifi` step, once, with a key that is a checked download, or
  with none after an `adb-key` step. It takes no command: what it runs is Kiln's code.
- The frame's own test checks each file against Refract's allowlist: the same source (a release
  file, or a repository file at a pinned commit), size and SHA-256.

Refract ([`kiln/workers/refract`](../kiln/workers/refract/README.md)) is how Kiln downloads every
large file: your image's files by their allowlist id, and Glassworks' own APKs by their release line.
None of it goes through Kiln's own site, and Refract keeps no copy.

## Adding your image, step by step

1. **Publish a release** with your files and their SHA-256 (a `SHA256SUMS` file, or GitHub's own
   digests). Kiln pins one release; a new release is a new pull request.
2. **Add the files to Refract's allowlist**,
   [`kiln/workers/refract/allowlist.json`](../kiln/workers/refract/allowlist.json), as its
   [README](../kiln/workers/refract/README.md#adding-an-asset) says. Read the size and hash from GitHub:
   ```sh
   gh api repos/<owner>/<repo>/releases/tags/<tag> --jq '.assets[] | "\(.name) \(.size) \(.digest)"'
   ```
3. **Credit your project** in the frame's `frame.json` `credits`, with your name, your project and
   the licence.
4. **Add your recipe** to the frame's list of images, after Glassworks, and run the frame's tests:
   `npm test -w @glassworks/<frame>-installer`.
5. **Open a pull request** with the release you pin, the terms that let Refract pass your files on,
   and what you tested.

Only URLs and hashes go in the repo, never an image's bytes.

## What review a community image needs

The maintainer reviews every community image before Kiln offers it. The pull request needs:

- **The terms.** Whether Refract may pass the files on, written down next to the frame's research, as
  [`frames/lululemon-mirror/research/community-image-terms.md`](../frames/lululemon-mirror/research/community-image-terms.md)
  does for the Mirror.
- **The release.** A tagged release, its files and their SHA-256s. Kiln never follows "latest".
- **The partitions.** Only those the frame allows. A recipe that needs another partition, or a new
  step, needs a change to Kiln first.
- **A run on a real device**: install the image through Kiln (the frame's page with
  `?image=<id>`), then install Glassworks back from its Done, and say what you saw.
- **The Refract deploy.** The root thread deploys Refract once the allowlist change is merged; until
  then the download fails, as Kiln's bug to report.

## The link to your image

Each image has a link that opens the frame's page straight into its install, with no choice first:

```text
https://kiln.glassworks.dev/<frame>?image=<id>
https://kiln.glassworks.dev/lululemon-mirror?image=glass-panes
```

Put it in your README. Every safety step stays: nothing downloads before the person clicks, and
"Install Glassworks instead" is in the foot. An unknown id, or `glassworks`, opens the normal page.
On a frame whose page isn't open yet (not alpha or later), add `apk=release` (or `apk=local`):
`https://kiln.glassworks.dev/<frame>?apk=release&image=<id>`.
