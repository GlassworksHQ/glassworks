import { ALL_POSTS, POSTS } from "@/lib/blog/posts";
import { tileImage } from "@/lib/ogTile";

/* A post's link preview, /blog/<slug>/card.png, and its cover when it has no cover.webp: the Glass
   Tile with its title, made at build time. */
export function generateStaticParams() {
  // every folder, so the route has at least one page; a draft this build hides renders the 404
  return ALL_POSTS.map((p) => ({ slug: p.slug }));
}

export async function GET(_req: Request, { params }: RouteContext<"/blog/[slug]/card.png">) {
  const { slug } = await params;
  const p = POSTS.find((x) => x.slug === slug);
  if (!p) return new Response("Not found", { status: 404 });
  return tileImage({ label: p.draft ? "Blog · Draft" : "Blog", name: p.title, line: p.summary });
}
