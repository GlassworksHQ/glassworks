import { CHECK_TEXT, COUNTED, WORD_MEANING, WORD_TEXT, type CheckItem, type Completeness } from "@glassworks/frame-card";
import s from "./Completeness.module.css";

/*
 * How complete Glassworks is on a frame (issue 423): the word, and the checklist under it. Worked out at build
 * time from the frame's facts by @glassworks/frame-card's completeness(). Ticks are status, so they use the
 * status colours, as Clarity's levels do.
 */

const MARK = { yes: "✓", no: "✗", na: "n/a" } as const;
const SAID = { yes: "Ticked", no: "Not yet", na: "Doesn't apply" } as const;
const TONE = { yes: s.ok, no: s.mid, na: s.none } as const;

/** "Install, silent updates, hardware, kiosk and setup", from the items that count */
const counted = COUNTED.map((k, i) => (i ? CHECK_TEXT[k].toLowerCase() : CHECK_TEXT[k]));
const DECIDES = `${counted.slice(0, -1).join(", ")} and ${counted[counted.length - 1]} decide the word.`;

function Items({ title, items, flag }: { title: string; items: CheckItem[]; flag: boolean }) {
  return (
    <div className={s.group}>
      <h3 className={s.groupHead}>{title}</h3>
      <ul className={s.items}>
        {items.map((i) => (
          <li key={i.key} className={`${s.item} ${TONE[i.state]}`}>
            <span className={s.mark} aria-hidden="true">
              {MARK[i.state]}
            </span>
            <span className={s.name}>
              {i.label}
              <span className="visually-hidden">: {SAID[i.state]}.</span>
            </span>
            <span className={s.reason}>
              {i.reason}
              {flag && i.unverified && <span className={s.prov}>Unverified</span>}
              {i.note && <span className={s.note}>{i.note}</span>}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** The frame page's checklist panel. */
export function CompletenessPanel({ c }: { c: Completeness }) {
  const w = c.word ?? "none";
  // every item unverified: one line in the head says so instead of a pill on each row
  const allUnverified = c.items.every((i) => i.unverified);
  return (
    <section className={s.section} aria-labelledby="completeness">
      <div className={`glass ${s.panel}`}>
        <div className={s.head}>
          <span id="completeness" className={s.label}>
            How complete
          </span>
          <span className={`${s.word} ${c.word ? "" : s.wordOff}`}>
            {WORD_TEXT[w]}
            {c.unverified && <span className={s.prov}>Unverified</span>}
          </span>
          <span className={s.meaning}>
            {WORD_MEANING[w]} {DECIDES}
            {c.noRun && " These are the values we expect: nothing here has run on a real unit yet."}
            {allUnverified && !c.noRun && " Every value here is unverified."}
          </span>
        </div>
        <div className={s.body}>
          <Items title="Decides the word" items={c.items.filter((i) => i.counts)} flag={!allUnverified} />
          <Items title="Shown, doesn't count" items={c.items.filter((i) => !i.counts)} flag={!allUnverified} />
        </div>
      </div>
    </section>
  );
}
