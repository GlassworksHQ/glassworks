<!-- SPDX-License-Identifier: MIT -->
# Mirror stock probe (`dev.glassworks.stockprobe`)

A tiny, zero-Gradle, **read-only** Android app that Kiln's Phase 1 stock probe
(`tools/mirror/stock-probe.ts`) plants into the Stage 1 `userdata` image when `1.2x+` adbd blocks
ADB. On boot the app starts a foreground service that exposes the Mirror's stock
`/system/framework/services.jar`, its SHA-256, odex/FIEMAP facts, and a redacted set of system
properties over two transports:

- **HTTP** on `127.0.0.1`/LAN TCP port **18765**, for a browser or the installer over SoftAP.
- **BLE GATT** peripheral, for the installer when no IP path exists.

It never writes to any partition, never touches `system`/`boot`/`recovery`, and only reads files
under an explicit allowlist (`/system`, `/proc`, device-tree panel nodes, backlight/LED sysfs, and
the ramdisk policy files). The app ships as `prebuilt/stock-probe.apk` (minSdk/targetSdk 23,
`versionCode` 1), signed by a throwaway key whose certificate is `prebuilt/cert.hex`.

## The GWSB bundle

The probe serves one self-describing binary, the **GWSB bundle**: the stock `services.jar` with a
metadata header, so the installer gets the jar and everything it needs to bind it to a device in a
single transfer.

### Wire format

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | ASCII magic `GWSB` |
| 4 | 4 | `uint32` LE `metaLen` |
| 8 | 4 | `uint32` LE `jarLen` |
| 12 | `metaLen` | UTF-8 JSON metadata (see below) |
| `12 + metaLen` | `jarLen` | raw `/system/framework/services.jar` bytes |

### Metadata JSON

```jsonc
{
  "version": 1,
  "mirrorVersion": "<ro.build.version.mirror or null>",
  "fingerprint": "<ro.build.fingerprint>",
  "serialno": "<ro.serialno or null>",
  "servicesPath": "/system/framework/services.jar",
  "servicesSize": 2490368,
  "servicesSha256": "<64 hex>",
  "servicesOdexFound": true,
  "servicesOdexPath": "/system/framework/oat/arm64/services.odex",
  "servicesHasClassesDex": false,
  "fiemap": { /* FS_IOC_FIEMAP extents, or null if libfiemap.so is absent */ },
  "lanIp": "192.0.2.10",
  "httpPort": 18765,
  "hmacSha256": "<64 hex>"   // present only when a per-run secret is planted
}
```

`serialno` lets the installer bind the bundle to the Stage 1 fastboot serial. `fiemap` carries the
jar's physical block extents for the surgical patch in Phase 2.

### Per-run HMAC

When the host plants a 32-byte secret at the app's private `files/stage.secret` (either 32 raw
bytes or 64 trimmed ASCII hex chars), the metadata gains a final field:

```
hmacSha256 = HMAC-SHA256(secret, metaBaseBytes || jarBytes)
```

where **`metaBaseBytes`** is the exact UTF-8 JSON of the metadata up to but **not** including the
trailing `,"hmacSha256":"<64 hex>"` and the closing `}`. To verify, a client strips that trailing
field, then feeds `metaBaseBytes || jarBytes` through the same HMAC. This authenticates **every**
metadata field (including `serialno`, `fiemap`, `servicesSha256`, `lanIp`) and **every** byte of
`services.jar` with no JSON-canonicalization ambiguity. Without the secret file — a standalone
`adb install` or a CLI run — the field is omitted cleanly and the rest is unchanged.

## HTTP endpoints

| Method | Route | Response |
|---|---|---|
| `GET`, `HEAD`, `OPTIONS` | `/stock-bundle.bin` | the GWSB bundle (`application/octet-stream`) |
| `GET` | `/` and `/bundle` | an HTML page with bundle facts and a download link |
| `GET` | `/file?path=…` | one allowlisted read-only file |
| `GET` | `/fiemap?path=…` | FS_IOC_FIEMAP extents for an allowlisted path (needs `libfiemap.so`) |
| `GET` | other diagnostic routes | redacted properties, device-tree panel nodes, etc. |

