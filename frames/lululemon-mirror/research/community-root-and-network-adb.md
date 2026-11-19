---
topic: access
evidence: read
---

# Mirror community projects: a kernel-module root and a stock network-adb path

**Status (2026-11-19): read, nothing run on a device; this stays research.** The maintainer decided
on 2026-11-19 (on #573): **no publishing for now**, which covers a root kernel module (GPL, hard rule
4) and an AOSP image alike — nothing we build from either project ships; the research continues. So
everything below is a comparison and a knowledge store, not a plan to adopt. Two public Mirror
projects were read in full and compared with what we do. [`shihanqu/lululemon-mirror-root`](https://github.com/shihanqu/lululemon-mirror-root)
is the custom-kernel-module (`.ko`) root this project flagged as *open, untested* in
[mirror-root.md](mirror-root.md) §4, now built and reported working on two units: it gives **full,
unconfined root** (permissive SELinux, a root command daemon), where the root we verified
([mirror-root.md](mirror-root.md) §7) is deliberately narrow and keeps SELinux enforcing.
[`jstriblet/lululemon-mirror-network-adb`](https://github.com/jstriblet/lululemon-mirror-network-adb)
reaches **authorised adb over Ethernet on a stock Mirror** by planting a key and a TCP port in a
fresh `userdata` — the one thing we have never been able to do (a stock Mirror giving adb, the open
question in [option-c.md](option-c.md)). Recommendation: **borrow the kernel root's method** (it
answers a real open question) but don't ship its module (GPL payload, rule 4; permissive SELinux we
wouldn't ship); **mine the network-adb repo for two facts and one idea**, but don't build on it (one
unit, pre-1.2x firmware, a destructive wipe). Neither is adopted; both update open questions in our
notes.

Device: none touched. Both repos were cloned read-only into throwaway directories and read, never
run. Cross-references are to our own verified notes. Tools: `git`, a text editor.

## Question

Issue #573: the maintainer linked two community Mirror projects and asked whether either helps.
Specifically:

1. How does each work (root method, persistence, what it changes in boot/system/userdata)?
2. Does it apply to our patched image (Glass Panes `mirror-android-2`, MMB29M base) or only to stock?
3. What would we gain, and what does it cost or risk (security, bricking under hard rule 1, its
   licence under rules 3/4, and the real-panel requirement of rule 5)?
4. How does each bear on #334 (the 13 vendor daemons adb can't read), #435/#486 (a new computer over
   adb on a touchscreen-less Mirror), #574/#575 (carry the ESPHome key over a reflash) and #577
   (rerun the first boot)?
5. Adopt, borrow, or skip?

Every claim below is **verified** (we ran it — only the cross-references to our own notes are
verified, on the dates they give), **read** (from one of the two repos or AOSP, cited by
`owner/repo` path and line), or **untested**.

## Project 1: `shihanqu/lululemon-mirror-root` — a kernel module at the free `adsprpc.ko` slot

### How it works (read, `shihanqu/lululemon-mirror-root`)

A 4 KB relocatable kernel module is written into the system image at `/lib/modules/adsprpc.ko`.
`init` already runs `insmod /system/lib/modules/adsprpc.ko` in its `on boot` action, and the target
image ships no real `adsprpc.ko`, so that load used to fail silently; the project fills the slot
(`README.md:49-64`). On every boot, before the framework starts, `init` — uid 0, full caps — loads
the module, whose `init`:

- writes `0` to the kernel's `selinux_enforcing`, found via `kallsyms_lookup_name`
  (`mod/mirror_root.c:78-86`) → **SELinux goes permissive**;
- `call_usermodehelper`s a shell that remounts `/system` rw, installs the adb key, and starts a
  root command daemon at `/data/local/rootd` (`mod/mirror_root.c:31-86`);
- `/system/xbin/su`, already setuid-root in a userdebug image, then gives any adb shell uid 0
  (`README.md:60-64`).

