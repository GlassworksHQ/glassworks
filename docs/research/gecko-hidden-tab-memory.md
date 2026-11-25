---
topic: perf
evidence: verified
---

# GeckoView's page process behind another app: why it grew to 1.4 GB, and what the kiosk does about it

**Status (2026-11-24): partly working.** One kiosk defect is found, fixed and verified on the S1: a page reopened
while the kiosk is stopped (behind another app, or with the screen off) stayed "shown" for as long as the kiosk stayed
stopped. With the fix it is hidden within 3 s (attempt 7). The growth itself is not explained. The S1's own log shows
a page growing to 1.27 GB while it was correctly hidden, so the fix is not the whole answer. Telling the causes apart
needs parts A–C and F of the panel plan below (#829).

Device: Aqara S1 Plus, the S1 app 0.9.0 (900), GeckoView 159, Android 10. Read from: GeckoView 159 and 144 AARs
(`javap`), Home Assistant frontend `src/layouts/home-assistant.ts` and `src/state/connection-mixin.ts` (dev branch,
2026-11-24), the panel's logcat from 2026-11-24 (kept off the repo, addresses redacted).

## Question

On 2026-11-24 the S1 app's page process (`sh.edm.s1kiosk:sandboxed_tab…`) reached 1.40 GB PSS while the kiosk sat
behind another app (a test app that a probe was driving). MemAvailable fell by about 50 MB a minute to 94 MB, and
Android's low-memory killer took the page. The kiosk opened a new one, and that grew at the same rate: 381 MB to
652 MB in five minutes, of which 589 MB was "Unknown" in `dumpsys meminfo` (anonymous memory, so Gecko's heap and JS,
not graphics). Is this a kiosk bug, a Home Assistant frontend behaviour, or the dashboard?

## How we got here

The obvious suspect was that the kiosk never tells GeckoView the page is hidden. Nothing in the repo calls
`GeckoSession.setActive`, so a backgrounded page would still be "visible", keep drawing, and stop Home Assistant from
noticing it is hidden. Reading GeckoView changed that picture: `GeckoView` calls `setActive(false)` itself when its
surface goes (the activity stops) and `setActive(true)` when a surface comes back. The S1's log agrees. GeckoView's
`ServiceAllocator` logs each content process's priority, and the page drops from FOREGROUND to IDLE about three
seconds after every `Lifecycle: onPause`. IDLE is how Gecko ranks a process whose pages are all inactive.

The log showed one hole in that, though. When the low-memory killer took the page at 15:49 while the kiosk was in the
background, the kiosk restarted the session at once ("Content process killed; restarting session"). The new page
process stayed at FOREGROUND for 80 minutes, until the kiosk next came to the front. GeckoView's `setSession` only
makes a session active when the view has a surface; without one it leaves the session as it is, and a new session
starts active. So a page opened in the background counts as shown until the kiosk comes back.

That is a real defect, and its fix is one line. It doesn't explain the growth, though. The page that the low-memory
killer took at 15:49 (1.27 GB freed) had been at IDLE, so correctly hidden, for the 74 minutes before, with no visit
from the probe in that time. Home Assistant's frontend closes its connection after five minutes hidden, so in that
window it should have been quiet. The growth happens in a hidden page too, and its mechanism is still open.

The probe adds a second effect. It brought the kiosk to the front for a few seconds every few minutes during
14:24–14:35 and 17:08–17:40. Home Assistant's five-minute timer starts again with each `visibilitychange`, so with
visits less than five minutes apart it never closes the connection. That is a test artifact: a wall panel doesn't
flick between apps.

## What we tried

1. **The S1's logcat, process by process** (2026-11-24, read)
   ```
   $ grep -E "tab(18|33|11) updateBindings|Lifecycle: on|Kill 'sh.edm|Content process" logcat-full.raw
   14:24:16  tab33 starts (FOREGROUND); 14:24:19 IDLE; visits until 14:35:10 onStart / 14:35:10 onPause
   14:35:13  tab33 IDLE                          (no lifecycle event until 17:08:51: the kiosk stays behind)
   15:49:24  lowmemorykiller: Kill '…:sandboxed_tab…33', oom_adj 900 to free 1265440kB
   15:49:25  E/Glassworks: Content process killed; restarting session
   15:49:26  tab18 updateBindings: FOREGROUND priority      (kiosk still behind the other app)
   17:09:05  tab18 updateBindings: IDLE priority            (first surface cycle: onStart 17:08:51, onPause 17:09:02)
   17:38:43  lowmemorykiller: Kill '…:sandboxed_tab…18', oom_adj 900 to free 1231532kB
   17:38:45  tab11 FOREGROUND (restarted behind the other app again); 17:40:41 IDLE after a visit
   ```
   Rules out the idea that GeckoView never hides a backgrounded page. Shows the reopened-in-background page staying
   shown (tab18, 80 min) and a page growing while hidden (tab33).

2. **GeckoView 159 and 144, `GeckoView$Display`** (2026-11-24, read with `javap -c`)
   - `acquire(GeckoDisplay)`: returns at once unless `mValid` (a surface); otherwise ends with `setActive(true)`.
   - `onSurfaceChanged`: `setActive(true)`. `onSurfaceDestroyed` and `release()`: `setActive(false)`.
   - `GeckoSession.setActive(false)` also posts a memory-pressure notice 10 s later (`mNotifyMemoryPressure`).
   So a session that the app opens and hands to a view with no surface keeps its default (active). Both engines
   share `GeckoEngine.kt`, and 144 behaves the same.

3. **Home Assistant frontend, hidden-page handling** (2026-11-24, read)
   `home-assistant.ts` `_onHidden()`: on `visibilitychange` to hidden it holds reconnects and, unless
   `suspendWhenHidden === false`, arms a 300 000 ms timer that calls `window.stop()` and `connection.suspend()`.
   `_onVisible()` clears that timer. `connection-mixin.ts` starts `suspendWhenHidden: true`. The listener is added in
   `hassConnected()` and nothing checks the state at start, so a page that loads already hidden is never suspended.

4. **The panel's dashboard** (2026-11-24, read through Home Assistant's API, read-only)
   One view with a single custom card (an inline Lovelace resource). It rebuilds its whole content with `innerHTML`
   whenever an entity it shows changes, when the minute changes, and every 20 s (`setInterval`). Those timers don't
   stop when the page is hidden. Its camera is a still picture in a sheet that opens on a tap, not a stream.
   So the "camera stream left running" hypothesis doesn't fit this dashboard. A page rebuilding itself every 20 s
   while hidden does, but that is untested.

