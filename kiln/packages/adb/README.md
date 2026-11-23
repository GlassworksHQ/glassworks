<p>
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30">
    </picture>
  </a>
</p>

# `@glassworks/adb`

Kiln's adb over WebUSB, on [Tango](https://github.com/yume-chan/ya-webadb) (`@yume-chan/adb`). It
holds this browser's adb key, connects to a device and runs commands on it, and sideloads an OTA
package to a recovery.

## Files

| File | What |
|---|---|
| `src/key.ts` | This browser's adb key, kept in IndexedDB, and a project's published key read from its `adbkey` file |
| `src/panel.ts` | `Panel`: connect (with a deadline on the key), the device's mode, shell, push, raw streams, restart into the bootloader, sideload |
| `src/run.ts` | A shell command that a signal can cancel at any point |
| `src/sideload.ts` | `adb sideload`: serves an OTA package to a recovery, block by block |

## Sideload

`sideload` does what `adb sideload <file>` does: it serves an OTA package to a device whose recovery
waits for one (in LineageOS Recovery, **Apply update**, then **Apply from ADB**). It reads the blocks
from a `Blob` as the device asks for them, so a 2 GB build never sits in memory.

```ts
import { Panel, SideloadError } from '@glassworks/adb';

const device = await Panel.connect(key);       // in sideload mode, the recovery's adb lets any key in
if (device.mode !== 'sideload') askToChooseApplyFromAdb();
try {
  const { ended } = await device.sideload(file, {  // file: the File the person picked
    onProgress: ({ sent, size }) => show(Math.min(sent / size, 0.99)),
    signal: cancel.signal,
  });
  // ended: 'done' or 'closed'. Either way, the device's screen says whether the install worked
} catch (e) {
  if (e instanceof SideloadError && e.code === 'not-in-sideload-mode') askToChooseApplyFromAdb();
  else throw e;
}
```

`device.mode` is the first field of the device's adb banner: `device` for Android, `recovery` for a
recovery's menu, `sideload` once someone picks **Apply from ADB**, and `rescue` or `bootloader`.

The free function, `sideload(transport, source, options)`, takes any `SideloadTransport`: a way to
open an adb stream, and the device's mode when it's known. The tests drive it with a fake device.

| Argument | What |
|---|---|
| `source` | A `Blob` (a `File`), or a `SideloadReader`: `{ size, read(offset, length) }` for bytes from somewhere else. A source must not change during the transfer: the recovery checks each block against the first time it read it |
| `onProgress` | Called after every block with `{ block, sent, size }`. `sent` counts repeats: a recovery may read a block more than once. LineageOS's wiki says its builds are usually read about once (adb's `Total xfer: 1.00x`), and adb counts on about twice for other packages (once to verify, once to install), so `sent / size` is a fair bar for the first and runs to 2 for the second |
| `signal` | Cancels. The stream closes, so the recovery stops the install, and the call rejects with the signal's reason |
| `stallMs` | How long the device may go without asking for a block, or the source take to give one, before the call gives up. The default is 10 minutes. A prompt on the device's screen (an unsigned build's "Install anyway?") stops its requests until someone answers it; `Infinity` waits for ever |

It resolves with `{ ended, sent }` when the transfer is over:

| `ended` | When |
|---|---|
| `done` | The device sent its end message (`DONEDONE`) |
| `closed` | The device closed the stream after it had blocks. LineageOS's wiki says an install that worked can end this way, with adb stopped at 47% and `adb: failed to read command: Success` |

**Neither means the install worked.** In sideload mode the recovery sends the same end whatever the
result, and only its screen says how it went. Check the installed system after the device restarts.

It rejects with a `SideloadError` whose `code` says what went wrong:

| `code` | When |
|---|---|
| `not-in-sideload-mode` | The device's banner names another mode (booted Android, recovery's menu), or it refused the sideload service |
| `closed` | The device closed the stream before it asked for a block, or while it waited for one, or the connection went |
| `out-of-range` | The device asked for a block past the end of the package |
| `bad-request` | The device sent something that is neither a block number nor the end |
| `stalled` | Nothing from the device, or no block from the source, for `stallMs` |
| `failed` | The device ended with `FAILFAIL`, which only a recovery in rescue mode sends |
| `source` | The package couldn't be read (a file changed or removed on disk, or a reader that returned the wrong length) |

### The protocol

As AOSP implements it, read at tag `android-15.0.0_r36`. LineageOS Recovery
(`LineageOS/android_bootable_recovery`, `lineage-22.2`) has the same device side.

1. The host opens `sideload-host:<size>:<block size>`, with a 64 KiB block, as adb does
   (`packages/modules/adb/client/commandline.cpp`, `adb_sideload_install`;
   `SIDELOAD_HOST_BLOCK_SIZE` is `CHUNK_SIZE` in `adb.h`).
2. The device asks for a block by its number, as eight decimal digits (`00000023`), and reads
   exactly that block back. The last block is short, not padded
   (`bootable/recovery/minadbd/fuse_adb_provider.cpp`, `ReadBlockAlignedData`).
3. The recovery reads the package through a FUSE file, so the device asks in any order and asks for
   most blocks twice (`bootable/recovery/fuse_sideload/fuse_sideload.cpp`).
4. It ends with `DONEDONE`, or `FAILFAIL` in rescue mode only
   (`bootable/recovery/minadbd/minadbd_services.cpp`, `RunAdbFuseSideload`; the strings are in adb's
   `services.h`).
5. The device may close the stream instead, once it has what it needs: LineageOS's wiki
   (`lineage_wiki`, `_includes/alerts/specific/tip_adb_flash_success.html`) counts that as fine.

Two things adb has that this package doesn't. The first is the pre-KitKat `sideload:<size>`
service, which adb falls back to when `sideload-host` is refused: only recoveries from Android 4.3
and older speak it, and today's recovery exits on it with an error that asks for a newer adb. The
second is rescue mode (`rescue-install`).

## Tests

```sh
npm test -w @glassworks/adb
```
