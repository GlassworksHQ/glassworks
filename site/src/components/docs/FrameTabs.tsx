import Link from "next/link";
import { researchUrl } from "@/lib/docs/research";
import d from "./Docs.module.css";

/** A frame's two halves in the docs: its guides, and its research notes. */
export function FrameTabs({ frame, on, notes }: { frame: string; on: "docs" | "research"; notes: number }) {
  return (
    <nav className={d.tabs} aria-label="This frame">
      <Link href={`/docs/frames/${frame}`} className={d.tab} aria-current={on === "docs" ? "true" : undefined}>
        Docs
      </Link>
      <Link href={researchUrl(frame)} className={d.tab} aria-current={on === "research" ? "true" : undefined}>
        Research <small>{notes}</small>
      </Link>
    </nav>
  );
}
