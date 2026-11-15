import { STAGE_TEXT } from "@glassworks/frame-card";
import { frameById, frames } from "@/lib/frames";
import { tileImage } from "@/lib/ogTile";

/* The frame's link preview, /frames/<id>/card.png: the Glass Tile with its name, made at build time. */
export function generateStaticParams() {
  return frames.map((f) => ({ slug: f.id }));
}

export async function GET(_req: Request, { params }: RouteContext<"/frames/[slug]/card.png">) {
  const f = frameById((await params).slug);
  if (!f) return new Response("Not found", { status: 404 });
  return tileImage({ label: `Frame · ${STAGE_TEXT[f.status]}`, name: f.name, line: f.tagline ?? f.summary });
}
