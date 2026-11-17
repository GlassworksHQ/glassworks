---
topic: android
evidence: verified
---

# Android CA roots: which roots Android 6 and 7.0 lack for the hosts the app fetches from

**Status (2026-11-17): working.** Android 6.0.1 and 7.0 lack **ISRG Root X1**. GitHub serves its download
hosts (`raw.githubusercontent.com`, its release assets) on a chain to that root, so the wake word models and
update APKs failed there. Below Android 7.1 the app now trusts the system store plus a bundled ISRG Root X1
(#456). Verified on the API 23 emulator, whose store is AOSP's. Not yet tested on the Mirror.

Device: lululemon Mirror, Android 6.0.1 (API 23), `dev.glassworks.generic_android.legacy`. Tools: OpenSSL
3.1.0, AOSP's CA stores at the tags below, `tools/legacy/ca-roots.sh`.

## Question

On 2026-11-17 the Mirror logged:

```
S1Voice: Downloading the wake word model alexa.tflite failed: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.
```

Which roots does Android's store lack for the hosts the app fetches from, and from which Android version on is
each one there?

## What we tried

1. **The chains the hosts serve** (2026-11-16, verified)
   ```
   $ echo | openssl s_client -connect raw.githubusercontent.com:443 -servername raw.githubusercontent.com -showcerts
    0 s:CN = *.github.io                       i:C = US, O = Let's Encrypt, CN = YR1
    1 s:C = US, O = Let's Encrypt, CN = YR1     i:C = US, O = ISRG, CN = Root YR
    2 s:C = US, O = ISRG, CN = Root YR          i:C = US, O = Internet Security Research Group, CN = ISRG Root X1
   $ echo | openssl s_client -connect github.com:443 -servername github.com -showcerts
    0 s:CN = github.com                        i:... CN = Sectigo Public Server Authentication CA DV E36
    1 s:... CA DV E36                          i:... CN = Sectigo Public Server Authentication Root E46
    2 s:... Root E46                           i:... CN = USERTrust ECC Certification Authority
   ```
   `release-assets.githubusercontent.com` and `objects.githubusercontent.com` serve the same chain as `raw`
   (one `*.github.io` certificate). `api.github.com` serves the Sectigo chain, like `github.com`.

2. **AOSP's CA stores** (2026-11-16, read). Android 6.0.1 kept the store in libcore, not in
   `system/ca-certificates` (that project has no Android 6 tags):

   | Android | Source | Commit | Roots | ISRG Root X1 | USERTrust ECC |
   |---|---|---|---|---|---|
   | 6.0.1 (API 23) | `platform/libcore` `android-6.0.1_r81`, `luni/src/main/files/cacerts` | `5bfcb25` | 158 | no | yes |
   | 7.0 (API 24) | `platform/system/ca-certificates` `android-7.0.0_r1`, `files` | `c36d8eb` | 148 | no | yes |
   | 7.1 (API 25) | `platform/system/ca-certificates` `android-7.1.0_r1`, `files` | `465b0d8` | 156 | yes (`6187b673.0`) | yes |

3. **Each chain against each store** (2026-11-16, verified, `tools/legacy/ca-roots.sh`): `openssl verify` with
   only that store as its CA file (`-no-CAfile -no-CApath -no-CAstore`, so the computer's own roots don't count).

   | Host | Root it chains to | 6.0.1 (API 23) | 7.0 (API 24) | 7.1 (API 25) |
   |---|---|---|---|---|
   | github.com | USERTrust ECC Certification Authority | OK | OK | OK |
   | api.github.com | USERTrust ECC Certification Authority | OK | OK | OK |
   | release-assets.githubusercontent.com | ISRG Root X1 | **fails** | **fails** | OK |
   | raw.githubusercontent.com | ISRG Root X1 | **fails** | **fails** | OK |
   | objects.githubusercontent.com | ISRG Root X1 | **fails** | **fails** | OK |

   With `--with isrgrootx1.pem` (ISRG Root X1 added to every store), every row is OK.

4. **The downloads on the API 23 emulator** (2026-11-17, verified). `system-images;android-23;default;arm64-v8a`,
   whose CA store is AOSP's. An instrumentation test, `tools/legacy/https-trust-device-check.kt` (copied into the
   core's androidTest for the run; CI doesn't run it because it reaches the internet), fetched each URL with a socket factory over the system trust manager alone ("without"), then with
   `HttpsTrust.socketFactory` ("with"), and hashed the body:
   ```
   $ ./gradlew --init-script tools/legacy/api23-emulator.gradle :glassworks-core:connectedDebugAndroidTest \
       -Pandroid.testInstrumentationRunnerArguments.class=dev.glassworks.platform.HttpsTrustDeviceCheck
   API 23, hasIsrgRootX1=false
   WITHOUT .../micro-wake-word-models/40ff33f…/models/v2/alexa.tflite -> SSLHandshakeException: CertPathValidatorException: Trust anchor for certification path not found.
   WITH    .../micro-wake-word-models/40ff33f…/models/v2/alexa.tflite -> HTTP 200 from raw.githubusercontent.com, 55856 bytes, sha256 9011a815…a723b
   WITHOUT github.com/dscripka/openWakeWord/releases/download/v0.5.1/alexa_v0.1.tflite -> SSLHandshakeException: … Trust anchor for certification path not found.
   WITH    github.com/dscripka/openWakeWord/releases/download/v0.5.1/alexa_v0.1.tflite -> HTTP 200 from release-assets.githubusercontent.com, 855312 bytes, sha256 7333a317…48397
   WITHOUT api.github.com/repos/GlassworksHQ/glassworks/releases?per_page=1 -> HTTP 200 from api.github.com
   WITH    api.github.com/repos/GlassworksHQ/glassworks/releases?per_page=1 -> HTTP 200 from api.github.com
   WITH    https://<raw.githubusercontent.com's IP address>/ (a chain to X1, a name that doesn't match) -> SSLPeerUnverifiedException: Hostname … not verified
   WITH    https://untrusted-root.badssl.com/ -> SSLHandshakeException: … Trust anchor for certification path not found.
   install()=true, then the default factory -> HTTP 200 from raw.githubusercontent.com, 55856 bytes, sha256 9011a815…a723b
   ```
   Both hashes match the ones `ModelFetcher` pins. The openWakeWord download fails on its redirect: github.com
   itself is trusted. With the root, a wrong hostname and an unknown root are still refused.

