import { getPosts } from "@/lib/docs";
import { SITE } from "@/lib/site";

/*
 * /blog/feed.xml: the blog as Atom, which every feed reader supports. Published posts only; a
 * draft is never in it, whatever the build shows. Links and images in the HTML are made absolute,
 * and each entry's xml:base is its post, so a #link in it points into that post.
 */
const esc = (t: string) => t.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
const absolute = (html: string) => html.replace(/(\s(?:href|src))="\/(?!\/)/g, `$1="${SITE}/`);
const stamp = (d: string) => `${d}T00:00:00Z`;

export async function GET() {
  const posts = (await getPosts()).filter((p) => !p.draft);
  // Atom needs <updated> even on an empty feed: the day the blog started
  const updated = posts[0] ? stamp(posts[0].date) : "2026-11-15T00:00:00Z";
  const entries = posts
    .map(
      (p) => `  <entry>
    <title>${esc(p.title)}</title>
    <id>${SITE}${p.url}</id>
    <link rel="alternate" type="text/html" href="${SITE}${p.url}"/>
    <published>${stamp(p.date)}</published>
    <updated>${stamp(p.date)}</updated>
    <author><name>${esc(p.author)}</name></author>
    <summary>${esc(p.summary)}</summary>
    <content type="html" xml:base="${SITE}${p.url}">${esc(absolute(p.html))}</content>
  </entry>`,
    )
    .join("\n");
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Glassworks</title>
  <subtitle>News from the open core for Home Assistant wall panels</subtitle>
  <id>${SITE}/blog</id>
  <link rel="self" type="application/atom+xml" href="${SITE}/blog/feed.xml"/>
  <link rel="alternate" type="text/html" href="${SITE}/blog"/>
  <icon>${SITE}/brand/app-icon.svg</icon>
  <updated>${updated}</updated>
  <author><name>The Glassworks project</name><uri>${SITE}</uri></author>
${entries}
</feed>
`;
  return new Response(xml, { headers: { "content-type": "application/atom+xml; charset=utf-8" } });
}
