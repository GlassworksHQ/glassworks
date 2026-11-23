<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-library-odin-on-dark.svg">
      <img alt="odin, a Glassworks library" src="https://www.glassworks.dev/brand/banner-library-odin-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">odin</h1>

<p align="center">
  <b>Samsung Download mode (Odin) over WebUSB.</b><br>
  A TypeScript port of <a href="https://github.com/Benjamin-Dobell/Heimdall">Heimdall</a>'s protocol (MIT).
</p>

<p align="center">
  <a href="LICENSE">
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

Samsung phones and tablets have a USB mode of their own for flashing, Download mode, which speaks a
protocol named after Odin, Samsung's flashing tool. This package speaks it from a web page over
WebUSB: it connects, reads what the device says about itself, downloads and parses its partition
table (the PIT), writes one image to one partition, and ends the session with or without a reboot.

It's written for one job: putting a recovery on a device, so that [Kiln](../../apps/kiln/README.md)
can install LineageOS on Samsung tablets from the browser. So it writes `RECOVERY` and nothing else,
unless the caller also allows `VBMETA`, and it checks every write against the device's own PIT
before anything goes out. It holds no Samsung software or firmware, and you bring the image.

The protocol is ported from [Heimdall](#heimdall), the open-source flasher (MIT). Its name is the
protocol's, the word Heimdall and the device's handshake use; it isn't Samsung's tool.

> [!WARNING]
> This hasn't run on a real device yet: its tests use a simulated device built from Heimdall's
> source. Flashing a recovery trips Samsung's warranty flag (Knox) for good, and needs an unlocked
> bootloader.

## Install

It isn't on npm yet. In this repository's workspace, depend on `@glassworks/odin`. It ships as
TypeScript source (ES2022), so use it from a bundler (Vite, esbuild, Next.js…). It runs in a browser
with WebUSB (Chrome, Edge and other Chromium browsers), on a secure origin, from a user gesture.

## Use

```ts
import { ODIN_USB_FILTERS, OdinDevice, OdinError } from '@glassworks/odin';

export async function flashRecovery(recovery: File): Promise<void> {
  const usb = await navigator.usb.requestDevice({ filters: ODIN_USB_FILTERS }); // from a click
  const odin = await OdinDevice.open(usb); // claims the Download mode interface
  try {
    const info = await odin.info().catch(() => undefined); // model, sales code, bootloader (newer devices)
    console.log(info?.model ?? 'a Samsung device');

    await odin.beginSession({ totalBytes: recovery.size }); // all this session may write
    const pit = await odin.pit();
    const entry = pit.find('RECOVERY');
    if (!entry) throw new Error('this device has no RECOVERY partition');

    await odin.flash({
      partition: 'RECOVERY',
      entry,
      image: recovery,
      onProgress: ({ sentBytes, totalBytes }) => console.log(`${Math.round((100 * sentBytes) / totalBytes)}%`),
    });
    // Stay in Download mode: the person holds the keys for recovery, so the stock system can't replace it.
    await odin.end({ reboot: false });
  } catch (e) {
    if (e instanceof OdinError && e.code === 'claim-failed') console.log(e.message); // says what to do (WinUSB)
    throw e;
  } finally {
    await odin.close();
  }
}
```

`OdinDevice.open` takes a `USBDevice`. For any other transport, implement `OdinTransport` (`send`,
`receive`, `close`; an empty `send` is a zero-length packet) and pass it to `new OdinDevice(transport)`.

## API

| Call | What it does | Heimdall |
|---|---|---|
| `OdinDevice.open(usb, { writable })` | Opens the device and claims its Download mode interface (CDC Data, one bulk pair). `writable` defaults to `['RECOVERY']` | `BridgeManager::Initialise` |
| `info()` | The model, sales code and bootloader version, before any session. The device's serial number is dropped. Older devices don't answer | Not in Heimdall: samloader-rs's `DVIF` |
| `beginSession({ totalBytes, resume })` | The `ODIN`/`LOKE` handshake (unless `resume`), the session, the transfer size, and the bytes this session may write. If it fails part way, the device may keep what it already agreed to, so this connection is done: `close()` it and open a new one | `InitialiseProtocol`, `BeginSession`, the total-bytes packet |
| `deviceType()` | A number the device sends back; what it means isn't known | `RequestDeviceType` |
| `pit()` | Downloads and parses the PIT, in parts of 500 bytes | `ReceivePitFile`, libpit |
| `flash({ partition, entry, image, onProgress, signal })` | Writes `image` (a `Blob` or bytes) to the partition, in sequences of parts | `SendFile` |
| `end({ reboot })` | Ends the session; with `reboot`, the device restarts | `EndSession` |
| `close()` | Releases the USB interface | |

Calls run one at a time. A call made while another is still running, such as `end()` from an
`onProgress` callback or a second `flash` that wasn't awaited, sends nothing and isn't queued. Its
promise rejects with an `OdinError` whose code is `state`, so handle that with `.catch()` or with
`await` in a `try`. A plain `try` around a call that isn't awaited won't catch it.

Also exported: `parsePit` and `partitionBytes` for a PIT on its own, `ODIN_USB_FILTERS` for
`requestDevice`, `WebUsbOdinTransport`, `OdinError` (with a `code` to act on), and `CLAIM_HELP`.
`@glassworks/odin/fake` is the simulated device the tests use, with `buildPit` for synthetic PITs.

### What `flash` refuses

`flash` throws an `OdinError` with the code `refused`, before it sends anything, unless all of these hold:

| Check | Why |
|---|---|
| The partition is `RECOVERY`, or `VBMETA` on a device opened with `writable: ['RECOVERY', 'VBMETA']` | Anything else is out of scope, so nothing else can be written by mistake |
| `entry` is that partition's entry in the PIT this session downloaded, the only one with that name | The partition is the device's own, not a copy or a guess |
| The entry is an application processor partition on eMMC or UFS, with a block count | Its size is known; modem partitions use another packet |
| The image isn't empty and fits the partition (512-byte blocks on eMMC, 4096 on UFS) | Heimdall's size check, without its `--skip-size-check` |
| The session announced at least that many bytes in `beginSession({ totalBytes })` | A session that announced nothing only reads |
| No earlier write in this session stopped part way | After a failure, the device's state is unknown |

A flash stopped part way (an error, or `signal` aborted) leaves the partition partly written. The
device stays in Download mode: restart it into Download mode and flash again.

## Browsers and drivers

| System | What to expect |
|---|---|
| Windows | Samsung's USB driver (or none) holds Download mode, and WebUSB can't claim it. Install the WinUSB driver for the device with [Zadig](https://zadig.akeo.ie), then plug it in again. A failed claim throws `claim-failed`, and its message says so (`CLAIM_HELP`) |
| macOS, Linux | Untested. The system may bind its own CDC-ACM serial driver to Download mode, in which case WebUSB can't claim it either. Web Serial would be the way around that: a transport for it is a follow-up |
| Android, iOS | No WebUSB host to a tablet in Download mode |

## Compared with Heimdall

The packets, their order, the sizes and the timeouts are Heimdall's, including its empty
(zero-length) transfers. What differs:

- **Protocol version 4** in the begin-session packet, and a 64-bit total, as in Henrik Grimler's maintained fork.
- **Device info** (`info()`) isn't in Heimdall. The `DVIF` query and its `@#KEY=VALUE;` answer are as
  [samloader-rs](https://github.com/topjohnwu/samloader-rs) documents them. No code is taken from it.
- **The device's failure status** (a response of type `0xFFFFFFFF` with a negative code) becomes an
  `OdinError` with that `status`, as samloader-rs reads it. A stray `FAIL` before `LOKE` is skipped.
  Heimdall only reports an unexpected response.
- **The reboot's answer is optional.** Heimdall waits for it; a device that restarts first doesn't fail the session.
- **No retries.** Heimdall retries a part whose answer didn't come, by sending the file's next part
  again; this package stops instead.
- **Left out:** repartitioning and flashing a PIT, modem partitions, T-Flash, dumping, and any
  partition but `RECOVERY` and `VBMETA`.

samloader-rs, the tool LineageOS's install guides use now, differs from Heimdall too: it sends
empty transfers only to some devices, and announces sequences rounded to 128 KiB. If a real device
disagrees with Heimdall's way, that's where to look first.

## Heimdall

| | |
|---|---|
| Upstream | https://github.com/Benjamin-Dobell/Heimdall, and Henrik Grimler's maintained fork, https://git.sr.ht/~grimler/Heimdall |
| Read at | the fork's commit `8f3044db985fd9710038f04886b51240ddbb2834` |
| Ported | `heimdall/source`: `BridgeManager.cpp` (interface, handshake, session, PIT download, file transfer, end), the packet classes, `FlashAction.cpp` (total bytes, size check); `libpit/source/libpit.{h,cpp}` (the PIT) |
| Licence | MIT, Copyright (c) 2010-2017 Benjamin Dobell, Glass Echidna, and Copyright (c) 2021-2024 Henrik Grimler. In [LICENSE](LICENSE) |

The ported files say which Heimdall source they come from in their headers.

## Tests

`npm test` in this package, or at the repository root with every other workspace. The tests drive
`OdinDevice` against `test/fake-download-mode.ts`, a device that answers in Heimdall's packet
formats (its header lists them), and check the bytes on the wire against Heimdall's, the file
transfer's arithmetic against `SendFile`'s, and the PIT parser against a PIT packed by hand at
libpit's offsets. Every PIT in them is synthetic. `transport.test.ts` covers the WebUSB side on a
stand-in `USBDevice`, and `readme.test.ts` type-checks the example above.
