"use client";

import { useEffect, useState } from "react";
import type { Toc as TocItem } from "@/lib/docs";
import d from "./Docs.module.css";

/* a heading counts as read once its top passes this far down the window */
const LINE = 120;

/** "On this page": the H2s and H3s, the one you're reading lit. */
export function Toc({ toc }: { toc: TocItem[] }) {
  const [on, setOn] = useState<string>();
  useEffect(() => {
    const els = toc.map((t) => document.getElementById(t.id)).filter((e): e is HTMLElement => !!e);
    let frame = 0;
    // the last heading above the line, from positions, so scrolling either way lands right
    const pick = () => {
      frame = 0;
      let current: string | undefined;
      for (const e of els) {
        if (e.getBoundingClientRect().top <= LINE) current = e.id;
        else break;
      }
      setOn(current);
    };
    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(pick);
    };
    pick();
    window.addEventListener("scroll", schedule, { passive: true });
    window.addEventListener("resize", schedule);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("scroll", schedule);
      window.removeEventListener("resize", schedule);
    };
  }, [toc]);
  return (
    <aside className={d.toc} aria-label="On this page">
      <span className="label">On this page</span>
      <ol>
        {toc.map((t) => (
          <li key={t.id}>
            <a href={`#${t.id}`} className={`${t.depth === 3 ? d.tocSub : ""} ${on === t.id ? d.tocOn : ""}`} aria-current={on === t.id ? "location" : undefined}>
              {t.text}
            </a>
          </li>
        ))}
      </ol>
    </aside>
  );
}
