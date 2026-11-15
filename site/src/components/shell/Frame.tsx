import type { ReactNode } from "react";
import { SiteFooter } from "../SiteFooter";
import { Shell } from "./Shell";
import chrome from "../chrome.module.css";

/** A page in the site's frame: a nav (the site's, or the docs' tree) beside the page and its footer. */
export function Frame({ nav, children }: { nav: ReactNode; children: ReactNode }) {
  return (
    <Shell nav={nav}>
      <main id="content" className={chrome.main}>
        {children}
      </main>
      <SiteFooter />
    </Shell>
  );
}