5. **The kiosk's own page script and pushes** (2026-11-24, read)
   `content.js` runs `apply()` every 1 s, reads the theme every 2 s, and asks for the dashboard list every 10 min.
   The app pushes `state` only when presence, lux (more than 20 %) or the screen change. None of it keeps anything,
   so nothing here grows.

6. **A reproduction on an emulator** (2026-11-24, not run)
   The plan was the generic app (same engine) on an Android 10 arm64 emulator, against Clarity's fake Home
   Assistant with its live stream, sampled with `tools/perf/debug/tab-memory.sh`: in front, behind another app,
   behind with visits every few minutes, and restarted in the background. The emulator never finished booting: the
   build machine was at a load average of 60, then 700–960, from other work. It was stopped rather than add load.

7. **Parts D and E on the S1** (2026-11-24 21:54–22:11 local, verified)
   The S1 has no Settings app (`cmd package resolve-activity -a android.settings.SETTINGS`: "No activity found"), so
   the first try at D woke the screen, `am start` did nothing, and the page was killed with the kiosk in front (it went
   IDLE when the screen slept again, as it should). The S1's background state is the screen off: the kiosk is stopped
   and nothing is resumed. D2 and E ran there. `adb shell` is uid 2000 on this panel; the kill is Magisk's
   `su -c 'kill -9 <pid>'`, which is no force-stop.
   ```
   D2, 0.9.0 release (sha256 41e416d3…), screen asleep, nothing resumed
   21:59:42.536  E Glassworks: Content process killed; restarting session
   21:59:42.674  tab16 updateBindings: FOREGROUND priority, 2 importance, 23654 pid     (the new page, in Gecko's spare process)
   22:02:42      still FOREGROUND; the page has loaded the dashboard: 186,738K PSS
   E, test build of this change (0.9.0 · 55876de, sha256 fe73c094…), same state
   22:05:23.226  E Glassworks: Content process killed; restarting session
   22:05:23.336  tab3 updateBindings: FOREGROUND priority, 2 importance, 5625 pid       (the new page)
   22:05:26.342  tab3 updateBindings: IDLE priority, 6 importance, 5625 pid            (3 s later)
   22:08:22      still IDLE; the page has loaded the dashboard: 202,608K PSS
   ```
   `dumpsys activity processes | grep 'Process LRU list'` (the #248 detector) read non-svc at 4–8 of 24–25 throughout;
   the two page kills didn't move it, and each install's two force-stops (`installPackageLI`, `pkg removed`) didn't
   walk it down either. Restored to the 0.9.0 release: same hash and radio mode as before, screen asleep, dashboard
   loaded again.

## Result

| Claim | Evidence | How sure |
|---|---|---|
| GeckoView hides the page when the kiosk goes behind another app | `GeckoView$Display.onSurfaceDestroyed`; the S1's FOREGROUND → IDLE ~3 s after each `onPause` | read, and seen in the S1's log |
| A page reopened while the kiosk is in the background stays shown until the kiosk returns | `acquire` without a surface; tab18 FOREGROUND for 80 min behind the other app; part D2 | verified on the S1 (0.9.0) |
| With this change it is hidden at once | part E: IDLE 3 s after the restart, and stays IDLE | verified on the S1 (test build) |
| The page grows while correctly hidden | tab33 IDLE for 74 min with no visit, then 1.27 GB freed | seen in the S1's log; when in those 74 min it grew is unknown |
| Brief visits every few minutes stop Home Assistant from closing its connection | `_onVisible` clears the 5-min timer | read |
| The kiosk's own script and pushes don't hold memory | `content.js`, `MainActivity.stateJson` | read |
| A camera stream is involved | the dashboard shows a still, not a stream | ruled out for this dashboard (read) |

**The fix in this change.** `GeckoEngine` makes every new session inactive before it hands it to the view
(`s.setActive(false)` before `view.setSession(s)`). With a surface, GeckoView's `acquire` makes it active again at
once, so nothing changes in front. Without one, the page stays hidden until the kiosk comes back, and Gecko ranks
its process low. `GeckoBehaviourPinTest` pins the order for the first session, a restart, and a restart behind
another app.

## How to reproduce

On a panel: the plan below, with `tools/perf/debug/tab-memory.sh` (read-only, or with visits: `am start` only).
Off the panel: the emulator steps in attempt 6, on a computer with room for an emulator.

### Panel plan (S1, for whoever drives the panel)

Each part runs about 30 minutes. `$ADB` is `adb -s $(cat /tmp/s1-serial)`. Before you start, write down the
installed version (`$ADB shell dumpsys package sh.edm.s1kiosk | grep version`) and the radio mode, and make sure
nothing else drives the panel. The kiosk stays on its own dashboard throughout: no Clarity, no test dashboard.

| Part | Build | What | Answers |
|---|---|---|---|
| A | 0.9.0 | Kiosk in front, screen held on (`am start -n sh.edm.s1kiosk/.MainActivity --ez test_hold true --ei test_hold_s 1800`), `ADB="$ADB" tools/perf/debug/tab-memory.sh a.txt 30` | Does the page grow in normal use? |
| B | 0.9.0 | Let the screen sleep (the S1 has no Settings app to put in front; with the screen off the kiosk is stopped), then `tab-memory.sh b.txt 30` | Does a hidden page grow once Home Assistant has closed its connection (after 5 min)? |
| C | 0.9.0 | As B, with visits: `tab-memory.sh c.txt 30 4` (on the S1, set `BEHIND` to another app's `am start` arguments, as Settings isn't there) | The 2026-11-24 condition: does growth need the open connection? |
| D | 0.9.0 | As B, then kill the page: find its pid (the biggest `sandboxed_tab` in `$ADB shell dumpsys meminfo`) and `$ADB shell "su -c 'kill -9 <pid>'"`. Then `$ADB logcat -d -s ServiceAllocator Glassworks` | The reopened page's priority: FOREGROUND until a visit (0.9.0). **Done, attempt 7** |
| E | test build | D again | IDLE within a few seconds of the restart (this fix). **Done, attempt 7** |
| F | 0.9.0 | If A or B grows: turn on remote debugging (`am start -n sh.edm.s1kiosk/.MainActivity --ez remote_debug true`, which restarts the app), connect desktop Firefox's `about:debugging` over USB, and save a heap snapshot in the Memory tool at minute 5 and minute 20 of a growing part | Which objects grow: DOM from the card's 20 s rebuild, Home Assistant's state, or Gecko itself |

The test build: push the PR's head to `test-build/s1-tab-memory` with the workflow's `version` input `0.9.0`, so its
versionCode (900) matches the panel's and both the install and the restore are `adb install -r`.

**Restore:** `am start -n sh.edm.s1kiosk/.MainActivity --ez test_hold false` (and `--ez remote_debug false` after F); `adb install -r` the 0.9.0 release APK after E; check the version and radio mode against
what you wrote down. Nothing here changes a setting or the sign-in.

## Open questions

- Where the growth is (part F's snapshots). The likeliest lead is the dashboard's card rebuilding its whole DOM every
  20 s whether or not anyone can see it, in a page Gecko no longer paints. If so, the card is the place to fix it
  (stop its timers on `visibilitychange`), and the same page would grow on any browser that hides it.
- Whether it grows in front at all (part A). If not, normal use on a wall panel is safe, and this only bites when
  something else takes the screen for a long time.
- A kiosk-side guard, if part B grows: after the page has been hidden for, say, 15 minutes, load `about:blank` and
  load the dashboard again when the kiosk comes back. That costs a full frontend load (up to 45 s on the S1) on
  return, so it's a product call, not part of this change.
- Home Assistant never suspends a page that loads already hidden (attempt 3). That is upstream's, and it matters
  only after a restart in the background, which this change makes rarer to matter.
