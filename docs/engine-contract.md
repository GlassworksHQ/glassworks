# The engine contract

<p>
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-core-on-dark.svg">
      <img alt="Glassworks Core" src="https://www.glassworks.dev/brand/badge-core-on-light.svg" height="30">
    </picture>
  </a>
</p>

The Glassworks core never talks to a browser directly. It shows the dashboard through an **engine**: the interface in
[`Engine.kt`](../core/src/main/java/dev/glassworks/engine/Engine.kt) (`Engine`, `Page`, `PagePort`, `PageHost` and
`EngineSettings`). Three engines implement it today:

| Engine | Module | Runs on |
|---|---|---|
| GeckoView, current | [`engines/engine-gecko`](../engines/engine-gecko) | Android 8 and later |
| GeckoView, legacy (frozen, locked to the dashboard's origin) | [`engines/engine-gecko-legacy`](../engines/engine-gecko-legacy) | Android 6 and later |
| Vitro, experimental native Lovelace, over a Gecko engine for every page that isn't a dashboard | [`engines/engine-vitro`](../engines/engine-vitro) | Android 6 and later |

This page says what an engine must do for the core, and which test holds it to that. The test is the same for every
engine, and it runs on real Android emulators, so a new engine passes the same checks or the build says why.

## What an engine must do

An engine is one per process. It reads its `EngineSettings` once, at the first `open`, and `started` stays null until
then. Every `PageHost` call comes on the main thread.

| The core does | The engine must |
|---|---|
| `open(activity, host, background)` | Return a page whose `view` the core adds to its layout. Opening loads nothing but an empty first page (`about:blank`) |
| `load(dashboard URL)` | Report `onPageStart(url)`, with nothing of the dashboard before it. After it, report a first paint (`onFirstPaint`), `onPageStop(true)` and a `{"type": "ready"}` message from a top-level port the host was given in `onConnect`. The core shows the page on `ready` and keeps the other two as fallbacks, so their order among themselves is free: GeckoView paints after it stops, Vitro before |
| `load(another page)`, such as Home Assistant's sign-in | Load it and report where the page is with `onLocationChange`. An engine with a fallback opens the fallback's page for it, inside its own view |
| `post({"type": "navigate", "url": …})` | Move the page to that URL on the same Home Assistant (a notification's view) |
| `goBack()` | Return to the previous page or view |
| `reload()` | Load the page again: another `onPageStop(true)` and another `ready` |
| `restart()`, then `load` | After the content is gone, give a page in the same view that loads and is ready again |
| `capture(done)` | Answer on the main thread with a still of the page at the view's size, or `null` when the engine can't take one |
| `isContentProcess(name)` | Say which of the app's processes render web content. The app's own process never does |
| `lockedDown`, `name` | Say whether pages are held to the dashboard's origin, and name the engine for the Manage page. A locked-down engine never asks another origin for a page; an open one loads it |

After any of these, the main thread must still answer within 5 seconds (no ANR), and the process must not crash.

### Where engines differ

Engines don't skip checks. Where they differ on purpose, each one declares it as a capability, and the test checks
what it declared:

| Capability | GeckoView | Legacy GeckoView | Vitro |
|---|---|---|---|
| Locked down (`lockedDown`) | No | Yes | As its fallback |
| Renders in content processes of its own | Yes | Yes | As its fallback, for the fallback's pages. Dashboards draw in the app's process |
| Opens a fallback engine's page for what it can't draw | No | No | Yes, every page that isn't a dashboard |

Vitro also has an off switch. When Vitro is off at start, the apps run on the Gecko engine alone, and a Vitro engine
that is off behaves exactly like its fallback.

## The test that enforces it

The checks live in [`engines/conformance`](../engines/conformance), a test-only module:

| File | What it checks |
|---|---|
| `EngineContract.kt` | Every row of the table above, once per engine. Each engine's test class extends it |
| `GeckoEngineContractTest.kt` | The run's Gecko engine: current or legacy |
| `VitroEngineContractTest.kt` | Vitro over that Gecko engine, built the way the apps build it with Vitro on, plus that it draws a dashboard itself. `VitroOffTest` checks the off switch |
| `FakeHomeAssistant.kt` | A Home Assistant on the device's loopback: a page for every page path, and enough of the websocket API for Vitro to draw a two-view dashboard |

Each test runs in a process of its own (Android Test Orchestrator), because an engine reads its settings once and
GeckoView allows one runtime per process.

### Where it runs

The emulator tests in CI ([CI, Emulator tests](ci.md#emulator-tests)) run it on pushes to `main`, every night and by
hand, in the same jobs as the core's conformance test:

| Emulator | Engines |
|---|---|
| API 34 | GeckoView 159, and Vitro over it |
| API 23 (Android 6) | The legacy GeckoView 144, and Vitro over it, with the legacy build's desugaring |

On API 23 this is Android 6's own runtime and verifier, which is what the lululemon Mirror runs.

### Running it yourself

On an emulator only, never a panel. On an x86_64 emulator (an Intel or Linux machine):

```sh
./gradlew :engine-conformance:connectedDebugAndroidTest                    # Android 8 or later
./gradlew --init-script tools/legacy/api23-emulator.gradle -PengineConformanceGecko=legacy \
    :engine-conformance:connectedDebugAndroidTest                          # Android 6
```

On an Apple Silicon Mac, add `-PengineConformanceAbi=arm64-v8a` for an arm64 emulator. The engines' GeckoView is
swapped for that ABI's build of the same version. The reports land in
`engines/conformance/build/reports/androidTests/connected/`.

## Adding an engine

1. Implement `Engine` in a module of its own under `engines/`.
2. Add a `Subject` for it in `Subjects.kt`, with the capabilities it declares, and a test class that extends
   `EngineContract`.
3. Add it to the emulator jobs it can run on, and to the tables on this page.

If the new engine can't meet a row, change the contract on purpose: the row here, the check, and the core code that
relies on it, together.
