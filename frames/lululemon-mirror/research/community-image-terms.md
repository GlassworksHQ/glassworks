# Community image terms: may Kiln download the Mirror's community system image and modify it for the user?

**Status (2026-11-15): decided, then changed the same day.** The image's own release grants no
licence for its vendor binaries, and a browser can't fetch it from GitHub without a server in
between.

- **Now (maintainer, 2026-11-15, later):** Kiln downloads `system.img` through
  [Refract](../../../kiln/workers/refract/README.md) (#347), our own Cloudflare Worker at
  `refract.glassworks.dev`, with one button. The maintainer chose this knowing that our Worker then
  transmits the image, vendor binaries included. Refract only streams: it stores nothing (no cache,
  no storage, Cloudflare's cache bypassed), serves only an allowlist of exact release files, and
  credits Glass Panes on every answer. Kiln still checks the SHA-256 and modifies the image in the
  person's browser. Refract went live on 2026-11-15 (verified that day: the full image came through
  with the published SHA-256, marked `no-store` and not cached by Cloudflare).
- **Fallback:** when Refract is down or refuses (502), the person downloads `system.img` from the
  Glass Panes release themselves and picks the file in Kiln. This was the first decision (earlier on
  2026-11-15), when nothing was to pass through our servers.

Image: Glass Panes `mirror-android-1` (`git-nimrod/glass-panes`, published 2026-11-05):
`system.img` 850,905,808 bytes, `userdata.sparse` 154,944,572 bytes, `SHA256SUMS`.

## Question

Option A (#40): Kiln downloads the community `system.img`, checks its SHA-256, adds S1 Kiosk's
legacy APK and the boot animation, removes the community kiosk app, and flashes it, all in the
person's browser. Do the image's terms allow that, and can Kiln do it without hosting the image?

## What we read

1. **The repository's licence** (2026-11-15, read: `gh api repos/git-nimrod/glass-panes`). MIT. It
   covers the project's own code (the Mirror Kiosk app, its scripts), not the binaries in the image.

2. **The image's release notes** (2026-11-15, read: release `mirror-android-1`). Its "Licences and
   credit" section, in full:
   > The Android and kernel source keep their own licences. The vendor binaries (Qualcomm, Quectel)
   > belong to their owners. […] If you own any of this and want it changed or removed, please open
   > an issue.

   So no licence is granted for the vendor binaries, to the publisher or to anyone else. The notes
   neither allow nor forbid modifying the image.

3. **What the vendor binaries are** (read, from our earlier research,
   [mirror-android.md](mirror-android.md#5-blobs-what-we-could-ship-what-must-come-off-the-device-ties-to-40)): the Android vendor userspace
   (Adreno GLES, `mm-camera`, ACDB, QSEECom/keymaster, `thermal-engine`) is the DragonBoard
   LA.BR.1.2.7 BSP re-hosted without a licence, plus Quectel camera parts. That note already
   concluded these "must come from the user".

4. **Can a browser fetch it directly?** (2026-11-15, verified)
   ```
   $ curl -sIL https://github.com/git-nimrod/glass-panes/releases/download/mirror-android-1/SHA256SUMS | grep -i -e ^HTTP -e access-control
   HTTP/2 302
   HTTP/2 200
   ```
   No `Access-Control-Allow-Origin`, so a page can't read the bytes. Kiln's release APKs have the
   same problem, and `kiln/apps/kiln/src/app/api/release-asset` streams them through Kiln's own
   domain. Doing that for this image makes our server send out Qualcomm and Quectel binaries that
   nobody licensed to us. The first decision ruled that out on hard rule 3's reasoning ("no vendor
   binaries or firmware"). The maintainer then accepted it for this image, streaming only, through
   Refract (see Result).

5. **The shared key** (read: the release notes and the project's install guide, section 4). The image
   trusts one published adb key, and the guide says it lives in `userdata` ("your Wi-Fi and the ADB
   key are kept" when only `system` is flashed). Option A's own userdata replaces it.

## Result

| Way | Who sends the bytes | Terms | |
|---|---|---|---|
| Kiln streams the release through Refract (`refract.glassworks.dev`): an allowlist of exact files, nothing stored, credit on every answer; Kiln checks the SHA-256 and patches it in the browser | Our Worker, from GitHub to the person | Unlicensed vendor binaries pass through our Worker; accepted knowingly | **Chosen** (maintainer, 2026-11-15, replacing the row below) |
| The person downloads `system.img` from the Glass Panes release and picks the file in Kiln; Kiln checks the published SHA-256 and patches it in the browser | GitHub, to the person | No licence either way for a modified copy on their own device; nothing passes through us | First choice (2026-11-15); now the fallback when Refract is down |
| Ask `git-nimrod/glass-panes` for written permission, or a release with CORS-friendly hosting | — | Can't grant rights to the Qualcomm and Quectel parts | Doesn't settle the vendor part |

Option C (our own AOSP system, vendor files taken from the community image at install time) has the
same vendor-binary question. It would get the same answer: through Refract, with "picked by the
person" as the fallback.

## What this means for Kiln

1. One button: Kiln `fetch`es
   `https://refract.glassworks.dev/glass-panes/mirror-android-1/system.img`. Refract passes `Range`
   through, so Kiln can resume a broken download. Refract refuses (502) when GitHub's size isn't the
   pinned 850,905,808 bytes.
2. If Refract fails, Kiln falls back: a plain link to the release asset's URL (the browser downloads
   it, which needs no CORS), then the person picks the downloaded file (Chrome and Edge open the
   picker in Downloads).
3. Either way, Kiln hashes the bytes as it reads them, and refuses an image whose SHA-256 isn't the
   published one (a partial download, or another file).
4. The screen says plainly that the image is built by git-nimrod as part of Glass Panes, and that
   its vendor files belong to Qualcomm and Quectel, not to us.

## Open questions

- Does the release's `SHA256SUMS` stay put? Kiln would pin `d13dd330…83ed5` for `system.img`.
  Refract checks only the size pinned in its `allowlist.json` (it streams, so it never hashes the
  file): a changed release of another size fails there with a 502. One of the same size streams
  through, and Kiln rejects it by its SHA-256.
