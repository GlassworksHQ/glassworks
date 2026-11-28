---
topic: access
evidence: verified
---

# adb keys on the S1: how does a new computer get in, and why does Android 10 forget keys?

**Status (2026-11-28): the S1's adbd doesn't check keys (verified).** On a panel, adbd let in a key it had never seen,
over TCP, with a working shell. It sent no request to Android, and no dialog appeared. That happened with
`ro.adb.secure=1` and `ro.debuggable=0`. So "Allow a new computer" has nothing to answer on the S1 until adbd checks
keys. The rest of this note, Android 10's key handling and the agent's side, still holds, and is read from AOSP
`android10-release` or tested off-device in `frames/aqara-s1-plus/tools/radio/test/`.

Device: Aqara Panel Hub S1 Plus, Android 10 (Aqara firmware 4.5.80). Source: AOSP `platform/frameworks/base`,
branch `android10-release`.

## Question

A maintainer noticed they had never had to accept an adb key on the S1. Does the panel let in any key? And can Manage
let in a new computer, as it does on the Mirror ([privileged-app.md](../../lululemon-mirror/research/privileged-app.md))?

## How we got here

The first question had a quick answer. `ro.adb.secure=1` ([findings.md](findings.md#usb--adb)), so adbd only lets in
keys listed in `/data/misc/adb/adb_keys`. Nobody ever sees the prompt because the installer bakes its computer's key
into the boot image (`overlay.d/sbin/glassworks_adb_keys`), and `glassworks_firstboot.sh` copies it into `adb_keys`
once. A dev boot image (`glassworks_devsu.sh`) adds it back at every boot.

For the second question, the Mirror's way doesn't carry over. On the Mirror the app reads the system log for
`Received public key:` and calls the hidden `IUsbManager.allowUsbDebugging`, which needs `MANAGE_USB`, a permission
its privileged app holds. Android 10 moved that call to `IAdbManager` (`core/java/android/debug/IAdbManager.aidl`),
behind `MANAGE_DEBUGGING`, and the S1 app is an ordinary app. It can't hold that permission, and it can't read the
system log either. But the S1 has a root side, the frame agent, and root has every permission. So the agent can
answer Android itself.

That led us to read how Android 10's `AdbDebuggingManager` handles keys. It turned up a second problem. Android 10
added key expiry: `AdbKeyStore` copies every key in `adb_keys` into `adb_temp_keys.xml` with a last-connection time,
and a scheduled job rewrites `adb_keys` without any key whose computer hasn't connected for
`adb_allowed_connection_time`. That setting defaults to 7 days. So the installer's key should drop out of `adb_keys` a
week after its last connection, and the next connection would ask on the panel's screen. Nobody had noticed, because
the dev panel's boot image adds the key back at every boot and the maintainer connects often.

Expiry also breaks a Remove button. The keystore keeps its keys in memory and rewrites `adb_keys` from them, so a key
taken out of the file can come back the next time a key expires. We asked the maintainer, who chose to turn the
timeout off (`adb_allowed_connection_time = 0`). That's the same as Android 11's developer option "Disable adb
authorization timeout". With the timeout off, the file is the record, and Manage's list can be trusted.

Turning the timeout off stops future drops, but it doesn't bring back a key Android already dropped, and first boot
runs only once. So the agent also puts the boot image's key back at every start. The maintainer's call (2026-11-28) was
to show that key on Manage as Kiln, or as the computer that ran the command-line installer, and let an administrator
remove it like any other key. The agent records that removal and doesn't undo it.

## What we tried

1. **Read Android 10's adb framework** (2026-11-28, read). The files:
   ```
   $ curl -s 'https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/<file>?format=TEXT' | base64 -d
   services/core/java/com/android/server/adb/AdbDebuggingManager.java  (1143 lines)
   services/core/java/com/android/server/adb/AdbService.java           (323 lines)
   core/java/android/debug/IAdbManager.aidl                            (44 lines)
   ```
   What they show:
   - `IAdbManager` declares `allowDebugging(boolean alwaysAllow, String publicKey)` first, so it's transaction 1.
     It's published as the `adb` service (`AdbService.java:71`, `Context.ADB_SERVICE`).
   - `AdbService.allowDebugging` checks `MANAGE_DEBUGGING` (`:238`). Root (uid 0) passes every permission check.
   - adbd hands Android a computer's key as `PK<key>`, and Android logs `Slog.d(TAG, "Received public key: " + key)`
     under the tag `AdbDebuggingManager` (`AdbDebuggingManager.java:196`). It also logs `Received connected key
     message:` when a listed key connects (`:210`).
   - `MESSAGE_ADB_ALLOW` takes the call only when the key's fingerprint is the one asking: otherwise it logs
     `Fingerprints do not match` (`:358`–`361`). With `alwaysAllow` it records the key
     (`setLastConnectionTime`), which appends it to `adb_keys` only when the keystore doesn't know it yet (`:1100`–`1102`).
   - `AdbKeyStore.addUserKeysToKeyStore` copies every line of `adb_keys` into the keystore with the current time
     (`:943`–`962`). `filterOutOldKeys` drops keys older than `getAllowedConnectionTime()`, then rewrites
     `adb_keys` from the keystore (`:1009`–`1028`). That setting is `Settings.Global.ADB_ALLOWED_CONNECTION_TIME`
     (`adb_allowed_connection_time`), defaulting to `604800000` ms, 7 days (`Settings.java:12393`). `0` means never.
   - `/adb_keys` (system keys) never expire, but on this system-as-root image that's the system partition, and we
     don't write it.
   - `isKeyAuthorized` (`:1123`) isn't called anywhere in Android 10. adbd decides from the file alone.

2. **Built it in the frame agent** (2026-11-28, tested off-device). See `glassworks_agent.sh`, "computers allowed
   over USB adb", and the contract in [radio.md](../docs/radio.md#computers-over-usb-adb). The tests are in
   `tools/radio/test/shell-tests.sh`, "computers over USB adb". They use made-up keys and mock `logcat`, `service`,
   `settings` and system_server's pid. They cover log lines from another pid or older than 10 minutes, Allow for a
   computer other than the one asking, Android writing the key itself or not, keeping the owner and mode, putting the
   installer's key back after Android drops it, keeping it out once it's removed on Manage, and stale or malformed
   requests.

3. **Looked at the dev panel's adb files** (2026-11-28, verified, read-only, from METIS's run 20 step 0). The panel runs
   app 0.9.0 (900) on a frame-loader boot image, with the agent source `boot`.
   ```
   $ getprop ro.adb.secure             -> 1
   $ ls -la /data/misc/adb/            -> drwxr-s--- system shell, empty (only . and ..)
   $ ls /data/misc/adb/adb_keys        -> No such file or directory
   $ settings get global adb_allowed_connection_time -> null (Android's 7 days)
   ```
   Both `adb_keys` and `adb_temp_keys.xml` were gone, yet METIS's adb session still worked. Android deletes both files
   when every key in its keystore has expired: `persistKeyStore` (`AdbDebuggingManager.java:971`–`977`) calls
   `deleteKeyStore`, which calls `deleteKeyFile` and deletes the XML too (`:1059`–`1066`). adbd doesn't re-check a
   connection it has already let in, so METIS's open session survives. The next adbd restart or replug would ask on the
   screen. This fits the 7-day expiry, but it doesn't prove it: we never saw the files before they went.

4. **Run 20b: a throwaway computer on the panel** (2026-11-28, verified; driven from METIS, the maintainer's Windows
   machine, with the PR's agent pinned at `cf217d5` through the loader's dev override). The props were
   `ro.adb.secure=1`, `ro.secure=1`, `ro.debuggable=0`, `sys.usb.config=adb`, and the system log held no
   `AdbDebuggingManager` lines.
   - **The agent worked as built.** It started from the dev override with its adb status `ok`, set the timeout to `0`,
     and put the boot image's key back into an `adb_keys` that Android had deleted ("the installer's key is back in
     the adb key list"). Remove took that key out again through the request file (`ok removed`), and kept the file's
     owner and mode.
   - **adbd lets in any key.** TCP adb was turned on with `setprop service.adb.tcp.port 5555; stop adbd; start adbd`.
     A second adb server on the same machine, with a freshly generated key and USB off, then connected:
     ```
     $ adb -P 5099 connect <panel>:5555; adb -P 5099 devices   -> device   (expected: unauthorized)
     $ adb -P 5099 -s <panel>:5555 shell id -un                -> shell
     ```
     No `Received public key` line appeared, and the agent never saw anyone asking. `adb_keys` didn't change, and no
     window drew on the screen. adbd's own log shows the connection go straight from transport to shell, with no auth
     step. Android's AdbDebuggingManager lost its socket to adbd at the restart ("Connection refused"), so nothing
     there could have approved it either.
   - **What it rules out.** It isn't Android approving computers on its own: nothing reached the framework. It isn't
     a stored key either: the throwaway key was new and in no key file. The maintainer never approved METIS or their
     MacBook, and both got in with an empty `/data/misc/adb/`, which fits.
   - **Not proven yet.** Only TCP was tested with a fresh key; that USB behaves the same is inferred from the two
     computers above. Why adbd skips the check is open too: an Aqara build of adbd without authentication is the
     likeliest reason.


| Question | Answer | Evidence |
|---|---|---|
| Does the S1 let in any key? | **Yes.** adbd let in a key it had never seen, with no request to Android and no dialog, although `ro.adb.secure=1` | Attempt 4 (run 20b, 2026-11-28) |
| Can root answer "Allow USB debugging?" | Yes, through `service call adb 1 i32 1 s16 <key>`, only for the computer asking | `IAdbManager.aidl`, `AdbService.java:238`, `AdbDebuggingManager.java:358` |
| Does Android 10 forget keys? | Yes, 7 days after the last connection, and the installer's key with them | `AdbDebuggingManager.java:943`–`1028`, `Settings.java:12393` |
| Does a dropped installer key come back? | Not by itself: first boot runs once. The agent puts it back at every start, unless it was removed on Manage | `glassworks_firstboot.sh` (its marker), `glassworks_agent.sh` (`adb_restore`) |
| Who's asking? | `Received public key:` from system_server's pid, in the system log | `AdbDebuggingManager.java:196` |

## How to reproduce

On a panel with a frame bundle that has this agent (writes `adb_keys` and one global setting; undo below):

1. `adb shell getprop sys.glassworks.adb.status` shows `ok`, and `settings get global adb_allowed_connection_time`
   shows `0`.
2. Plug in a computer whose key isn't listed. `adb devices` shows it as `unauthorized`, and Manage's Security page
   shows its fingerprint.
3. Allow it on the page. The computer gets in within a few seconds, and its key is in `adb_keys`.
4. Remove it on the page. It can't get in the next time it connects.

Undo: `settings delete global adb_allowed_connection_time` (`tools/install.sh --undo` runs it). Remove takes a key
out of `adb_keys`.

## Open questions

- **Why doesn't adbd check keys?** The next step is to read `/system/bin/adbd` from Aqara's 4.5.80 firmware (kept
  outside the repo) and see whether its authentication is compiled out, or tied to something other than
  `ro.adb.secure`.
- **USB:** is it the same as TCP? Plug in a computer with a fresh key over USB. The expected answer is yes.
- **Stock firmware:** stock ships with adb off (`persist.sys.usb.config=none`), so only panels where adb is on are open.
  Glassworks' first boot turns it on.

The questions below were written before attempt 4:

All of these need a real panel:

- Does Aqara's Android 10 log `Received public key:` the way AOSP does, in the system buffer, from system_server?
- Does the S1's `logcat` take `-T <epoch>.000`? The agent falls back to `-T 1` when logcat ends at once.
- Does `service call adb 1 i32 1 s16 <key>` as root let a waiting computer in without a replug?
- Is `adb_temp_keys.xml` on a panel that still has keys, with the installer's key and a `lastConnection` time? The dev
  panel had neither file (attempt 3), which is what Android leaves once every key has expired.
- Does Android's own dialog stay on the screen after Manage answers it? On Android 6 it stays until the computer is
  unplugged (`UsbDebugging.cover` in the core).
- After Remove, Android's keystore still holds the key in memory until the next reboot. With the timeout off that
  doesn't let it in, because adbd reads only the file. But if the timeout is ever turned back on, the next rewrite
  could put the key back.