Persistence is the init `on boot` trigger itself, so it depends on nothing else — not Wi-Fi, not the
app, not the state of `/data` (`README.md:63-64`). The **flash** writes **only `system`**; boot,
recovery, aboot and modem are untouched (`README.md:24`, `README.md:121`). At runtime the module
does then write into `/data`: it **replaces** (`cp`, not append) `/data/misc/adb/adb_keys` with
`/system/etc/adb_keys`, and creates the `/data/local/rootd` daemon dir (`mod/mirror_root.c:36-67`).
A `system` reflash restores the boot and app surfaces but does not undo those `userdata` files, which
a fresh `userdata` (or deleting them) clears — and because the key copy overwrites rather than
appends, any keys already in `/data/misc/adb/adb_keys` are lost and not recovered by a reflash or by
deleting the new file, so back that file up before the first boot of a modified image if it matters.
(Our flow flashes a fresh `userdata`, so there is nothing there to lose.)

The hard part — making a module the loader accepts — is solved by recovering the ABI from the
vendor's own files (`docs/ABI.md`):

- `struct module` layout (size `0x220`, `name@0x18`, `init@0x120`, `exit@0x210`) is transcribed from
  the **DWARF of the unstripped `pronto_wlan.ko`** (`docs/ABI.md:9-19`) — the same ~70 MB debug
  module we pulled in [mirror-root.md](mirror-root.md) §4.
- `module_layout`'s CRC is copied from that shipped driver's `__versions`:
  **`0xa5b7f7dd`** (`docs/ABI.md:34`, `mod/mirror_root.c:104`).
- Only two imports, both primitive-signature so their CRCs are reproducible with `genksyms`:
  `kallsyms_lookup_name = 0xe007de41` and `call_usermodehelper = 0xc5fdef94`
  (`mod/mirror_root.c:105-106`, `docs/ABI.md:43-48`).
- `vermagic` must match the kernel verbatim: `3.10.49-perf-g87fe01fd365 SMP preempt mod_unload
  modversions aarch64`, and `license=GPL` is required because `kallsyms_lookup_name` is
  `EXPORT_SYMBOL_GPL` (`mod/mirror_root.c:109-111`, `docs/ABI.md:91`).

Reported **verified on two units**, kernels `3.10.49-perf-g87fe01fd365` and
`3.10.49-perf-g4869d64961b` (`README.md:9-11`). Licence: **MIT** on the repo source
(`LICENSE`), but see rule 4 below for what the *built* module is.

### Does it apply to our image? (read / untested)

Its requirement is "a userdebug system image that ships `/system/xbin/su` (04750) and test-keys — for
example the glass-panes `mirror-android-2` image" (`README.md:33-36`). That **is** the image Kiln
pins ([image-patch.md](image-patch.md)). Its `docs/USERSPACE.md:1-4` measurements were taken on
`MMB29M/11120023:userdebug/test-keys` — byte-for-byte the fingerprint of our image 2
([image-patch.md](image-patch.md), image 2 row). So the project targets our base image, not a
foreign one.

One gap stops the **prebuilt** module from loading on our dev unit: the vermagic. Our unit's kernel
is `3.10.49-perf-gf46dad5260f` ([mirror-root.md](mirror-root.md) §4, verified 2026-11-15), **not**
the `-g87fe01fd365` their module carries (`mod/mirror_root.c:111`). The module compares vermagic
verbatim, so theirs would be refused on our unit. Their **method** looks portable to our unit, but
only one of its inputs has actually been checked against our kernel; the rest are assumptions:

| ABI input | Theirs (`shihanqu`) | Ours (mirror-root.md §4, verified) | For our unit |
|---|---|---|---|
| `module_layout` CRC | `0xa5b7f7dd` (`docs/ABI.md:34`) | `0xa5b7f7dd` | **matches** (the one measured agreement) |
| `kallsyms_lookup_name` / `call_usermodehelper` CRC | primitive-signature, reproducible | not measured on our unit | **assume** reproducible — not checked |
| `struct module` layout | from `pronto_wlan.ko` DWARF | we hold the same 70 MB `pronto_wlan.ko` | **assume** re-derivable — not checked |
| vermagic string | `…-g87fe01fd365…` | `…-gf46dad5260f…` | **differs** — must change |

So the one input we have actually compared is `module_layout` (it agrees); the vermagic plainly
differs and must change; the two imported CRCs and the `struct module` layout are **assumptions** —
primitive-signature CRCs *should* reproduce and the DWARF layout *should* re-derive, but neither has
been compared against our kernel, so changing the vermagic alone may not be enough. **Untested end to
end** — nobody has built it for our kernel.

### What we'd gain, and the two things it settles

