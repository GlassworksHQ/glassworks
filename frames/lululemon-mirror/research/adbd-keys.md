# Mirror adbd keys: why doesn't adbd accept a key from `/data/misc/adb/adb_keys`?

**Status (2026-11-16): answered as the best-supported hypothesis; the one direct check is blocked by
our access.** On stock Android 6.0.1 the file is re-read and checked on every authentication, so a
valid key there lets a computer in with no dialog. The Mirror does not do this: a key written into
`/data/misc/adb/adb_keys` never authenticates, and only the framework's hardcoded "Mirror owner"
fingerprint ever says OK. The cause is the Mirror's own `adbd`, which lives in the **stock lululemon
boot ramdisk** and does not verify a connection's signature against `/data/misc/adb/adb_keys`. SELinux
(the unit's policy allows the read), the AOSP key-loading timing, and the key-file format are each
**ruled out** by evidence below. The one check that would name the exact modification, reading
`/sbin/adbd`, needs a route we don't have: the boot is unreadable (locked bootloader, no published
copy), and the confined root can't read it either.

Device: lululemon Studio Mirror (MSM8916 / APQ8016E, Android 6.0.1), Glass Panes `mirror-android-2`
system image (`userdebug`, `test-keys`), stock signed `boot` (`IFC-6309-2.0-MIR.S22Q401`, a
`user`/`test-keys` build). Tools: AOSP source at tag `android-6.0.1_r81` (android.googlesource.com),
the SELinux policy and `dmesg` pulled read-only from the unit on 2026-11-15 (see
[mirror-root.md](mirror-root.md) §3), `grep`. No device was touched in this pass.

## Question

A Mirror flashed by Kiln has to let in the installer's own adb key, with no touchscreen to answer
"Allow USB debugging?" (#29, #40). Kiln writes that key into a fresh `userdata`'s
`/data/misc/adb/adb_keys`, the AOSP location ([image-patch.md](image-patch.md)). On 2026-11-16 the
root verified that this does not work: adbd leaves the key `unauthorized`, and the only key that gets
in is the Glass Panes project key, which the project publishes with its private half. #426 works
around it by rewriting the framework's hardcoded fingerprint. This note asks the root-cause question
#426 left open: **why does adbd ignore `/data/misc/adb/adb_keys`, and does that leave Kiln any lever
other than the services.jar rewrite?**

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
reads `/data/misc/adb/adb_keys`. In `system/core/adb/adb.cpp`, the `A_AUTH` handler calls
`adb_auth_verify` when the client sends `ADB_AUTH_SIGNATURE`; only when that returns 0 for every
token does the client fall through to sending `ADB_AUTH_RSAPUBLICKEY`, which routes to
`adb_auth_confirm_key` and the framework dialog. So on a stock 6.0.1 adbd a valid key in the file
authenticates immediately, with the framework never consulted.

**This rules out candidate (b).** Stock 6.0.1 does not cache keys from init before `/data` is
mounted. It re-reads the file at connection time. The Mirror's behaviour, where the framework is the
only thing that ever says OK, is the opposite of this. Whatever `adbd` the Mirror runs, it is not
behaving like stock 6.0.1 `adb_auth_verify`.

### 2. SELinux (c) (2026-11-15 pull, read 2026-11-16 from the dump)

The unit's binary SELinux policy was dumped read-only on 2026-11-15 (`magiskpolicy --print-rules`,
10,038 rules; [mirror-root.md](mirror-root.md) §3). It **grants** adbd the read:

```
allow adbd adb_keys_file dir { search }
allow adbd adb_keys_file file { ioctl read getattr lock open }
```

So adbd is allowed by policy to open and read `/data/misc/adb/adb_keys`. The pulled `dmesg` (same
day) shows `init: Starting service 'adbd'` at 2.6 s and **no** `avc: denied` for adbd on
`adb_keys_file`. The only adbd denials are `shell` doing an `ioctl` on adbd's control socket (that is
`adb devices` / `dumpsys`, not adbd reading a file), and one `shell` `ls /adb_keys` `getattr` denial
(our own probe, not adbd). **This rules out candidate (c).** A policy denial would have left adbd's
read in the log, and the rule to deny is not there anyway.

