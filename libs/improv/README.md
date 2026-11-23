<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-library-improv-on-dark.svg">
      <img alt="improv, a Glassworks library" src="https://www.glassworks.dev/brand/banner-library-improv-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">improv</h1>

<p align="center">
  <b>Improv Wi-Fi for Android, over BLE, serial or adb.</b><br>
  The device side: it takes the credentials and joins the network.
</p>

<p align="center">
  <a href="../../LICENSE">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-mit-on-dark.svg">
      <img alt="License: MIT" src="https://www.glassworks.dev/brand/badge-license-mit-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-alpha-on-dark.svg">
    <img alt="Status: alpha" src="https://www.glassworks.dev/brand/badge-status-alpha-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

The device side of [Improv Wi-Fi](https://www.improv-wifi.com/) for Android. It lets a phone,
Home Assistant's `improv_ble` integration or improv-wifi.com send Wi-Fi credentials to an Android
device over **Bluetooth LE**, over a **serial** stream, or over **adb on a USB cable** from a web
page. It also joins the network, which is the hard part on Android 10 and later. Few Android
implementations of the device side exist.

It was extracted from [Glassworks for the Aqara S1 Plus](../../frames/aqara-s1-plus/android/README.md), where it puts the Aqara S1 Plus panel on Wi-Fi.
Package `dev.glassworks.improv`, MIT licensed.

## What's in it

| | |
|---|---|
| `Improv`, `ImprovState`, `ImprovError`, `ScannedNetwork` | [Improv BLE](https://www.improv-wifi.com/ble/)'s UUIDs, capability flags, commands, states and errors. The packet format (RPC encode and decode, checksums, chunk reassembly) runs inside `ImprovSession`. Pure Kotlin. |
| `ImprovSession` | The Improv BLE state machine (authorization, provisioning, errors, auth timeout). Pure Kotlin: Bluetooth and Wi-Fi sit behind `ImprovSession.Effects`, and time is passed in, so it is unit-testable. Behaviour follows the spec and ESPHome's `improv_ble`. |
| `ImprovBleServer` | The Android GATT server and advertiser: service, characteristics, 0x4677 service data, queued notifications, MTU tracking, legacy or extended advertising (only legacy below Android 8). Turns Bluetooth on if needed. |
| `ImprovSerial`, `SerialPacketReader`, `ImprovSerialSession` | [Improv Serial](https://www.improv-wifi.com/serial/): framing and its session. Transport-agnostic: feed it bytes from a UART, a socket or an adb stream. Pure Kotlin. |
| `ImprovAdbServer`, `AdbSocketServer` | Improv Serial over adb: an abstract Unix socket that only adb may open, with one serial session per connection. The browser client is the [`improv-serial`](../../kiln/packages/improv-serial/README.md) TypeScript package. `AdbSocketServer` is also usable on its own, for any browser-to-app channel over USB. |
| `WifiProvisioner` | Joins the network and verifies the connection, putting the previous network back on failure. Picks the security type from the scan (open, OWE, WEP, PSK, SAE, EAP), hidden networks included. Also scans. See [Joining Wi-Fi](#joining-wi-fi). |

The UI is yours. That covers how the user physically authorizes (a button or a prompt, then
`session.authorize(now)`) and what the screen shows while provisioning.

## Joining Wi-Fi

From Android 10 (API 29), `WifiManager.addNetwork` and `enableNetwork` fail for ordinary apps
that target API 29 or later. `WifiProvisioner` uses whichever of these routes the app has:

1. **Device owner** (`adb shell dpm set-device-owner <pkg>/<YourAdminReceiver>`): the legacy API
   works. The provisioner also grants the app location access, which it needs to see scan results
   and the current SSID.
2. **The `SYSTEM_ALERT_WINDOW` app-op** (`adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow`):
   AOSP still allows the legacy API for apps that hold it.
3. Otherwise, a **network suggestion**. Android 10 shows the user a notification to approve, and
   prefers an already-saved network that works.

Below Android 10 every app may use the legacy API, so that is the route there. WPA3 (`SAE`) and
Enhanced Open (`OWE`) networks need Android 10 or later; older versions fail to join them.

It turns Wi-Fi on if needed. It waits for the network to show up in a scan, picks the security type,
watches for authentication errors, and reports `Outcome.Connected(ssid, ip,
method)` or `Outcome.Failed(reason, restored)`. On failure it removes what it added and selects the
previous network again.

```kotlin
val wifi = WifiProvisioner(context, main, admin = ComponentName(context, MyAdminReceiver::class.java))
wifi.connect(ssid, password) { outcome -> /* any thread */ }
```

`admin` is optional: when it's null and the app is device owner, the provisioner finds the app's
active admin itself. Call it from the main thread. Logs go under `WifiProvisioner.logTag`.

## Use (BLE)

```kotlin
val main = Handler(Looper.getMainLooper())
val wifi = WifiProvisioner(context, main)
lateinit var ble: ImprovBleServer
lateinit var session: ImprovSession
val now = { System.currentTimeMillis() }

session = ImprovSession(object : ImprovSession.Effects {
    override fun publishState(state: ImprovState) = ble.setState(state)
    override fun publishError(error: ImprovError) = ble.setError(error)
    override fun sendResult(packet: ByteArray) = ble.sendResult(packet)
    override fun identify() { /* blink, beep… */ }
    override fun startWifi(ssid: String, password: String) = wifi.connect(ssid, password) { o ->
        main.post { session.onWifiResult(o is WifiProvisioner.Outcome.Connected, redirectUrl = null, now()) }
    }
    override fun scanNetworks(): List<ScannedNetwork> = wifi.scan()
    override fun deviceInfo() = listOf("My App", "1.0", "Android", "Living room tablet")
    override fun maxResultSize() = ble.maxNotify()
})

ble = ImprovBleServer(context, main, object : ImprovBleServer.Listener {
    override fun onRpcWrite(chunk: ByteArray) = session.onRpcWrite(chunk, now())
    override fun onClientConnected(address: String) {}
    override fun onClientDisconnected(address: String) = session.onDisconnect()
    override fun onAdvertising(ok: Boolean, detail: String) {}
})

session.start(requireAuthorization = true, now = now())
ble.start(session.state, session.capabilities)
// When the user physically authorizes (a button press): session.authorize(now())
// Call session.tick(now()) every second or so, to let authorization expire.
```

Call `ImprovBleServer`, `ImprovSession` and `WifiProvisioner` only from the main thread. GATT callbacks are posted to
the handler you pass in.

**Permissions.** The library's manifest declares `BLUETOOTH` and `BLUETOOTH_ADMIN` (API ≤ 30) and
`BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT` (API 31+, runtime permissions there, so request them
yourself), and marks `bluetooth_le` as not required. Logs go to logcat under
`ImprovBleServer.logTag` (default `ImprovBle`).

## Use (serial, and serial over adb)

Implement `ImprovSerialSession.Effects` (`send`, `online`, `redirectUrl`, `info`, `scan`,
`connect`), then pass every chunk you read to `session.onBytes(chunk, len)`, from a UART or any
other stream.

Over adb, `ImprovAdbServer` does the plumbing:

```kotlin
val server = ImprovAdbServer("myapp_improv") { output ->
    object : ImprovSerialSession.Effects {
        override fun send(packet: ByteArray) = synchronized(output) { output.write(packet); output.flush() }
        override fun online() = false // true when already on Wi-Fi
        override fun redirectUrl() = ""
        override fun info() = ImprovSerial.info("My App", "1.0", "Android", "Living room tablet")
        override fun scan(): List<SerialNetwork> = emptyList() // e.g. wifi.scanFresh(...) mapped to SerialNetwork
        override fun connect(ssid: String, password: String, done: (String?) -> Unit) {
            main.post { wifi.connect(ssid, password) { o -> done(if (o is WifiProvisioner.Outcome.Connected) "" else null) } }
        }
    }
}
server.start()
```

A web page then opens `localabstract:myapp_improv` over WebUSB adb with the
[`improv-serial`](../../kiln/packages/improv-serial/README.md) package. From a computer you can
also run `adb forward tcp:7000 localabstract:myapp_improv` and use any Improv Serial client on
`localhost:7000`. Only adb (uid 2000, or 0 after `adb root`) may connect, so USB access with an
authorized adb key is the physical authorization. That means it needs adb turned on, which suits
kiosks, signage and other single-purpose devices. Glassworks' `ImprovSerialServer.kt` is the full
example.

## Compared with the Improv spec

What the [Improv Wi-Fi](https://www.improv-wifi.com/) spec defines, and what each side here does:
this library on the device, and [`improv-serial`](../../kiln/packages/improv-serial/README.md) in
the browser. Checked on 2026-11-15 against the [BLE spec](https://www.improv-wifi.com/ble/) at
revision 2.4 and the [serial spec](https://www.improv-wifi.com/serial/) as published that day.
"Since" is this library's version; `improv-serial` isn't on npm yet. The adb transport is ours, not
the spec's: it carries Improv Serial unchanged.

Status words:

- **Supported**: works today, since the version given.
- **Partial**: works, with the gaps named.
- **Planned**: we mean to do it; the issue is linked.
- **Not yet**: no decision either way.
- **Not planned**: we won't, for the reason given.

**Count for improv (device)** (BLE 2.4 and the serial spec): 15 supported, 1 partial, 0 planned, 2 not yet, 2 not planned.

**Count for improv-serial (browser)** (the same specs): 7 supported, 0 partial, 0 planned, 4 not yet, 9 not planned.

| Feature | Spec | improv (device) | improv-serial (browser) | Since | Notes |
|---|---|---|---|---|---|
| BLE: GATT service and characteristics | Service `…8000`; state, error, RPC command, RPC result, capabilities | Supported | Not planned: Kiln reaches the device over USB; HA's `improv_ble` and improv-wifi.com are the BLE clients | 0.1.0 | |
| BLE: service data advertisement | `4677` service data with state and capabilities (rev 2.0) | Supported | Not planned: BLE | 0.1.0 | Legacy or extended advertising |
| BLE: authorization | Authorization required, authorized, a timeout | Supported | Not planned: BLE | 0.1.0 | The app does the physical step (`session.authorize`). Two minutes by default |
| BLE: commands over 20 bytes | Split over several writes | Supported | Not planned: BLE | 0.1.0 | Reassembled; results sized to the MTU |
| BLE: send Wi-Fi settings | 0x01, redirect URL in the result | Supported | Not planned: BLE | 0.1.0 | |
| BLE: identify | 0x02 | Supported | Not planned: BLE | 0.1.0 | Your `identify()` |
| BLE: device info | 0x03 (rev 2.1) | Supported | Not planned: BLE | 0.1.0 | |
| BLE: scan networks | 0x04 (rev 2.2) | Supported | Not planned: BLE | 0.1.0 | One result of triples, strongest first, cut to fit |
| BLE: errors | 0x00–0x05, 0xFF | Supported | Not planned: BLE | 0.1.0 | |
| Serial: framing | `IMPROV`, version 1, type, length, checksum | Supported | Supported | 0.1.0 | Over a UART, a socket or adb |
| Serial: state packet | Ready, provisioning, provisioned | Supported | Supported | 0.1.0 | |
| Serial: error packet | 0x00–0x03, 0x05, 0xFF; 0x00 again when a command arrives | Partial: no 0x00 reset when a command arrives | Supported | 0.1.0 | The device sends errors 0x01–0x03 but never clears them with 0x00, as the spec asks. Over BLE it does |
| Serial: send Wi-Fi settings | 0x01, redirect URL in the result | Supported | Supported | 0.1.0 | |
| Serial: request current state | 0x02 | Supported | Supported | 0.1.0 | Sends the redirect URL too when provisioned |
| Serial: device info | 0x03 | Supported | Supported | 0.1.0 | |
| Serial: scan networks | 0x04, one result per network, then an empty one | Supported | Supported | 0.1.0 | The browser drops duplicates and sorts by signal |
| Serial: "stopped" state | 0x00: provisioning unavailable, for example Wi-Fi off | Not planned: the device turns Wi-Fi on itself | Not yet | | The browser has no name for 0x00 and shows the number |
| Serial: network state | 0x07 (online, Wi-Fi, Ethernet, Thread, cellular, URLs) | Not yet | Not yet | | The device answers "unknown command", which the spec allows |
| Hostname | BLE 0x05 (rev 2.3), serial 0x05; error 0x05 | Not planned: an Android app can't set the system's hostname | Not yet | | |
| Device name | BLE 0x06 (rev 2.4), serial 0x06 | Not yet | Not yet | | An app could rename itself; no decision |

Beyond the spec, `WifiProvisioner` joins the network on Android 10 and later ([Joining Wi-Fi](#joining-wi-fi)).
`tools/check-parity-tables.py` checks the status words and both counts.

## Gradle

On Maven Central as `dev.glassworks:improv` ([changelog](CHANGELOG.md)).

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("dev.glassworks:improv:0.2.0")
}
```

An Android library (AAR), minSdk 23 (Android 6). Your app can compile against any compileSdk from 23
up. A Kotlin build needs Kotlin 2.0 or newer; a Java build needs Java 17 (so compileSdk 30 or newer).

## Tests

```sh
./gradlew :improv:testDebugUnitTest
```

The protocol, both sessions, the adb peer check and the Wi-Fi security rules have JVM unit tests.
`ImprovBleServer` and `WifiProvisioner` have no automated tests. Glassworks has only lightly tested them end to end on the panel (see
[onboarding.md](../../docs/onboarding.md)).
