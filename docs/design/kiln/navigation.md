# Moving through Kiln

**Status: draft, 2026-11-25, built on a branch for the maintainer's review.** It covers every flow:
the image over fastboot, the in-place install, the app over adb, the Android USB flow, the S1's
wizard and the standard set-up after any install.

## The problem

Kiln is a wizard: a rail of steps on the left, one card per screen, the main action in the card's
foot. It remembers where an install got to (the `ProgressMark` in the tab's storage) and resumes
there after a reload. That protects the device, but it trapped people:

- A reload dropped you mid-flow, on a screen you hadn't seen, with nothing saying why you were there
  or what had already changed.
- Going back was a different thing on every screen: a sky "Back" link in some feet, "Want to begin
  again? Start over" as a sentence with a link in others, nothing at all in the rest. Set-up's only
  way back was the rail's links, and the rail is hidden on a phone.
- Start over opened an inline well in the card, worded like a question, next to the button that ran
  it. It didn't say what stayed.
- The rail showed steps as done, but you couldn't use it to go to them, and it never said why a step
  was behind you for good.
- On a phone the stack took the top half of the screen, so the main button was often below the fold.

## The model

Three ideas, used the same way on every flow.

### 1. The rail is the map, and the one source of "back"

Each flow builds its rail as it always has, and now also says, for each step behind you, either how
to return to it (`go`) or why you can't (`lock`). A done step you can return to looks and acts like a
link. Behind a write, the rail says once, in one note under it, what changed and that the steps before
are done for good (round two, [system.md](system.md): no lock on each step).

**Back is the rail's previous step you can return to.** The shell works it out from the rail, so no
screen decides it alone, and two screens can't disagree. A screen may still give its own Back when
"back" means something inside the step (the unverified bundle's Back goes to the receive screen).

| Where you are | Back goes to | Locked because |
|---|---|---|
| The first screen of a frame | The device picker | |
| An image's screen, its download, fastboot, the warning | The step before, as the old links did | |
| Anything after the first write | | The device has changed: Kiln names the change |
| Set-up, past its first step | The set-up step before | |
| While anything runs (`busy`, a write, a connection, a transfer) | Nothing | Nothing is offered while it works |

### 2. One foot, the same on every screen

```
[‹ Back]   [secondary]  [secondary]             [Primary]
```

Back sits on the left, the one ember button on the right, and anything else is a ghost button
between them. No prose links in the foot. When there is no way back, the rail's note says why; on a
phone, where the rail is hidden, the left of the foot says it, with a lock: "Glassworks is written to
the Mirror". On a phone the foot sticks to the bottom of the screen, Back and the main button side by
side, so the main action is always in reach.

### 3. Leaving the path is a decision, so it gets a dialog

**Start over** and **Restore the original system** are flow actions, not screen actions. They live
under the rail on a computer; on a phone, in the Steps sheet and as a quiet button under the card;
and on the resume and error screens. Start over opens a dialog (Radix Dialog, as the photo and "Your
turn" already use) that says what starts again and what stays, with a confirm button in solid ink
(round two: not rose, which means a problem, and not ember, which invites). It is offered only where
`canStartOver()` says it's honest, and never while something writes.

## Resume

When Kiln opens on a saved mark, it shows a resume screen first instead of dropping you on a step:

- **Pick up where you left off**, with the device's name.
- What's already done, as the rail's done steps on the same progress list as the working screens, the
  step you'll continue at marked as your turn, and one note on what the device has now ("The setup
  helper is on the Mirror. Its system hasn't changed yet.").
- **Continue** (ember), and the ways out as ghost buttons: Start over when it's still honest, and
  Restore the original system once the system has been written.

Continue goes to the step the mark points at, exactly as the resume did before. Nothing about the
mark or what it resumes changes.

## On a phone

The rail becomes a slim bar at the top: a small stack, "Step 5 of 8", the step's name, and a
**Steps** button. Steps opens a sheet with the whole rail (the same links and locks) and the flow
actions. The large stack above the card goes, so the heading and the main button sit in the first
screen. Back is in the sticky foot.

## Safety

Nothing here changes what the sessions allow. The flows ask the sessions they already have:
`busy()`, `canStartOver()`, `writing()` and `running()`. A step gets `go` only when the session is
idle and the step before is one the old screens already offered (Back, Back to download, Back to the
choice). The leave guard still holds during writes, and the picker still leads back to a running
install.

## What changed for the person

| Before | After |
|---|---|
| A reload lands mid-flow | A resume screen: where you stopped, what's done, Continue or a way out |
| Sky "Back" links, some screens only | A Back button in the same place on every screen it's safe |
| "Want to begin again? Start over" and an inline well | A Start over button under the rail, and a dialog that says what resets and what stays |
| Done steps on the rail are plain text | Done steps you can return to are links; locked ones say why |
| Phone: big stack, the main button below the fold, no way back in set-up | Phone: a slim step bar with a Steps sheet, a sticky foot with Back and the main button |
| The Mirror's choice cards open with what you get | They open with what happens to the Mirror, and the project link sits outside the radio button |

## Demos

| Demo | Shows |
|---|---|
| `/lululemon-mirror?demo=place-resume` | The resume screen of an in-place install with the bundle in |
| `/lululemon-mirror?demo=place-resume-staged` | The resume screen with only the setup agent on the device |
| `/lululemon-mirror?demo=place-restore-point` | The rail's Start over; open it for the dialog |
| `/lululemon-mirror?demo=flash-confirm` | Back on a mid-flow image step, with the rail's links |
| `/lululemon-mirror?demo=flash-adb` | The lock after the write, and the ghost "Write the image again" |
| `/aqara-s1-plus?demo=connect` | The S1's Connect: Back to the picker |
| `/android?demo=debug` | The Android flow's Back |