DAC does not block it either. The dir is 02750 `system:shell` and the file 0640 `system:shell`, and
a `ro.secure=1` adbd drops to `AID_SHELL`, so it reads the file through the `shell` group
([image-patch.md](image-patch.md) read 2, AOSP `adb/daemon/main.cpp`).

### 3. The key-file format (d) (2026-11-16, read + inferred from the premise)

A format or line-handling fault in Kiln's written line would be a per-writer bug. It cannot be the
cause here, because keys written three different ways all fail: Kiln's line, the framework's own
`writeKey` line (canonical AOSP: base64, a space, `user@host`), and the project key present 11 times.
If adbd read and parsed the file, any one canonical key would match a signature and authenticate.
None do. **This rules out candidate (d).** The failure is not in how a key is written to the file, it
is that the file's contents never decide a connection.

### 4. What is left: the adbd binary (a) (2026-11-16, inference from 1 to 3)

Stock `adb_auth_verify` checks the files **first** and reaches the framework only on failure (step
1). On the Mirror the framework is the **only** authority that authenticates, and it authenticates
exactly one hardcoded fingerprint (the premise, and #426). For that to be true while the file is
present, readable, SELinux-allowed and canonically formatted (steps 2, 3), `adb_auth_verify` has to
be returning 0 for every key in the file every time. The file is not the variable. The binary is.

The Mirror's `adbd` is in the **stock lululemon boot ramdisk** (`/sbin/adbd`), not in the Glass Panes
system image. So this is lululemon's `adbd`, and it does not verify a connection against
`/data/misc/adb/adb_keys`. Within that, three shapes all fit the evidence and all have the same
consequence for us:

- `key_paths[]` trimmed to `/adb_keys` only (the ramdisk key), so `/data` is never read;
- `adb_auth_verify` changed to skip the local check and always defer to the framework;
- an older or altered `adbd` that loads keys once at init, before `/data`, so later file contents are
  never seen (the stock-6.0.1 version of this is already ruled out by step 1, but a non-stock build
  could do it).

The evidence to hand cannot tell these apart. It can say it is one of them.

### 5. Could the root settle it, or a public image? (2026-11-16, read)

No. The direct check is to read `/sbin/adbd` and look at `key_paths` and the `adb_auth` strings. Every
route to the bytes is closed:

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
ramdisk, does not verify a connection's signature against that file. It defers the decision to the
framework, which the Glass Panes image rebuilt to auto-approve one hardcoded fingerprint. Confidence:
the cause is **read/inferred** from AOSP source and the unit's pulled policy and log, not **verified**
by reading the binary, because the binary is out of reach.

| Candidate | Verdict | Evidence |
|---|---|---|
| (a) adbd modified so it doesn't verify against `/data/misc/adb/adb_keys` | **Best-supported** | §4: the only variable left once (b), (c), (d) are out; adbd is in the stock boot |
| (b) 6.0.1 adbd loads keys once at init, before `/data` | **Ruled out** (for stock) | §1: `adb_auth_verify` calls `load_keys` on every check, and `key_paths` includes the file (AOSP `android-6.0.1_r81`) |
| (c) SELinux or DAC denies adbd the file | **Ruled out** | §2: policy allows `adbd adb_keys_file:file read`, no `avc` denial in `dmesg`; DAC group-readable |
| (d) key-file format or line handling | **Ruled out** | §3: Kiln's line, the framework's `writeKey` line, and the project key times 11 all fail |

**The one experiment that would name the exact modification:** read `/sbin/adbd`'s `key_paths` array
and its `adb_auth` strings (and its build string). It needs the boot ramdisk, which today means the
untested full-root `.ko` route ([mirror-root.md](mirror-root.md) §4) or a published stock boot. It is
not needed to decide the lever below, so it stays a want, not a blocker.

### What it means for Glassworks

**The services.jar rewrite (#426) is the only durable lever, and it is the right one.** The thing
that would have to change to honour `/data/misc/adb/adb_keys` is `adbd` (its binary, or its policy if
that were the cause, which it isn't). Both live in the **stock lululemon boot**, which Kiln never
writes: the bootloader is locked, Kiln flashes only `system` and `userdata`, and an image we build
won't RAM-boot. So:

- **Nothing Kiln writes to `userdata` makes the key stick.** Writing `adb_keys` is inert on this
  device. The note and #426 keep writing it only because it is harmless and matches AOSP.
- **No `system` or property knob turns it back on.** AOSP 6 has no property that makes adbd consult
  the file, and a binary that doesn't read the file wouldn't honour one.
- **A privileged app can't make it persist either.** `allowUsbDebugging` only answers "the computer
  that last asked", and the key it writes is the same file adbd ignores, so the approval lasts one
  connection ([privileged-app.md](privileged-app.md), verified read-only on the unit 2026-11-16).
  This matches the premise's "let in only for the connection it was approved on".
- **The framework is the only authority adbd consults, and the Glass Panes `services.jar` decides
  which fingerprint it auto-approves.** Rewriting that constant to Kiln's own key, before first boot,
  in the `system` image Kiln already patches, is therefore the only way to make a Mirror trust the
  installer's computer and no one else. That is exactly #426.

In short, #426 is not a workaround that a cleaner fix would replace. It is the fix, because the clean
fix lives in a partition Kiln cannot touch.

## How to reproduce

Off-device, all read-only:

- The AOSP behaviour (§1): fetch `system/core/adb/adb_auth_client.cpp` and `adb.cpp` at
  `android-6.0.1_r81` from android.googlesource.com
  (`.../platform/system/core/+/refs/tags/android-6.0.1_r81/adb/adb_auth_client.cpp`). Read
  `key_paths`, `load_keys`, `read_keys`, `adb_auth_verify`, and the `A_AUTH` case in `adb.cpp`.
- The policy and log (§2): from the material pulled in [mirror-root.md](mirror-root.md) §3 (kept
  outside the repo), `grep -E 'allow adbd adb_keys_file' sepolicy-rules.txt` and
  `grep -iE 'adb|avc' dmesg.txt`. `/sbin` and `/adb_keys` labels are in the pulled `file_contexts`.
- Availability of a stock boot (§5): `gh api repos/git-nimrod/glass-panes/releases/tags/mirror-android-2 --jq '.assets[].name'`
  and `gh api repos/<repo>/git/trees/HEAD?recursive=1 --jq '.tree[].path'` for the three research
  repos.

On the unit, a light read-only re-confirmation (not needed for the result, and the root ran a Clarity
test through the same adb server during this pass, so it was not run): `adb -s $(cat /tmp/mirror-serial)`
then `ps -A | grep adbd` (is it `shell` or `root`?), `dumpsys usb` (both key lists), and, while a
computer with a file-only key is connecting, `logcat -b all -d | grep -i adb` (expect no file-key
match, and the framework's auto-approve line for the one hardcoded fingerprint). None of these writes
anything.

A plan for the root, if the exact modification is ever wanted (untested, and gated on the `.ko`
route, so **don't run it for this question**): from a full root that can read a block device or
rootfs, copy `/sbin/adbd` out through the #393 hook's one channel, a 0644 file in `/persist` written
by `qti_init_shell`, then `adb pull` it and read `key_paths`/`adb_auth` strings. The confined root as
it stands can't, because it has neither rootfs read nor `boot` block access (§5). Undo is the
cleanup-build boot that deletes the `/persist/gw-*` files, exactly as [mirror-root.md](mirror-root.md)
§7 describes.

## Open questions

- **Which shape of modification** is in lululemon's `adbd` (trimmed `key_paths`, always-defer, or
  load-once-at-init)? Settled only by reading the binary (§5), which needs a route we don't have.
- **Does `/adb_keys` (the ramdisk key) authenticate on its own,** i.e. does the modified adbd still do
  a local check against the one ramdisk key, or defer everything? We can't test it without lululemon's
  private key. It doesn't change the lever.
- The `.ko` full-root route stays the only untested way past the boot's limits, and it is a separate,
  riskier decision ([mirror-root.md](mirror-root.md) §4), not a follow-on from this note.

<!--
No serials, MACs, keys, fingerprints or vendor/Glass Panes bytes are in this note. The adbd binary is
described, not quoted; no offsets, because it was not read. Added to docs/research/README.md and
frames/lululemon-mirror/README.md.
-->
