import Image from "next/image";
import type { Post } from "@/lib/docs";
import { ASSET_PREFIX } from "@/lib/docs";
import b from "./Blog.module.css";

/** "1 December 2026", from a post's YYYY-MM-DD */
export const longDate = (d: string) =>
  new Date(`${d}T00:00:00Z`).toLocaleDateString("en-GB", { day: "numeric", month: "long", year: "numeric", timeZone: "UTC" });

/** The post's cover.webp, or its preview card when it has none. */
export function PostCover({ post, priority = false, className = "" }: { post: Post; priority?: boolean; className?: string }) {
  const src = post.cover ? ASSET_PREFIX + post.cover : `${post.url}/card.png`;
  return (
    <div className={`${b.cover} ${className}`}>
      <Image src={src} alt={post.coverAlt ?? ""} width={1200} height={630} priority={priority} unoptimized />
    </div>
  );
}

export function DraftFlag() {
  return <span className={b.draft}>Draft</span>;
}
