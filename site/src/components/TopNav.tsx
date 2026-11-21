"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Mark } from "./Mark";
import { DiscordGlyph, GitHubGlyph, KilnGlyph } from "./Glyphs";
import { ThemeToggle } from "./shell/ThemeToggle";
import { KILN, links } from "@/lib/links";
import s from "./TopNav.module.css";

const NAV_ITEMS = [
  { href: "/", label: "Overview" },
  { href: "/frames", label: "Devices" },
  { href: "/panes", label: "Features" },
  { href: "/docs", label: "Docs" },
  { href: "/developers", label: "Developers" },
  { href: "/blog", label: "Blog" },
] as const;

export function TopNav({ hasFreshPost = false }: { hasFreshPost?: boolean }) {
  const path = usePathname();
  const [open, setOpen] = useState(false);
  const [shownOn, setShownOn] = useState(path);
  const menuBtnRef = useRef<HTMLButtonElement>(null);
  const drawerRef = useRef<HTMLDivElement>(null);

  if (shownOn !== path) {
    setShownOn(path);
    setOpen(false);
  }

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        const focusInDrawer = drawerRef.current?.contains(document.activeElement);
        setOpen(false);
        if (focusInDrawer) {
          menuBtnRef.current?.focus();
        }
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  useEffect(() => {
    const mq = window.matchMedia("(min-width: 821px)");
    const close = () => {
      if (mq.matches) setOpen(false);
    };
    mq.addEventListener("change", close);
    return () => mq.removeEventListener("change", close);
  }, []);

  const isActive = (href: string) => (href === "/" ? path === "/" : path === href || path.startsWith(`${href}/`));

  return (
    <header className={s.bar}>
      <div className={s.inner}>
        <Link href="/" className={s.brand} aria-label="Glassworks home">
          <Mark cut="small" className={s.mark} />
          <span className={s.word}>Glassworks</span>
        </Link>

        <nav className={s.nav} aria-label="Primary">
          {NAV_ITEMS.map((item) => {
            const active = isActive(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                className={`${s.link} ${active ? s.active : ""}`}
                aria-current={active ? "page" : undefined}
              >
                {item.label}
                {item.href === "/blog" && hasFreshPost && <span className={s.freshDot} aria-label="New post" />}
              </Link>
            );
          })}
        </nav>

        <div className={s.actions}>
          <ThemeToggle />
          <a href={links.discord} className={s.discordPill} aria-label="Join Glassworks on Discord" title="Join Discord">
            <DiscordGlyph />
            <span className={s.discordLabel}>Discord</span>
          </a>
          <a href={links.org} className={s.iconLink} aria-label="Glassworks on GitHub" title="GitHub">
            <GitHubGlyph />
          </a>
          <a href={KILN} className={s.kilnPill}>
            <KilnGlyph />
            <span>Install with Kiln</span>
            <span className={s.arrow} aria-hidden="true">
              ↗
            </span>
          </a>
        </div>

        <div className={s.mobileActions}>
          <ThemeToggle />
          <button
            ref={menuBtnRef}
            type="button"
            className={s.menuBtn}
            aria-label={open ? "Close navigation menu" : "Open navigation menu"}
            aria-expanded={open}
            aria-controls="topnav-drawer"
            onClick={() => setOpen((v) => !v)}
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
              {open ? <path d="M6 6l12 12M18 6 6 18" /> : <path d="M4 8h16M4 16h16" />}
            </svg>
          </button>
        </div>
      </div>

      <div ref={drawerRef} id="topnav-drawer" className={`${s.drawer} ${open ? s.drawerOpen : ""}`} hidden={!open}>
        <nav className={s.drawerNav} aria-label="Mobile navigation">
          {NAV_ITEMS.map((item) => {
            const active = isActive(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                className={`${s.drawerLink} ${active ? s.drawerActive : ""}`}
                aria-current={active ? "page" : undefined}
                onClick={() => setOpen(false)}
              >
                <span>{item.label}</span>
                {item.href === "/blog" && hasFreshPost && <span className={s.freshPill}>New</span>}
              </Link>
            );
          })}
          <div className={s.drawerDivider} />
          <a href={links.discord} className={s.drawerLink} onClick={() => setOpen(false)}>
            <span className={s.drawerGitHub}>
              <DiscordGlyph />
              Discord
            </span>
            <span className={s.drawerMeta}>Community ↗</span>
          </a>
          <a href={links.org} className={s.drawerLink} onClick={() => setOpen(false)}>
            <span className={s.drawerGitHub}>
              <GitHubGlyph />
              GitHub
            </span>
            <span className={s.drawerMeta}>GlassworksHQ ↗</span>
          </a>
          <a href={KILN} className={s.drawerKiln} onClick={() => setOpen(false)}>
            <KilnGlyph />
            <span>Install with Kiln ↗</span>
          </a>
        </nav>
      </div>
    </header>
  );
}
