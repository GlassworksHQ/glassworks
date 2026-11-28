# Releasing the kiosk app

Panels update themselves from GitHub Releases: the app lists
`https://api.github.com/repos/GlassworksHQ/glassworks/releases`, picks its own newest release
and shows the result in Home Assistant as **update.s1_panel_app_update** (on a panel added before #63;
`update.glassworks_panel_app_update` on one added before #260; a newer one starts with the device's
name, `aqara_s1_plus_` on a new S1, [Names](esphome.md#names))
([esphome.md](esphome.md#app-updates)). Every release, of every app line and every frame, is built and published
by one workflow, [`.github/workflows/release.yml`](../.github/workflows/release.yml), which an admin starts from the
Actions tab with a version: [Release](#release). The S1 app's release holds one APK per ABI, each with its
`.sha256` (see [ABIs](#abis)):

- `s1-kiosk-<version>.apk`, the `armeabi-v7a` APK the S1 runs, signed with the release key;
- `s1-kiosk-arm64-v8a-<version>.apk`, the same app for `arm64-v8a` devices;
- `s1-kiosk-<version>.apk.sha256` and `s1-kiosk-arm64-v8a-<version>.apk.sha256`, their `sha256sum` lines.

The libraries in `libs/` are released to Maven Central on their own tags: see [Libraries](#libraries). A few of
Kiln's packages go to npm the same way: see [npm packages](#npm-packages).

Every release **must be signed with the same key**. Android only installs an update signed with the
key of the installed app, and the app refuses anything else before trying. Lose the key and every
panel needs a manual reinstall, which clears its settings.

### The repo name

The repo started as `aguilaair/aqara-s1-unleashed`, moved to `GlassworksHQ/aqara-s1-unleashed`, and
was renamed to `GlassworksHQ/glassworks` on 2026-11-13. Panels on 0.4.3 and older ask
`aguilaair/aqara-s1-unleashed`, the name built into them, and reach the releases through GitHub's
redirects. A redirect only lasts while no repo exists at the old name, so **never create or reuse
either old name**, not even as an empty placeholder: it would cut off updates for every panel that
hasn't moved past 0.4.3.

## 1. Create the release key (once)

On your own computer, outside the repository. `keytool` comes with any JDK.

```sh
mkdir -p ~/secure && cd ~/secure
keytool -genkeypair -v \
  -keystore s1kiosk-release.jks -storetype PKCS12 \
  -alias s1kiosk -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=S1 Kiosk, O=aqara-s1-unleashed"
```

- `keytool` asks for the keystore password twice; it is not echoed and not stored in your shell
  history. Use a long random one from your password manager.
- PKCS12 keystores have one password for the store and the key, so the key password is the same.
- `-validity 10000` is about 27 years (the Android documentation's recommendation is at least 25).

Write down the certificate fingerprint; it is public, and lets anyone check a release:

```sh
keytool -list -v -keystore ~/secure/s1kiosk-release.jks -alias s1kiosk | grep SHA256
```

The fingerprint is also committed in
[`tools/release/release-cert.sha256`](../tools/release/release-cert.sha256) (lowercase hex, no
colons). The release and test-build workflows refuse an APK signed with any other key
([`tools/release/check-update-compat.sh`](../tools/release/check-update-compat.sh)), so a wrong or
swapped secret fails the build instead of shipping an update no panel can install. Rotating the key
means changing that file in the same PR as the secrets.

## 2. Keep it safe

- Store `s1kiosk-release.jks` and its password in your password manager, and keep a second copy
  offline (an encrypted USB stick).
- Never commit it. `.gitignore` ignores `*.jks` and `*.keystore`, but keep it outside the repo anyway.
- If it leaks, someone can sign updates your panels would accept, if they can also get them onto a
  panel. Rotate: make a new key, reinstall the panels by hand, and replace the secrets.
- The maintainer's password manager holds every release key (the S1 app's keystore, the generic
  app's keystore and the radio bundle key), each with its password, and an offline backup holds a
  copy. Check a saved copy byte for byte against the original before you rely on it.

## 3. Add the four secrets to GitHub (once)

Secrets of the **`release-signing` environment**, not repository secrets. A repository secret can be
read by any workflow that anyone with write access pushes, on any branch; an environment secret only
by jobs that run on what the environment allows. Here that's `main` and the `test-build/**` branches, and a ruleset
lets only admins create those branches (see *Who can sign*, below). Releases run on `main` only
([Release](#release)), so no tag needs to be let in.

Create the environment once (Settings → Environments → New environment → `release-signing`;
Deployment branches and tags → Selected: `main`, `test-build/**`), then set the secrets with the
[GitHub CLI](https://cli.github.com/). These commands read the values from the file or from a prompt, so nothing
ends up in your shell history:

```sh
cd ~/secure
# The keystore, base64-encoded, from the file:
base64 < s1kiosk-release.jks | gh secret set S1_KEYSTORE_B64 --env release-signing --repo GlassworksHQ/glassworks
# Passwords: gh prompts for them ("Paste your secret"); the same password twice for PKCS12.
gh secret set S1_KEYSTORE_PASSWORD --env release-signing --repo GlassworksHQ/glassworks
gh secret set S1_KEY_PASSWORD --env release-signing --repo GlassworksHQ/glassworks
# The alias is not secret:
gh secret set S1_KEY_ALIAS --env release-signing --repo GlassworksHQ/glassworks --body s1kiosk
gh secret list --env release-signing --repo GlassworksHQ/glassworks
```

The same `release-signing` environment also holds Glassworks' own key for the generic app and its legacy build
(`GENERIC_*`, since 2026-11-17), which `generic-test-build.yml` and the release's `build-apps` job use (see
[frames/generic-android/README.md](../frames/generic-android/README.md#signing)), and the tag deploy key the release's
`publish` job pushes tags with, `RELEASE_TAG_DEPLOY_KEY` ([Settings for the button](#settings-for-the-button-admin-once)).
The release workflow uses only these secrets, the radio bundle key below, and the jobs' own `GITHUB_TOKEN`.

### Who can sign

Anyone who can run a job in `release-signing` or `radio-signing` can sign code every panel accepts.
Rulesets (Settings → Rules) keep that to admins:

- **Release tags** (`v*`, `aqara-s1-plus-v*`, `generic-android-v*`, `generic-android-legacy-v*`, `frames/**`): only
  admins and the tag deploy key can create, move or delete them.
- **Test builds** (`test-build/**`): only admins can create, push to or delete these branches.
- **main**: changes go through a pull request with the `app`, `web`, `scripts` and `root` checks
  green; no force pushes or deletion. Admins can bypass, for emergencies.

The Release workflow runs only from its button, and its first job stops unless the person who pressed it is an
admin. Anyone with write access sees the button; a copy of the workflow on another branch gets no key, because the
environments admit only `main` and `test-build/**`. Pull requests from forks never get any secret, and no workflow
uses `pull_request_target`. `discord-releases.yml` uses `workflow_run`, runs the default branch's code only, and has
no key. `radio-signing` has a required reviewer: every bundle signing, dry runs too, waits for an approval. Before
anyone else gets admin, add yourself as a required reviewer on `release-signing` too.

## 3b. The radio bundle key (once)

Only the armeabi-v7a (S1) APK carries a **signed radio bundle**: the root radio helper, otbr-agent/ot-ctl
and the Silicon Labs Zigbee runtime ([radio.md](../frames/aqara-s1-plus/docs/radio.md#radio-bundles-helper-v4)). The other ABIs'
APKs carry none, because no S1 runs them. Panels whose boot image has the bundle
loader install it only if it is signed with the Ed25519 key whose public half is baked into the boot
image ([`frames/aqara-s1-plus/tools/root/glassworks_bundle.pub`](../frames/aqara-s1-plus/tools/root/glassworks_bundle.pub)). It is a separate key from
the APK key on purpose: it lets root code run on the panel, so it lives only in `~/secure` and in one
repository secret.

```sh
# Done once (2026-11-10): ~/secure/s1kiosk-radio-bundle-ed25519.pem, mode 600, never printed.
openssl genpkey -algorithm ed25519 -out ~/secure/s1kiosk-radio-bundle-ed25519.pem   # OpenSSL 3
openssl pkey -in ~/secure/s1kiosk-radio-bundle-ed25519.pem -pubout -outform DER | tail -c 32 | xxd -p -c 64 \
  > frames/aqara-s1-plus/tools/root/glassworks_bundle.pub
# The secret: an environment secret of the `radio-signing` environment (Settings -> Environments;
# create the environment first), so only the release workflow's signing job can read it:
gh secret set S1_RADIO_BUNDLE_KEY --env radio-signing --repo GlassworksHQ/glassworks \
  < ~/secure/s1kiosk-radio-bundle-ed25519.pem
```

- Only the release workflow's `sign-bundle` job names that environment. It signs only commits on
  `main` (`git merge-base --is-ancestor`), runs no third-party action (GitHub's own checkout and
  the artifact actions, pinned by commit SHA), and takes nothing from the unprivileged jobs
  without checking it: from the plan only the names of what to sign, every fact about them read from the commit
  (`tools/release/plan.py --item`); the Silicon Labs runtime against the committed
  [`frames/aqara-s1-plus/tools/radio/sisdk-runtime.sha256`](../frames/aqara-s1-plus/tools/radio/sisdk-runtime.sha256), otbr-agent/ot-ctl against
  `kiln/assets.lock.json`, the helper from the checkout. The environment has a required reviewer, so each
  signing waits for an approval.
- Keep no repository-level `S1_RADIO_BUNDLE_KEY`: other jobs would then see it.

- Back it up like the APK key. **Losing it** means no more radio bundle updates until panels get a boot
  image with a new public key (one FEL session each). **If it leaks**, someone who can also get a file
  into the kiosk's private storage could run root code: rotate it (new key, new
  `glassworks_bundle.pub`, new boot image on every panel).
- The bundle version is the APK's versionCode, and panels refuse a lower one than they have, so a
  bundle can't be rolled back to an older (perhaps buggy) helper by replaying it.

## 3c. Web installer files

The installer's Vercel build downloads its binaries from pinned URLs ([`kiln/assets.lock.json`](../kiln/assets.lock.json)).
The boot image's signature checker is one of them since the radio bundle loader:

```sh
frames/aqara-s1-plus/tools/root/build-verify.sh      # reproducible; must print sha256 6aa0dd83…9f80 (the lock's pin)
gh release upload installer-files-3 frames/aqara-s1-plus/tools/root/out/s1kiosk_verify --repo GlassworksHQ/glassworks
```

(Create the `installer-files-3` release first if it doesn't exist, e.g. as a copy of
`installer-files-2` plus this file.) Until it is uploaded, the hosted installer's build fails at
`stage-assets.mjs --remote` with a download error; local builds use `frames/aqara-s1-plus/tools/root/out/` directly.

## Release

One button releases everything: **Actions → Release → Run workflow**, on `main`, with a version. That is the only
way a release starts. No workflow runs on a tag push, so a tag pushed by hand publishes nothing.

| Input | What |
|---|---|
| `version` | `MAJOR.MINOR.PATCH`, e.g. `1.0.0`. One number for the run: every line and frame whose changelog has a `## [1.0.0]` section is due |
| `what` | `all` (the default): everything due. Or some of it, comma or space separated: `aqara-s1-plus` (its app line and its frame bundle), `aqara-s1-plus:app`, `aqara-s1-plus:frame`, `generic-android` (both of its lines), `google-pixel-tablet:app` (the generic line), `lululemon-mirror:app` (the legacy line), `lululemon-mirror:frame`, or a line by name (`generic-android-legacy`) |
| `channel` | `stable` or `beta` ([Betas](#betas)) |
| `dry_run` | On by default: build, sign and check everything, publish nothing. Turn it off to publish |

```sh
gh workflow run release.yml --repo GlassworksHQ/glassworks --ref main -f version=1.0.0                    # dry run
gh workflow run release.yml --repo GlassworksHQ/glassworks --ref main -f version=1.0.0 -f dry_run=false   # publish
gh run list --repo GlassworksHQ/glassworks --workflow release.yml --limit 1                                 # its run
```

### What ships

The frames' data says it, not the workflow: each frame's `release` block in `frames/<id>/design/frame.json`
([frames/README.md](../frames/README.md#release)) names the app lines its devices run and its own frame release.
`tools/release/plan.py` turns that into the run's plan. An app line several frames run is built once.

| Line or frame release | Tag | Files | Notes from | Run by |
|---|---|---|---|---|
| The S1 app | `aqara-s1-plus-v1.2.3` | `s1-kiosk-<version>.apk`, `s1-kiosk-arm64-v8a-<version>.apk` | `CHANGELOG.md` | `aqara-s1-plus` |
| Glassworks for generic Android | `generic-android-v1.2.3` | `glassworks-generic-android-<abi>-<version>.apk` | `frames/generic-android/CHANGELOG.md` | `generic-android`, `google-pixel-tablet` |
| Its legacy build | `generic-android-legacy-v1.2.3` | `glassworks-generic-android-legacy-<abi>-<version>.apk` | `frames/generic-android/CHANGELOG.md` | `generic-android`, `lululemon-mirror` |
| The S1 frame | `frames/aqara-s1-plus/v1.2.3` | `frame-bundle-aqara-s1-plus-<version>.zip` | `frames/aqara-s1-plus/FRAME-CHANGELOG.md` | |
| The Mirror frame | `frames/lululemon-mirror/v1.2.3` | none: a notice | `frames/lululemon-mirror/FRAME-CHANGELOG.md` | |

Each file comes with its `.sha256`. The tags and file names are the ones the apps' update checks (`ReleaseLine`,
`FrameOffer` in `core/.../update/`), Kiln and Refract read, so they never change. `tools/release/test`
checks the plan's names against `Releases.kt`'s, `release.yml`'s `apk_name` and Kiln's release patterns.

A new frame on an existing line needs only its `release` block: a tested device names its line (`"app":
["generic-android"]`), a device frame with a frame version adds `"frame": {"bundle": "notice", ...}` and a
`FRAME-CHANGELOG.md`. The workflow stays as it is.

### Cut a release

1. In each changelog that changes, move the *Unreleased* items into `## [1.0.0] - <date>` and add a new empty
   `## [Unreleased]` above it. The first line of each section is what Home Assistant shows on the update card, so
   make it a one-sentence summary. The generic app and its legacy build share `frames/generic-android/CHANGELOG.md`
   and one version sequence: one section is the notes of both. Merge that to `main`.
2. Dry run it: `-f version=1.0.0`, `dry_run` on. Read the plan in the run's summary (below) and the build
   artifacts.
3. Run it again with `dry_run` off.

A line whose changelog has no section for the version is skipped, so a version can be an S1 app release alone, or
the app lines and not the frames. Versions only go up per line, and the plan refuses a version that isn't higher
than the line's last release or beta, so after 1.0.0 the S1 app may go to 1.0.1 while the generic line waits for
1.0.2. A line already released at the version is skipped.

**An S1-only update** is `what: aqara-s1-plus` (its app and its frame bundle, each only if its changelog has the
section, else the run fails: a line you name must be due), or `aqara-s1-plus:app` for the app alone.
**`lululemon-mirror:app` is the generic legacy line**, which every other frame on that line gets too: the plan
warns in the summary.

Panels see a release within 24 hours. To check right away, call `homeassistant.update_entity` on the update
entity (`update.s1_panel_app_update` on an old S1): HA sends the entity's "check" command. Every Kiln page
(kiln.glassworks.dev), the S1's included, reads the releases when someone installs (`/api/release/<line>/latest`) and
downloads the APK through Refract (refract.glassworks.dev), so a release needs no Kiln or Refract deploy. A new release
line does: see below.
s1.edm.sh, the first installer's address, redirects to Kiln's S1 page (`kiln/s1-redirect`).

### The plan

The run's first job writes the plan to its summary: one row per app line and frame release, its version, its tag,
and whether it is built and published or skipped, and why; then each item's notes, and any warnings. In a dry run,
that table and the artifacts are the whole output.

For 1.0.0, once every changelog has its `[1.0.0]` section:

| Line or frame | Kind | Version | Tag | Plan |
|---|---|---|---|---|
| `aqara-s1-plus` | app line (s1-kiosk), for aqara-s1-plus | 1.0.0 | `aqara-s1-plus-v1.0.0` | **build, check; publish** (latest) |
| `generic-android` | app line (gradle), for generic-android, google-pixel-tablet | 1.0.0 | `generic-android-v1.0.0` | **build, check; publish** |
| `generic-android-legacy` | app line (gradle), for generic-android, lululemon-mirror | 1.0.0 | `generic-android-legacy-v1.0.0` | **build, check; publish** |
| `aqara-s1-plus` | frame release (signed) | 1.0.0 | `frames/aqara-s1-plus/v1.0.0` | **build, check, sign the bundle; publish** |
| `lululemon-mirror` | frame release (notice) | 1.0.0 | `frames/lululemon-mirror/v1.0.0` | **notes only; publish** |

The S1 frame's row carries a note: panels with the frame loader install the frame bundle zip from Home Assistant's
Frame update card and restart once. A 0.4.x panel gets the loader first, from the S1 app's radio bundle (the boot
writer).

### Dry run

A dry run builds, signs and checks exactly what the release would publish, keeps the files as artifacts for 7 days
(`line-<line>` and `frame-bundles`), and publishes nothing:

```sh
gh workflow run release.yml --repo GlassworksHQ/glassworks --ref main -f version=1.0.0
gh run download <run-id> --repo GlassworksHQ/glassworks -n line-generic-android-legacy
```

A dry run before the version's sections exist builds every line and frame anyway, with the *Unreleased* notes (a
placeholder while *Unreleased* is empty), and warns that the real run would skip each of them. So the whole
release, the S1 frame bundle included, can be tried on `main` before any section is cut. Run a dry run on `main`:
the signing job signs only commits on `main`, and `radio-signing` admits nothing else. On a `test-build/**` branch
only the generic lines can run (`what: generic-android`).

### What the jobs do

1. **`plan`** (no secrets): stops unless the person who pressed the button is an admin, and, for a real release,
   unless it runs on `main`. Reads the frames' data and the releases that exist, works out the plan, refuses a
   version that isn't higher than a due line's last release or beta (`tools/release/check-newer-than-released.sh`)
   and a named item that isn't due, and writes the summary.
2. **`inputs`** (no secrets), when an S1 bundle is due: builds the Silicon Labs runtime
   (`frames/aqara-s1-plus/tools/radio/build-sisdk-runtime.sh`, Docker, a pinned image and Ubuntu snapshot; it must
   reproduce `frames/aqara-s1-plus/tools/radio/sisdk-runtime.sha256`; never Silicon Labs' zigbeed itself). PR CI
   catches most drift first: the `size` job in `pr.yml` caches the runtime under a key made from the script,
   `frames/aqara-s1-plus/tools/radio/sisdk/` and the hash file, and rebuilds it when one of them changes. Drift
   outside the repo (the Ubuntu snapshot, the cpc-daemon tag) still shows first in a release's fresh build.
3. **`sign-bundle`** (environment `radio-signing`, [3b](#3b-the-radio-bundle-key-once)): signs the S1 app's radio
   bundle (`make-bundle.py --app-version`, version = the versionCode, checked against
   `frames/aqara-s1-plus/tools/root/glassworks_bundle.pub`) and each signed frame bundle (its `script` from the
   frame's data, `frame-bundle.sh` for the S1). Main only, every input checked against committed hashes.
4. **`build-kiosk`** (environment `release-signing`, the S1 app key): `:kiosk:assembleRelease` with `S1_VERSION`
   once per ABI, the `armeabi-v7a` one with the radio bundle as APK assets, `S1_REQUIRE_RELEASE_SIGNING=true`.
   Then `apksigner verify --print-certs` (the certificate fingerprint is in the log),
   `tools/release/check-update-compat.sh --signed`, the package, version and native code ABI, the signed bundle in
   the `armeabi-v7a` APK and in no other, no zigbeed anywhere, a `.sha256` each, and exactly the files the data
   names.
5. **`build-apps`** (environment `release-signing`, Glassworks' generic key), one job per other app line: its Gradle
   task with `-PgenericVersion` once per ABI of its frame, `GENERIC_REQUIRE_RELEASE_SIGNING=true`. Then
   `apksigner verify`, `check-update-compat.sh --app <line> --signed` (Glassworks' certificate, the applicationId,
   no `sharedUserId`, the components named from outside the APK), the package, versionName and versionCode, the
   minSdk (26, or 23 for legacy), its ABI's GeckoView, the system permissions the Mirror needs, `WRITE_SETTINGS` in
   the legacy build only, a `.sha256` each, and exactly the files the data names.
6. **`publish`** (not on a dry run; `main` only; after every part the plan needs passed; one run at a time for every
   line, `tools/release/publish.sh`): first checks every item and changes nothing: its files match the plan and
   their `.sha256`, its notes are the version's changelog section, its version is still higher than its line's last
   release or beta (another run may have published since), and a tag that exists already names this run's commit.
   Then, item by item, pushes the tag over SSH with the tag deploy key and creates the release with every file in one
   `gh release create`, so a release never has half its ABIs. A line marked `latest` in its frame's data gets
   `--latest` on a stable; everything else is `--latest=false`. No line is marked until 1.0.0, so `releases/latest`
   stays `v0.4.4`, the one release panels up to 0.4.3 can read ([Tags](#tags)).

The radio bundle and the frame bundle are not published on their own (the Silicon Labs libcpc may only be
distributed inside the product): the radio bundle is inside the `armeabi-v7a` APK, the frame bundle only in its zip.
HA shows the first 255 characters of the notes, with a link to the full release.

### If a run fails

- **Before `publish`:** nothing is published. Fix the cause and run the button again; or, for a failure outside
  the repo (a download, an approval that timed out), "Re-run failed jobs" within 7 days, while the artifacts last.
- **In `publish`:** "Re-run failed jobs" reuses this run's files. A release that already has exactly these files is
  left as it is, a draft from an upload that stopped is deleted (no one saw it) and made again, and the rest are
  made. After 7 days, run the button again: a new run's frame bundle isn't the same bytes
  (`hotspot-name/build.sh` signs the overlay with a key made for each build), so a published release with other
  files fails the run. Never replace a published release's files; panels may have them. Fix a broken release with a
  higher version.
- **A tag on another commit** (pushed by hand, or a run on an older `main`) stops `publish` before it changes
  anything. Delete the tag if no release uses it, or release a higher version.

**Panels on 0.4.0 or older can't install an update from HA.** Their update path refused every release as
"the APK is not signed": on Android 10, `getPackageArchiveInfo` with only `GET_SIGNING_CERTIFICATES`
returns no signer for a downloaded APK. 0.4.1 also asks for `GET_SIGNATURES`
([esphome.md](esphome.md#app-updates)). Because the bug is in the version doing the installing, those
panels need one manual update to 0.4.1 or later (the web installer's *Already installed? Finish setting
it up*, or `adb install -r`, see [install.md](../frames/aqara-s1-plus/docs/install.md#updating)); from then on HA installs work.

The Vercel projects have no Git connection, so pushes and PRs start no Vercel deployment (each one counted against
the free plan's daily deploy limit, even cancelled). Kiln, glassworks.dev and the s1.edm.sh redirect deploy from
[`web-deploy.yml`](../.github/workflows/web-deploy.yml), on a push to `main` or every 6 hours when what each one
builds from changed since its last successful deploy, or by hand from the Actions tab. A deploy that failed at the
limit goes out again on the next push or schedule; a running deploy is never cancelled, because a newer one waits
for it. `tools/ci/web_deploy.py` decides. Each job records its upload as a GitHub deployment (task `web-deploy`) of the commit it
checked out, in the project's environment, and marks it a success once Vercel takes it. The next run diffs from
that commit. When a newer record never reached success, the next run deploys anyway, because what's live is
unknown. Each run's summary lists what it deployed, from which commit, and why.

Refract (refract.glassworks.dev), the Cloudflare Worker Kiln downloads every APK and image through, deploys from the
same workflow, to Cloudflare, when its code, allowlist or release lines changed. Kiln's job waits for it, and Kiln
doesn't deploy a commit whose Refract files changed since Refract's last successful deploy (when that deploy failed,
or main moved on in between), so a Kiln that names a new download never goes live before Refract serves it. Running
the workflow by hand for Kiln deploys Refract first when it needs it ([its README](../kiln/workers/refract/README.md#deploying)).

Versions are `MAJOR.MINOR.PATCH`. For the app lines minor and patch are below 100, and the versionCode is
`major*10000 + minor*100 + patch`, so `1.2.3` is 10203. Frame versions take each part up to 999. SemVer pre-release
tags (`1.2.3-rc.1`) are refused, and the apps would ignore them anyway: a beta is a plain version, published as a
GitHub pre-release ([Betas](#betas)).

### ABIs

The app is built once per ABI, because each GeckoView AAR is for one ABI and carries its own classes
and `omni.ja`, so two cannot go into one APK. The release builds one APK for every ABI that some
frame's device runs: each frame lists its ABIs in `abis` in `frames/<id>/design/frame.json`
([frames/README.md](../frames/README.md#designframejson)). `tools/release/frame-abis.sh` prints that
list for the workflows. `-Ps1Abi=<abi>` (or `S1_ABI`) picks the ABI; without it the build is the S1's
first ABI, `armeabi-v7a`:

```sh
./gradlew :kiosk:assembleRelease                      # armeabi-v7a
./gradlew :kiosk:assembleRelease -Ps1Abi=arm64-v8a    # arm64-v8a
```

| ABI | Release asset | Radio bundle | Who runs it |
|---|---|---|---|
| `armeabi-v7a` | `s1-kiosk-<version>.apk` | yes | the S1 (32-bit userspace), other 32-bit devices |
| `arm64-v8a` | `s1-kiosk-arm64-v8a-<version>.apk` | no (the bundle is the S1's root side) | arm64 tablets (generic-android), the arm64 emulator. Not the Mirror (Android 6): The S1 app needs API 26, so Kiln installs the legacy build of Glassworks for generic Android there (#198, #411) |

- **Every ABI's APK has the same versionCode**, `major*10000 + minor*100 + patch`. Store schemes put
  the ABI in the versionCode because a store must choose between APKs. Here the app chooses: it
  takes the APK of the first ABI in `Build.SUPPORTED_ABIS` (the device's order of preference) that
  the release has (`update/Releases.kt`, `ApkAbi`). So versions go up per ABI exactly as they did
  before, and `ApkCheck` still wants the versionCode of the release's version.
- **The S1's asset name never changes.** Apps up to 0.4.3 and the web installer only know
  `s1-kiosk-<version>.apk`. An S1 lists `armeabi-v7a, armeabi`, so it keeps taking that file.
- An arm64 device that also runs 32-bit code falls back to `s1-kiosk-<version>.apk` for releases
  that have no arm64 APK (all releases up to 0.4.3). An arm64-only device skips them.
- An ABI counts only when the release has both its APK and its `.sha256`. Half a pair is skipped
  for the device's next ABI.
- **Known gap, rare.** If a release ever had only `s1-kiosk-<version>.apk`, an arm64
  device that runs 32-bit code and has the arm64 APK installed would update to the 32-bit APK. That
  moves the app to slower 32-bit code until a release has an arm64 APK again. `release.yml` creates
  the release only after every ABI's APK is built and checked, and uploads them all in one
  `gh release create`, so a release has all of them unless an upload fails partway. Keep it that
  way, or make the app prefer the ABI it is running.
- A device whose ABIs the app is not built for (x86_64) gets no offer, and the update entity says why.
- Adding an ABI means adding it to a frame's `abis`, to `ApkAbi`, to `apk_name` in `release.yml` and
  `test-build.yml` if its name differs, to the line's `assets` in its frame's `release` data, and an upload step in `test-build.yml`. Gradle and the workflows' loops take
  the list from the frames. `ApkAbiListsTest` fails if the app and the frames disagree.

### Tags

Each app line, frame and library releases under its own tag prefix, and the S1 app's is `aqara-s1-plus-` (the board id it reports, since other S1
models may get their own frame). From 0.4.4 the app accepts both `v1.2.3` and
`aqara-s1-plus-v1.2.3` and picks its own newest release from the list, whatever else was released
after it. Apps up to **0.4.3** only read `releases/latest` and only understand `v1.2.3`.

- `v0.4.4` is the last plain tag. Every S1 release after it is tagged `aqara-s1-plus-v1.2.3` (maintainer,
  2026-11-19).
- **`v0.4.4` stays `releases/latest` until 1.0.0** (maintainer, 2026-11-23): the S1 line's `latest` is `false`
  in `frames/aqara-s1-plus/design/frame.json`, so the releases before 1.0.0 don't take it. A panel on 0.4.3 is
  offered 0.4.4, and once on 0.4.4 it reads the whole list and is offered the newest S1 release: two updates from
  Home Assistant, no reinstall. 0.4.3 doesn't update by itself, so this gives its owners until 1.0.0 to press
  Install. GitHub's page shows 0.4.4 as *Latest* meanwhile; nothing else reads it (Kiln, the site and the apps from
  0.4.4 on pick from the list).
- **1.0.0 sets `latest` back to `true`**, in the same PR as its changelog sections, and is published as
  `releases/latest`. That PR also changes `test_no_line_is_releases_latest_while_v0_4_4_holds_it` in
  `tools/release/test/test_release_plan.py` to expect the S1 line (the other tests read the flag from the data). A panel still on 0.4.3 after that sees no update, and needs Kiln or `adb install -r`.
- The app's blueprint links point at its own release's tag, which `ReleaseLine.tag` builds
  (`aqara-s1-plus-v1.2.3` on the S1). A release tagged any other way gets links to files that aren't
  there ([Blueprints](blueprints.md#which-version-you-get)). The generic app and its legacy build do
  the same with their own tags.
- Every other frame's or library's release is published with `--latest=false`, so `releases/latest` stays an S1
  release.

First add a `## [1.0.0] - <date>` section to [CHANGELOG.md](../CHANGELOG.md) (move the

## The S1 frame bundle

The S1's frame version (#139) is released on its own tag, `frames/aqara-s1-plus/v<version>`, with semantic
versioning from 1.0.0 (each part 0–999), by the same button: `what: aqara-s1-plus:frame`, or `all` when
`frames/aqara-s1-plus/FRAME-CHANGELOG.md` has the version's section. The `sign-bundle` job builds it with
`frames/aqara-s1-plus/tools/radio/frame-bundle.sh`, the same script the shell tests install through the loader:

- **What it holds:** the service scripts (the frame agent, netwatch, first boot, app updates), otbr-agent and ot-ctl (the
  pinned builds in `kiln/assets.lock.json`), the setup hotspot's name overlay (`hotspot-name.apk`, built in the signing
  job with the runner's Android SDK, as for the radio bundle), the Silicon Labs Zigbee runtime (checked against
  `frames/aqara-s1-plus/tools/radio/sisdk-runtime.sha256`; never zigbeed itself) and the boot animation.
- **The key:** the radio bundle's (`S1_RADIO_BUNDLE_KEY`, [3b](#3b-the-radio-bundle-key-once)), whose public half
  boot images carry as `glassworks_bundle.pub`. The signing job only signs commits on `main`.
- **The release:** `frame-bundle-aqara-s1-plus-<version>.zip` and its `.sha256`, published with
  `--latest=false`, with notes from that version's section of `frames/aqara-s1-plus/FRAME-CHANGELOG.md`. The frame
  bundle's version only goes up: panels refuse a lower one for good, and the plan refuses a version that isn't
  higher than the last frame release.
- **How panels install it.** On a panel with the frame loader, Install on the Frame update card (or on the Device
  page) downloads the zip, checks its SHA-256 and signature, stages it and restarts the panel once to load it. A
  bundle the panel refuses is shown on the Device page, on Manage and in Home Assistant. A 0.4.x panel has no
  loader yet: the boot writer in the S1 app's radio bundle puts it into the panel's boot image first.

The manifest's "created" is the commit's time, so a rebuild gives the same bytes but for `hotspot-name.apk`, which
`hotspot-name/build.sh` signs with a key made for each build ([If a run fails](#if-a-run-fails)).

## Betas

Every app has an **Update channel** on its Device page and on the Manage page (#605): Stable, the default, or Beta.
Stable takes releases only. Beta also takes GitHub pre-releases of its own line, and offers whichever version is
highest. A lower version is never offered, so a panel that goes back to Stable keeps its beta until a stable release
is higher.

**A beta is an ordinary higher version, published as a GitHub pre-release.** It is the same tag, the same versionCode
and the same APK a stable would be. Promoting it un-marks the pre-release on that release, so nothing is rebuilt and
panels on Beta that already run it see no update.

### Cut a beta

Write the version's changelog section as for any release (its notes are the update card). Then press the button with
`channel: beta`:

```sh
gh workflow run release.yml --repo GlassworksHQ/glassworks --ref main -f version=0.6.0 -f channel=beta -f what=aqara-s1-plus:app
```

The workflow builds and checks everything as usual, and publishes each app line with `--prerelease` and
`--latest=false`, " beta" after the title. `releases/latest` doesn't move, and Kiln, which installs
stable releases only, doesn't offer it. **Frame releases have no betas** (the apps' frame check skips pre-releases):
a beta run skips them, and fails if you name one.

Versions keep going up across both channels: `tools/release/check-newer-than-released.sh` compares a new version with
the line's releases **and** pre-releases, so a stable can't go out below a published beta. After beta 0.6.0, the next
stable is 0.6.0 promoted, or 0.6.1 and up.

### Promote a beta

```sh
gh release edit aqara-s1-plus-v0.6.0 --repo GlassworksHQ/glassworks --prerelease=false --latest=false \
  --title "Glassworks for the Aqara S1 Plus 0.6.0"
gh release edit generic-android-legacy-v0.3.0 --repo GlassworksHQ/glassworks --prerelease=false --latest=false \
  --title "Glassworks for generic Android, legacy build 0.3.0"
```

`--latest=false` for every line while `v0.4.4` holds `releases/latest` (until 1.0.0, [Tags](#tags)); after that,
`--latest` for the S1 app only, as when it is published. Panels on Stable
see it at their next check. A beta with a bug is fixed by a higher version, beta or stable: panels that run it are
never offered anything lower.

## Glassworks for generic Android and its legacy build

Two more apps release from this repo, each on its own tag and asset names, which are the ones its update check
reads (`ReleaseLine` in `core/.../update/Releases.kt`). So no app ever offers itself another app's APK:

| App | applicationId | Tag | Assets, one pair per ABI in `frames/generic-android/design/frame.json` |
|---|---|---|---|
| Glassworks for generic Android (Android 8+) | `dev.glassworks.generic_android` | `generic-android-v1.2.3` | `glassworks-generic-android-<abi>-1.2.3.apk` and its `.sha256` |
| Its legacy build (Android 6-7, the Mirror) | `dev.glassworks.generic_android.legacy` | `generic-android-legacy-v1.2.3` | `glassworks-generic-android-legacy-<abi>-1.2.3.apk` and its `.sha256` |

The release's `build-apps` job builds them, one job per line, from the facts in `frames/generic-android/design/frame.json`
(`release.lines`). Both are signed with Glassworks' own key, never the S1 app's
([frames/generic-android/README.md](../frames/generic-android/README.md#signing)), whose certificate is in
[`tools/release/generic-release-cert.sha256`](../tools/release/generic-release-cert.sha256). Both are published with
`--latest=false`, so `releases/latest` stays the S1 app's.

**Release notes.** Both builds share [`frames/generic-android/CHANGELOG.md`](../frames/generic-android/CHANGELOG.md)
and one version sequence: the section `## [1.2.3] - <date>` is the notes of `generic-android-v1.2.3` and of
`generic-android-legacy-v1.2.3`. With `what: all` both lines are due once it has the section. To release one alone,
name it: `google-pixel-tablet:app` is the generic line, `lululemon-mirror:app` the legacy one. Release the other later
on that same section (add a line to it if the second app changed), so the file never has two `[1.2.3]` headings. The
repo's `CHANGELOG.md` stays the S1 app's. A change that people running both apps would notice, a core change for
example, gets a line in each file.

**Versions.** As for the S1 app. Test builds of the generic app are 0.1.0 and 0.1.1, and a device on test build 0.1.1
is offered only a higher version (and `adb install -r` refuses a lower versionCode).

Refract ([`kiln/workers/refract`](../kiln/workers/refract/README.md#glassworks-own-releases)) serves each line's
APKs and their `.sha256` to Kiln, by the line's tag pattern and file names in its `releases.json`. The Mirror's page
and `/android`, behind `?apk=release`, install the newest release of their line (`generic-android-legacy-v*` and
`generic-android-v*`), the APK for the device's ABI, checked against that `.sha256`. A new line, or a new file name
on a line, is a change to the frame's `installer.json` `releases`: write Refract's `releases.json` again
(`REFRACT_RELEASES=write npm test -w @glassworks/kiln-app -- refract-releases`) and deploy Refract after the merge.

## Frame releases

A frame version is the version of the parts of a device that Glassworks owns (#139): the S1's boot-side services,
scripts and boot animation, or the Mirror's patched image. It is separate from the app's version, and uses semantic
versioning from 1.0.0. The S1 frame 1.0.0 and the Mirror frame 1.0.0 come out with Glassworks 1.0.0. Each device
carries its frame version in `ro.glassworks.frame.id` and `ro.glassworks.frame.version`, and the app's update check
compares it with the frame's newest release ([esphome.md](esphome.md#frame-updates)).

| Frame | Tag | Release | Who sets the version on the device |
|---|---|---|---|
| Aqara S1 Plus | `frames/aqara-s1-plus/v1.2.3` | The signed frame bundle ([above](#the-s1-frame-bundle)) | The loader in its boot image, at boot |
| lululemon Mirror | `frames/lululemon-mirror/v1.2.3` | A notice: notes, no files | Kiln, in `build.prop`, when it flashes the Mirror (`MIRROR_FRAME` in `frames/lululemon-mirror/installer/src/image.ts`) |

The tag is `frames/<frame id>/v<version>`, the frame's folder in this repo, like a library's `libs/<name>/v<version>`.
Nothing else matches it: the S1 app's `v*` and `aqara-s1-plus-v*`, the generic app's `generic-android-*`, Refract's
release lines and `stage-assets.mjs` all skip it, and `FrameUpdateTest` checks that the app's own update check does.

- **Published with `--latest=false`**, like every release that isn't the S1 app's: panels on 0.4.3 read only
  `releases/latest`.
- **No pre-releases.** The check skips drafts, GitHub pre-releases and SemVer pre-release tags (`v1.1.0-rc.1`).
- **The notes are the update card.** Home Assistant shows how to install it first, then the notes' first
  paragraph, headings and bullets, up to 255 characters (`Releases.summary`). Start them with a one-line summary.
- **The Mirror's notice** comes from `frames/lululemon-mirror/FRAME-CHANGELOG.md`, released with
  `what: lululemon-mirror:frame` or `all`. Kiln's `MIRROR_FRAME.version` must be the release's version, so freshly
  flashed Mirrors say the version the release names: bump it in the change that cuts the section. The plan fails a
  Mirror frame release at any other version (`versionFile` in its frame's data); a dry run on the Unreleased notes
  only warns.

Panels see it within 24 hours, or at once through `homeassistant.update_entity` on the Frame update entity. No
frame release exists before 1.0.0, so until then every device shows its frame version and no update.

A local clone may still have a `frames/aqara-s1-plus/v0.5.0` tag. It belongs to a temporary 0.5.0 release made to
test Frame update on the dev S1 (2026-11-21); that release and its tag on GitHub were deleted after the run, so
GitHub has neither. The Release workflow reads GitHub's tags and releases, so a stale local tag changes nothing.
To drop it from a clone: `git tag -d frames/aqara-s1-plus/v0.5.0`.

## Settings for the button (admin, once)

The workflow needs three things from the repository's settings. Make them in this order, so nothing is ever more
open than now. Check each one after.

**1. The tag deploy key.** The `publish` job pushes the release tags over SSH with a deploy key, because the release
tags ruleset lets only admins and deploy keys create them, and GitHub refuses GitHub Actions as a bypass actor
there. Make the key on your own computer, give GitHub its public half with write access, and put the private half
in `release-signing` only:

```sh
ssh-keygen -t ed25519 -N '' -C "Glassworks release tags" -f ~/secure/glassworks-release-tags
gh repo deploy-key add ~/secure/glassworks-release-tags.pub --repo GlassworksHQ/glassworks \
  --allow-write --title "Release tags (release.yml publish)"
gh secret set RELEASE_TAG_DEPLOY_KEY --env release-signing --repo GlassworksHQ/glassworks \
  < ~/secure/glassworks-release-tags
rm ~/secure/glassworks-release-tags   # keep it in the password manager instead, or make a new one when needed
gh api repos/GlassworksHQ/glassworks/keys --jq '.[] | "\(.id) \(.title) read_only=\(.read_only)"'
```

- Only `publish` reads it, and only on `main`: `release-signing` admits `main` and `test-build/**`, and only admins
  can push `test-build/**`.
- A write deploy key can push to any branch a ruleset doesn't guard, and the tags ruleset lets *every* deploy key
  bypass it (`actor_id: null`). So keep this one the repo's only deploy key with write access.
- If it leaks: delete it (`gh repo deploy-key delete <id>`), make a new one, and set the secret again. Check the
  release tags for any you didn't make.

**2. The ruleset.** "Release tags: admins only" (id 27082980) already covers every release tag and has the deploy
key bypass (added 2026-11-21). To check it:

```sh
gh api repos/GlassworksHQ/glassworks/rulesets/27082980 \
  --jq '{enforcement, bypass: [.bypass_actors[] | "\(.actor_type) \(.actor_id) \(.bypass_mode)"], include: .conditions.ref_name.include}'
# enforcement active; bypass "DeployKey null always" and "RepositoryRole 5 always" (admins);
# include refs/tags/aqara-s1-plus-v*, frames/**, generic-android-legacy-v*, generic-android-v*, v*
```

**3. The environments: `main` (and test builds) only.** With no tag workflow left, the tag patterns in the two
environments let nothing in that needs to be, and each would let a tag run reach a key. Remove them, so
`release-signing` admits `main` and `test-build/**`, and `radio-signing` `main` alone:

```sh
(
  set -euo pipefail
  repo=repos/GlassworksHQ/glassworks
  for env in release-signing radio-signing; do
    gh api "$repo/environments/$env/deployment-branch-policies" --jq '.branch_policies[] | select(.type == "tag") | .id' |
      while read -r id; do gh api -X DELETE "$repo/environments/$env/deployment-branch-policies/$id"; done
    echo "$env:"; gh api "$repo/environments/$env/deployment-branch-policies" --jq '.branch_policies[] | "  \(.type) \(.name)"'
  done
)
# release-signing: branch main, branch test-build/**    radio-signing: branch main
```

Do it after this workflow is on `main` and the old tag workflows are gone (they are, in the same change). Keep
`radio-signing`'s required reviewer: each signing waits for an approval, dry runs too.

## Discord

Every published release is posted to the Glassworks Discord server by
`.github/workflows/discord-releases.yml` (`tools/ci/discord_release.py`). Nothing to do when you release.

| Tags | Channel | Secret |
|---|---|---|
| `libs/*` | `#ecosystem-updates` | `DISCORD_ECOSYSTEM_WEBHOOK` |
| Everything else: `v*`, `aqara-s1-plus-v*`, `generic-android-*`, `frames/*` | `#glassworks-releases` | `DISCORD_RELEASES_WEBHOOK` |

- It runs after a Release run succeeds, and posts each tag its `publish` job lists in the run's `published-tags`
  artifact (a dry run lists none); after the Library release workflow succeeds on a tag push; and when a person
  publishes a release (a library's draft). Drafts and pre-releases are skipped.
- The post is the release's title, its notes up to 1,500 characters, and a link to the rest. It never pings anyone.
- Each secret is a webhook URL of one channel (Channel settings, Integrations, Webhooks). The URL lets anyone
  post to that channel: keep it in the repo's Actions secrets only. If one leaks, delete the webhook, make a new
  one and run `gh secret set DISCORD_RELEASES_WEBHOOK -R GlassworksHQ/glassworks` with the new URL.
- With no secret set, the job prints "not posted" and passes.

## Raising targetSdk

`targetSdk` is in `frames/aqara-s1-plus/android/build.gradle.kts`, and only there. It opts the app
into newer Android rules on newer devices. It doesn't change which devices can install the app:
`minSdk` (26) decides that.

Raise it once a year, when a new Android version comes out. Generic Android devices on Android 12+
install updates without a tap only if the new APK targets a recent enough API, and each Android
version raises that bar by one level (Android 17 needs 35;
[frames/generic-android](../frames/generic-android/README.md#silent-self-update-android-12)).

1. Read Android's "behaviour changes for apps targeting" page for the new level, and note in the PR
   which items apply to the app and how each is handled.
2. Raise `targetSdk` by one, in its own PR.
3. Check it on the newest device we have (today the Pixel Tablet): the setup screen, the settings,
   the overlays and the dashboard are not under the system bars, the foreground service starts after
   a reboot, and an update from HA installs without a tap. Before/after screenshots go in the PR.
4. `tools/release/check-update-compat.sh` must still pass: the target doesn't touch applicationId,
   signing or versionCode.

| Date | targetSdk | Why | PR |
|---|---|---|---|
| 2026-11-15 | 34 → 35 | Android 17 (the Pixel Tablet) installs updates without a tap | #277 |

## The secret store and downgrades

Since the secret store (2026-11-15, [secrets.md](secrets.md)), the app seals its secrets with a key in
the Android Keystore. An update seals them in place and keeps every one. **A downgrade doesn't**: a
build from before the secret store reads a sealed value as a broken one. The panel then needs setting
up again: the Home Assistant sign-in (QR code), the ESPHome API key, the Sendspin pairing and the
camera password. `main` isn't a release, so this only matters when you install an older test build
over a newer one. Don't add a path that writes plaintext back for older builds.

## Building a signed APK locally

The same environment variables as CI. Without them, `assembleRelease` produces an **unsigned** APK
(`kiosk-release-unsigned.apk`); it never falls back to the debug key.

```sh
export S1_KEYSTORE_FILE=~/secure/s1kiosk-release.jks S1_KEY_ALIAS=s1kiosk S1_VERSION=1.0.0
read -rs S1_KEYSTORE_PASSWORD && export S1_KEYSTORE_PASSWORD S1_KEY_PASSWORD="$S1_KEYSTORE_PASSWORD"
./gradlew :kiosk:assembleRelease
```

If only some of the four variables are set, the build stops and names the missing ones.

## Signed test builds of a branch

To try a branch on a panel that runs a release, without losing the app's data, let CI sign it:

```sh
git push origin HEAD:test-build/<name>          # builds as the latest release's version
git push origin HEAD:test-build/v1.2.3/<name>   # builds as 1.2.3 (panel on an unreleased version)
gh run download <run-id> -n s1-kiosk-test-<sha>              # armeabi-v7a, for the S1
adb install -r s1-kiosk-test-*.apk
gh run download <run-id> -n s1-kiosk-test-arm64-v8a-<sha>    # arm64-v8a, its own artifact
```

[`test-build.yml`](../.github/workflows/test-build.yml) runs the unit tests and builds the release
variant with the release key, once per ABI. It takes the latest release's version, or the one in the branch name
(Android refuses to install a lower versionCode, so a panel on an unreleased version needs that). The
APK then installs over the panel's app, HA's update entity offers nothing, and the next real release
replaces it. The build also gets its commit (`S1_TEST_BUILD`, a `BuildConfig` field): the Manage page
and the settings' About say "0.4.3 · test build 28c157d", while versionName and versionCode stay the
release's. It carries **no
radio bundle**, so the panel's root side stays as it is. The artifact is kept for 3 days, and since
the repo is public, any signed-in GitHub user can download it. To go back to the release, install
its APK from the release page with `adb install -r`. Delete the `test-build/` branch when you're
done.

## Debug builds and the release key

`assembleDebug` is unchanged: version `0.1.0` (versionCode 1), signed with the local debug key.
A panel running a debug build **cannot** update to a release through HA, because the keys differ.
The update entity then says so ("signed with a different key than this app"). Moving such a panel
to releases takes a one-time reinstall: uninstall the debug app, then install the release APK.
Both installers do that for you, after asking, and keep the panel on its Wi-Fi. The app's data is
kept only where adb has root; on a normal panel the switch **resets the app's settings** (sign-in,
pairing key, options). See [install.md](../frames/aqara-s1-plus/docs/install.md#switching-a-development-build-to-a-release).

## Libraries

The libraries in `libs/` go to Maven Central as `dev.glassworks:<name>` (#153): `esphome-api` (a jar),
`esphome-api-android`, `improv`, `sendspin`, `voice` and `ha-webrtc` (AARs). Each has its own semver version, in `version=` in
`libs/<name>/gradle.properties`, and its own `libs/<name>/CHANGELOG.md`, apart from the app's. Each
release ships the jar or AAR, a sources jar, a KDoc jar (Dokka), the POM (MIT, the GlassworksHQ org as
developer) and a `.asc` signature for each. The licence is in the jar as `META-INF/LICENSE`. Their
dependencies come from Maven Central with their own licences, except `voice`'s LiteRT, which is on
Google's Maven repository.

Ported code keeps its own licence. `voice`'s `MicroFrontend.kt` is a port of TensorFlow Lite Micro's
audio frontend (Apache-2.0) and kissfft (BSD-3-Clause). A library lists such licences in its
`gradle.properties` as `extraLicenses=<SPDX id>|<upstream copyright line>;…`, and explains the port in
`libs/<name>/NOTICE`. The build then adds each licence to the POM, and ships it in the jar as
`META-INF/LICENSE-<ID>` (from `LICENSES/<id>.txt`, under the copyright line), with `META-INF/NOTICE`.
`check-lib-artifact.sh` checks both. They're built for Kotlin 2.1 with kotlin-stdlib 2.1, so apps on Kotlin 2.0 or newer can
use them.

`bluetooth-proxy` isn't published yet. It's alpha, and GATT connections will change its API. To publish
a library, apply `id("org.jetbrains.dokka")` and `id("com.vanniktech.maven.publish")` in its build file,
and give it a `gradle.properties` with `version` and `description` and a `CHANGELOG.md`. The rest of
the setup is in the root `build.gradle.kts`.

**A release can't be deleted from Maven Central**, and its version can't be used again.

### The public API

Each library's public API is dumped in `libs/<name>/api/<name>.api`: every public class and member,
as apps compile against it. `./gradlew apiCheck` fails when the code's API differs from the dump. It
is part of `check`, and `libs-publish` in `pr.yml` runs it. When you change the API on purpose,
update the dump with:

```sh
./gradlew apiDump        # or :esphome-api:apiDump for one library
```

Commit the dump with the change. Its diff is the API change a reviewer reads. A removed or changed
line breaks apps built against an older release, so it needs a new minor version while the version
starts with 0, and a new major version from 1.0.

The rest of the API rules, all in the root `build.gradle.kts`:

- **Explicit API mode (strict).** Every public declaration says `public`. Make anything else
  `internal`: once released, a public declaration can't be taken back without breaking someone.
- **KDoc on every public declaration.** The KDoc jar (Dokka) fails to build when one has none.
- **README examples compile.** Each ```` ```kotlin ```` block of a library's README must be in its
  `ReadmeExample*` test, line for line (`readmeSnippets`, which `test` runs). Gradle blocks are skipped.

The dump comes from the binary-compatibility-validator's tasks, wired by hand. Neither Kotlin's own
`abiValidation` nor that plugin finds the classes of an Android library on AGP 9's built-in Kotlin
(KT-78025): both write an empty dump.

### Cut a release

The tag is `libs/<name>/v<MAJOR.MINOR.PATCH>`. It must match `version` in that library's
`gradle.properties`. `release.yml` runs only from its button, never on a tag, and the app's update
check skips any other tag (`Releases.tagVersion`). So a library tag never makes an app release.

1. In a PR: set `version` in `libs/<name>/gradle.properties`, and rename `## [Unreleased]` in
   `libs/<name>/CHANGELOG.md` to `## [<version>] - <date>`. Merge it.
2. Tag the merged commit on `main` and push the tag:

   ```sh
   git fetch origin
   git tag libs/esphome-api/v0.1.0 origin/main
   git push origin libs/esphome-api/v0.1.0
   ```

3. [`libs-release.yml`](../.github/workflows/libs-release.yml) uploads the release (below). While
   `MANUAL_PUBLISH` is `'true'` (it must be exactly `'true'` or `'false'`; the job fails on anything
   else), open [Deployments](https://central.sonatype.com/publishing/deployments)
   on the Central Portal, check the files, and press **Publish**. It's on Maven Central 10 to 30
   minutes later.
4. Once the Portal shows the deployment as **Published**, publish the draft GitHub release that the
   workflow made:
   `gh release edit libs/esphome-api/v0.1.0 --draft=false --latest=false`. If you publish it from
   GitHub's web page instead, **untick "Set as the latest release"**: panels on 0.4.3 read
   `releases/latest`, which must stay the S1 app's release.

Release `esphome-api` before `esphome-api-android`, `sendspin` and `voice`: the workflow refuses a library whose
`dev.glassworks` dependency isn't on Maven Central yet. Once a few releases have gone out cleanly,
set `MANUAL_PUBLISH: 'false'`. The workflow then publishes the deployment itself and makes the
GitHub release public.

### What the workflow does

The job runs in the `maven-central` environment, which deploys only from `libs/*/v*` tags.

1. Reads the library and version from the tag. It stops if the library doesn't apply the publish
   plugin, if the version isn't `version` in its `gradle.properties`, if the commit isn't on `main`,
   or if `CHANGELOG.md` has no section for it.
2. Runs the library's tests (`./gradlew :<name>:test`).
3. Publishes it to mavenLocal unsigned and checks the files with
   `tools/release/check-lib-artifact.sh`: the jar or AAR, sources, KDoc, POM and licence, and no
   class under the old `sh.edm` packages.
4. Signs it and uploads it to the Central Portal (`publishToMavenCentral`). It then polls the Portal
   for up to 60 minutes until the deployment is validated, and fails if the Portal refuses it. (The
   plugin validates only deployments it publishes itself.)
5. Makes the GitHub release `<name> <version>`, with the changelog section as notes and
   `--latest=false`, so `releases/latest` stays the S1 app's release.

Every PR that touches `libs/`, the Gradle build or these scripts runs a dry run (`libs-publish` in
`pr.yml`). It needs no secrets, so it runs on forks too:

```sh
./gradlew publishToMavenLocal                        # no key: unsigned
tools/release/check-lib-artifact.sh esphome-api      # and esphome-api-android, improv, sendspin, voice
tools/release/lib-consumer-check.sh                  # fresh apps build against them (below)
```

`lib-consumer-check.sh` builds two minSdk 23 Android apps, at compileSdk 37.2 and at compileSdk 23
(the AARs' `minCompileSdk`), and a JVM app on Kotlin 2.0.21, the oldest Kotlin the READMEs promise. It
needs the android-23 and android-37.2 SDK platforms.

### If the validation wait times out

The bundle is already on the Portal when the 60-minute wait gives up. **Don't re-run the workflow**:
it would upload the same version again, and the Portal rejects a duplicate. Instead:

Open [Deployments](https://central.sonatype.com/publishing/deployments) and look at the deployment.

- **Failed:** drop it there, and make no GitHub release. A version is used up only once it's
  published. Fix the cause, and if that needs a new commit, release it as the next patch version.
- **Validated** (or still validating: wait until it is):
  1. Press **Publish**, and wait until the Portal shows the deployment as **Published**. If it
     shows it as failed instead, follow the failed case above.
  2. Make the GitHub release by hand, as the workflow's last step would (here for esphome-api 0.1.0):

     ```sh
     { printf 'Maven Central: `dev.glassworks:%s:%s`\n\n' esphome-api 0.1.0
       tools/release/changelog-section.sh 0.1.0 libs/esphome-api/CHANGELOG.md; } > /tmp/notes.md
     gh release create libs/esphome-api/v0.1.0 --verify-tag --latest=false \
       --title "esphome-api 0.1.0" --notes-file /tmp/notes.md
     ```

### Rotate the token or the signing key

The secrets are in the `maven-central` environment (Settings → Environments, or
`gh secret set <NAME> --env maven-central`):

| Secret | What |
|---|---|
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | A Central Portal user token (not the login) |
| `SIGNING_IN_MEMORY_KEY` | The ASCII-armoured private signing key (`gpg --export-secret-keys --armor <id>`) |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | Its passphrase |
| `SIGNING_IN_MEMORY_KEY_ID` | The last 8 hex digits of its fingerprint |

- **The token.** On the Portal, open Account → Generate User Token. Set both secrets to the new pair,
  then revoke the old token there.
- **The signing key.** Make a new RSA-4096 key and upload its public key to keys.openpgp.org and
  keyserver.ubuntu.com. The Portal checks signatures against those servers. Set the three
  `SIGNING_*` secrets. Leave the old public key on the servers, so older releases still verify. If
  the old key leaked, publish its revocation certificate too.
- No dry run tests the secrets; the next release tag does. The workflow checks that all five are set
  before it builds.

## npm packages

Four of Kiln's packages go to npm as `@glassworks/<name>`: `fel`, `fastboot`, `ext4` and `improv-serial`
(all in `kiln/packages/`). The rest (`adb`, `app-adb`, `bootpatch`, `setup-channel`, `kiln-core`, `brand`, and
`packages/*`) are Kiln's and the site's own, and aren't published. Like the Maven libraries, each package has
its own semver `version` (in its `package.json`) and its own `CHANGELOG.md`, apart from the apps'.

In the workspace every package stays `"private": true`, and its `exports` point at `src/*.ts`, so Kiln, the
frames and the site build from source. What npm gets is built from that source by
`tools/release/npm-package.mjs`:

- `dist/`: `src/` compiled by the package's `tsconfig.build.json` (which extends its `tsconfig.json`, then the
  root `tsconfig.npm.json`): Node ESM, `.d.ts`, source maps. `src/` ships too, for the maps.
- `package.json`: without `private` and `scripts`. Each `./src/X.ts` export becomes `./dist/X.js` with its
  types. Exports of `./test/*` (the fakes our own tests use, such as `@glassworks/fel/fakes`) are left out. A
  workspace `@glassworks` dependency becomes `^` its version, and must itself be published.
- `README.md`, with its relative links made absolute on GitHub at the release's tag; `LICENSE`, the repo's MIT
  (and, for a fork such as `fastboot`, upstream's licence under it); `CHANGELOG.md`.

A package is published when its folder has a `CHANGELOG.md` and a `tsconfig.build.json`. To add one: give it
those two, a version other than `0.0.0`, a name in the `@glassworks` scope, and a README with a library banner
([docs/branding.md](branding.md#adding-a-library)). Its source names the `.ts` file in relative imports
(`from './felDevice.ts'`): Node ESM needs an extension, the build rewrites it to `.js`
(`rewriteRelativeImportExtensions`; the script does the same in the `.d.ts`), and Vite, Next.js (Turbopack)
and vitest find the file as named. `tsconfig.base.json` allows it (`allowImportingTsExtensions`). A WebUSB package puts `/// <reference types="w3c-web-usb" preserve="true" />` at the top of each
file whose types use WebUSB, and depends on `@types/w3c-web-usb`, so a consumer gets those types without
setting anything up.

### The dry run

Every PR that the `web` job runs for (`pr.yml`) packs every package and checks it as someone else gets it:

```sh
node tools/release/npm-consumer-check.mjs          # or: … fel   (one package)
node tools/release/npm-package.mjs fel --out /tmp/npm   # build and pack one, to look inside
```

The check installs the tarballs into a fresh project (with the network: their dependencies come from npm),
type-checks every entry point from there under `moduleResolution` `nodenext` and `bundler` with `skipLibCheck`
off and `types: []`, and imports each one in Node.

### Cut an npm release

The tag is `npm/<name>/v<MAJOR.MINOR.PATCH>`, where `<name>` is the package's folder. It must match
`version` in that package's `package.json`.

1. In a PR: set `version` in the package's `package.json`, and in every workspace `package.json` that
   depends on it (the pins are exact, or npm stops linking the workspace copy). Rename `## [Unreleased]`
   in its `CHANGELOG.md` to `## [<version>] - <date>`. Merge it.
2. Tag the merged commit on `main` and push the tag:

   ```sh
   git fetch origin
   git tag npm/fel/v0.1.0 origin/main
   git push origin npm/fel/v0.1.0
   ```

3. [`npm-release.yml`](../.github/workflows/npm-release.yml) publishes it: it checks the tag (the package,
   its version, the commit on `main`, its changelog section, the version not on npm yet), runs the
   package's typecheck and tests, runs the consumer check on it alone, then `npm publish --provenance` and
   a GitHub release `@glassworks/<name> <version>` with `--latest=false` (`releases/latest` stays the S1
   app's).

Release `fel` before `fastboot`: the consumer check installs `fastboot`'s `@glassworks/fel` from npm, and
fails while that version isn't there.

**Treat a published version as permanent: its number can never be used again, even after an unpublish.** npm lets
a version be unpublished only while no other package on npm depends on it, and after 72 hours only if it also
had under 300 downloads last week and has one owner ([npm's unpublish policy](https://docs.npmjs.com/policies/unpublish)).
Once `@glassworks/fastboot` is out, `@glassworks/fel` has a dependent.

If the job published the package but failed at the GitHub release, a rerun stops at "Not on npm yet".
Make the release by hand from the same tag, without publishing again (here `fel` 0.1.0):

```sh
{ printf 'npm: [`@glassworks/fel@0.1.0`](https://www.npmjs.com/package/@glassworks/fel/v/0.1.0)\n\n'
  tools/release/changelog-section.sh 0.1.0 kiln/packages/fel/CHANGELOG.md; } > /tmp/notes.md
gh release create npm/fel/v0.1.0 --verify-tag --latest=false --title "@glassworks/fel 0.1.0" --notes-file /tmp/notes.md
```

### Access

The job runs in the `npm` environment (Settings → Environments), which should deploy only from `npm/*/v*`
tags. It publishes with npm's trusted publishing (OIDC): no token, and the provenance shows the workflow
that built the package. npm sets trusted publishing up per package, and only for a package that already
exists, so the first release of each goes out with a token:

1. On npmjs.com, the `glassworks` organization owns the `@glassworks` scope.
2. Make a granular access token that may read and write `@glassworks` packages, with a short expiry and
   **Bypass two-factor authentication** on (a new package asks for 2FA to publish, which a job can't
   give), and set it as `NPM_TOKEN` in the `npm` environment (`gh secret set NPM_TOKEN --env npm`).
3. Push the first tags (`fel` first). Each package is published with the token.
4. For each package, on npmjs.com → the package → Settings → Trusted publishing: GitHub Actions,
   `GlassworksHQ/glassworks`, workflow `npm-release.yml`, environment `npm`. Under "Allowed actions", turn
   on `npm publish`: a new setup allows only `npm stage publish`, and the job publishes directly. Then set
   "Require two-factor authentication and disallow tokens".
5. Delete the `NPM_TOKEN` secret and revoke the token. Without it, the job publishes through OIDC.
6. Publish a release through it within 2 days. npm doesn't check a trusted publishing setup when it's saved:
   the first publish through it does, and a setup nothing has published through in 2 days expires. Delete an
   expired one and set it up again, right before the package's next tag.
