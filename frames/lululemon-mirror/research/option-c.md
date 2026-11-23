---
topic: android
evidence: read
---

# Option C: can Kiln build a Mirror system image from our own AOSP build plus vendor files taken off the person's Mirror?

**Status (2026-11-18): feasible on paper, with three gaps; nothing run on a device.** A Mirror image
needs about 140 vendor files (17 MB) without the camera, out of the 1,060 (213 MB) the community
image carries. adb's shell can read all but 13 of them off a running Mirror. Those 13 are daemons
whose SELinux labels let only `init` and a few system daemons read them. Whether a Mirror on stock
software gives adb is untested (the maintainer doesn't expect it), and no other way to read it is
known. So, for now, option C is an upgrade for a Mirror that already runs option A, not a first
install. An
AOSP 6 + CAF build fits GitHub Actions only on a paid larger runner. Every AOSP system image carries
GPL programs, which hard rule 4 doesn't allow us to ship today. The checks on the Mirror are in the
root's panel plan.

Device: none touched. The image read is Glass Panes `mirror-android-2` (`bdb6b844…e35c1`, the image
Kiln pins), and `mirror-android-1` (`d13dd330…83ed5`) for comparison. Both are copies the root
downloaded earlier for the option A work, outside the repo. The policy is the unit's
`magiskpolicy` dump from [mirror-root.md](mirror-root.md) section 3. Tools: e2fsprogs 1.47.4
(`debugfs`), LLVM `llvm-readelf`, `git ls-remote`, the GitHub API, and two new scripts:
[`tools/mirror/vendor-files.py`](../../../tools/mirror/vendor-files.py) and
[`tools/mirror/vendor-pull-check.sh`](../../../tools/mirror/vendor-pull-check.sh).

## Question

Issue #334: our own Android 6.0.1 `system` image for the Mirror, built from open source (AOSP
`MMB29M` and Qualcomm's open msm8916 code), with empty slots for the proprietary files. Kiln fills
the slots at install time. The maintainer's update on 2026-11-15: the Glass Panes release grants no
licence for its vendor files, so they should come from the person's own Mirror over adb, the
LineageOS way, and never from us.

This note answers five questions:

1. Which proprietary files does the image need?
2. Which of them can adb's shell read off a stock or Glass Panes Mirror, without root?
3. Can we build AOSP `MMB29M` plus CAF msm8916 here: toolchain, time, CI cost, GitHub Actions?
4. How would Kiln assemble the image in the browser?
5. What are the risks, and what are the PR-sized steps?

Each claim is marked **verified** (ran it, with the date), **read** (from a source or a file, cited)
or **untested**.

## What we did

All on 2026-11-18, off-device. Nothing was written to a Mirror, and no vendor bytes are in the repo.

1. **Listed the image** (verified). Unsparsed image 2 into a scratch folder, then walked it with
   `debugfs ls -p` and read every file's SELinux label with `debugfs ea_list`:
   ```
   2,678 files; 2,563 labelled system_file, 115 with a daemon's *_exec label
   modes: 2,434 files 0644, 235 files 0755 (root:shell); 9 others (run-as, uncrypt, su, …)
   ```
   Every vendor file is world-readable by its mode. Only the SELinux label can stop a reader.

2. **Told the vendor files apart from the open ones** (verified). The Glass Panes build doesn't say
   which files are proprietary. The community build tooling
   ([`Onemoremichael/lulu-mirror-software-only`](https://github.com/Onemoremichael/lulu-mirror-software-only)
   at `efddcbd`, `docs/BUILD.md`) says the vendor tree is Qualcomm's `prebuilt_HY22`. #40 found it
   re-hosted at `RSB4760/apq8016_vendor_qcom_proprietary`, branch
   `LA.BR.1.2.7-01010-8x16_advan` (commit `97899ddb`, 2017-11-09). We listed that tree's file
   **names** and git blob ids with the GitHub tree API (no file contents were downloaded), then
   matched them against the image:
   ```
   $ gh api 'repos/RSB4760/apq8016_vendor_qcom_proprietary/git/trees/LA.BR.1.2.7-01010-8x16_advan?recursive=1' \
       --jq '.tree[]|select(.type=="blob")|"\(.sha)\t\(.path)"' | grep 'prebuilt_HY22/target/product/msm8916_64/system/'
   1,094 names under prebuilt_HY22/…/system
   in the image: 1,060 of them, 213.0 MB
   git hash-object of the image's copy == the tree's blob id: 1,011 of 1,060
   ```
   So the community image's vendor files are that tree, byte for byte, apart from 49 files. 22 of
   the 49 are APKs, which a build re-signs. The rest are `mm-qcamera-daemon`, `hci_qcomm_init`, a
   cdma config file and 24 libraries in `/vendor/lib`. This confirms #40's reading: the files are
   Qualcomm's DragonBoard 410c Android BSP (LA.BR.1.2.7), with no licence.

3. **Compared image 1 with image 2** (verified). The same 1,060 vendor files, every one with the same
   SHA-256, and the same file list. So the hashes below hold for both releases.

4. **Worked out what the image needs** (read, from the image's ELF files). We copied `/lib`,
   `/lib64`, `/vendor`, `/bin` and `/xbin` to a scratch folder (deleted after) and read each ELF's
   `DT_NEEDED` with `llvm-readelf --needed-libs`. Two ways tie open code to a vendor file:
   - **Linked:** 13 open programs and libraries link 20 vendor libraries by name. Three of them
     matter for the whole system, not just one feature: `libskia` links `libqc-opt.so`,
     `libaudioflinger` links `libsrsprocessing.so`, and `wpa_supplicant` links `libidl.so` and the
     QMI libraries.
   - **Loaded at run time:** 23 vendor libraries are named as strings inside open code, which
     `dlopen`s them. For example, `hwcomposer.msm8916.so` names `libEGL_adreno.so` and
     `libmm-qdcm.so`, `audio.primary.msm8916.so` names `libacdbloader.so`, and
     `keystore.qcom.so` names `libQSEEComAPI.so`.

   From those, plus the GLES drivers, the firmware in `/etc/firmware` and the daemons a Qualcomm
   build starts, the script takes everything they link, recursively. The result is
   [`option-c-vendor-files.tsv`](option-c-vendor-files.tsv): the path, size, SHA-256 (from image 2),
   label, whether the shell may read it, and why each file is there.

5. **Checked who may read each label** (read, from the unit's policy dump). The `shell` domain has
   the attributes `mlstrustedsubject netdomain appdomain domain`. The dump has
   `allow domain system_file file { ioctl read getattr lock execute open }`. For a daemon's label it
   has read rules only for `init`, `debuggerd`, `mdtpdaemon` and the daemon itself, for example:
   ```
   $ grep -E "^allow [^ ]+ (tee_exec|exec_type|file_type) file \{[^}]*read" sepolicy-rules.txt | awk '{print $2}' | sort -u
   debuggerd init mdtpdaemon tee
   ```
   The other daemon labels give the same list. `system_app` (the platform-key app from
   [mirror-root.md](mirror-root.md) section 6) and `qti_init_shell` (the `post_boot.sh` root from
   section 7) aren't in it either.

6. **Read the community's AOSP 6 build** (read). `lulu-mirror-software-only` at `efddcbd`:
   `docs/BUILD.md`, `docs/PROVENANCE.md`, `build/container-build.sh`, `build/prepare.py`,
   `build/manifest.xml`. And whether CodeLinaro, which took over from CodeAurora (CAF), still has
   the sources (verified with `git ls-remote`):
   ```
   $ git ls-remote https://git.codelinaro.org/clo/la/kernel/msm-3.10.git | grep 'LA.BR.1.2.7-01010-8x16.0$'
   3565efb7…  refs/tags/LA.BR.1.2.7-01010-8x16.0
   $ git ls-remote https://git.codelinaro.org/clo/la/platform/frameworks/base.git | grep 'LA.BR.1.2.7-01010-8x09.0^{}'
   09bab0cc…  refs/tags/LA.BR.1.2.7-01010-8x09.0^{}        # the revision the community manifest pins
   $ git ls-remote https://git.codelinaro.org/clo/la/platform/hardware/qcom/display.git | grep -c 'LA.BR.1.2.7-01010'
   0
   ```
   CodeLinaro's platform manifest repo has no `LA.BR.1.2.7-01010-8x16.0` tag either. So some of the
   sources are on CodeLinaro by tag, and the rest only through the community's pins (439 of its 447
   projects come from the `RSB4760` GitHub organisation).

7. **Read GitHub's runner sizes and prices** (read, docs.github.com, 2026-11-18).

## Result

### 1. The files the image needs

The script's parts, from [`option-c-vendor-files.tsv`](option-c-vendor-files.tsv). "Built" files
come from open source; the rest are vendor files.

| Part | Files | Size | Why | Shell can't read |
|---|---|---|---|---|
| GPU and display | 40 | 7.2 MB | Adreno GLES and its helpers, Adreno microcode, the display post-processing daemon | `mm-pp-daemon` |
| Platform | 31 | 2.7 MB | Daemons a Qualcomm build starts (secure world, time, thermal, remote storage, peripheral manager, QMI), and the hardware keystore | 10 daemons (below) |
| Wi-Fi and Bluetooth | 17 | 70.8 MB | QMI libraries that `wpa_supplicant` and `wcnss_service` link, Bluetooth NV. Also `pronto_wlan.ko` (70 MB, a debug build), which is built from source, not a vendor file | `wcnss_service`, `cnss-daemon` |
| Audio | 36 | 3.1 MB | ACDB calibration loader and data, resampler, SRS, surround | none |
| Video | 17 | 3.7 MB | Venus firmware, HEVC decoder | none |
| Camera (camera pane only) | 321 | 18.4 MB | mm-camera daemon, its modules, sensor tuning, CPP firmware | `mm-qcamera-daemon` |
| **Without the camera** | **141** | **87.5 MB** (17.5 MB without the Wi-Fi module) | | **13** |
| **All** | **462** | **105.9 MB** | | **14** |

How sure: **read.** The script finds what open code links or names. It can't see a library loaded
by a path built at run time, and it doesn't know which daemons the stock `init.rc` starts (that file
is `0750 root`, unreadable as shell, [mirror-root.md](mirror-root.md) section 5). The panel plan
lists the services `init` knows (`init.svc.*`), which settles the daemon list. Only a boot of the
assembled image proves the list.

Firmware the image doesn't need to carry: the Wi-Fi (`wcnss`) and modem firmware are in the
unit's own `modem` partition, mounted at `/firmware`, which flashing never writes
([mirror-root.md](mirror-root.md) section 7, verified 2026-11-15). Venus isn't there, so video
firmware has to come from `system`. The Adreno microcode (`a300_pm4.fw`, `a300_pfp.fw`) is also in
linux-firmware as "Redistributable" ([mirror-android.md](mirror-android.md#5-blobs-what-we-could-ship-what-must-come-off-the-device-ties-to-40)).
Whether those copies are the same bytes is **untested**.

### 2. What adb's shell can read off a Mirror

| Mirror | adb | What the shell reads |
|---|---|---|
| Option A (Kiln's patched image) | Yes, Kiln's own key (verified 2026-11-17, #443) | 128 of the 141 files. Not the 13 daemons: their labels allow only `init`, `debuggerd`, `mdtpdaemon` and the daemon (read, the unit's policy) |
| Plain Glass Panes image | Yes, the published project key | The same 128 (read). Kiln shouldn't use that key, so option A comes first |
| Stock lululemon software | **Maybe, with work.** The stock ramdisk sets `persist.sys.usb.config=none`, and the Mirror has no touchscreen to allow a computer, so a bare adb connection gives nothing. But two community reports now show authorised adb on stock by planting a key (and, for network adb, a TCP port) in a fresh `userdata`: a USB report on `IFC-6309-2.0-MIR/329`, and a network-adb report on **one** `1.16.0` unit ([community-root-and-network-adb.md](community-root-and-network-adb.md); `1.2x+` reportedly breaks it). The maintainer doesn't expect it to generalise ([#40](https://github.com/GlassworksHQ/glassworks/issues/40)) | **Untested by us, and uncertain for stock.** The 13-daemon result is read from the **dev unit's** policy and the Glass Panes `file_contexts`. The reported stock units run a **different boot build**, whose policy and vendor-file labels we have not read, so whether a stock `shell` can read the 13 daemons is **not established** — likely the same (the policy is in the signed boot), but unverified until a stock unit's labels are checked |

So:

- **No way is known to take a vendor file off a stock Mirror before the first flash.** Whether a
  stock Mirror gives adb at all is **untested** (above, and an open question below). Fastboot can't
  read (the locked LK has no read-back, [mirror-root.md](mirror-root.md) section 7). So, as far as
  we know, option C is an **upgrade for a Mirror that runs option A**, not a first install, and a
  stock Mirror goes through option A first.
- **On a Glass Panes or option A Mirror, "the person's own files" are the Glass Panes files.** The
  1,060 vendor files on such a Mirror are the ones in the release (step 2 above). Pulling them over
  adb keeps them off our servers, which is the point. It doesn't change who made them.
- **The 13 daemons need another source.** In order of preference:
  1. The person's own copy of the Glass Panes `system.img` (option A's "picked by the person" path,
     [community-image-terms.md](community-image-terms.md)). Kiln already reads it with
     `@glassworks/ext4`. It never passes through us.
  2. Leave a daemon out, where the Mirror doesn't need it. Each one needs a test boot:
     `msm_irqbalance`, `qcom-system-daemon` and `mm-pp-daemon` look optional; `qseecomd` backs
     the hardware keystore (a software keystore would do for a kiosk); `thermal-engine`,
     `rmt_storage`, `tftp_server`, `qmuxd`, `time_daemon`, `pm-service` and `irsc_util` are
     **unknown**. `thermal-engine` is the one to keep: it caps the CPU when the board is hot, and
     the board sits sealed behind a 40" panel.
  3. A `system_app` or root route: none found. Neither domain may read these labels (step 5).
- **What's on `/persist`** (Wi-Fi NV, calibration) stays on the unit. Flashing never writes it, and
  the image doesn't carry it.

### 3. Building AOSP `MMB29M` + CAF msm8916

| Item | Finding | How sure |
|---|---|---|
| Source | The community's `build/manifest.xml`: 447 projects at pinned commits for `msm8916_64`, CAF LA.BR.1.2.7. 439 come from the `RSB4760` GitHub org (named after an Advantech APQ8016 board), 7 from other GitHub mirrors (frameworks/base from `AOSParadox`), 1 from CodeLinaro | Read (`efddcbd`) |
| Upstream | CodeLinaro still has `LA.BR.1.2.7-01010-8x16.0` on `kernel/msm-3.10`, and the community's `frameworks/base` pin is CodeLinaro's `LA.BR.1.2.7-01010-8x09.0`. It has no such tag on `hardware/qcom/display` or the platform manifest | Verified 2026-11-18 (`git ls-remote`) |
| Proof that it boots on this boot image | Glass Panes' fingerprint (`msm8916_64`, `MMB29M`, `userdebug`) matches the community script's `lunch msm8916_64-userdebug`, and that image runs under the stock signed boot every day on the maintainer's Mirror. Whether Glass Panes used exactly this manifest isn't published | Read (its fingerprint and the community build script); the boot verified since 2026-11-15 |
| Host tools | Old ones: OpenJDK 7, Python 2.7 for the build's own scripts, the prebuilt GCC 4.9 cross-compilers (`linux-x86` only), and Jack, Android 6's Java compiler, which needs several GB of RAM. The community pins a container, `alleen/apq8016_bm@sha256:b1d57497…`, and doesn't vouch for it | Read (`BUILD.md`, `PROVENANCE.md`; AOSP's requirements for 6.0, from memory, not reopened) |
| Disk | "at least 100 GiB" of workspace (`BUILD.md`). A shallow sync of only the needed projects would be smaller | Read; the shallow size untested |
| A Mac | No: the prebuilts are Linux x86-64 and the tree needs a case-sensitive filesystem. It would run under emulation, slowly | Read |
| Time | Not published. The community caps its container at 2 cores and 4 GB. Our guess for 16 cores: 30–60 min to sync, 1–2 h to build | **Untested** |
| GitHub Actions, standard runner | 4 CPU, 16 GB RAM, **14 GB SSD** for a public repo. Doesn't fit without deleting the runner's preinstalled tools to free disk, and even then 100 GiB is out of reach. 6 h per job | Read (docs.github.com, 2026-11-18); the 6 h limit from memory |
| GitHub Actions, larger runner | 16 CPU, 64 GB, 600 GB SSD at $0.042/min. A 3 h run costs about $7.60. Larger runners aren't free for public repos and need a card on the org | Read (docs.github.com, 2026-11-18) |
| A self-hosted runner | Any Linux x86-64 box with 200 GB free | Read; not set up |
| How often | Only when the recipe changes, on a manual or tag trigger, never on each PR. The `ccache` wouldn't fit GitHub's cache anyway | Our proposal |

**The build must link without the vendor files.** Nine open modules link vendor libraries (step 4):
`libskia`, `libaudioflinger`, `libaudioresampler`, `wpa_supplicant`, `wcnss_service`, the HEVC
OMX component, `libbt-vendor`, `libloc_api_v02` and the camera HAL. With an empty vendor tree they fail to link. Two ways round it,
**untested**:
- Turn the feature off in our device config (CAF's SRS and Qualcomm skia switches), where a switch
  exists.
- Build **stub libraries**: a `.so` with the same name and symbol names and no code, used only at
  link time and left out of the image. The vendor file fills the slot at install time. The symbol
  names come from reading the vendor library once. That is names, not bytes, which hard rule 3
  allows.

**Hard rule 4: every AOSP system image ships GPL programs.** Image 2 has `iptables`, `ip6tables`,
`dnsmasq`, `e2fsck`, `resize2fs`, `ip`, `tc`, `pppd`, `strace` and others (read, the listing). Our
blob-free image would carry them too, and publishing it ships GPL payloads. The Wi-Fi module
declares `Dual BSD/GPL` ([mirror-root.md](mirror-root.md) section 4). The maintainer has to decide
this before anything is published:
- allow it, with a GPL source offer or the matching sources published beside each image; or
- take those programs off the person's Mirror too (they're all `system_file`, so the shell can read
  them), which makes the slot list longer but ships no GPL bytes; or
- leave out what a kiosk doesn't need (`pppd`, `racoon`, `mtpd`, `strace`), and decide only for the
  rest.

### 4. How Kiln would assemble the image

Everything here already exists for option A except the pull and the base image
([image-patch.md](image-patch.md), `frames/lululemon-mirror/installer/src`, `@glassworks/ext4`).

1. **The base image.** Our CI builds `system-base.img`: our own `system`, vendor slots empty, signed
   with our own keys, and a `vendor-slots.json`. For each slot it gives the path, SHA-256, mode,
   owner, SELinux label (a daemon's `*_exec` label, as our build's `file_contexts` gives it) and
   part. We publish both on our release. Kiln fetches them through Refract, as it does for APKs
   (GitHub sends no CORS headers): a line in the frame's `installer.json` `releases`.
2. **Connect.** The Mirror runs option A, and Kiln holds its key (in the browser, as today).
   Kiln checks the fingerprint, the board (`msm8916`) and the space it has in the browser (OPFS).
3. **Pull.** Kiln reads each slot over adb's sync protocol and hashes it as it streams.
   `@yume-chan/adb`, which Kiln uses, has the sync `read`; Kiln's `Panel` only wraps `push` today,
   so this adds a `pull`. A file whose hash isn't in the list stops the install, and nothing is
   written. The 13 daemons come from the person's `system.img`, through `readSparse` and
   `Ext4.readFile`, the same way.
4. **Keep a copy.** The pulled files stay in OPFS, and Kiln offers to save them as one file on the
   person's computer. After the flash, the Mirror no longer has them.
5. **Assemble.** `readSparse` the base image into a `Disk`, `Ext4.open`, `writeFile` each slot with
   its attributes and label, then the same additions as option A: the app as a priv-app, the boot
   animation, the settings overlay, `ro.glassworks.frame.*`. Then `flush` and `writeSparse`.
6. **adb's key.** Our build changes `UsbDebuggingManager` in the source to trust a fingerprint read
   from one file in `/system`, which Kiln writes. This replaces option A's dex rewrite (adbd still
   ignores `adb_keys`, [adbd-keys.md](adbd-keys.md)). **Untested.**
7. **userdata.** A fresh one, as in option A. Our platform key differs from the image's test key,
   and Android 6's package manager checks each system package's signature against what userdata
   recorded (`packages.xml`). A whole new platform key is the case it handles worst. **Untested**
   (our reading of AOSP 6's `PackageManagerService`, from memory); keeping the data is a later
   question.
8. **Flash.** `adb reboot bootloader` (no need to open the back: the Mirror already runs adb),
   the fastboot checks, flash `system` and `userdata`, then the first boot, all as in option A.

**The way back** is option A again: the patched Glass Panes image, which Kiln already builds.
Neither image touches anything but `system` and `userdata`.

## How to reproduce

Off-device only. It writes nothing outside a scratch folder. Run it from a new, empty folder, with
the repo's path in `$REPO`, so nothing in the repo can be overwritten:

```sh
REPO=/path/to/glassworks
W=$(mktemp -d) && cd "$W"
```

1. Get image 2's `system.img` from the Glass Panes release and check its SHA-256
   (`bdb6b844…e35c1`). Unsparse it (`simg2img system.img system.raw`).
2. List the vendor tree's names:
   `gh api 'repos/RSB4760/apq8016_vendor_qcom_proprietary/git/trees/97899ddbcd8916de7663ecd2102c70719460f5bf?recursive=1' --jq '.tree[]|select(.type=="blob")|.path' | grep '^prebuilt_HY22/target/product/msm8916_64/system/' | sed 's|^prebuilt_HY22/target/product/msm8916_64/system||' > "$W/hy22.txt"`
3. `TMPDIR="$W" python3 -I "$REPO/tools/mirror/vendor-files.py" --raw "$W/system.raw" --vendor "$W/hy22.txt" --rules /path/to/sepolicy-rules.txt > "$W/option-c-vendor-files.tsv"`,
   then compare it with the one in the repo (`diff "$W/option-c-vendor-files.tsv" "$REPO/frames/lululemon-mirror/research/option-c-vendor-files.tsv"`).
   It needs `debugfs` and `llvm-readelf` (`brew install e2fsprogs llvm`). It runs in about 35 s on
   an M-series Mac. `--rules` is the unit's policy dump ([mirror-root.md](mirror-root.md) section 3,
   kept outside the repo); without it the `shell` column is `?`. With `TMPDIR="$W"` its scratch
   copy of the image's ELF files (about 450 MB, deleted when it ends) is under `$W` too, so even a
   killed run leaves nothing outside it.
4. `cd "$REPO"`, then delete `$W` (`system.img`, `system.raw`, the list) and the policy dump. Never commit them.

On a Mirror (only the root, read-only): `ANDROID_ADB_SERVER_PORT=<port> tools/mirror/vendor-pull-check.sh
-s <serial> frames/lululemon-mirror/research/option-c-vendor-files.tsv`, through the adb server
already bound to that Mirror. It stops unless that server answers on the port with the client's
own version, so it never starts or replaces one. It `cat`s each file as adb's shell and hashes it
on the computer.

## Open questions

- Which daemons does the Mirror's `init` start? (`init.svc.*`, in the panel plan.)
- Does the shell really read the 128 files and get refused on the 13, as the policy says?
  (`vendor-pull-check.sh`, in the panel plan.)
- Which of the 13 daemons can the image leave out? Each needs a test boot.
- Does an open-source build with stub libraries link, and does it boot with the vendor files filled
  in? Nobody has built the community's manifest with an empty vendor tree.
- Hard rule 4, the GPL programs in any AOSP image: the maintainer's call.
- Can a stock Mirror give adb at all? If it can, its own vendor files (lululemon's build, never read
  by anyone we know) would be the cleanest source. Needs a stock unit, which we don't have. One
  community report now answers this for a single unit: authorised **network** adb on a stock Mirror
  at firmware 1.16.0, by planting a key and a TCP port in a fresh userdata (1.2x+ reportedly breaks
  it) — [community-root-and-network-adb.md](community-root-and-network-adb.md). Untested by us; and
  whether a stock `shell` could read the 13 daemons is **not established** (the stock build's policy
  and labels were never read — likely the same, since the policy is in the signed boot, but
  unverified).
- Does Android 6 keep userdata across a change of platform key, or must the move wipe it?

## The plan, in PR-sized pieces

| # | Piece | Needs | Touches |
|---|---|---|---|
| 1 | This note, the slot list, `vendor-files.py` and `vendor-pull-check.sh` | — | Docs, tools |
| 2 | The root's panel run: `init.svc.*` and the pull check (read-only) | 1 | Nothing on the Mirror |
| 3 | The maintainer decides: GPL programs in our image (rule 4), and the source of the 13 daemons | 1, 2 | — |
| 4 | `Panel.pull` in `@glassworks/adb`: sync read with a streaming SHA-256, tested on the fake | — | Kiln's adb package |
| 5 | The build recipe: our manifest (pinned, CodeLinaro first, then the community's mirrors), our device config with empty slots, stub libraries, the `UsbDebuggingManager` change, a pinned container. A `workflow_dispatch` job on a larger or self-hosted runner that writes `system-base.img` and `vendor-slots.json` as workflow artifacts, not a release | 3 | CI, `frames/lululemon-mirror/system/` |
| 6 | Boot it on the dev Mirror: our base image with the slots filled from that Mirror, through `flash-node.ts --system-base`. The way back is option A | 4, 5 | The Mirror's `system` and `userdata` (the root, with a backup first) |
| 7 | Kiln: `assembleSystem` in the installer package (pull, check, write slots, keep a copy), with tests on the fake Mirror | 4, 6 | Kiln |
| 8 | Kiln's page: "Move to Glassworks' own image" for an option A Mirror, with the way back. Screenshots, in Kiln's approved direction (#32) | 7 | Kiln UI |
| 9 | Release: publish the base image, its slot list and (if 3 allows) the GPL sources, with SHA-256 | 8 | Releases |

Pieces 5 and 6 are the weeks of work #334 expects. Option A stays the install for a stock Mirror
throughout.

## Sources

Opened 2026-11-18.

- Issues [#334](https://github.com/GlassworksHQ/glassworks/issues/334) (with the maintainer's
  2026-11-15 update), [#29](https://github.com/GlassworksHQ/glassworks/issues/29) and
  [#40](https://github.com/GlassworksHQ/glassworks/issues/40).
- This frame's notes: [image-patch.md](image-patch.md), [community-image-terms.md](community-image-terms.md),
  [mirror-android.md](mirror-android.md), [mirror-root.md](mirror-root.md), [adbd-keys.md](adbd-keys.md).
- [`Onemoremichael/lulu-mirror-software-only`](https://github.com/Onemoremichael/lulu-mirror-software-only)
  at `efddcbd`: `docs/BUILD.md`, `docs/PROVENANCE.md`, `build/container-build.sh`,
  `build/prepare.py`, `build/manifest.xml`.
- [`RSB4760/apq8016_vendor_qcom_proprietary`](https://github.com/RSB4760/apq8016_vendor_qcom_proprietary),
  branch `LA.BR.1.2.7-01010-8x16_advan` at `97899ddb`: file names and blob ids only.
- CodeLinaro: `git.codelinaro.org/clo/la/kernel/msm-3.10`, `…/platform/frameworks/base`,
  `…/platform/hardware/qcom/display`, `…/platform/manifest` (`git ls-remote`).
- GitHub: [GitHub-hosted runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners),
  [larger runners](https://docs.github.com/en/actions/reference/runners/larger-runners),
  [runner pricing](https://docs.github.com/en/billing/reference/actions-runner-pricing).
