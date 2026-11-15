import fs from "node:fs";
import path from "node:path";
import { docAssets } from "@/lib/docs";
import { REPO } from "@/lib/docs/sources";

/* The images the docs use, served from the repo beside their Markdown (only those the docs link). */
const TYPES: Record<string, string> = { ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp", ".gif": "image/gif", ".svg": "image/svg+xml" };

export async function generateStaticParams() {
  return (await docAssets()).map((p) => ({ path: p.split("/") }));
}

export async function GET(_req: Request, ctx: RouteContext<"/doc-assets/[...path]">) {
  const file = (await ctx.params).path.join("/");
  const type = TYPES[path.extname(file).toLowerCase()];
  if (!type || !(await docAssets()).includes(file)) return new Response("Not found", { status: 404 });
  return new Response(new Uint8Array(fs.readFileSync(path.join(/* turbopackIgnore: true */ REPO, file))), { headers: { "content-type": type, "cache-control": "public, max-age=3600" } });
}
