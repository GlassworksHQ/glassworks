import { frameById } from "@/lib/frames";
import { tileImage } from "@/lib/ogTile";
import { panes } from "@/lib/site";

/* The pane's link preview, /panes/<id>/card.png: the Glass Tile with its name, made at build time. */
export function generateStaticParams() {
  return panes.map((p) => ({ id: p.id }));
}

export async function GET(_req: Request, { params }: RouteContext<"/panes/[id]/card.png">) {
  const { id } = await params;
  const p = panes.find((x) => x.id === id);
  if (!p) return new Response("Not found", { status: 404 });
  const frame = p.frame ? frameById(p.frame) : undefined;
  return tileImage({ label: frame ? `Frame pane · ${frame.name}` : "Pane", name: p.name, line: p.does });
}
