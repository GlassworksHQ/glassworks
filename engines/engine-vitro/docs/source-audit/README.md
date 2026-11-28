# Source audits

Vitro's native cards are ports of the cards they draw, not copies of what those cards look like. Each audit here reads
one card family's upstream source at the version Vitro pins, lists every branch the card takes on its config and its
entity's state, and records where Vitro implements each one. The tables are the migration checklist when upstream
changes, and each arm names the parity case or unit test that checks it.

| Family | Upstream, pinned | Audit |
|---|---|---|
| HA's `tile` card, its parts and its features | home-assistant/frontend `20261028.5` (diffed against `20261125.1`, the 2026.12 beta) | [tile.md](tile.md) |

## How to read a table

- **Arm**: one branch of the source: a config option, a state, or a CSS rule that applies only in some cases. The
  source column names the file and the function or selector.
- **Vitro**: the Kotlin that implements the arm, or **missing**.
- **Before** and **after**: whether Vitro matched the arm before this audit's port and after it. **match** means the
  same pixels, text and behavior; **routed** means Vitro doesn't claim the arm, and `native-options.json` sends the card
  to Home Assistant's frontend (a built-in card) or to its island (a custom card), so the panel shows upstream's own
  drawing.
- **Checked by**: the parity case (`engines/engine-vitro/src/test/parity/corpus/<case>.json`) that compares the arm
  with upstream's render, or the unit test for an arm that acts rather than draws (a tap, an animation, a label).
  Parity cases stop CSS animations at their start, so animated arms are checked by unit tests.

## When upstream moves

1. Diff the pinned tag against the new one for the files each audit lists under **Sources**.
2. Every changed line maps to an arm in the table, or is a new arm. Port it in the same branch order, with the same
   defaults and CSS values, and add its row.
3. A new option or value that Vitro doesn't draw yet goes in `native-options.json` as unsupported first, so the card
   falls back until the port lands.
