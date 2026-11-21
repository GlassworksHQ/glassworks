// @vitest-environment jsdom
import { act, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DocsLandingTabs } from "./docs/DocsLandingTabs";
import { DocsTree } from "./docs/DocsTree";
import { RecipeList } from "./Recipes";
import type { NavTrack } from "../lib/docs/nav";
import { RECIPES } from "../lib/recipes";

const pathname = vi.hoisted(() => vi.fn(() => "/docs"));
vi.mock("next/navigation", () => ({ usePathname: pathname }));
vi.mock("./Manage", () => ({ manage: { card: "card" } }));

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
  pathname.mockReturnValue("/docs");
  host = document.body.appendChild(document.createElement("div"));
  root = createRoot(host);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const render = (node: ReactNode) => act(() => root.render(node));
const element = <T extends HTMLElement = HTMLElement>(selector: string) => {
  const found = host.querySelector<T>(selector);
  expect(found, selector).not.toBeNull();
  return found!;
};
const button = (label: string) => {
  const found = [...host.querySelectorAll("button")].find((node) =>
    node.getAttribute("aria-label") === label || node.textContent?.trim() === label,
  );
  expect(found, label).toBeDefined();
  return found!;
};
const click = (node: HTMLElement) => act(() => node.click());

describe("documentation navigation", () => {
  it("selects the research track for device notes and resets a manual choice on navigation", () => {
    const tracks: NavTrack[] = [
      { id: "user", label: "User Guide", shortLabel: "Users", groups: [] },
      { id: "developers", label: "Developers", shortLabel: "Developers", groups: [
        { label: "Architecture", items: [{ label: "Libraries", href: "/docs/libraries", children: [
          { label: "Internal API", href: "/docs/libraries/fixture/api", plain: true, deep: true },
        ] }] },
      ] },
      { id: "notes", label: "Hardware Research", shortLabel: "Research", groups: [
        { label: "By device", items: [{ label: "Fixture", href: "/docs/frames/fixture/research", also: "/docs/frames/fixture/notes/" }] },
      ] },
    ];
    pathname.mockReturnValue("/docs/frames/fixture/notes/display");
    render(<DocsTree tracks={tracks} />);
    expect(button("Research").getAttribute("aria-selected")).toBe("true");
    expect(element('a[href="/docs/frames/fixture/research"]').getAttribute("aria-current")).toBe("true");
    click(button("Users"));
    expect(button("Users").getAttribute("aria-selected")).toBe("true");

    pathname.mockReturnValue("/docs/libraries/fixture/api");
    render(<DocsTree tracks={tracks} />);
    expect(button("Developers").getAttribute("aria-selected")).toBe("true");
    expect(element('a[aria-current="page"]').textContent).toBe("Internal API");
  });

  it("defaults to user guides, then reveals every section when All docs is selected", () => {
    render(<DocsLandingTabs user={<span id="guide" />} developers={<span id="dev" />} research={<span id="notes" />} />);
    expect(button("User Guide").getAttribute("aria-selected")).toBe("true");
    expect(element("#dev").closest("[hidden]")).not.toBeNull();
    click(button("Developers"));
    expect(element("#guide").closest("[hidden]")).not.toBeNull();
    expect(element("#dev").closest("[hidden]")).toBeNull();
    click(button("All docs"));
    expect(host.querySelector("[hidden]")).toBeNull();
  });
});

describe("dashboard prompt controls", () => {
  it.each([true, false])("handles clipboard success=%s and restores the copy button", async (succeeds) => {
    vi.useFakeTimers();
    const writeText = vi.fn(async () => {
      if (!succeeds) throw new Error("Clipboard permission denied");
    });
    vi.stubGlobal("navigator", Object.assign(Object.create(window.navigator), { clipboard: { writeText } }));
    const recipe = RECIPES[0];
    render(<RecipeList recipes={[recipe]} compact />);
    await act(async () => button("Copy prompt").click());

    expect(writeText).toHaveBeenCalledExactlyOnceWith(recipe.prompt);
    expect(button(succeeds ? "Copied prompt ✓" : "Expand & copy manually")).toBeDefined();
    expect(element("details pre").textContent).toBe(recipe.prompt);
    act(() => vi.runOnlyPendingTimers());
    expect(button("Copy prompt")).toBeDefined();
  });
});