5. **A trust manager that extends `X509ExtendedTrustManager` doesn't load on Android 6** (2026-11-17, verified).
   The first version of the fix extended it, to pass the hostname-aware overloads through. The JVM tests passed;
   on the API 23 emulator it threw `NoClassDefFoundError: dev.glassworks.platform.SystemPlusRoots`, because the
   class arrived in Android 7.0 (API 24). In the app that would have crashed `GlassworksRuntime` on every start.
   The fix is a plain `X509TrustManager`, and `HttpsTrustTest` checks its supertypes.

## Result

- **One root is missing: ISRG Root X1.** It is in AOSP from Android 7.1 (API 25). Android 7.0 lacks it too, so the
  threshold is API 25, not 24.
- **What failed on API 23–24:** the openWakeWord models (GitHub release assets, which redirect to
  `release-assets.githubusercontent.com`), the microWakeWord models (`raw.githubusercontent.com`), and an update's
  APK download (also a release asset). A Home Assistant on https with a Let's Encrypt certificate (DuckDNS, a
  reverse proxy) should fail the same way for the sign-in and notification pictures (untested: inferred from the
  same chain, as they use the same HttpsURLConnection).
- **What worked:** the update check itself. `api.github.com` chains to USERTrust ECC, which Android 6 has. The
  Mirror's "latest none" means the check got an answer and found no usable release, not a TLS failure. The
  dashboard is not affected: GeckoView brings its own CA store.
- **The fix** (`pane-api/.../dev/glassworks/platform/HttpsTrust.kt`): below API 25, `GlassworksRuntime` sets
  `HttpsURLConnection`'s default `SSLSocketFactory` before any component connects. `install()` never throws:
  if the device's KeyStore or TrustManagerFactory refuses, it logs a warning and HTTPS stays as the system has it. Its trust manager accepts a
  chain the system trusts, else one that reaches a bundled root. It refuses everything else with the system's
  error. Hostname verification is Android's `HostnameVerifier`, unchanged (step 4). It is a plain `X509TrustManager`
  (step 5). Android 7.0's Network Security Config accepts the plain overload because neither app sets a
  per-domain config.
- **Android 7.0's Network Security Config** could add the root from XML (`<trust-anchors>`), but not on Android 6.
  One code path for both is simpler.

### The bundled roots

| Root | Expires | SHA-256 | Source | Licence |
|---|---|---|---|---|
| ISRG Root X1 | 2035-06-04 | `96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6` | https://letsencrypt.org/certs/isrgrootx1.pem, the same file as AOSP's `6187b673.0` | A public CA certificate; no licence terms attached (THIRD-PARTY-NOTICES.md) |

`HttpsTrustTest` fails when a bundled root is within a year of expiry, and when the fingerprints differ from this
table. To refresh or add a root:

1. Run `tools/legacy/ca-roots.sh` to see which hosts fail on which Android.
2. Take the new root from its CA's site, and check that it matches the copy in AOSP's `system/ca-certificates`.
3. Paste it into `HttpsTrust.ROOTS`, update the fingerprint in `HttpsTrustTest`, and update this table.
4. If the new root is missing on Android 7.1 or later too, move the gate: `PlatformApi.hasIsrgRootX1` becomes a
   query for the first Android that has every bundled root, `PlatformTest` gets its new boundary, and
   `ca-roots.sh` gets that Android's store (add its tag to `STORES`).
5. Run `tools/legacy/ca-roots.sh --with <the roots>.pem`: every row should be OK.

The next likely one is **ISRG Root X2** (ECDSA). Let's Encrypt signs its ECDSA chains with it, but GitHub served
RSA chains on 2026-11-16. Android has it only from Android 14, so if GitHub moves, newer Androids would fail too.

## How to reproduce

1. `tools/legacy/ca-roots.sh` (read-only: network and a temporary directory).
2. On the API 23 arm64 emulator (`system-images;android-23;default;arm64-v8a`), run
   `tools/legacy/https-trust-device-check.kt` as its header says (with `tools/legacy/api23-emulator.gradle`).
   It touches only the emulator and the internet. The app
   itself could not be started there: agent-device's `open` and `snapshot` fail on this image (2026-11-17).

## Open questions

- Not yet tested on the Mirror. The root confirms it there, with the app itself: the emulator ran the trust code
  in a test process, not the app's start.
- `SisdkZigbeed` downloads from GitHub too, but it runs only on the S1 (Android 10), so this doesn't affect it.
