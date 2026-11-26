# Kiln's screens: principles and components

**Status: draft, 2026-11-25, round two of the redesign, built on a branch for the maintainer's review.** It builds
on [navigation.md](navigation.md) (round one: the rail as the map, Back, Start over, resume) and covers every flow:
the picker, the S1 wizard, the Mirror's image and in-place installs, the way back, the Android flow, set-up and
errors.

Kiln is how people meet Glassworks: they plug a wall panel or a mirror that cost real money into a browser tab.
Every screen should feel like an official, rock-solid hardware utility: calm, obvious and honest about time.

## What people hit

A real Mirror install, on the in-place flow, from the maintainer:

- The working screen ("Keep it plugged in") listed steps with a small arrow and dots. Mid-install they couldn't tell
  which steps were done and which one was running, and believed Kiln had gone backwards.
- Long waits (a 4–5 minute first start, a 5 minute Bluetooth transfer) felt stuck. Nothing said how long they take,
  or that a dark screen is normal.
- Step names were plumbing: "Build and check the changes from the bundle", "Write fresh storage".

Walking every `?demo=` state of round one (the screenshots in the branch's report) added these:

| # | Where | What felt wrong |
|---|---|---|
| 1 | Working screens | The current step was marked only by a red arrow in the brand's ember, which reads as an alarm next to rose. Done and next differed only by shade. A step's percent sat on a line of its own |
| 2 | Working screens | The list started at the step running and hid what was left; the bar reset between the two fastboot sessions and again on the first start's screen, which showed no list at all |
| 3 | Waits | An endless bar and "Waiting for the Mirror to finish starting…": no time, no clock, no word that it's normal. With reduced motion the bar froze at 30% and looked stuck |
| 4 | Transfers | "18% · 0.0 MB / 0.0 MB · 1 KB/s · ~9s left" in one grey line: the numbers that matter (how far, how long) were no bigger than the rest |
| 5 | Copy | "Bundle" on nine screens, "setup agent", "SHA-256", "Fastboot by hand", "Restart over USB". Headings that narrate Kiln ("Kiln can't confirm this bundle") |
| 6 | Instructions | The second fastboot was one 50-word paragraph. The first was a faint dotted list under a drawing taller than the screen, so the steps and the Connect button were below the fold |
| 7 | Browser prompts | "Then click Connect and pick the Mirror, listed as “Android”, in the browser's list" as small grey text after the steps, easy to miss before the list opens |
| 8 | Hierarchy | Four sizes of grey text per card (lede, small, well, status); small notes in `--faint`, which fails WCAG AA (about 3.5:1) on both themes |
| 9 | The way back | Five locks on the rail, a note under it, and the same note again in the foot: three ways of saying one thing |
| 10 | Errors | The raw error in red monospace as the main content; each flow laid its error out differently; only the S1 said whether anything was written |
| 11 | The restore point | Its SHA-256 on screen; its download a ghost button beside a paragraph, as easy to skip as any other |
| 12 | Start over | The confirm in rose, the brand's color for a problem, on a choice that isn't one |
| 13 | Phones | Start over only inside the Steps sheet; a gap under the sticky foot; long ember labels wrapping to two lines |
| 14 | Focus | After Continue, the button was gone and focus fell to the page: a keyboard or screen reader started from the top |
| 15 | A bug | The in-place install's first fastboot screen said the setup helper was already on the Mirror. It locked the rail and hid Back before anything was written |
| 16 | Demos | No demo for the in-place install's first screen, first fastboot, warning, Connect, first start, write error, or the way back's stages |

## Principles

1. **Every screen answers three questions:** where am I, what's happening, and what do I do now. The answer to the
   third is usually "nothing": say so.
2. **Progress never goes backwards and never hides what's next.** One list from the first write to the end, every
   step on it from the start, the person's own clicks among them.
3. **Every wait says how long, up front, and stays alive.** How long it usually takes, a clock of how long it has
   taken, and that it's normal. Past the usual time, calmly, never as an error.
4. **Say what happens to the device, never how Kiln does it.** "Write the new system", not "flash system.img".
   No bundle, agent, sparse, SHA-256 or Refract on a screen; the technical log keeps them.
5. **One action per screen, and ember is that action.** Ember is never decoration, a progress bar, or "now".
6. **Warn before the browser asks.** Before any device list opens, show what it will look like and which line to pick.
7. **Physical steps are numbered, illustrated and tickable.** One action per step, the action in bold, the drawing
   beside the steps so both fit the first screen.
8. **An error says the device's state first.** Then what to do, then the action. The raw error is one click away.
9. **Safety is visible.** The restore point and its download are the screen's main action until the copy is saved.
   Nothing that writes offers Back, Start over or leaving.
10. **Color means one thing each.** Ink is progress and "now", ember the one action, sky a link, and green, amber
    and rose are status only.

## The components

All in `kiln/apps/kiln/src/components/Kit.tsx`, styled in `globals.css` (the kit section), tested in
`test/kit.test.tsx`. Nothing in them names a frame: the words come from the frame's data.

### Progress

```
Step 2 of 7                                   68% · under a minute
━━━━━━━━━━━━━━━━━━━━━━━━━━━──────────────────────────────────────
 ✓  Prepare the changes and check them                      Done
 ◌  Write the new system                                     50%
 3  Start its app storage fresh                       1–2 minutes
 4  Restart the Mirror
 ●  Connect to the Mirror                               Your turn
 6  Let it finish its first start                     4–5 minutes
 7  Install Glassworks and hide the original apps
```

- Each row has a mark (a check, a spinning ring, a number, an amber dot for the person's turn, `!` when stopped) and
  a word on the right (Done, Now or its percent, Your turn, Stopped, or how long it usually takes).
- The running step carries one note: what's happening, or that a pause is normal.
- A line joins the marks, solid behind what's done.
- The bar above is the whole, in ink; it turns rose only when a step stopped.
- `useForward`: while one screen shows, the list only moves forward. A step reported late never pulls it back.
- One polite live region says "Step 2 of 7: Write the new system. Now." when the step changes, not on every percent.
  A screen with its own announcement turns it off (`live={false}`).
- Used by: every working screen (the Mirror's image and in-place installs, the S1's firing, Android's install, the
  app's install in set-up, joining Wi-Fi, the restore point being saved, the way back), the first start's Connect,
  and the resume screen (what's done, and the step you'll continue at).

The image flow builds its list in `installRows()` (ImageScreens): every planned step from the first write on, with
the person's Connect put in before the first start. The working screen and the Connect screen show the same list,
so the click and the first start are steps on it, not new screens with no history.

### Wait

A long wait: a ring that turns, what is happening, "Usually 4–5 minutes", a clock ("1:23 so far"), and a note on what
the person may see ("The screen stays dark until it's ready. That's normal."). Past 1.25 × the usual time it says
"This is taking longer than usual. That can still be normal, and Kiln keeps checking", and its edge turns amber.
With reduced motion the ring stands still; the clock still counts, which is the life that matters.

Used for the first start, the device restarting into its setup helper, its Wi-Fi appearing, and set-up's "Starting
the panel".

### Hands

"Nothing to do but wait. Keep the Mirror plugged in and this tab open until it's done." The same words in the same
place on every screen that writes, so the person never wonders whether they should be doing something. A screen can
change the second line (Bluetooth: "keep this computer near the Mirror").

### Note

The one callout: an icon, a short bold line, a sentence, and at most one action. Its tone is status only:

| Tone | Means | Example |
|---|---|---|
| `info` | A fact the person should know | "What changes", "The way back" |
| `ok` | Safe, done, checked | "Found the Mirror in fastboot mode", "Saved in this browser", "Nothing was written." |
| `turn` | Needs the person's attention or decision | "Keep the back cover off", "Before you go on" |
| `bad` | Something is wrong | A refused check, a connection error |

### Instructions

A physical procedure: one numbered step per line of the frame's words, each number a button that ticks the step off
(a mark for the person only; Kiln acts on nothing). The next step to do is highlighted. The frame's lines start with
the action in bold (`**Switch the Mirror off** with the rocker switch…`). On a computer the drawing (the recovery
player) sits beside the steps, so both fit the first screen; on a phone it sits above them. Used by the Mirror's
fastboot screens and the S1's Connect (its `words.recoverySteps`); a frame without steps shows the drawing alone.

### BrowserAsk

Before Kiln opens the browser's own device list: a small drawing of that list with the line to pick highlighted, and
"Your browser asks next. It shows a list of devices. Pick `Android`, which is the Mirror, then click Connect." The
line comes from the frame's `listed` (or the S1's `felPick`). Shown on the fastboot screens, the first start's
Connect, the way back's fastboot, the S1's Connect and before a Bluetooth pick.

### Problem

Every error, laid out the same way: a rose `!`, what happened as the heading, **the device's state first** (a Note:
"The Mirror's system hasn't changed.", "Your restore point is saved in this browser."), "What to do", the actions,
then **Details** (the raw error and the log) closed. The S1's error keeps its own "How to fix it" group and help, on
the same order.

### The restore point's copy

A card of its own, outlined in ink: "Keep a copy on this computer too", with Download as the screen's ember button.
**Continue waits for the copy** (maintainer, round two): until it's downloaded, Continue is off and one line beside it
says why ("Continue opens once a copy is on this computer."), tied to the button with `aria-describedby`. Once it's
downloaded the card turns green ("A copy is on this computer") and Continue becomes the ember button. The session
holds the same rule (`continueInstall` does nothing without the copy), so no other path writes past it.

A download is recorded with the restore point in this browser (the in-place store's `markCopied`, keyed by the
restore image's SHA-256). A reload saves the restore point again; if this browser already has a copy recorded for that
same restore point, Continue opens at once. A new restore point (another bundle) asks again. The fingerprint stays,
small; the word SHA-256 goes.

### The road

The in-place install's first screen lists everything that will happen, read from the recipe's own steps
(`inPlaceRoad`), the person's parts marked "You", the long ones with their time, and the total in the heading
("What happens · 20–30 minutes"). Nothing is a surprise later.

### Time on the choice

Each card on "Choose what to install" says how long that install usually takes ("Usually 20–30 minutes", with a
clock: Kit's `Usually`), read from the frame's `time.total.<recipe id>` word and read out with the card's summary. The
S1 has one install and no choice, so its Connect says it under the lede, in the same line ("Usually about 10
minutes"), from its `words.time.total`; the picker's What you'll need says the same words. A recipe whose frame
gives no total shows no time: the Mirror's full image has none yet, since its download and system write were never
timed, and a sum of the parts that were would be a guess.

## The visual system

| | Rule |
|---|---|
| Color | Only `@glassworks/brand` tokens (`test/no-hex.test.ts`). `--ink` (the foreground) for progress, "now" and Start over's confirm. Ember for the one primary button. Sky for links and the browser-list drawing. Green, amber, rose for status only |
| Type | One heading size (30px, 25px on a phone, weight 400). Lede 16px `--muted`. Small text 13.5px `--muted`, never `--faint`, which stays for marks and dividers |
| Space | A 4/8/12/16/24/32 scale (`--s-1`…`--s-6`). Cards 30×32px padding, 18px between blocks |
| Shape | Cards `--r-tile`; notes, waits and lists 14–16px; marks round |
| Motion | Only what says "alive": the running step's ring, the wait's ring and breathing dot, the turn's ping. With reduced motion they stop, the clock keeps counting, and an endless bar shows full and faint |
| Focus | A new screen under a click moves focus to its heading (`tabindex=-1`), so the keyboard and a screen reader start there. Not on the first load, and not while the person is in the rail or a dialog |

## Round one's open questions, decided

| Question | Decision |
|---|---|
| Start over's confirm is rose | **Solid ink** (`.btn.strong`). Rose means a problem and ember invites; ink says "this is a deliberate choice" without alarm |
| Five locks on the rail are noisy | **One note under the rail**: "The Mirror's system is changed, so the steps before this one are done for good." No lock per step. The foot says why there's no Back only on a phone, where the rail is hidden |
| Start over on phones hides in the Steps sheet | **Also under the card** on a phone, as a quiet text button (`.flow-under`), and still in the sheet |

## Round two's open questions, decided

| Question | Decision |
|---|---|
| Timing words | **Kept**: "20–30 minutes" for the whole in-place install, "about 10 minutes" for fastboot. The choice shows each install's total too, from the frame's words |
| "Setup helper" for the small app Kiln adds | **Kept** |
| Ink for a selection | **Kept** |
| Should Continue wait for the restore point's copy? | **Yes.** See [the restore point's copy](#the-restore-points-copy) |
| The rail's "Receive" | **"Copy"**, the step's name wherever a person sees it. The code keeps `receive` |
| The S1's times and recovery steps (maintainer: "give the S1 the same data, 10 minute install as well") | **From its data**, read generically (`FrameWords` `time`, `recoverySteps`): "Usually about 10 minutes" on Connect, the numbered steps beside the drawing, 1–2 minutes on the restart and the first start. The picker's time line comes from the same word, so the two can't disagree |

## What a frame gives

The screens read these from `frames/<id>/design/installer.json` `device.words` (a device frame, like the Mirror), or
`words` (a frame on the wizard, like the S1), where a frame has them; without them the screens say less, never
something wrong:

| Word | Used for |
|---|---|
| `time.fastboot`, `time.agent`, `time.flash-userdata`, `time.first-start` | How long each part usually takes: the road, the rows, the waits |
| `time.total.<recipe id>` | How long a whole install usually takes: its card on the choice, and the road's heading |
| `fastboot.lede` | One line under the fastboot heading on why and how long |
| `firstStart.note` | What the person sees during the first start |
| `fastboot.byHandSteps`, `inPlace.fastboot.again` | One step per line, the action in `**bold**` |
| `words.time.total` (wizard) | How long the install usually takes: Connect's "Usually …" line and the picker's What you'll need |
| `words.time.steps.<step id>` (wizard) | How long a step of the install flow usually takes. `first-boot`: the Restart row and set-up's first-start Wait |
| `words.recoverySteps` (wizard) | Connect's numbered steps beside the drawing, one per item, the action in `**bold**` |

A time is only what the frame's docs or measurements say. The S1's come from its README and `docs/install.md`: the
whole install about 10 minutes, the first boot 1–2 minutes. Its FEL boot, backup, patch and write were never timed
as steps, so their rows show no time (the bar's estimate still counts down once it has enough to go on).

## Demos

Every state above has a demo at 1280×800 and 390 wide. On `/aqara-s1-plus`, `?demo=connect` shows the time and the
numbered steps, `?demo=install` the restart's time, and `?demo=first-boot` (new) the first start's Wait. New in round
two, on `/lululemon-mirror`:

| Demo | Shows |
|---|---|
| `?demo=place-start` | The in-place first screen with the road |
| `?demo=place-fastboot` | The first fastboot: the drawing beside the steps, then the browser's list |
| `?demo=place-confirm` | The checks passed, add the setup helper |
| `?demo=place-restore-point` | The restore point saved; Continue waits for the copy |
| `?demo=place-restore-offered` | The copy offered as a download; Continue waits for the person to confirm it's saved |
| `?demo=place-restore-copied` | The copy confirmed saved; Continue is the ember button |
| `?demo=place-connect` | The first start's Connect, with the whole list |
| `?demo=place-first-start` | Picked, waiting for the first start: the Wait and the list |
| `?demo=place-installing` | The first start on the working screen |
| `?demo=place-error-write` | A write that stopped, with the restore point saved |
| `?demo=place-undo-fastboot`, `-undo-working`, `-undo-error`, `-undo-done` | The way back's stages |

## Not done here

- The picker, the choice cards and set-up's forms keep their layout; they take the new type, color and notes.
- Times for the S1's FEL boot, backup, patch and write: nothing in its docs or research times them as steps. A panel
  run with timestamps would give them.
- Times for Android's steps: its frame doesn't give `time.*` words yet.
