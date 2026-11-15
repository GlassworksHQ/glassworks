import type { Metadata } from "next";
import Link from "next/link";
import s from "./not-found.module.css";

/* No canonical and no index: Next adds `noindex` to a not-found page, and the title says what happened. */
export const metadata: Metadata = {
  title: "Page not found",
  description: "There's no page at this address on glassworks.dev.",
};

export default function NotFound() {
  return (
    <div className={`wrap ${s.page}`}>
      <header className={s.head}>
        <span className="label">404</span>
        <h1 className={s.h1}>Page not found</h1>
        <p className={s.lede}>There&rsquo;s no page at this address.</p>
      </header>
      <ul className={s.links}>
        <li>
          <Link href="/">Home</Link>
        </li>
        <li>
          <Link href="/frames">Devices</Link>
        </li>
        <li>
          <Link href="/panes">Panes</Link>
        </li>
      </ul>
    </div>
  );
}
