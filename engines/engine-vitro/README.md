# Vitro (`engines/engine-vitro`)

> **Experimental.** Off by default (`KioskPrefs.vitroEnabled`). Enable via **Settings → Home Assistant & dashboard → Vitro renderer** or `adb shell am start ... --ez vitro true`.

Vitro is an experimental native Android Lovelace rendering engine for Glassworks, built behind [`dev.glassworks.engine.Engine`](../../core/src/main/java/dev/glassworks/engine/Engine.kt). Its goal is to deliver native Android startup speed, frame pacing, and ~35–50 MB RAM footprints on low-powered hardware (such as the 952 MB Android 6 lululemon Studio Mirror and the 32-bit Aqara Panel Hub S1 Plus) **without changing how dashboards are authored in Home Assistant and without installing anything in Home Assistant**.

## How it works

Vitro moves the Home Assistant data plane out of the browser into Kotlin (`HaDashboardClient` over `WsHaSocket`) and splits every card across three automatic tiers:

| Tier | What it handles | How it renders |
|---|---|---|
| **Tier 1 — Native Core** | Layouts (`sections`, `masonry`, `panel`, `grid`, stacks, `visibility`) and built-in HA cards (`tile`, `entities`, `glance`, `button`, `heading`, `clock`, `sensor`, `history-graph`, `gauge`, `thermostat`, `weather-forecast`, `markdown`, `picture*`, `media-control`) | Native Android Views + Canvas (`MdiIcons`, `SparklineCanvasView`, `GaugeCanvasView`, `MoreInfoSheet`) — zero JS, zero browser process |
| **Tier 2 — Native HACS Fast-Paths** | Popular HACS cards (`custom:mushroom-*`, `custom:bubble-card`, `custom:mini-graph-card`, `custom:clock-weather-card`) | Native Kotlin renderers + HA `render_template` WS subscriptions for Jinja2 templates |
| **Tier 3 — Micro-Web Islands** | Arbitrary unrecognized `custom:*` cards (including full-viewport `type: panel` custom cards and inline `data:` URI ES module resources) | Stripped ~5 KB inline Lovelace card shim injecting resolved `:root` CSS theme variables, `hass.callWS`, `hass.callService`, `hass.config`, `<ha-icon>`, and a dependency-tracking `hass` Proxy — without loading HA's SPA shell |

Non-dashboard URLs (such as `/auth/authorize` panel login or Blueprint import dialogs) lazily delegate to the wrapped fallback `Engine` (`GeckoEngine`). When a dashboard stays in Tier 1 + Tier 2, GeckoView never starts at all.

## Key optimizations

- **View-scoped entity subscriptions:** Deep-scans the active view's config (including nested custom-card config objects) and passes only those `entity_id`s to HA's `subscribe_entities`.
- **O(changed) selective reactivity:** `EntityStore` dispatches updates only to the exact cards bound to each changed entity instead of cloning `hass.states` across the whole tree.
- **Keep-alive view cache:** Switching views toggles visibility of pre-built native trees instead of tearing down and recreating cards.
- **Native YAML theme resolution:** `ThemeResolver` reads `frontend/get_themes`, resolves `modes.light`/`dark`, recursive `var(--...)` chains, `rgb-*` triples, gradients, wallpapers, and container-level `card-mod` styles, and exports `:root` CSS variables to Tier 3 Micro-Islands so native and custom cards match seamlessly.

## Modules & testing

```sh
./gradlew :engine-vitro:testDebugUnitTest
./gradlew :vitro-test:assembleDebug
./gradlew test :kiosk:assembleDebug :generic-android:assembleDebug
```

Launch the standalone Gecko-free test APK against Clarity's `fakeha.py` or a real Home Assistant instance:

```sh
adb shell am start -n dev.glassworks.vitro.test/.VitroTestActivity \
  --es url http://127.0.0.1:8089/clarity-ref/typical-nocam
```
