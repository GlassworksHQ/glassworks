"use client";

import { useEffect, useState } from "react";
import type { Toc as TocItem } from "@/lib/docs";
import d from "./Docs.module.css";

/** "On this page": the H2s and H3s, the one you're reading lit. */
export function Toc({ toc }: { toc: TocItem[] }) {
  const [on, setOn] = useState<string>();
  useEffect(() => {
    const els = toc.map((t) => document.getElementById(t.id)).filter((e): e is HTMLElement => !!e);
    const io = new IntersectionObserver(
      (entries) => {
        const top = entries.filter((e) => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0];
        if (top) setOn(top.target.id);
      },
      { rootMargin: "0px 0px -70% 0px" },
    );
    els.forEach((e) => io.observe(e));
    return () => io.disconnect();
  }, [toc]);
  return (
    <aside className={d.toc} aria-label="On this page">
      <span className="label">On this page</span>
      <ol>
        {toc.map((t) => (
          <li key={t.id}>
            <a href={`#${t.id}`} className={`${t.depth === 3 ? d.tocSub : ""} ${on === t.id ? d.tocOn : ""}`}>
              {t.text}
            </a>
          </li>
        ))}
      </ol>
    </aside>
  );
}
