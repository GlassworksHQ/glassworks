import Link from "next/link";
import { KILN, links } from "@/lib/links";
import chrome from "./chrome.module.css";
import f from "./SiteFooter.module.css";

export function SiteFooter({ layout = "sidebar" }: { layout?: "sidebar" | "quiet" }) {
  if (layout === "sidebar") {
    return (
      <footer className={chrome.foot}>
        <span>Home Assistant is a trademark of the Open Home Foundation. Glassworks isn&rsquo;t affiliated with it.</span>
        <span className={chrome.footLinks}>
          <a href="/blog/feed.xml">Feed</a>
          <a href={links.contact}>Contact</a>
        </span>
      </footer>
    );
  }

  return (
    <footer className={f.footer}>
      <div className={f.inner}>
        <p className={f.notice}>
          Home Assistant is a trademark of the Open Home Foundation. Glassworks isn&rsquo;t affiliated with it.
        </p>
        <nav className={f.links} aria-label="Footer">
          <Link href="/frames">Devices</Link>
          <Link href="/panes">Features</Link>
          <Link href="/blueprints">Blueprints</Link>
          <Link href="/developers">Developers</Link>
          <Link href="/docs">Docs</Link>
          <Link href="/research">Research</Link>
          <Link href="/blog">Blog</Link>
          <Link href="/brand">Brand</Link>
          <Link href="/trademarks">Trademarks</Link>
          <a href={KILN}>Kiln ↗</a>
          <a href="/blog/feed.xml">Feed</a>
          <a href={links.contact}>Contact</a>
        </nav>
      </div>
    </footer>
  );
}
