<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/banner-kiln-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Kiln</h1>

<p align="center">
  <b>Install Glassworks on a wall panel from the browser.</b><br>
  Over WebUSB, with a backup first and a way back.
</p>

<p align="center">
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30">
    </picture>
  </a>
  <a href="../../../LICENSE">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-mit-on-dark.svg">
      <img alt="License: MIT" src="https://www.glassworks.dev/brand/badge-license-mit-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-under-development-on-dark.svg">
    <img alt="Status: under development" src="https://www.glassworks.dev/brand/badge-status-under-development-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

Kiln installs Glassworks on a wall panel from the browser, over WebUSB. It backs the panel up
first, and can put it back. This is Kiln on Next.js (App Router), built on the design in
[`docs/design/kiln`](../../../docs/design/kiln/README.md) and the Glassworks brand.

**Status:** live at [kiln.glassworks.dev](https://kiln.glassworks.dev). It has not yet done a full
install on a real panel. Until it has, s1.edm.sh keeps serving the page in
[`kiln/src`](../../src/README.md).

## Routes

| Route | What | Reached with nothing running |
|---|---|---|
| `/` | Pick your panel: the frames as on glassworks.dev/frames, from each frame's `design/frame.json`. Forwards s1.edm.sh's old `/#/…` links | — |
| `/aqara-s1-plus` | Connect: put the panel in recovery mode, click Connect | shows Connect |
| `/aqara-s1-plus/install` | Backing up and writing, and the "your turn" sheets | goes to `/aqara-s1-plus` |
| `/aqara-s1-plus/set-up` | The app's settings on a freshly installed panel | connects to an installed panel over adb, then settings (was `#/resume`) |
| `/aqara-s1-plus/set-up/wifi`, `/home-assistant`, `/radio` | One set-up step each | connects to an installed panel, then that step (was `#/wifi`, …) |
| `/aqara-s1-plus/done` | Done, with the backup to download | goes to `/aqara-s1-plus` |
| `/aqara-s1-plus/undo` | Undo: how far back, which backup. "Keep its settings" checks the backup (kiln-core's `checkRestorable`) before Aqara's apps go back; a refused backup, or a broken plan in the recipe, changes nothing on the panel and links to undoing by hand | shows Undo |
| `/aqara-s1-plus/undo/connect`, `/undo/restore`, `/undo/done` | Undo's recovery mode, writing, and done | go to `/aqara-s1-plus/undo` |
| `/aqara-s1-plus/resume` | An alias of `/set-up` (s1.edm.sh's `#/resume`) | as `/set-up`: connects to an installed panel; the address then becomes `/aqara-s1-plus/set-up` |
| `/lululemon-mirror` | **Test only.** With `?apk=release` or `?apk=local`: what goes on a Mirror over fastboot (#40, #579). The page opens on the choice: Glassworks, first and picked, or a community image (Glass Panes) behind "Show community images". Each is a recipe of data (`frames/lululemon-mirror/installer/src/choices.ts`, format in [docs/kiln-images.md](../../../docs/kiln-images.md)) that `src/mirror/flash.ts` runs inside Kiln's safety steps. Every file comes through Refract, Kiln's release proxy (#347; `IMAGE_PROXY`, `NEXT_PUBLIC_KILN_PROXY` at build time; Range resume), checked against its SHA-256; a download Refract can't serve shows *Try again* and *Report this*, never download-and-select. Glassworks: Kiln builds the images in a worker on OPFS (`images.ts`), flashes them and finishes over adb with this browser's key. The Mirror refuses that key until it has started far enough to read it, so "One last click." asks again every 5 s for five minutes; a first start that didn't finish (an error, a reload) finishes again from the image screen's "Finish its first start", with no reflash. Glass Panes: both files written as published. Each Done's way back opens the choice with the other image picked. `&image=glass-panes` skips the choice into that image's install. With `&mode=app`: Glassworks alone over adb onto a Mirror already on the community image, with undo. The APK: with `?apk=release`, Glassworks' newest `generic-android-legacy-v*` release (never `releases/latest`, which is the S1's), its arm64-v8a APK through Kiln's release route (`/api/release/generic-android-legacy`), checked against the release's `.sha256` (`src/mirror/release.ts`); no release is an error, not a file picker. With `?apk=local`, a file picked on this computer. Without either, the page says the Mirror doesn't install from Kiln yet. It opens to everyone, with the release as the default, when the Mirror's `frame.json` makes it installable (`status` alpha or later and `kiln` set; `MIRROR_OPEN` in `src/mirror/session.ts`), the same two fields that put it in the picker. Until then the picker shows the Mirror as a disabled card (it is `development`). Release demos: `?demo=flash-release`, `flash-release-downloading`, `flash-release-none`, `flash-release-bad`, `pick-release` (`src/mirror/releaseDemo.ts`) | shows the test page or the "not yet" page |
| `/android` | **Test only.** With `?apk=local`: Glassworks onto any Android 6+ device over adb (#403), with the recipe in `frames/generic-android/installer`. Kiln names the device from WebUSB before USB debugging is on (its vendor ID and product name), or asks for its brand; shows that brand's steps to turn debugging on; connects (Allow on the device); reads it, writing nothing (a device with a frame of its own, the S1 by its `ro.hardware`, is refused and sent to its page), then installs or updates, grants and reads each grant back, sets the home screen and hands over to the app's setup page. A vendor build that refuses a step says so once and carries on; a home screen Android won't set asks for one tap. Done shows the frame card of a tested device, or "untested device". Undo takes back the device admin, then removes the app. Kiln holds the adb connection only while it is known to work: Done, or a failure other than a refusal, lets it go, so Undo from Done and Try again ask the browser for the device again. A refused APK can be swapped on the error screen. Coming back after Done starts a new install; a refusal says so when `ro.hardware` can't be read. The APK is a file picked on this computer until Glassworks for Android has a release. Without `?apk=local`, the page says Kiln doesn't install on Android devices yet. Demos: `?demo=run`, `find`, `undo-find`, `named`, `brand`, `debug`, `debug-generic`, `allow`, `installing`, `vendor`, `done`, `done-untested`, `undo-done`, `error`, `own-frame` (`src/android/demo.ts`) | shows the test page or the "not yet" page |
| `/android/tablet` | Glassworks from the tablet's own browser, no computer (#403, `src/android/tablet`). Kiln reads what the browser lets it (`identifyBrowser` in `frames/generic-android/installer/src/browser.ts`): Android or not, tablet or phone, the Android version and the build that fits, the model from client hints or a GPU and screen fingerprint (Chrome's desktop site hides it). Then: Device, Get the app (the newest release's APK for its ABI through `/api/release/<line>`, or "not out yet" before the first), Set up (what the app's own setup asks), and Finish with Kiln (USB debugging, with a link to About tablet; Android won't open Developer options from a link). On a computer it says to open it on the tablet. Demos: `?demo=pixel`, `galaxy`, `phone`, `unknown`, `nougat`, `old-chrome`, `lollipop`, `computer`, `get`, `get-unsure`, `get-legacy`, `get-either`, `get-none`, `get-error`, `get-loading`, `setup`, `kiln`, `kiln-galaxy`, `kiln-unknown` (`src/android/tablet/demo.ts`) | shows Device, or "open it on the tablet" |
| `/api/release/<line>?tag=…&name=…` | Streams a release line's APK from this repo's GitHub Releases (no CORS there). Only allowlisted lines, tags and names pass (`RELEASES` in `src/app/api/release/lines.ts`): `aqara-s1-plus` (`vX.Y.Z` or `aqara-s1-plus-vX.Y.Z` with `s1-kiosk-X.Y.Z.apk`), `generic-android` and `generic-android-legacy` (`<line>-vX.Y.Z` with `glassworks-<line>-<abi>-X.Y.Z.apk`, one per ABI in `frames/generic-android/design/frame.json`). An unknown line gets 404, any other tag or name 400, both before GitHub is asked | — |
| `/api/release/<line>/latest` | The line's newest published release and the files of it Kiln serves, as JSON, or `{ "release": null }` before its first. Asks GitHub's REST API unauthenticated, every page up to a short one (10 at most, else 502), so the CDN keeps each answer 10 minutes | — |
| `/api/release-asset` | The S1's address before #28: a 308 redirect to `/api/release/aqara-s1-plus` with the same query | — |

The session is the truth and the address bar follows it (`src/wizard/routes.ts`, `useRouteSync.ts`).
Moving forward through set-up adds a history entry, so Back (and the rail's set-up steps) go back to
the step before on the same session, with what was entered kept; every other move replaces the
address.
A route never starts or repeats a flow: while something runs (USB work, a picker, the app going in),
any other route of the frame shows the running step, and leaving for the picker keeps it running
(the picker shows "Back to it"). A route that starts something new once the last flow finished gets
a new session, and the old one lets go of the panel (`Session.close`: the Home Assistant poll, the
app's channels, adb). A Home Assistant sign-in waiting on its tab counts as running. Kiln's own links
are client-side (`next/link`), so they never cut an install short; the leave-page warning and the
screen wake lock (`components/LeaveGuard.tsx`, in the root layout) follow a write onto every route,
the picker included.

When a run stops with an error and the person moves on (Try again, another route, a new install),
Kiln keeps that failure until the next run starts: the picker and Connect say "The last run stopped:
…" with **Download its log**, a text file with what happened, the error and the technical log
(`wizard/registry.ts`). Nothing else keeps it, and a reload drops it.

The browser tab keeps two things across sessions and reloads (`wizard/registry.ts`, sessionStorage):
whether an earlier step may have changed the panel, and which backup the latest run saved. So a later
session's failure never says "nothing was written" when a try did, and its Done still offers the
backup's download (the files stay in IndexedDB). When the browser blocks storage, a reload can't
know, and the screens say Kiln can't tell instead.

## How it fits together

| File | What |
|---|---|
| `src/wizard/state.ts` | The wizard's state and its one reducer. The rail, the stack, the plain log and the time left are derived |
| `src/wizard/flowUi.ts` | kiln-core's `FlowUi` as wizard actions. `AskBridge` is the "your turn" sheet |
| `src/wizard/session.ts` | The hardware steps: InstallFlow with the recipe, then the app's setup over adb. Screens only call it |
| `src/wizard/routes.ts`, `useRouteSync.ts`, `registry.ts` | The routes, and the frame's session that outlives them |
| `src/frames/catalog.ts`, `src/components/FrameCard.tsx` | The picker's frames, from `frames/<id>/design/frame.json` ([frames/README.md](../../../frames/README.md)), drawn by the card glassworks.dev shares ([`packages/frame-card`](../../../packages/frame-card/README.md)) as radio items |
| `src/wizard/screens.tsx` | One component per screen of the design, on Radix primitives |
| `src/components/Credit.tsx` | The outside work an install stands on, from the frame's `credits` ([frames/README.md](../../../frames/README.md#credits)): `Credit` next to the step that uses it (`built="image"`: "This image is built by git-nimrod as part of Glass Panes."), `BuiltOn` on the start page and the done screen, `stepCredits()` to pick them from a recipe's `credits` |
| `src/frames/aqara-s1-plus.ts` | What the screens say for the S1: the USB names to pick, help links, the radio's next steps |
| `src/mirror/` | The Mirror's test page (`MirrorWizard.tsx`). Its fastboot install: `flash.ts` (the session, no React), `FlashScreens.tsx`, `imageWorker.ts` and `images.ts` (the images on OPFS in a worker), `flashDemo.ts`. Its adb-only path: `session.ts`, `demo.ts`. On `@glassworks/lululemon-mirror-installer` |
| `src/recovery/player.ts` | The recovery-mode player, drawing a frame's data. The S1's is `frames/aqara-s1-plus/design/recovery.json` (canonical); `docs/design/kiln/recovery/` keeps the prototype's copy, and a test keeps them equal. The Mirror's is `frames/lululemon-mirror/design/recovery.json`. `src/recovery/Recovery.tsx` shows it with its step chips, on the S1's Connect and the Mirror's fastboot card |

Rules that tests guard:

- **`requestDevice()` runs inside the click.** A button that leads to a USB prompt calls it before
  any `await` (`test/in-click.test.tsx`).
- **No literal colours.** Colours come from `@glassworks/brand` (`test/no-hex.test.ts`). Ember is
  Kiln's; green, amber and rose mean status only.

## Commands

From the repo root, the npm workspace root:

```sh
npm install
node kiln/scripts/stage-assets.mjs --remote --out apps/kiln/public/assets --release-route /api/release/aqara-s1-plus   # the pinned blobs, as on Vercel
npm run dev -w @glassworks/kiln-app       # http://localhost:5174
npm test -w @glassworks/kiln-app          # also part of npm test
npm run build -w @glassworks/kiln-app     # next build
```

Without a panel: `/aqara-s1-plus?demo=run` clicks through a simulated panel behind the real
InstallFlow. Other `?demo=` names jump to one screen (`src/wizard/demo.ts`), for example
`app` and `app-update` (settings before a first install and an update), `app-installing` and
`app-keyboard` (the install's steps), `wifi` (the panel on Wi-Fi already) and `wifi-new` (it isn't),
`radio-thread` and `radio-thread-old` (a Thread router on a Glassworks that gets the network from
Home Assistant, and on 0.4.3, which can't), and `done-old-boot` (Done on a boot image without the root update service,
so its Silent updates block says each update asks for a tap). The Mirror's test page has its own. The choice and Glass Panes: `?demo=image-choose` (the page's first screen), `image-choose-open`, `image-choose-panes`, `image-link`
(`?image=glass-panes`), `image-download`, `image-download-done`, `image-download-error` (Refract couldn't serve it), `image-download-bad` (a wrong SHA-256),
`image-fastboot`, `image-confirm`, `image-writing`, `image-done`, `image-error`, `image-wifi` (a network to save), `image-wifi-writing`, `image-adb` (Wi-Fi on with Glass Panes' own key),
`image-done-wifi`, `image-done-wifi-unconfirmed`, `image-back-glassworks` and `image-back-panes` (the two ways back;
the list is in [docs/design/kiln/community-images.md](../../../docs/design/kiln/community-images.md)). Glassworks over fastboot: `?demo=flash-image`, `flash-downloading`,
`flash-download-error`, `flash-download-bad`, `flash-ready`, `flash-fastboot`, `flash-fastboot-wrong`, `flash-confirm` (the stock warning),
`flash-working`, `flash-adb`, `flash-done`, `flash-done-chooser` (another launcher survived), `flash-error` (a refused write, with the retry without FILL). The adb-only path: `?demo=run`
clicks through a simulated Mirror (`src/mirror/demo.ts`); `pick`, `pick-wrong` (an APK that isn't Glassworks),
`checks`, `checks-fail`, `installing`, `done`, `done-unconfirmed` (a launcher `pm hide` couldn't hide, so HOME opened
Android's list), `undo-done`, `undo-pending` (undo again to finish) and `error` jump to one screen. None waits on a tap
on the Mirror: it has no touchscreen. They work in `next dev`,
or in a build made with `NEXT_PUBLIC_KILN_DEMO=1`.

## The panel's setup page

The panel serves a small build of Kiln's setup steps at `http://<panel>:8099/setup` (#34), so a panel
already on Wi-Fi can be set up from a laptop with no cable, and one on no network from a phone on the
panel's own setup Wi-Fi (#197): then the page starts with the Wi-Fi step. The steps are the wizard's own
(`src/setup/`: `ChannelSetup` and `steps.tsx`); the page adds pairing with the code on the panel's
screen (`src/lan/`). It runs the setup channel over the panel's `/setup/channel` WebSocket.
The page is the panel's own, so it has the panel's look (#63), not Kiln's: `lan/lan.css` sets the tokens
Kiln's steps read to the panel's colours (always night, one white button, sky to violet for "on"), and
`src/lan/PanelStack.tsx` draws the Glassworks stack. Kiln's wizard loads neither, so it doesn't change.

```sh
npm run build:lan -w @glassworks/kiln-app          # writes core/src/main/assets/setup (committed)
node kiln/apps/kiln/lan/fake-panel.mjs [--closed] [--offline] [--no-radio]  # http://localhost:8099/setup against a fake panel; the code is 482913
```

CI rebuilds the page and fails when the committed files differ, so commit the build with its sources.
The bundle carries React's licence headers, and THIRD-PARTY-NOTICES.md lists what it bundles.

How it behaves:

- Before pairing, the page asks the panel to show a code and checks the one typed (`/setup/pair`). The
  panel's limits apply: 5 wrong tries kill a code, 10 in 15 minutes lock code entry.
- A pairing lasts until Done, 30 minutes without a change, or 2 hours. Reading the state doesn't count
  as a change. When the panel ends it, the page asks for a new code and carries on at the same step.
- A pairing counts only from the address that paired. A browser whose source address changes (for
  example a `.local` name that resolves to IPv4 one moment and IPv6 the next) has to pair again.
- Wi-Fi (`wifi.scan`, `wifi.join`) comes first only when the panel says it is on no network
  (`state.online` false). The same card as the wizard's Wi-Fi step (`Wifi` in `steps.tsx`). The panel
  replies, then joins, which closes its setup Wi-Fi, so the page stays on "joining" and the panel's screen
  gives the outcome. The page keeps its channel and asks once more 45 s later (`JOIN_CHECK_MS`): an answer
  means the panel's setup Wi-Fi is still up and the join didn't happen, so the page says so, with Try again. The panel refuses `wifi.join` while it is on a network: a password never crosses a LAN
  as plain HTTP.
- **Sign in** on the Home Assistant step saves the address, then goes to the panel's sign-in in the same
  tab (`/signin/continue?then=setup`, #453). The panel binds that sign-in to this browser with a cookie
  holding its state (single use, 10 minutes, PKCE as for the QR code). Each pending sign-in gets a cookie
  slot no other pending one holds (`gw_signin_<n>`), so starting another never replaces it and a browser holds
  at most 16 of them. The panel refuses its callback from any other browser with a way back to setup, and
  after Home Assistant loads `/setup#signed-in` again. The binding cookie is left to run out (30 minutes), not deleted: once its state is used its slot is free, so a newer sign-in may already hold that name, and a leftover binds nothing. The
  QR code's own sign-in is not bound, as before. The page reads where setup is from the
  panel, then goes on to the radio step, or to Done on a panel without a radio. Kiln's wizard
  over USB opens the sign-in in a pop-up instead (`/signin/continue?then=kiln`; a tab where pop-ups are
  blocked), since leaving its page would drop the USB connection. The panel's last page closes that window, or
  says "Signed in. Kiln has moved on." where the browser won't let it, and Kiln moves on to the radio step as
  soon as the panel reports the sign-in, with no click.
- The step rail lists the steps the panel has. Before pairing, the page reads the panel's state once over
  the channel (which answers `state` to anyone) and lets it go; a frame without a radio has no Radio step.
- Pairing is open until Home Assistant adds the panel. After that, the panel's **Allow setup over the
  network** button in Home Assistant, or Kiln over USB (`pairing.arm`), opens it for 30 minutes.
- What is left: the panel has no write timeout on the WebSocket. A browser that stops reading while the
  panel writes holds one of the panel's web workers until the OS gives up on the TCP send (minutes).
  A newer paired browser or Done ends such a channel at once.

## Vercel

`vercel.json` installs and builds from the repo root (the workspace root): `npm ci`, then the blobs,
then `next build`. The project's Root Directory is `kiln/apps/kiln`, with "Include files outside the root
directory" on (the build reads the root's `package.json` and lockfile, `kiln/packages`, `packages/`,
`frames/`, `tools/`, `branding/` and `docs/img`).
