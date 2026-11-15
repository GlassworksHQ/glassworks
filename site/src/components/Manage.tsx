import Link from "next/link";
import type { CSSProperties, ReactNode } from "react";
import { SupportTag } from "./DeviceBits";
import type { Support } from "@/lib/frameText";
import m from "./Manage.module.css";

/*
 * The panel's Manage page, as site parts: glass cards, list rows with an icon square and a status
 * chip, and summary tiles. Every page builds from these, so the site reads as one product.
 */

export function PageHead({ crumbs, title, lede, children }: { crumbs?: ReactNode; title: ReactNode; lede?: ReactNode; children?: ReactNode }) {
  return (
    <header className={m.pagehead}>
      <div className={m.pageheadText}>
        {crumbs && (
          <nav className={m.crumbs} aria-label="Breadcrumb">
            {crumbs}
          </nav>
        )}
        <h1 className={m.h1}>{title}</h1>
        {lede && <p className={m.lede}>{lede}</p>}
      </div>
      {children}
    </header>
  );
}

export function ListCard({ id, title, aside, children, className = "" }: { id: string; title: ReactNode; aside?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`${m.card} ${m.list} ${className}`} aria-labelledby={id}>
      <div className={m.lh}>
        <h2 id={id}>{title}</h2>
        {aside}
      </div>
      {children}
    </section>
  );
}

export function Row({
  href,
  lead,
  title,
  sub,
  extra,
  end,
}: {
  href: string;
  lead?: ReactNode;
  title: ReactNode;
  sub?: ReactNode;
  extra?: ReactNode;
  end?: ReactNode;
}) {
  const body = (
    <>
      {lead}
      <span className={m.rt}>
        <b>{title}</b>
        {sub && <span>{sub}</span>}
        {extra}
      </span>
      {end}
      <span className={m.chev} aria-hidden="true">
        ›
      </span>
    </>
  );
  return /^https?:/.test(href) ? (
    <a className={m.row} href={href}>
      {body}
    </a>
  ) : (
    <Link className={m.row} href={href}>
      {body}
    </Link>
  );
}

/** A row that goes nowhere: a fact, like a spec. */
export function FactRow({ lead, label, value }: { lead?: ReactNode; label: ReactNode; value: ReactNode }) {
  return (
    <div className={`${m.row} ${m.fact}`}>
      {lead}
      <span className={m.rt}>{label}</span>
      <em>{value}</em>
    </div>
  );
}

export function Sq({ children, lit = false }: { children: ReactNode; lit?: boolean }) {
  return <span className={`${m.sq} ${lit ? m.lit : ""}`}>{children}</span>;
}

export function Chip({ children }: { children: ReactNode }) {
  return <span className={m.chip}>{children}</span>;
}

export function StageChip({ support }: { support: Support }) {
  return (
    <span className={m.chip}>
      <SupportTag support={support} />
    </span>
  );
}

export function Chips({ children }: { children: ReactNode }) {
  return <span className={m.chips}>{children}</span>;
}

export function Tiles({ children, cols, className = "" }: { children: ReactNode; cols?: number; className?: string }) {
  return (
    <div className={`${m.tiles} ${className}`} style={cols ? ({ "--cols": cols } as CSSProperties) : undefined}>
      {children}
    </div>
  );
}

export function Tile({ href, label, value, sub }: { href?: string; label?: ReactNode; value: ReactNode; sub?: ReactNode }) {
  const body = (
    <>
      {label && <small>{label}</small>}
      <b>{value}</b>
      {sub && <span>{sub}</span>}
    </>
  );
  if (!href) return <div className={m.tile}>{body}</div>;
  return /^https?:/.test(href) ? (
    <a className={m.tile} href={href}>
      {body}
    </a>
  ) : (
    <Link className={m.tile} href={href}>
      {body}
    </Link>
  );
}

export { m as manage };
