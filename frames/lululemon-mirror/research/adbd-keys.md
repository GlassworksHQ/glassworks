# Mirror adbd keys: why doesn't adbd accept a key from `/data/misc/adb/adb_keys`?

**Status (2026-11-16): answered as the best-supported hypothesis; the one direct check is blocked by
our access.** On stock Android 6.0.1 the file is re-read and checked on every authentication, so a
valid key there lets a computer in with no dialog. The Mirror does not do this: a key written into
`/data/misc/adb/adb_keys` never authenticates, and only the framework's hardcoded "Mirror owner"
fingerprint ever says OK. The best-supported cause is the Mirror's own `adbd`, which lives in the
**stock lululemon boot ramdisk** and most likely does not verify a connection's signature against
`/data/misc/adb/adb_keys`. The AOSP key-loading timing is **ruled out** (stock re-reads the file on
every check), and SELinux is **ruled out by the policy** (every path component is allowed; a
failed-connection log is still uncaptured). A malformed key file is **narrowed but not ruled out**:
it isn't a per-writer bug, but the file's bytes weren't read, and it isn't separated from a modified
adbd. The one check that would settle both, disassembling `/sbin/adbd`, needs a route we don't have:
the boot is unreadable (locked bootloader, no published copy), and the confined root can't read it
either. Either way the thing that would have to change lives in the boot partition.

Device: lululemon Studio Mirror (MSM8916 / APQ8016E, Android 6.0.1), Glass Panes `mirror-android-2`
system image (`userdebug`, `test-keys`), stock signed `boot` (`IFC-6309-2.0-MIR.S22Q401`, a
`user`/`test-keys` build). Tools: AOSP source at tag `android-6.0.1_r81` (android.googlesource.com),
and two files pulled read-only from the unit on 2026-11-15 ([mirror-root.md](mirror-root.md) §3):
`sepolicy-rules.txt`, the 10,038-rule dump of the unit's binary policy, and `dmesg.txt`, the kernel
log. Both live outside the repo with the rest of that pull and are not committed (hard rule 3). No
device was touched in this pass; the analysis is `grep` over those two files and the AOSP source.

## Question

A Mirror flashed by Kiln has to let in the installer's own adb key, with no touchscreen to answer
"Allow USB debugging?" (#29, #40). Kiln writes that key into a fresh `userdata`'s
`/data/misc/adb/adb_keys`, the AOSP location ([image-patch.md](image-patch.md)). On 2026-11-16 the
root verified that this does not work: adbd leaves the key `unauthorized`, and the only key that gets
in is the Glass Panes project key, which the project publishes with its private half. #426 rewrites
the framework's hardcoded fingerprint to work around it, and corrects the first-flash row in
[image-patch.md](image-patch.md) that had read "took the adb key" (it was the project key the root's
computer carried). This note builds on #426 and merges after it. It asks the root-cause question #426
left open: **why does adbd ignore `/data/misc/adb/adb_keys`, and does that leave Kiln any lever other
than the services.jar rewrite?**

Observed by the root (2026-11-16), taken as given here:

- A throwaway key Kiln wrote into a fresh `userdata`'s `adb_keys` (`system:shell` 0640,
  `u:object_r:adb_keys_file:s0`, in a 02750 `system:shell` dir with the same label) stays
  `unauthorized`.
- A key the framework's own `writeKey` appended to that file is let in only for the one connection it
  was approved on, not on the next connection.
