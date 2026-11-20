"use client";

import { useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { NavItem, NavTrack } from "@/lib/docs/nav";
import type { Section } from "@/lib/docs/sources";
import s from "../shell/Shell.module.css";
import d from "./Docs.module.css";

function matchesItem(path: string, it: NavItem): boolean {
  if (path === it.href) return true;
  if (it.also && path.startsWith(it.also)) return true;
  if (it.href !== "/docs" && path.startsWith(`${it.href}/`)) return true;
  return !!it.children?.some((c) => matchesItem(path, c));
}

function trackForPath(path: string, tracks: NavTrack[]): Section {
  if (path === "/research" || path.startsWith("/docs/notes") || /\/docs\/frames\/[^/]+\/notes(\/|$)/.test(path) || /\/docs\/frames\/[^/]+\/research(\/|$)/.test(path)) {
    return "notes";
  }
  for (const t of tracks) {
    if (t.id === "user") continue;
    if (t.groups.some((g) => g.items.some((it) => matchesItem(path, it)))) return t.id;
  }
  return "user";
}

/**
 * The docs sidebar: a 3-track audience switcher (Users · Developers · Research) above the active
 * track's tree, so end users never have to wade through contributor specs or reverse-engineering logs.
 */
export function DocsTree({ tracks }: { tracks: NavTrack[] }) {
  const path = usePathname();
  const routeTrack = trackForPath(path, tracks);
  const [picked, setPicked] = useState<{ path: string; id: Section } | null>(null);
  const activeId = picked && picked.path === path ? picked.id : routeTrack;
  const active = tracks.find((t) => t.id === activeId) ?? tracks[0];

  const within = (href: string) => path === href || path.startsWith(`${href}/`);
  const link = (it: NavItem, sub = false) => {
    const A = it.plain ? "a" : Link;
    const on = path === it.href || (!!it.also && path.startsWith(it.also));
    return (
      <A
        key={it.href}
        href={it.href}
        className={`${s.ni} ${sub ? s.sub : ""} ${it.deep ? s.deep : ""} ${on ? s.on : ""}`}
        aria-current={path === it.href ? "page" : on ? "true" : undefined}
      >
        {it.label}
        {it.aside && <small>{it.aside}</small>}
      </A>
    );
  };

  return (
    <>
      <div className={d.trackSwitch} role="tablist" aria-label="Documentation audience">
        {tracks.map((t) => {
          const on = t.id === active.id;
          return (
            <button
              key={t.id}
              type="button"
              role="tab"
              aria-selected={on}
              className={`${d.trackBtn} ${on ? d.trackBtnOn : ""}`}
              onClick={() => setPicked({ path, id: t.id })}
            >
              {t.shortLabel}
            </button>
          );
        })}
      </div>
      <nav className={s.nav} aria-label={`${active.label} docs`}>
        {active.groups.map((g) => (
          <div key={g.label} className={s.navGroup}>
            <span className={s.group}>{g.label}</span>
            {g.items.map((it) => (
              <div key={it.href} className={s.navGroup}>
                {link(it)}
                {it.children && within(it.href) && it.children.map((c) => link(c, true))}
              </div>
            ))}
          </div>
        ))}
      </nav>
    </>
  );
}
