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
type State = "idle" | "loading" | "ready" | "unavailable";

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
  const [state, setState] = useState<State>("idle");
  const [q, setQ] = useState("");
  const [groups, setGroups] = useState<Group[]>([]);
  const [at, setAt] = useState(0);
  const router = useRouter();

  const open = useCallback(async () => {
    dialog.current?.showModal();
    if (pf.current || state === "loading") return;
    setState("loading");
    try {
      const mod = (await import(/* webpackIgnore: true */ /* turbopackIgnore: true */ PAGEFIND)) as Pagefind;
      // the index lives under /_next/static; its pages live at the site's root
      await mod.options({ baseUrl: "/" });
      pf.current = mod;
      setState("ready");
    } catch {
      setState("unavailable");
    }
  }, [state]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        void open();
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
    if (state !== "ready" || !pf.current) return;
    let live = true;
    const t = setTimeout(async () => {
      const r = q.trim() ? await pf.current!.search(q) : null;
      const data = r ? await Promise.all(r.results.slice(0, 10).map((x) => x.data())) : [];
      if (!live) return;
      setGroups(group(data));
      setAt(0);
    }, 120);
    return () => {
      live = false;
      clearTimeout(t);
    };
  }, [q, state]);

  const flat = groups.flatMap((g) => g.hits);
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
            value={q}
            placeholder="Search the docs"
            aria-label="Search the docs"
            autoFocus
            onChange={(e) => setQ(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "ArrowDown" || e.key === "ArrowUp") {
                e.preventDefault();
                setAt((a) => (e.key === "ArrowDown" ? Math.min(flat.length - 1, a + 1) : Math.max(0, a - 1)));
              }
              if (e.key === "Enter") go(flat[at]);
            }}
          />
          <kbd>esc</kbd>
        </label>
        <div className={s.results} aria-live="polite">
          {state === "unavailable" && <p className={s.note}>Search works on the built site.</p>}
          {state === "ready" && q.trim() && !flat.length && <p className={s.note}>No results.</p>}
          {groups.map((g) => (
            <div key={g.section} className={s.group}>
              <span className="label">{g.section}</span>
              {g.hits.map((h) => {
                const i = flat.indexOf(h);
                return (
                  <a
                    key={h.url}
                    href={h.url}
                    className={`${s.hit} ${i === at ? s.on : ""}`}
                    onMouseEnter={() => setAt(i)}
                    onClick={(e) => {
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
        <div className={s.foot}>
          <span>↑↓ to move</span>
          <span>↵ to open</span>
        </div>
      </div>
    </dialog>
  );
}
