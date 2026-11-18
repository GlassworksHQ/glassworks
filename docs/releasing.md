# Releasing the kiosk app

Panels update themselves from GitHub Releases: the app lists
`https://api.github.com/repos/GlassworksHQ/glassworks/releases`, picks its own newest release
and shows the result in Home Assistant as **update.s1_panel_app_update** (on a panel added before #63;
`update.glassworks_panel_app_update` on one added before #260; a newer one starts with the device's
name, `aqara_s1_plus_` on a new S1, [Names](esphome.md#names))
([esphome.md](esphome.md#app-updates)). A release is built and published by
[`.github/workflows/release.yml`](../.github/workflows/release.yml) when you push a `v*` or
`aqara-s1-plus-v*` tag. It holds one APK per ABI, each with its `.sha256` (see [ABIs](#abis)):

- `s1-kiosk-<version>.apk`, the `armeabi-v7a` APK the S1 runs, signed with the release key;
- `s1-kiosk-arm64-v8a-<version>.apk`, the same app for `arm64-v8a` devices;
- `s1-kiosk-<version>.apk.sha256` and `s1-kiosk-arm64-v8a-<version>.apk.sha256`, their `sha256sum` lines.

The libraries in `libs/` are released to Maven Central on their own tags: see [Libraries](#libraries).

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
- A copy is in Bitwarden as the secure note "S1 Unleashed signing keys" (keystore, its password,
  and the radio bundle key), saved and checked byte for byte on 2026-11-11. Since 2026-11-17 the
  vault's Glassworks folder also holds "S1 Kiosk release key" and "Glassworks Android release key"
  (each keystore attached, with its password).

## 3. Add the four secrets to GitHub (once)

Secrets of the **`release-signing` environment**, not repository secrets. A repository secret can be
read by any workflow that anyone with write access pushes, on any branch; an environment secret only
by jobs that run on what the environment allows. Here that's `main`, `test-build/**` branches and the
`v*` and `aqara-s1-plus-v*` tags (and the generic app's `generic-android-v*` and `generic-android-legacy-v*`,
[below](#glassworks-for-generic-android-and-its-legacy-build)), and a ruleset lets only admins create those
branches and tags (see *Who can sign*, below).

Create the environment once (Settings → Environments → New environment → `release-signing`;
Deployment branches and tags → Selected: `main`, `test-build/**`, tags `v*`, `aqara-s1-plus-v*`),
then set the secrets with the [GitHub CLI](https://cli.github.com/). These commands read the values
from the file or from a prompt, so nothing ends up in your shell history:

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

The workflow uses only these four secrets, the radio bundle key below, the optional `VERCEL_DEPLOY_HOOK`
(a Vercel deploy hook URL for the web installer), and the job's own `GITHUB_TOKEN`. The same `release-signing`
environment also holds Glassworks' own key for the generic app and its legacy build (`GENERIC_*`, since
2026-11-17), which `generic-test-build.yml` and `generic-release.yml` use: see
[frames/generic-android/README.md](../frames/generic-android/README.md#signing).

### Who can sign

Anyone who can run a job in `release-signing` or `radio-signing` can sign code every panel accepts.
Rulesets (Settings → Rules) keep that to admins:

- **Release tags** (`v*`, `aqara-s1-plus-v*`, and from the generic app's first release `generic-android-v*`
  and `generic-android-legacy-v*`): only admins can create, move or delete them.
- **Test builds** (`test-build/**`): only admins can create, push to or delete these branches.
- **main**: changes go through a pull request with the `app`, `web`, `scripts` and `root` checks
  green; no force pushes or deletion. Admins can bypass, for emergencies.

Pull requests from forks never get any secret, and no workflow uses `pull_request_target` or
`workflow_run`. Before anyone else gets admin, add yourself as a required reviewer on both
environments, so every signed build waits for an approval.

## 3b. The radio bundle key (once)

Only the armeabi-v7a (S1) APK carries a **signed radio bundle**: the root radio helper, otbr-agent/ot-ctl
and the Silicon Labs Zigbee runtime ([radio.md](../frames/aqara-s1-plus/docs/radio.md#radio-bundles-helper-v4)). The other ABIs'
APKs carry none, because no S1 runs them. Panels whose boot image has the bundle
loader install it only if it is signed with the Ed25519 key whose public half is baked into the boot
image ([`frames/aqara-s1-plus/tools/root/s1kiosk_bundle.pub`](../frames/aqara-s1-plus/tools/root/s1kiosk_bundle.pub)). It is a separate key from
the APK key on purpose: it lets root code run on the panel, so it lives only in `~/secure` and in one
repository secret.

```sh
# Done once (2026-11-10): ~/secure/s1kiosk-radio-bundle-ed25519.pem, mode 600, never printed.
openssl genpkey -algorithm ed25519 -out ~/secure/s1kiosk-radio-bundle-ed25519.pem   # OpenSSL 3
openssl pkey -in ~/secure/s1kiosk-radio-bundle-ed25519.pem -pubout -outform DER | tail -c 32 | xxd -p -c 64 \
  > frames/aqara-s1-plus/tools/root/s1kiosk_bundle.pub
# The secret: an environment secret of the `radio-signing` environment (Settings -> Environments;
# create the environment first), so only the release workflow's signing job can read it:
gh secret set S1_RADIO_BUNDLE_KEY --env radio-signing --repo GlassworksHQ/glassworks \
  < ~/secure/s1kiosk-radio-bundle-ed25519.pem
```

- Only the workflow's `sign-bundle` job names that environment. It signs only tags whose commit is on
  `main` (`git merge-base --is-ancestor`), runs no third-party action (GitHub's own checkout and
  download-artifact, pinned by commit SHA), and takes nothing from the unprivileged `inputs` job
  without checking it: the Silicon Labs runtime against the committed
  [`frames/aqara-s1-plus/tools/radio/sisdk-runtime.sha256`](../frames/aqara-s1-plus/tools/radio/sisdk-runtime.sha256), otbr-agent/ot-ctl against
  `kiln/assets.lock.json`, the helper from the checkout. No required reviewer is set on the environment
  (one releaser); adding one later only needs the environment setting.
- Keep no repository-level `S1_RADIO_BUNDLE_KEY`: other jobs would then see it.

- Back it up like the APK key. **Losing it** means no more radio bundle updates until panels get a boot
  image with a new public key (one FEL session each). **If it leaks**, someone who can also get a file
  into the kiosk's private storage could run root code: rotate it (new key, new
  `s1kiosk_bundle.pub`, new boot image on every panel).
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

## 4. Release

Versions are `MAJOR.MINOR.PATCH`, with minor and patch below 100. The versionCode is
`major*10000 + minor*100 + patch`, so `1.2.3` is 10203. Every release must have a higher version
than the last one. Pre-release tags (`v1.2.3-rc.1`) are refused by the workflow, and the app would
ignore them anyway.

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
| `arm64-v8a` | `s1-kiosk-arm64-v8a-<version>.apk` | no (the bundle is the S1's root side) | arm64 tablets (generic-android), the arm64 emulator. Not the Mirror (Android 6): S1 Kiosk needs API 26, so Kiln installs the legacy build of Glassworks for generic Android there (#198, #411) |

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
- Adding an ABI means adding it to a frame's `abis`, to `ApkAbi`, to `apk_name` in both workflows
  if its name differs, and an upload step in `test-build.yml`. Gradle and the workflows' loops take
  the list from the frames. `ApkAbiListsTest` fails if the app and the frames disagree.

**Tags, now that the repo will hold other frames and libraries (Glassworks).** Each of them releases
under its own tag prefix, and this app's is `aqara-s1-plus-` (the board id it reports, since other S1
models may get their own frame). From 0.4.4 the app accepts both `v1.2.3` and
`aqara-s1-plus-v1.2.3` and picks its own newest release from the list, whatever else was released
after it. Apps up to **0.4.3** only read `releases/latest` and only understand `v1.2.3`, so:

- keep tagging S1 releases `v1.2.3` until the repo is renamed and other frames start releasing;
- an S1 release is always published with `--latest` (the workflow does this), and every other frame's
  or library's release with `--latest=false`, so `releases/latest` stays an S1 release for old panels.

First add a `## [1.0.0] - <date>` section to [CHANGELOG.md](../CHANGELOG.md) (move the
*Unreleased* entries into it). Its first line is what Home Assistant shows on the update card, so make
it a one-sentence summary. The release fails early if the section is missing.

```sh
git switch main && git pull
git tag -a v1.0.0 -m "S1 Kiosk 1.0.0"
git push origin v1.0.0
gh run watch --repo GlassworksHQ/glassworks   # or the Actions tab
```

The workflow then:

1. checks the tag;
2. builds the Silicon Labs runtime (`inputs` job: `frames/aqara-s1-plus/tools/radio/build-sisdk-runtime.sh`, Docker, a pinned
   image and Ubuntu snapshot; it must reproduce `frames/aqara-s1-plus/tools/radio/sisdk-runtime.sha256`; never Silicon Labs'
   zigbeed itself), then signs the radio bundle in the `sign-bundle` job (environment `radio-signing`,
   main only, every input checked against committed hashes; `frames/aqara-s1-plus/tools/radio/make-bundle.py --app-version`,
   version = the versionCode, checked against `frames/aqara-s1-plus/tools/root/s1kiosk_bundle.pub`). PR CI catches most drift
   before a tag does. The `size` job in `pr.yml` caches the runtime under a key made from the script,
   `frames/aqara-s1-plus/tools/radio/sisdk/` and the hash file. When one of them changes, it rebuilds from scratch and checks
   the hashes. On a cache hit, it only checks the cached files against the hashes. So drift outside the
   repo (the Ubuntu snapshot, the cpc-daemon tag) still shows first in this fresh release build;
3. builds `:kiosk:assembleRelease` with `S1_VERSION=1.0.0` once per ABI, the `armeabi-v7a` one with
   the bundle as APK assets. The build fails if a key is missing (`S1_REQUIRE_RELEASE_SIGNING=true`);
4. runs `apksigner verify --print-certs` on each APK, so the certificate fingerprint is in the log,
   checks the package name, version and native code ABI, that the signed bundle is in the
   `armeabi-v7a` APK and not in the others, and that no zigbeed is anywhere;
5. writes a `.sha256` for each;
6. creates the release, with that version's CHANGELOG.md section as the notes. The bundle is not
   published on its own (its Silicon Labs libcpc may only be distributed inside the product).
7. asks Vercel to rebuild the web installer, so it installs the new release (`VERCEL_DEPLOY_HOOK`).

HA shows the first 255 characters of the notes, with a link to the full release.

Panels see the release within 24 hours. To check right away, call `homeassistant.update_entity`
on `update.s1_panel_app_update`: HA sends the entity's "check" command.

**Panels on 0.4.0 or older can't install it from HA.** Their update path refused every release as
"the APK is not signed": on Android 10, `getPackageArchiveInfo` with only `GET_SIGNING_CERTIFICATES`
returns no signer for a downloaded APK. 0.4.1 also asks for `GET_SIGNATURES`
([esphome.md](esphome.md#app-updates)). Because the bug is in the version doing the installing, those
panels need one manual update to 0.4.1 or later (the web installer's *Already installed? Finish setting
it up*, or `adb install -r`, see [install.md](../frames/aqara-s1-plus/docs/install.md#updating)); from then on HA installs work.

The hosted web installer installs the latest release it saw when it was last built
(`kiln/scripts/stage-assets.mjs`). The workflow's last step asks Vercel to rebuild it through the
`VERCEL_DEPLOY_HOOK` secret; without that secret it only warns, and you redeploy the installer by hand.

Vercel does not build on pushes (`git.deploymentEnabled: false` in `kiln/vercel.json`), so PRs get no
Vercel preview. Production builds come only from that deploy hook, called by `release.yml` and by
[`installer-deploy.yml`](../.github/workflows/installer-deploy.yml). The second runs on a push to
`main` that changes an installer input (`kiln/`, `branding/`, or a repo file the installer stages), or
by hand from the Actions tab.

## Glassworks for generic Android and its legacy build

Two more apps release from this repo, each on its own tag and asset names, which are the ones its update check
reads (`ReleaseLine` in `core/.../update/Releases.kt`). So no app ever offers itself another app's APK:

| App | applicationId | Tag | Assets, one pair per ABI in `frames/generic-android/design/frame.json` |
|---|---|---|---|
| Glassworks for generic Android (Android 8+) | `dev.glassworks.generic_android` | `generic-android-v1.2.3` | `glassworks-generic-android-<abi>-1.2.3.apk` and its `.sha256` |
| Its legacy build (Android 6-7, the Mirror) | `dev.glassworks.generic_android.legacy` | `generic-android-legacy-v1.2.3` | `glassworks-generic-android-legacy-<abi>-1.2.3.apk` and its `.sha256` |

[`generic-release.yml`](../.github/workflows/generic-release.yml) builds them. Both are signed with Glassworks'
own key, never S1 Kiosk's ([frames/generic-android/README.md](../frames/generic-android/README.md#signing)),
whose certificate is in [`tools/release/generic-release-cert.sha256`](../tools/release/generic-release-cert.sha256).
S1 Kiosk's `release.yml` never sees these tags, and these releases are published with `--latest=false`, so
`releases/latest` stays S1 Kiosk's.

**Release notes.** Both builds share [`frames/generic-android/CHANGELOG.md`](../frames/generic-android/CHANGELOG.md)
and one version sequence: the section `## [1.2.3] - <date>` is the notes of `generic-android-v1.2.3` and of
`generic-android-legacy-v1.2.3`, whichever of the two you tag. The repo's `CHANGELOG.md` stays S1 Kiosk's. A change
that people running both apps would notice, a core change for example, gets a line in each file. Its first line is
the update card's text in Home Assistant, as for S1 Kiosk.

**Versions.** `MAJOR.MINOR.PATCH`, versionCode `major*10000 + minor*100 + patch`, as for S1 Kiosk. The workflow
refuses a version that isn't higher than the line's last release. Start at **0.2.0**: test builds are 0.1.0 and
0.1.1, and a device on test build 0.1.1 is offered only a higher version (and `adb install -r` refuses a lower
versionCode).

### Before the first generic tag (once, admin)

The tags need the same protection as S1 Kiosk's: only admins create them (the ruleset), and only they reach the key
(the environment). Ruleset first, so the environment never lets in a tag that anyone with write access could push:

```sh
gh api -X PUT repos/GlassworksHQ/glassworks/rulesets/27082980 --input - <<'EOF'
{"conditions": {"ref_name": {"exclude": [], "include": ["refs/tags/v*", "refs/tags/aqara-s1-plus-v*",
  "refs/tags/generic-android-v*", "refs/tags/generic-android-legacy-v*"]}}}
EOF
for t in 'generic-android-v*' 'generic-android-legacy-v*'; do
  gh api -X POST repos/GlassworksHQ/glassworks/environments/release-signing/deployment-branch-policies -f name="$t" -f type=tag
done
```

Both patterns are needed: `generic-android-v*` doesn't match `generic-android-legacy-v1.2.3`.

### Dry run

A manual run builds, signs and checks exactly what the tag would publish, keeps the APKs as an artifact for 3 days,
and creates no release. Run it on `main` (or a `test-build/` branch: the environment refuses any other):

```sh
gh workflow run generic-release.yml --repo GlassworksHQ/glassworks --ref main -f tag=generic-android-legacy-v0.2.0
gh run download <run-id> --repo GlassworksHQ/glassworks -n generic-android-legacy-v0.2.0
```

Before the version has its section in the CHANGELOG, the dry run warns and shows *Unreleased* as the notes; the tag
would fail there.

### Release

1. In `frames/generic-android/CHANGELOG.md`, rename *Unreleased* to `## [0.2.0] - <date>` (one-sentence first
   line), and merge that.
2. Tag the merged commit, one tag per app you release:

```sh
git switch main && git pull
git tag -a generic-android-v0.2.0 -m "Glassworks for generic Android 0.2.0"
git tag -a generic-android-legacy-v0.2.0 -m "Glassworks for generic Android 0.2.0, legacy build"
git push origin generic-android-v0.2.0 generic-android-legacy-v0.2.0
gh run list --repo GlassworksHQ/glassworks --workflow generic-release.yml --limit 2
```

Each tag runs the workflow once. Its `build` job (environment `release-signing`, read-only token):

1. reads the app and version from the tag, and refuses a version that isn't higher than the line's last release;
2. takes the notes from the CHANGELOG, before the slow build;
3. builds `:generic-android:assembleRelease` (or `assembleLegacy`) with `-PgenericVersion` once per ABI of the
   frame, with `GENERIC_REQUIRE_RELEASE_SIGNING=true`;
4. checks each APK: `apksigner verify`, `tools/release/check-update-compat.sh --app <line> --signed` (Glassworks'
   certificate, the applicationId, no `sharedUserId`, the components named from outside the APK), the package,
   versionName and versionCode, the minSdk (26, or 23 for legacy), its ABI's GeckoView, and the system
   permissions the Mirror needs;
5. writes a `.sha256` for each.

Then `publish` (tag pushes only, write token, no key) creates the release with every APK and `.sha256` in one
`gh release create`, so a release never has half its ABIs. Installed copies see it within 24 hours, or at once
through `homeassistant.update_entity` on the app's update entity.

Not part of it yet: Kiln still installs the generic app and the Mirror's build from a test build's APK
(`?apk=local`), and its release route serves S1 Kiosk only (`kiln/apps/kiln/src/app/api/release/[frame]/route.ts`),
so the workflow doesn't rebuild the installer.

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
`esphome-api-android`, `improv`, `sendspin` and `voice` (AARs). `sendspin` and `voice` (#152) are set up
but have no release yet. Each has its own semver version, in `version=` in
`libs/<name>/gradle.properties`, and its own `libs/<name>/CHANGELOG.md`, apart from the app's. Each
release ships the jar or AAR, a sources jar, a KDoc jar (Dokka), the POM (MIT, the GlassworksHQ org as
developer) and a `.asc` signature for each. The licence is in the jar as `META-INF/LICENSE`. Their
dependencies come from Maven Central with their own licences.

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
`gradle.properties`. `release.yml` takes only `v*` and `aqara-s1-plus-v*` tags, and the app's update
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