- It **answers [mirror-root.md](mirror-root.md) §4's open question** ("can we reproduce this kernel's
  vermagic/CRCs from CAF `msm-3.10` + this `.config`?") with a working recipe, and with a lighter
  input than a full kernel build: the unit's own `pronto_wlan.ko` DWARF plus `genksyms` for two
  primitive symbols.
- It uses a **safer load trigger than §4 feared.** [mirror-root.md](mirror-root.md) §4 assumed a
  module would have to **replace `wlan.ko` and chain-load the original** through the
  `cnss-daemon`/`subsys-pil-tz` path, and called that "the only step that can damage the panel: a
  wrong regulator or panel value is physical damage." This project touches no Wi-Fi driver: it fills
  the **free `adsprpc.ko` init slot** (`README.md:49-52`), so that physical-damage risk does not
  apply to its approach.
- Full root would **read the 13 daemons for #334** and could **dump `/sbin/adbd`** to settle
  [adbd-keys.md](adbd-keys.md)'s one unreachable question — both things our confined `qti_init_shell`
  root ([mirror-root.md](mirror-root.md) §7) and platform-app foothold (§6) cannot do.

### Cost and risk

| Concern | Finding |
|---|---|
| **Rule 1 — never brick, always a way back** | Fits as a *dev* technique. A module that fails to load can't brick: the boot chain is untouched and the failure is a missing feature, recovered by reflashing `system` (`README.md:169-172`). The way back is our Glass Panes image + patch, already on disk. **But** it disables SELinux and runs a daemon that executes anything in `/data/local/rootd/cmd` — "treat the device as fully compromised" (`README.md:160-162`). That is a security posture we would never ship to a user's Mirror; our own [mirror-root.md](mirror-root.md) §7 kept SELinux **enforcing** on purpose. |
| **Rule 4 — no GPL payloads shipped by us** | The built module declares `license=GPL` and links `EXPORT_SYMBOL_GPL` kernel symbols (`mod/mirror_root.c:110`, `docs/ABI.md:91`). A Linux kernel module linking GPL-only symbols is a derivative of the GPL-2.0 kernel, whatever the repo's MIT source licence. So **we could not ship a prebuilt `mirror_root.ko`** — the same bar that keeps Magisk out of the tree. The *method and source* are fine to describe and reuse; a *binary we distribute* is not. (This is not new: any `.ko` on this kernel, ours included, has the same rule-4 shape — the project just makes it concrete.) The maintainer settled it on 2026-11-19 (#573): no publishing for now, so no root kernel module ships — rule 4 as written stands, with no exception for this. |
| **Rule 3 — no vendor binaries** | The repo commits no vendor bytes; it reads `pronto_wlan.ko`'s DWARF for a struct layout and one CRC (names/offsets, not bytes), as we do. Fine. |
| **Rule 5 — boot/root changes need a real panel** | Squarely applies. Nothing from this reaches a panel without a real-Mirror run, and only the root drives the Mirror. |

### Verdict: borrow the method, don't ship the module

