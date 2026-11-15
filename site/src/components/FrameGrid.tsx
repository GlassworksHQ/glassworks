import type { ReactNode } from "react";
import type { FrameDesign } from "@glassworks/frame-card";
import { FrameCard } from "./FrameCard";
import { Chip } from "./Manage";
import { CLARITY, clarityFor } from "@/lib/clarity";
import s from "./FrameGrid.module.css";

/** The frames as 3D cards under a section head. `children` are more grid items, such as "Your panel". */
export function FrameGrid({ id, title, aside, frames, children }: { id: string; title: ReactNode; aside?: ReactNode; frames: FrameDesign[]; children?: ReactNode }) {
  return (
    <section className={s.section} aria-labelledby={id}>
      <div className={s.head}>
        <h2 id={id}>{title}</h2>
        {aside}
      </div>
      <ul className={s.grid}>
        {frames.map((f) => {
          const c = clarityFor(f.id);
          return (
            <li key={f.id}>
              <FrameCard f={f}>
                {c.state === "graded" && (
                  <span className={s.line}>
                    <Chip>
                      <b>
                        {CLARITY} {c.grade.grade}
                      </b>
                    </Chip>
                  </span>
                )}
              </FrameCard>
            </li>
          );
        })}
        {children}
      </ul>
    </section>
  );
}

/** A grid item that links out: a plus, a name and a line. */
export function AddCard({ href, title, children }: { href: string; title: string; children: ReactNode }) {
  return (
    <li className={s.wide}>
      <a href={href} className={`glass ${s.add}`}>
        <span className={s.plus} aria-hidden="true">
          +
        </span>
        <span>
          <b>{title}</b>
          <span>{children}</span>
        </span>
      </a>
    </li>
  );
}
