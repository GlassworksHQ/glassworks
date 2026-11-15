"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import s from "./Search.module.css";

/*
 * Search over the docs with Pagefind: the index is built from the static HTML after `next build`
 * (the build script), loaded the first time the dialog opens, and searched in the browser. No
 * server, no third party. Results are grouped by section, guides first and engineering notes last.
 */
const PAGEFIND = "/_next/static/pagefind/pagefind.js";
const ORDER = ["Using a panel", "Frames", "Philosophy", "Panes and libraries", "Project", "Engineering notes"];

type Sub = { title: string; url: string; excerpt: string };
type Data = { url: string; excerpt: string; meta: { title?: string; section?: string }; sub_results: Sub[] };
type Pagefind = {
  options: (o: { baseUrl: string }) => Promise<void>;
  search: (q: string) => Promise<{ results: { data: () => Promise<Data> }[] } | null>;
};
type Hit = { title: string; url: string; excerpt: string; where?: string };
type Group = { section: string; hits: Hit[] };
/** results always say which query they answer, so Enter never opens an older search's pick */
type Results = { q: string; groups: Group[] } | { q: string; failed: true };
type Index = "idle" | "loading" | "ready" | "unavailable";

/** Pagefind's URLs are its file paths: /docs/x.html#y → /docs/x#y */
const clean = (u: string) => u.replace(/\/index\.html/, "/").replace(/\.html(?=#|$)/, "");

function group(data: Data[]): Group[] {
  const by = new Map<string, Hit[]>();
  for (const d of data) {
    const section = d.meta.section ?? "Docs";
    const sub = d.sub_results.find((r) => r.url.includes("#"));
    const hit: Hit = sub
      ? { title: d.meta.title ?? sub.title, where: sub.title, url: clean(sub.url), excerpt: sub.excerpt }
      : { title: d.meta.title ?? clean(d.url), url: clean(d.url), excerpt: d.excerpt };
    by.set(section, [...(by.get(section) ?? []), hit]);
  }
  const rank = (x: string) => (ORDER.includes(x) ? ORDER.indexOf(x) : ORDER.length);
  return [...by.entries()].sort((a, b) => rank(a[0]) - rank(b[0])).map(([section, hits]) => ({ section, hits }));
}

export function SearchDialog() {
  const dialog = useRef<HTMLDialogElement>(null);
  const pf = useRef<Pagefind | null>(null);
  const [index, setIndex] = useState<Index>("idle");
  const [q, setQ] = useState("");
  const [results, setResults] = useState<Results>({ q: "", groups: [] });
  const [at, setAt] = useState(0);
  const [attempt, setAttempt] = useState(0);
  const router = useRouter();

  const open = useCallback(async () => {
    dialog.current?.showModal();
    if (pf.current || index === "loading") return;
    setIndex("loading");
    try {
      const mod = (await import(/* webpackIgnore: true */ /* turbopackIgnore: true */ PAGEFIND)) as Pagefind;
      // the index lives under /_next/static; its pages live at the site's root
      await mod.options({ baseUrl: "/" });
      pf.current = mod;
      setIndex("ready");
    } catch {
      setIndex("unavailable");
    }
  }, [index]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        // the same event the search buttons send, so the phone drawer closes for this too
        window.dispatchEvent(new Event("gw:search"));
      }
    };
    const onOpen = () => void open();
    window.addEventListener("keydown", onKey);
    window.addEventListener("gw:search", onOpen);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("gw:search", onOpen);
    };
  }, [open]);

  useEffect(() => {
    if (index !== "ready" || !pf.current) return;
    let live = true;
    const query = q;
    const t = setTimeout(async () => {
      let next: Results;
      try {
        const r = query.trim() ? await pf.current!.search(query) : null;
        const data = r ? await Promise.all(r.results.slice(0, 10).map((x) => x.data())) : [];
        next = { q: query, groups: group(data) };
      } catch {
        next = { q: query, failed: true };
      }
      if (!live) return;
      setResults(next);
      setAt(0);
    }, 120);
    return () => {
      live = false;
      clearTimeout(t);
    };
  }, [q, index, attempt]);

  // the picked result stays in view as the arrows move it
  useEffect(() => {
    document.getElementById(`search-hit-${at}`)?.scrollIntoView({ block: "nearest" });
  }, [at, results]);

  const current = results.q === q && !("failed" in results) ? results.groups : [];
  const failed = results.q === q && "failed" in results;
  const flat = current.flatMap((g) => g.hits);
  const idOf = (i: number) => `search-hit-${i}`;
  const go = (hit: Hit | undefined) => {
    if (!hit) return;
    dialog.current?.close();
    router.push(hit.url);
  };

  return (
    <dialog
      ref={dialog}
      className={s.dialog}
      aria-label="Search the docs"
      onClick={(e) => e.target === dialog.current && dialog.current.close()}
    >
      <div className={s.box}>
        <label className={s.input}>
          <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
            <circle cx="11" cy="11" r="6.5" />
            <path d="m16 16 4.5 4.5" />
          </svg>
          <input
            type="search"
            role="combobox"
            aria-expanded={flat.length > 0}
            aria-controls="search-results"
            aria-activedescendant={flat.length ? idOf(at) : undefined}
            aria-autocomplete="list"
            value={q}
            placeholder="Search the docs"
            aria-label="Search the docs"
            autoFocus
            onChange={(e) => setQ(e.target.value)}
            onKeyDown={(e) => {
              // keys that confirm an input method's composition aren't ours
              if (e.nativeEvent.isComposing || e.keyCode === 229) return;
              if (e.key === "ArrowDown" || e.key === "ArrowUp") {
                e.preventDefault();
                setAt((a) => (e.key === "ArrowDown" ? Math.min(flat.length - 1, a + 1) : Math.max(0, a - 1)));
              }
              if (e.key === "Enter") {
                // the dialog closes and focus goes back to the button that opened it: keep this
                // Enter from pressing that button too
                e.preventDefault();
                go(flat[at]);
              }
            }}
          />
          <kbd>esc</kbd>
        </label>
        <div className={s.results} aria-live="polite">
          {index === "unavailable" && <p className={s.note}>Search works on the built site.</p>}
          {failed && (
            <p className={s.note}>
              Search failed.{" "}
              <button type="button" className={s.retry} onClick={() => setAttempt((n) => n + 1)}>
                Try again
              </button>
            </p>
          )}
          {index === "ready" && q.trim() && results.q === q && !failed && !flat.length && <p className={s.note}>No results.</p>}
          <div id="search-results" role="listbox" aria-label="Results">
            {current.map((g) => (
              <div key={g.section} className={s.group} role="group" aria-label={g.section}>
                <span className="label" aria-hidden="true">
                  {g.section}
                </span>
                {g.hits.map((h) => {
                  const i = flat.indexOf(h);
                  return (
                    <a
                      key={h.url}
                      id={idOf(i)}
                      role="option"
                      aria-selected={i === at}
                      href={h.url}
                      className={`${s.hit} ${i === at ? s.on : ""}`}
                      onMouseEnter={() => setAt(i)}
                      onClick={(e) => {
                        // a modified click (new tab, new window) is the browser's
                        if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
                        e.preventDefault();
                        go(h);
                      }}
                    >
                      <b>
                        {h.title}
                        {h.where && h.where !== h.title && <span> › {h.where}</span>}
                      </b>
                      {/* Pagefind's excerpt is the page's own text with <mark> around the matches */}
                      <span className={s.excerpt} dangerouslySetInnerHTML={{ __html: h.excerpt }} />
                      <small>{h.url}</small>
                    </a>
                  );
                })}
              </div>
            ))}
          </div>
        </div>
        <div className={s.foot}>
          <span>↑↓ to move</span>
          <span>↵ to open</span>
        </div>
      </div>
    </dialog>
  );
}
