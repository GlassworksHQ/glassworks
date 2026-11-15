"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import s from "./Shell.module.css";

export function NavLink({ href, children }: { href: string; children: ReactNode }) {
  const path = usePathname();
  const on = href === "/" ? path === "/" : path === href || path.startsWith(`${href}/`);
  return (
    <Link href={href} className={`${s.ni} ${on ? s.on : ""}`} aria-current={on ? "page" : undefined}>
      {children}
    </Link>
  );
}
