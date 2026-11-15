"use client";

import Link from "next/link";
import { useEffect, useState, type ReactNode } from "react";
import { Mark } from "../Mark";
import s from "./Shell.module.css";

/** The page frame: the sidebar beside the page, or on phones a top bar whose menu opens it. */
export function Shell({ nav, children }: { nav: ReactNode; children: ReactNode }) {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open) return;
    const close = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", close);
    return () => window.removeEventListener("keydown", close);
  }, [open]);

  // the drawer is a phone thing: widening past it (Shell.module.css, 900px) closes it
  useEffect(() => {
    const desktop = window.matchMedia("(min-width: 901px)");
    const close = () => desktop.matches && setOpen(false);
    desktop.addEventListener("change", close);
    return () => desktop.removeEventListener("change", close);
  }, []);

  return (
    <>
      <header className={s.top}>
        <Link href="/" className={s.topBrand}>
          <Mark cut="small" className={s.topMark} />
          Glassworks
        </Link>
        <span className={s.topR}>
          <button type="button" className={s.iconBtn} aria-label="Menu" aria-expanded={open} aria-controls="site-nav" onClick={() => setOpen(!open)}>
            <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
              {open ? <path d="M6 6l12 12M18 6 6 18" /> : <path d="M4 8h16M4 16h16" />}
            </svg>
          </button>
        </span>
      </header>
      <aside
        id="site-nav"
        className={s.side}
        data-open={open || undefined}
        onClick={(e) => {
          if ((e.target as Element).closest("a")) setOpen(false);
        }}
      >
        {nav}
      </aside>
      {/* while the drawer covers the page, the page can't take focus */}
      <div className={s.page} inert={open}>
        {children}
      </div>
    </>
  );
}
