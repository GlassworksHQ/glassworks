import { expect, it, vi } from "vitest";
import { sitemapEntries } from "./routes";

// The sitemap normally reads generated API docs and runs git to date every Markdown page.
vi.mock("./apiDocs", () => ({ apiReferences: () => new Map() }));
vi.mock("./docs", () => ({ lastChanged: () => undefined }));

it("publishes the Developers landing page once at its canonical URL", () => {
  const developers = sitemapEntries().filter(({ url }) => new URL(url).pathname === "/developers");
  expect(developers).toEqual([{ url: "https://www.glassworks.dev/developers" }]);
});
