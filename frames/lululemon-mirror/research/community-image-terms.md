# Community image terms: may Kiln download the Mirror's community system image and modify it for the user?

**Status (2026-11-15): decided.** The image's own release grants no licence for its vendor
binaries, and a browser can't fetch it from GitHub without a server in between. So the maintainer
decided (2026-11-15): **the person downloads `system.img` from the Glass Panes release themselves**,
and picks the file in Kiln. Kiln checks it against the published SHA-256 and modifies it in their
browser. Nothing passes through our servers, ever: no proxy, no mirror, no cache of the image or any
part of it. We never distribute it.

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
   domain. Doing that for this image would make Kiln's server send out Qualcomm and Quectel binaries
   that nobody licensed to us: distribution, which hard rule 3's reasoning ("no vendor binaries or
   firmware") rules out.

5. **The shared key** (read: the release notes and the project's install guide, section 4). The image
   trusts one published adb key, and the guide says it lives in `userdata` ("your Wi-Fi and the ADB
   key are kept" when only `system` is flashed). Option A's own userdata replaces it.

## Result

| Way | Who sends the bytes | Terms | |
|---|---|---|---|
| Kiln proxies the release (like `release-asset`) | Kiln's server | Unlicensed vendor binaries from our domain | Ruled out |
| The person downloads `system.img` from the Glass Panes release and picks the file in Kiln; Kiln checks the published SHA-256 and patches it in the browser | GitHub, to the person | No licence either way for a modified copy on their own device; nothing passes through us | **Chosen** (maintainer, 2026-11-15) |
| Ask `git-nimrod/glass-panes` for written permission, or a release with CORS-friendly hosting | — | Can't grant rights to the Qualcomm and Quectel parts | Doesn't settle the vendor part |

Option C (our own AOSP system, vendor files taken from the community image at install time) has the
same vendor-binary question, with the same "picked by the person" answer.

## What this means for Kiln

1. Kiln's download button is a plain link to the release asset's URL: the browser downloads it, which
   needs no CORS. It never `fetch`es it.
2. The person picks the downloaded file (Chrome and Edge open the picker in Downloads). The browser
   can't read Downloads by itself, so this click stays.
3. Kiln hashes the file as it reads it, and refuses a file whose SHA-256 isn't the published one
   (a partial download, or another file).
4. The screen says plainly that the image's vendor files belong to Qualcomm and Quectel and come
   from the Glass Panes release, not from us.

## Open questions

- Does the release's `SHA256SUMS` stay put? Kiln would pin `d13dd330…83ed5` for `system.img`.
