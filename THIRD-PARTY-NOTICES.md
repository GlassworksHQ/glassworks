# Third-party notices

Glassworks' own code is MIT ([LICENSE](LICENSE)), except the Glassworks core, which is MPL-2.0
([its README](pane-api/src/main/java/dev/glassworks/README.md#licence)). This file lists the third-party code that the
project distributes: in the S1 app's APK (with the signed radio bundle that release APKs carry), and in
the files Kiln, the web installer at https://kiln.glassworks.dev, serves. For each part it gives the version, the licence
(as an SPDX identifier), where it comes from and where its source is. The libraries published to Maven
Central (`dev.glassworks:*`, [docs/releasing.md](docs/releasing.md#libraries)) contain only this
project's MIT code; their dependencies come from Maven Central under their own licences.

The full licence texts are in [`LICENSES/`](LICENSES), named by SPDX identifier. Those files are the
standard texts. The copyright holders are named under each component below. The app shows this file
and those texts under **Open source licences** in its hidden settings: tap the top-left corner five
times.

**Frames credit the work they stand on.** This file covers code we distribute. Some frames also rely
on other people's work: another project's Android image, an exploit, a tool or research. Each frame
names those people in its `frame.json` `credits`, and glassworks.dev, Kiln and the frame's README
show them where that work is used ([frames/README.md](frames/README.md#credits)). A new frame that
uses someone else's work adds a credit, whether or not it is listed here.

Checked against the code on 2026-11-10. `checkThirdPartyNotices` in
`frames/aqara-s1-plus/android/build.gradle.kts` fails a release build if a library in the APK is
missing from section 1.

## Contents

1. [The S1 app APK](#1-the-s1-app-apk)
2. [Radio bundle (inside release APKs)](#2-radio-bundle-inside-release-apks)
3. [Web installer downloads](#3-web-installer-downloads)
4. [The web installer page itself](#4-the-web-installer-page-itself)
   - [Kiln, the Next.js installer](#kiln-the-nextjs-installer)
   - [glassworks.dev, the project site](#glassworksdev-the-project-site)
5. [Not distributed by this project](#5-not-distributed-by-this-project)
6. [Corresponding source for GPL, LGPL and MPL parts](#6-corresponding-source-for-gpl-lgpl-and-mpl-parts)

---

## 1. The S1 app APK

`sh.edm.s1kiosk`, built from [`frames/aqara-s1-plus/android`](frames/aqara-s1-plus/android) by
`.github/workflows/release.yml`. These are the libraries in the APK: the release runtime classpath
of `:kiosk`, including transitive dependencies. The versions are the ones Gradle resolves.

### GeckoView (browser engine)

- Module: `org.mozilla.geckoview:geckoview-armeabi-v7a:159.0.20261102144711` (the armeabi-v7a APK),
  `org.mozilla.geckoview:geckoview-arm64-v8a:159.0.20261102144711` (the arm64-v8a APK)
- Licence: MPL-2.0. Some engine libraries are LGPL-2.1-or-later: `liblgpllibs.so`, and
  `libmozavcodec.so`/`libmozavutil.so` (parts of FFmpeg). Other parts are under other free licences.
- Copyright: Mozilla Foundation and contributors
- Upstream: https://geckoview.dev
- Source: mozilla-release revision `7b34a03a910d0385299f4a9f27aa585a786862d8`,
  https://hg.mozilla.org/releases/mozilla-release/rev/7b34a03a910d0385299f4a9f27aa585a786862d8
- GeckoView's own list of the third-party code it contains, with every licence text, is inside the
  APK (`about:license`, in `assets/omni.ja`). It is also at
  https://hg.mozilla.org/releases/mozilla-release/file/7b34a03a910d0385299f4a9f27aa585a786862d8/toolkit/content/license.html

### The legacy build of Glassworks for generic Android (#198)

`dev.glassworks.generic_android.legacy`, for Android 6 and 7: the `legacy` build type of
[`frames/generic-android/android`](frames/generic-android/android). Its libraries are the generic app's (all
listed in this section), with these differences. `checkLegacyThirdPartyNotices` in that build file fails a legacy
build if one is missing here.

- **GeckoView 144** in place of 159: `org.mozilla.geckoview:geckoview-arm64-v8a:144.0.20251027123126` (the
  arm64-v8a APK), `org.mozilla.geckoview:geckoview-armeabi-v7a:144.0.20251027123126` (the armeabi-v7a APK), and
  `org.mozilla.geckoview:geckoview-exoplayer2:144.0.20251027123126`. Licence, copyright, upstream and `about:license`
  as for GeckoView 159 above. Source: mozilla-release revision `5f480a9ab713ba79e5a14face39aacb038c36f30`,
  https://hg.mozilla.org/releases/mozilla-release/rev/5f480a9ab713ba79e5a14face39aacb038c36f30
- **desugar_jdk_libs** 2.1.5 (`com.android.tools:desugar_jdk_libs:2.1.5`): OpenJDK's `java.time`, `java.util`
  and other classes for Android versions that lack them (core library desugaring). The build compiles the ones the
  app calls into the APK's code. Licence: GPL-2.0-only WITH Classpath-exception-2.0 (`LICENSES/GPL-2.0.txt`,
  `LICENSES/Classpath-exception-2.0.txt`): the exception lets the app link it and keep its own licence.
  Copyright: Oracle and/or its affiliates, and Google LLC. Upstream and source: https://github.com/google/desugar_jdk_libs
  (release 2.1.5). Allowed by the maintainer on 2026-11-16 as the one exception to the repo's no-GPL rule
  (`AGENTS.md`, rule 4), in two apps: this legacy APK, and the legacy engine's debug test app
  (`dev.glassworks.s1.legacy`, never released; test builds on branches named `legacy` upload it). Extended by the
  maintainer on 2026-11-25 to a third: Vitro's debug test app (`dev.glassworks.vitro.test`,
  `engines/engine-vitro/test-app`, minSdk 23, debug only and never released; CI builds it for the emulator jobs).
  Only those three ship its classes. The current generic builds (minSdk 26) set desugaring up too, because AGP turns it on per
  module, but none of its classes go into their APKs. The S1 app doesn't use it.
- **AndroidX and kotlinx.coroutines at GeckoView 144's versions**, which it resolves to in place of the current
  build's (all Apache-2.0; copyright, upstream and source as for the current versions under Kotlin and AndroidX):
  - `androidx.annotation:annotation-experimental:1.5.1`
  - `androidx.annotation:annotation-jvm:1.9.1`
  - `androidx.annotation:annotation:1.9.1`
  - `androidx.collection:collection-jvm:1.5.0`
  - `androidx.collection:collection:1.5.0`
  - `androidx.concurrent:concurrent-futures:1.1.0`
  - `androidx.core:core:1.17.0`
  - `androidx.lifecycle:lifecycle-common-jvm:2.9.3`
  - `androidx.lifecycle:lifecycle-common:2.9.3`
  - `androidx.lifecycle:lifecycle-process:2.9.3`
  - `androidx.lifecycle:lifecycle-runtime-android:2.9.3`
  - `androidx.lifecycle:lifecycle-runtime:2.9.3`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.1`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.1`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1`

### libwebrtc (Vitro's WebRTC camera streams)

With Vitro on, the S1 app and Glassworks for generic Android play Home Assistant cameras over WebRTC with the
[`ha-webrtc`](libs/ha-webrtc/README.md) library, which runs on libwebrtc. Both APKs carry it.

- **WebRTC Android SDK** 150.7871.04, prefixed: `io.github.webrtc-sdk:android-prefixed:150.7871.04`, the
  webrtc-sdk project's build of Chromium's WebRTC M150 (branch-heads/7871) with its Java package renamed to
  `livekit.org.webrtc`. Its Java classes and native library (`liblkjingle_peerconnection_so.so`, one per ABI).
  Licence: BSD-3-Clause, Copyright (c) 2011, The WebRTC project authors, with WebRTC's additional IP rights grant
  (`PATENTS`, in `LICENSES/LicenseRef-libwebrtc-notices.txt`).
  Upstream: https://github.com/webrtc-sdk/android and https://webrtc.googlesource.com/src (branch-heads/7871).
- **Third-party code inside the native library.** The libraries WebRTC's own licence generator
  (`tools_webrtc/libs/generate_licenses.py`) lists for its Android build, at the revisions branch-heads/7871's
  `DEPS` pins:
  - **BoringSSL** (`f91f1447397c6719f9774dfb8e67329378e1f3d3`): Apache-2.0, The BoringSSL Authors; its fiat-crypto
    code Apache-2.0 (or MIT), Copyright 2015-2020 the fiat-crypto authors. https://boringssl.googlesource.com/boringssl
  - **libvpx** (`31af37b1bd2774d11a932c1cd9a3849328375f64`, v1.16.0): BSD-3-Clause, Copyright (c) 2010, The WebM
    Project authors. https://chromium.googlesource.com/webm/libvpx
  - **libaom** (`c213343c8d32bcae729fe09fcba16e1f371cb23b`): BSD-2-Clause, Copyright (c) 2016, Alliance for Open
    Media, with the Alliance for Open Media Patent License 1.0. https://aomedia.googlesource.com/aom
  - **dav1d** (`1718ff9aded99f0a89f5c7940d6afb8948301e33`): BSD-2-Clause, Copyright © 2018-2025, VideoLAN and dav1d
    authors. https://code.videolan.org/videolan/dav1d
  - **libyuv** (`de63bd90f4396313f864ad58b65279e7894451a9`): BSD-3-Clause, Copyright 2011 The LibYuv Project
    Authors. https://chromium.googlesource.com/libyuv/libyuv
  - **Opus** (Chromium's `third_party/opus`, `55513e81`): BSD-3-Clause, Copyright 2001-2023 Xiph.Org, Skype Limited,
    Octasic, Jean-Marc Valin, Timothy B. Terriberry, CSIRO, Gregory Maxwell, Mark Borgerding, Erik de Castro Lopo,
    Mozilla, Amazon. https://opus-codec.org
  - **libsrtp** (`cd5d177bf1fde755ddb4c7f0d9ff7693f8b49e5e`): BSD-3-Clause, Copyright (c) 2001-2017 Cisco Systems,
    Inc. https://chromium.googlesource.com/chromium/deps/libsrtp
  - **crc32c** (`d3d60ac6e0f16780bcfcc825385e1d338801a558`): BSD-3-Clause, Copyright 2017, The CRC32C Authors.
    https://github.com/google/crc32c
  - **Abseil**, **jni_zero**, **rnnoise**, **pffft**, **zlib** and **protobuf** (Chromium's `third_party` at
    `7c92732938de0ef7e28f5da231994723f938f407`): Abseil Apache-2.0 (The Abseil Authors); jni_zero BSD-3-Clause
    (Copyright 2023 The Chromium Authors); rnnoise BSD-3-Clause (Copyright (c) 2017, Mozilla; 2007-2017, Jean-Marc
    Valin; 2005-2017, Xiph.Org Foundation); pffft under its FFTPACK licence (Copyright (c) 2013 Julien Pommier;
    2004 UCAR), text in `LICENSES/LicenseRef-libwebrtc-notices.txt`; zlib Zlib (Jean-loup Gailly and Mark Adler);
    protobuf BSD-3-Clause (Copyright 2008 Google Inc.). https://chromium.googlesource.com/chromium/src/third_party
  - **libc++, libc++abi and libunwind** (LLVM; `5abc7f839700f0f17338434e1c1c6a8c87c00c11`,
    `8f11bb1d4438d0239d0dfc1bd9456a9f31629dda`, `d6c7a21e978f0adaa43accaad53bc64f0b64f6ec`):
    Apache-2.0 WITH LLVM-exception (`LICENSES/LLVM-exception.txt`), the LLVM Project contributors.
    https://github.com/llvm/llvm-project
  - **WebRTC's own bundled code**: G.711 and G.722 (SpanDSP, Steve Underwood, public domain), fft (Copyright 1995,97
    Mark Olesen), Ooura FFT (Copyright Takuya Ooura, 1996-2001), spl_sqrt_floor (Wilco Dijkstra, public domain).
    Their notices, verbatim, are in `LICENSES/LicenseRef-libwebrtc-notices.txt`.

### Libraries the app uses directly

- **LiteRT** (formerly TensorFlow Lite) 1.4.1: `com.google.ai.edge.litert:litert:1.4.1`,
  `com.google.ai.edge.litert:litert-api:1.4.1`. Apache-2.0, Copyright The TensorFlow Authors.
  https://github.com/google-ai-edge/LiteRT (tag `v1.4.1`). Its native library `libtensorflowlite_jni.so`
  also contains TensorFlow's bundled third-party code, for example ruy (Apache-2.0) and cpuinfo
  (BSD-2-Clause). Those are listed in the source tree's `third_party/`.
- **Bouncy Castle** (provider) 1.80: `org.bouncycastle:bcprov-jdk18on:1.80`. MIT (the "Bouncy Castle
  Licence" is the MIT text). Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc.
  (https://www.bouncycastle.org). Source: https://github.com/bcgit/bc-java (tag `r1rv80`).
- **AndroidX Media3** 1.11.1 (ExoPlayer): Apache-2.0, Copyright The Android Open Source Project.
  https://github.com/androidx/media (tag `1.11.1`). Modules: `androidx.media3:media3-exoplayer:1.11.1`,
  `androidx.media3:media3-common:1.11.1`, `androidx.media3:media3-container:1.11.1`,
  `androidx.media3:media3-database:1.11.1`, `androidx.media3:media3-datasource:1.11.1`,
  `androidx.media3:media3-decoder:1.11.1`, `androidx.media3:media3-extractor:1.11.1`,
  `androidx.media3:media3-exoplayer-hls:1.11.1`.
- **ZXing** core 3.5.3: `com.google.zxing:core:3.5.3`. Apache-2.0, Copyright ZXing authors.
  https://github.com/zxing/zxing (tag `zxing-3.5.3`).
- **XZ for Java** 1.10: `org.tukaani:xz:1.10`. 0BSD. https://github.com/tukaani-project/xz-java
  (tag `v1.10`).

### Code ported into the app

- **TensorFlow Lite Micro audio microfrontend** (`tensorflow/lite/experimental/microfrontend/lib`:
  window, fft, filterbank, noise reduction, PCAN gain control, log scale). Apache-2.0, Copyright 2018
  The TensorFlow Authors. Ported to Kotlin in
  `libs/voice/src/main/java/dev/glassworks/voice/MicroFrontend.kt`, which stays under Apache-2.0 and
  says what changed. Ported from the copy in https://github.com/rhasspy/pymicro-features (tag
  `v2.0.2`, commit `96bd69cfad79aa67697e176570d3dd87052c3def`), the same code as
  https://github.com/tensorflow/tflite-micro.
- **kissfft** (the 16-bit fixed-point `kiss_fft` and `kiss_fftr` the microfrontend uses).
  BSD-3-Clause, Copyright (c) 2003-2010 Mark Borgerding. Ported in the same file, from the same copy.
  https://github.com/mborgerding/kissfft
- **android-ip-camera** (the motion detector, the RTSP server and the ONVIF server with
  WS-Discovery). MIT, Copyright (c) 2025 DigitallyRefined and contributors. Ported from the
  maintainer's fork, https://github.com/aguilaair/android-ip-camera (branch `onvif-rtsp-motion`,
  commit `7b5e6f28b7cbcb0f06716365d995e3a5d5cf1050`; `H264Bitstream.kt` as upstream had it at
  `90f9f80f0237832b1251f511592296e5ac5a630a`), itself a fork of
  https://github.com/DigitallyRefined/android-ip-camera. In
  `panes/camera/src/main/java/dev/glassworks/camera/MotionDetectorCore.kt` and
  `panes/camera/src/main/java/dev/glassworks/camera/onvif/`. Those files stay under MIT, and each says
  what changed. `OnvifServer.kt` also includes upstream `ca0085d12591465db309f571ac12f48d8a9f47dc` (a HEAD answer's
  Content-Length) and `c58342b20009b4d0900cad082c35efb2eae26dff` (WS-BaseNotification push subscriptions), synced
  2026-11-20.
- **button-card** (the card's config merging, its `state:` matching, its color rules and its stylesheet's grid
  layouts, for Vitro's native `custom:button-card`). MIT, Copyright (c) 2018, Alexandre Garcia. Ported from
  https://github.com/custom-cards/button-card at tag `v7.0.1` (`src/button-card.ts`, `src/helpers.ts`,
  `src/styles.ts`, `src/common/state_color.ts`) to Kotlin in
  `engines/engine-vitro/src/main/java/dev/glassworks/engine/vitro/cards/ButtonCardModel.kt`, `ButtonCardView.kt`
  and `ButtonCardGrid.kt`, and to TypeScript in `site/src/lib/vitroButtonCard.ts`. Those files stay under MIT.
- **libwebrtc's frame drawer** (`VideoFrameDrawer`'s `drawFrame`, `calculateTransformedRenderSize` and
  `drawTexture`, and `RendererCommon.convertMatrixFromAndroidGraphicsMatrix`, for drawing camera frames with nothing
  allocated per frame). BSD-3-Clause, Copyright (c) 2011, The WebRTC project authors, with WebRTC's additional IP
  rights grant (`LICENSES/LicenseRef-libwebrtc-notices.txt`). Reproduced from libwebrtc M150
  (https://webrtc.googlesource.com/src, branch-heads/7871, `sdk/android/api/org/webrtc`) in Kotlin in
  `libs/ha-webrtc/src/main/java/dev/glassworks/hawebrtc/TextureFrameDrawer.kt`, which keeps those steps under
  BSD-3-Clause and says what changed (`libs/ha-webrtc/NOTICE`).
- **weather-card** (its layout, units and icon names, for Vitro's native `custom:weather-card`). MIT, Copyright (c)
  2019 Bram Kragten. Ported from https://github.com/bramkragten/weather-card at tag `v1.5.0` in
  `engines/engine-vitro/src/main/java/dev/glassworks/engine/vitro/cards/HacsFastPathCards.kt`. The card's icons are
  not in the app: Vitro loads them from the address the card names, as the browser does.
- **swipe-card** and the **Swiper** pagination it shows (the pager's sizing and its bullets, for Vitro's native
  `custom:swipe-card`). swipe-card: MIT (its `package.json`), Bram Kragten, https://github.com/bramkragten/swipe-card at
  tag `v5.0.0`. Swiper 6: MIT, Copyright (c) Vladimir Kharlampidi, https://github.com/nolimits4web/swiper. Ported in
  the same file, which stays under MIT.

### Kotlin and AndroidX

Apache-2.0. Kotlin is Copyright JetBrains s.r.o. and Kotlin Programming Language contributors
(https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines). AndroidX is
Copyright The Android Open Source Project (https://cs.android.com/androidx/platform/frameworks/support,
https://developer.android.com/jetpack/androidx). Most of them come in through GeckoView and Media3.

- `org.jetbrains.kotlin:kotlin-stdlib:2.4.20`
- `org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.9.0` (version list only, no code)
- `org.jetbrains:annotations:23.0.0` (https://github.com/JetBrains/java-annotations)
- `androidx.activity:activity:1.0.0`
- `androidx.annotation:annotation:1.11.0`
- `androidx.annotation:annotation-jvm:1.11.0`
- `androidx.annotation:annotation-experimental:1.6.0`
- `androidx.arch.core:core-common:2.2.0`
- `androidx.arch.core:core-runtime:2.2.0`
- `androidx.collection:collection:1.6.0`
- `androidx.collection:collection-jvm:1.6.0`
- `androidx.concurrent:concurrent-futures:1.2.0`
- `androidx.core:core:1.19.1`
- `androidx.core:core-viewtree:1.0.0`
- `androidx.customview:customview:1.0.0`
- `androidx.exifinterface:exifinterface:1.3.6`
- `androidx.fragment:fragment:1.1.0`
- `androidx.interpolator:interpolator:1.0.0`
- `androidx.lifecycle:lifecycle-common:2.11.0`
- `androidx.lifecycle:lifecycle-common-jvm:2.11.0`
- `androidx.lifecycle:lifecycle-livedata:2.11.0`
- `androidx.lifecycle:lifecycle-livedata-core:2.11.0`
- `androidx.lifecycle:lifecycle-livedata-core-ktx:2.11.0`
- `androidx.lifecycle:lifecycle-process:2.11.0`
- `androidx.lifecycle:lifecycle-runtime:2.11.0`
- `androidx.lifecycle:lifecycle-runtime-android:2.11.0`
- `androidx.lifecycle:lifecycle-viewmodel:2.11.0`
- `androidx.lifecycle:lifecycle-viewmodel-android:2.11.0`
- `androidx.loader:loader:1.0.0`
- `androidx.profileinstaller:profileinstaller:1.4.0`
- `androidx.savedstate:savedstate:1.0.0`
- `androidx.startup:startup-runtime:1.1.1`
- `androidx.tracing:tracing:1.2.0`
- `androidx.versionedparcelable:versionedparcelable:1.1.1`
- `androidx.viewpager:viewpager:1.0.0`

### Other open-source libraries pulled in by GeckoView or Media3

All Apache-2.0.

- **Guava** (Copyright The Guava Authors, https://github.com/google/guava):
  `com.google.guava:guava:33.3.1-android`, `com.google.guava:failureaccess:1.0.2`,
  `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` (an empty placeholder).
- **SnakeYAML** 2.2: `org.yaml:snakeyaml:2.2`. Copyright (c) 2008, SnakeYAML.
  https://bitbucket.org/snakeyaml/snakeyaml
- **JSpecify** 1.0.0: `org.jspecify:jspecify:1.0.0`. Copyright The JSpecify Authors.
  https://github.com/jspecify/jspecify

### Not included: Google Play services client libraries

GeckoView depends on Google's proprietary Play services client libraries (`play-services-fido`,
`-base`, `-basement`, `-tasks`) for WebAuthn/passkeys. The app's build excludes them
(`engines/engine-gecko/build.gradle.kts`), as Firefox's F-Droid builds do: the panel has no Google Play services,
so they could never run. Passkey sign-in is therefore not available in the kiosk's browser.

### Assets in the APK

- `assets/extension/` (the built-in kiosk web extension): this project, MIT.
- `assets/licenses/`: this file and `LICENSES/`, copied at build time.
- `res/font/inter.ttf`: **Inter** 4.001, the variable font (weight and optical-size axes), unmodified.
  - Licence: OFL-1.1 (SIL Open Font License 1.1)
  - Copyright: 2020 The Inter Project Authors
  - Upstream: https://github.com/rsms/inter
  - Source: google/fonts, `ofl/inter/Inter[opsz,wght].ttf` at commit
    `e1d6480102fed30739fead0faee463101f892c8f` (SHA-256
    `29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031`)
  - Used by the app's own screens: voice overlay, timers, settings menu, setup and loading screens.
- `assets/setup/`: the setup page the panel serves at `/setup` (#34), a build of Kiln's setup steps
  (`kiln/apps/kiln/lan/`). This project's code is MIT. It bundles:
  - **React** and **React DOM** 19.3.0, **scheduler** 0.28.0: MIT, "Copyright (c) Meta Platforms, Inc.
    and affiliates." https://github.com/facebook/react. Their `@license` headers stay in `setup.js`.
  - **Radix Primitives** `@radix-ui/react-radio-group` 1.5.1 and `@radix-ui/react-collapsible` 1.2.1,
    with the `@radix-ui/*` packages they use (a subset of those listed under Kiln in section 4): MIT,
    "Copyright (c) 2022 WorkOS". https://github.com/radix-ui/primitives
  - The licence text: `LICENSES/MIT.txt`.
- `assets/vitro-mdi.tsv.gz` (in APKs that carry the Vitro engine, `engines/engine-vitro`): **Material Design
  Icons**, `@mdi/svg` 7.4.47, all 7,447 icons. Only the `d` path data of each SVG, unmodified, stored as a
  gzipped `name<TAB>pathData` table that `tools/vitro/update_mdi_icons.py` regenerates from the npm tarball.
  - Licence: Apache-2.0 (`LICENSES/Apache-2.0.txt`)
  - Copyright: Pictogrammers (Austin Andrews and contributors)
  - Upstream: https://pictogrammers.com/library/mdi/
  - Source: https://github.com/Templarian/MaterialDesign-SVG (npm `@mdi/svg` 7.4.47)
  - Used by Vitro to draw the same icons Home Assistant shows.
- `dev/glassworks/engine/vitro/locale/*.json` (Java resources, in APKs that carry the Vitro engine): Vitro's
  **locale tables**, the number marks, plural rules, relative-time patterns, month, weekday and day-period names and
  week data it formats numbers, dates and times with. They are **Unicode CLDR** data, captured by
  `tools/vitro-parity/locale-data.mjs` from the `Intl` of Chromium 141.0.7390.37, as `languages.json` records (ICU 74
  data with CLDR 44.1, read from that build's `icudtl.dat`), and, for the 16 languages whose tables say
  `"source":"node"`, from Node.js's ICU, whose version `languages.json` records from the next regeneration on.
  - Licence: Unicode-3.0 (`LICENSES/Unicode-3.0.txt`)
  - Copyright: © 1991-2025 Unicode, Inc.
  - Upstream: https://cldr.unicode.org
  - Source: https://github.com/unicode-org/cldr (through ICU, https://github.com/unicode-org/icu)
- `dev.glassworks.platform.HttpsTrust` (in the code, as text): **ISRG Root X1**, Let's Encrypt's root CA
  certificate, unmodified, from https://letsencrypt.org/certs/isrgrootx1.pem (SHA-256
  `96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6`, the same file as AOSP's
  `system/ca-certificates/files/6187b673.0`). A public certificate published for anyone to trust; its
  publisher attaches no licence terms, and every OS and browser CA store ships it. The app trusts it next to
  the system store on Android 6 and 7.0 only, which lack it (#456,
  [android-ca-roots.md](docs/research/android-ca-roots.md)).
- No machine-learning models are bundled by the app. The wake word models are downloaded by the
  panel at runtime (section 5). GeckoView's `omni.ja` has its own assets, such as the Liberation fonts
  used by its PDF viewer (SIL OFL 1.1). Those are listed in `about:license`.
- Release APKs also carry the radio bundle in `assets/radio-bundle/` (section 2).

### Not in the APK

- JUnit 4.13.2 (EPL-1.0) and org.json 20250517 (Public Domain) are used only by the unit tests.
- Bouncy Castle `bcpkix-jdk18on` 1.80 (Bouncy Castle Licence, MIT-style) makes made-up certificates in
  `HttpsTrustTest`, a unit test only. `pane-api/src/test/resources/dev/glassworks/platform/githubusercontent-chain-2026-11-16.pem`
  is the public certificate chain `raw.githubusercontent.com` served on 2026-11-16, a test fixture.
- `panes/assist/src/test/resources/mww_clip.wav` (a unit test fixture, not in the APK): speech
  synthesised with Piper's `en_US-kristin-medium` voice (https://huggingface.co/rhasspy/piper-voices,
  MIT; trained from scratch on public-domain LibriVox recordings) by `tools/voice/mww_reference.py`.
  No recording of a person.
- `engines/engine-vitro/src/test/ha-frontend/hass-types.ts` (Vitro's island contract test, not in the APK): the
  `HomeAssistant` interfaces of home-assistant/frontend's `src/types.ts` (commit
  `421f8b19861cacc162da79a0459f522db2c1ec30`) and the public members of home-assistant-js-websocket's `Connection`
  (commit `afcb55a4f76fa9855de52ea8fdb9717c314da267`), trimmed. Apache-2.0 (`LICENSES/Apache-2.0.txt`), Copyright
  Home Assistant contributors and Paulus Schoutsen and contributors.
- `engines/engine-vitro/src/test/parity/fixtures/fonts/` (Vitro's web-font tests and parity cases, not in the APK):
  three fonts from google/fonts at commit `83c62e30d87daa70216dab6b984d00d20d96d585`, subset to Latin with
  fontTools `pyftsubset` and saved as TTF, WOFF and WOFF2. None has a Reserved Font Name. Licence: OFL-1.1
  (`LICENSES/OFL-1.1.txt`; each font's own `OFL.txt` sits next to it).
  - **Bitter** (`ofl/bitter/Bitter[wght].ttf`, SHA-256 `ef2b9a711fb02f1e5823b34da1b7450e0fc76793b7d733a8b41006e24916d4a7`):
    Copyright 2011 The Bitter Project Authors.
  - **Pacifico** (`ofl/pacifico/Pacifico-Regular.ttf`, SHA-256 `5b6c0d5334a7bf77dea52b975c5a0c408878c0f7115ed5b6fb151f634b7bf701`):
    Copyright 2018 The Pacifico Project Authors.
  - **Comic Neue** (`ofl/comicneue/ComicNeue-Regular.ttf` and `-Bold.ttf`, SHA-256
    `a0ee5a37c8b27c4db0700137d928598b1e23b0089e1546a8961909176b779360` and
    `3e7e5fccfd7e0788f317b43312151c1bd5cf058c9697a8d83eac3939050bd61e`): Copyright 2014 The Comic Neue Project Authors.

---

## 2. Radio bundle (inside release APKs)

`assets/radio-bundle/`, made and signed by `frames/aqara-s1-plus/tools/radio/make-bundle.py` in the release workflow. The
panel installs it into its root side. Contents:

### `s1kiosk_radio.sh`

The radio helper, this project, MIT ([`frames/aqara-s1-plus/tools/radio/s1kiosk_radio.sh`](frames/aqara-s1-plus/tools/radio/s1kiosk_radio.sh)).

### `otbr-agent`, `ot-ctl`

OpenThread Border Router, the same binaries as the installer's (section 3). They are statically
linked, so they contain the following:

| Part | Version | Licence | Copyright | Source |
|---|---|---|---|---|
| ot-br-posix | `v2026.11.0` (`76fb21d666a41050b43f8eaabc30e71d9d75d9fd`) | BSD-3-Clause | (c) 2017, The OpenThread Authors | https://github.com/openthread/ot-br-posix |
| OpenThread | `ecd308b4a00f76d643b352f8f5c4ffc287bb33a2` | BSD-3-Clause | (c) 2016, The OpenThread Authors | https://github.com/openthread/openthread |
| Mbed TLS | `v3.6.5` (`e185d7fd85499c8ce5ca2a54f5cf8fe7dbe3f8df`) | Apache-2.0 (dual-licensed with GPL-2.0-or-later; used under Apache-2.0) | The Mbed TLS Contributors | https://github.com/Mbed-TLS/mbedtls |
| cJSON | `cf97c6f066d81fdbba4ef722cfd327bbbba2365c` | MIT | (c) 2009-2017 Dave Gamble and cJSON contributors | https://github.com/DaveGamble/cJSON |
| cpp-httplib | `03cf43ebaa55f27a2778bed870ea3549f7e84e2c` | MIT | (c) 2017 yhirose | https://github.com/yhirose/cpp-httplib |
| Silicon Labs CPC vendor interface (`cpc_interface.cpp`) | Simplicity SDK `v2025.6.3` (`b41bec3ff2485199c1a5a9995b3e649e118c1b8d`) | Zlib | Copyright 2024 Silicon Laboratories Inc. | https://github.com/SiliconLabs/simplicity_sdk |
| Silicon Labs libcpc (`otbr-agent` only) | cpc-daemon `v4.3.3` (`684595db6be6534f856cfc7455551e71567298ee`) | LicenseRef-Silabs-MSLA (source-code sections) | Copyright 2018 Silicon Laboratories Inc. | https://github.com/SiliconLabs/cpc-daemon |
| GNU C Library (static) | 2.35, Ubuntu 22.04 `libc6-dev-armhf-cross` 2.35-0ubuntu1cross3 | LGPL-2.1-or-later | Free Software Foundation, Inc. and others | section 6 |
| GCC runtime, libstdc++ (static) | GCC 11.4.0, Ubuntu `11.4.0-1ubuntu1~22.04.3` | GPL-3.0-or-later WITH GCC-exception-3.1 | Free Software Foundation, Inc. | section 6 |

The build and all its pins are in [`frames/aqara-s1-plus/tools/radio/build-otbr.sh`](frames/aqara-s1-plus/tools/radio/build-otbr.sh) (`PROFILE=br`).
The libcpc in `otbr-agent` has one build-time change: it reads the socket directory from
`$CPC_SOCKET_DIR`.

### `sisdk/` (runtime for the Silicon Labs Zigbee stack)

Built by [`frames/aqara-s1-plus/tools/radio/build-sisdk-runtime.sh`](frames/aqara-s1-plus/tools/radio/build-sisdk-runtime.sh). It must match the
committed [`frames/aqara-s1-plus/tools/radio/sisdk-runtime.sha256`](frames/aqara-s1-plus/tools/radio/sisdk-runtime.sha256). It has its own
`NOTICE.txt` and `licenses/`, which go into the bundle with it.

- `sisdk/zigbeed.sh`: this project, MIT.
- `sisdk/lib/libcpc.so.3`: Silicon Labs cpc-daemon `v4.3.3` (`684595db6be6534f856cfc7455551e71567298ee`),
  compiled unmodified. LicenseRef-Silabs-MSLA (source-code sections), Copyright 2018 Silicon
  Laboratories Inc., https://github.com/SiliconLabs/cpc-daemon. It is provided only for use with the
  Silicon Labs radio inside the panel, as part of this bundle. See `sisdk/NOTICE.txt` and
  https://www.silabs.com/about-us/legal/master-software-license-agreement.
- `sisdk/lib/ld-linux-armhf.so.3`, `libc.so.6`, `libm.so.6`: GNU C Library 2.35, unmodified, from Ubuntu
  22.04 `libc6-armhf-cross`. LGPL-2.1-or-later. Source: section 6.
- `sisdk/lib/libstdc++.so.6`, `libgcc_s.so.1`: GCC 12 runtime, unmodified, from Ubuntu 22.04
  `libstdc++6-armhf-cross` / `libgcc-s1-armhf-cross`. GPL-3.0-or-later WITH GCC-exception-3.1.
  Source: section 6.
- **Not included:** Silicon Labs' `zigbeed` (section 5).

The bundle carries no radio firmware.

---

## 3. Web installer downloads

The files Kiln (https://kiln.glassworks.dev) serves and writes to the panel. Pinned by SHA-256 in
[`kiln/assets.lock.json`](kiln/assets.lock.json) and in `kiln/scripts/stage-assets.mjs`.

### U-Boot SPL and U-Boot (`u-boot-sunxi-with-spl.bin`, `u-boot.bin`)

- Licence: GPL-2.0-or-later. Copyright Wolfgang Denk and the U-Boot contributors.
- Version: U-Boot `2027.01-rc1`, built 2026-11-10 04:34 UTC.
- Release files: GitHub release `installer-files-2`. sha256 `b2e6820a…1ffa` (`u-boot-sunxi-with-spl.bin`)
  and `ea0050fd…b98e` (`u-boot.bin`).
- Corresponding source: upstream U-Boot `main` at commit `934995f642a21ca7f35fa9aed5f061394fcba243`
  (https://github.com/u-boot/u-boot/commit/934995f642a21ca7f35fa9aed5f061394fcba243). Add this
  repository's [`tools/fel/uboot/`](https://github.com/GlassworksHQ/glassworks/tree/8b2bf488fed3c7ba8343a32c67c9651066479128/tools/fel/uboot) at commit `8b2bf488fed3c7ba8343a32c67c9651066479128`
  (now [`frames/aqara-s1-plus/tools/fel/uboot/`](frames/aqara-s1-plus/tools/fel/uboot)):
  - `patches/0001-sunxi-a133-ddr4-match-boot0-controller-setup.patch`
  - `patches/0002-fastboot-add-upload-command.patch`
  - `aqara-s1-plus_defconfig`
  - `sun50i-a133-aqara-s1-plus.dts`

  Build with [`frames/aqara-s1-plus/tools/fel/build-uboot.sh`](frames/aqara-s1-plus/tools/fel/build-uboot.sh) (arm64 `ubuntu:22.04`, gcc
  `11.4.0-1ubuntu1~22.04.3`). A rebuild is not byte-identical, because U-Boot embeds the build time.
- `u-boot-sunxi-with-spl.bin` also contains a TF-A BL31 (below). The installer starts U-Boot without
  it.

### Trusted Firmware-A BL31 (inside `u-boot-sunxi-with-spl.bin`)

- Licence: BSD-3-Clause. Copyright (c) Arm Limited and Contributors. https://www.trustedfirmware.org/projects/tf-a/
- Version: `v2.15.0` debug build for `sun50i_a133`, from upstream commit
  `a413e1d7d38ed42dcdffc690d52a0cceb142a0e7` (https://github.com/ARM-software/arm-trusted-firmware),
  with local debugging changes, kept as a patch in [`frames/aqara-s1-plus/tools/fel/tfa/`](frames/aqara-s1-plus/tools/fel/tfa/). The installer never
  runs it.

### `otbr-agent`, `ot-ctl`

The OpenThread Border Router build described in section 2. Release `installer-files-2`.

### `s1kiosk_verify`

The Ed25519 checker for radio bundles. Release `installer-files-3`, sha256 `6aa0dd83…9f80`.

- `frames/aqara-s1-plus/tools/root/verify/s1kiosk_verify.c`: this project, MIT.
- **Monocypher 4.0.3**: BSD-2-Clause (dual-licensed with CC0-1.0; used under BSD-2-Clause).
  Copyright (c) 2017-2023, Loup Vaillant; Copyright (c) 2017-2019, Michael Savage; Copyright (c)
  2017-2023, Fabio Scotoni. https://monocypher.org (`monocypher-4.0.3.tar.gz`, sha256
  `8cc9bc341a66249016db9bd70e9142d8d0aef9945973744b1ac05dbc55d8ee66`).
- **musl libc** (static), Alpine 3.20: MIT. Copyright © 2005-2020 Rich Felker, et al.
  https://musl.libc.org
- Reproducible build: [`frames/aqara-s1-plus/tools/root/build-verify.sh`](frames/aqara-s1-plus/tools/root/build-verify.sh).

### Magisk v31.1

- Licence: GPL-3.0-only. Copyright John Wu (topjohnwu) and contributors.
- The official release APK, unmodified (sha256 `89cefac0…c6e7`), served by the installer. The
  installer also writes four libraries from it into the panel's boot image: `libmagiskinit.so`,
  `libmagisk.so`, `libinit-ld.so` and `libbusybox.so` (BusyBox 1.36.1, GPL-2.0-only, Magisk's build of
  https://github.com/topjohnwu/ndk-busybox, branch `android-ndk-1.36.1`). At boot the panel copies BusyBox to
  `/data/adb/magisk`, where Magisk needs it.
- Corresponding source: tag `v31.1` of https://github.com/topjohnwu/Magisk
  (https://github.com/topjohnwu/Magisk/releases/tag/v31.1).

### Simple Keyboard

- `rkr.simplekeyboard.inputmethod`, F-Droid build 149, unmodified. Apache-2.0. Copyright the Simple
  Keyboard authors. https://github.com/rkkr/simple-keyboard

### Glass Panes system images (lululemon Studio Mirror)

- By git-nimrod, https://github.com/git-nimrod/glass-panes. The project's own code is MIT. The Android and
  kernel sources in the images keep their own licences, and the vendor binaries in them (Qualcomm,
  Quectel) belong to their owners; the image's release grants no licence for those.
- Kiln fetches the release files from Glass Panes' own GitHub release through Refract
  ([`kiln/workers/refract`](kiln/workers/refract)), our pass-through proxy at `refract.glassworks.dev`. Refract
  streams the files with CORS headers, stores nothing, serves only the files in its allowlist (pinned by
  size and SHA-256 in [`kiln/workers/refract/allowlist.json`](kiln/workers/refract/allowlist.json)) and
  credits Glass Panes on every answer. Kiln checks each file's SHA-256 and changes its copy in the browser
  before writing it to the Mirror. Why, and the terms we read:
  [community-image-terms.md](frames/lululemon-mirror/research/community-image-terms.md).

### The Mirror's backup kernel module (`selinux_permissive.ko`, `tools/mirror` only)

- Licence: GPL-2.0-only. Glassworks' own build, published with its Corresponding Source in the separate
  [GlassworksHQ/glassworks-device-bins](https://github.com/GlassworksHQ/glassworks-device-bins) repository
  (`frames/lululemon-mirror/selinux-permissive`, release `lululemon-mirror-selinux-permissive-v1`).
- Kiln's web installer doesn't use it. Only the optional `--backup` step of the Mirror's command-line tool
  ([`tools/mirror`](tools/mirror)) does: the tool fetches it through Refract, checks its size and SHA-256
  against [`tools/mirror/device-bins.json`](tools/mirror/device-bins.json) (held to Refract's allowlist by
  `tools/check-device-bins.py`), and embeds it in a one-boot patch on the Mirror. It isn't in this
  repository or in any APK. `--backup-module` takes a module you built yourself instead.

### This project's files

All MIT, from this repository:
- The frame loader, the root helpers and their init file: `frames/aqara-s1-plus/tools/root/glassworks_*.sh`,
  `frames/aqara-s1-plus/tools/root/glassworks.rc`, `frames/aqara-s1-plus/tools/radio/glassworks_agent.sh`, and for one
  release the old radio bundle's `frames/aqara-s1-plus/tools/radio/s1kiosk_radio.sh` and
  `frames/aqara-s1-plus/tools/root/s1kiosk_bundle.sh`.
- `frames/aqara-s1-plus/tools/root/glassworks_bundle.pub`.
- The boot branding in `branding/glass-tile/` (`bootanimation.zip`, `bootlogo.bmp`). It shows text set
  in Inter (SIL OFL 1.1, fetched at build time). These downloads carry only images made with the font;
  the font itself ships in the S1 app's APK (section 1, "Assets in the APK").
- The S1 app's APK (sections 1 and 2), except the Glassworks core inside it, which is MPL-2.0
  ([its README](pane-api/src/main/java/dev/glassworks/README.md#licence)).
- The Mirror's settings and framework overlays, `frames/lululemon-mirror/overlay/GlassworksSettings.apk`
  and `GlassworksFramework.apk` (resource values only, built by its `build.sh`). Each is signed with AOSP's public test key
  (`build/target/product/security/testkey`, android-6.0.1_r81, Apache-2.0, Copyright The Android Open
  Source Project), so each signature block carries that public certificate. The private key is
  downloaded at build time and never committed.

---

## 4. The web installer page itself

The page's JavaScript and WebAssembly ([`kiln/`](kiln)):

- **Tango / ya-webadb**: MIT, Copyright (c) 2020-2025 Simon Chan. https://github.com/yume-chan/ya-webadb.
  `@yume-chan/adb` 2.6.4, `@yume-chan/adb-daemon-webusb` 2.3.2, `@yume-chan/stream-extra` 2.6.1, and
  their `@yume-chan/*` dependencies.
- **fastboot.js**: MIT, "Copyright (c) 2021 Danny Lin <danny@kdrag0n.dev>".
  https://github.com/kdrag0n/fastboot.js, commit `5b613332aa9d66cca5bebb49f147cd084a76c464`.
  [`kiln/packages/fastboot`](kiln/packages/fastboot) is a fork of its `fastboot.ts`, `sparse.ts` and
  `common.ts`, changed by this project. Its licence is in `kiln/packages/fastboot/LICENSE`, and what we
  changed is in that package's README.
- **`bootpatch.wasm`**: this project's `kiln/packages/bootpatch/src/wasm` (MIT), statically linked with these
  crates:
  - flate2 1.1.10 (MIT OR Apache-2.0)
  - zlib-rs 0.6.8 (Zlib)
  - lzma-rust2 0.21.0 (Apache-2.0)
  - sha2 0.11.1, digest 0.11.3, block-buffer 0.12.1, crypto-common 0.2.2, const-oid 0.10.2,
    hybrid-array 0.4.15, typenum 1.20.1 and cfg-if 1.0.5 (MIT OR Apache-2.0)
  - the Rust standard library (MIT OR Apache-2.0)

  The list is in `kiln/packages/bootpatch/src/wasm/THIRD_PARTY.txt`.
- **Kiln's boot patcher** ([`kiln/packages/bootpatch`](kiln/packages/bootpatch)): MIT, this project's
  own code. It was rewritten in a clean room in 2026 from
  [docs/research/kiln-cleanroom/bootpatch-spec.md](docs/research/kiln-cleanroom/bootpatch-spec.md)
  ([#355](https://github.com/GlassworksHQ/glassworks/issues/355)) and holds no Magisk code. It writes
  Magisk's own binaries into the boot image. They come from the official Magisk v31.1 APK, fetched
  from Magisk's release and pinned by SHA-256 in `kiln/assets.lock.json` (section 3).
- **The fel-to-spl thunk from sunxi-tools** (https://github.com/linux-sunxi/sunxi-tools, commit
  `34dfa20f8284273085666436e4cac09af19646c8`, `thunks/fel-to-spl-thunk.S`): 69 ARM words in
  [`kiln/packages/fel/src/thunk.ts`](kiln/packages/fel/src/thunk.ts), with its licence. MIT,
  "Copyright © 2015 Siarhei Siamashka". This file alone was always MIT, unlike the rest of sunxi-tools.
  The FEL client in `kiln/packages/fel` was rewritten clean-room from
  [docs/research/kiln-cleanroom/fel-spec.md](docs/research/kiln-cleanroom/fel-spec.md) (#355).
  `src/a133.ts` is kept: it holds chip facts (addresses and ids), not code. They were first taken
  from sunxi-tools' `soc_info.c`, and the spec lists their public sources.

### Kiln, the Next.js installer

[`kiln/apps/kiln`](kiln/apps/kiln) serves the same installer code (above) through Next.js, at
https://kiln.glassworks.dev (the old address, s1.edm.sh, redirects there). Checked against its lockfile, each package's own licence file and a production
build on 2026-11-12. The copyright lines are copied from those files. Where a package has no licence
file, its `package.json` licence and author are given. What it adds:

- **Next.js** 16.4.6 (`next`): MIT, "Copyright (c) 2025 Vercel, Inc." https://github.com/vercel/next.js
  (tag `v16.4.6`). Next ships third-party code it has compiled in under `next/dist/compiled/`, each
  with its licence file there. That includes the copies of React and React DOM it serves App Router
  pages with.
- **React** and **React DOM** 19.3.0, **scheduler** 0.28.0: MIT, "Copyright (c) Meta Platforms, Inc. and
  affiliates." https://github.com/facebook/react
- **styled-jsx** 5.1.6: MIT, "Copyright (c) 2016-present Vercel, Inc." **client-only** 0.0.1: MIT (no
  licence file in the package). Both are part of Next's server runtime.
- **@swc/helpers** 0.5.23: Apache-2.0 (the licence file has no copyright line; `package.json` names
  강동윤 as author). https://github.com/swc-project/swc
- **Radix Primitives**: MIT, "Copyright (c) 2022 WorkOS" (the same line in each package).
  https://github.com/radix-ui/primitives. `@radix-ui/react-dialog` 1.3.1, `@radix-ui/react-radio-group`
  1.5.1, `@radix-ui/react-collapsible` 1.2.1, and the `@radix-ui/*` packages they use: `primitive`
  1.2.0, `react-collection` 1.2.0, `react-compose-refs` 1.1.5, `react-context` 1.3.0, `react-direction`
  1.2.0, `react-dismissable-layer` 1.2.1, `react-focus-guards` 1.2.0, `react-focus-scope` 1.3.1,
  `react-id` 1.1.4, `react-portal` 1.2.0, `react-presence` 1.1.11, `react-primitive` 2.2.0,
  `react-roving-focus` 1.2.1, `react-slot` 1.5.0, `react-use-callback-ref` 1.1.5,
  `react-use-controllable-state` 1.3.0, `react-use-effect-event` 0.0.5, `react-use-is-hydrated` 0.1.3,
  `react-use-layout-effect` 1.1.4, `react-use-size` 1.1.5.
- What Radix's dialog uses, all MIT (https://github.com/theKashey):
  - "Copyright (c) 2017 Anton Korzunov": **react-remove-scroll** 2.7.2, **react-style-singleton**
    2.2.3, **use-callback-ref** 1.3.3, **use-sidecar** 1.1.3, **aria-hidden** 1.2.6.
  - "Copyright (c) 2020 Anton Korzunov": **get-nonce** 1.0.1.
  - **react-remove-scroll-bar** 2.3.8: no licence file in the package; `package.json` names Anton
    Korzunov as author.
  - **detect-node-es** 1.1.0: "Copyright (c) 2017 Ilya Kantor".
  - **tslib** 2.9.1: 0BSD, "Copyright (c) Microsoft Corporation."
- **Inter** and **JetBrains Mono** (the font files, served by `next/font`): OFL-1.1. "Copyright (c) 2016
  The Inter Project Authors (https://github.com/rsms/inter)"; "Copyright 2020 The JetBrains Mono Project
  Authors (https://github.com/JetBrains/JetBrainsMono)". Fetched from Google Fonts at build time.
- The Glassworks tokens, mark, and Kiln's favicon and app icon (`kiln/packages/brand`) are Glassworks' own, from
  the site's brand source in [`site/`](site) (until 2026-11-15 its own repo, GlassworksHQ/glassworks.dev).
- **The device renders** (`frames/<id>/design/render.webp`, on the frame cards): screenshots of the
  **Home Assistant frontend** 20261028.5 (Apache-2.0, Copyright Home Assistant contributors,
  https://github.com/home-assistant/frontend) drawing a made-up dashboard, made by
  `tools/showcase/render.mjs`. The TV still and the album art in them are drawn by
  `tools/showcase/home.py` (this project's, MIT). The two camera stills are photos from Wikimedia
  Commons, both **CC0 1.0** (public domain), cropped, graded to dusk, resized and, for the first, a sign post blurred
  (`tools/showcase/photos/`, with `make.py` and a README):
  - `front-garden.jpg`: "Aigantighe Art Gallery - 06 - House and driveway", David Nind, 2019,
    https://commons.wikimedia.org/wiki/File:Aigantighe_Art_Gallery_-_06_-_House_and_driveway.jpg
  - `driveway.jpg`: "Driveway - 30 May 2026", Aethonatic,
    https://commons.wikimedia.org/wiki/File:Driveway_-_30_May_2026.jpg

  The images show the frontend's own look, with the **Material Design Icons** it bundles (`@mdi/js`, Apache-2.0, Copyright Pictogrammers, https://github.com/Templarian/MaterialDesign)
  and text in the **Roboto** font it serves (Google). Only rendered pixels are kept: no code, icon file
  or font file from the frontend is in the images or in Kiln. The frontend's licence was read from its
  wheel's `METADATA` (`License-Expression: Apache-2.0`) on 2026-11-13. [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt).

Not shipped, by the build's traces:

- Next's build tools: `@next/env`, `@next/swc-*`, postcss, caniuse-lite, baseline-browser-mapping, and
  their dependencies.
- **sharp**, its LGPL libvips (`@img/*`), and `detect-libc`, `semver` and `@emnapi/runtime`. These appear
  only in the trace of the full `next start` server (`.next/next-server.js.nft.json`), with or without
  `images.unoptimized`. The minimal server's trace (`.next/next-minimal-server.js.nft.json`) lists only
  `next`, `react`, `client-only` and `styled-jsx`. Each route's trace lists only `next` and
  `@swc/helpers`.

Check which server a deployment runs on the deployed function's files whenever Kiln's Next.js version or
its deployment changes. If sharp, `@img/*`, `detect-libc`, `semver` or `@emnapi/runtime` are there, they need entries
here, and `semver` (ISC) needs `LICENSES/ISC.txt`.

### glassworks.dev, the project site

[`site/`](site) is the static site at https://www.glassworks.dev (MIT, this project's). Checked
against the lockfile on 2026-11-15. What it ships, beyond this project's own code and the frame
renders above:

- **Next.js** 16.4.6, **React** and **React DOM** 19.3.0, **scheduler** 0.28.0, **styled-jsx** 5.1.6
  and **@swc/helpers** 0.5.23: the same packages and licences as Kiln's, above.
- **Inter** and **JetBrains Mono**: OFL-1.1, the same lines as Kiln's, above. The pages get the font
  files from `next/font`. The badges, banners and icons in `site/public/brand/` hold their text as
  outlines of these fonts' glyphs, drawn at build time from **@fontsource/inter** and
  **@fontsource/jetbrains-mono** 5.3.0 (OFL-1.1) with **opentype.js** 2.0.0 (MIT, "Copyright (c) 2020
  Frederik De Bleser", https://github.com/opentypejs/opentype.js). opentype.js only runs at build
  time and isn't shipped.
- **Pagefind** 1.6.0 (MIT, "Copyright (c) Pagefind", https://github.com/Pagefind/pagefind): the docs'
  search. It builds the index after `next build`, and its browser script, WebAssembly and index are
  served under `/_next/static/pagefind/`.
- The docs are rendered from the repo's Markdown at build time with **unified** 11.0.5,
  **remark-parse** 11.0.0, **remark-gfm** 4.0.1, **remark-rehype** 11.1.2, **rehype-raw** 7.0.0,
  **rehype-sanitize** 6.0.0, **rehype-slug** 6.0.0, **rehype-stringify** 10.0.1,
  **unist-util-visit** 5.2.0, **hast-util-to-string** 3.0.1 and **hast-util-to-html** 9.0.6 (all
  MIT, "Copyright (c) Titus Wormer", https://github.com/unifiedjs), **github-slugger** 2.0.0 (ISC, `LICENSES/ISC.txt`,
  "Copyright (c) 2015, Dan Flettre"), **@shikijs/rehype** 4.6.0 and **Shiki** (MIT, "Copyright (c)
  2021 Pine Wu" and "Copyright (c) 2023 Anthony Fu", https://github.com/shikijs/shiki), with its
  GitHub Light and GitHub Dark themes from **@shikijs/themes** (MIT), and **image-size**
  2.1.0 (MIT, "Copyright © 2013-Present Aditya Yadav", https://github.com/image-size/image-size).
  They only run at build time and aren't shipped; the pages carry the HTML they produce and
  Shiki's colours.

---

## 5. Not distributed by this project

- **Silicon Labs `zigbeed`** (EmberZNet 8.2.2). This is Silicon Labs object code under the Silicon Labs
  MSLA. The project neither ships nor hosts it. The panel downloads it from Silicon Labs' own release
  (`SiliconLabs/simplicity_sdk` `v2025.6.3`, `debian-bookworm.zip`) only when the Silicon Labs Zigbee
  stack is chosen, and checks its SHA-256. `frames/aqara-s1-plus/tools/radio/make-bundle.py` refuses to package it, and the
  release workflow fails if an APK contains it.
- **microWakeWord models** (Apache-2.0, Copyright Kevin Ahrendt): `okay_nabu`, `hey_jarvis`,
  `alexa`, `hey_mycroft` from https://github.com/esphome/micro-wake-word-models (`models/v2` at commit
  `40ff33f57f8fc6ad71a75ef085abbf742495b225`). The panel downloads them itself and checks their
  SHA-256.
- **openWakeWord models** (CC-BY-NC-SA-4.0), the legacy wake word engine kept for one release. The
  panel downloads them from the official v0.5.1 release (https://github.com/dscripka/openWakeWord)
  and checks their SHA-256. 0.4 and earlier fetched them; a new panel doesn't, unless a legacy word
  is picked.
- **Home Assistant frontend** (`home-assistant-frontend` 20261028.5, the build HA 2026.11 serves):
  Apache-2.0 ([`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt)), Copyright Home Assistant
  contributors, https://github.com/home-assistant/frontend (tag `20261028.5`). Clarity, the perf test
  in `tools/perf`, downloads the wheel from PyPI on the computer that runs the test, checks its
  SHA-256 (`df2af7a4…4258730`, `tools/perf/hafrontend.py`), and serves it to the panel under test,
  from that computer. Its files are served as they are, except its `index.html` template, which is
  filled in with the test's harness script added first (`tools/perf/fakeha.py`). The project neither
  ships nor hosts it. `tools/showcase/render.mjs` serves the same build the same way to a headless
  Chrome on that computer, to make the device renders (section 4); only those screenshots are kept.
- **Vitro's parity references** (`engines/engine-vitro/src/test/parity/refs/*/*.png`, test data in the repo,
  #793): screenshots of the Home Assistant frontend (the build HA 2026.11 serves, Apache-2.0, as above)
  drawing single cards, made by `tools/vitro-parity/capture.mjs` in a headless Chromium, with the Material
  Design Icons and Roboto it serves. One frontend file is kept as it is, for a card that shows it:
  `engines/engine-vitro/src/test/parity/assets/static/icons/favicon-192x192.png`, the frontend's app icon
  (Apache-2.0, Copyright Home Assistant contributors, [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt)).
  Unlike the rest of this section, both **are distributed, in the source tree**, under Apache-2.0 with this
  notice. Neither is in an APK, the bundle or a web page.
  Since the references come from the parity harness's fake Home Assistant (`tools/vitro-parity/fakehome.py`),
  they draw made-up fixtures: the album art and person picture they show
  (`engines/engine-vitro/src/test/parity/fixtures/images/`) are drawn by `tools/vitro-parity/fixture_images.py`
  (this project's, MIT).
- **Home Assistant core's English entity texts and icons** (`tools/vitro-parity/hacore-2026.11.0.json`, test
  data in the repo): the `entity_component`, `entity` and `title` parts of each integration's
  `translations/en.json` and the `entity_component`/`entity` parts of its `icons.json`, unmodified, cut from the
  `homeassistant` 2026.11.0 wheel on PyPI (SHA-256 `7ca5fbde…391b00f7`) by `tools/vitro-parity/hacore.py`.
  Apache-2.0 ([`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt)), Copyright Home Assistant contributors,
  https://github.com/home-assistant/core (tag `2026.11.0`). **Distributed in the source tree** under Apache-2.0
  with this notice; not in an APK, the bundle or a web page. The parity harness's fake HA answers the frontend's
  `frontend/get_translations` and `frontend/get_icons` with it.
- **Home Assistant frontend's English, German and French UI strings**
  (`engines/engine-vitro/src/test/resources/dev/glassworks/engine/vitro/locale/strings-{en,de,fr}.json`, test data
  in the repo): the values, unmodified, of the frontend keys Vitro's code names, copied from the pinned frontend
  20261028.5's `static/translations` by `tools/vitro-parity/locale-data.mjs`. Apache-2.0
  ([`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt)), Copyright Home Assistant contributors,
  https://github.com/home-assistant/frontend (tag `20261028.5`). **Distributed in the source tree** under
  Apache-2.0 with this notice; not in an APK, the bundle or a web page. Vitro's tests read them; on a panel, Vitro
  reads HA's strings from the signed-in Home Assistant.
- **Community Lovelace cards** (host only, test inputs of the parity harness): `tools/vitro-parity/cards.py`
  downloads each from its GitHub release (or its repo at the tag, as HACS does), pinned by release and by the
  SHA-256 of every file in `tools/vitro-parity/cards.json`, into a cache on the computer that captures the
  references, and the fake Home Assistant serves them to a headless Chromium there. None is in the repo, an APK
  or a web page; only the screenshots of what they draw are kept (with the references). Licences, read from each
  repository on 2026-11-24: Mushroom v5.3.0 (Apache-2.0, Paul Bottein), card-mod v4.2.1,
  layout-card v2.4.7 and auto-entities v1.16.1 (MIT, Copyright Thomas Lovén), stack-in-card 0.2.0 and
  bar-card 3.2.0 (MIT, Copyright Custom cards for Home Assistant), mini-graph-card v0.13.0 and mini-media-player v1.16.12 (MIT, Copyright
  Karl Kihlström), Bubble Card v3.4.3 (MIT, Copyright Cloos), vertical-stack-in-card v1.1.4 (MIT, Copyright
  Ofek Ashery), clock-weather-card v3.1.0 (MIT, Copyright Patrick Kissling; its weather icons are Meteocons,
  MIT, Copyright Bas Milius) modern-circular-gauge v0.15.0 (MIT, Copyright Stanisław Krzeptowski), and, for the island checks only, battery-state-card v4.3.0
  (MIT, Copyright Max Chodorowski), scheduler-card v4.0.20 (Apache-2.0, Niels Faber) and calendar-card-pro v6.0.0 (MIT, Copyright Alex Pfau), read 2026-11-25.
- **Jinja2** (BSD-3-Clause, Copyright Pallets, host only): the parity harness's fake HA renders templates with
  the jinja2 already installed on the computer that captures the references. Nothing of it is in the repo.
- **Home Assistant core's icon data** (the `icons.json` of the integrations Clarity's fake Home
  Assistant loads, from `home-assistant/core` 2026.11.0, commit `74cf5a49…`): Apache-2.0
  ([`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt)), Copyright Home Assistant contributors,
  https://github.com/home-assistant/core. Kept as data in `tools/perf/haicons.json`, with each file's
  SHA-256, written and checked against that commit by `tools/perf/haicons.py`. The fake serves it to
  the panel under test as HA core's `frontend/get_icons` answer. It names icons only; no code, and
  nothing from anyone's install. Nothing from it ships in an app.
- **FFmpeg with libx264** (GPL-2.0-or-later as built with libx264). Clarity's camera streams
  (`tools/perf/camera.py`) run the ffmpeg already on the computer that runs the test, to encode its
  built-in test pattern into the test's HLS segments. Those segments are test data, served to the panel
  under test from that computer. Nothing from ffmpeg or libx264, and none of the segments, ships in
  the app, the bundle or the repo.
- **aiortc and the Python packages it needs** (host only, #202). Clarity's fake Home Assistant answers
  the frontend's WebRTC offers with aiortc (`tools/perf/webrtc.py`), in a venv that
  `tools/perf/webrtcenv.py` makes on the computer that runs the test, from PyPI wheels pinned and
  hash-checked in `tools/perf/webrtc-requirements.txt`. Versions and licences (from each package's
  metadata, 2026-11-13): aiortc 1.16.0, aioice 0.13.0, PyAV (`av`) 18.1.0, pylibsrtp 1.0.0 and
  pycparser 3.12 (BSD-3-Clause); cryptography 50.0.2 (Apache-2.0 OR BSD-3-Clause); pyOpenSSL 26.4.0
  and google-crc32c 1.9.0 (Apache-2.0); pyee 13.0.1 and ifaddr 0.2.0 (MIT); cffi 2.1.1 (MIT-0);
  dnspython 2.9.2 (ISC); typing-extensions 4.16.0 (PSF-2.0). PyAV's wheel bundles FFmpeg built with libx264 and libx265
  (GPL-2.0-or-later), so that FFmpeg is effectively GPL-2.0-or-later, though its build metadata says
  LGPL-3.0-or-later. It is downloaded to the tester's computer only, into a venv of about 93 MB; the
  peer only packs the H.264 that ffmpeg made, and decodes nothing. None of these ships in the app, the bundle or the repo,
  and the project doesn't host them.
- **node-usb and tsx** (host only, #40). `tools/mirror/flash-node.ts`, the Mirror install run from a
  computer, reaches fastboot through `usb` 3.2.1 (MIT, Copyright Rob Moran, https://github.com/node-usb/node-usb,
  with its prebuilt `@node-usb/usb-<platform>` binary, MIT) and runs under `tsx` 4.23.15 (MIT, Copyright
  Hiroki Osame, https://github.com/privatenumber/tsx). npm installs `usb` on that computer from the
  lockfile, and npx fetches the pinned `tsx` there. Neither ships in the app, the installer page or the repo.
- **Aqara/Lumi firmware.** None is included. The installer's partition backup stays with the owner. On
  the panel, the radio helper uses Aqara's own `cpcd` and stock radio firmware where they already are.

---

## 6. Corresponding source for GPL, LGPL and MPL parts

| Component | Licence | Where it ships | Corresponding source |
|---|---|---|---|
| U-Boot | GPL-2.0-or-later | installer | Upstream `934995f642a21ca7f35fa9aed5f061394fcba243` plus [`tools/fel/uboot/`](https://github.com/GlassworksHQ/glassworks/tree/8b2bf488fed3c7ba8343a32c67c9651066479128/tools/fel/uboot) at `8b2bf488fed3c7ba8343a32c67c9651066479128` (section 3) |
| Magisk | GPL-3.0-only | installer, panel boot image | https://github.com/topjohnwu/Magisk/tree/v31.1 |
| BusyBox 1.36.1, inside the Magisk APK | GPL-2.0-only | installer, panel boot image | https://github.com/topjohnwu/ndk-busybox/tree/android-ndk-1.36.1 (built by Magisk's build as `busybox-1.36.1.1.zip`) |
| GeckoView | MPL-2.0 (some parts LGPL-2.1-or-later) | APK | https://hg.mozilla.org/releases/mozilla-release/rev/7b34a03a910d0385299f4a9f27aa585a786862d8 |
| GeckoView 144 | MPL-2.0 (some parts LGPL-2.1-or-later) | the generic app's legacy APK (#198) | https://hg.mozilla.org/releases/mozilla-release/rev/5f480a9ab713ba79e5a14face39aacb038c36f30 |
| desugar_jdk_libs 2.1.5 | GPL-2.0-only WITH Classpath-exception-2.0 | the generic app's legacy APK (#198), the legacy engine's debug test APK, Vitro's debug test APK | https://github.com/google/desugar_jdk_libs (release 2.1.5) |
| `selinux_permissive.ko` (the Mirror's backup module) | GPL-2.0-only | fetched by `tools/mirror --backup` (section 3), not in this repository | https://github.com/GlassworksHQ/glassworks-device-bins (`frames/lululemon-mirror/selinux-permissive`) |
| GNU C Library, static in `otbr-agent`/`ot-ctl` | LGPL-2.1-or-later | installer, radio bundle | Ubuntu 22.04 source package `cross-toolchain-base` 59ubuntu3, which built `libc6-dev-armhf-cross` 2.35-0ubuntu1cross3 from Ubuntu's `glibc` 2.35 source: https://launchpad.net/ubuntu/+source/cross-toolchain-base/59ubuntu3. To relink `otbr-agent`/`ot-ctl` with a modified C library, rebuild them from source with [`frames/aqara-s1-plus/tools/radio/build-otbr.sh`](frames/aqara-s1-plus/tools/radio/build-otbr.sh). |
| GNU C Library, `sisdk/lib/` | LGPL-2.1-or-later | radio bundle | The Ubuntu source packages named in `sisdk/NOTICE.txt` (Ubuntu snapshot `20261101T000000Z`, see `frames/aqara-s1-plus/tools/radio/build-sisdk-runtime.sh`). The libraries can be replaced: the bundle loads them from that directory. |
| libstdc++, libgcc_s | GPL-3.0-or-later WITH GCC-exception-3.1 | installer, radio bundle | The Ubuntu GCC cross source packages: `gcc-11-cross` for the static copies in `otbr-agent`/`ot-ctl`, and those named in `sisdk/NOTICE.txt` for `sisdk/lib/`. All are on https://launchpad.net/ubuntu |

Questions about source: open an issue at https://github.com/GlassworksHQ/glassworks/issues.