- The project key is in the file 11 times, yet `unauthorized` after a reboot until the framework's
  hardcoded "Mirror owner" fingerprint (in the image's rebuilt `services.jar`) answers OK.
- `dumpsys usb` prints both the user keys (that file) and the system keys (`/adb_keys` in the
  ramdisk, one key, a lululemon key).

### What the root measured on the unit (2026-11-16, verified)

The root ran these on the Mirror (Glass Panes image 2) and recorded them in #426; the full redacted
logs are in [#426's body](https://github.com/GlassworksHQ/glassworks/pull/426) and
[privileged-app.md](privileged-app.md) (the 2026-11-16 run). Below, `$S` is the serial
(`S=$(cat /tmp/mirror-serial)`, never printed), and a throwaway key pair is made in its own `HOME`:

```sh
T=$(mktemp -d); mkdir "$T/.android"
tools/mirror/probe-app/adbkey.py new "$T/.android"       # prints fingerprints only, not the key
```

| Test | Commands (redacted output) | Result |
|---|---|---|
| Throwaway key in `userdata` only | `flash-node.ts --image … --apk … --adb-key "$T/.android/adbkey.pub"`, then `HOME=$T adb --one-device "$S" start-server`, then `adb -s "$S" shell dumpsys usb` | `dumpsys usb` lists two user keys (the throwaway and the project key) and one system key, the ramdisk's `/adb_keys` |
| The throwaway key alone | `HOME=$T adb devices` across the first boot and a reconnect | `<serial> unauthorized`, and stays `unauthorized` |
| The project key after a restart | `adb -s "$S" shell stop && … start` (the root's restart), `adb devices`, `adb -s "$S" shell logcat -d` | `unauthorized` until the framework runs and lets it in through the `services.jar` constant, although `adb_keys` held the project key 11 times |
| A key approved with `allowUsbDebugging(true, key)` | the probe's step 6: queue the approval, `HOME=$T adb --one-device "$S" start-server`, reconnect, then `adb -s "$S" shell dumpsys usb` | In for that one connection and written to `adb_keys`; the next connection `unauthorized` again ([privileged-app.md](privileged-app.md), the root's step 6) |

The four tests say the file never decides a connection on this image: not for the key Kiln writes,
not for a key written there 11 times, not for a key the framework itself appended. The framework's
one hardcoded fingerprint is the only thing that ever authenticates. The commands here are the root's
(they write the Mirror, so only the root runs them); the redacted output is in the sources linked
above.

## What we tried

### 1. The normal AOSP behaviour and the timing question (b) (2026-11-16, read: AOSP `android-6.0.1_r81`)

`system/core/adb/adb_auth_client.cpp` (the device side) defines the files it trusts and when it reads
them:

```c
static const char *key_paths[] = {
    "/adb_keys",
    "/data/misc/adb/adb_keys",
    NULL
};

static void load_keys(struct listnode *list) {
    const char** paths = key_paths;
    ...
    while ((path = *paths++))
        if (!stat(path, &buf)) read_keys(path, list);   // reads BOTH files
}

int adb_auth_verify(uint8_t* token, uint8_t* sig, int siglen) {
    struct listnode key_list;
    load_keys(&key_list);                                 // called every time
    list_for_each(item, &key_list) {
        ... RSA_verify(&key->key, sig, siglen, token, SHA_DIGEST_SIZE) ...
    }
    free_keys(&key_list);
    ...
}
```

`load_keys` runs **inside `adb_auth_verify`, on every signature check**, not once at init, and it
reads `/data/misc/adb/adb_keys`. The order on the wire is in `system/core/adb/adb.cpp`'s `A_AUTH`
case. adbd sends a token. The client signs it with one private key at a time and sends each as
`ADB_AUTH_SIGNATURE` (`adb.cpp:435`-440, the client picking `adb_auth_nextkey` until it runs out).
adbd calls `adb_auth_verify` on each signature; on a failure it just asks again
(`send_auth_request`, `adb.cpp:449`), so the client tries its next key. Only when the client has no
private keys left does it send its **public** key, `ADB_AUTH_RSAPUBLICKEY`, which routes to
`adb_auth_confirm_key` and the framework dialog (`adb.cpp:450`). So on a stock 6.0.1 adbd a key in
the file matches during the signature exchange and authenticates immediately, with the framework
never consulted.

**This rules out candidate (b).** Stock 6.0.1 does not cache keys from init before `/data` is
mounted. It re-reads the file at connection time. The Mirror's behaviour, where the framework is the
only thing that ever says OK, is the opposite of this. Whatever `adbd` the Mirror runs, it is not
behaving like stock 6.0.1 `adb_auth_verify`.

`load_keys` skips a path whose `stat()` fails (`adb_auth_client.cpp:119`), so a path that isn't
present is simply not read. That cannot be why `/data/misc/adb/adb_keys` is ignored here. `/data` is
`unencrypted` (`ro.crypto.state`, [image-patch.md](image-patch.md) read 2) and mounted on
`post-fs-data`, long before the reconnects that stay `unauthorized`, and `dumpsys usb` reads the file
at those same reconnects, so the file exists and `stat()`s at verify time.

### 2. SELinux (c) (2026-11-15 pull, read 2026-11-16 from the dump)

The unit's binary policy was dumped read-only on 2026-11-15 and read here as `sepolicy-rules.txt`.
How it was taken (mirror-root.md §3): the world-readable `/sepolicy` was pulled from the ramdisk, and
its rules printed with `magiskpolicy --load /sepolicy --print-rules` (magiskpolicy pushed to
`/data/local/tmp` as `shell`, deleted after), giving 10,038 rules. `dmesg.txt` is `adb shell dmesg`
from the same session. To repeat: redo that pull (mirror-root.md §3, "How to reproduce"), then `grep`
the two files as below. The dump **grants** adbd every component of the path, not just the file:

```
allow adbd adb_keys_file dir { search }                 # /data/misc/adb
allow adbd adb_keys_file file { ioctl read getattr lock open }   # /data/misc/adb/adb_keys
allow domain system_data_file dir { getattr search }    # /data and /data/misc (adbd has the `domain` attr)
```

`/data` and `/data/misc` are `system_data_file` (the pulled `file_contexts`), and the blanket
`domain` rule gives every domain, adbd included (`type adbd { ... domain }`), `search` on them. So
adbd can traverse `/data` and `/data/misc` and read `/data/misc/adb/adb_keys`, with no missing
component. This matches stock AOSP 6.0.1 `adbd.te` (lines 59-61, `adb_keys_file` dir+file) plus the
same `domain` ancestor rule.

**The verdict, and its limit.** There is no rule in the policy that would deny adbd this read: every
path component is explicitly allowed, so SELinux cannot be what blocks it. That is the strong claim,
and it holds whenever the read happens. The log is weaker. The pulled `dmesg` is the **2026-11-15
boot log**, it predates the 2026-11-16 failures, and no log was captured during a failed connection.
In that boot log adbd starts at 2.6 s and there is **no adbd denial on `adb_keys_file`** (the only
adbd lines are a `shell` `ioctl` on adbd's control socket, and a `shell` `ls /adb_keys` `getattr`
denial, our own probe). So: **candidate (c) is ruled out by the policy** (no denying rule exists),
and **not seen to fire** in the pulled boot log, with a failed-connection log still uncaptured.

DAC does not block it either. The dir is 02750 `system:shell` and the file 0640 `system:shell`, and
a `ro.secure=1` adbd drops to `AID_SHELL`, so it reads the file through the `shell` group
([image-patch.md](image-patch.md) read 2, AOSP `adb/daemon/main.cpp`).

And `ro.adb.secure=0` is not a way round any of this (**read**, from AOSP, not from the device's own
daemon, which we can't read). Stock adbd only skips auth when
`ALLOW_ADBD_NO_AUTH && ro.adb.secure == 0` (`adb_main.cpp:242`), and `ALLOW_ADBD_NO_AUTH` is compiled
to `1` only for `userdebug` and `eng` builds (`adb/Android.mk:238`). The Mirror's `boot` is a `user`
build ([mirror-root.md](mirror-root.md) §1), so a stock adbd would have `ALLOW_ADBD_NO_AUTH` `0` and
auth stays required
whatever `ro.adb.secure` is set to. There is no property that opens adb here.

### 3. The key-file format (d) (2026-11-16, read + inferred; file bytes not inspected)

What the three writers **do** rule out is a per-writer bug: Kiln's line, the framework's own
`writeKey` line (canonical AOSP: base64, a space, `user@host`), and the project key present 11 times
all fail, so no single writer's format explains it. If one writer had botched its line, another's
would still have worked.

What they **don't** rule out is a malformed *combined* file. We did not read the file's raw bytes,
its line endings, or how adbd handles a bad line (a bad line could, in principle, make a parser skip
the rest). `shell` can't read the file on this image, so this was not checked. And a malformed file
(d) is not distinguishable from a modified adbd (a) by these observations: under (a) adbd never reads
the file, so every key failing is exactly what you'd see either way. **So (d) is not ruled out, only
narrowed:** not a per-writer bug, but a combined-file fault remains possible and is not separated
from (a). Reading the file's bytes (needs root or a readable copy) would settle it.

### 4. What is left: the adbd binary (a) (2026-11-16, inference from 1 to 3)

Stock `adb_auth_verify` checks the files **first** and reaches the framework only on failure (step
1). On the Mirror the framework is the **only** authority that authenticates, and it authenticates
exactly one hardcoded fingerprint (the premise, and #426). The file is present, readable, and the
policy allows every path component (steps 1, 2). Two shapes remain, and they share the same
consequence for Kiln: adbd doesn't verify the file (a), or the file is malformed so no line parses
(d, §3). Both mean `adb_auth_verify` returns 0 for every key every time. (a) is the better-supported
of the two, because the same failure holds for the framework's own canonical `writeKey` line and for
11 copies of the project key, which a format fault would have to corrupt uniformly. But the note does
not read the binary or the file's bytes, so it cannot close (d) out entirely.

The Mirror's `adbd` is in the **stock lululemon boot ramdisk** (`/sbin/adbd`), not in the Glass Panes
system image. So this is lululemon's `adbd`. If the cause is (a) rather than (d), three shapes all
fit the evidence and all have the same consequence for us:

- `key_paths[]` trimmed to `/adb_keys` only (the ramdisk key), so `/data` is never read;
- `adb_auth_verify` changed to skip the local check and always defer to the framework;
- an older or altered `adbd` that loads keys once at init, before `/data`, so later file contents are
  never seen (the stock-6.0.1 version of this is already ruled out by step 1, but a non-stock build
  could do it).

The evidence to hand cannot tell these apart, or separate (a) from (d). It can say the file is not
what decides a connection, and that the thing that would have to change lives in the boot partition.

### 5. Could the root settle it, or a public image? (2026-11-16, read)

No. The direct check is to disassemble `/sbin/adbd`'s `adb_auth_verify` and see what it reads and
whether it checks signatures at all. Every route to the bytes is closed:

- **A published stock boot.** None exists. git-nimrod/glass-panes ships only `system.img`,
  `userdata.sparse` and `SHA256SUMS` (`gh api .../releases/tags/mirror-android-2`, 2026-11-16). The
  other cited Mirror repos (`repairman29/lululemon-mirror-repurpose`,
  `Onemoremichael/lulu-mirror-software-only`, `olm3ca/mirror`) hold docs and app source, no boot
  image or ramdisk (`gh api .../git/trees/HEAD?recursive=1`, 2026-11-16).
- **A fastboot dump or a RAM boot.** The bootloader is locked (`secure: yes`,
  `Device unlocked: false`; [mirror-root.md](mirror-root.md) §7), so `boot` can't be dumped over
  fastboot and an image we build won't `fastboot boot`.
- **The confined root (#393).** It runs as `qti_init_shell`, whose only access to rootfs is
  `execute_no_trans entrypoint` and whose only block-device rule is `efs_boot_dev` (the modem EFS,
  not `boot`). `/sbin` is labelled `u:object_r:rootfs:s0` (the unit's `file_contexts`), so the hook
  can't `cat /sbin/adbd` and can't read the `boot` partition to carve the ramdisk out. `shell` can't
  read `/sbin/adbd` either (0750 root, and no `shell → rootfs` read).

So the only way to the binary is a route this project has not opened: full root (the untested kernel
module in [mirror-root.md](mirror-root.md) §4, which holds `sys_module` and could read a block device
or rootfs) or someone publishing the stock boot. Neither is worth opening for this question, because
the answer does not change what Kiln can do (see Result).

## Result

**Why adbd ignores `/data/misc/adb/adb_keys`:** the Mirror's `adbd`, in the stock lululemon boot
ramdisk, most likely does not verify a connection's signature against that file, and defers the
decision to the framework, which the Glass Panes image rebuilt to auto-approve one hardcoded
fingerprint. Confidence: the cause is **read/inferred** from AOSP source and the unit's pulled policy
and log, not **verified** by reading the binary, which is out of reach. A malformed key file (d)
would produce the same symptom and is not separated from this by the evidence to hand, but it is the
weaker reading (§3, §4).

| Candidate | Verdict | Evidence |
|---|---|---|
| (a) adbd modified so it doesn't verify against `/data/misc/adb/adb_keys` | **Best-supported** | §4: stands once (b) and (c) are out; the better of (a)/(d) because the failure holds for canonical lines too; not separated from (d) |
| (b) 6.0.1 adbd loads keys once at init, before `/data` | **Ruled out** (for stock) | §1: `adb_auth_verify` calls `load_keys` on every check, and `key_paths` includes the file (AOSP `android-6.0.1_r81`) |
| (c) SELinux or DAC denies adbd the file | **Ruled out by policy; not seen to fire** | §2: policy allows every path component (file, its dir, and `/data`+`/data/misc` via the `domain` rule); DAC group-readable; no adbd denial in the 2026-11-15 boot log, which predates the failures |
| (d) key-file format or line handling | **Narrowed, not ruled out** | §3: not a per-writer bug (Kiln's line, the `writeKey` line, the project key ×11 all fail), but the file's bytes were not read and a combined-file fault is not separated from (a) |

**The one experiment that would name the exact modification:** disassemble `/sbin/adbd`'s
`adb_auth_verify` (and `load_keys`) and read its `key_paths` and build string. It needs the boot
ramdisk, which today means the untested full-root `.ko` route ([mirror-root.md](mirror-root.md) §4)
or a published stock boot. It is not needed to decide the lever below, so it stays a want, not a
blocker.

### What it means for Glassworks

**The services.jar rewrite (#426) is the only lever Kiln has without the boot partition, and it is
the right one.** The thing that would have to change to honour `/data/misc/adb/adb_keys` is `adbd`
(its binary, or the key file it reads, both reached only from `boot`). It lives in the **stock
lululemon boot**, which Kiln never writes: the bootloader is locked, Kiln flashes only `system` and
`userdata`, and an image we build won't RAM-boot. So:

- **Nothing Kiln writes to `userdata` makes the key stick.** On this image (Glass Panes
  `mirror-android-2`, 2026-11-16) writing `adb_keys` is inert, verified by the throwaway key staying
  `unauthorized` (§"What the root measured"). An earlier reading, that the first flash's
  `userdata` key "worked" ([image-patch.md](image-patch.md), the first-flash Restart row), was
  **confounded** and #426 corrected it: the root's own computer carried the Glass Panes project key,
  which `services.jar` lets in, so that boot never showed the written key working. The note and #426
  keep writing `adb_keys` only because it is harmless and matches AOSP.
- **No `system` or property knob turns it back on.** AOSP 6 has no property that makes adbd consult
  the file, a binary that doesn't read the file wouldn't honour one, and `ro.adb.secure=0` is dead on
  a `user` build (§2: `ALLOW_ADBD_NO_AUTH` is compiled to `0`).
- **A privileged app can't make it persist either.** `allowUsbDebugging` only answers "the computer
  that last asked", and the key it writes is the same file adbd ignores, so the approval lasts one
  connection ([privileged-app.md](privileged-app.md), verified read-only on the unit 2026-11-16).
  This matches the premise's "let in only for the connection it was approved on".
- **The framework is the only authority adbd consults, and the Glass Panes `services.jar` decides
  which fingerprint it auto-approves.** Rewriting that constant to Kiln's own key, before first boot,
  in the `system` image Kiln already patches, is therefore the only way Kiln can make a Mirror trust
  the installer's computer without touching `boot`, and stop trusting the published project key. That
  is exactly #426. It does not make the installer's key the *only* key that can get in: the ramdisk
  `/adb_keys` holds a lululemon key, and whether a stock-style local check still lets that key in is
  untested (open question below), outside Kiln's reach either way.

In short, #426 is not a workaround that a cleaner fix would replace. It is the fix, because the clean
fix lives in a partition Kiln cannot touch.

## How to reproduce

Off-device, all read-only:

- The AOSP behaviour (§1): fetch `system/core/adb/adb_auth_client.cpp` and `adb.cpp` at
  `android-6.0.1_r81` from android.googlesource.com
  (`.../platform/system/core/+/refs/tags/android-6.0.1_r81/adb/adb_auth_client.cpp`). Read
  `key_paths`, `load_keys`, `read_keys`, `adb_auth_verify`, and the `A_AUTH` case in `adb.cpp`.
- The policy and log (§2): redo the 2026-11-15 pull per [mirror-root.md](mirror-root.md) §3 (the
  files are kept outside the repo, never committed), then `grep -E 'allow adbd adb_keys_file'
  sepolicy-rules.txt` (expect the dir-search and file-read rules) and `grep -iE 'adb|avc' dmesg.txt`
  (expect adbd starting, and no adbd denial on `adb_keys_file`). `/sbin` and `/adb_keys` labels are
  in the pulled `file_contexts`.
- The three tests on the unit (the "What the root measured" table): the harness and probe steps in
  `tools/mirror/README.md` ("A throwaway key") and `tools/mirror/probe-app/README.md` (step 6). They
  write the Mirror, so only the root runs them; read `adb devices` and `dumpsys usb` after each.
- Availability of a stock boot (§5): `gh api repos/git-nimrod/glass-panes/releases/tags/mirror-android-2 --jq '.assets[].name'`
  and `gh api repos/<repo>/git/trees/HEAD?recursive=1 --jq '.tree[].path'` for the three research
  repos.

On the unit, a light read-only re-confirmation (not needed for the result, and the root ran a Clarity
test through the same adb server during this pass, so it was not run). With `S=$(cat /tmp/mirror-serial)`:

```sh
adb -s "$S" shell 'ps -A | grep adbd'                  # is adbd shell or root?
adb -s "$S" shell dumpsys usb                          # both key lists
adb -s "$S" shell 'logcat -b all -d | grep -i adb'     # while a file-only-key computer is connecting
```

Expect no file-key match and the framework's auto-approve line for the one hardcoded fingerprint.
None of these writes anything. A failed-connection `logcat`, which the 2026-11-15 pull does not have,
is the read that would confirm no `avc` denial fires at auth time (§2).

A plan for the root, if the exact modification is ever wanted (untested, and gated on the `.ko`
route, so **don't run it for this question**): from a full root that can read a block device or
rootfs, copy `/sbin/adbd` out through the #393 hook's one channel, a 0644 file in `/persist` written
by `qti_init_shell`, then `adb pull` it and disassemble `adb_auth_verify`. The confined root as
it stands can't, because it has neither rootfs read nor `boot` block access (§5). Undo is the
cleanup-build boot that deletes the `/persist/gw-*` files, exactly as [mirror-root.md](mirror-root.md)
§7 describes.

## Open questions

- **Which shape of modification** is in lululemon's `adbd` (trimmed `key_paths`, always-defer, or
  load-once-at-init)? Settled only by reading the binary (§5), which needs a route we don't have.
- **Does `/adb_keys` (the ramdisk key) authenticate on its own,** i.e. does this adbd still do a local
  check against the one ramdisk key, or defer everything? Untested: we don't have lululemon's private
  key. It matters for one claim only, that the installer's key is not necessarily the *sole* key that
  can connect, since lululemon holds the ramdisk key. It doesn't change Kiln's lever.
- The `.ko` full-root route stays the only untested way past the boot's limits, and it is a separate,
  riskier decision ([mirror-root.md](mirror-root.md) §4), not a follow-on from this note.

<!--
No serials, MACs, keys, fingerprints or vendor/Glass Panes bytes are in this note. The adbd binary is
described, not quoted; no offsets, because it was not read. Added to docs/research/README.md and
frames/lululemon-mirror/README.md.
-->
