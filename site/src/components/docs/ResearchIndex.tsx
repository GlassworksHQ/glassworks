"use client";

import { useState, type ReactNode } from "react";
import { ListCard } from "../Manage";
import { NoteRow } from "./NoteBits";
import { EVIDENCE, type Evidence } from "@/lib/docs/notes";
import type { NoteItem } from "@/lib/docs/research";
import d from "./Docs.module.css";

const plain = (html: string) => html.replace(/<[^>]+>/g, "").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");

/**
 * The /research page's filter: words and how sure. With neither set, the page shows its frames and
 * topics (children); with either, one list of every note that matches, across frames. The status line
 * stays in the page, so a screen reader announces the count when the filter changes.
 */
export function ResearchIndex({ notes, frameNames, children }: { notes: NoteItem[]; frameNames: Record<string, string>; children: ReactNode }) {
  const [q, setQ] = useState("");
  const [ev, setEv] = useState<Evidence | "">("");
  const words = q.toLowerCase().split(/\s+/).filter(Boolean);
  const active = words.length > 0 || ev !== "";
  const hits = active
    ? notes.filter((n) => {
        if (ev && n.evidence !== ev) return false;
        const text = `${n.title} ${plain(n.blurb)} ${n.frame ? frameNames[n.frame] : ""}`.toLowerCase();
        return words.every((w) => text.includes(w));
      })
    : [];
  return (
    <>
      <div className={d.filter} role="search">
        <input
          type="search"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder={`Filter ${notes.length} notes`}
          aria-label="Filter the notes by words"
        />
        <div className={d.filterChips} role="group" aria-label="How sure">
          {(["", ...Object.keys(EVIDENCE)] as (Evidence | "")[]).map((e) => (
            <button key={e || "all"} type="button" aria-pressed={ev === e} onClick={() => setEv(e)}>
              {e ? EVIDENCE[e] : "All"}
            </button>
          ))}
        </div>
      </div>
      <p className="visually-hidden" role="status">
        {active ? (hits.length === 0 ? "No note matches." : hits.length === 1 ? "1 note matches." : `${hits.length} notes match.`) : ""}
      </p>
      {active ? (
        <ListCard id="matches" title={hits.length === 1 ? "1 note" : `${hits.length} notes`} aside={<button type="button" className={d.clear} onClick={() => (setQ(""), setEv(""))}>Clear</button>}>
          {hits.map((n) => (
            <NoteRow key={n.url} note={n} frame={n.frame ? frameNames[n.frame] : "General"} />
          ))}
          {hits.length === 0 && <p className={d.none}>No note matches. Search (the magnifier) looks inside every doc and note.</p>}
        </ListCard>
      ) : (
        children
      )}
    </>
  );
}
