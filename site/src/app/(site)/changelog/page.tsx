import { DocArticle } from "@/components/docs/DocArticle";
import { getDoc } from "@/lib/docs";
import { pageMetadata } from "@/lib/seo";

export const metadata = pageMetadata({
  title: "Changelog",
  description: "What changed in each Glassworks app release, for people running a panel. The newest release is first.",
  path: "/changelog",
  type: "article",
});

export default async function Changelog() {
  const doc = await getDoc("/changelog");
  if (!doc) throw new Error("CHANGELOG.md isn't a docs source (src/lib/docs/sources.ts)");
  return <DocArticle doc={doc} />;
}
