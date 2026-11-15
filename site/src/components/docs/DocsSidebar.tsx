import Link from "next/link";
import { Mark } from "../Mark";
import { GitHubGlyph } from "../Glyphs";
import { SearchButton } from "../search/SearchButton";
import { ThemeToggle } from "../shell/ThemeToggle";
import { DocsTree } from "./DocsTree";
import { docsNav } from "@/lib/docs/nav";
import { links } from "@/lib/site";
import s from "../shell/Shell.module.css";
import d from "./Docs.module.css";

/** In the docs, the sidebar holds the docs' tree; a row of chips leads back to the site. */
export async function DocsSidebar() {
  const nav = await docsNav();
  return (
    <>
      <Link href="/" className={s.brand}>
        <Mark cut="small" className={s.brandMark} />
        <span>
          <b>Glassworks</b>
          <small>The open panel core</small>
        </span>
      </Link>
      <nav className={d.cross} aria-label="Site">
        <Link href="/">Overview</Link>
        <Link href="/frames">Frames</Link>
        <Link href="/panes">Panes</Link>
      </nav>
      <div className={d.search}>
        <SearchButton />
      </div>
      <DocsTree groups={nav} />
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
