# CI emulator, API 34: why `conformance (API 34)` died mid-test

**Status (2026-11-17): working.** Two faults, both fixed. The emulator itself crashed (qemu SIGSEGV)
under `-gpu swiftshader_indirect`, and the permission dialog paused MainActivity before the test
read its state. The job now renders with `swangle_indirect`, and the test grants the mic and camera.

Runner: GitHub `ubuntu-24.04` (4 vCPU, 16 GB, KVM; Intel Xeon 6975P-C and AMD EPYC hosts).
Emulator 37.2.13.0, `system-images;android-34;google_apis;x86_64`, reactivecircus/android-emulator-runner
v2.38.0 with its defaults (2 cores, 320×640 skin).

## Question

`conformance (API 34)` failed about one run in four and passed on rerun. It failed on #418, #429,
#433, #434 and main. Each failure read the same way:

```
sh.edm.s1kiosk.conformance.CoreConformanceTest > mainActivityStartsAndStandsAsTheFrameSays FAILED
com.android.ddmlib.AdbCommandRejectedException: device offline
... device 'emulator-5554' not found
Test run failed to complete. Expected 6 tests, received 0
adb -s emulator-5554 emu kill → could not connect to TCP port 5554: Connection refused
```

API 23, 26 and 29 rarely failed this way.

## What we tried

1. **The history** (2026-11-17, verified). The last 100 runs of `core-emulators.yml`, every attempt:
   API 34 passed 82 of 108 completed attempts. API 23, 26 and 29 lost 1 to 3 each. 3 of the API 34
   failures were real build errors. The rest died in the first test, and only after #412 added
   `mainActivityStartsAndStandsAsTheFrameSays` (2026-11-16 11:52 UTC).

2. **`adb: device offline` during boot** (verified). It shows in passing runs too, two polls each,
   before adbd is authorised. It is noise, not the fault.

3. **A probe workflow** (verified). Ten copies of the unchanged job on a throwaway `ci-probe/*`
   branch, with a host monitor (`free -m`, top RSS, every second), a streamed guest `logcat`,
   `dmesg -w`, and after the test `coredumpctl` and `gdb` on the core. Three rounds: 19 of 29 copies
   passed.

4. **Host memory pressure** (verified, ruled out). At the crash the host still had 13.5 GB available
   and no swap in use. No OOM killer line in `dmesg`.

5. **What dies** (verified). Every failing copy left a core:
   ```
   $ coredumpctl list
   ... SIGSEGV present /usr/local/lib/android/sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless 834.8M
   $ gdb -batch -ex bt -ex 'x/4i $pc' qemu-system-x86_64-headless core
   #0  0x00005626bfc4e687 in ?? ()
   => 0x5626bfc4e687:  movdqu 0x184e0(%rax,%rcx,1),%xmm5
   ```
   The same instruction (page offset `0x687`, stripped main binary) in every core, in a thread made
   late in the run. The stack holds float pairs like 131.5 and 373.5, which look like coordinates.
   The host GL libraries of `swiftshader_indirect` are loaded (`libEGL.so`, `libGLESv2.so`,
   `libGLES_CM.so`). The guest log stops within 2 s of the trigger. Writing the 850 MB core takes
   about 25 s, which is why adb first says `device offline` and only then `not found`.

6. **The trigger** (verified). In all 10 failing copies the guest log ends 0.5 to 2 s after
   `START u0 {act=android.content.pm.action.REQUEST_PERMISSIONS ...}`. MainActivity asks for
   `RECORD_AUDIO` and `CAMERA` at start (`requestRuntimePermissions`). The conformance APK declared
   neither, so Android opened its permission activity over MainActivity, found nothing to ask, and
   closed it. Passing copies open the same activity and survive it.

7. **`-gpu swangle_indirect`, test unchanged** (verified). No crash in 8 copies, but 6 failed another
   way: `expected:<RESUMED> but was:<STARTED>`. The permission activity paused MainActivity before
   the test read `s.state`. So the dialog causes two faults: a crash under one renderer, a race
   under the other.

8. **Grant the permissions, `swiftshader_indirect`** (verified). The test declares both and grants
   them with `pm grant` before it launches MainActivity. No permission activity opens. 11 of 12
   passed. The one failure was the same qemu SIGSEGV with no dialog at all, so the dialog is one
   trigger, not the only one.

9. **No rotation: `-skin 640x320`, with the grant** (verified, ruled out). MainActivity asks for
   `SENSOR_LANDSCAPE`, so on the 320×640 skin the display turns. A landscape-native skin removes
   every rotation (`CHANGE_ROTATION` count 0). 5 of 7 passed. Both failures were the same SIGSEGV.

10. **`-gpu swangle_indirect` with the grant** (verified). 7 of 7 passed. Then 10 more copies of the
    PR head with the final settings: 10 of 10 passed.

11. **Job time** (verified). The emulator step takes about 135 s with either renderer. The whole job
    takes 410 to 465 s with either.

## Result

| Settings | Passed | How the rest failed |
|---|---|---|
| Before: `swiftshader_indirect`, no grant | 19 of 29 | qemu SIGSEGV |
| `swangle_indirect`, no grant | 2 of 8 | STARTED, not RESUMED |
| `swiftshader_indirect`, grant | 11 of 12 | qemu SIGSEGV |
| `swiftshader_indirect`, grant, landscape skin | 5 of 7 | qemu SIGSEGV |
| After: `swangle_indirect`, grant | 17 of 17 | none |

The fix is both changes. `core-emulators.yml` sets `-gpu swangle_indirect` for API 34 only (23, 26
and 29 keep `swiftshader_indirect`; they don't crash). `CoreConformanceTest` grants `RECORD_AUDIO`
and `CAMERA` before it starts MainActivity, as a frame's setup does.

## How to reproduce

Push a branch named `ci-probe/<name>` with a workflow that runs the job N times in a matrix and,
after it, `coredumpctl list` and `sudo apt-get update && sudo apt-get install -y gdb` then
`coredumpctl dump -o core` and `gdb -batch -ex bt -ex 'x/4i $pc' <qemu-system-x86_64-headless> core`.
It touches only GitHub's runners. Delete the branch after.

## Open questions

- Which qemu function faults. The binary is stripped. A report to the emulator's tracker would need
  the build id (16740729) and the instruction above.
- MainActivity asks for permissions its app may not declare. On `generic-android` (no `CAMERA` in its
  manifest) it opens the permission activity on every start for nothing. Filtering by the declared
  permissions belongs in MainActivity, after #418 and #429 (which change that file) land.
