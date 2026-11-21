"use client";

import { useId, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { NavItem, NavTrack } from "@/lib/docs/nav";
import type { Section } from "@/lib/docs/sources";
import s from "../shell/Shell.module.css";
import d from "./Docs.module.css";
import { onTabKey } from "./tabs";

/** The page is this item, or one of its children */
function isItem(path: string, it: NavItem): boolean {
  return path === it.href || !!it.children?.some((c) => isItem(path, c));
}

/** The page is this item, one of its children, or under it */
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
  // a page a track lists first, then one under an item, so a device guide (the User Guide's
  // /docs/frames/<id>) is not taken for the Developers' index of /docs/frames
  for (const match of [isItem, matchesItem]) {
    for (const t of tracks) {
      if (t.groups.some((g) => g.items.some((it) => match(path, it)))) return t.id;
    }
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
  const pick = (id: Section) => setPicked({ path, id });
  const base = useId();
  const tabId = (id: Section) => `${base}-tab-${id}`;
  const panel = `${base}-panel`;

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
              id={tabId(t.id)}
              aria-selected={on}
              aria-controls={panel}
              tabIndex={on ? 0 : -1}
              className={`${d.trackBtn} ${on ? d.trackBtnOn : ""}`}
              onClick={() => pick(t.id)}
              onKeyDown={(e) => onTabKey(e, tracks.map((x) => x.id), active.id, pick)}
            >
              {t.shortLabel}
            </button>
          );
        })}
      </div>
      {/* the active track's tree: the panel the tabs above control */}
      <div className={d.trackPanel} role="tabpanel" id={panel} aria-labelledby={tabId(active.id)}>
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
      </div>
    </>
  );
}
