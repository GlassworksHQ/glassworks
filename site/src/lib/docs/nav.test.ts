import { describe, expect, it, vi } from "vitest";
import { docsTracks } from "./nav";

vi.mock("./index", () => ({
  getDocs: async () => [
    { section: "user", url: "/docs/getting-started", title: "Getting started" },
    { section: "user", url: "/docs/frames/fixture-frame", frame: "fixture-frame", title: "Frame overview" },
    { section: "user", url: "/docs/frames/fixture-frame/setup", frame: "fixture-frame", title: "Install the frame", label: "Setup" },
    { section: "notes", url: "/docs/notes", title: "How we got here" },
    { section: "notes", url: "/docs/frames/fixture-frame/notes/display", frame: "fixture-frame", title: "Display notes" },
    { section: "developers", url: "/docs/principles", title: "Principles" },
    { section: "developers", url: "/docs/libraries/site-ref", title: "Site reference", file: "site-ref" },
    { section: "developers", url: "/docs/libraries/external-ref", title: "External reference", file: "external-ref" },
    { section: "developers", url: "/docs/libraries/pending", title: "Pending library", file: "pending" },
  ],
}));

vi.mock("../frames", () => ({
  frames: [
    { id: "fixture-frame", name: "Fixture Frame", status: "beta" },
    { id: "other-frame", name: "Other Frame", status: "planned" },
  ],
}));

vi.mock("../apiDocs", () => ({
  apiReferenceOf: (file: string) => ({
    reference: file === "site-ref"
      ? { kind: "site", href: "/docs/api/site-ref", version: "1.2.3", coordinate: "dev.glassworks:site-ref:1.2.3" }
      : file === "external-ref"
        ? { kind: "elsewhere", host: "javadoc.io", href: "https://javadoc.io/doc/example/external-ref" }
        : { kind: "unreleased" },
  }),
}));

describe("documentation tracks", () => {
  it("keeps setup in the User Guide and counts only devices with notes in Research", async () => {
    const tracks = await docsTracks();
    expect(tracks.map(({ id }) => id)).toEqual(["user", "developers", "notes"]);
    const userItems = tracks[0].groups.flatMap(({ items }) => items);
    expect(userItems.find(({ href }) => href === "/docs/frames/fixture-frame")).toEqual({
      label: "Fixture Frame",
      href: "/docs/frames/fixture-frame",
      aside: undefined,
      children: [{ label: "Setup", href: "/docs/frames/fixture-frame/setup" }],
    });
    expect(userItems.find(({ href }) => href === "/docs/frames/other-frame")).toMatchObject({ aside: "Planned" });
    expect(userItems.filter(({ href }) => href === "/docs/getting-started")).toHaveLength(1);
    const research = tracks[2].groups;
    expect(research.find(({ label }) => label === "By device")?.items).toEqual([
      { label: "Fixture Frame", href: "/docs/frames/fixture-frame/research", also: "/docs/frames/fixture-frame/notes/", aside: "1" },
    ]);
    expect(research.flatMap(({ items }) => items).find(({ href }) => href === "/research")?.aside).toBe("1");
  });

  it("lists the API links after their library and omits an unreleased API", async () => {
    const developers = (await docsTracks()).find(({ id }) => id === "developers")!;
    const libraries = developers.groups.flatMap(({ items }) => items).find(({ href }) => href === "/docs/libraries");
    expect(libraries?.aside).toBe("3");
    expect(libraries?.children).toEqual([
      { label: "Site reference", href: "/docs/libraries/site-ref" },
      { label: "API", href: "/docs/api/site-ref", aside: "1.2.3", deep: true, plain: true },
      { label: "External reference", href: "/docs/libraries/external-ref" },
      { label: "API", href: "https://javadoc.io/doc/example/external-ref", aside: "javadoc.io", deep: true, plain: true },
      { label: "Pending library", href: "/docs/libraries/pending" },
    ]);
  });
});