Keep it as a **dev/research tool** for the day we need full root on the Mirror — reading the 13
daemons for #334, or dumping `/sbin/adbd` for [adbd-keys.md](adbd-keys.md) — gated on the maintainer
and a panel run, and never shipped to users (permissive SELinux + GPL binary). Do **not** adopt it
into Kiln's install. The maintainer's 2026-11-19 decision (no publishing for now, #573) settles the
ship question regardless: no root kernel module goes out. [mirror-root.md](mirror-root.md) §4's open
question is updated to point here.

## Project 2: `jstriblet/lululemon-mirror-network-adb` — adb over Ethernet on a stock Mirror

### How it works (read, `jstriblet/lululemon-mirror-network-adb`)

A **userdata-only** method for a stock Mirror. It replaces `userdata` with a fresh ext4 image that
carries three things Android reads on first boot (`README.md:93-106`):

1. `/data/misc/adb/adb_keys` — the owner's adb **public** key; with `ro.adb.secure=1` it is
   pre-authorised, so no on-glass dialog (which is broken on this firmware anyway);
2. `/data/property/persist.sys.usb.config = adb`;
3. `/data/property/persist.adb.tcp.port = 5555` — a **TCP listener** so the unit is reachable over
   Ethernet.

The properties are planted with `debugfs` and the `property_data_file` SELinux label
(`scripts/property.debugfs`). It touches **no `system`, `boot`, `recovery` or bootloader**
(`README.md:14-16`); the way back is reflashing a blank `userdata` (`README.md:291-311`). The image
builder itself is the upstream `Onemoremichael/lulu-mirror-software-only`, **referenced at a pinned
commit, not vendored** (`README.md:316-333`, `scripts/README.md`), because upstream ships no licence
file; this repo's own scripts are MIT (`README.md:335-341`, `LICENSE`). The pre-flash gates
(`scripts/preflash-gates.sh`) are seven read-only `fastboot getvar` checks — the model of the hard
gates we already run in [image-patch.md](image-patch.md) (the first real flash's size check, #357).

### Does it apply to our image? (read)

Only partly, and the differences are the interesting part:

- It targets **stock lululemon software** (`README.md:83-89`: one unit, RevP17, `os_version 1.16.0`,
  build `IFC-6309-2.0-MIR/673`). We run the **Glass Panes `mirror-android-2`** system under stock
  signed boot `IFC-6309-2.0-MIR.S22Q401` ([image-patch.md](image-patch.md)) — a different boot build.
- Its key method **works because stock 1.16.0 adbd reads `/data/misc/adb/adb_keys`** — normal AOSP
  `android-6.0.1_r81` behaviour (`README.md:331-333`). On **our** image, planting a key in `userdata`
  is **inert**: we verified on 2026-11-16 that such a key stays `unauthorized` and only the
  framework's one hardcoded fingerprint authenticates ([adbd-keys.md](adbd-keys.md)). The
  *observation* is verified; the *cause* is a hypothesis — [adbd-keys.md](adbd-keys.md)'s
  best-supported reading is that this adbd doesn't verify against the file and defers to the
  framework, but a malformed combined key file is narrowed, not ruled out, since the file's bytes
  were not read. Either way the key plant does not authenticate on our image.

This is a useful cross-check for [adbd-keys.md](adbd-keys.md): on at least one **stock** firmware
(1.16.0, boot `/673`) adbd behaves like stock AOSP and honours `adb_keys`. It does not contradict our
observation that a key planted on the Glass Panes image stays `unauthorized` (adbd-keys.md's
best-supported reading: that adbd defers to the framework) — different boot build, and no Glass Panes
`services.jar` in the picture — but it narrows where the deviation lives (the specific stock boot
build we run under, or something the Glass Panes system does, still not separated).

### What we'd gain, and the one open question it touches

- It is the first report of authorised adb on a stock Mirror **over the network** (`README.md:81-89`).
  It is **not** the first stock-adb report over **USB**: the project itself credits earlier owner
  reports of USB authorisation on stock — porcuswine's RevP18 `adb_keys` recipe and the
  `TimStewartJ` issue #5 thread, which it calls "the only prior working 1.16.0 report"
  (`README.md:316-333`). So the new thing here is the network path, not stock adb as such. That still
  bears directly on [option-c.md](option-c.md)'s biggest open question — "Can a stock Mirror give adb
  at all?" — which we marked untested: for **one** unit at firmware 1.16.0, the answer is **yes**
  (and the author notes firmware **1.2x+ is reported to break** the key method — `README.md:87-89`).
  If it held generally, a stock Mirror's **own lululemon vendor files** (never read by anyone we know)
  would become the cleanest source for option C — no Glass Panes licence question at all.
- The **persistent TCP listener** is a headless-recovery idea worth noting for #435/#486 and #577.

### Cost and risk

| Concern | Finding |
|---|---|
| **Rule 1 — never brick, always a way back** | The bootloader is untouched and fastboot stays reachable, so no failure here is a brick (`README.md:291-311`). The real tension is destructiveness: it **wipes app data** and may make **stock setup unable to complete again** (`README.md:44-50`) — a one-way door off stock, like our own §7 note for a virgin unit. On a user's only Mirror that is a real cost; on a dev unit it is fine. |
| **Rule 4 — no GPL payloads** | Its own code is MIT scripts, no binaries. But it depends on the upstream `lulu-mirror-software-only` image builder, which ships **no licence** — we could neither vendor nor redistribute it (`README.md:337-341`), the same bar as [community-image-terms.md](community-image-terms.md). No GPL payload of our own. |
| **Security — unauthenticated adb on a LAN** | The TCP listener is **key-authenticated** (possession of the private half), but open on whatever network the Mirror is on (`README.md:154-158`). The repo's own guidance is to keep it on a trusted LAN and remove `persist.adb.tcp.port` when not needed. A persistent network-adb listener on a user's panel is a posture we would not enable by default. |
| **Rule 5 — real panel** | We have no **stock** Mirror to reproduce it on (our dev unit already runs the Glass Panes image), so we cannot verify it, and the author verified only one unit. |

### Verdict: skip adopting; keep two facts and one idea

Don't adopt the flow (one unit, pre-1.2x firmware, a destructive wipe, an unlicensed upstream
builder, and on **our** image the key plant is inert). Keep from it:

1. **Fact** — a stock Mirror at 1.16.0 gives authorised network adb (updates
   [option-c.md](option-c.md)'s open question; still untested for us and for 1.2x+).
2. **Fact** — stock 1.16.0 adbd reads `adb_keys` normally (a cross-check for
   [adbd-keys.md](adbd-keys.md); consistent, different boot build).
3. **Idea** — a persistent **TCP adb listener** as a headless-recovery path (see #435/#486/#577
   below). **Untested on our image**, because our adbd most likely defers auth to `services.jar`
   (adbd-keys.md's best-supported reading), so whether a TCP transport is auto-approved for the owner
   fingerprint at all is unknown. A competing data point to keep in mind: `shihanqu`'s `docs/ADB.md`
   reports that on *their* (stock-reading) adbd, **TCP has no framework-approval helper** — the
   framework dialog is USB-only, so a TCP transport authenticates against the key file, which they
   bind-mount over the ramdisk `/adb_keys` to make it count (`shihanqu/lululemon-mirror-root`
   `docs/ADB.md`). Read, unverified, and on a different adbd than ours; it cuts against assuming our
   image's framework-defer applies uniformly to TCP, and is one more reason our-image behaviour has
   to be measured, not assumed.

## How each bears on the open issues

| Issue | `shihanqu` kernel root | `jstriblet` network adb |
|---|---|---|
| **#334** — the 13 vendor daemons adb's shell can't read | Permissive-SELinux full root **would read all 13** (their labels allow only `init`/`debuggerd`/`mdtpdaemon`; SELinux off removes the block). A genuine alternative source for option C's hardest gap — but a heavier one than option C's existing plan ([option-c.md](option-c.md): read them from the person's own `system.img` with `@glassworks/ext4`, no root, no flash). Adopt only if that cleaner source ever falls through. | Doesn't help read the 13: same signed boot, same policy, so a stock unit's `shell` is denied the same daemon labels ([option-c.md](option-c.md) step 5). It widens **who** can be reached (a stock Mirror over the network), not **what** `shell` may read. Relevant to option C's "source of the files", not to the 13. |
| **#435 / #486** — allow a new computer over adb, no touchscreen | Overkill and wrong shape: #486 deliberately avoids more `system` patching whose failure mode is a non-booting Mirror. A root that disables SELinux is the opposite of that caution. Skip for #435/#486. | **Most relevant.** It is exactly "reach a touchscreen-less unit with a pre-planted key and no dialog", plus a TCP transport. On stock it works via `adb_keys`; on **our** image `adb_keys` is inert, so the borrowable piece is narrower: a TCP listener **might** be auto-approved for the owner fingerprint the way USB is (services.jar defers by transport-agnostic fingerprint — **untested**). Worth a future spike as a network alternative to #486's USB-only, app-allowlist flow. |
| **#574 / #575** — carry the ESPHome API key over a reflash | Not relevant: app-level key state, nothing a kernel root addresses. | Not relevant: #575 already carries the key over an adb abstract socket; network adb would only change the transport, which it doesn't need. |
| **#577** — rerun only the first boot after an interrupted flash | Not relevant. | Thin but real: a **persistent network-adb listener** would let `flash-node` reconnect and rerun the first boot after a power cut with no physical access. Not needed (USB adb already works post-flash on our image), but it is the kind of resilience #577 is about. Note, don't build. |

## How to reproduce (read-only, off-device)

Both repos were read, not run. To repeat the reading, clone each into its **own empty directory**
(downloaded material is untrusted — read it, don't run it) and read:

- `shihanqu/lululemon-mirror-root`: `README.md`, `mod/mirror_root.c`, `docs/ABI.md`, `docs/ADB.md`,
  `docs/USERSPACE.md`, `scripts/patch_image.sh`, `LICENSE`.
- `jstriblet/lululemon-mirror-network-adb`: `README.md`, `docs/our-unit.md`, `scripts/README.md`,
  `scripts/build-userdata-tcp.sh`, `scripts/property.debugfs`, `scripts/preflash-gates.sh`, `LICENSE`.

Compare against our verified notes: [mirror-root.md](mirror-root.md) §4 and §7,
[adbd-keys.md](adbd-keys.md), [option-c.md](option-c.md), [image-patch.md](image-patch.md).

A short **read-only** panel check (for the root — only the root drives the Mirror) would help settle
whether `shihanqu`'s exact mechanism — a free `adsprpc.ko` slot that `init` loads on boot — exists on
**our** image. All of it is `ls`/`cat`/`dmesg`/`getprop`, no writes, no serial printed
(`S=$(cat /tmp/mirror-serial)`):

```sh
# Is the adsprpc.ko slot free, and does init try (and fail) to load it on boot?
adb -s "$S" shell ls -l /system/lib/modules/adsprpc.ko     # expect "No such file" if free
adb -s "$S" shell 'dmesg | grep -iE "adsprpc|insmod"'      # a failed-insmod line CONFIRMS the trigger
adb -s "$S" shell cat /proc/modules                        # expect wlan, not adsprpc
# Does our image ship /system/xbin/su (shihanqu needs it)?
adb -s "$S" shell ls -l /system/xbin/su                    # mode if readable, else Permission denied
# Confirm the kernel identity for a vermagic-adapted module (already known: -gf46dad5260f, module_layout 0xa5b7f7dd)
adb -s "$S" shell cat /proc/version
```

The check is asymmetric: a failed-insmod line **confirms** the trigger exists; a **blank** result is
**inconclusive**, not proof it's absent. AOSP 6.0.1 `init` logs a command's result at a log level
that may not reach the kernel log on this build, and the ring buffer scrolls, so the trigger could be
present with no visible line. Reading the ramdisk `init.qcom.rc` (or watching a module load across a
clean boot) is the only thing that would settle the negative case, and both are out of `shell`'s
reach. The fuller version, with the pass/fail reading for each, is in the root's trail
(`~/.t3/glassworks-m1/573/panel-plan.md`). Nothing in this note needs a device.

## Open questions

- **Does our Glass Panes image have the free `adsprpc.ko` slot and the init `insmod` trigger?** The
  trigger lives in the boot ramdisk's `init.qcom.rc` (0750 root, unreadable as `shell`), so it can be
  read only indirectly — see the panel plan (a failed-insmod line in `dmesg` confirms it; a blank
  result is inconclusive, not a negative), and `ls /system/lib/modules`. If the slot turns out to be
  occupied, or the trigger is shown absent (by reading the ramdisk, not by a blank `dmesg`),
  `shihanqu`'s approach would need a different trigger on our image.
- **Does our image ship `/system/xbin/su` (04750)?** `shihanqu` relies on it; our image is the same
  userdebug family, but we never confirmed `su` is present and setuid. Read-only check in the plan.
- **Would a vermagic-adapted module load on our `-gf46dad5260f` kernel?** `module_layout` already
  matches (`0xa5b7f7dd`); the two imports are primitive. Untested — needs a build and a panel run
  (rule 5), and is a separate maintainer decision (permissive SELinux, a GPL binary).
- **Can a stock Mirror give adb beyond the one 1.16.0 unit, and on firmware 1.2x+?** `jstriblet`
  reports 1.2x+ breaks the key method. Untested by us (no stock unit). This is
  [option-c.md](option-c.md)'s open question, now with one positive data point.
- **On our image, is a TCP adb transport auto-approved for the owner fingerprint** the way USB is
  (services.jar defers by transport-agnostic fingerprint)? Untested; it would decide whether network
  adb is a lever for #435/#486/#577.

<!--
No serials, MACs, keys, fingerprints or vendor/Glass Panes bytes are in this note. Both community
repos are referenced by owner/repo path:line, not by local clone paths; the clones were deleted after
reading. Added to docs/research/README.md and frames/lululemon-mirror/README.md.
-->
