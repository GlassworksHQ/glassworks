import Link from "next/link";
import { Mark } from "../Mark";
import { GitHubGlyph } from "../Glyphs";
import { SupportTag } from "../DeviceBits";
import { NavLink } from "./NavLink";
import { ThemeToggle } from "./ThemeToggle";
import { frames, KILN } from "@/lib/frames";
import { links, panes } from "@/lib/site";
import s from "./Shell.module.css";

/** The site's one navigation: Manage's sidebar on desktop, the menu's drawer on phones. */
export function Sidebar() {
  return (
    <>
      <Link href="/" className={s.brand}>
        <Mark cut="small" className={s.brandMark} />
        <span>
          <b>Glassworks</b>
          <small>The open panel core</small>
        </span>
      </Link>
      <nav className={s.nav} aria-label="Main">
        <NavLink href="/">Overview</NavLink>

        <Link href="/frames" className={s.group}>
          Frames
        </Link>
        {frames.map((f) => (
          <NavLink key={f.id} href={`/frames/${f.id}`}>
            {f.name}
            <small>
              <SupportTag support={f.status} />
            </small>
          </NavLink>
        ))}

        <span className={s.group}>Build</span>
        <NavLink href="/panes">
          Panes<small>{panes.length}</small>
        </NavLink>
        <NavLink href="/blueprints">Blueprints</NavLink>
        <a href={`${links.s1}/tree/main/docs`} className={s.ni}>
          Docs<small aria-hidden="true">↗</small>
        </a>
        <a href={KILN} className={s.ni}>
          <span className={s.ember}>Kiln</span>
          <small aria-hidden="true">↗</small>
        </a>

        <span className={s.group}>Project</span>
        <NavLink href="/brand">Brand</NavLink>
        <NavLink href="/trademarks">Trademarks</NavLink>
      </nav>
      <div className={s.who}>
        <a href={links.org}>
          <GitHubGlyph />
          GlassworksHQ
        </a>
        <span className={s.whoEnd}>
          <ThemeToggle />
          MPL-2.0
        </span>
      </div>
    </>
  );
}
