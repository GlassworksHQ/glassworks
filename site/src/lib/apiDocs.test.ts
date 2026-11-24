import { beforeEach, describe, expect, it, vi } from "vitest";

// scripts/api-docs.mjs writes .api-docs/libraries.json; each test gives its own.
const files = new Map<string, string>();
vi.mock("node:fs", () => ({
  default: {
    existsSync: (p: string) => files.has(p),
    readFileSync: (p: string) => files.get(p),
  },
}));

const FILE = `${process.cwd()}/.api-docs/libraries.json`;

async function load(json: unknown) {
  files.clear();
  files.set(FILE, JSON.stringify(json));
  vi.resetModules();
  return import("./apiDocs");
}

describe("API references", () => {
  beforeEach(() => files.clear());

  it("finds an npm package's reference from its README in kiln/packages, with its npm coordinate", async () => {
    const fel = { kind: "site", version: "0.1.1", href: "/docs/libraries/fel/api/index.html", coordinate: "@glassworks/fel@0.1.1" };
    const { apiReferenceOf } = await load({ fel });
    expect(apiReferenceOf("kiln/packages/fel/README.md")).toEqual({ name: "fel", reference: fel });
  });

  it("finds a Kotlin library's reference from its README in libs", async () => {
    const improv = { kind: "site", version: "0.2.0", href: "/docs/libraries/improv/api/index.html", coordinate: "dev.glassworks:improv:0.2.0" };
    const { apiReferenceOf } = await load({ improv });
    expect(apiReferenceOf("libs/improv/README.md")).toEqual({ name: "improv", reference: improv });
  });

  it("has none for a README that isn't a library's, or a package with no reference", async () => {
    const { apiReferenceOf } = await load({ fel: { kind: "unreleased" } });
    expect(apiReferenceOf("frames/fel/README.md")).toBeUndefined();
    expect(apiReferenceOf("kiln/packages/kiln-core/README.md")).toBeUndefined();
  });

  it("links a reference elsewhere when the registry didn't answer", async () => {
    const ext4 = { kind: "elsewhere", host: "jsdocs.io", href: "https://www.jsdocs.io/package/@glassworks/ext4" };
    const { apiReferences } = await load({ ext4 });
    expect(apiReferences().get("ext4")).toEqual(ext4);
  });

  it("refuses a reference the site would serve without saying what it's named by", async () => {
    const { apiReferences } = await load({ fel: { kind: "site", version: "0.1.1", href: "/docs/libraries/fel/api/index.html" } });
    expect(() => apiReferences()).toThrow(/fel/);
  });
});
