---
topic: boot
evidence: verified
---

# <Topic>: <one-line question this note answers>

**Status (YYYY-MM-DD): working | partly working | dead end | untested.** One or two sentences: what
works now and what doesn't.

Device: <model>, firmware <version>. Tools: <names and versions>.

## Question

What we wanted to know or make work, and why it matters for the project.

## How we got here

Walk through the investigation and thought process:
- What constraints made the problem hard or looked like a dead end at first.
- Which hypotheses or approaches we considered, and how we decided to test or rule them out.
- The key insight(s) or chain of reasoning that led to the solution (or why we stopped).

## What we tried

For each attempt, in order, including the ones that failed:

1. **<attempt>** (YYYY-MM-DD, verified | read | untested)
   ```
   $ exact command
   output, redacted
   ```
   What it showed, and what it rules out.

## Result

The answer, in a sentence or a table. Put each claim next to its evidence: a command above, or a
source file and line, or a binary and offset.

## How to reproduce

The steps from a known state, with the scripts in `tools/` they use. Say what each step touches:
RAM only, read-only, or writes storage. Say how to undo it.

## Open questions

What is still unknown, and what the next test would be.

<!--
Before committing:
- No serials, MACs, EUI64s, keys, PAN IDs, tokens or Wi-Fi details. Redact them in logs.
- No vendor binaries or firmware, only names, versions, offsets and what you read there.
- Front matter: topic is one of hardware, boot, access, install, android, display, radio, panes,
  perf, method. evidence is the strongest level the note's answer reaches: verified (ran it),
  read (from source or a binary) or untested. The site groups and labels notes by them
  (site/src/lib/docs/notes.ts) and takes the date from the Status line.
- Where it goes: frame-specific notes go in frames/<id>/research/, general ones in docs/research/.
- Add this note to the table in docs/research/README.md, and a frame's note to frames/<id>/README.md.
-->
