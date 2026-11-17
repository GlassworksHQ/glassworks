"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { NavGroup, NavItem } from "@/lib/docs/nav";
import s from "../shell/Shell.module.css";

/** The docs' tree: the page you're on is lit, and a frame's guides open under it while you're in them. */
export function DocsTree({ groups }: { groups: NavGroup[] }) {
  const path = usePathname();
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
    <nav className={s.nav} aria-label="Docs">
      {groups.map((g) => (
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
  );
}
