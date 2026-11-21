import type { KeyboardEvent } from "react";

/**
 * The keys of a WAI-ARIA tab list (https://www.w3.org/WAI/ARIA/apg/patterns/tabs/): Left and Right move to the
 * previous and next tab, wrapping, and Home and End to the first and last; the tab reached is selected and
 * focused. Give only the selected tab tabIndex 0, so Tab leaves the list.
 */
export function onTabKey<T>(e: KeyboardEvent<HTMLElement>, ids: readonly T[], current: T, select: (id: T) => void) {
  const n = ids.length;
  const i = ids.indexOf(current);
  const to =
    e.key === "ArrowRight" ? (i + 1) % n : e.key === "ArrowLeft" ? (i - 1 + n) % n : e.key === "Home" ? 0 : e.key === "End" ? n - 1 : -1;
  if (to < 0) return;
  e.preventDefault();
  select(ids[to]!);
  e.currentTarget.closest('[role="tablist"]')?.querySelectorAll<HTMLElement>('[role="tab"]')[to]?.focus();
}
