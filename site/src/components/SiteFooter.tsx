import { links } from "@/lib/site";
import s from "./chrome.module.css";

export function SiteFooter() {
  return (
    <footer className={s.foot}>
      <span>Home Assistant is a trademark of the Open Home Foundation. Glassworks isn&rsquo;t affiliated with it.</span>
      <span className={s.footLinks}>
        <a href="/blog/feed.xml">Feed</a>
        <a href={links.contact}>Contact</a>
      </span>
    </footer>
  );
}