The bundle routes carry CORS and Private Network Access headers
(`Access-Control-Allow-Origin: *`, `Access-Control-Allow-Private-Network: true`,
`Access-Control-Expose-Headers: Content-Length, Content-Disposition`) and answer the `OPTIONS`
preflight (`GET, HEAD, OPTIONS`), so Kiln can fetch from `https://kiln.glassworks.dev` over the
SoftAP link.

`/file` refuses (403) any path that canonicalizes **inside the app's own private data directory**
(`getApplicationInfo().dataDir`, `/data/data/dev.glassworks.stockprobe`,
`/data/user/0/dev.glassworks.stockprobe`), so `stage.secret` can never be read back over HTTP even
through a symlink.

## BLE GATT profile

All UUIDs share the base `…-6b69-6c6e-7374-6f636b70726f` (ASCII `kilnstockpro`):

| Role | UUID | Properties |
|---|---|---|
| Service | `67770001-6b69-6c6e-7374-6f636b70726f` | advertised |
| `META` | `67770002-…` | read (long read) |
| `CTRL` | `67770003-…` | write |
| `DATA` | `67770004-…` | notify (CCCD `00002902-…`) |

### META

A long-read JSON: the GWSB metadata plus `bundleSize`, `chunkSize` and `chunkLocked`. When present,
its `hmacSha256` is the **same** value as the GWSB header's (which authenticates the full,
untruncated metadata and jar), so a central reading only this characteristic verifies against the
streamed header, not this readout. To stay within the **512-byte** ATT attribute cap it tiers down:

1. full FIEMAP + `hmacSha256`;
2. else `fiemap` replaced by `{"truncated":true,"extentCount":N}` + `hmacSha256` (the full FIEMAP is
   always in the streamed GWSB header);
3. else, when the 64-hex `hmacSha256` is what overflows, the truncated FIEMAP with a short
   `"hasHmac":true` flag in place of the field.

The central reads offset 0 first; the server snapshots the JSON and serves later offsets as slices
of that snapshot (so a long value can still be read in full over sequential blob reads).

### CTRL opcodes

| Opcode | Meaning | Payload |
|---|---|---|
| `0x01` | stream | optional `startChunk` (LE32) + `count` (LE32); `count` 0 means to the end |
| `0x02` | selective resend | `count` (LE16) + that many `chunkIndex` (LE32) values |
| `0x03` | abort the current stream | — |

### DATA framing

Each notification is `chunkIndex` (LE32) followed by its payload:

- A normal frame: `chunkIndex` + up to `chunkSize` bundle bytes.
- `chunkIndex == 0xFFFFFFFF` — **INFO** frame: `chunkSize` (LE32), `bundleSize` (LE32),
  `totalChunks` (LE32). Sent first.
- `chunkIndex == 0xFFFFFFFE` — **END** frame: `sentInStream` (LE32). Sent last.

`chunkSize = min(508, max(16, MTU - 7))` (3 bytes ATT notification header + 4 bytes chunk index,
kept within the 512-byte attribute cap). It is locked for the lifetime of a stream so chunk indexes
stay stable across selective resends.

## Rebuilding

```sh
./build.sh      # writes out/stock-probe.apk, out/cert.hex, and optional out/libfiemap.so
```

`build.sh` needs an Android SDK build-tools and an `android-23` platform jar; it uses no Gradle and
touches no device or network. An Android NDK, when present, also builds the optional arm64
`libfiemap.so` (unpacked to the app's `lib/arm64/` for `FS_IOC_FIEMAP`).

When the APK changes, copy the fresh artifacts into `prebuilt/`:

- **`prebuilt/stock-probe.apk` and `prebuilt/cert.hex` must always be updated together** — the
  installer trusts the APK by the certificate in `cert.hex`, so a stale `cert.hex` rejects the new
  APK.
- The 1-entry `packages.xml` the installer plants for the APK must grant `BLUETOOTH` and
  `BLUETOOTH_ADMIN` (as well as the HTTP/boot permissions), or the BLE peripheral never comes up.

Verify a build with:

```sh
dexdump -c out/stock-probe.apk | head        # classes present
apksigner verify --min-sdk-version 23 out/stock-probe.apk
aapt2 dump badging out/stock-probe.apk        # package, version, permissions
```
