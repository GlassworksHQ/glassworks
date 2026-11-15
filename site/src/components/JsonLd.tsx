import { jsonLd } from "@/lib/seo";

/** Structured data for search engines: the nodes from src/lib/seo.ts, as one JSON-LD script. */
export function JsonLd({ nodes }: { nodes: Record<string, unknown>[] }) {
  return <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: jsonLd(...nodes) }} />;
}
