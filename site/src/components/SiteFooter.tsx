import { links } from "@/lib/site";
import s from "./chrome.module.css";

export function SiteFooter() {
  return (
    <footer className={s.foot}>
      <span>Home Assistant is a trademark of the Open Home Foundation. Glassworks isn&rsquo;t affiliated with it.</span>
      <a href={links.contact}>Contact</a>
    </footer>
  );
}
